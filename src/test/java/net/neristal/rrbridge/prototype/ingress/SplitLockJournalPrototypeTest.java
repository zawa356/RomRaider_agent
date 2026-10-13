package net.neristal.rrbridge.prototype.ingress;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Different JVM ownership matrix, isolated TEMP files only. */
public final class SplitLockJournalPrototypeTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static Process start(Path dir, String mode, String tag) throws IOException {
        String executable = new File(new File(System.getProperty("java.home"), "bin"),
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
        return new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
            SplitLockJournalPrototypeTest.class.getName(), mode, dir.toString(), tag)
            .redirectErrorStream(true).redirectOutput(dir.resolve(tag + ".log").toFile()).start();
    }
    private static void await(Process process, Path flag) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(flag)) {
            if (!process.isAlive() || System.nanoTime() > deadline)
                throw new AssertionError("child did not publish readiness: " + flag);
            Thread.sleep(10);
        }
    }
    private static void finish(Process process, Path dir, String tag) throws Exception {
        Files.write(dir.resolve(tag + ".stop"), new byte[0]);
        if (!process.waitFor(15, TimeUnit.SECONDS)) throw new AssertionError("child hung");
        check(process.exitValue() == 0, "child exits normally: " + tag);
    }
    private static void kill(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS);
        }
    }
    private static void child(String mode, Path dir, String tag) throws Exception {
        File source = dir.resolve("source.rlj").toFile();
        Closeable handle = null;
        try {
            if (mode.equals("old")) {
                IngressJournalPrototype writer = new IngressJournalPrototype(source, IngressJournalPrototype.MAX_JOURNAL_BYTES);
                handle = writer;
                JournalIdentityPrototype.open(source);
                if (writer.replay().isEmpty()) writer.append("E\tsynthetic\told");
            } else if (mode.equals("split")) {
                SplitLockJournalPrototype writer = new SplitLockJournalPrototype(source, IngressJournalPrototype.MAX_JOURNAL_BYTES);
                handle = writer;
                if (source.length() == 0) writer.append("E\tsynthetic\tsplit");
            } else if (mode.equals("naive")) {
                final RandomAccessFile file = new RandomAccessFile(source.getPath() + ".writer.lock", "rw");
                final FileLock lock = file.getChannel().tryLock();
                if (lock == null) { file.close(); throw new IOException("locked"); }
                handle = new Closeable() { public void close() throws IOException { try { lock.release(); } finally { file.close(); } } };
            } else throw new IllegalArgumentException("unknown child mode");
        } catch (IOException denied) {
            Files.write(dir.resolve(tag + ".denied"), new byte[0]); return;
        }
        try {
            Files.write(dir.resolve(tag + ".ready"), new byte[0]);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!Files.exists(dir.resolve(tag + ".stop"))) {
                if (System.nanoTime() > deadline) throw new IOException("test owner deadline");
                Thread.sleep(10);
            }
        } finally { handle.close(); }
    }
    private static void matrix(String ownerMode, String challengerMode, boolean denied) throws Exception {
        Path dir = Files.createTempDirectory("romlogger-lock-matrix-");
        try (IngressJournalPrototype seed = new IngressJournalPrototype(dir.resolve("source.rlj").toFile(), 1024)) {
            JournalIdentityPrototype.open(dir.resolve("source.rlj").toFile());
            seed.append("E\tsynthetic\tseed");
        }
        byte[] before = Files.readAllBytes(dir.resolve("source.rlj"));
        Process owner = null, contender = null;
        try {
            owner = start(dir, ownerMode, "owner"); await(owner, dir.resolve("owner.ready"));
            contender = start(dir, challengerMode, "challenger");
            await(contender, dir.resolve(denied ? "challenger.denied" : "challenger.ready"));
            check(denied == Files.exists(dir.resolve("challenger.denied")),
                ownerMode + " -> " + challengerMode + (denied ? " excludes second JVM" : " COUNTEREXAMPLE double ownership"));
            if (denied) {
                check(contender.waitFor(10, TimeUnit.SECONDS) && contender.exitValue() == 0, "rejection finishes cleanly");
            } else finish(contender, dir, "challenger");
            if (ownerMode.equals("split")) {
                LiveJournalReadProbe.Result probe = LiveJournalReadProbe.inspect(dir.resolve("source.rlj").toFile());
                check(probe.status == LiveJournalReadProbe.Status.COMPLETE && probe.frames == 1,
                    "separate JVM live READ succeeds below compatibility guard");
                check(dir.resolve("source.rlj").toFile().length() < SplitLockJournalPrototype.GUARD_OFFSET,
                    "locking beyond EOF never extends original");
            }
            finish(owner, dir, "owner");
            check(Arrays.equals(before, Files.readAllBytes(dir.resolve("source.rlj"))), "ownership trials leave original unchanged");
            check(LiveJournalReadProbe.inspect(dir.resolve("source.rlj").toFile()).frames == 1,
                "original readable after owners close");
            try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(dir.resolve("source.rlj").toFile(), IngressJournalPrototype.MAX_JOURNAL_BYTES)) {
                check(writer.append("E\tsynthetic\trestart") == 2, "new writer reopens retained original and sequence");
            }
        } finally { kill(contender); kill(owner); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3) { child(args[0], new File(args[1]).toPath(), args[2]); return; }
        matrix("old", "old", true);
        matrix("old", "split", true);
        matrix("split", "old", true);
        matrix("split", "split", true);
        // Pure separate-file locking demonstrably fails migration in BOTH orders.
        matrix("old", "naive", false);
        // Naive mode does not create data. Seed through the old writer first.
        Path dir = Files.createTempDirectory("romlogger-naive-first-");
        File source = dir.resolve("source.rlj").toFile();
        try (IngressJournalPrototype w = new IngressJournalPrototype(source, 1024)) {
            JournalIdentityPrototype.open(source); w.append("E\tsynthetic\tseed");
        }
        Process naive = null, old = null;
        try {
            naive = start(dir, "naive", "naive"); await(naive, dir.resolve("naive.ready"));
            old = start(dir, "old", "old"); await(old, dir.resolve("old.ready"));
            check(true, "naive -> old COUNTEREXAMPLE double ownership");
            finish(old, dir, "old"); finish(naive, dir, "naive");
        } finally { kill(old); kill(naive); }
        Path quotaDir = Files.createTempDirectory("romlogger-guard-quota-");
        File limited = quotaDir.resolve("quota.rlj").toFile();
        try (SplitLockJournalPrototype writer = new SplitLockJournalPrototype(limited, 21)) {
            writer.append("x");
            try { writer.append("y"); throw new AssertionError("quota accepted"); }
            catch (IngressJournalPrototype.QuotaExceededException expected) { check(limited.length() == 21, "quota refuses guard overlap without deleting original"); }
        }
        check(LiveJournalReadProbe.inspect(limited).frames == 1, "quota source retains committed frame");
        System.out.println("SplitLockJournalPrototypeTest passed " + checks + " checks (cross-JVM synthetic only)");
    }
}
