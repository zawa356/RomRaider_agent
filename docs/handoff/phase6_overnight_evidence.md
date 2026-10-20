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
## Owned actual HTTP at broad baseline d394103

`C:\Dev\tools\venv-server\Scripts\python.exe scripts/test-rlc2-owned-postgres-http.py` with explicit owned-cluster opt-in, fixed PG16 binaries and the dedicated Server checkout passed35 checks, exit0. `../phase6-final-acceptance/build/owned-http-d394103.log` SHA2564c80823520139375efb0db58de42fd2ee042b7d4158b81b46ef55c9bfd8ec3ae. Actual FastAPI/Uvicorn HTTP includes a dropped committed response/retry, own PG immediate-stop→503/noACK→restart, persisted canonical records, and unchanged Agent candidate bytes/ACK state. Only the new invocation-owned build-directory cluster was stopped; final stop completed. Shared55432 was never stopped. The Agent fixture came from d394103; no final53f18a2 HTTP coverage is claimed or needed for the drive-entry-only runtime delta.
## Final runtime/test head53f18a2 — Linux complete

Fresh pinned-host root `/tmp/romlogger-phase6-53f18a2-j7RBxG`; source archive29a3f6e5f4ddae52043ed12446f8671cf36abbee3b0389b2dc66ab79aa2f995d verified before extraction, then repository dependency hashes verified. Explicit invocation-local `umask 077`, ROMLOGGER_EXPECT_DATA_MODEL=64, actual OpenJDK17.0.20.1/Linux x64. Local command log `../phase6-entry-acceptance/build/linux-53f18a2-driver.log`.

| Command | Exit/result | Remote log SHA256 |
|---|---|---|
| `bash scripts/test-phase6.sh` | 0; writer161, drive33, preflight41, transition59 + child helpers, parent10; Windows native/early-drive cases explicitly NOT RUN | phase6.log9dea8b03863af0d0728a99615aec9309cd3e5743eff51efd87921b52abc526aa |
| `bash scripts/test-phase4.sh` | 0; final runtime regression with actual model assertion | phase4.log520d8d20af5e59a0b1f09b6d77d78d04e1d29c6065f7e7bb1e715806adb6a13a |
| `bash scripts/test-phase3-deployment.sh` | 0 | phase3.log77bc49fdf7aecbf800910405be0733e8660964d05ec802507e1d62ff93fe7b4d |
| `bash scripts/test-phase5.sh` | 0;62 cases | phase5.log44f27f5d59b1c1413ff81823199821e3431305bfc793abdf03b7dd7437f531fb |

No runtime/test sources changed after53f18a2. Final Windows focused tests use that same head; separate d394103 broad Windows batch remains at its own fixed source. Large loads/actual HTTP remain explicitly atd394103 because this final delta only extracts/reorders Windows drive checks.

## CI execution and time ceiling

Latest inspected runs: Agent80=39238610241,81=39337678090,82=39338106529,83=39338630078,84=39339586070,85=39340656457; Server62=39238624521,63=39340974721. Every observed job has steps0. CI NOT RUN (runner not started), not a code-test failure or PASS. Final PR86/doc heads are checked again at publication; no billing changes, dispatches or merges.

The d394103 Windows batch's x64 section alone reached approximately28minutes before starting actual x86. With the added Phase VI suites and final drive-entry guards, raise the supplementary single-OS matrix ceiling30→60minutes (full Windows x64/x86 ceiling stays60). This is a maximum allowance only; YAML safe_load PASS using existing Server venv. Runtime/test head stays53f18a2; CI has not executed.
## Final53f18a2 Windows17x64 / actual Zulu8x86 focused acceptance — complete

Detached `../phase6-entry-acceptance`, exact head53f18a27663c0f7f9c8f2398fd3390190f5f5749, clean after execution. Each x64 script compiled Java8 bytecode under JDK17; the x86 scripts used `--run-only` under actual Zulu1.8.0_504 with ROMLOGGER_EXPECT_DATA_MODEL=32 asserted. Driver completed exit0 after every command below returned0. Native Windows Fixed drive controls actually executed; no SMB mapping/access.

| Script / cases | x64 log SHA256 | actual x86 log SHA256 |
|---|---|---|
| `test-phase6-drive.sh`,36+1 | drive-x64.log62d941c77dca2f1256844a22e1a153667ec73a21ceaaf09f233464cedfaa1e78 | drive-x86.logb78f56069e1861d99964fe63de6716cb1fbfe3cc47730d67c375f2333ee20729 |
| `test-phase6-drive-entry.sh`,6/0 | drive-entry-x64.log81c1d0655f1a2184abb8e302ef08b45ebee4c589326b639d6a05971d8f66544c | drive-entry-x86.log2810d66fdd340bd8c20706a15dee98431abda8197689c789be773dba31c54c99 |
| `test-phase6-preflight.sh`,41 | preflight-x64.log0fbfce6e70ce27d99cfb467ff739014cf8a89defe690a03fcb5f4b46446c4763 | preflight-x86.loga10541d57166e2c89eb32812ad0505cf14e4cb37daf9c68203ab6a35cd5440e9 |
| `test-phase6-transition.sh`,59 + child-lock helpers | transition-x64.logaf49108c419280462626422fefda191606c98bdc93e9f73caaaf870c2da9924e | transition-x86.log28b79ee841897ec27473f5f7d4e5d7b6331df47ca9f3eb0893d4d62d5bb754fc |
| `test-phase4.sh`, complete focused regression | phase4-x64.log6dafc3efcccd9cda585a8fcbbea60441dc8b7ff55e8933d766a8b8046862e64a | phase4-x86.log779e253fc58585b7d51b1d90a80964a539552e40d4fba7ce6197c788a55b2ad2 |

