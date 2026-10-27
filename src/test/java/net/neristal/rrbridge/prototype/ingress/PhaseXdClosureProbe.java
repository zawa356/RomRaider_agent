package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Phase X-D1 evidence for the RLDR1 required-set table: which JARs does the entry's SQLite path really need? Starts child
 * JVMs with an exact class path (probe classes + chosen dependency JARs, no JVM options, CLASSPATH/JAVA_TOOL_OPTIONS
 * removed); each child opens an in-memory SQLite database through JDBC exactly as the product does
 * ({@code Class.forName("org.sqlite.JDBC")}, {@code jdbc:sqlite:}) and reports which logging backend was reachable.
 * Only in-memory databases; nothing is written outside the JVM's own temp extraction of the SQLite native library.
 *
 * Parent args: PROBE_CLASSES_DIR SQLITE_JAR SLF4J_API_JAR SLF4J_NOP_JAR JNA_JAR|-. Child arg: --child.
 */
public final class PhaseXdClosureProbe {
    static int checks;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}

    public static void main(String[] a)throws Exception {
        if(a.length==1&&"--child".equals(a[0])){child();return;}
        String probe=a[0],sqlite=a[1],api=a[2],nop=a[3],jna=a[4];
        Run base=run(probe,sqlite);
        check(base.exit==0&&base.out.contains("SQLITE_OK")&&base.out.contains("slf4j=absent"),"sqlite-jdbc alone: JDBC works, SLF4J absent (JUL fallback) -> SLF4J is not required "+base);
        Run apiOnly=run(probe,sqlite,api);
        check(apiOnly.exit==0&&apiOnly.out.contains("SQLITE_OK")&&apiOnly.out.contains("slf4j=present"),"sqlite + slf4j-api: works "+apiOnly);
        System.out.println("OBSERVED sqlite+slf4j-api stderr_bytes="+apiOnly.err.length()+" mentions_slf4j="+apiOnly.err.contains("SLF4J"));
        Run pair=run(probe,sqlite,api,nop);
        check(pair.exit==0&&pair.out.contains("SQLITE_OK")&&pair.out.contains("slf4j=present"),"sqlite + slf4j pair: works "+pair);
        System.out.println("OBSERVED sqlite+slf4j-api+nop stderr_bytes="+pair.err.length()+" mentions_slf4j="+pair.err.contains("SLF4J"));
        Run nopOnly=run(probe,sqlite,nop);
        check(nopOnly.exit==0&&nopOnly.out.contains("SQLITE_OK")&&nopOnly.out.contains("slf4j=absent"),"sqlite + slf4j-nop only: works, the provider is unreachable without the API "+nopOnly);
        Run none=run(probe);
        check(none.exit!=0&&none.out.contains("SQLITE_DRIVER_ABSENT"),"no sqlite-jdbc: the JDBC path fails -> sqlite-jdbc is required "+none);
        // -verbose:class (probe only, never the product) lists every loaded class on stdout in Java 8 and 17 formats.
        if(!"-".equals(jna)){Run withJna=run(true,probe,sqlite,jna);check(withJna.exit==0&&withJna.out.contains("SQLITE_OK")&&withJna.out.contains("org.sqlite.JDBC")&&!withJna.out.contains("com.sun.jna."),
            "sqlite + JNA on the class path: the SQLite path loads no com.sun.jna class (verbose class log)");}
        else System.out.println("NOT_RUN JNA closure probe (JNA JAR absent on this runner)");
        System.out.println("PHASE_XD_CLOSURE_PROBE_CHECKS "+checks);
    }

    static final class Run {
        final int exit;final String out,err;Run(int exit,String out,String err){this.exit=exit;this.out=out;this.err=err;}
        public String toString(){return "[exit "+exit+", out "+out.trim().replaceAll("\\s+"," ")+", stderr "+err.length()+"B]";}
    }
    static Run run(String probe,String... jars)throws Exception {return run(false,probe,jars);}
    static Run run(boolean verbose,String probe,String... jars)throws Exception {
        StringBuilder cp=new StringBuilder(probe);for(String j:jars)cp.append(File.pathSeparator).append(j);
        String java=new File(System.getProperty("java.home"),"bin"+File.separator+(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java")).getPath();
        List<String> cmd=new ArrayList<String>(Arrays.asList(java));if(verbose)cmd.add("-verbose:class");
        cmd.addAll(Arrays.asList("-cp",cp.toString(),PhaseXdClosureProbe.class.getName(),"--child"));
        ProcessBuilder b=new ProcessBuilder(cmd);
        for(String n:new String[]{"JAVA_TOOL_OPTIONS","_JAVA_OPTIONS","JDK_JAVA_OPTIONS","CLASSPATH"})b.environment().remove(n);
        Process p=b.start();p.getOutputStream().close();
        final ByteArrayOutputStream err=new ByteArrayOutputStream();final InputStream es=p.getErrorStream();
        Thread t=new Thread(){public void run(){try{copy(es,err);}catch(IOException ignored){}}};t.start();
        ByteArrayOutputStream out=new ByteArrayOutputStream();copy(p.getInputStream(),out);t.join(60000);
        int exit=p.waitFor();
        return new Run(exit,new String(out.toByteArray(),StandardCharsets.UTF_8),new String(err.toByteArray(),StandardCharsets.UTF_8));
    }
    static void copy(InputStream in,OutputStream out)throws IOException {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}

    /** Child: the product's SQLite access pattern on an in-memory database. */
    static void child(){
        try{Class.forName("org.sqlite.JDBC");}catch(ClassNotFoundException absent){System.out.println("SQLITE_DRIVER_ABSENT");System.exit(3);}
        try(Connection c=DriverManager.getConnection("jdbc:sqlite::memory:");Statement s=c.createStatement();ResultSet r=s.executeQuery("select 1")){
            if(!r.next()||r.getInt(1)!=1){System.out.println("SQLITE_WRONG_RESULT");System.exit(4);}
        }catch(Throwable t){System.out.println("SQLITE_FAILED "+t.getClass().getSimpleName());System.exit(4);}
        boolean slf4j;try{Class.forName("org.slf4j.Logger");slf4j=true;}catch(ClassNotFoundException absent){slf4j=false;}
        System.out.println("SQLITE_OK slf4j="+(slf4j?"present":"absent"));
    }
}
