# Manual recovery/migration preflight and bounded workloads (Phase G)

## Read-only operator/Supervisor plan

Every `IngressDiagnostics.Finding` now includes a fixed `nextAction`; JSON adds
`next_action` and `automatic_repair_allowed:false` without removing schema-v1 fields.
These are manual review hints, never callbacks or executable commands. ACK stays false.

| Finding | Manual hint |
| --- | --- |
| tmp | Keep staged files and review |
| orphan/manifest/history | Preserve originals and compare manifest history |
| stale/highwater regression | Compare persisted highwater without rewinding |
| identity/lane conflict | Verify ownership without readoption |
| receipts/sealed change | Compare preserved receipts without repair |
| quota | Stop new recording while retaining originals (hint only) |
| active/unavailable DB | Supply an externally closed checkpointed ledger snapshot |
| ledger lag | Verify ledger alignment; not misclassified as corrupted receipts |
| RLC1 or mixed formats | Require an explicit migration contract |
| durability UNKNOWN | Keep ACK disabled |

Session inspection/projection now rejects a legacy marker alongside an RLC2 source, and
reports when the ledger has not yet caught up with a committed marker. Existing complete
RLC2/ledger routes remain unchanged; unpublished active tails are still withheld.

## Source-only migration preflight

```bash
java -cp 'build/ingress-diagnostics:lib/*' \
 net.neristal.rrbridge.prototype.ingress.IngressDiagnostics \
 --migration-preflight SOURCE.rlj --json
```

On Windows select `WindowsIngressDiagnosticsCli` and the same native test-only classpath
documented in acceleration_diagnostics.md. Omit --json for human output and manual hints.
The command does not open/create a ledger or write a marker. Exit5 means manual review,
including intact legacy input; exit2 means invalid arguments.

The existing RLC1 codec is reused for CRC/bounds/envelope inspection, with original prefix
SHA/frame sequence and existing RLI1 UUID checks. **Its Java fileKey hash is never interpreted
as an RLC2 POSIX/NTFS identity.** A valid envelope/prefix still needs an explicit migration
contract and ownership/durability gates. Windows RLC1 publication remains unsupported;
reading a portable envelope does not change that. RLC2-only source inspection explicitly
requires persisted ledger/claims/receipts/manifest before any integration assessment.

Both marker versions, missing marker, retained old/new tmp, invalid prefix and unpublished
tail are reported without repair. Known pending updates are classified as unreviewed rather
than falsely called corrupt. Source/marker/identity byte preservation and no RLC2 creation
are tested. There is no migration/repair/send command or automatic format preference.

## Real bounded load/replay

`scripts/test-rlc2-preview.sh` also executes `Rlc2PreviewLoadTest`: by default 1000 observations
across two physical sources, then three additional independent sessions. It verifies all
receipts, global observation sequence, distinct record IDs, exact source highwaters,
manifest/session/source ownership, reopened-DB replay, ACK prohibition and original bytes.
The original synthetic CLI fixture for PostgreSQL remains separate.

Use only synthetic data for a larger bounded run:

```bash
ROMLOGGER_LOAD_OBSERVATIONS=10000 ROMLOGGER_LOAD_SESSIONS=10 \
 java -cp 'build/rlc2-preview:lib/*' \
 net.neristal.rrbridge.prototype.ingress.Rlc2PreviewLoadTest
```

Windows uses the native test JNA classpath with semicolon separators. Bounds are 2–20000
main observations and 0–12 additional sessions. The larger executed run has 10100 product
records, 10111 raw receipts, 22 source cursors and 11 logical sessions. It passed on Linux
JDK17 x64 and actual Windows Zulu8 x86, with no duplicates, missing receipts or changed inputs.

## Identical hex format with lower allocation

The ledger's lowercase, zero-padded two-hex-digits-per-byte encoding now uses a fixed digit
lookup instead of constructing a Formatter for every byte. Database/wire formats are unchanged.
The new x64 inspector verified 10001 receipts from an actual x86 dataset created by the old
implementation; every original file remained byte-identical. Existing real SQLite, Python
hashlib/PostgreSQL comparisons, stale/copy/tamper and crash regressions continue to apply.

Single cold-process observations for the same 10000-observation/11-session synthetic workload:

| Environment / implementation | Source publish ms | Ledger ms | Project ms | Preview commit ms | Reproject + replay ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| VPS Java17 x64 / original | 297 | 1423 | 1485 | 1159 | 2086 |
| VPS Java17 x64 / lookup | 297 | 949 | 614 | 1675 | 939 |
| Windows Zulu8 x86 / original | 2151 | 5572 | 9856 | 6377 | 11433 |
| Windows Zulu8 x86 / lookup | 1722 | 3653 | 4359 | 4807 | 5010 |

These are exploratory observations with filesystem/JIT/host variance, not normalized
benchmarks or an ECU logging-rate guarantee. The increased VPS commit time is retained in
the table rather than hidden. Correctness, original preservation and ACK gating are the
acceptance criteria; there is no flaky wall-clock threshold.

## Next product integration gate

The current v1 single-source claim/cursor contract still needs an explicit versioned mapping
for manifest-session ownership and child sources. Preserve original receipts, atomic cursor/
projection state, expected native identity and persisted manifest history in that design.
Windows directory durability remains UNKNOWN; physical power loss, real ENOSPC, ECU/X270,
production DB/service deployment and formal new-path Server ACK remain unverified/disabled.
This phase provides inspection, executable preview and measured bounded replay without
silently approving that production migration.
