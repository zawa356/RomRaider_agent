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
