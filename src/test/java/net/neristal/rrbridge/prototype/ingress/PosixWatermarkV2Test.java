package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** RLC2 scenarios with Java-only POSIX dev/inode identity. On non-POSIX platforms: fail-closed check, scenarios NOT RUN. */
public final class PosixWatermarkV2Test {
    static final class Owned extends WatermarkV2Scenarios.OwnedJournal {
        private final RandomAccessFile file; private final WatermarkV2Prototype.PosixOwnedSource source;
        Owned(Path path) throws IOException {
            WatermarkV2Prototype.posixIdentity(path); // fail closed before opening for write
            file = new RandomAccessFile(path.toFile(), "rw");
            source = new WatermarkV2Prototype.PosixOwnedSource(path, file);
            try { adopt(); } catch (IOException | RuntimeException e) { file.close(); throw e; }
        }
        public WatermarkV2Prototype.SourceIdentity identity() throws IOException { return source.identity(); }
        public long length() throws IOException { return source.length(); }
        public byte[] read(long offset, int length) throws IOException { return source.read(offset, length); }
        public void sync() throws IOException { source.sync(); }
        protected void writeAt(long offset, byte[] bytes) throws IOException { file.seek(offset); file.write(bytes); }
        public void close() throws IOException { file.close(); }
    }
    static final WatermarkV2Scenarios.Env ENV = new WatermarkV2Scenarios.Env() {
        public String name() { return "POSIX/Java"; }
        public WatermarkV2Scenarios.OwnedJournal openOwned(Path source) throws IOException { return new Owned(source); }
        public WatermarkV2Prototype.ReadOpener reader() { return WatermarkV2Prototype.POSIX_READER; }
        public WatermarkV2Prototype.Platform platform() { return WatermarkV2Prototype.POSIX_PLATFORM; }
        public WatermarkV2Prototype.IdentityTag tag() { return WatermarkV2Prototype.IdentityTag.POSIX_DEV_INODE; }
        public WatermarkV2Prototype.DirectoryDurability expectedDirectory() { return WatermarkV2Prototype.DirectoryDurability.OS_REPORTED_SYNCED; }
        public boolean heldNamesArePinned() { return false; }
        public String childMain() { return PosixWatermarkV2Test.class.getName(); }
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("child")) { WatermarkV2Scenarios.child(ENV, args[1], new java.io.File(args[2]).toPath(), args[3]); return; }
        Path probe = WatermarkV2Scenarios.seed("posix-probe").resolve("source.rlj");
        boolean posix;
        try { WatermarkV2Prototype.posixIdentity(probe); posix = true; }
        catch (IOException unavailable) { posix = false; }
        if (!posix) {
            try { new Owned(probe).close(); throw new AssertionError("POSIX writer opened without identity"); }
            catch (IOException failClosed) { WatermarkV2Scenarios.check(Files.size(probe) == 0, "POSIX identity unavailable: owned writer fails closed, source untouched"); }
            System.out.println("NOT RUN PosixWatermarkV2Test scenarios: no unix:dev/ino view on " + System.getProperty("os.name"));
            return;
        }
        WatermarkV2Scenarios.run(ENV);
    }
}
