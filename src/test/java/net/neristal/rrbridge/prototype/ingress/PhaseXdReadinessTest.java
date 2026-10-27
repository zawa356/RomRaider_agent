package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.product.v1.DeploymentBindingTest;

/**
 * Phase X-D1: RLDR1 required-dependency readiness candidate. Owned synthetic installs built from the real entry closure JAR
 * and the real pinned dependency JARs, an RLDI2 record outside the install and a real RLOP1 profile from
 * {@link OfflineCandidateEntry#createProfile}. Positive shapes must be MATCHED_NOT_AUTHORIZATION (still BLOCKED); every
 * negative (missing/forbidden/partial/unused dependency, foreign or aliased class-path entry, order/duplicate/role
 * tampering, profile smuggling, wrong record version, TOCTOU) must fail closed with its stable code. Every report must be
 * strict JSON with the fixed key set, all authorization flags false and no path. Nothing is launched; nothing outside the
 * fixture homes created by this run is written.
 *
 * Args: the packaged entry closure JAR (absolute). With -Dphasexd.expectRed=true failures are counted (RED_COUNT) instead
 * of thrown.
 */
public final class PhaseXdReadinessTest {
    static int checks,red;
    static final boolean RED=Boolean.getBoolean("phasexd.expectRed");
    static void check(boolean ok,String m){if(!ok){if(RED){red++;System.out.println("RED "+m);return;}throw new AssertionError(m);}checks++;System.out.println("PASS "+m);}

    static final String SQLITE="sqlite-jdbc-3.46.1.3",API="slf4j-api-2.0.16",NOP="slf4j-nop-2.0.16",JNA="jna-5.14.0";
    static final Map<String,String[]> DEP=new LinkedHashMap<String,String[]>();
    static{for(String[] d:PhaseXcF1ReproTest.DEPS)DEP.put(d[1].substring(0,d[1].length()-4),d);}
    static File closure;static boolean jna;
    static final List<Path> HOMES=new ArrayList<Path>();
    static final String POSIX=OfflineCandidateEntry.READER_POSIX,V1=OfflineCandidateEntry.READER_WINDOWS_V1_SYNTHETIC,TEST=OfflineCandidateEntry.READER_WINDOWS_TEST;

