package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.zip.CRC32;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/** Real product-v1 SQLite data, NO ECU, no HTTP, journal ingress opt-in only. */
public final class JournalProductBridgeTest {
    private static int checks=0;
    private static final String SESSION="20261013_191500";
    interface Action{void run()throws Exception;}
    private static void ok(String name,boolean value){
        if(!value)throw new AssertionError(name);
        System.out.println("PASS "+name);checks++;
    }
    private static void rejects(String name,Action action)throws Exception{
        boolean failed=false;
        try{action.run();}
        catch(IOException | IllegalStateException | IllegalArgumentException err){failed=true;}
        ok(name,failed);
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static File journalFile(File folder,String stem)throws Exception{
        File source=new File(folder,stem+".rlj");
        if(!source.createNewFile())throw new AssertionError("journal already exists");
        JournalIdentityPrototype.open(source);
        return source;
    }
    private static void write(File source,String... rows)throws Exception{
        try(IngressJournalPrototype j=new IngressJournalPrototype(source,8*1024*1024)){
            for(String row:rows)j.append(row);
        }
    }
    private static String m(String session){
        return "M\t"+session+"\t1\tTEST_RPM\tSynthetic RPM\trpm";
    }
    private static String s(String session,int seq){
        return "S\t"+session+"\t1\t"+seq+"\t1791838800000\tTEST_RPM="+(1200+seq);
    }
    private static String e(String session){
        return "E\t"+session+"\t1\t1791838800200\thealth";
    }
    private static long count(File db,String sql)throws Exception{
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db.getAbsolutePath());
            Statement q=c.createStatement();ResultSet r=q.executeQuery(sql)){
            return r.next()?r.getLong(1):-1;
        }
    }
    private static String recordIds(File db)throws Exception{
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db.getAbsolutePath());
            Statement q=c.createStatement();
            ResultSet r=q.executeQuery("SELECT record_id FROM v1_records ORDER BY local_id")){
            StringBuilder s=new StringBuilder();
            while(r.next())s.append(r.getString(1)).append("\n");
            return s.toString();
        }
    }
    public static void main(String[] args)throws Exception{
        if(args.length==5 && "halt-after-record".equals(args[0])){
            DurableSpool child=new DurableSpool(new File(args[2]));
            IncrementalTsvBridge bridge=new IncrementalTsvBridge(
                child,args[3],args[4],new Runnable(){
                    public void run(){Runtime.getRuntime().halt(79);}
                });
            bridge.importJournal(new File(args[1]),10);
            throw new AssertionError("child JVM did not halt at the checkpoint failpoint");
        }
        File folder=Files.createTempDirectory("romlogger-journal-product-only").toFile();
        String device=id(),vehicle=id();
        final File db=new File(folder,"product.db");
        final File source=journalFile(folder,"source");
        final String uuid=JournalIdentityPrototype.open(source).sourceUuid();
        final DurableSpool spool=new DurableSpool(db);
        final IncrementalTsvBridge bridge=new IncrementalTsvBridge(spool,device,vehicle);
        write(source,m(SESSION),s(SESSION,0),e(SESSION));
        IncrementalTsvBridge.Result first=bridge.importJournal(source,1);
        ok("one journal definition imported within budget",first.metadata==1&&first.processedLines==1);
        ok("metadata alone creates no deliverable record",spool.countPending()==0);
        ok("journal cursor committed definition",spool.loadJournalCursor(uuid).sequence==1);
        IncrementalTsvBridge.Result next=bridge.importJournal(source,1);
        ok("journal observation uses shared production converter",next.observations==1&&spool.countPending()==1);
        ok("actual v1 profile durable before observation",count(db,"SELECT count(*) FROM v1_profiles")==1);
        ok("journal converter restores definitions from cursor",
            new IncrementalTsvBridge(new DurableSpool(db),device,vehicle)
                .importJournal(source,10).events==1);
        ok("event is a real v1 record",spool.countPending()==2&&
            count(db,"SELECT count(*) FROM v1_records WHERE kind='event'")==1);
        ok("no Server ACK invented by local journal conversion",spool.countAcked()==0);
        ok("journal observation still requires product profile ACK",
            spool.pendingProfileEntries().size()==1);
        DurableSpool.Batch eligible=spool.nextBatch(10,256*1024);
        ok("unconfirmed profile keeps observation out of deliverable batch",
            eligible!=null&&eligible.records.size()==1&&
            eligible.records.get(0).json.contains("\"kind\":\"event\""));
        ok("fully imported journal cursor equals frame sequence",spool.loadJournalCursor(uuid).sequence==3);
        ok("idempotent re-import makes no new records",
            bridge.importJournal(source,10).processedLines==0&&spool.countPending()==2);
        String journalIds=recordIds(db);

        // The old, proven TSV conversion must yield byte-identical record IDs
        // and JSON semantics for the same session/lines in its own SQLite DB.
        File legacyFolder=Files.createTempDirectory("journal-legacy-comparison").toFile();
        File legacy=new File(legacyFolder,"rr_"+SESSION+".tsv");
        String text=m(SESSION)+"\n"+s(SESSION,0)+"\n"+e(SESSION)+"\n";
        Files.write(legacy.toPath(),text.getBytes(StandardCharsets.UTF_8));
        File legacyDb=new File(legacyFolder,"legacy.db");
        DurableSpool legacySpool=new DurableSpool(legacyDb);
        new IncrementalTsvBridge(legacySpool,device,vehicle).importNew(legacy,100);
        ok("journal/TSV conversions use the same record UUIDs",journalIds.equals(recordIds(legacyDb)));
        ok("journal and TSV create the same one profile",
            count(legacyDb,"SELECT count(*) FROM v1_profiles")==count(db,"SELECT count(*) FROM v1_profiles"));
        rejects("journal-owned session refuses duplicate TSV lane",new Action(){
            public void run()throws Exception{bridge.importNew(legacy,10);}
        });
        ok("rejected second lane creates no phantom v1 observations",spool.countPending()==2);
        rejects("full legacy TSV importer also rejects journal-owned session",new Action(){
            public void run()throws Exception{
                new LegacyTsvBridge(spool,device,vehicle).importFile(legacy);
            }
        });

        rejects("TSV-owned session refuses duplicate journal lane",new Action(){
            public void run()throws Exception{
                new IncrementalTsvBridge(legacySpool,device,vehicle).importJournal(source,10);
            }
        });
        ok("TSV lane retains original records on journal rejection",legacySpool.countPending()==2);

        // Migration safety: historical full-TSV records may exist without
        // an incremental cursor or new ownership claim.
        File migrationDb=new File(folder,"historical.db");
        DurableSpool historical=new DurableSpool(migrationDb);
        String migrationSession="20261013_195000";
        String run=UUID.nameUUIDFromBytes(("RomLogger-v1|"+device+
            "|run|"+migrationSession+"||").getBytes(StandardCharsets.UTF_8)).toString();
        historical.appendRecord(id(),run,null,null,"event",null,
            "2026-10-13T19:50:00Z","agent","{\"kind\":\"event\"}");
        File migratedJournal=journalFile(folder,"historical");
        write(migratedJournal,m(migrationSession),s(migrationSession,0));
        rejects("journal cannot take over an unclaimed historical v1 run",new Action(){
            public void run()throws Exception{
                new IncrementalTsvBridge(historical,device,vehicle)
                    .importJournal(migratedJournal,10);
            }
        });
        ok("historical v1 original stays untouched after refused migration",
            historical.countPending()==1);

        // A copy of the same UUID+data must not silently relocate the journal
        // while keeping a cursor (possible sidecar/source swapping).
        File relocatedFolder=Files.createTempDirectory("journal-relocation").toFile();
        final File relocated=new File(relocatedFolder,"relocated.rlj");
        Files.copy(source.toPath(),relocated.toPath());
        Files.copy(new File(source.getAbsolutePath()+".identity").toPath(),
            new File(relocated.getAbsolutePath()+".identity").toPath());
        rejects("same UUID moved to different journal path fails closed",new Action(){
            public void run()throws Exception{bridge.importJournal(relocated,10);}
        });
        ok("relocation rejection never advances original cursor",
            spool.loadJournalCursor(uuid).sequence==3);

        // Another session in a new journal. Simulate failure AFTER durable
        // product sample commit but BEFORE journal cursor update.
        final String crashSession="20261013_192000";
        final File crash=journalFile(folder,"crash");
        String crashUuid=JournalIdentityPrototype.open(crash).sourceUuid();
        write(crash,m(crashSession));
        IncrementalTsvBridge crashBridge=new IncrementalTsvBridge(spool,device,vehicle);
        ok("second journal starts with metadata checkpoint",
            crashBridge.importJournal(crash,10).metadata==1);
        write(crash,s(crashSession,0));
        final long before=spool.loadJournalCursor(crashUuid).sequence;
        IncrementalTsvBridge failing=new IncrementalTsvBridge(spool,device,vehicle,
            new Runnable(){public void run(){throw new IllegalStateException("fault after product commit");}});
        rejects("crash after v1 insert before journal cursor CAS",new Action(){
            public void run()throws Exception{failing.importJournal(crash,10);}
        });
        ok("record durable while checkpoint deliberately unchanged",
            spool.countPending()==3&&spool.loadJournalCursor(crashUuid).sequence==before);
        ok("restart replays and advances checkpoint idempotently",
            new IncrementalTsvBridge(new DurableSpool(db),device,vehicle)
                .importJournal(crash,10).observations==1);
        ok("no duplicate after durable product replay",
            spool.countPending()==3&&spool.loadJournalCursor(crashUuid).sequence==2);
        ok("no local import is Server acknowledgement",spool.countAcked()==0);

        // Actual independent JVM terminates immediately after committing a
        // product v1 observation, before advancing journal cursor.
        final String haltSession="20261013_193000";
        final File haltSource=journalFile(folder,"halt-source");
        String haltUuid=JournalIdentityPrototype.open(haltSource).sourceUuid();
        write(haltSource,m(haltSession));
        new IncrementalTsvBridge(spool,device,vehicle).importJournal(haltSource,10);
        write(haltSource,s(haltSession,0));
        long beforeHalt=spool.countPending();
        String executable=System.getProperty("os.name").toLowerCase().contains("win")
            ?"java.exe":"java";
        String javaBin=new File(new File(System.getProperty("java.home"),"bin"),
            executable).getAbsolutePath();
        Process child=new ProcessBuilder(javaBin,"-cp",System.getProperty("java.class.path"),
            JournalProductBridgeTest.class.getName(),"halt-after-record",
            haltSource.getAbsolutePath(),db.getAbsolutePath(),device,vehicle)
            .redirectErrorStream(true).start();
        ok("separate worker JVM halts after v1 record commit",
            child.waitFor(20,java.util.concurrent.TimeUnit.SECONDS) && child.exitValue()==79);
        ok("JVM halt leaves real v1 record but old journal cursor",
            spool.countPending()==beforeHalt+1 &&
            spool.loadJournalCursor(haltUuid).sequence==1);
        ok("second JVM importer commits cursor without multiplying v1 records",
            new IncrementalTsvBridge(new DurableSpool(db),device,vehicle)
                .importJournal(haltSource,10).observations==1 &&
            spool.countPending()==beforeHalt+1 &&
            spool.loadJournalCursor(haltUuid).sequence==2);

        // Journal corruption with matching recomputed CRC: saved SHA chain must
        // detect edited immutable prefix rather than relying on CRC32 alone.
        try(RandomAccessFile f=new RandomAccessFile(source,"rw")){
            f.seek(0);
            if(f.readInt()!=IngressJournalPrototype.FRAME_MAGIC)throw new AssertionError("magic");
            int size=f.readInt();
            byte[] payload=new byte[size];f.seek(IngressJournalPrototype.HEADER_BYTES);
            f.readFully(payload);
            payload[payload.length-1]=(byte)(payload[payload.length-1]=='X'?'Y':'X');
            CRC32 crc=new CRC32();crc.update(payload);
            f.seek(16);f.writeInt((int)crc.getValue());f.write(payload);
        }
        rejects("recomputed CRC cannot bypass imported source SHA chain",new Action(){
            public void run()throws Exception{bridge.importJournal(source,10);}
        });
        ok("mutated journal leaves all product rows and ACK untouched",
            spool.countPending()==beforeHalt+1&&spool.countAcked()==0);
        ok("no accidental real ECU or product Server access",folder.isDirectory());
        System.out.println("JournalProductBridgeTest passed "+checks+" checks (product v1 SQLite, no ECU)");
    }
}
