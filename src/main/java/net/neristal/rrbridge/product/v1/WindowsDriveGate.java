package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only Windows local fixed-drive proof. No network mapping or mount operation. Java8 compatible. */
public final class WindowsDriveGate {
    public interface Probe {String query(String driveRoot)throws IOException;}
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
        String system=System.getenv("SystemRoot");if(system==null)throw new IOException("DRIVE_TYPE_UNPROVEN");
        File exe=new File(system,"System32\\WindowsPowerShell\\v1.0\\powershell.exe");
        if(!exe.isAbsolute()||!exe.isFile())throw new IOException("DRIVE_TYPE_UNPROVEN");
        String encoded=Base64.getEncoder().encodeToString(PROGRAM.getBytes(StandardCharsets.UTF_16LE));
        Process process=new ProcessBuilder(exe.toString(),"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",encoded).redirectErrorStream(true).start();
        try{
            try(OutputStream out=process.getOutputStream()){out.write((root+"\n").getBytes(StandardCharsets.US_ASCII));}
            return reply(process,10000,8192);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }};
    /** No filesystem access; rejects unknown/remote/removable/subst and inconsistent replies before path inspection. */
    public static void requireFixed(String root,Probe probe)throws IOException {
        if(root==null||!root.matches("[A-Za-z]:\\\\")||probe==null)throw new DeploymentBinding.Refusal("DRIVE_TYPE_UNPROVEN");
        String result;try{result=probe.query(root.toUpperCase(Locale.ROOT));}catch(IOException unavailable){throw new DeploymentBinding.Refusal("DRIVE_TYPE_UNPROVEN");}
        if(!"3|LOCAL\n".equals(result)&&!"3|LOCAL\r\n".equals(result))throw new DeploymentBinding.Refusal("LOCAL_FIXED_DRIVE_REQUIRED");
    }
    static String reply(Process process,long timeoutMillis,int limit)throws IOException {
        long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=process.getInputStream()){
            byte[] buffer=new byte[1024];
            while(true){
                if(System.nanoTime()>end)throw new IOException("DRIVE_TYPE_UNPROVEN");
                if(in.available()>0||!process.isAlive()){
                    int n=in.read(buffer);if(n==-1)break;
                    if(bytes.size()+n>limit)throw new IOException("DRIVE_TYPE_UNPROVEN");bytes.write(buffer,0,n);
                }else try{Thread.sleep(5);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("DRIVE_TYPE_UNPROVEN");}
            }
            if(!process.waitFor(Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime())),TimeUnit.MILLISECONDS)||process.exitValue()!=0)throw new IOException("DRIVE_TYPE_UNPROVEN");
            return new String(bytes.toByteArray(),StandardCharsets.US_ASCII);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("DRIVE_TYPE_UNPROVEN");}
    }
    private WindowsDriveGate(){}
}
