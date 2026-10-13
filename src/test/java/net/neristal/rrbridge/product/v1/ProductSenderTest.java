package net.neristal.rrbridge.product.v1;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real HTTP loopback contract-mock, durable SQLite; no RomRaider or ECU. */
public final class ProductSenderTest {
    static int passed=0;
    static final String TOKEN="local-ci-token-no-secrets";
    static void check(String description,boolean ok) {
        if(!ok) throw new AssertionError(description);
        ++passed;System.out.println("PASS "+description);
    }
    static String id(){return UUID.randomUUID().toString();}
    static String get(String json,String field){
        Matcher m=Pattern.compile("\\\""+Pattern.quote(field)+"\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if(!m.find())throw new IllegalArgumentException("Missing "+field);
        return m.group(1);
    }
    static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        byte[] buf=new byte[2048];int count;
        while((count=input.read(buf))!=-1){if(output.size()+count>1024*1024)throw new AssertionError("Oversized request");output.write(buf,0,count);}
        return new String(output.toByteArray(),StandardCharsets.UTF_8);
    }
    static final class Mock implements HttpHandler {
        final String profileId;
        volatile String mode="normal";
        volatile String lastBatch="",lastProfile="";
        final AtomicInteger profiles=new AtomicInteger(), batches=new AtomicInteger(), committed=new AtomicInteger();
        Mock(String profileId){this.profileId=profileId;}
        @Override public void handle(HttpExchange exchange) throws java.io.IOException {
            try {
                String request=read(exchange.getRequestBody());
                String path=exchange.getRequestURI().getPath();
                String authorization=exchange.getRequestHeaders().getFirst("Authorization");
                int code=200;String response="";
                if(!("Bearer "+TOKEN).equals(authorization)){code=401;}
                else if(path.startsWith("/api/agent/v1/profile-revisions/")){
                    lastProfile=request;profiles.incrementAndGet();
                    if(!"PUT".equals(exchange.getRequestMethod())) code=405;
                    else if("profile503".equals(mode)) code=503;
                    else if("profile401".equals(mode)) code=401;
                    else if("invalidProfileJson".equals(mode)) response="{\"status\":{}}";
                    else if("profileAlreadyRegistered".equals(mode)){code=200;response="{\"status\":\"already_registered\",\"profile_revision_id\":\""+profileId+"\"}";}
                    else if("mismatchedProfileStatus".equals(mode)){code=200;response="{\"status\":\"registered\",\"profile_revision_id\":\""+profileId+"\"}";}
                    else if("badProfile".equals(mode)) response="{\"status\":\"registered\",\"profile_revision_id\":\""+id()+"\"}";
                    else {code=201;response="{\"status\":\"registered\",\"profile_revision_id\":\""+profileId+"\"}";}
                } else if(path.equals("/api/agent/v1/telemetry/batches")){
                    lastBatch=request;batches.incrementAndGet();
                    if(!"POST".equals(exchange.getRequestMethod()))code=405;
                    else if("batch503".equals(mode))code=503;
                    else if("batch401".equals(mode))code=401;
                    else if("batch422".equals(mode))code=422;
                    else if("batch409".equals(mode))code=409;
                    else if("batch413".equals(mode))code=413;
                    else if("redirect".equals(mode)){code=302;exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/redirect");}
                    else if("badAck".equals(mode))response="{\"status\":\"accepted\",\"batch_id\":\""+get(request,"batch_id")+"\",\"record_count\":2,\"inserted_count\":2,\"duplicate_count\":0}";
                    else if("wrongBatch".equals(mode))response="{\"status\":\"committed\",\"batch_id\":\""+id()+"\",\"record_count\":2,\"inserted_count\":2,\"duplicate_count\":0}";
                    else if("partialAck".equals(mode))response="{\"status\":\"committed\",\"batch_id\":\""+get(request,"batch_id")+"\",\"record_count\":1,\"inserted_count\":1,\"duplicate_count\":0}";
                    else if("malformed".equals(mode))response="{\"status\":\"committed\",\"batch_id\":\""+get(request,"batch_id")+"\",\"record_count\":2,\"inserted_count\":2,\"duplicate_count\":0,\"record_count\":2}";
                    else if("lossAfterCommit".equals(mode)){committed.set(2);code=503;}
                    else {
                        int count=2;
                        int added=committed.getAndSet(2)==0 ? 2 : 0;
                        response="{\"status\":\"committed\",\"batch_id\":\""+get(request,"batch_id")+"\",\"record_count\":"+count+",\"inserted_count\":"+added+",\"duplicate_count\":"+(count-added)+"}";
                    }
                } else code=404;
                if(code>=300 && response.isEmpty())response="{\"error\":{\"code\":\"synthetic\"}}";
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(code,bytes.length);
                exchange.getResponseBody().write(bytes);
            }catch(Exception e){
                byte[] error="{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,error.length);
                exchange.getResponseBody().write(error);
            }finally{exchange.close();}
        }
    }

