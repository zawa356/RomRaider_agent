# 09. 未解決事項・技術的負債・次の作業

## 1. 優先度の高い未解決事項

| # | 内容 | 状態 | 次の一手 |
| --- | --- | --- | --- |
| 1 | 実ECUの応答が `Stream.handleDataUpdate` に届くか | 未確認 | 実車試験 §4 の T1 |
| 2 | LLMを無人で（定期・イベント駆動で）起動する方法。API従量課金を避け、サブスクの範囲で | 未解決（最大の課題） | サーバー側で区間統計を自動計算しておき、起動方式は後で選ぶ。候補: Claude Code／Codex CLIの非対話実行（使用量を消費）、スケジュール機能、ローカルルールでの一次判断 |
| 3 | 音声モードから自作MCPを呼べるか | Claude: 要検証、ChatGPT: 厳しい見込み | ダミーMCPを一時公開し、Claude Android音声から呼ぶ |
| 4 | サーバーの構成（VPSか自宅か） | 提案済み・未決定。中身（`server/`）は試作済み | ユーザーの決定待ち。決まれば `server/README.md` §5 の手順で配置 |
| 5 | 項目数の上限（通信が破綻しない範囲） | 9/21のログで目安のみ | 実車で項目数を段階的に増やして周期とエラーを測る |
| 6 | デフォッガー（S20）連動のCSV記録とRRBridgeの競合 | 未検証 | 実車試験 T4。必要なら `filelogging active="false"` にする |
| 7 | Supervisor（計測計画の受信・検証・適用） | 未着手 | 計測計画のデータモデルから設計 |
| 8 | 計測計画モデル（区間の切り方、承認、失敗時の扱い、基礎項目と探索項目の配分） | 未着手 | — |
| 9 | 13:51の「51バイト待ちタイムアウト」の原因 | 不明 | 項目構成を再現して切り分け |

## 2. 実装上の既知の制約・技術的負債

### 2.1 v0.4.0 で解消したもの（2026-10-12）

- 読み取り系コマンドがHTTPでPOST必須だった → GET可に。
- `Agent` と `Util` の `q()`／`reply()` の重複 → `Util` に集約。
- HTTP（18765）が認証なし → `http.token` で任意に認証可能（既定はオフのまま）。
- `switch_profile` の待機が固定1.5秒 → `switch.settleMs` で設定可能（所要時間をログに記録）。
- `logging_start` が非同期で状態が古い → `wait_ms`。
- spool と `rrbridge.log` が増え続ける → 送信済みspoolを `spool.retentionDays` 日で削除、ログは5MBで1世代ローテーション。
- キュー満杯時に黙って捨てる → `linesDropped` で計数。
- `writeAtomic` が非原子的 → `Files.move(ATOMIC_MOVE)`。
- 受信サーバーの `events` 件数が試行数 → 実挿入数。
- `Stream.clean()` で名称・イベントの `,` `=` が消える → IDだけ置換。
- テストコードがない → `test.sh`（30項目）。

### 2.3 server/ の残課題（v0.1.0）

- DockerイメージのビルドとComposeでの起動が未確認（作業環境にデーモンなし）。
- `query_telemetry` をDBの読み取り専用ロールで実行していない（同じ接続で READ ONLY トランザクション＋タイムアウト＋文の検査のみ）。
- MCPの認証は固定Bearerトークンのみ（OAuth未対応）。Claudeのカスタムコネクタは固定トークンに対応している（`02_architecture.md` §2.1）。
- `/ingest` のレート制限・本文サイズ上限はCaddy／uvicorn任せ。
- 区間統計の事前計算（`segment_stats` テーブル）や、引継ぎ資料の `analysis_results`／`measurement_plans`／`commands` は未実装。
- 時刻はX270の時計（`t_ms`）とサーバー受信時刻（`received_ms`）の2系統。X270の時計ずれは補正していない。

