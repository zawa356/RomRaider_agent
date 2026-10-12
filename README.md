# RomRaider_agent（RRBridge）

RomRaider Logger を外部から操作し、ロギング中のデータをリアルタイムにサーバーへ転送するための Java エージェントです。
RomRaider 本体には手を加えず、`-javaagent` で同じJVMに読み込ませて動かします。

> **AIエージェントへ**: 作業前に [`AGENTS.md`](AGENTS.md) と [`docs/00_index.md`](docs/00_index.md) を読んでください。経緯は [`docs/07_history.md`](docs/07_history.md)、決定理由は [`docs/08_decisions.md`](docs/08_decisions.md) にあります。

## 1. 位置づけ

スバル レガシィ BP5（EJ20X、SSM/K-Line）を RomRaider Logger＋OpenPort 2.0互換機で計測し、最終的には
「走行中に LLM が次に取るべき項目を判断して計測を切り替える」「スマホの音声モードから MCP 経由でログを解析する」ことを目指すプロジェクトの一部です。
このリポジトリは、車載PC（ThinkPad X270、Windows 10）上で RomRaider を操作し、データを取り出す部分（Adapter）を担います。
全体構成は [`docs/02_architecture.md`](docs/02_architecture.md)。

## 2. 状態（v0.3.0、2026-10-12）

| 機能 | 状態 |
| --- | --- |
| 状態取得、ロギング開始・停止、CSV記録開始・停止 | 実機確認済み |
| プロファイル読込（`profiles/` 内のみ） | 実機確認済み |
| `switch_profile`（記録停止→読込→再開で1区間1CSV） | 実機確認済み |
| リアルタイム転送（内部配信 → spool → HTTP → SQLite） | 合成データで確認済み（平均273ms・最大533ms。受信停止中の80件を復旧後に全件再送）。**実ECUデータは未確認** |
| 本番サーバー（VPS）、MCP、Supervisor | 未着手 |

検証の詳細: [`docs/06_verification.md`](docs/06_verification.md)。未解決事項: [`docs/09_open_issues.md`](docs/09_open_issues.md)。

## 3. 仕組みの要点

- `EcuLogger.getEcuLoggerWithoutCreation()`（public static）でロガー本体を取得し、`startLogging()`／`stopLogging()`／`loadUserProfile()` などの公開メソッドを呼ぶ。
- CSV記録は「Start file log」ボタン（private `logToFileButton`）を `doClick()` する。GUI操作と同じ処理が走る。
- データは `QueryManagerImpl.updateHandlers`（private 配列）に自前の `DataUpdateHandler` を追加して受け取る。CSVを経由しないため、記録のオン・オフと無関係に1応答＝1サンプルの換算値が届く。
- 受け取ったデータはローカルの spool（TSV）に追記してから HTTP POST する。送信済み位置を記録し、送信先が止まっていても復旧後に続きから再送する。受信側は `session+seq` で重複を除く。
- 依存しているRomRaider内部の一覧（バージョンを上げたら要確認）: [`docs/04_romraider_internals.md`](docs/04_romraider_internals.md)。

ECUリセット、DTC読出・消去、ROM読み書き、学習値リセットなど、車両に影響する操作は公開していません。

## 4. リポジトリ構成

```
AGENTS.md / CLAUDE.md      AIエージェント向けの作業ルール
README.md                  このファイル
build.sh                   ビルド（dist/ に配置一式を出力）
src/main/java/net/neristal/rrbridge/
  Agent.java               -javaagent の入口。コマンド受付（inbox/ と HTTP 127.0.0.1:18765）、status.json
  Stream.java              データ配信への取り付け、spool追記、HTTP転送、合成データ試験
  Receiver.java            検証用受信サーバー（Telemetry API 試作）。TSV → SQLite
  Util.java                RomRaider非依存の共通処理（受信サーバー用）
src/main/resources-manifest.txt  Premain-Class
profiles/                  テスト用プロファイル（basic.xml 6項目 / fuel.xml 7項目）
scripts/
  start-romraider-with-bridge.bat  RomRaiderをRRBridge付きで起動
  start-receiver.bat               検証用受信サーバーを起動（data\receiver.log に出力）
  sink.properties.example          転送先URLとトークン
  fetch-deps.sh                    sqlite-jdbc を lib/ に取得
lib/                       ビルド時の依存jar置き場（Git管理外。lib/README.md）
docs/                      設計・調査・検証・履歴・決定記録（docs/00_index.md）
docs/handoff/              会話間の引継ぎ資料（原典）
```

## 5. ビルド

JDK 9 以上が必要です（`--release 8` で Java 8 向けに出力。実行環境はRomRaider同梱の Zulu 8 32bit）。RomRaider同梱のJavaはJREのみでコンパイラがありません。

```bash
cp "/c/Program Files (x86)/RomRaider/RomRaider.jar" lib/   # コンパイル時の参照用
./scripts/fetch-deps.sh                                     # lib/sqlite-jdbc.jar（3.46.1.3）
./build.sh                                                  # dist/ に出力
```

`dist/` の中身: `rrbridge.jar`、`rrreceiver.jar`、`sqlite-jdbc.jar`、`profiles/`、`start-romraider-with-bridge.bat`、`start-receiver.bat`、`sink.properties`。

## 6. 配置と起動（X270）

1. `dist/` の中身を `C:\Users\zawa\Documents\OpenPortWork\RRBridge` にコピーする（バッチはこの場所を前提にしていないが、`rrbridge.dir` はバッチのあるフォルダになる）。
2. 既存の RomRaider Logger を終了し、`start-romraider-with-bridge.bat` で起動する。**通常のショートカットから起動するとRRBridgeは読み込まれない。**
3. `rrbridge.log` に次の行が出れば成功:
   ```
   agent loaded v0.3.0 dir=…
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

### HTTP経由（127.0.0.1 のみ、認証なし）
`status` だけ GET 可。それ以外は POST。

```
GET  http://127.0.0.1:18765/status
POST http://127.0.0.1:18765/logging_start
POST http://127.0.0.1:18765/filelog_start
POST http://127.0.0.1:18765/switch_profile?profile=basic.xml
POST http://127.0.0.1:18765/stream_status
POST http://127.0.0.1:18765/selftest_stream?count=80&hz=8
```

### コマンド一覧

| cmd | 引数 | 内容 |
| --- | --- | --- |
| `status` | — | 状態（ロギング、CSV記録、タイトル、出力先、最新CSV、転送状態） |
| `logging_start` / `logging_stop` | — | ECU通信の開始・停止（非同期） |
| `filelog_start` / `filelog_stop` | — | CSV記録の開始・停止（開始はロギング中のみ） |
| `load_profile` | `profile=<名>.xml` | 読込のみ。**CSV記録中に使うとヘッダーが多重化する** |
| `switch_profile` | `profile=<名>.xml` | 記録中なら停止→読込→再開。通常はこちら |
| `stream_status` | — | 転送の状態 |
| `selftest_stream` | `count`≤10000、`hz`≤50 | ECU無しで転送経路を試す合成データ |

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
