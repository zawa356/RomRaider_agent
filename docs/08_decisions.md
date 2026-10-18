# 08. 決定記録（ADR）

形式: 背景 / 選択肢 / 決定 / 理由 / 結果・影響 / 状態。新しい決定は末尾に追記し、覆すときは新しい番号で「D-xxx を置き換える」と書く。

---

## D-001 作業環境を ChatGPT Work から Claude Cowork へ移す（2026-10-12）

- 背景: ChatGPT のWorkモードの調子が悪い [ユーザー発言]。
- 決定: このプロジェクトは Claude Cowork（Claudeのプロジェクト「【RomLogger】AI連携」）で進める。引継ぎ資料はClaude側でも更新し、ChatGPTのLibraryにはユーザーが反映する。
- 影響: ClaudeからX270にリモート接続して実機作業ができるようになった。
- 状態: 有効。

## D-002 RomRaider の外部制御は `-javaagent`（RRBridge）方式とする（2026-10-12）

- 背景: 引継ぎ資料では「XML＋再読込／Java側に制御API追加／UI操作」の3案が未選択だった。
- 選択肢:
  1. 設定・プロファイルXMLを外部で書き換えて再読込 → 実行中のRomRaiderが終了時に上書きし競合する。再読込を外部から発火できない。
  2. プラグイン機構 → インスタンスは取れるが、クラスパスへの追加か Program Files への書き込みが必要。作業ディレクトリ変更は相対パス依存で不可。
  3. RomRaiderのソース改造・再ビルド → 本体更新への追従が難しい。
  4. UI操作（画面クリック、Java Access Bridge） → 遅く壊れやすい。無人運用に向かない。
  5. **`-javaagent`** → 作業ディレクトリもクラスパスも変えずに同じJVMへ入れる。`EcuLogger.getEcuLoggerWithoutCreation()` でインスタンスを取得できる。
- 決定: 5。公開メソッドを優先し、必要最小限のprivateフィールドだけリフレクションで扱う。
- 結果: 実機で status／logging／filelog／profile の操作を確認。RomRaiderのバージョン依存（`04_romraider_internals.md` §1）が残る。
- 状態: 有効。

## D-003 RRBridge の配置は `Documents\OpenPortWork\RRBridge`、起動は専用バッチ（2026-10-12）

- 背景: Program Files は一般ユーザーで書き込めない。RomRaiderの作業ディレクトリはインストール先である必要がある。
- 決定: jar等はユーザーフォルダに置き、`start-romraider-with-bridge.bat` が `cd /d <インストール先>` してから `-javaagent:"<RRBridge>\rrbridge.jar" -Drrbridge.dir="<RRBridge>"` 付きで公式と同じ引数で起動する。
- 影響: **通常のショートカットから起動するとRRBridgeは読み込まれない**（実際に一度起きた）。運用ではショートカットを差し替えるか、Supervisorから起動する。
- 状態: 有効。

## D-004 コマンドの受け口はファイル（inbox/outbox）とHTTP（127.0.0.1:18765）の2経路（2026-10-12）

- 理由: ファイル経由は、外部（クラウド側のAI、別プロセス）からマウント経由でも試験でき、プロセスが落ちても指示が残る。HTTPは後段のSupervisor／MCPから扱いやすい。処理は `Agent.execute()` に一本化。
- 制約: HTTPは認証なし（localhost限定）。同じPC上の任意のプロセスから操作できる。
- 状態: 有効。

## D-005 プロファイルは `RRBridge\profiles` のファイル名だけを受け付ける（2026-10-12）

- 理由: 任意パスを受け付けると、LLMやネットワーク経由の指示で意図しないファイルを読ませられる。計測計画の「承認済みの範囲」をファイルの配置で表現できる。
- 実装: `[A-Za-z0-9_.-]+\.xml` かつ実在。範囲外は拒否を実機確認。
- 状態: 有効。

## D-006 計測区間の切替は `switch_profile`（記録停止→読込→記録再開）で行い、1区間1CSVとする（2026-10-12）

- 背景: CSV記録中に `loadUserProfile` すると、項目の登録・解除のたびにヘッダー行が追記され、1つのCSVに10行のヘッダーが並んだ（実機）。
- 決定: 記録中なら停止→読込→1.5秒待機→再開。区間＝CSVファイル＝segment として扱える。
- 影響: 切替のたびに短い欠測が出る（ECU通信中の長さは未測定）。転送ストリーム側は止めないので、ストリームには中間状態のsegmentが記録される。
- 状態: 有効。

## D-007 CSVを経由せず、ロガー内部の配信から直接データを取り出す（2026-10-12）

- 背景: ユーザー要求「ログを取りながら転送したい」。CSVはバッファリングされ、全項目が揃った行しか書かれず、プロファイル切替でヘッダーが崩れる。
- 決定: `QueryManagerImpl.updateHandlers` に自前の `DataUpdateHandler` を追加する。1応答＝1サンプル、換算済みの値、`ResponseImpl` の生成時刻を使う。
- 影響: CSV記録のオン・オフと無関係に、ロギング中は常にストリームが流れる。実ECUでの到着は未確認。
- 状態: 有効（実車確認待ち）。

## D-008 転送は「ローカルspool（正本）→ HTTP受信API → DB」、DBへの直接書き込みはしない（2026-10-12）

- 選択肢: (a) RomRaider/RRBridgeからJDBCでMariaDB/MySQLへ直接、(b) 受信APIを挟む。
- 決定: (b)。
- 理由: DBをインターネットに公開しない。通信断時の再送、重複排除（`session+seq`）、検証、認証（Bearerトークン）を受信側で一括管理できる。車載PCにDB資格情報を置かない。送り先は `sink.properties` の書き換えだけで切り替えられる。
- 結果: 受信停止中のデータを復旧後に全件再送できることを確認。
- 状態: 有効。

## D-009 転送形式はTSV（M/S/E行）、縦持ちで保存（2026-10-12）

- 理由: エージェントに外部ライブラリを入れない（JSONライブラリなし）。行単位でspoolに追記・途中から再送しやすい。プロファイルごとに列が変わるので縦持ち（`measurements(sample_id, param_id, value)`）。
- 状態: 有効（本番受信APIでも同形式を受ける想定。変更する場合は互換を考える）。

## D-010 検証用受信サーバーは X270 内の Java＋SQLite（2026-10-12）

- 理由: X270上にPythonがあるか不明、同梱のJava 8は確実に動く。sqlite-jdbcはWindows x86ネイティブを同梱しJava 8対応。
- 制約: RomRaiderのクラスに依存させない（`rrreceiver.jar` を分離。D-010の実装中にClassNotFoundを踏んだ）。
- 状態: 試作。本番はVPS上の別実装に置き換える。

## D-011 サーバーはVPS 1台を推奨（提案のみ、未決定）（2026-10-12）

- 提案: Ubuntu Server 24.04 LTS、Caddy（443のみ、自動TLS）、PostgreSQL（`statement_timeout` 付き読み取り専用ロール）、Python（受信API＋MCP同居）、Docker Compose。自宅PC＋Cloudflare Tunnelも同じ構成で可能。
- 前提調査: MCPはAI各社のクラウドから接続するため公開必須、VPNだけでは届かない。
- 状態: **ユーザー未決定**。

## D-012 音声UIの第一候補は Claude モバイル（調査段階）（2026-10-12）

- 背景: 引継ぎ資料では Android ChatGPT Live＋自作Remote MCP を想定。
- 調査結果: ChatGPTの自作MCPはWeb専用（モバイル非対応）でBusiness以上（Proは読み取りのみ）、音声モードで失敗する報告あり。Claudeはカスタムコネクタを個人プランで追加でき、音声モードは接続済みツールに対応（カスタムコネクタは未明記）。
- 次: ダミーMCPで Claude Android 音声から呼べるか実機検証。
- 状態: 調査段階。

