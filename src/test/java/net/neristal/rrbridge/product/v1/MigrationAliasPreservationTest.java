package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class MigrationAliasPreservationTest {
 public static void main(String[] args)throws Exception {
  Path home=Files.createTempDirectory("romlogger-migration-alias-");File source=home.resolve("original.db").toFile(),alias=home.resolve("alias.lane-migration.db").toFile();new DurableSpool(source);
  V1LaneMigration.Report before=V1LaneMigration.preflight(true,true,source);byte[] bytes=Files.readAllBytes(source.toPath());
  Files.createLink(alias.toPath(),source.toPath());Files.write(Paths.get(alias+".migration-owner"),("RMI1\n"+before.sourceSha+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  boolean refused=false;try{V1LaneMigration.migrateCopy(true,true,source,alias,V1LaneMigration.NO_FAULT);}catch(IOException expected){refused=true;}
  if(!Arrays.equals(bytes,Files.readAllBytes(source.toPath())))throw new AssertionError("SOURCE_MUTATED: migration target hardlink modified original before refusal");
  if(!refused)throw new AssertionError("hardlink migration target accepted");
  System.out.println("PASS hardlink target with matching copied owner refuses before any original DB mutation");
 }
}
