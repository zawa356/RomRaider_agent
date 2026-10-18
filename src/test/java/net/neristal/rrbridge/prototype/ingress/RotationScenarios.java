package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.RotationManifestPrototype.Stage;
import net.neristal.rrbridge.prototype.ingress.RotationManifestPrototype.State;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.Env;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Scenarios.OwnedJournal;

/** Phase 3d-1 seal/rotation scenarios over RLC2 segments, per platform, plus session ingest into the 3c-1 ledger. */
final class RotationScenarios {
    private static int checks;
    private static void check(boolean v, String n) { if (!v) throw new AssertionError(n); checks++; System.out.println("PASS " + n); }
    private interface Action { void run() throws Exception; }
    private static void rejects(String n, Action a) throws Exception {
        try { a.run(); } catch (IOException expected) { check(true, n + " [" + expected.getMessage() + "]"); return; }
        throw new AssertionError("accepted: " + n);
    }
    private static Path dir(String tag) throws IOException { return Files.createTempDirectory("romlogger-rlm1-" + tag + "-"); }
    private static void write(Env env, Path seg, int rows, String tag) throws IOException {
        try (OwnedJournal w = env.openOwned(seg)) {
            for (int i = 0; i < rows; i++) w.append("S\tsynthetic\t" + tag + i + "\tTEST_RPM=" + (700 + i));
            WatermarkV2Scenarios.publish(env, seg, w, WatermarkV2Prototype.NO_FAULT);
        }
    }
    /** Build a session with `sealed` sealed segments (2 rows each) and an active one. */
    private static Path session(Env env, String tag, int sealed) throws Exception {
        Path d = dir(tag);
        RotationManifestPrototype.begin(d, env.reader(), env.platform());
        for (int i = 1; i <= sealed; i++) {
            write(env, RotationManifestPrototype.segment(d, i), 2, "s" + i + "-");
            RotationManifestPrototype.seal(d, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
            RotationManifestPrototype.startNext(d, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
        }
        return d;
    }

    static void child(Env env, String mode, Path d, String tag) throws Exception {
        final String point = mode.substring(mode.indexOf(':') + 1);
        RotationManifestPrototype.Hook halt = new RotationManifestPrototype.Hook() { public void at(Stage s) { if (s.name().equals(point)) Runtime.getRuntime().halt(73); } };
        if (mode.startsWith("rot-seal:")) RotationManifestPrototype.seal(d, env.reader(), env.platform(), halt);
        else if (mode.startsWith("rot-next:")) RotationManifestPrototype.startNext(d, env.reader(), env.platform(), halt);
        else throw new IllegalArgumentException(mode);
        throw new AssertionError("halt missed");
    }

    static int run(Env base, String childMain) throws Exception {
        final Env env = WatermarkLedgerScenarios.withChild(base, childMain);
        String model = System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if (model != null) check(model.equals(System.getProperty("sun.arch.data.model")), env.name() + " rotation actual data model " + model);

        // 1. three-segment rotation and verification
        final Path d = session(env, "main", 2);
        write(env, RotationManifestPrototype.segment(d, 3), 1, "s3-");
        State v = RotationManifestPrototype.verify(d, env.reader(), null);
        check(v.sealed.size() == 2 && v.active != null && v.active.index == 3 && v.generation == 5, "two sealed + active segment 3 at manifest gen5");
        check(!v.sealed.get(0).sourceUuid.equals(v.sealed.get(1).sourceUuid) && !v.sealed.get(1).identity.equals(v.active.identity),
            "each segment has its own UUID and identity");
        final State seen = v;

        // 2. session ingest into the 3c-1 ledger, in manifest order
        final WatermarkLedgerPrototype ledger = new WatermarkLedgerPrototype(d.resolve("session-ledger.db").toFile());
        check(RotationManifestPrototype.ingestSession(d, ledger, env.reader(), seen) == 5 && RotationManifestPrototype.ingestSession(d, ledger, env.reader(), seen) == 0,
            "session ingest records 2+2+1 receipts once, in manifest order");

        // 3. seal requires full RLC2 coverage
        try (OwnedJournal w = env.openOwned(RotationManifestPrototype.segment(d, 3))) { w.append("S\tsynthetic\tunpublished\tTEST_RPM=1"); }
        rejects("seal refused while active segment has an unpublished tail", new Action() { public void run() throws Exception {
            RotationManifestPrototype.seal(d, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });
        rejects("startNext refused while a segment is still active", new Action() { public void run() throws Exception {
            RotationManifestPrototype.startNext(d, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });

        // 4. growth after seal
        final Path g = session(env, "growth", 1);
        try (OwnedJournal w = env.openOwned(RotationManifestPrototype.segment(g, 1))) { w.append("S\tsynthetic\tlate\tTEST_RPM=2"); }
        rejects("append after seal detected (sealed length is final)", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(g, env.reader(), null); } });

        // 5. manifest rollback and history rewrite
        final Path r = session(env, "rollback", 1);
        final byte[] gen3 = Files.readAllBytes(r.resolve(RotationManifestPrototype.MANIFEST));
        final State r3 = RotationManifestPrototype.verify(r, env.reader(), null);
        write(env, RotationManifestPrototype.segment(r, 2), 1, "r2-");
        RotationManifestPrototype.seal(r, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
        final State r4 = RotationManifestPrototype.verify(r, env.reader(), r3);
        Files.write(r.resolve(RotationManifestPrototype.MANIFEST), gen3);
        rejects("rolled-back manifest rejected against previously observed generation", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(r, env.reader(), r4); } });
        State cur = RotationManifestPrototype.decode(Files.readAllBytes(r.resolve(RotationManifestPrototype.MANIFEST)));
        RotationManifestPrototype.Segment s1 = r4.sealed.get(0);
        java.util.List<RotationManifestPrototype.Segment> forged = new java.util.ArrayList<RotationManifestPrototype.Segment>();
        forged.add(new RotationManifestPrototype.Segment(1, s1.sourceUuid, s1.identity, s1.finalSequence - 1, s1.finalOffset, s1.prefixHash()));
        forged.add(r4.sealed.get(1));
        Files.write(r.resolve(RotationManifestPrototype.MANIFEST), RotationManifestPrototype.encode(cur.sessionUuid, r4.generation + 1, forged, null));
        rejects("re-digested manifest rewriting a sealed entry rejected against history", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(r, env.reader(), r4); } });
        rejects("re-digested manifest rewriting a sealed entry rejected against segment marker", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(r, env.reader(), null); } });

