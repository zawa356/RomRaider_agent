package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import net.neristal.rrbridge.product.v1.IncrementalTsvBridge;
import net.neristal.rrbridge.product.v1.LegacyWorkerGuard;

/**
 * RLPC1: classify legacy v1 records against available RLC2 originals (Phase IV, synthetic, read-only classification).
 *
 * <p>Legacy v1 wire JSON, claims and cursors cannot reconstruct an RLC2 physical source UUID, committed frame bytes,
 * an RLM1 manifest or a durable receipt. A v1 record is VERIFIED_ORIGINAL only when an operator-supplied original
 * RLC2 session (manifest + durable ledger) re-projects, through the shared v1 converter, to the identical record ID
 * and identical v1 canonical body hash, and the v1 journal claim names one of that session's physical sources.
 * Nothing here fabricates a receipt, writes the v1 DB, or connects any ACK.
 */
public final class LegacyProvenanceClassifier {
    public enum Category { VERIFIED_ORIGINAL, LEGACY_WITHOUT_RLC2_PROVENANCE, CONFLICT, UNKNOWN_OR_INCOMPLETE }
    /** Operator-supplied original: one RLC2 session directory and the durable ledger that recorded it. */
    public static final class Original {
        public final Path directory;public final File ledger;public final String device,vehicle;
        public Original(Path directory,File ledger,String device,String vehicle){this.directory=directory;this.ledger=ledger;this.device=device;this.vehicle=vehicle;}
    }
    public static final class Row {
        public final long localId;public final String recordId,runId,bodySha,ackedBatch,ackedAt;public final boolean acked;public final String bodyJson;
        public final Category category;public final String reason;
        Row(long id,String rid,String run,String sha,boolean acked,String batch,String at,String json,Category c,String reason){
            localId=id;recordId=rid;runId=run;bodySha=sha;this.acked=acked;ackedBatch=batch;ackedAt=at;bodyJson=json;category=c;this.reason=reason;
        }
    }
    public static final class Report {
        public final List<Row> rows;public final Map<String,String> sessions;public final String v1Sha256;
        public final Map<String,Set<String>> verifiedSources;public final List<String> conflicts;
        Report(List<Row> rows,Map<String,String> sessions,String v1Sha,Map<String,Set<String>> verifiedSources,List<String> conflicts){
            this.rows=Collections.unmodifiableList(rows);this.sessions=Collections.unmodifiableMap(sessions);v1Sha256=v1Sha;
            this.verifiedSources=Collections.unmodifiableMap(verifiedSources);this.conflicts=Collections.unmodifiableList(conflicts);
        }
        public long count(Category c){long n=0;for(Row r:rows)if(r.category==c)n++;return n;}
        /** A row moves to the RLI2 candidate only if it verified AND its whole session verified (one CONFLICT blocks the run). */
        public boolean migratable(Row r){return r.category==Category.VERIFIED_ORIGINAL&&Category.VERIFIED_ORIGINAL.name().equals(sessions.get(r.runId));}
        public long migratable(){long n=0;for(Row r:rows)if(migratable(r))n++;return n;}
        /** Counts and codes only: no paths, bodies or IDs. */
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"state\":\"CLASSIFIED\"");
            for(Category c:Category.values())b.append(",\"").append(c.name().toLowerCase(Locale.ROOT)).append("\":").append(count(c));
            Map<String,Integer> reasons=new TreeMap<String,Integer>();for(String c:conflicts)reasons.put(c,reasons.containsKey(c)?reasons.get(c)+1:1);
            b.append(",\"conflict_reasons\":{");boolean first=true;for(Map.Entry<String,Integer> e:reasons.entrySet()){if(!first)b.append(',');first=false;b.append('"').append(e.getKey()).append("\":").append(e.getValue());}
            return b.append("},\"v1_sha256\":\"").append(v1Sha256).append("\",\"receipts_fabricated\":0,\"formal_ack\":false,\"production_authorized\":false,\"ack_eligible\":false}").toString();
        }
    }
    private static String sha(String text)throws Exception {return DeploymentTransition.sha(text.getBytes(StandardCharsets.UTF_8));}
    /** The v1 DurableSpool canonical record hash (same field order and null rendering). */
    static String v1Canonical(Rlc2ProductAdapter.Record r)throws Exception {
        return sha(r.id+"|"+r.run+"|"+String.valueOf(r.segment)+"|"+String.valueOf(r.sequence)+"|"+r.kind+"|"+String.valueOf(r.profile)+"|"+r.capturedAt+"|"+r.source+"|"+r.json);
    }

    /**
     * Read-only. The v1 DB must be closed (no WAL/SHM/journal) and is opened immutable; its bytes are hashed before
     * and after. Each original is projected through the shared adapter (manifest + ledger verification included).
     */
    public static Report classify(boolean enable,boolean quiescent,File v1,List<Original> originals,WatermarkV2Prototype.ReadOpener reader)throws Exception {
        if(!enable||!quiescent)throw new IOException("CLASSIFICATION_REQUIRES_EXPLICIT_QUIESCENCE");
        String before=DeploymentTransition.fileSha(v1);
        // Project every original first; refusal of an original is an INCOMPLETE reason for its records, never a pass.
        Map<String,Map<String,String>> projectedByRun=new HashMap<String,Map<String,String>>();
        Map<String,Set<String>> sourcesByRun=new HashMap<String,Set<String>>();Map<String,String> sessionOfRun=new HashMap<String,String>();
        Map<String,String> brokenRun=new HashMap<String,String>();Map<String,String> sourceOwner=new HashMap<String,String>();
        List<String> conflicts=new ArrayList<String>();
        for(Original o:originals) {
            Rlc2ProductAdapter.Projection p;
            try{p=Rlc2ProductAdapter.project(true,o.directory,o.ledger,o.device,o.vehicle,reader);}
            catch(Exception e){
                // We cannot know which run it was without a valid projection; record as an unusable original.
                conflicts.add("ORIGINAL_UNVERIFIABLE");continue;
            }
            String run=IncrementalTsvBridge.runId(p.device,p.payloadSession);
            Map<String,String> recs=new HashMap<String,String>();for(Rlc2ProductAdapter.Record r:p.records){if(!r.run.equals(run))throw new IOException("ORIGINAL_RUN_MIXED");recs.put(r.id,v1Canonical(r));}
            Set<String> ids=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources){ids.add(s.uuid);String prior=sourceOwner.put(s.uuid,run);if(prior!=null&&!prior.equals(run))brokenRun.put(run,"SOURCE_UUID_DUPLICATED");}
            if(projectedByRun.containsKey(run))brokenRun.put(run,"ORIGINAL_DUPLICATED_FOR_RUN");
            projectedByRun.put(run,recs);sourcesByRun.put(run,ids);sessionOfRun.put(run,p.device+"|"+p.vehicle+"|"+p.payloadSession);
        }
        List<Row> rows=new ArrayList<Row>();Map<String,String> sessions=new TreeMap<String,String>();Map<String,Set<String>> verified=new TreeMap<String,Set<String>>();
        try(Connection c=IngressDiagnostics.readOnly(v1)) {
            // Claims → runs. A journal claim must name one of the original's physical sources to be verifiable.
            Map<String,String> claimKind=new HashMap<String,String>(),claimKey=new HashMap<String,String>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT device_id,vehicle_id,session_id,source_kind,source_key FROM v1_source_claims")) {
                while(r.next()){String run=IncrementalTsvBridge.runId(r.getString(1),r.getString(3));claimKind.put(run,r.getString(4));claimKey.put(run,r.getString(5));}
            }
            Set<String> journalCursorUuids=new HashSet<String>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT source_uuid FROM v1_journal_import_cursors")){while(r.next())journalCursorUuids.add(r.getString(1));}
            Map<String,long[]> receipts=new HashMap<String,long[]>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT batch_id,record_count FROM v1_ack_receipts")){while(r.next())receipts.put(r.getString(1),new long[]{r.getLong(2),0});}
            Set<String> profiles=new HashSet<String>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT profile_id FROM v1_profiles")){while(r.next())profiles.add(r.getString(1));}
            List<Object[]> raw=new ArrayList<Object[]>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT local_id,record_id,run_id,body_sha256,acked,acked_batch_id,acked_at,body_json,profile_id FROM v1_records ORDER BY local_id")) {
                while(r.next()){
                    raw.add(new Object[]{r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)==1,r.getString(6),r.getString(7),r.getString(8),r.getString(9)});
                    if(r.getInt(5)==1&&receipts.containsKey(r.getString(6)))receipts.get(r.getString(6))[1]++;
                }
            }
            Map<String,Set<String>> v1IdsByRun=new HashMap<String,Set<String>>();
            for(Object[] x:raw){String run=(String)x[2];if(!v1IdsByRun.containsKey(run))v1IdsByRun.put(run,new HashSet<String>());v1IdsByRun.get(run).add((String)x[1]);}
            for(Object[] x:raw) {
                long id=(Long)x[0];String rid=(String)x[1],run=(String)x[2],hash=(String)x[3],batch=(String)x[5],at=(String)x[6],json=(String)x[7],profile=(String)x[8];boolean acked=(Boolean)x[4];
                Category cat;String reason;
                if(acked&&(batch==null||!receipts.containsKey(batch))){cat=Category.CONFLICT;reason="ACK_RECEIPT_MISSING";}
                else if(acked&&receipts.get(batch)[1]>receipts.get(batch)[0]){cat=Category.CONFLICT;reason="ACK_RECEIPT_COUNT_EXCEEDED";}
                else if(!acked&&batch!=null){cat=Category.CONFLICT;reason="UNACKED_WITH_BATCH";}
                else if(profile!=null&&!profiles.contains(profile)){cat=Category.UNKNOWN_OR_INCOMPLETE;reason="PROFILE_MISSING";}
                else if(!claimKind.containsKey(run)){cat=Category.UNKNOWN_OR_INCOMPLETE;reason="RUN_WITHOUT_SOURCE_CLAIM";}
                else if(brokenRun.containsKey(run)){cat=Category.CONFLICT;reason=brokenRun.get(run);}
                else if(!projectedByRun.containsKey(run)){cat=Category.LEGACY_WITHOUT_RLC2_PROVENANCE;reason="legacy".equals(claimKind.get(run))?"TSV_LANE_HAS_NO_RLC2_ORIGINAL":"NO_ORIGINAL_SUPPLIED";}
                else if("legacy".equals(claimKind.get(run))){cat=Category.CONFLICT;reason="TSV_CLAIM_WITH_RLC2_ORIGINAL";}
                else if(!sourcesByRun.get(run).contains(claimKey.get(run))||!journalCursorUuids.contains(claimKey.get(run))){cat=Category.CONFLICT;reason="JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE";}
                else {
                    String original=projectedByRun.get(run).get(rid);
                    if(original==null){cat=Category.UNKNOWN_OR_INCOMPLETE;reason="NOT_IN_COMMITTED_ORIGINAL";}
                    else if(!original.equals(hash)){cat=Category.CONFLICT;reason="SAME_RECORD_ID_DIFFERENT_BODY";}
                    else{cat=Category.VERIFIED_ORIGINAL;reason="ORIGINAL_REPROJECTED_EXACTLY";}
                }
                if(cat==Category.CONFLICT)conflicts.add(reason);
                rows.add(new Row(id,rid,run,hash,acked,batch,at,json,cat,reason));
            }
            // Session verdict: every v1 record of the run verified AND the original has no record the v1 lane lacks is not required
            // (an original may have grown); but a run with any non-verified record is not migratable as a whole.
            for(String run:claimKind.keySet()) {
                String verdict=Category.LEGACY_WITHOUT_RLC2_PROVENANCE.name();boolean all=true,any=false;
                for(Row r:rows)if(r.runId.equals(run)){any=true;if(r.category!=Category.VERIFIED_ORIGINAL)all=false;if(r.category==Category.CONFLICT)verdict=Category.CONFLICT.name();}
                if(any&&all){verdict=Category.VERIFIED_ORIGINAL.name();verified.put(run,sourcesByRun.get(run));}
                else if(!any)verdict=Category.UNKNOWN_OR_INCOMPLETE.name();
                sessions.put(run,verdict);
            }
        }
        if(!before.equals(DeploymentTransition.fileSha(v1)))throw new IOException("V1_CHANGED_DURING_CLASSIFICATION");
        return new Report(rows,sessions,before,verified,conflicts);
    }

    // ------------------------------------------------------------------ isolated staging (synthetic opt-in)
    public enum StagingStage { VAULT_CREATED, VAULT_ROWS_WRITTEN, RLI2_WRITTEN, VERIFIED }
    public interface StagingHook { void at(StagingStage stage)throws Exception; }
    public static final StagingHook NO_FAULT=new StagingHook(){public void at(StagingStage s){}};
    public static final int VAULT_APPLICATION_ID=0x524c4c56; // "RLLV"

    /**
     * Writes two NEW isolated files only: an RLLV1 vault holding every v1 row with its category and preserved v1 ACK
     * state (never a formal ACK), and an RLI2 candidate DB filled ONLY from VERIFIED originals through the normal store
     * (receipts come from the original ledger, never from v1). Two files are not one atomic commit: a completion marker is
     * written last, and anything without it is INCOMPLETE; rollback is the operator discarding the new files (originals
     * untouched).
     */
    public static Report stageIsolated(boolean enable,boolean quiescent,File v1,List<Original> originals,WatermarkV2Prototype.ReadOpener reader,
            File vault,File rli2,StagingHook hook)throws Exception {
        if(!enable||!quiescent)throw new IOException("STAGING_REQUIRES_EXPLICIT_QUIESCENCE");
        for(File f:new File[]{vault,rli2,marker(vault)})if(Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("STAGING_TARGET_EXISTS");
        for(File f:new File[]{vault,rli2})if(f.getCanonicalFile().equals(v1.getCanonicalFile()))throw new IOException("STAGING_TARGET_IS_SOURCE");
        try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(v1)) {
            Report report=classify(true,true,v1,originals,reader);
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+vault.getAbsolutePath())) {
                try(Statement s=db.createStatement()) {
                    s.execute("PRAGMA journal_mode=DELETE");s.execute("PRAGMA synchronous=FULL");
                    s.execute("PRAGMA application_id="+VAULT_APPLICATION_ID);s.execute("PRAGMA user_version=1");
                    s.execute("CREATE TABLE vault_meta(version INTEGER PRIMARY KEY CHECK(version=1),v1_sha256 TEXT NOT NULL,formal_ack INTEGER NOT NULL CHECK(formal_ack=0))");
                    s.execute("CREATE TABLE vault_records(v1_local_id INTEGER PRIMARY KEY,record_id TEXT NOT NULL UNIQUE,run_id TEXT NOT NULL,v1_body_sha256 TEXT NOT NULL,body_json TEXT,"
                        +"v1_acked INTEGER NOT NULL CHECK(v1_acked IN (0,1)),v1_acked_batch_id TEXT,v1_acked_at TEXT,category TEXT NOT NULL,reason TEXT NOT NULL,rli2_record INTEGER NOT NULL CHECK(rli2_record IN (0,1)))");
                    s.execute("CREATE TABLE vault_sessions(run_id TEXT PRIMARY KEY,category TEXT NOT NULL)");
                }
                hook.at(StagingStage.VAULT_CREATED);
                db.setAutoCommit(false);
                try(PreparedStatement m=db.prepareStatement("INSERT INTO vault_meta VALUES(1,?,0)")){m.setString(1,report.v1Sha256);m.executeUpdate();}
                try(PreparedStatement q=db.prepareStatement("INSERT INTO vault_records VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                    for(Row r:report.rows){q.setLong(1,r.localId);q.setString(2,r.recordId);q.setString(3,r.runId);q.setString(4,r.bodySha);q.setString(5,r.bodyJson);q.setInt(6,r.acked?1:0);
                        q.setString(7,r.ackedBatch);q.setString(8,r.ackedAt);q.setString(9,r.category.name());q.setString(10,r.reason);q.setInt(11,report.migratable(r)?1:0);q.executeUpdate();}
                }
                try(PreparedStatement q=db.prepareStatement("INSERT INTO vault_sessions VALUES(?,?)")){for(Map.Entry<String,String> e:report.sessions.entrySet()){q.setString(1,e.getKey());q.setString(2,e.getValue());q.executeUpdate();}}
                db.commit();
            }
            hook.at(StagingStage.VAULT_ROWS_WRITTEN);
            new Rlc2IntegrationStore(true,rli2); // initializes the dedicated RLI2 format (CREATE only; target checked absent above)
            for(Original o:originals) {
                Rlc2ProductAdapter.Projection p;try{p=Rlc2ProductAdapter.project(true,o.directory,o.ledger,o.device,o.vehicle,reader);}catch(Exception e){continue;}
                String run=IncrementalTsvBridge.runId(p.device,p.payloadSession);
                if(!Category.VERIFIED_ORIGINAL.name().equals(report.sessions.get(run)))continue;
                importVerified(rli2,p,o);
            }
            hook.at(StagingStage.RLI2_WRITTEN);
            verifyStaged(v1,report,vault,rli2);
            Map<String,String> m=new TreeMap<String,String>();m.put("version","RLPC1-STAGED");m.put("v1_sha256",report.v1Sha256);
            m.put("vault_sha256",DeploymentTransition.fileSha(vault));m.put("rli2_sha256",DeploymentTransition.fileSha(rli2));
            net.neristal.rrbridge.product.v1.DeploymentBinding.createRecord(marker(vault).toPath(),net.neristal.rrbridge.product.v1.DeploymentBinding.encodeRecord(m));
            hook.at(StagingStage.VERIFIED);
            old.verify();
            return report;
        }
    }
    static File marker(File vault){return new File(vault.getPath()+".rlpc1-staged");}
    /**
     * The normal candidate entry refuses sessions the v1 DB already claims, which is exactly the migrated case. This isolated
     * path uses the same transactional backend and records the same binding/admission rows, but only for a VERIFIED run,
     * only into the new output, and with the original's own sources/receipts. It never reads v1 bodies into RLI2.
     */
    private static void importVerified(File rli2,final Rlc2ProductAdapter.Projection p,Original o)throws Exception {
        final String directory=o.directory.toFile().getCanonicalPath(),run=p.records.get(0).run;
        final Set<String> sources=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources)sources.add(s.uuid);
        new Rlc2PreviewStore(true,rli2,true).ingest(p,Rlc2PreviewStore.NO_FAULT,new Rlc2PreviewStore.TransactionGuard(){
            public void beforeWrite(Connection db){}
            public void claimsWritten(Connection db)throws Exception{
                try(PreparedStatement q=db.prepareStatement("INSERT OR IGNORE INTO integration_bindings VALUES(?,?,?,2)")){q.setString(1,p.manifestSession);q.setString(2,directory);q.setString(3,run);q.executeUpdate();}
                for(String uuid:sources)try(PreparedStatement q=db.prepareStatement("INSERT OR IGNORE INTO integration_admissions VALUES(?,?)")){q.setString(1,uuid);q.setString(2,p.manifestSession);q.executeUpdate();}
            }
            public void beforeCommit(Connection db){}
        });
    }

    /** Read-only verification of staged outputs against the v1 source; v1 bytes must be unchanged. */
    public static void verifyStaged(File v1,Report report,File vault,File rli2)throws Exception {
        if(!report.v1Sha256.equals(DeploymentTransition.fileSha(v1)))throw new IOException("V1_CHANGED");
        try(Connection c=IngressDiagnostics.readOnly(vault)) {
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA application_id")){r.next();if(r.getInt(1)!=VAULT_APPLICATION_ID)throw new IOException("VAULT_FORMAT");}
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*),sum(v1_acked),sum(rli2_record) FROM vault_records")) {
                r.next();long acked=0,verified=report.migratable();for(Row x:report.rows)if(x.acked)acked++;
                if(r.getLong(1)!=report.rows.size()||r.getLong(2)!=acked||r.getLong(3)!=verified)throw new IOException("VAULT_ROWS_MISMATCH");
            }
            try(PreparedStatement q=c.prepareStatement("SELECT record_id,v1_body_sha256,v1_acked,v1_acked_batch_id,category FROM vault_records WHERE v1_local_id=?")) {
                for(Row x:report.rows){q.setLong(1,x.localId);try(ResultSet r=q.executeQuery()){
                    if(!r.next()||!x.recordId.equals(r.getString(1))||!x.bodySha.equals(r.getString(2))||(r.getInt(3)==1)!=x.acked||!Objects.equals(x.ackedBatch,r.getString(4))||!x.category.name().equals(r.getString(5)))throw new IOException("VAULT_ROW_CHANGED");
                }}
            }
        }
        Set<String> expected=new HashSet<String>();for(Row x:report.rows)if(report.migratable(x))expected.add(x.recordId);
        Set<String> stored=new HashSet<String>();
        if(rli2.exists())try(Connection c=IngressDiagnostics.readOnly(rli2);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT record_id FROM preview_records")){while(r.next())stored.add(r.getString(1));}
        // Every verified v1 record exists in the RLI2 candidate; extra RLI2 rows can only come from the same verified originals.
        if(!stored.containsAll(expected))throw new IOException("RLI2_MISSING_VERIFIED_RECORD");
        for(Row x:report.rows)if(!report.migratable(x)&&stored.contains(x.recordId))throw new IOException("RLI2_CONTAINS_UNVERIFIED_RECORD");
    }

    /** Status of a staging directory without opening anything writable. */
    public static String stagingState(File vault,File rli2) {
        boolean m=Files.exists(marker(vault).toPath(),LinkOption.NOFOLLOW_LINKS),v=vault.exists(),r=rli2.exists();
        if(!m&&!v&&!r)return "NO_STAGING";
        if(!m)return "INCOMPLETE_REVIEW_REQUIRED";
        try {
            Map<String,String> rec=net.neristal.rrbridge.product.v1.DeploymentBinding.readRecord(marker(vault).toPath());
            if(!"RLPC1-STAGED".equals(rec.get("version"))||!rec.get("vault_sha256").equals(DeploymentTransition.fileSha(vault))||!rec.get("rli2_sha256").equals(DeploymentTransition.fileSha(rli2)))return "STAGED_EVIDENCE_MISMATCH";
            return "STAGED_VERIFIED_INACTIVE";
        }catch(Exception e){return "STAGED_EVIDENCE_MISMATCH";}
    }
    private LegacyProvenanceClassifier(){}
}
