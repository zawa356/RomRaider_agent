# RLPC1 legacy v1 → RLI2 provenance classification and RLLV1 isolated staging (Phase IV, synthetic)

`net.neristal.rrbridge.prototype.ingress.LegacyProvenanceClassifier`. Classification is read-only. Staging writes new files inside an exclusively claimed private work directory and publishes two NEW isolated outputs without overwriting final names. Nothing here writes the v1 DB, fabricates an RLC2 physical source, frame, manifest or receipt, or connects a formal ACK.

## Why a classifier, not a converter

A v1 row holds wire JSON (dropped to `body_json=NULL` once ACKed), a canonical `body_sha256`, run/record IDs, and lane claims and cursors. An RLI2 record requires an RLC2 physical source UUID, committed frame bytes and offsets, an RLM1 manifest and a durable ledger receipt. None of these can be derived from v1. So a v1 row can only be **matched** against an original that actually exists, and is never converted.

## Mapping table (v1 → RMI1 inventory → RLI2)

| v1 table / column | RMI1 (Phase III) | RLI2 / RLPC1 use |
|---|---|---|
| `v1_source_claims(device,vehicle,session,source_kind,source_key)` | copied verbatim + `lane_migration_sources_v1` | run = `IncrementalTsvBridge.runId(device,session)` (the same deterministic converter). `legacy` → TSV lane, never RLC2. `journal` → `source_key` must be one of the original session's physical source UUIDs |
| `v1_journal_import_cursors(source_uuid, source_path, …, chain_sha256)` | copied | exactly one full cursor must match the claim and current physical source; owner/path/integer sequence/first hash/RLC1 chain/metadata snapshot are independently checked. The chain is **not** an RLC2 durable committed prefix |
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

## Phase V cursor and collision rules

Cursor owner (device, vehicle, logical session) must equal both the claim and the reprojected original. Comparison does not silently normalize paths: the stored path must exactly equal the original physical source path, be absolute/normalized and pass the existing deployment path checks (real spelling, no symbolic link/junction/reparse component, single hardlink). Unknown inspection refuses. A copied/replaced RLC2 original must independently pass its manifest/physical-marker identity checks. Existing run IDs intentionally derive from device + session, not vehicle; conflicting vehicle claims for that same run are `CLAIM_OWNER_MISMATCH`/`CLAIM_RUN_COLLISION`, never silently overwritten.

The cursor sequence must have SQLite INTEGER storage, be >=1 and within the claimed source's committed frame extent. SHA256 first-row and the RLC1 `START` chain are recomputed from the source's actual prefix payloads, using the original per-source frame sequence and UTF-8 byte lengths. The pure legacy converter replays that prefix to independently compare its exact encoded metadata state. Malformed, incomparable or mismatched snapshots fail closed (`CURSOR_SNAPSHOT_MISMATCH`), not VERIFIED. This is payload correspondence only: no RLC1 fileKey hash or cursor is promoted into an RLC2 physical identity, highwater or receipt. A later source without sufficient legacy metadata to replay independently cannot prove this correspondence.

Reasons include `CURSOR_MISSING`, `CURSOR_UUID_DUPLICATED`, `CURSOR_OWNER_MISMATCH`, `CURSOR_PATH_MISMATCH`, `CURSOR_SEQUENCE_INVALID`, `CURSOR_FIRST_HASH_MISMATCH`, `CURSOR_CHAIN_MISMATCH`, `CURSOR_SNAPSHOT_MISMATCH`, `ORPHAN_CURSOR`. Every cursor in a claimed run is accounted for; unknown extra source cursors block that run. Reuse of one source UUID across supplied runs invalidates BOTH runs, in either input order. A duplicate original for the same run is also a conflict. Whole-session verdict gates every row, including a row that independently matched.

## Isolated staging (explicit opt-in, synthetic)

`stageIsolated(v1, originals, vault, rli2)` holds the exact legacy Worker guard and requires two distinct new target names in the same existing private local parent. It refuses source/ledger/session overlap, existing final targets/marker/work directory and unsafe aliases/ACLs. It then:

1. Classifies and freezes each valid original projection, with a bounded input snapshot covering original files, ledger and v1. Legitimate original growth BEFORE classification is allowed; any change through staging or verification refuses. No later re-projection silently adds records.
2. Atomically claims `<vault>.rlpc1-work` with CREATE_NEW directory semantics. Internal Vault/RLI2 files are CREATE_NEW; SQLite writes only these private files. The directory remains as evidence after success or failure. It is never adopted on retry.
3. Builds RLLV1 (`RLLV` application_id, user_version1): exact `vault_meta(version=1,v1_sha256,formal_ack=0)`, every v1 record column and session verdict. Historical ACKs are evidence, never new formal ACKs.
4. Builds RLI2 from ONLY the frozen VERIFIED sessions, using real original receipts and the existing backend. Binding/admission insertions also require exact retained values; UUID re-admission to another session refuses transactionally.
5. Forces each completed file and publishes its final name through atomic no-replace hardlink insertion; only the owned internal name is removed. Existing competitors are never opened writable. Unsupported publication primitives fail closed. A halt between link and internal-name removal leaves a multi-link INCOMPLETE artifact, not an approved output.
6. Independently verifies read-only: unchanged v1/input snapshots; every typed Vault column (including NULL, body, ACK time, reason, run and RLI2 flag), exact sessions/meta, full schema objects (unknown trigger/view refused), application/version, quick/integrity/FK checks. Independently maps the frozen projections to all eight exact RLI2 table row sets: records, profiles, receipts, sources/cursors, sessions/manifests, claims, bindings and admissions. Extra or missing rows/content/storage types refuse; no subset comparison or repair.
7. Writes `<vault>.rlpc1-staged` CREATE_NEW last, recording v1/Vault/RLI2 SHA256. Output hashes are fixed across independent verification. The completion marker is local evidence only and has no signature or production authority.

Two DBs plus a marker are **not** one atomic commit. `stagingState` reports `NO_STAGING`, `INCOMPLETE_REVIEW_REQUIRED` (including a retained work directory without a completion marker), `STAGED_EVIDENCE_MISMATCH` or `STAGED_LOCAL_HASHES_MATCH_INACTIVE`. The last means only that completed local files match their local marker. It does NOT prove current original provenance, historical authenticity, full restoration, or permission to use a candidate. Re-run independent verification with current originals and retain all safety gates. An actor who rewrites files and hashes consistently is outside this local checksum guarantee. No automatic rollback, cleanup or recovery is provided.

Atomic CREATE_NEW/private-directory checks exclude accidental competing initializers and other unprivileged writers. They do not fence a hostile same-user/admin process replacing all evidence. External quiescence remains required. Directory durability on Windows is UNKNOWN.

## Preservation rules

Record IDs, run IDs and profile IDs are carried unchanged. v1 `local_id` is kept as `v1_local_id`. ACKed and unACKed state, the ACK batch and its time are copied into the vault as historical v1 facts and are **never** turned into an RLI2 or Server ACK. Retry and review state stay in the v1 DB and its RMI1 copy. The new path's ACK remains disabled.

## Acceptance (Phase IV regression plus `Phase5ProvenanceTest`)

Covered: journal session verified (including an ACKed row with a NULL body); TSV-only and journal-only rows without RLC2 (LEGACY); orphan run (UNKNOWN); same ID with a different body, missing or tampered ACK receipt, and a claim naming another source (CONFLICT); a v1 tail beyond the committed original (UNKNOWN, blocks the session); the same sources offered twice; a WAL sidecar (refused); an original without ledger evidence (verifies nothing); verification after a later rotation; deterministic replay; staging success, refusal to overwrite, an interruption (INCOMPLETE), a CONFLICT session kept vault-only, and a tampered staged vault (MISMATCH). v1 bytes are unchanged throughout.

Phase V adds full-cursor negative cases, both-run UUID reuse, claim run collisions, all Vault columns/meta/session/schema mutations, exact RLI2 extra/missing/content/schema cases, three publication competitors and four actual child-JVM Runtime.halt boundaries. See Phase V evidence for exact tested heads/platforms. Runtime.halt is not physical power loss.

RLLV1 is NOT a complete v1 database backup: it does not contain `v1_profiles`, `v1_ack_receipts`, import cursor tables, delivery or review state. RMI1 and the unchanged v1 original retain that complete inventory. No Vault-only full-restoration claim is made. Formal adoption of RLLV1 as a product storage version remains an unapproved proposal.

Still NOT RUN unless recorded otherwise in Phase V evidence: real SQLite write-lock contention during RLPC1 reads, privileged Windows symlinks, external hostile directory replacement, physical power loss and real ENOSPC. Restored roots remain inactive and no global fencing is claimed.

Phase V physical snapshot verification also re-opens every original source through the original RLC2 reader and compares the captured marker. This revalidates physical identity on Windows, where BasicFileAttributes.fileKey is null: byte-identical copy/replacement must not pass just because file hashes match. A dedicated Windows RED fixture reproduced this and is included in `scripts/test-phase5.sh`.
