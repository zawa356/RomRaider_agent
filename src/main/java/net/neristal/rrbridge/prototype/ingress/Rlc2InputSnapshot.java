package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Bounded change detection, not a substitute for external quiescence or atomic FS/DB locks. */
final class Rlc2InputSnapshot {
    private final Path directory;
    private final File ledger,legacy;
    private final Map<String,String> evidence;
    private Rlc2InputSnapshot(Path dir,File ledger,File legacy)throws Exception{
        directory=dir;this.ledger=ledger;this.legacy=legacy;evidence=scan();
    }
    static Rlc2InputSnapshot capture(Path dir,File ledger,File legacy)throws Exception{return new Rlc2InputSnapshot(dir,ledger,legacy);}
    void verify()throws Exception{if(!evidence.equals(scan()))throw new IOException("SNAPSHOT_CHANGED: retain inputs and retry only after quiescence");}
    private Map<String,String> scan()throws Exception{
        Map<String,String> result=new TreeMap<String,String>();long total=0;int count=0;
        IngressDiagnostics.safePath(directory);
        BasicFileAttributes dir=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        result.put("directory",String.valueOf(dir.fileKey()));
        try(java.nio.file.DirectoryStream<Path> paths=Files.newDirectoryStream(directory)){
            for(Path p:paths){if(++count>160)throw new IOException("snapshot file capacity reached");total+=stamp(p,result);if(total>256L*1024*1024)throw new IOException("snapshot total byte capacity reached");}
        }
        for(File f:new File[]{ledger,legacy}){
            // A checkpointed closed DB only. Never ignore a newly created WAL/journal.
            for(String suffix:new String[]{"-wal","-shm","-journal"})if(Files.exists(new File(f.getPath()+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("snapshot database is active");
            total+=stamp(f.toPath(),result);
        }
        if(total>256L*1024*1024)throw new IOException("snapshot total byte capacity reached");return result;
    }
    private long stamp(Path p,Map<String,String> result)throws Exception{
        IngressDiagnostics.safePath(p);BasicFileAttributes before=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>128L*1024*1024)throw new IOException("bounded regular snapshot files required");
        MessageDigest hash=WatermarkV2Prototype.sha();long bytes=0;byte[] buffer=new byte[32768];
        try(InputStream stream=Files.newInputStream(p)){int n;while((n=stream.read(buffer))!=-1){bytes+=n;if(bytes>128L*1024*1024)throw new IOException("snapshot file grew past capacity");hash.update(buffer,0,n);}}
        BasicFileAttributes after=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(bytes!=before.size()||bytes!=after.size()||!java.util.Objects.equals(before.fileKey(),after.fileKey())||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("snapshot changed while hashing");
        result.put(p.toFile().getCanonicalPath(),String.valueOf(after.fileKey())+"|"+bytes+"|"+WatermarkLedgerPrototype.hex(hash.digest()));return bytes;
    }
}
