package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class Rlc2LaneCompetitionTest {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static String java(){return new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
 static Process launch(String mode,Rlc2ProductAdapterTest.Fixture f,File old,File out,File root,Rlc2IntegrationStore.Admission a)throws Exception {
  return new ProcessBuilder(java(),"-cp",System.getProperty("java.class.path"),Rlc2LaneCompetitionTest.class.getName(),mode,f.dir.toString(),f.db.toString(),old.toString(),out.toString(),root.toString(),a.manifestSession,String.join(",",a.sourceUuids)).redirectErrorStream(true).redirectOutput(new File(root,mode+".log")).start();
 }
 public static void main(final String[] args)throws Exception {
  if(args.length>0){
   final Path root=Paths.get(args[5]);
   try {
    new Rlc2IntegrationStore(true,new File(args[4])).ingestPublished(true,root.toFile(),Rlc2ProductAdapterTest.SESSION,new Rlc2IntegrationStore.Admission(Paths.get(args[1]),args[6],new HashSet<String>(Arrays.asList(args[7].split(",")))),Paths.get(args[1]),new File(args[2]),new File(args[3]),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.environment().reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage)throws Exception {
     if("hold".equals(args[0])&&stage==Rlc2PreviewStore.Stage.BEFORE_COMMIT){Files.write(root.resolve("ready"),new byte[]{1});long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!Files.exists(root.resolve("release"))&&System.nanoTime()<until)Thread.sleep(20);if(!Files.exists(root.resolve("release")))throw new IOException("test release timeout");}
    }});
   }catch(IOException busy){if("LANE_BUSY".equals(busy.getMessage())){System.out.println("LANE_BUSY");System.exit(85);}throw busy;}return;
  }
  Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
  File root=Files.createTempDirectory("romlogger-engine-competition-").toFile(),old1=Rlc2IntegrationStoreTest.legacy(),old2=Rlc2IntegrationStoreTest.legacy(),out1=Rlc2IntegrationStoreTest.target(),out2=Rlc2IntegrationStoreTest.target();Rlc2IntegrationStore.Admission a=Rlc2IntegrationStoreTest.admit(f);
  new Rlc2IntegrationStore(true,out1);new Rlc2IntegrationStore(true,out2);Process holder=launch("hold",f,old1,out1,root,a);
  try {
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!Files.exists(root.toPath().resolve("ready"))&&holder.isAlive()&&System.nanoTime()<until)Thread.sleep(20);
   check(Files.exists(root.toPath().resolve("ready")),"first actual Engine holds lane before output commit");
   Process competitor=launch("compete",f,old2,out2,root,a);try{check(competitor.waitFor(15,TimeUnit.SECONDS)&&competitor.exitValue()==85,"second Engine in another JVM/legacy DB/output refuses same lane");}finally{if(competitor.isAlive())competitor.destroyForcibly();}
   check(Rlc2PreviewStoreTest.count(out2,"SELECT count(*) FROM preview_records")==0,"losing Engine commits no output rows");
   Files.write(root.toPath().resolve("release"),new byte[]{1});check(holder.waitFor(15,TimeUnit.SECONDS)&&holder.exitValue()==0,"first Engine completes normal commit after release");
   check(Rlc2PreviewStoreTest.count(out1,"SELECT count(*) FROM preview_records")==3,"winning Engine commits exact source set");
   Process retry=launch("retry",f,old2,out2,root,a);try{check(retry.waitFor(15,TimeUnit.SECONDS)&&retry.exitValue()==0,"explicit second Engine retry after release completes");}finally{if(retry.isAlive())retry.destroyForcibly();}
   check(Rlc2PreviewStoreTest.count(out2,"SELECT count(*) FROM preview_records")==3,"retry stores all records once");
  }finally{Files.write(root.toPath().resolve("release"),new byte[]{1});if(holder.isAlive()){holder.destroyForcibly();holder.waitFor(15,TimeUnit.SECONDS);}}
  System.out.println("Separate Engine competition passed "+checks+" checks; safety and progress verified separately");
 }
}
