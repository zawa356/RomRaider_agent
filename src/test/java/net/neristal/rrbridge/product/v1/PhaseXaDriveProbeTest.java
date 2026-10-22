package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Phase X-A: WindowsDriveGate probe failure classes and real-probe latency, without weakening the gate.
 * Child JVMs simulate slow / exiting / flooding / partial / silent probes with SHORT injected bounds (no 10 s waits).
 * On Windows the real SYSTEM probe is measured N times (codes and milliseconds only) and an unused drive letter must be
 * refused. Results are printed as DRIVE_PROBE_DIAG lines and, when GITHUB_STEP_SUMMARY is set, appended there.
 */
public final class PhaseXaDriveProbeTest {
    static int checks;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}
    static Process child(String mode)throws IOException {
        return new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),PhaseXaDriveProbeTest.class.getName(),mode).redirectErrorStream(true).start();
    }
    static Object run(String mode,long timeout){
        Process p=null;
        try{p=child(mode);return WindowsDriveGate.reply(p,timeout,512);}
        catch(WindowsDriveGate.ProbeFailure f){return f;}
        catch(IOException other){return other;}
        finally{if(p!=null&&p.isAlive()){p.destroyForcibly();try{p.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    }
    static String kind(Object o){return o instanceof WindowsDriveGate.ProbeFailure?((WindowsDriveGate.ProbeFailure)o).kind:"RETURNED:"+o;}

    public static void main(String[] a)throws Exception {
        if(a.length==1){
            if(a[0].equals("sleep"))Thread.sleep(3000);
            else if(a[0].equals("exit3"))System.exit(3);
            else if(a[0].equals("flood")){for(int i=0;i<4000;i++)System.out.print('x');System.out.flush();}
            else if(a[0].equals("slowok")){Thread.sleep(300);System.out.print("3|LOCAL\n");}
            else if(a[0].equals("partial")){System.out.print("3|LO");System.out.flush();System.exit(1);}
            return;// "silent": exit 0, no output
        }
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);

        // ---- failure classes with short injected bounds.
        long t0=System.nanoTime();Object r=run("sleep",250);long took=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t0);
        check("TIMEOUT".equals(kind(r))&&((WindowsDriveGate.ProbeFailure)r).elapsedMillis>=200&&took<2500,"slow probe → TIMEOUT at the injected 250 ms bound ("+took+" ms), not after the child finished");
        check("EXIT_3".equals(kind(run("exit3",5000))),"nonzero probe exit → EXIT_3");
        check("OUTPUT_BOUND".equals(kind(run("flood",5000))),"oversized probe output → OUTPUT_BOUND");
        check("EXIT_1".equals(kind(run("partial",5000))),"partial output then exit 1 → EXIT_1 (partial text never returned)");
        check("RETURNED:".equals(kind(run("silent",5000))),"exit 0 without output returns empty text");
        check("RETURNED:3|LOCAL\n".equals(kind(run("slowok",5000))),"probe answering within the bound is accepted (bound not over-eager)");
        // ---- contract: every probe failure is DRIVE_TYPE_UNPROVEN; malformed/empty replies are refused; no cache.
        for(final String k:new String[]{"TIMEOUT","EXIT_3","OUTPUT_BOUND","START","STDIN","INTERRUPTED"}){
            String code;try{WindowsDriveGate.requireFixed("C:\\",new WindowsDriveGate.Probe(){public String query(String root)throws IOException{throw new WindowsDriveGate.ProbeFailure(k,1);}});code="ACCEPTED";}
            catch(DeploymentBinding.Refusal refusal){code=refusal.getMessage();}
            check("DRIVE_TYPE_UNPROVEN".equals(code),"probe failure "+k+" → DRIVE_TYPE_UNPROVEN (unchanged contract)");
        }
        for(String reply:new String[]{"","3|LO","4|LOCAL\n","2|LOCAL\n","3|ALIAS\n","3|LOCAL\nextra\n"}){
            final String rr=reply;String code;
            try{WindowsDriveGate.requireFixed("C:\\",new WindowsDriveGate.Probe(){public String query(String root){return rr;}});code="ACCEPTED";}catch(DeploymentBinding.Refusal refusal){code=refusal.getMessage();}
            check("LOCAL_FIXED_DRIVE_REQUIRED".equals(code),"reply '"+reply.replace("\n","\\n")+"' refused LOCAL_FIXED_DRIVE_REQUIRED");
        }
        final int[] calls={0};
        WindowsDriveGate.Probe flapping=new WindowsDriveGate.Probe(){public String query(String root)throws IOException{if(calls[0]++%2==1)throw new WindowsDriveGate.ProbeFailure("TIMEOUT",1);return "3|LOCAL\n";}};
        WindowsDriveGate.requireFixed("C:\\",flapping);String second;try{WindowsDriveGate.requireFixed("C:\\",flapping);second="ACCEPTED";}catch(DeploymentBinding.Refusal refusal){second=refusal.getMessage();}
        check(calls[0]==2&&"DRIVE_TYPE_UNPROVEN".equals(second),"no result cache: a later failing probe is refused after an earlier success");
        check("INVALID_ROOT|0".equals(WindowsDriveGate.diagnose("C:"))&&"INVALID_ROOT|0".equals(WindowsDriveGate.diagnose("\\\\host\\share\\")),"diagnose refuses non-root input without a subprocess");

        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("NOT_RUN real Windows drive probe (non-Windows host)");System.out.println("Phase X-A drive probe checks="+checks);return;}
        // ---- real probe latency (codes + ms only).
        int n=Integer.parseInt(System.getenv().getOrDefault("ROMLOGGER_DRIVE_DIAG_N","5"));
        Set<String> roots=new TreeSet<String>();roots.add(System.getenv("SystemDrive")+"\\");
        roots.add(Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().getRoot().toString().toUpperCase(Locale.ROOT));
        roots.add(Paths.get("").toAbsolutePath().getRoot().toString().toUpperCase(Locale.ROOT));
        List<String> lines=new ArrayList<String>();List<Long> ms=new ArrayList<Long>();int bad=0;
        for(String root:roots)for(int i=0;i<n;i++){
            String d=WindowsDriveGate.diagnose(root);String[] kv=d.split("\\|");long m=Long.parseLong(kv[1]);ms.add(m);if(!"OK_LOCAL_FIXED".equals(kv[0]))bad++;
            String line="DRIVE_PROBE_DIAG model="+System.getProperty("sun.arch.data.model")+" root="+root.charAt(0)+" i="+i+" kind="+kv[0]+" ms="+m;lines.add(line);System.out.println(line);
        }
        Collections.sort(ms);String stats="DRIVE_PROBE_STATS model="+System.getProperty("sun.arch.data.model")+" n="+ms.size()+" min="+ms.get(0)+" median="+ms.get(ms.size()/2)+" max="+ms.get(ms.size()-1)+" not_ok="+bad+" bound_ms="+WindowsDriveGate.PROBE_TIMEOUT_MILLIS;
        System.out.println(stats);
        String summary=System.getenv("GITHUB_STEP_SUMMARY");
        if(summary!=null){StringBuilder b=new StringBuilder("\n```\n");for(String l:lines)b.append(l).append('\n');b.append(stats).append("\n```\n");
            Files.write(Paths.get(summary),b.toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND,StandardOpenOption.CREATE);}
        // ---- PowerShell selection: Sysnative (64-bit) only from a 32-bit JVM; both give the same reply for the same root.
        String sr=System.getenv("SystemRoot");File chosen=WindowsDriveGate.powershell(sr);
        boolean jvm32="32".equals(System.getProperty("sun.arch.data.model"));
        check(jvm32==chosen.getPath().toLowerCase(Locale.ROOT).contains("sysnative"),"probe uses Sysnative PowerShell exactly when the JVM is 32-bit ("+(jvm32?"x86":"x64")+")");
        File sys32=new File(sr,"System32\\WindowsPowerShell\\v1.0\\powershell.exe");String sysDrive=System.getenv("SystemDrive")+"\\";
        if(jvm32){long t=System.nanoTime();String viaWow=WindowsDriveGate.query(sys32,sysDrive);long wowMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);
            t=System.nanoTime();String viaNative=WindowsDriveGate.query(chosen,sysDrive);long nativeMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);
            System.out.println("DRIVE_PROBE_DIAG x86 wow64_ms="+wowMs+" sysnative_ms="+nativeMs);
            check(viaWow.equals(viaNative),"32-bit and 64-bit PowerShell give the same reply for the system drive");}
        String unused=null;for(char c='Z';c>='M';c--)if(!new File(c+":\\").exists()){unused=c+":\\";break;}
        if(unused!=null){String code;try{WindowsDriveGate.requireFixed(unused,WindowsDriveGate.SYSTEM);code="ACCEPTED";}catch(DeploymentBinding.Refusal refusal){code=refusal.getMessage();}
            String d=WindowsDriveGate.diagnose(unused);System.out.println("DRIVE_PROBE_DIAG unused-letter kind="+d.split("\\|")[0]);
            check("DRIVE_TYPE_UNPROVEN".equals(code)||"LOCAL_FIXED_DRIVE_REQUIRED".equals(code),"unused drive letter refused by the real probe ["+code+"]");}
        else System.out.println("NOT_RUN unused drive letter (M..Z all present)");
        System.out.println("NOT_RUN subst/mapped network drive (would change the host's drive mappings)");
        check(bad==0,"every real probe of the job's own drives proved LOCAL fixed (not_ok="+bad+")");
        System.out.println("Phase X-A drive probe checks="+checks);
    }
}
