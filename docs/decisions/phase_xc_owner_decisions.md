# Phase X-C owner decision dossier (2026-10-25)

Prepared by Claude Code for the RomLogger owner. **No decision below has been taken; every item is
`OWNER_DECISION_REQUIRED`.** Nothing here authorizes launch, production, formal ACK, distribution, release or contact with
the X270/ECU/RomRaider. Each section separates **Facts** (with source), **Recommendation** (provisional), **Owner decides**
and **Unverified / UNKNOWN**.

Source labels: **[repo]** this repository at the X-C branch; **[measured]** run in this session on synthetic fixtures
(Windows 11 dev VM, JDK17 x64 and actual Zulu 8 x86); **[upstream]** primary upstream file or page, fetched 2026-10-25;
**[prior]** earlier phase evidence in this repository (not re-run unless stated).

---

## 1. RLDI2 dependency-JAR candidate (F-IX-1)

**Facts**
- RLDI1 reports a real install (entry closure + sqlite-jdbc 3.46.1.3 + slf4j-api/nop 2.0.16 + JNA 5.14.0) `MISMATCH` with
  exactly `UNAPPROVED_MAIN_METHOD`×2 (`org.sqlite.util.OSInfo`, `com.sun.jna.Native`), `UNAPPROVED_MANIFEST_MAIN`×1 (JNA
  only — sqlite-jdbc has no `Main-Class`), `VERSIONED_OR_META_CLASS`×6 [measured, `PhaseXcF1ReproTest`].
- RLDI2 (`DistributionInventoryV2`) with a fixed, code-reviewed pin list reports that install `MATCHED` (state BLOCKED) and
  rejects every adversarial variant tested (unknown/modified JARs, record/role/policy tampering, aliases, injected
  mains/legacy classes, archive tricks, malformed entries, bombs, races) [measured, `PhaseXcRldi2Test`].
- RLDI1 code is byte-identical to `main`; RLDI2 is not wired into any CLI or launcher [repo].

**Recommendation (provisional):** accept option A (fixed pins in reviewed code) as the design direction for the four
JARs; keep RLDI2 default-off until (a) an independent review of `DistributionInventoryV2`, (b) a decision on §2 (JNA),
(c) a decision whether the entry CLI should offer `--inventory-rldi2` (additive; RLDI1 stays default).

**Owner decides:** adopt A vs. B (external signed policy; needs key custody) vs. C (repackage; rejected for now);
whether "every dependency must be pinned" is the intended strictness; who may change the pin list (review rule).

**Unverified:** independent provenance of the pinned SHA-256 values beyond the repository's existing pins (Maven Central
signatures not checked in X-C); behaviour on Linux of the JNA cases (JNA is fetched on Windows CI jobs only → NOT_RUN on
Linux).

## 2. JNA 5.14.0 distribution licence

**Facts**
- POM `net.java.dev.jna:jna:5.14.0` declares two licences, `LGPL-2.1-or-later` and `Apache-2.0`, with the comment "You can
  freely decide which license you want to apply to the project" [upstream: Maven Central POM].
- The JAR itself contains `META-INF/LICENSE` (`SPDX-License-Identifier: Apache-2.0 OR LGPL-2.1`; text says LGPL 2.1 *or
  later*, or Apache 2.0 from 4.0 onward), `META-INF/AL2.0` (Apache 2.0 text) and `META-INF/LGPL2.1` (LGPL text)
  [measured: listing of the pinned JAR `34ed1e1f…`]. The upstream README states the same dual licence [upstream: GitHub].
- The JAR contains prebuilt native `jnidispatch` libraries for 30+ platforms, incl. `com/sun/jna/win32-x86/jnidispatch.dll`
  (211 456 B) and `win32-x86-64/jnidispatch.dll` (254 464 B) [measured]. They are part of the same artifact; no separate
  licence file for them was found in the JAR.
- RomLogger uses only `jna` (not `jna-platform`): `WindowsProductReaderJna` binds kernel32 (`CreateFileW`,
  `GetFileInformationByHandle[Ex]`, `GetFinalPathNameByHandleW`, `GetVolumeInformationByHandleW`, `GetDriveTypeW`,
  `QueryDosDeviceW`, `SetFilePointerEx`, `ReadFile`, `CloseHandle`) through `StdCallLibrary` [repo]. JNA is unmodified.
- At run time JNA extracts `jnidispatch` to a temp directory on first use [prior: RLWR1 §7 review note].
- RomRaider is GPL-2 [repo: AGENTS.md §2]. RomLogger's offline entry runs in **its own JVM** (not the RomRaider JVM); the
  PoC `rrbridge.jar` agent runs inside RomRaider's JVM and does not use JNA [repo].

**Distribution forms to distinguish**

