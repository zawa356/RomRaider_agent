# Phase VII validation evidence

Logs are ignored local/remote artifacts; no payloads, credentials or test databases are committed. Each row names the exact code head it ran on. Results from one head are never relabelled as another head's. "checks" is the suite's own final counter; "PASS lines" can be higher (helper/child lines). Failed or superseded runs are kept as such.

## Frozen-source locations (detached, unedited during execution)

| Code head | Checkout | Scope |
|---|---|---|
| `297126cf2b44029f11c26bc757473d7c9018e8d1` (= runtime/tests 53f18a2) | `C:\Dev\RomLogger\phase7-claude` before Phase VII edits | baseline preflight 41, F1 RED |
| `2be491d35642c4b2d5d1e62e8376b486189a2071` | `C:\Dev\RomLogger\phase7-accept-2be491d` | VII-1 entry |
| `0cd4c697c063f155bc8152c835086b86ecb38bf0` | `C:\Dev\RomLogger\phase7-accept-0cd4c69` | VII-2 readiness |
| `67657d98985dbe66f881fc4d683b0bce1a5a75b1` | `C:\Dev\RomLogger\phase7-accept-67657d9`; Linux `/tmp/romlogger-phase7-67657d9-14Wof6` | **final runtime/tests** (all Phase VII suites + Phase VI/V/IV regression) |

Later commits on the top branch change only handoff documentation unless stated in the worklog.

## Baseline and RED (Phase VI runtime)

| Head / platform / command | Exit / result | Log | SHA256 |
|---|---|---|---|
| 297126c Windows JDK17.0.20.1 x64 `bash scripts/test-phase6-preflight.sh` | 0; 41 checks | `build/baseline-preflight-x64.log` | (local, not retained as evidence beyond exit/count) |
| 297126c + test-only F1 RED `bash scripts/test-phase7-review-red.sh` | **1**; 4 PASS / 4 RED: approved CLI stores while actual child holds Lane (PID16828) / Publication (PID11848) | `build/phase7-entry-bypass-red.log` | cad47faafca458d455cd3f9a971bfad318039c8e3ae962903d54a44cdd28db31 |

Two earlier RED harness attempts are FAIL evidence of the harness only: (1) Phase V fixture's v1 already claims the session ("legacy lane already owns logical session"); (2) payload not `synthetic-*`. Neither is a product result.

## Intermediate frozen heads

| Head / platform / command | Exit | checks | Log SHA256 |
|---|---|---|---|
| 2be491d Win JDK17 x64 `test-phase7-entry.sh` | 0 | 108 | f03d20cc60d8c8e8e14a3a090c1f2971e9aa335d47e894ace8dcb7e6f5ea416f |
| 2be491d actual Zulu 1.8.0_504 x86 (model 32 asserted) `--run-only` | 0 | 109 | 1cd1460178bf96f9a4d836dcbb7f8b95f0c1d192b774edf3c6b581b2f0d52628 |
| 2be491d Win x64 / x86 `test-phase6-preflight.sh` | 0 / 0 | 41 / 41 | 19c359cea81e47f143fa0d6ffa15289398b52e2c30614b7cc81cb8aef1cc7bf4 / 224d43a8b9092abc7fc98ebee12aeefe95c47687da717d5533312036616392a9 |
| 2be491d Win x64 `test-phase7-review-red.sh` (old CLI unchanged) | 1 (expected) | 4/4 RED | 44569c3f7306ab41b963e66f3dfca2e550225306e64ce23e55697971fb215203 |
| 0cd4c69 Win x64 / x86 `test-phase7-readiness.sh` | 0 / 0 | 65 / 66 | d21e2bf5ad79d58710e7e3c7cd2ef3e5d5e8670748e588ef300044e27b6b0e26 / 888a9da991cec3a11c4a54fb432b91f144b50f06fb44e4fd015926c6c69e4c67 |
| 0cd4c69 Win x64 / x86 `test-phase5.sh` | 0 / 0 | 62 / 62 | db42ecead73daf5da56f6710b620bc0975ce050c2e4580a5ddf60a47ccdd8295 / 76bf71164c70a4f7073ae68ae7476125d499631b2631dd8ecfd1c1443e351cf6 |

