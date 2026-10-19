package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.product.v1.LaneOwnership;
import net.neristal.rrbridge.product.v1.LegacyWorkerGuard;

/**
 * RLDT1 operator-mediated deployment transition and RLDB2 versioned binding. Synthetic opt-in only.
 *
 * <p>Generation 0 is the immutable RLDB1 pair. Generation N records are written CREATE_NEW in the order
 * prepare → anchor → root → committed; several files are never claimed to be one atomic commit. Any
 * generation that has a prepare file but neither committed nor abandoned evidence stops every caller.
 * RLDB2 does not bind the manifest file identity (it changes at every legitimate seal/rotation); it binds
 * a manifest content floor that the current manifest must extend monotonically.
 * No global fencing, no production authorization, no ACK, no automatic activation or rollback.
 */
public final class DeploymentTransition {
    public static final String VERSION="RLDB2";
    private static final String DOMAIN="00000000-0000-0000-0000-000000000001",PUBLICATION="RLC2-publication-v1";
    public enum Kind { SAME_SESSION_MANIFEST_UPDATE, NEXT_SESSION }
    public enum Stage { LOCKED, PREPARED, VALIDATED, ANCHOR_WRITTEN, ROOT_WRITTEN, COMMITTED }
    public interface Hook { void at(Stage stage)throws Exception; }
    public static final Hook NO_FAULT=new Hook(){public void at(Stage stage){}};
    /** Coded refusal; codes are stable and never carry paths, bodies or credentials. */
    public static final class Refusal extends IOException { public Refusal(String code){super(code);} }
    private static void require(boolean ok,String code)throws Refusal { if(!ok)throw new Refusal(code); }

