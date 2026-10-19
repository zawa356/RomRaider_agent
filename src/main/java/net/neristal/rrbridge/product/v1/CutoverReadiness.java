package net.neristal.rrbridge.product.v1;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.jar.*;
/** Advisory only: no stop, migration, repair, lock creation or production approval. */
public final class CutoverReadiness {
 public static final class Report {
  public final String state,reason;public final long acked,pending,claims,cursors,receipts;
  Report(String state,String reason,V1LaneMigration.Report inventory){this.state=state;this.reason=reason;acked=inventory==null?-1:inventory.acked;pending=inventory==null?-1:inventory.pending;claims=inventory==null?-1:inventory.rows.get("v1_source_claims");cursors=inventory==null?-1:inventory.rows.get("v1_import_cursors")+inventory.rows.get("v1_journal_import_cursors");receipts=inventory==null?-1:inventory.rows.get("v1_ack_receipts");}
  public String json(){String base=DeploymentBinding.json(state,reason);return base.substring(0,base.length()-1)+",\"acked_records\":"+acked+",\"pending_records\":"+pending+",\"source_claims\":"+claims+",\"cursors\":"+cursors+",\"ack_receipts\":"+receipts+",\"automatic_cutover\":false}";}
 }
 private static byte[] bounded(InputStream in)throws IOException {try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=source.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("artifact entry bound");out.write(b,0,n);}return out.toByteArray();}}
 /** Exact current build class bytes only. Unknown historic binaries are never inferred compatible. */
 public static boolean knownArtifact(File jar)throws Exception {
  DeploymentBinding.safe(jar);if(!jar.isFile()||jar.length()>32L*1024*1024)return false;
  Set<String> required=new HashSet<String>(Arrays.asList("ProductBridgeWorker","LegacyWorkerGuard","DurableSpool","IncrementalTsvBridge","ProductDeliveryCoordinator","ProductSender","ProductLegacyScanner","ProductJournalScanner","AgentWire","LaneOwnership"));int entries=0;
  try(JarFile file=new JarFile(jar)) {Enumeration<JarEntry> all=file.entries();while(all.hasMoreElements()) {
   JarEntry e=all.nextElement();if(++entries>1024)return false;if(!e.getName().endsWith(".class"))continue;
   if(!e.getName().startsWith("net/neristal/rrbridge/"))return false;
   InputStream current=CutoverReadiness.class.getResourceAsStream("/"+e.getName());if(current==null)return false;
   if(!Arrays.equals(bounded(current),bounded(file.getInputStream(e))))return false;
   if(e.getName().startsWith("net/neristal/rrbridge/product/v1/"))required.remove(e.getName().substring(e.getName().lastIndexOf('/')+1,e.getName().length()-6));
  }}return required.isEmpty();
 }
 private static long scalar(Connection c,String sql)throws SQLException {try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 public static Report inspect(boolean enable,boolean externallySingleActive,DeploymentBinding.Layout l,File artifact) {
  V1LaneMigration.Report inventory=null;
  try {
   DeploymentBinding.verify(enable,externallySingleActive,l);
   // Existing exact lock only: absence is unknown, never permission and never creates a file.
   Path lock=Paths.get(l.legacy.getPath()+".worker.lock");
   if(!Files.exists(lock,LinkOption.NOFOLLOW_LINKS))return new Report("REVIEW_REQUIRED","LEGACY_LOCK_ABSENT",null);
   DeploymentBinding.safe(lock.toFile());DeploymentBinding.privateAccess(lock);
   try(FileChannel c=FileChannel.open(lock,StandardOpenOption.READ)) {
    FileLock observed;try{observed=c.tryLock(0,Long.MAX_VALUE,true);}catch(OverlappingFileLockException busy){return new Report("BLOCKED_UNCOOPERATIVE_PROCESS","LEGACY_WORKER_BUSY",null);}
    if(observed==null)return new Report("BLOCKED_UNCOOPERATIVE_PROCESS","LEGACY_WORKER_BUSY",null);
    try {
     if(artifact==null||!knownArtifact(artifact))return new Report("REVIEW_REQUIRED","UNKNOWN_WORKER_BINARY",null);
     String lane=LaneOwnership.diagnose(l.root,l.device,l.vehicle,l.payloadSession);
     if("BUSY".equals(lane))return new Report("BLOCKED_UNCOOPERATIVE_PROCESS","LANE_BUSY",null);
     if(!"IDLE".equals(lane)&&!"MISSING".equals(lane))return new Report("REVIEW_REQUIRED","LANE_EVIDENCE_INVALID",null);
     inventory=V1LaneMigration.inspect(l.legacy);
     Class.forName("org.sqlite.JDBC");
     try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+l.legacy.toURI().toASCIIString()+"?mode=ro&immutable=1")) {
      if(scalar(db,"SELECT count(*) FROM v1_ingress_review_state")>0||scalar(db,"SELECT count(*) FROM v1_delivery_state WHERE needs_review<>0 OR retry_count<>0")>0)return new Report("REVIEW_REQUIRED","DELIVERY_OR_INGRESS_REVIEW",inventory);
     }
     DeploymentBinding.verify(true,true,l);
     return new Report("READY_FOR_SYNTHETIC_TEST","KNOWN_BUILD_EXTERNAL_QUIESCENCE_ASSERTED",inventory);
    }finally{observed.release();}
   }
  }catch(Exception e){return new Report("REVIEW_REQUIRED",e instanceof DeploymentBinding.Refusal?e.getMessage():"INSPECTION_UNAVAILABLE",inventory);}
 }
 public static void main(String[] args) {
  if(args.length!=13||!"--cutover-readiness-synthetic".equals(args[0])||!"--external-single-active".equals(args[1])){System.out.println(DeploymentBinding.json("REVIEW_REQUIRED","USAGE"));System.exit(2);return;}
  DeploymentBinding.Layout l=new DeploymentBinding.Layout(new File(args[2]),new File(args[3]),new File(args[4]),new File(args[5]),new File(args[6]),new File(args[7]),args[8],args[9],args[10],args[11]);
  Report r=inspect(true,true,l,new File(args[12]));System.out.println(r.json());System.exit("READY_FOR_SYNTHETIC_TEST".equals(r.state)?0:"BLOCKED_UNCOOPERATIVE_PROCESS".equals(r.state)?3:5);
 }
}
