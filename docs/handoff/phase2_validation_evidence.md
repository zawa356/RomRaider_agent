# Phase II local validation evidence

2026-10-19 JST. Tested final implementation head `82e1d54da767a545027ecbf82f6fd361d29a2a77`. Final changes after this head are documentation only. Successful commands completed with exit0; failed setup attempts are separately retained in the worklog. CI is NOT RUN (runner did not start).

No duplicate aggregate check total is claimed. Full Windows batch runs the per-platform suites; focused final-source reruns confirm the final ownership/competition changes. Local raw logs remain ignored. These extracts and SHA256 identify the retained evidence.

## acceleration-20261019-095142.log

SHA256: `8e94b485b2f4c9e09457482ef44f9d7647e447216ea7eda91db9740f1758bedd`

```text
RUN JDK17 x64 version
RUN Windows x64 test-ingress-journal-prototype.sh
SKIP journal symlink rejection (symlink creation unavailable: FileSystemException)
IngressJournalPrototypeTest passed 33 checks (isolated synthetic only)
IngressHandoffPrototypeTest passed 41 checks (synthetic only)
BLOCKED Windows mandatory FileLock: live reader is NOT supported
SKIP symlink probe test (OS privileges unavailable)
BLOCKED Windows live read attempts=287; product live ingest must remain disabled
LiveJournalReadProbeTest passed 22 checks (Java8 readonly concurrent diagnostics, NO product import)
SplitLockJournalPrototypeTest passed 39 checks (cross-JVM synthetic only)
BLOCKED default provider fileKey unavailable; watermark integration NOT SUPPORTED
DurableWatermarkPrototypeTest passed 6 fail-closed checks; 56 watermark scenarios NOT RUN
NOT RUN symbolic-link fixtures: provider/privilege unavailable FileSystemException
JournalIdentityComparisonTest passed 22 checks; A/C NOT SAFE standalone; OS restart NOT RUN
WatermarkV2ContractTest passed 22 checks (codec/contract only; SHA-256 is integrity, not authentication)
NOT RUN PosixWatermarkV2Test scenarios: no unix:dev/ino view on Windows 11
RUN Windows x64 test-ingress-sqlite-receipts.sh
DurableSpoolTest passed 42 checks (synthetic SQLite only)
ProductSenderTest passed 41 checks (loopback HTTP contract mock only)
LegacyTsvBridgeTest passed 16 checks (synthetic TSV / SQLite)
IncrementalTsvBridgeTest passed 37 checks (synthetic growing TSV, real SQLite; no ECU)
JournalProductBridgeTest passed 35 checks (product v1 SQLite, no ECU)
ProductDeliveryCoordinatorTest passed 47 checks (real SQLite; no ECU)
ProductLegacyScannerTest passed 14 checks (real SQLite, no ECU)
SKIP symlink directory test (OS privileges unavailable)
ProductJournalScannerTest passed 24 checks (SQLite; synthetic closed journal)
ProductBridgeWorkerTest passed 11 checks (separate JVM / synthetic HTTP)
ProductJournalWorkerTest passed 23 checks (separate JVM, SQLite, localhost synthetic HTTP; no ECU)
ProductIngressStatusTest passed 12 checks (read-only SQLite, separate JVM)
FairDeliveryTest passed 19 checks
JournalSqliteReceiptPrototypeTest passed 22 checks (real SQLite, isolated)
RUN Windows x64 test-windows-native-identity.sh
NOT RUN native symbolic-link fixture: provider/privilege unavailable
WindowsNativeIdentityTest passed 17 checks; test-only NTFS primitive, OS reboot/power loss NOT RUN
PASS native READ remains BLOCKED by old writer with ERROR_LOCK_VIOLATION=33
WindowsBoundReadTest passed 21 checks; NOT_COMMITTED, no watermark/DB/ACK
WindowsNativeWriterTest passed 47 checks; handle/flush primitive ONLY, no marker/DB/ACK/power-loss guarantee
BLOCKED full 25-generation concurrent publish progress: Windows replacement refused; exact old/staged/original bytes preserved, no auto-repair
Windows/NTFS native 64-bit Java 17.0.20.1 RLC2 scenarios passed 104 checks; directory=UNKNOWN; JVM halt != power loss; no DB/ACK/product wiring
RUN Windows x64 test-watermark-ledger.sh
NOT RUN PosixWatermarkLedgerTest: no unix:dev/ino view on Windows 11
NOT RUN PosixRotationTest: no unix:dev/ino view on Windows 11
Windows/NTFS native 64-bit Java 17.0.20.1 RLC2 ledger scenarios passed 28 checks; real SQLite; receipts never ACK-eligible; directory=UNKNOWN
Windows/NTFS native 64-bit Java 17.0.20.1 RLM1 rotation scenarios passed 44 checks; nothing deleted/repaired; no product wiring
RUN Windows x64 test-ingress-diagnostics.sh
Ingress diagnostics Windows/NTFS native 64-bit Java 17.0.20.1 passed 37 checks; real SQLite; ACK disabled
RLC2 product projection Windows/NTFS native 64-bit Java 17.0.20.1 passed 21 checks; synthetic only; no ACK/transport
Ingress migration preflight Windows/NTFS native 64-bit Java 17.0.20.1 passed 19 checks; read-only; no conversion/ACK/repair
RUN Windows x64 test-rlc2-preview.sh
RLC2 preview SQLite Windows/NTFS native 64-bit Java 17.0.20.1 passed 43 checks; no Server ACK; no production tables
RLC2 preview faults Windows/NTFS native 64-bit Java 17.0.20.1 passed 44 checks; Runtime.halt is not physical power loss; ACK forbidden
CANDIDATE_LOAD_METRICS observations=1000 sessions=4 snapshot_project_commit_ms=876
RLC2 preview load Windows/NTFS native 64-bit Java 17.0.20.1 passed 32 checks; synthetic only; timings observational; ACK forbidden
RLC2 integration schema passed 13 checks; isolated opt-in only; no production migration/ACK
RLC2 integration engine passed 28 checks; explicit offline candidate; no Server ACK
RLC2 integration snapshot passed 15 checks; external quiescence still required
RLC2 store schema passed 35 checks
RLC2 integration JVM faults passed 21 checks; halt is not physical power loss
RUN Windows x64 test-phase2-lanes.sh
Lane ownership passed 26 checks; no expiring lease; power loss NOT RUN
PASS real Worker subprocess opt-in rejects held lane before import/delivery
V1 lane migration passed 19 checks; isolated copy only; no automatic downgrade/apply
RLC2 publication passed 6 checks; cooperative synthetic Writer only
Cooperative lane fault suite passed 21 checks; Runtime.halt is not power loss
Separate Engine competition passed 7 checks; safety and progress verified separately
RUN Zulu8 x86 version
RUN Zulu8 x86 test-windows-native-identity.sh
NOT RUN native symbolic-link fixture: provider/privilege unavailable
WindowsNativeIdentityTest passed 17 checks; test-only NTFS primitive, OS reboot/power loss NOT RUN
PASS native READ remains BLOCKED by old writer with ERROR_LOCK_VIOLATION=33
WindowsBoundReadTest passed 21 checks; NOT_COMMITTED, no watermark/DB/ACK
WindowsNativeWriterTest passed 47 checks; handle/flush primitive ONLY, no marker/DB/ACK/power-loss guarantee
Windows/NTFS native 32-bit Java 1.8.0_504 RLC2 scenarios passed 102 checks; directory=UNKNOWN; JVM halt != power loss; no DB/ACK/product wiring
RUN Zulu8 x86 test-watermark-ledger.sh
NOT RUN PosixWatermarkLedgerTest: no unix:dev/ino view on Windows 11
NOT RUN PosixRotationTest: no unix:dev/ino view on Windows 11
Windows/NTFS native 32-bit Java 1.8.0_504 RLC2 ledger scenarios passed 28 checks; real SQLite; receipts never ACK-eligible; directory=UNKNOWN
Windows/NTFS native 32-bit Java 1.8.0_504 RLM1 rotation scenarios passed 44 checks; nothing deleted/repaired; no product wiring
RUN Zulu8 x86 test-ingress-diagnostics.sh
Ingress diagnostics Windows/NTFS native 32-bit Java 1.8.0_504 passed 37 checks; real SQLite; ACK disabled
RLC2 product projection Windows/NTFS native 32-bit Java 1.8.0_504 passed 21 checks; synthetic only; no ACK/transport
Ingress migration preflight Windows/NTFS native 32-bit Java 1.8.0_504 passed 19 checks; read-only; no conversion/ACK/repair
RUN Zulu8 x86 test-rlc2-preview.sh
RLC2 preview SQLite Windows/NTFS native 32-bit Java 1.8.0_504 passed 43 checks; no Server ACK; no production tables
RLC2 preview faults Windows/NTFS native 32-bit Java 1.8.0_504 passed 44 checks; Runtime.halt is not physical power loss; ACK forbidden
CANDIDATE_LOAD_METRICS observations=1000 sessions=4 snapshot_project_commit_ms=1940
RLC2 preview load Windows/NTFS native 32-bit Java 1.8.0_504 passed 32 checks; synthetic only; timings observational; ACK forbidden
RLC2 integration schema passed 13 checks; isolated opt-in only; no production migration/ACK
RLC2 integration engine passed 28 checks; explicit offline candidate; no Server ACK
RLC2 integration snapshot passed 15 checks; external quiescence still required
RLC2 store schema passed 35 checks
RLC2 integration JVM faults passed 21 checks; halt is not physical power loss
RUN Zulu8 x86 test-phase2-lanes.sh
Lane ownership passed 26 checks; no expiring lease; power loss NOT RUN
PASS real Worker subprocess opt-in rejects held lane before import/delivery
V1 lane migration passed 19 checks; isolated copy only; no automatic downgrade/apply
RLC2 publication passed 6 checks; cooperative synthetic Writer only
Cooperative lane fault suite passed 21 checks; Runtime.halt is not power loss
Separate Engine competition passed 7 checks; safety and progress verified separately
RUN Zulu8 x86 JournalIdentityComparisonTest
NOT RUN symbolic-link fixtures: provider/privilege unavailable FileSystemException
JournalIdentityComparisonTest passed 22 checks; A/C NOT SAFE standalone; OS restart NOT RUN
RUN Zulu8 x86 SplitLockJournalPrototypeTest
SplitLockJournalPrototypeTest passed 39 checks (cross-JVM synthetic only)
RUN Zulu8 x86 DurableWatermarkPrototypeTest
BLOCKED default provider fileKey unavailable; watermark integration NOT SUPPORTED
DurableWatermarkPrototypeTest passed 6 fail-closed checks; 56 watermark scenarios NOT RUN
RUN PostgreSQL destination safety guards
RUN real HTTP destination and opt-in guards
NOT RUN PostgreSQL preview (use -Postgres with a protected test DSN)
PASS acceleration batch; physical power loss/real ENOSPC/ECU NOT RUN; Windows directory durability UNKNOWN; new path ACK forbidden
```

