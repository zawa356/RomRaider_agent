# Phase VII append-only worklog

Implementation agent: Claude Code (Opus 5.5), fresh session without prior chat context. Predecessor: Codex (Phase V/VI). Entries are appended only; earlier entries are never rewritten.

## 2026-10-20 JST — start checkpoint (before any Phase VII code or test)

- Instruction: `RomLogger_PhaseVII_ClaudeCode_FreshSession_20261020.md` (user-attached). Scope: independent review of Phase V/VI, single default-off Offline Candidate Entry, integrated read-only readiness, synthetic-owned E2E. No production cutover, ECU/X270/OpenPort, formal ACK, Sender wiring, production migration, existing service change or merge.
- Host: Windows 11 development VM (not X270). Verified present: `C:\Dev\RomLogger\RomRaider_agent`, `C:\Dev\RomLogger\RomRaiderServer`, Codex Phase VI worktree (reference only, not edited), Zulu17 x64, Zulu8 x86, Git Bash (absolute path), Python 3.12, gh authenticated as zawa356.
- Fetched refs (identical to the instruction snapshot):
  - Agent main `4f80c5e9831ec39368f4e771d829337598eeaa76`
  - PR80 `phase5/provenance-hardening` `7da38372044792cde5271932a6134b7c6f156ccb` (base main, OPEN)
  - PR81 `c52d7ba`, PR82 `105f66d`, PR83 `556f4ff`, PR84 `97e28b0`, PR85 `4239c20`, PR86 `phase6/transition-drive-preflight` `297126cf2b44029f11c26bc757473d7c9018e8d1` (all draft, stacked)
  - Server main `d8c7a2f3b96ba278d269074dc40cca7741b4155b`; PR62 `056958d`; PR63 `phase6/agent-safety-progress` `526e77d4bb2dc0fb0c995e2f6ae09951d0ce8d0e`
- CI: latest runs at 297126c (39347006053 / 39347006139 / 39347006154) failure with every job `steps=0`. **CI NOT RUN** (runner not started). No dispatch, billing change or merge.
- New worktree `C:\Dev\RomLogger\phase7-claude`, branch `phase7/independent-review` created from `origin/phase6/transition-drive-preflight` 297126c. Phase VII work stacks on PR86; existing PR bases unchanged.
- Read: Phase VI branch bootstrap, AGENTS.md/CLAUDE.md, Phase VI report/evidence/review/worklog. Next: direct code review of the classes listed in VII-0.

Safety baseline carried forward: default-off, synthetic-only, production_authorized=false, ack_eligible=false, launch_authorized=false. WRITER_UNPROVEN, Windows process provenance / directory durability UNKNOWN.

## 2026-10-20 JST — VII-0 reproduced defect F1 (test-only checkpoint)

- Baseline: Phase VI preflight suite rerun unchanged at runtime 297126c (runtime/tests identical to 53f18a2), Windows JDK17.0.20.1 x64: 41 checks exit0 (`build/baseline-preflight-x64.log`). Test dependencies copied from the Codex worktree and verified against `lib/test-dependencies.sha256` and the pinned JNA SHA256 34ed1e1f….
- F1 (Medium): `OfflineCandidatePreflight.MAIN` approves `Rlc2IntegrationCli`, whose only write path is unbound `Rlc2IntegrationStore.ingest` (Worker guard only; no RLDB/RLDT generation, no Lane/Publication lock, self-asserted `--quiescent`). Test-only `Phase7EntryBypassRedTest` on an invocation-owned fixture: control preflight WRITER_UNPROVEN/matched and CLI stores; with an actual child JVM holding the Lane (PID16828) or Publication (PID11848) lock, preflight refuses (LANE_NOT_OBSERVED_IDLE / PUBLICATION_NOT_OBSERVED_IDLE) but the approved entry exits 0 and changes the candidate output. `scripts/test-phase7-review-red.sh`, Windows17x64, exit1, 4 PASS / 4 RED, log `build/phase7-entry-bypass-red.log` SHA256 cad47faafca458d455cd3f9a971bfad318039c8e3ae962903d54a44cdd28db31.
- Interpretation: cooperative-lock bypass by the entry the preflight names; not production authorization (launch_authorized stays false, no ACK/Sender). Fix belongs to VII-1: a single bound entry that runs only through lock-holding, generation-checked ingest; preflight must name that entry instead.

