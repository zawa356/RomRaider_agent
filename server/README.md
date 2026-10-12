# server/ — 受信API＋Remote MCP サーバー（試作 v0.1.0）

RRBridge（X270）から送られてくるTSVを受けてDBに保存し、同じデータを AI クライアント（Claude のカスタムコネクタ等）に
**Remote MCP（Streamable HTTP）**として公開するサーバーです。`docs/02_architecture.md` §2 の推奨構成（VPS 1台）の中身にあたります。

- 状態: **試作**。コンテナ内で SQLite と PostgreSQL 16 の両方に対して自動試験36件がPASS。X270の実際のspool（selftest 480サンプル）を流し込み、MCP経由で照会できることを確認済み。
- 未確認: Docker イメージのビルドと docker compose での起動（作業環境にDockerデーモンがなく、`docker compose config` で構文のみ確認）、実際のVPS・ドメインでの運用、Claude からの接続（音声モード含む）。

## 1. 構成

```
rrserver/
  __init__.py    バージョン
  db.py          DBアクセス層。DATABASE_URL で PostgreSQL / SQLite を切替。読み取り専用SQLの実行（多重防御）
  ingest.py      TSV（M/S/E行）の取り込み。Java版 Receiver.java と同じ規則・同じスキーマ。session+seq で冪等
  analysis.py    MCPツールの中身（状態、区間、統計、推移、イベント、メモ、SQL）。MCPに依存しない純粋関数
  app.py         ASGIアプリ。/ingest、/health、/mcp（MCPServer, stateless, JSONレスポンス）、Bearer認証
tests/           pytest（SQLite、RR_TEST_PG_URL があれば PostgreSQL でも）
Dockerfile / docker-compose.yml / Caddyfile / .env.example
```

使用ライブラリ（2026-10-12 時点で動作確認した版）: `mcp==2.3.0`（**v2系**。v1の `FastMCP` は `mcp.server.mcpserver.MCPServer` に改名されている）、`psycopg[binary]==3.3.6`、`uvicorn==0.53.0`（starlette 1.6.0 が付随）。Python 3.13。

## 2. エンドポイント

| パス | 方法 | 認証 | 内容 |
| --- | --- | --- | --- |
| `/health` | GET | なし | `{"ok":true,"service":"rrbridge-server","version":…,"db":"postgres"}` |
| `/ingest` | POST | `INGEST_TOKEN`（Bearer） | RRBridge の転送形式（TSV）。応答 `{"ok","samples","measurements","events","duplicates","rejectedLines","maxLagMs"}` |
| `/mcp` | POST | `MCP_TOKEN`（Bearer） | MCP Streamable HTTP（stateless、JSONレスポンス）。トークン不一致は401 |

トークンを空にすると認証なし（ローカル試験専用。公開環境では必ず設定する）。`hmac.compare_digest` で比較。
`ALLOWED_HOSTS` を設定するとHostヘッダーを検査する（DNSリバインディング対策）。

## 3. MCPツール

| ツール | 種別 | 内容 |
| --- | --- | --- |
| `get_acquisition_health(window_sec)` | 読取 | 取得周波数（受信したサンプル時刻から計算）、項目数、RRBridgeの直近 `health`・`guard_revert`・`link_error` イベント、警告（2Hz未満、15秒以上無受信、1分以内の通信エラー） |
| `get_vehicle_status` | 読取 | （`acquisition` 欄に上記の健全性も含む）最新セッションの受信状態（最新サンプルの経過秒、転送遅延、10秒以内に受信があるか）、全項目の最新値（名称・単位付き）、直近イベント5件 |
| `list_sessions(limit)` | 読取 | セッション一覧（サンプル数、長さ、区間数） |
| `list_segments(session)` | 読取 | 区間一覧（サンプル数、長さ、周波数、項目名） |
| `get_segment_stats(session, segment, last_seconds)` | 読取 | 項目ごとの件数・最小・最大・平均・標準偏差。直近N秒だけも可 |
| `get_recent_values(parameter, seconds, max_points)` | 読取 | 1項目の直近の時系列（間引き）。ID（P8）か名称の部分一致（engine） |
| `list_events(session, limit)` | 読取 | イベント（RRBridge由来と運転者メモ） |
| `query_telemetry(sql, max_rows)` | 読取 | SELECT/WITH 1文のみ。3秒で打ち切り、最大500行 |
| `add_event_marker(text)` | 書込（追記のみ） | 運転者メモを現在のセッション・区間に記録（source=driver）。車両は操作しない |

