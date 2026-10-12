# 【RomLogger】AI連携 — 会話引継ぎ・構想整理・検討履歴

> 更新日: 2026-10-10（JST）  
> 種別: **継続検討用の共有ノート／会話内容を構造化した引継ぎ資料**  
> 対象: RomRaider Loggerを使った自律的なECU計測・データ収集・LLM解析・MCP/音声連携  
> 状態: **構想整理段階。製品選定・実装方式は原則未確定**  
> 目的: このチャット以外から参照・追記・修正し、設計検討を継続できるようにする。

## 0. このファイルの使い方・更新原則

1. 別チャットではChatGPTのLibraryから本ファイルを取得して読み、**「確定事項」「有力候補」「未決事項」「技術検証済み」「前の応答で述べただけ」**を混同しないこと。
2. これまでの会話を逐語的に全転記したものではなく、**ユーザーの要求・会話の経緯・提案・調査で判明した情報を後から再利用できる形で再構成した記録**。逐語記録の代替と誤認しないこと。
3. 要件定義では「何を実現するか」を記述し、実装方式（Python、SQLite、MariaDB、VPC、Cloudflare Tunnel、Java改造等）は別の設計候補として扱う。
4. 新たな決定が生じた場合、既存の検討史を消さず「決定ログ」に日付を付けて追加し、冒頭の最新状態を更新する。
5. 他のチャットで編集するときはLibrary上の**同一ファイルの最新版**を確認し、バージョン競合を避けて上書きする。別名ファイルを増殖させない。
6. 車両制御・故障診断・運転中操作に関する部分は安全上の制限を維持する。**ECU書き込み、車両を動かす制御、OpenPortのファームウェア更新**は本MCPのスコープ外。

---

## 1. ユーザーが達成したいこと（会話で明示された意向）

### 1.1 出発点

- RomRaider Loggerの各種設定、特に**取得ログ項目（パラメーター）の手動変更が煩雑**。
- ChatGPTやClaudeからMCP経由で設定を読み、変更できるようにしたい。
- 単なる設定編集ではなく、最終目標は**RomRaider全体の操作（ログ取得の開始・停止、状態照会、CSV解析を含む）**。

### 1.2 自動計測の最終目標

- 運転中にPCを触るのは難しいため、あらかじめ許可した計測の範囲でシステムがログを区切り、解析し、**次に取得するパラメーターをLLMが動的に判断**して情報を収集していく。
- 例えば基本項目→点火／ノック関連→燃調関連、と調査の目的に応じてログ項目を切り替える。特定の計測シナリオは例示であり、現車への適用可否を検証する。
- CSV（ユーザーの入力ではCVSという表記もあったが文脈上CSV）をローカル側で構造化し、SQLで検索・比較できるようにしたい。
- 走行中の**LLMによるオンライン判断（方式1）**を最終的に望む。通信回線の利用可否は今回の設計では制約としない（利用可能前提）。
- LLM APIの**従量課金は極力避けたい**。Work/Cowork/Codex/Claude Codeもサブスク使用量が厳しいため、通常のチャット・拡張機能・音声モードの利用可能性を検討したい。
- スマートフォンを車載ホルダーに固定して音声会話を続け、状況確認、計測方針変更、気付いた症状へのタイムスタンプ付きマーカー登録などができるとよい。

### 1.3 既知の環境と確認上の注意

- 車両: スバル Legacy BP5（EJ20X、SSM/K-Line世代という前提で議論）。
- ECU通信: OpenPort 2.0互換機、RomRaider Loggerを活用する方向。
- ロガー実行PC: ThinkPad X270（以前の応答ではWindows 10 Home 22H2と記載）。
- 以前の応答で挙げた例: RomRaider 1.1.0 NOV26 2025、Logger定義 v370 METRIC_EN、245パラメーター／172スイッチ、FW 1.17.4877、`C:\Users\zawa\.RomRaider\settings.xml`。**この会話に実機ファイルの再提示はなく、過去の引継ぎに基づく記載なので要再確認**。
- 実車での接続成功、認識された項目の実取得、通信帯域・サンプリング周期については、少なくともこの会話では成功結果が提示されていない。**実車確認を最優先の技術検証項目とする**。
- 通信回線についてユーザーは「今回は使えるものとして前提としてよい」と明言。
- ChatGPT最新デスクトップクライアント＋Workモードで**ローカルstdioのMCPが使えることはユーザーが動作確認済み**。ただしWorkの使用量が厳しく、恒常稼働への採用は消極的。

