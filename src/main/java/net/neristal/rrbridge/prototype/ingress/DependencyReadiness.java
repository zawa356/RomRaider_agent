package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.ArtifactProvenance;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/**
 * RLDR1 required-dependency readiness candidate (Phase X-D1, read-only, synthetic-only, default-off: reachable through this
 * Java API only; no CLI, launcher, reader, Sender or ACK path uses it).
 *
 * <p>For one RLOP1 launch profile and one RLDI2-inventoried install it answers the question RLDI2 leaves open (RLDI2 contract,
 * "Responsibility boundary"): does the profile's exact ordered class path consist only of files of this install, the
 * approved JAR first, followed by dependency JARs that are exactly the set the profile's reader needs (required pins
 * present, forbidden pins absent, optional groups complete), byte-identical to the inventory, during one bounded
 * inspection? The required sets are a compiled-in, code-derived policy (design: phase_xd1_required_dependency_readiness.md).
 * RLDI2 is run here, fresh; no stored inventory result is ever accepted.
 *
 * <p>A MATCHED answer is still {@code state=BLOCKED} with every authorization flag false. Process provenance, the runtime,
 * Windows ACLs, the writer and other copies on disk are separate gates and stay UNKNOWN / NOT_PROVEN here.
 */
public final class DependencyReadiness {
    public static final String VERSION="RLDR1",POLICY_ID="RLDR1-REQUIRED-SET-POLICY-1";
    public static final List<String> AXES=Collections.unmodifiableList(Arrays.asList("profile_policy","inventory","classpath","required_set","freshness","process_provenance","access_control","writer"));
    /** The axes that must all be MATCHED for MATCHED_NOT_AUTHORIZATION (in reason priority order). */
    static final List<String> GATING=Collections.unmodifiableList(Arrays.asList("freshness","profile_policy","inventory","classpath","required_set"));
    static final String SQLITE="sqlite-jdbc-3.46.1.3",SLF4J_API="slf4j-api-2.0.16",SLF4J_NOP="slf4j-nop-2.0.16",JNA="jna-5.14.0";

    /** Dependency rule of one RLOP1 reader. {@code blocked} != null: the profile is never evaluated to MATCHED. */
    static final class Rule {
        final Set<String> required,forbidden;final List<Set<String>> groups;final String blocked;
        Rule(String blocked,Set<String> required,Set<String> forbidden,List<Set<String>> groups){
            this.blocked=blocked;this.required=Collections.unmodifiableSet(new TreeSet<String>(required));this.forbidden=Collections.unmodifiableSet(new TreeSet<String>(forbidden));
            List<Set<String>> g=new ArrayList<Set<String>>();for(Set<String> s:groups)g.add(Collections.unmodifiableSet(new TreeSet<String>(s)));this.groups=Collections.unmodifiableList(g);
        }
        Set<String> allowed(){Set<String> a=new TreeSet<String>(required);for(Set<String> g:groups)a.addAll(g);return a;}
    }
    private static Set<String> set(String... s){return new TreeSet<String>(Arrays.asList(s));}
    /**
     * Code-derived policy (evidence: design §2). SQLite is reached by every reader through JDBC; sqlite-jdbc 3.46.1.3 falls
     * back to java.util.logging without SLF4J, so the SLF4J API/provider pair is optional but all-or-none; JNA is reached
     * only by the RLWR1 product reader. The test-only reader needs a test JAR, which is not an inventory role.
     */
    private static final Map<String,Rule> POLICY=validate(policy());
    private static Map<String,Rule> policy(){
        List<Set<String>> slf4j=Collections.singletonList(set(SLF4J_API,SLF4J_NOP));
        Map<String,Rule> m=new TreeMap<String,Rule>();
        m.put(OfflineCandidateEntry.READER_POSIX,new Rule(null,set(SQLITE),set(JNA),slf4j));
        m.put(OfflineCandidateEntry.READER_WINDOWS_V1_SYNTHETIC,new Rule(null,set(SQLITE,JNA),set(),slf4j));
        m.put(OfflineCandidateEntry.READER_WINDOWS_TEST,new Rule("TEST_ONLY_READER_PROFILE",set(),set(),Collections.<Set<String>>emptyList()));
        return m;
    }
    /** Structural rules; a violation stops class initialisation (fail closed). Every pin must be an RLDI2 compiled-in pin. */
    static Map<String,Rule> validate(Map<String,Rule> policy){
        Set<String> pins=new HashSet<String>();for(DistributionInventoryV2.Pin p:DistributionInventoryV2.pins())pins.add(p.id);
        for(Map.Entry<String,Rule> e:policy.entrySet()){
            if(!OfflineCandidateEntry.knownReader(e.getKey()))throw new IllegalStateException("RLDR1_POLICY_READER");
            Rule r=e.getValue();Set<String> seen=new HashSet<String>();
            List<Set<String>> all=new ArrayList<Set<String>>(r.groups);all.add(r.required);all.add(r.forbidden);
            for(Set<String> s:all)for(String id:s){if(!pins.contains(id))throw new IllegalStateException("RLDR1_POLICY_UNKNOWN_PIN");if(!seen.add(id))throw new IllegalStateException("RLDR1_POLICY_OVERLAP");}
            for(Set<String> g:r.groups)if(g.size()<2)throw new IllegalStateException("RLDR1_POLICY_GROUP");
            if(r.blocked!=null&&!(r.required.isEmpty()&&r.groups.isEmpty()))throw new IllegalStateException("RLDR1_POLICY_BLOCKED_RULE");
        }
        return Collections.unmodifiableMap(new TreeMap<String,Rule>(policy));
    }

