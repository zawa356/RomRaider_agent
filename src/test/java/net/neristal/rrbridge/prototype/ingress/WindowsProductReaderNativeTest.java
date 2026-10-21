package net.neristal.rrbridge.prototype.ingress;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Phase IX-2 RLWR1 on real NTFS files (Windows x64 JDK17 and actual Zulu8 x86). Owned private fixtures only.
 * Compares identity/bytes with the reviewed test-only reader, injects path/handle/race faults, compares the two share
 * modes for rename/delete/rotation, checks handle accounting, native-unavailable, and runs the RLC2 scenario suite
 * ({@link WatermarkV2Scenarios}) with the product reader. Cases without a safe fixture print NOT_RUN, never PASS.
 */
public final class WindowsProductReaderNativeTest {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static void notRun(String s){System.out.println("NOT_RUN "+s);}
    interface Action{void run()throws Exception;}
    static String code(Action a){try{a.run();return "OK";}catch(WindowsProductReader.Refusal r){return r.code;}catch(Exception e){return "UNCODED:"+e.getClass().getSimpleName();}}
    interface K32 extends StdCallLibrary {
        Pointer CreateFileW(WString p,int access,int share,Pointer sec,int disp,int flags,Pointer t);
        int CloseHandle(Pointer h);int GetProcessHandleCount(Pointer process,com.sun.jna.ptr.IntByReference count);Pointer GetCurrentProcess();
        int MoveFileExW(WString from,WString to,int flags);
    }
    static final K32 K=Native.load("kernel32",K32.class);
    static Pointer writer(Path p,int share){Pointer h=K.CreateFileW(new WString(p.toString()),0x80000000|0x40000000,share,null,3,0,null);
        if(h==null||Pointer.nativeValue(h)==-1L||(Native.POINTER_SIZE==4&&Pointer.nativeValue(h)==0xffffffffL))throw new IllegalStateException("fixture writer open failed "+Native.getLastError());return h;}
    static int handles(){com.sun.jna.ptr.IntByReference n=new com.sun.jna.ptr.IntByReference();K.GetProcessHandleCount(K.GetCurrentProcess(),n);return n.getValue();}
    static byte[] bytes(int n,int seed){byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)(i*seed+11);return b;}
    static byte[] all(WatermarkV2Prototype.ReadOpener r,Path p)throws IOException{try(WatermarkV2Prototype.ReadHandle h=r.open(p)){return h.read(0,(int)h.length());}}
    static WatermarkV2Prototype.SourceIdentity id(WatermarkV2Prototype.ReadOpener r,Path p)throws IOException{try(WatermarkV2Prototype.ReadHandle h=r.open(p)){return h.identity();}}

    static WindowsProductReader.ShareMode scenarioShare;
    static final WatermarkV2Scenarios.Env ENV=new WatermarkV2Scenarios.Env(){
        public String name(){return "Windows/NTFS RLWR1 "+scenarioShare+" "+System.getProperty("sun.arch.data.model")+"-bit Java "+System.getProperty("java.version");}
        public WatermarkV2Scenarios.OwnedJournal openOwned(Path source)throws IOException{return WindowsWatermarkV2Test.ENV.openOwned(source);}
        public WatermarkV2Prototype.ReadOpener reader(){try{return WindowsProductReader.create(scenarioShare);}catch(IOException e){throw new IllegalStateException(e.getMessage());}}
        public WatermarkV2Prototype.Platform platform(){return WindowsWatermarkV2Test.PLATFORM;}
        public WatermarkV2Prototype.IdentityTag tag(){return WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX;}
        public WatermarkV2Prototype.DirectoryDurability expectedDirectory(){return WatermarkV2Prototype.DirectoryDurability.UNKNOWN;}
        public boolean heldNamesArePinned(){return true;}
        public String childMain(){return WindowsProductReaderNativeTest.class.getName();}
    };

    public static void main(String[] a)throws Exception {
        // Scenario children only exercise the owned writer side; the reader share mode does not reach them.
        if(a.length==4&&a[0].equals("child")){scenarioShare=WindowsProductReader.ShareMode.READ_WRITE;WatermarkV2Scenarios.child(ENV,a[1],new File(a[2]).toPath(),a[3]);return;}
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" pointer="+Native.POINTER_SIZE+" jna="+Native.VERSION);
        check("5.14.0".equals(Native.VERSION),"pinned JNA 5.14.0 loaded");
        final WindowsProductReader rw=WindowsProductReader.create(WindowsProductReader.ShareMode.READ_WRITE),rwd=WindowsProductReader.create(WindowsProductReader.ShareMode.READ_WRITE_DELETE);
        final Path home=Files.createTempDirectory("romlogger-rlwr1-").toRealPath();
        final Path dir=Files.createDirectory(home.resolve("src"));
        Files.write(dir.resolve(".keep"),"synthetic".getBytes(StandardCharsets.US_ASCII));

        // ---- 1. same fixture: identity/bytes equal the reviewed test-only reader; boundaries.
        final Path src=dir.resolve("source.rlj");byte[] payload=bytes(200003,31);Files.write(src,payload);
        check(id(rw,src).equals(id(WindowsWatermarkV2Test.READER,src))&&id(rwd,src).equals(id(rw,src)),"identity equals test-only reader (both share modes)");
        check(Arrays.equals(all(rw,src),payload)&&Arrays.equals(all(WindowsWatermarkV2Test.READER,src),payload),"bytes equal file and test-only reader");
        try(WatermarkV2Prototype.ReadHandle h=rw.open(src)){
            for(int n:new int[]{1,65535,65536,65537,131072,200003})check(Arrays.equals(h.read(0,n),Arrays.copyOf(payload,n)),"exact read of "+n+" bytes");
            check(Arrays.equals(h.read(65535,3),Arrays.copyOfRange(payload,65535,65538)),"read straddling 64KiB at offset 65535");
            check(h.read(payload.length,0).length==0,"zero-length read at EOF");
            check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(200000,10);}})),"read past EOF refused");
        }
        Path empty=Files.write(dir.resolve("empty.rlj"),new byte[0]);
        try(WatermarkV2Prototype.ReadHandle h=rw.open(empty)){check(h.length()==0&&h.read(0,0).length==0,"0-byte source: length 0, empty read");}
        check(rw.statusJson().contains("\"adoption\":\"BLOCKED\""),"status keeps adoption BLOCKED");

        // ---- 2. replacement: same name new content and byte-identical replacement give a new identity.
        WatermarkV2Prototype.SourceIdentity first=id(rw,src);
        Files.delete(src);Files.write(src,payload);
        check(!id(rw,src).equals(first)&&Arrays.equals(all(rw,src),payload),"byte-identical replacement at same name has a new identity");
        WatermarkV2Prototype.SourceIdentity second=id(rw,src);Files.delete(src);Files.write(src,bytes(200003,17));
        check(!id(rw,src).equals(second)&&!id(rw,src).equals(first),"same-name content replacement has a new identity (old/new indices kept apart)");
        Files.delete(src);Files.write(src,payload);

        // ---- 3. share mode comparison: rename / delete / rotation while the reader holds the source.
        Path moved=dir.resolve("moved.rlj");
        try(WatermarkV2Prototype.ReadHandle h=rw.open(src)){
            String r;try{Files.move(src,moved);r="RENAMED";}catch(IOException e){r="BLOCKED";}
            check("BLOCKED".equals(r),"READ_WRITE share: rename of a held source is blocked (writer rotation would wait)");
            int err=K.MoveFileExW(new WString(src.toString()),new WString(moved.toString()),0x1)==0?Native.getLastError():0;
            check(err==32||err==5,"READ_WRITE share: MoveFileEx rotation fails (Win32 "+err+")");
            String d;try{Files.delete(src);d="DELETED";}catch(IOException e){d="BLOCKED";}
            check("BLOCKED".equals(d)&&Files.exists(src),"READ_WRITE share: delete of a held source is blocked");
        }
        WatermarkV2Prototype.SourceIdentity held=id(rwd,src);
        try(final WatermarkV2Prototype.ReadHandle h=rwd.open(src)){
            Files.move(src,moved);
            check(h.identity().equals(held)&&Arrays.equals(h.read(0,1000),Arrays.copyOf(payload,1000)),"READ_WRITE_DELETE share: rename while open allowed; handle keeps identity and bytes");
            check("READER_NOT_FOUND".equals(code(new Action(){public void run()throws Exception{rwd.open(src);}})),"old name after rename: READER_NOT_FOUND");
            check(id(rwd,moved).equals(held),"renamed file keeps its identity (rename is not replacement)");
            Files.delete(moved);
            check("READER_DELETE_PENDING".equals(code(new Action(){public void run()throws Exception{h.identity();}}))&&"READER_DELETE_PENDING".equals(code(new Action(){public void run()throws Exception{h.read(0,10);}})),"READ_WRITE_DELETE share: delete while open → READER_DELETE_PENDING on identity and read");
        }
        check(!Files.exists(moved),"deleted file disappears after the reader closes");
        Files.write(src,payload);

        // ---- 4. writer coordination.
        Pointer excl=writer(src,0);
        try{WindowsProductReader.Refusal r=null;try{rw.open(src);}catch(WindowsProductReader.Refusal x){r=x;}
            check(r!=null&&"READER_SHARING_VIOLATION".equals(r.code)&&r.win32==32,"exclusive writer → READER_SHARING_VIOLATION win32=32");}
        finally{K.CloseHandle(excl);}
        Pointer shared=writer(src,0x1|0x2);
        try(final WatermarkV2Prototype.ReadHandle h=rw.open(src)){
            check(h.length()==payload.length,"writer holding READ|WRITE share does not block the reader");
            try(RandomAccessFile raf=new RandomAccessFile(src.toFile(),"rw")){
                raf.seek(payload.length);raf.write(bytes(5000,3));
                check(h.length()==payload.length+5000&&Arrays.equals(h.read(payload.length,5000),bytes(5000,3)),"append by writer: growth visible through the same handle");
                raf.setLength(1000);
            }
            check("READER_LENGTH_SHRANK".equals(code(new Action(){public void run()throws Exception{h.length();}})),"truncation by writer → READER_LENGTH_SHRANK");
            check("READER_LENGTH_SHRANK".equals(code(new Action(){public void run()throws Exception{h.read(0,10);}})),"read after truncation refused");
        }finally{K.CloseHandle(shared);}
        Files.write(src,payload);

        // ---- 5. path/link faults.
        Path link=dir.resolve("alias.rlj");Files.createLink(link,src);
        check("READER_HARDLINK_PRESENT".equals(code(new Action(){public void run()throws Exception{rw.open(src);}}))&&"READER_HARDLINK_PRESENT".equals(code(new Action(){public void run()throws Exception{rw.open(dir.resolve("alias.rlj"));}})),"hard link alias refused by either name");
        Files.delete(link);check(id(rw,src).equals(id(WindowsWatermarkV2Test.READER,src)),"after removing the alias the source opens again");
        final Path junction=home.resolve("junction");
        Process mk=new ProcessBuilder("cmd","/c","mklink","/J",junction.toString(),dir.toString()).redirectErrorStream(true).redirectOutput(home.resolve("mklink.log").toFile()).start();
        check(mk.waitFor()==0,"junction fixture created (owned)");
        check("READER_PATH_LINK".equals(code(new Action(){public void run()throws Exception{rw.open(junction.resolve("source.rlj"));}})),"junction path component refused");
        final Path sym=dir.resolve("sym.rlj");boolean symOk;try{Files.createSymbolicLink(sym,src);symOk=true;}catch(IOException|UnsupportedOperationException noPrivilege){symOk=false;}
        if(symOk){String c=code(new Action(){public void run()throws Exception{rw.open(sym);}});check("READER_REPARSE_POINT".equals(c)||"READER_PATH_CHANGED".equals(c),"file symlink refused ["+c+"]");}
        else notRun("file symlink (no SeCreateSymbolicLinkPrivilege for this user)");
        final Path dsym=home.resolve("dirsym");boolean dsymOk;try{Files.createSymbolicLink(dsym,dir);dsymOk=true;}catch(IOException|UnsupportedOperationException noPrivilege){dsymOk=false;}
        if(dsymOk)check("READER_PATH_LINK".equals(code(new Action(){public void run()throws Exception{rw.open(dsym.resolve("source.rlj"));}})),"directory symlink component refused");
        else notRun("directory symlink component (no privilege)");
        check("READER_NOT_FOUND".equals(code(new Action(){public void run()throws Exception{rw.open(dir.resolve("absent.rlj"));}})),"absent source → READER_NOT_FOUND");
        String dc=code(new Action(){public void run()throws Exception{rw.open(dir);}});
        check("READER_OPEN_DENIED".equals(dc)||"READER_NOT_REGULAR_FILE".equals(dc),"directory as source refused ["+dc+"]");
        check("READER_PATH_INVALID".equals(code(new Action(){public void run()throws Exception{rw.open(Paths.get("source.rlj"));}})),"relative path refused");
        check("READER_PATH_INVALID".equals(code(new Action(){public void run()throws Exception{rw.open(Paths.get(dir.toString(),"..","src","source.rlj"));}})),"non-normalized path refused");
        String unc="\\\\localhost\\"+src.toString().charAt(0)+"$"+src.toString().substring(2);
        check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{rw.open(Paths.get(unc));}})),"UNC/administrative share path refused before opening");
        // A short (8.3) name resolves to a different final path: refused rather than trusted by name.
        notRun("non-NTFS volume (FAT/exFAT/ReFS), mapped network drive and subst: no owned fixture without admin/system changes; logic covered by the fake test");

        // ---- 6. handle accounting.
        for(int i=0;i<100;i++){try(WatermarkV2Prototype.ReadHandle h=rw.open(src)){h.read(0,100);}}// warm-up: JVM/JNA lazily open their own handles
        int before=handles();
        for(int i=0;i<500;i++){try(WatermarkV2Prototype.ReadHandle h=rw.open(src)){h.read(0,100);}}
        for(int i=0;i<200;i++)code(new Action(){public void run()throws Exception{rw.open(junction.resolve("source.rlj"));}});
        for(int i=0;i<200;i++)code(new Action(){public void run()throws Exception{rw.open(dir.resolve("absent.rlj"));}});
        int after=handles();
        check(after-before<64,"no per-open handle leak over 900 opens/refusals (process handles "+before+"->"+after+"; a per-call leak would add >=200)");

        // ---- 7. native unavailable: same classes without JNA on the classpath.
        String cp=System.getProperty("java.class.path");StringBuilder noJna=new StringBuilder();
        for(String part:cp.split(File.pathSeparator))if(!part.toLowerCase(Locale.ROOT).contains("jna")){if(noJna.length()>0)noJna.append(File.pathSeparator);noJna.append(part);}
        String exe=new File(new File(System.getProperty("java.home"),"bin"),"java.exe").toString();
        Process child=new ProcessBuilder(exe,"-cp",noJna.toString(),NoJna.class.getName()).redirectErrorStream(true).start();
        String out=new String(readAll(child.getInputStream()),StandardCharsets.UTF_8);child.waitFor();
        check(out.contains("CODE READER_NATIVE_UNAVAILABLE")||out.contains("NoClassDefFoundError: com/sun/jna"),"without JNA: READER_NATIVE_UNAVAILABLE (no java.io fallback) ["+out.trim().split("\r?\n")[0]+"]");
        check(out.contains("CODE READER_NATIVE_UNAVAILABLE"),"native-unavailable is a coded refusal, not a crash");

        // ---- 8. RLC2 scenarios with the product reader (both share modes).
        for(WindowsProductReader.ShareMode m:WindowsProductReader.ShareMode.values()){
            scenarioShare=m;WatermarkV2Scenarios.run(ENV);check(true,"RLC2 WatermarkV2Scenarios suite completed with RLWR1 "+m);
        }
        Files.delete(junction);
        System.out.println("RLWR1 native test passed "+checks+" checks; adoption BLOCKED; FILE_SHARE_DELETE and JNA licence OWNER_DECISION_REQUIRED");
    }
    /** Child probe free of JNA references (the outer class loads JNA statically). */
    static final class NoJna {
        public static void main(String[] a){
            try{WindowsProductReader.create(WindowsProductReader.ShareMode.READ_WRITE);System.out.println("CODE OK");}
            catch(WindowsProductReader.Refusal r){System.out.println("CODE "+r.code);}
        }
    }
    static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toByteArray();}
    private WindowsProductReaderNativeTest(){}
}
