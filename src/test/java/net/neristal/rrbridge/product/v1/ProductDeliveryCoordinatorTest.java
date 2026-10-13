package net.neristal.rrbridge.product.v1;

import java.io.File;
import java.nio.file.Files;

/** Clock-controlled recovery tests, backed by genuine SQLite WAL FULL. */
public final class ProductDeliveryCoordinatorTest {
    static int checks;
    static void ok(String label,boolean yes) {
        if(!yes)throw new AssertionError(label);
        checks++;System.out.println("PASS "+label);
    }
    static class Fake implements ProductDeliveryCoordinator.DeliveryStep {
        ProductSender.Outcome response=ProductSender.Outcome.RETRY_LATER;
        int calls;
        public ProductSender.Outcome sendOnce() {calls++;return response;}
    }
    static ProductDeliveryCoordinator make(DurableSpool s,Fake f) {
        return new ProductDeliveryCoordinator(s,f,2000,120000);
    }
    public static void main(String[] args)throws Exception {
        File file=new File(Files.createTempDirectory("v1-backoff-").toFile(),"sender.db");
        DurableSpool db=new DurableSpool(file);
        Fake fake=new Fake();
        ProductDeliveryCoordinator coordinator=make(db,fake);
        ok("fresh sender state empty",coordinator.status().retryCount==0);
        ok("first temporary failure backoff",coordinator.tick(10000)==
           ProductDeliveryCoordinator.Result.BACKOFF);
        ok("first retry delay 2 seconds",coordinator.status().nextAttemptMs==12000);
        ok("first retry counter persistent",coordinator.status().retryCount==1);
        ok("early tick does not contact server",coordinator.tick(11999)==
           ProductDeliveryCoordinator.Result.WAITING&&fake.calls==1);
        // Restart changes Java instance, retains the exact deadline in SQLite.
        db=new DurableSpool(file);
        Fake resumed=new Fake();
        coordinator=make(db,resumed);
        ok("restart preserves backoff countdown",coordinator.tick(11999)==
           ProductDeliveryCoordinator.Result.WAITING&&resumed.calls==0);
        ok("deadline allows second retry",coordinator.tick(12000)==
           ProductDeliveryCoordinator.Result.BACKOFF&&resumed.calls==1);
        ok("exponential backoff is 4 seconds",coordinator.status().nextAttemptMs==16000&&
           coordinator.status().retryCount==2);
        long now=16000;
        for(int i=0;i<12;i++) {
            ok("retries are deferred until previous deadline "+i,
                coordinator.tick(now-1)==ProductDeliveryCoordinator.Result.WAITING);
            coordinator.tick(now);
            long target=Math.min(120000,2000L<<(Math.min(12,i+2)));
            long next=coordinator.status().nextAttemptMs;
            ok("bounded retry delay "+i,next-now==target);
            now=next;
        }
        ok("backoff capped at 120 seconds",coordinator.status().retryCount==14);
        // A valid batch ACK resets counters and permits immediate further work.
        resumed.response=ProductSender.Outcome.BATCH_ACKED;
        long deadline=coordinator.status().nextAttemptMs;
        ok("ACK at deadline accepted",coordinator.tick(deadline)==
           ProductDeliveryCoordinator.Result.ACKED);
        ok("successful ACK clears retry state",coordinator.status().retryCount==0&&
           coordinator.status().nextAttemptMs==deadline);
        resumed.response=ProductSender.Outcome.IDLE;
        ok("IDLE delays next poll without retry count",coordinator.tick(deadline)==
           ProductDeliveryCoordinator.Result.IDLE);
        ok("IDLE has short bounded pause",coordinator.status().nextAttemptMs==deadline+2000);
        // Operator review is durable and hard-blocks automatic loops.
        resumed.response=ProductSender.Outcome.NEEDS_REVIEW;
        ok("invalid response requires manual review",
           coordinator.tick(deadline+2000)==ProductDeliveryCoordinator.Result.REVIEW_REQUIRED);
        int calls=resumed.calls;
        ok("review gate persisted",coordinator.status().needsReview);
        ok("manual review blocks without any extra network call",
           coordinator.tick(deadline+10000000)==ProductDeliveryCoordinator.Result.REVIEW_REQUIRED&&
           resumed.calls==calls);
        Fake second=new Fake();
        coordinator=make(new DurableSpool(file),second);
        ok("review gate survives restart",coordinator.tick(deadline+10000001)==
           ProductDeliveryCoordinator.Result.REVIEW_REQUIRED&&second.calls==0);
        coordinator.resetAfterOperatorReview();
        ok("manual operator reset recorded",!coordinator.status().needsReview&&
           "OPERATOR_RESET".equals(coordinator.status().lastOutcome));
        second.response=ProductSender.Outcome.PROFILE_ACKED;
        ok("operator reset permits a new attempt",
            coordinator.tick(deadline+10000002)==ProductDeliveryCoordinator.Result.ACKED&&
            second.calls==1);
        boolean failed=false;
        try{coordinator.resetAfterOperatorReview();}
        catch(IllegalStateException expected){failed=true;}
        ok("reset without review is rejected",failed);
        Fake exploded=new Fake(){
            public ProductSender.Outcome sendOnce() {
                super.calls++;throw new IllegalStateException("synthetic SQLite failure");
            }
        };
        ProductDeliveryCoordinator fail=make(db,exploded);
        boolean threw=false;
        try{fail.tick(deadline+10000003);}
        catch(IllegalStateException expected){threw=true;}
        ok("unexpected storage exception never suppressed",threw);
        ok("unexpected exception not recorded as server ACK",
            "PROFILE_ACKED".equals(fail.status().lastOutcome));
        ok("no spurious pending data inserted by retry logic",db.countPending()==0);
        System.out.println("ProductDeliveryCoordinatorTest passed "+checks+" checks (real SQLite; no ECU)");
    }
}
