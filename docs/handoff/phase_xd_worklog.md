# Phase X-D append-only worklog

## 2026-10-26 JST — kickoff (documentation only)

- Verified Agent main bcc81566b2c90b61de88ec0fb7fb3bf43631e0f9, Server main 3f16a701fb6fe1b0f94780f8dfef1ec38515dd84. No open PRs before kickoff.
- Verified X-C merged PR #106 and Agent main 4 workflow / 13 actual jobs all completed/success at attempt 1.
- Created new Agent candidate branch phase-xd/readiness-design at the exact Agent main SHA.
- Began D0 baseline + D1 dependency-completeness boundary plan. This commit is planning only, not implementation or X-D tests.
- RLDI2 validates present-file inventory. RLOP1 captures exact ordered classpath. Dependency required-set is not yet separately proved. Owner decisions on JNA, FILE_SHARE_DELETE, ACL principals and Windows 10 remain open.
- No Server edits, production, X270, real car, sender, formal ACK or MCP/Caddy operations.
- Next: derive dependency closure from actual code; prepare reviewed read-only isolated candidate API / RED→GREEN synthetic tests. Keep PR Draft / unmerged.

## Worklog policy

Append subsequent exact SHA, tests, RED/GREEN steps, failures, independent review and remaining NOT_RUN. Do not erase historical entries.

## 2026-10-26 JST — Claude Code: baseline, review, RLDR1 RED→GREEN, environments (appended)

Executor: Claude Code on the Windows 11 dev VM. Fresh session-only clones `C:\Dev\RomLogger\phasexd-work` (Agent),
`phasexd-server` (Server), worktree `phasexd-docs` (this branch). No other worktree was edited.

1. **Baseline re-verified** (GitHub API): Agent `main` `bcc81566…`, Server `main` `3f16a701…` — equal to the instruction
   baseline. Open PRs: Agent #107 only (Draft, head `6d9c0fd`); Server none. #106 merged (merge commit, 2026-10-26 05:35 UTC);
   Server #66 merged (2026-10-24 11:57 UTC).
2. **Read** AGENTS, bootstrap, X-C report/evidence/worklog/dossier, RLDI1/2/RLOP1/RLIR1/RLWR1 contracts, #107's four files,
   and the code (`OfflineCandidateEntry`, `DistributionInventory(V2)`, `ArtifactProvenance`, `WindowsProductReader*`,
   SQLite/SLF4J/JNA uses, `phase7-packaged.sh`). Review of #107 appended to its design draft and plan (this branch).
3. **Implementation branch** `phase-xd/dependency-readiness` from `main` → Draft PR #108:
   - `f4b7660` design + threat model (`docs/design/phase_xd1_required_dependency_readiness.md`) before code.
   - `891a6f5` RED: `PhaseXdReadinessTest` + `PhaseXdClosureProbe` + `scripts/test-phase-xd-readiness.sh` against an API
     stub. First RED attempt crashed with an NPE inside the test's own JSON check (test bug, fixed before the commit; its
     fixture dir `romlogger-phasexd-7920…` in %TEMP% was removed afterwards). Recorded RED: `--expect-red`, JDK17 x64,
     RED_COUNT 223, log sha256 `ec72835d…395a`.
   - `ec342b3` GREEN. During GREEN three **test** corrections (no change of intent): (a) the unlisted-file fixture was a
     copy of sqlite-jdbc, so RLDI2's alphabetically first finding was `DUPLICATE_CLASS`, not the expected
     `UNLISTED_EXECUTABLE_OR_ARCHIVE` → fixture changed to an unlisted `.cmd`; (b) flipping the last byte of a JAR breaks
     the ZIP (`JAR_UNREADABLE` sorts first) → the case now asserts inventory MISMATCH and checks RLDI2's
     `RLDI2_DEPENDENCY_NOT_PINNED` finding directly; (c) pin lists are reported sorted. JDK17 x64 1137 PASS, actual Zulu 8
     x86 1137 PASS.
   - `4d9ad6e` interface contract `docs/interfaces/dependency_readiness_v1.md`; pushed; PR #108 opened (Draft).
   - `6272129` permission-denied JAR case (POSIX), read-only Windows ACL PoC (test code), RUNTIME line (the earlier x64
     and x86 logs were byte-identical because nothing runtime-specific was printed).
4. **VPS Linux** (pinned key OK; user `claude_codex`; JDK17 Ubuntu): private `umask 077` sandbox
   `/tmp/romlogger-phasexd-4d9ad6e-1793003743`; head `4d9ad6e`: X-D 1137, X-C 244 (JNA cases ran on Linux for the first
   time), phase8-inventory 76, X-A zip 45, X-B zip 26 — all exit 0. Head `6272129`: X-D 1187 PASS incl. mode-000 JAR.
   Sandbox removed afterwards. Six `/tmp/romlogger-phasexc-*` dirs (09:37 UTC, same account) were **not** provably from
   this session → left untouched.
