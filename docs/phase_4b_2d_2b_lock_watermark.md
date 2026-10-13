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
