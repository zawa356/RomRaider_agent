package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.ArtifactProvenance;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/**
 * RLIR1 integrated read-only readiness (Phase VII-2). Joins the offline preflight, RLPC1 classification of the legacy v1 DB,
 * the RLLV1 Vault and the staged RLI2 candidate into independent axes, each with a status, a stable code and the next human
 * step. Every axis holds only what was actually executed in THIS call; anything not run is NOT_RUN, anything unprovable stays
 * UNKNOWN/BLOCKED. A complete set of PASS content axes never grants launch, production or ACK.
 *
 * Read-only: SQLite inputs are opened mode=ro&immutable=1 only when no WAL/SHM/journal exists (never created, checkpointed or
 * repaired). Every input's bytes and physical identity, the staging parent and the original session snapshot are captured
 * before the first check and re-verified at each stage boundary; a substitution stops the diagnosis.
 */
public final class IntegratedReadiness {
    public static final String VERSION="RLIR1";
    public enum Status { PASS, MISMATCH, UNKNOWN, BLOCKED, NOT_RUN, DISABLED }
    public interface Hook { void at(String stage)throws Exception; }
    public static final Hook NO_HOOK=new Hook(){public void at(String stage){}};
    public static final String[] AXES={"deployment_manifest","identity_admission","cooperative_locks","artifact_provenance","cursor_provenance",
        "legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission","staging_generation","writer_quiescence",
        "process_provenance","directory_durability","formal_ack","production_send"};

