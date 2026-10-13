package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/** Real SQLite, synthetic closed journals, no ECU and no network. */
public final class ProductJournalScannerTest {
    private static int checks;
    private interface Action{void run() throws Exception;}
    private static void ok(String name,boolean yes) {
        if(!yes)throw new AssertionError(name);
        checks++;System.out.println("PASS "+name);
    }
    private static void reject(String name,Action action)throws Exception {
        boolean denied=false;
        try { action.run(); }
        catch(IllegalStateException | IllegalArgumentException | java.io.IOException expected) {
            denied=true;
        }
        ok(name,denied);
    }
    private static File dir(File base,String name)throws Exception{
        File d=new File(base,name);
        if(!d.mkdir())throw new IllegalStateException("fixture directory exists");
        return d;
    }
    private static File source(File dir,String name,String session)throws Exception{
        File j=new File(dir,name+".rlj");
        if(!j.createNewFile())throw new IllegalStateException("fixture exists");
        JournalIdentityPrototype.open(j);
        try(IngressJournalPrototype w=new IngressJournalPrototype(j,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            w.append("M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm");
            w.append("S\t"+session+"\t1\t0\t1791838800000\tTEST_RPM=1800");
        }
        return j;
    }
    private static String id(){return UUID.randomUUID().toString();}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("romlogger-journal-scanner-test").toFile();
        String device=id(),vehicle=id();
        File firstDir=dir(root,"original");
        File db=new File(root,"spool.sqlite");
        final DurableSpool spool=new DurableSpool(db);
        ProductJournalScanner scanner=new ProductJournalScanner(spool,device,vehicle,firstDir);
        ok("empty dedicated journal directory has no work",scanner.scanOnce(1).sources==0);
        File j=source(firstDir,"ingress_a","session_a");
        ok("bounded journal import includes only definition first",
            scanner.scanOnce(1).importedLines==1&&spool.countPending()==0);
        ok("next scan imports observation via product v1 SQLite",
            scanner.scanOnce(1).samples==1&&spool.countPending()==1);
        ok("repeated scanner run is idempotent",scanner.scanOnce(50).importedLines==0);
        scanner=new ProductJournalScanner(new DurableSpool(db),device,vehicle,firstDir);
        ok("scanner restart preserves source cursor",scanner.scanOnce(50).importedLines==0);
        ok("journal source location stored durably",
            spool.journalImportedSources().size()==1 &&
            spool.journalImportedSources().get(0).path.equals(j.getCanonicalPath()));
        ok("initial ingress hold is absent",spool.ingressReviewReason()==null);

        File wrongDir=dir(root,"different");
        final ProductJournalScanner noSourceScanner=
            new ProductJournalScanner(spool,device,vehicle,wrongDir);
        reject("known original cannot silently vanish from chosen directory",
            new Action(){public void run()throws Exception{noSourceScanner.scanOnce(20);}});
        ok("missing original leaves durable source and rows unchanged",
            spool.countPending()==1&&spool.journalImportedSources().size()==1);

        File duplicate=new File(firstDir,"ingress_b.rlj");
        Files.copy(j.toPath(),duplicate.toPath());
        Files.copy(new File(j.getPath()+".identity").toPath(),
                   new File(duplicate.getPath()+".identity").toPath());
        final ProductJournalScanner duplicateScanner=
            new ProductJournalScanner(spool,device,vehicle,firstDir);
        reject("copied source UUID collision is rejected before import",
            new Action(){public void run()throws Exception{duplicateScanner.scanOnce(50);}});
        ok("duplicate UUID never creates extra product row",spool.countPending()==1);
        Files.delete(duplicate.toPath());
        Files.delete(new File(duplicate.getPath()+".identity").toPath());

        File empty=new File(firstDir,"empty.rlj");
        if(!empty.createNewFile())throw new AssertionError("empty fixture");
        final ProductJournalScanner missingIdScanner=
            new ProductJournalScanner(spool,device,vehicle,firstDir);
        reject("empty journal without sidecar does not auto-mint UUID",
            new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        ok("failed scanner did not create identity sidecar",
            !new File(empty.getPath()+".identity").exists());
        Files.delete(empty.toPath());

        File orphan=new File(firstDir,"missing.rlj.identity");
        Files.write(orphan.toPath(),new byte[]{0});
        reject("orphan identity is rejected before product mutation",
            new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        Files.delete(orphan.toPath());

        File mixed=new File(firstDir,"rr_session_a.tsv");
        Files.write(mixed.toPath(),"dummy".getBytes(StandardCharsets.UTF_8));
        reject("journal-only directory rejects mixed legacy TSV",
            new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        Files.delete(mixed.toPath());

        File unexpected=new File(firstDir,"other.db");
        Files.write(unexpected.toPath(),new byte[]{0});
        reject("source directory must not contain unrecognized files",
            new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        Files.delete(unexpected.toPath());

        try(IngressJournalPrototype locked=
                new IngressJournalPrototype(j,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            reject("active journal writer lock prevents offline import",
                new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        }
        ok("journal becomes readable after writer closes",
            missingIdScanner.scanOnce(50).importedLines==0);

        File manyDir=dir(root,"too-many");
        for(int i=0;i<17;i++){
            if(!new File(manyDir,"log"+i+".rlj").createNewFile())
                throw new IllegalStateException("fixture");
        }
        final ProductJournalScanner bounded=
            new ProductJournalScanner(new DurableSpool(new File(root,"bounded.sqlite")),
                id(),id(),manyDir);
        reject("more than sixteen journals fail closed",
            new Action(){public void run()throws Exception{bounded.scanOnce(10);}});

        java.nio.file.Path dirLink=new File(root,"directory-link").toPath();
        try {
            Files.createSymbolicLink(dirLink,firstDir.toPath());
            reject("symlink directory is rejected when supported",
                new Action(){public void run()throws Exception {
                    new ProductJournalScanner(spool,device,vehicle,dirLink.toFile());
                }});
        }catch(UnsupportedOperationException | java.nio.file.FileSystemException unavailable){
            // Windows CI may not permit creating symlinks. Do not claim PASS
            // for a test that could not be executed on that runner.
            System.out.println("SKIP symlink directory test (OS privileges unavailable)");
        }

        spool.requireIngressReview("JOURNAL_IMPORT");
        ok("review reason persists durably",
            "JOURNAL_IMPORT".equals(new DurableSpool(db).ingressReviewReason()));
        spool.requireIngressReview("JOURNAL_PREFLIGHT");
        ok("first ingress review reason retained after later fault",
            "JOURNAL_IMPORT".equals(spool.ingressReviewReason()));
        reject("invalid reason code cannot be stored",
            new Action(){public void run()throws Exception{spool.requireIngressReview("RAW_SECRET");}});
        reject("sticky review blocks scanner after source recovery",
            new Action(){public void run()throws Exception{missingIdScanner.scanOnce(50);}});
        ok("held spool retains unACKed source row",spool.countPending()==1);
        System.out.println("ProductJournalScannerTest passed "+checks+" checks (SQLite; synthetic closed journal)");
    }
}
