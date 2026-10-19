# 10. 接続規約（インターフェース仕様）

このシステムは複数の場所（車載X270、受信サーバー、AIクライアント）にまたがる。
この文書は、**部品と部品をつなぐすべての接続口（コネクター）について、何を受け取り、何を返し、何を送るか**を定める正本である。
コードと食い違ったら、どちらが正しいかを確認して両方を直す。接続口を変更・追加したら、必ずこの文書を同じコミットで更新する。

対象バージョン: RRBridge v0.5.0、受信サーバー（Java `Receiver`）v0.5.0同梱版、`server/`（Python）v0.1.0（2026-10-12）。

---

> **2026-10-13 製品版v1実装に関する注記:** ここに記述されたTSV転送、C8/C9などは**引き続きPoC専用**。製品版Phase 2.1-Aで独立したSQLite ACKスプール、Phase 2.1-BでHTTPS/JSON送信クライアントが追加されたが、**PoCの既存転送APIは変更していない**。製品SenderはまだRRBridgeから自動起動されず、実ServerとのE2Eも未検証。新しいServer–Agent v1の正本は[別リポジトリの通信契約](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md)。製品実装の進捗は[`product_v1_implementation_progress.md`](product_v1_implementation_progress.md)を参照。

## 0. 全体図と接続口の一覧

```
 [BP5 ECU] ─K-Line(SSM)─ [OpenPort 2.0] ─J2534─ [RomRaider Logger]
                                                     │  (同じJVM)
                                          C1 ─────── │ ─────── RomRaider内部への接続（RRBridge が取り付く）
                                                     ▼
                                              [RRBridge agent]  ── X270 ──────────────────────────────┐
     操作する側（Supervisor、人、試験スクリプト）                                                         │
        ├─ C2 ファイル: inbox/*.cmd → outbox/*.json                                                       │
        ├─ C3 HTTP: 127.0.0.1:18765                                                                      │
        └─ C4 状態ファイル: status.json（読むだけ）                                                       │
     設定（人が置く）                                                                                     │
        ├─ C5 profiles/*.xml（RomRaiderユーザープロファイル）                                             │
        ├─ C6 rrbridge.properties                                                                        │
        └─ C7 sink.properties                                                                            │
     転送                                                                                                 │
        ├─ C8 spool/rr_<session>.tsv（ローカルの正本）＋ .sent                                            │
        └─ C9 HTTP POST <sink url>（TSV） ──────────────────────────────────────────────┐                │
                                                                                         ▼                │
                              ┌────────── 受信側（どちらか、または両方） ──────────────────┐            │
                              │ C10 Java Receiver（X270内、検証用）  127.0.0.1:18766       │            │
                              │      /ingest  /health  /stats      → SQLite               │            │
                              │ C11 Python server（VPS想定）  https://<DOMAIN>              │            │
                              │      /ingest  /health  /mcp        → PostgreSQL / SQLite   │            │
                              └────────────────────────────────────────────────────────────┘            │
                                         ▲ C12 MCP（Streamable HTTP, JSON-RPC）                          │
                                         │                                                                │
                     [AIクライアント] Claude カスタムコネクタ / ChatGPT 等（各社クラウドから接続）           │
                                         C13 DBスキーマ（query_telemetry と将来の解析の契約）               │
```

| ID | 接続口 | 方向 | 形式 | 認証 | 実装 | 状態 |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | RomRaider内部 | RomRaider ⇄ RRBridge | Java（同一JVM、リフレクション、log4j） | なし（同一プロセス） | `Agent`、`Stream`、`LinkMonitor` | 実機確認済み（実ECUデータの到着は未確認） |
| C2 | 操作ファイル | 外部 → RRBridge → 外部 | Properties → JSON | フォルダのアクセス権 | `Agent.handleFile` | 実機確認済み |
| C3 | 操作HTTP | 外部 ⇄ RRBridge | HTTP/1.1、クエリ文字列 → JSON | 任意のBearer（既定オフ） | `Agent.startHttp` | 実装済み、X270上の別プロセスからの利用は未試験 |
| C4 | 状態ファイル | RRBridge → 外部 | JSON（1秒ごとに置換） | フォルダのアクセス権 | `Agent.mainLoop` | 実機確認済み |
| C5 | プロファイル | 人 → RRBridge → RomRaider | RomRaiderユーザープロファイルXML | フォルダのアクセス権 | `Agent.profileFile/selectedIds` | 実機確認済み |
| C6 | 動作設定 | 人 → RRBridge | Properties | — | `Agent.init` | 実機確認済み |
| C7 | 転送先設定 | 人 → RRBridge | Properties | — | `Stream.startThreads` | 実機確認済み |
| C8 | spool | RRBridge → ディスク | TSV（UTF-8、LF） | — | `Stream.writerLoop` | 実機確認済み |
| C9 | 転送HTTP | RRBridge → 受信側 | HTTP POST、TSV | Bearer（任意） | `Stream.sendSome` | 実機確認済み（Java受信）、Python受信へは実spoolの再送で確認 |
| C10 | Java受信 | 受信 | HTTP、TSV → SQLite | Bearer（任意） | `Receiver` | 実機確認済み |
| C11 | Python受信 | 受信 | HTTP、TSV → DB | Bearer（`INGEST_TOKEN`） | `server/rrserver` | コンテナ内で確認済み、VPS未配置 |
| C12 | MCP | AIクライアント ⇄ server | MCP Streamable HTTP（stateless、JSONレスポンス） | Bearer（`MCP_TOKEN`） | `server/rrserver/app.py` | コンテナ内で確認済み |
| C13 | DBスキーマ | server ⇄ 解析 | SQL | 読み取り専用で利用 | `server/rrserver/db.py`、`Receiver` | 確認済み |
| C14 | Supervisor ⇄ server（計測計画） | — | 未定 | — | 未実装 | 構想のみ（§14） |

---

## C1. RomRaider 内部への接続（RRBridge が取り付く場所）

RRBridge は `-javaagent` で RomRaider と同じJVMに読み込まれる。RomRaider本体は変更しない。

