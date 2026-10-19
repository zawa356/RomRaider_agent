> **2026-10-19 Phase II entry:** Read [development bootstrap](docs/ops/development_environment_bootstrap.md) and [Phase II worklog](docs/handoff/codex_phase2_worklog.md) first. Earlier dated handoffs below are historical; current main/progress/contracts govern.

# AGENTS.md — AIエージェント向け作業ガイド

> **2026-10-14 最新Codex日中指示の入口**：[Agent日中引継ぎ](docs/handoff/2026-10-14_codex_day_autonomy.md)と[Server日中指示全文](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-14_codex_day_autonomy.md)を最初に読む。勤務中のユーザー確認なしで安全な合成開発・試験・PR・CI・マージを自律継続。実車、既存MCP/Caddy、不可逆操作は禁止。[追記式worklog](docs/handoff/codex_daytime_worklog.md)を先に更新してcommit/push。以下の2026-10-12の状態表は歴史資料であり、現在地ではない。


> **2026-10-13夜からのCodex開発**：先に [Codex夜間Agent入口](docs/handoff/2026-10-13_codex_overnight.md) と [Server側Codex引継ぎ正本](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_codex_overnight.md) を全文確認する。以下の「現在の状態（2026-10-12時点）」は歴史的スナップショットであり最新版ではない。**現在地はAgent製品進捗、Server進捗、最新GitHub main・PR・CIで確認**すること。作業中はworklog、履歴、ADR、commitをこまめにpushし、中断後の再開点をGitへ残す。ECU/本番/Caddy/MCPの夜間無断変更は禁止。

このファイルは、このリポジトリで作業するAI（Claude、ChatGPT/Codex など）が最初に読むためのものです。
人間向けの概要は `README.md`、詳細は `docs/` にあります。**情報量を優先して書いています。要約しすぎないでください。**

---

## 0. 最初に読む順番

