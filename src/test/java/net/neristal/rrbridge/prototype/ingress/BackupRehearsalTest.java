package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
public final class BackupRehearsalTest {
 static int checks;interface Action{void run()throws Exception;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static void reject(Action a,String why)throws Exception {try{a.run();}catch(Exception e){check(true,why);return;}throw new AssertionError(why);}
 static void privateFiles(Path home)throws Exception {
  try(java.util.stream.Stream<Path> paths=Files.walk(home)){for(Iterator<Path> it=paths.iterator();it.hasNext();){Path p=it.next();if(Files.isDirectory(p))DeploymentBindingTest.privateDir(p);else DeploymentBindingTest.privateFile(p);}}
 }
 public static void main(String[] args)throws Exception {
  final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-backup-rehearsal-"));
  final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1,home);
  Rlc2IntegrationStore.Admission admission=Rlc2IntegrationStoreTest.admit(f);
  File ledger=home.resolve("ledger.db").toFile();Files.move(f.db,ledger.toPath());
  final File legacy=home.resolve("v1.db").toFile(),out=home.resolve("out.rlc2-integration.db").toFile();DurableSpool spool=new DurableSpool(legacy);
  File historical=home.resolve("rr_historical.tsv").toFile();Files.write(historical.toPath(),("M\thistorical\t1\tRPM\tRPM\trpm\nS\thistorical\t1\t0\t1791838800000\tRPM=700\nS\thistorical\t1\t1\t1791838801000\tRPM=701\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
  new IncrementalTsvBridge(spool,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE).importNew(historical,10);
  spool.markProfileAcked(spool.pendingProfileEntries().get(0).profileId,true);DurableSpool.Batch batch=spool.nextBatch(1,200000);spool.markBatchAcked(batch,new DurableSpool.Ack(batch.batchId,"committed",1,1,0));
  new Rlc2IntegrationStore(true,out);File root=Files.createDirectory(home.resolve("root")).toFile();privateFiles(home);
  final DeploymentBinding.Layout l=new DeploymentBinding.Layout(root,legacy,out,f.dir.toFile(),f.dir.resolve("rotation.manifest").toFile(),ledger,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.SESSION,admission.manifestSession);
  DeploymentBinding.bindSynthetic(true,true,l);Rlc2IntegrationStore.ingestBoundDeployment(true,true,l,admission,f.env.reader(),Rlc2PreviewStore.NO_FAULT);privateFiles(home);
  final File candidate=home.resolve("copy.lane-migration.db").toFile();final byte[] original=Files.readAllBytes(legacy.toPath());
  final boolean[] reached={false};
  reject(new Action(){public void run()throws Exception{SyntheticMigrationRehearsal.run(true,true,l,candidate,f.env.reader(),new V1LaneMigration.Hook(){public void beforeCommit()throws Exception{reached[0]=true;throw new IOException("SYNTHETIC_INTERRUPT");}});}},"rehearsal refuses interrupted prepared inventory");
  check(reached[0],"interruption reached actual prepared commit");
  final SyntheticMigrationRehearsal.Report report=SyntheticMigrationRehearsal.run(true,true,l,candidate,f.env.reader(),V1LaneMigration.NO_FAULT);
  check(report.inventory.acked==1&&report.inventory.pending==1&&report.inventory.rows.get("v1_ack_receipts")==1,"rehearsal preserves actual ACK/unACK and receipt inventory");
  check(report.inventory.rows.get("v1_import_cursors")==1&&report.inventory.rows.get("v1_source_claims")==1,"cursor and explicit historical source claim retained");
  check(Arrays.equals(original,Files.readAllBytes(legacy.toPath())),"original unchanged through interrupted copy/retry and verification");
  check(SyntheticMigrationRehearsal.run(true,true,l,candidate,f.env.reader(),V1LaneMigration.NO_FAULT).inventory.dataSha.equals(report.inventory.dataSha),"rehearsal rerun preserves every rowid/record/profile/receipt fingerprint");
  check(report.json().contains("\"rli2_record_conversion\":false")&&report.json().contains("OPERATOR_RETAINS_ORIGINAL"),"RMI1 and RLI2 conversion/rollback boundaries explicit");
  final IsolatedBackupAcceptance.Evidence evidence=report.backup;final File copy=home.resolve("isolated-restore").toFile();evidence.copyToNewDirectory(true,copy);evidence.verifyDirectory(copy);
  check(evidence.json().contains("\"activation_allowed\":false"),"complete copied backup verified without activation");
  final File ev=home.resolve("backup.rlba1").toFile();evidence.writeNew(ev);IsolatedBackupAcceptance.read(ev).verifyDirectory(copy);check(true,"versioned role/hash evidence reload verifies copied target");
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  Path cli=home.resolve("restore-inspect.json");Process process=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),IsolatedBackupAcceptance.class.getName(),"--inspect-isolated-copy",ev.toString(),copy.toString()).redirectErrorStream(true).redirectOutput(cli.toFile()).start();
  check(process.waitFor()==0&&new String(Files.readAllBytes(cli),java.nio.charset.StandardCharsets.UTF_8).contains("VERIFIED_ISOLATED_COPY"),"actual restore CLI verifies copied target with activation false");
  final Path missing=copy.toPath().resolve("data-rmi1-owner");final byte[] owner=Files.readAllBytes(missing);Files.delete(missing);
  reject(new Action(){public void run()throws Exception{evidence.verifyDirectory(copy);}},"partial backup missing ownership sidecar refuses");Files.write(missing,owner);
  final Path rli=copy.toPath().resolve("data-rli2");final byte[] rliBytes=Files.readAllBytes(rli);
  Rlc2PreviewStoreTest.execute(rli.toFile(),"UPDATE preview_records SET body_json='tampered'");
  reject(new Action(){public void run()throws Exception{evidence.verifyDirectory(copy);}},"tampered record bytes refuse");Files.write(rli,rliBytes);
  final Path copiedV1=copy.toPath().resolve("data-v1");final byte[] v1=Files.readAllBytes(copiedV1);Rlc2PreviewStoreTest.execute(copiedV1.toFile(),"UPDATE v1_records SET acked=0");
  reject(new Action(){public void run()throws Exception{evidence.verifyDirectory(copy);}},"tampered ACK state refuses");Files.write(copiedV1,v1);
  final Path binding=copy.toPath().resolve("root-deployment.rldb1");final byte[] bindingBytes=Files.readAllBytes(binding);Files.write(binding,new byte[]{0});
  reject(new Action(){public void run()throws Exception{evidence.verifyDirectory(copy);}},"old or damaged owner metadata refuses exact evidence comparison");Files.write(binding,bindingBytes);
  final Path sidecar=copy.toPath().resolve("data-v1-wal");Files.write(sidecar,new byte[]{0});
  reject(new Action(){public void run()throws Exception{evidence.verifyDirectory(copy);}},"unexpected WAL or extra backup entry refuses");Files.delete(sidecar);
  try(LegacyWorkerGuard busy=LegacyWorkerGuard.acquire(legacy)) {
   reject(new Action(){public void run()throws Exception{IsolatedBackupAcceptance.capture(true,true,l,candidate,f.env.reader());}},"backup capture refuses actual active legacy guard");
  }
  try(LaneOwnership importer=LaneOwnership.acquire(true,root,l.device,l.vehicle,l.payloadSession)) {
   reject(new Action(){public void run()throws Exception{IsolatedBackupAcceptance.capture(true,true,l,candidate,f.env.reader());}},"backup capture refuses second active importer");
  }
  // These are deliberate changes to fresh invocation-owned synthetic fixtures only.
  RotationManifestPrototype.seal(f.dir,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
  reject(new Action(){public void run()throws Exception{IsolatedBackupAcceptance.capture(true,true,l,candidate,f.env.reader());}},"manifest publication ahead of ledger or changed bound identity refuses");
  check(Arrays.equals(original,Files.readAllBytes(legacy.toPath())),"operator-mediated rollback retains original; no copy applied or ACK rewound");
  check(!Files.exists(home.resolve("automatic-restore")),"no automatic restore target created");
  System.out.println("Backup/rehearsal passed "+checks+" checks; isolated byte equivalence only; no global clone fencing");
 }
}
