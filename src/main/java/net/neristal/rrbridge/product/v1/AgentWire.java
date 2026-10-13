package net.neristal.rrbridge.product.v1;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Java-8-only, dependency-free HTTP/JSON wire handling for the v1 outbox. */
final class AgentWire {
    private AgentWire() {}

    static String quoted(String s) {
        if(s==null) throw new IllegalArgumentException("Null string");
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if(ch=='"' || ch=='\\') b.append('\\').append(ch);
            else if(ch<32) {
                String hex=Integer.toHexString(ch);
                b.append("\\u");
                for(int p=hex.length();p<4;p++) b.append('0');
                b.append(hex);
            } else b.append(ch);
        }
        return b.append('"').toString();
    }

    static String batchJson(String device, String vehicle, DurableSpool.Batch batch) {
        if(batch==null || batch.records.isEmpty()) throw new IllegalArgumentException("Empty batch");
        StringBuilder out=new StringBuilder("{\"protocol_version\":1,\"batch_id\":");
        out.append(quoted(batch.batchId)).append(",\"device_id\":").append(quoted(device))
           .append(",\"vehicle_id\":").append(quoted(vehicle))
           .append(",\"run_id\":").append(quoted(batch.runId))
           .append(",\"records\":[");
        for(int i=0;i<batch.records.size();i++) {
            if(i>0) out.append(',');
            out.append(batch.records.get(i).json);
        }
        return out.append("]}").toString();
    }

    static Map<String,Object> flatObject(String json) {
        if(json==null || json.length()>32768) throw new IllegalArgumentException("JSON response too large");
        Reader r=new Reader(json);
        Map<String,Object> values=new LinkedHashMap<String,Object>();
        r.space();r.take('{');r.space();
        if(r.peek('}')) {r.take('}');r.space();r.end();return values;}
        while(true) {
            String key=r.string();
            r.space();r.take(':');r.space();
            Object value=r.peek('"') ? r.string() : r.number();
            if(values.put(key,value)!=null)
                throw new IllegalArgumentException("Duplicate response field");
            r.space();
            if(r.peek('}')) {r.take('}');break;}
            r.take(',');r.space();
        }
        r.space();r.end();
        return values;
    }

    private static final class Reader {
        private final String s; private int i;
        Reader(String s){this.s=s;}
        void space(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        boolean peek(char ch){return i<s.length()&&s.charAt(i)==ch;}
        void take(char ch){if(!peek(ch))throw new IllegalArgumentException("Malformed JSON");i++;}
        void end(){if(i!=s.length())throw new IllegalArgumentException("Trailing JSON");}
        String string(){
            take('"');StringBuilder out=new StringBuilder();
            while(i<s.length()){
                char c=s.charAt(i++);
                if(c=='"') return out.toString();
                if(c=='\\'){
                    if(i>=s.length())break;
                    char escaped=s.charAt(i++);
                    switch(escaped){
                        case '"':case '\\':case '/':out.append(escaped);break;
                        case 'b':out.append('\b');break;
                        case 'f':out.append('\f');break;
                        case 'n':out.append('\n');break;
                        case 'r':out.append('\r');break;
                        case 't':out.append('\t');break;
                        case 'u':
                            if(i+4>s.length()) throw new IllegalArgumentException("Bad Unicode escape");
                            try { out.append((char)Integer.parseInt(s.substring(i,i+4),16));}
                            catch(NumberFormatException e){throw new IllegalArgumentException("Bad Unicode escape");}
                            i+=4;break;
                        default:throw new IllegalArgumentException("Bad string escape");
                    }
                }else{
                    if(c<32) throw new IllegalArgumentException("Bad JSON string");
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
        Long number(){
            int start=i;
            if(peek('-'))i++;
            if(i>=s.length()||s.charAt(i)<'0'||s.charAt(i)>'9')
                throw new IllegalArgumentException("Non-integer JSON response");
            if(peek('0'))i++;
            else while(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')i++;
            try{return Long.valueOf(s.substring(start,i));}
            catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid integer");}
        }
    }

    static String requireString(Map<String,Object> fields,String key) {
        Object value=fields.get(key);
        if(!(value instanceof String)) throw new IllegalArgumentException("Missing string "+key);
        return (String)value;
    }

    static int requireCount(Map<String,Object> fields,String key) {
        Object value=fields.get(key);
        if(!(value instanceof Long))throw new IllegalArgumentException("Missing count "+key);
        long count=((Long)value).longValue();
        if(count<0||count>128)throw new IllegalArgumentException("Invalid count "+key);
        return (int)count;
    }

    static boolean profileConfirmed(String json,String profileId,int httpStatus) {
        Map<String,Object> fields=flatObject(json);
        String status=requireString(fields,"status");
        // Profile HTTP statuses are part of the frozen wire-v1 contract.
        return ((httpStatus==201 && "registered".equals(status)) ||
                (httpStatus==200 && "already_registered".equals(status))) &&
                profileId.equals(requireString(fields,"profile_revision_id"));
    }

    static DurableSpool.Ack confirmedAck(String json,DurableSpool.Batch batch) {
        Map<String,Object> fields=flatObject(json);
        DurableSpool.Ack ack=new DurableSpool.Ack(
            requireString(fields,"batch_id"),requireString(fields,"status"),
            requireCount(fields,"record_count"),requireCount(fields,"inserted_count"),
            requireCount(fields,"duplicate_count"));
        if(!"committed".equals(ack.status)||!batch.batchId.equals(ack.batchId)||
           ack.recordCount!=batch.records.size()||
           ack.insertedCount+ack.duplicateCount!=ack.recordCount)
            throw new IllegalArgumentException("ACK mismatches requested batch");
        return ack;
    }

    static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status,String body){this.status=status;this.body=body;}
    }

    static final class HttpTransport {
        private final URI base;
        private final String token;
        private final int timeoutMs;
        HttpTransport(String serverBase,String deviceToken,int timeoutMs) throws Exception {
            if(deviceToken==null||deviceToken.isEmpty()||deviceToken.length()>512||
               deviceToken.indexOf('\r')>=0||deviceToken.indexOf('\n')>=0)
                throw new IllegalArgumentException("Invalid device token");
            if(timeoutMs<100||timeoutMs>120000)
                throw new IllegalArgumentException("Invalid HTTP timeout");
            URI u=URI.create(serverBase);
            String scheme=u.getScheme();
            if(u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||
               u.getHost()==null||!(("https".equalsIgnoreCase(scheme))||
                 ("http".equalsIgnoreCase(scheme)&&isLoopbackLiteral(u.getHost()))))
                throw new IllegalArgumentException("HTTPS is mandatory except literal loopback");
            if(u.getPath()!=null && !u.getPath().isEmpty()&&!"/".equals(u.getPath()))
                throw new IllegalArgumentException("Server base must be an origin without path");
            this.base=u;this.token=deviceToken;this.timeoutMs=timeoutMs;
        }

        private static boolean isLoopbackLiteral(String host) {
            // Never resolve an arbitrary DNS name to authorize insecure HTTP.
            return "localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||
                   "[::1]".equals(host)||"::1".equals(host);
        }

        HttpResult send(String method,String path,String request) throws Exception {
            if(!path.startsWith("/api/agent/v1/")||path.contains(".."))
                throw new IllegalArgumentException("Invalid API route");
            URL url=base.resolve(path).toURL();
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization","Bearer "+token);
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            c.setDoOutput(true);
            byte[] bytes=request.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>512*1024)throw new IllegalArgumentException("Body too large");
            try {
                c.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream output=c.getOutputStream()){output.write(bytes);}
                int status=c.getResponseCode();
                if(status>=300) return new HttpResult(status,"");
                String contentType=c.getContentType();
                if(contentType==null||!contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("application/json"))
                    return new HttpResult(status,"");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int n;
                    while((n=in.read(buffer))!=-1){
                        if(out.size()+n>32768)throw new IllegalArgumentException("Response too large");
                        out.write(buffer,0,n);
                    }
                    return new HttpResult(status,new String(out.toByteArray(),StandardCharsets.UTF_8));
                }
            } finally {c.disconnect();}
        }
    }
}
