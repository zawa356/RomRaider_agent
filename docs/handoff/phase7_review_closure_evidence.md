# Phase VII review closure evidence (VII-C2 / F2 / fileSha) — exact heads

Logs are local/remote artifacts (ignored, not committed); no payloads, credentials or test databases. Each row names the
code head it ran on. Results of one head are never relabelled as another head's. "checks" is the suite's own final
counter; "PASS lines" can be higher. Final head of this branch's runtime/tests: **`2ccae84e9012f33b95b7ae8d6c6863f6b6684110`**
(the docs commit on top changes documentation only).

Phase VII final-head evidence (d93a975 Windows x64 / actual Zulu8 x86 / Linux, owned HTTP35 + 10k load on x64, the
67657d9 load FAIL) is recorded by the Phase VII session in
[phase7_validation_evidence.md on phase7/adversarial](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_validation_evidence.md)
and is not repeated here.

## RED (product code d93a975, test commit 36bb721)

| Platform / command | Exit | Result | Log SHA256 |
|---|---|---|---|
| Win JDK17.0.20.1 x64 `bash scripts/test-phase7-review-c2.sh` in `C:\Dev\RomLogger\phase7-claude` (worktree at d93a975 + test only) | **1** | 7 RED / 9 PASS: H1 claim+cursor, H2 tampered cursor, H3 orphan cursor, H4 claim only → all `PASS NO_LEGACY_V1_RECORDS`; H1–H3 `content_axes_complete=true`; overall BLOCKED | a10aa98ad6d209cbb6226e39914518c14b808ae847e328bd4897b5dafa6da82d |

A first attempt failed to compile (missing import) — harness error, not a result.

## Final head 2ccae84 — Windows (frozen `C:\Dev\RomLogger\phase7-accept-2ccae84`)

Detached worktree, dependencies verified against `lib/test-dependencies.sha256` and pinned JNA 34ed1e1f…; driver
`build/driver.sh` SHA256 decbff92f21451e80acffe22de106535bd7fb2a4ca6400834aa2de5557b58a15; dirty=0 before and after.
HTTP step: `ROMLOGGER_OWNED_PG_HTTP_ENABLE=1`, invocation-owned PG16 (`C:\Dev\tools\pgsql16\bin`), Server checkout
`C:\Dev\RomLogger\phase7-server-d8c7a2f`; the shared 55432 cluster was not touched. The VM was shared with other test
JVMs during this run: durations are not performance data.

| Runtime / command | Exit | checks | Log SHA256 |
|---|---|---|---|
| JDK17 x64 `test-phase7-review-closure.sh` | 0 | C2 16 + closure 10 | 980bc80d50eff712e342a53d13d9ce2ce7df12d603511c22bb17f5789186bd7a |
| JDK17 x64 `test-phase7-entry.sh` | 0 | 108 | 67b768230fd7a0ebbb624b8871fa68380b854086835547928ed40cd0ddb50e27 |
| JDK17 x64 `test-phase7-readiness.sh` | 0 | 65 | 64029ac761e455319fcb13d2181dc34f5b3da521668cbe24f002cf3cb4d4cf8b |
| JDK17 x64 `test-phase7-e2e.sh` + owned PG16/FastAPI HTTP + `ROMLOGGER_PHASE7_LOAD_OBSERVATIONS=10000` | 0 | 26; 10003 records via the entry, rerun 0 duplicates; HTTP 35 checks incl. PG immediate-stop 503/no ACK/restart; candidate bytes unchanged | 42d88b5dd91ebd0146019d676c644c3755bf90073c44e573e584ba9f29e23c9a |
| JDK17 x64 `test-phase7-adversarial.sh` | 0 | 41 | a9195a02665faa97a8d1ad3f034393e61ea94c5e31498d11af7cb860271581bd |
| JDK17 x64 `test-phase7-review-red.sh` (historical F1 RED vs old CLI) | **1 (expected)** | 4 PASS / 4 RED — old CLI still callable | 0374ca8fa2f828aa31f26a13e47db189fb4069dc506f7d295cfb46007a383a84 |
| JDK17 x64 `test-phase6.sh` | 0 | writer/drive/drive-entry/preflight/transition/staging-parent (328 PASS lines) | 4b901e19a27607b8f066ec1e797f76181ec96d67de001f0f3433e124ebe23487 |
| JDK17 x64 `test-phase5.sh` | 0 | 62 | 6a9580af667cbabc93e6af01343ba84f9f7e5b694eabdc08a6c449b356acb3d1 |
| JDK17 x64 `test-phase4.sh` | 0 | 126 PASS lines | 7342589586a1a3fb552b89b7d1994a9e7644c1c7e850d73b19a71f19dceee4e3 |
| actual Zulu8 x86 (`ROMLOGGER_EXPECT_DATA_MODEL=32`) `test-phase7-review-closure.sh --run-only` | 0 | C2 17 + closure 11 (incl. data-model check) | bdb4e7a320dd9b11910219177af1976ff659a999c2dc3d5f8bf8531bd76a9a75 |
| actual Zulu8 x86 `test-phase7.sh --run-only` (entry/readiness/E2E/adversarial) | 0 | 109 / 66 / 25 / 42 (owned HTTP NOT_RUN on x86) | 8cedb930a844f4a17b351e046f9ef410c490416f8f8bd4570cb9bd880b327f5c |
| actual Zulu8 x86 `test-phase5.sh --run-only` | 0 | 62 | 2e8510aa9191333f5a47bd2019aa4b3dfc39a7eb861e107005084971ebcf204d |

