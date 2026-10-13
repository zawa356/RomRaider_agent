package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/** Real SQLite incremental importer tests without RomRaider or an ECU. */
public final class IncrementalTsvBridgeTest {
    static int checks;
    static final String SESSION="20261013_185000";
    static void ok(String name,boolean condition){
        if(!condition)throw new AssertionError(name);
        checks++;System.out.println("PASS "+name);
    }
    static String id(){return UUID.randomUUID().toString();}
    static String row(String kind,String part) {
        return kind+"\t"+SESSION+"\t1\t"+part+"\n";
    }
    static void append(File file,String text)throws Exception{
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
    }
    static void rejected(String name,Op op)throws Exception{
        boolean failed=false;
        try{op.run();}
        catch(IllegalArgumentException ex){failed=true;}
        catch(IllegalStateException ex){failed=true;}
        ok(name,failed);
    }
    interface Op{void run()throws Exception;}
    static long line(DurableSpool spool,File file)throws Exception{
        DurableSpool.ImportCursor cursor=spool.loadImportCursor(file.getCanonicalPath());
        return cursor==null?0:cursor.lineNumber;
    }
    static long offset(DurableSpool spool,File file)throws Exception{
        DurableSpool.ImportCursor cursor=spool.loadImportCursor(file.getCanonicalPath());
        return cursor==null?0:cursor.offsetBytes;
    }
    static long profileCount(File dbFile)throws Exception{
        try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+dbFile.getAbsolutePath());
            Statement q=db.createStatement();
            ResultSet rows=q.executeQuery("SELECT count(*) FROM v1_profiles")){
            return rows.next()?rows.getLong(1):-1;
        }
    }
    public static void main(String[] args)throws Exception{
        File folder=Files.createTempDirectory("v1-incremental-tsv-").toFile();
        File input=new File(folder,"rr_"+SESSION+".tsv");
        File dbfile=new File(folder,"state.db");
        String metadata=row("M","TEST_RPM\tSynthetic RPM\trpm")+
                        row("M","TEST_TPS\tSynthetic throttle\t%");
        Files.write(input.toPath(),metadata.getBytes(StandardCharsets.UTF_8));
        String device=id(),vehicle=id();
        DurableSpool spool=new DurableSpool(dbfile);
        IncrementalTsvBridge bridge=new IncrementalTsvBridge(spool,device,vehicle);
        IncrementalTsvBridge.Result first=bridge.importNew(input,1);
        ok("budget imports exactly one line",first.processedLines==1&&first.metadata==1);
        ok("no sample before metadata",spool.countPending()==0);
        ok("cursor at first LF",first.offsetBytes>0&&line(spool,input)==1);
        IncrementalTsvBridge.Result next=bridge.importNew(input,10);
        ok("second metadata recovered from cursor",next.metadata==1&&next.processedLines==1);
        ok("metadata checkpoint at complete input",offset(spool,input)==input.length());
        IncrementalTsvBridge.Result idle=bridge.importNew(input,10);
        ok("idle importer skips processed prefix",idle.processedLines==0&&
           idle.offsetBytes==input.length());

        append(input,row("S","0\t1791838800000\tTEST_RPM=1500,TEST_TPS=30")+
                     row("S","1\t1791838800100\tTEST_RPM=1600,TEST_TPS=32"));
        IncrementalTsvBridge.Result samples=bridge.importNew(input,1);
        ok("bounded sample import",samples.observations==1&&samples.processedLines==1);
        ok("profile saved before data",profileCount(dbfile)==1);
        ok("first observation durable",spool.countPending()==1);
        spool=new DurableSpool(dbfile);
        bridge=new IncrementalTsvBridge(spool,device,vehicle);
        IncrementalTsvBridge.Result continued=bridge.importNew(input,10);
        ok("restarted importer loads metadata without rescanning",continued.observations==1&&
           continued.processedLines==1);
        ok("exactly two unique samples",spool.countPending()==2);
        ok("cursor advanced to both lines",offset(spool,input)==input.length());

        // A crashed importer can commit the record and die before checkpoint.
        // Reopening must replay with same UUID, no duplicate and no sequence loss.
        DurableSpool.ImportCursor old=spool.loadImportCursor(input.getCanonicalPath());
        append(input,row("S","2\t1791838800200\tTEST_RPM=1700,TEST_TPS=34"));
        IncrementalTsvBridge.Result third=bridge.importNew(input,10);
        ok("third row committed and cursor advanced",third.observations==1&&spool.countPending()==3);
        // A duplicate full importer uses exactly the same ID algorithm.
        new LegacyTsvBridge(spool,device,vehicle).importFile(input);
        ok("legacy re-import after incremental commit is idempotent",spool.countPending()==3);
        IncrementalTsvBridge.Result afterFull=bridge.importNew(input,10);
        ok("incremental cursor not rewound by legacy replay",afterFull.processedLines==0);

        // Incomplete writer tail cannot be committed or raise a spurious event.
        append(input,"S\t"+SESSION+"\t1\t3\t1791838800300\tTEST_RPM=1800");
        long beforePartial=offset(spool,input);
        IncrementalTsvBridge.Result partial=bridge.importNew(input,10);
        ok("incomplete tail is quarantined",partial.incompleteTail&&partial.processedLines==0);
        ok("no advance before LF",offset(spool,input)==beforePartial&&spool.countPending()==3);
        append(input,",TEST_TPS=35\n");
        ok("completed tail appended once",bridge.importNew(input,10).observations==1&&spool.countPending()==4);
        append(input,row("E","1791838800450\tlegacy watchdog status"));
        ok("event persists after restart metadata restore",
           new IncrementalTsvBridge(new DurableSpool(dbfile),device,vehicle)
               .importNew(input,10).events==1&&spool.countPending()==5);
        ok("same source idle remains constant",bridge.importNew(input,10).processedLines==0);

        // Invalid row must never let cursor jump over it and erase data.
        append(input,row("S","4\t1791838800500\tTEST_RPM=NaN,TEST_TPS=44"));
        final long safe=offset(spool,input);
        final IncrementalTsvBridge fault=bridge;
        rejected("invalid sample blocks cursor",new Op(){public void run()throws Exception{
            fault.importNew(input,10);
        }});
        ok("failed sample leaves pending originals",spool.countPending()==5&&offset(spool,input)==safe);
        rejected("repeat failure cannot skip the bad row",new Op(){public void run()throws Exception{
            new IncrementalTsvBridge(new DurableSpool(dbfile),device,vehicle).importNew(input,10);
        }});
        ok("no phantom ACK",spool.countAcked()==0);
        // Rewriting only the not-yet-imported tail is permitted and retried.
        byte[] accepted=Files.readAllBytes(input.toPath());
        String revised=new String(accepted,StandardCharsets.UTF_8).replace(
            "TEST_RPM=NaN,TEST_TPS=44","TEST_RPM=1900,TEST_TPS=44");
        Files.write(input.toPath(),revised.getBytes(StandardCharsets.UTF_8));
        ok("fixed uncommitted tail is read",bridge.importNew(input,10).observations==1);
        ok("repaired line written exactly once",spool.countPending()==6);

        // In-place edit of previously imported content is a hard error.
        byte[] contents=Files.readAllBytes(input.toPath());
        String edit=new String(contents,StandardCharsets.UTF_8).replace(
            "Synthetic RPM","Synthetic ZPM");
        Files.write(input.toPath(),edit.getBytes(StandardCharsets.UTF_8));
        rejected("changed head fingerprint is rejected",new Op(){public void run()throws Exception{
            new IncrementalTsvBridge(new DurableSpool(dbfile),device,vehicle).importNew(input,10);
        }});
        ok("source edit cannot erase sqlite originals",spool.countPending()==6);
        // Truncation also refuses to jump over missing committed source.
        Files.write(input.toPath(),"".getBytes(StandardCharsets.UTF_8));
        rejected("truncated source is rejected",new Op(){public void run()throws Exception{
            new IncrementalTsvBridge(new DurableSpool(dbfile),device,vehicle).importNew(input,10);
        }});
        rejected("wrong-device source cannot reuse cursor",new Op(){public void run()throws Exception{
            new IncrementalTsvBridge(new DurableSpool(dbfile),id(),vehicle).importNew(input,10);
        }});
        ok("all unacknowledged records remain durable",spool.countPending()==6);
        // Hard crash exactly after SQLite sample commit but before cursor CAS:
        // replay must write zero additional rows and advance the checkpoint.
        File crashFolder=Files.createTempDirectory("incremental-cursor-crash-").toFile();
        File crashFile=new File(crashFolder,"rr_"+SESSION+".tsv");
        File crashDb=new File(crashFolder,"crash.db");
        Files.write(crashFile.toPath(),
                    row("M","TEST_RPM\tSynthetic RPM\trpm").getBytes(StandardCharsets.UTF_8));
        DurableSpool crashSpool=new DurableSpool(crashDb);
        final String crashDevice=id(), crashVehicle=id();
        new IncrementalTsvBridge(crashSpool,crashDevice,crashVehicle).importNew(crashFile,10);
        append(crashFile,row("S","0\t1791838800000\tTEST_RPM=1234"));
        final long atMetadata=offset(crashSpool,crashFile);
        IncrementalTsvBridge interrupted=new IncrementalTsvBridge(
            crashSpool,crashDevice,crashVehicle,new Runnable(){
                public void run(){throw new IllegalStateException("synthetic crash after SQLite commit");}
            });
        rejected("failure injected AFTER durable sample BEFORE checkpoint",new Op(){
            public void run()throws Exception{interrupted.importNew(crashFile,10);}
        });
        ok("source cursor not advanced by injected crash",offset(crashSpool,crashFile)==atMetadata);
        ok("record itself was committed despite crash",crashSpool.countPending()==1);
        DurableSpool afterPowerLoss=new DurableSpool(crashDb);
        ok("restart replays same record ID and advances cursor",
           new IncrementalTsvBridge(afterPowerLoss,crashDevice,crashVehicle)
             .importNew(crashFile,10).observations==1);
        ok("replayed record remains one unique original",afterPowerLoss.countPending()==1);
        ok("crash recovery moves checkpoint to EOF",offset(afterPowerLoss,crashFile)==crashFile.length());

        System.out.println("IncrementalTsvBridgeTest passed "+checks+
            " checks (synthetic growing TSV, real SQLite; no ECU)");
    }
}
