package net.neristal.rrbridge.prototype.ingress;
import java.io.File;
import net.neristal.rrbridge.product.v1.LaneOwnership;
/** Synthetic Writer/Reader contract: every source/manifest/ledger mutation uses this boundary. */
public final class Rlc2PublicationBoundary {
 private static final String DOMAIN="00000000-0000-0000-0000-000000000001";
 public interface Update {void run()throws Exception;}
 private Rlc2PublicationBoundary(){}
 public static LaneOwnership acquire(boolean enable,File root,String manifestSession)throws Exception {
  return LaneOwnership.acquire(enable,root,DOMAIN,manifestSession,"RLC2-publication-v1");
 }
 /** Publish -> seal/rotation -> ledger alignment all complete before releasing boundary. */
 public static void update(boolean enable,File root,String manifestSession,Update update)throws Exception {
  try(LaneOwnership boundary=acquire(enable,root,manifestSession)) {
   boundary.verify();update.run();boundary.verify();
  }
 }
}
