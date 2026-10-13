package net.neristal.rrbridge.product.v1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in, bounded, append-only TSV importer. A complete line is persisted
 * before its checkpoint. DurableSpool CAS prevents stale cursor overwrite.
 *
 * Separate from legacy Stream callbacks. Does NOT solve upstream queue drops
 * or switch on-road operation; no background thread is started.
 */
public final class IncrementalTsvBridge {
    public static final class Result {
        public final int metadata, observations, events, processedLines;
        public final boolean incompleteTail;
        public final long offsetBytes;
        Result(int m,int s,int e,int lines,boolean tail,long offset) {
            metadata=m;observations=s;events=e;processedLines=lines;
            incompleteTail=tail;offsetBytes=offset;
        }
    }
    private static final class Param {
        final String name,unit;
        Param(String name,String unit){this.name=name;this.unit=unit;}
    }
    private static final class Segment {
        boolean started;
        final LinkedHashMap<String,Param> params=new LinkedHashMap<String,Param>();
    }
    private final DurableSpool spool;
    private final String deviceId,vehicleId;
    private final Runnable beforeCheckpoint;

    public IncrementalTsvBridge(DurableSpool spool,String deviceId,String vehicleId){
        this(spool,deviceId,vehicleId,null);
    }
    // Package-private deterministic CI failpoint; production leaves it null.
    IncrementalTsvBridge(DurableSpool spool,String deviceId,String vehicleId,
                         Runnable beforeCheckpoint){
        if(spool==null)throw new IllegalArgumentException("Spool required");
        this.spool=spool;
        this.deviceId=UUID.fromString(deviceId).toString();
        this.vehicleId=UUID.fromString(vehicleId).toString();
        this.beforeCheckpoint=beforeCheckpoint;
    }
    private String uuid(String kind,String session,String segment,String key){
        return UUID.nameUUIDFromBytes(
            ("RomLogger-v1|"+deviceId+"|"+kind+"|"+session+"|"+segment+"|"+key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String str(String s){return AgentWire.quoted(s);}
    private static long nonnegative(String s){
        long value=Long.parseLong(s);
        if(value<0)throw new IllegalArgumentException("Negative legacy timestamp/sequence");
        return value;
    }
    private static String digest(RandomAccessFile file,long start,int count) throws Exception{
        long where=file.getFilePointer();
        byte[] b=new byte[count];file.seek(start);file.readFully(b);file.seek(where);
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        byte[] hash=sha.digest(b);
        StringBuilder out=new StringBuilder();
        for(byte x:hash)out.append(String.format("%02x",x&0xff));
        return out.toString();
    }
    private static String encode(Map<String,Segment> segments)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(segments.size());
        for(Map.Entry<String,Segment> s:segments.entrySet()){
            out.writeUTF(s.getKey());out.writeBoolean(s.getValue().started);
            out.writeInt(s.getValue().params.size());
            for(Map.Entry<String,Param> p:s.getValue().params.entrySet()){
                out.writeUTF(p.getKey());out.writeUTF(p.getValue().name);
                out.writeUTF(p.getValue().unit);
            }
        }
        out.close();
        if(bytes.size()>1024*1024)
            throw new IllegalStateException("Legacy metadata snapshot exceeds 1 MiB");
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private static Map<String,Segment> decode(String snapshot)throws Exception{
        LinkedHashMap<String,Segment> segments=new LinkedHashMap<String,Segment>();
        if(snapshot==null||snapshot.isEmpty())return segments;
        byte[] bytes=Base64.getDecoder().decode(snapshot);
        if(bytes.length>1024*1024)throw new IllegalStateException("Oversized metadata cursor");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        int count=in.readInt();
        if(count<0||count>10000)throw new IllegalStateException("Invalid metadata segments");
        for(int i=0;i<count;i++){
            String key=in.readUTF();
            Segment segment=new Segment();segment.started=in.readBoolean();
            int size=in.readInt();
            if(size<0||size>1000)throw new IllegalStateException("Invalid metadata count");
            for(int j=0;j<size;j++)
                segment.params.put(in.readUTF(),new Param(in.readUTF(),in.readUTF()));
            if(segments.put(key,segment)!=null)
                throw new IllegalStateException("Duplicated metadata segment");
        }
        if(in.available()!=0)throw new IllegalStateException("Malformed cursor metadata");
        return segments;
    }
    private String profileJson(String id,Segment segment){
        StringBuilder s=new StringBuilder("{\"protocol_version\":1,\"device_id\":")
            .append(str(deviceId)).append(",\"vehicle_id\":").append(str(vehicleId))
            .append(",\"profile_revision_id\":").append(str(id))
            .append(",\"logger_definition_fingerprint\":")
            .append(str("legacy-tsv-"+id)).append(",\"parameters\":[");
        int n=0;
        for(Map.Entry<String,Param> p:segment.params.entrySet()){
            if(n++>0)s.append(',');
            s.append("{\"parameter_id\":").append(str(p.getKey()))
             .append(",\"name\":").append(str(p.getValue().name))
             .append(",\"unit\":").append(str(p.getValue().unit)).append('}');
        }
        return s.append("]}").toString();
    }
    private String profileId(String session,String segment,Segment value){
        StringBuilder definition=new StringBuilder();
        for(Map.Entry<String,Param> p:value.params.entrySet()){
            definition.append(p.getKey()).append('\t').append(p.getValue().name)
                .append('\t').append(p.getValue().unit).append('\n');
        }
        return uuid("profile",session,segment,definition.toString());
    }
    private int apply(String line,long lineNumber,String session,Map<String,Segment> state)
            throws Exception {
        String[] t=line.split("\\t",-1);
        if(t.length<3||!session.equals(t[1]))
            throw new IllegalArgumentException("Wrong source session at line "+lineNumber);
        String segment=t[2];long segOrdinal=nonnegative(segment);
        Segment meta=state.get(segment);
        if(meta==null){meta=new Segment();state.put(segment,meta);}
        if("M".equals(t[0])){
            if(t.length!=6||t[3].isEmpty()||t[4].isEmpty())
                throw new IllegalArgumentException("Invalid definition at line "+lineNumber);
            Param old=meta.params.get(t[3]);
            if((old!=null&&(!old.name.equals(t[4])||!old.unit.equals(t[5])))||
               (meta.started&&old==null))
                throw new IllegalStateException("Legacy definition changed after sample");
            meta.params.put(t[3],new Param(t[4],t[5]));
            return 1;
        }
        final String run=uuid("run",session,"","");
        final String segmentId=uuid("segment",session,segment,"");
        if("S".equals(t[0])){
            if(t.length!=6||meta.params.isEmpty()||t[5].isEmpty())
                throw new IllegalArgumentException("Missing sample fields/metadata at line "+lineNumber);
            long seq=nonnegative(t[3]),millis=nonnegative(t[4]);
            String[] values=t[5].split(",",-1);
            if(values.length>200)throw new IllegalArgumentException("Too many values");
            StringBuilder arr=new StringBuilder();boolean synthetic=true;
            for(String field:values){
                int pos=field.indexOf('=');
                if(pos<=0||pos!=field.lastIndexOf('='))
                    throw new IllegalArgumentException("Malformed sample value");
                String id=field.substring(0,pos);
                if(!meta.params.containsKey(id))
                    throw new IllegalStateException("Value lacks definition");
                double value=Double.parseDouble(field.substring(pos+1));
                if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite sample");
                if(arr.length()>0)arr.append(',');
                arr.append("{\"parameter_id\":").append(str(id))
                   .append(",\"value\":").append(Double.toString(value)).append('}');
                if(!id.startsWith("TEST_"))synthetic=false;
            }
            String pid=profileId(session,segment,meta);
            String rid=uuid("observation",session,"",Long.toString(seq));
            String at=Instant.ofEpochMilli(millis).toString();
            String source=synthetic?"synthetic":"ecu";
            String json="{\"kind\":\"observation\",\"record_id\":"+str(rid)
                +",\"segment_id\":"+str(segmentId)+",\"sequence\":"+seq
                +",\"captured_at\":"+str(at)+",\"source_kind\":"+str(source)
                +",\"profile_revision_id\":"+str(pid)+",\"values\":["+arr+"]}";
            spool.appendProfile(pid,profileJson(pid,meta));
            spool.appendRecord(rid,run,segmentId,Long.valueOf(seq),"observation",
                               pid,at,source,json);
            meta.started=true;
            return 2;
        }
        if("E".equals(t[0])){
            if(t.length!=5)throw new IllegalArgumentException("Malformed event");
            String at=Instant.ofEpochMilli(nonnegative(t[3])).toString();
            String rid=uuid("event",session,segment,Long.toString(lineNumber));
            String json="{\"kind\":\"event\",\"record_id\":"+str(rid)
                +",\"captured_at\":"+str(at)
                +",\"source_kind\":\"agent\",\"event_type\":\"legacy_rrbridge_event\""
                +",\"data\":{\"message\":"+str(t[4])
                +",\"legacy_line\":"+lineNumber+"}";
            String usedSegment=segOrdinal>0?segmentId:null;
            if(usedSegment!=null)json+=",\"segment_id\":"+str(usedSegment);
            json+="}";
            spool.appendRecord(rid,run,usedSegment,null,"event",null,at,"agent",json);
            return 3;
        }
        throw new IllegalArgumentException("Unexpected legacy kind at line "+lineNumber);
    }

    /**
     * Imports at most maxLines COMPLETED lines. Opening a fresh instance is
     * safe; per-line cursor state is stored in the same durable SQLite.
     *
     * Does not consume incomplete LF-less bytes. Rejects truncation,
     * replacement or mutations at a saved source boundary rather than
     * silently skipping a potentially missing span.
     */
    public Result importNew(File source,int maxLines)throws Exception{
        if(source==null||!source.isFile()||!source.getName().startsWith("rr_")||
           !source.getName().endsWith(".tsv")||maxLines<1||maxLines>10000)
            throw new IllegalArgumentException("Invalid source or import limit");
        String path=source.getCanonicalPath();
        String session=source.getName().substring(3,source.getName().length()-4);
        BasicFileAttributes attr=Files.readAttributes(source.toPath(),BasicFileAttributes.class);
        String key=attr.fileKey()==null?"unknown":attr.fileKey().toString();
        DurableSpool.ImportCursor cursor=spool.loadImportCursor(path);
        Map<String,Segment> state=decode(cursor==null?"":cursor.snapshot);
        int m=0,s=0,e=0,complete=0;
        boolean partial=false;
        long last=cursor==null?0:cursor.offsetBytes;
        try(RandomAccessFile input=new RandomAccessFile(source,"r")){
            if(cursor!=null){
                if(!cursor.deviceId.equals(deviceId)||!cursor.vehicleId.equals(vehicleId)||
                   !cursor.fileKey.equals(key)||input.length()<cursor.offsetBytes)
                    throw new IllegalStateException("TSV source identity changed/truncated");
                if(!digest(input,0,cursor.headBytes).equals(cursor.headSha256)||
                   !digest(input,Math.max(0,last-128),(int)Math.min(128,last))
                       .equals(cursor.tailSha256))
                    throw new IllegalStateException("TSV source modified before checkpoint");
            }
            input.seek(last);
            for(int i=0;i<maxLines;i++){
                ByteArrayOutputStream line=new ByteArrayOutputStream();
                int byteValue;
                boolean completeLine=false;
                while((byteValue=input.read())!=-1){
                    if(byteValue==10){completeLine=true;break;}
                    if(line.size()>=65536)throw new IllegalStateException("TSV line > 64KiB");
                    line.write(byteValue);
                }
                if(!completeLine){
                    partial=line.size()>0;break;
                }
                long end=input.getFilePointer();
                String text=new String(line.toByteArray(),StandardCharsets.UTF_8);
                if(text.endsWith("\r"))text=text.substring(0,text.length()-1);
                long lineNo=(cursor==null?0:cursor.lineNumber)+1;
                int kind=text.isEmpty()?0:apply(text,lineNo,session,state);
                int headBytes=cursor==null?(int)Math.min(256,end):cursor.headBytes;
                String head=cursor==null?digest(input,0,headBytes):cursor.headSha256;
                String tail=digest(input,Math.max(0,end-128),(int)Math.min(128,end));
                DurableSpool.ImportCursor next=new DurableSpool.ImportCursor(
                    path,deviceId,vehicleId,key,end,lineNo,headBytes,head,tail,encode(state));
                // Data/profile append has COMMITTED at this point. A crash
                // here leaves the cursor behind, causing harmless replay.
                if(beforeCheckpoint!=null)beforeCheckpoint.run();
                spool.saveImportCursor(cursor,next);
                cursor=next;last=end;complete++;
                if(kind==1)m++;else if(kind==2)s++;else if(kind==3)e++;
            }
        }
        return new Result(m,s,e,complete,partial,last);
    }
}
