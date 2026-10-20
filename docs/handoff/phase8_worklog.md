# Phase VIII append-only worklog

Implementation agent: Claude Code (Opus 5.5), fresh session (no prior chat context), instruction
`RomLogger_ClaudeCode_PhaseVII_Closure_PhaseVIII_FreshSession_20261020.md`. Entries are appended only.

## 2026-10-20 ~15:50 JST — start checkpoint

- Re-fetched: Agent main `4f80c5e`, Server main `d8c7a2f`; PR #80–#91 open, stack ancestry verified with
  `git merge-base --is-ancestor` along #80→…→#91; #91 `phase7/adversarial` head `d93a975`. Server #62/#63 open;
  `phase7/agent-entry-progress` existed only as a local branch in `C:\Dev\RomLogger\phase7-server-docs` (unpushed).
- CI: runs 39380478331/…411/…393 at d93a975 — every job `steps=0` → CI NOT RUN.
- `67657d9..d93a975`: non-doc changes only `Phase7Fixture.java`, `Phase7E2ETest.java` (test fixture).
- Found the previous Phase VII session still running its acceptance drivers (67657d9 and d93a975, x86 stage) in
  `phase7-accept-*`; left untouched. That session later messaged: it owns #91 docs and the Server docs PR
  ([RomRaiderServer#64](https://github.com/zawa356/RomRaiderServer/pull/64), on #63); this session owns C2 + Phase VIII.

## VII-C2 / F2 / fileSha — `phase7/review-closure`

- `36bb721` RED test at d93a975 product code: 7 RED / 9 PASS (log SHA256 a10aa98a…; see closure evidence).
- `2ccae84` fix + F2 cause + fileSha bound; dev GREEN 16 + 10 checks.
- Process mistake (kept on record): a background Phase V/readiness regression was started in the implementation
  worktree and the sources were then edited while it compiled; that run was stopped and **discarded** (not evidence).
  All evidence comes from frozen checkouts (`phase7-accept-2ccae84`, `phase8-accept-8b8a0f0`) and private Linux dirs.
- F2 test first expected the coded reader fault to survive end to end; it does not (IngressDiagnostics absorbs it and
  the adapter throws free text without a cause). The expectation was corrected to the real behaviour and the limit
  documented — not hidden.

## Phase VIII-1 — `phase8/distribution-inventory` (`991e5e5`)

- RLDI1 read-only inventory + entry `--inventory`. Dev Windows x64: 68 checks exit0.
- Finding: the real entry closure JAR has no known legacy entry class but four secondary `main` classes
  (ArtifactProvenance, DeploymentBinding, DeploymentTransition, IngressDiagnostics) → MISMATCH; kept as D2 blocker.
- File symlink case NOT_RUN on Windows (no symlink privilege); junction case covers reparse points.

## Phase VIII-3 — `phase8/readiness-cli` (`0cbd18f`)

- RLIR1 JSON Schema + entry `--readiness`. Dev Windows x64: 17 checks exit0; actual entry JVM produced the identical
  readiness object.

## Phase VIII-2 — `phase8/multi-session` (`8b8a0f0`)

- RLMS1 + 10 sessions through actual entry JVMs. Dev Windows x64: 168 checks exit0 (first pass 471 s, restart pass
  412 s on a VM shared with the closure driver; not performance data).

## Phase VIII-4 — design only

- `docs/design/windows_product_reader_v1_design.md`: product reader contract (proposed RLWR1), failure taxonomy,
  acceptance plan, open owner decisions (FILE_SHARE_DELETE, JNA licence). Nothing wired.

## Frozen acceptance runs

- `2ccae84` (review closure): Windows `C:\Dev\RomLogger\phase7-accept-2ccae84`, Linux
  `/tmp/romlogger-p7closure-2ccae84-95idtj`. Results in `phase7_review_closure_evidence.md` (on `phase7/review-closure`).
- `8b8a0f0` (Phase VIII runtime/tests head): Windows `C:\Dev\RomLogger\phase8-accept-8b8a0f0`, Linux
  `/tmp/romlogger-phase8-8b8a0f0-Wx3b98`. Results appended below when the drivers exit.

## 2026-10-20 18:12–20:53 JST — frozen Phase VIII results at `8b8a0f02ba936eb523676150fbc949fc3eee30ba`

Windows: detached `C:\Dev\RomLogger\phase8-accept-8b8a0f0`, dependencies verified, driver SHA256
f347ab21901f4e994b997a5afb32e2fb5140e3c4cadaa7abc69e69e8fc368398, dirty=0 before/after. No owned HTTP step in this
batch (Phase VII HTTP35 + 10k load were run at 2ccae84; the Phase VIII commits do not touch the HTTP/store path).
Shared VM: durations are not performance data.

| Runtime / command | Exit | checks | Log SHA256 |
|---|---|---|---|
| JDK17 x64 `test-phase8-inventory.sh` | 0 | 68 (file symlink NOT_RUN: no privilege; POSIX case NOT_RUN) | 402dd92508738419a67a51292723643a5b11095809aaffc8c48d90f1039f99b0 |
| JDK17 x64 `test-phase8-readiness.sh` | 0 | 17 | cafec28af24cbadab4b67aa8f4846c50339632442d7b04fa60308dd0f6c58cae |
| JDK17 x64 `test-phase8-multisession.sh` (10 sessions) | 0 | 168 | c32faf773c713822aabc1f8db323d589b6e7084bf1df40a36f5b4346e48a50e1 |
| JDK17 x64 `test-phase7-review-closure.sh` | 0 | 16 + 10 | 980bc80d50eff712e342a53d13d9ce2ce7df12d603511c22bb17f5789186bd7a |
| JDK17 x64 `test-phase7.sh` (entry/readiness/E2E/adversarial) | 0 | 108 / 65 / 24 / 41 | ad23108c547df2ea37df16aed0951e02410778e0a610198bd9dfab0d61ecce3d |
| actual Zulu8 x86 `test-phase8-inventory.sh --run-only` | 0 | 69 | e0fbae0f53698501a28285c7290b238d2cf3fce7b81ee233f590e895e23e8fc4 |
| actual Zulu8 x86 `test-phase8-readiness.sh --run-only` | 0 | 18 | 3a6a0b952739e4b518028c628f0d9f3c1c204a341b369d7b8e13d74e95ccdc9b |
| actual Zulu8 x86 `test-phase8-multisession.sh --run-only` | 0 | 169 | 38fa95d82a7e928161694bd4ecfbbc8408b5d0720c4763c8c95d734d430fcea2 |
| actual Zulu8 x86 `test-phase7-review-closure.sh --run-only` | 0 | 17 + 11 | bdb4e7a320dd9b11910219177af1976ff659a999c2dc3d5f8bf8531bd76a9a75 |
| actual Zulu8 x86 `test-phase7.sh --run-only` | 0 | 109 / 66 / 25 / 42 | 62c5dcf2666df7222c8ae92b67496c83eac783bdd9d4bd51196e4af63ebdd420 |

Linux: `/tmp/romlogger-phase8-8b8a0f0-Wx3b98` (`umask 077`), archive SHA256
8903b13346d411e4bfbe8754adf8bb76b674831373e8c0e6922b672d2783f1a9 verified, driver SHA256
01e3f67147aa02a139ecf36bb37aaac7b2f69d0795944bb273de39d6a51383b2, OpenJDK 17.0.20.1 x86_64 model 64.

| Command | Exit | checks | Remote log SHA256 |
|---|---|---|---|
| `test-phase8-inventory.sh` | 0 | 75 (incl. file symlink and POSIX group-writable) | f78d232ebfa22f03bf907159d0cedd537755588dd42119665ab43897ad619293 |
| `test-phase8-readiness.sh` | 0 | 18 | 16589bf67940418f208262ac9c508b2018ef060b02ca3fa772cbce78ae287bb0 |
| `test-phase8-multisession.sh` | 0 | 169 | c30fa24d459a54206fa3fceff69c500a50e73965ae91fc81ea243b60d18e431c |
| `test-phase7-review-closure.sh` | 0 | 17 + 11 | 0638b19c6b197ad0d062674dffb403802c6cdcb5cd23a58e4208e000e6a88446 |
| `test-phase7.sh` | 0 | 109 / 66 / 25 / 42 (HTTP NOT_RUN) | e3919aa9a7e5b6fec880a059de3bdd69a9b4c0d7d55aaf8e4247495b6cc087c3 |

Not rerun at 8b8a0f0 (unchanged code paths, last results at 2ccae84): Phase VI/V/IV, owned HTTP35, 10k load,
historical F1 RED. CI at all Phase VIII branches: jobs `steps=0` → CI NOT RUN (runs 39390678902…39390701085).

## Phase VIII-1b — `phase8/entry-closure-cli-split` (`95a4a30`, draft PR #96)

- The four secondary `main` methods moved to `ArtifactProvenanceCli`, `DeploymentBindingCli`, `DeploymentTransitionCli`
  and `IngressDiagnosticsCli` (not referenced by the entry). Arguments/output/exit codes unchanged; Java APIs unchanged.
- `test-phase8-inventory.sh` packaged-closure expectation flipped: the real closure (149 classes) is now RLDI1
  `MATCHED`, `unapproved_mains=[]`, `findings={}`, still `state=BLOCKED`, `launch_authorized=false`,
  `global_old_binary_exclusion=NOT_PROVEN`. Legacy-class checks kept. This is an inventory of one directory only:
  not launch, production, ACK or global old-binary exclusion.

## 2026-10-20 21:20 – 2026-10-21 01:25 JST — frozen Phase VIII-1b results at `95a4a3054f6815adcbaca10e3d0c3e87ec5cad23`

Windows dev VM only (synthetic): detached `C:\Dev\RomLogger\phase8-accept-95a4a30`, `lib/test-dependencies.sha256`
verified, JNA 5.14.0 SHA256 34ed1e1f…68c6, driver SHA256 b1a40003cf8b978b2fbe98402db01ab1599f2c5049e671a7755200d6e2899205,
dirty=0 before/after. JDK17 = Zulu 17.0.20.1 x64 (model 64, compiles `--release 8`); x86 = actual Zulu 8u504 win32
Client VM (model 32, `ROMLOGGER_EXPECT_DATA_MODEL=32 … --run-only`). Logs in `C:\Dev\RomLogger\logs\phase8-1b-95a4a30`.
Shared VM: durations are not performance data.

| Runtime / command | Exit | PASS lines | Log SHA256 |
|---|---|---|---|
| JDK17 x64 `test-phase8-inventory.sh` | 0 | 71 (packaged closure MATCHED) | 89c10ef0bf6160ec796eedff97e0798e9ebbc58a31e48199d535d80d9e8e769e |
| JDK17 x64 `test-phase3-deployment.sh` (DeploymentBindingCli child) | 0 | 125 | 9ea681014958f0d24429f397c0b874ee0e03af3484a3e169bb31637c0b701bcf |
| JDK17 x64 `test-phase4.sh` | 0 | 128 | f8f3694ea1ae01c4c32bcbf6b20b57f6d1f0f9fa6e5dfacec96b952eea4c25a2 |
| JDK17 x64 `test-ingress-diagnostics.sh` | 0 | 78 | a18521846c13ea6604a8ce8084619d6844d873f4eaba8fe0a848f77dae811e48 |
| JDK17 x64 `test-phase8-readiness.sh` | 0 | 23 | c83f15d7bd50a436a9a9d5e9aa7395d0da45a2f48b869592397780ec1848b565 |
| JDK17 x64 `test-phase7-review-closure.sh` | 0 | 28 | c4a9f26f1238390660cf6b3c2dab35a966d3d650c9abb345f6dc4bb771736ad0 |
| JDK17 x64 `test-phase7.sh` (entry 109 / readiness 66 / E2E 25 / adversarial 42; owned PG/HTTP NOT_RUN) | 0 | 260 | e80478dca68399273dfe8415fd5b6892af8fbb9a81c865a1ffb6822d6cf6da78 |
| JDK17 x64 `test-phase8-multisession.sh` (1st attempt) | **1** | 93 | c5ae4f1e5bf6df5fb6c58476fbb2a9ac5754a98d7b766d46210ee1ed5111b61e |
| JDK17 x64 `test-phase8-multisession.sh` (rerun, same frozen checkout) | 0 | 230 (checks=169) | e069bc81155d359e20da58abd67c8a0c9bc82917a1f41c782f67bf6db53dc795 |
| actual Zulu8 x86 `test-phase8-inventory.sh --run-only` | 0 | 71 (packaged closure MATCHED) | 3082ba8d5389a03084b6bb053ab01691d31e112cb06cf04eed629c0efc2339c4 |
| actual Zulu8 x86 `test-phase3-deployment.sh --run-only` | 0 | 125 | aecc0790bf7202af90470a2900d6b37c18236c23b0c9c8a1b543ae0d42f95f9a |
| actual Zulu8 x86 `test-phase4.sh --run-only` | 0 | 128 | 61bb95f948405acf25c31ab4ecc5c2b9ae3c9993e3439899045d95bf79200d2f |
| actual Zulu8 x86 `test-ingress-diagnostics.sh --run-only` | 0 | 78 | ad6c9c77db7bec86eccc7f6f725fce60558a2c3b607a75175d72801f303cf525 |
| actual Zulu8 x86 `test-phase8-readiness.sh --run-only` | 0 | 23 | 3a6a0b952739e4b518028c628f0d9f3c1c204a341b369d7b8e13d74e95ccdc9b |
| actual Zulu8 x86 `test-phase7-review-closure.sh --run-only` | 0 | 28 | bdb4e7a320dd9b11910219177af1976ff659a999c2dc3d5f8bf8531bd76a9a75 |
| actual Zulu8 x86 `test-phase7.sh --run-only` (owned PG/HTTP NOT_RUN) | 0 | 260 | 389fac5b91f9af084f73dfee64e45893eb3acc0bb8d36b1ee7283afc33bd092f |
| actual Zulu8 x86 `test-phase8-multisession.sh --run-only` | 0 | 230 | 9175c2c762116fdcb12f9caf50df9cfd7ac48f252ecbcf95d9166475d06c8d50 |

PASS lines = `grep -c ^PASS` over the whole log (several scripts print sub-suite `checks=` totals that differ).
The x64 multisession first attempt failed after a passing 10-session first pass (925 s): the restart pass hit
`CreateProcess error=267` (directory invalid) because its `%TEMP%\romlogger-phase7-*\work` directory no longer existed.
Not diagnosed further. It is recorded as an environment failure, not a pass. The rerun on the same frozen head passed.
Not rerun: Linux, Phase V/VI scripts (no use of the moved mains), owned HTTP35/10k load, historical F1 RED.
CI on `phase8/entry-closure-cli-split` (runs 39423087794, 39423087902, 39423087820): jobs `steps=0` → CI NOT RUN.
