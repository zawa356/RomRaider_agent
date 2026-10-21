# Public CI restart worklog (append-only) — Claude Code, 2026-10-21 JST

Instructions: owner-supplied `RomLogger_ClaudeCode_CI_Restart_Public_20261021.md` (not in Git). Both repositories were
made public by the owner on 2026-10-21. No merge, rebase, force push, tag, release, billing or runner change.

## ≈16:35 JST — CI-0 runner proof

- `gh repo view`: RomRaider_agent PUBLIC, RomRaiderServer PUBLIC; Actions enabled, `allowed_actions=all`.
  Agent main `4f80c5e`, Server main `d8c7a2f`; #100 head `d37c3bd`, Server #65 head `7455f83` (= snapshot).
- Agent `workflow_dispatch` product-v1-spool-ci on `phase9/distribution-hardening` → run 39563411217: **runners started**
  (10 steps per job) → **PUBLIC_RUNNER_CONFIRMED**. Both jobs FAILED at "Compile Java 8-compatible durable spool":
  `Phase9CliProcessTest.java` (product/v1 test, added in Phase IX `bc4226d`) references `prototype.ingress`
  classes; the script compiles `src/test/java/.../product/v1/*.java` without a sourcepath → 14 javac errors. A real CI
  defect present on #97…#100 heads (it was never seen because CI never ran).
- Server: one rerun of #65 PoC run 39535242731 → attempt 2 **success** (supervisor-unit 8 steps, compose-smoke 11 steps).

## ≈16:40 JST — CI-1/CI-2 branch `ci/public-restart` (Draft PR #101, base #100)

Commit `693aa28`: spool script `-sourcepath src/main/java` (local spool suite GREEN first); real-Server E2E without
secret (public Server checkout read-only at pinned `d8c7a2f`, SHA asserted, checkout failure fails the job, private-token
history kept as a comment); new `phase7-9-synthetic-ci.yml` + `scripts/ci-phase7-9.sh` (Linux + Windows, Windows re-runs
every suite under actual Zulu 8 x86 with data model 32 asserted). Push + PR triggered duplicate Phase VII-IX runs; the
push-triggered 39564169918 was cancelled by me to save runner capacity (the PR run 39564212325 tests the same SHA).

Results at `693aa28`:
- Real-Server E2E PR run 39564212303: **success**, 17 steps, 0 skipped. Log shows actual PostgreSQL 16.15 service,
  "Real Server incremental/Journal/Journal Worker E2E: PASS", "REAL PostgreSQL verification: PASS (TSV 3 + journal 3 +
  CLI Worker 1, three profiles, no duplicates)", "Real RLC2 Server HTTP E2E passed 35 checks; test ACK only".
- Spool PR run 39564212197: **success** ubuntu + windows (10 steps each).
- Server product-v1-ci 39564314019 and Java-Agent E2E 39564309265 dispatched on #65 head (results below).

## ≈16:50–17:00 JST — first full runs at `693aa28`, fixes at `a590d6f`

- Ingress (dispatch 39564209789) Windows + x86 batch FAILED: `V1LaneMigrationTest` "injection actually reached prepared
  migration commit". Local run passes. Cause: runner `%TEMP%` = 8.3 short path → `DeploymentBinding.safe` PATH_ALIAS
  before the hook (product guard correct). Phase VII–IX (39564212325): Windows "x86 runtime reports data model '64'"
  (my bash PATH bug, caught), RLWR1 `READER_PATH_CHANGED` in scenarios and RLDI1 UNAVAILABLE in the real-install check
  (same 8.3 cause). Linux all success.
- Fix `a590d6f`: Windows jobs `TMP/TEMP=$RUNNER_TEMP`; `cygpath -u` for the x86 home; no duplicate push triggers.
  Result: all four workflows success (39565160914 incl. x86 batch model 32; 39565160789 6/6 jobs; E2E; spool).

## ≈17:05–17:45 JST — CI-4 old heads, CI-5 P1/P2

- Original heads dispatched (workflow files of that head): #80/#96 ingress Windows FAIL (C2), Linux/PG PASS; spool
  #80/#86/#92/#96 PASS, #97 FAIL (C1, expected). Verification branches (original + TMP step only): #80 PASS; #96, #86,
  #92 first attempt FAIL with `DRIVE_TYPE_UNPROVEN` / x86 V1LaneMigration refusal (C4, probe timeouts), one `--failed`
  rerun each → all PASS. One rerun per run only.
- Duplicate push-triggered runs 39564169918 (Phase VII–IX) and 39568445347 (ingress on fix/**) were cancelled by me to
  save runner capacity; the PR runs test the same SHAs.
- Server #62/#63/#64: one rerun each of the latest (pre-public, steps=0) PoC run → success.
- #102 P1 `build-offline-tools.sh`: RED 6 with owned fixtures. Note: the RED run of `build/` wiped this worktree's own
  `build/` (regenerable outputs incl. the copied JNA JAR, restored from the frozen checkout and re-verified by SHA-256)
  and `build/../../<owned dir>` deleted the owned outside fixture — exactly the defect. GREEN 10; CI PASS.
- #103 P2 RLDI1 temp-file write: RED (UNAVAILABLE without writable tmpdir, log 46f3f9b9…), in-memory ZIP parse, GREEN;
  all four workflows PASS at `763e6fe`.
- Docs: `public_ci_recovery_2026-10-21.md`, `public_ci_validation_2026-10-21.md` (on #103).
