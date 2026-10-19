# RLBA1 isolated backup acceptance / RMI1 rehearsal v1

Synthetic opt-in only. RLBA1 is a byte-equivalence manifest for a previously validated, externally quiescent local deployment; it is not a signed backup, a global clone fence, or activation permission. Keep its file/hash under operator control outside copied targets. An attacker who changes both baseline evidence and copies is outside this contract.

`IsolatedBackupAcceptance.capture` verifies RLDB1, retains shared READ observations of the exact existing old Worker lock and all observed root lane locks, verifies the existing RLI2 schema strictly read-only, verifies RMI1 preservation against the v1 source and projects source/manifest/ledger through existing RLC2 diagnostics. It captures all immediate session input files and root metadata plus distinct roles for v1, RLI2, RMI1, RMI1 owner, ledger and paired deployment anchors/old lock. Source/manifest and ledger identity/publication consistency are checked by the existing reader. Unknown schema, missing artifact, active lock, input sidecar or changed before/after hashes refuses. An absent old lock is not created to pass backup capture.

Bounds:180 artifacts,128MiB/file,256MiB aggregate; streaming32KiB SHA256. The canonical/no-link path and private-file checks apply at capture. Every source role has exact length/SHA256; schema/record/ACK/cursor/receipt/rowid/source claim and migration guard integrity are independently checked for RMI1 through all old tables and sqlite_sequence. RLI2 schema/ACK constraints and source projection are checked, but RLBA1 does not newly certify every pre-existing RLI2 record's semantic lineage. That separate acceptance remains in the integration/replay suites. It never reconstructs a missing record, owner, manifest, ledger or source.

`V1LaneMigration.verifyIsolatedCopy(true,true,source,target)` is read-only for both DBs, checks the exact RMI1 source-hash sidecar, application/version/schema/guards, source inventory, every preserved table/rowid value fingerprint and before/after source/target bytes. Unlike migrateCopy it does not create a target or recover journals. RMI1 remains a versioned inventory copy; no RLI2 record conversion exists.

The reference schema helper now uses a fresh invocation-owned directory and `finally` closes handles and deletes only exact synthetic.db/-wal/-shm/-journal then the empty own directory. It never recursively/wildcard deletes references from earlier invocations. Unexpected files/links cause retention/refusal. RED repeatedly leaked10 directories; GREEN adds zero and preserves all pre-existing references.

`SyntheticMigrationRehearsal.run` sequences RLDB1 preflight -> old v1 preflight -> owned copied RMI1 migration -> read-only candidate verification -> RLBA1 capture. Original and target are separate DBs with separate locks/durability events, never one cross-DB transaction. Prepared-commit fault rollback, explicit retry/rerun and full preserved data hashes are tested. This is a Java test-only harness, not a Worker launch/service switch. Existing Phase II tests independently cover actual child-JVM halt/recovery, unknown schema and source-claim conflicts.

`Evidence.copyToNewDirectory(true,target)` requires a new isolated target, copies only enumerated artifacts, then rechecks source/copy hashes. Partial failed copies and their baseline evidence remain for review; no overwrite/resume/repair occurs. Restored metadata intentionally retains original paths/identity and must NOT be used as a new deployment enrollment. Copy-only success returns VERIFIED_ISOLATED_COPY with activation_allowed=false, external_single_active_required=true, ACK/production/repair false. Two cloned copies are never made runnable or globally fenced. Hash mismatch, missing/extra files, record/ACK tampering, stale/damaged known metadata, WAL/journal and partial ownership sidecars refuse.

Read-only copied-target CLI:

```
java -cp 'build/phase3:lib/*' net.neristal.rrbridge.product.v1.IsolatedBackupAcceptance --inspect-isolated-copy EVIDENCE.rlba1 COPIED_DIRECTORY
```

Windows separator `;`. Exit0 verified isolated copy (no activation); exit2 usage; exit5 REVIEW_REQUIRED/BACKUP_VERIFICATION_REFUSED. Evidence writes CREATE_NEW + force; multiple artifact files/evidence/directory durability are not one atomic event. Evidence is deliberately not stored inside the copied directory, which requires an exact role set. Windows directory durability remains UNKNOWN.

Operator-mediated rollback in the rehearsal means retaining the unchanged original v1 and refusing candidate activation. Do not copy a candidate over the original, rewind ACK, start an old sender against RMI1/RLI2, delete locks/sidecars or run two restored roots. Actual production stopping, deployment enrollment/rebinding/restore/cutover and rollback require separate reviewed procedures and approval.

Remaining acceptance: dedicated after-owner/during-copy/after-commit fault injection across the whole new harness, known-version deployment rebind/restore authority, authenticated artifact distribution/process provenance, RMI1-to-RLI2 record mapping, global external fencing and Windows directory durability. Existing halt/SQLite-busy suites are not proof of these new harness-specific fault boundaries. Physical power loss/real ENOSPC/ECU NOT RUN.
