package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Result;

/**
 * Phase VII-4 adversarial checks against the single entry with real separate processes: sidecar-free noncooperative SQLite
 * writers (BEGIN IMMEDIATE/EXCLUSIVE) that the entry cannot see, a writer that creates a hot journal, two supervisors racing
 * on one root, junction/symlink and hardlink aliases, same-name directory replacement and smuggled authorization fields.
 * Known limits are asserted as limits (WRITER_UNPROVEN kept), not hidden as PASS.
 */
public final class Phase7AdversarialTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    static String[] args(Deployment d,String mode)throws Exception {return new String[]{mode,d.profile.getPath(),d.f.manifestSha(),d.f.sourcesCsv()};}
    static long records(Deployment d)throws Exception {return Rlc2PreviewStoreTest.count(d.f.output,"SELECT COUNT(*) FROM preview_records");}
    static boolean windows(){return System.getProperty("os.name").startsWith("Windows");}

    /** Separate JVM holding a SQLite transaction on the v1 DB without the cooperative Worker lock. */
    static final class Writer implements AutoCloseable {
        final Process process;final BufferedReader out;final PrintWriter in;final String pid;
        Writer(File db,String mode)throws Exception {
            process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),Child.class.getName(),db.toString(),mode).redirectErrorStream(true).start();
            out=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));in=new PrintWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8),true);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!out.ready()){if(!process.isAlive()||System.nanoTime()>end)throw new AssertionError("writer did not start");Thread.sleep(10);}
            String line=out.readLine();check(line.startsWith("HELD "),"noncooperative "+mode+" writer holds v1 "+line);pid=line.substring(5);
        }
        public void close()throws Exception {in.println("rollback");if(!process.waitFor(30,TimeUnit.SECONDS))process.destroyForcibly();check(process.exitValue()==0,"writer "+pid+" rolled back");in.close();out.close();}
    }
    public static final class Child {
        public static void main(String[] a)throws Exception {
            Class.forName("org.sqlite.JDBC");
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+new File(a[0]).toURI().toASCIIString()+"?mode=rw");Statement s=c.createStatement()){
                s.execute("BEGIN "+(a[1].equals("exclusive")?"EXCLUSIVE":"IMMEDIATE"));
                if(a[1].equals("journal"))s.execute("CREATE TABLE phase7_noncooperative_writer(x INTEGER)");
                System.out.println("HELD "+ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);System.out.flush();
                new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();s.execute("ROLLBACK");
            }
        }
    }
    static Process start(Deployment d,String mode)throws Exception {
        Map<String,String> r=d.record();List<String> c=new ArrayList<String>(Arrays.asList(r.get("java_executable"),"-cp",r.get("classpath"),OfflineCandidateEntry.MAIN));c.addAll(Arrays.asList(args(d,mode)));
        Phase7EntryTest.requireWork(d);
        Process p=new ProcessBuilder(c).directory(d.work).redirectErrorStream(true).start();p.getOutputStream().close();return p;
    }
    static Result finish(Process p)throws Exception {
        ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}
        check(p.waitFor(600,TimeUnit.SECONDS),"entry process finished");String[] lines=new String(o.toByteArray(),StandardCharsets.UTF_8).trim().split("\r?\n");return new Result(p.exitValue(),lines[lines.length-1]);
    }
    static Deployment forged(Deployment base,String key,String value)throws Exception {
        Map<String,String> r=base.record();r.put(key,value);File p=base.f.home.resolve("forged-"+UUID.randomUUID()+".rlop1").toFile();
        net.neristal.rrbridge.product.v1.DeploymentBinding.createRecord(p.toPath(),net.neristal.rrbridge.product.v1.DeploymentBinding.encodeRecord(r));return new Deployment(base,p);
    }

    public static void main(String[] argv)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        List<File> packaged=new ArrayList<File>();for(String a:argv)packaged.add(new File(a).getCanonicalFile());

        // ---- 1. sidecar-free noncooperative writers: the reproduced limit. The entry cannot see them and must keep WRITER_UNPROVEN.
        {
            // Product v1 (DurableSpool) is WAL: a noncooperative writer's connection leaves -wal/-shm, which is refused.
            Deployment d=new Deployment("synthetic-phase7-adv-wal",packaged,true,0);Result r;
            try(Writer w=new Writer(d.f.legacy,"immediate")){
                check(Files.exists(Paths.get(d.f.legacy.getPath()+"-wal"))||Files.exists(Paths.get(d.f.legacy.getPath()+"-shm")),"WAL-mode v1 writer leaves a sidecar");
                r=finish(start(d,"--synthetic-owned"));
            }
            check(r.exit!=0&&r.json.contains("\"child_started\":false")&&records(d)==0,"WAL writer sidecar refuses before any child ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
        }
        for(String mode:new String[]{"immediate","exclusive"}){
            Deployment d=new Deployment(new Phase7Fixture("synthetic-phase7-adv-"+mode,false,0,true),packaged,true,0);String v1=net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.legacy);Result r;
            try(Writer w=new Writer(d.f.legacy,mode)){
                check(!Files.exists(Paths.get(d.f.legacy.getPath()+"-journal"))&&!Files.exists(Paths.get(d.f.legacy.getPath()+"-wal")),mode+" writer left no sidecar");
                r=finish(start(d,"--synthetic-owned"));
            }
            System.out.println("LIMIT "+mode+" exit="+r.exit+" reason="+OfflineCandidateEntry.field(r.json,"reason"));
            check(r.json.contains("\"writer_quiescence\":\"WRITER_UNPROVEN\"")&&r.json.contains("\"launch_authorized\":false")&&r.json.contains("\"production_authorized\":false"),mode+" sidecar-free writer: result keeps WRITER_UNPROVEN, no authorization (limit, not proof)");
            check(v1.equals(net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.legacy)),mode+" writer: v1 bytes unchanged");
        }
        // ---- 2. a noncooperative writer that has created a hot journal is refused before writing.
        {
            Deployment d=new Deployment(new Phase7Fixture("synthetic-phase7-adv-journal",false,0,true),packaged,true,0);String o=net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.output);Result r;
            try(Writer w=new Writer(d.f.legacy,"journal")){
                check(Files.exists(Paths.get(d.f.legacy.getPath()+"-journal")),"journal writer created a hot journal");
                r=finish(start(d,"--synthetic-owned"));
            }
            check(r.exit!=0&&r.json.contains("\"child_started\":false"),"hot journal on v1 refuses before any child ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
            check(o.equals(net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.output))&&records(d)==0,"hot journal refusal: candidate unchanged");
        }
        // ---- 3. two supervisors racing on the same owned root: never duplicates, at most one store adds rows.
        {
            Deployment d=new Deployment("synthetic-phase7-adv-race",packaged,true,0);
            Process a=start(d,"--synthetic-owned"),b=start(d,"--synthetic-owned");Result ra=finish(a),rb=finish(b);
            System.out.println("RACE a="+ra.exit+" "+ra.json);System.out.println("RACE b="+rb.exit+" "+rb.json);
            check((ra.exit==0||rb.exit==0)&&records(d)==2&&Rlc2PreviewStoreTest.count(d.f.output,"SELECT COUNT(*) FROM preview_receipts")==3,"racing supervisors converge on exactly two records / three receipts");
            int added=(ra.json.contains("\"records_added\":2")?1:0)+(rb.json.contains("\"records_added\":2")?1:0);
            check(added==1,"exactly one racer committed the rows");
            Result loser=ra.json.contains("\"records_added\":2")?rb:ra;
            check(loser.exit==0?loser.json.contains("\"records_added\":0"):(loser.exit==3||loser.exit==5)&&!"REVIEW_REQUIRED".equals(OfflineCandidateEntry.field(loser.json,"reason")),"loser adds nothing or refuses with a coded reason ["+loser.exit+" "+OfflineCandidateEntry.field(loser.json,"reason")+"]");
        }
        // ---- 4. aliases: junction/symlink data root, hardlinked v1, same-name directory replacement.
        {
            Deployment d=new Deployment("synthetic-phase7-adv-alias",packaged,true,0);String o=net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.output);
            Path link=d.f.home.getParent().resolve(d.f.home.getFileName()+"-alias");
            Process mk=windows()?new ProcessBuilder(new File(System.getenv("SystemRoot"),"System32\\cmd.exe").getPath(),"/c","mklink","/J",link.toString(),d.f.home.toString()).redirectErrorStream(true).start()
                :new ProcessBuilder("ln","-s",d.f.home.toString(),link.toString()).redirectErrorStream(true).start();
            mk.getInputStream().close();check(mk.waitFor(30,TimeUnit.SECONDS)&&mk.exitValue()==0&&Files.exists(link),(windows()?"actual junction":"symbolic link")+" to the owned data root created");
            Result r=Phase7EntryTest.inProcess(args(forged(d,"data_root",link.toString()),"--synthetic-owned"),d.facts());
            check(r.exit==5&&OfflineCandidateEntry.field(r.json,"reason").startsWith("OWNED_"),"aliased data root refused before DB access ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
            Files.delete(link);
            Path hard=d.f.home.resolve("v1-hardlink.db");Files.createLink(hard,d.f.legacy.toPath());
            r=Phase7EntryTest.inProcess(args(d,"--synthetic-owned"),d.facts());
            check(r.exit==5&&OfflineCandidateEntry.field(r.json,"reason").contains("HARDLINK"),"second hard link to v1 refused ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
            Files.delete(hard);
            check(o.equals(net.neristal.rrbridge.prototype.ingress.DeploymentTransition.fileSha(d.f.output)),"alias refusals: candidate unchanged");
        }
        {
            Deployment d=new Deployment("synthetic-phase7-adv-dirswap",packaged,true,0);
            Path session=d.f.dir,moved=d.f.home.resolve("session-original");Files.move(session,moved);
            net.neristal.rrbridge.product.v1.DeploymentBindingTest.privateDir(Files.createDirectory(session));
            try(DirectoryStream<Path> files=Files.newDirectoryStream(moved)){for(Path p:files)Files.copy(p,session.resolve(p.getFileName()));}
            DeploymentTransitionTest.privatize(d.f.home);
            Result r=Phase7EntryTest.inProcess(args(d,"--synthetic-owned"),d.facts());
            check(r.exit==5&&"OWNED_MARKER_IDENTITY_MISMATCH".equals(OfflineCandidateEntry.field(r.json,"reason")),"same-name session directory with identical copies refused ["+OfflineCandidateEntry.field(r.json,"reason")+"]");
            check(records(d)==0,"directory swap: nothing stored");
        }
        // ---- 5. smuggled authorization fields in profile / caller cannot promote anything.
        {
            Deployment d=new Deployment("synthetic-phase7-adv-forge",packaged,true,0);
            for(String[] f:new String[][]{{"launch_authorized","true"},{"writer_quiescence","PASS"},{"artifact.extra","x"}}){
                Result r=Phase7EntryTest.inProcess(args(forged(d,f[0],f[1]),"--synthetic-owned"),d.facts());
                String reason=OfflineCandidateEntry.field(r.json,"reason");
                check(r.exit==5&&(reason.equals("PROFILE_UNKNOWN_FIELD")||reason.startsWith("PROVENANCE")||reason.startsWith("CLASSPATH")),"profile field "+f[0]+" refused ["+reason+"]");
                check(r.json.contains("\"launch_authorized\":false")&&r.json.contains("\"writer_quiescence\":\"WRITER_UNPROVEN\""),"forged "+f[0]+": output never promotes");
            }
            check(records(d)==0,"forged profiles: nothing stored");
        }
        System.out.println("NOT_RUN subst/drive-letter alias creation and real SMB (no system mapping changes); covered only by the Phase VI injected drive probe");
        System.out.println("Phase VII adversarial checks="+checks);
    }
}
