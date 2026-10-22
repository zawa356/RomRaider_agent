package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import net.neristal.rrbridge.product.v1.DeploymentBinding;

/**
 * Read-only stop gate for a candidate requiring proven noncooperative SQLite writer absence.
 * No writable probe, database connection, Worker lock creation or recovery. SQLite reserved and
 * exclusive locks can exist without sidecars; unchanged bytes and an external assertion cannot
 * prove absence. There is deliberately no boolean/probe injection that can manufacture proof.
 * The old explicitly synthetic classifier/stager remain content experiments, not this gate.
 */
public final class LegacyCandidateGate {
    public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
    private static String reason(boolean enable,File legacy){
        if(!enable)return "CANDIDATE_GATE_DISABLED";
        try{
            Path p=DeploymentBinding.safe(legacy);
            if(!Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isRegularFile())return "LEGACY_INPUT_UNAVAILABLE";
            for(String suffix:new String[]{"-wal","-shm","-journal"}){
                // Attributes distinguish absence from access failure; Files.exists can hide access errors.
                try{Files.readAttributes(Paths.get(p.toString()+suffix),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return "DATABASE_SIDECAR_PRESENT";}
                catch(NoSuchFileException absent){}
            }
            return "WRITER_UNPROVEN";
        }catch(Exception unavailable){return "LEGACY_INPUT_UNAVAILABLE";}
    }
    public static String inspect(boolean enable,File legacy){
        String code=reason(enable,legacy);
        return "{\"contract_version\":1,\"state\":\"BLOCKED\",\"reason\":\""+code+"\",\"writer_quiescence\":\"WRITER_UNPROVEN\",\"synthetic_only\":true,\"automatic_recovery\":false,\"production_authorized\":false,\"ack_eligible\":false}";
    }
    /** Must precede classification/staging in any caller requiring writer-absence proof. Always refuses today. */
    public static void requireWriterProof(boolean enable,File legacy)throws Refusal {throw new Refusal(reason(enable,legacy));}
    public static void main(String[] args){
        if(args.length!=2||!"--inspect-synthetic".equals(args[0])){System.out.println(inspect(false,null));System.exit(2);return;}
        System.out.println(inspect(true,new File(args[1])));System.exit(5);
    }
    private LegacyCandidateGate(){}
}
