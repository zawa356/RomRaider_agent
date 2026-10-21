package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.prototype.ingress.DistributionInventory;
import net.neristal.rrbridge.prototype.ingress.OfflineCandidateEntry;

/**
 * Phase IX-1 (R9-01): the four offline tool CLIs are started as real child JVMs from the separately built tool JAR only
 * (never from this test's own class directory), and the tool JAR must stay out of an approved RLDI1 install.
 *
 * <p>Args: TOOL_JAR ENTRY_JAR DEPENDENCY_CLASSPATH. Synthetic fixtures only; read-only CLIs.
 */
public final class Phase9CliProcessTest {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static final String[] CLIS={"net.neristal.rrbridge.product.v1.ArtifactProvenanceCli","net.neristal.rrbridge.product.v1.DeploymentBindingCli",
        "net.neristal.rrbridge.prototype.ingress.DeploymentTransitionCli","net.neristal.rrbridge.prototype.ingress.IngressDiagnosticsCli"};
    static final String[] OLD={"net.neristal.rrbridge.product.v1.ArtifactProvenance","net.neristal.rrbridge.product.v1.DeploymentBinding",
        "net.neristal.rrbridge.prototype.ingress.DeploymentTransition","net.neristal.rrbridge.prototype.ingress.IngressDiagnostics"};
    static final boolean WINDOWS=System.getProperty("os.name").startsWith("Windows");
    static final String SECRET="phase9-canary-"+UUID.randomUUID();

    static final class Run {final int exit;final String out;Run(int e,String o){exit=e;out=o;}}
    static Path home;static File toolJar;static String deps;

