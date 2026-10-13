# RomLogger 車載Agent引継ぎ（2026-10-13、チャット上限対応）

> **全体状況・最新SHA・Server API/CI・次Phaseの正本:** [Server側の詳細引継ぎ](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_chat_handoff.md)。本ファイルはAgent固有の構成と作業上の注意を追記する。作成時点の記録であり、**作業再開時は最新main/PR/CIを再確認**。

## 最新の確認時点（2026-10-13 16時台 JST）

- Agent repo `zawa356/RomRaider_agent` `main`: `f2fc8dc60d7af0d21e62deec72abb9e0cef9e477`（引継ぎコミット前）、[PR #13](https://github.com/zawa356/RomRaider_agent/pull/13) Phase 2.1-D2b-2b公平配送までmerge。
- Server repo `zawa356/RomRaiderServer` `main`: `673e3f931b856943d3aaa8f4185a58663e01db68`（引継ぎコミット前）。
- 両repoとも未マージPRなし（確認時点）。
- [Windows+Linux SQLite CI #38361610506](https://github.com/zawa356/RomRaider_agent/actions/runs/38361610506)：両OS成功、`FairDeliveryTest` 19チェックずつ成功。
- **重大な誤認防止:** [Agent E2E #38361610750](https://github.com/zawa356/RomRaider_agent/actions/runs/38361610750)はワークフロー全体`success`だが、`ROMLOGGER_SERVER_READ_TOKEN`不在でServer checkoutと実E2Eステップは**skipped（NOT RUN）**。Agent最新mainと実Server/PostgreSQLのE2E成功ではない。
- **実行済みの合成E2Eは別:** Server側[CI #38350205850](https://github.com/zawa356/RomRaiderServer/actions/runs/38350205850)でGit blob SHA固定版Agent（`668ad5230e7e3db4fc592311c857906f43802ad3`）から実FastAPI/PostgreSQL 16への転送・再送試験が成功。ただし最新Agentではない。

## 構成・実装済み

| Phase | 変更 | 主な実装 |
| --- | --- | --- |
| 2.1-A | SQLite WAL/FULL spool /原本record ID/ACK | `DurableSpool.java`、[PR #3](https://github.com/zawa356/RomRaider_agent/pull/3) |
| 2.1-B | Java HTTPS/JSON送信/厳格ACK | `ProductSender.java`・`AgentWire.java`、[PR #4](https://github.com/zawa356/RomRaider_agent/pull/4) |
| 2.1-C1 | 旧PoC TSV→製品v1 SQLite取込 | `LegacyTsvBridge.java`、[PR #5](https://github.com/zawa356/RomRaider_agent/pull/5) |
| 2.1-D1 | 増分TSV読み＋永続offset/CAS/定義snapshot | `IncrementalTsvBridge.java`、[PR #8](https://github.com/zawa356/RomRaider_agent/pull/8) |
| 2.1-D2b-1 | SQLite永続backoff・NEEDS_REVIEW/独立Worker/ソース欠損検出 | `ProductDeliveryCoordinator.java`、`ProductBridgeWorker.java`、`ProductLegacyScanner.java`、[PR #11](https://github.com/zawa356/RomRaider_agent/pull/11) |
| 2.1-D2b-2a | 旧TSV writer IOException後の再試行/fsync、overflowマーカー、旧ACKだけによるファイル削除禁止 | `Stream.java`、[PR #12](https://github.com/zawa356/RomRaider_agent/pull/12) |
| 2.1-D2b-2b | **3回古いrun、4回目最新run**のACK成功時のみスロット進行。SQLiteへ永続保存 | `DurableSpool.java`、`ProductSender.java`、`FairDeliveryTest.java`、[PR #13](https://github.com/zawa356/RomRaider_agent/pull/13) |

**PoCと製品の違い:** `src/main/java/net/neristal/rrbridge/Stream.java`は既存RomRaider callback→TSV writer/旧Receiver転送。`product/v1/`はSQLite/JSON/Serverへ独立送信する製品モジュール。旧PoC`docs/10_interfaces.md`と新Serverの[正式v1契約](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md)は互換とは限らない。製品Workerは明示`--once`/`--foreground`で別JVM起動、RomRaider JVMには自動統合しない。

## 直近最重要の未解決事項

1. **D2b-2c：旧PoC bounded memory queue飽和と原本欠損を防ぐ/最小化する。** 既存のqueue上限超は計測原本の再構成が不可能。今は`E queue_overflow lost_lines=N`を記録するのみ。Logger callback非ブロック、元TSV耐久、CPU/メモリ負荷、過負荷継続時間、クラッシュ時のデータ保全を検討し、合成負荷・I/O故障テストで段階的に検証。
2. **最新Agent（Worker、公平配送を含む）の実Server E2E。** Agent側ワークフローのtoken未設定によるスキップを解消するか、Server側で最新Agentソースを再pin＋Git blob SHA照合して実FastAPI/PGの合成E2Eを実行。**CI green と E2E actually executed を区別**。
3. **D3実車・Windows検証:** ThinkPad X270のZulu 8 **32bit**でJDBC nativeとJava Agent実行、停車中K-Line／RomRaiderからの実測、disk-full/物理電源断、長時間・大量runの耐久。GitHub windows-latest JDK17 `--release 8`では代替不可。
4. Worker 413/409/422隔離、Retry-After/jitter、ローカルバックログ容量閾値と通知、元TSVの保持運用、承認ゲートの運用。本番自動配備と実車AI操作は未実装。

## 次のチャットの実行要領

- ユーザーは細かい実装判断の逐次承認を要求しない。**短いPhaseに分け、GitHub Actions CI/CD、故障注入、こまめなGit commit、PR、履歴ファイル更新、mainマージ**を優先。
- 各コミットの理由・CI成功/失敗・既存PoCからの影響を`docs/product_v1_implementation_progress.md`、`docs/07_history.md`、`docs/08_decisions.md`（ADR）へ記録。接続規約変更時は`docs/10_interfaces.md`更新（旧PoC）またはServer正式通信契約とテスト更新（新v1）。
- SRS v1.0・設計正本はServer repo。ROM読み書き・ECUリセット・DTC消去・走行中の無承認切替をしない。未ACK原本を削除しない。Secretsをコミットしない。実車接続やデプロイをCI successだけで解禁しない。
- **同時進行した別PR・別チャットの更新があり得るため、次回は必ず最新main SHAとopen PRを再確認。** 既に完成したPhase 2.1-Bを重複開発しない。
- 全体引継ぎ本文は[Server側の完全版](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_chat_handoff.md)。次のチャットではまずこれを読む。
