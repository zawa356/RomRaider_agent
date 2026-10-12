package net.neristal.rrbridge;

import com.romraider.logger.ecu.EcuLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RRBridge: RomRaider Logger と同じJVMで動く -javaagent。
 * RomRaider本体は改造しない。公開メソッドを優先し、必要最小限の private フィールドだけリフレクションで扱う。
 * 公開する操作は「状態取得」「ロギング開始/停止」「CSV記録開始/停止」「許可フォルダ内のプロファイル読込」「転送の状態・試験」のみ。
 * ECUリセット・DTC・ROM読み書き・学習値リセットなど車両に影響する操作には触れない。
 *
 * 設定（任意）: <rrbridge.dir>/rrbridge.properties
 *   http.token=        設定するとHTTPの全リクエストに Authorization: Bearer <token> を要求
 *   switch.settleMs=1500   switch_profile で読込後CSV記録を再開するまでの待ち
 *   log.maxBytes=5242880   rrbridge.log の上限（超えたら rrbridge.log.1 に退避）
 *   spool.retentionDays=14 送信済みspoolを残す日数（0以下で削除しない）
 */
public final class Agent {
    static final String VERSION = "0.4.0";
    static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_.-]+\\.xml");
    /** 読み取り専用コマンド（排他ロック不要、HTTPでGET可） */
    static final Set<String> READ_ONLY = new HashSet<String>(Arrays.asList("status", "stream_status", "list_profiles"));
    static final String ALLOWED = "status, stream_status, list_profiles, logging_start, logging_stop, filelog_start, filelog_stop, load_profile, switch_profile, selftest_stream";

    static File baseDir, inbox, outbox, profilesDir, statusFile, logFile;
    static int port = 18765;
    static String httpToken = "";
    static long settleMs = 1500, logMaxBytes = 5L * 1024 * 1024;
    static final AtomicReference<String> lastCommand = new AtomicReference<String>("");
    static final Object CONTROL_LOCK = new Object();

    public static void premain(String args, Instrumentation inst) {
        String dir = System.getProperty("rrbridge.dir");
        if (dir == null) dir = System.getProperty("user.home") + "/Documents/OpenPortWork/RRBridge";
        init(new File(dir));
        String p = System.getProperty("rrbridge.port");
        if (p != null) port = Integer.parseInt(p);
        Thread t = new Thread(new Runnable() { public void run() { mainLoop(); } }, "RRBridge");
        t.setDaemon(true);
        t.start();
        log("agent loaded v" + VERSION + " dir=" + baseDir.getAbsolutePath()
                + " httpAuth=" + (httpToken.isEmpty() ? "off" : "on") + " settleMs=" + settleMs);
    }

    /** ディレクトリと設定の初期化（試験からも呼ぶ） */
    static void init(File dir) {
        baseDir = dir;
        inbox = new File(baseDir, "inbox");
        outbox = new File(baseDir, "outbox");
        profilesDir = new File(baseDir, "profiles");
        statusFile = new File(baseDir, "status.json");
        logFile = new File(baseDir, "rrbridge.log");
        inbox.mkdirs(); outbox.mkdirs(); profilesDir.mkdirs();
        Properties c = Util.loadProps(new File(baseDir, "rrbridge.properties"));
        httpToken = c.getProperty("http.token", "").trim();
        settleMs = Math.max(0, Math.min(10000, Util.longProp(c, "switch.settleMs", 1500)));
        logMaxBytes = Util.longProp(c, "log.maxBytes", logMaxBytes);
        Stream.INSTANCE.retentionDays = Util.longProp(c, "spool.retentionDays", 14);
    }

    static EcuLogger logger() { return EcuLogger.getEcuLoggerWithoutCreation(); }

    static void mainLoop() {
        try {
            while (logger() == null) Thread.sleep(500);
            Thread.sleep(3000); // GUI構築完了待ち
            log("EcuLogger instance found");
            log("stream " + Stream.INSTANCE.install(logger(), baseDir));
            startHttp();
        } catch (Throwable e) {
            log("startup failed: " + e);
        }
        long lastStatus = 0;
        while (true) {
            // 1回の失敗でループ全体が止まらないよう、反復ごとに例外を捕まえる
            try {
                File[] cmds = inbox.listFiles(new FilenameFilter() {
                    public boolean accept(File d, String n) { return n.endsWith(".cmd"); }
                });
                if (cmds != null) {
                    Arrays.sort(cmds);
                    for (File f : cmds) handleFile(f);
                }
                if (System.currentTimeMillis() - lastStatus > 1000) {
                    Util.writeAtomic(statusFile, statusJson());
                    lastStatus = System.currentTimeMillis();
                }
            } catch (Throwable e) {
                log("mainLoop error: " + e);
            }
            sleep(200);
        }
    }

    static void handleFile(File f) {
        Properties p = new Properties();
        try {
            InputStream in = new FileInputStream(f);
            try { p.load(in); } finally { in.close(); }
        } catch (IOException e) { log("read fail " + f + " " + e); }
        Map<String, String> m = new HashMap<String, String>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k).trim());
        String result = execute(m);
        String base = f.getName().replaceAll("\\.cmd$", "");
        Util.writeAtomic(new File(outbox, base + ".json"), result);
        if (!f.delete()) f.renameTo(new File(outbox, f.getName() + ".done"));
    }

    /** 全コマンドの入口（ファイル経由・HTTP経由共通）。読み取り系はロックなし、操作系は1つずつ実行 */
    static String execute(Map<String, String> a) {
        final String cmd = a.get("cmd") == null ? "" : a.get("cmd").trim();
        try {
            if (READ_ONLY.contains(cmd)) return executeReadOnly(cmd);
            synchronized (CONTROL_LOCK) {
                lastCommand.set(cmd + " @" + Util.now());
                log("cmd " + a);
                return executeControl(cmd, a);
            }
        } catch (Throwable e) {
            log("cmd " + cmd + " failed: " + e);
            return err(cmd, e.toString());
        }
    }

    static String executeReadOnly(String cmd) {
        if (cmd.equals("status")) return statusJson();
        if (cmd.equals("stream_status")) return "{\"ok\":true,\"cmd\":\"stream_status\",\"stream\":" + Stream.INSTANCE.statusJson() + "}";
        if (cmd.equals("list_profiles")) return listProfilesJson();
        return err(cmd, "unknown");
    }

    static String executeControl(final String cmd, Map<String, String> a) throws Exception {
        final EcuLogger lg = logger();
        if (lg == null) return err(cmd, "logger not ready");
        if (cmd.equals("logging_start")) {
            onEdt(new Runnable() { public void run() { lg.startLogging(); } });
            Stream.INSTANCE.event("logging_start");
            long waitMs = intArg(a, "wait_ms", 0, 0, 10000);
            if (waitMs > 0 && !waitFor(lg, true, waitMs)) return err(cmd, "logging did not start within " + waitMs + "ms");
            return ok(cmd);
        }
        if (cmd.equals("logging_stop")) {
            // CSV記録中なら先に止める（RomRaiderはロギング停止でCSVも閉じるが、ボタン状態を揃えるため）
            JToggleButton b = fileButton();
            if (b != null && b.isSelected()) setFileLog("filelog_stop", false);
            onEdt(new Runnable() { public void run() { lg.stopLogging(); } });
            Stream.INSTANCE.event("logging_stop");
            long waitMs = intArg(a, "wait_ms", 0, 0, 10000);
            if (waitMs > 0 && !waitFor(lg, false, waitMs)) return err(cmd, "logging did not stop within " + waitMs + "ms");
            return ok(cmd);
        }
        if (cmd.equals("filelog_start")) return setFileLog(cmd, true);
        if (cmd.equals("filelog_stop")) return setFileLog(cmd, false);
        if (cmd.equals("selftest_stream")) {
            int n = (int) intArg(a, "count", 40, 1, 10000);
            int hz = (int) intArg(a, "hz", 8, 1, 50);
            return "{\"ok\":true,\"cmd\":\"selftest_stream\",\"result\":" + Util.q(Stream.INSTANCE.selftest(n, hz)) + "}";
        }
        if (cmd.equals("switch_profile") || cmd.equals("load_profile")) {
            String name = a.get("profile");
            File pf = profileFile(name);
            if (pf == null) return err(cmd, "profile must be an existing file name in profiles dir (pattern " + PROFILE_NAME.pattern() + "): " + name);
            boolean wasFile = cmd.equals("switch_profile") && fileButton() != null && fileButton().isSelected();
            long t0 = System.currentTimeMillis();
            if (wasFile) { String r = setFileLog("filelog_stop", false); if (r.startsWith("{\"ok\":false")) return r; }
            if (!load(lg, pf)) return err(cmd, "loadUserProfile returned false (see rr_system.log)");
            Stream.INSTANCE.event(cmd + " " + pf.getName());
            if (wasFile && lg.isLogging()) {
                sleep(settleMs);
                String r = setFileLog("filelog_start", true);
                if (r.startsWith("{\"ok\":false")) return r;
            }
            log(cmd + " " + pf.getName() + " took " + (System.currentTimeMillis() - t0) + "ms (filelogRestarted=" + wasFile + ")");
            return ok(cmd);
        }
        return err(cmd, "unknown command. allowed: " + ALLOWED);
    }

    static long intArg(Map<String, String> a, String key, long def, long min, long max) {
        String v = a.get(key);
        if (v == null || v.isEmpty()) return def;
        try { return Math.max(min, Math.min(max, Long.parseLong(v))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("bad number for " + key + ": " + v); }
    }

    static boolean waitFor(EcuLogger lg, boolean logging, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (lg.isLogging() == logging) return true;
            sleep(100);
        }
        return lg.isLogging() == logging;
    }

    static File profileFile(String name) {
        if (name == null || !PROFILE_NAME.matcher(name).matches()) return null;
        File pf = new File(profilesDir, name);
        return pf.isFile() ? pf : null;
    }

    /** profiles/ 内のXMLと、選択されている項目（livedata="selected"）のIDを返す */
    static String listProfilesJson() {
        File[] fs = profilesDir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) { return PROFILE_NAME.matcher(n).matches(); }
        });
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"cmd\":\"list_profiles\",\"profiles\":[");
        if (fs != null) {
            Arrays.sort(fs);
            Pattern sel = Pattern.compile("<(parameter|switch|external)\\s+id=\"([^\"]+)\"[^>]*livedata=\"selected\"");
            for (int i = 0; i < fs.length; i++) {
                if (i > 0) sb.append(',');
                List<String> ids = new ArrayList<String>();
                try {
                    String xml = new String(java.nio.file.Files.readAllBytes(fs[i].toPath()), StandardCharsets.ISO_8859_1);
                    Matcher m = sel.matcher(xml);
                    while (m.find()) ids.add(m.group(2));
                } catch (IOException ignore) {}
                sb.append("{\"name\":").append(Util.q(fs[i].getName())).append(",\"selected\":[");
                for (int j = 0; j < ids.size(); j++) { if (j > 0) sb.append(','); sb.append(Util.q(ids.get(j))); }
                sb.append("],\"count\":").append(ids.size()).append('}');
            }
        }
        return sb.append("]}").toString();
    }

    static boolean load(final EcuLogger lg, final File pf) throws Exception {
        final boolean[] r = new boolean[1];
        onEdt(new Runnable() { public void run() { r[0] = lg.loadUserProfile(pf.getAbsolutePath()); } });
        return r[0];
    }

    static String setFileLog(String cmd, final boolean on) throws Exception {
        final JToggleButton b = fileButton();
        if (b == null) return err(cmd, "logToFileButton not accessible");
        if (on && !logger().isLogging()) return err(cmd, "logging is not started");
        onEdt(new Runnable() { public void run() { if (b.isSelected() != on) b.doClick(); } });
        if (b.isSelected() == on) { Stream.INSTANCE.event((on ? "filelog_start " : "filelog_stop ") + title()); return ok(cmd); }
        return err(cmd, "state did not change");
    }

    static String title() { try { return logger().getTitle(); } catch (Throwable e) { return ""; } }

    static JToggleButton fileButton() {
        try {
            Field f = EcuLogger.class.getDeclaredField("logToFileButton");
            f.setAccessible(true);
            return (JToggleButton) f.get(logger());
        } catch (Throwable e) { return null; }
    }

    static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
    }

    static String statusJson() {
        EcuLogger lg = logger();
        StringBuilder sb = new StringBuilder("{");
        kv(sb, "agent", "RRBridge " + VERSION); sb.append(',');
        kv(sb, "time", Util.now()); sb.append(',');
        sb.append("\"ready\":").append(lg != null);
        if (lg != null) {
            JToggleButton b = fileButton();
            sb.append(",\"logging\":").append(lg.isLogging());
            sb.append(",\"fileLogging\":").append(b != null && b.isSelected());
            sb.append(','); kv(sb, "title", lg.getTitle());
            String out = null;
            try { out = lg.getSettings().getLoggerOutputDirPath(); } catch (Throwable ignore) {}
            sb.append(','); kv(sb, "outputDir", out);
            File newest = newestCsv(out);
            if (newest != null) {
                sb.append(",\"newestCsv\":{");
                kv(sb, "name", newest.getName());
                sb.append(",\"bytes\":").append(newest.length());
                sb.append(','); kv(sb, "modified", Util.fmt(new Date(newest.lastModified())));
                sb.append('}');
            }
        }
        sb.append(','); kv(sb, "lastCommand", lastCommand.get());
        sb.append(",\"stream\":").append(Stream.INSTANCE.statusJson());
        return sb.append('}').toString();
    }

    static File newestCsv(String dir) {
        if (dir == null) return null;
        File[] fs = new File(dir).listFiles(new FilenameFilter() {
            public boolean accept(File d, String n) { return n.toLowerCase().endsWith(".csv"); }
        });
        if (fs == null || fs.length == 0) return null;
        File best = fs[0];
        for (File f : fs) if (f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    static void startHttp() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            s.createContext("/", new com.sun.net.httpserver.HttpHandler() {
                public void handle(HttpExchange ex) throws IOException {
                    try {
                        if (!Util.authorized(ex, httpToken)) { Util.reply(ex, 401, err("", "unauthorized")); return; }
                        Map<String, String> q = new HashMap<String, String>();
                        String raw = ex.getRequestURI().getRawQuery();
                        if (raw != null) for (String kv : raw.split("&")) {
                            int i = kv.indexOf('=');
                            if (i > 0) q.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                        }
                        String path = ex.getRequestURI().getPath().replaceAll("^/+", "");
                        if (!path.isEmpty()) q.put("cmd", path);
                        String cmd = q.get("cmd");
                        if (!READ_ONLY.contains(cmd) && !"POST".equals(ex.getRequestMethod())) {
                            Util.reply(ex, 405, err(cmd, "use POST for control commands"));
                            return;
                        }
                        Util.reply(ex, 200, execute(q));
                    } catch (Throwable e) {
                        Util.reply(ex, 500, err("", e.toString()));
                    }
                }
            });
            // 操作中（switch_profile の待機など）でも status を返せるよう複数スレッドで受ける
            s.setExecutor(Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
                public Thread newThread(Runnable r) { Thread t = new Thread(r, "RRBridge-http"); t.setDaemon(true); return t; }
            }));
            s.start();
            log("http listening on 127.0.0.1:" + port);
        } catch (Throwable e) { log("http failed: " + e); }
    }

    static String ok(String cmd) { return "{\"ok\":true,\"cmd\":" + Util.q(cmd) + ",\"status\":" + statusJson() + "}"; }
    static String err(String cmd, String msg) { return "{\"ok\":false,\"cmd\":" + Util.q(cmd) + ",\"error\":" + Util.q(msg) + "}"; }
    static void kv(StringBuilder sb, String k, String v) { sb.append(Util.q(k)).append(':').append(Util.q(v)); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }

    static synchronized void log(String s) {
        if (logFile == null) return;
        try {
            Util.rotateIfLarge(logFile, logMaxBytes);
            Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            try { w.write(Util.now() + " " + s + "\r\n"); } finally { w.close(); }
        } catch (Throwable ignore) {}
    }
}
