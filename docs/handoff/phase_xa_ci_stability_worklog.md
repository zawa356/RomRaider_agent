# Phase X-A worklog (append-only) — Windows CI stability, RLDI1 ZIP audit (Claude Code, 2026-10-22 JST)

Rules: append only; failures stay. Synthetic only; no merge/rebase/force-push; main, production, ECU untouched.

## 2026-10-22 ≈12:50 JST — start (bootstrap)

- Instructions: owner-supplied `RomLogger_ClaudeCode_PhaseX_CI_Stability_ZIP_Audit_20261022.md` (not in Git).
- GitHub re-fetched: Agent main `4f80c5e`; stack top #103 `fix/rldi1-no-temp-write` = `32b139ff55b878ddaaf9fdbbe63086621b7dd5b3`
  (base #102 `fix/offline-tools-outdir`); Server main `d8c7a2f` (unchanged). Matches the instruction snapshot.
- CI at `32b139f`: Spool 39573357800 success, Server E2E 39573358311 success, Ingress 39573354521 **failure** (Windows job
  119132739485), Phase VII–IX 39573358384 **failure** (Windows phase7 job 119132829917). At `763e6fe` all four success.
- Worktree `C:\Dev\RomLogger\phase-xa-work`, branch `ci/phase-xa-windows-probe` from `32b139f` (Draft PR #104, base
  `fix/rldi1-no-temp-write`).
- Dev VM C: had **615 MB free**. Freed ~80 MB by deleting only the generated `build/` dirs of my own Phase IX frozen
  checkouts (`phase9-accept-*`, dirty=0). %TEMP% holds ~10 170 `romlogger-*` fixture dirs (~9.3 GB) from many sessions —
  **not touched** (ownership not provable per dir; owner decision). Heavy suites therefore run on GitHub CI.

## ≈13:00 — X-A-1 failure classification (from real logs, gh run view --log-failed)

| Run / job | Test | Last PASS → error | Class |
|---|---|---|---|
| 39573354521 / Windows Fsync job | `DeploymentBindingTest` → `bindSynthetic` → `requireLocalDrives` → `requireFixed` | 09:00:27.710 → 09:00:37.732 = **10.02 s** | probe TIMEOUT (10 s `reply` bound) |
| 39573358384 / phase7 windows | `Phase7EntryTest` (JDK17 x64) | 09:00:00.409 → 09:00:11.594 = 11.2 s (fixture setup + 10 s) | TIMEOUT |
| 39573358384 / phase7 windows | `Phase7ReviewC2Test` (`--run-only`, Zulu8 x86) | 09:01:08.520 → 09:01:19.892 = 11.4 s | TIMEOUT |

Not fsutil (`windowsFileId` has its own 5 s bound and different stack), not exit-code. All three were the first drive probe
in a fresh JVM during the same minute.

## ≈13:10–13:40 — local measurement (dev VM, owned probes, results only)

- PowerShell start alone 1.1–1.3 s; start + `Add-Type` 2.1–2.2 s.
- Production probe from JDK17 x64: 2.5–3.1 s (5 runs). From actual Zulu8 x86: **5.2–7.2 s** — a 32-bit process opening
  `System32\WindowsPowerShell` gets the WOW64 **32-bit** PowerShell; via `Sysnative` (64-bit) 2.5–3.2 s, same reply.
- A Reflection.Emit `DefinePInvokeMethod` variant (no csc) was prototyped: ~0.6 s faster but returned `rc=3`
  (marshalling not equivalent) → **rejected** (would change the probe's native semantics for a small gain).
- Tooling note: the Bash tool's heredoc collapsed `\\` to `\` (broken regex in a probe script, one python patch failed with
  no change written); Java/PS sources were then written with the Write/Edit tools only.

## ≈13:45 — step ① `d2bc329` (measurement, no behaviour change), Draft PR #104

`ProbeFailure(kind, ms)` inside the gate; `diagnose()`; `PhaseXaDriveProbeTest` (short injected bounds) + CI step early in
Windows jobs. Local x64 22 checks, x86 23 checks. CI run 39721643368 (Phase VII–IX): x64 diagnostics **first probe of each
fresh job 3 312 / 9 692 / 8 211 ms, later 322–499 ms**, all OK_LOCAL_FIXED; the x86 half failed (`actual JVM data model 32`)
because my step put a Windows `JAVA_HOME` into a bash PATH — my bug, fixed in `c49b8ac` (cygpath). Spool and Server E2E
at d2bc329: success.

## ≈14:00–14:40 — X-A-2 ZIP audit

Code review of `ZipEntryView` → 7 suspected gaps. `PhaseXaZipAuditTest` (byte-level ZipBuilder, each fixture asserts its
mutation) `--expect-red` on the unfixed parser: **15 accepted** (`4680f69`), incl. a lowercase manifest decoy that made
RLDI1 report MATCHED while the JDK honours the real `Main-Class`. Fix `c49b8ac`: GREEN 45 checks x64 and x86; real
sqlite-jdbc/slf4j/JNA and a JDK-made JAR read identically to `java.util.jar`; `test-phase8-inventory` 70 (x86 71),
`test-phase9-inventory-race` (incl. Phase9InventoryNoWriteTest) 23 on both runtimes.

## ≈14:45 — step ② `86c680b` Windows fix

`PROBE_TIMEOUT_MILLIS` 10 000 → 30 000 (≈3× worst measured cold start) and Sysnative PowerShell from 32-bit JVMs. Local:
x64 2.5–2.6 s (23 checks), x86 2.5–3.0 s (25 checks; WOW64 6 718 ms vs Sysnative 2 585 ms, same reply). Diagnostics
steps moved after the suites (`if: always()`) so the real suites meet the cold first probe.

## ≈15:00–17:30 — X-A-3 CI gate

- `86c680b`: Ingress, Spool, Phase VII–IX success; real-Server E2E failed in "Install Python Server deps…" (`pip`
  IncompleteRead, network) — infrastructure, recorded, not counted.
- Docs commit `94d2f15` (report/evidence/worklog, public-CI addenda, AGENTS/bootstrap, RLDI1 interface) = acceptance head:
  four workflows × three complete attempts all success (13 jobs each). WOW64 side-by-side first calls still reach 21.3 s,
  Sysnative ≤ 0.76 s — the 64-bit PowerShell change, not only the 30 s bound, removes the x86 risk.
- Workflow audit: no `continue-on-error`; `if:` only for Windows-specific setup/native steps and post-suite `always()`;
  one informational `|| true` (runtime print). No P1 structural false-success found.

## ≈17:40 — FAIL at the docs head `5119b48` (not a drive-gate failure)

Phase VII–IX run 39728825326 attempt 1: `phase9 (windows-latest)` job 119658888410 failed in `Phase9ReaderEntryTest`
(JDK17 x64) with `DeploymentBinding$Refusal: UNSAFE_ACCESS`, thrown from `DeploymentBinding.createRecord` when the test
wrote its RLDI1 record into a directory made with plain `Files.createTempDirectory` (all other fixtures use
`DeploymentBindingTest.privateDir`). On Windows the record inherits the temp directory's ACL; an inherited ALLOW-write ACE
for another principal is correctly refused. The three earlier attempts at `94d2f15` passed → depends on the runner's
inherited TEMP ACL. Local owned reproduction: inherited `Users` write ACE → `UNSAFE_ACCESS`; same after `privateDir` →
created. Classification: **Phase IX test-fixture defect (mine), product behaviour correct**. Fix: the four temp dirs of
`Phase9ReaderEntryTest` are made private like every other fixture (test-only change; product check unchanged). The other
three workflows at `5119b48` succeeded (Spool 39728825278, E2E 39728825153, Ingress 39728825155).
