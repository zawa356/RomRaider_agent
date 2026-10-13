package net.neristal.rrbridge.product.v1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * One deterministic iteration of the product v1 delivery pipeline.
 *
 * Called by a future RRBridge/Supervisor scheduler. Never blocks a Logger
 * callback or performs remote commands. Does NOT start its own background
 * thread. No unACKed records are dropped under any error condition.
 */
public final class ProductSender {
    public enum Outcome { IDLE, PROFILE_ACKED, BATCH_ACKED, RETRY_LATER, NEEDS_REVIEW }

    private final DurableSpool spool;
    private final AgentWire.HttpTransport transport;
    private final String deviceId, vehicleId;
    private final int maxRecords, maxRecordBytes;

    public ProductSender(DurableSpool spool, String serverOrigin, String deviceToken,
                         String deviceId, String vehicleId, int timeoutMs,
                         int maxRecords, int maxRecordBytes) throws Exception {
        if(spool==null)throw new IllegalArgumentException("Spool required");
        // A device identity is supplied independently of the agent-controlled
        // payload, and the Server verifies its assignment to the Bearer token.
        this.deviceId=java.util.UUID.fromString(deviceId).toString();
        this.vehicleId=java.util.UUID.fromString(vehicleId).toString();
        if(maxRecords<1||maxRecords>128||maxRecordBytes<128||maxRecordBytes>450000)
            throw new IllegalArgumentException("Unsafe batch bounds");
        this.spool=spool;
        this.transport=new AgentWire.HttpTransport(serverOrigin,deviceToken,timeoutMs);
        this.maxRecords=maxRecords;
        this.maxRecordBytes=maxRecordBytes;
    }

    /** A single outbound HTTP exchange at most, for deterministic retries. */
    public Outcome pumpOnce() throws Exception {
        java.util.List<DurableSpool.PendingProfile> profiles=spool.pendingProfileEntries();
        if(!profiles.isEmpty()) {
            DurableSpool.PendingProfile pending=profiles.get(0);
            try {
                AgentWire.HttpResult result=transport.send("PUT",
                    "/api/agent/v1/profile-revisions/"+pending.profileId,pending.json);
                if(result.status==200||result.status==201) {
                    // 2xx does NOT imply confirmation without full response matching.
                    boolean confirmed;
                    try {confirmed=AgentWire.profileConfirmed(result.body,pending.profileId,result.status);}
                    catch(IllegalArgumentException invalidResponse) {return Outcome.NEEDS_REVIEW;}
                    if(!confirmed)return Outcome.NEEDS_REVIEW;
                    spool.markProfileAcked(pending.profileId,true);
                    return Outcome.PROFILE_ACKED;
                }
                return statusOutcome(result.status);
            } catch (IOException ex) {return Outcome.RETRY_LATER;}
        }
        DurableSpool.Batch batch=spool.nextBatch(maxRecords,maxRecordBytes);
        if(batch==null)return Outcome.IDLE;
        final String request=AgentWire.batchJson(deviceId,vehicleId,batch);
        // Total envelope size is bounded as well as each record's byte count.
        if(request.getBytes(StandardCharsets.UTF_8).length>512*1024)
            return Outcome.NEEDS_REVIEW;
        try {
            AgentWire.HttpResult result=transport.send("POST",
                "/api/agent/v1/telemetry/batches",request);
            if(result.status==200) {
                DurableSpool.Ack ack;
                try {ack=AgentWire.confirmedAck(result.body,batch);}
                catch(IllegalArgumentException ex){return Outcome.NEEDS_REVIEW;}
                spool.markBatchAcked(batch,ack);
                return Outcome.BATCH_ACKED;
            }
            return statusOutcome(result.status);
        } catch (IOException ex) {return Outcome.RETRY_LATER;}
    }

    private static Outcome statusOutcome(int status) {
        if(status==429||status==503||status>=500||status==408)
            return Outcome.RETRY_LATER;
        // 401/403 require credential intervention; 409/422 need diagnostic
        // isolation; 413 needs record-preserving subdivision in Phase 2.1-C.
        return Outcome.NEEDS_REVIEW;
    }
}