### C1.1 読むもの
| 対象 | 取得方法 | 用途 |
| --- | --- | --- |
| ロガー本体 | `EcuLogger.getEcuLoggerWithoutCreation()`（public static） | すべての起点 |
| ロギング中か | `EcuLogger.isLogging()` | status、`wait_ms`、ガード |
| CSV記録中か | private `EcuLogger.logToFileButton.isSelected()`（リフレクション） | status、`switch_profile` |
| ウィンドウタイトル | `EcuLogger.getTitle()` | status（RomRaiderの直近メッセージが入る） |
| CSV出力先 | `EcuLogger.getSettings().getLoggerOutputDirPath()` | status の `outputDir`、`newestCsv` |
| サンプル（1応答） | `QueryManagerImpl.updateHandlers` に追加した `DataUpdateHandler` の `handleDataUpdate(Response)` | 転送（C8/C9）、取得速度 |
| 通信の成否 | log4j ルートロガーに追加した `Appender`（Proxy実装）が受けるログ | `status.link`、`link_error`／`health` イベント |

サンプル1件から得るもの: `LoggerData.getId()`（例 `P8`）、`getName()`（例 `Engine Speed`）、`getSelectedConvertor().getUnits()`（例 `rpm`）、`Response.getDataValue(d)`（換算済み double）、`Response.getTimestamp()`（応答オブジェクト生成時刻、epoch ms）。

### C1.2 書くもの（RomRaiderに対して行う操作）
| 操作 | 呼び出し | スレッド |
| --- | --- | --- |
| ロギング開始・停止 | `EcuLogger.startLogging()`／`stopLogging()` | EDT（`SwingUtilities.invokeAndWait`） |
| CSV記録の開始・停止 | `logToFileButton.doClick()`（状態が違うときだけ） | EDT |
| プロファイル読込 | `EcuLogger.loadUserProfile(<RRBridge>/profiles/<名>)` | EDT |
| データ配信への追加 | `QueryManagerImpl.updateHandlers` 配列の差し替え（＋稼働中なら `AsyncDataUpdateHandler.handlers`） | 起動時1回 |
| ログ監視の追加 | `Logger.getRootLogger().addAppender(proxy)`（10秒ごとに付け直し確認） | 起動時＋定期 |

### C1.3 しないこと（禁止）
ECUへの書き込み、ROM読み書き、ECUリセット、DTC読出・消去、学習値リセット、OpenPortのFW更新、RomRaider設定ファイル（`settings.xml`）の書き換え、Program Files への書き込み。

依存箇所の詳細と行番号: `04_romraider_internals.md` §1。

---

## C2. 操作ファイル（inbox / outbox）

| 項目 | 規約 |
| --- | --- |
| 場所 | `<rrbridge.dir>\inbox\`（既定 `C:\Users\zawa\Documents\OpenPortWork\RRBridge\inbox\`） |
| 投入 | `<任意名>.cmd`。書き込み途中を読まれないよう、`<任意名>.tmp` に書いてから `.cmd` に rename すること |
| 形式 | `java.util.Properties`（`key=value` を1行ずつ、CRLF可、UTF-8／ASCII）。**バックスラッシュはエスケープ扱い**なので値にWindowsパスを書かない（プロファイルはファイル名だけ渡す） |
| 必須キー | `cmd` |
| その他のキー | コマンドごと（C3 の表と同じ）。値は前後の空白を除去 |
| 処理順 | 200ms周期で inbox を見て、ファイル名の昇順に1件ずつ処理 |
| 結果 | `<rrbridge.dir>\outbox\<任意名>.json`（C3 と同じJSON）。`.tmp` 経由で原子的に置換 |
| 後始末 | 処理後 `.cmd` を削除（失敗時は `outbox\<任意名>.cmd.done` に移動） |
| 認証 | なし（フォルダのアクセス権で守る） |
| 推奨命名 | `<UTC時刻HHMMSS>_<識別子>.cmd`（例 `035910_s4.cmd`）。昇順＝投入順になる |

例:
```properties
cmd=switch_profile
profile=fuel.xml
```

---

## C3. 操作HTTP（127.0.0.1:18765）

| 項目 | 規約 |
| --- | --- |
| 待受 | `127.0.0.1:<rrbridge.port>`（既定 18765、`-Drrbridge.port=` で変更）。**外部ネットワークからは届かない** |
| URL | `http://127.0.0.1:18765/<cmd>?key=value&...`（パスがコマンド名、引数はクエリ文字列、URLエンコード） |
| メソッド | 読み取り系（`status`、`stream_status`、`list_profiles`）は GET／POST どちらも可。操作系は **POST のみ**（GET は 405） |
| 認証 | `rrbridge.properties` の `http.token` が空でなければ全リクエストで `Authorization: Bearer <token>` が必要（不一致は 401）。既定は空（認証なし） |
| 応答 | `Content-Type: application/json; charset=utf-8`。HTTPステータスは原則 200（成否は本文の `ok`）。401／405／500 は本文 `{"ok":false,...}` |
| 同時実行 | 4スレッドで受ける。読み取り系は即応答、操作系は1件ずつ直列（`CONTROL_LOCK`） |
| 文字コード | UTF-8 |

### C3.1 コマンド一覧

| cmd | 種別 | 引数 | 動作 | 成功時の本文 |
| --- | --- | --- | --- | --- |
| `status` | 読取 | — | 状態を返す | §C4 の status JSON そのもの（`ok` キーなし） |
| `stream_status` | 読取 | — | 転送部分だけ | `{"ok":true,"cmd":"stream_status","stream":{…}}` |
| `list_profiles` | 読取 | — | `profiles/` のXMLと選択項目 | `{"ok":true,"cmd":"list_profiles","maxParams":20,"current":"basic.xml","profiles":[{"name":"basic.xml","selected":["P8",…],"count":6,"allowed":true},…]}` |
| `logging_start` | 操作 | `wait_ms`（0〜10000、既定0） | ECU通信開始。`wait_ms` 指定時は `isLogging()` が真になるまで待つ | `{"ok":true,"cmd":…,"status":{…}}` |
| `logging_stop` | 操作 | `wait_ms` | CSV記録中なら先に止めてから通信停止 | 同上 |
| `filelog_start` | 操作 | — | CSV記録開始（ロギング中のみ） | 同上 |
| `filelog_stop` | 操作 | — | CSV記録停止 | 同上 |
| `switch_profile` | 操作 | `profile`（必須、ファイル名） | 項目数検査 → CSV記録中なら 停止→読込→`switch.settleMs` 待ち→再開。条件を満たせば自動復帰ガードを仕掛ける | 同上 |
| `load_profile` | 操作 | `profile` | 項目数検査 → 読込のみ（**CSV記録中に使うとCSVヘッダーが多重化する**） | 同上 |
| `selftest_stream` | 操作（試験） | `count`（1〜10000、既定40）、`hz`（1〜50、既定8） | 合成データ（`TEST_*`）を転送経路に流す | `{"ok":true,"cmd":"selftest_stream","result":"selftest started"}` |

