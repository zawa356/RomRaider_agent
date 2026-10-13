# 03. RRBridge の設計（v0.4.0）

> v0.3.0 → v0.4.0 の差分は §9 にまとめた。本文は v0.4.0 の内容に更新済み。

## 1. 方針

1. **RomRaider本体を改造しない。** `-javaagent` で同じJVMに入り、公開メソッドを優先して呼ぶ。公開されていないもの（トグルボタン、データ配信の配列）だけリフレクションで触る。
2. **Program Filesに書き込まない。** 配置先は `Documents\OpenPortWork\RRBridge`。起動バッチだけを差し替える。
3. **車両に影響する操作は公開しない。** コマンドはホワイトリスト。
4. **ロガーを止めない。** RomRaiderのスレッド（EDT、配信スレッド）では重い処理をしない。転送の失敗がロギングに影響しない構造（キュー → spool → 送信は別スレッド）。
5. **データ保全を優先する。** spool（ローカルTSV）を保持し、送信済み位置を永続化する。**キューが満杯の瞬間の観測やクラッシュ直前の未永続化行は復元できない**ため、欠損を検出・明示し、無欠損とは主張しない。
6. **依存を増やさない。** エージェントは標準ライブラリのみ（JSONも手書き）。実行環境は Java 8（Zulu 8, 32bit）。
7. **後段（Supervisor・MCP）から使いやすい窓口。** ファイル（inbox/outbox）とHTTP（localhost）の2経路で同じ処理を呼ぶ。

## 2. 構成要素

```
RomRaider JVM (javaw.exe, Zulu 8 32bit, cwd = C:\Program Files (x86)\RomRaider)
├─ RomRaider Logger (EcuLogger, Swing)
│   └─ QueryManagerImpl ──(Response)──> AsyncDataUpdateHandler ──> 各 DataUpdateHandler
│                                                     ├ LiveData/Graph/Dashboard/…/FileUpdateHandlerImpl（CSV）
│                                                     └ Stream.INSTANCE（RRBridgeが追加）
└─ RRBridge（-javaagent: rrbridge.jar）
    ├ スレッド "RRBridge"            : 起動待ち → Stream取り付け → HTTP開始 → inbox監視・status.json更新
    ├ HTTPサーバー 127.0.0.1:18765   : com.sun.net.httpserver（4スレッドのプール "RRBridge-http"）
    ├ スレッド "RRBridge-spool"      : キュー → spool\rr_<session>.tsv に追記
    ├ スレッド "RRBridge-sender"     : spoolの未送信分 → HTTP POST → .sent 更新
    └ スレッド "RRBridge-selftest"   : 合成データ生成（コマンド実行時のみ）

別プロセス（検証用）: java.exe -cp rrreceiver.jar;sqlite-jdbc.jar net.neristal.rrbridge.Receiver
    HTTP 127.0.0.1:18766  /ingest（POST） /stats
    SQLite data\telemetry.db（WAL）
```

## 3. クラス別の詳細

### 3.1 `Agent`（`Premain-Class`）

- `premain(args, inst)`
  - `rrbridge.dir`（システムプロパティ）。未指定時は `%USERPROFILE%/Documents/OpenPortWork/RRBridge`。
  - `inbox/`、`outbox/`、`profiles/` を作成。`status.json`、`rrbridge.log` を置く。
  - `rrbridge.port`（既定 18765）。
  - デーモンスレッド `RRBridge` で `mainLoop()` を開始。
- `mainLoop()`
  1. `EcuLogger.getEcuLoggerWithoutCreation()` が非nullになるまで500ms間隔で待つ（RomRaiderが `getEcuLogger()` で生成するstaticシングルトン）。
  2. GUI構築完了待ちとして3秒待つ。
  3. `Stream.INSTANCE.install(logger, baseDir)` でデータ配信に取り付け、spool・送信スレッドを開始。
  4. HTTPサーバーを開始。
  5. 200ms周期で `inbox/*.cmd` を名前順に処理。1秒ごとに `status.json` を書き換える。
