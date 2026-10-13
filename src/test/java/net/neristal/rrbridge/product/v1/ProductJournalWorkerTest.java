package net.neristal.rrbridge.product.v1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/** Separate-JVM journal CLI safety test, real SQLite and synthetic HTTP ONLY. */
public final class ProductJournalWorkerTest {
    private static int checks;
    private static final String TOKEN="synthetic-journal-cli-only-token";
    private static void ok(String name,boolean value){
        if(!value)throw new AssertionError(name);
        checks++;System.out.println("PASS "+name);
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static String javaBin(){
        File f=new File(System.getProperty("java.home"),"bin/java");
        if(!f.isFile())f=new File(System.getProperty("java.home"),"bin/java.exe");
        return f.getAbsolutePath();
    }
    private static final class Result {
        final int exit;final String output;
        Result(int exit,String output){this.exit=exit;this.output=output;}
    }
    private static Result run(String mode,boolean enabled,File sourceDir,File db,
                              String origin,String device,String vehicle)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(javaBin(),"-cp",
            System.getProperty("java.class.path"),ProductBridgeWorker.class.getName(),
            mode,sourceDir.getAbsolutePath(),db.getAbsolutePath(),origin);
        builder.redirectErrorStream(true);
        builder.environment().put("ROMLOGGER_DEVICE_ID",device);
        builder.environment().put("ROMLOGGER_VEHICLE_ID",vehicle);
        builder.environment().put("ROMLOGGER_DEVICE_TOKEN",TOKEN);
        if(enabled)builder.environment().put("ROMLOGGER_JOURNAL_WORKER_ENABLE","1");
        else builder.environment().remove("ROMLOGGER_JOURNAL_WORKER_ENABLE");
        Process p=builder.start();
        if(!p.waitFor(20,TimeUnit.SECONDS)){
            p.destroyForcibly();
            throw new AssertionError("worker hung; process forcibly stopped");
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=p.getInputStream()){
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){
                if(output.size()+n>64000)throw new AssertionError("unbounded worker stdout");
                output.write(buf,0,n);
            }
        }
        return new Result(p.exitValue(),
            new String(output.toByteArray(),StandardCharsets.UTF_8));
    }
    private static File journal(File dir,String session)throws Exception{
        File source=new File(dir,"test_journal.rlj");
        if(!source.createNewFile())throw new IllegalStateException("fixture exists");
        JournalIdentityPrototype.open(source);
        try(IngressJournalPrototype writer=new IngressJournalPrototype(
                source,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            writer.append("M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm");
            writer.append("S\t"+session+"\t1\t0\t1791838800000\tTEST_RPM=1800");
        }
        return source;
    }
    private static String batchId(String json){
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile(
            "\\\"batch_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if(!matcher.find())throw new IllegalArgumentException("test missing batch_id");
        return matcher.group(1);
    }
    public static void main(String[] args)throws Exception{
        File base=Files.createTempDirectory("journal-worker-separate-jvm").toFile();
        File dir=new File(base,"journals");
        if(!dir.mkdir())throw new AssertionError("fixture dir");
        final File source=journal(dir,"syntheticJournal");
        final File db=new File(base,"product.sqlite");
        final String device=id(),vehicle=id();
        final AtomicInteger profiles=new AtomicInteger(),batches=new AtomicInteger();
        HttpServer mock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mock.createContext("/", (HttpExchange req)->{
            String uri=req.getRequestURI().getPath();
            ByteArrayOutputStream reqBody=new ByteArrayOutputStream();
            try(InputStream in=req.getRequestBody()){
                byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1)reqBody.write(b,0,n);
            }
            String json=new String(reqBody.toByteArray(),StandardCharsets.UTF_8);
            String body;
            int http;
            if(uri.startsWith("/api/agent/v1/profile-revisions/")){
                profiles.incrementAndGet();http=201;
                body="{\"status\":\"registered\",\"profile_revision_id\":\""+
                    uri.substring(uri.lastIndexOf('/')+1)+"\"}";
            }else if(uri.equals("/api/agent/v1/telemetry/batches")){
                batches.incrementAndGet();http=200;
                body="{\"status\":\"committed\",\"batch_id\":\""+batchId(json)+
                    "\",\"record_count\":1,\"inserted_count\":1,\"duplicate_count\":0}";
            }else{
                http=404;body="{}";
            }
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            req.getResponseHeaders().add("Content-Type","application/json");
            req.sendResponseHeaders(http,b.length);
            req.getResponseBody().write(b);
            req.close();
        });
        mock.start();
        try{
            String origin="http://127.0.0.1:"+mock.getAddress().getPort();
            Result disabled=run("--journal-once",false,dir,db,origin,device,vehicle);
            ok("journal mode fails without explicit opt-in",disabled.exit==2);
            ok("disabled journal mode cannot create product DB",!db.exists());
            ok("disabled journal mode makes no network request",
                profiles.get()==0&&batches.get()==0);
            Result unsupported=run("--journal-foreground",true,dir,db,origin,device,vehicle);
            ok("journal foreground auto-scan is not implemented",unsupported.exit==2);
            ok("unsupported mode leaves source and outbox untouched",
                source.isFile()&&!db.exists());
            Result first=run("--journal-once",true,dir,db,origin,device,vehicle);
            ok("explicit offline journal worker imports and exits",first.exit==0);
            ok("first worker sends only profile",profiles.get()==1&&batches.get()==0);
            ok("journal product record durable before any Server batch ACK",
                new DurableSpool(db).countPending()==1);
            ok("first worker never exposes device token in stdout",
                !first.output.contains(TOKEN));
            String uuid=JournalIdentityPrototype.open(source).sourceUuid();
            ok("first pass advanced durable journal cursor",
                new DurableSpool(db).loadJournalCursor(uuid).sequence==2);
            Result second=run("--journal-once",true,dir,db,origin,device,vehicle);
            ok("second worker sends exactly one observation batch",
                second.exit==0&&profiles.get()==1&&batches.get()==1);
            ok("actual local ACK persisted across Java process",
                new DurableSpool(db).countAcked()==1);
            Result third=run("--journal-once",true,dir,db,origin,device,vehicle);
            ok("third worker does not replay ACKed data",
                third.exit==0&&batches.get()==1);
            ok("no journal originals are removed by worker",
                source.isFile()&&new File(source.getPath()+".identity").isFile());

            // A new unACKed sample is deliberately left local while the
            // journal is corrupted. No HTTP attempts are permitted afterwards.
            try(IngressJournalPrototype writer=new IngressJournalPrototype(
                    source,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
                writer.append("S\tsyntheticJournal\t1\t1\t1791838800500\tTEST_RPM=1900");
            }
            // New sample is committed into product SQLite, profile already ACKed,
            // but mock server response is not yet required for this assertion.
            try{
                new IncrementalTsvBridge(new DurableSpool(db),device,vehicle)
                    .importJournal(source,100);
            }catch(Exception unexpected){
                throw new AssertionError("Synthetic journal product import failed",unexpected);
            }
            ok("unACKed original prepared before fault",new DurableSpool(db).countPending()==1);
            byte[] clean=Files.readAllBytes(source.toPath());
            try(RandomAccessFile f=new RandomAccessFile(source,"rw")){
                f.seek(16);
                int crc=f.readInt();
                f.seek(16);
                f.writeInt(crc^1);
            }
            int sentBefore=batches.get();
            Result bad=run("--journal-once",true,dir,db,origin,device,vehicle);
            ok("corrupt journal leads to distinct review exit 5",bad.exit==5);
            ok("persistent review status is set on source corruption",
                "JOURNAL_IMPORT".equals(new DurableSpool(db).ingressReviewReason()));
            ok("no HTTP delivery during journal integrity failure",
                batches.get()==sentBefore);
            ok("no raw source or token leaked in review error output",
                !bad.output.contains(TOKEN)&&
                !bad.output.contains("TEST_RPM=1900")&&
                bad.output.contains("INGRESS_REVIEW_REQUIRED"));
            Files.write(source.toPath(),clean);
            Result held=run("--journal-once",true,dir,db,origin,device,vehicle);
            ok("repaired source does NOT clear sticky review after restart",held.exit==5);
            ok("held journal worker never sends queued unACKed data",
                batches.get()==sentBefore&&new DurableSpool(db).countPending()==1);
            Result otherMode=run("--once",true,dir,db,origin,device,vehicle);
            ok("legacy worker sharing held outbox cannot bypass ingress review",
                otherMode.exit==5&&batches.get()==sentBefore);
            ok("confirmed old ACK and new pending original survive hold",
                new DurableSpool(db).countAcked()==1&&new DurableSpool(db).countPending()==1);
        }finally{
            mock.stop(0);
        }
        System.out.println("ProductJournalWorkerTest passed "+checks+
            " checks (separate JVM, SQLite, localhost synthetic HTTP; no ECU)");
    }
}
