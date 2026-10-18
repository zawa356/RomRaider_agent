package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;

/** Abrupt process stop, contention and real SQLite failure; never a power-loss proof. */
public final class Rlc2PreviewFaultTest {
    private static int checks;
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static Process haltChild(Rlc2ProductAdapterTest.Fixture f,File db,Rlc2PreviewStore.Stage stage,Path log)throws Exception {
        String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")?"java.exe":"java").getPath();
        return new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),Rlc2PreviewFaultTest.class.getName(),"child",stage.name(),f.dir.toString(),f.db.toString(),db.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    public static void main(String[] args)throws Exception {
        final Env env=Rlc2ProductAdapterTest.environment();
        if(args.length==5&&"child".equals(args[0])) {
            final Rlc2PreviewStore.Stage point=Rlc2PreviewStore.Stage.valueOf(args[1]);
            Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,new File(args[2]).toPath(),new File(args[3]),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,env.reader());
            new Rlc2PreviewStore(true,new File(args[4])).ingest(p,new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage s){if(s==point)Runtime.getRuntime().halt(84);}});
            throw new AssertionError("halt missed");
        }
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"fault actual JVM architecture");
        Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(env,true,Rlc2ProductAdapterTest.SESSION,1);
        Rlc2ProductAdapter.Projection projection=f.project();Map<String,String> originals=Rlc2PreviewStoreTest.hashes(f.dir);
        for(Rlc2PreviewStore.Stage stage:Rlc2PreviewStore.Stage.values()) {
            File db=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,db);
            Process child=haltChild(f,db,stage,db.toPath().resolveSibling("halt.log"));
            check(child.waitFor(40,TimeUnit.SECONDS)&&child.exitValue()==84,"separate JVM halted at "+stage);
            // Constructor MUST be first: a direct JDBC count could recover the journal and mask a broken reopen gate.
            Rlc2PreviewStore recovered=new Rlc2PreviewStore(true,db);
            boolean after=stage==Rlc2PreviewStore.Stage.AFTER_COMMIT;
            check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_records")== (after?3:0),"whole projected transaction after "+stage);
            check(Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM preview_receipts")== (after?4:0),"whole receipt transaction after "+stage);
            Rlc2PreviewStore.Result retry=recovered.ingest(f.project());
            check(retry.recordsAdded==(after?0:3)&&retry.receiptsAdded==(after?0:4)&&!retry.ackEligible,"restart replay exactly once after "+stage);
            check(recovered.ingest(projection).recordsAdded==0,"second replay adds nothing after "+stage);
        }
        File competing=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,competing);
        Process a=Rlc2PreviewStoreTest.cli(f,competing,competing.toPath().resolveSibling("a.log"));
        Process b=Rlc2PreviewStoreTest.cli(f,competing,competing.toPath().resolveSibling("b.log"));
        check(a.waitFor(40,TimeUnit.SECONDS)&&b.waitFor(40,TimeUnit.SECONDS),"competing CLI JVMs finish");
        check((a.exitValue()==0||a.exitValue()==5)&&(b.exitValue()==0||b.exitValue()==5)&&(a.exitValue()==0||b.exitValue()==0),"one or both workers complete; contention may fail closed");
        check(new Rlc2PreviewStore(true,competing).ingest(f.project()).recordsAdded==0&&Rlc2PreviewStoreTest.count(competing,"SELECT count(*) FROM preview_records")==3&&Rlc2PreviewStoreTest.count(competing,"SELECT count(*) FROM preview_receipts")==4,"competing processes converge on one exact receipt/record set");
        File locked=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,locked);Path log=locked.toPath().resolveSibling("locked.log");
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+locked);Statement tx=db.createStatement()) {
            tx.execute("BEGIN EXCLUSIVE");
            try {
                Process child=Rlc2PreviewStoreTest.cli(f,locked,log);
                check(child.waitFor(30,TimeUnit.SECONDS)&&child.exitValue()==5,"exclusive database lock yields review exit rather than success");
                String output=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);
                check(output.contains("REVIEW_REQUIRED")&&output.contains("\"ack_eligible\":false"),"unavailable DB reports no ACK authority");
            }finally {tx.execute("ROLLBACK");}
        }
        check(Rlc2PreviewStoreTest.count(locked,"SELECT count(*) FROM preview_records")==0,"DB lock leaves no partial output");
        check(new Rlc2PreviewStore(true,locked).ingest(f.project()).recordsAdded==3,"explicit retry after lock release succeeds");
        final File failed=Rlc2PreviewStoreTest.target();new Rlc2PreviewStore(true,failed);
        Rlc2PreviewStoreTest.execute(failed,"CREATE TRIGGER synthetic_db_failure BEFORE INSERT ON preview_records BEGIN SELECT RAISE(ABORT,'synthetic database failure'); END");
        Process dbFailure=Rlc2PreviewStoreTest.cli(f,failed,failed.toPath().resolveSibling("db-failure.log"));
        check(dbFailure.waitFor(40,TimeUnit.SECONDS)&&dbFailure.exitValue()==5,"real SQLite insert failure stops CLI");
        for(String table:new String[]{"preview_sessions","preview_claims","preview_sources","preview_receipts","preview_profiles","preview_records"})check(Rlc2PreviewStoreTest.count(failed,"SELECT count(*) FROM "+table)==0,"DB failure rolls back "+table);
        check(originals.equals(Rlc2PreviewStoreTest.hashes(f.dir)),"all failure/competition scenarios preserve original source/marker/manifest/ledger bytes");
        System.out.println("RLC2 preview faults "+env.name()+" passed "+checks+" checks; Runtime.halt is not physical power loss; ACK forbidden");
    }
}