---

## 2. 現時点の構成候補（暫定アーキテクチャ）

```
                    【運転者・AIクライアント】
                    Android ChatGPT Live（音声）
                    ChatGPT / Claude（通常チャット）
                              │
                     MCP / HTTPS・認証
                              ▼
              ┌──────────────────────────────┐
              │ VPC内サーバー（配置は候補）  │
              │                              │
              │ Remote MCP Server            │
              │ ├ 状態照会・SQL検索          │
              │ ├ 集計・異常・履歴照会       │
              │ ├ 計測計画の提案・提出       │
              │ └ コメント・イベント登録    │
              │             │                │
              │ Telemetry API／計測計画API  │
              │             │                │
              │       MariaDB / MySQL        │
              │       ├ 計測値               │
              │       ├ 計測計画・指示       │
              │       ├ 検証・統計結果       │
              │       └ 操作監査ログ         │
              └──────────────┬───────────────┘
                             ▲│
                  車載側発HTTPS送信／指示取得
                             │▼
              ┌──────────────────────────────┐
              │ BP5車載 X270                 │
              │                              │
              │ Logging Supervisor           │
              │ ├ 計測スケジューラ           │
              │ ├ ログ状態・安全条件の管理   │
              │ ├ CSV監視・正規化 ETL        │
              │ ├ SQLite（バッファ候補）     │
              │ ├ 計測計画受信・適用         │
              │ └ 同期失敗時の再送           │
              │            │                 │
              │ RomRaider Adapter            │
              │ ├ プロファイル読み書き       │
              │ ├ ロギング開始・終了         │
              │ └ ECU接続・取得状態          │
              │            │                 │
              │ RomRaider Logger              │
              │            │                 │
              │ OpenPort 2.0互換機            │
              └────────────┼─────────────────┘
                           │ J2534/SSM/K-Line
                           ▼
                         BP5 ECU
```

**注意:** この構成は会話で浮上した有力候補を整理したもので、VPC・MySQL/MariaDB・SQLite・Python・Cloudflare Tunnel・Java制御API追加を採用決定したわけではない。

### 2.1 主な責務分割

| 領域 | 責務 | 候補 |
| --- | --- | --- |
| ECU通信・計測 | ECUからパラメーターを取得し記録 | RomRaider＋OpenPort |
| Adapter | ロガー内部／外部設定・開始停止・接続状態 | XML＋GUI／Javaインターフェースの比較 |
| Supervisor | ログ区切り、計画実行、コマンドの検証・適用、再送、状態管理 | X270常駐Python等 |
| ETL | CSVの検証、パラメーターID対応付け、単位・時刻正規化 | Python等 |
| ローカル保持 | 送信できないログの保全、正本CSV | CSV＋SQLite等 |
| VPC蓄積 | 走行・計測・分析の履歴、SQLによる検索 | MariaDB／MySQL |
| 遠隔IF | 認証済みAIクライアントへの読み書きツール公開 | MCP（Streamable HTTP） |
| AI判断 | 異常解釈、次回取得項目の提案・計測計画生成 | ChatGPT/Claude（方法未定） |
| 音声UI | 手を使わない状況確認・運転者コメント | ChatGPT Live（対応検証要） |
| 版管理 | スキーマ・ソース・プロファイル・設計資料 | GitHub |

---

## 3. インターフェース整理（候補）

| 発信元 | 宛先 | 想定IF／留意事項 |
| --- | --- | --- |
| BP5 ECU | OpenPort互換機 | SSM / K-Line（実車検証要） |
| OpenPort互換機 | RomRaider | J2534（DLL・対応状況検証要） |
| RomRaider | Adapter | プロファイルXML、内部Java API、またはUI操作。未選択 |
| Adapter | Supervisor | localhost限定API、イベント/状態取得、排他制御 |
| RomRaider CSV | ETL | 確定後取り込みを初期案、追記監視や直送は比較対象 |
| Supervisor | ローカル保持 | ファイル／SQLite |
| X270 | Telemetry API | HTTPS、バッチ同期、重複排除・再送・認証 |
| MCP | 計測データ | 読み取り専用SQL／安全な集計API、行数・時間制限 |
| MCP | 計測計画管理 | 計測計画の検証・投入（DB直接無制限更新を許可しない） |
| X270 | コマンド配信 | HTTPSポーリング等で車載側から取りに行く方式 |
| ChatGPT・Claude | MCP | リモートMCP＋OAuth等のクライアント互換確認 |
| インターネット | VPCのMCP | Cloudflare Tunnel/Access等（候補） |

