# Phase X-D owner decision record (2026-10-26 JST)

Prepared by Claude Code. **No decision below has been taken; every open item is `OWNER_DECISION_REQUIRED`.** Nothing here
authorizes launch, production, formal ACK, reader adoption, distribution, release, or contact with the X270/ECU/RomRaider.
It continues the [X-C dossier](phase_xc_owner_decisions.md); facts that did not change are not repeated.

Labels: **[measured]** run in this phase on owned synthetic fixtures (environment named); **[code]** this repository;
**[prior]** earlier evidence, not re-run.

## 1. RLDR1 required-dependency readiness (X-D1) — new

**Facts**
- `DependencyReadiness` (RLDR1) implemented as a default-off, Java-API-only candidate in Draft PR #108; not wired to any
  CLI/entry/launcher/reader/Sender/ACK [code].
- Code-derived policy: sqlite-jdbc required for every product reader; SLF4J api+nop optional, all-or-none; JNA required
  for `WINDOWS_NATIVE_V1_SYNTHETIC`, forbidden for `POSIX`; `WINDOWS_NATIVE_TEST_ONLY` profile BLOCKED [code + measured].
- Measured SQLite closure (child JVMs, JDK17 x64, actual Zulu 8 x86, Linux JDK17): sqlite-jdbc alone works (JUL
  fallback); slf4j-api without a provider → 189 B SLF4J warning on stderr; with slf4j-nop → 0 B; the SQLite path loads no
  JNA class [measured].

**Owner decides:** (a) whether RLDR1's direction is accepted for independent review; (b) whether the product ships SLF4J
at all (RLDR1 accepts "both" or "neither"); (c) whether JNA must be *forbidden* (not just "not required") for POSIX
profiles; (d) whether an additive entry flag (e.g. `--readiness-rldr1`) is ever wanted. Until then RLDR1 stays default-off.

## 2. Windows ACL (X-D2) — read-only PoC result

**Facts [measured, Windows 11 dev VM, JDK17 x64 and actual Zulu 8 x86; no ACL was written]**
- `PhaseXdWindowsAclProbe` (test code only) read owner + DACL of a new owned fixture (default inherited ACL) and of every
  real parent up to the volume root (6 levels).
- Fixture objects: write-class rights only for the current user, SYSTEM and Administrators →
  `CANDIDATE_IF_SYSTEM_AND_ADMINS_TRUSTED` (trust of SYSTEM/Administrators flagged `OWNER_DECISION_REQUIRED`).
- Parent chain: **volume root `MISMATCH`** (a broad group holds a write-class right; owner is a principal other than
  user/SYSTEM/Administrators) and two AppData levels **`UNKNOWN`** (write-class ACE for a SID the JDK could not resolve).
- GitHub `windows-latest` (Windows Server 2025) runner: a fresh fixture directory under `RUNNER_TEMP` already
  **inherits a broad-group write-class right** → `MISMATCH` on the fixture itself (CI job 121487334190, see worklog).
  A "newly created private directory" is therefore not owner-controlled by default on every Windows host.
- Effective rights (group membership, privileges, deny ordering, conditional ACEs) are not evaluated (needs native
  `AuthzAccessCheck`; JNA decision first). Overall: `UNKNOWN_EFFECTIVE_RIGHTS_NOT_EVALUATED`.

**Implication:** the dossier's proposed scope "every parent up to the volume root" is `MISMATCH` on a stock Windows 11
volume root. Either the scope stops at an owner-controlled install anchor, or the root's broad "create folders/append"
right must be judged acceptable for directories the user cannot rename. That is a scope decision, not a code fix.

> **Correction 2026-10-27 (independent review):** the sentence above generalizes too far. What was measured is limited
> to the environments actually inspected: the volume root of **one** Windows 11 dev VM was `MISMATCH`, and on the GitHub
> `windows-latest` (Windows Server 2025) runner a fresh fixture under `RUNNER_TEMP` was `MISMATCH`. No claim is made
> about Windows installations in general, other Windows 11/10 builds, or the X270 (not inspected). These observations
> only show that a "volume-root" scope *can* fail on a real host; they do not decide the scope. Trusted principals and
> parent scope remain `OWNER_DECISION_REQUIRED`. The bullet "not owner-controlled by default on every Windows host" above
> likewise means only "not on that runner" — one counterexample, not a property of every host.

