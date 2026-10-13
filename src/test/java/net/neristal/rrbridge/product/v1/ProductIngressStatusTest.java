package net.neristal.rrbridge.product.v1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/** Monitoring surface contract: separate JVM and real read-only SQLite. */
public final class ProductIngressStatusTest {
    private static int checks;
    private static void ok(String label,boolean yes){
        if(!yes)throw new AssertionError(label);
        checks++;System.out.println("PASS "+label);
    }
    private static final class Result{
        final int exit;final String output;
        Result(int exit,String output){this.exit=exit;this.output=output;}
    }
    private static Result run(File db,String... extra)throws Exception{
        File binary=new File(System.getProperty("java.home"),"bin/java");
        if(!binary.isFile())binary=new File(System.getProperty("java.home"),"bin/java.exe");
        java.util.List<String> args=new java.util.ArrayList<String>();
        args.add(binary.getAbsolutePath());
        args.add("-cp");
        args.add(System.getProperty("java.class.path"));
        args.add(ProductIngressStatus.class.getName());
        if(db!=null)args.add(db.getAbsolutePath());
        for(String arg:extra)args.add(arg);
        ProcessBuilder builder=new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        Process p=builder.start();
        if(!p.waitFor(15,TimeUnit.SECONDS)){
            p.destroyForcibly();throw new AssertionError("Status process hung");
        }
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        try(InputStream in=p.getInputStream()){
            int c;
            while((c=in.read())!=-1){
                if(body.size()>4096)throw new AssertionError("Status output too large");
                body.write(c);
            }
        }
        return new Result(p.exitValue(),new String(body.toByteArray(),StandardCharsets.UTF_8));
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("ingress-status-cli-real-sqlite").toFile();
        File database=new File(root,"product.sqlite");
        Result absent=run(database);
        ok("missing database fails without creating file",absent.exit==2&&!database.exists());
        Result noargs=run(null);
        ok("status CLI rejects missing argument",noargs.exit==2);
        DurableSpool spool=new DurableSpool(database);
        Result healthy=run(database);
        ok("existing database reports no review latch",healthy.exit==0&&
            healthy.output.contains("INGRESS_STATUS=NO_REVIEW_LATCH"));
        ok("status query does not write review latch",spool.ingressReviewReason()==null);
        spool.requireIngressReview("JOURNAL_PREFLIGHT");
        Result held=run(database);
        ok("read-only status reflects persistent review after JVM restart",
            held.exit==5&&held.output.contains("reason=JOURNAL_PREFLIGHT"));
        ok("status does not clear sticky review",spool.ingressReviewReason().equals("JOURNAL_PREFLIGHT"));
        Result again=run(database);
        ok("repeated status polling never resumes ingestion",again.exit==5);
        ok("monitoring output includes no database path",!held.output.contains(root.getPath()));
        File bogus=new File(root,"bogus.sqlite");
        byte[] raw="NOT A SQLITE DB".getBytes(StandardCharsets.UTF_8);
        Files.write(bogus.toPath(),raw);
        Result unreadable=run(bogus);
        ok("invalid database returns unknown rather than OK",unreadable.exit==6&&
            unreadable.output.contains("INGRESS_STATUS=UNKNOWN"));
        ok("invalid database remains unmodified",java.util.Arrays.equals(raw,Files.readAllBytes(bogus.toPath())));
        File unknown=new File(root,"unknown.sqlite");
        new DurableSpool(unknown);
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+unknown.getAbsolutePath());
            Statement stmt=db.createStatement()){
            stmt.execute("INSERT INTO v1_ingress_review_state(singleton,reason_code,created_at_ms)"+
                         " VALUES(1,'UNEXPECTED_REASON',0)");
        }
        ok("unknown persisted review reason causes monitoring fail-closed",run(unknown).exit==6);
        ok("no monitoring operation sends network or ECU commands",database.isFile());
        System.out.println("ProductIngressStatusTest passed "+checks+" checks (read-only SQLite, separate JVM)");
    }
}
