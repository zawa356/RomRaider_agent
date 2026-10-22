# Development environment bootstrap — start here

## Phase X-A current entry — 2026-10-22 (not merged)

Top of the Agent stack: Draft PR [#104](https://github.com/zawa356/RomRaider_agent/pull/104) `ci/phase-xa-windows-probe`
on #103. Read [Phase X-A report](https://github.com/zawa356/RomRaider_agent/blob/ci/phase-xa-windows-probe/docs/handoff/phase_xa_ci_stability_report.md)
→ [evidence](https://github.com/zawa356/RomRaider_agent/blob/ci/phase-xa-windows-probe/docs/handoff/phase_xa_ci_stability_evidence.md).
Windows `DRIVE_TYPE_UNPROVEN` at `32b139f` = cold-start probe timeouts; fixed by a measured 30 s bound and 64-bit
PowerShell from x86 JVMs (gate not weakened). RLDI1 ZIP parser hardened (15 RED → GREEN). New tests:
`scripts/test-phase-xa-drive.sh`, `scripts/test-phase-xa-zip.sh`. Dev VM C: is nearly full — run heavy suites on CI.

## CI status — 2026-10-21 (both repositories PUBLIC)

GitHub-hosted runners now execute (before 2026-10-21 every job had `steps=0` = CI NOT RUN; that history stays). Top of the
Agent stack is Draft PR #103 `fix/rldi1-no-temp-write` (on #102 → #101 `ci/public-restart` → #100): ingress (incl. actual
Zulu 8 x86), spool, **real Agent→FastAPI→PostgreSQL16 E2E against the public Server pinned at `d8c7a2f` (no secret)** and
the new `phase7-9-synthetic-ci.yml` all pass. Windows jobs must use a long-name `TMP` (runner `%TEMP%` is 8.3 and is
refused by product path guards). Read [recovery report](https://github.com/zawa356/RomRaider_agent/blob/fix/rldi1-no-temp-write/docs/handoff/public_ci_recovery_2026-10-21.md)
and [validation table](https://github.com/zawa356/RomRaider_agent/blob/fix/rldi1-no-temp-write/docs/handoff/public_ci_validation_2026-10-21.md).
Statements below that CI cannot run or that the E2E needs a private-repo token are history. Still: no merge without the
owner; intermittent probe timeouts (C4) are open.


## Phase IX current entry — 2026-10-21 (not merged)

Use [this bootstrap on the Phase IX top branch](https://github.com/zawa356/RomRaider_agent/blob/phase9/distribution-hardening/docs/ops/development_environment_bootstrap.md) → [Phase IX report](https://github.com/zawa356/RomRaider_agent/blob/phase9/distribution-hardening/docs/handoff/phase9_report.md) → [evidence](https://github.com/zawa356/RomRaider_agent/blob/phase9/distribution-hardening/docs/handoff/phase9_validation_evidence.md) → [append-only worklog](https://github.com/zawa356/RomRaider_agent/blob/phase9/distribution-hardening/docs/handoff/phase9_worklog.md).

Stack: … #96 → [#97](https://github.com/zawa356/RomRaider_agent/pull/97) `phase9/cli-review-closure` → [#98](https://github.com/zawa356/RomRaider_agent/pull/98) `phase9/windows-reader-synthetic` → [#99](https://github.com/zawa356/RomRaider_agent/pull/99) `phase9/windows-reader-entry-gated` → [#100](https://github.com/zawa356/RomRaider_agent/pull/100) `phase9/distribution-hardening` (top). Server docs: [RomRaiderServer#65](https://github.com/zawa356/RomRaiderServer/pull/65) on #64. All draft/unmerged; CI NOT RUN. Implementation worktree `C:\Dev\RomLogger\phase9-work`; frozen checkouts `C:\Dev\RomLogger\phase9-accept-<sha7>`; logs `C:\Dev\RomLogger\logs\phase9`.

New tests: `scripts/test-phase9-cli.sh` (tool JAR + 4 CLI child JVMs; build tools with `scripts/build-offline-tools.sh`), `scripts/test-phase9-reader.sh` (RLWR1), `scripts/test-phase9-inventory-race.sh` (`--expect-red` on pre-fix code), `scripts/phase7-packaged.sh Phase9WorkdirTest|Phase9ReaderEntryTest|Phase9LauncherEnvTest`. Windows: `--run-only` under actual Zulu8 x86 with `ROMLOGGER_EXPECT_DATA_MODEL=32` after the JDK17 build.

RLWR1 adoption BLOCKED (FILE_SHARE_DELETE, JNA licence = OWNER_DECISION_REQUIRED). Finding F-IX-1: a real install with the entry's sqlite-jdbc/JNA JARs is RLDI1 MISMATCH (not relaxed). Synthetic fixtures under %TEMP% can lose empty directories to the VM's temp cleaner (R9-03). Earlier entries below are history.

## Phase VII current entry — 2026-10-20 (not merged)

Use [this bootstrap on the Phase VII top branch](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/ops/development_environment_bootstrap.md) → [Phase VII report](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_report.md) → [exact-head evidence](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_validation_evidence.md) → [independent review](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_independent_review.md) / [append-only worklog](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_worklog.md). main contains neither Phase V/VI nor VII.

Stack: PR86 → [#87](https://github.com/zawa356/RomRaider_agent/pull/87) `phase7/independent-review` → [#88](https://github.com/zawa356/RomRaider_agent/pull/88) `phase7/offline-entry` → [#89](https://github.com/zawa356/RomRaider_agent/pull/89) `phase7/integrated-readiness` → [#90](https://github.com/zawa356/RomRaider_agent/pull/90) `phase7/synthetic-e2e` → [#91](https://github.com/zawa356/RomRaider_agent/pull/91) `phase7/adversarial` (top). All draft/unmerged. Final runtime/tests `67657d98985dbe66f881fc4d683b0bce1a5a75b1`. Implementation worktree `C:\Dev\RomLogger\phase7-claude` (Claude Code); frozen acceptance checkouts `C:\Dev\RomLogger\phase7-accept-<sha7>`; detached Server test checkout `C:\Dev\RomLogger\phase7-server-d8c7a2f`. Codex Phase VI worktrees are reference only.

New tests: `bash scripts/test-phase7.sh` (entry, readiness, E2E, adversarial; `--run-only` under actual Zulu8 x86 after a JDK17 compile). `scripts/test-phase7-review-red.sh` is the historical F1 reproduction (expected exit1). Owned HTTP step: see [runbook](phase7_synthetic_owned_runbook.md). Test JARs: copy `lib/*.jar` and `build/identity-deps/jna-5.14.0.jar` from an existing checkout and verify `lib/test-dependencies.sha256` and the pinned JNA hash before use.

Single entry: `OfflineCandidateEntry` (`--diagnose`, `--synthetic-owned`, `--production-live` always refused). Diagnose/readiness always BLOCKED; synthetic STORED is never launch/production/ACK authorization. WRITER_UNPROVEN, Windows process provenance/directory durability UNKNOWN. Do not merge while CI has not executed.

## Phase VI entry — 2026-10-20 (history)

Use [this bootstrap on the Phase VI branch](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/ops/development_environment_bootstrap.md) → [Phase VI report](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/handoff/phase6_overnight_report.md) → [exact-head evidence](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/handoff/phase6_overnight_evidence.md) / [append-only worklog](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/handoff/phase6_overnight_worklog.md). These are explicit branch URLs because main does not contain Phase V/VI. Older Phase IV/Phase V entries below are history.

Implementation worktree: `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-4\RomRaider_agent`, branch `phase6/transition-drive-preflight`, stacked PR80→81→82→83→84→85→86. Final runtime/tests53f18a2 uses sibling `phase6-entry-acceptance`; broad baseline d394103 uses sibling `phase6-final-acceptance`. Never edit it while tests run. Preserve existing main/Phase V and ignored logs/partial fixtures. `bash` is not on the initial PATH; use `C:\Program Files\Git\bin\bash.exe` and explicit JDK bin PATH. Actual Zulu8x86 data model must be asserted.

New Phase VI tests: `bash scripts/test-phase6.sh` (aggregates writer/drive/drive-entry/preflight/transition/staging-parent); `--run-only` under actual Java8 after JDK17 compilation. Existing acceleration wrapper now includes them on x64 and real x86. Read evidence for completed/failed exact heads before choosing a new verification scope.

No merge while CI has not run. Writer absence remains WRITER_UNPROVEN; preflight always blocks launch. Windows process provenance/directory durability UNKNOWN. No production DB/Worker/Sender/ACK/ECU action. Server runtime unchanged; progress docs must stack on existing PR62, not duplicate it.

Verified 2026-10-19 JST (Phase V provenance hardening). This document contains paths and non-secret operating rules only.

## First three sources

1. [This bootstrap](https://github.com/zawa356/RomRaider_agent/blob/main/docs/ops/development_environment_bootstrap.md)
2. [Phase V report](../handoff/phase5_report.md) / [append-only worklog](../handoff/phase5_worklog.md) / [exact-head validation evidence](../handoff/phase5_validation_evidence.md) / [deferred P2 designs](../handoff/phase5_followups.md). Phase IV report/review are historical context, not proof of Phase V validation.
3. [Agent progress](../product_v1_implementation_progress.md) and [Server progress](https://github.com/zawa356/RomRaiderServer/blob/main/docs/implementation_progress.md)

Phase IV PRs #75–#79 and Server #61 were already merged before Phase V. Agent main at Phase V start was4f80c5e, Serverd8c7a2f. Phase V is saved in PR80/branch phase5/provenance-hardening; check its current state instead of remerging old PRs. Each PR has a pre-merge review comment. Phase III documents ([report](../handoff/codex_phase3_report.md)) are history.

Read AGENTS.md, latest fetched main/PRs, current contracts and the latest report before edits. Older dated instructions describe history.

## Actual development host and access differences

Windows 11 development VM is separate from the real-car X270. Repositories:
C:\Dev\RomLogger\RomRaider_agent and C:\Dev\RomLogger\RomRaiderServer.
A cloud or other host cannot assume these paths or local credentials exist. Test their existence first; use GitHub/local synthetic work when inaccessible. Do not probe the X270 or ECU.

Proxmox Web URL, VM ID and RDP destination: **unconfirmed / administrator information pending**. No authoritative management-entry document found in current repository. Do not infer endpoints from docs/05_environment.md (X270 record).

## Tools and credential paths

- PowerShell7, Git/Git Bash, gh, Python3.12, SSH.
- JDK17 x64: C:\Program Files\Zulu\zulu-17; actual Java8 x86: C:\Program Files (x86)\Zulu\zulu-8. Assert runtime architecture in tests.
- Credential data: C:\Users\zawa\git\hosts.env. Parse as dotenv data, never execute or print. VPS keys IP/PORT/USERNAME/PASSWORD; Docker keys DOCKER_SSH_HOST/DOCKER_SSH_PORT/DOCKER_SSH_USER/DOCKER_SSH_PASSWORD.
- Existing helper: C:\Dev\tools\romlogger_ssh.py; interpreter C:\Dev\tools\venv-ssh\Scripts\python.exe; pinned host keys C:\Dev\secrets\romlogger_known_hosts.json. Verify existence and implementation before use. Key mismatch stops; never erase all pins/disable verification.
- VPS SSH re-verified in Phase IV (2026-10-19) through the pinned helper. In Git Bash, set MSYS_NO_PATHCONV=1 so remote /tmp paths are not rewritten. VPS and Docker SSH verified on 2026-10-19; Linux JDK17 available, 14 existing Docker containers Up. Only fresh isolated synthetic work directories/disposable resources may be used. No existing service/volume/network/DB changes.
- Local isolated PostgreSQL16 test cluster on loopback port55432; helper C:\Dev\tools\romlogger-pg.ps1; Server tests C:\Dev\tools\run-server-tests.ps1; Python C:\Dev\tools\venv-server\Scripts\python.exe. DSNs stay in protected process environment, never logs/arguments.

## Test entrypoints and independent gates

Agent: `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1` (includes `scripts/test-phase4.sh` on JDK17 x64 and Zulu8 x86 from Phase IV). Phase IV alone: `bash scripts/test-phase4.sh`; Phase V: `bash scripts/test-phase5.sh` (56 cursor/Vault/RLI2/fault checks + physical replacement +4 retained-body checks + final output-name guard =62).
Optional guarded PG/HTTP and owned-cluster stop/restart: [automation](../acceleration_test_automation.md).
Linux: same Bash scripts in a new dedicated directory with synthetic sources and hash-verified dependencies only.
Root test.sh requires RomRaider.jar; absence means NOT RUN, not success.

GitHub Actions latest observed jobs have empty steps and account-limit/payment annotations; record **CI NOT RUN (runner not started)** and inspect fresh status each session. The supplied Phase V instructions prohibit merging a PR while CI has not run, despite historical local-only merge policy; leave Phase V PRs unmerged unless the user explicitly changes this instruction. Local verification remains distinct; do not change billing or repeatedly dispatch blocked workflows.

Synthetic lock/HTTP tests never enable live ECU ingestion, production migration, formal ACK or original deletion. Windows directory durability UNKNOWN; Runtime.halt is not physical power loss. Keep per-phase commands, failures and resume pointers in the append-only worklog. Never commit secrets, generated data or test credentials.

## Phase V exact restart locations

Implementation/editing branch: `phase5/provenance-hardening`, checkout `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\RomRaider_agent`.
Final implementation head `928d43fb96452b4e4f989352d8627549c1e23a4d` (focused62 on x64/x86/Linux); broad regression baseline `b4b909cc3111c11e58f35b7600e47bea1d6aadfa`, frozen Windows acceptance checkout `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\phase5-acceptance-final`. Run tests only on a frozen tree; do not edit a Bash script while it is executing.
Read Phase V evidence for exact logs/heads/results and the status of PR80 before resuming. Staging success is `STAGED_LOCAL_HASHES_MATCH_INACTIVE`, a local checksum result only. Existing/partial staging targets are retained and must not be adopted/overwritten. Formal ACK/product migration remain off. F3/old-binary exclusion and the remaining P1/P2 fixtures are explicitly incomplete.

Linux synthetic invocation: use `umask 077` before the suites so newly created fixture locks meet the private-access contract. The first default0002 attempt correctly refused UNSAFE_ACCESS; keep it as FAIL, do not chmod an existing operational lock or weaken the gate.
Phase VI final acceptance is saved: runtime/tests53f18a2 focused Windows17x64/actualZulu8x86/Linux, separate d394103 Windows full batch3181PASS/exit0 plus large loads/ownedHTTP35. Preserve the distinction between heads and read conditional BLOCKED/NOT RUN items in evidence. Existing main/PR80/PR62 remain unchanged/unmerged. VI-3 launcher and full VI-5 readiness remain Partial; never infer authorization from this test completion.
