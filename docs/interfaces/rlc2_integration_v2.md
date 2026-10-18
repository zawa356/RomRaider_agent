# RLC2 product integration contract v2 (candidate, default disabled)

This is Agent-local storage contract v2, migration revision 1. HTTP remains Server–Agent wire v1. No production migration, sender activation or ACK state change is implied.

| Identity | Owner / invariant |
|---|---|
| logical payload session | unique device/vehicle/session claim; maps to one immutable manifest-session UUID and canonical directory |
| manifest session UUID | owns all physical child sources, ordered by segment index; persisted raw manifest history must extend monotonically |
| physical source UUID | owns one original canonical path/native identity and independent frame seq/offset/hash highwater; never moved/readopted |
| run UUID / record UUID | existing v1 deterministic converter, based on payload session/device and sample sequence or logical TSV event ordinal; physical source UUID does not change them |
| frame receipt UUID | source UUID + physical frame sequence; original bytes/hash/offset remain immutable |
| Server ACK record IDs | separate from source cursors and receipts; integration candidate never marks any ACK |

An explicit admission contains the expected canonical directory, manifest-session UUID and approved source UUID set. A new rotation source must be present in a verified extending manifest AND explicitly admitted. Orphans are never adopted. Duplicate logical session, moved/copied directories, UUID reuse, reverted manifest/highwater and differing same-ID payloads fail closed.

## SQLite migration revision 1

`Rlc2IntegrationSchema.initialize(true, dedicated.rlc2-integration.db)` creates only a new dedicated file: application_id RLI2 (0x524c4932), user_version=1. Shared transaction backend tables retain their preview_* names and semantics: sessions, claims, physical sources/cursors, original receipts, profiles, records. New integration_bindings binds manifest session to canonical directory/run and contract_version=2; integration_admissions binds explicitly approved child sources. No v1_* tables, ACK state or Worker wiring exist in this file.

Initialization is one BEGIN IMMEDIATE / FULL transaction; precommit failure rolls back all DDL and version updates. Existing correct files are read-only verified, unsupported versions/renamed product DBs are refused, never upgraded implicitly. A failed new empty file requires manual review rather than automatic adoption. Existing preview-v0 files remain separate and valid; they are not renamed or silently migrated. Detailed structural verification is strengthened in I-4.

## Legacy compatibility and coexistence gate

The old v1 schema and its single-source entries remain unchanged. A closed, checkpointed v1 inventory can be inspected read-only: source claims, old journal cursors, TSV cursor file names, and historical run records all block a conflicting candidate claim. Missing/unknown legacy schema also fails closed. Existing ACK/unacked records remain byte-for-byte unchanged.

This is an OFFLINE/quiescent integration contract. The existing old Worker does not understand contract v2, so live cross-database mutual exclusion is **BLOCKED**; inventory alone is not a lock against future old-Worker activity. The caller must stop all producers, manifest/ledger updaters and legacy importers for the entire snapshot-to-commit interval. No automatic switch of existing Worker is made. New candidate APIs must require explicit quiescence and legacy inventory input. Later production adoption requires a coordinated versioned lane migration/lock contract and separate approval for affected running DBs.

## Acceptance

Real SQLite creation/reexecution/rollback, old v1 byte preservation and lane rejection are executable in Rlc2IntegrationSchemaTest. I-2 reuses the existing transaction engine and shared converter rather than creating an alternate importer. I-3 binds the immutable projection to input snapshot evidence and repeats checks before commit, while retaining the external quiescence requirement. Windows directory durability UNKNOWN, no physical-power-loss guarantee, no original repair/delete.

## I-2 atomic candidate backend

Rlc2IntegrationStore requires explicit enable, externallyQuiescent=true, exact canonical directory/manifest UUID and exact admitted source UUID set, closed ledger and a closed legacy v1 inventory. It projects through Rlc2ProductAdapter and invokes the same Rlc2PreviewStore transactional backend using the isolated integration format. A transaction guard validates prior admissions before writes and commits bindings/admissions alongside all claims/cursors/receipts/profiles/records. Removed admissions are not reconstructed. Existing preview API/default behavior stays separate.

The candidate remains a read/convert/store entry point only, not a sender or Worker replacement. Two workers serialize through SQLite BEGIN IMMEDIATE, preserving whole-set replay. External quiescence is mandatory; the next snapshot phase strengthens change detection around the commit interval without claiming filesystem/SQLite atomicity against a non-cooperating writer.

## I-3 snapshot evidence boundary

Before projection, the candidate captures bounded streaming SHA256/file identity evidence for the immediate session files plus closed ledger/legacy DB; output must be outside those inputs. Evidence is rechecked after projection, under the output write transaction before writes, and immediately before commit after the fault hook. Source/marker publication, manifest seal, ledger-only changes, legacy DB changes and unpublished tail writes invalidate the transaction. Fixed limits:160 directory entries,128MiB/file,256MiB aggregate; hashing uses32KiB buffers.

Lock order: external quiescence first (caller responsibility), read-only input verification/projection, then output BEGIN IMMEDIATE, read-only bounded rechecks, commit. Reader acquires no writer/publisher/manifest lock and never blocks waiting to stop a writer. No new deadlock cycle is introduced. Two output workers serialize or fail closed. This is change detection, not an atomic snapshot proof: a non-cooperating writer after the final check remains outside contract. Live-source ingestion and legacy coexistence remain blocked. Physical source identity continues to use the existing native/POSIX rules, not the generic fingerprint key.

## I-7 explicit CLI / one-command verification

Linux after scripts/test-rlc2-preview.sh:

    java -cp 'build/rlc2-preview:lib/*' net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationCli --enable-synthetic-integration --quiescent SESSION_DIR LEDGER_DB LEGACY_V1_DB DEVICE_UUID VEHICLE_UUID OUTPUT.rlc2-integration.db MANIFEST_SESSION_UUID SOURCE_UUID_1,SOURCE_UUID_2

Both switches and the exact approved manifest/source UUID set are required; empty/unknown arguments do not auto-discover/adopt sources. Quiescent is a caller assertion for the entire operation, not an automatic stop command. Output stays outside input directories. Exit0 means stored offline only; records_added/receipts_added/ack_eligible=false are fixed structured fields, not send authorization. Invalid invocation2, review5, no exception/row/path disclosure. On Windows replace the class with WindowsRlc2IntegrationCli and use ';' classpath plus build/identity-deps/jna-5.14.0.jar; this remains a test-only native wrapper.

Windows PowerShell: scripts/run-acceleration-tests.ps1 -Postgres -RealServerHttp -Python <Server-venv-python> -ServerPath <actual-local-Server-checkout>. Provide only the existing disposable loopback DATABASE_URL via protected process environment; never paste DSN into command history. Without the optional HTTP switch, HTTP is explicitly NOT RUN. The runner starts its own loopback sockets/private PG schema, exercises actual Server code and drops only its own schema; it never stops shared PostgreSQL. Endpoint-unavailable503 is tested using an owned non-listening loopback port, not by stopping the shared PG cluster.

Candidate PostgreSQL schema is an isolated migration mirror of the actual SQLite DDL: BYTEA for BLOB, BIGINT for SQLite signed64 INTEGER and an explicit revision row. Interrupted DDL rolls back; two migrations are idempotent; a2^40 generation probe verifies width. This is not an installed Server migration or a general-purpose live database adapter.