### 3.1 仮のMCPツール群

- 状態と定義: `get_vehicle_status`, `get_current_profile`, `list_available_parameters`, `get_measurement_plan`
- ログ参照: `get_latest_statistics`, `get_session_history`, `query_telemetry`, `get_anomalies`
- 計画の変更: `propose_measurement_plan`, `submit_measurement_plan`, `compare_profiles`
- 運転者からのイベント: `add_event_marker`

**設計原則:** AIの提案はそのままECU・ロガーに適用せず、Supervisorが定義整合性・通信負荷・現在状態・承認済み範囲・安全条件を検査して適用可否を決定。監査ログを残す。

---

## 4. データの扱い（会話での発展）

### 4.1 最初の案: CSV→SQLite→GitHub

ユーザーの発案:

1. RomRaiderがCSVを生成する。
2. X270側がCSVの完成を検出する。
3. Python ETLでSQLiteに変換して記録する。
4. 定期的にSQLiteをGitHubへPushする。
5. ChatGPTから連携済みGitHubを参照し、SQLで最新状態を分析したい。

整理された制約:

- GitHubは任意のSQLiteファイルにSQLクエリを直接実行するサービスではない。SQLiteを取得して実行環境で開く必要がある。
- SQLiteバイナリを頻繁にPushするとリポジトリ肥大化や更新競合が発生しやすい。
- GitHubには解析サマリーJSON、設計・設定、レポートを保存し、DBそのもののクエリはMCP/専用サービスへ任せる案が出た。

### 4.2 発展案: VPC上にMySQL/MariaDB

その後ユーザーから、**VPCでMySQL/MariaDBを立ててリアルタイム蓄積することも可能**という案が提示された。

- X270側でCSV→ETL→ローカル保存（ローカルSQLiteは候補）。
- HTTPSでVPCへ定期/準リアルタイム送信。
- VPC側で計測値を蓄積。MCPはSQLの結果を必要な粒度に縮約してAIへ返却。
- X270は外部から直接着信させず、コマンドキューを取りに行く方式を候補とする。
- **CSV確定後のバッチ取り込み**、**CSV追記監視**、**RomRaider内部から直接送信**の3案は選択未了。

### 4.3 DBモデルの概念案

- `sessions`: 走行単位
- `segments`: 取得区間とプロファイル、CSVファイル
- `parameters`: ロガー定義のパラメーターID・単位・変換情報
- `samples`: 計測タイムスタンプ
- `measurements`: `(sample_id, parameter_id, value)` などの縦持ち計測値
- `segment_stats` / `statistics`: 区間単位の統計
- `events`: 運転者メモ、異常、接続・変更イベント
- `analysis_results`: 推論入力・結果、使用モデル・解析日時・対象区間
- `measurement_plans`: 計測計画とその版
- `commands`: 車載側への指示、状態と実行結果

列構成の異なるCSVを取り込めるよう、概念上は縦持ちモデルを候補とした。性能や運用性を踏まえ、物理スキーマは別途設計する。

### 4.4 データ品質・検証要件

- CSV原本を保持して、正規化結果を再生成可能にする。
- 計測ファイルの確定、バッファリング、途中行・途中ヘッダー、ファイル名衝突を考慮する。
- プロファイル、Logger定義バージョン、ECU対応項目の区別を記録する。
- タイムゾーン、絶対/相対時刻、サンプリング周期、欠損、不正値、単位を管理する。
- 同一行の複数パラメーターが必ずしも同一瞬間の観測ではない可能性に注意。
- 再送の重複排除、送信済み管理、バックフィル、通信復旧後同期を検討。
- 解析結果は再評価できるよう、後から上書きせず履歴管理する。

---

## 5. RomRaiderソースコード調査で会話中に確認した事項

**対象ソース:** 公開GitHub `RomRaider/RomRaider` の `1.1.0-NOV26-2025` ref を用い、当チャット内でGitHub連携からファイル内容を取得して確認した。これは公開コードの調査であり、ユーザー実機での動作確認を意味しない。

