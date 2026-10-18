# RLC2 isolated preview persistence and E2E (acceleration Phase D)

This is an executable synthetic integration path, **not a product sender or Server ACK path**:

1. Generate synthetic journal segments with the existing platform-owned test writer.
2. Publish RLC2 committed prefixes and persist exact highwater/original receipts in SQLite.
3. Validate RLM1 rotation, identity, lane/source claims and receipt consistency.
4. Project committed rows with the shared product v1 converter (Phase C).
5. Store the projection atomically in a dedicated SQLite preview database.
6. Reopen/reproject in a separate JVM and verify no duplicates or silent repairs.
7. Optionally validate actual Server request models locally, then verify source/receipt/profile/
   record equality and replay in a temporary PostgreSQL schema. No HTTP endpoint is called.

## Preview store boundaries

`Rlc2PreviewStore(true, output)` requires an existing parent, a `.rlc2-preview.db` filename,
no symlink ancestors, SQLite application ID `RLP1` on existing databases, and exactly the six
owned tables. Foreign/renamed product DBs are refused before writable access. Input ledgers
must remain closed/checkpointed. Owned preview output may use SQLite-managed rollback after
a killed transaction, subject to the additional ownership checks below. No v1 table is
created, and existing product Worker/configuration does not reference these tables.

| Table | Purpose |
| --- | --- |
| `preview_sessions` | Device/vehicle/payload ownership, raw RLM1 history/generation, saved row counts |
| `preview_claims` | One manifest-session ownership claim per device/vehicle/payload session, preview lane |
| `preview_sources` | Child source UUID/path/index/expected identity, exact highwater and immutable sealed state |
| `preview_receipts` | Exact original body, hash, offset and stable receipt ID per source/frame |
| `preview_profiles` | Unchanged product profile JSON |
| `preview_records` | Unchanged product record fields/JSON, unique run/sample sequence, `CHECK(ack_eligible=0)` |

All claims, manifest history, source highwater, receipts and projections advance in one
`BEGIN IMMEDIATE` / `synchronous=FULL` transaction. Replay compares immutable values rather
than overwriting them. Saved row counts detect missing receipts/records before replay; missing
data causes review, not reconstruction. Persisted previous manifest history rejects rollback
after restart. Failure before commit rolls back the complete transaction. A failure after
commit is an ambiguous outcome resolved by identical replay, never by deleting originals.

Original source/identity/marker/manifest/ledger files are not written. SQLite preview output
is a separate intended write target. Input quiescence is external: this is not a cross-file
transaction or a physical power-loss guarantee. Windows directory durability stays UNKNOWN.

## CLI

After test dependency setup and compilation (`bash scripts/test-rlc2-preview.sh`):

```bash
java -cp 'build/rlc2-preview:lib/*' \
 net.neristal.rrbridge.prototype.ingress.Rlc2PreviewCli \
 --enable-synthetic-preview SESSION LEDGER DEVICE_UUID VEHICLE_UUID OUTPUT.rlc2-preview.db
```

Windows uses class `WindowsRlc2PreviewCli` with classpath
`build/rlc2-preview;lib/*;build/identity-deps/jna-5.14.0.jar`. Native dependencies remain
test-only. Disabled/malformed arguments exit 2 before database creation. Validation/storage
failure exits 5 with a fixed reason and no original contents, paths, exceptions or secrets.
Success reports schema_version=1, PREVIEW_STORED, added counts, ACK false, durability UNKNOWN.
There is no send or ACK command.

## PostgreSQL test

`scripts/test-rlc2-preview-postgres.py` consumes the generated
`build/rlc2-preview-fixture.path`. It requires `ROMLOGGER_RLC2_PREVIEW_ENABLE=1` and a loopback
PostgreSQL DSN whose database is `romlogger_test` or `romlogger_*_e2e`, without URI overrides.
It validates before opening a database. A random `rlc2_preview_<uuid>` schema is created,
tested across two committed/reconnected passes, and only that invocation's schema is removed.
Existing tables are never used. Receipts/IDs/hashes/JSON/profile counts and per-source extent
are checked; ACK=true is rejected by SQL and injected failure is rolled back.

