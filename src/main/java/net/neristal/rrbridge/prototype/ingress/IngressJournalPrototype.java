package net.neristal.rrbridge.prototype.ingress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * ISOLATED DESIGN PROTOTYPE ONLY (Phase D2b-2c-4a).
 *
 * This class is deliberately NOT connected to RomRaider, Stream.handleDataUpdate,
 * the legacy TSV writer, or the product-v1 outbox. Every append calls fsync and
 * therefore MUST NOT be invoked by the non-blocking ECU Logger callback.
 *
 * Append-only frames: magic(4), payload length(4), sequence(8), CRC32(4),
 * strict UTF-8 TSV row (no newline). Offsets/sequence stay stable while the
 * file is not replaced, truncated or compacted. No automatic data deletion.
 *
 * A damaged/truncated frame fails CLOSED: an operator must preserve evidence
 * and determine a recovery procedure. This prototype NEVER repairs the tail.
 */
public final class IngressJournalPrototype implements Closeable {
    public static final int FRAME_MAGIC = 0x524c4a31; // "RLJ1"
    public static final int HEADER_BYTES = 20;
    public static final int MAX_ROW_BYTES = 128 * 1024;
    // Deliberately small prototype guardrail: bounded startup scan and memory.
    public static final long MAX_JOURNAL_BYTES = 8L * 1024 * 1024;

    public static final class Entry {
        public final long offset, sequence;
        public final String row;
        Entry(long offset, long sequence, String row) {
            this.offset = offset;
            this.sequence = sequence;
            this.row = row;
        }
    }

    public static final class QuotaExceededException extends IOException {
        public QuotaExceededException(String message) { super(message); }
    }

    private final RandomAccessFile file;
    private final FileLock lock;
    private final long maxBytes;
    private long lastSequence;
    private boolean closed;
    private boolean rollbackFailed;

    public IngressJournalPrototype(File path, long maxBytes) throws IOException {
        if (path == null) throw new IllegalArgumentException("journal path required");
        if (maxBytes < HEADER_BYTES + 1 || maxBytes > MAX_JOURNAL_BYTES)
            throw new IllegalArgumentException("invalid prototype quota");
        File absolute = path.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null || !parent.isDirectory() || Files.isSymbolicLink(parent.toPath()))
            throw new IOException("existing non-symlink directory required");
        if (Files.isSymbolicLink(absolute.toPath()) || (absolute.exists() && !absolute.isFile()))
            throw new IOException("journal must be a regular, non-symlink file");

        RandomAccessFile opened = new RandomAccessFile(absolute, "rw");
        FileLock acquired = null;
        try {
            try {
                acquired = opened.getChannel().tryLock();
            } catch (OverlappingFileLockException locked) {
                throw new IOException("journal already held by another writer", locked);
            }
            if (acquired == null) throw new IOException("journal already locked");
            if (opened.length() > maxBytes) throw new QuotaExceededException("existing journal exceeds quota");
            List<Entry> entries = scan(opened);
            this.lastSequence = entries.isEmpty() ? 0 : entries.get(entries.size() - 1).sequence;
        } catch (IOException | RuntimeException failed) {
            if (acquired != null) {
                try { acquired.release(); } catch (IOException suppressed) { failed.addSuppressed(suppressed); }
            }
            try { opened.close(); } catch (IOException suppressed) { failed.addSuppressed(suppressed); }
            throw failed;
        }
        this.file = opened;
        this.lock = acquired;
        this.maxBytes = maxBytes;
    }

    /** Synchronous fsync: call only from an isolated test/worker, NEVER ECU callback. */
    public synchronized Entry append(String tsvRow) throws IOException {
        ensureUsable();
        byte[] data = encode(tsvRow);
        if (data.length > MAX_ROW_BYTES)
            throw new IllegalArgumentException("TSV row exceeds prototype limit");
        long oldLength = file.length();
        long required = HEADER_BYTES + (long) data.length;
        if (required > maxBytes - oldLength)
            throw new QuotaExceededException("journal quota reached; stop producer and retain source");
        long next = lastSequence + 1;
        if (next <= 0) throw new IOException("sequence exhausted");
        CRC32 crc = new CRC32();
        crc.update(data);
        try {
            file.seek(oldLength);
            file.writeInt(FRAME_MAGIC);
            file.writeInt(data.length);
            file.writeLong(next);
            file.writeInt((int) crc.getValue());
            file.write(data);
            file.getFD().sync();
        } catch (IOException | RuntimeException failure) {
            try {
                file.setLength(oldLength);
                file.getFD().sync();
            } catch (IOException rollback) {
                rollbackFailed = true;
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
        lastSequence = next;
        return new Entry(oldLength, next, tsvRow);
    }

    /** Replay is read-only. Never auto-skip, compact, or truncate corrupt data. */
    public synchronized List<Entry> replay() throws IOException {
        ensureUsable();
        return Collections.unmodifiableList(scan(file));
    }

    public synchronized long sizeBytes() throws IOException {
        ensureUsable();
        return file.length();
    }

    private void ensureUsable() throws IOException {
        if (closed) throw new IOException("journal closed");
        if (rollbackFailed) throw new IOException("journal rollback failed; operator review required");
    }

    private static byte[] encode(String text) throws IOException {
        if (text == null || text.isEmpty() || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
            throw new IllegalArgumentException("exactly one nonempty TSV row without CR/LF required");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
            byte[] out = new byte[encoded.remaining()];
            encoded.get(out);
            return out;
        } catch (CharacterCodingException bad) {
            throw new IOException("invalid Unicode input", bad);
        }
    }

    private static String decode(byte[] raw) throws IOException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString();
            if (text.isEmpty() || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
                throw new IOException("invalid row delimiters");
            return text;
        } catch (CharacterCodingException invalid) {
            throw new IOException("invalid UTF-8 journal payload", invalid);
        }
    }

    private static List<Entry> scan(RandomAccessFile raf) throws IOException {
        long length = raf.length();
        long position = 0;
        long sequence = 0;
        List<Entry> result = new ArrayList<Entry>();
        while (position < length) {
            if (length - position < HEADER_BYTES)
                throw new IOException("incomplete journal header at offset " + position);
            raf.seek(position);
            int magic = raf.readInt();
            int size = raf.readInt();
            long recordedSequence = raf.readLong();
            int expectedCrc = raf.readInt();
            if (magic != FRAME_MAGIC || size <= 0 || size > MAX_ROW_BYTES
                    || recordedSequence != sequence + 1)
                throw new IOException("invalid journal frame at offset " + position);
            if (size > length - (position + HEADER_BYTES))
                throw new IOException("incomplete journal payload at offset " + position);
            byte[] raw = new byte[size];
            raf.readFully(raw);
            CRC32 crc = new CRC32();
            crc.update(raw);
            if (expectedCrc != (int) crc.getValue())
                throw new IOException("journal CRC mismatch at offset " + position);
            String row = decode(raw);
            result.add(new Entry(position, recordedSequence, row));
            sequence = recordedSequence;
            position += HEADER_BYTES + size;
        }
        return result;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); }
        finally { file.close(); }
    }
}