- `handleFile(f)`: `.cmd` を `java.util.Properties` として読む（`key=value`。バックスラッシュはエスケープ扱いになるので値にWindowsパスを書かない）→ `execute()` → `outbox/<同名>.json` に結果 → `.cmd` を削除（失敗時は `outbox/<名>.cmd.done` に移動）。
- `init(dir)`: ディレクトリ作成と `rrbridge.properties` の読込（試験からも呼ぶ）。`http.token`、`switch.settleMs`（0〜10000に丸める、既定1500）、`log.maxBytes`（既定5MB）、`spool.retentionDays`（既定14、`Stream` に渡す）。
- `execute(map)`（ファイル経由・HTTP経由の共通入口）: 読み取り系（`READ_ONLY` = status、stream_status、list_profiles）はロックなしで即応答。操作系は `CONTROL_LOCK` で1つずつ実行し、`lastCommand` 更新とログ出力を行う。例外はすべて `{"ok":false}` に変換。コマンド表は §4。
- `mainLoop()` は反復ごとに例外を捕まえる（v0.3.0までは1回の例外でループ全体が止まった）。
- `onEdt(r)`: EDT上なら直接、そうでなければ `SwingUtilities.invokeAndWait`。
- `fileButton()`: `EcuLogger.logToFileButton`（private `JToggleButton`）をリフレクションで取得。
- `setFileLog(cmd, on)`: `on=true` は `logger.isLogging()` が真のときのみ。状態が違うときだけ `doClick()`（RomRaiderのActionListenerがそのまま動くので、`fileUpdateHandler.start()/stop()`、ボタン色・文言・タイトル表示もGUI操作と同じになる）。
- `statusJson()`: §5。
- `Util.writeAtomic(f, s)`: `<f>.tmp` に書いて `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`（WindowsではMoveFileExで置換）。失敗したら通常の置換、それも失敗したら一時ファイルを消して次回に持ち越す。
- `log(s)`: `rrbridge.log` に追記。`log.maxBytes` を超えたら `rrbridge.log.1` に退避（1世代）。
- `listProfilesJson()`: `profiles/` の各XMLから `livedata="selected"` の要素IDを正規表現で抜き出す（ISO-8859-1で読む）。

### 3.2 `Stream`（`DataUpdateHandler` 実装、シングルトン `INSTANCE`）

- `session`: エージェント起動時刻 `yyyyMMdd_HHmmss`（X270のローカル時刻＝JST）。RomRaiderを起動し直すと新しいsession。
- `install(lg, baseDir)`
  - `spool/` 作成、`sink.properties`（`url`、`token`）を読む。既定 `url=http://127.0.0.1:18766/ingest`、token空。
  - リフレクションで `EcuLogger.controller` → `LoggerControllerImpl.queryManager` → `QueryManagerImpl.updateHandlers`（`DataUpdateHandler[]`）を取得し、末尾に自分を追加した新しい配列で置き換える。
  - `QueryManagerImpl.dataUpdater`（`AsyncDataUpdateHandler`）が既に存在すれば、その `handlers` フィールドも置き換える（ロギング中に取り付けた場合への対応）。通常はロギング開始のたびに `new AsyncDataUpdateHandler(updateHandlers)` されるので、`updateHandlers` の置き換えで以後のロギングにも反映される。
  - 実機ログ: `stream installed (handlers=10, session=20261012_121452, sink=http://127.0.0.1:18766/ingest)`（元の9個＋RRBridge）。
- `handleDataUpdate(Response r)`（RomRaiderの `AsyncDataUpdater` スレッドから、約16ms周期のループ内で応答ごとに呼ばれる）
  - `r.getData()` の `LoggerData` をID順にソート。IDの集合が前回と変わったら `segment++` し、各項目の `M` 行（ID、名称、選択中の換算の単位）をキューへ。
  - `S` 行: `seq`（セッション内通番）、`r.getTimestamp()`（`ResponseImpl` の生成時刻＝`System.currentTimeMillis()`）、`id=値` を `,` 区切り。値は `r.getDataValue(d)`（換算済みdouble、`Double.toString` 表記）。
  - 例外はすべて握りつぶす（RomRaiderのスレッドを止めないため）。
