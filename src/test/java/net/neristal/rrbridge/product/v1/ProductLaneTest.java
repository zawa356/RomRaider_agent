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
  System.out.println("PASS product cross-DB conflict, no writes, key guard, scope guard, exact import, cursor and replay (7 checks)");
 }
}
