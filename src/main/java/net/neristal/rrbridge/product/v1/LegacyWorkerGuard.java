package net.neristal.rrbridge.product.v1;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
/** Compatibility gate for the exact configured v1 DB. Never deletes its lock. */
public final class LegacyWorkerGuard implements AutoCloseable {
 private static final Set<String> OPEN=new HashSet<String>();
 private final RandomAccessFile handle; private final FileLock lock;private final Path path;private final Object identity;
 private boolean closed;
 private LegacyWorkerGuard(RandomAccessFile h,FileLock l,Path p,Object id){handle=h;lock=l;path=p;identity=id;}
 private static Object identity(Path p)throws IOException {BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return a.fileKey()==null?a.creationTime():a.fileKey();}
 private static void safe(Path path)throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("LEGACY_LOCK_UNSAFE");}
 public static LegacyWorkerGuard acquire(File database)throws IOException {
  safe(database.toPath().toAbsolutePath());Path path=new File(database.getCanonicalPath()+".worker.lock").toPath();safe(path);
  synchronized(OPEN){if(!OPEN.add(path.toString()))throw new IOException("LEGACY_WORKER_BUSY");}
  RandomAccessFile h=null;
  try {
   h=new RandomAccessFile(path.toFile(),"rw");FileLock l;try{l=h.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("LEGACY_WORKER_BUSY",e);}
   if(l==null)throw new IOException("LEGACY_WORKER_BUSY");
   LegacyWorkerGuard result=new LegacyWorkerGuard(h,l,path,identity(path));result.verify();return result;
  }catch(IOException e){try{if(h!=null)h.close();}finally{synchronized(OPEN){OPEN.remove(path.toString());}}throw e;}
 }
 public synchronized void verify()throws IOException {if(closed||!lock.isValid()||!handle.getChannel().isOpen())throw new IOException("LEGACY_LOCK_LOST");safe(path);if(!identity.equals(identity(path)))throw new IOException("LEGACY_LOCK_REPLACED");}
 public synchronized void close()throws IOException {if(closed)return;closed=true;try{lock.release();}finally{try{handle.close();}finally{synchronized(OPEN){OPEN.remove(path.toString());}}}}
}
