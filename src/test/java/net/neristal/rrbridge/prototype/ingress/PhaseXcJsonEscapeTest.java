package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/**
 * Phase X-C review P2: RLDI1/RLDI2 report JSON must stay well-formed (RFC 8259) whatever class names a JAR carries. A class
 * name is taken from the ZIP entry name, and the JVM allows {@code "} and {@code \} in binary names, so a dependency JAR can
 * name a main class {@code x.In","launch_authorized":true,"j":"}. Every report is parsed by a strict parser here (no
 * duplicate keys, no raw control characters, only standard escapes), its key set and gate values are fixed, and the
 * reported main names must round-trip exactly. Existing fail-closed behaviour is kept: control characters in an entry name
 * still make the JAR unreadable. Owned temp directories only; nothing is launched.
 */
public final class PhaseXcJsonEscapeTest {
    static int checks,red;
    /** -Dphasexc.jsonExpectRed=true (code before the escape fix): failures are counted as RED and the run continues. */
    static final boolean RED=Boolean.getBoolean("phasexc.jsonExpectRed");
    static void check(boolean ok,String m){if(!ok){if(RED){red++;System.out.println("RED "+m);return;}throw new AssertionError(m);}checks++;System.out.println("PASS "+m);}
    static Set<Object> set(Object list){return list instanceof List?new TreeSet<Object>((List<?>)list):Collections.<Object>emptySet();}