## phase2-release-x64.log

SHA256: `3cf97e28816b91bb3b86692d196fbdaee796a6ace9e13028640c91fa7eab058a`

```text
Lane ownership passed 26 checks; no expiring lease; power loss NOT RUN
PASS real Worker subprocess opt-in rejects held lane before import/delivery
V1 lane migration passed 19 checks; isolated copy only; no automatic downgrade/apply
RLC2 publication passed 6 checks; cooperative synthetic Writer only
Cooperative lane fault suite passed 21 checks; Runtime.halt is not power loss
Separate Engine competition passed 7 checks; safety and progress verified separately
```

## phase2-release-x86.log

SHA256: `3cf97e28816b91bb3b86692d196fbdaee796a6ace9e13028640c91fa7eab058a`

```text
Lane ownership passed 26 checks; no expiring lease; power loss NOT RUN
PASS real Worker subprocess opt-in rejects held lane before import/delivery
V1 lane migration passed 19 checks; isolated copy only; no automatic downgrade/apply
RLC2 publication passed 6 checks; cooperative synthetic Writer only
Cooperative lane fault suite passed 21 checks; Runtime.halt is not power loss
Separate Engine competition passed 7 checks; safety and progress verified separately
```

## phase2-linux-release-full.log

SHA256: `b640302cf869c7cec30c718479818e8f90734d8b5ea3a4b8263b78cf46b9f71c`

