package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Same native READ handle provides both identity and bytes. NOT_COMMITTED diagnostic only. */
public final class WindowsBoundReadTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static byte[] hash(byte[] data) throws Exception { return MessageDigest.getInstance("SHA-256").digest(data); }
    private static String at(Path path) throws Exception {
        try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(path)) { return h.identity(); }
    }
    private static void denied(Path path, String expected, int length, byte[] hash, String name) throws Exception {
        try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(path, true)) {
            try { h.readPrefix(expected, length, hash); throw new AssertionError("accepted " + name); }
            catch (IOException valid) { check(true, name); }
        }
    }
    private static void lockedReader(String mode, boolean blocked) throws Exception {
        Path dir = Files.createTempDirectory("romlogger-native-locked-reader-");
        Path source = dir.resolve("source.rlj");
        try (IngressJournalPrototype w = new IngressJournalPrototype(source.toFile(), 1024)) {
            JournalIdentityPrototype.open(source.toFile()); w.append("E\tsynthetic\tseed");
        }
        byte[] original = Files.readAllBytes(source); String expected = at(source);
        String exe = new File(new File(System.getProperty("java.home"), "bin"), "java.exe").getPath();
        Process p = new ProcessBuilder(exe, "-cp", System.getProperty("java.class.path"),
            SplitLockJournalPrototypeTest.class.getName(), mode, dir.toString(), "owner")
            .redirectErrorStream(true).redirectOutput(dir.resolve("owner.log").toFile()).start();
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(dir.resolve("owner.ready"))) {
                if (!p.isAlive() || System.nanoTime() > end) throw new AssertionError("owner readiness");
                Thread.sleep(10);
            }
            try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(source, true)) {
                if (blocked) {
                    try { h.readPrefix(expected, original.length, hash(original)); throw new AssertionError("old lock read bypassed"); }
                    catch (IOException valid) { check(true, "native READ remains BLOCKED by old writer in separate JVM"); }
                } else {
                    check(Arrays.equals(original, h.readPrefix(expected, original.length, hash(original))),
                        "native same-handle READ succeeds below split guard in separate JVM");
                }
            }
            Files.write(dir.resolve("owner.stop"), new byte[0]);
            check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, "separate lock owner exits normally " + mode);
        } finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
        check(Arrays.equals(original, Files.readAllBytes(source)), "lock reader leaves original unchanged " + mode);
    }
    public static void main(String[] args) throws Exception {
        String expectedModel = System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if (expectedModel != null) check(expectedModel.equals(System.getProperty("sun.arch.data.model")), "actual JVM data model matches required " + expectedModel);
        System.out.println("Runtime " + System.getProperty("java.version") + " " + System.getProperty("java.vendor") +
            " data-model=" + System.getProperty("sun.arch.data.model"));
        Path dir = Files.createTempDirectory("romlogger-native-bound-read-");
        Path source = dir.resolve("原本_ユニコード.rlj"), anchor = dir.resolve("retained.anchor");
        byte[] bytes = new byte[9000]; for (int i=0; i<bytes.length; i++) bytes[i] = (byte)(i % 251);
        Files.write(source, bytes); Files.createLink(anchor, source); String expected = at(source);
        try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(source, true)) {
            check(Arrays.equals(bytes, h.readPrefix(expected, bytes.length, hash(bytes))), "multi-chunk bytes and identity bound to same handle, Unicode path");
        }
        Path moved = dir.resolve("retained.rlj");
        try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(source, true)) {
            Files.move(source, moved); Files.copy(moved, source);
            check(!expected.equals(at(source)), "replacement pathname is a different file");
            check(Arrays.equals(bytes, h.readPrefix(expected, bytes.length, hash(bytes))), "held native reader returns original bytes after pathname replacement");
            check(h.identity().equals(at(anchor)), "returned bytes tied to retained original identity");
        }
        denied(source, expected, bytes.length, hash(bytes), "copied source cannot use original expected ID");
        denied(moved, expected, bytes.length + 1, hash(bytes), "prefix past EOF rejected");
        denied(moved, expected, -1, hash(bytes), "negative prefix rejected");
        denied(moved, expected, (int)IngressJournalPrototype.MAX_JOURNAL_BYTES + 1, hash(bytes), "prefix quota rejected");
        byte[] changed = bytes.clone(); changed[0] ^= 1; Files.write(moved, changed);
        check(expected.equals(at(moved)), "COUNTEREXAMPLE native identity alone does not detect in-place tampering");
        denied(moved, expected, bytes.length, hash(bytes), "retained prefix SHA rejects in-place tampering");
        // Keep corrupted fixture; never silently repair it.
        check(Arrays.equals(changed, Files.readAllBytes(anchor)), "tampered original preserved for review");
        Path quota = dir.resolve("quota.rlj"); Files.write(quota, new byte[]{1}); String quotaId = at(quota);
        try (RandomAccessFile f = new RandomAccessFile(quota.toFile(), "rw")) { f.setLength(IngressJournalPrototype.MAX_JOURNAL_BYTES + 1); }
        denied(quota, quotaId, 1, hash(new byte[]{1}), "source size over quota rejected before buffer allocation");
        try (WindowsNativeIdentityTest.Handle h = new WindowsNativeIdentityTest.Handle(source, true)) {
            h.close();
            try { h.identity(); throw new AssertionError("closed identity accepted"); }
            catch (IOException valid) { check(true, "closed identity handle rejected"); }
            try { h.readPrefix(at(source), 1, hash(new byte[]{bytes[0]})); throw new AssertionError("closed read accepted"); }
            catch (IOException valid) { check(true, "closed read handle rejected"); }
        }
        lockedReader("split", false); lockedReader("old", true);
        System.out.println("WindowsBoundReadTest passed " + checks + " checks; NOT_COMMITTED, no watermark/DB/ACK");
    }
}
