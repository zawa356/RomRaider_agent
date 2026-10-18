package net.neristal.rrbridge.prototype.ingress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Committed;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.DirectoryDurability;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Hook;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Marker;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.OwnedSource;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Platform;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Publication;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.ReadOpener;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Stage;

/**
 * Shared RLC2 commit-procedure scenarios, run once per real platform environment
 * (POSIX dev/inode on Linux, native NTFS file index on Windows). Synthetic TEMP data only.
 */
final class WatermarkV2Scenarios {
    /** A platform-specific writer-owned journal handle. */
    abstract static class OwnedJournal implements OwnedSource, AutoCloseable {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private long sequence;
        /** Adopt existing bytes as the writer record (validated RLJ1, bounded). */
        protected void adopt() throws IOException {
            long n = length();
            if (n > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("existing source over 8MiB quota");
            byte[] existing = read(0, (int) n);
            sequence = WatermarkV2Prototype.scanSequence(existing, null);
            written.write(existing, 0, existing.length);
        }
        protected abstract void writeAt(long offset, byte[] bytes) throws IOException;
        void append(String row) throws IOException {
            byte[] body = row.getBytes(StandardCharsets.UTF_8);
            if ((long) written.size() + 20 + body.length > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("quota stop");
            CRC32 crc = new CRC32(); crc.update(body);
            byte[] frame = ByteBuffer.allocate(20 + body.length).putInt(IngressJournalPrototype.FRAME_MAGIC).putInt(body.length)
                .putLong(sequence + 1).putInt((int) crc.getValue()).put(body).array();
            writeAt(written.size(), frame);
            written.write(frame, 0, frame.length); sequence++;
        }
        long writtenLength() { return written.size(); }
        byte[] writtenHash() { return WatermarkV2Prototype.sha().digest(written.toByteArray()); }
        public abstract void close() throws IOException;
    }

    interface Env {
        String name();
        OwnedJournal openOwned(Path source) throws IOException;
        ReadOpener reader();
        Platform platform();
        WatermarkV2Prototype.IdentityTag tag();
        DirectoryDurability expectedDirectory();
        /** True when the platform forbids renaming over a source/marker that is held open. */
        boolean heldNamesArePinned();
        String childMain();
    }

    private static int checks;
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    interface Action { void run() throws Exception; }
    static void rejects(String name, Action a) throws Exception {
        try { a.run(); } catch (IOException expected) { check(true, name + " [" + expected.getMessage() + "]"); return; }
        throw new AssertionError("accepted: " + name);
    }

    static Path seed(String tag) throws IOException {
        Path dir = Files.createTempDirectory("romlogger-rlc2-" + tag + "-");
        Path source = dir.resolve("source.rlj");
        Files.write(source, new byte[0]);
        JournalIdentityPrototype.open(source.toFile());
        return dir;
    }
    static Publication publish(Env env, Path source, OwnedJournal w, Hook hook) throws IOException {
        return WatermarkV2Prototype.publish(source.toFile(), w, w.writtenLength(), w.writtenHash(), env.platform(), hook);
    }
    static Committed read(Env env, Path source, Marker previous) throws IOException {
        return WatermarkV2Prototype.readCommitted(source.toFile(), env.reader(), previous);
    }
    private static void rewriteFrameCrcValid(Path source, int frameStart, byte newFirstByte) throws IOException {
        byte[] b = Files.readAllBytes(source);
        int length = ByteBuffer.wrap(b).getInt(frameStart + 4);
        b[frameStart + 20] = newFirstByte;
        CRC32 c = new CRC32(); c.update(b, frameStart + 20, length);
        ByteBuffer.wrap(b).putInt(frameStart + 16, (int) c.getValue());
        Files.write(source, b);
    }
    private static Path commitTwo(Env env, String tag) throws IOException {
        Path dir = seed(tag), source = dir.resolve("source.rlj");
        try (OwnedJournal w = env.openOwned(source)) { w.append("E\tsynthetic\tone"); w.append("S\tsynthetic\t1\tTEST_RPM=900"); publish(env, source, w, WatermarkV2Prototype.NO_FAULT); }
        return dir;
    }

    // ---------------------------------------------------------------- child JVMs
    static Process start(Env env, Path dir, String mode, String tag) throws IOException {
        String exe = new File(new File(System.getProperty("java.home"), "bin"),
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").getPath();
        return new ProcessBuilder(exe, "-cp", System.getProperty("java.class.path"), env.childMain(), "child", mode, dir.toString(), tag)
            .redirectErrorStream(true).redirectOutput(dir.resolve(tag + ".log").toFile()).start();
    }
    private static void await(Process p, Path flag) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(flag)) { if (!p.isAlive() || System.nanoTime() > end) throw new AssertionError("child flag missing " + flag); Thread.sleep(10); }
    }
    private static void kill(Process p) throws Exception { if (p != null && p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }

    static void child(Env env, String mode, Path dir, String tag) throws Exception {
        final Path source = dir.resolve("source.rlj");
        if (mode.startsWith("halt:")) {
            final Stage stage = Stage.valueOf(mode.substring(5));
            OwnedJournal w = env.openOwned(source);
            w.append("S\tsynthetic\t2\tTEST_RPM=950");
            publish(env, source, w, new Hook() { public void at(Stage s) {
                System.out.println("OBSERVED " + s);
                if (s == stage) Runtime.getRuntime().halt(73);
            }});
            throw new AssertionError("halt missed");
        } else if (mode.equals("lock")) {
            try (FileChannel c = FileChannel.open(WatermarkV2Prototype.commitLock(source.toFile()), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock l = c.lock()) {
                Files.write(dir.resolve(tag + ".ready"), new byte[0]);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Files.exists(dir.resolve(tag + ".stop"))) { if (System.nanoTime() > end) throw new IOException("deadline"); Thread.sleep(10); }
            }
        } else if (mode.equals("read")) {
            Committed c = read(env, source, null);
            Files.write(dir.resolve(tag + ".out"), ("gen=" + c.marker.generation + " seq=" + c.marker.sequence + " entries=" + c.entries.size()).getBytes(StandardCharsets.UTF_8));
        } else if (mode.equals("readloop")) {
            Marker previous = null; int ok = 0, closed = 0; java.util.Map<String, Integer> reasons = new java.util.TreeMap<String, Integer>();
            Files.write(dir.resolve(tag + ".ready"), new byte[0]);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (true) {
                if (System.nanoTime() > end) throw new IOException("deadline");
                // After the writer has stopped (quiescent), one final read MUST succeed.
                boolean quiescent = Files.exists(dir.resolve(tag + ".stop"));
                try {
                    Committed c = read(env, source, previous);
                    if (c.entries.size() != c.marker.sequence) throw new AssertionError("entries/sequence mismatch");
                    previous = c.marker; ok++;
                    if (quiescent) break;
                } catch (IOException failClosed) {
                    if (quiescent) throw failClosed;
                    closed++; String why = String.valueOf(failClosed.getMessage());
                    Integer n = reasons.get(why); reasons.put(why, n == null ? 1 : n + 1);
                }
            }
            Files.write(dir.resolve(tag + ".out"), ("ok=" + ok + " closed=" + closed + " lastGen=" + (previous == null ? 0 : previous.generation) +
                " reasons=" + reasons).getBytes(StandardCharsets.UTF_8));
        } else throw new IllegalArgumentException(mode);
    }

    // ---------------------------------------------------------------- scenarios
    static int run(final Env env) throws Exception {
        String model = System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if (model != null) check(model.equals(System.getProperty("sun.arch.data.model")), env.name() + " actual data model " + model);

        // A. publish / tail exclusion / generations / idempotence / separate-JVM reader
        final Path dir = seed("basic"); final Path source = dir.resolve("source.rlj");
        byte[] gen1Bytes; Marker gen1, gen2;
        try (OwnedJournal w = env.openOwned(source)) {
            w.append("E\tsynthetic\tone"); w.append("S\tsynthetic\t1\tTEST_RPM=900");
            Publication p = publish(env, source, w, WatermarkV2Prototype.NO_FAULT);
            check(p.marker.generation == 1 && p.marker.sequence == 2 && p.marker.identity.tag == env.tag(), "publish gen1 seq2 with " + env.tag());
            check(p.directory == env.expectedDirectory() && !p.powerLossDurable(), "directory durability reported " + p.directory + ", never power-loss durable");
            gen1 = p.marker; gen1Bytes = Files.readAllBytes(WatermarkV2Prototype.marker(source.toFile()));
            Committed c = read(env, source, null);
            check(c.entries.size() == 2 && c.marker.generation == 1, "reader returns validated committed prefix");
            w.append("S\tsynthetic\t2\tTEST_RPM=910");
            check(read(env, source, gen1).entries.size() == 2, "unpublished tail beyond watermark is excluded");
            Publication p2 = publish(env, source, w, WatermarkV2Prototype.NO_FAULT); gen2 = p2.marker;
            check(gen2.generation == 2 && gen2.sequence == 3 && read(env, source, gen1).entries.size() == 3, "gen2 advances by one and reader accepts monotonic step");
            Publication again = publish(env, source, w, WatermarkV2Prototype.NO_FAULT);
            check(again.alreadyCommitted && again.marker.generation == 2, "republish without new frames is idempotent (no new generation)");
            Process r = start(env, dir, "read", "reader");
            check(r.waitFor(20, TimeUnit.SECONDS) && r.exitValue() == 0, "separate JVM reader exits cleanly while writer holds owned handle");
            check(new String(Files.readAllBytes(dir.resolve("reader.out")), StandardCharsets.UTF_8).equals("gen=2 seq=3 entries=3"), "separate JVM reader sees exactly gen2 committed prefix");
        }

        // B. stale marker / rollback
        final byte[] gen2Bytes = Files.readAllBytes(WatermarkV2Prototype.marker(source.toFile()));
        Files.write(WatermarkV2Prototype.marker(source.toFile()), gen1Bytes);
        final Marker seen = gen2;
        rejects("rolled-back marker rejected against previously observed gen2", new Action() { public void run() throws Exception { read(env, source, seen); } });
        Committed stale = read(env, source, null);
        check(stale.marker.generation == 1 && stale.entries.size() == 2,
            "COUNTEREXAMPLE fresh reader without persistent highwater accepts older safe prefix (3c ledger required)");
        Files.write(WatermarkV2Prototype.marker(source.toFile()), gen2Bytes);

        // C. CRC-valid modification of committed data
        final Path cdir = commitTwo(env, "crc-valid"); final Path csrc = cdir.resolve("source.rlj");
        rewriteFrameCrcValid(csrc, 0, (byte) 'X');
        final byte[] tampered = Files.readAllBytes(csrc);
        check(WatermarkV2Prototype.scanSequence(tampered, null) == 2, "tampered journal still has valid RLJ1 CRC frames");
        rejects("reader rejects CRC-valid committed rewrite via prefix SHA-256", new Action() { public void run() throws Exception { read(env, csrc, null); } });
        try (final OwnedJournal w = env.openOwned(csrc)) {
            w.append("S\tsynthetic\t3\tTEST_RPM=920");
            rejects("writer refuses to publish over a modified committed prefix", new Action() { public void run() throws Exception { publish(env, csrc, w, WatermarkV2Prototype.NO_FAULT); } });
        }
        check(Arrays.equals(Arrays.copyOf(Files.readAllBytes(csrc), tampered.length), tampered) &&
            WatermarkV2Prototype.decode(Files.readAllBytes(WatermarkV2Prototype.marker(csrc.toFile()))).generation == 1, "tampered evidence and gen1 marker retained");

        // D. post-write rewrite before publish (writer memory record)
        final Path ddir = seed("post-write"); final Path dsrc = ddir.resolve("source.rlj");
        try (final OwnedJournal w = env.openOwned(dsrc)) {
            w.append("E\tsynthetic\tone");
            rewriteFrameCrcValid(dsrc, 0, (byte) 'Z');
            rejects("publish rejects CRC-valid rewrite of bytes the writer wrote", new Action() { public void run() throws Exception { publish(env, dsrc, w, WatermarkV2Prototype.NO_FAULT); } });
        }
        check(!Files.exists(WatermarkV2Prototype.marker(dsrc.toFile())) && !Files.exists(WatermarkV2Prototype.staged(dsrc.toFile())), "no marker or staged file fabricated after rejected publish");

        // E. same-content replacement (closed) and rename-over while owned
        final Path edir = commitTwo(env, "replace"); final Path esrc = edir.resolve("source.rlj");
        Path copy = edir.resolve("copy.tmp"); Files.copy(esrc, copy);
        Files.move(copy, esrc, StandardCopyOption.REPLACE_EXISTING);
        rejects("reader rejects byte-identical replacement (identity differs)", new Action() { public void run() throws Exception { read(env, esrc, null); } });
        try (final OwnedJournal w = env.openOwned(esrc)) {
            w.append("S\tsynthetic\t3\tTEST_RPM=930");
            rejects("writer rejects publish onto replaced source identity", new Action() { public void run() throws Exception { publish(env, esrc, w, WatermarkV2Prototype.NO_FAULT); } });
        }
        final Path hdir = commitTwo(env, "held-rename"); final Path hsrc = hdir.resolve("source.rlj");
        Path c2 = hdir.resolve("copy.tmp"); Files.copy(hsrc, c2); // copy before ownership (copy needs read sharing)
        try (final OwnedJournal w = env.openOwned(hsrc)) {
            boolean renamed;
            try { Files.move(c2, hsrc, StandardCopyOption.REPLACE_EXISTING); renamed = true; }
            catch (java.nio.file.FileSystemException denied) { renamed = false; }
            if (env.heldNamesArePinned()) check(!renamed, "owned source name is pinned: rename-over denied while held");
            else {
                check(renamed, "DIFFERENCE POSIX allows rename-over of an owned source");
                w.append("S\tsynthetic\t3\tTEST_RPM=940");
                rejects("POSIX writer detects rename-over at publish (identity/size disagreement)", new Action() { public void run() throws Exception { publish(env, hsrc, w, WatermarkV2Prototype.NO_FAULT); } });
            }
        }

        // F. whole-set copy and UUID collision
        final Path fdir = Files.createTempDirectory("romlogger-rlc2-copy-"); final Path fsrc = fdir.resolve("source.rlj");
        for (String n : new String[]{"source.rlj", "source.rlj.identity", "source.rlj.commit2"}) Files.copy(dir.resolve(n), fdir.resolve(n));
        rejects("whole-set copy (source+identity+marker) rejected by identity", new Action() { public void run() throws Exception { read(env, fsrc, null); } });
        rejects("copy read with previously observed snapshot rejected", new Action() { public void run() throws Exception { read(env, fsrc, seen); } });
        final Path udir = commitTwo(env, "uuid-collision"); final Path usrc = udir.resolve("source.rlj");
        Files.copy(dir.resolve("source.rlj.identity"), udir.resolve("source.rlj.identity"), StandardCopyOption.REPLACE_EXISTING);
        rejects("copied UUID sidecar over a different source rejected", new Action() { public void run() throws Exception { read(env, usrc, null); } });

        // G. marker damage and forgery
        final Path mpath = WatermarkV2Prototype.marker(source.toFile()); final byte[] good = Files.readAllBytes(mpath);
        Files.write(mpath, Arrays.copyOf(good, 70));
        rejects("partial marker rejected", new Action() { public void run() throws Exception { read(env, source, null); } });
        byte[] flipped = good.clone(); flipped[60] ^= 1; Files.write(mpath, flipped);
        rejects("bit-flipped marker rejected by digest", new Action() { public void run() throws Exception { read(env, source, null); } });
        Marker g = WatermarkV2Prototype.decode(good);
        Files.write(mpath, WatermarkV2Prototype.encode(new Marker(g.identity, g.sourceUuid, g.generation, g.sequence, g.offset - 1, g.prefixHash())));
        rejects("re-digested marker with moved offset rejected by prefix hash", new Action() { public void run() throws Exception { read(env, source, null); } });
        Files.write(mpath, WatermarkV2Prototype.encode(new Marker(g.identity, g.sourceUuid, g.generation, g.sequence - 1, g.offset, g.prefixHash())));
        rejects("re-digested marker with wrong sequence rejected by frame scan", new Action() { public void run() throws Exception { read(env, source, null); } });
        byte[] v3 = good.clone(); v3[5] = 3; redigest(v3); Files.write(mpath, v3);
        rejects("unknown RLC2 version rejected", new Action() { public void run() throws Exception { read(env, source, null); } });
        byte[] tag = good.clone(); tag[6] = (byte) (env.tag() == WatermarkV2Prototype.IdentityTag.POSIX_DEV_INODE ? 2 : 1); redigest(tag); Files.write(mpath, tag);
        rejects("identity tag swapped without matching length rejected", new Action() { public void run() throws Exception { read(env, source, null); } });
        byte[] pad = good.clone(); pad[8 + 31] = 1; redigest(pad); Files.write(mpath, pad);
        rejects("nonzero identity padding rejected", new Action() { public void run() throws Exception { read(env, source, null); } });
        Files.write(mpath, good);
        // Forgery: re-point the copied marker at the copy's identity and recompute the digest.
        try (WatermarkV2Prototype.ReadHandle h = env.reader().open(fsrc)) {
            Path fm = WatermarkV2Prototype.marker(fsrc.toFile()); Marker cm = WatermarkV2Prototype.decode(Files.readAllBytes(fm));
            Files.write(fm, WatermarkV2Prototype.encode(new Marker(h.identity(), cm.sourceUuid, cm.generation, cm.sequence, cm.offset, cm.prefixHash())));
        }
        check(read(env, fsrc, null).entries.size() == 3,
            "COUNTEREXAMPLE forged marker with recomputed digest accepted (SHA-256 is integrity, not authentication)");

        // H. legacy RLC1 never reinterpreted
        final Path ldir = seed("legacy"); final Path lsrc = ldir.resolve("source.rlj");
        byte[] rlc1 = ByteBuffer.allocate(104).putInt(WatermarkV2Prototype.LEGACY_RLC1_MAGIC).array();
        Files.write(WatermarkV2Prototype.legacyMarker(lsrc.toFile()), rlc1);
        try (final OwnedJournal w = env.openOwned(lsrc)) {
            w.append("E\tsynthetic\tone");
            try { publish(env, lsrc, w, WatermarkV2Prototype.NO_FAULT); throw new AssertionError("RLC1 coexistence accepted"); }
            catch (WatermarkV2Prototype.LegacyMarkerException legacy) { check(true, "publish refuses while legacy RLC1 marker exists"); }
        }
        check(Arrays.equals(rlc1, Files.readAllBytes(WatermarkV2Prototype.legacyMarker(lsrc.toFile()))) && !Files.exists(WatermarkV2Prototype.marker(lsrc.toFile())),
            "legacy RLC1 bytes retained, no RLC2 created");
        Files.write(WatermarkV2Prototype.marker(lsrc.toFile()), rlc1);
        try { read(env, lsrc, null); throw new AssertionError("RLC1 bytes decoded as RLC2"); }
        catch (WatermarkV2Prototype.LegacyMarkerException legacy) { check(true, "reader refuses RLC1 bytes in RLC2 slot (never reinterpreted)"); }

        // I. staged residue
        Files.write(WatermarkV2Prototype.staged(source.toFile()), new byte[]{1, 2, 3});
        rejects("reader refuses while staged marker remains", new Action() { public void run() throws Exception { read(env, source, null); } });
        Files.delete(WatermarkV2Prototype.staged(source.toFile())); // test-owned synthetic residue only

        // J. 8MiB quota
        final Path qdir = seed("quota"); final Path qsrc = qdir.resolve("source.rlj");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(qsrc.toFile(), "rw")) { f.setLength(IngressJournalPrototype.MAX_JOURNAL_BYTES + 1); }
        rejects("owned writer refuses an existing source over 8MiB", new Action() { public void run() throws Exception { env.openOwned(qsrc).close(); } });
        check(qsrc.toFile().length() == IngressJournalPrototype.MAX_JOURNAL_BYTES + 1, "over-quota source retained untouched");

        // K. in-process fault at every stage
        for (final Stage stage : Stage.values()) {
            final Path kdir = commitTwo(env, "fault-" + stage); final Path ksrc = kdir.resolve("source.rlj");
            try (final OwnedJournal w = env.openOwned(ksrc)) {
                w.append("S\tsynthetic\t3\tTEST_RPM=960");
                try { publish(env, ksrc, w, new Hook() { public void at(Stage s) throws IOException { if (s == stage) throw new IOException("injected " + s); } }); throw new AssertionError("hook missed"); }
                catch (IOException injected) { /* expected */ }
                verifyInterrupted(env, ksrc, stage, "fault " + stage, w);
            }
        }

        // L. separate-JVM halt at every stage (NOT physical power loss)
        for (Stage stage : Stage.values()) {
            Path ldir2 = commitTwo(env, "halt-" + stage); Path lsrc2 = ldir2.resolve("source.rlj");
            Process p = start(env, ldir2, "halt:" + stage, "halt");
            try { check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 73, "separate JVM halted at " + stage); } finally { kill(p); }
            verifyInterrupted(env, lsrc2, stage, "halt " + stage, null);
        }
        System.out.println("NOTE Runtime.halt terminates the JVM only; OS caches survive. This is NOT physical power loss.");

