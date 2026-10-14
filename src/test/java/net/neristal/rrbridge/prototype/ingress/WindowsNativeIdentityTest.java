package net.neristal.rrbridge.prototype.ingress;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** JNA dependency/native extraction is confined to this TEST process. No product binding. */
public final class WindowsNativeIdentityTest {
    public interface Api extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int sharing, Pointer security, int disposition, int flags, Pointer template);
        int GetFileInformationByHandle(Pointer handle, Info information);
        int CloseHandle(Pointer handle);
        int ReadFile(Pointer handle, byte[] data, int bytes, com.sun.jna.ptr.IntByReference read, Pointer overlapped);
    }
    // BY_HANDLE_FILE_INFORMATION: 13 DWORDs, fixed 52 bytes on both x86/x64.
    public static final class Info extends Structure {
        public int[] words = new int[13];
        protected List<String> getFieldOrder() { return Collections.singletonList("words"); }
    }
    static final Api API = Native.load("kernel32", Api.class);
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    static String id(Pointer handle) throws IOException {
        Info info = new Info();
        if (API.GetFileInformationByHandle(handle, info) == 0) throw new IOException("file identity unavailable, error=" + Native.getLastError());
        info.read();
        if ((info.words[0] & 0x10) != 0 || (info.words[0] & 0x400) != 0) throw new IOException("directory or reparse point rejected");
        if (info.words[11] == 0 && info.words[12] == 0) throw new IOException("empty file index rejected");
        return String.format(java.util.Locale.ROOT, "%08x:%08x:%08x", info.words[7], info.words[11], info.words[12]);
    }
    static final class Handle implements AutoCloseable {
        private Pointer value;
        Handle(Path path) throws IOException { this(path, false); }
        Handle(Path path, boolean dataRead) throws IOException {
            for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
                if (Files.isSymbolicLink(p)) throw new IOException("symlink path rejected");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("existing regular file required");
            if (!"NTFS".equalsIgnoreCase(Files.getFileStore(path).type())) throw new IOException("unreviewed filesystem");
            // FILE_READ_ATTRIBUTES; share READ|WRITE|DELETE; OPEN_EXISTING;
            // FILE_FLAG_OPEN_REPARSE_POINT. No creation, write, lock, or repair.
            value = API.CreateFileW(new WString(path.toAbsolutePath().toString()), dataRead ? 0x80000000 : 0x80, 7, null, 3, 0x00200000, null);
            if (value == null || Pointer.nativeValue(value) == -1L ||
                    (Native.POINTER_SIZE == 4 && Pointer.nativeValue(value) == 0xffffffffL))
                throw new IOException("open failed, error=" + Native.getLastError());
        }
        String identity() throws IOException {
            if (value == null) throw new IOException("closed handle");
            return id(value);
        }
        byte[] readPrefix(String expectedId, int bytes, byte[] expectedHash) throws IOException {
            if (value == null || bytes < 0 || bytes > IngressJournalPrototype.MAX_JOURNAL_BYTES)
                throw new IOException("closed handle or bounded quota");
            Info before = new Info();
            if (API.GetFileInformationByHandle(value, before) == 0) throw new IOException("size unavailable");
            before.read();
            long size = ((long) before.words[8] << 32) | (before.words[9] & 0xffffffffL);
            if (size < bytes || size > IngressJournalPrototype.MAX_JOURNAL_BYTES || !expectedId.equals(identity()))
                throw new IOException("expected source/size mismatch");
            byte[] result = new byte[bytes]; int offset = 0;
            while (offset < bytes) {
                byte[] chunk = new byte[Math.min(4096, bytes - offset)];
                com.sun.jna.ptr.IntByReference read = new com.sun.jna.ptr.IntByReference();
                if (API.ReadFile(value, chunk, chunk.length, read, null) == 0)
                    throw new IOException("native read denied, error=" + Native.getLastError());
                int count = read.getValue();
                if (count < 1 || count > chunk.length) throw new IOException("short/stalled native read");
                System.arraycopy(chunk, 0, result, offset, count); offset += count;
            }
            Info after = new Info();
            if (API.GetFileInformationByHandle(value, after) == 0) throw new IOException("final size unavailable");
            after.read();
            long finalSize = ((long) after.words[8] << 32) | (after.words[9] & 0xffffffffL);
            if (finalSize < bytes || finalSize > IngressJournalPrototype.MAX_JOURNAL_BYTES || !expectedId.equals(identity()))
                throw new IOException("source changed");
            try {
                if (!Arrays.equals(expectedHash, java.security.MessageDigest.getInstance("SHA-256").digest(result)))
                    throw new IOException("prefix content changed");
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            return result; // DIAGNOSTIC ONLY; no fsync/commit proof or production cursor.
        }
        public void close() throws IOException {
            if (value != null) { Pointer owned = value; value = null;
                if (API.CloseHandle(owned) == 0) throw new IOException("close failed"); }
        }
    }
    private static String at(Path path) throws IOException { try (Handle h = new Handle(path)) { return h.identity(); } }
    private static void denied(Path p, String name) throws Exception {
        try { at(p); throw new AssertionError("accepted " + name); }
        catch (IOException expected) { check(true, name); }
    }
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) throw new AssertionError("Windows-only fixture");
        if (args.length == 2) { if (!at(new File(args[0]).toPath()).equals(args[1])) throw new AssertionError("restart mismatch"); return; }
        System.out.println("Runtime " + System.getProperty("java.version") + " " + System.getProperty("java.vendor") +
            " data-model=" + System.getProperty("sun.arch.data.model") + " JNA=" + Native.VERSION);
        check(new Info().size() == 52, "native info struct is fixed 52 bytes");
        Path dir = Files.createTempDirectory("romlogger-native-file-id-");
        Path source = dir.resolve("source.rlj"), anchor = dir.resolve("source.anchor");
        Files.write(source, new byte[]{1,2,3}); Files.createLink(anchor, source);
        String originalId = at(source);
        check(originalId.equals(at(anchor)), "B hardlinks have same volume/index");
        check(originalId.equals(at(source)), "B reopen identity stable");
        String exe = new File(new File(System.getProperty("java.home"), "bin"), "java.exe").getPath();
        Process p = new ProcessBuilder(exe, "-cp", System.getProperty("java.class.path"),
            WindowsNativeIdentityTest.class.getName(), source.toString(), originalId)
            .redirectErrorStream(true).redirectOutput(dir.resolve("restart.log").toFile()).start();
        try { check(p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0, "B fresh JVM sees same persisted expected identity"); }
        finally { if (p.isAlive()) { p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); } }
        Path cloneDir = Files.createDirectory(dir.resolve("clone")), clone = cloneDir.resolve("source.rlj");
        Files.copy(source, clone); Files.createLink(cloneDir.resolve("source.anchor"), clone);
        check(!originalId.equals(at(clone)), "B copied whole anchor set rejected by externally retained expected id");
        check(Files.isSameFile(clone, cloneDir.resolve("source.anchor")), "A whole-set copy passes relationship test");
        try (Handle held = new Handle(source)) {
            Path moved = dir.resolve("retained.rlj"); Files.move(source, moved);
            check(originalId.equals(held.identity()), "B held handle identity stable across rename");
            check(originalId.equals(at(moved)), "B path to renamed original identity stable");
            Files.copy(moved, source);
            check(!held.identity().equals(at(source)), "B same-content path replacement differs from owned handle");
            check(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(moved)), "B replacement is byte-identical");
            // Unlink synthetic pathname only; hardlink original retained.
            Files.delete(moved);
            check(originalId.equals(held.identity()) && originalId.equals(at(anchor)), "B handle and retained anchor survive synthetic unlink");
        }
        denied(dir.resolve("missing.rlj"), "B missing source fails closed without creation");
        denied(dir, "B directory rejected");
        try { id(Pointer.createConstant(-1)); throw new AssertionError("invalid handle accepted"); }
        catch (IOException expected) { check(true, "B native query failure fails closed"); }
        try {
            Path sym = dir.resolve("symlink.rlj"); Files.createSymbolicLink(sym, anchor);
            denied(sym, "B symlink rejected");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            System.out.println("NOT RUN native symbolic-link fixture: provider/privilege unavailable");
        }
        Path journal = dir.resolve("owned.rlj");
        try (SplitLockJournalPrototype w = new SplitLockJournalPrototype(journal.toFile(), 1024)) {
            w.append("E\tsynthetic\tnative");
            try (Handle h = new Handle(journal)) { check(h.identity().equals(at(journal)), "B read-attributes sharing permits split writer ownership"); }
        }
        try (IngressJournalPrototype w = new IngressJournalPrototype(journal.toFile(), 1024)) {
            check(at(journal) != null, "B read-attributes allowed under legacy whole-file lock");
        }
        check(Arrays.equals(new byte[]{1,2,3}, Files.readAllBytes(anchor)), "B original retained unchanged");
        System.out.println("WindowsNativeIdentityTest passed " + checks + " checks; test-only NTFS primitive, OS reboot/power loss NOT RUN");
    }
}
