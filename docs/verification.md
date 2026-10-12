# 実機検証結果（2026-10-12、Claude Cowork／X270実機）

検証環境: X270（laptop-glmhjuc8、Windows 10）、RomRaider 1.1.0 NOV26 2025 Build 1227、同梱Java Zulu 8（32bit, 1.8.0_504）、Logger定義 v370 METRIC_EN（SSM: 245パラメーター／172スイッチ）。

## 1. 既存ログから判明した事項（9/21の実車記録）

- **実車疎通は成功済み**: ECU ID `2F04785006`、OpenPort FW 1.17.4877／DLL 1.01.4341（`C:\WINDOWS\SysWOW64\op20pt32.dll`）、プロトコル SSM / ISO9141。
- 9/21 13:20〜17:48 にプロファイルを切り替えながらCSV 8本（計約2.5時間）を取得。出力先 `C:\Users\zawa\Documents\OpenPortWork\Logs`、時刻は相対ミリ秒。
- **サンプリング周期は項目数でほぼ決まる**（中央値）:

| 項目数 | 周期 | 周波数 |
| --- | --- | --- |
| 8 | 125ms | 8.0Hz |
| 9〜10 | 159〜167ms | 6.0〜6.3Hz |
| 12〜14 | 234〜301ms | 3.3〜4.3Hz |
| 18〜19 | 351〜385ms | 2.6〜2.9Hz |

- 9/21 13:51ごろ「readMsg timeout（51バイト待ち）→切断→再接続」が約3秒周期で繰り返された区間がある。項目数・構成が原因の可能性があり、**計測計画には項目数（応答バイト数）の上限を設ける**必要がある（原因は未確定）。
- 設定でCSV記録をデフォッガースイッチ（S20）に連動させる機能が有効（`filelogging switchid="S20" active="true"`）。自動制御との競合は未検証。

## 2. RomRaider外部制御: `-javaagent` 方式（動作確認済み）

- 調査: インストール先に同梱の `src` を確認。`EcuLogger.getEcuLoggerWithoutCreation()`（static public）でロガー本体を取得でき、`startLogging()` / `stopLogging()` / `isLogging()` / `loadUserProfile(path)` / `getSettings()` が public。CSV記録の開始停止は `logToFileButton`（private）経由。
- 方式: 自作の Java agent（RRBridge）を `-javaagent` で同じJVMに読み込む。**RomRaider本体・Program Filesは無変更**。起動は `Documents\OpenPortWork\RRBridge\start-romraider-with-bridge.bat`（作業ディレクトリは従来どおりインストール先）。
  - プラグイン機構（`plugins/*.plugin`）も `getMenuAction(EcuLogger)` でインスタンスを受け取れるが、クラスパス追加かProgram Filesへの書込みが必要なため不採用。
- 受付経路: ①`inbox\*.cmd`（Properties形式）→`outbox\*.json`、②HTTP `127.0.0.1:18765`（状態はGET、操作はPOST）。`status.json` を約1秒ごとに更新。
- 公開コマンド: `status` / `logging_start` / `logging_stop` / `filelog_start` / `filelog_stop` / `load_profile` / `switch_profile`。プロファイルは `RRBridge\profiles` 内のファイル名のみ許可。ECUリセット・DTC・ROM関連は非公開。範囲外パス・未知コマンドが拒否されることを確認。
- 試験結果（OpenPortはUSB接続、ECU無応答の状態）:
  - プロファイル読込がGUIに反映される（Data タブの項目・タイトル）。
  - ロギング開始・停止、CSV記録開始・停止が外部から操作できる。ECU無応答でもCSVファイルは作成される（ヘッダーのみ）。
  - **CSV記録中に `load_profile` すると、同じCSVに項目を1つずつ増減したヘッダー行が多重に書き込まれる**（RomRaiderが項目を逐次登録・解除するため）。
  - これを避ける `switch_profile`（記録停止→読込→記録再開）を追加し、**プロファイルごとに別CSV・ヘッダー1行**になることを確認。
  - CSVの列順はプロファイルXMLの記載順と一致しない場合がある → **ETLは列名で対応付ける**こと。
  - `logging_start` は非同期で、応答直後の状態は `logging=false` のことがある → 状態は `status` で確認する。
