# Phase VII validation evidence

Logs are ignored local/remote artifacts; no payloads, credentials or test databases are committed. Each row names the exact code head it ran on. Results from one head are never relabelled as another head's. "checks" is the suite's own final counter; "PASS lines" can be higher (helper/child lines). Failed or superseded runs are kept as such.

## Frozen-source locations (detached, unedited during execution)

| Code head | Checkout | Scope |
|---|---|---|
| `297126cf2b44029f11c26bc757473d7c9018e8d1` (= runtime/tests 53f18a2) | `C:\Dev\RomLogger\phase7-claude` before Phase VII edits | baseline preflight 41, F1 RED |
| `2be491d35642c4b2d5d1e62e8376b486189a2071` | `C:\Dev\RomLogger\phase7-accept-2be491d` | VII-1 entry |
| `0cd4c697c063f155bc8152c835086b86ecb38bf0` | `C:\Dev\RomLogger\phase7-accept-0cd4c69` | VII-2 readiness |
| `67657d98985dbe66f881fc4d683b0bce1a5a75b1` | `C:\Dev\RomLogger\phase7-accept-67657d9`; Linux `/tmp/romlogger-phase7-67657d9-14Wof6` | **final product runtime**; all Phase VII suites + Phase VI/V/IV regression |
| `d93a97546aaf774bd821e3e44cf9c46c1e101cc7` | `C:\Dev\RomLogger\phase7-accept-d93a975`; Linux `/tmp/romlogger-phase7-d93a975-jbGBvX` | **final tests** (test-only fixture fix after 67657d9; `src/main` and scripts identical): Phase VII suites rerun on all platforms |

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

## Final product head 67657d9 — Windows JDK17 x64 and actual Zulu8 x86

Detached `../phase7-accept-67657d9`, clean after execution. Server test checkout: detached `C:\Dev\RomLogger\phase7-server-d8c7a2f` at Server main d8c7a2f.

| Command | Exit | checks / notes | Log SHA256 |
|---|---|---|---|
| x64 `test-phase7-entry.sh` | 0 | 108 | c71494ea15d7e5d0311b22f9f2e220562c6ce1577c8ff81a0bbb0718d92d93fa |
| x64 `test-phase7-readiness.sh` | 0 | 65 | 93c71526215555bfff588d53c8d0104d6dcc6a47b319493d77041b1939aac5a8 |
| x64 `ROMLOGGER_OWNED_PG_HTTP_ENABLE=1 ROMLOGGER_PHASE7_LOAD_OBSERVATIONS=10000 test-phase7-e2e.sh` | **1 (FAIL)** | core E2E passed; the 10k step was refused by the RLC2 adapter because the **test fixture** broke sample-sequence continuity; `set -e` then skipped HTTP → HTTP NOT RUN at this head. Kept as FAIL; corrected at d93a975 | 3b756fdf903f185eec96e229ffa4491364a2992d921b41b448b26ded1e04b3b5 |
| x64 `test-phase7-adversarial.sh` | 0 | 41 | 73d579abc0d172cad2de521b231b52faa74bb0776b39f17bae61d50aeda089c6 |
| x64 `test-phase7-review-red.sh` | 1 (expected) | 4/4 RED vs unchanged old CLI | d244167046a24b4f7c1ddbf428d7e1c6da71b499d72fe3629a912b56b2ead73d |
| x64 `test-phase6.sh` | 0 | writer/drive/drive-entry/preflight/transition/staging-parent incl. native Windows drive and actual junction | 4529f5a255a96cfcafbfe2fbae2525863ccd439b08283addde19825b9cadc51f |
| x64 `test-phase5.sh` | 0 | 62 | 56eafb96e343dbcef70a61b8706acde8c838d628514552852558c7735e4d04ce |
| x64 `test-phase4.sh` | 0 | focused regression | 7342589586a1a3fb552b89b7d1994a9e7644c1c7e850d73b19a71f19dceee4e3 |
| actual Zulu 1.8.0_504 x86 `test-phase7.sh --run-only` (model 32) | 0 | entry/readiness/E2E/adversarial | 31b5e8a0a639c58aaff720e7ecb6299e7bc5554316b14603b08dd99f53468691 |

| actual x86 `test-phase6.sh --run-only` | 0 | same Phase VI suites incl. native drive and actual junction | 4df4cfa201168d0e65c2a6184e389b1323bd02e1362ff3e1c97d1416852c01da |
| actual x86 `test-phase5.sh --run-only` | 0 | 62 | ad068d909379f50e2647b6af310c34688cfe8292cf92e7003eae83e2aa81a7e1 |
| actual x86 `test-phase4.sh --run-only` | 0 | focused regression | 61bb95f948405acf25c31ab4ecc5c2b9ae3c9993e3439899045d95bf79200d2f |

Driver 14:50–16:26 JST, clean after execution (concurrent with the d93a975 driver on the same VM, so durations are not performance data).

## Final test head d93a975 — Windows JDK17 x64 and actual Zulu8 x86 (complete)

