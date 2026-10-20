package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.product.v1.ArtifactProvenance;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/**
 * Phase VII single recommended Offline Candidate Entry (RLOE1). Default-off: without one explicit mode it does nothing.
 *
 * <ul>
 * <li>{@code --diagnose PROFILE MANIFEST_SHA SOURCES}: read-only. Verifies the RLOP1 launch profile against this JVM and runs
 * {@link OfflineCandidatePreflight}. Always BLOCKED; never opens a DB writable or creates a lock.</li>
 * <li>{@code --synthetic-owned PROFILE MANIFEST_SHA SOURCES}: explicit synthetic execution. Requires an RLSO1 ownership marker
 * binding the exact data files (checked before any DB is opened), a matched preflight, then launches ONE child JVM itself with
 * the profile's exact java executable, classpath and working directory, and tracks its exit, stdout and stderr. The child writes
 * only through the generation-checked ingest that holds the Worker, Lane and Publication locks.</li>
 * <li>{@code --production-live}: always refused. Product live operation is not implemented.</li>
 * </ul>
 *
 * A successful synthetic-owned run is NOT launch authorization, NOT proof that no other (old or noncooperative) writer exists and
 * NOT a formal ACK. There is deliberately no force/override flag and no environment-variable bypass.
 */
public final class OfflineCandidateEntry {
    public static final String VERSION="RLOE1",PROFILE_VERSION="RLOP1",MARKER_VERSION="RLSO1",RUN_VERSION="RLOR1";
    public static final String MAIN=OfflineCandidateEntry.class.getName();
    public static final String MARKER_NAME=".romlogger-synthetic-owned.rlso1";
    public static final String READER_POSIX="POSIX",READER_WINDOWS_TEST="WINDOWS_NATIVE_TEST_ONLY";
    static final String WINDOWS_TEST_READER_CLASS="net.neristal.rrbridge.prototype.ingress.WindowsWatermarkV2Test";
    static final long CHILD_TIMEOUT_MILLIS=600000;static final int CHILD_OUTPUT_LIMIT=65536;
    private static final String[] LAYOUT_KEYS={"root","legacy","output","session","manifest","ledger","device","vehicle","payload","manifest_session"};
    private static final String[] OWNED_FILES={"legacy","output","session","manifest","ledger"};
    /** Paths of the real-car deployment are never accepted, whatever the marker says. */
    private static final String[] PRODUCTION_PATH_MARKERS={"openportwork","rrbridge\\dist","program files (x86)\\romraider","program files\\romraider"};

    public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
    private static void require(boolean ok,String code)throws Refusal {if(!ok)throw new Refusal(code);}

    /** What this JVM says about itself. A claim, compared to the pinned profile; never process authentication. */
    public static final class RuntimeFacts {
        public final String classpath,mainCommand,user,workingDirectory,javaHome,javaSpecification,dataModel;public final List<String> jvmArguments;
        public RuntimeFacts(String classpath,String mainCommand,String user,String workingDirectory,String javaHome,String javaSpecification,String dataModel,List<String> jvmArguments){
            this.classpath=classpath;this.mainCommand=mainCommand;this.user=user;this.workingDirectory=workingDirectory;this.javaHome=javaHome;
            this.javaSpecification=javaSpecification;this.dataModel=dataModel;this.jvmArguments=Collections.unmodifiableList(new ArrayList<String>(jvmArguments));
        }
        public static RuntimeFacts current(){
            String command=String.valueOf(System.getProperty("sun.java.command","")).trim();
            return new RuntimeFacts(System.getProperty("java.class.path"),command.split(" ")[0],System.getProperty("user.name"),System.getProperty("user.dir"),
                System.getProperty("java.home"),System.getProperty("java.specification.version"),String.valueOf(System.getProperty("sun.arch.data.model")),
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        }
    }

