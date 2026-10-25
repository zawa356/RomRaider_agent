# Phase X-C worklog (append-only) — Claude Code, 2026-10-25 JST

Rules: append only; never rewrite earlier lines. Times are JST. Each entry names the branch/commit it refers to.

## 2026-10-25 21:50 — X-C0 preflight (read-only)

- Host: Windows 11 dev VM (Claude desktop app, Code tab). The usual `C:\Dev\RomLogger\*` checkouts were **not** used: this
  session cloned both repositories fresh into a session-private folder (`git -c core.longpaths=true clone`; the first plain
  clone failed with "Filename too long" for deep `src/` paths under the long session path — no repository content involved,
  re-cloned with the per-repository long-path setting; no system setting changed).
- Verified `main`: Agent `7fdb4db893828718621ce8965664a89e36b9281a`, Server `3f16a701fb6fe1b0f94780f8dfef1ec38515dd84` —
  equal to the instruction snapshot. Agent PR #81/#92/#104 CLOSED (draft, unmerged, branches kept); #105 MERGED
  2026-10-22T13:47:37Z. Server #62 and #66 MERGED 2026-10-24T11:57Z, #65 CLOSED. No open Agent PR. Post-merge Agent `main`
  CI runs 39790019299 / 39790019276 / 39790019559 / 39790019494 all `success`.
- Tools: JDK17 x64 `C:\Program Files\Zulu\zulu-17`, actual Zulu 8 x86 `C:\Program Files (x86)\Zulu\zulu-8` (first on PATH;
  build steps prepend JDK17 explicitly). `gh` authenticated as zawa356. C: 63 GB free. `%TEMP%\romlogger-*` of unknown
  owners left untouched.
- Dependency JARs fetched through the existing pinned paths: `scripts/test-product-v1-spool.sh --deps-only`
  (sqlite-jdbc 3.46.1.3, slf4j-api/nop 2.0.16: `sha256sum -c` OK) and JNA 5.14.0 into `build/identity-deps`
  (`34ed1e1f…fe8c6` OK). None is tracked.

### Inconsistencies found (X-C0)

1. Agent `AGENTS.md` top entry still said "Draft PR #104 … unmerged; no merge"; bootstrap top said "Top of the Agent stack:
   Draft PR #104"; `docs/00_index.md` top was Phase VI. A fresh session could branch from an old stack branch.
   → fixed by prepending a 2026-10-25 current-state block to all three (history kept verbatim).
2. Server `docs/implementation_progress.md` last section (2026-10-22 追記) says "本 Server PR（#66）は owner の再レビュー待ちで、
   未マージ" and "#81〜#104 は Draft・open のまま". Both became stale on 2026-10-24 (#66 merged; old Agent PRs closed). Server
   is out of scope for code/doc changes in X-C → recorded here and in the X-C report only (owner/next phase).
3. RLDI1 F-IX-1 text (distribution_inventory_v1.md, phase9 report) attributes `UNAPPROVED_MANIFEST_MAIN` to sqlite-jdbc.
   Measured on the pinned JARs: sqlite-jdbc's manifest has **no** `Main-Class`; the manifest main is JNA's
   `Main-Class: com.sun.jna.Native`. sqlite-jdbc contributes the class main `org.sqlite.util.OSInfo` and 4 multi-release
   classes; slf4j-api and slf4j-nop each contribute `META-INF/versions/9/module-info.class`. History not edited; the
   correction is recorded in the RLDI2 design and an X-C note appended to RLDI1.

Branch `phase-xc/rldi2-candidate` created from `7fdb4db`.

## 2026-10-25 22:10–23:05 JST (13:10–14:05Z) — X-C1 design, RED, implementation, GREEN

- `4a93bb7` docs X-C0 (current-state blocks in AGENTS.md, bootstrap, index; this worklog).
- Measured the four pinned JARs with the RLDI1 parser (scratch probe outside the repository): exact findings recorded
  in `docs/design/rldi2_dependency_policy_design.md` §1. No versioned class declares `main`; no signature files, nested
  archives, Class-Path/agent attributes in any of them.
