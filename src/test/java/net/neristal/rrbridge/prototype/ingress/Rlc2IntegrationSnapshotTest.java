package net.neristal.rrbridge.prototype.ingress;
import java.io.File;

public final class Rlc2IntegrationSnapshotTest {
    static int checks;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    public static void main(String[] args)throws Exception{
        final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
        final File old=Rlc2IntegrationStoreTest.legacy(),out=Rlc2IntegrationStoreTest.target();
        Rlc2IntegrationStore store=new Rlc2IntegrationStore(true,out);boolean rejected=false;
        try{store.ingest(true,Rlc2IntegrationStoreTest.admit(f),f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage)throws Exception{
            if(stage==Rlc2PreviewStore.Stage.BEFORE_COMMIT){
                f.append(2,"S\t"+Rlc2ProductAdapterTest.SESSION+"\t1\t2\t1791838802000\tTEST_RPM=799");
                RotationManifestPrototype.ingestSession(f.dir,f.ledger,f.env.reader(),null);
            }
        }});}catch(java.io.IOException expected){rejected=true;}
        check(rejected,"source and ledger advance after projection must reject stale commit");
        check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records")==0&&Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM integration_admissions")==0,"snapshot conflict rolls back records and admissions");
        check(Rlc2IntegrationStoreTest.ingest(store,f,old).recordsAdded==4,"explicit retry with new quiescent snapshot ingests exact new state");
        System.out.println("RLC2 integration snapshot passed "+checks+" checks; external quiescence still required");
    }
}