        // M. competing publisher
        final Path mdir = commitTwo(env, "contention"); final Path msrc = mdir.resolve("source.rlj");
        Process holder = start(env, mdir, "lock", "holder");
        try {
            await(holder, mdir.resolve("holder.ready"));
            try (final OwnedJournal w = env.openOwned(msrc)) {
                w.append("S\tsynthetic\t3\tTEST_RPM=970");
                rejects("second publisher refused while another JVM holds the commit lock", new Action() { public void run() throws Exception { publish(env, msrc, w, WatermarkV2Prototype.NO_FAULT); } });
                Files.write(mdir.resolve("holder.stop"), new byte[0]);
                check(holder.waitFor(20, TimeUnit.SECONDS) && holder.exitValue() == 0, "commit lock holder exits");
                check(publish(env, msrc, w, WatermarkV2Prototype.NO_FAULT).marker.generation == 2, "publish succeeds after lock release");
            }
        } finally { kill(holder); }

        // N. concurrent reader in another JVM while publishing many generations
        final Path ndir = commitTwo(env, "concurrent"); final Path nsrc = ndir.resolve("source.rlj");
        Process loop = start(env, ndir, "readloop", "loop");
        try {
            await(loop, ndir.resolve("loop.ready"));
            try (OwnedJournal w = env.openOwned(nsrc)) {
                for (int i = 0; i < 25; i++) { w.append("S\tsynthetic\t" + (3 + i) + "\tTEST_RPM=" + (1000 + i)); publish(env, nsrc, w, WatermarkV2Prototype.NO_FAULT); }
            }
            Files.write(ndir.resolve("loop.stop"), new byte[0]);
            check(loop.waitFor(30, TimeUnit.SECONDS) && loop.exitValue() == 0, "concurrent reader JVM never observed an inconsistent or regressed prefix");
            String out = new String(Files.readAllBytes(ndir.resolve("loop.out")), StandardCharsets.UTF_8);
            System.out.println("OBSERVED concurrent reader " + out);
            check(out.contains(" lastGen=26 "), "concurrent reader's quiescent final read returns the last generation (26)");
        } finally { kill(loop); }

