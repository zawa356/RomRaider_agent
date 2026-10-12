# 02. システム全体の構成

このリポジトリ（RRBridge）は、全体構成のうち「車載X270上のRomRaider Adapter／データ取り出し」を担う。
全体はまだ構想段階で、確定しているのは RRBridge の方式（`-javaagent`）と、転送経路の考え方（spool → HTTP受信API → DB）まで。

## 1. 三層構成（引継ぎ資料 §2 の有力候補、未確定）

```
                 【運転者・AIクライアント】
          スマホ音声（第一候補: Claudeモバイルの音声モード）
          ChatGPT / Claude（通常チャット）
                          │  （AI各社のクラウドから接続）
                    MCP / HTTPS・認証
                          ▼
        ┌──────────────────────────────────┐
        │ サーバー（VPS 推奨。自宅PC＋Tunnelも可）│
        │  Caddy（443のみ公開、証明書自動）     │
        │   ├ /ingest  受信API（Telemetry）    │
        │   ├ /mcp     Remote MCP Server        │
        │   │   状態照会・区間統計・上限付きSQL │
        │   │   イベントマーカー登録            │
        │   └ /plan    計測計画の提出（将来）    │
        │  PostgreSQL（外部非公開）             │
        └───────────────▲──────────────────────┘
                        │ HTTPS（X270発のみ。X270への着信はさせない）
        ┌───────────────┴──────────────────────┐
        │ BP5車載 X270（Windows 10）            │
        │  Supervisor（未実装）                  │
        │   計測計画の受信・検証・適用、状態管理 │
        │  RRBridge（このリポジトリ）            │
        │   Agent: コマンド受付・状態            │
        │   Stream: データ取り出し・spool・転送  │
        │  RomRaider Logger（同じJVM）           │
        │  OpenPort 2.0 互換機（J2534）          │
        └───────────────┬──────────────────────┘
                        │ SSM / ISO9141（K-Line）
                      BP5 ECU（ID 2F04785006）
```

### 責務分割

| 領域 | 責務 | 担当／候補 | 状態 |
| --- | --- | --- | --- |
| ECU通信・計測 | ECUからパラメーターを取得 | RomRaider＋OpenPort | 実車で動作実績あり（2026-09-21） |
| Adapter | ロギング開始停止、CSV記録、プロファイル切替、状態 | RRBridge `Agent` | 実装済み・実機確認済み |
| データ取り出し | 1応答＝1サンプルの換算値をCSVを経由せず取得 | RRBridge `Stream` | 実装済み・合成データで確認 |
| ローカル保持 | 通信断時のデータ保全（正本） | RRBridge spool（TSV） | 実装済み |
| 転送 | HTTP POST、再送、重複排除前提 | RRBridge `Stream` | 実装済み |
| 受信・蓄積 | 受信API、DB保存 | 試作: `Receiver`（SQLite）／本番: VPS上（未実装） | 試作のみ |
| Supervisor | 計測計画の実行、検証、区間の区切り、承認範囲 | 未実装（X270常駐。言語未定、Python候補） | 未着手 |
| MCP | AIクライアントへの読み取りツール・限定的な書き込み | 未実装（Python公式SDK候補） | 未着手 |
| AI判断 | 次の計測項目の提案 | ChatGPT／Claude（起動方法が未解決） | 未解決 |
| 音声UI | 状況確認、イベントマーカー | Claudeモバイル音声（要検証） | 未検証 |

## 2. サーバー構成の提案（2026-10-12、未決定）

ユーザーから「VPS（レンタルサーバー）でOSやWebサーバーは未定。自宅PCにVPNでSQLサーバーも可能」との発言を受けて提案したもの。

### 2.1 前提となる調査結果 [調査 2026-10-12]

- **MCPサーバーはインターネットから直接届く必要がある。** ClaudeのカスタムコネクタもChatGPTも、スマホ本体ではなく各社のクラウドからMCPサーバーへ接続する。Claudeのヘルプには「VPNの内側やプライベートネットワーク、ファイアウォールで遮断されたサーバーには接続できない。プライベートな場合はAnthropicのIP範囲を許可する」とある。
  - 出典: https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp
- **Claudeのカスタムコネクタ**: Free（1つまで）／Pro／Max／Team／Enterpriseで利用可能。Pro・Maxは Customize > Connectors から個人で追加できる。接続は claude.ai、Desktop、Cowork、モバイルアプリのすべてでAnthropicのクラウドから行われる。認証は OAuth、「サインインなし」、または**固定の資格情報（APIキーやBearerトークンをリクエストヘッダーで送る）**から選べる。
- **Claudeの音声モード**: iOS／Android／Desktop／Webで使え、「接続済みのツール（Gmail、Googleカレンダー、Googleドキュメント、Slackなど）を使える」と案内されている。**カスタムコネクタが音声モードで使えるかは明記されていない** → 要実機検証。
  - 出典: https://support.claude.com/en/articles/11101966-use-voice-mode
- **ChatGPTの開発者モード（自作MCP）**: Business／Enterprise／EduのWeb版で利用可能。書き込み系はベータ。**Proは読み取り（read/fetch）のみ**。**MCPアプリはWeb専用でモバイル非対応**と案内されている。書き込み操作はChatGPTが確認を求めることがある。ローカルや社内のサーバーには「Secure MCP Tunnel」を推奨。
  - 出典: https://help.openai.com/en/articles/12584461-developer-mode-apps-and-full-mcp-connectors-in-chatgpt
