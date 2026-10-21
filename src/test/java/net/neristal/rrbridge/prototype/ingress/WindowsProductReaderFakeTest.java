package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Phase IX-2 RLWR1 logic test with an injected fake Win32 (no native code, runs on any OS). Every refusal code, the
 * 32/64-bit invalid-handle values, chunking/bounds, post-read re-checks and handle accounting (no leak on any refusal).
 * Paths are Windows-form strings parsed with the platform's Path only when the platform is Windows; on POSIX the
 * Windows-path cases are reported NOT_RUN and the platform refusal is checked instead.
 */
public final class WindowsProductReaderFakeTest {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    interface Action{void run()throws Exception;}
    static String code(Action a){try{a.run();return "OK";}catch(WindowsProductReader.Refusal r){return r.code;}catch(Exception e){return "UNCODED:"+e.getClass().getSimpleName();}}

    /** Programmable fake. A file is a byte array plus attributes; every handle is counted. */
    static final class Fake implements WindowsProductReader.Win32 {
        int pointer=8,last,attrs=0x20,links=1,stdLinks=1,serial=0x1234abcd,idxHigh=7,idxLow=99,driveType=3;
        boolean deletePending,directoryStd,infoFails,stdFails,seekFails,closeFails,sizeMismatch;
        String fs="NTFS",dos="\\Device\\HarddiskVolume3",finalPrefix="\\\\?\\",finalOverride;
        long invalidValue=-1L;Integer openError;Set<String> reparseDirs=new HashSet<String>();Set<String> fileDirs=new HashSet<String>();
        byte[] data=new byte[0];int readCap=Integer.MAX_VALUE,readError,shortAfter=-1,readCalls,maxChunk;long pos;
        Runnable afterRead;int open,closed;Map<Long,String> handles=new HashMap<Long,String>();long next=100;
        public int pointerSize(){return pointer;}
        public long createFile(String path,int access,int share,int flags){
            if(openError!=null&&(access&0x80000000)!=0){last=openError;return invalidValue;}
            long h=next++;handles.put(h,path);open++;return h;
        }
        public int lastError(){return last;}
        public boolean handleInfo(long h,byte[] out){
            if(infoFails){last=6;return false;}
            String p=handles.get(h);boolean file=p!=null&&p.endsWith(".rlj");
            int a=file?attrs:(reparseDirs.contains(p)?0x410:(fileDirs.contains(p)?0x20:0x10));
            ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            long size=data.length+(sizeMismatch?1:0);
            b.putInt(0,a).putInt(28,serial).putInt(32,(int)(size>>>32)).putInt(36,(int)size).putInt(40,links).putInt(44,idxHigh).putInt(48,idxLow);return true;
        }
        public boolean standardInfo(long h,byte[] out){
            if(stdFails){last=6;return false;}
            ByteBuffer b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            b.putLong(0,4096).putLong(8,data.length).putInt(16,stdLinks).put(20,(byte)(deletePending?1:0)).put(21,(byte)(directoryStd?1:0));return true;
        }
        public String finalPath(long h){return finalOverride!=null?finalOverride:finalPrefix+handles.get(h);}
        boolean fsError;
        public String fileSystem(long h){if(fsError)throw new UnsatisfiedLinkError("synthetic");return fs;}
        public int driveType(String root){return driveType;}
        public String dosDevice(String d){return dos;}
        public boolean seek(long h,long off){if(seekFails){last=87;return false;}pos=off;return true;}
        public int read(long h,byte[] buf,int len){
            readCalls++;maxChunk=Math.max(maxChunk,len);
            if(readError!=0){last=readError;return -1;}
            if(shortAfter>=0&&readCalls>shortAfter)return 0;
            int n=(int)Math.min(Math.min(len,readCap),data.length-pos);if(n<0)n=0;
            System.arraycopy(data,(int)pos,buf,0,n);pos+=n;
            if(afterRead!=null)afterRead.run();
            return n;
        }
        public boolean close(long h){if(handles.remove(h)==null)throw new AssertionError("double native close");closed++;if(closeFails){last=6;return false;}return true;}
        boolean balanced(){return open==closed&&handles.isEmpty();}
    }

    static WindowsProductReader reader(Fake f){return new WindowsProductReader(f,WindowsProductReader.ShareMode.READ_WRITE,true);}

