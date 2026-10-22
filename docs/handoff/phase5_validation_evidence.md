# Phase V validation evidence

PASS, FAIL/RED, NOT RUN and pending are distinct. Logs/archives/test databases are ignored local artifacts, never committed. Local implementation checkout: `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\RomRaider_agent`. Windows development VM DESKTOP-KVHGDS9, JDK17 x64 and actual Zulu1.8.0_504 x86 (`sun.arch.data.model=32`). Linux uses the pinned SSH helper and new owned `/tmp/romlogger-phase5-*` directories only; JDBC/SLF4J hashes are checked against the repository manifest.

## RED and correction evidence

| Tested implementation | Command / log | Result | log SHA256 |
|---|---|---|---|
| Agent baseline4f80c5e + test-only d052c56 | `bash scripts/test-phase5.sh`, `build/phase5-red.log` | exit1;9 passed/32 failed (accepted mutations) | `3739c11625af168ea2d0d6638d1e7521abe9c7ae236ed32ec0143776fb5aeebf` |
| Compiled69691a1 classifier | standalone Phase5PhysicalReplacementTest; `build/phase5-replacement-red.log` | exit1; byte-identical source copy accepted | `55f9d5b8a5c28b30cc95d25db20eb71e1d2a8bb4378a4a4888973fbdce0750ec` |
| Physical revalidation delta →16db2c4 | standalone physical test; `build/phase5-replacement-green.log` | exit0; physical replacement refuses | `e2005cff182c77c54d282bbcf5ad15a89bb12d0c33b8e6eee486100093b872ec` |
| Prior compiled classifier (69691a1) | Phase5StoredBodyTest; `build/phase5-body-red.log` | exit1;4 retained-hash/body inconsistencies accepted | `30824ee9fb44393c443daffa5a8cac26cfbccb2792eb016cad74dd11ba99728e` |
| Body delta →b4b909c | standalone body test; `build/phase5-body-green.log` | exit0;4 exact reasons/session refusals | `10e7416fb4bf3367b15a14c25319c5a1861967069c21e8d3d481efa8e1380356` |

Other non-PASS attempts: first test harness lacked reflective Windows classes (fixed before RED collection); first Linuxd87108a run refused unsafe group-write work-directory creation (fixed with atomic0700); initial HTTP lacked generated synthetic fixture (owned PG stopped); premature upload checksum refused incomplete archive before extraction; editing-checkout broad batch failed127 when Bash script changed during execution. These are recorded in the append-only worklog, never reclassified as PASS.

## Earlier fixed-head platform coverage (not final-head PASS)

Linux16db2c4 in `/tmp/romlogger-phase5-16db2c4-NHOevO`, source archive SHA256 `fc14c48a74d4712c493f124f3d7b8ee2677de9017fd6fa93c3811be5cf0450ee`:

| Command | Result | Remote log SHA256 |
|---|---|---|
| `bash scripts/test-phase5.sh` | exit0;56+physical replacement | `ce9f79f66827f92bdba06e2494502da339fe72a00f9775120c13a2d679a86701` |
| `bash scripts/test-phase4.sh` | exit0; review/transition/provenance/legacy | `b95680647e5789e542b080ee98618baee6ad3733438ef9fd08af1028f78a7630` |
| `bash scripts/test-phase3-deployment.sh` | exit0 | `47e511f2690795bca7db7be9a1b195a38820728d056a297b2dda726abd2deef1` |
| `java -cp build/phase5-classes:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar net.neristal.rrbridge.prototype.ingress.Phase5ProvenanceTest --load10000` | exit0; v1 subset2; candidate10000 records/10001 receipts; independent reverify | `c83d46d31dcbe6be3957e6d17d2e928aa4293d291fc0156f8182141167036d21` |
| `ROMLOGGER_LOAD_OBSERVATIONS=10000 ROMLOGGER_LOAD_SESSIONS=10 bash scripts/test-rlc2-preview.sh` | exit0; preview/integration/schema/snapshot/JVM faults, multi-session exact replay | `e3994f7a7dcc2d2a0f8d154259c94facd82cbfa5c1f9d502c9614b627bfadf0f` |

## Server and actual HTTP

Server runtime head `d8c7a2f3b96ba278d269074dc40cca7741b4155b`, `pwsh -NoProfile -File C:\Dev\tools\run-server-tests.ps1`: upgrade twice/check exit0; pytest57 passed,1 warning, exit0. Logs `C:\Dev\RomLogger\logs\server-migrate-20261019-183510.log` SHA256 `42419493a8b29a1104524db90bfbe2811cba1de9b5d10dfcc4d9b83a5a43ea24`; pytest log `server-pytest-20261019-183510.log` SHA256 `afbd32e89592f7fa484547376b9dda9a545c7f11fb43f26b7c5f749ddcd38a4f`.