- `src/main/java/com/romraider/logger/ecu/profile/xml/UserProfileHandler.java`
  - `profile`、`parameter`、`switch`、`external` をパース。
  - 属性に `protocol`, `id`, `units`, `livedata`, `graph`, `dash` を持つ。
  - 選択値は `selected`。
- `src/main/java/com/romraider/logger/ecu/profile/UserProfileImpl.java`
  - XML組み立て・保存形式を実装。`<profile protocol="SSM">`、パラメーター/スイッチ/外部データなど。
  - 出力エンコーディングは `ISO-8859-1`。
- `src/main/java/com/romraider/logger/ecu/profile/UserProfileLoaderImpl.java`
  - ファイルからプロファイル読み込み。旧 `profile.dtd` の扱いに互換処理あり。
- `src/main/java/com/romraider/logger/ecu/ui/swing/menubar/action/ReloadProfileAction.java`
  - プロファイルの再読み込みアクションあり。ただし**外部プログラムから自動実行するAPIの存在は未確認**。
- `src/main/java/com/romraider/logger/ecu/ui/swing/menubar/action/LoadProfileAction.java`
  - プロファイル読み込み後にロギングを再起動する経路あり。
- `src/main/java/com/romraider/logger/ecu/EcuLogger.java`
  - `startLogging()`, `stopLogging()`, `restartLogging()` の内部メソッドあり。
  - `getCurrentProfile()`, `loadUserProfile(...)` あり。
  - 終了時に設定保存、バックアッププロファイル保存。実行中のsettings.xml直接書換は競合の可能性。
  - ロギングコントローラーの開始/停止とファイルへの記録開始/停止は別の責務。
- `src/main/java/com/romraider/logger/ecu/ui/handler/file/FileUpdateHandlerImpl.java`
  - `start()`, `stop()` はCSVファイルへの書き出し制御。
  - 計測値登録・CSVヘッダー更新の経路あり。
- `src/main/java/com/romraider/logger/ecu/ui/handler/file/FileLoggerImpl.java`
  - `romraiderlog_yyyyMMdd_HHmmss.csv` 形式。任意プレフィックスが付く場合あり。
  - ファイル出力はバッファリング。相対ミリ秒・絶対時刻の設定あり。

参考コード（URLは調査対象の識別目的。現時点の実装と一致するか別途確認）:

- https://github.com/RomRaider/RomRaider/blob/1.1.0-NOV26-2025/src/main/java/com/romraider/logger/ecu/profile/UserProfileImpl.java
- https://github.com/RomRaider/RomRaider/blob/1.1.0-NOV26-2025/src/main/java/com/romraider/logger/ecu/EcuLogger.java
- https://github.com/RomRaider/RomRaider/blob/1.1.0-NOV26-2025/src/main/java/com/romraider/logger/ecu/ui/handler/file/FileUpdateHandlerImpl.java
- https://github.com/RomRaider/RomRaider/blob/1.1.0-NOV26-2025/src/main/java/com/romraider/logger/ecu/ui/handler/file/FileLoggerImpl.java

---

## 6. 最難関: LLMをどう継続的に起動するか

### 6.1 明確な制約

- LLM API従量課金は極力避ける。
- Claude Code/Codex CLIは定期実行に使える可能性があるが、**サブスク使用量を消費するため主方式にはしづらい**。
- ChatGPT Work／Claude Coworkも使用量が厳しい。
- MCPは通常「AIクライアントがツールを呼び出す」インターフェースであり、**DB更新や5分ごとのイベントだけで通常チャットのモデル推論を自動開始する標準機構ではない**。
- GitHubへPushするだけで自動的に通常のChatGPT会話が再開されるわけではない。

### 6.2 検討した候補

| 候補 | 利点 | 課題／現状 |
| --- | --- | --- |
| 通常チャット＋Remote MCP | 対話しながら最新データを問い合わせ可能 | 無人での定期起動が未解決 |
| ChatGPT Live＋MCP | 走行中の音声対話、イベントマーカー | 自作MCP対応・承認フローは実機検証要。定期無人トリガーにはならない |
| Chrome拡張（5分おきに送信） | 通常チャットを周期的に起動できる技術的可能性 | DOM変化・完了検知・利用規約・サブスク制限など。**採用未定** |
| Claude Code CLI `claude -p` | 非対話・構造化応答 | 使用量制限、認証・料金運用確認が必要 |
| Codex CLI `codex exec` | 非対話・構造化応答 | Work/Codexの利用枠圧迫の懸念 |
| LLM API | イベント駆動の自動判断が容易 | ユーザーのコスト要件と衝突 |
| ローカルルールによる一部判断 | API不要、堅牢 | 高度な探索的判断の代替にはならない |

