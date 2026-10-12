package net.neristal.rrbridge;

import com.romraider.logger.ecu.EcuLogger;
import com.romraider.logger.ecu.comms.query.Response;
import com.romraider.logger.ecu.definition.LoggerData;
import com.romraider.logger.ecu.ui.handler.DataUpdateHandler;

import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ロガー内部のデータ配信（AsyncDataUpdateHandler）に自前のハンドラーを追加し、
 * 1応答=1サンプルとして ①ローカルspoolへ追記 ②HTTPで転送先へ送る。
 * spoolが正本。送信済み位置を記録し、送信失敗時は復旧後に続きから再送する（受信側は session+seq で重複排除）。
 */
public final class Stream implements DataUpdateHandler {
    static final Stream INSTANCE = new Stream();

    final String session = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    final AtomicLong seq = new AtomicLong();
    final AtomicLong produced = new AtomicLong(), sentLines = new AtomicLong(), sendErrors = new AtomicLong();
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(200000);
    volatile int segment = 0;
    volatile String currentIds = "";
    volatile String sinkUrl = "http://127.0.0.1:18766/ingest", sinkToken = "";
    volatile String lastSendResult = "", lastSendAt = "";
    volatile long lastSampleT = 0, lastSentT = 0;
    volatile boolean installed = false;
    File spoolDir;

    // ---------- 取り付け ----------
    synchronized String install(EcuLogger lg, File baseDir) {
        spoolDir = new File(baseDir, "spool");
        spoolDir.mkdirs();
        loadSinkConfig(new File(baseDir, "sink.properties"));
        try {
            Object controller = field(lg, "controller");
            Object qm = field(controller, "queryManager");
            DataUpdateHandler[] hs = (DataUpdateHandler[]) field(qm, "updateHandlers");
            for (DataUpdateHandler h : hs) if (h == this) { installed = true; return "already installed"; }
            DataUpdateHandler[] n = Arrays.copyOf(hs, hs.length + 1);
            n[hs.length] = this;
            setField(qm, "updateHandlers", n);
            // 既にロギング中なら稼働中の配信スレッドにも差し込む
            Object du = field(qm, "dataUpdater");
            if (du != null) setField(du, "handlers", n);
            installed = true;
            Thread t = new Thread(new Runnable() { public void run() { writerLoop(); } }, "RRBridge-spool");
            t.setDaemon(true); t.start();
            Thread s = new Thread(new Runnable() { public void run() { senderLoop(); } }, "RRBridge-sender");
            s.setDaemon(true); s.start();
            return "installed (handlers=" + n.length + ", session=" + session + ", sink=" + sinkUrl + ")";
        } catch (Throwable e) {
            return "install failed: " + e;
        }
    }

    void loadSinkConfig(File f) {
        if (!f.isFile()) return;
        Properties p = new Properties();
        try { InputStream in = new FileInputStream(f); try { p.load(in); } finally { in.close(); } } catch (IOException ignore) {}
        if (p.getProperty("url") != null) sinkUrl = p.getProperty("url").trim();
        if (p.getProperty("token") != null) sinkToken = p.getProperty("token").trim();
    }

    // ---------- DataUpdateHandler（RomRaiderの配信スレッドから呼ばれる。重い処理はしない） ----------
    public void registerData(LoggerData d) {}
    public void deregisterData(LoggerData d) {}
    public void cleanUp() {}
    public void reset() {}

    public void handleDataUpdate(Response r) {
        try {
            List<LoggerData> ds = new ArrayList<LoggerData>(r.getData());
            Collections.sort(ds, new Comparator<LoggerData>() {
                public int compare(LoggerData a, LoggerData b) { return a.getId().compareTo(b.getId()); }
            });
            StringBuilder ids = new StringBuilder();
            for (LoggerData d : ds) ids.append(d.getId()).append(',');
            if (!ids.toString().equals(currentIds)) {
                currentIds = ids.toString();
                segment++;
                for (LoggerData d : ds)
                    offer("M\t" + session + "\t" + segment + "\t" + clean(d.getId()) + "\t" + clean(d.getName()) + "\t" + clean(d.getSelectedConvertor().getUnits()));
            }
            StringBuilder v = new StringBuilder();
            for (LoggerData d : ds) {
                if (v.length() > 0) v.append(',');
                v.append(clean(d.getId())).append('=').append(r.getDataValue(d));
            }
            lastSampleT = r.getTimestamp();
            offer("S\t" + session + "\t" + segment + "\t" + seq.incrementAndGet() + "\t" + r.getTimestamp() + "\t" + v);
        } catch (Throwable ignore) {}
    }

    void event(String text) {
        offer("E\t" + session + "\t" + segment + "\t" + System.currentTimeMillis() + "\t" + clean(text));
    }

    void offer(String line) { if (queue.offer(line)) produced.incrementAndGet(); }