Actual `scripts/test-rlc2-owned-postgres-http.py` with invocation-owned PostgreSQL16 and real local Server passed35 checks, exit0 (`build/phase5-owned-http-final.log`; final hash below). It generated/stopped only its own build-directory cluster and retained unchanged Agent candidate ACK bytes. It covered dropped committed response/retry, actual owned PG stop503/noACK/restart and immutable persisted records. The first fixture came from the earlier mixed editing batch; do not label it as final-head Agent coverage. A frozen b4b909c repeat passed35/0 as recorded below.

## Broad regression baseline b4b909cc3111c11e58f35b7600e47bea1d6aadfa (before the final output-name guard)

Frozen Windows checkout: `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\phase5-acceptance-final`. Command `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1`. Completed exit0; see the final broad-batch table below.

Final Linux archive `build/phase5-b4b909c.tar`, SHA256 `079f6778f891f03533b005ce4489ccd5b278103a89edabdc2d0abee467d4611e`, owned root `/tmp/romlogger-phase5-b4b909c-8rPW4i`. Completed exit0; exact hashes are recorded in the b4b909c milestone table below.

## CI and limits

Agent PR80 journal run39232118574 atd87108a has4 jobs with zero steps; product39232118579 and HTTP39232118549 also fail before execution. Latest head will be checked again without redispatch. CI NOT RUN, not code-test failure/PASS. Supplied Phase V policy prohibits merging while CI is unexecuted.

Windows directory durability UNKNOWN; Runtime.halt is not physical power loss. Real ENOSPC/ECU/OpenPort/X270/production migration/formal new-path ACK NOT RUN/disabled. Root `test.sh` requires absent lib/RomRaider.jar and is NOT RUN. Other deferred cases are explicit in the report/followups. Existing loopback PG55432 remained ready; no existing VPS/Docker service was modified.

### Final b4b909c focused/Linux/HTTP milestones

| Environment/command | Exit / output | log SHA256 |
|---|---|---|
| Windows JDK17 x64 `bash scripts/test-phase5.sh`, `build/phase5-b4b909c-x64.log` |0;56+1+4=61 cases | `49cc7dc120c656f90d086a6aa314a3cb92caf101f106f0b08aad1d8eba8f9876` |
| Actual Zulu8x86 `bash scripts/test-phase5.sh --run-only`, `build/phase5-b4b909c-x86.log` |0;61 cases; shell asserts data model32 | `a3293fbe6d6d2d78879c041e6caade3d7709dec701ee58c8003a30af6450efcd` |
| Actual x86 Phase5ProvenanceTest `--load10000`, `build/phase5-b4b909c-load-x86.log` |0;10000 records/10001 receipts; v1 subset2 | `5735df59f793962a2eb771bcbda63faffdfa39970022d928d8ff8583d23caaf9` |
| Frozen b4b909c `scripts/test-rlc2-owned-postgres-http.py`, `phase5-acceptance-final/build/phase5-b4b909c-owned-http.log` |0;35 actual HTTP checks, owned PG stop503/noACK/restart | `4c80823520139375efb0db58de42fd2ee042b7d4158b81b46ef55c9bfd8ec3ae` |
| Linux `bash scripts/test-phase5.sh`, remote phase5.log |0;61 cases | `bad76b7f11da7d3ae09ba052a57d70731f7241ad8efc4f8f411d0bd6d0cf94b7` |
| Linux `bash scripts/test-phase4.sh`, remote phase4.log |0 | `b95680647e5789e542b080ee98618baee6ad3733438ef9fd08af1028f78a7630` |
| Linux `bash scripts/test-phase3-deployment.sh`, remote phase3.log |0 | `47e511f2690795bca7db7be9a1b195a38820728d056a297b2dda726abd2deef1` |
| Linux Phase5ProvenanceTest `--load10000`, remote phase5-load.log |0;10000 records/10001 receipts; v1 subset2 | `c83d46d31dcbe6be3957e6d17d2e928aa4293d291fc0156f8182141167036d21` |
| Linux `ROMLOGGER_LOAD_OBSERVATIONS=10000 ROMLOGGER_LOAD_SESSIONS=10 bash scripts/test-rlc2-preview.sh`, remote preview-load-regression.log |0; large load59 checks,11sessions,10100records/10111receipts/22admissions/11bindings; further engine/schema/snapshot/JVM regressions also pass | `f0a895e7f6d5463ac25211ed02456575682dc7e58709fdd92f30a73dab2222c9` |