    /** Immutable, deterministic dry-run result. The digest is what an operator approves for commit. */
    public static final class Plan {
        public final Kind kind; public final int generation; public final String digest;
        final Map<String,String> fields; final Map<String,String> record;
        Plan(Kind kind,int generation,Map<String,String> fields,Map<String,String> record)throws Exception {
            this.kind=kind;this.generation=generation;this.fields=Collections.unmodifiableMap(new TreeMap<String,String>(fields));
            this.record=Collections.unmodifiableMap(new TreeMap<String,String>(record));
            digest=sha(DeploymentBinding.encodeRecord(this.fields));
        }
        /** No paths, bodies or credentials; hashes and identifiers only. */
        public String json() {
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"state\":\"TRANSITION_PLAN_READY\",\"kind\":\"").append(kind)
                .append("\",\"generation\":").append(generation).append(",\"plan_sha256\":\"").append(digest).append('"');
            for(String k:new String[]{"old_manifest_session","new_manifest_session","old_generation_sha256","legacy_sha256","output_sha256","ledger_sha256",
                    "old_manifest_sha256","new_manifest_sha256","admitted_sources","purpose_sha256"})
                b.append(",\"").append(k).append("\":\"").append(fields.get(k)).append('"');
            return b.append(",\"operator_assertion_external_single_active\":true,\"synthetic_only\":true,\"automatic_activation\":false,")
                .append("\"production_authorized\":false,\"ack_eligible\":false,\"global_fencing\":false}").toString();
        }
    }

    // ------------------------------------------------------------------ evidence files
    static String gen(int g){return String.format(Locale.ROOT,"g%04d",g);}
    static Path prepare(DeploymentBinding.Layout l,int g){return l.root.toPath().resolve("deployment.rldt1."+gen(g)+".prepare");}
    static Path committed(DeploymentBinding.Layout l,int g){return l.root.toPath().resolve("deployment.rldt1."+gen(g)+".committed");}
    static Path abandoned(DeploymentBinding.Layout l,int g){return l.root.toPath().resolve("deployment.rldt1."+gen(g)+".abandoned");}
    static Path rootRecord(DeploymentBinding.Layout l,int g){return l.root.toPath().resolve("deployment.rldb2."+gen(g));}
    static Path anchorRecord(DeploymentBinding.Layout l,int g){return Paths.get(l.legacy.getPath()+".deployment-binding."+gen(g));}

    /** Chain state: committed records in order (index 0 = RLDB1) and the highest used generation. */
    static final class Chain {
        final List<Map<String,String>> records=new ArrayList<Map<String,String>>();final List<byte[]> bytes=new ArrayList<byte[]>();
        int highest;
        Map<String,String> active(){return records.get(records.size()-1);}
        int activeGeneration(){return Integer.parseInt(active().containsKey("generation")?active().get("generation"):"0");}
    }
    static Chain chain(DeploymentBinding.Layout l)throws Exception {return chain(l,0);}
    /** {@code ownPrepared}>0: the caller holds every lock and wrote that prepare itself (validation step only). */
    static Chain chain(DeploymentBinding.Layout l,int ownPrepared)throws Exception {
        Chain c=new Chain();
        Path rootRecord0=DeploymentBinding.rootRecordPath(l),anchor0=DeploymentBinding.anchorPath(l);
        require(Files.isRegularFile(rootRecord0,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(anchor0,LinkOption.NOFOLLOW_LINKS),"RLDB1_RECORD_MISSING");
        Map<String,String> zero=DeploymentBinding.readRecord(rootRecord0);
        require(zero.equals(DeploymentBinding.readRecord(anchor0)),"ROOT_DB_BINDING_MISMATCH");
        require("RLDB1".equals(zero.get("version"))&&"SYNTHETIC_ONLY".equals(zero.get("mode")),"BINDING_VERSION");
        c.records.add(zero);c.bytes.add(Files.readAllBytes(rootRecord0));
        // Inventory every transition-looking name. Unknown names refuse rather than being ignored.
        TreeSet<Integer> generations=new TreeSet<Integer>();
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(l.root.toPath())) {
            for(Path p:entries) {
                String n=p.getFileName().toString();
                if(!n.startsWith("deployment.rldt1.")&&!n.startsWith("deployment.rldb2."))continue;
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("deployment\\.(rldt1\\.g(\\d{4})\\.(prepare|committed|abandoned)|rldb2\\.g(\\d{4}))").matcher(n);
                require(m.matches(),"TRANSITION_UNKNOWN_FILE");
                generations.add(Integer.parseInt(m.group(2)!=null?m.group(2):m.group(4)));
            }
        }
        String prefix=l.legacy.getName()+".deployment-binding.";
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(l.legacy.toPath().getParent())) {
            for(Path p:entries) {
                String n=p.getFileName().toString();if(!n.startsWith(prefix))continue;
                String rest=n.substring(prefix.length());require(rest.matches("g\\d{4}"),"TRANSITION_UNKNOWN_FILE");
                generations.add(Integer.parseInt(rest.substring(1)));
            }
        }
        int expected=1;
        for(int g:generations) {
            require(g==expected++,"TRANSITION_GENERATION_GAP");
            require(Files.isRegularFile(prepare(l,g),LinkOption.NOFOLLOW_LINKS),"TRANSITION_EVIDENCE_WITHOUT_PREPARE");
            boolean done=Files.exists(committed(l,g),LinkOption.NOFOLLOW_LINKS),dropped=Files.exists(abandoned(l,g),LinkOption.NOFOLLOW_LINKS);
            require(!(done&&dropped),"TRANSITION_EVIDENCE_INCONSISTENT");
            if(g==ownPrepared&&!done&&!dropped&&!Files.exists(anchorRecord(l,g),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(rootRecord(l,g),LinkOption.NOFOLLOW_LINKS)){require(g==generations.last(),"TRANSITION_GENERATION_GAP");continue;}
            require(done||dropped,"TRANSITION_INTERRUPTED");
            byte[] planBytes=Files.readAllBytes(prepare(l,g));
            if(dropped) {
                Map<String,String> a=DeploymentBinding.readRecord(abandoned(l,g));
                require("RLDT1-ABANDONED".equals(a.get("version"))&&sha(planBytes).equals(a.get("plan_sha256")),"TRANSITION_EVIDENCE_INCONSISTENT");
                continue;
            }
            Map<String,String> plan=DeploymentBinding.readRecord(prepare(l,g));
            Map<String,String> root=DeploymentBinding.readRecord(rootRecord(l,g)),anchor=DeploymentBinding.readRecord(anchorRecord(l,g));
            byte[] recordBytes=Files.readAllBytes(rootRecord(l,g));
            require(root.equals(anchor),"ROOT_DB_BINDING_MISMATCH");
            Map<String,String> done2=DeploymentBinding.readRecord(committed(l,g));
            require("RLDT1-COMMITTED".equals(done2.get("version"))&&sha(planBytes).equals(done2.get("plan_sha256"))&&sha(recordBytes).equals(done2.get("record_sha256")),"TRANSITION_EVIDENCE_INCONSISTENT");
            require("RLDT1-PREPARE".equals(plan.get("version"))&&String.valueOf(g).equals(plan.get("generation")),"TRANSITION_EVIDENCE_INCONSISTENT");
            require(VERSION.equals(root.get("version"))&&String.valueOf(g).equals(root.get("generation")),"BINDING_VERSION");
            // Hash chain: every committed generation extends exactly the record that was active before it.
            require(sha(c.bytes.get(c.bytes.size()-1)).equals(root.get("previous_sha256"))&&root.get("previous_sha256").equals(plan.get("old_generation_sha256")),"TRANSITION_CHAIN_BROKEN");
            require(zero.get("deployment_uuid").equals(root.get("deployment_uuid")),"BINDING_OWNER");
            require(sha(DeploymentBinding.encodeRecord(root)).equals(plan.get("new_record_sha256")),"TRANSITION_EVIDENCE_INCONSISTENT");
            c.records.add(root);c.bytes.add(recordBytes);
        }
        c.highest=generations.isEmpty()?0:generations.last();
        return c;
    }

    // ------------------------------------------------------------------ layout comparison
    /** Keys that RLDB2 binds. The manifest file identity is deliberately absent. */
    private static final String[] BOUND={"root","root_identity","legacy","legacy_identity","output","output_identity","session","session_identity",
        "manifest","ledger","ledger_identity","device","vehicle","payload","manifest_session"};
    private static Map<String,String> bound(Map<String,String> m) {
        Map<String,String> r=new TreeMap<String,String>();for(String k:BOUND)r.put(k,m.get(k));return r;
    }
    /** Compare a layout to an active record; return the first changed key as a stable code. */
    private static void sameLayout(Map<String,String> record,Map<String,String> observed,boolean includeManifestIdentity)throws Refusal {
        for(String k:BOUND)require(Objects.equals(record.get(k),observed.get(k)),k.toUpperCase(Locale.ROOT)+"_CHANGED");
        if(includeManifestIdentity)require(Objects.equals(record.get("manifest_identity"),observed.get("manifest_identity")),"MANIFEST_IDENTITY_CHANGED");
        for(Map.Entry<String,String> e:record.entrySet())require(!e.getKey().endsWith("_identity")||!e.getValue().startsWith("null|"),"BINDING_IDENTITY_REENROLL_REQUIRED");
    }

    /**
     * Read-only. Returns the active generation if the layout is the active authority. RLDB1 (generation 0)
     * still requires the exact manifest identity; RLDB2 requires the current manifest to extend its floor.
     */
    public static int verifyActive(boolean enable,boolean externallySingleActive,DeploymentBinding.Layout l)throws Exception {
        require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");
        Map<String,String> observed=DeploymentBinding.describeLayout(l);
        Chain c=chain(l);Map<String,String> active=c.active();
        if(c.records.size()==1){sameLayout(active,observed,true);return 0;}
        sameLayout(active,observed,false);
        RotationManifestPrototype.State floor=RotationManifestPrototype.decode(hexBytes(active.get("manifest_floor")));
        RotationManifestPrototype.State current;
        try{current=RotationManifestPrototype.read(l.session.toPath(),floor);}catch(IOException e){throw new Refusal("MANIFEST_NOT_EXTENDING_FLOOR");}
        require(current.sessionUuid.equals(l.manifestSession),"MANIFEST_SESSION_MISMATCH");
        return c.activeGeneration();
    }

    // ------------------------------------------------------------------ planning
    private static final class Output {
        byte[] storedManifest;long storedGeneration;String sessionPath;
        final Map<String,long[]> sources=new TreeMap<String,long[]>();final Map<String,String> prefixes=new TreeMap<String,String>();
        final Map<String,String> receipts=new TreeMap<String,String>();
    }
    private static Output output(File db,DeploymentBinding.Layout old)throws Exception {
        Output o=new Output();
        try(Connection c=IngressDiagnostics.readOnly(db)) {
            try(PreparedStatement q=c.prepareStatement("SELECT device,vehicle,payload_session,manifest_generation,manifest FROM preview_sessions WHERE session_uuid=?")) {
                q.setString(1,old.manifestSession);
                try(ResultSet r=q.executeQuery()) {
                    require(r.next(),"OLD_SESSION_NOT_INGESTED");
                    require(old.device.equals(r.getString(1))&&old.vehicle.equals(r.getString(2))&&old.payloadSession.equals(r.getString(3)),"OUTPUT_SESSION_OWNER_MISMATCH");
                    o.storedGeneration=r.getLong(4);o.storedManifest=r.getBytes(5);
                }
            }
            try(PreparedStatement q=c.prepareStatement("SELECT session_path FROM integration_bindings WHERE session_uuid=?")) {
                q.setString(1,old.manifestSession);try(ResultSet r=q.executeQuery()){require(r.next(),"OLD_SESSION_NOT_BOUND");o.sessionPath=r.getString(1);}
            }
            try(PreparedStatement q=c.prepareStatement("SELECT source_uuid,seq,offset_bytes,sealed,prefix_sha256 FROM preview_sources WHERE session_uuid=?")) {
                q.setString(1,old.manifestSession);
                try(ResultSet r=q.executeQuery()){while(r.next()){o.sources.put(r.getString(1),new long[]{r.getLong(2),r.getLong(3),r.getLong(4)});o.prefixes.put(r.getString(1),r.getString(5));}}
            }
            try(PreparedStatement q=c.prepareStatement("SELECT r.source_uuid,r.seq,r.body_sha256 FROM preview_receipts r JOIN preview_sources s ON r.source_uuid=s.source_uuid WHERE s.session_uuid=? LIMIT 100001")) {
                q.setString(1,old.manifestSession);
                try(ResultSet r=q.executeQuery()){while(r.next()){require(o.receipts.size()<100000,"OUTPUT_RECEIPT_BOUND");o.receipts.put(r.getString(1)+"|"+r.getLong(2),r.getString(3));}}
            }
        }
        return o;
    }
    private static boolean outputKnows(File db,String table,String column,String value)throws Exception {
        try(Connection c=IngressDiagnostics.readOnly(db);PreparedStatement q=c.prepareStatement("SELECT 1 FROM "+table+" WHERE "+column+"=?")) {
            q.setString(1,value);try(ResultSet r=q.executeQuery()){return r.next();}
        }
    }
    /** The durable ledger must not have regressed or changed any receipt already stored in the output. */
    private static void ledgerCovers(File ledger,Output o)throws Exception {
        try(Connection c=IngressDiagnostics.readOnly(ledger)) {
            for(Map.Entry<String,long[]> e:o.sources.entrySet()) {
                try(PreparedStatement q=c.prepareStatement("SELECT seq,offset_bytes,prefix_sha256 FROM proto_rlc2_highwater WHERE source_uuid=?")) {
                    q.setString(1,e.getKey());
                    try(ResultSet r=q.executeQuery()) {
                        require(r.next(),"LEDGER_SOURCE_MISSING");
                        require(r.getLong(1)>=e.getValue()[0]&&r.getLong(2)>=e.getValue()[1],"LEDGER_REGRESSED");
                        if(r.getLong(1)==e.getValue()[0])require(r.getString(3).equals(o.prefixes.get(e.getKey())),"LEDGER_PREFIX_CHANGED");
                    }
                }
            }
            try(PreparedStatement q=c.prepareStatement("SELECT body_sha256 FROM proto_rlc2_receipts WHERE source_uuid=? AND seq=?")) {
                for(Map.Entry<String,String> e:o.receipts.entrySet()) {
                    int at=e.getKey().lastIndexOf('|');q.setString(1,e.getKey().substring(0,at));q.setLong(2,Long.parseLong(e.getKey().substring(at+1)));
                    try(ResultSet r=q.executeQuery()){require(r.next(),"LEDGER_RECEIPT_MISSING");require(r.getString(1).equals(e.getValue()),"LEDGER_RECEIPT_CHANGED");}
                }
            }
        }
    }
    /** source UUID -> canonical source path recorded by the durable ledger. */
    private static Map<String,String> ledgerSources(File ledger)throws Exception {
        Map<String,String> s=new HashMap<String,String>();
        try(Connection c=IngressDiagnostics.readOnly(ledger);PreparedStatement q=c.prepareStatement("SELECT source_uuid,source_path FROM proto_rlc2_sources");ResultSet r=q.executeQuery()){while(r.next())s.put(r.getString(1),r.getString(2));}
        return s;
    }
    private static List<RotationManifestPrototype.Segment> segments(RotationManifestPrototype.State s) {
        List<RotationManifestPrototype.Segment> all=new ArrayList<RotationManifestPrototype.Segment>(s.sealed);if(s.active!=null)all.add(s.active);return all;
    }
    private static Set<String> manifestSources(RotationManifestPrototype.State s) {
        Set<String> ids=new TreeSet<String>();for(RotationManifestPrototype.Segment g:s.sealed)ids.add(g.sourceUuid);if(s.active!=null)ids.add(s.active.sourceUuid);return ids;
    }
    private static String lockState(String observed,String code)throws Refusal {
        if("BUSY".equals(observed))throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");
        require("IDLE".equals(observed)||"MISSING".equals(observed),code);return observed;
    }
    /** Observe without creating: an existing legacy Worker lock that is held means a running cooperating Worker. */
    private static void legacyIdle(File legacy)throws Exception {
        Path lock=Paths.get(legacy.getPath()+".worker.lock");
        if(!Files.exists(lock,LinkOption.NOFOLLOW_LINKS))return;
        require(Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS),"LEGACY_LOCK_INVALID");
        try(java.nio.channels.FileChannel ch=java.nio.channels.FileChannel.open(lock,java.nio.file.StandardOpenOption.READ)) {
            java.nio.channels.FileLock seen;
            try{seen=ch.tryLock(0,Long.MAX_VALUE,true);}catch(java.nio.channels.OverlappingFileLockException busy){throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");}
            if(seen==null)throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");seen.release();
        }
    }

    /**
     * Dry run. {@code admitted} is the operator's explicit set of NEW physical source UUIDs; nothing is discovered
     * or adopted implicitly. {@code purpose} is hashed into the plan, never echoed.
     */
    public static Plan plan(boolean enable,boolean externallySingleActive,DeploymentBinding.Layout old,DeploymentBinding.Layout next,
            Set<String> admitted,String purpose,WatermarkV2Prototype.ReadOpener reader)throws Exception {
        require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");
        require(purpose!=null&&!purpose.trim().isEmpty()&&purpose.length()<=1024,"PURPOSE_REQUIRED");
        Set<String> admit=new TreeSet<String>();for(String id:admitted)admit.add(UUID.fromString(id).toString());
        // Fixed deployment anchors: changing root/legacy/output/ledger/device/vehicle is a different deployment (BLOCKED).
        require(old.root.equals(next.root)&&old.legacy.equals(next.legacy)&&old.output.equals(next.output)&&old.ledger.equals(next.ledger),"DEPLOYMENT_ANCHOR_CHANGE_UNSUPPORTED");
        require(old.device.equals(next.device)&&old.vehicle.equals(next.vehicle),"DEVICE_OR_VEHICLE_CHANGE_UNSUPPORTED");
        Integer held=HELD.get();int own=held==null?0:held;
        Chain c=chain(old,own);Map<String,String> active=c.active();
        Map<String,String> oldObserved=DeploymentBinding.describeLayout(old);
        sameLayout(active,oldObserved,false);
        Output o=output(old.output,old);
        require(o.sessionPath.equals(oldObserved.get("session")),"OLD_SESSION_PATH_MISMATCH");
        RotationManifestPrototype.State stored=RotationManifestPrototype.decode(o.storedManifest);
        if(c.records.size()>1) {
            try{RotationManifestPrototype.monotonic(RotationManifestPrototype.decode(hexBytes(active.get("manifest_floor"))),stored);}
            catch(IOException e){throw new Refusal("OUTPUT_MANIFEST_BEHIND_FLOOR");}
        }
        RotationManifestPrototype.State oldNow;
        try{oldNow=RotationManifestPrototype.verify(old.session.toPath(),reader,stored);}catch(IOException e){throw new Refusal("OLD_MANIFEST_NOT_EXTENDING_OUTPUT");}
        require(oldNow.sessionUuid.equals(old.manifestSession),"MANIFEST_SESSION_MISMATCH");
        for(String id:o.sources.keySet())require(manifestSources(oldNow).contains(id),"OUTPUT_SOURCE_MISSING_FROM_MANIFEST");
        ledgerCovers(old.ledger,o);
        Map<String,String> ledgerIds=ledgerSources(old.ledger);
        boolean sameDir=old.session.equals(next.session),sameManifest=old.manifestSession.equals(next.manifestSession),samePayload=old.payloadSession.equals(next.payloadSession);
        Kind kind;RotationManifestPrototype.State floor;Map<String,String> nextObserved;
        if(sameDir&&sameManifest&&samePayload) {
            kind=Kind.SAME_SESSION_MANIFEST_UPDATE;nextObserved=oldObserved;
            Set<String> fresh=new TreeSet<String>(manifestSources(oldNow));fresh.removeAll(o.sources.keySet());
            require(fresh.equals(admit),admit.containsAll(fresh)?"SOURCE_ADMISSION_UNEXPECTED":"SOURCE_ADMISSION_REQUIRED");
            for(String id:fresh)require(!outputKnows(old.output,"preview_sources","source_uuid",id),"SOURCE_UUID_REUSED");
            if(c.records.size()>1)require(!Arrays.equals(hexBytes(active.get("manifest_floor")),oldNow.raw),"NO_TRANSITION_REQUIRED");
            floor=oldNow;
        } else if(!sameDir&&!sameManifest&&!samePayload) {
            kind=Kind.NEXT_SESSION;
            // The old Session must be closed and fully stored: all sealed, output holds that exact final manifest and highwater.
            require(oldNow.active==null&&Arrays.equals(oldNow.raw,o.storedManifest),"OLD_SESSION_NOT_CLOSED");
            for(RotationManifestPrototype.Segment g:oldNow.sealed) {
                long[] h=o.sources.get(g.sourceUuid);
                require(h!=null&&h[0]==g.finalSequence&&h[1]==g.finalOffset&&h[2]==1,"OLD_SESSION_NOT_FULLY_STORED");
            }
            Path a=old.session.toPath(),b=next.session.toPath();
            require(!a.startsWith(b)&&!b.startsWith(a),"SESSION_DIRECTORY_OVERLAP");
            nextObserved=DeploymentBinding.describeLayout(next);
            for(String k:new String[]{"root","root_identity","legacy","legacy_identity","output","output_identity","ledger","ledger_identity"})
                require(nextObserved.get(k).equals(oldObserved.get(k)),k.toUpperCase(Locale.ROOT)+"_CHANGED");
            require(!outputKnows(old.output,"preview_sessions","session_uuid",next.manifestSession)&&!outputKnows(old.output,"integration_bindings","session_uuid",next.manifestSession),"NEW_MANIFEST_SESSION_ALREADY_STORED");
            require(!outputKnows(old.output,"preview_claims","payload_session",next.payloadSession),"NEW_PAYLOAD_SESSION_ALREADY_CLAIMED");
            try{Rlc2IntegrationSchema.requireUnclaimed(old.legacy,next.device,next.vehicle,next.payloadSession,"");}catch(IOException e){throw new Refusal("NEW_PAYLOAD_SESSION_LEGACY_CLAIMED");}
            RotationManifestPrototype.State nextNow;
            try{nextNow=RotationManifestPrototype.verify(next.session.toPath(),reader,null);}catch(IOException e){throw new Refusal("NEW_MANIFEST_INVALID");}
            require(nextNow.sessionUuid.equals(next.manifestSession),"NEW_MANIFEST_SESSION_MISMATCH");
            Set<String> fresh=manifestSources(nextNow);
            require(fresh.equals(admit),admit.containsAll(fresh)?"SOURCE_ADMISSION_UNEXPECTED":"SOURCE_ADMISSION_REQUIRED");
            for(String id:fresh)require(!outputKnows(old.output,"preview_sources","source_uuid",id)&&!manifestSources(oldNow).contains(id),"SOURCE_UUID_REUSED");
            // The Writer records the new Session in the shared ledger first; a ledger entry for a fresh UUID must be that exact segment.
            for(RotationManifestPrototype.Segment g:segments(nextNow)) {
                String recorded=ledgerIds.get(g.sourceUuid);
                require(recorded==null||recorded.equals(RotationManifestPrototype.segment(next.session.toPath(),g.index).toFile().getCanonicalPath()),"SOURCE_UUID_REUSED");
            }
            floor=nextNow;
        } else if(sameDir) throw new Refusal("SAME_DIRECTORY_DIFFERENT_SESSION");
        else if(sameManifest) throw new Refusal("MANIFEST_SESSION_MOVED");
        else throw new Refusal("PAYLOAD_SESSION_REUSED");
        // Quiescence observation (never creates locks, never takes over). Skipped only when this thread holds them all.
        if(held==null) {
            legacyIdle(old.legacy);
            lockState(LaneOwnership.diagnose(old.root,old.device,old.vehicle,old.payloadSession),"LANE_EVIDENCE_INVALID");
            lockState(LaneOwnership.diagnose(next.root,next.device,next.vehicle,next.payloadSession),"LANE_EVIDENCE_INVALID");
            lockState(LaneOwnership.diagnose(old.root,DOMAIN,old.manifestSession,PUBLICATION),"PUBLICATION_EVIDENCE_INVALID");
            lockState(LaneOwnership.diagnose(next.root,DOMAIN,next.manifestSession,PUBLICATION),"PUBLICATION_EVIDENCE_INVALID");
        }
        for(File db:new File[]{old.legacy,old.ledger,old.output})for(String suffix:new String[]{"-wal","-shm","-journal"})
            require(!Files.exists(Paths.get(db.getPath()+suffix),LinkOption.NOFOLLOW_LINKS),"DATABASE_NOT_CLOSED");
        int generation=own>0?own:c.highest+1;require(generation<=9999,"TRANSITION_GENERATION_EXHAUSTED");
        Map<String,String> record=new TreeMap<String,String>(bound(nextObserved));
        record.put("version",VERSION);record.put("mode","SYNTHETIC_ONLY");record.put("generation",String.valueOf(generation));
        record.put("previous_sha256",sha(c.bytes.get(c.bytes.size()-1)));record.put("deployment_uuid",c.records.get(0).get("deployment_uuid"));
        record.put("kind",kind.name());record.put("manifest_floor",hex(floor.raw));record.put("manifest_floor_generation",String.valueOf(floor.generation));
        Map<String,String> f=new TreeMap<String,String>();
        f.put("version","RLDT1-PREPARE");f.put("generation",String.valueOf(generation));f.put("kind",kind.name());
        f.put("old_generation",String.valueOf(c.activeGeneration()));f.put("old_generation_sha256",record.get("previous_sha256"));
        f.put("old_manifest_session",old.manifestSession);f.put("new_manifest_session",next.manifestSession);
        f.put("old_payload_sha256",sha(old.payloadSession.getBytes(StandardCharsets.UTF_8)));f.put("new_payload_sha256",sha(next.payloadSession.getBytes(StandardCharsets.UTF_8)));
        f.put("legacy_sha256",fileSha(old.legacy));f.put("output_sha256",fileSha(old.output));f.put("ledger_sha256",fileSha(old.ledger));
        f.put("old_manifest_sha256",sha(oldNow.raw));f.put("new_manifest_sha256",sha(floor.raw));
        f.put("admitted_sources",String.join(",",admit));f.put("purpose_sha256",sha(purpose.getBytes(StandardCharsets.UTF_8)));
        f.put("new_record_sha256",sha(DeploymentBinding.encodeRecord(record)));
        f.put("operator_assertion","EXTERNAL_SINGLE_ACTIVE_DECLARED_NOT_PROVEN");
        // Hash-derived exact lock names let abandon retain BOTH old/new scopes, without storing payload text.
        f.put("old_lane_lock",lockName(old.device,old.vehicle,old.payloadSession));
        f.put("new_lane_lock",lockName(next.device,next.vehicle,next.payloadSession));
        f.put("old_publication_lock",lockName(DOMAIN,old.manifestSession,PUBLICATION));
        f.put("new_publication_lock",lockName(DOMAIN,next.manifestSession,PUBLICATION));
        return new Plan(kind,generation,f,record);
    }

    // ------------------------------------------------------------------ commit / abandon
    /**
     * Explicit synthetic commit of an approved dry run. All cooperating locks are taken in the existing order
     * (legacy → lane → publication), the plan is recomputed and must equal the approved digest, then evidence is
     * written CREATE_NEW: prepare → anchor → root → committed. An interruption leaves TRANSITION_INTERRUPTED.
     */
    public static int commitSynthetic(boolean enable,boolean externallySingleActive,DeploymentBinding.Layout old,DeploymentBinding.Layout next,
            Set<String> admitted,String purpose,String approvedPlanSha256,WatermarkV2Prototype.ReadOpener reader,Hook hook)throws Exception {
        require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");
        List<AutoCloseable> held=new ArrayList<AutoCloseable>();
        try {
            try{held.add(LegacyWorkerGuard.acquire(old.legacy));}catch(IOException e){throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");}
            LinkedHashSet<String> lanes=new LinkedHashSet<String>(Arrays.asList(old.payloadSession,next.payloadSession));
            for(String lane:lanes)try{held.add(LaneOwnership.acquire(true,old.root,old.device,old.vehicle,lane));}catch(IOException e){throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");}
            LinkedHashSet<String> publications=new LinkedHashSet<String>(Arrays.asList(old.manifestSession,next.manifestSession));
            for(String m:publications)try{held.add(Rlc2PublicationBoundary.acquire(true,old.root,m));}catch(IOException e){throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");}
            hook.at(Stage.LOCKED);
            Plan p=planHeld(old,next,admitted,purpose,reader);
            require(p.digest.equals(approvedPlanSha256),"PLAN_CHANGED");
            byte[] planBytes=DeploymentBinding.encodeRecord(p.fields),recordBytes=DeploymentBinding.encodeRecord(p.record);
            DeploymentBinding.createRecord(prepare(old,p.generation),planBytes);
            hook.at(Stage.PREPARED);
            // Validate again after the prepare evidence exists: nothing observed may have moved meanwhile.
            for(AutoCloseable h:held)verifyHeld(h);
            require(planHeld(old,next,admitted,purpose,reader,p.generation).digest.equals(p.digest),"PLAN_CHANGED");
            hook.at(Stage.VALIDATED);
            DeploymentBinding.createRecord(anchorRecord(old,p.generation),recordBytes);
            hook.at(Stage.ANCHOR_WRITTEN);
            DeploymentBinding.createRecord(rootRecord(old,p.generation),recordBytes);
            hook.at(Stage.ROOT_WRITTEN);
            Map<String,String> done=new TreeMap<String,String>();
            done.put("version","RLDT1-COMMITTED");done.put("generation",String.valueOf(p.generation));
            done.put("plan_sha256",sha(planBytes));done.put("record_sha256",sha(recordBytes));
            for(AutoCloseable h:held)verifyHeld(h);
            DeploymentBinding.createRecord(committed(old,p.generation),DeploymentBinding.encodeRecord(done));
            hook.at(Stage.COMMITTED);
            require(verifyActive(true,true,next)==p.generation,"TRANSITION_NOT_ACTIVE");
            return p.generation;
        } finally {
            for(int i=held.size()-1;i>=0;i--)try{held.get(i).close();}catch(Exception ignored){}
        }
    }
    private static void verifyHeld(AutoCloseable h)throws Exception {
        if(h instanceof LegacyWorkerGuard)((LegacyWorkerGuard)h).verify();else ((LaneOwnership)h).verify();
    }
    /** Under held locks the lock observation itself is skipped (we are the holder); every other check repeats. */
    private static Plan planHeld(DeploymentBinding.Layout old,DeploymentBinding.Layout next,Set<String> admitted,String purpose,WatermarkV2Prototype.ReadOpener reader)throws Exception {
        return planHeld(old,next,admitted,purpose,reader,0);
    }
    private static Plan planHeld(DeploymentBinding.Layout old,DeploymentBinding.Layout next,Set<String> admitted,String purpose,WatermarkV2Prototype.ReadOpener reader,int preparedGeneration)throws Exception {
        HELD.set(preparedGeneration);
        try{return plan(true,true,old,next,admitted,purpose,reader);}finally{HELD.remove();}
    }
    /** null: not held. 0: held, no prepare yet. N: held, our own prepare for generation N exists. */
    private static final ThreadLocal<Integer> HELD=new ThreadLocal<Integer>();

    /**
     * Operator-mediated: marks an interrupted generation as abandoned (CREATE_NEW evidence, nothing deleted).
     * The previously committed generation stays active; partial anchor/root files remain for review.
     */
    public static void abandonSynthetic(boolean enable,boolean externallySingleActive,DeploymentBinding.Layout l,int generation,String reason)throws Exception {
        require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");
        require(reason!=null&&!reason.trim().isEmpty()&&reason.length()<=1024,"PURPOSE_REQUIRED");
        DeploymentBinding.describeLayout(l); // Local fixed-drive/path checks precede Worker-lock access.
        require(Files.isRegularFile(prepare(l,generation),LinkOption.NOFOLLOW_LINKS),"TRANSITION_NOT_PREPARED");
        require(!Files.exists(committed(l,generation),LinkOption.NOFOLLOW_LINKS),"TRANSITION_ALREADY_COMMITTED");
        require(!Files.exists(abandoned(l,generation),LinkOption.NOFOLLOW_LINKS),"TRANSITION_ALREADY_ABANDONED");
        try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(l.legacy)) {
            // The Worker guard serializes transition/abandon callers; publication writers also need their exact locks observed.
            DeploymentBinding.safe(l.root);DeploymentBinding.privateAccess(l.root.toPath());
            Map<String,String> prepared=DeploymentBinding.readRecord(prepare(l,generation));
            require("RLDT1-PREPARE".equals(prepared.get("version"))&&String.valueOf(generation).equals(prepared.get("generation")),"TRANSITION_EVIDENCE_INCONSISTENT");
            require(!Files.exists(committed(l,generation),LinkOption.NOFOLLOW_LINKS),"TRANSITION_ALREADY_COMMITTED");
            require(!Files.exists(abandoned(l,generation),LinkOption.NOFOLLOW_LINKS),"TRANSITION_ALREADY_ABANDONED");
            List<AbandonObservation> observations=new ArrayList<AbandonObservation>();
            try{
                Set<String> names=new LinkedHashSet<String>();
                for(String key:new String[]{"old_lane_lock","new_lane_lock","old_publication_lock","new_publication_lock"}){
                    String name=prepared.get(key);require(name!=null&&name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.lane\\.lock"),"ABANDON_LOCK_SET_UNKNOWN");names.add(name);
                }
                require(lockName(l.device,l.vehicle,l.payloadSession).equals(prepared.get("old_lane_lock"))&&lockName(DOMAIN,l.manifestSession,PUBLICATION).equals(prepared.get("old_publication_lock")),"ABANDON_LAYOUT_MISMATCH");
                for(String name:names)observations.add(new AbandonObservation(l.root.toPath().resolve(name)));
                old.verify();for(AbandonObservation observation:observations)observation.verify();
            Map<String,String> a=new TreeMap<String,String>();a.put("version","RLDT1-ABANDONED");a.put("generation",String.valueOf(generation));
            a.put("plan_sha256",sha(Files.readAllBytes(prepare(l,generation))));a.put("reason_sha256",sha(reason.getBytes(StandardCharsets.UTF_8)));
            DeploymentBinding.createRecord(abandoned(l,generation),DeploymentBinding.encodeRecord(a));
            }finally{for(int i=observations.size()-1;i>=0;i--)observations.get(i).close();}
        }catch(IOException e){if(e instanceof DeploymentBinding.Refusal)throw e;if(e.getMessage()!=null&&e.getMessage().startsWith("LEGACY_WORKER_BUSY"))throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");throw e;}
        chain(l);
    }
    private static String lockName(String device,String vehicle,String payload){return UUID.nameUUIDFromBytes((device+"|"+vehicle+"|"+payload).getBytes(StandardCharsets.UTF_8)).toString()+".lane.lock";}
    /** Never creates or rewrites a lock. Legacy prepares without the exact lock set stay blocked for manual review. */
    private static final class AbandonObservation implements AutoCloseable {
        final Path path;final java.nio.channels.FileChannel channel;final java.nio.channels.FileLock lock;
        final byte[] bytes;
        AbandonObservation(Path path)throws Exception {
            this.path=path;require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"ABANDON_LOCK_SET_UNKNOWN");
            DeploymentBinding.safe(path.toFile());DeploymentBinding.privateAccess(path);
            channel=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ);java.nio.channels.FileLock held=null;
            try{
                try{held=channel.tryLock(0,Long.MAX_VALUE,true);}catch(java.nio.channels.OverlappingFileLockException busy){throw new Refusal("BLOCKED_UNCOOPERATIVE_PROCESS");}
                require(held!=null,"BLOCKED_UNCOOPERATIVE_PROCESS");lock=held;bytes=read();verify();
            }catch(Exception failure){if(held!=null)held.release();channel.close();throw failure;}
        }
        byte[] read()throws Exception {
            try(java.io.InputStream in=Files.newInputStream(path);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){require(out.size()+n<=4096,"ABANDON_LOCK_SET_UNKNOWN");out.write(b,0,n);}return out.toByteArray();
            }
        }
        void verify()throws Exception {DeploymentBinding.safe(path.toFile());require(lock.isValid()&&channel.isOpen()&&Arrays.equals(bytes,read()),"ABANDON_LOCK_CHANGED");}
        public void close()throws Exception{try{lock.release();}finally{channel.close();}}
    }

    // ------------------------------------------------------------------ read-only CLI
    static String json(String state,String reason,int generation) {
        return "{\"contract_version\":1,\"state\":\""+state+"\",\"reason\":\""+reason+"\",\"active_generation\":"+generation
            +",\"synthetic_only\":true,\"automatic_activation\":false,\"production_authorized\":false,\"ack_eligible\":false,\"global_fencing\":false}";
    }
    static DeploymentBinding.Layout layout(String[] a,int at) {
        return new DeploymentBinding.Layout(new File(a[at]),new File(a[at+1]),new File(a[at+2]),new File(a[at+3]),new File(a[at+4]),new File(a[at+5]),a[at+6],a[at+7],a[at+8],a[at+9]);
    }
    /**
     * Read-only only: {@code --inspect-active-synthetic} and {@code --plan-synthetic}. Commit/abandon have no CLI on purpose.
     * Exit 0 inspected/planned, 2 usage, 3 blocked by a held cooperative lock, 5 review required. Never production permission.
     */
    public static int run(String[] a,WatermarkV2Prototype.ReadOpener reader) {
        try {
            if(a.length==12&&"--inspect-active-synthetic".equals(a[0])&&"--external-single-active".equals(a[1])) {
                int g=verifyActive(true,true,layout(a,2));System.out.println(json("ACTIVE_GENERATION_VERIFIED",g==0?"RLDB1":"RLDB2",g));return 0;
            }
            // old layout (10) + new SESSION MANIFEST PAYLOAD MANIFEST_SESSION + ADMITTED_CSV|- + PURPOSE
            if(a.length==18&&"--plan-synthetic".equals(a[0])&&"--external-single-active".equals(a[1])) {
                DeploymentBinding.Layout old=layout(a,2);
                DeploymentBinding.Layout next=new DeploymentBinding.Layout(old.root,old.legacy,old.output,new File(a[12]),new File(a[13]),old.ledger,old.device,old.vehicle,a[14],a[15]);
                Set<String> admitted=new TreeSet<String>();if(!"-".equals(a[16]))admitted.addAll(Arrays.asList(a[16].split(",")));
                System.out.println(plan(true,true,old,next,admitted,a[17],reader).json());return 0;
            }
            System.out.println(json("REVIEW_REQUIRED","USAGE",-1));return 2;
        } catch(Exception e) {
            String reason=e instanceof Refusal||e instanceof DeploymentBinding.Refusal?e.getMessage():"INSPECTION_UNAVAILABLE";
            boolean blocked="BLOCKED_UNCOOPERATIVE_PROCESS".equals(reason);
            System.out.println(json(blocked?"BLOCKED_UNCOOPERATIVE_PROCESS":"REVIEW_REQUIRED",reason,-1));return blocked?3:5;
        }
    }
    /** POSIX reader only; Windows physical-identity reading stays in the test-only native wrapper (as for Rlc2IntegrationCli). */
    public static void main(String[] a) {System.exit(run(a,WatermarkV2Prototype.POSIX_READER));}

    // ------------------------------------------------------------------ helpers
    static String sha(byte[] b)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String fileSha(File f)throws Exception {
        require(f.length()<=128L*1024*1024,"FILE_BOUND");MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=Files.newInputStream(f.toPath())){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());
    }
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
    static byte[] hexBytes(String s)throws Refusal {
        require(s!=null&&s.length()%2==0&&s.matches("[0-9a-f]*"),"BINDING_FORMAT");
        byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return out;
    }
    private DeploymentTransition(){}
}