**結論:** 本会話終了時点で「通常のサブスクチャットを5分ごとに無人で推論させる」実現方式は確定していない。サービス仕様・利用規約・上限については、将来の実装時点で改めて確認すること。

### 6.3 会話内で推奨された分離

- **計測とルール実行**はSupervisorが担う。LLM不通でもログが消えない／停止しない。
- **LLMの役割**は、統計サマリー・必要な抽出データから、次の計測対象と計画を提案すること。
- 分析用データの前処理（集計・異常候補の検出）はローカルPython/SQLで実行し、LLMには小さな要約と追加照会の手段を提供する。
- 「5分おきに必ずLLMを起動」ではなく、当初は計測区間確定・異常・計画完了時にLLMレビューを要求する設計も提案。ただし、ユーザーの最終志向は**走行中のLLMによる適応的探索**。
- 基礎項目の連続記録＋目的別の探索項目を持つ計測計画を検討。K-Lineの通信負荷・項目の実対応と、計測条件の比較可能性を考慮。

### 6.4 ChatGPT音声モード（Live）について

- ユーザーがスマホをホルダーに置き、音声でログの状態や変化を聞く使い方を提案。
- 以前の応答では「Liveが接続済みプラグインに対応する」という説明があったが、**自作Remote MCPがAndroid Liveから実際に利用可能か、読み書き操作で何の承認が必要かはこのチャット中に検証していない**。
- 走行中に画面タップが必要になる設計は避ける。音声操作は、状態確認・手動イベント記録・計測計画の要求などを中心に検証。
- ChatGPT Liveは、常時無人スケジューラの代わりとしては設計しない。

---

## 7. 安全・権限・信頼性の基本ルール

- ロガーの取得項目切替は**既知の安全条件と停車条件**を満たす場合に限定する方向で検討。走行中の画面操作を要求しない。
- 車両の運転に影響を与える制御コマンド、ECUのROM書換、FW更新は除外。
- LLMからの計画変更は、取得できる項目のホワイトリスト、Logger定義・ECU適合確認、件数・サンプリング制約、競合防止、承認範囲と照合する。
- 一時停止・通信断・MCP不能でも収集済みデータは保持する。失敗した計画は検証済みの前の計画に復帰できるようにする。
- データベースの読み取りは専用の読み取り権限で行い、恣意的なSQLやOSコマンドをMCPから無制限実行させない。
- 認証済みHTTPS、必要ならTunnel＋Access/OAuthを検討。MariaDBをインターネットへ直接公開しない。
- ローカルDBとサーバーDBの役割、バックアップ、スキーマバージョン管理、コマンド重複実行防止を後の詳細設計で定義する。

---

## 8. 実装ステップの提案（順序は未確定）

| 段階 | 狙い／完了の目安 |
| --- | --- |
| 0 実車疎通 | 実車のECUと通信し、取得可能な項目・更新速度・CSV形式を確認 |
| 1 ロガー制御 | 外部からロギング状態取得、CSV出力の開始・終了を安全に制御 |
| 2 項目設定 | XMLまたはAPIでログ項目設定を作成・検証・反映 |
| 3 ETL | CSV確定判定、正規化、履歴保存、統計処理 |
| 4 VPC同期 | HTTPSによるリアルタイム/バッチ蓄積、再送管理 |
| 5 MCP | 状態照会・SQL分析・計測計画投入のリモートツール |
| 6 音声試験 | Android ChatGPT LiveからのカスタムMCP利用可否、承認の要否 |
| 7 LLM連携 | 解析結果→計測計画への変換と検証 |
| 8 自律運用 | 運転中の適応的探索。推論エンジンの継続起動方法が前提条件 |

これらは独立して並行開発可能な領域がある。仕様策定では目的・要求品質・IFを先に決め、技術選定を分離する。

---

## 9. 重要な未決事項／次チャットの検討優先事項

