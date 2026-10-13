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
    // Reserve space for definitions/events (including overflow diagnostics).
    // This is loss mitigation, NOT durable buffering or a zero-loss guarantee.
    static final int SAMPLE_QUEUE_LIMIT = QUEUE_CAPACITY - 4096;
    static final int MAX_POST_BYTES = 512 * 1024;
    // Bound transient encoding memory while reducing per-row disk write calls.
    static final int WRITE_CHUNK_BYTES = 64 * 1024;

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
    private final Object spoolIoLock = new Object();
    // Writer-only state. A failed append retains the same queued lines for retry.
    // A queue overflow cannot be recovered, so persist an explicit loss marker
    // as soon as the local TSV is writable again.
    private long lastDropMarker = 0;
    private long pendingDropMarker = -1;

    // ---------- 取得速度の計測（実ECUの応答だけを数える。selftest は含めない） ----------
    static final int RING = 512;
    private final long[] ring = new long[RING];
    private int ringPos = 0, ringCount = 0;
    final AtomicLong realSamples = new AtomicLong();
    volatile int currentParamCount = 0;
    volatile long healthIntervalMs = 10000;

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
        Thread h = new Thread(new Runnable() { public void run() { healthLoop(); } }, "RRBridge-health");
        h.setDaemon(true); h.start();
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
            currentParamCount = ds.size();
            recordSample(r.getTimestamp());
            offer("S\t" + session + "\t" + segment + "\t" + seq.incrementAndGet() + "\t" + r.getTimestamp() + "\t" + v);
        } catch (Throwable ignore) {}
    }

    synchronized void recordSample(long t) {
        ring[ringPos] = t;
        ringPos = (ringPos + 1) % RING;
        if (ringCount < RING) ringCount++;
        realSamples.incrementAndGet();
    }

    /** 直近 windowMs（now基準）に届いた実サンプルの時刻（古い順） */
    synchronized long[] recentTimes(long windowMs, long now) {
        long[] tmp = new long[ringCount];
        int n = 0;
        for (int i = 0; i < ringCount; i++) {
            long t = ring[(ringPos - ringCount + i + RING) % RING];
            if (t >= now - windowMs) tmp[n++] = t;
        }
        return java.util.Arrays.copyOf(tmp, n);
    }

    /** 直近 windowMs の取得周波数（Hz）。サンプルが2件未満なら 0 */
    double rateHz(long windowMs, long now) {
        long[] t = recentTimes(windowMs, now);
        if (t.length < 2) return 0;
        long span = t[t.length - 1] - t[0];
        return span <= 0 ? 0 : (t.length - 1) * 1000.0 / span;
    }

    /** 直近 windowMs のサンプル間隔の95パーセンタイル（ms）。2件未満なら -1 */
    long intervalP95(long windowMs, long now) {
        long[] t = recentTimes(windowMs, now);
        if (t.length < 2) return -1;
        long[] d = new long[t.length - 1];
        for (int i = 1; i < t.length; i++) d[i - 1] = t[i] - t[i - 1];
        java.util.Arrays.sort(d);
        return d[Math.min(d.length - 1, (int) Math.ceil(d.length * 0.95) - 1)];
    }

    /** 定期的に取得速度と通信エラーを health イベントとして流す（受信側・MCPから速度が見える） */
    void healthLoop() {
        long lastErrors = -1;
        while (true) {
            sleep(healthIntervalMs);
            try {
                long now = System.currentTimeMillis();
                long[] t = recentTimes(healthIntervalMs, now);
                long errs = LinkMonitor.INSTANCE.errors.get();
                if (t.length == 0 && errs == lastErrors) continue;  // 何も起きていなければ出さない
                event(String.format(java.util.Locale.ROOT, "health rate_hz=%.2f samples=%d params=%d interval_p95_ms=%d link_errors=%d link_connects=%d",
                        rateHz(healthIntervalMs, now), t.length, currentParamCount, intervalP95(healthIntervalMs, now),
                        errs, LinkMonitor.INSTANCE.connects.get()));
                lastErrors = errs;
                LinkMonitor.INSTANCE.ensureAttached();
            } catch (Throwable ignore) {}
        }
    }

    void event(String text) {
        offer("E\t" + session + "\t" + segment + "\t" + System.currentTimeMillis() + "\t" + cleanField(text));
    }

    void offer(String line) {
        // Sample volume must not consume the final 4096 slots. Preserve M/E
        // metadata while the writer drains, without blocking the ECU callback.
        // An M/E storm can still fill the bounded queue: count such loss too.
        if (line.startsWith("S\t") && queue.size() >= SAMPLE_QUEUE_LIMIT) {
            dropped.incrementAndGet();
            return;
        }
        if (queue.offer(line)) produced.incrementAndGet(); else dropped.incrementAndGet();
    }

    /** M行の名称・単位、E行の本文: 列区切り（タブ）と行区切りだけを除く */
    static String cleanField(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
    /** S行に入るID: 値リストの区切り（, =）も除く */
    static String cleanId(String s) { return cleanField(s).replace(',', '_').replace('=', '_'); }

    // ---------- spool書込み ----------
    File spoolFile() { return new File(spoolDir, "rr_" + session + ".tsv"); }

    void writerLoop() {
        // DO NOT clear this buffer after an IOException. queue.take/drainTo
        // removed the originals, so this is the last copy until fsync succeeds.
        List<String> pending = new ArrayList<String>();
        while (true) {
            try {
                if (pending.isEmpty() && dropped.get() <= lastDropMarker) {
                    pending.add(queue.take());
                    queue.drainTo(pending, 5000);
                }
                flushPending(pending);
            } catch (Throwable e) {
                Agent.log("spool write error (retaining " + pending.size()
                    + " lines for retry): " + e);
                sleep(500);
            }
        }
    }

    /**
     * One bounded writer cycle, exposed to GUI-free fault tests.
     * Retain pending on every exception; after success the full group is
     * durable and can be cleared. Never append to a damaged partial TSV tail.
     */
    void flushPending(List<String> pending) throws IOException {
        if (pending == null) throw new IllegalArgumentException("Pending buffer required");
        if (pending.isEmpty()) {
            String head = queue.poll();
            if (head != null) {
                pending.add(head);
                queue.drainTo(pending, 5000);
            }
        }
        // When pending is non-empty it may have failed on the previous
        // attempt. Never increase that in-flight batch during retries.
        if (pendingDropMarker < 0 && dropped.get() > lastDropMarker) {
            pendingDropMarker = dropped.get();
            long delta = pendingDropMarker - lastDropMarker;
            // The original dropped sample is unrecoverable. This marker is
            // evidence of a gap, NEVER a claim that no observations were lost.
            pending.add("E\t" + session + "\t" + segment + "\t"
                + System.currentTimeMillis() + "\tqueue_overflow lost_lines="
                + delta + " dropped_total=" + pendingDropMarker);
        }
        if (pending.isEmpty()) return;
        File file = spoolFile();
        // Reader must not see a complete LF-delimited row before fsync.
        synchronized (spoolIoLock) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                appendDurably(raf, pending);
            }
        } // release spool I/O mutex after durable fsync
        if (pendingDropMarker >= 0) {
            lastDropMarker = pendingDropMarker;
            pendingDropMarker = -1;
        }
        pending.clear();
    }

    /**
     * Append the entire in-flight group or restore the original file length
     * on a write/sync failure. The caller retains pending until this returns.
     * Package-private so fault tests exercise the SAME production rollback.
     */
    static void appendDurably(RandomAccessFile raf, List<String> rows) throws IOException {
        long before = raf.length();
        if (before > 0) {
            raf.seek(before - 1);
            if (raf.read() != '\\n')
                throw new IOException("Incomplete TSV tail; manual recovery required");
        }
        try {
            raf.seek(before);
            writeRowsBatched(raf, rows);
            raf.getFD().sync();
        } catch (IOException | RuntimeException ex) {
            // Partial rows MUST NOT become source data for the scanner.
            try { raf.setLength(before); raf.getFD().sync(); }
            catch (IOException rollback) { ex.addSuppressed(rollback); }
            throw ex;
        }
    }

    /** Write byte-for-byte identical UTF-8/LF TSV in fixed-size blocks.
     * A rare oversized row is written directly (no second large copy).
     */
    static void writeRowsBatched(RandomAccessFile raf, List<String> rows) throws IOException {
        byte[] chunk = new byte[WRITE_CHUNK_BYTES];
        int used = 0;
        for (String row : rows) {
            byte[] encoded = row.getBytes(StandardCharsets.UTF_8);
            if (encoded.length + 1 > chunk.length) {
                if (used > 0) { raf.write(chunk, 0, used); used = 0; }
                raf.write(encoded);
                raf.write('\n');
                continue;
            }
            if (encoded.length + 1 > chunk.length - used) {
                raf.write(chunk, 0, used);
                used = 0;
            }
            System.arraycopy(encoded, 0, chunk, used, encoded.length);
            used += encoded.length;
            chunk[used++] = (byte) '\n';
        }
        if (used > 0) raf.write(chunk, 0, used);
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
        long len;
        byte[] chunk;
        synchronized (spoolIoLock) {
            // Copy a committed byte range while the writer cannot append or
            // roll back an interrupted write. Network I/O stays OUTSIDE lock.
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                len = raf.length();
                if (pos >= len) return false;
                raf.seek(pos);
                int n = (int) Math.min(len - pos, MAX_POST_BYTES);
                chunk = new byte[n];
                raf.readFully(chunk);
            }
        }
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

    /**
     * RETENTION SAFETY: legacy .sent is NOT a product-v1 Server commit ACK.
     * Never unlink source files behind the product scanner. Offline archival
     * must be explicitly reviewed against import cursors AND Server receipts.
     */
    int cleanupSpool(long days, long nowMs) {
        return 0;
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
                + ",\"lastSampleAgeMs\":" + (lastSampleT == 0 ? "null" : String.valueOf(now - lastSampleT))
                + ",\"realSamples\":" + realSamples.get() + ",\"params\":" + currentParamCount
                + ",\"rateHz10s\":" + String.format(java.util.Locale.ROOT, "%.2f", rateHz(10000, now))
                + ",\"intervalP95Ms10s\":" + intervalP95(10000, now) + "}";
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
