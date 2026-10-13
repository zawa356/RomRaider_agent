# Phase 2.1-D2b-2c-4b-2c：最新Agentの実FastAPI／PostgreSQL結合検証

**範囲**：CloudPC上の使い捨てLinuxデータベースに対する合成計測。実車・X270・運用中のCaddy/MCP・製品環境には未接続。

## 構成と責任境界

通常のLoggerや運用Workerの起動経路は変更していない。テスト専用Javaドライバから、以下の実装済み機能を明示的に実行する。

```text
[従来TSV]      M/S -> IncrementalTsvBridge.importNew()
                                    |
[新journal]    M/S -> IngressJournalPrototype(RLJ1 fsync + source UUID)
                   -> IncrementalTsvBridge.importJournal()
                                    |
                             製品 DurableSpool
                       SQLite v1_profiles / v1_records
                                    |
                              ProductSender.pumpOnce()
                     実HTTP profile登録 → 受領検証
                     実HTTP batch転送  → 厳格なACK検証
                                    |
                  FastAPI product_v1.api (127.0.0.1:18083)
                                    |
                    PostgreSQL16 (127.0.0.1:25432)
                  専用クラスタ・専用 *_e2e DBのみ
```

## CloudPC検証

- Agent main基準：`f3898a942b3b3b83e7ce77e8b28113a15d806439`、Server main基準：`1e7bd0bc4132d53f4019289fe4db8ecd02e2c901`。両private repoを`jr2twt`の専用cloneに取得し、相互のソースツリーを変更しない。
- VPSにPostgreSQLがなかったため、Ubuntu公式`postgresql-16`と`libpq5`のdebを`apt download`で取得し、`dpkg-deb -x`で専用ディレクトリ `/home/jr2twt/git/romlogger-4b2c-pg16` のみに展開。systemdのDBサービスは登録・起動していない。データは同ディレクトリの`data`、ループバック限定の25432番で`jr2twt`として起動。通常の5432番やCaddy/MCP/SSHには触れていない。
- Python側は専用Agent clone内`.venv`でServer `requirements.txt`をインストール。Ubuntu標準の`python3.12-venv`のみ不足のためapt導入。`.venv`は`.gitignore`で明示除外。
- 新規Java8互換`JournalServerE2E`は、M2行・S3行のTEST_合成journalと永続source UUIDを作成。v1 SQLiteへの区切り取込・別インスタンス再開・cursor再読込→本物のFastAPIプロフィールACK→3観測のPostgreSQLバッチACKを確認。
- 別の空SQLite outboxを使って**同じ原本を最初から再取込**し、Serverが同一profile・batchを重複としてACKし、受信レコード数を増やさずにローカルACKを保持することを確認。
- 従来TSVドライバ`ProductServerE2E`も同じ隔離DB上で実行。PostgreSQL実SQLで **TSV 3件＋journal 3件＝6件、プロフィール2件、record UUID重複0件、source_kindは全件synthetic**を照合。現地実行では両ドライバ・2 SQL検証ともPASS。
- `scripts/test-product-v1-real-server-guard.sh`は**DB接続なしで6件の拒否試験PASS**：明示opt-in欠落、リモートDBホスト、製品らしいDB名、非Postgres URL、libpqの`?host=`上書き、許可しないDB名prefix。
- 既存回帰：`test-ingress-sqlite-receipts.sh` **284項目PASS**、`test-ingress-journal-prototype.sh` **75項目PASS**、合計**359項目PASS**。
- これはJVMプロセス／通常Linux仮想環境での試験であり、実物理電源断やWindows x86/32bit・実ECU動作試験とは同義ではない。

## 安全ゲートと手動再現

E2Eスクリプトは最初に`ROMLOGGER_E2E_DISPOSABLE=1`、ループバック先`localhost/127.0.0.1`、DB名`romlogger_*_e2e`、Postgres URL形式、hostを書き換えるURLパラメータがないことを検査する。条件未達なら**DB migration実行前**に停止する。

接続情報を引数やログへ露出させないようにし、デバイストークンは`mktemp`の一時ファイルをmode 600で扱い、終了時に削除。FastAPIは127.0.0.1:18083にテスト実行中だけ起動し、終了時にプロセスを停止する。

実行例（DBクラスタと新しい使い捨てDBを**事前に自身で作成**した場合のみ）：

```bash
cd /home/jr2twt/git/romlogger-4b2c-agent-20261013
bash scripts/test-product-v1-real-server-guard.sh
bash scripts/test-product-v1-spool.sh
export PATH="$PWD/.venv/bin:$PATH"
export ROMLOGGER_E2E_DISPOSABLE=1
export ROMLOGGER_SERVER_PATH=/home/jr2twt/git/romlogger-4b2c-server-20261013
export DATABASE_URL=postgresql://jr2twt@127.0.0.1:25432/romlogger_new_e2e
bash scripts/test-product-v1-real-server.sh
```

**使い捨てDBの再利用はしない**。SQL検証は対象デバイスの受信件数が0から増える前提であるため、再試験時は新規`*_e2e`データベースを使う。

## GitHub Actionsの判定

- `product-v1-real-server-e2e.yml` はcross-private-repoの読取専用secret `ROMLOGGER_SERVER_READ_TOKEN`がない場合、実Server checkoutと本物のE2Eを**SKIPPED**にする仕様を維持する。job自体がsuccessでも、実Server PASSとは報告しない。
- secretがない場合でも、今回追加した**誤接続防止の6件の負試験**だけはCIで実行する。
- [PR #23製品SQLite CI #38387493290](https://github.com/zawa356/RomRaider_agent/actions/runs/38387493290) はUbuntu/Windows双方の実SQLiteコンパイル試験PASS。[E2EゲートCI #38387493427](https://github.com/zawa356/RomRaider_agent/actions/runs/38387493427)は6件ガードの実行ステップ成功、一方で本物のAgent→FastAPI→PostgreSQLステップは**SKIPPED**である。
- secretが設定されている場合にのみ、ワークフローが最新AgentからServerソースをcheckoutし、Disposable PostgreSQLサービスでTSV/journal両方のE2Eを実行する（実際に走ったかはjob stepsで確認する）。

## 残課題

1. Agentの運用Workerから`importJournal`を選択的に呼び出す安全なフラグ／切替／事前チェックを設計する。現在も既存Loggerや実Workerへ**自動接続していない**。
2. source UUID sidecarと原本の二ファイル整合、8MiB上限と全履歴再走査、ジャーナル回転・ディスク逼迫時の安全な停止、実サーバー切断の長期耐久を検証。
3. Windows 32bit／Zulu8／実装入出力の遅延とメモリ圧、停車中の監督付き実機検証は別途承認が必要。実車運転中に勝手な測定変更はしない。
4. サーバー側の確定ACKは**今回の合成HTTP交換に限定して実証**。CloudPCの成功を物理電源断での欠損ゼロ保証と解釈しない。

**判定：最新Agentの両入力経路から実FastAPI/PostgreSQL16の厳格ACKまで、CloudPC隔離E2E PASS。製品ランタイム配線・実車・物理電源断は保留。**
