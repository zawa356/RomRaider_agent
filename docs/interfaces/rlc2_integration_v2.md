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
