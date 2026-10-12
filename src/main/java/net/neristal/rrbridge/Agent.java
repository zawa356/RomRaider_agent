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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RRBridge PoC: RomRaider Logger と同じJVMで動く -javaagent。
 * RomRaider本体は改造しない。公開されている EcuLogger のメソッドだけを呼ぶ。
 * 公開する操作は「ロギング開始/停止」「CSV記録開始/停止」「許可フォルダ内のプロファイル読込」「状態取得」のみ。
 * ECUリセット・DTC・ROM書込などには触れない。
 */
public final class Agent {
    static final String VERSION = "0.3.0";
    static File baseDir, inbox, outbox, profilesDir, statusFile, logFile;
    static int port = 18765;
    static final AtomicReference<String> lastCommand = new AtomicReference<String>("");

    public static void premain(String args, Instrumentation inst) {
        String dir = System.getProperty("rrbridge.dir");
        if (dir == null) dir = System.getProperty("user.home") + "/Documents/OpenPortWork/RRBridge";
        baseDir = new File(dir);
        inbox = new File(baseDir, "inbox");
        outbox = new File(baseDir, "outbox");
        profilesDir = new File(baseDir, "profiles");
        statusFile = new File(baseDir, "status.json");
        logFile = new File(baseDir, "rrbridge.log");
        inbox.mkdirs(); outbox.mkdirs(); profilesDir.mkdirs();
        String p = System.getProperty("rrbridge.port");
        if (p != null) port = Integer.parseInt(p);
        Thread t = new Thread(new Runnable() { public void run() { mainLoop(); } }, "RRBridge");
        t.setDaemon(true);
        t.start();
        log("agent loaded v" + VERSION + " dir=" + baseDir.getAbsolutePath());
    }

    static EcuLogger logger() { return EcuLogger.getEcuLoggerWithoutCreation(); }

    static void mainLoop() {
        try {
            while (logger() == null) Thread.sleep(500);
            Thread.sleep(3000); // GUI構築完了待ち
            log("EcuLogger instance found");
            log("stream " + Stream.INSTANCE.install(logger(), baseDir));
            startHttp();
            long lastStatus = 0;
            while (true) {
                File[] cmds = inbox.listFiles(new FilenameFilter() {
                    public boolean accept(File d, String n) { return n.endsWith(".cmd"); }
                });
                if (cmds != null) {
                    Arrays.sort(cmds);
                    for (File f : cmds) handleFile(f);
                }
                if (System.currentTimeMillis() - lastStatus > 1000) {
                    writeAtomic(statusFile, statusJson());
                    lastStatus = System.currentTimeMillis();
                }
                Thread.sleep(200);
            }
        } catch (Throwable e) {
            log("mainLoop died: " + e);
        }
    }

    static void handleFile(File f) {
        Properties p = new Properties();
        try {
            InputStream in = new FileInputStream(f);
            try { p.load(in); } finally { in.close(); }
        } catch (IOException e) { log("read fail " + f + " " + e); }
        Map<String, String> m = new HashMap<String, String>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        String result = execute(m);
        String base = f.getName().replaceAll("\\.cmd$", "");
        writeAtomic(new File(outbox, base + ".json"), result);
        if (!f.delete()) f.renameTo(new File(outbox, f.getName() + ".done"));
    }

