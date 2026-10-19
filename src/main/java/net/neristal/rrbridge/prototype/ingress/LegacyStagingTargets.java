package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/** SQLite writes only inside a new, retained, private directory. Final targets are never opened writable. */
final class LegacyStagingTargets {
    final File vault,rli2;
    private final Path work;
    static Path work(File vault){return Paths.get(vault.getAbsolutePath()+".rlpc1-work");}
    LegacyStagingTargets(File finalVault,File finalRli2)throws Exception {
        Path parent=finalVault.getAbsoluteFile().getParentFile().toPath();
        if(!parent.equals(finalRli2.getAbsoluteFile().getParentFile().toPath()))throw new IOException("STAGING_SAME_PRIVATE_PARENT_REQUIRED");
        DeploymentBinding.safe(parent.toFile());DeploymentBinding.privateAccess(parent);
        work=work(finalVault);
        // Atomic directory claim. A partial attempt is retained and cannot be adopted/recovered on retry.
        try{Files.createDirectory(work);}catch(FileAlreadyExistsException exists){throw new IOException("STAGING_TARGET_EXISTS");}
        DeploymentBinding.safe(work.toFile());DeploymentBinding.privateAccess(work);
        vault=work.resolve("vault.db").toFile();rli2=work.resolve("candidate.rlc2-integration.db").toFile();
        Files.createFile(vault.toPath());Files.createFile(rli2.toPath());
    }
    void publish(File staged,File target)throws Exception {
        DeploymentBinding.safe(work.toFile());DeploymentBinding.privateAccess(work);
        DeploymentBinding.safe(staged);
        // Force file content before publication. Hard-link insertion is atomic no-replace, unlike ATOMIC_MOVE.
        try(FileChannel channel=FileChannel.open(staged.toPath(),StandardOpenOption.WRITE)){channel.force(true);}
        try{Files.createLink(target.toPath(),staged.toPath());}
        catch(FileAlreadyExistsException collision){throw new IOException("STAGING_TARGET_EXISTS");}
        catch(UnsupportedOperationException unavailable){throw new IOException("STAGING_ATOMIC_PUBLICATION_UNAVAILABLE",unavailable);}
        // Only our internal name is removed. A halt before this point leaves a multi-link INCOMPLETE artifact.
        Files.delete(staged.toPath());DeploymentBinding.safe(target);
    }
}