- `event(text)`: `E` 行。`Agent` がロギング開始停止、CSV記録開始停止（タイトル文字列付き）、`switch_profile` で呼ぶ。
- キュー: `LinkedBlockingQueue<String>`、容量200,000行。満杯の場合は非ブロッキングで該当行を破棄し`linesDropped`加算。**実測損失としてTSVへ`E ... queue_overflow lost_lines=...`を後続writerが追記**する。これによって失われた原本が復活するわけではない。
- `startThreads(spoolDir, sinkConfig)`: spool・送信スレッドの開始と `sink.properties` の読込。`install()` から呼ぶほか、試験からも呼べる。`url` を空にすると送信せずspoolのみ。
- `writerLoop()`（2026-10-13改善）: dequeueしたバッチは**fsync成功までメモリ上で保持**。IOExceptionで破棄せず同じ原文を再試行、既存TSV末尾がLF欠損なら停止状態を保って手動調査。write/readerのI/Oロックでcommit前の部分行送信を防ぐ。**電源断で未fsyncのキューデータは依然失われ得る**。
- `senderLoop()`: `spool/*.tsv` を名前順に走査し `sendSome()`。未送信がなければ500ms待機。例外時は `sendErrors++` して2秒待機。10分ごとに `cleanupSpool()`。
- `cleanupSpool(days, now)`（2026-10-13安全変更）: **自動削除無効（常に0）**。旧`.sent`は製品Server ACKではなく、製品Workerがまだ取り込んでいないTSVを消す危険がある。バックアップ・製品SQLite import cursor・Server ACKの照合に基づくオフライン手動移行だけを許容。
- `sendSome(f)`
  - `<f>.sent` に保存された送信済みバイト位置から最大512KB読み、最後の改行までを1回のPOST本文にする（行の途中は送らない）。
  - `POST <url>`、`Content-Type: text/tab-separated-values; charset=utf-8`、tokenがあれば `Authorization: Bearer <token>`。接続3秒・読み取り10秒タイムアウト。
  - 2xxなら `.sent` を進める。非2xxなら `sendErrors++`、2秒待機、位置は進めない。
  - 過去のsessionのspoolも未送信分があれば送る（RomRaider再起動をまたいだ再送）。
- `statusJson()`: `installed, installResult, session, segment, samples, linesProduced, linesDropped, linesSent, queue, pendingBytes（全spoolの未送信バイト合計）, sendErrors, sink, lastSendAt, lastSendResult, lastSampleAgeMs`。
- `selftest(count, hz)`: ECU無しで経路を試すための合成データ。`TEST_RPM`／`TEST_TPS`／`TEST_MRP`（正弦波）。新しいsegmentを作り、開始・終了イベントも出す。`count` 最大10000、`hz` 最大50。
- `cleanField(s)`（M行の名称・単位、E行の本文）: タブと改行だけ空白に置換。`,` や `=` はそのまま残る（v0.3.0では空白に置換していたため、`selftest start count=80` が `selftest start count 80` になっていた）。
- `cleanId(s)`（S行に入るID）: さらに `,` と `=` を `_` に置換（値リストの区切りとの衝突回避。実際のIDには含まれない）。

### 3.3 `Receiver`（検証用受信サーバー＝Telemetry APIの試作）

