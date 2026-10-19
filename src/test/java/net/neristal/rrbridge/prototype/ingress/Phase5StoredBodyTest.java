package net.neristal.rrbridge.prototype.ingress;
import java.io.File;

/** A stored hash is a claim: retained v1 JSON/canonical fields must agree with the actual original too. */
public final class Phase5StoredBodyTest {
 public static void main(String[] args)throws Exception {
  Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();int failures=0;
  String[][] mutations={
   {"body_json='{}' WHERE acked=0","SAME_RECORD_ID_DIFFERENT_BODY"},
   {"body_json=NULL WHERE acked=0","UNACKED_BODY_MISSING"},
   {"captured_at='2026-10-19T01:00:00Z' WHERE acked=1","SAME_RECORD_ID_DIFFERENT_BODY"},
   {"sequence=100 WHERE acked=0","SAME_RECORD_ID_DIFFERENT_BODY"}
  };
  for(String[] mutation:mutations){
   File copy=f.copy(f.v1);Phase5ProvenanceTest.sql(copy,"UPDATE v1_records SET "+mutation[0]);
   LegacyProvenanceClassifier.Report r=f.classify(copy);
   if(r.migratable()!=0||LegacyProvenanceClassifierTest.reasons(r,mutation[1])!=1){failures++;System.out.println("FAIL stored body/metadata with retained hash: "+mutation[0]);}
   else System.out.println("PASS stored body/metadata with retained hash: "+mutation[0]+" ["+mutation[1]+"]");
  }
  if(failures>0)throw new AssertionError("stored v1 payload inconsistencies accepted: "+failures);
 }
}
