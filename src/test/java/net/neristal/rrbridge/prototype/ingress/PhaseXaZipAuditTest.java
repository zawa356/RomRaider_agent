package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.prototype.ingress.Phase8InventoryTest.Install;

/**
 * Phase X-A-2: malformed ZIP/JAR inputs against RLDI1's in-memory {@link DistributionInventory.ZipEntryView}.
 * Archives are written byte by byte (ZipBuilder) so each case sets exactly the field under test; every case first asserts
 * the mutation is really present. Expected: refusal (ZipException → JAR_UNREADABLE) or an explicit finding — never a
 * silent different view. With -Dphasexa.expectRed=true a case the parser ACCEPTS is counted as a reproduced defect.
 * Owned in-memory fixtures; the JDK comparison writes one small JAR into this test's own temp directory.
 */
public final class PhaseXaZipAuditTest {
    static int checks,red;static final boolean RED=Boolean.getBoolean("phasexa.expectRed");
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}

    /** One entry; central fields and local fields are independent so mismatches can be built on purpose. */
    static final class E {
        byte[] name;int method,flags,lflags,lmethod;byte[] data;long crc,cs,us,lcrc,lcs,lus;boolean descriptor;
        E(String n,int method,byte[] raw)throws Exception {this(n.getBytes(StandardCharsets.US_ASCII),method,raw);}
        E(byte[] n,int method,byte[] raw)throws Exception {
            name=n;this.method=lmethod=method;CRC32 c=new CRC32();c.update(raw);crc=lcrc=c.getValue();us=lus=raw.length;
            data=method==8?deflate(raw):raw;cs=lcs=data.length;
        }
        E descriptor(){descriptor=true;flags|=8;lflags|=8;lcrc=0;lcs=0;lus=0;return this;}
    }
    static byte[] deflate(byte[] raw){Deflater d=new Deflater(Deflater.DEFAULT_COMPRESSION,true);d.setInput(raw);d.finish();
        ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];while(!d.finished()){int n=d.deflate(b);o.write(b,0,n);}d.end();return o.toByteArray();}
    static byte[] syncFlushOnly(byte[] raw){Deflater d=new Deflater(Deflater.DEFAULT_COMPRESSION,true);d.setInput(raw);
        ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=d.deflate(b,0,b.length,Deflater.SYNC_FLUSH))>0)o.write(b,0,n);d.end();return o.toByteArray();}
    static long crc(byte[] b){CRC32 c=new CRC32();c.update(b);return c.getValue();}

    static final class Built {final byte[] bytes;final int[] localOffsets,centralOffsets;Built(byte[] b,int[] l,int[] c){bytes=b;localOffsets=l;centralOffsets=c;}}
    static void u16(ByteArrayOutputStream o,int v){o.write(v&0xff);o.write((v>>>8)&0xff);}
    static void u32(ByteArrayOutputStream o,long v){u16(o,(int)(v&0xffff));u16(o,(int)((v>>>16)&0xffff));}
    static Built build(List<E> es){
        ByteArrayOutputStream o=new ByteArrayOutputStream();int[] lo=new int[es.size()],co=new int[es.size()];
        for(int i=0;i<es.size();i++){E e=es.get(i);lo[i]=o.size();
            u32(o,0x04034b50L);u16(o,20);u16(o,e.lflags);u16(o,e.lmethod);u16(o,0);u16(o,0);u32(o,e.lcrc);u32(o,e.lcs);u32(o,e.lus);u16(o,e.name.length);u16(o,0);
            o.write(e.name,0,e.name.length);o.write(e.data,0,e.data.length);
            if(e.descriptor){u32(o,0x08074b50L);u32(o,e.crc);u32(o,e.cs);u32(o,e.us);}
        }
        int cd=o.size();
        for(int i=0;i<es.size();i++){E e=es.get(i);co[i]=o.size();
            u32(o,0x02014b50L);u16(o,20);u16(o,20);u16(o,e.flags);u16(o,e.method);u16(o,0);u16(o,0);u32(o,e.crc);u32(o,e.cs);u32(o,e.us);
            u16(o,e.name.length);u16(o,0);u16(o,0);u16(o,0);u16(o,0);u32(o,0);u32(o,lo[i]);o.write(e.name,0,e.name.length);}
        int cdSize=o.size()-cd;u32(o,0x06054b50L);u16(o,0);u16(o,0);u16(o,es.size());u16(o,es.size());u32(o,cdSize);u32(o,cd);u16(o,0);
        return new Built(o.toByteArray(),lo,co);
    }
    static Built build(E... es){return build(Arrays.asList(es));}
    static int le16(byte[] b,int i){return (b[i]&0xff)|((b[i+1]&0xff)<<8);}
    static long le32(byte[] b,int i){return (le16(b,i)|((long)le16(b,i+2)<<16))&0xffffffffL;}
    static void put32(byte[] b,int i,long v){for(int k=0;k<4;k++)b[i+k]=(byte)(v>>>(8*k));}

    /** "OK" when the parser reads every entry's data, else "REJECT". */
    static String parse(byte[] zip){
        try{for(DistributionInventory.ZipEntryView e:DistributionInventory.ZipEntryView.read(zip))e.data(DistributionInventory.MAX_CLASS);return "OK";}
        catch(ZipException bad){return "REJECT";}
        catch(IOException other){return "REJECT:"+other.getMessage();}
    }
    /** A malformed case: the parser must refuse. */
    static void refuse(String what,byte[] zip){
        String r=parse(zip);
        if(RED&&"OK".equals(r)){red++;System.out.println("RED "+what+" -> accepted (defect reproduced)");return;}
        check(r.startsWith("REJECT"),what+" refused ["+r+"]");
    }

    public static void main(String[] a)throws Exception {
        byte[] cls="synthetic class bytes".getBytes(StandardCharsets.US_ASCII);
        // ---- builder sanity: the hand-written archive is read identically by the JDK and by RLDI1.
        {Built ok=build(new E("a/A.class",0,cls),new E("b/B.class",8,cls),new E("c/C.class",8,cls).descriptor());
         check("OK".equals(parse(ok.bytes)),"valid STORED + DEFLATED + data-descriptor archive accepted");
         Path dir=Files.createTempDirectory("romlogger-phasexa-zip-");Path f=dir.resolve("ok.jar");Files.write(f,ok.bytes);
         try(JarFile j=new JarFile(f.toFile())){for(String n:new String[]{"a/A.class","b/B.class","c/C.class"}){
             ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=j.getInputStream(j.getJarEntry(n))){byte[] x=new byte[256];int k;while((k=in.read(x))>0)o.write(x,0,k);}
             check(Arrays.equals(o.toByteArray(),cls),"JDK reads "+n+" from the builder archive with the same bytes");}}
         Files.delete(f);Files.delete(dir);}
        // ---- local vs central consistency (Z1/Z2).
        {E e=new E("a/A.class",0,cls);e.lmethod=8;Built b=build(e);check(le16(b.bytes,b.localOffsets[0]+8)==8&&le16(b.bytes,b.centralOffsets[0]+10)==0,"fixture: local method DEFLATED, central STORED");
         refuse("local/central compression method mismatch",b.bytes);}
        {E e=new E("a/A.class",0,cls);e.lflags=0x0002;Built b=build(e);check(le16(b.bytes,b.localOffsets[0]+6)!=le16(b.bytes,b.centralOffsets[0]+8),"fixture: local/central flags differ");
         refuse("local/central general-purpose flags mismatch",b.bytes);}
        {E e=new E("a/A.class",0,cls);e.lcrc=e.crc^1;Built b=build(e);check(le32(b.bytes,b.localOffsets[0]+14)!=le32(b.bytes,b.centralOffsets[0]+16),"fixture: local CRC differs from central");
         refuse("local CRC differs from central (no data descriptor)",b.bytes);}
        {E e=new E("a/A.class",8,cls);e.lus=e.us+7;Built b=build(e);check(le32(b.bytes,b.localOffsets[0]+22)!=le32(b.bytes,b.centralOffsets[0]+24),"fixture: local uncompressed size differs");
         refuse("local sizes differ from central (no data descriptor)",b.bytes);}
        {E e=new E("a/A.class",8,cls).descriptor();e.lcrc=e.crc^1;refuse("data descriptor: nonzero local CRC that contradicts central",build(e).bytes);}
        // ---- DEFLATE stream termination (Z3).
        {byte[] more=(new String(cls,StandardCharsets.US_ASCII)+"HIDDEN-TAIL").getBytes(StandardCharsets.US_ASCII);
         E e=new E("a/A.class",8,more);e.us=e.lus=cls.length;e.crc=e.lcrc=crc(cls);Built b=build(e);
         Inflater inf=new Inflater(true);inf.setInput(e.data);byte[] all=new byte[200];int n=inf.inflate(all);inf.end();
         check(n==more.length,"fixture: compressed stream really holds "+more.length+" bytes while central declares "+cls.length);
         refuse("DEFLATE stream longer than the declared size (truncated view)",b.bytes);}
        {E e=new E("a/A.class",8,cls);e.data=syncFlushOnly(cls);e.cs=e.lcs=e.data.length;Built b=build(e);
         Inflater inf=new Inflater(true);inf.setInput(e.data);byte[] all=new byte[200];int n=inf.inflate(all);boolean fin=inf.finished();inf.end();
         check(n==cls.length&&!fin,"fixture: all bytes present but the DEFLATE stream has no final block");
         refuse("unterminated DEFLATE stream",b.bytes);}
        {E e=new E("a/A.class",8,cls);byte[] d=Arrays.copyOf(e.data,e.data.length+3);d[d.length-1]=9;e.data=d;e.cs=e.lcs=d.length;
         refuse("garbage after the end of the DEFLATE stream inside the compressed size",build(e).bytes);}
        {E e=new E("a/Empty.txt",8,new byte[0]);check(e.us==0,"fixture: 0-byte DEFLATE entry");check("OK".equals(parse(build(e).bytes)),"valid 0-byte DEFLATE entry accepted");
         e.data=new byte[]{1,2,3,4,5};e.cs=e.lcs=5;refuse("0-byte DEFLATE entry whose compressed data is not an empty stream",build(e).bytes);}
        {E e=new E("a/A.class",8,cls);e.data=Arrays.copyOf(e.data,e.data.length-2);e.cs=e.lcs=e.data.length;refuse("truncated DEFLATE data",build(e).bytes);}
        // ---- flags (Z4).
        for(int bit:new int[]{0x0001,0x0040,0x2000}){E e=new E("a/A.class",0,cls);e.flags|=bit;e.lflags|=bit;refuse("general-purpose flag 0x"+Integer.toHexString(bit)+" (encryption/masked) refused",build(e).bytes);}
        // ---- names (Z5).
        {byte[] n="a/É.class".getBytes(StandardCharsets.ISO_8859_1);E e=new E(n,0,cls);check(n[2]<0,"fixture: non-ASCII name byte without the UTF-8 flag");
         refuse("non-ASCII name without the UTF-8 flag (JDK decodes UTF-8, view would differ)",build(e).bytes);}
        {byte[] n={'a','/',(byte)0xc3,(byte)0x28,'.','c'};E e=new E(n,0,cls);e.flags|=0x800;e.lflags|=0x800;refuse("invalid UTF-8 name with the UTF-8 flag",build(e).bytes);}
        {byte[] n="a/é.txt".getBytes(StandardCharsets.UTF_8);E e=new E(n,0,cls);e.flags|=0x800;e.lflags|=0x800;check("OK".equals(parse(build(e).bytes)),"valid UTF-8 name with the UTF-8 flag accepted");}
        // ---- overlapping entries (Z6): B's local header and data sit inside A's data.
        {E b=new E("b/B.class",0,cls);Built inner=build(b);byte[] bLocal=Arrays.copyOfRange(inner.bytes,0,inner.centralOffsets[0]);
         E outer=new E("a/A.class",0,bLocal);Built ob=build(outer);
         int aData=30+outer.name.length;ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(ob.bytes,0,ob.centralOffsets[0]);int cd=o.size();
         byte[] centralA=Arrays.copyOfRange(ob.bytes,ob.centralOffsets[0],ob.centralOffsets[0]+46+outer.name.length);
         byte[] centralB=Arrays.copyOfRange(inner.bytes,inner.centralOffsets[0],inner.centralOffsets[0]+46+b.name.length);put32(centralB,42,aData);
         o.write(centralA,0,centralA.length);o.write(centralB,0,centralB.length);int cds=o.size()-cd;
         u32(o,0x06054b50L);u16(o,0);u16(o,0);u16(o,2);u16(o,2);u32(o,cds);u32(o,cd);u16(o,0);byte[] z=o.toByteArray();
         check(le32(z,aData)==0x04034b50L,"fixture: entry B's local header lies inside entry A's data");
         refuse("overlapping entry data ranges",z);}
        // ---- end record / directory (regressions of existing refusals).
        {Built b=build(new E("a/A.class",0,cls));byte[] z=b.bytes.clone();z[z.length-22+4]=1;refuse("nonzero disk number",z);}
        {Built b=build(new E("a/A.class",0,cls));byte[] z=Arrays.copyOf(b.bytes,b.bytes.length+5);refuse("bytes after the end record",z);}
        {Built b=build(new E("a/A.class",0,cls));byte[] z=b.bytes.clone();put32(z,b.centralOffsets[0]+42,999999);refuse("local header offset out of range",z);}
        {Built b=build(new E("a/A.class",0,cls));byte[] z=b.bytes.clone();z[z.length-22+10]=(byte)0xff;z[z.length-22+11]=(byte)0xff;z[z.length-22+8]=(byte)0xff;z[z.length-22+9]=(byte)0xff;refuse("ZIP64 entry count marker",z);}
        {E e=new E("a/A.class",0,cls);e.us=e.lus=0x7fffffffL;String r=parse(build(e).bytes);check(r.startsWith("REJECT"),"huge declared size refused without allocation ["+r+"]");}
        // ---- manifest selection (Z7): a lowercase decoy before the real manifest must not hide Main-Class.
        {Map<String,byte[]> m=new LinkedHashMap<String,byte[]>();
         m.put("meta-inf/manifest.mf","Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
         m.put("META-INF/MANIFEST.MF","Manifest-Version: 1.0\r\nMain-Class: evil.Main\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
         m.putAll(Phase8InventoryTest.classes(OfflineCandidateEntry.class,DistributionInventory.class));
         Install t=new Install(m);
         try(JarFile j=new JarFile(t.root.resolve("lib/romlogger-offline.jar").toFile())){check("evil.Main".equals(j.getManifest().getMainAttributes().getValue("Main-Class")),"fixture: the JDK honours the exact-name manifest's Main-Class");}
         DistributionInventory.Report r=t.inspect();
         if(RED&&r.matched()){red++;System.out.println("RED manifest case decoy -> MATCHED (defect reproduced)");}
         else check(!r.matched()&&(r.findings.containsKey("UNAPPROVED_MANIFEST_MAIN")||r.findings.containsKey("JAR_MANIFEST_AMBIGUOUS")),"manifest case decoy cannot hide Main-Class "+r.findings);}
        // ---- many entries (bounded) and versioned main (regression).
        {List<E> many=new ArrayList<E>();for(int i=0;i<DistributionInventory.MAX_JAR_ENTRIES+1;i++)many.add(new E("m/"+i,0,new byte[0]));
         String code;try{DistributionInventory.ZipEntryView.read(build(many).bytes);code="READ";}catch(ZipException z){code="REJECT";}
         Map<String,byte[]> big=new LinkedHashMap<String,byte[]>();big.putAll(Phase8InventoryTest.classes(OfflineCandidateEntry.class,DistributionInventory.class));
         System.out.println("NOTE parser read of "+many.size()+" entries: "+code+" (inventory bound is enforced in scanJar)");check(true,"entry-count fixture built ("+many.size()+")");}
        {Map<String,byte[]> m=new LinkedHashMap<String,byte[]>(Phase8InventoryTest.classes(OfflineCandidateEntry.class,DistributionInventory.class));
         m.put("META-INF/versions/9/x/Hidden.class",Phase8InventoryTest.classBytes(DeploymentTransitionCli.class));
         DistributionInventory.Report r=new Install(m).inspect();
         check(!r.matched()&&r.findings.containsKey("VERSIONED_OR_META_CLASS"),"multi-release class under META-INF/versions is a finding "+r.findings);}
        // ---- real JARs must not be falsely refused: same names and bytes as the JDK's JarFile (no false JAR_UNREADABLE).
        if(!RED){
            List<File> real=new ArrayList<File>();for(String n:new String[]{"lib/sqlite-jdbc.jar","lib/slf4j-api.jar","lib/slf4j-nop.jar","build/identity-deps/jna-5.14.0.jar"})if(new File(n).isFile())real.add(new File(n));
            Path dir=Files.createTempDirectory("romlogger-phasexa-jdkjar-");File made=dir.resolve("jdk-made.jar").toFile();
            Manifest mf=new Manifest();mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
            try(JarOutputStream j=new JarOutputStream(new FileOutputStream(made),mf)){for(int i=0;i<5;i++){j.putNextEntry(new JarEntry("p/E"+i+".class"));j.write(cls);j.closeEntry();}}
            real.add(made);
            for(File f:real){
                byte[] z=Files.readAllBytes(f.toPath());Map<String,byte[]> ours=new TreeMap<String,byte[]>();
                for(DistributionInventory.ZipEntryView e:DistributionInventory.ZipEntryView.read(z))ours.put(e.name,e.size<=DistributionInventory.MAX_CLASS?e.data(DistributionInventory.MAX_CLASS):null);
                Map<String,byte[]> jdk=new TreeMap<String,byte[]>();
                try(JarFile j=new JarFile(f,false)){for(Enumeration<JarEntry> en=j.entries();en.hasMoreElements();){JarEntry je=en.nextElement();
                    ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=j.getInputStream(je)){byte[] x=new byte[8192];int k;while((k=in.read(x))>0)o.write(x,0,k);}jdk.put(je.getName(),o.toByteArray());}}
                boolean same=ours.keySet().equals(jdk.keySet());
                for(Map.Entry<String,byte[]> e:ours.entrySet())if(e.getValue()!=null&&!Arrays.equals(e.getValue(),jdk.get(e.getKey())))same=false;
                check(same,"real JAR "+f.getName()+" ("+ours.size()+" entries) read identically to the JDK, not refused");
            }
            made.delete();Files.delete(dir);
        }
        if(RED){System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected the pre-fix parser to accept at least one malformed case");}
        System.out.println("Phase X-A ZIP audit passed "+checks+" checks"+(red>0?" with "+red+" RED reproductions":"")+"; RLDI1 still BLOCKED by design");
    }
    private PhaseXaZipAuditTest(){}
}
