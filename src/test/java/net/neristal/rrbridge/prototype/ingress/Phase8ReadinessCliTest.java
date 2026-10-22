package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;

/**
 * Phase VIII-3: RLIR1 through the single entry ({@code --readiness}), validated against the rules of
 * docs/interfaces/integrated_readiness_v1.schema.json (re-implemented here without a JSON-Schema library, plus the
 * content_axes_complete consistency the schema cannot express). Packaged JARs as for Phase7EntryTest; synthetic only.
 */
public final class Phase8ReadinessCliTest {
    static int checks;
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;System.out.println("PASS "+reason);}

    // ---- minimal JSON parser (objects, arrays, strings without escapes beyond \\ \" , numbers, booleans, null).
    static final class Json {
        final String s;int i;Json(String s){this.s=s;}
        static Object parse(String text){Json j=new Json(text);Object v=j.value();j.ws();if(j.i!=text.length())throw new IllegalArgumentException("trailing");return v;}
        void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        Object value(){
            ws();char c=s.charAt(i);
            if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<String,Object>();ws();if(s.charAt(i)=='}'){i++;return m;}
                while(true){ws();String k=(String)value();ws();expect(':');if(m.put(k,value())!=null)throw new IllegalArgumentException("duplicate key "+k);ws();if(s.charAt(i)==','){i++;continue;}expect('}');return m;}}
            if(c=='['){i++;List<Object> l=new ArrayList<Object>();ws();if(s.charAt(i)==']'){i++;return l;}
                while(true){l.add(value());ws();if(s.charAt(i)==','){i++;continue;}expect(']');return l;}}
            if(c=='"'){i++;StringBuilder b=new StringBuilder();while(s.charAt(i)!='"'){char x=s.charAt(i++);if(x=='\\'){char e=s.charAt(i++);if(e!='"'&&e!='\\')throw new IllegalArgumentException("escape");b.append(e);}else b.append(x);}i++;return b.toString();}
            if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;}if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}if(s.startsWith("null",i)){i+=4;return null;}
            int st=i;while(i<s.length()&&"-0123456789".indexOf(s.charAt(i))>=0)i++;if(st==i)throw new IllegalArgumentException("value at "+st);return Long.parseLong(s.substring(st,i));
        }
        void expect(char c){if(s.charAt(i)!=c)throw new IllegalArgumentException("expected "+c+" at "+i);i++;}
    }
    static final List<String> AXES=Arrays.asList(IntegratedReadiness.AXES);
    static final List<String> CONTENT=AXES.subList(0,10);
    static final Set<String> STATUS=new HashSet<String>(Arrays.asList("PASS","MISMATCH","UNKNOWN","BLOCKED","NOT_RUN","DISABLED"));
    static final Set<String> TOP=new HashSet<String>(Arrays.asList("contract_version","readiness","state","reason","content_axes_complete","axes","counts","synthetic_only","launch_authorized","production_authorized","ack_eligible","automatic_recovery"));

    /** Returns null if valid, else the first violated rule. */
    @SuppressWarnings("unchecked")
    static String violation(String text){
        Object root;try{root=Json.parse(text);}catch(RuntimeException bad){return "NOT_JSON "+bad.getMessage();}
        if(!(root instanceof Map))return "NOT_OBJECT";Map<String,Object> m=(Map<String,Object>)root;
        if(!m.keySet().equals(TOP))return "TOP_KEYS "+m.keySet();
        if(!Long.valueOf(1).equals(m.get("contract_version"))||!"RLIR1".equals(m.get("readiness"))||!"BLOCKED".equals(m.get("state")))return "HEADER";
        if(!(m.get("reason") instanceof String)||!((String)m.get("reason")).matches("[A-Z0-9_]{2,80}"))return "REASON";
        if(!Boolean.TRUE.equals(m.get("synthetic_only")))return "SYNTHETIC_ONLY";
        for(String k:new String[]{"launch_authorized","production_authorized","ack_eligible","automatic_recovery"})if(!Boolean.FALSE.equals(m.get(k)))return "AUTHORIZATION_FLAG "+k;
        if(!(m.get("content_axes_complete") instanceof Boolean))return "CONTENT_COMPLETE_TYPE";
        if(!(m.get("axes") instanceof Map))return "AXES_TYPE";Map<String,Object> axes=(Map<String,Object>)m.get("axes");
        if(!axes.keySet().equals(new HashSet<String>(AXES)))return "AXES_KEYS "+axes.keySet();
        boolean allContent=true;
        for(String name:AXES){
            if(!(axes.get(name) instanceof Map))return "AXIS_TYPE "+name;Map<String,Object> a=(Map<String,Object>)axes.get(name);
            if(!a.keySet().equals(new HashSet<String>(Arrays.asList("status","code","next"))))return "AXIS_KEYS "+name;
            Object st=a.get("status");if(!STATUS.contains(st))return "AXIS_STATUS "+name;
            for(String k:new String[]{"code","next"})if(!(a.get(k) instanceof String)||!((String)a.get(k)).matches("[A-Z0-9_]{2,80}"))return "AXIS_"+k.toUpperCase(Locale.ROOT)+" "+name;
            if(Arrays.asList("writer_quiescence","process_provenance","directory_durability").contains(name)&&"PASS".equals(st))return "UNPROVABLE_AXIS_PASS "+name;
            if(Arrays.asList("formal_ack","production_send").contains(name)&&!"DISABLED".equals(st))return "DISABLED_AXIS "+name;
            if(CONTENT.contains(name)&&!"PASS".equals(st))allContent=false;
        }
        if(!m.get("content_axes_complete").equals(allContent))return "CONTENT_COMPLETE_INCONSISTENT";
        if(!(m.get("counts") instanceof Map))return "COUNTS_TYPE";
        for(Map.Entry<String,Object> e:((Map<String,Object>)m.get("counts")).entrySet())if(!e.getKey().matches("[a-z0-9_]{1,64}")||!(e.getValue() instanceof Long)||(Long)e.getValue()<0)return "COUNT "+e.getKey();
        return null;
    }
    /** The nested readiness object of an entry JSON line (the entry embeds it verbatim as a raw value). */
    static String embedded(String entryJson){
        int at=entryJson.indexOf("\"readiness\":{");if(at<0)return null;int start=at+12,depth=0;
        for(int i=start;i<entryJson.length();i++){char c=entryJson.charAt(i);if(c=='{')depth++;else if(c=='}'&&--depth==0)return entryJson.substring(start,i+1);}
        return null;
    }

    public static void main(String[] args)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        List<File> packaged=new ArrayList<File>();for(String a:args)packaged.add(new File(a).getCanonicalFile());
        check(!packaged.isEmpty(),"packaged JARs supplied");

        // ---- the validator itself rejects what the schema forbids.
        String sample=IntegratedReadiness.inspect(false,null,0,null,null,null,null,null,null,null,null,null).json();
        check(violation(sample)==null,"disabled readiness JSON is schema-valid "+violation(sample));
        check(violation(sample.replace("\"launch_authorized\":false","\"launch_authorized\":true"))!=null,"validator rejects launch_authorized=true");
        check(violation(sample.replace("\"state\":\"BLOCKED\"","\"state\":\"READY\""))!=null,"validator rejects a non-BLOCKED state");
        check(violation(sample.replaceFirst("\"writer_quiescence\":\\{\"status\":\"[A-Z_]+\"","\"writer_quiescence\":{\"status\":\"PASS\""))!=null,"validator rejects writer_quiescence PASS");
        check(violation(sample.replace("\"content_axes_complete\":false","\"content_axes_complete\":true"))!=null,"validator rejects inconsistent content_axes_complete");
        check(violation(sample.replaceFirst(",\"process_provenance\":\\{[^}]*\\}",""))!=null,"validator rejects a missing axis");

        // ---- through the entry: pinned profile, staged evidence created in an owned private directory.
        Phase7EntryTest.Deployment d=new Phase7EntryTest.Deployment("synthetic-phase8-readiness",packaged,true,0);
        Map<String,String> before=d.data();String outBefore=d.output();
        String[] base=d.args("--readiness");
        Phase7EntryTest.Result none=Phase7EntryTest.inProcess(new String[]{base[0],base[1],base[2],base[3],"-","-"},d.facts());
        check(none.exit==5&&none.json.contains("\"mode\":\"READINESS\"")&&none.json.contains("\"reason\":\"STAGED_EVIDENCE_MISSING\""),"no staged evidence: READINESS BLOCKED STAGED_EVIDENCE_MISSING exit5 ["+none.exit+"]");
        check(violation(embedded(none.json))==null,"entry readiness (no staging) is schema-valid "+violation(embedded(none.json)));
        Phase7EntryTest.safe(none,d,"readiness without staging");

        File dir=DeploymentBindingTest.privateDir(Files.createDirectory(d.f.home.resolve("staging"))).toFile();
        File vault=new File(dir,"vault.db"),rli2=new File(dir,"candidate.rlc2-integration.db");
        LegacyProvenanceClassifier.stageIsolated(true,true,d.f.legacy,Collections.singletonList(new LegacyProvenanceClassifier.Original(d.f.layout.session.toPath(),d.f.layout.ledger,d.f.layout.device,d.f.layout.vehicle)),d.f.env.reader(),vault,rli2,LegacyProvenanceClassifier.NO_FAULT);
        Map<String,String> stagedHashes=new TreeMap<String,String>();for(File x:new File[]{vault,rli2,LegacyProvenanceClassifier.marker(vault)})stagedHashes.put(x.getName(),DeploymentTransition.fileSha(x));
        String[] full={base[0],base[1],base[2],base[3],vault.getPath(),rli2.getPath()};
        Phase7EntryTest.Result in=Phase7EntryTest.inProcess(full,d.facts());
        String r=embedded(in.json);
        check(in.exit==5&&violation(r)==null,"entry readiness with staging is schema-valid, exit5 ["+in.exit+" "+OfflineCandidateEntry.field(in.json,"reason")+"] "+violation(r));
        check(r.contains("\"writer_quiescence\":{\"status\":\"BLOCKED\"")&&r.contains("\"formal_ack\":{\"status\":\"DISABLED\""),"writer stays BLOCKED, formal ACK DISABLED");
        Phase7EntryTest.safe(in,d,"readiness with staging");
        System.out.println("READINESS "+r);

        // ---- actual JVM from the pinned profile gives the same readiness object.
        Phase7EntryTest.Result proc=Phase7EntryTest.process(d,full);
        check(proc.exit==5&&r.equals(embedded(proc.json)),"actual entry JVM reports the identical readiness object");

        // ---- refusals: wrong arity, forged profile, tampered staged evidence (schema-valid MISMATCH, never repaired).
        check(Phase7EntryTest.inProcess(new String[]{base[0],base[1],base[2],base[3],"-"},d.facts()).exit==2,"wrong arity is EXPLICIT_MODE_REQUIRED");
        Phase7EntryTest.Deployment forged=Phase7EntryTest.forged(d,"main_class",ProductBridgeWorker.class.getName());
        String[] fa=forged.args("--readiness");Phase7EntryTest.Result fr=Phase7EntryTest.inProcess(new String[]{fa[0],fa[1],fa[2],fa[3],"-","-"},d.facts());
        check(fr.exit==5&&!fr.json.contains("\"readiness\":{"),"forged profile refused before any readiness ["+OfflineCandidateEntry.field(fr.json,"reason")+"]");
        Path copy=DeploymentBindingTest.privateDir(Files.createDirectory(d.f.home.resolve("staging-copy")));
        for(File x:new File[]{vault,LegacyProvenanceClassifier.marker(vault),rli2})Files.copy(x.toPath(),copy.resolve(x.getName()));
        LegacyProvenanceClassifierTest.sql(copy.resolve(rli2.getName()).toFile(),"INSERT INTO preview_profiles VALUES('phase8-extra','{}')");
        Phase7EntryTest.Result t=Phase7EntryTest.inProcess(new String[]{base[0],base[1],base[2],base[3],copy.resolve(vault.getName()).toString(),copy.resolve(rli2.getName()).toString()},d.facts());
        String tr=embedded(t.json);
        check(violation(tr)==null&&tr.contains("\"staging_generation\":{\"status\":\"MISMATCH\"")&&tr.contains("\"content_axes_complete\":false"),"tampered staged RLI2: schema-valid MISMATCH, not complete");

        // ---- read-only: data, output and staged evidence bytes unchanged.
        check(before.equals(d.data())&&outBefore.equals(d.output()),"readiness leaves original v1/ledger/sources and candidate output unchanged");
        Map<String,String> after=new TreeMap<String,String>();for(File x:new File[]{vault,rli2,LegacyProvenanceClassifier.marker(vault)})after.put(x.getName(),DeploymentTransition.fileSha(x));
        check(stagedHashes.equals(after),"staged evidence unchanged");
        System.out.println("Phase VIII readiness CLI checks="+checks);
    }
}
