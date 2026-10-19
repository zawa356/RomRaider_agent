package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** RLDB1 local synthetic deployment. No global fence or production authorization. */
public final class DeploymentBinding {
 public static final String NAME="deployment.rldb1";
 private static final LinkOption[] NOFOLLOW={LinkOption.NOFOLLOW_LINKS};
 public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
 public static final class Layout {
  public final File root,legacy,output,session,manifest,ledger;
  public final String device,vehicle,payloadSession,manifestSession;
  public Layout(File root,File legacy,File output,File session,File manifest,File ledger,String device,String vehicle,String payloadSession,String manifestSession){
   this.root=root;this.legacy=legacy;this.output=output;this.session=session;this.manifest=manifest;this.ledger=ledger;
   this.device=device;this.vehicle=vehicle;this.payloadSession=payloadSession;this.manifestSession=manifestSession;
  }
  File[] files(){return new File[]{root,legacy,output,session,manifest,ledger};}
 }
 private static void require(boolean condition,String code)throws Refusal {if(!condition)throw new Refusal(code);}
 /** Reject aliases/reparse points in EVERY existing component, before canonicalization. */
 public static Path safe(File file)throws IOException {
  Path p=file.toPath();require(p.isAbsolute()&&p.equals(p.normalize()),"PATH_NOT_ABSOLUTE_NORMAL");
  require(!p.toString().startsWith("\\\\"),"NETWORK_PATH_UNSUPPORTED");
  for(Path c=p;c!=null;c=c.getParent()) {
   BasicFileAttributes a=Files.readAttributes(c,BasicFileAttributes.class,NOFOLLOW);
   require(!a.isSymbolicLink()&&!a.isOther(),"PATH_LINK_OR_REPARSE");
  }
  Path real=p.toRealPath();require(real.toString().equals(p.toString()),"PATH_ALIAS");
  String type=Files.getFileStore(p).type().toLowerCase(Locale.ROOT);
  require(type.equals("ntfs")||type.equals("ext4")||type.equals("ext3")||type.equals("xfs")||type.equals("btrfs")||type.equals("tmpfs")||type.equals("overlay"),"FILESYSTEM_UNSUPPORTED");
  return real;
 }
 public static void privateAccess(Path p)throws IOException {
  PosixFileAttributeView posix=Files.getFileAttributeView(p,PosixFileAttributeView.class,NOFOLLOW);
  if(posix!=null) {
   Set<PosixFilePermission> perms=posix.readAttributes().permissions();
   require(!perms.contains(PosixFilePermission.GROUP_WRITE)&&!perms.contains(PosixFilePermission.OTHERS_WRITE),"UNSAFE_ACCESS");
   require(Files.getOwner(p,NOFOLLOW).equals(p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"))),"UNKNOWN_OWNER");
   return;
  }
  AclFileAttributeView acl=Files.getFileAttributeView(p,AclFileAttributeView.class,NOFOLLOW);require(acl!=null,"ACCESS_UNKNOWN");
  UserPrincipal user=p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
  Set<AclEntryPermission> writes=EnumSet.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER);
  for(AclEntry entry:acl.getAcl())if(entry.type()==AclEntryType.ALLOW&&!Collections.disjoint(entry.permissions(),writes)) {
   String name=entry.principal().getName();require(entry.principal().equals(user)||name.equalsIgnoreCase("NT AUTHORITY\\SYSTEM")||name.equalsIgnoreCase("BUILTIN\\Administrators"),"UNSAFE_ACCESS");
  }
  String owner=acl.getOwner().getName();require(acl.getOwner().equals(user)||owner.equalsIgnoreCase("BUILTIN\\Administrators")||owner.equalsIgnoreCase("NT AUTHORITY\\SYSTEM"),"UNKNOWN_OWNER");
 }
 private static String identity(Path p)throws IOException {
  BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,NOFOLLOW);
  return String.valueOf(a.fileKey())+"|"+a.creationTime().toString();
 }
 private static Map<String,String> describe(Layout l)throws Exception {
  Map<String,String> m=new TreeMap<String,String>();m.put("version","RLDB1");m.put("mode","SYNTHETIC_ONLY");
  require(UUID.fromString(l.device).toString().equals(l.device)&&UUID.fromString(l.vehicle).toString().equals(l.vehicle)&&UUID.fromString(l.manifestSession).toString().equals(l.manifestSession),"IDENTITY_INVALID");
  require(l.payloadSession!=null&&!l.payloadSession.isEmpty()&&l.payloadSession.length()<=1024&&l.payloadSession.indexOf('\n')<0&&l.payloadSession.indexOf('\r')<0,"IDENTITY_INVALID");
  m.put("device",l.device);m.put("vehicle",l.vehicle);m.put("payload",l.payloadSession);m.put("manifest_session",l.manifestSession);
  String[] names={"root","legacy","output","session","manifest","ledger"};File[] files=l.files();Path[] paths=new Path[files.length];
  for(int i=0;i<files.length;i++) {
   Path p=paths[i]=safe(files[i]);privateAccess(p);privateAccess(p.getParent());
   require(i==0||i==3?Files.isDirectory(p,NOFOLLOW):Files.isRegularFile(p,NOFOLLOW),"PATH_TYPE");
   m.put(names[i],p.toString());m.put(names[i]+"_identity",identity(p));
  }
  require(paths[4].getParent().equals(paths[3]),"MANIFEST_LOCATION");
  for(int i:new int[]{0,1,2,5})require(!paths[i].startsWith(paths[3])&&!paths[3].startsWith(paths[i]),"INPUT_OUTPUT_OVERLAP");
  for(int i:new int[]{1,2,5})require(!paths[i].startsWith(paths[0])&&!Files.isSameFile(paths[i],paths[4]),"ROOT_DATA_OVERLAP");
  for(int i:new int[]{1,2,5})for(int j:new int[]{1,2,5})if(i<j)require(!Files.isSameFile(paths[i],paths[j]),"DATABASE_ALIAS");
  for(int i:new int[]{1,5})for(String suffix:new String[]{"-wal","-shm","-journal"})require(!Files.exists(Paths.get(paths[i]+suffix),NOFOLLOW),"DATABASE_NOT_CLOSED");
  return m;
 }
 private static byte[] encode(Map<String,String> m) {
  StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:m.entrySet())b.append(e.getKey()).append('=').append(Base64.getEncoder().encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8))).append('\n');return b.toString().getBytes(StandardCharsets.US_ASCII);
 }
 private static Map<String,String> read(Path p)throws Exception {
  safe(p.toFile());privateAccess(p);require(Files.size(p)<=32768,"BINDING_FORMAT");
  Map<String,String> m=new TreeMap<String,String>();String text=new String(Files.readAllBytes(p),StandardCharsets.US_ASCII);
  require(text.endsWith("\n"),"BINDING_FORMAT");
  try {for(String line:text.split("\n")){int at=line.indexOf('=');require(at>0,"BINDING_FORMAT");String key=line.substring(0,at);require(m.put(key,new String(Base64.getDecoder().decode(line.substring(at+1)),StandardCharsets.UTF_8))==null,"BINDING_DUPLICATE");}}catch(IllegalArgumentException bad){throw new Refusal("BINDING_FORMAT");}
  require("RLDB1".equals(m.get("version")),"BINDING_VERSION");require("SYNTHETIC_ONLY".equals(m.get("mode")),"BINDING_MODE");return m;
 }
 private static Path anchor(Layout l){return Paths.get(l.legacy.getPath()+".deployment-binding");}
 private static void create(Path p,byte[] bytes)throws IOException {
  Set<OpenOption> options=new HashSet<OpenOption>(Arrays.<OpenOption>asList(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE));
  FileAttribute<?>[] attrs=Files.getFileAttributeView(p.getParent(),PosixFileAttributeView.class)!=null?new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}:new FileAttribute<?>[0];
  try(FileChannel c=FileChannel.open(p,options,attrs)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}privateAccess(p);
 }
 /** Only fresh isolated fixtures. Partial two-file enrollment remains evidence for manual review. */
 public static void bindSynthetic(boolean enable,boolean externallySingleActive,Layout l)throws Exception {
  require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");Map<String,String> m=describe(l);
  require(!Files.exists(l.root.toPath().resolve(NAME),NOFOLLOW)&&!Files.exists(anchor(l),NOFOLLOW),"BINDING_EXISTS");
  m.put("deployment_uuid",UUID.randomUUID().toString());byte[] bytes=encode(m);
  create(anchor(l),bytes);create(l.root.toPath().resolve(NAME),bytes);verify(true,true,l);
 }
 /** Read-only and advisory. External gate is a caller assertion, never inferred from absent locks. */
 public static void verify(boolean enable,boolean externallySingleActive,Layout l)throws Exception {
  require(enable&&externallySingleActive,"EXTERNAL_GATE_REQUIRED");Map<String,String> expected=describe(l);
  Map<String,String> actual=read(l.root.toPath().resolve(NAME));Map<String,String> peer=read(anchor(l));
  require(actual.equals(peer),"ROOT_DB_BINDING_MISMATCH");String uuid=actual.remove("deployment_uuid");
  try{require(UUID.fromString(uuid).toString().equals(uuid),"BINDING_OWNER");}catch(RuntimeException bad){throw new Refusal("BINDING_OWNER");}
  require(actual.equals(expected),"BINDING_LAYOUT_CHANGED");
 }
 public static String json(String state,String reason) {return "{\"contract_version\":1,\"state\":\""+state+"\",\"reason\":\""+reason+"\",\"synthetic_only\":true,\"production_authorized\":false,\"ack_eligible\":false,\"global_fencing\":false}";}
 public static void main(String[] args) {
  if(args.length!=12||!"--inspect-synthetic".equals(args[0])||!"--external-single-active".equals(args[1])){System.out.println(json("REVIEW_REQUIRED","USAGE"));System.exit(2);return;}
  try {verify(true,true,new Layout(new File(args[2]),new File(args[3]),new File(args[4]),new File(args[5]),new File(args[6]),new File(args[7]),args[8],args[9],args[10],args[11]));System.out.println(json("READY_FOR_SYNTHETIC_TEST","BOUND_LOCAL_DEPLOYMENT"));}
  catch(Exception e){String reason=e instanceof Refusal?e.getMessage():"INSPECTION_UNAVAILABLE";System.out.println(json("REVIEW_REQUIRED",reason));System.exit(5);}
 }
}
