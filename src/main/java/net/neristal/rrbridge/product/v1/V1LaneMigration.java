package net.neristal.rrbridge.product.v1;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Versioned synthetic-copy inventory migration. Never mutates the input DB. */
public final class V1LaneMigration {
 public static final int APPLICATION_ID=0x524d4931;
 private static final String[] TABLES={"v1_source_claims","v1_journal_import_cursors","v1_import_cursors","v1_records","v1_profiles","v1_ack_receipts","v1_delivery_state","v1_ingress_review_state","sqlite_sequence"};
 public interface Hook{void beforeCommit()throws Exception;}
 public enum Stage { BEFORE_OUTPUT, OWNER_CREATED, COPY_PROGRESS, COPY_COMPLETED, BEFORE_COMMIT, AFTER_COMMIT }
 public interface StageHook {void at(Stage stage)throws Exception;}
 public static final StageHook NO_STAGE_FAULT=new StageHook(){public void at(Stage stage){}};
 public static final Hook NO_FAULT=new Hook(){public void beforeCommit(){}};
 public static final class Report {
  public final Map<String,Long> rows;public final long acked,pending;public final String sourceSha,dataSha;
  Report(Map<String,Long> rows,long acked,long pending,String sourceSha,String dataSha){this.rows=Collections.unmodifiableMap(rows);this.acked=acked;this.pending=pending;this.sourceSha=sourceSha;this.dataSha=dataSha;}
  public String json(){return "{\"contract_version\":1,\"acked_records\":"+acked+",\"pending_records\":"+pending+",\"source_claims\":"+rows.get("v1_source_claims")+",\"records_changed\":0,\"ack_activated\":false,\"source_sha256\":\""+sourceSha+"\"}";}
 }
 private static Connection open(File f,boolean readOnly)throws Exception {
  Class.forName("org.sqlite.JDBC");return DriverManager.getConnection("jdbc:sqlite:"+f.toURI().toASCIIString()+(readOnly?"?mode=ro&immutable=1":"?mode=rw"));
 }
 private static long scalar(Connection db,String sql)throws SQLException {try(Statement s=db.createStatement();ResultSet r=s.executeQuery(sql)){if(!r.next())throw new SQLException("missing scalar");return r.getLong(1);}}
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}
 private static String hash(File f)throws Exception {
  if(!f.isFile()||Files.isSymbolicLink(f.toPath())||f.length()>128L*1024*1024)throw new IOException("bounded closed regular DB required");
  MessageDigest sha=MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=Files.newInputStream(f.toPath())){byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException("DB grew past migration capacity");sha.update(b,0,n);}}return hex(sha.digest());
 }
 private static void closed(File f)throws IOException {for(String suffix:new String[]{"-wal","-shm","-journal"})if(Files.exists(new File(f.getPath()+suffix).toPath()))throw new IOException("closed checkpointed DB required");}
 private static Map<String,String> schema(Connection db)throws SQLException {
  Map<String,String> m=new TreeMap<String,String>();try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY type,name")){while(r.next())m.put(r.getString(1)+":"+r.getString(2),r.getString(3));}return m;
 }
 private static Map<String,String> referenceSchema(boolean candidate)throws Exception {
  java.nio.file.Path directory=Files.createTempDirectory(candidate?"romlogger-migration-schema-reference-":"romlogger-v1-schema-reference-");
  File reference=directory.resolve("synthetic.db").toFile();
  try {
   new DurableSpool(reference);
   try(Connection db=open(reference,!candidate)) {
    if(candidate)try(Statement statement=db.createStatement()){ddl(statement);}
    return schema(db);
   }
  } finally {
   // Only exact files in this invocation's fresh directory, never wildcard/recursive cleanup.
   for(String suffix:new String[]{"-wal","-shm","-journal",""}) {
    java.nio.file.Path file=java.nio.file.Paths.get(reference.getPath()+suffix);
    if(Files.isSymbolicLink(file))throw new IOException("reference cleanup path changed; retained evidence");
    Files.deleteIfExists(file);
   }
   Files.delete(directory); // Unexpected files cause refusal and remain for review.
  }
 }
 private static Map<String,String> baseline()throws Exception {return referenceSchema(false);}
 private static Map<String,String> candidateSchema()throws Exception {return referenceSchema(true);}
 public static Report verifyIsolatedCopy(boolean enable,boolean quiescent,File source,File target)throws Exception {
  if(!enable||!quiescent)throw new IOException("isolated copy verification requires quiescence");
  if(source.getCanonicalFile().equals(target.getCanonicalFile()))throw new IOException("separate copied target required");
  Report report=inspect(source);closed(target);String before=hash(target);
  File owner=new File(target.getPath()+".migration-owner");
  if(!owner.isFile()||Files.size(owner.toPath())>128||Files.isSymbolicLink(owner.toPath())||!Arrays.equals(Files.readAllBytes(owner.toPath()),("RMI1\n"+report.sourceSha+"\n").getBytes(StandardCharsets.US_ASCII)))throw new IOException("migration owner mismatch");
  try(Connection db=open(target,true)) {
   if(scalar(db,"PRAGMA application_id")!=APPLICATION_ID||scalar(db,"PRAGMA user_version")!=1||!schema(db).equals(candidateSchema())||!data(db).equals(report.dataSha))throw new IOException("candidate preservation mismatch");
   try(Statement statement=db.createStatement();ResultSet r=statement.executeQuery("SELECT version,source_sha,data_sha FROM lane_migration_v1")) {
    if(!r.next()||r.getInt(1)!=1||!report.sourceSha.equals(r.getString(2))||!report.dataSha.equals(r.getString(3))||r.next())throw new IOException("candidate evidence mismatch");
   }
   String originals="SELECT device_id,vehicle_id,session_id,source_kind,source_key FROM v1_source_claims",inventory="SELECT device,vehicle,session,source_kind,source_key FROM lane_migration_sources_v1";
   if(scalar(db,"SELECT count(*) FROM ("+originals+" EXCEPT "+inventory+")")!=0||scalar(db,"SELECT count(*) FROM ("+inventory+" EXCEPT "+originals+")")!=0)throw new IOException("candidate claim mismatch");
  }
  if(!before.equals(hash(target))||!report.sourceSha.equals(hash(source)))throw new IOException("copy changed during verification");return report;
 }
 private static String data(Connection db)throws Exception {
  MessageDigest sha=MessageDigest.getInstance("SHA-256");
  for(String table:TABLES){sha.update(("TABLE:"+table+":"+scalar(db,"SELECT count(*) FROM "+table)+":").getBytes(StandardCharsets.UTF_8));try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT rowid,* FROM "+table+" ORDER BY rowid")){
   int columns=r.getMetaData().getColumnCount();while(r.next())for(int c=1;c<=columns;c++){Object v=r.getObject(c);byte[] b=v==null?new byte[0]:v instanceof byte[]?(byte[])v:v.toString().getBytes(StandardCharsets.UTF_8);String type=v==null?"NULL":v.getClass().getName();sha.update((type+":"+b.length+":").getBytes(StandardCharsets.UTF_8));sha.update(b);}
  }}return hex(sha.digest());
 }
 static Report inspect(File source)throws Exception {
  closed(source);String before=hash(source);Map<String,Long> counts=new LinkedHashMap<String,Long>();
  try(Connection db=open(source,true)){
   if(scalar(db,"PRAGMA application_id")!=0||scalar(db,"PRAGMA user_version")!=0||!schema(db).equals(baseline()))throw new IOException("unknown v1 schema; no migration");
   try(Statement s=db.createStatement();ResultSet r=s.executeQuery("PRAGMA quick_check")){if(!r.next()||!"ok".equals(r.getString(1))||r.next())throw new IOException("integrity refusal");}
   for(String table:TABLES)counts.put(table,scalar(db,"SELECT count(*) FROM "+table));
   // No auto-adoption of a cursor which contradicts its durable source claim.
   if(scalar(db,"SELECT count(*) FROM v1_journal_import_cursors c LEFT JOIN v1_source_claims s ON c.device_id=s.device_id AND c.vehicle_id=s.vehicle_id AND c.session_id=s.session_id WHERE s.source_kind IS NULL OR s.source_kind<>'journal' OR s.source_key<>c.source_uuid")!=0)throw new IOException("journal source claim conflict");
   try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT source_path,device_id,vehicle_id FROM v1_import_cursors")) {while(r.next()){
    File path=new File(r.getString(1));String name=path.getName();if(!name.startsWith("rr_")||!name.endsWith(".tsv"))throw new IOException("unknown TSV cursor identity");
    String session=name.substring(3,name.length()-4);
    try(PreparedStatement q=db.prepareStatement("SELECT source_kind,source_key FROM v1_source_claims WHERE device_id=? AND vehicle_id=? AND session_id=?")){q.setString(1,r.getString(2));q.setString(2,r.getString(3));q.setString(3,session);try(ResultSet claim=q.executeQuery()){if(!claim.next()||!"legacy".equals(claim.getString(1))||!path.getPath().equals(claim.getString(2)))throw new IOException("TSV source claim conflict; manual review");}}
   }}
   String digest=data(db);long acked=scalar(db,"SELECT count(*) FROM v1_records WHERE acked=1"),pending=scalar(db,"SELECT count(*) FROM v1_records WHERE acked=0");
   if(!before.equals(hash(source)))throw new IOException("source changed during preflight");return new Report(counts,acked,pending,before,digest);
  }
 }
 public static Report preflight(boolean enable,boolean quiescent,File source)throws Exception {
  if(!enable||!quiescent)throw new IOException("isolated migration and quiescence required");
  try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(source)){Report report=inspect(source);old.verify();return report;}
 }
 private static void ddl(Statement s)throws SQLException {
  s.execute("CREATE TABLE lane_migration_v1(version INTEGER PRIMARY KEY CHECK(version=1),source_sha TEXT NOT NULL,data_sha TEXT NOT NULL)");
  s.execute("CREATE TABLE lane_migration_sources_v1(device TEXT NOT NULL,vehicle TEXT NOT NULL,session TEXT NOT NULL,source_kind TEXT NOT NULL,source_key TEXT NOT NULL,PRIMARY KEY(device,vehicle,session))");
  s.execute("INSERT INTO lane_migration_sources_v1 SELECT device_id,vehicle_id,session_id,source_kind,source_key FROM v1_source_claims");
  for(String table:TABLES)if(!table.equals("sqlite_sequence"))for(String action:new String[]{"INSERT","UPDATE","DELETE"})s.execute("CREATE TRIGGER lane_guard_"+table+"_"+action+" BEFORE "+action+" ON "+table+" BEGIN SELECT RAISE(ABORT,'ISOLATED_MIGRATION_READ_ONLY'); END");
 }
 private static void copyOwned(File source,File target,StageHook hook)throws Exception {
  java.util.Set<java.nio.file.OpenOption> options=new java.util.HashSet<java.nio.file.OpenOption>(java.util.Arrays.<java.nio.file.OpenOption>asList(java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE));
  java.nio.file.attribute.FileAttribute<?>[] attrs=Files.getFileAttributeView(target.toPath().getParent(),java.nio.file.attribute.PosixFileAttributeView.class)!=null?new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}:new java.nio.file.attribute.FileAttribute<?>[0];
  try(java.nio.channels.FileChannel input=java.nio.channels.FileChannel.open(source.toPath(),java.nio.file.StandardOpenOption.READ);java.nio.channels.FileChannel output=java.nio.channels.FileChannel.open(target.toPath(),options,attrs)) {
   java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(32768);long total=0;boolean first=true;
   while(input.read(buffer)!=-1){buffer.flip();total+=buffer.remaining();if(total>128L*1024*1024)throw new IOException("copy grew past capacity");while(buffer.hasRemaining())output.write(buffer);buffer.clear();if(first){output.force(true);hook.at(Stage.COPY_PROGRESS);first=false;}}
   output.force(true);
  }
 }
 public static Report migrateCopy(boolean enable,boolean quiescent,File source,File target,final Hook hook)throws Exception {
  return migrateCopyWithStages(enable,quiescent,source,target,new StageHook(){public void at(Stage stage)throws Exception{if(stage==Stage.BEFORE_COMMIT)hook.beforeCommit();}});
 }
 /** Explicit synthetic fault boundary API; never a production switch or automatic repair. */
 public static Report migrateCopyWithStages(boolean enable,boolean quiescent,File source,File target,StageHook hook)throws Exception {
  if(!enable||!quiescent)throw new IOException("isolated migration and quiescence required");
  // Reject alias/junction targets before guard creation or any SQLite writable open.
  DeploymentBinding.safe(source);
  java.nio.file.Path targetPath=target.toPath();
  if(!targetPath.isAbsolute()||!targetPath.equals(targetPath.normalize()))throw new IOException("absolute isolated target required");
  DeploymentBinding.safe(targetPath.getParent().toFile());
  if(Files.exists(targetPath,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
   if(Files.isSameFile(source.toPath(),targetPath))throw new IOException("source/target alias refused");
   DeploymentBinding.safe(target);
  }
  if(!target.getName().endsWith(".lane-migration.db")||source.getCanonicalFile().equals(target.getCanonicalFile())||Files.isSymbolicLink(target.toPath()))throw new IOException("dedicated isolated target required");
  try(LegacyWorkerGuard old=LegacyWorkerGuard.acquire(source);LegacyWorkerGuard output=LegacyWorkerGuard.acquire(target)) {
   Report report=inspect(source);hook.at(Stage.BEFORE_OUTPUT);
   File owner=new File(target.getPath()+".migration-owner");
   byte[] ownerBytes=("RMI1\n"+report.sourceSha+"\n").getBytes(StandardCharsets.US_ASCII);
   if(!target.exists()) {
    if(owner.exists())throw new IOException("incomplete copy ownership requires manual review");
    java.util.Set<java.nio.file.OpenOption> markerOptions=new java.util.HashSet<java.nio.file.OpenOption>(java.util.Arrays.<java.nio.file.OpenOption>asList(java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE));
    java.nio.file.attribute.FileAttribute<?>[] markerAttrs=Files.getFileAttributeView(owner.toPath().getParent(),java.nio.file.attribute.PosixFileAttributeView.class)!=null?new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}:new java.nio.file.attribute.FileAttribute<?>[0];
    try(java.nio.channels.FileChannel marker=java.nio.channels.FileChannel.open(owner.toPath(),markerOptions,markerAttrs)) {
     java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(ownerBytes);while(b.hasRemaining())marker.write(b);marker.force(true);
    }
    hook.at(Stage.OWNER_CREATED);copyOwned(source,target,hook);hook.at(Stage.COPY_COMPLETED);
   }
   DeploymentBinding.safe(owner);
   if(!owner.isFile()||Files.size(owner.toPath())>128||Files.isSymbolicLink(owner.toPath())||!Arrays.equals(Files.readAllBytes(owner.toPath()),ownerBytes))throw new IOException("migration copy owner mismatch");
   for(String suffix:new String[]{"-wal","-shm","-journal"}) {
    java.nio.file.Path recovery=java.nio.file.Paths.get(target+suffix);
    if(Files.exists(recovery,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
     DeploymentBinding.safe(recovery.toFile());
     if(!Files.isRegularFile(recovery,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe target recovery file");
    }
   }
   // Only this positively owned copied target may undergo SQLite rollback recovery.
   byte[] header=new byte[100];try(java.io.RandomAccessFile h=new java.io.RandomAccessFile(target,"r")){h.readFully(header);}
   if(!Arrays.equals(Arrays.copyOf(header,16),"SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII)))throw new IOException("foreign target header");
   int headerApp=java.nio.ByteBuffer.wrap(header).getInt(68);if(headerApp!=0&&headerApp!=APPLICATION_ID)throw new IOException("foreign target application");
   boolean interrupted=false;for(String suffix:new String[]{"-wal","-shm","-journal"})if(Files.exists(java.nio.file.Paths.get(target+suffix)))interrupted=true;
   if(headerApp==0&&!interrupted&&!hash(target).equals(report.sourceSha))throw new IOException("partial or modified plain copy; manual review required");
   try(Connection db=open(target,false);Statement s=db.createStatement()) {
    s.execute("PRAGMA synchronous=FULL");s.execute("BEGIN IMMEDIATE");
    try {
     long app=scalar(db,"PRAGMA application_id");
     if(app==APPLICATION_ID){
      if(scalar(db,"PRAGMA user_version")!=1)throw new IOException("unknown migration version");
      try(ResultSet r=s.executeQuery("SELECT version,source_sha,data_sha FROM lane_migration_v1")){if(!r.next()||r.getInt(1)!=1||!report.sourceSha.equals(r.getString(2))||!report.dataSha.equals(r.getString(3))||r.next())throw new IOException("migration source/evidence mismatch");}
     }else {
      if(app!=0||!schema(db).equals(baseline())||!data(db).equals(report.dataSha))throw new IOException("foreign or modified migration target");
      ddl(s);s.execute("PRAGMA application_id="+APPLICATION_ID);s.execute("PRAGMA user_version=1");
      try(PreparedStatement q=db.prepareStatement("INSERT INTO lane_migration_v1 VALUES(1,?,?)")){q.setString(1,report.sourceSha);q.setString(2,report.dataSha);q.executeUpdate();}
     }
     String originals="SELECT device_id,vehicle_id,session_id,source_kind,source_key FROM v1_source_claims";
     String inventory="SELECT device,vehicle,session,source_kind,source_key FROM lane_migration_sources_v1";
     if(scalar(db,"SELECT count(*) FROM ("+originals+" EXCEPT "+inventory+")")!=0||scalar(db,"SELECT count(*) FROM ("+inventory+" EXCEPT "+originals+")")!=0)throw new IOException("migration inventory changed");
     if(!schema(db).equals(candidateSchema()))throw new IOException("migration schema/guard mismatch");
     if(!data(db).equals(report.dataSha))throw new IOException("preserved record/ACK/cursor data changed");
     hook.at(Stage.BEFORE_COMMIT);old.verify();output.verify();if(!report.sourceSha.equals(hash(source)))throw new IOException("source changed before commit");s.execute("COMMIT");hook.at(Stage.AFTER_COMMIT);
    }catch(Exception e){try{s.execute("ROLLBACK");}catch(SQLException ignored){}throw e;}
   }
   if(!report.sourceSha.equals(hash(source)))throw new IOException("source changed after isolated migration");return report;
  }
 }
 public static final class CopyState {
  public final String state,action;public final Report inventory;
  CopyState(String state,String action,Report inventory){this.state=state;this.action=action;this.inventory=inventory;}
  public int exit(){return state.equals("COMMITTED_INVENTORY_VERIFIED")?0:state.equals("BLOCKED_OWNER_BUSY")?3:5;}
  public String json(){return "{\"contract_version\":1,\"copy_state\":\""+state+"\",\"operator_action\":\""+action+"\",\"automatic_repair\":false,\"production_authorized\":false,\"ack_eligible\":false,\"acked_records\":"+(inventory==null?-1:inventory.acked)+",\"pending_records\":"+(inventory==null?-1:inventory.pending)+"}";}
 }
 private static final class Observation implements AutoCloseable {
  final java.nio.channels.FileChannel channel;
  Observation(java.nio.channels.FileChannel channel){this.channel=channel;}
  public void close()throws IOException{channel.close();}
 }
 private static Observation observe(File db,boolean required)throws Exception {
  java.nio.file.Path path=java.nio.file.Paths.get(db.getPath()+".worker.lock");
  if(!Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)){if(required)throw new IOException("COPY_OWNER_UNKNOWN");return null;}
  DeploymentBinding.safe(path.toFile());java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.READ);
  try {java.nio.channels.FileLock lock;try{lock=channel.tryLock(0,Long.MAX_VALUE,true);}catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("COPY_OWNER_BUSY");}if(lock==null)throw new IOException("COPY_OWNER_BUSY");return new Observation(channel);}
  catch(Exception e){channel.close();throw e;}
 }
 /** Observes existing locks only. No source/target/owner/sidecar creation or recovery. */
 public static CopyState inspectCopyState(boolean enable,boolean quiescent,File source,File target) {
  Report report=null;
  try {
   if(!enable||!quiescent)throw new IOException("COPY_GATE_REQUIRED");
   DeploymentBinding.safe(source);java.nio.file.Path path=target.toPath();
   if(!path.isAbsolute()||!path.equals(path.normalize())||!target.getName().endsWith(".lane-migration.db"))throw new IOException("COPY_TARGET_INVALID");
   DeploymentBinding.safe(path.getParent().toFile());
   try(Observation old=observe(source,true);Observation output=observe(target,false)) {
    report=inspect(source);File owner=new File(target+".migration-owner");
    boolean exists=Files.exists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS),marked=Files.exists(owner.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS);
    if(!exists&&!marked)return new CopyState("NO_CANDIDATE","EXPLICIT_SYNTHETIC_RETRY",report);
    if(!marked)return new CopyState("UNOWNED_TARGET_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
    DeploymentBinding.safe(owner);if(owner.length()>128||!Arrays.equals(Files.readAllBytes(owner.toPath()),("RMI1\n"+report.sourceSha+"\n").getBytes(StandardCharsets.US_ASCII)))return new CopyState("OWNER_MISMATCH_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
    if(!exists)return new CopyState("OWNER_ONLY_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
    DeploymentBinding.safe(target);if(Files.isSameFile(source.toPath(),path))throw new IOException("COPY_TARGET_INVALID");
    byte[] header=new byte[100];try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(target,"r")){if(file.length()<100)return new CopyState("PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);file.readFully(header);}
    if(!Arrays.equals(Arrays.copyOf(header,16),"SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII)))return new CopyState("PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
    int app=java.nio.ByteBuffer.wrap(header).getInt(68);if(app!=0&&app!=APPLICATION_ID)return new CopyState("PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
    boolean needsRecovery=false;
    for(String suffix:new String[]{"-wal","-shm","-journal"}) {
     java.nio.file.Path recovery=java.nio.file.Paths.get(target+suffix);
     if(Files.exists(recovery,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      DeploymentBinding.safe(recovery.toFile());if(!Files.isRegularFile(recovery,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe target recovery file");
      needsRecovery=true;
     }
    }
    if(needsRecovery)return new CopyState("OWNED_INTERRUPTED_TARGET_REVIEW_REQUIRED","EXPLICIT_OWNED_RECOVERY_RETRY",report);
    if(app==0&&hash(target).equals(report.sourceSha))return new CopyState("COPIED_V1_READY_FOR_EXPLICIT_RETRY","EXPLICIT_SYNTHETIC_RETRY",report);
    if(app==APPLICATION_ID){verifyIsolatedCopy(true,true,source,target);return new CopyState("COMMITTED_INVENTORY_VERIFIED","CANDIDATE_VERIFIED_NO_ACTIVATION",report);}
    return new CopyState("PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);
   }
  }catch(Exception e){return new CopyState("COPY_OWNER_BUSY".equals(e.getMessage())?"BLOCKED_OWNER_BUSY":"INSPECTION_REVIEW_REQUIRED","RETAIN_EVIDENCE_MANUAL_REVIEW",report);}
 }
 public static void main(String[] args)throws Exception {
  if(args.length>0&&"--inspect-isolated-copy-state".equals(args[0])) {
   if(args.length!=5||!"--external-quiescent".equals(args[1])||!"--no-apply".equals(args[4])){System.out.println(new CopyState("USAGE","RETAIN_EVIDENCE_MANUAL_REVIEW",null).json());System.exit(2);return;}
   CopyState state=inspectCopyState(true,true,new File(args[2]),new File(args[3]));System.out.println(state.json());System.exit(state.exit());return;
  }
  if(args.length!=3||!"--dry-run-isolated-quiescent".equals(args[0])||!"--no-apply".equals(args[2]))throw new IllegalArgumentException("explicit dry-run mode required");
  System.out.println(preflight(true,true,new File(args[1])).json());
 }
}
