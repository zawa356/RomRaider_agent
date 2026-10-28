package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.*;
public final class V1LaneMigrationTest {
 static int checks;interface Action{void run()throws Exception;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static void reject(Action a,String s)throws Exception {try{a.run();}catch(Exception e){check(true,s);return;}throw new AssertionError(s);}
 /** Phase X-E1 RED stub: same catch-all as reject(); GREEN accepts only the injected instance after the hook ran. */
 static void rejectAtInjection(Action a,Exception injection,boolean[] reached,String s)throws Exception {reject(a,s);}
 static void execute(File db,String text)throws Exception {try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement()){s.execute(text);}}
 public static void main(String[] args)throws Exception {
  if(args.length>0){V1LaneMigration.migrateCopy(true,true,new File(args[0]),new File(args[1]),new V1LaneMigration.Hook(){public void beforeCommit(){Runtime.getRuntime().halt(84);}});throw new AssertionError("migration halt not reached");}
  final File dir=Files.createTempDirectory("romlogger-migration-").toFile(),source=new File(dir,"v1.db"),target=new File(dir,"copy.lane-migration.db");
  DurableSpool spool=new DurableSpool(source);File tsv=new File(dir,"rr_synthetic.tsv");
  Files.write(tsv.toPath(),("M\tsynthetic\t1\tRPM\tRPM\trpm\nS\tsynthetic\t1\t0\t1791838800000\tRPM=700\nS\tsynthetic\t1\t1\t1791838801000\tRPM=701\n").getBytes(StandardCharsets.UTF_8));
  new IncrementalTsvBridge(spool,LaneOwnershipTest.D,LaneOwnershipTest.V).importNew(tsv,10);
  spool.markProfileAcked(spool.pendingProfileEntries().get(0).profileId,true);
  DurableSpool.Batch batch=spool.nextBatch(1,200000);spool.markBatchAcked(batch,new DurableSpool.Ack(batch.batchId,"committed",1,1,0));
  spool.saveDeliveryState(3,1791838805000L,true,"NEEDS_REVIEW");spool.requireIngressReview("JOURNAL_IMPORT");
  final byte[] original=Files.readAllBytes(source.toPath());
  V1LaneMigration.Report report=V1LaneMigration.preflight(true,true,source);
  check(report.acked==1&&report.pending==1&&report.rows.get("v1_import_cursors")==1,"dry-run inventories ACK/unACK and cursor");
  check(!target.exists()&&java.util.Arrays.equals(original,Files.readAllBytes(source.toPath())),"dry-run creates no target and changes no input bytes");
  final boolean[] injected={false};
  reject(new Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,target,new V1LaneMigration.Hook(){public void beforeCommit()throws Exception{injected[0]=true;throw new IOException("injected interruption");}});}},"interrupted migration refuses");
  check(injected[0]&&target.length()>0,"injection actually reached prepared migration commit");
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+target);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='lane_migration_v1'")){r.next();check(r.getInt(1)==0,"interruption rolls back metadata/inventory/guards atomically");}
  V1LaneMigration.Report migrated=V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);
  check(migrated.rows.get("v1_delivery_state")==1&&migrated.rows.get("v1_ingress_review_state")==1,"nonempty retry/review latches retained in inventory");
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+target.toURI().toASCIIString()+"?mode=ro&immutable=1");Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT retry_count,next_attempt_ms,needs_review,last_outcome,reason_code FROM v1_delivery_state JOIN v1_ingress_review_state USING(singleton)")) {
   check(r.next()&&r.getInt(1)==3&&r.getLong(2)==1791838805000L&&r.getInt(3)==1&&r.getString(4).equals("NEEDS_REVIEW")&&r.getString(5).equals("JOURNAL_IMPORT")&&!r.next(),"exact retry schedule/outcome and ingress fault preserved without reset");
  }
  check(migrated.dataSha.equals(report.dataSha)&&migrated.sourceSha.equals(report.sourceSha),"isolated copy preserves every original row/rowid/ACK/cursor/profile");
  check(V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT).dataSha.equals(report.dataSha),"migration reexecution idempotent");
  check(java.util.Arrays.equals(original,Files.readAllBytes(source.toPath())),"original byte-for-byte unchanged after migration/retry");
  reject(new Action(){public void run()throws Exception{execute(target,"UPDATE v1_records SET acked=0");}},"old SQL writers blocked by candidate guard");
  reject(new Action(){public void run()throws Exception{new DurableSpool(target);}},"new Worker refuses versioned candidate as v1 DB");
  final File crashTarget=new File(dir,"crash.lane-migration.db");
  String executable=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  Process child=new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),V1LaneMigrationTest.class.getName(),source.toString(),crashTarget.toString()).redirectErrorStream(true).redirectOutput(new File(dir,"migration-halt.log")).start();
  check(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)&&child.exitValue()==84,"child halts at prepared migration commit");
  check(V1LaneMigration.migrateCopy(true,true,source,crashTarget,V1LaneMigration.NO_FAULT).dataSha.equals(report.dataSha),"owned target recovers SQLite rollback after real JVM halt");
  check(java.util.Arrays.equals(original,Files.readAllBytes(source.toPath())),"real migration halt leaves original bytes unchanged");
  execute(crashTarget,"UPDATE lane_migration_sources_v1 SET source_key='tampered'");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,crashTarget,V1LaneMigration.NO_FAULT);}},"rerun rejects modified migrated source inventory");
  execute(target,"DROP TRIGGER lane_guard_v1_records_UPDATE");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);}},"rerun rejects missing preservation trigger");
  final File unknown=new File(dir,"unknown.db");Files.copy(source.toPath(),unknown.toPath());execute(unknown,"CREATE TABLE future_schema(value TEXT)");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.preflight(true,true,unknown);}},"unknown schema refuses");
  execute(source,"UPDATE v1_source_claims SET source_key='conflicting-source'");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.preflight(true,true,source);}},"claim/cursor conflict refuses without auto-adoption");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.preflight(false,true,null);}},"disabled before filesystem");
  reject(new Action(){public void run()throws Exception{V1LaneMigration.preflight(true,false,null);}},"quiescence mandatory");
  System.out.println("V1 lane migration passed "+checks+" checks; isolated copy only; no automatic downgrade/apply");
 }
}
