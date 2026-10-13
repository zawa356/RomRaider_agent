# Phase 4b-2d-2b — lock / durable watermark 独立実験

## 2b-1 lock候補と移行反例

既存writer / 起動設定 / ProductJournalScanner / 製品SQLite / ACK / Server IFは変更しない。
試験は合成一時ディレクトリと別JVMのみ。既存原本を置換・切り詰め・削除しない。

| 候補 | 旧writer排除 | Windows READ | 判定 |
| --- | --- | --- | --- |
| journal全体排他（既存） | あり | writer中BLOCKED | 維持、live不可 |
| `.writer.lock`だけ専有 | なし（両起動順の反例） | 妨げない | 移行には不採用 |
| 別lock＋8MiB位置の1byte guard | 旧whole-file lockと重なる | readerはguard未満のprefixだけ | 独立PoC、CI判定待ち |

`SplitLockJournalPrototype`はcoordination lock→journal guard→identity→同じjournal
handleによる厳密CRC/sequence/UTF8/容量検証の順で初期化する。旧writerを変更せず、
新旧ロック競合をOSで拒否する。8MiBは固定データ上限であり、guardは書き込まず
EOFを伸ばさない。任意の容量増加はこの証明を無効にするため別判断。

ロックの相互排除は協調参加者・同じinode・同じファイルシステムでの限定結果。
管理者がlock/journalをunlink・rename・置換すれば別inodeが生まれ、保護不能。
**同一JVMでjournalへの別descriptorを開閉するとLinux POSIX lockが解放され得る。**
初回constructorがLiveJournalReadProbeを開閉して旧writer排除に失敗した。
所有RandomAccessFileの検証へ修正した。reader/競合writerは別JVMでのみ検証。
同一JVM内統合、network filesystem、実X270/Zulu8 32bitは未検証。

appendのI/O失敗は失敗latchして以降追記を拒否し、曖昧な原本は保持する。
従来writerの自動rollbackを新PoCへ持ち込まず、review待ちを優先する。
フレーム完結はfsync watermarkではない。read probeから製品cursor/ACKは進めない。

試験コマンド: `bash scripts/test-ingress-journal-prototype.sh`。
新39項目、既存99項目。別JVMの旧→旧、旧→新、新→旧、新→新の拒否と
new writer保有中の別JVM live READ、純粋別lockの両方向二重ownership反例、
閉鎖後の原本バイト保存・再開連番、quota時原本保全を確認する。
各子JVMにdeadlineがあり、失敗時は自身の子のみ終了させる。

## 未承認ゲート

- PoCから既存writer/Logger/Workerへ配線しない。
- `--journal-foreground`、ECU/X270、安全ガード緩和、原本retentionは未実装。
- watermark設計、dir fsync/物理電源断、rotationは次の独立Phase。
- Windows実結果はCIログを確認して追記する。Linux PASSから推定しない。

## 2b-2 RLC1 watermark候補

実験用`.rlj.commit`はbig-endian固定104bytes:
magic RLC1(4)、UUID(16)、sequence(8)、offset(8)、fileKeyのSHA256(32)、
全確定prefixのSHA256(32)、先頭100byteのCRC32(4)。version不一致は拒否。
fileKey未提供のFSは保護できたと推定せず停止する。原本ファイルの移設/
network filesystem/管理者によるinode置換の運用は承認前。

`checkpoint()`は所有handle検証→getFD.sync→tmp CREATE_NEW→force(true)→
ATOMIC_MOVE+REPLACE_EXISTING→親directory READ channel force(true)。
atomic move未対応は例外で停止しtmpを残す。非atomic fallbackは実装しない。
dir force失敗はUNCONFIRMEDを返す。これは成功したjournal/temp syncの事実と
区別し、物理電源断後のdirectory entry保存を保証しない。

readerは別JVMでのみ使用し、markerが安定している間の確定prefixだけ返す。
マーカー欠落/破損/UUID違い/offset先行/途中frame/seq矛盾/原本改変/
byte-identical inode置換/tmp残存をfail closed。既存製品DBへの挿入機能なし。
読めた完全tailであってもwatermarkを超えていればentriesへ含めない。

