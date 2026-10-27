# Dependency Readiness v1 (RLDR1) — Phase X-D1 synthetic-only candidate

`net.neristal.rrbridge.prototype.ingress.DependencyReadiness`. **Default-off:** reachable only through its Java API
(`inspect`) from tests. Not wired into `OfflineCandidateEntry`, any CLI, launcher, reader, Sender, ACK path or runbook; no
default changed. Read-only: never launches, writes, repairs, deletes, renames or quarantines anything. Design and threat
model: [phase_xd1_required_dependency_readiness.md](../design/phase_xd1_required_dependency_readiness.md).

RLDR1 adds the axis that [RLDI2](distribution_inventory_v2.md) leaves out ("Responsibility boundary"): the per-profile
required dependency set and the launch class path. RLDI1, RLDI2, RLOP1 and RLAP1 code and output are unchanged.

## API

`DependencyReadiness.inspect(boolean enable, File rlop1Profile, File installRoot, File rldi2Record)` (and an overload
with a `DistributionInventory.Hook` for tests; stages `PROFILE_READ`, `INVENTORY_DONE`, `CLASSPATH_HASHED`).
All three paths must be absolute. `enable=false` → `NOT_RUN` / `READINESS_DISABLED`, nothing is read.

## Policy `RLDR1-REQUIRED-SET-POLICY-1` (compiled in; pin ids are RLDI2 compiled-in pins)

| RLOP1 `reader` | required | optional group (all-or-none) | forbidden | profile_policy |
|---|---|---|---|---|
| `POSIX` | `sqlite-jdbc-3.46.1.3` | `slf4j-api-2.0.16` + `slf4j-nop-2.0.16` | `jna-5.14.0` | `MATCHED` |
| `WINDOWS_NATIVE_V1_SYNTHETIC` | `sqlite-jdbc-3.46.1.3`, `jna-5.14.0` | `slf4j-api-2.0.16` + `slf4j-nop-2.0.16` | — | `MATCHED` (reader adoption stays BLOCKED) |
| `WINDOWS_NATIVE_TEST_ONLY` | — | — | — | `BLOCKED` `TEST_ONLY_READER_PROFILE` |
| other | — | — | — | `MISMATCH` `READER_UNSUPPORTED` |

Validated at class load (known reader, every id an RLDI2 pin, no id in two sets, groups ≥ 2, a blocked rule has no
required/optional pins); an invalid policy prevents the class from loading.

## Checks, in order

1. **profile_policy** — RLOP1 read through `DeploymentBinding.readRecord(safe(...))`; same static checks and strict field
   set as `OfflineCandidateEntry.verifyProfile` (`PROFILE_VERSION`, `PROFILE_MODE_UNSUPPORTED`,
   `UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS`, `READER_UNSUPPORTED`, `PROFILE_FORMAT`, `PROFILE_UNKNOWN_FIELD`,
   `OWNER_ROOT_MISMATCH`, `PRODUCTION_PATH_REFUSED`, `PROVENANCE_VERSION`; unreadable → `UNKNOWN` `PROFILE_UNREADABLE`,
   record format refusal → `PROFILE_<code>`, identity unavailable → `UNKNOWN` `PROFILE_IDENTITY_UNAVAILABLE`).
   `expected_generation` is parsed by RLOP1's own `Profile` code (32-bit decimal, ≥ 0, else `PROFILE_FORMAT`) and the
   production-path rule is RLOP1's own `requireNotProduction` (2026-10-27, review P2-1). Runtime facts are not compared
   (this is not the launched JVM).
2. **inventory** — a fresh `DistributionInventoryV2.inspect`; status `MATCHED` / `MISMATCH` / `UNKNOWN` (UNAVAILABLE), code =
   RLDI2 reason. Not MATCHED → `classpath` and `required_set` `UNKNOWN` `INVENTORY_NOT_MATCHED`. Record unreadable →
   `UNKNOWN` `RECORD_UNREADABLE`; record identity unavailable (e.g. a hard-linked record) → `UNKNOWN`
   `RECORD_IDENTITY_UNAVAILABLE`.
