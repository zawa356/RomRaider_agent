# Phase X-D1 — dependency completeness/readiness axis: design candidate

Status: DRAFT / REVIEW REQUIRED, 2026-10-26 JST. **No implementation or adoption.**
Baseline: Agent main bcc81566b2c90b61de88ec0fb7fb3bf43631e0f9.

## 1. Existing code-derived constraints

- DistributionInventoryV2.java offers pinned, content-validated *present dependency* JAR inventory only. Its MATCHED result does not prove the installed dependency set is complete.
- OfflineCandidateEntry.java accepts **three** reader values in knownReader(): POSIX, WINDOWS_NATIVE_TEST_ONLY and WINDOWS_NATIVE_V1_SYNTHETIC. The older offline_candidate_entry_v1.md RLOP1 summary lists only the first two; this is documentation drift, not an instruction to remove the third mode.
- createProfile(...) records an exact ordered classpath string and ArtifactProvenance.describe(classpath, MAIN) into RLOP1. readProfile(...) checks that string, running JVM facts, the described classpath artifacts, runtime and main command. These checks are not authentication of a running process and do not prove closure of required JARs.
- productReader() for WINDOWS_NATIVE_V1_SYNTHETIC requires Windows, refuses the test reader on the classpath, resolves com.sun.jna.Native code source, requires its bytes to match the JNA 5.14.0 SHA-256 and uses WindowsProductReader ShareMode.READ_WRITE. This is an *existing* synthetic reader condition, NOT an authorization to adopt it.
- RLOP1 field sets reject unknown fields. An additive required-set policy must not be smuggled into RLOP1 without separate versioning and explicit owner approval.
- RLDI2 fixed allowed pin IDs: sqlite-jdbc-3.46.1.3, slf4j-api-2.0.16, slf4j-nop-2.0.16, jna-5.14.0. Allowlist membership is different from per-profile *mandatory presence*.
- Existing OfflineCandidatePreflight and IntegratedReadiness perform other independent read-only axes. Process identity, writer quiescence, ACL and old-binary exclusion remain separate non-PASS gates.

## 2. Proposed independent contract — not yet code

A read-only DependencyReadiness candidate shall take explicit **already validated** RLDI2 record/snapshot metadata, code-reviewed profile-policy version, observed RLOP1 reader and classpath/ordered RLAP1 descriptions, and directory identity/freshness evidence. It must never interpret RLDI2 MATCHED as authorization, nor assume the profile data is trustworthy without checking its independently pinned constraints.

Validation sequence candidate:
1. disabled/no inputs => NOT_RUN, state BLOCKED, flags false. Inability to authenticate pinned inputs => UNKNOWN/BLOCKED, not MATCHED.
2. Verify exact version/policy/reader mode and resolve required pins from code-derived reader implementation; unknown profile/reader => MISMATCH/BLOCKED. Preserve RLOP1 strict field set.
3. Compare the entire required set against present validated RLDI2 pins. Missing => MISMATCH; unpinned/extra unknown JAR or duplicate => MISMATCH. An allowed optional dependency requires a separately documented, profile-appropriate rule.
4. Compare exact ordered classpath to *all* approved artifacts/roles, not only dependency SHA names. Mismatch in order/extra/missing/outside-root/duplicate => MISMATCH. Carefully separate classpath artifacts (which may include approved_jars) from pinned dependency subset.
5. Check observation source, identity, snapshot age and TOCTOU/final reinspection. A saved record with no current identity evidence => UNKNOWN. Never report a long-lived PASS on an old scan.
6. Return bounded stable codes and parseable JSON. No file paths, process command lines, tokens or payloads. All launch/production/ACK flags false and state BLOCKED.

Suggested axes: required_set, classpath_closure, profile_policy, freshness, process_provenance, access_control. A green required_set axis must NOT imply any of the others. AXIS STATUS: MATCHED / MISMATCH / UNKNOWN / NOT_RUN / BLOCKED, with separately fixed code and next action.

## 3. Mode decision table (actual required IDs not yet approved)

| Existing RLOP1 reader | Proven existing code rule | Required pinned dependency set |
| --- | --- | --- |
| POSIX | WatermarkV2Prototype.POSIX_READER | TO_DERIVE from actual Java closure; JNA NOT automatically required |
| WINDOWS_NATIVE_TEST_ONLY | reflective WindowsWatermarkV2Test.READER in test classpath | TO_DERIVE; test artifact role must be considered, no JNA assumption |
| WINDOWS_NATIVE_V1_SYNTHETIC | productReader() requires pinned JNA 5.14.0, no test reader class | JNA is necessary for this specific synthetic reader; sqlite/slf4j closure TO_DERIVE |