    public static final class Axis {public final String status,code;Axis(String status,String code){this.status=status;this.code=code;}}
    public static final class Report {
        public final String readiness,reason,reader;public final Map<String,Axis> axes;public final List<String> requiredPins,observedPins;public final Map<String,Integer> findings;
        Report(String readiness,String reason,String reader,Map<String,Axis> axes,Collection<String> required,Collection<String> observed,Map<String,Integer> findings){
            this.readiness=readiness;this.reason=reason;this.reader=reader;this.axes=Collections.unmodifiableMap(new LinkedHashMap<String,Axis>(axes));
            requiredPins=Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(required)));observedPins=Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(observed)));
            this.findings=Collections.unmodifiableMap(new TreeMap<String,Integer>(findings));
        }
        public boolean matched(){return "MATCHED_NOT_AUTHORIZATION".equals(readiness);}
        public Axis axis(String name){return axes.get(name);}
        /** Fixed keys; codes, statuses and compiled-in pin ids only: no path, file name, hash, user or profile value. */
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"readiness_version\":\"").append(VERSION).append("\",\"policy\":\"").append(POLICY_ID)
                .append("\",\"state\":\"BLOCKED\",\"dependency_readiness\":").append(DistributionInventory.jsonString(readiness)).append(",\"reason\":").append(DistributionInventory.jsonString(reason))
                .append(",\"reader\":").append(DistributionInventory.jsonString(reader)).append(",\"axes\":{");
            boolean first=true;
            for(Map.Entry<String,Axis> e:axes.entrySet()){if(!first)b.append(',');first=false;
                b.append(DistributionInventory.jsonString(e.getKey())).append(":{\"status\":").append(DistributionInventory.jsonString(e.getValue().status)).append(",\"code\":").append(DistributionInventory.jsonString(e.getValue().code)).append('}');}
            b.append("},\"required_pins\":");list(b,requiredPins);b.append(",\"observed_pins\":");list(b,observedPins);b.append(",\"findings\":{");
            first=true;for(Map.Entry<String,Integer> e:findings.entrySet()){if(!first)b.append(',');first=false;b.append(DistributionInventory.jsonString(e.getKey())).append(':').append(e.getValue());}
            return b.append("},\"reader_adoption\":\"BLOCKED\",\"global_old_binary_exclusion\":\"NOT_PROVEN\",\"formal_ack\":\"DISABLED\",\"synthetic_only\":true,")
                .append("\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false}").toString();
        }
        private static void list(StringBuilder b,List<String> l){b.append('[');boolean first=true;for(String s:l){if(!first)b.append(',');first=false;b.append(DistributionInventory.jsonString(s));}b.append(']');}
    }

    /** Mutable state of one inspection; every axis starts UNKNOWN and only an explicit check moves it. */
    private static final class State {
        final Map<String,Axis> axes=new LinkedHashMap<String,Axis>();final Map<String,Integer> findings=new TreeMap<String,Integer>();
        final Set<String> required=new TreeSet<String>(),observed=new TreeSet<String>();String reader="UNKNOWN";
        State(){for(String a:AXES)axes.put(a,new Axis("UNKNOWN","NOT_EVALUATED"));
            axes.put("process_provenance",new Axis("UNKNOWN","NOT_INSPECTED"));axes.put("access_control",new Axis("UNKNOWN","NOT_INSPECTED"));axes.put("writer",new Axis("UNKNOWN","WRITER_UNPROVEN"));}
        void set(String axis,String status,String code){axes.put(axis,new Axis(status,code(code)));}
        void rest(String status,String code,String... axisNames){for(String a:axisNames)if("NOT_EVALUATED".equals(axes.get(a).code))set(a,status,code);}
        void add(String code){Integer n=findings.get(code);findings.put(code,n==null?1:n+1);}
        Report report(){
            boolean all=true;for(String a:GATING)all&="MATCHED".equals(axes.get(a).status);
            if(all)return new Report("MATCHED_NOT_AUTHORIZATION","DEPENDENCY_READINESS_MATCHED_NOT_AUTHORIZATION",reader,axes,required,observed,findings);
            String overall="UNKNOWN",reason=null;
            for(String status:new String[]{"MISMATCH","BLOCKED","UNKNOWN","NOT_RUN"})for(String a:GATING)if(reason==null&&status.equals(axes.get(a).status)){overall=status;reason=axes.get(a).code;}
            return new Report(overall,reason==null?"REVIEW_REQUIRED":reason,reader,axes,required,observed,findings);
        }
    }
    /** Only stable upper-case codes reach the report; anything else is collapsed (never echoed). */
    static String code(String c){return c!=null&&c.matches("[A-Z0-9_]{1,64}")?c:"CODE_INVALID";}

    public static Report inspect(boolean enable,File profile,File installRoot,File record){return inspect(enable,profile,installRoot,record,DistributionInventory.NO_HOOK);}
    /** {@code hook} stages (tests): PROFILE_READ, INVENTORY_DONE, CLASSPATH_HASHED. */
    public static Report inspect(boolean enable,File profile,File installRoot,File record,DistributionInventory.Hook hook){
        State s=new State();
        if(!enable){for(String a:GATING)s.set(a,"NOT_RUN","READINESS_DISABLED");
            return new Report("NOT_RUN","READINESS_DISABLED","UNKNOWN",s.axes,s.required,s.observed,s.findings);}
        try{return evaluate(s,profile,installRoot,record,hook);}
        catch(Exception failure){
            s.add("cause:"+OfflineCandidatePreflight.cause(failure));
            for(String a:GATING)if(!"MISMATCH".equals(s.axes.get(a).status))s.set(a,"UNKNOWN","READINESS_UNAVAILABLE");
            return s.report();
        }
    }

    private static Report evaluate(State s,File profile,File installRoot,File record,DistributionInventory.Hook hook)throws Exception {
        if(profile==null||installRoot==null||record==null||!profile.isAbsolute()||!installRoot.isAbsolute()||!record.isAbsolute()){
            s.set("profile_policy","UNKNOWN","INPUT_PATH_NOT_ABSOLUTE");s.rest("UNKNOWN","INPUT_PATH_NOT_ABSOLUTE","inventory","classpath","required_set","freshness");return s.report();
        }
        // ---- freshness start: both records are bound by their bytes and physical identity for the whole inspection.
        if(!Files.isRegularFile(profile.toPath(),LinkOption.NOFOLLOW_LINKS)){s.set("profile_policy","UNKNOWN","PROFILE_UNREADABLE");s.rest("UNKNOWN","PROFILE_NOT_MATCHED","inventory","classpath","required_set","freshness");return s.report();}
        final String profileSha=DeploymentTransition.fileSha(profile);
        Map<String,String> prof;
        try{prof=DeploymentBinding.readRecord(DeploymentBinding.safe(profile));}
        catch(DeploymentBinding.Refusal r){s.set("profile_policy","MISMATCH","PROFILE_"+r.getMessage());s.rest("UNKNOWN","PROFILE_NOT_MATCHED","inventory","classpath","required_set","freshness");return s.report();}
        final String profileId;
        try{profileId=DeploymentBinding.fileIdentity(profile);}catch(IOException unavailable){s.set("profile_policy","UNKNOWN","PROFILE_IDENTITY_UNAVAILABLE");s.rest("UNKNOWN","PROFILE_NOT_MATCHED","inventory","classpath","required_set","freshness");return s.report();}
        hook.at("PROFILE_READ");

        // ---- profile_policy: the static RLOP1 checks of OfflineCandidateEntry.verifyProfile (runtime facts are not ours).
        String policy=profileCode(prof,profileSha);Rule rule=null;
        if(OfflineCandidateEntry.knownReader(prof.get("reader")))s.reader=prof.get("reader");
        if(policy!=null)s.set("profile_policy","MISMATCH",policy);
        else{
            rule=POLICY.get(prof.get("reader"));
            if(rule==null)s.set("profile_policy","MISMATCH","READER_UNSUPPORTED");
            else if(rule.blocked!=null)s.set("profile_policy","BLOCKED",rule.blocked);
            else{s.set("profile_policy","MATCHED","PROFILE_POLICY_MATCHED");s.required.addAll(rule.required);}
        }
        if(!"MATCHED".equals(s.axes.get("profile_policy").status)&&!"BLOCKED".equals(s.axes.get("profile_policy").status)){
            s.rest("UNKNOWN","PROFILE_NOT_MATCHED","inventory","classpath","required_set");finalFreshness(s,profile,profileSha,profileId,record,null,null,Collections.<File,String[]>emptyMap());return s.report();
        }

        // ---- inventory: a fresh RLDI2 inspection; nothing stored is trusted.
        String recordSha,recordId;
        try{recordSha=DeploymentTransition.fileSha(record);}catch(Exception unreadable){s.set("inventory","UNKNOWN","RECORD_UNREADABLE");s.rest("UNKNOWN","INVENTORY_NOT_MATCHED","classpath","required_set","freshness");return s.report();}
        try{recordId=DeploymentBinding.fileIdentity(record);}catch(IOException unavailable){s.set("inventory","UNKNOWN","RECORD_IDENTITY_UNAVAILABLE");s.rest("UNKNOWN","INVENTORY_NOT_MATCHED","classpath","required_set","freshness");return s.report();}
        DistributionInventoryV2.Report inv=DistributionInventoryV2.inspect(true,installRoot,record);
        s.set("inventory",inv.matched()?"MATCHED":"MISMATCH".equals(inv.inventory)?"MISMATCH":"UNKNOWN",inv.reason);
        if("POSIX_OWNER_ONLY_WRITE".equals(inv.acl))s.set("access_control","MATCHED",inv.acl);else s.set("access_control","POSIX_MISMATCH".equals(inv.acl)?"MISMATCH":"UNKNOWN",inv.acl);
        hook.at("INVENTORY_DONE");
        if(!inv.matched()){s.rest("UNKNOWN","INVENTORY_NOT_MATCHED","classpath","required_set");finalFreshness(s,profile,profileSha,profileId,record,recordSha,recordId,Collections.<File,String[]>emptyMap());return s.report();}
        if(!same(record,recordSha,recordId)){s.set("freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS");s.rest("UNKNOWN","INVENTORY_NOT_MATCHED","classpath","required_set");return s.report();}
        Map<String,String[]> listed=listed(DeploymentBinding.readRecord(record.toPath()));

        // ---- classpath: exact ordered elements, all inside this install, inventoried, approved JAR first, exact dependency set.
        Map<File,String[]> bound=new LinkedHashMap<File,String[]>();// file -> {sha, identity} at hashing time
        String cpCode=classpath(s,prof,installRoot,listed,bound);
        if(cpCode!=null){
            s.set("classpath","MISMATCH",cpCode);s.rest("UNKNOWN","CLASSPATH_NOT_MATCHED","required_set");
            if("INPUT_CHANGED_DURING_READINESS".equals(cpCode)){s.set("classpath","UNKNOWN","INPUT_CHANGED_DURING_READINESS");s.set("freshness","MISMATCH",cpCode);}
            finalFreshness(s,profile,profileSha,profileId,record,recordSha,recordId,bound);return s.report();
        }
        s.set("classpath","MATCHED","CLASSPATH_MATCHED_INSTALL_ONLY");

        // ---- required_set: pins on the class path vs. the reader's compiled-in rule.
        if(rule!=null&&rule.blocked==null){
            List<String> codes=new ArrayList<String>();
            for(String id:rule.required)if(!s.observed.contains(id)){s.add("REQUIRED_PIN_MISSING");codes.add("REQUIRED_PIN_MISSING");}
            for(String id:rule.forbidden)if(s.observed.contains(id)){s.add("FORBIDDEN_PIN_PRESENT");codes.add("FORBIDDEN_PIN_PRESENT");}
            for(Set<String> g:rule.groups){Set<String> have=new TreeSet<String>(g);have.retainAll(s.observed);if(!have.isEmpty()&&!have.equals(g)){s.add("OPTIONAL_GROUP_INCOMPLETE");codes.add("OPTIONAL_GROUP_INCOMPLETE");}}
            Set<String> other=new TreeSet<String>(s.observed);other.removeAll(rule.allowed());other.removeAll(rule.forbidden);
            for(int i=0;i<other.size();i++){s.add("PIN_NOT_ALLOWED_FOR_PROFILE");codes.add("PIN_NOT_ALLOWED_FOR_PROFILE");}
            if(s.findings.containsKey("DEPENDENCY_NOT_PINNED"))codes.add("DEPENDENCY_NOT_PINNED");
            String first=null;
            for(String c:new String[]{"REQUIRED_PIN_MISSING","FORBIDDEN_PIN_PRESENT","OPTIONAL_GROUP_INCOMPLETE","PIN_NOT_ALLOWED_FOR_PROFILE","DEPENDENCY_NOT_PINNED"})if(first==null&&codes.contains(c))first=c;
            s.set("required_set",first==null?"MATCHED":"MISMATCH",first==null?"REQUIRED_SET_MATCHED":first);
        }else s.set("required_set","NOT_RUN","PROFILE_POLICY_BLOCKED");

        hook.at("CLASSPATH_HASHED");
        finalFreshness(s,profile,profileSha,profileId,record,recordSha,recordId,bound);
        return s.report();
    }

    /**
     * freshness end: profile, record and every hashed class-path JAR are unchanged in bytes AND physical identity (a
     * byte-identical replacement is a different file). A snapshot of this inspection only; nothing is held afterwards.
     */
    private static void finalFreshness(State s,File profile,String profileSha,String profileId,File record,String recordSha,String recordId,Map<File,String[]> bound){
        if("MISMATCH".equals(s.axes.get("freshness").status))return;
        boolean same;
        try{
            same=same(profile,profileSha,profileId)&&(recordSha==null||same(record,recordSha,recordId));
            for(Map.Entry<File,String[]> e:bound.entrySet())same&=same(e.getKey(),e.getValue()[0],e.getValue()[1]);
        }catch(Exception gone){same=false;}
        if(!same)s.set("freshness","MISMATCH","INPUT_CHANGED_DURING_READINESS");
        else if(recordSha==null||bound.isEmpty())s.set("freshness","UNKNOWN","NOTHING_BOUND");
        else s.set("freshness","MATCHED","CURRENT_INSPECTION_ONLY");
    }

    /** Bytes and physical identity both as bound; an unreadable file or unavailable identity throws (callers fail closed). */
    private static boolean same(File f,String sha,String id)throws Exception {return sha.equals(DeploymentTransition.fileSha(f))&&id.equals(DeploymentBinding.fileIdentity(f));}

    /**
     * Static RLOP1 checks in OfflineCandidateEntry.verifyProfile order; null when they pass. The generation format/range and
     * the production-path rule are RLOP1's own code (Profile parsing, requireNotProduction), not a copy that could drift.
     */
    static String profileCode(Map<String,String> record,String sha)throws Exception {
        if(!OfflineCandidateEntry.PROFILE_VERSION.equals(record.get("version")))return "PROFILE_VERSION";
        if(!"SYNTHETIC_OWNED_ONLY".equals(record.get("mode")))return "PROFILE_MODE_UNSUPPORTED";
        if(!OfflineCandidateEntry.MAIN.equals(record.get("main_class")))return "UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS";
        if(!OfflineCandidateEntry.knownReader(record.get("reader")))return "READER_UNSUPPORTED";
        for(String k:LAYOUT_KEYS)if(record.get("layout."+k)==null)return "PROFILE_FORMAT";
        // Strict field set (as verifyProfile): an extra field, e.g. a smuggled authorization flag, is refused, never ignored.
        Set<String> allowed=new HashSet<String>(Arrays.asList("version","mode","main_class","reader","profile_uuid","user","working_directory","data_root",
            "java_executable","classpath","owner_root","expected_generation"));
        for(String k:LAYOUT_KEYS)allowed.add("layout."+k);
        for(String k:record.keySet())if(!allowed.contains(k)&&!k.startsWith("artifact."))return "PROFILE_UNKNOWN_FIELD";
        for(String k:new String[]{"user","working_directory","data_root","java_executable","classpath","owner_root","expected_generation","profile_uuid"})if(record.get(k)==null)return "PROFILE_FORMAT";
        OfflineCandidateEntry.Profile p;
        try{p=new OfflineCandidateEntry.Profile(record,sha);}catch(OfflineCandidateEntry.Refusal r){return r.getMessage();}
        if(p.expectedGeneration<0)return "PROFILE_FORMAT";
        if(!record.get("owner_root").equals(record.get("layout.root")))return "OWNER_ROOT_MISMATCH";
        try{OfflineCandidateEntry.requireNotProduction(p);}catch(OfflineCandidateEntry.Refusal r){return r.getMessage();}
        if(!ArtifactProvenance.VERSION.equals(record.get("artifact.version")))return "PROVENANCE_VERSION";
        return null;
    }
    private static final String[] LAYOUT_KEYS={"root","legacy","output","session","manifest","ledger","device","vehicle","payload","manifest_session"};

    /** RLDI2 record rows by relative path: {sha256, role, pin}. The format was already validated by the RLDI2 inspection. */
    private static Map<String,String[]> listed(Map<String,String> rec)throws Exception {
        Map<String,String[]> out=new TreeMap<String,String[]>();int n=Integer.parseInt(rec.get("files"));
        for(int i=0;i<n;i++){String k=String.format(Locale.ROOT,"file.%04d",i);out.put(rec.get(k+".path"),new String[]{rec.get(k+".sha256"),rec.get(k+".role"),rec.get(k+".pin")});}
        return out;
    }

    /** Class-path checks; returns the first mismatch code or null. Fills {@code bound} with what was hashed and s.observed. */
    private static String classpath(State s,Map<String,String> prof,File installRoot,Map<String,String[]> listed,Map<File,String[]> bound)throws Exception {
        List<File> elements;try{elements=ArtifactProvenance.parse(prof.get("classpath"));}catch(ArtifactProvenance.Refusal r){return r.getMessage();}
        Path root=installRoot.toPath();
        // The root itself must be its own real path: an alias root would make "inside the install" meaningless.
        if(!root.equals(root.normalize())||!root.equals(root.toRealPath()))return "INSTALL_ROOT_NOT_CANONICAL";
        Set<String> seen=new HashSet<String>();
        for(int i=0;i<elements.size();i++){
            Path p=elements.get(i).toPath();
            if(!p.isAbsolute())return "CLASSPATH_NOT_ABSOLUTE";
            // Real path (links, junctions, 8.3 names and case resolved) must equal the written path: no alias reaches a JAR.
            Path real;try{real=p.toRealPath();}catch(IOException missing){return "CLASSPATH_ENTRY_MISSING";}
            if(!p.equals(p.normalize())||!real.toString().equals(p.toString()))return "CLASSPATH_PATH_NOT_CANONICAL";
            if(!p.startsWith(root)||p.equals(root))return "CLASSPATH_OUTSIDE_INSTALL";
            StringBuilder rel=new StringBuilder();for(Path part:root.relativize(p)){if(rel.length()>0)rel.append('/');rel.append(part.toString());}
            if(!seen.add(rel.toString()))return "CLASSPATH_DUPLICATE_ENTRY";
            String[] want=listed.get(rel.toString());
            if(want==null)return "CLASSPATH_NOT_INVENTORIED";
            if(!"approved_jar".equals(want[1])&&!"dependency_jar".equals(want[1]))return "CLASSPATH_ROLE_NOT_ALLOWED";
            if((i==0)!="approved_jar".equals(want[1]))return "APPROVED_JAR_NOT_FIRST";
            File f=p.toFile();String id=DeploymentBinding.fileIdentity(f),sha=DeploymentTransition.fileSha(f);bound.put(f,new String[]{sha,id});
            if(!want[0].equals(sha))return "INPUT_CHANGED_DURING_READINESS";
            if("dependency_jar".equals(want[1])){if("-".equals(want[2]))s.add("DEPENDENCY_NOT_PINNED");else s.observed.add(want[2]);}
        }
        // Exact set: a dependency in the install that the launch does not use is unexplained code (e.g. JNA next to a POSIX profile).
        for(Map.Entry<String,String[]> e:listed.entrySet())if("dependency_jar".equals(e.getValue()[1])&&!seen.contains(e.getKey()))return "INVENTORY_DEPENDENCY_UNUSED";
        // RLAP1: the pinned description (order, hashes, sizes, class sets) of exactly these files. The runtime keys are the
        // profile's own: the runtime is the launched JVM's business (process_provenance), not this inspection's.
        Map<String,String> expected=new TreeMap<String,String>();
        for(Map.Entry<String,String> e:prof.entrySet())if(e.getKey().startsWith("artifact."))expected.put(e.getKey().substring(9),e.getValue());
        try{
            Map<String,String> observed=ArtifactProvenance.describe(elements,OfflineCandidateEntry.MAIN);
            observed.put("java_specification",expected.get("java_specification"));observed.put("data_model",expected.get("data_model"));
            ArtifactProvenance.compare(expected,observed);
        }catch(ArtifactProvenance.Refusal r){return r.getMessage();}
        catch(RuntimeException malformed){return "PROVENANCE_FORMAT";}
        return null;
    }
    private DependencyReadiness(){}
}
