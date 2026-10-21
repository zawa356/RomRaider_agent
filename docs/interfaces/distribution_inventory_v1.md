# Distribution Inventory v1 (RLDI1) — Phase VIII-1

`net.neristal.rrbridge.prototype.ingress.DistributionInventory`, reachable read-only through the single entry as
`OfflineCandidateEntry --inventory INSTALL_DIR RLDI1_RECORD`. Synthetic acceptance only. It never launches, writes,
repairs, deletes, renames or quarantines anything, and never sets `launch_authorized`, `production_authorized` or
`ack_eligible`.

## Purpose and limit

Phase VII fixed the *approved entry* (review F1) but did not fence old binaries: `Rlc2IntegrationCli`,
`ProductBridgeWorker/Once` and others stay callable from any classpath. RLDI1 is the read-only half of an
**isolated install candidate**: it proves (or refutes) that *one install directory* contains exactly the pinned files
and no Java entry other than `OfflineCandidateEntry`.

A `MATCHED` inventory does **not** prove old-binary exclusion in general. Copies elsewhere on the host, another user
or administrator, another host, and `java -cp <anything>` started by hand are out of reach. The JSON always carries
`"global_old_binary_exclusion":"NOT_PROVEN"`; **this stays a D2 acceptance blocker**.

## Record (private `key=base64`, DeploymentBinding record format, outside the install directory)

| key | value |
|---|---|
| `version` | `RLDI1` |
| `mode` | `SYNTHETIC_ONLY` |
| `approved_main` | must equal `OfflineCandidateEntry.MAIN` |
| `files` | N (1..4096); the record has exactly `4+3N` keys |
| `file.NNNN.path` | relative path, `/`-separated, `[A-Za-z0-9_.-]` segments, no `.`/`..`, unique |
| `file.NNNN.sha256` | lowercase hex SHA-256 (read bound 64 MiB, enforced while reading) |
| `file.NNNN.role` | `approved_jar` \| `dependency_jar` \| `launcher` \| `profile` \| `document` |

`DistributionInventory.describe(root, approvedMain, roles)` writes the description of a directory as it is; it is a
packaging step, not an approval.

## Checks (all read-only; every finding is a stable code, counted)

| Finding | Meaning |
|---|---|
| `LINK_OR_SPECIAL_IN_DISTRIBUTION` | symlink, Windows junction/reparse point or special file anywhere (never followed) |
| `UNLISTED_FILE` / `UNLISTED_EXECUTABLE_OR_ARCHIVE` | regular file not in the record (launcher/archive extensions reported separately) |
| `LISTED_FILE_MISSING`, `FILE_HASH_MISMATCH`, `FILE_BOUND` | pinned file absent / different / too large |
| `ROLE_DOES_NOT_MATCH_FILE` | `*_jar` role on a non-JAR, launcher role on a non-launcher, or the reverse |
| `APPROVED_JAR_COUNT`, `APPROVED_MAIN_MISSING` | not exactly one `approved_jar`, or it does not declare the approved `main` |
| `UNAPPROVED_MAIN_METHOD` | any class in any JAR declaring `public static void main(String[])` other than the approved entry (class names listed in `unapproved_mains`; malformed class files count as mains) |
| `LEGACY_ENTRY_CLASS_PRESENT` | a known pre-Phase VII entry class (`Rlc2IntegrationCli`, `Rlc2PreviewCli`, `ProductBridgeWorker`, `ProductBridgeOnce`, `V1LaneMigration`, `LegacyCandidateGate`, `Receiver`) — listing it in the record does not bless it |
| `UNAPPROVED_MANIFEST_MAIN`, `JAR_MANIFEST_CLASSPATH_OR_AGENT` | `Main-Class` other than the approved entry in the approved JAR; any `Class-Path`, `Launcher-Agent-Class`, `Premain-Class`, `Agent-Class`, `Boot-Class-Path` |
| `NESTED_ARCHIVE`, `DUPLICATE_CLASS`, `JAR_DUPLICATE_ENTRY`, `VERSIONED_OR_META_CLASS`, `JAR_UNREADABLE` | archive shape that can hide or shadow code |
| `WRITABLE_BY_OTHERS`, `OWNER_DIFFERS` | POSIX only: any file or directory up to the install root writable by group/other or owned by another user |

Refusals (`inventory=UNAVAILABLE`): `INVENTORY_DISABLED` (NOT_RUN), `INVENTORY_PATH_NOT_ABSOLUTE`,
`DISTRIBUTION_ROOT_NOT_DIRECTORY`, `RECORD_INSIDE_DISTRIBUTION`, `INVENTORY_VERSION/MODE/FORMAT/PATH_INVALID/ROLE_INVALID/DUPLICATE_PATH`,
`APPROVED_MAIN_NOT_THE_OFFLINE_ENTRY`, `DISTRIBUTION_DEPTH_BOUND` (16), `DISTRIBUTION_ENTRY_BOUND` (4096),
`DISTRIBUTION_UNREADABLE`, `INPUT_CHANGED_DURING_INVENTORY` (record bytes, file set, sizes or mtimes changed between the
scan and the final re-read), DeploymentBinding path-safety codes, `INVENTORY_UNAVAILABLE` plus
`inventory_cause:<CODE|Class>` (never free text).

