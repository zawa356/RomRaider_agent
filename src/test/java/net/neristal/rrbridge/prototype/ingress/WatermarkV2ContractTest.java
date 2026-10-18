package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.IdentityTag;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.Marker;
import net.neristal.rrbridge.prototype.ingress.WatermarkV2Prototype.SourceIdentity;

/** OS-independent RLC2 codec/contract checks. No files, no platform identity, no product wiring. */
public final class WatermarkV2ContractTest {
    private static int checks;
    private static void check(boolean v, String name) { if (!v) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    private interface Action { void run() throws Exception; }
    private static void rejects(String name, Action a) throws Exception {
        try { a.run(); } catch (IOException expected) { check(true, name); return; }
        throw new AssertionError("accepted: " + name);
    }
    private static byte[] redigest(byte[] raw) {
        MessageDigest d = WatermarkV2Prototype.sha(); d.update(raw, 0, raw.length - 32);
        System.arraycopy(d.digest(), 0, raw, raw.length - 32, 32); return raw;
    }

    public static void main(String[] args) throws Exception {
        final byte[] posixValue = ByteBuffer.allocate(16).putLong(2049).putLong(131).array();
        final byte[] winValue = ByteBuffer.allocate(12).putInt(0x1234abcd).putInt(0).putInt(77).array();
        SourceIdentity posix = new SourceIdentity(IdentityTag.POSIX_DEV_INODE, posixValue);
        SourceIdentity win = new SourceIdentity(IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX, winValue);
        check(WatermarkV2Prototype.BYTES == 144, "RLC2 fixed size is 144 bytes");
        rejects("identity length must match tag", new Action() { public void run() throws Exception { new SourceIdentity(IdentityTag.POSIX_DEV_INODE, winValue); } });
        rejects("all-zero identity rejected", new Action() { public void run() throws Exception { new SourceIdentity(IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX, new byte[12]); } });
        byte[] padded = Arrays.copyOf(winValue, 16);
        check(!new SourceIdentity(IdentityTag.POSIX_DEV_INODE, padded).equals(win), "same bytes under different tags never compare equal");

        String uuid = UUID.randomUUID().toString();
        byte[] hash = WatermarkV2Prototype.sha().digest("prefix".getBytes("UTF-8"));
        for (SourceIdentity id : new SourceIdentity[]{posix, win}) {
            Marker m = new Marker(id, uuid, 7, 3, 3 * 30, hash);
            Marker back = WatermarkV2Prototype.decode(WatermarkV2Prototype.encode(m));
            check(back.identity.equals(id) && back.sourceUuid.equals(uuid) && back.generation == 7 && back.sequence == 3 &&
                back.offset == 90 && Arrays.equals(back.prefixHash(), hash), "round trip preserves fields for " + id.tag);
        }
        final byte[] good = WatermarkV2Prototype.encode(new Marker(win, uuid, 1, 2, 60, hash));
        check(ByteBuffer.wrap(good).getInt() == WatermarkV2Prototype.MAGIC && ByteBuffer.wrap(good).getShort(4) == 2, "header carries RLC2 magic and version 2");

        final byte[] rlc1 = ByteBuffer.allocate(104).putInt(WatermarkV2Prototype.LEGACY_RLC1_MAGIC).array();
        try { WatermarkV2Prototype.decode(rlc1); throw new AssertionError("RLC1 decoded"); }
        catch (WatermarkV2Prototype.LegacyMarkerException legacy) { check(true, "RLC1 bytes raise LegacyMarkerException (never reinterpreted)"); }
        final byte[] rlc1Long = Arrays.copyOf(rlc1, WatermarkV2Prototype.BYTES);
        try { WatermarkV2Prototype.decode(rlc1Long); throw new AssertionError("RLC1 decoded at RLC2 length"); }
        catch (WatermarkV2Prototype.LegacyMarkerException legacy) { check(true, "RLC1 magic rejected even at RLC2 length"); }

        rejects("truncated marker", new Action() { public void run() throws Exception { WatermarkV2Prototype.decode(Arrays.copyOf(good, 143)); } });
        rejects("oversized marker", new Action() { public void run() throws Exception { WatermarkV2Prototype.decode(Arrays.copyOf(good, 145)); } });
        rejects("foreign magic", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[3] = 'X'; WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("digest mismatch on any byte", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[100] ^= 1; WatermarkV2Prototype.decode(b); } });
        rejects("version 1 under RLC2 magic", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[5] = 1; WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("unknown identity tag", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[6] = 9; WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("identity length not matching tag", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[7] = 16; WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("nonzero padding after identity", new Action() { public void run() throws Exception { byte[] b = good.clone(); b[8 + 12] = 1; WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("generation zero", new Action() { public void run() throws Exception { byte[] b = good.clone(); ByteBuffer.wrap(b).putLong(56, 0); WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("offset beyond 8MiB quota", new Action() { public void run() throws Exception { byte[] b = good.clone(); ByteBuffer.wrap(b).putLong(72, IngressJournalPrototype.MAX_JOURNAL_BYTES + 1); WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("sequence without offset", new Action() { public void run() throws Exception { byte[] b = good.clone(); ByteBuffer.wrap(b).putLong(72, 0); WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("sequence larger than frames can fit", new Action() { public void run() throws Exception { byte[] b = good.clone(); ByteBuffer.wrap(b).putLong(64, 10); WatermarkV2Prototype.decode(redigest(b)); } });
        rejects("encode refuses generation zero", new Action() { public void run() throws Exception { WatermarkV2Prototype.encode(new Marker(new SourceIdentity(IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX, winValue), UUID.randomUUID().toString(), 0, 0, 0, new byte[32])); } });
        System.out.println("WatermarkV2ContractTest passed " + checks + " checks (codec/contract only; SHA-256 is integrity, not authentication)");
    }
}
