package net.neristal.rrbridge.prototype.ingress;

/** TEST ONLY: Phase 3c-1 ledger scenarios with native NTFS identity (JNA) and real SQLite. */
public final class WindowsWatermarkLedgerTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("child")) { WatermarkLedgerScenarios.child(WindowsWatermarkV2Test.ENV, args[1], new java.io.File(args[2]).toPath(), args[3]); return; }
        WatermarkLedgerScenarios.run(WindowsWatermarkV2Test.ENV, WindowsWatermarkLedgerTest.class.getName());
    }
}
