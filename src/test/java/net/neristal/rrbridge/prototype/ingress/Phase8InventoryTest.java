package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.*;

/**
 * Phase VIII-1 RLDI1 distribution inventory on fresh synthetic install directories. Optional argument: the packaged
 * entry closure JAR (romlogger-offline.jar) whose current content is inspected as a real artifact.
 */
public final class Phase8InventoryTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final boolean POSIX=FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    static byte[] classBytes(Class<?> c)throws IOException {
        try(InputStream in=c.getResourceAsStream("/"+c.getName().replace('.','/')+".class")){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}
    }
    static void jar(Path p,Manifest mf,Map<String,byte[]> entries)throws IOException {
        try(JarOutputStream j=mf==null?new JarOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW)):new JarOutputStream(Files.newOutputStream(p,StandardOpenOption.CREATE_NEW),mf)){
            for(Map.Entry<String,byte[]> e:entries.entrySet()){j.putNextEntry(new JarEntry(e.getKey()));j.write(e.getValue());j.closeEntry();}
        }
    }
    static Map<String,byte[]> classes(Class<?>... cs)throws IOException {Map<String,byte[]> m=new LinkedHashMap<String,byte[]>();for(Class<?> c:cs)m.put(c.getName().replace('.','/')+".class",classBytes(c));return m;}

    /** A fresh install directory with one approved JAR, one dependency, one launcher, one profile; record outside it. */
    static final class Install {
        final Path home,root;final File record;final Map<String,String> roles=new TreeMap<String,String>();
        Install(Map<String,byte[]> approvedClasses)throws Exception {
            home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase8-inv-"));
            root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("install")));
            Files.createDirectories(root.resolve("lib"));Files.createDirectories(root.resolve("profiles"));
            jar(root.resolve("lib/romlogger-offline.jar"),null,approvedClasses);roles.put("lib/romlogger-offline.jar","approved_jar");
            Map<String,byte[]> dep=new LinkedHashMap<String,byte[]>();dep.put("synthetic/resource.txt",new byte[]{1,2,3});
            jar(root.resolve("lib/dependency.jar"),null,dep);roles.put("lib/dependency.jar","dependency_jar");
            Files.write(root.resolve("run-entry.bat"),"@echo synthetic launcher only\r\n".getBytes(StandardCharsets.US_ASCII));roles.put("run-entry.bat","launcher");
            Files.write(root.resolve("profiles/entry.rlop1"),"synthetic profile\n".getBytes(StandardCharsets.US_ASCII));roles.put("profiles/entry.rlop1","profile");
            record=home.resolve("install.rldi1").toFile();pin();
        }
        Install()throws Exception {this(classes(OfflineCandidateEntry.class,DistributionInventory.class));}
        void pin()throws Exception {
            Files.deleteIfExists(record.toPath());
            DeploymentBinding.createRecord(record.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
        }
        DistributionInventory.Report inspect(){return DistributionInventory.inspect(true,root.toFile(),record);}
    }
    static void mismatch(DistributionInventory.Report r,String code,String label){
        check("MISMATCH".equals(r.inventory)&&r.findings.containsKey(code)&&!r.matched(),label+" -> "+code+" "+r.json());
        safe(r);
    }
    static void safe(DistributionInventory.Report r){
        String j=r.json();
        check(j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")&&j.contains("\"production_authorized\":false")&&j.contains("\"ack_eligible\":false")&&j.contains("\"global_old_binary_exclusion\":\"NOT_PROVEN\""),"inventory never authorizes and never claims global exclusion");
        check(!j.contains("romlogger-phase8-inv-")&&!j.contains("run-entry")&&!j.contains("dependency.jar"),"JSON carries no paths or file names");
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);

        // ---- class-file main detection.
        check(DistributionInventory.declaresMain(classBytes(OfflineCandidateEntry.class)),"parser finds the entry's main");
        check(DistributionInventory.declaresMain(classBytes(Rlc2IntegrationCli.class)),"parser finds the legacy CLI's main");
        check(!DistributionInventory.declaresMain(classBytes(DistributionInventory.class)),"parser finds no main in a library class");
        check(DistributionInventory.declaresMain(new byte[]{1,2,3,4,5}),"malformed class file is treated as a main (fail closed)");

        // ---- disabled.
        check("NOT_RUN".equals(DistributionInventory.inspect(false,null,null).inventory),"disabled inventory inspects nothing");

        // ---- GREEN.
        Install ok=new Install();
        DistributionInventory.Report g=ok.inspect();
        check(g.matched()&&g.findings.isEmpty()&&g.files==4&&g.unapprovedMains.isEmpty(),"exact synthetic install MATCHED "+g.json());
        safe(g);
        check(POSIX?g.acl.equals("POSIX_OWNER_ONLY_WRITE"):g.acl.equals("UNKNOWN_WINDOWS_ACL_NOT_INSPECTED"),"access control reported honestly ["+g.acl+"]");
        check(g.json().equals(ok.inspect().json()),"inventory is repeatable and read-only");

        // ---- through the single entry (read-only mode).
        {ByteArrayOutputStream o=new ByteArrayOutputStream();int exit=OfflineCandidateEntry.run(new String[]{"--inventory",ok.root.toString(),ok.record.toString()},null,new PrintStream(o,true,"UTF-8"));
         String j=new String(o.toByteArray(),StandardCharsets.UTF_8);
         check(exit==0&&j.contains("\"mode\":\"INVENTORY\"")&&j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"inventory\":\"MATCHED\"")&&j.contains("\"launch_authorized\":false"),"entry --inventory MATCHED exit0, still BLOCKED "+j.trim());
         Install t=new Install();Files.write(t.root.resolve("old-worker.cmd"),new byte[]{1});o.reset();
         exit=OfflineCandidateEntry.run(new String[]{"--inventory",t.root.toString(),t.record.toString()},null,new PrintStream(o,true,"UTF-8"));
         check(exit==5&&new String(o.toByteArray(),StandardCharsets.UTF_8).contains("UNLISTED_EXECUTABLE_OR_ARCHIVE"),"entry --inventory MISMATCH exit5");
         o.reset();exit=OfflineCandidateEntry.run(new String[]{"--inventory",ok.root.toString()},null,new PrintStream(o,true,"UTF-8"));
         check(exit==2,"entry --inventory with missing argument is EXPLICIT_MODE_REQUIRED");}

        // ---- unlisted files.
        {Install t=new Install();Files.write(t.root.resolve("notes.txt"),new byte[]{1});mismatch(t.inspect(),"UNLISTED_FILE","unlisted text file");}
        {Install t=new Install();Files.write(t.root.resolve("old-worker.cmd"),"java -cp x ProductBridgeWorker\r\n".getBytes(StandardCharsets.US_ASCII));mismatch(t.inspect(),"UNLISTED_EXECUTABLE_OR_ARCHIVE","unlisted launcher");}
        // ---- a legacy binary dropped next to the entry.
        {Install t=new Install();jar(t.root.resolve("lib/rrbridge-legacy.jar"),null,classes(Rlc2IntegrationCli.class));
         DistributionInventory.Report r=t.inspect();mismatch(r,"LEGACY_ENTRY_CLASS_PRESENT","legacy CLI JAR dropped in");
         check(r.findings.containsKey("UNLISTED_EXECUTABLE_OR_ARCHIVE")&&r.unapprovedMains.contains(Rlc2IntegrationCli.class.getName()),"legacy JAR is unlisted and its main is named");}
        // ---- listing a legacy binary does not bless it.
        {Install t=new Install();jar(t.root.resolve("lib/rrbridge-legacy.jar"),null,classes(ProductBridgeWorker.class));t.roles.put("lib/rrbridge-legacy.jar","dependency_jar");t.pin();
         DistributionInventory.Report r=t.inspect();mismatch(r,"LEGACY_ENTRY_CLASS_PRESENT","pinned legacy Worker JAR");
         check(!r.findings.containsKey("UNLISTED_EXECUTABLE_OR_ARCHIVE")&&r.unapprovedMains.contains(ProductBridgeWorker.class.getName()),"pinning a legacy JAR still refuses it");}
        // ---- secondary main inside the approved JAR (the shape of today's packaged closure).
        {Install t=new Install(classes(OfflineCandidateEntry.class,DeploymentTransition.class));
         DistributionInventory.Report r=t.inspect();mismatch(r,"UNAPPROVED_MAIN_METHOD","secondary main in approved JAR");
         check(r.unapprovedMains.equals(Collections.singletonList(DeploymentTransition.class.getName())),"secondary main named "+r.unapprovedMains);}
        // ---- approved main missing / two approved JARs.
        {Install t=new Install(classes(DistributionInventory.class));mismatch(t.inspect(),"APPROVED_MAIN_MISSING","approved JAR without the entry");}
        {Install t=new Install();jar(t.root.resolve("lib/second.jar"),null,classes(OfflineCandidateEntry.class));t.roles.put("lib/second.jar","approved_jar");t.pin();
         DistributionInventory.Report r=t.inspect();mismatch(r,"APPROVED_JAR_COUNT","two approved JARs");check(r.findings.containsKey("DUPLICATE_CLASS"),"entry class shadowed across JARs");}
        // ---- hashes and missing files.
        {Install t=new Install();Files.write(t.root.resolve("profiles/entry.rlop1"),new byte[]{'x'},StandardOpenOption.APPEND);mismatch(t.inspect(),"FILE_HASH_MISMATCH","profile byte appended");}
        {Install t=new Install();Files.delete(t.root.resolve("run-entry.bat"));mismatch(t.inspect(),"LISTED_FILE_MISSING","listed launcher deleted");}
        {Install t=new Install();t.roles.put("run-entry.bat","document");t.pin();mismatch(t.inspect(),"ROLE_DOES_NOT_MATCH_FILE","launcher pinned as document");}
        // ---- manifests and nested archives.
        {Manifest mf=new Manifest();mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS,Rlc2IntegrationCli.class.getName());
         Install t=new Install();Files.delete(t.root.resolve("lib/dependency.jar"));Map<String,byte[]> dep=new LinkedHashMap<String,byte[]>();dep.put("synthetic/resource.txt",new byte[]{1});
         jar(t.root.resolve("lib/dependency.jar"),mf,dep);t.pin();mismatch(t.inspect(),"UNAPPROVED_MANIFEST_MAIN","dependency Main-Class points at a legacy entry");}
        {Manifest mf=new Manifest();mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");mf.getMainAttributes().put(Attributes.Name.CLASS_PATH,"../elsewhere/legacy.jar");
         Install t=new Install();Files.delete(t.root.resolve("lib/dependency.jar"));Map<String,byte[]> dep=new LinkedHashMap<String,byte[]>();dep.put("synthetic/resource.txt",new byte[]{1});
         jar(t.root.resolve("lib/dependency.jar"),mf,dep);t.pin();mismatch(t.inspect(),"JAR_MANIFEST_CLASSPATH_OR_AGENT","Class-Path manifest");}
        {Install t=new Install();Files.delete(t.root.resolve("lib/dependency.jar"));Map<String,byte[]> dep=new LinkedHashMap<String,byte[]>();dep.put("inner/legacy.jar",new byte[]{'P','K',3,4});
         jar(t.root.resolve("lib/dependency.jar"),null,dep);t.pin();mismatch(t.inspect(),"NESTED_ARCHIVE","nested archive inside a dependency");}
        // ---- links / junctions.
        {Install t=new Install();Path target=DeploymentBindingTest.privateDir(Files.createDirectory(t.home.resolve("outside")));jar(target.resolve("legacy.jar"),null,classes(Rlc2IntegrationCli.class));
         Path link=t.root.resolve("linked");boolean made;
         if(POSIX){Files.createSymbolicLink(link,target);made=true;}
         else{Process p=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).redirectOutput(t.home.resolve("junction.log").toFile()).start();made=p.waitFor()==0&&Files.exists(link,LinkOption.NOFOLLOW_LINKS);}
         check(made,"created "+(POSIX?"symlink":"junction")+" to a directory holding a legacy JAR");
         DistributionInventory.Report r=t.inspect();mismatch(r,"LINK_OR_SPECIAL_IN_DISTRIBUTION",POSIX?"directory symlink":"directory junction");
         check(!r.findings.containsKey("LEGACY_ENTRY_CLASS_PRESENT"),"link target is not followed");}
        {Install t=new Install();Path target=t.home.resolve("outside.jar");jar(target,null,classes(Rlc2IntegrationCli.class));boolean made;
         try{Files.createSymbolicLink(t.root.resolve("lib/linked.jar"),target);made=true;}catch(IOException|UnsupportedOperationException noPrivilege){made=false;}
         if(made)mismatch(t.inspect(),"LINK_OR_SPECIAL_IN_DISTRIBUTION","file symlink");
         else System.out.println("NOT_RUN file symlink (no symlink privilege on this account; directory junction covers Windows reparse points)");}
        // ---- record placement / integrity / concurrent change.
        {Install t=new Install();File inside=t.root.resolve("install.rldi1").toFile();Files.copy(t.record.toPath(),inside.toPath());
         DistributionInventory.Report r=DistributionInventory.inspect(true,t.root.toFile(),inside);check("UNAVAILABLE".equals(r.inventory)&&"RECORD_INSIDE_DISTRIBUTION".equals(r.reason),"record inside the install refused ["+r.reason+"]");}
        {Install t=new Install();Map<String,String> rec=DeploymentBinding.readRecord(t.record.toPath());rec.put("approved_main",Rlc2IntegrationCli.class.getName());
         Files.delete(t.record.toPath());DeploymentBinding.createRecord(t.record.toPath(),DeploymentBinding.encodeRecord(rec));
         DistributionInventory.Report r=t.inspect();check("APPROVED_MAIN_NOT_THE_OFFLINE_ENTRY".equals(r.reason)&&!r.matched(),"record naming another main refused ["+r.reason+"]");}
        {Install t=new Install();Map<String,String> rec=DeploymentBinding.readRecord(t.record.toPath());rec.put("file.0000.path","../outside.jar");
         Files.delete(t.record.toPath());DeploymentBinding.createRecord(t.record.toPath(),DeploymentBinding.encodeRecord(rec));
         check("INVENTORY_PATH_INVALID".equals(t.inspect().reason),"record path escaping the install refused");}
        {final Install t=new Install();
         DistributionInventory.Report r=DistributionInventory.inspect(true,t.root.toFile(),t.record,new DistributionInventory.Hook(){public void at(String s)throws Exception{if(s.equals("SCANNED"))Files.write(t.root.resolve("late.jar"),new byte[]{1});}});
         check("INPUT_CHANGED_DURING_INVENTORY".equals(r.reason)&&!r.matched(),"file added during inventory stops ["+r.reason+"]");}
        {final Install t=new Install();
         DistributionInventory.Report r=DistributionInventory.inspect(true,t.root.toFile(),t.record,new DistributionInventory.Hook(){public void at(String s)throws Exception{if(s.equals("SCANNED"))Files.write(t.root.resolve("profiles/entry.rlop1"),new byte[]{'y'},StandardOpenOption.APPEND);}});
         check("INPUT_CHANGED_DURING_INVENTORY".equals(r.reason),"file grown during inventory stops ["+r.reason+"]");}
        if(POSIX){Install t=new Install();Files.setPosixFilePermissions(t.root.resolve("lib/dependency.jar"),PosixFilePermissions.fromString("rw-rw-r--"));
         mismatch(t.inspect(),"WRITABLE_BY_OTHERS","group-writable dependency (POSIX)");}
        else System.out.println("NOT_RUN POSIX permission case on Windows (ACL inspection is UNKNOWN by contract)");

        // ---- the real packaged entry closure, if supplied.
        if(args.length==1){
            Path built=Paths.get(args[0]);
            Install t=new Install();Files.delete(t.root.resolve("lib/romlogger-offline.jar"));Files.copy(built,t.root.resolve("lib/romlogger-offline.jar"));t.pin();
            DistributionInventory.Report r=t.inspect();
            System.out.println("PACKAGED_CLOSURE "+r.json());
            check(!r.findings.containsKey("LEGACY_ENTRY_CLASS_PRESENT")&&!r.unapprovedMains.contains(Rlc2IntegrationCli.class.getName())&&!r.unapprovedMains.contains(ProductBridgeWorker.class.getName()),"packaged closure carries no known legacy entry class");
            check("MISMATCH".equals(r.inventory)&&r.findings.containsKey("UNAPPROVED_MAIN_METHOD")&&!r.unapprovedMains.isEmpty(),"packaged closure still carries secondary mains (D2 blocker, reported not hidden) "+r.unapprovedMains);
        }else System.out.println("NOT_RUN packaged closure inspection (no JAR argument)");
        System.out.println("Phase VIII inventory checks="+checks);
    }
}
