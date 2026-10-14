package net.neristal.rrbridge.prototype.ingress;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** TEST ONLY native writer ownership/binding primitive. No marker, DB, ACK or Logger. */
public final class WindowsNativeWriterTest {
    public interface Api extends WindowsNativeIdentityTest.Api {
        int WriteFile(Pointer h, byte[] data, int bytes, IntByReference written, Pointer overlapped);
        int FlushFileBuffers(Pointer h);
        int SetFilePointerEx(Pointer h, long distance, LongByReference position, int method);
        int LockFileEx(Pointer h, int flags, int reserved, int low, int high, Overlapped overlap);
        int UnlockFileEx(Pointer h, int reserved, int low, int high, Overlapped overlap);
    }
    public static final class Overlapped extends Structure {
        public Pointer internal, internalHigh;
        public int offset, offsetHigh;
        public Pointer event;
        protected List<String> getFieldOrder() { return Arrays.asList("internal", "internalHigh", "offset", "offsetHigh", "event"); }
    }
    private static final Api API = Native.load("kernel32", Api.class);
    private enum Stage { WRITTEN, FLUSHED }
    private interface Hook { void at(Stage stage) throws IOException; }
    private static final Hook NONE = new Hook() { public void at(Stage s) {} };
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    private static byte[] sha(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static long size(Pointer h) throws IOException {
        WindowsNativeIdentityTest.Info i = new WindowsNativeIdentityTest.Info();
        if (API.GetFileInformationByHandle(h, i) == 0) throw new IOException("native size unavailable");
        i.read(); return ((long)i.words[8] << 32) | (i.words[9] & 0xffffffffL);
    }
    private static void seek(Pointer h, long offset) throws IOException {
        if (API.SetFilePointerEx(h, offset, new LongByReference(), 0) == 0) throw new IOException("native seek failed");
    }
    private static byte[] readOwned(Pointer h) throws IOException {
        long length = size(h);
        if (length < 0 || length > IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("source exceeds quota");
        byte[] all = new byte[(int)length]; int at = 0; seek(h, 0);
        while (at < all.length) {
            byte[] part = new byte[Math.min(4096, all.length-at)]; IntByReference count = new IntByReference();
            if (API.ReadFile(h, part, part.length, count, null) == 0 || count.getValue() < 1 || count.getValue() > part.length)
                throw new IOException("owned read incomplete");
            System.arraycopy(part, 0, all, at, count.getValue()); at += count.getValue();
        }
        return all;
    }
    private static long validate(byte[] raw) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(raw); long sequence=0;
        while (b.hasRemaining()) {
            if (b.remaining() < 20) throw new IOException("partial header retained");
            int magic=b.getInt(), length=b.getInt(); long next=b.getLong(); int crc=b.getInt();
            if (magic!=IngressJournalPrototype.FRAME_MAGIC || length<1 || length>IngressJournalPrototype.MAX_ROW_BYTES ||
                    length>b.remaining() || next!=sequence+1) throw new IOException("invalid frame retained");
            byte[] row=new byte[length]; b.get(row); CRC32 actual=new CRC32();actual.update(row);
            if ((int)actual.getValue()!=crc) throw new IOException("CRC mismatch retained");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(row)).toString();
            if (text.isEmpty() || text.indexOf('\r')>=0 || text.indexOf('\n')>=0) throw new IOException("invalid row retained");
            sequence=next;
        }
        return sequence;
    }
    private static final class Proof {
        final String identity; final long sequence, bytes; final byte[] hash;
        Proof(String i,long s,long n,byte[] h) { identity=i;sequence=s;bytes=n;hash=h.clone(); }
    }
    private static final class Writer implements AutoCloseable {
        private Pointer handle;
        private RandomAccessFile coordination;
        private FileLock owner;
        private final Overlapped guard = new Overlapped();
        private boolean locked, failed;
        private String identity;
        private long sequence, length;
        private byte[] prefix;
        private final long quota;
        Writer(Path path, long quota) throws IOException {
            this.quota=quota;
            if (quota<21 || quota>IngressJournalPrototype.MAX_JOURNAL_BYTES) throw new IOException("invalid quota");
            // Require existing dedicated source; never create/repair the original.
            try (WindowsNativeIdentityTest.Handle ignored = new WindowsNativeIdentityTest.Handle(path)) { ignored.identity(); }
            Path lock = path.resolveSibling(path.getFileName()+".writer.lock");
            if (Files.isSymbolicLink(lock) || (Files.exists(lock) && !Files.isRegularFile(lock))) throw new IOException("unsafe coordination lock");
            try {
                coordination=new RandomAccessFile(lock.toFile(),"rw");
                try { owner=coordination.getChannel().tryLock(); }
                catch(java.nio.channels.OverlappingFileLockException e) { throw new IOException("coordination conflict",e); }
                if (owner==null) throw new IOException("coordination conflict");
                // READ|WRITE and share READ|WRITE; omit SHARE_DELETE to pin the current name while held.
                handle=API.CreateFileW(new WString(path.toAbsolutePath().toString()),0xc0000000,3,null,3,0x00200000,null);
                if(handle==null || Pointer.nativeValue(handle)==-1L || (Native.POINTER_SIZE==4 && Pointer.nativeValue(handle)==0xffffffffL)) {
                    handle=null; throw new IOException("native write open denied");
                }
                guard.offset=(int)IngressJournalPrototype.MAX_JOURNAL_BYTES; guard.write();
                if(API.LockFileEx(handle,3,0,1,0,guard)==0) throw new IOException("legacy guard conflict");
                locked=true; identity=WindowsNativeIdentityTest.id(handle);
                byte[] original=readOwned(handle);sequence=validate(original);length=original.length;prefix=sha(original);
                if(length>quota) throw new IOException("retained source exceeds writer quota");
                JournalIdentityPrototype.readExisting(path.toFile());
            } catch(IOException | RuntimeException fault) {
                try { close(); } catch(IOException suppressed) { fault.addSuppressed(suppressed); }
                throw fault;
            }
        }
        Proof append(String row, Hook hook) throws IOException {
            if(handle==null || failed) throw new IOException("writer stopped, review required");
            try {
                byte[] raw=readOwned(handle);
                if(!identity.equals(WindowsNativeIdentityTest.id(handle)) || raw.length!=length || !Arrays.equals(prefix,sha(raw)))
                    throw new IOException("owned original changed");
                if(row==null) throw new IOException("row required");
                ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(row));
                byte[] body=new byte[encoded.remaining()];encoded.get(body);
                if(body.length<1 || body.length>IngressJournalPrototype.MAX_ROW_BYTES || row.indexOf('\r')>=0 || row.indexOf('\n')>=0)
                    throw new IOException("single bounded row required");
                if(sequence==Long.MAX_VALUE || 20L+body.length>quota-length) throw new IOException("quota/sequence stop, retain original");
                CRC32 crc=new CRC32();crc.update(body);
                byte[] frame=ByteBuffer.allocate(20+body.length).putInt(IngressJournalPrototype.FRAME_MAGIC).putInt(body.length)
                    .putLong(sequence+1).putInt((int)crc.getValue()).put(body).array();
                seek(handle,length);IntByReference written=new IntByReference();
                if(API.WriteFile(handle,frame,frame.length,written,null)==0 || written.getValue()!=frame.length)
                    throw new IOException("partial native write retained");
                hook.at(Stage.WRITTEN);
                if(API.FlushFileBuffers(handle)==0) throw new IOException("native flush failed, error="+Native.getLastError());
                hook.at(Stage.FLUSHED);
                byte[] next=readOwned(handle);
                if(!identity.equals(WindowsNativeIdentityTest.id(handle)) || next.length!=length+frame.length ||
                    !Arrays.equals(prefix,sha(Arrays.copyOf(next,(int)length))) || validate(next)!=sequence+1)
                    throw new IOException("flushed original changed");
                length=next.length;sequence++;prefix=sha(next);
                return new Proof(identity,sequence,length,prefix);
            } catch(IOException | RuntimeException fault) { failed=true;throw fault; }
        }
        public void close() throws IOException {
            IOException problem=null;
            if(handle!=null) {
                Pointer h=handle;handle=null;
                if(locked && API.UnlockFileEx(h,0,1,0,guard)==0) problem=new IOException("native unlock failed");
                locked=false;
                if(API.CloseHandle(h)==0 && problem==null) problem=new IOException("native close failed");
            }
            if(owner!=null) { try { owner.release(); } catch(IOException e) { if(problem==null)problem=e;else problem.addSuppressed(e); } owner=null; }
            if(coordination!=null) { try { coordination.close(); } catch(IOException e) { if(problem==null)problem=e;else problem.addSuppressed(e); } coordination=null; }
            if(problem!=null) throw problem;
        }
    }
    private static Path seed(String tag) throws Exception {
        Path dir=Files.createTempDirectory("romlogger-native-writer-"+tag+"-");
        Path source=dir.resolve("source.rlj");Files.write(source,new byte[0]);JournalIdentityPrototype.open(source.toFile());return dir;
    }
    private static Process start(Path dir,String mode,String tag) throws IOException {
        String exe=new File(new File(System.getProperty("java.home"),"bin"),"java.exe").getPath();
        String main=mode.equals("old") || mode.equals("split") ? SplitLockJournalPrototypeTest.class.getName() : WindowsNativeWriterTest.class.getName();
        return new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),main,mode,dir.toString(),tag)
            .redirectErrorStream(true).redirectOutput(dir.resolve(tag+".log").toFile()).start();
    }
    private static void ready(Process p,Path flag) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!Files.exists(flag)) { if(!p.isAlive() || System.nanoTime()>end)throw new AssertionError("no child flag "+flag);Thread.sleep(10); }
    }
    private static void kill(Process p) throws Exception { if(p!=null && p.isAlive()){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);} }
    private static void child(String mode,Path dir,String tag) throws Exception {
        Writer w;
        try { w=new Writer(dir.resolve("source.rlj"),IngressJournalPrototype.MAX_JOURNAL_BYTES); }
        catch(IOException denied){Files.write(dir.resolve(tag+".denied"),new byte[0]);return;}
        try {
            if(mode.startsWith("halt")) {
                w.append("E\tsynthetic\tnative-halt",new Hook(){ public void at(Stage s) {
                    System.out.println("OBSERVED "+s);
                    if((mode.equals("halt-before") && s==Stage.WRITTEN) || (mode.equals("halt-after") && s==Stage.FLUSHED))Runtime.getRuntime().halt(73);
                }});throw new AssertionError("halt missed");
            }
            Files.write(dir.resolve(tag+".ready"),new byte[0]);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!Files.exists(dir.resolve(tag+".stop"))){if(System.nanoTime()>end)throw new IOException("child deadline");Thread.sleep(10);}
        }finally{w.close();}
    }
    private static void matrix(String own,String other) throws Exception {
        Path dir=seed("matrix");Path source=dir.resolve("source.rlj");
        // Seed one valid frame so legacy child does not append to an empty file.
        try(IngressJournalPrototype w=new IngressJournalPrototype(source.toFile(),1024)){w.append("E\tsynthetic\tseed");}
        byte[] original=Files.readAllBytes(source);Process owner=null,contender=null;
        try {
            owner=start(dir,own,"owner");ready(owner,dir.resolve("owner.ready"));
            contender=start(dir,other,"challenger");ready(contender,dir.resolve("challenger.denied"));
            check(contender.waitFor(10,TimeUnit.SECONDS) && contender.exitValue()==0,own+" -> "+other+" native guard excludes second JVM");
            if(own.equals("native")) {
                try(WindowsNativeIdentityTest.Handle r=new WindowsNativeIdentityTest.Handle(source,true)) {
                    check(Arrays.equals(original,r.readPrefix(r.identity(),original.length,sha(original))),"separate native reader succeeds under native guard");
                }
            }
            Files.write(dir.resolve("owner.stop"),new byte[0]);
            check(owner.waitFor(15,TimeUnit.SECONDS) && owner.exitValue()==0,"native matrix owner exits "+own);
            check(Arrays.equals(original,Files.readAllBytes(source)),"native ownership matrix preserves original");
        }finally{kill(contender);kill(owner);}
    }
    public static void main(String[] args) throws Exception {
        if(args.length==3){child(args[0],new File(args[1]).toPath(),args[2]);return;}
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");
        if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"native writer actual model "+model);
        check(new Overlapped().size()==(Native.POINTER_SIZE==4?20:32),"OVERLAPPED native layout matches actual pointer width");
        Path dir=seed("flush");Path source=dir.resolve("source.rlj");Proof proof;
        try(Writer w=new Writer(source,1024)) {
            proof=w.append("E\tsynthetic\tnative",NONE);
            check(proof.sequence==1 && proof.bytes>20,"same native handle write/flush returns validated prefix snapshot");
            try { Files.move(source,dir.resolve("moved.rlj"));throw new AssertionError("delete-share pin failed"); }
            catch(java.nio.file.FileSystemException denied){check(Files.exists(source),"native writer denies rename while held (no SHARE_DELETE)");}
            try(WindowsNativeIdentityTest.Handle r=new WindowsNativeIdentityTest.Handle(source,true)) {
                check(r.identity().equals(proof.identity) && Arrays.equals(proof.hash,sha(r.readPrefix(proof.identity,(int)proof.bytes,proof.hash))),
                    "separate reader agrees with flushed writer handle identity/hash");
            }
        }
        check(LiveJournalReadProbe.inspect(source.toFile()).frames==1,"native RLJ1 frame interoperates with closed legacy probe");
        try(Writer reopened=new Writer(source,1024)){check(reopened.append("E\tsynthetic\trestart",NONE).sequence==2,"native writer reopen validates sequence/prefix");}
        check(API.FlushFileBuffers(Pointer.createConstant(-1))==0,"invalid native flush handle rejected");
        Path quotaDir=seed("quota");Path quota=quotaDir.resolve("source.rlj");
        try(Writer w=new Writer(quota,21)){w.append("x",NONE);
            try{w.append("y",NONE);throw new AssertionError("quota accepted");}catch(IOException denied){check(quota.toFile().length()==21,"quota stop retains flushed frame");}
            try{w.append("z",NONE);throw new AssertionError("failure latch absent");}catch(IOException denied){check(true,"quota failure latches writer");}}
        Path partialDir=seed("partial");Path partial=partialDir.resolve("source.rlj");
        byte[] broken=new byte[]{0x52,0x4c,0x4a,0x31,0,0};Files.write(partial,broken);
        try(Writer ignored=new Writer(partial,1024)){throw new AssertionError("partial source accepted");}
        catch(IOException denied){check(Arrays.equals(broken,Files.readAllBytes(partial)),"native constructor rejects partial frame without truncation/repair");}
        Path tamperDir=seed("tamper");Path tamper=tamperDir.resolve("source.rlj");
        try(Writer w=new Writer(tamper,1024)){
            w.append("x",NONE);byte[] modified=Files.readAllBytes(tamper);modified[modified.length-1]^=1;Files.write(tamper,modified);
            try{w.append("y",NONE);throw new AssertionError("modified prefix accepted");}
            catch(IOException denied){check(Arrays.equals(modified,Files.readAllBytes(tamper)),"native writer rejects in-place prefix tamper and retains evidence");}
        }
        Path invalidDir=seed("invalid-row");Path invalid=invalidDir.resolve("source.rlj");
        try(Writer w=new Writer(invalid,1024)){
            try{w.append("\ud800",NONE);throw new AssertionError("malformed surrogate accepted");}
            catch(IOException denied){check(invalid.toFile().length()==0,"malformed UTF16 input rejected before native write");}
        }
        Pointer readOnly=API.CreateFileW(new WString(source.toAbsolutePath().toString()),0x80000000,7,null,3,0x00200000,null);
        try{
            WindowsNativeIdentityTest.id(readOnly);
            check(API.FlushFileBuffers(readOnly)==0,"real native FlushFileBuffers denies read-only handle");
        }finally{check(API.CloseHandle(readOnly)!=0,"read-only native flush test closes owned handle");}
        Path closedDir=seed("closed");Writer closed=new Writer(closedDir.resolve("source.rlj"),1024);closed.close();
        try{closed.append("x",NONE);throw new AssertionError("closed writer appended");}catch(IOException denied){check(true,"closed native writer rejects append");}
        for(final Stage fault:Stage.values()) {
            Path fdir=seed("fault");Path f=fdir.resolve("source.rlj");
            try(Writer w=new Writer(f,1024)) {
                try{w.append("x",new Hook(){public void at(Stage s)throws IOException{if(s==fault)throw new IOException("injected "+s);}});throw new AssertionError("hook missed");}
                catch(IOException expected){check(f.toFile().length()==21,"ambiguous original retained after injected "+fault);}
                try{w.append("y",NONE);throw new AssertionError("continued after fault");}catch(IOException denied){check(true,"fault latches writer "+fault);}
            }
        }
        for(String mode:new String[]{"halt-before","halt-after"}) {
            Path hdir=seed(mode);Process p=start(hdir,mode,"crash");
            try{check(p.waitFor(15,TimeUnit.SECONDS) && p.exitValue()==73,"separate JVM "+mode+" exits by Runtime.halt");}
            finally{kill(p);}
            String log=new String(Files.readAllBytes(hdir.resolve("crash.log")),StandardCharsets.UTF_8);
            check(mode.equals("halt-after") ? log.contains("OBSERVED FLUSHED") : !log.contains("OBSERVED FLUSHED"),"flush observation distinguished for "+mode);
            check(Files.exists(hdir.resolve("source.rlj")) && !Files.exists(hdir.resolve("source.rlj.commit")),"halt retains source, no fabricated watermark "+mode);
            if(mode.equals("halt-after"))check(LiveJournalReadProbe.inspect(hdir.resolve("source.rlj").toFile()).frames==1,"post-flush JVM halt reopens retained frame (not physical power loss)");
        }
        matrix("old","native");matrix("native","old");matrix("split","native");matrix("native","split");matrix("native","native");
        System.out.println("WindowsNativeWriterTest passed "+checks+" checks; handle/flush primitive ONLY, no marker/DB/ACK/power-loss guarantee");
    }
}
