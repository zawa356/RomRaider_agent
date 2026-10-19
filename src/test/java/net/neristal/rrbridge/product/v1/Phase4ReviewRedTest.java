package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.jar.*;
/** Phase IV independent review: each case states the required safe behaviour. Fresh synthetic fixtures only. */
public final class Phase4ReviewRedTest {
 static int pass,red;
 interface Action{void run()throws Exception;}
 static void expectRefusal(String name,Action a) {
  try{a.run();}catch(Exception e){pass++;System.out.println("PASS "+name+" ["+e.getMessage()+"]");return;}
  red++;System.out.println("RED  "+name+" (accepted)");
 }
 static void expect(String name,boolean ok) {if(ok){pass++;System.out.println("PASS "+name);}else{red++;System.out.println("RED  "+name);}}
 /** Replace the bound legacy DB by different bytes while keeping path and creation time. */
 static void replaceKeepingCreation(File target,byte[] bytes)throws Exception {
  FileTime created=Files.readAttributes(target.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).creationTime();
  Path staged=target.toPath().resolveSibling(target.getName()+".restored");
  Files.write(staged,bytes);DeploymentBindingTest.privateFile(staged);
  Files.move(staged,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
  Files.setAttribute(target.toPath(),"basic:creationTime",created,LinkOption.NOFOLLOW_LINKS);
 }
 static File jar(Path home,String name,Manifest manifest)throws Exception {
  Path classes=Paths.get(CutoverReadiness.class.getProtectionDomain().getCodeSource().getLocation().toURI());File jar=home.resolve(name).toFile();
  try(JarOutputStream out=manifest==null?new JarOutputStream(new FileOutputStream(jar)):new JarOutputStream(new FileOutputStream(jar),manifest);java.util.stream.Stream<Path> files=Files.walk(classes)) {
   for(Iterator<Path> it=files.iterator();it.hasNext();) {Path p=it.next();if(!p.toString().endsWith(".class"))continue;out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));Files.copy(p,out);out.closeEntry();}
  }return jar;
 }
 public static void main(String[] args)throws Exception {
  System.out.println("os="+System.getProperty("os.name")+" java="+System.getProperty("java.version")+" data_model="+System.getProperty("sun.arch.data.model"));
  // F1: the bound legacy DB is replaced by another DB (restore/rollback) at the same path.
  final DeploymentBinding.Layout a=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,a);
  replaceKeepingCreation(a.legacy,new byte[]{2,2,2});
  expectRefusal("F1 replaced legacy DB with preserved creation time refused",new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,a);}});
  // F1b: same replacement for the ledger (rolled-back durable ledger).
  final DeploymentBinding.Layout b=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,b);
  replaceKeepingCreation(b.ledger,new byte[]{3});
  expectRefusal("F1b replaced ledger with preserved creation time refused",new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,b);}});
  // F1c: delete + recreate at the same name, no explicit timestamp change (Windows name tunneling).
  final DeploymentBinding.Layout c=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,c);
  Files.delete(c.legacy.toPath());Files.write(c.legacy.toPath(),new byte[]{4});DeploymentBindingTest.privateFile(c.legacy.toPath());
  expectRefusal("F1c delete and recreate of legacy DB refused",new Action(){public void run()throws Exception{DeploymentBinding.verify(true,true,c);}});
  // Control: an in-place write by the legacy Worker keeps the same file and must still verify.
  final DeploymentBinding.Layout d=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,d);
  try(RandomAccessFile w=new RandomAccessFile(d.legacy,"rw")){w.seek(0);w.write(7);w.getFD().sync();}
  boolean inPlace;try{DeploymentBinding.verify(true,true,d);inPlace=true;}catch(Exception e){inPlace=false;System.out.println("  in-place verify: "+e.getMessage());}
  expect("F1 control: in-place legacy write keeps binding",inPlace);
  // F1d: an RLDB1 record written by the pre-Phase-IV Windows identity ("null|creation") must be re-enrolled, never trusted.
  if(System.getProperty("os.name").startsWith("Windows")) {
   final DeploymentBinding.Layout e=DeploymentBindingTest.fixture();DeploymentBinding.bindSynthetic(true,true,e);
   for(Path p:new Path[]{e.root.toPath().resolve(DeploymentBinding.NAME),Paths.get(e.legacy+".deployment-binding")}) {
    StringBuilder out=new StringBuilder();
    for(String line:new String(Files.readAllBytes(p),"US-ASCII").split("\n")) {
     int at=line.indexOf('=');String key=line.substring(0,at),value=new String(Base64.getDecoder().decode(line.substring(at+1)),"UTF-8");
     if(key.endsWith("_identity"))value="null|"+value.substring(value.indexOf('|')+1);
     out.append(key).append('=').append(Base64.getEncoder().encodeToString(value.getBytes("UTF-8"))).append('\n');
    }
    Files.write(p,out.toString().getBytes("US-ASCII"));
   }
   String reason;try{DeploymentBinding.verify(true,true,e);reason="ACCEPTED";}catch(Exception x){reason=x.getMessage();}
   expect("F1d pre-Phase-IV Windows identity requires re-enrollment ["+reason+"]","BINDING_IDENTITY_REENROLL_REQUIRED".equals(reason));
  }
  // F6: a dangling symlink sidecar must not read as "closed DB" in the RMI1 preflight.
  Path f6=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase4-sidecar-"));
  final File v1=f6.resolve("v1.db").toFile();Files.write(v1.toPath(),new byte[]{1});
  boolean linked;try{Files.createSymbolicLink(Paths.get(v1+"-wal"),f6.resolve("missing-target"));linked=true;}catch(Exception unsupported){linked=false;}
  if(linked)expectRefusal("F6 dangling symlink WAL sidecar refused",new Action(){public void run()throws Exception{V1LaneMigration.inspect(v1);}});
  else System.out.println("SKIP F6 symlink creation unavailable (no privilege); NOT RUN on this host");
  // F2: a JAR with identical class bytes but a manifest Class-Path can load missing classes from elsewhere.
  Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase4-artifact-"));
  Manifest m=new Manifest();m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");m.getMainAttributes().put(Attributes.Name.CLASS_PATH,"override.jar");
  final File withClassPath=jar(home,"classpath.jar",m);
  expect("F2 known artifact with manifest Class-Path refused",!CutoverReadiness.knownArtifact(withClassPath));
  Manifest agent=new Manifest();agent.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");agent.getMainAttributes().put(new Attributes.Name("Launcher-Agent-Class"),"example.Agent");
  expect("F2b known artifact with Launcher-Agent-Class refused",!CutoverReadiness.knownArtifact(jar(home,"agent.jar",agent)));
  Manifest premain=new Manifest();premain.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");premain.getMainAttributes().put(new Attributes.Name("Premain-Class"),"net.neristal.rrbridge.Agent");
  expect("F2 control: production Premain-Class manifest still recognized",CutoverReadiness.knownArtifact(jar(home,"premain.jar",premain)));
  expect("F2 control: plain current build still recognized",CutoverReadiness.knownArtifact(jar(home,"plain.jar",null)));
  System.out.println("Phase IV review: "+pass+" PASS, "+red+" RED");
  if(red>0)System.exit(1);
 }
}
