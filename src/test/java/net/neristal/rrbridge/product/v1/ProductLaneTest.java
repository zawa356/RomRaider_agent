package net.neristal.rrbridge.product.v1;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
public final class ProductLaneTest {
 public static void main(String[] args)throws Exception {
  File dir=Files.createTempDirectory("romlogger-product-lane-").toFile(),root=new File(dir,"lanes");root.mkdir();
  File source=new File(dir,"rr_synthetic.tsv");
  Files.write(source.toPath(),("M\tsynthetic\t1\tRPM\tRPM\trpm\nS\tsynthetic\t1\t0\t1791838800000\tRPM=700\n").getBytes(StandardCharsets.UTF_8));
  DurableSpool a=new DurableSpool(new File(dir,"a.db"),root),b=new DurableSpool(new File(dir,"b.db"),root);
  try(DurableSpool.IngressScope scope=a.beginIngress(LaneOwnershipTest.D,LaneOwnershipTest.V,"synthetic")) {
   boolean refused=false;try{new IncrementalTsvBridge(b,LaneOwnershipTest.D,LaneOwnershipTest.V).importNew(source,10);}catch(java.io.IOException e){refused=true;}
   if(!refused||b.countPending()!=0)throw new AssertionError("conflict mutated second spool");
   boolean mismatch=false;try{a.claimSourceSession(LaneOwnershipTest.D,LaneOwnershipTest.V,"wrong","legacy","test");}catch(java.sql.SQLException e){mismatch=true;}
   if(!mismatch)throw new AssertionError("wrong session claimed");
  }
  boolean scopeRequired=false;try{a.claimSourceSession(LaneOwnershipTest.D,LaneOwnershipTest.V,"synthetic","legacy","test");}catch(java.sql.SQLException e){scopeRequired=true;}
  if(!scopeRequired)throw new AssertionError("claim bypass accepted");
  IncrementalTsvBridge importer=new IncrementalTsvBridge(b,LaneOwnershipTest.D,LaneOwnershipTest.V);
  if(importer.importNew(source,10).observations!=1||b.countPending()!=1||importer.importNew(source,10).processedLines!=0)throw new AssertionError("import/replay failed");
  if(b.loadImportCursor(source.getCanonicalPath()).lineNumber!=2)throw new AssertionError("cursor lost");
  File childDb=new File(dir,"worker.db"),log=new File(dir,"worker.log");
  File executable=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
  try(LaneOwnership held=LaneOwnership.acquire(true,root,LaneOwnershipTest.D,LaneOwnershipTest.V,"synthetic");java.net.ServerSocket endpoint=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
   ProcessBuilder worker=new ProcessBuilder(executable.toString(),"-cp",System.getProperty("java.class.path"),ProductBridgeWorker.class.getName(),"--once",dir.toString(),childDb.toString(),"http://127.0.0.1:"+endpoint.getLocalPort()).redirectErrorStream(true).redirectOutput(log);
   worker.environment().put("ROMLOGGER_DEVICE_ID",LaneOwnershipTest.D);worker.environment().put("ROMLOGGER_VEHICLE_ID",LaneOwnershipTest.V);worker.environment().put("ROMLOGGER_DEVICE_TOKEN","synthetic-test-token");worker.environment().put("ROMLOGGER_SYNTHETIC_LANE_ROOT",root.toString());
   Process child=worker.start();try{if(!child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)||child.exitValue()==0)throw new AssertionError("Worker did not refuse held lane");}finally{if(child.isAlive())child.destroyForcibly();}
   if(new DurableSpool(childDb).countPending()!=0||!new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8).contains("LANE_BUSY"))throw new AssertionError("Worker ignored opt-in lane root");
  }
  System.out.println("PASS real Worker subprocess opt-in rejects held lane before import/delivery");
  System.out.println("PASS product cross-DB conflict, no writes, key guard, scope guard, exact import, cursor and replay (7 checks)");
 }
}
