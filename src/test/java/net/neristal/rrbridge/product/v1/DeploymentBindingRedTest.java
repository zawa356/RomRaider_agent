package net.neristal.rrbridge.product.v1;
import java.nio.file.Files;
public final class DeploymentBindingRedTest {
 public static void main(String[] args)throws Exception {
  try(LaneOwnership a=LaneOwnership.acquire(true,Files.createTempDirectory("phase3-red-a").toFile(),LaneOwnershipTest.D,LaneOwnershipTest.V,"same-session")) {
   boolean rejected=false;
   try(LaneOwnership b=LaneOwnership.acquire(true,Files.createTempDirectory("phase3-red-b").toFile(),LaneOwnershipTest.D,LaneOwnershipTest.V,"same-session")) {}catch(java.io.IOException expected){rejected=true;}
   if(!rejected)throw new AssertionError("UNBOUND_ROOT: same logical session simultaneously acquired in different roots");
  }
 }
}
