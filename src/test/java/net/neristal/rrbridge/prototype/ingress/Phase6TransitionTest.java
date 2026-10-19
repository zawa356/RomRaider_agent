package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import net.neristal.rrbridge.product.v1.*;

/** Real source UUID reuse, child-JVM locks and halt stages in fresh isolated deployments only. */
public final class Phase6TransitionTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final String PURPOSE="Phase VI synthetic transition test";
    static final class Fixture {
        final WatermarkV2Scenarios.Env env=Rlc2ProductAdapterTest.environment();
        final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase6-transition-"));
        final Path a=DeploymentTransitionTest.session(env,home,"a",DeploymentTransitionTest.A,1791838800000L);
        final File root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("root"))).toFile();
        final File legacy=home.resolve("old.db").toFile(),out=home.resolve("out.rlc2-integration.db").toFile(),ledger=home.resolve("ledger.db").toFile();
        final DeploymentBinding.Layout old;
        Path b;DeploymentBinding.Layout next;
        Fixture()throws Exception {
            new DurableSpool(legacy);new Rlc2IntegrationStore(true,out);
            RotationManifestPrototype.ingestSession(a,new WatermarkLedgerPrototype(ledger),env.reader(),null);
            old=DeploymentTransitionTest.layout(root,legacy,out,a,ledger,DeploymentTransitionTest.A);DeploymentTransitionTest.privatize(home);DeploymentBinding.bindSynthetic(true,true,old);ingest(old);
        }
        void ingest(DeploymentBinding.Layout layout)throws Exception {
            new Rlc2IntegrationStore(true,out).ingestPublished(true,root,layout.payloadSession,new Rlc2IntegrationStore.Admission(layout.session.toPath(),layout.manifestSession,DeploymentTransitionTest.sources(layout.session.toPath())),layout.session.toPath(),ledger,legacy,layout.device,layout.vehicle,env.reader(),Rlc2PreviewStore.NO_FAULT);
        }
        void next()throws Exception {
            RotationManifestPrototype.seal(a,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);RotationManifestPrototype.ingestSession(a,new WatermarkLedgerPrototype(ledger),env.reader(),null);ingest(old);
            b=DeploymentTransitionTest.session(env,home,"b",DeploymentTransitionTest.B,1791838900000L);RotationManifestPrototype.ingestSession(b,new WatermarkLedgerPrototype(ledger),env.reader(),null);
            next=DeploymentTransitionTest.layout(root,legacy,out,b,ledger,DeploymentTransitionTest.B);DeploymentTransitionTest.privatize(home);
        }
        DeploymentTransition.Plan plan()throws Exception{return DeploymentTransition.plan(true,true,old,next,DeploymentTransitionTest.sources(b),PURPOSE,env.reader());}
        void interrupt()throws Exception {
            try{DeploymentTransition.commitSynthetic(true,true,old,next,DeploymentTransitionTest.sources(b),PURPOSE,plan().digest,env.reader(),new DeploymentTransition.Hook(){public void at(DeploymentTransition.Stage stage)throws Exception{if(stage==DeploymentTransition.Stage.PREPARED)throw new IOException("SYNTHETIC_INTERRUPT");}});throw new AssertionError("no interrupt");}
            catch(IOException expected){check("SYNTHETIC_INTERRUPT".equals(expected.getMessage()),"interrupt after PREPARED");}
        }
    }
    static void abandonContention()throws Exception {
        for(String side:new String[]{"old","next"})for(String kind:new String[]{"lane","publication"}){
            Fixture f=new Fixture();f.next();f.interrupt();String legacy=DeploymentTransition.fileSha(f.legacy),output=DeploymentTransition.fileSha(f.out),ledger=DeploymentTransition.fileSha(f.ledger);
            String prepare=DeploymentTransition.fileSha(DeploymentTransition.prepare(f.old,1).toFile());
            boolean refused=false;
            try(Phase6PreflightTest.Held held=new Phase6PreflightTest.Held(side.equals("old")?f.old:f.next,kind)){
                try{DeploymentTransition.abandonSynthetic(true,true,f.old,1,"competing synthetic holder");}catch(DeploymentTransition.Refusal expected){refused="BLOCKED_UNCOOPERATIVE_PROCESS".equals(expected.getMessage());}
            }
            check(refused,"abandon refuses actual child holding "+side+" "+kind);
            check(!Files.exists(DeploymentTransition.abandoned(f.old,1)),"busy abandon creates no marker");
            check(prepare.equals(DeploymentTransition.fileSha(DeploymentTransition.prepare(f.old,1).toFile())),"busy abandon preserves exact prepare");
            DeploymentTransition.abandonSynthetic(true,true,f.old,1,"holder released");
            DeploymentTransition.Plan retry=f.plan();check(retry.generation==2,"abandoned generation skipped on retry");
            check(DeploymentTransition.commitSynthetic(true,true,f.old,f.next,DeploymentTransitionTest.sources(f.b),PURPOSE,retry.digest,f.env.reader(),DeploymentTransition.NO_FAULT)==2,"retry commits only next Session");
            boolean oldBlocked=false;try{DeploymentTransition.verifyActive(true,true,f.old);}catch(DeploymentTransition.Refusal expected){oldBlocked=expected.getMessage().equals("SESSION_CHANGED");}check(oldBlocked,"old Session authority revoked");
            check(legacy.equals(DeploymentTransition.fileSha(f.legacy))&&output.equals(DeploymentTransition.fileSha(f.out))&&ledger.equals(DeploymentTransition.fileSha(f.ledger)),"transition and abandon preserve DB/ledger/ACK bytes");
        }
    }
    static void reusedSource()throws Exception {
        Fixture f=new Fixture();f.b=DeploymentTransitionTest.session(f.env,f.home,"stored-other",DeploymentTransitionTest.B,1791838900000L);
        RotationManifestPrototype.ingestSession(f.b,new WatermarkLedgerPrototype(f.ledger),f.env.reader(),null);
        DeploymentBinding.Layout other=DeploymentTransitionTest.layout(f.root,f.legacy,f.out,f.b,f.ledger,DeploymentTransitionTest.B);f.ingest(other);
        String duplicate=RotationManifestPrototype.read(f.b,null).active.sourceUuid;
        RotationManifestPrototype.seal(f.a,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);RotationManifestPrototype.startNext(f.a,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
        DeploymentTransitionTest.append(f.env,f.a,3,"S\t"+DeploymentTransitionTest.A+"\t1\t2\t1791838802000\tTEST_RPM=702");
        Path source=RotationManifestPrototype.segment(f.a,3);WatermarkV2Prototype.Marker marker=WatermarkV2Prototype.readCommitted(source.toFile(),f.env.reader(),null).marker;
        RotationManifestPrototype.State state=RotationManifestPrototype.read(f.a,null);
        // Forge only this new synthetic physical source to reuse the already-stored other run's UUID.
        String prefix="RLI1\n"+duplicate+"\n";CRC32 crc=new CRC32();crc.update(prefix.getBytes(StandardCharsets.US_ASCII));
        Files.write(Paths.get(source+".identity"),(prefix+String.format(Locale.ROOT,"%08x",crc.getValue())+"\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(WatermarkV2Prototype.marker(source.toFile()),WatermarkV2Prototype.encode(new WatermarkV2Prototype.Marker(marker.identity,duplicate,marker.generation,marker.sequence,marker.offset,marker.prefixHash())));
        RotationManifestPrototype.Segment active=new RotationManifestPrototype.Segment(3,duplicate,state.active.identity,0,0,null);
        Files.write(f.a.resolve("rotation.manifest"),RotationManifestPrototype.encode(state.sessionUuid,state.generation+1,state.sealed,active));
        DeploymentTransitionTest.privatize(f.home);
        Map<String,String> before=Phase6PreflightTest.hashes(f.home);boolean refused=false;
        try{DeploymentTransition.plan(true,true,f.old,f.old,Collections.singleton(duplicate),PURPOSE,f.env.reader());}catch(DeploymentTransition.Refusal expected){refused=expected.getMessage().equals("SOURCE_UUID_REUSED");}
        check(refused,"SAME_SESSION refuses real physical UUID reused from another stored run");
        check(before.equals(Phase6PreflightTest.hashes(f.home)),"UUID refusal preserves all originals, manifests, binding, ledger and candidate/ACK bytes");
    }
    static void haltStages()throws Exception {
        for(DeploymentTransition.Stage stage:new DeploymentTransition.Stage[]{DeploymentTransition.Stage.PREPARED,DeploymentTransition.Stage.ANCHOR_WRITTEN,DeploymentTransition.Stage.ROOT_WRITTEN,DeploymentTransition.Stage.COMMITTED}){
            Fixture f=new Fixture();f.next();String legacy=DeploymentTransition.fileSha(f.legacy),output=DeploymentTransition.fileSha(f.out),ledger=DeploymentTransition.fileSha(f.ledger);
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),HaltChild.class.getName(),f.root.toString(),f.legacy.toString(),f.out.toString(),f.a.toString(),f.b.toString(),f.ledger.toString(),f.plan().digest,stage.name()).redirectErrorStream(true).redirectOutput(f.home.resolve("child.log").toFile()).start();
            check(child.waitFor(90,TimeUnit.SECONDS),"transition halt child exits at "+stage);check(child.exitValue()==74,"halt exit74 at "+stage);
            System.out.println("HALT stage="+stage+" log="+DeploymentTransition.fileSha(f.home.resolve("child.log").toFile()));
            System.out.println(new String(Files.readAllBytes(f.home.resolve("child.log")),StandardCharsets.UTF_8).trim());
            if(stage==DeploymentTransition.Stage.COMMITTED){check(DeploymentTransition.verifyActive(true,true,f.next)==1,"committed next generation remains sole authority");}
            else{
                boolean refused=false;try{DeploymentTransition.verifyActive(true,true,f.old);}catch(DeploymentTransition.Refusal expected){refused=expected.getMessage().equals("TRANSITION_INTERRUPTED");}check(refused,"restart interrupted at "+stage);
                try(Phase6PreflightTest.Held held=new Phase6PreflightTest.Held(f.next,"publication")){
                    refused=false;try{DeploymentTransition.abandonSynthetic(true,true,f.old,1,"halt competition");}catch(DeploymentTransition.Refusal expected){refused=expected.getMessage().equals("BLOCKED_UNCOOPERATIVE_PROCESS");}check(refused,"halt abandon refuses active new publication");
                }
                DeploymentTransition.abandonSynthetic(true,true,f.old,1,"halt reviewed");check(f.plan().generation==2,"halt retry requires fresh generation");
            }
            check(legacy.equals(DeploymentTransition.fileSha(f.legacy))&&output.equals(DeploymentTransition.fileSha(f.out))&&ledger.equals(DeploymentTransition.fileSha(f.ledger)),"halt leaves original/candidate DBs and ACK unchanged");
        }
    }
    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        if(args.length>0&&args[0].equals("--reuse")){reusedSource();return;}
        abandonContention();reusedSource();haltStages();System.out.println("Phase VI transition checks="+checks);
    }
    public static final class HaltChild {
        public static void main(String[] args)throws Exception {
            final WatermarkV2Scenarios.Env env=Rlc2ProductAdapterTest.environment();File root=new File(args[0]),legacy=new File(args[1]),out=new File(args[2]),ledger=new File(args[5]);
            DeploymentBinding.Layout old=DeploymentTransitionTest.layout(root,legacy,out,Paths.get(args[3]),ledger,DeploymentTransitionTest.A),next=DeploymentTransitionTest.layout(root,legacy,out,Paths.get(args[4]),ledger,DeploymentTransitionTest.B);
            final DeploymentTransition.Stage halt=DeploymentTransition.Stage.valueOf(args[7]);
            DeploymentTransition.commitSynthetic(true,true,old,next,DeploymentTransitionTest.sources(next.session.toPath()),PURPOSE,args[6],env.reader(),new DeploymentTransition.Hook(){public void at(DeploymentTransition.Stage stage){if(stage==halt){System.out.println("HALT "+stage+" pid="+java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);System.out.flush();Runtime.getRuntime().halt(74);}}});
        }
    }
}