    /** Verified RLOP1 profile: exact code, packaging, runtime, working directory, owner root, user and data root. */
    public static final class Profile {
        public final Map<String,String> record;public final DeploymentBinding.Layout layout;public final File dataRoot,workingDirectory,javaExecutable;
        public final Map<String,String> artifacts;public final int expectedGeneration;public final String sha256;
        Profile(Map<String,String> record,String sha)throws Exception {
            this.record=record;sha256=sha;
            layout=new DeploymentBinding.Layout(new File(record.get("layout.root")),new File(record.get("layout.legacy")),new File(record.get("layout.output")),
                new File(record.get("layout.session")),new File(record.get("layout.manifest")),new File(record.get("layout.ledger")),
                record.get("layout.device"),record.get("layout.vehicle"),record.get("layout.payload"),record.get("layout.manifest_session"));
            dataRoot=new File(record.get("data_root"));workingDirectory=new File(record.get("working_directory"));javaExecutable=new File(record.get("java_executable"));
            Map<String,String> a=new TreeMap<String,String>();
            for(Map.Entry<String,String> e:record.entrySet())if(e.getKey().startsWith("artifact."))a.put(e.getKey().substring(9),e.getValue());
            artifacts=Collections.unmodifiableMap(a);
            try{expectedGeneration=Integer.parseInt(record.get("expected_generation"));}catch(RuntimeException bad){throw new Refusal("PROFILE_FORMAT");}
        }
    }

