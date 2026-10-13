package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/**
 * Current-source Agent journal -> product v1 SQLite -> actual FastAPI/PG E2E.
 * Synthetic TEST_ data and disposable Server only. Never touches RomRaider.
 */
public final class JournalServerE2E {
    private static String env(String key) {
        String value=System.getenv(key);
        if(value==null || value.trim().isEmpty())
            throw new IllegalStateException("Missing "+key);
        return value;
    }
    private static void require(boolean value,String text) {
        if(!value)throw new AssertionError(text);
    }
    private static IncrementalTsvBridge.Result importJournal(
            DurableSpool spool,String device,String vehicle,File source,int limit)
            throws Exception {
        return new IncrementalTsvBridge(spool,device,vehicle).importJournal(source,limit);
    }
    private static void sendBoth(DurableSpool spool,String origin,String token,
                                  String device,String vehicle) throws Exception {
        ProductSender sender=new ProductSender(spool,origin,token,device,vehicle,3000,16,65536);
        require(sender.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED,
            "Real FastAPI did not confirm journal-derived profile");
        require(sender.pumpOnce()==ProductSender.Outcome.BATCH_ACKED,
            "Real PostgreSQL did not confirm journal-derived batch");
        require(spool.countPending()==0 && spool.countAcked()==3,
            "Incomplete journal-origin ACK persistence");
    }

    public static void main(String[] args) throws Exception {
        final String origin=env("ROMLOGGER_E2E_ORIGIN");
        final String device=env("ROMLOGGER_E2E_DEVICE_ID");
        final String vehicle=env("ROMLOGGER_E2E_VEHICLE_ID");
        final String token=env("ROMLOGGER_E2E_TOKEN");
        final String session="ci_journal_"+UUID.randomUUID().toString().replace("-","");
        final File dir=Files.createTempDirectory("romlogger-journal-server-e2e").toFile();
        final File source=new File(dir,"ingress.rlj");
        require(source.createNewFile(),"journal file already exists");
        final String sourceUuid=JournalIdentityPrototype.open(source).sourceUuid();
        final File spoolPath=new File(dir,"product.sqlite");
        try(IngressJournalPrototype journal=new IngressJournalPrototype(source,8*1024*1024)){
            journal.append("M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm");
            journal.append("M\t"+session+"\t1\tTEST_TPS\tSynthetic throttle\t%");
            for(int i=0;i<3;i++){
                journal.append("S\t"+session+"\t1\t"+i+"\t"+(1791838800000L+i*500)+
                    "\tTEST_RPM="+(1800+i*100)+",TEST_TPS="+(20+i));
            }
        }
        DurableSpool spool=new DurableSpool(spoolPath);
        require(importJournal(spool,device,vehicle,source,1).metadata==1,
            "First bounded journal metadata import failed");
        // Fresh Java objects and DB connections must restore metadata and
        // source UUID without any in-memory cursor.
        spool=new DurableSpool(spoolPath);
        require(importJournal(spool,device,vehicle,source,2).observations==1,
            "Journal import after restart missed its first observation");
        spool=new DurableSpool(spoolPath);
        require(importJournal(spool,device,vehicle,source,10).observations==2,
            "Journal importer failed to resume remaining records");
        require(spool.countPending()==3,"Three journal samples not present in v1 SQLite");
        require(spool.loadJournalCursor(sourceUuid).sequence==5,
            "Journal cursor not committed for all original frames");
        require(importJournal(spool,device,vehicle,source,10).processedLines==0,
            "Already imported journal inserted duplicate data");
        sendBoth(spool,origin,token,device,vehicle);
        spool=new DurableSpool(spoolPath);
        require(spool.countAcked()==3 && spool.countPending()==0,
            "ACK receipts did not survive SQLite restart");
        require(importJournal(spool,device,vehicle,source,10).processedLines==0,
            "ACKed journal could not be rechecked from immutable origin");

        // A FRESH SQLite outbox imports the same source from zero. Server
        // must confirm the identical immutable profile and batch as duplicates.
        final File replayPath=new File(dir,"fresh-replay.sqlite");
        DurableSpool replay=new DurableSpool(replayPath);
        require(importJournal(replay,device,vehicle,source,2).metadata==2,
            "Fresh-outbox journal metadata import failed");
        replay=new DurableSpool(replayPath);
        require(importJournal(replay,device,vehicle,source,2).observations==2,
            "Fresh-outbox bounded journal observations failed");
        replay=new DurableSpool(replayPath);
        require(importJournal(replay,device,vehicle,source,5).observations==1,
            "Fresh-outbox last journal sample missing");
        require(replay.countPending()==3 && replay.countAcked()==0,
            "Fresh outbox improperly inherited Server ACK");
        sendBoth(replay,origin,token,device,vehicle);
        require(replay.confirmedBatchCount()==1,
            "Duplicate-only Server batch receipt not durable locally");
        System.out.println("Real Server Journal E2E: PASS (3 synthetic observations, "+
            "product v1 SQLite, durable replay cursor, profile/batch/duplicate ACK, "+
            "actual FastAPI/PostgreSQL; no ECU)");
    }
}
