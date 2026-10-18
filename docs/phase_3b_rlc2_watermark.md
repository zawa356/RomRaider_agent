# Phase 3b — RLC2 cross-platform durable watermark 独立PoC

対象: `src/main/.../prototype/ingress/WatermarkV2Prototype.java`（codec・確定手順・POSIX実装）と
`src/test/.../WatermarkV2Scenarios.java`／`WatermarkV2ContractTest`／`PosixWatermarkV2Test`／
`WindowsWatermarkV2Test`（JNA test-only）。製品writer・Logger・Worker・SQLite cursor・ACK・Server IFは
**変更も配線もしない**。旧RLC1（`DurableWatermarkPrototype`）は変更しない。

## 3b-1 RLC2 marker契約

`<source>.rlj.commit2`、big-endian固定144 bytes。

| offset | bytes | 内容 |
| --- | --- | --- |
| 0 | 4 | magic `RLC2` (0x524c4332) |
| 4 | 2 | version = 2（他は拒否） |
| 6 | 1 | identity tag: 1=`POSIX_DEV_INODE`、2=`WINDOWS_NTFS_VOLUME_FILE_INDEX` |
| 7 | 1 | identity長（tagごとに固定: 16 / 12。不一致は拒否） |
| 8 | 32 | identity値（POSIX: dev(8)+ino(8)、Windows: volume serial(4)+file index high(4)+low(4)）。残りは0必須 |
| 40 | 16 | 原本UUID（RLI1 sidecar） |
| 56 | 8 | generation（1開始、writerは+1のみ） |
| 64 | 8 | 確定sequence |
| 72 | 8 | 確定offset（≤8MiB、sequence/offsetの整合はRLC1と同じ） |
| 80 | 32 | 確定prefix全体のSHA-256 |
| 112 | 32 | 先頭112 bytesのSHA-256（整合性検査。**認証ではない**） |

- tagが異なるidentityは同じbytesでも等しくない。POSIXとWindowsの値を混同しない。全0のidentityは拒否。
- **RLC1は読み替えない**。`.commit`（RLC1）が存在すればpublishを拒否し（`LegacyMarkerException`）、bytesを保持。
  RLC2の位置にRLC1 magicがあればreaderも同じ例外で拒否。RLC1→RLC2移行は別の契約・承認が必要。
- 判定不能（identity取得不能、unix viewなし、NTFS以外、部分marker、tmp残存）はすべてfail closed。

## 3b-2 確定手順（`publish`）

1. `<source>.commit2.lock`をtryLock（別JVMのpublisherは拒否）。RLC1残存・tmp残存は拒否。
2. writer所有handleからidentity・長さ・全bytesを取得し、**writerがメモリに保持した書込記録（長さ・SHA-256）と完全一致**を要求
   （D-044と同じ考え方。CRC-validな書換えも拒否）。RLJ1 frameを厳密検証。
3. 既存RLC2があれば: 旧prefix hash一致、identity/UUID一致、generation+1、sequence/offset単調。新規frameなしなら冪等（`alreadyCommitted`）。
4. 所有handleでsync（POSIX `FileDescriptor.sync`、Windows `FlushFileBuffers`）→ `JOURNAL_SYNCED`。
5. sync後に同じhandleでidentity・長さ・prefix SHA・UUIDを再検証 → `SOURCE_REVERIFIED`。
6. tmpを`CREATE_NEW`で作成し半分書込（`TEMP_CREATED`）→残り（`TEMP_WRITTEN`）→`force(true)`（`TEMP_SYNCED`）。
7. identity再確認後に原子的置換。POSIX: `ATOMIC_MOVE`+`REPLACE_EXISTING`。Windows: `MoveFileExW(REPLACE_EXISTING|WRITE_THROUGH)`。
   非atomic fallbackなし。失敗時tmpは保持（review必須）→ `REPLACED`。
8. directory durability判定 → `DIRECTORY_CHECKED`。POSIX: 親directoryの`force(true)`が成功すれば`OS_REPORTED_SYNCED`、
   失敗は`UNKNOWN`。**Windowsは常に`UNKNOWN`**（directory handleの`FlushFileBuffers`が成功を返しても、
   directory entryの電源断耐性はWin32で文書化されていないため昇格しない）。`powerLossDurable()`は常にfalse。
9. 公開markerのbytesが書いたものと一致することを確認。

