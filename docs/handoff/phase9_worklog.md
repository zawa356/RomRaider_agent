# Phase IX worklog (append-only) — Claude Code, 2026-10-21 JST

Rules: append only; never rewrite earlier entries. Failures stay recorded. Synthetic-only.

## 2026-10-21 IX-0 checkpoint — what was actually read

- Instructions: owner-supplied `RomLogger_ClaudeCode_PhaseIX_5h_Autonomous_20261021.md` (not in Git; ChatGPT Library not
  accessible from this session → GitHub is the source of truth).
- GitHub re-fetched at session start (gh as zawa356):
  - Agent main `4f80c5e9831ec39368f4e771d829337598eeaa76`, Server main `d8c7a2f3b96ba278d269074dc40cca7741b4155b` (= snapshot).
  - Agent open PRs #80…#96, all stacked, #81–#96 draft. Top: #96 `phase8/entry-closure-cli-split`
    `ab32e6b5c8cd5f61110ad1eb9e8ebe2d31ea7e67` (base `phase8/multi-session` `c9e01428`). No PR newer than #96.
  - Server open PRs #62→#63→#64 (`phase7/agent-entry-progress` `2c73c421`).
  - Actions on `ab32e6b5` (runs 39457547760/746/733): 7 jobs, every job `steps=0`, no runner → **CI NOT RUN**.
- Read at `ab32e6b5`: AGENTS.md, docs/ops/development_environment_bootstrap.md, docs/handoff/phase8_worklog.md (incl.
  VIII-1b and the x64 multisession first-attempt exit1), phase8_report.md, windows_product_reader_v1_design.md,
  distribution_inventory_v1.md, offline_candidate_entry_v1.md, scripts/test-ingress-diagnostics.sh.
- Worktree: `C:\Dev\RomLogger\phase9-work`, new branch `phase9/cli-review-closure` from `ab32e6b5` (git worktree of the
  local clone; no existing worktree/frozen checkout touched). Test JARs copied from `phase8-cli-split`, verified against
  `lib/test-dependencies.sha256`; JNA 5.14.0 SHA256 34ed1e1f…c6 verified. `git status --porcelain` empty.
- Host: Windows 11 dev VM DESKTOP-KVHGDS9 (not X270), 12 logical CPUs, C: ≈6 GB free (keep fixtures small).
  JDK17 = Zulu 17.0.20.1 x64; actual Zulu 8u504 x86 `sun.arch.data.model=32` confirmed.

## Safety gates in force (unchanged by this session)

synthetic_only=true; production_authorized=false; launch_authorized=false; ack_eligible=false; formal_ack=DISABLED;
`--production-live` always refused; readiness overall BLOCKED; WRITER_UNPROVEN; Windows process provenance and directory
durability UNKNOWN; global_old_binary_exclusion=NOT_PROVEN. No ECU/X270/RomRaider/OpenPort, no existing DB/Worker/
Sender/containers/VPS/MCP/Caddy/secrets. No merge, rebase, force push, tag, release. CI NOT RUN ≠ test FAIL.

## Planned scope (Phase IX)

IX-1 R9-01 (CLI clean build + 4 CLI process tests + separate tool classes), R9-02 (report timeline), R9-03 (error=267
isolation); IX-2 RLWR1 synthetic Windows reader; IX-3 gated entry wiring if IX-2 stable; IX-4 RLDI1 TOCTOU; IX-5 frozen
acceptance and handoff. Branches stack on #96.

## 2026-10-21 ≈10:20–11:00 JST — IX-1 (branch `phase9/cli-review-closure`, Draft PR #97)

R9-01 (P1) — **reproduced**. At `ab32e6b5`, a clean `scripts/test-ingress-diagnostics.sh` (JDK17 x64) exits 0 but leaves
no `IngressDiagnosticsCli`, `DeploymentTransitionCli`, `ArtifactProvenanceCli` or `DeploymentBindingCli` class in
`build/ingress-diagnostics`; the documented `java … IngressDiagnosticsCli` fails with `ClassNotFoundException`
(log `r901-diag-x64.log` SHA256 2219fc6b075e0a22ed9007a0bff52409489ded21e3727450fe166a0740932325). Nothing in the repo
built the four split-out CLIs (they are not referenced by tests or the entry). Fix (`bc4226d`/`815dae7`):
`scripts/build-offline-tools.sh` (separate tool JAR from `src/main` only; no Main-Class, JNA or test classes),
`test-ingress-diagnostics.sh` compiles + starts `IngressDiagnosticsCli` explicitly, `scripts/test-phase9-cli.sh`
(+`Phase9CliProcessTest`, 39 checks) separates build stage and child-JVM run stage (child classpath = tool JAR + deps).
First drafts of my own test had two wrong expectations (IngressDiagnostics 2-arg = SESSION LEDGER; `--describe` of the
tool JAR correctly refuses MAIN_CLASS_MISSING) — test corrected, product unchanged.

R9-02 — `phase8_report.md`: row annotated "BLOCKED at 8b8a0f0 (historical)" and a timeline section appended.

