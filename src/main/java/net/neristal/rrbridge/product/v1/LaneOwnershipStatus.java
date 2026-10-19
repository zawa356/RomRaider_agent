package net.neristal.rrbridge.product.v1;
import java.io.File;
/** Bounded read-only operator diagnostic; status never authorizes recovery. */
public final class LaneOwnershipStatus {
 public static void main(String[] args) {
  if(args.length!=5||!"--status".equals(args[0])){System.err.println("Explicit status arguments required");System.exit(2);return;}
  String state=LaneOwnership.diagnose(new File(args[1]),args[2],args[3],args[4]);
  System.out.println("{\"contract_version\":1,\"state\":\""+state+"\",\"automatic_recovery\":false,\"ack_eligible\":false}");
  System.exit("IDLE".equals(state)?0:"BUSY".equals(state)?3:"MISSING".equals(state)?4:"INVALID".equals(state)?5:6);
 }
}
