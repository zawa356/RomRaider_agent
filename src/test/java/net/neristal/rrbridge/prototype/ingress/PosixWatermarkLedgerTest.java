package net.neristal.rrbridge.prototype.ingress;

import java.io.IOException;

/** Phase 3c-1 ledger scenarios with POSIX dev/inode identity. NOT RUN without the unix attribute view. */
public final class PosixWatermarkLedgerTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("child")) { WatermarkLedgerScenarios.child(PosixWatermarkV2Test.ENV, args[1], new java.io.File(args[2]).toPath(), args[3]); return; }
        try { WatermarkV2Prototype.posixIdentity(WatermarkV2Scenarios.seed("posix-ledger-probe").resolve("source.rlj")); }
        catch (IOException unavailable) {
            System.out.println("NOT RUN PosixWatermarkLedgerTest: no unix:dev/ino view on " + System.getProperty("os.name"));
            return;
        }
        WatermarkLedgerScenarios.run(PosixWatermarkV2Test.ENV, PosixWatermarkLedgerTest.class.getName());
    }
}
