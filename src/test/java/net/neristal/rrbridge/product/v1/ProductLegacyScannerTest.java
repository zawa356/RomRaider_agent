package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Scanner safety tests on real SQLite and synthetic legacy TSV files. */
public final class ProductLegacyScannerTest {
    static int checks;
    static void ok(String name,boolean pass){
        if(!pass)throw new AssertionError(name);
        ++checks;System.out.println("PASS "+name);
    }
    static void rejected(String name,ProductLegacyScanner scanner)throws Exception {
        boolean fail=false;
        try{scanner.scanOnce(256);}
        catch(IllegalStateException expected){fail=true;}
        ok(name,fail);
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("v1-legacy-watchdog-").toFile();
        File db=new File(root,"outbox.sqlite");
        File dir=new File(root,"legacy");dir.mkdirs();
        File tsv=new File(dir,"rr_test1.tsv");
        String m="M\ttest1\t1\tTEST_RPM\tSynthetic RPM\trpm\n";
        String s="S\ttest1\t1\t0\t1791838800000\tTEST_RPM=1900\n";
        Files.write(tsv.toPath(),(m+s).getBytes(StandardCharsets.UTF_8));
        DurableSpool spool=new DurableSpool(db);
        String device=UUID.randomUUID().toString(),vehicle=UUID.randomUUID().toString();
        ProductLegacyScanner scanner=new ProductLegacyScanner(spool,device,vehicle,dir);
        ProductLegacyScanner.Result initial=scanner.scanOnce(1);
        ok("bounded file scan reads first metadata",initial.importedLines==1);
        ok("not-yet-read row retained for next round",spool.countPending()==0);
        ok("later scan picks up existing sample",scanner.scanOnce(1).samples==1&&spool.countPending()==1);
        ok("at EOF scan is idle",scanner.scanOnce(256).importedLines==0);
        ok("committed legacy source tracked",spool.importedSourcePaths().size()==1);
        Files.write(tsv.toPath(),
            "S\ttest1\t1\t1\t1791838800100\tTEST_RPM=2000".getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND);
        ok("partial LF kept out of SQLite",scanner.scanOnce(256).importedLines==0&&spool.countPending()==1);
        Files.write(tsv.toPath(),"\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
        ok("complete LF inserted exactly once",scanner.scanOnce(256).samples==1&&spool.countPending()==2);
        spool=new DurableSpool(db);
        scanner=new ProductLegacyScanner(spool,device,vehicle,dir);
        ok("new scanner resumes from durable cursor",scanner.scanOnce(256).importedLines==0);
        ok("no ACK from local scan alone",spool.countAcked()==0&&spool.countPending()==2);
        File moved=new File(dir,"rr_test2.tsv");
        if(!tsv.renameTo(moved))throw new IllegalStateException("Unable to test rename");
        rejected("source rename is a fail-closed error",scanner);
        ok("no original removed after rename",spool.countPending()==2);
        if(!moved.renameTo(tsv))throw new IllegalStateException("Unable to restore");
        ok("restored file is readable",scanner.scanOnce(256).importedLines==0);
        for(int i=2;i<=65;i++){
            File extra=new File(dir,"rr_extra"+i+".tsv");
            Files.write(extra.toPath(),new byte[0]);
        }
        rejected("too many source files requires review",scanner);
        ok("overflow listing does not delete originals",spool.countPending()==2);
        System.out.println("ProductLegacyScannerTest passed "+checks+" checks (real SQLite, no ECU)");
    }
}
