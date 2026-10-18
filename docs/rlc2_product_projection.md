# RLC2 → product v1 synthetic projection (acceleration Phase C)

`Rlc2ProductAdapter.project(enableSynthetic, sessionDirectory, closedLedger, device,
vehicle, readOpener)` produces an immutable in-memory projection. Explicit `true` is
required before accessing any path. Rows must use a `synthetic-` session and `TEST_`
parameters. No product database, outbox, worker, HTTP, ACK or deletion is invoked.

## Model comparison and mapping

| Existing model | Projection rule / boundary |
| --- | --- |
| RLM1 session UUID | Rotation ownership namespace; retained separately from payload session |
| `v1_source_claims` | Current key is device/vehicle/payload session, with **one** source key. A rotated session has multiple UUIDs, so direct insertion into the current product claim is BLOCKED. Preview persistence must keep a manifest-session claim and child source claims in its own tables. |
| `v1_journal_import_cursors` | Existing cursor has one UUID/path, first/chain hashes and metadata state. Projection exports each source's exact RLC2 identity/generation/sequence/offset/prefix, rather than mislabelling those as the existing chain hash. |
| Old TSV lane | Any legacy claim detected by read-only diagnostics blocks projection; no fallback or automatic migration |
| source UUID | Preserved per physical segment, with canonical path, identity, sealed flag and marker |
| payload session / run | All segments must keep one payload session. Existing v1 converter derives the same stable run ID as TSV import. RLM1 session UUID is not substituted for payload session. |
| frame sequence / record ID | Frame sequences restart per source. Original receipt ID remains UUID+frame sequence; v1 observation/event IDs use the existing shared converter. These are separate identities. |
| observation sequence | Must strictly increase across all rotated sources; reset/reuse is rejected before publishing a result |
| metadata | Converted in manifest order, carrying logical segment definitions across physical rotation. No metadata duplication or made-up defaults. |
| receipt/highwater | Every source marker must exactly match persisted ledger generation/sequence/offset/prefix. Diagnostics also compare original receipt bytes/hash/ID/offset. No stateless marker is accepted as a ledger substitute. |
| durability / ACK | Always ACK-ineligible. Windows directory durability remains UNKNOWN. No transport entry point. |

The current product single-source claim and per-source cursor semantics are **not changed**.
This is an executable mapping/pre-integration layer, not completion of production rotation.
Formal claim/cursor migration and Windows durability remain production gates.

## Shared converter

`IncrementalTsvBridge.ProjectionSink` receives profiles and records from its existing
`apply()` implementation. Existing importer constructors use a sink that delegates to the
same `DurableSpool.appendProfile/appendRecord` calls as before. `projectRows()` is a no-I/O
conversion API; its caller validates source provenance and buffers the output before exposing
it. The adapter supplies an in-memory sink and rejects duplicate projected record IDs.

The synthetic preview keeps deterministic existing v1 IDs; it does not introduce a new
on-wire generation convention. Tests compare every projected record/profile JSON and ID
against a separate invocation of the existing TSV importer into a temporary baseline DB.

## Consistency and bounds

Inputs must be externally quiesced. The adapter first runs read-only diagnostics and full
manifest verification, then requires exact ledger alignment, projects only committed prefixes,
and repeats marker/manifest/diagnostic validation before returning. Concurrent changes fail
closed when observed; this is not an atomic cross-filesystem/SQLite snapshot proof.

The preview stops at 100,000 rows or 32MiB of row bodies; it never truncates output to fit.
The existing 16-source/8MiB-per-source constraints remain. Capacity diagnostics require review.
An unpublished active tail is withheld. A newly committed marker ahead of the ledger blocks
projection until an independent explicit ledger ingest has completed.

## Running synthetic tests

```bash
bash scripts/test-ingress-diagnostics.sh
# Existing compiled classes, after selecting Zulu8 x86:
ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-ingress-diagnostics.sh --run-only
```

Windows uses the existing test-only JNA native reader. Linux uses the existing POSIX reader.
No native library is added to the product Java agent. The existing product spool test suite
is required because the conversion target was extracted from product code.
