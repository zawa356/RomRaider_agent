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

