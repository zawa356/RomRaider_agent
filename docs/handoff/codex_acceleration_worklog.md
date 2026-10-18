# Codex acceleration worklog — 2026-10-18

## 2026-10-18 11:37 JST — START / Phase A review then B diagnostics

- User GO received after preflight. Agent base `a6e10cc8e01c74e79d18382e64552c488894eef2`; Server base `1dd425808b8611135beb0bb984cc8df212936f1e`.
- Branch `feat/acceleration-diagnostics`; clean initial trees; both open PR lists empty; personal noreply author confirmed. CloudPC MCP unavailable; local Windows and pinned SSH are available.
- Preflight: Windows JDK17 x64 / Zulu8 x86 installed; real SQLite ledger 27 + rotation 43 PASS on Windows x64; isolated PostgreSQL16.15 read-only query PASS. Docker restricted disposable Java container PASS, removed. VPS Ubuntu24.04 SSH/password sudo PASS; Java/SQLite provisioning pending. VPS reinstalled host pin updated with prior pin backup per explicit user authorization.
- Plan: short independent code review of RLC2/ledger/manifest; fix critical findings with RED/GREEN; implement read-only Supervisor diagnostics with stable codes/JSON/CLI and real-DB tests; continue with isolated adapter/E2E, faults and CI.
- Gates: directory durability UNKNOWN on Windows; no production ACK/sending, ECU/X270, original deletion/repair, existing Docker service changes or production DB writes. Runtime.halt is not physical power loss.
- Next: inspect `RotationManifestPrototype`, `WatermarkV2Prototype`, `WatermarkLedgerPrototype`, existing product ingress interfaces; implement diagnostics using read-only SQLite connections (never construct mutating ledger just to inspect).
- Tests after GO: NOT RUN yet. No implementation PR yet.

## 2026-10-18 11:51 JST — Phase B RED: SQLite mode=ro side effects
- Initial diagnostics API/CLI and real SQLite scenarios implemented. Windows x64 RED: read-only SQL connection creates WAL/SHM companion files, violating zero-file-mutation test. No production/original data touched; test fixture only. Fix will require a quiescent SQLite snapshot, reject WAL/journal companions, and use immutable read-only mode; active DB fails closed.
- Phase A targeted review found no critical regression requiring a core fix; prior limitations retained. Next: fix diagnostic read-only DB opening, extend DB-active rejection test, rerun x64/x86/Linux.


## 2026-10-18 12:00 JST — Phase B x64 GREEN / other environments running
- Fixed RED SQLite side effect with immutable closed snapshot and WAL/SHM/journal refusal. Test fixture error (zero/incorrect-length identity is invalid rather than mismatched) corrected to a valid different native identity.
- Windows JDK17 x64: diagnostics 34 PASS, real SQLite/file-byte preservation/CLI/quota. Zulu8 x86 and VPS Linux running. Java17, SQLite3 and python3-venv provisioned on VPS successfully; no production deployment.
- CI adds explicit Linux/Windows and Zulu8 x86 diagnostics steps. API and caveats documented in acceleration_diagnostics.md; ADR D-048 and interfaces/history/progress updated. Next: x86/Linux results, PR+CI, normal merge; then isolated adapter mapping design.


## 2026-10-18 11:50:32 +09:00 — Phase A/B COMPLETE; Phase C START
- Agent PR43 normal merge 77089a368d3c562bb5b5e8f01ae9e4e6ba90c606; PR CI39091138681/39091138687 and main CI39091295337/39091295292 success. Inspected actual Linux/Windows diagnostic steps and actual Zulu8 x86 step. Local results x64 34, x86 35, VPS34; existing VPS ledger27+rotation43. Server regression57 passed.
- Server progress PR50 merged e056c0590cf61413b9e237cc7d9469a6a5578285; main CI39091434142 success.
- Clock correction: earlier manually entered 11:51 and 12:00 headings were entry errors; those checkpoints occurred before the observed 11:43:58 JST reading, in the recorded Git sequence. Subsequent headings use the machine clock.
- Phase C base Agent77089a3 / Servere056c05; branch feat/rlc2-product-projection. Plan pure opt-in synthetic projection using shared existing v1 row converter, strict RLC2/ledger alignment, manifest-order metadata, stable IDs and source receipts. No output to product spool or HTTP. Phase D will separately persist to quarantined preview tables, with no ACK column accepting true.
- Next: extract a no-I/O conversion sink from IncrementalTsvBridge; implement Rlc2ProductAdapter and native/SQLite cross-platform tests. Existing production behavior must remain equivalent.