    /** Strict RFC 8259 subset (objects, arrays, strings, integers, booleans, null); duplicate keys are an error. */
    static final class Strict {
        final String s;int i;Strict(String s){this.s=s;}
        static Object parse(String text){Strict j=new Strict(text);Object v=j.value();j.ws();if(j.i!=text.length())throw new IllegalArgumentException("trailing at "+j.i);return v;}
        void ws(){while(i<s.length()&&" \t\r\n".indexOf(s.charAt(i))>=0)i++;}
        char at(){if(i>=s.length())throw new IllegalArgumentException("end of input");return s.charAt(i);}
        Object value(){
            ws();char c=at();
            if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<String,Object>();ws();if(at()=='}'){i++;return m;}
                while(true){ws();if(at()!='"')throw new IllegalArgumentException("key at "+i);String k=(String)value();ws();expect(':');
                    if(m.containsKey(k))throw new IllegalArgumentException("duplicate key "+k);m.put(k,value());ws();if(at()==','){i++;continue;}expect('}');return m;}}
            if(c=='['){i++;List<Object> l=new ArrayList<Object>();ws();if(at()==']'){i++;return l;}
                while(true){l.add(value());ws();if(at()==','){i++;continue;}expect(']');return l;}}
            if(c=='"'){i++;StringBuilder b=new StringBuilder();
                while(true){char x=at();i++;
                    if(x=='"')return b.toString();
                    if(x<0x20)throw new IllegalArgumentException("raw control character at "+(i-1));
                    if(x!='\\'){b.append(x);continue;}
                    char e=at();i++;
                    switch(e){case '"':b.append('"');break;case '\\':b.append('\\');break;case '/':b.append('/');break;case 'b':b.append('\b');break;
                        case 'f':b.append('\f');break;case 'n':b.append('\n');break;case 'r':b.append('\r');break;case 't':b.append('\t');break;
                        case 'u':if(i+4>s.length()||!s.substring(i,i+4).matches("[0-9a-fA-F]{4}"))throw new IllegalArgumentException("bad \\u at "+i);b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;
                        default:throw new IllegalArgumentException("bad escape \\"+e);}
                }}
            if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;}if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}if(s.startsWith("null",i)){i+=4;return null;}
            int st=i;if(at()=='-')i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(st==i||s.charAt(i-1)=='-')throw new IllegalArgumentException("value at "+st);return Long.parseLong(s.substring(st,i));
        }
        void expect(char c){if(at()!=c)throw new IllegalArgumentException("expected "+c+" at "+i);i++;}
    }
    static final Set<String> V1_KEYS=new TreeSet<String>(Arrays.asList("contract_version","inventory_version","state","inventory","reason","files","findings",
        "unapproved_mains","access_control","global_old_binary_exclusion","synthetic_only","launch_authorized","production_authorized","ack_eligible"));
    static final Set<String> V2_KEYS=new TreeSet<String>(V1_KEYS);
    static{V2_KEYS.addAll(Arrays.asList("dependency_policy","pinned_dependencies","policy_exceptions_applied"));}
    static final Set<String> ENTRY_KEYS=new TreeSet<String>(Arrays.asList("contract_version","entry","mode","state","reason","inventory","synthetic_only",
        "launch_authorized","production_authorized","ack_eligible","formal_ack","automatic_recovery","writer_quiescence"));

    /** Parses strictly and checks the fixed shape and gates; returns the parsed object. */
    @SuppressWarnings("unchecked")
    static Map<String,Object> shape(String json,Set<String> keys,long contract,String what){
        Object root;String err=null;try{root=Strict.parse(json);}catch(RuntimeException bad){root=null;err=bad.getMessage();}
        check(root instanceof Map,what+": report is well-formed JSON"+(err==null?"":" ("+err+") "+json));
        Map<String,Object> m=root instanceof Map?(Map<String,Object>)root:new LinkedHashMap<String,Object>();
        check(new TreeSet<String>(m.keySet()).equals(keys),what+": exact key set (no injected key) "+m.keySet());
        check(Long.valueOf(contract).equals(m.get("contract_version"))&&"BLOCKED".equals(m.get("state"))&&Boolean.TRUE.equals(m.get("synthetic_only"))&&Boolean.FALSE.equals(m.get("launch_authorized"))
            &&Boolean.FALSE.equals(m.get("production_authorized"))&&Boolean.FALSE.equals(m.get("ack_eligible")),what+": gates closed (BLOCKED, launch/production/ACK false)");
        boolean ascii=true;for(int k=0;k<json.length();k++)if(json.charAt(k)<0x20||json.charAt(k)>0x7e)ascii=false;
        check(ascii,what+": output is printable ASCII (non-ASCII and controls are \\u-escaped)");
        return m;
    }

    public static void main(String[] a)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" os="+System.getProperty("os.name"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        File closure=new File(a[0]);check(closure.isFile(),"entry closure JAR built");
        List<String[]> deps=new ArrayList<String[]>();
        for(String[] d:PhaseXcF1ReproTest.DEPS)if(d[1].startsWith("slf4j-")){check(new File(d[0]).isFile()&&d[2].equals(DeploymentTransition.fileSha(new File(d[0]))),"real "+d[1]+" matches its repository pin");deps.add(d);}

        // 1. Escape helper: every UTF-16 code unit round-trips through the strict parser and is emitted as printable ASCII.
        {StringBuilder all=new StringBuilder();for(int c=0;c<=0xFFFF;c++)all.append((char)c);
         String q=DistributionInventory.jsonString(all.toString());Object back=Strict.parse(q);
         boolean ascii=true;for(int k=0;k<q.length();k++)if(q.charAt(k)<0x20||q.charAt(k)>0x7e)ascii=false;
         check(all.toString().equals(back)&&ascii,"jsonString: all 65536 code units (incl. \", \\, controls, DEL, U+2028/9, lone surrogates) round-trip as printable ASCII");
         check(DistributionInventory.jsonString("org.sqlite.util.OSInfo").equals("\"org.sqlite.util.OSInfo\""),"jsonString leaves ordinary class names byte-identical");}

        // 2. Reports built directly with hostile values (defence in depth: also names the ZIP reader would reject).
        {List<String> evil=Arrays.asList("a\"b","c\\d","e\u0000f\u001fg","h\u007fi","j k l","m\uD800n","o\",\"launch_authorized\":true,\"p\":\"");
         Map<String,Integer> f=new TreeMap<String,Integer>();f.put("UNAPPROVED_MAIN_METHOD",evil.size());
         DistributionInventory.Report r1=new DistributionInventory.Report("MISMATCH","FINDINGS",f,evil,3,"UNKNOWN");
         Map<String,Object> m1=shape(r1.json(),V1_KEYS,1,"RLDI1 report with hostile names (direct)");
         check(new TreeSet<String>(evil).equals(set(m1.get("unapproved_mains"))),"RLDI1 hostile names round-trip exactly (direct)");
         DistributionInventoryV2.Report r2=new DistributionInventoryV2.Report("MISMATCH","FINDINGS",f,new TreeMap<String,Integer>(),evil,3,0,"UNKNOWN");
         Map<String,Object> m2=shape(r2.json(),V2_KEYS,2,"RLDI2 report with hostile names (direct)");
         check(new TreeSet<String>(evil).equals(set(m2.get("unapproved_mains"))),"RLDI2 hostile names round-trip exactly (direct)");}

        // 3. Real installs: an unpinned dependency JAR whose main classes carry hostile (but ZIP-legal) entry names.
        byte[] mainClass=Phase8InventoryTest.classBytes(DeploymentTransitionCli.class);
        String[] names={"x/Q\"uote","x/Back\\slash","x/In\",\"launch_authorized\":true,\"j\":\"","x/Br]}{,","x/Del\u007f","x/Ls Ps ","x/日本語","x/Smile😀","x/Sol/idus"};
        Map<String,byte[]> entries=new LinkedHashMap<String,byte[]>();Set<String> expectV1=new TreeSet<String>();
        for(String n:names){entries.put(n+".class",mainClass);expectV1.add(n.replace('/','.'));}
        Set<String> expectV2=new TreeSet<String>(expectV1);
        entries.put("META-INF/versions/9/x/V\"9.class",mainClass);expectV2.add("x.V\"9");// RLDI2 checks a versioned class as its effective class
        List<Path> homes=new ArrayList<Path>();
        {Map<String,String> roles=new TreeMap<String,String>();Path root=PhaseXcF1ReproTest.install(closure,deps,roles);homes.add(root.getParent());
         Path tool=root.resolve("lib/tools.jar");Phase8InventoryTest.jar(tool,null,entries);roles.put("lib/tools.jar","dependency_jar");
         File rec1=root.getParent().resolve("install.rldi1").toFile(),rec2=root.getParent().resolve("install.rldi2").toFile();
         DeploymentBinding.createRecord(rec1.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
         DeploymentBinding.createRecord(rec2.toPath(),DeploymentBinding.encodeRecord(DistributionInventoryV2.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));

         DistributionInventory.Report v1=DistributionInventory.inspect(true,root.toFile(),rec1);
         check("MISMATCH".equals(v1.inventory)&&v1.findings.containsKey("UNAPPROVED_MAIN_METHOD")&&new TreeSet<String>(v1.unapprovedMains).equals(expectV1),
             "RLDI1: hostile-named mains still found and refused (MISMATCH UNAPPROVED_MAIN_METHOD) "+v1.findings);
         Map<String,Object> j1=shape(v1.json(),V1_KEYS,1,"RLDI1 report, real JAR with hostile entry names");
         check(expectV1.equals(set(j1.get("unapproved_mains")))&&"MISMATCH".equals(j1.get("inventory")),"RLDI1 JSON names round-trip exactly and say MISMATCH");

         DistributionInventoryV2.Report v2=DistributionInventoryV2.inspect(true,root.toFile(),rec2);
         check("MISMATCH".equals(v2.inventory)&&v2.findings.containsKey("RLDI2_DEPENDENCY_NOT_PINNED")&&v2.findings.containsKey("UNAPPROVED_MAIN_METHOD")&&new TreeSet<String>(v2.unapprovedMains).equals(expectV2),
             "RLDI2: hostile-named mains still found and refused (MISMATCH NOT_PINNED + UNAPPROVED_MAIN_METHOD) "+v2.findings+" "+v2.unapprovedMains.size());
         Map<String,Object> j2=shape(v2.json(),V2_KEYS,2,"RLDI2 report, real JAR with hostile entry names");
         check(expectV2.equals(set(j2.get("unapproved_mains")))&&"MISMATCH".equals(j2.get("inventory")),"RLDI2 JSON names round-trip exactly and say MISMATCH");

         OfflineCandidateEntry.Outcome o=OfflineCandidateEntry.inventory(new String[]{"--inventory",root.toString(),rec1.toString()});
         Map<String,Object> je=shape(o.json,ENTRY_KEYS,1,"OfflineCandidateEntry --inventory output");
         @SuppressWarnings("unchecked") Map<String,Object> inner=je.get("inventory") instanceof Map?(Map<String,Object>)je.get("inventory"):new LinkedHashMap<String,Object>();
         check(o.exit==5&&"INVENTORY".equals(je.get("mode"))&&"DISABLED".equals(je.get("formal_ack"))&&new TreeSet<String>(inner.keySet()).equals(V1_KEYS)
             &&Boolean.FALSE.equals(inner.get("launch_authorized"))&&expectV1.equals(set(inner.get("unapproved_mains"))),"--inventory: exit 5, nested RLDI1 report intact, names exact");}

        // 4. Existing fail-closed behaviour kept: a control character in an entry name makes the JAR unreadable (not "escaped and accepted").
        {Map<String,String> roles=new TreeMap<String,String>();Path root=PhaseXcF1ReproTest.install(closure,deps,roles);homes.add(root.getParent());
         Phase8InventoryTest.jar(root.resolve("lib/tab.jar"),null,Collections.singletonMap("x/Ta\tb.class",mainClass));roles.put("lib/tab.jar","dependency_jar");
         File rec1=root.getParent().resolve("install.rldi1").toFile(),rec2=root.getParent().resolve("install.rldi2").toFile();
         DeploymentBinding.createRecord(rec1.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
         DeploymentBinding.createRecord(rec2.toPath(),DeploymentBinding.encodeRecord(DistributionInventoryV2.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
         DistributionInventory.Report v1=DistributionInventory.inspect(true,root.toFile(),rec1);DistributionInventoryV2.Report v2=DistributionInventoryV2.inspect(true,root.toFile(),rec2);
         check("MISMATCH".equals(v1.inventory)&&v1.findings.containsKey("JAR_UNREADABLE")&&"MISMATCH".equals(v2.inventory)&&v2.findings.containsKey("JAR_UNREADABLE"),
             "control character in an entry name: RLDI1 and RLDI2 still JAR_UNREADABLE "+v1.findings+" "+v2.findings);
         shape(v1.json(),V1_KEYS,1,"RLDI1 report, control-character entry");shape(v2.json(),V2_KEYS,2,"RLDI2 report, control-character entry");}

        for(Path h:homes)Files.walkFileTree(h,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path f,java.nio.file.attribute.BasicFileAttributes x)throws IOException{Files.delete(f);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{Files.delete(d);return FileVisitResult.CONTINUE;}});
        if(RED){System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected the pre-fix code to emit broken JSON");return;}
        System.out.println("RLDI JSON escape test passed "+checks+" checks; launch/production/ACK remain disabled");
    }
}
