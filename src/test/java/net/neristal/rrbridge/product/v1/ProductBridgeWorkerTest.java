package net.neristal.rrbridge.product.v1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes real separate JVM --once worker with a local synthetic HTTP mock. */
public final class ProductBridgeWorkerTest {
    static int checks;
    static void ok(String label,boolean condition){
        if(!condition)throw new AssertionError(label);
        ++checks;System.out.println("PASS "+label);
    }
    static String id(){return UUID.randomUUID().toString();}
    static String javaBinary(){
        File bin=new File(System.getProperty("java.home"),"bin/java");
        if(!bin.isFile())bin=new File(System.getProperty("java.home"),"bin/java.exe");
        return bin.getAbsolutePath();
    }
    static final class Run {
        final int exit;final String output;
        Run(int exit,String output){this.exit=exit;this.output=output;}
    }
    static Run run(File dir,File db,String origin,String device,String vehicle)throws Exception{
        ProcessBuilder proc=new ProcessBuilder(javaBinary(),"-cp",
            System.getProperty("java.class.path"),
            ProductBridgeWorker.class.getName(),"--once",dir.getAbsolutePath(),
            db.getAbsolutePath(),origin);
        proc.redirectErrorStream(true);
        proc.environment().put("ROMLOGGER_DEVICE_ID",device);
        proc.environment().put("ROMLOGGER_VEHICLE_ID",vehicle);
        proc.environment().put("ROMLOGGER_DEVICE_TOKEN","fake-local-ci-worker-token");
        Process p=proc.start();
        if(!p.waitFor(15,TimeUnit.SECONDS)){
            p.destroyForcibly();throw new AssertionError("Worker timed out");
        }
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(InputStream in=p.getInputStream()){
            int c;while((c=in.read())!=-1){
                if(output.size()>64000)throw new AssertionError("Worker output too large");
                output.write(c);
            }
        }
        return new Run(p.exitValue(),
            new String(output.toByteArray(),StandardCharsets.UTF_8));
    }
    static String captureBatch(String request){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile(
            "\\\"batch_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(request);
        if(!m.find())throw new IllegalStateException("Missing batch ID");
        return m.group(1);
    }
    public static void main(String[] args)throws Exception {
        File base=Files.createTempDirectory("product-worker-process-ci").toFile();
        File dir=new File(base,"legacy");dir.mkdirs();
        File source=new File(dir,"rr_demo.tsv");
        String sample="M\tdemo\t1\tTEST_RPM\tSynthetic RPM\trpm\n"+
                      "S\tdemo\t1\t0\t1791838800000\tTEST_RPM=2400\n";
        Files.write(source.toPath(),sample.getBytes(StandardCharsets.UTF_8));
        final String device=id(),vehicle=id();
        File db=new File(base,"outbox.sqlite");
        final AtomicInteger profiles=new AtomicInteger(),batches=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",(HttpExchange req)->{
            String path=req.getRequestURI().getPath();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=req.getRequestBody()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1)bytes.write(buf,0,n);
            }
            String request=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            String body;
            int code;
            if(path.startsWith("/api/agent/v1/profile-revisions/")){
                profiles.incrementAndGet();code=201;
                body="{\"status\":\"registered\",\"profile_revision_id\":\""+
                      path.substring(path.lastIndexOf('/')+1)+"\"}";
            }else if(path.equals("/api/agent/v1/telemetry/batches")){
                batches.incrementAndGet();code=200;
                body="{\"status\":\"committed\",\"batch_id\":\""+captureBatch(request)+
                     "\",\"record_count\":1,\"inserted_count\":1,\"duplicate_count\":0}";
            }else{code=404;body="{}";}
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            req.getResponseHeaders().add("Content-Type","application/json");
            req.sendResponseHeaders(code,b.length);
            req.getResponseBody().write(b);req.close();
        });
        server.start();
        try{
            String origin="http://127.0.0.1:"+server.getAddress().getPort();
            Run first=run(dir,db,origin,device,vehicle);
            ok("first worker process successfully exits",first.exit==0);
            ok("first worker sends profile before telemetry",profiles.get()==1&&batches.get()==0);
            ok("first process does not leak token",!first.output.contains("fake-local-ci-worker-token"));
            DurableSpool local=new DurableSpool(db);
            ok("first process has durable local observation",local.countPending()==1);
            Run second=run(dir,db,origin,device,vehicle);
            ok("second process sends telemetry once",second.exit==0&&batches.get()==1);
            ok("confirmed batch ACK persisted across processes",new DurableSpool(db).countAcked()==1);
            Run third=run(dir,db,origin,device,vehicle);
            ok("third process does not replay ACKed data",third.exit==0&&batches.get()==1);
            ok("source offset stays at EOF",local.loadImportCursor(source.getCanonicalPath())
                .offsetBytes==source.length());
            // Old path missing is fatal even after ACK; retention needs review.
            File moved=new File(dir,"rr_moved.tsv");
            ok("source can be renamed to simulate rotation",source.renameTo(moved));
            Run fourth=run(dir,db,origin,device,vehicle);
            ok("missing source makes separate process exit nonzero",fourth.exit!=0);
            ok("missing source leaves ACK history untouched",new DurableSpool(db).countAcked()==1);
        }finally{server.stop(0);}
        System.out.println("ProductBridgeWorkerTest passed "+checks+" checks (separate JVM / synthetic HTTP)");
    }
}