        // O. replace while the marker is held open by a reader without delete sharing
        final Path odir = commitTwo(env, "held-marker"); final Path osrc = odir.resolve("source.rlj");
        final Path omarker = WatermarkV2Prototype.marker(osrc.toFile()); final byte[] before = Files.readAllBytes(omarker);
        try (final OwnedJournal w = env.openOwned(osrc)) {
            w.append("S\tsynthetic\t3\tTEST_RPM=980");
            boolean published;
            try (FileInputStream holderStream = new FileInputStream(omarker.toFile())) {
                try { publish(env, osrc, w, WatermarkV2Prototype.NO_FAULT); published = true; }
                catch (IOException denied) { published = false; }
            }
            if (env.heldNamesArePinned()) {
                check(!published && Arrays.equals(before, Files.readAllBytes(omarker)) && Files.exists(WatermarkV2Prototype.staged(osrc.toFile())),
                    "NTFS replace denied while marker held: old marker intact, staged file retained");
                rejects("retry refuses until staged residue is reviewed", new Action() { public void run() throws Exception { publish(env, osrc, w, WatermarkV2Prototype.NO_FAULT); } });
            } else check(published, "DIFFERENCE POSIX replaces a marker that another descriptor holds open");
        }
        System.out.println(env.name() + " RLC2 scenarios passed " + checks + " checks; directory=" + env.expectedDirectory() +
            "; JVM halt != power loss; no DB/ACK/product wiring");
        return checks;
    }

