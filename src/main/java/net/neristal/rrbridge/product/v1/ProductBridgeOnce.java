package net.neristal.rrbridge.product.v1;

import java.io.File;

/**
 * Explicit opt-in one-shot bridge from a legacy RRBridge TSV file.
 * Does not modify or truncate the TSV; no scheduler/Logger hook is installed.
 *
 * Usage (do not put token on command line):
 * ROMLOGGER_DEVICE_ID=... ROMLOGGER_VEHICLE_ID=... ROMLOGGER_DEVICE_TOKEN=...
 * java ... ProductBridgeOnce rr_session.tsv product.sqlite https://server
 */
public final class ProductBridgeOnce {
    private static String requiredEnv(String name) {
        String value=System.getenv(name);
        if(value==null||value.isEmpty())throw new IllegalStateException("Missing "+name);
        return value;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3) {
            System.err.println("Usage: ProductBridgeOnce <legacy-rr_*.tsv> <sqlite-db> <server-origin>");
            System.exit(2);
        }
        String device=requiredEnv("ROMLOGGER_DEVICE_ID");
        String vehicle=requiredEnv("ROMLOGGER_VEHICLE_ID");
        String token=requiredEnv("ROMLOGGER_DEVICE_TOKEN");
        DurableSpool spool=new DurableSpool(new File(args[1]));
        // Bounded incremental scan. Cursor advances only after the exact
        // complete TSV line has reached the durable SQLite outbox.
        IncrementalTsvBridge adapter=new IncrementalTsvBridge(spool,device,vehicle);
        IncrementalTsvBridge.Result result=adapter.importNew(new File(args[0]),256);
        System.out.println("Durable imported TSV lines="+result.processedLines+
            " metadata="+result.metadata+" observations="+result.observations+
            " events="+result.events+" offset_bytes="+result.offsetBytes+
            " incompleteTail="+result.incompleteTail+" unacked="+spool.countPending());
        // Maximum one profile or data HTTP transaction. External scheduler
        // decides when to retry; 409/422/401 must NOT be retried aggressively.
        ProductSender sender=new ProductSender(spool,args[2],token,device,vehicle,
                                               3000,64,200000);
        ProductSender.Outcome outcome=sender.pumpOnce();
        System.out.println("Delivery outcome="+outcome+" unacked="+spool.countPending());
        if(outcome==ProductSender.Outcome.NEEDS_REVIEW)System.exit(3);
        if(outcome==ProductSender.Outcome.RETRY_LATER)System.exit(4);
    }
}
