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
owned tables. Foreign/renamed product DBs are refused before writable access. Existing output
must be quiescent for opening; active companions cause conservative refusal. No v1 table is
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
