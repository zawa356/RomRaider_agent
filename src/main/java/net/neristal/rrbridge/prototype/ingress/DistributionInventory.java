package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.*;

/**
 * RLDI1 distribution inventory (Phase VIII-1, read-only, synthetic acceptance).
 *
 * <p>Compares one isolated install directory against a pinned inventory record: every regular file must be listed with
 * its exact SHA-256 and role, nothing else may exist (no links, junctions or special files), exactly one approved JAR
 * holds the approved entry, and no JAR in the directory carries another {@code public static void main(String[])}, a
 * known legacy entry class, a Main-Class other than the approved entry, a Class-Path/agent manifest or a nested archive.
 *
 * <p>A MATCHED inventory only says this directory contains no other Java entry. It cannot stop copies elsewhere, another
 * user/administrator, other hosts or a JVM started by hand with its own classpath: global old-binary exclusion stays
 * NOT_PROVEN and the result never authorizes launch, production or ACK. Windows ACLs are not inspected (UNKNOWN).
 */
public final class DistributionInventory {
    public static final String VERSION="RLDI1";
    public static final String[] ROLES={"approved_jar","dependency_jar","launcher","profile","document"};
    /** Known pre-Phase VII entries (by class name) that must never ship next to the approved entry. */
    public static final String[] LEGACY_ENTRIES={
        "net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationCli","net.neristal.rrbridge.prototype.ingress.Rlc2PreviewCli",
        "net.neristal.rrbridge.product.v1.ProductBridgeWorker","net.neristal.rrbridge.product.v1.ProductBridgeOnce",
        "net.neristal.rrbridge.product.v1.V1LaneMigration","net.neristal.rrbridge.prototype.ingress.LegacyCandidateGate",
        "net.neristal.rrbridge.Receiver"};
    static final int MAX_ENTRIES=4096,MAX_DEPTH=16,MAX_JAR_ENTRIES=50000;
    static final long MAX_FILE=64L*1024*1024,MAX_CLASS=4L*1024*1024;
    static final Set<String> LAUNCHER_EXT=new HashSet<String>(Arrays.asList("bat","cmd","ps1","sh","exe","com","vbs","js","jar","war","zip"));

    public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
    private static void require(boolean ok,String code)throws Refusal {if(!ok)throw new Refusal(code);}

