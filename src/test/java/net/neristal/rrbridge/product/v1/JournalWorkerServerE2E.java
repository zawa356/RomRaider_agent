package net.neristal.rrbridge.product.v1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/**
 * Dedicated real FastAPI/PostgreSQL E2E for the opt-in OFFLINE Worker.
 * Only synthetic TEST_RPM input and ephemeral Server-issued token.
 * Never attaches to a running Logger/ECU or starts a writer during import.
 */
public final class JournalWorkerServerE2E {
    private static String required(String key){
        String v=System.getenv(key);
        if(v==null||v.trim().isEmpty())throw new IllegalStateException("Missing "+key);
        return v;
    }
    private static void require(boolean yes,String message){
        if(!yes)throw new AssertionError(message);
    }
    private static File javaBin(){
        File f=new File(System.getProperty("java.home"),"bin/java");
        if(!f.isFile())f=new File(System.getProperty("java.home"),"bin/java.exe");
        return f;
    }
    private static void worker(File dir,File db,String origin,String device,String vehicle,
                               String token)throws Exception{
        ProcessBuilder pb=new ProcessBuilder(javaBin().getAbsolutePath(),"-cp",
            System.getProperty("java.class.path"),ProductBridgeWorker.class.getName(),
            "--journal-once",dir.getAbsolutePath(),db.getAbsolutePath(),origin);
        pb.redirectErrorStream(true);
        pb.environment().put("ROMLOGGER_DEVICE_ID",device);
        pb.environment().put("ROMLOGGER_VEHICLE_ID",vehicle);
        pb.environment().put("ROMLOGGER_DEVICE_TOKEN",token);
        pb.environment().put("ROMLOGGER_JOURNAL_WORKER_ENABLE","1");
        Process p=pb.start();
        if(!p.waitFor(30,TimeUnit.SECONDS)){
            p.destroyForcibly();
            throw new AssertionError("Real Server synthetic journal worker timed out");
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=p.getInputStream()){
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){
                if(output.size()+n>32000)throw new AssertionError("Worker output too large");
                output.write(buf,0,n);
            }
        }
        String log=new String(output.toByteArray(),StandardCharsets.UTF_8);
        require(!log.contains(token),"Synthetic real-Server token exposed in worker logs");
        require(p.exitValue()==0,"Journal worker failed during real Server E2E");
    }
    public static void main(String[] args)throws Exception{
        String origin=required("ROMLOGGER_E2E_ORIGIN");
        String device=required("ROMLOGGER_E2E_DEVICE_ID");
        String vehicle=required("ROMLOGGER_E2E_VEHICLE_ID");
        String token=required("ROMLOGGER_E2E_TOKEN");
        File root=Files.createTempDirectory("real-server-journal-worker-e2e").toFile();
        File dir=new File(root,"closed-journals");
        require(dir.mkdir(),"source directory could not be created");
        File source=new File(dir,"worker_source.rlj");
        require(source.createNewFile(),"test source unexpectedly exists");
        String id=JournalIdentityPrototype.open(source).sourceUuid();
        String session="ci_worker_"+UUID.randomUUID().toString().replace("-","");
        try(IngressJournalPrototype w=new IngressJournalPrototype(
                source,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            w.append("M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm");
            w.append("S\t"+session+"\t1\t0\t1791838800000\tTEST_RPM=2500");
        }
        File db=new File(root,"product.sqlite");
        worker(dir,db,origin,device,vehicle,token);
        DurableSpool spool=new DurableSpool(db);
        require(spool.countPending()==1&&spool.countAcked()==0,
            "Worker failed to import journal or incorrectly ACKed before batch");
        require(spool.loadJournalCursor(id).sequence==2,
            "Worker did not advance durable journal cursor");
        worker(dir,db,origin,device,vehicle,token);
        require(new DurableSpool(db).countPending()==0&&
                new DurableSpool(db).countAcked()==1,
            "Actual PostgreSQL batch ACK did not persist in local SQLite");
        worker(dir,db,origin,device,vehicle,token);
        require(new DurableSpool(db).countAcked()==1,
            "Worker unexpectedly retransmitted fully ACKed data");

        // Fresh local SQLite imports the identical immutable journal and
        // receives two genuine duplicate-only Server acknowledgements.
        File replay=new File(root,"independent-replay.sqlite");
        worker(dir,replay,origin,device,vehicle,token);
        require(new DurableSpool(replay).countPending()==1,
            "Fresh outbox failed to import synthetic journal original");
        worker(dir,replay,origin,device,vehicle,token);
        require(new DurableSpool(replay).countAcked()==1,
            "Fresh outbox failed duplicate-only Postgres ACK");
        require(new DurableSpool(replay).ingressReviewReason()==null,
            "Fresh outbox was spuriously latched for review");
        require(source.isFile()&&new File(source.getPath()+".identity").isFile(),
            "Worker deleted or moved original journal/identity");
        System.out.println("Real Server Journal Worker E2E: PASS (offline --journal-once, "+
            "profile+batch ACK, fresh SQLite duplicate-only replay, no ECU)");
    }
}