| Form | What ships | Licence work implied (checklist, not legal advice) |
|---|---|---|
| (i) internal tests only (today) | nothing; CI downloads the pinned JAR | none beyond keeping the pin |
| (ii) external reference | install instructions point to Maven Central; operator fetches the pinned JAR | state the licence choice in docs; keep SHA pin |
| (iii) bundled redistribution | `jna-5.14.0.jar` inside a RomLogger package | choose Apache-2.0 or LGPL-2.1+; ship the licence text(s) (`META-INF/LICENSE`, `AL2.0`/`LGPL2.1` are already inside the JAR — confirm they suffice); NOTICE/attribution; for LGPL: allow replacement of the library (separate unmodified JAR satisfies this in practice — to be confirmed) and source offer; record that the native `jnidispatch` binaries are redistributed unmodified |

**Recommendation (provisional):** stay at (i) for X-C/X-D. If bundling is wanted later, prefer the Apache-2.0 option for
JNA (fewer obligations for an unmodified, separately replaceable JAR), keep JNA as a separate unmodified file, and ask a
qualified person to confirm compatibility with how RomRaider (GPL-2) and RomLogger are distributed.

**Owner decides:** form (i)/(ii)/(iii); licence option for JNA; whether professional review is required before (iii);
whether temp extraction of `jnidispatch` is acceptable or `jna.boot.library.path` with a pinned, separately inventoried
DLL is required (that would add a native file to RLDI2's scope — not designed yet).

**Unverified / not a legal opinion:** compatibility of LGPL-2.1+/Apache-2.0 with GPL-2 RomRaider in the chosen form; the
licence status of the bundled native binaries beyond the JAR's licence files; RomLogger's own licence. **No
redistribution is enabled by X-C.**

## 3. `FILE_SHARE_DELETE` for RLWR1

**Facts**
- Microsoft: `FILE_SHARE_DELETE` "Enables subsequent open operations on a file or device to request delete access.
  Otherwise, no process can open the file or device if it requests delete access." [upstream: CreateFileW docs].
- Measured trade-off (owned NTFS fixtures, x64 and actual x86) [prior: RLWR1 §4]: `READ_WRITE` blocks rename/delete of
  the source while the reader holds it (Win32 32/5 for the writer); `READ_WRITE_DELETE` allows them — the handle keeps its
  identity and bytes, the old name becomes `READER_NOT_FOUND`, a deleted file `READER_DELETE_PENDING`. Append and
  truncate behave the same in both (truncate → `READER_LENGTH_SHRANK`). RLC2 scenarios pass in both.
- Real RomRaider writer behaviour (does it rename/rotate/delete while logging? how does it react to a sharing
  violation?) has **never been measured** [prior].
- Phase X-C re-ran the RLWR1 suite unchanged on CI (`phase9` group, Windows x64 + actual x86) [see evidence]; no new
  native measurement was added in X-C.

**Options**

| | `READ_WRITE` (no delete share) | `READ_WRITE_DELETE` |
|---|---|---|
| protects against | source being replaced/renamed under the reader | — (relies on identity/path checks) |
| risk to the writer | rotation/rename by the writer can fail while the reader holds the file | none from the reader |
| reader-side handling needed | short holds; retry policy for the writer is RomRaider's (unknown) | delete-pending, same-name replacement, publication race already fail closed (`READER_DELETE_PENDING`, identity/path checks) |

**Recommendation (provisional):** keep `READ_WRITE` (no `FILE_SHARE_DELETE`) as the provisional default for any future
synthetic acceptance, because it fails toward protecting the original data, **and** treat "writer rotation blocked" as a
blocker to be measured, not assumed harmless. Required before any adoption: a real-writer test plan on a **non-car**
Windows host with RomRaider's file logger and synthetic input (owner-approved), measuring rename/rotate behaviour under
both modes, handle hold times, and failure classification.

**Owner decides:** provisional default; whether/when a real-writer measurement may be planned (outside X-C; no X270).
**UNKNOWN:** real writer; behaviour under load; handle leaks under real rotation.

## 4. Windows ACL acceptance criteria

**Facts:** RLDI1 and RLDI2 report `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED` on Windows; POSIX owner/mode checks exist only on
Linux [repo]. No ACL probe exists. X-C implements none (report only).

**What must be inspected (proposal)**

| Object | Why |
|---|---|
| install root and **every parent directory up to the volume root** | a writable parent allows renaming the install directory away and substituting another |
| every listed file (approved JAR, dependency JARs, launcher, profiles) | replacement / in-place modification |
| RLDI/RLOP records and deployment root records | record swap |
| work/data roots, spool, journal directories | already private by RLDB/RLSO1 rules on POSIX; Windows equivalent missing |
| JVM runtime directory and `java.exe` used by the launcher | runtime replacement |
| runtime temp directory (JNA `jnidispatch` extraction, `java.io.tmpdir`) | native library substitution |

Per object: owner SID; DACL with explicit and inherited ACEs; effective rights of principals other than the operating
user for write data, append, write attributes/EA, delete, delete child (on the parent), write DAC, write owner;
reparse-point status (already checked by RLDI); re-inspection after scan (TOCTOU, same pattern as IX-4).

**Proposed classification:** `OWNER_CONTROLLED` only if every ACE granting any of those rights names the operating user,
`SYSTEM` or `BUILTIN\Administrators` (documented as trusted-by-definition), with no unresolved SID, no `Everyone`/
`Users`/`Authenticated Users` write-class right, owner = operating user or Administrators; `MISMATCH` if any other
principal has such a right; `UNKNOWN` for anything not decidable without evaluating group membership, deny/allow
ordering edge cases, privileges (`SeBackupPrivilege`/`SeRestorePrivilege`/`SeTakeOwnershipPrivilege`), conditional ACEs,
or when inspection fails. Never derive PASS from `icacls` text or a DACL string comparison.

**Implementation sketch (not done):** read-only probe through `AclFileAttributeView` + owner, default-off, synthetic
private NTFS fixtures (owner-only; `Users` write; unresolved SID; deny-before-allow); result shown next to RLDI without
changing `MATCHED`. Effective-rights evaluation (`GetEffectiveRightsFromAcl`/`AuthzAccessCheck`) needs native calls (JNA) —
another reason the JNA decision comes first.

**Owner decides:** whether `SYSTEM`/Administrators are trusted principals; whether parent directories up to the volume
root are in scope; whether an ACL `OWNER_CONTROLLED` result may ever contribute to a gate. **UNKNOWN:** everything about
the real X270 ACLs (not inspected, not to be inspected in X-C).

## 5. Minimum Windows version and native prerequisites

**Facts [upstream, Microsoft Learn requirement tables]**

| API used by RLWR1 | Minimum client / server |
|---|---|
| `GetFinalPathNameByHandleW` | Windows Vista / Server 2008 |
| `GetFileInformationByHandleEx` | Windows Vista / Server 2008 |
| `GetVolumeInformationByHandleW` | Windows Vista / Server 2008 (desktop apps only) |
| `CreateFileW`, `QueryDosDeviceW`, `GetDriveTypeW` | Windows XP / Server 2003 |

→ API floor of RLWR1 = **Windows Vista / Server 2008**.

- Azul's "Supported Platforms" page (October 2026) lists Zulu 8 for "Windows (x86 32/64-bit)" on Windows 11 and Windows
  Server 2016/2019/2022/2025; **Windows 10 is not listed** [upstream; table read through a text conversion — verify
  visually]. The X270 runs **Windows 10** with RomRaider's bundled Zulu 8 (1.8.0_504) 32-bit [repo: AGENTS.md §1/§4].
- JNA's README gives no Windows version list [upstream]. The pinned JAR ships `win32-x86` and `win32-x86-64`
  `jnidispatch.dll` [measured]; their own OS floor is not documented in the JAR.
- Prior measurements: Windows 11 dev VM and GitHub `windows-latest` (Windows Server 2022/2025 image) with actual Zulu 8 x86
  [prior + this phase's CI]. Never run on Windows 10.
- Prerequisites RLWR1 enforces: NTFS (`GetVolumeInformationByHandleW`), `DRIVE_FIXED`, no subst alias, no reparse
  components; symlink fixtures need `SeCreateSymbolicLinkPrivilege` (NOT_RUN without it); x86 JVM on x64 Windows uses
  `Sysnative` for 64-bit tools (Phase X-A drive probe) [repo].

**Recommendation (provisional):** declare the *supported* floor as "Windows 10 22H2 / Windows 11, x64 OS, NTFS fixed
drive, Zulu 8 x86 as bundled by RomRaider" only after a non-car Windows 10 run of the synthetic suites; until then state
`MIN_WINDOWS=UNVERIFIED (API floor Vista; tested Windows 11 and Server CI images only)`. Flag the Windows 10 vs. Azul
support-list gap to the owner (Windows 10 reached Microsoft's end of support on 2025-10-14 — general knowledge, not
fetched in X-C; the X270 is the target host).

**Owner decides:** minimum version; whether a non-car Windows 10 VM may be used for synthetic tests; whether the
RomRaider-bundled JRE (not Azul's support list) is the runtime of record. **NOT_RUN:** Windows 10, ARM64, non-NTFS,
network/removable/subst drives (refused by design).

---

## 6. Summary for the owner

| Item | Status after X-C | Provisional recommendation | Blocking? |
|---|---|---|---|
| RLDI2 / F-IX-1 | candidate implemented, synthetic GREEN, default-off | option A; independent review next | yes (not adopted) |
| JNA licence | facts gathered; no distribution | form (i); Apache-2.0 option if bundling later; expert check | yes |
| FILE_SHARE_DELETE | unchanged; real writer UNKNOWN | provisional `READ_WRITE`; plan non-car real-writer test | yes |
| Windows ACL | `UNKNOWN_WINDOWS_ACL_NOT_INSPECTED` | criteria above; read-only probe after JNA decision | yes |
| Minimum Windows | API floor Vista; tested 11/Server only | `UNVERIFIED`; run synthetic suites on non-car Windows 10 | yes |

Unchanged everywhere: synthetic_only, launch/production/ACK disabled, formal_ack=DISABLED, reader_adoption=BLOCKED,
global_old_binary_exclusion=NOT_PROVEN, WRITER_UNPROVEN.