- 未検証: ECUと通信中（イグニッションON）での `switch_profile` 所要時間と欠測時間、デフォッガー連動との競合、長時間稼働、RomRaider異常終了時の扱い。

## 3. これにより変わること

- 第3節の「RomRaider → Adapter: 未選択」は **`-javaagent`（RRBridge）を第一候補**とする。第8節の段階1（ロガー制御）と段階2（項目設定）は、ECU通信下での試験を残して概ね達成。
- Supervisor は `switch_profile` を計測区間の区切りとして使い、CSVファイル＝計測区間（segment）として扱える。

## 4. ロギング中のリアルタイム転送（RRBridge v0.3.0、経路を確認済み・実ECUデータは未確認）

- 取り出し方: RomRaider内部の配信処理（`QueryManagerImpl.updateHandlers` → `AsyncDataUpdateHandler`）に自前の `DataUpdateHandler` を追加（リフレクション。本体は無変更）。**CSVを経由せず、1応答＝1サンプルの換算済みの値**を受け取れる。CSV記録のオン・オフとは独立して動く。
- 流れ: ハンドラー → メモリキュー → `spool\rr_<session>.tsv` に追記（正本）→ 送信スレッドが未送信分を `sink.properties` のURLへ HTTP POST（Bearerトークン対応）。送信済み位置は `*.tsv.sent`。
- 送信形式（TSV1行1レコード）: `M`＝項目定義（session, segment, id, 名称, 単位）、`S`＝サンプル（session, segment, seq, epoch ms, `id=値,...`）、`E`＝イベント（ロギング開始停止、CSV記録、プロファイル切替）。項目構成が変わるたびに segment を進める。
- 検証用受信サーバー（`start-receiver.bat`、`rrreceiver.jar`＋sqlite-jdbc。RomRaider同梱のJavaで動作）: 127.0.0.1:18766 で受け、`data\telemetry.db` に `sessions / parameters / samples / measurements / events` の縦持ちで保存。`session+seq` で重複排除。
- 試験結果（ECU無応答のため、同じ経路に合成データを流す `selftest_stream` で実施）:
  - 8Hz・240サンプルを転送し、**送信から保存までの遅延は平均273ms、最大533ms**。POST 1回は10〜20ms。
  - 受信サーバー停止中に発生した80サンプルはspoolに保持され、**起動後に自動で再送されて全件保存**された（欠落なし）。
  - 同じデータを再送しても重複しないことを確認（コンテナ内試験）。
- 未確認: 実ECUの応答がハンドラーに届くこと（取り付け自体は成功）。実車でロギングすれば確認できる。

## 5. 今後の構成への示唆

- 「RomRaiderから直接SQLサーバーへ」は、**DBへ直接書き込むのではなく、受信API（HTTPS）経由**とする。理由: DBをインターネットに公開しない、通信断時の再送・重複排除を受信側で一括管理できる、認証をトークンで扱える。
- X270側のspoolが正本となるため、走行中に回線が切れてもデータは失われない。
- 受信サーバーは試作では X270 内の SQLite。VPC に移す際は同じ受信APIを MariaDB 版で実装し、同じホストに HTTP MCP（読み取り専用の集計ツール）を置けば、スマホの音声モードから参照する構成につながる。

## 決定ログ

| 日付 | 決定・変更 |
| --- | --- |
| 2026-10-12 | 作業環境を ChatGPT Work から Claude Cowork へ移行（ユーザー判断） |
| 2026-10-12 | RomRaider外部制御は `-javaagent`（RRBridge）方式を第一候補とする（実機で基本動作を確認） |
| 2026-10-12 | 計測区間の切替は `switch_profile`（記録停止→読込→記録再開）で行い、1区間1CSVとする |
| 2026-10-12 | リアルタイム転送はRomRaider内部の配信から直接取り出し、ローカルspool（正本）→HTTP受信API→DBの順とする（DB直書きはしない） |
