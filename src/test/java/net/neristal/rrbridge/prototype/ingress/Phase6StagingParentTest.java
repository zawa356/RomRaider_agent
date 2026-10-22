package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.product.v1.DeploymentBindingTest;

/** Actual separate-process directory replacement and junctions in invocation-owned fixtures only. */
public final class Phase6StagingParentTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}
    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        for(final String mode:new String[]{"directory","junction"}){
            final Phase5ProvenanceTest.Fixture f=new Phase5ProvenanceTest.Fixture();
            final Path parent=DeploymentBindingTest.privateDir(Files.createDirectory(f.home.resolve("owned-output")));
            final File vault=parent.resolve("vault.db").toFile(),rli2=parent.resolve("out.rlc2-integration.db").toFile();
            final Path competitor=(mode.equals("directory")?parent:f.home.resolve("competitor")).resolve("vault.db.rlpc1-work/candidate.rlc2-integration.db");
            final Path saved=f.home.resolve("saved-output");final boolean[] skipped={false};
            String before=DeploymentTransition.fileSha(f.v1);boolean refused=false;
            try{
                LegacyProvenanceClassifier.stageIsolated(true,true,f.v1,f.originals,f.env.reader(),vault,rli2,new LegacyProvenanceClassifier.StagingHook(){public void at(LegacyProvenanceClassifier.StagingStage stage)throws Exception {
                    if(stage!=LegacyProvenanceClassifier.StagingStage.VAULT_ROWS_WRITTEN)return;
                    Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),SwapChild.class.getName(),f.home.toString(),parent.toString(),mode).redirectErrorStream(true).redirectOutput(f.home.resolve("swap-child.log").toFile()).start();
                    if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();throw new IOException("SWAP_TIMEOUT");}
                    System.out.println(new String(Files.readAllBytes(f.home.resolve("swap-child.log")),StandardCharsets.UTF_8).trim());
                    if(child.exitValue()==2){skipped[0]=true;throw new IOException("JUNCTION_NOT_RUN");}
                    if(child.exitValue()!=0)throw new AssertionError("swap child failed "+child.exitValue());
                }});
            }catch(IOException expected){refused=true;System.out.println("REFUSAL "+mode+" "+expected.getMessage());}
            if(skipped[0]){System.out.println("NOT RUN privileged junction fixture");continue;}
            check(refused,"staging stops after actual child replaces output parent "+mode);
            check(Files.isRegularFile(competitor,LinkOption.NOFOLLOW_LINKS)&&Files.size(competitor)==0,"competing unclaimed internal DB remains byte-for-byte empty "+mode);
            check(Files.exists(saved.resolve("vault.db"))&&Files.exists(saved.resolve("vault.db.rlpc1-work")),"own published vault and private work evidence preserved "+mode);
            check(!Files.exists(LegacyProvenanceClassifier.marker(vault).toPath())&&!Files.exists(rli2.toPath()),"no competing output or completion marker published "+mode);
            check(before.equals(DeploymentTransition.fileSha(f.v1)),"original/ACK bytes unchanged "+mode);
        }
        System.out.println("Phase VI staging-parent checks="+checks);
    }
    public static final class SwapChild {
        public static void main(String[] args)throws Exception {
            Path home=Paths.get(args[0]).toRealPath(),parent=Paths.get(args[1]).toRealPath(),saved=home.resolve("saved-output").toAbsolutePath().normalize();
            if(!home.getFileName().toString().startsWith("romlogger-phase5-")||!parent.getParent().equals(home)||!parent.getFileName().toString().equals("owned-output")||!saved.getParent().equals(home))throw new IOException("OWNED_FIXTURE_REQUIRED");
            // Resolved source/destination are checked above, before moving the fresh owned test directory.
            Files.move(parent,saved);
            Path replacement=args[2].equals("directory")?parent:home.resolve("competitor");DeploymentBindingTest.privateDir(Files.createDirectory(replacement));
            Path work=DeploymentBindingTest.privateDir(Files.createDirectory(replacement.resolve("vault.db.rlpc1-work")));Files.createFile(work.resolve("candidate.rlc2-integration.db"));
            if(!args[2].equals("directory")){
                if(System.getProperty("os.name").startsWith("Windows")){
                    File tool=new File(System.getenv("SystemRoot"),"System32\\WindowsPowerShell\\v1.0\\powershell.exe");
                    String program="$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';$p=[Console]::ReadLine();$t=[Console]::ReadLine();New-Item -ItemType Junction -Path $p -Target $t | Out-Null";
                    Process junction=new ProcessBuilder(tool.toString(),"-NoLogo","-NoProfile","-NonInteractive","-Command",program).redirectErrorStream(true).redirectOutput(home.resolve("junction.log").toFile()).start();
                    try(OutputStream in=junction.getOutputStream()){in.write((parent+"\n"+replacement+"\n").getBytes(StandardCharsets.UTF_8));}
                    if(!junction.waitFor(20,TimeUnit.SECONDS)){junction.destroyForcibly();System.exit(2);}
                    if(junction.exitValue()!=0){System.out.println("NOT RUN junction creation unavailable");System.exit(2);}
                }else Files.createSymbolicLink(parent,replacement);
            }
            System.out.println("SWAP mode="+args[2]+" pid="+java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        }
    }
}