全ツールに MCP の `annotations` を付与（読み取り系は `readOnlyHint=true`、メモは `readOnlyHint=false, destructiveHint=false`）。
サーバーの `instructions` に「まず get_vehicle_status」「根拠が足りなければそう言う」「車両は操作できない」「運転中は短く」を記載。

### 読み取り専用SQLの防御（`Database.readonly_select`）
1. 文の検査: 1文のみ（`;` 禁止）、`SELECT`／`WITH` で始まる、禁止語（insert, update, delete, drop, alter, create, pragma, attach, set, copy など）を含まない（文字列リテラル内は除外）。
2. 実行環境: PostgreSQL は `SET TRANSACTION READ ONLY` ＋ `SET LOCAL statement_timeout`。SQLite は `mode=ro` の別接続＋進捗ハンドラーでの打ち切り。
3. 結果: `max_rows`（最大500）で切り詰め、`truncated` を返す。
本番では、さらにDBに読み取り専用ロールを作り、その接続で実行するのが望ましい（未実装、`docs/09_open_issues.md`）。

## 4. ローカルでの試験

```bash
cd server
pip install -r requirements-dev.txt
python -m pytest -q tests                      # SQLite（20件）
RR_TEST_PG_URL=postgresql://user@127.0.0.1:5432/db python -m pytest -q tests   # PostgreSQLも（計36件）
```

手動起動:

```bash
DATABASE_URL=sqlite:///telemetry.db INGEST_TOKEN=ing MCP_TOKEN=mcp uvicorn rrserver.app:app --port 8000
curl -X POST -H "Authorization: Bearer ing" --data-binary @rr_20261012_125843.tsv http://127.0.0.1:8000/ingest
curl -X POST http://127.0.0.1:8000/mcp -H "Authorization: Bearer mcp" \
  -H "Accept: application/json, text/event-stream" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_vehicle_status","arguments":{}}}'
```

## 5. VPS への配置（案、未実施）

前提: Ubuntu Server 24.04 LTS、Docker Engine＋compose plugin、ドメインのAレコードをVPSのIPに向ける。

```bash
git clone <this repo> && cd RomRaider_agent/server
cp .env.example .env    # DOMAIN、POSTGRES_PASSWORD、INGEST_TOKEN、MCP_TOKEN を設定（openssl rand -hex 32 など）
docker compose up -d --build
curl https://<DOMAIN>/health
```

- 外部に出るのは Caddy の 80/443 のみ。Caddy は `/ingest`、`/mcp`、`/health` だけを通し、それ以外は404。PostgreSQL は外部非公開。
- ファイアウォール（ufw 等）で 22/80/443 以外を閉じる。SSHは鍵認証のみ、可能ならTailscale経由に限定。
- X270 側: `RRBridge\sink.properties` を `url=https://<DOMAIN>/ingest`、`token=<INGEST_TOKEN>` に変更し、RomRaider を再起動。
- Claude 側: カスタムコネクタに `https://<DOMAIN>/mcp` を追加し、認証に固定の Bearer トークン（`MCP_TOKEN`）を設定。

## 6. 音声モードの適合性を先に試す方法（VPS契約前）

`docs/02_architecture.md` §3 の手順1。MCPは各社のクラウドから接続されるため、一時的にでも公開URLが必要。

1. Python が使えるPC（自宅PCなど）でサーバーを起動（SQLiteで可、`MCP_TOKEN` は必ず設定）。
2. Cloudflare の一時トンネルで公開: `cloudflared tunnel --url http://localhost:8000` → `https://<ランダム>.trycloudflare.com` が表示される。
3. テスト用データを入れる: X270 の `spool\rr_*.tsv` を `/ingest` にPOSTするか、RRBridge の `sink.properties` を一時URLに向けて `selftest_stream` を実行。
4. Claude（Web）の設定でカスタムコネクタを追加: URL `https://<ランダム>.trycloudflare.com/mcp`、Bearer トークン `MCP_TOKEN`。
5. Android の Claude アプリの音声モードで「車の状態を教えて」「いまのエンジン回転数は」「メモして、3速で息つき」などと話し、ツールが呼ばれるか、`add_event_marker` で確認が求められるかを見る。
6. 終わったらトンネルを止める（URLは毎回変わる）。
