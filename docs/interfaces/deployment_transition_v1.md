# RLDT1 deployment transition / RLDB2 versioned binding (Phase IV candidate, synthetic only)

## Phase VI abandon exclusion extension

The final Phase VI entry correction also performs the read-only Windows drive-root proof at the start of `plan` (before root/anchor chain reads) and `commitSynthetic` (before Worker/Lane/Publication file access). `DeploymentBinding.requireLocalDrives` checks every distinct root without filesystem traversal, and the existing later layout checks still repeat. Normal entries always use the fixed system probe; package-scoped test seams inject only the early observation. A synthetic Remote+missing-binding fixture reproduced the prior ordering: plan read binding evidence first, and commit created lock files before rejecting anything about the drive. No actual SMB was accessed.

New prepare records additionally retain `old_lane_lock`, `new_lane_lock`, `old_publication_lock`, `new_publication_lock`: exact hash-derived UUID `.lane.lock` basenames, without raw payload Session text. Commit already holds these scopes. Abandon now takes the exact legacy Worker guard, validates the prepared generation and old-layout lock names, and retains shared read-only locks on every distinct existing old/new Lane/Publication file through marker creation. Busy scopes return `BLOCKED_UNCOOPERATIVE_PROCESS` without creating an abandon marker. Unknown/missing lock-set fields return `ABANDON_LOCK_SET_UNKNOWN`; no lock is created or repaired for abandon. Completed historical RLDT1 generations remain readable. An interrupted historical prepare without these fields requires manual contract review, not automatic upgrade or deletion. Earlier binaries do not enforce this extension and still require operational exclusion.

Because the additional lock-set fields participate in the deterministic plan digest, an approved pre-extension dry-run digest is not reusable: recomputation returns PLAN_CHANGED. A fresh explicit plan review is required; no automatic approval or digest rewriting is provided.

RED reproduced the previous method creating an abandon marker while a separate JVM held the old payload Lane. This establishes a missing exclusion gate, not a demonstrated simultaneous double activation. Dedicated tests cover all four old/new Lane/Publication competitors, abandon/retry, four actual child halt stages and SAME_SESSION physical Source UUID reuse from another already-stored run. Physical power loss and global fencing remain unproven.

`net.neristal.rrbridge.prototype.ingress.DeploymentTransition`. This is a candidate implementation used with isolated synthetic fixtures and explicit opt-in. It never activates a restored copy, changes production state, connects a formal ACK or proves global fencing. RLDB1 ([deployment_binding_v1.md](deployment_binding_v1.md)) is unchanged as a format. It now refuses with `BINDING_SUPERSEDED` once any RLDT1 evidence exists for its root or legacy anchor.

## Why

RLDB1 binds the file identity of `rotation.manifest`. `RotationManifestPrototype` publishes every seal and rotation through an atomic replace, so a legitimate seal or a new source makes RLDB1 refuse with `BINDING_LAYOUT_CHANGED`. Continuous operation needs a versioned, operator-mediated authority change instead of editing or deleting RLDB1.

## Identities

| Kept fixed for the whole deployment | Changes only through RLDT1 |
|---|---|
| root, legacy v1 DB, RLI2 output, ledger (path + identity), device, vehicle | session directory, payload (logical) session, manifest session UUID, admitted physical sources |

Changing root, legacy, output, ledger, device or vehicle refuses with `DEPLOYMENT_ANCHOR_CHANGE_UNSUPPORTED` / `DEVICE_OR_VEHICLE_CHANGE_UNSUPPORTED`. These remain a different deployment (BLOCKED).

## Evidence files and generations

- Generation 0: the existing RLDB1 pair (`<root>/deployment.rldb1`, `<legacy>.deployment-binding`). It is never modified.
- Generation N≥1 is written CREATE_NEW and forced in this order:
  1. `<root>/deployment.rldt1.gNNNN.prepare`: the plan record (`RLDT1-PREPARE`).
  2. `<legacy>.deployment-binding.gNNNN`: the RLDB2 record (legacy-side anchor).
  3. `<root>/deployment.rldb2.gNNNN`: the identical RLDB2 record.
  4. `<root>/deployment.rldt1.gNNNN.committed`: SHA-256 of the plan and the record (`RLDT1-COMMITTED`).
