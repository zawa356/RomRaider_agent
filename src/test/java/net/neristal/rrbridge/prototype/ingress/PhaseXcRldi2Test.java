package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.product.v1.DeploymentBindingTest;
import net.neristal.rrbridge.prototype.ingress.PhaseXaZipAuditTest.E;

/**
 * Phase X-C1 GREEN: the RLDI2 synthetic-only dependency-policy candidate. The canonical candidate = real entry closure JAR +
 * the real pinned sqlite-jdbc/slf4j(/JNA on Windows) JARs under their canonical names. RLDI2 must report it MATCHED (still
 * BLOCKED) while RLDI1 keeps reporting MISMATCH, and every adversarial variant (unknown/modified JAR, record or role
 * tampering, forged policy, aliases, injected mains/legacy entries, archive-shape tricks, malformed entries, bombs, races)
 * must fail closed. Owned temp directories only; nothing is launched; no file is written by the inventory.
 */
public final class PhaseXcRldi2Test {
    static int checks;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
    static final String[][] DEPS=PhaseXcF1ReproTest.DEPS;
    static File closure;static List<String[]> deps=new ArrayList<String[]>();static boolean jna;
    /** Fixture homes created by THIS run (removed after a successful run only; nothing else under the temp dir is touched). */
    static final List<Path> HOMES=new ArrayList<Path>();
    static void removeOwnFixtures()throws IOException {
        for(Path h:HOMES)Files.walkFileTree(h,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes a)throws IOException{Files.delete(f);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{Files.delete(d);return FileVisitResult.CONTINUE;}});
    }

    /** An owned install with an RLDI2 (and optionally RLDI1) record outside it. */
    static final class Cand {
        final Path home,root;final File record,record1;final Map<String,String> roles=new TreeMap<String,String>();
        Cand(List<String[]> with)throws Exception {
            Path r=PhaseXcF1ReproTest.install(closure,with,roles);root=r;home=r.getParent();HOMES.add(home);
            record=home.resolve("install.rldi2").toFile();record1=home.resolve("install.rldi1").toFile();
        }
        Cand()throws Exception {this(deps);}
        Path lib(String name){return root.resolve("lib/"+name);}
        void put(String rel,byte[] bytes,String role)throws Exception {Path p=root.resolve(rel);Files.createDirectories(p.getParent());Files.write(p,bytes);if(role!=null)roles.put(rel,role);}
        Cand pin()throws Exception {
            Files.deleteIfExists(record.toPath());DeploymentBinding.createRecord(record.toPath(),DeploymentBinding.encodeRecord(DistributionInventoryV2.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
            Files.deleteIfExists(record1.toPath());pinned1=false;// the RLDI1 record is written on first use (halves hashing on slow x86 JVMs)
            return this;
        }
        boolean pinned1;
        void pin1()throws Exception {if(!pinned1){DeploymentBinding.createRecord(record1.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));pinned1=true;}}
        Map<String,String> rec()throws Exception {return new TreeMap<String,String>(DeploymentBinding.readRecord(record.toPath()));}
        void rewrite(Map<String,String> m)throws Exception {Files.delete(record.toPath());DeploymentBinding.createRecord(record.toPath(),DeploymentBinding.encodeRecord(m));}
        String key(String rel)throws Exception {for(Map.Entry<String,String> e:rec().entrySet())if(e.getKey().endsWith(".path")&&e.getValue().equals(rel))return e.getKey().substring(0,e.getKey().length()-5);throw new AssertionError("no record entry "+rel);}
        DistributionInventoryV2.Report v2(){return DistributionInventoryV2.inspect(true,root.toFile(),record);}
        DistributionInventoryV2.Report v2(DistributionInventory.Hook h){return DistributionInventoryV2.inspect(true,root.toFile(),record,h);}
        DistributionInventory.Report v1()throws Exception {pin1();return DistributionInventory.inspect(true,root.toFile(),record1);}
    }
    /** Every RLDI2 report: gates closed, no path, file name or hash in the JSON. */
    static void safe(DistributionInventoryV2.Report r,String what){
        String j=r.json();
        check(j.contains("\"contract_version\":2")&&j.contains("\"inventory_version\":\"RLDI2\"")&&j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")
            &&j.contains("\"production_authorized\":false")&&j.contains("\"ack_eligible\":false")&&j.contains("\"global_old_binary_exclusion\":\"NOT_PROVEN\"")&&j.contains("\"synthetic_only\":true")
            // Windows: never a PASS-like ACL value (UNKNOWN before the access check runs, UNKNOWN_WINDOWS_ACL_NOT_INSPECTED after).
            &&(!System.getProperty("os.name").startsWith("Windows")||j.contains("\"access_control\":\"UNKNOWN_WINDOWS_ACL_NOT_INSPECTED\"")||j.contains("\"access_control\":\"UNKNOWN\""))
            &&!j.contains("romlogger-phasexc-")&&!j.contains(".jar")&&!j.contains("lib/")&&!j.matches("(?s).*[0-9a-f]{64}.*"),what+": gates closed, no path/file name/hash in JSON");
    }
    static void mismatch(DistributionInventoryV2.Report r,String what,String... codes){
        boolean ok="MISMATCH".equals(r.inventory)&&!r.matched();for(String c:codes)ok&=r.findings.containsKey(c);
        check(ok,what+" -> RLDI2 MISMATCH "+Arrays.toString(codes)+" "+r.findings);safe(r,what);
    }
    static void unavailable(DistributionInventoryV2.Report r,String code,String what){
        check("UNAVAILABLE".equals(r.inventory)&&code.equals(r.reason)&&!r.matched(),what+" -> RLDI2 UNAVAILABLE "+code+" ("+r.reason+")");safe(r,what);
    }
    /** Light base install (approved + slf4j only) for archive-shape and malformed-entry cases that do not involve sqlite-jdbc or JNA. */
    static Cand light()throws Exception {List<String[]> l=new ArrayList<String[]>();l.add(dep("slf4j-api-2.0.16.jar"));l.add(dep("slf4j-nop-2.0.16.jar"));return new Cand(l);}
    static String[] dep(String canonical){for(String[] d:deps)if(d[1].equals(canonical))return d;return null;}
    static List<String[]> without(String canonical){List<String[]> l=new ArrayList<String[]>();for(String[] d:deps)if(!d[1].equals(canonical))l.add(d);return l;}

    /** Re-packs a real JAR (valid ZIP, new bytes) with entries added/dropped and an optional manifest edit. */
    static byte[] repack(String src,Map<String,byte[]> add,Set<String> drop,Map<String,String> manifestEdits)throws Exception {
        ByteArrayOutputStream bo=new ByteArrayOutputStream();
        try(JarFile in=new JarFile(src)){
            Manifest mf=new Manifest(in.getManifest());
            if(manifestEdits!=null)for(Map.Entry<String,String> e:manifestEdits.entrySet()){if(e.getValue()==null)mf.getMainAttributes().remove(new Attributes.Name(e.getKey()));else mf.getMainAttributes().putValue(e.getKey(),e.getValue());}
            try(JarOutputStream out=new JarOutputStream(bo,mf)){
                for(Enumeration<JarEntry> en=in.entries();en.hasMoreElements();){JarEntry e=en.nextElement();
                    if(e.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")||(drop!=null&&drop.contains(e.getName())))continue;
                    out.putNextEntry(new JarEntry(e.getName()));try(InputStream is=in.getInputStream(e)){byte[] b=new byte[8192];int n;while((n=is.read(b))!=-1)out.write(b,0,n);}out.closeEntry();}
                if(add!=null)for(Map.Entry<String,byte[]> e:add.entrySet()){out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}
            }
        }
        return bo.toByteArray();
    }
    static byte[] jar(Manifest mf,Map<String,byte[]> entries)throws Exception {
        ByteArrayOutputStream bo=new ByteArrayOutputStream();
        try(JarOutputStream j=mf==null?new JarOutputStream(bo):new JarOutputStream(bo,mf)){for(Map.Entry<String,byte[]> e:entries.entrySet()){j.putNextEntry(new JarEntry(e.getKey()));j.write(e.getValue());j.closeEntry();}}
        return bo.toByteArray();
    }
    static Map<String,byte[]> one(String k,byte[] v){Map<String,byte[]> m=new LinkedHashMap<String,byte[]>();m.put(k,v);return m;}
    static byte[] cls(Class<?> c)throws Exception {return Phase8InventoryTest.classBytes(c);}
    static byte[] entryOf(String jarPath,String entry)throws Exception {try(JarFile j=new JarFile(jarPath);InputStream in=j.getInputStream(j.getEntry(entry))){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}}
    /** Snapshot of every file (bytes) under a directory, to prove the inventory wrote nothing. */
    static Map<String,String> snapshot(Path dir)throws Exception {
        final Map<String,String> m=new TreeMap<String,String>();
        Files.walkFileTree(dir,new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes a)throws IOException{
            try{m.put(f.toString(),DeploymentTransition.fileSha(f.toFile())+"/"+a.lastModifiedTime());}catch(Exception e){throw new IOException(e);}return FileVisitResult.CONTINUE;}});
        return m;
    }
    interface Change{void apply(Path file)throws Exception;}
    static DistributionInventoryV2.Report during(final Cand t,final String stage,final String rel,final Change c,final boolean[] done){
        return t.v2(new DistributionInventory.Hook(){public void at(String s)throws Exception{
            if((s.equals(stage)||s.equals(stage+":"+rel))&&!done[0]){done[0]=true;c.apply(t.root.resolve(rel));}}});
    }

    public static void main(String[] a)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" os="+System.getProperty("os.name"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        closure=new File(a[0]);check(closure.isFile(),"entry closure JAR built");
        for(String[] d:DEPS){if(new File(d[0]).isFile()){check(d[2].equals(DeploymentTransition.fileSha(new File(d[0]))),"real "+d[1]+" matches its repository pin");deps.add(d);}
            else System.out.println("NOT_RUN "+d[1]+" absent on this runner (JNA cases NOT_RUN; JNA is fetched on Windows jobs only)");}
        jna=dep("jna-5.14.0.jar")!=null;check(deps.size()>=3,"sqlite-jdbc and slf4j JARs present");
        String sqlite=dep("sqlite-jdbc-3.46.1.3.jar")[0];

        // ================= policy structure (trust root) =================
        check(DistributionInventoryV2.pins().size()==4,"policy has exactly the 4 audited pins");
        for(String[] d:DEPS){DistributionInventoryV2.Pin p=DistributionInventoryV2.pinBySha(d[2]);check(p!=null&&p.file.equals(d[1])&&p.id.equals(d[1].substring(0,d[1].length()-4)),"pin for "+d[1]+" is keyed by its repository SHA-256 and canonical name");}
        for(DistributionInventoryV2.Pin p:DistributionInventoryV2.pins())for(String x:p.exceptions){String kind=x.substring(0,x.indexOf('|'));
            check(DistributionInventoryV2.EXCEPTABLE.contains(kind)&&!x.contains("net.neristal")&&!x.contains("Receiver"),"pin "+p.id+" exception "+x+" is an exceptable kind and no first-party/legacy class");}
        {boolean ro;try{DistributionInventoryV2.pins().add(null);ro=false;}catch(UnsupportedOperationException ok){ro=true;}check(ro,"compiled-in pin list cannot be extended at run time");
         java.lang.reflect.Field f=DistributionInventoryV2.class.getDeclaredField("PINS");int mod=f.getModifiers();
         check(java.lang.reflect.Modifier.isPrivate(mod)&&java.lang.reflect.Modifier.isStatic(mod)&&java.lang.reflect.Modifier.isFinal(mod),"PINS is private static final (no setter, no external policy file, no record-supplied pin)");
         boolean noLoader=true;for(java.lang.reflect.Method m:DistributionInventoryV2.class.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(m.getModifiers())&&m.getName().toLowerCase(Locale.ROOT).contains("polic"))noLoader=false;
         check(noLoader,"no public API loads or replaces a policy");
         check(DistributionInventoryV2.POLICY_SHA256.matches("[0-9a-f]{64}"),"policy digest computed from the canonical pin text");}
        // validate(): forged or over-wide pins are rejected structurally.
        String goodSha="1111111111111111111111111111111111111111111111111111111111111111";
        String[][] bad={
            {"LEGACY_ENTRY_CLASS_PRESENT|net.neristal.rrbridge.Receiver","RLDI2_POLICY_KIND_NOT_EXCEPTABLE"},
            {"JAR_MANIFEST_CLASSPATH_OR_AGENT|Class-Path","RLDI2_POLICY_KIND_NOT_EXCEPTABLE"},
            {"NESTED_ARCHIVE|x.jar","RLDI2_POLICY_KIND_NOT_EXCEPTABLE"},
            {"DUPLICATE_CLASS|a.B","RLDI2_POLICY_KIND_NOT_EXCEPTABLE"},
            {"JAR_UNREADABLE|","RLDI2_POLICY_KIND_NOT_EXCEPTABLE"},
            {"UNAPPROVED_MAIN_METHOD|net.neristal.rrbridge.prototype.ingress.DeploymentTransitionCli","RLDI2_POLICY_FIRST_PARTY_OR_LEGACY"},
            {"UNAPPROVED_MAIN_METHOD|"+OfflineCandidateEntry.MAIN,"RLDI2_POLICY_FIRST_PARTY_OR_LEGACY"},
            {"UNAPPROVED_MANIFEST_MAIN|net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationCli","RLDI2_POLICY_FIRST_PARTY_OR_LEGACY"},
            {"VERSIONED_OR_META_CLASS|META-INF/versions/9/net/neristal/rrbridge/Receiver.class","RLDI2_POLICY_FIRST_PARTY_OR_LEGACY"},
            {"VERSIONED_OR_META_CLASS|META-INF/evil/X.class","RLDI2_POLICY_VERSIONED_SUBJECT"},
            {"VERSIONED_OR_META_CLASS|*","RLDI2_POLICY_VERSIONED_SUBJECT"},
            {"UNAPPROVED_MAIN_METHOD|*","RLDI2_POLICY_SUBJECT"},
            {"UNAPPROVED_MAIN_METHOD","RLDI2_POLICY_EXCEPTION_FORMAT"}};
        for(String[] b:bad){String got;try{DistributionInventoryV2.validate(Arrays.asList(new DistributionInventoryV2.Pin("x-1","x-1.jar",goodSha,10,1,"Bundle-Version","1",b[0])));got="ACCEPTED";}catch(IllegalStateException e){got=e.getMessage();}
            check(b[1].equals(got),"forged pin exception '"+b[0]+"' rejected by policy validation ("+got+")");}
        {String got;try{DistributionInventoryV2.validate(Arrays.asList(new DistributionInventoryV2.Pin("x-1","x-1.jar",goodSha,10,1,"Bundle-Version","1")));got="ACCEPTED";}catch(IllegalStateException e){got=e.getMessage();}check("RLDI2_POLICY_NO_EXCEPTION".equals(got),"pin without exceptions rejected (pins exist only to name reviewed findings)");}
        {String got;DistributionInventoryV2.Pin p=new DistributionInventoryV2.Pin("x-1","x-1.jar",goodSha,10,1,"Bundle-Version","1","UNAPPROVED_MAIN_METHOD|a.B");
         try{DistributionInventoryV2.validate(Arrays.asList(p,new DistributionInventoryV2.Pin("x-2","x-2.jar",goodSha,10,1,"Bundle-Version","1","UNAPPROVED_MAIN_METHOD|a.B")));got="ACCEPTED";}catch(IllegalStateException e){got=e.getMessage();}
         check("RLDI2_POLICY_SHA".equals(got),"two pins for one SHA-256 rejected");}

        // ================= 1-2. RLDI1 unchanged, RLDI2 MATCHED =================
        {Cand t=new Cand().pin();t.pin1();Map<String,String> before=snapshot(t.home);
         DistributionInventory.Report r1=t.v1();
         check("MISMATCH".equals(r1.inventory)&&r1.findings.containsKey("UNAPPROVED_MAIN_METHOD")&&r1.findings.containsKey("VERSIONED_OR_META_CLASS"),"RLDI1 unchanged: canonical candidate still MISMATCH "+r1.findings);
         DistributionInventoryV2.Report r=t.v2();
         Map<String,Integer> exp=new TreeMap<String,Integer>();exp.put("UNAPPROVED_MAIN_METHOD",jna?2:1);if(jna)exp.put("UNAPPROVED_MANIFEST_MAIN",1);exp.put("VERSIONED_OR_META_CLASS",6);
         check(r.matched()&&r.findings.isEmpty()&&r.unapprovedMains.isEmpty()&&r.pinnedDependencies==deps.size()&&r.exceptionsApplied.equals(exp),
             "RLDI2: canonical candidate ("+deps.size()+" pinned dependencies) MATCHED, exceptions applied exactly "+exp+" "+r.json());
         safe(r,"canonical");
         check(r.json().contains("\"reason\":\"INVENTORY_MATCHED_NOT_AUTHORIZATION\""),"MATCHED reason says it is not an authorization");
         check(before.equals(snapshot(t.home)),"RLDI1 + RLDI2 inventories wrote, changed or created nothing (install and record bytes/mtimes identical)");
         System.out.println("RLDI2 JSON "+r.json());
         // contract separation
         DistributionInventory.Report cross1=DistributionInventory.inspect(true,t.root.toFile(),t.record);
         check("UNAVAILABLE".equals(cross1.inventory)&&"INVENTORY_VERSION".equals(cross1.reason),"RLDI1 refuses an RLDI2 record (INVENTORY_VERSION)");
         t.pin1();unavailable(DistributionInventoryV2.inspect(true,t.root.toFile(),t.record1),"INVENTORY_VERSION","RLDI2 given an RLDI1 record");
         DistributionInventoryV2.Report off=DistributionInventoryV2.inspect(false,t.root.toFile(),t.record);
         check("NOT_RUN".equals(off.inventory)&&"INVENTORY_DISABLED".equals(off.reason),"disabled RLDI2 is NOT_RUN (default-off API)");}
        {Cand t=new Cand(Collections.<String[]>emptyList()).pin();DistributionInventoryV2.Report r=t.v2();
         check(r.matched()&&r.pinnedDependencies==0,"approved closure JAR alone: RLDI2 MATCHED (0 pins)");safe(r,"alone");}
        {Cand t=new Cand(without("jna-5.14.0.jar")).pin();DistributionInventoryV2.Report r=t.v2();
         check(r.matched()&&r.pinnedDependencies==3&&!r.exceptionsApplied.containsKey("UNAPPROVED_MANIFEST_MAIN"),"JNA absent (optional, reader not needed): sqlite/slf4j candidate MATCHED; RLDI2 never assumes JNA "+r.exceptionsApplied);}
        for(String[] d:deps){Cand t=new Cand(Collections.singletonList(d)).pin();DistributionInventoryV2.Report r=t.v2();
            check(r.matched()&&r.pinnedDependencies==1,"approved + "+d[1]+" alone: RLDI2 MATCHED (pin exactly accounts for its findings) "+r.exceptionsApplied);}

        // ================= 3. unknown / modified / other version =================
        {Cand t=new Cand();byte[] b=Files.readAllBytes(t.lib("sqlite-jdbc-3.46.1.3.jar"));b[b.length/2]^=1;Files.write(t.lib("sqlite-jdbc-3.46.1.3.jar"),b);t.pin();
         mismatch(t.v2(),"sqlite-jdbc with one byte flipped (CRC breaks)","RLDI2_DEPENDENCY_NOT_PINNED","JAR_UNREADABLE");}
        {Cand t=new Cand();Files.write(t.lib("sqlite-jdbc-3.46.1.3.jar"),repack(sqlite,null,null,null));t.pin();
         mismatch(t.v2(),"same name, valid repacked bytes (different SHA)","RLDI2_DEPENDENCY_NOT_PINNED","UNAPPROVED_MAIN_METHOD","VERSIONED_OR_META_CLASS");}
        {Cand t=new Cand(without("sqlite-jdbc-3.46.1.3.jar"));Map<String,String> mf=new HashMap<String,String>();mf.put("Bundle-Version","3.47.0.0");
         t.put("lib/sqlite-jdbc-3.47.0.0.jar",repack(sqlite,null,null,mf),"dependency_jar");t.pin();
         mismatch(t.v2(),"unknown sqlite-jdbc version","RLDI2_DEPENDENCY_NOT_PINNED");}
        {Cand t=new Cand();t.put("lib/extra-1.0.jar",jar(null,one("x/Res.txt",new byte[]{1})),"dependency_jar");t.pin();
         mismatch(t.v2(),"additional clean but unpinned dependency (stricter than RLDI1)","RLDI2_DEPENDENCY_NOT_PINNED");
         check(t.v1().findings.containsKey("UNAPPROVED_MAIN_METHOD")&&!t.v1().findings.containsKey("RLDI2_DEPENDENCY_NOT_PINNED"),"RLDI1 semantics unchanged (no RLDI2 codes)");}
        {Cand t=new Cand();t.put("lib/tools.jar",jar(null,Phase8InventoryTest.classes(DeploymentTransitionCli.class,IngressDiagnosticsCli.class)),"dependency_jar");t.pin();
         DistributionInventoryV2.Report r=t.v2();mismatch(r,"offline-tool-like JAR (secondary CLIs) as dependency","RLDI2_DEPENDENCY_NOT_PINNED","UNAPPROVED_MAIN_METHOD");
         check(r.unapprovedMains.contains(DeploymentTransitionCli.class.getName()),"tool mains are reported by class name");}
        {Cand t=new Cand();Files.copy(Paths.get(dep("slf4j-nop-2.0.16.jar")[0]),t.lib("slf4j-api-2.0.16.jar"),StandardCopyOption.REPLACE_EXISTING);t.pin();
         mismatch(t.v2(),"slf4j-api name holding slf4j-nop bytes (swapped dependency)","RLDI2_PIN_SHAPE_MISMATCH");}

        // ================= 4. role / record / policy / alias tampering =================
        if(jna){
            {Cand t=new Cand();t.roles.put("lib/jna-5.14.0.jar","approved_jar");t.roles.put("lib/romlogger-offline.jar","dependency_jar");t.pin();
             mismatch(t.v2(),"roles swapped: pinned JNA as approved_jar, entry closure as dependency_jar","RLDI2_PIN_NOT_APPLICABLE","UNAPPROVED_MANIFEST_MAIN","APPROVED_MAIN_MISSING","RLDI2_DEPENDENCY_NOT_PINNED");}
            {Cand t=new Cand();t.roles.put("lib/jna-5.14.0.jar","launcher");t.pin();
             mismatch(t.v2(),"pinned JNA bytes listed as launcher","ROLE_DOES_NOT_MATCH_FILE","RLDI2_PIN_NOT_APPLICABLE","UNAPPROVED_MAIN_METHOD");}
            {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put(t.key("lib/sqlite-jdbc-3.46.1.3.jar")+".pin","jna-5.14.0");t.rewrite(m);
             mismatch(t.v2(),"record names JNA's pin for sqlite-jdbc","RLDI2_PIN_MISMATCH","UNAPPROVED_MAIN_METHOD");}
            {Cand t=new Cand();Files.move(t.lib("jna-5.14.0.jar"),t.lib("jna.jar"));t.roles.remove("lib/jna-5.14.0.jar");t.roles.put("lib/jna.jar","dependency_jar");t.pin();
             mismatch(t.v2(),"pinned JNA under another file name","RLDI2_PIN_SHAPE_MISMATCH","UNAPPROVED_MANIFEST_MAIN");}
            {Cand t=new Cand();Path tmp=t.lib("jna.tmp");Files.move(t.lib("jna-5.14.0.jar"),tmp);Files.move(tmp,t.lib("JNA-5.14.0.jar"));// two steps: a case-only move is a no-op on NTFS
             t.roles.remove("lib/jna-5.14.0.jar");t.roles.put("lib/JNA-5.14.0.jar","dependency_jar");t.pin();
             mismatch(t.v2(),"pinned JNA really renamed to a case-variant file name","RLDI2_PIN_SHAPE_MISMATCH");}
            if(System.getProperty("os.name").startsWith("Windows")){
             // Case-insensitive alias: the record lists "JNA-5.14.0.jar" while the directory entry is "jna-5.14.0.jar" (same file
             // on NTFS). The walk sees the real name, so the listing is missing and the real file unlisted — no exception applies.
             Cand t=new Cand();t.roles.remove("lib/jna-5.14.0.jar");t.roles.put("lib/JNA-5.14.0.jar","dependency_jar");t.pin();
             mismatch(t.v2(),"record lists a case-variant alias of the pinned JNA (NTFS)","LISTED_FILE_MISSING","UNLISTED_EXECUTABLE_OR_ARCHIVE","UNAPPROVED_MANIFEST_MAIN");}
            {Cand t=new Cand();Files.createDirectories(t.root.resolve("lib/extra"));Files.copy(Paths.get(dep("jna-5.14.0.jar")[0]),t.root.resolve("lib/extra/jna-5.14.0.jar"));t.roles.put("lib/extra/jna-5.14.0.jar","dependency_jar");t.pin();
             mismatch(t.v2(),"second copy of pinned JNA at another path (alias)","RLDI2_PIN_REUSED","DUPLICATE_CLASS");}
        }else System.out.println("NOT_RUN JNA role/alias cases (no JNA JAR)");
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put(t.key("lib/romlogger-offline.jar")+".pin","sqlite-jdbc-3.46.1.3");t.rewrite(m);
         mismatch(t.v2(),"record names a pin for the approved JAR","RLDI2_PIN_NOT_APPLICABLE");}
        {Cand t=new Cand();t.roles.put("lib/sqlite-jdbc-3.46.1.3.jar","document");t.pin();
         mismatch(t.v2(),"pinned sqlite-jdbc listed as document","ROLE_DOES_NOT_MATCH_FILE","RLDI2_PIN_NOT_APPLICABLE");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put(t.key("lib/slf4j-api-2.0.16.jar")+".pin","-");t.rewrite(m);
         mismatch(t.v2(),"record drops the pin of a pinned dependency","RLDI2_PIN_MISMATCH","VERSIONED_OR_META_CLASS");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put(t.key("lib/sqlite-jdbc-3.46.1.3.jar")+".pin","sqlite-jdbc-3.47.0.0");t.rewrite(m);
         unavailable(t.v2(),"RLDI2_PIN_UNKNOWN","record names a pin id that is not compiled in");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put("policy_sha256",goodSha);t.rewrite(m);
         unavailable(t.v2(),"RLDI2_POLICY_MISMATCH","record carries another policy digest");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put("policy","RLDI2-DEPENDENCY-POLICY-2");t.rewrite(m);
         unavailable(t.v2(),"RLDI2_POLICY_MISMATCH","record names another policy id");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put("pin.extra","sqlite-jdbc-3.46.1.3|UNAPPROVED_MAIN_METHOD|evil.Main");t.rewrite(m);
         unavailable(t.v2(),"INVENTORY_FORMAT","record adds its own policy entry");}
        {Cand t=new Cand().pin();Map<String,String> m=t.rec();m.put("version","RLDI1");t.rewrite(m);
         unavailable(t.v2(),"INVENTORY_VERSION","record downgraded to RLDI1 version");}

        // ================= 5. injected mains / legacy entries into a third-party JAR =================
        {Cand t=new Cand();Map<String,byte[]> add=new LinkedHashMap<String,byte[]>();add.put("net/neristal/rrbridge/prototype/ingress/Rlc2IntegrationCli.class",cls(Rlc2IntegrationCli.class));
         add.put("org/sqlite/util/Evil.class",cls(DeploymentTransitionCli.class));
         Files.write(t.lib("sqlite-jdbc-3.46.1.3.jar"),repack(sqlite,add,null,null));t.pin();
         DistributionInventoryV2.Report r=t.v2();mismatch(r,"sqlite-jdbc with a legacy entry and an extra main injected","RLDI2_DEPENDENCY_NOT_PINNED","LEGACY_ENTRY_CLASS_PRESENT","UNAPPROVED_MAIN_METHOD");
         check(r.unapprovedMains.contains(Rlc2IntegrationCli.class.getName())&&r.unapprovedMains.contains("org.sqlite.util.OSInfo"),"injected and original mains all reported (no blanket main exception) "+r.unapprovedMains);}
        if(jna){Cand t=new Cand();Map<String,String> mf=new HashMap<String,String>();mf.put("Main-Class","net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationCli");
         Files.write(t.lib("jna-5.14.0.jar"),repack(dep("jna-5.14.0.jar")[0],null,null,mf));t.pin();
         mismatch(t.v2(),"JNA with Main-Class redirected to a legacy entry","RLDI2_DEPENDENCY_NOT_PINNED","UNAPPROVED_MANIFEST_MAIN");}
        {boolean none=true;for(DistributionInventoryV2.Pin p:DistributionInventoryV2.pins())for(String x:p.exceptions)for(String l:DistributionInventory.LEGACY_ENTRIES)if(x.endsWith("|"+l))none=false;
         check(none,"design: no pin can bless a legacy entry class (validated at class load), so a pinned JAR cannot carry one");}

        // ================= 6. archive-shape tricks (each as an extra dependency or a repack) =================
        {Cand t=light();t.put("lib/nest-1.0.jar",jar(null,one("inner/evil.jar",jar(null,one("a/A.class",cls(DeploymentTransitionCli.class))))),"dependency_jar");t.pin();
         mismatch(t.v2(),"nested archive","NESTED_ARCHIVE","RLDI2_DEPENDENCY_NOT_PINNED");}
        for(String attr:new String[]{"Class-Path","Launcher-Agent-Class","Premain-Class","Agent-Class","Boot-Class-Path"}){
         Cand t=light();Map<String,String> mf=new HashMap<String,String>();mf.put(attr,"evil.jar");Files.write(t.lib("slf4j-nop-2.0.16.jar"),repack(dep("slf4j-nop-2.0.16.jar")[0],null,null,mf));t.pin();
         mismatch(t.v2(),"slf4j-nop with manifest "+attr,"JAR_MANIFEST_CLASSPATH_OR_AGENT","RLDI2_DEPENDENCY_NOT_PINNED");}
        {Cand t=new Cand();t.put("lib/dup-1.0.jar",jar(null,one("org/sqlite/util/OSInfo.class",entryOf(sqlite,"org/sqlite/util/OSInfo.class"))),"dependency_jar");t.pin();
         mismatch(t.v2(),"duplicate of a pinned JAR's class in another JAR","DUPLICATE_CLASS","RLDI2_DEPENDENCY_NOT_PINNED");}
        {Cand t=new Cand();t.put("lib/shadow-1.0.jar",jar(null,one("META-INF/versions/9/org/sqlite/JDBC.class",entryOf(sqlite,"org/sqlite/JDBC.class"))),"dependency_jar");t.pin();
         DistributionInventoryV2.Report r=t.v2();mismatch(r,"multi-release class in another JAR shadowing org.sqlite.JDBC","DUPLICATE_CLASS","VERSIONED_OR_META_CLASS");
         Cand u=new Cand();u.put("lib/shadow-1.0.jar",jar(null,one("META-INF/versions/9/org/sqlite/JDBC.class",entryOf(sqlite,"org/sqlite/JDBC.class"))),"dependency_jar");u.pin();
         check(!u.v1().findings.containsKey("DUPLICATE_CLASS"),"(RLDI1 does not see versioned shadowing as a duplicate; RLDI2 is stricter)");}
        {Cand t=light();Files.write(t.lib("slf4j-api-2.0.16.jar"),repack(dep("slf4j-api-2.0.16.jar")[0],one("META-INF/versions/11/org/slf4j/Evil.class",cls(DeploymentTransitionCli.class)),null,null));t.pin();
         DistributionInventoryV2.Report r=t.v2();mismatch(r,"unexpected multi-release class with a main added to slf4j-api","RLDI2_DEPENDENCY_NOT_PINNED","VERSIONED_OR_META_CLASS","UNAPPROVED_MAIN_METHOD");}
        {Cand t=light();t.put("lib/mr-1.0.jar",jar(null,one("META-INF/versions/9/net/neristal/rrbridge/Receiver.class",cls(DeploymentTransitionCli.class))),"dependency_jar");t.pin();
         mismatch(t.v2(),"legacy entry hidden as a versioned class","LEGACY_ENTRY_CLASS_PRESENT","UNAPPROVED_MAIN_METHOD");}
        {Cand t=light();Map<String,byte[]> add=new LinkedHashMap<String,byte[]>();add.put("meta-inf/MANIFEST.MF","Manifest-Version: 1.0\r\nMain-Class: net.neristal.rrbridge.Receiver\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
         Files.write(t.lib("slf4j-nop-2.0.16.jar"),repack(dep("slf4j-nop-2.0.16.jar")[0],add,null,null));t.pin();
         mismatch(t.v2(),"case-variant manifest decoy","JAR_MANIFEST_AMBIGUOUS","UNAPPROVED_MANIFEST_MAIN","RLDI2_DEPENDENCY_NOT_PINNED");}
        {Cand t=light();Map<String,byte[]> add=new LinkedHashMap<String,byte[]>();add.put("META-INF/EVIL.SF","Signature-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));add.put("META-INF/EVIL.RSA",new byte[]{0x30,0x00});
         Files.write(t.lib("slf4j-nop-2.0.16.jar"),repack(dep("slf4j-nop-2.0.16.jar")[0],add,null,null));t.pin();
         mismatch(t.v2(),"forged signature/metadata files added","RLDI2_DEPENDENCY_NOT_PINNED");}

        // ================= 7. malformed entries and bombs (Phase X-A/X-B checks stay active) =================
        byte[] text="synthetic resource\n".getBytes(StandardCharsets.US_ASCII);byte[] bin=new byte[70000];new Random(11).nextBytes(bin);bin[0]='M';bin[1]='Z';
        byte[] classBytes=cls(DistributionInventory.class);
        String[][] kinds={{"res/a.txt","resource"},{"native/win32-x86/lib.dll","DLL"},{"native/linux-x86-64/lib.so","shared object"},{"META-INF/MANIFEST.MF","manifest"},{"a/B.class","class"}};
        for(String[] k:kinds){
            byte[] raw=k[0].endsWith(".MF")?"Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII):k[0].endsWith(".class")?classBytes:k[0].endsWith(".txt")?text:bin;
            {E e=new E(k[0],8,raw);e.crc^=1;e.lcrc=e.crc;Cand t=light();t.put("lib/bad-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();
             mismatch(t.v2(),k[1]+" entry with CRC mismatch","JAR_UNREADABLE","RLDI2_DEPENDENCY_NOT_PINNED");}
        }
        {byte[] more=new byte[text.length+40];System.arraycopy(text,0,more,0,text.length);E e=new E("res/a.txt",8,more);e.us=e.lus=text.length;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(text);
         Cand t=light();t.put("lib/bad-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();mismatch(t.v2(),"DEFLATE longer than declared","JAR_UNREADABLE");}
        {E e=new E("res/a.txt",8,text);byte[] pad=Arrays.copyOf(e.data,e.data.length+3);e.data=pad;e.cs=e.lcs=pad.length;
         Cand t=light();t.put("lib/bad-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();mismatch(t.v2(),"DEFLATE with trailing garbage","JAR_UNREADABLE");}
        {E e=new E("res/a.txt",8,text);e.data=PhaseXaZipAuditTest.syncFlushOnly(text);e.cs=e.lcs=e.data.length;
         Cand t=light();t.put("lib/bad-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();mismatch(t.v2(),"DEFLATE stream ending early (unterminated)","JAR_UNREADABLE");}
        {E e=new E("res/a.txt",8,text);e.us=e.lus=text.length+5;
         Cand t=light();t.put("lib/bad-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();mismatch(t.v2(),"entry declared longer than its stream","JAR_UNREADABLE");}
        {byte[] zeros=new byte[(int)(DistributionInventory.MAX_ENTRY_EXPANDED+1)];E e=new E("bomb/one.bin",8,zeros);
         Cand t=light();t.put("lib/bomb-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();unavailable(t.v2(),"JAR_ENTRY_EXPANSION_BOUND","entry above the 16 MiB bound (zip bomb)");}
        {byte[] fifteen=new byte[15*1024*1024];byte[] comp=PhaseXaZipAuditTest.deflate(fifteen);List<E> many=new ArrayList<E>();int n=(int)(DistributionInventory.MAX_JAR_EXPANDED/fifteen.length)+1;
         for(int i=0;i<n;i++){E e=new E("bomb/p"+i+".bin",0,new byte[0]);e.method=e.lmethod=8;e.data=comp;e.cs=e.lcs=comp.length;e.us=e.lus=fifteen.length;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(fifteen);many.add(e);}
         Cand t=light();t.put("lib/bomb-1.0.jar",PhaseXaZipAuditTest.build(many).bytes,"dependency_jar");t.pin();unavailable(t.v2(),"JAR_TOTAL_EXPANSION_BOUND","JAR above the 128 MiB total expansion bound");}
        {byte[] ten=new byte[10*1024*1024];E e=new E("bomb/liar.bin",8,ten);e.us=e.lus=1024;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(new byte[1024]);
         Cand t=light();t.put("lib/bomb-1.0.jar",PhaseXaZipAuditTest.build(e).bytes,"dependency_jar");t.pin();mismatch(t.v2(),"lying bomb (10 MiB stream declared 1 KiB)","JAR_UNREADABLE");}

        // ================= 8. races (Phase IX-4 guarantees kept) =================
        for(String stage:new String[]{"HASHED","SCANNED"}){
            String rel="lib/sqlite-jdbc-3.46.1.3.jar";
            {Cand t=new Cand().pin();Path f=t.root.resolve(rel);byte[] before=Files.readAllBytes(f);FileTime m=Files.getLastModifiedTime(f);boolean[] done={false};
             DistributionInventoryV2.Report r=during(t,stage,rel,new Change(){public void apply(Path p)throws Exception{Phase9InventoryRaceTest.SAME_SIZE.apply(p);}},done);
             check(done[0]&&!Arrays.equals(before,Files.readAllBytes(f))&&Files.size(f)==before.length&&Files.getLastModifiedTime(f).equals(m),"fixture: same-size/same-mtime change of pinned sqlite-jdbc at "+stage);
             unavailable(r,"INPUT_CHANGED_DURING_INVENTORY","same-size/same-mtime byte change of a pinned dependency at "+stage);}
            {Cand t=new Cand().pin();Path f=t.root.resolve(rel);String id=DeploymentBinding.fileIdentity(f.toFile());boolean[] done={false};
             DistributionInventoryV2.Report r=during(t,stage,rel,new Change(){public void apply(Path p)throws Exception{Phase9InventoryRaceTest.REPLACE.apply(p);}},done);
             check(done[0]&&!id.equals(DeploymentBinding.fileIdentity(f.toFile())),"fixture: byte-identical physical replacement at "+stage);
             unavailable(r,"INPUT_CHANGED_DURING_INVENTORY","physical replacement of a pinned dependency at "+stage);}
        }
        {final Cand t=new Cand().pin();final Path f=t.lib("slf4j-api-2.0.16.jar");final byte[] original=Files.readAllBytes(f);final FileTime m=Files.getLastModifiedTime(f);
         final byte[] other=repack(dep("slf4j-api-2.0.16.jar")[0],one("org/slf4j/Evil.class",cls(DeploymentTransitionCli.class)),null,null);final boolean[] sw={false,false};
         DistributionInventoryV2.Report r=t.v2(new DistributionInventory.Hook(){public void at(String s)throws Exception{
             if(s.equals("HASHED:lib/slf4j-api-2.0.16.jar")){Files.write(f,other);sw[0]=true;}
             if(s.equals("SCANNED")&&sw[0]){Files.write(f,original);Files.setLastModifiedTime(f,m);sw[1]=true;}}});
         check(sw[0]&&sw[1]&&Arrays.equals(original,Files.readAllBytes(f)),"A-B-A fixture swapped a different JAR in after hashing and restored the original");
         check(r.matched()&&r.unapprovedMains.isEmpty(),"A-B-A: pin evaluated on the hashed bytes only (scanned = hashed), injected main never seen ["+r.inventory+" "+r.reason+"]");}
        {final Cand t=new Cand().pin();final boolean[] done={false};
         DistributionInventoryV2.Report r=t.v2(new DistributionInventory.Hook(){public void at(String s)throws Exception{
             if(s.equals("SCANNED")&&!done[0]){done[0]=true;Map<String,String> m=t.rec();m.put(t.key("lib/slf4j-api-2.0.16.jar")+".pin","-");t.rewrite(m);}}});
         unavailable(r,"INPUT_CHANGED_DURING_INVENTORY","record content changed during inventory");}
        {final Cand t=new Cand().pin();final boolean[] done={false};
         DistributionInventoryV2.Report r=t.v2(new DistributionInventory.Hook(){public void at(String s)throws Exception{
             if(s.equals("SCANNED")&&!done[0]){done[0]=true;Files.write(t.root.resolve("lib/late-1.0.jar"),new byte[]{1});}}});
         unavailable(r,"INPUT_CHANGED_DURING_INVENTORY","file added during inventory");}

        // ================= 9. missing / unreadable / optional =================
        {Cand t=new Cand().pin();Files.delete(t.lib("slf4j-nop-2.0.16.jar"));mismatch(t.v2(),"listed pinned dependency missing","LISTED_FILE_MISSING");}
        {Cand t=new Cand();Files.delete(t.lib("slf4j-nop-2.0.16.jar"));Files.createDirectory(t.lib("slf4j-nop-2.0.16.jar"));t.roles.remove("lib/slf4j-nop-2.0.16.jar");t.pin();
         Map<String,String> m=t.rec();// re-add the listing by hand: a directory where a JAR is listed
         int n=Integer.parseInt(m.get("files"));String k=String.format(Locale.ROOT,"file.%04d",n);m.put("files",String.valueOf(n+1));m.put(k+".path","lib/slf4j-nop-2.0.16.jar");m.put(k+".role","dependency_jar");
         m.put(k+".sha256",DEPS[2][2]);m.put(k+".pin","slf4j-nop-2.0.16");t.rewrite(m);
         mismatch(t.v2(),"directory where a pinned JAR is listed (unreadable as a file)","LISTED_FILE_MISSING");}
        System.out.println("NOT_RUN permission-denied JAR fixture: the owned fixture cannot remove its own read access portably; RLDI2 keeps RLDI1's visitFileFailed -> DISTRIBUTION_UNREADABLE and read-failure -> INVENTORY_UNAVAILABLE handling (same code, duplicated), not exercised here");

        // ================= 11. RLDI1/RLDI2 agree on everything outside the dependency policy =================
        {String[] variants={"clean","unlisted file","secondary main in approved JAR","legacy entry in approved JAR","profile changed after pinning"};
         for(String v:variants){
            Cand t=new Cand(Collections.<String[]>emptyList());Files.write(t.root.resolve("profile.rlop1"),"synthetic\n".getBytes(StandardCharsets.US_ASCII));t.roles.put("profile.rlop1","profile");
            if(v.startsWith("secondary")||v.startsWith("legacy")){Files.delete(t.lib("romlogger-offline.jar"));
                Map<String,byte[]> c=new LinkedHashMap<String,byte[]>();try(JarFile j=new JarFile(closure)){for(Enumeration<JarEntry> en=j.entries();en.hasMoreElements();){JarEntry e=en.nextElement();if(!e.isDirectory()&&!e.getName().startsWith("META-INF/"))c.put(e.getName(),entryOf(closure.getPath(),e.getName()));}}
                if(v.startsWith("secondary"))c.put("net/neristal/rrbridge/prototype/ingress/DeploymentTransitionCli.class",cls(DeploymentTransitionCli.class));
                else c.put("net/neristal/rrbridge/Receiver.class",cls(DeploymentTransitionCli.class));
                Files.write(t.lib("romlogger-offline.jar"),jar(null,c));}
            t.pin();
            if(v.equals("unlisted file"))Files.write(t.root.resolve("notes.txt"),new byte[]{1});
            if(v.startsWith("profile"))Files.write(t.root.resolve("profile.rlop1"),"changed!!\n".getBytes(StandardCharsets.US_ASCII));
            DistributionInventory.Report r1=t.v1();DistributionInventoryV2.Report r2=t.v2();
            check(r1.inventory.equals(r2.inventory)&&r1.findings.equals(r2.findings)&&r1.unapprovedMains.equals(r2.unapprovedMains)&&r1.reason.equals(r2.reason),
                "RLDI1 and RLDI2 agree without dependencies ("+v+"): "+r1.inventory+" "+r1.findings);
            safe(r2,v);}}

        removeOwnFixtures();System.out.println("removed "+HOMES.size()+" fixture directories created by this run");
        System.out.println("Phase X-C RLDI2 candidate test passed "+checks+" checks; RLDI1 unchanged; state BLOCKED; launch/production/ACK disabled; global old-binary exclusion NOT_PROVEN");
    }
    private PhaseXcRldi2Test(){}
}
