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
