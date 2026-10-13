# 07. 作業履歴（時系列）

方針: 消さずに追記する。失敗・回り道も残す（同じ失敗を繰り返さないため）。

---

## 2026-10-13：製品版 Phase 2.1-D2b-1（永続送信backoff／旧TSV監視）

- SQLiteにretry期限／回数／要調査停止を永続化し、認証・契約エラーの自動再送を禁止。明示起動の別JVM Workerに限り読み取り専用旧TSV監視とServerへのHTTP送信を実行
- 実際の別Javaプロセスで定義→送信→ACK再送防止→既読TSV消失時停止を合成HTTPで試験。Windows/Linuxで各208項目PASS
- 既存PoCのwriterはqueueから取り出した後の書き込み失敗で行を失い得ること、送信済みTSVを旧ACKに基づき自動削除することをコードで確認。D2b-2に未解決の重大リスクとして記録
- 詳細：[送信Worker・欠損リスク](product_v1_delivery_worker.md)と[Phase台帳](product_v1_implementation_progress.md)

---

## 2026-10-13：製品版 Phase 2.1-D1 増分TSVと故障復旧

- 旧TSVの全行再走査を廃止するため、永続offset・行番号・定義snapshotのSQLite CAS台帳を追加。完結済み行だけ進める。
- 保存直後／cursor更新前クラッシュを合成注入し、レコード再処理でデータ重複も欠落も無いことをWindows/Linux実SQLiteで検証。失敗CIの試験fixture修正も作業台帳に残す。
- [増分取り込み詳細](product_v1_incremental_import.md)と[Phase進捗](product_v1_implementation_progress.md)を参照。
- 実車自動有効化、旧PoC安全ガード変更は行わない。

---

## 2026-10-13：Windows main CI Maven 403への修正

