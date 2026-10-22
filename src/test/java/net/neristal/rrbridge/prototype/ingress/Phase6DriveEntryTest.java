package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;

/** Synthetic Remote on an owned C-drive layout with deliberately missing binding; never accesses SMB. */
public final class Phase6DriveEntryTest {
    static int passed,failed;
    static void check(boolean ok,String message){if(ok){passed++;System.out.println("PASS "+message);}else{failed++;System.out.println("FAIL "+message);}}
    public static void main(String[] args)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("NOT RUN Windows transition early-drive simulation");return;}
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        for(String entry:new String[]{"plan","commit"}){
            Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-early-drive-"));
            File root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("root"))).toFile(),session=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("session"))).toFile();
            File legacy=home.resolve("old.db").toFile(),out=home.resolve("out.rlc2-integration.db").toFile(),ledger=home.resolve("ledger.db").toFile(),manifest=new File(session,"rotation.manifest");
            for(File file:new File[]{legacy,out,ledger,manifest}){Files.write(file.toPath(),new byte[]{1});DeploymentBindingTest.privateFile(file.toPath());}
            final DeploymentBinding.Layout layout=new DeploymentBinding.Layout(root,legacy,out,session,manifest,ledger,Rlc2ProductAdapterTest.DEVICE,Rlc2ProductAdapterTest.VEHICLE,"synthetic-early-drive",UUID.randomUUID().toString());
            Map<String,String> before=Phase6PreflightTest.hashes(home);final int[] calls={0};
            WindowsDriveGate.Probe remote=new WindowsDriveGate.Probe(){public String query(String root){calls[0]++;return "4|ALIAS\n";}};
            String reason="ACCEPTED";
            try{
                if(entry.equals("plan")){
                    try{Method method=DeploymentTransition.class.getDeclaredMethod("planWithDriveProbe",boolean.class,boolean.class,DeploymentBinding.Layout.class,DeploymentBinding.Layout.class,Set.class,String.class,WatermarkV2Prototype.ReadOpener.class,WindowsDriveGate.Probe.class);method.invoke(null,true,true,layout,layout,Collections.emptySet(),"synthetic early gate",null,remote);}
                    catch(NoSuchMethodException baseline){DeploymentTransition.plan(true,true,layout,layout,Collections.<String>emptySet(),"synthetic early gate",null);}
                }else{
                    try{Method method=DeploymentTransition.class.getDeclaredMethod("commitWithDriveProbe",boolean.class,boolean.class,DeploymentBinding.Layout.class,DeploymentBinding.Layout.class,Set.class,String.class,String.class,WatermarkV2Prototype.ReadOpener.class,DeploymentTransition.Hook.class,WindowsDriveGate.Probe.class);method.invoke(null,true,true,layout,layout,Collections.emptySet(),"synthetic early gate","invalid-plan",null,DeploymentTransition.NO_FAULT,remote);}
                    catch(NoSuchMethodException baseline){DeploymentTransition.commitSynthetic(true,true,layout,layout,Collections.<String>emptySet(),"synthetic early gate","invalid-plan",null,DeploymentTransition.NO_FAULT);}
                }
            }catch(Exception expected){Throwable cause=expected instanceof InvocationTargetException?expected.getCause():expected;reason=cause.getMessage();}
            check("LOCAL_FIXED_DRIVE_REQUIRED".equals(reason),entry+" refuses Remote before missing-root evidence ["+reason+"]");
            check(calls[0]==1,entry+" queries distinct drive once before any binding access");
            check(before.equals(Phase6PreflightTest.hashes(home)),entry+" Remote refusal creates no Worker/Lane/Publication files");
        }
        System.out.println("Phase VI early-drive checks="+passed+" failed="+failed);if(failed>0)System.exit(1);
    }
}
