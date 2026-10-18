# Codex product integration worklog — Phase I

## START — 2026-10-18
- User supplied next-session Phase I instructions; autonomously continue I-1 through I-7, with implementation, tests, PR/CI/normal merges. No additional GO needed.
- Baselines Agent e916f57eef0f450eea35cad78c0e9d105a1f9fe0 / Server d66ab42648181ef64b5264d71b9694ee5cc63816. Both clean main, origin equal, openPR0, prior applicable main CI success. Personal zawa356 noreply author confirmed. No unfinished worktree changes.
- Branch feat/rlc2-integration-contract. I-1 plan: versioned isolated integration schema and explicit migration, logical session/physical source ownership, compatibility inventory for read-only existing v1 claims/cursors; never migrate a running v1 DB. Reuse preview transaction/conversion code for I-2, add session path/admission binding. Unknown sources require manifest plus explicit admission policy; a source is not adopted from directory enumeration.
- Test plan: real SQLite migration/reexecution/rollback/legacy compatibility, exact ID/run invariants, Windows17 and actual8x86, Linux via pinned VPS/CI, isolated PG schema plus real loopback HTTP later. I-3 quiescence/snapshot contract; I-4 independent diagnostic fields and strict schema verification; I-5 real Server HTTP and failure/retry; I-6/7 regressions/automation/report.
- Gates unchanged: Windows directory durability UNKNOWN, no physical power-loss proof, no original repair/delete, no real ECU/X270/OpenPort, no existing Docker/productionDB changes, no production migration/Worker/ACK activation, no secrets/history rewrite.
- Legacy root test.sh NOT RUN until required lib/RomRaider.jar exists. Dedicated suites will be executed explicitly. Next: implement I-1 schema/contract, then checkpoint before I-2.

## I-1 implementation checkpoint
- New isolated RLI2/user_version1 schema reuses six preview transaction tables and adds session/run binding plus explicit child admissions. D-057/C20/storage contract v2 documented. Existing v1 is inspected read-only; live cross-DB coexistence remains BLOCKED, mandatory external quiescence.
- Windows17 x64 schema13 PASS (migration rerun/rollback, unknown version, product DB unchanged, old-lane conflict); dedicated full preview/fault/load running. VPS pinned SSH/java and loopback PG16.15 read-only query PASS. Next actual x86/PR CI, then I-2 atomic candidate engine using this backend.

## I-1 CI external BLOCK / I-2 START
- Agent PR52 head50c0475 and Server PR58 created/attached. GitHub annotations: jobs not started due recent account payments/spending limit; steps empty. CI NOT RUN, no merge. PR52/58 remain reviewable. No billing/settings changes attempted.
- I-2 branch feat/rlc2-integration-engine stacks on PR52; base50c0475. Reuse existing full transaction backend via an isolated format/transaction guard, explicit admission and external quiescence. Keep each phase in separate PR against prior phase until CI/main gate clears.
- Local schema13 actual x86 PASS; VPS full suite still running. Next candidate engine checks canonical binding/admitted UUIDs/legacy lane within the shared transaction and preserves all original/checkpoint gates.

## I-2 local engine GREEN
- Reused full existing transaction engine via isolated-format constructor and transaction guard. All eight tables (including session binding/admissions) commit/rollback together. Explicit approved UUID set required for rotation; missing saved admissions cannot be reconstructed. Closed legacy inventory checked before ingest and precommit; live coexistence still blocked.
- Windows17 x64 integration24 PASS: two-source projection/replay, unchanged legacy/input bytes, unapproved-source rejection, admitted third-source append, rollback all8, lane conflict and two-worker exact convergence. Actual x86 follows. Existing full preview/fault regression will be rerun after final snapshot/schema changes.
- I-1 VPS schema13 plus preview42/fault36/load23 and10k44 PASS. CI still blocked at scheduling by account billing; no merges.

