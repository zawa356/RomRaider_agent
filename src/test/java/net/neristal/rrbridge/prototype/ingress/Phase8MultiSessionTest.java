package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.neristal.rrbridge.product.v1.*;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Result;

/**
 * Phase VIII-2: many small logical Sessions through the single entry. Contract (RLMS1, see
 * docs/interfaces/offline_multi_session_v1.md): one Session = one owned root + one RLDB binding + one RLOP1 profile +
 * its own generation and candidate output. Every store is an actual supervisor + child JVM started from the pinned
 * profile. Not the Phase VI store-level 11-session load. Synthetic only; launch/production/ACK stay disabled.
 */
public final class Phase8MultiSessionTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static String[] args(Deployment d,String mode,String manifest,String sources){return new String[]{mode,d.profile.getPath(),manifest,sources};}
    static Set<String> ids(File db,String sql)throws Exception {
        Set<String> s=new TreeSet<String>();
        try(java.sql.Connection c=IngressDiagnostics.readOnly(db);java.sql.Statement st=c.createStatement();java.sql.ResultSet r=st.executeQuery(sql)){while(r.next())s.add(r.getString(1));}
        return s;
    }
    static void flags(Result r,Deployment d,String what)throws Exception {
        Phase7EntryTest.safe(r,d,what);
        check(r.json.contains("\"writer_quiescence\":\"WRITER_UNPROVEN\""),what+": WRITER_UNPROVEN kept");
    }
    static void refusedBeforeChild(Result r,Deployment d,Map<String,String> outputs,List<Deployment> all,String what)throws Exception {
        check(r.exit!=0&&r.json.contains("\"child_started\":false"),what+" refused before any child ["+r.exit+" "+OfflineCandidateEntry.field(r.json,"reason")+"]");
        flags(r,d,what);
        for(Deployment x:all)check(outputs.get(x.f.payload).equals(DeploymentTransition.fileSha(x.f.output)),what+": output of "+x.f.payload+" unchanged");
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        final List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());
        int n=Integer.parseInt(System.getenv().getOrDefault("ROMLOGGER_PHASE8_SESSIONS","10"));
        check(n>=10,"at least ten sessions requested ("+n+")");

        // ---- 1. N independent sessions, each stored once through the entry by an actual supervisor + child JVM.
        List<Deployment> all=new ArrayList<Deployment>();long t0=System.nanoTime();
        for(int i=0;i<n;i++){
            Phase7Fixture f=new Phase7Fixture(String.format(Locale.ROOT,"synthetic-phase8-s%02d",i),true,0);
            Deployment d=new Deployment(f,packaged,true,0);all.add(d);
            Result r=Phase7EntryTest.process(d,Phase7E2ETest.args(d,"--synthetic-owned"));
            check(r.exit==0&&r.json.contains("\"records_added\":3")&&r.json.contains("\"receipts_added\":4"),"session "+i+" stored through the entry (3 records / 4 receipts) ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
            flags(r,d,"session "+i);
        }
        System.out.println("TIMING first pass sessions="+n+" ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t0));

        // ---- 2. isolation: one run per output, all runs/records/sources distinct across sessions, owner roots distinct.
        Set<String> runs=new HashSet<String>(),records=new HashSet<String>(),sources=new HashSet<String>(),roots=new HashSet<String>();
        Map<String,Set<String>> recordsBy=new HashMap<String,Set<String>>();
        for(Deployment d:all){
            Set<String> run=ids(d.f.output,"SELECT DISTINCT run_id FROM preview_records"),rec=ids(d.f.output,"SELECT record_id FROM preview_records"),src=ids(d.f.output,"SELECT source_uuid FROM preview_sources");
            check(run.size()==1&&rec.size()==3&&src.size()==2,d.f.payload+": one run, three records, two sources");
            check(runs.addAll(run)&&Collections.disjoint(records,rec)&&Collections.disjoint(sources,src),d.f.payload+": nothing shared with any other session");
            records.addAll(rec);sources.addAll(src);recordsBy.put(d.f.payload,rec);
            check(roots.add(d.f.layout.root.getCanonicalPath()),d.f.payload+": owner root not shared");
            check(Phase7E2ETest.count(d.f.output,"SELECT COUNT(*) FROM preview_records WHERE ack_eligible<>0")==0,d.f.payload+": every record ACK-ineligible");
        }
        check(runs.size()==n&&records.size()==3*n,"distinct runs="+runs.size()+" records="+records.size());

        // ---- 3. restart: every session again with identical input adds nothing (new supervisor + child JVMs).
        Map<String,String> outputs=new HashMap<String,String>();Map<String,Set<String>> receipts=new HashMap<String,Set<String>>();
        for(Deployment d:all)receipts.put(d.f.payload,ids(d.f.output,"SELECT receipt_id FROM preview_receipts"));
        t0=System.nanoTime();
        for(Deployment d:all){
            Result r=Phase7EntryTest.process(d,Phase7E2ETest.args(d,"--synthetic-owned"));
            check(r.exit==0&&r.json.contains("\"records_added\":0")&&r.json.contains("\"receipts_added\":0"),d.f.payload+": restart adds nothing");
            check(receipts.get(d.f.payload).equals(ids(d.f.output,"SELECT receipt_id FROM preview_receipts"))&&recordsBy.get(d.f.payload).equals(ids(d.f.output,"SELECT record_id FROM preview_records")),d.f.payload+": receipt and record sets unchanged (0 duplicates)");
            outputs.put(d.f.payload,DeploymentTransition.fileSha(d.f.output));
        }
        System.out.println("TIMING restart pass sessions="+n+" ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t0));

        // ---- 4. cross-session leakage refused before any child: other session's Sources, other session's manifest.
        Deployment a=all.get(0),b=all.get(1);
        refusedBeforeChild(Phase7EntryTest.process(a,args(a,"--synthetic-owned",a.f.manifestSha(),b.f.sourcesCsv())),a,outputs,all,"session A profile with session B Sources");
        refusedBeforeChild(Phase7EntryTest.process(a,args(a,"--synthetic-owned",b.f.manifestSha(),a.f.sourcesCsv())),a,outputs,all,"session A profile with session B manifest");
        String both=a.f.sourcesCsv()+","+b.f.sourcesCsv();
        refusedBeforeChild(Phase7EntryTest.process(a,args(a,"--synthetic-owned",a.f.manifestSha(),both)),a,outputs,all,"session A profile admitting A+B Sources");
        // A profile forged to point at another session's owner root / data root.
        Deployment forgedRoot=Phase7EntryTest.forged(a,"owner_root",b.f.layout.root.getPath());
        refusedBeforeChild(Phase7EntryTest.process(forgedRoot,Phase7E2ETest.args(forgedRoot,"--synthetic-owned")),a,outputs,all,"profile forged to another session's owner root");
        Deployment forgedOut=Phase7EntryTest.forged(a,"layout.output",b.f.output.getPath());
        refusedBeforeChild(Phase7EntryTest.process(forgedOut,Phase7E2ETest.args(forgedOut,"--synthetic-owned")),a,outputs,all,"profile forged to another session's candidate output");

        // ---- 5. owner-root / lock-scope overlap: a second session cannot be bound into an existing owned root.
        DeploymentBinding.Layout overlap=new DeploymentBinding.Layout(a.f.layout.root,b.f.layout.legacy,b.f.layout.output,b.f.layout.session,b.f.layout.manifest,b.f.layout.ledger,
            b.f.layout.device,b.f.layout.vehicle,b.f.layout.payloadSession,b.f.layout.manifestSession);
        String bind;try{DeploymentBinding.bindSynthetic(true,true,overlap);bind="BOUND";}catch(Exception e){bind=e.getMessage();}
        check(!"BOUND".equals(bind),"binding session B into session A's owned root refused ["+bind+"]");
        String marker;try{OfflineCandidateEntry.createOwnedMarker(true,overlap,b.f.home.toFile());marker="CREATED";}catch(Exception e){marker=e.getMessage();}
        check(!"CREATED".equals(marker),"ownership marker for B over A's root refused ["+marker+"]");
        for(Deployment x:all)check(outputs.get(x.f.payload).equals(DeploymentTransition.fileSha(x.f.output)),"overlap attempts left "+x.f.payload+" unchanged");

        // ---- 6. two sessions concurrently (separate roots/lock scopes): both converge, neither blocks the other.
        final Phase7Fixture c1=new Phase7Fixture("synthetic-phase8-c1",true,0),c2=new Phase7Fixture("synthetic-phase8-c2",true,0);
        final Deployment d1=new Deployment(c1,packaged,true,0),d2=new Deployment(c2,packaged,true,0);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<Result> f1=pool.submit(new Callable<Result>(){public Result call()throws Exception{return Phase7EntryTest.process(d1,Phase7E2ETest.args(d1,"--synthetic-owned"));}});
        Future<Result> f2=pool.submit(new Callable<Result>(){public Result call()throws Exception{return Phase7EntryTest.process(d2,Phase7E2ETest.args(d2,"--synthetic-owned"));}});
        Result r1=f1.get(),r2=f2.get();pool.shutdown();
        check(r1.exit==0&&r2.exit==0&&r1.json.contains("\"records_added\":3")&&r2.json.contains("\"records_added\":3"),"two sessions stored concurrently through separate entries");
        check(Collections.disjoint(ids(c1.output,"SELECT record_id FROM preview_records"),ids(c2.output,"SELECT record_id FROM preview_records")),"concurrent sessions share no records");
        flags(r1,d1,"concurrent session 1");flags(r2,d2,"concurrent session 2");

        // ---- 7. originals never written.
        for(Deployment d:all)check(Phase7E2ETest.count(d.f.legacy,"SELECT COUNT(*) FROM v1_records")==0,d.f.payload+": legacy v1 untouched (no records written to it)");
        System.out.println("NOT_RUN aggregation of several session outputs into one candidate store (not part of RLMS1; RLDB1 binds one session per root)");
        System.out.println("Phase VIII multi-session checks="+checks);
    }
}
