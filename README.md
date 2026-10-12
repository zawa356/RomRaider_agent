# RomRaider_agent (RRBridge)

RomRaider Logger を外部から操作し、ロギング中のデータをリアルタイムに転送するための Java エージェントです。
RomRaider 本体には手を加えず、`-javaagent` で同じJVMに読み込ませて動かします。

- 対象: RomRaider 1.1.0 NOV26 2025（同梱 Java 8 / 32bit）、Logger定義 v370、SSM（スバル K-Line）
- 状態: **PoC（v0.3.0）**。外部制御は実機で動作確認済み。リアルタイム転送は経路のみ確認済み（実ECUデータは未確認）。詳細は [docs/verification.md](docs/verification.md)。

## できること

| 機能 | 内容 |
| --- | --- |
| 状態取得 | ロギング中か、CSV記録中か、タイトル、最新CSV、転送状況 |
| ロギング開始・停止 | ECUとの通信の開始・停止 |
| CSV記録開始・停止 | RomRaider の「Start file log」と同じ |
| プロファイル切替 | `profiles/` 内のXMLのみ。`switch_profile` は記録停止→読込→記録再開で1区間1CSVにする |
| リアルタイム転送 | 内部の配信から1応答＝1サンプルを取り出し、ローカルspool（正本）に追記してHTTPで送信。送信先停止中は保持し、復旧後に再送 |

ECUリセット、DTC読出・消去、ROM読み書きなど、車両に影響する操作は公開していません。

## 構成

```
src/main/java/net/neristal/rrbridge/
  Agent.java     -javaagent の入口。コマンド受付（inbox/ と HTTP 127.0.0.1:18765）、状態出力
  Stream.java    データ配信への取り付け、spool追記、HTTP転送
  Receiver.java  検証用受信サーバー（Telemetry API 試作）。TSVを受けて SQLite に保存
  Util.java      共通処理（RomRaider非依存）
profiles/        テスト用プロファイル（basic.xml / fuel.xml）
scripts/         起動バッチ、設定例、依存取得スクリプト
docs/            検証結果
```

## ビルド

JDK 9 以上が必要です（`--release 8` で Java 8 向けに出力します）。

```bash
cp "/c/Program Files (x86)/RomRaider/RomRaider.jar" lib/   # RomRaider.jar を lib/ へ
./scripts/fetch-deps.sh                                     # sqlite-jdbc を取得
./build.sh                                                  # dist/ に一式を出力
```

## 配置と起動（X270）

1. `dist/` の中身を `Documents\OpenPortWork\RRBridge` にコピーする。
2. 既存の RomRaider Logger を終了し、`start-romraider-with-bridge.bat` で起動する。
   `rrbridge.log` に `EcuLogger instance found` と `stream installed` が出れば成功。
3. 転送先を使う場合は `sink.properties` の `url` と `token` を設定する。検証用には `start-receiver.bat` で受信サーバーを起動する（127.0.0.1:18766、`data\telemetry.db`）。

## 操作

ファイル経由（`inbox\任意名.cmd` → 結果は `outbox\任意名.json`）:

```properties
cmd=switch_profile
profile=fuel.xml
```

HTTP経由（127.0.0.1 のみ待受。状態は GET、操作は POST）:

```
GET  http://127.0.0.1:18765/status
POST http://127.0.0.1:18765/logging_start
POST http://127.0.0.1:18765/filelog_start
POST http://127.0.0.1:18765/switch_profile?profile=basic.xml
POST http://127.0.0.1:18765/selftest_stream?count=80&hz=8
GET  http://127.0.0.1:18765/stream_status
```

コマンド一覧: `status` / `logging_start` / `logging_stop` / `filelog_start` / `filelog_stop` / `load_profile` / `switch_profile` / `stream_status` / `selftest_stream`

## 転送フォーマット

1行1レコードのTSV（UTF-8）を `POST <sink url>` で送ります。`Authorization: Bearer <token>`（設定時）。

```
M  session  segment  param_id  name  units           項目定義（項目構成が変わると segment が進む）
S  session  segment  seq  epoch_ms  id=値,id=値,...  サンプル
E  session  segment  epoch_ms  text                   イベント（ロギング開始停止、CSV記録、プロファイル切替）
```

受信側は `session + seq` で重複排除する前提です（spool からの再送があるため）。

## 注意

- RomRaider の内部フィールド（`controller`、`queryManager`、`updateHandlers`、`logToFileButton` など）にリフレクションでアクセスしています。RomRaider のバージョンが変わると動かなくなる可能性があります。
- RomRaider は GPL v2 で公開されています。このエージェントは RomRaider と同じJVM内で動作するため、配布する場合はライセンスの扱いを確認してください。