## I-3 START — snapshot boundary
- Base engine0c5139a, branch fix/rlc2-integration-snapshot. Reproduce a writer publishing/advancing ledger in the BEFORE_COMMIT hook after projection; old engine may commit a stale projection. Keep mandatory external quiescence; add bounded input fingerprint evidence from before projection through immediately before SQLite commit, not a claim of atomicity against uncooperative writers.
- No source locks acquired by reader; output SQLite IMMEDIATE lock acquired only after projection, input checks do not wait on writer locks. Mutation causes output rollback and preserved inputs. Test source/marker, manifest, ledger and legacy changes independently.

## I-3 RED/GREEN checkpoint
- RED d08860d reproduced source+ledger advance after projection accepted at commit. Streaming bounded snapshot evidence now checked before/after projection, before output writes and immediately precommit; changes roll back output without repair. Quiescence and non-live contract remain mandatory.
- Initial Windows x64 RED->GREEN3 checks; expanded independent manifest/ledger/legacy/unpublished-tail scenarios running (15 total expected). Next actual x86, Linux and PR. GitHub scheduling still billing-blocked; main unchanged.

## I-4 START — explicit diagnostic axes / strict output schema
- Base109804c, branch fix/ingress-schema-supervisor-contract. I-3 Windows15 PASS; x86 running. I-2 x8624 PASS. CI still blocked by billing, all branches retained, no main merge.
- Plan RED: rename ack_eligible column in a synthetic preview DB; current name/application-only ownership may accept it. Then compare complete SQLite schema signature against authoritative DDL (all columns/types/nullability/PK/UNIQUE/FK/CHECK/index/trigger/object/version), quick_check and FK consistency. Preserve actual legacy-v0 DDL compatibility. Replace trigger-based failure injection with real NOT NULL constraint failure inside transaction because untrusted triggers must be refused.
- Diagnostic JSON v1/exit compatibility retained with independent supervisor contract v2 fields; exit0 never implies operational readiness, durability or ACK.

## I-4 RED/GREEN
- RED0a27d0d accepted renamed ack_eligible column. Strict authoritative-schema verifier now checks every table/index/object and constraint/type/version plus quick_check/FK. Windows schema33 PASS, including genuine pre-I v0 DDL bytes and malformed DB unchanged. Diagnostic additive Supervisor axes implemented; Windows diagnostic/migration suite exit0, final counts in raw log.
- Full preview/fault/load/schema/integration regression running. Intentional trigger injection now stops before ingest; an actual NOT NULL SQL failure inside transaction separately verifies rollback. I-3 actual x86 snapshot15 PASS.
- CI billing block retained; PRs52/53/54 not merged. Continue real loopback Server HTTP E2E next, using own schema/credentials and test ACK only.

## I-5 START — real loopback HTTP / owned PostgreSQL schema
- Base3842a0c, branch test/rlc2-real-server-http. Build reproducible runner from both local checkouts, no private CI secret needed. Generate admitted three-source integration fixture, run actual Server migrations in invocation-owned schema only, bind actual Uvicorn/FastAPI to an owned loopback socket.
- Test actual HTTP profile/batch persistence, duplicate/409/422/profile refs, database endpoint unavailability, real PG transaction failure, dropped HTTP response/retry, one run across rotation. Test ACK is observed but never written into candidate ACK state. Never stop shared local PG or touch production services.
- Also exercise translated candidate schema migration transaction failure/rerun in owned PG schema. CI wiring later; scheduling remains blocked and merges prohibited.