- 起動引数: `[db] [bind] [port] [token]`。既定 `telemetry.db 127.0.0.1 18766`、token空（認証なし）。
- `PRAGMA journal_mode=WAL`、autocommit off。
- `POST /ingest`: 本文を行ごとに処理し1トランザクションでcommit。応答 `{"ok":true,"samples":n,"measurements":n,"events":n,"duplicates":n,"maxLagMs":n}`。`events` は実際に挿入した件数（v0.4.0〜。v0.3.0は重複でも数えていた）。`duplicates` は重複した S 行の数。
- `GET /health`: 認証不要の死活確認 `{"ok":true,"service":"rrbridge-receiver","time":…}`。
- HTTPは4スレッドのプール。認証は `Util.authorized()`（`Authorization: Bearer <token>`、tokenが空なら不要）。
- `GET /stats`: session別の件数・先頭/末尾時刻・平均/最大遅延（`received_ms - t_ms`）と、最新サンプルの全項目値。
- `ingest()`/`stats()` は `synchronized`（SQLite接続を1本共有）。
- RomRaiderのクラスに依存しない（`Util` のみ）。`rrreceiver.jar` に `Receiver*.class` と `Util.class` だけを入れる。

### 3.4 `Util`

RomRaiderに依存しない共通処理（受信サーバーにも入る）: `q()`（JSON文字列）、`read()`、`reply()`、`writeAtomic()`、`loadProps()`、`longProp()`、`now()`/`fmt()`、`rotateIfLarge()`、`authorized()`。v0.4.0で `Agent` 側の重複実装を削除し、すべてここに集約した。

## 4. コマンド仕様

| cmd | 引数 | 動作 | 応答 | 備考 |
| --- | --- | --- | --- | --- |
| `status` | なし | 状態取得 | §5 の status JSON そのもの | 読み取り系。HTTPはGET可、ロック不要 |
| `list_profiles` | なし | `profiles/` のXMLと選択項目ID | `{"ok":true,"profiles":[{"name","selected":[…],"count"}]}` | 読み取り系 |
| `logging_start` | `wait_ms`（任意、0〜10000） | EDTで `EcuLogger.startLogging()`（`controller.start()`）。`wait_ms` 指定時は `isLogging()` が真になるまで100ms間隔で待つ | `ok`／`logging did not start within …` | イベント `logging_start` |
| `logging_stop` | `wait_ms`（任意） | CSV記録中なら先に `filelog_stop`、続けてEDTで `stopLogging()` | `ok` | イベント `logging_stop` |
| `filelog_start` | なし | ロギング中なら「Start file log」ボタンを押す | `ok`／`logging is not started` | 新しいCSV `romraiderlog_yyyyMMdd_HHmmss.csv` が作られる。イベント `filelog_start <タイトル>` |
| `filelog_stop` | なし | 「Stop file log」 | `ok` | イベント `filelog_stop <タイトル>` |
| `load_profile` | `profile=<名>.xml` | EDTで `EcuLogger.loadUserProfile(<profiles>/<名>)` | `ok`／エラー | **CSV記録中に使うとCSVヘッダーが多重化する** |
| `switch_profile` | `profile=<名>.xml` | CSV記録中なら 記録停止 → 読込 → `switch.settleMs`（既定1.5秒）待機 → 記録再開。所要時間を `rrbridge.log` に記録 | `ok`／エラー | 推奨。1区間1CSV。イベント `switch_profile <名>` |
| `stream_status` | なし | 転送状態 | `{"ok":true,"cmd":"stream_status","stream":{…}}` | 読み取り系 |
| `selftest_stream` | `count`（≤10000、既定40）、`hz`（≤50、既定8） | 合成データを転送経路に流す | `selftest started` | |

- `ok` 応答: `{"ok":true,"cmd":"<cmd>","status":{status JSON}}`
- エラー応答: `{"ok":false,"cmd":"<cmd>","error":"<理由>"}`
- 未知のコマンド: `unknown command. allowed: …`
- プロファイル名の検証: 正規表現 `[A-Za-z0-9_.-]+\.xml` かつ `profiles/` に実在。`../../.RomRaider/settings.xml` のような指定は拒否されることを実機確認。
- HTTP: `http://127.0.0.1:18765/<cmd>?key=value`。読み取り系はGET可、操作系はPOST必須（GETは405）。既定は**認証なし**（localhost限定。同じPCの任意のプロセスから操作可能）。`rrbridge.properties` の `http.token` を設定すると全リクエストでBearerトークン必須（401）。
- 数値引数が数字でない場合は `bad number for <key>: <値>` のエラー。範囲外は丸める。
- ファイル: `inbox/<任意>.cmd` に Properties 形式（CRLF可）。書き込み途中を拾わないよう `.tmp` で書いてから `.cmd` にrenameするのが安全（検証スクリプトはそうしている）。