```text
Lane ownership passed 26 checks; no expiring lease; power loss NOT RUN
PASS real Worker subprocess opt-in rejects held lane before import/delivery
V1 lane migration passed 19 checks; isolated copy only; no automatic downgrade/apply
RLC2 publication passed 6 checks; cooperative synthetic Writer only
Cooperative lane fault suite passed 21 checks; Runtime.halt is not power loss
Separate Engine competition passed 7 checks; safety and progress verified separately
RLC2 preview SQLite POSIX/Java passed 42 checks; no Server ACK; no production tables
RLC2 preview faults POSIX/Java passed 43 checks; Runtime.halt is not physical power loss; ACK forbidden
CANDIDATE_LOAD_METRICS observations=1000 sessions=4 snapshot_project_commit_ms=383
RLC2 preview load POSIX/Java passed 31 checks; synthetic only; timings observational; ACK forbidden
RLC2 integration schema passed 13 checks; isolated opt-in only; no production migration/ACK
RLC2 integration engine passed 28 checks; explicit offline candidate; no Server ACK
RLC2 integration snapshot passed 15 checks; external quiescence still required
RLC2 store schema passed 35 checks
RLC2 integration JVM faults passed 20 checks; halt is not physical power loss
CANDIDATE_LOAD_METRICS observations=10000 sessions=11 snapshot_project_commit_ms=1511
RLC2 preview load POSIX/Java passed 59 checks; synthetic only; timings observational; ACK forbidden
```