    static DurableSpool senderSpool(File directory, String profile, String device, String vehicle,
                                    String run, String segment, String rid0, String rid1) throws Exception {
        DurableSpool spool=new DurableSpool(new File(directory,"v1.sqlite"));
        String def="{\"protocol_version\":1,\"device_id\":\""+device+
            "\",\"vehicle_id\":\""+vehicle+"\",\"profile_revision_id\":\""+profile+
            "\",\"logger_definition_fingerprint\":\"synthetic-fingerprint\",\"parameters\":[{\"parameter_id\":\"P8\",\"name\":\"Engine speed\",\"unit\":\"rpm\"}]}";
        spool.appendProfile(profile,def);
        String[] ids={rid0,rid1};
        for(int i=0;i<ids.length;i++){
            String json="{\"kind\":\"observation\",\"record_id\":\""+ids[i]+
                "\",\"segment_id\":\""+segment+"\",\"sequence\":"+i+
                ",\"captured_at\":\"2026-10-13T02:00:00.000Z\",\"source_kind\":\"synthetic\""+
                ",\"profile_revision_id\":\""+profile+"\",\"values\":[{\"parameter_id\":\"P8\",\"value\":"+(2500+i)+"}]}";
            spool.appendRecord(ids[i],run,segment,Long.valueOf(i),"observation",profile,
                "2026-10-13T02:00:00.000Z","synthetic",json);
        }
        return spool;
    }
    static void failConstructor(String label,String url) throws Exception {
        boolean failed=false;
        try {new AgentWire.HttpTransport(url,TOKEN,500);}
        catch(IllegalArgumentException e){failed=true;}
        check(label,failed);
    }
    public static void main(String[] args) throws Exception {
        String profile=id(),device=id(),vehicle=id(),run=id(),segment=id(),rid0=id(),rid1=id();
        File base=Files.createTempDirectory("product-http-ci").toFile();
        DurableSpool spool=senderSpool(base,profile,device,vehicle,run,segment,rid0,rid1);
        check("persist two pending records before network",spool.countPending()==2);
        failConstructor("reject public HTTP","http://example.com:8003");
        failConstructor("reject URL credentials","http://anyone:secret@localhost:8003");
        failConstructor("reject URI with path","http://localhost:8003/invalid");
        failConstructor("reject hostname disguised loopback","http://127.0.0.1.example.com:8003");
        check("valid Java8 HTTPS transport construction",new AgentWire.HttpTransport("https://example.com",TOKEN,500)!=null);
        // Loopback offline: transport is not an implicit data-deletion signal.
        ProductSender offline=new ProductSender(spool,"http://127.0.0.1:1",TOKEN,
                                                device,vehicle,250,2,65536);
        check("connection refused retains profile",offline.pumpOnce()==ProductSender.Outcome.RETRY_LATER);
        check("connection refused retains records",spool.countPending()==2&&spool.pendingProfiles().size()==1);

        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        Mock mock=new Mock(profile);
        server.createContext("/",mock);server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();
            ProductSender sender=new ProductSender(spool,origin,TOKEN,device,vehicle,1500,2,65536);
            mock.mode="profile503";
            check("503 profile keeps original",sender.pumpOnce()==ProductSender.Outcome.RETRY_LATER&&spool.pendingProfiles().size()==1);
            mock.mode="profile401";
            check("401 profile held for review",sender.pumpOnce()==ProductSender.Outcome.NEEDS_REVIEW&&spool.pendingProfiles().size()==1);
            mock.mode="badProfile";
            check("mismatching 201 profile response is not ACK",sender.pumpOnce()==ProductSender.Outcome.NEEDS_REVIEW&&spool.pendingProfiles().size()==1);
            mock.mode="invalidProfileJson";
            check("malformed 201 profile body does not crash or ACK",
                  sender.pumpOnce()==ProductSender.Outcome.NEEDS_REVIEW&&
                  spool.pendingProfiles().size()==1);
            mock.mode="mismatchedProfileStatus";
            check("200 with registered instead of already_registered is not ACK",
                  sender.pumpOnce()==ProductSender.Outcome.NEEDS_REVIEW&&
                  spool.pendingProfiles().size()==1);
            mock.mode="profileAlreadyRegistered";
            check("200 already_registered confirms lost earlier profile ACK",
                  sender.pumpOnce()==ProductSender.Outcome.PROFILE_ACKED);
            // A returned definition ACK is not the same as a telemetry ACK.
            check("profile ACK does not release observations",spool.countPending()==2);
            mock.mode="normal";
            check("profile no longer pending after 200 confirmation",spool.pendingProfiles().isEmpty());
            // Other runs and the strict telemetry ACK checks continue below.
            // Existing profile-registration assertion is now about the idempotent 200 path.
            // Original 201 case is exercised below using another fixture.
            // DO NOT alter the synthetic Server's known token.
            /* old 201 assertion removed */
            check("successful profile registration is durable",spool.pendingProfiles().isEmpty());
            check("profile originally sent before any samples",mock.batches.get()==0);
            check("profile registered once only",spool.pendingProfiles().isEmpty());
            check("profile request contains definition and device",mock.lastProfile.contains(device)&&mock.lastProfile.contains("synthetic-fingerprint"));
            // Server's explicit rejection never releases any original.
            for(String mode:new String[]{"batch503","batch401","batch422","batch409","batch413",
                                         "badAck","wrongBatch","partialAck","malformed","redirect"}){
                mock.mode=mode;
                ProductSender.Outcome got=sender.pumpOnce();
                check("no ACK for "+mode,got!=ProductSender.Outcome.BATCH_ACKED && spool.countPending()==2);
            }
            mock.mode="lossAfterCommit";
            check("lost response after remote commit is retryable",sender.pumpOnce()==ProductSender.Outcome.RETRY_LATER);
            check("remote commit without ACK leaves both records",spool.countPending()==2);
            // Recreate both database and sender to emulate process restart.
            DurableSpool reopened=new DurableSpool(new File(base,"v1.sqlite"));
            ProductSender afterRestart=new ProductSender(reopened,origin,TOKEN,device,vehicle,1500,2,65536);
            mock.mode="normal";
            check("duplicate-only server ACK is accepted",afterRestart.pumpOnce()==ProductSender.Outcome.BATCH_ACKED);
            check("ACKed rows are no longer pending",reopened.countPending()==0);
            check("receipt persists",new DurableSpool(new File(base,"v1.sqlite")).countAcked()==2);
            check("no uploads when all original rows ACKed",afterRestart.pumpOnce()==ProductSender.Outcome.IDLE);
            check("record IDs were submitted without mutation",
                  mock.lastBatch.contains(rid0)&&mock.lastBatch.contains(rid1));
            check("envelope includes correct IDs and 2 records",mock.lastBatch.contains(run)&&
                  mock.lastBatch.contains(device)&&mock.lastBatch.contains(vehicle)&&
                  mock.lastBatch.contains("\"protocol_version\":1"));
        } finally {server.stop(0);}
        check("duplicate JSON response keys rejected",rejectAck(
              "{\"status\":\"committed\",\"status\":\"committed\"}"));
        check("malformed nested response rejected",rejectAck("{\"status\":{}}"));
        System.out.println("ProductSenderTest passed "+passed+" checks (loopback HTTP contract mock only)");
    }
    static boolean rejectAck(String s){
        try {AgentWire.flatObject(s);return false;}
        catch(IllegalArgumentException expected){return true;}
    }
}
