# RLDI2 dependency-JAR policy — design (Phase X-C1, 2026-10-25)

Status: **synthetic-only candidate, default-off, not adopted.** Contract: [distribution_inventory_v2.md](../interfaces/distribution_inventory_v2.md).
RLDI1 ([distribution_inventory_v1.md](../interfaces/distribution_inventory_v1.md)) is unchanged and stays the strict
reference. Owner decisions: [phase_xc_owner_decisions.md](../decisions/phase_xc_owner_decisions.md).

## 1. Problem (F-IX-1, measured again at `7fdb4db`)

The approved entry `OfflineCandidateEntry` needs third-party JARs at runtime: sqlite-jdbc (journal/receipt SQLite),
slf4j-api + slf4j-nop (sqlite-jdbc's logging binding) and, for the RLWR1 Windows reader only, JNA. RLDI1 inventories every
JAR in the install the same way, so an install that lists them as `dependency_jar` is `MISMATCH`. Findings per pinned JAR
(measured with the RLDI1 parser itself, this session):

| JAR (pinned SHA-256) | RLDI1 findings it causes | Why the JAR has it |
|---|---|---|
| sqlite-jdbc 3.46.1.3 (`4a483272…b0b4fa`, 14 127 503 B, 214 entries) | `UNAPPROVED_MAIN_METHOD` `org.sqlite.util.OSInfo`; `VERSIONED_OR_META_CLASS` ×4 (`META-INF/versions/9/module-info.class`, `…/org/sqlite/nativeimage/SqliteJdbcFeature{,$1,$SqliteJdbcFeatureException}.class`) | `OSInfo.main` prints the native-library folder name (diagnostic CLI); multi-release classes for JPMS and GraalVM native-image. Manifest has **no** `Main-Class` (the F-IX-1 text that attributed `UNAPPROVED_MANIFEST_MAIN` to sqlite-jdbc was inaccurate). 24 native libraries (`org/sqlite/native/**/*.{dll,so,jnilib}`) produce no finding but are CRC/DEFLATE-verified like every entry |
| slf4j-api 2.0.16 (`a12578dd…4c579a`, 69 435 B, 71 entries) | `VERSIONED_OR_META_CLASS` ×1 (`META-INF/versions/9/module-info.class`) | JPMS descriptor |
| slf4j-nop 2.0.16 (`deca6c04…4a9ba7`, 4 982 B, 17 entries) | `VERSIONED_OR_META_CLASS` ×1 (`META-INF/versions/9/module-info.class`) | JPMS descriptor |
| JNA 5.14.0 (`34ed1e1f…e8c6`, 1 878 533 B, 188 entries) | `UNAPPROVED_MAIN_METHOD` `com.sun.jna.Native`; `UNAPPROVED_MANIFEST_MAIN` (`Main-Class: com.sun.jna.Native`) | `Native.main` prints version information. Contains `jnidispatch` for 30+ platforms (`com/sun/jna/<os-arch>/…`) |

None of the four declares `Class-Path`, an agent attribute, a nested archive, a known legacy entry, a JAR signature, a
manifest case variant or a duplicate class. These four exact JARs are the whole scope of the policy.

RLDI1's refusal is **intentional fail-closed behaviour, not a defect**: it cannot tell a reviewed third-party `main` from
an injected one. Skipping the checks for dependencies is forbidden by the X-C instructions and by this design.

## 2. Threat model

Assets: the install directory must contain no Java entry other than the approved one, and nothing that can shadow or
hijack its classes, so that the RLOP1 launch profile and RLDI inventory describe what really runs.

| Adversary / failure | Example | Must result in |
|---|---|---|
| Unknown or modified dependency | rebuilt sqlite-jdbc with an extra class; 1-byte change; same file name, other bytes; another version | not exceptable (`RLDI2_DEPENDENCY_NOT_PINNED`) + every RLDI1 finding of its content |
| Self-approval | record lists a new "pin", changes the pin id, adds policy keys, edits `policy_sha256` | refusal or finding; the record can never create or widen an exception |
| Privilege transfer | pinned JNA bytes listed as `approved_jar`, `launcher`, or under another name/path; tool JAR as dependency | no exception outside `dependency_jar` + exact file name; tool JAR = unpinned |
| Hiding code in a pinned JAR | legacy entry class, extra main, manifest `Class-Path`/agent, nested archive, case-variant manifest, duplicate/shadow class, extra multi-release class | impossible without changing the SHA → unpinned; the non-exceptable kinds are also never in any pin (validated) |
| Malformed archive | CRC/DEFLATE/size/bomb in resources, natives, classes, manifests | Phase X-A/X-B checks run first and unchanged (`JAR_UNREADABLE` / bound refusal) |
| Race | byte change keeping size/mtime, physical replacement, A-B-A after hashing | Phase IX-4 identity + re-hash (`INPUT_CHANGED_DURING_INVENTORY`) |
| Policy drift | a pin that no longer matches the JAR's real findings (policy lists more than exists) | `RLDI2_POLICY_EXCEPTION_NOT_OBSERVED` (exact equality, not subset) |

Out of scope (unchanged blockers): copies elsewhere on the host, other users/administrators, `java -cp` by hand
(`global_old_binary_exclusion=NOT_PROVEN`); Windows ACLs (`UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`); runtime fencing; JNA
extracting `jnidispatch` to a temp directory at run time (RLWR1 review note, see owner decisions §2).

## 3. Where the trust comes from — options compared

| | A. Fixed policy in reviewed Java code (**chosen**) | B. External signed / independently pinned policy file | C. Repackage dependencies to remove the findings |
|---|---|---|---|
| Trust root | the source of `DistributionInventoryV2.PINS`, changed only by a reviewed commit; the inspector that evaluates it is the same reviewed code | a signing key or a pin of the policy file held somewhere the install record cannot reach | the build that produces the repackaged JARs |
| Self-approval by record | impossible: the record can only *name* a compiled-in pin; a mismatch is a finding | possible unless the key/pin lives outside the install's trust domain — RomLogger has **no** key infrastructure, HSM or second store today; a pin of the policy would itself need to be compiled in → reduces to A | n/a |
| Update cost | code change + review per dependency update (rare: 4 JARs, versions fixed since Phase 2) | key management, rotation, revocation — new operational surface for a single-owner project | rebuild per upstream release; must reproduce byte-exactly to stay pinnable |
| Licence/signature impact | none: upstream bytes shipped unchanged | none | **modifies third-party binaries**: LGPL/Apache obligations for modified works (JNA), loss of upstream provenance, sqlite-jdbc's multi-release/native layout must be re-verified; harder to audit than an exact upstream SHA |
| Performance | one map lookup per JAR | signature verification per run | none at inspection |
| Review evidence | the exact findings are listed in code and re-observed at every inspection | depends on signer process | diff of the repackaged contents |

**Decision for the candidate: A.** It is the only option with a trust root that the install record cannot influence and
that needs no new infrastructure. B is recorded as a future option if dependencies start changing often (it requires an
owner decision on key custody). C is rejected for now (modifies third-party binaries, licence and provenance cost).

## 4. Rules implemented

1. **Separate contract.** New class `DistributionInventoryV2` (`RLDI2`), new record version `RLDI2`, JSON
   `contract_version:2`, `inventory_version:"RLDI2"`. RLDI1 behaviour, fixtures and JSON are unchanged; an RLDI2 record is
   refused by RLDI1 (`INVENTORY_VERSION`) and an RLDI1 record by RLDI2. `DistributionInventory.java` is **not modified**
   (byte-identical to `main`); RLDI2 calls its package-private `ZipEntryView.read/verify/data` and `declaresMain`, so the
   X-A/X-B parsing and verification code is shared, not copied. The directory walk, identity/hash re-checks and POSIX access
   check are private in RLDI1 and are duplicated line-for-line in RLDI2 (kept identical; a regression test compares both on
   the same fixtures). No CLI, launcher, reader or default changes: RLDI2 is reachable only through its Java API (tests).
2. **Pins (fixed).** Each pin = id, exact file name, SHA-256, byte size, entry count, one manifest attribute/value
   (`Bundle-Version` or `Implementation-Version`), and the exact set of exceptions `KIND|subject`. Only three kinds can
   ever be exceptions: `UNAPPROVED_MAIN_METHOD|<class>`, `UNAPPROVED_MANIFEST_MAIN|<class>`,
   `VERSIONED_OR_META_CLASS|<entry name>`. The pin list is validated at class initialisation (unique ids/SHAs/names,
   allowed kinds only, no `net.neristal.` subject, no legacy entry, versioned subjects only under `META-INF/versions/<n>/`);
   an invalid policy stops the class from loading (fail closed). The policy digest (`policy_sha256`) is the SHA-256 of the
   canonical pin text and must be named by the record.
3. **Application.** An exception is applied only when *all* hold: role `dependency_jar`; SHA-256 of the scanned bytes
   equals a pin; the record names that pin id; base file name, size, entry count and manifest attribute equal the pin;
   the pin is used at most once in the install. Then the JAR's observed findings are split: those listed by the pin are
   counted in `policy_exceptions_applied`; every other finding stays a finding; and the observed listed set must
   **equal** the pin's set (`RLDI2_POLICY_EXCEPTION_NOT_OBSERVED` otherwise).
4. **Every dependency must be pinned.** A `dependency_jar` with an unknown SHA-256 is `RLDI2_DEPENDENCY_NOT_PINNED`
   (stricter than RLDI1, which accepts any clean dependency). `approved_jar`, `launcher`, profiles and documents are never
   pinned and never get an exception; the approved JAR is pinned by the record only, as in RLDI1.
5. **Stricter class view.** RLDI2 additionally treats `META-INF/versions/<n>/X.class` as class `X` for the main,
   legacy-entry and cross-JAR duplicate checks (a versioned class runs on Java 9+). Class ownership is keyed by the
   relative path, not the base file name.
6. **Everything else is RLDI1.** Walk without following links, listed/unlisted/role checks, 64 MiB file bound, identity
   before hash, scanned bytes = hashed bytes, every-entry CRC/DEFLATE/size verification, 16 MiB/128 MiB expansion bounds,
   ZIP64/encryption/method refusal, manifest ambiguity, POSIX access check, final record/size/mtime/identity/hash
   re-check. Same stable codes, no paths, hashes or file names in the JSON.
7. **Output never authorizes.** `state=BLOCKED`, launch/production/ACK `false`, `global_old_binary_exclusion=NOT_PROVEN`,
   Windows ACL `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`, `synthetic_only=true`, regardless of `MATCHED`.

## 5. Allow / deny matrix

| Case | RLDI1 | RLDI2 |
|---|---|---|
| approved JAR alone | MATCHED | MATCHED |
| approved JAR + the four pinned JARs (canonical names, pins named) | MISMATCH (F-IX-1) | **MATCHED** (BLOCKED) |
| approved JAR + sqlite/slf4j only (no JNA; Linux / reader not needed) | MISMATCH | MATCHED — RLDI2 never requires JNA; whether a reader needs it is the entry's `READER_NATIVE_UNAVAILABLE` gate |
| dependency with unknown SHA (1-byte change, other version, rebuilt, same name) | per content | MISMATCH `RLDI2_DEPENDENCY_NOT_PINNED` + content findings |
| pinned bytes as `approved_jar` / `launcher` / renamed / listed twice | MISMATCH | MISMATCH (no exception; `RLDI2_PIN_NOT_APPLICABLE`, `RLDI2_PIN_SHAPE_MISMATCH`, `RLDI2_PIN_REUSED`, `DUPLICATE_CLASS`) |
| record names a wrong/foreign pin, other policy digest, extra keys | n/a | `RLDI2_PIN_MISMATCH` / refusal `RLDI2_POLICY_MISMATCH` / `INVENTORY_FORMAT` |
| offline tools JAR as dependency | MISMATCH | MISMATCH (unpinned + four mains + duplicates) |
| legacy entry / Class-Path / agent / nested archive / manifest case variant / duplicate class anywhere | MISMATCH | MISMATCH (never exceptable) |
| CRC / DEFLATE / bomb / ZIP64 | stop | stop (same code path) |
| race (same-size change, replacement, A-B-A) | stop | stop (same checks) |

## 6. Pin supply and update procedure (proposal; owner approval required before any real use)

1. Obtain the artifact from Maven Central through the existing pinned fetch (`lib/test-dependencies.sha256`, CI JNA pin).
2. Compare its SHA-256 with an independent source (Maven Central `.sha1`/signature, upstream release notes) — not done in
   X-C beyond the existing repository pins.
3. Run the RLDI1 parser over it, review **every** finding and its reason (table §1), and only then add a pin with the exact
   findings. Re-run `scripts/test-phase-xc-rldi2.sh`; the exact-equality rule fails if the list is too long or too short.
4. Removing a dependency = deleting its pin (records naming it then fail). Never widen a pin to "any version".

## 7. Remaining limits

- The trust root is the approved code itself: whoever can replace the inspector can replace the policy. RLDI2 does not
  change who is trusted, it only stops the install record from approving anything.
- `MATCHED` is a snapshot of one directory. Not adopted, not wired into the entry CLI, not proof of old-binary exclusion.
- JNA licence/distribution form, FILE_SHARE_DELETE, Windows ACL and minimum OS are separate owner decisions; RLDI2 being
  able to read JNA's JAR says nothing about whether JNA may be distributed.