Development (unfrozen) runs at 76288e8 — E2E x64 24 checks and owned PG16 + FastAPI 35 checks on the entry candidate — are superseded by the final-head rows and are not used as evidence.

## Final head 67657d9 — Linux (complete)

Pinned-host fresh private directory `/tmp/romlogger-phase7-67657d9-14Wof6` (created with `mktemp -d` under `umask 077`). Source `git archive` SHA256 fcb884b1e18254fd02df9277f08b32d66205ca64d509c4edab640701a237b152 verified before extraction; `lib/test-dependencies.sha256` verified; driver script SHA256 c505d6730a9a43f4f871754f798aa8451124a93114885eb5d4e19554e4797528. OpenJDK 17.0.20.1 Linux x86_64, `ROMLOGGER_EXPECT_DATA_MODEL=64`, `umask=0077`. No service/container commands.

| Command | Exit | checks / notes | Remote log SHA256 |
|---|---|---|---|
| `test-phase7-entry.sh` | 0 | 109 | a66b1ea8bd73d4cddb363e297e619c0a21e00279ffdb5a08605239cfb58ea447 |
| `test-phase7-readiness.sh` | 0 | 66 | 006892940b27b79215c10d5ecc3b77d85032fc6ce5be77611cdb5bed77df421e |
| `test-phase7-e2e.sh` | 0 | 25; forced kill landed pre-commit (0 records), retry converged; owned HTTP step NOT_RUN on Linux | fba182cbb1beeb6aa88465cb485863e998036f3f3cea92f1b39163afe54f192d |
| `test-phase7-adversarial.sh` | 0 | 42; sidecar-free IMMEDIATE/EXCLUSIVE writer limit reproduced (stores, WRITER_UNPROVEN kept); symlink alias used for the junction case | d75d01c5b1ac32dbd924f25eb7f43deba56ae141cba8c9eb69bdccaa383d59f2 |
| `test-phase7-review-red.sh` | 1 (expected) | 4 PASS / 4 RED, old CLI unchanged | dc783eed68e210b03f7f967e1a32c2993254bc87189e9c2afc87b6babdc86be8 |
| `test-phase6.sh` | 0 | writer/drive/drive-entry/preflight/transition/staging-parent; Windows-native drive cases NOT RUN on Linux | b6a3e92e47ebcaebdf292e710ff977565797ddcc6ec0e388132119a3a1bc802b |
| `test-phase5.sh` | 0 | 62 | 11ca2628fdae9bf287cd26bc7712b9f7f4f6239a8bd1bb00bc928e63008b4ae6 |
| `test-phase4.sh` | 0 | complete focused regression | 520d8d20af5e59a0b1f09b6d77d78d04e1d29c6065f7e7bb1e715806adb6a13a |

## Final head 67657d9 — Windows JDK17 x64 and actual Zulu8 x86

_Rows appended from `C:\Dev\RomLogger\phase7-accept-67657d9\build\driver.log` after the driver exits._

## CI

Latest runs at PR86 297126c (39347006053, 39347006139, 39347006154): every job `steps=0`. CI NOT RUN (runner not started). Phase VII branch runs are checked again at publication. No dispatch, billing change or merge.

## Not run / blocked (explicit)

Real ECU/X270/OpenPort/RomRaider, product Worker/Sender/formal ACK, production DB/migration, existing service/container changes: not done by design. Real SMB, subst/drive-letter alias creation, true Windows symlinks, physical power loss, real ENOSPC: NOT RUN. Windows running-process provenance and directory durability: UNKNOWN. Noncooperative writer absence: WRITER_UNPROVEN (reproduced again in VII-4). Server pytest57/migrations: not rerun (Server runtime unchanged at d8c7a2f; last result is Phase VI's). Root `test.sh`: NOT RUN (needs RomRaider.jar).
