package net.neristal.rrbridge.prototype.ingress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Offline product candidate. Explicit admission; no Worker, sender or persistent ACK. */
public final class Rlc2IntegrationStore {
    public static final class Admission {
        public final String directory,manifestSession;
        public final Set<String> sourceUuids;
        public Admission(Path directory,String manifestSession,Set<String> sourceUuids)throws Exception {
            IngressDiagnostics.safePath(directory);this.directory=directory.toFile().getCanonicalPath();
            this.manifestSession=java.util.UUID.fromString(manifestSession).toString();
            Set<String> ids=new HashSet<String>();for(String id:sourceUuids)ids.add(java.util.UUID.fromString(id).toString());
            if(ids.isEmpty()||ids.size()>RotationManifestPrototype.MAX_SEGMENTS)throw new IllegalArgumentException("bounded explicit admissions required");
            this.sourceUuids=Collections.unmodifiableSet(ids);
        }
    }
    private final Rlc2PreviewStore backend;
    private final File output;
    public Rlc2IntegrationStore(boolean enable,File output)throws Exception {
        if(!enable)throw new IllegalStateException("integration disabled");
        this.output=output;
        backend=new Rlc2PreviewStore(true,output,true);
    }
    public Rlc2PreviewStore.Result ingest(boolean externallyQuiescent,final Admission admission,final Path directory,final File ledger,
            final File legacy,final String device,final String vehicle,final WatermarkV2Prototype.ReadOpener reader)throws Exception {
        return ingest(externallyQuiescent,admission,directory,ledger,legacy,device,vehicle,reader,Rlc2PreviewStore.NO_FAULT);
    }
    public Rlc2PreviewStore.Result ingest(boolean externallyQuiescent,final Admission admission,final Path directory,final File ledger,
            final File legacy,final String device,final String vehicle,final WatermarkV2Prototype.ReadOpener reader,Rlc2PreviewStore.Hook hook)throws Exception {
        try(net.neristal.rrbridge.product.v1.LegacyWorkerGuard legacyGuard=net.neristal.rrbridge.product.v1.LegacyWorkerGuard.acquire(legacy)) {
        return ingestOwned(externallyQuiescent,admission,directory,ledger,legacy,device,vehicle,reader,hook,legacyGuard,null);
        }
    }
    /** Isolated opt-in; payload session must match projection before any output writes. */
    public Rlc2PreviewStore.Result ingestCooperative(boolean enable,boolean externallyQuiescent,File root,String payloadSession,
            final Admission admission,final Path directory,final File ledger,final File legacy,
            final String device,final String vehicle,final WatermarkV2Prototype.ReadOpener reader,Rlc2PreviewStore.Hook hook)throws Exception {
        if(!enable)throw new IOException("LANE_DISABLED");
        try(net.neristal.rrbridge.product.v1.LegacyWorkerGuard old=net.neristal.rrbridge.product.v1.LegacyWorkerGuard.acquire(legacy);
            net.neristal.rrbridge.product.v1.LaneOwnership lane=net.neristal.rrbridge.product.v1.LaneOwnership.acquire(true,root,device,vehicle,payloadSession)) {
            return ingestOwned(externallyQuiescent,admission,directory,ledger,legacy,device,vehicle,reader,hook,old,lane);
        }
    }
    private Rlc2PreviewStore.Result ingestOwned(boolean externallyQuiescent,final Admission admission,final Path directory,final File ledger,
            final File legacy,final String device,final String vehicle,final WatermarkV2Prototype.ReadOpener reader,Rlc2PreviewStore.Hook hook,
            final net.neristal.rrbridge.product.v1.LegacyWorkerGuard legacyGuard,final net.neristal.rrbridge.product.v1.LaneOwnership lane)throws Exception {
        if(!externallyQuiescent)throw new IOException("external quiescence required throughout transaction");
        if(admission==null||!directory.toFile().getCanonicalPath().equals(admission.directory))throw new IOException("session directory not admitted");
        if(output.getCanonicalFile().toPath().startsWith(directory.toFile().getCanonicalFile().toPath())||output.getCanonicalFile().equals(ledger.getCanonicalFile())||output.getCanonicalFile().equals(legacy.getCanonicalFile()))throw new IOException("output must be separate from all input snapshots");
        final Rlc2InputSnapshot snapshot=Rlc2InputSnapshot.capture(directory,ledger,legacy);
        final Rlc2ProductAdapter.Projection p=Rlc2ProductAdapter.project(true,directory,ledger,device,vehicle,reader);
        snapshot.verify();
        if(lane!=null&&!lane.matches(p.device,p.vehicle,p.payloadSession))throw new IOException("LANE_KEY_MISMATCH");
        if(!p.manifestSession.equals(admission.manifestSession))throw new IOException("manifest session not admitted");
        final Set<String> actual=new HashSet<String>();for(Rlc2ProductAdapter.Source s:p.sources)actual.add(s.uuid);
        if(!actual.equals(admission.sourceUuids))throw new IOException("every physical source requires explicit admission");
        final String run=p.records.get(0).run;
        Rlc2IntegrationSchema.requireUnclaimed(legacy,p.device,p.vehicle,p.payloadSession,run);
        return backend.ingest(p,hook,new Rlc2PreviewStore.TransactionGuard(){
            public void beforeWrite(Connection db)throws Exception{
                legacyGuard.verify();if(lane!=null)lane.verify();
                snapshot.verify();
                try(PreparedStatement q=db.prepareStatement("SELECT session_path,run_id FROM integration_bindings WHERE session_uuid=?")){
                    q.setString(1,p.manifestSession);try(ResultSet r=q.executeQuery()){
                        if(r.next()&&(!admission.directory.equals(r.getString(1))||!run.equals(r.getString(2))))throw new IOException("logical session moved or rebound");
                    }
                }
                Set<String> existingSources=new HashSet<String>(),existingAdmissions=new HashSet<String>();
                String[] tables={"preview_sources","integration_admissions"};
                for(int i=0;i<tables.length;i++)try(PreparedStatement q=db.prepareStatement("SELECT source_uuid FROM "+tables[i]+" WHERE session_uuid=?")){
                    q.setString(1,p.manifestSession);try(ResultSet r=q.executeQuery()){while(r.next())(i==0?existingSources:existingAdmissions).add(r.getString(1));}
                }
                if(!existingSources.equals(existingAdmissions))throw new IOException("saved source admission missing; no reconstruction");
            }
            public void claimsWritten(Connection db)throws Exception{
                try(PreparedStatement q=db.prepareStatement("INSERT OR IGNORE INTO integration_bindings VALUES(?,?,?,2)")){
                    q.setString(1,p.manifestSession);q.setString(2,admission.directory);q.setString(3,run);q.executeUpdate();
                }
                for(String uuid:actual)try(PreparedStatement q=db.prepareStatement("INSERT OR IGNORE INTO integration_admissions VALUES(?,?)")){
                    q.setString(1,uuid);q.setString(2,p.manifestSession);q.executeUpdate();
                }
            }
            public void beforeCommit(Connection db)throws Exception{
                Rlc2IntegrationSchema.requireUnclaimed(legacy,p.device,p.vehicle,p.payloadSession,run);
                legacyGuard.verify();if(lane!=null)lane.verify();
                snapshot.verify();
            }
        });
    }
}
