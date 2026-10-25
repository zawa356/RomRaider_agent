package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.*;

/**
 * RLDI2 distribution inventory candidate (Phase X-C1, read-only, synthetic-only, default-off: reachable through this Java
 * API only; no CLI, launcher or reader uses it).
 *
 * <p>Same checks as RLDI1 ({@link DistributionInventory}, unchanged), plus a fixed dependency policy for F-IX-1: the exact
 * third-party JARs the approved entry needs (sqlite-jdbc, slf4j, JNA) are pinned here by file name, SHA-256, size, entry
 * count and one manifest attribute, together with the exact findings their reviewed content produces. Only those findings,
 * only of three kinds ({@code UNAPPROVED_MAIN_METHOD}, {@code UNAPPROVED_MANIFEST_MAIN}, {@code VERSIONED_OR_META_CLASS}),
 * only for a {@code dependency_jar} whose record names that pin, are not counted; the observed set must equal the pin's
 * set. Every other finding stays. Every {@code dependency_jar} must be pinned. The trust root is this reviewed source:
 * the install record can only name a compiled-in pin, never create or widen one (design: rldi2_dependency_policy_design.md).
 *
 * <p>A MATCHED inventory is still only a snapshot of one directory: state BLOCKED, never launch/production/ACK, global
 * old-binary exclusion NOT_PROVEN, Windows ACLs not inspected.
 */
public final class DistributionInventoryV2 {
    public static final String VERSION="RLDI2",POLICY_ID="RLDI2-DEPENDENCY-POLICY-1";
    /** The only finding kinds a pin may list. Everything else (legacy entry, Class-Path/agent, nested archive, duplicates,
     *  manifest ambiguity, unreadable JAR, …) can never be an exception. */
    static final Set<String> EXCEPTABLE=Collections.unmodifiableSet(new TreeSet<String>(Arrays.asList("UNAPPROVED_MAIN_METHOD","UNAPPROVED_MANIFEST_MAIN","VERSIONED_OR_META_CLASS")));

