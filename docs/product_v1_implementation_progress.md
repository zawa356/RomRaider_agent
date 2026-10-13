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
| 2.1-C1 | 既存RRBridge TSV→製品v1 SQLiteの非破壊取込・合成CI | **CI合格、PR #5** | [PR #5](https://github.com/zawa356/RomRaider_agent/pull/5)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-C2a | Agent固定版（JavaソースSHA照合）→実FastAPI/Postgresの合成E2E | **PASS（Server CI、Agent commit 6dd46835固定）** | [Server PR #22](https://github.com/zawa356/RomRaiderServer/pull/22)／[E2E CI](https://github.com/zawa356/RomRaiderServer/actions/runs/38346271102) |
| 2.1-C2b | Agent最新main自動同期E2E／常駐取り込み接続 | **未実装**（本Agent repoのPrivate checkout専用E2EはtokenなしでNOT RUN） | [E2E run](https://github.com/zawa356/RomRaider_agent/actions/runs/38339215160) |
| 2.1-D1 | TSV増分取り込み、SQLite永続offset＋安全再開・障害注入 | **CI合格・Agent PR #8** | [PR #8](https://github.com/zawa356/RomRaider_agent/pull/8) |
| 2.1-D2a | Agent増分取り込み版の実Server E2E再pin・再起動試験 | **実FastAPI/PostgreSQL E2E PASS・Server PR #24** | [Server PR #24](https://github.com/zawa356/RomRaiderServer/pull/24)／[E2E CI](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030034) |
| 2.1-D2b-1 | 明示起動の独立Worker、SQLite永続backoff／要調査ゲート、旧TSV監視 | **Windows/Linux合成CI合格** | [Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-D2b-2 | PoC queue drop／TSV writer破棄・削除条件の欠損対策、fresh/backlog公平転送 | 未着手 | [Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-D3 | X270 Zulu8 32bit・disk-full・物理電源断・実車耐久 | 未着手 | Issue #7 |
| 3 | ECU→RomRaider→Agent→Serverの停車状態での実車検証 | 未着手 | [Issue #8](https://github.com/zawa356/RomRaiderServer/issues/8) |

## 変更履歴

### 2026-10-13 — Phase 2.1-D2b-1：永続backoffと明示起動Worker／旧TSVソース欠損検知

- `DurableSpool.java`：ローカル`v1_delivery_state`にretry回数・次回送信時刻・要調査停止状態・最終送信結果を保存。Server側のACK/原本JSONの削除条件は変更しない
- `ProductDeliveryCoordinator.java`：RETRY_LATER時は2→4→8…最大120秒で遅延。再起動で待ち時間維持。認証・契約不備の`NEEDS_REVIEW`は再起動後も**送信禁止**。専用APIによる明示的なoperator resetまで止める
- `ProductBridgeWorker.java`：**`--once`／`--foreground`で明示起動した別Javaプロセスのみ**。TSVの増分取り込みと送信を分離し、SQLiteとOS lock fileで同一workerの多重起動を抑止。RomRaider Logger JVM本体・既存PoCの操作コードへ自動組込なし
- `ProductLegacyScanner.java`：旧TSVソース64個の上限・取り込み済みTSV消失／移動・symlinkを検知して**fail closed**。ファイルを削除しない
- `ProductDeliveryCoordinatorTest`：実SQLiteで再起動後のバックオフ・上限・正しいACKでの解除・要調査の永続停止／手動解除・予期しない例外伝播を47件試験
- `ProductLegacyScannerTest`：実SQLiteで途中行・再起動・元ファイル移動・64ファイル超・未ACK保全を14件試験
- `ProductBridgeWorkerTest`：別のJavaプロセス（合成HTTPモック）で定義送信→次プロセスでバッチ送信→ACK済行の再送防止→既読ファイル消失時のプロセス停止を11件試験
- [Windows/Linux GitHub Actions #38351975064](https://github.com/zawa356/RomRaider_agent/actions/runs/38351975064)：両OSで **SQLite42＋HTTP41＋旧TSV16＋増分37＋retry47＋scanner14＋別JVM11＝各208項目PASS**
- [送信Worker詳細／旧PoCの欠損リスク調査](product_v1_delivery_worker.md)。旧`Stream.offer()`のdrop、`writerLoop()`のdrain後write失敗時の行消失、`cleanupSpool()`の旧ACKだけによる元ファイル削除は**なお未解決**。D2b-2で優先対応
- **実Server E2EのD2b-1版pinは未更新**。現行Server PR #24はD2aのJava実ソースのみ。ECU・X270／Zulu8 32-bit・電源断・disk-full・常駐起動設定も未検証。自動デプロイは行わない

### 2026-10-13 — Phase 2.1-D2a：実Server E2Eの最新増分取り込み対応（テストコード）

- `ProductServerE2E.java`を従来の全件`LegacyTsvBridge`から、**`IncrementalTsvBridge`と永続offset**を使用する実Serverテストへ更新
- 合成TSVを初回1定義行だけ読み、**プロセス再起動→残り定義＋観測1件→未完結TSV行保留→追記→再起動して合計3件**をSQLiteへ保存する手順へ変更
- FastAPIへ定義PUT→3件のPOST→完全ACKを受け、**新しい別SQLite**で同一TSVを2/2/1行の増分チャンクで読み直し、同一原本IDを再送して重複のみのACKが成立するか検証
- 使い捨てPostgreSQLの直接照合を観測**3件**・ProfileRevision**1件**に変更。[Server PR #24](https://github.com/zawa356/RomRaiderServer/pull/24)でAgent main commit `668ad5230e7e3db4fc592311c857906f43802ad3` の7ソースファイルを原本Git blob SHA一致で固定し、[実E2E CI #38350030034](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030034)がPASS
- [Server製品版契約CI #38350030080](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030080)と[PoC回帰CI #38350030122](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030122)も全ジョブPASS
- 既存RRBridgeのLogger callback、操作系、実車、自動スケジューラは対象外。Agent main変更のたびにServer CIが自動同期する構造ではなく、次の変更では再pin／差分確認が必要

### 2026-10-13 — Phase 2.1-D1：TSV増分取り込み／SQLite永続カーソル（Windows/Linux CI合格）

- `IncrementalTsvBridge.java`：旧TSVの読み取り専用処理を**保存済みoffsetから増分実行**。メタデータ定義のスナップショットをSQLiteへ保持して再起動後も全履歴再走査不要
- `DurableSpool.java`：`v1_import_cursors`を追加。**SQLiteへの原本保存が成功してから**offset・行番号・定義状態・source fileKey・先頭／境界SHAを更新。CASで古いカーソルによる後退を拒否
- `ProductBridgeOnce.java`：明示実行時に最大256の完結済みLF行のみ取り込み、最大1 HTTP交換。**従来PoCの`Stream.java`やLogger callbackを変更・自動起動しない**
- `IncrementalTsvBridgeTest.java`：再起動後の続き読み、部分行、故障レコードによるoffset停止、元ファイル改変／短縮の拒否、**原本DB commit直後／カーソル更新前の故障注入→重複なしの再処理**を実SQLiteで試験
- [PR #8 最終CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38348565296)：Windows／Linuxそれぞれ**SQLite 42＋HTTP 41＋旧TSV 16＋増分取り込み 37＝136項目PASS**
- [失敗CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38348269336)：故障注入テスト用TSVでタブ記号のエスケープを誤り形式不正。テストfixtureを修正した再実行でPASS。**失敗履歴は残す**
- [詳細と限界](product_v1_incremental_import.md)：既存キューあふれ／TSV writer欠損／ソース削除とローテーション、fileKey不明時の交換検知、実車負荷・電源断・disk-full・Windows X270/Zulu8、Server側pin E2E更新はいずれも未完了
- phase進行は安全側：未ACK原本を削除せず、予期しないソース変更は自動リセットしない

### 2026-10-13 — Phase 2.1-C2a：pin済みAgentと実Server/PostgreSQL合成E2Eの成功

- [Server PR #22](https://github.com/zawa356/RomRaiderServer/pull/22)に、本Agentのcommit `6dd46835a200d961d517f5bb54f11eb2d7bdd2af` のJavaソース5ファイルと実Server E2Eスクリプトを**検証用に原本blob SHA一致で固定**。製品版Agent本体は変更していない
- [E2E CI #38346271102](https://github.com/zawa356/RomRaiderServer/actions/runs/38346271102)で、Java8互換の実ソース→レガシー合成TSV→SQLite→実FastAPI→Disposable PostgreSQL16（観測2件＋定義1件）を成功。**別SQLiteからの同一record ID再送も重複登録なし**
- これで**pinしたAgentバージョンの実Server結合は検証できた**が、Agentの将来のmain自動追従ではない。旧RRBridgeの実車Logger callback、ECU、X270、Zulu8 32bit、TSV writer欠損／queue drop、常駐自動運用は未試験
- [pin版と同期更新手順](https://github.com/zawa356/RomRaiderServer/blob/main/tests/e2e/agent_v1_snapshot/README.md)。本Agent repoの`ROMLOGGER_SERVER_READ_TOKEN`を必要とする別Private checkoutワークフローは引き続き**NOT RUN**（別ルートの成功とは区別する）

### 2026-10-13 — Phase 2.1-C1追加修正：mainマージ後Windows CIの外部依存403

- **障害履歴（隠さない）**：[Agent main CI #38339774312](https://github.com/zawa356/RomRaider_agent/actions/runs/38339774312)のWindowsジョブで、`repo.maven.apache.org`からの依存JARダウンロードがHTTP403を返して終了。LinuxジョブはPASS、Javaコードのテスト自体はWindowsで開始できなかった
- **原因区分**：ビルド用依存ファイルの外部取得失敗。SQLite/HTTP/TSVの業務ロジック不一致ではないが、main CIの失敗として取り扱う
- **修正**：Maven Central公式2エンドポイントへの段階的再試行、部分ダウンロードを隔離、Java `jar tf`で破損確認してからJAR配置、OSごとのテスト依存jarキャッシュ
- **修正ブランチ再試験**：[PR #6](https://github.com/zawa356/RomRaider_agent/pull/6)／[Windows・Linux CI #38340182185](https://github.com/zawa356/RomRaider_agent/actions/runs/38340182185)ともに**99項目PASS**。mainへマージ後の再検証も行う。実Server E2EのNOT RUN問題はこのCI修正では解消しない

### 2026-10-13 — Phase 2.1-C1：RRBridge PoC TSV→製品SQLite取り込み（合成CI）

**追加**

- `LegacyTsvBridge.java`：既存RRBridge v0.5のM/S/E TSVを読み取り、ProfileRevision・Sample・Eventの正式v1 JSONを作りSQLiteに永続登録。TSV本体は読み取り専用。既存Logger callbackとPoC送信・安全制御は変更しない
- IDはdevice ID・旧session・segment・元sample sequence／行位置から**決定論的に生成**。再読み込み・再起動後も変わらず、二重登録は原本UUIDで抑制
- 元TSVのUTF-8/LF終端済み行だけ処理。書込み途中の行は保留し、不正データは黙って補完・読み飛ばししない
- TEST_パラメーターだけの観測は`synthetic`、それ以外は`ecu`。旧RRBridgeイベントは`source_kind=agent`で構造化登録
- `ProductBridgeOnce.java`で、明示的に指定したTSVファイルから一度だけSQLiteに取り込み、最大1 HTTP交換するCLIを追加。トークンは環境変数、**車載へ自動組込／自動起動しない**
- Windows/LinuxのSQLite実DBテストに`LegacyTsvBridgeTest`を追加。再読込・不完全行・イベント・profile定義の保護を確認
- [Product v1 Agent CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38339596749)：**Windows/Linuxの両方で SQLite 42 + HTTP 41 + TSV importer 16 = 各99件PASS**。合成データ／契約モックの試験。

**実Server接続試験の状態**

- `ProductServerE2E.java`と`scripts/test-product-v1-real-server.sh`で、独立したJava Agent→FastAPI→PostgreSQLの合成2件送信と重複再送を検証する試験を準備
- [PR #5](https://github.com/zawa356/RomRaider_agent/pull/5)に実装、テスト、通信契約と安全上の制約、任意実Server E2Eの試験手順を記録。\n- **GitHubの両リポジトリがPrivate**。Agentの標準`GITHUB_TOKEN`にはServerリポジトリの読取権限が無いため、専用の`ROMLOGGER_SERVER_READ_TOKEN`が設定されない場合に試験を**NOT RUN**と明示するCIゲートを設置
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