3. **classpath** — `ArtifactProvenance.parse` of the profile class path, then per element: absolute; real path
   (`toRealPath`: links, junctions, 8.3 names, case) equal to the written, normalized path
   (`CLASSPATH_PATH_NOT_CANONICAL`); strictly inside the install root, whose own real path must equal it
   (`CLASSPATH_OUTSIDE_INSTALL`, `INSTALL_ROOT_NOT_CANONICAL`); no repeat (`CLASSPATH_DUPLICATE_ENTRY`); listed in the
   RLDI2 record (`CLASSPATH_NOT_INVENTORIED`); role `approved_jar`/`dependency_jar` only (`CLASSPATH_ROLE_NOT_ALLOWED`);
   approved JAR at position 0 and nowhere else (`APPROVED_JAR_NOT_FIRST`). Then every `dependency_jar` of the install is
   on the class path (`INVENTORY_DEPENDENCY_UNUSED`), and the RLAP1 description of the elements equals the profile's
   `artifact.*` (RLAP1 codes such as `CLASSPATH_ORDER_CHANGED`, `CLASSPATH_ENTRY_CHANGED`, `PROVENANCE_FIELD_CHANGED`).
   Parse refusals keep their RLAP1 code (`CLASSPATH_EMPTY_ELEMENT`, …).
4. **required_set** — record pin ids of the class-path dependencies (already re-derived from bytes by RLDI2) against the
   reader rule: `REQUIRED_PIN_MISSING`, `FORBIDDEN_PIN_PRESENT`, `OPTIONAL_GROUP_INCOMPLETE`,
   `PIN_NOT_ALLOWED_FOR_PROFILE`, `DEPENDENCY_NOT_PINNED` (first in this order is the code; all are counted in
   `findings`). Blocked profile → `NOT_RUN` `PROFILE_POLICY_BLOCKED`.
5. **freshness** — SHA-256 + physical identity (`DeploymentBinding.fileIdentity`) of profile and record at start and
   end, record also right after the inventory, and of every class-path JAR at hashing time and at the end (profile and
   record identity added 2026-10-27, review P2-2: a byte-identical replacement is a different file). Unreadable or
   identity unavailable at the end counts as changed:
   `MATCHED` `CURRENT_INSPECTION_ONLY`, else `MISMATCH` `INPUT_CHANGED_DURING_READINESS`. Nothing bound (early stop) →
   `UNKNOWN` `NOTHING_BOUND`. No stored result is accepted, and none stays valid.
6. **fixed** — `process_provenance` `UNKNOWN` `NOT_INSPECTED`; `writer` `UNKNOWN` `WRITER_UNPROVEN`; `access_control` from
   RLDI2 (`MATCHED POSIX_OWNER_ONLY_WRITE`, `MISMATCH POSIX_MISMATCH`, otherwise `UNKNOWN`, e.g.
   `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`).

`dependency_readiness` = `MATCHED_NOT_AUTHORIZATION` (reason `DEPENDENCY_READINESS_MATCHED_NOT_AUTHORIZATION`) only if
freshness, profile_policy, inventory, classpath and required_set are all `MATCHED`; otherwise the first of
`MISMATCH`/`BLOCKED`/`UNKNOWN`/`NOT_RUN` over those axes (in that order) with that axis' code. An unexpected exception →
`UNKNOWN` `READINESS_UNAVAILABLE` with `findings` key `cause:<ExceptionSimpleName>` (never a message or path).

## JSON (fixed keys)

```
{"contract_version":1,"readiness_version":"RLDR1","policy":"RLDR1-REQUIRED-SET-POLICY-1","state":"BLOCKED",
 "dependency_readiness":"MATCHED_NOT_AUTHORIZATION|MISMATCH|BLOCKED|UNKNOWN|NOT_RUN","reason":CODE,
 "reader":"POSIX|WINDOWS_NATIVE_TEST_ONLY|WINDOWS_NATIVE_V1_SYNTHETIC|UNKNOWN",
 "axes":{"profile_policy":{"status":S,"code":C},"inventory":…,"classpath":…,"required_set":…,"freshness":…,
         "process_provenance":…,"access_control":…,"writer":…},
 "required_pins":[pin ids],"observed_pins":[pin ids],"findings":{CODE:count},
 "reader_adoption":"BLOCKED","global_old_binary_exclusion":"NOT_PROVEN","formal_ack":"DISABLED","synthetic_only":true,
 "launch_authorized":false,"production_authorized":false,"ack_eligible":false}
```

Codes are reduced to `[A-Z0-9_]{1,64}` (anything else → `CODE_INVALID`); the reader is printed only if it is one of the
three known values. Every string goes through `DistributionInventory.jsonString` (RFC 8259, printable ASCII). No path,
file name, hash, user, UUID or profile value is printed.

## Not provided

Launch, process authentication (Windows or Linux), runtime/JVM-option verification, ACL inspection on Windows, writer
quiescence, exclusion of other copies on disk, any validity after `inspect` returns. Test:
`bash scripts/test-phase-xd-readiness.sh` (`--run-only` under actual Java 8 x86; `--expect-red` against the stub).
