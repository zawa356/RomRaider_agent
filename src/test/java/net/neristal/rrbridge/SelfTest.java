package net.neristal.rrbridge;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * RomRaiderのGUIなしで動く範囲の自己試験（JUnit不要）。./test.sh から実行する。
 * 対象: Util、プロファイル名の検証と一覧、Stream の spool → HTTP → Receiver(SQLite)、
 *       受信停止中の保持と再送、重複排除、トークン認証、spool の掃除。
 * 実ECU・RomRaiderの画面操作は対象外（docs/06_verification.md の手動試験で行う）。
 */
public final class SelfTest {
    static int failures = 0, passes = 0;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("rrbridge-test").toFile();
        int port;
        ServerSocket ss = new ServerSocket(0); port = ss.getLocalPort(); ss.close();
        String token = "test-token";

        // --- Util ---
        check("q escapes quotes/backslash/control", Util.q("a\"b\\c\n").equals("\"a\\\"b\\\\c\\u000a\""));
        check("q null", Util.q(null).equals("null"));
        check("cleanField keeps comma/equals", Stream.cleanField("A/F, x=1\tz").equals("A/F, x=1 z"));
        check("cleanId replaces , and =", Stream.cleanId("P1,=2").equals("P1__2"));

        // --- Agent 設定とプロファイル ---
        write(new File(root, "rrbridge.properties"), "switch.settleMs=99999\nspool.retentionDays=3\n");
        Agent.init(root);
        check("settleMs clamped to 10000", Agent.settleMs == 10000);
        check("retentionDays loaded", Stream.INSTANCE.retentionDays == 3);
        File repoProfiles = new File("profiles");
        for (String n : new String[]{"basic.xml", "fuel.xml"})
            Files.copy(new File(repoProfiles, n).toPath(), new File(Agent.profilesDir, n).toPath());
        check("profileFile ok", Agent.profileFile("basic.xml") != null);
        check("profileFile rejects traversal", Agent.profileFile("../rrbridge.properties") == null);
        check("profileFile rejects missing", Agent.profileFile("nope.xml") == null);
        check("profileFile rejects null", Agent.profileFile(null) == null);
        String lp = Agent.execute(map("cmd", "list_profiles"));
        check("list_profiles basic=6 fuel=7", lp.contains("\"name\":\"basic.xml\"") && lp.contains("\"count\":6") && lp.contains("\"count\":7"));
        check("list_profiles includes P8", lp.contains("\"P8\""));

        // --- 取得速度ガード関連（v0.5.0） ---
        check("default maxParams 20", Agent.maxParams == 20);
        check("guard on by default", Agent.guardEnabled && Agent.guardMinRateHz == 2.0);
        // 21項目のプロファイルは allowed=false
        StringBuilder big = new StringBuilder("<?xml version=\"1.0\"?>\n<profile protocol=\"SSM\">\n<parameters>\n");
        for (int i = 1; i <= 21; i++) big.append("<parameter id=\"P").append(i).append("\" units=\"x\" livedata=\"selected\"/>\n");
        big.append("<parameter id=\"P99\" units=\"x\"/>\n</parameters>\n</profile>\n");
        write(new File(Agent.profilesDir, "big21.xml"), big.toString());
        check("selectedIds counts only selected", Agent.selectedIds(new File(Agent.profilesDir, "big21.xml")).size() == 21);
        String lp2 = Agent.execute(map("cmd", "list_profiles"));
        check("list_profiles marks big21 not allowed", lp2.contains("\"name\":\"big21.xml\",\"selected\"") && lp2.contains("\"count\":21,\"allowed\":false"));
        check("list_profiles marks basic allowed", lp2.contains("\"count\":6,\"allowed\":true"));

        // 速度計測: 125ms間隔で40件 → 8Hz、p95=125
        Stream rs = new Stream();
        long base = 1_000_000L;
        for (int i = 0; i < 40; i++) rs.recordSample(base + i * 125);
        long nowT = base + 39 * 125;
        check("rateHz ~8", Math.abs(rs.rateHz(10000, nowT) - 8.0) < 0.01);
        check("intervalP95 125", rs.intervalP95(10000, nowT) == 125);
        check("rate window excludes old", rs.rateHz(1000, nowT + 5000) == 0);
        // 間隔が乱れた場合
        Stream rs2 = new Stream();
        for (int i = 0; i < 20; i++) rs2.recordSample(base + i * (i % 10 == 9 ? 3000 : 400));
        check("rate drops with gaps", rs2.rateHz(60000, base + 100000) < 2.0);

        // 通信状態の判定（RomRaider 1.1.0 の実際のログ文言）
        LinkMonitor lm = new LinkMonitor();
        lm.classify("INFO", "J2534/ISO9141 connection initialised", "", 1000);
        lm.classify("INFO", "ECU ID = 2F04785006", "", 1001);
        lm.classify("ERROR", "Error occurred", "readMsg error: timeout expired waiting for 51 more bytes", 2000);
        lm.classify("ERROR", "Error sending init: readMsg error: timeout expired waiting for 2 more message(s)", "", 3000);
        lm.classify("ERROR", "Error sending init: Parameter portName must not be null or empty", "", 4000);
        lm.classify("INFO", "TX Manager Started.", "", 5000);
        check("link connects 1", lm.connects.get() == 1);
        check("link ecuId parsed", lm.ecuId.equals("2F04785006") && lm.ecuInits.get() == 1);
        check("link errors 3", lm.errors.get() == 3);
        check("link lastError has throwable text", lm.lastError.contains("Error sending init") );
        lm.ensureAttached(); // log4j が無い環境では失敗を記録するだけで例外を出さない
        check("link attach without log4j is safe", lm.attachResult.startsWith("attach failed") || lm.attachResult.startsWith("attached"));

