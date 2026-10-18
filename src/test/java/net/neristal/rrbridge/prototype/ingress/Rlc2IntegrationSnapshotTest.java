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
        for(int kind=0;kind<4;kind++){
            final int mutation=kind;
            final Rlc2ProductAdapterTest.Fixture g=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
            final File legacy=Rlc2IntegrationStoreTest.legacy(),target=Rlc2IntegrationStoreTest.target();final Rlc2IntegrationStore candidate=new Rlc2IntegrationStore(true,target);
            final java.util.List<java.util.Map<String,String>> changed=new java.util.ArrayList<java.util.Map<String,String>>();
            boolean refused=false;
            try{candidate.ingest(true,Rlc2IntegrationStoreTest.admit(g),g.dir,g.db.toFile(),legacy,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,g.env.reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage)throws Exception{
                if(stage!=Rlc2PreviewStore.Stage.BEFORE_COMMIT)return;
                if(mutation==0)RotationManifestPrototype.seal(g.dir,g.env.reader(),g.env.platform(),RotationManifestPrototype.NO_FAULT);
                if(mutation==1)g.ledger.execute("UPDATE proto_rlc2_highwater SET generation=generation+1");
                if(mutation==2)Rlc2PreviewStoreTest.execute(legacy,"INSERT INTO v1_ingress_review_state VALUES(1,'SYNTHETIC_SNAPSHOT_CHANGE',0)");
                if(mutation==3)try(WatermarkV2Scenarios.OwnedJournal writer=g.env.openOwned(RotationManifestPrototype.segment(g.dir,2))){writer.append("E\t"+Rlc2ProductAdapterTest.SESSION+"\t1\t1791838803000\tsynthetic unpublished");}
                changed.add(Rlc2PreviewStoreTest.hashes(g.dir));
            }});}catch(java.io.IOException expected){refused=true;}
            check(refused,"independent snapshot mutation rejected kind="+kind);
            check(Rlc2PreviewStoreTest.count(target,"SELECT count(*) FROM preview_records")==0,"mutation leaves no partially committed projection kind="+kind);
            check(changed.size()==1&&changed.get(0).equals(Rlc2PreviewStoreTest.hashes(g.dir)),"changed original evidence retained without repair kind="+kind);
        }
        System.out.println("RLC2 integration snapshot passed "+checks+" checks; external quiescence still required");
    }
}
