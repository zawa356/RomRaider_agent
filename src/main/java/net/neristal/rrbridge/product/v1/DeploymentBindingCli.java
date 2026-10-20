package net.neristal.rrbridge.product.v1;

import java.io.File;

/**
 * Read-only RLDB1 CLI ({@code --inspect-synthetic --external-single-active} + 10 layout fields), kept out of
 * {@link DeploymentBinding} so the single entry's dependency closure carries no secondary main (Phase VIII RLDI1).
 * Exit 0 bound, 2 usage, 5 review required.
 */
public final class DeploymentBindingCli {
 public static void main(String[] args) {
  if(args.length!=12||!"--inspect-synthetic".equals(args[0])||!"--external-single-active".equals(args[1])){System.out.println(DeploymentBinding.json("REVIEW_REQUIRED","USAGE"));System.exit(2);return;}
  try {DeploymentBinding.verify(true,true,new DeploymentBinding.Layout(new File(args[2]),new File(args[3]),new File(args[4]),new File(args[5]),new File(args[6]),new File(args[7]),args[8],args[9],args[10],args[11]));System.out.println(DeploymentBinding.json("READY_FOR_SYNTHETIC_TEST","BOUND_LOCAL_DEPLOYMENT"));}
  catch(Exception e){String reason=e instanceof DeploymentBinding.Refusal?e.getMessage():"INSPECTION_UNAVAILABLE";System.out.println(DeploymentBinding.json("REVIEW_REQUIRED",reason));System.exit(5);}
 }
 private DeploymentBindingCli(){}
}
