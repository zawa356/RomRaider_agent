package net.neristal.rrbridge.product.v1;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only importer for existing RRBridge v0.5 TSV spool:
 * M session segment param_id name units
 * S session segment sequence epoch_ms id=value,id=value,...
 * E session segment epoch_ms message
 *
 * Produces immutable v1 JSON in SQLite without altering RomRaider's Logger
 * callback, original TSV, send cursor, or legacy safety controls.
 *
 * Phase 2.1-C1: explicit invocation only, no background tailing and no
 * guaranteed continuity across legacy queue overflow or TSV rotation.
 */
public final class LegacyTsvBridge {
    public static final class Result {
        public final int metadata, observations, events;
        public final boolean incompleteTail;
        Result(int m,int s,int e,boolean tail) {
            metadata=m;observations=s;events=e;incompleteTail=tail;
        }
    }
    private final DurableSpool spool;
    private final String deviceId,vehicleId;

    public LegacyTsvBridge(DurableSpool spool,String deviceId,String vehicleId) {
        if(spool==null)throw new IllegalArgumentException("Spool required");
        this.spool=spool;
        this.deviceId=UUID.fromString(deviceId).toString();
        this.vehicleId=UUID.fromString(vehicleId).toString();
    }
    private static String identity(String text) {
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private String derived(String kind,String session,String segment,String key) {
        return identity("RomLogger-v1|"+deviceId+"|"+kind+"|"+session+"|"+segment+"|"+key);
    }
    private static long positiveLong(String input,String label) {
        long result=Long.parseLong(input);
        if(result<0)throw new IllegalArgumentException("Negative "+label);
        return result;
    }
    private static String now(long millis) {
        if(millis<0)throw new IllegalArgumentException("Negative timestamp");
        return Instant.ofEpochMilli(millis).toString();
    }
    private static final class Param {
        final String id,name,unit;
        Param(String id,String name,String unit){this.id=id;this.name=name;this.unit=unit;}
    }
    private static final class Segment {
        final LinkedHashMap<String,Param> params=new LinkedHashMap<String,Param>();
        String profileId;
        boolean started;
    }

    private static String profileJson(String device,String vehicle,String profileId,
                                      LinkedHashMap<String,Param> definitions) {
        StringBuilder s=new StringBuilder("{\"protocol_version\":1,\"device_id\":")
            .append(AgentWire.quoted(device)).append(",\"vehicle_id\":")
            .append(AgentWire.quoted(vehicle)).append(",\"profile_revision_id\":")
            .append(AgentWire.quoted(profileId))
            .append(",\"logger_definition_fingerprint\":")
            .append(AgentWire.quoted("legacy-tsv-"+profileId))
            .append(",\"parameters\":[");
        int i=0;
        for(Param p:definitions.values()) {
            if(i++>0)s.append(',');
            s.append("{\"parameter_id\":").append(AgentWire.quoted(p.id))
             .append(",\"name\":").append(AgentWire.quoted(p.name))
             .append(",\"unit\":").append(AgentWire.quoted(p.unit)).append('}');
        }
        return s.append("]}").toString();
    }
    private String register(Segment segment,String session,String segNum) throws Exception {
        if(segment.params.isEmpty())throw new IllegalStateException("Missing segment metadata");
        StringBuilder key=new StringBuilder();
        for(Param p:segment.params.values())key.append(p.id).append('\t').append(p.name)
             .append('\t').append(p.unit).append('\n');
        // Definitions are immutable; changing metadata under same legacy segment
        // produces a distinct profile ID, never edits an ACKed profile.
        String pid=derived("profile",session,segNum,key.toString());
        spool.appendProfile(pid,profileJson(deviceId,vehicleId,pid,segment.params));
        segment.profileId=pid;segment.started=true;
        return pid;
    }

    private static String[] fields(String line) {
        return line.split("\\t",-1);
    }

    /** Stream only complete LF-terminated lines; partial file writes remain for next scan. */
    public Result importFile(File source) throws Exception {
        if(source==null || !source.isFile() || !source.getName().startsWith("rr_")
           || !source.getName().endsWith(".tsv"))
            throw new IllegalArgumentException("Expected RRBridge rr_*.tsv spool");
        // Legacy filename forms a namespace; re-importing after reboot is idempotent.
        final String fileSession=source.getName().substring(3,source.getName().length()-4);
        final Map<String,Segment> segments=new LinkedHashMap<String,Segment>();
        int metadata=0,observations=0,events=0;
        long lineNumber=0;
        boolean incomplete=false;
        ByteArrayOutputStream buf=new ByteArrayOutputStream();
        try(BufferedInputStream input=new BufferedInputStream(new FileInputStream(source))) {
            int b;
            while((b=input.read())!=-1) {
                if(b!=10) {
                    if(buf.size()>=65536)throw new IllegalStateException("Legacy TSV line exceeds 64 KiB");
                    buf.write(b);continue;
                }
                lineNumber++;
                String line=new String(buf.toByteArray(),StandardCharsets.UTF_8);
                buf.reset();
                if(line.endsWith("\r"))line=line.substring(0,line.length()-1);
                if(line.length()==0)continue;
                String[] t=fields(line);
                if(t.length<3 || !fileSession.equals(t[1]))
                    throw new IllegalArgumentException("Invalid legacy session at line "+lineNumber);
                String segNum=t[2];
                long ordinal=positiveLong(segNum,"segment");
                Segment segment=segments.get(segNum);
                if(segment==null) {segment=new Segment();segments.put(segNum,segment);}
                if("M".equals(t[0])) {
                    if(t.length!=6 || t[3].isEmpty() || t[4].isEmpty())
                        throw new IllegalArgumentException("Invalid metadata at line "+lineNumber);
                    Param existing=segment.params.get(t[3]);
                    if(existing!=null && (!existing.name.equals(t[4]) ||
                                           !existing.unit.equals(t[5])))
                        throw new IllegalStateException("Conflicting parameter definition");
                    if(segment.started && existing==null)
                        throw new IllegalStateException("Metadata changes after samples");
                    segment.params.put(t[3],new Param(t[3],t[4],t[5]));
                    metadata++;
                } else if("S".equals(t[0])) {
                    if(t.length!=6)throw new IllegalArgumentException("Invalid observation at line "+lineNumber);
                    long seq=positiveLong(t[3],"sequence");
                    long epoch=positiveLong(t[4],"captured time");
                    String runId=derived("run",fileSession,"","");
                    String segmentId=derived("segment",fileSession,segNum,"");
                    String profile=register(segment,fileSession,segNum);
                    String rid=derived("observation",fileSession,"",Long.toString(seq));
                    String[] values=t[5].split(",",-1);
                    if(values.length==0 || t[5].isEmpty())throw new IllegalArgumentException("Empty values");
                    StringBuilder obj=new StringBuilder();
                    boolean synthetic=true;
                    for(String field:values) {
                        int eq=field.indexOf('=');
                        if(eq<=0||eq!=field.lastIndexOf('='))
                            throw new IllegalArgumentException("Invalid value at line "+lineNumber);
                        String param=field.substring(0,eq);
                        if(!segment.params.containsKey(param))
                            throw new IllegalStateException("Value without metadata at line "+lineNumber);
                        double val=Double.parseDouble(field.substring(eq+1));
                        if(!Double.isFinite(val))throw new IllegalArgumentException("Nonfinite observation");
                        if(obj.length()>0)obj.append(',');
                        obj.append("{\"parameter_id\":").append(AgentWire.quoted(param))
                           .append(",\"value\":").append(Double.toString(val)).append('}');
                        if(!param.startsWith("TEST_"))synthetic=false;
                    }
                    String at=now(epoch);
                    String kind=synthetic?"synthetic":"ecu";
                    String json="{\"kind\":\"observation\",\"record_id\":"+AgentWire.quoted(rid)
                        +",\"segment_id\":"+AgentWire.quoted(segmentId)
                        +",\"sequence\":"+seq+",\"captured_at\":"+AgentWire.quoted(at)
                        +",\"source_kind\":"+AgentWire.quoted(kind)
                        +",\"profile_revision_id\":"+AgentWire.quoted(profile)
                        +",\"values\":["+obj+"]}";
                    spool.appendRecord(rid,runId,segmentId,Long.valueOf(seq),"observation",
                                       profile,at,kind,json);
                    observations++;
                } else if("E".equals(t[0])) {
                    if(t.length!=5)throw new IllegalArgumentException("Invalid event at line "+lineNumber);
                    String at=now(positiveLong(t[3],"event time"));
                    String runId=derived("run",fileSession,"","");
                    String segmentId=ordinal>0?derived("segment",fileSession,segNum,""):null;
                    String rid=derived("event",fileSession,segNum,Long.toString(lineNumber));
                    String json="{\"kind\":\"event\",\"record_id\":"+AgentWire.quoted(rid)
                        +",\"captured_at\":"+AgentWire.quoted(at)
                        +",\"source_kind\":\"agent\",\"event_type\":\"legacy_rrbridge_event\""
                        +",\"data\":{\"message\":"+AgentWire.quoted(t[4])
                        +",\"legacy_line\":"+lineNumber+"}}";
                    if(segmentId!=null) {
                        json=json.substring(0,json.length()-1)+
                            ",\"segment_id\":"+AgentWire.quoted(segmentId)+"}";
                    }
                    spool.appendRecord(rid,runId,segmentId,null,"event",null,at,"agent",json);
                    events++;
                } else throw new IllegalArgumentException("Unexpected legacy row at line "+lineNumber);
            }
            incomplete=buf.size()>0;
        }
        return new Result(metadata,observations,events,incomplete);
    }
}