## 2026-10-18 11:52:39 +09:00 — Phase C implementation checkpoint / tests running
- Added no-I/O ProjectionSink to existing IncrementalTsvBridge and reused its exact apply/ID/profile logic. Default production sink still delegates to DurableSpool, with existing public constructors unchanged.
- Rlc2ProductAdapter requires explicit enableSynthetic and synthetic-/TEST_ input. Requires clean diagnostics plus exact persisted marker/highwater alignment. Projects manifest-order metadata, stable v1 JSON and original source receipts in memory only. Rejects payload session/sequence reset, claim conflicts, changed snapshots and capacity overflow; no sender/outbox.
- New real-platform scenario compares projected IDs/JSON/profile against independently invoked existing TSV importer, plus rotation/metadata/tail/ledger/gating cases. Windows tests running; Linux/x86 pending. Next: verify results and product regression, document source-claim migration boundary, push PR.


## 2026-10-18 11:54:40 +09:00 — Phase C local GREEN
- Projection: Windows17 x64 20, actual Zulu8 x86 21, VPS Linux17 20 checks PASS. Diagnostics remain 34/35/34 PASS. Full scripts/test-product-v1-spool.sh exited0 on Windows and VPS Linux. No Server transport tests were added to this new path; ACK remains disabled.
- Existing vs new model/mapping documented in rlc2_product_projection.md; interfaces/history/progress and D-049 updated. Product single-source claim migration is explicitly BLOCKED, while pure preview is implemented.
- Next: PR CI, review diff, normal merge and main CI; then Phase D quarantined SQLite persistence with atomic receipts/cursors and separate-JVM E2E/faults.


## 2026-10-18 12:01:11 +09:00 — Phase C COMPLETE; Phase D START
- Agent PR44 merged b862651902ad25dc86a3233c00df77dddcaa3919; PR CI39091762536/39091762488 and main39091939531/39091939477 success. Actual Windows/Linux projection+diagnostics and Zulu8 x86 steps ran. Existing real-Server gated workflow succeeded but actual E2E SKIPPED (no private Server read secret).
- Server progress PR51 merged3d574d52ce716b5c2733ba3bc9f77414f1bf58bd; main CI39092090529 running (docs only). Full local product suites PASS; projection20/21/20.
- Phase D base Agentb862651 / Server3d574d5; branch feat/rlc2-preview-store. Build dedicated named .rlc2-preview.db storage with application ID and preview-only schema guard. One transaction for manifest history, lane/source claims, raw receipts, v1 projections and highwater. No v1 tables and CHECK ack_eligible=0. Add restart/CLI E2E and faults on synthetic fixtures; PostgreSQL direct isolated verification later.
- Next: implement preview store and opt-in CLI, real SQLite tests including idempotency and original preservation; retain UNKNOWN durability and no Server ACK.


## 2026-10-18 12:04:44 +09:00 — Phase D implementation checkpoint / SQLite tests running
- Added Rlc2PreviewStore with dedicated extension/application ID/schema ownership check before writable access. Dedicated preview-only tables retain manifest bytes/history, source/lane claims, exact receipts/highwaters and shared v1 profiles/records in one IMMEDIATE transaction. Records enforce ack_eligible=0; no v1 table names.
- Immutable replay verifies prior row counts and bytes, refusing deleted/tampered receipts or records. Added explicit synthetic CLI (fixed JSON status, no exception text/path disclosure), Windows test-only native wrapper, real CLI-process E2E and rollback/claim/schema/preservation tests.
- Tests running Windows x64. Next: fix failures, x86/VPS, wire CI, isolated PostgreSQL verification without Server ACK.