    /** Child JVM: absolute java executable, classpath = tool JAR + pinned dependencies only, canary in the environment. */
    static Run run(String cp,Map<String,String> extraEnv,String... args)throws Exception {
        String exe=new File(new File(System.getProperty("java.home"),"bin"),WINDOWS?"java.exe":"java").toString();
        List<String> cmd=new ArrayList<String>(Arrays.asList(exe,"-cp",cp));cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true);
        File log=Files.createTempFile(home,"cli-",".out").toFile();pb.redirectOutput(log);
        pb.environment().put("ROMLOGGER_PHASE9_CANARY",SECRET);pb.environment().remove("JAVA_TOOL_OPTIONS");pb.environment().remove("_JAVA_OPTIONS");pb.environment().remove("JDK_JAVA_OPTIONS");
        if(extraEnv!=null)pb.environment().putAll(extraEnv);
        Process p=pb.start();int exit=p.waitFor();
        return new Run(exit,new String(Files.readAllBytes(log.toPath()),StandardCharsets.UTF_8));
    }
    static Run tool(String... args)throws Exception {return run(toolJar+File.pathSeparator+deps,null,args);}
    static boolean clean(Run r){return !r.out.contains(SECRET)&&!r.out.contains(home.toString())&&!r.out.contains("Exception")&&!r.out.contains("\tat ");}

    public static void main(String[] a)throws Exception {
        if(a.length!=3)throw new IllegalArgumentException("TOOL_JAR ENTRY_JAR DEPENDENCY_CLASSPATH");
        toolJar=new File(a[0]).getAbsoluteFile();File entryJar=new File(a[1]).getAbsoluteFile();deps=a[2];
        home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase9-cli-"));
        String own=System.getProperty("java.class.path");
        boolean excluded=true;for(String part:own.split(File.pathSeparator.equals(";")?";":":")){if(part.endsWith(".jar"))continue;String abs=new File(part).getAbsolutePath();for(String c:(toolJar+File.pathSeparator+deps).split(File.pathSeparator.equals(";")?";":":"))if(new File(c).getAbsolutePath().equals(abs))excluded=false;}
        check(excluded,"child classpath excludes every class directory of this test");

        // 1. tool JAR holds exactly the four CLIs as public static mains (loaded in an isolated loader from the JAR only).
        try(URLClassLoader l=new URLClassLoader(new URL[]{toolJar.toURI().toURL()},ClassLoader.getSystemClassLoader().getParent())){
            for(String c:CLIS){Method m=Class.forName(c,false,l).getMethod("main",String[].class);
                check(Modifier.isPublic(m.getModifiers())&&Modifier.isStatic(m.getModifiers()),"tool JAR defines "+c.substring(c.lastIndexOf('.')+1)+".main");}
        }
        try(JarFile j=new JarFile(toolJar)){
            check(j.getManifest()==null||j.getManifest().getMainAttributes().getValue("Main-Class")==null,"tool JAR has no Main-Class");
            boolean test=false;for(Enumeration<JarEntry> e=j.entries();e.hasMoreElements();){String n=e.nextElement().getName();if(n.endsWith("Test.class")||n.contains("Fixture")||n.contains("Windows")&&n.contains("Cli"))test=true;}
            check(!test,"tool JAR contains no test classes or test-only Windows CLI");
            check(j.getEntry("com/sun/jna/Native.class")==null,"tool JAR contains no JNA");
        }
        // 2. the old classes no longer carry main (the incompatibility is deliberate; no alias added).
        for(String c:OLD){boolean has=false;for(Method m:Class.forName(c).getDeclaredMethods())if(m.getName().equals("main")&&Modifier.isStatic(m.getModifiers()))has=true;
            check(!has,"old class "+c.substring(c.lastIndexOf('.')+1)+" has no static main");}
        try(JarFile j=new JarFile(entryJar)){for(String c:CLIS)check(j.getEntry(c.replace('.','/')+".class")==null,"entry JAR does not contain "+c.substring(c.lastIndexOf('.')+1));}

        // 3. usage: every CLI exits 2 (no args; an unknown 4-argument invocation). IngressDiagnostics treats 2 positional args as SESSION LEDGER.
        for(String c:CLIS){
            Run r=tool(c);check(r.exit==2&&clean(r),c.substring(c.lastIndexOf('.')+1)+" no-arg usage exit2 without leak (exit "+r.exit+")");
            r=tool(c,"--bogus",home.toString(),"x","--y");check(r.exit==2&&clean(r),c.substring(c.lastIndexOf('.')+1)+" unknown 4-arg invocation exit2 without path echo (exit "+r.exit+")");
        }
        // 4. ArtifactProvenanceCli: real describe of the tool JAR itself, refused inspection.
        Run r=tool(CLIS[0],"--describe",OfflineCandidateEntry.MAIN,entryJar.toString());
        check(r.exit==0&&!r.out.contains(entryJar.getParent())&&!r.out.contains("UNKNOWN")&&clean(r),"ArtifactProvenanceCli --describe of the entry JAR exit0, names not paths");
        r=tool(CLIS[0],"--describe",OfflineCandidateEntry.MAIN,toolJar.toString());
        check(r.exit==5&&r.out.contains("MAIN_CLASS_MISSING")&&clean(r),"ArtifactProvenanceCli --describe refuses the tool JAR as entry (MAIN_CLASS_MISSING)");
        Path rec=home.resolve("missing.rlap1");
        r=tool(CLIS[0],"--inspect-process","not-a-pid",rec.toString());
        check(r.exit==5&&r.out.contains("UNKNOWN_PROCESS_PROVENANCE")&&clean(r),"ArtifactProvenanceCli malformed PID refused exit5");
        r=tool(CLIS[0],"--inspect-process","1",rec.toString());
        check(r.exit==5&&clean(r),"ArtifactProvenanceCli missing record refused exit5");

        // 5. DeploymentBindingCli / DeploymentTransitionCli on a bound synthetic RLDB1 fixture.
        DeploymentBinding.Layout l=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,l);
        String[] layout={l.root.toString(),l.legacy.toString(),l.output.toString(),l.session.toString(),l.manifest.toString(),l.ledger.toString(),l.device,l.vehicle,l.payloadSession,l.manifestSession};
        List<String> args=new ArrayList<String>(Arrays.asList(CLIS[1],"--inspect-synthetic","--external-single-active"));args.addAll(Arrays.asList(layout));
        r=tool(args.toArray(new String[0]));
        check(r.exit==0&&r.out.contains("READY_FOR_SYNTHETIC_TEST")&&r.out.contains("\"production_authorized\":false")&&!r.out.contains(l.root.toString())&&!r.out.contains(SECRET),"DeploymentBindingCli bound fixture exit0, no paths");
        args=new ArrayList<String>(Arrays.asList(CLIS[2],"--inspect-active-synthetic","--external-single-active"));args.addAll(Arrays.asList(layout));
        r=tool(args.toArray(new String[0]));
        check(r.exit==0&&r.out.contains("ACTIVE_GENERATION_VERIFIED")&&!r.out.contains(l.root.toString())&&!r.out.contains(SECRET),"DeploymentTransitionCli --inspect-active-synthetic exit0 (no reader needed), no paths: "+r.out.trim());
        Files.move(l.manifest.toPath(),l.manifest.toPath().resolveSibling("held.manifest"));
        args=new ArrayList<String>(Arrays.asList(CLIS[1],"--inspect-synthetic","--external-single-active"));args.addAll(Arrays.asList(layout));
        r=tool(args.toArray(new String[0]));check(r.exit==5&&r.out.contains("REVIEW_REQUIRED")&&!r.out.contains(l.root.toString()),"DeploymentBindingCli missing manifest exit5");
        args.set(0,CLIS[2]);args.set(1,"--inspect-active-synthetic");
        r=tool(args.toArray(new String[0]));check(r.exit==5&&r.out.contains("REVIEW_REQUIRED")&&!r.out.contains(l.root.toString()),"DeploymentTransitionCli missing manifest exit5");
        Files.move(l.manifest.toPath().resolveSibling("held.manifest"),l.manifest.toPath());

        // 6. IngressDiagnosticsCli: source-only preflight and session inspection refuse (exit5) without echoing paths.
        Path rlj=home.resolve("absent-source.rlj");
        r=tool(CLIS[3],"--migration-preflight",rlj.toString(),"--json");check(r.exit==5&&clean(r),"IngressDiagnosticsCli preflight on absent source exit5 without path");
        Path bogus=Files.write(home.resolve("bogus.rlj"),"not an RLC1 journal".getBytes(StandardCharsets.US_ASCII));
        r=tool(CLIS[3],"--migration-preflight",bogus.toString(),"--json");check(r.exit==5&&clean(r),"IngressDiagnosticsCli preflight on malformed source exit5");
        Path session=Files.createDirectory(home.resolve("empty-session"));
        r=tool(CLIS[3],session.toString(),home.resolve("absent-ledger.db").toString(),"--json");
        check(r.exit==5&&r.out.contains("\"ack_eligible\":false")&&clean(r),"IngressDiagnosticsCli empty session/absent ledger exit5 review, ack_eligible=false");
        if(WINDOWS)System.out.println("NOTE IngressDiagnosticsCli healthy session / DeploymentTransitionCli --plan-synthetic use POSIX identity; a Windows exit0 for those is NOT claimed (needs RLWR1)");

        // 7. classpath shadowing: CLASSPATH env is ignored when -cp is given; a shadow JAR placed first wins (JVM rule) and
        //    is caught only by RLDI1 (duplicate class / unapproved main) — so an install must be inventoried.
        Path shadowSrc=Files.createDirectories(home.resolve("shadow/net/neristal/rrbridge/prototype/ingress"));
        File shadowJar=home.resolve("shadow.jar").toFile();
        try(JarOutputStream j=new JarOutputStream(new FileOutputStream(shadowJar))){
            j.putNextEntry(new JarEntry("net/neristal/rrbridge/prototype/ingress/IngressDiagnosticsCli.class"));
            try(JarFile tj=new JarFile(toolJar);InputStream in=tj.getInputStream(tj.getJarEntry("net/neristal/rrbridge/product/v1/DeploymentBindingCli.class"))){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)j.write(b,0,n);}
        }
        Map<String,String> env=new HashMap<String,String>();env.put("CLASSPATH",shadowJar.toString());
        r=run(toolJar+File.pathSeparator+deps,env,CLIS[3]);check(r.exit==2&&!r.out.contains("REVIEW_REQUIRED"),"CLASSPATH environment cannot shadow an explicit -cp");
        Path inst=Files.createDirectories(home.resolve("install"));Files.copy(entryJar.toPath(),inst.resolve("romlogger-offline.jar"));
        Files.copy(toolJar.toPath(),inst.resolve("romlogger-offline-tools.jar"));
        Map<String,String> roles=new TreeMap<String,String>();roles.put("romlogger-offline.jar","approved_jar");roles.put("romlogger-offline-tools.jar","dependency_jar");
        File rldi=home.resolve("tools-colocated.rldi1").toFile();writeRecord(rldi,DistributionInventory.describe(inst.toFile(),OfflineCandidateEntry.MAIN,roles));
        DistributionInventory.Report rep=DistributionInventory.inspect(true,inst.toFile(),rldi);
        check("MISMATCH".equals(rep.inventory)&&rep.unapprovedMains.containsAll(Arrays.asList(CLIS))&&rep.findings.containsKey("DUPLICATE_CLASS"),"tool JAR next to the entry is RLDI1 MISMATCH (4 unapproved mains + duplicate classes)");
        Files.delete(inst.resolve("romlogger-offline-tools.jar"));roles.remove("romlogger-offline-tools.jar");
        writeRecord(rldi,DistributionInventory.describe(inst.toFile(),OfflineCandidateEntry.MAIN,roles));
        rep=DistributionInventory.inspect(true,inst.toFile(),rldi);
        check(rep.matched()&&rep.unapprovedMains.isEmpty()&&rep.json().contains("\"state\":\"BLOCKED\"")&&rep.json().contains("\"launch_authorized\":false"),"entry JAR alone stays RLDI1 MATCHED, still BLOCKED/not launch authorization");
        Files.copy(shadowJar.toPath(),inst.resolve("shadow.jar"));roles.put("shadow.jar","dependency_jar");
        writeRecord(rldi,DistributionInventory.describe(inst.toFile(),OfflineCandidateEntry.MAIN,roles));
        rep=DistributionInventory.inspect(true,inst.toFile(),rldi);
        check("MISMATCH".equals(rep.inventory)&&rep.unapprovedMains.contains(CLIS[3]),"listed shadow JAR with a main is RLDI1 MISMATCH");
        System.out.println("Phase IX CLI process test passed "+checks+" checks; tool JAR separate; launch/production/ACK disabled");
    }
    static void writeRecord(File f,Map<String,String> m)throws Exception {
        if(f.exists())Files.delete(f.toPath());
        DeploymentBinding.createRecord(f.toPath(),DeploymentBinding.encodeRecord(m));
    }
    private Phase9CliProcessTest(){}
}
