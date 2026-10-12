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
 * ロガー内部のデータ配信（QueryManagerImpl.updateHandlers → AsyncDataUpdateHandler）に自前のハンドラーを追加し、
 * 1応答=1サンプルとして ①ローカルspoolへ追記 ②HTTPで転送先へ送る。
 * spoolが正本。送信済み位置を記録し、送信失敗時は復旧後に続きから再送する（受信側は session+seq で重複排除）。
 *
 * 転送形式（UTF-8、タブ区切り、LF）:
 *   M session segment param_id name units
 *   S session segment seq epoch_ms id=value,id=value,...
 *   E session segment epoch_ms text
 */
public final class Stream implements DataUpdateHandler {
    static final Stream INSTANCE = new Stream();
    static final int QUEUE_CAPACITY = 200000;
    static final int MAX_POST_BYTES = 512 * 1024;

    final String session = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    final AtomicLong seq = new AtomicLong();
    final AtomicLong produced = new AtomicLong(), dropped = new AtomicLong(), sentLines = new AtomicLong(), sendErrors = new AtomicLong();
    final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>(QUEUE_CAPACITY);
    volatile int segment = 0;
    volatile String currentIds = "";
    volatile String sinkUrl = "http://127.0.0.1:18766/ingest", sinkToken = "";
    volatile String lastSendResult = "", lastSendAt = "", installResult = "";
    volatile long lastSampleT = 0;
    volatile long retentionDays = 14;
    volatile boolean installed = false, threadsStarted = false;
    File spoolDir;

    // ---------- 取り付け ----------
    synchronized String install(EcuLogger lg, File baseDir) {
        startThreads(new File(baseDir, "spool"), new File(baseDir, "sink.properties"));
        try {
            Object controller = field(lg, "controller");
            Object qm = field(controller, "queryManager");
            DataUpdateHandler[] hs = (DataUpdateHandler[]) field(qm, "updateHandlers");
            for (DataUpdateHandler h : hs) if (h == this) { installed = true; return installResult = "already installed"; }
            DataUpdateHandler[] n = Arrays.copyOf(hs, hs.length + 1);
            n[hs.length] = this;
            setField(qm, "updateHandlers", n);
            // 既にロギング中なら稼働中の配信スレッドにも差し込む（通常はロギング開始のたびに updateHandlers から作り直される）
            Object du = field(qm, "dataUpdater");
            if (du != null) setField(du, "handlers", n);
            installed = true;
            return installResult = "installed (handlers=" + n.length + ", session=" + session + ", sink=" + (sinkUrl.isEmpty() ? "(spool only)" : sinkUrl) + ")";
        } catch (Throwable e) {
            return installResult = "install failed: " + e;
        }
    }

    /** spool書込み・送信スレッドを開始する（RomRaider無しの試験からも呼べる） */
    synchronized void startThreads(File spool, File sinkConfig) {
        if (threadsStarted) return;
        spoolDir = spool;
        spoolDir.mkdirs();
        Properties p = Util.loadProps(sinkConfig);
        if (p.getProperty("url") != null) sinkUrl = p.getProperty("url").trim();
        if (p.getProperty("token") != null) sinkToken = p.getProperty("token").trim();
        Thread t = new Thread(new Runnable() { public void run() { writerLoop(); } }, "RRBridge-spool");
        t.setDaemon(true); t.start();
        Thread s = new Thread(new Runnable() { public void run() { senderLoop(); } }, "RRBridge-sender");
        s.setDaemon(true); s.start();
        threadsStarted = true;
    }

