package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/** Actual child JVMs, using the parent's actual Java runtime and explicit admissions. */
public final class Rlc2IntegrationFaultTest {
    static int checks;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
    static Process child(Rlc2ProductAdapterTest.Fixture f,File legacy,File output,String stage,Path log)throws Exception{
        Rlc2IntegrationStore.Admission a=Rlc2IntegrationStoreTest.admit(f);
        String executable=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")?"java.exe":"java").toString();
        return new ProcessBuilder(executable,"-cp",System.getProperty("java.class.path"),Rlc2IntegrationFaultTest.class.getName(),"child",f.dir.toString(),f.db.toString(),legacy.toString(),output.toString(),a.manifestSession,String.join(",",a.sourceUuids),stage).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){
            try{
                final String stage=args[7];Path dir=new File(args[1]).toPath();
                Rlc2IntegrationStore.Admission a=new Rlc2IntegrationStore.Admission(dir,args[5],new HashSet<String>(Arrays.asList(args[6].split(","))));
                new Rlc2IntegrationStore(true,new File(args[4])).ingest(true,a,dir,new File(args[2]),new File(args[3]),Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,Rlc2ProductAdapterTest.environment().reader(),new Rlc2PreviewStore.Hook(){public void at(Rlc2PreviewStore.Stage s){if(s.name().equals(stage))Runtime.getRuntime().halt(77);}});
                System.out.println("INTEGRATION_COMPLETED ACK_ELIGIBLE=false");return;
            }catch(Exception review){System.out.println("INTEGRATION_REVIEW_REQUIRED ACK_ELIGIBLE=false");System.exit(5);return;}
        }
        Rlc2ProductAdapterTest.Fixture f=new Rlc2ProductAdapterTest.Fixture(Rlc2ProductAdapterTest.environment(),true,Rlc2ProductAdapterTest.SESSION,1);
        File legacy=Rlc2IntegrationStoreTest.legacy();java.util.Map<String,String> originals=Rlc2PreviewStoreTest.hashes(f.dir);byte[] oldBytes=Files.readAllBytes(legacy.toPath());
        String bits=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(bits!=null)check(bits.equals(System.getProperty("sun.arch.data.model")),"integration faults actual JVM architecture");
        for(Rlc2PreviewStore.Stage stage:Rlc2PreviewStore.Stage.values()){
            File output=Rlc2IntegrationStoreTest.target();new Rlc2IntegrationStore(true,output);
            Process p=child(f,legacy,output,stage.name(),output.toPath().resolveSibling("halt.log"));
            boolean done=p.waitFor(50,TimeUnit.SECONDS);if(!done)p.destroyForcibly();check(done&&p.exitValue()==77,"separate JVM halt at "+stage);
            Rlc2IntegrationStore reopened=new Rlc2IntegrationStore(true,output);
            boolean committed=stage==Rlc2PreviewStore.Stage.AFTER_COMMIT;
            check(Rlc2PreviewStoreTest.count(output,"SELECT count(*) FROM preview_records")==(committed?3:0),"whole candidate record transaction at "+stage);
            check(Rlc2PreviewStoreTest.count(output,"SELECT count(*) FROM integration_admissions")==(committed?2:0),"whole source admission transaction at "+stage);
            check(Rlc2IntegrationStoreTest.ingest(reopened,f,legacy).recordsAdded==(committed?0:3)&&Rlc2IntegrationStoreTest.ingest(reopened,f,legacy).recordsAdded==0,"restart idempotency after "+stage);
        }
        File concurrent=Rlc2IntegrationStoreTest.target();new Rlc2IntegrationStore(true,concurrent);
        Process one=child(f,legacy,concurrent,"NONE",concurrent.toPath().resolveSibling("worker1.log"));
        Process two=child(f,legacy,concurrent,"NONE",concurrent.toPath().resolveSibling("worker2.log"));
        boolean finished1=one.waitFor(50,TimeUnit.SECONDS),finished2=two.waitFor(50,TimeUnit.SECONDS);
        if(!finished1)one.destroyForcibly();if(!finished2)two.destroyForcibly();
        check(finished1&&finished2&&(one.exitValue()==0||two.exitValue()==0),"separate workers finish with at least one whole commit");
        check(Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_records")==3&&Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_receipts")==4&&Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM integration_admissions")==2,"separate workers converge on exact records receipts admissions");
        check(originals.equals(Rlc2PreviewStoreTest.hashes(f.dir))&&Arrays.equals(oldBytes,Files.readAllBytes(legacy.toPath())),"all child halts and workers preserve original and legacy bytes");
        check(Rlc2PreviewStoreTest.count(concurrent,"SELECT count(*) FROM preview_records WHERE ack_eligible<>0")==0,"fault recovery never enables ACK");
        System.out.println("RLC2 integration JVM faults passed "+checks+" checks; halt is not physical power loss");
    }
}
