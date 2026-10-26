# Phase X-C report — RLDI2 dependency-JAR candidate and Windows reader decision dossier (Claude Code, 2026-10-25 JST)

Read with: [evidence](phase_xc_evidence.md) · [append-only worklog](phase_xc_worklog.md) ·
[RLDI2 design](../design/rldi2_dependency_policy_design.md) · [RLDI2 contract](../interfaces/distribution_inventory_v2.md) ·
[owner decision dossier](../decisions/phase_xc_owner_decisions.md).

**No merge, no Ready-for-review, no release/tag/deploy. Synthetic-only. Launch/production/ACK disabled.**

## 1. Summary

| Item | Result |
|---|---|
| Base | Agent `main` `7fdb4db893828718621ce8965664a89e36b9281a` (unchanged during X-C); Server `main` `3f16a701…` (not touched) |
| PR | Draft [#106](https://github.com/zawa356/RomRaider_agent/pull/106), base `main`, branch `phase-xc/rldi2-candidate` |
| Code/test head | `2febacdf253f93ed0b0885f3f04a6ca83be2eea0`; later commits are documentation only |
| X-C0 | current-state blocks prepended to `AGENTS.md`, bootstrap, `docs/00_index.md`; history untouched |
| F-IX-1 | reproduced exactly with the real pinned JARs under RLDI1 (intended fail-closed, not relaxed) |
| RLDI2 | `DistributionInventoryV2`: separate contract, fixed code-reviewed pins (option A), 3 exceptable kinds, exact-set rule, every dependency pinned; canonical candidate MATCHED (BLOCKED); 186/187 adversarial/regression checks GREEN |
| RLDI1 | inspection unchanged; after review P2 `DistributionInventory.java` differs from `main` only in JSON string escaping (+18/−5, §8); all RLDI1 suites re-run with Phase X-B counts |
| Review (2026-10-26) | P2 JSON escaping fixed in RLDI1 and RLDI2 (RED 25 → GREEN 41/42); completeness boundary specified; PR commit count corrected (§8) |
| X-C2 | dossier: RLDI2 adoption, JNA licence/distribution form, FILE_SHARE_DELETE, Windows ACL criteria, minimum Windows — facts / provisional recommendation / owner decision / unverified |
| CI | see evidence §4 (4 workflows; the new suite runs in `phase8` on ubuntu, windows x64 and actual Java 8 x86) |

## 2. X-C0 — current-state fixes

Found: the top of `AGENTS.md` and the bootstrap still described PR #104 as the unmerged top of a stack; the index top was
Phase VI. Fixed by prepending a 2026-10-25 block (main SHAs, old PRs closed and not to be reused, branch from `main`,
unchanged gates). Also found and **not** changed (Server out of scope): Server `docs/implementation_progress.md` last
section still says #66 unmerged and #81–#104 open (stale since 2026-10-24) → for the next Server docs change. Corrected
an F-IX-1 detail: the manifest `Main-Class` finding is JNA's, not sqlite-jdbc's (note appended to RLDI1, history kept).

## 3. X-C1 — RLDI2

- **Trust root:** the pin list is a `private static final` unmodifiable list in reviewed source; the record can only name
  a compiled-in pin id and the policy digest; unknown ids, other digests and extra keys are refused. No external policy
  file, no setter, no CLI.
- **Minimal exception:** per pin, the exact `KIND|subject` findings measured on that exact JAR (sqlite-jdbc: 1 main + 4
  versioned classes; slf4j-api/nop: 1 versioned `module-info` each; JNA: 1 main + manifest main). Only
  `UNAPPROVED_MAIN_METHOD`, `UNAPPROVED_MANIFEST_MAIN`, `VERSIONED_OR_META_CLASS` are exceptable; legacy entries,
  first-party classes, Class-Path/agent, nested archives, duplicates, manifest ambiguity and unreadable JARs never are
  (validated at class load and tested with forged pins).
- **Strong match:** role `dependency_jar` + SHA-256 of the scanned bytes + record pin id + file name + size + entry count
  + manifest `Bundle-Version` + single use. Observed exceptions must equal the pin's set.
- **Stricter than RLDI1:** every dependency must be pinned; versioned classes checked as classes (main/legacy/duplicate);
  class ownership keyed by path.
- **Unchanged:** all X-A/X-B ZIP checks (shared code), IX-4 identity/re-hash TOCTOU checks, JSON hygiene, BLOCKED and all
  flags false, `global_old_binary_exclusion=NOT_PROVEN`, Windows ACL `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`.
- **Default-off:** Java API only; `OfflineCandidateEntry --inventory` still runs RLDI1; no launcher/reader/runbook change.
- **Safety evaluation:** RLDI2 narrows the gap only for four exact upstream artifacts. It does not change who is trusted
  (whoever controls the inspector controls the policy), does not fence other copies, and is a snapshot. It is a
  candidate for independent review, not an adopted gate.

## 4. X-C2 — conclusions (all `OWNER_DECISION_REQUIRED`)

| Topic | Key facts | Provisional recommendation |
|---|---|---|
| RLDI2 | option A only one without new key infrastructure | adopt A as direction; independent review; decide on an additive `--inventory-rldi2` later |
| JNA 5.14.0 | dual `LGPL-2.1-or-later` / `Apache-2.0` (POM + JAR `META-INF/LICENSE`, AL2.0, LGPL2.1); ships native `jnidispatch`; RomLogger uses `jna` only, unmodified | stay "internal tests only"; if bundling later, Apache-2.0 option, separate unmodified JAR, expert check; no legal conclusion here |
| FILE_SHARE_DELETE | measured trade-off (RLWR1 §4); real writer never measured | provisional `READ_WRITE`; plan a non-car real-writer test |
| Windows ACL | not inspected anywhere | inspection scope + `OWNER_CONTROLLED/MISMATCH/UNKNOWN` criteria; read-only probe after JNA decision |
| Minimum Windows | API floor Vista/Server 2008; Azul's current Zulu 8 list omits Windows 10 (X270 = Windows 10); tested only on Windows 11 and CI server images | `MIN_WINDOWS=UNVERIFIED`; synthetic run on a non-car Windows 10 VM |

## 5. Remaining BLOCKED / UNKNOWN

RLDI2 not adopted; JNA distribution not allowed; reader_adoption=BLOCKED; FILE_SHARE_DELETE undecided; Windows ACL
UNKNOWN; minimum Windows UNVERIFIED; WRITER_UNPROVEN; global old-binary exclusion NOT_PROVEN; formal_ack=DISABLED.
NOT_RUN: JNA cases on Linux (no JNA JAR on ubuntu jobs), permission-denied JAR fixture, Windows 10, real writer, X270.

## 6. Proposed Phase X-D

1. Owner decisions in the dossier (RLDI2 direction, JNA form, share mode default, ACL principals, OS floor).
2. Independent review of `DistributionInventoryV2` (separate context), incl. the duplicated walk/TOCTOU code; consider
   refactoring RLDI1/RLDI2 onto one shared private walker only with an RLDI1 byte-identical-output regression.
3. If approved: additive entry flag `--inventory-rldi2` (RLDI1 stays default) and RLOP1 profile pin alignment.
4. Read-only Windows ACL probe candidate on private NTFS fixtures.
5. Synthetic suites on a non-car Windows 10 VM; Server progress doc refresh (stale #66 sentence).

## 7. Declaration

Unmerged (Draft), no release, synthetic-only; launch/production/ACK disabled; no Sender, ECU, X270, RomRaider, VPS,
Caddy or MCP contact; Server unchanged; `main` unchanged.

## 8. Independent review follow-up (2026-10-26 JST)

Review of #106 (ChatGPT, relayed by the owner) accepted the structure, the SHA-256 pin policy, the dependency
exceptions, RLDI1 non-interference and CI, with these items to resolve before integration:

| Item | Result |
|---|---|
| P2 `DistributionInventoryV2.Report.json()` escaping | **Confirmed and fixed.** Class names come from ZIP entry names; `"`/`\` are ZIP- and JVM-legal, so `unapproved_mains` could break the JSON and inject keys (e.g. a second `launch_authorized`). RED `8bb1d4c` (RED_COUNT 25) → fix `f7b59ba`: every string via `DistributionInventory.jsonString` (RFC 8259, printable ASCII). Negative test `PhaseXcJsonEscapeTest` (strict parser, exact keys, gates, exact round-trip) |
| Reject bad names instead? | Not added: such a main is already refused (`UNAPPROVED_MAIN_METHOD`) — escaping only fixes the report. Existing refusals kept: control characters / invalid UTF-8 in an entry name → `JAR_UNREADABLE` (tested) |
| RLDI1 impact | **Same defect, also in `OfflineCandidateEntry --inventory`** (nested raw). Fixed in this PR with an explicit scope: helper + `json()` only; inspection, findings, refusals, record format, `describe` unchanged; ordinary output byte-identical. Regression: phase8-inventory 70/71, xa-zip 45/45, xb-zip 26/26, phase9-race 23/23 (= Phase X-B) on x64 and actual x86. Other JSON embedded by the entry (IntegratedReadiness, Preflight) prints codes only (code reading) |
| Completeness | Contract v2 §"Responsibility boundary": RLDI2 checks what is present; a subset of pins is MATCHED. Dependency completeness, the per-profile required set, the launch class path and freshness belong to a future readiness axis (owner decision); until then completeness `UNKNOWN`, `reader_adoption=BLOCKED` |
| PR commit count | The body said 10; GitHub had 9. Corrected in the PR body to the GitHub count at the final head |

Gates unchanged; still Draft, not merged, no release; synthetic-only; launch/production/ACK disabled.
