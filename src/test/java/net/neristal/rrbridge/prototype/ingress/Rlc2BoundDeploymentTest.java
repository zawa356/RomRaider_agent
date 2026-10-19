package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import net.neristal.rrbridge.product.v1.*;
public final class Rlc2BoundDeploymentTest {
 static int checks;
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 public static void main(String[] args)throws Exception {
  Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-bound-engine-"));
  final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1,home);
  final Rlc2IntegrationStore.Admission admission=Rlc2IntegrationStoreTest.admit(f);
  File ledger=home.resolve("ledger.db").toFile();Files.move(f.db,ledger.toPath());
  File legacy=home.resolve("old.db").toFile(),out=home.resolve("out.rlc2-integration.db").toFile();new DurableSpool(legacy);new Rlc2IntegrationStore(true,out);
  File root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("root"))).toFile();
  DeploymentBindingTest.privateDir(f.dir);
  try(java.util.stream.Stream<Path> files=Files.walk(home)){for(java.util.Iterator<Path> it=files.iterator();it.hasNext();){Path path=it.next();if(Files.isRegularFile(path))DeploymentBindingTest.privateFile(path);}}

  final DeploymentBinding.Layout l=new DeploymentBinding.Layout(root,legacy,out,f.dir.toFile(),f.dir.resolve("rotation.manifest").toFile(),ledger,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.SESSION,admission.manifestSession);
  DeploymentBinding.bindSynthetic(true,true,l);
  byte[] old=Files.readAllBytes(legacy.toPath());final boolean[] reached={false};
  boolean refused=false;
  try {Rlc2IntegrationStore.ingestBoundDeployment(true,true,l,admission,f.env.reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage)throws Exception {
   if(stage==Rlc2PreviewStore.Stage.BEFORE_COMMIT){reached[0]=true;Files.write(l.root.toPath().resolve(DeploymentBinding.NAME),new byte[]{0});}
  }});}catch(IOException expected){refused=true;}
  check(refused&&reached[0],"binding change at real precommit refuses");
  check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records")==0,"binding failure rolls back all output records");
  Files.write(root.toPath().resolve(DeploymentBinding.NAME),Files.readAllBytes(Paths.get(legacy+".deployment-binding")));
  Rlc2PreviewStore.Result result=Rlc2IntegrationStore.ingestBoundDeployment(true,true,l,admission,f.env.reader(),Rlc2PreviewStore.NO_FAULT);
  check(result.recordsAdded==3&&!result.ackEligible,"bound synthetic entry imports actual two-source candidate without ACK");
  check(Rlc2IntegrationStore.ingestBoundDeployment(true,true,l,admission,f.env.reader(),Rlc2PreviewStore.NO_FAULT).recordsAdded==0,"bound retry idempotent");
  check(java.util.Arrays.equals(old,Files.readAllBytes(legacy.toPath())),"legacy bytes unchanged");
  try(LegacyWorkerGuard worker=LegacyWorkerGuard.acquire(legacy)) {
   refused=false;try{Rlc2IntegrationStore.ingestBoundDeployment(true,true,l,admission,f.env.reader(),Rlc2PreviewStore.NO_FAULT);}catch(IOException expected){refused=true;}
   check(refused,"old exact Worker lock blocks bound engine");
  }
  System.out.println("Bound deployment engine passed "+checks+" checks");
 }
}
