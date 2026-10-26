package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.util.*;

/** RLDR1 required-dependency readiness (Phase X-D1). RED stub: API shape only, every axis UNKNOWN. */
public final class DependencyReadiness {
    public static final String VERSION="RLDR1",POLICY_ID="RLDR1-REQUIRED-SET-POLICY-1";
    public static final List<String> AXES=Collections.unmodifiableList(Arrays.asList("profile_policy","inventory","classpath","required_set","freshness","process_provenance","access_control","writer"));
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
        public String json(){return "{}";}
    }
    public static Report inspect(boolean enable,File profile,File installRoot,File record){return inspect(enable,profile,installRoot,record,DistributionInventory.NO_HOOK);}
    public static Report inspect(boolean enable,File profile,File installRoot,File record,DistributionInventory.Hook hook){
        Map<String,Axis> axes=new LinkedHashMap<String,Axis>();for(String a:AXES)axes.put(a,new Axis("UNKNOWN","NOT_IMPLEMENTED"));
        return new Report("UNKNOWN","NOT_IMPLEMENTED","UNKNOWN",axes,Collections.<String>emptySet(),Collections.<String>emptySet(),new TreeMap<String,Integer>());
    }
    private DependencyReadiness(){}
}