- These are four separate durability events. They are **not** one atomic commit, and Windows directory durability remains UNKNOWN.
- A generation that has a prepare file but neither committed nor abandoned evidence makes every caller refuse with `TRANSITION_INTERRUPTED`: verifyActive, plan, commit, the bound ingest, and the RLDB1 verify (`BINDING_SUPERSEDED`).
- An operator can record `deployment.rldt1.gNNNN.abandoned` (`RLDT1-ABANDONED`, holding the prepare's SHA-256 and the reason's SHA-256). Nothing is deleted, and partial anchor or root files stay as evidence. The previously committed generation stays active, and a retry uses N+1.
- Chain rules: generation numbers are contiguous; unknown `deployment.rldt1.*` / `deployment.rldb2.*` / `<legacy>.deployment-binding.*` names refuse; the root and anchor records must be byte-identical; each committed record's `previous_sha256` equals the SHA-256 of the previously active record; `deployment_uuid` stays that of generation 0.

## RLDB2 record

The record holds the bound keys `root(_identity)`, `legacy(_identity)`, `output(_identity)`, `session(_identity)`, `manifest` (path only), `ledger(_identity)`, `device`, `vehicle`, `payload` and `manifest_session`, plus `version=RLDB2`, `mode=SYNTHETIC_ONLY`, `generation`, `previous_sha256`, `deployment_uuid`, `kind`, `manifest_floor` (raw RLM1 bytes, hex) and `manifest_floor_generation`. Identities follow RLDB1 (Windows: volume serial + NTFS file ID, see Phase IV F1).

`verifyActive` requires the observed layout to equal the active record. For generation 0, the exact manifest identity is required too (unchanged RLDB1 semantics). For RLDB2, the current manifest must pass `RotationManifestPrototype.read(dir, floor)`: same session, generation not lower, sealed entries unchanged, active segment not replaced, and no staged manifest. A later legitimate seal therefore needs no new transition, while a manifest rollback below the floor refuses with `MANIFEST_NOT_EXTENDING_FLOOR`.

## Transition kinds (plan)

All kinds have these preconditions:

- explicit enable plus the external single-active operator assertion (declared, **not proven**);
- a non-empty purpose, which is hashed and never echoed;
- the old layout equals the active record, except the manifest identity;
- the output DB contains the old manifest session (owner device, vehicle and payload match; the bound session path matches);
- the output manifest extends the active floor, and the on-disk old manifest verifies against the output manifest (sealed segment identity, final sequence, offset and prefix hash);
- every output source is still in the manifest;
- the durable ledger covers the output: for every source, ledger highwater is ≥ output highwater with the same prefix at equal sequence, and every stored output receipt exists in the ledger with the same body SHA-256 (`LEDGER_REGRESSED`, `LEDGER_PREFIX_CHANGED`, `LEDGER_RECEIPT_MISSING`, `LEDGER_RECEIPT_CHANGED`, `LEDGER_SOURCE_MISSING`);
- legacy, ledger and output have no `-wal/-shm/-journal`;
- no held legacy Worker lock, old or new payload lane, or old or new publication boundary. These are observed with a shared lock, never created (`BLOCKED_UNCOOPERATIVE_PROCESS`).

| Kind | Additional conditions |
|---|---|
| `SAME_SESSION_MANIFEST_UPDATE` (same directory, manifest session and payload) | The NEW source UUIDs (in the manifest but not in the output) must equal the operator's explicit admission set (`SOURCE_ADMISSION_REQUIRED` / `SOURCE_ADMISSION_UNEXPECTED`) and must be unknown to the output (`SOURCE_UUID_REUSED`). Under RLDB2, an unchanged floor refuses with `NO_TRANSITION_REQUIRED`. |
| `NEXT_SESSION` (directory, manifest session and payload all different) | The old Session is closed: no active segment, the output holds that exact final manifest, and every sealed source is stored at its final sequence and offset with sealed=1 (`OLD_SESSION_NOT_CLOSED`, `OLD_SESSION_NOT_FULLY_STORED`). The directories do not overlap. The new manifest session is absent from the output, and the new payload is not claimed in the output or the legacy v1 DB. The new manifest verifies and names the new manifest session. Its sources equal the admission set, are not in the output or the old manifest, and any ledger entry for them is the exact new segment path. |
| anything else | `SAME_DIRECTORY_DIFFERENT_SESSION`, `MANIFEST_SESSION_MOVED`, `PAYLOAD_SESSION_REUSED`. |

