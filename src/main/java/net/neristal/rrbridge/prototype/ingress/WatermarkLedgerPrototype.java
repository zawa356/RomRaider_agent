package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Phase 3c-1 independent PoC: persistent RLC2 highwater ledger in REAL SQLite.
 *
 * <p>Expected source identity, highwater (generation/sequence/offset/prefix hash) and raw
 * frame receipts advance in ONE SQLite transaction. This closes the two 3b
 * counterexamples for a ledger that has already observed the source: a stale marker is
 * rejected against the persisted highwater, and a marker re-pointed at a copied source is
 * rejected against the persisted expected identity.
 *
 * <p>NOT the product spool (v1_*), NOT the Server ACK, NOT wired to any Worker. The
 * directory durability of the publisher is NOT known here; receipts are therefore never
 * ACK-eligible in this PoC.
 */
public final class WatermarkLedgerPrototype {
    public interface Hook { void beforeCommit() throws Exception; }
    private static final Hook NONE = new Hook() { public void beforeCommit() {} };
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private final String jdbcUrl;

    public WatermarkLedgerPrototype(File database) throws Exception {
        if (database == null) throw new IllegalArgumentException("DB path required");
        File f = database.getAbsoluteFile();
        if (f.getParentFile() == null || !f.getParentFile().isDirectory() || java.nio.file.Files.isSymbolicLink(f.toPath()))
            throw new IllegalArgumentException("pre-existing directory and non-symlink DB required");
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + f.getAbsolutePath();
        try (Connection db = open(); Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS proto_rlc2_sources(" +
                "source_uuid TEXT PRIMARY KEY, source_path TEXT NOT NULL UNIQUE, identity_tag INTEGER NOT NULL, identity_hex TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS proto_rlc2_highwater(" +
                "source_uuid TEXT PRIMARY KEY REFERENCES proto_rlc2_sources(source_uuid), generation INTEGER NOT NULL, " +
                "seq INTEGER NOT NULL, offset_bytes INTEGER NOT NULL, prefix_sha256 TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS proto_rlc2_receipts(" +
                "source_uuid TEXT NOT NULL, seq INTEGER NOT NULL, record_id TEXT NOT NULL UNIQUE, frame_offset INTEGER NOT NULL, " +
                "body_sha256 TEXT NOT NULL, body_tsv TEXT NOT NULL, PRIMARY KEY(source_uuid, seq))");
            // Prototype stand-in for the product lane exclusion (legacy TSV vs journal).
            s.execute("CREATE TABLE IF NOT EXISTS proto_lane_claims(source_path TEXT PRIMARY KEY, lane TEXT NOT NULL CHECK(lane IN ('tsv','journal')))");
        }
    }

    private Connection open() throws SQLException {
        Connection db = DriverManager.getConnection(jdbcUrl);
        try (Statement q = db.createStatement()) {
            q.execute("PRAGMA journal_mode=WAL");
            q.execute("PRAGMA synchronous=FULL");
            q.execute("PRAGMA foreign_keys=ON");
            q.execute("PRAGMA busy_timeout=2000");
        }
        return db;
    }

