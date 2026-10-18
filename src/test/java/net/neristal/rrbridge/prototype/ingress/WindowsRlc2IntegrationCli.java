package net.neristal.rrbridge.prototype.ingress;
/** Test-only native opener; adds no JNA dependency to rrbridge.jar. */
public final class WindowsRlc2IntegrationCli {
    public static void main(String[] args)throws Exception{System.exit(Rlc2IntegrationCli.run(args,Rlc2ProductAdapterTest.environment().reader()));}
}