数値引数は範囲外なら丸める。数字でなければエラー。

### C3.2 エラー（本文 `{"ok":false,"cmd":"<cmd>","error":"<理由>"}`）

| 理由（`error` の先頭） | 発生条件 |
| --- | --- |
| `unknown command. allowed: …` | ホワイトリストにないコマンド |
| `logger not ready` | RomRaiderのロガーがまだ生成されていない |
| `profile must be an existing file name in profiles dir (pattern [A-Za-z0-9_.-]+\.xml): <値>` | ファイル名の形式違反、パス区切りを含む、存在しない |
| `too many parameters: <名> selects <n> (limit profile.maxParams=<m>) …` | 選択項目数が上限超過（RomRaiderには何もしない） |
| `no parameter selected in <名>` | 選択項目が0 |
| `logging is not started` | ロギング停止中に `filelog_start` |
| `logToFileButton not accessible` | RomRaiderの内部構造が想定と違う（バージョン違い） |
| `loadUserProfile returned false (see rr_system.log)` | RomRaiderが読込に失敗 |
| `logging did not start within <n>ms` ／ `did not stop` | `wait_ms` 内に状態が変わらない |
| `bad number for <key>: <値>` | 数値引数が数字でない |
| `state did not change` | ボタン操作後も状態が変わらない |
| `unauthorized`（HTTP 401） | トークン不一致 |
| `use POST for control commands`（HTTP 405） | 操作系をGETで呼んだ |

---

## C4. 状態（status.json と `status` コマンド）

`<rrbridge.dir>\status.json` に約1秒ごとに置換（原子的）。内容は `status` コマンドと同じ。

| キー | 型 | 意味 |
| --- | --- | --- |
| `agent` | string | `"RRBridge 0.5.0"` |
| `time` | string | 生成時刻（ISO 8601、X270のローカル時刻＋オフセット） |
| `ready` | bool | ロガー本体を取得できたか |
| `logging` | bool | ECU通信中か（接続試行中を含む） |
| `fileLogging` | bool | CSV記録中か |
| `title` | string | RomRaiderのタイトル（`- Profile: …`、`- Started logging to file: …` など） |
| `outputDir` | string | CSV出力先 |
| `newestCsv` | object | `{name, bytes, modified}` 出力先で最新のCSV |
| `lastCommand` | string | 直近の操作系コマンドと時刻 |
| `stream` | object | §C4.1 |
| `link` | object | §C4.2 |
| `guard` | object | §C4.3 |

### C4.1 `stream`
| キー | 意味 |
| --- | --- |
| `installed` / `installResult` | データ配信への取り付け結果（例 `installed (handlers=10, session=…, sink=…)`） |
| `session` | セッションID（RRBridge起動時刻 `yyyyMMdd_HHmmss`、X270ローカル時刻） |
| `segment` | 現在の区間番号 |
| `samples` | セッション内の通番（selftest を含む） |
| `realSamples` | 実ECU応答の件数（selftest を含まない） |
| `params` | 直近サンプルの項目数 |
| `rateHz10s` | 直近10秒の取得周波数（実ECU応答のみ） |
| `intervalP95Ms10s` | 直近10秒のサンプル間隔の95パーセンタイル（ms、2件未満は -1） |
| `lastSampleAgeMs` | 最後のサンプルからの経過（ms、なければ null） |
| `linesProduced` / `linesDropped` / `linesSent` | 生成した行／キュー溢れで失った行／旧経路の送信済み行。`linesDropped`増加分はTSVに`E ... queue_overflow lost_lines=N dropped_total=N`として明示記録（行自体の復元不可） |
| `queue` / `pendingBytes` | メモリキューの行数／spoolの未送信バイト合計 |
| `writerPendingLines` / `memoryBufferedLines` | writerがキューから取り出してfsync未確定の行数（欠損マーカーを含む）／`queue + writerPendingLines`の概算値。**いずれもメモリ上でのみ保持され、電源断・プロセス異常終了時に失われ得る** |
| `writerDegraded` / `spoolWriteFailures` | 最後の書込試行が失敗してから正常fsyncしていない場合true／このRRBridgeプロセス中の書込失敗試行の累積数。falseは過去の欠測ゼロを示さない |
| `lastWriterFsyncAtMs` / `lastWriterFailureAtMs` | 最後に成功したTSV fsync／書込障害を観測した時刻（epoch ms）。まだ発生していなければnull。これらはプロセス再起動を跨いで復元されない |
| `sendErrors` / `lastSendAt` / `lastSendResult` | 送信失敗回数／最後の送信時刻／最後の応答（HTTPコード、本文、所要時間、バイト数） |
| `sink` | 送信先URL（空なら `(spool only)`） |

### C4.2 `link`（RomRaiderのログから数えた通信状態）
| キー | 意味 |
| --- | --- |
| `attach` | ログ監視の取り付け結果 |
| `ecuId` | 最後に応答したECUのID（例 `2F04785006`、未応答なら空） |
| `connects` | J2534接続の確立回数（`connection initialised`） |
| `ecuInits` | ECU応答回数（`ECU ID = `） |
| `errors` | 通信エラー回数（ECU無応答時は1試行で約2件） |
| `lastError` / `lastErrorAt` | 直近のエラー文言と時刻 |

### C4.3 `guard`
`{enabled, minRateHz, checkMs, maxParams, currentProfile, lastAction}`。`lastAction` は `armed …`／`ok … rate …Hz`／`cancelled (another switch)`／`guard_revert …` のいずれか。

