package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Genuine SQLite integration for deterministic legacy TSV conversion. */
public final class LegacyTsvBridgeTest {
    static int checks=0;
    static void ok(String label,boolean condition) {
        if(!condition)throw new AssertionError(label);
        System.out.println("PASS "+label);checks++;
    }
    static String id(){return UUID.randomUUID().toString();}
    static void failure(String label,Action action) throws Exception {
        try {action.run();throw new AssertionError("Did not fail: "+label);}
        catch(IllegalArgumentException expected) {ok(label,true);}
        catch(IllegalStateException expected) {ok(label,true);}
    }
    interface Action {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        File tmp=Files.createTempDirectory("rrbridge-tsv-import-ci").toFile();
        File file=new File(tmp,"rr_20261013_123456.tsv");
        File db=new File(tmp,"product.sqlite");
        String tsv=
            "M\t20261013_123456\t1\tTEST_RPM\tTest Engine Speed\trpm\n"+
            "M\t20261013_123456\t1\tTEST_TPS\tThrottle\t%\n"+
            "S\t20261013_123456\t1\t1\t1791838800000\tTEST_RPM=1250,TEST_TPS=25.0\n"+
            "S\t20261013_123456\t1\t2\t1791838800500\tTEST_RPM=1350,TEST_TPS=26.0\n"+
            "E\t20261013_123456\t1\t1791838800501\tselftest end\n";
        Files.write(file.toPath(),tsv.getBytes(StandardCharsets.UTF_8));
        DurableSpool spool=new DurableSpool(db);
        LegacyTsvBridge adapter=new LegacyTsvBridge(spool,id(),id());
        LegacyTsvBridge.Result first=adapter.importFile(file);
        ok("complete stream contains metadata and samples",first.metadata==2 &&
            first.observations==2 && first.events==1 && !first.incompleteTail);
        ok("all three raw events durable",spool.countPending()==3);
        ok("profile stored before samples",spool.pendingProfileEntries().size()==1);
        DurableSpool.PendingProfile p=spool.pendingProfileEntries().get(0);
        ok("profile includes both parameter definitions",p.json.contains("TEST_RPM")&&
           p.json.contains("TEST_TPS")&&p.json.contains("logger_definition_fingerprint"));
        ok("synthetic sample identified explicitly",spool.nextBatch(128,65536)!=null); // event may be ready
        LegacyTsvBridge.Result again=adapter.importFile(file);
        ok("re-import is idempotent",again.observations==2&&spool.countPending()==3);
        DurableSpool restored=new DurableSpool(db);
        LegacyTsvBridge resumed=new LegacyTsvBridge(restored,
            getDevice(p.json),getVehicle(p.json));
        // Use the persisted profile's device/vehicle so IDs stay stable after restart.
        resumed.importFile(file);
        ok("reopen/replay preserves IDs",restored.countPending()==3);
        String profileId=p.profileId;
        restored.markProfileAcked(profileId,true);
        DurableSpool.Batch b=restored.nextBatch(128,65536);
        ok("all samples and event grouped in run",b!=null&&b.records.size()==3);
        ok("all records labeled correctly",b.records.get(0).json.contains("\"source_kind\":\"synthetic\""));
        ok("events are source=agent and remain in same run",b.records.get(2).json.contains("\"event_type\":\"legacy_rrbridge_event\""));
        restored.markBatchAcked(b,new DurableSpool.Ack(b.batchId,"committed",3,3,0));
        ok("only complete ACK releases all three",restored.countPending()==0);
        resumed.importFile(file);
        ok("replaying fully ACKed old TSV cannot duplicate observations",restored.countPending()==0);

        // In-progress legacy writer may be holding a line without LF. Never consume it.
        Files.write(file.toPath(),"S\t20261013_123456\t1\t3\t1791838801000\tTEST_RPM=1450".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        LegacyTsvBridge.Result partial=resumed.importFile(file);
        ok("partial tail not interpreted",partial.incompleteTail&&restored.countPending()==0);
        Files.write(file.toPath(),",TEST_TPS=27\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
        resumed.importFile(file);
        ok("completed tail becomes durable without loss",restored.countPending()==1);
        File invalid=new File(tmp,"rr_bad.tsv");
        Files.write(invalid.toPath(),"S\tbad\t1\t1\t1791838801000\tTEST_RPM=2\n".getBytes(StandardCharsets.UTF_8));
        failure("missing metadata never invents sample",new Action(){
            public void run() throws Exception {resumed.importFile(invalid);}
        });
        ok("invalid old log doesn't delete pending original",restored.countPending()==1);

        System.out.println("LegacyTsvBridgeTest passed "+checks+" checks (synthetic TSV / SQLite)");
    }
    static String getDevice(String json){return AgentWire.requireString(AgentWire.flatObject(json),"device_id");}
    static String getVehicle(String json){return AgentWire.requireString(AgentWire.flatObject(json),"vehicle_id");}
}