    static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(',', ' ').replace('=', ' '); }

    // ---------- spool書込み ----------
    File spoolFile() { return new File(spoolDir, "rr_" + session + ".tsv"); }

    void writerLoop() {
        List<String> buf = new ArrayList<String>();
        while (true) {
            try {
                buf.clear();
                String first = queue.take();
                buf.add(first);
                queue.drainTo(buf, 5000);
                Writer w = new OutputStreamWriter(new FileOutputStream(spoolFile(), true), StandardCharsets.UTF_8);
                try { for (String s : buf) { w.write(s); w.write('\n'); } } finally { w.close(); }
            } catch (Throwable e) { Agent.log("spool write error " + e); sleep(500); }
        }
    }

    // ---------- 転送（spoolの未送信分を送る） ----------
    void senderLoop() {
        while (true) {
            try {
                boolean more = false;
                File[] fs = spoolDir.listFiles(new FilenameFilter() { public boolean accept(File d, String n) { return n.endsWith(".tsv"); } });
                if (fs != null) { Arrays.sort(fs); for (File f : fs) more |= sendSome(f); }
                if (!more) sleep(500);
            } catch (Throwable e) { sendErrors.incrementAndGet(); lastSendResult = e.toString(); sleep(2000); }
        }
    }

    /** @return まだ未送信が残っていれば true */
    boolean sendSome(File f) throws IOException {
        File off = new File(f.getPath() + ".sent");
        long pos = readLong(off);
        long len = f.length();
        if (pos >= len) return false;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] chunk;
        try {
            raf.seek(pos);
            int n = (int) Math.min(len - pos, 512 * 1024);
            chunk = new byte[n];
            raf.readFully(chunk);
        } finally { raf.close(); }
        int end = chunk.length;
        while (end > 0 && chunk[end - 1] != '\n') end--;   // 行の途中は送らない
        if (end == 0) return false;
        byte[] body = Arrays.copyOf(chunk, end);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(sinkUrl).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(10000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/tab-separated-values; charset=utf-8");
        if (sinkToken.length() > 0) c.setRequestProperty("Authorization", "Bearer " + sinkToken);
        OutputStream o = c.getOutputStream(); o.write(body); o.close();
        int code = c.getResponseCode();
        String resp = read(code < 400 ? c.getInputStream() : c.getErrorStream());
        lastSendAt = Agent.now();
        lastSendResult = code + " " + resp.trim() + " (" + (System.currentTimeMillis() - t0) + "ms, " + body.length + "B)";
        if (code / 100 != 2) { sendErrors.incrementAndGet(); sleep(2000); return false; }
        int lines = 0; for (int i = 0; i < end; i++) if (body[i] == '\n') lines++;
        sentLines.addAndGet(lines);
        lastSentT = lastSampleT;
        writeLong(off, pos + end);
        return pos + end < len;
    }

    String statusJson() {
        long now = System.currentTimeMillis();
        return "{\"installed\":" + installed + ",\"session\":" + Agent.q(session) + ",\"segment\":" + segment
                + ",\"samples\":" + seq.get() + ",\"linesProduced\":" + produced.get() + ",\"linesSent\":" + sentLines.get()
                + ",\"queue\":" + queue.size() + ",\"sendErrors\":" + sendErrors.get()
                + ",\"sink\":" + Agent.q(sinkUrl) + ",\"lastSendAt\":" + Agent.q(lastSendAt) + ",\"lastSendResult\":" + Agent.q(lastSendResult)
                + ",\"lastSampleAgeMs\":" + (lastSampleT == 0 ? "null" : String.valueOf(now - lastSampleT)) + "}";
    }

    /** ECU無しで経路を確認するための合成データ（params は TEST_ で始まるIDで区別） */
    String selftest(final int count, final int hz) {
        Thread t = new Thread(new Runnable() { public void run() {
            final String[] ids = {"TEST_RPM", "TEST_TPS", "TEST_MRP"};
            final String[] names = {"Selftest Engine Speed", "Selftest Throttle", "Selftest Manifold Rel Pressure"};
            final String[] units = {"rpm", "%", "kPa"};
            segment++;
            int seg = segment;
            currentIds = "SELFTEST";
            for (int i = 0; i < ids.length; i++) offer("M\t" + session + "\t" + seg + "\t" + ids[i] + "\t" + names[i] + "\t" + units[i]);
            event("selftest start count=" + count + " hz=" + hz);
            for (int i = 0; i < count; i++) {
                long ts = System.currentTimeMillis();
                double rpm = 800 + 2500 * (1 + Math.sin(i / 10.0)) / 2, tps = 5 + 40 * (1 + Math.sin(i / 10.0)) / 2, mrp = -60 + 120 * (1 + Math.sin(i / 10.0)) / 2;
                lastSampleT = ts;
                offer("S\t" + session + "\t" + seg + "\t" + seq.incrementAndGet() + "\t" + ts + "\tTEST_RPM=" + Math.round(rpm) + ",TEST_TPS=" + Math.round(tps * 100) / 100.0 + ",TEST_MRP=" + Math.round(mrp));
                sleep(1000 / Math.max(1, hz));
            }
            event("selftest end");
        } }, "RRBridge-selftest");
        t.setDaemon(true); t.start();
        return "selftest started";
    }

    // ---------- util ----------
    static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static void setField(Object o, String name, Object v) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(o, v); return; } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    static long readLong(File f) { try { BufferedReader r = new BufferedReader(new FileReader(f)); try { return Long.parseLong(r.readLine().trim()); } finally { r.close(); } } catch (Exception e) { return 0; } }
    static void writeLong(File f, long v) { Agent.writeAtomic(f, String.valueOf(v)); }
    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
}
