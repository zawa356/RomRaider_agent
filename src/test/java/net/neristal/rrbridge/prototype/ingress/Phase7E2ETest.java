package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Result;

/**
 * Phase VII-3 single synthetic-owned E2E through the one Offline Candidate Entry. Every step is a real JVM started from the
 * pinned profile (supervisor) which starts its own child. Flow: owned multi-source session → diagnose → store → restart/same
 * input → legitimate rotation refused until an RLDT1 transition admits the new Source → re-pin → store the new Source →
 * forced kill of the owned child and explicit retry → final diagnose + RLIR1 readiness → HTTP fixture handoff.
 * Original v1 bytes and ACK state never change; formal ACK and production stay disabled.
 *
 * Args: packaged JARs (see scripts/test-phase7-e2e.sh). Optional env ROMLOGGER_PHASE7_LOAD_OBSERVATIONS for a large session.
 */
public final class Phase7E2ETest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static final String PURPOSE="Phase VII synthetic-owned rotation admission";
    static long count(File db,String sql)throws Exception {return Rlc2PreviewStoreTest.count(db,sql);}
    static String[] args(Deployment d,String mode)throws Exception {return new String[]{mode,d.profile.getPath(),d.f.manifestSha(),d.f.sourcesCsv()};}
    static String legacyState(Phase7Fixture f)throws Exception {
        return DeploymentTransition.fileSha(f.legacy)+"|records="+count(f.legacy,"SELECT COUNT(*) FROM v1_records")+"|acks="+count(f.legacy,"SELECT COUNT(*) FROM v1_ack_receipts")+"|claims="+count(f.legacy,"SELECT COUNT(*) FROM v1_source_claims");
    }
    static String idsSha(File db,String sql)throws Exception {
        StringBuilder b=new StringBuilder();
        try(java.sql.Connection c=IngressDiagnostics.readOnly(db);java.sql.Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery(sql)){while(r.next())b.append(r.getString(1)).append('\n');}
        return DeploymentTransition.sha(b.toString().getBytes(StandardCharsets.UTF_8));
    }
    static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());

        // ---- 1. owned deployment, read-only diagnose, explicit store (two physical sources, one logical session).
        Phase7Fixture f=new Phase7Fixture("synthetic-phase7-e2e",true,0);
        Deployment d=new Deployment(f,packaged,true,0);final String legacy=legacyState(f);
        Result diag=Phase7EntryTest.process(d,args(d,"--diagnose"));
        check(diag.exit==5&&diag.json.contains("\"prerequisites_matched\":true")&&diag.json.contains("\"state\":\"BLOCKED\""),"E2E diagnose before store: matched and BLOCKED");
        long t=System.nanoTime();Result first=Phase7EntryTest.process(d,args(d,"--synthetic-owned"));long firstMs=elapsed(t);
        check(first.exit==0&&first.json.contains("\"records_added\":3")&&first.json.contains("\"receipts_added\":4"),"generation 0: two sources store three records / four receipts ["+OfflineCandidateEntry.field(first.json,"reason")+"]");
        check(count(f.output,"SELECT COUNT(DISTINCT run_id) FROM preview_records")==1&&count(f.output,"SELECT COUNT(*) FROM integration_admissions")==2,"two physical sources stay one logical run with two admissions");
        Phase7EntryTest.safe(first,d,"generation 0 store");

        // ---- 2. restart with identical input: new supervisor + child JVMs, zero duplicates, no cursor rewind.
        String receiptsBefore=idsSha(f.output,"SELECT receipt_id FROM preview_receipts ORDER BY receipt_id");
        Result again=Phase7EntryTest.process(d,args(d,"--synthetic-owned"));
        check(again.exit==0&&again.json.contains("\"records_added\":0")&&again.json.contains("\"receipts_added\":0"),"restart + same input adds nothing");
        check(receiptsBefore.equals(idsSha(f.output,"SELECT receipt_id FROM preview_receipts ORDER BY receipt_id"))&&count(f.output,"SELECT COUNT(*) FROM preview_records")==3,"receipt set and record count unchanged (no rewind/duplicate)");

        // ---- 3. legitimate rotation: the old pinned generation must refuse until the new Source is admitted by RLDT1.
        String out3=DeploymentTransition.fileSha(f.output);String seg3=f.rotate();
        Result stale=Phase7EntryTest.process(d,args(d,"--synthetic-owned"));
        check(stale.exit!=0&&stale.json.contains("\"child_started\":false"),"rotated session refused under the old binding before any child ["+OfflineCandidateEntry.field(stale.json,"reason")+"]");
        check(out3.equals(DeploymentTransition.fileSha(f.output)),"refused rotation leaves candidate output unchanged");
        Set<String> admitted=Collections.singleton(seg3);
        DeploymentTransition.Plan plan=DeploymentTransition.plan(true,true,f.layout,f.layout,admitted,PURPOSE,f.env.reader());
        check(DeploymentTransition.commitSynthetic(true,true,f.layout,f.layout,admitted,PURPOSE,plan.digest,f.env.reader(),DeploymentTransition.NO_FAULT)==1,"RLDT1 transition commits generation 1 admitting only the new Source");
        DeploymentTransitionTest.privatize(f.home);
        Result oldPin=Phase7EntryTest.process(d,args(d,"--synthetic-owned"));
        check(oldPin.exit!=0&&oldPin.json.contains("DEPLOYMENT_GENERATION_MISMATCH"),"profile pinned to generation 0 refused after transition");
        Deployment d1=d.repin(1);
        Result grown=Phase7EntryTest.process(d1,args(d1,"--synthetic-owned"));
        check(grown.exit==0&&grown.json.contains("\"records_added\":1")&&grown.json.contains("\"receipts_added\":1"),"re-pinned generation 1 stores exactly the admitted new Source ["+OfflineCandidateEntry.field(grown.json,"reason")+"]");
        check(count(f.output,"SELECT COUNT(*) FROM preview_sources")==3&&count(f.output,"SELECT COUNT(*) FROM preview_receipts")==5&&count(f.output,"SELECT COUNT(*) FROM preview_records")==4&&count(f.output,"SELECT COUNT(*) FROM integration_admissions")==3,"one session, three sources, five receipts, four records, three admissions");
        check(count(f.output,"SELECT COUNT(*) FROM preview_records WHERE ack_eligible<>0")==0,"every candidate record stays ACK-ineligible");
        check(legacy.equals(legacyState(f)),"original v1 bytes, records, ACK receipts and claims unchanged across the flow");

        // ---- 4. forced kill of the owned child (by its self-reported PID) and explicit retry, on a fresh deployment.
        {
            Phase7Fixture k=new Phase7Fixture("synthetic-phase7-kill",true,0);Deployment dk=new Deployment(k,packaged,true,0);String kLegacy=legacyState(k);
            Map<String,String> r=dk.record();List<String> command=new ArrayList<String>(Arrays.asList(r.get("java_executable"),"-cp",r.get("classpath"),OfflineCandidateEntry.MAIN));
            command.addAll(Arrays.asList(args(dk,"--synthetic-owned")));
            Phase7EntryTest.requireWork(dk);
            Process supervisor=new ProcessBuilder(command).directory(dk.work).redirectErrorStream(true).start();supervisor.getOutputStream().close();
            final ByteArrayOutputStream out=new ByteArrayOutputStream();final InputStream in=supervisor.getInputStream();
            Thread drain=new Thread(new Runnable(){public void run(){byte[] b=new byte[4096];int n;try{while((n=in.read(b))!=-1)synchronized(out){out.write(b,0,n);}}catch(IOException e){}}});drain.start();
            Path runs=k.home.resolve("owned-runs");String pid=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            while(pid==null&&System.nanoTime()<end&&supervisor.isAlive()){
                File[] started=runs.toFile().listFiles(new FileFilter(){public boolean accept(File x){return x.getName().endsWith(".started");}});
                if(started!=null&&started.length==1){String text=new String(Files.readAllBytes(started[0].toPath()),StandardCharsets.US_ASCII).trim();if(text.matches("pid=[0-9]+"))pid=text.substring(4);}
                else Thread.sleep(2);
            }
            check(pid!=null,"owned child started and published its one-shot start record");
            boolean windows=System.getProperty("os.name").startsWith("Windows");
            Process kill=new ProcessBuilder(windows?Arrays.asList(new File(System.getenv("SystemRoot"),"System32\\taskkill.exe").getPath(),"/F","/PID",pid):Arrays.asList("kill","-9",pid)).redirectErrorStream(true).start();
            kill.getInputStream().close();check(kill.waitFor(60,TimeUnit.SECONDS),"kill command finished");
            check(supervisor.waitFor(300,TimeUnit.SECONDS),"supervisor returned after child kill");drain.join(10000);
            String text;synchronized(out){text=new String(out.toByteArray(),StandardCharsets.UTF_8).trim();}String last=text.split("\r?\n")[text.split("\r?\n").length-1];
            long afterKill=count(k.output,"SELECT COUNT(*) FROM preview_records");
            System.out.println("KILL child_pid_kill_exit="+kill.exitValue()+" supervisor_exit="+supervisor.exitValue()+" records_after_kill="+afterKill+" reason="+OfflineCandidateEntry.field(last,"reason"));
            check(supervisor.exitValue()==0?afterKill==3:afterKill==0||afterKill==3,"killed run is atomic: zero or all three records, never partial ["+afterKill+"]");
            check(supervisor.exitValue()!=0||last.contains("STORED_SYNTHETIC_OWNED"),"supervisor reports the killed child honestly (no STORED claim without child exit 0)");
            check(count(k.output,"SELECT COUNT(*) FROM preview_receipts")==(afterKill==3?4:0),"receipts follow the same atomic outcome");
            Result retry=Phase7EntryTest.process(dk,args(dk,"--synthetic-owned"));
            check(retry.exit==0&&count(k.output,"SELECT COUNT(*) FROM preview_records")==3&&count(k.output,"SELECT COUNT(*) FROM preview_receipts")==4,"explicit retry converges on exactly three records / four receipts");
            check(kLegacy.equals(legacyState(k)),"kill/retry: original v1 bytes and ACK state unchanged");
        }

        // ---- 5. optional large session through the same entry (one large logical run).
        String loadEnv=System.getenv("ROMLOGGER_PHASE7_LOAD_OBSERVATIONS");long loadMs=-1;int load=loadEnv==null?0:Integer.parseInt(loadEnv);
        if(load>0){
            Phase7Fixture big=new Phase7Fixture("synthetic-phase7-load",true,load);Deployment db=new Deployment(big,packaged,true,0);
            long s=System.nanoTime();Result r=Phase7EntryTest.process(db,args(db,"--synthetic-owned"));loadMs=elapsed(s);
            check(r.exit==0&&count(big.output,"SELECT COUNT(*) FROM preview_records")==load+3,"large session stores "+(load+3)+" records through the entry [exit="+r.exit+" "+r.json+"]");
            Result rr=Phase7EntryTest.process(db,args(db,"--synthetic-owned"));
            check(rr.exit==0&&rr.json.contains("\"records_added\":0")&&count(big.output,"SELECT COUNT(*) FROM preview_records")==load+3,"large session rerun adds zero duplicates");
        }

        // ---- 6. final read-only diagnose + RLIR1 readiness; JSON summary; HTTP harness handoff.
        Result fin=Phase7EntryTest.process(d1,args(d1,"--diagnose"));
        check(fin.exit==5&&fin.json.contains("\"prerequisites_matched\":true"),"final diagnose at generation 1: matched, BLOCKED");
        List<File> jars=d1.jars;
        IntegratedReadiness.Report readiness=IntegratedReadiness.inspect(true,f.layout,1,f.manifestSha(),new Rlc2IntegrationStore.Admission(f.dir,f.layout.manifestSession,f.sources()),
            jars,ArtifactProvenance.describe(jars,OfflineCandidateEntry.MAIN),Collections.<LegacyProvenanceClassifier.Original>emptyList(),null,null,f.env.reader(),IntegratedReadiness.NO_HOOK);
        check("STAGED_EVIDENCE_MISSING".equals(readiness.reason)&&readiness.axis("deployment_manifest").status==IntegratedReadiness.Status.PASS&&readiness.axis("identity_admission").status==IntegratedReadiness.Status.PASS
            &&readiness.axis("cursor_provenance").code.equals("NO_LEGACY_V1_RECORDS")&&!readiness.contentComplete(),"final readiness: deployment/admission PASS, no legacy records, no staging → not complete");
        check(!readiness.json().contains("\"launch_authorized\":true"),"final readiness never authorizes");
        Map<String,String> summary=new TreeMap<String,String>();
        summary.put("records",String.valueOf(count(f.output,"SELECT COUNT(*) FROM preview_records")));summary.put("receipts",String.valueOf(count(f.output,"SELECT COUNT(*) FROM preview_receipts")));
        summary.put("sources",String.valueOf(count(f.output,"SELECT COUNT(*) FROM preview_sources")));summary.put("active_generation","1");
        summary.put("record_ids_sha256",idsSha(f.output,"SELECT record_id FROM preview_records ORDER BY record_id"));
        summary.put("receipt_ids_sha256",idsSha(f.output,"SELECT receipt_id FROM preview_receipts ORDER BY receipt_id"));
        summary.put("first_store_ms",String.valueOf(firstMs));summary.put("load_observations",String.valueOf(load));summary.put("load_store_ms",String.valueOf(loadMs));
        summary.put("formal_ack","false");summary.put("production_authorized","false");summary.put("launch_authorized","false");
        System.out.println("E2E_SUMMARY "+summary);
        System.out.println("E2E_READINESS "+readiness.json());
        File handoff=new File("build/rlc2-integration-fixture.path");handoff.getAbsoluteFile().getParentFile().mkdirs();
        Files.write(handoff.toPath(),f.output.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        System.out.println("Phase VII E2E checks="+checks);
    }
}