## 5. status JSON（実機の出力例、2026-10-12 12:03）

```json
{"agent":"RRBridge 0.1.0","time":"2026-10-12T12:03:25.877+09:00","ready":true,
 "logging":false,"fileLogging":false,
 "title":"RomRaider v1.1.0 NOV26 2025 | ECU/TCU Logger",
 "outputDir":"C:\\Users\\zawa\\Documents\\OpenPortWork\\Logs",
 "newestCsv":{"name":"romraiderlog_20260921_191115.csv","bytes":326,"modified":"2026-09-21T19:11:18.392+09:00"},
 "lastCommand":""}
```

v0.3.0 では末尾に `"stream":{installed, session, segment, samples, linesProduced, linesSent, queue, sendErrors, sink, lastSendAt, lastSendResult, lastSampleAgeMs}` が加わる。

- `title`: RomRaiderのウィンドウタイトル。`- Profile: C:\User...\profiles\basic.xml`、`- Started logging to file: …\romraiderlog_20261012_120350.csv`、`- Stopped logging to file` など、RomRaiderが直近のメッセージを載せる。
- `outputDir`: `Settings.getLoggerOutputDirPath()`。
- `newestCsv`: `outputDir` 内で更新日時が最新の `.csv`。

## 6. 転送プロトコル（spool と POST本文で共通）

UTF-8、1行1レコード、タブ区切り、改行LF。

| 種別 | 列 | 例 |
| --- | --- | --- |
| `M` 項目定義 | `M` session segment param_id name units | `M	20261012_121452	1	TEST_RPM	Selftest Engine Speed	rpm` |
| `S` サンプル | `S` session segment seq epoch_ms values | `S	20261012_121452	1	1	1791774902345	TEST_RPM=800,TEST_TPS=5.0,TEST_MRP=-60` |
| `E` イベント | `E` session segment epoch_ms text | `E	20261012_121452	2	1791774950000	switch_profile fuel.xml` |

- `segment` は「項目の集合（ID）」が変わるたびに増える。プロファイル切替時は項目が1つずつ登録・解除されるため、**切替の途中経過で segment が複数進むことがある**（中間状態のsegmentはサンプル数が少ない）。
- 実ECUの param_id は Logger定義のID（`P8`=Engine Speed など。`E` で始まるのはECU固有の拡張パラメーター、`S` はスイッチ）。
- 受信側は `(session, seq)` で一意。再送されても重複しない。
- 時刻はX270の時計（epoch ms）。受信側の `received_ms` との差が遅延になるが、両者の時計がずれていればその分ずれる（VPS化したらNTP前提）。

## 7. 受信側DBスキーマ（試作、SQLite）

```sql
CREATE TABLE sessions(session TEXT PRIMARY KEY, first_received_ms INTEGER, last_received_ms INTEGER);
CREATE TABLE parameters(session TEXT, segment INTEGER, param_id TEXT, name TEXT, units TEXT,
                        PRIMARY KEY(session, segment, param_id));
CREATE TABLE samples(id INTEGER PRIMARY KEY, session TEXT, segment INTEGER, seq INTEGER,
                     t_ms INTEGER, received_ms INTEGER, UNIQUE(session, seq));
CREATE TABLE measurements(sample_id INTEGER, param_id TEXT, value REAL, PRIMARY KEY(sample_id, param_id));
CREATE TABLE events(session TEXT, segment INTEGER, t_ms INTEGER, text TEXT, received_ms INTEGER,
                    UNIQUE(session, t_ms, text));
CREATE INDEX samples_t ON samples(session, t_ms);
```