R9-03 — investigation (owned/read-only only): all 330 leftover `%TEMP%\romlogger-phase7-*` homes (created by earlier
sessions' tests; not touched) have every file but **no `work` directory**, and `work` is the only always-empty
directory of a fixture. Home-directory mtimes cluster at **2026-10-20 22:43** (262 homes) and **2026-10-21 04:53** (50);
the failing x64 multisession run spanned ≈22:38 (start of restart pass) – 22:54 (log write). No repository code deletes
`work`. Storage Sense is enabled (`01=1`, temp cleanup `04=1`, run cadence `2048=0` = on low disk space; C: ≈6 GB free).
No Task Scheduler entry ran at those minutes; event logs show nothing attributable. Conclusion: an external cleaner
removed empty directories under %TEMP% during the run — **ROOT_CAUSE_NOT_PROVEN** as to the exact actor. Owned probe
(empty `work` + non-empty sibling under %TEMP% and a control outside TEMP, 20 s sampling, paths not logged) started
10:42 (`r903-probe-*.log`). Fix: supervisor refuses `WORKING_DIRECTORY_MISSING` before `ProcessBuilder.start`
(RED at bc4226d: `[5 REVIEW_REQUIRED]`, `r903-red-x64.log` 7bb96c5f…; GREEN at 815dae7 x64 + actual x86, 14 checks);
fixture `work/` gets a keep marker; harness throws `FIXTURE_WORKDIR_MISSING` (no retry, no recreation).

## 2026-10-21 ≈11:00–11:10 JST — IX-2 (branch `phase9/windows-reader-synthetic`, `c42c4a6`)

RLWR1 `WindowsProductReader` (+ `WindowsProductReaderJna`) implemented per design with stable codes. Dev-tree results
(not frozen): `scripts/test-phase9-reader.sh` JDK17 x64 exit0 (fake 54, native 43, RLC2 scenarios 99/198 incl. both share
modes; log 42a6f9ac…), actual Zulu8 x86 `--run-only` exit0 (model=32, pointer=4, fake 54, native 44, scenarios 100/200;
log 63167c89…). Iterations recorded: handle-count threshold (JVM background handles +27 → warm-up and <64 bound; a
per-open leak would add ≥200), no-JNA child had to be a JNA-free nested class, MAX_READ long→int compile error, and the
binding is loaded by fixed class name so the logic class compiles without JNA. NOT_RUN: symlinks (no privilege),
non-NTFS/mapped/subst drives (no owned fixture). FILE_SHARE_DELETE and JNA licence: OWNER_DECISION_REQUIRED.

## 2026-10-21 ≈11:15–12:20 JST — IX-3, IX-4, independent review, fixes

- IX-3 (`4285e7d`, `0612693`): my first `Phase9ReaderEntryTest` ran forged deployments with the *base* profile path
  (test bug; the product refused correctly once invoked with the forged profile, as Phase VIII already showed). Fixed.
  Finding **F-IX-1**: a real install with the entry's sqlite-jdbc/slf4j/JNA JARs is RLDI1 MISMATCH (third-party mains,
  manifest Main-Class, multi-release classes). Pinned in the test; not relaxed. x64 dev 72 checks.
- IX-4 (`6736229` RED test, `bc91d17` fix): `--expect-red` on the pre-fix code → 4/4 MATCHED (same-size/same-mtime byte
  change and byte-identical physical replacement of approved JAR/profile). Fixed → 14 checks; Phase VIII inventory 70.
  Launcher env test 25 checks x64.
- R9-03 probe result: **11:46:25 the owned empty `work` dir under %TEMP% vanished**; its non-empty sibling, marker file and
  the control outside TEMP stayed (free space 4.92 GB). Mechanism proven (external removal of empty directories under
  %TEMP%); actor not identified (Storage Sense most likely) → still ROOT_CAUSE_NOT_PROVEN for the actor. VM setting not
  changed (owner's machine).
- Independent review (separate agent context, read-only) at bc91d17: P1 A-B-A between JAR hash and scan (scan re-opened
  the path); P2: handle not closed on `Error` after open; `Refusal` message `CODE win32=N` lost the code through
  `cause()`; `WORKING_DIRECTORY_MISSING` checked after the run record was created (orphan); weak test assertion
  (`||d==a`), CLI test built a different entry JAR than the Windows closure; `rm -rf` on any OUT_DIR; JarFile leak;
  short-name (8.3) path refusal untested; concurrent append could cause `READER_LENGTH_UNSTABLE` (fails closed);
  JNA extracts `jnidispatch.dll` to temp (the reader itself writes nothing; JNA does). All fixed except the last three,
  which are documented. The A-B-A case is covered by a new GREEN regression (no hook existed on the pre-fix code to
  make it deterministic, so no RED is claimed). Frozen acceptance at bc91d17 was stopped after 7 suites (all exit0; logs
  kept in `accept-bc91d17`) because the review fixes change runtime code; owned leftover JVM/driver processes of that
  checkout were terminated (only those).

## 2026-10-21 12:09–15:16 JST — IX-5 frozen acceptance and close

- Frozen `987e504` (all Phase IX runtime/tests): 11 suites JDK17 x64 + 11 suites actual Zulu8 x86, **all exit0**,
  dirty=0 before/after (table in [evidence](phase9_validation_evidence.md)). Multisession 10 sessions passed first time on
  both runtimes. IX-1 frozen `815dae7` x64 10 suites exit0; its x86 part interrupted by me (covered by 987e504).
- CI on all phase9 branches: jobs `steps=0` → CI NOT RUN. No merge, rebase, force push, tag or release.
- R9-03 probe stopped at ≈15:17 (owned process only); only one removal observed (11:46:25). Probe dirs
  (`%TEMP%\romlogger-p9probe-*`, `C:\Dev\RomLogger\p9-probe-*`) are owned synthetic leftovers.
- Server: docs-only Draft PR RomRaiderServer#65 on #64.
- Not run: Linux, owned PG16/HTTP35/10k load, Phase V/VI suites, symlink and non-NTFS fixtures.
- Resume point: owner decisions in the [Phase IX report](phase9_report.md) (FILE_SHARE_DELETE, JNA licence, F-IX-1
  RLDI1 third-party dependency policy); then RLDI2 design / Windows ACL evidence / Linux rerun of Phase IX scripts.
