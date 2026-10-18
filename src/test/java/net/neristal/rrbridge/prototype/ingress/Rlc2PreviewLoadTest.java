package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Bounded real-files/SQLite workload; no timing threshold, Server endpoint or ACK. */
public final class Rlc2PreviewLoadTest {
    private static int checks;
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static int setting(String name,int fallback,int min,int max) {
        String value=System.getenv(name);int n=value==null?fallback:Integer.parseInt(value);
        if(n<min||n>max)throw new IllegalArgumentException("bounded synthetic load setting required");return n;
    }
    private static long ms(long nanos){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);}
    private static Rlc2IntegrationStore.Admission admission(Workload w,Rlc2ProductAdapter.Projection p)throws Exception{
        java.util.Set<String> ids=new java.util.HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources)ids.add(s.uuid);
        return new Rlc2IntegrationStore.Admission(w.dir,p.manifestSession,ids);
    }
    private static final class Workload {
        final Path dir,ledger;final long writeMillis,ledgerMillis;
        Workload(Path d,Path l,long write,long ingest){dir=d;ledger=l;writeMillis=write;ledgerMillis=ingest;}
        Rlc2ProductAdapter.Projection project(Env env)throws Exception {
            return Rlc2ProductAdapter.project(true,dir,ledger.toFile(),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,env.reader());
        }
    }
    private static Workload create(Env env,String session,int observations)throws Exception {
        long start=System.nanoTime();Path dir=Files.createTempDirectory("romlogger-preview-load-");
        RotationManifestPrototype.begin(dir,env.reader(),env.platform());
        int split=observations/2;
        for(int source=1;source<=2;source++) {
            Path segment=RotationManifestPrototype.segment(dir,source);
            try(OwnedJournal w=env.openOwned(segment)) {
                if(source==1)w.append("M\t"+session+"\t1\tTEST_RPM\tSynthetic load RPM\trpm");
                for(int seq=source==1?0:split;seq<(source==1?split:observations);seq++)
                    w.append("S\t"+session+"\t1\t"+seq+"\t"+(1791838800000L+seq)+"\tTEST_RPM="+(700+seq%500));
                WatermarkV2Scenarios.publish(env,segment,w,WatermarkV2Prototype.NO_FAULT);
            }
            RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            if(source==1)RotationManifestPrototype.startNext(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        }
        long written=System.nanoTime();Path ledger=dir.resolve("ledger.db");
        WatermarkLedgerPrototype receiptStore=new WatermarkLedgerPrototype(ledger.toFile());
        check(RotationManifestPrototype.ingestSession(dir,receiptStore,env.reader(),null)==observations+1,"all synthetic source frames recorded once");
        return new Workload(dir,ledger,ms(written-start),ms(System.nanoTime()-written));
    }
    public static void main(String[] args)throws Exception {
        Env env=Rlc2ProductAdapterTest.environment();String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"load actual JVM architecture");
        int observations=setting("ROMLOGGER_LOAD_OBSERVATIONS",1000,2,20000);
        int additional=setting("ROMLOGGER_LOAD_SESSIONS",3,0,12);
        Workload large=create(env,"synthetic-load-main",observations);Map<String,String> originals=Rlc2PreviewStoreTest.hashes(large.dir);
        long start=System.nanoTime();Rlc2ProductAdapter.Projection p=large.project(env);long projectMillis=ms(System.nanoTime()-start);
        check(p.sources.size()==2&&p.receipts.size()==observations+1&&p.records.size()==observations&&p.profiles.size()==1,"bounded workload projects every committed record across physical rotation");
        check(p.records.get(0).sequence==0&&p.records.get(observations-1).sequence==observations-1,"run sequence spans the entire workload");
        File db=Files.createTempDirectory("romlogger-load-output-").resolve("load.rlc2-preview.db").toFile();
        start=System.nanoTime();Rlc2PreviewStore store=new Rlc2PreviewStore(true,db);Rlc2PreviewStore.Result first=store.ingest(p);long commitMillis=ms(System.nanoTime()-start);
        check(first.recordsAdded==observations&&first.receiptsAdded==observations+1&&!first.ackEligible,"large transaction retains all receipts and records, never ACK");
        start=System.nanoTime();Rlc2ProductAdapter.Projection fresh=large.project(env);Rlc2PreviewStore.Result retry=new Rlc2PreviewStore(true,db).ingest(fresh);long replayMillis=ms(System.nanoTime()-start);
        check(retry.recordsAdded==0&&retry.receiptsAdded==0,"fresh projection and reopened DB replay add no duplicates");
        check(fresh.records.get(0).id.equals(p.records.get(0).id)&&fresh.records.get(observations-1).json.equals(p.records.get(observations-1).json),"first and last product identities/content remain stable");
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(DISTINCT record_id) FROM preview_records")==observations,"real SQL verifies every distinct record");
        File candidateDb=Rlc2IntegrationStoreTest.target(),legacy=Rlc2IntegrationStoreTest.legacy();Rlc2IntegrationStore candidate=new Rlc2IntegrationStore(true,candidateDb);
        start=System.nanoTime();Rlc2PreviewStore.Result admitted=candidate.ingest(true,admission(large,p),large.dir,large.ledger.toFile(),legacy,p.device,p.vehicle,env.reader());long candidateMillis=ms(System.nanoTime()-start);
        check(admitted.recordsAdded==observations&&admitted.receiptsAdded==observations+1&&!admitted.ackEligible,"versioned candidate commits every large-workload source atomically");
        check(candidate.ingest(true,admission(large,p),large.dir,large.ledger.toFile(),legacy,p.device,p.vehicle,env.reader()).recordsAdded==0,"large admitted candidate replay is exact");
        for(int i=0;i<additional;i++) {
            Workload small=create(env,"synthetic-load-session-"+i,10);Map<String,String> before=Rlc2PreviewStoreTest.hashes(small.dir);
            check(store.ingest(small.project(env)).recordsAdded==10,"independent session "+i+" owns a distinct record namespace");
            Rlc2ProductAdapter.Projection smallProjection=small.project(env);
            check(candidate.ingest(true,admission(small,smallProjection),small.dir,small.ledger.toFile(),legacy,p.device,p.vehicle,env.reader()).recordsAdded==10,"versioned candidate retains independent session "+i);
            check(before.equals(Rlc2PreviewStoreTest.hashes(small.dir)),"session "+i+" input bytes unchanged");
        }
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_sessions")==1+additional&&Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_claims")==1+additional,"separate manifest/session claims persist independently");
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_sources")==2*(1+additional),"each session retains both physical source cursors");
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_records")==observations+10*additional&&Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_receipts")==observations+1+11*additional,"all session record/receipt totals agree with injected inputs");
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_sources s WHERE s.seq<>(SELECT count(*) FROM preview_receipts r WHERE r.source_uuid=s.source_uuid)")==0,"all source highwaters exactly match receipt extents");
        check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"no workload record becomes ACK-eligible");
        check(store.ingest(large.project(env)).recordsAdded==0,"other sessions do not disturb original session replay");
        check(originals.equals(Rlc2PreviewStoreTest.hashes(large.dir)),"large original journal/identity/marker/manifest/ledger bytes preserved");
        check(Rlc2PreviewStoreTest.count(candidateDb,"SELECT count(*) FROM preview_records")==observations+10*additional&&Rlc2PreviewStoreTest.count(candidateDb,"SELECT count(*) FROM preview_receipts")==observations+1+11*additional,"candidate aggregate rows and receipts exactly match preview workload");
        check(Rlc2PreviewStoreTest.count(candidateDb,"SELECT count(*) FROM integration_bindings")==1+additional&&Rlc2PreviewStoreTest.count(candidateDb,"SELECT count(*) FROM integration_admissions")==2*(1+additional),"candidate bindings and explicit admissions span all sessions");
        check(Rlc2PreviewStoreTest.count(candidateDb,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"large candidate never promotes ACK");
        System.out.println("CANDIDATE_LOAD_METRICS observations="+observations+" sessions="+(1+additional)+" snapshot_project_commit_ms="+candidateMillis);
        System.out.println("LOAD_METRICS observations="+observations+" sessions="+(1+additional)+" source_publish_ms="+large.writeMillis+" ledger_ms="+large.ledgerMillis+" project_ms="+projectMillis+" preview_commit_ms="+commitMillis+" replay_ms="+replayMillis+" java="+System.getProperty("java.version")+" bits="+System.getProperty("sun.arch.data.model"));
        System.out.println("RLC2 preview load "+env.name()+" passed "+checks+" checks; synthetic only; timings observational; ACK forbidden");
    }
}
