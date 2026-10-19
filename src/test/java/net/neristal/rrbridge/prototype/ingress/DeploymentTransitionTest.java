package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;
/** RLDT1/RLDB2 synthetic acceptance on real RLC2 sessions, real ledger, real RLI2 output. Fresh fixtures only. */
public final class DeploymentTransitionTest {
 static int checks;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static String refusal(Action a){try{a.run();return "ACCEPTED";}catch(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}}
 static void refuses(String code,String why,Action a){String got=refusal(a);check(got.equals(code)||(code.endsWith("*")&&got.startsWith(code.substring(0,code.length()-1))),why+" ["+got+"]");}
 static final String D=Rlc2ProductAdapterTest.DEVICE,V=Rlc2ProductAdapterTest.VEHICLE,A="synthetic-transition-a",B="synthetic-transition-b";
 static final String PURPOSE="Phase IV synthetic rebind acceptance";
 static void append(Env env,Path dir,int index,String... lines)throws Exception {
  Path p=RotationManifestPrototype.segment(dir,index);
  try(OwnedJournal w=env.openOwned(p)){for(String row:lines)w.append(row);WatermarkV2Scenarios.publish(env,p,w,WatermarkV2Prototype.NO_FAULT);}
 }
 /** Two segments: first sealed, second active (unless sealLast). */
 static Path session(Env env,Path parent,String name,String payload,long t)throws Exception {
  Path dir=Files.createDirectory(parent.resolve(name));DeploymentBindingTest.privateDir(dir);
  RotationManifestPrototype.begin(dir,env.reader(),env.platform());
  append(env,dir,1,"M\t"+payload+"\t1\tTEST_RPM\tSynthetic RPM\trpm","S\t"+payload+"\t1\t0\t"+t+"\tTEST_RPM=700");
  RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  RotationManifestPrototype.startNext(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  append(env,dir,2,"S\t"+payload+"\t1\t1\t"+(t+1000)+"\tTEST_RPM=701");
  return dir;
 }
 static Set<String> sources(Path dir)throws Exception {
  RotationManifestPrototype.State s=RotationManifestPrototype.read(dir,null);Set<String> ids=new TreeSet<String>();
  for(RotationManifestPrototype.Segment g:s.sealed)ids.add(g.sourceUuid);if(s.active!=null)ids.add(s.active.sourceUuid);return ids;
 }
 static String manifestSession(Path dir)throws Exception {return RotationManifestPrototype.read(dir,null).sessionUuid;}
 static DeploymentBinding.Layout layout(File root,File legacy,File out,Path dir,File ledger,String payload)throws Exception {
  return new DeploymentBinding.Layout(root,legacy,out,dir.toFile(),dir.resolve("rotation.manifest").toFile(),ledger,D,V,payload,manifestSession(dir));
 }
 static Rlc2PreviewStore.Result ingest(DeploymentBinding.Layout l,Env env)throws Exception {
  return Rlc2IntegrationStore.ingestBoundActive(true,true,l,new Rlc2IntegrationStore.Admission(l.session.toPath(),l.manifestSession,sources(l.session.toPath())),env.reader(),Rlc2PreviewStore.NO_FAULT);
 }
 static void privatize(Path home)throws Exception {
  try(java.util.stream.Stream<Path> files=Files.walk(home)){for(Iterator<Path> it=files.iterator();it.hasNext();){Path p=it.next();if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))DeploymentBindingTest.privateFile(p);}}
 }
 static String sha(File f)throws Exception {return DeploymentTransition.fileSha(f);}
 static long count(File db,String sql)throws Exception {return Rlc2PreviewStoreTest.count(db,sql);}
 static Set<String> set(String... s){return new TreeSet<String>(Arrays.asList(s));}

 public static void main(String[] args)throws Exception {
  if(args.length>0&&args[0].equals("--child-halt")){child(args);return;}
  final Env env=Rlc2ProductAdapterTest.environment();
  String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
  if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
  final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase4-transition-"));
  final Path dirA=session(env,home,"session-a",A,1791838800000L);
  final File ledger=home.resolve("ledger.db").toFile();
  RotationManifestPrototype.ingestSession(dirA,new WatermarkLedgerPrototype(ledger),env.reader(),null);
  final File legacy=home.resolve("old.db").toFile(),out=home.resolve("out.rlc2-integration.db").toFile();
  new DurableSpool(legacy);new Rlc2IntegrationStore(true,out);
  final File root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("root"))).toFile();
  privatize(home);
  final DeploymentBinding.Layout la=layout(root,legacy,out,dirA,ledger,A);
  DeploymentBinding.bindSynthetic(true,true,la);
  check(DeploymentTransition.verifyActive(true,true,la)==0,"RLDB1 pair is generation 0");
  check(ingest(la,env).recordsAdded==2,"generation 0 ingests session A");
  final String legacyBefore=sha(legacy);
  final byte[] manifestBeforeRotation=Files.readAllBytes(dirA.resolve("rotation.manifest"));

  // ---- Same logical Session: legitimate seal + new source changes the manifest file identity.
  RotationManifestPrototype.seal(dirA,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  RotationManifestPrototype.startNext(dirA,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  append(env,dirA,3,"S\t"+A+"\t1\t2\t1791838802000\tTEST_RPM=702");
  RotationManifestPrototype.ingestSession(dirA,new WatermarkLedgerPrototype(ledger),env.reader(),null);privatize(home);
  refuses("BINDING_LAYOUT_CHANGED","RLDB1 still refuses a legitimate manifest replacement (the Phase III limit)",new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,la);}});
  final String seg3=RotationManifestPrototype.read(dirA,null).active.sourceUuid;
  refuses("SOURCE_ADMISSION_REQUIRED","new physical source is never adopted implicitly",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(),PURPOSE,env.reader());}});
  refuses("SOURCE_ADMISSION_UNEXPECTED","extra admission refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3,UUID.randomUUID().toString()),PURPOSE,env.reader());}});
  refuses("EXTERNAL_GATE_REQUIRED","external single-active assertion mandatory",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,false,la,la,set(seg3),PURPOSE,env.reader());}});
  refuses("PURPOSE_REQUIRED","purpose mandatory",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3)," ",env.reader());}});
  final DeploymentTransition.Plan p1=DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());
  check(p1.kind==DeploymentTransition.Kind.SAME_SESSION_MANIFEST_UPDATE&&p1.generation==1,"dry run classifies same-session manifest update as generation 1");
  check(p1.json().contains("\"production_authorized\":false")&&p1.json().contains("\"automatic_activation\":false")&&!p1.json().contains(home.toString())&&!p1.json().contains(PURPOSE),"plan JSON has no paths/purpose text and no production permission");
  check(DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader()).digest.equals(p1.digest),"dry run is deterministic");
  check(!DeploymentBinding.superseded(la),"dry run writes nothing");
  refuses("PLAN_CHANGED","commit requires the approved dry-run digest",new Action(){public void run()throws Exception{DeploymentTransition.commitSynthetic(true,true,la,la,set(seg3),PURPOSE+" changed",p1.digest,env.reader(),DeploymentTransition.NO_FAULT);}});
  check(!DeploymentBinding.superseded(la),"refused commit before prepare leaves no evidence");
  // Interrupted after the anchor record: every caller stops.
  refuses("SIMULATED_INTERRUPT","fault after anchor record",new Action(){public void run()throws Exception{DeploymentTransition.commitSynthetic(true,true,la,la,set(seg3),PURPOSE,p1.digest,env.reader(),new DeploymentTransition.Hook(){public void at(DeploymentTransition.Stage s)throws Exception{if(s==DeploymentTransition.Stage.ANCHOR_WRITTEN)throw new IOException("SIMULATED_INTERRUPT");}});}});
  refuses("TRANSITION_INTERRUPTED","interrupted transition stops verifyActive",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,la);}});
  refuses("BINDING_SUPERSEDED","interrupted transition stops the RLDB1 entry",new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,la);}});
  refuses("TRANSITION_INTERRUPTED","interrupted transition stops new plans",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());}});
  refuses("TRANSITION_INTERRUPTED","interrupted transition stops bound ingest",new Action(){public void run()throws Exception{ingest(la,env);}});
  refuses("PURPOSE_REQUIRED","abandon needs a reason",new Action(){public void run()throws Exception{DeploymentTransition.abandonSynthetic(true,true,la,1,"");}});
  DeploymentTransition.abandonSynthetic(true,true,la,1,"synthetic interrupt drill");
  check(Files.exists(DeploymentTransition.anchorRecord(la,1))&&Files.exists(DeploymentTransition.prepare(la,1)),"abandon keeps partial evidence (nothing deleted)");
  refuses("MANIFEST_IDENTITY_CHANGED","after abandon generation 0 is active again and still refuses the replaced manifest",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,la);}});
  refuses("TRANSITION_ALREADY_ABANDONED","abandon cannot be repeated",new Action(){public void run()throws Exception{DeploymentTransition.abandonSynthetic(true,true,la,1,"again");}});
  // Concurrent participants block planning and commit.
  try(LaneOwnership writer=LaneOwnership.acquire(true,root,D,V,A)) {
   refuses("BLOCKED_UNCOOPERATIVE_PROCESS","held payload lane blocks dry run",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());}});
  }
  final DeploymentTransition.Plan retry=DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());
  check(retry.generation==2,"retry after abandon uses the next generation");
  final String[] code={null};
  try(LegacyWorkerGuard worker=LegacyWorkerGuard.acquire(legacy)) {
   refuses("BLOCKED_UNCOOPERATIVE_PROCESS","held legacy Worker lock blocks dry run",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());}});
   final DeploymentTransition.Plan p=retry;
   code[0]=refusal(new Action(){public void run()throws Exception{DeploymentTransition.commitSynthetic(true,true,la,la,set(seg3),PURPOSE,p.digest,env.reader(),DeploymentTransition.NO_FAULT);}});
  }
  check("BLOCKED_UNCOOPERATIVE_PROCESS".equals(code[0]),"held legacy Worker lock blocks commit ["+code[0]+"]");
  // Ledger tampering of an already stored receipt.
  final byte[] ledgerBytes=Files.readAllBytes(ledger.toPath());
  final String stored=RotationManifestPrototype.read(dirA,null).sealed.get(0).sourceUuid; // seg1, already in the output
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+ledger.getAbsolutePath());PreparedStatement s=c.prepareStatement("UPDATE proto_rlc2_receipts SET body_sha256='00' WHERE seq=1 AND source_uuid=?")){s.setString(1,stored);check(s.executeUpdate()==1,"fixture tampers exactly one stored ledger receipt");}
  refuses("LEDGER_RECEIPT_CHANGED","changed ledger receipt refuses",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());}});
  try(RandomAccessFile w=new RandomAccessFile(ledger,"rw")){w.setLength(0);w.write(ledgerBytes);w.getFD().sync();}
  final DeploymentTransition.Plan p2=DeploymentTransition.plan(true,true,la,la,set(seg3),PURPOSE,env.reader());
  check(DeploymentTransition.commitSynthetic(true,true,la,la,set(seg3),PURPOSE,p2.digest,env.reader(),DeploymentTransition.NO_FAULT)==2,"approved same-session transition commits generation 2");
  check(DeploymentTransition.verifyActive(true,true,la)==2,"RLDB2 generation 2 active");
  refuses("BINDING_SUPERSEDED","fixed RLDB1 entry refuses after transition",new Action(){public void run()throws Exception{Rlc2IntegrationStore.ingestBoundDeployment(true,true,la,new Rlc2IntegrationStore.Admission(dirA,la.manifestSession,sources(dirA)),env.reader(),Rlc2PreviewStore.NO_FAULT);}});
  check(ingest(la,env).recordsAdded==1,"active RLDB2 ingests the admitted new source row");
  check(ingest(la,env).recordsAdded==0,"retry idempotent");
  refuses("NO_TRANSITION_REQUIRED","no-op transition refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,la,set(),PURPOSE,env.reader());}});
  // A later seal (no new source) needs no rebind: content floor is extended, not file identity.
  RotationManifestPrototype.seal(dirA,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
  RotationManifestPrototype.ingestSession(dirA,new WatermarkLedgerPrototype(ledger),env.reader(),null);privatize(home);
  check(DeploymentTransition.verifyActive(true,true,la)==2,"RLDB2 accepts a later legitimate seal without rebinding");
  // Manifest rollback to the floor's predecessor is refused.
  final Path manifest=dirA.resolve("rotation.manifest");final byte[] sealedManifest=Files.readAllBytes(manifest);
  try(RandomAccessFile w=new RandomAccessFile(manifest.toFile(),"rw")){w.setLength(0);w.write(manifestBeforeRotation);}
  refuses("MANIFEST_NOT_EXTENDING_FLOOR","manifest rollback below floor refused",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,la);}});
  try(RandomAccessFile w=new RandomAccessFile(manifest.toFile(),"rw")){w.setLength(0);w.write(sealedManifest);}
  check(DeploymentTransition.verifyActive(true,true,la)==2,"restored manifest verifies again");
  // Restore clone of the root at another path: never the active authority.
  final Path clone=home.resolve("root-restored");Files.createDirectory(clone);DeploymentBindingTest.privateDir(clone);
  try(DirectoryStream<Path> e=Files.newDirectoryStream(root.toPath())){for(Path p:e)Files.copy(p,clone.resolve(p.getFileName()));}privatize(home);
  final DeploymentBinding.Layout cloned=new DeploymentBinding.Layout(clone.toFile(),la.legacy,la.output,la.session,la.manifest,la.ledger,D,V,A,la.manifestSession);
  refuses("ROOT_CHANGED","restored root clone at another path refused",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,cloned);}});
  refuses("DEPLOYMENT_ANCHOR_CHANGE_UNSUPPORTED","transition to another root refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,cloned,set(),PURPOSE,env.reader());}});

  // ---- Next Session: old Session closed and fully stored.
  final Path dirB=session(env,home,"session-b",B,1791838900000L);
  RotationManifestPrototype.ingestSession(dirB,new WatermarkLedgerPrototype(ledger),env.reader(),null);privatize(home);
  final DeploymentBinding.Layout lb=layout(root,legacy,out,dirB,ledger,B);
  check(ingest(la,env).recordsAdded==0,"final sealed manifest of A stored");
  refuses("SOURCE_ADMISSION_REQUIRED","next session sources need explicit admission",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,lb,set(),PURPOSE,env.reader());}});
  final DeploymentBinding.Layout sameDir=new DeploymentBinding.Layout(root,legacy,out,dirA.toFile(),la.manifest,ledger,D,V,B,lb.manifestSession);
  refuses("SAME_DIRECTORY_DIFFERENT_SESSION","same directory with another session refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,sameDir,sources(dirB),PURPOSE,env.reader());}});
  final DeploymentBinding.Layout reusedPayload=new DeploymentBinding.Layout(root,legacy,out,dirB.toFile(),lb.manifest,ledger,D,V,A,lb.manifestSession);
  refuses("PAYLOAD_SESSION_REUSED","old payload session cannot move to a new manifest",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,reusedPayload,sources(dirB),PURPOSE,env.reader());}});
  final DeploymentBinding.Layout otherDevice=new DeploymentBinding.Layout(root,legacy,out,dirB.toFile(),lb.manifest,ledger,UUID.randomUUID().toString(),V,B,lb.manifestSession);
  refuses("DEVICE_OR_VEHICLE_CHANGE_UNSUPPORTED","device change refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,otherDevice,sources(dirB),PURPOSE,env.reader());}});
  // A copied old session directory (same manifest session UUID) cannot become the next session.
  final Path copyA=Files.createDirectory(home.resolve("session-a-copy"));DeploymentBindingTest.privateDir(copyA);
  try(DirectoryStream<Path> e=Files.newDirectoryStream(dirA)){for(Path p:e)Files.copy(p,copyA.resolve(p.getFileName()));}privatize(home);
  final DeploymentBinding.Layout copied=new DeploymentBinding.Layout(root,legacy,out,copyA.toFile(),copyA.resolve("rotation.manifest").toFile(),ledger,D,V,B,UUID.randomUUID().toString());
  refuses("NEW_MANIFEST_INVALID","copied old session directory refused as next session (segment physical identity differs)",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,la,copied,sources(copyA),PURPOSE,env.reader());}});
  final DeploymentTransition.Plan p3=DeploymentTransition.plan(true,true,la,lb,sources(dirB),PURPOSE,env.reader());
  check(p3.kind==DeploymentTransition.Kind.NEXT_SESSION&&p3.generation==3,"next session classified as generation 3");
  // Real child JVM halts after the root record: interrupted evidence, then operator abandon and retry.
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  Process child=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),DeploymentTransitionTest.class.getName(),"--child-halt",root.toString(),legacy.toString(),out.toString(),dirA.toString(),dirB.toString(),ledger.toString(),p3.digest)
   .redirectErrorStream(true).redirectOutput(home.resolve("child-halt.log").toFile()).start();
  check(child.waitFor()==52,"child JVM halted after ROOT_WRITTEN (exit 52)");
  refuses("TRANSITION_INTERRUPTED","halted transition is interrupted for old layout",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,la);}});
  refuses("TRANSITION_INTERRUPTED","halted transition is interrupted for new layout",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,lb);}});
  DeploymentTransition.abandonSynthetic(true,true,la,3,"child halt drill");
  check(DeploymentTransition.verifyActive(true,true,la)==2,"after abandon the old session generation remains active");
  final DeploymentTransition.Plan p4=DeploymentTransition.plan(true,true,la,lb,sources(dirB),PURPOSE,env.reader());
  check(DeploymentTransition.commitSynthetic(true,true,la,lb,sources(dirB),PURPOSE,p4.digest,env.reader(),DeploymentTransition.NO_FAULT)==4,"next session commits generation 4");
  refuses("SESSION_CHANGED","old session layout is no longer active (no simultaneous activation)",new Action(){public void run()throws Exception{DeploymentTransition.verifyActive(true,true,la);}});
  refuses("SESSION_CHANGED","old session ingest refused after transition",new Action(){public void run()throws Exception{ingest(la,env);}});
  check(ingest(lb,env).recordsAdded==2,"new session ingests under generation 4");
  check(count(out,"SELECT count(DISTINCT run_id) FROM preview_records")==2&&count(out,"SELECT count(*) FROM preview_sessions")==2,"two runs / two manifest sessions kept separately");
  check(sha(legacy).equals(legacyBefore),"legacy v1 DB bytes (ACK/unACK state) unchanged by all transitions");
  // Ledger rollback (older ledger bytes) is detected before the next transition.
  final byte[] ledgerNow=Files.readAllBytes(ledger.toPath());
  try(RandomAccessFile w=new RandomAccessFile(ledger,"rw")){w.setLength(0);w.write(ledgerBytes);}
  RotationManifestPrototype.seal(dirB,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);privatize(home);
  final Path dirC=session(env,home,"session-c","synthetic-transition-c",1791839000000L);privatize(home);
  final DeploymentBinding.Layout lc=layout(root,legacy,out,dirC,ledger,"synthetic-transition-c");
  refuses("LEDGER_SOURCE_MISSING","rolled-back ledger refused",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,lb,lc,sources(dirC),PURPOSE,env.reader());}});
  try(RandomAccessFile w=new RandomAccessFile(ledger,"rw")){w.setLength(0);w.write(ledgerNow);}
  refuses("OLD_SESSION_NOT_CLOSED","sealed but not stored session is not closed",new Action(){public void run()throws Exception{DeploymentTransition.plan(true,true,lb,lc,sources(dirC),PURPOSE,env.reader());}});
  // Read-only CLI: inspect the active generation and dry-run a plan; no evidence file may appear.
  final long evidenceBefore=countEvidence(root.toPath());
  String[] inspect={"--inspect-active-synthetic","--external-single-active",root.toString(),legacy.toString(),out.toString(),dirB.toString(),lb.manifest.toString(),ledger.toString(),D,V,B,lb.manifestSession};
  String[] cli=capture(inspect,env);
  check(cli[0].equals("0")&&cli[1].contains("\"active_generation\":4")&&cli[1].contains("\"production_authorized\":false")&&!cli[1].contains(home.toString()),"inspect CLI exit0 reports generation 4 without paths");
  String[] old={"--inspect-active-synthetic","--external-single-active",root.toString(),legacy.toString(),out.toString(),dirA.toString(),la.manifest.toString(),ledger.toString(),D,V,A,la.manifestSession};
  cli=capture(old,env);check(cli[0].equals("5")&&cli[1].contains("SESSION_CHANGED"),"inspect CLI exit5 for the superseded old session");
  String[] plan={"--plan-synthetic","--external-single-active",root.toString(),legacy.toString(),out.toString(),dirB.toString(),lb.manifest.toString(),ledger.toString(),D,V,B,lb.manifestSession,
   dirC.toString(),lc.manifest.toString(),"synthetic-transition-c",lc.manifestSession,String.join(",",sources(dirC)),PURPOSE};
  cli=capture(plan,env);check(cli[0].equals("5")&&cli[1].contains("OLD_SESSION_NOT_CLOSED"),"plan CLI exit5 with coded reason");
  check(capture(new String[]{"--commit"},env)[0].equals("2"),"CLI has no commit/abandon mode (usage exit2)");
  check(countEvidence(root.toPath())==evidenceBefore,"read-only CLI wrote no evidence");
  System.out.println("Deployment transition passed "+checks+" checks; RLDT1/RLDB2 synthetic only; global fencing BLOCKED; directory durability UNKNOWN; actual JVM="+System.getProperty("sun.arch.data.model"));
 }
 static long countEvidence(Path root)throws Exception {long n=0;try(DirectoryStream<Path> e=Files.newDirectoryStream(root,"deployment.*")){for(Path p:e)n++;}return n;}
 static String[] capture(String[] args,Env env)throws Exception {
  PrintStream saved=System.out;ByteArrayOutputStream buffer=new ByteArrayOutputStream();
  System.setOut(new PrintStream(buffer,true,"UTF-8"));int code;
  try{code=DeploymentTransition.run(args,env.reader());}finally{System.setOut(saved);}
  return new String[]{String.valueOf(code),new String(buffer.toByteArray(),"UTF-8")};
 }
 /** Child: commit the approved plan and Runtime.halt right after the root record. Not power loss. */
 static void child(String[] a)throws Exception {
  final Env env=Rlc2ProductAdapterTest.environment();
  File root=new File(a[1]),legacy=new File(a[2]),out=new File(a[3]),ledger=new File(a[6]);
  DeploymentBinding.Layout old=layout(root,legacy,out,Paths.get(a[4]),ledger,A),next=layout(root,legacy,out,Paths.get(a[5]),ledger,B);
  DeploymentTransition.commitSynthetic(true,true,old,next,sources(Paths.get(a[5])),PURPOSE,a[7],env.reader(),new DeploymentTransition.Hook(){public void at(DeploymentTransition.Stage s){if(s==DeploymentTransition.Stage.ROOT_WRITTEN)Runtime.getRuntime().halt(52);}});
  System.exit(1);
 }
}
