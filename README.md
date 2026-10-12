# RomRaider_agent（RRBridge）

RomRaider Logger を外部から操作し、ロギング中のデータをリアルタイムにサーバーへ転送するための Java エージェントです。
RomRaider 本体には手を加えず、`-javaagent` で同じJVMに読み込ませて動かします。

> **AIエージェントへ**: 作業前に [`AGENTS.md`](AGENTS.md) と [`docs/00_index.md`](docs/00_index.md) を読んでください。経緯は [`docs/07_history.md`](docs/07_history.md)、決定理由は [`docs/08_decisions.md`](docs/08_decisions.md) にあります。

## 1. 位置づけ

スバル レガシィ BP5（EJ20X、SSM/K-Line）を RomRaider Logger＋OpenPort 2.0互換機で計測し、最終的には
「走行中に LLM が次に取るべき項目を判断して計測を切り替える」「スマホの音声モードから MCP 経由でログを解析する」ことを目指すプロジェクトの一部です。
このリポジトリは、車載PC（ThinkPad X270、Windows 10）上で RomRaider を操作し、データを取り出す部分（Adapter）を担います。
全体構成は [`docs/02_architecture.md`](docs/02_architecture.md)。

## 2. 状態（v0.5.0、2026-10-12）

| 機能 | 状態 |
| --- | --- |
| 状態取得、ロギング開始・停止、CSV記録開始・停止 | 実機確認済み |
| プロファイル読込（`profiles/` 内のみ） | 実機確認済み |
| `switch_profile`（記録停止→読込→再開で1区間1CSV） | 実機確認済み |
| リアルタイム転送（内部配信 → spool → HTTP → SQLite） | 合成データで確認済み（平均273ms・最大533ms。受信停止中の80件を復旧後に全件再送）。**実ECUデータは未確認** |
| 取得速度の送信（`health` イベント10秒ごと、`status.stream.rateHz10s`）と通信エラーの計数（`status.link`） | 実機確認済み（ECU無応答時のエラー計数） |
| 項目数の上限（`profile.maxParams`、既定20）での読込拒否 | 実機確認済み |
| 切替後に取得速度が下がったら直前のプロファイルへ自動復帰（`guard.*`） | 単体試験のみ（ECUデータが無いため実機未確認） |
| 自己試験（`./test.sh`、GUIなしで動く範囲44項目） | 全件PASS |
| 受信API＋Remote MCPサーバー（`server/`、Python） | 試作。SQLite／PostgreSQL 16で自動試験32件PASS、X270の実spoolを取り込みMCPで照会できることを確認。VPS未配置 |
| Supervisor、LLMの無人起動 | 未着手 |

検証の詳細: [`docs/06_verification.md`](docs/06_verification.md)。未解決事項: [`docs/09_open_issues.md`](docs/09_open_issues.md)。

## 3. 仕組みの要点

- `EcuLogger.getEcuLoggerWithoutCreation()`（public static）でロガー本体を取得し、`startLogging()`／`stopLogging()`／`loadUserProfile()` などの公開メソッドを呼ぶ。
- CSV記録は「Start file log」ボタン（private `logToFileButton`）を `doClick()` する。GUI操作と同じ処理が走る。
- データは `QueryManagerImpl.updateHandlers`（private 配列）に自前の `DataUpdateHandler` を追加して受け取る。CSVを経由しないため、記録のオン・オフと無関係に1応答＝1サンプルの換算値が届く。
- 受け取ったデータはローカルの spool（TSV）に追記してから HTTP POST する。送信済み位置を記録し、送信先が止まっていても復旧後に続きから再送する。受信側は `session+seq` で重複を除く。
- 依存しているRomRaider内部の一覧（バージョンを上げたら要確認）: [`docs/04_romraider_internals.md`](docs/04_romraider_internals.md)。

ECUリセット、DTC読出・消去、ROM読み書き、学習値リセットなど、車両に影響する操作は公開していません。

### K-Lineの帯域を守る仕組み（v0.5.0〜）

SSM（K-Line）は1周期で全項目を問い合わせるため、項目が多いほど周期が延び、多すぎると応答待ちのタイムアウトで**全データが取れなくなる**（2026-09-21 の実車で発生）。
MCP経由の設定変更でこの事故が起きないよう、三重に防いでいます。

