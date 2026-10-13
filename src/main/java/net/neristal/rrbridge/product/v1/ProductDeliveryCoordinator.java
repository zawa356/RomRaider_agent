package net.neristal.rrbridge.product.v1;

/**
 * Opt-in delivery scheduler policy. One tick performs at most one HTTP exchange.
 * All retry/review decisions persist in the SAME SQLite DB as the original rows.
 * This class creates no thread and never invokes Logger or an ECU command.
 */
public final class ProductDeliveryCoordinator {
    public interface DeliveryStep {
        ProductSender.Outcome sendOnce() throws Exception;
    }

    public enum Result { IDLE, ACKED, BACKOFF, REVIEW_REQUIRED, WAITING }

    private final DurableSpool spool;
    private final DeliveryStep step;
    private final long baseDelayMs;
    private final long capDelayMs;

    public ProductDeliveryCoordinator(DurableSpool spool,DeliveryStep step,
                                      long baseDelayMs,long capDelayMs) {
        if(spool==null||step==null||baseDelayMs<100||capDelayMs<baseDelayMs||
           capDelayMs>3600000)
            throw new IllegalArgumentException("Invalid delivery scheduling");
        this.spool=spool;this.step=step;
        this.baseDelayMs=baseDelayMs;this.capDelayMs=capDelayMs;
    }

    public static ProductDeliveryCoordinator forSender(
            DurableSpool spool,final ProductSender sender) {
        if(sender==null)throw new IllegalArgumentException("Missing sender");
        return new ProductDeliveryCoordinator(spool,new DeliveryStep(){
            public ProductSender.Outcome sendOnce() throws Exception {
                return sender.pumpOnce();
            }
        },2000,120000);
    }

    /**
     * nowMs is injected for reproducible tests and to avoid blocking or
     * sleeping in any Logger callback. Use wall-clock Unix milliseconds.
     */
    public synchronized Result tick(long nowMs) throws Exception {
        if(nowMs<0)throw new IllegalArgumentException("Invalid current time");
        DurableSpool.DeliveryState prior=spool.loadDeliveryState();
        if(prior.needsReview)return Result.REVIEW_REQUIRED;
        if(nowMs<prior.nextAttemptMs)return Result.WAITING;
        // Unknown SQLException/RuntimeException must propagate and MUST NOT
        // be interpreted as a successful network retry or automatic quarantine.
        ProductSender.Outcome outcome=step.sendOnce();
        if(outcome==null)throw new IllegalStateException("Sender returned null");
        switch(outcome) {
            case PROFILE_ACKED:
            case BATCH_ACKED:
                spool.saveDeliveryState(0,nowMs,false,outcome.name());
                return Result.ACKED;
            case IDLE:
                spool.saveDeliveryState(0,addBounded(nowMs,baseDelayMs),
                                        false,outcome.name());
                return Result.IDLE;
            case NEEDS_REVIEW:
                spool.saveDeliveryState(prior.retryCount,nowMs,true,outcome.name());
                return Result.REVIEW_REQUIRED;
            case RETRY_LATER:
                int retry=Math.min(30,prior.retryCount+1);
                long delay=baseDelayMs;
                for(int i=1;i<retry;i++)delay=Math.min(capDelayMs,delay*2);
                spool.saveDeliveryState(retry,addBounded(nowMs,delay),
                                        false,outcome.name());
                return Result.BACKOFF;
            default:throw new IllegalStateException("Unhandled sender state");
        }
    }

    private static long addBounded(long a,long b) {
        return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;
    }

    /** Explicit operator action, not a network error auto-retry. */
    public synchronized void resetAfterOperatorReview() throws Exception {
        DurableSpool.DeliveryState existing=spool.loadDeliveryState();
        if(!existing.needsReview)
            throw new IllegalStateException("No held error requires operator review");
        spool.saveDeliveryState(0,0,false,"OPERATOR_RESET");
    }

    public DurableSpool.DeliveryState status() throws Exception {
        return spool.loadDeliveryState();
    }
}
