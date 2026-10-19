# Phase VI validation evidence

Logs are ignored local artifacts; no raw payloads, credentials or test databases are committed. Source heads, platform, exits and SHA256 are appended after execution. Failed harness attempts are not PASS evidence.

## Frozen-source locations

| Code head | Local detached checkout / scope |
|---|---|
| c52d7ba0e46b466d602602cb4ca45d780d6154a9 | `../phase6-writer-acceptance`, writer/classification correction |
| 105f66d4b564491ce9f690bcbdd97c9bf8f64070 | `../phase6-drive-acceptance`, drive gate + Phase III/IV regression |
| 556f4ff249a699584ba7a8bf15290636057af001 | `../phase6-preflight-acceptance`, read-only preflight |
| d3941031af4d22c7317055c6d9439cf10e75e364 | `../phase6-final-acceptance`, broad baseline before final drive-entry ordering delta |
| 53f18a27663c0f7f9c8f2398fd3390190f5f5749 | `../phase6-entry-acceptance`, final runtime/tests and separately scoped entry/drive/preflight/transition/Phase IV checks |

All paths are relative to this session's implementation worktree `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-4\RomRaider_agent`. No sources are edited inside these detached checkouts. Later handoff commits do not change runtime/tests.

## Earlier fixed-head results (not relabelled as final-head PASS)

| Head/platform/command | Exit / result | Relative log | SHA256 |
|---|---|---|---|
| c52d7ba Windows17x64 `bash scripts/test-phase6-writer.sh` | 0;154+7 checks, actual SQLite child PIDs | `../phase6-writer-acceptance/build/writer-x64.log` | a816ffa5e9851e310394a0110a690fd5fb2f3cf3af048349378938ef2a50c828 |
| c52d7ba actual Zulu8x86 (model32 asserted), same `--run-only` | 0;154+7 | `../phase6-writer-acceptance/build/writer-x86.log` | 685ca0c6e6f837203a9cdc09d66b146ec65336843f8e8a410e463650402fcedf |
| c52d7ba Windows17x64 `bash scripts/test-phase5.sh` | 0;62 cases | `../phase6-writer-acceptance/build/phase5-x64.log` | 4858ee428ef2be0f211dd448c90dc25555ff928c565232cc4284bca6f88e9c62 |
| c52d7ba Linux17 `bash scripts/test-phase6-writer.sh` | 0;154+7 | remote `writer-linux.log` | 0c965a11c2dc3532531f547291e0803fab4fee360e33485ce3f7ac62efbed589 |
| c52d7ba Linux17 `bash scripts/test-phase5.sh` | 0;62 | remote `phase5-linux.log` | 016e707729cdc90f122b69b28a9deaf268455472b459d61b4eb749be633be76e |
| 105f66d Windows17x64 `bash scripts/test-phase6-drive.sh` | 0;36+1 checks | `../phase6-drive-acceptance/build/drive-x64.log` | 62d941c77dca2f1256844a22e1a153667ec73a21ceaaf09f233464cedfaa1e78 |
| 105f66d actual Zulu8x86 drive suite, `--run-only` | 0;36+1 | `../phase6-drive-acceptance/build/drive-x86-direct.log` | b78f56069e1861d99964fe63de6716cb1fbfe3cc47730d67c375f2333ee20729 |
| 105f66d Windows17x64 `bash scripts/test-phase4.sh` | 0 | `../phase6-drive-acceptance/build/phase4-x64.log` | 6dafc3efcccd9cda585a8fcbbea60441dc8b7ff55e8933d766a8b8046862e64a |
| 556f4ff Windows17x64 `bash scripts/test-phase6-preflight.sh` | 0;41 | `../phase6-preflight-acceptance/build/preflight-x64.log` | 384b419835843938a62903290ea36cc9ac9323b986aa5138c73b6cd9cf90f7eb |
| 556f4ff actual Zulu8x86 same `--run-only`, justified repeat after unknown drive refusal | 0;41 | `../phase6-preflight-acceptance/build/preflight-x86-repeat.log` | 6510b02ea5e4e3ad9122836dee52ad1e8916bb41d8117ebeb02bbc82d6536e69 |