Linux final root `/tmp/romlogger-phase5-b4b909c-8rPW4i`, JDK17.0.20.1 x64/Linux6.8.0-139/ext4 (findmnt); all archive/dependency hashes checked before execution. Local summary log `build/phase5-linux-b4b909c.log` SHA256 `54dc66af2ce106724d23d5efbbe4d2696170710e57d962b191b560d44b9c8312`. Frozen Windows full batch completed exit0; see its completion entry below. The obsolete16db2c4 batch was intentionally stopped and is not PASS evidence.

## Final guard head928d43fb96452b4e4f989352d8627549c1e23a4d

The runtime diff from b4b909c is exactly one early dedicated-output-name check in stageIsolated; source diff inspected directly. Existing valid-name behavior is exercised by the entire focused suite again. The final negative fixture actually accepted `product.db` on b4b909c (RED exit1, `build/phase5-name-red.log` SHA256 `f8eef2fb6a2c06902095251a6bd740642d71306cfee0584b86f1de176ba09e91`); restored guard rejects before staging writes (GREEN exit0, `build/phase5-name-green.log` SHA256 `2859fcda58e56988b80984f45e05eb97267b0854c6f356341e5b72b2313df2d6`). An initial discriminating assertion used a missing input and was tightened to a fully valid synthetic source before recording this acceptance RED.

| Command / platform | Exit / cases | log SHA256 |
|---|---|---|
| `bash scripts/test-phase5.sh`, Windows JDK17x64, build/phase5-final-guard-x64.log |0;56+1+4+1=62 | `e7e86860ac0aef3cf474474234d211790eae838660470387d87270435f5d6d1b` |
| `bash scripts/test-phase5.sh --run-only`, actual Zulu8x86, build/phase5-final-guard-x86.log |0;62, asserted JVM32 | `32700999e3ddaae2be9bcfeab62fa903beb10e9be6693ec2493e5e8ec53f46ea` |
| `bash scripts/test-phase5.sh`, Linux17x64, remote phase5.log |0;62 | `424f21ceb341a41fc0fe88ba44d413ec2c42d843529f694ee94a4081e6f544dd` |

Final Linux root `/tmp/romlogger-phase5-928d43f-6h49B6`, archive SHA256 `343dc1adac11c9f65b787492e5ce4eced8a4f049dfb5bf1e2ead953df40aebcd`. Archive and dependency hashes verified before compilation. Local summary build/phase5-linux-final-guard.log SHA256 `110416d8033fe45a28bdc4628e70d365ee45fb5250c788cd5fb772be9963194c`.

Broad Windows/Linux regressions, large load and actual HTTP are explicitly reported at b4b909c; they have NOT been relabelled as runs at928d43f. The final guard delta receives the full62-case focused matrix; the complete broad matrix is not repeated for this one entry guard. Root test.sh/physical tests and remaining P1/P2 limitations remain NOT RUN as described above.

## Frozen broad Windows batch completed (b4b909c only)

- Checkout `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\phase5-acceptance-final`, detached `b4b909cc3111c11e58f35b7600e47bea1d6aadfa`, clean before/after. Command `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1`, exit0.
- Log `build/acceleration-20261019-185632.log`,158308bytes,2533 lines starting PASS, SHA256 `769fac249566bf33d38bfdddeef4f91fe325ca2e71d7164b44c7f94e2cd11504`. Created18:56:32+09, last write19:33:36+09 (about37min04; observational). Console `build/phase5-final-console.log` SHA256 `7eda92ccb71c98f1fa1cc159e1f891af44cae798fead7389766ad19fa13773d1`.
- Actual x64 and Zulu8x86: Phase III/bound/copy/ownership/product/spool/ingress/SQLite/native/ledger/diagnostics/preview/integration regressions pass. Phase IV per architecture review9, transition64, provenance21, legacy34. Phase V at this baseline per architecture56 main +1 physical replacement +4 stored-body cases=61. The final output-name guard was tested separately at928d43f with62 cases on all3platforms, not falsely included in this b4b909c batch.
- Batch optional direct PostgreSQL preview was NOT RUN (no -Postgres); actual owned PostgreSQL/FastAPI HTTP35 and Server migration/pytest were separately executed and passed as recorded above. No optional check is inferred from another head's historical PASS.
- Existing loopback PG55432 ready after completion; own final HTTP cluster has no postmaster.pid and was stopped by its own helper. Original main checkouts preserved. Physical power loss/ENOSPC/ECU and remaining P1/P2 cases remain NOT RUN.
- CI definition only: full Windows timeout ceiling60min, single-OS journal matrix30min, because observed local full matrix exceeded the old15min ceiling. No runner execution verifies the new ceiling yet; no billing change or manual dispatch. Final runtime/test source remains928d43f.