## D-013 GitHub `zawa356/RomRaider_agent`（private）で管理、jarはコミットしない（2026-10-12）

- 決定: ソース、バッチ、プロファイル、ドキュメントを管理。`RomRaider.jar`・`sqlite-jdbc.jar` はライセンス・サイズの都合で `lib/` に手動配置（`.gitignore`）。実行時生成物と `sink.properties` も除外。
- 注意: RomRaiderはGPL v2。公開・配布時はライセンスを確認。
- 状態: 有効。

## D-014 読み取り系と操作系を分け、読み取り系はロックなし・GET可にする（2026-10-12、v0.4.0）

- 背景: v0.3.0 は `execute` 全体が `synchronized`、HTTPは単一スレッドで、`switch_profile` の待機（1.5秒以上）中は `status` も返らなかった。ドキュメントでは `stream_status` をGETで書いていたが実装はPOSTのみだった。
- 決定: `READ_ONLY = {status, stream_status, list_profiles}` はロックなし・GET可。操作系は `CONTROL_LOCK` で直列化。HTTPは4スレッド。
- 状態: 有効。実機確認済み。

## D-015 `logging_stop` はCSV記録中なら先に記録を止める（2026-10-12、v0.4.0）

- 理由: ロギングだけを止めると「Start file log」ボタンが押されたまま残り、表示と実態がずれる恐れがある。後段から見て「止めたら全部止まる」方が扱いやすい。
- 状態: 有効。実機で `logging:false, fileLogging:false` を確認。

## D-016 RomRaider非依存の自己試験を持つ（2026-10-12、v0.4.0）

- 決定: JUnit等を入れず、`SelfTest.java`（main）と `test.sh` で、GUIなしで動く範囲（転送・受信・重複排除・認証・掃除・検証）を確認する。RomRaider連携部分は実機の手動試験（`06_verification.md`）で補う。
- 理由: 依存を増やさない方針（D-009）と、RomRaider.jarをリポジトリに含めない方針（D-013）のため、GitHub ActionsでのCIは現状できない（RomRaider.jarを取得する手段が必要）。
- 状態: 有効。

## D-017 HTTPの認証は任意（既定オフ）（2026-10-12、v0.4.0）

- 理由: localhost限定だが、同じPCの他プロセスから操作できる。Supervisor導入時にトークンを設定する想定。既定オフにしたのは、現行の手動試験・ファイル経由の操作を変えないため。ファイル経由（inbox）は認証対象外（フォルダのアクセス権で守る）。
- 状態: 有効。

## D-018 受信API＋MCPサーバーは Python（mcp 2.x、Starlette/uvicorn）で1プロセスにまとめる（2026-10-12、試作）

- 理由: ユーザーが普段使う言語。MCP公式SDKがある。受信APIとMCPが同じDBを読むので、1プロセス（`MCPServer.custom_route` で `/ingest`・`/health` を同居）にすると構成が単純。
- 選択: Streamable HTTP を stateless・JSONレスポンスで運用（セッション管理不要、リバースプロキシ・再起動に強い）。
- 状態: 試作。D-011（VPS構成）が決まったら配置する。

## D-019 DB層は PostgreSQL と SQLite の両対応（2026-10-12）

- 理由: 本番はPostgreSQL（D-011）、ローカル試験・自宅PC・一時トンネルでの音声検証はSQLiteで手軽に動かしたい。Java版受信サーバーのDBもそのまま読める。
- 制約: SQLは両方で動く書き方に限定（`?`、`ON CONFLICT DO NOTHING`、標準偏差はPython側で計算）。
- 状態: 有効。

## D-020 MCPの書き込みは「運転者メモの追記」だけ（2026-10-12）

- 理由: AGENTS §2（車両・ロガーを操作しない）と、LLMの提案をそのまま適用しない方針。計測計画の提出を将来加える場合も「保存するだけ」にし、適用はX270のSupervisorが検証して行う。
- 状態: 有効。

## D-021 取得速度と通信状態を常時送る（2026-10-12、v0.5.0）

- 背景: 項目過多でK-Lineの周期が延び、全データが取れなくなる事故が過去にあった [ユーザー発言]。遠隔（MCP・サーバー）から異常に気付ける必要がある。
- 決定: RRBridgeが取得周波数・間隔p95・項目数・通信エラー数を `status` と10秒ごとの `health` イベントで送る。通信エラーはlog4jを横取りして数える（APIが無いため）。
- 状態: 有効。実機確認済み（ECU無応答時）。

## D-022 項目数の上限（既定20）を車載側で強制する（2026-10-12、v0.5.0）

- 理由: 設定の誤りを、LLMやサーバー側の判断に頼らず、最後の砦である車載側で止める。
- 既定20の根拠: 9/21の実車で19項目（2.60Hz）までは取得できていた。
- 限界: 本来は応答バイト数で決まる。4バイト拡張項目が多いと20項目未満でも遅くなりうる → 将来はLogger定義からバイト数を引いて制限する（09_open_issues）。
- 状態: 有効。

## D-023 切替後に取得速度が下限を割ったら直前のプロファイルへ自動復帰（2026-10-12、v0.5.0）

- 決定: 切替前に2Hz以上で取れていた場合だけ作動。切替後10秒（＋settleMs）の平均が2Hz未満なら戻す。戻し先は直前のプロファイル（不明なら `guard.fallbackProfile`）。
- 理由: 項目数の上限だけでは防げない（バイト数、ECU側の事情、配線）。「取れなくなったら取れていた状態へ戻す」ことでデータ全損を避ける。
- 状態: 有効。実車での作動確認は未実施。

## D-024 製品版スプールは既存PoCと分離したSQLite outboxにする（2026-10-13、Phase 2.1-A）

- 背景: PoCのTSV spoolと旧Receiverは「HTTP 2xxなら送信済み」となる条件が製品の全件DB commit後ACK契約と一致せず、プロファイル版・UUID・分割run・再送冪等の管理も不足する。
- 選択肢: PoC `Stream.java`を一括書換／製品版専用ファイルジャーナル新作／Java8 SQLite JDBCの製品版独立outbox。
- 決定: **既存RRBridgeのJava Agentを再利用する方針は維持しつつ、製品版spool層を別クラスへ追加する**。SQLite JDBCのWAL＋FULL同期、原本行の不変ID／ハッシュ／run連番、先行profile登録、バッチACK確認と未ACK保持を採用。
- 理由: PoCの実車操作・旧送信経路・安全ガードを壊さない。DBトランザクションで「全件送信済み／未送信」を原子的に更新でき、プロセス再起動時の未ACK再送に向く。長期の任意容量でスプールを無言削除する方式を採用しない。
- 影響: Java8 SQLite JDBCのネイティブライブラリとWindows x86同梱を実機で確認する必要がある。現段階はspool単体で、JSON構築・HTTP送受信・RRBridge統合はPhase 2.1-B/C。Agent上の安全判定は独立して必要。
- 検証区分: Windows／Linux CIのSQLite実DB単体試験。**X270実機、Zulu 8 32bit、実ECU、停車/走行中動作は未検証**。
- 状態: 有効（Phase 2.1-Aで限定実装）。

## D-025 製品v1の送信は厳密なACK検証とHTTPS限定を優先する（2026-10-13、Phase 2.1-B）

