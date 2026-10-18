package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import net.neristal.rrbridge.product.v1.DurableSpool;
import net.neristal.rrbridge.product.v1.IncrementalTsvBridge;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Full source -> RLC2 -> real ledger -> shared v1 JSON, without a sender. */
public final class Rlc2ProductAdapterTest {
    static final String DEVICE="8d034ae6-32ac-4d51-bdcb-4cf7cc199540", VEHICLE="8d034ae6-32ac-4d51-bdcb-4cf7cc199541";
    static final String SESSION="synthetic-adapter";
    private static int checks;
    static void check(boolean b,String why) { if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why); }
    interface Action { void run()throws Exception; }
    static void rejects(String why,Action action)throws Exception {
        try { action.run(); }catch(IOException|IllegalArgumentException|IllegalStateException expected) { check(true,why);return; }
        throw new AssertionError("accepted: "+why);
    }
    static Env environment()throws Exception {
        if(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            java.lang.reflect.Field f=Class.forName("net.neristal.rrbridge.prototype.ingress.WindowsWatermarkV2Test").getDeclaredField("ENV");
            f.setAccessible(true);return (Env)f.get(null);
        }return PosixWatermarkV2Test.ENV;
    }
    static final class Fixture {
        final Path dir,db;
        final Env env;
        final WatermarkLedgerPrototype ledger;
        final String[] rows;
        Fixture(Env env,boolean sealSecond,String secondSession,int seq)throws Exception {
            this.env=env;dir=Files.createTempDirectory("romlogger-adapter-");db=dir.resolve("ledger.db");
            rows=new String[]{"M\t"+SESSION+"\t1\tTEST_RPM\tSynthetic RPM\trpm",
                "S\t"+SESSION+"\t1\t0\t1791838800000\tTEST_RPM=700",
                "S\t"+secondSession+"\t1\t"+seq+"\t1791838801000\tTEST_RPM=701",
                "E\t"+secondSession+"\t1\t1791838801200\tsynthetic rotation"};
            RotationManifestPrototype.begin(dir,env.reader(),env.platform());
            append(1,rows[0],rows[1]);
            RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            RotationManifestPrototype.startNext(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            append(2,rows[2],rows[3]);
            if(sealSecond)RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            ledger=new WatermarkLedgerPrototype(db.toFile());
            RotationManifestPrototype.ingestSession(dir,ledger,env.reader(),null);
        }
        void append(int index,String... lines)throws Exception {
            Path p=RotationManifestPrototype.segment(dir,index);
            try(OwnedJournal w=env.openOwned(p)) { for(String row:lines)w.append(row);WatermarkV2Scenarios.publish(env,p,w,WatermarkV2Prototype.NO_FAULT); }
        }
        Rlc2ProductAdapter.Projection project()throws Exception { return Rlc2ProductAdapter.project(true,dir,db.toFile(),DEVICE,VEHICLE,env.reader()); }
    }
    public static void main(String[] args)throws Exception {
        Env env=environment();
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"projection actual JVM architecture");
        rejects("default-disabled gate before any source or DB access",new Action(){public void run()throws Exception { Rlc2ProductAdapter.project(false,null,null,null,null,null); }});
        final Fixture f=new Fixture(env,true,SESSION,1);
        Rlc2ProductAdapter.Projection p=f.project();
        check(p.sources.size()==2&&p.receipts.size()==4&&p.records.size()==3&&p.profiles.size()==1,"two source UUIDs map four original receipts to one profile and three v1 records");
        check(!p.ackEligible&&p.records.get(0).source.equals("synthetic")&&p.records.get(1).source.equals("synthetic"),"synthetic projection remains ACK-ineligible");
        check(p.records.get(0).run.equals(p.records.get(1).run)&&p.records.get(0).sequence==0&&p.records.get(1).sequence==1,"one run and global sample sequence survive rotation");
        check(p.sources.get(0).marker.sequence==2&&p.sources.get(1).marker.sequence==2&&p.receipts.get(2).sequence==1,"source frame sequences remain distinct from run sequence");
        check(!p.receipts.get(0).receiptId.equals(p.receipts.get(2).receiptId),"source UUID namespaces frame receipt IDs");
        Rlc2ProductAdapter.Projection again=f.project();
        for(int i=0;i<p.records.size();i++)check(p.records.get(i).id.equals(again.records.get(i).id)&&p.records.get(i).json.equals(again.records.get(i).json),"restart-style projection stable record "+i);
        Path baseline=Files.createTempDirectory("romlogger-projection-legacy-baseline-");
        Path tsv=baseline.resolve("rr_"+SESSION+".tsv");
        Files.write(tsv,(String.join("\n",f.rows)+"\n").getBytes(StandardCharsets.UTF_8));
        Path product=baseline.resolve("baseline.db");
        DurableSpool spool=new DurableSpool(product.toFile());
        new IncrementalTsvBridge(spool,DEVICE,VEHICLE).importNew(tsv.toFile(),100);
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+product);Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT record_id,run_id,body_json FROM v1_records ORDER BY local_id")) {
            int at=0;
            while(r.next()) { Rlc2ProductAdapter.Record record=p.records.get(at++);check(record.id.equals(r.getString(1))&&record.run.equals(r.getString(2))&&record.json.equals(r.getString(3)),"projection exactly matches unchanged legacy product JSON and IDs"); }
            check(at==3,"legacy comparison includes every projected record");
        }
        check(spool.pendingProfileEntries().get(0).json.equals(p.profiles.values().iterator().next()),"projection exactly matches product profile JSON");
        final Fixture tail=new Fixture(env,false,SESSION,1);
        try(OwnedJournal w=env.openOwned(RotationManifestPrototype.segment(tail.dir,2))) { w.append("S\t"+SESSION+"\t1\t2\t1791838802000\tTEST_RPM=999"); }
        check(tail.project().receipts.size()==4&&tail.project().records.size()==3,"unpublished active tail never enters projection");
        tail.append(2,"S\t"+SESSION+"\t1\t3\t1791838803000\tTEST_RPM=998");
        rejects("committed source ahead of ledger blocks projection",new Action(){public void run()throws Exception{tail.project();}});
        final Fixture reset=new Fixture(env,true,SESSION,0);
        rejects("sample sequence reset across source rotation rejected",new Action(){public void run()throws Exception{reset.project();}});
        final Fixture session=new Fixture(env,true,"synthetic-other",1);
        rejects("cross-rotation payload session change rejected",new Action(){public void run()throws Exception{session.project();}});
        f.ledger.execute("UPDATE proto_lane_claims SET lane='tsv'");
        rejects("legacy source claim conflict blocks projection",new Action(){public void run()throws Exception{f.project();}});
        check(!Files.exists(f.dir.resolve("product.db")),"adapter creates no product DB or outbox");
        System.out.println("RLC2 product projection "+env.name()+" passed "+checks+" checks; synthetic only; no ACK/transport");
    }
}