    /** One audited third-party JAR. Exceptions are {@code KIND|subject} (class name, or entry name for versioned classes). */
    static final class Pin {
        final String id,file,sha256,attribute,value;final long size;final int entries;final Set<String> exceptions;
        Pin(String id,String file,String sha256,long size,int entries,String attribute,String value,String... exceptions){
            this.id=id;this.file=file;this.sha256=sha256;this.size=size;this.entries=entries;this.attribute=attribute;this.value=value;
            this.exceptions=Collections.unmodifiableSet(new TreeSet<String>(Arrays.asList(exceptions)));
        }
        String canonical(){
            StringBuilder b=new StringBuilder(id).append('\t').append(file).append('\t').append(sha256).append('\t').append(size).append('\t').append(entries)
                .append('\t').append(attribute).append('=').append(value);
            for(String e:exceptions)b.append('\t').append(e);return b.append('\n').toString();
        }
    }
    /**
     * Phase X-C audited pins (measured 2026-10-25 with the RLDI1 parser on the repository-pinned artifacts; reasons in the
     * design document §1). Changing this list is a reviewed code change; nothing at run time can add to it.
     */
    private static final List<Pin> PINS=validate(Arrays.asList(
        new Pin("sqlite-jdbc-3.46.1.3","sqlite-jdbc-3.46.1.3.jar","4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa",14127503L,214,"Bundle-Version","3.46.1.3",
            "UNAPPROVED_MAIN_METHOD|org.sqlite.util.OSInfo",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/module-info.class",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/org/sqlite/nativeimage/SqliteJdbcFeature.class",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/org/sqlite/nativeimage/SqliteJdbcFeature$1.class",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/org/sqlite/nativeimage/SqliteJdbcFeature$SqliteJdbcFeatureException.class"),
        new Pin("slf4j-api-2.0.16","slf4j-api-2.0.16.jar","a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a",69435L,71,"Bundle-Version","2.0.16",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/module-info.class"),
        new Pin("slf4j-nop-2.0.16","slf4j-nop-2.0.16.jar","deca6c04ed35515a0a911fa44c0e836bee92c0c59d2e8fa9bab8ffbc464a9ba7",4982L,17,"Bundle-Version","2.0.16",
            "VERSIONED_OR_META_CLASS|META-INF/versions/9/module-info.class"),
        new Pin("jna-5.14.0","jna-5.14.0.jar","34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6",1878533L,188,"Bundle-Version","5.14.0",
            "UNAPPROVED_MAIN_METHOD|com.sun.jna.Native",
            "UNAPPROVED_MANIFEST_MAIN|com.sun.jna.Native")));
    /** SHA-256 of the canonical pin text; an RLDI2 record must name exactly this policy. */
    public static final String POLICY_SHA256=digest(PINS);

    private static final String CLASS_NAME="[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*";
    /** Structural rules every pin list must satisfy; violation stops class initialisation (fail closed). */
    static List<Pin> validate(List<Pin> pins){
        if(pins==null||pins.isEmpty())throw new IllegalStateException("RLDI2_POLICY_EMPTY");
        Set<String> ids=new HashSet<String>(),files=new HashSet<String>(),shas=new HashSet<String>();
        Set<String> legacy=new HashSet<String>(Arrays.asList(DistributionInventory.LEGACY_ENTRIES));
        for(Pin p:pins){
            if(p.id==null||!p.id.matches("[a-z0-9][a-z0-9.-]*")||!ids.add(p.id))throw new IllegalStateException("RLDI2_POLICY_ID");
            if(p.file==null||!p.file.matches("[a-z0-9][a-z0-9.-]*\\.jar")||!files.add(p.file))throw new IllegalStateException("RLDI2_POLICY_FILE");
            if(p.sha256==null||!p.sha256.matches("[0-9a-f]{64}")||!shas.add(p.sha256))throw new IllegalStateException("RLDI2_POLICY_SHA");
            if(p.size<=0||p.size>DistributionInventory.MAX_FILE||p.entries<=0||p.entries>DistributionInventory.MAX_JAR_ENTRIES)throw new IllegalStateException("RLDI2_POLICY_SHAPE");
            if(p.attribute==null||!p.attribute.matches("[A-Za-z0-9-]+")||p.value==null||!p.value.matches("[A-Za-z0-9._-]+"))throw new IllegalStateException("RLDI2_POLICY_ATTRIBUTE");
            if(p.exceptions.isEmpty())throw new IllegalStateException("RLDI2_POLICY_NO_EXCEPTION");
            for(String x:p.exceptions){
                int bar=x.indexOf('|');if(bar<=0||bar!=x.lastIndexOf('|'))throw new IllegalStateException("RLDI2_POLICY_EXCEPTION_FORMAT");
                String kind=x.substring(0,bar),subject=x.substring(bar+1);
                if(!EXCEPTABLE.contains(kind))throw new IllegalStateException("RLDI2_POLICY_KIND_NOT_EXCEPTABLE");
                String cls;
                if("VERSIONED_OR_META_CLASS".equals(kind)){
                    if(!subject.matches("META-INF/versions/[1-9][0-9]*/[A-Za-z0-9_$/-]+\\.class"))throw new IllegalStateException("RLDI2_POLICY_VERSIONED_SUBJECT");
                    cls=versionedClass(subject);
                }else cls=subject;
                if(!cls.matches(CLASS_NAME)&&!"module-info".equals(cls))throw new IllegalStateException("RLDI2_POLICY_SUBJECT");
                // A first-party class or a known legacy entry can never be blessed, not even inside a third-party JAR.
                if(cls.startsWith("net.neristal.")||legacy.contains(cls)||OfflineCandidateEntry.MAIN.equals(cls))throw new IllegalStateException("RLDI2_POLICY_FIRST_PARTY_OR_LEGACY");
            }
        }
        return Collections.unmodifiableList(new ArrayList<Pin>(pins));
    }
    private static String digest(List<Pin> pins){
        List<Pin> sorted=new ArrayList<Pin>(pins);
        Collections.sort(sorted,new Comparator<Pin>(){public int compare(Pin x,Pin y){return x.id.compareTo(y.id);}});
        StringBuilder b=new StringBuilder(POLICY_ID).append('\n');for(Pin p:sorted)b.append(p.canonical());
        try{return DeploymentTransition.sha(b.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception noDigest){throw new IllegalStateException("RLDI2_POLICY_DIGEST_UNAVAILABLE");}
    }
    static Pin pinBySha(String sha){for(Pin p:PINS)if(p.sha256.equals(sha))return p;return null;}
    static List<Pin> pins(){return PINS;}
    /** {@code META-INF/versions/9/a/B.class} → {@code a.B}. */
    static String versionedClass(String entry){
        String rest=entry.substring("META-INF/versions/".length());rest=rest.substring(rest.indexOf('/')+1);
        return rest.substring(0,rest.length()-6).replace('/','.');
    }

    public static final class Report {
        public final String inventory,reason;public final Map<String,Integer> findings,exceptionsApplied;public final List<String> unapprovedMains;
        public final int files,pinnedDependencies;public final String acl;
        Report(String inventory,String reason,Map<String,Integer> findings,Map<String,Integer> applied,List<String> mains,int files,int pinned,String acl){
            this.inventory=inventory;this.reason=reason;this.findings=Collections.unmodifiableMap(new TreeMap<String,Integer>(findings));
            exceptionsApplied=Collections.unmodifiableMap(new TreeMap<String,Integer>(applied));
            unapprovedMains=Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(mains)));this.files=files;pinnedDependencies=pinned;this.acl=acl;
        }
        public boolean matched(){return "MATCHED".equals(inventory);}
        /** Codes, counts and class names only: no paths, file names or hashes. */
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":2,\"inventory_version\":\"").append(VERSION).append("\",\"dependency_policy\":\"").append(POLICY_ID)
                .append("\",\"state\":\"BLOCKED\",\"inventory\":\"").append(inventory).append("\",\"reason\":\"").append(reason).append("\",\"files\":").append(files)
                .append(",\"pinned_dependencies\":").append(pinnedDependencies).append(",\"findings\":");
            counts(b,findings);b.append(",\"policy_exceptions_applied\":");counts(b,exceptionsApplied);
            b.append(",\"unapproved_mains\":[");boolean first=true;for(String m:unapprovedMains){if(!first)b.append(',');first=false;b.append('"').append(m).append('"');}
            return b.append("],\"access_control\":\"").append(acl).append("\",\"global_old_binary_exclusion\":\"NOT_PROVEN\",\"synthetic_only\":true,")
                .append("\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false}").toString();
        }
        private static void counts(StringBuilder b,Map<String,Integer> m){
            b.append('{');boolean first=true;for(Map.Entry<String,Integer> e:m.entrySet()){if(!first)b.append(',');first=false;b.append('"').append(e.getKey()).append("\":").append(e.getValue());}b.append('}');
        }
    }

    /** Builds the RLDI2 record of a directory as it is (synthetic packaging step; not an approval). A dependency whose
     *  SHA-256 is a compiled-in pin is recorded with that pin id, anything else with "-". */
    public static Map<String,String> describe(File root,String approvedMain,Map<String,String> roles)throws Exception {
        Map<String,String> m=new TreeMap<String,String>();m.put("version",VERSION);m.put("mode","SYNTHETIC_ONLY");m.put("approved_main",approvedMain);
        m.put("policy",POLICY_ID);m.put("policy_sha256",POLICY_SHA256);
        List<String> names=new ArrayList<String>(roles.keySet());Collections.sort(names);m.put("files",String.valueOf(names.size()));
        for(int i=0;i<names.size();i++){
            String key=String.format(Locale.ROOT,"file.%04d",i);File f=new File(root,names.get(i).replace('/',File.separatorChar));String sha=DeploymentTransition.fileSha(f);
            Pin pin="dependency_jar".equals(roles.get(names.get(i)))?pinBySha(sha):null;
            m.put(key+".path",names.get(i));m.put(key+".role",roles.get(names.get(i)));m.put(key+".sha256",sha);m.put(key+".pin",pin==null?"-":pin.id);
        }
        return m;
    }

    public static Report inspect(boolean enable,File root,File record){return inspect(enable,root,record,DistributionInventory.NO_HOOK);}
    public static Report inspect(boolean enable,File root,File record,DistributionInventory.Hook hook){
        Map<String,Integer> findings=new TreeMap<String,Integer>(),applied=new TreeMap<String,Integer>();List<String> mains=new ArrayList<String>();
        if(!enable)return new Report("NOT_RUN","INVENTORY_DISABLED",findings,applied,mains,0,0,"UNKNOWN");
        int files=0,pinned=0;String acl="UNKNOWN";
        try{
            require(root!=null&&record!=null&&root.isAbsolute()&&record.isAbsolute(),"INVENTORY_PATH_NOT_ABSOLUTE");
            DeploymentBinding.safe(root);
            require(Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS),"DISTRIBUTION_ROOT_NOT_DIRECTORY");
            require(!record.toPath().toAbsolutePath().normalize().startsWith(root.toPath().toAbsolutePath().normalize()),"RECORD_INSIDE_DISTRIBUTION");
            String recordSha=DeploymentTransition.fileSha(record);
            Map<String,String> rec=DeploymentBinding.readRecord(record.toPath());
            require(VERSION.equals(rec.get("version")),"INVENTORY_VERSION");require("SYNTHETIC_ONLY".equals(rec.get("mode")),"INVENTORY_MODE");
            String approved=rec.get("approved_main");
            require(OfflineCandidateEntry.MAIN.equals(approved),"APPROVED_MAIN_NOT_THE_OFFLINE_ENTRY");
            require(POLICY_ID.equals(rec.get("policy"))&&POLICY_SHA256.equals(rec.get("policy_sha256")),"RLDI2_POLICY_MISMATCH");
            Map<String,String[]> listed=listed(rec);
            hook.at("RECORD_READ");

            final Path base=root.toPath();
            Walk walk=new Walk(base);Files.walkFileTree(base,EnumSet.noneOf(FileVisitOption.class),DistributionInventory.MAX_DEPTH+1,walk);
            final Map<String,BasicFileAttributes> tree=walk.tree;for(int i=0;i<walk.links;i++)add(findings,"LINK_OR_SPECIAL_IN_DISTRIBUTION");
            files=tree.size();
            for(String name:listed.keySet())if(!tree.containsKey(name))add(findings,"LISTED_FILE_MISSING");
            int approvedJars=0;boolean approvedMainFound=false;Map<String,String> classOwner=new HashMap<String,String>();Set<String> usedPins=new HashSet<String>();
            Map<String,String> pinnedSha=new HashMap<String,String>(),pinnedId=new HashMap<String,String>();
            for(Map.Entry<String,BasicFileAttributes> e:tree.entrySet()){
                String name=e.getKey();File f=base.resolve(name.replace('/',File.separatorChar)).toFile();String[] want=listed.get(name);
                String ext=name.lastIndexOf('.')>name.lastIndexOf('/')?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
                if(want==null){add(findings,DistributionInventory.LAUNCHER_EXT.contains(ext)?"UNLISTED_EXECUTABLE_OR_ARCHIVE":"UNLISTED_FILE");}
                else{
                    if(e.getValue().size()>DistributionInventory.MAX_FILE){add(findings,"FILE_BOUND");continue;}
                    pinnedId.put(name,identityOf(f));
                    String sha=DeploymentTransition.fileSha(f);pinnedSha.put(name,sha);
                    if(!want[0].equals(sha))add(findings,"FILE_HASH_MISMATCH");
                    boolean jarRole=want[1].endsWith("_jar");
                    if(jarRole!=ext.equals("jar")||("launcher".equals(want[1])!=(DistributionInventory.LAUNCHER_EXT.contains(ext)&&!ext.equals("jar"))))add(findings,"ROLE_DOES_NOT_MATCH_FILE");
                    if("approved_jar".equals(want[1]))approvedJars++;
                    if(!"-".equals(want[2])&&!"dependency_jar".equals(want[1]))add(findings,"RLDI2_PIN_NOT_APPLICABLE");
                }
                if(ext.equals("jar")){
                    boolean isApproved=want!=null&&"approved_jar".equals(want[1]);
                    if(e.getValue().size()>DistributionInventory.MAX_FILE){if(want==null)add(findings,"FILE_BOUND");continue;}
                    // Scanned bytes = hashed bytes (Phase IX review P1) and nothing is written (CI-5 P2), as RLDI1.
                    byte[] snapshot=readFile(f);String snapSha=DeploymentTransition.sha(snapshot);
                    if(pinnedSha.containsKey(name))require(pinnedSha.get(name).equals(snapSha),"INPUT_CHANGED_DURING_INVENTORY");
                    hook.at("HASHED:"+name);
                    Scan s=scanJar(snapshot,approved,isApproved,name,classOwner,findings);
                    approvedMainFound|=s.approvedMainFound;
                    // Dependency policy (F-IX-1). The pin is chosen by the scanned bytes' SHA-256, never by the record.
                    Pin bySha=pinBySha(snapSha),use=null;
                    boolean dependency=want!=null&&"dependency_jar".equals(want[1]);
                    if(bySha!=null&&!dependency)add(findings,"RLDI2_PIN_NOT_APPLICABLE");
                    if(dependency){
                        String base1=name.substring(name.lastIndexOf('/')+1);
                        if(bySha==null){add(findings,"RLDI2_DEPENDENCY_NOT_PINNED");if(!"-".equals(want[2]))add(findings,"RLDI2_PIN_MISMATCH");}
                        else if(!bySha.id.equals(want[2]))add(findings,"RLDI2_PIN_MISMATCH");
                        else if(!(bySha.file.equals(base1)&&bySha.size==snapshot.length&&bySha.entries==s.entries&&s.mainAttributes!=null&&bySha.value.equals(s.mainAttributes.getValue(bySha.attribute))))add(findings,"RLDI2_PIN_SHAPE_MISMATCH");
                        else if(!usedPins.add(bySha.id))add(findings,"RLDI2_PIN_REUSED");
                        else use=bySha;
                    }
                    Set<String> observed=new TreeSet<String>();
                    for(String[] r:s.raw){
                        String key=r[0]+"|"+r[1];
                        if(use!=null&&use.exceptions.contains(key)){observed.add(key);add(applied,r[0]);continue;}
                        add(findings,r[0]);if("UNAPPROVED_MAIN_METHOD".equals(r[0]))mains.add(r[1]);
                    }
                    if(use!=null){if(observed.equals(use.exceptions))pinned++;else add(findings,"RLDI2_POLICY_EXCEPTION_NOT_OBSERVED");}
                }
            }
            if(approvedJars!=1)add(findings,"APPROVED_JAR_COUNT");
            if(!approvedMainFound)add(findings,"APPROVED_MAIN_MISSING");
            acl=accessControl(base,tree.keySet(),findings);
            hook.at("SCANNED");
            require(recordSha.equals(DeploymentTransition.fileSha(record)),"INPUT_CHANGED_DURING_INVENTORY");
            for(Map.Entry<String,BasicFileAttributes> e:tree.entrySet()){
                BasicFileAttributes now;try{now=Files.readAttributes(base.resolve(e.getKey().replace('/',File.separatorChar)),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(IOException gone){throw new Refusal("INPUT_CHANGED_DURING_INVENTORY");}
                require(now.size()==e.getValue().size()&&now.lastModifiedTime().equals(e.getValue().lastModifiedTime())&&now.isRegularFile(),"INPUT_CHANGED_DURING_INVENTORY");
            }
            Walk again=new Walk(base);Files.walkFileTree(base,EnumSet.noneOf(FileVisitOption.class),DistributionInventory.MAX_DEPTH+1,again);
            require(again.tree.keySet().equals(tree.keySet())&&again.links==walk.links,"INPUT_CHANGED_DURING_INVENTORY");
            for(Map.Entry<String,String> e:pinnedSha.entrySet()){
                File f=base.resolve(e.getKey().replace('/',File.separatorChar)).toFile();
                String id;try{id=identityOf(f);}catch(IOException gone){throw new Refusal("INPUT_CHANGED_DURING_INVENTORY");}
                require(id.equals(pinnedId.get(e.getKey()))&&e.getValue().equals(DeploymentTransition.fileSha(f)),"INPUT_CHANGED_DURING_INVENTORY");
            }
            if(!findings.isEmpty())return new Report("MISMATCH",findings.keySet().iterator().next(),findings,applied,mains,files,pinned,acl);
            return new Report("MATCHED","INVENTORY_MATCHED_NOT_AUTHORIZATION",findings,applied,mains,files,pinned,acl);
        }catch(Exception failure){
            String code=failure instanceof Refusal||failure instanceof DistributionInventory.Refusal||failure instanceof DeploymentBinding.Refusal||failure instanceof DeploymentTransition.Refusal?failure.getMessage():"INVENTORY_UNAVAILABLE";
            if("INVENTORY_UNAVAILABLE".equals(code))findings.put("inventory_cause:"+OfflineCandidatePreflight.cause(failure),1);
            return new Report("UNAVAILABLE",code,findings,applied,mains,files,pinned,acl);
        }
    }

    public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
    private static void require(boolean ok,String code)throws Refusal {if(!ok)throw new Refusal(code);}
    private static void add(Map<String,Integer> m,String code){Integer n=m.get(code);m.put(code,n==null?1:n+1);}

    private static Map<String,String[]> listed(Map<String,String> rec)throws Refusal {
        int n;try{n=Integer.parseInt(rec.get("files"));}catch(Exception bad){throw new Refusal("INVENTORY_FORMAT");}
        require(n>=1&&n<=DistributionInventory.MAX_ENTRIES,"INVENTORY_FORMAT");
        require(rec.size()==6+4*n,"INVENTORY_FORMAT");
        Map<String,String[]> out=new TreeMap<String,String[]>();Set<String> roles=new HashSet<String>(Arrays.asList(DistributionInventory.ROLES));
        Set<String> pinIds=new HashSet<String>();pinIds.add("-");for(Pin p:PINS)pinIds.add(p.id);
        for(int i=0;i<n;i++){
            String key=String.format(Locale.ROOT,"file.%04d",i);String p=rec.get(key+".path"),sha=rec.get(key+".sha256"),role=rec.get(key+".role"),pin=rec.get(key+".pin");
            require(p!=null&&sha!=null&&role!=null&&pin!=null,"INVENTORY_FORMAT");
            require(p.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")&&!Arrays.asList(p.split("/")).contains("..")&&!Arrays.asList(p.split("/")).contains("."),"INVENTORY_PATH_INVALID");
            require(sha.matches("[0-9a-f]{64}"),"INVENTORY_FORMAT");require(roles.contains(role),"INVENTORY_ROLE_INVALID");
            // A record can only name a compiled-in pin (or none); an unknown id is a refusal, not a new policy.
            require(pinIds.contains(pin),"RLDI2_PIN_UNKNOWN");
            require(out.put(p,new String[]{sha,role,pin})==null,"INVENTORY_DUPLICATE_PATH");
        }
        return out;
    }

    /** Per-JAR scan result: raw findings as {kind, subject}, entry count and the (single) manifest's main attributes. */
    private static final class Scan {final List<String[]> raw=new ArrayList<String[]>();boolean approvedMainFound;int entries=-1;Attributes mainAttributes;}

    /**
     * RLDI1's JAR checks over the given bytes, with the subject of each finding kept so the dependency policy can match it
     * exactly. Stricter than RLDI1: classes under META-INF/versions/N/ are also checked as their effective class (main,
     * legacy entry, cross-JAR duplicate), and class ownership is keyed by the relative path.
     */
    private static Scan scanJar(byte[] jarBytes,String approved,boolean isApproved,String label,Map<String,String> owner,Map<String,Integer> findings)throws Exception {
        Scan s=new Scan();Set<String> legacy=new HashSet<String>(Arrays.asList(DistributionInventory.LEGACY_ENTRIES));
        List<DistributionInventory.ZipEntryView> entries;
        try{entries=DistributionInventory.ZipEntryView.read(jarBytes);}catch(java.util.zip.ZipException bad){s.raw.add(new String[]{"JAR_UNREADABLE",""});return s;}
        if(entries.size()>DistributionInventory.MAX_JAR_ENTRIES)throw new Refusal("JAR_ENTRY_BOUND");
        long declared=0;
        for(DistributionInventory.ZipEntryView e:entries){
            if(e.size>DistributionInventory.MAX_ENTRY_EXPANDED)throw new Refusal("JAR_ENTRY_EXPANSION_BOUND");
            declared+=e.size;if(declared>DistributionInventory.MAX_JAR_EXPANDED)throw new Refusal("JAR_TOTAL_EXPANSION_BOUND");
        }
        // Phase X-B: every entry verified with the same code as RLDI1 (CRC-32, exact DEFLATE end, sizes, empty directories).
        try{for(DistributionInventory.ZipEntryView e:entries)e.verify();}catch(java.util.zip.ZipException bad){s.raw.add(new String[]{"JAR_UNREADABLE",""});return s;}
        s.entries=entries.size();
        try{
            int manifests=0;for(DistributionInventory.ZipEntryView e:entries)if(e.name.equalsIgnoreCase("META-INF/MANIFEST.MF"))manifests++;
            if(manifests>1)s.raw.add(new String[]{"JAR_MANIFEST_AMBIGUOUS",""});
            for(DistributionInventory.ZipEntryView e:entries)if(e.name.equalsIgnoreCase("META-INF/MANIFEST.MF")){
                Manifest mf=new Manifest(new ByteArrayInputStream(e.data(DistributionInventory.MAX_CLASS)));
                if(manifests==1&&e.name.equals("META-INF/MANIFEST.MF"))s.mainAttributes=mf.getMainAttributes();
                for(Object k:mf.getMainAttributes().keySet()){String key=k.toString();
                    if(key.equalsIgnoreCase("Class-Path")||key.equalsIgnoreCase("Launcher-Agent-Class")||key.equalsIgnoreCase("Boot-Class-Path")||key.equalsIgnoreCase("Premain-Class")||key.equalsIgnoreCase("Agent-Class"))s.raw.add(new String[]{"JAR_MANIFEST_CLASSPATH_OR_AGENT",key});}
                String main=mf.getMainAttributes().getValue("Main-Class");
                if(main!=null&&!(isApproved&&approved.equals(main.trim())))s.raw.add(new String[]{"UNAPPROVED_MANIFEST_MAIN",main.trim()});
            }
            Set<String> local=new HashSet<String>();
            for(DistributionInventory.ZipEntryView e:entries){
                String name=e.name;
                if(!local.add(name)){s.raw.add(new String[]{"JAR_DUPLICATE_ENTRY",name});continue;}
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith(".jar")||lower.endsWith(".zip")||lower.endsWith(".war")||lower.endsWith(".ear"))s.raw.add(new String[]{"NESTED_ARCHIVE",name});
                if(!lower.endsWith(".class")||name.endsWith("/"))continue;
                String cls;boolean versioned=false;
                if(name.startsWith("META-INF/")){
                    s.raw.add(new String[]{"VERSIONED_OR_META_CLASS",name});
                    if(!name.matches("META-INF/versions/[1-9][0-9]*/.+\\.class"))continue;
                    cls=versionedClass(name);versioned=true;
                }else cls=name.substring(0,name.length()-6).replace('/','.');
                if(!cls.equals("module-info")){String prior=owner.get(cls);if(prior==null)owner.put(cls,label);else if(!prior.equals(label))add(findings,"DUPLICATE_CLASS");}
                if(legacy.contains(cls))s.raw.add(new String[]{"LEGACY_ENTRY_CLASS_PRESENT",cls});
                if(e.size>DistributionInventory.MAX_CLASS)throw new Refusal("CLASS_BOUND");
                if(DistributionInventory.declaresMain(e.data(DistributionInventory.MAX_CLASS))){
                    if(isApproved&&!versioned&&cls.equals(approved))s.approvedMainFound=true;
                    else s.raw.add(new String[]{"UNAPPROVED_MAIN_METHOD",cls});
                }
            }
        }catch(java.util.zip.ZipException bad){s.raw.add(new String[]{"JAR_UNREADABLE",""});}
        return s;
    }

    /** Collects regular files by relative name and counts anything else (links, junctions, devices) without following it. */
    private static final class Walk extends SimpleFileVisitor<Path> {
        final Path base;final Map<String,BasicFileAttributes> tree=new TreeMap<String,BasicFileAttributes>();int links;
        Walk(Path base){this.base=base;}
        public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{
            if(!d.equals(base)&&(a.isSymbolicLink()||a.isOther())){links++;return FileVisitResult.SKIP_SUBTREE;}
            if(base.relativize(d).getNameCount()>DistributionInventory.MAX_DEPTH)throw new Refusal("DISTRIBUTION_DEPTH_BOUND");return FileVisitResult.CONTINUE;
        }
        public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{
            if(tree.size()+links>=DistributionInventory.MAX_ENTRIES)throw new Refusal("DISTRIBUTION_ENTRY_BOUND");
            if(a.isSymbolicLink()||a.isOther()||!a.isRegularFile())links++;else tree.put(rel(base,f),a);
            return FileVisitResult.CONTINUE;
        }
        public FileVisitResult visitFileFailed(Path f,IOException e)throws IOException{throw new Refusal("DISTRIBUTION_UNREADABLE");}
    }
    private static String identityOf(File f)throws IOException {
        try{return DeploymentBinding.fileIdentity(f);}catch(DeploymentBinding.Refusal r){throw new Refusal("FILE_IDENTITY_"+r.getMessage());}
    }
    private static String rel(Path base,Path f){StringBuilder b=new StringBuilder();for(Path part:base.relativize(f)){if(b.length()>0)b.append('/');b.append(part.toString());}return b.toString();}
    private static byte[] readFile(File f)throws IOException {
        try(InputStream in=new FileInputStream(f)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>DistributionInventory.MAX_FILE)throw new Refusal("CLASS_BOUND");out.write(b,0,n);}return out.toByteArray();
        }
    }
    /** POSIX: nothing in the distribution may be writable by group/other or owned by another user. Windows ACLs: UNKNOWN. */
    private static String accessControl(Path base,Set<String> names,Map<String,Integer> findings)throws IOException {
        if(Files.getFileAttributeView(base,PosixFileAttributeView.class)==null)return "UNKNOWN_WINDOWS_ACL_NOT_INSPECTED";
        UserPrincipal me=Files.getOwner(base,LinkOption.NOFOLLOW_LINKS);boolean ok=true;
        List<Path> all=new ArrayList<Path>();all.add(base);for(String n:names)all.add(base.resolve(n));
        for(Path p:all){
            for(Path q=p;q!=null&&q.startsWith(base);q=q.getParent()){
                Set<PosixFilePermission> perms=Files.getPosixFilePermissions(q,LinkOption.NOFOLLOW_LINKS);
                if(perms.contains(PosixFilePermission.GROUP_WRITE)||perms.contains(PosixFilePermission.OTHERS_WRITE)){add(findings,"WRITABLE_BY_OTHERS");ok=false;break;}
                if(!me.equals(Files.getOwner(q,LinkOption.NOFOLLOW_LINKS))){add(findings,"OWNER_DIFFERS");ok=false;break;}
                if(q.equals(base))break;
            }
        }
        return ok?"POSIX_OWNER_ONLY_WRITE":"POSIX_MISMATCH";
    }
    private DistributionInventoryV2(){}
}
