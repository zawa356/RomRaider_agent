package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
/** RLAP1 synthetic acceptance: only fresh JARs and a child JVM launched by this test; no service or Worker is touched. */
public final class ArtifactProvenanceTest {
 static int checks;
 interface Action{void run()throws Exception;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;System.out.println("PASS "+why);}
 static void refuses(String code,String why,Action a){String got;try{a.run();got="ACCEPTED";}catch(Exception e){got=e.getMessage();}check(code.equals(got),why+" ["+got+"]");}
 static final String MAIN=ProvenanceChild.class.getName();
 /** JAR from compiled product/v1 classes (+ the synthetic child). */
 static File appJar(Path dir,String name,Manifest manifest,String extraEntry)throws Exception {
  Path classes=Paths.get(ArtifactProvenance.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  File jar=dir.resolve(name).toFile();
  try(JarOutputStream out=manifest==null?new JarOutputStream(new FileOutputStream(jar)):new JarOutputStream(new FileOutputStream(jar),manifest);
      DirectoryStream<Path> files=Files.newDirectoryStream(classes.resolve("net/neristal/rrbridge/product/v1"),"*.class")) {
   for(Path p:files){out.putNextEntry(new JarEntry("net/neristal/rrbridge/product/v1/"+p.getFileName()));Files.copy(p,out);out.closeEntry();}
   if(extraEntry!=null){out.putNextEntry(new JarEntry(extraEntry));out.write(1);out.closeEntry();}
  }return jar;
 }
 static File shadowJar(Path dir)throws Exception {
  Path classes=Paths.get(ArtifactProvenance.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  File jar=dir.resolve("shadow.jar").toFile();
  try(JarOutputStream out=new JarOutputStream(new FileOutputStream(jar))){String n=MAIN.replace('.','/')+".class";out.putNextEntry(new JarEntry(n));Files.copy(classes.resolve(n),out);out.closeEntry();}
  return jar;
 }
 static List<File> list(File... f){return Arrays.asList(f);}
 static Map<String,String> parseRecord(byte[] bytes)throws Exception {
  Map<String,String> m=new TreeMap<String,String>();
  for(String line:new String(bytes,StandardCharsets.US_ASCII).split("\n")){if(line.isEmpty())continue;int at=line.indexOf('=');m.put(line.substring(0,at),new String(Base64.getDecoder().decode(line.substring(at+1)),StandardCharsets.UTF_8));}
  return m;
 }
 public static void main(String[] args)throws Exception {
  System.out.println("os="+System.getProperty("os.name")+" java="+System.getProperty("java.version")+" data_model="+System.getProperty("sun.arch.data.model"));
  final Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase4-provenance-"));
  final File app=appJar(home,"app.jar",null,null);
  final File sqlite=home.resolve("sqlite-jdbc.jar").toFile();Files.copy(Paths.get("lib","sqlite-jdbc.jar"),sqlite.toPath());
  final Map<String,String> expected=ArtifactProvenance.describe(list(app,sqlite),MAIN);
  check(expected.get("entries").equals("2")&&!new String(ArtifactProvenance.encode(expected),StandardCharsets.US_ASCII).contains(Base64.getEncoder().encodeToString(home.toString().getBytes(StandardCharsets.UTF_8))),"RLAP1 describes ordered JAR hashes without directory paths");
  ArtifactProvenance.compare(expected,ArtifactProvenance.describe(list(app,sqlite),MAIN));check(true,"same classpath compares equal");
  refuses("CLASSPATH_ORDER_CHANGED","reordered classpath refused",new Action(){public void run()throws Exception{ArtifactProvenance.compare(expected,ArtifactProvenance.describe(list(sqlite,app),MAIN));}});
  refuses("WILDCARD_CLASSPATH","wildcard classpath refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(new File(home.toFile(),"*")),MAIN);}});
  refuses("DIRECTORY_ENTRY_REFUSED","directory classpath entry refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(home.toFile(),sqlite),MAIN);}});
  refuses("CLASSPATH_ENTRY_MISSING","missing JAR refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(app,home.resolve("absent.jar").toFile()),MAIN);}});
  refuses("CLASSPATH_ENTRY_COUNT_CHANGED","missing dependency refused",new Action(){public void run()throws Exception{ArtifactProvenance.compare(expected,ArtifactProvenance.describe(list(app),MAIN));}});
  final File shadow=shadowJar(home);
  refuses("DUPLICATE_CLASS","class shadowed by an earlier JAR refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(shadow,app,sqlite),MAIN);}});
  final File changed=appJar(Files.createDirectory(home.resolve("changed")),"app.jar",null,"net/neristal/rrbridge/product/v1/Extra.class");
  refuses("CLASSPATH_ENTRY_CHANGED","replaced JAR bytes refused",new Action(){public void run()throws Exception{ArtifactProvenance.compare(expected,ArtifactProvenance.describe(list(changed,sqlite),MAIN));}});
  Manifest m=new Manifest();m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");m.getMainAttributes().put(Attributes.Name.CLASS_PATH,"override.jar");
  final File withClassPath=appJar(Files.createDirectory(home.resolve("cp")),"app.jar",m,null);
  refuses("MANIFEST_CLASSPATH_REFUSED","manifest Class-Path refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(withClassPath,sqlite),MAIN);}});
  final Map<String,String> otherJava=new TreeMap<String,String>(expected);otherJava.put("java_specification","99");
  refuses("JAVA_VERSION_MISMATCH","different Java version refused",new Action(){public void run()throws Exception{ArtifactProvenance.compare(otherJava,ArtifactProvenance.describe(list(app,sqlite),MAIN));}});
  final Map<String,String> otherModel=new TreeMap<String,String>(expected);otherModel.put("data_model","32".equals(expected.get("data_model"))?"64":"32");
  refuses("DATA_MODEL_MISMATCH","different JVM bitness refused",new Action(){public void run()throws Exception{ArtifactProvenance.compare(otherModel,ArtifactProvenance.describe(list(app,sqlite),MAIN));}});
  refuses("MAIN_CLASS_MISSING","main class absent from classpath refused",new Action(){public void run()throws Exception{ArtifactProvenance.describe(list(sqlite),MAIN);}});

  // Child JVM launched by this verifier with the exact classpath: its self report is checked against the expectation.
  String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
  String cp=app.getPath()+File.pathSeparator+sqlite.getPath();
  Process child=new ProcessBuilder(exe,"-Dromlogger.synthetic.secret=do-not-print","-cp",cp,MAIN).redirectError(home.resolve("child.err").toFile()).start();
  try {
   BufferedReader in=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.US_ASCII));StringBuilder report=new StringBuilder();String line;
   while((line=in.readLine())!=null&&!line.equals("END"))report.append(line).append('\n');
   final Map<String,String> self=parseRecord(report.toString().getBytes(StandardCharsets.US_ASCII));
   check(!report.toString().contains(Base64.getEncoder().encodeToString("do-not-print".getBytes(StandardCharsets.UTF_8)))&&self.get("jvm_arguments_sha256").length()==64,"self report hashes JVM options instead of printing them");
   ArtifactProvenance.verifySelfReport(expected,self);check(true,"verifier-launched child self report matches RLAP1 (LAUNCHED_BY_VERIFIER_MATCHED)");
   Map<String,String> forged=new TreeMap<String,String>(self);forged.put("classpath",sqlite.getPath()+File.pathSeparator+app.getPath());
   final Map<String,String> f2=forged;
   refuses("CLASSPATH_ORDER_CHANGED","self report naming another classpath refused",new Action(){public void run()throws Exception{ArtifactProvenance.verifySelfReport(expected,f2);}});
   long pid=Long.parseLong(self.get("pid"));
   ArtifactProvenance.Inspection seen=ArtifactProvenance.inspectProcess(pid,expected);
   check(seen.json().contains("\"production_authorized\":false")&&seen.json().contains("\"ack_eligible\":false")&&seen.json().contains("\"signature_verified\":false"),"inspection JSON separates inspection from authorization");
   if(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
    check(seen.processProvenance.equals("MATCHED_OPEN_ARTIFACTS_LINUX_SAME_USER"),"Linux same-user child: command line and open JAR bytes match ["+seen.reason+"]");
    // Replace app.jar on disk after start: the process still runs the old bytes (deleted inode).
    Path staged=home.resolve("app.jar.next");Files.copy(changed.toPath(),staged);Files.move(staged,app.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    ArtifactProvenance.Inspection replaced=ArtifactProvenance.inspectProcess(pid,expected);
    check(replaced.processProvenance.equals("UNKNOWN_PROCESS_PROVENANCE")&&replaced.reason.equals("ARTIFACT_REPLACED_AFTER_START"),"JAR replaced after start detected ["+replaced.reason+"]");
   } else {
    check(seen.processProvenance.equals("UNKNOWN_PROCESS_PROVENANCE")&&seen.reason.equals("PROCESS_INSPECTION_UNAVAILABLE_ON_THIS_OS"),"Windows: running process provenance stays UNKNOWN ["+seen.reason+"]");
   }
   ArtifactProvenance.Inspection self2=ArtifactProvenance.inspectProcess(Long.parseLong(ArtifactProvenance.selfReport().get("pid")),expected);
   check(self2.processProvenance.equals("UNKNOWN_PROCESS_PROVENANCE"),"a PID running another classpath is never matched ["+self2.reason+"]");
   ArtifactProvenance.Inspection none=ArtifactProvenance.inspectProcess(Long.MAX_VALUE-7,expected);
   check(none.processProvenance.equals("UNKNOWN_PROCESS_PROVENANCE"),"absent PID is UNKNOWN ["+none.reason+"]");
  } finally {
   try{child.getOutputStream().write('\n');child.getOutputStream().flush();}catch(IOException ignored){}
   if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();
  }
  check(!child.isAlive(),"synthetic child stopped (only the child this test started)");
  System.out.println("Artifact provenance passed "+checks+" checks; signatures/distribution authenticity NOT CLAIMED; Windows process provenance UNKNOWN");
 }
}