## 2026-10-18 12:08:22 +09:00 — Phase D cross-platform RED / PostgreSQL local PASS
- Windows SQLite preview x64 31 and actual Zulu8 x86 32 PASS. Local PostgreSQL16.15 twice committed/reconnected: two sources/four receipts/one profile/three records, exact JSON/originals, no duplicates, CHECK blocks ACK, injected failure rollback PASS. Actual Server ProfileRevision/Batch model validation PASS locally; no endpoint invoked.
- Added guarded PostgreSQL fixture test and CI job; 3 destination guard tests PASS (unsafe endpoints/overrides/opt-in rejected before fixture/database access).
- VPS Linux preview reached 27 checks then separate-JVM CLI failed: launcher is referenced by class name string, so javac did not compile POSIX CLI implicitly. Windows wrapper compiled it transitively. This is a harness failure; next fix explicitly compiles Rlc2PreviewCli on both OSs and reruns Linux.
- Server PR51 main CI39092090529 success. Next: fix Linux launcher compilation, CI proof, docs/PR.


## 2026-10-18 12:18:17 +09:00 — Phase D COMPLETE; Phase E START
- Agent PR45 merged a4a5a0ba06d8cd908d7908cd5e08f5f40891518a; PR39092722886 (all four jobs including actual PostgreSQL) /39092722876 and main39092940711/39092940692 success. Raw logs confirm preview34 Linux/Windows and35 actual Zulu8 x86. Server PR52 merged94b05a67ca5154916e7f9d9eddeb3f607eaadbda, main39093054991 success.
- Phase E base Agenta4a5a0b / Server94b05a6; branch fix/rlc2-preview-crash-recovery. Hypothesis: abrupt pre-commit exit leaves a rollback journal that the output-store's overly strict immutable preflight may refuse. Need RED separate-JVM test first; recovery must be limited to a positively identified preview DB, never input ledger/original repair.
- Plan four transaction-stage halts, exclusive lock/DB failure, competing CLI processes and exact replay/no duplicate/no ACK proof. Existing rename/manifest/stale/UUID/CRC/quota faults remain in regression; Runtime.halt remains non-physical-power-loss.
- Next: implement separate-JVM fault harness and reproduce before fixing recovery gate.


## 2026-10-18 12:20:07 +09:00 — Phase E RED reproduced (Windows17 x64)
- Added four-stage separate-JVM halt, competing CLI, exclusive lock and real SQLite ABORT-trigger fault tests. Existing preview34 checks PASS, then child halted at CLAIMS_WRITTEN and store constructor failed with active database requires closed checkpointed snapshot.
- Root cause: output-store preflight reused immutable input-ledger quiescence, rejecting SQLite's own rollback journal after abrupt exit. Original files preserved; no ACK or partial-success claim. New fault test opens store before any direct SQL count so engine recovery cannot hide this bug.
- Next: preserve strict input diagnostics, permit SQLite-managed rollback only for output with verified SQLite magic/RLP1 header and safe journal, verify owned schema after recovery. Add foreign product DB+journal negative case and rerun all fault stages.


## 2026-10-18 12:21:40 +09:00 — Phase E Windows x64 GREEN
- Fixed output reopen gate: only a regular non-symlink rollback journal plus SQLite magic/RLP1 header permits mode=rw SQLite-managed recovery, followed by owned-schema verification. Foreign WAL/SHM or foreign application ID refuse. Input ledger diagnostics remain immutable/quiescent; no journal is manually removed.
- Windows17 x64: existing preview34 PASS plus new faults36 PASS (four stage halts, all-or-none receipts/records, exact replay, competing CLI JVMs, exclusive lock review/no ACK, real insert ABORT with whole six-table rollback, original-byte preservation, foreign product DB+journal unchanged). RED778757d retained.
- Next: actual x86/VPS, CI and manual dry-run recovery documentation. Physical power loss/real ENOSPC NOT RUN.