    static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            int value = b[i] & 0xff;
            out[i * 2] = HEX[value >>> 4]; out[i * 2 + 1] = HEX[value & 15];
        }
        return new String(out);
    }
    private static byte[] unhex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
    private static String sha(String v) {
        return hex(WatermarkV2Prototype.sha().digest(v.getBytes(StandardCharsets.UTF_8)));
    }
    public static String recordId(String sourceUuid, long seq) {
        return java.util.UUID.nameUUIDFromBytes(("RomLogger-rlc2-ledger-prototype|" + sourceUuid + "|" + seq)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String canonicalPath(File source) throws IOException { return source.getCanonicalFile().getPath(); }

    /** Prototype-only: record a legacy TSV lane claim, which must block the journal lane. */
    public void claimTsvLane(File source) throws Exception {
        try (Connection db = open(); PreparedStatement p = db.prepareStatement("INSERT INTO proto_lane_claims(source_path,lane) VALUES(?,'tsv')")) {
            p.setString(1, canonicalPath(source)); p.executeUpdate();
        }
    }

    public static final class Highwater {
        public final long generation, sequence, offset; public final long receipts;
        Highwater(long g, long s, long o, long r) { generation = g; sequence = s; offset = o; receipts = r; }
    }
    public Highwater highwater(String sourceUuid) throws Exception {
        try (Connection db = open();
             PreparedStatement h = db.prepareStatement("SELECT generation,seq,offset_bytes FROM proto_rlc2_highwater WHERE source_uuid=?");
             PreparedStatement c = db.prepareStatement("SELECT count(*) FROM proto_rlc2_receipts WHERE source_uuid=?")) {
            h.setString(1, sourceUuid); c.setString(1, sourceUuid);
            long n; try (ResultSet r = c.executeQuery()) { r.next(); n = r.getLong(1); }
            try (ResultSet r = h.executeQuery()) { return r.next() ? new Highwater(r.getLong(1), r.getLong(2), r.getLong(3), n) : new Highwater(0, 0, 0, n); }
        }
    }

    public int ingest(File source, WatermarkV2Prototype.ReadOpener opener) throws Exception { return ingest(source, opener, NONE); }

    /**
     * Read the RLC2 committed prefix and transactionally record new receipts + highwater.
     * First contact claims the source (expected identity/path); later ingests require that
     * exact identity, path and a non-regressed marker. Any mismatch rolls back and fails closed.
     */
    public int ingest(File source, WatermarkV2Prototype.ReadOpener opener, Hook hook) throws Exception {
        String path = canonicalPath(source);
        String uuid = JournalIdentityPrototype.readExisting(source).sourceUuid();
        try (Connection db = open(); Statement tx = db.createStatement()) {
            // Autocommit stays on at JDBC level; an explicit IMMEDIATE transaction takes the
            // write lock up front so concurrent ingesters serialise (or fail busy) cleanly.
            tx.execute("BEGIN IMMEDIATE");
            try {
                try (PreparedStatement lane = db.prepareStatement("SELECT lane FROM proto_lane_claims WHERE source_path=?")) {
                    lane.setString(1, path);
                    try (ResultSet r = lane.executeQuery()) { if (r.next() && !"journal".equals(r.getString(1))) throw new IOException("source claimed by legacy TSV lane"); }
                }
                WatermarkV2Prototype.SourceIdentity expected = null; String claimedPath = null;
                try (PreparedStatement s = db.prepareStatement("SELECT source_path,identity_tag,identity_hex FROM proto_rlc2_sources WHERE source_uuid=?")) {
                    s.setString(1, uuid);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            claimedPath = r.getString(1);
                            expected = new WatermarkV2Prototype.SourceIdentity(WatermarkV2Prototype.IdentityTag.of(r.getInt(2)), unhex(r.getString(3)));
                        }
                    }
                }
                if (claimedPath != null && !claimedPath.equals(path)) throw new IOException("source UUID already claimed at another path (copy or move)");
                WatermarkV2Prototype.Marker previous = null;
                try (PreparedStatement h = db.prepareStatement("SELECT generation,seq,offset_bytes,prefix_sha256 FROM proto_rlc2_highwater WHERE source_uuid=?")) {
                    h.setString(1, uuid);
                    try (ResultSet r = h.executeQuery()) {
                        if (r.next()) {
                            if (expected == null) throw new IOException("highwater without source claim");
                            previous = new WatermarkV2Prototype.Marker(expected, uuid, r.getLong(1), r.getLong(2), r.getLong(3), unhex(r.getString(4)));
                        }
                    }
                }
                // Persisted highwater is the "previously observed" marker: stale/rolled-back markers fail here.
                WatermarkV2Prototype.Committed c = WatermarkV2Prototype.readCommitted(source, opener, previous);
                if (expected != null && !expected.equals(c.marker.identity)) throw new IOException("marker identity differs from persisted expected identity");
                if (!c.marker.sourceUuid.equals(uuid)) throw new IOException("marker UUID mismatch");
                if (expected == null) {
                    try (PreparedStatement p = db.prepareStatement("INSERT INTO proto_rlc2_sources(source_uuid,source_path,identity_tag,identity_hex) VALUES(?,?,?,?)")) {
                        p.setString(1, uuid); p.setString(2, path); p.setInt(3, c.marker.identity.tag.code); p.setString(4, hex(c.marker.identity.value()));
                        p.executeUpdate();
                    }
                    try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO proto_lane_claims(source_path,lane) VALUES(?,'journal')")) {
                        p.setString(1, path); p.executeUpdate();
                    }
                }
                long high = previous == null ? 0 : previous.sequence;
                int added = 0;
                List<IngressJournalPrototype.Entry> entries = c.entries;
                for (IngressJournalPrototype.Entry e : entries) {
                    String id = recordId(uuid, e.sequence), digest = sha(e.row);
                    if (e.sequence <= high) {
                        try (PreparedStatement q = db.prepareStatement("SELECT record_id,frame_offset,body_sha256,body_tsv FROM proto_rlc2_receipts WHERE source_uuid=? AND seq=?")) {
                            q.setString(1, uuid); q.setLong(2, e.sequence);
                            try (ResultSet r = q.executeQuery()) {
                                if (!r.next() || !id.equals(r.getString(1)) || r.getLong(2) != e.offset || !digest.equals(r.getString(3)) || !e.row.equals(r.getString(4)))
                                    throw new IOException("persisted receipt differs from committed journal prefix");
                            }
                        }
                        continue;
                    }
                    if (e.sequence != high + added + 1) throw new IOException("sequence gap");
                    try (PreparedStatement a = db.prepareStatement("INSERT INTO proto_rlc2_receipts(source_uuid,seq,record_id,frame_offset,body_sha256,body_tsv) VALUES(?,?,?,?,?,?)")) {
                        a.setString(1, uuid); a.setLong(2, e.sequence); a.setString(3, id); a.setLong(4, e.offset); a.setString(5, digest); a.setString(6, e.row);
                        if (a.executeUpdate() != 1) throw new IOException("receipt insert failed");
                    }
                    added++;
                }
                try (PreparedStatement count = db.prepareStatement("SELECT count(*), coalesce(max(seq),0) FROM proto_rlc2_receipts WHERE source_uuid=?")) {
                    count.setString(1, uuid);
                    try (ResultSet r = count.executeQuery()) { r.next(); if (r.getLong(1) != c.marker.sequence || r.getLong(2) != c.marker.sequence) throw new IOException("receipt set does not match marker sequence"); }
                }
                try (PreparedStatement u = db.prepareStatement("INSERT INTO proto_rlc2_highwater(source_uuid,generation,seq,offset_bytes,prefix_sha256) VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(source_uuid) DO UPDATE SET generation=excluded.generation,seq=excluded.seq,offset_bytes=excluded.offset_bytes,prefix_sha256=excluded.prefix_sha256")) {
                    u.setString(1, uuid); u.setLong(2, c.marker.generation); u.setLong(3, c.marker.sequence); u.setLong(4, c.marker.offset); u.setString(5, hex(c.marker.prefixHash()));
                    u.executeUpdate();
                }
                hook.beforeCommit();
                tx.execute("COMMIT");
                return added;
            } catch (Exception failed) {
                try { tx.execute("ROLLBACK"); } catch (SQLException rollback) { failed.addSuppressed(rollback); }
                throw failed;
            }
        }
    }
    /** Test helper: direct SQL against the isolated prototype DB (tampering scenarios). */
    void execute(String sql) throws Exception { try (Connection db = open(); Statement s = db.createStatement()) { s.executeUpdate(sql); } }
}