    // ------------------------------------------------------------------ profile and ownership records
    static String javaExecutable(String javaHome){return new File(javaHome,"bin"+File.separator+(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java")).getAbsolutePath();}
    /**
     * Operator/fixture step: pins the exact launch for one synthetic deployment. CREATE_NEW, private, never overwritten.
     * The description is computed from the given JARs and the CURRENT runtime: create it under the runtime it will pin.
     */
    public static void createProfile(File profile,DeploymentBinding.Layout l,File dataRoot,File workingDirectory,List<File> classpath,String reader,int expectedGeneration)throws Exception {
        require(READER_POSIX.equals(reader)||READER_WINDOWS_TEST.equals(reader),"READER_UNSUPPORTED");
        Map<String,String> m=new TreeMap<String,String>();
        m.put("version",PROFILE_VERSION);m.put("mode","SYNTHETIC_OWNED_ONLY");m.put("main_class",MAIN);m.put("reader",reader);
        m.put("profile_uuid",UUID.randomUUID().toString());m.put("user",System.getProperty("user.name"));
        m.put("working_directory",workingDirectory.getCanonicalPath());m.put("data_root",dataRoot.getCanonicalPath());
        m.put("java_executable",new File(javaExecutable(System.getProperty("java.home"))).getCanonicalPath());
        StringBuilder cp=new StringBuilder();for(File f:classpath){if(cp.length()>0)cp.append(File.pathSeparator);cp.append(f.getPath());}m.put("classpath",cp.toString());
        for(Map.Entry<String,String> e:ArtifactProvenance.describe(classpath,MAIN).entrySet())m.put("artifact."+e.getKey(),e.getValue());
        String[] values={l.root.getPath(),l.legacy.getPath(),l.output.getPath(),l.session.getPath(),l.manifest.getPath(),l.ledger.getPath(),l.device,l.vehicle,l.payloadSession,l.manifestSession};
        for(int i=0;i<LAYOUT_KEYS.length;i++)m.put("layout."+LAYOUT_KEYS[i],values[i]);
        m.put("owner_root",l.root.getPath());m.put("expected_generation",String.valueOf(expectedGeneration));
        DeploymentBinding.createRecord(profile.toPath(),DeploymentBinding.encodeRecord(m));
    }
    /**
     * Synthetic fixture step: records that the CALLER generated these exact data files in a fresh data root. A cooperative
     * declaration bound to physical identities, not authentication. Refuses production-looking paths and an existing marker.
     */
    public static void createOwnedMarker(boolean enable,DeploymentBinding.Layout l,File dataRoot)throws Exception {
        require(enable,"OWNED_MARKER_DISABLED");Map<String,String> m=ownedDescription(l,dataRoot);
        m.put("owner_uuid",UUID.randomUUID().toString());
        DeploymentBinding.createRecord(dataRoot.toPath().resolve(MARKER_NAME),DeploymentBinding.encodeRecord(m));
    }
    private static Map<String,String> ownedDescription(DeploymentBinding.Layout l,File dataRoot)throws Exception {
        Path root=DeploymentBinding.safe(dataRoot);DeploymentBinding.privateAccess(root);require(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS),"OWNED_DATA_ROOT_REQUIRED");
        Map<String,String> m=new TreeMap<String,String>();m.put("version",MARKER_VERSION);m.put("mode","SYNTHETIC_OWNED_ONLY");m.put("data_root",root.toString());
        File[] files={l.legacy,l.output,l.session,l.manifest,l.ledger};
        for(int i=0;i<files.length;i++){
            Path p=files[i].toPath();require(p.isAbsolute()&&p.equals(p.normalize()),"PATH_NOT_ABSOLUTE_NORMAL");
            require(p.startsWith(root)&&!p.equals(root),"DATA_OUTSIDE_OWNED_ROOT");
            m.put(OWNED_FILES[i],p.toString());
            // A legitimate rotation atomically replaces the manifest file; its content is bound by RLDB/RLDT instead.
            if(!"manifest".equals(OWNED_FILES[i]))m.put(OWNED_FILES[i]+"_identity",DeploymentBinding.fileIdentity(files[i]));
        }
        return m;
    }
    /** Must run before any SQLite connection: unknown or swapped data is refused without inspection. */
    static void requireOwned(Profile p)throws Exception {
        requireNotProduction(p);
        Path marker=p.dataRoot.toPath().resolve(MARKER_NAME);
        require(Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS),"OWNED_MARKER_MISSING");
        Map<String,String> stored;try{stored=DeploymentBinding.readRecord(marker);}catch(DeploymentBinding.Refusal r){throw new Refusal("OWNED_MARKER_INVALID");}
        require(MARKER_VERSION.equals(stored.get("version"))&&"SYNTHETIC_OWNED_ONLY".equals(stored.get("mode")),"OWNED_MARKER_INVALID");
        String owner=stored.remove("owner_uuid");try{require(owner!=null&&UUID.fromString(owner).toString().equals(owner),"OWNED_MARKER_INVALID");}catch(IllegalArgumentException bad){throw new Refusal("OWNED_MARKER_INVALID");}
        Map<String,String> observed;
        try{observed=ownedDescription(p.layout,p.dataRoot);}catch(Refusal r){throw r;}catch(DeploymentBinding.Refusal r){throw new Refusal("OWNED_DATA_"+r.getMessage());}
        for(String key:OWNED_FILES){
            require(Objects.equals(stored.get(key),observed.get(key)),"OWNED_MARKER_PATH_MISMATCH");
            require(Objects.equals(stored.get(key+"_identity"),observed.get(key+"_identity")),"OWNED_MARKER_IDENTITY_MISMATCH");
        }
        require(stored.equals(observed),"OWNED_MARKER_MISMATCH");
    }
    static void requireNotProduction(Profile p)throws Refusal {
        List<String> paths=new ArrayList<String>(Arrays.asList(p.dataRoot.getPath(),p.workingDirectory.getPath()));
        for(String k:new String[]{"root","legacy","output","session","manifest","ledger"})paths.add(p.record.get("layout."+k));
        for(String path:paths){String lower=path.toLowerCase(Locale.ROOT).replace('/','\\');for(String marker:PRODUCTION_PATH_MARKERS)require(!lower.contains(marker),"PRODUCTION_PATH_REFUSED");}
    }
    /** Reads and checks the profile against this JVM. Every mismatch is a stable refusal code; nothing is relaxed. */
    static Profile verifyProfile(File file,RuntimeFacts facts)throws Exception {
        Map<String,String> record;
        try{record=DeploymentBinding.readRecord(DeploymentBinding.safe(file));}catch(DeploymentBinding.Refusal r){throw new Refusal("PROFILE_"+r.getMessage());}
        require(PROFILE_VERSION.equals(record.get("version")),"PROFILE_VERSION");
        require("SYNTHETIC_OWNED_ONLY".equals(record.get("mode")),"PROFILE_MODE_UNSUPPORTED");
        require(MAIN.equals(record.get("main_class")),"UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS");
        require(READER_POSIX.equals(record.get("reader"))||READER_WINDOWS_TEST.equals(record.get("reader")),"READER_UNSUPPORTED");
        for(String k:LAYOUT_KEYS)require(record.get("layout."+k)!=null,"PROFILE_FORMAT");
        // Strict field set: an extra field (e.g. a smuggled authorization flag) is refused, never ignored.
        Set<String> allowed=new HashSet<String>(Arrays.asList("version","mode","main_class","reader","profile_uuid","user","working_directory","data_root",
            "java_executable","classpath","owner_root","expected_generation"));
        for(String k:LAYOUT_KEYS)allowed.add("layout."+k);
        for(String k:record.keySet())require(allowed.contains(k)||k.startsWith("artifact."),"PROFILE_UNKNOWN_FIELD");
        for(String k:new String[]{"user","working_directory","data_root","java_executable","classpath","owner_root","expected_generation","profile_uuid"})require(record.get(k)!=null,"PROFILE_FORMAT");
        Profile p=new Profile(record,DeploymentTransition.fileSha(file));
        require(p.expectedGeneration>=0,"PROFILE_FORMAT");
        require(MAIN.equals(facts.mainCommand),"MAIN_COMMAND_MISMATCH");
        require(facts.jvmArguments.isEmpty(),"JVM_OPTIONS_REFUSED");
        require(record.get("user").equals(facts.user),"EXECUTION_USER_MISMATCH");
        require(new File(facts.workingDirectory).getCanonicalPath().equals(record.get("working_directory")),"WORKING_DIRECTORY_MISMATCH");
        require(new File(javaExecutable(facts.javaHome)).getCanonicalPath().equals(record.get("java_executable")),"JAVA_EXECUTABLE_MISMATCH");
        require(Objects.equals(p.artifacts.get("data_model"),facts.dataModel),"DATA_MODEL_MISMATCH");
        require(Objects.equals(p.artifacts.get("java_specification"),facts.javaSpecification),"JAVA_VERSION_MISMATCH");
        require(record.get("classpath").equals(facts.classpath),"CLASSPATH_STRING_MISMATCH");
        Map<String,String> observed=ArtifactProvenance.describe(ArtifactProvenance.parse(facts.classpath),MAIN);
        observed.put("java_specification",facts.javaSpecification);observed.put("data_model",facts.dataModel);
        ArtifactProvenance.compare(p.artifacts,observed);
        require(record.get("owner_root").equals(record.get("layout.root")),"OWNER_ROOT_MISMATCH");
        requireNotProduction(p);
        return p;
    }
    static Rlc2IntegrationStore.Admission admission(Profile p,String sources)throws Exception {
        require(sources!=null&&sources.matches("[0-9a-f,-]{36,}"),"SOURCE_ADMISSION_REQUIRED");
        try{return new Rlc2IntegrationStore.Admission(p.layout.session.toPath(),p.layout.manifestSession,new HashSet<String>(Arrays.asList(sources.split(",",-1))));}
        catch(IllegalArgumentException bad){throw new Refusal("SOURCE_ADMISSION_REQUIRED");}
    }
    /** Only the reader pinned by the profile. The Windows native reader is test-only code present in the pinned classpath. */
    static WatermarkV2Prototype.ReadOpener reader(Profile p)throws Exception {
        if(READER_POSIX.equals(p.record.get("reader")))return WatermarkV2Prototype.POSIX_READER;
        try{java.lang.reflect.Field f=Class.forName(WINDOWS_TEST_READER_CLASS).getDeclaredField("READER");f.setAccessible(true);return (WatermarkV2Prototype.ReadOpener)f.get(null);}
        catch(ReflectiveOperationException|LinkageError unavailable){throw new Refusal("READER_UNAVAILABLE");}
    }

    // ------------------------------------------------------------------ result JSON (codes and counts only; no paths or IDs)
    static String json(String mode,String state,String reason,Map<String,String> fields){
        StringBuilder b=new StringBuilder("{\"contract_version\":1,\"entry\":\"").append(VERSION).append("\",\"mode\":\"").append(mode).append("\",\"state\":\"").append(state).append("\",\"reason\":\"").append(reason).append('"');
        if(fields!=null)for(Map.Entry<String,String> e:fields.entrySet()){
            String v=e.getValue();boolean raw=v!=null&&(v.equals("true")||v.equals("false")||v.matches("-?[0-9]{1,18}")||v.startsWith("{"));
            b.append(",\"").append(e.getKey()).append("\":").append(raw?v:"\""+v+"\"");
        }
        return b.append(",\"synthetic_only\":true,\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false,\"formal_ack\":\"DISABLED\",\"automatic_recovery\":false,\"writer_quiescence\":\"WRITER_UNPROVEN\"}").toString();
    }
    static String reason(Exception e){
        return e instanceof Refusal||e instanceof DeploymentBinding.Refusal||e instanceof DeploymentTransition.Refusal||e instanceof ArtifactProvenance.Refusal||e instanceof OfflineCandidatePreflight.Refusal?e.getMessage():
            e.getMessage()!=null&&(e.getMessage().startsWith("LEGACY_WORKER_BUSY")||e.getMessage().startsWith("LANE_BUSY"))?"BLOCKED_COOPERATIVE_LOCK_HELD":"REVIEW_REQUIRED";
    }
    /** Uncoded failures keep their exception class (never a message, path or payload) so evidence is not collapsed (review F2). */
    static void cause(Map<String,String> f,String reason,Exception e){if("REVIEW_REQUIRED".equals(reason))f.put("cause_class",e.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]",""));}
    static int exit(String reason){
        if(reason.startsWith("BLOCKED_COOPERATIVE")||reason.endsWith("_BUSY")||reason.endsWith("_NOT_OBSERVED_IDLE"))return 3;
        return 5;
    }

    // ------------------------------------------------------------------ modes
    static final class Outcome {final int exit;final String json;Outcome(int exit,String json){this.exit=exit;this.json=json;}}
    static OfflineCandidatePreflight.Report preflight(Profile p,String manifestSha,Rlc2IntegrationStore.Admission a,RuntimeFacts facts,WatermarkV2Prototype.ReadOpener reader)throws Exception {
        return OfflineCandidatePreflight.inspect(true,p.layout,p.expectedGeneration,manifestSha,a,ArtifactProvenance.parse(facts.classpath),p.artifacts,-1,reader);
    }
    static Outcome diagnose(String[] a,RuntimeFacts facts){
        Map<String,String> f=new TreeMap<String,String>();f.put("data_ownership","UNKNOWN");f.put("profile","UNKNOWN");
        try{
            Profile p=verifyProfile(new File(a[1]),facts);f.put("profile","MATCHED_PINNED_LAUNCH");
            try{requireOwned(p);f.put("data_ownership","SYNTHETIC_OWNED_MARKER_MATCHED");}catch(Refusal r){f.put("data_ownership",r.getMessage());}
            OfflineCandidatePreflight.Report report=preflight(p,a[2],admission(p,a[3]),facts,reader(p));
            f.put("preflight",report.json());f.put("prerequisites_matched",String.valueOf(report.prerequisitesMatched));
            return new Outcome(5,json("DIAGNOSE","BLOCKED",report.reason,f));
        }catch(Exception e){String r=reason(e);cause(f,r,e);return new Outcome(exit(r)==3?3:5,json("DIAGNOSE","BLOCKED",r,f));}
    }
    /** Supervisor: owns exactly one child JVM it starts itself, with the profile's exact command line. */
    static Outcome syntheticOwned(String[] a,RuntimeFacts facts){
        Map<String,String> f=new TreeMap<String,String>();f.put("child_started","false");
        try{
            Profile p=verifyProfile(new File(a[1]),facts);f.put("profile","MATCHED_PINNED_LAUNCH");
            requireOwned(p);f.put("data_ownership","SYNTHETIC_OWNED_MARKER_MATCHED");
            Rlc2IntegrationStore.Admission admission=admission(p,a[3]);WatermarkV2Prototype.ReadOpener reader=reader(p);
            OfflineCandidatePreflight.Report report=preflight(p,a[2],admission,facts,reader);
            require(report.prerequisitesMatched&&"WRITER_UNPROVEN".equals(report.reason),report.reason);
            f.put("preflight_prerequisites","MATCHED_STILL_BLOCKED");
            String nonce=UUID.randomUUID().toString();Path runs=runs(p);
            Map<String,String> run=new TreeMap<String,String>();run.put("version",RUN_VERSION);run.put("nonce",nonce);run.put("profile_sha256",p.sha256);run.put("manifest_sha256",a[2]);run.put("sources",a[3]);
            DeploymentBinding.createRecord(runs.resolve(nonce+".rlor1"),DeploymentBinding.encodeRecord(run));
            List<String> command=Arrays.asList(p.javaExecutable.getPath(),"-cp",p.record.get("classpath"),MAIN,"--synthetic-owned-child",a[1],a[2],a[3],nonce);
            ProcessBuilder builder=new ProcessBuilder(command).directory(p.workingDirectory);
            for(String name:new String[]{"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH"})builder.environment().remove(name);
            Process child=builder.start();f.put("child_started","true");
            Captured out=new Captured(child.getInputStream()),err=new Captured(child.getErrorStream());out.start();err.start();
            child.getOutputStream().close();
            boolean finished=child.waitFor(CHILD_TIMEOUT_MILLIS,TimeUnit.MILLISECONDS);
            if(!finished){child.destroyForcibly();child.waitFor(30,TimeUnit.SECONDS);}
            out.join(30000);err.join(30000);
            f.put("child_exit",finished?String.valueOf(child.exitValue()):"-1");f.put("child_timed_out",String.valueOf(!finished));
            f.put("child_stdout_sha256",out.sha());f.put("child_stderr_sha256",err.sha());f.put("child_stderr_bytes",String.valueOf(err.size()));
            f.put("child_process_provenance","LAUNCHED_BY_SUPERVISOR_EXACT_COMMAND");
            String line=out.lastLine();
            require(finished&&!out.overflow&&!err.overflow,"CHILD_INCOMPLETE");
            require(line.startsWith("{\"contract_version\":1,\"entry\":\""+VERSION+"\",\"mode\":\"SYNTHETIC_OWNED_CHILD\""),"CHILD_RESULT_INVALID");
            f.put("child_result",line);
            // The tree must still be the same owned deployment after the child returns.
            requireOwned(p);
            int code=child.exitValue();
            if(code!=0){String r=field(line,"reason");return new Outcome(code==3?3:5,json("SYNTHETIC_OWNED","REFUSED",r==null?"CHILD_REFUSED":r,f));}
            require(DeploymentTransition.verifyActive(true,true,p.layout)==p.expectedGeneration,"DEPLOYMENT_GENERATION_CHANGED");
            return new Outcome(0,json("SYNTHETIC_OWNED","STORED_SYNTHETIC_OWNED","CHILD_STORED_THROUGH_BOUND_LOCKED_INGEST",f));
        }catch(Exception e){String r=reason(e);cause(f,r,e);return new Outcome(exit(r),json("SYNTHETIC_OWNED","REFUSED",r,f));}
    }
    /** Child: runs only for a supervisor-created one-shot run record; writes only through the bound, lock-holding ingest. */
    static Outcome child(String[] a,RuntimeFacts facts){
        Map<String,String> f=new TreeMap<String,String>();
        String pid=java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];f.put("pid_self_reported",pid.matches("[0-9]{1,10}")?pid:"0");
        try{
            Profile p=verifyProfile(new File(a[1]),facts);
            require(a[4].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"OWNED_RUN_NONCE_MISSING");
            Path runs=p.dataRoot.toPath().resolve("owned-runs"),record=runs.resolve(a[4]+".rlor1");
            require(Files.isRegularFile(record,LinkOption.NOFOLLOW_LINKS),"OWNED_RUN_NONCE_MISSING");
            Map<String,String> run=DeploymentBinding.readRecord(record);
            require(RUN_VERSION.equals(run.get("version"))&&a[4].equals(run.get("nonce"))&&p.sha256.equals(run.get("profile_sha256"))&&a[2].equals(run.get("manifest_sha256"))&&a[3].equals(run.get("sources")),"OWNED_RUN_MISMATCH");
            try{DeploymentBinding.createRecord(runs.resolve(a[4]+".started"),("pid="+f.get("pid_self_reported")+"\n").getBytes(StandardCharsets.US_ASCII));}
            catch(FileAlreadyExistsException used){throw new Refusal("OWNED_RUN_ALREADY_USED");}
            requireOwned(p);
            Rlc2IntegrationStore.Admission admission=admission(p,a[3]);WatermarkV2Prototype.ReadOpener reader=reader(p);
            OfflineCandidatePreflight.Report report=preflight(p,a[2],admission,facts,reader);
            require(report.prerequisitesMatched&&"WRITER_UNPROVEN".equals(report.reason),report.reason);
            Rlc2PreviewStore.Result result=Rlc2IntegrationStore.ingestBoundActive(true,true,p.layout,admission,reader,Rlc2PreviewStore.NO_FAULT);
            requireOwned(p);
            f.put("records_added",String.valueOf(result.recordsAdded));f.put("receipts_added",String.valueOf(result.receiptsAdded));f.put("directory_durability","UNKNOWN");
            return new Outcome(0,json("SYNTHETIC_OWNED_CHILD","STORED_SYNTHETIC_OWNED","BOUND_LOCKED_INGEST_COMMITTED",f));
        }catch(Exception e){String r=reason(e);cause(f,r,e);return new Outcome(exit(r),json("SYNTHETIC_OWNED_CHILD","REFUSED",r,f));}
    }
    static Path runs(Profile p)throws Exception {
        Path runs=p.dataRoot.toPath().resolve("owned-runs");
        if(!Files.exists(runs,LinkOption.NOFOLLOW_LINKS)){
            // Two supervisors may race here (Phase VII-4); the loser re-checks the winner's directory below instead of failing.
            try{
                if(Files.getFileAttributeView(p.dataRoot.toPath(),java.nio.file.attribute.PosixFileAttributeView.class)!=null)
                    Files.createDirectory(runs,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectory(runs);
            }catch(FileAlreadyExistsException raced){require(Files.isDirectory(runs,LinkOption.NOFOLLOW_LINKS),"OWNED_RUNS_INVALID");}
        }
        DeploymentBinding.safe(runs.toFile());DeploymentBinding.privateAccess(runs);return runs;
    }
    static String field(String json,String key){
        String k="\""+key+"\":\"";int at=json.indexOf(k);if(at<0)return null;int end=json.indexOf('"',at+k.length());return end<0?null:json.substring(at+k.length(),end);
    }
    /** Bounded capture of one child stream. Overflow makes the run incomplete, never silently truncated. */
    static final class Captured extends Thread {
        private final InputStream in;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();volatile boolean overflow;
        Captured(InputStream in){this.in=in;setDaemon(true);}
        public void run(){
            byte[] b=new byte[4096];int n;
            try{while((n=in.read(b))!=-1){synchronized(bytes){if(bytes.size()+n>CHILD_OUTPUT_LIMIT){overflow=true;continue;}bytes.write(b,0,n);}}}catch(IOException closed){}
        }
        int size(){synchronized(bytes){return bytes.size();}}
        String sha()throws Exception {synchronized(bytes){return DeploymentTransition.sha(bytes.toByteArray());}}
        String lastLine(){
            String text;synchronized(bytes){text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
            String[] lines=text.split("\r?\n");return lines.length==0?"":lines[lines.length-1].trim();
        }
    }

    /** Exit 0 stored (synthetic-owned only), 2 no explicit mode/usage, 3 cooperative lock held, 5 blocked/review, 6 production live refused. */
    public static int run(String[] a,RuntimeFacts facts,PrintStream out){
        Outcome o;
        if(a.length==1&&"--production-live".equals(a[0]))o=new Outcome(6,json("PRODUCTION_LIVE","REFUSED","PRODUCTION_LIVE_NOT_IMPLEMENTED",null));
        else if(a.length==4&&"--diagnose".equals(a[0]))o=diagnose(a,facts);
        else if(a.length==4&&"--synthetic-owned".equals(a[0]))o=syntheticOwned(a,facts);
        else if(a.length==5&&"--synthetic-owned-child".equals(a[0]))o=child(a,facts);
        else o=new Outcome(2,json("NONE","REFUSED","EXPLICIT_MODE_REQUIRED",null));
        out.println(o.json);out.flush();return o.exit;
    }
    public static void main(String[] a){System.exit(run(a,RuntimeFacts.current(),System.out));}
    private OfflineCandidateEntry(){}
}
