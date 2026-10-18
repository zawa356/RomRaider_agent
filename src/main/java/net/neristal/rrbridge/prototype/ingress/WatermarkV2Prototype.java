package net.neristal.rrbridge.prototype.ingress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Phase 3b independent PoC: RLC2 cross-platform durable watermark candidate.
 * NEVER feeds production cursors, outbox, SQLite receipts or ACKs.
 *
 * <p>RLC2 is a NEW format. RLC1 markers are rejected explicitly and are never
 * reinterpreted (RLC1 fileKeyHash has no native-identity meaning). Identity is
 * tagged: POSIX dev/inode and Windows NTFS volume serial/file index are distinct
 * encodings and never compare equal. SHA-256 digests are integrity checks, NOT
 * authentication: an actor able to rewrite marker + source can forge a marker.
 */
public final class WatermarkV2Prototype {
    public static final int MAGIC = 0x524c4332;        // "RLC2"
    public static final int LEGACY_RLC1_MAGIC = 0x524c4331;
    public static final short VERSION = 2;
    public static final int IDENTITY_FIELD = 32;
    public static final int BYTES = 4 + 2 + 1 + 1 + IDENTITY_FIELD + 16 + 8 + 8 + 8 + 32 + 32; // 144

    /** Identity encodings. A value is only comparable with the SAME tag. */
    public enum IdentityTag {
        POSIX_DEV_INODE(1, 16), WINDOWS_NTFS_VOLUME_FILE_INDEX(2, 12);
        public final int code, length;
        IdentityTag(int code, int length) { this.code = code; this.length = length; }
        static IdentityTag of(int code) throws IOException {
            for (IdentityTag t : values()) if (t.code == code) return t;
            throw new IOException("unknown identity tag " + code);
        }
    }

