# Phase VIII report — product-integration preparation (Claude Code, 2026-10-20)

Fresh-session reading order: [Bootstrap (Phase VII top)](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/ops/development_environment_bootstrap.md)
→ [Phase VII report](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_report.md)
→ [Phase VII review closure report](https://github.com/zawa356/RomRaider_agent/blob/phase7/review-closure/docs/handoff/phase7_review_closure_report.md)
→ this report → [Phase VIII worklog](phase8_worklog.md) (exact-head results at the end)
→ specs: [RLDI1](../interfaces/distribution_inventory_v1.md), [RLIR1 schema](../interfaces/integrated_readiness_v1.schema.json),
[RLMS1](../interfaces/offline_multi_session_v1.md), [Windows reader design](../design/windows_product_reader_v1_design.md).

## Branches / PRs (all draft, unmerged; `main` unchanged at 4f80c5e)

| PR | branch | base | content |
|---|---|---|---|
| [#92](https://github.com/zawa356/RomRaider_agent/pull/92) | `phase7/review-closure` | `phase7/adversarial` (#91) | VII-C2 RED `36bb721` + fix `2ccae84`, F2 cause, fileSha bound, closure report/evidence |
| [#93](https://github.com/zawa356/RomRaider_agent/pull/93) | `phase8/distribution-inventory` | #92 | VIII-1 RLDI1 + entry `--inventory` (`991e5e5`) |
| [#94](https://github.com/zawa356/RomRaider_agent/pull/94) | `phase8/readiness-cli` | #93 | VIII-3 RLIR1 schema + entry `--readiness` (`0cbd18f`) |
| [#95](https://github.com/zawa356/RomRaider_agent/pull/95) | `phase8/multi-session` | #94 | VIII-2 RLMS1 10-session test (`8b8a0f0`), VIII-4 design, worklog/report |

**Phase VIII runtime/tests head: `8b8a0f0`.** Later commits are documentation only. Server: no change by this session
(Server docs PR [RomRaiderServer#64](https://github.com/zawa356/RomRaiderServer/pull/64) on #63 was made by the
Phase VII session; Server runtime/schema/wire unchanged at d8c7a2f).

## Results by classification

| Item | Classification | Basis |
|---|---|---|
| VII-C2 claims/cursors without records | DEFECT CONFIRMED → FIXED, IMPLEMENTED_AND_TESTED at 2ccae84 | RED 7/9 at d93a975; GREEN Windows x64, actual x86, Linux |
| F2 uncoded preflight cause | IMPLEMENTED_AND_TESTED (code-only `inspection_cause`); end-to-end reader code lost by adapter = documented limit | 2ccae84 |
| fileSha bound while reading | IMPLEMENTED_AND_TESTED | 2ccae84 |
| VIII-1 RLDI1 inventory, entry `--inventory` | IMPLEMENTED_AND_TESTED | 8b8a0f0 Windows x64, actual x86, Linux (worklog) |
| VIII-1 real entry package inventory-clean | **BLOCKED at 8b8a0f0** (historical; see timeline below: MATCHED since 95a4a30) | four secondary `main` classes in the closure JAR → MISMATCH |
| VIII-1 old-binary exclusion beyond one directory | NOT PROVEN (by design) | copies elsewhere / other users / hand-started JVMs out of reach; D2 blocker |
| VIII-1 Windows ACL inspection | UNKNOWN | not inspected |
| VIII-2 ≥10 sessions through the entry, restart 0 dup, cross-session refusals, concurrency | IMPLEMENTED_AND_TESTED | 8b8a0f0 Windows x64, actual x86, Linux |
| VIII-2 several sessions in one store / one root | NOT_RUN | RLDB1 single-session; needs new binding version |
| VIII-3 RLIR1 schema + entry `--readiness` | IMPLEMENTED_AND_TESTED | 8b8a0f0 Windows x64, actual x86, Linux |
| VIII-3 continuous report generation (scheduler) | NOT_RUN | CLI is ready; no scheduler wired (operator/ops decision) |
| VIII-4 Windows product reader | DESIGN ONLY | nothing wired; open owner decisions listed |
| Writer quiescence / process provenance / directory durability | WRITER_UNPROVEN / UNKNOWN | unchanged |
| Production, Sender, formal ACK, migration, ECU/X270 | NOT_RUN (disabled) | out of scope |
| GitHub Actions | NOT_RUN | jobs `steps=0` |

## Next safe gates (D2)

1. Split the four secondary `main` CLIs out of the entry closure (separate tool JARs) so an RLDI1 install can be MATCHED.
2. Decide the isolated install layout operationally (owner, quarantine of old binaries, Windows ACL evidence).
3. Product Windows reader (RLWR1) per the design, with independent acceptance on x64 and actual x86.
4. A multi-session binding version if one store must hold several sessions.
5. Only then, and only with explicit authorization: any path toward a real Sender/formal ACK.

Do not merge any PR while CI has not executed. Keep launch/production/ACK disabled.

## Timeline update — added 2026-10-21 by Phase IX (R9-02); rows above are kept as written at 8b8a0f0

| Head | Entry closure RLDI1 | Source |
|---|---|---|
| `8b8a0f0` (#95, Phase VIII) | **MISMATCH** — four secondary `main` (ArtifactProvenance, DeploymentBinding, DeploymentTransition, IngressDiagnostics) | this report, worklog Phase VIII results |
| `95a4a30` (#96, VIII-1b runtime/tests) | **MATCHED**, `unapproved_mains=[]`, `findings={}`; still `state=BLOCKED`, `launch_authorized=false` | [phase8_worklog.md](phase8_worklog.md) "Phase VIII-1b" (Windows x64 + actual x86 exit0) |
| `ab32e6b5` (#96 head) | unchanged from 95a4a30 (documentation only) | same |
| Phase IX `phase9/cli-review-closure` | MATCHED kept; the split-out CLIs now have an explicit separate tool JAR build and real process tests (R9-01) | [phase9_worklog.md](phase9_worklog.md), [phase9 evidence](phase9_validation_evidence.md) |

Next safe gate 1 of this report ("split the four secondary main CLIs out") was closed by #96 for the **one inventoried
directory** only. Still true at every head: MATCHED is not launch/production/ACK authorization;
`global_old_binary_exclusion=NOT_PROVEN`; Windows ACL `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`; production AUTH disabled.
At 95a4a30 the x64 multisession first attempt failed (exit1, `CreateProcess error=267`) and the same-head rerun passed;
both remain recorded. Phase IX analysed that failure (R9-03) — see the Phase IX worklog.
