package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neristal.rrbridge.product.v1.IncrementalTsvBridge;

/** Explicit synthetic preview only. No DurableSpool, sender, Worker or ACK integration. */
public final class Rlc2ProductAdapter {
    public static final int MAX_ROWS=100000, MAX_BODY_BYTES=32*1024*1024;
    public static final class Source {
        public final int index;
        public final String uuid,path;
        public final WatermarkV2Prototype.Marker marker;
        public final boolean sealed;
        Source(int i,String u,String p,WatermarkV2Prototype.Marker m,boolean s) { index=i;uuid=u;path=p;marker=m;sealed=s; }
    }
    public static final class Receipt {
        public final String sourceUuid,receiptId,bodySha256,body;
        public final long sequence,offset;
        Receipt(String uuid,IngressJournalPrototype.Entry e) {
            sourceUuid=uuid;sequence=e.sequence;offset=e.offset;body=e.row;
            receiptId=WatermarkLedgerPrototype.recordId(uuid,e.sequence);
            bodySha256=WatermarkLedgerPrototype.hex(WatermarkV2Prototype.sha().digest(body.getBytes(StandardCharsets.UTF_8)));
        }
    }
    public static final class Record {
        public final String id,run,segment,kind,profile,capturedAt,source,json;
        public final Long sequence;
        Record(String id,String run,String seg,Long seq,String kind,String profile,String at,String source,String json) {
            this.id=id;this.run=run;segment=seg;sequence=seq;this.kind=kind;this.profile=profile;capturedAt=at;this.source=source;this.json=json;
        }
    }
    public static final class Projection {
        public final String manifestSession,payloadSession,device,vehicle;
        public final long manifestGeneration;
        public final boolean ackEligible=false;
        public final List<Source> sources;
        public final List<Receipt> receipts;
        public final List<Record> records;
        public final Map<String,String> profiles;
        private final byte[] manifestBytes;
        Projection(RotationManifestPrototype.State state,String session,String device,String vehicle,List<Source> sources,
                   List<Receipt> receipts,List<Record> records,Map<String,String> profiles) {
            manifestSession=state.sessionUuid;manifestGeneration=state.generation;manifestBytes=state.raw.clone();
            payloadSession=session;this.device=device;this.vehicle=vehicle;
            this.sources=Collections.unmodifiableList(new ArrayList<Source>(sources));
            this.receipts=Collections.unmodifiableList(new ArrayList<Receipt>(receipts));
            this.records=Collections.unmodifiableList(new ArrayList<Record>(records));
            this.profiles=Collections.unmodifiableMap(new LinkedHashMap<String,String>(profiles));
        }
        public byte[] manifestBytes() { return manifestBytes.clone(); }
    }
    private Rlc2ProductAdapter() {}
    /** Disabled by default at call sites; explicit true only permits synthetic, closed-snapshot projection. */
    public static Projection project(boolean enableSynthetic,Path directory,File ledger,String device,String vehicle,
                                     WatermarkV2Prototype.ReadOpener opener)throws Exception {
        if(!enableSynthetic)throw new IllegalStateException("RLC2 synthetic adapter disabled");
        device=java.util.UUID.fromString(device).toString();vehicle=java.util.UUID.fromString(vehicle).toString();
        IngressDiagnostics.Report report=IngressDiagnostics.inspect(directory,ledger,opener,null);
        if(report.requiresReview())throw new IOException("RLC2 diagnostics require review");
        RotationManifestPrototype.State state=RotationManifestPrototype.verify(directory,opener,null);
        List<RotationManifestPrototype.Segment> segments=new ArrayList<RotationManifestPrototype.Segment>(state.sealed);
        if(state.active!=null)segments.add(state.active);
        final List<Source> sources=new ArrayList<Source>();
        final List<Receipt> receipts=new ArrayList<Receipt>();
        List<String> rows=new ArrayList<String>();
        String session=null;long lastSample=-1;long bytes=0;
        try(Connection db=IngressDiagnostics.readOnly(ledger)) {
            for(RotationManifestPrototype.Segment g:segments) {
                File file=RotationManifestPrototype.segment(directory,g.index).toFile();
                WatermarkV2Prototype.Committed c=WatermarkV2Prototype.readCommitted(file,opener,null);
                try(PreparedStatement p=db.prepareStatement("SELECT generation,seq,offset_bytes,prefix_sha256 FROM proto_rlc2_highwater WHERE source_uuid=?")) {
                    p.setString(1,g.sourceUuid);
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next()||r.getLong(1)!=c.marker.generation||r.getLong(2)!=c.marker.sequence||r.getLong(3)!=c.marker.offset||
                           !WatermarkLedgerPrototype.hex(c.marker.prefixHash()).equals(r.getString(4)))
                            throw new IOException("ledger is not aligned with committed marker");
                    }
                }
                sources.add(new Source(g.index,g.sourceUuid,file.getCanonicalPath(),c.marker,state.sealed.contains(g)));
                for(IngressJournalPrototype.Entry e:c.entries) {
                    String[] t=e.row.split("\\t",-1);
                    if(t.length<3||!t[1].startsWith("synthetic-")||t[1].indexOf('/')>=0||t[1].indexOf('\\')>=0)
                        throw new IOException("synthetic payload session required");
                    if(session==null)session=t[1];
                    if(!session.equals(t[1]))throw new IOException("payload session changed across rotation");
                    if("M".equals(t[0])&&(t.length!=6||!t[3].startsWith("TEST_")))throw new IOException("synthetic parameter required");
                    if("S".equals(t[0])) {
                        if(t.length!=6)throw new IOException("invalid synthetic sample");
                        long seq=Long.parseLong(t[3]);
                        if(seq<=lastSample)throw new IOException("sample sequence must continue across rotation");
                        lastSample=seq;
                        for(String value:t[5].split(",",-1))if(!value.startsWith("TEST_"))throw new IOException("synthetic sample values required");
                    }
                    bytes+=e.row.getBytes(StandardCharsets.UTF_8).length;
                    if(rows.size()>=MAX_ROWS||bytes>MAX_BODY_BYTES)throw new IOException("projection capacity reached");
                    rows.add(e.row);receipts.add(new Receipt(g.sourceUuid,e));
                }
            }
        }
        if(session==null)throw new IOException("nonempty synthetic session required");
        final Map<String,String> profiles=new LinkedHashMap<String,String>();
        final Map<String,Record> records=new LinkedHashMap<String,Record>();
        IncrementalTsvBridge.projectRows(device,vehicle,session,rows,new IncrementalTsvBridge.ProjectionSink() {
            public void appendProfile(String id,String json)throws Exception {
                String previous=profiles.put(id,json);
                if(previous!=null&&!previous.equals(json))throw new IOException("projected profile conflict");
            }
            public void appendRecord(String id,String run,String segment,Long sequence,String kind,String profile,String at,String source,String json)throws Exception {
                if(records.containsKey(id))throw new IOException("projected record collision");
                records.put(id,new Record(id,run,segment,sequence,kind,profile,at,source,json));
            }
        });
        // Reject a changing source/manifest rather than silently mixing generations.
        if(!Arrays.equals(state.raw,RotationManifestPrototype.verify(directory,opener,state).raw))throw new IOException("manifest changed during projection");
        for(Source source:sources) {
            WatermarkV2Prototype.Marker after=WatermarkV2Prototype.readCommitted(new File(source.path),opener,source.marker).marker;
            if(!Arrays.equals(WatermarkV2Prototype.encode(source.marker),WatermarkV2Prototype.encode(after)))throw new IOException("marker changed during projection");
        }
        if(IngressDiagnostics.inspect(directory,ledger,opener,state).requiresReview())throw new IOException("snapshot changed during projection");
        return new Projection(state,session,device,vehicle,sources,receipts,new ArrayList<Record>(records.values()),profiles);
    }
}
