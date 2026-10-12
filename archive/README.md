# archive/ — 実機から回収したデータと検証記録

X270（`laptop-glmhjuc8`）上にしか無かったデータを、2026-10-12 13:3x JST 時点でリポジトリに保存したもの。
コード（`src/`、`server/`）とは独立した**記録**で、書き換えない（追加のみ）。日付つきのフォルダで世代を分ける。

| パス | 元の場所（X270） | 内容 |
| --- | --- | --- |
| `romraider_logs/2026-09-21_real_car/` | `Documents\OpenPortWork\Logs` | **実車の計測ログ**（BP5、ECU ID 2F04785006）。CSV 11本、約2.4MB。8本はデータあり（13:20〜17:48、合計約2.5時間）、3本はヘッダーのみ。項目と周期の一覧は `docs/05_environment.md` §5。時刻は相対ミリ秒 |
| `romraider_logs/2026-10-12_bench/` | 同上 | RRBridge の試験で作られたCSV（ECU無応答のためヘッダーのみ）。`120350` はCSV記録中に `load_profile` してヘッダーが10行に多重化した例（D-006 の根拠）。`120539/120545/120550`、`125911/125913` は `switch_profile` で1区間1CSVになった例 |
| `x270_runtime_2026-10-12/outbox_results/` | `RRBridge\outbox` | v0.1〜v0.4 の試験で投入したコマンドの応答JSON（ファイル名は `UTC時刻_試験ID`。対応は `docs/06_verification.md`） |
| `x270_runtime_2026-10-12/spool_tsv/` | `RRBridge\spool` | 転送の正本（TSV）と送信済み位置（`.sent`）。session `20261012_121452`（v0.3、selftest 80＋240サンプル）、`20261012_125843`（v0.4、selftest 160サンプルとイベント）。`server/` の `/ingest` にそのまま送れば再現できる |
| `x270_runtime_2026-10-12/rrbridge.log` | `RRBridge\rrbridge.log` | エージェントのログ（起動、取り付け結果、コマンド、切替所要時間） |
| `x270_runtime_2026-10-12/status.json` | `RRBridge\status.json` | 回収時点の状態（v0.4.0、ロギング停止） |
| `x270_runtime_2026-10-12/receiver.log` | `RRBridge\data\receiver.log` | Java版受信サーバーの出力 |
| `x270_runtime_2026-10-12/telemetry_java_receiver.db` | `RRBridge\data\telemetry.db`（＋WAL） | Java版受信サーバーのSQLite。WALをチェックポイントして1ファイルにしたもの。samples 480（320＋160）、measurements 1,440、events 13 |
| `x270_romraider_userdir/` | `C:\Users\zawa\.RomRaider` | RomRaiderのユーザー設定 `settings.xml`、`profile_backup.xml`（最後に読み込んだプロファイル）、`rr_system.log`（2026-09-19以降の全ログ、約2.8MB。接続の成否、ECU ID、タイムアウトの記録、各起動時の System Properties を含む） |

含めていないもの:
- `RRBridge\sink.properties`（トークンを書く場所。回収時点は空だが、方針として除外）
- jar類（`rrbridge.jar`、`rrreceiver.jar`、`sqlite-jdbc.jar`）: `./build.sh` で再生成できる
- `OpenPortWork\versions.php`（Tactrix EcuFlash のリリースノート。第三者の文書）
- `OpenPortWork\Exports`、`ROM`、`Reports`（空）
- `Downloads` の `OpenPort_X270_*.md`（2026-09のChatGPT／Claude作業資料）と `Documents\Codex`: アクセス許可の確認がタイムアウトしたため未回収