## 2026-10-18 12:31:54 +09:00 — Phase E COMPLETE; Phase F START
- Agent PR46 merged9caa2486d7b4a9e256b68eb02835ed90cf09e0f7; PR39093449838/39093449961 and main39093698538/39093698530 success. Server PR53 merged2aaaeda93f39260c97534f52d463ace663b209fd, main39093791658 success. Fault36/37/36 and preview34/35/34 validated; no physical power loss/ENOSPC claim.
- Phase F base Agent9caa248 / Server2aaaeda; branch feat/acceleration-test-automation. Add hash-verified Java dependencies, locked cp312 Win/Linux PostgreSQL driver wheels, pinned observed PostgreSQL image digest, Windows one-command batch with optional guarded PG/model validation, artifact guard and duplicate push/PR CI cancellation.
- Preparation: isolated Windows Python venv installed psycopg3.3.6/binary3.3.6/typing-extensions4.16.0/tzdata2026.5 with full wheel hashes; deliberately wrong hash rejected before install. Wheel files remain workspace-only. Docker image digest observed from successful PostgreSQL CI, not guessed.
- Next: implement automation and run the actual complete Windows batch with local disposable PG; CI verifies Linux hash lock and all test steps.


## 2026-10-18 12:34:33 +09:00 — Phase F implementation checkpoint / full Windows batch running
- Central SHA256 manifest verifies cached/downloaded SQLite/SLF4J jars; fetch-deps uses staged checked publication. CPython3.12 Win/Linux x64 driver lock has hashes for both native wheels and pinned transitive packages. PG CI image pinned to observed digest ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d.
- Added configurable Windows PowerShell batch: compile Java8-compatible code on JDK17 x64, actual Zulu8 x86 runs, guarded optional test PG/Server-model checks, ignored build log and no tracked artifacts. Environment restored afterward; secrets stay in inherited process environment.
- CI cache key follows hash manifest; push/PR runs share head-branch concurrency to cancel duplicates; generated artifacts/source mutations checked. Full actual Windows batch running with local test PG and actual Server models.
- Next: batch result, wrong-valid-JAR cache negative verification, CI, docs/PR.


## 2026-10-18 12:40:59 +09:00 — Phase F RED: YAML quoting and existing Windows concurrency harness
- Full Windows batch reached existing RLC2 concurrent reader/publisher scenario, then MoveFileExW error5. Preserved synthetic fixture: old marker gen24/seq25/offset1178 and staged gen25/seq26/offset1226; both marker digests and original prefix hashes valid, source size1226. Core publish correctly retained old marker and complete staged update; harness wrongly assumed every concurrent replacement must succeed.
- Local JDK17 and Zulu8 source archives confirm NIO defaults shareRead/shareWrite/shareDelete=true; which external/transient handle caused denial is not proven. Do not attribute it to a specific process or loosen product replace/review behavior.
- Workflow39094266492 rejected before jobs: inline pip --only-binary=:all: has a colon-space YAML token. Change to a block scalar. Existing passing pre-F runs retained.
- Next: test-only harness verifies either all25 publications plus final read, or exact old-marker/staged/original preservation plus reader fail-closed; deterministically exercise pending-review reader case. Never delete staged files or retry originals automatically. Rerun actual batch and CI.


## 2026-10-18 12:42:28 +09:00 — Phase F targeted harness/CI correction (verification running)
- YAML pip install switched to block scalar so :all: cannot parse as a mapping delimiter.
- Existing RLC2 TEST harness now accepts Windows replacement refusal only if exact old marker, fully encoded expected staged next generation and writer-recorded source bytes all match. It records concurrent25-progress BLOCKED for that run and requires the reader to remain fail-closed; normal successful path still requires finalgen26. No staged cleanup, retry or product publish change.
- Added deterministic held-marker+reader case, verifying pending data stays unreadable after writer and reader stop. This covers the refusal branch even when intermittent denial does not occur. Targeted native suite running; then full batch will rerun.


