package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32;

/** Isolated prototype. Identity must be persisted before ANY journal append. */
public final class JournalIdentityPrototype {
    private static final String MAGIC = "RLI1\n";
    private final String sourceUuid;
    private JournalIdentityPrototype(String id) { sourceUuid = id; }
    public String sourceUuid() { return sourceUuid; }

    /** Never silently regenerate an identity for a nonempty journal. */
    public static JournalIdentityPrototype open(File journal) throws IOException {
        if (journal == null) throw new IllegalArgumentException("journal required");
        File source = journal.getAbsoluteFile();
        File parent = source.getParentFile();
        if (parent == null || !parent.isDirectory() ||
                Files.isSymbolicLink(parent.toPath()) ||
                Files.isSymbolicLink(source.toPath()) || !source.isFile())
            throw new IOException("existing non-symlink journal required");
        File sidecar = new File(parent, source.getName() + ".identity");
        if (Files.isSymbolicLink(sidecar.toPath())
                || (sidecar.exists() && !sidecar.isFile()))
            throw new IOException("invalid identity path");
        if (!sidecar.exists()) {
            if (source.length() != 0)
                throw new IOException("identity missing for nonempty journal; operator review");
            byte[] content = encode(UUID.randomUUID().toString()).getBytes(StandardCharsets.US_ASCII);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    sidecar.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(content);
                while (buf.hasRemaining()) channel.write(buf);
                channel.force(true);
            } catch (java.nio.file.FileAlreadyExistsException racing) {
                throw new IOException("racing identity creation; operator review", racing);
            }
        }
        byte[] bytes = Files.readAllBytes(sidecar.toPath());
        if (bytes.length != MAGIC.length() + 36 + 1 + 8 + 1)
            throw new IOException("partial or invalid identity sidecar");
        String text = new String(bytes, StandardCharsets.US_ASCII);
        String id = text.substring(MAGIC.length(), MAGIC.length() + 36);
        if (!text.equals(encode(id)))
            throw new IOException("identity checksum mismatch");
        return new JournalIdentityPrototype(id);
    }

    private static String encode(String id) throws IOException {
        String canonical;
        try { canonical = UUID.fromString(id).toString(); }
        catch (IllegalArgumentException bad) { throw new IOException("invalid UUID", bad); }
        if (!canonical.equals(id)) throw new IOException("noncanonical UUID");
        String prefix = MAGIC + id + "\n";
        CRC32 crc = new CRC32();
        crc.update(prefix.getBytes(StandardCharsets.US_ASCII));
        return prefix + String.format(java.util.Locale.ROOT, "%08x", crc.getValue()) + "\n";
    }
}
