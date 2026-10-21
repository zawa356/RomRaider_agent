package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import net.neristal.rrbridge.prototype.ingress.Phase8InventoryTest.Install;

/**
 * Phase IX-4 (R9-04): RLDI1 changes during inventory that keep size and mtime. Each fixture proves the change really
 * happened (bytes or physical identity differ afterwards) and then requires the inventory to stop with
 * INPUT_CHANGED_DURING_INVENTORY. Owned temp installs only. Before the fix the same-size cases reported MATCHED (RED).
 */
public final class Phase9InventoryRaceTest {
    static int checks,red;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    /** Expectation for the fixed code; with -Dphase9.expectRed=true a MATCHED result is counted as the reproduced defect. */
    static void stopped(DistributionInventory.Report r,boolean changed,String what){
        check(changed,what+": fixture really changed the file");
        if(Boolean.getBoolean("phase9.expectRed")&&r.matched()){red++;System.out.println("RED "+what+" -> MATCHED (defect reproduced)");return;}
        check("INPUT_CHANGED_DURING_INVENTORY".equals(r.reason)&&!r.matched()&&r.json().contains("\"state\":\"BLOCKED\""),what+" -> "+r.reason);
    }
    interface Change{void apply(Path file)throws Exception;}
    static DistributionInventory.Report during(final Install t,final String stage,final String rel,final Change c,final boolean[] done)throws Exception {
        return DistributionInventory.inspect(true,t.root.toFile(),t.record,new DistributionInventory.Hook(){public void at(String s)throws Exception{
            if((s.equals(stage)||s.equals(stage+":"+rel))&&!done[0]){done[0]=true;c.apply(t.root.resolve(rel));}}});
    }
    /** Rewrites one byte in the middle (keeps the ZIP directory intact) with the same length and restores the mtime. */
    static final Change SAME_SIZE=new Change(){public void apply(Path f)throws Exception{
        FileTime m=Files.getLastModifiedTime(f);byte[] b=Files.readAllBytes(f);int at=b.length/2;b[at]^=0x55;
        try(RandomAccessFile raf=new RandomAccessFile(f.toFile(),"rw")){raf.seek(at);raf.write(b[at]);}
        Files.setLastModifiedTime(f,m);}};
    /** Deletes and recreates the file at the same name with identical bytes and mtime (a different physical file). */
    static final Change REPLACE=new Change(){public void apply(Path f)throws Exception{
        FileTime m=Files.getLastModifiedTime(f);byte[] b=Files.readAllBytes(f);Path tmp=f.resolveSibling(f.getFileName()+".new");
        Files.write(tmp,b);Files.setLastModifiedTime(tmp,m);Files.delete(f);Files.move(tmp,f);Files.setLastModifiedTime(f,m);}};

    public static void main(String[] a)throws Exception {
        for(String stage:new String[]{"SCANNED","HASHED"})for(String rel:new String[]{"lib/romlogger-offline.jar","profiles/entry.rlop1"}){
            if(stage.equals("HASHED")&&(!rel.endsWith(".jar")||Boolean.getBoolean("phase9.expectRed")))continue;// HASHED hook stage exists only after the fix
            {Install t=new Install();byte[] before=Files.readAllBytes(t.root.resolve(rel));long size=before.length;FileTime m=Files.getLastModifiedTime(t.root.resolve(rel));boolean[] done={false};
             DistributionInventory.Report r=during(t,stage,rel,SAME_SIZE,done);
             Path f=t.root.resolve(rel);
             stopped(r,done[0]&&!Arrays.equals(before,Files.readAllBytes(f))&&Files.size(f)==size&&Files.getLastModifiedTime(f).equals(m),"same-size/same-mtime byte change of "+rel+" at "+stage);}
            {Install t=new Install();Path f=t.root.resolve(rel);String id=net.neristal.rrbridge.product.v1.DeploymentBinding.fileIdentity(f.toFile());byte[] before=Files.readAllBytes(f);boolean[] done={false};
             DistributionInventory.Report r=during(t,stage,rel,REPLACE,done);
             stopped(r,done[0]&&Arrays.equals(before,Files.readAllBytes(f))&&!id.equals(net.neristal.rrbridge.product.v1.DeploymentBinding.fileIdentity(f.toFile())),"byte-identical physical replacement of "+rel+" at "+stage);}
        }
        // Regressions: unchanged install still MATCHED; directory swapped for a junction/symlink stops.
        {Install t=new Install();DistributionInventory.Report r=t.inspect();check(r.matched(),"untouched install stays MATCHED (still BLOCKED) "+r.json());}
        {final Install t=new Install();final boolean[] done={false};
         DistributionInventory.Report r=DistributionInventory.inspect(true,t.root.toFile(),t.record,new DistributionInventory.Hook(){public void at(String s)throws Exception{
             if(s.equals("SCANNED")&&!done[0]){done[0]=true;Path lib=t.root.resolve("profiles"),old=t.root.getParent().resolve("profiles-real");Files.move(lib,old);
                 if(System.getProperty("os.name").startsWith("Windows")){Process p=new ProcessBuilder("cmd","/c","mklink","/J",lib.toString(),old.toString()).redirectErrorStream(true).redirectOutput(t.home.resolve("j.log").toFile()).start();if(p.waitFor()!=0)throw new IOException("junction fixture");}
                 else Files.createSymbolicLink(lib,old);}}});
         check(done[0]&&"INPUT_CHANGED_DURING_INVENTORY".equals(r.reason)&&!r.matched(),"directory swapped for a junction/symlink during inventory stops ["+r.reason+"]");}
        if(Boolean.getBoolean("phase9.expectRed")){System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected the pre-fix code to reproduce at least one defect");}
        System.out.println("Phase IX inventory race test passed "+checks+" checks"+(red>0?" with "+red+" RED reproductions":"")+"; Windows ACL UNKNOWN; global old-binary exclusion NOT_PROVEN");
    }
    private Phase9InventoryRaceTest(){}
}