実例（2026-10-12 14:08、X270、ECU無応答）:
```json
{"agent":"RRBridge 0.5.0","time":"2026-10-12T14:08:14.470+09:00","ready":true,"logging":false,"fileLogging":false,
 "title":"RomRaider v1.1.0 NOV26 2025 | ECU/TCU Logger - Profile: C:\\User...\\profiles\\basic.xml",
 "outputDir":"C:\\Users\\zawa\\Documents\\OpenPortWork\\Logs",
 "newestCsv":{"name":"romraiderlog_20261012_125913.csv","bytes":175,"modified":"2026-10-12T12:59:37.759+09:00"},
 "lastCommand":"logging_stop @2026-10-12T13:57:26.069+09:00",
 "stream":{"installed":true,"installResult":"installed (handlers=10, session=20261012_135639, sink=http://127.0.0.1:18766/ingest)",
   "session":"20261012_135639","segment":0,"samples":0,"linesProduced":10,"linesDropped":0,"linesSent":10,"queue":0,"pendingBytes":0,
   "sendErrors":0,"sink":"http://127.0.0.1:18766/ingest","lastSendAt":"2026-10-12T13:57:31.202+09:00",
   "lastSendResult":"200 {\"ok\":true,\"samples\":0,\"measurements\":0,\"events\":1,\"duplicates\":0,\"maxLagMs\":0} (17ms, 123B)",
   "lastSampleAgeMs":null,"realSamples":0,"params":0,"rateHz10s":0.00,"intervalP95Ms10s":-1},
 "link":{"attach":"attached 2026-10-12T13:56:50.708+09:00","ecuId":"","connects":7,"ecuInits":0,"errors":14,
   "lastError":"Error sending init: Parameter portName must not be null or empty","lastErrorAt":"2026-10-12T13:57:28.017+09:00"},
 "guard":{"enabled":true,"minRateHz":2.0,"checkMs":10000,"maxParams":20,"currentProfile":"basic.xml","lastAction":""}}
```

キーの追加はありうる。読む側は知らないキーを無視すること。キーの削除・意味の変更はこの文書の更新とバージョンアップを伴う。

---

## C5. プロファイル（`<rrbridge.dir>\profiles\*.xml`）

| 項目 | 規約 |
| --- | --- |
| 置き場所 | `<rrbridge.dir>\profiles\` 直下のみ。サブフォルダ不可 |
| ファイル名 | 正規表現 `[A-Za-z0-9_.-]+\.xml`（日本語・空白・パス区切り不可） |
| 形式 | RomRaider のユーザープロファイルXML（ISO-8859-1）。`<profile protocol="SSM">` 下の `<parameters>`／`<switches>`／`<externals>` |
| 選択 | `livedata="selected"` が付いた要素だけが取得対象（`graph`／`dash` は任意） |
| ID | Logger定義 v370 のID（`P8`=Engine Speed、`P2`=Coolant Temperature、`E…`=ECU固有拡張、`S…`=スイッチ） |
| 単位 | `units` 属性は Logger定義の換算名と一致させる（例 `rpm`、`C`、`Lambda`） |
| 上限 | 選択数が `profile.maxParams`（既定20）を超えると読み込まない。0件も不可 |
| 追加・変更の権限 | 人（またはSupervisorが検証の上で）。MCP・サーバーからは書き込まない |

最小例（`profiles/basic.xml`）:
```xml
<?xml version="1.0" encoding="ISO-8859-1"?>
<profile protocol="SSM">
    <parameters>
        <parameter id="P8" units="rpm" livedata="selected"/>
        <parameter id="P9" units="km/h" livedata="selected"/>
        <parameter id="P13" units="%" livedata="selected"/>
        <parameter id="P25" units="kPa" livedata="selected"/>
        <parameter id="P2" units="C" livedata="selected"/>
        <parameter id="P11" units="C" livedata="selected"/>
    </parameters>
</profile>
```

周期の目安（2026-09-21 実車）: 8項目 8.0Hz、9〜10項目 6.0〜6.3Hz、12〜14項目 3.3〜4.3Hz、18〜19項目 2.6〜2.9Hz。4バイト拡張項目（名前に `(4-byte)*`）は1項目あたりの負荷が大きい。

---

## C6. 動作設定（`<rrbridge.dir>\rrbridge.properties`、任意）

| キー | 既定 | 範囲・意味 |
| --- | --- | --- |
| `http.token` | 空 | C3 の Bearer トークン。空なら認証なし |
| `switch.settleMs` | 1500 | 0〜10000。`switch_profile` で読込後CSV記録を再開するまでの待ち |
| `log.maxBytes` | 5242880 | `rrbridge.log` の上限。超えたら `.1` に退避（1世代） |
| `spool.retentionDays` | 14 | **互換設定値として読取のみ**。2026-10-13から元TSVの自動削除は安全のため無効。旧`.sent`だけでは製品ServerのACKを保証できない |
| `profile.maxParams` | 20 | 選択項目数の上限（0で無制限） |
| `guard.enabled` | true | 自動復帰ガード |
| `guard.minRateHz` | 2.0 | これを下回ったら取れていないとみなす |
| `guard.checkMs` | 10000 | 3000〜60000。切替（＋settleMs）後に速度を測る時間 |
| `guard.fallbackProfile` | 空 | 直前のプロファイルが不明なときの戻し先 |

起動時に1回だけ読む（変更したらRomRaiderを再起動）。システムプロパティ: `-Drrbridge.dir=<フォルダ>`（既定 `%USERPROFILE%\Documents\OpenPortWork\RRBridge`）、`-Drrbridge.port=<番号>`。

## C7. 転送先設定（`<rrbridge.dir>\sink.properties`）

| キー | 既定 | 意味 |
| --- | --- | --- |
| `url` | `http://127.0.0.1:18766/ingest` | C9 の送信先。空にすると送信せずspoolのみ |
| `token` | 空 | C9 の `Authorization: Bearer <token>`。空なら付けない |

**このファイルはコミットしない**（`.gitignore` 済み）。起動時に1回だけ読む。

---

## C8. 転送データ形式（spool と C9 の本文で共通）

### C8.1 共通規約
- 文字コード UTF-8、改行 LF、1行1レコード、列区切りはタブ（`\t`）。
- 1列目がレコード種別。列数が足りない行、未知の種別の行は**受信側で無視**する（エラーにしない）。新しい種別を追加しても古い受信側が壊れないようにするため。
- 値の中のタブ・改行は送信側で空白に置換済み。S行の `param_id` には `,` と `=` を含まない（`_` に置換済み）。
- 時刻はすべて X270 の時計による epoch ミリ秒（UTC基準の整数）。
- `session`: RRBridge起動ごとに変わる文字列（`yyyyMMdd_HHmmss`、X270ローカル時刻）。
- `segment`: セッション内の区間番号（整数、0始まり）。サンプルの項目IDの集合が変わるたびに1増える。selftest も1区間使う。プロファイル切替中は項目が1つずつ登録・解除されるため、中間状態の短い区間ができることがある。
- `seq`: セッション内のサンプル通番（1始まり、単調増加、欠番なし）。**`(session, seq)` がサンプルの一意キー**。

### C8.2 レコード種別

