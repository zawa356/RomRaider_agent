# RomLogger 製品版 Windows Agent 実装台帳

**正本:** サーバー全体の[実装進捗](https://github.com/zawa356/RomRaiderServer/blob/main/docs/implementation_progress.md)と[製品設計](https://github.com/zawa356/RomRaiderServer/blob/main/docs/agent_detailed_design.md)。本台帳はこのリポジトリのAgent実装差分・CI・残課題を追跡する。

## 運用規約

- 小PhaseごとにPRを分け、コード、回帰試験、CI、履歴をそれぞれコミットして履歴を残す
- GitHub ActionsをLinuxとWindowsで実行し、失敗時は原因・修正コミットをこの履歴へ追記する
- CIのJava 8対応コンパイルは**実車WindowsのZulu 8 32-bitの実行確認ではない**
- 実ECU接続・走行中操作・未ACK破棄・本番展開を単体テストの成功から推定しない
- 現行PoCと製品v1の接続契約は異なる。**既存のTSV送信やRomRaider操作を勝手に置換しない**

## フェーズ一覧

| Phase | 対象 | 状態 | 記録 |
| --- | --- | --- | --- |
| 2.1-A | Java 8対応SQLite durable spool、原本・定義とACK台帳 | **CI合格・PR #3** | [PR #3](https://github.com/zawa356/RomRaider_agent/pull/3)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-B | Server v1プロファイル／バッチのHTTP送信・ACK厳密検証（契約モックCI） | **合成CI完了・PR #4** | [PR #4](https://github.com/zawa356/RomRaider_agent/pull/4)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-C1 | 既存RRBridge TSV→製品v1 SQLiteの非破壊取込・合成CI | **CI合格、PR作成中** | [Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-C2 | Agent→実FastAPI→PostgreSQL E2Eと常駐取り込み接続 | **E2Eワークフロー実装済み／別Private Repo認証未設定で未実行** | [E2E run](https://github.com/zawa356/RomRaider_agent/actions/runs/38339215160) |
| 2.1-D | Windows再起動／通信断／ディスク不足／実機での耐久検証 | 未着手 | Issue #7 |
| 3 | ECU→RomRaider→Agent→Serverの停車状態での実車検証 | 未着手 | [Issue #8](https://github.com/zawa356/RomRaiderServer/issues/8) |

## 変更履歴

### 2026-10-13 — Phase 2.1-C1：RRBridge PoC TSV→製品SQLite取り込み（合成CI）

**追加**

- `LegacyTsvBridge.java`：既存RRBridge v0.5のM/S/E TSVを読み取り、ProfileRevision・Sample・Eventの正式v1 JSONを作りSQLiteに永続登録。TSV本体は読み取り専用。既存Logger callbackとPoC送信・安全制御は変更しない
- IDはdevice ID・旧session・segment・元sample sequence／行位置から**決定論的に生成**。再読み込み・再起動後も変わらず、二重登録は原本UUIDで抑制
- 元TSVのUTF-8/LF終端済み行だけ処理。書込み途中の行は保留し、不正データは黙って補完・読み飛ばししない
- TEST_パラメーターだけの観測は`synthetic`、それ以外は`ecu`。旧RRBridgeイベントは`source_kind=agent`で構造化登録
- `ProductBridgeOnce.java`で、明示的に指定したTSVファイルから一度だけSQLiteに取り込み、最大1 HTTP交換するCLIを追加。トークンは環境変数、**車載へ自動組込／自動起動しない**
- Windows/LinuxのSQLite実DBテストに`LegacyTsvBridgeTest`を追加。再読込・不完全行・イベント・profile定義の保護を確認
- [Product v1 Agent CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38339288365)：**Windows/Linuxの両方で SQLite 42 + HTTP 41 + TSV importer 16 = 各99件PASS**。合成データ／契約モックの試験。

**実Server接続試験の状態**

- `ProductServerE2E.java`と`scripts/test-product-v1-real-server.sh`で、独立したJava Agent→FastAPI→PostgreSQLの合成2件送信と重複再送を検証する試験を準備
- **GitHubの両リポジトリがPrivate**。Agentの標準`GITHUB_TOKEN`にはServerリポジトリの読取権限が無いため、専用の`ROMLOGGER_SERVER_READ_TOKEN`が設定されない場合に試験を**NOT RUN**と明示するCIゲートを設置
- [最初のE2E CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38339215160)は**認証情報未設定のため未実行**。スキップしたジョブをE2E成功と呼ばない

**未解決**

- Serverとの実E2E、X270実機、Java8 32bit、常駐取込・バックオフ、レガシーキューあふれ、TSVローテーション／削除、disk-full、409/422隔離
- 元PoCのQueueOverflowやTSV writer失敗を検出・保全する仕組みは未実装。今回の変換層だけでECU→DBの完全無欠損を保証しない
- 毎回全TSVファイルを先頭から走査する**検証用実装**。大容量ログでの効率化・永続オフセットは後続工程
- Agent側に実際のK-Line、Supervisor、romraider profile操作を組み込んでいない

### 2026-10-13 — Phase 2.1-B（Windows/Linux合成CI合格）

**追加・修正**

- `ProductSender.java`：既存SQLiteのProfileRevisionを先にPUT登録し、定義ACK後に同一runの観測レコードをJSON配列化してPOSTする。1回の呼出しで1 HTTP交換に限定
- `AgentWire.java`：Java8のみでHTTPS通信（loopbackはHTTP可）、転送先リダイレクト禁止、Bearer認証、パスと本文の上限、受信JSONの型・フィールド重複を厳格に検証
- HTTP200だけでは決してACKとせず、`committed`／`batch_id`／`record_count`／`inserted_count+duplicate_count`一致を条件にSQLiteの原本受領台帳へ反映
- 401/403/409/413/422/不正レスポンスは`NEEDS_REVIEW`、429/503・一時ネットワーク不通は`RETRY_LATER`とし、**いずれも未ACK原本を保持**
- 不正な201 Profile応答が例外伝播し得た点を修正し、原本保全した診断待ち結果へ変更。さらに**201 registered／200 already_registered**のステータスと応答本文の一致を厳密に照合するよう修正
- `ProductSenderTest.java`：ローカルHTTP契約モックと実SQLiteで、ACK喪失後の再起動・duplicate-only確定、レスポンス偽装、不正なJSON、各種HTTPエラーの保全を検証
- [通信実装・未検証範囲](product_v1_http_transport.md)を追加

**非対象／未検証**

- **本物のServer v1 PostgreSQLとJava Agent間のE2E試験は未実施**（ローカルHTTP契約モックでの検証）
- 既存`Stream.java`のLogger callbackへの組込み、バックグラウンド送信、デバイスtokenファイル運用、実ECU、X270 Zulu 8 32bitは未対応
- 413分割、409/422の不良レコードの隔離、Retry-After／指数バックオフ、鮮度優先と過去回収の公平性はPhase 2.1-C以降
- PoCの旧HTTP/TSV、RomRaiderアプリ、手動操作・安全ゲートは未変更

**CI**: [Agent PR #4](https://github.com/zawa356/RomRaider_agent/pull/4)／[GitHub Actions](https://github.com/zawa356/RomRaider_agent/actions/runs/38337745932)：**Windows・LinuxそれぞれSQLite 42件＋HTTP契約モック41件、計83件成功**。実際のServer PostgreSQL・実ECU／Zulu8環境は未検証。

### 2026-10-13 — Phase 2.1-A（Linux/Windows合成CI合格）

**追加**

- `src/main/java/net/neristal/rrbridge/product/v1/DurableSpool.java`：既存PoCとは分離した製品版SQLiteスプール。JDBC／WAL／synchronous=FULLで永続トランザクション
- 不変record IDとrun+sequenceを保護し、内容の衝突を拒否する。ProfileRevisionの登録確認前は対応する観測値の送信候補を返さない
- 同一runの観測を最大128件のバッチとして取り出し、バッチIDと照合した**全件確定ACKのみ**記録。本当にACKされた後はJSON本体をNULL化し、ID／ハッシュ／ACK履歴は保持
- タイムアウト、HTTP 503、形式不正応答ではACK関数を呼ばない構成。欠番の自動補完、未ACKデータの削除・ダウンサンプリングはしない
- `src/test/java/net/neristal/rrbridge/product/v1/DurableSpoolTest.java`：実SQLiteで再起動復旧、定義ACK待ち、ACK不正拒否、ACK喪失後の冪等再送、行衝突とbatch境界、保持を試験
- GitHub Actions `Product v1 Windows Agent spool CI`：ubuntu-latest / windows-latest のマトリクスでJava 8互換バイトコード、実SQLiteテストを実行。RomRaider.jar不要

**変更しない範囲**

- `Stream.java`の既存PoC TSVキューと送信・測定処理、`Agent.java`の操作API／安全ガード
- RomRaider本体、プロファイルXML、本番MCP／Serverの操作要求配送

**残課題・制約**

- 入力JSONと車両／端末IDのv1 wireシリアライズはPhase 2.1-Bで実装。現時点のspoolクラス単体は**JSONテキストを保存するだけ**で完全な通信スキーマ検証はしない
- HTTP送受信、トークン管理、認証失敗・Server 409/422の隔離、未ACK backlogの優先制御は未実装
- 物理電源断・disk-full・SQLite破損時の実機試験、車載PCのZulu 8 32bitおよびRomRaider同一JVMへの組込は未検証
- SQLite初期DB生成／ACK済み本体回収に対する性能特性の測定は今後実施
- [PR #3](https://github.com/zawa356/RomRaider_agent/pull/3)／[GitHub Actions](https://github.com/zawa356/RomRaider_agent/actions/runs/38336131261)：**ubuntu-latest・windows-latestともに実SQLiteの42チェックが成功**。Java 8互換コンパイル確認。X270の32bit Zulu8や電源断の実機試験は引き続き未実施
