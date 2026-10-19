package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.LegacyProvenanceClassifier.Category;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;

/** Fresh synthetic originals and copied v1/output databases only. Every mutation has a precise rejection code. */
public final class Phase5ProvenanceTest {
 static int passed,failed,serial;
 interface Action {void run()throws Exception;}
 static void test(String name,Action action){try{action.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e);}}
 static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 static String quote(String s){return "'"+s.replace("'","''")+"'";}
 static void sql(File f,String s)throws Exception{LegacyProvenanceClassifierTest.sql(f,s);}
 static String sha(String s)throws Exception{return DeploymentTransition.sha(s.getBytes(StandardCharsets.UTF_8));}
 static final String D=Rlc2ProductAdapterTest.DEVICE,V=Rlc2ProductAdapterTest.VEHICLE,A="synthetic-phase5";
 static final class Fixture {
  final Env env=Rlc2ProductAdapterTest.environment();
  final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase5-"));
  final Path dir=DeploymentTransitionTest.session(env,home,"session",A,1791838800000L);
  final File ledger=home.resolve("ledger.db").toFile(),v1=home.resolve("v1.db").toFile();
  final Rlc2ProductAdapter.Projection p;
  final List<LegacyProvenanceClassifier.Original> originals;
  Fixture()throws Exception {
   RotationManifestPrototype.ingestSession(dir,new WatermarkLedgerPrototype(ledger),env.reader(),null);
   p=Rlc2ProductAdapter.project(true,dir,ledger,D,V,env.reader());
   DurableSpool spool=new DurableSpool(v1);spool.claimSourceSession(D,V,A,"journal",p.sources.get(0).uuid);
   for(Map.Entry<String,String> e:p.profiles.entrySet())spool.appendProfile(e.getKey(),e.getValue());
   for(Rlc2ProductAdapter.Record r:p.records)spool.appendRecord(r.id,r.run,r.segment,r.sequence,r.kind,r.profile,r.capturedAt,r.source,r.json);
   List<Rlc2ProductAdapter.Receipt> prefix=new ArrayList<Rlc2ProductAdapter.Receipt>();
   for(Rlc2ProductAdapter.Receipt r:p.receipts)if(r.sourceUuid.equals(p.sources.get(0).uuid))prefix.add(r);
   String chain="START";for(Rlc2ProductAdapter.Receipt r:prefix)chain=sha(chain+"|"+r.sequence+"|"+r.body.getBytes(StandardCharsets.UTF_8).length+"|"+r.body);
   // Independent encoding of the legacy metadata state for this fixture (M RPM followed by S).
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
   out.writeInt(1);out.writeUTF("1");out.writeBoolean(true);out.writeInt(1);out.writeUTF("TEST_RPM");out.writeUTF("Synthetic RPM");out.writeUTF("rpm");out.close();
   sql(v1,"INSERT INTO v1_journal_import_cursors VALUES("+quote(p.sources.get(0).uuid)+","+quote(p.sources.get(0).path)+","+quote(D)+","+quote(V)+","+quote(A)+","+prefix.size()+","+quote(sha(prefix.get(0).body))+","+quote(chain)+","+quote(Base64.getEncoder().encodeToString(bytes.toByteArray()))+")");
   sql(v1,"UPDATE v1_records SET acked=1,body_json=NULL,acked_batch_id='historical',acked_at='2026-10-19T00:00:00Z' WHERE local_id=1");
   sql(v1,"INSERT INTO v1_ack_receipts VALUES('historical',1,1,0,'2026-10-19T00:00:00Z')");
   originals=Collections.singletonList(new LegacyProvenanceClassifier.Original(dir,ledger,D,V));
  }
  LegacyProvenanceClassifier.Report classify(File file)throws Exception{return LegacyProvenanceClassifier.classify(true,true,file,originals,env.reader());}
  File copy(File file)throws Exception{return LegacyProvenanceClassifierTest.copy(home,file,"copy-"+(++serial)+".db");}
  void rejectsCursor(String mutation,String reason)throws Exception {
   File c=copy(v1);sql(c,mutation);LegacyProvenanceClassifier.Report r=classify(c);
   require(r.migratable()==0,"cursor admitted "+r.json());
   require(LegacyProvenanceClassifierTest.reasons(r,reason)==p.records.size(),"wrong reason "+r.json());
  }
 }
 public static void main(String[] args)throws Exception {
  final Fixture f=new Fixture();
  if(args.length==1&&"--load10000".equals(args[0])){load(f);return;}
  final String before=DeploymentTransition.fileSha(f.v1);
  test("valid RLC1 cursor payload evidence against real synthetic RLC2 original",new Action(){public void run()throws Exception{require(f.classify(f.v1).migratable()==f.p.records.size(),f.classify(f.v1).json());}});
  String[][] mutations={
   {"device_id="+quote(UUID.randomUUID().toString()),"CURSOR_OWNER_MISMATCH"},
   {"vehicle_id="+quote(UUID.randomUUID().toString()),"CURSOR_OWNER_MISMATCH"},
   {"session_id='synthetic-other'","CURSOR_OWNER_MISMATCH"},
   {"source_path='relative.rlj'","CURSOR_PATH_MISMATCH"},
   {"source_path="+quote(f.ledger.getCanonicalPath()),"CURSOR_PATH_MISMATCH"},
   {"sequence='unknown'","CURSOR_SEQUENCE_INVALID"},
   {"sequence=99999","CURSOR_SEQUENCE_INVALID"},
   {"chain_sha256='00'","CURSOR_CHAIN_MISMATCH"},
   {"chain_sha256="+quote(String.join("",Collections.nCopies(64,"0"))),"CURSOR_CHAIN_MISMATCH"},
   {"first_sha256='00'","CURSOR_FIRST_HASH_MISMATCH"},
   {"segment_snapshot='fixture'","CURSOR_SNAPSHOT_MISMATCH"}
  };
  for(final String[] m:mutations)test("cursor "+m[0],new Action(){public void run()throws Exception{f.rejectsCursor("UPDATE v1_journal_import_cursors SET "+m[0],m[1]);}});
  test("missing cursor",new Action(){public void run()throws Exception{f.rejectsCursor("DELETE FROM v1_journal_import_cursors","CURSOR_MISSING");}});
  test("negative sequence in malformed legacy schema",new Action(){public void run()throws Exception{
   File c=f.copy(f.v1);sql(c,"PRAGMA ignore_check_constraints=ON");
   try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+c);Statement s=db.createStatement()){s.execute("PRAGMA ignore_check_constraints=ON");s.execute("UPDATE v1_journal_import_cursors SET sequence=-1");}
   require(LegacyProvenanceClassifierTest.reasons(f.classify(c),"CURSOR_SEQUENCE_INVALID")==f.p.records.size(),"negative sequence admitted");
  }});
  test("claim vehicle mismatch blocks run (run ID intentionally excludes vehicle)",new Action(){public void run()throws Exception{
   File c=f.copy(f.v1);sql(c,"UPDATE v1_source_claims SET vehicle_id="+quote(UUID.randomUUID().toString()));require(f.classify(c).migratable()==0,"claim mismatch admitted");
  }});
  test("orphan same-run cursor blocks the entire session",new Action(){public void run()throws Exception{
   File c=f.copy(f.v1);sql(c,"INSERT INTO v1_journal_import_cursors SELECT '"+UUID.randomUUID()+"',source_path,device_id,vehicle_id,session_id,sequence,first_sha256,chain_sha256,segment_snapshot FROM v1_journal_import_cursors");require(f.classify(c).migratable()==0,"orphan admitted");
  }});
  test("duplicate UUID rows in damaged cursor table",new Action(){public void run()throws Exception{
   File c=f.copy(f.v1);sql(c,"ALTER TABLE v1_journal_import_cursors RENAME TO saved_cursors");sql(c,"CREATE TABLE v1_journal_import_cursors AS SELECT * FROM saved_cursors");sql(c,"INSERT INTO v1_journal_import_cursors SELECT * FROM saved_cursors");
   require(LegacyProvenanceClassifierTest.reasons(f.classify(c),"CURSOR_UUID_DUPLICATED")==f.p.records.size(),"duplicate UUID admitted");
  }});
  test("multiple vehicle claims collide on existing device/session run ID",new Action(){public void run()throws Exception{
   File c=f.copy(f.v1);sql(c,"INSERT INTO v1_source_claims SELECT device_id,'"+UUID.randomUUID()+"',session_id,source_kind,source_key FROM v1_source_claims");require(f.classify(c).migratable()==0,"colliding claims admitted");
  }});
  test("same physical UUID across two runs rejects BOTH runs regardless of supplied order",new Action(){public void run()throws Exception{
   String other=UUID.randomUUID().toString();File c=f.copy(f.v1);DurableSpool spool=new DurableSpool(c);
   Rlc2ProductAdapter.Projection p2=Rlc2ProductAdapter.project(true,f.dir,f.ledger,other,V,f.env.reader());
   spool.claimSourceSession(other,V,A,"journal",f.p.sources.get(0).uuid);
   for(Map.Entry<String,String> e:p2.profiles.entrySet())spool.appendProfile(e.getKey(),e.getValue());
   for(Rlc2ProductAdapter.Record r:p2.records)spool.appendRecord(r.id,r.run,r.segment,r.sequence,r.kind,r.profile,r.capturedAt,r.source,r.json);
   LegacyProvenanceClassifier.Original a=f.originals.get(0),b=new LegacyProvenanceClassifier.Original(f.dir,f.ledger,other,V);
   for(List<LegacyProvenanceClassifier.Original> originals:Arrays.asList(Arrays.asList(a,b),Arrays.asList(b,a))){
    LegacyProvenanceClassifier.Report r=LegacyProvenanceClassifier.classify(true,true,c,originals,f.env.reader());
    require(r.count(Category.VERIFIED_ORIGINAL)==0&&LegacyProvenanceClassifierTest.reasons(r,"SOURCE_UUID_DUPLICATED")==f.p.records.size()+p2.records.size(),"earlier run stayed verified");
   }
  }});
  test("hardlink cursor alias is not normalized into an original",new Action(){public void run()throws Exception{
   Path alias=f.home.resolve("alias.rlj");Files.createLink(alias,Paths.get(f.p.sources.get(0).path));
   try{f.rejectsCursor("UPDATE v1_journal_import_cursors SET source_path="+quote(alias.toString()),"CURSOR_PATH_MISMATCH");}finally{Files.delete(alias);}
  }});
  test("source with a hardlink remains unverified even at exact original path",new Action(){public void run()throws Exception{
   Path alias=f.home.resolve("alias2.rlj");Files.createLink(alias,Paths.get(f.p.sources.get(0).path));
   try{require(f.classify(f.v1).migratable()==0,"multi-link original admitted");}finally{Files.delete(alias);}
  }});
  final File vault=f.home.resolve("vault.db").toFile(),rli2=f.home.resolve("candidate.rlc2-integration.db").toFile();
  final LegacyProvenanceClassifier.Report staged=LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
  String[] columns={"v1_local_id=v1_local_id+100","record_id=record_id||'x'","run_id='tampered'","v1_body_sha256='tampered'","body_json='tampered'","v1_acked=1-v1_acked","v1_acked_batch_id='tampered'","v1_acked_at='tampered'","category='CONFLICT'","reason='tampered'","rli2_record=1-rli2_record"};
  for(final String col:columns)test("vault all columns: "+col,new Action(){public void run()throws Exception{
   final File copy=f.copy(vault);sql(copy,"UPDATE vault_records SET "+col+" WHERE v1_local_id=1");expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.verifyStaged(f.v1,staged,copy,rli2);}});
  }});
  for(final String mutation:new String[]{"UPDATE vault_sessions SET category='CONFLICT'","UPDATE vault_meta SET v1_sha256='wrong'","PRAGMA user_version=2","CREATE TRIGGER surprise AFTER INSERT ON vault_records BEGIN DELETE FROM vault_sessions; END","CREATE VIEW surprise AS SELECT * FROM vault_records"})test("vault structure/meta/session: "+mutation,new Action(){public void run()throws Exception{
   final File copy=f.copy(vault);sql(copy,mutation);expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.verifyStaged(f.v1,staged,copy,rli2);}});
  }});
  for(final String mutation:new String[]{
   "UPDATE preview_records SET body_json='{}'",
   "INSERT INTO preview_records SELECT 'extra',run_id,segment_id,NULL,'event',profile_id,captured_at,source_kind,body_json,session_uuid,ack_eligible FROM preview_records LIMIT 1",
   "DELETE FROM preview_receipts WHERE seq=1",
   "UPDATE integration_admissions SET session_uuid='wrong'",
   "DELETE FROM integration_admissions",
   "UPDATE preview_sources SET prefix_sha256='wrong'",
   "UPDATE integration_bindings SET session_path='wrong'",
   "INSERT INTO preview_sources SELECT 'extra',session_uuid,999,'extra-path',identity_tag,identity_hex,generation,seq,offset_bytes,prefix_sha256,sealed FROM preview_sources LIMIT 1",
   "CREATE TRIGGER surprise AFTER INSERT ON preview_records BEGIN DELETE FROM preview_receipts; END"
  })test("exact RLI2 evidence: "+mutation,new Action(){public void run()throws Exception{
   final File copy=f.copy(rli2);sql(copy,mutation);expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.verifyStaged(f.v1,staged,vault,copy);}});
  }});
  for(final LegacyProvenanceClassifier.StagingStage stage:new LegacyProvenanceClassifier.StagingStage[]{LegacyProvenanceClassifier.StagingStage.VAULT_CREATED,LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN,LegacyProvenanceClassifier.StagingStage.RLI2_WRITTEN})test("no overwrite when competitor creates target at "+stage,new Action(){public void run()throws Exception{
   final File v=f.home.resolve("race-"+stage+".db").toFile(),r=f.home.resolve("race-"+stage+".rlc2-integration.db").toFile();
   final File sentinel=stage==LegacyProvenanceClassifier.StagingStage.VAULT_CREATED?v:stage==LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN?r:LegacyProvenanceClassifier.marker(v);
   expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),v,r,new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage s)throws Exception{if(s==stage)Files.write(sentinel.toPath(),"competitor".getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);}});}});
   require(new String(Files.readAllBytes(sentinel.toPath()),StandardCharsets.UTF_8).equals("competitor"),"competitor file changed");
  }});
  test("live legacy Worker lock refuses staging",new Action(){public void run()throws Exception{
   try(LegacyWorkerGuard guard=LegacyWorkerGuard.acquire(f.v1)){expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),f.home.resolve("locked.db").toFile(),f.home.resolve("locked.rlc2-integration.db").toFile(),LegacyProvenanceClassifier.NO_FAULT);}});guard.verify();}
  }});
  for(final LegacyProvenanceClassifier.StagingStage stage:LegacyProvenanceClassifier.StagingStage.values())test("child JVM Runtime.halt boundary "+stage,new Action(){public void run()throws Exception{
   final File v=f.home.resolve("halt-"+stage+".db").toFile(),r=f.home.resolve("halt-"+stage+".rlc2-integration.db").toFile();
   File log=f.home.resolve("halt-"+stage+".log").toFile();
   Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),CrashChild.class.getName(),f.v1.toString(),f.dir.toString(),f.ledger.toString(),v.toString(),r.toString(),stage.name()).redirectErrorStream(true).redirectOutput(log).start();
   if(!child.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("child timeout");}
   require(child.exitValue()==73,"child exit="+child.exitValue()+" "+new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8));
   String state=LegacyProvenanceClassifier.stagingState(v,r);
   require(state.equals(stage==LegacyProvenanceClassifier.StagingStage.VERIFIED?"STAGED_LOCAL_HASHES_MATCH_INACTIVE":"INCOMPLETE_REVIEW_REQUIRED"),state);
   String hash=Files.exists(v.toPath())?DeploymentTransition.fileSha(v):"absent";
   expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),v,r,LegacyProvenanceClassifier.NO_FAULT);}});
   require(hash.equals(Files.exists(v.toPath())?DeploymentTransition.fileSha(v):"absent"),"retry changed partial artifact");
  }});
  test("original growth during staging is rejected",new Action(){public void run()throws Exception{
   expectRefusal(new Action(){public void run()throws Exception{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),f.home.resolve("growing.db").toFile(),f.home.resolve("growing.rlc2-integration.db").toFile(),new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage s)throws Exception{
    if(s==LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN){DeploymentTransitionTest.append(f.env,f.dir,2,"S\t"+A+"\t1\t2\t1791838802000\tTEST_RPM=702");RotationManifestPrototype.ingestSession(f.dir,new WatermarkLedgerPrototype(f.ledger),f.env.reader(),null);}
   }});}});
  }});
  test("all mutations preserve synthetic v1 source bytes",new Action(){public void run()throws Exception{require(before.equals(DeploymentTransition.fileSha(f.v1)),"source changed");}});
  System.out.println("Phase V provenance: "+passed+" passed, "+failed+" failed; JVM="+System.getProperty("sun.arch.data.model"));
  if(failed!=0)throw new AssertionError("Phase V negative tests failed: "+failed);
 }
 static void expectRefusal(Action a)throws Exception{try{a.run();}catch(Exception expected){return;}throw new AssertionError("tampered evidence accepted");}
 static void load(final Fixture f)throws Exception {
  try(WatermarkV2Scenarios.OwnedJournal writer=f.env.openOwned(RotationManifestPrototype.segment(f.dir,2))){
   for(int i=2;i<10000;i++)writer.append("S\t"+A+"\t1\t"+i+"\t"+(1791838800000L+i*1000L)+"\tTEST_RPM=702");
   WatermarkV2Scenarios.publish(f.env,RotationManifestPrototype.segment(f.dir,2),writer,WatermarkV2Prototype.NO_FAULT);
  }
  RotationManifestPrototype.ingestSession(f.dir,new WatermarkLedgerPrototype(f.ledger),f.env.reader(),null);
  File vault=f.home.resolve("load.db").toFile(),rli2=f.home.resolve("load.rlc2-integration.db").toFile();
  LegacyProvenanceClassifier.Report report=LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
  require(report.migratable()==2,"original growth before classification should preserve old two rows");
  require(Rlc2PreviewStoreTest.count(rli2,"SELECT count(*) FROM preview_records")==10000,"exact 10000 records");
  require(Rlc2PreviewStoreTest.count(rli2,"SELECT count(*) FROM preview_receipts")==10001,"exact 10001 receipts");
  LegacyProvenanceClassifier.verifyStaged(f.v1,report,vault,rli2);
  System.out.println("Phase V load PASS: v1=2 historical rows, frozen original/RLI2=10000 records,10001 receipts; independent verification/reverification; JVM="+System.getProperty("sun.arch.data.model"));
 }
 public static final class CrashChild {
  public static void main(String[] args)throws Exception {
   Env env=Rlc2ProductAdapterTest.environment();
   LegacyProvenanceClassifier.stageIsolated(true,true,new File(args[0]),Collections.singletonList(new LegacyProvenanceClassifier.Original(Paths.get(args[1]),new File(args[2]),D,V)),env.reader(),new File(args[3]),new File(args[4]),new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage s){if(s.name().equals(args[5]))Runtime.getRuntime().halt(73);}});
   throw new AssertionError("halt point not reached");
  }
 }
}
