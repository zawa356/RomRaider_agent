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

## Final head `94d2f15d0c7b54484b15e9748f9e50e46f319184` — three complete runs of all four workflows

Event `pull_request` (tests merge commit `77b6a5b` = `94d2f15` onto base `32b139f`; base is an ancestor, so the tree equals
`94d2f15`). Each attempt re-ran **all** jobs (`gh run rerun`, not `--failed`).

| Workflow | Run | Attempt 1 | Attempt 2 | Attempt 3 | Jobs (steps) |
|---|---|---|---|---|---|
| Isolated ingress journal prototype CI | 39724223654 | success | success | success | Fsync ubuntu (16; Windows-only TMP + native-identity steps skipped by design), Fsync windows (16), **Zulu8 x86** (12), PG16 (15; Windows TMP step skipped on ubuntu) |
| Product v1 Windows Agent spool CI | 39724223802 | success | success | success | durability ubuntu (10), windows (10) |
| Product v1 REAL Server E2E | 39724223775 | success | success | success | real-server-e2e (17): Server `zawa356/RomRaiderServer` pinned `d8c7a2f3b96ba278d269074dc40cca7741b4155b`; Real RLC2 Server HTTP E2E **35 checks** |
| Phase VII–IX synthetic CI | 39724223749 | success | success | success | phase7/8/9 × ubuntu (15; Windows-only setup skipped) and windows (16); Windows: 12 suites `--run-only` under **actual Java 8 x86, model 32** |

13 jobs per attempt, 0 non-success in any attempt (job lists: `logs/phase-xa/final-a{1,2,3}-jobs.txt` on the dev VM).
Drive probe after the suites, all `not_ok=0`, bound 30 000 ms:

| Attempt | x64 probe min–max (ms) over 5 Windows jobs | x86 probe (Sysnative) min–max | WOW64 32-bit PowerShell side-by-side (first call) |
|---|---|---|---|
| 1 | 340–489 | 336–433 | 1 392 – 8 948 |
| 2 | 272–517 | 272–755 | 3 478 – 14 266 |
| 3 | 255–738 | 254–589 | 5 924 – **21 270** |

Server (read-only, not re-run): PR #65 head `7455f83` — PoC 39535242731 attempt 2 success, contract 39564314019 success,
Java-Agent E2E 39564309265 success. That Server E2E uses its own pinned old Agent snapshot; it is **not** the Phase IX/X-A
Agent E2E (that is the Agent `product-v1-real-server-e2e` above).

The next commit (this evidence, docs only) gets its own CI run; its result is recorded in the PR #104 body.