Linux c52d7ba owned root `/tmp/romlogger-phase6-c52d7ba-bVm6Ea`; source archive4455577ed8b777dc52f8f523b4d8d0d73b021c9b75a72396bc48ae62e52bbada. Final d394103 archive076703ef713008112b1718a44f8ffae523b4e74ed8003e774945d21342594dd1, owned root `/tmp/romlogger-phase6-d394103-8UQIfi`. Archive/dependency hashes verified before extraction/execution. Linux17.0.20.1 x64; only fresh private roots on the pinned host, no service/container commands.

## Independent Server and syntax verification

Server checkout head056958dd1973106173e0e9b4c8664324c001ad8a, runtime unchanged fromd8c7a2f3b96ba278d269074dc40cca7741b4155b. `C:\Dev\tools\run-server-tests.ps1 -Repo <owned Server checkout>`: isolated55432 was ready before execution; migration upgrade twice/check exit0, pytest57 passed/1warning exit0. No test-cluster start/stop occurred. Agent-local logs `build/server-tests/server-migrate-20261020-080414.log` SHA25642419493a8b29a1104524db90bfbe2811cba1de9b5d10dfcc4d9b83a5a43ea24; `server-pytest-20261020-080414.log` SHA256f203b9145b012c860a9f0f595f0be46ab0f39d4214478bb3194bad3927141dde.

PowerShell Parser and Bash `-n` for Phase VI scripts PASS. YAML safe_load PASS using the existing Server test venv's PyYAML; global Python lacks that module (initial parser attempt NOT RUN, no package installation). This is syntax validation, not executed GitHub Actions.

## Initial RED and harness corrections

- Exact Phase V runtime plus test-only Phase6WriterTest: Windows17x64 `scripts/test-phase6-writer.sh`, log `build/phase6-writer-red.log`, exit1. Actual child PID2460 holds BEGIN IMMEDIATE without journal; classification still matches content but has no explicit writer_quiescence axis. This RED demonstrates a missing diagnostic, not production authorization.
- Phase V staging/snapshot runtime plus diagnostic delta: `scripts/test-phase6-writer.sh --compile --late-sidecar`, log `build/phase6-late-sidecar-red.log`, exit1. Actual PID8328 creates rollback journal after classification with empty originals; completion was accepted. Child rolled back normally; main v1 hash unchanged. This is the reproduced staging defect.
- First matrix harness attempts failed on test assumptions: switching journal mode was attributed to reader writes; normalize fresh fixture mode before hashing. WAL commit was incorrectly expected to preserve content; correct expectation is body-correspondence refusal after both journal and WAL commits. These runs remain FAIL, not final coverage.

## Persistent gates

- Windows actual Zulu8x86 first preflight at556f4ff refused `DRIVE_TYPE_UNPROVEN` during fixture enrollment. This is a preserved FAIL (fe898caf3c911e7821882b31836331984e1f5e2731c1a58adc279cb41af7fa0c), not PASS. Independent direct native x86 drive probe then passed36+1 checks; one justified preflight rerun at the same frozen code passed41. Product code performs no retry or relaxation of unknown-drive refusal.
- Transition exclusion RED: test-onlydd36511 against unchanged Phase V transition runtime; actual child PID8452 holds old Lane, abandon still creates its marker. `build/phase6-abandon-red.log` exit1, SHA256e7655a630a14aec22b187189b550f1ddcce54fca354480b2161cf5db006b0249. Final correction is separately frozen/tested; no double-activation claim.

Windows directory durability / running-process provenance UNKNOWN. Noncooperative writer absence WRITER_UNPROVEN; readiness BLOCKED. Real SMB, privileged Windows links, physical power loss, real ENOSPC, ECU/OpenPort/X270, production DB/Worker, production migration and new formal ACK NOT RUN/disabled. Root test.sh requires absent RomRaider.jar and is NOT RUN.

