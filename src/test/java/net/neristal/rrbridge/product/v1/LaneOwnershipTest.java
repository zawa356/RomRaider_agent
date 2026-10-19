package net.neristal.rrbridge.product.v1;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
public final class LaneOwnershipTest {
 static int checks; static final String D="8d034ae6-32ac-4d51-bdcb-4cf7cc199540",V="8d034ae6-32ac-4d51-bdcb-4cf7cc199541";
 interface Action{void run()throws Exception;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static void reject(Action a,String s)throws Exception{try{a.run();}catch(Exception e){check(true,s);return;}throw new AssertionError(s);}
 static Process child(File root,String session,Path ready)throws Exception {
  File java=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
  return new ProcessBuilder(java.toString(),"-cp",System.getProperty("java.class.path"),LaneOwnershipTest.class.getName(),"hold",root.toString(),session,ready.toString()).redirectErrorStream(true).redirectOutput(ready.resolveSibling("child.log").toFile()).start();
 }
 static LaneOwnership recover(File root)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(true)try{return LaneOwnership.acquire(true,root,D,V,"s");}catch(java.io.IOException e){if(!"LANE_BUSY".equals(e.getMessage())||System.nanoTime()>end)throw e;Thread.sleep(20);}
 }
 public static void main(String[] args)throws Exception {
  if(args.length>0){try(LaneOwnership l=LaneOwnership.acquire(true,new File(args[1]),D,V,args[2])){Files.write(new File(args[3]).toPath(),new byte[]{1});Thread.sleep(30000);}return;}
  final File root=Files.createTempDirectory("romlogger-lane-").toFile();
  reject(new Action(){public void run()throws Exception{LaneOwnership.acquire(false,root,D,V,"s");}},"disabled before ownership");
  final LaneOwnership first=LaneOwnership.acquire(true,root,D,V,"s");
  check(first.generation==1,"initial generation one");first.verify();
  reject(new Action(){public void run()throws Exception{LaneOwnership.acquire(true,root,D,V,"s");}},"same JVM conflict");
  first.verify();check(true,"failed duplicate does not release first owner");
  try(LaneOwnership other=LaneOwnership.acquire(true,root,D,V,"other")){other.verify();check(true,"different session progresses concurrently");}
  first.close();first.close();
  reject(new Action(){public void run()throws Exception{first.verify();}},"closed owner cannot commit");
  try(LaneOwnership next=LaneOwnership.acquire(true,root,D,V,"s")){check(next.generation==2&&!next.owner.equals(first.owner),"new UUID and monotonic generation; no PID identity");}
  Path ready=root.toPath().resolve("ready");Process holder=child(root,"s",ready);
  try {
   long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!Files.exists(ready)&&holder.isAlive()&&System.nanoTime()<end)Thread.sleep(20);
   check(Files.exists(ready),"child JVM acquired ownership");
   reject(new Action(){public void run()throws Exception{LaneOwnership.acquire(true,root,D,V,"s");}},"separate JVM conflict refuses");
   try(LaneOwnership other=LaneOwnership.acquire(true,root,D,V,"parallel")){check(true,"different JVM session remains available");}
  }finally{holder.destroyForcibly();check(holder.waitFor(15,TimeUnit.SECONDS),"forced child termination completes");}
  try(LaneOwnership recovered=recover(root)){check(recovered.generation==4,"kernel releases halted owner; generation advances");}
  reject(new Action(){public void run()throws Exception{first.verify();}},"stale token remains fenced after recovery");
  String key=D+"|"+V+"|s";Path lock=root.toPath().resolve(UUID.nameUUIDFromBytes(key.getBytes("UTF-8"))+".lane.lock");
  Files.write(lock,"RLLO99\n".getBytes("UTF-8"));
  reject(new Action(){public void run()throws Exception{LaneOwnership.acquire(true,root,D,V,"s");}},"unknown version refuses without takeover");
  check(new String(Files.readAllBytes(lock),"UTF-8").equals("RLLO99\n"),"unknown evidence preserved");
  final LaneOwnership changed=LaneOwnership.acquire(true,root,D,V,"changed");
  java.lang.reflect.Field field=LaneOwnership.class.getDeclaredField("handle");field.setAccessible(true);
  java.io.RandomAccessFile handle=(java.io.RandomAccessFile)field.get(changed);handle.seek(handle.length()-2);handle.write('9');
  reject(new Action(){public void run()throws Exception{changed.verify();}},"changed generation evidence fences current owner");
  changed.close();
  reject(new Action(){public void run()throws Exception{changed.verify();}},"lost OS lock cannot verify");
  reject(new Action(){public void run()throws Exception{LaneOwnership.acquire(true,new File(root,"missing"),D,V,"x");}},"missing root not created");
  check(root.listFiles().length>=4,"persistent lock files retained");
  System.out.println("Lane ownership passed "+checks+" checks; no expiring lease; power loss NOT RUN");
 }
}