## I-5 HTTP implementation checkpoint / user CI policy update
- Implemented actual socket-bound Uvicorn/FastAPI runner using both local checkouts, random invocation-owned PostgreSQL schema and synthetic credentials. Actual Server migrations twice/check plus candidate PostgreSQL transactional DDL failure/replay run without touching existing schema. Candidate fixture has3 admitted sources/5 receipts/4 records/one run.
- First actual HTTP run passed unknown profile422, profile201, real response-drop proxy after committed PG write, identical batch four duplicates, same-ID conflict409 and malformed422. Harness initially expected422 for an already-persisted record with a changed undefined parameter, but Server correctly prioritized immutable-record409; corrected to a new record ID/sequence to isolate definition validation. Final HTTP run pending.
- User clarified GitHub Actions likely exceeded free quota and requested local tests as the verification basis. Use full Windows/x86/VPS/isolated DB results and code review for normal merges; retain Actions definitions and record scheduling NOT RUN rather than PASS. No billing/settings changes.

## I-6 START — final fault/compatibility review
- Basecac5347, branch test/rlc2-integration-faults. Add real separate-JVM integration halts/concurrent workers and schema-token adversarial cases; reuse existing native copy/CRC/seal/8MiB/16segment/claim/receipt/marker suites rather than duplicate implementations.
- Independent review noted schema whitespace normalization must preserve token boundaries (TEXT NOT NULL must not equal TEXTNOTNULL); add RED before correcting. Candidate byte/ACK preservation and quiescence contracts stay unchanged.
- I-5 HTTP rerun after correcting expected Server canonical datetime serialization; no Server runtime change required. Local verification is authorized basis for normal merge; Actions scheduling unavailable is recorded separately.

## I-6 local proof and follow-up
- RED15f04f3 reproduced TEXT NOT NULL vs TEXTNOTNULL collision in schema normalization. Preserve token whitespace (collapse to one space) and SQL literals; schema35 Windows x64 PASS, including legacy-v0 compatibility. This follow-up closes the I-4 verifier issue before the final integration stack is merged.
- New actual separate-JVM integration fault suite Windows x64 20 PASS: halt at all4 transaction stages, owned RLI2 rollback recovery, whole admissions/records, retry exactly once, two independent worker processes, original/legacy bytes and ACK state preservation. Compile harness variable shadowing error corrected before runtime; no stale pass counted.
- I-5 final actual HTTP34 PASS: Server migrations twice/check, candidate PG interrupted DDL rollback/replay, profile/batches, response-drop proxy then four duplicates,409/422, parameter refs, actual PG transaction fault, unavailable DB endpoint503, exact Server-canonical JSON/run/sequence, unchanged Agent fixture. Physical PG cluster stop NOT RUN; shared test service intentionally retained.
- Next I-7: one-command Windows including opt-in realHTTP, optional private-checkout CI step, final full Windows/x86/VPS and Server regression, local-validation authorized merge sequence and complete report.

## I-7 START — final automation and executable entry point
- Base0eaccd7; branch feat/integration-verification-automation. Add explicit synthetic/quiescent/admission CLI (native Windows remains test-only), Windows one-command optional real HTTP and guard checks, gated CI reuse of both checkouts. No automatic Worker/sender wiring.
- Run final Windows17/actualZulu8 complete batch, Linux full isolated suites/large load, actual Server regression57 and local HTTP34. Review latest stack as one coherent candidate with I-6 token fix. User authorized local verification basis; Actions NOT RUN due account limit, normal merges after local evidence/review.

## I-7 automation implementation
- Added explicit synthetic/quiescent/admission CLI; Windows native wrapper remains test-only. Windows full batch gains -RealServerHttp with strict -Postgres/-ServerPath prerequisites and guards, restores HTTP opt-in environment. Gated private-checkout CI includes new actual HTTP test and pinned observed PG16 digest; missing checkout remains NOT RUN, never a claimed pass.
- Expanded bounded workload to exercise versioned candidate plus old preview across all sessions, exact bindings/admissions/records/receipts and no ACK. New CLI real-process import/replay included. Final whole Windows/x86 and fresh VPS test workspace will verify the exact source before merge.
- Actual Server local regression57 PASS after migrations twice/check. I-6 Windows candidate JVM faults20/schema35 PASS. User local-validation authorization retained; Actions are supplementary and unavailable scheduling is not a code failure.

