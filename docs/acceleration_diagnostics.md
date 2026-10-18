# RLC2 Supervisor diagnostics (Phase A/B, isolated)

## Review of existing PR39–41

Targeted independent review of `WatermarkV2Prototype`, `WatermarkLedgerPrototype` and
`RotationManifestPrototype` found no new critical defect requiring a core change. RLC2
compares writer-recorded bytes before and after sync; persistent expected identity and
highwater reject copied sources/stale markers; receipts and highwater use one IMMEDIATE
transaction; seal checks full coverage and later verifies immutable lengths/hashes.
Staged updates and orphan segments fail closed. Existing separate-JVM crash/race tests
remain the regression baseline. This was a focused code review, not a proof of durability.

Known limits retained: Windows directory durability UNKNOWN; POSIX identity path-sampled;
initial identity claim TOFU; persisted manifest history requires a caller-provided previous
state; digest is not authentication. Concurrent modification may invalidate inspection.

## Java API and CLI

`IngressDiagnostics.inspect(sessionDirectory, existingLedger, readOpener, previousManifest)`
returns immutable `Report` / `Finding` objects with stable `Code`, segment index and a
fixed cause. No exception text, original rows, paths or credentials are emitted. It never
constructs the ledger, runs DDL, ingests, repairs, adopts or deletes anything.

Supervisor callers retain the previous manifest for history checks. Inspection is
observational, never permission to send or ACK. `ackEligible` is always false and directory
durability always UNKNOWN (inspection cannot recover publisher durability evidence).

SQLite prerequisite: **a closed, checkpointed snapshot with no -wal, -shm or -journal**.
SQLite `mode=ro` alone created WAL/SHM files in the RED test. Therefore this inspector uses
`mode=ro&immutable=1`, query_only and a single read transaction; it rejects companions both
before and after reading, rather than ignoring uncheckpointed WAL commits. Caller must
quiesce writers externally or supply a closed snapshot; the inspector does not checkpoint,
lock, copy or mutate a running DB. An active DB reports DATABASE_UNAVAILABLE, never healthy.
An inspection is not an atomic snapshot across filesystem and SQLite, and cannot certify
the absence of a concurrent writer from its own observations.

Linux (after existing dependency setup):

```bash
bash scripts/test-ingress-diagnostics.sh
java -cp 'build/ingress-diagnostics:lib/*' \
  net.neristal.rrbridge.prototype.ingress.IngressDiagnostics SESSION LEDGER --json
```

Windows uses the isolated native reader already used by RLC2 tests (JNA remains test-only):

```bash
bash scripts/test-ingress-diagnostics.sh
java -cp 'build/ingress-diagnostics;lib/*;build/identity-deps/jna-5.14.0.jar' \
  net.neristal.rrbridge.prototype.ingress.WindowsIngressDiagnosticsCli SESSION LEDGER --json
```

Omit `--json` for human-readable output. Exit 0 = inspected with no review finding, **not
safe/durable/ACK-ready**; exit 5 = review required (including unavailable DB); exit 2 = usage.
Schema version 1 includes status, ack_eligible=false, directory_durability=UNKNOWN, findings.
Stable codes cover orphan/tmp, marker/highwater regression, identity/claim/receipt conflicts,
manifest corruption/history, seal changes, quota and unavailable/changing snapshots.

## Verification

Real SQLite/native filesystem scenarios validate byte-for-byte preservation of every
session/ledger file, WAL rejection and preservation, no absent DB creation, orphan/tmp,
manifest corruption/rollback, stale marker, highwater regression, receipt alteration/deletion,
identity and TSV claims, seal growth, 16 real sealed segments, JSON/human CLI and exit codes.
CI runs Linux, Windows x64 and actual Zulu8 x86; results are recorded in the worklog.

## Supervisor contract v2 (additive)

Legacy schema_version=1, status and CLI exit codes remain diagnostic compatibility fields. New supervisor_contract_version=2 adds inspection_status (COMPLETED/INCOMPLETE), source_integrity (VERIFIED_UNDER_QUIESCENCE/INVALID/UNDETERMINED), source_review_required, operational_ready=false and manual_intervention_required=true. Existing directory_durability=UNKNOWN, ack_eligible=false and automatic_repair_allowed=false remain independent gates. Inspection completion or exit0 cannot authorize sending, recovery or ACK. Manual intervention includes establishing the missing durability/operational evidence even when source review itself is not required. VERIFIED_UNDER_QUIESCENCE is conditional, never a concurrent snapshot proof.
