package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Real FastAPI/PostgreSQL E2E, invoked only against a disposable test DB.
 * Tests the CURRENT incremental cursor importer, not legacy full TSV scan.
 * The driver has no ECU access, no profile changes and no real vehicle token.
 */
public final class ProductServerE2E {
    private static String env(String key) {
        String value=System.getenv(key);
        if(value==null||value.trim().isEmpty())
            throw new IllegalStateException("Missing "+key);
        return value;
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    private static void write(File file,String text) throws Exception {
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
    private static void append(File file,String text) throws Exception {
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
    }
    private static IncrementalTsvBridge importer(DurableSpool spool,String device,String vehicle) {
        return new IncrementalTsvBridge(spool,device,vehicle);
    }
    public static void main(String[] args) throws Exception {
        String origin=env("ROMLOGGER_E2E_ORIGIN");
        String device=env("ROMLOGGER_E2E_DEVICE_ID");
        String vehicle=env("ROMLOGGER_E2E_VEHICLE_ID");
        String token=env("ROMLOGGER_E2E_TOKEN");
        String session="ci_"+UUID.randomUUID().toString().replace("-","");
        File dir=Files.createTempDirectory("product-incremental-e2e-").toFile();
        File source=new File(dir,"rr_"+session+".tsv");
        File spoolFile=new File(dir,"outbox.sqlite");
        String header=
            "M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm\n"+
            "M\t"+session+"\t1\tTEST_TPS\tSynthetic throttle\t%\n";
        String sample0=
            "S\t"+session+"\t1\t0\t1791838800000\tTEST_RPM=1800,TEST_TPS=20\n";
        // Writer has started a record, but has not yet written the terminating LF.
        String pendingSample1=
            "S\t"+session+"\t1\t1\t1791838800500\tTEST_RPM";
        write(source,header+sample0+pendingSample1);
        DurableSpool spool=new DurableSpool(spoolFile);
        IncrementalTsvBridge.Result first=importer(spool,device,vehicle).importNew(source,1);
        require(first.metadata==1&&first.processedLines==1,
                "Bounded initial import did not stop after first M line");
        // Process restart: restore metadata after the first M line.
        spool=new DurableSpool(spoolFile);
        IncrementalTsvBridge.Result second=importer(spool,device,vehicle).importNew(source,10);
        require(second.metadata==1&&second.observations==1&&second.incompleteTail,
                "Incremental restart lost metadata, observation or partial line");
        require(spool.countPending()==1,"Only first complete sample should be durable");
        DurableSpool.ImportCursor beforeTail=spool.loadImportCursor(source.getCanonicalPath());
        require(beforeTail!=null&&beforeTail.offsetBytes<source.length(),
                "Cursor improperly consumed unfinished TSV record");
        // Another restart without any new LF must not acknowledge a partial record.
        spool=new DurableSpool(spoolFile);
        IncrementalTsvBridge.Result waiting=importer(spool,device,vehicle).importNew(source,10);
        require(waiting.processedLines==0&&waiting.incompleteTail&&spool.countPending()==1,
                "Importer skipped or accepted incomplete tail");
        append(source,"=1900,TEST_TPS=21\n"+
            "S\t"+session+"\t1\t2\t1791838801000\tTEST_RPM=2000,TEST_TPS=22\n");
        IncrementalTsvBridge.Result third=importer(spool,device,vehicle).importNew(source,1);
        require(third.observations==1&&third.processedLines==1,
                "Only the completed partial sample should be imported");
        spool=new DurableSpool(spoolFile);
        IncrementalTsvBridge.Result fourth=importer(spool,device,vehicle).importNew(source,10);
        require(fourth.observations==1&&spool.countPending()==3,
                "Restart missed appended third observation");
        require(spool.loadImportCursor(source.getCanonicalPath()).offsetBytes==source.length(),
                "Durable cursor did not reach current TSV EOF");
        require(importer(spool,device,vehicle).importNew(source,10).processedLines==0,
                "At EOF, importer must not repeat consumed lines");
        ProductSender sender=new ProductSender(spool,origin,token,device,vehicle,2500,16,65536);
        require(sender.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED,
                "Real FastAPI profile registration failed");
        require(sender.pumpOnce()==ProductSender.Outcome.BATCH_ACKED,
                "Real PostgreSQL batch commit failed");
        require(spool.countPending()==0&&spool.countAcked()==3,
                "Three committed originals were not ACKed locally");
        spool=new DurableSpool(spoolFile);
        require(spool.countPending()==0&&spool.countAcked()==3,
                "SQLite ACK state not persistent after restart");

        // New outbox and importer: scan the identical original in small chunks.
        // The server must accept both the profile and the batch as duplicates.
        File replayDir=Files.createTempDirectory("product-incremental-e2e-replay-").toFile();
        DurableSpool replay=new DurableSpool(new File(replayDir,"outbox.sqlite"));
        require(importer(replay,device,vehicle).importNew(source,2).metadata==2,
                "Replay metadata chunk failed");
        replay=new DurableSpool(new File(replayDir,"outbox.sqlite"));
        require(importer(replay,device,vehicle).importNew(source,2).observations==2,
                "Replay observation chunk failed");
        replay=new DurableSpool(new File(replayDir,"outbox.sqlite"));
        require(importer(replay,device,vehicle).importNew(source,2).observations==1,
                "Replay last observation chunk failed");
        require(replay.countPending()==3,"Replay UUID set was incomplete");
        ProductSender again=new ProductSender(replay,origin,token,device,vehicle,2500,16,65536);
        require(again.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED,
                "Real PostgreSQL idempotent profile response failed");
        require(again.pumpOnce()==ProductSender.Outcome.BATCH_ACKED,
                "Real PostgreSQL duplicate-only batch ACK failed");
        require(replay.countPending()==0&&replay.countAcked()==3,
                "Duplicate-only ACK not saved to second SQLite file");
        System.out.println("Real Server incremental E2E: PASS (3 synthetic observations, "+
            "partial LF, durable cursor, multiple restarts, duplicate-only replay; no ECU)");
    }
}
