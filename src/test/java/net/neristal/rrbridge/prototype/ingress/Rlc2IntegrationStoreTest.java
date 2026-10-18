package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import net.neristal.rrbridge.product.v1.DurableSpool;

public final class Rlc2IntegrationStoreTest {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    interface Action{void run()throws Exception;}
    static void rejects(Action a,String s)throws Exception{try{a.run();}catch(Exception expected){check(true,s);return;}throw new AssertionError("accepted "+s);}
    static File target()throws Exception{return Files.createTempDirectory("romlogger-integration-output-").resolve("synthetic.rlc2-integration.db").toFile();}
    static File legacy()throws Exception{File f=Files.createTempDirectory("romlogger-integration-legacy-").resolve("product.db").toFile();new DurableSpool(f);return f;}
    static Rlc2IntegrationStore.Admission admit(Rlc2ProductAdapterTest.Fixture f)throws Exception{
        Rlc2ProductAdapter.Projection p=f.project();Set<String> ids=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources)ids.add(s.uuid);
        return new Rlc2IntegrationStore.Admission(f.dir,p.manifestSession,ids);
    }
    static Rlc2PreviewStore.Result ingest(Rlc2IntegrationStore store,Rlc2ProductAdapterTest.Fixture f,File old)throws Exception{
        return store.ingest(true,admit(f),f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader());
    }
    public static void main(String[] args)throws Exception{
        final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
        final File old=legacy(),out=target();byte[] oldBytes=Files.readAllBytes(old.toPath());java.util.Map<String,String> originals=Rlc2PreviewStoreTest.hashes(f.dir);
        final Rlc2IntegrationStore store=new Rlc2IntegrationStore(true,out);
        rejects(new Action(){public void run()throws Exception{store.ingest(false,admit(f),f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader());}},"quiescence is explicit and mandatory");
        Rlc2PreviewStore.Result first=ingest(store,f,old);check(first.recordsAdded==3&&first.receiptsAdded==4&&!first.ackEligible,"two physical sources atomically map to three v1 records");
        check(ingest(new Rlc2IntegrationStore(true,out),f,old).recordsAdded==0,"reopened versioned engine replay adds nothing");
        check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM integration_admissions")==2&&Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM integration_bindings")==1,"one logical binding owns two explicit admissions");
        check(Rlc2PreviewStoreTest.count(out,"SELECT count(DISTINCT run_id) FROM preview_records")==1,"physical UUIDs do not create new runs");
        check(oldBytes.length==Files.size(old.toPath())&&java.util.Arrays.equals(oldBytes,Files.readAllBytes(old.toPath()))&&originals.equals(Rlc2PreviewStoreTest.hashes(f.dir)),"legacy ACK/product and all source bytes remain unchanged");
        final Rlc2IntegrationStore.Admission beforeRotation=admit(f);
        RotationManifestPrototype.seal(f.dir,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
        RotationManifestPrototype.startNext(f.dir,f.env.reader(),f.env.platform(),RotationManifestPrototype.NO_FAULT);
        f.append(3,"S\t"+Rlc2ProductAdapterTest.SESSION+"\t1\t2\t1791838802000\tTEST_RPM=702");
        RotationManifestPrototype.ingestSession(f.dir,f.ledger,f.env.reader(),null);
        rejects(new Action(){public void run()throws Exception{store.ingest(true,beforeRotation,f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader());}},"new source is not adopted without explicit admission");
        Rlc2PreviewStore.Result grown=ingest(store,f,old);check(grown.recordsAdded==1&&grown.receiptsAdded==1&&Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM integration_admissions")==3,"explicitly admitted rotation extends immutable receipts and run");
        final File rollback=target();final Rlc2IntegrationStore fail=new Rlc2IntegrationStore(true,rollback);
        rejects(new Action(){public void run()throws Exception{fail.ingest(true,admit(f),f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage s)throws Exception{if(s==Rlc2PreviewStore.Stage.BEFORE_COMMIT)throw new java.io.IOException("injected candidate failure");}});}},"candidate precommit exception rolls back");
        for(String table:new String[]{"preview_sessions","preview_claims","preview_sources","preview_receipts","preview_profiles","preview_records","integration_bindings","integration_admissions"})check(Rlc2PreviewStoreTest.count(rollback,"SELECT count(*) FROM "+table)==0,"rollback includes "+table);
        check(ingest(fail,f,old).recordsAdded==4,"retry after full rollback succeeds once");
        Rlc2PreviewStoreTest.execute(out,"DELETE FROM integration_admissions WHERE source_uuid=(SELECT min(source_uuid) FROM integration_admissions)");
        rejects(new Action(){public void run()throws Exception{ingest(store,f,old);}},"missing saved admission is not silently reconstructed");
        final File claimed=legacy();new DurableSpool(claimed).claimSourceSession(Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.SESSION,"legacy","synthetic.tsv");
        final File denied=target();final Rlc2IntegrationStore deniedStore=new Rlc2IntegrationStore(true,denied);
        rejects(new Action(){public void run()throws Exception{ingest(deniedStore,f,claimed);}},"legacy TSV lane blocks candidate transaction");
        check(Rlc2PreviewStoreTest.count(denied,"SELECT count(*) FROM preview_records")==0,"lane conflict advances no records or claims");
        final File concurrent=target();final Rlc2IntegrationStore a=new Rlc2IntegrationStore(true,concurrent),b=new Rlc2IntegrationStore(true,concurrent);final Throwable[] errors=new Throwable[2];
        Thread t1=new Thread(new Runnable(){public void run(){try{ingest(a,f,old);}catch(Throwable e){errors[0]=e;}}});
        Thread t2=new Thread(new Runnable(){public void run(){try{ingest(b,f,old);}catch(Throwable e){errors[1]=e;}}});
        t1.start();t2.start();t1.join(30000);t2.join(30000);
        check(!t1.isAlive()&&!t2.isAlive()&&(errors[0]==null||errors[1]==null),"two workers finish with at least one committed transaction");
        check(Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_records")==4&&Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_receipts")==5,"two workers converge on exact record and receipt sets");
        check(Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"candidate never promotes ACK");
        System.out.println("RLC2 integration engine passed "+checks+" checks; explicit offline candidate; no Server ACK");
    }
}

