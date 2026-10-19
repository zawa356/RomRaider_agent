package net.neristal.rrbridge.prototype.ingress;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class Rlc2LaneFaultTest {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static String java(){return new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
 public static void main(String[] args)throws Exception {
  if(args.length>0){
   final Rlc2PreviewStore.Stage halt=Rlc2PreviewStore.Stage.valueOf(args[0]);
   Rlc2IntegrationStore store=new Rlc2IntegrationStore(true,new File(args[4]));
   Set<String> ids=new HashSet<String>(Arrays.asList(args[7].split(",")));
   store.ingestPublished(true,new File(args[5]),Rlc2ProductAdapterTest.SESSION,new Rlc2IntegrationStore.Admission(Paths.get(args[1]),args[6],ids),Paths.get(args[1]),new File(args[2]),new File(args[3]),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.environment().reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage stage){if(stage==halt)Runtime.getRuntime().halt(83);}});throw new AssertionError("halt not reached");
  }
  Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),false,Rlc2ProductAdapterTest.SESSION,1);
  File old=Rlc2IntegrationStoreTest.legacy(),root=Files.createTempDirectory("romlogger-lane-fault-").toFile();byte[] oldBytes=Files.readAllBytes(old.toPath());Map<String,String> originals=Rlc2PreviewStoreTest.hashes(f.dir);
  Rlc2IntegrationStore.Admission admission=Rlc2IntegrationStoreTest.admit(f);
  for(Rlc2PreviewStore.Stage stage:Rlc2PreviewStore.Stage.values()) {
   File out=Rlc2IntegrationStoreTest.target();new Rlc2IntegrationStore(true,out);
   Process child=new ProcessBuilder(java(),"-cp",System.getProperty("java.class.path"),Rlc2LaneFaultTest.class.getName(),stage.name(),f.dir.toString(),f.db.toString(),old.toString(),out.toString(),root.toString(),admission.manifestSession,String.join(",",admission.sourceUuids)).redirectErrorStream(true).redirectOutput(new File(root,stage+".log")).start();
   check(child.waitFor(30,TimeUnit.SECONDS)&&child.exitValue()==83,"cooperative child halted at "+stage);
   // New output opener performs its owned SQLite rollback recovery, never original repair.
   Rlc2IntegrationStore resumed=new Rlc2IntegrationStore(true,out);
   long rows=Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records");
   check(rows==(stage==Rlc2PreviewStore.Stage.AFTER_COMMIT?3:0),"all-or-none output after halt "+stage);
   Rlc2PreviewStore.Result result=resumed.ingestPublished(true,root,Rlc2ProductAdapterTest.SESSION,admission,f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT);
   check(result.recordsAdded==(stage==Rlc2PreviewStore.Stage.AFTER_COMMIT?0:3),"actual retained locks recover and import completes "+stage);
   check(resumed.ingestPublished(true,root,Rlc2ProductAdapterTest.SESSION,admission,f.dir,f.db.toFile(),old,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,f.env.reader(),Rlc2PreviewStore.NO_FAULT).recordsAdded==0,"replay exact after cooperative halt "+stage);
   check(Rlc2PreviewStoreTest.count(out,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"no ACK after halt "+stage);
  }
  check(originals.equals(Rlc2PreviewStoreTest.hashes(f.dir))&&Arrays.equals(oldBytes,Files.readAllBytes(old.toPath())),"all old ACK/source evidence unchanged across child halts");
  System.out.println("Cooperative lane fault suite passed "+checks+" checks; Runtime.halt is not power loss");
 }
}
