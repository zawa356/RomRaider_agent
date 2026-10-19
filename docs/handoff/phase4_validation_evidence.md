# Phase IV validation evidence

Each row records the exact code under test, the command, the result and the log SHA256. Logs are ignored local files under `build/` on the Windows development VM (`C:\Dev\RomLogger\RomRaider_agent`). PASS / FAIL / SKIPPED / NOT RUN / BLOCKED are kept distinct. GitHub Actions: **CI NOT RUN** (runner not started, jobs `steps=0`, see worklog).

## IV-0 independent review (working tree on top of `0b14ddf`, committed in the IV-0 PR)

| Target | Command | Result | Log SHA256 |
|---|---|---|---|
| RED before fix, Windows JDK17.0.20.1 x64 | `javac --release 8 … Phase4ReviewRedTest` → `java … Phase4ReviewRedTest` | exit1: F1/F1b/F1c/F2/F2b **RED** (1 PASS control) | `fc0a74eaab62649784dc03fa06e657b945cafe1fe53ac80e9110bbabb6f03b7e` (`build/phase4-review-red.log`) |
| GREEN after fix, JDK17 x64 | same | exit0: 9 PASS, 0 RED | `da897a122a9c430e378200de0b06333dff2443573016ec7644dd0fe8fb43a7ab` |
| GREEN after fix, real Zulu 1.8.0_504 x86 (`sun.arch.data.model=32`) | same classes, Zulu8 java | exit0: 9 PASS, 0 RED | `5899d6a52ccddf0c20381d628e7d309ace202b298212d142da1ca5567ddfb87b` |
| Phase III regression, JDK17 x64 | `bash scripts/test-phase3-deployment.sh` (JDK17 first on PATH) | exit0: binding18 / engine6 (JVM=64) / readiness11 / backup32 / stages52 + alias/CLI/cleanup tests | `9ea681014958f0d24429f397c0b874ee0e03af3484a3e169bb31637c0b701bcf` |
| Phase III regression, Zulu8 x86 | `ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-phase3-deployment.sh --run-only` (Zulu8 first on PATH) | exit0: same counts, engine `actual JVM=32` | `aecc0790bf7202af90470a2900d6b37c18236c23b0c9c8a1b543ae0d42f95f9a` |
| F6 dangling symlink sidecar | Phase4ReviewRedTest on Windows | **SKIPPED / NOT RUN** (no symlink privilege). Linux run pending | — |
| Linux identity probe (VPS, Linux 6.8 ext4, JDK17.0.20.1) | `java L.java` in `/tmp/romlogger-phase4-probe` | birth time stable across write; delete+recreate reused inode 19122 but changed birth time | console only |
