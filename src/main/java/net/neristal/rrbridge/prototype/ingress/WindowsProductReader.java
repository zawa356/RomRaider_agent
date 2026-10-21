package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;

/**
 * RLWR1 — Windows product RLC2 reader candidate (Phase IX-2, synthetic only, <b>not adopted</b>).
 *
 * <p>Implements the unchanged {@link WatermarkV2Prototype.ReadOpener}/{@link WatermarkV2Prototype.ReadHandle} contract.
 * Every check is fail-closed and every failure is a {@link Refusal} carrying a stable code (plus a Win32 error number
 * where one exists) and never a path, Win32 message or payload byte:
 * <ul>
 * <li>Windows only; absolute, normalized, drive-letter path; no UNC/device/{@code \\?\} form; the drive must be a local
 *     fixed drive that is not a {@code subst} alias; the file system read <i>from the opened handle</i> must be NTFS.</li>
 * <li>Every existing path component is opened without following reparse points and refused if it is one
 *     (symlink, junction, mount point); the file itself is opened with {@code FILE_FLAG_OPEN_REPARSE_POINT}.</li>
 * <li>After opening, the handle's final normalized path must equal the requested path (a component swapped between the
 *     walk and the open is refused), the file must be a regular, non-reparse file with exactly one link and not
 *     delete-pending.</li>
 * <li>Identity (volume serial + 64-bit file index, {@code WINDOWS_NTFS_VOLUME_FILE_INDEX}), length and bytes all come
 *     from the same handle. Reads are bounded (≤64 KiB per {@code ReadFile}, ≤{@link #MAX_READ} per call), exact, never
 *     past the current length, and re-check identity/length/delete-pending afterwards.</li>
 * </ul>
 * The reader never writes, locks, truncates or repairs. There is no fallback to {@code java.io}. Directory durability,
 * process provenance and writer quiescence are not addressed (UNKNOWN / WRITER_UNPROVEN).
 *
 * <p>Open decisions are not taken here: the share mode with or without {@code FILE_SHARE_DELETE} is a constructor
 * argument and both are reported; JNA distribution licensing is {@code OWNER_DECISION_REQUIRED}. No {@code main}.
 */
public final class WindowsProductReader implements WatermarkV2Prototype.ReadOpener {
    public static final String VERSION="RLWR1";
    static final String BINDING="net.neristal.rrbridge.prototype.ingress.WindowsProductReaderJna";
    /** Per-call bound; equals the RLC2 journal quota so a single read cannot exceed one segment. */
    public static final int MAX_READ=(int)Math.min(Integer.MAX_VALUE,IngressJournalPrototype.MAX_JOURNAL_BYTES);
    static final int CHUNK=65536;

    /** Share mode while the reader holds a source. Adoption of either is an owner decision (design §4). */
    public enum ShareMode {
        READ_WRITE(0x1|0x2),READ_WRITE_DELETE(0x1|0x2|0x4);
        final int bits;ShareMode(int bits){this.bits=bits;}
    }

    /** Stable failure with a code and, where meaningful, the Win32 error number. No path or message. */
    public static final class Refusal extends IOException {
        public final String code;public final int win32;
        Refusal(String code){this(code,0);}
        // Message is the bare code so generic IOException handlers (preflight cause()) keep it; the Win32 number is a field.
        Refusal(String code,int win32){super(code);this.code=code;this.win32=win32;}
    }

    /** Minimal native surface. Raw handle values; structures are returned as little-endian byte images. */
    public interface Win32 {
        int pointerSize();
        long createFile(String path,int access,int share,int flags);
        int lastError();
        /** BY_HANDLE_FILE_INFORMATION, 52 bytes. */
        boolean handleInfo(long h,byte[] out52);
        /** FILE_STANDARD_INFO (GetFileInformationByHandleEx class 1), 24 bytes. */
        boolean standardInfo(long h,byte[] out24);
        /** GetFinalPathNameByHandleW(FILE_NAME_NORMALIZED|VOLUME_NAME_DOS) or null. */
        String finalPath(long h);
        /** File system name via GetVolumeInformationByHandleW or null. */
        String fileSystem(long h);
        int driveType(String root);
        /** QueryDosDeviceW for "C:" (first string) or null. */
        String dosDevice(String drive);
        boolean seek(long h,long offset);
        /** bytes read, or -1 on failure (lastError set). */
        int read(long h,byte[] buffer,int length);
        boolean close(long h);
    }

    static final int GENERIC_READ=0x80000000,FILE_READ_ATTRIBUTES=0x80;
    static final int FLAG_OPEN_REPARSE_POINT=0x00200000,FLAG_BACKUP_SEMANTICS=0x02000000;
    static final int ATTR_DIRECTORY=0x10,ATTR_REPARSE=0x400,DRIVE_FIXED=3;
    static final int ERROR_FILE_NOT_FOUND=2,ERROR_PATH_NOT_FOUND=3,ERROR_ACCESS_DENIED=5,ERROR_SHARING_VIOLATION=32,ERROR_LOCK_VIOLATION=33;

