package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.Files;
import net.neristal.rrbridge.product.v1.LaneOwnership;
public final class Rlc2PublicationTest {
 static int checks;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 public static void main(String[] args)throws Exception {
  final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
  final File root=Files.createTempDirectory("romlogger-publication-").toFile(),old=Rlc2IntegrationStoreTest.legacy(),out=Rlc2IntegrationStoreTest.target();
  final Rlc2IntegrationStore store=new Rlc2IntegrationStore(true,out);final Rlc2IntegrationStore.Admission admission=Rlc2IntegrationStoreTest.admit(f);
  try(LaneOwnership writer=Rlc2PublicationBoundary.acquire(true,root,admission.manifestSession)) {
   boolean refused=false;try{store.ingestPublished(true,root,Rlc2ProductAdapterTest.SESSION,admission,f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT);}catch(java.io.IOException expected){refused=true;}
   check(refused,"Reader refuses while Writer owns publication boundary");
   check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records")==0,"publication conflict writes nothing");
  }
  final boolean[] blocked={false};
  Rlc2PreviewStore.Result first=store.ingestPublished(true,root,Rlc2ProductAdapterTest.SESSION,admission,f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage)throws Exception {
   if(stage==Rlc2PreviewStore.Stage.BEFORE_COMMIT)try{Rlc2PublicationBoundary.update(true,root,admission.manifestSession,new Rlc2PublicationBoundary.Update(){public void run(){throw new AssertionError("writer ran inside Reader boundary");}});}catch(java.io.IOException expected){blocked[0]=true;}
  }});
  check(blocked[0]&&first.recordsAdded==3&&!first.ackEligible,"Writer cannot mutate through Reader commit; exact normal import succeeds");
  Rlc2PublicationBoundary.update(true,root,admission.manifestSession,new Rlc2PublicationBoundary.Update(){public void run()throws Exception {
   RotationManifestPrototype.seal(f.dir,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
   RotationManifestPrototype.startNext(f.dir,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
   f.append(3,"S\t"+Rlc2ProductAdapterTest.SESSION+"\t1\t2\t1791838802000\tTEST_RPM=702");
   RotationManifestPrototype.ingestSession(f.dir,f.ledger,f.env.reader(),null);
  }});
  Rlc2PreviewStore.Result next=store.ingestPublished(true,root,Rlc2ProductAdapterTest.SESSION,Rlc2IntegrationStoreTest.admit(f),f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT);
  check(next.recordsAdded==1&&next.receiptsAdded==1,"published seal/rotation/ledger boundary extends three-source run once");
  check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"publication cannot enable ACK");
  boolean refused=false;try{store.ingestPublished(false,null,null,null,null,null,null,null,null,null,null);}catch(java.io.IOException e){refused=true;}
  check(refused,"publication defaults disabled before inputs");
  System.out.println("RLC2 publication passed "+checks+" checks; cooperative synthetic Writer only");
 }
}