reader（`readCommitted`、別JVMで使用）: tmp残存→拒否。markerをbounded read→decode→previousとの単調性。
**同じread handle**からidentity・長さ・prefixを取得（Windows native。POSIXは下記の差異）。identity/UUID/prefix SHA/
frame scan/sequence一致を検証し、読み取り後にidentity・marker bytes・tmp不在を再確認。watermarkを超えるtailは返さない。

### Linux（POSIX）との差異

- Javaは所有descriptorを`fstat`できないため、POSIX identityは**pathの`unix:dev`/`unix:ino`を前後でサンプル**し、
  path sizeとhandle長の一致を要求する。Windowsのhandle bindingより弱い。rename-overはpublish時のidentity/size不一致で検出。
- POSIXは所有中のsource・open中のmarkerへのrename-overを許す。Windowsは`SHARE_DELETE`なしの所有handleで拒否する
  （試験で両方の挙動を`DIFFERENCE`として記録）。
- `unix` viewのないplatform（Windows）では`PosixOwnedSource`/`POSIX_READER`はfail closed。

## 3b-3 試験（合成TEMPのみ）

`WatermarkV2ContractTest`（OS非依存、22）: 往復、tag/長さ、全0、tag違い同bytes、RLC1（104 bytes・144 bytes）、
部分/超過、magic、digest、version、未知tag、padding、generation 0、8MiB超offset、sequence/offset不整合。

`WatermarkV2Scenarios`（OSごと、Windows x64 96 / x86 97）:

- 発行・tail除外・gen単調・冪等、別JVM reader（writer所有中）。
- stale/巻戻り: previous gen2に対するgen1 markerを拒否。**COUNTEREXAMPLE**: previousを持たない新reader は古い安全prefixを受理
  → 永続highwaterは3c必須。
- CRC-valid改変: 確定frameのpayload+CRC再計算をprefix SHAで拒否、writerも上書き発行を拒否、証拠と旧marker保持。
- 書込後の改変: writer書込記録との不一致でpublish拒否、marker/tmpを作らない。
- 同内容置換（閉鎖時）、所有中rename-over（Windows拒否／POSIX許可だがpublish拒否）。
- 一式コピー・previous付き読取・UUID sidecarコピーをidentityで拒否。
- marker破損: 部分、bit flip、digest再計算済みのoffset/sequence改変、未知version、tag差替え、padding。
  **COUNTEREXAMPLE**: コピーへidentityを向け直しdigestを再計算した偽造markerは受理される（SHA-256は認証ではない）。
- RLC1共存拒否とbytes保持、RLC2位置のRLC1 bytes拒否、tmp残存拒否、8MiB超過sourceの拒否と保持。
- 7段階すべてで(a)同一JVMのIOException注入、(b)別JVMの`Runtime.halt`。置換前は旧gen1のみ、tmp段階は残存保持とreader拒否、
  置換後はgen2可視（呼出側には曖昧な結果）・retryは`alreadyCommitted`。**JVM haltは物理電源断ではない**。
- 別JVMのcommit lock保持中は発行拒否。別JVMの連続readerは25世代の連続発行中に不整合を一度も返さず（fail closedのみ）、
  静止後の最終読取が最終gen 26を返す。
- markerをdelete共有なしで開いたままの置換: NTFSは拒否・旧marker保持・tmp保持、POSIXは置換（`DIFFERENCE`）。

実行:

```bash
# JDK17でcompile（Git Bash）
bash scripts/test-ingress-journal-prototype.sh      # Contract + POSIX(Linuxで実行、WindowsはNOT RUN)
bash scripts/test-windows-native-identity.sh        # WindowsWatermarkV2Test（x64）
# 同じclassesをZulu8 x86で
ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-windows-native-identity.sh --run-only
```

## 残るゲート（3c/3dへ進む前提）

1. **Windows directory durabilityはUNKNOWN**。物理電源断・OS再起動・実ENOSPCはNOT RUN。
2. 古いmarkerの受理（previousなし）と偽造marker受理の反例 → 永続highwater/expected identityを
   SQLite原文receiptと同一transactionで保持する設計（3c）が必須。
3. POSIX identityはpath-sampledでhandle bindingではない。
4. 置換後の失敗は曖昧（markerは可視）。呼出側は再publishの`alreadyCommitted`で確認する必要がある。
5. RLC1→RLC2移行、製品writer/Workerへの配線、JNAの製品採用は未承認。
