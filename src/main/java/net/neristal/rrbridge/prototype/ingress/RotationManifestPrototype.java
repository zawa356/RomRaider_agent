package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3d-1 independent PoC: journal seal + rotation manifest (RLM1) over RLC2 segments.
 *
 * <p>A session directory holds segment-0001.rlj .. segment-0016.rlj (each with RLI1 identity and
 * RLC2 marker) and {@code rotation.manifest}. Sealed entries are immutable; a segment may only be
 * sealed when its RLC2 marker covers the WHOLE file; growth after seal, orphan segments, reordering,
 * manifest rollback or quota overrun fail closed. Nothing is ever deleted, truncated or repaired.
 * NOT wired to the product writer, Worker, SQLite spool or ACK.
 */
public final class RotationManifestPrototype {
    public static final int MAGIC = 0x524c4d31; // "RLM1"
    public static final short VERSION = 1;
    public static final int MAX_SEGMENTS = 16;
    static final int HEADER = 4 + 2 + 16 + 8 + 1 + 1, SEALED = 4 + 16 + 1 + 1 + 32 + 8 + 8 + 32, ACTIVE = 4 + 16 + 1 + 1 + 32;
    public static final String MANIFEST = "rotation.manifest";

    public enum Stage { SEGMENT_CREATED, TEMP_SYNCED, REPLACED }
    public interface Hook { void at(Stage stage) throws IOException; }
    public static final Hook NO_FAULT = new Hook() { public void at(Stage s) {} };

    public static final class Segment {
        public final int index; public final String sourceUuid; public final WatermarkV2Prototype.SourceIdentity identity;
        public final long finalSequence, finalOffset; private final byte[] prefixHash; // final* only for sealed
        Segment(int index, String uuid, WatermarkV2Prototype.SourceIdentity id, long seq, long off, byte[] hash) {
            this.index = index; sourceUuid = uuid; identity = id; finalSequence = seq; finalOffset = off; prefixHash = hash == null ? null : hash.clone();
        }
        public byte[] prefixHash() { return prefixHash == null ? null : prefixHash.clone(); }
        boolean sameAs(Segment o) {
            return index == o.index && sourceUuid.equals(o.sourceUuid) && identity.equals(o.identity) && finalSequence == o.finalSequence &&
                finalOffset == o.finalOffset && Arrays.equals(prefixHash, o.prefixHash);
        }
    }
    public static final class State {
        public final String sessionUuid; public final long generation; public final List<Segment> sealed; public final Segment active;
        final byte[] raw;
        State(String session, long generation, List<Segment> sealed, Segment active, byte[] raw) {
            sessionUuid = session; this.generation = generation; this.sealed = Collections.unmodifiableList(sealed); this.active = active; this.raw = raw;
        }
    }

    private RotationManifestPrototype() {}
    public static String segmentName(int index) { return String.format(java.util.Locale.ROOT, "segment-%04d.rlj", index); }
    public static Path segment(Path dir, int index) { return dir.resolve(segmentName(index)); }
    static Path manifest(Path dir) { return dir.resolve(MANIFEST); }
    static Path staged(Path dir) { return dir.resolve(MANIFEST + ".tmp"); }

