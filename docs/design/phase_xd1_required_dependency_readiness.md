# Phase X-D1 — RLDR1 required-dependency readiness: design and threat model

Status: **candidate, synthetic-only, default-off, Java API only** (Claude Code, 2026-10-26 JST). Base: Agent `main`
`bcc81566b2c90b61de88ec0fb7fb3bf43631e0f9`. Reviews the ChatGPT draft in Draft PR #107
(`docs/design/phase_xd_dependency_readiness.md`, not merged) against the code; that draft stays untouched here — this file
is the implementation-side design and names where it differs. Nothing here authorizes launch, production, ACK, reader
adoption or distribution.

## 1. Question answered

RLDI2 (`DistributionInventoryV2`) answers "is every file **present** under the install root listed, with the recorded
role and bytes, and is every `dependency_jar` a compiled-in pin?". It explicitly does **not** answer (RLDI2 contract,
"Responsibility boundary"): completeness, the per-profile required set, the launch class path, freshness.

RLDR1 answers exactly one further question, for one RLOP1 launch profile and one RLDI2-inventoried install:

> Does the profile's exact ordered class path consist only of files of this install, starting with its one approved
> JAR, followed by dependency JARs that are exactly the set the profile's reader needs (required pins present, forbidden
> pins absent, optional groups complete), all byte-identical to the inventory, during one bounded inspection?

A `MATCHED` answer is still `state=BLOCKED` with every authorization flag false. It says nothing about the running
process, the runtime, ACLs, the writer, other copies on disk, or the future.

## 2. Code-derived required sets (source evidence, not assumption)

| Fact | Source (this repository at `bcc8156`, or the pinned JAR) |
|---|---|
| The entry closure opens SQLite through JDBC (`Class.forName("org.sqlite.JDBC")`, `jdbc:sqlite:`) | `IngressDiagnostics`, `Rlc2IntegrationStore`/`Rlc2PreviewStore` path, product `DurableSpool`, `V1LaneMigration`, `CutoverReadiness`, `ProductIngressStatus` (`grep org.sqlite`/`DriverManager`) |
| No first-party class references `org.slf4j` | `grep -rl org.slf4j src/main/java` → none |
| sqlite-jdbc 3.46.1.3 logs through `org.sqlite.util.LoggerFactory`: `Class.forName("org.slf4j.Logger")`, on failure `JDKLogger` (java.util.logging) | `javap -c org/sqlite/util/LoggerFactory.class` from the pinned JAR `4a483272…` |
| slf4j-api 2.0.x without a provider prints a "no providers" warning on stderr and falls back to NOP; `slf4j-nop` is a provider and is useless without the API | upstream SLF4J 2.0 behaviour; **measured** in this phase (`PhaseXdClosureProbe`, evidence) |
| JNA is reached only by name (`WindowsProductReader.BINDING` → `WindowsProductReaderJna`) and only for reader `WINDOWS_NATIVE_V1_SYNTHETIC`; `productReader()` refuses the test reader class and requires the JNA code source SHA-256 = 5.14.0 pin | `OfflineCandidateEntry.reader()/productReader()`, `WindowsProductReader.create` |
| `knownReader()` accepts **three** values: `POSIX`, `WINDOWS_NATIVE_TEST_ONLY`, `WINDOWS_NATIVE_V1_SYNTHETIC` | `OfflineCandidateEntry.knownReader` (the RLOP1 summary in `offline_candidate_entry_v1.md` lists two — documentation drift, corrected on the docs track) |
| `WINDOWS_NATIVE_TEST_ONLY` loads `WindowsWatermarkV2Test.READER` reflectively from a **test** JAR on the class path | `OfflineCandidateEntry.reader()`; `scripts/phase7-packaged.sh` |

Resulting compiled-in policy `RLDR1-REQUIRED-SET-POLICY-1` (reviewed code; nothing at run time can change it):

