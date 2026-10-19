package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;

/**
 * RLAP1 artifact/classpath provenance (Phase IV, read-only, synthetic acceptance).
 *
 * <p>Describes an exact ordered JAR classpath (SHA-256, size, class set) and the Java runtime, and compares a
 * launch or a running process against it. A matching description is NOT proof that a process runs it:
 * only a child JVM launched by the verifier itself, or (Linux, same user) the JAR bytes reachable through the
 * process's own open file descriptors, are accepted as process evidence. Everything else is
 * UNKNOWN_PROCESS_PROVENANCE. No signature, administrator or distribution authenticity is claimed.
 */
public final class ArtifactProvenance {
 public static final String VERSION="RLAP1";
 public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
 private static void require(boolean ok,String code)throws Refusal {if(!ok)throw new Refusal(code);}
 private static final int MAX_ENTRIES=64,MAX_CLASSES=50000;
 private static final long MAX_JAR=64L*1024*1024;

 /** Ordered classpath description. Refuses wildcards, directories, missing/aliased entries, Class-Path manifests and shadowed classes. */
 public static Map<String,String> describe(List<File> classpath,String mainClass)throws Exception {
  require(!classpath.isEmpty()&&classpath.size()<=MAX_ENTRIES,"CLASSPATH_BOUND");
  require(mainClass!=null&&mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*"),"MAIN_CLASS_INVALID");
  Map<String,String> m=new TreeMap<String,String>();m.put("version",VERSION);m.put("main_class",mainClass);
  m.put("java_specification",System.getProperty("java.specification.version"));m.put("data_model",String.valueOf(System.getProperty("sun.arch.data.model")));
  Map<String,Integer> owner=new HashMap<String,Integer>();Set<String> seen=new HashSet<String>();boolean mainFound=false;
  for(int i=0;i<classpath.size();i++) {
   File f=classpath.get(i);String text=f.getPath();
   require(!text.endsWith("*")&&!text.contains("*"),"WILDCARD_CLASSPATH");
   require(f.isAbsolute(),"CLASSPATH_NOT_ABSOLUTE");
   require(!Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS),"DIRECTORY_ENTRY_REFUSED");
   require(Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS),"CLASSPATH_ENTRY_MISSING");
   require(!Files.isSymbolicLink(f.toPath()),"CLASSPATH_LINK_REFUSED");
   require(f.getName().endsWith(".jar")&&f.length()<=MAX_JAR,"CLASSPATH_ENTRY_NOT_JAR");
   require(seen.add(f.getCanonicalPath()),"CLASSPATH_DUPLICATE_ENTRY");
   int classes=0;MessageDigest names=MessageDigest.getInstance("SHA-256");
   try(JarFile jar=new JarFile(f)) {
    require(manifestHasNoClassPath(jar.getManifest()),"MANIFEST_CLASSPATH_REFUSED");
    List<String> sorted=new ArrayList<String>();Set<String> local=new HashSet<String>();
    for(Enumeration<JarEntry> e=jar.entries();e.hasMoreElements();) {
     JarEntry entry=e.nextElement();String n=entry.getName();
     require(local.add(n),"JAR_DUPLICATE_ENTRY");
     if(!n.endsWith(".class")||n.startsWith("META-INF/"))continue;
     require(++classes<=MAX_CLASSES,"CLASS_BOUND");sorted.add(n);
     if(n.equals("module-info.class"))continue;
     Integer prior=owner.put(n,i);require(prior==null,"DUPLICATE_CLASS");
     if(n.equals(mainClass.replace('.','/')+".class"))mainFound=true;
    }
    Collections.sort(sorted);for(String n:sorted)names.update((n+"\n").getBytes(StandardCharsets.UTF_8));
   }
   String key=String.format(Locale.ROOT,"entry.%02d",i);
   m.put(key+".name",f.getName());m.put(key+".sha256",sha(f));m.put(key+".size",String.valueOf(f.length()));
   m.put(key+".classes",String.valueOf(classes));m.put(key+".class_names_sha256",hex(names.digest()));
  }
  require(mainFound,"MAIN_CLASS_MISSING");
  m.put("entries",String.valueOf(classpath.size()));
  return m;
 }
 /** Only Class-Path / agent / boot attributes are refused here; CutoverReadiness keeps its stricter product allowlist. */
 static boolean manifestHasNoClassPath(Manifest manifest) {
  if(manifest==null)return true;
  for(Object k:manifest.getMainAttributes().keySet()) {
   String key=k.toString();
   if(key.equalsIgnoreCase("Class-Path")||key.equalsIgnoreCase("Launcher-Agent-Class")||key.equalsIgnoreCase("Boot-Class-Path"))return false;
  }return true;
 }
 /** Parse a launch classpath string exactly (no wildcard expansion, no implicit current directory). */
 public static List<File> parse(String classpath)throws Refusal {
  require(classpath!=null&&!classpath.isEmpty(),"CLASSPATH_EMPTY");
  List<File> out=new ArrayList<File>();
  for(String part:classpath.split(java.util.regex.Pattern.quote(File.pathSeparator),-1)){require(!part.isEmpty(),"CLASSPATH_EMPTY_ELEMENT");out.add(new File(part));}
  return out;
 }
 /** Compare an observed description to the expected one; the first difference is a stable code. */
 public static void compare(Map<String,String> expected,Map<String,String> observed)throws Refusal {
  require(VERSION.equals(expected.get("version")),"PROVENANCE_VERSION");
  require(Objects.equals(expected.get("main_class"),observed.get("main_class")),"MAIN_CLASS_MISMATCH");
  require(Objects.equals(expected.get("java_specification"),observed.get("java_specification")),"JAVA_VERSION_MISMATCH");
  require(Objects.equals(expected.get("data_model"),observed.get("data_model")),"DATA_MODEL_MISMATCH");
  require(Objects.equals(expected.get("entries"),observed.get("entries")),"CLASSPATH_ENTRY_COUNT_CHANGED");
  int n=Integer.parseInt(expected.get("entries"));
  // Order matters: the same hashes in another order is a different class resolution.
  List<String> exp=new ArrayList<String>(),obs=new ArrayList<String>();
  for(int i=0;i<n;i++){String k=String.format(Locale.ROOT,"entry.%02d",i);exp.add(expected.get(k+".sha256"));obs.add(observed.get(k+".sha256"));}
  if(!exp.equals(obs)) {require(!new HashSet<String>(exp).equals(new HashSet<String>(obs)),"CLASSPATH_ORDER_CHANGED");throw new Refusal("CLASSPATH_ENTRY_CHANGED");}
  require(expected.equals(observed),"PROVENANCE_FIELD_CHANGED");
 }

 /** What a JVM can say about itself. A self report is a claim; it never authenticates the process alone. */
 public static Map<String,String> selfReport()throws Exception {
  Map<String,String> m=new TreeMap<String,String>();
  m.put("version","RLAP1-SELF");
  m.put("java_specification",System.getProperty("java.specification.version"));m.put("data_model",String.valueOf(System.getProperty("sun.arch.data.model")));
  m.put("classpath",System.getProperty("java.class.path"));
  m.put("main_class",String.valueOf(System.getProperty("sun.java.command","")).split(" ")[0]);
  String name=java.lang.management.ManagementFactory.getRuntimeMXBean().getName();m.put("pid",name.substring(0,Math.max(0,name.indexOf('@'))));
  // JVM options can contain secrets: only their digest is reported.
  m.put("jvm_arguments_sha256",sha(String.join("\n",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()).getBytes(StandardCharsets.UTF_8)));
  return m;
 }
 /** Description of the classpath named in a self report, compared to the expectation. */
 public static void verifySelfReport(Map<String,String> expected,Map<String,String> report)throws Exception {
  require("RLAP1-SELF".equals(report.get("version")),"SELF_REPORT_VERSION");
  Map<String,String> observed=describe(parse(report.get("classpath")),report.get("main_class"));
  observed.put("java_specification",report.get("java_specification"));observed.put("data_model",report.get("data_model"));
  compare(expected,observed);
 }

 /** Result of a process inspection: success of the inspection is separate from provenance and never authorizes anything. */
 public static final class Inspection {
  public final boolean inspectionSuccess;public final String processProvenance,reason;
  Inspection(boolean ok,String provenance,String reason){inspectionSuccess=ok;processProvenance=provenance;this.reason=reason;}
  public String json(){return "{\"contract_version\":1,\"inspection_success\":"+inspectionSuccess+",\"process_provenance\":\""+processProvenance+"\",\"reason\":\""+reason+"\",\"production_authorized\":false,\"ack_eligible\":false,\"signature_verified\":false}";}
 }
 static Inspection unknown(String reason){return new Inspection(true,"UNKNOWN_PROCESS_PROVENANCE",reason);}

 /**
  * Linux, same user, read-only: /proc/PID/cmdline must name exactly the expected classpath and main class, and every
  * expected JAR must be open in the process with matching bytes (read through /proc/PID/fd, so a file replaced on disk
  * after start is seen as the process sees it). A JAR not yet opened cannot be proven and stays UNKNOWN.
  * Command lines are compared, never printed.
  */
 public static Inspection inspectProcess(long pid,Map<String,String> expected) {
  try {
   if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"))return unknown("PROCESS_INSPECTION_UNAVAILABLE_ON_THIS_OS");
   Path proc=Paths.get("/proc",String.valueOf(pid));
   if(!Files.isDirectory(proc))return new Inspection(false,"UNKNOWN_PROCESS_PROVENANCE","PROCESS_NOT_FOUND");
   byte[] raw;try{raw=bounded(proc.resolve("cmdline"),65536);}catch(IOException e){return new Inspection(false,"UNKNOWN_PROCESS_PROVENANCE","CMDLINE_UNREADABLE");}
   List<String> argv=new ArrayList<String>();int start=0;for(int i=0;i<raw.length;i++)if(raw[i]==0){argv.add(new String(raw,start,i-start,StandardCharsets.UTF_8));start=i+1;}
   String cp=null,main=null;
   for(int i=1;i<argv.size();i++) {
    String a=argv.get(i);
    if(a.equals("-cp")||a.equals("-classpath")||a.equals("--class-path")){if(i+1<argv.size())cp=argv.get(++i);continue;}
    if(a.startsWith("-jar"))return unknown("JAR_LAUNCH_UNSUPPORTED");
    if(a.startsWith("@"))return unknown("ARGUMENT_FILE_UNSUPPORTED");
    if(a.startsWith("-"))continue;
    main=a;break;
   }
   if(cp==null||main==null)return unknown("CLASSPATH_OR_MAIN_NOT_EXPLICIT");
   Set<String> wanted=new HashSet<String>();try{for(File f:parse(cp))wanted.add(f.getCanonicalPath());}catch(Refusal r){return unknown(r.getMessage());}
   Set<String> opened=new HashSet<String>();
   try(DirectoryStream<Path> fds=Files.newDirectoryStream(proc.resolve("fd"))) {
    for(Path fd:fds) {
     String target;try{target=Files.readSymbolicLink(fd).toString();}catch(IOException e){continue;}
     if(target.endsWith(" (deleted)")&&target.substring(0,target.length()-10).endsWith(".jar"))return unknown("ARTIFACT_REPLACED_AFTER_START");
     if(!wanted.contains(target))continue;
     String sha;try(InputStream in=Files.newInputStream(fd)){sha=sha(in);}catch(IOException e){return unknown("OPEN_ARTIFACT_UNREADABLE");}
     boolean match=false;for(int i=0;i<Integer.parseInt(expected.get("entries"));i++)if(sha.equals(expected.get(String.format(Locale.ROOT,"entry.%02d.sha256",i))))match=true;
     if(!match)return unknown("OPEN_ARTIFACT_DIFFERS");
     opened.add(target);
    }
   }catch(IOException e){return unknown("FD_TABLE_UNREADABLE");}
   if(!opened.equals(wanted))return unknown("ARTIFACT_NOT_OPEN_YET");
   // Then the on-disk classpath named by the command line must still be exactly the expectation.
   // Runtime of the target is not readable without attaching: the expectation names the verifier's own runtime.
   Map<String,String> observed;
   try{observed=describe(parse(cp),main);compare(expected,observed);}catch(Refusal r){return unknown(r.getMessage());}
   return new Inspection(true,"MATCHED_OPEN_ARTIFACTS_LINUX_SAME_USER","CMDLINE_AND_OPEN_JARS_MATCH");
  }catch(Exception e){return new Inspection(false,"UNKNOWN_PROCESS_PROVENANCE","INSPECTION_UNAVAILABLE");}
 }

 static byte[] bounded(Path p,int max)throws IOException {
  try(InputStream in=Files.newInputStream(p);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("bound");out.write(b,0,n);}return out.toByteArray();}
 }
 static String sha(File f)throws Exception {try(InputStream in=Files.newInputStream(f.toPath())){return sha(in);}}
 static String sha(InputStream in)throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){total+=n;require(total<=MAX_JAR,"CLASSPATH_ENTRY_NOT_JAR");d.update(b,0,n);}return hex(d.digest());}
 static String sha(byte[] b)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(b));}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}

 /** Record format shared with RLDB1 (key=base64). */
 public static byte[] encode(Map<String,String> m){return DeploymentBinding.encodeRecord(m);}

 /**
  * Read-only CLI. {@code --describe MAIN_CLASS JAR...} prints the RLAP1 record (base64 lines; names, not paths).
  * {@code --inspect-process PID RLAP1_FILE} prints inspection JSON. Exit 0 = inspected (see process_provenance), 2 usage, 5 refused.
  */
 public static void main(String[] a) {
  try {
   if(a.length>=3&&"--describe".equals(a[0])) {
    List<File> jars=new ArrayList<File>();for(int i=2;i<a.length;i++)jars.add(new File(a[i]));
    System.out.write(encode(describe(jars,a[1])));System.out.flush();System.exit(0);return;
   }
   if(a.length==3&&"--inspect-process".equals(a[0])) {
    Inspection r=inspectProcess(Long.parseLong(a[1]),DeploymentBinding.readRecord(Paths.get(a[2])));System.out.println(r.json());System.exit(r.inspectionSuccess?0:5);return;
   }
   System.out.println(unknown("USAGE").json());System.exit(2);
  }catch(Exception e){System.out.println(new Inspection(false,"UNKNOWN_PROCESS_PROVENANCE",e instanceof Refusal||e instanceof DeploymentBinding.Refusal?e.getMessage():"INSPECTION_UNAVAILABLE").json());System.exit(5);}
 }
 private ArtifactProvenance(){}
}
