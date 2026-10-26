# Phase X-C evidence (2026-10-25 JST)

## 1. Git

- Base: Agent `main` `7fdb4db893828718621ce8965664a89e36b9281a` (re-checked at start and before the final push; unchanged).
  Server `main` `3f16a701fb6fe1b0f94780f8dfef1ec38515dd84` (read only).
- Branch `phase-xc/rldi2-candidate` from `7fdb4db`; Draft [#106](https://github.com/zawa356/RomRaider_agent/pull/106).
  No rebase, squash, amend after push, force push or branch deletion.

| Commit | Kind | Content |
|---|---|---|
| `4a93bb7` | docs | X-C0 current-state blocks; worklog start |
| `06c34f8` | test (RED) | `PhaseXcF1ReproTest`, `scripts/test-phase-xc-rldi2.sh`, RLDI2 design |
| `004f8a6` | feat | `DistributionInventoryV2`, RLDI2 contract |
| `6ef2717` | test | `PhaseXcRldi2Test`, CI wiring (`ci-phase7-9.sh` phase8), RLDI1 X-C note — **its own run failed** (test-only, see worklog) |
| `6df4ea3`, `097a0e9` | test fix | each fixed the previous head's test-only failure (both still failing a later case) |
| `2febacd` | test fix | **code/test head**: last change to code, tests or scripts |
| `7509227` | docs | owner decision dossier; worklog |
| later | docs | report, evidence, worklog (documentation only) |

`git diff 7fdb4db -- src/main/java/net/neristal/rrbridge/prototype/ingress/DistributionInventory.java` was empty up to
`719ee41`. Product code added: `DistributionInventoryV2.java` only. No other product file changed. Server: no change.
**Superseded by §5 (review fix):** from `f7b59ba` RLDI1's `DistributionInventory.java` differs from `main` by +18/−5
(JSON string escaping only).

## 2. RED (reproduction) — head `06c34f8`, Windows 11 dev VM

| Runtime | Command | Exit | Result | Log SHA-256 |
|---|---|---|---|---|
| JDK17 x64 (17.0.20.1) | `bash scripts/test-phase-xc-rldi2.sh --expect-red` | 0 | 18 checks, RED_COUNT 1 | `a33061023accda67c6ca67e148e7d7b277d0892349960f82f3ae4c091d74137e` |
| actual Zulu 8 x86 (1.8.0_504, model 32) | `… --expect-red --run-only` | 0 | 19 checks, RED_COUNT 1 | `b34ad67dd319835477fb0d51c62f475b40b506171fba3d11deffc366be6394d6` |

RLDI1 on the real candidate (4 dependency JARs): `MISMATCH`, findings exactly
`{UNAPPROVED_MAIN_METHOD:2, UNAPPROVED_MANIFEST_MAIN:1, VERSIONED_OR_META_CLASS:6}`, mains
`[com.sun.jna.Native, org.sqlite.util.OSInfo]`; per JAR as in the design §1. RED = "no RLDI2 contract exists"; the RLDI1
MISMATCH is the intended fail-closed result (not a defect).

## 3. GREEN — head `2febacd`, Windows 11 dev VM (logs under the session's `logs/`, not committed)

The frozen-run logs record `clean=1`: the only non-committed item then was the untracked `docs/decisions/` directory
(the dossier, committed next as `7509227`); no code, test or script was uncommitted.

| Runtime | Command | Exit | Checks | Time | Log SHA-256 |
|---|---|---|---|---|---|
| JDK17 x64 | `bash scripts/test-phase-xc-rldi2.sh` | 0 | repro 19 + RLDI2 186 | 6m16s | `858eeadc04ce4f7dc755f35231463cbba520cdd8a8eb7b631438923ad2ba08d4` |
| actual Zulu 8 x86 (model 32 asserted) | `… --run-only` | 0 | repro 20 + RLDI2 187 | 6m55s | `8de78d2cb3c164eecb3211da94a5620b74a40e5f975e824eb6cfb8fcbb2fc843` |

RLDI1 regressions (unchanged suites; x64 at `2febacd`, x86 `--run-only` at `7509227` = same code):

| Suite | x64 PASS | x86 PASS | Phase X-B reference |
|---|---|---|---|
| `test-phase8-inventory.sh` | 70 | 71 | 70 / 71 |
| `test-phase-xa-zip.sh` | 45 | 45 | 45 / 45 |
| `test-phase-xb-zip.sh` | 26 | 26 | 26 / 26 — x64 log SHA-256 `6d3b6f20…7d` is **byte-identical** to the Phase X-B GREEN log |
| `test-phase9-inventory-race.sh` | 23 | 23 | 23 / 23 |

GREEN coverage (instruction §3.4): 1 RLDI1 unchanged · 2 canonical MATCHED (4 pins; also alone, without JNA, each pin
alone) · 3 one-byte flip, repacked same-name, unknown version, extra clean dependency, tool-like JAR, swapped content ·
4 role swaps (approved/launcher/document), record pin edits (foreign, dropped, unknown id), policy id/digest edits, extra
policy key, version downgrade, renamed / case-variant / NTFS alias / second-copy alias · 5 injected legacy entry + main,
JNA `Main-Class` redirected, design check "no pin can bless a legacy entry" · 6 nested archive, five manifest
Class-Path/agent attributes, duplicate class, versioned shadow class (RLDI2 stricter than RLDI1), extra versioned main,
versioned legacy entry, case-variant manifest, forged signature files · 7 CRC mismatch in resource/DLL/.so/manifest/class,
long/padded/unterminated/short DEFLATE, entry and total expansion bombs, lying bomb · 8 same-size/same-mtime change and
physical replacement at HASHED and SCANNED, A-B-A (scanned = hashed), record change, file added · 9 missing listed JAR,
directory in place of a JAR, JNA optional · 10 platforms below · 11 RLDI1/RLDI2 agree on five no-dependency variants;
inventory wrote nothing (snapshot of every file's bytes and mtime). Policy validation: 13 forged exception shapes, empty
exception list, duplicate SHA → rejected.

NOT_RUN (never counted as PASS): permission-denied JAR fixture (cannot drop own read access portably); JNA cases on Linux
(ubuntu jobs do not fetch JNA: printed `NOT_RUN`); Windows 10; non-NTFS volumes.

## 4. GitHub Actions

### Head `2febacd` (pull_request) — superseded, kept on record

Spool [40200075753](https://github.com/zawa356/RomRaider_agent/actions/runs/40200075753) success; REAL Server E2E
[40200075789](https://github.com/zawa356/RomRaider_agent/actions/runs/40200075789) success. Phase VII–IX
[40200075779](https://github.com/zawa356/RomRaider_agent/actions/runs/40200075779) and ingress
[40200075774](https://github.com/zawa356/RomRaider_agent/actions/runs/40200075774) **cancelled** by the workflows' own
concurrency group when docs commit `7509227` was pushed (the PR path filter is evaluated on the whole PR diff, so a
docs push re-runs them). Ubuntu jobs had already succeeded; Windows jobs were cancelled mid-run. Not a test result.

### Head `7509227` (pull_request, attempt 1) — 4 workflows, 13/13 jobs success

| Run | Workflow | Jobs |
|---|---|---|
| [40200553998](https://github.com/zawa356/RomRaider_agent/actions/runs/40200553998) | Phase VII–IX synthetic | phase7/8/9 × ubuntu, windows — 6/6 success |
| [40200553967](https://github.com/zawa356/RomRaider_agent/actions/runs/40200553967) | Isolated ingress journal prototype | 4/4 success |
| [40200553960](https://github.com/zawa356/RomRaider_agent/actions/runs/40200553960) | Product v1 Windows Agent spool | 2/2 success |
| [40200553973](https://github.com/zawa356/RomRaider_agent/actions/runs/40200553973) | Product v1 REAL Server E2E (Server `d8c7a2f` pinned, disposable PG16) | 1/1 success |

X-C lines in the job logs (not only the run status):

| Job | X-C result lines | Job log SHA-256 prefix |
|---|---|---|
| phase8 windows-latest (Windows Server 2025) | JDK17: "F-IX-1 reproduction passed 19 checks", "RLDI2 candidate test passed 186 checks"; actual Zulu **1.8.0_512 x86, model 32** (`PASS actual JVM data model 32`): 20 / 187 checks | `d5b603b7d1bec1bd` |
| phase8 ubuntu-latest | 16 / 168 checks; `NOT_RUN jna-5.14.0.jar absent`, `NOT_RUN JNA role/alias cases`; canonical RLDI2 MATCHED with `access_control:POSIX_OWNER_ONLY_WRITE` (3 pins) | `9e67954e56f0ca61` |
| phase9 windows-latest | `Phase9ReaderEntryTest` (pins F-IX-1 under RLDI1) passed 74 (x64) / 75 (x86) checks | `6e413a2cbd1db7a2` |
| phase9 ubuntu-latest | `Phase9ReaderEntryTest` 5 checks (Windows-native parts NOT_RUN) | `762e8b11dd490f93` |

Phase8 Windows job time: 14m29s (was ≈8 min on `main`; 120 min limit). No step skipped other than the existing
Windows-only steps on Linux jobs.

### Final documentation head

Commits after `7509227` change documentation only. Their CI runs are listed in the PR description / a PR comment
(this file cannot contain its own head's results).

## 5. Independent review fix (2026-10-26 JST)

Review (ChatGPT, relayed by the owner): P2 JSON escaping; completeness boundary; PR body commit count.

| Commit | Kind | Content |
|---|---|---|
| `8bb1d4c` | test (RED) | `PhaseXcJsonEscapeTest`; unused helper `DistributionInventory.jsonString`; suite line in `test-phase-xc-rldi2.sh` |
| `f7b59ba` | fix | RLDI1 and RLDI2 `Report.json()` escape every string — **code/test head** |
| `0a2a652` | docs | contract v2 responsibility boundary + JSON encoding; RLDI1 dated note; design §7 |
| later | docs | this section, report §8, worklog |

Product diff vs `main` now: `DistributionInventoryV2.java` (new) and `DistributionInventory.java` +18/−5
(`jsonString` helper; `json()` uses it for `inventory`, `reason`, finding keys, `unapproved_mains`, `access_control`).
Unchanged in RLDI1: walk, roles, hashes, ZIP verification, findings, refusals, record format, `describe`, entry-name rule.

### RED — `8bb1d4c`, JDK17 x64, Windows 11 dev VM

`PhaseXcJsonEscapeTest -Dphasexc.jsonExpectRed=true` exit 0, 16 PASS, **RED_COUNT 25** (log SHA-256
`279bf68e64b36df6a5f1bcf694d4af2b53d995a07fcd65569cb65651c920643b`): RLDI1 and RLDI2 reports (direct and real JAR)
and `OfflineCandidateEntry --inventory` are not valid JSON (`expected ]`, `bad escape \s`); a name
`x/In","launch_authorized":true,"j":".class` adds a second `launch_authorized` key. Inventory verdicts were
`MISMATCH` throughout; the control-character entry was `JAR_UNREADABLE` in both.

### GREEN — `f7b59ba` frozen (`clean=1`), Windows 11 dev VM (logs not committed)

| Suite | JDK17 x64 | actual Zulu 8 x86 (model 32) | Phase X-B / X-C reference |
|---|---|---|---|
| `test-phase-xc-rldi2.sh` | exit 0 — repro 19, RLDI2 186, **escape 41** (408 s) `0f4d6be2…` | exit 0 — 20, 187, **42** (427 s) `8e44e130…` | 19+186 / 20+187 (+ new suite) |
| `test-phase8-inventory.sh` | 70 `a4497cd3…` | 71 `9c2e2406…` | 70 / 71 |
| `test-phase-xa-zip.sh` | 45 `4d27a38e…` | 45 `3a4022ac…` | 45 / 45 |
| `test-phase-xb-zip.sh` | 26 `8ed4472d…` | 26 `b14cad70…` | 26 / 26 — x64 output line-for-line equal to the earlier X-C/X-B log (`6d3b6f20…`, CRLF) |
| `test-phase9-inventory-race.sh` | 23 `f1ebfe6c…` | 23 `a68ff705…` | 23 / 23 |

(x86 escape = x64 + the data-model check.) Escape test coverage: all 65 536 UTF-16 code units round-trip as printable
ASCII; ordinary names byte-identical; direct reports with `"`, `\`, NUL/0x1F, DEL, U+2028/9, lone surrogate and a
key-injection name; a real unpinned dependency JAR with 9 hostile main names + 1 versioned hostile main → RLDI1/RLDI2
still MISMATCH (`UNAPPROVED_MAIN_METHOD`, RLDI2 also `RLDI2_DEPENDENCY_NOT_PINNED`), JSON strict-valid with the exact key
set and gates, names exact; `--inventory` exit 5 with the nested report intact; control-character entry still
`JAR_UNREADABLE` in both.

NOT_RUN (unchanged): JNA cases on Linux; permission-denied JAR fixture; Windows 10; non-NTFS volumes; real writer; X270.
The escape test itself uses only slf4j and runs on ubuntu too.

### CI on the final head

Recorded in the PR description and a PR comment (this file cannot contain its own head's results).