All names above are relative to `../phase6-entry-acceptance/build/`. Linux final53f18a2 Phase VI/IV/III/V also completed as above. The d394103 full Windows batch is still separately awaited, never substituted with this final-head focused result.

Large d394103 Linux workload details confirmed from actual metrics: observations10000, sessions11 (one large+10small),59 checks. Tests assert10100records/10111receipts/11bindings/22explicit admissions, exact replay and ACK-ineligible rows. Snapshot/project/commit1812ms was one observation only, not a throughput or durability guarantee.

Final Bash Phase VI, PowerShell wrapper and YAML parser log `build/phase6-final-syntax.log` SHA256fd2ed2b3c3cf2e8a1ac41084e50ec3ce396c2aa38c79511031f315cba80ed242. No runtime/test difference from53f18a2; later changes are handoff/CI maximum duration only.
## Broad d394103 Windows full x64 / actual x86 + staging10k — complete

Frozen detached `../phase6-final-acceptance` atd3941031af4d22c7317055c6d9439cf10e75e364. `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1` completed **exit0**, both real architectures,3181 `PASS ` lines. Canonical log `build/acceleration-20261020-081038.log`,196297bytes,60.371minutes wall time (concurrent focused verification also ran on this VM), SHA256a52a1a9aa70be4b78d16e8ea0052d4a891bce05d9374d014ce69fdc92f16aa43. Driver log `build/final-windows-driver.log` SHA256c62076fd225422e724d11f4c9aff2d92ecad1b44cd58a6851f1cfdccf98e02ae. Every x64/x86 suite returned0 before the wrapper completed; no runtime/test sources changed during execution.

Phase VI included writer154+late-journal7, drive36+binding1, preflight41, transition59 plus child-lock helpers, staging-parent10 on each architecture. Actual Windows junction fixtures succeeded on both; new sources were not substituted with synthetic reparse replies. Final early-drive6 is separately proven at53f18a2, not retroactively part of this d394103 batch.

After the full batch, actual Zulu8x86 `java -cp build/phase5-classes;lib/sqlite-jdbc.jar;lib/slf4j-api.jar;lib/slf4j-nop.jar;build/identity-deps/jna-5.14.0.jar net.neristal.rrbridge.prototype.ingress.Phase5ProvenanceTest --load10000` returned0: v1 historical subset2, original/RLI2 10000records/10001receipts, independent verification/reverification, actual JVM32. Log `build/load10000-x86.log`, SHA2565735df59f793962a2eb771bcbda63faffdfa39970022d928d8ff8583d23caaf9.

**Conditional/unexecuted cases remain explicit despite batch exit0:**

- Full25-generation concurrent publish progress was BLOCKED because Windows refused replacement; exact old/staged/original bytes were preserved and no repair was attempted. This is not a successful progress/availability guarantee.
- Old Windows RLC1 live read / default-provider fileKey watermark integration remains BLOCKED/unsupported. Expected fail-closed behavior is exercised; product live ingestion stays disabled.
- True symbolic-link fixtures were SKIPPED/NOT RUN for missing OS privilege/provider support. Owned junctions are a distinct executed case, not proof of true-symlink coverage.
- POSIX dev/ino suites are NOT RUN on Windows. Separate Linux native-fixture coverage is recorded above.
- Optional direct PostgreSQL preview was NOT RUN (`-Postgres` not enabled). Actual owned HTTP/PostgreSQL35 and isolated Server57 ran separately and must not be conflated with that optional mirror check.
- Root test.sh, physical power loss, real ENOSPC, ECU/X270/OpenPort, real SMB, production migration and new-path formal ACK remain NOT RUN/disabled. Windows directory durability/process provenance UNKNOWN.

The observed full Windows workload exceeded the previous60minute job ceiling even before the final drive-entry delta. Final CI maxima are **single-OS60minutes / full Windows90minutes**, YAML syntax PASS. This supersedes the earlier interim note that full Windows remained60; no extra workload or runner dispatch was added and no CI PASS is claimed.
## Publication CI / preservation checkpoint

At Agent handoff head7c36a076d30dc941f1b32a793a2776117a846db4 (runtime/tests53f18a2 unchanged), latest HTTP39346709425, product39346709443 and ingress39346709431 jobs all have steps0 and failure before runner execution. Server final docs head526e77d4bb2dc0fb0c995e2f6ae09951d0ce8d0e run39346425186 also has two jobs with steps0. CI NOT RUN; no merge, retry dispatch or billing change. Subsequent publication-only documentation does not change these runtime/test results; always fetch fresh CI state when resuming.

Original Agent main4f80c5e and Server maind8c7a2f stayed clean at the same commits. Existing Agent PR80head7da3837 and Server PR62head056958d stayed OPEN/unmerged. Previous Phase V worktree remained clean and untouched. New implementation/Server worktrees and detached acceptance worktrees were clean after commits/execution. No runtime/test diff exists between final53f18a2 and the handoff commits. Existing Worker/Once/Spool/Sender source diff from PR80 is empty; Server diff from PR62 is only implementation_progress.md. Shared isolated PG55432 was ready after owned HTTP's final stop. No existing service/container/real-car operations were issued.
