package net.neristal.rrbridge.prototype.ingress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import net.neristal.rrbridge.prototype.ingress.IngressDiagnostics.Code;
import net.neristal.rrbridge.prototype.ingress.IngressDiagnostics.Action;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Synthetic legacy envelopes can be inspected on Windows without pretending fileKey is NTFS identity. */
public final class IngressMigrationPreflightTest {
    private static int checks;
    private static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    private static Path legacy(Env env)throws Exception {
        Path dir=Files.createTempDirectory("romlogger-migration-");Path source=dir.resolve("source.rlj");
        Files.write(source,new byte[0]);JournalIdentityPrototype.open(source.toFile());
        try(OwnedJournal w=env.openOwned(source)) {w.append("E\tsynthetic-migration\t1\t1791838800000\tsynthetic");w.sync();}
        UUID uuid=UUID.fromString(JournalIdentityPrototype.readExisting(source.toFile()).sourceUuid());
        byte[] body=Files.readAllBytes(source);byte[] unmappableFileKeyHash=new byte[32];Arrays.fill(unmappableFileKeyHash,(byte)7);
        ByteBuffer marker=ByteBuffer.allocate(DurableWatermarkPrototype.BYTES);
        marker.putInt(DurableWatermarkPrototype.MAGIC).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
            .putLong(1).putLong(body.length).put(unmappableFileKeyHash).put(WatermarkV2Prototype.sha().digest(body));
        CRC32 crc=new CRC32();crc.update(marker.array(),0,DurableWatermarkPrototype.BYTES-4);marker.putInt((int)crc.getValue());
        Files.write(WatermarkV2Prototype.legacyMarker(source.toFile()),marker.array());return source;
    }
    public static void main(String[] args)throws Exception {
        Env env=Rlc2ProductAdapterTest.environment();String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"preflight actual JVM architecture");
        Path old=legacy(env);Map<String,String> before=Rlc2PreviewStoreTest.hashes(old.getParent());
        IngressDiagnostics.Report r=IngressDiagnostics.inspectMigration(old.toFile(),env.reader());
        check(r.has(Code.LEGACY_RLC1_PRESENT)&&!r.has(Code.WATERMARK_INVALID)&&r.requiresReview(),"valid RLC1 envelope and prefix still require migration contract");
        check(!r.ackEligible&&!r.automaticRepairAllowed&&r.json().contains("REQUIRE_EXPLICIT_MIGRATION_CONTRACT"),"legacy fileKey hash never becomes native identity, ACK or automatic repair");
        check(before.equals(Rlc2PreviewStoreTest.hashes(old.getParent()))&&!Files.exists(WatermarkV2Prototype.marker(old.toFile())),"migration dry run preserves all bytes and creates no RLC2 marker");
        try(OwnedJournal w=env.openOwned(old)){w.append("E\tsynthetic-migration\t1\t1791838800001\tunpublished");}
        check(IngressDiagnostics.inspectMigration(old.toFile(),env.reader()).has(Code.UNCOMMITTED_TAIL),"legacy uncommitted tail remains outside accepted prefix");
        Path corrupt=legacy(env);byte[] bad=Files.readAllBytes(WatermarkV2Prototype.legacyMarker(corrupt.toFile()));bad[4]^=1;Files.write(WatermarkV2Prototype.legacyMarker(corrupt.toFile()),bad);
        check(IngressDiagnostics.inspectMigration(corrupt.toFile(),env.reader()).has(Code.WATERMARK_INVALID),"legacy marker corruption detected without rewrite");
        Path staged=legacy(env);Files.write(new File(staged.toString()+".commit.tmp").toPath(),new byte[]{1});
        check(IngressDiagnostics.inspectMigration(staged.toFile(),env.reader()).has(Code.TMP_MARKER),"legacy tmp is preserved for review");
        Files.write(WatermarkV2Prototype.marker(staged.toFile()),new byte[]{1});
        check(IngressDiagnostics.inspectMigration(staged.toFile(),env.reader()).has(Code.MIXED_MARKER_FORMATS),"mixed RLC1/RLC2 paths never choose a format automatically");
        Path missing=Files.createTempDirectory("romlogger-migration-missing-").resolve("source.rlj");Files.write(missing,new byte[0]);
        check(IngressDiagnostics.inspectMigration(missing.toFile(),env.reader()).has(Code.MARKER_MISSING),"missing marker never fabricated");
        check(IngressDiagnostics.inspectMigration(missing.resolveSibling("absent.rlj").toFile(),env.reader()).has(Code.INVALID_PATH),"missing source never created");
        Rlc2ProductAdapterTest.Fixture modern=new Rlc2ProductAdapterTest.Fixture(env,false,Rlc2ProductAdapterTest.SESSION,1);
        Path source=RotationManifestPrototype.segment(modern.dir,2);
        check(IngressDiagnostics.inspectMigration(source.toFile(),env.reader()).has(Code.LEDGER_REQUIRED),"valid stateless RLC2 preflight cannot substitute for persisted provenance");
        modern.append(2,"S\t"+Rlc2ProductAdapterTest.SESSION+"\t1\t2\t1791838802000\tTEST_RPM=702");
        IngressDiagnostics.Report lag=IngressDiagnostics.inspect(modern.dir,modern.db.toFile(),env.reader(),null);
        check(lag.has(Code.LEDGER_BEHIND)&&!lag.has(Code.RECEIPT_INCONSISTENT),"ledger lag distinguished from receipt corruption");
        boolean hint=false;for(IngressDiagnostics.Finding finding:lag.findings)if(finding.code==Code.LEDGER_BEHIND)hint=finding.nextAction==Action.VERIFY_LEDGER_ALIGNMENT;
        check(hint&&lag.json().contains("\"automatic_repair_allowed\":false"),"manual ledger alignment hint has no automatic action authority");
        modern.ledger.ingest(source.toFile(),env.reader());
        check(!IngressDiagnostics.inspect(modern.dir,modern.db.toFile(),env.reader(),null).has(Code.LEDGER_BEHIND),"explicit synthetic ledger catch-up resolves lag observation");
        Files.write(WatermarkV2Prototype.legacyMarker(source.toFile()),new byte[]{1});
        check(IngressDiagnostics.inspect(modern.dir,modern.db.toFile(),env.reader(),null).has(Code.MIXED_MARKER_FORMATS),"session diagnostics also refuse legacy/new mixed source");
        Rlc2ProductAdapterTest.rejects("mixed markers block product projection",new Rlc2ProductAdapterTest.Action(){public void run()throws Exception{modern.project();}});
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();PrintStream out=new PrintStream(bytes,true,"UTF-8");
        check(IngressDiagnostics.run(new String[]{"--migration-preflight",old.toString(),"--json"},out,out,env.reader())==5,"migration CLI JSON exits review even for intact legacy bytes");
        check(!bytes.toString("UTF-8").contains(old.toString())&&bytes.toString("UTF-8").contains("next_action"),"migration CLI reveals fixed hints without source paths");
        check(IngressDiagnostics.run(new String[]{"--migration-preflight"},out,out,env.reader())==2,"migration CLI rejects incomplete invocation");
        System.out.println("Ingress migration preflight "+env.name()+" passed "+checks+" checks; read-only; no conversion/ACK/repair");
    }
}
