# RLPC1 legacy v1 → RLI2 provenance classification and RLLV1 isolated staging (Phase IV, synthetic)

`net.neristal.rrbridge.prototype.ingress.LegacyProvenanceClassifier`. Classification is read-only. Staging writes only two NEW isolated files. Nothing here writes the v1 DB, fabricates an RLC2 physical source, frame, manifest or receipt, or connects a formal ACK.

## Why a classifier, not a converter

A v1 row holds wire JSON (dropped to `body_json=NULL` once ACKed), a canonical `body_sha256`, run/record IDs, and lane claims and cursors. An RLI2 record requires an RLC2 physical source UUID, committed frame bytes and offsets, an RLM1 manifest and a durable ledger receipt. None of these can be derived from v1. So a v1 row can only be **matched** against an original that actually exists, and is never converted.

## Mapping table (v1 → RMI1 inventory → RLI2)

| v1 table / column | RMI1 (Phase III) | RLI2 / RLPC1 use |
|---|---|---|
| `v1_source_claims(device,vehicle,session,source_kind,source_key)` | copied verbatim + `lane_migration_sources_v1` | run = `IncrementalTsvBridge.runId(device,session)` (the same deterministic converter). `legacy` → TSV lane, never RLC2. `journal` → `source_key` must be one of the original session's physical source UUIDs |
| `v1_journal_import_cursors(source_uuid, source_path, …, chain_sha256)` | copied | must exist for the claimed journal `source_uuid`. The cursor chain is RLC1-era and is **not** an RLC2 committed prefix |
| `v1_import_cursors` (TSV) | copied | evidence of the TSV lane only |
| `v1_records(record_id, run_id, …, body_sha256, body_json, acked, acked_batch_id, acked_at)` | copied (rowid preserved) | compared with the original re-projection by record ID and the v1 canonical hash `sha256(id|run|segment|sequence|kind|profile|captured_at|source|json)` |
| `v1_profiles` | copied | profile must exist; otherwise `UNKNOWN_OR_INCOMPLETE (PROFILE_MISSING)` |
| `v1_ack_receipts(batch_id, record_count, …)` | copied | every ACKed row needs its batch, and the batch count is not exceeded |
| `v1_delivery_state`, `v1_ingress_review_state` | copied | unchanged. Cutover readiness keeps treating them as review latches |
| RLI2 `preview_sources` / `preview_receipts` / `integration_admissions` | — | filled **only from the original RLC2 session and its durable ledger**, through the normal store backend |

## Categories (per v1 row; a session is VERIFIED only if all its rows are)

| Category | Rule (first match) |
|---|---|
| `CONFLICT` | `ACK_RECEIPT_MISSING`, `ACK_RECEIPT_COUNT_EXCEEDED`, `UNACKED_WITH_BATCH`, `SOURCE_UUID_DUPLICATED` / `ORIGINAL_DUPLICATED_FOR_RUN`, `TSV_CLAIM_WITH_RLC2_ORIGINAL`, `JOURNAL_CLAIM_NOT_AN_ORIGINAL_SOURCE` (also: no journal cursor for the claim), `SAME_RECORD_ID_DIFFERENT_BODY` |
| `UNKNOWN_OR_INCOMPLETE` | `PROFILE_MISSING`, `RUN_WITHOUT_SOURCE_CLAIM`, `NOT_IN_COMMITTED_ORIGINAL` (a v1 row beyond the committed original, for example an imported tail) |
| `LEGACY_WITHOUT_RLC2_PROVENANCE` | `TSV_LANE_HAS_NO_RLC2_ORIGINAL`, `NO_ORIGINAL_SUPPLIED` (including an original that failed verification: `ORIGINAL_UNVERIFIABLE` is reported) |
| `VERIFIED_ORIGINAL` | `ORIGINAL_REPROJECTED_EXACTLY`: the operator-supplied original (manifest + ledger verified by `Rlc2ProductAdapter.project`) yields the identical record ID and canonical hash, and the journal claim/cursor names one of its sources |

A row is **migratable** only if it is VERIFIED **and** its whole session is VERIFIED, so a single CONFLICT/UNKNOWN row blocks the run. An original that grew after the v1 import (later rotation) still verifies, because only v1 ⊆ original is required. Rows only in the original are not classified (they are not v1 data).

## Isolated staging (explicit opt-in, synthetic)

`stageIsolated(v1, originals, vault, rli2)` refuses existing targets and holds the exact legacy Worker guard for the v1 DB. Then it:

1. Classifies.
2. Creates the vault `RLLV1` (application_id `RLLV`, user_version 1) with `vault_meta(formal_ack=0)`, `vault_records` (every v1 row, its category and reason, preserved v1 ACK state `v1_acked/v1_acked_batch_id/v1_acked_at`, body as stored by v1, and `rli2_record` = migratable) and `vault_sessions`.
3. Creates a dedicated RLI2 candidate and ingests ONLY VERIFIED sessions from their originals. This uses the same transactional backend and binding/admission rows. The normal candidate entry refuses because v1 claims the session, which is exactly the migrated case.
4. Verifies read-only: v1 bytes unchanged; vault row count, ACK count and migratable count; every row's identity and ACK state; every migratable record present in RLI2 and no non-migratable record in RLI2.
5. Writes the completion marker `<vault>.rlpc1-staged` (CREATE_NEW), holding the v1, vault and RLI2 SHA-256 values.

Two DBs plus a marker are **not** one atomic commit. `stagingState` reports `NO_STAGING`, `INCOMPLETE_REVIEW_REQUIRED` (no marker), `STAGED_EVIDENCE_MISMATCH` (hashes differ) or `STAGED_VERIFIED_INACTIVE`. Rollback means the operator discards the new isolated files. The v1 DB and the originals are never modified, and nothing is activated.

## Preservation rules

Record IDs, run IDs and profile IDs are carried unchanged. v1 `local_id` is kept as `v1_local_id`. ACKed and unACKed state, the ACK batch and its time are copied into the vault as historical v1 facts and are **never** turned into an RLI2 or Server ACK. Retry and review state stay in the v1 DB and its RMI1 copy. The new path's ACK remains disabled.

## Acceptance (`LegacyProvenanceClassifierTest`, 33 checks)

Covered: journal session verified (including an ACKed row with a NULL body); TSV-only and journal-only rows without RLC2 (LEGACY); orphan run (UNKNOWN); same ID with a different body, missing or tampered ACK receipt, and a claim naming another source (CONFLICT); a v1 tail beyond the committed original (UNKNOWN, blocks the session); the same sources offered twice; a WAL sidecar (refused); an original without ledger evidence (verifies nothing); verification after a later rotation; deterministic replay; staging success, refusal to overwrite, an interruption (INCOMPLETE), a CONFLICT session kept vault-only, and a tampered staged vault (MISMATCH). v1 bytes are unchanged throughout.

**NOT RUN** for RLPC1: a child-JVM halt during staging, a real DB lock held by another process (WAL presence is the tested proxy), restoring from another root, and an original with a deliberately different JSON than its frames (the reader rejects frame/marker mismatches before RLPC1 sees them).
