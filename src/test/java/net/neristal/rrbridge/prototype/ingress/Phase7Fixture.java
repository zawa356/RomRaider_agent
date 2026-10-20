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
    final Env env;final Path home,dir;final File ledger,legacy,output,root;
    final DeploymentBinding.Layout layout;final Rlc2IntegrationStore.Admission admission;
    Phase7Fixture(String payload)throws Exception {this(payload,1791838800000L);}
    Phase7Fixture(String payload,long t)throws Exception {
        env=Rlc2ProductAdapterTest.environment();
        home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase7-"));
        dir=DeploymentTransitionTest.session(env,home,"session",payload,t);
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
    String sourcesCsv(){List<String> s=new ArrayList<String>(admission.sourceUuids);Collections.sort(s);StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(',');b.append(x);}return b.toString();}
    String manifestSha()throws Exception {return DeploymentTransition.fileSha(layout.manifest);}
    Map<String,String> tree()throws Exception {return Phase6PreflightTest.hashes(home);}
}
