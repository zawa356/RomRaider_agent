package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * EXPLICIT, separate-process legacy TSV scanner + durable HTTP retry worker.
 *
 * Not installed by premain/Agent, not started in the Logger JVM; no ECU
 * connection or remote commands. Operator supplies credentials via env.
 *
 * Usage:
 * java ... ProductBridgeWorker --once <legacy-spool-directory> <sqlite-db> <server-origin>
 * java ... ProductBridgeWorker --foreground <legacy-spool-directory> <sqlite-db> <server-origin>
 * java ... ProductBridgeWorker --journal-once <OFFLINE-journal-directory> <sqlite-db> <server-origin>
 * The journal mode requires ROMLOGGER_JOURNAL_WORKER_ENABLE=1 and a CLOSED
 * source writer. No --journal-foreground until concurrent reading is designed.
 */
public final class ProductBridgeWorker {
    private static String env(String name){
        String value=System.getenv(name);
        if(value==null||value.isEmpty())throw new IllegalStateException("Missing "+name);
        return value;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4 ||
           !("--once".equals(args[0])||"--foreground".equals(args[0])||
             "--journal-once".equals(args[0]))) {
            System.err.println("Usage: ProductBridgeWorker (--once|--foreground|--journal-once) "+
                "<dedicated-source-dir> <sqlite-db> <server-origin>");
            System.exit(2);
        }
        boolean journalMode="--journal-once".equals(args[0]);
        if(journalMode && !"1".equals(System.getenv("ROMLOGGER_JOURNAL_WORKER_ENABLE"))) {
            System.err.println("Journal mode disabled; explicit opt-in required");
            System.exit(2);
        }
        boolean continuous="--foreground".equals(args[0]);
        String device=env("ROMLOGGER_DEVICE_ID"),vehicle=env("ROMLOGGER_VEHICLE_ID");
        String token=env("ROMLOGGER_DEVICE_TOKEN");
        File dbFile=new File(args[2]).getCanonicalFile();
        File parent=dbFile.getParentFile();
        if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))
            throw new IllegalStateException("Cannot create private database directory");
        File lockFile=new File(dbFile.getPath()+".worker.lock");
        try(RandomAccessFile lockHandle=new RandomAccessFile(lockFile,"rw")){
            FileLock lock=null;
            try {lock=lockHandle.getChannel().tryLock();}
            catch(OverlappingFileLockException alreadyRunning){}
            if(lock==null)throw new IllegalStateException("Another product worker owns this spool");
            try {
                DurableSpool spool=new DurableSpool(dbFile);
                // A previously recorded ingress integrity fault blocks ALL
                // future imports and network delivery from the same outbox.
                if(spool.ingressReviewReason()!=null) {
                    System.err.println("INGRESS_REVIEW_REQUIRED; stop before send");
                    System.exit(5);
                }
                ProductLegacyScanner scanner=journalMode?null:
                    new ProductLegacyScanner(spool,device,vehicle,new File(args[1]));
                ProductJournalScanner journalScanner=null;
                if(journalMode){
                    try{
                        journalScanner=new ProductJournalScanner(spool,device,vehicle,new File(args[1]));
                    }catch(Exception invalid){
                        spool.requireIngressReview("JOURNAL_PREFLIGHT");
                        System.err.println("INGRESS_REVIEW_REQUIRED; journal preflight rejected");
                        System.exit(5);
                    }
                }
                ProductSender sender=new ProductSender(spool,args[3],token,device,vehicle,
                    3000,64,200000);
                ProductDeliveryCoordinator coordinator=
                    ProductDeliveryCoordinator.forSender(spool,sender);
                do {
                    // Import local original into SQLite even while remote delivery
                    // is waiting: never block ingestion on a network outage.
                    int importedLines;
                    if(journalMode) {
                        try {
                            // Fail closed and permanently latch the fault.
                            // Never auto-resume, delete originals or send
                            // previously staged records after a bad source.
                            importedLines=journalScanner.scanOnce(256).importedLines;
                        }catch(Exception invalid){
                            spool.requireIngressReview("JOURNAL_IMPORT");
                            System.err.println("INGRESS_REVIEW_REQUIRED; journal originals retained");
                            System.exit(5);
                            throw new IllegalStateException("Unreachable after exit");
                        }
                    }else{
                        importedLines=scanner.scanOnce(256).importedLines;
                    }
                    ProductDeliveryCoordinator.Result result=
                        coordinator.tick(System.currentTimeMillis());
                    // Never print credentials, record bodies or response URLs.
                    if(importedLines>0||result==
                       ProductDeliveryCoordinator.Result.REVIEW_REQUIRED) {
                        System.out.println("imported_lines="+importedLines+
                            " pending_records="+spool.countPending()+
                            " delivery="+result);
                    }
                    if(result==ProductDeliveryCoordinator.Result.REVIEW_REQUIRED) {
                        System.err.println("Delivery stopped for operator investigation; "+
                            "originals and retry state retained");
                        System.exit(3);
                    }
                    if(!continuous)break;
                    Thread.sleep(result==ProductDeliveryCoordinator.Result.ACKED?50:1000);
                } while(!Thread.currentThread().isInterrupted());
            } finally {lock.release();}
        }
    }
}