    public static final class SourceIdentity {
        public final IdentityTag tag;
        private final byte[] value;
        public SourceIdentity(IdentityTag tag, byte[] value) throws IOException {
            if (tag == null || value == null || value.length != tag.length) throw new IOException("identity length mismatch");
            boolean zero = true; for (byte b : value) zero &= b == 0;
            if (zero) throw new IOException("empty identity rejected");
            this.tag = tag; this.value = value.clone();
        }
        public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof SourceIdentity && ((SourceIdentity) o).tag == tag && Arrays.equals(((SourceIdentity) o).value, value);
        }
        @Override public int hashCode() { return tag.hashCode() * 31 + Arrays.hashCode(value); }
        @Override public String toString() {
            StringBuilder s = new StringBuilder(tag.name()).append(':');
            for (byte b : value) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return s.toString();
        }
    }

    /** Thrown for a legacy RLC1 marker: migration contract required, never reinterpreted. */
    public static final class LegacyMarkerException extends IOException {
        private static final long serialVersionUID = 1L;
        LegacyMarkerException() { super("legacy RLC1 marker present; RLC2 never reinterprets it (migration review required)"); }
    }

    public static final class Marker {
        public final SourceIdentity identity;
        public final String sourceUuid;
        public final long generation, sequence, offset;
        private final byte[] prefixHash;
        Marker(SourceIdentity identity, String uuid, long generation, long sequence, long offset, byte[] prefixHash) {
            this.identity = identity; sourceUuid = uuid; this.generation = generation;
            this.sequence = sequence; this.offset = offset; this.prefixHash = prefixHash.clone();
        }
        public byte[] prefixHash() { return prefixHash.clone(); }
    }

    /** Observational only. Even OS_REPORTED_SYNCED is NOT a tested physical power-loss guarantee. */
    public enum DirectoryDurability { OS_REPORTED_SYNCED, UNKNOWN }

    public static final class Publication {
        public final Marker marker;
        public final DirectoryDurability directory;
        public final boolean alreadyCommitted;
        Publication(Marker marker, DirectoryDurability directory, boolean already) {
            this.marker = marker; this.directory = directory; alreadyCommitted = already;
        }
        /** This PoC never asserts power-loss durability; downstream DB/ACK gates stay closed. */
        public boolean powerLossDurable() { return false; }
    }

    public static final class Committed {
        public final Marker marker;
        public final List<IngressJournalPrototype.Entry> entries;
        Committed(Marker marker, List<IngressJournalPrototype.Entry> entries) {
            this.marker = marker; this.entries = Collections.unmodifiableList(entries);
        }
    }

    /** Writer-owned source. Identity/read/sync MUST be bound to the writer's own handle where the platform allows. */
    public interface OwnedSource {
        SourceIdentity identity() throws IOException;
        long length() throws IOException;
        byte[] read(long offset, int length) throws IOException;
        void sync() throws IOException;
    }
    /** Reader handle: identity and bytes from the SAME open handle where the platform allows. */
    public interface ReadHandle extends Closeable {
        SourceIdentity identity() throws IOException;
        long length() throws IOException;
        byte[] read(long offset, int length) throws IOException;
    }
    public interface ReadOpener { ReadHandle open(Path source) throws IOException; }
    public interface Platform {
        void atomicReplace(Path staged, Path marker) throws IOException;
        DirectoryDurability syncDirectory(Path directory);
    }

    public enum Stage { JOURNAL_SYNCED, SOURCE_REVERIFIED, TEMP_CREATED, TEMP_WRITTEN, TEMP_SYNCED, REPLACED, DIRECTORY_CHECKED }
    public interface Hook { void at(Stage stage) throws IOException; }
    public static final Hook NO_FAULT = new Hook() { public void at(Stage stage) {} };

    private WatermarkV2Prototype() {}

    public static Path marker(File source) { return new File(source.getPath() + ".commit2").toPath(); }
    public static Path staged(File source) { return new File(source.getPath() + ".commit2.tmp").toPath(); }
    public static Path commitLock(File source) { return new File(source.getPath() + ".commit2.lock").toPath(); }
    /** The RLC1 marker path from Phase 2b-2. Its presence blocks RLC2 publication. */
    public static Path legacyMarker(File source) { return new File(source.getPath() + ".commit").toPath(); }

    static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // ---------------------------------------------------------------- codec
    public static byte[] encode(Marker m) throws IOException {
        if (m.generation < 1) throw new IOException("generation must start at 1");
        bounds(m.sequence, m.offset);
        UUID uuid = UUID.fromString(m.sourceUuid);
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.putInt(MAGIC).putShort(VERSION).put((byte) m.identity.tag.code).put((byte) m.identity.tag.length);
        byte[] id = new byte[IDENTITY_FIELD]; System.arraycopy(m.identity.value, 0, id, 0, m.identity.value.length);
        b.put(id).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        b.putLong(m.generation).putLong(m.sequence).putLong(m.offset).put(m.prefixHash);
        MessageDigest d = sha(); d.update(b.array(), 0, BYTES - 32); b.put(d.digest());
        return b.array();
    }

    public static Marker decode(byte[] raw) throws IOException {
        if (raw.length >= 4 && ByteBuffer.wrap(raw).getInt() == LEGACY_RLC1_MAGIC) throw new LegacyMarkerException();
        if (raw.length != BYTES) throw new IOException("partial or oversized RLC2 marker");
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.getInt() != MAGIC) throw new IOException("not an RLC2 marker");
        MessageDigest d = sha(); d.update(raw, 0, BYTES - 32);
        if (!MessageDigest.isEqual(d.digest(), Arrays.copyOfRange(raw, BYTES - 32, BYTES)))
            throw new IOException("RLC2 marker digest mismatch");
        short version = b.getShort();
        if (version != VERSION) throw new IOException("unsupported RLC2 version " + version);
        IdentityTag tag = IdentityTag.of(b.get() & 0xff);
        int idLength = b.get() & 0xff;
        if (idLength != tag.length) throw new IOException("identity length inconsistent with tag");
        byte[] field = new byte[IDENTITY_FIELD]; b.get(field);
        for (int i = idLength; i < IDENTITY_FIELD; i++) if (field[i] != 0) throw new IOException("nonzero identity padding");
        SourceIdentity identity = new SourceIdentity(tag, Arrays.copyOf(field, idLength));
        String uuid = new UUID(b.getLong(), b.getLong()).toString();
        long generation = b.getLong(), sequence = b.getLong(), offset = b.getLong();
        if (generation < 1) throw new IOException("invalid generation");
        bounds(sequence, offset);
        byte[] hash = new byte[32]; b.get(hash);
        return new Marker(identity, uuid, generation, sequence, offset, hash);
    }

    private static void bounds(long sequence, long offset) throws IOException {
        if (sequence < 0 || offset < 0 || offset > IngressJournalPrototype.MAX_JOURNAL_BYTES ||
                (sequence == 0) != (offset == 0) || sequence > offset / (IngressJournalPrototype.HEADER_BYTES + 1))
            throw new IOException("RLC2 sequence/offset bounds inconsistent");
    }

    // ---------------------------------------------------------- validation
    static long scanSequence(byte[] bytes, List<IngressJournalPrototype.Entry> out) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes); long seq = 0;
        while (b.hasRemaining()) {
            long at = b.position();
            if (b.remaining() < IngressJournalPrototype.HEADER_BYTES) throw new IOException("partial frame header");
            int magic = b.getInt(), length = b.getInt(); long next = b.getLong(); int crc = b.getInt();
            if (magic != IngressJournalPrototype.FRAME_MAGIC || length < 1 || length > IngressJournalPrototype.MAX_ROW_BYTES ||
                    next != seq + 1 || length > b.remaining()) throw new IOException("invalid frame");
            byte[] raw = new byte[length]; b.get(raw); CRC32 c = new CRC32(); c.update(raw);
            if ((int) c.getValue() != crc) throw new IOException("frame CRC mismatch");
            String row = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if (row.isEmpty() || row.indexOf('\r') >= 0 || row.indexOf('\n') >= 0) throw new IOException("invalid row");
            if (out != null) out.add(new IngressJournalPrototype.Entry(at, next, row));
            seq = next;
        }
        return seq;
    }

    private static void safeSource(File source) throws IOException {
        Path p = source.toPath().toAbsolutePath();
        for (Path a = p; a != null; a = a.getParent()) if (Files.isSymbolicLink(a)) throw new IOException("symlink path rejected");
        if (!source.getName().endsWith(".rlj") || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("existing regular .rlj source required");
    }

    static byte[] boundedMarker(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("missing or unsafe marker");
        try (FileChannel f = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = f.size();
            if (size > BYTES || size < 4) throw new IOException("invalid marker size");
            ByteBuffer buf = ByteBuffer.allocate((int) size);
            int stalls = 0;
            while (buf.hasRemaining()) {
                int n = f.read(buf);
                if (n < 0 || (n == 0 && ++stalls > 3)) throw new IOException("truncated marker read");
            }
            if (f.size() != size) throw new IOException("marker changed during read");
            return buf.array();
        }
    }

    private static void monotonic(Marker prior, Marker next, boolean writer) throws IOException {
        if (prior == null) return;
        if (!prior.identity.equals(next.identity)) throw new IOException("source identity changed (copy or replacement)");
        if (!prior.sourceUuid.equals(next.sourceUuid)) throw new IOException("source UUID changed");
        if (next.generation < prior.generation || next.sequence < prior.sequence || next.offset < prior.offset ||
                (next.sequence == prior.sequence) != (next.offset == prior.offset))
            throw new IOException("marker regressed (stale or rolled back)");
        if (next.generation == prior.generation && (next.sequence != prior.sequence || !Arrays.equals(next.prefixHash, prior.prefixHash)))
            throw new IOException("same generation with different content");
        if (writer && next.generation != prior.generation + 1) throw new IOException("generation must advance by one");
        if (next.sequence == prior.sequence && !Arrays.equals(next.prefixHash, prior.prefixHash))
            throw new IOException("committed prefix changed");
    }

    private static byte[] hashOwned(OwnedSource s, long length) throws IOException {
        MessageDigest d = sha();
        for (long at = 0; at < length; ) { int n = (int) Math.min(65536, length - at); d.update(s.read(at, n)); at += n; }
        return d.digest();
    }

    // -------------------------------------------------------------- writer
    /**
     * Publish an RLC2 marker for the writer-owned source.
     *
     * @param expectedLength bytes the writer itself wrote (memory record)
     * @param expectedHash   SHA-256 of exactly those bytes; any post-write rewrite (even CRC-valid) fails
     */
    public static Publication publish(File source, OwnedSource owned, long expectedLength, byte[] expectedHash,
                                      Platform platform, Hook hook) throws IOException {
        safeSource(source);
        Path lockPath = commitLock(source);
        if (Files.isSymbolicLink(lockPath) || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("unsafe commit lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = lockChannel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("concurrent publisher", e); }
            if (lock == null) throw new IOException("concurrent publisher");
            try { return publishLocked(source, owned, expectedLength, expectedHash, platform, hook); }
            finally { lock.release(); }
        }
    }

    private static Publication publishLocked(File source, OwnedSource owned, long expectedLength, byte[] expectedHash,
                                             Platform platform, Hook hook) throws IOException {
        if (Files.exists(legacyMarker(source), LinkOption.NOFOLLOW_LINKS)) throw new LegacyMarkerException();
        if (Files.exists(staged(source), LinkOption.NOFOLLOW_LINKS)) throw new IOException("interrupted RLC2 update retained; review required");
        SourceIdentity id = owned.identity();
        long length = owned.length();
        if (length != expectedLength || length > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("owned length differs from writer record or quota");
        byte[] all = owned.read(0, (int) length);
        if (!MessageDigest.isEqual(sha().digest(all), expectedHash)) throw new IOException("owned bytes differ from writer record");
        long sequence = scanSequence(all, null);
        String uuid = JournalIdentityPrototype.readExisting(source).sourceUuid();
        Marker old = null;
        if (Files.exists(marker(source), LinkOption.NOFOLLOW_LINKS)) {
            old = decode(boundedMarker(marker(source)));
            if (old.offset > length || !MessageDigest.isEqual(old.prefixHash, sha().digest(Arrays.copyOf(all, (int) old.offset))))
                throw new IOException("committed prefix changed or marker ahead of source");
        }
        if (old != null && old.sequence == sequence && old.offset == length) {
            monotonic(old, new Marker(id, uuid, old.generation, sequence, length, old.prefixHash), false);
            return new Publication(old, platform.syncDirectory(source.toPath().toAbsolutePath().getParent()), true);
        }
        Marker next = new Marker(id, uuid, old == null ? 1 : old.generation + 1, sequence, length, sha().digest(all));
        monotonic(old, next, true);

        owned.sync(); hook.at(Stage.JOURNAL_SYNCED);
        // Re-verify identity and original bytes AFTER sync, through the owned handle.
        if (!id.equals(owned.identity()) || owned.length() != length || !MessageDigest.isEqual(hashOwned(owned, length), next.prefixHash) ||
                !uuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid()))
            throw new IOException("source changed during commit; review required");
        hook.at(Stage.SOURCE_REVERIFIED);

        byte[] encoded = encode(next);
        Path tmp = staged(source);
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer half = ByteBuffer.wrap(encoded, 0, BYTES / 2); while (half.hasRemaining()) out.write(half);
            hook.at(Stage.TEMP_CREATED);
            ByteBuffer rest = ByteBuffer.wrap(encoded, BYTES / 2, BYTES - BYTES / 2); while (rest.hasRemaining()) out.write(rest);
            hook.at(Stage.TEMP_WRITTEN);
            out.force(true);
        }
        hook.at(Stage.TEMP_SYNCED);
        if (!id.equals(owned.identity())) throw new IOException("source identity changed before replace");
        // No non-atomic fallback; a failed replace retains the staged file for review.
        platform.atomicReplace(tmp, marker(source));
        hook.at(Stage.REPLACED);
        DirectoryDurability directory = platform.syncDirectory(source.toPath().toAbsolutePath().getParent());
        hook.at(Stage.DIRECTORY_CHECKED);
        if (!Arrays.equals(encoded, boundedMarker(marker(source)))) throw new IOException("published marker differs");
        return new Publication(next, directory, false);
    }

    // -------------------------------------------------------------- reader
    /** Returns only the validated committed prefix; tail beyond the watermark is never included. */
    public static Committed readCommitted(File source, ReadOpener opener, Marker previouslyObserved) throws IOException {
        safeSource(source);
        if (Files.exists(staged(source), LinkOption.NOFOLLOW_LINKS)) throw new IOException("RLC2 update pending review or retry");
        byte[] markerBytes = boundedMarker(marker(source));
        Marker m = decode(markerBytes);
        monotonic(previouslyObserved, m, false);
        try (ReadHandle h = opener.open(source.toPath())) {
            SourceIdentity id = h.identity();
            if (!m.identity.equals(id)) throw new IOException("source identity mismatch (copy or replacement)");
            if (!m.sourceUuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid())) throw new IOException("source UUID mismatch");
            long size = h.length();
            if (size < m.offset || size > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("source shorter than marker or over quota");
            byte[] prefix = h.read(0, (int) m.offset);
            if (!MessageDigest.isEqual(sha().digest(prefix), m.prefixHash)) throw new IOException("committed prefix modified");
            if (previouslyObserved != null && !MessageDigest.isEqual(previouslyObserved.prefixHash,
                    sha().digest(Arrays.copyOf(prefix, (int) previouslyObserved.offset))))
                throw new IOException("previously observed prefix modified");
            List<IngressJournalPrototype.Entry> entries = new ArrayList<IngressJournalPrototype.Entry>();
            if (scanSequence(prefix, entries) != m.sequence) throw new IOException("marker sequence mismatch");
            if (!id.equals(h.identity()) || h.length() < m.offset || Files.exists(staged(source), LinkOption.NOFOLLOW_LINKS) ||
                    !Arrays.equals(markerBytes, boundedMarker(marker(source))))
                throw new IOException("source or marker changed during read");
            return new Committed(m, entries);
        }
    }

    // ------------------------------------------------------ POSIX (Java only)
    /**
     * POSIX identity from the "unix" attribute view (dev, ino). Java cannot fstat the owned
     * descriptor, so identity is PATH-sampled before/after; this is weaker than Windows
     * handle binding. Platforms without the unix view fail closed.
     */
    public static SourceIdentity posixIdentity(Path path) throws IOException {
        Object dev, ino;
        try {
            dev = Files.getAttribute(path, "unix:dev", LinkOption.NOFOLLOW_LINKS);
            ino = Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException unavailable) {
            throw new IOException("POSIX dev/inode identity unavailable on this platform", unavailable);
        }
        if (!(dev instanceof Long) || !(ino instanceof Long)) throw new IOException("POSIX identity type unexpected");
        return new SourceIdentity(IdentityTag.POSIX_DEV_INODE, ByteBuffer.allocate(16).putLong((Long) dev).putLong((Long) ino).array());
    }

    /** Owned POSIX source: writes and sync through the writer's RandomAccessFile. */
    public static final class PosixOwnedSource implements OwnedSource {
        private final Path path; private final RandomAccessFile file;
        public PosixOwnedSource(Path path, RandomAccessFile file) { this.path = path; this.file = file; }
        public SourceIdentity identity() throws IOException {
            SourceIdentity before = posixIdentity(path);
            // Path and owned handle must agree in size; a rename-over is otherwise caught by hash checks.
            if (Files.size(path) != file.length() || !before.equals(posixIdentity(path))) throw new IOException("path/handle disagreement");
            return before;
        }
        public long length() throws IOException { return file.length(); }
        public byte[] read(long offset, int length) throws IOException {
            byte[] out = new byte[length]; file.seek(offset); file.readFully(out); return out;
        }
        public void sync() throws IOException { file.getFD().sync(); }
    }

    public static final ReadOpener POSIX_READER = new ReadOpener() {
        public ReadHandle open(final Path source) throws IOException {
            final SourceIdentity opened = posixIdentity(source);
            final FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);
            return new ReadHandle() {
                public SourceIdentity identity() throws IOException {
                    SourceIdentity now = posixIdentity(source);
                    if (!now.equals(opened) || Files.size(source) != channel.size()) throw new IOException("path replaced while reading");
                    return now;
                }
                public long length() throws IOException { return channel.size(); }
                public byte[] read(long offset, int length) throws IOException {
                    ByteBuffer b = ByteBuffer.allocate(length); int stalls = 0;
                    while (b.hasRemaining()) {
                        int n = channel.read(b, offset + b.position());
                        if (n < 0 || (n == 0 && ++stalls > 3)) throw new IOException("short read");
                    }
                    return b.array();
                }
                public void close() throws IOException { channel.close(); }
            };
        }
    };

    public static final Platform POSIX_PLATFORM = new Platform() {
        public void atomicReplace(Path staged, Path marker) throws IOException {
            Files.move(staged, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        public DirectoryDurability syncDirectory(Path directory) {
            try (FileChannel d = FileChannel.open(directory, StandardOpenOption.READ)) { d.force(true); return DirectoryDurability.OS_REPORTED_SYNCED; }
            catch (IOException | UnsupportedOperationException unavailable) { return DirectoryDurability.UNKNOWN; }
        }
    };
}
