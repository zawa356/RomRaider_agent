# Phase X-B integration evidence (2026-10-22 JST)

## Git

- main `4f80c5e9831ec39368f4e771d829337598eeaa76`; #104 head `e8e2c54999a6edb6d4a41a996fe2721f342e56af` (tree `c2287e5e…`).
- Integration branch `integration/phase-v-xa-to-main`, created at `e8e2c54`:
  - `4cb627d` merge #92 head `ae5ff53a4ed2ebf531546eade4b77d0cdcf513c1` (clean; +2 docs, 140 lines)
  - `7010e37` merge #91 head `71f72c58c240efe5df118f7e1c8c5015d472fc8c` (AGENTS.md one-line conflict resolved; 6 docs)
  - `2c8a5a3` X-B RED test; `dae1848` X-B fix (`DistributionInventory.java`); `fb9fd56` CI driver line + RLDI1 doc
  - later: this report/evidence/worklog (docs only)
- Ancestors of the code head `fb9fd56`: every head of #80–#104 (25/25). main..fb9fd56 = 81 commits (3 merges incl. none
  rewritten), 141 files vs main. vs `e8e2c54`: product code changed only in `src/main/java/.../DistributionInventory.java`.
- Recovered commits now reachable: `e4dc146`, `01cd886`, `71f72c5` (#91), `ae5ff53` (#92).
- PR #105 merge ref `8d0cea5d35a7443771861a058a413dde03ca153f` (parents `4f80c5e`, `fb9fd56`): tree
  `80033952cfff23ac9c77649ff3d10a6ce7ff9d8e` = head tree → CI tested exactly the head tree.

## Local (Windows dev VM, synthetic)

| Head | Runtime | Command | Exit | Result | Log SHA-256 (C:\Dev\RomLogger\logs) |
|---|---|---|---|---|---|
| 2c8a5a3 (pre-fix) | JDK17 x64 | `test-phase-xb-zip.sh --expect-red` | 0 | RED_COUNT 12 (all malformed/oversized non-class cases MATCHED) | ad2704b000d42bb2bf0d53c00f77d3c14bb4fcf2f815e20d1a1c2c7e385c8b39 |
| fix (= dae1848 code) | JDK17 x64 | `test-phase-xb-zip.sh` | 0 | 26 checks | 6d3b6f202487d43d7bb464925187da6affa98d4210b8652e5aa2704af089807d |
| fix | JDK17 x64 | `test-phase-xa-zip.sh` / `test-phase8-inventory.sh` / `test-phase9-inventory-race.sh` | 0/0/0 | 45 / 70 / 23 PASS lines | `xb-reg-x64-*.log` |
| fix | actual Zulu8 x86 (model 32) | `--run-only` xb-zip / xa-zip / phase8-inventory / phase9-race | 0/0/0/0 | 26 / 45 / 71 / 23 | `xb-reg-x86-*.log` |

The first RED attempt did not compile (the test referenced the new constants/method). It was not a product result. The test was changed to use local bound constants plus reflection, and the unpushed commit was amended to `2c8a5a3` before any push.

## GitHub Actions on code head `fb9fd56` (pull_request, attempt 1, all success)

| Run | Workflow | Jobs (steps executed; skipped = Windows-only steps on Linux jobs) |
|---|---|---|
| [39754534978](https://github.com/zawa356/RomRaider_agent/actions/runs/39754534978) | Isolated ingress journal prototype CI | Zulu8 x86 native identity 12/0 skip · Fsync windows 16/0 · Fsync ubuntu 16/2 · RLC2 PG16 15/1 |
| [39754535016](https://github.com/zawa356/RomRaider_agent/actions/runs/39754535016) | Product v1 Windows Agent spool CI | durability ubuntu 10/0 · durability windows 10/0 |
| [39754534984](https://github.com/zawa356/RomRaider_agent/actions/runs/39754534984) | Product v1 REAL Server E2E (Server pinned) | real-server-e2e 17/0 |
| [39754535004](https://github.com/zawa356/RomRaider_agent/actions/runs/39754535004) | Phase VII–IX synthetic | phase7/8/9 × ubuntu (15/3 each) and windows (16/0 each) |

13/13 jobs success, no failed step, no retry needed. The X-B test ran in phase8: ubuntu "passed 24 checks" (no JNA JAR on
Linux), windows "passed 26 checks" twice (JDK17 x64 and actual Zulu8 x86 `--run-only`). Job log SHA-256 prefixes:
ubuntu phase8 `30f64fff370d03fe`, windows phase8 `cd1951b4ccc53f8b`.

### Head `15e4ebb` (report commit; docs only) — one FAILED job, kept on record

- `pull_request` runs: Phase VII–IX [39756631029](https://github.com/zawa356/RomRaider_agent/actions/runs/39756631029) **failure**. The failure is in phase8 (windows-latest), step "Drive probe diagnostics after the suites", which runs after the suites had passed (X-B 26 checks on x64 and x86).
  - Cause: `PhaseXaDriveProbeTest` called the 32-bit WOW64 PowerShell, a test-only reference path the product no longer uses from an x86 JVM. The call hit the 30 s probe bound: 30.09 s between the last PASS and `ProbeFailure: DRIVE_TYPE_UNPROVEN`.
  - Classification: timing-dependent test diagnostic, not a product or X-B regression.
  - Job log SHA-256 d2b21f975dcc4d78c5f09589cfd66d8c86993e0b96023d3095f46dafaf8a0f65.
- Same SHA, other runs:
  - Ingress PR [39756631064](https://github.com/zawa356/RomRaider_agent/actions/runs/39756631064): success.
  - REAL Server E2E PR [39756631032](https://github.com/zawa356/RomRaider_agent/actions/runs/39756631032): success.
  - `workflow_dispatch` runs: Phase VII–IX [39756641142](https://github.com/zawa356/RomRaider_agent/actions/runs/39756641142), REAL Server E2E [39756635899](https://github.com/zawa356/RomRaider_agent/actions/runs/39756635899), Spool [39756631185](https://github.com/zawa356/RomRaider_agent/actions/runs/39756631185): all success.
  - Ingress dispatch 39756625481 and Spool PR 39756631033 were cancelled by the workflows' own concurrency groups (0 steps; duplicate runs, not test results).
- Fix `7055e2e` (test only): query the Sysnative product path first (failure there stays fatal). A TIMEOUT of the WOW64 reference is reported `NOT_RUN` and never counted as PASS; any other failure kind or a different reply still fails. Local x64 / x86 pass (x86: WOW64 10.4 s, Sysnative 2.7 s, same reply).

### Final code/test head `7055e2e38ba38f7cf544812fca53c84df2f7d624` — pull_request, attempt 1, 13/13 success

| Run | Workflow | Jobs (steps; skipped = Windows-only steps on Linux) |
|---|---|---|
| [39759033374](https://github.com/zawa356/RomRaider_agent/actions/runs/39759033374) | Isolated ingress journal prototype CI | PG16 15/1 · Zulu8 x86 native identity 12/0 · Fsync windows 16/0 · Fsync ubuntu 16/2 |
| [39759033278](https://github.com/zawa356/RomRaider_agent/actions/runs/39759033278) | Product v1 Windows Agent spool CI | durability ubuntu 10/0 · windows 10/0 |
| [39759033260](https://github.com/zawa356/RomRaider_agent/actions/runs/39759033260) | Product v1 REAL Server E2E (Server d8c7a2f pinned) | real-server-e2e 17/0 |
| [39759033222](https://github.com/zawa356/RomRaider_agent/actions/runs/39759033222) | Phase VII–IX synthetic | phase7/8/9 × ubuntu 15/3 · windows 16/0 |

- No failed step. phase8 windows: X-B "passed 26 checks" twice (JDK17 x64 and actual Zulu8 x86). x86 WOW64 reference 18 377 ms vs Sysnative 461 ms, same reply, PASS.
- Merge ref `d2bd2053be55ae14ca0605875b5b9283533e8716` (parents `4f80c5e`, `7055e2e`): tree `118cca1d9bf2fce66941de224cef4ad837c49246` = head tree.
- 25/25 PR heads are ancestors; main..7055e2e = 83 commits, 144 files; main still `4f80c5e`.
- Code/script changes vs `e8e2c54`: `DistributionInventory.java` (ZIP fix), `PhaseXbZipAllEntriesTest.java`, `PhaseXaDriveProbeTest.java` (diagnostic), `test-phase-xb-zip.sh`, `ci-phase7-9.sh` (1 line).

Later commits after `7055e2e` are documentation only. Their CI results are posted as a PR comment on #105.

## Server

`integration/server-phase-v-ix-docs`, created at #65 head `7455f837…`, plus one docs commit `9f1ff1a`. Contains #62–#65 heads; 8 commits vs main; only `docs/implementation_progress.md` changes. Draft [#66](https://github.com/zawa356/RomRaiderServer/pull/66).
- CI: PoC checks [39754716469](https://github.com/zawa356/RomRaiderServer/actions/runs/39754716469) (pull_request) success.
- CI: Product v1 contract [39754789849](https://github.com/zawa356/RomRaiderServer/actions/runs/39754789849) success (workflow_dispatch; its path filter skips docs).
- CI: Java Agent→PostgreSQL E2E [39754795566](https://github.com/zawa356/RomRaiderServer/actions/runs/39754795566) success (workflow_dispatch; fixed old Agent snapshot, not the latest Agent).
