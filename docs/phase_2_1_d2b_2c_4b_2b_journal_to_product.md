# Phase 2.1-D2b-2c-4b-2b：Journal原本から製品v1 SQLiteへの共有変換

**検証済み範囲：明示呼び出し型の取込メソッドと実SQLite。Logger/ECU、本番Worker、Serverへの自動接続は未実装。**

## 目的とデータフロー

```text
現行運用経路（今回変更なし）
Logger callback → bounded queue → 旧TSV writer
   → IncrementalTsvBridge.importNew(rr_*.tsv) → v1 SQLite → 既存Sender

今回追加した明示的な入力経路（自動起動なし）
journal.rlj + journal.rlj.identity
   → IngressJournalPrototype.replay() CRC/seq/UTF-8検証
   → IncrementalTsvBridge.importJournal(file,maxLines)
       → 既存 apply(M/S/E) と同じprofile/record ID・JSON
       → DurableSpool v1_profiles/v1_records（既存APIでcommit）
       → v1_journal_import_cursors（行単位CAS checkpoint）
   → 既存Senderのprofile ACK gate・Server ACK gateは変更なし
```

## 仕様と保障できること

- `importJournal`は自動登録されていない。既存Logger callbackにはfsyncもSQLite I/Oも追加していない。取込前に既存の`.identity`のUUIDを読み、UUID欠落・破損・symlinkなら停止する。
- journalの全フレームがCRC、連番、UTF-8で正常と確認できることを前提に、M/S/E変換を既存の`IncrementalTsvBridge.apply()`と共通化。同じdevice/vehicle/session/segment/sequenceと元TSV内容なら、TSV経路と同じprofile ID・record IDを作る。読み込みは最大8MiBの4a PoC上限内。maxLinesは最大10000で、処理済み行の定義状態はSQLite snapshotから復元する。
- `v1_journal_import_cursors`はUUID/ファイルパス/device/vehicle/session/連番/先頭SHA256/累積SHA256チェーン/metadata snapshotを保持する。既存カーソルの先頭・取込済み全行・ファイル移動・短縮の変更を拒否。カーソルは製品profile/recordのSQLite保存**後**に一行ずつCASで進める。**製品SQLite commitとcursor commitは別トランザクション**だが、同じ不変IDの冪等再実行で重複を防ぐ。
- `v1_source_claims`はdevice/vehicle/sessionごとに`legacy(path)`または`journal(UUID)`の一方だけを割当てる。従来の増分TSV、一括TSV両経路もこのclaimを通す。既存版のcursorや、旧バージョンでcursor/claimなしのproduct record（run IDが既存の場合）も検査し、別経路への勝手な引継ぎをfail closedにする。
- journal由来の観測がv1_recordsへ保存されても、Server ACKは増えない。プロフィール確認のない観測は`nextBatch()`の送信対象とならず、既存のプロファイル先行ACK・厳密バッチACK処理を保持する。
- 取込中の例外やworker異常終了時、元journal/sidecarを勝手に消去・改変しない。ソースclaimが行途中の失敗で残ることはある（安全側で再利用を拒否する）が、解除ツールは実装していない。手動解除の判断は別Phaseとする。

## CloudPC検証・失敗履歴

- `bash scripts/test-product-v1-spool.sh` に`JournalProductBridgeTest`を追加。既存の製品v1の送信／旧TSV／cursor／SQLite／synthetic HTTP回帰も同時実行。
- **新規JournalProductBridgeTest：35件PASS**。M/S/E→本物の`v1_profiles`/`v1_records`、TSVと同じレコードID、sessionの二重レーン拒否、旧`v1_records`からの誤引継ぎ拒否、UUID複製ファイルの移設検知、定義の再起動復元、改ざん後CRC再計算の取り込み済みprefix検知を確認。
- 別JVMを**製品観測レコードcommit直後／journal cursor CAS直前**に`Runtime.halt(79)`で強制終了し、別JVM相当の次回取込で一度だけのSQLite登録とcursor進行を確認。電源断やハード故障は再現していない。
- `bash scripts/test-ingress-sqlite-receipts.sh`：製品v1＋独立receipt試験、合計**284項目PASS**。`bash scripts/test-ingress-journal-prototype.sh`：旧journal/handoff **75項目PASS**。合計359項目。テスト用SQLite／Java合成のみで実ECU接続なし。
- 追加した別JVMクラッシュfixtureにより結果レコードが1件増えたため、末尾で期待件数が以前の3件のままになり一度失敗。テスト期待値を更新し、上記すべてを再実行してPASS。製品コードの障害ではない。
- GitHub Actionsは`product-v1-spool-ci.yml`と既存のjournal prototype CIでWindows/Linuxを別途確認。正式CI SHAと実行結果はPR完了時に追記する。

## 未完了・安全ゲート

1. 本Phaseは**製品に存在するメソッドへ明示呼び出しできる試験**まで。実際の起動Workerから`importJournal()`を呼ぶ自動切替は未実装。プロファイル変更や実車操作を伴う切替を勝手に有効化しない。
2. 今回のcursorは製品record commitと**別トランザクション**。不変IDによる再入で回復できる合成範囲を確認したが、DB故障、ストレージ破損、原本とsidecarの不一致、複数process競合の全ケースは未検証。
3. journalの8MiB上限、全件再走査とSHAチェーン、32bit JVMのメモリ、Windows実ハード・物理電源断・disk-full時の持続性能は未検証。有限queueにおける無期限writer停止の不可逆損失も残る。
4. 最新Agent mainを使用した実Server/PostgreSQL E2E、および新経路のServer batch ACK実試験は**NOT RUN**。CloudPC合成HTTP成功とは混同しない。
5. 次は4b-2cとして隔離された実Server/PostgreSQL合成E2Eと、Workerからの選択的ルーティング／監視・停止ゲートを別PRで設計する。Caddy/MCP、本番DBやX270をテストに用いない。

**判定：ジャーナル→製品v1 SQLite変換の明示呼び出し・故障復旧・重複・レーン排他は合成PASS。実車／本番有効化は保留。**
