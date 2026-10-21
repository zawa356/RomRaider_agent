package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.prototype.ingress.Phase8InventoryTest.Install;

/**
 * CI-5 P2: the RLDI1 inventory is read-only. It must work with no writable temp directory and create no file anywhere
 * (before the fix it scanned a temp copy of each JAR). The inspection runs in a child JVM whose java.io.tmpdir does not
 * exist; the install tree must be byte-identical afterwards. Owned temp installs only.
 */
public final class Phase9InventoryNoWriteTest {
    static int checks;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static Map<String,String> tree(Path root)throws Exception {
        final Map<String,String> m=new TreeMap<String,String>();final Path base=root;
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,java.nio.file.attribute.BasicFileAttributes x){m.put(base.relativize(d).toString(),"dir");return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes x)throws IOException{
                try{m.put(base.relativize(f).toString(),DeploymentTransition.fileSha(f.toFile()));}catch(Exception e){throw new IOException(e);}return FileVisitResult.CONTINUE;}});
        return m;
    }
    static String child(Path root,File record,Path tmp)throws Exception {
        String exe=new File(new File(System.getProperty("java.home"),"bin"),System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process p=new ProcessBuilder(exe,"-Djava.io.tmpdir="+tmp,"-cp",System.getProperty("java.class.path"),Phase9InventoryNoWriteTest.class.getName(),"child",root.toString(),record.toString())
            .redirectErrorStream(true).start();
        ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))>0)o.write(b,0,n);}
        p.waitFor();String[] lines=new String(o.toByteArray(),StandardCharsets.UTF_8).trim().split("\r?\n");return lines[lines.length-1];
    }
    public static void main(String[] a)throws Exception {
        if(a.length==3&&a[0].equals("child")){System.out.println(DistributionInventory.inspect(true,new File(a[1]),new File(a[2])).json());return;}
        boolean red=Boolean.getBoolean("phase9.expectRed");
        Install t=new Install();
        Path missingTmp=t.home.resolve("no-such-temp-dir");
        Map<String,String> before=tree(t.home);
        String json=child(t.root,t.record,missingTmp);
        check(!Files.exists(missingTmp),"fixture: the child's java.io.tmpdir does not exist");
        if(red&&!json.contains("\"inventory\":\"MATCHED\"")){System.out.println("RED inventory needs a writable temp directory: "+json);System.out.println("RED_COUNT 1");return;}
        check(json.contains("\"inventory\":\"MATCHED\"")&&json.contains("\"state\":\"BLOCKED\""),"inventory MATCHED (still BLOCKED) without any writable temp directory: "+json);
        check(before.equals(tree(t.home)),"install, record and fixture tree unchanged (no file created or modified)");
        Install bad=new Install(Phase8InventoryTest.classes(OfflineCandidateEntry.class,DistributionInventory.class,DeploymentTransitionCli.class));
        String mm=child(bad.root,bad.record,missingTmp);
        check(mm.contains("\"inventory\":\"MISMATCH\"")&&mm.contains(DeploymentTransitionCli.class.getName()),"secondary main still found from the in-memory scan: "+mm);
        // A local header whose name differs from its central-directory name (the JVM reads by central directory) is refused.
        Install skew=new Install();Path jar=skew.root.resolve("lib/romlogger-offline.jar");byte[] z=Files.readAllBytes(jar);
        check(z[0]==0x50&&z[1]==0x4b&&z[2]==3&&z[3]==4,"fixture: JAR starts with a local header");
        int nameAt=30;z[nameAt]=(byte)(z[nameAt]=='x'?'y':'x');Files.write(jar,z);skew.pin();
        DistributionInventory.Report sk=DistributionInventory.inspect(true,skew.root.toFile(),skew.record);
        check(!sk.matched()&&sk.findings.containsKey("JAR_UNREADABLE"),"local/central name mismatch is JAR_UNREADABLE: "+sk.findings);
        if(red)throw new AssertionError("expected RED on the pre-fix code");
        System.out.println("Phase IX inventory no-write test passed "+checks+" checks");
    }
    private Phase9InventoryNoWriteTest(){}
}
