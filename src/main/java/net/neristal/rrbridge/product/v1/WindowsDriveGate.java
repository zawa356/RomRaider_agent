package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only Windows local fixed-drive proof. No network mapping or mount operation. Java8 compatible. */
public final class WindowsDriveGate {
    public interface Probe {String query(String driveRoot)throws IOException;}
    /**
     * Upper bound for one probe subprocess (PowerShell start + Add-Type compile + four Win32 calls). Phase X-A evidence:
     * GitHub windows-latest, first probe of a fresh job 3.3 / 8.2 / 9.7 s (cold PowerShell/.NET/csc), later probes
     * 0.32-0.50 s; the 10 s bound failed three jobs at 32b139f (gaps 10.0-11.4 s). Dev VM: 2.5-3.1 s from x64, 5.2-7.2 s
     * from a 32-bit JVM through WOW64 PowerShell. 30 s ≈ 3x the worst observed cold start; it only limits waiting — every
     * reply is still checked exactly as before, a timeout is still DRIVE_TYPE_UNPROVEN, and nothing is cached.
     */
    static final long PROBE_TIMEOUT_MILLIS=30000;
    /**
     * 64-bit PowerShell when this JVM is a 32-bit (WOW64) process: %SystemRoot%\Sysnative exists only for WOW64 processes and
     * avoids the slower 32-bit PowerShell/csc. Same fixed program and the same Win32 calls; System32 otherwise.
     */
    static File powershell(String systemRoot){
        File sysnative=new File(systemRoot,"Sysnative\\WindowsPowerShell\\v1.0\\powershell.exe");
        return sysnative.isFile()?sysnative:new File(systemRoot,"System32\\WindowsPowerShell\\v1.0\\powershell.exe");
    }
    /**
     * Internal failure class of the SYSTEM probe (Phase X-A diagnostics). Never leaves this gate as a result:
     * {@link #requireFixed} maps every probe failure to DRIVE_TYPE_UNPROVEN exactly as before.
     */
    public static final class ProbeFailure extends IOException {
        public final String kind;public final long elapsedMillis;
        ProbeFailure(String kind,long elapsedMillis){super("DRIVE_TYPE_UNPROVEN");this.kind=kind;this.elapsedMillis=elapsedMillis;}
    }
    // Fixed program only. The root arrives on stdin as data, never in PowerShell source/arguments.
    private static final String PROGRAM=
        "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';$s='using System;using System.Text;using System.Runtime.InteropServices;public static class RLDrive {"+
        "[DllImport(\"kernel32.dll\",CharSet=CharSet.Unicode)]public static extern uint GetDriveType(string r);"+
        "[DllImport(\"kernel32.dll\",CharSet=CharSet.Unicode)]public static extern uint QueryDosDevice(string n,StringBuilder b,int c);}' ;"+
        "Add-Type -TypeDefinition $s; $r=[Console]::ReadLine(); if($r -notmatch '^[A-Z]:\\\\$'){exit 2};"+
        "$a=New-Object System.Text.StringBuilder 4096;$b=New-Object System.Text.StringBuilder 4096;"+
        "$n=$r.Substring(0,2);$q1=[RLDrive]::QueryDosDevice($n,$a,4096);$t1=[RLDrive]::GetDriveType($r);"+
        "$q2=[RLDrive]::QueryDosDevice($n,$b,4096);$t2=[RLDrive]::GetDriveType($r);"+
        "if($q1 -eq 0 -or $q2 -eq 0 -or $a.ToString() -cne $b.ToString() -or $t1 -ne $t2){exit 3};"+
        "$kind='ALIAS';if($a.ToString() -match '^\\\\Device\\\\HarddiskVolume[0-9]+$'){$kind='LOCAL'};"+
        "[Console]::WriteLine(('{0}|{1}' -f $t1,$kind));";
    public static final Probe SYSTEM=new Probe(){public String query(String root)throws IOException {
        String system=System.getenv("SystemRoot");if(system==null)throw new ProbeFailure("START",0);
        return WindowsDriveGate.query(powershell(system),root);
    }};
    /** The fixed probe program run by the given powershell.exe (SYSTEM uses {@link #powershell}; tests compare both). */
    static String query(File exe,String root)throws IOException {
        long start=System.nanoTime();
        if(!exe.isAbsolute()||!exe.isFile())throw new ProbeFailure("START",0);
        String encoded=Base64.getEncoder().encodeToString(PROGRAM.getBytes(StandardCharsets.UTF_16LE));
        Process process;
        try{process=new ProcessBuilder(exe.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",encoded).redirectErrorStream(true).start();}
        catch(IOException cannot){throw new ProbeFailure("START",elapsed(start));}
        try{
            try(OutputStream out=process.getOutputStream()){out.write((root+"\n").getBytes(StandardCharsets.US_ASCII));}
            catch(IOException closed){throw new ProbeFailure("STDIN",elapsed(start));}
            return reply(process,PROBE_TIMEOUT_MILLIS,8192);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    /** No filesystem access; rejects unknown/remote/removable/subst and inconsistent replies before path inspection. */
    public static void requireFixed(String root,Probe probe)throws IOException {
        if(root==null||!root.matches("[A-Za-z]:\\\\")||probe==null)throw new DeploymentBinding.Refusal("DRIVE_TYPE_UNPROVEN");
        String result;try{result=probe.query(root.toUpperCase(Locale.ROOT));}catch(IOException unavailable){throw new DeploymentBinding.Refusal("DRIVE_TYPE_UNPROVEN");}
        if(!"3|LOCAL\n".equals(result)&&!"3|LOCAL\r\n".equals(result))throw new DeploymentBinding.Refusal("LOCAL_FIXED_DRIVE_REQUIRED");
    }
    static long elapsed(long start){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
    static String reply(Process process,long timeoutMillis,int limit)throws IOException {
        long start=System.nanoTime(),end=start+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=process.getInputStream()){
            byte[] buffer=new byte[1024];
            while(true){
                if(System.nanoTime()>end)throw new ProbeFailure("TIMEOUT",elapsed(start));
                if(in.available()>0||!process.isAlive()){
                    int n=in.read(buffer);if(n==-1)break;
                    if(bytes.size()+n>limit)throw new ProbeFailure("OUTPUT_BOUND",elapsed(start));bytes.write(buffer,0,n);
                }else try{Thread.sleep(5);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProbeFailure("INTERRUPTED",elapsed(start));}
            }
            if(!process.waitFor(Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())),TimeUnit.MILLISECONDS))throw new ProbeFailure("TIMEOUT",elapsed(start));
            if(process.exitValue()!=0)throw new ProbeFailure("EXIT_"+Math.max(-1,Math.min(255,process.exitValue())),elapsed(start));
            return new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ProbeFailure("INTERRUPTED",elapsed(start));}
    }
    /**
     * Diagnostics only (tests / CI step summary): runs the SYSTEM probe once and returns {@code kind|elapsedMillis}, kind =
     * OK_LOCAL_FIXED, OK_OTHER (a reply requireFixed would refuse) or a ProbeFailure kind. Codes and numbers only, never a
     * path, reply text or PowerShell output. Grants nothing: production callers still go through requireFixed.
     */
    public static String diagnose(String root){
        long start=System.nanoTime();
        if(root==null||!root.matches("[A-Za-z]:\\\\"))return "INVALID_ROOT|0";
        try{String r=SYSTEM.query(root.toUpperCase(Locale.ROOT));return ("3|LOCAL\n".equals(r)||"3|LOCAL\r\n".equals(r)?"OK_LOCAL_FIXED":"OK_OTHER")+"|"+elapsed(start);}
        catch(ProbeFailure f){return f.kind+"|"+elapsed(start);}
        catch(IOException other){return "IO|"+elapsed(start);}
    }
    private WindowsDriveGate(){}
}
