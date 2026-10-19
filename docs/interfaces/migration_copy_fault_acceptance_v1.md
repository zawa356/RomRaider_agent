# RMI1 isolated copy fault acceptance / copy-state CLI v1

Synthetic-only extension. No production DB migration/restore, Worker stopping, global fence, automatic repair or formal ACK. The v1/RMI1 data schema and owner marker format remain unchanged. Old beforeCommit Hook API delegates to the new staged API; ordinary callers do not activate faults.

`V1LaneMigration.migrateCopyWithStages(enable,externallyQuiescent,source,target,StageHook)` has BEFORE_OUTPUT (after inventory, before owner/DB), OWNER_CREATED (owner forced, no DB), COPY_PROGRESS (first buffer, up to32KiB, forced), COPY_COMPLETED (full copy forced/closed), BEFORE_COMMIT and AFTER_COMMIT. CREATE_NEW prevents overwriting a target. Copying is bounded128MiB with32KiB buffers and private new POSIX0600 output. A plain v1 target without recovery sidecars must still match the exact original source hash before writable SQLite open; partial/modified copies never get reconstructed. Only matching RMI1 ownership plus known header permits explicit normal SQLite recovery of an interrupted target, as before. Sources with sidecars always refuse. The first buffer may exhaust a small source; state is determined from actual bytes, not the stage name. Owner and every existing recovery sidecar must also pass canonical/no-reparse/single-hardlink/regular-file checks before writable recovery: an aliased WAL previously mutated a separate fresh synthetic DB in RED.

Stages are separate filesystem/SQLite events. A committed SQL transaction can still be in WAL/SHM when a process halts before connection close/checkpoint. Read-only inspection does not ignore sidecars to claim completion. No stage converts Runtime.halt into physical power-loss proof.

Read-only input/candidate CLI (temporary authoritative schema references are created only in own fresh temp directories and cleaned; no source/target/owner/lock/sidecar creation or recovery):

```
java -cp 'build/phase3:lib/*' net.neristal.rrbridge.product.v1.V1LaneMigration --inspect-isolated-copy-state --external-quiescent SOURCE TARGET.lane-migration.db --no-apply
```

Windows separator `;`. The explicit external flag asserts all participants are stopped/isolated; missing locks never prove this. Existing exact source/target Worker locks are observed shared/READ; absent required source lock requires review. Output JSON contains fixed copy_state/operator_action, ACK/repair/production=false and inventory counts (-1 unavailable), without paths/bodies/errors.

| State | Meaning / exit |
|---|---|
| NO_CANDIDATE | No DB/owner observed; explicit synthetic retry only /5 |
| OWNER_ONLY_REVIEW_REQUIRED | Forced owner without DB; retain all evidence, no auto-overwrite /5 |
| COPIED_V1_READY_FOR_EXPLICIT_RETRY | Complete byte-identical plain copy; explicit synthetic retry only /5 |
| PARTIAL_OR_FOREIGN_TARGET_REVIEW_REQUIRED | Partial/changed/unknown copy; manual review, no repair /5 |
| UNOWNED_TARGET_REVIEW_REQUIRED / OWNER_MISMATCH_REVIEW_REQUIRED | Missing/stale/changed ownership; manual review /5 |
| OWNED_INTERRUPTED_TARGET_REVIEW_REQUIRED | Known owner/header with sidecars; only explicit owned recovery may establish committed state /5 |
| COMMITTED_INVENTORY_VERIFIED | Closed RMI1 full preserved rows/inventory/guards verified; no activation /0 |
| BLOCKED_OWNER_BUSY | Actual retained observation lock conflict /3 |
| INSPECTION_REVIEW_REQUIRED | Unknown schema/path/owner/gate/I/O; evidence retained /5 |
| USAGE | Missing explicit mode/quiescence/no-apply arguments /2 |

`SyntheticMigrationRehearsal.runWithStages` passes these boundaries through the full deployment preflight/owned-copy/inventory/candidate/backup harness. Tests inject before output, during real partial copy and after actual commit, then verify explicit retry/inactive backup. `Evidence.copyToNewDirectory` has a synthetic afterArtifact hook; an actual first-artifact interruption preserves the partial directory and restore verification refuses its incomplete role set. No cleanup of failed candidates is performed.

Acceptance: V1MigrationStageTest actually halts6 child JVMs at all boundaries, checks source bytes/ACK unchanged and read-only target/owner/journal fingerprints, rejects owner-only/partial retries without overwrite, explicitly retries complete/owned-interrupted/committed copies and verifies every original table/rowid/ACK/retry/review field. A separate actual child holds SQLite BEGIN IMMEDIATE (without cooperative lock) so migration returns SQLITE_BUSY; after owned child exit, explicit retry succeeds. A synthetic source append at precommit causes refusal, retains changed source evidence and never silently repairs it. CLI subprocess requires explicit quiescence and never creates a candidate. Existing migration21 and backup/rehearsal tests remain independent.

Still separate: production snapshot authority, arbitrary uncooperative writers, same-path/identity restored clones, complete process/distribution provenance, RLDB1 manifest replacement/next Session rebind, RMI1-to-RLI2 physical receipt/record mapping, cross-DB atomicity and Windows directory durability (UNKNOWN). No physical power-loss/real ENOSPC/ECU test.
