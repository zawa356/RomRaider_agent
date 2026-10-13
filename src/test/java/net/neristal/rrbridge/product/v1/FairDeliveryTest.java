package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Real SQLite, process-restart-safe fairness scheduling; synthetic only. */
public final class FairDeliveryTest {
    static int checks;
    static String id(){return UUID.randomUUID().toString();}
    static void ok(String why,boolean valid){
        if(!valid)throw new AssertionError(why);
        checks++;System.out.println("PASS "+why);
    }
    static void insert(DurableSpool s,String run,String segment,String profile,int sequence)
            throws Exception {
        String rid=id();
        String json="{\"kind\":\"observation\",\"record_id\":\""+rid+
            "\",\"segment_id\":\""+segment+"\",\"sequence\":"+sequence+
            ",\"captured_at\":\"2026-10-13T00:00:00Z\",\"source_kind\":\"synthetic\""+
            ",\"profile_revision_id\":\""+profile+"\",\"values\":[{\"parameter_id\":\"P8\",\"value\":2000}]}";
        s.appendRecord(rid,run,segment,(long)sequence,"observation",profile,
            "2026-10-13T00:00:00Z","synthetic",json);
    }
    static DurableSpool.Batch planned(DurableSpool s)throws Exception {
        return s.confirmedBatchCount()%4==3
            ?s.nextRecentBatch(2,65000):s.nextBatch(2,65000);
    }
    static void ack(DurableSpool s,DurableSpool.Batch b)throws Exception {
        s.markBatchAcked(b,new DurableSpool.Ack(b.batchId,"committed",
            b.records.size(),b.records.size(),0));
    }
    public static void main(String[] args)throws Exception {
        File file=new File(Files.createTempDirectory("fair-delivery-ci").toFile(),"spool.sqlite");
        DurableSpool db=new DurableSpool(file);
        String profile=id(),oldRun=id(),newRun=id(),oldSeg=id(),newSeg=id();
        db.appendProfile(profile,"{\"definition\":\"synthetic\"}");
        db.markProfileAcked(profile,true);
        for(int i=0;i<12;i++)insert(db,oldRun,oldSeg,profile,i);
        for(int i=0;i<4;i++)insert(db,newRun,newSeg,profile,i);
        ok("16 synthetic observations pending",db.countPending()==16);
        for(int round=0;round<3;round++) {
            DurableSpool.Batch b=planned(db);
            ok("oldest run served in backlog round "+round,b.runId.equals(oldRun));
            ok("oldest run ordered and 2 records per batch "+round,
                b.records.size()==2);
            ack(db,b);
        }
        ok("three successful ACKs persist lane",db.confirmedBatchCount()==3);
        db=new DurableSpool(file);
        ok("restart restores fourth (recent) scheduling slot",db.confirmedBatchCount()%4==3);
        DurableSpool.Batch fresh=planned(db);
        ok("newest run served on fourth ACK slot",fresh.runId.equals(newRun));
        ok("newest run takes earliest two of its own run",fresh.records.size()==2);
        DurableSpool.Batch repeated=planned(db);
        ok("without ACK slot does not advance",repeated.runId.equals(newRun));
        ack(db,fresh);
        ok("fourth ACK moves scheduling slot",db.confirmedBatchCount()==4);
        DurableSpool.Batch back=planned(db);
        ok("fifth batch services old backlog",back.runId.equals(oldRun));
        ack(db,back);
        for(int i=0;i<2;i++) {
            DurableSpool.Batch b=planned(db);
            ok("oldest backlog continues after recent ACK "+i,b.runId.equals(oldRun));
            ack(db,b);
        }
        DurableSpool.Batch remaining=planned(db);
        ok("second recent slot picks new run",remaining.runId.equals(newRun));
        ack(db,remaining);
        ok("all eligible original rows ACKed without loss",db.countPending()==0);
        ok("eight durable batch receipts",db.confirmedBatchCount()==8);
        System.out.println("FairDeliveryTest passed "+checks+" checks");
    }
}
