# Phase IX report — #96 review closure, RLWR1 Windows reader candidate, RLDI1 hardening (Claude Code, 2026-10-21)

Reading order for a fresh session: [Bootstrap](../ops/development_environment_bootstrap.md) → this report →
[Phase IX evidence](phase9_validation_evidence.md) → [append-only worklog](phase9_worklog.md) →
[RLWR1 interface](../interfaces/windows_product_reader_v1.md) → [RLDI1](../interfaces/distribution_inventory_v1.md),
[entry](../interfaces/offline_candidate_entry_v1.md). Phase VIII report: [phase8_report.md](phase8_report.md).

## Stack (all Draft, unmerged; `main` unchanged at 4f80c5e; Server unchanged at d8c7a2f)

| PR | Branch | Base | Content |
|---|---|---|---|
| [#97](https://github.com/zawa356/RomRaider_agent/pull/97) | `phase9/cli-review-closure` | `phase8/entry-closure-cli-split` (#96) | IX-1: R9-01 tool build + CLI process tests, R9-02 report timeline, R9-03 `WORKING_DIRECTORY_MISSING` |
| [#98](https://github.com/zawa356/RomRaider_agent/pull/98) | `phase9/windows-reader-synthetic` | #97 | IX-2: RLWR1 `WindowsProductReader` candidate + fake/native tests |
| [#99](https://github.com/zawa356/RomRaider_agent/pull/99) | `phase9/windows-reader-entry-gated` | #98 | IX-3: `WINDOWS_NATIVE_V1_SYNTHETIC` behind the single entry (gated) |
| [#100](https://github.com/zawa356/RomRaider_agent/pull/100) | `phase9/distribution-hardening` | [#99](https://github.com/zawa356/RomRaider_agent/pull/99) | IX-4: RLDI1 TOCTOU (R9-04) + launcher env tests; Phase IX report/evidence |

Heads: #97 `815dae7`, #98 `c42c4a6`, #99 `0612693`, #100 runtime/tests `987e504` (later commits on #100 are documentation only). Server docs: [RomRaiderServer#65](https://github.com/zawa356/RomRaiderServer/pull/65) on #64 (docs only).

## Results by item

| Item | Classification | Basis |
|---|---|---|
| R9-01 CLIs not built | DEFECT CONFIRMED (RED at ab32e6b5) → FIXED | explicit tool JAR build; 4 CLIs started as real child JVMs from the tool JAR only (39 checks) x64 + actual x86 |
| R9-02 stale report | FIXED (history kept) | phase8_report timeline |
| R9-03 error=267 | CAUSE NARROWED, ROOT_CAUSE_NOT_PROVEN (actor) | empty `work` dirs under %TEMP% removed in two batches (22:43 inside the failing run, 04:53); Storage Sense temp cleanup enabled on low disk. Product: `WORKING_DIRECTORY_MISSING` RED→GREEN; fixture keep-marker; no retry |
| R9-04 RLDI1 TOCTOU | DEFECT CONFIRMED (same-size/same-mtime byte change and byte-identical physical replacement → MATCHED before) → FIXED | identity pinned before hash, hash re-checked after JAR scan and at the end, identity re-read; RED/GREEN in evidence |
| IX-2 RLWR1 reader | IMPLEMENTED_AND_TESTED (synthetic), **adoption BLOCKED** | frozen 987e504 JDK17 x64 + actual Zulu8 x86 exit0 (fake, native, RLC2 scenarios in both share modes) |
| IX-3 RLWR1 via entry | IMPLEMENTED_AND_TESTED (synthetic subset), BLOCKED | frozen 987e504 x64 + actual x86: 3 sessions stored via real supervisor/child without test JAR, restart 0 dup, forged/tampered refusals |
| IX-3 real install RLDI1 | **BLOCKED — new finding F-IX-1** | approved JAR alone MATCHED; with the entry's required sqlite-jdbc/slf4j/JNA JARs → MISMATCH (`com.sun.jna.Native`, `org.sqlite.util.OSInfo` mains, sqlite manifest Main-Class, multi-release META-INF classes) |
| IX-4 launcher env | IMPLEMENTED_AND_TESTED | JAVA_TOOL_OPTIONS/_JAVA_OPTIONS (+JDK_JAVA_OPTIONS on 17) → `JVM_OPTIONS_REFUSED`; Java 8 ignores JDK_JAVA_OPTIONS (reported, not PASS-inflated) |
| Windows ACL inspection | UNKNOWN | not implemented (no non-operator principal fixture) |
| Global old-binary exclusion | NOT_PROVEN | by design |
| Writer quiescence / process provenance / directory durability | WRITER_UNPROVEN / UNKNOWN | unchanged |
| Production, Sender, formal ACK, ECU/X270 | NOT_RUN (disabled) | out of scope |
| GitHub Actions | CI NOT RUN | jobs `steps=0` |

## Independent review (separate agent context, read-only, at bc91d17)

P1: A-B-A between a JAR's hash and its entry scan (the scan re-opened the path) → fixed in `987e504` (one bounded read,
hash = pin, scan of a private copy of exactly those bytes) + GREEN regression. P2 fixed: handle closed on `Error` after open;
`Refusal` message = bare code (preflight `cause()` keeps it); `WORKING_DIRECTORY_MISSING` before the run record (no orphan);
test exemptions removed; CLI test builds the same entry closure as the Windows harness; `build-offline-tools.sh` OUT_DIR
guard; JarFile leak. Documented, not changed: 8.3 short paths refused but untested; possible `READER_LENGTH_UNSTABLE` while
a writer appends (fails closed); JNA extracts `jnidispatch` to temp; only `com.sun.jna.Native`'s JAR is hashed (RLAP1
profile pin covers the classpath). No P0.

## Compatibility changes

- Entry: new refusal `WORKING_DIRECTORY_MISSING`; new reader value `WINDOWS_NATIVE_V1_SYNTHETIC` with codes
  `READER_PLATFORM_UNSUPPORTED`, `READER_TEST_CODE_ON_CLASSPATH`, `READER_NATIVE_UNAVAILABLE`,
  `READER_DEPENDENCY_UNPINNED` (+ RLWR1 `READER_*` codes); diagnose/run JSON gain `reader`, and for RLWR1
  `reader_version`, `reader_adoption=BLOCKED`, `reader_share_mode`, `share_delete_decision`, `distribution_license`.
- RLDI1: new stop reasons for a file whose identity is unavailable (`FILE_IDENTITY_*`), and changes keeping size/mtime
  now stop with `INPUT_CHANGED_DURING_INVENTORY`. Hook stage `HASHED:<name>` added (test seam).
- Old CLI class names still have no `main` (from #96; no alias).

## Owner / operator decisions required

1. `FILE_SHARE_DELETE` for RLWR1 (measured trade-off in the RLWR1 interface §4; real writer behaviour unknown).
2. JNA 5.14.0 distribution licence in a GPL-2 RomRaider context.
3. **F-IX-1**: how RLDI1 should treat the entry's own third-party dependencies (e.g. RLDI2 with a per-SHA-256 allow-list
   of known third-party classes/mains, or repackaging) — not relaxed unilaterally.
4. Minimum Windows version; network/removable drives stay refused.
5. Whether to move synthetic fixtures out of %TEMP% (R9-03) or adjust Storage Sense on the dev VM (owner's machine
   setting; not changed by this session).

## Next safe gates

Decide 1–3; then an independent review of RLWR1 native calls; RLDI2 design for third-party dependencies; Windows ACL
evidence; only then any path toward real-writer measurements (still synthetic until authorized). Do not merge while CI
has not executed. Launch/production/ACK stay disabled.

## Phase status (handoff format)

- Time: 2026-10-21 ≈10:15–15:20 JST. Agent main `4f80c5e`, Server main `d8c7a2f` (unchanged).
- IX-0 COMPLETE · IX-1 COMPLETE (#97) · IX-2 COMPLETE as synthetic candidate, adoption BLOCKED (#98) · IX-3 COMPLETE for a
  3-session subset, BLOCKED by F-IX-1 and owner decisions (#99) · IX-4 COMPLETE for R9-04 + launcher env; Windows ACL
  NOT_RUN/UNKNOWN (#100) · IX-5 COMPLETE (frozen 987e504 Windows x64 + actual x86 exit0; Linux/HTTP NOT_RUN; CI NOT RUN).
- Original data, ACK, Server wire/schema untouched; production/launch/ACK disabled; no ECU/X270/RomRaider contact.
- Next agent reads: AGENTS.md → bootstrap → this report → evidence → worklog on branch `phase9/distribution-hardening`.
