package net.neristal.rrbridge.prototype.ingress;

/** TEST ONLY: Phase 3d-1 RLM1 rotation scenarios with native NTFS identity (JNA) and the 3c-1 ledger. */
public final class WindowsRotationTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("child")) { RotationScenarios.child(WindowsWatermarkV2Test.ENV, args[1], new java.io.File(args[2]).toPath(), args[3]); return; }
        RotationScenarios.run(WindowsWatermarkV2Test.ENV, WindowsRotationTest.class.getName());
    }
}