- 背景: PoCの「HTTP 2xxなら送信済み」ではServerの一部失敗・commit不明・別batchのレスポンスを取り違える恐れがある。Java 8 32bit環境で常駐LLMや追加のJSON/HTTP依存を増やしたくない。
- 決定: ProductSenderを既存Agentから独立した同期1ステップの送信器として実装し、HTTP 200/201の本文を検証して**定義版／バッチ全件保存確定のときのみSQLiteをACK**。外部接続はHTTPS、HTTPはloopbackのみ、3xxは追わない。
- 理由: 未ACKデータ保全を最優先し、障害時に本体から勝手に消さない。誤応答・不正形式は`NEEDS_REVIEW`、一時接続障害は`RETRY_LATER`へ分岐して将来の送信スケジューラが判断する。
- 影響: 現段階では接続先FastAPI/PostgreSQLとの実結合はまだ検証しておらず、ローカルHTTP契約モックだけがCI対象。ACK失敗時の不良レコード隔離やRetry-After制御は次工程。Java AgentのLogger処理・実車操作は変更しない。
- 状態: Phase 2.1-Bの限定実装。追加検証が必要。

## D-026 旧PoCのLogger callbackを保持し、TSV→製品v1を先行する（2026-10-13、Phase 2.1-C1）

- 背景: `Stream.handleDataUpdate`はLoggerスレッドで実行され、PoCはbounded queue→TSV writer→旧HTTP senderの経路。ここで同期SQLite/HTTPを直接呼ぶとECUロギング阻害の危険がある。また旧キューは飽和時に行が失われ得る。
- 決定: **先行Phaseでは旧TSVを読み取り専用にスキャン**し、不変ID・ProfileRevision・時刻・値・イベントを製品v1 SQLiteへ変換する独立クラスを採用。既存RRBridgeの本番動作・安全制御を変更しない。
- 利点: 全変換をWindows/Linux実SQLiteで再現でき、PoCの合成`selftest_stream`も`synthetic`として正しく区別できる。連携で未ACK原本を削除しない。
- 制約: 現時点の取込は**明示実行・先頭再走査**であり、長期運用の効率・複数同時writer・ファイル削除とqueue drop時のデータ欠損問題を解消していない。安全な常駐化・障害通知は次工程。
- 実Server E2E: Agent/Server両方Privateのため、別リポジトリへの読取専用認証情報が設定されるまでCIを`NOT RUN`表示。モックを実Server成功と扱わない。
- 状態: C1の限定実装。実車での有効化は未承認／未実行。

## D-027 永続offsetは各完結TSV行をSQLiteへ保存した後にのみ進める（2026-10-13、Phase 2.1-D1）

- 背景：Phase 2.1-C1の全行再走査は長距離走行で非効率。単に読取り位置を保存すると「offsetだけ進みレコード未保存」の電源断時に欠落する。
- 決定：`IncrementalTsvBridge`は`RandomAccessFile.seek(offset)`から開始。完成LF行を検証し原本`v1_records`/`v1_profiles`へcommitした**後に**`v1_import_cursors`へCASでoffset・メタデータsnapshot・境界fingerprintを保存。読取り元TSVは一切変更しない。
- トレードオフ：原本commit後のクラッシュは同じ行を再送するが、原本ID不変・SQLite重複抑制で冪等になる。複数独立DB commitとcursor commitは原子的単一トランザクションではないが、**順序と再処理**で欠落側を防ぐ。
- 安全上の制限：元ファイル短縮・OS fileKey差・既読境界fingerprint異常では自動resetせず停止。fileKeyをOSが提供しない環境、追記以外のファイル書き換え・queue欠落の検知は別検証が必要。
- スコープ：ProductBridgeOnceの明示操作に限定。Server ACKは別台帳、常駐スケジューラ・Loggerコールバック・ECU操作の切替は未実装。
- 証跡：[詳細](product_v1_incremental_import.md)、[Phase台帳](product_v1_implementation_progress.md)。

## D-028 製品v1の再送は永続backoff・operator reviewを維持しPoC外の別JVMで実行する（2026-10-13、Phase 2.1-D2b-1）

- 背景：HTTPが戻らない際の連続再送はネットワークとServerを圧迫する。認証エラー・契約衝突等は再送しても原本が受理されず、Logger JVMの計測を妨げてはならない。
- 決定：`v1_delivery_state`にretry回数・次回予定・要調査を永続化し、`ProductDeliveryCoordinator`による2秒開始・最大120秒の指数遅延を実装。`NEEDS_REVIEW`は永続停止し、明示的なoperator resetなしでは再送しない。
- 実行：`ProductBridgeWorker --once|--foreground`の別Javaプロセスで旧TSVをread-onlyに増分走査。ファイル消失・symlink・上限超はfail closed。同一DBの製品worker間はlock fileで制御。PoC Loggerの`Stream.java`と車両安全操作は変更しない。
- 制約：旧Queueの満杯drop、`writerLoop`のdequeue後write失敗による消失、旧ACK基準での14日cleanupは未解決。Workerが初めて見る前に削除されたTSVは検知不能。Retry-After、jitter、近時・過去の公平送信、409/422隔離も別フェーズ。
- 検証：Windows/Linux実SQLite／合成HTTP／独立Javaプロセス。Zulu8 32bit実車・電源断・実Serverに対する**本Worker** E2Eは未実行。
- 状態：安全側の限定実装。Windowsサービスへの自動登録・実車有効化なし。

## D-029 元TSVの自動削除を止めwriterはfsyncと失敗再試行を必須とする（2026-10-13、D2b-2a）

- 背景：旧`Stream.java`はqueueから最大5001行をdequeueした後にファイル書込に失敗すると、その原文を失う。旧`.sent`は製品Server v1へのDB commit ACKではなく、元TSVを移行前に消す危険がある。bounded queueが満杯の行は復元できない。
- 決定：1）writerの未書込バッチを成功fsyncまで保持・再試行。2）readerはwriterとI/Oロックを共有し、fsync前・rollback中の読み出しを避ける。3）TSV尾のLF欠損には追記せず手動修復。4）`cleanupSpool`の旧ACKによる自動削除は**無条件停止**。5）queue超過は可視化イベント`queue_overflow lost_lines`とstatus値で知らせる。
- 理由：旧PoCサンプル生成と車載操作への影響を最小化しつつ、製品v1のSQLite取込前に原本が消える事故を防ぐ。原本破棄による不可逆欠損より、増加するディスク使用量と要調査停止を安全側に選ぶ。
- 制約：キューからファイルfsyncまでの電源断、上限超過した瞬間の行の復元はできない。異常tailではWriterが再試行停止し容量逼迫が起こり得る。旧`.sent`の到達だけで製品の全件保存確認としない。
- 試験：RomRaider.jar不要のStubを用いて実`Stream.java`を`--release 8`でコンパイルし、Windows/Linux上で書込失敗・復帰・尾破損・drop marker・削除無効化を検証。K-Line/実車・電源断は未検証。
- 残：高負荷時のqueue dropを根絶する構造、fairness、長時間運用・ディスク制限・X270実機は後続Phaseで確認。
- 状態：D2b-2aとして採用。


## D-030 旧PoC TSV writerのI/O回数を減らし、バッチ単位の追記失敗はrollbackする（2026-10-13、D2b-2c-2）

