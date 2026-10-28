# Phase X-E evidence ledger

Code/test head: **`c309185`** (branch `phase-xe/ci-diagnostics`, base Agent `main` `cfa7543`). Docs commits after it change
only `docs/`. Evidence of a different head is labelled with that head and never merged into these rows.

## X-E0 — post-merge baseline (Agent `main` `cfa7543`, Server `main` `139da11`; not an X-E test)

| Workflow | Run | Head | Attempt | Jobs | Result |
|---|---|---|---|---|---|
| Phase VII–IX | [40439762112](https://github.com/zawa356/RomRaider_agent/actions/runs/40439762112) | `cfa7543` | 1 | 6 (phase7/8/9 × ubuntu/windows) | all success |
| Ingress | [40439761999](https://github.com/zawa356/RomRaider_agent/actions/runs/40439761999) | `cfa7543` | 1 | 4 (RLC2 PG16, fsync ubuntu/windows, Zulu8 x86) | all success |
| Spool | [40439761996](https://github.com/zawa356/RomRaider_agent/actions/runs/40439761996) | `cfa7543` | 1 | 2 (durability ubuntu/windows) | all success |
| REAL Server E2E | [40439761984](https://github.com/zawa356/RomRaider_agent/actions/runs/40439761984) | `cfa7543` | 1 | 1 | success |
| Server PoC (separate) | [40439786015](https://github.com/zawa356/RomRaiderServer/actions/runs/40439786015) | `139da11` | 1 | 2 (compose-smoke, supervisor-unit) | all success |

13/13 Agent jobs, no skipped job, no re-run. (At ChatGPT's check two of these runs were still in_progress.)

## X-E1 — V1LaneMigrationTest "injection actually reached prepared migration commit"

**Classification: HYPOTHESIS** (not ROOT_CAUSE_PROVEN). Reproduction: **NOT_REPRODUCED** (0/20 local).

Evidence for the hypothesis "a pre-hook `DeploymentBinding` refusal — most likely the 5 s `fsutil hardlink list` bound
(`HARDLINK_INSPECTION_UNAVAILABLE`) of the first `safe(source)` — was swallowed by `reject()`":

| Interval "dry-run creates no target" → "interrupted migration refuses" | Value |
|---|---|
| Failing x86 job 121944670338 (run 40429873076 attempt 1) | **5.13 s** |
| Same run attempt 2 x86 (121951002504) / x64 | 0.24 s / 0.13 s |
| `main` push x86 (121977024390) / x64 | 0.24 s / 0.17 s |
| Local `DeploymentBinding.safe` (incl. fsutil), first call per JVM, x64 / x86 | 211–806 ms / 239–264 ms |

Why not proven: the failing log has no exception (that is the defect fixed here), and fsutil is not the only pre-hook
step (`inspect`, `referenceSchema` temp DB, `LegacyWorkerGuard`). The new diagnostic would print e.g.
`at=DeploymentBinding.fsutil:104` (read bound) vs `:108` (exit/waitFor) if it recurs. No product change proposed: no
product defect was reproduced; a 5 s fsutil stall would still fail closed (correct).

| Step | Head | JDK17 x64 | actual Zulu8 x86 (`sun.arch.data.model=32`) | Linux JDK17 (VPS) |
|---|---|---|---|---|
| RED (`rejectAtInjection` = old catch-all) | `d70b9b8` | exit 1, sha256 `16db300e…3b98` | exit 1, identical log | NOT_RUN |
| GREEN `PhaseXeMigrationInjectionTest` | `e591d42`/`c309185` | 23 PASS | 23 PASS | 23 PASS (`c309185`) |
| `V1LaneMigrationTest` (uses `rejectAtInjection`) | `c309185` | 21 PASS | 21 PASS | 21 PASS |
| Repeat `V1LaneMigrationTest` (base `cfa7543`) | `cfa7543` | 10/10 PASS, 11.0–12.1 s | 10/10 PASS, 9.9–12.8 s | — |

GREEN cases: exact injected instance after the hook → accepted, atomic rollback (`lane_migration_v1` absent), original
bytes unchanged, retry completes and is idempotent. FAIL with diagnostic: non-dedicated target name
(`code=dedicated isolated target required`), missing source (`type=NoSuchFileException code=-`, path not printed),
real hard-link (`code=HARDLINK_ALIAS at=DeploymentBinding.singleLink:55` Windows / `:50` Linux), injected earlier-stage
`HARDLINK_INSPECTION_UNAVAILABLE`, same-text different instance after the hook (`hook_reached=true`), no exception. Every
printed diagnostic is asserted free of the fixture path, its directory name, `user.name`, `java.io.tmpdir`, `\` and `.db`.

## X-E2 — C4 `DRIVE_TYPE_UNPROVEN` (WindowsDriveGate)

**Classification: UNKNOWN** (runner-side cold start is the leading HYPOTHESIS; not proven). Reproduction:
**NOT_REPRODUCED** locally.

| Fact | Value |
|---|---|
| Failing calls | `Phase8ReadinessCliTest:90` → `Phase7Fixture` → `bindSynthetic` → `requireFixed:68`, JDK17 x64, job 121498362441 (run 40300936878 a1) and 121944670108 (run 40429872972 a1) |
| Position | first SYSTEM drive probe of the phase8 Windows job (the 5 preceding suites do not probe) |
| Interval "validator rejects a missing axis" → next line, 26 attempts since `86c680b` | success x64 6.03–15.03 s (24), failure **30.41 s**, **35.26 s**; x86 rerun 4.17–6.47 s |
| Failure kind in those logs | not recoverable: `requireFixed` discarded the `ProbeFailure` (fixed here) |
| Local fixture split (sampled), x64 / x86 | pre-probe 2.3–2.8 s / 2.1–2.7 s, first probe 2.1–3.0 s / 1.4–2.5 s, 2 probes per bind |
| Local `diagnose` idle / 12 parallel JVMs, x64 | 2.18–2.49 s / 1.53–2.52 s, all `OK_LOCAL_FIXED` |
| Local `diagnose` idle / 12 parallel JVMs, x86 (Sysnative) | 2.15–2.78 s / 1.09–2.22 s, all `OK_LOCAL_FIXED` |

Separation: *test-origin* — no (the fixture is the first legitimate cold probe; nothing in the test holds the probe);
*implementation defect* — not shown (wrong answers never observed; replies are judged as before; the bound only limits
waiting); *runner environment* — consistent (bimodal: ≈8 s normally, ≥30 s twice, only on the job's first PowerShell/csc
start, as in X-A) but not proven. The 30.41 s case is at the edge of "TIMEOUT after ≥0.4 s of fixture work"; it could
also be another kind after slow fixture work — exactly what the new cause chain will show (`probe=TIMEOUT ms=30003`).

Change (product, observability only): `requireFixed` chains the gate's own `ProbeFailure` as cause;
`ProbeFailure.toString()` = `WindowsDriveGate.ProbeFailure: DRIVE_TYPE_UNPROVEN probe=<kind> ms=<n>`. Not done (by
instruction): timeout extension, retry, warm-up, relaxed drive condition, fallback, RUNNER_OS bypass.

| Step | Head | JDK17 x64 | actual Zulu8 x86 | Linux JDK17 |
|---|---|---|---|---|
| RED `PhaseXeDriveDiagnosticTest` (no cause) | `dccc63f` | exit 1, sha256 `38111933…cd7e` | exit 1, identical | NOT_RUN |
| GREEN | `c309185` | 30 PASS | 30 PASS | 30 PASS |
| `test-phase-xa-drive.sh` (X-A + X-E2, real probes on Windows) | `c309185` | 54 PASS | 55 PASS | 50 PASS |
| `test-phase8-readiness.sh` | `cfa7543`+X-E1 (`e591d42`) | 23 PASS, 218 s | 23 PASS, 167 s | — |

## X-E3 — regression at `c309185`

Local Windows 11 dev VM (12 vCPU), session-owned TMP, JDK17 x64 + actual Zulu8 1.8.0_504 x86 (`model=32`, the
`--run-only` rerun of `scripts/ci-phase7-9.sh`). PASS/FAIL = count of lines starting with `PASS`/`FAIL` in the log.

| Suite | exit | Duration | PASS lines | FAIL lines |
|---|---|---|---|---|
| `scripts/run-acceleration-tests.ps1` (x64; PostgreSQL preview NOT_RUN, no protected DSN) | 0 | 5226 s | 3241 | 0 |
| `ci-phase7-9.sh phase7` (x64 + x86 rerun) | 0 | 3901 s | 570 | 0 |
| `ci-phase7-9.sh phase8` (x64 + x86 rerun; includes `Phase8ReadinessCliTest` real drive probes) | 0 | 5360 s | 5670 | 0 |
| `ci-phase7-9.sh phase9` (x64 + x86 rerun) | 0 | 2109 s | 956 | 0 |

Linux JDK17 (VPS sandbox, `c309185`): `test-phase2-lanes.sh` 109 PASS, `test-phase-xa-drive.sh` 50 PASS (worklog 10).
Full phase7–9 on Linux and Docker: covered by the PR's ubuntu CI jobs (recorded below), not run locally.

## X-E3 — CI of the Draft PR

Recorded after the push; every run, attempt and job, including failed first attempts.

## Not run / unchanged

Docker host: NOT_RUN (no Server/PG/wire change). Windows 10 non-car VM: NOT_RUN (not reachable/documented). Real
Writer, X270/ECU, JNA redistribution, ACL effective rights, FILE_SHARE_DELETE, native OS minimum: open gates, untouched.
Logs/diagnostics contain no secret, vehicle data, user path, credential or command argument (asserted for the new
diagnostics; `hosts.env` never printed). Flags unchanged: synthetic_only, BLOCKED, launch/production/ACK false,
formal_ack DISABLED, reader_adoption BLOCKED, global_old_binary_exclusion NOT_PROVEN, WRITER_UNPROVEN; RLDR1/RLDI2 default-off.
