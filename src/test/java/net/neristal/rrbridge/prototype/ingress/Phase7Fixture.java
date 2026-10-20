package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;

/**
 * Phase VII invocation-owned synthetic deployment: fresh private home, real RLC2 session (two physical sources), real ledger,
 * empty v1 DurableSpool DB that does not claim the session, empty RLI2 output and RLDB1 generation 0 binding. Cooperative
 * Worker/Lane/Publication lock files are created once and released, so read-only observers can find them.
 */
final class Phase7Fixture {
    static final String D=DeploymentTransitionTest.D,V=DeploymentTransitionTest.V;
    final Env env;final Path home,dir;final String payload;final File ledger,legacy,output,root;
    final DeploymentBinding.Layout layout;final Rlc2IntegrationStore.Admission admission;
    Phase7Fixture(String payload)throws Exception {this(payload,false,0);}
    /**
     * @param httpShape segment rows of the real-Server HTTP harness: M+S0 | S1+E (then {@link #rotate} adds S2): two sources,
     *        four receipts, three records; after rotation three sources, five receipts, four records.
     * @param load extra observation rows appended to segment 1 (0 for none)
     */
    Phase7Fixture(String payload,boolean httpShape,int load)throws Exception {
        env=Rlc2ProductAdapterTest.environment();this.payload=payload;
        home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase7-"));
        if(!httpShape&&load==0)dir=DeploymentTransitionTest.session(env,home,"session",payload,1791838800000L);
        else{
            dir=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("session")));
            RotationManifestPrototype.begin(dir,env.reader(),env.platform());
            List<String> first=new ArrayList<String>(Arrays.asList("M\t"+payload+"\t1\tTEST_RPM\tSynthetic RPM\trpm","S\t"+payload+"\t1\t0\t1791838800000\tTEST_RPM=700"));
            for(int i=0;i<load;i++)first.add("S\t"+payload+"\t1\t"+(1000+i)+"\t"+(1791838900000L+i)+"\tTEST_RPM="+(800+i%5000));
            DeploymentTransitionTest.append(env,dir,1,first.toArray(new String[0]));
            RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            RotationManifestPrototype.startNext(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            DeploymentTransitionTest.append(env,dir,2,"S\t"+payload+"\t1\t1\t1791838801000\tTEST_RPM=701","E\t"+payload+"\t1\t1791838801200\tsynthetic rotation");
        }
        ledger=home.resolve("ledger.db").toFile();
        RotationManifestPrototype.ingestSession(dir,new WatermarkLedgerPrototype(ledger),env.reader(),null);
        legacy=home.resolve("v1.db").toFile();output=home.resolve("out.rlc2-integration.db").toFile();
        new DurableSpool(legacy);new Rlc2IntegrationStore(true,output);
        root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("root"))).toFile();
        DeploymentTransitionTest.privatize(home);
        layout=DeploymentTransitionTest.layout(root,legacy,output,dir,ledger,payload);
        DeploymentBinding.bindSynthetic(true,true,layout);
        admission=new Rlc2IntegrationStore.Admission(dir,layout.manifestSession,DeploymentTransitionTest.sources(dir));
        try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(legacy);LaneOwnership lane=LaneOwnership.acquire(true,root,D,V,payload);
            LaneOwnership publication=Rlc2PublicationBoundary.acquire(true,root,layout.manifestSession)){}
        DeploymentTransitionTest.privatize(home);
    }
    /** Legitimate rotation by the fixture's own writer: seal the active source, start a new one with one observation. */
    String rotate()throws Exception {
        RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        RotationManifestPrototype.startNext(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        int next=RotationManifestPrototype.read(dir,null).sealed.size()+1;
        DeploymentTransitionTest.append(env,dir,next,"S\t"+payload+"\t1\t2\t1791838802000\tTEST_RPM=702");
        RotationManifestPrototype.ingestSession(dir,new WatermarkLedgerPrototype(ledger),env.reader(),null);
        DeploymentTransitionTest.privatize(home);
        return RotationManifestPrototype.read(dir,null).active.sourceUuid;
    }
    Set<String> sources()throws Exception {return DeploymentTransitionTest.sources(dir);}
    String sourcesCsv(){List<String> s;try{s=new ArrayList<String>(sources());}catch(Exception e){throw new IllegalStateException(e);}Collections.sort(s);StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(',');b.append(x);}return b.toString();}
    String manifestSha()throws Exception {return DeploymentTransition.fileSha(layout.manifest);}
    Map<String,String> tree()throws Exception {return Phase6PreflightTest.hashes(home);}
}
