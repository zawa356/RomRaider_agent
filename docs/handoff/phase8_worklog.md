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
