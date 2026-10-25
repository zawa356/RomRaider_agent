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

## Update: changes that keep size and mtime (Phase IX-4, R9-04, 2026-10-21)

Before Phase IX the final re-check compared only size, mtime and the file set, so a same-size/same-mtime byte change
or a byte-identical physical replacement made after hashing was still reported `MATCHED` (reproduced:
`bash scripts/test-phase9-inventory-race.sh --expect-red` on the pre-fix code, 4 RED). Now, for every listed file, the
physical identity (`DeploymentBinding.fileIdentity`: POSIX dev/inode, NTFS volume serial + file ID) is read before
hashing; a JAR is re-hashed right after its entries are scanned (scanned bytes = hashed bytes, else stop); at the end every
listed file is re-hashed and its identity re-read. Any difference stops with `INPUT_CHANGED_DURING_INVENTORY`; an
unavailable identity stops with `FILE_IDENTITY_<code>`. Limits that remain: Java cannot hash and identify through one
handle, so a change-and-restore entirely between two reads is not excluded; the inventory is a snapshot, not runtime
fencing; Windows ACLs stay `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`; `global_old_binary_exclusion=NOT_PROVEN`.

## Finding F-IX-1: a real install with the entry's own dependencies cannot be MATCHED (2026-10-21, open)

The approved entry needs sqlite-jdbc (and slf4j; JNA for RLWR1). Inventoried as `dependency_jar`, these produce
`UNAPPROVED_MAIN_METHOD` (`com.sun.jna.Native`, `org.sqlite.util.OSInfo`), `UNAPPROVED_MANIFEST_MAIN` (sqlite-jdbc) and
`VERSIONED_OR_META_CLASS` (multi-release classes) → `MISMATCH`. Phase VIII's packaged-closure MATCHED covered the approved
JAR alone. This is **not relaxed**: options (an RLDI2 per-SHA-256 allow-list of known third-party entries, or
repackaging) are an owner/design decision. Pinned by `Phase9ReaderEntryTest`.

## Update: no file is written during inventory (CI-5 P2, 2026-10-21)

Phase IX `987e504` scanned each JAR from a private temp copy of the hashed bytes; that contradicted the read-only
contract and made the inventory UNAVAILABLE when no writable temp directory existed (RED:
`Phase9InventoryNoWriteTest`, child JVM with a non-existent `java.io.tmpdir`). The JAR is now parsed in memory from
exactly the hashed bytes (`ZipEntryView`: end record → central directory → local header at the recorded offset with the
same name → STORED/DEFLATED data with central sizes and CRC-32; ZIP64/encryption/other methods/inconsistency →
`JAR_UNREADABLE`). The A-B-A guarantee is unchanged (scanned bytes = hashed bytes); nothing is opened for writing or
created.

## Update: stricter in-memory ZIP parsing (Phase X-A-2, 2026-10-22)

The in-memory parser (`ZipEntryView`, since #103) now also refuses (`JAR_UNREADABLE`): local headers whose method/flags —
or, without a data descriptor, CRC/sizes — differ from the central directory (with a descriptor the local values may be
zero but never contradict); DEFLATE data that does not end exactly at the declared size (longer, unterminated, padded,
non-empty 0-byte); flags 0x40/0x2000 (as 0x1 before); control bytes in names, and non-ASCII names unless flagged UTF-8 and
valid UTF-8; overlapping entry spans. New finding **`JAR_MANIFEST_AMBIGUOUS`**: more than one case variant of
`META-INF/MANIFEST.MF`; every variant's `Main-Class`/agent attributes are inspected. Real sqlite-jdbc, slf4j, JNA and
JDK-made JARs read identically to `java.util.jar`. Test: `bash scripts/test-phase-xa-zip.sh` (`--expect-red` reproduced 15
accepted cases on the previous parser). F-IX-1 (third-party dependency mains) unchanged — not relaxed.

## Update: every JAR entry is verified (Phase X-B, 2026-10-22)

Phase X-A's parser inflated and CRC-checked only manifests and non-`META-INF` `.class` entries; every other entry
(resources, `META-INF/services`, signature files, native libraries such as sqlite-jdbc's `.so`/`.dll` or JNA's
`jnidispatch`, multi-release classes, directories) was checked only for header consistency. RLDI1 now verifies **every
entry** before any finding is computed:

- declared uncompressed size ≤ 16 MiB per entry (`JAR_ENTRY_EXPANSION_BOUND`) and ≤ 128 MiB summed per JAR
  (`JAR_TOTAL_EXPANSION_BOUND`), checked before inflating (refusal → inventory `UNAVAILABLE`, fail closed);
- STORED: compressed size = declared size; DEFLATED: the stream yields exactly the declared size (inflation stops at
  declared + 1 byte, so a lying stream is never expanded further), the stream ends there and no input remains;
- CRC-32 equals the central value; a directory entry (`…/`) must be empty;
- streaming with a fixed 64 KiB buffer; entry content is never held in memory for this check.

Any violation is `JAR_UNREADABLE` → `MISMATCH`. Real dependencies (sqlite-jdbc 3.46.1.3: largest entry 1.3 MB, 25.7 MB
total; JNA 5.14.0: 5.5 MB total; slf4j) verify without findings of this kind. Reproduced before the fix: 12/12 malformed
or oversized non-class cases were MATCHED (`bash scripts/test-phase-xb-zip.sh --expect-red` on `2c8a5a3`). Unchanged:
SHA-256 pin per listed file, F-IX-1 (third-party mains still MISMATCH), no RLDI2 allow-list, launch/production/ACK
disabled, Windows ACL UNKNOWN, global old-binary exclusion NOT_PROVEN.

## Note: Phase X-C (2026-10-25) — RLDI1 unchanged; RLDI2 candidate is separate

RLDI1 code (`DistributionInventory.java`), fixtures and JSON are unchanged by Phase X-C. F-IX-1 is now reproduced
exactly with the real pinned JARs (`scripts/test-phase-xc-rldi2.sh`, `PhaseXcF1ReproTest`): sqlite-jdbc →
`UNAPPROVED_MAIN_METHOD` `org.sqlite.util.OSInfo` + 4 `VERSIONED_OR_META_CLASS`; slf4j-api / slf4j-nop → 1
`VERSIONED_OR_META_CLASS` each; JNA → `UNAPPROVED_MAIN_METHOD` `com.sun.jna.Native` + `UNAPPROVED_MANIFEST_MAIN`.
Correction to the F-IX-1 paragraph above (kept as written): the manifest `Main-Class` finding comes from **JNA**, not
sqlite-jdbc (sqlite-jdbc's manifest has no `Main-Class`). The synthetic-only, default-off RLDI2 candidate that can
report such an install MATCHED (still BLOCKED) is specified in [distribution_inventory_v2.md](distribution_inventory_v2.md);
RLDI1 keeps reporting it MISMATCH.