Actual Windows junction insertion was later executed successfully; the earlier privileged-link gap now refers to true Windows symbolic links, not these owned junction fixtures. Old Windows RLC1 full56 remains NOT RUN/BLOCKED as in the prior handoff; the synthetic acceleration batch is not root test.sh or that full legacy acceptance.
## Final broad baseline d394103 — Linux completed

First Linux run retained as FAIL: `phase6.log`, SHA25660f08c44d2221570a65b1d2667a2e6335c9e2a77b814e03b15afcd4c144b29b2. The SSH invocation's umask was0002; fixture Worker/Lane files created after initial privatization were group-writable, correctly causing UNSAFE_ACCESS in read-only preflight. No safety assertion was relaxed and no existing file was chmodded. The same source was rerun with invocation-local `umask 077`, creating fresh private fixtures and retaining the failed artifacts.

All following commands completed exit0 atd394103 in `/tmp/romlogger-phase6-d394103-8UQIfi`, with the source/dependency archive hashes above verified:

| Command (`umask 077`) | Result / remote log | SHA256 |
|---|---|---|
| `bash scripts/test-phase6.sh` | writer154+7; drive33 (native Windows cases NOT RUN); preflight41; transition59 plus child-lock helpers; parent10 (Linux symlink substitute); `phase6-private.log` | fb6dab96dcf076399637962b43e12b2f54b572e8e983f9616dd1a27a4a72bc10 |
| `bash scripts/test-phase4.sh` | existing review/transition/provenance/legacy regressions; `phase4.log` | b95680647e5789e542b080ee98618baee6ad3733438ef9fd08af1028f78a7630 |
| `bash scripts/test-phase3-deployment.sh` | binding/bound engine/readiness/copy/halt regressions; `phase3.log` | 77bc49fdf7aecbf800910405be0733e8660964d05ec802507e1d62ff93fe7b4d |
| `bash scripts/test-phase5.sh` | 62 cases; `phase5.log` | c8ae3f35278459183e025c197552a859a8ac8853f2e42d4015910678ac8a3135 |
| `ROMLOGGER_LOAD_OBSERVATIONS=10000 ROMLOGGER_LOAD_SESSIONS=10 bash scripts/test-rlc2-preview.sh` | large multi-session candidate + full preview/integration/JVM regressions; `preview-load.log` | 060ccee43f06c9c6ff2b9b6611e82ccbb282703d8744b4eb7eaef9e491fa5422 |
| `java -cp build/phase5-classes:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar net.neristal.rrbridge.prototype.ingress.Phase5ProvenanceTest --load10000` | v1 subset2, original/RLI2 10000records/10001receipts; `staging-load.log` | c83d46d31dcbe6be3957e6d17d2e928aa4293d291fc0156f8182141167036d21 |
| `ROMLOGGER_BOUND_LOAD_OBSERVATIONS=10000 java -cp build/phase3:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar net.neristal.rrbridge.prototype.ingress.Rlc2BoundDeploymentTest` | 10003records,6 checks; `bound-load.log` | 423c69fce1473aa07c955050203441146066dedd801de9ab43bf9ccedb16d0e6 |

These are d394103 broad results, not the later early-drive entry correction's results. That correction changes only drive proof placement/extraction, with later plan/layout checks unchanged. Separate focused final-head evidence follows.

## Final early-drive entry RED

Test-onlyfd958d0 againstd394103 runtime: `build/phase6-drive-entry-red.log`, Windows17x64, exit1;1 passed/5 failed, SHA25620c24eaac87fb59762c6f0dc6135d77bfc103c1fe92e833a14aa7086434c2329. A synthetic Remote probe was never consulted before missing-binding refusal; commit also created Worker/Lane/Publication files in the fresh owned fixture. No actual network drive was queried or accessed. Final correction adds root-only proof before chain/lock I/O; normal SYSTEM/later layout checks are retained.