- `06c34f8` test RED: `PhaseXcF1ReproTest` + `scripts/test-phase-xc-rldi2.sh` + design doc. `--expect-red` at this head:
  JDK17 x64 exit 0, 18 checks, RED_COUNT 1 (log sha256 a33061023acc…); actual Zulu 8 x86 (model 32) exit 0, 19 checks,
  RED_COUNT 1 (b34ad67dd319…). RED = "no RLDI2 contract"; the RLDI1 MISMATCH itself is intended fail-closed behaviour.
- `004f8a6` feat: `DistributionInventoryV2` + `docs/interfaces/distribution_inventory_v2.md`. Planned a visibility change
  of `ZipEntryView.verify()`; it turned out to be package-private already → `DistributionInventory.java` untouched
  (`git diff main` empty for that file). Design doc corrected before commit.
- First GREEN attempts (uncommitted test, JDK17): (1) assertion too strict — early refusals report `access_control:UNKNOWN`
  (never inspected) on Windows, which is correct; assertion widened to "UNKNOWN or UNKNOWN_WINDOWS_ACL_NOT_INSPECTED".
  (2) Test-tool issue: Windows Python cannot open the >260-char test path; edits done with the editor tool instead.
  (3) Case-variant rename fixture: `Files.move(jna-…, JNA-…)` is a no-op on NTFS; the record then listed an alias name and
  RLDI2 correctly reported `LISTED_FILE_MISSING`+`UNLISTED_EXECUTABLE_OR_ARCHIVE`. Kept as an explicit Windows alias case;
  the shape case now renames in two steps. Then JDK17 186 checks exit 0; actual x86 187 checks exit 0 (≈35 min).
- x86 was slow (hashing/inflating 14 MB sqlite-jdbc per fixture). Optimisations: RLDI1 record written lazily, light base
  install for archive/malformed cases. They introduced three **test-only** bugs, each committed as found and fixed in a
  separate commit (history kept): `6ef2717` (GREEN commit) failed "no-write snapshot" because the lazy RLDI1 record was
  created after the snapshot → `6df4ea3`; `6df4ea3` failed the duplicate-class case because the light base lacked
  sqlite-jdbc → `097a0e9`; `097a0e9` failed the RLDI1/RLDI2 agreement case (lazy record written after the profile edit)
  → `2febacd`. No product code changed in these commits.
- `2febacd` frozen: JDK17 x64 `test-phase-xc-rldi2.sh` exit 0 (19 + 186 checks, 6m16s). RLDI1 regressions at `2febacd`
  JDK17 x64: phase8-inventory 70, xa-zip 45, xb-zip 26, phase9-race 23 PASS lines, all exit 0 (= Phase X-B counts).
- Pushed `phase-xc/rldi2-candidate`; Draft PR #106 (base `main`). CI started on `2febacd`.
- Fixture hygiene: the GREEN test removes only the fixture directories it created itself (69 per run) after a passing run;
  `%TEMP%\romlogger-*` from other runs/owners untouched.

## 2026-10-25 23:05–23:40 JST (14:05–14:40Z) — CI, x86, evidence

- Docs push `7509227` re-triggered the PR workflows; the in-flight `2febacd` Phase VII–IX and ingress runs were cancelled
  by their concurrency groups (Windows jobs mid-run; not test results). Lesson: batch docs pushes until CI on the code
  head has finished, or expect a full re-run.
- `7509227` pull_request attempt 1: 4 workflows, 13/13 jobs success (runs 40200553998, 40200553967, 40200553960,
  40200553973). Job logs downloaded and grepped: X-C suite ran on ubuntu (16+168, JNA NOT_RUN), Windows Server 2025 JDK17
  (19+186) and actual Zulu 1.8.0_512 x86 model 32 (20+187).
- Local frozen `2febacd`: x64 19+186 (6m16s), actual Zulu 8 x86 20+187 (6m55s); RLDI1 regressions x64/x86 equal to
  Phase X-B counts; x64 X-B ZIP log byte-identical to the X-B GREEN log. Frozen-run logs say `clean=1` = untracked
  dossier directory only.
- `main` re-fetched: still `7fdb4db`. Report/evidence written. Next: docs push, CI on the final docs head, PR description.
