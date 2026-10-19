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
 }
}