- 縦持ち（`measurements`）にしたのは、プロファイルによって列構成が変わるため（引継ぎ資料 §4.3 の概念モデルに沿う）。
- 項目名・単位は `parameters` を `(session, segment, param_id)` で結合して得る。
- 引継ぎ資料の概念モデルのうち `segment_stats`、`analysis_results`、`measurement_plans`、`commands` は未実装。

## 8. 採用しなかった方式（詳細は `08_decisions.md`）

| 方式 | 不採用の理由 |
| --- | --- |
| `settings.xml`／プロファイルXMLを外部で書き換えて再読込 | 実行中のRomRaiderは終了時に設定を保存するため競合する。再読込を外部から発火する手段がない |
| RomRaiderのプラグイン機構（`plugins/*.plugin`） | `getMenuAction(EcuLogger)` でインスタンスは得られるが、クラスはアプリのクラスローダーから読まれるため、jarをクラスパスに追加するか Program Files に書く必要がある。作業ディレクトリを変えると相対パス依存（`customize/`、`lib/log4j.properties`、`i18n`）が壊れる |
| Javaソースを改造してRomRaiderを再ビルド | 本体の更新に追従しにくい。配布物を置き換える必要がある |
| GUI操作（UI Automation、Java Access Bridge、画面クリック） | 遅い・壊れやすい。走行中の無人運用に向かない（Access Bridgeは同梱されているので非常手段としては残る） |
| CSVの追記を監視して転送 | `FileLoggerImpl` がバッファリングするため遅れる。全項目が揃った行しか書かれない。プロファイル切替でヘッダーが崩れる |
| RomRaiderから直接DB（MariaDB/MySQL）へJDBCで書き込み | DBを外部公開することになる。通信断時の扱い・重複排除・認証を車載側に持つことになる |
| JDWP（デバッガ接続）やAttach API での後付け注入 | 同梱がJRE（`attach.dll` なし）。起動時 `-javaagent` で足りる |

## 9. v0.4.0 での変更（2026-10-12）

| 区分 | 変更 | 理由 |
| --- | --- | --- |
| 応答性 | 読み取り系（status / stream_status / list_profiles）を操作系のロックから外し、HTTPを4スレッド化 | v0.3.0では `execute` 全体が `synchronized` で、`switch_profile` の待機中は status も待たされた。HTTPも単一スレッド |
| HTTP | 読み取り系はGET可 | ドキュメントと実装の食い違い（stream_status がPOSTのみ）を解消 |
| 認証 | `http.token`（任意）でBearer認証 | localhostでも同じPCの他プロセスから操作できるため |
| コマンド | `list_profiles` 追加 | Supervisor／MCPが「何に切り替えられるか」を知る手段 |
| コマンド | `logging_start`／`logging_stop` に `wait_ms` | 非同期で応答時の状態が古かった |
| 挙動 | `logging_stop` は記録中なら先にCSVを止める | ボタン状態と実際の記録状態を揃える |
| 設定 | `rrbridge.properties`（`http.token`、`switch.settleMs`、`log.maxBytes`、`spool.retentionDays`） | 固定値をやめる |
| 堅牢性 | `mainLoop` を反復ごとに例外処理 | 1回の例外で inbox 監視と status.json 更新が永久に止まる恐れ |
| 堅牢性 | `writeAtomic` を `Files.move(ATOMIC_MOVE)` に | delete→rename の間に読み取り側がファイルを見失う |
| 運用 | `rrbridge.log` のローテーション、送信済みspoolの自動削除 | 長期運用でディスクを使い続ける |
| 観測 | `linesDropped`、`pendingBytes`、`installResult` を status に追加 | キュー溢れ・未送信量・取り付け結果が見えなかった |
| データ | E行・M行で `,` `=` を残す（IDのみ置換） | イベント文字列が変わっていた |
| 受信 | `events` を実挿入件数に、`/health` 追加、4スレッド化 | 再送時に件数が誤解を招いた |
| 構成 | 共通処理を `Util` に集約 | `Agent` と `Util` の重複 |
| 試験 | `src/test/.../SelfTest.java` と `test.sh`（30項目） | 手動試験だけでは回帰を防げない |

