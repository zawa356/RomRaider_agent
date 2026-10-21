# RLWR1 — Windows product RLC2 reader candidate (Phase IX-2, 2026-10-21)

Status: **implemented as a synthetic candidate, not adopted**. Not wired into `OfflineCandidateEntry`, RomRaider Logger,
the X270 or any production path. `adoption=BLOCKED` until the owner decisions in §5 are made and an independent
acceptance passes. Design origin: [windows_product_reader_v1_design.md](../design/windows_product_reader_v1_design.md).

## 1. Classes

| Class | Role |
|---|---|
| `prototype.ingress.WindowsProductReader` | RLWR1 logic; implements the unchanged `WatermarkV2Prototype.ReadOpener`/`ReadHandle`. No JNA import, no `main`. |
| `prototype.ingress.WindowsProductReaderJna` | the only product class importing JNA (kernel32 binding); loaded by fixed class name from `create()` so the logic class compiles and loads without JNA. |

`WindowsProductReader.create(ShareMode)` → JNA reader, or `READER_PLATFORM_UNSUPPORTED` (non-Windows) /
`READER_NATIVE_UNAVAILABLE` (no JNA / linkage failure). There is no `java.io` fallback.

## 2. Contract (all fail-closed)

1. Path: absolute, `normalize()`-equal, drive-letter form `X:\…`; UNC/`\?\`/device forms refused before any open.
2. Drive: `GetDriveTypeW == DRIVE_FIXED`; `QueryDosDeviceW` must not be a `subst` alias (`\??\…`).
3. Every directory component is opened with `FILE_FLAG_OPEN_REPARSE_POINT|BACKUP_SEMANTICS` and must be a non-reparse
   directory (junction, directory symlink, mount point → `READER_PATH_LINK`).
4. File: `CreateFileW(GENERIC_READ, share, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT)`; invalid handle detected for
   NULL, `-1` and 32-bit `0xffffffff`.
5. From the **same handle**: file system name must be `NTFS` (`GetVolumeInformationByHandleW`); final normalized DOS path
   (`GetFinalPathNameByHandleW`) must equal the requested path (case-insensitive) → otherwise `READER_PATH_CHANGED`;
   `BY_HANDLE_FILE_INFORMATION` (52-byte image) + `FILE_STANDARD_INFO` (24-byte image): not directory, not reparse,
   exactly one link, not delete-pending, both size sources equal.
6. Identity: volume serial + file index high + low (12 bytes, `WINDOWS_NTFS_VOLUME_FILE_INDEX`), from the reading handle;
   must not change for the life of the handle.
7. Reads: `offset ≥ 0`, `0 ≤ length ≤ MAX_READ` (8 MiB = RLC2 journal quota), no overflow, never past the current
   length (no silent truncation); `SetFilePointerEx` + `ReadFile` in ≤64 KiB chunks; 0-byte read = `READER_SHORT_READ`;
   identity/length/delete-pending re-checked after the bytes are taken. Growth is accepted (append-only writer); any
   length below a previously observed length is `READER_LENGTH_SHRANK`.
8. `close()` idempotent; any use after close = `READER_HANDLE_CLOSED`; every post-open refusal closes the handle.
9. Never writes, locks, truncates, repairs or creates files.

## 3. Stable codes

`READER_PLATFORM_UNSUPPORTED`, `READER_NATIVE_UNAVAILABLE`, `READER_PATH_INVALID`, `READER_PATH_LINK`,
`READER_PATH_CHANGED`, `READER_FILESYSTEM_UNSUPPORTED`, `READER_NOT_FOUND`, `READER_OPEN_DENIED`,
`READER_SHARING_VIOLATION`, `READER_NOT_REGULAR_FILE`, `READER_REPARSE_POINT`, `READER_HARDLINK_PRESENT`,
`READER_DELETE_PENDING`, `READER_IDENTITY_UNAVAILABLE`, `READER_IDENTITY_CHANGED`, `READER_LENGTH_UNSTABLE`,
`READER_LENGTH_SHRANK`, `READER_BOUNDS`, `READER_SEEK_FAILED`, `READER_READ_FAILED`, `READER_LOCK_VIOLATION`,
`READER_SHORT_READ`, `READER_HANDLE_CLOSED`, `READER_HANDLE_INVALID`. `Refusal.code` + `Refusal.win32` (number only);
the exception message is `CODE` or `CODE win32=N` — never a path or Win32 text. Known limit (unchanged): adapters that
catch `IOException` generically lose the code (Phase VII F2 note).

## 4. Measured share-mode comparison (owned NTFS fixtures, x64 and actual x86)

| While RLWR1 holds the source | `READ_WRITE` | `READ_WRITE_DELETE` |
|---|---|---|
| writer append / truncate | allowed; growth visible, truncation → `READER_LENGTH_SHRANK` | same |
| rename (`Files.move`, `MoveFileExW`) | **blocked** (Win32 32/5) — writer rotation must wait | allowed; handle keeps identity and bytes; old name → `READER_NOT_FOUND` |
| delete | **blocked** | allowed; handle → `READER_DELETE_PENDING` |
| RLC2 `WatermarkV2Scenarios` | pass | pass |

Which one the real RomRaider writer needs is **not known** (no real-writer measurement): `share_delete_decision =
OWNER_DECISION_REQUIRED`.

## 5. Owner decisions (not taken here)

- `FILE_SHARE_DELETE` while reading (table above).
- JNA 5.14.0 (Apache-2.0/LGPL-2.1 dual) in a distributed offline tool next to GPL-2 RomRaider: `distribution_license =
  OWNER_DECISION_REQUIRED`. No release, no redistribution claim.
- Minimum Windows version; network/redirected/removable drives stay refused.

## 6. Tests

`bash scripts/test-phase9-reader.sh` (x64 JDK17; `ROMLOGGER_EXPECT_DATA_MODEL=32 … --run-only` under actual Zulu8 x86):
fake-native logic test (compiled without JNA; all codes, 32/64-bit invalid handles, chunking, post-read re-checks, handle
accounting) and, on Windows, the native NTFS test (identity/bytes equal to the test-only reader, 64 KiB boundaries,
0-byte file, same-name and byte-identical replacement, rename/delete/rotation per share mode, exclusive writer →
`READER_SHARING_VIOLATION` win32 32, append/truncate, hard link, junction, absent/directory/relative/non-normalized/UNC,
500-open handle accounting, no-JNA child → `READER_NATIVE_UNAVAILABLE`, RLC2 scenario suite in both share modes).
NOT_RUN (never PASS): symlinks without `SeCreateSymbolicLinkPrivilege`; non-NTFS, mapped and subst drives (no owned
fixture without system changes; logic covered by the fake test). Linux: fake test only, native NOT_RUN.

Still UNKNOWN / unproven: directory durability, process provenance, writer quiescence (WRITER_UNPROVEN), real
RomRaider writer behaviour.
