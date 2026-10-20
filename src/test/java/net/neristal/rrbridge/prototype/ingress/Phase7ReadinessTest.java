package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.IntegratedReadiness.Status;

/**
 * Phase VII-2 RLIR1 integrated readiness. Fresh Phase V provenance fixtures (v1 claiming the session with cursor and one
 * historical ACK, real original + ledger), RLPC1 staged Vault/RLI2, bound RLDB1 deployment. Mutations are applied to copies
 * or to dedicated fixtures; every run must leave the fixture tree unchanged and never authorize launch/production/ACK.
 */
public final class Phase7ReadinessTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final class Staged {
        final Phase6PreflightTest.Fixture f;final File dir,vault,rli2;
        Staged()throws Exception {
            f=new Phase6PreflightTest.Fixture();
            dir=DeploymentBindingTest.privateDir(Files.createDirectory(f.source.home.resolve("staging"))).toFile();
            vault=new File(dir,"vault.db");rli2=new File(dir,"candidate.rlc2-integration.db");
            LegacyProvenanceClassifier.stageIsolated(true,true,f.source.v1,f.source.originals,f.source.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
            check("STAGED_LOCAL_HASHES_MATCH_INACTIVE".equals(LegacyProvenanceClassifier.stagingState(vault,rli2)),"fixture staging completed");
        }
        IntegratedReadiness.Report inspect(List<LegacyProvenanceClassifier.Original> originals,File v,File r,String manifest,IntegratedReadiness.Hook hook){
            return IntegratedReadiness.inspect(true,f.layout,0,manifest,f.admission,f.jars,f.expected,originals,v,r,f.source.env.reader(),hook);
        }
        IntegratedReadiness.Report inspect(File v,File r){return inspect(f.source.originals,v,r,f.manifest,IntegratedReadiness.NO_HOOK);}
        IntegratedReadiness.Report inspect(){return inspect(vault,rli2);}
        /** Copy of the staged triple into a new private directory (same basenames). */
        File[] copy()throws Exception {
            Path d=DeploymentBindingTest.privateDir(Files.createDirectory(f.source.home.resolve("copy-"+UUID.randomUUID())));
            for(File x:new File[]{vault,LegacyProvenanceClassifier.marker(vault),rli2})Files.copy(x.toPath(),d.resolve(x.getName()));
            return new File[]{d.resolve(vault.getName()).toFile(),d.resolve(rli2.getName()).toFile()};
        }
        /** An adversary who edits a staged file and re-hashes the completion marker: only exact row checks can catch it. */
        static void rehash(File v,File r)throws Exception {
            Path m=LegacyProvenanceClassifier.marker(v).toPath();Map<String,String> rec=DeploymentBinding.readRecord(m);
            rec.put("vault_sha256",DeploymentTransition.fileSha(v));rec.put("rli2_sha256",DeploymentTransition.fileSha(r));
            Files.delete(m);DeploymentBinding.createRecord(m,DeploymentBinding.encodeRecord(rec));
        }
    }
    static void axis(IntegratedReadiness.Report r,String name,Status status,String code){
        IntegratedReadiness.Axis a=r.axis(name);
        check(a.status==status&&(code==null||a.code.equals(code)),name+" "+status+(code==null?"":" "+code)+" ["+a.status+" "+a.code+"]");
    }
    static void safe(IntegratedReadiness.Report r,Staged s){
        String j=r.json();
        check(j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")&&j.contains("\"production_authorized\":false")&&j.contains("\"ack_eligible\":false")&&!j.contains("\"launch_authorized\":true"),"readiness stays BLOCKED, no launch/production/ACK");
        check(r.axis("writer_quiescence").status==Status.BLOCKED&&r.axis("process_provenance").status==Status.UNKNOWN&&r.axis("directory_durability").status==Status.UNKNOWN&&r.axis("formal_ack").status==Status.DISABLED&&r.axis("production_send").status==Status.DISABLED,"unprovable axes never promoted");
        check(!j.contains(s.f.source.home.toString())&&!j.contains(s.f.source.home.toString().replace("\\","\\\\"))&&!j.contains(s.f.layout.manifestSession)&&!j.contains("TEST_RPM"),"JSON omits paths, session IDs and payload");
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        IntegratedReadiness.Report off=IntegratedReadiness.inspect(false,null,0,null,null,null,null,null,null,null,null,null);
        check("READINESS_DISABLED".equals(off.reason)&&off.axis("vault_integrity").status==Status.NOT_RUN,"disabled readiness inspects nothing");

