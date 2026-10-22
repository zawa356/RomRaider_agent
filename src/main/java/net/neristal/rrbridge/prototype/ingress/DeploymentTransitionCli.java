package net.neristal.rrbridge.prototype.ingress;

/**
 * Process entry for {@link DeploymentTransition#run}, kept out of DeploymentTransition so the single entry's dependency
 * closure carries no secondary main (Phase VIII RLDI1). POSIX reader only; Windows physical-identity reading stays in
 * the test-only native wrapper (as for Rlc2IntegrationCli).
 */
public final class DeploymentTransitionCli {
    public static void main(String[] a) {System.exit(DeploymentTransition.run(a,WatermarkV2Prototype.POSIX_READER));}
    private DeploymentTransitionCli(){}
}
