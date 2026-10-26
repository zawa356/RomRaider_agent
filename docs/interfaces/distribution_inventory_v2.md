# Distribution Inventory v2 (RLDI2) — Phase X-C1 synthetic-only candidate

`net.neristal.rrbridge.prototype.ingress.DistributionInventoryV2`. **Default-off:** reachable only through its Java API
(`describe`, `inspect`) from tests. Not wired into `OfflineCandidateEntry`, any CLI, launcher, reader or runbook; no
default changed. Read-only; never launches, writes, repairs, deletes, renames or quarantines anything. Design and threat
model: [rldi2_dependency_policy_design.md](../design/rldi2_dependency_policy_design.md). RLDI1 ([v1](distribution_inventory_v1.md))
remains the strict contract; its inspection is unchanged (only its JSON string escaping was fixed after review P2).

## Relation to RLDI1

- Separate record version (`RLDI2`), JSON `contract_version:2`. An RLDI2 record given to RLDI1 → `INVENTORY_VERSION`
  (UNAVAILABLE); an RLDI1 record given to RLDI2 → `INVENTORY_VERSION`.
- Every RLDI1 check and refusal applies unchanged (walk, listed/unlisted, roles, hashes, identity, scanned = hashed bytes,
  every-entry CRC/DEFLATE/size verification, 16 MiB/entry and 128 MiB/JAR expansion bounds, ZIP64/encryption/method
  refusal, manifest ambiguity, POSIX access, final re-checks; same codes).
- Stricter: classes under `META-INF/versions/<n>/` are also checked as their effective class (main, legacy entry,
  cross-JAR duplicate); class ownership is keyed by relative path; **every `dependency_jar` must be a compiled-in pin**.
- Relaxed only by the fixed policy below, only for `dependency_jar`, only for the exact findings listed.

## Record (private `key=base64`, DeploymentBinding record format, outside the install directory)

| key | value |
|---|---|
| `version` | `RLDI2` |
| `mode` | `SYNTHETIC_ONLY` |
| `approved_main` | must equal `OfflineCandidateEntry.MAIN` |
| `policy` | must equal `RLDI2-DEPENDENCY-POLICY-1` |
| `policy_sha256` | must equal `DistributionInventoryV2.POLICY_SHA256` (digest of the compiled-in pin text) |
| `files` | N (1..4096); the record has exactly `6+4N` keys |
| `file.NNNN.path` / `.sha256` / `.role` | as RLDI1 |
| `file.NNNN.pin` | `-` or the id of a compiled-in pin; an unknown id is refused (`RLDI2_PIN_UNKNOWN`) |

`describe(root, approvedMain, roles)` writes the record of a directory as it is, filling `pin` from the scanned SHA-256
of each `dependency_jar`. It is a packaging step, not an approval: the pin is honoured only if `inspect` re-derives it.

## Policy `RLDI2-DEPENDENCY-POLICY-1` (compiled in, reviewed code)

| pin id = file name (without `.jar`) | SHA-256 | bytes / entries | manifest | exceptions (exact) |
|---|---|---|---|---|
| `sqlite-jdbc-3.46.1.3` | `4a4832720a65eaf7f4d6fd7ede52087b994dc5633c076f9e994dc0c8b4b0b4fa` | 14 127 503 / 214 | `Bundle-Version: 3.46.1.3` | `UNAPPROVED_MAIN_METHOD org.sqlite.util.OSInfo`; `VERSIONED_OR_META_CLASS` ×4 (`META-INF/versions/9/module-info.class`, `…/org/sqlite/nativeimage/SqliteJdbcFeature.class`, `…$1.class`, `…$SqliteJdbcFeatureException.class`) |
| `slf4j-api-2.0.16` | `a12578dde1ba00bd9b816d388a0b879928d00bab3c83c240f7013bf4196c579a` | 69 435 / 71 | `Bundle-Version: 2.0.16` | `VERSIONED_OR_META_CLASS META-INF/versions/9/module-info.class` |
| `slf4j-nop-2.0.16` | `deca6c04ed35515a0a911fa44c0e836bee92c0c59d2e8fa9bab8ffbc464a9ba7` | 4 982 / 17 | `Bundle-Version: 2.0.16` | `VERSIONED_OR_META_CLASS META-INF/versions/9/module-info.class` |
| `jna-5.14.0` | `34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6` | 1 878 533 / 188 | `Bundle-Version: 5.14.0` | `UNAPPROVED_MAIN_METHOD com.sun.jna.Native`; `UNAPPROVED_MANIFEST_MAIN com.sun.jna.Native` |

Exceptable kinds: `UNAPPROVED_MAIN_METHOD`, `UNAPPROVED_MANIFEST_MAIN`, `VERSIONED_OR_META_CLASS` only. The pin list is
validated at class load (unique id/file/SHA, allowed kinds, no `net.neristal.*`, legacy entry or approved-main subject,
versioned subjects only under `META-INF/versions/<n>/`); an invalid list prevents the class from loading.

Being pinned here is **not** distribution approval: JNA's licence/distribution form is an open owner decision.

## Application of a pin (all must hold)