- **ChatGPTの音声モードとMCP**: 2026-05-30／31にコミュニティで「テキストでは動くが音声モードに切り替えると失敗する（Web・Android）」との報告。公式回答なし。
  - 出典: https://community.openai.com/t/chatgpt-support-of-mcp-in-voice-mode-on-web-and-android/1382072
- 結論: **音声UIの第一候補はClaudeモバイル**。引継ぎ資料で想定していた「Android ChatGPT Live＋自作Remote MCP」は、現時点では成立しない可能性が高い。

### 2.2 推奨: VPS 1台

| 層 | 提案 | 理由 |
| --- | --- | --- |
| OS | Ubuntu Server 24.04 LTS | 情報量、長期サポート |
| 拠点・規模 | 国内リージョン、2vCPU／2〜4GB | データ量は小さい（8Hz×10項目の縦持ちで1時間あたり約29万行） |
| Webサーバー | **Caddy** | 役割はリバースプロキシとTLS終端だけ。Apache／Nginxより設定が短く、証明書の取得・更新が自動 |
| DB | **PostgreSQL**（MariaDBでも可） | 読み取り専用ロールに `statement_timeout` を設定でき、LLMにSQLを実行させる際に重いクエリを自動で打ち切れる。統計関数が揃っている。将来TimescaleDBも追加可能 |
| アプリ | Python（受信API＋MCPサーバーを同居） | ユーザーが普段使う言語。MCPの公式Python SDKがある |
| 配置 | Docker Compose | 再構築・自宅PCへの移設が容易 |

セキュリティの最低ライン:
- 外部に開けるのは443のみ。SSHは鍵認証、またはTailscale経由に限定。
- DBは外部非公開。
- トークンは受信API用とMCP用で分ける。
- MCPの道具は「状態照会」「区間の統計」「上限付きの読み取りSQL」「イベントマーカー登録」に絞る。

### 2.3 自宅PC案との比較

| | VPS | 自宅PC＋Cloudflare Tunnel |
| --- | --- | --- |
| 費用 | 月額あり | 追加ほぼなし |
| 常時稼働 | 前提 | 自宅PCの24時間稼働が必要 |
| 外部公開 | 443を開ける | 外向きトンネル（ポート開放不要） |
| 障害点 | VPSのみ | PC・自宅回線・停電 |
| 計算資源 | 小 | 大 |

VPNだけではAIクライアント（各社クラウド）からMCPに届かないため、自宅案は実質「Cloudflare Tunnel等で公開する案」になる。Docker Composeで組めば両者で同じ構成を使える。

### 2.4 「RomRaiderから直接SQLへ」ではなく受信APIを挟む理由

- DBをインターネットに公開しなくてよい。
- 再送・重複排除（`session+seq`）・バリデーションを受信側で一括して扱える。
- 認証をトークンで扱える。DBの資格情報を車載PCに置かずに済む。
- X270の `sink.properties` の `url`／`token` を書き換えるだけで、試作（X270内SQLite）→ VPS へ切り替えられる。

### 2.5 実装状況（2026-10-12 13:30）

推奨構成の中身（受信API＋MCP）を `server/` に試作済み。Python 3.13、`mcp` 2.3.0（Streamable HTTP、stateless、JSONレスポンス）、psycopg 3.3.6、uvicorn 0.53.0、Docker Compose（postgres:16、caddy:2、app）。ツールは `get_vehicle_status` など8つ（`server/README.md` §3）。VPS・ドメインは未決定のため未配置。

## 3. 推奨された進め方（2026-10-12）

1. **音声モードの適合性を最初に検証**: ダミー値を返すだけのMCPを一時公開（Cloudflare Tunnelの一時URLで可）し、Claude Androidの音声モードから呼べるか、書き込み系ツールで確認が出るかを試す。結果次第で音声部分の設計が変わる。
2. VPS契約 → Caddy、PostgreSQL、受信API、MCPを構築。X270は `sink.properties` を書き換える。
3. 実車で走行し、ECUデータの到着と通信断後の再送を確認。

LLMの無人定期起動（未決事項1）は別課題。サーバー側で区間ごとの統計を自動計算しておけば、どの起動方式でも再利用できる。

## 4. 未確定の論点（引継ぎ資料 §9 より、2026-10-12時点の状況を付記）

1. LLMの自律起動方法 — 未解決。
2. 音声＋自作MCP — ChatGPTは厳しい見込み、Claudeは要検証（上記）。
3. RomRaiderの制御実装 — `-javaagent` 方式で解決（`08_decisions.md` D-002）。
4. ロガーの実車互換・速度 — 9/21のログで周期と項目数の関係を把握（`05_environment.md`）。項目数上限は未決定。
5. 計測計画モデル — 未着手。
6. データモデルと保管 — 縦持ち、spool正本、受信APIで重複排除まで決定。本番DBは未決定（PostgreSQL推奨）。
7. VPS／公開方式 — 提案済み、未決定。
8. MCPツールの最小権限・検証仕様 — 方針のみ。
9. ログ解釈の妥当性 — 未着手。