1. **MCPには設定を変える道具がない**: `server/` のMCPツールは読み取りと運転者メモの追記だけ。プロファイルの切替はX270上のRRBridgeへのコマンド（ローカルのみ）でしかできず、選べるのは `profiles/` に置いたファイルだけ。
2. **項目数の上限**: `profile.maxParams`（既定20）を超えるプロファイルは読み込まずにエラーを返す。`list_profiles` の `allowed` で事前に分かる。
3. **自動復帰**: 切替前に `guard.minRateHz`（既定2Hz）以上で取れていたのに、切替後 `guard.checkMs`（既定10秒）の平均が下回ったら、直前のプロファイルへ自動で戻し、`guard_revert` イベントを送る。

速度と通信状態は、`status`（`stream.rateHz10s`、`stream.intervalP95Ms10s`、`link.errors` など）、10秒ごとの `health` イベント、通信エラー時の `link_error` イベントで外部（受信サーバー・MCPの `get_acquisition_health`）から見える。

## 4. リポジトリ構成

```
AGENTS.md / CLAUDE.md      AIエージェント向けの作業ルール
README.md                  このファイル
build.sh                   ビルド（dist/ に配置一式を出力）
test.sh                    自己試験（RomRaiderのGUIなしで動く範囲）
src/main/java/net/neristal/rrbridge/
  Agent.java               -javaagent の入口。コマンド受付（inbox/ と HTTP 127.0.0.1:18765）、status.json
  Stream.java              データ配信への取り付け、spool追記、HTTP転送、合成データ試験
  Receiver.java            検証用受信サーバー（Telemetry API 試作）。TSV → SQLite
  LinkMonitor.java         RomRaiderのlog4j出力から通信状態（接続、ECU応答、エラー）を数える
  Util.java                RomRaider非依存の共通処理（受信サーバー用）
src/main/resources-manifest.txt  Premain-Class
src/test/java/net/neristal/rrbridge/SelfTest.java  自己試験（JUnit不要）
profiles/                  テスト用プロファイル（basic.xml 6項目 / fuel.xml 7項目）
scripts/
  start-romraider-with-bridge.bat  RomRaiderをRRBridge付きで起動
  start-receiver.bat               検証用受信サーバーを起動（data\receiver.log に出力）
  sink.properties.example          転送先URLとトークン
  rrbridge.properties.example      RRBridgeの動作設定（HTTP認証、切替待ち、ログ上限、spool保持日数）
  fetch-deps.sh                    sqlite-jdbc を lib/ に取得
lib/                       ビルド時の依存jar置き場（Git管理外。lib/README.md）
docs/                      設計・調査・検証・履歴・決定記録（docs/00_index.md）
docs/handoff/              会話間の引継ぎ資料（原典）
server/                    受信API＋Remote MCPサーバー（Python、mcp 2.3.0、PostgreSQL/SQLite、docker compose＋Caddy）。server/README.md
```

## 5. ビルド

JDK 9 以上が必要です（`--release 8` で Java 8 向けに出力。実行環境はRomRaider同梱の Zulu 8 32bit）。RomRaider同梱のJavaはJREのみでコンパイラがありません。

```bash
cp "/c/Program Files (x86)/RomRaider/RomRaider.jar" lib/   # コンパイル時の参照用
./scripts/fetch-deps.sh                                     # lib/sqlite-jdbc.jar（3.46.1.3）
./build.sh                                                  # dist/ に出力
```

`dist/` の中身: `rrbridge.jar`、`rrreceiver.jar`、`sqlite-jdbc.jar`、`profiles/`、`start-romraider-with-bridge.bat`、`start-receiver.bat`、`sink.properties`、`rrbridge.properties.example`。

自己試験:

```bash
./test.sh     # passed 44, failed 0 で成功
```

## 6. 配置と起動（X270）

1. `dist/` の中身を `C:\Users\zawa\Documents\OpenPortWork\RRBridge` にコピーする（バッチはこの場所を前提にしていないが、`rrbridge.dir` はバッチのあるフォルダになる）。
2. 既存の RomRaider Logger を終了し、`start-romraider-with-bridge.bat` で起動する。**通常のショートカットから起動するとRRBridgeは読み込まれない。**
3. `rrbridge.log` に次の行が出れば成功:
   ```
   agent loaded v0.4.0 dir=… httpAuth=off settleMs=1500
   EcuLogger instance found
   stream installed (handlers=10, session=…, sink=…)
   http listening on 127.0.0.1:18765
   ```
