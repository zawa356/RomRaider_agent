# Phase X-D — dependency readiness / Windows safety plan

Status: STARTED — DESIGN ONLY (2026-10-26 JST).
Start from merged Agent main bcc81566b2c90b61de88ec0fb7fb3bf43631e0f9; Server main 3f16a701fb6fe1b0f94780f8dfef1ec38515dd84. Refresh SHAs and CI before implementation. Phase X-C PR #106 was merged with a two-parent merge commit; Agent main had 4/4 successful workflows, 13/13 successful jobs (attempt 1):
- Phase VII-IX https://github.com/zawa356/RomRaider_agent/actions/runs/40275065445
- Isolated ingress https://github.com/zawa356/RomRaider_agent/actions/runs/40275065395
- Agent spool https://github.com/zawa356/RomRaider_agent/actions/runs/40275065452
- Isolated Server HTTP E2E https://github.com/zawa356/RomRaider_agent/actions/runs/40275065500

## Hard boundaries

Never touch real car, ECU, X270, live RomRaider/Logger, Sender, formal ACK, existing MCP/Caddy/VPS or production/deploy/release/tag. Do not stop existing services, migrate production data, use external production endpoints, or delete branches.
Always preserve synthetic_only=true, state=BLOCKED, launch_authorized=false, production_authorized=false, ack_eligible=false, formal_ack=DISABLED, reader_adoption=BLOCKED, global_old_binary_exclusion=NOT_PROVEN, WRITER_UNPROVEN. RLDI1 refusal policy does not change. RLDI2 remains default-off, Java API only and disconnected from CLI, launcher, Reader and Sender. No Ready/merge without explicit owner authorization.

## D0: verified baseline and documentation

Record exact HEADs, CI runs, historical fixes and NOT_RUN in append-only worklog/evidence. The X-C dossier statement that RLDI1 was byte-identical to main describes the early review snapshot; merged X-C fixed diagnostic JSON serialization in RLDI1. The source inspection and refusal policy were not relaxed. Stale Server #66 / old PR status should be corrected in a separate Server docs-only Draft PR.

## D1: dependency completeness (highest priority)

RLDI2 proves the integrity of files PRESENT in one install directory. RLDI2 MATCHED is not required-dependency completeness and not permission to run. Existing RLOP1 records include reader (POSIX or WINDOWS_NATIVE_TEST_ONLY), Java/runtime identity, exact ordered classpath and RLAP1 artifacts. RLIR1/preflight aggregate distinct axes; neither is process authentication.

Read actual Java implementation and profile creation code to derive profile-specific required pin IDs. Never invent expected profiles/dependencies; JNA is not automatically required for POSIX or an unadopted Windows reader. Write a separate versioned, read-only candidate contract with required_set, classpath, profile, provenance and freshness axes. Distinguish missing, extra, unknown, duplicate, modified, misordered, foreign/outside-root and stale dependencies, wrong reader/profile, classpath substitution and TOCTOU. Unknown information => UNKNOWN/BLOCKED, not MATCHED. Ensure bounded stable codes, strict JSON, no filesystem secrets. Do NOT wire into OfflineCandidateEntry, RLOP1 launch authorization, production/ACK or existing readers.

After review of design/threat matrix, a standalone synthetic-only default-off Java API and RED->GREEN negative fixture tests may be implemented. Keep RLDI1/RLDI2 policy and output unchanged. Any shared walker refactor is a separate high-risk review task.

## D2: Windows ACL — read-only design/fixture only

Inspect owner SID, DACL allow/deny/inheritance, group/effective rights, parent chain, directory replacement, file and record ACL, Java executable, JNA temp area, reparse points and TOCTOU on newly owned private NTFS fixtures. Ambiguous SID/ACE/privilege/group or inspection failure => UNKNOWN; never infer PASS from icacls text. SYSTEM/Admin trusted principals, root-to-volume parent scope and whether ACL can ever unlock a gate remain OWNER_DECISION_REQUIRED. No Windows ACL changes and no X270 checks.

## D3: platform, native runtime, writer

Plan non-car Windows 10 VM synthetic tests with actual Zulu8 x86 and NTFS; no suitable VM confirmed => NOT_RUN. Keep JNA 5.14.0 internal-test-only; no redistribution or legal selection. Keep READ_WRITE vs READ_WRITE_DELETE an unapproved comparison only. Actual RomRaider writer rotation measurement is a plan, not execution; WRITER_UNPROVEN remains. Linux JNA and permission-denied test gaps also remain explicit.

## Test/review/acceptance before any Phase X-D integration

- Linux JDK17; Windows JDK17 x64 and real Zulu8 x86 (assert data model=32).
- Positive exact-dependency fixture and negative missing/extra/unknown/duplicate/mutated/replaced/misordered/stale/foreign classpath, symlink, reparse and race fixtures.
- Strict parsed diagnostic JSON; no injected keys or true authorization flags.
- Prior Phase VII-IX, X-A/B/C, product spool and isolated actual Server E2E regression per new HEAD.
- Record concrete HEAD, OS/JVM, run/attempt, actual steps/job count and skipped/NOT_RUN without borrowing previous-head CI.
- Create Draft PR only. Distinct owner approval for every merge. Design completion is not product adoption.

References: docs/interfaces/distribution_inventory_v2.md, docs/interfaces/offline_candidate_entry_v1.md, docs/interfaces/integrated_readiness_v1.md, docs/interfaces/offline_candidate_preflight_v1.md, docs/decisions/phase_xc_owner_decisions.md, PR #106 independent review comment 6408600612.

## Status update by Claude Code (2026-10-26 JST, appended)

- D0: done on this track (current-state blocks in `AGENTS.md`, bootstrap, `docs/00_index.md`; reader-value drift fixed
  in `offline_candidate_entry_v1.md`; X-C dossier "byte-identical" note). Correction to this plan's D1 paragraph: RLOP1
  `reader` has **three** values (`POSIX`, `WINDOWS_NATIVE_TEST_ONLY`, `WINDOWS_NATIVE_V1_SYNTHETIC`), not two.
  Stale Server `implementation_progress.md` → separate Server docs-only Draft PR.
- D1: implemented as RLDR1 in a separate Draft PR #108 from `main` (code, tests, CI script, design, contract). This
  track keeps the worklog/evidence/decision record; the two PRs share no file.
- D2: read-only ACL PoC (test code) run; D3: Windows 10 NOT_RUN (no VM), JNA/FILE_SHARE_DELETE unchanged — see
  [owner decision record](../decisions/phase_xd_owner_decisions.md).
