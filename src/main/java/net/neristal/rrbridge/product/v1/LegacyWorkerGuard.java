package net.neristal.rrbridge.product.v1;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
/** Compatibility gate for the exact configured v1 DB. Never deletes its lock. */
public final class LegacyWorkerGuard implements AutoCloseable {
 private final RandomAccessFile handle; private final FileLock lock;
 private LegacyWorkerGuard(RandomAccessFile h,FileLock l){handle=h;lock=l;}
 public static LegacyWorkerGuard acquire(File database)throws IOException {
  File path=new File(database.getCanonicalPath()+".worker.lock");
  if(Files.isSymbolicLink(path.toPath()))throw new IOException("LEGACY_LOCK_UNSAFE");
  RandomAccessFile h=new RandomAccessFile(path,"rw");
  try {
   FileLock l;try{l=h.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("LEGACY_WORKER_BUSY",e);}
   if(l==null)throw new IOException("LEGACY_WORKER_BUSY");
   return new LegacyWorkerGuard(h,l);
  }catch(IOException e){h.close();throw e;}
 }
 public void verify()throws IOException {if(!lock.isValid()||!handle.getChannel().isOpen())throw new IOException("LEGACY_LOCK_LOST");}
 public void close()throws IOException {try{lock.release();}finally{handle.close();}}
}