5. **Docker host** (pinned key OK; separate host from the VPS): 14 containers Up before/after. Per run: new network +
   PG16 (`postgres:16-alpine`, tmpfs, no published port) + agent container (`eclipse-temurin:17-jdk`, shares PG's
   network namespace), all labelled `romlogger.owner=phasexd-<id>` and removed by label check. Server `d8c7a2f` and
   `3f16a70`: guard 6, spool 322, real Agent→FastAPI→PG E2E PASS, RLC2 real HTTP 35 checks, X-D 1137 — all exit 0.
   Note: the image's apt Python is 3.14.4 (CI uses 3.12). Sandbox removed.
6. **Local PG16** 127.0.0.1:55432 was stopped; started via `romlogger-pg.ps1`, `run-server-tests.ps1` on Server `3f16a70`:
   migrate ×2 + check, pytest 57 passed; stopped again (state as found).
7. **Windows ACL PoC** (X-D2): fixture objects candidate-only; parent chain MISMATCH at the volume root, UNKNOWN at two
   AppData levels (unresolved SID). No ACL written by the probe. Note: the RLDR1 test fixtures use the existing harness
   `DeploymentBindingTest.privateDir`, which sets an owner-only ACL on each **newly created** fixture directory (as every
   Phase VII–X-C suite does); the ACL PoC deliberately does not.
8. **Docs track** (this branch): X-D0 current-state blocks, reader-value drift fix, X-C dossier note, review appendices,
   owner decision record. **Server** docs-only Draft PR RomRaiderServer#67 (stale #66 status).

Prohibited items untouched: X270, ECU, OpenPort, RomRaider, Sender, formal ACK, production DB, VPS Caddy/MCP/systemd,
Docker daemon/existing containers/volumes/networks/images, main, merges, Ready, tags, releases.

## 2026-10-26 JST — CI FAIL at `6272129` and test fix `9aa2253` (appended)

- Run [40297595012](https://github.com/zawa356/RomRaider_agent/actions/runs/40297595012) attempt 1 (Phase VII–IX, head
  `6272129`): 5/6 jobs success; **phase8 (windows-latest) job 121487334190 FAILED** on JDK17 (log sha256
  `db119fee…074d`): `PhaseXdWindowsAclProbe` asserted that a fresh fixture's inherited ACL has no broad write principal.
  On the GitHub runner, `RUNNER_TEMP`'s inherited ACL gives a broad group a write-class right (`WRITE_BROAD_GROUP` on
  `install\lib`), so the probe correctly said `MISMATCH` and the test's environment assumption was wrong. The other three
  workflows at `6272129` succeeded (ingress 40297595098, spool 40297594981, real Server E2E 40297594994).
- Fix `9aa2253` (test only): observe the fixture classification; assert only that verdicts are provisional classes and
  the classifier cases. Local JDK17 x64 / actual Zulu 8 x86 1168 PASS. Not retried at `6272129` (the failure stays).
- Finding kept for the owner: on a stock CI Windows image, a freshly created directory under the runner temp already
  inherits a broad-group write-class right — an ACL gate could not assume "new private directory ⇒ owner-controlled".

## 2026-10-26 JST — CI at `9aa2253`: unrelated DRIVE_TYPE_UNPROVEN, failed-job rerun (appended)

