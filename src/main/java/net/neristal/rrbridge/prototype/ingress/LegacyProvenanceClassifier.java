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
import net.neristal.rrbridge.product.v1.DeploymentBinding;

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
        private final Map<String,Rlc2ProductAdapter.Projection> projections;
        private final Map<String,String> directories;
        private final List<Rlc2InputSnapshot> snapshots;
        private final WatermarkV2Prototype.ReadOpener reader;
        Report(List<Row> rows,Map<String,String> sessions,String v1Sha,Map<String,Set<String>> verifiedSources,List<String> conflicts,
                Map<String,Rlc2ProductAdapter.Projection> projections,Map<String,String> directories,List<Rlc2InputSnapshot> snapshots,WatermarkV2Prototype.ReadOpener reader){
            this.rows=Collections.unmodifiableList(rows);this.sessions=Collections.unmodifiableMap(sessions);v1Sha256=v1Sha;
            this.verifiedSources=Collections.unmodifiableMap(verifiedSources);this.conflicts=Collections.unmodifiableList(conflicts);
            this.projections=Collections.unmodifiableMap(projections);this.directories=Collections.unmodifiableMap(directories);
            this.snapshots=Collections.unmodifiableList(snapshots);
            this.reader=reader;
        }
        void verifySnapshots()throws Exception{
            for(Rlc2InputSnapshot s:snapshots)s.verify();
            // Windows BasicFileAttributes.fileKey() is null: identical bytes do not prove the same physical source.
            for(Rlc2ProductAdapter.Projection p:projections.values())for(Rlc2ProductAdapter.Source source:p.sources){
                WatermarkV2Prototype.Committed current=WatermarkV2Prototype.readCommitted(new File(source.path),reader,source.marker);
                if(!Arrays.equals(WatermarkV2Prototype.encode(source.marker),WatermarkV2Prototype.encode(current.marker)))throw new IOException("ORIGINAL_PHYSICAL_SNAPSHOT_CHANGED");
            }
            for(Rlc2InputSnapshot s:snapshots)s.verify();
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
        Map<String,Map<String,Rlc2ProductAdapter.Record>> projectedByRun=new HashMap<String,Map<String,Rlc2ProductAdapter.Record>>();
        Map<String,Set<String>> sourcesByRun=new HashMap<String,Set<String>>();Map<String,String> sessionOfRun=new HashMap<String,String>();
        Map<String,String> brokenRun=new HashMap<String,String>();Map<String,String> sourceOwner=new HashMap<String,String>();
        Map<String,Rlc2ProductAdapter.Projection> projections=new TreeMap<String,Rlc2ProductAdapter.Projection>();
        Map<String,String> directories=new TreeMap<String,String>();List<Rlc2InputSnapshot> snapshots=new ArrayList<Rlc2InputSnapshot>();
        List<String> conflicts=new ArrayList<String>();
        for(Original o:originals) {
            Rlc2ProductAdapter.Projection p;
            try{
                DeploymentBinding.safe(o.directory.toFile());
                Rlc2InputSnapshot snapshot=Rlc2InputSnapshot.capture(o.directory,o.ledger,v1);
                p=Rlc2ProductAdapter.project(true,o.directory,o.ledger,o.device,o.vehicle,reader);snapshot.verify();snapshots.add(snapshot);
            }
            catch(Exception e){
                // We cannot know which run it was without a valid projection; record as an unusable original.
                conflicts.add("ORIGINAL_UNVERIFIABLE");continue;
            }
            String run=IncrementalTsvBridge.runId(p.device,p.payloadSession);
            Map<String,Rlc2ProductAdapter.Record> recs=new HashMap<String,Rlc2ProductAdapter.Record>();for(Rlc2ProductAdapter.Record r:p.records){if(!r.run.equals(run))throw new IOException("ORIGINAL_RUN_MIXED");recs.put(r.id,r);}
            Set<String> ids=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources){ids.add(s.uuid);String prior=sourceOwner.put(s.uuid,run);if(prior!=null&&!prior.equals(run)){brokenRun.put(run,"SOURCE_UUID_DUPLICATED");brokenRun.put(prior,"SOURCE_UUID_DUPLICATED");}}
            if(projectedByRun.containsKey(run))brokenRun.put(run,"ORIGINAL_DUPLICATED_FOR_RUN");
            projectedByRun.put(run,recs);sourcesByRun.put(run,ids);sessionOfRun.put(run,p.device+"|"+p.vehicle+"|"+p.payloadSession);
            projections.put(run,p);directories.put(run,o.directory.toFile().getCanonicalPath());
        }
        List<Row> rows=new ArrayList<Row>();Map<String,String> sessions=new TreeMap<String,String>();Map<String,Set<String>> verified=new TreeMap<String,Set<String>>();
        try(Connection c=IngressDiagnostics.readOnly(v1)) {
            // Claims → runs. A journal claim must name one of the original's physical sources to be verifiable.
            Map<String,String> claimKind=new HashMap<String,String>(),claimKey=new HashMap<String,String>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT device_id,vehicle_id,session_id,source_kind,source_key FROM v1_source_claims")) {
                while(r.next()){
                    String run=IncrementalTsvBridge.runId(r.getString(1),r.getString(3));
                    if(claimKind.containsKey(run))brokenRun.put(run,"CLAIM_RUN_COLLISION");
                    claimKind.put(run,r.getString(4));claimKey.put(run,r.getString(5));
                    if(sessionOfRun.containsKey(run)&&!sessionOfRun.get(run).equals(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3)))brokenRun.put(run,"CLAIM_OWNER_MISMATCH");
                }
            }
            Map<String,List<Cursor>> cursors=new HashMap<String,List<Cursor>>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT source_uuid,source_path,device_id,vehicle_id,session_id,sequence,first_sha256,chain_sha256,segment_snapshot,typeof(sequence) FROM v1_journal_import_cursors")){
                while(r.next()){
                    Cursor cursor=new Cursor(r);List<Cursor> list=cursors.get(cursor.uuid);
                    if(list==null){list=new ArrayList<Cursor>();cursors.put(cursor.uuid,list);}list.add(cursor);
                    String run=IncrementalTsvBridge.runId(cursor.device,cursor.session);
                    if(claimKey.containsKey(run)&&sourcesByRun.containsKey(run)&&sourcesByRun.get(run).contains(claimKey.get(run))&&!Objects.equals(claimKey.get(run),cursor.uuid))brokenRun.put(run,"ORPHAN_CURSOR");
                }
            }
            for(String run:claimKey.keySet())if(projections.containsKey(run)&&"journal".equals(claimKind.get(run))&&!brokenRun.containsKey(run)){
                String reason=cursorReason(claimKey.get(run),cursors,projections.get(run));if(reason!=null)brokenRun.put(run,reason);
            }
            Map<String,long[]> receipts=new HashMap<String,long[]>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT batch_id,record_count FROM v1_ack_receipts")){while(r.next())receipts.put(r.getString(1),new long[]{r.getLong(2),0});}
            Set<String> profiles=new HashSet<String>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT profile_id FROM v1_profiles")){while(r.next())profiles.add(r.getString(1));}
            List<Object[]> raw=new ArrayList<Object[]>();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT local_id,record_id,run_id,body_sha256,acked,acked_batch_id,acked_at,body_json,profile_id,segment_id,sequence,kind,captured_at,source_kind FROM v1_records ORDER BY local_id")) {
                while(r.next()){
                    raw.add(new Object[]{r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)==1,r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14)});
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
                else if(!"journal".equals(claimKind.get(run))||!sourcesByRun.get(run).contains(claimKey.get(run))){cat=Category.CONFLICT;reason="JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE";}
                else {
                    Rlc2ProductAdapter.Record original=projectedByRun.get(run).get(rid);
                    if(original==null){cat=Category.UNKNOWN_OR_INCOMPLETE;reason="NOT_IN_COMMITTED_ORIGINAL";}
                    else if(!v1Canonical(original).equals(hash)||!Objects.equals(original.profile,profile)||!Objects.equals(original.segment,x[9])||!Objects.equals(original.sequence==null?null:original.sequence.toString(),x[10])||!Objects.equals(original.kind,x[11])||!Objects.equals(original.capturedAt,x[12])||!Objects.equals(original.source,x[13])||(json!=null&&!original.json.equals(json))){cat=Category.CONFLICT;reason="SAME_RECORD_ID_DIFFERENT_BODY";}
                    else if(json==null&&!acked){cat=Category.UNKNOWN_OR_INCOMPLETE;reason="UNACKED_BODY_MISSING";}
                    else{cat=Category.VERIFIED_ORIGINAL;reason="ORIGINAL_REPROJECTED_EXACTLY";}
                }
                if(cat==Category.CONFLICT)conflicts.add(reason);
                rows.add(new Row(id,rid,run,hash,acked,batch,at,json,cat,reason));
            }
            // Session verdict: every v1 record of the run verified AND the original has no record the v1 lane lacks is not required
            // (an original may have grown); but a run with any non-verified record is not migratable as a whole.
            for(String run:claimKind.keySet()) {
                String verdict=Category.LEGACY_WITHOUT_RLC2_PROVENANCE.name();boolean all=true,any=false,unknown=false;
                for(Row r:rows)if(r.runId.equals(run)){any=true;if(r.category!=Category.VERIFIED_ORIGINAL)all=false;if(r.category==Category.UNKNOWN_OR_INCOMPLETE)unknown=true;if(r.category==Category.CONFLICT)verdict=Category.CONFLICT.name();}
                if(any&&all){verdict=Category.VERIFIED_ORIGINAL.name();verified.put(run,sourcesByRun.get(run));}
                else if((!any||unknown)&&!Category.CONFLICT.name().equals(verdict))verdict=Category.UNKNOWN_OR_INCOMPLETE.name();
                sessions.put(run,verdict);
            }
            for(Row r:rows)if(!sessions.containsKey(r.runId))sessions.put(r.runId,Category.UNKNOWN_OR_INCOMPLETE.name());
        }
        if(!before.equals(DeploymentTransition.fileSha(v1)))throw new IOException("V1_CHANGED_DURING_CLASSIFICATION");
        for(Rlc2InputSnapshot snapshot:snapshots)snapshot.verify();
        Report report=new Report(rows,sessions,before,verified,conflicts,projections,directories,snapshots,reader);
        report.verifySnapshots();return report;
    }

    private static final class Cursor {
        final String uuid,path,device,vehicle,session,first,chain,snapshot,sequence,type;
        Cursor(ResultSet r)throws Exception{uuid=r.getString(1);path=r.getString(2);device=r.getString(3);vehicle=r.getString(4);session=r.getString(5);sequence=r.getString(6);first=r.getString(7);chain=r.getString(8);snapshot=r.getString(9);type=r.getString(10);}
    }
    private static String cursorReason(String uuid,Map<String,List<Cursor>> cursors,Rlc2ProductAdapter.Projection p)throws Exception {
        Rlc2ProductAdapter.Source source=null;for(Rlc2ProductAdapter.Source s:p.sources)if(s.uuid.equals(uuid))source=s;
        if(source==null)return "JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE";
        List<Cursor> list=cursors.get(uuid);if(list==null)return "CURSOR_MISSING";if(list.size()!=1)return "CURSOR_UUID_DUPLICATED";
        Cursor c=list.get(0);
        if(!p.device.equals(c.device)||!p.vehicle.equals(c.vehicle)||!p.payloadSession.equals(c.session))return "CURSOR_OWNER_MISMATCH";
        // Require an exact absolute, normalized, real single-link path. Do not bless aliases by canonicalizing them first.
        try{if(!source.path.equals(c.path)||!DeploymentBinding.safe(new File(c.path)).toString().equals(c.path))return "CURSOR_PATH_MISMATCH";}
        catch(Exception unknown){return "CURSOR_PATH_MISMATCH";}
        long n;try{n=Long.parseLong(c.sequence);}catch(Exception bad){return "CURSOR_SEQUENCE_INVALID";}
        if(!"integer".equals(c.type)||n<1||n>source.marker.sequence||n>100000)return "CURSOR_SEQUENCE_INVALID";
        List<String> prefix=new ArrayList<String>();
        for(Rlc2ProductAdapter.Receipt r:p.receipts)if(r.sourceUuid.equals(uuid)&&r.sequence<=n){if(r.sequence!=prefix.size()+1)return "CURSOR_SEQUENCE_INVALID";prefix.add(r.body);}
        if(prefix.size()!=n)return "CURSOR_SEQUENCE_INVALID";
        String[] expected;
        try{expected=IncrementalTsvBridge.journalCursorEvidence(p.device,p.vehicle,p.payloadSession,prefix);}
        catch(Exception incompatible){return "CURSOR_SNAPSHOT_MISMATCH";}
        if(!expected[0].equals(c.first))return "CURSOR_FIRST_HASH_MISMATCH";
        if(!expected[1].equals(c.chain))return "CURSOR_CHAIN_MISMATCH";
        if(!expected[2].equals(c.snapshot))return "CURSOR_SNAPSHOT_MISMATCH";
        return null;
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
        if(!rli2.getName().endsWith(".rlc2-integration.db"))throw new IOException("STAGING_RLI2_EXTENSION_REQUIRED");
        for(File f:new File[]{vault,rli2,marker(vault),LegacyStagingTargets.work(vault).toFile()})if(Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("STAGING_TARGET_EXISTS");
        for(File f:new File[]{vault,rli2})if(f.getCanonicalFile().equals(v1.getCanonicalFile()))throw new IOException("STAGING_TARGET_IS_SOURCE");
        if(vault.getCanonicalFile().equals(rli2.getCanonicalFile())||marker(vault).getCanonicalFile().equals(rli2.getCanonicalFile()))throw new IOException("STAGING_TARGET_ALIAS");
        for(Original o:originals)for(File f:new File[]{vault,rli2})if(f.toPath().toAbsolutePath().startsWith(o.directory.toAbsolutePath())||f.getCanonicalFile().equals(o.ledger.getCanonicalFile()))throw new IOException("STAGING_TARGET_IS_SOURCE");
        try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(v1)) {
            Report report=classify(true,true,v1,originals,reader);
            LegacyStagingTargets targets=new LegacyStagingTargets(vault,rli2);
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+targets.vault.toURI().toASCIIString()+"?mode=rw")) {
                try(Statement s=db.createStatement()) {
                    s.execute("PRAGMA journal_mode=DELETE");s.execute("PRAGMA synchronous=FULL");
                    s.execute("PRAGMA application_id="+VAULT_APPLICATION_ID);s.execute("PRAGMA user_version=1");
                    LegacyStagedEvidence.createVault(s);
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
            targets.publish(targets.vault,vault);
            hook.at(StagingStage.VAULT_ROWS_WRITTEN);
            Rlc2IntegrationSchema.initializeClaimed(targets.rli2);
            report.verifySnapshots();
            for(Map.Entry<String,Rlc2ProductAdapter.Projection> entry:report.projections.entrySet()) {
                String run=entry.getKey();Rlc2ProductAdapter.Projection p=entry.getValue();
                if(!Category.VERIFIED_ORIGINAL.name().equals(report.sessions.get(run)))continue;
                importVerified(targets.rli2,p,report.directories.get(run));
            }
            targets.publish(targets.rli2,rli2);
            hook.at(StagingStage.RLI2_WRITTEN);
            String vaultHash=DeploymentTransition.fileSha(vault),rli2Hash=DeploymentTransition.fileSha(rli2);
            verifyStaged(v1,report,vault,rli2);
            Map<String,String> m=new TreeMap<String,String>();m.put("version","RLPC1-STAGED");m.put("v1_sha256",report.v1Sha256);
            m.put("vault_sha256",vaultHash);m.put("rli2_sha256",rli2Hash);
            if(!vaultHash.equals(DeploymentTransition.fileSha(vault))||!rli2Hash.equals(DeploymentTransition.fileSha(rli2)))throw new IOException("STAGING_OUTPUT_CHANGED");
            report.verifySnapshots();old.verify();
            net.neristal.rrbridge.product.v1.DeploymentBinding.createRecord(marker(vault).toPath(),net.neristal.rrbridge.product.v1.DeploymentBinding.encodeRecord(m));
            hook.at(StagingStage.VERIFIED);
            report.verifySnapshots();old.verify();
            if(!"STAGED_LOCAL_HASHES_MATCH_INACTIVE".equals(stagingState(vault,rli2)))throw new IOException("STAGING_OUTPUT_CHANGED");
            return report;
        }
    }
    static File marker(File vault){return new File(vault.getPath()+".rlpc1-staged");}
    /**
     * The normal candidate entry refuses sessions the v1 DB already claims, which is exactly the migrated case. This isolated
     * path uses the same transactional backend and records the same binding/admission rows, but only for a VERIFIED run,
     * only into the new output, and with the original's own sources/receipts. It never reads v1 bodies into RLI2.
     */
    private static void importVerified(File rli2,final Rlc2ProductAdapter.Projection p,final String directory)throws Exception {
        final String run=p.records.get(0).run;
        final Set<String> sources=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources)sources.add(s.uuid);
        new Rlc2PreviewStore(true,rli2,true).ingest(p,Rlc2PreviewStore.NO_FAULT,new Rlc2PreviewStore.TransactionGuard(){
            public void beforeWrite(Connection db){}
            public void claimsWritten(Connection db)throws Exception{
                Rlc2IntegrationStore.saveBindingAndAdmissions(db,p.manifestSession,directory,run,sources);
            }
            public void beforeCommit(Connection db){}
        });
    }

    /** Read-only verification of staged outputs against the v1 source; v1 bytes must be unchanged. */
    public static void verifyStaged(File v1,Report report,File vault,File rli2)throws Exception {
        if(!report.v1Sha256.equals(DeploymentTransition.fileSha(v1)))throw new IOException("V1_CHANGED");
        report.verifySnapshots();
        String vaultHash=DeploymentTransition.fileSha(vault),outputHash=DeploymentTransition.fileSha(rli2);
        try(Connection c=IngressDiagnostics.readOnly(vault)){LegacyStagedEvidence.vault(c,report);}
        Map<String,List<Object[]>> expected=LegacyStagedEvidence.expected();
        for(Map.Entry<String,Rlc2ProductAdapter.Projection> e:report.projections.entrySet())
            if(Category.VERIFIED_ORIGINAL.name().equals(report.sessions.get(e.getKey())))
                LegacyStagedEvidence.add(expected,e.getValue(),report.directories.get(e.getKey()));
        try(Connection c=IngressDiagnostics.readOnly(rli2)){
            Rlc2IntegrationSchema.verify(c);
            for(Map.Entry<String,List<Object[]>> e:expected.entrySet())LegacyStagedEvidence.exact(c,e.getKey(),e.getValue());
        }
        report.verifySnapshots();
        if(!report.v1Sha256.equals(DeploymentTransition.fileSha(v1))||!vaultHash.equals(DeploymentTransition.fileSha(vault))||!outputHash.equals(DeploymentTransition.fileSha(rli2)))throw new IOException("STAGED_EVIDENCE_CHANGED_DURING_VERIFY");
    }

    /** Local completed-file hashes only. Does not prove current original provenance or authorize use. */
    public static String stagingState(File vault,File rli2) {
        boolean m=Files.exists(marker(vault).toPath(),LinkOption.NOFOLLOW_LINKS),v=Files.exists(vault.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.exists(LegacyStagingTargets.work(vault),LinkOption.NOFOLLOW_LINKS),r=Files.exists(rli2.toPath(),LinkOption.NOFOLLOW_LINKS);
        if(!m&&!v&&!r)return "NO_STAGING";
        if(!m)return "INCOMPLETE_REVIEW_REQUIRED";
        try {
            Map<String,String> rec=net.neristal.rrbridge.product.v1.DeploymentBinding.readRecord(marker(vault).toPath());
            DeploymentBinding.safe(vault);DeploymentBinding.safe(rli2);
            if(rec.size()!=4||!"RLPC1-STAGED".equals(rec.get("version"))||rec.get("v1_sha256")==null||!rec.get("v1_sha256").matches("[0-9a-f]{64}")||!rec.get("vault_sha256").equals(DeploymentTransition.fileSha(vault))||!rec.get("rli2_sha256").equals(DeploymentTransition.fileSha(rli2)))return "STAGED_EVIDENCE_MISMATCH";
            return "STAGED_LOCAL_HASHES_MATCH_INACTIVE";
        }catch(Exception e){return "STAGED_EVIDENCE_MISMATCH";}
    }
    private LegacyProvenanceClassifier(){}
}