| 種別 | 列 | 意味 |
| --- | --- | --- |
| `M` | `M`, session, segment, param_id, name, units | 項目定義。区間が始まるときに、その区間の全項目分を S行より前に出す |
| `S` | `S`, session, segment, seq, epoch_ms, values | サンプル。values は `id=値` を `,` 区切り（ID昇順）。値は換算済み数値（Java の `Double.toString`、例 `750.0`、`1.0E-4`） |
| `E` | `E`, session, segment, epoch_ms, text | イベント。text の語彙は §C8.3 |

例（実際のspoolより）:
```
M	20261012_125843	1	TEST_RPM	Selftest Engine Speed	rpm
S	20261012_125843	1	160	1791777576260	TEST_RPM=1811,TEST_TPS=21.18,TEST_MRP=-11
E	20261012_125843	1	1791777577763	filelog_stop RomRaider v1.1.0 NOV26 2025 | ECU/TCU Logger - Stopped logging to file
E	20261012_135639	0	1791781040695	health rate_hz=0.00 samples=0 params=0 interval_p95_ms=-1 link_errors=8 link_connects=5
```

### C8.3 イベント（E行 text）の語彙
text は「先頭の語＝種類」＋空白区切りの続き。`key=value` 形式の部分は機械的に読める。

| 先頭の語 | 続き | 発生元 | 発生条件 |
| --- | --- | --- | --- |
| `logging_start` | — | Agent | `logging_start` 実行 |
| `logging_stop` | — | Agent | `logging_stop` 実行 |
| `filelog_start` | RomRaiderのタイトル（CSVファイル名を含む） | Agent | CSV記録開始 |
| `filelog_stop` | RomRaiderのタイトル | Agent | CSV記録停止 |
| `switch_profile` | プロファイル名 | Agent | 切替成功 |
| `load_profile` | プロファイル名 | Agent | 読込成功 |
| `guard_revert` | `<新> -> <戻し先> rate=<Hz>Hz < <下限>Hz (before <Hz>Hz, link_errors=<n>)` | Agent（ガード） | 切替後に速度が下限未満 |
| `health` | `rate_hz=<小数> samples=<整数> params=<整数> interval_p95_ms=<整数> link_errors=<累計> link_connects=<累計>` | Stream | 10秒ごと（サンプルもエラー増加もない間は出さない） |
| `link_error` | `count=<累計> <RomRaiderのエラー文言>` | LinkMonitor | 通信エラー（10秒に1回まで） |
| `selftest` | `start count=<n> hz=<n>` ／ `end` | Stream | `selftest_stream` |

受信側（server）は、`source` 列に `rrbridge` を入れて保存する。運転者メモ（C12 の `add_event_marker`）は `source=driver`。

### C8.4 spool ファイル
- `<rrbridge.dir>\spool\rr_<session>.tsv`: 追記のみ。転送の正本。
- `<…>.tsv.sent`: 送信済みバイト位置（10進整数1行）。行の途中では止まらない。
- **旧TSVは自動削除しない**。2026-10-13より、従来の`.sent`を根拠にした削除は製品v1 Workerの原本損失につながるため停止。管理者が製品SQLite取込済み・Server ACK・バックアップを別途検証するまでは保持する。

---

## C9. 転送HTTP（RRBridge → 受信側）

| 項目 | 規約 |
| --- | --- |
| リクエスト | `POST <sink.properties の url>` |
| ヘッダー | `Content-Type: text/tab-separated-values; charset=utf-8`、`Authorization: Bearer <token>`（token 設定時） |
| 本文 | C8 の行の並び（spool の未送信部分、最大512KB、行の途中で切らない） |
| タイムアウト | 接続3秒、読み取り10秒 |
| 成功 | HTTP 2xx。送信済み位置を本文の末尾まで進める |
| 失敗 | 2xx以外・接続失敗・タイムアウト。位置を進めず、2秒後に同じ範囲を再送 |
| 送信間隔 | 未送信がある限り連続、なければ500ms待機 |
| 順序 | spool ファイル名の昇順（＝セッション順）、ファイル内は先頭から |
| 重複 | **同じ行が複数回届くことがある**（応答が失われた場合など）。受信側は `(session, seq)` で冪等にすること |
| 受信側への要求 | 2xxを返すのはDBへの保存が確定（commit）した後。応答本文の形式は自由（RRBridgeは記録するだけ） |

---

## C10. Java受信サーバー（`rrreceiver.jar`、検証用）

起動: `java -cp rrreceiver.jar;sqlite-jdbc.jar net.neristal.rrbridge.Receiver [db] [bind] [port] [token]`（既定 `telemetry.db 127.0.0.1 18766` 認証なし）。X270では `start-receiver.bat`（`data\telemetry.db`、ログ `data\receiver.log`）。

| パス | メソッド | 認証 | 応答 |
| --- | --- | --- | --- |
| `/ingest` | POST（他は405） | token（設定時） | `{"ok":true,"samples":n,"measurements":n,"events":n,"duplicates":n,"maxLagMs":n}`。エラー時 500 `{"ok":false,"error":…}`（ロールバック） |
| `/health` | 任意 | なし | `{"ok":true,"service":"rrbridge-receiver","time":…}` |
| `/stats` | 任意 | token（設定時） | `{"ok":true,"sessions":[{session,samples,firstT,lastT,avgLagMs,maxLagMs}],"latest":[{name,units,value,t}]}` |

`events` は実際に挿入した件数、`duplicates` は重複した S 行の数、`maxLagMs` は受信時刻−サンプル時刻の最大。DBは C13 と同じスキーマ（`events.source` 列なし）。

## C11. Python受信（`server/` の `/ingest`、`/health`）

| パス | メソッド | 認証 | 応答 |
| --- | --- | --- | --- |
| `/ingest` | POST | `INGEST_TOKEN`（空なら認証なし）。`hmac.compare_digest` で比較 | `{"ok":true,"samples":n,"measurements":n,"events":n,"duplicates":n,"rejectedLines":n,"maxLagMs":n}`、401 `{"ok":false,"error":"unauthorized"}`、500 `{"ok":false,"error":…}` |
| `/health` | GET | なし | `{"ok":true,"service":"rrbridge-server","version":"0.1.0","db":"postgres"|"sqlite"}` |

- `rejectedLines` は形式不正・未知種別の行数（無視した行）。Java版はこれを返さない。
- 公開時は Caddy が `/ingest`、`/mcp`、`/mcp/*`、`/health` 以外を 404 にする。
- 環境変数: `DATABASE_URL`、`INGEST_TOKEN`、`MCP_TOKEN`、`ALLOWED_HOSTS`（`server/README.md`）。