    /** 全コマンドの入口（ファイル経由・HTTP経由共通） */
    static synchronized String execute(Map<String, String> a) {
        final String cmd = a.get("cmd") == null ? "" : a.get("cmd").trim();
        lastCommand.set(cmd + " @" + now());
        log("cmd " + a);
        try {
            final EcuLogger lg = logger();
            if (lg == null) return err(cmd, "logger not ready");
            if (cmd.equals("status")) return statusJson();
            if (cmd.equals("logging_start")) { onEdt(new Runnable() { public void run() { lg.startLogging(); } }); Stream.INSTANCE.event("logging_start"); return ok(cmd); }
            if (cmd.equals("logging_stop"))  { onEdt(new Runnable() { public void run() { lg.stopLogging(); } });  Stream.INSTANCE.event("logging_stop"); return ok(cmd); }
            if (cmd.equals("filelog_start")) return setFileLog(cmd, true);
            if (cmd.equals("filelog_stop"))  return setFileLog(cmd, false);
            if (cmd.equals("stream_status")) return "{\"ok\":true,\"cmd\":\"stream_status\",\"stream\":" + Stream.INSTANCE.statusJson() + "}";
            if (cmd.equals("selftest_stream")) {
                int n = 40, hz = 8;
                try { if (a.get("count") != null) n = Math.min(10000, Integer.parseInt(a.get("count"))); if (a.get("hz") != null) hz = Math.min(50, Integer.parseInt(a.get("hz"))); } catch (NumberFormatException e) { return err(cmd, "bad number"); }
                return "{\"ok\":true,\"cmd\":\"selftest_stream\",\"result\":" + q(Stream.INSTANCE.selftest(n, hz)) + "}";
            }
            if (cmd.equals("switch_profile")) {
                // CSV記録中にプロファイルを差し替えると同一CSV内にヘッダー行が多重に書かれるため、
                // 記録中なら「記録停止→読込→記録再開」で新しいCSVに分ける。
                File pf = profileFile(a.get("profile"));
                if (pf == null) return err(cmd, "profile must be an existing file name in profiles dir");
                boolean wasFile = fileButton() != null && fileButton().isSelected();
                if (wasFile) { String r = setFileLog("filelog_stop", false); if (r.startsWith("{\"ok\":false")) return r; }
                if (!load(lg, pf)) return err(cmd, "loadUserProfile returned false (see rr_system.log)");
                Stream.INSTANCE.event("switch_profile " + pf.getName());
                if (wasFile && lg.isLogging()) { Thread.sleep(1500); String r = setFileLog("filelog_start", true); if (r.startsWith("{\"ok\":false")) return r; }
                return ok(cmd);
            }
            if (cmd.equals("load_profile")) {
                String name = a.get("profile");
                if (name == null || !name.matches("[A-Za-z0-9_.-]+\\.xml")) return err(cmd, "profile must be a file name like basic.xml");
                final File pf = new File(profilesDir, name);
                if (!pf.isFile()) return err(cmd, "not found in profiles dir: " + name);
                final boolean[] r = new boolean[1];
                onEdt(new Runnable() { public void run() { r[0] = lg.loadUserProfile(pf.getAbsolutePath()); } });
                return r[0] ? ok(cmd) : err(cmd, "loadUserProfile returned false (see rr_system.log)");
            }
            return err(cmd, "unknown command. allowed: status, logging_start, logging_stop, filelog_start, filelog_stop, load_profile, switch_profile, stream_status, selftest_stream");
        } catch (Throwable e) {
            return err(cmd, e.toString());
        }
    }

    static File profileFile(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+\\.xml")) return null;
        File pf = new File(profilesDir, name);
        return pf.isFile() ? pf : null;
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
        if (b.isSelected() == on) { Stream.INSTANCE.event((on ? "filelog_start " : "filelog_stop ") + lg_title()); return ok(cmd); }
        return err(cmd, "state did not change");
    }

    static String lg_title() { try { return logger().getTitle(); } catch (Throwable e) { return ""; } }

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
        kv(sb, "time", now()); sb.append(',');
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
                sb.append(','); kv(sb, "modified", fmt(new Date(newest.lastModified())));
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
                    Map<String, String> q = new HashMap<String, String>();
                    String raw = ex.getRequestURI().getRawQuery();
                    if (raw != null) for (String kv : raw.split("&")) {
                        int i = kv.indexOf('=');
                        if (i > 0) q.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                    }
                    String path = ex.getRequestURI().getPath().replaceAll("^/+", "");
                    if (!path.isEmpty()) q.put("cmd", path);
                    if (!"status".equals(q.get("cmd")) && !"POST".equals(ex.getRequestMethod())) {
                        reply(ex, 405, err(q.get("cmd"), "use POST for commands"));
                        return;
                    }
                    reply(ex, 200, execute(q));
                }
            });
            s.start();
            log("http listening on 127.0.0.1:" + port);
        } catch (Throwable e) { log("http failed: " + e); }
    }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        OutputStream o = ex.getResponseBody(); o.write(b); o.close();
    }

    static String ok(String cmd) { return "{\"ok\":true,\"cmd\":" + q(cmd) + ",\"status\":" + statusJson() + "}"; }
    static String err(String cmd, String msg) { return "{\"ok\":false,\"cmd\":" + q(cmd) + ",\"error\":" + q(msg) + "}"; }
    static void kv(StringBuilder sb, String k, String v) { sb.append(q(k)).append(':').append(q(v)); }
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    static String now() { return fmt(new Date()); }
    static String fmt(Date d) { return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(d); }

    static void writeAtomic(File f, String s) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            try { w.write(s); } finally { w.close(); }
            if (f.exists() && !f.delete()) { /* Windowsで読取中なら次回 */ }
            tmp.renameTo(f);
        } catch (IOException e) { /* ignore */ }
    }

    static synchronized void log(String s) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            try { w.write(now() + " " + s + "\r\n"); } finally { w.close(); }
        } catch (Throwable ignore) {}
    }
}
