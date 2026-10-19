package net.neristal.rrbridge.prototype.ingress;
import net.neristal.rrbridge.product.v1.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.neristal.rrbridge.prototype.ingress.*;
/** RLBA1 byte-equivalence evidence; never enables or repairs a restored deployment. */
public final class IsolatedBackupAcceptance {
 private static final long MAX=128L*1024*1024,TOTAL=256L*1024*1024;
 private static final Set<String> REQUIRED=new HashSet<String>(Arrays.asList("data-v1","data-rli2","data-rmi1","data-rmi1-owner","data-ledger","data-legacy-binding","data-legacy-lock","root-deployment.rldb1","input-rotation.manifest"));
 private static String digest(File file)throws Exception {
  DeploymentBinding.safe(file);if(!file.isFile()||file.length()>MAX)throw new IOException("BACKUP_FILE_BOUND");
  MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file.toPath())){byte[] buffer=new byte[32768];int n;long size=0;while((n=in.read(buffer))!=-1){if((size+=n)>MAX)throw new IOException("BACKUP_FILE_BOUND");sha.update(buffer,0,n);}}
  StringBuilder s=new StringBuilder();for(byte b:sha.digest())s.append(String.format("%02x",b&255));return file.length()+":"+s;
 }
 private static Map<String,String> hashes(Map<String,File> files)throws Exception {
  if(files.size()>180||!files.keySet().containsAll(REQUIRED))throw new IOException("BACKUP_ROLES_MISSING");
  Map<String,String> result=new TreeMap<String,String>();long total=0;
  for(Map.Entry<String,File> e:files.entrySet()) {
   if(!e.getKey().matches("[A-Za-z0-9._-]{1,120}"))throw new IOException("BACKUP_ROLE_INVALID");
   for(String suffix:new String[]{"-wal","-shm","-journal"})if(Files.exists(Paths.get(e.getValue()+suffix),LinkOption.NOFOLLOW_LINKS))throw new IOException("BACKUP_NOT_CLOSED");
   if((total+=e.getValue().length())>TOTAL)throw new IOException("BACKUP_TOTAL_BOUND");result.put(e.getKey(),digest(e.getValue()));
  }return result;
 }
 private static String encode(Map<String,String> hashes) {StringBuilder b=new StringBuilder("RLBA1\n");for(Map.Entry<String,String> e:hashes.entrySet())b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');return b.toString();}
 public interface CopyHook {void afterArtifact(String role,int copied)throws Exception;}
 public static final CopyHook NO_COPY_FAULT=new CopyHook(){public void afterArtifact(String role,int copied){}};
 public static final class Evidence {
  public final Map<String,File> sourceFiles;private final Map<String,String> hashes;
  Evidence(Map<String,File> files,Map<String,String> hashes){sourceFiles=Collections.unmodifiableMap(new TreeMap<String,File>(files));this.hashes=Collections.unmodifiableMap(new TreeMap<String,String>(hashes));}
  public void writeNew(File file)throws Exception {
   try(FileChannel channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(encode(hashes).getBytes(StandardCharsets.US_ASCII));while(b.hasRemaining())channel.write(b);channel.force(true);}
  }
  /** New isolated directory only. Copies remain inactive, their original bindings deliberately do not rebind. */
  public void copyToNewDirectory(boolean enable,File target)throws Exception {copyToNewDirectory(enable,target,NO_COPY_FAULT);}
  public void copyToNewDirectory(boolean enable,File target,CopyHook hook)throws Exception {
   if(!enable)throw new IOException("BACKUP_DISABLED");if(!hashes.equals(IsolatedBackupAcceptance.hashes(sourceFiles)))throw new IOException("BACKUP_SOURCE_CHANGED");
   Path p=target.toPath();if(!p.isAbsolute()||!p.equals(p.normalize()))throw new IOException("BACKUP_TARGET_PATH");DeploymentBinding.safe(p.getParent().toFile());
   if(Files.getFileAttributeView(p.getParent(),java.nio.file.attribute.PosixFileAttributeView.class)!=null)Files.createDirectory(p,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
   else Files.createDirectory(p);
   int copied=0;for(Map.Entry<String,File> e:sourceFiles.entrySet()){Files.copy(e.getValue().toPath(),p.resolve(e.getKey()));hook.afterArtifact(e.getKey(),++copied);}
   verifyDirectory(target);
   if(!hashes.equals(IsolatedBackupAcceptance.hashes(sourceFiles)))throw new IOException("BACKUP_SOURCE_CHANGED");
  }
  public void verifyDirectory(File target)throws Exception {
   DeploymentBinding.safe(target);DeploymentBinding.privateAccess(target.toPath());Map<String,File> copies=new TreeMap<String,File>();
   try(DirectoryStream<Path> entries=Files.newDirectoryStream(target.toPath())){for(Path p:entries)copies.put(p.getFileName().toString(),p.toFile());}
   if(!copies.keySet().equals(hashes.keySet())||!hashes.equals(IsolatedBackupAcceptance.hashes(copies)))throw new IOException("BACKUP_CONTENT_MISMATCH");
  }
  public String json(){return "{\"contract_version\":1,\"state\":\"VERIFIED_ISOLATED_COPY\",\"artifact_count\":"+hashes.size()+",\"activation_allowed\":false,\"production_authorized\":false,\"ack_eligible\":false,\"external_single_active_required\":true,\"automatic_repair\":false}";}
 }
 private static void add(Map<String,File> files,String role,File file)throws Exception {DeploymentBinding.safe(file);DeploymentBinding.privateAccess(file.toPath());files.put(role,file);}
 public static Evidence capture(boolean enable,boolean externallyQuiescent,DeploymentBinding.Layout l,File rmi,WatermarkV2Prototype.ReadOpener reader)throws Exception {
  DeploymentBinding.verify(enable,externallyQuiescent,l);Map<String,File> files=new TreeMap<String,File>();
  add(files,"data-v1",l.legacy);add(files,"data-rli2",l.output);add(files,"data-rmi1",rmi);add(files,"data-rmi1-owner",new File(rmi+".migration-owner"));add(files,"data-ledger",l.ledger);
  add(files,"data-legacy-binding",new File(l.legacy+".deployment-binding"));add(files,"data-legacy-lock",new File(l.legacy+".worker.lock"));
  for(String domain:new String[]{"input","root"}) {
   Path directory=(domain.equals("input")?l.session:l.root).toPath();
   try(DirectoryStream<Path> entries=Files.newDirectoryStream(directory)){for(Path p:entries){if(files.size()>=180)throw new IOException("BACKUP_ENTRY_BOUND");add(files,domain+"-"+p.getFileName(),p.toFile());}}
  }
  List<FileChannel> channels=new ArrayList<FileChannel>();List<FileLock> locks=new ArrayList<FileLock>();
  try {
   for(Map.Entry<String,File> e:files.entrySet())if(e.getKey().equals("data-legacy-lock")||e.getKey().endsWith(".lane.lock")) {
    FileChannel c=FileChannel.open(e.getValue().toPath(),StandardOpenOption.READ);channels.add(c);FileLock lock;
    try{lock=c.tryLock(0,Long.MAX_VALUE,true);}catch(OverlappingFileLockException busy){throw new IOException("BACKUP_OWNER_BUSY");}if(lock==null)throw new IOException("BACKUP_OWNER_BUSY");locks.add(lock);
   }
   Map<String,String> before=hashes(files);
   V1LaneMigration.verifyIsolatedCopy(true,true,l.legacy,rmi);
   Rlc2IntegrationSchema.verifyExistingReadOnly(l.output);
   Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,l.session.toPath(),l.ledger,l.device,l.vehicle,reader);
   if(!p.payloadSession.equals(l.payloadSession)||!p.manifestSession.equals(l.manifestSession))throw new IOException("BACKUP_SOURCE_IDENTITY");
   DeploymentBinding.verify(true,true,l);if(!before.equals(hashes(files)))throw new IOException("BACKUP_SOURCE_CHANGED");return new Evidence(files,before);
  }finally{for(int i=locks.size()-1;i>=0;i--)locks.get(i).release();for(int i=channels.size()-1;i>=0;i--)channels.get(i).close();}
 }
 public static Evidence read(File file)throws Exception {
  DeploymentBinding.safe(file);if(file.length()>65536)throw new IOException("BACKUP_EVIDENCE_BOUND");
  List<String> lines=Files.readAllLines(file.toPath(),StandardCharsets.US_ASCII);if(lines.isEmpty()||!lines.get(0).equals("RLBA1"))throw new IOException("BACKUP_EVIDENCE_VERSION");
  Map<String,String> hashes=new TreeMap<String,String>();for(int i=1;i<lines.size();i++){String[] f=lines.get(i).split(" ",-1);if(f.length!=2||!f[0].matches("[A-Za-z0-9._-]{1,120}")||!f[1].matches("[0-9]{1,12}:[0-9a-f]{64}")||hashes.put(f[0],f[1])!=null)throw new IOException("BACKUP_EVIDENCE_FORMAT");}
  if(hashes.size()>180||!hashes.keySet().containsAll(REQUIRED))throw new IOException("BACKUP_ROLES_MISSING");return new Evidence(Collections.<String,File>emptyMap(),hashes);
 }
 public static void main(String[] args) {
  if(args.length!=3||!"--inspect-isolated-copy".equals(args[0])){System.out.println(DeploymentBinding.json("REVIEW_REQUIRED","USAGE"));System.exit(2);return;}
  try{Evidence e=read(new File(args[1]));e.verifyDirectory(new File(args[2]));System.out.println(e.json());}
  catch(Exception e){System.out.println(DeploymentBinding.json("REVIEW_REQUIRED","BACKUP_VERIFICATION_REFUSED"));System.exit(5);}
 }
}