The plan record contains the old and new generation, kind, manifest session UUIDs, payload SHA-256s, file SHA-256s of legacy/output/ledger, old and new manifest SHA-256s, the admitted UUIDs, the purpose SHA-256 and the SHA-256 of the new record. The plan digest is deterministic, and the operator approves that digest.

## Commit (synthetic API only, no CLI)

`commitSynthetic` acquires, in the existing order, the legacy Worker guard, then the old and new payload lanes, then the old and new publication boundaries. It recomputes the plan and requires the approved digest (`PLAN_CHANGED`). After writing prepare, it re-verifies the held locks and recomputes the plan again, then writes anchor, root and committed, verifying the held locks once more before committed. It ends by requiring `verifyActive(next)` to equal the new generation. It never regenerates manifests, admits sources automatically, writes to the legacy DB/output/ledger, or touches ACK state.

`Rlc2IntegrationStore.ingestBoundActive` is the bound ingest for RLDT1-managed deployments. It calls `verifyActive` before opening the output and at every store stage, and refuses if the active generation changes (`DEPLOYMENT_GENERATION_CHANGED`). The old layout of a superseded Session refuses (for example `SESSION_CHANGED`), so the old and new Sessions are never both active through this code.

## Read-only CLI

```
java -cp <classes>:lib/* net.neristal.rrbridge.prototype.ingress.DeploymentTransition --inspect-active-synthetic --external-single-active ROOT LEGACY OUTPUT SESSION MANIFEST LEDGER DEVICE VEHICLE PAYLOAD MANIFEST_SESSION
java -cp <classes>:lib/* net.neristal.rrbridge.prototype.ingress.DeploymentTransition --plan-synthetic --external-single-active <old 10 fields> NEW_SESSION NEW_MANIFEST NEW_PAYLOAD NEW_MANIFEST_SESSION ADMITTED_CSV|- PURPOSE
```

Exit codes: 0 verified/planned, 2 usage, 3 `BLOCKED_UNCOOPERATIVE_PROCESS`, 5 review required. The JSON contains no paths, purpose text or bodies, and always reports `production_authorized=false`, `ack_eligible=false`, `automatic_activation=false` and `global_fencing=false`. The plan CLI uses the POSIX reader; on Windows the physical identity reader stays test-only (the same as `Rlc2IntegrationCli`). The inspect CLI needs no reader.

## Not covered / BLOCKED

- A pre-Phase-IV binary only knows RLDB1. After a manifest replacement it refuses (identity changed), but it does not know RLDT1 supersession. Old binaries must be excluded operationally (CutoverReadiness reports an older class set as `UNKNOWN_WORKER_BINARY`). No dedicated old-binary execution test was run (**NOT RUN**).
- Rollback after commit (returning to an earlier generation) is not provided. A same-session rollback would violate the manifest monotonicity, and the old Session of a NEXT_SESSION is closed. All earlier records are kept for audit.
- Cloned roots or legacy anchors at the same paths on another host, network filesystems, and an administrator rewriting all evidence consistently: global fencing is **BLOCKED**.
- A copied session directory fails `NEW_MANIFEST_INVALID` because the segment physical identity differs from the manifest. That is evidence of a copy, not a fence.
- The same-session `SOURCE_UUID_REUSED` path is implemented but has no dedicated fixture (**NOT RUN**).
- Physical power loss and real ENOSPC are NOT RUN. Runtime.halt in a child JVM is not power loss.