---

## C12. MCP（AIクライアント → `server/` の `/mcp`）

| 項目 | 規約 |
| --- | --- |
| トランスポート | MCP Streamable HTTP、**stateless**（セッションIDなし）、**JSONレスポンス**（SSEなし）。`POST /mcp`、本文は JSON-RPC 2.0 |
| ヘッダー | `Accept: application/json, text/event-stream`、`Content-Type: application/json`、`Authorization: Bearer <MCP_TOKEN>` |
| 認証失敗 | HTTP 401 `{"error":"unauthorized"}`、`WWW-Authenticate: Bearer` |
| Host検査 | `ALLOWED_HOSTS` 設定時、Host／Origin が一致しないと拒否（DNSリバインディング対策） |
| サーバー名 | `rrbridge-telemetry`（`serverInfo.name`）。`instructions` に使い方と注意（最初に状態確認、根拠不足は明言、車両は操作不可、2Hz未満の構成を提案しない、運転中は短く） |
| 実装 | Python `mcp` 2.3.0（`MCPServer`） |
| 接続元の要件 | AIクライアントは各社クラウドから接続するため、インターネットから到達可能なHTTPS URLが必要（`02_architecture.md` §2.1） |

### C12.1 ツール

すべて `annotations` 付き。読み取り系は `readOnlyHint=true, destructiveHint=false, idempotentHint=true, openWorldHint=false`。

| ツール | 引数（既定） | 返り値（主なキー） | 種別 |
| --- | --- | --- | --- |
| `get_vehicle_status` | なし | `available`、`session`、`segment`、`lastSeq`、`lastSampleAgeSec`、`transferLagMs`、`receiving`（10秒以内に受信）、`latest[{name,units,value,id}]`、`acquisition`（下と同じ）、`recentEvents[{agoSec,text,source}]`（5件） | 読取 |
| `get_acquisition_health` | `window_sec`（10） | `rateHz`、`params`、`samplesInWindow`、`minRateHz`（2.0）、`ok`、`warnings[]`、`lastHealth`（health イベントを分解した dict）、`lastGuardRevert`、`lastLinkError` | 読取 |
| `list_sessions` | `limit`（10、1〜100） | `{"sessions":[{session,samples,durationSec,segments}]}` | 読取 |
| `list_segments` | `session`（最新） | `{"session":…,"segments":[{segment,samples,durationSec,rateHz,parameters[名称]}]}` | 読取 |
| `get_segment_stats` | `session`（最新）、`segment`（最新）、`last_seconds`（全体） | `samples`、`durationSec`、`rateHz`、`parameters[{name,units,id,count,min,max,mean,stddev}]` | 読取 |
| `get_recent_values` | `parameter`（必須、IDか名称の部分一致）、`seconds`（30）、`max_points`（120） | `name`、`units`、`id`、`points[{tSec,value}]`（tSecは最新からの相対秒、負）、`totalPoints`、`step` | 読取 |
| `list_events` | `session`（最新）、`limit`（20、1〜200） | `{"events":[{agoSec,segment,text,source}]}` | 読取 |
| `query_telemetry` | `sql`（必須）、`max_rows`（200、1〜500） | `columns`、`rows`、`rowCount`、`truncated` | 読取（下の制限） |
| `add_event_marker` | `text`（必須、最大500文字） | `ok`、`session`、`segment`、`text` | **書込（追記のみ）**。`readOnlyHint=false, destructiveHint=false` |

`warnings` の文言: 取得周波数が下限未満／最後のサンプルから15秒以上届いていない／直近1分以内に `link_error`。

### C12.2 `query_telemetry` の制限
- 1文のみ（`;` 不可）、`SELECT` または `WITH` で始まる。
- 禁止語（文字列リテラル内を除く）: insert, update, delete, drop, alter, create, replace, truncate, attach, detach, pragma, vacuum, grant, revoke, copy, call, do, execute, prepare, listen, notify, set, reset, lock, reindex, analyze, cluster, comment, security, load。
- PostgreSQL: `SET TRANSACTION READ ONLY` ＋ `statement_timeout`。SQLite: `mode=ro` 接続＋進捗ハンドラー。いずれも3秒で打ち切り（`TimeoutError`）。
- 最大500行、超過は `truncated=true`。
- 違反時はツールエラー（MCPの `isError: true`）。

### C12.3 MCPから**できないこと**
RomRaider・RRBridge・車両の操作（ロギング開始停止、プロファイル切替、設定変更）は一切できない。計測計画の提出を将来追加する場合も「提案の保存」に限り、適用は X270 の Supervisor が検証して行う（C14、D-020）。

---

## C13. DBスキーマ（受信側の保存形式、`query_telemetry` の契約）

```sql
sessions    (session TEXT PRIMARY KEY, first_received_ms BIGINT, last_received_ms BIGINT)
parameters  (session TEXT, segment INTEGER, param_id TEXT, name TEXT, units TEXT,
             PRIMARY KEY(session, segment, param_id))
samples     (id <自動採番> PRIMARY KEY, session TEXT, segment INTEGER, seq BIGINT,
             t_ms BIGINT, received_ms BIGINT, UNIQUE(session, seq))
measurements(sample_id BIGINT, param_id TEXT, value DOUBLE PRECISION, PRIMARY KEY(sample_id, param_id))
events      (session TEXT, segment INTEGER, t_ms BIGINT, text TEXT, received_ms BIGINT,
             source TEXT DEFAULT 'rrbridge',   -- Python版のみ（Java版DBには無い。Python版が開くと追加）
             UNIQUE(session, t_ms, text))
INDEX samples_t (session, t_ms), samples_seg (session, segment)  -- samples_seg は Python版のみ
```

- `t_ms` は X270 の時計、`received_ms` は受信サーバーの時計（epoch ms）。差が転送遅延（時計がずれていればその分ずれる）。
- 値の取り出し: `measurements` を `samples`（`sample_id = samples.id`）と `parameters`（`session, segment, param_id`）に結合して名称・単位を得る。
- 典型クエリ（区間内のエンジン回転数の推移）:
```sql
SELECT x.t_ms, m.value FROM samples x
JOIN measurements m ON m.sample_id = x.id
JOIN parameters p ON p.session = x.session AND p.segment = x.segment AND p.param_id = m.param_id
WHERE x.session = '20261012_125843' AND p.name = 'Engine Speed' ORDER BY x.t_ms
```

