package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
public final class CutoverReadinessTest {
 static int checks;
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static File artifact(Path home)throws Exception {
  Path classes=Paths.get(CutoverReadiness.class.getProtectionDomain().getCodeSource().getLocation().toURI());File jar=home.resolve("known-test-build.jar").toFile();
  try(JarOutputStream out=new JarOutputStream(new FileOutputStream(jar));java.util.stream.Stream<Path> files=Files.walk(classes)) {
   for(Iterator<Path> it=files.iterator();it.hasNext();) {Path p=it.next();if(!p.toString().endsWith(".class"))continue;out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));Files.copy(p,out);out.closeEntry();}
  }return jar;
 }
 public static void main(String[] args)throws Exception {
  final DeploymentBinding.Layout l=DeploymentBindingTest.fixture();Files.delete(l.legacy.toPath());new DurableSpool(l.legacy);DeploymentBindingTest.privateFile(l.legacy.toPath());
  File jar=artifact(l.root.toPath().getParent());DeploymentBinding.bindSynthetic(true,true,l);
  check(CutoverReadiness.inspect(true,true,l,jar).reason.equals("LEGACY_LOCK_ABSENT"),"missing lock is review and creates no lock");
  check(!new File(l.legacy+".worker.lock").exists(),"read-only diagnosis leaves absent lock absent");
  try(LegacyWorkerGuard init=LegacyWorkerGuard.acquire(l.legacy)){}
  DeploymentBindingTest.privateFile(Paths.get(l.legacy+".worker.lock"));
  check(CutoverReadiness.inspect(true,true,l,null).reason.equals("UNKNOWN_WORKER_BINARY"),"unknown binary never inferred cooperative");
  check(CutoverReadiness.knownArtifact(jar),"current isolated build class bytes recognized");
  byte[] lockBytes=Files.readAllBytes(Paths.get(l.legacy+".worker.lock")),source=Files.readAllBytes(l.legacy.toPath());
  check(CutoverReadiness.inspect(true,true,l,jar).state.equals("READY_FOR_SYNTHETIC_TEST"),"known current build plus external gate is synthetic-only ready");
  check(Arrays.equals(lockBytes,Files.readAllBytes(Paths.get(l.legacy+".worker.lock")))&&Arrays.equals(source,Files.readAllBytes(l.legacy.toPath())),"diagnostic does not change DB or lock bytes");
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  Path empty=Files.createDirectory(l.root.toPath().getParent().resolve("empty-worker-input")),log=l.root.toPath().getParent().resolve("worker.log");
  ProcessBuilder pb=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),ProductBridgeWorker.class.getName(),"--foreground",empty.toString(),l.legacy.toString(),"http://127.0.0.1:1").redirectErrorStream(true).redirectOutput(log.toFile());
  pb.environment().put("ROMLOGGER_DEVICE_ID",l.device);pb.environment().put("ROMLOGGER_VEHICLE_ID",l.vehicle);pb.environment().put("ROMLOGGER_DEVICE_TOKEN",java.util.UUID.randomUUID().toString());pb.environment().remove("ROMLOGGER_SYNTHETIC_LANE_ROOT");
  Process worker=pb.start();try {
   Thread.sleep(1500); // Let the nonblocking old Worker acquire before advisory shared probes.
   long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);String state="";
   while(System.nanoTime()<end&&worker.isAlive()){state=CutoverReadiness.inspect(true,true,l,jar).state;if(state.equals("BLOCKED_UNCOOPERATIVE_PROCESS"))break;Thread.sleep(20);}
   check(state.equals("BLOCKED_UNCOOPERATIVE_PROCESS"),"actual legacy Worker subprocess blocks read-only cutover diagnostic");
  }finally {worker.destroy();if(!worker.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){worker.destroyForcibly();worker.waitFor();}}
  long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);CutoverReadiness.Report ready;
  do {ready=CutoverReadiness.inspect(true,true,l,jar);if(!ready.state.equals("BLOCKED_UNCOOPERATIVE_PROCESS"))break;Thread.sleep(20);}while(System.nanoTime()<end);
  check(ready.state.equals("READY_FOR_SYNTHETIC_TEST"),"explicit retry after test-owned Worker exit observes actual released lock: "+ready.reason);
  File tsv=l.root.toPath().getParent().resolve("rr_synthetic.tsv").toFile();Files.write(tsv.toPath(),("M\tsynthetic\t1\tRPM\tRPM\trpm\nS\tsynthetic\t1\t0\t1791838800000\tRPM=700\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  DurableSpool spool=new DurableSpool(l.legacy);new IncrementalTsvBridge(spool,l.device,l.vehicle).importNew(tsv,10);
  ready=CutoverReadiness.inspect(true,true,l,jar);check(ready.pending==1&&ready.claims==1&&ready.cursors==1&&ready.acked==0,"known v1 inventory reports unACK/source claims/cursors without sending");
  spool.requireIngressReview("JOURNAL_IMPORT");check(CutoverReadiness.inspect(true,true,l,jar).reason.equals("DELIVERY_OR_INGRESS_REVIEW"),"ingress error latch requires manual review");
  check(CutoverReadiness.inspect(true,false,l,jar).reason.equals("EXTERNAL_GATE_REQUIRED"),"lock absence cannot replace external quiescence gate");
  System.out.println("Cutover readiness passed "+checks+" checks; no production permission or automatic service stop");
 }
}
