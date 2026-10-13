package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32;

/**
 * Bounded, strictly READ-ONLY diagnostic probe for a journal with a live
 * writer. It never obtains a FileLock, opens a write handle, or changes files.
 *
 * WARNING: CRC-valid and complete DOES NOT mean fsynced/committed. This
 * class MUST NOT advance v1 SQLite cursors or issue Server ACKs. A separate
 * durable commit watermark protocol is required before live ingestion.
 */
public final class LiveJournalReadProbe {
    public enum Status { COMPLETE, INCOMPLETE_TAIL, CHANGING }
    public static final class Result {
        public final Status status;
        public final long frames, verifiedBytes, observedBytes;
        private Result(Status s,long n,long verified,long observed){
            status=s;frames=n;verifiedBytes=verified;observedBytes=observed;
        }
    }
    private LiveJournalReadProbe(){}

    private static BasicFileAttributes attributes(File path)throws IOException {
        if(Files.isSymbolicLink(path.toPath())||!path.isFile())
            throw new IOException("Invalid journal file");
        return Files.readAttributes(path.toPath(),BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
    }
    private static boolean changed(BasicFileAttributes before,
                                   BasicFileAttributes after,long beforeSize,
                                   long afterSize){
        Object a=before.fileKey(),b=after.fileKey();
        return (a!=null&&b!=null&&!a.equals(b))||
            beforeSize!=afterSize||
            !before.lastModifiedTime().equals(after.lastModifiedTime());
    }
    private static void fullyRead(FileChannel ch,ByteBuffer data,long offset)
            throws IOException {
        int stall=0;
        while(data.hasRemaining()){
            int n=ch.read(data,offset);
            if(n<0)throw new IOException("Journal truncated during bounded read");
            if(n==0 && ++stall>3)throw new IOException("Journal changed during bounded read");
            if(n>0){offset+=n;stall=0;}
        }
        data.flip();
    }
    private static void validateRow(byte[] raw)throws IOException {
        try {
            String text=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString();
            if(text.isEmpty()||text.indexOf('\r')>=0||text.indexOf('\n')>=0)
                throw new IOException("Invalid journal row");
        }catch(CharacterCodingException bad){
            throw new IOException("Invalid UTF-8 journal row",bad);
        }
    }
    /**
     * Reading the observed prefix is a bounded diagnostic only. Partial tail
     * is never reported as a committed record. Complete malformed frames
     * raise IOException if the file remains stable. If it changed during
     * inspection, CHANGING takes precedence over a transient validation
     * fault. The source is never created, repaired, shortened or replaced.
     */
    public static Result inspect(File path)throws IOException {
        if(path==null||!path.getName().endsWith(".rlj"))
            throw new IllegalArgumentException("Existing .rlj source required");
        File journal=path.getAbsoluteFile();
        File dir=journal.getParentFile();
        if(dir==null||!dir.isDirectory()||Files.isSymbolicLink(dir.toPath()))
            throw new IOException("Invalid journal directory");
        // Do not let JournalIdentityPrototype.open() create a missing sidecar
        // for an empty source.
        File sidecar=new File(journal.getAbsolutePath()+".identity");
        if(!sidecar.isFile()||Files.isSymbolicLink(sidecar.toPath()))
            throw new IOException("Journal identity required");
        String identity=JournalIdentityPrototype.readExisting(journal).sourceUuid();
        BasicFileAttributes start=attributes(journal);
        long size=start.size();
        if(size>IngressJournalPrototype.MAX_JOURNAL_BYTES)
            throw new IOException("Journal exceeds bounded read quota");
        long count=0,pos=0;
        boolean partial=false;
        IOException fault=null;
        try(FileChannel file=FileChannel.open(journal.toPath(),StandardOpenOption.READ)){
            while(pos<size){
                if(size-pos<IngressJournalPrototype.HEADER_BYTES){
                    partial=true;break;
                }
                try{
                    ByteBuffer header=ByteBuffer.allocate(IngressJournalPrototype.HEADER_BYTES)
                                                .order(ByteOrder.BIG_ENDIAN);
                    fullyRead(file,header,pos);
                    int magic=header.getInt(),bytes=header.getInt();
                    long sequence=header.getLong();
                    int crc=header.getInt();
                    if(magic!=IngressJournalPrototype.FRAME_MAGIC||
                       bytes<=0||bytes>IngressJournalPrototype.MAX_ROW_BYTES||
                       sequence!=count+1)
                        throw new IOException("Invalid journal header or sequence");
                    if(bytes>size-pos-IngressJournalPrototype.HEADER_BYTES){
                        partial=true;break;
                    }
                    byte[] content=new byte[bytes];
                    fullyRead(file,ByteBuffer.wrap(content),
                        pos+IngressJournalPrototype.HEADER_BYTES);
                    CRC32 check=new CRC32();check.update(content);
                    if((int)check.getValue()!=crc)
                        throw new IOException("Invalid journal CRC");
                    validateRow(content);
                    pos+=IngressJournalPrototype.HEADER_BYTES+(long)bytes;
                    count++;
                }catch(IOException problem){
                    fault=problem;break;
                }
            }
        }
        BasicFileAttributes end=attributes(journal);
        if(changed(start,end,size,end.size()))
            return new Result(Status.CHANGING,count,pos,size);
        // Re-check identity but NEVER create or update a sidecar.
        if(!identity.equals(JournalIdentityPrototype.readExisting(journal).sourceUuid()))
            throw new IOException("Journal source identity changed during probe");
        if(fault!=null)throw fault;
        return new Result(partial?Status.INCOMPLETE_TAIL:Status.COMPLETE,count,pos,size);
    }
}
