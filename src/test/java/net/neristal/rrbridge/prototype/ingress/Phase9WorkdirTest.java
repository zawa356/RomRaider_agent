package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Deployment;
import net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.Result;
import static net.neristal.rrbridge.prototype.ingress.Phase7EntryTest.*;

/**
 * Phase IX-1 (R9-03): the pinned working directory disappearing between runs (observed at 95a4a30 as CreateProcess
 * error=267 in the x64 multi-session restart pass). The supervisor must refuse with a stable code before starting a child,
 * the test harness must name the cause instead of an opaque Win32 error, and nothing may be retried silently.
 * Owned synthetic fixture only; the work directory is moved aside and restored by this test, never anyone else's.
 */
public final class Phase9WorkdirTest {
    public static void main(String[] args)throws Exception {
        List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());
        check(!packaged.isEmpty(),"packaged JARs supplied");
        Deployment d=new Deployment("p9-workdir",packaged,true,0);
        check(new File(d.work,KEEP).isFile(),"fixture work directory carries a keep marker (not empty)");
        Result first=process(d,d.args("--synthetic-owned"));
        check(first.exit==0&&first.json.contains("STORED_SYNTHETIC_OWNED"),"baseline synthetic-owned run stores ["+first.exit+"]");
        Map<String,String> data=d.data();String out=d.output();

        Path held=d.work.toPath().resolveSibling("work-held-by-test");Files.move(d.work.toPath(),held);
        Result missing=inProcess(d.args("--synthetic-owned"),d.facts());
        refused("WORKING_DIRECTORY_MISSING",5,missing,d,"supervisor refuses a vanished working directory before any child");
        check(!missing.json.contains("\"child_started\":\"true\""),"no child started for a vanished working directory");
        check(data.equals(d.data())&&out.equals(d.output()),"vanished working directory leaves data and output unchanged");
        String harness;try{process(d,d.args("--synthetic-owned"));harness="started";}catch(AssertionError e){harness=e.getMessage();}
        check(harness.startsWith("FIXTURE_WORKDIR_MISSING"),"harness names a missing work directory instead of CreateProcess error=267 ["+harness+"]");

        Files.move(held,d.work.toPath());
        Result again=process(d,d.args("--synthetic-owned"));
        check(again.exit==0&&again.json.contains("\"records_added\":0")&&again.json.contains("\"receipts_added\":0"),"restored working directory: restart adds nothing");
        safe(again,d,"restored run");
        System.out.println("Phase IX workdir test passed "+checks+" checks; launch/production/ACK disabled");
    }
    private Phase9WorkdirTest(){}
}
