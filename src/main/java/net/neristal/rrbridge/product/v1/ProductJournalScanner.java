package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.neristal.rrbridge.prototype.ingress.IngressJournalPrototype;
import net.neristal.rrbridge.prototype.ingress.JournalIdentityPrototype;

/**
 * EXPLICIT offline-only journal scanner. No writer/Logger hook, no retention,
 * no daemon thread. Journal readers currently require an exclusive file lock:
 * use ONLY after the journal writer is stopped/closed. Never run concurrently
 * with an ECU callback, journal writer or automatic rotation.
 */
public final class ProductJournalScanner {
    public static final class Result {
        public final int sources,importedLines,samples;
        Result(int sources,int lines,int samples){
            this.sources=sources;this.importedLines=lines;this.samples=samples;
        }
    }
    private static final int MAX_FILES=16;
    private final DurableSpool spool;
    private final IncrementalTsvBridge importer;
    private final File directory;
    private final String device,vehicle;

    public ProductJournalScanner(DurableSpool spool,String device,String vehicle,File directory)
            throws Exception {
        if(spool==null||directory==null||!directory.isDirectory()||
           Files.isSymbolicLink(directory.toPath()))
            throw new IllegalArgumentException("Existing non-symlink journal directory required");
        this.directory=directory.getCanonicalFile();
        this.spool=spool;this.device=device;this.vehicle=vehicle;
        this.importer=new IncrementalTsvBridge(spool,device,vehicle);
    }
    public Result scanOnce(int maxLinesPerSource)throws Exception {
        if(maxLinesPerSource<1||maxLinesPerSource>10000)
            throw new IllegalArgumentException("Invalid bounded scan limit");
        if(spool.ingressReviewReason()!=null)
            throw new IllegalStateException("Ingress is held for review");
        File[] listed=directory.listFiles();
        if(listed==null)throw new IllegalStateException("Unable to list journal directory");
        Arrays.sort(listed,new Comparator<File>(){
            public int compare(File a,File b){return a.getName().compareTo(b.getName());}
        });
        List<File> sources=new ArrayList<File>();
        Map<String,String> sourceIds=new HashMap<String,String>();
        for(File f:listed){
            String name=f.getName();
            if(Files.isSymbolicLink(f.toPath()) || !f.isFile()
                || !f.getCanonicalFile().getParentFile().equals(directory))
                throw new IllegalStateException("Unsafe journal directory entry");
            if(name.endsWith(".rlj")){
                if(!name.matches("[A-Za-z0-9_-]{1,96}\\.rlj"))
                    throw new IllegalStateException("Invalid journal filename");
                if(f.length()>IngressJournalPrototype.MAX_JOURNAL_BYTES)
                    throw new IllegalStateException("Journal exceeds bounded quota");
                sources.add(f);
            } else if(name.endsWith(".identity")){
                String journal=name.substring(0,name.length()-".identity".length());
                if(!journal.matches("[A-Za-z0-9_-]{1,96}\\.rlj")
                    || !new File(directory,journal).isFile())
                    throw new IllegalStateException("Orphan journal identity");
            } else {
                // Protect against accidentally using an old TSV or mixed
                // product DB directory. The source directory is dedicated.
                throw new IllegalStateException("Unrecognized entry in journal directory");
            }
        }
        if(sources.size()>MAX_FILES)
            throw new IllegalStateException("Journal source limit exceeded; operator review");

        // Validate ALL identities and all known-original continuity BEFORE
        // importing even one row; never repair an absent companion sidecar.
        for(File source:sources){
            File identity=new File(source.getAbsolutePath()+".identity");
            if(!identity.isFile()||Files.isSymbolicLink(identity.toPath()))
                throw new IllegalStateException("Missing or unsafe journal identity");
            String uuid=JournalIdentityPrototype.readExisting(source).sourceUuid();
            String prior=sourceIds.put(uuid,source.getCanonicalPath());
            if(prior!=null)
                throw new IllegalStateException("Duplicate source UUID");
        }
        for(DurableSpool.JournalImportCursor old:spool.journalImportedSources()){
            if(!old.device.equals(device)||!old.vehicle.equals(vehicle))
                throw new IllegalStateException("Journal cursor belongs to other device");
            if(!old.path.startsWith(directory.getPath()+File.separator)||
                !old.path.equals(sourceIds.get(old.uuid)))
                throw new IllegalStateException("Previously imported journal disappeared or moved");
        }
        int lines=0,samples=0;
        for(File source:sources){
            // Import is read-only on original frames, but the prototype
            // obtains an exclusive journal lock. If a live writer owns the
            // file the import MUST fail, not attempt to race.
            IncrementalTsvBridge.Result result=importer.importJournal(source,maxLinesPerSource);
            lines+=result.processedLines;
            samples+=result.observations;
        }
        return new Result(sources.size(),lines,samples);
    }
}
