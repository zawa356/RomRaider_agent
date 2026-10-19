package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Final publication names retain the normal RLI2 dedicated-extension guard; the internal name is insufficient. */
public final class Phase5OutputNameTest {
 public static void main(String[] args)throws Exception {
  Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();Path dir=f.home;
  try{
   LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),dir.resolve("vault.db").toFile(),dir.resolve("product.db").toFile(),LegacyProvenanceClassifier.NO_FAULT);
   throw new AssertionError("non-dedicated output name accepted");
  }catch(IOException expected){if(!"STAGING_RLI2_EXTENSION_REQUIRED".equals(expected.getMessage()))throw new AssertionError("wrong refusal: "+expected.getMessage());}
  for(String name:new String[]{"vault.db","product.db","vault.db.rlpc1-work","vault.db.rlpc1-staged"})if(Files.exists(dir.resolve(name),LinkOption.NOFOLLOW_LINKS))throw new AssertionError("refusal wrote staging artifacts");
  System.out.println("PASS final RLI2 extension refused before staging file writes");
 }
}
