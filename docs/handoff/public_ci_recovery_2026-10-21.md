# Public CI recovery report — 2026-10-21 (Claude Code)

Read with: [validation table](public_ci_validation_2026-10-21.md) · [append-only worklog](public_ci_worklog_2026-10-21.md) ·
Phase IX: [report](phase9_report.md), [evidence](phase9_validation_evidence.md).

## Outcome

- **Runners:** after the owner made `zawa356/RomRaider_agent` and `zawa356/RomRaiderServer` public, GitHub-hosted
  runners start (`PUBLIC_RUNNER_CONFIRMED`). Every earlier `failure` with `steps=0` remains classified **CI NOT RUN**
  (runner never started); those runs are kept, not re-labelled.
- **Agent top of stack** (`fix/rldi1-no-temp-write`, PR #103, `763e6fe`, which contains #80…#102): all four Agent
  workflows **success** with real steps — ingress (Linux, Windows, **actual Zulu 8 x86** batch, PostgreSQL16 preview),
  product spool (Linux, Windows), **real Agent→FastAPI→PostgreSQL16 E2E against the public Server pinned at `d8c7a2f`**,
  and the new Phase VII–IX workflow (Linux + Windows; Windows re-runs every suite under actual Java 8 x86, model 32).
- **Server:** PoC, product-v1 contract CI (PostgreSQL16 ACK/auth + container build) and the pinned Java-Agent E2E all
  **success** on the #65 head `7455f83`. No Server change was needed.
- **Merge: NOT DONE.** No PR was made ready, merged, rebased or force-pushed; no tag/release/deploy/billing/visibility change.

## Defects and environment issues found by the first real CI runs

| # | Kind | Finding | Resolution |
|---|---|---|---|
| C1 | **Code (test build) defect** | `scripts/test-product-v1-spool.sh` compiles every `product/v1` test without a sourcepath; Phase IX `Phase9CliProcessTest` (product/v1) references `prototype.ingress` → 14 javac errors on Linux and Windows. Present on #97…#100 heads (run 39563411217 at `d37c3bd`; original #97 head run 39568619064). | `-sourcepath src/main/java` in #101 (`693aa28`). #80/#86/#92/#96 original heads: spool success. |
| C2 | CI environment (product guard working as designed) | Windows hosted runner `%TEMP%` is the 8.3 short path `C:\Users\RUNNER~1\…`. `DeploymentBinding.safe` refuses the alias (`PATH_ALIAS`) → V1LaneMigration/RLDI1 fixtures refused; RLWR1 refuses with `READER_PATH_CHANGED`. Locally never seen (user name has no short form). This is the first real evidence that the 8.3 refusal (Phase IX review note) works. | Windows jobs set `TMP`/`TEMP` to `RUNNER_TEMP` (long name) — #101 `a590d6f`. Original #80 head + only this step: ingress all green. |
| C3 | CI script defect (mine) | `ci-phase7-9.sh` put a Windows-form `JAVA_HOME` into a bash `PATH` prefix, so the "x86" re-run still found JDK17; the model-32 assertion caught it and failed the job (no false PASS). | `cygpath -u` (#101 `a590d6f`). |
| C4 | **Intermittent, fail-closed probe timeouts on hosted runners** | `WindowsDriveGate` (PowerShell `Add-Type`, 10 s) → `DRIVE_TYPE_UNPROVEN`, and the x86 V1LaneMigration refusal at #86 (fsutil identity probe, 5 s, suspected). Seen on verification runs of #92/#96 (x86 batch) and #86; #96 passed on attempt 2. | Not changed (product timeouts are a safety decision). Recorded as an open P2 for the owner: CI flakiness vs. probe timeout policy. |
| C5 | Workflow precondition | Real-Server E2E needed a private-repo token and otherwise declared NOT RUN. | Public checkout, Server SHA pinned `d8c7a2f`, SHA asserted, checkout failure fails the job, no secret (#101). |

## Review follow-ups fixed in separate stacked PRs (RED → GREEN)

- **#102 (P1)** `build-offline-tools.sh` OUT_DIR: `build/*` string check accepted `build/../..`, `build/`, `build/./x`
  and links; RED with owned fixtures (an owned outside dir was deleted; `build/` wiped this worktree's own build output).
  Component-wise guard; 10 checks GREEN; in CI (Linux symlink, Windows junction).
- **#103 (P2)** RLDI1 `--inventory` wrote a temp JAR (read-only contract violated; UNAVAILABLE without writable temp).
  In-memory central-directory parse of exactly the hashed bytes; RED/GREEN + local/central name-skew case; A-B-A
  regression still GREEN.

## How to update the pinned Server SHA (real-Server E2E)

1. Pick a reviewed Server commit (normally Server `main` after an approved merge); record why.
2. Change `SERVER_SHA` in `.github/workflows/product-v1-real-server-e2e.yml` in a Draft PR; the job asserts the checked-out
   SHA. 3. The run must show the PostgreSQL service, "Real Server … E2E: PASS" lines and the RLC2 HTTP 35 checks. 4. Never
   point it at a moving ref or add a token; both repositories must stay public for this job (if either becomes private
   again, the checkout fails and the job fails — it must not be turned back into a silent NOT RUN).

## Safety statement (unchanged)

synthetic_only=true; state=BLOCKED; launch_authorized=false; production_authorized=false; ack_eligible=false;
formal_ack=DISABLED; writer_quiescence=WRITER_UNPROVEN; global_old_binary_exclusion=NOT_PROVEN; Windows ACL, process
provenance and directory durability unproven; RLWR1 adoption BLOCKED. CI uses only synthetic fixtures and disposable
PostgreSQL services; `contents: read`; no secrets; no `pull_request_target`; no X270/ECU/RomRaider/production access.

## Addendum 2026-10-22 (Phase X-A): Windows failures at the final head `32b139f`

The rows above for #103 describe the last **code** head `763e6fe` (all four workflows success) and remain true. The
documentation-only final head `32b139ff55b878ddaaf9fdbbe63086621b7dd5b3` was **not** all green: on 2026-10-21 ≈08:58–09:01
UTC (17:58–18:01 JST) Spool 39573357800 and real-Server E2E 39573358311 succeeded, but Ingress 39573354521 (Windows job
119132739485, `DeploymentBindingTest`) and Phase VII–IX 39573358384 (Windows phase7 job 119132829917, `Phase7EntryTest` on
JDK17 and `Phase7ReviewC2Test` on Zulu8 x86) **failed with `DRIVE_TYPE_UNPROVEN`**. Phase X-A measured these as the
WindowsDriveGate 10 s probe timeout on the first (cold) PowerShell probe of a fresh runner; analysis, fix and new runs:
[Phase X-A report](phase_xa_ci_stability_report.md) / [evidence](phase_xa_ci_stability_evidence.md) (PR #104).