## 10. v0.5.0 での変更: 取得速度の可視化とガード（2026-10-12）

背景 [ユーザー発言]: 「パラメータを選びすぎるとK-Lineのスローに追いつけなくなり、データがうまく取得できない問題が過去にあった。MCP経由で設定がめちゃくちゃにされると全データが取れない事故が起きかねない」。

### 10.1 取得速度の計測（`Stream`）
- `handleDataUpdate`（実ECU応答のみ。selftestは含めない）で応答時刻を512件のリングバッファに記録。
- `rateHz(windowMs, now)` = 窓内の (件数−1)÷(最後−最初)。`intervalP95(windowMs, now)` = 窓内のサンプル間隔の95パーセンタイル。
- `status.stream` に `realSamples`、`params`（直近サンプルの項目数）、`rateHz10s`、`intervalP95Ms10s` を追加。
- スレッド `RRBridge-health` が10秒ごとに E 行 `health rate_hz=7.95 samples=80 params=8 interval_p95_ms=126 link_errors=0 link_connects=1` を送る（サンプルもエラー増加もない間は送らない）。ついでに `LinkMonitor.ensureAttached()` を呼ぶ。

### 10.2 通信状態の計数（`LinkMonitor`、新規）
- RomRaiderは通信の成否をlog4jにしか出さない。log4j 1.2 の `org.apache.log4j.Appender` インターフェースを `java.lang.reflect.Proxy` で実装し、`Logger.getRootLogger().addAppender()` で取り付ける（log4jをコンパイル時に参照しない＝依存を増やさない）。
- RomRaiderは `lib/log4j.properties` を `configureAndWatch` しており再設定でアペンダーが外れうるため、10秒ごとに `isAttached` を確認して付け直す。
- 判定（メッセージ文字列）: `connection initialised` → connects、`ECU ID = <id>` → ecuInits と ecuId、レベルERRORで `readMsg`／`timeout`／`Error sending init`／`Error occurred`（例外メッセージも見る）→ errors。エラー時は E 行 `link_error count=N <文言>` を送る（10秒に1回まで）。
- 注意: ECU無応答時は1回の接続試行で「J2534のinit失敗」と「シリアル（portName未設定）失敗」の2件が出るため、errors は試行回数の約2倍になる。
- `status.link` = `{attach, ecuId, connects, ecuInits, errors, lastError, lastErrorAt}`。

### 10.3 項目数の上限
- `profile.maxParams`（既定20、0で無制限）。`switch_profile`／`load_profile` は読込前にXMLの `livedata="selected"` を数え、超えれば `too many parameters …` で拒否（RomRaiderには何もしない）。選択0件も拒否。
- 既定20の根拠: 2026-09-21 の実車で19項目（2.60Hz）までは連続取得できていた。13:51のタイムアウト連発時の構成は不明。**本来は項目数ではなく応答バイト数（1/2/4バイト項目の合計）で制限すべき**（`09_open_issues.md`）。
- `list_profiles` に `maxParams`、`current`、各プロファイルの `allowed` を追加。

### 10.4 自動復帰ガード
- `switch_profile`（ガード自身による切替を除く）の直前に、直近5秒の取得周波数 `prevRate` と現在のプロファイル名を記録。
- 切替後、`prevRate >= guard.minRateHz` かつロギング中なら、別スレッド `RRBridge-guard` が `settleMs + guard.checkMs` 待ってから直近 `guard.checkMs` の周波数を測る。下限未満なら、直前のプロファイル（不明なら `guard.fallbackProfile`）へ `switch_profile` し直し、E 行 `guard_revert <新> -> <戻し先> rate=… < 2.0Hz (before …Hz, link_errors=…)` を送る。
- 判定までの間に別の切替があれば中止（世代番号 `switchGeneration`）。
- 切替前から取れていない（イグニッションOFF等）ときは判断材料がないので作動しない。
- `status.guard` = `{enabled, minRateHz, checkMs, maxParams, currentProfile, lastAction}`。
