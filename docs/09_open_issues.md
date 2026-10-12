# 09. 未解決事項・技術的負債・次の作業

## 1. 優先度の高い未解決事項

| # | 内容 | 状態 | 次の一手 |
| --- | --- | --- | --- |
| 1 | 実ECUの応答が `Stream.handleDataUpdate` に届くか | 未確認 | 実車試験 §4 の T1 |
| 2 | LLMを無人で（定期・イベント駆動で）起動する方法。API従量課金を避け、サブスクの範囲で | 未解決（最大の課題） | サーバー側で区間統計を自動計算しておき、起動方式は後で選ぶ。候補: Claude Code／Codex CLIの非対話実行（使用量を消費）、スケジュール機能、ローカルルールでの一次判断 |
| 3 | 音声モードから自作MCPを呼べるか | Claude: 要検証、ChatGPT: 厳しい見込み | ダミーMCPを一時公開し、Claude Android音声から呼ぶ |
| 4 | サーバーの構成（VPSか自宅か） | 提案済み・未決定 | ユーザーの決定待ち（`02_architecture.md` §2） |
| 5 | 項目数の上限（通信が破綻しない範囲） | 9/21のログで目安のみ | 実車で項目数を段階的に増やして周期とエラーを測る |
| 6 | デフォッガー（S20）連動のCSV記録とRRBridgeの競合 | 未検証 | 実車試験 T4。必要なら `filelogging active="false"` にする |
| 7 | Supervisor（計測計画の受信・検証・適用） | 未着手 | 計測計画のデータモデルから設計 |
| 8 | 計測計画モデル（区間の切り方、承認、失敗時の扱い、基礎項目と探索項目の配分） | 未着手 | — |
| 9 | 13:51の「51バイト待ちタイムアウト」の原因 | 不明 | 項目構成を再現して切り分け |

## 2. 実装上の既知の制約・技術的負債（v0.3.0）

- `stream_status`、`selftest_stream` もHTTPではPOST必須（読み取り系の `stream_status` はGETで許すべき）。
- `Agent` と `Util` に `q()`／`reply()` が重複している。
- HTTP（18765）は認証なし。localhost限定だが、同じPCの任意のプロセスから操作できる。
- `switch_profile` の待機は固定1.5秒。ECU通信中に適切か未検証。
- `logging_start` が非同期で、応答時点の状態が古い。完了を待つオプションがあるとよい。
- spool と `rrbridge.log`、`data\receiver.log` にローテーション・削除がない。長期運用でディスクを使い続ける。
- キュー満杯（200,000行）時は黙って捨てる。捨てた件数を数えていない。
- `writeAtomic` は Windows では「削除→rename」で原子的ではない。読み取り側が一瞬ファイルを見失うことがある。
- 受信サーバーの `events` 件数は挿入試行数（重複でも数える）。
- session IDは秒単位の時刻。同じ秒に2回起動すると衝突する（実害は小さい）。
- `Stream.clean()` が名称中の `,` と `=` を空白に置換するため、パラメーター名が元と変わる場合がある。
- 時刻はX270の時計。受信側の遅延計算は時計のずれの影響を受ける（NTP前提）。
- 受信サーバーはSQLite接続1本を `synchronized` で共有。試作用。
- テストコードがない（検証は手動）。

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