## 2026-10-18 12:45:14 +09:00 — Phase F targeted native GREEN; full batch rerun
- Freshly compiled Windows native suite passes; RLC2 101 checks including normal finalgen26 and deterministic pending-review refusal. Initial targeted invocation reused old common bytecode, so native test script now explicitly compiles shared WatermarkV2Scenarios with sourcepath; no stale pass is counted as new proof.
- Corrected YAML now schedules all four CI jobs; Linux/Windows/PG jobs already success at0541317, x86 running. Superseded runs may cancel by design. Full Windows batch reruns after these actual corrections; prior failed batch remains recorded.
- Valid wrong-JAR fixture replacement and wheel hash positive/negative checks PASS. No product publication/retry semantics changed.


## 2026-10-18 12:57:57 +09:00 — Phase F COMPLETE; Phase G START
- Agent PR47 merged202b9c95a77d7f07d28a6cb1d2a2c03927b13d98; PR39094810643/39094810652 and main39095250378/39095250377 success. Actual full local batch build/acceleration-20261018-124526.log exit0 includes test PG/actual Server models; native102 each explicit64/32, diagnostics35/projection21/preview35/fault37 per architecture. Local symlink/old Windows RLC156/physical power loss/real ENOSPC limitations retained.
- Server PR54 merged60d33653da26ec5c796aae0fe44cf4094e9b9807; main39095397003 running (docs only).
- Phase G base Agent202b9c9 / Server60d3365; branch feat/ingress-preflight-load. Add fixed manual next-action hints (never execute), source-only RLC1/RLC2 migration preflight without reinterpretation/repair, mixed-format refusal, ledger-lag diagnosis, and bounded multi-source/multi-session load/replay verification. Capture larger10k observations on VPS/actual x86 if feasible; CI uses bounded default.
- Next: implement read-only preflight using existing codecs, then workload fixtures; preserve all ACK/durability/production gates.


## 2026-10-18 13:03:31 +09:00 — Phase G preflight implementation/local GREEN; workload running
- Diagnostic findings add fixed manual next_action and automatic_repair_allowed=false. Source-only --migration-preflight uses existing RLC1 codec plus raw prefix/CRC/UUID checks, never maps fileKey hash to native identity or creates an RLC2 marker/ledger. Mixed marker paths block session projection. Ledger lag is distinguished from corrupted receipts.
- Windows x64 existing diagnostics34/projection20 and new migration preflight17 checks PASS (one additional shared rejection assertion also ran). New bounded load test defaults1000 observations +3 additional sessions, verifies two physical sources/session, exact receipts/rows/highwaters, new-process-style replay and input-byte preservation; running.
- Next: x86/VPS; larger10k observations/10 additional sessions; measure existing implementation before any performance change. ACK/repair/durability limits retained.


## 2026-10-18 13:05:38 +09:00 — Phase G Linux large workload baseline PASS
- VPS Linux17.0.20.1 x64: new preflight and all diagnostics/preview/fault regression exit0; bounded1000-observation/4-session load23 checks PASS. Larger10000 observations +10 additional sessions (11 total) load44 checks PASS:10100 product records,10111 raw receipts,22 physical source cursors and11 logical claims; no duplicates/ACK, all highwaters and original bytes verified.
- Cold-process exploratory large metrics: source297ms, ledger1423ms, projection1485ms, preview commit1159ms, full projection+store replay2086ms. Not a normalized benchmark. Original hex utility allocates a Formatter per byte for every receipt/hash; next replace with equivalent fixed lowercase nibble mapping, preserving exact stored string format, then remeasure and rerun independent hash/DB tests.
- Actual Windows x86 migration/large baseline still running; baseline log will be retained before recompiling. No protocol or database format change planned.