role `dependency_jar` · SHA-256 of the scanned bytes = pin · record `pin` = that id · base file name, byte size, entry
count and manifest attribute = pin · pin not already used in this install. Then the pin's exceptions are subtracted from
that JAR's findings and counted in `policy_exceptions_applied`; the subtracted set must equal the pin's set.

## New findings (MISMATCH) and refusals (UNAVAILABLE)

| Code | Meaning |
|---|---|
| `RLDI2_DEPENDENCY_NOT_PINNED` | a `dependency_jar` whose SHA-256 is not a compiled-in pin (unknown, modified, other version) |
| `RLDI2_PIN_MISMATCH` | record names another pin (or a pin for unpinned bytes) |
| `RLDI2_PIN_SHAPE_MISMATCH` | pinned bytes under another file name, or size/entries/manifest attribute differ |
| `RLDI2_PIN_REUSED` | the same pin applied twice (alias / second copy) |
| `RLDI2_PIN_NOT_APPLICABLE` | a pin named for, or pinned bytes used as, a non-`dependency_jar` role |
| `RLDI2_POLICY_EXCEPTION_NOT_OBSERVED` | the JAR does not produce every listed exception (policy drift) |
| refusal `RLDI2_POLICY_MISMATCH` | record `policy`/`policy_sha256` is not the compiled-in policy |
| refusal `RLDI2_PIN_UNKNOWN` | record names a pin id that does not exist |

## JSON

`{"contract_version":2,"inventory_version":"RLDI2","dependency_policy":"RLDI2-DEPENDENCY-POLICY-1","state":"BLOCKED",
"inventory":"MATCHED|MISMATCH|UNAVAILABLE|NOT_RUN","reason":…,"files":N,"pinned_dependencies":K,"findings":{code:count},
"policy_exceptions_applied":{kind:count},"unapproved_mains":[class names],"access_control":…,
"global_old_binary_exclusion":"NOT_PROVEN","synthetic_only":true,"launch_authorized":false,"production_authorized":false,"ack_eligible":false}`.
No paths, file names or hashes (the policy digest is not printed). Excepted mains are counted, not listed.

**String encoding (review P2, 2026-10-26):** every string value and key is emitted through
`DistributionInventory.jsonString`: RFC 8259, printable ASCII only — `"` and `\` are backslash-escaped, every character
outside 0x20..0x7E (controls, DEL, non-ASCII, U+2028/2029, surrogates) as `\uXXXX`. This matters for
`unapproved_mains`: a class name is derived from a ZIP entry name, and `"` or `\` are legal in ZIP entry names and JVM
binary names (`x/In","launch_authorized":true,"j":".class`). Such a name is **reported, escaped, and still refused**
(`UNAPPROVED_MAIN_METHOD`); it is never accepted because it was escaped. Control characters in an entry name are still
refused by the ZIP reader (`JAR_UNREADABLE`), as before. Ordinary codes and class names are byte-identical to the
earlier output. Test: `PhaseXcJsonEscapeTest` (strict parser, exact key set, gates, exact round-trip).

## Responsibility boundary: RLDI2 does not prove the install is complete

RLDI2 answers one question: *is every executable/archive file that **is present** under the install root listed, of the
recorded role and bytes, and (for `dependency_jar`) one of the compiled-in pins with exactly its audited findings?* It
also requires exactly one `approved_jar` that declares the approved main (`APPROVED_JAR_COUNT`, `APPROVED_MAIN_MISSING`).

It does **not** establish, and a `MATCHED` result must never be read as:

- **Dependency completeness** — that every JAR the runtime needs is present. A subset of the pins is `MATCHED`
  (e.g. without JNA, or with only one pin); a missing dependency surfaces only as a runtime failure or a later gate.
- **The required set per profile** — which dependencies a given launch profile needs (sqlite-jdbc + slf4j for the
  spool; JNA only if the Windows reader is adopted, which today is `READER_NATIVE_UNAVAILABLE` / BLOCKED).
- **The launch class path** — that the JVM is started with exactly these files, in this order, and loads nothing from
  outside the root (other copies on disk are not fenced: `global_old_binary_exclusion=NOT_PROVEN`).
- **Freshness** — the result is a snapshot of one inspection.

These belong to a future readiness axis (owner decision; not implemented here): a profile-pinned *required dependency
set* (pin ids per profile) checked against the RLDI2 `pinned` set, plus the existing launch-provenance checks on the
actual class path. Until that exists, completeness stays `UNKNOWN` and `reader_adoption=BLOCKED`.

## Not changed by RLDI2

`state` is always `BLOCKED`; launch/production/ACK always `false`; `global_old_binary_exclusion=NOT_PROVEN`; on Windows
`access_control=UNKNOWN_WINDOWS_ACL_NOT_INSPECTED`. JNA is never required: an install without JNA is judged only on what it
contains; whether a reader needs JNA is the entry's `READER_NATIVE_UNAVAILABLE` gate. Test:
`bash scripts/test-phase-xc-rldi2.sh` (`--run-only` under actual Java 8 x86; `--expect-red` = reproduction only).
