# Lane ownership v1 — manual development recovery

Scope: synthetic local candidate only. No ECU/X270, formal ACK, live ingestion or production migration permission.

1. Retain all inputs, DBs, journals and lock/owner files. A BUSY result means stop/retry later; it is not permission to kill an owner or remove a lock. Never unlink `.worker.lock` or `.lane.lock`.
2. Establish which binaries, canonical ownership root and exact legacy DB are in use. All cooperating importers must share that root. Unknown old importers/other DBs/noncooperative Writers require external quiescence; no automatic lane switch exists.
3. Read-only advisory CLI: `LaneOwnershipStatus --status <root> <device-uuid> <vehicle-uuid> <payload-session>`. Exit0 IDLE,3 BUSY,4 MISSING,5 INVALID,6 UNKNOWN/UNINITIALIZED,2 invocation error. It uses a short shared observation lock, emits no paths or record bodies and never increments generation. IDLE is an observation, not a grant; only actual exclusive acquire grants ownership.
4. After a process exits, retry explicitly. Windows may still return BUSY briefly after process termination. Wait for the kernel to grant the lock; elapsed time/PID reuse never fences an owner. Closed/stale tokens always refuse.
5. Invalid/unknown version, replaced identity or owner/generation mismatch requires investigation and retained evidence. No repair CLI is provided. Do not overwrite lock metadata to advance generation or clear a latch.
6. For a known isolated RLC2 output, normal SQLite rollback recovery plus full schema/evidence validation may resume the same exact admitted snapshot. New Source UUIDs still require explicit admission. Publication ahead of Ledger/unfinished rotation remains a refusal; do not auto-seal/repair/reconstruct.
7. Isolated migration dry-run inventories current known v1 schema only. The original remains the manual rollback source; never apply the candidate in place. Recovery of a copied target requires a matching RMI1 source-digest owner sidecar and known header, then SQLite rollback and complete schema/row/inventory checks. Partial copy/marker, changed source or unknown schema remains manual. Never delete input WAL/SHM/journal to make preflight pass.
8. Backup/restore must occur while all owners/Writers/importers are stopped. Restoring lock metadata does not revoke a retained kernel lock or create a global fence across two restored roots. Simultaneously active backup copies with different roots are unsupported. Re-establish one root and explicitly inspect IDs/claims/admissions before any candidate retry.

Windows directory durability UNKNOWN. Runtime.halt/DB immediate stop are not physical power loss; real ENOSPC is NOT RUN. These gates stay independent of cooperative exclusion and HTTP success.