---

## C14. 将来の接続口（未実装、構想）

| ID | 接続口 | 想定 | 決めること |
| --- | --- | --- | --- |
| C14a | Supervisor → RRBridge | C2 または C3（`http.token` を設定して使う） | 実装言語、常駐方法 |
| C14b | Supervisor → server（計測計画の取得） | X270発のHTTPSポーリング（X270への着信はさせない） | 計画の形式（プロファイル名＋区間条件＋期限）、承認状態、応答 |
| C14c | MCP → server（計画の提案） | `propose_measurement_plan`（保存のみ） | 検証規則（項目数・バイト数・既存プロファイルとの対応） |
| C14d | 既存CSV → DB（ETL） | `archive/romraider_logs/` を C8 形式に変換して `/ingest` | CSV列名 → param_id の対応（Logger定義から） |

---

## 15. 互換性の規則

1. **追加は自由、削除・変更は慎重に。** JSONのキー、イベント語彙、MCPツールの返り値は追加してよい。読む側は知らないキー・種別・語を無視する。削除・意味変更・型変更は、この文書・両受信側・ドキュメントを同時に直し、RRBridgeのバージョンを上げる。
2. **C8（転送形式）を変えるときは、Java `Receiver`、Python `ingest.py`、この文書を同じコミットで直す。** 未知の種別を無視する性質を保つ。
3. **冪等性を壊さない。** `(session, seq)` の一意性、E行の `(session, t_ms, text)` の一意性を前提に再送している。
4. **セキュリティ上の既定値を弱めない。** 操作系HTTPを外部に公開しない（127.0.0.1固定）。MCPに車両・ロガーを操作するツールを足さない。`profile.maxParams` とガードを弱める変更はユーザーの確認を取る。
5. **時刻**: 機械向けは epoch ms、人向け（status の `time` など）は ISO 8601＋オフセット。

## C15. Isolated RLC2 Supervisor diagnostics (2026-10-18)

IngressDiagnostics.inspect(session, existing closed ledger snapshot, ReadOpener, previous manifest) returns immutable structured findings. CLI arguments: SESSION LEDGER [--json]; schema_version=1, stable code/segment/cause, ack_eligible=false, directory_durability=UNKNOWN. Exit 0 inspected (not ACK authorization), 5 review/unknown, 2 usage. Active SQLite WAL/SHM/journal is refused; immutable read-only query avoids companion creation. Windows isolated CLI uses existing test-only native reader. Details: docs/acceleration_diagnostics.md. Existing product CLI/Worker are unchanged.


## C16. RLC2 synthetic product projection — 2026-10-18 11:54:12 +09:00

Rlc2ProductAdapter.project requires enableSynthetic=true, synthetic-/TEST_ inputs and exact marker/ledger alignment. Returns immutable sources, original receipts, shared v1 profiles/records; ackEligible=false. IncrementalTsvBridge.projectRows/ProjectionSink expose the unchanged conversion independently of database I/O. No Worker or transport activation. Current v1 single-source claim/cursor format is unchanged; multi-source persistence must stay in preview tables. Mapping and limits: rlc2_product_projection.md.


## C17. Isolated RLC2 preview store/CLI and test PostgreSQL — 2026-10-18 12:10:37 +09:00

Rlc2PreviewStore requires explicit synthetic enable and a dedicated .rlc2-preview.db; existing files require RLP1 application ID and preview-only tables before writable access. Six preview tables atomically retain ownership/history/highwater/receipts/shared v1 JSON; CHECK ack_eligible=0. CLI: --enable-synthetic-preview SESSION LEDGER DEVICE VEHICLE OUTPUT; exits0 stored,5 review,2 usage. No transport command. PostgreSQL test has exact opt-in/loopback/test-name/URI override guards and uses only an invocation-owned random schema. See rlc2_preview_e2e.md.


### C17 output-only abrupt restart extension — 2026-10-18 12:22:40 +09:00

Existing positively identified RLP1 output with a safe rollback journal can undergo SQLite normal rollback recovery before schema verification. Immutable input ledger rules remain unchanged. Unknown application/schema or unexpected WAL/SHM refuses; no manual journal removal, original repair or ACK permission. The same explicit CLI/review exit codes apply. See D-051 and rlc2_preview_e2e.md.


## C18. Development-only acceleration batch — 2026-10-18 12:46:00 +09:00

PowerShell7 scripts/run-acceleration-tests.ps1 selects configurable JDK17 x64/Zulu8 x86/Git Bash/Python, restores test environment and writes ignored build logs. Optional -Postgres requires an inherited protected loopback test DSN and downstream strict destination guard; -ServerPath requires actual local Server model validation. Java/Python byte hashes and PG CI image digest are pinned. It never enables new-path sending. See acceleration_test_automation.md.


## C19. Read-only recovery plan and migration preflight — 2026-10-18 13:18:30 +09:00

IngressDiagnostics JSON schema1 additively includes automatic_repair_allowed=false and per-finding next_action (fixed manual hint only). --migration-preflight SOURCE.rlj [--json] inspects existing legacy/new envelopes and prefixes without opening/creating a ledger, creating markers, choosing mixed formats or migrating. Intact RLC1 still exits5 pending explicit identity/migration contract. Normal session diagnostics add mixed-format refusal and LEDGER_BEHIND. Load fixtures accept bounded ROMLOGGER_LOAD_OBSERVATIONS/ROMLOGGER_LOAD_SESSIONS; no runtime product activation. Details: ingress_preflight_load.md.


## C20 — RLC2 integration storage contract v2 (candidate)
- Dedicated opt-in .rlc2-integration.db, RLI2 application ID/user_version1, eight tables with logical session and explicit physical-source admissions. Existing product v1 DB/HTTP/Worker/ACK behavior unchanged. See interfaces/rlc2_integration_v2.md; requires offline/quiescent legacy inventory, no live cross-DB exclusion guarantee.

## C21 — Offline integration engine API
- Rlc2IntegrationStore.ingest requires explicit external quiescence, Admission(directory,manifestSession,sourceUuids), closed ledger and legacy inventory. It writes only dedicated integration format through shared atomic preview backend, returns recordsAdded/receiptsAdded/ackEligible=false. No persistent ACK transition.