Before implementing a required set, read the actual SQLite ingest path, logging path, and test fixtures. The existence of four pinned JARs in RLDI2 does NOT by itself justify requiring all four for every profile. The current Windows reader path is still BLOCKED for adoption.

## 4. Negative test matrix (RED first)

- Profile with only approved jar; missing sqlite or logging dependency as derived from the real code.
- POSIX profile omits JNA: do not reject only because JNA is an allowed pin.
- Windows native v1 synthetic reader with missing, modified or incorrect-source JNA: mismatch; no launch.
- Unknown/extra/duplicate dependency, renamed same SHA, wrong role, old RLDI1 record vs RLDI2 contract and mismatched profile-policy digest.
- Classpath order swap, removed entry, foreign directory/JAR, symlink/reparse and alternate identical-bytes copy; current vs expected RLOP1 mismatch.
- Owner/time-of-check data change, directory/file replaced after inventory scan, spoofed flags in record.
- Malicious ZIP entry/report names with quotes and backslashes: strict parsed JSON and no fabricated authorization keys.
- Windows ACL unknown and writer unproven while dependency set matches => state BLOCKED; no client/Server behavior change.

## 5. Implementation and review gates

- First produce source-backed required-closure table, policy digest/version and independent reviewer agreement. Then consider a separate Java-only, default-off synthetic candidate API and adversarial tests. Never wire to CLI/launcher/Reader/Sender/production/ACK in Phase X-D.
- Reuse existing RLDI2 verified data without weakening RLDI1 and without accidentally adding writes to read-only APIs.
- CI: Linux Java17, Windows Java17 x64, actual Zulu8 x86 (data model 32), old X-A/X-B/X-C + Phase VII-IX + spool + isolated Server E2E. Report exact tested SHA and NOT_RUN.
- Existing file walker/TOCTOU code duplication is a separate refactor requiring explicit regression proof, not part of this design change.
- No ready/merge without owner approval.

Sources: src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java (knownReader/createProfile/readProfile/productReader); DistributionInventoryV2.java; docs/interfaces/offline_candidate_entry_v1.md; docs/interfaces/distribution_inventory_v2.md; docs/interfaces/integrated_readiness_v1.md.

## Independent review by Claude Code against the code (2026-10-26 JST, appended; text above unchanged)

Reviewed at Agent `main` `bcc8156`. The draft's direction holds; its facts were checked against the code and its open
`TO_DERIVE` items were resolved in the implementation design
[`phase_xd1_required_dependency_readiness.md`](https://github.com/zawa356/RomRaider_agent/blob/phase-xd/dependency-readiness/docs/design/phase_xd1_required_dependency_readiness.md)
(Draft PR #108). Findings:

| Draft statement | Result |
|---|---|
| §1 `knownReader()` has three values; `offline_candidate_entry_v1.md` lists two | **Confirmed**; the contract text is corrected on this track (dated note) |
| §1 `productReader()` requires Windows, refuses the test reader, pins JNA SHA-256, `READ_WRITE` | **Confirmed** (`OfflineCandidateEntry.productReader`) |
| §1 RLOP1 strict field set | **Confirmed** (`verifyProfile`); RLDR1 re-implements the static part read-only |
| §2 input "already validated RLDI2 record/snapshot metadata" | **Changed**: a passed-in snapshot can be stale or forged; RLDR1 runs RLDI2 itself and binds profile/record/JAR bytes and identities from start to end. Freshness then needs no time window |
| §3 POSIX: "TO_DERIVE; JNA not automatically required" | **Derived**: sqlite-jdbc required; SLF4J api+nop optional all-or-none (measured: JUL fallback; api alone → 189 B stderr warning); JNA **forbidden** (unreachable native code) |
| §3 `WINDOWS_NATIVE_TEST_ONLY`: TO_DERIVE | **Derived**: profile policy `BLOCKED` (a test JAR is not an inventory role); never MATCHED |
| §3 V1 synthetic: JNA necessary; sqlite/slf4j TO_DERIVE | **Derived**: sqlite-jdbc + JNA required; SLF4J optional pair |
| §4 negative matrix | Implemented (RED 223 → GREEN), plus: approved JAR must be first; unused dependency in the install is a mismatch; real path must equal the written path (junction/symlink/8.3/`..`); permission-denied JAR on POSIX |
| §5 "no wiring, no walker refactor" | **Kept** |

Not adopted from the draft: the axis name `classpath_closure` (implemented as `classpath`) and separate
`process_provenance`/`access_control` evaluation (reported as fixed `UNKNOWN` / RLDI2 value, never MATCHED by RLDR1).