## phase2-large-x86.log

SHA256: `dd3f9824db81292765a9e1298519b69bfd2bc3b7787dfc590f5375cb54c7ea73`

```text
CANDIDATE_LOAD_METRICS observations=10000 sessions=11 snapshot_project_commit_ms=5364
RLC2 preview load Windows/NTFS native 32-bit Java 1.8.0_504 passed 60 checks; synthetic only; timings observational; ACK forbidden
```

## phase2-owned-http.log

SHA256: `4c80823520139375efb0db58de42fd2ee042b7d4158b81b46ef55c9bfd8ec3ae`

```text
Real RLC2 Server HTTP E2E passed 35 checks; test ACK only; no production access
Owned PostgreSQL immediate-stop/restart HTTP: PASS; existing cluster never stopped
```

## Server / environment

Server isolated migrations: upgrade0 / upgrade-again0 / check0; pytest57 passed,1 warning. Local log `C:\Dev\RomLogger\logs\server-pytest-20261019-094046.log`. Existing PostgreSQL55432 ready; invocation-owned PostgreSQL PID files0 after test. Existing Docker14 all Up; no remaining test JVM found on VPS. RomRaider.jar absent; root test.sh NOT RUN. Windows C: NTFS.

PowerShell AST errors0; YAML parse PASS; generated tracked paths0; sensitive connection-value matches in code diff0. No billing change or workflow redispatch. Actions39182473844 annotation explicitly says account payments/spending limit blocked job start; steps=[] in all jobs.
