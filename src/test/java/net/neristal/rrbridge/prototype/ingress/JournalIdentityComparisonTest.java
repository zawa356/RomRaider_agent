package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** TEST ONLY: comparison counterexamples, never an ingestion authorization. */
public final class JournalIdentityComparisonTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static void regular(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path p = absolute; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("symlink component");
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing regular file");
    }
    // This proves ONLY the relationship between the CURRENT two pathnames.
    private static boolean anchored(Path source, Path anchor) throws IOException {
        if (source.toAbsolutePath().normalize().equals(anchor.toAbsolutePath().normalize()))
            throw new IOException("two distinct names required");
        regular(source); regular(anchor);
        return Files.isSameFile(source, anchor);
    }
    private static void denied(Path source, Path anchor, String name) throws Exception {
        try { anchored(source, anchor); throw new AssertionError("accepted " + name); }
        catch (IOException expected) { check(true, name); }
    }
    private static Process child(Path source, Path anchor) throws IOException {
        String exe = new File(new File(System.getProperty("java.home"), "bin"),
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
        return new ProcessBuilder(exe, "-cp", System.getProperty("java.class.path"),
            JournalIdentityComparisonTest.class.getName(), source.toString(), anchor.toString())
            .redirectErrorStream(true).redirectOutput(source.resolveSibling("restart.log").toFile()).start();
    }
    private static void verifyChild(Path source, Path anchor) throws Exception {
        Process p = child(source, anchor);
        try { check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0,
                    "fresh JVM verifies unchanged anchor pair"); }
        finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
    }
    private static void lockCloseCounterexample(Path dir) throws Exception {
        Path source = dir.resolve("source.rlj"), anchor = dir.resolve("source.anchor");
        try (SplitLockJournalPrototype owner = new SplitLockJournalPrototype(source.toFile(), 1024)) {
            owner.append("E\tsynthetic\tseed"); Files.createLink(anchor, source);
            check(anchored(source, anchor), "anchor comparison while split owner holds guard");
            // On POSIX ANY close of this inode may release the process's guard.
            try (RandomAccessFile extra = new RandomAccessFile(anchor.toFile(), "r")) {
                check(extra.length() == source.toFile().length(), "extra anchor handle observes source");
            }
            String exe = new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").getPath();
            Process p = new ProcessBuilder(exe, "-cp", System.getProperty("java.class.path"),
                SplitLockJournalPrototypeTest.class.getName(), "old", dir.toString(), "probe")
                .redirectErrorStream(true).redirectOutput(dir.resolve("probe.log").toFile()).start();
            try {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (!Files.exists(dir.resolve("probe.ready")) && !Files.exists(dir.resolve("probe.denied"))) {
                    if (!p.isAlive() || System.nanoTime() > end) throw new AssertionError("lock contender readiness");
                    Thread.sleep(10);
                }
                boolean blocked = Files.exists(dir.resolve("probe.denied"));
                if (System.getProperty("os.name").startsWith("Linux"))
                    check(!blocked, "COUNTEREXAMPLE Linux same-JVM anchor handle close loses legacy guard");
                else if (System.getProperty("os.name").startsWith("Windows"))
                    check(blocked, "Windows legacy guard survives distinct anchor handle close");
                else throw new AssertionError("unreviewed OS lock semantics");
                Files.write(dir.resolve("probe.stop"), new byte[0]);
                check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, "lock contender ends normally");
            } finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            if (!anchored(new File(args[0]).toPath(), new File(args[1]).toPath())) throw new AssertionError("pair differs");
            return;
        }
        Path dir = Files.createTempDirectory("romlogger-identity-comparison-");
        Path source = dir.resolve("source.rlj"), anchor = dir.resolve("source.anchor");
        try (IngressJournalPrototype w = new IngressJournalPrototype(source.toFile(), 1024)) {
            JournalIdentityPrototype.open(source.toFile()); w.append("E\tsynthetic\tidentity");
        }
        byte[] original = Files.readAllBytes(source);
        Files.createLink(anchor, source);
        check(anchored(source, anchor), "A hardlink identifies original");
        check(Files.getFileStore(source).equals(Files.getFileStore(anchor)), "anchor on same file store");
        verifyChild(source, anchor);
        Path copy = dir.resolve("copy.rlj"); Files.copy(source, copy);
        check(!anchored(copy, anchor), "A byte-identical copy rejected against retained anchor");
        Path moved = dir.resolve("moved.rlj"); Files.move(source, moved);
        denied(source, anchor, "A missing original path fails closed");
        check(anchored(moved, anchor), "A rename retains inode relationship (path contract still separate)");
        Files.copy(moved, source);
        check(!anchored(source, anchor), "A byte-identical replacement at original name rejected");
        check(Arrays.equals(original, Files.readAllBytes(source)), "replacement content is exactly identical");
        Path set = Files.createDirectory(dir.resolve("cloned-set"));
        Path cloned = set.resolve("source.rlj"), clonedAnchor = set.resolve("source.anchor");
        Files.copy(moved, cloned); Files.createLink(clonedAnchor, cloned);
        Files.copy(dir.resolve("source.rlj.identity"), set.resolve("source.rlj.identity"));
        check(anchored(cloned, clonedAnchor), "COUNTEREXAMPLE A copied whole set passes current-name comparison");
        check(!Files.isSameFile(moved, cloned), "copied whole set is physically a different original");
        check(JournalIdentityPrototype.readExisting(cloned.toFile()).sourceUuid().equals(
            JournalIdentityPrototype.readExisting(moved.resolveSibling("source.rlj").toFile()).sourceUuid()),
            "COUNTEREXAMPLE C copied UUID sidecar cannot distinguish originals");
        // An in-band UUID/header is also just copied bytes, irrespective of CRC/hash.
        byte[] header = ("RLCANDIDATE\t" + java.util.UUID.randomUUID() + "\n").getBytes("UTF-8");
        Path headerFile = dir.resolve("candidate-header"), headerCopy = dir.resolve("candidate-header-copy");
        Files.write(headerFile, header); Files.copy(headerFile, headerCopy);
        check(Arrays.equals(Files.readAllBytes(headerFile), Files.readAllBytes(headerCopy)) &&
            !Files.isSameFile(headerFile, headerCopy), "COUNTEREXAMPLE C identical in-band UUID/header copied to different file");
        denied(moved, dir.resolve("missing.anchor"), "A missing anchor never regenerated");
        denied(moved, moved, "A identical pathname forbidden (isSameFile shortcut)");
        denied(moved, dir, "A directory anchor rejected");
        try {
            Path sym = dir.resolve("symbolic-anchor"); Files.createSymbolicLink(sym, moved);
            check(Files.isSameFile(moved, sym), "raw isSameFile follows symlink");
            denied(moved, sym, "A symbolic anchor rejected before comparison");
            Path symDir = dir.resolve("symbolic-directory"); Files.createSymbolicLink(symDir, dir);
            denied(symDir.resolve("moved.rlj"), anchor, "A symbolic ancestor rejected");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("NOT RUN symbolic-link fixtures: provider/privilege unavailable " + unavailable.getClass().getSimpleName());
        }
        Path missingPair = dir.resolve("missing-same");
        check(Files.isSameFile(missingPair, missingPair), "COUNTEREXAMPLE raw isSameFile equal missing paths returns true");
        denied(missingPair, missingPair, "A equal missing names fail closed");
        check(Arrays.equals(original, Files.readAllBytes(moved)) && Arrays.equals(original, Files.readAllBytes(anchor)),
            "all retained original/anchor bytes preserved");
        lockCloseCounterexample(Files.createTempDirectory("romlogger-anchor-lock-close-"));
        System.out.println("JournalIdentityComparisonTest passed " + checks + " checks; A/C NOT SAFE standalone; OS restart NOT RUN");
    }
}