- Run [40300936878](https://github.com/zawa356/RomRaider_agent/actions/runs/40300936878) attempt 1: 5/6 jobs success;
  phase8 (windows-latest) job 121498362441 **FAILED** (log sha256 `5a1f2833…d29d`). The X-D suite in that job passed
  (`PHASE_XD_READINESS_CHECKS 1144`). The failure is in the next suite, `test-phase8-readiness.sh` on JDK17:
  `DeploymentBinding$Refusal: DRIVE_TYPE_UNPROVEN` from `WindowsDriveGate.requireFixed` — the Windows drive-type probe
  refusal documented in Phase X-A (`phase_xa_ci_stability_report.md`, open item C4: intermittent probe timeouts). The
  gate fails closed as designed; this branch changes no product code except the new `DependencyReadiness.java`, and the
  same suite passed in the same job at `4d9ad6e` and `6272129`.
- Other workflows at `9aa2253`: ingress 40300936916, spool 40300937000, real Server E2E 40300936882 — success, attempt 1.
- Retry reason: classified as the known intermittent C4 probe refusal (not a regression); `gh run rerun --failed` →
  attempt 2 of the failed job only. Attempt 1's failure stays recorded here; the gate was not weakened.
- Timing evidence: last PASS line 09:42:35.24Z → refusal 09:43:10.50Z (35.3 s gap ≈ the 30 s probe bound set in Phase X-A plus JVM overhead), the probe-timeout pattern, not a fast refusal.

## 2026-10-27 JST — independent review fixes (P2-1, P2-2, evidence, ACL wording) (appended)

- Start: Agent main `bcc8156`, Server main `3f16a70` (both unchanged); #108 `9aa2253`, #107 `745f8fa`, Server #67
  `9629627` — all equal to the reviewed heads; no other session's commits.
- **P2-1 (RLOP1 static rules).** Cause: `DependencyReadiness.profileCode` re-implemented part of
  `OfflineCandidateEntry.verifyProfile` and left out the `expected_generation` parse/range and `requireNotProduction`.
  RED `8dde278` (tests only, product code = `9aa2253`), JDK17 x64: generation `invalid`/`-1`/``/`1.0`/`2147483648`/`0x10`
  and production-looking `data_root`/`working_directory`/`layout.ledger`/`layout.legacy` all reached
  `MATCHED_NOT_AUTHORIZATION`. Fix `e6bace2`: `profileCode` now calls RLOP1's own package-private code
  (`new OfflineCandidateEntry.Profile(...)`, `expectedGeneration >= 0`, `requireNotProduction`) — not a copy;
  `OfflineCandidateEntry` itself unchanged. The tests also show RLOP1's code refuses each of those profiles.
- **P2-2 (profile/record identity).** Cause: profile and RLDI2 record were bound by SHA-256 only, the JARs by SHA-256 +
  identity. RED: a byte-identical replacement of either reached `freshness MATCHED`. Fix `e6bace2`: both are bound by
  SHA-256 + `DeploymentBinding.fileIdentity` at start, after RLDI2 (record) and at the end; identity unavailable at start
  → `UNKNOWN` (`PROFILE_IDENTITY_UNAVAILABLE` / `RECORD_IDENTITY_UNAVAILABLE`), at the end → `MISMATCH`. Still a
  snapshot of one inspection, not lasting safety. JAR identity case kept and passing.
- RED total: `RED_COUNT 26` = 13 new cases × 2 checks, no existing case red. Docs `9757533` (contract/design).
- GREEN at `9757533`: Win11 JDK17 x64 and actual Zulu 1.8.0_504 x86 (data model 32) 2170/2170; VPS Linux JDK17
  (fresh private sandbox, removed after) 2218/2218 + X-C 244, RLDI1 76, X-A 45, X-B 26, all exit 0.
- Docker E2E not re-run (decision): the change is inside `DependencyReadiness`, which no CLI/launcher/reader/Sender/Server
  path calls; CI's real Server E2E passed at `9757533`. Docker host untouched this round.
- CI at `9757533` attempt 1: spool, real Server E2E success. **Two failures, both outside X-D:**
  1. Phase VII–IX run 40429872972, phase8 windows job 121944670108: X-D passed (2170 x64 and x86, ACL 18, closure 6),
     then `test-phase8-readiness.sh` → `DRIVE_TYPE_UNPROVEN` after a 30.4 s gap (last PASS 01:04:57.70Z → 01:05:28.11Z) —
     the known C4 probe-timeout pattern (second occurrence in X-D, previous at `9aa2253`). Gate not touched.
  2. Ingress run 40429873076, Zulu8 x86 job 121944670338: `V1LaneMigrationTest` "injection actually reached prepared
     migration commit" (in `test-phase2-lanes.sh`). The same test passed in the same job on JDK17 x64 at 01:01:14Z; it
     passed 10/10 locally on actual Zulu 8 x86 at `9757533`; neither the test nor any class it uses changed since main.
     The underlying exception is swallowed by the test's `reject()`, so **the cause is UNKNOWN** (not a proven flake).
     Earlier occurrence (2026-10-21) was the 8.3 `%TEMP%` cause, fixed then; not proven to be the same now.
  Job logs sha256 `d0036f63…7285` / `1a62c796…a03`. Failed jobs only rerun → attempt 2: all success (6/6, 4/4).
  Attempt 1 failures stay recorded here.
- #107 docs: ingress job count corrected to 4 in three evidence rows (GitHub API: Phase VII–IX 6, ingress 4, spool 2,
  real Server E2E 1 = 13 in every run listed); ACL statement in `phase_xd_owner_decisions.md` narrowed to the inspected
  hosts with a dated correction note. Server #67 unchanged (no reason found).
