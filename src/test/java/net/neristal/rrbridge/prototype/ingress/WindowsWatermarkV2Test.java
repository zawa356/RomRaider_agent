package net.neristal.rrbridge.prototype.ingress;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TEST ONLY: RLC2 scenarios with native NTFS identity bound to the writer's and reader's own
 * handles (JNA confined to this test JVM). Directory durability stays UNKNOWN on Windows:
 * MOVEFILE_WRITE_THROUGH is requested but no power-loss guarantee is claimed.
 */
public final class WindowsWatermarkV2Test {
    public interface Api extends WindowsNativeWriterTest.Api {
        int MoveFileExW(WString existing, WString replacement, int flags);
    }
    static final Api API = Native.load("kernel32", Api.class);
    private static final int GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000, OPEN_EXISTING = 3, OPEN_REPARSE_POINT = 0x00200000;
    private static final int MOVEFILE_REPLACE_EXISTING = 0x1, MOVEFILE_WRITE_THROUGH = 0x8;
    static String directoryObservation = "not attempted";

    private static boolean invalid(Pointer h) {
        return h == null || Pointer.nativeValue(h) == -1L || (Native.POINTER_SIZE == 4 && Pointer.nativeValue(h) == 0xffffffffL);
    }
    private static Pointer open(Path path, int access, int share) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("symlink path rejected");
        if (!"NTFS".equalsIgnoreCase(Files.getFileStore(path).type())) throw new IOException("unreviewed filesystem");
        Pointer h = API.CreateFileW(new WString(path.toAbsolutePath().toString()), access, share, null, OPEN_EXISTING, OPEN_REPARSE_POINT, null);
        if (invalid(h)) throw new IOException("native open failed, error=" + Native.getLastError());
        return h;
    }
    /** volume serial (4) + file index high (4) + low (4), from the given handle. */
    static WatermarkV2Prototype.SourceIdentity identity(Pointer h) throws IOException {
        WindowsNativeIdentityTest.Info i = new WindowsNativeIdentityTest.Info();
        if (API.GetFileInformationByHandle(h, i) == 0) throw new IOException("identity unavailable, error=" + Native.getLastError());
        i.read();
        if ((i.words[0] & 0x10) != 0 || (i.words[0] & 0x400) != 0) throw new IOException("directory or reparse point rejected");
        return new WatermarkV2Prototype.SourceIdentity(WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX,
            ByteBuffer.allocate(12).putInt(i.words[7]).putInt(i.words[11]).putInt(i.words[12]).array());
    }
    static long size(Pointer h) throws IOException {
        WindowsNativeIdentityTest.Info i = new WindowsNativeIdentityTest.Info();
        if (API.GetFileInformationByHandle(h, i) == 0) throw new IOException("size unavailable");
        i.read(); return ((long) i.words[8] << 32) | (i.words[9] & 0xffffffffL);
    }
    static byte[] read(Pointer h, long offset, int length) throws IOException {
        if (API.SetFilePointerEx(h, offset, new LongByReference(), 0) == 0) throw new IOException("native seek failed");
        byte[] out = new byte[length]; int at = 0;
        while (at < length) {
            byte[] part = new byte[Math.min(65536, length - at)]; IntByReference n = new IntByReference();
            if (API.ReadFile(h, part, part.length, n, null) == 0) throw new IOException("native read denied, error=" + Native.getLastError());
            if (n.getValue() < 1 || n.getValue() > part.length) throw new IOException("short native read");
            System.arraycopy(part, 0, out, at, n.getValue()); at += n.getValue();
        }
        return out;
    }

    static final class Owned extends WatermarkV2Scenarios.OwnedJournal {
        private Pointer handle;
        Owned(Path path) throws IOException {
            if (Files.size(path) > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("existing source over 8MiB quota");
            // READ|WRITE, share READ|WRITE, no SHARE_DELETE: pins the name while owned.
            handle = open(path, GENERIC_READ | GENERIC_WRITE, 3);
            try { adopt(); } catch (IOException | RuntimeException e) { close(); throw e; }
        }
        private Pointer h() throws IOException { if (handle == null) throw new IOException("closed"); return handle; }
        public WatermarkV2Prototype.SourceIdentity identity() throws IOException { return WindowsWatermarkV2Test.identity(h()); }
        public long length() throws IOException { return size(h()); }
        public byte[] read(long offset, int length) throws IOException { return WindowsWatermarkV2Test.read(h(), offset, length); }
        public void sync() throws IOException { if (API.FlushFileBuffers(h()) == 0) throw new IOException("FlushFileBuffers failed, error=" + Native.getLastError()); }
        protected void writeAt(long offset, byte[] bytes) throws IOException {
            if (API.SetFilePointerEx(h(), offset, new LongByReference(), 0) == 0) throw new IOException("native seek failed");
            IntByReference n = new IntByReference();
            if (API.WriteFile(h(), bytes, bytes.length, n, null) == 0 || n.getValue() != bytes.length) throw new IOException("partial native write retained");
        }
        public void close() throws IOException {
            if (handle != null) { Pointer x = handle; handle = null; if (API.CloseHandle(x) == 0) throw new IOException("close failed"); }
        }
    }

    static final WatermarkV2Prototype.ReadOpener READER = new WatermarkV2Prototype.ReadOpener() {
        public WatermarkV2Prototype.ReadHandle open(Path source) throws IOException {
            final Pointer h = WindowsWatermarkV2Test.open(source, GENERIC_READ, 7);
            return new WatermarkV2Prototype.ReadHandle() {
                public WatermarkV2Prototype.SourceIdentity identity() throws IOException { return WindowsWatermarkV2Test.identity(h); }
                public long length() throws IOException { return size(h); }
                public byte[] read(long offset, int length) throws IOException { return WindowsWatermarkV2Test.read(h, offset, length); }
                public void close() throws IOException { if (API.CloseHandle(h) == 0) throw new IOException("close failed"); }
            };
        }
    };

    static final WatermarkV2Prototype.Platform PLATFORM = new WatermarkV2Prototype.Platform() {
        public void atomicReplace(Path staged, Path marker) throws IOException {
            if (API.MoveFileExW(new WString(staged.toAbsolutePath().toString()), new WString(marker.toAbsolutePath().toString()),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) == 0)
                throw new IOException("MoveFileExW replace failed, error=" + Native.getLastError());
        }
        public WatermarkV2Prototype.DirectoryDurability syncDirectory(Path directory) {
            // Observation only. Win32 documents no directory-entry flush guarantee for this
            // path, so the result is UNKNOWN regardless of what the call returns.
            Pointer d = API.CreateFileW(new WString(directory.toAbsolutePath().toString()), GENERIC_READ | GENERIC_WRITE, 7, null, OPEN_EXISTING, 0x02000000, null);
            if (invalid(d)) directoryObservation = "directory open error=" + Native.getLastError();
            else {
                int ok = API.FlushFileBuffers(d); int err = ok == 0 ? Native.getLastError() : 0;
                API.CloseHandle(d);
                directoryObservation = ok != 0 ? "FlushFileBuffers(directory) returned success" : "FlushFileBuffers(directory) error=" + err;
            }
            return WatermarkV2Prototype.DirectoryDurability.UNKNOWN;
        }
    };

    static final WatermarkV2Scenarios.Env ENV = new WatermarkV2Scenarios.Env() {
        public String name() { return "Windows/NTFS native " + System.getProperty("sun.arch.data.model") + "-bit Java " + System.getProperty("java.version"); }
        public WatermarkV2Scenarios.OwnedJournal openOwned(Path source) throws IOException { return new Owned(source); }
        public WatermarkV2Prototype.ReadOpener reader() { return READER; }
        public WatermarkV2Prototype.Platform platform() { return PLATFORM; }
        public WatermarkV2Prototype.IdentityTag tag() { return WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX; }
        public WatermarkV2Prototype.DirectoryDurability expectedDirectory() { return WatermarkV2Prototype.DirectoryDurability.UNKNOWN; }
        public boolean heldNamesArePinned() { return true; }
        public String childMain() { return WindowsWatermarkV2Test.class.getName(); }
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("child")) { WatermarkV2Scenarios.child(ENV, args[1], new java.io.File(args[2]).toPath(), args[3]); return; }
        Path dir = WatermarkV2Scenarios.seed("win-identity"); Path src = dir.resolve("source.rlj");
        try (WatermarkV2Prototype.ReadHandle h = READER.open(src)) {
            final WatermarkV2Prototype.SourceIdentity id = h.identity();
            WatermarkV2Scenarios.check(id.tag == WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX, "native identity is tagged WINDOWS_NTFS_VOLUME_FILE_INDEX");
            WatermarkV2Scenarios.rejects("Java-only POSIX identity fails closed on Windows", new WatermarkV2Scenarios.Action() {
                public void run() throws Exception { WatermarkV2Prototype.posixIdentity(dirSource()); } });
        }
        WatermarkV2Scenarios.run(ENV);
        System.out.println("OBSERVED Windows directory: " + directoryObservation + " -> reported UNKNOWN (no documented power-loss guarantee)");
    }
    private static Path dirSource() throws IOException { return WatermarkV2Scenarios.seed("win-posix").resolve("source.rlj"); }
}