4. 転送先を使う場合は `sink.properties` の `url`／`token` を設定（既定 `http://127.0.0.1:18766/ingest`）。検証用には `start-receiver.bat` を起動（`data\telemetry.db`）。

RomRaider実行中は `rrbridge.jar` がロックされるため、更新時はRomRaiderを終了してから差し替えます。

## 7. 操作

### ファイル経由
`inbox\<任意名>.cmd` に Properties 形式で書く（`.tmp` で書いてから rename 推奨）。結果は `outbox\<任意名>.json`。

```properties
cmd=switch_profile
profile=fuel.xml
```

### HTTP経由（127.0.0.1 のみ）
読み取り系（`status`、`stream_status`、`list_profiles`）は GET 可。操作系は POST。
`rrbridge.properties` に `http.token` を設定すると `Authorization: Bearer <token>` が必要になる（既定は認証なし）。
操作の実行中（`switch_profile` の待機など）でも、読み取り系は待たされずに応答する。

```
GET  http://127.0.0.1:18765/status
GET  http://127.0.0.1:18765/stream_status
GET  http://127.0.0.1:18765/list_profiles
POST http://127.0.0.1:18765/logging_start?wait_ms=3000
POST http://127.0.0.1:18765/filelog_start
POST http://127.0.0.1:18765/switch_profile?profile=basic.xml
POST http://127.0.0.1:18765/selftest_stream?count=80&hz=8
```

### コマンド一覧

| cmd | 引数 | 内容 |
| --- | --- | --- |
| `status` | — | 状態（ロギング、CSV記録、タイトル、出力先、最新CSV、転送状態） |
| `list_profiles` | — | `profiles/` 内のXMLと選択されている項目ID・件数 |
| `logging_start` / `logging_stop` | `wait_ms`（任意、≤10000） | ECU通信の開始・停止。`wait_ms` を付けると状態が変わるまで待つ。`logging_stop` は記録中ならCSVも止める |
| `filelog_start` / `filelog_stop` | — | CSV記録の開始・停止（開始はロギング中のみ） |
| `load_profile` | `profile=<名>.xml` | 読込のみ。**CSV記録中に使うとヘッダーが多重化する** |
| `switch_profile` | `profile=<名>.xml` | 記録中なら停止→読込→再開。通常はこちら |
| `stream_status` | — | 転送の状態（生成・破棄・送信済み行数、未送信バイト数、送信エラー、最終応答） |
| `selftest_stream` | `count`≤10000、`hz`≤50 | ECU無しで転送経路を試す合成データ |

設定ファイル `rrbridge.properties`（任意）: `http.token`、`switch.settleMs`（既定1500）、`log.maxBytes`（既定5MB、超えたら `.1` に退避）、`spool.retentionDays`（既定14、送信済みで古いspoolを削除）、`profile.maxParams`（既定20）、`guard.enabled`（既定true）、`guard.minRateHz`（既定2.0）、`guard.checkMs`（既定10000）、`guard.fallbackProfile`。

仕様の詳細（応答形式、status JSON、エラー）: [`docs/03_rrbridge_design.md`](docs/03_rrbridge_design.md) §4〜5。

## 8. 転送フォーマット

UTF-8、1行1レコード、タブ区切りを `POST <url>`（`Content-Type: text/tab-separated-values`、トークン設定時 `Authorization: Bearer <token>`）。

```
M  session  segment  param_id  name  units           項目定義（項目の集合が変わると segment が進む）
S  session  segment  seq  epoch_ms  id=値,id=値,...  サンプル（値は換算済み）
E  session  segment  epoch_ms  text                   イベント（ロギング開始停止、CSV記録、プロファイル切替）
```

受信側は `(session, seq)` で一意にすること（再送があるため）。受信DBのスキーマは [`docs/03_rrbridge_design.md`](docs/03_rrbridge_design.md) §7。

## 9. 注意

- RomRaider の private フィールドにリフレクションでアクセスしています。RomRaider のバージョンが変わると動かなくなる可能性があります。
- CSVの列順はプロファイルの記載順と一致しません（同じプロファイルでも直前の状態で変わる）。列名で扱ってください。
- RomRaider の設定でCSV記録がデフォッガースイッチ（S20）に連動しています。RRBridgeの制御と競合するかは未検証です。
- RomRaider は GPL v2 で公開されています。このエージェントは同じJVM内で動作するため、公開・配布する場合はライセンスの扱いを確認してください（現在リポジトリは private）。
