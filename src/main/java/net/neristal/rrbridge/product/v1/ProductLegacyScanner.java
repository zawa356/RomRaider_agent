package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Incremental read-only legacy files scanner for an opt-in product worker. */
public final class ProductLegacyScanner {
    public static final class Result {
        public final int sources, importedLines, samples;
        Result(int sources,int lines,int samples){
            this.sources=sources;this.importedLines=lines;this.samples=samples;
        }
    }
    private final DurableSpool spool;
    private final IncrementalTsvBridge importer;
    private final File directory;
    public ProductLegacyScanner(DurableSpool spool,String device,String vehicle,File directory)
            throws Exception {
        if(spool==null||directory==null||!directory.isDirectory())
            throw new IllegalArgumentException("Existing legacy spool directory required");
        this.spool=spool;this.importer=new IncrementalTsvBridge(spool,device,vehicle);
        this.directory=directory.getCanonicalFile();
    }
    public Result scanOnce(int maxLinesPerSource)throws Exception {
        if(maxLinesPerSource<1||maxLinesPerSource>10000)
            throw new IllegalArgumentException("Invalid scan limit");
        List<String> known=spool.importedSourcePaths();
        for(String path:known){
            File checked=new File(path);
            if(!checked.isFile()||Files.isSymbolicLink(checked.toPath())||
               !checked.getCanonicalFile().getParentFile().equals(directory))
                throw new IllegalStateException("Previously imported TSV disappeared or moved");
        }
        File[] sources=directory.listFiles((dir,name)->
            name.matches("rr_[A-Za-z0-9_-]+\\.tsv"));
        if(sources==null)throw new IllegalStateException("Cannot list legacy TSV");
        if(sources.length>64)
            throw new IllegalStateException("More than 64 legacy source files; review retention policy");
        Arrays.sort(sources,new Comparator<File>(){
            public int compare(File a,File b){return a.getName().compareTo(b.getName());}
        });
        int lines=0,samples=0;
        for(File f:sources) {
            if(Files.isSymbolicLink(f.toPath()))
                throw new IllegalStateException("Legacy TSV symlink is not allowed");
            if(!f.getCanonicalFile().getParentFile().equals(directory))
                throw new IllegalStateException("Legacy TSV outside designated directory");
            IncrementalTsvBridge.Result r=importer.importNew(f,maxLinesPerSource);
            lines+=r.processedLines;
            samples+=r.observations;
        }
        return new Result(sources.length,lines,samples);
    }
}
