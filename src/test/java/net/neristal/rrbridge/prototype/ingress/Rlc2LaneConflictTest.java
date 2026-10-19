package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
/** Regression: legacy inventory is not exclusion against a running Worker. */
public final class Rlc2LaneConflictTest {
 public static void main(String[] args)throws Exception {
  final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
  File old=Rlc2IntegrationStoreTest.legacy(),out=Rlc2IntegrationStoreTest.target();
  Rlc2IntegrationStore store=new Rlc2IntegrationStore(true,out);
  boolean refused=false;
  try(RandomAccessFile h=new RandomAccessFile(old.getPath()+".worker.lock","rw");FileLock lock=h.getChannel().lock()) {
   try {Rlc2IntegrationStoreTest.ingest(store,f,old);}catch(java.io.IOException expected){refused=true;}
   if(!refused)throw new AssertionError("RLC2 committed while legacy Worker owns its compatibility lock");
  }
  if(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records")!=0)throw new AssertionError("conflict wrote records");
  if(Rlc2IntegrationStoreTest.ingest(store,f,old).recordsAdded!=3)throw new AssertionError("recovery did not import");
  System.out.println("PASS legacy Worker conflict refuses before records; retry after release completes");
  final File root=java.nio.file.Files.createTempDirectory("romlogger-common-lanes-").toFile();
  final File isolated=Rlc2IntegrationStoreTest.target(),old2=Rlc2IntegrationStoreTest.legacy();
  final Rlc2IntegrationStore cooperative=new Rlc2IntegrationStore(true,isolated);
  net.neristal.rrbridge.product.v1.DurableSpool product=new net.neristal.rrbridge.product.v1.DurableSpool(old2,root);
  try(net.neristal.rrbridge.product.v1.DurableSpool.IngressScope scope=product.beginIngress(Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.SESSION)) {
   boolean busy=false;
   try{cooperative.ingestCooperative(true,true,root,Rlc2ProductAdapterTest.SESSION,Rlc2IntegrationStoreTest.admit(f),f.dir,f.db.toFile(),old2,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT);}catch(java.io.IOException expected){busy=true;}
   if(!busy)throw new AssertionError("cross DB common lane did not exclude");
  }
  if(Rlc2PreviewStoreTest.count(isolated,"SELECT count(*) FROM preview_records")!=0)throw new AssertionError("busy common lane wrote");
  boolean mismatch=false;
  try{cooperative.ingestCooperative(true,true,root,"wrong-session",Rlc2IntegrationStoreTest.admit(f),f.dir,f.db.toFile(),old2,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT);}catch(java.io.IOException expected){mismatch=true;}
  if(!mismatch)throw new AssertionError("wrong lane key accepted");
  if(cooperative.ingestCooperative(true,true,root,Rlc2ProductAdapterTest.SESSION,Rlc2IntegrationStoreTest.admit(f),f.dir,f.db.toFile(),old2,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT).recordsAdded!=3)throw new AssertionError("cooperative recovery failed");
  System.out.println("PASS product scope versus RLC2 across separate DBs; mismatch rejects; release permits exact import");
 }
}
