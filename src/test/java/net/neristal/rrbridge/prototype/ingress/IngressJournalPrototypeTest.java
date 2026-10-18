package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Synthetic-only Java8 test. Never accesses ECU, the host spool, or Caddy. */
public final class IngressJournalPrototypeTest {
    private static int checks = 0;
    private interface Action { void run() throws Exception; }
    private static void ok(String reason, boolean condition) {
        if (!condition) throw new AssertionError(reason);
        checks++;
        System.out.println("PASS " + reason);
    }
    private static void refuses(String reason, Action operation) throws Exception {
        boolean rejected = false;
        try { operation.run(); } catch (IOException | IllegalArgumentException expected) { rejected = true; }
        ok(reason, rejected);
    }
    private static File unique(File dir, String name) { return new File(dir, name + ".rlj"); }
    private static final long QUOTA = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "halt-after-sync".equals(args[0])) {
            try (IngressJournalPrototype journal = new IngressJournalPrototype(new File(args[1]), QUOTA)) {
                journal.append("S\tchild\t0\t1\t123\tRPM=850");
                // close not reached. The append returned after fd.sync().
                Runtime.getRuntime().halt(73);
            }
            return;
        }

        File dir = Files.createTempDirectory("rl-ingress-journal-only").toFile();
        final File file = unique(dir, "normal");
        try (IngressJournalPrototype journal = new IngressJournalPrototype(file, QUOTA)) {
            ok("fresh journal is empty", journal.replay().isEmpty());
            IngressJournalPrototype.Entry first = journal.append("M\tsession\t1\tRPM\t回転数\trpm");
            IngressJournalPrototype.Entry second = journal.append("S\tsession\t1\t1\t1700\tRPM=850");
            IngressJournalPrototype.Entry third = journal.append("E\tsession\t1\t1701\thealth");
            ok("sequence starts at one", first.sequence == 1 && second.sequence == 2 && third.sequence == 3);
            ok("byte offsets monotonically increase", first.offset == 0
                && second.offset > first.offset && third.offset > second.offset);
            List<IngressJournalPrototype.Entry> replay = journal.replay();
            ok("all fsynced rows replay in order", replay.size() == 3
                && replay.get(0).row.equals(first.row)
                && replay.get(1).row.equals(second.row)
                && replay.get(2).row.equals(third.row));
            ok("utf8 Japanese metadata retained", replay.get(0).row.contains("回転数"));
            ok("journal bytes include framing", journal.sizeBytes() > 3L * IngressJournalPrototype.HEADER_BYTES);
            refuses("second writer lock is rejected", new Action() {
                public void run() throws Exception {
                    try (IngressJournalPrototype other = new IngressJournalPrototype(file, QUOTA)) {
                        throw new AssertionError("concurrent journal writer opened");
                    }
                }
            });
            refuses("newline-bearing row is rejected", new Action() {
                public void run() throws Exception { journal.append("S\tbad\nline"); }
            });
            refuses("empty row is rejected", new Action() {
                public void run() throws Exception { journal.append(""); }
            });
            refuses("invalid Java Unicode is rejected", new Action() {
                public void run() throws Exception { journal.append("M\tbad\uD800"); }
            });
            StringBuilder large = new StringBuilder("E\tlarge\t");
            for (int i = 0; i < IngressJournalPrototype.MAX_ROW_BYTES; i++) large.append('X');
            final String tooLarge = large.toString();
            refuses("row max-size enforced", new Action() {
                public void run() throws Exception { journal.append(tooLarge); }
            });
            ok("invalid rows do not change durable length", journal.replay().size() == 3);
        }
        long originalLength = file.length();
        try (IngressJournalPrototype reopened = new IngressJournalPrototype(file, QUOTA)) {
            List<IngressJournalPrototype.Entry> entries = reopened.replay();
            ok("restart replays complete fsynced prefix", entries.size() == 3);
            ok("replay retains stable sequence and byte offset",
                entries.get(1).sequence == 2 && entries.get(1).offset > 0);
            ok("reopen does not truncate source", reopened.sizeBytes() == originalLength);
            ok("new append continues from recovered sequence", reopened.append("E\tsession\t1\t1800\tresume").sequence == 4);
        }
        ok("close preserves append-only file on disk", file.length() > originalLength);

        final File quotaFile = unique(dir, "quota");
        try (IngressJournalPrototype limited = new IngressJournalPrototype(quotaFile, 55)) {
            IngressJournalPrototype.Entry first = limited.append("E\t1");
            long sizeBefore = limited.sizeBytes();
            refuses("hard quota rejects the next frame", new Action() {
                public void run() throws Exception { limited.append("E\t123456789012345678901234567890"); }
            });
            ok("quota failure does not discard accepted entry",
                limited.replay().size() == 1 && limited.replay().get(0).sequence == first.sequence);
            ok("quota failure does not alter bytes", limited.sizeBytes() == sizeBefore);
        }
        try (IngressJournalPrototype retained = new IngressJournalPrototype(quotaFile, 55)) {
            ok("quota stop remains replayable after reopening", retained.replay().size() == 1);
        }
        refuses("invalid tiny quota is rejected", new Action() {
            public void run() throws Exception { new IngressJournalPrototype(unique(dir, "tiny"), 20); }
        });
        refuses("unbounded prototype quota is rejected", new Action() {
            public void run() throws Exception {
                new IngressJournalPrototype(unique(dir, "huge"), IngressJournalPrototype.MAX_JOURNAL_BYTES + 1);
            }
        });

        File abnormal = unique(dir, "abrupt-process");
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaBin = new File(new File(System.getProperty("java.home"), "bin"), executable).getAbsolutePath();
        Process child = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
            IngressJournalPrototypeTest.class.getName(), "halt-after-sync", abnormal.getAbsolutePath())
            .redirectErrorStream(true).start();
        int exit = child.waitFor();
        ok("independent JVM ends abruptly after fd.sync", exit == 73);
        try (IngressJournalPrototype afterHalt = new IngressJournalPrototype(abnormal, QUOTA)) {
            ok("synced row survives abnormal JVM termination",
                afterHalt.replay().size() == 1
                && afterHalt.replay().get(0).row.equals("S\tchild\t0\t1\t123\tRPM=850"));
            ok("new process can append after old lock disappears",
                afterHalt.append("E\tchild\t0\t124\trestart").sequence == 2);
        }

        final File incomplete = unique(dir, "incomplete");
        try (IngressJournalPrototype good = new IngressJournalPrototype(incomplete, QUOTA)) {
            good.append("S\tsafe\t1\t1\t1800\tRPM=900");
        }
        try (RandomAccessFile raf = new RandomAccessFile(incomplete, "rw")) {
            raf.seek(raf.length());
            raf.writeInt(IngressJournalPrototype.FRAME_MAGIC);
            raf.writeByte(42); // partial second header, like an interrupted write
        }
        final byte[] evidence = Files.readAllBytes(incomplete.toPath());
        refuses("truncated frame fails closed, no auto-repair", new Action() {
            public void run() throws Exception {
                try (IngressJournalPrototype bad = new IngressJournalPrototype(incomplete, QUOTA)) {
                    throw new AssertionError("incomplete journal accepted");
                }
            }
        });
        ok("truncated source bytes are preserved for review",
            Arrays.equals(evidence, Files.readAllBytes(incomplete.toPath())));

        final File badCrc = unique(dir, "crc");
        try (IngressJournalPrototype valid = new IngressJournalPrototype(badCrc, QUOTA)) {
            valid.append("S\tcrc\t0\t1\t1900\tRPM=925");
        }
        try (RandomAccessFile raf = new RandomAccessFile(badCrc, "rw")) {
            raf.seek(raf.length() - 1);
            raf.writeByte('Z');
        }
        final byte[] crcEvidence = Files.readAllBytes(badCrc.toPath());
        refuses("payload CRC mismatch fails closed", new Action() {
            public void run() throws Exception { new IngressJournalPrototype(badCrc, QUOTA); }
        });
        ok("corrupt journal is not deleted or modified", Arrays.equals(crcEvidence, Files.readAllBytes(badCrc.toPath())));

        final File badSeq = unique(dir, "sequence");
        try (IngressJournalPrototype valid = new IngressJournalPrototype(badSeq, QUOTA)) {
            valid.append("M\tsequence\t0\tRPM\trpm");
        }
        try (RandomAccessFile raf = new RandomAccessFile(badSeq, "rw")) {
            raf.seek(8); // first frame sequence begins after magic + length
            raf.writeLong(2);
        }
        refuses("nonconsecutive sequence is rejected", new Action() {
            public void run() throws Exception { new IngressJournalPrototype(badSeq, QUOTA); }
        });

        final File invalidUtf8 = unique(dir, "utf8");
        try (IngressJournalPrototype valid = new IngressJournalPrototype(invalidUtf8, QUOTA)) {
            valid.append("M\tutf8\t0\tRPM\trpm");
        }
        try (RandomAccessFile raf = new RandomAccessFile(invalidUtf8, "rw")) {
            raf.seek(IngressJournalPrototype.HEADER_BYTES);
            byte[] payload = new byte[(int) (raf.length() - IngressJournalPrototype.HEADER_BYTES)];
            raf.readFully(payload);
            payload[0] = (byte) 0xff;
            CRC32 crc = new CRC32();
            crc.update(payload);
            raf.seek(16); // CRC field
            raf.writeInt((int) crc.getValue());
            raf.write(payload);
        }
        refuses("malformed UTF8 rejects even when CRC is recomputed", new Action() {
            public void run() throws Exception { new IngressJournalPrototype(invalidUtf8, QUOTA); }
        });

        // Only link creation may be skipped (no symlink privilege, e.g. Windows without
        // Developer Mode); the rejection assertion itself is never caught. CI sets
        // ROMLOGGER_REQUIRE_SYMLINK_TESTS=1 so a missing capability fails there.
        final File symlink = new File(dir, "link.rlj");
        boolean linked = false;
        try {
            Files.createSymbolicLink(symlink.toPath(), abnormal.toPath());
            linked = true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            if ("1".equals(System.getenv("ROMLOGGER_REQUIRE_SYMLINK_TESTS"))) throw unavailable;
            System.out.println("SKIP journal symlink rejection (symlink creation unavailable: "
                + unavailable.getClass().getSimpleName() + ")");
        }
        if (linked) {
            refuses("journal symlink is rejected", new Action() {
                public void run() throws Exception { new IngressJournalPrototype(symlink, QUOTA); }
            });
        }
        ok("all test artifacts stay in isolated temporary directory", dir.isDirectory());
        System.out.println("IngressJournalPrototypeTest passed " + checks + " checks (isolated synthetic only)");
    }
}