    public static void main(String[] args)throws Exception {
        final boolean windowsPaths=System.getProperty("os.name","").startsWith("Windows");
        // Platform gate is independent of paths.
        check("READER_PLATFORM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{new WindowsProductReader(new Fake(),WindowsProductReader.ShareMode.READ_WRITE,false).open(Paths.get("x"));}})),"non-Windows platform refused");
        if(!windowsPaths){
            check("READER_PLATFORM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{WindowsProductReader.create(WindowsProductReader.ShareMode.READ_WRITE);}})),"create() refuses on non-Windows (no native attempt)");
            System.out.println("NOT_RUN Windows path-form cases (POSIX Path cannot parse drive-letter paths)");
            System.out.println("RLWR1 fake test passed "+checks+" checks (POSIX subset)");return;
        }
        final Path src=Paths.get("C:\\fake\\dir\\source.rlj");
        byte[] payload=new byte[200003];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31+7);

        // ---- healthy open/read/close, chunking, bounds, accounting.
        {final Fake f=new Fake();f.data=payload;WindowsProductReader r=reader(f);
         WatermarkV2Prototype.ReadHandle h=r.open(src);
         check(h.identity().tag==WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX&&h.length()==payload.length,"healthy open: tag and length from handle");
         check(Arrays.equals(h.read(0,payload.length),payload)&&f.maxChunk==65536,"exact read in ≤64KiB chunks");
         check(Arrays.equals(h.read(65535,2),Arrays.copyOfRange(payload,65535,65537)),"read across the 64KiB boundary");
         check(h.read(payload.length,0).length==0,"zero-length read at EOF");
         check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(-1,1);}})),"negative offset refused");
         check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(0,-1);}})),"negative length refused");
         check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(Long.MAX_VALUE-1,5);}})),"offset+length overflow refused");
         check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(payload.length-1,2);}})),"read past current length refused (no silent truncation)");
         check("READER_BOUNDS".equals(code(new Action(){public void run()throws Exception{h.read(0,WindowsProductReader.MAX_READ+1);}})),"read above per-call bound refused");
         h.close();h.close();
         check(f.balanced(),"close is idempotent and every native handle closed");
         check("READER_HANDLE_CLOSED".equals(code(new Action(){public void run()throws Exception{h.read(0,1);}}))&&"READER_HANDLE_CLOSED".equals(code(new Action(){public void run()throws Exception{h.identity();}})),"read/identity after close refused");
         check(r.statusJson().contains("\"adoption\":\"BLOCKED\"")&&r.statusJson().contains("\"share_delete_decision\":\"OWNER_DECISION_REQUIRED\"")&&r.statusJson().contains("\"distribution_license\":\"OWNER_DECISION_REQUIRED\""),"status JSON keeps adoption BLOCKED and owner decisions open");}

        // ---- path form refusals (no native open at all).
        for(final String[] c:new String[][]{{"relative\\source.rlj","READER_PATH_INVALID"},{"C:\\fake\\..\\fake\\source.rlj","READER_PATH_INVALID"},
                {"\\\\server\\share\\source.rlj","READER_FILESYSTEM_UNSUPPORTED"}}){
            final Fake f=new Fake();
            check(c[1].equals(code(new Action(){public void run()throws Exception{reader(f).open(Paths.get(c[0]));}}))&&f.open==0,"path "+c[1]+" before any native open: "+c[0].replace('\\','/'));
        }
        // ---- drive gates.
        {Fake f=new Fake();f.driveType=4;check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.open==0,"network drive (DRIVE_REMOTE) refused");}
        {Fake f=new Fake();f.driveType=2;check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.open==0,"removable drive refused");}
        {Fake f=new Fake();f.dos="\\??\\C:\\real\\dir";check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.open==0,"subst drive alias refused");}
        {Fake f=new Fake();f.dos=null;check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.open==0,"unresolvable drive device refused");}
        // ---- component and file attribute refusals, each with balanced handles.
        {Fake f=new Fake();f.reparseDirs.add("C:\\fake\\dir");check("READER_PATH_LINK".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"junction/symlink directory component refused, handles balanced");}
        {Fake f=new Fake();f.fileDirs.add("C:\\fake");check("READER_PATH_INVALID".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"non-directory path component refused");}
        {Fake f=new Fake();f.fs="ReFS";check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"non-NTFS file system (from handle) refused, handle closed");}
        {Fake f=new Fake();f.fs=null;check("READER_FILESYSTEM_UNSUPPORTED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"unknown file system refused");}
        {Fake f=new Fake();f.finalOverride="\\\\?\\C:\\elsewhere\\source.rlj";check("READER_PATH_CHANGED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"handle final path differs from request (component swap) refused");}
        {Fake f=new Fake();f.finalOverride="\\\\?\\UNC\\server\\share\\source.rlj";check("READER_PATH_CHANGED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"handle resolving to UNC refused");}
        {Fake f=new Fake();f.attrs=0x420;check("READER_REPARSE_POINT".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"reparse-point file refused");}
        {Fake f=new Fake();f.attrs=0x10;check("READER_NOT_REGULAR_FILE".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"directory as source refused");}
        {Fake f=new Fake();f.links=2;f.stdLinks=2;check("READER_HARDLINK_PRESENT".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"hard-linked source refused");}
        {Fake f=new Fake();f.deletePending=true;check("READER_DELETE_PENDING".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"delete-pending source refused");}
        {Fake f=new Fake();f.idxHigh=0;f.idxLow=0;check("READER_IDENTITY_UNAVAILABLE".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"empty file index refused");}
        {Fake f=new Fake();f.infoFails=true;check("READER_IDENTITY_UNAVAILABLE".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"identity query failure refused");}
        {Fake f=new Fake();f.stdFails=true;check("READER_IDENTITY_UNAVAILABLE".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"standard info failure refused");}
        {Fake f=new Fake();f.sizeMismatch=true;f.data=payload;check("READER_LENGTH_UNSTABLE".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}}))&&f.balanced(),"inconsistent size sources refused");}
        // ---- open errors and invalid handle values (x64 -1, x86 0xffffffff, NULL).
        for(final int[] e:new int[][]{{32},{5},{2},{3},{1450}}){
            Fake f=new Fake();f.openError=e[0];String want=e[0]==32?"READER_SHARING_VIOLATION":e[0]==2||e[0]==3?"READER_NOT_FOUND":"READER_OPEN_DENIED";
            WindowsProductReader.Refusal r=null;try{reader(f).open(src);}catch(WindowsProductReader.Refusal x){r=x;}
            check(r!=null&&want.equals(r.code)&&r.win32==e[0]&&f.balanced()&&r.getMessage().equals(want),"open error "+e[0]+" → "+want+" with Win32 number, no path");
        }
        {Fake f=new Fake();f.pointer=4;f.openError=5;f.invalidValue=0xffffffffL;check("READER_OPEN_DENIED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}})),"32-bit INVALID_HANDLE_VALUE 0xffffffff detected");}
        {Fake f=new Fake();f.pointer=8;f.openError=5;f.invalidValue=0L;check("READER_OPEN_DENIED".equals(code(new Action(){public void run()throws Exception{reader(f).open(src);}})),"NULL handle detected");}
        {Fake f=new Fake();f.pointer=8;check(!reader(f).invalid(0xffffffffL),"64-bit: 0xffffffff is a valid handle value");}
        // ---- read-time faults.
        {final Fake f=new Fake();f.data=payload;f.readCap=1000;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         check(Arrays.equals(h.read(0,5000),Arrays.copyOf(payload,5000)),"partial ReadFile results are accumulated exactly");h.close();}
        {final Fake f=new Fake();f.data=payload;f.shortAfter=1;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         check("READER_SHORT_READ".equals(code(new Action(){public void run()throws Exception{h.read(0,100000);}})),"zero-byte ReadFile (stall/EOF) refused");h.close();check(f.balanced(),"handle closed after short read");}
        {final Fake f=new Fake();f.data=payload;f.readError=33;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         check("READER_LOCK_VIOLATION".equals(code(new Action(){public void run()throws Exception{h.read(0,10);}})),"ERROR_LOCK_VIOLATION on read coded");f.readError=6;
         check("READER_READ_FAILED".equals(code(new Action(){public void run()throws Exception{h.read(0,10);}})),"other read failure coded");h.close();}
        {final Fake f=new Fake();f.data=payload;f.seekFails=true;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         check("READER_SEEK_FAILED".equals(code(new Action(){public void run()throws Exception{h.read(0,10);}})),"seek failure coded");h.close();}
        {final Fake f=new Fake();f.data=payload;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         f.afterRead=new Runnable(){public void run(){f.data=Arrays.copyOf(f.data,10);}};
         check("READER_LENGTH_SHRANK".equals(code(new Action(){public void run()throws Exception{h.read(0,100);}})),"shrink during read refused after the bytes were taken");h.close();}
        {final Fake f=new Fake();f.data=payload;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         f.afterRead=new Runnable(){public void run(){f.deletePending=true;}};
         check("READER_DELETE_PENDING".equals(code(new Action(){public void run()throws Exception{h.read(0,100);}})),"delete-pending set during read refused");h.close();}
        {final Fake f=new Fake();f.data=payload;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         f.idxLow=100;check("READER_IDENTITY_CHANGED".equals(code(new Action(){public void run()throws Exception{h.identity();}})),"identity change on the same handle refused");h.close();}
        {final Fake f=new Fake();f.data=payload;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         f.data=Arrays.copyOf(payload,payload.length+50);check(h.length()==payload.length+50&&Arrays.equals(h.read(0,10),Arrays.copyOf(payload,10)),"growth is accepted (append-only writer)");
         f.data=Arrays.copyOf(payload,100);check("READER_LENGTH_SHRANK".equals(code(new Action(){public void run()throws Exception{h.length();}})),"shrink below an observed length refused");h.close();}
        {final Fake f=new Fake();f.fsError=true;
         String c;try{reader(f).open(src);c="OK";}catch(LinkageError err){c="ERROR";}
         check("ERROR".equals(c)&&f.balanced(),"Error thrown after open (first native bind) still closes the handle");}
        {final Fake f=new Fake();f.data=payload;f.closeFails=true;final WatermarkV2Prototype.ReadHandle h=reader(f).open(src);
         check("READER_HANDLE_INVALID".equals(code(new Action(){public void run()throws Exception{h.close();}}))&&"READER_HANDLE_CLOSED".equals(code(new Action(){public void run()throws Exception{h.read(0,1);}})),"CloseHandle failure coded; handle unusable afterwards");}
        System.out.println("RLWR1 fake test passed "+checks+" checks; adoption BLOCKED; owner decisions open");
    }
    private WindowsProductReaderFakeTest(){}
}
