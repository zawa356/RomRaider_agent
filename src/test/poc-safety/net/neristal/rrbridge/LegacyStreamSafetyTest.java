package net.neristal.rrbridge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Isolated test compiled against stubs. No Logger, ECU or RomRaider.jar. */
public final class LegacyStreamSafetyTest {
    private static int passed;
    private static void ok(String label, boolean success) {
        if (!success) throw new AssertionError(label);
        passed++; System.out.println("PASS " + label);
    }
    private static void failure(String label, Action action) throws Exception {
        boolean thrown = false;
        try { action.run(); } catch(IOException expected){ thrown=true; }
        ok(label,thrown);
    }
    private interface Action {void run() throws Exception;}
    private static void write(File f,String text)throws Exception {
        Files.write(f.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File f)throws Exception {
        return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
    }
    /** Counts byte-array writes without depending on timing or runner speed. */
    private static final class CountingRaf extends RandomAccessFile {
        int writes;
        CountingRaf(File file) throws IOException { super(file, "rw"); }
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            super.write(bytes, off, len);
            writes++;
        }
        @Override public void write(byte[] bytes) throws IOException {
            super.write(bytes);
            writes++;
        }
        @Override public void write(int b) throws IOException {
            super.write(b);
            writes++;
        }
    }
    /** Simulates a mid-batch I/O failure AFTER bytes have reached a file. */
    private static final class FaultRaf extends RandomAccessFile {
        int writes;
        FaultRaf(File file) throws IOException { super(file, "rw"); }
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            if (writes++ >= 1) throw new IOException("synthetic mid-batch write failure");
            super.write(bytes, off, len);
        }
        @Override public void write(byte[] bytes) throws IOException {
            if (writes++ >= 1) throw new IOException("synthetic mid-batch write failure");
            super.write(bytes);
        }
        @Override public void write(int b) throws IOException {
            if (writes++ >= 1) throw new IOException("synthetic mid-batch write failure");
            super.write(b);
        }
    }
    public static void main(String[] args)throws Exception {
        final Stream stream=Stream.INSTANCE;
        final File base=Files.createTempDirectory("romlogger-legacy-poc-safety").toFile();
        final File good=new File(base,"spool");
        ok("creates isolated safe directory",good.mkdir());
        stream.spoolDir=good;
        final List<String> pending=new ArrayList<String>();

        stream.queue.offer("E\tsession\t0\t1\tfirst");
        stream.queue.offer("E\tsession\t0\t2\tsecond");
        stream.flushPending(pending);
        ok("durable initial writer drains queue",stream.queue.isEmpty());
        ok("durable initial writer clears pending only after sync",pending.isEmpty());
        ok("writes complete newline-terminated TSV",
            read(stream.spoolFile()).equals("E\tsession\t0\t1\tfirst\nE\tsession\t0\t2\tsecond\n"));

        File block=new File(base,"cannot-be-directory");
        write(block,"block");
        stream.spoolDir=block;
        stream.queue.offer("E\tsession\t0\t3\tthird");
        stream.queue.offer("E\tsession\t0\t4\tfourth");
        failure("write failure retains uncommitted buffer",new Action() {
            public void run()throws Exception {stream.flushPending(pending);}
        });
        ok("two dequeued originals still in pending",pending.size()==2);
        ok("writer failure is visible while originals remain in memory",
            stream.writerDegraded && stream.spoolWriteFailures.get() == 1
            && stream.writerPendingLines == 2
            && stream.lastWriterFailureAtMs > 0);
        String failedStatus = stream.statusJson();
        ok("status exposes failure and uncommitted data without claiming fsync",
            failedStatus.contains("\"writerDegraded\":true")
            && failedStatus.contains("\"writerPendingLines\":2")
            && failedStatus.contains("\"memoryBufferedLines\":2")
            && failedStatus.contains("\"lastWriterFsyncAtMs\":null"));
        ok("no duplicated queue entries after failure",stream.queue.isEmpty());
        stream.spoolDir=good;
        stream.flushPending(pending);
        ok("writer retry commits unchanged originals exactly once",
            read(stream.spoolFile()).endsWith("E\tsession\t0\t3\tthird\nE\tsession\t0\t4\tfourth\n"));
        ok("retry clears pending after fsync",pending.isEmpty());
        ok("successful fsync clears degraded flag but retains failure history",
            !stream.writerDegraded && stream.writerPendingLines == 0
            && stream.spoolWriteFailures.get() == 1
            && stream.lastWriterFsyncAtMs >= stream.lastWriterFailureAtMs);

        write(stream.spoolFile(),"uncommitted-partial");
        stream.queue.offer("E\tsession\t0\t5\tfifth");
        failure("damaged TSV tail fails closed",new Action(){
            public void run()throws Exception {stream.flushPending(pending);}
        });
        ok("failed tail recovery never appends into partial record",
            read(stream.spoolFile()).equals("uncommitted-partial"));
        ok("failed tail recovery retains new record",pending.size()==1);
        // Operator repairs incomplete tail OUTSIDE the production writer.
        write(stream.spoolFile(),"repaired\n");
        stream.flushPending(pending);
        ok("after explicit repair backlog is preserved",
            read(stream.spoolFile()).equals("repaired\nE\tsession\t0\t5\tfifth\n"));

        final long prior=stream.dropped.get();
        // 200k is the real bounded callback queue capacity. No blocking or
        // I/O in the Logger callback. A lost row MUST be signalled explicitly.
        for(int i=0;i<Stream.QUEUE_CAPACITY;i++)
            if(!stream.queue.offer("synthetic-queued-" + i))
                throw new AssertionError("queue unexpectedly full early");
        ok("bounded queue refuses overload",
            !stream.queue.offer("extra-unrecoverable"));
        // The normal callback path must reserve headroom for M/E definitions,
        // even when heavy sampling has pushed the queue beyond its soft limit.
        stream.queue.clear();
        long reserveBefore = stream.dropped.get();
        for (int i = 0; i < Stream.SAMPLE_QUEUE_LIMIT; i++)
            if (!stream.queue.offer("synthetic-queued-" + i))
                throw new AssertionError("premature reserve fill");
        stream.offer("S\tsession\t0\t123\t123\tRPM=900");
        ok("sample pressure drops at reserve threshold",
            stream.dropped.get() == reserveBefore + 1);
        stream.offer("M\tsession\t0\tRPM\tEngine Speed\trpm");
        stream.offer("E\tsession\t0\t124\thealth");
        ok("definitions and health retained while sample limit reached",
            stream.queue.size() == Stream.SAMPLE_QUEUE_LIMIT + 2
            && stream.queue.contains("M\tsession\t0\tRPM\tEngine Speed\trpm")
            && stream.queue.contains("E\tsession\t0\t124\thealth"));
        stream.queue.clear();
        for (int i = 0; i < Stream.QUEUE_CAPACITY; i++)
            stream.queue.offer("synthetic-refill-" + i);
        // Stream.offer is the actual production loss-accounting hook.
        stream.offer("another-unrecoverable");
        ok("callback overflow is counted",stream.dropped.get()==prior+2);
        stream.queue.clear(); // Test fixture ONLY: do not write 200k dummy lines.
        stream.flushPending(pending);
        String loss=read(stream.spoolFile());
        ok("overflow creates durable loss event",
            loss.contains("queue_overflow lost_lines=2 dropped_total="+(prior+2)));
        int length=loss.length();
        stream.flushPending(pending);
        ok("same loss is not reported twice",read(stream.spoolFile()).length()==length);

        // D2b-2c-2: multi-chunk UTF-8/LF write uses fixed buffering, and
        // rollback is exercised with an IOException AFTER the first write.
        File batchFile = new File(base, "batched-test.tsv");
        write(batchFile, "prefix\n");
        List<String> batchRows = new ArrayList<String>();
        StringBuilder expected = new StringBuilder("prefix\n");
        for (int i = 0; i < 2400; i++) {
            String row = "S\tsynthetic\t2\t" + i
                + "\t1790000000000\tRPM=1400.0,TPS=12.0,MPR=-9.0,UNIT=日本語";
            batchRows.add(row);
            expected.append(row).append('\n');
        }
        ok("test batch spans multiple 64KiB blocks",
            expected.toString().getBytes(StandardCharsets.UTF_8).length
                > Stream.WRITE_CHUNK_BYTES * 2);
        boolean injected = false;
        try (FaultRaf raf = new FaultRaf(batchFile)) {
            try { Stream.appendDurably(raf, batchRows); }
            catch (IOException expectedFault) { injected = true; }
        }
        ok("mid-batch injected IOException occurs after first write", injected);
        ok("partial multi-chunk batch is rolled back to durable prefix",
            read(batchFile).equals("prefix\n"));
        ok("writer input retained unchanged for explicit retry",
            batchRows.size() == 2400 && batchRows.get(0).startsWith("S\tsynthetic"));
        int calls;
        try (CountingRaf raf = new CountingRaf(batchFile)) {
            Stream.appendDurably(raf, batchRows);
            calls = raf.writes;
        }
        ok("batched writer avoids per-line OS writes", calls < 50);
        ok("retry preserves exact UTF-8 TSV bytes once",
            read(batchFile).equals(expected.toString()));

        List<String> runtimeRows = new ArrayList<String>(batchRows);
        runtimeRows.add(null);  // synthetic invalid producer, after two chunks
        boolean runtimeFailed = false;
        try (RandomAccessFile raf = new RandomAccessFile(batchFile, "rw")) {
            try { Stream.appendDurably(raf, runtimeRows); }
            catch (NullPointerException expectedFault) { runtimeFailed = true; }
        }
        ok("runtime encoder failure is propagated", runtimeFailed);
        ok("runtime encoder failure restores original file", read(batchFile).equals(expected.toString()));

        StringBuilder oversized = new StringBuilder("E\tsynthetic\t2\t999\t");
        for (int i = 0; i < 70000; i++) oversized.append('x');
        oversized.append("終");
        List<String> mixedRows = Arrays.asList("M\tsynthetic\t2\tRPM\t回転数\trpm",
            oversized.toString(), "E\tsynthetic\t2\t1000\tlast");
        StringBuilder mixedExpected = new StringBuilder(expected);
        for (String row : mixedRows) mixedExpected.append(row).append('\n');
        try (RandomAccessFile raf = new RandomAccessFile(batchFile, "rw")) {
            Stream.appendDurably(raf, mixedRows);
        }
        ok("single oversized UTF-8 row bypasses chunk without truncation",
            read(batchFile).equals(mixedExpected.toString()));
        ok("oversized line ends with LF before next complete event",
            read(batchFile).contains("終\nE\tsynthetic\t2\t1000\tlast\n"));

        // D2b-2c-3: simulate a sustained disk outage WITHOUT touching the
        // machine's real disk state. Verify real callback offer policy,
        // writer in-flight preservation, queued backlog and eventual drain.
        stream.spoolDir = block; // a regular file: all attempted opens fail
        stream.offer("E\tstall\t0\t1\tbefore-outage");
        long errorsBeforeStall = stream.spoolWriteFailures.get();
        failure("sustained outage leaves first event in writer memory",
            new Action() { public void run() throws Exception { stream.flushPending(pending); } });
        ok("failure counter increases and in-flight row remains visible",
            stream.spoolWriteFailures.get() == errorsBeforeStall + 1
                && pending.size() == 1 && stream.writerPendingLines == 1
                && stream.writerDegraded);
        long dropsBeforeStall = stream.dropped.get();
        for (int i = 0; i < Stream.SAMPLE_QUEUE_LIMIT; i++) {
            stream.offer("S\tstall\t2\t" + i + "\t1000\tRPM=" + i);
        }
        for (int i = 0; i < 10; i++) {
            stream.offer("S\tstall\t2\t" + (Stream.SAMPLE_QUEUE_LIMIT + i) + "\t1000\tRPM=DROP");
        }
        stream.offer("M\tstall\t2\tRPM\tEngine Speed\trpm");
        stream.offer("E\tstall\t2\t2000\thealth");
        ok("bounded nonblocking producer counts exactly ten dropped samples",
            stream.dropped.get() == dropsBeforeStall + 10
            && stream.queue.size() == Stream.SAMPLE_QUEUE_LIMIT + 2);
        ok("metadata survives synthetic queue saturation",
            stream.queue.contains("M\tstall\t2\tRPM\tEngine Speed\trpm")
            && stream.queue.contains("E\tstall\t2\t2000\thealth"));
        String stalledStatus = stream.statusJson();
        ok("status reports total volatile backlog during writer failure",
            stalledStatus.contains("\"writerDegraded\":true")
            && stalledStatus.contains("\"memoryBufferedLines\":"
                + (Stream.SAMPLE_QUEUE_LIMIT + 3))
            && stalledStatus.contains("\"linesDropped\":" + stream.dropped.get()));
        stream.spoolDir = good;
        int drainCycles = 0;
        while (!pending.isEmpty() || !stream.queue.isEmpty()) {
            stream.flushPending(pending);
            if (++drainCycles > 100) throw new AssertionError("writer did not drain restored backlog");
        }
        ok("entire accepted backlog drained after disk restoration",
            stream.queue.isEmpty() && pending.isEmpty() && !stream.writerDegraded
            && stream.writerPendingLines == 0 && drainCycles > 20);
        long recoveredSamples = 0;
        long recoveredMarkers = 0;
        long recoveredMeta = 0;
        try (java.util.stream.Stream<String> lines = Files.lines(stream.spoolFile().toPath(),
                StandardCharsets.UTF_8)) {
            java.util.Iterator<String> iter = lines.iterator();
            while (iter.hasNext()) {
                String row = iter.next();
                if (row.startsWith("S\tstall\t2\t")) recoveredSamples++;
                if (row.contains("queue_overflow lost_lines=10 dropped_total="
                    + stream.dropped.get())) recoveredMarkers++;
                if (row.equals("M\tstall\t2\tRPM\tEngine Speed\trpm")
                    || row.equals("E\tstall\t2\t2000\thealth")
                    || row.equals("E\tstall\t0\t1\tbefore-outage")) recoveredMeta++;
            }
        }
        ok("every accepted synthetic S row was persisted exactly once",
            recoveredSamples == Stream.SAMPLE_QUEUE_LIMIT);
        ok("one and only one loss marker is durable after recovery",
            recoveredMarkers == 1);
        ok("all three metadata rows were persisted", recoveredMeta == 3);
        ok("post-recovery status retains failure evidence",
            stream.statusJson().contains("\"spoolWriteFailures\":"
                + stream.spoolWriteFailures.get())
            && stream.spoolWriteFailures.get() >= 2
            && stream.lastWriterFsyncAtMs > 0);

        File old=new File(good,"rr_20000101_000000.tsv");
        write(old,"E\told\t0\t1\tline\n");
        File legacyAck=new File(old.getPath()+".sent");
        write(legacyAck,String.valueOf(old.length()));
        old.setLastModified(System.currentTimeMillis()-30L*24*3600*1000);
        ok("legacy ACK alone cannot auto-delete product import source",
            stream.cleanupSpool(7,System.currentTimeMillis())==0 && old.exists()
            && legacyAck.exists());
        ok("all persisted file bytes still available for product importer",old.length()>0);
        System.out.println("LegacyStreamSafetyTest passed "+passed+" checks (stub-only synthetic test)");
    }
}
