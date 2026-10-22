package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.*;

/** Byte-identical replacement is still a different physical source (especially Windows fileKey=null). */
public final class Phase5PhysicalReplacementTest {
 public static void main(String[] args)throws Exception {
  final Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();
  File vault=f.home.resolve("replacement.db").toFile(),rli2=f.home.resolve("replacement.rlc2-integration.db").toFile();
  boolean refused=false;
  try{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,new LegacyProvenanceClassifier.StagingHook(){
   public void at(LegacyProvenanceClassifier.StagingStage s)throws Exception {
    if(s==LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN){
     Path source=Paths.get(f.p.sources.get(0).path),copy=f.home.resolve("byte-identical-copy.rlj");
     Files.copy(source,copy);Files.move(copy,source,StandardCopyOption.REPLACE_EXISTING);
    }
   }
  });}catch(Exception expected){refused=true;System.out.println("PASS byte-identical physical source replacement refused ["+expected.getClass().getSimpleName()+"]");}
  if(!refused)throw new AssertionError("byte-identical physical source replacement was accepted");
  if(!LegacyProvenanceClassifier.stagingState(vault,rli2).equals("INCOMPLETE_REVIEW_REQUIRED"))throw new AssertionError("replacement got a completion marker");
 }
}