**Owner decides:** trusted principals (SYSTEM? Administrators?); parent scope (install anchor vs. volume root); whether an
ACL result may ever contribute to a gate; whether native effective-rights evaluation (JNA) is wanted. **UNKNOWN:** every
real X270 ACL (not inspected, prohibited).

## 3. Windows 10 minimum (X-D3)

**Facts:** no non-car Windows 10 VM is documented in this repository or reachable with the tools of this session; the
Proxmox management entry is still "unconfirmed" (bootstrap). No VM was created and no management setting was touched.
→ **NOT_RUN**. X270 untouched.

**Synthetic acceptance plan (for an owner-provided non-car Windows 10 22H2 x64 VM):**
1. Fresh clone with `core.longpaths=true`; long-name `TMP`; JDK17 x64 for builds; the RomRaider-bundled Zulu 8
   (1.8.0_504, x86) copied — not installed system-wide — as the `--run-only` runtime, `sun.arch.data.model=32` asserted.
2. Suites: `scripts/ci-phase7-9.sh phase7|phase8|phase9` (includes X-C RLDI2 and X-D RLDR1), `test-phase-xa-drive.sh`,
   `test-windows-native-identity.sh`, RLWR1 native reader suite — all on a dedicated NTFS fixed-drive fixture.
3. Record OS build, Zulu build, NTFS/drive type, PASS/FAIL/NOT_RUN per suite; compare with Windows 11 / Server 2025.
4. Pass criterion for `MIN_WINDOWS`: identical PASS counts to Windows 11 for x64 and x86, no new NOT_RUN. Even then, the
   Azul support-list gap for Windows 10 (X-C dossier §5) stays an owner risk decision.

**Owner decides:** provide/approve such a VM; whether the RomRaider-bundled JRE is the runtime of record.

## 4. JNA 5.14.0 (X-D3) — unchanged

Still internal tests only (form (i)). New fact [measured]: with the pinned JAR copied to the VPS (Linux JDK17), the X-C
RLDI2 JNA cases and the X-D RLDR1 JNA cases ran on Linux for the first time (previously NOT_RUN on ubuntu CI). No
licence conclusion, no bundling, no product distribution. **Owner decides** as in the X-C dossier §2.

## 5. `FILE_SHARE_DELETE` (X-D3) — unchanged, comparison kept

| | `READ_WRITE` (current RLWR1 default) | `READ_WRITE_DELETE` |
|---|---|---|
| reader holds source | writer rename/delete fails (Win32 32/5) [prior] | rename/delete succeed; handle keeps identity/bytes [prior] |
| detection on reader side | n/a | `READER_NOT_FOUND`, `READER_DELETE_PENDING`, identity/path checks [prior] |
| risk | real writer rotation may fail (unmeasured) | relies on identity checks; source can disappear under the reader |

No real-writer measurement in X-D (requires separate owner approval, non-car host). `WRITER_UNPROVEN` stays.

## 6. Summary

| Item | After X-D | Blocking? |
|---|---|---|
| RLDR1 | candidate, synthetic GREEN (Win11 x64/x86, Linux VPS, Docker Linux, CI) | yes (not adopted) |
| SLF4J shipping | measured optional | owner |
| Windows ACL | PoC (inspected hosts only): fixture candidate, parent chain MISMATCH/UNKNOWN | yes |
| Windows 10 | NOT_RUN (no VM); plan above | yes |
| JNA | internal tests only; Linux cases now run | yes |
| FILE_SHARE_DELETE / real writer | unchanged; WRITER_UNPROVEN | yes |

Unchanged everywhere: synthetic_only, launch/production/ACK disabled, formal_ack=DISABLED, reader_adoption=BLOCKED,
global_old_binary_exclusion=NOT_PROVEN, WRITER_UNPROVEN.
