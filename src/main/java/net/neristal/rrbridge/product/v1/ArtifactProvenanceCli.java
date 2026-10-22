package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Paths;
import java.util.*;

/**
 * Read-only RLAP1 CLI, kept out of {@link ArtifactProvenance} so the single entry's dependency closure carries no
 * secondary main (Phase VIII RLDI1). {@code --describe MAIN_CLASS JAR...} prints the RLAP1 record (base64 lines; names,
 * not paths). {@code --inspect-process PID RLAP1_FILE} prints inspection JSON. Exit 0 = inspected (see process_provenance), 2 usage, 5 refused.
 */
public final class ArtifactProvenanceCli {
 public static void main(String[] a) {
  try {
   if(a.length>=3&&"--describe".equals(a[0])) {
    List<File> jars=new ArrayList<File>();for(int i=2;i<a.length;i++)jars.add(new File(a[i]));
    System.out.write(ArtifactProvenance.encode(ArtifactProvenance.describe(jars,a[1])));System.out.flush();System.exit(0);return;
   }
   if(a.length==3&&"--inspect-process".equals(a[0])) {
    ArtifactProvenance.Inspection r=ArtifactProvenance.inspectProcess(Long.parseLong(a[1]),DeploymentBinding.readRecord(Paths.get(a[2])));System.out.println(r.json());System.exit(r.inspectionSuccess?0:5);return;
   }
   System.out.println(ArtifactProvenance.unknown("USAGE").json());System.exit(2);
  }catch(Exception e){System.out.println(new ArtifactProvenance.Inspection(false,"UNKNOWN_PROCESS_PROVENANCE",e instanceof ArtifactProvenance.Refusal||e instanceof DeploymentBinding.Refusal?e.getMessage():"INSPECTION_UNAVAILABLE").json());System.exit(5);}
 }
 private ArtifactProvenanceCli(){}
}
