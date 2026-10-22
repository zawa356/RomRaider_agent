package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import static net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.*;

/**
 * Phase IX-4 §7.2: launcher-side injection against the pinned entry (read-only --diagnose, owned fixture). The JVM
 * honours JAVA_TOOL_OPTIONS/_JAVA_OPTIONS (and JDK_JAVA_OPTIONS on Java 9+) before any entry code runs; the entry must see
 * them as JVM input arguments and refuse (JVM_OPTIONS_REFUSED). CLASSPATH is ignored with an explicit -cp. A -D/-javaagent
 * style option on the command line is refused the same way. Never PASS for a runtime that ignores a variable: that is
 * reported as IGNORED_BY_RUNTIME with the result it produced.
 */
public final class Phase9LauncherEnvTest {
    static Phase7EntryTest.Result launch(Deployment d,Map<String,String> env,List<String> jvmOptions)throws Exception {
        Map<String,String> r=d.record();List<String> c=new ArrayList<String>();c.add(r.get("java_executable"));c.addAll(jvmOptions);
        c.addAll(Arrays.asList("-cp",r.get("classpath"),OfflineCandidateEntry.MAIN));c.addAll(Arrays.asList(d.args("--diagnose")));
        requireWork(d);
        ProcessBuilder b=new ProcessBuilder(c).directory(d.work).redirectErrorStream(true);
        for(String k:new String[]{"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH"})b.environment().remove(k);
        b.environment().putAll(env);
        Process p=b.start();p.getOutputStream().close();
        ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=p.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)o.write(buf,0,n);}
        check(p.waitFor(300,TimeUnit.SECONDS),"entry process finished");
        String[] lines=new String(o.toByteArray(),StandardCharsets.UTF_8).trim().split("\r?\n");
        return new Phase7EntryTest.Result(p.exitValue(),lines[lines.length-1]);
    }
    public static void main(String[] args)throws Exception {
        List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());
        Deployment d=new Deployment("synthetic-phase9-launcher",packaged,true,0);
        Map<String,String> data=d.data();String out=d.output();
        Phase7EntryTest.Result base=launch(d,Collections.<String,String>emptyMap(),Collections.<String>emptyList());
        check(base.exit==5&&base.json.contains("\"reason\":\"WRITER_UNPROVEN\""),"clean launcher: diagnose reaches preflight (BLOCKED WRITER_UNPROVEN)");
        boolean java9=!System.getProperty("java.specification.version").startsWith("1.");
        for(String var:new String[]{"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS"}){
            Phase7EntryTest.Result r=launch(d,Collections.singletonMap(var,"-Dphase9.injected=1"),Collections.<String>emptyList());
            if(var.equals("JDK_JAVA_OPTIONS")&&!java9){
                check(r.json.contains("\"reason\":\"WRITER_UNPROVEN\""),"JDK_JAVA_OPTIONS IGNORED_BY_RUNTIME on Java 8 (no option reached the JVM; result unchanged)");continue;
            }
            refused("JVM_OPTIONS_REFUSED",5,r,d,var+" injected option refused");
        }
        refused("JVM_OPTIONS_REFUSED",5,launch(d,Collections.<String,String>emptyMap(),Collections.singletonList("-Dphase9.injected=1")),d,"command-line -D option refused");
        refused("JVM_OPTIONS_REFUSED",5,launch(d,Collections.<String,String>emptyMap(),Collections.singletonList("-Xss2m")),d,"command-line -X option refused");
        Phase7EntryTest.Result cp=launch(d,Collections.singletonMap("CLASSPATH",d.f.home.toString()),Collections.<String>emptyList());
        check(cp.json.contains("\"reason\":\"WRITER_UNPROVEN\""),"CLASSPATH environment ignored with explicit -cp (result unchanged)");
        check(data.equals(d.data())&&out.equals(d.output()),"no launcher variant changed data or output");
        System.out.println("NOTE -javaagent is a JVM input argument like -D/-X above and is refused by the same JVM_OPTIONS_REFUSED rule (no agent JAR is built here)");
        System.out.println("Phase IX launcher env test passed "+checks+" checks; launch/production/ACK disabled");
    }
    private Phase9LauncherEnvTest(){}
}
