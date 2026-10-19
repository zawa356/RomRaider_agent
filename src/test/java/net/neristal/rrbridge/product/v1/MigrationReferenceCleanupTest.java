package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class MigrationReferenceCleanupTest {
 static Set<Path> references()throws Exception {
  Set<Path> paths=new HashSet<Path>();try(DirectoryStream<Path> entries=Files.newDirectoryStream(Paths.get(System.getProperty("java.io.tmpdir")))){for(Path p:entries){String n=p.getFileName().toString();if(n.startsWith("romlogger-v1-schema-reference-")||n.startsWith("romlogger-migration-schema-reference-"))paths.add(p);}}return paths;
 }
 public static void main(String[] args)throws Exception {
  Path own=Files.createTempDirectory("romlogger-cleanup-fixture-");File source=own.resolve("v1.db").toFile(),target=own.resolve("copy.lane-migration.db").toFile();new DurableSpool(source);
  Set<Path> before=references();
  for(int i=0;i<3;i++){V1LaneMigration.preflight(true,true,source);V1LaneMigration.migrateCopy(true,true,source,target,V1LaneMigration.NO_FAULT);}
  Set<Path> added=references();added.removeAll(before);if(!added.isEmpty())throw new AssertionError("REFERENCE_LEAK: new schema reference directories="+added.size());
  for(Path p:before)if(!Files.exists(p))throw new AssertionError("pre-existing reference touched");
  System.out.println("PASS repeated preflight/candidate verification leaves zero new schema references; pre-existing references retained");
 }
}
