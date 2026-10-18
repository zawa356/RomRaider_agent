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
