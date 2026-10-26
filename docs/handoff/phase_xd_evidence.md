# Phase X-D evidence ledger

## Source baseline (not an X-D test)

Agent main X-C merge commit bcc81566b2c90b61de88ec0fb7fb3bf43631e0f9, parents 7fdb4db893828718621ce8965664a89e36b9281a and ff133ae04da2d44c61f630c29f3ce0833d3221cf; reviewed/merged tree 4e3ce5c7de648a29f5076da578d9909d40f2263b. Server main unchanged 3f16a701fb6fe1b0f94780f8dfef1ec38515dd84.

At the above Agent main HEAD: Phase VII-IX run 40275065445 6/6, ingress run 40275065395 4/4, spool run 40275065452 2/2, isolated real Server E2E run 40275065500 1/1; each attempt 1, completed/success. These verify the prior baseline only.

## X-D-specific work

| Check | Status |
| --- | --- |
| Actual code-derived profile required-set | NOT_RUN |
| D1 contract/prototype | NOT_RUN |
| New negative RED/GREEN tests | NOT_RUN |
| Windows ACL private fixture probe | NOT_RUN |
| Windows 10 non-car VM synthetic suite | NOT_RUN |
| Real writer rotation trial | NOT_RUN / separate owner approval required |
| JNA redistribution approval | OWNER_DECISION_REQUIRED |
| Reader/Sender/formal ACK/production | BLOCKED / DISABLED |
| X270/ECU/real car and MCP/Caddy change | PROHIBITED |

Safety: only plan/worklog/evidence documents changed in X-D kickoff. No runtime, product, test harness, workflow, API, schema, or deployment changes. RLDI2 remains default-off and all authorization flags stay false.

## 2026-10-26 — Claude Code X-D evidence (appended; the kickoff table above is the pre-implementation state)

