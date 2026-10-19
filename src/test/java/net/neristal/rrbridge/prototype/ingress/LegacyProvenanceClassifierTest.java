package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.LegacyProvenanceClassifier.Category;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
/** RLPC1 synthetic acceptance. The synthetic v1 DB is written by the real DurableSpool API (plus fixture SQL for cursors/ACK). */
public final class LegacyProvenanceClassifierTest {
 static int checks;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static String refusal(Action a){try{a.run();return "ACCEPTED";}catch(Exception e){return String.valueOf(e.getMessage());}}
 static final String D=Rlc2ProductAdapterTest.DEVICE,V=Rlc2ProductAdapterTest.VEHICLE,A="synthetic-legacy-a",TSV="synthetic-legacy-tsv",J="synthetic-legacy-journal-only";
 static final String AT="2026-10-19T00:00:00Z";
 static void sql(File db,String... statements)throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db.getAbsolutePath());Statement s=c.createStatement()){for(String q:statements)s.executeUpdate(q);}
 }
 static File copy(Path home,File v1,String name)throws Exception {Path p=home.resolve(name);Files.copy(v1.toPath(),p);return p.toFile();}
 static long reasons(LegacyProvenanceClassifier.Report r,String reason){long n=0;for(LegacyProvenanceClassifier.Row x:r.rows)if(x.reason.equals(reason))n++;return n;}
 public static void main(String[] args)throws Exception {
  final Env env=Rlc2ProductAdapterTest.environment();
  String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
  if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
  final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase4-legacy-"));
  final Path dirA=DeploymentTransitionTest.session(env,home,"session-a",A,1791838800000L);
  final File ledger=home.resolve("ledger.db").toFile();
  RotationManifestPrototype.ingestSession(dirA,new WatermarkLedgerPrototype(ledger),env.reader(),null);
  final Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,dirA,ledger,D,V,env.reader());
  final String seg1=p.sources.get(0).uuid;
  // Synthetic v1 DB: the v1 journal lane imported the same original (same shared converter output).
  final File v1=home.resolve("v1.db").toFile();DurableSpool spool=new DurableSpool(v1);
  spool.claimSourceSession(D,V,A,"journal",seg1);
  for(Map.Entry<String,String> e:p.profiles.entrySet())spool.appendProfile(e.getKey(),e.getValue());
  for(Rlc2ProductAdapter.Record r:p.records)spool.appendRecord(r.id,r.run,r.segment,r.sequence,r.kind,r.profile,r.capturedAt,r.source,r.json);
  spool.claimSourceSession(D,V,TSV,"legacy",home.resolve("rr_"+TSV+".tsv").toString());
  spool.appendRecord(UUID.randomUUID().toString(),IncrementalTsvBridge.runId(D,TSV),null,null,"event",null,AT,"synthetic","{\"kind\":\"event\",\"text\":\"tsv\"}");
  spool.claimSourceSession(D,V,J,"journal",UUID.randomUUID().toString());
  spool.appendRecord(UUID.randomUUID().toString(),IncrementalTsvBridge.runId(D,J),null,null,"event",null,AT,"synthetic","{\"kind\":\"event\",\"text\":\"journal\"}");
  spool.appendRecord(UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,null,"event",null,AT,"synthetic","{\"kind\":\"event\",\"text\":\"orphan\"}");
  // Fixture-only rows the real lanes would have written: the journal cursor and one ACKed record with its receipt.
  sql(v1,"INSERT INTO v1_journal_import_cursors VALUES('"+seg1+"','"+RotationManifestPrototype.segment(dirA,1).toFile().getCanonicalPath().replace("'","''")+"','"+D+"','"+V+"','"+A+"',2,'00','00','fixture')",
   "UPDATE v1_records SET acked=1,body_json=NULL,acked_batch_id='batch-1',acked_at='"+AT+"' WHERE local_id=1",
   "INSERT INTO v1_ack_receipts VALUES('batch-1',1,1,0,'"+AT+"')");
  spool=null;
  for(String suffix:new String[]{"-wal","-shm"})check(!Files.exists(Paths.get(v1+suffix)),"synthetic v1 DB closed and checkpointed ("+suffix+")");
  final List<LegacyProvenanceClassifier.Original> originals=Collections.singletonList(new LegacyProvenanceClassifier.Original(dirA,ledger,D,V));
  final String v1Before=DeploymentTransition.fileSha(v1);

  refuses("CLASSIFICATION_REQUIRES_EXPLICIT_QUIESCENCE","explicit quiescence required",new Action(){public void run()throws Exception{LegacyProvenanceClassifier.classify(true,false,v1,originals,env.reader());}});
  LegacyProvenanceClassifier.Report r=LegacyProvenanceClassifier.classify(true,true,v1,originals,env.reader());
  check(r.count(Category.VERIFIED_ORIGINAL)==p.records.size(),"every v1 record of the journal session re-projects exactly from the original ("+p.records.size()+")");
  check(reasons(r,"ORIGINAL_REPROJECTED_EXACTLY")==p.records.size()&&r.rows.get(0).acked&&r.rows.get(0).category==Category.VERIFIED_ORIGINAL,"ACKed record with body_json=NULL verifies via the canonical hash");
  check(reasons(r,"TSV_LANE_HAS_NO_RLC2_ORIGINAL")==1,"TSV lane record is LEGACY_WITHOUT_RLC2_PROVENANCE");
  check(reasons(r,"NO_ORIGINAL_SUPPLIED")==1,"journal-only session without RLC2 original is LEGACY_WITHOUT_RLC2_PROVENANCE");
  check(reasons(r,"RUN_WITHOUT_SOURCE_CLAIM")==1,"orphan run is UNKNOWN_OR_INCOMPLETE");
  check(r.sessions.get(IncrementalTsvBridge.runId(D,A)).equals("VERIFIED_ORIGINAL")&&r.sessions.get(IncrementalTsvBridge.runId(D,TSV)).equals("LEGACY_WITHOUT_RLC2_PROVENANCE"),"session verdicts");
  check(r.json().contains("\"receipts_fabricated\":0")&&r.json().contains("\"formal_ack\":false")&&!r.json().contains(home.toString())&&!r.json().contains(seg1),"JSON has counts/codes only, no paths/IDs, no fabricated receipts");
  check(DeploymentTransition.fileSha(v1).equals(v1Before),"v1 DB bytes unchanged by classification");
  check(LegacyProvenanceClassifier.classify(true,true,v1,originals,env.reader()).json().equals(r.json()),"duplicate replay of classification is deterministic");

  // Negative variants, each on its own copy of the synthetic v1 DB.
  final String first=r.rows.get(1).recordId;
  final File body=copy(home,v1,"body.db");sql(body,"UPDATE v1_records SET body_sha256='"+new String(new char[64]).replace('\0','0')+"' WHERE record_id='"+first+"'");
  LegacyProvenanceClassifier.Report rb=LegacyProvenanceClassifier.classify(true,true,body,originals,env.reader());
  check(reasons(rb,"SAME_RECORD_ID_DIFFERENT_BODY")==1&&rb.sessions.get(IncrementalTsvBridge.runId(D,A)).equals("CONFLICT"),"same record ID with different body is CONFLICT and blocks the session");
  final File noReceipt=copy(home,v1,"no-receipt.db");sql(noReceipt,"DELETE FROM v1_ack_receipts");
  check(reasons(LegacyProvenanceClassifier.classify(true,true,noReceipt,originals,env.reader()),"ACK_RECEIPT_MISSING")==1,"missing ACK receipt is CONFLICT");
  final File badReceipt=copy(home,v1,"bad-receipt.db");sql(badReceipt,"UPDATE v1_ack_receipts SET record_count=0");
  check(reasons(LegacyProvenanceClassifier.classify(true,true,badReceipt,originals,env.reader()),"ACK_RECEIPT_COUNT_EXCEEDED")==1,"tampered ACK receipt count is CONFLICT");
  final File otherClaim=copy(home,v1,"other-claim.db");sql(otherClaim,"UPDATE v1_source_claims SET source_key='"+UUID.randomUUID()+"' WHERE session_id='"+A+"'");
  check(reasons(LegacyProvenanceClassifier.classify(true,true,otherClaim,originals,env.reader()),"JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE")==p.records.size(),"journal claim naming another physical source is CONFLICT");
  final File tail=copy(home,v1,"tail.db");
  sql(tail,"INSERT INTO v1_records(record_id,run_id,segment_id,sequence,kind,profile_id,captured_at,source_kind,body_sha256,body_json) VALUES('"+UUID.randomUUID()+"','"+IncrementalTsvBridge.runId(D,A)+"',NULL,NULL,'event',NULL,'"+AT+"','synthetic','"+new String(new char[64]).replace('\0','1')+"','{}')");
  LegacyProvenanceClassifier.Report rt=LegacyProvenanceClassifier.classify(true,true,tail,originals,env.reader());
  check(reasons(rt,"NOT_IN_COMMITTED_ORIGINAL")==1&&!rt.sessions.get(IncrementalTsvBridge.runId(D,A)).equals("VERIFIED_ORIGINAL"),"v1 record beyond the committed original (tail) is not verified and blocks the session");
  final List<LegacyProvenanceClassifier.Original> twice=Arrays.asList(originals.get(0),originals.get(0));
  check(reasons(LegacyProvenanceClassifier.classify(true,true,v1,twice,env.reader()),"SOURCE_UUID_DUPLICATED")+reasons(LegacyProvenanceClassifier.classify(true,true,v1,twice,env.reader()),"ORIGINAL_DUPLICATED_FOR_RUN")>0,"the same physical sources offered twice are CONFLICT");
  final File active=copy(home,v1,"active.db");Files.write(Paths.get(active+"-wal"),new byte[0]);
  check(!refusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.classify(true,true,active,originals,env.reader());}}).equals("ACCEPTED"),"v1 DB with a WAL sidecar (possibly locked/active) refuses");
  // A damaged original never verifies: copy the session and remove its ledger evidence.
  final File emptyLedger=home.resolve("empty-ledger.db").toFile();new WatermarkLedgerPrototype(emptyLedger);
  LegacyProvenanceClassifier.Report ru=LegacyProvenanceClassifier.classify(true,true,v1,Collections.singletonList(new LegacyProvenanceClassifier.Original(dirA,emptyLedger,D,V)),env.reader());
  check(ru.count(Category.VERIFIED_ORIGINAL)==0,"original without matching ledger evidence verifies nothing ["+ru.json().replaceAll(".*\"conflict_reasons\":(\\{[^}]*\\}).*","$1")+"]");
  // After a later rotation the original grows; v1 records are still exactly contained.
  RotationManifestPrototype.seal(dirA,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  RotationManifestPrototype.startNext(dirA,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  DeploymentTransitionTest.append(env,dirA,3,"S\t"+A+"\t1\t2\t1791838802000\tTEST_RPM=702");
  RotationManifestPrototype.ingestSession(dirA,new WatermarkLedgerPrototype(ledger),env.reader(),null);
  check(LegacyProvenanceClassifier.classify(true,true,v1,originals,env.reader()).count(Category.VERIFIED_ORIGINAL)==p.records.size(),"rotation after the v1 import keeps the v1 records verified");

  // Isolated staging: vault (all rows, categories, v1 ACK state) + RLI2 from verified originals only.
  final File vault=home.resolve("staged.rllv1.db").toFile(),rli2=home.resolve("staged.rlc2-integration.db").toFile();
  check(LegacyProvenanceClassifier.stagingState(vault,rli2).equals("NO_STAGING"),"no staging yet");
  LegacyProvenanceClassifier.Report staged=LegacyProvenanceClassifier.stageIsolated(true,true,v1,originals,env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
  check(LegacyProvenanceClassifier.stagingState(vault,rli2).equals("STAGED_VERIFIED_INACTIVE"),"staging verified and inactive");
  check(Rlc2PreviewStoreTest.count(rli2,"SELECT count(*) FROM preview_records")==p.records.size()+1&&Rlc2PreviewStoreTest.count(rli2,"SELECT count(*) FROM preview_receipts")>0,"RLI2 candidate holds the original's records and ledger receipts (incl. the post-rotation row)");
  check(Rlc2PreviewStoreTest.count(vault,"SELECT count(*) FROM vault_records")==staged.rows.size()&&Rlc2PreviewStoreTest.count(vault,"SELECT count(*) FROM vault_records WHERE v1_acked=1 AND v1_acked_batch_id='batch-1'")==1,"vault keeps every v1 row with its v1 ACK state");
  check(Rlc2PreviewStoreTest.count(vault,"SELECT count(*) FROM vault_meta WHERE formal_ack=0")==1,"vault never marks a formal ACK");
  check(DeploymentTransition.fileSha(v1).equals(v1Before),"v1 DB bytes unchanged by staging");
  check(refusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,v1,originals,env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);}}).equals("STAGING_TARGET_EXISTS"),"staging never overwrites targets");
  // Interrupted staging is INCOMPLETE and leaves the source untouched.
  final File vault2=home.resolve("interrupted.rllv1.db").toFile(),rli22=home.resolve("interrupted.rlc2-integration.db").toFile();
  check(refusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,v1,originals,env.reader(),vault2,rli22,new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage s)throws Exception{if(s==LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN)throw new IOException("SIMULATED_INTERRUPT");}});}}).equals("SIMULATED_INTERRUPT"),"staging interrupted after vault rows");
  check(LegacyProvenanceClassifier.stagingState(vault2,rli22).equals("INCOMPLETE_REVIEW_REQUIRED"),"interrupted staging is INCOMPLETE, not verified");
  check(DeploymentTransition.fileSha(v1).equals(v1Before),"v1 DB bytes unchanged after interrupted staging");
  // A CONFLICT session never reaches the RLI2 candidate.
  final File vault3=home.resolve("conflict.rllv1.db").toFile(),rli23=home.resolve("conflict.rlc2-integration.db").toFile();
  LegacyProvenanceClassifier.stageIsolated(true,true,body,originals,env.reader(),vault3,rli23,LegacyProvenanceClassifier.NO_FAULT);
  check(Rlc2PreviewStoreTest.count(rli23,"SELECT count(*) FROM preview_records")==0&&Rlc2PreviewStoreTest.count(vault3,"SELECT count(*) FROM vault_records WHERE category='CONFLICT'")>=1,"CONFLICT session stays only in the vault");
  // Tampering a staged output is detected by the state check.
  try(RandomAccessFile w=new RandomAccessFile(vault,"rw")){w.seek(w.length()-1);int b=w.read();w.seek(w.length()-1);w.write(b^1);}
  check(LegacyProvenanceClassifier.stagingState(vault,rli2).equals("STAGED_EVIDENCE_MISMATCH"),"modified staged vault detected");
  System.out.println("Legacy provenance classifier passed "+checks+" checks; no RLC2 receipt fabricated; formal ACK not connected; actual JVM="+System.getProperty("sun.arch.data.model"));
 }
 static void refuses(String code,String why,Action a){String got=refusal(a);check(got.equals(code),why+" ["+got+"]");}
}