## I-7 additional owned database outage harness
- Added optional freshly initialized build/romlogger-http-pg-* PG process-stop/restart wrapper. Only fixed invocation-owned data directories reach pg_ctl; existing127.0.0.1:55432 cluster never stops. Synthetic initialization password removed; test data/logs retained ignored. Guard now3 tests PASS; local YAML parsed with test-only PyYAML6.0.3 and PowerShell AST parser PASS.
- Initial Windows pg_ctl start harness captured pipes inherited by long-lived postmaster, causing TimeoutExpired/drain wait; owned PID12028/data root was positively identified and stopped, shared cluster readiness remained0. Corrected subprocess handling uses DEVNULL and hidden creation flags plus PID-file cleanup fallback. Fresh wrapper rerun underway; no result claimed before it completes.

## I-7 full local matrix GREEN — 2026-10-18 22:04 JST
- Windows full batch build/acceleration-20261018-214753.log exit0 includes actual17x64/8x86 native, ledger, diagnostics, preview, candidate, snapshots/schema/JVM faults, guards, real PG and actual HTTP35. New per-arch schema13/engine28/snapshot15/strictschema35/JVM21, preview43/fault44/defaultload32, diagnostics37/projection21/preflight19. Actual x86 10k/11session candidate+preview load60 PASS; candidate snapshot/project/commit10448ms observational, exact10100records/10111receipts/22admissions/11bindings.
- Fresh VPS full product/journal/SQLite/ledger/rotation/diagnostics/preview/candidate stack exit0: integration13/28/15/schema35/JVM20, diagnostics36, preview42/fault43/defaultload31;10k/11session59 PASS, candidate combined1656ms observational. Java core matches8ac9213; later edits are test Python/docs only.
- Owned fresh PG immediate-stop/restart actual HTTP35 PASS after bounding test DSN connect_timeout=2. Earlier retry hit unbounded driver wait after actual process stop; set test-only timeout and verified503 before restart,4committed records retained and all Agent bytes unchanged. Own cluster stopped; existing55432 remained available. Final default HTTP repeat of latest code pending, then local-authorized normal merges52..58.
- Server57 passed (migration upgrade twice/check). YAML syntax(PyYAML6.0.3 test-only) and PowerShell AST PASS. Actions scheduling remains NOT RUN due account quota/payment annotation, per user it is supplementary; no waiver of production/ACK/durability gates.

## Final code merges / report publication — 2026-10-18
- Agent normal merges:52=37de3ea2a0763f9e241799f8c606612c5ee35e48;53=a336a3c89ad6ce8d1ed73f13aa9af6364dceb878;54=04a4c9963404a33e85a86732c8109efd8ceb9197;55=90d79c6c7c032a22b7d9f04df7d62230c5c7d41c;56=7b74edf0170eb35a896b5788adb67cd64a9bf646;57=0a25315476c9cb6d13bb5b0b3b3cb894aac66f93;58=aa55dcd007b48728a320448fb827049254fcb6fd. Tree equals testedbe08629; main/origin clean and openPR0 before final report branch.
- Server PR58 normally merged9274394ec5b093ecd71f44c7eaec146130bb1d32; documentation-only, runtime/migrations unchanged, main/origin clean/openPR0. Exact contract copy synchronized.
- Latest default HTTP35 and actual owned-process-stop HTTP35 PASS; all3owned clusters stopped, existing55432ready, shared preview/HTTP schemas0, Docker14Up, VPS own processes none. Latest YAML/PS parsing/guards passed. No source changes after the proven tree; report branch docs/product-integration-report only.
- Final report codex_product_integration_report.md includes phase times/merge SHAs, complete platform counts, real HTTP distinctions, failure history, CI-unavailable user override and blocked live/production gates. Next: merge this docs-only PR, verify both clean main/openPR0. Resume from latest main using report commands; no force/reset or branch deletion.