If `ROMLOGGER_SERVER_PATH` points to a trusted Server checkout, actual `ProfileRevision` and
`Batch` classes validate the JSON locally. `ROMLOGGER_REQUIRE_SERVER_CONTRACT=1` makes that
checkout mandatory. CI without that private checkout explicitly reports model validation
NOT RUN; its PostgreSQL persistence test still runs. This differs from the pre-existing
gated real-Server HTTP E2E, which remains SKIPPED when its read secret is absent.

```bash
python scripts/test-rlc2-preview-postgres-guard.py
# Supply the local disposable DSN through environment/secrets, never console output:
ROMLOGGER_RLC2_PREVIEW_ENABLE=1 python scripts/test-rlc2-preview-postgres.py
```

The Java synthetic store tests run Windows x64, Linux and actual Zulu8 x86. Test fixture
paths, dependencies, SQLite output and Python bytecode remain ignored build artifacts.

## Phase E: abrupt-stop recovery and operator dry-run

The RED separate-JVM test found that treating output as an immutable input refused even the
preview's own rollback journal. The output-only recovery gate now first checks regular DB/
journal paths, the SQLite header magic and RLP1 application ID, and absence of unexpected
WAL/SHM companions. Only then does SQLite open that positively identified preview output
with mode=rw and perform its normal transaction rollback. Owned schema is verified afterward.
The code never manually deletes a journal, repairs original data or relaxes input-ledger rules.
Foreign product DB plus journal remains byte-identical and is refused before recovery.

For manual review, the diagnostic CLI is the dry-run entry point: it reports fixed findings
without adopting, removing or editing any input. A staged marker/manifest, orphan, changed
identity, corrupt receipt or regressed highwater requires preservation and investigation;
there is no auto-clear or repair command. Quiesce synthetic writers externally before supplying
a closed ledger snapshot. Do not delete WAL/SHM to make an active input appear closed.

For an otherwise valid synthetic input and positively identified preview output, repeat the
explicit preview CLI after an interrupted run. SQLite recovery yields either the whole old
transaction or the committed new one; stable replay adds missing *new* output only. A saved
receipt/record mismatch or deletion is refused, not reconstructed. A transient exclusive lock
reports review/no ACK; retry explicitly after the owning test process releases the lock.

The fault suite halts separate JVMs at CLAIMS_WRITTEN, RECEIPTS_WRITTEN, BEFORE_COMMIT and
AFTER_COMMIT. It verifies whole receipt/record sets and exactly-once local replay, two competing
CLI processes, an exclusive lock, an actual SQLite RAISE(ABORT) trigger, six-table rollback,
original-byte preservation, and foreign journal refusal. Existing RLC2/RLM1 regressions retain
rename/manifest-stage halts, stale marker, copied UUID, CRC-valid alteration and quota tests.
These are synthetic process-failure tests. Physical power loss and real ENOSPC remain NOT RUN;
Windows directory durability UNKNOWN and ACK prohibition are unchanged.

## Phase H: committed checkpoint integrity

A synthetic RED replay changed only an event record primary key, retaining the same row count and JSON. The previous store inserted the expected event again and retained the unknown row. Replay now verifies the old committed record ID set before updating any checkpoint. It derives that set from the old source receipt boundaries and ordered S/E projection, independently of saved aggregate counters. Each old source must have exactly receipts 1..saved_seq, and old receipts must form a contiguous projection prefix. Unknown IDs, missing committed IDs, unknown sources and incomplete extents require manual review; the transaction leaves the evidence intact.

Tests include the original single-key RED, deletion plus adjusted saved record/receipt counts, byte-for-byte preservation of changed-ID evidence, normal active-source append, new-source rotation and idempotent replay. No schema migration, automatic reconstruction or ACK is added. This detects inconsistent stored state; it is not authentication against an administrator who can rewrite the entire database and every checkpoint consistently.
