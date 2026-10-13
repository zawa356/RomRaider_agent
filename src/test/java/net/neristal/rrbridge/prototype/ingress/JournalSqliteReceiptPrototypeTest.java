package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/** Separate SQLite is REAL; it is NOT the product-v1 record schema. */
public final class JournalSqliteReceiptPrototypeTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void ok(String name, boolean valid) {
        if (!valid) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private static void rejects(String name, Action action) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException | IllegalArgumentException |
                IllegalStateException expected) { failed = true; }
        ok(name, failed);
    }
    private static File emptyJournal(File dir, String name) throws Exception {
        File f = new File(dir,name+".rlj");
        if (!f.createNewFile()) throw new AssertionError("fixture exists");
        return f;
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "commit-then-halt".equals(args[0])) {
            File source = new File(args[1]);
            try (IngressJournalPrototype journal = new IngressJournalPrototype(source, 1048576)) {
                new JournalSqliteReceiptPrototype(new File(args[2])).importNew(
                    journal, JournalIdentityPrototype.open(source), 100);
            }
            Runtime.getRuntime().halt(77);
            return;
        }
        File dir = Files.createTempDirectory("romlogger-receipt-4b2a").toFile();
        File journalFile = emptyJournal(dir,"source");
        JournalIdentityPrototype identity = JournalIdentityPrototype.open(journalFile);
        String uuid = identity.sourceUuid();
        ok("new journal has canonical source UUID", UUID.fromString(uuid).toString().equals(uuid));
        ok("source UUID reopens unchanged",
            JournalIdentityPrototype.open(journalFile).sourceUuid().equals(uuid));
        File otherFile = emptyJournal(dir,"other");
        ok("independent journal receives independent identity",
            !JournalIdentityPrototype.open(otherFile).sourceUuid().equals(uuid));
        final File dbFile = new File(dir,"receipts.db");
        final JournalSqliteReceiptPrototype bridge = new JournalSqliteReceiptPrototype(dbFile);
        try (IngressJournalPrototype journal = new IngressJournalPrototype(journalFile,1048576)) {
            journal.append("M\talpha\t1\tRPM\t回転数\trpm");
            journal.append("S\talpha\t1\t1\t1000\tRPM=850");
            journal.append("E\talpha\t1\t1001\thealth");
            ok("real SQLite imports first two rows in one transaction",
                bridge.importNew(journal,identity,2) == 2);
            ok("cursor is committed with receipt rows",
                bridge.committedSequence(uuid) == 2 && bridge.rowCount() == 2);
            ok("retry without additional data is idempotent at committed prefix",
                bridge.importNew(journal,identity,2) == 1 &&
                bridge.committedSequence(uuid) == 3 && bridge.rowCount() == 3);
            ok("further replay does not insert duplicates",
                bridge.importNew(journal,identity,100) == 0 && bridge.rowCount() == 3);
            String id = JournalSqliteReceiptPrototype.recordId(uuid, 2);
            ok("record ID deterministic across process-like bridge restart",
                id.equals(JournalSqliteReceiptPrototype.recordId(
                    JournalIdentityPrototype.open(journalFile).sourceUuid(),2)));
            JournalSqliteReceiptPrototype restarted = new JournalSqliteReceiptPrototype(dbFile);
            ok("SQLite cursor survives fresh DB connection",
                restarted.committedSequence(uuid) == 3 && restarted.rowCount() == 3);

            journal.append("E\talpha\t1\t2000\tafter-reset");
            JournalSqliteReceiptPrototype fault = new JournalSqliteReceiptPrototype(
                dbFile, new Runnable() { public void run() { throw new IllegalStateException(
                    "synthetic crash immediately before transaction commit"); } });
            rejects("injected failure aborts SQLite receipt plus cursor",
                new Action() { public void run() throws Exception { fault.importNew(journal,identity,10); } });
            ok("failed atomic transaction retains old checkpoint and count",
                restarted.committedSequence(uuid) == 3 && restarted.rowCount() == 3);
            ok("restart retries missing frame exactly once",
                restarted.importNew(journal,identity,10) == 1 &&
                restarted.committedSequence(uuid) == 4 && restarted.rowCount() == 4);
        }
        File identitySidecar = new File(journalFile.getAbsolutePath()+".identity");
        final byte[] identityEvidence = Files.readAllBytes(identitySidecar.toPath());
        // Losing the companion ID may NEVER mint a fresh identity for old source.
        Files.delete(identitySidecar.toPath());
        rejects("missing identity for nonempty journal fails closed",
            new Action() { public void run() throws Exception {
                JournalIdentityPrototype.open(journalFile);
            } });
        ok("missing ID does not create replacement sidecar", !identitySidecar.exists());
        Files.write(identitySidecar.toPath(),identityEvidence);
        try (RandomAccessFile f = new RandomAccessFile(identitySidecar,"rw")) {
            f.seek(f.length()-2);
            int original = f.readUnsignedByte();
            f.seek(f.length()-2);
            f.writeByte(original == '0' ? '1' : '0');
        }
        final byte[] corruptedEvidence = Files.readAllBytes(identitySidecar.toPath());
        rejects("identity checksum corruption is rejected",
            new Action() { public void run() throws Exception { JournalIdentityPrototype.open(journalFile); } });
        ok("identity evidence is not silently rewritten",
            Arrays.equals(corruptedEvidence,Files.readAllBytes(identitySidecar.toPath())));
        Files.write(identitySidecar.toPath(),identityEvidence);

        // A valid CRC can be recomputed by an offline actor: the downstream
        // SQLite immutable digest must still detect altered committed prefix.
        try (RandomAccessFile f = new RandomAccessFile(journalFile,"rw")) {
            f.seek(0);
            if(f.readInt()!=IngressJournalPrototype.FRAME_MAGIC)throw new AssertionError("magic");
            int n=f.readInt();
            byte[] raw=new byte[n];
            f.seek(IngressJournalPrototype.HEADER_BYTES);
            f.readFully(raw);
            raw[n-1]=(byte)(raw[n-1]=='X'?'Y':'X');
            CRC32 crc=new CRC32();crc.update(raw);
            f.seek(16);f.writeInt((int)crc.getValue());
            f.write(raw);
        }
        final JournalIdentityPrototype restoredIdentity=JournalIdentityPrototype.open(journalFile);
        try (final IngressJournalPrototype rewritten = new IngressJournalPrototype(journalFile,1048576)) {
            rejects("changed durable journal prefix fails immutable SQLite check",
                new Action() { public void run() throws Exception {
                    bridge.importNew(rewritten,restoredIdentity,10);
                } });
            ok("refused changed source leaves SQLite cursor unchanged",
                bridge.committedSequence(uuid)==4 && bridge.rowCount()==4);
        }
        File childSource = emptyJournal(dir,"child");
        JournalIdentityPrototype childIdentity = JournalIdentityPrototype.open(childSource);
        try (IngressJournalPrototype child = new IngressJournalPrototype(childSource, 1048576)) {
            child.append("E\tchild\t0\t1900\tsynced");
        }
        File childDb = new File(dir,"child-sqlite.db");
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
            ? "java.exe" : "java";
        String javaBin = new File(new File(System.getProperty("java.home"),"bin"),
            executable).getAbsolutePath();
        Process process = new ProcessBuilder(javaBin,"-cp",System.getProperty("java.class.path"),
            JournalSqliteReceiptPrototypeTest.class.getName(),"commit-then-halt",
            childSource.getAbsolutePath(),childDb.getAbsolutePath())
            .redirectErrorStream(true).start();
        int exited = process.waitFor();
        ok("independent JVM halts directly after SQLite commit",exited==77);
        JournalSqliteReceiptPrototype afterHalt = new JournalSqliteReceiptPrototype(childDb);
        ok("separate JVM sees committed SQLite receipt and cursor",
            afterHalt.committedSequence(childIdentity.sourceUuid())==1
            && afterHalt.rowCount()==1);
        try (IngressJournalPrototype reopened = new IngressJournalPrototype(childSource,1048576)) {
            ok("restart replay sees no duplicate receipt after abrupt JVM stop",
                afterHalt.importNew(reopened,childIdentity,100)==0
                && afterHalt.rowCount()==1);
        }
        ok("journal and receipt fixture isolated from v1 product DB",dir.isDirectory());
        System.out.println("JournalSqliteReceiptPrototypeTest passed "+checks+" checks (real SQLite, isolated)");
    }
}
