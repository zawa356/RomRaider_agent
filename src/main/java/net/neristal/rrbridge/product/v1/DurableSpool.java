package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Product-v1 durable outbox. Independent of RomRaider classes and the legacy
 * TSV spool; never automatically discards unacknowledged measurements.
 *
 * Phase 2.1-A scope: disk state and ACK bookkeeping only. HTTP, JSON wire
 * serialization, RRBridge callback integration and safety logic are NOT here.
 */
public final class DurableSpool {
    private final String jdbcUrl;

    public static final class PendingRecord {
        public final String recordId, runId, json;
        public final long insertionId;
        PendingRecord(long insertionId, String recordId, String runId, String json) {
            this.insertionId = insertionId;
            this.recordId = recordId;
            this.runId = runId;
            this.json = json;
        }
    }

    public static final class PendingProfile {
        public final String profileId, json;
        PendingProfile(String profileId, String json) {
            this.profileId=profileId;
            this.json=json;
        }
    }

    public static final class Batch {
        public final String batchId, runId;
        public final List<PendingRecord> records;
        Batch(String runId, List<PendingRecord> records) {
            this.batchId = UUID.randomUUID().toString();
            this.runId = runId;
            this.records = Collections.unmodifiableList(new ArrayList<PendingRecord>(records));
        }
    }

    public static final class Ack {
        public final String batchId, status;
        public final int recordCount, insertedCount, duplicateCount;
        public Ack(String batchId, String status, int recordCount,
                   int insertedCount, int duplicateCount) {
            this.batchId = batchId;
            this.status = status;
            this.recordCount = recordCount;
            this.insertedCount = insertedCount;
            this.duplicateCount = duplicateCount;
        }
    }