    private final Win32 api;private final ShareMode share;private final boolean windows;
    public WindowsProductReader(Win32 api,ShareMode share){this(api,share,System.getProperty("os.name","").startsWith("Windows"));}
    WindowsProductReader(Win32 api,ShareMode share,boolean windows){
        if(share==null)throw new IllegalArgumentException("share mode required");
        this.api=api;this.share=share;this.windows=windows;
    }

    /** JNA-backed reader. Any linkage problem (no JNA, no kernel32) is READER_NATIVE_UNAVAILABLE, never a fallback. */
    public static WindowsProductReader create(ShareMode share)throws Refusal {
        if(!System.getProperty("os.name","").startsWith("Windows"))throw new Refusal("READER_PLATFORM_UNSUPPORTED");
        // Fixed class name (never caller-supplied): keeps this class and its entry closure compilable/loadable without JNA.
        try{return new WindowsProductReader((Win32)Class.forName(BINDING).getDeclaredMethod("load").invoke(null),share);}
        catch(LinkageError|ReflectiveOperationException|RuntimeException unavailable){throw new Refusal("READER_NATIVE_UNAVAILABLE");}
    }

    public ShareMode shareMode(){return share;}

    /** Fixed status: what this candidate is and what stays undecided. Codes only. */
    public String statusJson(){
        return "{\"reader_version\":\""+VERSION+"\",\"share_mode\":\""+share+"\",\"identity_tag\":\"WINDOWS_NTFS_VOLUME_FILE_INDEX\","
            +"\"adoption\":\"BLOCKED\",\"share_delete_decision\":\"OWNER_DECISION_REQUIRED\",\"distribution_license\":\"OWNER_DECISION_REQUIRED\","
            +"\"network_drives\":\"REFUSED\",\"directory_durability\":\"UNKNOWN\",\"writer_quiescence\":\"WRITER_UNPROVEN\",\"synthetic_only\":true,"
            +"\"production_authorized\":false,\"ack_eligible\":false}";
    }

    boolean invalid(long h){return h==0||h==-1L||(api.pointerSize()==4&&h==0xffffffffL);}

    /** Validates path form, drive and every component, then opens. Closes the handle on every post-open refusal. */
    public WatermarkV2Prototype.ReadHandle open(Path source)throws IOException {
        if(!windows)throw new Refusal("READER_PLATFORM_UNSUPPORTED");
        if(source==null)throw new Refusal("READER_PATH_INVALID");
        String path=source.toString();
        if(!source.isAbsolute()||!source.equals(source.normalize()))throw new Refusal("READER_PATH_INVALID");
        if(path.startsWith("\\\\")||path.startsWith("//"))throw new Refusal("READER_FILESYSTEM_UNSUPPORTED");
        if(path.length()<4||!Character.isLetter(path.charAt(0))||path.charAt(1)!=':'||path.charAt(2)!='\\')throw new Refusal("READER_PATH_INVALID");
        if(path.indexOf('/')>=0||path.indexOf(':',2)>=0)throw new Refusal("READER_PATH_INVALID");
        String drive=path.substring(0,2).toUpperCase(Locale.ROOT);
        if(api.driveType(drive+"\\")!=DRIVE_FIXED)throw new Refusal("READER_FILESYSTEM_UNSUPPORTED");
        String device=api.dosDevice(drive);
        if(device==null||device.startsWith("\\??\\"))throw new Refusal("READER_FILESYSTEM_UNSUPPORTED");
        // Every existing directory component, opened without following reparse points.
        Path root=source.getRoot();Path at=root;
        for(int i=0;i<source.getNameCount()-1;i++){
            at=at.resolve(source.getName(i));
            long d=api.createFile(at.toString(),FILE_READ_ATTRIBUTES,0x7,FLAG_OPEN_REPARSE_POINT|FLAG_BACKUP_SEMANTICS);
            if(invalid(d))throw openFailure(api.lastError());
            try{
                byte[] info=new byte[52];
                if(!api.handleInfo(d,info))throw new Refusal("READER_IDENTITY_UNAVAILABLE",api.lastError());
                int attrs=le(info).getInt(0);
                if((attrs&ATTR_REPARSE)!=0)throw new Refusal("READER_PATH_LINK");
                if((attrs&ATTR_DIRECTORY)==0)throw new Refusal("READER_PATH_INVALID");
            }finally{api.close(d);}
        }
        long h=api.createFile(path,GENERIC_READ,share.bits,FLAG_OPEN_REPARSE_POINT);
        if(invalid(h))throw openFailure(api.lastError());
        Handle handle=new Handle(h);boolean ok=false;
        try{
            String fs=api.fileSystem(h);
            if(fs==null||!"NTFS".equalsIgnoreCase(fs))throw new Refusal("READER_FILESYSTEM_UNSUPPORTED");
            String fin=api.finalPath(h);
            if(fin==null)throw new Refusal("READER_IDENTITY_UNAVAILABLE",api.lastError());
            if(fin.startsWith("\\\\?\\")&&!fin.startsWith("\\\\?\\UNC\\"))fin=fin.substring(4);
            if(!fin.equalsIgnoreCase(path))throw new Refusal("READER_PATH_CHANGED");
            handle.opened=handle.check();
            ok=true;return handle;
        }finally{if(!ok)handle.closeQuietly();}// also on Error (e.g. UnsatisfiedLinkError on first bind)
    }

