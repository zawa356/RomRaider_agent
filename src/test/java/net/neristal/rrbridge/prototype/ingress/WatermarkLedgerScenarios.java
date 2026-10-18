package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.WatermarkLedgerPrototype.Highwater;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Marker;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Phase 3c-1 RLC2 ledger scenarios with REAL SQLite, per platform environment. Synthetic TEMP only. */
final class WatermarkLedgerScenarios {
    private static int checks;
    private static void check(boolean v, String name) { if (!v) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private interface Action { void run() throws Exception; }
    private static void rejects(String name, Action a) throws Exception {
        try { a.run(); } catch (IOException expected) { check(true, name + " [" + expected.getMessage() + "]"); return; }
        throw new AssertionError("accepted: " + name);
    }
    private static String uuid(Path source) throws IOException { return JournalIdentityPrototype.readExisting(source.toFile()).sourceUuid(); }
    private static void publish(Env env, Path source, OwnedJournal w) throws IOException { WatermarkV2Scenarios.publish(env, source, w, WatermarkV2Prototype.NO_FAULT); }
    private static Path fresh(Env env, String tag, int rows) throws IOException {
        Path dir = WatermarkV2Scenarios.seed("ledger-" + tag), src = dir.resolve("source.rlj");
        try (OwnedJournal w = env.openOwned(src)) { for (int i = 1; i <= rows; i++) w.append("S\tsynthetic\t" + i + "\tTEST_RPM=" + (800 + i)); publish(env, src, w); }
        return dir;
    }
    private static void redigest(byte[] raw) {
        java.security.MessageDigest d = WatermarkV2Prototype.sha(); d.update(raw, 0, raw.length - 32);
        System.arraycopy(d.digest(), 0, raw, raw.length - 32, 32);
    }
    private static Env withChild(final Env base, final String childMain) {
        return new Env() {
            public String name() { return base.name(); }
            public OwnedJournal openOwned(Path s) throws IOException { return base.openOwned(s); }
            public WatermarkV2Prototype.ReadOpener reader() { return base.reader(); }
            public WatermarkV2Prototype.Platform platform() { return base.platform(); }
            public WatermarkV2Prototype.IdentityTag tag() { return base.tag(); }
            public WatermarkV2Prototype.DirectoryDurability expectedDirectory() { return base.expectedDirectory(); }
            public boolean heldNamesArePinned() { return base.heldNamesArePinned(); }
            public String childMain() { return childMain; }
        };
    }

    /** Child JVM modes: ledger-halt-before / ledger-halt-after / ledger-ingest. */
    static void child(Env env, String mode, Path dir, String tag) throws Exception {
        Path src = dir.resolve("source.rlj");
        WatermarkLedgerPrototype ledger = new WatermarkLedgerPrototype(dir.resolve("ledger.db").toFile());
        if (mode.equals("ledger-halt-before")) {
            ledger.ingest(src.toFile(), env.reader(), new WatermarkLedgerPrototype.Hook() { public void beforeCommit() { Runtime.getRuntime().halt(73); } });
            throw new AssertionError("halt missed");
        } else if (mode.equals("ledger-halt-after")) {
            System.out.println("INGESTED " + ledger.ingest(src.toFile(), env.reader()));
            Runtime.getRuntime().halt(74);
        } else if (mode.equals("ledger-ingest")) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(dir.resolve("go"))) { if (System.nanoTime() > end) throw new IOException("deadline"); Thread.sleep(2); }
            String out;
            try { out = "added=" + ledger.ingest(src.toFile(), env.reader()); }
            catch (java.sql.SQLException busy) { out = "busy=" + busy.getErrorCode(); }
            Files.write(dir.resolve(tag + ".out"), out.getBytes(StandardCharsets.UTF_8));
        } else throw new IllegalArgumentException(mode);
    }

    static int run(Env base, String childMain) throws Exception {
        final Env env = withChild(base, childMain);
        String model = System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if (model != null) check(model.equals(System.getProperty("sun.arch.data.model")), env.name() + " ledger actual data model " + model);

        // 1-2. first claim, idempotence, next generation
        final Path dir = WatermarkV2Scenarios.seed("ledger-main"); final Path src = dir.resolve("source.rlj");
        final WatermarkLedgerPrototype ledger = new WatermarkLedgerPrototype(dir.resolve("ledger.db").toFile());
        final String id = uuid(src);
        byte[] gen1;
        try (OwnedJournal w = env.openOwned(src)) {
            w.append("E\tsynthetic\tone"); w.append("S\tsynthetic\t1\tTEST_RPM=900"); publish(env, src, w);
            gen1 = Files.readAllBytes(WatermarkV2Prototype.marker(src.toFile()));
            check(ledger.ingest(src.toFile(), env.reader()) == 2, "first ingest claims source and records 2 receipts");
            Highwater h = ledger.highwater(id);
            check(h.generation == 1 && h.sequence == 2 && h.receipts == 2, "highwater gen1/seq2 and receipts commit together");
            check(ledger.ingest(src.toFile(), env.reader()) == 0 && ledger.highwater(id).receipts == 2, "re-ingest is idempotent (no duplicate receipts)");
            w.append("S\tsynthetic\t2\tTEST_RPM=910"); publish(env, src, w);
            check(ledger.ingest(src.toFile(), env.reader()) == 1 && ledger.highwater(id).generation == 2, "gen2 adds exactly the new receipt");
        }

        // 3. stale marker against PERSISTED highwater (closes 3b counterexample 1)
        final Path m = WatermarkV2Prototype.marker(src.toFile()); final byte[] gen2 = Files.readAllBytes(m);
        Files.write(m, gen1);
        rejects("stale gen1 marker rejected by persisted highwater (fresh process, no memory)", new Action() { public void run() throws Exception {
            new WatermarkLedgerPrototype(dir.resolve("ledger.db").toFile()).ingest(src.toFile(), env.reader()); } });
        check(ledger.highwater(id).generation == 2 && ledger.highwater(id).receipts == 3, "ledger unchanged after stale marker");
        Files.write(m, gen2);

        // 4. forged marker re-pointed to a copy (closes 3b counterexample 2)
        final Path cdir = Files.createTempDirectory("romlogger-rlc2-ledger-copy-"); final Path csrc = cdir.resolve("source.rlj");
        for (String n : new String[]{"source.rlj", "source.rlj.identity", "source.rlj.commit2"}) Files.copy(dir.resolve(n), cdir.resolve(n));
        try (WatermarkV2Prototype.ReadHandle h = env.reader().open(csrc)) {
            Path cm = WatermarkV2Prototype.marker(csrc.toFile()); Marker cmk = WatermarkV2Prototype.decode(Files.readAllBytes(cm));
            Files.write(cm, WatermarkV2Prototype.encode(new Marker(h.identity(), cmk.sourceUuid, cmk.generation, cmk.sequence, cmk.offset, cmk.prefixHash())));
        }
        check(WatermarkV2Prototype.readCommitted(csrc.toFile(), env.reader(), null).entries.size() == 3, "forged copy alone still passes the stateless reader (3b counterexample reproduced)");
        rejects("ledger rejects forged copy at another path (UUID already claimed)", new Action() { public void run() throws Exception { ledger.ingest(csrc.toFile(), env.reader()); } });
        Path aside = dir.resolve("original-aside");
        Files.createDirectory(aside);
        for (String n : new String[]{"source.rlj", "source.rlj.identity", "source.rlj.commit2"}) Files.move(dir.resolve(n), aside.resolve(n));
        for (String n : new String[]{"source.rlj", "source.rlj.identity", "source.rlj.commit2"}) Files.copy(cdir.resolve(n), dir.resolve(n));
        rejects("ledger rejects forged copy placed at the claimed path (expected identity persisted)", new Action() { public void run() throws Exception { ledger.ingest(src.toFile(), env.reader()); } });
        for (String n : new String[]{"source.rlj", "source.rlj.identity", "source.rlj.commit2"}) Files.move(aside.resolve(n), dir.resolve(n), StandardCopyOption.REPLACE_EXISTING);
        check(ledger.ingest(src.toFile(), env.reader()) == 0 && ledger.highwater(id).receipts == 3, "restored original still matches ledger");

        // 5. legacy TSV lane exclusion
        final Path tdir = fresh(env, "tsv-lane", 1); final Path tsrc = tdir.resolve("source.rlj");
        final WatermarkLedgerPrototype tl = new WatermarkLedgerPrototype(tdir.resolve("ledger.db").toFile());
        tl.claimTsvLane(tsrc.toFile());
        rejects("journal ingest refused while legacy TSV lane claims the path", new Action() { public void run() throws Exception { tl.ingest(tsrc.toFile(), env.reader()); } });
        check(tl.highwater(uuid(tsrc)).receipts == 0, "no receipts under TSV lane claim");

        // 6. tampered persisted receipt
        final Path rdir = fresh(env, "receipt-tamper", 2); final Path rsrc = rdir.resolve("source.rlj");
        final WatermarkLedgerPrototype rl = new WatermarkLedgerPrototype(rdir.resolve("ledger.db").toFile());
        rl.ingest(rsrc.toFile(), env.reader());
        rl.execute("UPDATE proto_rlc2_receipts SET body_tsv='S\tforged' WHERE seq=1");
        try (OwnedJournal w = env.openOwned(rsrc)) { w.append("S\tsynthetic\t3\tTEST_RPM=930"); publish(env, rsrc, w); }
        rejects("tampered persisted receipt detected on next ingest", new Action() { public void run() throws Exception { rl.ingest(rsrc.toFile(), env.reader()); } });
        check(rl.highwater(uuid(rsrc)).sequence == 2, "highwater not advanced past tampered ledger");

        // 7. marker regenerated after ledger observed a later generation
        final Path gdir = fresh(env, "regenerated", 1); final Path gsrc = gdir.resolve("source.rlj");
        final WatermarkLedgerPrototype gl = new WatermarkLedgerPrototype(gdir.resolve("ledger.db").toFile());
        try (OwnedJournal w = env.openOwned(gsrc)) { w.append("S\tsynthetic\t2\tTEST_RPM=940"); publish(env, gsrc, w); }
        gl.ingest(gsrc.toFile(), env.reader());
        Files.move(WatermarkV2Prototype.marker(gsrc.toFile()), gdir.resolve("gen2-evidence.commit2"));
        try (OwnedJournal w = env.openOwned(gsrc)) { publish(env, gsrc, w); }
        check(WatermarkV2Prototype.decode(Files.readAllBytes(WatermarkV2Prototype.marker(gsrc.toFile()))).generation == 1, "marker regenerated from scratch at gen1");
        rejects("regenerated gen1 marker rejected against persisted gen2", new Action() { public void run() throws Exception { gl.ingest(gsrc.toFile(), env.reader()); } });

        // 8. in-process failure before commit
        final Path fdir = fresh(env, "fault", 2); final Path fsrc = fdir.resolve("source.rlj");
        final WatermarkLedgerPrototype fl = new WatermarkLedgerPrototype(fdir.resolve("ledger.db").toFile());
        try { fl.ingest(fsrc.toFile(), env.reader(), new WatermarkLedgerPrototype.Hook() { public void beforeCommit() throws Exception { throw new IOException("injected before commit"); } }); throw new AssertionError("hook missed"); }
        catch (IOException injected) { check(fl.highwater(uuid(fsrc)).receipts == 0 && fl.highwater(uuid(fsrc)).sequence == 0, "failure before commit rolls back claim, receipts and highwater"); }
        check(fl.ingest(fsrc.toFile(), env.reader()) == 2 && fl.ingest(fsrc.toFile(), env.reader()) == 0, "retry after rollback records exactly once");

        // 9. separate-JVM halt before / after SQLite commit
        for (String mode : new String[]{"ledger-halt-before", "ledger-halt-after"}) {
            Path hdir = fresh(env, mode, 2); Path hsrc = hdir.resolve("source.rlj");
            Process p = WatermarkV2Scenarios.start(env, hdir, mode, "halt");
            check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == (mode.endsWith("before") ? 73 : 74), "separate JVM " + mode + " halted");
            WatermarkLedgerPrototype hl = new WatermarkLedgerPrototype(hdir.resolve("ledger.db").toFile());
            if (mode.endsWith("before")) {
                check(hl.highwater(uuid(hsrc)).receipts == 0, "halt before commit leaves no receipts/highwater");
                check(hl.ingest(hsrc.toFile(), env.reader()) == 2, "restart after pre-commit halt records receipts once");
            } else {
                check(hl.highwater(uuid(hsrc)).receipts == 2 && hl.highwater(uuid(hsrc)).sequence == 2, "halt after commit keeps committed receipts+highwater");
                check(hl.ingest(hsrc.toFile(), env.reader()) == 0, "restart after post-commit halt adds no duplicates");
            }
        }

        // 10. two JVMs ingest concurrently
        Path kdir = fresh(env, "concurrent", 3); Path ksrc = kdir.resolve("source.rlj");
        new WatermarkLedgerPrototype(kdir.resolve("ledger.db").toFile()); // schema before racing
        Process a = WatermarkV2Scenarios.start(env, kdir, "ledger-ingest", "a"), b = WatermarkV2Scenarios.start(env, kdir, "ledger-ingest", "b");
        Thread.sleep(300); Files.write(kdir.resolve("go"), new byte[0]);
        check(a.waitFor(30, TimeUnit.SECONDS) && b.waitFor(30, TimeUnit.SECONDS) && a.exitValue() == 0 && b.exitValue() == 0, "concurrent ingest JVMs exit cleanly");
        String oa = new String(Files.readAllBytes(kdir.resolve("a.out")), StandardCharsets.UTF_8), ob = new String(Files.readAllBytes(kdir.resolve("b.out")), StandardCharsets.UTF_8);
        System.out.println("OBSERVED concurrent ingest a=" + oa + " b=" + ob);
        WatermarkLedgerPrototype kl = new WatermarkLedgerPrototype(kdir.resolve("ledger.db").toFile());
        check(kl.highwater(uuid(ksrc)).receipts == 3 && kl.highwater(uuid(ksrc)).sequence == 3, "concurrent ingest yields exactly one receipt per frame");
        check((oa + ob).contains("added=3") && !(oa.equals("added=3") && ob.equals("added=3")), "exactly one JVM recorded the receipts");

        System.out.println(env.name() + " RLC2 ledger scenarios passed " + checks + " checks; real SQLite; receipts never ACK-eligible; directory=" + env.expectedDirectory());
        return checks;
    }
    private WatermarkLedgerScenarios() {}
}
