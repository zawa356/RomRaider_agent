package net.neristal.rrbridge.prototype.ingress;

/** Isolated Windows CLI: reuses the already reviewed test-only JNA native read opener. */
public final class WindowsIngressDiagnosticsCli {
    public static void main(String[] args) {
        System.exit(IngressDiagnostics.run(args,System.out,System.err,WindowsWatermarkV2Test.READER));
    }
}
