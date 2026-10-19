package net.neristal.rrbridge.product.v1;
/** Synthetic child JVM: reports itself, opens its dependency JAR, waits for the parent to stop it. */
public final class ProvenanceChild {
 public static void main(String[] args)throws Exception {
  Class.forName("org.sqlite.JDBC"); // opens the dependency JAR so it is visible as an open artifact
  System.out.write(ArtifactProvenance.encode(ArtifactProvenance.selfReport()));System.out.println("END");System.out.flush();
  new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
 }
}
