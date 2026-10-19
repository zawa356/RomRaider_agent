package net.neristal.rrbridge.prototype.ingress;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import net.neristal.rrbridge.product.v1.*;

/**
 * Default-off, read-only preflight for a future offline launcher. Never starts a Worker/Sender,
 * opens a DB writable, creates a lock or activates a candidate. Matching artifacts and retained
 * shared observations still cannot prove noncooperative writer absence: launch remains BLOCKED.
 */
public final class OfflineCandidatePreflight {
    public static final String MAIN=Rlc2IntegrationCli.class.getName();
    public static final class Refusal extends IOException {public Refusal(String code){super(code);}}
    public static final class Report {
        public final String reason;public final boolean prerequisitesMatched;
        private final Map<String,String> axes;
        Report(String reason,boolean matched,Map<String,String> axes){this.reason=reason;prerequisitesMatched=matched;this.axes=Collections.unmodifiableMap(new TreeMap<String,String>(axes));}
        public String json(){
            StringBuilder b=new StringBuilder("{\"contract_version\":1,\"state\":\"BLOCKED\",\"reason\":\"").append(reason).append("\",\"prerequisites_matched\":").append(prerequisitesMatched);
            for(Map.Entry<String,String> axis:axes.entrySet())b.append(",\"").append(axis.getKey()).append("\":\"").append(axis.getValue()).append('"');
            return b.append(",\"synthetic_only\":true,\"launch_authorized\":false,\"production_authorized\":false,\"ack_eligible\":false,\"automatic_recovery\":false}").toString();
        }
    }
    private static void require(boolean ok,String code)throws Refusal {if(!ok)throw new Refusal(code);}
    /** Shared read-only observation of an existing exact lock, retained through the whole preflight. */
    private static final class Observation implements AutoCloseable {
        final Path path;final FileChannel channel;final FileLock lock;final byte[] bytes;
        Observation(Path path,String code)throws Exception {
            this.path=path;require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"COOPERATIVE_LOCK_MISSING");
            DeploymentBinding.safe(path.toFile());DeploymentBinding.privateAccess(path);require(Files.size(path)<=4096,"LOCK_EVIDENCE_INVALID");
            channel=FileChannel.open(path,StandardOpenOption.READ);FileLock held=null;
            try{
                try{held=channel.tryLock(0,Long.MAX_VALUE,true);}catch(OverlappingFileLockException busy){throw new Refusal(code);}
                require(held!=null,code);lock=held;bytes=read();verify();
            }catch(Exception failure){if(held!=null)held.release();channel.close();throw failure;}
        }
        byte[] read()throws IOException {
            try(InputStream in=Files.newInputStream(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>4096)throw new Refusal("LOCK_EVIDENCE_INVALID");out.write(buffer,0,n);}return out.toByteArray();
            }
        }
        void verify()throws Exception {require(lock.isValid()&&channel.isOpen(),"OBSERVATION_LOST");DeploymentBinding.safe(path.toFile());require(Arrays.equals(bytes,read()),"LOCK_EVIDENCE_CHANGED");}
        public void close()throws IOException{try{lock.release();}finally{channel.close();}}
    }
    private static Path lane(File root,String device,String vehicle,String session){
        String key=device+"|"+vehicle+"|"+session;
        return root.toPath().resolve(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()+".lane.lock");
    }
    public static Report inspect(boolean enable,DeploymentBinding.Layout layout,int expectedGeneration,String expectedManifestSha,
            Rlc2IntegrationStore.Admission admission,List<File> classpath,Map<String,String> expectedArtifacts,long processId,
            WatermarkV2Prototype.ReadOpener reader){
        Map<String,String> axes=new TreeMap<String,String>();
        for(String field:new String[]{"legacy_worker","lane_owner","publication_lock","deployment_generation","manifest_floor","source_admission","artifact_provenance","cursor_provenance","vault_integrity","staged_rli2_integrity","process_provenance","directory_durability"})axes.put(field,"UNKNOWN");
        axes.put("product_mode","SYNTHETIC_OFFLINE");axes.put("writer_quiescence","WRITER_UNPROVEN");axes.put("formal_ack","DISABLED");
        if(!enable)return new Report("PREFLIGHT_DISABLED",false,axes);
        try{
            require(expectedGeneration>=0&&expectedManifestSha!=null&&expectedManifestSha.matches("[0-9a-f]{64}"),"EXPECTED_GENERATION_REQUIRED");
            require(expectedArtifacts!=null&&MAIN.equals(expectedArtifacts.get("main_class")),"UNKNOWN_OR_LEGACY_ENTRY_BUILD");
            ArtifactProvenance.compare(expectedArtifacts,ArtifactProvenance.describe(classpath,MAIN));axes.put("artifact_provenance","MATCHED_APPROVED_DESCRIPTION");
            int generation=DeploymentTransition.verifyActive(true,true,layout);require(generation==expectedGeneration,"DEPLOYMENT_GENERATION_MISMATCH");axes.put("deployment_generation",Integer.toString(generation));
            require(expectedManifestSha.equals(DeploymentTransition.fileSha(layout.manifest)),"MANIFEST_EXPECTATION_MISMATCH");axes.put("manifest_floor","MATCHED_EXPECTED_BYTES");
            require(admission!=null&&admission.directory.equals(layout.session.getPath())&&admission.manifestSession.equals(layout.manifestSession),"SOURCE_ADMISSION_MISMATCH");
            require("IDLE".equals(LaneOwnership.diagnose(layout.root,layout.device,layout.vehicle,layout.payloadSession)),"LANE_NOT_OBSERVED_IDLE");
            require("IDLE".equals(LaneOwnership.diagnose(layout.root,"00000000-0000-0000-0000-000000000001",layout.manifestSession,"RLC2-publication-v1")),"PUBLICATION_NOT_OBSERVED_IDLE");
            try(Observation old=new Observation(Paths.get(layout.legacy.getPath()+".worker.lock"),"LEGACY_WORKER_BUSY");
                Observation owner=new Observation(lane(layout.root,layout.device,layout.vehicle,layout.payloadSession),"LANE_BUSY");
                Observation publication=new Observation(lane(layout.root,"00000000-0000-0000-0000-000000000001",layout.manifestSession,"RLC2-publication-v1"),"PUBLICATION_BUSY")){
                axes.put("legacy_worker","EXISTING_COOPERATIVE_LOCK_OBSERVED_IDLE");axes.put("lane_owner","EXISTING_COOPERATIVE_LOCK_OBSERVED_IDLE");axes.put("publication_lock","EXISTING_COOPERATIVE_LOCK_OBSERVED_IDLE");
                Rlc2InputSnapshot snapshot=Rlc2InputSnapshot.capture(layout.session.toPath(),layout.ledger,layout.legacy);
                Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,layout.session.toPath(),layout.ledger,layout.device,layout.vehicle,reader);
                Set<String> sources=new HashSet<String>();for(Rlc2ProductAdapter.Source source:p.sources)sources.add(source.uuid);
                require(p.manifestSession.equals(admission.manifestSession)&&p.payloadSession.equals(layout.payloadSession)&&sources.equals(admission.sourceUuids),"SOURCE_ADMISSION_MISMATCH");axes.put("source_admission","MATCHED_ORIGINAL_PROJECTION");
                ArtifactProvenance.Inspection process=ArtifactProvenance.inspectProcess(processId,expectedArtifacts);
                // This is independently observed OS evidence; classpath description above never authenticates a process.
                axes.put("process_provenance",process.processProvenance);
                snapshot.verify();old.verify();owner.verify();publication.verify();
                ArtifactProvenance.compare(expectedArtifacts,ArtifactProvenance.describe(classpath,MAIN));
                require(DeploymentTransition.verifyActive(true,true,layout)==expectedGeneration,"DEPLOYMENT_GENERATION_MISMATCH");
                require(expectedManifestSha.equals(DeploymentTransition.fileSha(layout.manifest)),"MANIFEST_EXPECTATION_MISMATCH");
                return new Report("WRITER_UNPROVEN",true,axes);
            }
        }catch(Exception failure){
            String reason=failure instanceof Refusal||failure instanceof DeploymentBinding.Refusal||failure instanceof DeploymentTransition.Refusal||failure instanceof ArtifactProvenance.Refusal?failure.getMessage():"INSPECTION_UNAVAILABLE";
            return new Report(reason,false,axes);
        }
    }
    private OfflineCandidatePreflight(){}
}
