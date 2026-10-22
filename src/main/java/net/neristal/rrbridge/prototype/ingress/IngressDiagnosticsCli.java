package net.neristal.rrbridge.prototype.ingress;

/**
 * Process entry for {@link IngressDiagnostics#run}, kept out of IngressDiagnostics so the single entry's dependency
 * closure carries no secondary main (Phase VIII RLDI1). POSIX identity; a native Windows ReadOpener can be supplied via the Java API.
 */
public final class IngressDiagnosticsCli {
    public static void main(String[] args) {System.exit(IngressDiagnostics.run(args,System.out,System.err));}
    private IngressDiagnosticsCli(){}
}
