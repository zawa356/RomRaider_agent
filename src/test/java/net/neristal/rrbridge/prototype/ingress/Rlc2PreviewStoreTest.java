package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;

/** Synthetic source + native RLC2 + ledger + converter + isolated SQLite, including real CLI processes. */
public final class Rlc2PreviewStoreTest {
    private static int checks;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    interface Action{void run()throws Exception;}
    static void rejects(String why,Action a)throws Exception {try{a.run();}catch(Exception e){check(true,why);return;}throw new AssertionError("accepted: "+why);}
    static long count(File db,String sql)throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}
    }
    static void execute(File db,String sql)throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);Statement s=c.createStatement()){s.execute(sql);}
    }
    static File target()throws Exception{return Files.createTempDirectory("romlogger-preview-output-").resolve("synthetic.rlc2-preview.db").toFile();}
    static Map<String,String> hashes(Path directory)throws Exception {
        Map<String,String> result=new LinkedHashMap<String,String>();
        try(java.nio.file.DirectoryStream<Path> ds=Files.newDirectoryStream(directory)){
            for(Path p:ds)if(Files.isRegularFile(p))result.put(p.getFileName().toString(),WatermarkLedgerPrototype.hex(WatermarkV2Prototype.sha().digest(Files.readAllBytes(p))));
        }return result;
    }
    static Process cli(Rlc2ProductAdapterTest.Fixture f,File output,Path log)throws Exception {
        boolean win=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        String exe=new File(new File(System.getProperty("java.home"),"bin"),win?"java.exe":"java").getPath();
        String main="net.neristal.rrbridge.prototype.ingress."+(win?"WindowsRlc2PreviewCli":"Rlc2PreviewCli");
        return new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),main,"--enable-synthetic-preview",f.dir.toString(),f.db.toString(),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,output.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    public static void main(String[] args)throws Exception {
        Env env=Rlc2ProductAdapterTest.environment();
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"preview actual JVM architecture");
        final File disabled=target();
        rejects("preview disabled before creating database",new Action(){public void run()throws Exception{new Rlc2PreviewStore(false,disabled);}});
        check(!disabled.exists(),"disabled preview leaves no database");
        final Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(env,true,Rlc2ProductAdapterTest.SESSION,1);
        final Rlc2ProductAdapter.Projection p=f.project();
        Map<String,String> originals=hashes(f.dir);
        final File output=target();Rlc2PreviewStore store=new Rlc2PreviewStore(true,output);
        Rlc2PreviewStore.Result first=store.ingest(p);
        check(first.recordsAdded==3&&first.receiptsAdded==4&&!first.ackEligible,"atomic preview stores three records and four source receipts without ACK");
        check(new Rlc2PreviewStore(true,output).ingest(f.project()).recordsAdded==0,"restart and re-projection do not duplicate records");
        check(count(output,"SELECT count(*) FROM preview_sources")==2&&count(output,"SELECT count(*) FROM preview_claims")==1,"one manifest-session claim owns two source cursors");
        check(count(output,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0&&count(output,"SELECT count(*) FROM sqlite_master WHERE name LIKE 'v1_%'")==0,"preview schema has no product v1 tables and no ACK eligibility");
        rejects("SQLite constraint rejects enabling ACK",new Action(){public void run()throws Exception{execute(output,"UPDATE preview_records SET ack_eligible=1");}});
        check(originals.equals(hashes(f.dir)),"source, marker, identity, manifest and ledger bytes remain unchanged");
        final File unknown=target();Files.write(unknown.toPath(),"not a database".getBytes(StandardCharsets.UTF_8));final byte[] unknownBytes=Files.readAllBytes(unknown.toPath());
        rejects("foreign existing DB refused before mutation",new Action(){public void run()throws Exception{new Rlc2PreviewStore(true,unknown);}});
        check(java.util.Arrays.equals(unknownBytes,Files.readAllBytes(unknown.toPath())),"foreign file preserved byte-for-byte");
        final File renamedProduct=target();new net.neristal.rrbridge.product.v1.DurableSpool(renamedProduct);final byte[] productBytes=Files.readAllBytes(renamedProduct.toPath());
        rejects("renamed real product DB cannot become a preview DB",new Action(){public void run()throws Exception{new Rlc2PreviewStore(true,renamedProduct);}});
        check(java.util.Arrays.equals(productBytes,Files.readAllBytes(renamedProduct.toPath())),"renamed product DB remains unchanged");
        final File rollback=target();final Rlc2PreviewStore interrupted=new Rlc2PreviewStore(true,rollback);
        rejects("failure immediately before commit rolls back",new Action(){public void run()throws Exception{interrupted.ingest(p,new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage s)throws Exception{if(s==Rlc2PreviewStore.Stage.BEFORE_COMMIT)throw new java.io.IOException("synthetic failure");}});}});
        for(String table:new String[]{"preview_sessions","preview_claims","preview_sources","preview_receipts","preview_profiles","preview_records"})check(count(rollback,"SELECT count(*) FROM "+table)==0,"rollback leaves no partial "+table);
        check(interrupted.ingest(p).recordsAdded==3,"retry after rollback stores exactly once");
        final File corrupt=target();final Rlc2PreviewStore damaged=new Rlc2PreviewStore(true,corrupt);damaged.ingest(p);execute(corrupt,"UPDATE preview_receipts SET body_tsv='synthetic tamper' WHERE seq=1");
        rejects("persisted receipt mismatch fails closed without repair",new Action(){public void run()throws Exception{damaged.ingest(p);}});
        check(count(corrupt,"SELECT count(*) FROM preview_receipts WHERE body_tsv='synthetic tamper'")==2,"tampered evidence retained");
        final File missing=target();final Rlc2PreviewStore incomplete=new Rlc2PreviewStore(true,missing);incomplete.ingest(p);execute(missing,"DELETE FROM preview_receipts WHERE rowid=(SELECT min(rowid) FROM preview_receipts)");
        rejects("missing persisted receipt refused rather than reconstructed",new Action(){public void run()throws Exception{incomplete.ingest(p);}});
        check(count(missing,"SELECT count(*) FROM preview_receipts")==3,"missing receipt not automatically repaired");
        final File lostRecord=target();final Rlc2PreviewStore missingRecord=new Rlc2PreviewStore(true,lostRecord);missingRecord.ingest(p);execute(lostRecord,"DELETE FROM preview_records WHERE rowid=(SELECT min(rowid) FROM preview_records)");
        rejects("missing persisted product projection refused rather than reconstructed",new Action(){public void run()throws Exception{missingRecord.ingest(p);}});
        check(count(lostRecord,"SELECT count(*) FROM preview_records")==2,"missing projected record remains for review");
        final File changedId=target();final Rlc2PreviewStore unknownRecord=new Rlc2PreviewStore(true,changedId);unknownRecord.ingest(p);
        execute(changedId,"UPDATE preview_records SET record_id='00000000-0000-0000-0000-000000000001' WHERE kind='event'");
        final byte[] changedBytes=Files.readAllBytes(changedId.toPath());
        rejects("changed event ID with unchanged counts is refused",new Action(){public void run()throws Exception{unknownRecord.ingest(p);}});
        check(count(changedId,"SELECT count(*) FROM preview_records")==3&&java.util.Arrays.equals(changedBytes,Files.readAllBytes(changedId.toPath())),"unknown event evidence preserved without inserting original ID");
        final File mixed=target();new Rlc2PreviewStore(true,mixed);execute(mixed,"CREATE TABLE sqlitex_foreign(value TEXT)");
        rejects("owned preview rejects foreign tables including sqlite-like names",new Action(){public void run()throws Exception{new Rlc2PreviewStore(true,mixed);}});
        final File conflict=target();final Rlc2PreviewStore claimed=new Rlc2PreviewStore(true,conflict);
        execute(conflict,"INSERT INTO preview_claims VALUES('"+p.device+"','"+p.vehicle+"','"+p.payloadSession+"','legacy','other')");
        rejects("quarantine legacy lane claim blocks whole transaction",new Action(){public void run()throws Exception{claimed.ingest(p);}});
        check(count(conflict,"SELECT count(*) FROM preview_records")==0,"claim conflict creates no projected records");
        final Rlc2ProductAdapterTest.Fixture history=new Rlc2ProductAdapterTest.Fixture(env,false,Rlc2ProductAdapterTest.SESSION,1);
        final Rlc2ProductAdapter.Projection older=history.project();final Rlc2PreviewStore durableHistory=new Rlc2PreviewStore(true,target());durableHistory.ingest(older);
        RotationManifestPrototype.seal(history.dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);durableHistory.ingest(history.project());
        rejects("persistent manifest history rejects older projection after restart",new Action(){public void run()throws Exception{durableHistory.ingest(older);}});
        File cliDb=target();Path log=cliDb.toPath().resolveSibling("cli.log");Process child=cli(f,cliDb,log);
        check(child.waitFor(40,TimeUnit.SECONDS)&&child.exitValue()==0,"real separate-JVM opt-in CLI imports preview");
        String text=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);
        check(text.contains("\"records_added\":3")&&text.contains("\"ack_eligible\":false")&&!text.contains(f.dir.toString()),"CLI reports structured counts without paths or ACK");
        Process replay=cli(f,cliDb,log);check(replay.waitFor(40,TimeUnit.SECONDS)&&replay.exitValue()==0&&count(cliDb,"SELECT count(*) FROM preview_records")==3,"separate-JVM replay is idempotent");
        check(new String(Files.readAllBytes(log),StandardCharsets.UTF_8).contains("\"records_added\":0"),"replay reports zero added records");
        if(args.length==1) {
            Path manifest=new File(args[0]).toPath();Files.createDirectories(manifest.toAbsolutePath().getParent());Files.write(manifest,cliDb.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("RLC2 preview SQLite "+env.name()+" passed "+checks+" checks; no Server ACK; no production tables");
    }
}