## 2026-10-18 13:12:40 +09:00 — Phase G load/performance after-change PASS
- Actual Zulu8 x86 baseline: migration18 PASS;10000 observations/11 sessions load45 PASS, original bytes preserved. Baseline metrics source2151/ledger5572/project9856/commit6377/replay11433 ms, before hex recompilation.
- VPS optimized implementation retains all migration/diagnostic/preview/fault proof and10k/11-session load44 PASS. Metrics source297/ledger949/project614/commit1675/replay939 ms versus baseline297/1423/1485/1159/2086. Commit timing increased while CPU-heavy phases decreased; these are single cold-process observations, not a normalized speed guarantee. Exact stored lowercase hex format retained.
- Windows x64 after-change existing suites and default load23 PASS. Added precise pending-update preflight classification: known staged state is not falsely labelled corrupt; final added case awaiting fresh compile/CI. Next: optimized x8610k, backward compatibility scan of an old10k ledger, docs/PR/main CI.


## 2026-10-18 13:18:30 +09:00 — Phase G final local proof and review
- Final migration x64 18 / actual x86 19 PASS. Optimized x86 large10k/11-session load45 PASS; metrics source1722/ledger3653/project4359/commit4807/replay5010 ms. New x64 inspector read10001 receipts from original formatter/actual x86 dataset, exit0 INSPECTED, every input byte unchanged.
- Existing source/ledger data format unchanged; all timing phases and limitations retained in ingress_preflight_load.md. Final pending-classification case passed locally; CI will run final source on Linux/Windows/x86 and guarded PostgreSQL. F Server main39095397003 success.
- Next: PR with final CI, normal merge/main CI, Server progress synchronization, final acceleration report/resume commands. No remaining production gate is silently relaxed.

## 2026-10-18 — Phase G merged; Phase H checkpoint integrity review START
- Agent PR48 merged33875a2e86dabb91f8b3a93441192595a2a7d08f, PR journal39096699520/product39096699522 success; main39097392309 running/product39097392286 success. Server PR55 mergedaf30941a4f0128a448389e3dd48d5003191bd2cd, main39097403278 success.
- Phase H base33875a2/af30941; fix/preview-checkpoint-integrity. Probe replay after an event primary key is changed while aggregate counts remain valid. If reproduced, reject unknown/missing committed projection sets before adding rows; never silently reconstruct output evidence. Also verify prior source receipt extents independently of aggregate counts. Synthetic preview only, no schema migration or ACK.


## 2026-10-18 — Phase H RED reproduced / fix under test
- RED b032e04 changed one synthetic event key without changing row counts; replay accepted it and added the original row. New pre-write validation checks prior per-source receipt extents and exact record IDs derived from the old contiguous receipt prefix, independently of aggregate counters. Unknown/missing rows fail closed without reconstruction.
- Regression adds original key replacement/byte preservation, deletion+adjusted counters, valid active append and third-source rotation/replay. Windows x64 RED became GREEN for new cases; full fault/load and actual x86/Linux running. No schema or formal ACK changes.

## 2026-10-18 20:51 JST — Phase H COMPLETE / final report checkpoint
- Agent PR49 merged e1677c4e8341b1d60d61bd2be11dd57a5e459a43; PR journal39097642181/product39097642133 and main39124576935/39124576968 all success. Actual four jobs, 32-bit batch and PG steps ran. Local final preview/fault/load42/36/23 x64,43/37/24 x86,42/36/23 VPS; VPS10k/11session44 PASS. Fresh local PG twice/actual Server models PASS.
- Server PR56 merged a1bc6210159f6ee736f084b6f81d3da0d5f9f500; main39124675916 success. Docker14 existing services Up, own container absent; VPS dedicated test processes none; loopback test PG preview schemas0. Synthetic files retained intentionally; no real originals/ECU/X270/production services changed.
- Final report codex_acceleration_report.md captures all phases/PRs/merge/main CI, RED/GREEN, limitations and detailed next integration gates/resume commands. Documentation-only final PR to follow; source baseline stays e1677c4. Next: final docs normal merge and Server final progress, clean synchronized main/openPR0 checks.
