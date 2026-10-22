# Phase V deferred P2 design and acceptance gates

These are proposals and test designs, not implemented gates or production permission. P0/P1 provenance verification takes priority. RLLV1 remains experimental record evidence; adopting it as a formal product storage version requires a separate decision.

## Mapped Windows network drives (F3)

`DeploymentBinding.safe()` still recognizes UNC spelling and filesystem type but has no locale-independent drive-type proof. Proposed design: an injected read-only drive-type probe, implemented with fixed SystemRoot PowerShell and `[System.IO.DriveInfo]::new(root).DriveType` emitted as a numeric enum. Accept only proven local Fixed drives in the existing filesystem allowlist; Remote, Unknown, missing output, timeout, duplicate output and unsupported invocation fail closed. Pass the root as data rather than interpolated shell code, bound process output/time, and retain Java8 x86 compatibility. Do not run `net use`, create a share or change a real mapping.

RED fixtures: drive-letter path with synthetic Remote result and misleading NTFS type; Unknown/empty/nonzero/timeout output; local Fixed control. Then an actual already-existing mapped drive, only if available and authorized, with no files written. No such actual mapping was used in Phase V; F3 remains unimplemented/NOT RUN.

## Entry inventory and old binaries

Source inspection at implementation head `16db2c4` identifies:

- `ProductBridgeWorker`: explicit `--once`, `--foreground`, opt-in `--journal-once`; old Worker lock and optional synthetic lane scope; it can import and use the old sender. It is not a new RLI2 product entry.
- `ProductBridgeOnce`: explicit legacy TSV import/send; creates the v1 spool directly. No RLDT1 or independent process provenance gate.
- `DurableSpool` / `IncrementalTsvBridge`: callable low-level legacy APIs; no universal binary exclusion.
- `Rlc2IntegrationCli`: explicit synthetic/quiescent offline candidate only; `STORED_OFFLINE` is not production permission.
- `ingestBoundDeployment` / `ingestBoundActive`: synthetic bound APIs with RLDB1/RLDT1 checks. They cannot prevent an unrelated historic binary opening a separate/legacy path.

A future default-off product launcher should require exact artifact/version expectations, active deployment-generation checks, source admission, retained legacy/lane/publication locks and no sender/ACK activation. RLAP1 classpath inspection must not be advertised as Windows running-process proof: Windows remains UNKNOWN. A launcher gate does not fence direct Java APIs or already-running unknown old binaries; never stop those automatically. No old binary was launched against a real DB in Phase V.

## Transition-specific acceptance fixtures to add next

1. Build two valid synthetic sessions in one candidate; reuse a source UUID already owned by the other session during SAME_SESSION_MANIFEST_UPDATE. Require precise SOURCE_UUID_REUSED and unchanged candidate/ledger/binding bytes.
2. Interrupt prepare in one child JVM while another holds old/new lane/publication locks. Attempt abandon and commit independently; require no simultaneous authorizations, preserve every prepare/partial anchor, and verify the generation chain after release/retry.
3. Re-read committed and abandoned chains under cross-generation manifest/ledger rollback, path alias and cloned-root variants. Correct final seal must remain legal; prior Session entry must refuse after NEXT_SESSION.

Existing Phase III/IV regression is executed separately; it does not substitute for these missing dedicated fixtures. Physical power loss/real ENOSPC, hostile same-user/admin path replacement, global fencing, privileged Windows symlink cases and real mapped-SMB tests remain independent gaps.

## Additional P1 acceptance left for a later isolated harness

Add a separate child SQLite writer holding an uncommitted update, then rollback, verifying sidecar refusal and byte preservation. Also cover a noncooperative reserved lock before any journal exists; immutable readers plus the external-quiescence assertion are not proof of writer absence. Do not add writable lock probes to an existing user DB. Test actual junction/symlink output-parent replacement and protected rollback/WAL/SHM competitors using fresh owned targets only. No automatic cleanup or adoption of an interrupted `.rlpc1-work` directory.
