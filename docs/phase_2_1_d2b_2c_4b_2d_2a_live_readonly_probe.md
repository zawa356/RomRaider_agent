# Phase 2.1-D2b-2c-4b-2d-2a — ライブJournalの読み取り専用診断

**結論：writerと並行してRLJ1を読み取るための診断プローブを実装。製品v1取込・SQLite cursor・Server ACK・本番Workerには接続しない。**

## 現行の安全上の問題と今回の境界

- 既存 `IngressJournalPrototype` は `RandomAccessFile("rw")` と排他的 `FileLock` を取り、各フレームをheader、body、fsyncの順で追記する。writerがlockを持つ間、既存`importJournal()`を呼ぶと競合する。
- **重要**：header/bodyが読めたことやCRCが一致することは、writerの`getFD().sync()`が完了した証明にはならない。したがって観測された完全フレームをそのまま製品v1 SQLiteへ確定保存してはならない。
- 新規`LiveJournalReadProbe.inspect(File)`はJava NIO `FileChannel.open(READ)`だけを使い、writerのFileLockを取得せず、原本を作成・修復・切り詰めず、SQL/HTTPにも触れない。1回の上限は8MiB、各行128KiB、逐次読み取りで一定メモリ。
- フレームのbig-endian magic、length、sequence、CRC32、厳密UTF-8、改行禁止を検査。完全フレーム数とバイト境界、観測サイズのみを返し、生ログ本文は返さない。ファイルサイズ、mtime、fileKeyの変化を検知した場合は`CHANGING`、末尾に未完成のheader/bodyを観測した場合は`INCOMPLETE_TAIL`、安定して完結した場合は`COMPLETE`。安定したCRC/順序/UTF-8不一致はIOExceptionで拒否する。
- `JournalIdentityPrototype.readExisting()`を新設。従来`open()`はwriter用の初回ID作成APIとして維持するが、診断プローブ・`ProductJournalScanner`・`IncrementalTsvBridge.importJournal`は**既存IDを読むだけ**に変更。sidecarが判定直後に消えても、新IDを勝手に生成しない。プローブはスキャンの前後にIDを再読込し、途中で変われば失敗する。
- **本Phaseでは`ProductBridgeWorker --journal-once`のオフライン限定を変更しない**。自動常駐`--journal-foreground`はまだサポートしない。

## CloudPC実試験

- `scripts/test-ingress-journal-prototype.sh`：旧journal34、旧handoff41、新live-readonly probe **24項目PASS**、計99項目。
- `scripts/test-ingress-sqlite-receipts.sh`：既存SQLite/receipt/Worker **344項目PASS**。合算して**443項目PASS**。
- 新規試験：writer排他lock保有中の読取、160回のfsync付き追記とreader最大320回の並行検査、途中header/途中body/復旧完了、安定CRC破損、sequence gap、UTF-8不正、source/identity不在、quota8MiB超、symlink（OSで許可される環境）、読取専用identity APIがsidecarを生成しないことを確認。
- 追加の5反復試験：各160回追記・最大320回検査を5回実行、すべてPASS。いずれも合成一時ファイルのみで実ECU/車両に接続なし。
- **Windowsライブ読取はBLOCKED**：初回CI [#38400570091](https://github.com/zawa356/RomRaider_agent/actions/runs/38400570091) で、writerの排他ロック保有中はFileChannel.readが「別プロセスがファイルの一部をロックしている」エラーで拒否。Linuxの成功をWindowsへ一般化しない。テストでは既知の拒否をBLOCKEDと明記し、writer CLOSE後に全フレームを確認。symlink作成不可はSKIP。
- **[PR #25最終journal CI #38401119395](https://github.com/zawa356/RomRaider_agent/actions/runs/38401119395)**：Ubuntu/Windows各ジョブのJava8 compile＋journal/SQLite実試験ステップPASS。WindowsログではBLOCKED Windows mandatory FileLockと明記されている。[製品CI #38401119457](https://github.com/zawa356/RomRaider_agent/actions/runs/38401119457) は両OS製品SQLite PASS。[E2Eゲート #38401119352](https://github.com/zawa356/RomRaider_agent/actions/runs/38401119352) は安全条件検査PASS、実Server E2EステップSKIPPED。今回の変更でCloudPC実Postgres E2Eは実行していない。
- 物理電源断、ディスク満杯、Zulu8 32bit、大量長時間ロードテスト、実サーバー統合は未実施。

## 次の判断ゲート

1. **fsync完了位置をreaderに伝えるdurable watermark**（単調連番・source UUID・既存フレームSHA/checksum・atomic/耐電源断の更新方式）の設計と、failpointを挟む異常終了試験を行う。watermark不在はfail closed。
2. writerの途中書込とロールバック中に読んだcomplete-but-not-fsyncedフレームを、今回の診断出力から勝手に昇格させない。
3. source rotation後の旧原本保持、途中切断、ディスク容量上限（8MiB）、同じsource UUIDの衝突、複数processのreader/worker実行を安全に調停する。
4. Windows/Java8実機及びSupervisor通知/復旧レビューの認可設計を別Phaseで実施する。

**判定：診断プローブのJava8/Linux合成検証完了。製品取込とライブ配備は保留。**
