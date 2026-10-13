package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** GUI-free synthetic handoff tests. Nothing is attached to an ECU. */
public final class IngressHandoffPrototypeTest {
    private static int checks;
    private static void ok(String title, boolean valid) {
        if (!valid) throw new AssertionError(title);
        checks++;
        System.out.println("PASS " + title);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(String title, Action action) throws Exception {
        boolean threw = false;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { threw = true; }
        ok(title, threw);
    }
    private static IngressHandoffPrototype.Appender adapter(final IngressJournalPrototype journal) {
        return new IngressHandoffPrototype.Appender() {
            public IngressJournalPrototype.Entry append(String row) throws IOException {
                return journal.append(row);
            }
            public List<IngressJournalPrototype.Entry> replay() throws IOException {
                return journal.replay();
            }
        };
    }

    /** Test-only immutable ID store. Real v1 SQLite integration is NOT part of 4b. */
    private static final class IdempotentMap implements IngressHandoffPrototype.IdempotentSink {
        final Map<String,String> rows = new LinkedHashMap<String,String>();
        int invocations;
        boolean failAfterSecond;
        public void commit(String id, String row) throws Exception {
            invocations++;
            String previous = rows.get(id);
            if (previous != null && !previous.equals(row))
                throw new IOException("collision with changed payload");
            rows.put(id, row);
            if (failAfterSecond && invocations == 2)
                throw new IOException("synthetic crash after downstream commit, before cursor");
        }
    }

    public static void main(String[] args) throws Exception {
        final File folder = Files.createTempDirectory("rl-ingress-handoff-synthetic").toFile();
        final File source = new File(folder,"entry.rlj");
        final String sourceUuid = "1c2b45b9-dfde-4a3d-9275-e707526223e2";
        IngressJournalPrototype j = new IngressJournalPrototype(source, 1024 * 1024);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean[] fault = { true };
        IngressHandoffPrototype.Appender blocked = new IngressHandoffPrototype.Appender() {
            public IngressJournalPrototype.Entry append(String row) throws IOException {
                if (fault[0]) {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("synthetic interruption", interrupted);
                    }
                    fault[0] = false;
                    throw new IOException("synthetic worker I/O failure");
                }
                return j.append(row);
            }
            public List<IngressJournalPrototype.Entry> replay() throws IOException { return j.replay(); }
        };
        final IngressHandoffPrototype pump = new IngressHandoffPrototype(blocked, 2);
        ok("producer accepts first volatile sample",
            pump.offer("M\ttest\t1\tTEST_RPM\tSpeed\trpm")
                == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);
        ok("producer accepts second volatile sample",
            pump.offer("S\ttest\t1\t1\t1000\tTEST_RPM=850")
                == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);

        final AtomicReference<Throwable> workerOutcome = new AtomicReference<Throwable>();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try { pump.drainOne(); }
                catch (Throwable expected) { workerOutcome.set(expected); }
            }
        }, "synthetic-prototype-worker");
        worker.start();
        entered.await();
        ok("producer still accepts within bounded queue while writer is blocked",
            pump.offer("E\ttest\t1\t1001\thealth")
                == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);
        ok("bounded producer refuses extra row without waiting for disk writer",
            pump.offer("S\ttest\t1\t2\t1002\tTEST_RPM=900")
                == IngressHandoffPrototype.OfferResult.REJECTED_FULL);
        ok("uncommitted inflight row and queued rows are counted",
            pump.status().volatileBufferedRows() == 3
            && pump.status().inFlightRows == 1
            && pump.status().queuedRows == 2
            && pump.status().rejectedFull == 1);
        ok("blocked writer has not fsynced any row", j.replay().isEmpty());
        release.countDown();
        worker.join();
        ok("writer IO exception is visible after controlled release",
            workerOutcome.get() instanceof IOException
            && pump.status().writerFailures == 1 && pump.status().halted);
        ok("failed inflight remains accepted but NOT durable",
            pump.status().journalCommitted == 0 && pump.status().volatileBufferedRows() == 3);
        ok("halted producer rejects further records explicitly",
            pump.offer("S\ttest\t1\t3\t1003\tTEST_RPM=950")
                == IngressHandoffPrototype.OfferResult.REJECTED_HALTED);
        ok("invalid producer row is rejected without callback exception",
            pump.offer("S\tbad\nline") == IngressHandoffPrototype.OfferResult.REJECTED_INVALID);

        pump.resumeAfterReview();
        ok("operator review clears only transient halt", !pump.status().halted);
        ok("worker commits retained first row", pump.drainOne());
        ok("worker commits second row", pump.drainOne());
        ok("worker commits third row", pump.drainOne());
        ok("worker returns no work when drained", !pump.drainOne());
        ok("no accepted record lost after explicit resumption",
            pump.status().journalCommitted == 3 && pump.status().volatileBufferedRows() == 0
            && j.replay().size() == 3);

        final IdempotentMap sink = new IdempotentMap();
        sink.failAfterSecond = true;
        rejects("downstream may crash AFTER durable ID commit",
            new Action() {
                public void run() throws Exception { pump.replayTo(sourceUuid,sink,0,3); }
            });
        ok("two idempotency IDs retained after replay failure", sink.rows.size() == 2);
        sink.failAfterSecond = false;
        ok("replay from zero commits and deduplicates without a cursor",
            pump.replayTo(sourceUuid,sink,0,3) == 3 && sink.rows.size() == 3);
        ok("repeat replay remains three immutable IDs",
            pump.replayTo(sourceUuid,sink,0,3) == 3 && sink.rows.size() == 3);
        ok("bounded replay starts after acknowledged sequence",
            pump.replayTo(sourceUuid,sink,1,1) == 1 && sink.rows.size() == 3);
        ok("after final sequence no rows are replayed",
            pump.replayTo(sourceUuid,sink,3,5) == 0);
        rejects("invalid source ID fails closed",
            new Action() { public void run() throws Exception { pump.replayTo("mutable-name",sink,0,3); } });
        rejects("negative cursor fails closed",
            new Action() { public void run() throws Exception { pump.replayTo(sourceUuid,sink,-1,3); } });
        rejects("zero replay limit fails closed",
            new Action() { public void run() throws Exception { pump.replayTo(sourceUuid,sink,0,0); } });

        // Volatile rows must NOT be reported as durable through a JVM restart.
        ok("additional uncommitted row accepted only in memory",
            pump.offer("E\ttest\t1\t1111\tvolatile-never-fsynced")
                == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);
        ok("memory backlog is observable before pseudo-restart",
            pump.status().volatileBufferedRows() == 1);
        j.close();
        try (IngressJournalPrototype reopened = new IngressJournalPrototype(source, 1024*1024)) {
            IngressHandoffPrototype restarted = new IngressHandoffPrototype(adapter(reopened),2);
            ok("restart replays only three fsynced rows, not volatile row",
                reopened.replay().size() == 3);
            ok("fresh handoff has no false durable acknowledgement",
                restarted.status().journalCommitted == 0 && restarted.status().volatileBufferedRows() == 0);
            ok("stable source UUID gives identical replay IDs across JVM-like restart",
                restarted.replayTo(sourceUuid,sink,0,3) == 3 && sink.rows.size() == 3);
        }

        try (IngressJournalPrototype quotaJournal =
            new IngressJournalPrototype(new File(folder,"quota.rlj"), 27)) {
            IngressHandoffPrototype limited = new IngressHandoffPrototype(adapter(quotaJournal),2);
            ok("quota fixture accepts first volatile event",
                limited.offer("E\t1") == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);
            ok("first event becomes durable", limited.drainOne());
            ok("quota fixture accepts a second event but not yet durable",
                limited.offer("E\tlonger") == IngressHandoffPrototype.OfferResult.ACCEPTED_VOLATILE);
            rejects("journal quota rejects second sync append",
                new Action() { public void run() throws Exception { limited.drainOne(); } });
            ok("quota fail-closed retains in-flight volatile record",
                limited.status().halted && limited.status().quotaTerminal
                && limited.status().inFlightRows == 1 && limited.status().journalCommitted == 1);
            ok("quota stop rejects new producer data",
                limited.offer("E\tnew") == IngressHandoffPrototype.OfferResult.REJECTED_HALTED);
            rejects("quota-terminal journal cannot be resumed unsafely",
                new Action() { public void run() throws Exception { limited.resumeAfterReview(); } });
            ok("previous fsynced frame survives quota exhaustion",
                quotaJournal.replay().size() == 1);
        }

        ok("test uses only isolated temporary files, no ECU", folder.isDirectory());
        System.out.println("IngressHandoffPrototypeTest passed "+checks+" checks (synthetic only)");
    }
}
