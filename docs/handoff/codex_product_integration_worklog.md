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
