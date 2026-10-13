# Phase 2.1-D2b-2c-4b-2d-1：オフラインJournal Workerの明示起動・永続停止・状態参照

**適用対象：CloudPC上の合成検証まで。実車・X270・Logger自動配線・常駐Journal監視・本番デプロイは実施しない。**

## 結論と動作モード

現在の実装は`ProductBridgeWorker --once`／`--foreground`で従来のレガシーTSV経路だけを維持する。

新しい`ProductBridgeWorker --journal-once <閉じたjournal専用ディレクトリ> <既存/新規SQLite> <server-origin>` は、起動環境の`ROMLOGGER_JOURNAL_WORKER_ENABLE=1`を同時に設定した場合**だけ**実行できる。既定の挙動や旧コマンドではJournalを勝手に読み始めない。`--journal-foreground`は意図的にサポートしない。

- 原本ファイルはテスト以外の同時writerから**閉じられた状態**で渡す。現行`IngressJournalPrototype`は排他的ファイルロックを取るため、writer同時実行時に安全な常時監視はまだできない。
- 入口には別スレッドも間欠ポーリングも追加していない。新しい経路はCLI単発実行のみで、`Stream.handleDataUpdate`やECU/走行中のLoggerに接続しない。
- 既存の`ProductDeliveryCoordinator`／`ProductSender`／strict Server ACK／SQLite retry backoffを変更せずに利用する。Worker外部環境変数でdevice/vehicle/tokenを渡し、原本・トークン・通信本文は状態表示に出さない。

## 事前検査：ProductJournalScanner

Journalソースのディレクトリは専用で、`[A-Za-z0-9_-]{1,96}.rlj`とそれに対応する`.identity`だけを受け付ける。シンボリックリンク、余分なファイル、TSV混在、孤立したsidecar、sidecar欠落、16ファイルを超える入力、8MiBを超えるJournal、UUID衝突を拒否する。

製品`v1_journal_import_cursors`に既存の取込実績がある原本は、元のUUIDとパスの両方が引き続き存在しなければならない。取込済み原本の改変は既存のSHAチェーンで検知する。全原本の名前とIDを前検査し、書き込み中のジャーナルを読み取ろうとしたときは排他ロック失敗として停止する。

`JournalIdentityPrototype.open`は空JournalへIDを作る仕様を持つが、新Scanner側は`.identity`ファイルが既存でなければ**一切呼び出さない**。取込処理がIDを勝手に発行しないよう制限する。

## 停止・再起動の契約

取込失敗時にはSQLite`v1_ingress_review_state`の単一行に機械可読の固定コードを保存する。初回の原因を維持し、コードは`JOURNAL_PREFLIGHT`／`JOURNAL_IMPORT`／将来予約の`JOURNAL_SOURCE_MISSING`に限定。生の例外内容、UUIDやトークンは保存しない。

- `--journal-once`は不正な原本・writerロックなどで**終了コード5**、`INGRESS_REVIEW_REQUIRED`の定型メッセージを表示する。**その回はServerへ送信しない**。
- 要レビュー状態がある場合、以後はジャーナルモードだけでなく同じDBを利用する旧TSV Workerも起動冒頭で停止する。元ファイルが復旧しても自動解除しない。
- Workerは`v1_records`・既存のACK記録・未ACKレコード・元journal／ID sidecarを消去・変更しない。
- **解除APIは提供しない**。運用者が保全／原因解析を実施した上で、将来設計する承認付き復旧手順でのみ対応する。
- `ProductIngressStatus <既存SQLite DB>`はトークン不要、URIのSQLite読み取り専用モードで状態のみ参照。終了コード0は`INGRESS_STATUS=NO_REVIEW_LATCH`（安全保証ではない）、5は`INGRESS_STATUS=REVIEW_REQUIRED reason=<固定コード>`、6は不明・壊れたSQLiteのfail-closed、2は無効なDBパス・引数。観測用CLIは状態を書き換えない。

## CloudPC試験と結果

- 製品SQLite＋SQLite receiptの合成回帰をJava17 `javac --release 8`で**344件PASS**、journal/handoff旧回帰**75件PASS**、合計**419件PASS**。追加項目はScanner25件、Worker23件、Status12件、計60件。
- Scannerのテストは失われた既存原本、UUIDのコピー衝突、孤立/欠落sidecar、旧TSV混在、余分なファイル、writer排他ロック、16本制限、再起動後SQLiteカーソル、破損後fail-closedを確認。
- Workerテストは**別JVM起動**でopt-inなし拒否・未サポートforeground拒否・synthetic HTTPのprofile先行ACKとbatch ACK、SQLiteに残る未ACK原本、原本CRC破損時のコード5、SQLite要レビュー持続、旧TSV Workerによる抜け道を拒否することを確認。
- 読取専用Statusは**別JVMで12件PASS**。DBが存在しない場合は作成せず、状態保持、壊れたSQLiteや未知コードではHealthy扱いしない。Windows CIではsymlink作成権限がなければ該当試験を`SKIP`として計上し、実行済みとして扱わない。
- CloudPCの専用PostgreSQL16（127.0.0.1:25432）と使い捨てDB`romlogger_4b2d_worker_e2e`で、旧TSV3件＋直接journal3件＋**新しい--journal-once別JVMからの1件**、合計**実Postgres合成レコード7件、profile3件、UUID重複0件**。新Workerの2回呼び出しでprofile→batch ACKを得た後、別の空SQLiteから同じJournalを再投入して重複ACKも確認。実サーバーE2E PASS。
- [Agent PR #24製品SQLite CI #38391573311](https://github.com/zawa356/RomRaider_agent/actions/runs/38391573311) はWindows/Linux両ジョブでJava8コンパイル＋SQLite試験PASS。[GitHub E2Eゲート #38391572993](https://github.com/zawa356/RomRaider_agent/actions/runs/38391572993)ではDB安全ゲート6件の試験ステップ成功、実Server E2Eステップは**SKIPPED**。CloudPC実PostgreSQL E2EのPASSとは区別する。
- GitHub Actionsがprivate Server repoを取得するための`ROMLOGGER_SERVER_READ_TOKEN`を持たない場合、**実Server接続ステップはSKIPPEDのまま**。成功表示だけを実Server PASSに読み替えない。

## 追加で必要な作業

1. 走行中のwriterとは異なるプロセスから、排他に抵触せず読み取れるJournal read-only方式、ローテーションとIDペア、安定した容量監視を設計・負荷試験する。それまで`--journal-foreground`は非対応。
2. 日常運用で使えるSupervisorの状態集約と通知は未接続。現時点の`ProductIngressStatus`は**監視から取得できる状態出力口**に留まる。
3. 原本ディスクフル・実物理電源断・ファイル破損・32bit Zulu8/Win10でのメモリ/ディスク負荷、停止中の現車合成測定を後続の別Phaseで検証する。
4. D2b-2c全体の無欠損目標は未達。前段volatile queueの未fsync行およびwriter長期停止時の拒否は引き続き残る。
5. 運用者のレビュー後にしか解除できない安全な回復フローと権限、原本保全／監査記録を別途仕様化する。

**判定：オフラインJournal Workerの明示的切替、異常時の持続的な停止、外部監視向け参照口、実Server ACKまでの合成E2EをPASS。常時並列読取・実車・本番配備はNOT RUN。**