## 2026-10-20 JST — VII-1..VII-4 implementation checkpoint

- PR87 `phase7/independent-review` (review + F1 RED), PR88 `phase7/offline-entry` 2be491d (RLOE1 entry), `phase7/integrated-readiness` 0cd4c69 (RLIR1), `phase7/synthetic-e2e` 76288e8 (E2E + runbook), `phase7/adversarial` 98c0235 (VII-4). All stacked on PR86; none merged.
- Frozen 2be491d (`../phase7-accept-2be491d`): entry x64 108 / actual Zulu8 x86 109 checks exit0; Phase VI preflight 41 x64/x86 exit0; old-CLI RED exit1 as designed. Frozen 0cd4c69 (`../phase7-accept-0cd4c69`): readiness x64 65 / x86 66 checks exit0; Phase V 62 x64/x86 exit0.
- Development (not frozen) runs at 76288e8: E2E x64 24 checks exit0 incl. forced child kill (landed pre-commit: 0 records, retry converged); owned PG16 + actual FastAPI/Uvicorn harness 35 checks on the entry-produced candidate, candidate bytes unchanged, shared 55432 untouched. These dev runs are superseded by the final frozen run.
- VII-4 found and fixed an `owned-runs/` creation race between two supervisors (fail-closed uncoded REVIEW_REQUIRED before the fix). Sidecar-free BEGIN IMMEDIATE/EXCLUSIVE writers on a DELETE-journal v1 are invisible; result keeps WRITER_UNPROVEN (limit reproduced, not hidden).
- Next: freeze one head, run all Phase VII suites + Phase VI/V/IV regressions on Windows x64, actual x86 and Linux, owned HTTP, then evidence/report/bootstrap/progress and Server progress PR on #63.

## 2026-10-20 JST — final-head 67657d9 load FAIL (test fixture) and test-only correction

- Frozen 67657d9 Windows x64 `ROMLOGGER_OWNED_PG_HTTP_ENABLE=1 ROMLOGGER_PHASE7_LOAD_OBSERVATIONS=10000 test-phase7-e2e.sh`: **exit1**, 31 PASS lines, log `../phase7-accept-67657d9/build/p7-e2e-http-load-x64.log` SHA256 3b756fdf903f185eec96e229ffa4491364a2992d921b41b448b26ded1e04b3b5. Core E2E checks passed; the large-session step was refused (`INSPECTION_UNAVAILABLE` from preflight); `set -e` then skipped the HTTP step, so HTTP is NOT RUN at 67657d9.
- Cause (test-side): the load fixture numbered extra samples 1000+ before segment 2's sequence 1; the RLC2 adapter correctly refuses with "sample sequence must continue across rotation". The product refusal was right; preflight collapsed its cause (review F2 pattern, unchanged Phase VI code).
- Correction: `Phase7Fixture` continues sample sequences across segments and rotation; the E2E load assertion now prints the entry result. Dev rerun x64 with 10000: exit0, 26 checks, 10003 records stored in 49.6 s, rerun 0 duplicates. Test-only change; product sources unchanged since 67657d9. New final head follows; Phase VII suites rerun there on all platforms.

## 2026-10-20T16:15+09:00 — final frozen results and shared-worktree notice

- d93a975 (test-only fix; `src/main`/scripts = 67657d9): Windows x64 entry 108 / readiness 65 / E2E 26 incl. 10003-record load and owned PG16 + FastAPI 35 checks (candidate bytes unchanged) / adversarial 41; actual Zulu8 x86 Phase VII exit0 (109/66/25/42); Linux Phase VII exit0 (109/66/25/42). 67657d9: Windows x64 Phase VI/V/IV exit0, x86 Phase VII exit0, Linux Phase VII/VI/V/IV exit0; x64 load E2E FAIL kept. Hashes in evidence.
- A separate Claude session started using `C:\Dev\RomLogger\phase7-claude` on a new branch `phase7/review-closure` (commit 36bb721, VII-C2 RED: zero-record v1 with claims/cursors reads as cursor PASS) with uncommitted product edits. This session stopped editing that worktree, messaged the peer, and moved handoff docs to `C:\Dev\RomLogger\phase7-docs` (branch phase7/adversarial). VII-2 is reclassified IMPLEMENTED_NOT_FULLY_TESTED until C2 is closed there.
