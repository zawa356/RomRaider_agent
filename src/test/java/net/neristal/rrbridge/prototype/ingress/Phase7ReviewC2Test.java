package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.IntegratedReadiness.Status;

/**
 * Phase VII review closure VII-C2. The v1 schema has no foreign key or trigger tying v1_source_claims /
 * v1_journal_import_cursors to v1_records, so a v1 DB with claims and cursors but zero records is representable.
 * Before the fix the classifier projected run-level faults (cursor/claim) into conflicts only through record rows, and
 * readiness reported cursor_provenance PASS NO_LEGACY_V1_RECORDS for such a DB. Every case uses a fresh synthetic fixture.
 */
public final class Phase7ReviewC2Test {
    static int checks,red;
    static void check(boolean ok,String reason){
        if(!ok){red++;System.out.println("RED "+reason);return;}
        checks++;System.out.println("PASS "+reason);
    }
    static final String ZERO=String.join("",Collections.nCopies(64,"0"));

    static IntegratedReadiness.Report inspect(Phase6PreflightTest.Fixture f,File vault,File staged){
        return IntegratedReadiness.inspect(true,f.layout,0,f.manifest,f.admission,f.jars,f.expected,f.source.originals,vault,staged,f.source.env.reader(),IntegratedReadiness.NO_HOOK);
    }
    /** Mutate v1, then stage from the mutated v1 so staging_generation cannot mask the cursor axis. */
    static IntegratedReadiness.Report staged(String label,String... sql)throws Exception {
        Phase6PreflightTest.Fixture f=new Phase6PreflightTest.Fixture();
        if(sql.length>0)LegacyProvenanceClassifierTest.sql(f.source.v1,sql);
        File dir=DeploymentBindingTest.privateDir(Files.createDirectory(f.source.home.resolve("staging"))).toFile();
        File vault=new File(dir,"vault.db"),rli2=new File(dir,"candidate.rlc2-integration.db");
        LegacyProvenanceClassifier.stageIsolated(true,true,f.source.v1,f.source.originals,f.source.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
        IntegratedReadiness.Report r=inspect(f,vault,rli2);
        String j=r.json();
        check(j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")&&j.contains("\"production_authorized\":false")&&j.contains("\"ack_eligible\":false"),label+": readiness stays BLOCKED, no launch/production/ACK");
        return r;
    }
    static void axis(IntegratedReadiness.Report r,String label,Status status,String code){
        IntegratedReadiness.Axis a=r.axis("cursor_provenance");
        check(a.status==status&&(code==null||a.code.equals(code)),label+": cursor_provenance "+status+(code==null?"":" "+code)+" [actual "+a.status+" "+a.code+"]");
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);

        // Control: the unmodified fixture still passes every content axis.
        IntegratedReadiness.Report ok=staged("control");
        axis(ok,"control",Status.PASS,"CLAIMS_AND_CURSORS_MATCH_ORIGINALS");
        check(ok.contentComplete(),"control: content axes complete");

        // Control: a genuinely empty v1 (no records, claims or cursors) keeps NO_LEGACY_V1_RECORDS.
        IntegratedReadiness.Report empty=staged("empty v1","DELETE FROM v1_records","DELETE FROM v1_ack_receipts","DELETE FROM v1_source_claims","DELETE FROM v1_journal_import_cursors");
        axis(empty,"empty v1",Status.PASS,"NO_LEGACY_V1_RECORDS");

        // H1: claim + valid cursor survive, records gone. Cursor says sequence>=1 was imported, yet nothing is in v1.
        IntegratedReadiness.Report h1=staged("H1 records deleted","DELETE FROM v1_records","DELETE FROM v1_ack_receipts");
        axis(h1,"H1 claim+cursor without records",Status.UNKNOWN,"LEGACY_CLAIMS_WITHOUT_RECORDS");
        check(!h1.contentComplete(),"H1: claim+cursor without records blocks content completeness");

        // H2: records gone and the cursor chain tampered: the cursor fault must surface, not vanish with the rows.
        IntegratedReadiness.Report h2=staged("H2 tampered cursor","DELETE FROM v1_records","DELETE FROM v1_ack_receipts","UPDATE v1_journal_import_cursors SET chain_sha256='"+ZERO+"'");
        axis(h2,"H2 tampered cursor without records",Status.MISMATCH,"CURSOR_CHAIN_MISMATCH");
        check(!h2.contentComplete(),"H2: tampered cursor without records blocks content completeness");

        // H3: cursor with no claim at all and no records (orphan cursor).
        IntegratedReadiness.Report h3=staged("H3 orphan cursor","DELETE FROM v1_records","DELETE FROM v1_ack_receipts","DELETE FROM v1_source_claims");
        axis(h3,"H3 orphan cursor without claim or records",Status.MISMATCH,"ORPHAN_CURSOR");
        check(!h3.contentComplete(),"H3: orphan cursor blocks content completeness");

        // H4: claim only (no cursor, no records). A claimed journal run with nothing imported is not proof of absence.
        IntegratedReadiness.Report h4=staged("H4 claim only","DELETE FROM v1_records","DELETE FROM v1_ack_receipts","DELETE FROM v1_journal_import_cursors");
        check(h4.axis("cursor_provenance").status!=Status.PASS,"H4: claim without cursor or records is not PASS [actual "+h4.axis("cursor_provenance").status+" "+h4.axis("cursor_provenance").code+"]");

        System.out.println("Phase VII review C2 checks="+checks+" red="+red);
        if(red>0)System.exit(1);
    }
}
