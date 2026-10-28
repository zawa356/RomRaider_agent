package net.neristal.rrbridge.product.v1;

import java.io.*;

/**
 * Phase X-E2: a DRIVE_TYPE_UNPROVEN refusal must carry the probe failure kind and ms into an uncaught stack trace, so a
 * CI failure (C4: Phase8ReadinessCliTest:90, runs 40300936878/40429872972 attempt 1, gaps 35.3 s / 30.4 s) can be
 * classified from the log. The refusal code, the accepted reply and the timeout are unchanged. Injected probes only.
 */
public final class PhaseXeDriveDiagnosticTest {
    static int checks;
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);checks++;System.out.println("PASS "+what);}
    static Exception refusal(WindowsDriveGate.Probe probe){try{WindowsDriveGate.requireFixed("C:\\",probe);return null;}catch(Exception e){return e;}}
    static String trace(Throwable t){StringWriter w=new StringWriter();t.printStackTrace(new PrintWriter(w,true));return w.toString();}
    static WindowsDriveGate.Probe failing(final IOException e){return new WindowsDriveGate.Probe(){public String query(String root)throws IOException{throw e;}};}
    static WindowsDriveGate.Probe replying(final String reply){return new WindowsDriveGate.Probe(){public String query(String root){return reply;}};}

    public static void main(String[] args)throws Exception {
        for(String kind:new String[]{"TIMEOUT","START","STDIN","EXIT_3","OUTPUT_BOUND","INTERRUPTED"}) {
            WindowsDriveGate.ProbeFailure failure=new WindowsDriveGate.ProbeFailure(kind,30012);Exception r=refusal(failing(failure));
            check(r instanceof DeploymentBinding.Refusal&&"DRIVE_TYPE_UNPROVEN".equals(r.getMessage()),kind+": refusal code unchanged DRIVE_TYPE_UNPROVEN");
            check(r.getCause()==failure&&"DRIVE_TYPE_UNPROVEN".equals(failure.getMessage()),kind+": the probe failure is the cause; its message stays the stable code");
            String text=trace(r);
            check(text.contains("probe="+kind+" ms=30012"),kind+": uncaught stack trace shows probe kind and ms");
            check(!text.contains(":\\")&&!text.contains("powershell"),kind+": trace carries no path or program text");
        }
        // A probe exception that is not the gate's own diagnostic may hold arbitrary text: never chained.
        Exception other=refusal(failing(new IOException("C:\\Users\\someone\\secret-fixture")));
        check(other instanceof DeploymentBinding.Refusal&&"DRIVE_TYPE_UNPROVEN".equals(other.getMessage())&&other.getCause()==null,"foreign probe exception: DRIVE_TYPE_UNPROVEN without a chained cause");
        check(!trace(other).contains("secret-fixture"),"foreign probe exception text never reaches the trace");
        // Replies are judged exactly as before and carry no cause.
        Exception removable=refusal(replying("2|LOCAL\n"));
        check(removable instanceof DeploymentBinding.Refusal&&"LOCAL_FIXED_DRIVE_REQUIRED".equals(removable.getMessage())&&removable.getCause()==null,"non-fixed reply still LOCAL_FIXED_DRIVE_REQUIRED, no cause");
        Exception alias=refusal(replying("3|ALIAS\n"));
        check(alias instanceof DeploymentBinding.Refusal&&"LOCAL_FIXED_DRIVE_REQUIRED".equals(alias.getMessage()),"alias reply still refused");
        check(refusal(replying("3|LOCAL\n"))==null&&refusal(replying("3|LOCAL\r\n"))==null,"only the exact local fixed reply is accepted (unchanged)");
        check(WindowsDriveGate.PROBE_TIMEOUT_MILLIS==30000,"probe wait bound unchanged at 30 s (no timeout extension)");
        System.out.println("Phase X-E2 drive diagnostic passed "+checks+" checks; injected probes only");
    }
}