        final Staged s=new Staged();final Map<String,String> tree=Phase6PreflightTest.hashes(s.f.source.home);
        // ---- GREEN: every content axis actually ran and passed; still BLOCKED.
        IntegratedReadiness.Report ok=s.inspect();
        check("WRITER_UNPROVEN".equals(ok.reason)&&ok.contentComplete(),"all content axes PASS ["+ok.reason+"] "+ok.json());
        for(String a:new String[]{"deployment_manifest","identity_admission","cooperative_locks","artifact_provenance","cursor_provenance","legacy_ack_set","vault_integrity","staged_rli2_integrity","record_receipt_admission","staging_generation"})axis(ok,a,Status.PASS,null);
        safe(ok,s);
        check(tree.equals(Phase6PreflightTest.hashes(s.f.source.home)),"read-only readiness preserves the whole fixture tree");
        check(!Files.exists(Paths.get(s.f.source.v1.getPath()+"-wal"))&&!Files.exists(Paths.get(s.rli2.getPath()+"-shm"))&&!Files.exists(Paths.get(s.vault.getPath()+"-journal")),"no WAL/SHM/journal created");

        // ---- missing evidence fails closed and is never generated.
        IntegratedReadiness.Report none=s.inspect(null,null);
        check("STAGED_EVIDENCE_MISSING".equals(none.reason)&&!none.contentComplete(),"no staging: STAGED_EVIDENCE_MISSING");
        axis(none,"vault_integrity",Status.BLOCKED,"STAGED_EVIDENCE_MISSING");axis(none,"cursor_provenance",Status.PASS,null);safe(none,s);
        {File[] c=s.copy();Files.delete(LegacyProvenanceClassifier.marker(c[0]).toPath());
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);check("REQUIRED_EVIDENCE_MISSING".equals(r.reason)&&!r.contentComplete(),"missing completion marker fails closed ["+r.reason+"]");
         check(!Files.exists(LegacyProvenanceClassifier.marker(c[0]).toPath()),"missing marker is not generated");}

        // ---- tampered staged evidence with a re-hashed marker.
        {File[] c=s.copy();LegacyProvenanceClassifierTest.sql(c[0],"UPDATE vault_records SET reason='EDITED' WHERE v1_local_id=2");Staged.rehash(c[0],c[1]);
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);axis(r,"vault_integrity",Status.MISMATCH,null);axis(r,"legacy_ack_set",Status.PASS,null);check(!r.contentComplete(),"Vault one-column substitution blocks completeness");safe(r,s);}
        {File[] c=s.copy();LegacyProvenanceClassifierTest.sql(c[0],"UPDATE vault_records SET v1_acked=0,v1_acked_batch_id=NULL,v1_acked_at=NULL WHERE v1_acked=1");Staged.rehash(c[0],c[1]);
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);axis(r,"legacy_ack_set",Status.MISMATCH,"V1_ACK_SET_DIFFERS");axis(r,"vault_integrity",Status.MISMATCH,null);safe(r,s);}
        {File[] c=s.copy();LegacyProvenanceClassifierTest.sql(c[1],"INSERT INTO preview_profiles VALUES('phase7-extra','{}')");Staged.rehash(c[0],c[1]);
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);axis(r,"record_receipt_admission",Status.MISMATCH,"RLI2_ROWS_DIFFER_PREVIEW_PROFILES");axis(r,"vault_integrity",Status.PASS,null);safe(r,s);}
        {File[] c=s.copy();LegacyProvenanceClassifierTest.sql(c[1],"INSERT INTO preview_profiles VALUES('phase7-extra','{}')");
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);axis(r,"staging_generation",Status.MISMATCH,"STAGED_EVIDENCE_MISMATCH");axis(r,"record_receipt_admission",Status.BLOCKED,"STAGED_EVIDENCE_MISMATCH");}
        {File[] c=s.copy();Path m=LegacyProvenanceClassifier.marker(c[0]).toPath();Map<String,String> rec=DeploymentBinding.readRecord(m);rec.put("v1_sha256",String.join("",Collections.nCopies(64,"a")));Files.delete(m);DeploymentBinding.createRecord(m,DeploymentBinding.encodeRecord(rec));
         IntegratedReadiness.Report r=s.inspect(c[0],c[1]);axis(r,"staging_generation",Status.MISMATCH,"STAGED_FOR_DIFFERENT_V1");axis(r,"vault_integrity",Status.BLOCKED,"STAGED_FOR_DIFFERENT_V1");}

        // ---- identity: the same original supplied twice (Source UUID reuse for one run).
        {List<LegacyProvenanceClassifier.Original> twice=new ArrayList<LegacyProvenanceClassifier.Original>(s.f.source.originals);twice.addAll(s.f.source.originals);
         IntegratedReadiness.Report r=s.inspect(twice,s.vault,s.rli2,s.f.manifest,IntegratedReadiness.NO_HOOK);axis(r,"identity_admission",Status.MISMATCH,"ORIGINAL_DUPLICATED_FOR_RUN");check(!r.contentComplete(),"duplicate original blocks completeness");}
        // ---- wrong manifest expectation and a held Lane: other axes still run and report.
        {IntegratedReadiness.Report r=s.inspect(s.f.source.originals,s.vault,s.rli2,String.join("",Collections.nCopies(64,"0")),IntegratedReadiness.NO_HOOK);
         axis(r,"deployment_manifest",Status.MISMATCH,"MANIFEST_EXPECTATION_MISMATCH");axis(r,"vault_integrity",Status.PASS,null);}
        try(Phase6PreflightTest.Held held=new Phase6PreflightTest.Held(s.f.layout,"lane")){
            IntegratedReadiness.Report r=s.inspect();axis(r,"cooperative_locks",Status.BLOCKED,"LANE_NOT_OBSERVED_IDLE");axis(r,"staged_rli2_integrity",Status.PASS,null);check(!r.contentComplete(),"held Lane blocks completeness");
        }

        // ---- change during diagnosis: sidecar creation, staged byte change, staging parent replacement.
        {final File[] c=s.copy();
         IntegratedReadiness.Report r=s.inspect(s.f.source.originals,c[0],c[1],s.f.manifest,new IntegratedReadiness.Hook(){public void at(String stage)throws Exception{if(stage.equals("CLASSIFIED"))Files.write(c[1].toPath(),new byte[]{0},StandardOpenOption.APPEND);}});
         check("INPUT_CHANGED_DURING_DIAGNOSIS".equals(r.reason)&&!r.contentComplete(),"staged RLI2 appended mid-diagnosis stops ["+r.reason+"]");}
        {final Path journal=Paths.get(s.f.source.v1.getPath()+"-journal");
         IntegratedReadiness.Report r=s.inspect(s.f.source.originals,s.vault,s.rli2,s.f.manifest,new IntegratedReadiness.Hook(){public void at(String stage)throws Exception{if(stage.equals("PREFLIGHT_DONE"))Files.write(journal,new byte[0],StandardOpenOption.CREATE_NEW);}});
         check("INPUT_CHANGED_DURING_DIAGNOSIS".equals(r.reason),"legacy hot journal appearing mid-diagnosis stops ["+r.reason+"]");
         check(Files.size(journal)==0,"diagnosis did not touch the hot journal");Files.delete(journal);}
        {final File[] c=s.copy();final Path parent=c[0].toPath().getParent();
         IntegratedReadiness.Report r=s.inspect(s.f.source.originals,c[0],c[1],s.f.manifest,new IntegratedReadiness.Hook(){public void at(String stage)throws Exception{
            if(!stage.equals("VAULT_CHECKED"))return;Path moved=parent.resolveSibling(parent.getFileName()+"-moved");Files.move(parent,moved);
            DeploymentBindingTest.privateDir(Files.createDirectory(parent));for(String n:new String[]{c[0].getName(),c[0].getName()+".rlpc1-staged",c[1].getName()})Files.copy(moved.resolve(n),parent.resolve(n));}});
         check("INPUT_CHANGED_DURING_DIAGNOSIS".equals(r.reason),"staging parent replaced by identical copies mid-diagnosis stops ["+r.reason+"]");}

        // ---- legacy v1 mutated after staging (cursor tamper): cursor MISMATCH and staging is for a different v1.
        {Staged t=new Staged();
         LegacyProvenanceClassifierTest.sql(t.f.source.v1,"UPDATE v1_journal_import_cursors SET chain_sha256='"+String.join("",Collections.nCopies(64,"0"))+"'");
         IntegratedReadiness.Report r=t.inspect();axis(r,"cursor_provenance",Status.MISMATCH,"CURSOR_CHAIN_MISMATCH");axis(r,"staging_generation",Status.MISMATCH,"STAGED_FOR_DIFFERENT_V1");
         check(!r.contentComplete(),"post-staging cursor tamper blocks completeness");safe(r,t);}

        Map<String,String> after=Phase6PreflightTest.hashes(s.f.source.home);Set<String> changed=new TreeSet<String>();
        for(Map.Entry<String,String> e:tree.entrySet())if(!e.getValue().equals(after.get(e.getKey())))changed.add(e.getKey());
        // The actual child competitor legitimately rewrote its own Lane lock generation; nothing else may differ.
        String lane=UUID.nameUUIDFromBytes((s.f.layout.device+"|"+s.f.layout.vehicle+"|"+s.f.layout.payloadSession).getBytes(java.nio.charset.StandardCharsets.UTF_8))+".lane.lock";
        check(changed.equals(Collections.singleton("root"+File.separator+lane)),"original fixture files unchanged except the competitor's own Lane lock "+changed);
        System.out.println("Phase VII readiness checks="+checks);
    }
}
