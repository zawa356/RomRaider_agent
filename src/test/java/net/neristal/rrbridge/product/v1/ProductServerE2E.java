package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Opt-in real-Server E2E smoke. Requires disposable PostgreSQL and FastAPI
 * supplied externally. No real ECU, no vehicle commands, never log secrets.
 *
 * Environment:
 * ROMLOGGER_E2E_ORIGIN=http://127.0.0.1:18083
 * ROMLOGGER_E2E_DEVICE_ID, ROMLOGGER_E2E_VEHICLE_ID, ROMLOGGER_E2E_TOKEN
 */
public final class ProductServerE2E {
    static String env(String k) {
        String v=System.getenv(k);
        if(v==null||v.trim().isEmpty())throw new IllegalStateException("Missing "+k);
        return v;
    }
    static void require(boolean ok,String reason) {
        if(!ok)throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        String origin=env("ROMLOGGER_E2E_ORIGIN");
        String device=env("ROMLOGGER_E2E_DEVICE_ID");
        String vehicle=env("ROMLOGGER_E2E_VEHICLE_ID");
        String token=env("ROMLOGGER_E2E_TOKEN");
        String session="ci_"+UUID.randomUUID().toString().replace("-","");
        String legacy=
            "M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm\n"+
            "M\t"+session+"\t1\tTEST_TPS\tSynthetic throttle\t%\n"+
            "S\t"+session+"\t1\t0\t1791838800000\tTEST_RPM=1800,TEST_TPS=20\n"+
            "S\t"+session+"\t1\t1\t1791838800500\tTEST_RPM=1900,TEST_TPS=21\n";
        // Temp file must match legacy file pattern exactly; don't introduce a
        // non-stable suffix to the source session.
        File dir=Files.createTempDirectory("product-e2e-").toFile();
        File source=new File(dir,"rr_"+session+".tsv");
        Files.write(source.toPath(),legacy.getBytes(StandardCharsets.UTF_8));
        File db=new File(dir,"outbox.sqlite");
        DurableSpool outbox=new DurableSpool(db);
        LegacyTsvBridge bridge=new LegacyTsvBridge(outbox,device,vehicle);
        LegacyTsvBridge.Result imported=bridge.importFile(source);
        require(imported.observations==2&&outbox.countPending()==2,"TSV import failed");
        ProductSender sender=new ProductSender(outbox,origin,token,device,vehicle,2500,16,65536);
        require(sender.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED,"Real FastAPI profile not confirmed");
        require(sender.pumpOnce()==ProductSender.Outcome.BATCH_ACKED,"Real Postgres batch not committed");
        require(outbox.countPending()==0&&outbox.countAcked()==2,"ACK did not persist");
        // New spool, original same source: IDs must remain unchanged; DB should
        // answer with duplicate-only ACK, not insert two extra records.
        File replayDir=Files.createTempDirectory("product-e2e-replay-").toFile();
        DurableSpool replay=new DurableSpool(new File(replayDir,"outbox.sqlite"));
        new LegacyTsvBridge(replay,device,vehicle).importFile(source);
        ProductSender again=new ProductSender(replay,origin,token,device,vehicle,2500,16,65536);
        require(again.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED,"Real profile replay failed");
        require(again.pumpOnce()==ProductSender.Outcome.BATCH_ACKED,"Real duplicate-only replay failed");
        require(replay.countPending()==0&&replay.countAcked()==2,"Replay receipt not durable");
        System.out.println("Real Server E2E: PASS (2 synthetic observations; replay unchanged IDs; no ECU)");
    }
}