    /** One owned install (lib/romlogger-offline.jar + the given pins), its RLDI2 record and an RLOP1 profile. */
    static final class Cand {
        final Path home,root,lib;final File record,profile;final Map<String,String> roles=new TreeMap<String,String>();final List<File> classpath=new ArrayList<File>();
        Cand(String reader,List<String> install,List<String> onClasspath)throws Exception {
            home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phasexd-"));HOMES.add(home);
            root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("install")));
            lib=DeploymentBindingTest.privateDir(Files.createDirectory(root.resolve("lib")));
            Files.copy(closure.toPath(),lib.resolve("romlogger-offline.jar"));roles.put("lib/romlogger-offline.jar","approved_jar");
            for(String pin:install){Files.copy(Paths.get(DEP.get(pin)[0]),lib.resolve(DEP.get(pin)[1]));roles.put("lib/"+DEP.get(pin)[1],"dependency_jar");}
            record=home.resolve("install.rldi2").toFile();describe();
            classpath.add(lib.resolve("romlogger-offline.jar").toFile().getCanonicalFile());
            for(String pin:onClasspath)classpath.add(jar(pin).getCanonicalFile());
            Path data=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("data"))),work=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("work")));
            Files.write(work.resolve(".romlogger-fixture-keep"),"synthetic fixture working directory".getBytes(StandardCharsets.US_ASCII));
            File d=data.toFile();
            DeploymentBinding.Layout l=new DeploymentBinding.Layout(new File(d,"root"),new File(d,"v1.db"),new File(d,"rli2.db"),new File(d,"session"),new File(d,"session/manifest"),
                new File(d,"ledger.db"),"SYNTHETIC-DEVICE","SYNTHETIC-VEHICLE","synthetic-payload","synthetic-manifest");
            profile=home.resolve("offline-candidate.rlop1").toFile();
            OfflineCandidateEntry.createProfile(profile,l,d,work.toFile(),classpath,reader,0);
        }
        File jar(String pin){return lib.resolve(DEP.get(pin)[1]).toFile();}
        void describe()throws Exception {Files.deleteIfExists(record.toPath());DeploymentBinding.createRecord(record.toPath(),DeploymentBinding.encodeRecord(DistributionInventoryV2.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));}
        Map<String,String> prof()throws Exception {return new TreeMap<String,String>(DeploymentBinding.readRecord(profile.toPath()));}
        void rewriteProfile(Map<String,String> m)throws Exception {Files.delete(profile.toPath());DeploymentBinding.createRecord(profile.toPath(),DeploymentBinding.encodeRecord(m));}
        /** Same profile, class-path string replaced (artifact description left as pinned). */
        void classpath(List<String> elements)throws Exception {Map<String,String> m=prof();m.put("classpath",join(elements));rewriteProfile(m);}
        List<String> cpElements()throws Exception {return new ArrayList<String>(Arrays.asList(prof().get("classpath").split(java.util.regex.Pattern.quote(File.pathSeparator))));}
        DependencyReadiness.Report run(){return DependencyReadiness.inspect(true,profile,root.toFile(),record);}
        DependencyReadiness.Report run(DistributionInventory.Hook h){return DependencyReadiness.inspect(true,profile,root.toFile(),record,h);}
    }
    static String join(List<String> parts){StringBuilder b=new StringBuilder();for(String p:parts){if(b.length()>0)b.append(File.pathSeparator);b.append(p);}return b.toString();}
    static List<String> L(String... s){return Arrays.asList(s);}

    static final Set<String> KEYS=new TreeSet<String>(Arrays.asList("contract_version","readiness_version","policy","state","dependency_readiness","reason","reader",
        "axes","required_pins","observed_pins","findings","reader_adoption","global_old_binary_exclusion","formal_ack","synthetic_only","launch_authorized",
        "production_authorized","ack_eligible"));
    /** Every report: strict JSON, exact keys/axes, gates closed, BLOCKED, no fixture path. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> safe(DependencyReadiness.Report r,String what){
        String j=r.json();Map<String,Object> o;
        try{o=(Map<String,Object>)PhaseXcJsonEscapeTest.Strict.parse(j);}catch(RuntimeException bad){check(false,what+": strict JSON ("+bad.getMessage()+")");return new HashMap<String,Object>();}
        check(o.keySet().equals(KEYS),what+": exact key set "+o.keySet());
        check(Long.valueOf(1).equals(o.get("contract_version"))&&"RLDR1".equals(o.get("readiness_version"))&&"RLDR1-REQUIRED-SET-POLICY-1".equals(o.get("policy")),what+": versions");
        check("BLOCKED".equals(o.get("state"))&&Boolean.FALSE.equals(o.get("launch_authorized"))&&Boolean.FALSE.equals(o.get("production_authorized"))&&Boolean.FALSE.equals(o.get("ack_eligible"))
            &&Boolean.TRUE.equals(o.get("synthetic_only"))&&"DISABLED".equals(o.get("formal_ack"))&&"BLOCKED".equals(o.get("reader_adoption"))&&"NOT_PROVEN".equals(o.get("global_old_binary_exclusion")),what+": gates closed");
        Object axes=o.get("axes");
        check(axes instanceof Map&&((Map<String,Object>)axes).keySet().equals(new LinkedHashSet<String>(DependencyReadiness.AXES)),what+": exact axes");
        if(axes instanceof Map)for(Object a:((Map<String,Object>)axes).values())check(a instanceof Map&&((Map<String,Object>)a).keySet().equals(new TreeSet<String>(L("code","status"))),what+": axis shape");
        Object pp=axes instanceof Map?((Map<String,Object>)axes).get("process_provenance"):null,wr=axes instanceof Map?((Map<String,Object>)axes).get("writer"):null;
        check(pp instanceof Map&&wr instanceof Map&&"UNKNOWN".equals(((Map<String,Object>)pp).get("status"))&&"UNKNOWN".equals(((Map<String,Object>)wr).get("status"))
            &&"WRITER_UNPROVEN".equals(((Map<String,Object>)wr).get("code")),what+": process provenance and writer stay UNKNOWN");
        for(Path h:HOMES)check(!j.contains(h.getFileName().toString()),what+": no fixture path in JSON");
        check(j.matches("[\\x20-\\x7e]*"),what+": printable ASCII only");
        check(r.matched()==("MATCHED_NOT_AUTHORIZATION".equals(o.get("dependency_readiness"))),what+": matched() agrees with JSON");
        return o;
    }
    static String code(DependencyReadiness.Report r,String axis){DependencyReadiness.Axis a=r.axis(axis);return a==null?"(none)":a.status+"/"+a.code;}
    static void expect(DependencyReadiness.Report r,String axis,String status,String code,String what){
        safe(r,what);check(!r.matched(),what+": not MATCHED");check((status+"/"+code).equals(code(r,axis)),what+": "+axis+" = "+status+"/"+code+" (got "+code(r,axis)+", reason "+r.reason+")");
    }
    static void matched(DependencyReadiness.Report r,String what){
        safe(r,what);check(r.matched()&&"DEPENDENCY_READINESS_MATCHED_NOT_AUTHORIZATION".equals(r.reason),what+": MATCHED_NOT_AUTHORIZATION (got "+r.readiness+" "+r.reason+" "+r.findings+")");
        for(String a:L("profile_policy","inventory","classpath","required_set","freshness"))check("MATCHED".equals(r.axis(a).status),what+": axis "+a+" MATCHED ("+code(r,a)+")");
    }
    interface Step {void at()throws Exception;}
    static DistributionInventory.Hook at(final String stage,final Step step){
        return new DistributionInventory.Hook(){boolean done;public void at(String s)throws Exception {if(!done&&stage.equals(s)){done=true;step.at();}}};
    }
    static void flipLastByte(File f)throws Exception {byte[] b=Files.readAllBytes(f.toPath());b[b.length-1]^=0x55;Files.write(f.toPath(),b);}
    /** Replaces f by a different file with the same bytes (the copy exists before the swap, so its identity differs). */
    static void sameBytesNewFile(File f)throws Exception {
        Path copy=f.toPath().resolveSibling(f.getName()+".swap");Files.copy(f.toPath(),copy,StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(copy,f.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    /** RLOP1's own static refusal for the profile (its Profile parsing, generation range and production-path rule). */
    static boolean rlop1Refuses(Cand c)throws Exception {
        try{OfflineCandidateEntry.Profile p=new OfflineCandidateEntry.Profile(c.prof(),"-");if(p.expectedGeneration<0)return true;OfflineCandidateEntry.requireNotProduction(p);return false;}
        catch(OfflineCandidateEntry.Refusal r){return true;}
    }

    public static void main(String[] a)throws Exception {
        // Evidence: which runtime produced this log (x64 and actual x86 logs must not be confusable).
        System.out.println("RUNTIME java.version="+System.getProperty("java.version")+" vendor="+System.getProperty("java.vendor")+" data_model="+System.getProperty("sun.arch.data.model")+" os="+System.getProperty("os.name"));
        closure=new File(a[0]);check(closure.isFile(),"entry closure JAR present");
        for(Map.Entry<String,String[]> e:DEP.entrySet()){
            if(!new File(e.getValue()[0]).isFile()){System.out.println("NOT_RUN "+e.getKey()+" absent on this runner (JNA is fetched on Windows jobs only)");continue;}
            check(e.getValue()[2].equals(DeploymentTransition.fileSha(new File(e.getValue()[0]))),"real "+e.getKey()+" matches its repository pin");
        }
        jna=PhaseXcF1ReproTest.jnaPresent();

        // ---------------------------------------------------------------- disabled / missing inputs
        {DependencyReadiness.Report r=DependencyReadiness.inspect(false,null,null,null);safe(r,"disabled");check("NOT_RUN".equals(r.readiness)&&"READINESS_DISABLED".equals(r.reason),"disabled: NOT_RUN, nothing read");}
        {DependencyReadiness.Report r=DependencyReadiness.inspect(true,new File("relative.rlop1"),new File("install"),new File("x.rldi2"));
         expect(r,"profile_policy","UNKNOWN","INPUT_PATH_NOT_ABSOLUTE","relative inputs");}

        // ---------------------------------------------------------------- positive shapes
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));DependencyReadiness.Report r=c.run();matched(r,"POSIX + sqlite + slf4j pair");
         check(r.requiredPins.equals(L(SQLITE))&&r.observedPins.equals(L(API,NOP,SQLITE)),"POSIX: required = sqlite only; observed pins listed "+r.observedPins);
         check(POSIX.equals(r.reader),"POSIX: reader reported");
         check(DistributionInventoryV2.inspect(true,c.root.toFile(),c.record).matched(),"RLDI2 alone still MATCHED on the same install (unchanged)");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));matched(c.run(),"POSIX + sqlite, optional SLF4J group absent");}
        if(jna){Cand c=new Cand(V1,L(SQLITE,API,NOP,JNA),L(SQLITE,API,NOP,JNA));DependencyReadiness.Report r=c.run();matched(r,"V1 synthetic + sqlite + slf4j + JNA");
            check(r.requiredPins.equals(L(JNA,SQLITE)),"V1: required = JNA + sqlite "+r.requiredPins);}
        else System.out.println("NOT_RUN V1 synthetic positive (JNA JAR absent)");

        // ---------------------------------------------------------------- required set
        {Cand c=new Cand(POSIX,L(),L());expect(c.run(),"required_set","MISMATCH","REQUIRED_PIN_MISSING","POSIX without sqlite-jdbc");}
        {Cand c=new Cand(POSIX,L(SQLITE,API),L(SQLITE,API));expect(c.run(),"required_set","MISMATCH","OPTIONAL_GROUP_INCOMPLETE","slf4j-api without slf4j-nop");}
        {Cand c=new Cand(POSIX,L(SQLITE,NOP),L(SQLITE,NOP));expect(c.run(),"required_set","MISMATCH","OPTIONAL_GROUP_INCOMPLETE","slf4j-nop without slf4j-api");}
        if(jna){Cand c=new Cand(POSIX,L(SQLITE,JNA),L(SQLITE,JNA));expect(c.run(),"required_set","MISMATCH","FORBIDDEN_PIN_PRESENT","JNA on a POSIX profile");}
        else System.out.println("NOT_RUN JNA forbidden for POSIX (JNA JAR absent)");
        {Cand c=new Cand(V1,L(SQLITE,API,NOP),L(SQLITE,API,NOP));expect(c.run(),"required_set","MISMATCH","REQUIRED_PIN_MISSING","V1 synthetic without JNA");}

        // ---------------------------------------------------------------- profile policy
        {Cand c=new Cand(TEST,L(SQLITE,API,NOP),L(SQLITE,API,NOP));DependencyReadiness.Report r=c.run();expect(r,"profile_policy","BLOCKED","TEST_ONLY_READER_PROFILE","WINDOWS_NATIVE_TEST_ONLY profile");
         check(!"MATCHED".equals(r.axis("required_set").status),"test-only profile: required set never MATCHED");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("launch_authorized","true");c.rewriteProfile(m);
         expect(c.run(),"profile_policy","MISMATCH","PROFILE_UNKNOWN_FIELD","smuggled launch_authorized field");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("reader","WINDOWS_NATIVE_V2");c.rewriteProfile(m);
         DependencyReadiness.Report r=c.run();expect(r,"profile_policy","MISMATCH","READER_UNSUPPORTED","unknown reader value");check("UNKNOWN".equals(r.reader),"unknown reader printed as UNKNOWN");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("reader","POSIX\",\"launch_authorized\":true,\"x\":\"");c.rewriteProfile(m);
         DependencyReadiness.Report r=c.run();expect(r,"profile_policy","MISMATCH","READER_UNSUPPORTED","hostile reader value");check(!r.json().contains("\"x\""),"hostile reader value not echoed");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("version","RLOP2");c.rewriteProfile(m);expect(c.run(),"profile_policy","MISMATCH","PROFILE_VERSION","profile version");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("main_class","net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationCli");c.rewriteProfile(m);
         expect(c.run(),"profile_policy","MISMATCH","UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS","legacy main class");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.remove("classpath");c.rewriteProfile(m);expect(c.run(),"profile_policy","MISMATCH","PROFILE_FORMAT","classpath field missing");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Files.delete(c.profile.toPath());expect(c.run(),"profile_policy","UNKNOWN","PROFILE_UNREADABLE","profile missing");}
        // Review P2-1: the static RLOP1 rules RLDR1 must not be weaker than (expected_generation format/range, production paths).
        // Each bad profile is also shown to be refused by RLOP1's own code (Profile parsing / requireNotProduction).
        for(String gen:L("invalid","-1","","1.0","2147483648","0x10")){
            Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put("expected_generation",gen);c.rewriteProfile(m);
            expect(c.run(),"profile_policy","MISMATCH","PROFILE_FORMAT","expected_generation='"+gen+"'");
            check(rlop1Refuses(c),"expected_generation='"+gen+"': RLOP1 refuses it too");}
        for(String[] prod:new String[][]{{"data_root","OpenPortWork"},{"working_directory","Program Files"+File.separator+"RomRaider"},{"layout.ledger","rrbridge"+File.separator+"dist"+File.separator+"ledger.db"},{"layout.legacy","openportwork"+File.separator+"v1.db"}}){
            Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Map<String,String> m=c.prof();m.put(prod[0],c.home.resolve(prod[1]).toString());c.rewriteProfile(m);
            expect(c.run(),"profile_policy","MISMATCH","PRODUCTION_PATH_REFUSED","production-looking "+prod[0]);
            check(rlop1Refuses(c),"production-looking "+prod[0]+": RLOP1 refuses it too");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));check(!rlop1Refuses(c),"control: RLOP1 static rules accept the generated profile");}

        // ---------------------------------------------------------------- inventory (RLDI2, fresh)
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Files.write(c.lib.resolve("extra.cmd"),"rem synthetic".getBytes(StandardCharsets.US_ASCII));
         DependencyReadiness.Report r=c.run();expect(r,"inventory","MISMATCH","UNLISTED_EXECUTABLE_OR_ARCHIVE","unlisted executable in install");
         check("UNKNOWN".equals(r.axis("required_set").status)&&"INVENTORY_NOT_MATCHED".equals(r.axis("classpath").code),"inventory MISMATCH: required_set/classpath UNKNOWN (record not trusted)");}
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));flipLastByte(c.jar(API));c.describe();
         DependencyReadiness.Report r=c.run();safe(r,"modified dependency");
         check("MISMATCH".equals(r.axis("inventory").status)&&!r.matched()&&"INVENTORY_NOT_MATCHED".equals(r.axis("required_set").code),"modified dependency, same name/size, re-described record: inventory MISMATCH ("+code(r,"inventory")+")");
         check(DistributionInventoryV2.inspect(true,c.root.toFile(),c.record).findings.containsKey("RLDI2_DEPENDENCY_NOT_PINNED"),"modified dependency: RLDI2 finds RLDI2_DEPENDENCY_NOT_PINNED");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));File r1=c.home.resolve("install.rldi1").toFile();
         DeploymentBinding.createRecord(r1.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(c.root.toFile(),OfflineCandidateEntry.MAIN,c.roles)));
         expect(DependencyReadiness.inspect(true,c.profile,c.root.toFile(),r1),"inventory","UNKNOWN","INVENTORY_VERSION","RLDI1 record given to RLDR1");}

        // Permission-denied dependency (X-C NOT_RUN item): POSIX owner removes its own read access on an owned fixture JAR.
        // Not portable to Windows ACLs without changing an ACL (out of scope), and meaningless as root (root still reads).
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));Path jar=c.jar(API).toPath();
         if(Files.getFileAttributeView(jar,java.nio.file.attribute.PosixFileAttributeView.class)==null)System.out.println("NOT_RUN permission-denied dependency JAR (no POSIX permissions here; Windows ACLs are not changed)");
         else{Files.setPosixFilePermissions(jar,EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission.class));
             if(Files.isReadable(jar))System.out.println("NOT_RUN permission-denied dependency JAR (process can still read a mode-000 file: running as root)");
             else{DependencyReadiness.Report r=c.run();safe(r,"unreadable dependency JAR");
                 check("UNKNOWN".equals(r.axis("inventory").status)&&!r.matched()&&"INVENTORY_NOT_MATCHED".equals(r.axis("required_set").code),
                     "unreadable (mode 000) dependency JAR: inventory UNKNOWN, readiness not MATCHED ("+code(r,"inventory")+")");
                 check(!DistributionInventoryV2.inspect(true,c.root.toFile(),c.record).matched(),"unreadable dependency JAR: RLDI2 alone not MATCHED");}
             Files.setPosixFilePermissions(jar,EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));}}

        // ---------------------------------------------------------------- class path
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE));expect(c.run(),"classpath","MISMATCH","INVENTORY_DEPENDENCY_UNUSED","slf4j in install but not on the class path");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Path other=DeploymentBindingTest.privateDir(Files.createDirectory(c.home.resolve("other")));
         Path copy=other.resolve(c.jar(SQLITE).getName());Files.copy(c.jar(SQLITE).toPath(),copy);
         List<String> cp=c.cpElements();cp.set(1,copy.toFile().getCanonicalPath());c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_OUTSIDE_INSTALL","identical copy outside the install on the class path");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));List<String> cp=c.cpElements();
         cp.set(1,c.lib.toFile().getCanonicalPath()+File.separator+".."+File.separator+"lib"+File.separator+c.jar(SQLITE).getName());c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_PATH_NOT_CANONICAL","'..' alias of an install JAR");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Path alias=c.home.resolve("alias");boolean linked=false;
         try{Files.createSymbolicLink(alias,c.lib);linked=true;}catch(Exception noSymlink){
             if(System.getProperty("os.name").startsWith("Windows")){
                 Process p=new ProcessBuilder("cmd","/c","mklink","/J",alias.toString(),c.lib.toString()).redirectErrorStream(true).start();
                 p.getInputStream().close();linked=p.waitFor()==0&&Files.isDirectory(alias);}}
         if(!linked)System.out.println("NOT_RUN directory symlink/junction alias (cannot create link here)");
         else{List<String> cp=c.cpElements();cp.set(1,alias.toString()+File.separator+c.jar(SQLITE).getName());c.classpath(cp);
             expect(c.run(),"classpath","MISMATCH","CLASSPATH_PATH_NOT_CANONICAL","directory link/junction alias of the install lib");}}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));List<String> cp=c.cpElements();Collections.swap(cp,0,1);c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","APPROVED_JAR_NOT_FIRST","approved JAR not first");}
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));List<String> cp=c.cpElements();Collections.swap(cp,2,3);c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_ORDER_CHANGED","dependency order swapped against the pinned RLAP1 description");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));List<String> cp=c.cpElements();cp.add(cp.get(1));c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_DUPLICATE_ENTRY","duplicate class-path element");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Files.write(c.lib.resolve("notes.txt"),"synthetic".getBytes(StandardCharsets.US_ASCII));c.roles.put("lib/notes.txt","document");c.describe();
         List<String> cp=c.cpElements();cp.add(c.lib.resolve("notes.txt").toFile().getCanonicalPath());c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_ROLE_NOT_ALLOWED","document-role file on the class path");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));List<String> cp=c.cpElements();cp.add("");c.classpath(cp);
         expect(c.run(),"classpath","MISMATCH","CLASSPATH_EMPTY_ELEMENT","empty class-path element (implicit current directory)");}

        // ---------------------------------------------------------------- freshness / TOCTOU
        {final Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{flipLastByte(c.jar(NOP));}})),"freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","JAR bytes changed after hashing (same size)");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{byte[] b=Files.readAllBytes(c.jar(SQLITE).toPath());Files.delete(c.jar(SQLITE).toPath());Files.write(c.jar(SQLITE).toPath(),b);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","JAR replaced by an identical copy (identity changed)");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("INVENTORY_DONE",new Step(){public void at()throws Exception{c.roles.put("lib/late.txt","document");Files.write(c.lib.resolve("late.txt"),new byte[]{1});c.describe();}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","RLDI2 record replaced after inventory");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("PROFILE_READ",new Step(){public void at()throws Exception{Map<String,String> m=c.prof();m.put("profile_uuid",UUID.randomUUID().toString());c.rewriteProfile(m);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","profile replaced during inspection");}
        // Review P2-2: profile and RLDI2 record are bound by bytes AND physical identity, like the class-path JARs.
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{sameBytesNewFile(c.profile);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","profile replaced by a byte-identical different file");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{sameBytesNewFile(c.record);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","RLDI2 record replaced by a byte-identical different file");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("INVENTORY_DONE",new Step(){public void at()throws Exception{sameBytesNewFile(c.record);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","RLDI2 record replaced by a byte-identical file right after RLDI2");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{Map<String,String> m=c.prof();m.put("profile_uuid",UUID.randomUUID().toString());c.rewriteProfile(m);}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","profile content changed at the end of the inspection");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{c.roles.put("lib/late.txt","document");Files.write(c.lib.resolve("late.txt"),new byte[]{1});c.describe();}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","RLDI2 record content changed at the end of the inspection");}
        {final Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));
         expect(c.run(at("CLASSPATH_HASHED",new Step(){public void at()throws Exception{Files.delete(c.record.toPath());}})),
             "freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS","RLDI2 record gone at the end (identity unobtainable)");}
        {Cand c=new Cand(POSIX,L(SQLITE),L(SQLITE));Path link=c.home.resolve("install-link.rldi2");boolean linked=false;
         try{Files.createLink(link,c.record.toPath());linked=true;}catch(Exception noHardlink){}
         if(!linked)System.out.println("NOT_RUN hard-linked RLDI2 record (cannot create a hard link here)");
         else{DependencyReadiness.Report r=DependencyReadiness.inspect(true,c.profile,c.root.toFile(),link.toFile());safe(r,"hard-linked record");
             check(!r.matched()&&!"MATCHED".equals(r.axis("freshness").status),"hard-linked RLDI2 record (identity refused): not MATCHED, freshness "+code(r,"freshness")+", reason "+r.reason);}}

        // ---------------------------------------------------------------- nothing written by the inspection
        {Cand c=new Cand(POSIX,L(SQLITE,API,NOP),L(SQLITE,API,NOP));Map<String,String> before=tree(c.home);c.run();check(before.equals(tree(c.home)),"inspection writes nothing (home tree byte-identical)");}

        for(Path h:HOMES)removeTree(h);
        if(RED){System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected RED, got none");System.out.println("Phase X-D RED reproduction completed (RED_COUNT "+red+")");return;}
        System.out.println("PHASE_XD_READINESS_CHECKS "+checks);
    }
    static Map<String,String> tree(Path h)throws Exception {
        final Map<String,String> m=new TreeMap<String,String>();final Path base=h;
        Files.walkFileTree(h,new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes a)throws IOException{
            try{m.put(base.relativize(f).toString(),DeploymentTransition.fileSha(f.toFile())+"/"+a.lastModifiedTime());}catch(Exception e){throw new IOException(e);}return FileVisitResult.CONTINUE;}});
        return m;
    }
    static void removeTree(final Path h)throws IOException {
        // The link/junction alias goes first, on its own: it is removed as a link, its target is never walked through it.
        Path alias=h.resolve("alias");if(Files.exists(alias,LinkOption.NOFOLLOW_LINKS))Files.delete(alias);
        Files.walkFileTree(h,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,java.nio.file.attribute.BasicFileAttributes a)throws IOException{if(!d.equals(h)&&(a.isSymbolicLink()||a.isOther())){Files.delete(d);return FileVisitResult.SKIP_SUBTREE;}return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes a)throws IOException{Files.delete(f);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{Files.delete(d);return FileVisitResult.CONTINUE;}});
    }
}
