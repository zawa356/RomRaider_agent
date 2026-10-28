# Phase X-E append-only worklog

Policy: append only; exact SHAs, commands, denominators and NOT_RUN. Earlier entries are never edited.

## 2026-10-28 JST — Claude Code: X-E0 baseline, X-E1, X-E2 (Windows 11 dev VM)

1. **Baseline (GitHub API, 2026-10-28):** Agent `main` `cfa7543fbf7de9d2aad1a38f064337b26a6784aa` (merge of #108, parents
   `1fd3816` = #107 merge and `9757533` = #108 head), Server `main` `139da11927624887006cd98f1b8d6d7dba4e3d97` (#67).
   #107 merged 2026-10-27 02:39:52Z, #108 02:40:06Z, Server #67 02:40:19Z. Open PRs: Agent 0, Server 0.
   The ChatGPT instruction listed Phase VII–IX and ingress as in_progress: both have since **completed/success**, attempt 1
   (13 jobs total, table in evidence). Server PoC `40439786015` success (2 jobs).
2. **Workspace:** new worktree `C:\Dev\RomLogger\phase-xe-work`, branch `phase-xe/ci-diagnostics` created from
   `origin/main` `cfa7543`, upstream removed (no accidental push to `main`). No existing branch/worktree reused or edited.
   Test jars copied from the existing `phasexd-work` clone (read only) and verified against `lib/test-dependencies.sha256`
   and the pinned JNA hash `34ed1e1f…c8fe8c6`. Runtime TMP: a session-owned `%TEMP%\xe\t` (the VM's `%TEMP%` already
   holds 10 825 old `romlogger-*` fixtures — owner cleanup, untouched).
3. **X-E1 CI log analysis** (existing logs only, no re-run): in job 121944670338 the x86 rerun printed
   "dry-run creates no target" at 01:06:50.006 and "interrupted migration refuses" at 01:06:55.132 — **5.13 s** — then
   the `injected[0]` assertion failed. Same interval in the passing x86 runs: 0.24 s (attempt 2, job 121951002504) and
   0.24 s (main push, job 121977024390); x64 0.13–0.17 s. Code reading: `migrateCopy` calls `DeploymentBinding.safe(source)`
   before the hook; on Windows `safe` → `singleLink` → `fsutil hardlink list` with a **5 s** read bound
   (`DeploymentBinding.fsutil`, `HARDLINK_INSPECTION_UNAVAILABLE`). `preflight` (the previous check) never calls `safe`,
   so this is the first fsutil launch of that JVM. `reject()` accepted that refusal as "interrupted migration refuses".
4. **X-E1 reproduction:** `DeploymentBinding.safe` timing probe (measurement class outside the repo), 6 JVMs × 5 calls each
   arch: x64 first 211–806 ms, later 166–201 ms; x86 first 239–264 ms, later 184–214 ms. `V1LaneMigrationTest` 10× JDK17
   x64 (11.0–12.1 s) and 10× Zulu8 x86 (9.9–12.8 s): **20/20 PASS → NOT_REPRODUCED.**
5. **X-E1 RED `d70b9b8`:** `rejectAtInjection` stub (old catch-all) + `PhaseXeMigrationInjectionTest`. x64 and x86 exit 1:
   the pre-hook target-name refusal printed "PASS xe1 probe" (the masking) and the next check stopped the run; logs
   byte-identical, sha256 `16db300e…3b98`.
6. **X-E1 GREEN `e591d42`:** identity + hook-reached check with path-free diagnostic; the first product frame skips the
   generic `require` helper (first GREEN attempt failed its own hard-link check because `at=` named `require`; fixed before
   the commit). JDK17 x64 and Zulu8 x86: `PhaseXeMigrationInjectionTest` 23 checks, `V1LaneMigrationTest` 21 checks.
7. **X-E2 CI log analysis:** both C4 failures are the same call: `Phase8ReadinessCliTest:90` → `Phase7Fixture` →
   `bindSynthetic` → first `requireLocalDrives` → `requireFixed:68`, JDK17 x64, after "validator rejects a missing axis".
   None of the five earlier phase8 suites runs a SYSTEM drive probe, so this is the **first PowerShell probe of the job**.
   Interval "validator…" → next line over the 26 phase8 Windows attempts since the 30 s bound (`86c680b`): x64 success
   6.03–15.03 s (median ≈8.5 s), failures **30.41 s** and **35.26 s**; x86 rerun 4.17–6.47 s. The kind was discarded by
   `requireFixed`, so TIMEOUT vs EXIT_n/START is not recoverable from these logs.
8. **X-E2 local measurement:** fixture split by sampling the main thread (outside the repo): pre-probe 2.1–2.8 s, first
   probe 1.4–3.0 s, 2 probes per `bindSynthetic`, both arches. `diagnose` idle x64 2.18–2.49 s / x86 2.15–2.78 s; with 12
   parallel migration JVMs on 12 vCPU x64 1.53–2.52 s / x86 1.09–2.22 s. All `OK_LOCAL_FIXED`. **NOT_REPRODUCED.**
   `test-phase8-readiness.sh` x64 23 PASS (218 s) / x86 23 PASS (167 s).
9. **X-E2 RED `dccc63f`:** `PhaseXeDriveDiagnosticTest`, x64/x86 exit 1 at "the probe failure is the cause", sha256
   `38111933…cd7e`. **GREEN `c309185`:** cause chaining + `ProbeFailure.toString`; 30 checks; `test-phase-xa-drive.sh`
   x64 54 PASS / x86 55 PASS.
10. **Linux (VPS, pinned key OK, user `claude_codex`, JDK17 17.0.20.1, 4 vCPU):** private `umask 077` sandbox
    `/tmp/romlogger-phasexe-c309185-<epoch>`, tarball of `git archive c309185` + pinned jars (sha256 `7433a774…b5f5`,
    jar hashes OK). `test-phase2-lanes.sh` exit 0, 109 PASS (X-E1 23, hard-link via `unix:nlink` → `singleLink:50`).
    `test-phase-xa-drive.sh` first exit 1 — **my environment**: I had set `JAVA_TOOL_OPTIONS`, whose "Picked up" stderr
    line made the "silent child" case non-silent; rerun without it: exit 0, 50 PASS. `/tmp/romlogger-*` count 552 before
    and after (no fixture left). Sandbox removed by exact path.
11. **Docker host: NOT_RUN** — no Server, PostgreSQL, wire or spool change; the isolated real Server E2E runs in CI.
12. **X-E3 local regression at `c309185`** (2026-10-28 10:41–15:17 JST, session-owned TMP, JDK17 x64 + Zulu8 x86 `model=32`):
    `run-acceleration-tests.ps1` exit 0 (5226 s, 3241 PASS / 0 FAIL, PostgreSQL preview NOT_RUN);
    `ci-phase7-9.sh` phase7 exit 0 (3901 s, 570 / 0), phase8 exit 0 (5360 s, 5670 / 0), phase9 exit 0 (2109 s, 956 / 0).
    No C4 and no X-E1 failure locally. Next: commit docs, push `phase-xe/ci-diagnostics`, Draft PR, record CI.
