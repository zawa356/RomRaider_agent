# Windows product RLC2 reader — design v1 (Phase VIII-4, design only, not implemented)

Status: **design proposal**. Nothing in this document is wired into the entry, the real RomRaider Logger, the X270 or
any production path. Adoption requires a new reader version, its own implementation PR and an independent acceptance.

## 1. Where we are (code-reviewed facts)

- The only Windows reader is **test code**: `WindowsWatermarkV2Test.READER` (JNA, `src/test/java`). The entry accepts it
  only as `reader=WINDOWS_NATIVE_TEST_ONLY`, loaded from the pinned test JAR (RLOP1). Without it the entry cannot read
  RLC2 sources on Windows outside synthetic tests.
- What the test reader does (and the evidence behind it, `WindowsWatermarkV2Test`, `WindowsNativeIdentityTest`,
  `WindowsBoundReadTest`, Windows x64 and actual Zulu8 x86 runs in Phase III–VII):
  - rejects any symlink path component (`Files.isSymbolicLink` walk) and any filesystem whose type is not `NTFS`;
  - `CreateFileW(GENERIC_READ, share=READ|WRITE|DELETE, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT)`;
  - identity **from the same handle** that reads: `GetFileInformationByHandle` → volume serial + file index (12 bytes,
    tag `WINDOWS_NTFS_VOLUME_FILE_INDEX`); directories and reparse points rejected;
  - size from the handle; reads with `SetFilePointerEx` + `ReadFile` in ≤64 KiB parts, short read = error;
  - invalid-handle check covers 32-bit (`0xffffffff`) and 64-bit values.
- Things that are **not** proven and must stay separate: Java `FileLock` semantics on Windows, directory-entry
  durability (UNKNOWN; `MOVEFILE_WRITE_THROUGH` + directory `FlushFileBuffers` are observations only), WAL behaviour,
  running-process provenance (UNKNOWN), noncooperative writers (WRITER_UNPROVEN).

## 2. Required contract for a product reader (proposed RLWR1)

| Area | Requirement |
|---|---|
| Interface | implements `WatermarkV2Prototype.ReadOpener`/`ReadHandle` unchanged (identity, length, read, close) |
| Packaging | product package (not test), in the RLDI1 install as `approved_jar` content; native access via a **pinned** dependency (JNA 5.14.0 SHA-256 already pinned for tests) listed as `dependency_jar`; no download at runtime; no `main` |
| Runtimes | Windows x64 JDK17 and **actual Zulu 8 x86** (`sun.arch.data.model=32` asserted); RomRaider runs 32-bit |
| Path rules | absolute, normalized, no symlink/junction/reparse component (checked per component on the opened handle path, not only by name), NTFS only (ReFS, FAT, exFAT, network/SMB, subst/mapped drives refused unless separately accepted) |
| Open | read-only access; share mode must not block the RomRaider writer (READ|WRITE; DELETE share is a decision point, see §4) |
| Identity | from the reading handle only; equal to the RLC2 marker identity before and after every read; mismatch = refusal |
| Reads | bounded (segment quota), exact length, no partial acceptance; offsets from the committed watermark only |
| Rotation/seal | sealed segments verified against the rotation manifest; the active segment read only up to the committed marker |
| Writer coordination | the reader never writes, locks or truncates; a writer holding the file is normal; a rename/replace/delete during a read must surface as identity or length mismatch, never as data |
| Failure taxonomy | stable codes only: `READER_PATH_LINK`, `READER_FILESYSTEM_UNSUPPORTED`, `READER_OPEN_DENIED` (with Win32 error number), `READER_SHARING_VIOLATION`, `READER_IDENTITY_UNAVAILABLE`, `READER_IDENTITY_CHANGED`, `READER_SHORT_READ`, `READER_LENGTH_SHRANK`, `READER_REPARSE_POINT`, `READER_HANDLE_INVALID`, `READER_NATIVE_UNAVAILABLE`; no free-text messages or paths in JSON |
| Fail-closed | any native error, unknown filesystem or unexpected attribute is a refusal; there is no fallback to `java.io` reads |

## 3. Acceptance plan (for the future implementation PR)

1. Unit + scenario suite reusing `WatermarkV2Scenarios` with the product reader (x64 and actual x86).
2. Fault injection on owned temp files: rename-while-open, replace with same name, hard link, junction component,
   file grown/shrunk mid-read, writer holding READ|WRITE share, delete-pending, `ERROR_SHARING_VIOLATION`, ReFS/FAT
   or virtual drives where available (else NOT_RUN, never PASS).
3. Entry: new profile reader value (e.g. `WINDOWS_NATIVE_V1`) accepted only with the pinned JNA hash; RLDI1 must list
   the JAR; the test-only reader value stays for tests.
4. Independent review of the native calls (handle leaks, 32-bit structure layout, error mapping).
5. Still synthetic: no RomRaider Logger, no X270, no production path; durability and process provenance stay UNKNOWN.

## 4. Open decisions (need owner input; not decided here)

- `FILE_SHARE_DELETE` while reading: allowing it keeps the writer's rotation unblocked but lets a rename happen during a
  read (detected by identity re-check); refusing it may break RomRaider's own file handling. Needs a measured decision
  on the real writer behaviour, which is out of scope for synthetic work.
- Whether JNA (Apache/LGPL dual licence) may ship in the offline tool distribution (GPL v2 RomRaider context, AGENTS §2).
- Minimum Windows version and whether network/redirected drives are ever acceptable (currently: no).