- [main CI #38339774312](https://github.com/zawa356/RomRaider_agent/actions/runs/38339774312)はWindows runnerがテスト用SQLite JDBC JARのHTTP取得中に403を受けて失敗（テストは未実行）。Linuxは成功。
- Maven Central 公式URLの再試行／代替ドメイン／JARの破損検査／GitHub Actions依存キャッシュを追加。修正コミットと修正後のCIを正本に追記する。

---

## 2026-10-13：製品版Phase 2.1-C1 PoC TSV→v1取込／E2E準備

- PoCの`Stream.java`内部のQueue/TSV writerとServer v1の送信契約が違うため、Logger callbackにI/Oを足さず**TSVの読み取り専用インポーター**を追加。
- 安定IDでM/S/E行をv1のprofile/observation/eventへ変換してSQLiteに追加。同じ旧TSVの繰り返し取込、切れた行、再起動をWindows/Linux合成テストへ追加。
- 本物のServer PostgreSQLへ送るE2E試験を用意したが、Private→Privateを読むCI credentialが未設定で実行できていない。**モックCI成功とE2E実行を区別**。
- 経緯と未解決：[Phase履歴](product_v1_implementation_progress.md)。

---

## 2026-10-13：製品版Phase 2.1-B HTTP送信クライアント（実装中）

- Phase 2.1-AのSQLite outboxに、Java8単体のHTTPS／JSON送信クライアントとServer v1の完全ACK照合を追加。
- ローカルHTTPモックで実SQLiteからProfile先行PUT→計測POST、接続失敗／401／503、偽ACK、commit後ACK喪失の再送を再現。
- 既存PoCのLogger/Streamを変更せず、今回のCIを本物のPostgreSQLに対するE2E成功と混同しない。
- 参照：[製品HTTP通信実装の範囲](product_v1_http_transport.md)、[Phase実装履歴](product_v1_implementation_progress.md)。

---

## 2026-10-13：製品版Phase 2.1-A SQLiteスプール（実装中）

- RomRaiderServerのPhase 1.2-A/Bで受信側のPostgreSQL原子的ACK・通信契約CIが通過したことを前提に、Agent側のdurable spoolに着手。
- PoCの`Stream.java`を直接製品用に置換すると安全ガード・旧転送に影響するため、新Javaパッケージ`net.neristal.rrbridge.product.v1`へ分離。
- SQLiteのWAL／FULL同期、record ID・run sequence衝突検出、profile定義ACK待ち、未ACK保持、バッチ単位の受信確認を実装。
- JDK17の`--release 8`でJava8互換バイトコードを生成し、Linux/WindowsでSQLite実DB試験を実行。実車・K-Line・Zulu 8 32-bitは未検証。
- 詳細：[`product_v1_implementation_progress.md`](product_v1_implementation_progress.md)とGitコミット／PRを参照。

---

## フェーズ0: ChatGPT での検討（〜2026-10-10）

出典: 引継ぎ資料 §10（`docs/handoff/`）。この期間の作業はChatGPT上の会話で、実機での実行結果はほとんど残っていない。

1. **初回の要望**: RomRaiderの手動設定・ログ取得項目設定をMCP化したい → XMLプロファイル操作で一部実現できると調査。
2. **目標の拡張**: ユーザーがB案（RomRaider全体の操作）を最終目標に指定。運転中に ログ分割 → 解析 → 次の項目選定 → 再計測 を自律化したい。CSV解析も必須。
3. **遠隔化**: ユーザーはChatGPT Desktop＋WorkでローカルstdioのMCPが動くことを確認済み。ただしWorkの使用量が厳しいため、Remote MCPやCloudflare Tunnelも候補に。
4. **LLM利用料の制約**: 通信回線は使える前提、API従量課金は避けたい。Claude Code／Codex／Work／Coworkはサブスク枠が厳しい。通常のチャットに拡張機能でトリガーを送る案を検討。
5. **データ構造案**: ユーザー案「CSV完了 → Python ETL → SQLite → 定期的にGitHubへPush → チャットのGitHub連携から検索」。GitHubはSQLを実行できず、SQLiteバイナリの頻繁なPushはリポジトリ肥大化と競合を招くため、GitHubはサマリー・ソース・レポート向きと整理。
6. **VPC（実際はVPS）DB案**: ユーザーからMySQL/MariaDBを立てる案。リアルタイム保存とRemote MCPによるSQL照会、車載側からキューを取りに行く方式を候補に追加。
7. **Chrome拡張案**: ユーザーが「5分間隔でチャットに自動送信」を提案。技術的には可能だが、規約・DOM依存・制限のため未採用。
8. **音声案**: ユーザーがAndroidのChatGPT Liveで走行中に音声＋MCPを使う案を提案。自作MCPへの対応と承認フローは要実機検証。
9. **構成整理**: 車載X270（RomRaider＋Adapter＋Supervisor＋ETL）／VPC（Telemetry API＋MariaDB＋MCP）／AIクライアント（チャット・Live）の3層を有力案に。最大の課題は「サブスクの通常チャットを使った自律的なLLM起動」。
10. **2026-10-10**: 引継ぎ資料をChatGPTのLibraryに保存し、別チャットから参照・編集できるようにした。
11. ChatGPT時代に公開GitHubの `RomRaider/RomRaider`（`1.1.0-NOV26-2025`）のソースを調査し、`UserProfileHandler`、`UserProfileImpl`、`UserProfileLoaderImpl`、`ReloadProfileAction`、`LoadProfileAction`、`EcuLogger`（`startLogging`/`stopLogging`/`restartLogging`/`getCurrentProfile`/`loadUserProfile`）、`FileUpdateHandlerImpl`、`FileLoggerImpl` を確認していた。この時点では「外部プログラムから自動実行するAPIの存在は未確認」。

その他（ダウンロードフォルダのファイル名から）: 2026-09-13〜09-21に `OpenPort_X270_*` という名前の検討資料（Online Operation Proposal／Review、Claude Review Response、Cowork作業指示書、Work版作業指示書、AI引継資料詳細版）が作られている。内容は未確認。2026-09-21に実車で計測を行っている（`05_environment.md` §5）。

---

## フェーズ1: Claude Cowork へ移行、現状調査（2026-10-12 11:38〜11:52）

- 11:38 ユーザーが引継ぎ資料（2026-10-10版）をClaudeのプロジェクト「【RomLogger】AI連携」に添付。
- ユーザー発言: 「ChatGPTのワークモードが調子悪いので、このままCoworkで進めたい。今RomRaiderを起動しているので、自動化ができるかどうかの検証を進めたい」。
- X270にリモート接続。`C:\Users\zawa\.RomRaider` のアクセス許可を得て `settings.xml`、`profile_backup.xml`、`rr_system.log` を確認。
  - `profile_backup.xml` は245パラメーター・173スイッチ・外部5項目を列挙しているが、`selected` は付いていなかった（何も選択されていない状態で保存されたもの）。
  - `rr_system.log` 末尾: OpenPort未接続（`ERR_DEVICE_NOT_CONNECTED`）を1秒周期で再試行中。
- `Documents\OpenPortWork` のアクセス許可 → `Logs` に2026-09-21のCSV 11本を発見。周期と項目数を集計（`05_environment.md` §5）。**「実車疎通は未確認」とされていた引継ぎ資料の記述を「9/21に成功済み」に更新**。
- `rr_system.log` から ECU ID `2F04785006`、FW 1.17.4877、DLL `op20pt32.dll`、13:51の「51バイト待ちタイムアウト」繰り返しを確認。
- 画面操作でRomRaiderを前面に出そうとしたが、タスクバーのクリックにはエクスプローラーの許可が必要で、この時点では見送った。

## フェーズ2: 外部制御の窓口を探す（11:52〜11:56）

- `C:\Program Files (x86)\RomRaider` の読み取り許可。`src/`（ソース同梱）、`runtime/`（Zulu 8 32bit JRE）、`plugins/`、`run-logger.bat` を確認。
- 最初に**プラグイン機構**を検討: `ExternalDataSource.getMenuAction(EcuLogger)` でインスタンスが手に入る。しかし `ExternalDataSourceLoaderImpl` はアプリのクラスローダーで読むため、自作jarをクラスパスに入れる必要がある。`plugins/` への書き込みは `Permission denied`。
- 次に「作業ディレクトリを `RRBridge` に変えて、そこに `plugins/` を置く」案を検討 → `customize/j2534Libraries.properties`、`lib/log4j.properties`、`i18n` など相対パス依存が多く、J2534 DLL探索が壊れるため却下（`04_romraider_internals.md` §6）。
- **`EcuLogger.getEcuLoggerWithoutCreation()`（public static）を発見** → `-javaagent` なら作業ディレクトリもクラスパスも変えずに入れると判断。`runtime/bin/instrument.dll` があることも確認。
- 公開メソッド `startLogging`／`stopLogging`／`isLogging`／`loadUserProfile` と、CSV記録が private の `logToFileButton` 経由であることを確認。

## フェーズ3: RRBridge v0.1.0 の実装と配置（11:56〜12:00）

- クラウド側コンテナにJDK 21があり、`--release 8` でJava 8向けにコンパイル。RomRaider.jarをX270からコピーしてコンパイル時の参照に使用。
- コマンドの受け口は「ファイル（inbox/outbox）」と「HTTP（127.0.0.1:18765）」の2つ。ファイル経由にしたのは、クラウド側からX270のlocalhostに直接届かず、マウントしたフォルダ経由なら試験できるため（後段のSupervisorでも使える）。
- テスト用プロファイルは、Logger定義v370から名前→IDを引いて作成: basic（P8 rpm、P9 km/h、P13 %、P25 kPa、P2 C、P11 C）、fuel（P8、P12 g/s、P18 V、P58 Lambda、P3 %、P4 %、P13 %）。9/21に8項目8Hzで安定していた構成に近いものを選んだ。
- `Documents\OpenPortWork\RRBridge` に jar・bat・profiles・README を配置。
- コンテナでの起動試験: `premain` は動くが、log4jがクラスパスにないため `NoClassDefFoundError: org/apache/log4j/Logger` で止まる（コンテナ固有。実機では問題なし）。

## フェーズ4: 実機での外部制御試験（12:00〜12:06）

- 12:00 ユーザー「RomRaiderを再起動した」→ しかし `rrbridge.log` が出ない。`rr_system.log` の System Properties を見ると `sun.java.command=RomRaider.jar -logger`、`rrbridge` 関連のプロパティなし → **通常のショートカットで起動されていた**（11:59:27起動）。
- エクスプローラーを「クリックのみ」の権限で許可してもらい、Claudeが RomRaider を File > Exit で終了 → `Documents\OpenPortWork\RRBridge\start-romraider-with-bridge.bat` をダブルクリックして起動。
- 12:03 v0.1.0 起動成功。試験結果は `06_verification.md` §1。**CSV記録中のプロファイル切替でヘッダーが多重化**することを発見。
- この時点でOpenPortはUSB接続されたがECU無応答（`timeout expired waiting for 2 more message(s)`）。
- 12:04 `switch_profile` を追加した v0.2.0 をビルド。jarはRomRaider実行中はロックされるため、RomRaider終了 → 上書き → 再起動。
- 12:05 v0.2.0 で1区間1CSVを確認。**同じプロファイルでも列順が変わる**ことを発見。
- 引継ぎ資料に第13節（実機検証結果）・第14節（決定ログ）を追記し、プロジェクトに保存。

## フェーズ5: リアルタイム転送の検証（12:07〜12:22）

- ユーザー要求: 「CSVだけだとリアルタイム分析ができない。RomRaiderから直接SQLサーバー等に転送し、音声モードのスマホからHTTPのMCPで解析を呼び出せる構造にしたい。ログを取りながら転送できるか検証してほしい」。
- ソース調査で、データ配信の主経路が `QueryManagerImpl.updateHandlers` → `AsyncDataUpdateHandler` であることを確認。`DataUpdateHandlerManagerImpl.addHandler` はタブ用の別経路で使えないことも確認。
- 方式決定: 配列をリフレクションで差し替えて自前のハンドラーを追加 → キュー → spool（正本）→ HTTP送信。受信側は `session+seq` で冪等。
- 受信側にSQLが必要 → sqlite-jdbcをMaven Centralから取得しようとして **HTTP 429（レート制限）**。20秒待って `repo.maven.apache.org` から取得（3.46.1.3、14,127,503バイト、class version 52＝Java 8、`org/sqlite/native/Windows/x86/sqlitejdbc.dll` 同梱）。SLF4Jがなくても警告のみで動作。
- コンテナ内で、受信停止→再送、重複排除を試験して合格。送信スレッドの待機を1000ms→500msに短縮。
- 12:14 v0.3.0 起動。`stream installed (handlers=10 …)`。受信サーバー未起動のまま80サンプル → spoolに保持を確認。
- 12:16 `start-receiver.bat` 起動 → **失敗**。DBファイル（テーブル）は作られたが接続拒否。コマンドプロンプトの表示権限（クリックのみ）をもらって画面を確認 → `ClassNotFoundException: com.romraider.logger.ecu.ui.handler.DataUpdateHandler`（`Receiver` が `Stream.read()` を呼び、`Stream` が `DataUpdateHandler` を実装しているため、RomRaider.jarなしでは読めない）。最初のバッチは `pause` で止まっていたので出力が画面にしか残らなかった → バッチを `data\receiver.log` へ出力するよう変更。
- 対処: 共通処理を `Util` に切り出し、受信サーバーを `rrreceiver.jar`（`Receiver*.class`＋`Util.class`）として別jarに。RomRaider側のjarを差し替えずに済むので、RomRaiderは再起動しなかった。
- 12:18 受信サーバー起動成功 → 停止中の80サンプルが自動で全件送られた。続けて240サンプルで遅延を測定（平均273ms、最大533ms）。
- X270のLinux VM側から `telemetry.db` を直接sqlite3で開くと `disk I/O error`（WALファイルとマウントの相性）→ ファイルをクラウド側にコピーして集計。
- 引継ぎ資料に 13.4〜13.5 と決定ログを追記。

## フェーズ6: サーバー構成の提案（12:25ごろ）

- ユーザー発言: 「VPCというかVPS（レンタルサーバー）。OSもApacheもNginxも決めていないので構成を提案してほしい。自宅PCにVPNを張ってSQLサーバーを立てることもできる」。
- 調査（2026-10-12時点の公式ヘルプ等）: MCPは各社クラウドから接続するため公開が必要。ChatGPTの自作MCPはWeb専用・Business以上（Proは読み取りのみ）、音声モードでは動かない報告あり。Claudeのカスタムコネクタは個人プランでも追加でき、Bearerトークン認証可、音声モードは接続済みツールに対応（カスタムコネクタは未明記）。
- 提案: VPS 1台（Ubuntu 24.04、Caddy、PostgreSQL、Python受信API＋MCP、Docker Compose）。最初に「Claudeモバイル音声から自作MCPを呼べるか」をダミーMCPで検証することを推奨。詳細は `02_architecture.md` §2。ユーザーの決定はまだ。

## フェーズ7: GitHub化（12:34〜）

- ユーザーが空のprivateリポジトリ `zawa356/RomRaider_agent` を作成し、ここに書き込むよう依頼。
- 初回コミット `1486092`「Initial import: RRBridge v0.3.0」を `main` に直接push（空リポジトリのため）。コミット作成者は `zawa356 <zawa356@users.noreply.github.com>`（メールアドレス非公開設定でpushが拒否されるのを避けるため）。
- jar（RomRaider.jar、sqlite-jdbc.jar）はコミットせず `lib/` に置く方式。`build.sh` で `dist/` に配置一式を生成し、X270で動いているものと同じクラス構成になることを確認。
- ユーザー要求「README、設計方針、履歴を詳しく。主に読むのはAIなので要約より情報量重視」→ `AGENTS.md`、`CLAUDE.md`、`docs/00〜09`、`docs/handoff/` を作成（このファイルを含む）。

---

## フェーズ8: v0.4.0 改善（12:45〜13:05）

- ユーザー発言: 「改善する箇所があればどんどんこの環境とGitを触って改善してもらって大丈夫です。すすめてください」。
- `09_open_issues.md` の技術的負債を中心に改善（一覧は `03_rrbridge_design.md` §9）。主なもの: 読み取り系をロックから外しHTTPを複数スレッド化、GET対応、任意のHTTP認証、`list_profiles`、`wait_ms`、`logging_stop` でCSVも停止、設定ファイル、ログのローテーション、送信済みspoolの自動削除、キュー溢れの計数、`mainLoop` の例外耐性、原子的なファイル置換、E行の文字列を保持、受信側の件数修正と `/health`。
- 自己試験 `SelfTest.java`／`test.sh` を追加（30項目）。初回から全件PASS。
- X270の画面操作の許可が30分の無操作で失効していたため再承認を依頼（RomRaider Logger／エクスプローラー／コマンドプロンプト）。
- 配置: RomRaider終了、受信サーバーのコンソールを閉じ、jar等を上書き、受信サーバー→RomRaiderの順に起動。実機での回帰試験は `06_verification.md` §4。全項目期待どおり。

## フェーズ9: 受信API＋Remote MCPサーバーの試作（13:05〜13:35）

- 「どんどん改善して進めて」の指示を受け、`02_architecture.md` §2 の推奨構成の中身を `server/` に試作。VPS・ドメインはユーザー未決定のため、配置はしていない。
- 作業環境の `pip` で `mcp` を入れたところ **2.3.0（v2系）** で、`mcp.server.fastmcp` が存在しない（`MCPServer` に改名）。v2のAPI（`MCPServer`、`custom_route`、`streamable_http_app(stateless_http, json_response, transport_security)`、`ToolAnnotations`）で実装。
- DB層は SQLite／PostgreSQL 両対応（`DATABASE_URL`）。スキーマはJava版受信サーバーと同一（`events.source` 列だけ追加。既存SQLiteには `ALTER TABLE` で追加）。
- 試験: pytest。コンテナに PostgreSQL 16 のバイナリがあったので、`postgres` ユーザーで `/var/tmp/pgrr` に一時クラスタを作って試験（最初 `/tmp/claude-0` 配下に作ろうとして権限エラー、`rm -rf` を含むコマンドは安全確認で拒否されたため別ディレクトリにした）。SQLite 18件、PostgreSQL込みで32件PASS。
- 初回の失敗: 試験データの生成時刻が呼び出しごとに変わり、イベントの重複排除の試験が誤って失敗（試験側の問題）→ 時刻を固定。
- X270 の実際の spool（`rr_20261012_121452.tsv` 330行、`rr_20261012_125843.tsv` 172行）をそのまま PostgreSQL 版の `/ingest` に送り、全件取り込み（320＋160サンプル、イベント4＋9）→ MCP の `list_segments`、`list_events`、`add_event_marker` で照会・記録できることを確認。
- `list_*` ツールが配列を返すとMCPの応答が要素ごとの複数contentに分かれたため、`{"sessions":[…]}`／`{"events":[…]}` の形に変更。
- Docker デーモンがないため、イメージのビルドと起動は未確認（`docker compose config -q` で構文のみ確認）。

## フェーズ10: 別経路でのMCP呼び出し成功、ローカルデータの回収（13:40ごろ）

- ユーザー発言 [ユーザー発言]: 「別経路で、MCPをChatGPTのプラグイン等から呼び出すことは成功しました」。どのMCPサーバー（このリポジトリの `server/` か、別に作ったものか）、どのクライアント・プラン・接続方法（公開URL、認証）、音声モードかどうかは未確認。
- ユーザー依頼「エージェントの方で作ったやつが多分まだローカルにあるデータがあると思うので全部GitHubに上げてください」。
  - リポジトリ側（コード・ドキュメント）は全コミットpush済み（`f66b3b0`）で、未pushの変更はなかった。
  - X270上にしか無かったものを `archive/` に回収: 実車CSV（2026-09-21、11本）、試験で作られたCSV、`outbox` の応答JSON 32件、spool（TSV）、`rrbridge.log`、`status.json`、Java版受信サーバーのログとSQLite（WALをチェックポイントして1ファイル化）、`.RomRaider` の `settings.xml`・`profile_backup.xml`・`rr_system.log`。一覧は `archive/README.md`。
  - 2026-10-10 の ChatGPT 版引継ぎ資料（原本）も `docs/handoff/` に追加。
  - ルートの `.gitignore`（`status.json`、`rrbridge.log` 等）が `archive/` 内まで除外してしまうため、`!archive/**` を追加。ディレクトリ名は既存の除外（`data/`、`outbox/`、`spool/`）と重ならない名前にした。
  - `Downloads` の `OpenPort_X270_*.md` と `Documents\Codex` はフォルダのアクセス許可の確認がタイムアウトし、未回収。

## フェーズ11: 取得速度の可視化とガード（13:45〜14:00、v0.5.0）

- ユーザー質問 [ユーザー発言]: 「このエージェントツールはどれぐらいのスピードが出ているかも送信するようになっていますか。パラメータを選びすぎるとK-Lineのスローに追いつけなくなってデータがうまく取得できない問題が過去にあった。MCP経由で設定がめちゃくちゃにされると全データが取れない事故が起きかねないが、対策はされていますか」。
- 回答時点（v0.4.0）の状態: 各サンプルに時刻は付いており受信側で周波数を計算できたが、RRBridge自身は速度も通信エラーも送っていなかった。項目数の上限も、速度低下時の復帰もなかった。MCPに設定変更の道具はなく、切替は `profiles/` のファイルに限られていた。
- 対応（v0.5.0）: 取得速度の計測と `health` イベント、log4j経由の通信状態の計数（`LinkMonitor`）、項目数の上限（既定20）、切替後の速度低下時の自動復帰。server側に `get_acquisition_health` ツールと、`get_vehicle_status` の `acquisition` 欄を追加し、MCPの `instructions` に「2Hzを下回る構成は提案しない」を追記。
- server の PostgreSQL 試験で `LIKE 'health %'` のリテラル `%` を psycopg が書式と解釈してエラー → `db._sql()` で `%` を `%%` にしてから `?` を `%s` に変換するよう修正。
- 実機: 21項目プロファイルの拒否、log4jアペンダーの取り付けと通信エラーの計数、health／link_error イベントの送信を確認。自動復帰はECUデータが無いため未確認。

## 2026-10-12 時点のX270の状態

- RomRaider: RRBridge v0.5.0 付きで起動中（13:58時点）、ロギング停止、プロファイル basic.xml、session `20261012_135639`。`rrbridge.properties` 配置済み、`profiles/test_21params.xml`（試験用、読込不可）あり。
- 受信サーバー: v0.4.0 で起動中（127.0.0.1:18766、`data\telemetry.db` に selftest 320＋160サンプル）。
- OpenPort: USB接続、ECU無応答。
