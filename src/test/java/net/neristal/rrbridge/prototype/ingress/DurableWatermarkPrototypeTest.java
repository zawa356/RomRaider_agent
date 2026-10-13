package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Synthetic update failures, retained evidence and independent JVM halts. */
public final class DurableWatermarkPrototypeTest {
    private static int checks;
    interface Action { void run() throws Exception; }
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++; System.out.println("PASS " + what);
    }
    private static void denied(Action action, String what) throws Exception {
        try { action.run(); throw new AssertionError(what); }
        catch (IOException expected) { check(true, what); }
    }
    private static File fixture() throws Exception {
        File source = Files.createTempDirectory("romlogger-watermark-").resolve("synthetic.rlj").toFile();
        try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(source, 4096)) {
            writer.append("M\tsynthetic\t1\tP8\tRPM\trpm");
            writer.append("S\tsynthetic\t1\t1\t0\tP8=1000");
            DurableWatermarkPrototype.DirectorySync result = writer.checkpoint();
            System.out.println("Directory force observation: " + result + " on " + System.getProperty("os.name"));
        }
        return source;
    }
    private static Path marker(File source) { return new File(source.getPath() + ".commit").toPath(); }
    private static Path temp(File source) { return new File(source.getPath() + ".commit.tmp").toPath(); }
    private static void crc(byte[] bytes) {
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) crc.getValue());
    }
    private static void invalidMarker(final File source, byte[] valid, int offset, long value, String what) throws Exception {
        byte[] bad = valid.clone(); ByteBuffer.wrap(bad).putLong(offset, value); crc(bad); Files.write(marker(source), bad);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, what);
        Files.write(marker(source), valid);
    }
    private static void crash(DurableWatermarkPrototype.Stage stage) throws Exception {
        final File source = fixture();
        byte[] oldMarker = Files.readAllBytes(marker(source));
        String java = new File(new File(System.getProperty("java.home"), "bin"),
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            DurableWatermarkPrototypeTest.class.getName(), "crash", source.getPath(), stage.name())
            .redirectErrorStream(true).redirectOutput(new File(source.getPath() + ".crash.log")).start();
        try {
            check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 81 + stage.ordinal(), "separate JVM halts at " + stage);
        } finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
        if (stage == DurableWatermarkPrototype.Stage.JOURNAL_SYNCED) {
            check(Arrays.equals(oldMarker, Files.readAllBytes(marker(source))), "journal-only crash retains old watermark");
            check(DurableWatermarkPrototype.readCommitted(source, null).sequence == 2,
                "fully fsynced third frame excluded without watermark");
        } else if (stage == DurableWatermarkPrototype.Stage.TEMP_SYNCED) {
            check(Files.exists(temp(source)) && Arrays.equals(oldMarker, Files.readAllBytes(marker(source))),
                "staged crash retains temp plus old watermark");
            denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
                "staged update fails closed and is never auto-promoted");
        } else {
            check(!Files.exists(temp(source)), "atomic rename consumed temp");
            check(DurableWatermarkPrototype.readCommitted(source, null).sequence == 3,
                "post-rename process crash exposes validated synced prefix only");
        }
        check(source.isFile() && source.length() > 0, "crash retains entire source for review");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            if (args[0].equals("read")) {
                DurableWatermarkPrototype.Snapshot s = DurableWatermarkPrototype.readCommitted(new File(args[1]), null);
                if (s.sequence != Long.parseLong(args[2]) || s.entries.size() != s.sequence)
                    throw new AssertionError("live committed prefix mismatch");
                return;
            }
            if (args[0].equals("old-attempt")) {
                try (IngressJournalPrototype ignored = new IngressJournalPrototype(new File(args[1]), 4096)) {
                    throw new AssertionError("checkpoint released guard");
                } catch (IOException expected) { return; }
            }
            final DurableWatermarkPrototype.Stage crashStage = DurableWatermarkPrototype.Stage.valueOf(args[2]);
            try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(new File(args[1]), 4096)) {
                writer.append("E\tsynthetic\tcrash-boundary");
                writer.checkpoint(new DurableWatermarkPrototype.Hook() {
                    public void at(DurableWatermarkPrototype.Stage stage) { if (stage == crashStage) Runtime.getRuntime().halt(81 + stage.ordinal()); }
                });
            }
            throw new AssertionError("crash hook not reached");
        }
        final File source = fixture();
        byte[] valid = Files.readAllBytes(marker(source));
        final DurableWatermarkPrototype.Snapshot first = DurableWatermarkPrototype.readCommitted(source, null);
        check(valid.length == 104 && first.sequence == 2 && first.entries.size() == 2, "versioned marker binds two committed frames");
        check(first.offset == source.length(), "offset is exact complete frame boundary");
        check(DurableWatermarkPrototype.readCommitted(source, first).sequence == 2, "same watermark is idempotent");
        try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(source, 4096)) { writer.append("E\tsynthetic\tunpublished"); }
        check(DurableWatermarkPrototype.readCommitted(source, first).entries.size() == 2, "visible complete tail excluded until checkpoint");
        try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(source, 4096)) { writer.checkpoint(); }
        final DurableWatermarkPrototype.Snapshot third = DurableWatermarkPrototype.readCommitted(source, first);
        byte[] thirdMarker = Files.readAllBytes(marker(source));
        check(third.sequence == 3 && third.entries.size() == 3, "next checkpoint monotonically commits tail");
        Files.write(marker(source), valid);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, third); } }, "remembered sequence regression refused");
        check(DurableWatermarkPrototype.readCommitted(source, null).sequence == 2,
            "fresh reader accepts safe stale marker only (no persistent highwater claim)");
        Files.write(marker(source), thirdMarker);
        invalidMarker(source, thirdMarker, 20, -1, "negative sequence refused");
        invalidMarker(source, thirdMarker, 20, 4, "sequence ahead of prefix refused");
        invalidMarker(source, thirdMarker, 28, source.length() + 1, "offset ahead of source refused");
        invalidMarker(source, thirdMarker, 28, IngressJournalPrototype.MAX_JOURNAL_BYTES + 1, "oversized offset refused before allocation");
        invalidMarker(source, thirdMarker, 28, source.length() - 1, "offset cutting frame refused");
        invalidMarker(source, thirdMarker, 4, 123, "UUID mismatch refused even with valid CRC");
        byte[] badCrc = thirdMarker.clone(); badCrc[40] ^= 1; Files.write(marker(source), badCrc);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "marker CRC corruption refused");
        byte[] badVersion = thirdMarker.clone(); badVersion[3] ^= 1; crc(badVersion); Files.write(marker(source), badVersion);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "unsupported marker version refused");
        Files.write(marker(source), Arrays.copyOf(thirdMarker, 50));
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "non-atomic partial marker refused");
        Files.delete(marker(source));
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "missing marker refused without synthesis");
        check(!Files.exists(marker(source)), "reader never creates missing marker");
        Files.write(marker(source), thirdMarker);
        byte[] original = Files.readAllBytes(source.toPath());
        try (RandomAccessFile damaged = new RandomAccessFile(source, "rw")) { damaged.setLength(first.offset - 1); }
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "source truncation refused");
        Files.write(source.toPath(), original);
        byte[] modified = original.clone(); modified[20] ^= 1; Files.write(source.toPath(), modified);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "committed prefix modification refused");
        check(Arrays.equals(modified, Files.readAllBytes(source.toPath())), "reader preserves corrupt evidence");
        Files.write(source.toPath(), original);
        byte[] badFrame = original.clone(); badFrame[20] ^= 1;
        byte[] matchingMarker = thirdMarker.clone();
        System.arraycopy(java.security.MessageDigest.getInstance("SHA-256").digest(badFrame), 0, matchingMarker, 68, 32);
        crc(matchingMarker); Files.write(source.toPath(), badFrame); Files.write(marker(source), matchingMarker);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
            "frame CRC refused even when marker prefix SHA matches");
        badFrame = original.clone(); ByteBuffer.wrap(badFrame).putLong(8, 9);
        matchingMarker = thirdMarker.clone();
        System.arraycopy(java.security.MessageDigest.getInstance("SHA-256").digest(badFrame), 0, matchingMarker, 68, 32);
        crc(matchingMarker); Files.write(source.toPath(), badFrame); Files.write(marker(source), matchingMarker);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
            "frame sequence gap refused even with matching marker SHA");
        badFrame = original.clone(); badFrame[20] = (byte) 0xff;
        CRC32 frameCrc = new CRC32(); frameCrc.update(badFrame, 20, ByteBuffer.wrap(badFrame).getInt(4));
        ByteBuffer.wrap(badFrame).putInt(16, (int) frameCrc.getValue());
        matchingMarker = thirdMarker.clone();
        System.arraycopy(java.security.MessageDigest.getInstance("SHA-256").digest(badFrame), 0, matchingMarker, 68, 32);
        crc(matchingMarker); Files.write(source.toPath(), badFrame); Files.write(marker(source), matchingMarker);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
            "invalid UTF8 refused even with matching frame CRC and marker SHA");
        Files.write(source.toPath(), original); Files.write(marker(source), thirdMarker);
        Path identity = new File(source.getPath() + ".identity").toPath();
        byte[] validIdentity = Files.readAllBytes(identity); Files.delete(identity);
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
            "missing source identity refused without reissue");
        check(!Files.exists(identity), "reader does not recreate missing identity");
        Files.write(identity, validIdentity);
        Files.write(temp(source), new byte[]{1, 2});
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } }, "interrupted staging refuses read");
        try (final SplitLockJournalPrototype writer = new SplitLockJournalPrototype(source, 4096)) {
            denied(new Action() { public void run() throws Exception { writer.checkpoint(); } }, "writer never deletes or replaces old staging");
            denied(new Action() { public void run() throws Exception { writer.append("x"); } }, "checkpoint failure latches further appends");
        }
        check(Files.size(temp(source)) == 2 && Arrays.equals(thirdMarker, Files.readAllBytes(marker(source))), "staging failure retains both files");
        Files.delete(temp(source)); // fixture-only operator reset; implementation never deletes it
        Path replacement = source.toPath().resolveSibling("replacement.rlj"); Files.write(replacement, original);
        Files.move(source.toPath(), source.toPath().resolveSibling("preserved-original.rlj"));
        Files.move(replacement, source.toPath());
        denied(new Action() { public void run() throws Exception { DurableWatermarkPrototype.readCommitted(source, null); } },
            "byte-identical source replacement refused by file identity");
        for (final DurableWatermarkPrototype.Stage stage : DurableWatermarkPrototype.Stage.values()) {
            final File faultSource = fixture();
            try (final SplitLockJournalPrototype writer = new SplitLockJournalPrototype(faultSource, 4096)) {
                writer.append("E\tsynthetic\tinjected-quota-or-io");
                denied(new Action() { public void run() throws Exception {
                    writer.checkpoint(new DurableWatermarkPrototype.Hook() {
                        public void at(DurableWatermarkPrototype.Stage observed) throws IOException {
                            if (observed == stage) throw new IOException("synthetic storage failure");
                        }
                    });
                } }, "injected storage failure visible at " + stage);
                denied(new Action() { public void run() throws Exception { writer.append("x"); } }, "fault at " + stage + " retains stopped writer");
            }
            check(faultSource.isFile(), "storage failure retains original");
            crash(stage);
        }
        final File liveSource = fixture();
        try (SplitLockJournalPrototype live = new SplitLockJournalPrototype(liveSource, 4096)) {
            live.checkpoint();
            childCheck("read", liveSource, "2", "different JVM reads while split writer holds lock");
            live.append("E\tsynthetic\tlive-unpublished");
            childCheck("read", liveSource, "2", "live reader excludes complete unpublished frame");
            live.checkpoint();
            childCheck("read", liveSource, "3", "live reader sees only checkpointed advancement");
            childCheck("old-attempt", liveSource, "unused", "watermark publication retains compatibility guard");
        }
        System.out.println("DurableWatermarkPrototypeTest passed " + checks + " checks (synthetic only; NO physical power-loss guarantee)");
    }
    private static void childCheck(String mode, File source, String value, String name) throws Exception {
        String java = new File(new File(System.getProperty("java.home"), "bin"),
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            DurableWatermarkPrototypeTest.class.getName(), mode, source.getPath(), value)
            .redirectErrorStream(true).redirectOutput(new File(source.getPath() + "." + mode + ".log")).start();
        try { check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, name); }
        finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
    }
}