Driver 16:25–18:11 JST, `dirty=0` at end. Phase VI/IV on x86 were not rerun at 2ccae84 (Phase VI/IV code is
unchanged by this branch; their last actual-x86 results are the Phase VII session's at 67657d9) → NOT_RUN here.

## Final head 2ccae84 — Linux

Pinned-host fresh private directory `/tmp/romlogger-p7closure-2ccae84-95idtj` (`mktemp -d`, `umask 077`). Source
`git archive` SHA256 81c7ff2748753664549b6a809609057b57f24d5b729c692ccaaac810433c7350 verified before extraction;
`lib/test-dependencies.sha256` verified; driver SHA256 6c4e8c0efc5f2fe431eafff937518ecff22acf584f22438b01875aa79fdaf2da.
OpenJDK 17.0.20.1 x86_64, model 64. No service/container commands.

| Command | Exit | checks | Remote log SHA256 |
|---|---|---|---|
| `test-phase7-review-closure.sh` | 0 | C2 17 + closure 11 | 0638b19c6b197ad0d062674dffb403802c6cdcb5cd23a58e4208e000e6a88446 |
| `test-phase7-entry.sh` | 0 | 109 | 78c6878b5b374c37e1ce2cafb8eedcc84e59fd65fc3c84b17f55015fcea69753 |
| `test-phase7-readiness.sh` | 0 | 66 | f8fe6a8933b9f4dcd6efeae76e7ba4e1331aeef72a04a49a05155f03a3651fb4 |
| `test-phase7-e2e.sh` | 0 | 25; owned HTTP step NOT_RUN on Linux | 8005eaa600d93f720854007df6d788ab928f1ce87373947a925cae2fc4ecc7d9 |
| `test-phase7-adversarial.sh` | 0 | 42 | 0053e91ba456512d9b10d0d2b0d826f5e088d8eb9ad7e4f450f55d09f45707f3 |
| `test-phase7-review-red.sh` | 1 (expected) | 4 PASS / 4 RED | fee6f18016e56bb23501c46f39f9f888ac351893364b6da5c85e3f88246469cd |
| `test-phase6.sh` | 0 | writer 154, drive 33, preflight 41, transition 59, staging-parent 10 | d0ed493734633ba35bc32bf96af99e342ae53844e820cfb7876dd66e885a4224 |
| `test-phase5.sh` | 0 | 62 | 36af6fcdb1938b671d96d05750e9461f573ec9e1819067d246604f4e3fef02da |
| `test-phase4.sh` | 0 | complete | 520d8d20af5e59a0b1f09b6d77d78d04e1d29c6065f7e7bb1e715806adb6a13a |

## Superseded / discarded

- A background Phase V + readiness regression in the implementation worktree was stopped because sources were edited
  while it compiled. Discarded; not evidence.
- Development (unfrozen) runs of the closure suites are superseded by the rows above.

## CI and not run

GitHub Actions at the closure branch: see PR #92; every inspected Agent run had jobs with `steps=0` → CI NOT RUN.
No dispatch, billing change or merge. Server runtime unchanged (d8c7a2f); Server pytest/migrations not rerun in this
closure (no Server change). Root `test.sh` NOT RUN (needs RomRaider.jar). Production, Sender, formal ACK, ECU/X270:
not done by design.