    private static Refusal openFailure(int err){
        if(err==ERROR_SHARING_VIOLATION)return new Refusal("READER_SHARING_VIOLATION",err);
        if(err==ERROR_FILE_NOT_FOUND||err==ERROR_PATH_NOT_FOUND)return new Refusal("READER_NOT_FOUND",err);
        return new Refusal("READER_OPEN_DENIED",err);
    }
    static ByteBuffer le(byte[] b){return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);}

    final class Handle implements WatermarkV2Prototype.ReadHandle {
        private long h;private boolean closed;WatermarkV2Prototype.SourceIdentity opened;private long maxLength=-1;
        Handle(long h){this.h=h;}
        private long live()throws Refusal{if(closed)throw new Refusal("READER_HANDLE_CLOSED");return h;}

        /** Identity + standard info from this handle; refuses directory, reparse, extra links, delete-pending. */
        WatermarkV2Prototype.SourceIdentity check()throws IOException {
            long x=live();
            byte[] info=new byte[52];
            if(!api.handleInfo(x,info))throw new Refusal("READER_IDENTITY_UNAVAILABLE",api.lastError());
            ByteBuffer b=le(info);int attrs=b.getInt(0);
            if((attrs&ATTR_DIRECTORY)!=0)throw new Refusal("READER_NOT_REGULAR_FILE");
            if((attrs&ATTR_REPARSE)!=0)throw new Refusal("READER_REPARSE_POINT");
            int serial=b.getInt(28),links=b.getInt(40),high=b.getInt(44),low=b.getInt(48);
            if(high==0&&low==0)throw new Refusal("READER_IDENTITY_UNAVAILABLE");
            byte[] std=new byte[24];
            if(!api.standardInfo(x,std))throw new Refusal("READER_IDENTITY_UNAVAILABLE",api.lastError());
            ByteBuffer s=le(std);
            if(s.get(20)!=0)throw new Refusal("READER_DELETE_PENDING");
            if(s.get(21)!=0)throw new Refusal("READER_NOT_REGULAR_FILE");
            if(links!=1||s.getInt(16)!=1)throw new Refusal("READER_HARDLINK_PRESENT");
            long eof=s.getLong(8),sizeInfo=((long)b.getInt(32)<<32)|(b.getInt(36)&0xffffffffL);
            if(eof<0||eof!=sizeInfo)throw new Refusal("READER_LENGTH_UNSTABLE");
            if(eof<maxLength)throw new Refusal("READER_LENGTH_SHRANK");
            maxLength=eof;
            WatermarkV2Prototype.SourceIdentity id=new WatermarkV2Prototype.SourceIdentity(WatermarkV2Prototype.IdentityTag.WINDOWS_NTFS_VOLUME_FILE_INDEX,
                ByteBuffer.allocate(12).putInt(serial).putInt(high).putInt(low).array());
            if(opened!=null&&!opened.equals(id))throw new Refusal("READER_IDENTITY_CHANGED");
            return id;
        }
        public WatermarkV2Prototype.SourceIdentity identity()throws IOException {return check();}
        public long length()throws IOException {check();return maxLength;}
        public byte[] read(long offset,int length)throws IOException {
            long x=live();
            if(offset<0||length<0||length>MAX_READ)throw new Refusal("READER_BOUNDS");
            if(offset>Long.MAX_VALUE-length)throw new Refusal("READER_BOUNDS");
            check();
            if(offset+length>maxLength)throw new Refusal("READER_BOUNDS");
            byte[] out=new byte[length];int done=0;
            if(length>0&&!api.seek(x,offset))throw new Refusal("READER_SEEK_FAILED",api.lastError());
            while(done<length){
                int want=Math.min(CHUNK,length-done);byte[] part=new byte[want];
                int n=api.read(x,part,want);
                if(n<0){int err=api.lastError();throw new Refusal(err==ERROR_LOCK_VIOLATION?"READER_LOCK_VIOLATION":"READER_READ_FAILED",err);}
                if(n==0)throw new Refusal("READER_SHORT_READ");
                if(n>want)throw new Refusal("READER_HANDLE_INVALID");
                System.arraycopy(part,0,out,done,n);done+=n;
            }
            check();// identity, delete-pending and no shrink after the bytes were taken
            return out;
        }
        public void close()throws IOException {
            if(closed)return;
            closed=true;
            if(!api.close(h))throw new Refusal("READER_HANDLE_INVALID",api.lastError());
        }
        void closeQuietly(){try{close();}catch(IOException ignored){}}
    }
}
