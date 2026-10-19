# docs/ 索引

**2026-10-20 Phase VI (未マージ):** [branch Bootstrap](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/ops/development_environment_bootstrap.md) → [報告](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/handoff/phase6_overnight_report.md) → [証跡](https://github.com/zawa356/RomRaider_agent/blob/phase6/transition-drive-preflight/docs/handoff/phase6_overnight_evidence.md)。mainではなく#80上のPR81–85にstack。実Writer・drive・preflight・transition・親差替え。Writer/process/durability未証明のため起動/本番/ACKは無効。

**2026-10-19 Phase III current:** [Bootstrap](ops/development_environment_bootstrap.md) → [最終報告](handoff/codex_phase3_report.md) → [製品進捗](product_v1_implementation_progress.md)。[証跡](handoff/phase3_validation_evidence.md) / [追記worklog](handoff/codex_phase3_worklog.md)。固定配置・診断・隔離copyを受入。本番切替/次Session再binding/正式ACKは未成立。

**2026-10-19 Phase II:** [環境Bootstrap](ops/development_environment_bootstrap.md) → [最終報告](handoff/codex_phase2_report.md) → [製品進捗](product_v1_implementation_progress.md)。共通Lane/協調公開/隔離migration候補。実車・本番移行・新ACKは未解禁。

**2026-10-14 日中の最新結果:** [日中報告](handoff/codex_daytime_report.md)／[worklog](handoff/codex_daytime_worklog.md)。3a比較・actual Zulu8/x86・native owned-handle flush・post-flush integrity修正の4小Phase統合。旧RLC1 Windows watermarkとdirectory durabilityは未成立、製品配線なし。

**2026-10-14 Codex日中最新版**：[Agent引継ぎ](handoff/2026-10-14_codex_day_autonomy.md)／[Server全文](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-14_codex_day_autonomy.md)／[worklog](handoff/codex_daytime_worklog.md)。旧夜間文書は履歴。


**2026-10-14の終了・再開点:** [夜間開発報告](handoff/codex_overnight_report.md)、[追記worklog](handoff/codex_overnight_worklog.md)。4b-2d-2b-1は合成PoC統合済み、2b-2はLinux成立/Windows watermark BLOCKED、2b-3結合は未実装。運用Workerへは配線していない。

**最新Codex夜間開発の入口:** [Agent側引継ぎ](handoff/2026-10-13_codex_overnight.md)、[Server側全文・正本](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_codex_overnight.md)。[旧チャット引継ぎ](handoff/2026-10-13_chat_handoff.md)は過去の基準点。次回は最新main・open PR・CI・夜間worklogを必ず照合。

| ファイル | 内容 | 主な読者 |
| --- | --- | --- |
| [RomLogger製品全体の要件定義書（別リポジトリ／正本）](https://github.com/zawa356/RomRaiderServer/blob/main/docs/system_requirements.md) | サーバー・クライアント・MCP連携を横断する承認済み要件。既存PoCの暫定制限より製品要件として優先するが、現行安全ガードを無断で変更しない | 全員 |\n| [製品基本設計書・論理データ・MCP・安全検証（Server側正本）](https://github.com/zawa356/RomRaiderServer/blob/main/docs/README.md) | BD-001〜008、要件全件の追跡、責務分界、設計未検証の区別 | 設計・実装 |\n| [製品 Server–Agent通信契約v1](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md) | HTTPS/JSON、原子的DB保存後ACK、UUID、冪等、状態・操作要求。**下記10_interfaces.mdのPoC契約とは別世代** | 実装 |\n| [Windows Agent詳細設計](https://github.com/zawa356/RomRaiderServer/blob/main/docs/agent_detailed_design.md) | 既存Java Agent再利用候補、spool／転送／Supervisor／安全判定・動作モード | 実装・検証 |\n| [製品の契約・受入試験計画](https://github.com/zawa356/RomRaiderServer/blob/main/docs/test_acceptance_plan.md) | 合成転送・実ECU・安全・MCPの段階ゲート。未検証項目は未合格扱い | 検証 |
| [`product_v1_integration.md`](product_v1_integration.md) | Phase 2.1-Cの旧TSV importer、実Server E2E、Private repo CIの検証区分 | 実装・試験 |\n| [`product_v1_http_transport.md`](product_v1_http_transport.md) | 製品版Agent v1のHTTPS／JSON送信、ACK条件、通信エラー、E2E未検証事項 | 実装・試験 |\n| [`product_v1_incremental_import.md`](product_v1_incremental_import.md) | Phase 2.1-D1のSQLite永続offset、TSV追記処理、クラッシュ復旧と既知制約 | 実装・試験 |\n| [`product_v1_delivery_worker.md`](product_v1_delivery_worker.md) | D2b-1の明示起動Worker、SQLite再試行・要調査、旧PoC上流欠損リスク | 実装・安全確認 |
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

- [Codex acceleration final report (2026-10-18)](handoff/codex_acceleration_report.md): Phase A–H code/PR/CI evidence, limits and resume plan.

- [Phase I product integration final report (2026-10-18)](handoff/codex_product_integration_report.md): versioned multi-source candidate, actual Server HTTP/PG outages, platform evidence and exact resume/gates.
