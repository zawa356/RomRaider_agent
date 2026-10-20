package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.product.v1.*;

/**
 * Phase VII-1 acceptance for the single Offline Candidate Entry (RLOE1/RLOP1/RLSO1). Invocation-owned synthetic fixtures and
 * JARs only. Real supervisor and child JVMs are started from the pinned profile; every refusal must leave data bytes unchanged
 * and every result must keep launch/production/ACK disabled.
 *
 * Args: one or more packaged JARs (absolute), in launch order. Copied into an owned dist directory before pinning.
 */
public final class Phase7EntryTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final String READER=System.getProperty("os.name").startsWith("Windows")?OfflineCandidateEntry.READER_WINDOWS_TEST:OfflineCandidateEntry.READER_POSIX;

    static final class Deployment {
        final Phase7Fixture f;final File profile,dist,work;final List<File> jars=new ArrayList<File>();
        Deployment(String payload,List<File> packaged,boolean marker,int generation)throws Exception {this(new Phase7Fixture(payload),packaged,marker,generation);}
        Deployment(Phase7Fixture fixture,List<File> packaged,boolean marker,int generation)throws Exception {
            f=fixture;
            dist=DeploymentBindingTest.privateDir(Files.createDirectory(f.home.resolve("dist"))).toFile();
            work=DeploymentBindingTest.privateDir(Files.createDirectory(f.home.resolve("work"))).toFile();
            for(File jar:packaged){Path copy=dist.toPath().resolve(jar.getName());Files.copy(jar.toPath(),copy);DeploymentBindingTest.privateFile(copy);jars.add(copy.toFile().getCanonicalFile());}
            if(marker)OfflineCandidateEntry.createOwnedMarker(true,f.layout,f.home.toFile());
            profile=f.home.resolve("offline-candidate.rlop1").toFile();
            OfflineCandidateEntry.createProfile(profile,f.layout,f.home.toFile(),work,jars,READER,generation);
            DeploymentTransitionTest.privatize(f.home);
        }
        /** Same deployment and JARs, newly pinned for another expected generation (operator re-pins after a transition). */
        Deployment repin(int generation)throws Exception {
            File p=f.home.resolve("offline-candidate-g"+generation+"-"+UUID.randomUUID()+".rlop1").toFile();
            OfflineCandidateEntry.createProfile(p,f.layout,f.home.toFile(),work,jars,READER,generation);DeploymentBindingTest.privateFile(p.toPath());
            return new Deployment(this,p);
        }
        Deployment(Deployment base,File profile){f=base.f;dist=base.dist;work=base.work;jars.addAll(base.jars);this.profile=profile;}
        Map<String,String> record()throws Exception {return DeploymentBinding.readRecord(profile.toPath());}
        OfflineCandidateEntry.RuntimeFacts facts()throws Exception {
            Map<String,String> r=record();
            return new OfflineCandidateEntry.RuntimeFacts(r.get("classpath"),OfflineCandidateEntry.MAIN,r.get("user"),r.get("working_directory"),System.getProperty("java.home"),
                System.getProperty("java.specification.version"),System.getProperty("sun.arch.data.model"),Collections.<String>emptyList());
        }
        String[] args(String mode)throws Exception {return new String[]{mode,profile.getPath(),f.manifestSha(),f.sourcesCsv()};}
        /** Data bytes that no entry mode may change: original v1, ledger, RLC2 sources and manifest. */
        Map<String,String> data()throws Exception {
            Map<String,String> m=new TreeMap<String,String>();
            for(Map.Entry<String,String> e:f.tree().entrySet())if(e.getKey().equals("v1.db")||e.getKey().equals("ledger.db")||e.getKey().startsWith("session"))m.put(e.getKey(),e.getValue());
            return m;
        }
        String output()throws Exception {return DeploymentTransition.fileSha(f.output);}
    }
    static final class Result {final int exit;final String json;Result(int exit,String json){this.exit=exit;this.json=json;}}
    static Result inProcess(String[] args,OfflineCandidateEntry.RuntimeFacts facts)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int exit=OfflineCandidateEntry.run(args,facts,new PrintStream(bytes,true,"UTF-8"));
        return new Result(exit,new String(bytes.toByteArray(),StandardCharsets.UTF_8).trim());
    }
    /** Actual JVM from the pinned profile: exact java executable, classpath and working directory. */
    static Result process(Deployment d,String[] args)throws Exception {
        Map<String,String> r=d.record();List<String> command=new ArrayList<String>(Arrays.asList(r.get("java_executable"),"-cp",r.get("classpath"),OfflineCandidateEntry.MAIN));
        command.addAll(Arrays.asList(args));
        ProcessBuilder b=new ProcessBuilder(command).directory(d.work).redirectErrorStream(true);
        b.environment().remove("JAVA_TOOL_OPTIONS");
        Process p=b.start();p.getOutputStream().close();
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);}
        check(p.waitFor(900,TimeUnit.SECONDS),"entry process finished");
        String text=new String(out.toByteArray(),StandardCharsets.UTF_8).trim();String[] lines=text.split("\r?\n");
        return new Result(p.exitValue(),lines[lines.length-1]);
    }
    static void safe(Result r,Deployment d,String what)throws Exception {
        check(r.json.contains("\"launch_authorized\":false")&&r.json.contains("\"production_authorized\":false")&&r.json.contains("\"ack_eligible\":false")&&!r.json.contains(":true,\"production")&&!r.json.contains("\"launch_authorized\":true"),what+": launch/production/ACK stay disabled");
        if(d!=null)check(!r.json.contains(d.f.home.toString())&&!r.json.contains(d.f.home.toString().replace("\\","\\\\"))&&!r.json.contains(d.f.layout.manifestSession)&&!r.json.contains(d.f.layout.payloadSession),what+": JSON omits paths and session identifiers");
    }
    static void refused(String code,int exit,Result r,Deployment d,String what)throws Exception {
        check(r.exit==exit&&r.json.contains("\"reason\":\""+code+"\""),what+" ["+r.exit+" "+OfflineCandidateEntry.field(r.json,"reason")+"]");safe(r,d,what);
    }
    static Deployment forged(Deployment base,String key,String value)throws Exception {
        Map<String,String> r=base.record();r.put(key,value);File p=base.f.home.resolve("forged-"+UUID.randomUUID()+".rlop1").toFile();
        DeploymentBinding.createRecord(p.toPath(),DeploymentBinding.encodeRecord(r));
        return new Deployment(base,p);
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" home="+(System.getProperty("java.home").contains("x86")?"x86-dir":"x64-dir"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());
        check(!packaged.isEmpty(),"packaged JARs supplied");

        // ---- default-off: no mode, unknown/extra arguments, force and production live.
        OfflineCandidateEntry.RuntimeFacts none=OfflineCandidateEntry.RuntimeFacts.current();
        refused("EXPLICIT_MODE_REQUIRED",2,inProcess(new String[0],none),null,"no arguments start nothing");
        refused("EXPLICIT_MODE_REQUIRED",2,inProcess(new String[]{"--synthetic-owned","--force"},none),null,"--force is not an option");
        refused("EXPLICIT_MODE_REQUIRED",2,inProcess(new String[]{"--diagnose","a","b","c","--force"},none),null,"extra argument refused");
        refused("PRODUCTION_LIVE_NOT_IMPLEMENTED",6,inProcess(new String[]{"--production-live"},none),null,"production live always refused");

        // ---- real supervisor JVM: diagnose, then explicit synthetic-owned execution through its own child JVM.
        final Deployment d=new Deployment("synthetic-phase7-entry",packaged,true,0);
        check(String.valueOf(System.getProperty("sun.arch.data.model")).equals(d.record().get("artifact.data_model")),"profile pins this runtime data model");
        Map<String,String> data=d.data();String out0=d.output();
        Result diag=process(d,d.args("--diagnose"));
        check(diag.exit==5&&diag.json.contains("\"state\":\"BLOCKED\"")&&diag.json.contains("\"reason\":\"WRITER_UNPROVEN\"")&&diag.json.contains("\"prerequisites_matched\":true"),"diagnose matches prerequisites and stays BLOCKED ["+diag.exit+"]");
        check(diag.json.contains("\"data_ownership\":\"SYNTHETIC_OWNED_MARKER_MATCHED\"")&&diag.json.contains("\"profile\":\"MATCHED_PINNED_LAUNCH\""),"diagnose reports pinned profile and owned data");
        safe(diag,d,"diagnose");check(data.equals(d.data())&&out0.equals(d.output()),"diagnose changes no data or output bytes");
        Result run=process(d,d.args("--synthetic-owned"));
        check(run.exit==0&&run.json.contains("\"state\":\"STORED_SYNTHETIC_OWNED\""),"supervised synthetic-owned run stores ["+run.exit+" "+OfflineCandidateEntry.field(run.json,"reason")+"]");
        check(run.json.contains("\"child_started\":true")&&run.json.contains("\"child_exit\":0")&&run.json.contains("\"child_process_provenance\":\"LAUNCHED_BY_SUPERVISOR_EXACT_COMMAND\"")&&run.json.contains("\"records_added\":2"),"child exit, provenance and two records tracked");
        check(OfflineCandidateEntry.field(run.json,"child_stderr_sha256").matches("[0-9a-f]{64}")&&run.json.contains("\"child_stderr_bytes\":0"),"child stderr captured and hashed (empty)");
        safe(run,d,"synthetic-owned");
        check(data.equals(d.data())&&!out0.equals(d.output()),"original v1/ledger/sources unchanged; only candidate output written");
        check(Rlc2PreviewStoreTest.count(d.f.output,"SELECT COUNT(*) FROM preview_records")==2,"candidate holds exactly two records");
        check(Rlc2PreviewStoreTest.count(d.f.legacy,"SELECT COUNT(*) FROM v1_records")==0&&Rlc2PreviewStoreTest.count(d.f.legacy,"SELECT COUNT(*) FROM v1_ack_receipts")==0,"no v1 record or ACK receipt created");
        String out1=d.output();
        Result again=process(d,d.args("--synthetic-owned"));
        check(again.exit==0&&again.json.contains("\"records_added\":0")&&again.json.contains("\"receipts_added\":0"),"same input rerun adds nothing");
        check(Rlc2PreviewStoreTest.count(d.f.output,"SELECT COUNT(*) FROM preview_records")==2&&data.equals(d.data()),"rerun: no duplicate, data unchanged");

        // ---- profile / runtime mismatches (in-process, same pinned profile).
        OfflineCandidateEntry.RuntimeFacts ok=d.facts();String[] owned=d.args("--synthetic-owned");String out2=d.output();
        refused("MAIN_COMMAND_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ProductBridgeWorker.class.getName(),ok.user,ok.workingDirectory,ok.javaHome,ok.javaSpecification,ok.dataModel,ok.jvmArguments)),d,"unknown/legacy main class refused");
        refused("JVM_OPTIONS_REFUSED",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user,ok.workingDirectory,ok.javaHome,ok.javaSpecification,ok.dataModel,Collections.singletonList("-javaagent:x.jar"))),d,"JVM options/agents refused");
        refused("EXECUTION_USER_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user+"-other",ok.workingDirectory,ok.javaHome,ok.javaSpecification,ok.dataModel,ok.jvmArguments)),d,"different execution user refused");
        refused("WORKING_DIRECTORY_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user,d.dist.getPath(),ok.javaHome,ok.javaSpecification,ok.dataModel,ok.jvmArguments)),d,"different working directory refused");
        refused("JAVA_EXECUTABLE_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user,ok.workingDirectory,d.dist.getPath(),ok.javaSpecification,ok.dataModel,ok.jvmArguments)),d,"different java executable refused");
        refused("DATA_MODEL_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user,ok.workingDirectory,ok.javaHome,ok.javaSpecification,"32".equals(ok.dataModel)?"64":"32",ok.jvmArguments)),d,"x86/x64 difference refused");
        refused("JAVA_VERSION_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(ok.classpath,ok.mainCommand,ok.user,ok.workingDirectory,ok.javaHome,"99",ok.dataModel,ok.jvmArguments)),d,"java specification difference refused");
        if(d.jars.size()>1){
            List<String> parts=new ArrayList<String>(Arrays.asList(ok.classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))));Collections.swap(parts,0,1);
            StringBuilder swapped=new StringBuilder();for(String s:parts){if(swapped.length()>0)swapped.append(File.pathSeparator);swapped.append(s);}
            refused("CLASSPATH_STRING_MISMATCH",5,inProcess(owned,new OfflineCandidateEntry.RuntimeFacts(swapped.toString(),ok.mainCommand,ok.user,ok.workingDirectory,ok.javaHome,ok.javaSpecification,ok.dataModel,ok.jvmArguments)),d,"classpath order difference refused");
            Deployment order=forged(d,"classpath",swapped.toString());
            refused("CLASSPATH_ORDER_CHANGED",5,inProcess(order.args("--synthetic-owned"),order.facts()),order,"profile/JAR order inconsistency refused by RLAP1 order rule");
        }
        refused("UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS",5,inProcess(forged(d,"main_class",ProductBridgeWorker.class.getName()).args("--synthetic-owned"),ok),d,"profile naming another main class refused");
        refused("OWNER_ROOT_MISMATCH",5,inProcess(forged(d,"owner_root",d.f.home.resolve("other-root").toString()).args("--synthetic-owned"),ok),d,"different owner root refused");
        refused("PRODUCTION_PATH_REFUSED",5,inProcess(forged(d,"data_root","C:\\Users\\zawa\\Documents\\OpenPortWork\\RRBridge\\data").args("--synthetic-owned"),ok),d,"real-car deployment path refused before any access");
        refused("PROFILE_MODE_UNSUPPORTED",5,inProcess(forged(d,"mode","PRODUCTION").args("--synthetic-owned"),ok),d,"non-synthetic profile mode refused");
        check(d.output().equals(out2)&&data.equals(d.data()),"profile/runtime refusals change no bytes");

        // ---- tampered JAR after pinning.
        {
            Deployment t=new Deployment("synthetic-phase7-tamper",packaged,true,0);
            Files.write(t.jars.get(t.jars.size()-1).toPath(),new byte[]{0},StandardOpenOption.APPEND);
            String before=t.output();
            refused("CLASSPATH_ENTRY_CHANGED",5,inProcess(t.args("--synthetic-owned"),t.facts()),t,"JAR changed after pinning refused");
            check(before.equals(t.output()),"tampered JAR: output unchanged");
        }

        // ---- ownership: missing marker, swapped byte-identical v1, outside root, wrong generation.
        {
            Deployment m=new Deployment("synthetic-phase7-nomarker",packaged,false,0);Map<String,String> before=m.data();String o=m.output();
            refused("OWNED_MARKER_MISSING",5,inProcess(m.args("--synthetic-owned"),m.facts()),m,"data without ownership marker refused");
            check(before.equals(m.data())&&o.equals(m.output())&&!Files.exists(Paths.get(m.f.legacy.getPath()+"-journal")),"unowned data refused before any DB write");
            Result dg=inProcess(m.args("--diagnose"),m.facts());
            check(dg.exit==5&&dg.json.contains("\"data_ownership\":\"OWNED_MARKER_MISSING\""),"diagnose reports missing ownership without refusing to inspect read-only");
            try{OfflineCandidateEntry.createOwnedMarker(true,m.f.layout,m.dist);check(false,"marker outside data root");}catch(IOException e){check("DATA_OUTSIDE_OWNED_ROOT".equals(e.getMessage()),"marker cannot claim files outside its data root ["+e.getMessage()+"]");}
        }
        {
            Deployment s=new Deployment("synthetic-phase7-swap",packaged,true,0);
            Path legacy=s.f.legacy.toPath(),moved=s.f.home.resolve("v1-original.db");
            Files.move(legacy,moved);Files.copy(moved,legacy);DeploymentBindingTest.privateFile(legacy);
            check(DeploymentTransition.fileSha(legacy.toFile()).equals(DeploymentTransition.fileSha(moved.toFile())),"replacement v1 is byte-identical");
            String o=s.output();
            refused("OWNED_MARKER_IDENTITY_MISMATCH",5,inProcess(s.args("--synthetic-owned"),s.facts()),s,"byte-identical but different physical v1 refused before inspection");
            check(o.equals(s.output()),"swapped v1: output unchanged");
        }
        {
            Deployment g=new Deployment("synthetic-phase7-generation",packaged,true,1);String o=g.output();
            refused("DEPLOYMENT_GENERATION_MISMATCH",5,inProcess(g.args("--synthetic-owned"),g.facts()),g,"unconfirmed deployment generation refused");
            check(o.equals(g.output()),"generation refusal: output unchanged");
        }

        // ---- cooperative competitors held by actual child JVMs (the F1 GREEN).
        for(String kind:new String[]{"worker","lane","publication"}){
            Deployment c=new Deployment("synthetic-phase7-held-"+kind,packaged,true,0);Map<String,String> before=c.data();String o=c.output();Result r;
            try(Phase6PreflightTest.Held held=new Phase6PreflightTest.Held(c.f.layout,kind)){r=inProcess(c.args("--synthetic-owned"),c.facts());}
            String code=kind.equals("worker")?"LEGACY_WORKER_BUSY":kind.equals("lane")?"LANE_NOT_OBSERVED_IDLE":"PUBLICATION_NOT_OBSERVED_IDLE";
            refused(code,3,r,c,"approved entry refuses while actual child holds "+kind);
            check(r.json.contains("\"child_started\":false")&&o.equals(c.output())&&before.equals(c.data()),kind+" competitor: no child started, no bytes changed");
        }

        // ---- direct child invocation and nonce reuse.
        {
            String[] direct={"--synthetic-owned-child",d.profile.getPath(),d.f.manifestSha(),d.f.sourcesCsv(),UUID.randomUUID().toString()};String o=d.output();
            refused("OWNED_RUN_NONCE_MISSING",5,inProcess(direct,d.facts()),d,"child mode without supervisor run record refused");
            File[] started=new File(d.f.home.toFile(),"owned-runs").listFiles(new FileFilter(){public boolean accept(File x){return x.getName().endsWith(".started");}});
            check(started!=null&&started.length==2,"two supervised runs left two one-shot start records");
            String used=started[0].getName().replace(".started","");
            refused("OWNED_RUN_ALREADY_USED",5,inProcess(new String[]{"--synthetic-owned-child",d.profile.getPath(),d.f.manifestSha(),d.f.sourcesCsv(),used},d.facts()),d,"supervisor run record is one-shot");
            check(o.equals(d.output()),"direct child refusals: output unchanged");
        }

        // ---- the old CLI is no longer the approved preflight entry.
        {
            File old=d.f.home.resolve("old-cli.jar").toFile();
            try(java.util.jar.JarOutputStream jar=new java.util.jar.JarOutputStream(Files.newOutputStream(old.toPath(),StandardOpenOption.CREATE_NEW))){
                String name=Rlc2IntegrationCli.class.getName().replace('.','/')+".class";jar.putNextEntry(new java.util.jar.JarEntry(name));
                try(InputStream in=Rlc2IntegrationCli.class.getResourceAsStream("/"+name)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)jar.write(b,0,n);}jar.closeEntry();
            }
            List<File> cli=Collections.singletonList(old);
            OfflineCandidatePreflight.Report report=OfflineCandidatePreflight.inspect(true,d.f.layout,0,d.f.manifestSha(),d.f.admission,cli,ArtifactProvenance.describe(cli,Rlc2IntegrationCli.class.getName()),-1,d.f.env.reader());
            check("UNKNOWN_OR_LEGACY_ENTRY_BUILD".equals(report.reason)&&!report.prerequisitesMatched,"preflight no longer approves Rlc2IntegrationCli ["+report.reason+"]");
        }
        System.out.println("Phase VII entry checks="+checks);
    }
}
