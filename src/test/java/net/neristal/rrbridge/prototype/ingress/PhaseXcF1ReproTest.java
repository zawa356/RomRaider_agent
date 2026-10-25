package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.DeploymentBinding;
import net.neristal.rrbridge.product.v1.DeploymentBindingTest;

/**
 * Phase X-C1: exact reproduction of F-IX-1 with the REAL pinned dependency JARs, and the permanent "RLDI1 unchanged"
 * regression. A synthetic install = the real entry closure JAR (approved_jar) + sqlite-jdbc, slf4j-api, slf4j-nop and,
 * where present, JNA 5.14.0 (dependency_jar). RLDI1 must report exactly the measured third-party findings — this is the
 * intended fail-closed result, not a software defect. With -Dphasexc.expectRed=true (code before the RLDI2 candidate) the
 * missing RLDI2 contract is counted as the RED gap. Read-only inventory of owned temp directories; nothing is launched.
 *
 * <p>Args: closure JAR path. Dependency JARs: lib/*.jar and build/identity-deps/jna-5.14.0.jar (pinned, verified by the
 * fetch scripts and again here).
 */
public final class PhaseXcF1ReproTest {
    static int checks,red;static final boolean RED=Boolean.getBoolean("phasexc.expectRed");
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;System.out.println("PASS "+m);}

    /** Real dependency JARs: repository path, canonical (Maven) file name, pinned SHA-256. */
    static final String[][] DEPS={
        {"lib/sqlite-jdbc.jar","sqlite-jdbc-3.46.1.3.jar","4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa"},
        {"lib/slf4j-api.jar","slf4j-api-2.0.16.jar","a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a"},
        {"lib/slf4j-nop.jar","slf4j-nop-2.0.16.jar","deca6c04ed35515a0a911fa44c0e836bee92c0c59d2e8fa9bab8ffbc464a9ba7"},
        {"build/identity-deps/jna-5.14.0.jar","jna-5.14.0.jar","34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6"}};
    static boolean jnaPresent(){return new File(DEPS[3][0]).isFile();}

    /** Owned install: lib/romlogger-offline.jar (approved) + the given real dependencies under their canonical names. */
    static Path install(File closure,List<String[]> deps,Map<String,String> roles)throws Exception {
        Path home=DeploymentBindingTest.privateDir(Files.createTempDirectory("romlogger-phasexc-"));
        Path root=DeploymentBindingTest.privateDir(Files.createDirectory(home.resolve("install")));
        Files.createDirectories(root.resolve("lib"));
        Files.copy(closure.toPath(),root.resolve("lib/romlogger-offline.jar"));roles.put("lib/romlogger-offline.jar","approved_jar");
        for(String[] d:deps){Files.copy(Paths.get(d[0]),root.resolve("lib/"+d[1]));roles.put("lib/"+d[1],"dependency_jar");}
        return root;
    }
    static DistributionInventory.Report rldi1(Path root,Map<String,String> roles)throws Exception {
        File rec=root.getParent().resolve("install.rldi1").toFile();Files.deleteIfExists(rec.toPath());
        DeploymentBinding.createRecord(rec.toPath(),DeploymentBinding.encodeRecord(DistributionInventory.describe(root.toFile(),OfflineCandidateEntry.MAIN,roles)));
        return DistributionInventory.inspect(true,root.toFile(),rec);
    }
    static void safe(DistributionInventory.Report r,String what){
        String j=r.json();
        check(j.contains("\"inventory_version\":\"RLDI1\"")&&j.contains("\"state\":\"BLOCKED\"")&&j.contains("\"launch_authorized\":false")&&j.contains("\"production_authorized\":false")
            &&j.contains("\"ack_eligible\":false")&&j.contains("\"global_old_binary_exclusion\":\"NOT_PROVEN\"")&&j.contains("\"synthetic_only\":true")
            &&!j.contains("romlogger-phasexc-")&&!j.contains(".jar")&&!j.matches("(?s).*[0-9a-f]{64}.*"),what+": RLDI1 JSON keeps every gate closed and carries no path, file name or hash");
    }
    static Map<String,Integer> map(Object... kv){Map<String,Integer> m=new TreeMap<String,Integer>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],(Integer)kv[i+1]);return m;}

    public static void main(String[] a)throws Exception {
        System.out.println("RUNTIME "+System.getProperty("java.version")+" model="+System.getProperty("sun.arch.data.model")+" os="+System.getProperty("os.name"));
        String model=System.getenv("ROMLOGGER_EXPECT_DATA_MODEL");if(model!=null)check(model.equals(System.getProperty("sun.arch.data.model")),"actual JVM data model "+model);
        File closure=new File(a[0]);check(closure.isFile(),"entry closure JAR built");
        List<String[]> deps=new ArrayList<String[]>();
        for(String[] d:DEPS){if(!new File(d[0]).isFile()){System.out.println("NOT_RUN "+d[1]+" absent on this runner (JNA is fetched on Windows jobs only)");continue;}
            check(d[2].equals(DeploymentTransition.fileSha(new File(d[0]))),"real "+d[1]+" matches its repository pin");deps.add(d);}
        check(deps.size()>=3,"sqlite-jdbc and slf4j JARs present");

        // ---- 1. the approved JAR alone is RLDI1 MATCHED (Phase VIII/IX result unchanged).
        {Map<String,String> roles=new TreeMap<String,String>();Path root=install(closure,Collections.<String[]>emptyList(),roles);
         DistributionInventory.Report r=rldi1(root,roles);check(r.matched()&&r.findings.isEmpty(),"approved closure JAR alone: RLDI1 MATCHED "+r.findings);safe(r,"alone");}

        // ---- 2. each real dependency next to it: the exact third-party findings (measured, X-C0).
        Map<String,Map<String,Integer>> expected=new HashMap<String,Map<String,Integer>>();
        expected.put("sqlite-jdbc-3.46.1.3.jar",map("UNAPPROVED_MAIN_METHOD",1,"VERSIONED_OR_META_CLASS",4));
        expected.put("slf4j-api-2.0.16.jar",map("VERSIONED_OR_META_CLASS",1));
        expected.put("slf4j-nop-2.0.16.jar",map("VERSIONED_OR_META_CLASS",1));
        expected.put("jna-5.14.0.jar",map("UNAPPROVED_MAIN_METHOD",1,"UNAPPROVED_MANIFEST_MAIN",1));
        Map<String,List<String>> mains=new HashMap<String,List<String>>();
        mains.put("sqlite-jdbc-3.46.1.3.jar",Arrays.asList("org.sqlite.util.OSInfo"));mains.put("jna-5.14.0.jar",Arrays.asList("com.sun.jna.Native"));
        for(String[] d:deps){Map<String,String> roles=new TreeMap<String,String>();Path root=install(closure,Collections.singletonList(d),roles);
            DistributionInventory.Report r=rldi1(root,roles);
            List<String> m=mains.containsKey(d[1])?mains.get(d[1]):Collections.<String>emptyList();
            check("MISMATCH".equals(r.inventory)&&r.findings.equals(expected.get(d[1]))&&r.unapprovedMains.equals(m),
                "F-IX-1 per JAR: approved + "+d[1]+" -> RLDI1 MISMATCH with exactly "+expected.get(d[1])+" mains "+m+" (got "+r.findings+" "+r.unapprovedMains+")");
            safe(r,d[1]);}

        // ---- 3. the full real candidate: RLDI1 MISMATCH with exactly the sum (no other finding: no Class-Path/agent,
        //         nested archive, legacy entry, duplicate class, manifest ambiguity or JAR_UNREADABLE).
        {Map<String,String> roles=new TreeMap<String,String>();Path root=install(closure,deps,roles);
         DistributionInventory.Report r=rldi1(root,roles);
         Map<String,Integer> sum=jnaPresent()?map("UNAPPROVED_MAIN_METHOD",2,"UNAPPROVED_MANIFEST_MAIN",1,"VERSIONED_OR_META_CLASS",6):map("UNAPPROVED_MAIN_METHOD",1,"VERSIONED_OR_META_CLASS",6);
         List<String> m=jnaPresent()?Arrays.asList("com.sun.jna.Native","org.sqlite.util.OSInfo"):Arrays.asList("org.sqlite.util.OSInfo");
         check("MISMATCH".equals(r.inventory)&&r.findings.equals(sum)&&r.unapprovedMains.equals(m),
             "F-IX-1 reproduced: real candidate ("+deps.size()+" dependency JARs) is RLDI1 MISMATCH with exactly "+sum+" "+m+" (intended fail-closed, not relaxed)");
         safe(r,"full candidate");
         System.out.println("F-IX-1 RLDI1 JSON "+r.json());}

        // ---- 4. RLDI2 candidate presence.
        boolean v2;try{Class.forName("net.neristal.rrbridge.prototype.ingress.DistributionInventoryV2");v2=true;}catch(ClassNotFoundException absent){v2=false;}
        if(RED){
            if(!v2){red++;System.out.println("RED no RLDI2 contract: a real install with its required dependencies has no path to MATCHED (F-IX-1 gap reproduced)");}
            System.out.println("RED_COUNT "+red);if(red==0)throw new AssertionError("expected the pre-candidate code to lack RLDI2");
        }else check(v2,"RLDI2 candidate class present (GREEN run)");
        System.out.println("Phase X-C F-IX-1 reproduction passed "+checks+" checks"+(red>0?" with "+red+" RED reproductions":"")+"; RLDI1 unchanged and BLOCKED");
    }
    private PhaseXcF1ReproTest(){}
}
