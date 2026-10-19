package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.*;

/** Fresh synthetic deployment and JARs only. Preflight never changes files or starts a sender. */
public final class Phase6PreflightTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final class Fixture {
        final Phase5ProvenanceTest.Fixture source=new Phase5ProvenanceTest.Fixture();
        final File root=DeploymentBindingTest.privateDir(Files.createDirectory(source.home.resolve("root"))).toFile();
        final File output=source.home.resolve("out.rlc2-integration.db").toFile();
        final DeploymentBinding.Layout layout;
        final Rlc2IntegrationStore.Admission admission;
        final List<File> jars;
        final Map<String,String> expected;
        final String manifest;
        Fixture()throws Exception {
            new Rlc2IntegrationStore(true,output);
            layout=DeploymentTransitionTest.layout(root,source.v1,output,source.dir,source.ledger,Phase5ProvenanceTest.A);
            DeploymentTransitionTest.privatize(source.home);DeploymentBinding.bindSynthetic(true,true,layout);
            admission=new Rlc2IntegrationStore.Admission(source.dir,layout.manifestSession,DeploymentTransitionTest.sources(source.dir));
            try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(source.v1);LaneOwnership owner=LaneOwnership.acquire(true,root,layout.device,layout.vehicle,layout.payloadSession);LaneOwnership publication=Rlc2PublicationBoundary.acquire(true,root,layout.manifestSession)){}
            File app=jar(source.home.resolve("app.jar"),true),other=jar(source.home.resolve("dependency.jar"),false);
            jars=Arrays.asList(app,other);expected=ArtifactProvenance.describe(jars,OfflineCandidatePreflight.MAIN);manifest=DeploymentTransition.fileSha(layout.manifest);
        }
        OfflineCandidatePreflight.Report inspect(int generation,String floor,Rlc2IntegrationStore.Admission sources,List<File> paths,Map<String,String> approved){return OfflineCandidatePreflight.inspect(true,layout,generation,floor,sources,paths,approved,Long.MAX_VALUE,source.env.reader());}
        OfflineCandidatePreflight.Report inspect(){return inspect(0,manifest,admission,jars,expected);}
    }
    static File jar(Path path,boolean app)throws Exception {
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE_NEW))){
            String name=app?OfflineCandidatePreflight.MAIN.replace('.','/')+".class":"synthetic-resource.txt";jar.putNextEntry(new JarEntry(name));
            if(app){try(InputStream in=Rlc2IntegrationCli.class.getResourceAsStream("/"+name)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)jar.write(b,0,n);}}else jar.write(new byte[]{1});jar.closeEntry();
        }
        DeploymentBindingTest.privateFile(path);return path.toFile();
    }
    static Map<String,String> hashes(Path home)throws Exception {
        Map<String,String> hashes=new TreeMap<String,String>();
        try(java.util.stream.Stream<Path> paths=Files.walk(home)){for(Iterator<Path> it=paths.iterator();it.hasNext();){Path path=it.next();if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))hashes.put(home.relativize(path).toString(),DeploymentTransition.fileSha(path.toFile()));}}
        return hashes;
    }
    static void reason(String wanted,OfflineCandidatePreflight.Report report){check(wanted.equals(report.reason),"preflight "+wanted+" ["+report.reason+"]");check(!report.json().contains("\"launch_authorized\":true"),"refusal never authorizes launch");}
    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        reason("PREFLIGHT_DISABLED",OfflineCandidatePreflight.inspect(false,null,0,null,null,null,null,0,null));
        final Fixture f=new Fixture();final Map<String,String> before=hashes(f.source.home);
        OfflineCandidatePreflight.Report matched=f.inspect();reason("WRITER_UNPROVEN",matched);
        check(matched.prerequisitesMatched,"valid synthetic preflight matches artifacts/generation/admissions/retained observations");
        check(matched.json().contains("\"process_provenance\":\"UNKNOWN_PROCESS_PROVENANCE\"")&&matched.json().contains("\"directory_durability\":\"UNKNOWN\""),"unknown process/durability stay independent and blocked");
        check(!matched.json().contains(f.source.home.toString())&&!matched.json().contains(Phase5ProvenanceTest.A)&&!matched.json().contains("TEST_RPM"),"JSON omits paths payload and session identifiers");
        reason("DEPLOYMENT_GENERATION_MISMATCH",f.inspect(1,f.manifest,f.admission,f.jars,f.expected));
        reason("MANIFEST_EXPECTATION_MISMATCH",f.inspect(0,new String(new char[64]).replace('\0','0'),f.admission,f.jars,f.expected));
        Rlc2IntegrationStore.Admission wrong=new Rlc2IntegrationStore.Admission(f.source.dir,f.layout.manifestSession,Collections.singleton(UUID.randomUUID().toString()));
        reason("SOURCE_ADMISSION_MISMATCH",f.inspect(0,f.manifest,wrong,f.jars,f.expected));
        reason("CLASSPATH_ORDER_CHANGED",f.inspect(0,f.manifest,f.admission,Arrays.asList(f.jars.get(1),f.jars.get(0)),f.expected));
        reason("CLASSPATH_ENTRY_COUNT_CHANGED",f.inspect(0,f.manifest,f.admission,Collections.singletonList(f.jars.get(0)),f.expected));
        Map<String,String> old=new TreeMap<String,String>(f.expected);old.put("main_class",ProductBridgeWorker.class.getName());reason("UNKNOWN_OR_LEGACY_ENTRY_BUILD",f.inspect(0,f.manifest,f.admission,f.jars,old));
        Map<String,String> runtime=new TreeMap<String,String>(f.expected);runtime.put("java_specification","99");reason("JAVA_VERSION_MISMATCH",f.inspect(0,f.manifest,f.admission,f.jars,runtime));
        Map<String,String> bits=new TreeMap<String,String>(f.expected);bits.put("data_model","32".equals(f.expected.get("data_model"))?"64":"32");reason("DATA_MODEL_MISMATCH",f.inspect(0,f.manifest,f.admission,f.jars,bits));
        File missing=f.source.home.resolve("missing.jar").toFile();reason("CLASSPATH_ENTRY_MISSING",f.inspect(0,f.manifest,f.admission,Arrays.asList(f.jars.get(0),missing),f.expected));
        check(before.equals(hashes(f.source.home)),"read-only preflight preserves entire synthetic tree including ACK/output/lock evidence");
        for(String kind:new String[]{"worker","lane","publication"})try(Held held=new Held(f.layout,kind)){
            reason(kind.equals("worker")?"LEGACY_WORKER_BUSY":kind.equals("lane")?"LANE_NOT_OBSERVED_IDLE":"PUBLICATION_NOT_OBSERVED_IDLE",f.inspect());
        }
        reason("WRITER_UNPROVEN",f.inspect());
        check(DeploymentTransition.fileSha(f.source.v1).equals(before.get("v1.db"))&&DeploymentTransition.fileSha(f.output).equals(before.get("out.rlc2-integration.db")),"child lock tests preserve original and candidate DBs");
        System.out.println("Phase VI preflight checks="+checks);
    }
    static final class Held implements AutoCloseable {
        final Process child;final PrintWriter input;final BufferedReader output;
        Held(DeploymentBinding.Layout layout,String kind)throws Exception {
            child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),LockChild.class.getName(),kind,layout.root.toString(),layout.legacy.toString(),layout.device,layout.vehicle,layout.payloadSession,layout.manifestSession).redirectErrorStream(true).start();
            input=new PrintWriter(child.getOutputStream(),true);output=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!output.ready()){if(!child.isAlive()||System.nanoTime()>end)throw new AssertionError("lock child did not start");Thread.sleep(10);}
            String line=output.readLine();check(line.startsWith("HELD "),"actual child "+kind+" "+line);
        }
        public void close()throws Exception {input.println("release");if(!child.waitFor(20,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("lock child timeout");}check(child.exitValue()==0,"lock child exit0");input.close();output.close();}
    }
    public static final class LockChild {
        public static void main(String[] args)throws Exception {
            AutoCloseable scope=args[0].equals("worker")?LegacyWorkerGuard.acquire(new File(args[2])):args[0].equals("lane")?LaneOwnership.acquire(true,new File(args[1]),args[3],args[4],args[5]):Rlc2PublicationBoundary.acquire(true,new File(args[1]),args[6]);
            try{System.out.println("HELD "+java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);System.out.flush();new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();}finally{scope.close();}
        }
    }
}