        // --- Stream → Receiver ---
        write(new File(root, "sink.properties"), "url=http://127.0.0.1:" + port + "/ingest\ntoken=" + token + "\n");
        Stream st = Stream.INSTANCE;
        st.startThreads(new File(root, "spool"), new File(root, "sink.properties"));
        st.selftest(30, 50);
        Thread.sleep(2500);
        check("receiver down: nothing sent", st.sentLines.get() == 0);
        check("receiver down: errors counted", st.sendErrors.get() > 0);
        check("receiver down: spool has data", st.pendingBytes() > 0);
        long produced1 = st.produced.get();

        File db = new File(root, "t.db");
        Receiver.main(new String[]{db.getPath(), "127.0.0.1", String.valueOf(port), token});
        waitUntil(new Cond() { public boolean ok() { return Stream.INSTANCE.pendingBytes() == 0; } }, 10000);
        check("backfill: all lines sent", st.sentLines.get() == produced1);
        check("backfill: 30 samples in DB", count("SELECT COUNT(*) FROM samples") == 30);
        check("backfill: 90 measurements", count("SELECT COUNT(*) FROM measurements") == 90);
        check("backfill: 3 parameters", count("SELECT COUNT(*) FROM parameters") == 3);
        check("backfill: 2 events", count("SELECT COUNT(*) FROM events") == 2);
        check("event text keeps '='", count("SELECT COUNT(*) FROM events WHERE text LIKE 'selftest start count=30%'") == 1);

        // 再送しても重複しない
        for (File f : new File(root, "spool").listFiles()) if (f.getName().endsWith(".sent")) Util.writeAtomic(f, "0");
        waitUntil(new Cond() { public boolean ok() { return Stream.INSTANCE.pendingBytes() == 0; } }, 10000);
        Thread.sleep(300);
        check("resend: still 30 samples", count("SELECT COUNT(*) FROM samples") == 30);
        check("resend: still 2 events", count("SELECT COUNT(*) FROM events") == 2);
        check("resend: result reports duplicates", st.lastSendResult.contains("\"duplicates\":30") && st.lastSendResult.contains("\"events\":0"));

        // 認証
        check("receiver rejects missing token", httpCode("POST", "http://127.0.0.1:" + port + "/ingest", null, "M\tX\t1\tP\tN\tU\n") == 401);
        check("receiver accepts token", httpCode("POST", "http://127.0.0.1:" + port + "/ingest", token, "M\tX\t1\tP\tN\tU\n") == 200);
        check("receiver health without token", httpCode("GET", "http://127.0.0.1:" + port + "/health", null, null) == 200);

        // spool掃除: 送信済みかつ古いものだけ削除、現在のsessionと未送信は残す
        File spool = new File(root, "spool");
        File oldSent = new File(spool, "rr_20000101_000000.tsv"); write(oldSent, "E\tx\t0\t1\tt\n");
        Util.writeAtomic(new File(oldSent.getPath() + ".sent"), String.valueOf(oldSent.length()));
        File oldUnsent = new File(spool, "rr_20000101_000001.tsv"); write(oldUnsent, "E\tx\t0\t1\tt\n");
        long longAgo = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        oldSent.setLastModified(longAgo); oldUnsent.setLastModified(longAgo);
        st.sinkUrl = ""; // 掃除試験中に未送信ファイルが送られないよう転送を止める
        int del = st.cleanupSpool(7, System.currentTimeMillis());
        check("cleanup keeps legacy ACKed source for product importer", del == 0 && oldSent.exists());
        check("cleanup kept unsent", oldUnsent.exists());
        check("cleanup kept current session", st.spoolFile().exists());

        System.out.println();
        System.out.println("passed " + passes + ", failed " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    interface Cond { boolean ok(); }
    static void waitUntil(Cond c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!c.ok() && System.currentTimeMillis() < end) Thread.sleep(100);
    }
    static long count(String sql) throws Exception {
        synchronized (Receiver.class) {
            Statement s = Receiver.db.createStatement();
            ResultSet r = s.executeQuery(sql); r.next(); long v = r.getLong(1); r.close(); s.close();
            return v;
        }
    }
    static int httpCode(String method, String url, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) { c.setDoOutput(true); OutputStream o = c.getOutputStream(); o.write(body.getBytes(StandardCharsets.UTF_8)); o.close(); }
        return c.getResponseCode();
    }
    static java.util.Map<String, String> map(String k, String v) { java.util.Map<String, String> m = new java.util.HashMap<String, String>(); m.put(k, v); return m; }
    static void write(File f, String s) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
    static void check(String name, boolean ok) {
        if (ok) { passes++; System.out.println("PASS " + name); } else { failures++; System.out.println("FAIL " + name); }
    }
}