### 2.2 残っているもの（v0.4.0）

- `switch.settleMs` の適正値はECU通信中に未検証（無応答時の所要時間は1836ms）。
- `data\receiver.log`（受信サーバーの標準出力）はバッチの追記のみでローテーションなし。
- ファイル経由（inbox）の操作は認証なし（フォルダのアクセス権に依存）。
- GitHub ActionsでのCIがない（RomRaider.jarの入手方法が必要。D-016）。
- RomRaider連携部分（EDT操作、リフレクション、プロファイル切替）の自動試験がない。

- session IDは秒単位の時刻。同じ秒に2回起動すると衝突する（実害は小さい）。
- 時刻はX270の時計。受信側の遅延計算は時計のずれの影響を受ける（NTP前提）。
- 受信サーバーはSQLite接続1本を `synchronized` で共有。試作用。

## 3. 次の作業の候補（順不同、ユーザーの判断待ち）

1. **音声MCPの適合性検証**: Python（公式MCP SDK、Streamable HTTP）で `get_vehicle_status` などダミー値を返すMCPを作り、Cloudflare Tunnelの一時URLで公開 → Claudeのカスタムコネクタに登録 → Android音声モードから呼ぶ。書き込み系（`add_event_marker`）で確認が出るかも試す。
2. **本番受信API＋MCP（VPS）**: Docker Compose（caddy、postgres、app）。受信APIは現行TSV形式をそのまま受ける。MCPの道具候補: `get_vehicle_status`、`get_current_segment`、`get_segment_stats`、`query_telemetry`（読み取り専用・行数と時間の上限）、`list_events`、`add_event_marker`、（将来）`propose_measurement_plan`／`submit_measurement_plan`。
3. **RRBridge v0.4**: GETで `stream_status`、キュー溢れの計数、spoolのローテーション、`logging_start` の完了待ち、HTTPのトークン認証（任意）、計測計画の受け口（Supervisor用）。
4. **Supervisor**: X270常駐。サーバーから計測計画を取りに行き（X270への着信はさせない）、検証してRRBridgeに `switch_profile` を出す。安全条件（停車中のみ切替など）の判定には車速等のストリーム値を使う。
5. **ETL（既存CSVの取り込み）**: 9/21のCSVを同じDBに取り込み、列名でパラメーターIDに対応付ける。

## 4. 実車試験計画（停車状態で実施することを推奨）

前提: イグニッションON（できればアイドリング）、OpenPort接続、RRBridge v0.3.0 付きでRomRaider起動、受信サーバー起動。

| ID | 手順 | 確認すること |
| --- | --- | --- |
| T1 | `switch_profile basic.xml` → `logging_start` → 30秒 → `stream_status` | `samples` が増える。`M` 行の param_id が P8/P9/P13/P25/P2/P11。受信DBの値が画面表示と一致 |
| T2 | T1の状態で `filelog_start` → 60秒 → `filelog_stop` | CSVの行数とストリームのサンプル数・時刻・値を突き合わせる（CSVは相対ms、ストリームはepoch ms） |
| T3 | `filelog_start` → `switch_profile fuel.xml` → `switch_profile basic.xml` を数回 | 所要時間、CSVの切れ目の欠測時間、ストリームのsegmentの進み方 |
| T4 | CSV記録中にデフォッガーを ON/OFF | RomRaiderのS20連動とRRBridgeの状態（`fileLogging`）が食い違わないか |
| T5 | 項目数を 6 → 8 → 12 → 16 → 20 と増やす | 周期、`readMsg timeout` の有無、ストリーム遅延 |
| T6 | 受信サーバーを止めて1分 → 再開 | spoolからの再送で欠落がないこと |
| T7 | OpenPortのUSBを抜く → 挿す | 再接続時のイベント、segment、ストリームの途切れ方 |

注意: 走行中にPCを操作しない。切替はすべて停車中に、コマンドで行う。
