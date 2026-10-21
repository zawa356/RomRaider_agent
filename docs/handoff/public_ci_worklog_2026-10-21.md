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
