# Phase 2.1-C：既存RRBridgeから製品v1への移行経路

## 実装済みの接続

```text
既存 RomRaider Logger
  ↓（既存のまま・変更なし）
RRBridge Stream.handleDataUpdate
  ↓ bounded queue（既存仕様。飽和時の損失は未解決）
旧TSV writer → rr_YYYYMMDD_HHMMSS.tsv
  ↓ 読み取りのみ、明示的にProductBridgeOnceを実行した場合
LegacyTsvBridge (M/S/E→ProfileRevision/Observation/Event)
  ↓ UUID固定／イベント順／source_kind
製品版 DurableSpool (SQLite WAL + FULL)
  ↓ ProductSender.pumpOnce()
定義PUT、計測batch POST → RomLogger Server API
  ↓ HTTP 200 committedの完全ACKのみ
SQLite上でACK済みに変更
```

**重要:** Phase 2.1-C1は、独立した読み取り専用TSV importerを追加した段階です。既存のRRBridgeのLogger callback、TSV queue、古いreceiver、ログ削除条件は**まだ変更していません**。PoC全区間が無欠損になると主張しません。

## 安全な取り込みと再送の原則

- 入力TSVは`rr_*.tsv`のみ。M行で項目定義を確認してからS行を変換する。必要な項目が欠けたり値がNaNの場合、推測補完せずエラーにする
- 元のsession／device／segment／sequenceからUUIDを安定生成する。再起動後・再スキャン後も同じ計測レコードIDを使う
- 最終行が書込み途中でLFがない場合は保留し、次の走査で完結してから取り込む
- 出力観測JSONは合成`TEST_`パラメーターのみなら`source_kind=synthetic`、通常IDなら`ecu`。PoCのイベントは`agent`
- プロファイル定義のACKが届くまで観測をServerへ送らず、元TSVもSQLite未ACKレコードも削除しない
- `ProductBridgeOnce`は**1回の明示実行で、全TSV行をSQLiteに追加し、最大1 HTTP交換**を行う。送信継続・バックオフは後続フェーズに委ねる
- 秘密トークンをコマンドライン引数・ログに書かず、環境変数経由で渡す。実デバイスは外部HTTPSのみ使用

### 実行インターフェース（検証用途）

```bash
# Javaテスト類（Windows/Linux）
bash scripts/test-product-v1-spool.sh

# 環境変数：端末側の認証情報を安全な方法で設定
export ROMLOGGER_DEVICE_ID="<device-uuid>"
export ROMLOGGER_VEHICLE_ID="<vehicle-uuid>"
export ROMLOGGER_DEVICE_TOKEN="<secret-token>"

java -cp "build/product-v1-test:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
  net.neristal.rrbridge.product.v1.ProductBridgeOnce \
  ./spool/rr_20261013_123456.tsv ./product-outbox.sqlite https://example.invalid
```

上のURLとUUIDはプレースホルダー。実データ・トークンはGitHubやCIに投入しない。このCLIは**現在CIのJavaコンパイル対象ですが、実車・X270上では未実行**です。自動的にPoCを切り替えるものではありません。

## 本物のServer/PostgreSQLとのE2E試験

実行プログラム`ProductServerE2E.java`、`scripts/test-product-v1-real-server.sh`、CIワークフロー`.github/workflows/product-v1-real-server-e2e.yml`を追加した。

試験内容（実際に実行された場合のみ合格判定）:

1. Private Serverリポジトリをcheckoutし、**使い捨てのPostgreSQL 16**でAlembic migrationを適用
2. 合成Vehicle／Deviceを生成し、端末トークンはマスクされた環境変数だけへ渡す
3. Java8互換のTSV importer→SQLite→実FastAPIへProfileRevision PUTと2レコードのバッチPOST
4. 別SQLiteファイルで**同一TSV原本を再読込／同じUUIDで再送**し、Serverが原本を二重登録しないことを実DBクエリで確認
5. 2件の`source_kind=synthetic`とプロファイル1件を確認した場合のみPASS

### Private GitHub認証について

AgentもServerも**Private**。標準のGitHub Actions `GITHUB_TOKEN`は通常、別Private Repoのソースを読めない。

- Agent repoに「`zawa356/RomRaiderServer`のContents: Read-only」のみを許可するfine-grained tokenを、GitHub Actionsのsecret `ROMLOGGER_SERVER_READ_TOKEN`として設定すれば、E2E jobが実行される
- tokenが無ければ**NOT RUN**をジョブサマリーへ表示し、実ServerとのE2Eを成功扱いしない
- 読取権限を全リポジトリへ広げない。CI専用の使い捨てVehicle／DBだけを使用
- `workflow_dispatch`でも実行できる（secretを設定する前はNOT RUN）
- token平文、実車のECUログ、実車用tokenをGitHub Secrets以外に保存／共有しない

ローカルで両リポジトリへアクセスできる場合は、Agentの単体テストを実行してから
`ROMLOGGER_SERVER_PATH=/path/to/RomRaiderServer DATABASE_URL=postgresql://... bash scripts/test-product-v1-real-server.sh`
を利用できる。ただしDBは**必ず捨ててよい環境**を使用する。

## 残余リスクと次工程

- 全TSV先頭からの再走査は長時間走行でO(ログ総量)。移行前に**永続オフセット＋DBコミットの原子化**が必要
- 古いPoCのキューが飽和して`linesDropped`になった場合、TSV変換ではその欠損を復元できない。writerの失敗も要対策
- 元TSVの削除／ローテーションにより未移行原本が失われないよう、移行期間は旧保持ポリシーとの整合が必要
- 409/422/413の隔離・バッチ分割、直近/過去公平転送、端末スリープ／ディスク満杯／停電テスト、実車でのJava8 32bit稼働は未完了
- Phase 2.1-C2にて実E2E成功を確認するまでは**ServerとAgentの統合試験合格とは記載しない**