Implementation: Draft PR [#108](https://github.com/zawa356/RomRaider_agent/pull/108) `phase-xd/dependency-readiness`
(base `main` `bcc8156`). Product code changed: **only** the new `DependencyReadiness.java` (+272). Final code/test head
**`9aa2253`**. Docs track: this branch (#107), docs only. Server: Draft RomRaiderServer#67, docs only.

### X-D work status (replaces the NOT_RUN rows of the kickoff table for the items below)

| Check | Status |
|---|---|
| Code-derived per-reader required set | DONE — design §2 + `PhaseXdClosureProbe` (measured) |
| D1 contract/prototype | DONE — RLDR1 candidate, default-off, not wired |
| RED→GREEN | RED 223 (`891a6f5`, stub) → GREEN (`ec342b3` … `9aa2253`) |
| Windows ACL private fixture probe | DONE as read-only PoC (test code); result UNKNOWN/MISMATCH/candidate — never PASS |
| Windows 10 non-car VM | **NOT_RUN** — no such VM documented/reachable; plan in the owner decision record |
| Real writer rotation | **NOT_RUN** — separate owner approval required |
| JNA redistribution | OWNER_DECISION_REQUIRED |
| Linux JNA cases | DONE on VPS (pinned JAR copied); still NOT_RUN on ubuntu CI (JAR not fetched there) |
| Permission-denied JAR | DONE on VPS Linux (non-root, mode 000); NOT_RUN on Windows (no ACL change) and as root (Docker) |
| Reader/Sender/formal ACK/production | BLOCKED / DISABLED (unchanged) |

### Local / remote runs (synthetic, owned fixtures only)

| Environment | Runtime | Head | Suite | Result | Log sha256 |
|---|---|---|---|---|---|
| Win11 dev VM | JDK17.0.20.1 x64 | stub (pre-`891a6f5`) | X-D `--expect-red` | RED_COUNT 223 (exit 0) | `ec72835d…395a` |
| Win11 dev VM | JDK17 x64 / Zulu 1.8.0_504 x86 (model 32) | `ec342b3` | X-D | 1137 / 1137 PASS | `0ad96280…67a4` (identical logs: no runtime line yet) |
| Win11 dev VM | JDK17 x64 / Zulu 8 x86 | `9aa2253` | X-D (+ACL PoC) | 1168 / 1168 PASS | `96f3601c…86b8` / `ef7ed35f…6474` |
| VPS Linux (Ubuntu 24.04, 4 vCPU/8 GB) | OpenJDK 17.0.20.1 | `4d9ad6e` | X-D / X-C / phase8-inventory / X-A zip / X-B zip | 1137 / 244 / 76 / 45 / 26 PASS, all exit 0 | `5799d8f1…610e020`, `418605c2…bec84c`, `a7ad5ec7…41943`, `7c205466…53783`, `6d96416a…0aa` |
| VPS Linux | OpenJDK 17 | `6272129` | X-D (+mode-000 JAR) | 1187 PASS | `aaf6620b…19c1` |
| Docker host, isolated project (PG 16.15 tmpfs, no port) | Temurin 17.0.20.1, Python 3.14.4 | `4d9ad6e` + Server `d8c7a2f` | guard / spool / real E2E / RLC2 preview / RLC2 HTTP guard / RLC2 real HTTP / X-D | 6 / 322 / PASS / exit 0 / exit 0 / 35 checks / 1137 — all exit 0 | e2e `5e537c8e…5bd9c`, rlc2h `c9ab47ca…7994` |
| same | same | `4d9ad6e` + Server `3f16a70` | same | same results, all exit 0 | e2e `5e537c8e…5bd9c`, rlc2p `92671f77…44c9` |
| Win11 local PG16 127.0.0.1:55432 (test cluster) | venv-server Python | Server `3f16a70` | migrate ×2 + check, pytest | 57 passed, exit 0 | `e9c5718a…6865` |

Docker host before/after each run: 14 pre-existing running containers, unchanged; 0 labelled leftovers; image pulls: none.
Local PG16 was stopped before and is stopped after. Remote sandboxes removed after the runs.

### GitHub Actions (PR #108)

| Head | Workflow | Run | Attempt | Jobs | Result |
|---|---|---|---|---|---|
| `4d9ad6e` | Phase VII–IX | 40293098113 | 1 | 6 | success (X-D: ubuntu 1028, windows 1131 on JDK17 x64 and actual Zulu 1.8.0_512 x86, Windows Server 2025) |
| `4d9ad6e` | ingress / spool / real Server E2E | 40293098114 / 40293098116 / 40293098126 | 1 | 5 / 2 / 1 | success |
| `6272129` | Phase VII–IX | 40297595012 | 1 | 6 | **failure** — phase8 windows job 121487334190: ACL PoC test assumption (worklog) |
| `6272129` | ingress / spool / real Server E2E | 40297595098 / 40297594981 / 40297594994 | 1 | 5 / 2 / 1 | success |
| `9aa2253` | Phase VII–IX | 40300936878 | 1 | 6 | **failure** — phase8 windows job 121498362441: `DRIVE_TYPE_UNPROVEN` in `test-phase8-readiness.sh` (C4 probe timeout; X-D suite passed in that job) |
| `9aa2253` | Phase VII–IX | 40300936878 | **2** (rerun of failed) | 6 | success — X-D windows 1144 + closure 6 + ACL 18 (x64 and x86), ubuntu (attempt 1 job) 1076 + 5 |
| `9aa2253` | ingress / spool / real Server E2E | 40300936916 / 40300937000 / 40300936882 | 1 | 5 / 2 / 1 | success |

Steps: Windows jobs 16 steps, 0 skipped; ubuntu jobs 15 steps, 3 skipped (the Windows-only x86 setup steps); spool
durability 10/10; real Server E2E 17, 0 skipped. CI success is not "all tests PASS": ubuntu skips JNA cases; no CI job is
Windows 10, the X270 or a real writer. Docs-only heads on #107/#67 trigger no Agent workflow (path filters) — the Server
"PoC checks" workflow ran for RomRaiderServer#67 (run 40298242393, success).

### Gates at the end of X-D (unchanged)

synthetic_only=true; state=BLOCKED; launch_authorized=false; production_authorized=false; ack_eligible=false;
formal_ack=DISABLED; reader_adoption=BLOCKED; global_old_binary_exclusion=NOT_PROVEN; WRITER_UNPROVEN; Windows ACL
UNKNOWN (PoC only); process provenance UNKNOWN; directory durability UNKNOWN; MIN_WINDOWS=UNVERIFIED.
