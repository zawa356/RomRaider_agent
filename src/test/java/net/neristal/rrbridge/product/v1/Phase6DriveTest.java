package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Injected Remote/Unknown/alias results never touch a real mapped drive. */
public final class Phase6DriveTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static WindowsDriveGate.Probe probe(final String reply){return new WindowsDriveGate.Probe(){public String query(String root){check(root.equals("Z:\\"),"probe receives uppercase drive root only");return reply;}};}
    public static void main(String[] args)throws Exception {
        if(args.length>0){if(args[0].equals("oversize")){for(int i=0;i<10000;i++)System.out.print('x');}else if(args[0].equals("timeout"))Thread.sleep(2000);else if(args[0].equals("nonzero"))System.exit(3);return;}
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        for(String reply:new String[]{"4|LOCAL\n","0|LOCAL\n","1|LOCAL\n","2|LOCAL\n","5|LOCAL\n","6|LOCAL\n","3|ALIAS\n","","3|LOCAL\n3|LOCAL\n","3|LOCAL","garbage\n"}){
            boolean refused=false;try{WindowsDriveGate.requireFixed("z:\\",probe(reply));}catch(IOException expected){refused=true;}
            check(refused,"nonfixed/unknown/alias/malformed result refused");
        }
        WindowsDriveGate.requireFixed("z:\\",probe("3|LOCAL\r\n"));check(true,"local fixed control");
        for(String root:new String[]{"Z:","Z:\\;Write-Output secret","\\\\host\\share\\","C:\\mount\\","C:\\x\n"}){
            boolean refused=false;try{WindowsDriveGate.requireFixed(root,new WindowsDriveGate.Probe(){public String query(String value){throw new AssertionError("unsafe root reached probe");}});}catch(IOException expected){refused=true;}
            check(refused,"invalid/injected root rejected before subprocess");
        }
        boolean refused=false;try{WindowsDriveGate.requireFixed("Z:\\",new WindowsDriveGate.Probe(){public String query(String root)throws IOException{throw new IOException("synthetic unavailable");}});}catch(DeploymentBinding.Refusal expected){refused=expected.getMessage().equals("DRIVE_TYPE_UNPROVEN");}check(refused,"probe unavailable fails closed");
        for(String mode:new String[]{"oversize","timeout","nonzero"}){
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),Phase6DriveTest.class.getName(),mode).redirectErrorStream(true).start();
            try{refused=false;try{WindowsDriveGate.reply(child,mode.equals("timeout")?100:5000,512);}catch(IOException expected){refused=true;}check(refused,"bounded subprocess "+mode);}
            finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
        }
        if(System.getProperty("os.name").startsWith("Windows")){
            Path fixture=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phase6-drive-"));
            System.out.println("DRIVE_REPLY "+WindowsDriveGate.SYSTEM.query(fixture.getRoot().toString()).trim());
            WindowsDriveGate.requireFixed(fixture.getRoot().toString(),WindowsDriveGate.SYSTEM);check(true,"actual existing fixed drive Win32 probe");
            DeploymentBinding.safe(fixture.toFile());check(true,"actual existing local NTFS safe path");
            DeploymentBinding.safeLocal(fixture.toFile(),WindowsDriveGate.SYSTEM);check(true,"deployment local path uses actual fixed-drive gate");
        }else System.out.println("NOT RUN Windows native drive probe (non-Windows host)");
        System.out.println("Phase VI drive checks="+checks);
    }
}
