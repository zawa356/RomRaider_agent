package net.neristal.rrbridge.prototype.ingress;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * RLWR1 kernel32 binding (Phase IX-2). The only product class that touches JNA. Structures are read as raw little-endian
 * byte images of fixed size (52 / 24 bytes) so no JNA structure alignment rule is relied on for x86 vs x64.
 * JNA 5.14.0 must be the pinned JAR (SHA-256 34ed1e1f…c6); its distribution licence is OWNER_DECISION_REQUIRED.
 */
final class WindowsProductReaderJna implements WindowsProductReader.Win32 {
    interface Kernel32 extends StdCallLibrary {
        Pointer CreateFileW(WString path,int access,int share,Pointer security,int disposition,int flags,Pointer template);
        int GetFileInformationByHandle(Pointer h,Pointer info);
        int GetFileInformationByHandleEx(Pointer h,int infoClass,Pointer info,int size);
        int GetFinalPathNameByHandleW(Pointer h,char[] buffer,int length,int flags);
        int GetVolumeInformationByHandleW(Pointer h,char[] volume,int volumeLength,IntByReference serial,IntByReference maxComponent,IntByReference fsFlags,char[] fsName,int fsNameLength);
        int GetDriveTypeW(WString root);
        int QueryDosDeviceW(WString device,char[] target,int length);
        int SetFilePointerEx(Pointer h,long distance,LongByReference position,int method);
        int ReadFile(Pointer h,byte[] data,int bytes,IntByReference read,Pointer overlapped);
        int CloseHandle(Pointer h);
    }
    private static final int OPEN_EXISTING=3,FILE_STANDARD_INFO=1,PATH_MAX=32768;
    private final Kernel32 k;
    private WindowsProductReaderJna(Kernel32 k){this.k=k;}
    /** Reflective factory used by {@link WindowsProductReader#create}. */
    static WindowsProductReaderJna load(){return new WindowsProductReaderJna(Native.load("kernel32",Kernel32.class));}

    public int pointerSize(){return Native.POINTER_SIZE;}
    public int lastError(){return Native.getLastError();}
    private static Pointer p(long h){return new Pointer(h);}
    public long createFile(String path,int access,int share,int flags){
        Pointer h=k.CreateFileW(new WString(path),access,share,null,OPEN_EXISTING,flags,null);
        return h==null?0:Pointer.nativeValue(h);
    }
    public boolean handleInfo(long h,byte[] out){
        Memory m=new Memory(52);m.clear();
        if(k.GetFileInformationByHandle(p(h),m)==0)return false;
        m.read(0,out,0,52);return true;
    }
    public boolean standardInfo(long h,byte[] out){
        Memory m=new Memory(24);m.clear();
        if(k.GetFileInformationByHandleEx(p(h),FILE_STANDARD_INFO,m,24)==0)return false;
        m.read(0,out,0,24);return true;
    }
    public String finalPath(long h){
        char[] b=new char[PATH_MAX];int n=k.GetFinalPathNameByHandleW(p(h),b,b.length,0);
        return n<=0||n>=b.length?null:new String(b,0,n);
    }
    public String fileSystem(long h){
        char[] fs=new char[64];
        if(k.GetVolumeInformationByHandleW(p(h),null,0,new IntByReference(),new IntByReference(),new IntByReference(),fs,fs.length)==0)return null;
        int n=0;while(n<fs.length&&fs[n]!=0)n++;return new String(fs,0,n);
    }
    public int driveType(String root){return k.GetDriveTypeW(new WString(root));}
    public String dosDevice(String drive){
        char[] b=new char[1024];int n=k.QueryDosDeviceW(new WString(drive),b,b.length);
        if(n<=0)return null;int e=0;while(e<n&&b[e]!=0)e++;return new String(b,0,e);
    }
    public boolean seek(long h,long offset){return k.SetFilePointerEx(p(h),offset,new LongByReference(),0)!=0;}
    public int read(long h,byte[] buffer,int length){
        IntByReference n=new IntByReference();
        return k.ReadFile(p(h),buffer,length,n,null)==0?-1:n.getValue();
    }
    public boolean close(long h){return k.CloseHandle(p(h))!=0;}
}
