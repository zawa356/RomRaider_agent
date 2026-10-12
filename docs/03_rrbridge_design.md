# 03. RRBridge の設計（v0.3.0）

## 1. 方針

1. **RomRaider本体を改造しない。** `-javaagent` で同じJVMに入り、公開メソッドを優先して呼ぶ。公開されていないもの（トグルボタン、データ配信の配列）だけリフレクションで触る。
2. **Program Filesに書き込まない。** 配置先は `Documents\OpenPortWork\RRBridge`。起動バッチだけを差し替える。
3. **車両に影響する操作は公開しない。** コマンドはホワイトリスト。
4. **ロガーを止めない。** RomRaiderのスレッド（EDT、配信スレッド）では重い処理をしない。転送の失敗がロギングに影響しない構造（キュー → spool → 送信は別スレッド）。
5. **データを失わない。** spool（ローカルTSV）を正本とし、送信済み位置を永続化。受信側は冪等。
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
    ├ HTTPサーバー 127.0.0.1:18765   : com.sun.net.httpserver（既定の単一スレッドexecutor）
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
- `execute(map)`（`synchronized`。ファイル経由・HTTP経由の共通入口）: コマンド表は §4。
- `onEdt(r)`: EDT上なら直接、そうでなければ `SwingUtilities.invokeAndWait`。
- `fileButton()`: `EcuLogger.logToFileButton`（private `JToggleButton`）をリフレクションで取得。
- `setFileLog(cmd, on)`: `on=true` は `logger.isLogging()` が真のときのみ。状態が違うときだけ `doClick()`（RomRaiderのActionListenerがそのまま動くので、`fileUpdateHandler.start()/stop()`、ボタン色・文言・タイトル表示もGUI操作と同じになる）。
- `statusJson()`: §5。
- `writeAtomic(f, s)`: `<f>.tmp` に書いて削除→rename。Windowsでは読み取り中だとdeleteに失敗し、その回の更新が飛ぶことがある（実害は次回更新で解消）。
- `log(s)`: `rrbridge.log` に追記（ローテーションなし）。

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
- キュー: `LinkedBlockingQueue<String>`、容量200,000行。満杯なら捨てる（`linesProduced` が増えない）。
- `writerLoop()`: `take()` ＋ `drainTo(…, 5000)` でまとめて `spool/rr_<session>.tsv` に追記（UTF-8、改行LF）。失敗時はログ出力して500ms待機。
- `senderLoop()`: `spool/*.tsv` を名前順に走査し `sendSome()`。未送信がなければ500ms待機。例外時は `sendErrors++` して2秒待機。
- `sendSome(f)`
  - `<f>.sent` に保存された送信済みバイト位置から最大512KB読み、最後の改行までを1回のPOST本文にする（行の途中は送らない）。
  - `POST <url>`、`Content-Type: text/tab-separated-values; charset=utf-8`、tokenがあれば `Authorization: Bearer <token>`。接続3秒・読み取り10秒タイムアウト。
  - 2xxなら `.sent` を進める。非2xxなら `sendErrors++`、2秒待機、位置は進めない。
  - 過去のsessionのspoolも未送信分があれば送る（RomRaider再起動をまたいだ再送）。
- `statusJson()`: `installed, session, segment, samples, linesProduced, linesSent, queue, sendErrors, sink, lastSendAt, lastSendResult, lastSampleAgeMs`。
- `selftest(count, hz)`: ECU無しで経路を試すための合成データ。`TEST_RPM`／`TEST_TPS`／`TEST_MRP`（正弦波）。新しいsegmentを作り、開始・終了イベントも出す。`count` 最大10000、`hz` 最大50。
- `clean(s)`: 名称・単位・イベント文字列のタブ、改行、`,`、`=` を空白に置換（区切り文字との衝突回避）。そのためイベント文字列 `selftest start count=80` は受信側で `selftest start count 80` になる。

### 3.3 `Receiver`（検証用受信サーバー＝Telemetry APIの試作）

- 起動引数: `[db] [bind] [port] [token]`。既定 `telemetry.db 127.0.0.1 18766`、token空（認証なし）。
- `PRAGMA journal_mode=WAL`、autocommit off。
- `POST /ingest`: 本文を行ごとに処理し1トランザクションでcommit。応答 `{"ok":true,"samples":n,"measurements":n,"events":n,"duplicates":n,"maxLagMs":n}`。`events` は挿入試行数（重複でも数える）。
- `GET /stats`: session別の件数・先頭/末尾時刻・平均/最大遅延（`received_ms - t_ms`）と、最新サンプルの全項目値。
- `ingest()`/`stats()` は `synchronized`（SQLite接続を1本共有）。
- RomRaiderのクラスに依存しない（`Util` のみ）。`rrreceiver.jar` に `Receiver*.class` と `Util.class` だけを入れる。

### 3.4 `Util`

`q()`（JSON文字列エスケープ）、`read()`（InputStream→UTF-8文字列）、`reply()`（JSON応答）。`Receiver` 用に `Agent`/`Stream` から切り出したもの。`Agent` にも同等の `q()`/`reply()` が残っている（重複。`09_open_issues.md`）。

## 4. コマンド仕様

| cmd | 引数 | 動作 | 応答 | 備考 |
| --- | --- | --- | --- | --- |
| `status` | なし | 状態取得 | §5 の status JSON そのもの | HTTPはGET可 |
| `logging_start` | なし | EDTで `EcuLogger.startLogging()`（`controller.start()`） | `ok` | 非同期。直後は `logging=false` のことがある。イベント `logging_start` |
| `logging_stop` | なし | EDTで `stopLogging()` | `ok` | イベント `logging_stop` |
| `filelog_start` | なし | ロギング中なら「Start file log」ボタンを押す | `ok`／`logging is not started` | 新しいCSV `romraiderlog_yyyyMMdd_HHmmss.csv` が作られる。イベント `filelog_start <タイトル>` |
| `filelog_stop` | なし | 「Stop file log」 | `ok` | イベント `filelog_stop <タイトル>` |
| `load_profile` | `profile=<名>.xml` | EDTで `EcuLogger.loadUserProfile(<profiles>/<名>)` | `ok`／エラー | **CSV記録中に使うとCSVヘッダーが多重化する** |
| `switch_profile` | `profile=<名>.xml` | CSV記録中なら 記録停止 → 読込 → 1.5秒待機 → 記録再開 | `ok`／エラー | 推奨。1区間1CSV。イベント `switch_profile <名>` |
| `stream_status` | なし | 転送状態 | `{"ok":true,"cmd":"stream_status","stream":{…}}` | HTTPはPOSTのみ（現実装の制約） |
| `selftest_stream` | `count`（≤10000、既定40）、`hz`（≤50、既定8） | 合成データを転送経路に流す | `selftest started` | |

- `ok` 応答: `{"ok":true,"cmd":"<cmd>","status":{status JSON}}`
- エラー応答: `{"ok":false,"cmd":"<cmd>","error":"<理由>"}`
- 未知のコマンド: `unknown command. allowed: …`
- プロファイル名の検証: 正規表現 `[A-Za-z0-9_.-]+\.xml` かつ `profiles/` に実在。`../../.RomRaider/settings.xml` のような指定は拒否されることを実機確認。
- HTTP: `http://127.0.0.1:18765/<cmd>?key=value`。`status` 以外はPOST必須（GETは405）。**認証なし**（localhost限定のため。同じPCの任意のプロセスから操作可能）。
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