| RLOP1 reader | required pins | optional groups (all-or-none) | forbidden pins | profile policy |
|---|---|---|---|---|
| `POSIX` | `sqlite-jdbc-3.46.1.3` | {`slf4j-api-2.0.16`, `slf4j-nop-2.0.16`} | `jna-5.14.0` | evaluated |
| `WINDOWS_NATIVE_V1_SYNTHETIC` | `sqlite-jdbc-3.46.1.3`, `jna-5.14.0` | {`slf4j-api-2.0.16`, `slf4j-nop-2.0.16`} | — | evaluated (reader adoption stays BLOCKED) |
| `WINDOWS_NATIVE_TEST_ONLY` | — | — | — | `BLOCKED` `TEST_ONLY_READER_PROFILE`: a test JAR is not an inventory role; never MATCHED |
| anything else | — | — | — | `MISMATCH` `READER_UNSUPPORTED` |

Why the SLF4J pair is an all-or-none group (provisional, for review): absent → sqlite-jdbc uses JUL (works, measured);
api without nop → works but writes a provider warning to stderr, which changes the supervisor-captured child stderr
digest; nop without api → orphan provider. The repository's canonical closure (`phase7-packaged.sh`) ships both. Whether
the product should ship SLF4J at all is **OWNER_DECISION_REQUIRED**; RLDR1 accepts both shapes and refuses the partial ones.

Why JNA is forbidden for `POSIX`: the POSIX reader never reaches JNA; an unused JAR that carries native `jnidispatch`
binaries is attack surface without function. This is stricter than the #107 draft ("JNA not automatically required") —
it also says "not allowed".

## 3. Inputs, checks and axes

`DependencyReadiness.inspect(enable, profile, installRoot, rldi2Record[, hook])`. Disabled → `NOT_RUN`, nothing read.

1. **freshness/start:** SHA-256 and physical identity (`DeploymentBinding.fileIdentity`) of the RLOP1 profile and RLDI2
   record taken first (identity added 2026-10-27, review P2-2).
2. **profile_policy:** RLOP1 read with `DeploymentBinding.readRecord(safe(...))`; version, mode, main class, known reader,
   the **same strict field set** as `OfflineCandidateEntry.verifyProfile` (duplicated read-only; the entry is not touched),
   required fields present; `expected_generation` parsed and range-checked and production-looking paths refused by
   RLOP1's own package-private code (`OfflineCandidateEntry.Profile`, `requireNotProduction`; called, not copied — added
   2026-10-27, review P2-1). Runtime facts are *not* checked (this is not the launched JVM) → see `process_provenance`.
3. **inventory:** a fresh `DistributionInventoryV2.inspect` of the install (never a saved report). Not `MATCHED` →
   `required_set`/`classpath` are `UNKNOWN` `INVENTORY_NOT_MATCHED` (no trust in an unverified record).
4. **classpath:** `ArtifactProvenance.parse(profile.classpath)`; every element absolute, normalized and **equal to its
   canonical path** (refuses symlink/junction/8.3/`..` aliases), strictly inside the **canonical** install root, listed in
   the RLDI2 record, role `approved_jar` or `dependency_jar` only; no duplicate; element 0 is the single approved JAR;
   current SHA-256 = record SHA-256; every `dependency_jar` of the inventory appears on the class path (exact set, no
   unused dependency in the install); RLAP1 description of the class path = the profile's `artifact.*` entries (order,
   hash, size, class set; RLAP1's own codes, e.g. `CLASSPATH_ORDER_CHANGED`).
5. **required_set:** pins on the class path (from the record pin id, already re-derived from bytes by RLDI2) vs. the
   policy row: `REQUIRED_PIN_MISSING`, `FORBIDDEN_PIN_PRESENT`, `OPTIONAL_GROUP_INCOMPLETE`, `DEPENDENCY_NOT_PINNED`.
6. **freshness/end:** profile and record SHA-256 and identity unchanged since step 1; every class-path JAR re-hashed and
   its file identity unchanged since step 4 → otherwise (including unreadable / identity unavailable)
   `INPUT_CHANGED_DURING_READINESS`. This is a snapshot of one inspection, not lasting safety. The result is
   `CURRENT_INSPECTION_ONLY`: no stored result is ever accepted, and none is valid later.