以前のSnapshotを渡せば連番退行・既読prefix変更を拒否。ただしメモリのみに
保持するので別reader再起動での永続highwaterは未実装。古いvalid markerを
初観測した場合は古い安全prefixのみ返す。製品cursor/ACKへの採用には別の
SQLite永続highwater・原子的commitの検証が必要。

合成負試験: 3段階のIOExceptionと別JVM halt、旧marker/部分marker、
CRC/UUID/seq/offset不整合、原本切詰め/内容改変、同内容新inode置換、
再計算したmarker hashに対するframe CRC/UTF8/seq検査、identity欠落、
tmp残存の保持、別JVM live readとguard維持。計56項目。
擬似storage errorは実ENOSPC/実ディスク満杯試験ではない。JVM haltは物理
電源断ではない。dir sync/zero-lossの強い保証を試験PASSから推定しない。

## 2b-2のWindows blockerと判定訂正

初回Windows CI38409471808/38409589169はfileKey必須でFAIL。
[OpenJDK17公式WindowsFileAttributes](https://github.com/openjdk/jdk17u/blob/master/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java)
のfileKey()は常にnull。ensureAccurateMetadataは内部native値の取得に関わるが
public APIのfileKeyは変わらないため、設定で解決する推測は撤回した。
既存起動設定を変えず、nullのときはwatermark発行/readを拒否し原本を保持する。
Windowsではfull watermark56ケースNOT RUN/BLOCKED、atomic storage primitive3＋
fail-closed3の計6ケースだけを実行する。Linuxはprimitive3＋full56の59ケース。

## 2b-3 — BLOCKED / 未実装のrotation境界設計

2b-2が両OSで成立していないため、新writer→reader→SQLiteの結合を構築しない。
旧--journal-once、ProductJournalScanner、正式v1プロトコルは置き換えない。
次の条件は**設計候補であり実証ではない**。

1. 原本journal＋identity＋最終watermark＋writer.lockは専用namespaceの一組。
   使用中に移動/unlink/lock再作成しない。seal後も原本パス/UUIDと全prefixを保持。
2. 1本8MiB以下、16原本以下（最大128MiBは原本のみ、sidecar/SQLite容量は別）。
   quota到達時は取得/queueの明示停止・review通知を別Supervisorで判断する。
   無制限の追記・原本の自動削除・未ACKのretentionは行わない。
3. 原本末尾を最終fsync→watermark確定→writer停止を確認してsealする。
   directory永続化未確認ならsealedの電源断保証は成立しない。
   次原本は新UUIDの完全なセットをCREATE_NEWで作り、旧セットはそのまま保持。
4. manifestはsource UUID/immutable path/最終sequence/offset/prefix hashを記録。
   欠落・同UUID二重path・orphan sidecar・16本超・既読prefix退行はfail closed。
   セット更新の途中故障も旧セットとstaged証拠を保持、暗黙修復なし。
5. downstream SQLiteの原文receiptとpersistent highwaterは同一transaction。
   watermark以下でも既読prefix一致とcursor退行を検証し、異常なら保全停止。
   DB commit前後で別JVM haltを試験する。製品Server ACKとの連動は別ゲート。
6. 現行製品v1_source_claimsはsessionごと単一source経路を要求する。
   同一sessionを複数journalへ回す契約、run/sequence/決定的IDの継続は未設計。
   この意味を夜間に変更しない。次の設計レビュー対象として残す。

Windows候補比較:

| 候補 | 利点 | 追加検証・承認対象 |
| --- | --- | --- |
| hardlink anchor＋公開isSameFile | 同内容別inodeを区別できる可能性 | NTFS/Linuxの別handle close、link権限、anchor保持・dir同期・コピー時復旧 |
| Windows native volume/file index | inode相当の識別 | Java8/x86 binding、追加依存、JVM停止・OS再起動時識別 |
| UUID内包の新journal header | source識別を原本に固定 | RLJ1互換/旧writer移行、同UUIDコピー・原本改変/水準 |

いずれも今夜の既存writer/本番配線には採用しない。CRC/SHAは認証ではない。
fileKeyが出るLinuxでもOS再起動・inode再利用・物理電源断は未実証。
全取得値の欠損ゼロ・実車でのdurabilityは主張しない。