Detached `../phase7-accept-d93a975`, clean after execution; driver 15:27–16:08 JST.

| Command | Exit | checks / notes | Log SHA256 |
|---|---|---|---|
| x64 `test-phase7-entry.sh` | 0 | 108 | 74ffbb6bb7e17e1472de181d3b64f76df1280dff1695ba65cf57841fb46d214e |
| x64 `test-phase7-readiness.sh` | 0 | 65 | 9ec8b626deb0e1b75734d90446c410871f3dda2fce68bdde2371e338fdb7c9fe |
| x64 E2E with owned HTTP + 10000 observations | 0 | 26 E2E checks: store 3/4, restart 0, rotation refused → RLDT1 generation 1 → +1/1, kill (pre-commit, 0 records) + retry, 10003 records (50.4 s, one observation) and rerun 0. **Actual FastAPI/Uvicorn + invocation-owned PG16: 35 checks** (lost response/retry, 409/422/503, owned PG immediate-stop/restart, exact persisted JSON). E2E candidate bytes unchanged (18b733e88bafcfa4adf5801597ee17dae0db60acfd42b264e270f3461b5c9b0e). Shared 55432 never stopped | f437837d88dddb58f5c48160ce9a36cc8c52c0df7a2258855e6ecdb8ed57b9da |
| x64 `test-phase7-adversarial.sh` | 0 | 41 | 56c9fcec4b649c882a9f7e516cedab1f4838bc1e27aaeea095ba9dfd1388957f |
| actual Zulu 1.8.0_504 x86 `test-phase7.sh --run-only` | 0 | entry 109, readiness 66, E2E 25 (HTTP NOT_RUN on x86 by design), adversarial 42 | 92fc826ad5c05335915237f451bfadb6a6fcb2f1b9f0b04352ad2ca156ee5c1e |

## Final test head d93a975 — Linux (complete)

Fresh `/tmp/romlogger-phase7-d93a975-jbGBvX` (`umask 077`); archive SHA256 c7b69f1007e919148d1f406b988c26ac5fb0e4a36035b31f8e9624479220e639 and dependencies verified; driver SHA256 90ac23927b0e60afbfe28270f70396f373e23eb5aea6caa2e97b3d8e1ee19eba. OpenJDK 17.0.20.1 x86_64, model 64.

| Command | Exit | checks | Remote log SHA256 |
|---|---|---|---|
| `test-phase7-entry.sh` | 0 | 109 | 5f078b0674f47c8f74d0b04b22109c367bc2626a0508770ae17ad13fa4d76c8b |
| `test-phase7-readiness.sh` | 0 | 66 | dfeeb6f534035e449ac2e0403241cf7b38d48d5405d733bed145d3ee64b6fe5b |
| `test-phase7-e2e.sh` | 0 | 25 (kill pre-commit, retry converged; HTTP NOT_RUN) | 18722ca1ca6dad3f5022609b3921c8c0971955a90e7c34038052113db9a19779 |
| `test-phase7-adversarial.sh` | 0 | 42 | 6e901fa6648b1242703d035f7b0376038167ddf110ca2794bc386bbc5dd8111a |

Phase VI/V/IV Linux regression is the 67657d9 row set above (product code identical). On every platform the forced kill landed before commit (0 records); the post-commit kill window was not hit and is not claimed.

## CI

Latest runs at PR86 297126c (39347006053, 39347006139, 39347006154): every job `steps=0`. CI NOT RUN (runner not started). At publication, Phase VII heads d93a975 (39380478331/39380478411/39380478393) and e4dc146 (39384938945/39384939091/39384939225) also failed with jobs steps=0: CI NOT RUN. No dispatch, billing change or merge.

## Open finding after the final head

A separate Claude session ("review closure", branch `phase7/review-closure`, commit 36bb721 on this machine) reproduced **VII-C2** at d93a975: when the legacy v1 has claims/cursors but zero `v1_records`, RLIR1 reports `cursor_provenance PASS NO_LEGACY_V1_RECORDS`, and for tampered/orphan cursors even `content_axes_complete=true` (overall state stays BLOCKED; no launch/production/ACK). This is a real defect in the Phase VII readiness code. Its RED, fix and evidence belong to draft [#92](https://github.com/zawa356/RomRaider_agent/pull/92) (head 2ccae84) and are not part of the results above; this session has not re-verified them.

## Not run / blocked (explicit)

Real ECU/X270/OpenPort/RomRaider, product Worker/Sender/formal ACK, production DB/migration, existing service/container changes: not done by design. Real SMB, subst/drive-letter alias creation, true Windows symlinks, physical power loss, real ENOSPC: NOT RUN. Windows running-process provenance and directory durability: UNKNOWN. Noncooperative writer absence: WRITER_UNPROVEN (reproduced again in VII-4). Server pytest57/migrations: not rerun (Server runtime unchanged at d8c7a2f; last result is Phase VI's). Root `test.sh`: NOT RUN (needs RomRaider.jar).
