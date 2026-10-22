# Phase X-A evidence (2026-10-22 JST)

Repo `zawa356/RomRaider_agent` unless noted. PR #104 = branch `ci/phase-xa-windows-probe` (base `fix/rldi1-no-temp-write`).
Run URL = `https://github.com/zawa356/RomRaider_agent/actions/runs/<run>`. "steps" = executed steps of the job; a skipped
step is listed only when it is a required one. Windows x86 = actual Zulu 8 (`sun.arch.data.model=32` asserted).

## RED (before fixes)

| What | Head | Run / log | Result |
|---|---|---|---|
| Windows Ingress | `32b139f` | 39573354521 job 119132739485 | **failure** `DRIVE_TYPE_UNPROVEN` in `DeploymentBindingTest` (10.02 s gap = probe timeout) |
| Windows Phase VII | `32b139f` | 39573358384 job 119132829917 | **failure** `DRIVE_TYPE_UNPROVEN` in `Phase7EntryTest` (JDK17, 11.2 s) and `Phase7ReviewC2Test` (Zulu8 x86, 11.4 s) |
| ZIP audit `--expect-red` | `4680f69` test on unfixed parser (local, JDK17 x64) | `logs/phase-xa/zip-red-x64.log` | **15 malformed archives accepted** incl. manifest decoy → RLDI1 MATCHED |

## Local (dev VM, Windows 11, not CI)

| Suite | Head | JDK17 x64 | actual Zulu8 x86 |
|---|---|---|---|
| `test-phase-xa-drive.sh` | `d2bc329` | exit0, 22 checks, probe 2.5–3.1 s | exit0, 23 checks, probe 5.2–7.2 s (WOW64) |
| `test-phase-xa-drive.sh` | `86c680b` (working tree = commit) | exit0, 23 checks, 2.5–2.6 s | exit0, 25 checks, 2.5–3.0 s; WOW64 6 718 ms vs Sysnative 2 585 ms, same reply |
| `test-phase-xa-zip.sh` | `c49b8ac` | exit0, 45 checks | exit0, 45 checks |
| `test-phase8-inventory.sh` | `c49b8ac` | exit0, 70 PASS lines | exit0, 71 |
| `test-phase9-inventory-race.sh` (incl. NoWrite) | `c49b8ac` | exit0, 23 | exit0, 23 |
| Linux | — | NOT_RUN locally (covered by CI ubuntu jobs) | — |

## CI

| Head | Workflow | Run | Attempt | Jobs (result, steps) | Notes |
|---|---|---|---|---|---|
| `d2bc329` | Phase VII–IX | 39721643368 | 1 | 3× ubuntu success (15); 3× windows **failure** in the diagnostics step | x64 first probes 3 312 / 9 692 / 8 211 ms then 322–499 ms; x86 half failed on my PATH bug (Windows path in bash PATH → JDK17 ran, model check failed closed) |
| `d2bc329` | Spool | 39721643479 | 1 | success | |
| `d2bc329` | real-Server E2E | 39721643365 | 1 | success | |
| `d2bc329` | Ingress | 39721643480 | 1 | Windows/ubuntu Fsync + PG success; x86 job superseded by the next push | not counted |
| `86c680b` | Phase VII–IX | 39722969464 | 1 | phase7/8/9 × ubuntu/windows **all success** (15/16 steps) | x64 after-suite probes 270–443 ms, x86 Sysnative 261–395 ms; WOW64 side-by-side 28 695 / 14 396 / 6 378 ms; ZIP audit 45 (Windows x64 & x86) / 44 (ubuntu, no JNA jar) |
| `86c680b` | Ingress | 39722969544 | 1 | Fsync windows/ubuntu success (16), Zulu8 x86 job success (12), PG16 success (15) | x86 job WOW64 920 ms vs Sysnative 256 ms |
| `86c680b` | Spool | 39722969628 | 1 | durability ubuntu/windows success (10) | |
| `86c680b` | real-Server E2E | 39722969631 | 1 | **failure** — step "Install Python Server deps…": `pip` `IncompleteRead` (network) | infrastructure; not a test result; not counted |

Final-head results (all four workflows, repeated full runs) are appended below.