    public static final class Report {
        public final String inventory,reason;public final Map<String,Integer> findings;public final List<String> unapprovedMains;
        public final int files;public final String acl;
        Report(String inventory,String reason,Map<String,Integer> findings,List<String> mains,int files,String acl){
            this.inventory=inventory;this.reason=reason;this.findings=Collections.unmodifiableMap(new TreeMap<String,Integer>(findings));
            unapprovedMains=Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(mains)));this.files=files;this.acl=acl;
        }
        public boolean matched(){return "MATCHED".equals(inventory);}
        /** Codes, counts and class names only: no paths, hashes of payload or file names. */
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"inventory_version\":\"").append(VERSION).append("\",\"state\":\"BLOCKED\",\"inventory\":\"")
                .append(inventory).append("\",\"reason\":\"").append(reason).append("\",\"files\":").append(files).append(",\"findings\":{");
            boolean first=true;for(Map.Entry<String,Integer> e:findings.entrySet()){if(!first)b.append(',');first=false;b.append('"').append(e.getKey()).append("\":").append(e.getValue());}
            b.append("},\"unapproved_mains\":[");first=true;for(String m:unapprovedMains){if(!first)b.append(',');first=false;b.append('"').append(m).append('"');}
            return b.append("],\"access_control\":\"").append(acl).append("\",\"global_old_binary_exclusion\":\"NOT_PROVEN\",\"synthetic_only\":true,")
                .append("\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false}").toString();
        }
    }

    /** Builds the RLDI1 record describing a directory exactly as it is (synthetic packaging step; not an approval). */
    public static Map<String,String> describe(File root,String approvedMain,Map<String,String> roles)throws Exception {
        Map<String,String> m=new TreeMap<String,String>();m.put("version",VERSION);m.put("mode","SYNTHETIC_ONLY");m.put("approved_main",approvedMain);
        List<String> names=new ArrayList<String>(roles.keySet());Collections.sort(names);m.put("files",String.valueOf(names.size()));
        for(int i=0;i<names.size();i++){
            String key=String.format(Locale.ROOT,"file.%04d",i);File f=new File(root,names.get(i).replace('/',File.separatorChar));
            m.put(key+".path",names.get(i));m.put(key+".role",roles.get(names.get(i)));m.put(key+".sha256",DeploymentTransition.fileSha(f));
        }
        return m;
    }

    public interface Hook { void at(String stage)throws Exception; }
    public static final Hook NO_HOOK=new Hook(){public void at(String stage){}};

    public static Report inspect(boolean enable,File root,File record){return inspect(enable,root,record,NO_HOOK);}
    public static Report inspect(boolean enable,File root,File record,Hook hook){
        Map<String,Integer> findings=new TreeMap<String,Integer>();List<String> mains=new ArrayList<String>();
        if(!enable)return new Report("NOT_RUN","INVENTORY_DISABLED",findings,mains,0,"UNKNOWN");
        int files=0;String acl="UNKNOWN";
        try{
            require(root!=null&&record!=null&&root.isAbsolute()&&record.isAbsolute(),"INVENTORY_PATH_NOT_ABSOLUTE");
            DeploymentBinding.safe(root);
            require(Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS),"DISTRIBUTION_ROOT_NOT_DIRECTORY");
            require(!record.toPath().toAbsolutePath().normalize().startsWith(root.toPath().toAbsolutePath().normalize()),"RECORD_INSIDE_DISTRIBUTION");
            String recordSha=DeploymentTransition.fileSha(record);
            Map<String,String> rec=DeploymentBinding.readRecord(record.toPath());
            require(VERSION.equals(rec.get("version")),"INVENTORY_VERSION");require("SYNTHETIC_ONLY".equals(rec.get("mode")),"INVENTORY_MODE");
            String approved=rec.get("approved_main");
            require(OfflineCandidateEntry.MAIN.equals(approved),"APPROVED_MAIN_NOT_THE_OFFLINE_ENTRY");
            Map<String,String[]> listed=listed(rec);
            hook.at("RECORD_READ");

            // Walk without following anything; a link, junction or special file anywhere is a finding.
            final Path base=root.toPath();
            Walk walk=new Walk(base);Files.walkFileTree(base,EnumSet.noneOf(FileVisitOption.class),MAX_DEPTH+1,walk);
            final Map<String,BasicFileAttributes> tree=walk.tree;for(int i=0;i<walk.links;i++)add(findings,"LINK_OR_SPECIAL_IN_DISTRIBUTION");
            files=tree.size();
            for(String name:listed.keySet())if(!tree.containsKey(name))add(findings,"LISTED_FILE_MISSING");
            int approvedJars=0;boolean approvedMainFound=false;Map<String,String> classOwner=new HashMap<String,String>();
            // Phase IX-4 (R9-04): per listed file, physical identity before hashing and the hash itself are pinned and re-checked at the end.
            Map<String,String> pinnedSha=new HashMap<String,String>(),pinnedId=new HashMap<String,String>();
            for(Map.Entry<String,BasicFileAttributes> e:tree.entrySet()){
                String name=e.getKey();File f=base.resolve(name.replace('/',File.separatorChar)).toFile();String[] want=listed.get(name);
                String ext=name.lastIndexOf('.')>name.lastIndexOf('/')?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
                if(want==null){add(findings,LAUNCHER_EXT.contains(ext)?"UNLISTED_EXECUTABLE_OR_ARCHIVE":"UNLISTED_FILE");}
                else{
                    if(e.getValue().size()>MAX_FILE){add(findings,"FILE_BOUND");continue;}
                    pinnedId.put(name,identityOf(f));
                    String sha=DeploymentTransition.fileSha(f);pinnedSha.put(name,sha);
                    if(!want[0].equals(sha))add(findings,"FILE_HASH_MISMATCH");
                    boolean jarRole=want[1].endsWith("_jar");
                    if(jarRole!=ext.equals("jar")||("launcher".equals(want[1])!=(LAUNCHER_EXT.contains(ext)&&!ext.equals("jar"))))add(findings,"ROLE_DOES_NOT_MATCH_FILE");
                    if("approved_jar".equals(want[1]))approvedJars++;
                }
                if(ext.equals("jar")){
                    boolean isApproved=want!=null&&"approved_jar".equals(want[1]);
                    hook.at("HASHED:"+name);
                    approvedMainFound|=scanJar(f,approved,isApproved,findings,mains,classOwner);
                    // The bytes scanned for entries must be the bytes that were hashed (no swap between hash and scan).
                    if(pinnedSha.containsKey(name))require(pinnedSha.get(name).equals(DeploymentTransition.fileSha(f)),"INPUT_CHANGED_DURING_INVENTORY");
                }
            }
            if(approvedJars!=1)add(findings,"APPROVED_JAR_COUNT");
            if(!approvedMainFound)add(findings,"APPROVED_MAIN_MISSING");
            acl=accessControl(base,tree.keySet(),findings);
            hook.at("SCANNED");
            // Nothing may change while inventoried: record bytes and every file's size/mtime are re-read.
            require(recordSha.equals(DeploymentTransition.fileSha(record)),"INPUT_CHANGED_DURING_INVENTORY");
            for(Map.Entry<String,BasicFileAttributes> e:tree.entrySet()){
                BasicFileAttributes now;try{now=Files.readAttributes(base.resolve(e.getKey().replace('/',File.separatorChar)),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(IOException gone){throw new Refusal("INPUT_CHANGED_DURING_INVENTORY");}
                require(now.size()==e.getValue().size()&&now.lastModifiedTime().equals(e.getValue().lastModifiedTime())&&now.isRegularFile(),"INPUT_CHANGED_DURING_INVENTORY");
            }
            Walk again=new Walk(base);Files.walkFileTree(base,EnumSet.noneOf(FileVisitOption.class),MAX_DEPTH+1,again);
            require(again.tree.keySet().equals(tree.keySet())&&again.links==walk.links,"INPUT_CHANGED_DURING_INVENTORY");
            // Same size and mtime is not enough (R9-04): every listed file is re-hashed and its physical identity re-read.
            for(Map.Entry<String,String> e:pinnedSha.entrySet()){
                File f=base.resolve(e.getKey().replace('/',File.separatorChar)).toFile();
                String id;try{id=identityOf(f);}catch(IOException gone){throw new Refusal("INPUT_CHANGED_DURING_INVENTORY");}
                require(id.equals(pinnedId.get(e.getKey()))&&e.getValue().equals(DeploymentTransition.fileSha(f)),"INPUT_CHANGED_DURING_INVENTORY");
            }
            if(!findings.isEmpty())return new Report("MISMATCH",findings.keySet().iterator().next(),findings,mains,files,acl);
            return new Report("MATCHED","INVENTORY_MATCHED_NOT_AUTHORIZATION",findings,mains,files,acl);
        }catch(Exception failure){
            String code=failure instanceof Refusal||failure instanceof DeploymentBinding.Refusal||failure instanceof DeploymentTransition.Refusal?failure.getMessage():"INVENTORY_UNAVAILABLE";
            if("INVENTORY_UNAVAILABLE".equals(code))findings.put("inventory_cause:"+OfflineCandidatePreflight.cause(failure),1);
            return new Report("UNAVAILABLE",code,findings,mains,files,acl);
        }
    }
    /** Collects regular files by relative name and counts anything else (links, junctions, devices) without following it. */
    private static final class Walk extends SimpleFileVisitor<Path> {
        final Path base;final Map<String,BasicFileAttributes> tree=new TreeMap<String,BasicFileAttributes>();int links;
        Walk(Path base){this.base=base;}
        public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{
            if(!d.equals(base)&&(a.isSymbolicLink()||a.isOther())){links++;return FileVisitResult.SKIP_SUBTREE;}
            if(base.relativize(d).getNameCount()>MAX_DEPTH)throw new Refusal("DISTRIBUTION_DEPTH_BOUND");return FileVisitResult.CONTINUE;
        }
        public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{
            if(tree.size()+links>=MAX_ENTRIES)throw new Refusal("DISTRIBUTION_ENTRY_BOUND");
            if(a.isSymbolicLink()||a.isOther()||!a.isRegularFile())links++;else tree.put(rel(base,f),a);
            return FileVisitResult.CONTINUE;
        }
        public FileVisitResult visitFileFailed(Path f,IOException e)throws IOException{throw new Refusal("DISTRIBUTION_UNREADABLE");}
    }
    private static Map<String,String[]> listed(Map<String,String> rec)throws Refusal {
        int n;try{n=Integer.parseInt(rec.get("files"));}catch(Exception bad){throw new Refusal("INVENTORY_FORMAT");}
        require(n>=1&&n<=MAX_ENTRIES,"INVENTORY_FORMAT");
        require(rec.size()==4+3*n,"INVENTORY_FORMAT");
        Map<String,String[]> out=new TreeMap<String,String[]>();Set<String> roles=new HashSet<String>(Arrays.asList(ROLES));
        for(int i=0;i<n;i++){
            String key=String.format(Locale.ROOT,"file.%04d",i);String p=rec.get(key+".path"),sha=rec.get(key+".sha256"),role=rec.get(key+".role");
            require(p!=null&&sha!=null&&role!=null,"INVENTORY_FORMAT");
            require(p.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")&&!Arrays.asList(p.split("/")).contains("..")&&!Arrays.asList(p.split("/")).contains("."),"INVENTORY_PATH_INVALID");
            require(sha.matches("[0-9a-f]{64}"),"INVENTORY_FORMAT");require(roles.contains(role),"INVENTORY_ROLE_INVALID");
            require(out.put(p,new String[]{sha,role})==null,"INVENTORY_DUPLICATE_PATH");
        }
        return out;
    }
    /** Physical identity (POSIX dev/inode or NTFS volume + file ID) of a listed file; unavailable is a refusal, never skipped. */
    private static String identityOf(File f)throws IOException {
        try{return DeploymentBinding.fileIdentity(f);}catch(DeploymentBinding.Refusal r){throw new Refusal("FILE_IDENTITY_"+r.getMessage());}
    }
    private static String rel(Path base,Path f){StringBuilder b=new StringBuilder();for(Path part:base.relativize(f)){if(b.length()>0)b.append('/');b.append(part.toString());}return b.toString();}
    private static void add(Map<String,Integer> m,String code){Integer n=m.get(code);m.put(code,n==null?1:n+1);}

    /** Returns true iff this JAR is the approved one and declares the approved main. */
    private static boolean scanJar(File f,String approved,boolean isApproved,Map<String,Integer> findings,List<String> mains,Map<String,String> owner)throws Exception {
        boolean found=false;Set<String> legacy=new HashSet<String>(Arrays.asList(LEGACY_ENTRIES));
        try(JarFile jar=new JarFile(f,false)){
            Manifest mf=jar.getManifest();
            if(mf!=null){
                for(Object k:mf.getMainAttributes().keySet()){String key=k.toString();
                    if(key.equalsIgnoreCase("Class-Path")||key.equalsIgnoreCase("Launcher-Agent-Class")||key.equalsIgnoreCase("Boot-Class-Path")||key.equalsIgnoreCase("Premain-Class")||key.equalsIgnoreCase("Agent-Class"))add(findings,"JAR_MANIFEST_CLASSPATH_OR_AGENT");}
                String main=mf.getMainAttributes().getValue("Main-Class");
                if(main!=null&&!(isApproved&&approved.equals(main.trim())))add(findings,"UNAPPROVED_MANIFEST_MAIN");
            }
            int n=0;Set<String> local=new HashSet<String>();
            for(Enumeration<JarEntry> en=jar.entries();en.hasMoreElements();){
                JarEntry e=en.nextElement();String name=e.getName();
                if(++n>MAX_JAR_ENTRIES)throw new Refusal("JAR_ENTRY_BOUND");
                if(!local.add(name)){add(findings,"JAR_DUPLICATE_ENTRY");continue;}
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith(".jar")||lower.endsWith(".zip")||lower.endsWith(".war")||lower.endsWith(".ear"))add(findings,"NESTED_ARCHIVE");
                if(!lower.endsWith(".class")||e.isDirectory())continue;
                if(name.startsWith("META-INF/")){add(findings,"VERSIONED_OR_META_CLASS");continue;}
                String cls=name.substring(0,name.length()-6).replace('/','.');
                if(!cls.equals("module-info")){String prior=owner.put(cls,f.getName());if(prior!=null)add(findings,"DUPLICATE_CLASS");}
                if(legacy.contains(cls))add(findings,"LEGACY_ENTRY_CLASS_PRESENT");
                if(e.getSize()>MAX_CLASS)throw new Refusal("CLASS_BOUND");
                byte[] bytes;try(InputStream in=jar.getInputStream(e)){bytes=readBounded(in,MAX_CLASS);}
                if(declaresMain(bytes)){
                    if(isApproved&&cls.equals(approved))found=true;
                    else{add(findings,"UNAPPROVED_MAIN_METHOD");mains.add(cls);}
                }
            }
        }catch(java.util.zip.ZipException bad){add(findings,"JAR_UNREADABLE");}
        return found;
    }
    private static byte[] readBounded(InputStream in,long bound)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>bound)throw new Refusal("CLASS_BOUND");out.write(b,0,n);}return out.toByteArray();
    }

    /** Minimal class-file parser: does the class declare {@code public static void main(String[])}? Malformed → treated as main (fail closed). */
    static boolean declaresMain(byte[] c){
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(c));
            if(in.readInt()!=0xCAFEBABE)return true;in.readUnsignedShort();in.readUnsignedShort();
            int cp=in.readUnsignedShort();String[] utf=new String[cp];
            for(int i=1;i<cp;i++){
                int tag=in.readUnsignedByte();
                switch(tag){
                    case 1:utf[i]=in.readUTF();break;
                    case 3:case 4:in.readInt();break;
                    case 5:case 6:in.readLong();i++;break;
                    case 7:case 8:case 16:case 19:case 20:in.readUnsignedShort();break;
                    case 9:case 10:case 11:case 12:case 17:case 18:in.readInt();break;
                    case 15:in.readUnsignedByte();in.readUnsignedShort();break;
                    default:return true;
                }
            }
            in.readUnsignedShort();in.readUnsignedShort();in.readUnsignedShort();
            int ifaces=in.readUnsignedShort();for(int i=0;i<ifaces;i++)in.readUnsignedShort();
            int fields=in.readUnsignedShort();for(int i=0;i<fields;i++){in.readUnsignedShort();in.readUnsignedShort();in.readUnsignedShort();skipAttributes(in);}
            int methods=in.readUnsignedShort();
            for(int i=0;i<methods;i++){
                int access=in.readUnsignedShort();int name=in.readUnsignedShort(),desc=in.readUnsignedShort();
                if((access&0x0009)==0x0009&&"main".equals(at(utf,name))&&"([Ljava/lang/String;)V".equals(at(utf,desc)))return true;
                skipAttributes(in);
            }
            return false;
        }catch(Exception malformed){return true;}
    }
    private static String at(String[] utf,int i){return i>0&&i<utf.length?utf[i]:null;}
    private static void skipAttributes(DataInputStream in)throws IOException {
        int n=in.readUnsignedShort();for(int i=0;i<n;i++){in.readUnsignedShort();long len=in.readInt()&0xffffffffL;if(in.skip(len)!=len)throw new EOFException();}
    }

    /** POSIX: nothing in the distribution may be writable by group/other or owned by another user. Windows ACLs: UNKNOWN. */
    private static String accessControl(Path base,Set<String> names,Map<String,Integer> findings)throws IOException {
        if(Files.getFileAttributeView(base,PosixFileAttributeView.class)==null)return "UNKNOWN_WINDOWS_ACL_NOT_INSPECTED";
        UserPrincipal me=Files.getOwner(base,LinkOption.NOFOLLOW_LINKS);boolean ok=true;
        List<Path> all=new ArrayList<Path>();all.add(base);for(String n:names)all.add(base.resolve(n));
        for(Path p:all){
            for(Path q=p;q!=null&&q.startsWith(base);q=q.getParent()){
                Set<PosixFilePermission> perms=Files.getPosixFilePermissions(q,LinkOption.NOFOLLOW_LINKS);
                if(perms.contains(PosixFilePermission.GROUP_WRITE)||perms.contains(PosixFilePermission.OTHERS_WRITE)){add(findings,"WRITABLE_BY_OTHERS");ok=false;break;}
                if(!me.equals(Files.getOwner(q,LinkOption.NOFOLLOW_LINKS))){add(findings,"OWNER_DIFFERS");ok=false;break;}
                if(q.equals(base))break;
            }
        }
        return ok?"POSIX_OWNER_ONLY_WRITE":"POSIX_MISMATCH";
    }
    private DistributionInventory(){}
}