    public DurableSpool(File file) throws Exception {
        if (file == null) throw new IllegalArgumentException("spool file required");
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Cannot create spool directory");
        }
        Class.forName("org.sqlite.JDBC");
        jdbcUrl = "jdbc:sqlite:" + file.getAbsolutePath();
        try (Connection db = open(); Statement sql = db.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS v1_profiles (" +
                "profile_id TEXT PRIMARY KEY, body_sha256 TEXT NOT NULL," +
                "body_json TEXT, acked INTEGER NOT NULL DEFAULT 0 CHECK (acked IN (0,1)))");
            sql.execute("CREATE TABLE IF NOT EXISTS v1_records (" +
                "local_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "record_id TEXT NOT NULL UNIQUE, run_id TEXT NOT NULL," +
                "segment_id TEXT, sequence INTEGER, kind TEXT NOT NULL," +
                "profile_id TEXT, captured_at TEXT NOT NULL," +
                "source_kind TEXT NOT NULL, body_sha256 TEXT NOT NULL," +
                "body_json TEXT, acked INTEGER NOT NULL DEFAULT 0 CHECK (acked IN (0,1))," +
                "acked_batch_id TEXT, acked_at TEXT," +
                "CHECK (kind IN ('observation','event','gap'))," +
                "CHECK ((kind='observation' AND segment_id IS NOT NULL" +
                " AND profile_id IS NOT NULL AND sequence>=0)" +
                " OR (kind IN ('event','gap') AND sequence IS NULL)))");
            sql.execute("CREATE UNIQUE INDEX IF NOT EXISTS v1_run_sequence " +
                "ON v1_records(run_id,sequence) WHERE kind='observation'");
            sql.execute("CREATE INDEX IF NOT EXISTS v1_unsent ON v1_records(acked,local_id)");
            sql.execute("CREATE TABLE IF NOT EXISTS v1_ack_receipts (" +
                "batch_id TEXT PRIMARY KEY, record_count INTEGER NOT NULL," +
                "inserted_count INTEGER NOT NULL, duplicate_count INTEGER NOT NULL," +
                "acked_at TEXT NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS v1_import_cursors (" +
                "source_path TEXT PRIMARY KEY, device_id TEXT NOT NULL, vehicle_id TEXT NOT NULL," +
                "file_key TEXT NOT NULL, offset_bytes INTEGER NOT NULL CHECK (offset_bytes>=0)," +
                "line_number INTEGER NOT NULL CHECK (line_number>=0)," +
                "head_bytes INTEGER NOT NULL, head_sha256 TEXT NOT NULL," +
                "tail_sha256 TEXT NOT NULL, segment_snapshot TEXT NOT NULL)");

        }
    }


    /**
     * Durable source progress (not Server ACK). Cursor advances only AFTER
     * all records for a completed TSV line have been committed. A crash
     * between record commit and cursor commit replays the line idempotently.
     */
    public static final class ImportCursor {
        public final String sourcePath, deviceId, vehicleId, fileKey;
        public final long offsetBytes, lineNumber;
        public final int headBytes;
        public final String headSha256, tailSha256, snapshot;
        public ImportCursor(String sourcePath, String deviceId, String vehicleId, String fileKey,
                long offsetBytes, long lineNumber, int headBytes, String headSha256,
                String tailSha256, String snapshot) {
            this.sourcePath=sourcePath; this.deviceId=deviceId;this.vehicleId=vehicleId;
            this.fileKey=fileKey; this.offsetBytes=offsetBytes;this.lineNumber=lineNumber;
            this.headBytes=headBytes;this.headSha256=headSha256;
            this.tailSha256=tailSha256;this.snapshot=snapshot;
        }
    }

    public synchronized ImportCursor loadImportCursor(String sourcePath) throws SQLException {
        try(Connection db=open();PreparedStatement p=db.prepareStatement(
            "SELECT * FROM v1_import_cursors WHERE source_path=?")) {
            p.setString(1,sourcePath);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return null;
                return new ImportCursor(r.getString("source_path"),r.getString("device_id"),
                    r.getString("vehicle_id"),r.getString("file_key"),
                    r.getLong("offset_bytes"),r.getLong("line_number"),
                    r.getInt("head_bytes"),r.getString("head_sha256"),
                    r.getString("tail_sha256"),r.getString("segment_snapshot"));
            }
        }
    }

    /**
     * Optimistic CAS prevents a stale importer from rewinding a concurrent
     * importer. This checkpoint is intentionally AFTER appendRecord/profile.
     */
    public synchronized void saveImportCursor(ImportCursor before, ImportCursor after)
            throws SQLException {
        if(after==null||after.sourcePath==null||after.snapshot==null||
           after.offsetBytes<=0||after.lineNumber<=0||
           (before!=null&&(after.offsetBytes<=before.offsetBytes||
              after.lineNumber!=before.lineNumber+1||
              !after.sourcePath.equals(before.sourcePath)||
              !after.deviceId.equals(before.deviceId)||
              !after.vehicleId.equals(before.vehicleId)||
              !after.fileKey.equals(before.fileKey))))
            throw new IllegalArgumentException("Invalid import cursor transition");
        try(Connection db=open()){
            db.setAutoCommit(false);
            try{
                int changed;
                if(before==null){
                    try(PreparedStatement p=db.prepareStatement(
                        "INSERT INTO v1_import_cursors(source_path,device_id,vehicle_id,file_key,"+
                        "offset_bytes,line_number,head_bytes,head_sha256,tail_sha256,segment_snapshot)"+
                        " VALUES(?,?,?,?,?,?,?,?,?,?)")){
                        writeCursor(p,after,1);changed=p.executeUpdate();
                    }
                }else{
                    try(PreparedStatement p=db.prepareStatement(
                        "UPDATE v1_import_cursors SET offset_bytes=?,line_number=?,"+
                        "head_bytes=?,head_sha256=?,tail_sha256=?,segment_snapshot=?"+
                        " WHERE source_path=? AND offset_bytes=? AND line_number=?"+
                        " AND tail_sha256=? AND device_id=? AND vehicle_id=? AND file_key=?")){
                        p.setLong(1,after.offsetBytes);p.setLong(2,after.lineNumber);
                        p.setInt(3,after.headBytes);p.setString(4,after.headSha256);
                        p.setString(5,after.tailSha256);p.setString(6,after.snapshot);
                        p.setString(7,before.sourcePath);p.setLong(8,before.offsetBytes);
                        p.setLong(9,before.lineNumber);p.setString(10,before.tailSha256);
                        p.setString(11,before.deviceId);p.setString(12,before.vehicleId);
                        p.setString(13,before.fileKey);changed=p.executeUpdate();
                    }
                }
                if(changed!=1)throw new IllegalStateException("Import cursor moved concurrently");
                db.commit();
            }catch(Exception ex){db.rollback();throw ex;}
        }
    }
    private static void writeCursor(PreparedStatement p,ImportCursor c,int start)
            throws SQLException {
        p.setString(start,c.sourcePath);p.setString(start+1,c.deviceId);
        p.setString(start+2,c.vehicleId);p.setString(start+3,c.fileKey);
        p.setLong(start+4,c.offsetBytes);p.setLong(start+5,c.lineNumber);
        p.setInt(start+6,c.headBytes);p.setString(start+7,c.headSha256);
        p.setString(start+8,c.tailSha256);p.setString(start+9,c.snapshot);
    }

    private Connection open() throws SQLException {
        Connection db = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = db.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=FULL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        return db;
    }

    private static String sha(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static String uuid(String id) {
        if (id == null) throw new IllegalArgumentException("UUID is required");
        String normalized = UUID.fromString(id).toString();
        if (!normalized.equals(id)) throw new IllegalArgumentException("UUID must be canonical");
        return id;
    }

    private static String json(String body) {
        if (body == null || !body.trim().startsWith("{") || !body.trim().endsWith("}"))
            throw new IllegalArgumentException("Serialized record JSON object required");
        return body;
    }

    private static String timestamp(String value) {
        OffsetDateTime.parse(value);
        return value;
    }

    /** Persist profile JSON before any observations using that profile are sent. */
    public synchronized void appendProfile(String profileId, String bodyJson) throws Exception {
        uuid(profileId); json(bodyJson);
        String hash = sha(bodyJson);
        try (Connection db = open()) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement p = db.prepareStatement(
                    "INSERT OR IGNORE INTO v1_profiles(profile_id,body_sha256,body_json) VALUES (?,?,?)")) {
                    p.setString(1, profileId); p.setString(2, hash); p.setString(3, bodyJson);
                    p.executeUpdate();
                }
                try (PreparedStatement p = db.prepareStatement(
                    "SELECT body_sha256 FROM v1_profiles WHERE profile_id=?")) {
                    p.setString(1, profileId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next() || !hash.equals(r.getString(1)))
                            throw new IllegalStateException("Immutable profile content changed");
                    }
                }
                db.commit();
            } catch (Exception ex) { db.rollback(); throw ex; }
        }
    }

    /** A profile definition must be server-confirmed before sending its samples. */
    public synchronized void markProfileAcked(String profileId, boolean confirmed) throws Exception {
        if (!confirmed) throw new IllegalArgumentException("No confirmed Server profile ACK");
        uuid(profileId);
        try (Connection db = open(); PreparedStatement p = db.prepareStatement(
            "UPDATE v1_profiles SET acked=1,body_json=NULL WHERE profile_id=?")) {
            p.setString(1, profileId);
            if (p.executeUpdate()!=1) throw new IllegalArgumentException("Unknown profile");
        }
    }

    public synchronized List<String> pendingProfiles() throws SQLException {
        List<String> result = new ArrayList<String>();
        try (Connection db = open();
             PreparedStatement p = db.prepareStatement(
                 "SELECT body_json FROM v1_profiles WHERE acked=0 ORDER BY rowid");
             ResultSet r = p.executeQuery()) {
            while(r.next()) result.add(r.getString(1));
        }
        return result;
    }

    /** Read the exact persisted profile UUID/body; don't infer identity from JSON regex. */
    public synchronized List<PendingProfile> pendingProfileEntries() throws SQLException {
        List<PendingProfile> items=new ArrayList<PendingProfile>();
        try(Connection db=open(); PreparedStatement p=db.prepareStatement(
            "SELECT profile_id,body_json FROM v1_profiles WHERE acked=0 ORDER BY rowid");
            ResultSet r=p.executeQuery()) {
            while(r.next()) items.add(new PendingProfile(r.getString(1),r.getString(2)));
        }
        return items;
    }

    /**
     * Commit one immutable observed record. On success it is durable under
     * WAL + synchronous FULL; a later HTTP problem cannot erase it.
     *
     * The caller supplies fully serialized v1 record JSON; wire formatting,
     * ECU compatibility and profile discovery belong to a later phase.
     */
    public synchronized void appendRecord(String recordId, String runId,
        String segmentId, Long sequence, String kind, String profileId,
        String capturedAt, String sourceKind, String bodyJson) throws Exception {
        uuid(recordId); uuid(runId); json(bodyJson); timestamp(capturedAt);
        if (!"observation".equals(kind) && !"event".equals(kind) && !"gap".equals(kind))
            throw new IllegalArgumentException("Unknown record kind");
        if (!"ecu".equals(sourceKind) && !"synthetic".equals(sourceKind)
            && !"agent".equals(sourceKind)) throw new IllegalArgumentException("Unknown source kind");
        if ("observation".equals(kind)) {
            uuid(segmentId); uuid(profileId);
            if (sequence == null || sequence.longValue()<0 ||
                "agent".equals(sourceKind)) throw new IllegalArgumentException("Invalid observation");
        } else if (sequence != null || profileId != null)
            throw new IllegalArgumentException("Event and gap have no sample sequence/profile");
        if (segmentId != null) uuid(segmentId);
        String canonical = recordId+"|"+runId+"|"+String.valueOf(segmentId)+"|"+
            String.valueOf(sequence)+"|"+kind+"|"+String.valueOf(profileId)+"|"+
            capturedAt+"|"+sourceKind+"|"+bodyJson;
        String hash = sha(canonical);
        try (Connection db = open()) {
            db.setAutoCommit(false);
            try {
                try (PreparedStatement p = db.prepareStatement(
                    "INSERT OR IGNORE INTO v1_records(record_id,run_id,segment_id,sequence," +
                    "kind,profile_id,captured_at,source_kind,body_sha256,body_json)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,recordId); p.setString(2,runId);
                    p.setString(3,segmentId);
                    if (sequence==null) p.setNull(4, java.sql.Types.BIGINT);
                    else p.setLong(4,sequence.longValue());
                    p.setString(5,kind); p.setString(6,profileId);
                    p.setString(7,capturedAt); p.setString(8,sourceKind);
                    p.setString(9,hash); p.setString(10,bodyJson);
                    p.executeUpdate();
                }
                try (PreparedStatement p = db.prepareStatement(
                    "SELECT body_sha256 FROM v1_records WHERE record_id=?")) {
                    p.setString(1,recordId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next() || !hash.equals(r.getString(1)))
                            throw new IllegalStateException("Immutable record ID reused with different content");
                    }
                }
                // INSERT OR IGNORE also suppresses unique(run_id,sequence).
                // Explicitly detect and reject a different record at that position.
                if ("observation".equals(kind)) {
                    try (PreparedStatement p = db.prepareStatement(
                        "SELECT record_id FROM v1_records WHERE run_id=? AND sequence=? AND kind='observation'")) {
                        p.setString(1,runId); p.setLong(2,sequence.longValue());
                        try (ResultSet r = p.executeQuery()) {
                            if (!r.next() || !recordId.equals(r.getString(1)))
                                throw new IllegalStateException("Run sequence collision");
                        }
                    }
                }
                db.commit();
            } catch(Exception ex) { db.rollback(); throw ex; }
        }
    }

    /** First unACKed run only. Never mix run IDs in a Server batch. */
    public synchronized Batch nextBatch(int maxRecords, int maxUtf8Bytes) throws Exception {
        if (maxRecords<1 || maxRecords>128 || maxUtf8Bytes<128)
            throw new IllegalArgumentException("Invalid batch bounds");
        List<PendingRecord> rows=new ArrayList<PendingRecord>();
        String run=null; int size=0;
        try (Connection db=open(); PreparedStatement p=db.prepareStatement(
            "SELECT r.local_id,r.record_id,r.run_id,r.body_json FROM v1_records r "+
            "WHERE r.acked=0 AND (r.profile_id IS NULL OR EXISTS "+
            "(SELECT 1 FROM v1_profiles d WHERE d.profile_id=r.profile_id AND d.acked=1)) "+
            "ORDER BY r.local_id LIMIT ?")) {
            p.setInt(1,maxRecords+1);
            try (ResultSet result=p.executeQuery()) {
                while(result.next() && rows.size()<maxRecords) {
                    String candidateRun=result.getString(3);
                    if (run!=null && !run.equals(candidateRun)) break;
                    String body=result.getString(4);
                    int utf8=body.getBytes(StandardCharsets.UTF_8).length;
                    if (utf8+size>maxUtf8Bytes) {
                        if (rows.isEmpty()) throw new IllegalStateException("First pending record exceeds byte limit");
                        break;
                    }
                    if (run==null) run=candidateRun;
                    rows.add(new PendingRecord(result.getLong(1),result.getString(2),
                                               candidateRun,body));
                    size+=utf8;
                }
            }
        }
        return rows.isEmpty() ? null : new Batch(run,rows);
    }

    /**
     * Only a matching, complete Server ACK may release the original JSON.
     * If the network fails or the ACK is partial/malformed, DO NOT call this.
     * An ACK is bound to the exact batch object/record IDs, not just a count.
     */
    public synchronized void markBatchAcked(Batch batch, Ack ack) throws Exception {
        if (batch==null || ack==null || !batch.batchId.equals(ack.batchId) ||
            !"committed".equals(ack.status) || batch.records.isEmpty() ||
            ack.recordCount!=batch.records.size() ||
            ack.insertedCount<0 || ack.duplicateCount<0 ||
            ack.insertedCount+ack.duplicateCount!=ack.recordCount)
            throw new IllegalArgumentException("Unconfirmed or partial batch ACK");
        Set<String> seen=new HashSet<String>();
        try (Connection db=open()) {
            db.setAutoCommit(false);
            try {
                for (PendingRecord row:batch.records) {
                    if (!seen.add(row.recordId)) throw new IllegalArgumentException("Duplicate ACK record ID");
                    try (PreparedStatement p=db.prepareStatement(
                        "UPDATE v1_records SET acked=1,body_json=NULL,"+
                        "acked_batch_id=?,acked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') "+
                        "WHERE record_id=? AND local_id=? AND acked=0 AND run_id=?")) {
                        p.setString(1,ack.batchId);
                        p.setString(2,row.recordId);
                        p.setLong(3,row.insertionId);
                        p.setString(4,batch.runId);
                        if (p.executeUpdate()!=1)
                            throw new IllegalStateException("Batch changed, missing or already ACKed");
                    }
                }
                try (PreparedStatement p=db.prepareStatement(
                    "INSERT INTO v1_ack_receipts(batch_id,record_count,inserted_count,duplicate_count,acked_at)"+
                    " VALUES(?,?,?, ?, strftime('%Y-%m-%dT%H:%M:%fZ','now'))")) {
                    p.setString(1,batch.batchId);p.setInt(2,ack.recordCount);
                    p.setInt(3,ack.insertedCount);p.setInt(4,ack.duplicateCount);
                    p.executeUpdate();
                }
                db.commit();
            }catch(Exception ex){db.rollback();throw ex;}
        }
    }

    public synchronized long countPending() throws Exception {
        try (Connection db=open();Statement s=db.createStatement();
             ResultSet r=s.executeQuery("SELECT count(*) FROM v1_records WHERE acked=0")) {
            return r.next() ? r.getLong(1) : 0L;
        }
    }

    public synchronized long countAcked() throws Exception {
        try (Connection db=open();Statement s=db.createStatement();
             ResultSet r=s.executeQuery("SELECT count(*) FROM v1_records WHERE acked=1")) {
            return r.next()?r.getLong(1):0L;
        }
    }
}
