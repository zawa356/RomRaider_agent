package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
public final class DeploymentBindingTest {
 static int checks;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static void reject(Action a,String s)throws Exception {try{a.run();}catch(Exception e){check(true,s+" ["+(e instanceof DeploymentBinding.Refusal?e.getMessage():e.getClass().getSimpleName())+"]");return;}throw new AssertionError(s);}
 // Fixture-only: never changes existing operational permissions.
 public static Path privateDir(Path p)throws Exception {
  if(Files.getFileAttributeView(p,PosixFileAttributeView.class)!=null)Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));
  else {
   AclFileAttributeView acl=Files.getFileAttributeView(p,AclFileAttributeView.class);
   UserPrincipal user=p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
   acl.setAcl(Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
  }return p.toRealPath();
 }
 public static void privateFile(Path p)throws Exception {
  if(Files.getFileAttributeView(p,PosixFileAttributeView.class)!=null)Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------"));
 }
 static DeploymentBinding.Layout fixture()throws Exception {
  Path home=privateDir(Files.createTempDirectory("romlogger-rldb1-"));
  File root=privateDir(Files.createDirectory(home.resolve("root"))).toFile(),session=privateDir(Files.createDirectory(home.resolve("session"))).toFile();
  File legacy=home.resolve("old.db").toFile(),output=home.resolve("out.rlc2-integration.db").toFile(),ledger=home.resolve("ledger.db").toFile(),manifest=new File(session,"rotation.manifest");
  for(File f:new File[]{legacy,output,ledger,manifest}){Files.write(f.toPath(),new byte[]{1});privateFile(f.toPath());}
  return new DeploymentBinding.Layout(root,legacy,output,session,manifest,ledger,LaneOwnershipTest.D,LaneOwnershipTest.V,"synthetic", "8d034ae6-32ac-4d51-bdcb-4cf7cc199542");
 }
 static DeploymentBinding.Layout with(DeploymentBinding.Layout l,File root,File db,File out,File session,File manifest) {
  return new DeploymentBinding.Layout(root,db,out,session,manifest,l.ledger,l.device,l.vehicle,l.payloadSession,l.manifestSession);
 }
 public static void main(String[] args)throws Exception {
  final DeploymentBinding.Layout l=fixture();
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(false,true,l);}},"disabled before inspection");
  reject(new Action(){public void run()throws Exception{DeploymentBinding.bindSynthetic(true,false,l);}},"external single-active assertion mandatory");
  DeploymentBinding.bindSynthetic(true,true,l);DeploymentBinding.verify(true,true,l);check(true,"fresh private local deployment binds");
  final byte[] before=Files.readAllBytes(l.root.toPath().resolve(DeploymentBinding.NAME));
  DeploymentBinding.verify(true,true,l);check(Arrays.equals(before,Files.readAllBytes(l.root.toPath().resolve(DeploymentBinding.NAME))),"inspect preserves binding bytes");
  final File alternate=Files.createDirectory(l.root.toPath().getParent().resolve("other-root")).toFile();
  reject(new Action(){public void run()throws Exception{DeploymentBinding.bindSynthetic(true,true,with(l,alternate,l.legacy,l.output,l.session,l.manifest));}},"same exact DB cannot enroll second root");
  Files.copy(l.root.toPath().resolve(DeploymentBinding.NAME),alternate.toPath().resolve(DeploymentBinding.NAME));
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,with(l,alternate,l.legacy,l.output,l.session,l.manifest));}},"copied root metadata rejected");
  final File otherDb=l.legacy.toPath().resolveSibling("other.db").toFile();Files.copy(l.legacy.toPath(),otherDb.toPath());
  Files.copy(Paths.get(l.legacy+".deployment-binding"),Paths.get(otherDb+".deployment-binding"));
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,with(l,l.root,otherDb,l.output,l.session,l.manifest));}},"different old DB and copied anchor rejected");
  final File nested=Files.write(l.session.toPath().resolve("nested.db"),new byte[]{1}).toFile();
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,with(l,l.root,l.legacy,nested,l.session,l.manifest));}},"output in snapshot rejected");
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,with(l,new File("relative"),l.legacy,l.output,l.session,l.manifest));}},"relative root rejected");
  Path renamed=l.root.toPath().resolveSibling("renamed-root");Files.move(l.root.toPath(),renamed);
  final DeploymentBinding.Layout moved=with(l,renamed.toFile(),l.legacy,l.output,l.session,l.manifest);
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,moved);}},"root rename rejected");Files.move(renamed,l.root.toPath());
  Path binding=l.root.toPath().resolve(DeploymentBinding.NAME);
  String changed=new String(before,java.nio.charset.StandardCharsets.US_ASCII).replace("version=UkxEQjE=","version=UkxEQjk=");Files.write(binding,changed.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,l);}},"unknown version rejected");Files.write(binding,before);
  Files.move(l.manifest.toPath(),l.manifest.toPath().resolveSibling("saved.manifest"));
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,l);}},"missing manifest rejected");Files.move(l.manifest.toPath().resolveSibling("saved.manifest"),l.manifest.toPath());
  Path link=l.root.toPath().resolveSibling("linked-root");
  if(System.getProperty("os.name").startsWith("Windows")) {
   Process junction=new ProcessBuilder("cmd","/c","mklink","/J",link.toString(),l.root.toString()).redirectErrorStream(true).redirectOutput(l.root.toPath().getParent().resolve("junction.log").toFile()).start();
   check(junction.waitFor()==0,"Windows junction fixture created");
  } else Files.createSymbolicLink(link,l.root.toPath());
  final DeploymentBinding.Layout linked=with(l,link.toFile(),l.legacy,l.output,l.session,l.manifest);
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,linked);}},"junction or symlink rejected");
  // A full coordinated copy at identical paths/identities cannot be globally fenced here.
  check(DeploymentBinding.json("REVIEW_REQUIRED","EXTERNAL_GATE_REQUIRED").contains("\"global_fencing\":false"),"no clone/global fencing claim");
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  Path cli=l.root.toPath().getParent().resolve("cli.json");
  Process child=new ProcessBuilder(exe,"-cp",System.getProperty("java.class.path"),DeploymentBinding.class.getName(),"--inspect-synthetic","--external-single-active",l.root.toString(),l.legacy.toString(),l.output.toString(),l.session.toString(),l.manifest.toString(),l.ledger.toString(),l.device,l.vehicle,l.payloadSession,l.manifestSession).redirectErrorStream(true).redirectOutput(cli.toFile()).start();
  check(child.waitFor()==0,"real read-only CLI exit0 for bound synthetic fixture");String json=new String(Files.readAllBytes(cli),java.nio.charset.StandardCharsets.UTF_8);
  check(json.contains("READY_FOR_SYNTHETIC_TEST")&&!json.contains(l.root.toString())&&json.contains("\"production_authorized\":false"),"machine JSON omits paths and production permission");
  if(Files.getFileAttributeView(l.root.toPath(),PosixFileAttributeView.class)!=null)Files.setPosixFilePermissions(l.root.toPath(),PosixFilePermissions.fromString("rwxrwxrwx"));
  else {AclFileAttributeView acl=Files.getFileAttributeView(l.root.toPath(),AclFileAttributeView.class);List<AclEntry> entries=new ArrayList<AclEntry>(acl.getAcl());entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(l.root.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName("BUILTIN\\Users")).setPermissions(AclEntryPermission.WRITE_DATA).build());acl.setAcl(entries);}
  reject(new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,l);}},"dangerous writable permissions rejected");
  System.out.println("Deployment binding passed "+checks+" checks; global fencing BLOCKED; directory durability UNKNOWN");
 }
}
