# Phase VII report — single offline candidate entry (Claude Code)

Entry: [Bootstrap (Phase VII section)](../ops/development_environment_bootstrap.md) → this report → [exact-head evidence](phase7_validation_evidence.md) → [independent review](phase7_independent_review.md) → [append-only worklog](phase7_worklog.md). Everything below is on unmerged branches stacked on PR86; `main` contains none of it.

## Start point and PR stack

Agent main `4f80c5e`, PR80 `7da3837`, PR86 `297126c` (runtime/tests = Phase VI final `53f18a2`). Server main `d8c7a2f`, PR62 `056958d`, PR63 `526e77d`. CI at every inspected head: jobs with `steps=0` → **CI NOT RUN**.

| Order | PR / branch | base | content |
|---|---|---|---|
| VII-0 | [#87](https://github.com/zawa356/RomRaider_agent/pull/87) `phase7/independent-review` | `phase6/transition-drive-preflight` (#86) | worklog, independent review, F1 RED |
| VII-1 | [#88](https://github.com/zawa356/RomRaider_agent/pull/88) `phase7/offline-entry` | #87 | `OfflineCandidateEntry` RLOE1/RLOP1/RLSO1/RLOR1; preflight now names it |
| VII-2 | [#89](https://github.com/zawa356/RomRaider_agent/pull/89) `phase7/integrated-readiness` | #88 | `IntegratedReadiness` RLIR1 |
| VII-3 | [#90](https://github.com/zawa356/RomRaider_agent/pull/90) `phase7/synthetic-e2e` | #89 | E2E, shared packaged-JAR harness, runbook |
| VII-4..6 | [#91](https://github.com/zawa356/RomRaider_agent/pull/91) `phase7/adversarial` | #90 | adversarial suite + race fix, aggregate script, evidence/report/bootstrap/progress |
| Server | Server PR (docs only) `phase7/agent-entry-progress` | #63 | implementation_progress.md only; Server runtime unchanged |

**Final product runtime `67657d98985dbe66f881fc4d683b0bce1a5a75b1`; final tests `d93a97546aaf774bd821e3e44cf9c46c1e101cc7`** (test-only fixture fix after a recorded FAIL). Later commits are documentation only.

## What changed for the product

1. **Review F1 fixed (Medium, reproduced).** The preflight used to approve `Rlc2IntegrationCli`, which writes without Lane/Publication locks or generation checks; with an actual child holding those locks, preflight refused but the CLI wrote. Now the only approved main is `OfflineCandidateEntry`, whose write path is `ingestBoundActive` (all three locks held exclusively, generation re-checked at every stage). The old CLI is unchanged and still callable, and the historical RED keeps failing against it on purpose.
2. **One entry, default-off.** No args or any `--force` do nothing. `--production-live` is always refused (exit 6). `--diagnose` is read-only and always BLOCKED. `--synthetic-owned` is the only mode that writes: a supervisor checks the pinned RLOP1 profile (main class, ordered JAR hashes, Java executable/spec/x86-x64, user, working dir, owner root, data root, no JVM options, strict field set), the RLSO1 ownership marker (bound to physical identities, checked before any DB is opened), a matched preflight, then starts exactly one child JVM and tracks its exit code, stdout/stderr hashes and timeout.
3. **Integrated readiness (RLIR1).** A single read-only call reports 15 axes with status, stable code and next human step: deployment/manifest, identity/admission, cooperative locks, artifacts, cursor provenance, v1 ACK set, Vault all columns, RLI2 integrity, exact eight-table rows, staging generation, plus writer/process/durability (never PASS) and formal ACK/production send (DISABLED). Inputs and the staging parent are re-verified at every stage boundary.
4. **One synthetic-owned command flow** (`scripts/test-phase7-e2e.sh`): owned two-source session → diagnose → store → restart/same input (0 added) → rotation refused under the old binding → RLDT1 admits only the new Source → re-pin → store the new Source → forced kill of the owned child + explicit retry (atomic) → final diagnose + readiness → actual FastAPI/Uvicorn on an invocation-owned PG16 (35 checks, PG immediate-stop 503/no ACK/restart), Agent candidate bytes unchanged.
5. **Adversarial separate processes.** Found and fixed a supervisor race (`owned-runs/` creation) that made the loser fail with an uncoded error; uncoded failures now keep their exception class (review F2 lesson).

## Results by classification

| Item | Classification | Basis (exact head) |
|---|---|---|
| VII-0 independent review, F1 RED | IMPLEMENTED_AND_TESTED | RED exit1 at 297126c; persists against old CLI at 2be491d/67657d9 (Windows, Linux) |
| VII-1 single entry, profile/ownership/supervisor | IMPLEMENTED_AND_TESTED | d93a975 (and 67657d9): Windows x64, actual Zulu8 x86, Linux |
| VII-1 "old Worker cannot be mis-started" | PARTIAL | entry refuses legacy main classes/JARs and waits on the Worker lock; ProductBridgeWorker/Once and other old binaries are not fenced and remain callable |
| VII-2 integrated readiness | IMPLEMENTED_NOT_FULLY_TESTED | suites pass at d93a975 on all platforms, but open defect **VII-C2** (claims/cursors with zero v1 records read as cursor PASS) was reproduced afterwards by a separate review session; fix in draft [#92](https://github.com/zawa356/RomRaider_agent/pull/92) (`phase7/review-closure` head 2ccae84, separate session; results reported there, not verified here) |
| VII-3 synthetic-owned E2E incl. rotation/transition/kill/retry | IMPLEMENTED_AND_TESTED | d93a975 Windows x64/x86, Linux; the kill always landed pre-commit (post-commit window not claimed) |
| VII-3 actual HTTP + owned PG16 on the E2E candidate | IMPLEMENTED_AND_TESTED (Windows x64) | d93a975: 35 checks, candidate bytes unchanged; the 67657d9 attempt was skipped after the load FAIL (recorded); Linux/x86 NOT_RUN by design |
| VII-3 10,000-observation session through the entry | IMPLEMENTED_AND_TESTED (Windows x64) | d93a975: 10003 records, rerun 0; 67657d9 attempt FAIL (test fixture sequence bug), recorded |
| VII-3 ten small sessions through the entry | NOT_RUN | one layout = one session; multi-session load stays the Phase VI store-level result (d394103), not an entry result |
| VII-3 HTTP 409/422/503, lost response, retry | IMPLEMENTED_AND_TESTED (reused harness) | inside the 35-check HTTP run |
| VII-4 sidecar-free noncooperative writer | BLOCKED | reproduced: entry cannot see IMMEDIATE/EXCLUSIVE without sidecars; result keeps WRITER_UNPROVEN |
| VII-4 WAL sidecar / hot journal / race / junction-symlink / hardlink / directory swap / forged fields | IMPLEMENTED_AND_TESTED | d93a975 Windows x64/x86 (actual junction), Linux (symlink) |
| subst/drive alias creation, real SMB, true symlinks | NOT_RUN | no system mapping changes; Phase VI injected probe only |
| Windows process provenance, directory durability, physical power loss, real ENOSPC, global fencing | BLOCKED / NOT_RUN | UNKNOWN by design |
| Product live, Sender, formal ACK, production migration, ECU/X270 | NOT_RUN (disabled) | out of scope and refused |
| Server pytest57/migrations | NOT_RUN in Phase VII | Server runtime unchanged at d8c7a2f; last run Phase VI |
| GitHub Actions | NOT_RUN | steps=0 at all inspected heads |

## Next work for D2 product acceptance

0. Close **VII-C2** (the readiness cursor axis must examine claims/cursors even when v1 has zero records) — fix proposed in draft [#92](https://github.com/zawa356/RomRaider_agent/pull/92) by a separate session (Phase VIII #93–#95 stack on it); rerun readiness/E2E on its head.

1. A contract for **operational exclusion of old binaries** (e.g. deployment layout where only the pinned entry has the data root; an installer-side removal/rename record), since cooperative locks cannot stop a noncooperative writer.
2. A **production Windows RLC2 reader** (today the Windows reader is test-only JNA, loaded only from the pinned test JAR); without it the entry cannot run outside synthetic mode on Windows.
3. Entry-driven **multi-session/load** (one profile per session or a multi-session profile contract) and RLPC1 staging through the entry instead of the test API.
4. Process provenance on Windows (or an explicit operational substitute) and directory-durability evidence; both remain UNKNOWN.
5. Only after the above: an explicitly authorized, separately reviewed path from candidate RLI2 to a real Sender with formal ACK. Not started.

## Resume

```powershell
Set-Location C:\Dev\RomLogger\phase7-claude
git fetch origin; git status --short --branch
gh pr view 91 --repo zawa356/RomRaider_agent --json state,baseRefName,headRefOid
# Read this report and the evidence tail first. New work: new branch from phase7/adversarial, new stacked PR.
```

```bash
PATH="/c/Program Files/Zulu/zulu-17/bin:$PATH" bash scripts/test-phase7.sh
PATH="/c/Program Files (x86)/Zulu/zulu-8/bin:$PATH" ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-phase7.sh --run-only
```

Do not merge any PR while CI has not executed. Keep launch/production/ACK disabled.
