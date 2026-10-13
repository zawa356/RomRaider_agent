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
        ok("no duplicated queue entries after failure",stream.queue.isEmpty());
        stream.spoolDir=good;
        stream.flushPending(pending);
        ok("writer retry commits unchanged originals exactly once",
            read(stream.spoolFile()).endsWith("E\tsession\t0\t3\tthird\nE\tsession\t0\t4\tfourth\n"));
        ok("retry clears pending after fsync",pending.isEmpty());

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
        // Stream.offer is the actual production loss-accounting hook.
        stream.offer("another-unrecoverable");
        ok("callback overflow is counted",stream.dropped.get()==prior+1);
        stream.queue.clear(); // Test fixture ONLY: do not write 200k dummy lines.
        stream.flushPending(pending);
        String loss=read(stream.spoolFile());
        ok("overflow creates durable loss event",
            loss.contains("queue_overflow lost_lines=1 dropped_total="+(prior+1)));
        int length=loss.length();
        stream.flushPending(pending);
        ok("same loss is not reported twice",read(stream.spoolFile()).length()==length);

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