    // ---------- DataUpdateHandler（RomRaiderの配信スレッドから呼ばれる。重い処理・I/O・例外送出をしない） ----------
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
                    offer("M\t" + session + "\t" + segment + "\t" + cleanId(d.getId()) + "\t" + cleanField(d.getName()) + "\t" + cleanField(d.getSelectedConvertor().getUnits()));
            }
            StringBuilder v = new StringBuilder();
            for (LoggerData d : ds) {
                if (v.length() > 0) v.append(',');
                v.append(cleanId(d.getId())).append('=').append(r.getDataValue(d));
            }
            lastSampleT = r.getTimestamp();
            offer("S\t" + session + "\t" + segment + "\t" + seq.incrementAndGet() + "\t" + r.getTimestamp() + "\t" + v);
        } catch (Throwable ignore) {}
    }

    void event(String text) {
        offer("E\t" + session + "\t" + segment + "\t" + System.currentTimeMillis() + "\t" + cleanField(text));
    }

    void offer(String line) {
        if (queue.offer(line)) produced.incrementAndGet(); else dropped.incrementAndGet();
    }

    /** M行の名称・単位、E行の本文: 列区切り（タブ）と行区切りだけを除く */
    static String cleanField(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    /** S行に入るID: 値リストの区切り（, =）も除く */
    static String cleanId(String s) { return cleanField(s).replace(',', '_').replace('=', '_'); }

    // ---------- spool書込み ----------
    File spoolFile() { return new File(spoolDir, "rr_" + session + ".tsv"); }

    void writerLoop() {
        List<String> buf = new ArrayList<String>();
        while (true) {
            try {
                buf.clear();
                buf.add(queue.take());
                queue.drainTo(buf, 5000);
                Writer w = new OutputStreamWriter(new FileOutputStream(spoolFile(), true), StandardCharsets.UTF_8);
                try { for (String s : buf) { w.write(s); w.write('\n'); } } finally { w.close(); }
            } catch (Throwable e) { Agent.log("spool write error " + e); sleep(500); }
        }
    }

    // ---------- 転送（spoolの未送信分を送る） ----------
    void senderLoop() {
        long lastCleanup = 0;
        while (true) {
            try {
                boolean more = false;
                if (!sinkUrl.isEmpty()) {
                    File[] fs = spoolFiles();
                    for (File f : fs) more |= sendSome(f);
                }
                if (System.currentTimeMillis() - lastCleanup > 10 * 60 * 1000L) {
                    cleanupSpool(retentionDays, System.currentTimeMillis());
                    lastCleanup = System.currentTimeMillis();
                }
                if (!more) sleep(500);
            } catch (Throwable e) { sendErrors.incrementAndGet(); lastSendResult = e.toString(); sleep(2000); }
        }
    }

    File[] spoolFiles() {
        File[] fs = spoolDir.listFiles(new FilenameFilter() { public boolean accept(File d, String n) { return n.endsWith(".tsv"); } });
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
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
            int n = (int) Math.min(len - pos, MAX_POST_BYTES);
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
        String resp = Util.read(code < 400 ? c.getInputStream() : c.getErrorStream());
        lastSendAt = Util.now();
        lastSendResult = code + " " + resp.trim() + " (" + (System.currentTimeMillis() - t0) + "ms, " + body.length + "B)";
        if (code / 100 != 2) { sendErrors.incrementAndGet(); sleep(2000); return false; }
        int lines = 0; for (int i = 0; i < end; i++) if (body[i] == '\n') lines++;
        sentLines.addAndGet(lines);
        writeLong(off, pos + end);
        return pos + end < len;
    }

    /** 送信済み（.sent が末尾まで）かつ最終更新が保持日数より古いspoolを削除する。現在のsessionは消さない */
    int cleanupSpool(long days, long nowMs) {
        if (days <= 0 || spoolDir == null) return 0;
        int deleted = 0;
        for (File f : spoolFiles()) {
            if (f.equals(spoolFile())) continue;
            File off = new File(f.getPath() + ".sent");
            boolean fullySent = readLong(off) >= f.length();
            boolean old = nowMs - f.lastModified() > days * 24L * 3600 * 1000;
            if (fullySent && old && f.delete()) { off.delete(); deleted++; }
        }
        if (deleted > 0) Agent.log("spool cleanup: deleted " + deleted + " fully-sent file(s) older than " + days + " days");
        return deleted;
    }

    long pendingBytes() {
        long total = 0;
        if (spoolDir == null) return 0;
        for (File f : spoolFiles()) total += Math.max(0, f.length() - readLong(new File(f.getPath() + ".sent")));
        return total;
    }

    String statusJson() {
        long now = System.currentTimeMillis();
        return "{\"installed\":" + installed + ",\"installResult\":" + Util.q(installResult)
                + ",\"session\":" + Util.q(session) + ",\"segment\":" + segment
                + ",\"samples\":" + seq.get() + ",\"linesProduced\":" + produced.get() + ",\"linesDropped\":" + dropped.get()
                + ",\"linesSent\":" + sentLines.get() + ",\"queue\":" + queue.size() + ",\"pendingBytes\":" + pendingBytes()
                + ",\"sendErrors\":" + sendErrors.get()
                + ",\"sink\":" + Util.q(sinkUrl.isEmpty() ? "(spool only)" : sinkUrl) + ",\"lastSendAt\":" + Util.q(lastSendAt) + ",\"lastSendResult\":" + Util.q(lastSendResult)
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
                double w = (1 + Math.sin(i / 10.0)) / 2;
                lastSampleT = ts;
                offer("S\t" + session + "\t" + seg + "\t" + seq.incrementAndGet() + "\t" + ts + "\tTEST_RPM=" + Math.round(800 + 2500 * w)
                        + ",TEST_TPS=" + Math.round((5 + 40 * w) * 100) / 100.0 + ",TEST_MRP=" + Math.round(-60 + 120 * w));
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
    static long readLong(File f) {
        try { BufferedReader r = new BufferedReader(new FileReader(f)); try { return Long.parseLong(r.readLine().trim()); } finally { r.close(); } }
        catch (Exception e) { return 0; }
    }
    static void writeLong(File f, long v) { Util.writeAtomic(f, String.valueOf(v)); }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) {} }
}
