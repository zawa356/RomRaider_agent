package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Actual child JVMs write only fresh synthetic fixtures; stdin handshakes order every race. */
public final class Phase6WriterTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static String hash(File f)throws Exception{return DeploymentTransition.fileSha(f);}
    static boolean sidecars(File f){for(String s:new String[]{"-wal","-shm","-journal"})if(Files.exists(Paths.get(f+ s),LinkOption.NOFOLLOW_LINKS))return true;return false;}
    static final class Writer implements AutoCloseable {
        final Process process;final BufferedReader output;final PrintWriter input;final String pid;
        Writer(File db,String mode)throws Exception {
            process=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),Child.class.getName(),db.toString(),mode).redirectErrorStream(true).start();
            output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            input=new PrintWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8),true);
            String line=read();check(line.startsWith("HELD "),"writer ready "+mode+" "+line);pid=line.substring(5);
        }
        String read()throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!output.ready()){if(!process.isAlive())throw new AssertionError("writer exited "+process.exitValue());if(System.nanoTime()>end)throw new AssertionError("writer handshake timeout");Thread.sleep(10);}
            return output.readLine();
        }
        void finish(String command)throws Exception {
            input.println(command);check(process.waitFor(20,TimeUnit.SECONDS),"child completed "+pid);
            check(process.exitValue()==("halt".equals(command)?73:0),"child exit "+pid+"="+process.exitValue());
        }
        public void close()throws Exception{if(process.isAlive()){input.println("rollback");if(!process.waitFor(20,TimeUnit.SECONDS))process.destroyForcibly();}output.close();input.close();}
    }
    static void diagnostic(LegacyProvenanceClassifier.Report report)throws Exception {
        check(report.json().contains("\"writer_quiescence\":\"WRITER_UNPROVEN\""),"classification explicitly keeps writer unproven");
        check(report.json().contains("\"operational_readiness\":\"BLOCKED\""),"classification cannot authorize operation");
    }
    // Reflection permits running this test against the exact pre-change code for RED evidence.
    static void gate(File v1)throws Exception {
        Class<?> gate=Class.forName("net.neristal.rrbridge.prototype.ingress.LegacyCandidateGate");
        String json=(String)gate.getMethod("inspect",boolean.class,File.class).invoke(null,true,v1);
        check(json.contains("\"state\":\"BLOCKED\"")&&json.contains("\"ack_eligible\":false"),"candidate gate blocks without writing");
        try{gate.getMethod("requireWriterProof",boolean.class,File.class).invoke(null,true,v1);throw new AssertionError("gate authorized writer absence");}
        catch(java.lang.reflect.InvocationTargetException expected){check(expected.getCause() instanceof IOException,"writer proof refusal is coded IO");}
    }
    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" os="+System.getProperty("os.name"));
        if(args.length>0&&args[0].equals("--late-sidecar")){lateSidecar();return;}
        for(String mode:new String[]{"reserved","exclusive","journal","wal"})for(String finish:new String[]{"rollback","commit","halt"}){
            final Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();
            Phase5ProvenanceTest.sql(f.v1,"PRAGMA journal_mode="+(mode.equals("wal")?"WAL":"DELETE"));
            String before=hash(f.v1),ledger=hash(f.ledger),manifest=hash(f.dir.resolve("rotation.manifest").toFile());
            try(Writer writer=new Writer(f.v1,mode)){
                boolean dirty=mode.equals("journal")||mode.equals("wal");
                check(sidecars(f.v1)==dirty,"distinguish sidecar from lock-only "+mode);
                // Immutable reads really do bypass both reserved and exclusive SQLite locks.
                if(!dirty){
                    LegacyProvenanceClassifier.Report report=f.classify(f.v1);
                    check(report.migratable()==f.p.records.size(),"legacy immutable content correspondence under "+mode);
                    if(args.length==0||!"--observe-only".equals(args[0]))diagnostic(report);
                    File vault=f.home.resolve("locked-vault.db").toFile(),rli2=f.home.resolve("locked.rlc2-integration.db").toFile();
                    LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
                    check(LegacyProvenanceClassifier.stagingState(vault,rli2).equals("STAGED_LOCAL_HASHES_MATCH_INACTIVE"),"legacy staging is inactive local evidence despite held SQLite lock");
                }else{
                    boolean refused=false;try{f.classify(f.v1);}catch(Exception expected){refused=true;}check(refused,"classification refuses actual "+mode+" sidecar");
                    File vault=f.home.resolve("dirty-vault.db").toFile(),rli2=f.home.resolve("dirty.rlc2-integration.db").toFile();
                    refused=false;try{LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);}catch(Exception expected){refused=true;}
                    check(refused&&!vault.exists()&&!LegacyStagingTargets.work(vault).toFile().exists(),"sidecar refuses before staging claim");
                }
                if(args.length==0||!"--observe-only".equals(args[0]))gate(f.v1);
                check(hash(f.v1).equals(before),"reader/gate did not change v1 main bytes before writer release");
                writer.finish(finish);
            }
            // A hot journal is retained after halt; no implicit writable recovery is attempted.
            if(finish.equals("halt")&&mode.equals("journal")){
                check(sidecars(f.v1),"halt retains hot journal");boolean refused=false;try{f.classify(f.v1);}catch(Exception expected){refused=true;}check(refused,"restart refuses hot journal without recovery");
            }else if(!sidecars(f.v1)){
                LegacyProvenanceClassifier.Report after=f.classify(f.v1);
                if((mode.equals("journal")||mode.equals("wal"))&&finish.equals("commit"))check(after.migratable()==0,"writer commit invalidates stored body correspondence");
                else check(after.migratable()==f.p.records.size(),"rollback/lock-only exit preserves content correspondence");
            }
            if(!finish.equals("commit")||mode.equals("reserved")||mode.equals("exclusive"))check(hash(f.v1).equals(before),"source main hash unchanged after "+mode+"/"+finish);
            check(hash(f.ledger).equals(ledger)&&hash(f.dir.resolve("rotation.manifest").toFile()).equals(manifest),"reader leaves ledger/manifest unchanged");
            System.out.println("EVIDENCE mode="+mode+" finish="+finish+" before="+before+" after="+hash(f.v1)+" sidecars="+sidecars(f.v1));
        }
        System.out.println("Phase VI writer checks="+checks);
    }
    static void lateSidecar()throws Exception {
        final Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();
        Phase5ProvenanceTest.sql(f.v1,"PRAGMA journal_mode=DELETE");
        final String before=hash(f.v1);final Writer[] writer={null};
        File vault=f.home.resolve("late-vault.db").toFile(),rli2=f.home.resolve("late.rlc2-integration.db").toFile();
        boolean refused=false;
        try{
            LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,java.util.Collections.<LegacyProvenanceClassifier.Original>emptyList(),f.env.reader(),vault,rli2,new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage stage)throws Exception {
                if(stage==LegacyProvenanceClassifier.StagingStage.VAULT_CREATED)writer[0]=new Writer(f.v1,"journal");
            }});
        }catch(IOException expected){refused=true;System.out.println("REFUSAL "+expected.getMessage());}
        finally{if(writer[0]!=null){writer[0].finish("rollback");writer[0].close();}}
        check(before.equals(hash(f.v1)),"late writer rollback leaves original unchanged");
        check(refused,"vault-only staging detects writer sidecar created AFTER classification");
        check(LegacyStagingTargets.work(vault).toFile().exists()&&!LegacyProvenanceClassifier.marker(vault).exists(),"partial staging retained without completion marker");
        check(LegacyProvenanceClassifier.stagingState(vault,rli2).equals("INCOMPLETE_REVIEW_REQUIRED"),"restart never adopts partial artifacts");
    }
    public static final class Child {
        public static void main(String[] args)throws Exception {
            Class.forName("org.sqlite.JDBC");
            try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+new File(args[0]).toURI().toASCIIString()+"?mode=rw");Statement s=c.createStatement()){
                s.execute("PRAGMA journal_mode="+(args[1].equals("wal")?"WAL":"DELETE"));
                s.execute("BEGIN "+(args[1].equals("exclusive")?"EXCLUSIVE":"IMMEDIATE"));
                if(args[1].equals("journal")||args[1].equals("wal"))s.execute("UPDATE v1_records SET body_json='synthetic-writer-change' WHERE body_json IS NOT NULL");
                System.out.println("HELD "+ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);System.out.flush();
                String command=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8)).readLine();
                if("halt".equals(command))Runtime.getRuntime().halt(73);
                s.execute("commit".equals(command)?"COMMIT":"ROLLBACK");
            }
        }
    }
}
