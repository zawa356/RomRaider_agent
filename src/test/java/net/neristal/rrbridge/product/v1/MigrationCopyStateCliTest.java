package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
public final class MigrationCopyStateCliTest {
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory("romlogger-copy-state-cli-");File source=dir.resolve("v1.db").toFile(),target=dir.resolve("new.lane-migration.db").toFile();new DurableSpool(source);try(LegacyWorkerGuard lock=LegacyWorkerGuard.acquire(source)){}
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();Path log=dir.resolve("status.json");
  Process p=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),V1LaneMigration.class.getName(),"--inspect-isolated-copy-state","--external-quiescent",source.toString(),target.toString(),"--no-apply").redirectErrorStream(true).redirectOutput(log.toFile()).start();
  int exit=p.waitFor();String output=new String(Files.readAllBytes(log),java.nio.charset.StandardCharsets.UTF_8);
  if(exit!=5||!output.contains("NO_CANDIDATE")||!output.contains("\"automatic_repair\":false"))throw new AssertionError("COPY_STATE_CLI_MISSING: expected read-only no-candidate JSON/exit5");
  if(target.exists()||new File(target+".migration-owner").exists())throw new AssertionError("status created candidate");
  Process bad=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),V1LaneMigration.class.getName(),"--inspect-isolated-copy-state",source.toString(),target.toString(),"--no-apply").redirectErrorStream(true).redirectOutput(dir.resolve("usage.json").toFile()).start();
  if(bad.waitFor()!=2)throw new AssertionError("missing external quiescence accepted");
  System.out.println("PASS external quiescence CLI assertion required before inspection");
  System.out.println("PASS actual copy-state CLI emits NO_CANDIDATE/exit5 without creating target/owner");
 }
}
