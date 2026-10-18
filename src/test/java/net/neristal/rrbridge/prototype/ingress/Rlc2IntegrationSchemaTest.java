package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import net.neristal.rrbridge.product.v1.DurableSpool;

public final class Rlc2IntegrationSchemaTest {
    static int checks;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    interface Action{void run()throws Exception;}
    static void rejects(Action a,String why)throws Exception{try{a.run();}catch(Exception expected){check(true,why);return;}throw new AssertionError("accepted "+why);}
    static File target()throws Exception{return Files.createTempDirectory("romlogger-integration-schema-").resolve("synthetic.rlc2-integration.db").toFile();}
    public static void main(String[] args)throws Exception{
        final File off=target();rejects(new Action(){public void run()throws Exception{Rlc2IntegrationSchema.initialize(false,off);}},"disabled before filesystem mutation");check(!off.exists(),"disabled creates nothing");
        final File db=target();Rlc2IntegrationSchema.initialize(true,db);byte[] initial=Files.readAllBytes(db.toPath());Rlc2IntegrationSchema.initialize(true,db);
        check(java.util.Arrays.equals(initial,Files.readAllBytes(db.toPath())),"migration rerun is read-only and identical");
        check(Rlc2PreviewStoreTest.count(db,"PRAGMA user_version")==1&&Rlc2PreviewStoreTest.count(db,"SELECT count(*) FROM sqlite_master WHERE type='table'")==8,"contract v2 revision1 has eight actual SQLite tables");
        final File failed=target();rejects(new Action(){public void run()throws Exception{Rlc2IntegrationSchema.initialize(true,failed,new Rlc2IntegrationSchema.BeforeCommit(){public void run()throws Exception{throw new java.io.IOException("injected migration interruption");}});}},"migration precommit failure");
        check(Rlc2PreviewStoreTest.count(failed,"SELECT count(*) FROM sqlite_master")==0,"failed migration leaves no partial schema");
        final File bad=target();Rlc2IntegrationSchema.initialize(true,bad);Rlc2PreviewStoreTest.execute(bad,"PRAGMA user_version=99");byte[] badBytes=Files.readAllBytes(bad.toPath());
        rejects(new Action(){public void run()throws Exception{Rlc2IntegrationSchema.initialize(true,bad);}},"unknown schema version refused");check(java.util.Arrays.equals(badBytes,Files.readAllBytes(bad.toPath())),"unknown version remains unchanged");
        final File legacy=target();DurableSpool spool=new DurableSpool(legacy);byte[] legacyBytes=Files.readAllBytes(legacy.toPath());
        rejects(new Action(){public void run()throws Exception{Rlc2IntegrationSchema.initialize(true,legacy);}},"existing product database never migrated");check(java.util.Arrays.equals(legacyBytes,Files.readAllBytes(legacy.toPath())),"legacy product bytes unchanged");
        final String d=Rlc2ProductAdapterTest.DEVICE,v=Rlc2ProductAdapterTest.VEHICLE,session=Rlc2ProductAdapterTest.SESSION;
        Rlc2IntegrationSchema.requireUnclaimed(legacy,d,v,session,"other-run");check(true,"empty v1 compatibility inventory accepted read-only");
        spool.claimSourceSession(d,v,session,"legacy","synthetic.tsv");final byte[] claimed=Files.readAllBytes(legacy.toPath());
        rejects(new Action(){public void run()throws Exception{Rlc2IntegrationSchema.requireUnclaimed(legacy,d,v,session,"other-run");}},"legacy lane conflict rejected");check(java.util.Arrays.equals(claimed,Files.readAllBytes(legacy.toPath())),"legacy claim and ACK state untouched");
        System.out.println("RLC2 integration schema passed "+checks+" checks; isolated opt-in only; no production migration/ACK");
    }
}
