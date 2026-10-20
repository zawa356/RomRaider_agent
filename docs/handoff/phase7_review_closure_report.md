# Phase VII review closure report (Claude Code, 2026-10-20)

Entry for a fresh session: [Bootstrap (Phase VII top branch)](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/ops/development_environment_bootstrap.md)
→ [Phase VII report](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_report.md)
→ [Phase VII evidence](https://github.com/zawa356/RomRaider_agent/blob/phase7/adversarial/docs/handoff/phase7_validation_evidence.md)
→ this report → [closure evidence](phase7_review_closure_evidence.md) → [Phase VIII worklog](https://github.com/zawa356/RomRaider_agent/blob/phase8/multi-session/docs/handoff/phase8_worklog.md).
Nothing here is merged; `main` contains none of Phase V–VIII.

## Who did what (two Claude Code sessions on the same VM)

| Item | Owner | Where |
|---|---|---|
| VII-C0 final-head evidence (d93a975 Windows x64 / actual Zulu8 x86 / Linux, HTTP35 + 10k on x64; 67657d9 load FAIL kept) | Phase VII implementation session | PR #91 `phase7/adversarial` (e4dc146, 01cd886) |
| VII-C1 Server progress docs | Phase VII implementation session | [RomRaiderServer#64](https://github.com/zawa356/RomRaiderServer/pull/64), stacked on #63; Server runtime unchanged |
| VII-C2 RED/fix, F2 cause, fileSha bound, closure evidence on 2ccae84 | this session | `phase7/review-closure` (stacked on #91) |
| Phase VIII-1/3/2 | this session | `phase8/distribution-inventory` → `phase8/readiness-cli` → `phase8/multi-session` |

Facts re-fetched at start (2026-10-20 ~15:50 JST): Agent main `4f80c5e`, Server main `d8c7a2f`, PR #80–#91 open and
stacked exactly (`git merge-base --is-ancestor` along the chain OK), #91 head `d93a975`; Server #62/#63 open and
`phase7/agent-entry-progress` not published at that time. Actions runs at d93a975 (39380478331/…411/…393): every job
`steps=0` → **CI NOT RUN**.

## VII-C2 — confirmed defect, fixed

Hypothesis from the instruction: `IntegratedReadiness` PASSes `cursor_provenance` as `NO_LEGACY_V1_RECORDS` when
`v1_records` is empty although claims/cursors remain.

- **Can such a DB exist?** Yes. `DurableSpool` creates `v1_source_claims` and `v1_journal_import_cursors` with no
  foreign key or trigger to `v1_records`; nothing in the schema forbids claims/cursors without records.
- **Root cause.** `LegacyProvenanceClassifier.classify` recorded run-level faults (`CURSOR_*`, `ORPHAN_CURSOR`,
  claim/source collisions) in `brokenRun`, but projected them into `conflicts` only through record rows. With zero rows
  they vanished; cursors whose run had no claim were never reported. Readiness then took the `rows.isEmpty()` branch.
- **RED** (`36bb721`, test only, at d93a975 product code): 7 RED / 9 PASS. All four shapes (claim+valid cursor,
  tampered cursor, orphan cursor, claim only) reported `PASS NO_LEGACY_V1_RECORDS`; with a tampered or orphan cursor and
  staging made from that v1, **`content_axes_complete=true`**. Overall readiness stayed `BLOCKED` (no launch/ACK).
- **Fix** (`2ccae84`): classifier reports run-level faults of zero-row runs directly and flags cursors without a claim
  as `ORPHAN_CURSOR`; readiness treats any conflict as `MISMATCH` and claimed runs without records as
  `UNKNOWN LEGACY_CLAIMS_WITHOUT_RECORDS`. `NO_LEGACY_V1_RECORDS` now requires no records, claims or cursors.
  GREEN 17 checks (control + empty-v1 control unchanged).
- Classification of VII-2 integrated readiness: IMPLEMENTED_AND_TESTED **at 2ccae84** (not at d93a975).

## F2 and fileSha re-review

- **F2** (`OfflineCandidatePreflight` collapsed uncoded failures into `INSPECTION_UNAVAILABLE`): kept the reason, added
  `inspection_cause` = first stable code in the cause chain, else the innermost exception class; never free text or
  paths. Limit found and documented: a reader failure is absorbed by `IngressDiagnostics` and re-thrown by
  `Rlc2ProductAdapter` as free text without a cause, so end to end only `IOException` survives. Changing that adapter
  message contract is deferred (it is used elsewhere).
- **`DeploymentTransition.fileSha`**: the 128 MiB bound was checked only on the pre-read length; a file growing during
  hashing was read unbounded. Now enforced on bytes read (`boundedSha`), deterministic test with a synthetic stream.

## Results (details and SHA256 in the evidence file)

See [phase7_review_closure_evidence.md](phase7_review_closure_evidence.md). Summary of what ran at **2ccae84**:
Windows JDK17 x64, actual Zulu8 x86 and Linux for the closure suites, all Phase VII suites and the Phase VI/V/IV
regressions; owned PG16 + actual FastAPI HTTP35 and the 10,000-observation load on Windows x64.

## Known limits (unchanged, not hidden)

Old binaries (`Rlc2IntegrationCli`, `ProductBridgeWorker/Once`, …) remain callable; the historical F1 RED still exits 1
against the old CLI by design. WRITER_UNPROVEN; Windows process provenance and directory durability UNKNOWN; no real
SMB/subst/power loss/ENOSPC; no production, Sender, formal ACK, ECU/X270. GitHub Actions: NOT RUN (`steps=0`).
Do not merge while CI has not executed.
