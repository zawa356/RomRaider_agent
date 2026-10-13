# docs/ 索引

| ファイル | 内容 | 主な読者 |
| --- | --- | --- |
| [RomLogger製品全体の要件定義書（別リポジトリ／正本）](https://github.com/zawa356/RomRaiderServer/blob/main/docs/system_requirements.md) | サーバー・クライアント・MCP連携を横断する承認済み要件。既存PoCの暫定制限より製品要件として優先するが、現行安全ガードを無断で変更しない | 全員 |\n| [製品基本設計書・論理データ・MCP・安全検証（Server側正本）](https://github.com/zawa356/RomRaiderServer/blob/main/docs/README.md) | BD-001〜008、要件全件の追跡、責務分界、設計未検証の区別 | 設計・実装 |\n| [製品 Server–Agent通信契約v1](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md) | HTTPS/JSON、原子的DB保存後ACK、UUID、冪等、状態・操作要求。**下記10_interfaces.mdのPoC契約とは別世代** | 実装 |\n| [Windows Agent詳細設計](https://github.com/zawa356/RomRaiderServer/blob/main/docs/agent_detailed_design.md) | 既存Java Agent再利用候補、spool／転送／Supervisor／安全判定・動作モード | 実装・検証 |\n| [製品の契約・受入試験計画](https://github.com/zawa356/RomRaiderServer/blob/main/docs/test_acceptance_plan.md) | 合成転送・実ECU・安全・MCPの段階ゲート。未検証項目は未合格扱い | 検証 |
| [`product_v1_implementation_progress.md`](product_v1_implementation_progress.md) | **製品版AgentのPhase実装・CI・修正履歴**。PoCと製品v1の区別、Linux/Windows SQLite耐久試験 | 実装・試験 |\n| `01_requirements.md` | ユーザーの要求・制約・前提（会話で明示されたもの） | 全員 |
| `02_architecture.md` | システム全体の構成（車載X270／サーバー／AIクライアント）、サーバー構成案、MCPクライアント調査 | 設計 |
| `03_rrbridge_design.md` | このリポジトリのコード設計（クラス、スレッド、コマンド仕様、転送プロトコル、DBスキーマ、既知の制約） | 実装 |
| `04_romraider_internals.md` | 依存しているRomRaider内部（クラス・フィールド・行番号）、プラグイン機構、相対パス依存、CSV出力の挙動 | 実装・保守 |
| `05_environment.md` | X270実機の環境情報（パス、バージョン、ECU ID、設定内容、既存ログ一覧と周期） | 検証 |
| `06_verification.md` | 実機検証の手順・結果・数値 | 検証 |
| `07_history.md` | 時系列の作業履歴（ChatGPT時代の検討〜2026-10-12のCowork作業、失敗と対処を含む） | 全員 |
| `08_decisions.md` | 決定記録（ADR形式：背景・選択肢・決定・結果） | 設計 |
| `09_open_issues.md` | 未解決事項、技術的負債、次の作業と実車試験計画 | 全員 |
| `10_interfaces.md` | **接続規約**: すべての接続口（RomRaider内部、操作ファイル／HTTP、状態、プロファイル、設定、転送形式、受信API、MCPツール、DBスキーマ、将来の接続口）の入出力・認証・エラー・互換性規則。接続口を変えたら必ず更新 | 全員 |
| `handoff/` | 会話間の引継ぎ資料（原典、全文） | 全員 |
| `../archive/README.md` | X270から回収した実データと検証記録（実車CSV、spool、応答JSON、ログ、受信DB、RomRaider設定） | 解析・検証 |
| `../server/README.md` | 受信API＋Remote MCPサーバー（Python）の構成・ツール・試験・配置手順・音声モード検証手順 | 実装・運用 |

記述の確度の表記:
- **[実機確認]** X270実機で動かして確認したこと
- **[コード調査]** RomRaiderのソースを読んで確認したこと（実行はしていない）
- **[ユーザー発言]** ユーザーが会話で述べたこと
- **[調査]** Web等の公開情報で確認したこと（日付つき）
- **[推測]** 根拠はあるが未確認のこと
