package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;

/** Deliberate offline invocation, no background mode or persistent ACK handling. */
public final class Rlc2IntegrationCli {
    private Rlc2IntegrationCli(){}
    public static int run(String[] args,WatermarkV2Prototype.ReadOpener reader){
        if(args.length!=10||!"--enable-synthetic-integration".equals(args[0])||!"--quiescent".equals(args[1])){
            System.out.println("{\"contract_version\":2,\"status\":\"INVALID_INVOCATION\",\"ack_eligible\":false}");return 2;
        }
        try{
            File dir=new File(args[2]);Rlc2IntegrationStore.Admission a=new Rlc2IntegrationStore.Admission(dir.toPath(),args[8],new HashSet<String>(Arrays.asList(args[9].split(",",-1))));
            Rlc2PreviewStore.Result r=new Rlc2IntegrationStore(true,new File(args[7])).ingest(true,a,dir.toPath(),new File(args[3]),new File(args[4]),args[5],args[6],reader);
            System.out.println("{\"contract_version\":2,\"status\":\"STORED_OFFLINE\",\"records_added\":"+r.recordsAdded+",\"receipts_added\":"+r.receiptsAdded+",\"ack_eligible\":false,\"directory_durability\":\"UNKNOWN\"}");return 0;
        }catch(Exception review){System.out.println("{\"contract_version\":2,\"status\":\"REVIEW_REQUIRED\",\"ack_eligible\":false}");return 5;}
    }
    public static void main(String[] args){System.exit(run(args,WatermarkV2Prototype.POSIX_READER));}
}