    public static final class Axis {
        public final Status status;public final String code,next;
        Axis(Status status,String code,String next){this.status=status;this.code=code;this.next=next;}
    }
    public static final class Report {
        public final String reason;private final Map<String,Axis> axes;private final Map<String,Long> counts;
        Report(String reason,Map<String,Axis> axes,Map<String,Long> counts){this.reason=reason;this.axes=Collections.unmodifiableMap(new LinkedHashMap<String,Axis>(axes));this.counts=Collections.unmodifiableMap(new TreeMap<String,Long>(counts));}
        public Axis axis(String name){return axes.get(name);}
        /** True only when every content axis actually ran and passed in this call. Still not an authorization. */
        public boolean contentComplete(){
            for(String a:new String[]{"deployment_manifest","identity_admission","cooperative_locks","artifact_provenance","cursor_provenance","legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission","staging_generation"})
                if(axes.get(a).status!=Status.PASS)return false;
            return true;
        }
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"readiness\":\"").append(VERSION).append("\",\"state\":\"BLOCKED\",\"reason\":\"").append(reason).append("\",\"content_axes_complete\":").append(contentComplete()).append(",\"axes\":{");
            boolean first=true;for(Map.Entry<String,Axis> e:axes.entrySet()){if(!first)b.append(',');first=false;Axis a=e.getValue();
                b.append('"').append(e.getKey()).append("\":{\"status\":\"").append(a.status).append("\",\"code\":\"").append(a.code).append("\",\"next\":\"").append(a.next).append("\"}");}
            b.append("},\"counts\":{");first=true;for(Map.Entry<String,Long> e:counts.entrySet()){if(!first)b.append(',');first=false;b.append('"').append(e.getKey()).append("\":").append(e.getValue());}
            return b.append("},\"synthetic_only\":true,\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false,\"automatic_recovery\":false}").toString();
        }
    }
    static String code(Exception e){
        String m=e.getMessage();
        if(m==null)return e.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        return m.matches("[A-Z0-9_]{3,80}")?m:"INSPECTION_FAILED_UNCODED";
    }

    /** Bytes + physical identity of each input file, the staging parent and the original session snapshot. */
    static final class Inputs {
        private final List<File> files;private final File parent;private final Map<String,String> evidence;private final Rlc2InputSnapshot session;
        Inputs(List<File> files,File parent,Rlc2InputSnapshot session)throws Exception {this.files=files;this.parent=parent;this.session=session;evidence=scan();}
        private Map<String,String> scan()throws Exception {
            Map<String,String> m=new TreeMap<String,String>();
            for(File f:files){
                for(String suffix:new String[]{"-wal","-shm","-journal"})if(Files.exists(Paths.get(f.getPath()+suffix),LinkOption.NOFOLLOW_LINKS))throw new IOException("INPUT_DATABASE_ACTIVE");
                m.put(f.getPath(),DeploymentBinding.fileIdentity(f)+"|"+DeploymentTransition.fileSha(f));
            }
            if(parent!=null)m.put("parent",DeploymentBinding.fileIdentity(parent));
            return m;
        }
        void verify()throws Exception {
            Map<String,String> now;try{now=scan();}catch(IOException e){throw new IOException("INPUT_CHANGED_DURING_DIAGNOSIS");}
            if(!evidence.equals(now))throw new IOException("INPUT_CHANGED_DURING_DIAGNOSIS");
            if(session!=null)try{session.verify();}catch(IOException e){throw new IOException("INPUT_CHANGED_DURING_DIAGNOSIS");}
        }
    }

    /**
     * @param originals operator-supplied RLC2 originals for the legacy classification (may be empty)
     * @param vault RLLV1 vault produced by RLPC1 staging, or null when none exists
     * @param staged staged RLI2 candidate produced by RLPC1 staging, or null when none exists
     */
    public static Report inspect(boolean enable,DeploymentBinding.Layout layout,int expectedGeneration,String expectedManifestSha,Rlc2IntegrationStore.Admission admission,
            List<File> classpath,Map<String,String> approvedArtifacts,List<LegacyProvenanceClassifier.Original> originals,File vault,File staged,
            WatermarkV2Prototype.ReadOpener reader,Hook hook){
        Map<String,Axis> axes=new LinkedHashMap<String,Axis>();Map<String,Long> counts=new TreeMap<String,Long>();
        for(String a:AXES)axes.put(a,new Axis(Status.NOT_RUN,"NOT_RUN","RUN_DIAGNOSIS"));
        axes.put("writer_quiescence",new Axis(Status.BLOCKED,"WRITER_UNPROVEN","PROVIDE_NONCOOPERATIVE_WRITER_EXCLUSION_EVIDENCE"));
        axes.put("process_provenance",new Axis(Status.UNKNOWN,"UNKNOWN_PROCESS_PROVENANCE","NO_WINDOWS_PROCESS_PROOF_AVAILABLE"));
        axes.put("directory_durability",new Axis(Status.UNKNOWN,"DIRECTORY_DURABILITY_UNKNOWN","NO_DIRECTORY_DURABILITY_PROOF_AVAILABLE"));
        axes.put("formal_ack",new Axis(Status.DISABLED,"FORMAL_ACK_DISABLED","NONE"));
        axes.put("production_send",new Axis(Status.DISABLED,"PRODUCTION_SEND_DISABLED","NONE"));
        if(!enable)return new Report("READINESS_DISABLED",axes,counts);
        Inputs inputs;
        try{
            List<File> files=new ArrayList<File>(Arrays.asList(layout.legacy,layout.ledger,layout.manifest));
            if(vault!=null){files.add(vault);files.add(LegacyProvenanceClassifier.marker(vault));}
            if(staged!=null)files.add(staged);
            inputs=new Inputs(files,vault==null?null:vault.getAbsoluteFile().getParentFile(),Rlc2InputSnapshot.capture(layout.session.toPath(),layout.ledger,layout.legacy));
        }catch(Exception e){
            String c=code(e);if(c.equals("NOSUCHFILEEXCEPTION")||c.startsWith("INSPECTION_FAILED"))c="REQUIRED_EVIDENCE_MISSING";
            return new Report(c,axes,counts);
        }
        try{
            // ---- 1. preflight: deployment/generation/manifest, admission, cooperative locks, artifacts (read-only).
            OfflineCandidatePreflight.Report pre=OfflineCandidatePreflight.inspect(true,layout,expectedGeneration,expectedManifestSha,admission,classpath,approvedArtifacts,-1,reader);
            String pj=pre.json();
            axes.put("deployment_manifest",pj.contains("\"manifest_floor\":\"MATCHED_EXPECTED_BYTES\"")?new Axis(Status.PASS,"ACTIVE_GENERATION_AND_MANIFEST_MATCHED","NONE"):preflightAxis(pre.reason,"CONFIRM_ACTIVE_GENERATION_AND_MANIFEST"));
            axes.put("artifact_provenance",pj.contains("\"artifact_provenance\":\"MATCHED_APPROVED_DESCRIPTION\"")?new Axis(Status.PASS,"APPROVED_ENTRY_DESCRIPTION_MATCHED","NONE"):preflightAxis(pre.reason,"REBUILD_OR_REPIN_APPROVED_ENTRY"));
            axes.put("cooperative_locks",pj.contains("\"publication_lock\":\"EXISTING_COOPERATIVE_LOCK_OBSERVED_IDLE\"")?new Axis(Status.PASS,"COOPERATIVE_LOCKS_OBSERVED_IDLE","NONE"):preflightAxis(pre.reason,"STOP_COOPERATIVE_HOLDER_THEN_RETRY"));
            axes.put("identity_admission",pj.contains("\"source_admission\":\"MATCHED_ORIGINAL_PROJECTION\"")?new Axis(Status.PASS,"SOURCE_ADMISSION_MATCHED_PROJECTION","NONE"):preflightAxis(pre.reason,"REVIEW_EXPLICIT_SOURCE_ADMISSION"));
            inputs.verify();hook.at("PREFLIGHT_DONE");inputs.verify();

            // ---- 2. legacy v1 classification: cursors, claims, ACK records.
            LegacyProvenanceClassifier.Report report;
            try{report=LegacyProvenanceClassifier.classify(true,true,layout.legacy,originals,reader);}
            catch(Exception e){axes.put("cursor_provenance",new Axis(Status.UNKNOWN,code(e),"QUIESCE_AND_REINSPECT_LEGACY"));throw new Stop(code(e));}
            for(LegacyProvenanceClassifier.Category c:LegacyProvenanceClassifier.Category.values())counts.put("v1_"+c.name().toLowerCase(Locale.ROOT),report.count(c));
            counts.put("v1_conflicts",(long)report.conflicts.size());counts.put("v1_migratable",report.migratable());
            String cursor=null;for(String c:report.conflicts)if(c.startsWith("CURSOR_")||c.equals("ORPHAN_CURSOR")||c.equals("JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE")){cursor=c;break;}
            String sourceReuse=null;for(String c:report.conflicts)if(c.equals("SOURCE_UUID_DUPLICATED")||c.equals("ORIGINAL_DUPLICATED_FOR_RUN")||c.equals("CLAIM_RUN_COLLISION")||c.equals("CLAIM_OWNER_MISMATCH"))sourceReuse=c;
            if(sourceReuse!=null)axes.put("identity_admission",new Axis(Status.MISMATCH,sourceReuse,"REVIEW_SESSION_AND_SOURCE_IDENTITY"));
            if(cursor!=null)axes.put("cursor_provenance",new Axis(Status.MISMATCH,cursor,"REVIEW_LEGACY_CURSOR_EVIDENCE"));
            else if(report.conflicts.contains("ORIGINAL_UNVERIFIABLE"))axes.put("cursor_provenance",new Axis(Status.UNKNOWN,"ORIGINAL_UNVERIFIABLE","SUPPLY_VERIFIABLE_ORIGINAL"));
            else if(report.count(LegacyProvenanceClassifier.Category.CONFLICT)>0)axes.put("cursor_provenance",new Axis(Status.MISMATCH,report.conflicts.get(0),"REVIEW_CONFLICTING_RECORDS"));
            else if(report.count(LegacyProvenanceClassifier.Category.UNKNOWN_OR_INCOMPLETE)>0)axes.put("cursor_provenance",new Axis(Status.UNKNOWN,"LEGACY_RECORDS_INCOMPLETE","SUPPLY_MISSING_ORIGINALS_OR_BODIES"));
            else axes.put("cursor_provenance",new Axis(Status.PASS,"CLAIMS_AND_CURSORS_MATCH_ORIGINALS","NONE"));
            inputs.verify();hook.at("CLASSIFIED");inputs.verify();

            // ---- 3. staged evidence: marker generation, Vault, RLI2, ACK set, record/receipt/admission rows.
            if(vault==null||staged==null){
                for(String a:new String[]{"legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission","staging_generation"})axes.put(a,new Axis(Status.BLOCKED,"STAGED_EVIDENCE_MISSING","RUN_ISOLATED_STAGING"));
                throw new Stop("STAGED_EVIDENCE_MISSING");
            }
            String state=LegacyProvenanceClassifier.stagingState(vault,staged);
            if(!"STAGED_LOCAL_HASHES_MATCH_INACTIVE".equals(state)){
                axes.put("staging_generation",new Axis(state.equals("INCOMPLETE_REVIEW_REQUIRED")?Status.BLOCKED:Status.MISMATCH,state,"REVIEW_OR_DISCARD_STAGING_THEN_RESTAGE"));
                for(String a:new String[]{"legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission"})axes.put(a,new Axis(Status.BLOCKED,state,"REVIEW_OR_DISCARD_STAGING_THEN_RESTAGE"));
                throw new Stop(state);
            }
            Map<String,String> marker=DeploymentBinding.readRecord(LegacyProvenanceClassifier.marker(vault).toPath());
            if(!report.v1Sha256.equals(marker.get("v1_sha256"))){
                axes.put("staging_generation",new Axis(Status.MISMATCH,"STAGED_FOR_DIFFERENT_V1","RESTAGE_FROM_CURRENT_V1"));
                for(String a:new String[]{"legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission"})axes.put(a,new Axis(Status.BLOCKED,"STAGED_FOR_DIFFERENT_V1","RESTAGE_FROM_CURRENT_V1"));
                throw new Stop("STAGED_FOR_DIFFERENT_V1");
            }
            axes.put("staging_generation",new Axis(Status.PASS,"STAGED_FROM_CURRENT_V1_LOCAL_HASHES","NONE"));
            try(Connection c=IngressDiagnostics.readOnly(vault)){
                axes.put("legacy_ack_set",ackSet(c,report));
                try{LegacyStagedEvidence.vault(c,report);axes.put("vault_integrity",new Axis(Status.PASS,"VAULT_ALL_COLUMNS_EXACT","NONE"));}
                catch(Exception e){axes.put("vault_integrity",new Axis(Status.MISMATCH,code(e).startsWith("INSPECTION")?"VAULT_ROWS_DIFFER":code(e),"DISCARD_VAULT_AND_RESTAGE"));}
            }
            inputs.verify();hook.at("VAULT_CHECKED");inputs.verify();
            try(Connection c=IngressDiagnostics.readOnly(staged)){
                try{Rlc2IntegrationSchema.verify(c);axes.put("staged_rli2_integrity",new Axis(Status.PASS,"RLI2_SCHEMA_FOREIGN_KEYS_INTEGRITY","NONE"));}
                catch(Exception e){axes.put("staged_rli2_integrity",new Axis(Status.MISMATCH,"RLI2_SCHEMA_OR_INTEGRITY_DIFFERS","DISCARD_CANDIDATE_AND_RESTAGE"));}
                Map<String,List<Object[]>> expected=LegacyProvenanceClassifier.expectedStagedRows(report);
                String table=null;
                for(Map.Entry<String,List<Object[]>> e:expected.entrySet()){
                    counts.put("rli2_expected_"+e.getKey(),(long)e.getValue().size());
                    try{LegacyStagedEvidence.exact(c,e.getKey(),e.getValue());}catch(Exception mismatch){if(table==null)table=e.getKey();}
                }
                axes.put("record_receipt_admission",table==null?new Axis(Status.PASS,"RLI2_EXACT_ROW_SETS_ALL_EIGHT_TABLES","NONE"):new Axis(Status.MISMATCH,"RLI2_ROWS_DIFFER_"+table.toUpperCase(Locale.ROOT),"DISCARD_CANDIDATE_AND_RESTAGE"));
            }
            report.verifySnapshots();inputs.verify();hook.at("STAGED_CHECKED");inputs.verify();
            return new Report("WRITER_UNPROVEN",axes,counts);
        }catch(Stop stop){
            try{inputs.verify();}catch(Exception changed){return new Report("INPUT_CHANGED_DURING_DIAGNOSIS",axes,counts);}
            return new Report(stop.getMessage(),axes,counts);
        }catch(Exception e){
            return new Report(code(e),axes,counts);
        }
    }
    private static final class Stop extends Exception {Stop(String code){super(code);}}
    private static Axis preflightAxis(String reason,String next){
        boolean busy=reason.endsWith("_BUSY")||reason.endsWith("_NOT_OBSERVED_IDLE");
        return new Axis(busy?Status.BLOCKED:reason.startsWith("INSPECTION")?Status.UNKNOWN:Status.MISMATCH,reason,next);
    }
    /** Every v1 ACKed record ID and its body hash/batch must be preserved exactly in the Vault (and nothing else marked ACKed). */
    private static Axis ackSet(Connection vault,LegacyProvenanceClassifier.Report report)throws Exception {
        Set<String> expected=new TreeSet<String>(),actual=new TreeSet<String>();
        for(LegacyProvenanceClassifier.Row r:report.rows)if(r.acked)expected.add(r.recordId+"|"+r.bodySha+"|"+r.ackedBatch+"|"+r.ackedAt);
        try(Statement s=vault.createStatement();ResultSet r=s.executeQuery("SELECT record_id,v1_body_sha256,v1_acked_batch_id,v1_acked_at FROM vault_records WHERE v1_acked=1")){
            while(r.next())actual.add(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3)+"|"+r.getString(4));
        }catch(SQLException unreadable){return new Axis(Status.UNKNOWN,"VAULT_ACK_COLUMNS_UNREADABLE","DISCARD_VAULT_AND_RESTAGE");}
        return expected.equals(actual)?new Axis(Status.PASS,"V1_ACK_SET_PRESERVED_EXACTLY","NONE"):new Axis(Status.MISMATCH,"V1_ACK_SET_DIFFERS","DISCARD_VAULT_AND_RESTAGE");
    }
    private IntegratedReadiness(){}
}