- 背景: `Stream.writerLoop()`は最大約5,000行をメモリ上で保持し、その各行について本文とLFを別々に`RandomAccessFile.write()`していた。通常のI/O停滞が長引けば有限queueが飽和してサンプル欠損に至る。
- 選択肢: （A）callbackから直接ディスクへ同期書き込み（採らない、RomRaider/ECUに遅延を持ち込む）、（B）巨大なメモリqueueへ変更（採らない、32bit JVM圧迫と電源断前の未保存増大）、（C）writer側だけで64KiB単位に書込み集約（今回採用）、（D）別永続journalに投入する大幅な変更（後続設計として検討）。
- 決定: `Stream.writeRowsBatched()`を固定64KiBバッファで実装し、元のUTF-8/LF形式を保持。ファイル末尾を検証してから旧長を記録し、書込完了後はfsync。書込途中のIOException／RuntimeExceptionに対しては旧長への短縮と再fsyncを試みる。成功までは未書込行を破棄しない。
- 理由: 通信契約・安全ガード・非ブロッキングLogger callbackを一切変更せず、一般的な小行の書込呼出回数を大幅に減らせる。途中故障後に不完全な行を製品取り込み対象にしないことを最優先とする。
- 安全上の限界: fsync自体やrollbackが失敗し得る。電源断時の非永続キューは救えず、queue上限時はS行を失う。時間ベンチマークでの帯域保証、X270 Zulu8 32bitや実ECUの保証はない。**D2b-2cの根本的な無欠損保証は未達**。
- 合成検証: Linux/Windowsの`Stream.java`スタブ試験で複数チャンク途中の強制IOExceptionと復旧、RuntimeException rollback、UTF-8／長大行、旧writer回帰を確認。[GitHub Actions #38369405259](https://github.com/zawa356/RomRaider_agent/actions/runs/38369405259)。
- 状態: D2b-2c-2の限定対策として採用。実車・本番に自動展開しない。


## D-031 非ブロッキング取得と有限メモリqueueの欠損限界を明確化し、writer状態を可視化する（2026-10-13、D2b-2c-3）

- 背景：RomRaiderの`handleDataUpdate`はECU通信への悪影響を避けるためディスクI/O・待機・例外送出を行わない。writer故障中にwriter側`pending`とmemory queueの行が溜まり、どれがfsync済みか外部からわかりづらかった。
- 評価：取得callbackが非ブロッキング、RAM量が有限、writerが無制限に停止し得るなら、いつか必ずキューが飽和する。データ無欠損・無期限I/O停止耐性は、この条件のままでは論理的に保証できない。
- 決定：今回の小PhaseはLogger callbackの処理・キュー容量・転送形式を変えず、writer側のみの障害計数と状態可視化を追加し、故障時に`pending`を解放しないことを維持する。新しいstream statusフィールドは追加のみとし既存の読取互換を維持する。
- 合成検証：書込先を通常ファイルにした安全な擬似I/O不能を使い、S行195904件、追加10件の欠損計数、M/E行予約、故障復旧後の厳密な行数と欠損マーカーを確認。VMのホスト全体のディスク満杯、実ECU試験は行わない。
- 将来：writerの無限停止・異常終了に耐えるなら、取得を安全に一時停止する独立Supervisorの明示的な運用判断、または別の永続キューと非ブロッキング取込限界の仕様化が必要。callbackへの同期fsync追加や無制限メモリ増量を既定の回避策にしない。
- 状態：D2b-2c-3の限定対策。完全なdurable ingress・X270安全検証は未完了。


## D-032 同期永続journalは別Workerだけで検証しLogger callbackへの直結を禁止する（2026-10-13、D2b-2c-4a）

- 背景：旧TSV writerのqueue+pendingはJVM異常終了前の未fsync原本を救えない。非ブロッキングcallbackにfsyncを追加するとECU通信と計測周期を損なうため、機能の実証と本番接続を分離する。
- 選択肢：A. callbackに`FileDescriptor.sync`を直結（不採用）、B. 全データを巨大メモリキューで保持（不採用）、C. 前段の独立journalを別Workerで試験（この小Phaseのみ採用）、D. 既存製品SQLite outboxを前段まで移動（次Phaseで比較）。
- 決定：独立`IngressJournalPrototype`とそのLinux/Windows合成CIを追加し、追記順・原本保持・CRC32整合性・quota fail-closed・異常終了後の再読込を確認。元TSVと製品v1永続台帳へは繋がない。停止・破損時に未受領レコードを勝手に消したり修復したりしない。
- 制約：1行最大128KiB、ファイル最大8MiBは実証用。CRC32は偶発破損検知用で耐改ざん性を持たない。JVM `Runtime.halt`は物理電源断・フラッシュキャッシュの再現ではなく、ゼロ欠損を保証しない。多数の小レコードを先頭から読み込むため容量を無限に増やせない。
- 次の判定条件：専用writerへの安全な受渡し・永続ACK/replay契約・高負荷／容量逼迫・Windows32bitへの影響を設計してから、X270での安全検証を別途行う。
- 状態：**PoCのみ採用。実運用での有効化・自動起動は禁止。**


## D-033 揮発性受理・永続ジャーナル・製品outboxの成功境界を分離する（2026-10-13、D2b-2c-4b-1）

- 背景：`DataUpdateHandler`からfsyncやSQLiteへ直接書くとRomRaider Loggerを妨げる。前段journalのPoCができても、実際に永続化される前に`offer()`が成功する期間が残る。
- 選択肢：A.callbackから同期fsync（安全上不採用）、B.大きなRAM queueを「永続」と呼ぶ（不採用）、C.揮発性受理と別worker fsyncを明確に分離（今回の限定PoC）、D.製品SQLiteへ直接接続（後続で原本形式・重複を設計してから）。
- 決定：入口`offer`は容量・状態に応じた受理可否のみを返す。専用workerは`journal.append`のfsync成功で初めて永続確定と扱う。失敗したin-flight行は保持しfail-closed、quotaは自動解除しない。
- 重複抑止：journal本体の連番と、外部で**永続管理が必須**のsourceUUIDから安定IDを派生させる。下流はこのID＋不変原文でdurable idempotent commitし、カーソルをその後に進める契約とする。4b-1のMapは模擬sinkであり、`DurableSpool`に接続していない。
- 限界：callbackがブロックしない有限メモリ方式のため、I/Oが停止し続ければ拒否・欠損し得る。受理後fsync前の電源断も同様。同期プロセスを既存Loggerへ自動組込することは禁止。
- 検証・記録：[4b-1の設計と試験](phase_2_1_d2b_2c_4b_1_handoff_contract.md)。PoCであるため製品v1 API/旧TSVの変更なし。
- 次のゲート：journal IDのファイル内永続化、重複排除とsource変換の実SQLite試験、checkpoint更新順序、X270/32bitの検証、容量逼迫時のSupervisor通知。
- 状態：**独立PoCのみ。実車・製品版には未接続、D2b-2c全体は継続中。**


## D-034 ジャーナル前段の不変source IDと実SQLite receipt/cursorを製品v1から分離する（2026-10-13、D2b-2c-4b-2a）

- 背景：4b-1の`sourceUuid`は外部入力で永続性未確定、重複回避はテスト専用Mapだけだった。元journalと後段SQLiteのどちらが成功したか区別する必要がある。
- 決定：空journalに対してのみ一度のUUID sidecarを生成しfsync。既に原本があるのにIDが欠落・破損した際は自動再採番しない。受領SQLiteの`proto_ingress_rows`はjournalのsource UUID/sequence/原文SHA-256を保持し、`proto_ingress_cursor`と単一transactionでcommitする。
- 選択理由：原本・受領cursorが別transactionだと先送りcursorによるデータ欠落を招く。初回は試作用テーブルとして製品`v1_records`と責任を分離し、既存のプロファイル・観測ID・ACKを一切変更しない。
- フェイルクローズ：source IDの欠落、CRC不一致、変更済みの受領prefix、journalの縮小、SQLite commit失敗では保存済み原本と台帳を残す。
- 検証：CloudPC実SQLite新規22チェック、既存journal75チェックおよび製品v1回帰。commit直前例外／別JVMのcommit直後halt・復旧、元原本改変を確認。
- 制約：sidecarとjournalの二ファイル整合・ディレクトリ永続化はOSの物理電源断では未証明、Windows32bit・ECU接続なし。SQLite受領receiptを製品レコード登録／Server ACK済みと誤認しない。
- 後続：ジャーナルの固有IDを原本に埋める設計とファイル回転、既存TSVと二重投入を防ぐ製品v1変換契約、キュー容量上限時の安全な運用停止判断。
- 状態：独立PoCとして試験完了。実車/本番への接続は引き続き禁止。


## D-035 Journal→製品v1取込は既存M/S/E変換を共有し、取込元レーンをセッション単位で固定する（2026-10-13、D2b-2c-4b-2b）

- 背景：独立receipt PoCはSQLiteに原文を保存するが、実運用の`v1_records`・プロフィール・厳密ACKへは繋がっていなかった。別実装のM/S/Eパーサーを作るとTSVと異なるIDや変換ルールが発生する。
- 決定：`IncrementalTsvBridge.importJournal()`を明示呼び出しで設け、既存の`apply()`/ID生成をそのまま使う。製品v1のprofile/recordを各行の状態snapshot付きの新journalカーソルより先に永続保存する。データとcursorは別transactionだが不変ID・CAS・再入試験で回復する。
- 二重投入：`v1_source_claims`をSQLiteで永続化し、device/vehicle/sessionごとに旧TSVのsource pathまたはjournal UUIDのどちらか1経路だけ許す。既存の増分・全件TSV両方を対象とし、旧run recordや旧cursorがあり出所不明ならjournalへの切替は拒否する。自動移行・破壊的なclaim解除は提供しない。
- 改変・障害：journal原本のCRC・連番・UTF8と取込済みprefix SHAチェーンを検査。移設・短縮・取り込んだ行の改変時はSQLiteの原本/ACK/カウンターを勝手に変更せずfail closed。
- 検証：CloudPC（Java17 `--release 8`）で新規JournalProductBridgeTest35件、製品/receipt284件、journal/handoff75件PASS。異常終了JVMを使って「record保存後cursor更新前」の境界を実行し、再起動時の重複なしを確認。
- **制限**：現時点では実行Workerに自動接続しない。実ECU制御・OS電源断・32bit/JVM圧力・本番Server E2Eを試験していない。entryジャーナルの元queueが耐電源断になったわけではなく、無欠損・exactly-onceは保証しない。
- 次のゲート：隔離実Server/Postgresで最新Agentのprofile/record/ACK通過試験、CPU/ディスク容量上限、ファイルローテーション・メンテナンス手順、運用者レビューを設計。安全検証終了まで本番配備しない。
- 状態：限定的な**製品v1の明示的取込経路**として合成試験完了。Runtime経路の切替は保留。


## D-036 最新AgentのServer ACK検証はCloudPCの使い捨てPostgreSQLで実施し運用経路は切り替えない（2026-10-13、D2b-2c-4b-2c）

- 背景：4b-2bまで製品v1 SQLiteへのjournal保存は実証済みだが、最新Agentで実FastAPI/PostgreSQLへ到達するか未検証だった。旧Server CIは古いAgent sourceをpinしているため代替にはならない。
- 決定：`JournalServerE2E`を明示呼び出しで増設し、従来`ProductServerE2E`と同じServer・専用DBで実HTTP登録、完全なprofile/バッチACK、duplicate-only replay、実DBレコード件数を検証。製品本体の既存Sender/HTTP契約は変更しない。
- 安全制約：使い捨てDBのみに限定し、スクリプト開始時に`ROMLOGGER_E2E_DISPOSABLE=1`、127.0.0.1/localhost、`romlogger_*_e2e`のDB名、URLクエリなしを確認してからmigrationを実行。サーバー認証情報をログに表示せず、Caddy/MCP/本番DBに向けない。
- 検証：CloudPC Postgres16の合成E2E（TSV3行＋journal3行の本物の観測記録/2profile/Server ACK/replay）PASS、誤接続6件拒否PASS、SQLite/journal従来359チェックPASS。
- GitHub Actions：cross-private-repoのread-only secretがない環境のE2Eジョブがsuccessでも実Server部分はSKIPPED。E2E実施済みの表示は実ステップの成功確認がある時に限る。
- 未解決：自動Worker/Loggerへの配線は承認なしに進めない。電源断・容量逼迫・実車環境をこの合成E2Eと混同しない。
- 状態：**隔離CloudPCでの最新AgentリアルServer E2EをPASS。製品ランタイムへの導入は保留。**


## D-037 Journal Workerは閉じた原本の明示一回取込に限定し、障害時の持続reviewを優先する（2026-10-13、D2b-2c-4b-2d-1）

- 背景：4b-2cまで`importJournal`とSenderの実Server ACKは証明できたが、運用Workerに選択的に入る入口と、原本故障時に送信のみ継続してしまうリスクへの対策がなかった。現在のjournal readerは`RandomAccessFile("rw")`から排他的FileLockを取る仕様でwriterとの並行読取には使えない。
- 選択：A.既定Workerをjournalへ自動切替（拒否）、B.別daemonでjournalを継続走査（readerロック競合のため保留）、C.明示opt-in付きoffline`--journal-once`を追加し失敗時永久review（採用）。
- 決定：`ROMLOGGER_JOURNAL_WORKER_ENABLE=1`の明示設定がある`--journal-once`のみ新経路を有効化。writerが閉じたjournal専用ディレクトリだけ扱い、原本とidentityの継続性・上限・移設・UUID衝突を事前検査。従来TSV CLIはそのまま。
- 故障時：`v1_ingress_review_state`に定型原因を初回のみ保存し、以後のWorker全モードのHTTP送信を停止する。元原本・未ACK・既存ACKは残し、**クリア操作・自動再試行は実装しない**。本Phaseで解除権限を与えない。
- 観測：`ProductIngressStatus`は既存SQLiteをread-onlyで読み、0=no latch/5=review/6=unknownを返すだけ。Supervisor連携は後続とし、no latchを「安全に走行可能」と解釈しない。
- 検証：CloudPC合成419チェックPASS、隔離Postgres16への`--journal-once`別JVM本番型経路で新しい合成観測1件profile・batch ACK、fresh SQLite重複再送PASS。旧経路6件と合わせ実DB合成7件/3profiles/重複なし。Caddy/MCP/実車へ非接続。
- 次のゲート：reader/writer同時利用時のread-only方式、source回転と8MiB上限、監視通知・レビュー解除手順、Windows32bit/実物理電源断とdisk-fullの検証。
- 状態：**offline明示Worker限定で検証済み。常駐運用や実車への配備は未許可。**


## D-038 live Journalの完全フレーム観測をfsync済み確定受領と混同しない（2026-10-13、D2b-2c-4b-2d-2a）

- 背景：旧readerはRandomAccessFile(rw)+排他FileLockでwriterと並行読取できない。writerはheader/bodyのあとにfsyncするため、CRC妥当フレームが見えただけでは永続確定の根拠にならない。
- 選択：排他ロックを外して製品Workerへ直結（却下）、FileChannel.READのbounded監査プローブ（採用）、耐電源断watermarkで確定したフレームだけ製品へ移送（後続）。
- 実装：最大8MiB、行128KiB、magic/CRC/連番/UTF8とファイル属性変更を検証。COMPLETE/CHANGING/INCOMPLETE_TAILを区別。修復・破棄・自動ACKはしない。
- 書込禁止：writer用JournalIdentityPrototype.openのUUID発行がreaderまで及ばないようreadExistingを追加してreaderを切替。既存ID不在は必ず拒否。
- 合成検証：CloudPC Linux Java8 443チェックPASS、160回fsync writerと最大320回のprobe同時検査を5反復PASS。初回Windows PR CI #38400570091でmandatory FileLockによるライブreader拒否を確認。これは未解決blockerであり、Win32bit/物理電源断も未実施。
- 禁止：プローブから製品v1 cursor/ACKは進めない。--journal-foregroundは引き続き禁止。watermark、回転、容量監視、権限付き復旧手順を次Phaseで定義。
- 状態：**read-onlyライブ診断だけPoC合成PASS。ライブ製品化・運用適用は保留。**

## D-039 Lock分離は旧whole-file lockと衝突するquota外guardを付けた別JVM PoCのみ（2026-10-14）

- 純粋な.writer.lock専有は旧writerのwhole-file lockと交差せず、両起動順で二重ownershipが成立する反例。移行案として却下。
- 候補: 独立SplitLockJournalPrototypeでwriter.lockを取得した後、journalの8MiB位置に1byte排他guardを取る。データは8MiB以下、readerはデータ範囲だけREAD。旧writer whole-file lockはguardに必ず重なる。ロック自体はEOFを伸ばさない。
- FAIL→修正: Linux POSIXロックは同一inodeの別descriptor closeで解放される。constructor内LiveJournalReadProbeを使わず、所有RandomAccessFileでCRC/sequence/UTF8を検証する。reader/競合writerは別JVM限定。同一JVMの別handle操作・lockファイル置換・削除は未対応/禁止。
- WSL Linux OpenJDK17.0.20.1 --release 8: 新matrix39＋既存99=138チェックPASS。Windows/VPS新規試験は確認待ち。
- 状態: 合成PoC候補。既存IngressJournalPrototype、製品Scanner、Worker、Logger、正式IFは変更なし。容量変更・運用導入・同一JVM統合は承認待ち。

### 2026-10-13 22:47 JST — 4b-2d-2b-1 実検証結果 / PASS (PoCのみ)

- PR https://github.com/zawa356/RomRaider_agent/pull/27; code 36bd679 / docs a1a6fa9。
- WSL/VPS1 codex Linux OpenJDK17.0.20.1 --release8: journal138 + SQLite/receipt344 = 482チェックPASS。VPSにrgなし→grepへ切替、journal試験自体は最初から成功。
- GitHub PR最新a1a6fa9: journal run38407692420のWindows/Linux compile/crash/SQLite実ステップsuccess、spool run38407690527の両OS実ステップsuccess。旧writerライブ読取BLOCKEDは継続、新PoCの別JVMライブREAD成功。実Server E2Eは今回NOT RUN。
- 日時訂正: 前節の2026-10-14表記は記入誤り。実作業は2026-10-13 22:34〜22:49 JST (13:34〜13:49 UTC)。失敗内容・試験実績に変更なし。
- Next: 最新PR head再CI確認→main mergeとmain CI確認→4b-2d-2b-2 watermark独立PoC。製品導入承認待ち。

## D-040 watermarkは原本fsync後のprefixを独立markerで表し、dir永続化保証と分離する（2026-10-13）

- 独立RLC1 104bytesを候補: source UUID、sequence、offset、fileKey SHA256、原本prefix SHA256、CRC32。既存RLJ1/identityを変更しない。fileKeyを得られないFSはfail closed。置換原本は内容が同じでも拒否。
- writerの所有RandomAccessFileでCRC/seq/UTF8を確認しsync成功→tmp CREATE_NEW/force→ATOMIC_MOVE(REPLACE_EXISTING)→directory force。非atomic fallback、tmp自動削除、marker自動補修はしない。Windows dir force未確認時はUNCONFIRMEDとして電源断保証をしない。
- readerは別JVM READ専用でwatermark範囲まで厳密検査。watermark先行/破損/UUID違い/連番退行/原本切詰め/置換/改変/temp残存を拒否。完全だが未checkpointのtailは除外。読取途中marker変更はretry/reviewとして拒否。
- previouslyObservedはメモリ内だけ。新readerで古いvalid watermarkを読むと安全な古いprefixへ戻る場合があり、永続highwater/SQLiteとの原子的統合は本Phase未実装。CRC/hashは認証ではなく管理者による同時原本+marker改変の耐改ざん保証なし。
- WSL OpenJDK17 --release8の新56＋既存138=194チェックPASS。JVM halt3段階、擬似I/O/quota故障、部分marker、live別JVM READを検証。Windows/VPSはこれから。実ディスク満杯/物理電源断/実ECUはNOT RUN。
- 状態: 独立PoCのみ。製品cursor/ACK/Worker非接続。実運用採用と原本セットrotationは別判断。

### D-040補記 — Windows fileKey非対応をBLOCKEDとする（2026-10-13 23:05 JST）

- Windows Actions journal CI38409471808/38409589169でfileKey nullによる初回FAIL。OpenJDK17 WindowsFileAttributes.fileKey()は常にnullを返す公式実装を確認。内部ensureAccurateMetadata設定でもpublic APIのkeyは得られない。設定案は推測を訂正し未採用。
- SplitLockのlock-only機能はfileKey任意で前Phase互換を維持、watermarkはnon-null必須を維持。Windowsで3件のfail-closed/原本保全を試験、watermark56シナリオはBLOCKED/NOT RUNと表示する。Windows/Linux atomic stagingとdirectory forceは別の合成試験を追加。
- 2b-3結合は両OSでwatermark成立のゲート未達としてBLOCKED。代替候補: 公開APIのhardlink anchor+isSameFile、Windows native file identity、原本内UUIDを含む新形式。いずれもlock/互換/dir耐久の追加検証と運用採用承認が必要。夜間は配線せずこの候補を保持。

### D-040最終区切り — 2026-10-14

- 採用範囲は独立PoC/故障fixtureのみ。Linux full watermark56+storage primitive3 PASS、Windows watermark発行/読取拒否3+storage primitive3 PASS (full56 BLOCKED)。Windows directory force UNCONFIRMED。CI successを製品ライブ機能の成立と解釈しない。
- 2b-3は承認済み順序の両OS成立ゲートが未達でBLOCKED。別方式の識別とsession/source claimのrotation契約をレビューするまでWorker/SQLiteへの配線を実施しない。

## D-041 — Windows原本識別3案の比較（2026-10-14、独立試験）

- A: retained hardlinkへの同内容置換を区別するが、whole-set copyは関係を再構築できる。同JVM別handle closeはLinux guardを失う。単独採用しない。
- B: native NTFS volume/file indexをテスト専用JNA5.14.0で比較。次実験の推奨候補だが所有handle binding、永続expected identity、index再利用、dir永続化、x86実行が必要。製品採用ではない。
- C: in-band UUID/headerもコピーでき、物理原本同一性にならない。旧RLJ1互換破壊を伴う変更は実施しない。
- [比較詳細・一次資料・移行ゲート](phase_4b_2d_2b_3a_identity_comparison.md)。3b/3c/3dはBLOCKED。Windows/Linux実結果はworklogへ追記し、未実施をPASSにしない。

## D-042 — 原本IDと読取bytesは同一native handleで診断する（2026-10-14）

- 3bゲート未達の代替3a-compatで、NTFS native READの期待ID/SHA/容量を検証。pathnameの属性と別handleのbytesを混ぜない。
- データはNOT_COMMITTED診断のみ。fsync/DB/ACKの証明や製品path条件解除に使わない。writer側binding、dir永続化、expected ledgerは未成立。
- 実Zulu8/x86 Actionsを追加しdata-model32をassert。Java17 --release8だけでは32bit成功と報告しない。X270/RomRaider同JVMは別のNOT RUN。
- [設計・正確な実行手順](phase_4b_2d_2b_3a_compatibility.md)、実試験結果はworklogに追記。

### D-042試験結果（2026-10-14）

- Actual Zulu8 1.8.0_504 x86/Windows2025で同じnative handleのID/bytes診断成功。Java17/x64も成功。原本のコピー・in-place改変・旧writer lockを安全拒否。
- writer側fsync handleのbindingは残るため、次は独立test-only writer/FlushFileBuffers/identity試験。dir永続化とpersistent highwaterはまだ別ゲート。

## D-043 — native writerのID/read/write/flushは同じhandleで独立検証（2026-10-14）

- 3a-writer-bindingのtest-only native fixtureで既存RLJ1のowned prefixとnative ID、WriteFile、FlushFileBuffersを同一handleへ結合。Java coordination＋native quota外guardでold/split/nativeの排他を比較。
- 途中失敗/quotaでterminal latch、曖昧原本はretain。SHARE_DELETEなしのrename拒否はfixture限定で、製品共有モードや旧writer移行は決定しない。
- メモリProofのみ。marker/DB/ACKへ渡さず、RLC1を再解釈しない。dir durability、永続expected identity/highwater、marker version/移行、volume/再起動ゲートは残る。
- [設計・故障試験](phase_4b_2d_2b_3a_writer_binding.md)、実x64/x86結果はworklogへ保存。

### D-043試験結果（2026-10-14）

- 実Java17/x64とZulu8 1.8.0_504/x86でnative write/FlushFileBuffers/identityの同一handle結合に成功。新旧排他・原本保全・失敗latchもfixtureで成功。
- テスト候補の基礎のみ。dirUNKNOWN/marker version/expected ledgerが残るため、このProofを製品cursor/ACKへ渡さない。次Phase3bで新版markerを設計する際もdirectory sync未確認をBLOCKED/UNKNOWNと保持する。

## D-044 — flush後のnew frameはWriteFileへ渡したexact bytesと照合（2026-10-14）

- 最終レビューでTEST候補に不足を発見。old prefix SHA+new CRC/sequenceだけでは、flush直後にnew payloadとCRCを同時改変しても新Proofを返してしまう。新負試験でWindows17/x64実再現（RED213fd22、CI38499434376）。x86 REDはx64 baseline失敗でNOT RUN。
- 同じnative handleで読み戻したnew frameを、実際にWriteFileへ渡したframe byte[]と完全照合する。CRC/hashの再計算を物理fsyncの代替にしない。改変はProofなし/terminal latch/原本保全。
- c72ad0dで修正、x64/x86 GREENは実CI確認後worklogへ保存。private TEST writerの改修のみ、製品/marker/DB/ACKは非変更。D-043の初回合格は当時の44/45fixtureの範囲であり、この負試験の合格を含まない。

### D-044 actual GREEN確認

- c72ad0dのWindows17/x64とactualZulu8 1.8.0_504/x86で改変拒否/保全/latch成功。writer46/47。flush後に観測したvalid CRCを、実際のWriteFile bytesの代替にしない。製品・marker・ledgerへの非配線は維持。

## D-045 — Phase 3bはRLC2（新version・identity tag）の独立PoCとし、RLC1を読み替えない（2026-10-18）

- 新marker `.commit2`（RLC2, 144 bytes）。identityはtag付き（POSIX dev/inode、Windows NTFS volume serial/file index）で、tag違いは比較不能。原本UUID・generation・sequence・offset・prefix SHA-256を保持。SHA-256は整合性検査で認証ではない。
- RLC1 `.commit`が存在すればRLC2発行を拒否しbytesを保持。RLC2位置のRLC1 bytesも拒否。移行契約は別承認。
- 確定手順はwriter書込記録との完全一致→所有handle sync→同handle再検証→tmp CREATE_NEW/force→原子的置換（Windowsは`MoveFileExW` WRITE_THROUGH）→directory判定。Windows directory durabilityは常にUNKNOWN、`powerLossDurable()`は常にfalse。
- POSIX identityはpath-sampled（Javaはfstat不可）でWindowsのhandle bindingより弱い。この差異を試験に`DIFFERENCE`として残す。
- 反例: previousなしの新readerは古い安全markerを受理、digest再計算の偽造markerを受理。永続highwater/expected identityをSQLite receiptと同一transactionで持つ3c設計が前提。製品writer/Worker/DB/ACKへ配線しない。
- [設計・試験](phase_3b_rlc2_watermark.md)、実行結果は[Claude worklog](handoff/claude_phase3b_worklog.md)。

## D-046 — 3c-1はRLC2の永続expected identity/highwaterを原文receiptと同一SQLite transactionで持つ独立PoC（2026-10-18）

- 3bの反例（previousなしのstale受理、digest再計算の偽造コピー受理）を、claim済みsourceに対しては永続ledgerで拒否する。初回claimはTOFUで、初回前の置換は検出できない。
- `BEGIN IMMEDIATE`の1トランザクションでlane排他、UUID/path claim、highwaterを`previouslyObserved`とした読取、expected identity照合、既存receipt完全照合、新receipt、highwater更新を行う。失敗時は全rollback。
- receiptは常にACK対象外。Windows directory durabilityはUNKNOWNのまま。製品`v1_*`表、Worker、Server IFへは配線しない。ledger DB自体の改竄耐性は主張しない。
- [設計・試験](phase_3c1_rlc2_ledger.md)。

## D-047 — 3d-1はRLC2 segmentの独立seal/RLM1 rotation manifest PoC（2026-10-18）

- 1 session=1 directory、segment-0001〜0016、`rotation.manifest`（RLM1）。sealedはRLC2が全長を確定したsegmentのみ（空segmentはseal不可）。sealed entryは不変で、以後のmanifestはbyte等価なprefixを保持する。
- 16本で明示停止し、削除・retentionはしない。orphan・tmp残存・CREATE_NEW競合はreview待ちで停止し、自動採用・修復はしない。
- session ingestはmanifest順で、前segmentがsealed最終seq/offsetまでledgerに記録されるまで次segmentへ進まない。
- Windows directory durabilityはUNKNOWNのまま。製品writer/Worker/v1表/ACKへ配線しない。[設計・試験](phase_3d1_rotation_manifest.md)。

## D-048 — Read-only Supervisor diagnostics require a closed SQLite snapshot (2026-10-18)

- SQLite mode=ro alone was RED: it created WAL/SHM files. Diagnostic opens existing checkpointed snapshots with mode=ro plus immutable=1 and query_only, rejecting WAL/SHM/journal before and after inspection. Caller must externally quiesce writers; no checkpoint/repair is attempted.
- Fixed codes and content-free causes distinguish identity/claim/receipt/manifest/highwater/seal/quota failures. ACK is always ineligible; directory durability UNKNOWN. Stable filesystem+DB snapshot cannot be certified by this inspector.
- Windows native opener remains test-only JNA; no dependency added to rrbridge.jar. See acceleration_diagnostics.md and acceleration worklog.


## D-049 — Reuse v1 conversion through an isolated synthetic projection sink (2026-10-18 11:54:12 +09:00)

- Extract only the output target from existing IncrementalTsvBridge.apply; existing constructors delegate to the same DurableSpool calls. RLC2 adapter buffers projected profiles/records without any outbox/transport.
- Manifest order carries metadata; payload session and monotonic run sample sequence must continue across physical sources. Persisted ledger must exactly cover every projected marker; original source receipts remain separate from v1 record IDs.
- Current v1_source_claims permits a single source key per payload session, so direct multi-source product integration stays BLOCKED. Preview schema will own a manifest-session plus source children; no silent schema/ACK migration. Projection is explicitly enabled, synthetic only. See rlc2_product_projection.md.


## D-050 — Quarantine RLC2 product projections in dedicated atomic preview storage (2026-10-18 12:10:37 +09:00)

- Production v1 single-source claims/cursors remain unchanged. Preview owns a manifest-session and child source cursors, keeping original receipts and shared v1 JSON in one SQLite transaction. Existing DB application/schema ownership is verified before writing; absent opt-in creates nothing.
- Replay compares persisted immutable bytes/counts/history and fails on deletion/tampering instead of repairing. SQL forbids ACK eligibility, and no sender uses the preview schema. Input quiescence remains external; UNKNOWN Windows directory durability is not upgraded.
- Isolated PostgreSQL uses only a new randomly named test schema after strict destination guards; local actual Server models validate JSON without endpoint/ACK. See rlc2_preview_e2e.md.


## D-051 — Preview output crash recovery is distinct from immutable input inspection (2026-10-18 12:22:40 +09:00)

- Separate-JVM RED778757d found own rollback-journal refusal after CLAIMS_WRITTEN halt. Keep input ledger immutable/quiescent; do not loosen IngressDiagnostics or automatically checkpoint/repair originals.
- For a dedicated output only, verify regular paths, SQLite header magic/RLP1 app ID and no unexpected WAL/SHM before mode=rw SQLite-managed recovery, then verify owned schema. Never manually remove journal; foreign product DB+journal remains unchanged/refused.
- Four halt stages, concurrent CLI, exclusive lock and actual SQLite insert failure verify all-or-none receipts/records, restart idempotency and ACK refusal. No physical power-loss claim. See rlc2_preview_e2e.md.


## D-052 — Pin test dependency bytes and exercise one Windows x64/x86 batch (2026-10-18 12:37:03 +09:00)

- Java cache/downloads must match lib/test-dependencies.sha256, not merely parse as ZIP. PostgreSQL CPython3.12 Win/Linux x64 wheel/transitive hashes are fixed; the CI PostgreSQL image uses a digest observed in a passing run.
- Configurable PowerShell wrapper runs actual x64/x86/SQLite/diagnostics/projection/preview/fault tests, optionally guarded disposable PG and actual Server models, restores environment and records ignored logs. CI actual-x86 job invokes it. No production activation.
- CI cancels duplicate PR/push runs on the same head branch and checks artifacts/source cleanliness. See acceleration_test_automation.md for commands and positive/negative artifact verification.


## D-053 — Concurrent Windows replace refusal must verify preservation and report blocked progress (2026-10-18 12:46:00 +09:00)

- Full batch found intermittent MoveFileExW error5 in existing reader/publisher test. Retained valid gen24 marker/gen25 tmp and original bytes prove fail-closed state. NIO share flags confirmed from local JDK source; exact denying process is unknown.
- Test accepts denial only after exact old marker/complete expected staged generation/writer byte checks, and requires reader refusal. Full25 progress is explicitly BLOCKED in that branch; successful branch still requires finalgen26. Deterministic held-marker pending-reader test guarantees rejection coverage. Core publisher/retention/retry behavior unchanged.
- Fresh native-only script compilation includes common scenario source; Windows x64 RLC2 101 checks PASS with deterministic branch. See acceleration worklog/automation doc for full batch and CI evidence.


## D-054 — Migration preflight is inspection, never an RLC1 identity reinterpretation (2026-10-18 13:18:30 +09:00)

- Reuse existing RLC1 envelope codec and verify source UUID/prefix/hash/frame sequence through read-only handles. Legacy fileKey hash cannot become tagged native identity; even intact legacy input returns manual migration-contract review, ACK false and automatic repair false.
- Report fixed manual next_action hints; reject mixed legacy/new marker paths in normal session projection, distinguish ledger lag from receipt damage and pending updates from corruption. No marker/ledger creation, checkpoint, repair or actuation. See ingress_preflight_load.md.

## D-055 — Bounded multi-session replay validates data before performance (2026-10-18 13:18:30 +09:00)

- Default CI1000 observations/4 sessions; opt-in executed10000+10 small sessions validates10100 records/10111 receipts/22 source cursors/11 claims, exact highwaters, duplicate-free replay and original bytes on actual x86 and Linux.
- Replace per-byte Formatter allocation in ledger hex with identical lowercase digit lookup. New x64 inspector validates10001 old-formatter x86 receipts unchanged. Cold-process timings are recorded with all phases/variance, not a throughput/power-loss guarantee. No stored format or ACK semantics change.


### D-056 — Validate committed preview identity sets before replay
- Context: changing an event primary key left aggregate counts unchanged; replay silently inserted the original ID (RED b032e04).
- Decision: independently validate prior source receipt extents, the ordered committed prefix, and its exact projected record ID set before any checkpoint update. Existing per-receipt/per-record content comparisons remain. Refuse inconsistent evidence without reconstruction; no schema or product sender change.
- Validation: synthetic key replacement and coordinated counter/deletion cases, normal append/rotation/replay, Windows/Linux/actual x86 suites. This is consistency checking, not authenticated protection from coordinated database replacement.

## D-057 — Agent-local multi-source integration contract v2
- Keep HTTP wire v1 and existing product DB/ACK state unchanged. Dedicated opt-in RLI2/user_version1 DB reuses preview transaction tables, adds canonical session/run binding and explicit child-source admissions. One logical session owns multiple physical UUID cursors/receipts; v1 run/record IDs remain stable.
- Explicit migration creates only a new dedicated file transactionally. Existing v1 files are read-only compatibility inventories, never auto-migrated. Concurrent old-Worker/new-store coexistence remains BLOCKED until coordinated lane migration; external quiescence is mandatory.
- Contract/invariants and acceptance: docs/interfaces/rlc2_integration_v2.md. Real SQLite revision/replay/rollback/legacy-byte/lane checks implemented; production adoption is separate.

## D-058 — Shared atomic integration backend
- Candidate format uses the existing transaction engine and converter rather than duplicating receipt/record logic. Transaction guards persist session path/run binding and explicit source admissions in the same transaction, reject missing past admissions and keep source UUID independent of run ID. No sender/Worker wiring.

## D-059 — Keep quiescence mandatory and detect stale projection before commit
- RED d08860d: writer publishes and updates ledger in BEFORE_COMMIT after projection; candidate incorrectly commits the old state. Bounded input snapshot evidence from before projection through precommit now rejects the change and rolls back all output state.
- Checks detect source/marker/manifest/ledger/legacy/tail changes but do not prove atomic FS/DB consistency against uncooperative writers. No input lock acquisition or automatic writer stop; live ingestion remains BLOCKED. Output must be outside inputs.

## D-060 — Schema identity and independent Supervisor axes
- RED0a27d0d reproduced renamed ACK column accepted by name/application-only check. Compare every schema object with authoritative DDL, preserving literal contents; quick_check/FK/version checks reject malformed output before writable use. Pre-I v0 DDL compatibility fixture retained.
- Add supervisor_contract_version2 fields without changing legacy JSON/exit semantics: inspection success, conditional source integrity, review, durability, operational readiness, ACK and repair remain independent. Read-only success never grants operation. Unexpected triggers refused; real SQL constraint rollback remains tested.

## D-061 — Preserve SQL token boundaries; test candidate recovery in actual child JVMs
- Schema whitespace canonicalization must not merge TEXT NOT NULL with TEXTNOTNULL. RED15f04f3 is retained; collapse whitespace while preserving boundaries/literals. Negative schema matrix35 and original v0 compatibility PASS locally.
- Child JVMs use the parent's actual runtime for candidate transaction halts and two-worker convergence. Recovery preserves all eight-table atomicity and no ACK. Runtime.halt remains distinct from physical power loss.
