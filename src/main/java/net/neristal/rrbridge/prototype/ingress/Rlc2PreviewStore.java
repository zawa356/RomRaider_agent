package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Quarantined synthetic SQLite sink. Its tables cannot be consumed by a v1 sender. */
public final class Rlc2PreviewStore {
    private static final int APPLICATION_ID=0x524c5031; // RLP1
    public enum Stage { CLAIMS_WRITTEN, RECEIPTS_WRITTEN, BEFORE_COMMIT, AFTER_COMMIT }
    public interface Hook { void at(Stage stage)throws Exception; }
    public static final Hook NO_FAULT=new Hook(){public void at(Stage stage){}};
    interface TransactionGuard {
        void beforeWrite(Connection db)throws Exception;
        void claimsWritten(Connection db)throws Exception;
        void beforeCommit(Connection db)throws Exception;
    }
    public static final class Result {
        public final int recordsAdded,receiptsAdded;
        public final boolean ackEligible=false;
        Result(int records,int receipts){recordsAdded=records;receiptsAdded=receipts;}
    }
    private final String jdbc;
    private final boolean integration;
    public Rlc2PreviewStore(boolean enableSynthetic,File database)throws Exception {
        this(enableSynthetic,database,false);
    }
    Rlc2PreviewStore(boolean enableSynthetic,File database,boolean integration)throws Exception {
        this.integration=integration;
        if(!enableSynthetic)throw new IllegalStateException("synthetic preview store disabled");
        if(database==null||!database.getName().endsWith(integration?".rlc2-integration.db":".rlc2-preview.db"))throw new IllegalArgumentException("dedicated store extension required");
        File path=database.getAbsoluteFile();IngressDiagnostics.safePath(path.toPath());
        if(path.getParentFile()==null||!path.getParentFile().isDirectory())throw new IOException("existing preview parent directory required");
        Class.forName("org.sqlite.JDBC");
        boolean exists=Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS);
        if(!exists&&integration){Rlc2IntegrationSchema.initialize(true,path);exists=true;}
        if(exists) {
            if(!Files.isRegularFile(path.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("regular preview DB required");
            // Input ledgers remain immutable/quiescent. An owned output may need SQLite's
            // normal rollback recovery after a killed transaction; never delete its journal.
            File journal=new File(path.getPath()+"-journal");
            if(Files.exists(journal.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                verifyRecoveryOwner(path,journal);
                try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+path.toURI().toASCIIString()+"?mode=rw")) { owned(db); }
            } else {
                // Verify ownership before any writable open/DDL, including a renamed product DB.
                try(Connection db=IngressDiagnostics.readOnly(path)) { owned(db); }
            }
        }
        jdbc="jdbc:sqlite:"+path.getAbsolutePath();
        if(!exists)try(Connection db=open();Statement s=db.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                // Concurrent constructors may have initialized it after our existence check.
                try(ResultSet r=s.executeQuery("PRAGMA application_id")) {
                    r.next();if(r.getInt(1)!=0&&r.getInt(1)!=APPLICATION_ID)throw new IOException("foreign application database");
                }
                s.execute("PRAGMA application_id="+APPLICATION_ID);
                createSchema(s);
                s.execute("COMMIT");
            }catch(Exception e){try{s.execute("ROLLBACK");}catch(SQLException ignored){}throw e;}
        }
    }
    static void createSchema(Statement s)throws SQLException {
                s.execute("CREATE TABLE IF NOT EXISTS preview_sessions(session_uuid TEXT PRIMARY KEY,device TEXT NOT NULL,vehicle TEXT NOT NULL,payload_session TEXT NOT NULL,manifest_generation INTEGER NOT NULL,manifest BLOB NOT NULL,record_count INTEGER NOT NULL,receipt_count INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS preview_claims(device TEXT NOT NULL,vehicle TEXT NOT NULL,payload_session TEXT NOT NULL,lane TEXT NOT NULL,session_uuid TEXT NOT NULL,PRIMARY KEY(device,vehicle,payload_session))");
                s.execute("CREATE TABLE IF NOT EXISTS preview_sources(source_uuid TEXT PRIMARY KEY,session_uuid TEXT NOT NULL REFERENCES preview_sessions(session_uuid),segment_index INTEGER NOT NULL,source_path TEXT NOT NULL UNIQUE,identity_tag INTEGER NOT NULL,identity_hex TEXT NOT NULL,generation INTEGER NOT NULL,seq INTEGER NOT NULL,offset_bytes INTEGER NOT NULL,prefix_sha256 TEXT NOT NULL,sealed INTEGER NOT NULL CHECK(sealed IN (0,1)),UNIQUE(session_uuid,segment_index))");
                s.execute("CREATE TABLE IF NOT EXISTS preview_receipts(source_uuid TEXT NOT NULL REFERENCES preview_sources(source_uuid),seq INTEGER NOT NULL,receipt_id TEXT NOT NULL UNIQUE,frame_offset INTEGER NOT NULL,body_sha256 TEXT NOT NULL,body_tsv TEXT NOT NULL,PRIMARY KEY(source_uuid,seq))");
                s.execute("CREATE TABLE IF NOT EXISTS preview_profiles(profile_id TEXT PRIMARY KEY,body_json TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS preview_records(record_id TEXT PRIMARY KEY,run_id TEXT NOT NULL,segment_id TEXT,sequence INTEGER,kind TEXT NOT NULL CHECK(kind IN ('observation','event')),profile_id TEXT REFERENCES preview_profiles(profile_id),captured_at TEXT NOT NULL,source_kind TEXT NOT NULL CHECK(source_kind IN ('synthetic','agent')),body_json TEXT NOT NULL,session_uuid TEXT NOT NULL REFERENCES preview_sessions(session_uuid),ack_eligible INTEGER NOT NULL DEFAULT 0 CHECK(ack_eligible=0))");
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS preview_run_sequence ON preview_records(run_id,sequence) WHERE kind='observation'");
    }
    private void verifyRecoveryOwner(File path,File journal)throws Exception {
        if(!Files.isRegularFile(journal.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(journal.toPath()))throw new IOException("unsafe preview rollback journal");
        for(String suffix:new String[]{"-wal","-shm"})if(Files.exists(new File(path.getPath()+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("unexpected WAL on rollback preview database");
        byte[] header=new byte[100];
        try(java.io.RandomAccessFile source=new java.io.RandomAccessFile(path,"r")){source.readFully(header);}
        byte[] magic="SQLite format 3\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if(!Arrays.equals(magic,Arrays.copyOf(header,16))||java.nio.ByteBuffer.wrap(header).getInt(68)!=(integration?Rlc2IntegrationSchema.APPLICATION_ID:APPLICATION_ID))
            throw new IOException("rollback recovery requires positively identified preview output");
    }
    private void owned(Connection db)throws Exception {
        if(integration)Rlc2IntegrationSchema.verify(db);
        try(Statement s=db.createStatement()) {
            try(ResultSet r=s.executeQuery("PRAGMA application_id")) { if(!r.next()||r.getInt(1)!=(integration?Rlc2IntegrationSchema.APPLICATION_ID:APPLICATION_ID))throw new IOException("not a store-owned database"); }
            try(ResultSet r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT GLOB 'sqlite_*'")) {
                int count=0;
                while(r.next()) { count++;String name=r.getString(1);if(!Arrays.asList("preview_sessions","preview_claims","preview_sources","preview_receipts","preview_profiles","preview_records").contains(name)&&!(integration&&Arrays.asList("integration_bindings","integration_admissions").contains(name)))throw new IOException("foreign tables in store database"); }
                if(count!=(integration?8:6))throw new IOException("incomplete store schema");
            }
        }
    }
    private Connection open()throws SQLException {
        Connection db=DriverManager.getConnection(jdbc);
        try(Statement s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=3000"); }
        catch(SQLException e){db.close();throw e;}return db;
    }
    private static PreparedStatement sql(Connection db,String text,Object... args)throws SQLException {
        PreparedStatement p=db.prepareStatement(text);
        for(int i=0;i<args.length;i++) { if(args[i] instanceof byte[])p.setBytes(i+1,(byte[])args[i]);else p.setObject(i+1,args[i]); }
        return p;
    }
    private static int update(Connection db,String text,Object... args)throws SQLException {
        try(PreparedStatement p=sql(db,text,args)){return p.executeUpdate();}
    }
    public Result ingest(Rlc2ProductAdapter.Projection projection)throws Exception { return ingest(projection,NO_FAULT); }
    public Result ingest(Rlc2ProductAdapter.Projection p,Hook hook)throws Exception {
        return ingest(p,hook,null);
    }
    Result ingest(Rlc2ProductAdapter.Projection p,Hook hook,TransactionGuard guard)throws Exception {
        if(p.ackEligible||!p.payloadSession.startsWith("synthetic-"))throw new IOException("synthetic ACK-ineligible projection required");
        try(Connection db=open();Statement tx=db.createStatement()) {
            tx.execute("BEGIN IMMEDIATE");boolean committed=false;
            try {
                owned(db);
                if(guard!=null)guard.beforeWrite(db);
                try(ResultSet r=tx.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new IOException("preview foreign key inconsistency");}
                try(PreparedStatement q=sql(db,"SELECT lane,session_uuid FROM preview_claims WHERE device=? AND vehicle=? AND payload_session=?",p.device,p.vehicle,p.payloadSession);ResultSet r=q.executeQuery()) {
                    if(r.next()&&(!"rlc2-preview".equals(r.getString(1))||!p.manifestSession.equals(r.getString(2))))throw new IOException("source lane/session claim conflict");
                }
                try(PreparedStatement q=sql(db,"SELECT device,vehicle,payload_session,manifest,record_count,receipt_count FROM preview_sessions WHERE session_uuid=?",p.manifestSession);ResultSet r=q.executeQuery()) {
                    if(r.next()) {
                        if(!p.device.equals(r.getString(1))||!p.vehicle.equals(r.getString(2))||!p.payloadSession.equals(r.getString(3)))throw new IOException("manifest session ownership changed");
                        RotationManifestPrototype.monotonic(RotationManifestPrototype.decode(r.getBytes(4)),RotationManifestPrototype.decode(p.manifestBytes()));
                        try(PreparedStatement counts=sql(db,"SELECT (SELECT count(*) FROM preview_records WHERE session_uuid=?),(SELECT count(*) FROM preview_receipts r JOIN preview_sources s ON r.source_uuid=s.source_uuid WHERE s.session_uuid=?)",p.manifestSession,p.manifestSession);ResultSet c=counts.executeQuery()) {
                            c.next();if(c.getLong(1)!=r.getLong(5)||c.getLong(2)!=r.getLong(6))throw new IOException("persisted preview row set incomplete");
                        }
                    }
                }
                // Verify the OLD committed set before upserting cursors or inserting any rows.
                // Aggregate counts alone cannot detect a replaced event key (sequence is NULL).
                Map<String,Long> previous=new HashMap<String,Long>();
                try(PreparedStatement q=sql(db,"SELECT source_uuid,seq FROM preview_sources WHERE session_uuid=?",p.manifestSession);ResultSet r=q.executeQuery()) {
                    while(r.next())previous.put(r.getString(1),r.getLong(2));
                }
                Set<String> projectedSources=new HashSet<String>();
                for(Rlc2ProductAdapter.Source source:p.sources)projectedSources.add(source.uuid);
                if(!projectedSources.containsAll(previous.keySet()))throw new IOException("unknown persisted preview source");
                for(Map.Entry<String,Long> old:previous.entrySet())try(PreparedStatement q=sql(db,"SELECT count(*),coalesce(min(seq),0),coalesce(max(seq),0) FROM preview_receipts WHERE source_uuid=?",old.getKey());ResultSet r=q.executeQuery()) {
                    r.next();long n=old.getValue();
                    if(n<0||r.getLong(1)!=n||r.getLong(2)!=(n==0?0:1)||r.getLong(3)!=n)throw new IOException("persisted receipt extent differs from saved highwater");
                }
                int priorRecords=0;boolean newReceipt=false;
                for(Rlc2ProductAdapter.Receipt receipt:p.receipts) {
                    Long seq=previous.get(receipt.sourceUuid);boolean old=seq!=null&&receipt.sequence<=seq.longValue();
                    if(old&&newReceipt)throw new IOException("persisted receipt set is not a projection prefix");
                    if(!old)newReceipt=true;
                    if(old&&(receipt.body.startsWith("S\t")||receipt.body.startsWith("E\t")))priorRecords++;
                }
                Set<String> priorIds=new HashSet<String>();
                for(int i=0;i<priorRecords;i++)priorIds.add(p.records.get(i).id);
                try(PreparedStatement q=sql(db,"SELECT record_id FROM preview_records WHERE session_uuid=?",p.manifestSession);ResultSet r=q.executeQuery()) {
                    while(r.next())if(!priorIds.remove(r.getString(1)))throw new IOException("unknown persisted preview record");
                }
                if(!priorIds.isEmpty())throw new IOException("committed preview record missing; manual review required");
                update(db,"INSERT INTO preview_sessions VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(session_uuid) DO UPDATE SET manifest_generation=excluded.manifest_generation,manifest=excluded.manifest,record_count=excluded.record_count,receipt_count=excluded.receipt_count",p.manifestSession,p.device,p.vehicle,p.payloadSession,p.manifestGeneration,p.manifestBytes(),p.records.size(),p.receipts.size());
                update(db,"INSERT OR IGNORE INTO preview_claims VALUES(?,?,?,'rlc2-preview',?)",p.device,p.vehicle,p.payloadSession,p.manifestSession);
                for(Rlc2ProductAdapter.Source source:p.sources) {
                    WatermarkV2Prototype.Marker m=source.marker;
                    String identity=WatermarkLedgerPrototype.hex(m.identity.value()),prefix=WatermarkLedgerPrototype.hex(m.prefixHash());
                    try(PreparedStatement q=sql(db,"SELECT session_uuid,segment_index,source_path,identity_tag,identity_hex,generation,seq,offset_bytes,prefix_sha256,sealed FROM preview_sources WHERE source_uuid=?",source.uuid);ResultSet r=q.executeQuery()) {
                        if(r.next()) {
                            if(!p.manifestSession.equals(r.getString(1))||source.index!=r.getInt(2)||!source.path.equals(r.getString(3))||m.identity.tag.code!=r.getInt(4)||!identity.equals(r.getString(5)))throw new IOException("persisted source claim changed");
                            long gen=r.getLong(6),seq=r.getLong(7),off=r.getLong(8);boolean samePrefix=prefix.equals(r.getString(9));
                            if(m.generation<gen||m.sequence<seq||m.offset<off||((m.sequence==seq)!=(m.offset==off))||
                               (m.generation==gen&&(m.sequence!=seq||!samePrefix))||(m.sequence==seq&&!samePrefix)||
                               (r.getInt(10)==1&&(!source.sealed||m.sequence!=seq||m.offset!=off||!samePrefix)))throw new IOException("preview highwater or sealed state regressed");
                        }
                    }
                    update(db,"INSERT INTO preview_sources VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_uuid) DO UPDATE SET generation=excluded.generation,seq=excluded.seq,offset_bytes=excluded.offset_bytes,prefix_sha256=excluded.prefix_sha256,sealed=excluded.sealed",source.uuid,p.manifestSession,source.index,source.path,m.identity.tag.code,identity,m.generation,m.sequence,m.offset,prefix,source.sealed?1:0);
                }
                if(guard!=null)guard.claimsWritten(db);
                hook.at(Stage.CLAIMS_WRITTEN);
                int receipts=0,records=0;
                for(Rlc2ProductAdapter.Receipt receipt:p.receipts) {
                    boolean exists;
                    try(PreparedStatement q=sql(db,"SELECT receipt_id,frame_offset,body_sha256,body_tsv FROM preview_receipts WHERE source_uuid=? AND seq=?",receipt.sourceUuid,receipt.sequence);ResultSet r=q.executeQuery()) {
                        exists=r.next();
                        if(exists&&(!receipt.receiptId.equals(r.getString(1))||receipt.offset!=r.getLong(2)||!receipt.bodySha256.equals(r.getString(3))||!receipt.body.equals(r.getString(4))))throw new IOException("preview receipt changed");
                    }
                    if(!exists)receipts+=update(db,"INSERT INTO preview_receipts VALUES(?,?,?,?,?,?)",receipt.sourceUuid,receipt.sequence,receipt.receiptId,receipt.offset,receipt.bodySha256,receipt.body);
                }
                for(Rlc2ProductAdapter.Source source:p.sources)try(PreparedStatement q=sql(db,"SELECT count(*),coalesce(max(seq),0) FROM preview_receipts WHERE source_uuid=?",source.uuid);ResultSet r=q.executeQuery()) {
                    r.next();if(r.getLong(1)!=source.marker.sequence||r.getLong(2)!=source.marker.sequence)throw new IOException("preview receipt extent differs from highwater");
                }
                hook.at(Stage.RECEIPTS_WRITTEN);
                for(Map.Entry<String,String> profile:p.profiles.entrySet()) {
                    boolean exists;
                    try(PreparedStatement q=sql(db,"SELECT body_json FROM preview_profiles WHERE profile_id=?",profile.getKey());ResultSet r=q.executeQuery()) {
                        exists=r.next();if(exists&&!profile.getValue().equals(r.getString(1)))throw new IOException("preview profile changed");
                    }
                    if(!exists)update(db,"INSERT INTO preview_profiles VALUES(?,?)",profile.getKey(),profile.getValue());
                }
                for(Rlc2ProductAdapter.Record record:p.records) {
                    String[] expected={record.run,record.segment,record.sequence==null?null:record.sequence.toString(),record.kind,record.profile,record.capturedAt,record.source,record.json,p.manifestSession,"0"};
                    boolean exists;
                    try(PreparedStatement q=sql(db,"SELECT run_id,segment_id,sequence,kind,profile_id,captured_at,source_kind,body_json,session_uuid,ack_eligible FROM preview_records WHERE record_id=?",record.id);ResultSet r=q.executeQuery()) {
                        exists=r.next();if(exists)for(int i=0;i<expected.length;i++)if(!Objects.equals(expected[i],r.getString(i+1)))throw new IOException("preview product record changed");
                    }
                    if(!exists)records+=update(db,"INSERT INTO preview_records VALUES(?,?,?,?,?,?,?,?,?,?,0)",record.id,record.run,record.segment,record.sequence,record.kind,record.profile,record.capturedAt,record.source,record.json,p.manifestSession);
                }
                hook.at(Stage.BEFORE_COMMIT);if(guard!=null)guard.beforeCommit(db);tx.execute("COMMIT");committed=true;hook.at(Stage.AFTER_COMMIT);
                return new Result(records,receipts);
            }catch(Exception failure) { if(!committed)try{tx.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure; }
        }
    }
}
