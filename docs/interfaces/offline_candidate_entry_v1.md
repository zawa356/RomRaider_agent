# Offline Candidate Entry v1 (RLOE1 / RLOP1 / RLSO1 / RLOR1) — Phase VII

`net.neristal.rrbridge.prototype.ingress.OfflineCandidateEntry` is the **single recommended offline candidate entry**. It is default-off and synthetic-only. It replaces `Rlc2IntegrationCli` as the main class approved by [`OfflineCandidatePreflight`](offline_candidate_preflight_v1.md) (review F1). `ProductBridgeWorker`, `ProductBridgeOnce`, `DurableSpool`, `ProductSender`, `Rlc2IntegrationCli` and other PoCs are **not** stopped, modified or replaced; they stay separately callable and are not fenced.

## Modes and exits

| Invocation | Effect | Exit |
|---|---|---|
| no args / unknown / extra args / any `--force` | nothing; `EXPLICIT_MODE_REQUIRED` | 2 |
| `--production-live` | always `PRODUCTION_LIVE_NOT_IMPLEMENTED` | 6 |
| `--diagnose PROFILE MANIFEST_SHA256 SOURCES_CSV` | read-only: profile/runtime match, ownership marker status, preflight. Always `state=BLOCKED`. | 5 (3 if a cooperative lock is busy) |
| `--synthetic-owned PROFILE MANIFEST_SHA256 SOURCES_CSV` | explicit synthetic run: supervisor checks profile, ownership, matched preflight, then starts **one** child JVM itself | 0 stored, 3 lock held, 5 refused |
| `--synthetic-owned-child PROFILE MANIFEST_SHA256 SOURCES_CSV NONCE` | internal: only valid for a supervisor-created one-shot RLOR1 run record | 0/3/5 |

There is no override flag and no environment-variable bypass. `SOURCES_CSV` is the explicit physical Source admission (exact set).

## RLOP1 launch profile

A private `key=base64` record (DeploymentBinding record format), CREATE_NEW, never overwritten (`createProfile`). Fields: `version=RLOP1`, `mode=SYNTHETIC_OWNED_ONLY`, `main_class` (must be this class), `reader` (`POSIX` or `WINDOWS_NATIVE_TEST_ONLY`), `user`, canonical `working_directory`, `data_root`, `java_executable`, exact ordered absolute `classpath` string, `artifact.*` (RLAP1 description: per-JAR SHA-256/size/class-set hash, Java specification, data model), `layout.*` (RLDB layout fields), `owner_root` (must equal `layout.root`), `expected_generation`, `profile_uuid`.

Each run verifies, in this order, before touching deployment data: record format/version/mode/main class/reader; JVM main command equals this class; **no JVM input arguments** (agents/options refused); user; working directory; java executable; data model (x86/x64); Java specification; exact classpath string; RLAP1 JAR order/hash/class-set comparison; owner root; refusal of real-car deployment paths (`OpenPortWork`, `RRBridge\dist`, RomRaider install). Every mismatch is a stable code (`MAIN_COMMAND_MISMATCH`, `JVM_OPTIONS_REFUSED`, `EXECUTION_USER_MISMATCH`, `WORKING_DIRECTORY_MISMATCH`, `JAVA_EXECUTABLE_MISMATCH`, `DATA_MODEL_MISMATCH`, `JAVA_VERSION_MISMATCH`, `CLASSPATH_STRING_MISMATCH`, `CLASSPATH_ORDER_CHANGED`, `CLASSPATH_ENTRY_CHANGED`, `UNKNOWN_OR_LEGACY_ENTRY_MAIN_CLASS`, `OWNER_ROOT_MISMATCH`, `PRODUCTION_PATH_REFUSED`, `PROFILE_MODE_UNSUPPORTED`, …).

These are a pinned *launch description* compared with what the JVM says about itself. They are not process authentication, signature or distribution authenticity.

## RLSO1 ownership marker

`<data_root>/.romlogger-synthetic-owned.rlso1`, created by the synthetic fixture generator (`createOwnedMarker`). It names the exact absolute paths of legacy v1, output, session directory, manifest and ledger, all strictly inside `data_root`, and the native physical identities (`DeploymentBinding.fileIdentity`) of all of them except the manifest. A legitimate rotation atomically replaces the manifest file; its content is bound by RLDB1/RLDT1 (exact bytes at generation 0, floor extension afterwards) and by the operator-supplied manifest SHA-256. The supervisor and child verify it **before any SQLite connection**: missing marker, foreign file (`DATA_OUTSIDE_OWNED_ROOT`), different path or a byte-identical but physically different file (`OWNED_MARKER_IDENTITY_MISMATCH`) is refused without inspecting the DB. It is a cooperative declaration of who generated the data, not authentication; a person can still forge it.

`--diagnose` reports `data_ownership` but still runs its read-only preflight on unowned data (no writable access).

## Supervisor / child

The supervisor removes `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS` and `CLASSPATH` from the child environment, starts exactly `java_executable -cp classpath OfflineCandidateEntry --synthetic-owned-child …` in `working_directory`, captures stdout/stderr (64 KiB bound each; overflow is `CHILD_INCOMPLETE`), waits up to 600 s (timeout kills the child), and reports `child_exit`, `child_timed_out`, `child_stdout_sha256`, `child_stderr_sha256`, `child_stderr_bytes` and `child_process_provenance=LAUNCHED_BY_SUPERVISOR_EXACT_COMMAND` (it knows only the command it started). The child self-reports its PID (`pid_self_reported`, a claim).

The child consumes a one-shot RLOR1 run record (`<data_root>/owned-runs/<nonce>.rlor1` + CREATE_NEW `.started`), re-verifies profile and ownership, re-runs the preflight (must be matched/WRITER_UNPROVEN), then writes **only** via `Rlc2IntegrationStore.ingestBoundActive`: Worker guard, Lane lock and Publication lock held exclusively; active generation re-checked at every store stage; explicit admission compared to the projection. After return the supervisor re-verifies ownership and generation.

## Output

Fixed codes, counts and hashes only; no paths, payload or session identifiers. Every result includes `synthetic_only=true`, `launch_authorized=false`, `production_authorized=false`, `ack_eligible=false`, `formal_ack=DISABLED`, `automatic_recovery=false`, `writer_quiescence=WRITER_UNPROVEN`. `STORED_SYNTHETIC_OWNED` means the candidate RLI2 file of this owned deployment was committed; it does not authorize launch, ACK or production use, and does not prove that no old or noncooperative writer exists. `directory_durability=UNKNOWN`.

## Not provided

Product live operation, automatic start, Sender connection, formal ACK, existing DB migration, global fencing, exclusion of arbitrary old binaries, Windows running-process provenance, Windows directory durability.
