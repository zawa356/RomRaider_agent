package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class RecoverySidecarAliasTest {
 public static void main(String[] args)throws Exception {
  Path home=Files.createTempDirectory("romlogger-recovery-alias-");File source=home.resolve("source.db").toFile(),target=home.resolve("candidate.lane-migration.db").toFile(),bystander=home.resolve("unrelated-synthetic.db").toFile();new DurableSpool(source);new DurableSpool(bystander);
  V1LaneMigration.Report before=V1LaneMigration.preflight(true,true,source);byte[] original=Files.readAllBytes(source.toPath()),protectedBytes=Files.readAllBytes(bystander.toPath());
  Files.copy(source.toPath(),target.toPath());Files.write(Paths.get(target+".migration-owner"),("RMI1\n"+before.sourceSha+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));Files.createLink(Paths.get(target+"-wal"),bystander.toPath());
  V1LaneMigration.CopyState state=V1LaneMigration.inspectCopyState(true,true,source,target);
  if(state.exit()!=5||!state.action.equals("RETAIN_EVIDENCE_MANUAL_REVIEW"))throw new AssertionError("unsafe sidecar diagnostic proposed recovery");
  boolean refused=false;try{V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);}catch(Exception expected){refused=true;}
  if(!Arrays.equals(protectedBytes,Files.readAllBytes(bystander.toPath())))throw new AssertionError("RECOVERY_ALIAS_MUTATED: target WAL hardlink changed unrelated synthetic DB");
  if(!refused)throw new AssertionError("RECOVERY_ALIAS_ACCEPTED: target WAL hardlink not rejected before writable recovery");
  if(!Arrays.equals(original,Files.readAllBytes(source.toPath())))throw new AssertionError("source changed");
  System.out.println("PASS target recovery hardlink refuses before touching unrelated DB or source");
 }
}
