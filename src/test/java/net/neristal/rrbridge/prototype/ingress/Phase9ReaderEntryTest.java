package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Result;
import static net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.*;

/**
 * Phase IX-3: RLWR1 behind the single entry, synthetic-owned profiles only (reader value WINDOWS_NATIVE_V1_SYNTHETIC).
 * The pinned classpath is the approved entry JAR + pinned dependencies (incl. JNA 5.14.0) <b>without</b> the test JAR.
 * Real supervisor/child JVMs; small multi-session subset with restart 0 duplicates, cross-session/tamper refusals,
 * RLDI1 install inventory, production-live refusal. Everything stays BLOCKED / ACK-ineligible. Args: packaged JARs.
 */
public final class Phase9ReaderEntryTest {
    static final String V1=OfflineCandidateEntry.READER_WINDOWS_V1_SYNTHETIC;
    static void rlwr1(Result r,String what){
        check(r.json.contains("\"reader\":\""+V1+"\"")&&r.json.contains("\"reader_adoption\":\"BLOCKED\"")&&r.json.contains("\"share_delete_decision\":\"OWNER_DECISION_REQUIRED\"")
            &&r.json.contains("\"distribution_license\":\"OWNER_DECISION_REQUIRED\"")&&r.json.contains("\"ack_eligible\":false"),what+": RLWR1 labelled, adoption BLOCKED, owner decisions open");
    }
    /** Runs the forged deployment with ITS OWN profile path (not the base profile). */
    static Result fx(Deployment forged,String mode)throws Exception {return process(forged,forged.args(mode));}
    public static void main(String[] args)throws Exception {
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        List<File> packaged=new ArrayList<File>(),product=new ArrayList<File>();File testJar=null,jna=null;
        for(String a:args){File f=new File(a).getCanonicalFile();packaged.add(f);
            if(f.getName().equals("romlogger-phase7-test.jar"))testJar=f;else product.add(f);if(f.getName().startsWith("jna-"))jna=f;}
        check(testJar!=null&&!product.contains(testJar),"RLWR1 classpath excludes the test JAR");
        if(!System.getProperty("os.name").startsWith("Windows")){
            Deployment d=new Deployment(new Phase7Fixture("synthetic-phase9-posix",true,0),product,true,0,V1);
            refused("READER_PLATFORM_UNSUPPORTED",5,process(d,d.args("--diagnose")),d,"RLWR1 profile on a non-Windows host");
            System.out.println("NOT_RUN RLWR1 entry runs (Windows only)");System.out.println("Phase IX reader entry test passed "+checks+" checks");return;
        }
        check(jna!=null,"pinned JNA JAR on the product classpath");
        try(JarFile j=new JarFile(product.get(0))){
            check(j.getEntry("net/neristal/rrbridge/prototype/ingress/WindowsProductReader.class")!=null&&j.getEntry("net/neristal/rrbridge/prototype/ingress/WindowsProductReaderJna.class")!=null
                &&j.getEntry("net/neristal/rrbridge/prototype/ingress/WindowsWatermarkV2Test.class")==null,"approved JAR carries RLWR1 + binding, no test reader");
        }

        // ---- 1. small multi-session subset through the entry with RLWR1; restart adds nothing.
        int n=Integer.parseInt(System.getenv().getOrDefault("ROMLOGGER_PHASE9_SESSIONS","3"));
        List<Deployment> all=new ArrayList<Deployment>();Map<String,String> outputs=new HashMap<String,String>();
        for(int i=0;i<n;i++){
            Deployment d=new Deployment(new Phase7Fixture(String.format(Locale.ROOT,"synthetic-phase9-r%02d",i),true,0),product,true,0,V1);all.add(d);
            Map<String,String> data=d.data();
            Result diag=process(d,d.args("--diagnose"));
            check(diag.exit==5&&diag.json.contains("\"state\":\"BLOCKED\"")&&diag.json.contains("\"reason\":\"WRITER_UNPROVEN\"")&&diag.json.contains("\"prerequisites_matched\":true"),"session "+i+": RLWR1 diagnose matches prerequisites, stays BLOCKED ["+diag.exit+" "+OfflineCandidateEntry.field(diag.json,"reason")+"]");
            rlwr1(diag,"session "+i+" diagnose");
            Result r=process(d,d.args("--synthetic-owned"));
            check(r.exit==0&&r.json.contains("\"records_added\":3")&&r.json.contains("\"receipts_added\":4"),"session "+i+": stored through the entry with RLWR1 child ["+r.exit+" "+OfflineCandidateEntry.field(r.json,"reason")+"]");
            rlwr1(r,"session "+i+" run");safe(r,d,"session "+i);
            check(data.equals(d.data()),"session "+i+": originals unchanged");
            outputs.put(d.f.payload,d.output());
        }
        for(Deployment d:all){
            Result again=process(d,d.args("--synthetic-owned"));
            check(again.exit==0&&again.json.contains("\"records_added\":0")&&again.json.contains("\"receipts_added\":0"),d.f.payload+": restart with RLWR1 adds nothing");
        }
        Deployment a=all.get(0),b=all.get(1);
        Result root=fx(forged(a,"owner_root",b.f.layout.root.getPath()),"--synthetic-owned");
        check(root.exit==5&&!root.json.contains("STORED_SYNTHETIC_OWNED")&&!root.json.contains("\"child_started\":true"),"other session's owner root refused before any child ["+OfflineCandidateEntry.field(root.json,"reason")+"]");safe(root,a,"forged owner root");
        Result cross=fx(forged(a,"layout.output",b.f.output.getPath()),"--synthetic-owned");
        check(cross.exit==5&&!cross.json.contains("STORED_SYNTHETIC_OWNED")&&!cross.json.contains("\"child_started\":true"),"other session's output refused ["+cross.exit+" "+OfflineCandidateEntry.field(cross.json,"reason")+"]");
        for(Deployment d:all)check(outputs.get(d.f.payload).equals(d.output())||d==a,d.f.payload+": refused attempts changed nothing");

        // ---- 2. reader gate refusals (each through a real supervisor JVM).
        Deployment withTest=new Deployment(new Phase7Fixture("synthetic-phase9-testjar",true,0),packaged,true,0,V1);
        refused("READER_TEST_CODE_ON_CLASSPATH",5,process(withTest,withTest.args("--synthetic-owned")),withTest,"RLWR1 with the test JAR pinned refused");
        List<File> noJna=new ArrayList<File>();for(File f:product)if(!f.equals(jna))noJna.add(f);
        Deployment without=new Deployment(new Phase7Fixture("synthetic-phase9-nojna",true,0),noJna,true,0,V1);
        refused("READER_NATIVE_UNAVAILABLE",5,process(without,without.args("--diagnose")),without,"RLWR1 without JNA refused (no fallback)");
        Path tamperedDir=Files.createTempDirectory("romlogger-phase9-jna-");File tampered=tamperedDir.resolve("jna-5.14.0.jar").toFile();
        try(JarFile in=new JarFile(jna);JarOutputStream out=new JarOutputStream(new FileOutputStream(tampered),in.getManifest())){
            for(Enumeration<JarEntry> e=in.entries();e.hasMoreElements();){JarEntry x=e.nextElement();if(x.getName().equals("META-INF/MANIFEST.MF"))continue;
                out.putNextEntry(new JarEntry(x.getName()));try(InputStream s=in.getInputStream(x)){byte[] buf=new byte[8192];int k;while((k=s.read(buf))>0)out.write(buf,0,k);}}
            out.putNextEntry(new JarEntry("synthetic-tamper.txt"));out.write(1);
        }
        List<File> swapped=new ArrayList<File>();for(File f:product)swapped.add(f.equals(jna)?tampered.getCanonicalFile():f);
        Deployment unpinned=new Deployment(new Phase7Fixture("synthetic-phase9-unpinned",true,0),swapped,true,0,V1);
        refused("READER_DEPENDENCY_UNPINNED",5,process(unpinned,unpinned.args("--diagnose")),unpinned,"RLWR1 with a different JNA JAR (profile-pinned but not the 5.14.0 hash) refused");
        refused("READER_UNSUPPORTED",5,fx(forged(a,"reader","WINDOWS_NATIVE_V2"),"--diagnose"),a,"unknown reader value refused");
        refused("PROFILE_UNKNOWN_FIELD",5,fx(forged(a,"reader_class","x.Evil"),"--diagnose"),a,"no reader class field accepted");
        Result legacy=fx(forged(a,"reader",OfflineCandidateEntry.READER_WINDOWS_TEST),"--diagnose");
        check(legacy.exit==5&&!legacy.json.contains("\"prerequisites_matched\":true"),"test-only reader value without the test JAR cannot read ["+OfflineCandidateEntry.field(legacy.json,"reason")+"]");
        refused("PRODUCTION_LIVE_NOT_IMPLEMENTED",6,inProcess(new String[]{"--production-live"},OfflineCandidateEntry.RuntimeFacts.current()),null,"production live still refused");

        // ---- 3. RLDI1 install: approved JAR + pinned dependencies (JNA as dependency_jar) MATCHED; adding the test JAR MISMATCH.
        Path inst=Files.createTempDirectory("romlogger-phase9-install-");Map<String,String> roles=new TreeMap<String,String>();
        for(File f:product){Files.copy(f.toPath(),inst.resolve(f.getName()));roles.put(f.getName(),f.equals(product.get(0))?"approved_jar":"dependency_jar");}
        File rec=Files.createTempDirectory("romlogger-phase9-rldi-").resolve("install.rldi1").toFile();
        DeploymentBinding.createRecord(rec.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(inst.toFile(),OfflineCandidateEntry.MAIN,roles)));
        DistributionInventory.Report rep=DistributionInventory.inspect(true,inst.toFile(),rec);
        // Phase IX finding: the entry's REQUIRED third-party JARs carry mains (JNA Native, sqlite-jdbc OSInfo), a manifest
        // Main-Class and multi-release META-INF classes, so a real install cannot be RLDI1 MATCHED. Not relaxed here
        // (design decision, see phase9 report); the test pins the current fail-closed result exactly.
        check("MISMATCH".equals(rep.inventory)&&rep.unapprovedMains.equals(Arrays.asList("com.sun.jna.Native","org.sqlite.util.OSInfo"))
            &&rep.findings.containsKey("UNAPPROVED_MANIFEST_MAIN")&&rep.findings.containsKey("VERSIONED_OR_META_CLASS")&&rep.json().contains("\"state\":\"BLOCKED\""),
            "real install (approved JAR + sqlite-jdbc + slf4j + JNA) is RLDI1 MISMATCH only because of third-party dependency mains/META-INF (open design item) "+rep.findings+" "+rep.unapprovedMains);
        Map<String,String> own=new TreeMap<String,String>();own.put(product.get(0).getName(),"approved_jar");
        Path alone=Files.createTempDirectory("romlogger-phase9-install1-");Files.copy(product.get(0).toPath(),alone.resolve(product.get(0).getName()));
        File rec1=rec.toPath().resolveSibling("alone.rldi1").toFile();
        DeploymentBinding.createRecord(rec1.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(alone.toFile(),OfflineCandidateEntry.MAIN,own)));
        DistributionInventory.Report one=DistributionInventory.inspect(true,alone.toFile(),rec1);
        check(one.matched()&&one.unapprovedMains.isEmpty()&&one.json().contains("\"global_old_binary_exclusion\":\"NOT_PROVEN\""),"approved JAR with RLWR1 + binding alone is RLDI1 MATCHED (still BLOCKED) "+one.json());
        Files.copy(testJar.toPath(),inst.resolve(testJar.getName()));roles.put(testJar.getName(),"dependency_jar");
        File rec2=rec.toPath().resolveSibling("with-test.rldi1").toFile();
        DeploymentBinding.createRecord(rec2.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(inst.toFile(),OfflineCandidateEntry.MAIN,roles)));
        rep=DistributionInventory.inspect(true,inst.toFile(),rec2);
        check("MISMATCH".equals(rep.inventory)&&rep.unapprovedMains.size()>2,"test JAR in the install adds further RLDI1 MISMATCH mains ("+rep.unapprovedMains.size()+" unapproved mains)");
        System.out.println("Phase IX reader entry test passed "+checks+" checks; RLWR1 adoption BLOCKED; launch/production/ACK disabled");
    }
    private Phase9ReaderEntryTest(){}
}
