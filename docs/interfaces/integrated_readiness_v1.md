# Integrated readiness v1 (RLIR1) — Phase VII-2, read-only

`IntegratedReadiness.inspect(enable, layout, expectedGeneration, expectedManifestSha, admission, classpath, approvedRLAP1, originals, vault, stagedRli2, reader, hook)` joins, in one read-only call, the [offline preflight](offline_candidate_preflight_v1.md), the [RLPC1 classification](legacy_v1_rli2_provenance_v1.md) of the legacy v1 DB, the RLLV1 Vault and the staged RLI2 candidate. It never grants launch, production or ACK: `state=BLOCKED`, `launch_authorized=false`, `production_authorized=false`, `ack_eligible=false` in every result.

## Axes

Each axis is `{status, code, next}`; `status` ∈ `PASS`, `MISMATCH`, `UNKNOWN`, `BLOCKED`, `NOT_RUN`, `DISABLED`. An axis holds only what this call actually executed; nothing is copied from earlier runs or other tools.

| Axis | PASS means (this call) | Typical non-PASS codes |
|---|---|---|
| `deployment_manifest` | active RLDB/RLDT generation = expected and manifest bytes = expected SHA-256 | `DEPLOYMENT_GENERATION_MISMATCH`, `MANIFEST_EXPECTATION_MISMATCH`, layout codes |
| `identity_admission` | projection's physical Source UUIDs = explicit admission; no duplicated original/Source/claim collision | `SOURCE_ADMISSION_MISMATCH`, `ORIGINAL_DUPLICATED_FOR_RUN`, `SOURCE_UUID_DUPLICATED`, `CLAIM_OWNER_MISMATCH` |
| `cooperative_locks` | existing Worker/Lane/Publication locks observed idle (shared, read-only) | `LEGACY_WORKER_BUSY`, `LANE_NOT_OBSERVED_IDLE`, `PUBLICATION_NOT_OBSERVED_IDLE` (BLOCKED) |
| `artifact_provenance` | ordered JARs/runtime match the approved `OfflineCandidateEntry` description | RLAP1 codes |
| `cursor_provenance` | every v1 claim/TSV/journal cursor (owner, path, sequence, first/chain hash, snapshot) matches the originals | `CURSOR_*`, `ORPHAN_CURSOR`, `ORIGINAL_UNVERIFIABLE` (UNKNOWN), `LEGACY_RECORDS_INCOMPLETE` |
| `legacy_ack_set` | the set {record ID, body hash, batch, time} of v1 ACKed records equals the Vault's ACKed rows | `V1_ACK_SET_DIFFERS` |
| `vault_integrity` | Vault integrity/schema/application ID and **all columns of all rows** exact | `VAULT_ROWS_DIFFER`, `VAULT_SCHEMA`, … |
| `staged_rli2_integrity` | RLI2 schema, foreign keys, integrity check | `RLI2_SCHEMA_OR_INTEGRITY_DIFFERS` |
| `record_receipt_admission` | exact typed row sets of all eight RLI2 tables (sessions, claims, sources, receipts, profiles, records, bindings, admissions) | `RLI2_ROWS_DIFFER_<TABLE>` |
| `staging_generation` | completion marker matches Vault/RLI2 hashes and was made from the current v1 bytes | `INCOMPLETE_REVIEW_REQUIRED` (BLOCKED), `STAGED_EVIDENCE_MISMATCH`, `STAGED_FOR_DIFFERENT_V1` |
| `writer_quiescence` | never PASS | `WRITER_UNPROVEN` (BLOCKED) |
| `process_provenance` | never PASS | `UNKNOWN_PROCESS_PROVENANCE` |
| `directory_durability` | never PASS | `DIRECTORY_DURABILITY_UNKNOWN` |
| `formal_ack`, `production_send` | DISABLED | — |

`content_axes_complete=true` only when the ten content axes above are all PASS in this call. It is an input to human review, never an authorization. `next` names the human step (e.g. `RESTAGE_FROM_CURRENT_V1`, `REVIEW_LEGACY_CURSOR_EVIDENCE`, `STOP_COOPERATIVE_HOLDER_THEN_RETRY`, `PROVIDE_NONCOOPERATIVE_WRITER_EXCLUSION_EVIDENCE`).

## Read-only and stability rules

- SQLite inputs open `mode=ro&immutable=1` and only if no `-wal/-shm/-journal` exists; nothing is created, checkpointed or repaired.
- Before the first check the call captures bytes + native physical identity of legacy v1, ledger, manifest, Vault, completion marker and staged RLI2, the staging parent directory identity and the original session snapshot. These are re-verified after each stage (`PREFLIGHT_DONE`, `CLASSIFIED`, `VAULT_CHECKED`, `STAGED_CHECKED`). Any change, a new sidecar or a same-name parent replacement stops with `INPUT_CHANGED_DURING_DIAGNOSIS`.
- Missing required evidence fails closed (`REQUIRED_EVIDENCE_MISSING`, `STAGED_EVIDENCE_MISSING`); nothing is generated to fill the gap.
- A failure in one axis does not hide the others: preflight refusal still lets classification and staged checks run and report (fixes review F2's collapsed reason for this aggregate).
- JSON contains codes, statuses and counts only — no paths, IDs or payload.

`LegacyProvenanceClassifier.expectedStagedRows(report)` is an additive package-private helper returning the same eight-table expectation `verifyStaged` uses. No storage, wire, ACK or Worker change.
