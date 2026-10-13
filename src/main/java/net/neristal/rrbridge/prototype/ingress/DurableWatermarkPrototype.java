package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/** Independent synthetic PoC. NEVER feeds production cursors, outbox or ACKs. */
public final class DurableWatermarkPrototype {
    public static final int MAGIC = 0x524c4331; // RLC1, experimental version 1
    public static final int BYTES = 104;
    public enum Stage { JOURNAL_SYNCED, TEMP_SYNCED, RENAMED }
    interface Hook { void at(Stage stage) throws IOException; }
    private static final Hook NO_FAULT = new Hook() { public void at(Stage stage) {} };
    public static final class Snapshot {
        public final String sourceUuid;
        public final long sequence, offset;
        private final byte[] fileKeyHash, prefixHash;
        public final List<IngressJournalPrototype.Entry> entries;
        private Snapshot(String uuid, long sequence, long offset, byte[] key, byte[] hash,
                         List<IngressJournalPrototype.Entry> entries) {
            sourceUuid = uuid; this.sequence = sequence; this.offset = offset;
            fileKeyHash = key.clone(); prefixHash = hash.clone();
            this.entries = Collections.unmodifiableList(entries);
        }
    }
    /** Directory force is observational, NOT a physical power-loss guarantee. */
    public enum DirectorySync { SYNCED, UNCONFIRMED }
    private DurableWatermarkPrototype() {}
    private static Path marker(File source) { return new File(source.getPath() + ".commit").toPath(); }
    private static Path temp(File source) { return new File(source.getPath() + ".commit.tmp").toPath(); }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static BasicFileAttributes attributes(File source) throws IOException {
        Path path = source.toPath();
        if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.toAbsolutePath().getParent()) ||
                !source.getName().endsWith(".rlj")) throw new IOException("unsafe source");
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile() || a.size() > IngressJournalPrototype.MAX_JOURNAL_BYTES || a.fileKey() == null)
            throw new IOException("source identity or quota unavailable");
        return a;
    }
    private static byte[] key(BasicFileAttributes a) {
        return sha().digest(a.fileKey().toString().getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] boundedRead(Path path, int expected) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("unsafe watermark");
        try (FileChannel f = FileChannel.open(path, StandardOpenOption.READ)) {
            if (f.size() != expected) throw new IOException("invalid watermark size");
            ByteBuffer buf = ByteBuffer.allocate(expected);
            fullyRead(f, buf);
            if (f.size() != expected) throw new IOException("watermark changed");
            return buf.array();
        }
    }
    private static void fullyRead(FileChannel channel, ByteBuffer data) throws IOException {
        int stalls = 0;
        while (data.hasRemaining()) {
            int n = channel.read(data);
            if (n < 0 || (n == 0 && ++stalls > 3)) throw new IOException("truncated or stalled read");
            if (n > 0) stalls = 0;
        }
    }
    private static Snapshot decode(byte[] raw) throws IOException {
        CRC32 crc = new CRC32(); crc.update(raw, 0, BYTES - 4);
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.getInt() != MAGIC || (int) crc.getValue() != b.getInt(BYTES - 4))
            throw new IOException("watermark version or CRC mismatch");
        String uuid = new UUID(b.getLong(), b.getLong()).toString();
        long sequence = b.getLong(), offset = b.getLong();
        if (sequence < 0 || offset < 0 || offset > IngressJournalPrototype.MAX_JOURNAL_BYTES ||
                (sequence == 0) != (offset == 0) || sequence > offset / (IngressJournalPrototype.HEADER_BYTES + 1))
            throw new IOException("watermark bounds inconsistent");
        byte[] key = new byte[32], hash = new byte[32]; b.get(key); b.get(hash);
        return new Snapshot(uuid, sequence, offset, key, hash, new ArrayList<IngressJournalPrototype.Entry>());
    }
    private static byte[] encode(Snapshot s) {
        UUID uuid = UUID.fromString(s.sourceUuid);
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.putInt(MAGIC).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
        b.putLong(s.sequence).putLong(s.offset).put(s.fileKeyHash).put(s.prefixHash);
        CRC32 crc = new CRC32(); crc.update(b.array(), 0, BYTES - 4); b.putInt((int) crc.getValue());
        return b.array();
    }
    private static void monotonic(Snapshot prior, Snapshot next) throws IOException {
        if (prior == null) return;
        if (!prior.sourceUuid.equals(next.sourceUuid) || !Arrays.equals(prior.fileKeyHash, next.fileKeyHash) ||
                next.sequence < prior.sequence || next.offset < prior.offset ||
                ((next.sequence == prior.sequence) != (next.offset == prior.offset)) ||
                (next.sequence == prior.sequence && !Arrays.equals(prior.prefixHash, next.prefixHash)))
            throw new IOException("watermark regressed or source replaced");
    }
    private static byte[] ownedHash(RandomAccessFile data, long length) throws IOException {
        if (length > data.length()) throw new IOException("watermark ahead of journal");
        MessageDigest digest = sha(); byte[] chunk = new byte[8192]; data.seek(0);
        long left = length;
        while (left > 0) { int n = (int) Math.min(left, chunk.length); data.readFully(chunk, 0, n); digest.update(chunk, 0, n); left -= n; }
        return digest.digest();
    }

    /** Package-private: only the isolated owner may publish through its SAME handle. */
    static DirectorySync publish(File source, RandomAccessFile owned, long sequence) throws IOException {
        return publish(source, owned, sequence, NO_FAULT);
    }
    static DirectorySync publish(File source, RandomAccessFile owned, long sequence, Hook hook) throws IOException {
        BasicFileAttributes before = attributes(source);
        String uuid = JournalIdentityPrototype.readExisting(source).sourceUuid();
        long length = owned.length();
        if (length != before.size() || SplitLockJournalPrototype.validateOwnedHandle(owned, IngressJournalPrototype.MAX_JOURNAL_BYTES) != sequence)
            throw new IOException("owned source changed");
        Snapshot next = new Snapshot(uuid, sequence, length, key(before), ownedHash(owned, length), new ArrayList<IngressJournalPrototype.Entry>());
        if (Files.exists(temp(source), LinkOption.NOFOLLOW_LINKS)) throw new IOException("interrupted update; review required");
        if (Files.exists(marker(source), LinkOption.NOFOLLOW_LINKS)) {
            Snapshot old = decode(boundedRead(marker(source), BYTES)); monotonic(old, next);
            if (!Arrays.equals(old.prefixHash, ownedHash(owned, old.offset))) throw new IOException("committed prefix changed");
        }
        owned.getFD().sync(); hook.at(Stage.JOURNAL_SYNCED);
        // No uncommitted observation can make a marker visible before journal sync.
        try (FileChannel staged = FileChannel.open(temp(source), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(encode(next)); while (bytes.hasRemaining()) staged.write(bytes);
            staged.force(true);
        }
        hook.at(Stage.TEMP_SYNCED);
        if (!Arrays.equals(key(before), key(attributes(source))) || !uuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid()))
            throw new IOException("source replaced before publish");
        // No non-atomic fallback, and NEVER remove retained temp on failure.
        Files.move(temp(source), marker(source), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        hook.at(Stage.RENAMED);
        try (FileChannel directory = FileChannel.open(source.toPath().toAbsolutePath().getParent(), StandardOpenOption.READ)) {
            directory.force(true); return DirectorySync.SYNCED;
        } catch (IOException | UnsupportedOperationException unavailable) {
            return DirectorySync.UNCONFIRMED;
        }
    }

    /** Separate reader JVM ONLY. Stable fully validated committed prefix; tail ignored. */
    public static Snapshot readCommitted(File source, Snapshot previouslyObserved) throws IOException {
        BasicFileAttributes before = attributes(source);
        if (Files.exists(temp(source), LinkOption.NOFOLLOW_LINKS)) throw new IOException("watermark update pending review or retry");
        byte[] markerBytes = boundedRead(marker(source), BYTES);
        Snapshot wm = decode(markerBytes); monotonic(previouslyObserved, wm);
        if (!wm.sourceUuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid()) ||
                !Arrays.equals(wm.fileKeyHash, key(before)) || wm.offset > before.size())
            throw new IOException("watermark/source mismatch");
        ByteBuffer prefix = ByteBuffer.allocate((int) wm.offset);
        try (FileChannel reader = FileChannel.open(source.toPath(), StandardOpenOption.READ)) { fullyRead(reader, prefix); }
        if (!Arrays.equals(sha().digest(prefix.array()), wm.prefixHash)) throw new IOException("committed prefix modified");
        List<IngressJournalPrototype.Entry> entries = scan(prefix.array(), wm.sequence);
        if (previouslyObserved != null && !Arrays.equals(previouslyObserved.prefixHash,
                sha().digest(Arrays.copyOf(prefix.array(), (int) previouslyObserved.offset))))
            throw new IOException("previously observed prefix modified");
        if (!Arrays.equals(key(before), key(attributes(source))) ||
                !wm.sourceUuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid()) ||
                Files.exists(temp(source), LinkOption.NOFOLLOW_LINKS) ||
                !Arrays.equals(markerBytes, boundedRead(marker(source), BYTES)))
            throw new IOException("source/watermark changed during read");
        return new Snapshot(wm.sourceUuid, wm.sequence, wm.offset, wm.fileKeyHash, wm.prefixHash, entries);
    }
    private static List<IngressJournalPrototype.Entry> scan(byte[] bytes, long expectedSequence) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes); long seq = 0;
        List<IngressJournalPrototype.Entry> entries = new ArrayList<IngressJournalPrototype.Entry>();
        while (b.hasRemaining()) {
            long offset = b.position();
            if (b.remaining() < IngressJournalPrototype.HEADER_BYTES) throw new IOException("watermark cuts header");
            int magic = b.getInt(), length = b.getInt(); long next = b.getLong(); int crc = b.getInt();
            if (magic != IngressJournalPrototype.FRAME_MAGIC || length < 1 || length > IngressJournalPrototype.MAX_ROW_BYTES ||
                    next != seq + 1 || length > b.remaining()) throw new IOException("invalid committed frame");
            byte[] raw = new byte[length]; b.get(raw); CRC32 check = new CRC32(); check.update(raw);
            if ((int) check.getValue() != crc) throw new IOException("committed frame CRC");
            String row = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if (row.isEmpty() || row.indexOf('\r') >= 0 || row.indexOf('\n') >= 0) throw new IOException("invalid committed row");
            entries.add(new IngressJournalPrototype.Entry(offset, next, row)); seq = next;
        }
        if (seq != expectedSequence) throw new IOException("sequence/offset mismatch");
        return entries;
    }
}