    private static void redigest(byte[] raw) {
        MessageDigest d = WatermarkV2Prototype.sha(); d.update(raw, 0, raw.length - 32);
        System.arraycopy(d.digest(), 0, raw, raw.length - 32, 32);
    }

    /** Outcome after a failure/halt while publishing gen2 (seq3) over committed gen1 (seq2). */
    private static void verifyInterrupted(final Env env, final Path src, Stage stage, String label, final OwnedJournal live) throws Exception {
        boolean staged = stage == Stage.TEMP_CREATED || stage == Stage.TEMP_WRITTEN || stage == Stage.TEMP_SYNCED;
        boolean replaced = stage == Stage.REPLACED || stage == Stage.DIRECTORY_CHECKED;
        check(WatermarkV2Scenarios.frames(src) == 3, label + ": appended frame retained in source");
        if (staged) {
            check(Files.exists(WatermarkV2Prototype.staged(src.toFile())), label + ": staged marker retained for review");
            rejects(label + ": reader fails closed on staged residue", new Action() { public void run() throws Exception { read(env, src, null); } });
            check(WatermarkV2Prototype.decode(Files.readAllBytes(WatermarkV2Prototype.marker(src.toFile()))).generation == 1, label + ": committed gen1 marker untouched");
        } else if (replaced) {
            Committed c = read(env, src, null);
            check(c.marker.generation == 2 && c.entries.size() == 3, label + ": replaced gen2 visible (ambiguous outcome for caller)");
            if (live != null) check(publish(env, src, live, WatermarkV2Prototype.NO_FAULT).alreadyCommitted, label + ": retry recognises already committed gen2");
        } else {
            Committed c = read(env, src, null);
            check(c.marker.generation == 1 && c.entries.size() == 2, label + ": reader still returns only gen1 prefix");
            if (live != null) check(publish(env, src, live, WatermarkV2Prototype.NO_FAULT).marker.generation == 2, label + ": retry publishes gen2 once");
        }
    }
    static long frames(Path source) throws IOException { return WatermarkV2Prototype.scanSequence(Files.readAllBytes(source), null); }
    private WatermarkV2Scenarios() {}
}
