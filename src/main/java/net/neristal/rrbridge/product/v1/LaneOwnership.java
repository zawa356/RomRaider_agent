package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Retained cooperative local lock. No expiry, force takeover, PID test or ACK. */
public final class LaneOwnership implements AutoCloseable {
 private static final Set<String> OPEN=new HashSet<String>();
 private final Path path;
 private final Object identity;
 private final RandomAccessFile handle;
 private final FileLock lock;
 private final byte[] evidence;
 public final String owner;
 public final long generation;
 private boolean closed;
 private LaneOwnership(Path path,Object identity,RandomAccessFile handle,FileLock lock,byte[] evidence,String owner,long generation) {
  this.path=path;this.identity=identity;this.handle=handle;this.lock=lock;this.evidence=evidence;this.owner=owner;this.generation=generation;
 }
 public static LaneOwnership acquire(boolean enabled,File root,String device,String vehicle,String session)throws Exception {
  if(!enabled)throw new IOException("LANE_DISABLED");
  if(session==null||session.isEmpty()||session.getBytes(StandardCharsets.UTF_8).length>1024||session.indexOf('\n')>=0||session.indexOf('\r')>=0)throw new IOException("LANE_INVALID_KEY");
  String key=UUID.fromString(device).toString()+"|"+UUID.fromString(vehicle).toString()+"|"+session;
  Path directory=root.toPath().toAbsolutePath().normalize();
  safe(directory);directory=root.getCanonicalFile().toPath();
  safe(directory);
  if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("LANE_ROOT_REQUIRED");
  Path path=directory.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString()+".lane.lock");
  safe(path);
  synchronized(OPEN){if(!OPEN.add(path.toString()))throw new IOException("LANE_BUSY");}
  RandomAccessFile h=null;FileLock l=null;
  try {
   h=new RandomAccessFile(path.toFile(),"rw");
   try{l=h.getChannel().tryLock();}catch(OverlappingFileLockException e){throw new IOException("LANE_BUSY",e);}
   if(l==null)throw new IOException("LANE_BUSY");
   Object id=identity(path);
   long generation=0;
   if(h.length()!=0){
    if(h.length()>4096)throw new IOException("LANE_UNKNOWN_FORMAT");
    byte[] old=new byte[(int)h.length()];h.seek(0);h.readFully(old);
    String[] parts=new String(old,StandardCharsets.UTF_8).split("\n",-1);
    if(parts.length!=5||!parts[0].equals("RLLO1")||!parts[1].equals(java.util.Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)))||!parts[4].isEmpty())throw new IOException("LANE_UNKNOWN_FORMAT");
    UUID.fromString(parts[2]);generation=Long.parseLong(parts[3]);
    if(generation<1||generation==Long.MAX_VALUE)throw new IOException("LANE_GENERATION_INVALID");
   }
   String owner=UUID.randomUUID().toString();generation++;
   byte[] evidence=("RLLO1\n"+java.util.Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8))+"\n"+owner+"\n"+generation+"\n").getBytes(StandardCharsets.UTF_8);
   h.seek(0);h.write(evidence);h.setLength(evidence.length);h.getChannel().force(true);
   LaneOwnership result=new LaneOwnership(path,id,h,l,evidence,owner,generation);result.verify();return result;
  }catch(Exception e){try{if(l!=null)l.release();}finally{if(h!=null)h.close();synchronized(OPEN){OPEN.remove(path.toString());}}throw e;}
 }
 private static Object identity(Path path)throws IOException {
  BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
  return a.fileKey()==null?a.creationTime():a.fileKey();
 }
 private static void safe(Path path)throws IOException {
  for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("LANE_UNSAFE_PATH");
 }
 public boolean matches(String device,String vehicle,String session) {
  String key=UUID.fromString(device).toString()+"|"+UUID.fromString(vehicle).toString()+"|"+session;
  return new String(evidence,StandardCharsets.UTF_8).split("\n",-1)[1].equals(java.util.Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)));
 }
 public synchronized void verify()throws IOException {
  if(closed||!lock.isValid()||!handle.getChannel().isOpen())throw new IOException("LANE_LOST");
  safe(path);
  if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!identity.equals(identity(path)))throw new IOException("LANE_IDENTITY_CHANGED");
  if(handle.length()!=evidence.length)throw new IOException("LANE_OWNER_CHANGED");
  byte[] current=new byte[evidence.length];handle.seek(0);handle.readFully(current);
  if(!Arrays.equals(current,evidence))throw new IOException("LANE_OWNER_CHANGED");
 }
 public synchronized void close()throws IOException {
  if(closed)return;closed=true;
  try{lock.release();}finally{try{handle.close();}finally{synchronized(OPEN){OPEN.remove(path.toString());}}}
 }
}