7. **fixed axes:** `process_provenance` `UNKNOWN`/`NOT_INSPECTED`; `access_control` from RLDI2 (`UNKNOWN` on Windows);
   `writer` `UNKNOWN`/`WRITER_UNPROVEN`.

`dependency_readiness = MATCHED_NOT_AUTHORIZATION` only when profile_policy, inventory, classpath, required_set and
freshness are all `MATCHED`. `state` is always `BLOCKED`.

## 4. Output

Fixed key set, codes from compiled-in sets, pin ids from the compiled-in policy, reader only if it is one of the three
known values (else `UNKNOWN`). No path, file name, hash, user or profile field value is printed. Every string goes
through `DistributionInventory.jsonString` (review P2 of X-C). Always:
`state=BLOCKED, synthetic_only=true, launch_authorized=false, production_authorized=false, ack_eligible=false,
formal_ack=DISABLED, reader_adoption=BLOCKED, global_old_binary_exclusion=NOT_PROVEN`.

## 5. Threat model (what RLDR1 does and does not stop)

| Attack / fault | Handling |
|---|---|
| required JAR removed from install or class path | inventory/`LISTED_FILE_MISSING` or `REQUIRED_PIN_MISSING` |
| unknown / modified / repacked / other-version dependency | RLDI2 `RLDI2_DEPENDENCY_NOT_PINNED` (inventory MISMATCH) |
| JNA added to a POSIX profile | `FORBIDDEN_PIN_PRESENT` |
| only one SLF4J half | `OPTIONAL_GROUP_INCOMPLETE` |
| class-path entry outside the install, alternate identical copy, symlink/junction/8.3 alias | `CLASSPATH_OUTSIDE_INSTALL` / `CLASSPATH_PATH_NOT_CANONICAL` |
| entry not in record, wrong role (launcher/document/profile) | `CLASSPATH_NOT_INVENTORIED` / `CLASSPATH_ROLE_NOT_ALLOWED` |
| order swap, removed/added entry vs. pinned profile | RLAP1 `CLASSPATH_ORDER_CHANGED` / `CLASSPATH_ENTRY_COUNT_CHANGED` / `CLASSPATH_ENTRY_CHANGED` |
| approved JAR not first | `APPROVED_JAR_NOT_FIRST` |
| dependency in install but not on the class path | `INVENTORY_DEPENDENCY_UNUSED` |
| JAR / record / profile replaced during inspection (TOCTOU) | `INPUT_CHANGED_DURING_READINESS` (hook-driven tests) |
| smuggled profile field (`launch_authorized=true`) | `PROFILE_UNKNOWN_FIELD` |
| hostile names in JAR entries reaching JSON | only fixed codes/pin ids printed; escaping kept |

**Not stopped (stays UNKNOWN/BLOCKED):** a JVM started with another class path or options (process provenance), JVM
runtime substitution, Windows ACL weaknesses, other copies of old binaries elsewhere, a change after the inspection
returns, a person who controls the inspector source. These remain separate non-PASS gates.

## 6. Differences from the #107 draft

- Required/optional/forbidden table derived and fixed (draft: `TO_DERIVE`); SLF4J optional pair, JNA forbidden for POSIX.
- RLDR1 runs RLDI2 itself instead of accepting "already validated RLDI2 metadata" (draft §2) — a passed-in snapshot
  could be stale or forged; freshness then needs no time window.
- `WINDOWS_NATIVE_TEST_ONLY` is profile-policy `BLOCKED` (draft: `TO_DERIVE`).
- Unused dependency in the install is a mismatch (exact set), not just "extra on the class path".

## 7. Not done (deliberately)

No CLI flag, no `OfflineCandidateEntry`/launcher/reader/Sender/ACK wiring, no change to RLDI1/RLDI2/RLOP1/RLAP1 code or
output, no shared-walker refactor. Adoption, SLF4J shipping, JNA distribution, an `--readiness-rldr1` entry flag:
`OWNER_DECISION_REQUIRED`.