        // 6. reordered / duplicate entries, partial manifest
        final Path o = session(env, "reorder", 2);
        State os = RotationManifestPrototype.verify(o, env.reader(), null);
        java.util.List<RotationManifestPrototype.Segment> swapped = new java.util.ArrayList<RotationManifestPrototype.Segment>(Arrays.asList(os.sealed.get(1), os.sealed.get(0)));
        final Path om = o.resolve(RotationManifestPrototype.MANIFEST); final byte[] ogood = Files.readAllBytes(om);
        Files.write(om, RotationManifestPrototype.encode(os.sessionUuid, os.generation + 1, swapped, os.active));
        rejects("reordered sealed segments rejected", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(o, env.reader(), null); } });
        Files.write(om, Arrays.copyOf(ogood, ogood.length - 5));
        rejects("partial manifest rejected", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(o, env.reader(), null); } });
        Files.write(om, ogood);

        // 7. orphan segment, copied UUID, missing sealed file
        final Path p = session(env, "orphan", 1);
        Files.write(p.resolve("segment-0009.rlj"), new byte[0]);
        rejects("orphan segment file rejected (review, never adopted)", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(p, env.reader(), null); } });
        final Path u = session(env, "uuid-copy", 1);
        Files.copy(RotationManifestPrototype.segment(u, 1).resolveSibling("segment-0001.rlj.identity"), RotationManifestPrototype.segment(u, 2).resolveSibling("segment-0002.rlj.identity"), StandardCopyOption.REPLACE_EXISTING);
        rejects("active segment with copied UUID sidecar rejected", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(u, env.reader(), null); } });
        final Path q = session(env, "missing", 1);
        Files.move(RotationManifestPrototype.segment(q, 1), q.resolve("aside.bin"));
        rejects("missing sealed segment rejected", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(q, env.reader(), null); } });

        // 8. 16-segment quota: explicit stop, nothing deleted
        final Path z = session(env, "quota", 15);
        write(env, RotationManifestPrototype.segment(z, 16), 1, "z16-");
        RotationManifestPrototype.seal(z, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
        rejects("17th segment refused at quota (explicit stop)", new Action() { public void run() throws Exception {
            RotationManifestPrototype.startNext(z, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });
        State zs = RotationManifestPrototype.verify(z, env.reader(), null);
        check(zs.sealed.size() == 16 && zs.active == null && RotationManifestPrototype.segments(z).size() == 16 && !Files.exists(RotationManifestPrototype.segment(z, 17)),
            "all 16 sealed segments retained, no 17th file, nothing deleted");

        // 9. empty active segment is never sealed
        final Path e = dir("empty");
        RotationManifestPrototype.begin(e, env.reader(), env.platform());
        rejects("unpublished empty active segment cannot be sealed", new Action() { public void run() throws Exception {
            RotationManifestPrototype.seal(e, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });
        try (OwnedJournal w = env.openOwned(RotationManifestPrototype.segment(e, 1))) { WatermarkV2Scenarios.publish(env, RotationManifestPrototype.segment(e, 1), w, WatermarkV2Prototype.NO_FAULT); }
        check(WatermarkV2Prototype.decode(Files.readAllBytes(WatermarkV2Prototype.marker(RotationManifestPrototype.segment(e, 1).toFile()))).sequence == 0, "empty segment published as seq0/offset0");
        rejects("published empty segment is never sealed", new Action() { public void run() throws Exception {
            RotationManifestPrototype.seal(e, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });
        rejects("begin refused in a non-empty session directory", new Action() { public void run() throws Exception { RotationManifestPrototype.begin(e, env.reader(), env.platform()); } });

        // 10. in-process faults and separate-JVM halts during seal / startNext
        for (final String op : new String[]{"seal", "next"}) {
            for (final Stage stage : Stage.values()) {
                if (op.equals("seal") && stage == Stage.SEGMENT_CREATED) continue;
                final Path f = session(env, "fault-" + op + "-" + stage, 1);
                write(env, RotationManifestPrototype.segment(f, 2), 1, "f2-");
                if (op.equals("next")) RotationManifestPrototype.seal(f, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
                RotationManifestPrototype.Hook hook = new RotationManifestPrototype.Hook() { public void at(Stage s) throws IOException { if (s == stage) throw new IOException("injected " + s); } };
                try {
                    if (op.equals("seal")) RotationManifestPrototype.seal(f, env.reader(), env.platform(), hook);
                    else RotationManifestPrototype.startNext(f, env.reader(), env.platform(), hook);
                    throw new AssertionError("hook missed");
                } catch (IOException injected) {
                    if (!String.valueOf(injected.getMessage()).startsWith("injected")) throw injected; // only the injected fault is expected
                }
                verifyInterrupted(env, f, op, stage, "fault");
            }
        }
        for (String op : new String[]{"seal", "next"}) {
            for (Stage stage : Stage.values()) {
                if (op.equals("seal") && stage == Stage.SEGMENT_CREATED) continue;
                Path h = session(env, "halt-" + op + "-" + stage, 1);
                write(env, RotationManifestPrototype.segment(h, 2), 1, "h2-");
                if (op.equals("next")) RotationManifestPrototype.seal(h, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT);
                Process pr = WatermarkV2Scenarios.start(env, h, "rot-" + op + ":" + stage, "halt");
                try { check(pr.waitFor(30, TimeUnit.SECONDS) && pr.exitValue() == 73, "separate JVM halted during " + op + " at " + stage); }
                finally { if (pr.isAlive()) pr.destroyForcibly(); }
                verifyInterrupted(env, h, op, stage, "halt");
            }
        }
        System.out.println("NOTE Runtime.halt is not physical power loss; manifest directory durability follows the RLC2 classification (" + env.expectedDirectory() + ").");
        System.out.println(env.name() + " RLM1 rotation scenarios passed " + checks + " checks; nothing deleted/repaired; no product wiring");
        return checks;
    }

    /** seal: before REPLACED old manifest (active 2) valid unless staged; next: SEGMENT_CREATED leaves an orphan. */
    private static void verifyInterrupted(final Env env, final Path f, String op, Stage stage, String how) throws Exception {
        String label = how + " " + op + " " + stage;
        final boolean staged = stage == Stage.TEMP_SYNCED;
        if (staged) {
            check(Files.exists(f.resolve(RotationManifestPrototype.MANIFEST + ".tmp")), label + ": staged manifest retained");
            rejects(label + ": verify fails closed on staged manifest", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(f, env.reader(), null); } });
            return;
        }
        if (stage == Stage.SEGMENT_CREATED) {
            check(Files.exists(RotationManifestPrototype.segment(f, 3)), label + ": new segment file retained");
            rejects(label + ": unregistered new segment is an orphan (review, no auto-adopt)", new Action() { public void run() throws Exception { RotationManifestPrototype.verify(f, env.reader(), null); } });
            rejects(label + ": retry cannot recreate the segment (CREATE_NEW)", new Action() { public void run() throws Exception {
                RotationManifestPrototype.startNext(f, env.reader(), env.platform(), RotationManifestPrototype.NO_FAULT); } });
            return;
        }
        // REPLACED: the new manifest is visible even though the caller saw a failure/halt.
        State s = RotationManifestPrototype.verify(f, env.reader(), null);
        if (op.equals("seal")) check(s.sealed.size() == 2 && s.active == null, label + ": seal visible after replace (ambiguous outcome)");
        else check(s.sealed.size() == 2 && s.active != null && s.active.index == 3, label + ": next segment registered after replace");
    }
    private RotationScenarios() {}
}
