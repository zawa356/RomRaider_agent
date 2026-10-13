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

## D-005 プロファイルは `RRBridge\profiles\` のファイル名だけを受け付ける（2026-10-12）

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