    // ---------------------------------------------------------------- codec
    static byte[] encode(String session, long generation, List<Segment> sealed, Segment active) throws IOException {
        if (generation < 1 || sealed.size() > MAX_SEGMENTS) throw new IOException("manifest bounds");
        ByteBuffer b = ByteBuffer.allocate(HEADER + SEALED * sealed.size() + (active == null ? 0 : ACTIVE) + 32);
        UUID s = UUID.fromString(session);
        b.putInt(MAGIC).putShort(VERSION).putLong(s.getMostSignificantBits()).putLong(s.getLeastSignificantBits()).putLong(generation)
            .put((byte) sealed.size()).put((byte) (active == null ? 0 : 1));
        for (Segment g : sealed) { putHead(b, g); b.putLong(g.finalSequence).putLong(g.finalOffset).put(g.prefixHash); }
        if (active != null) putHead(b, active);
        MessageDigest d = WatermarkV2Prototype.sha(); d.update(b.array(), 0, b.position()); b.put(d.digest());
        return b.array();
    }
    private static void putHead(ByteBuffer b, Segment g) {
        UUID u = UUID.fromString(g.sourceUuid);
        b.putInt(g.index).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits())
            .put((byte) g.identity.tag.code).put((byte) g.identity.tag.length);
        byte[] id = new byte[32]; byte[] v = g.identity.value(); System.arraycopy(v, 0, id, 0, v.length); b.put(id);
    }
    private static Segment getHead(ByteBuffer b, boolean sealed) throws IOException {
        int index = b.getInt(); String uuid = new UUID(b.getLong(), b.getLong()).toString();
        WatermarkV2Prototype.IdentityTag tag = WatermarkV2Prototype.IdentityTag.of(b.get() & 0xff);
        int len = b.get() & 0xff; if (len != tag.length) throw new IOException("manifest identity length");
        byte[] field = new byte[32]; b.get(field);
        for (int i = len; i < 32; i++) if (field[i] != 0) throw new IOException("manifest identity padding");
        WatermarkV2Prototype.SourceIdentity id = new WatermarkV2Prototype.SourceIdentity(tag, Arrays.copyOf(field, len));
        if (!sealed) return new Segment(index, uuid, id, -1, -1, null);
        long seq = b.getLong(), off = b.getLong(); byte[] h = new byte[32]; b.get(h);
        if (off < 1 || off > IngressJournalPrototype.MAX_JOURNAL_BYTES || seq < 1 || seq > off / (IngressJournalPrototype.HEADER_BYTES + 1))
            throw new IOException("sealed bounds inconsistent (empty segments are never sealed)");
        return new Segment(index, uuid, id, seq, off, h);
    }
    static State decode(byte[] raw) throws IOException {
        if (raw.length < HEADER + 32) throw new IOException("partial manifest");
        MessageDigest d = WatermarkV2Prototype.sha(); d.update(raw, 0, raw.length - 32);
        if (!MessageDigest.isEqual(d.digest(), Arrays.copyOfRange(raw, raw.length - 32, raw.length))) throw new IOException("manifest digest mismatch");
        ByteBuffer b = ByteBuffer.wrap(raw);
        if (b.getInt() != MAGIC) throw new IOException("not an RLM1 manifest");
        if (b.getShort() != VERSION) throw new IOException("unsupported manifest version");
        String session = new UUID(b.getLong(), b.getLong()).toString();
        long generation = b.getLong(); int count = b.get() & 0xff, hasActive = b.get() & 0xff;
        if (generation < 1 || count > MAX_SEGMENTS || hasActive > 1 || (count == MAX_SEGMENTS && hasActive == 1)) throw new IOException("manifest header bounds");
        if (raw.length != HEADER + SEALED * count + ACTIVE * hasActive + 32) throw new IOException("manifest length inconsistent");
        List<Segment> sealed = new ArrayList<Segment>(); Set<String> uuids = new HashSet<String>(); Set<WatermarkV2Prototype.SourceIdentity> ids = new HashSet<WatermarkV2Prototype.SourceIdentity>();
        for (int i = 0; i < count; i++) {
            Segment g = getHead(b, true);
            if (g.index != i + 1) throw new IOException("sealed segments out of order");
            if (!uuids.add(g.sourceUuid) || !ids.add(g.identity)) throw new IOException("duplicate segment UUID or identity");
            sealed.add(g);
        }
        Segment active = null;
        if (hasActive == 1) {
            active = getHead(b, false);
            if (active.index != count + 1) throw new IOException("active segment index out of order");
            if (!uuids.add(active.sourceUuid) || !ids.add(active.identity)) throw new IOException("duplicate segment UUID or identity");
        }
        return new State(session, generation, sealed, active, raw);
    }

    /** Previous manifests are immutable history: sealed entries must be a byte-equal prefix. */
    static void monotonic(State prior, State next) throws IOException {
        if (prior == null) return;
        if (!prior.sessionUuid.equals(next.sessionUuid)) throw new IOException("session changed");
        if (next.generation < prior.generation) throw new IOException("manifest rolled back");
        if (next.generation == prior.generation) { if (!Arrays.equals(prior.raw, next.raw)) throw new IOException("same manifest generation, different content"); return; }
        if (next.sealed.size() < prior.sealed.size()) throw new IOException("sealed segments removed");
        for (int i = 0; i < prior.sealed.size(); i++) if (!prior.sealed.get(i).sameAs(next.sealed.get(i))) throw new IOException("sealed segment entry changed");
        if (prior.active != null) {
            Segment same = next.sealed.size() > prior.sealed.size() ? next.sealed.get(prior.sealed.size()) : next.active;
            if (same == null || same.index != prior.active.index || !same.sourceUuid.equals(prior.active.sourceUuid) || !same.identity.equals(prior.active.identity))
                throw new IOException("active segment replaced");
        }
    }

    // ------------------------------------------------------------ persistence
    static byte[] readRaw(Path dir) throws IOException {
        Path m = manifest(dir);
        if (Files.isSymbolicLink(m) || !Files.isRegularFile(m, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing or unsafe manifest");
        long size = Files.size(m);
        if (size > HEADER + SEALED * MAX_SEGMENTS + ACTIVE + 32) throw new IOException("oversized manifest");
        return Files.readAllBytes(m);
    }
    public static State read(Path dir, State previous) throws IOException {
        if (Files.exists(staged(dir), LinkOption.NOFOLLOW_LINKS)) throw new IOException("manifest update pending review");
        State s = decode(readRaw(dir)); monotonic(previous, s); return s;
    }
    private static State write(Path dir, State prior, String session, long generation, List<Segment> sealed, Segment active,
                               WatermarkV2Prototype.Platform platform, Hook hook) throws IOException {
        byte[] bytes = encode(session, generation, sealed, active);
        State next = decode(bytes); monotonic(prior, next);
        if (Files.exists(staged(dir), LinkOption.NOFOLLOW_LINKS)) throw new IOException("manifest update pending review");
        try (FileChannel out = FileChannel.open(staged(dir), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) out.write(b); out.force(true);
        }
        hook.at(Stage.TEMP_SYNCED);
        platform.atomicReplace(staged(dir), manifest(dir));
        hook.at(Stage.REPLACED);
        platform.syncDirectory(dir); // classification is reported by the RLC2 publication, never upgraded here
        if (!Arrays.equals(bytes, readRaw(dir))) throw new IOException("published manifest differs");
        return next;
    }

    private static Segment create(Path dir, int index, WatermarkV2Prototype.ReadOpener opener, Hook hook) throws IOException {
        Path seg = segment(dir, index);
        try (FileChannel c = FileChannel.open(seg, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { c.force(true); }
        String uuid = JournalIdentityPrototype.open(seg.toFile()).sourceUuid();
        hook.at(Stage.SEGMENT_CREATED);
        try (WatermarkV2Prototype.ReadHandle h = opener.open(seg)) { return new Segment(index, uuid, h.identity(), -1, -1, null); }
    }

    /** Start a new session in an EMPTY directory: segment-0001 + manifest gen1. */
    public static State begin(Path dir, WatermarkV2Prototype.ReadOpener opener, WatermarkV2Prototype.Platform platform) throws IOException {
        if (Files.exists(manifest(dir), LinkOption.NOFOLLOW_LINKS) || Files.exists(staged(dir), LinkOption.NOFOLLOW_LINKS) || !segments(dir).isEmpty())
            throw new IOException("session directory not empty");
        Segment first = create(dir, 1, opener, NO_FAULT);
        return write(dir, null, UUID.randomUUID().toString(), 1, new ArrayList<Segment>(), first, platform, NO_FAULT);
    }

    /** Seal the active segment; its RLC2 marker must cover the WHOLE file (no unpublished tail). */
    public static State seal(Path dir, WatermarkV2Prototype.ReadOpener opener, WatermarkV2Prototype.Platform platform, Hook hook) throws IOException {
        State s = read(dir, null);
        if (s.active == null) throw new IOException("no active segment to seal");
        Path seg = segment(dir, s.active.index);
        WatermarkV2Prototype.Committed c = WatermarkV2Prototype.readCommitted(seg.toFile(), opener, null);
        if (!c.marker.identity.equals(s.active.identity) || !c.marker.sourceUuid.equals(s.active.sourceUuid)) throw new IOException("active segment identity mismatch");
        long length;
        try (WatermarkV2Prototype.ReadHandle h = opener.open(seg)) { if (!h.identity().equals(s.active.identity)) throw new IOException("active segment replaced"); length = h.length(); }
        if (c.marker.offset != length) throw new IOException("unpublished tail; publish RLC2 for the whole segment before seal");
        if (c.marker.sequence < 1) throw new IOException("empty segments are never sealed");
        List<Segment> sealed = new ArrayList<Segment>(s.sealed);
        sealed.add(new Segment(s.active.index, s.active.sourceUuid, s.active.identity, c.marker.sequence, c.marker.offset, c.marker.prefixHash()));
        return write(dir, s, s.sessionUuid, s.generation + 1, sealed, null, platform, hook);
    }

    /** Create the next segment (CREATE_NEW, new UUID) after a seal. Quota reached = explicit stop. */
    public static State startNext(Path dir, WatermarkV2Prototype.ReadOpener opener, WatermarkV2Prototype.Platform platform, Hook hook) throws IOException {
        State s = read(dir, null);
        if (s.active != null) throw new IOException("active segment still open");
        if (s.sealed.size() >= MAX_SEGMENTS) throw new IOException("segment quota reached; explicit stop, nothing deleted");
        Segment next = create(dir, s.sealed.size() + 1, opener, hook);
        return write(dir, s, s.sessionUuid, s.generation + 1, new ArrayList<Segment>(s.sealed), next, platform, hook);
    }

    static List<Path> segments(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.rlj")) { for (Path p : ds) out.add(p); }
        Collections.sort(out); return out;
    }

    /** Full verification of the session against the manifest (and an optional previous manifest). */
    public static State verify(Path dir, WatermarkV2Prototype.ReadOpener opener, State previous) throws IOException {
        State s = read(dir, previous);
        Set<String> expected = new HashSet<String>();
        for (Segment g : s.sealed) {
            expected.add(segmentName(g.index));
            Path seg = segment(dir, g.index);
            WatermarkV2Prototype.Committed c = WatermarkV2Prototype.readCommitted(seg.toFile(), opener, null);
            if (!c.marker.identity.equals(g.identity) || !c.marker.sourceUuid.equals(g.sourceUuid) || c.marker.sequence != g.finalSequence ||
                    c.marker.offset != g.finalOffset || !MessageDigest.isEqual(c.marker.prefixHash(), g.prefixHash))
                throw new IOException("sealed segment " + g.index + " differs from manifest");
            try (WatermarkV2Prototype.ReadHandle h = opener.open(seg)) {
                if (!h.identity().equals(g.identity)) throw new IOException("sealed segment " + g.index + " replaced");
                if (h.length() != g.finalOffset) throw new IOException("sealed segment " + g.index + " grew after seal");
            }
        }
        if (s.active != null) {
            expected.add(segmentName(s.active.index));
            try (WatermarkV2Prototype.ReadHandle h = opener.open(segment(dir, s.active.index))) {
                if (!h.identity().equals(s.active.identity)) throw new IOException("active segment replaced");
            }
            if (!JournalIdentityPrototype.readExisting(segment(dir, s.active.index).toFile()).sourceUuid().equals(s.active.sourceUuid))
                throw new IOException("active segment UUID mismatch");
        }
        for (Path p : segments(dir)) if (!expected.contains(p.getFileName().toString())) throw new IOException("orphan segment " + p.getFileName() + "; review required");
        return s;
    }

    /**
     * Ingest a whole session in manifest order. A segment is only ingested once every earlier
     * segment is fully recorded up to its SEALED final sequence (source continuity).
     */
    public static int ingestSession(Path dir, WatermarkLedgerPrototype ledger, WatermarkV2Prototype.ReadOpener opener, State previous) throws Exception {
        State s = verify(dir, opener, previous);
        int added = 0;
        for (Segment g : s.sealed) {
            added += ledger.ingest(segment(dir, g.index).toFile(), opener);
            WatermarkLedgerPrototype.Highwater h = ledger.highwater(g.sourceUuid);
            if (h.sequence != g.finalSequence || h.offset != g.finalOffset) throw new IOException("sealed segment " + g.index + " not fully recorded; stop before next segment");
        }
        if (s.active != null && Files.exists(WatermarkV2Prototype.marker(segment(dir, s.active.index).toFile()), LinkOption.NOFOLLOW_LINKS))
            added += ledger.ingest(segment(dir, s.active.index).toFile(), opener);
        return added;
    }
}