## C22 — Supervisor axes and strict output ownership
- Additive diagnostic Supervisor contract v2 keeps legacy schema_version1/exit codes; operational_ready=false, manual_intervention_required=true, independent inspection/source_integrity/source_review fields. ACK false/repair false/durability UNKNOWN unchanged. See acceleration_diagnostics.md.
- Output schema verification includes full SQLite objects/constraints/indexes/version/quick_check/FK check; old legitimate v0 DDL supported. No schema repair or production migration.

## C23 — Explicit integration CLI and optional real HTTP test
- CLI requires both --enable-synthetic-integration/--quiescent plus exact session/source admissions and separate dedicated output; exit0 STORED_OFFLINE never permits ACK. Windows native wrapper stays test-only. Contract: interfaces/rlc2_integration_v2.md.
- Windows batch -RealServerHttp requires -Postgres/-ServerPath and protected disposable DSN. Actual own Uvicorn/PG schema plus response-drop/faults; test ACK never updates Agent. Existing Worker/HTTP/Server production schema remain unchanged.

## C24 — Owned PostgreSQL process-stop test contract
- scripts/test-rlc2-owned-postgres-http.py requires ROMLOGGER_OWNED_PG_HTTP_ENABLE=1, verified ROMLOGGER_OWNED_PG_BIN and actual ROMLOGGER_SERVER_PATH. Only a newly created build/romlogger-http-pg-* data directory is initialized/stopped/restarted; synthetic credentials stay internal, initialization password file removed. No shared or production PostgreSQL process is stopped.
- Real HTTP test uses an invocation-owned schema and connect_timeout=2 only in its constructed test DSN. Actual daemon-stop503, restart/persisted-record verification and unchanged Agent ACK bytes remain test-only. This is process crash recovery, not physical power loss.

## Phase II local lane ownership candidate v1
[RLLO1 contract](interfaces/lane_ownership_v1.md): cooperative local ownership only; old Worker compatibility is limited to its exact retained .worker.lock. No wire/ACK change.

Phase II: ROMLOGGER_SYNTHETIC_LANE_ROOT opts in isolated product scopes; LaneOwnershipStatus --status emits advisory state/exits without recovery. RMI1 .lane-migration.db plus ownership sidecar is separate copied inventory; v1 tables/HTTP wire unchanged. [Full API/order/schema guards/exits](interfaces/lane_ownership_v1.md).

## Phase III RLDB1 deployment preflight
[Deployment binding v1](interfaces/deployment_binding_v1.md): opt-in paired root/legacy anchor, bound synthetic entry, read-only CLI JSON/exits0/2/5. No wire/schema/ACK/default Worker change.

Phase III: CutoverReadiness adds bounded read-only current-build JAR classset/old-lock/v1 inventory/latch diagnosis; exits0/2/3/5. [Contract and manual dry-run](ops/cutover_readiness_v1.md). No automatic stop or cutover.

Phase III RLBA1: [backup/rehearsal contract](interfaces/isolated_backup_acceptance_v1.md), copied-target CLI --inspect-isolated-copy EVIDENCE TARGET (exits0/2/5, activation false), read-only RMI1 verifyIsolatedCopy and synthetic Java rehearsal. No product migration/restore/ACK activation.

Phase III copy-state CLI/staged RMI1 API: [fault acceptance v1](interfaces/migration_copy_fault_acceptance_v1.md). Explicit external-quiescent/no-apply, existing READ lock observations, exits0/2/3/5, no repair/ACK/activation. Partial copies and committed-but-uncheckpointed WAL are conservatively classified.


## Phase V RLPC1/RLLV1 isolated evidence hardening

[RLPC1 contract](interfaces/legacy_v1_rli2_provenance_v1.md): journal cursor owner, exact absolute safe original path, integer sequence and RLC1 prefix/snapshot must agree. Whole-session conflicts block staging; canonical IDs/legacy ACK are unchanged. `IncrementalTsvBridge.journalCursorEvidence` is a read-only payload comparison helper, never an RLC2 receipt/highwater converter. Staging fixes a projection/input snapshot, validates all typed Vault columns/schema and all eight exact RLI2 table sets. Atomic private work-directory claim and no-replace completed-file publication preserve existing/partial artifacts. Targets require the same existing private local parent. `stagingState` success is `STAGED_LOCAL_HASHES_MATCH_INACTIVE`, solely local hashes; independent verification and current originals remain required. Formal ACK/production authorization stay false.
# Phase VI additive synthetic diagnostics

RLPC1 staging now revalidates captured native physical identities of its parent/work directory before staging writes/publication/marker creation; same-spelling replacement refuses without opening a competitor's internal file. `DeploymentBinding.fileIdentity(file)` exposes the existing read-only native identity after strict path checks. No marker/storage version or Server wire changes.

[RLDT1](interfaces/deployment_transition_v1.md) prepare records add exact old/new Lane/Publication lock basenames for read-only retained abandon observations. Old completed generations remain readable; old incomplete prepares without lock-set evidence fail closed for manual review. Historic binaries do not enforce this extension.

[Offline candidate preflight v1](interfaces/offline_candidate_preflight_v1.md) adds a default-off read-only API joining artifact/runtime, RLDT1 generation, manifest/admission and retained existing lock observations. Results remain BLOCKED/launch_authorized=false with independent writer/process/durability axes. It launches no Worker or Sender and does not modify existing product entry contracts.

RLDB1/RLDB2 layout description now checks every distinct Windows drive root with read-only GetDriveTypeW plus stable QueryDosDeviceW observations before traversing any layout path. Only Fixed (3) with a local HarddiskVolume mapping passes; subst, Remote, Unknown, unsupported/timeout/malformed/duplicate replies refuse. `DeploymentBinding.safeLocal(file,probe)` exposes this policy for injected tests; low-level `safe(file)` retains path/alias/filesystem checks and alone is not a fixed-drive proof. No result is cached across layout calls. Linux behavior is unchanged.

RLPC1 adds independent writer_quiescence=WRITER_UNPROVEN, operational_readiness=BLOCKED and synthetic_only=true fields. New read-only LegacyCandidateGate.inspect/requireWriterProof/CLI fail closed without writable DB probes, recovery, Worker/Sender or ACK. See [RLPC1](interfaces/legacy_v1_rli2_provenance_v1.md). Existing content classification and inactive staging are not writer-exclusion proof.

Phase VI final drive entry correction: RLDT1 plan and commit now call `DeploymentBinding.requireLocalDrives` before chain/lock I/O. This is a read-only observation of roots, not a permission or global-fencing token. Later layout checks retain the existing proof. Additive utility API only; no Server/storage/wire change.