1. このファイル（作業ルール・禁止事項・現状）
2. `docs/00_index.md`（ドキュメント全体の地図）
3. `docs/07_history.md`（何をどの順で試し、何が失敗し、なぜ今の形になったか）
4. `docs/03_rrbridge_design.md`（コードの設計と各クラスの責務）
5. `docs/04_romraider_internals.md`（依存しているRomRaider内部の箇所。バージョン差異の確認に必須）
6. `docs/09_open_issues.md`（未解決事項と次の作業候補）
7. `docs/10_interfaces.md`（**現行PoCの接続規約**。接続口を変える前に必ず読む）\n8. **製品版の作業時:** [RomRaiderServerの設計資料一覧](https://github.com/zawa356/RomRaiderServer/blob/main/docs/README.md) と [Server–Agent通信契約v1](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md)。PoCと製品仕様は別世代であり、旧C8/C9規約を無条件に踏襲しない

`docs/handoff/` には、ユーザーがChatGPT／Claudeの会話をまたいで使っている引継ぎ資料の全文があります（設計検討の原典）。

---

## 1. プロジェクトの目的（ユーザーの意図）

- オーナー: zawa（GitHub: `zawa356`）。ITインフラエンジニア（開発者ではない）。普段はPython／PowerShellを書く。
- 車両: スバル レガシィ ツーリングワゴン BP5 Spec.B（EJ20X、SSM/K-Line世代）。
- ロガー実行PC: Lenovo ThinkPad X270（Windows 10、端末名 `laptop-glmhjuc8`、ユーザー `zawa`）。
- ECU通信: Tactrix OpenPort 2.0（互換機）＋ RomRaider Logger。
- 最終目標:
  1. RomRaider Logger 全体（ロギング開始・停止、取得項目＝プロファイルの切替、状態照会、CSV解析）を外部から操作する。
  2. 走行中にPCを触らずに、LLMが次に取得すべき項目を判断して計測計画を切り替える「適応的な計測」。
  3. ログをローカルCSVだけでなくサーバー（SQL）へリアルタイム転送し、スマホの音声モードからHTTPのMCP経由で解析を呼び出す。
- 制約: LLM APIの従量課金は極力避けたい。サブスク（ChatGPT／Claude）の範囲で使いたい。通信回線は使える前提でよい（ユーザー明言）。
- 作業環境: 当初ChatGPT（Workモード）で検討 → 2026-10-12にClaude Coworkへ移行（ChatGPT Workの調子が悪いため、ユーザー判断）。

---

## 2. 絶対に守ること（安全・スコープ）

- **ECUへの書き込み、ROMの読み書き、ECUリセット、DTC消去、学習値リセット、OpenPortのファームウェア更新は実装しない。** RomRaiderにはこれらの機能（`ResetEcuAction`、`ReadEcuCodesAction`、`LearningTableValuesAction` など、`EcuLoggerMenuBar` のToolsメニュー）があるが、エージェントからは呼ばない。
- 公開するコマンドはホワイトリスト方式（`Agent.execute()`）。新しいコマンドを足すときは「読み取り」か「ロガーの記録制御」に限る。
- プロファイルの読み込みは `RRBridge\profiles\` 内のファイル名（`[A-Za-z0-9_.-]+\.xml`）のみ許可。任意パスを受け付けない。
- 走行中に画面操作を要求する設計にしない。
- LLMの提案をそのままロガーに適用しない。Supervisor（未実装）が項目数上限・定義整合性・安全条件を検査してから適用する前提。
- **RomRaider本体（`C:\Program Files (x86)\RomRaider` 以下）は変更しない。** 書き込み権限もない（検証で `Permission denied` を確認済み）。
- 認証情報（`sink.properties` の token など）をコミットしない。`.gitignore` 済み。
- RomRaiderはGPL v2。このエージェントは同じJVM内で動くため、公開・配布する場合はライセンスを確認する（現在リポジトリはprivate）。

---

## 3. 現在の状態（2026-10-12 時点）

| 項目 | 状態 |
| --- | --- |
| バージョン | RRBridge v0.5.0（2026-10-12 13:56 にX270へ配置済み） |
| 外部制御（status / logging / filelog / profile） | X270実機で動作確認済み（ECUは無応答の状態） |
| `switch_profile` で1区間1CSV | 実機で確認済み |
| リアルタイム転送（spool → HTTP → SQLite） | 合成データ（`selftest_stream`）で経路確認済み。平均273ms・最大533msの遅延。受信停止中の80サンプルを復旧後に全件再送 |
| 取得速度ガード（v0.5.0） | 項目数上限（20）での拒否、log4j経由の通信エラー計数、health イベント送信は実機確認済み。切替後の速度低下による自動復帰は**ECUデータが無いため実機未確認**（単体試験のみ） |
| 実ECUデータがハンドラーに届くこと | **未確認**（取り付けは成功。ECUがつながればすぐ確認可能） |
| ChatGPTからのMCP呼び出し | ユーザーが別経路で成功と報告（2026-10-12、詳細未確認） |
| 実データ | `archive/`（2026-09-21の実車CSV 11本ほか。`archive/README.md`） |
| 受信API＋MCPサーバー（`server/`） | 試作 v0.1.0（+取得速度の健全性ツール）。自動試験36件PASS（SQLite／PostgreSQL 16）。VPS未配置。Claudeからの接続・音声モードは未検証 |
| Supervisor（計測計画の実行・検証） | 未着手 |
| LLMの無人起動方法 | 未解決（最大の課題） |

X270上の配置先: `C:\Users\zawa\Documents\OpenPortWork\RRBridge\`（このリポジトリの `dist/` の中身を置く）。

---

## 4. ビルドと検証の手順

```bash
cp "<RomRaiderインストール先>/RomRaider.jar" lib/
./scripts/fetch-deps.sh        # lib/sqlite-jdbc.jar (3.46.1.3)
./build.sh                     # dist/ に rrbridge.jar, rrreceiver.jar, bat, profiles, sink.properties
```

- JDK 9以上で `--release 8`。実行環境はRomRaider同梱の **Zulu 8 (1.8.0_504) 32bit**。Java 9以降のAPIを使わない。ラムダは使っていない（Java 8では使えるが、既存コードは匿名クラスで統一）。
- 外部ライブラリをエージェント（rrbridge.jar）に追加しない。RomRaiderのクラスパスに乗らないため、`-javaagent` のjar単体で完結させる。JSONも手書き（`Util.q()`）。
- `rrreceiver.jar` は RomRaider のクラスに依存してはいけない（`Receiver` と `Util` のみ）。過去に `Stream.read()` を呼んで `ClassNotFoundException: DataUpdateHandler` になった（`docs/07_history.md`）。
- **自己試験 `./test.sh`**（v0.4.0〜）: RomRaiderのGUIなしで動く範囲（Util、プロファイル名検証と一覧、設定読込、Stream→Receiverの転送、受信停止中の保持と再送、重複排除、トークン認証、spool掃除）を30項目確認する。コードを変えたら必ず実行し、全件PASSを確認してからコミットする。
- `Agent`/`Stream` のRomRaider連携部分（EDT操作、リフレクション、実際のプロファイル切替）は自己試験では動かない。実機（Windows）で `inbox/*.cmd` を使って試す。手順は `docs/06_verification.md`。

---

### 4.1 server/（Python）

```bash
cd server && pip install -r requirements-dev.txt
python -m pytest -q tests                                   # SQLite
RR_TEST_PG_URL=postgresql://user@host:port/db python -m pytest -q tests   # PostgreSQLも
```

- `mcp` は **2.x**（`from mcp.server.mcpserver import MCPServer`）。ネット上の例の多くは1.x の `FastMCP` なので混同しない。
- SQLは SQLite と PostgreSQL の両方で動く書き方にする（`?` プレースホルダ、`ON CONFLICT DO NOTHING`）。`db.py` が `%s` に変換する。
- MCPツールの中身は `analysis.py`（MCP非依存）に書き、`app.py` は薄く保つ。返り値は名称・単位付き（IDだけにしない）。
- 受信形式は Java 版 `Receiver` と同じ。どちらかを変えたら両方と `docs/03_rrbridge_design.md` §6〜7 を揃える。
- MCPに書き込み系ツールを足すときは、車両・ロガーを操作するものは入れない（AGENTS §2）。計測計画の提出を入れる場合も「提案を保存するだけ」にし、適用はX270側のSupervisorが検証してから行う。

## 5. コードを変更するときの注意

- RomRaiderの private フィールドにリフレクションでアクセスしている（`EcuLogger.controller`、`EcuLogger.logToFileButton`、`LoggerControllerImpl.queryManager`、`QueryManagerImpl.updateHandlers`、`QueryManagerImpl.dataUpdater`、`AsyncDataUpdateHandler.handlers`）。RomRaiderを更新したら `docs/04_romraider_internals.md` の表を見て、まだ存在するか確認する。
- Swingの状態を変える処理（`loadUserProfile`、トグルボタン操作、`startLogging`/`stopLogging`）は `Agent.onEdt()` でEDT上で実行する。
- `Stream.handleDataUpdate()` はRomRaiderの配信スレッド（`AsyncDataUpdater`、約16ms周期）から呼ばれる。重い処理・I/O・例外送出をしない（キューに積むだけ）。
- プロファイル切替時、RomRaiderは項目を1つずつ登録・解除する。CSV記録中に `load_profile` すると同じCSVにヘッダー行が多重に出る。区切りたいときは `switch_profile` を使う。
- CSVの列順はプロファイルXMLの記載順と一致しない。ETLは列名で対応付ける。
- `logging_start`／`logging_stop` は非同期。完了を待つときは `wait_ms`（最大10000）を付ける。
- `logging_stop` はCSV記録中なら先に記録を止める（v0.4.0〜）。
- **K-Lineの帯域に注意**（v0.5.0〜）: 項目を増やすと周期が延び、多すぎると応答待ちのタイムアウトで全データが取れなくなる（2026-09-21 に実害あり）。`profile.maxParams`（既定20）を超えるプロファイルは拒否し、切替前に2Hz以上で取れていて切替後に下回れば直前のプロファイルへ自動復帰する（`guard.*`）。このガードを弱める変更をするときはユーザーに確認する。
- `LinkMonitor` は log4j 1.2 の `Appender` を `java.lang.reflect.Proxy` で実装してルートロガーに追加し、RomRaiderのログ文言（`connection initialised`、`ECU ID = `、`Error sending init`、`readMsg`）で通信状態を数える。RomRaiderのログ文言が変わると数えられなくなる。
- `Util` は受信サーバー（rrreceiver.jar）にも入るので、`com.romraider.*`、`Agent`、`Stream` を参照させない。
- バッチファイルは CRLF（`.gitattributes` で指定）。`start-romraider-with-bridge.bat` は作業ディレクトリをRomRaiderのインストール先にする必要がある（相対パス依存。`docs/04_romraider_internals.md` §6）。

---

## 6. ドキュメント更新のルール

- 新しく分かった事実・決定・失敗は `docs/07_history.md`（時系列）と `docs/08_decisions.md`（決定記録）に**追記**する。過去の記述は消さない。
- 「実機で確認した」「コード調査のみ」「推測」「ユーザーの発言」を区別して書く。
- 数値（周期、遅延、件数、バイト数）は測定条件とともに残す。
- 引継ぎ資料（`docs/handoff/`）はユーザーがChatGPTのLibraryとClaudeのProjectでも共有している。更新したら同じ内容をProject側にも反映するとよい。
- **接続口（コマンド、status のキー、転送形式、受信API、MCPツール、DBスキーマ、設定キー）を追加・変更したら、`docs/10_interfaces.md` を同じコミットで更新する。** 互換性の規則は同文書 §15。
- コミットメッセージは日本語可。変更理由を書く。

