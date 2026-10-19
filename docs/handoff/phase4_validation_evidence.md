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
| F6 dangling symlink sidecar | Phase4ReviewRedTest on Windows | **SKIPPED / NOT RUN** (no symlink privilege) | — |
| Linux identity probe (VPS, Linux 6.8 ext4, JDK17.0.20.1) | `java L.java` in `/tmp/romlogger-phase4-probe` | birth time stable across write; delete+recreate reused inode 19122 but changed birth time | console only |

## IV-0 additional RED evidence on Linux (VPS, `/tmp/romlogger-phase4-red-2b620c7`, pre-fix main `2b620c7` + review test)

| Target | Result | Log SHA256 |
|---|---|---|
| Review test (original F6 assertion) on pre-fix code | exit1: F2/F2b RED; F1 family not RED on Linux (inode+birth time detects); F6 "passed" through a later schema refusal, so the assertion did not discriminate | `2b2c167dd98ef0eed1db8c7fc47a31e299bf67a28d7f7311f8e1d566ce01a165` (`red-linux.log`) |
| Tightened F6 (commit `04564c5`) on pre-fix code | exit1: **F6 RED** `[unknown v1 schema; no migration]`, F2/F2b RED (6 PASS / 3 RED) | `02fb35b01406f1cc87f95ea6ecf96208de18268c7960afb5d74344e8ed6f9c50` (`f6.log`) |
| Tightened F6 on fixed code (`0e61251` dir with the tightened test copied in) | exit0: 9 PASS / 0 RED, F6 `[closed checkpointed DB required]` | `bcdf66c44481980e9d120f39174c0fcdbbb0c59c17415983d2fd87ff8f5328d5` |

## IV-1..IV-4 (source archive of `0e61251`, SHA256 `bfda3dc017de0b46b0c8f6b90a666deeda5e83156624b760aebe19e0435804be`)

| Target | Command | Result | Log SHA256 |
|---|---|---|---|
| DeploymentTransitionTest, Windows JDK17 x64 (working tree before commit `ea9256f`) | `java … DeploymentTransitionTest` | exit0, 63 checks | `build/phase4-transition-x64.log` |
| ArtifactProvenanceTest, Windows JDK17 x64 | `java … ArtifactProvenanceTest` | exit0, 21 checks (Windows process provenance UNKNOWN by design) | `build/phase4-provenance-x64.log` |
| LegacyProvenanceClassifierTest, Windows JDK17 x64 | `java … LegacyProvenanceClassifierTest` | exit0, 33 checks | `build/phase4-legacy-x64.log` |
| Phase III regression with IV-1 code, Windows JDK17 x64 | `bash scripts/test-phase3-deployment.sh` | exit0 (counts unchanged) | `build/phase4-iv1-phase3-x64.log` |
| Linux VPS JDK17.0.20.1, ext4, dir `/tmp/romlogger-phase4-0e61251`, deps verified by `lib/test-dependencies.sha256` | `ROMLOGGER_EXPECT_DATA_MODEL=64 bash scripts/test-phase4.sh` | exit0: review 9/0 (F6 PASS), transition **64**, provenance **22** (`MATCHED_OPEN_ARTIFACTS_LINUX_SAME_USER`, `ARTIFACT_REPLACED_AFTER_START`), legacy **34** | `99fc0742dd01b14f1802f92fedc2357b02e45a77f087b604a6bbac0f4d359a92` |
| same | `bash scripts/test-phase3-deployment.sh` | exit0: binding17 / engine6 / readiness11 / backup32 / stages52 | `47e511f2690795bca7db7be9a1b195a38820728d056a297b2dda726abd2deef1` |
| Linux bound large | `ROMLOGGER_BOUND_LOAD_OBSERVATIONS=10000 java … Rlc2BoundDeploymentTest` | exit0, records=10003, 6 checks | `423c69fce1473aa07c955050203441146066dedd801de9ab43bf9ccedb16d0e6` |
| Server main `bb27cd1`, dedicated loopback test DB | `pwsh -File C:\Dev\tools\run-server-tests.ps1` | migrate upgrade, upgrade again and check exit0; pytest **57 passed, 1 warning** | `C:\Dev\RomLogger\logs\server-pytest-20261019-170508.log` |

Linux counts differ from Windows by design: Windows-only checks (F1d, junction fixture) and Linux-only checks (F6, `/proc` matching) are platform-gated and printed as SKIP/NOT RUN where unavailable.

## IV-4 Windows full matrix (fixed worktree `C:\Dev\RomLogger\phase4-final-0e61251`, head `0e61251`)

| Target | Command | Result | Log SHA256 |
|---|---|---|---|
| Acceleration batch: JDK17 x64 + real Zulu8 x86, all Bash suites including `test-phase4.sh` on both | `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1` | **exit0**, 2413 PASS lines. Phase IV x64: review 9/0, transition 64 (JVM=64), provenance 21, legacy 34. Phase IV x86: review 9/0, transition 64 (JVM=32), provenance 21, legacy 34 (JVM=32). Phase III both: binding18/engine6/readiness11/backup32/stages52. Pre-existing gates unchanged (Windows live reader BLOCKED, POSIX-only NOT RUN, symlink fixtures NOT RUN, PostgreSQL preview option NOT RUN) | `25d491aae5e7dd97369efd6ab47a74334d9b39acfc49d9870530df98f4c0802a` (`acceleration-20261019-170343.log`) |
| Real FastAPI/Uvicorn HTTP + invocation-owned PostgreSQL16 (actual Server checkout `bb27cd1`) | `ROMLOGGER_OWNED_PG_HTTP_ENABLE=1 … python scripts/test-rlc2-owned-postgres-http.py` | exit0: **35 checks**, owned cluster immediate stop/restart PASS, test ACK never changed Agent bytes/ACK state, only the owned cluster stopped; existing 55432 still listening afterwards | `4c80823520139375efb0db58de42fd2ee042b7d4158b81b46ef55c9bfd8ec3ae` |
| Bound large, JDK17 x64 | `ROMLOGGER_BOUND_LOAD_OBSERVATIONS=10000 java … Rlc2BoundDeploymentTest` | exit0, records=10003, JVM=64, 61 s wall (single run, not a benchmark) | `b24f684e3e11316cb0b4ede2e62b23535b11d78d5282cc5bfc1146a7e27464f6` |
| Bound large, Zulu8 x86 | same with Zulu8 | exit0, records=10003, JVM=32, 80 s wall | `f4bddd689a11c7897e7cf1f988ef226a991a16303dbc3f3d6465093bf45e372a` |

The Windows matrix ran the F6 test before the assertion was tightened (`04564c5`). On Windows it is SKIP anyway (no symlink privilege), and the tightened version was run on Linux, before and after the fix.
