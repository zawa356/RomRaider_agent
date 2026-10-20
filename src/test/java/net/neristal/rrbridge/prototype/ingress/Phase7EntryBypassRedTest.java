package net.neristal.rrbridge.prototype.ingress;

import java.util.*;

/**
 * Phase VII-0 review RED. OfflineCandidatePreflight approves Rlc2IntegrationCli as its only known offline entry, but that
 * CLI writes through the unbound ingest path: no RLDB/RLDT generation check, no Lane/Publication lock, self-asserted
 * quiescence. A preflight refusal (actual child JVM holding the Lane or Publication lock) therefore does not stop the approved
 * entry from writing the candidate DB. Invocation-owned synthetic fixtures only; no Sender, ACK or existing DB.
 */
public final class Phase7EntryBypassRedTest {
    static int checks,failures;
    static void expect(boolean ok,String reason){if(ok){checks++;System.out.println("PASS "+reason);}else{failures++;System.out.println("RED "+reason);}}
    static String[] cliArgs(Phase7Fixture f){
        return new String[]{"--enable-synthetic-integration","--quiescent",f.layout.session.getPath(),f.layout.ledger.getPath(),f.layout.legacy.getPath(),
            f.layout.device,f.layout.vehicle,f.layout.output.getPath(),f.layout.manifestSession,f.sourcesCsv()};
    }
    static OfflineCandidatePreflight.Report preflight(Phase7Fixture f)throws Exception {
        java.io.File app=Phase6PreflightTest.jar(f.home.resolve("app-"+UUID.randomUUID()+".jar"),true);
        List<java.io.File> jars=Collections.singletonList(app);
        return OfflineCandidatePreflight.inspect(true,f.layout,0,f.manifestSha(),f.admission,jars,ArtifactProvenanceAccess.describe(jars),Long.MAX_VALUE,f.env.reader());
    }
    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        Phase7Fixture control=new Phase7Fixture("synthetic-phase7-red-control");
        expect("WRITER_UNPROVEN".equals(preflight(control).reason),"control preflight matches prerequisites and still blocks");
        expect(Rlc2IntegrationCli.run(cliArgs(control),control.env.reader())==0,"control: approved entry stores without competitor");
        for(String kind:new String[]{"lane","publication"}){
            Phase7Fixture f=new Phase7Fixture("synthetic-phase7-red-"+kind);
            String before=DeploymentTransition.fileSha(f.output);int exit;String reason;
            try(Phase6PreflightTest.Held held=new Phase6PreflightTest.Held(f.layout,kind)){
                reason=preflight(f).reason;
                exit=Rlc2IntegrationCli.run(cliArgs(f),f.env.reader());
            }
            expect(!reason.equals("WRITER_UNPROVEN"),"preflight refuses while child holds "+kind+" ["+reason+"]");
            expect(exit!=0,"approved offline entry refuses while child holds "+kind+" [exit="+exit+"]");
            expect(before.equals(DeploymentTransition.fileSha(f.output)),"candidate output unchanged while "+kind+" is held");
        }
        System.out.println("Phase VII entry bypass checks="+checks+" red="+failures);
        if(failures>0)System.exit(1);
    }
    /** Description of the approved preflight main class from the given JARs. */
    static final class ArtifactProvenanceAccess {
        static Map<String,String> describe(List<java.io.File> jars)throws Exception {return net.neristal.rrbridge.product.v1.ArtifactProvenance.describe(jars,OfflineCandidatePreflight.MAIN);}
    }
}
