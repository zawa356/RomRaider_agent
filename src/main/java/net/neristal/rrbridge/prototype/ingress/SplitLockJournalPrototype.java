package net.neristal.rrbridge.prototype.ingress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

/**
 * SYNTHETIC PoC ONLY. No production writer/Worker/Logger wiring.
 * Separate writer lock plus a one-byte legacy exclusion guard OUTSIDE the
 * bounded data range. Old whole-file locks overlap the guard in either order.
 * Windows readers never touch the guarded byte. Increasing the 8MiB quota or
 * removing the guard invalidates this protocol and needs separate review.
 * Never unlink/recreate lock files while participants may be running.
 */
public final class SplitLockJournalPrototype implements Closeable {
    public static final long GUARD_OFFSET = IngressJournalPrototype.MAX_JOURNAL_BYTES;
    private final RandomAccessFile journal, coordination;
    private final FileLock writerLock, legacyGuard;
    private final long quota;
    private long sequence;
    private boolean failed, closed;

    public SplitLockJournalPrototype(File source, long quota) throws IOException {
        if (source == null || !source.getName().endsWith(".rlj") ||
                quota < IngressJournalPrototype.HEADER_BYTES + 1 || quota > GUARD_OFFSET)
            throw new IllegalArgumentException("bounded .rlj source required");
        File absolute = source.getAbsoluteFile();
        File parent = absolute.getParentFile();
        File lockFile = new File(absolute.getPath() + ".writer.lock");
        if (parent == null || !parent.isDirectory() || Files.isSymbolicLink(parent.toPath()))
            throw new IOException("existing isolated directory required");
        checkPath(absolute); checkPath(lockFile);
        RandomAccessFile coord = new RandomAccessFile(lockFile, "rw");
        RandomAccessFile data = null;
        FileLock owner = null, guard = null;
        try {
            owner = acquire(coord, 0, Long.MAX_VALUE);
            data = new RandomAccessFile(absolute, "rw");
            guard = acquire(data, GUARD_OFFSET, 1);
            // Identity creation is writer-only and happens after BOTH locks.
            JournalIdentityPrototype.open(absolute);
            LiveJournalReadProbe.Result result = LiveJournalReadProbe.inspect(absolute);
            if (result.status != LiveJournalReadProbe.Status.COMPLETE || result.observedBytes > quota)
                throw new IOException("source requires review; never repair tail");
            sequence = result.frames;
        } catch (IOException | RuntimeException problem) {
            release(guard, problem); release(owner, problem);
            close(data, problem); close(coord, problem);
            throw problem;
        }
        journal = data; coordination = coord;
        writerLock = owner; legacyGuard = guard; this.quota = quota;
    }

    private static void checkPath(File path) throws IOException {
        if (Files.isSymbolicLink(path.toPath()) || (path.exists() && !path.isFile()))
            throw new IOException("regular non-symlink path required");
    }
    private static FileLock acquire(RandomAccessFile file, long offset, long length) throws IOException {
        try {
            FileLock lock = file.getChannel().tryLock(offset, length, false);
            if (lock == null) throw new IOException("writer ownership conflict");
            return lock;
        } catch (OverlappingFileLockException conflict) {
            throw new IOException("writer ownership conflict", conflict);
        }
    }
    private static void release(FileLock lock, Throwable problem) {
        if (lock != null) try { lock.release(); } catch (IOException e) { problem.addSuppressed(e); }
    }
    private static void close(RandomAccessFile file, Throwable problem) {
        if (file != null) try { file.close(); } catch (IOException e) { problem.addSuppressed(e); }
    }

    /** Synchronous worker/test method. NEVER invoke on ECU callback. */
    public synchronized long append(String row) throws IOException {
        if (closed || failed) throw new IOException("writer stopped; review required");
        if (row == null || row.isEmpty() || row.indexOf('\r') >= 0 || row.indexOf('\n') >= 0)
            throw new IllegalArgumentException("single nonempty row required");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(row));
        byte[] body = new byte[encoded.remaining()]; encoded.get(body);
        if (body.length > IngressJournalPrototype.MAX_ROW_BYTES)
            throw new IllegalArgumentException("row exceeds bounded quota");
        long offset = journal.length();
        if (IngressJournalPrototype.HEADER_BYTES + (long) body.length > quota - offset)
            throw new IngressJournalPrototype.QuotaExceededException("retain source; quota reached");
        if (sequence == Long.MAX_VALUE) throw new IOException("sequence exhausted");
        CRC32 crc = new CRC32(); crc.update(body);
        try {
            journal.seek(offset);
            journal.writeInt(IngressJournalPrototype.FRAME_MAGIC);
            journal.writeInt(body.length); journal.writeLong(sequence + 1);
            journal.writeInt((int) crc.getValue()); journal.write(body);
            journal.getFD().sync();
        } catch (IOException | RuntimeException problem) {
            // Retain ambiguous bytes. Never silently truncate or retry append.
            failed = true; throw problem;
        }
        return ++sequence;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException problem = null;
        try { legacyGuard.release(); } catch (IOException e) { problem = e; }
        try { journal.close(); } catch (IOException e) { if (problem == null) problem = e; else problem.addSuppressed(e); }
        try { writerLock.release(); } catch (IOException e) { if (problem == null) problem = e; else problem.addSuppressed(e); }
        try { coordination.close(); } catch (IOException e) { if (problem == null) problem = e; else problem.addSuppressed(e); }
        if (problem != null) throw problem;
    }
}
