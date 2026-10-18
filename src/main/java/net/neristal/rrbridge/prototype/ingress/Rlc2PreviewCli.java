package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.PrintStream;

/** Explicit synthetic import into dedicated preview tables. No transport exists here. */
public final class Rlc2PreviewCli {
    private Rlc2PreviewCli(){}
    public static int run(String[] args,PrintStream out,PrintStream err,WatermarkV2Prototype.ReadOpener reader) {
        if(args.length!=6||!"--enable-synthetic-preview".equals(args[0])) {
            err.println("Usage: Rlc2PreviewCli --enable-synthetic-preview SESSION LEDGER DEVICE_UUID VEHICLE_UUID OUTPUT.rlc2-preview.db");return 2;
        }
        try {
            Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,new File(args[1]).toPath(),new File(args[2]),args[3],args[4],reader);
            Rlc2PreviewStore.Result r=new Rlc2PreviewStore(true,new File(args[5])).ingest(p);
            out.println("{\"schema_version\":1,\"status\":\"PREVIEW_STORED\",\"ack_eligible\":false,\"directory_durability\":\"UNKNOWN\",\"records_added\":"+r.recordsAdded+",\"receipts_added\":"+r.receiptsAdded+"}");return 0;
        }catch(Exception failure) { err.println("{\"schema_version\":1,\"status\":\"REVIEW_REQUIRED\",\"code\":\"PREVIEW_VALIDATION_OR_STORAGE_FAILED\",\"ack_eligible\":false}");return 5; }
    }
    public static void main(String[] args) {System.exit(run(args,System.out,System.err,WatermarkV2Prototype.POSIX_READER));}
}
