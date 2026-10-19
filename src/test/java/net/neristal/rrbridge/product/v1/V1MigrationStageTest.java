package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class V1MigrationStageTest {
 static int checks;static final String D=LaneOwnershipTest.D,V=LaneOwnershipTest.V;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static void reject(Action action,String why)throws Exception {try{action.run();}catch(Exception expected){check(true,why);return;}throw new AssertionError(why);}
 static String exe(){return new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
 static Process child(Path log,String... args)throws Exception {List<String> command=new ArrayList<String>(Arrays.asList(exe(),"-cp",System.getProperty("java.class.path"),V1MigrationStageTest.class.getName()));command.addAll(Arrays.asList(args));return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();}
 static void stopOwned(Process p)throws Exception {if(p.isAlive()){p.destroy();if(!p.waitFor(5,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor();}}}
 static Map<String,String> evidence(File target)throws Exception {Map<String,String> result=new TreeMap<String,String>();for(String suffix:new String[]{"",".migration-owner","-wal","-shm","-journal"}){File file=new File(target+suffix);if(file.isFile())result.put(suffix,Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));}return result;}
 static V1LaneMigration.CopyState status(File source,File target)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);V1LaneMigration.CopyState state;do{state=V1LaneMigration.inspectCopyState(true,true,source,target);if(!state.state.equals("BLOCKED_OWNER_BUSY"))return state;Thread.sleep(20);}while(System.nanoTime()<end);return state;}
 static V1LaneMigration.Report retry(File source,File target)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(true)try{return V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);}catch(Exception e){boolean busy=(e instanceof SQLException&&(((SQLException)e).getErrorCode()==5||((SQLException)e).getErrorCode()==6))||"LEGACY_WORKER_BUSY".equals(e.getMessage());if(!busy||System.nanoTime()>end)throw e;Thread.sleep(20);}
 }
 static File source(Path home,String name)throws Exception {
  File source=home.resolve(name+".db").toFile();DurableSpool spool=new DurableSpool(source);
  File tsv=home.resolve("rr_"+name+".tsv").toFile();Files.write(tsv.toPath(),("M\t"+name+"\t1\tRPM\tRPM\trpm\nS\t"+name+"\t1\t0\t1791838800000\tRPM=700\nS\t"+name+"\t1\t1\t1791838801000\tRPM=701\n").getBytes(StandardCharsets.UTF_8));
  new IncrementalTsvBridge(spool,D,V).importNew(tsv,10);spool.markProfileAcked(spool.pendingProfileEntries().get(0).profileId,true);DurableSpool.Batch batch=spool.nextBatch(1,200000);spool.markBatchAcked(batch,new DurableSpool.Ack(batch.batchId,"committed",1,1,0));
  spool.saveDeliveryState(3,1791838805000L,true,"NEEDS_REVIEW");spool.requireIngressReview("JOURNAL_IMPORT");try(LegacyWorkerGuard guard=LegacyWorkerGuard.acquire(source)){}return source;
 }
 public static void main(String[] args)throws Exception {
  if(args.length>0&&args[0].equals("halt")) {
   final V1LaneMigration.Stage selected=V1LaneMigration.Stage.valueOf(args[3]);V1LaneMigration.migrateCopyWithStages(true,true,new File(args[1]),new File(args[2]),new V1LaneMigration.StageHook(){public void at(V1LaneMigration.Stage stage){if(stage==selected)Runtime.getRuntime().halt(90+stage.ordinal());}});throw new AssertionError("halt boundary not reached");
  }
  if(args.length>0&&args[0].equals("hold-sqlite")) {
   Class.forName("org.sqlite.JDBC");try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+new File(args[1]).toURI().toASCIIString()+"?mode=rw");Statement s=db.createStatement()){s.execute("BEGIN IMMEDIATE");Files.write(Paths.get(args[2]),new byte[]{1});Thread.sleep(30000);s.execute("ROLLBACK");}return;
  }
  final Path home=Files.createTempDirectory("romlogger-migration-stages-");final File source=source(home,"stages");final byte[] original=Files.readAllBytes(source.toPath());final V1LaneMigration.Report baseline=V1LaneMigration.preflight(true,true,source);
  check(source.length()>32768,"fixture spans more than one copy chunk");List<String> summary=new ArrayList<String>();
  for(V1LaneMigration.Stage stage:V1LaneMigration.Stage.values()) {
   final File target=home.resolve(stage+".lane-migration.db").toFile();Process process=child(home.resolve(stage+".log"),"halt",source.toString(),target.toString(),stage.name());
   try{check(process.waitFor(45,TimeUnit.SECONDS)&&process.exitValue()==90+stage.ordinal(),"real child halts at "+stage);}finally{stopOwned(process);}
   check(Arrays.equals(original,Files.readAllBytes(source.toPath())),"source bytes/ACK remain unchanged at "+stage);
   Map<String,String> before=evidence(target);V1LaneMigration.CopyState state=status(source,target);check(before.equals(evidence(target)),"copy-state observation changes no target/owner/journal at "+stage);
   summary.add(stage+"\t"+state.state+"\t"+state.exit());
   if(stage==V1LaneMigration.Stage.OWNER_CREATED||stage==V1LaneMigration.Stage.COPY_PROGRESS) {
    check(state.state.equals(stage==V1LaneMigration.Stage.OWNER_CREATED?"OWNER_ONLY_REVIEW_REQUIRED":"PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED"),"incomplete boundary requires manual evidence review "+stage);
    if(stage==V1LaneMigration.Stage.COPY_PROGRESS)check(target.length()>0&&target.length()<source.length(),"actual partial bytes retained after first forced chunk");
    reject(new Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);}},"no automatic overwrite/repair of incomplete copy "+stage);
    check(before.equals(evidence(target)),"refused retry retains incomplete evidence "+stage);
   } else {
    String expected=stage==V1LaneMigration.Stage.BEFORE_OUTPUT?"NO_CANDIDATE":stage==V1LaneMigration.Stage.COPY_COMPLETED?"COPIED_V1_READY_FOR_EXPLICIT_RETRY":stage==V1LaneMigration.Stage.BEFORE_COMMIT?"OWNED_INTERRUPTED_TARGET_REVIEW_REQUIRED":"COMMITTED_INVENTORY_VERIFIED";
    boolean correct=state.state.equals(expected)||(stage==V1LaneMigration.Stage.AFTER_COMMIT&&state.state.equals("OWNED_INTERRUPTED_TARGET_REVIEW_REQUIRED"));
    check(correct,"correct readonly boundary state "+stage+" -> "+state.state);
    V1LaneMigration.Report retried=retry(source,target);check(retried.dataSha.equals(baseline.dataSha)&&retried.acked==1&&retried.pending==1,"explicit retry verifies full preserved inventory "+stage);
    check(status(source,target).state.equals("COMMITTED_INVENTORY_VERIFIED"),"completed candidate classified without activation "+stage);
   }
   check(Arrays.equals(original,Files.readAllBytes(source.toPath())),"source retained after diagnosis/retry "+stage);
  }
  final File busyTarget=home.resolve("busy.lane-migration.db").toFile();Files.copy(source.toPath(),busyTarget.toPath());Files.write(Paths.get(busyTarget+".migration-owner"),("RMI1\n"+baseline.sourceSha+"\n").getBytes(StandardCharsets.US_ASCII));
  Path ready=home.resolve("sqlite-ready");Process writer=child(home.resolve("sqlite-holder.log"),"hold-sqlite",busyTarget.toString(),ready.toString());
  try {
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!Files.exists(ready)&&writer.isAlive()&&System.nanoTime()<end)Thread.sleep(20);check(Files.exists(ready)&&writer.isAlive(),"actual second JVM holds SQLite transaction");
   boolean busy=false;try{V1LaneMigration.migrateCopy(true,true,source,busyTarget,V1LaneMigration.NO_FAULT);}catch(SQLException e){if(e.getErrorCode()==5||e.getErrorCode()==6)busy=true;else throw e;}
   check(busy,"noncooperative SQLite writer yields BUSY refusal, never forced takeover");check(Arrays.equals(original,Files.readAllBytes(source.toPath())),"DB busy leaves original unchanged");
  }finally{stopOwned(writer);}
  check(retry(source,busyTarget).dataSha.equals(baseline.dataSha),"explicit retry after owned SQLite child exit preserves inventory");
  final File changed=source(home,"changed"),changedTarget=home.resolve("changed.lane-migration.db").toFile();final byte[] initial=Files.readAllBytes(changed.toPath());final boolean[] reached={false};
  boolean refused=false;try{V1LaneMigration.migrateCopyWithStages(true,true,changed,changedTarget,new V1LaneMigration.StageHook(){public void at(V1LaneMigration.Stage stage)throws Exception{if(stage==V1LaneMigration.Stage.BEFORE_COMMIT){reached[0]=true;Files.write(changed.toPath(),new byte[]{42},StandardOpenOption.APPEND);}}});}catch(IOException expected){refused=expected.getMessage().equals("source changed before commit");}
  check(reached[0]&&refused,"owned synthetic source mutation at actual precommit refuses");
  check(!Arrays.equals(initial,Files.readAllBytes(changed.toPath()))&&changed.length()==initial.length+1,"external synthetic mutation remains evidence, never silently repaired");
  check(status(changed,changedTarget).exit()==5,"changed source/owner requires manual review");
  check(!status(changed,changedTarget).json().contains(changed.toString())&&status(changed,changedTarget).json().contains("\"automatic_repair\":false"),"copy-state JSON omits paths and never grants repair or ACK");
  Files.write(home.resolve("matrix-evidence.tsv"),summary,StandardCharsets.UTF_8);
  System.out.println("V1 migration copy stages passed "+checks+" checks; actual child halt is not power loss; incomplete copies retained");
 }
}
