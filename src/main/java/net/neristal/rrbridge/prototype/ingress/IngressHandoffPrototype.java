package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D2b-2c-4b isolated handoff experiment. NOT wired into Stream or vehicle.
 *
 * Producer offer() uses bounded memory only. ACCEPTED_VOLATILE is NOT a
 * durability acknowledgement. Only a dedicated worker may call drainOne().
 * A failed append retains the in-flight row and fails closed; recovery must
 * be explicitly authorized outside the ECU callback.
 */
public final class IngressHandoffPrototype {
    public interface Appender {
        IngressJournalPrototype.Entry append(String row) throws IOException;
        List<IngressJournalPrototype.Entry> replay() throws IOException;
    }

    public interface IdempotentSink {
        /**
         * Commit immutable record ID and payload DURABLY, or throw.
         * Repeated calls with the same ID+body must be safe. NOT a Server ACK.
         */
        void commit(String recordId, String row) throws Exception;
    }

    public enum OfferResult {
        ACCEPTED_VOLATILE, REJECTED_FULL, REJECTED_HALTED, REJECTED_INVALID
    }

    public static final class Status {
        public final int queuedRows, inFlightRows;
        public final long volatileAccepted, journalCommitted, rejectedFull,
            rejectedHalted, rejectedInvalid, writerFailures;
        public final boolean halted, quotaTerminal;
        Status(int queued, int inFlight, long accepted, long committed,
               long full, long haltedCount, long invalid, long failures,
               boolean blocked, boolean quota) {
            queuedRows = queued;
            inFlightRows = inFlight;
            volatileAccepted = accepted;
            journalCommitted = committed;
            rejectedFull = full;
            rejectedHalted = haltedCount;
            rejectedInvalid = invalid;
            writerFailures = failures;
            halted = blocked;
            quotaTerminal = quota;
        }
        public long volatileBufferedRows() { return queuedRows + (long) inFlightRows; }
    }

    private final Appender appender;
    private final ArrayBlockingQueue<String> queue;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong rejectedHalted = new AtomicLong();
    private final AtomicLong rejectedInvalid = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile String inFlight;
    private volatile boolean quotaTerminal;

    public IngressHandoffPrototype(Appender appender, int capacity) {
        if (appender == null || capacity < 1 || capacity > 8192)
            throw new IllegalArgumentException("invalid prototype appender/capacity");
        this.appender = appender;
        this.queue = new ArrayBlockingQueue<String>(capacity);
    }

    /** Never fsync, sleep or wait for the journal here. */
    public OfferResult offer(String row) {
        if (row == null || row.isEmpty() || row.indexOf('\n') >= 0 || row.indexOf('\r') >= 0) {
            rejectedInvalid.incrementAndGet();
            return OfferResult.REJECTED_INVALID;
        }
        if (halted.get()) {
            rejectedHalted.incrementAndGet();
            return OfferResult.REJECTED_HALTED;
        }
        if (!queue.offer(row)) {
            rejectedFull.incrementAndGet();
            return OfferResult.REJECTED_FULL;
        }
        accepted.incrementAndGet();
        return OfferResult.ACCEPTED_VOLATILE;
    }

    /**
     * Synchronous, dedicated-worker call only. No background thread is
     * started by this class. May throw on journal failure; do not pop inFlight.
     */
    public synchronized boolean drainOne() throws IOException {
        if (halted.get()) throw new IOException("journal halted; operator review required");
        if (inFlight == null) inFlight = queue.poll();
        if (inFlight == null) return false;
        try {
            appender.append(inFlight);
        } catch (IOException | RuntimeException failed) {
            failures.incrementAndGet();
            halted.set(true);
            if (failed instanceof IngressJournalPrototype.QuotaExceededException)
                quotaTerminal = true;
            throw failed;
        }
        committed.incrementAndGet();
        inFlight = null;
        return true;
    }

    /**
     * Explicit operator action, worker thread only. A quota-exhausted
     * immutable journal cannot be resumed with the same file.
     * Always validate complete on-disk frames before permitting retries.
     */
    public synchronized void resumeAfterReview() throws IOException {
        if (quotaTerminal) throw new IOException("quota terminal: retain journal, plan safe rotation");
        if (!halted.get()) return;
        appender.replay();
        halted.set(false);
    }

    public Status status() {
        return new Status(queue.size(), inFlight == null ? 0 : 1,
            accepted.get(), committed.get(), rejectedFull.get(),
            rejectedHalted.get(), rejectedInvalid.get(), failures.get(),
            halted.get(), quotaTerminal);
    }

    /**
     * Replays the immutable journal from the beginning: NO cursor advance.
     * sourceId must be an externally persisted, immutable journal UUID.
     * The sink must enforce (recordId,payload) idempotence durably.
     * A consumer MAY resume after an acknowledged sequence; if no durable
     * cursor exists, replay from zero and depend on sink idempotence.
     * This class is NOT linked to the existing SQLite outbox.
     */
    public int replayTo(String sourceId, IdempotentSink sink, long afterSequence, int maxEntries) throws Exception {
        if (sink == null || afterSequence < 0 || maxEntries < 1 || maxEntries > 8192)
            throw new IllegalArgumentException("invalid replay arguments");
        String canonical = UUID.fromString(sourceId).toString();
        if (!canonical.equals(sourceId))
            throw new IllegalArgumentException("non-canonical source UUID");
        List<IngressJournalPrototype.Entry> all = appender.replay();
        int count = 0;
        for (IngressJournalPrototype.Entry entry : all) {
            if (entry.sequence <= afterSequence) continue;
            if (count == maxEntries) break;
            String id = UUID.nameUUIDFromBytes(("RomLogger-ingress-prototype|"
                + canonical + "|" + entry.sequence).getBytes(StandardCharsets.UTF_8)).toString();
            sink.commit(id, entry.row);
            count++;
        }
        return count;
    }
}
