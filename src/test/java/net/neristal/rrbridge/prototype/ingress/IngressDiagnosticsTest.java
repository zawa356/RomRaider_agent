package net.neristal.rrbridge.prototype.ingress;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.neristal.rrbridge.prototype.ingress.IngressDiagnostics.Code;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Real SQLite + real platform source identity; no mocked persistence. */
public final class IngressDiagnosticsTest {
    private static int checks;
    private static void check(boolean b,String why) { if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why); }
    private static final class Fixture {
        final Path dir,db,source;
        final WatermarkLedgerPrototype ledger;
        final Env env;
        Fixture(Env env,boolean sealed)throws Exception {
            this.env=env;dir=Files.createTempDirectory("romlogger-diagnostics-");db=dir.resolve("ledger.db");
            RotationManifestPrototype.begin(dir,env.reader(),env.platform());
            source=RotationManifestPrototype.segment(dir,1);
            try(OwnedJournal w=env.openOwned(source)) {
                w.append("S\tsynthetic-secret-fixture\tfirst\tTEST_RPM=701");
                WatermarkV2Scenarios.publish(env,source,w,WatermarkV2Prototype.NO_FAULT);
            }
            ledger=new WatermarkLedgerPrototype(db.toFile());ledger.ingest(source.toFile(),env.reader());
            if(sealed)RotationManifestPrototype.seal(dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        }
        IngressDiagnostics.Report inspect() { return IngressDiagnostics.inspect(dir,db.toFile(),env.reader(),null); }
    }
    private static Map<String,byte[]> snapshot(Path dir)throws Exception {
        Map<String,byte[]> b=new LinkedHashMap<String,byte[]>();
        try(java.nio.file.DirectoryStream<Path> list=Files.newDirectoryStream(dir)) {
            for(Path p:list)if(Files.isRegularFile(p))b.put(p.getFileName().toString(),Files.readAllBytes(p));
        }return b;
    }
    private static void same(Map<String,byte[]> a,Map<String,byte[]> b) {
        check(a.keySet().equals(b.keySet()),"diagnosis creates or deletes no source/ledger files");
        for(String key:a.keySet())check(Arrays.equals(a.get(key),b.get(key)),"diagnosis preserves "+key);
    }
    public static void main(String[] args)throws Exception {
        boolean windows=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Env env;
        if(windows) {
            java.lang.reflect.Field field=Class.forName("net.neristal.rrbridge.prototype.ingress.WindowsWatermarkV2Test").getDeclaredField("ENV");
            field.setAccessible(true);env=(Env)field.get(null);
        } else env=PosixWatermarkV2Test.ENV;
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual requested JVM architecture");
        Fixture healthy=new Fixture(env,true);
        Map<String,byte[]> before=snapshot(healthy.dir);
        IngressDiagnostics.Report r=healthy.inspect();
        check(!r.requiresReview()&&r.has(Code.DURABILITY_UNKNOWN)&&!r.ackEligible,"healthy inspection never certifies durability or ACK");
        check(r.json().contains("\"schema_version\":1")&&!r.json().contains("synthetic-secret-fixture")&&!r.human().contains(healthy.dir.toString()),"output is structured and source-content/path free");
        same(before,snapshot(healthy.dir));
        Files.write(new java.io.File(healthy.db.toString()+"-wal").toPath(),new byte[]{1});
        Map<String,byte[]> activeBefore=snapshot(healthy.dir);
        check(healthy.inspect().has(Code.DATABASE_UNAVAILABLE),"WAL snapshot rejected rather than silently ignoring committed WAL data");
        same(activeBefore,snapshot(healthy.dir));
        Files.delete(new java.io.File(healthy.db.toString()+"-wal").toPath());
        Fixture orphan=new Fixture(env,false);Files.write(orphan.dir.resolve("segment-0015.rlj"),new byte[0]);
        check(orphan.inspect().has(Code.ORPHAN_SEGMENT),"orphan detected without adoption");
        Fixture tmp=new Fixture(env,false);Files.write(WatermarkV2Prototype.staged(tmp.source.toFile()),new byte[]{1});
        check(tmp.inspect().has(Code.TMP_MARKER),"staged watermark detected and retained");
        Fixture mt=new Fixture(env,false);Files.write(RotationManifestPrototype.staged(mt.dir),new byte[]{1});
        check(mt.inspect().has(Code.TMP_MARKER),"staged manifest detected");
        Fixture corrupt=new Fixture(env,false);Files.write(RotationManifestPrototype.manifest(corrupt.dir),new byte[]{1});
        check(corrupt.inspect().has(Code.MANIFEST_INVALID),"corrupt manifest detected");
        Fixture stale=new Fixture(env,false);byte[] old=Files.readAllBytes(WatermarkV2Prototype.marker(stale.source.toFile()));
        try(OwnedJournal w=env.openOwned(stale.source)) { w.append("S\tsynthetic\tsecond\tTEST_RPM=702");WatermarkV2Scenarios.publish(env,stale.source,w,WatermarkV2Prototype.NO_FAULT); }
        stale.ledger.ingest(stale.source.toFile(),env.reader());Files.write(WatermarkV2Prototype.marker(stale.source.toFile()),old);
        check(stale.inspect().has(Code.STALE_WATERMARK)&&stale.inspect().has(Code.HIGHWATER_REGRESSION),"restart-safe stale marker and highwater regression detected");
        Fixture receipt=new Fixture(env,false);receipt.ledger.execute("UPDATE proto_rlc2_receipts SET body_tsv='changed'");
        check(receipt.inspect().has(Code.RECEIPT_INCONSISTENT),"receipt tampering detected");
        Fixture missing=new Fixture(env,false);missing.ledger.execute("DELETE FROM proto_rlc2_receipts");
        check(missing.inspect().has(Code.RECEIPT_INCONSISTENT),"missing receipt detected");
        Fixture lane=new Fixture(env,false);lane.ledger.execute("UPDATE proto_lane_claims SET lane='tsv'");
        check(lane.inspect().has(Code.SOURCE_CLAIM_CONFLICT),"legacy lane collision detected");
        Fixture identity=new Fixture(env,false);identity.ledger.execute("UPDATE proto_rlc2_sources SET identity_hex=(CASE substr(identity_hex,1,1) WHEN 'f' THEN 'e' ELSE 'f' END)||substr(identity_hex,2)");
        check(identity.inspect().has(Code.IDENTITY_MISMATCH),"persisted expected identity mismatch detected");
        Fixture growth=new Fixture(env,true);try(OwnedJournal w=env.openOwned(growth.source)) { w.append("S\tsynthetic\tlate\tTEST_RPM=2"); }
        check(growth.inspect().has(Code.SEALED_SEGMENT_CHANGED),"sealed growth detected");
        Fixture quota=new Fixture(env,true);
        for(int i=2;i<=RotationManifestPrototype.MAX_SEGMENTS;i++) {
            RotationManifestPrototype.startNext(quota.dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
            Path seg=RotationManifestPrototype.segment(quota.dir,i);
            try(OwnedJournal w=env.openOwned(seg)) { w.append("S\tsynthetic\tquota\tTEST_RPM=1");WatermarkV2Scenarios.publish(env,seg,w,WatermarkV2Prototype.NO_FAULT); }
            RotationManifestPrototype.seal(quota.dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        }
        check(quota.inspect().has(Code.QUOTA_REACHED),"sixteen actual sealed segments report quota without deleting originals");
        Fixture history=new Fixture(env,false);RotationManifestPrototype.State prior=RotationManifestPrototype.read(history.dir,null);
        byte[] priorBytes=Files.readAllBytes(RotationManifestPrototype.manifest(history.dir));
        RotationManifestPrototype.State newer=RotationManifestPrototype.seal(history.dir,env.reader(),env.platform(),RotationManifestPrototype.NO_FAULT);
        Files.write(RotationManifestPrototype.manifest(history.dir),priorBytes);
        check(IngressDiagnostics.inspect(history.dir,history.db.toFile(),env.reader(),newer).has(Code.MANIFEST_HISTORY_CONFLICT),"manifest history rollback detected");
        Path absent=healthy.dir.resolve("does-not-exist.db");
        check(IngressDiagnostics.inspect(healthy.dir,absent.toFile(),env.reader(),null).has(Code.DATABASE_UNAVAILABLE)&&!Files.exists(absent),"absent database never created");
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream ps=new PrintStream(output,true,"UTF-8");
        check(IngressDiagnostics.run(new String[]{healthy.dir.toString(),healthy.db.toString(),"--json"},ps,ps,env.reader())==0,"JSON CLI healthy exit");
        check(IngressDiagnostics.run(new String[]{receipt.dir.toString(),receipt.db.toString()},ps,ps,env.reader())==5,"human CLI review exit");
        check(IngressDiagnostics.run(new String[]{},ps,ps,env.reader())==2,"CLI usage exit");
        System.out.println("Ingress diagnostics "+env.name()+" passed "+checks+" checks; real SQLite; ACK disabled");
    }
}
