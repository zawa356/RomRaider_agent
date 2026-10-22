package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import net.neristal.rrbridge.prototype.ingress.Phase8InventoryTest.Install;
import net.neristal.rrbridge.prototype.ingress.PhaseXaZipAuditTest.E;

/**
 * Phase X-B: RLDI1 verifies EVERY JAR entry (not only manifests and classes) — CRC-32, exact DEFLATE end, declared sizes,
 * header consistency — streaming, with per-entry and per-JAR expansion bounds. Each malformed archive is installed as the
 * listed {@code dependency_jar} of an owned synthetic install and must stop the inventory. With -Dphasexb.expectRed=true
 * (pre-fix code) an accepted (MATCHED) case is counted as the reproduced gap. Real dependency JARs must still be readable.
 */
public final class PhaseXbZipAllEntriesTest {
    static int checks,red;static final boolean RED=Boolean.getBoolean("phasexb.expectRed");
    /** Contract bounds (Phase X-B). Kept here so the test also compiles against the pre-fix code for the RED run. */
    static final long ENTRY_BOUND=16L*1024*1024,JAR_BOUND=128L*1024*1024;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}

    static DistributionInventory.Report inspectWithDependency(byte[] dependency)throws Exception {
        Install t=new Install();Path dep=t.root.resolve("lib/dependency.jar");
        Files.delete(dep);Files.write(dep,dependency);t.pin();
        return t.inspect();
    }
    /** Stops = not MATCHED, with JAR_UNREADABLE (finding) or the given refusal reason. */
    static void stops(String what,byte[] dependency,String code)throws Exception {
        DistributionInventory.Report r=inspectWithDependency(dependency);
        if(RED&&r.matched()){red++;System.out.println("RED "+what+" -> MATCHED (gap reproduced)");return;}
        boolean ok=!r.matched()&&("JAR_UNREADABLE".equals(code)?r.findings.containsKey("JAR_UNREADABLE"):code.equals(r.reason));
        check(ok&&r.json().contains("\"state\":\"BLOCKED\""),what+" -> "+r.inventory+" "+r.reason+" "+r.findings);
    }
    static byte[] deflate(byte[] raw){return PhaseXaZipAuditTest.deflate(raw);}
    static E resource(String name,int method,byte[] raw)throws Exception{return new E(name,method,raw);}

    public static void main(String[] a)throws Exception {
        byte[] text="synthetic resource\n".getBytes(StandardCharsets.US_ASCII);
        byte[] dll=new byte[70000];new Random(7).nextBytes(dll);dll[0]='M';dll[1]='Z';// a synthetic "DLL" (not executable)

        // ---- regression: valid non-class entries of every kind are accepted.
        {DistributionInventory.Report r=inspectWithDependency(PhaseXaZipAuditTest.build(resource("res/a.txt",0,text),resource("res/b.txt",8,text),
             resource("native/win32-x86/lib.dll",8,dll),resource("META-INF/services/x.Service",8,text),resource("empty/",0,new byte[0]),
             resource("res/c.txt",8,text).descriptor()).bytes);
         check(r.matched(),"valid STORED/DEFLATED/descriptor resources, DLL, service file and directory entry: MATCHED (still BLOCKED) "+r.findings);}

        // ---- malformed non-class entries (previously never inflated or CRC-checked).
        {E e=resource("res/a.txt",0,text);e.crc^=1;e.lcrc=e.crc;stops("STORED resource with CRC mismatch",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("META-INF/services/x.Service",8,text);e.crc^=0x100;e.lcrc=e.crc;stops("service file with CRC mismatch",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("native/win32-x86/lib.dll",8,dll);byte[] bad=e.data.clone();for(int i=10;i<bad.length;i+=97)bad[i]^=0x5a;e.data=bad;
         stops("corrupted DEFLATE data of a native DLL entry",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("native/win32-x86/lib.dll",0,dll);e.data=dll.clone();e.data[5000]^=1;// bytes changed, header CRC kept
         stops("STORED DLL whose bytes do not match the recorded CRC",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {byte[] more=new byte[text.length+40];System.arraycopy(text,0,more,0,text.length);
         E e=resource("res/a.txt",8,more);e.us=e.lus=text.length;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(text);
         stops("resource DEFLATE stream longer than declared",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("res/a.txt",8,text);e.data=PhaseXaZipAuditTest.syncFlushOnly(text);e.cs=e.lcs=e.data.length;
         stops("resource DEFLATE stream not terminated",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("res/a.txt",8,text);byte[] pad=Arrays.copyOf(e.data,e.data.length+3);e.data=pad;e.cs=e.lcs=pad.length;
         stops("resource DEFLATE with trailing garbage",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("res/a.txt",8,text);e.us=e.lus=text.length+5;
         stops("resource declared longer than its stream produces",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}
        {E e=resource("empty/",0,text);stops("directory entry carrying data",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");}

        // ---- zip-bomb guards (streaming, bounded).
        byte[] zeros=new byte[(int)ENTRY_BOUND+1];
        {E e=resource("bomb/one.bin",8,zeros);check(e.data.length<100000,"fixture: "+zeros.length+" expanded bytes compress to "+e.data.length);
         stops("single entry declared above the per-entry bound",PhaseXaZipAuditTest.build(e).bytes,"JAR_ENTRY_EXPANSION_BOUND");}
        {byte[] fifteen=new byte[15*1024*1024];byte[] comp=deflate(fifteen);List<E> many=new ArrayList<E>();
         int n=(int)(JAR_BOUND/fifteen.length)+1;
         for(int i=0;i<n;i++){E e=resource("bomb/p"+i+".bin",0,new byte[0]);e.method=e.lmethod=8;e.data=comp;e.cs=e.lcs=comp.length;e.us=e.lus=fifteen.length;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(fifteen);many.add(e);}
         stops(n+" entries of 15 MiB each above the per-JAR total bound",PhaseXaZipAuditTest.build(many).bytes,"JAR_TOTAL_EXPANSION_BOUND");}
        {byte[] ten=new byte[10*1024*1024];E e=resource("bomb/liar.bin",8,ten);e.us=e.lus=1024;e.crc=e.lcrc=PhaseXaZipAuditTest.crc(new byte[1024]);
         Runtime rt=Runtime.getRuntime();System.gc();long before=rt.totalMemory()-rt.freeMemory();
         stops("stream expanding far beyond its small declared size (lying bomb)",PhaseXaZipAuditTest.build(e).bytes,"JAR_UNREADABLE");
         System.out.println("NOTE heap delta during lying-bomb inspection ~"+((rt.totalMemory()-rt.freeMemory()-before)/1024/1024)+" MiB (inflation stops at declared size + 1 byte)");}
        if(!RED){byte[] big=new byte[15*1024*1024];Arrays.fill(big,(byte)7);
         DistributionInventory.Report r=inspectWithDependency(PhaseXaZipAuditTest.build(resource("big/ok.bin",8,big)).bytes);
         check(r.matched(),"valid 15 MiB resource below the bound verifies (streaming) and stays MATCHED");}

        // ---- real dependency JARs and a JDK-made JAR: every entry verifies (no false JAR_UNREADABLE).
        if(!RED){
            for(String[] c:new String[][]{{"MAX_ENTRY_EXPANDED",String.valueOf(ENTRY_BOUND)},{"MAX_JAR_EXPANDED",String.valueOf(JAR_BOUND)}}){
                java.lang.reflect.Field fld=DistributionInventory.class.getDeclaredField(c[0]);fld.setAccessible(true);
                check(String.valueOf(fld.get(null)).equals(c[1]),"product bound "+c[0]+" = "+c[1]);}
            List<File> real=new ArrayList<File>();for(String n:new String[]{"lib/sqlite-jdbc.jar","lib/slf4j-api.jar","lib/slf4j-nop.jar","build/identity-deps/jna-5.14.0.jar"})if(new File(n).isFile())real.add(new File(n));
            check(real.size()>=3,"real dependency JARs present ("+real.size()+")");
            for(File f:real){
                byte[] z=Files.readAllBytes(f.toPath());List<DistributionInventory.ZipEntryView> es=DistributionInventory.ZipEntryView.read(z);
                java.lang.reflect.Method verify=DistributionInventory.ZipEntryView.class.getDeclaredMethod("verify");verify.setAccessible(true);
                long total=0;for(DistributionInventory.ZipEntryView e:es){verify.invoke(e);total+=e.size;}
                check(total<=JAR_BOUND,"real "+f.getName()+": all "+es.size()+" entries verify, "+total+" bytes expanded (bounds 16 MiB/entry, 128 MiB/JAR)");
                DistributionInventory.Report r=inspectWithDependency(z);
                check(!r.findings.containsKey("JAR_UNREADABLE")&&!r.reason.contains("EXPANSION"),"real "+f.getName()+" as a listed dependency: no JAR_UNREADABLE/expansion refusal "+r.findings);
            }
        }
        if(RED){System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected the pre-fix code to accept at least one malformed non-class entry");}
        System.out.println("Phase X-B ZIP all-entries test passed "+checks+" checks"+(red>0?" with "+red+" RED reproductions":"")+"; RLDI1 still BLOCKED; no RLDI2");
    }
    private PhaseXbZipAllEntriesTest(){}
}