`access_control`: `POSIX_OWNER_ONLY_WRITE` / `POSIX_MISMATCH`; on Windows `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED` (ACLs are
not inspected; a Windows MATCHED says nothing about who else can write there).

## JSON

`{"contract_version":1,"inventory_version":"RLDI1","state":"BLOCKED","inventory":"MATCHED|MISMATCH|UNAVAILABLE|NOT_RUN",
"reason":…,"files":N,"findings":{code:count},"unapproved_mains":[class names],"access_control":…,
"global_old_binary_exclusion":"NOT_PROVEN","synthetic_only":true,"launch_authorized":false,"production_authorized":false,"ack_eligible":false}`.
No paths, file names or hashes appear.

## Isolated install candidate (design; nothing here is deployed)

1. Build the entry closure JAR (`scripts/phase7-packaged.sh` / `scripts/test-phase8-inventory.sh` do the same `javac` closure).
2. Create a new private install directory owned by the operating user; copy only: the closure JAR (`approved_jar`), pinned dependency JARs (`dependency_jar`), one launcher, the RLOP1 profile(s).
3. `describe` → RLDI1 record stored **outside** the install directory (next to the deployment root records).
4. Before any synthetic run: `--inventory` must be `MATCHED`; then `--diagnose`; the RLOP1 profile pins the same JAR hashes.
5. Old binaries: the operator moves any previous `rrbridge*.jar`/Worker launchers into a separate quarantine directory that is **not** on any launcher's classpath and not inside the install directory; RLDI1 then reports it absent here. Recovery is moving them back; nothing is deleted.
6. Data root / layout access: unchanged RLDB/RLSO1 private-access rules; the install directory itself holds no data.

## Current packaged closure (honest finding, 2026-10-20)

The current `OfflineCandidateEntry` dependency closure contains **no known legacy entry class**, but four other classes
declare `main`: `ArtifactProvenance`, `DeploymentBinding`, `DeploymentTransition`, `IngressDiagnostics`. RLDI1 therefore
reports `MISMATCH UNAPPROVED_MAIN_METHOD` for the real package. Removing those mains (moving their CLIs to separate
tool JARs) is required before an install candidate can be `MATCHED`; until then this remains BLOCKED.

## Update: secondary mains moved out (Phase VIII-1b, 2026-10-20)

The four CLIs above moved to `ArtifactProvenanceCli`, `DeploymentBindingCli`, `DeploymentTransitionCli` and
`IngressDiagnosticsCli`, which the entry does not reference. The real closure built by
`javac -sourcepath src/main/java OfflineCandidateEntry.java` now has no unapproved main and RLDI1 reports it `MATCHED`
(`scripts/test-phase8-inventory.sh` now requires this). `MATCHED` is still only an inventory of this directory: it is not
launch permission, not production authorization and not proof that old binaries elsewhere are excluded.

## Update: explicit offline tool build (Phase IX-1, R9-01, 2026-10-21)

The four tool CLIs were not compiled by any build or test script at 95a4a30/ab32e6b5 (no test referenced
`IngressDiagnosticsCli`, so a clean `scripts/test-ingress-diagnostics.sh` build produced no CLI class and the documented
`java … IngressDiagnosticsCli` failed with `ClassNotFoundException`). They are now built explicitly:

```bash
bash scripts/build-offline-tools.sh [OUT_DIR]   # default build/offline-tools → romlogger-offline-tools.jar (no Main-Class, no JNA, no test classes)
```

The tool JAR is a separate artifact. It must not be placed in an approved entry install: RLDI1 reports such a directory
`MISMATCH` (four `UNAPPROVED_MAIN_METHOD` + `DUPLICATE_CLASS`). `scripts/test-phase9-cli.sh` builds it in its own stage,
then starts every CLI as a real child JVM with classpath = tool JAR + pinned dependencies only (usage exit 2, refused
exit 5, synthetic success exit 0 where the CLI does not need a native reader, no path/canary leak), and proves the
co-installed layout is MISMATCH while the entry JAR alone is MATCHED (still BLOCKED). The JAR is not byte-reproducible
(JAR entry timestamps); its SHA-256 is printed per build. `IngressDiagnosticsCli` healthy-session and
`DeploymentTransitionCli --plan-synthetic` use the POSIX reader; on Windows their exit 0 is not claimed.
Launch environments must not carry `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`/`JDK_JAVA_OPTIONS` (the JVM honours them before
any CLI code runs); the CLIs cannot refuse them themselves.
