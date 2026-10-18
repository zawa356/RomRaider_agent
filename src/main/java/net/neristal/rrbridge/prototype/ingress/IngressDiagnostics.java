package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only RLC2/RLM1/SQLite inspection API. Never an ACK or repair authority. */
public final class IngressDiagnostics {
    public enum Code {
        DURABILITY_UNKNOWN, INVALID_PATH, MANIFEST_INVALID, MANIFEST_HISTORY_CONFLICT,
        ORPHAN_SEGMENT, TMP_MARKER, QUOTA_REACHED, SOURCE_CLAIM_CONFLICT,
        IDENTITY_MISMATCH, STALE_WATERMARK, HIGHWATER_REGRESSION, RECEIPT_INCONSISTENT,
        SEALED_SEGMENT_CHANGED, WATERMARK_INVALID, DATABASE_UNAVAILABLE, SNAPSHOT_CHANGED,
        LEGACY_RLC1_PRESENT, MIXED_MARKER_FORMATS, LEDGER_REQUIRED, LEDGER_BEHIND, MARKER_MISSING, UNCOMMITTED_TAIL
    }
    public enum Action {
        KEEP_ACK_DISABLED, VERIFY_EXISTING_PATH, KEEP_STAGED_FILES_AND_REVIEW,
        KEEP_ORIGINALS_AND_REVIEW_MANIFEST, COMPARE_HIGHWATER_WITHOUT_REWIND,
        CHECK_SOURCE_OWNERSHIP_WITHOUT_READOPTION, COMPARE_RECEIPTS_WITHOUT_REPAIR,
        STOP_WRITER_KEEP_ORIGINALS, SUPPLY_CLOSED_LEDGER_SNAPSHOT,
        REQUIRE_EXPLICIT_MIGRATION_CONTRACT, VERIFY_LEDGER_ALIGNMENT, KEEP_INPUTS_FOR_REVIEW
    }
    private static Action action(Code code) {
        switch(code) {
            case DURABILITY_UNKNOWN:return Action.KEEP_ACK_DISABLED;
            case INVALID_PATH:return Action.VERIFY_EXISTING_PATH;
            case TMP_MARKER:return Action.KEEP_STAGED_FILES_AND_REVIEW;
            case ORPHAN_SEGMENT:case MANIFEST_INVALID:case MANIFEST_HISTORY_CONFLICT:return Action.KEEP_ORIGINALS_AND_REVIEW_MANIFEST;
            case STALE_WATERMARK:case HIGHWATER_REGRESSION:return Action.COMPARE_HIGHWATER_WITHOUT_REWIND;
            case IDENTITY_MISMATCH:case SOURCE_CLAIM_CONFLICT:return Action.CHECK_SOURCE_OWNERSHIP_WITHOUT_READOPTION;
            case RECEIPT_INCONSISTENT:case SEALED_SEGMENT_CHANGED:return Action.COMPARE_RECEIPTS_WITHOUT_REPAIR;
            case QUOTA_REACHED:return Action.STOP_WRITER_KEEP_ORIGINALS;
            case DATABASE_UNAVAILABLE:case LEDGER_REQUIRED:return Action.SUPPLY_CLOSED_LEDGER_SNAPSHOT;
            case LEGACY_RLC1_PRESENT:case MIXED_MARKER_FORMATS:return Action.REQUIRE_EXPLICIT_MIGRATION_CONTRACT;
            case LEDGER_BEHIND:return Action.VERIFY_LEDGER_ALIGNMENT;
            default:return Action.KEEP_INPUTS_FOR_REVIEW;
        }
    }
    public static final class Finding {
        public final Code code;
        /** Manifest segment index, or zero for a session-level finding. No source contents. */
        public final int segment;
        public final String cause;
        /** Manual review hint only; this engine never executes actions. */
        public final Action nextAction;
        Finding(Code c, int s, String why) { code=c; segment=s; cause=why; nextAction=action(c); }
    }
    public static final class Report {
        public final List<Finding> findings;
        public final boolean ackEligible = false;
        public final boolean automaticRepairAllowed = false;
        Report(List<Finding> f) { findings=Collections.unmodifiableList(new ArrayList<Finding>(f)); }
        public boolean has(Code code) { for(Finding f:findings) if(f.code==code)return true; return false; }
        public boolean requiresReview() {
            for(Finding f:findings) if(f.code!=Code.DURABILITY_UNKNOWN)return true;
            return false;
        }
        public int exitCode() { return requiresReview()?5:0; }
        public String json() {
            StringBuilder b=new StringBuilder("{\"schema_version\":1,\"ack_eligible\":false,\"automatic_repair_allowed\":false,\"directory_durability\":\"UNKNOWN\",\"status\":\"")
                .append(requiresReview()?"REVIEW_REQUIRED":"INSPECTED").append("\",\"findings\":[");
            int n=0;
            for(Finding f:findings) {
                if(n++>0)b.append(',');
                b.append("{\"code\":\"").append(f.code.name()).append("\",\"segment\":").append(f.segment)
                    .append(",\"cause\":\"").append(f.cause).append("\",\"next_action\":\"").append(f.nextAction.name()).append("\"}");
            }
            return b.append("]}").toString();
        }
        public String human() {
            StringBuilder b=new StringBuilder("INGRESS_DIAGNOSTICS=").append(requiresReview()?"REVIEW_REQUIRED":"INSPECTED")
                .append(" ACK_ELIGIBLE=false AUTO_REPAIR=false DIRECTORY_DURABILITY=UNKNOWN\n");
            for(Finding f:findings)b.append(f.code).append(" segment=").append(f.segment).append(" cause=").append(f.cause).append(" next_action=").append(f.nextAction).append('\n');
            return b.toString();
        }
    }
    private IngressDiagnostics() {}
    private static void add(List<Finding> f,Code c,int i,String why) { f.add(new Finding(c,i,why)); }
    static void safePath(Path p) throws IOException {
        for(Path a=p.toAbsolutePath();a!=null;a=a.getParent())
            if(Files.isSymbolicLink(a))throw new IOException("symbolic link path");
    }
    private static byte[] unhex(String s) throws IOException {
        if(s==null || (s.length()!=24&&s.length()!=32&&s.length()!=64) || !s.matches("[0-9a-f]+"))throw new IOException("invalid digest/identity");
        byte[] out=new byte[s.length()/2];
        for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return out;
    }
    private static void quiescent(File file) throws IOException {
        for(String suffix:new String[]{"-wal","-shm","-journal"})
            if(Files.exists(new File(file.getPath()+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IOException("active database requires a closed checkpointed snapshot");
    }
    /** Only a closed checkpointed snapshot: immutable avoids even WAL/SHM creation. */
    static Connection readOnly(File file) throws Exception {
        safePath(file.toPath());
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("database missing");
        quiescent(file);
        Class.forName("org.sqlite.JDBC");
        Connection db=DriverManager.getConnection("jdbc:sqlite:"+file.toURI().toASCIIString()+"?mode=ro&immutable=1");
        try {
            try(Statement s=db.createStatement()) { s.execute("PRAGMA query_only=ON"); s.execute("PRAGMA busy_timeout=1000"); }
            db.setAutoCommit(false);
            return db;
        } catch(Exception e) { db.close();throw e; }
    }
    /** Caller may retain a previous manifest to detect session/history rollback between polls. */
    public static Report inspect(Path dir, File ledger, WatermarkV2Prototype.ReadOpener opener,
                                 RotationManifestPrototype.State previous) {
        List<Finding> out=new ArrayList<Finding>();
        add(out,Code.DURABILITY_UNKNOWN,0,"inspection_does_not_prove_directory_durability");
        try {
            safePath(dir);
            if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("not directory");
        } catch(Exception bad) { add(out,Code.INVALID_PATH,0,"existing_non_symlink_session_required"); return new Report(out); }
        RotationManifestPrototype.State state;
        try { state=RotationManifestPrototype.read(dir,null); }
        catch(Exception bad) {
            Code c=Files.exists(RotationManifestPrototype.staged(dir),LinkOption.NOFOLLOW_LINKS)?Code.TMP_MARKER:Code.MANIFEST_INVALID;
            add(out,c,0,"manifest_not_a_valid_stable_snapshot"); return new Report(out);
        }
        try { RotationManifestPrototype.monotonic(previous,state); }
        catch(Exception bad) { add(out,Code.MANIFEST_HISTORY_CONFLICT,0,"session_or_manifest_history_changed"); }
        List<RotationManifestPrototype.Segment> segments=new ArrayList<RotationManifestPrototype.Segment>(state.sealed);
        if(state.active!=null)segments.add(state.active);
        if(segments.size()>=RotationManifestPrototype.MAX_SEGMENTS)add(out,Code.QUOTA_REACHED,0,"segment_capacity_reached_no_retention");
        Set<String> expected=new HashSet<String>();
        for(RotationManifestPrototype.Segment g:segments)expected.add(RotationManifestPrototype.segmentName(g.index));
        try {
            for(Path p:RotationManifestPrototype.segments(dir))if(!expected.contains(p.getFileName().toString()))
                add(out,Code.ORPHAN_SEGMENT,0,"unregistered_segment_requires_review");
        } catch(IOException e) { add(out,Code.INVALID_PATH,0,"directory_listing_failed"); }
        // One read transaction provides a consistent persisted ledger snapshot across segments.
        try(Connection db=readOnly(ledger)) {
            for(RotationManifestPrototype.Segment g:segments)inspectSegment(dir,g,state.sealed.contains(g),db,opener,out);
            quiescent(ledger);
        } catch(Exception unavailable) { add(out,Code.DATABASE_UNAVAILABLE,0,"existing_ledger_snapshot_unavailable"); }
        try {
            if(!java.util.Arrays.equals(state.raw,RotationManifestPrototype.read(dir,null).raw))
                add(out,Code.SNAPSHOT_CHANGED,0,"manifest_changed_during_inspection");
        } catch(Exception changed) { add(out,Code.SNAPSHOT_CHANGED,0,"manifest_changed_during_inspection"); }
        return new Report(out);
    }
    private static void inspectSegment(Path dir, RotationManifestPrototype.Segment g, boolean sealed, Connection db,
                                       WatermarkV2Prototype.ReadOpener opener,List<Finding> out) throws Exception {
        File source=RotationManifestPrototype.segment(dir,g.index).toFile();
        boolean pending=false;
        if(Files.exists(WatermarkV2Prototype.staged(source),LinkOption.NOFOLLOW_LINKS)) {
            add(out,Code.TMP_MARKER,g.index,"staged_watermark_retained"); pending=true;
        }
        if(Files.exists(WatermarkV2Prototype.legacyMarker(source),LinkOption.NOFOLLOW_LINKS)) {
            add(out,Files.exists(WatermarkV2Prototype.marker(source),LinkOption.NOFOLLOW_LINKS)?Code.MIXED_MARKER_FORMATS:Code.LEGACY_RLC1_PRESENT,g.index,"legacy_marker_requires_explicit_migration_contract");pending=true;
        }
        if(pending)return;
        WatermarkV2Prototype.Marker previous=null;
        boolean claim=false, highwater=false;
        try(PreparedStatement p=db.prepareStatement("SELECT source_path,identity_tag,identity_hex FROM proto_rlc2_sources WHERE source_uuid=?")) {
            p.setString(1,g.sourceUuid);
            try(ResultSet r=p.executeQuery()) { if(r.next()) {
                claim=true;
                WatermarkV2Prototype.SourceIdentity id=new WatermarkV2Prototype.SourceIdentity(WatermarkV2Prototype.IdentityTag.of(r.getInt(2)),unhex(r.getString(3)));
                if(!source.getCanonicalPath().equals(r.getString(1)))add(out,Code.SOURCE_CLAIM_CONFLICT,g.index,"source_uuid_claimed_at_different_path");
                if(!g.identity.equals(id))add(out,Code.IDENTITY_MISMATCH,g.index,"manifest_differs_from_persisted_identity");
            }}
        }
        try(PreparedStatement p=db.prepareStatement("SELECT lane FROM proto_lane_claims WHERE source_path=?")) {
            p.setString(1,source.getCanonicalPath());
            try(ResultSet r=p.executeQuery()) { if(r.next()&&!"journal".equals(r.getString(1)))
                add(out,Code.SOURCE_CLAIM_CONFLICT,g.index,"legacy_tsv_lane_owns_source"); }
        }
        try(PreparedStatement p=db.prepareStatement("SELECT generation,seq,offset_bytes,prefix_sha256 FROM proto_rlc2_highwater WHERE source_uuid=?")) {
            p.setString(1,g.sourceUuid);
            try(ResultSet r=p.executeQuery()) { if(r.next()) {
                highwater=true;
                previous=new WatermarkV2Prototype.Marker(g.identity,g.sourceUuid,r.getLong(1),r.getLong(2),r.getLong(3),unhex(r.getString(4)));
                if(!claim)add(out,Code.RECEIPT_INCONSISTENT,g.index,"highwater_without_source_claim");
            }}
        }
        WatermarkV2Prototype.Committed committed;
        if(claim&&!highwater)add(out,Code.RECEIPT_INCONSISTENT,g.index,"source_claim_without_atomic_highwater");
        try(PreparedStatement p=db.prepareStatement("SELECT source_uuid FROM proto_rlc2_sources WHERE source_path=?")) {
            p.setString(1,source.getCanonicalPath());
            try(ResultSet r=p.executeQuery()) { if(r.next()&&!g.sourceUuid.equals(r.getString(1)))
                add(out,Code.SOURCE_CLAIM_CONFLICT,g.index,"source_path_claimed_by_another_uuid"); }
        }
        try {
            safePath(source.toPath());
            try(WatermarkV2Prototype.ReadHandle h=opener.open(source.toPath())) {
                if(!g.identity.equals(h.identity())) { add(out,Code.IDENTITY_MISMATCH,g.index,"source_differs_from_manifest_identity");return; }
                if(sealed&&h.length()!=g.finalOffset) { add(out,Code.SEALED_SEGMENT_CHANGED,g.index,"sealed_length_changed");return; }
                if(h.length()>=IngressJournalPrototype.MAX_JOURNAL_BYTES)add(out,Code.QUOTA_REACHED,g.index,"source_byte_capacity_reached");
            }
            if(!JournalIdentityPrototype.readExisting(source).sourceUuid().equals(g.sourceUuid)) {
                add(out,Code.IDENTITY_MISMATCH,g.index,"source_uuid_differs_from_manifest");return;
            }
            committed=WatermarkV2Prototype.readCommitted(source,opener,null);
            WatermarkV2Prototype.Marker m=committed.marker;
            if(previous==null||previous.sequence<m.sequence||previous.offset<m.offset)
                add(out,Code.LEDGER_BEHIND,g.index,"ledger_does_not_yet_cover_committed_source");
            if(sealed&&(m.sequence!=g.finalSequence||m.offset!=g.finalOffset||!MessageDigest.isEqual(m.prefixHash(),g.prefixHash())))
                add(out,Code.SEALED_SEGMENT_CHANGED,g.index,"sealed_marker_or_prefix_changed");
            if(previous!=null) {
                if(m.generation<previous.generation)add(out,Code.STALE_WATERMARK,g.index,"marker_generation_below_persisted_highwater");
                if(m.sequence<previous.sequence||m.offset<previous.offset)add(out,Code.HIGHWATER_REGRESSION,g.index,"committed_extent_below_persisted_highwater");
                try { WatermarkV2Prototype.readCommitted(source,opener,previous); }
                catch(IOException mismatch) { add(out,Code.HIGHWATER_REGRESSION,g.index,"persisted_highwater_or_prefix_conflicts_with_source"); }
            }
        } catch(Exception invalid) { add(out,sealed?Code.SEALED_SEGMENT_CHANGED:Code.WATERMARK_INVALID,g.index,"committed_source_validation_failed");return; }
        long count=0;
        try(PreparedStatement p=db.prepareStatement("SELECT seq,record_id,frame_offset,body_sha256,body_tsv FROM proto_rlc2_receipts WHERE source_uuid=? ORDER BY seq")) {
            p.setString(1,g.sourceUuid);
            try(ResultSet r=p.executeQuery()) { while(r.next()) {
                long seq=r.getLong(1); count++;
                if(!highwater||seq!=count||seq>previous.sequence||seq>committed.entries.size()) {
                    add(out,Code.RECEIPT_INCONSISTENT,g.index,"receipt_sequence_outside_highwater");return;
                }
                IngressJournalPrototype.Entry e=committed.entries.get((int)seq-1);
                String digest=WatermarkLedgerPrototype.hex(WatermarkV2Prototype.sha().digest(e.row.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                if(!WatermarkLedgerPrototype.recordId(g.sourceUuid,seq).equals(r.getString(2))||e.offset!=r.getLong(3)||!digest.equals(r.getString(4))||!e.row.equals(r.getString(5))) {
                    add(out,Code.RECEIPT_INCONSISTENT,g.index,"receipt_bytes_or_identity_conflict");return;
                }
            }}
        }
        if(highwater&&count!=previous.sequence)add(out,Code.RECEIPT_INCONSISTENT,g.index,"receipt_count_differs_from_highwater");
    }
    /** Source-only dry run: validates observed envelopes/prefixes, never migrates or creates a ledger. */
    public static Report inspectMigration(File source,WatermarkV2Prototype.ReadOpener opener) {
        List<Finding> out=new ArrayList<Finding>();
        add(out,Code.DURABILITY_UNKNOWN,0,"inspection_does_not_prove_directory_durability");
        try {
            safePath(source.toPath());
            if(!source.getName().endsWith(".rlj")||!Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("source required");
            if(Files.size(source.toPath())>IngressJournalPrototype.MAX_JOURNAL_BYTES) {add(out,Code.QUOTA_REACHED,1,"source_exceeds_inspection_capacity");return new Report(out);}
        }catch(Exception invalid) {add(out,Code.INVALID_PATH,1,"existing_non_symlink_source_required");return new Report(out);}
        boolean pending=Files.exists(WatermarkV2Prototype.staged(source),LinkOption.NOFOLLOW_LINKS)||Files.exists(new File(source.getPath()+".commit.tmp").toPath(),LinkOption.NOFOLLOW_LINKS);
        if(pending)
            add(out,Code.TMP_MARKER,1,"staged_format_update_requires_review");
        boolean legacy=Files.exists(WatermarkV2Prototype.legacyMarker(source),LinkOption.NOFOLLOW_LINKS);
        boolean modern=Files.exists(WatermarkV2Prototype.marker(source),LinkOption.NOFOLLOW_LINKS);
        if(legacy&&modern) {add(out,Code.MIXED_MARKER_FORMATS,1,"both_marker_versions_present_no_automatic_choice");return new Report(out);}
        if(!legacy&&!modern) {add(out,Code.MARKER_MISSING,1,"no_committed_marker_do_not_fabricate");return new Report(out);}
        if(legacy) {
            add(out,Code.LEGACY_RLC1_PRESENT,1,"rlc1_filekey_hash_cannot_be_reinterpreted_as_rlc2_identity");
            if(pending)return new Report(out);
            try {
                DurableWatermarkPrototype.Snapshot old=DurableWatermarkPrototype.inspectEnvelope(source);
                if(!old.sourceUuid.equals(JournalIdentityPrototype.readExisting(source).sourceUuid()))throw new IOException("UUID mismatch");
                try(WatermarkV2Prototype.ReadHandle h=opener.open(source.toPath())) {
                    if(h.length()<old.offset||h.length()>IngressJournalPrototype.MAX_JOURNAL_BYTES)throw new IOException("extent mismatch");
                    byte[] prefix=h.read(0,(int)old.offset);
                    if(!MessageDigest.isEqual(WatermarkV2Prototype.sha().digest(prefix),old.prefixHash())||WatermarkV2Prototype.scanSequence(prefix,null)!=old.sequence)throw new IOException("legacy prefix mismatch");
                    if(h.length()>old.offset)add(out,Code.UNCOMMITTED_TAIL,1,"bytes_beyond_legacy_watermark_remain_unaccepted");
                }
            }catch(Exception invalid) {add(out,Code.WATERMARK_INVALID,1,"legacy_envelope_or_original_prefix_invalid");}
        } else {
            add(out,Code.LEDGER_REQUIRED,1,"persistent_claim_highwater_receipts_and_manifest_not_checked");
            if(pending)return new Report(out);
            try {
                WatermarkV2Prototype.Committed c=WatermarkV2Prototype.readCommitted(source,opener,null);
                try(WatermarkV2Prototype.ReadHandle h=opener.open(source.toPath())) {
                    if(h.length()>c.marker.offset)add(out,Code.UNCOMMITTED_TAIL,1,"bytes_beyond_rlc2_watermark_remain_unaccepted");
                }
            }catch(Exception invalid) {add(out,Code.WATERMARK_INVALID,1,"rlc2_committed_prefix_invalid");}
        }
        return new Report(out);
    }
    /** CLI uses POSIX identity; a native Windows ReadOpener can be supplied via the Java API. */
    public static int run(String[] args, java.io.PrintStream stdout, java.io.PrintStream stderr) {
        return run(args,stdout,stderr,WatermarkV2Prototype.POSIX_READER);
    }
    public static int run(String[] args, java.io.PrintStream stdout, java.io.PrintStream stderr, WatermarkV2Prototype.ReadOpener opener) {
        if(args.length>=1&&"--migration-preflight".equals(args[0])) {
            if(args.length<2||args.length>3||(args.length==3&&!"--json".equals(args[2]))) {
                stderr.println("Usage: IngressDiagnostics --migration-preflight SOURCE.rlj [--json]");return 2;
            }
            Report report=inspectMigration(new File(args[1]),opener);
            stdout.println(args.length==3?report.json():report.human());return report.exitCode();
        }
        if(args.length<2||args.length>3||(args.length==3&&!"--json".equals(args[2]))) {
            stderr.println("Usage: IngressDiagnostics <session-directory> <existing-ledger-db> [--json]");return 2;
        }
        Report report=inspect(new File(args[0]).toPath(),new File(args[1]),opener,null);
        stdout.println(args.length==3?report.json():report.human());return report.exitCode();
    }
    public static void main(String[] args) { System.exit(run(args,System.out,System.err)); }
}
