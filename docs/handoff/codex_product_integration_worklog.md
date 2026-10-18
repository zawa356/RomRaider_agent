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
