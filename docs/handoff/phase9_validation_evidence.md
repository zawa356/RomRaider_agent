# Phase IX validation evidence (exact heads, 2026-10-21 JST)

Windows 11 dev VM DESKTOP-KVHGDS9 only (not the X270). Synthetic owned fixtures only. JDK17 = Zulu 17.0.20.1 x64
(compiles `--release 8`); x86 = actual Zulu 8u504 win32 (`ROMLOGGER_EXPECT_DATA_MODEL=32 … --run-only`, the scripts exit
before testing if `sun.arch.data.model` differs). Test JARs verified against `lib/test-dependencies.sha256`; JNA 5.14.0
SHA-256 34ed1e1f…68c6 checked by the driver and scripts. Logs: `C:\Dev\RomLogger\logs\phase9\…` (not in Git).
Driver (outside the repo) SHA-256 eba2c7b4eaaa46cfdbb44f3a5d84b6f8ece2d30ce5a24c798ee5582bcaaf39c9: runs suites
sequentially on a detached frozen checkout, records `dirty` before/after, exit, `grep -c ^PASS`, duration, log SHA-256.
**PASS lines ≠ assertion counts** (sub-suites print their own `checks=` totals). Shared VM: durations are not performance
data. The earlier Phase VIII runs exported `ROMLOGGER_EXPECT_DATA_MODEL=64` for x64 too, which adds one or two
"actual JVM data model 64" PASS lines per suite; Phase IX x64 runs do not set it (explains 70 vs 71, 126 vs 128, 256 vs 260).

## Reproductions (RED) before fixes

| Item | Head | Result | Log SHA-256 |
|---|---|---|---|
| R9-01 CLI not built | ab32e6b5 (dev tree = head) | clean `test-ingress-diagnostics.sh` exit0 but 4 CLI classes absent; `java … IngressDiagnosticsCli` → `ClassNotFoundException` | 2219fc6b075e0a22ed9007a0bff52409489ded21e3727450fe166a0740932325 |
| R9-03 vanished workdir | bc4226d | supervisor `[5 REVIEW_REQUIRED]` (uncoded) instead of a stable code | 7bb96c5f0b1924cd8288c52afb2d3a1dea2e29b88de3717444ac74d312c065d5 |
| R9-03 mechanism | — (owned probe) | 11:46:25 owned empty `work` under %TEMP% removed externally; non-empty sibling, marker and non-TEMP control kept | 5a383e81d7fb8c0b843b66b1197a6439129cd640c46db1d51bb66d3c24b57e52 |
| R9-04 RLDI1 TOCTOU | 6736229 (`--expect-red`) | 4/4 same-size/same-mtime and physical replacements → MATCHED | c8f7cfdbb084c6cbedc5fd83cb7b36a8e09fa356cbf3db96c6b9fba3084f3c6d |
| Review P1 A-B-A | bc91d17 | found by code reading (independent review); no hook allowed a deterministic RED on that code → covered by a GREEN regression only | — |

## IX-1 frozen `815dae7091901ba044935d5bbac23fed9a7f1043` (PR #97 head) — `phase9-accept-815dae7`, dirty=0

| Runtime / command | Exit | PASS lines | Log SHA-256 |
|---|---|---|---|
| x64 `test-phase9-cli.sh` | 0 | 39 | 3410374bf48e59e82d0b4acab020f32aa589c11dc7c019e6b88230c68e705ef6 |
| x64 `test-ingress-diagnostics.sh` | 0 | 76 | d1be36d64ae50143b201ba01259b95c0998c69711c2544902e5012683da744a8 |
| x64 `phase7-packaged.sh Phase9WorkdirTest` | 0 | 14 | 84f0abfb8276cb9f2a8e46d0899c22961b6a92a437ef9640f5167cb22ea4a2a7 |
| x64 `test-phase8-inventory.sh` | 0 | 70 | f425de994f702bd23be404d47975e5d49519f9310cff859e2162ff4db88ff2bc |
| x64 `test-phase8-readiness.sh` | 0 | 22 | cafec28af24cbadab4b67aa8f4846c50339632442d7b04fa60308dd0f6c58cae |
| x64 `test-phase3-deployment.sh` | 0 | 125 | 9ea681014958f0d24429f397c0b874ee0e03af3484a3e169bb31637c0b701bcf |
| x64 `test-phase4.sh` | 0 | 126 | 7342589586a1a3fb552b89b7d1994a9e7644c1c7e850d73b19a71f19dceee4e3 |
| x64 `test-phase7-review-closure.sh` | 0 | 26 | 980bc80d50eff712e342a53d13d9ce2ce7df12d603511c22bb17f5789186bd7a |
| x64 `test-phase7.sh` (owned PG/HTTP NOT_RUN) | 0 | 256 | aaa7022c5e175cad3cc2aa57992517bac3cb727577b0852b2c6d9c30e688126f |
| x64 `test-phase8-multisession.sh` (10 sessions, first attempt) | 0 | 229 | 4681a108ca50bcb91ef56ba4b902b5d3a07aa78e0200719ddd0fdf835b2c2f54 |
| x86 portion | **INTERRUPTED by me** at 12:29:58 during `test-phase9-cli.sh` (28 PASS lines, partial) | — | not used as evidence |

The x86 part for this head was stopped deliberately to free the shared VM: every IX-1 change is contained in the top
head, whose actual-x86 run is below. Dev-tree (not frozen) actual-x86 IX-1 runs at 815dae7 content: `test-phase9-cli.sh`
39 checks (6bb51bda…), `test-ingress-diagnostics.sh` exit0 (e1d4e061…), `Phase9WorkdirTest` 14 checks.

## Top frozen `987e5044924d6965cd1ec83da29ab2fe36dfad6a` (PR #100 head) — `phase9-accept-987e504`

(results appended below when the run completes)

## Not run / not proven

Linux (all Phase IX scripts; RLWR1 native is Windows-only by design) — NOT RUN this session. Owned PG16/FastAPI HTTP35 and
10k load — NOT RUN (no change to the HTTP/store path; last results at 2ccae84). Phase V/VI suites — NOT RUN (unchanged
paths). Symlink fixtures (no privilege), non-NTFS/mapped/subst volumes — NOT RUN. Windows ACL — UNKNOWN. GitHub Actions on
all phase9 branches — CI NOT RUN (runner not started; prior heads jobs `steps=0`). An earlier frozen run at bc91d17 was
stopped after 7 suites (all exit0) because review fixes followed; it is not counted.
