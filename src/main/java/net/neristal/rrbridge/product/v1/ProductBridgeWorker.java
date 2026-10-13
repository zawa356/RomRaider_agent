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
 */
public final class ProductBridgeWorker {
    private static String env(String name){
        String value=System.getenv(name);
        if(value==null||value.isEmpty())throw new IllegalStateException("Missing "+name);
        return value;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=4 ||
           !("--once".equals(args[0])||"--foreground".equals(args[0]))) {
            System.err.println("Usage: ProductBridgeWorker (--once|--foreground) "+
                "<rrbridge-spool-dir> <sqlite-db> <server-origin>");
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
                ProductLegacyScanner scanner=new ProductLegacyScanner(spool,device,vehicle,
                    new File(args[1]));
                ProductSender sender=new ProductSender(spool,args[3],token,device,vehicle,
                    3000,64,200000);
                ProductDeliveryCoordinator coordinator=
                    ProductDeliveryCoordinator.forSender(spool,sender);
                do {
                    // Import local original into SQLite even while remote delivery
                    // is waiting: never block ingestion on a network outage.
                    ProductLegacyScanner.Result imported=scanner.scanOnce(256);
                    ProductDeliveryCoordinator.Result result=
                        coordinator.tick(System.currentTimeMillis());
                    // Never print credentials, record bodies or response URLs.
                    if(imported.importedLines>0||result==
                       ProductDeliveryCoordinator.Result.REVIEW_REQUIRED) {
                        System.out.println("imported_lines="+imported.importedLines+
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