1. **LLMの自律起動方法**: 通常のChatGPT/Claude契約の範囲で、許可されたインターフェースを使い、定期またはイベント駆動で推論を実行する方法があるか。
2. **音声＋自作MCP**: Android ChatGPT LiveでRemote MCPの読み取り・計画提出を利用できるか、音声中の操作承認要否。
3. **RomRaiderの制御実装**: 既存APIの最小改造、Java側制御窓口、UI操作、プロファイル反映時のファイル確定とロギング再開。
4. **ロガーの実車互換・速度**: 実車ECU ID、パラメーター一覧、通信成功率、サンプリング速度と項目数の関係。
5. **計測計画モデル**: どの条件で区間を切るか、変更の承認・待機・失敗時の扱い、基礎パラメーターと探索項目のバランス。
6. **データモデルと保管**: CSV追記監視vs確定後取り込みvs直接送信。SQLiteとMariaDBのどちらを正本とするか、巨大化や長期保存、バックアップ方式。
7. **VPC/公開方式**: 実際のVPCプラットフォーム、可用性・費用、Cloudflare Tunnel/AccessのMCPクライアント互換性。
8. **MCPツールの最小権限・検証仕様**: SQLの読み取り制限、変更コマンドの型・承認状態・安全条件、トレーサビリティ。
9. **ログ解釈の妥当性**: 点火時期・ブースト・ノック補正等の関連の解釈は環境条件・サンプリング・ECU定義に依存する。AIが根拠不足を明示できる必要。

---

## 10. これまでの検討の時系列（合意事項と提案の区別）

1. **初回提案**: RomRaiderの手動設定・ログ取得項目設定をMCP化したい → XMLプロファイル操作により一部実現可能と調査。
2. **目標を拡張**: ユーザーがB案（RomRaider全体操作）を最終目標として指定。運転中にログ分割→解析→次の項目選定→再計測を自律化したい。CSV解析も必須。
3. **遠隔化**: ユーザーはChatGPT Desktop＋Workでローカルstdio MCP利用を確認済み。ただしWork消費が厳しいため、Remote MCPやCloudflare Tunnelも候補。
4. **LLM利用料制約**: 通信回線は利用可能前提だがAPI従量課金を避けたい。Claude Code/Codex/Work/Coworkはサブスク枠が厳しい。通常チャットへ拡張機能でトリガーを発生させる案を検討。
5. **データ構造案**: ユーザーからCSV完了→Python ETL→SQLite→定期GitHub Push→チャットのGitHub連携から検索、という提案。GitHubはDBクエリエンジンでないため、共有サマリー/ソース/レポートに向くと整理。
6. **VPC DB案**: ユーザーからVPCにMySQL/MariaDBを立てる案が提示。リアルタイム保存とRemote MCPによるSQL照会、車載側キュー取得方式を候補に追加。
7. **Chrome拡張案**: ユーザーが5分間隔のチャット自動送信を提案。技術的な可能性はあるが、規約・DOM依存・制限等のため未採用。
8. **音声案**: ユーザーがAndroidスマホでChatGPT Liveを起動して走行中の音声＋MCP活用を提案。音声UIとして有望だが、自作MCP対応と承認について実機検証が必要。
9. **構成整理**: 車載X270（RomRaider＋Adapter＋Supervisor＋ETL）／VPC（Telemetry API＋MariaDB＋MCP）／AIクライアント（チャット・Live）の3層構成を有力案として整理。最大の課題は**サブスク枠の通常チャットを用いた自律LLM起動**。
10. **本引継ぎ資料の依頼**: 2026-10-10にユーザーから、これまでの会話をLibraryに保存し、別チャットからも参照・編集したいと依頼された。

---

## 11. 次チャット開始時の推奨指示

> Libraryの「RomLogger_AI連携_会話引継ぎと設計検討_2026-10-10.md」を読んで、このプロジェクトを続けてください。確定要件・候補・未検証事項を区別し、今回の検討結果は同一Libraryファイルに追記・更新してください。特にLLMの自律起動方法とChatGPT Live＋自作MCPの適合性を優先して整理してください。

---

## 12. 更新履歴

| 日付 | 内容 |
| --- | --- |
| 2026-10-10 | 本チャットを元に、要望・検討経緯・暫定構成・公開コード調査・未解決課題をLibrary共有ノートとして初版作成 |
