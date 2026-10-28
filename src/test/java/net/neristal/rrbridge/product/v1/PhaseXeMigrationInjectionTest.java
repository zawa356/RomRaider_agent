package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
/**
 * Phase X-E1: the prepared-commit injection check of V1LaneMigrationTest must not hide an exception raised before the
 * hook (CI ingress run 40429873076 attempt 1, Zulu8 x86: "interrupted migration refuses" passed 5.13 s late, then
 * "injection actually reached prepared migration commit" failed with no cause in the log). Synthetic owned fixtures only.
 */
public final class PhaseXeMigrationInjectionTest {
 static int checks;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 /** Runs the V1LaneMigrationTest helper; returns its AssertionError text, or null when it accepted. */
 static String outcome(V1LaneMigrationTest.Action migration,Exception injection,boolean[] reached)throws Exception {
  try{V1LaneMigrationTest.rejectAtInjection(migration,injection,reached,"xe1 probe");}catch(AssertionError failure){return String.valueOf(failure.getMessage());}return null;
 }
 static V1LaneMigration.Hook throwing(final Exception e,final boolean[] reached){return new V1LaneMigration.Hook(){public void beforeCommit()throws Exception{reached[0]=true;throw e;}};}
 static boolean pathFree(String text,Path home){
  for(String secret:new String[]{home.toString(),home.getFileName().toString(),home.toUri().toString(),System.getProperty("user.name"),System.getProperty("java.io.tmpdir")})if(secret!=null&&!secret.isEmpty()&&text.contains(secret))return false;
  return !text.contains("\\")&&!text.contains(".db");
 }
 /** Prints a diagnostic only after it was proven path-free (evidence of the CI-visible format). */
 static void show(String name,String text,Path home){check(pathFree(text,home),name+" diagnostic is path-free");System.out.println("DIAG "+name+" "+text.substring(text.indexOf('[')));}
 static long lanes(File target)throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+target.toURI().toASCIIString()+"?mode=ro");Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='lane_migration_v1'")){r.next();return r.getLong(1);}
 }
 public static void main(String[] args)throws Exception {
  final Path home=Files.createTempDirectory("romlogger-xe1-injection-");
  final File source=V1MigrationStageTest.source(home,"xe");final byte[] original=Files.readAllBytes(source.toPath());
  final V1LaneMigration.Report baseline=V1LaneMigration.preflight(true,true,source);

  // Accept: the exact injected instance, raised by the hook at the prepared commit.
  final File target=home.resolve("accept.lane-migration.db").toFile();final IOException injection=new IOException("injected interruption");final boolean[] reached={false};
  String accepted=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,target,throwing(injection,reached));}},injection,reached);
  check(accepted==null&&reached[0],"exact injected exception after the prepared-commit hook is accepted");
  check(target.length()>0&&lanes(target)==0,"accepted interruption rolls back metadata/inventory/guards atomically");
  check(Arrays.equals(original,Files.readAllBytes(source.toPath())),"accepted interruption leaves original bytes unchanged");
  check(V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT).dataSha.equals(baseline.dataSha)&&V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT).dataSha.equals(baseline.dataSha),"retry after accepted interruption completes and stays idempotent");

  // Reject: an ordinary refusal before the hook (non-dedicated target name).
  final File plain=home.resolve("plain-copy.sqlite").toFile();final IOException i2=new IOException("injected interruption");final boolean[] r2={false};
  String early=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,plain,throwing(i2,r2));}},i2,r2);
  check(early!=null&&!r2[0]&&early.contains("PRIOR_EXCEPTION")&&early.contains("hook_reached=false")&&early.contains("type=IOException"),"refusal before the hook is a visible FAIL, not a pass");
  check(early.contains("code=dedicated isolated target required")&&early.contains("at=V1LaneMigration.migrateCopyWithStages:"),"early refusal names its stable code and product frame");
  check(!plain.exists()&&pathFree(early,home),"early refusal creates no target and its diagnostic carries no path");show("target-name",early,home);

  // Reject: exception whose message is a path (missing source) is reported by type only.
  final File absent=home.resolve("absent.db").toFile();final File absentTarget=home.resolve("absent.lane-migration.db").toFile();final IOException i3=new IOException("injected interruption");final boolean[] r3={false};
  String missing=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,absent,absentTarget,throwing(i3,r3));}},i3,r3);
  check(missing!=null&&!r3[0]&&missing.contains("type=NoSuchFileException")&&missing.contains("code=-")&&missing.contains("at=DeploymentBinding.safe:"),"missing-source exception before the hook is a visible FAIL");
  check(pathFree(missing,home)&&!absentTarget.exists(),"path-bearing exception message never reaches the diagnostic");show("missing-source",missing,home);

  // Reject: real DeploymentBinding hard-link refusal before the hook (the fsutil/nlink inspection route).
  final File linked=V1MigrationStageTest.source(home,"linked");Files.createLink(home.resolve("linked-alias.db"),linked.toPath());
  final File linkedTarget=home.resolve("linked.lane-migration.db").toFile();final IOException i4=new IOException("injected interruption");final boolean[] r4={false};
  String alias=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,linked,linkedTarget,throwing(i4,r4));}},i4,r4);
  check(alias!=null&&!r4[0]&&alias.contains("type=Refusal")&&alias.contains("code=HARDLINK_ALIAS")&&alias.contains("at=DeploymentBinding.singleLink:"),"hard-link Refusal before the hook is reported with its code and frame");
  check(pathFree(alias,home)&&!linkedTarget.exists(),"hard-link refusal diagnostic carries no path and creates no target");show("hard-link",alias,home);

  // Reject: an earlier-stage refusal shaped like the suspected CI cause (fsutil bound -> HARDLINK_INSPECTION_UNAVAILABLE).
  final File stagedTarget=home.resolve("staged.lane-migration.db").toFile();final IOException i5=new IOException("injected interruption");final boolean[] r5={false};
  String staged=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopyWithStages(true,true,source,stagedTarget,new V1LaneMigration.StageHook(){public void at(V1LaneMigration.Stage stage)throws Exception{
   if(stage==V1LaneMigration.Stage.BEFORE_OUTPUT)throw new DeploymentBinding.Refusal("HARDLINK_INSPECTION_UNAVAILABLE");
   if(stage==V1LaneMigration.Stage.BEFORE_COMMIT){r5[0]=true;throw i5;}}});}},i5,r5);
  check(staged!=null&&!r5[0]&&staged.contains("code=HARDLINK_INSPECTION_UNAVAILABLE")&&staged.contains("hook_reached=false")&&staged.contains("elapsed_ms="),"earlier-stage inspection refusal is not mistaken for the injection");
  check(!stagedTarget.exists(),"earlier-stage refusal leaves no target");show("earlier-stage",staged,home);

  // Reject: hook reached but a different instance (same type and text) surfaced.
  final File otherTarget=home.resolve("other.lane-migration.db").toFile();final IOException i6=new IOException("injected interruption");final boolean[] r6={false};
  String other=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,otherTarget,throwing(new IOException("injected interruption"),r6));}},i6,r6);
  check(other!=null&&r6[0]&&other.contains("hook_reached=true")&&other.contains("PRIOR_EXCEPTION"),"look-alike exception after the hook is not accepted as the injection");show("look-alike",other,home);
  check(lanes(otherTarget)==0,"look-alike interruption still rolls back atomically");

  // Reject: no exception at all.
  final File cleanTarget=home.resolve("clean.lane-migration.db").toFile();final IOException i7=new IOException("injected interruption");final boolean[] r7={false};
  String none=outcome(new V1LaneMigrationTest.Action(){public void run()throws Exception{V1LaneMigration.migrateCopy(true,true,source,cleanTarget,V1LaneMigration.NO_FAULT);}},i7,r7);
  check(none!=null&&none.contains("NO_EXCEPTION")&&none.contains("hook_reached=false"),"completed migration without the injection is a FAIL");show("no-exception",none,home);

  check(Arrays.equals(original,Files.readAllBytes(source.toPath())),"original byte-for-byte unchanged after every X-E1 case");
  System.out.println("Phase X-E1 migration injection passed "+checks+" checks; synthetic owned fixtures only");
 }
}
