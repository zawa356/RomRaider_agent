package net.neristal.rrbridge.prototype.ingress;
import net.neristal.rrbridge.product.v1.*;
import java.io.*;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype;
/** Test-only preflight -> owned RMI1 copy -> preserved inventory -> backup acceptance. */
public final class SyntheticMigrationRehearsal {
 public static final class Report {
  public final V1LaneMigration.Report inventory;public final IsolatedBackupAcceptance.Evidence backup;
  Report(V1LaneMigration.Report inventory,IsolatedBackupAcceptance.Evidence backup){this.inventory=inventory;this.backup=backup;}
  public String json(){return "{\"contract_version\":1,\"state\":\"ISOLATED_REHEARSAL_VERIFIED\",\"rmi1_inventory_verified\":true,\"rli2_record_conversion\":false,\"acked_records\":"+inventory.acked+",\"pending_records\":"+inventory.pending+",\"source_sha256\":\""+inventory.sourceSha+"\",\"preserved_rows_sha256\":\""+inventory.dataSha+"\",\"rollback\":\"OPERATOR_RETAINS_ORIGINAL\",\"production_authorized\":false,\"ack_eligible\":false}";}
 }
 public static Report run(boolean enable,boolean externallyQuiescent,DeploymentBinding.Layout l,File candidate,WatermarkV2Prototype.ReadOpener reader,final V1LaneMigration.Hook hook)throws Exception {
  return runWithStages(enable,externallyQuiescent,l,candidate,reader,new V1LaneMigration.StageHook(){public void at(V1LaneMigration.Stage stage)throws Exception{if(stage==V1LaneMigration.Stage.BEFORE_COMMIT)hook.beforeCommit();}});
 }
 public static Report runWithStages(boolean enable,boolean externallyQuiescent,DeploymentBinding.Layout l,File candidate,WatermarkV2Prototype.ReadOpener reader,V1LaneMigration.StageHook hook)throws Exception {
  DeploymentBinding.verify(enable,externallyQuiescent,l);
  V1LaneMigration.Report before=V1LaneMigration.preflight(true,true,l.legacy);
  V1LaneMigration.Report migrated=V1LaneMigration.migrateCopyWithStages(true,true,l.legacy,candidate,hook);
  V1LaneMigration.Report verified=V1LaneMigration.verifyIsolatedCopy(true,true,l.legacy,candidate);
  if(!before.sourceSha.equals(migrated.sourceSha)||!before.dataSha.equals(verified.dataSha))throw new IOException("REHEARSAL_SOURCE_CHANGED");
  return new Report(verified,IsolatedBackupAcceptance.capture(true,true,l,candidate,reader));
 }
}
