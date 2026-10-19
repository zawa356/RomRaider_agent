package net.neristal.rrbridge.product.v1;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

/** A synthetic Remote drive reply with a real local NTFS fixture; never accesses SMB. */
public final class Phase6DriveBindingRedTest {
    public static void main(String[] args)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("NOT RUN Windows Remote+NTFS binding simulation");return;}
        File root=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-drive-remote-sim-")).toFile();
        WindowsDriveGate.Probe remote=new WindowsDriveGate.Probe(){public String query(String drive){return "4|ALIAS\n";}};
        boolean refused=false;
        try{
            try{DeploymentBinding.class.getMethod("safeLocal",File.class,WindowsDriveGate.Probe.class).invoke(null,root,remote);}
            catch(NoSuchMethodException baseline){DeploymentBinding.safe(root);}
        }catch(InvocationTargetException expected){if(expected.getCause() instanceof DeploymentBinding.Refusal)refused=true;else throw expected;}
        if(!refused)throw new AssertionError("Remote drive simulation with NTFS passed deployment local-path gate");
        System.out.println("PASS synthetic Remote with NTFS refused before path inspection");
    }
}
