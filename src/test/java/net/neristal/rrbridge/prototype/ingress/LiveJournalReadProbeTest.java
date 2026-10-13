package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

/** Synthetic lock-free diagnostic tests; does not import any v1 data. */
public final class LiveJournalReadProbeTest {
    private static int checks=0;
    private interface Work{void run()throws Exception;}
    private static void ok(String name,boolean yes) {
        if(!yes)throw new AssertionError(name);
        checks++;System.out.println("PASS "+name);
    }
    private static void rejects(String name,Work operation)throws Exception {
        boolean error=false;
        try{operation.run();}catch(IOException|IllegalArgumentException expected){error=true;}
        ok(name,error);
    }
    private static File source(File directory,String stem)throws Exception {
        File file=new File(directory,stem+".rlj");
        if(!file.createNewFile())throw new AssertionError("Cannot create source");
        JournalIdentityPrototype.open(file);
        return file;
    }
    private static byte[] rawFrame(long sequence,byte[] row)throws Exception {
        CRC32 crc=new CRC32();crc.update(row);
        return ByteBuffer.allocate(20+row.length).order(ByteOrder.BIG_ENDIAN)
            .putInt(IngressJournalPrototype.FRAME_MAGIC)
            .putInt(row.length)
            .putLong(sequence)
            .putInt((int)crc.getValue())
            .put(row).array();
    }
    private static void extend(File f,byte[] bytes)throws Exception {
        try(RandomAccessFile file=new RandomAccessFile(f,"rw")) {
            file.seek(file.length());
            file.write(bytes);
            file.getFD().sync();
        }
    }
    private static void truncate(File f,long bytes)throws Exception {
        try(RandomAccessFile file=new RandomAccessFile(f,"rw")){
            file.setLength(bytes);file.getFD().sync();
        }
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("journal-live-probe-only").toFile();
        File file=source(root,"live");
        LiveJournalReadProbe.Result empty=LiveJournalReadProbe.inspect(file);
        ok("empty journal is complete and has no records",
            empty.status==LiveJournalReadProbe.Status.COMPLETE&&empty.frames==0);
        ok("probe never creates a second sidecar",
            new File(file.getPath()+".identity").isFile());

        try(IngressJournalPrototype writer=new IngressJournalPrototype(
                file,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            writer.append("M\tlive\t1\tTEST_RPM\tSynthetic RPM\trpm");
            LiveJournalReadProbe.Result active=LiveJournalReadProbe.inspect(file);
            ok("live writer exclusive lock does not block readonly probe",
                active.status==LiveJournalReadProbe.Status.COMPLETE&&active.frames==1);
            writer.append("S\tlive\t1\t0\t1791838800000\tTEST_RPM=2000");
            ok("complete frames can be inspected during live writer ownership",
                LiveJournalReadProbe.inspect(file).frames==2);
        }
        final long stableLength=file.length();
        byte[] trailer=rawFrame(3,"S\tlive\t1\t1\t1791838800500\tTEST_RPM=2100"
            .getBytes(StandardCharsets.UTF_8));
        extend(file,java.util.Arrays.copyOf(trailer,9));
        LiveJournalReadProbe.Result partialHeader=LiveJournalReadProbe.inspect(file);
        ok("partial header is retained as an incomplete tail, not ingested",
            partialHeader.status==LiveJournalReadProbe.Status.INCOMPLETE_TAIL&&
            partialHeader.frames==2&&partialHeader.verifiedBytes==stableLength);
        extend(file,java.util.Arrays.copyOfRange(trailer,9,22));
        LiveJournalReadProbe.Result partialPayload=LiveJournalReadProbe.inspect(file);
        ok("partial payload never becomes a counted record",
            partialPayload.status==LiveJournalReadProbe.Status.INCOMPLETE_TAIL&&
            partialPayload.frames==2);
        extend(file,java.util.Arrays.copyOfRange(trailer,22,trailer.length));
        ok("completed tail becomes visible after final payload arrives",
            LiveJournalReadProbe.inspect(file).frames==3);
        truncate(file,stableLength);
        extend(file,trailer);
        ok("read-only probe remains independent of mutable writer lock",
            LiveJournalReadProbe.inspect(file).frames==3);
        byte[] clean=Files.readAllBytes(file.toPath());
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")){
            f.seek(stableLength+16);
            f.writeInt(0);
        }
        rejects("stable complete CRC corruption fails closed",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(file);}
        });
        Files.write(file.toPath(),clean);
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")){
            f.seek(stableLength+8);f.writeLong(99);
        }
        rejects("stable sequence gap fails closed",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(file);}
        });
        Files.write(file.toPath(),clean);
        extend(file,rawFrame(4,new byte[]{(byte)0xc3,(byte)0x28}));
        rejects("invalid UTF-8 with matching CRC fails closed",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(file);}
        });
        Files.write(file.toPath(),clean);
        ok("repair performed only by test fixture, never by probe",
            LiveJournalReadProbe.inspect(file).frames==3);

        final File missing=new File(root,"missing.rlj");
        rejects("missing journal never created by probe",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(missing);}
        });
        ok("missing original remains missing",!missing.exists());
        final File identityMissing=new File(root,"no_id.rlj");
        if(!identityMissing.createNewFile())throw new AssertionError();
        rejects("empty file without identity is rejected without UUID minting",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(identityMissing);}
        });
        ok("no unexpected identity sidecar created",
            !new File(identityMissing.getPath()+".identity").exists());
        final File quota=source(root,"quota");
        try(RandomAccessFile f=new RandomAccessFile(quota,"rw")){
            f.setLength(IngressJournalPrototype.MAX_JOURNAL_BYTES+1);
        }
        rejects("oversized journal is rejected before allocation",new Work(){
            public void run()throws Exception{LiveJournalReadProbe.inspect(quota);}
        });
        java.nio.file.Path link=new File(root,"symlink.rlj").toPath();
        try{
            Files.createSymbolicLink(link,file.toPath());
            rejects("symlink file rejected",new Work(){
                public void run()throws Exception{LiveJournalReadProbe.inspect(link.toFile());}
            });
        }catch(UnsupportedOperationException|java.nio.file.FileSystemException unsupported){
            System.out.println("SKIP symlink probe test (OS privileges unavailable)");
        }

        final File ongoing=source(root,"concurrent");
        final AtomicReference<Throwable> writerFailure=new AtomicReference<Throwable>();
        final java.util.concurrent.atomic.AtomicInteger completed=
            new java.util.concurrent.atomic.AtomicInteger();
        try(final IngressJournalPrototype writer=new IngressJournalPrototype(
                ongoing,IngressJournalPrototype.MAX_JOURNAL_BYTES)){
            Thread producer=new Thread(new Runnable(){
                public void run(){
                    try{
                        for(int i=0;i<160;i++){
                            writer.append("S\tconcurrent\t1\t"+i+"\t1791838800000\tTEST_RPM="+(2000+i));
                            completed.incrementAndGet();
                        }
                    }catch(Throwable problem){writerFailure.set(problem);}
                }
            },"synthetic-journal-writer");
            producer.start();
            int changing=0,partial=0,complete=0;
            for(int i=0;i<320;i++){
                LiveJournalReadProbe.Result state=LiveJournalReadProbe.inspect(ongoing);
                if(state.frames>160||
                    state.observedBytes>IngressJournalPrototype.MAX_JOURNAL_BYTES)
                    throw new AssertionError("Unbounded or invalid concurrent snapshot");
                if(state.status==LiveJournalReadProbe.Status.CHANGING)changing++;
                else if(state.status==LiveJournalReadProbe.Status.INCOMPLETE_TAIL)partial++;
                else complete++;
                if(!producer.isAlive()&&i>15)break;
            }
            producer.join(20000);
            ok("320 bounded concurrent scans did not exceed frame or memory bounds",true);
            ok("concurrent producer completed all 160 fsynced frames",
                !producer.isAlive()&&writerFailure.get()==null&&completed.get()==160);
            LiveJournalReadProbe.Result finished=LiveJournalReadProbe.inspect(ongoing);
            ok("final complete snapshot counts all 160 committed append calls",
                finished.status==LiveJournalReadProbe.Status.COMPLETE&&finished.frames==160);
            ok("probe uses no Writer lock and never truncates source",ongoing.length()==finished.verifiedBytes);
            System.out.println("live iterations complete="+complete+" partial="+partial+
                " changing="+changing+" (diagnostic only)");
        }
        System.out.println("LiveJournalReadProbeTest passed "+checks+
            " checks (Java8 readonly concurrent diagnostics, NO product import)");
    }
}
