package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Standalone Java 8, GUI/ECU independent integration tests against real SQLite. */
public final class DurableSpoolTest {
    private static int passed=0;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        passed++;
        System.out.println("PASS "+label);
    }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String sample(String rid, long seq) {
        return "{\"kind\":\"observation\",\"record_id\":\""+rid+
            "\",\"sequence\":"+seq+",\"values\":[{\"parameter_id\":\"P8\",\"value\":2000}]}";
    }
    private static void expectFailure(String label, Throwing action) throws Exception {
        boolean failed=false;
        try { action.run(); } catch (IllegalArgumentException ex) { failed=true; }
        catch (IllegalStateException ex) { failed=true; }
        check(label,failed);
    }
    private interface Throwing { void run() throws Exception; }

    private static void record(DurableSpool spool, String rid, String run,
                               String seg, String profile, long seq) throws Exception {
        spool.appendRecord(rid,run,seg,Long.valueOf(seq),"observation",profile,
                           "2026-10-13T02:00:00+09:00","synthetic",sample(rid,seq));
    }

    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("product-spool-ci-").toFile();
        File file=new File(dir,"product-outbox.sqlite");
        DurableSpool spool=new DurableSpool(file);
        final DurableSpool initialSpool=spool;
        String run=id(), seg=id(), profile=id();
        String profileJson="{\"profile_revision_id\":\""+profile+"\",\"parameters\":[\"P8\"]}";
        spool.appendProfile(profile,profileJson);
        spool.appendProfile(profile,profileJson);
        check("pending profile survives registration",spool.pendingProfiles().size()==1);
        expectFailure("profile immutability", new Throwing() {public void run() throws Exception {
            initialSpool.appendProfile(profile,"{\"profile_revision_id\":\"changed\"}");
        }});
        String first=id();
        record(spool,first,run,seg,profile,0);
        check("unACKed observation is durable",spool.countPending()==1L);
        check("samples blocked until profile ACK",spool.nextBatch(20,65536)==null);
        expectFailure("profile cannot ACK without confirmation",new Throwing() {
            public void run() throws Exception { initialSpool.markProfileAcked(profile,false); }
        });
        check("unconfirmed profile not lost",spool.pendingProfiles().size()==1);

        // Restart before any upload; the same local UUID and original JSON must survive.
        spool=new DurableSpool(file);
        final DurableSpool opened=spool;
        check("restart keeps pending row",opened.countPending()==1L);
        check("restart keeps profile pending",opened.pendingProfiles().size()==1);
        opened.markProfileAcked(profile,true);
        check("profile receipt retained, content reclaimed",opened.pendingProfiles().isEmpty());
        DurableSpool.Batch b=opened.nextBatch(20,65536);
        check("profile unlocks observation",b!=null && b.records.size()==1);
        check("first sample ID survives",b.records.get(0).recordId.equals(first));
        check("run preserved",b.runId.equals(run));
        check("original serialized JSON preserved",b.records.get(0).json.equals(sample(first,0)));

        final DurableSpool.Batch one=b;
        final DurableSpool.Ack wrongId=new DurableSpool.Ack(id(),"committed",1,1,0);
        expectFailure("wrong batch ID does not ACK",new Throwing(){
            public void run() throws Exception {opened.markBatchAcked(one,wrongId);}
        });
        expectFailure("partial count does not ACK",new Throwing(){
            public void run() throws Exception {
                opened.markBatchAcked(one,new DurableSpool.Ack(one.batchId,"committed",0,0,0));
            }
        });
        expectFailure("uncommitted status does not ACK",new Throwing(){
            public void run() throws Exception {
                opened.markBatchAcked(one,new DurableSpool.Ack(one.batchId,"accepted",1,1,0));
            }
        });
        expectFailure("inconsistent inserted and duplicate counts does not ACK",new Throwing(){
            public void run() throws Exception {
                opened.markBatchAcked(one,new DurableSpool.Ack(one.batchId,"committed",1,1,1));
            }
        });
        check("rejecting malformed ACK preserves unsent",opened.countPending()==1L);

        // Pretend the Server committed, then lost the response; reopen to retry.
        DurableSpool restarted=new DurableSpool(file);
        DurableSpool.Batch retry=restarted.nextBatch(20,65536);
        check("lost ACK survives process restart",retry!=null &&
              retry.records.get(0).recordId.equals(first));
        check("retry creates new transport batch ID",!retry.batchId.equals(b.batchId));
        restarted.markBatchAcked(retry,new DurableSpool.Ack(retry.batchId,"committed",1,0,1));
        check("duplicate-only durable Server ACK releases original",restarted.countPending()==0L);
        check("minimal ACK receipt retained",restarted.countAcked()==1L);
        DurableSpool postAck=new DurableSpool(file);
        check("ACK survives restart",postAck.countPending()==0L && postAck.countAcked()==1L);
        expectFailure("same receipt cannot be ACKed again as new batch",new Throwing(){
            public void run() throws Exception {
                postAck.markBatchAcked(retry,new DurableSpool.Ack(retry.batchId,"committed",1,0,1));
            }
        });
        // An old record with same immutable ID+content is tolerated, but never requeued.
        record(postAck,first,run,seg,profile,0);
        check("record UUID replay is deduplicated",postAck.countPending()==0L);
        expectFailure("record UUID different content rejected",new Throwing(){
            public void run() throws Exception {
                postAck.appendRecord(first,run,seg,0L,"observation",profile,
                    "2026-10-13T02:00:00+09:00","synthetic",
                    "{\"record_id\":\""+first+"\",\"changed\":true}");
            }
        });
        expectFailure("run sequence collision with new UUID rejected",new Throwing(){
            public void run() throws Exception {record(postAck,id(),run,seg,profile,0);}
        });
        check("collision cannot create ghost pending records",postAck.countPending()==0L);

        // Local row envelopes must not mix different runs in the same HTTP batch.
        String run2=id(),seg2=id();
        List<String> all=new ArrayList<String>();
        for(int i=1;i<=6;i++) {
            String rid=id();all.add(rid);
            record(postAck,rid,run,seg,profile,i);
        }
        record(postAck,id(),run2,seg2,profile,0);
        check("7 rows are pending",postAck.countPending()==7L);
        DurableSpool.Batch firstChunk=postAck.nextBatch(3,65536);
        check("batch size max 3",firstChunk.records.size()==3);
        check("first chunk is same run",firstChunk.runId.equals(run));
        for(int i=0;i<3;i++)check("insertion order "+i,firstChunk.records.get(i).recordId.equals(all.get(i)));
        postAck.markBatchAcked(firstChunk,
            new DurableSpool.Ack(firstChunk.batchId,"committed",3,3,0));
        check("ACK only exact selected 3 records",postAck.countPending()==4L);
        DurableSpool.Batch secondChunk=postAck.nextBatch(128,65536);
        check("next run is not mixed into batch",secondChunk.records.size()==3 &&
              secondChunk.runId.equals(run));
        postAck.markBatchAcked(secondChunk,
            new DurableSpool.Ack(secondChunk.batchId,"committed",3,2,1));
        DurableSpool.Batch nextRun=postAck.nextBatch(128,65536);
        check("other run is separate batch",nextRun.records.size()==1 &&
              nextRun.runId.equals(run2));
        expectFailure("too small byte limit rejects oversized first row",new Throwing() {
            public void run() throws Exception { postAck.nextBatch(1,128); }
        });
        expectFailure("batch limit rejects 129",new Throwing(){
            public void run() throws Exception { postAck.nextBatch(129,65536); }
        });
        check("invalid bounds do not delete pending",postAck.countPending()==1L);
        postAck.markBatchAcked(nextRun,
            new DurableSpool.Ack(nextRun.batchId,"committed",1,1,0));
        check("no pending observations",postAck.countPending()==0L);
        check("ACKed bodies reclaimable without deleting receipts",postAck.countAcked()==8L);

        System.out.println("DurableSpoolTest passed "+passed+" checks (synthetic SQLite only)");
    }
}
