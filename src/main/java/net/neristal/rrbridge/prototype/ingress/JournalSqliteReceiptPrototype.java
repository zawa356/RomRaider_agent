package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/**
 * D2b-2c-4b-2a: isolated REAL SQLite receipt/cursor prototype.
 *
 * NOT v1_records or the product server ACK. Receipts store raw immutable
 * journal frames only. No TSV domain/profile conversion or ECU integration.
 * Receipt and cursor advance commit in ONE SQLite transaction.
 */
public final class JournalSqliteReceiptPrototype {
    private final String jdbcUrl;
    private final Runnable beforeCommit;

    public JournalSqliteReceiptPrototype(File database) throws Exception {
        this(database, null);
    }
    // Test-only fault injection: crash before commit must roll back BOTH writes.
    JournalSqliteReceiptPrototype(File database, Runnable beforeCommit) throws Exception {
        if (database == null) throw new IllegalArgumentException("DB path required");
        File f = database.getAbsoluteFile();
        if (f.getParentFile() == null || !f.getParentFile().isDirectory()
            || java.nio.file.Files.isSymbolicLink(f.toPath()))
            throw new IllegalArgumentException("pre-existing directory and non-symlink DB required");
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + f.getAbsolutePath();
        this.beforeCommit = beforeCommit;
        try (Connection db = open(); Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS proto_ingress_rows(" +
                "source_uuid TEXT NOT NULL, seq INTEGER NOT NULL, " +
                "record_id TEXT NOT NULL UNIQUE, body_sha256 TEXT NOT NULL, " +
                "body_tsv TEXT NOT NULL, PRIMARY KEY(source_uuid,seq))");
            s.execute("CREATE TABLE IF NOT EXISTS proto_ingress_cursor(" +
                "source_uuid TEXT PRIMARY KEY, committed_seq INTEGER NOT NULL)");
        }
    }
    private Connection open() throws Exception {
        Connection db = DriverManager.getConnection(jdbcUrl);
        try (Statement q = db.createStatement()) {
            q.execute("PRAGMA journal_mode=WAL");
            q.execute("PRAGMA synchronous=FULL");
            q.execute("PRAGMA busy_timeout=2000");
        }
        return db;
    }
    private static String sha(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String recordId(String sourceUuid, long seq) {
        if (!UUID.fromString(sourceUuid).toString().equals(sourceUuid) || seq < 1)
            throw new IllegalArgumentException("invalid source ID/sequence");
        return UUID.nameUUIDFromBytes(("RomLogger-ingress-prototype|" + sourceUuid + "|" + seq)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
    public long committedSequence(String sourceUuid) throws Exception {
        try (Connection db = open(); PreparedStatement s = db.prepareStatement(
                "SELECT committed_seq FROM proto_ingress_cursor WHERE source_uuid=?")) {
            s.setString(1, sourceUuid);
            try (ResultSet rows = s.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }
    public long rowCount() throws Exception {
        try (Connection db = open(); Statement s = db.createStatement();
             ResultSet rows = s.executeQuery("SELECT count(*) FROM proto_ingress_rows")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * Replay ALL checked frames to validate previously imported immutable
     * prefix, then transactionally commit up to maxNew new frames.
     * No checkpoint is advanced without the corresponding row in SQLite.
     */
    public int importNew(IngressJournalPrototype journal, JournalIdentityPrototype identity,
                         int maxNew) throws Exception {
        if (journal == null || identity == null || maxNew < 1 || maxNew > 10000)
            throw new IllegalArgumentException("invalid import inputs");
        String source = identity.sourceUuid();
        List<IngressJournalPrototype.Entry> all = journal.replay(); // CRC/fail closed
        try (Connection db = open()) {
            db.setAutoCommit(false);
            try {
                long cursor = 0;
                try (PreparedStatement c = db.prepareStatement(
                        "SELECT committed_seq FROM proto_ingress_cursor WHERE source_uuid=?")) {
                    c.setString(1, source);
                    try (ResultSet q = c.executeQuery()) {
                        if (q.next()) cursor = q.getLong(1);
                    }
                }
                if (cursor > all.size()) throw new IllegalStateException("journal shrunk after import");
                int imported = 0;
                for (IngressJournalPrototype.Entry entry : all) {
                    String id = recordId(source, entry.sequence);
                    String digest = sha(entry.row);
                    if (entry.sequence <= cursor) {
                        try (PreparedStatement existing = db.prepareStatement(
                                "SELECT record_id,body_sha256,body_tsv FROM proto_ingress_rows " +
                                "WHERE source_uuid=? AND seq=?")) {
                            existing.setString(1, source);
                            existing.setLong(2, entry.sequence);
                            try (ResultSet r = existing.executeQuery()) {
                                if (!r.next() || !id.equals(r.getString(1)) ||
                                    !digest.equals(r.getString(2)) ||
                                    !entry.row.equals(r.getString(3)))
                                    throw new IllegalStateException("immutable journal prefix changed");
                            }
                        }
                        continue;
                    }
                    if (imported == maxNew) break;
                    if (entry.sequence != cursor + imported + 1)
                        throw new IllegalStateException("journal sequence gap");
                    try (PreparedStatement add = db.prepareStatement(
                            "INSERT INTO proto_ingress_rows(" +
                            "source_uuid,seq,record_id,body_sha256,body_tsv) VALUES(?,?,?,?,?)")) {
                        add.setString(1, source);
                        add.setLong(2, entry.sequence);
                        add.setString(3, id);
                        add.setString(4, digest);
                        add.setString(5, entry.row);
                        if (add.executeUpdate() != 1) throw new IllegalStateException("failed insert");
                    }
                    imported++;
                }
                if (imported > 0) {
                    try (PreparedStatement checkpoint = db.prepareStatement(
                            "INSERT INTO proto_ingress_cursor(source_uuid,committed_seq) VALUES(?,?) " +
                            "ON CONFLICT(source_uuid) DO UPDATE SET committed_seq=excluded.committed_seq")) {
                        checkpoint.setString(1, source);
                        checkpoint.setLong(2, cursor + imported);
                        checkpoint.executeUpdate();
                    }
                }
                if (beforeCommit != null) beforeCommit.run();
                db.commit();
                return imported;
            } catch (Exception failed) {
                db.rollback();
                throw failed;
            }
        }
    }
}
