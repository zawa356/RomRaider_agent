# RomLogger Codex夜間開発 — Agent側必読入口（2026-10-13）

> **全体の運用規約・禁止事項・CloudPC/Git手順・今夜のPhase・中断復帰フローの正本**：
> [Server「Codex無人開発・再開指示書」](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_codex_overnight.md)
>
> 本書はAgent固有の入口。**過去の `docs/handoff/2026-10-13_chat_handoff.md` は16時台の旧スナップショットであり最新状態ではない。**

## 現在地（2026-10-13 22時頃JST、読込時に必ず再取得）

- Agent baseline main：`022d9daf36c957d62a2ff91b7ce61c690ebac779`（[PR #25](https://github.com/zawa356/RomRaider_agent/pull/25) マージ）
- Server baseline main：`6d9db50aaa12e216e677c2a0bf9fc34216f2eb36`（[PR #37](https://github.com/zawa356/RomRaiderServer/pull/37) マージ）
- 作成前のopen PR：両repo0件。**本引継ぎPRのmerge後はSHAが変わる**。
- 最新Phase `2.1-D2b-2c-4b-2d-2a`：Linuxで`LiveJournalReadProbe`合成読取PASS。Windows mandatory FileLockでwriter保有中のreadは**BLOCKED**。[初回Win FAIL](https://github.com/zawa356/RomRaider_agent/actions/runs/38400570091)。最終CIは両OS試験PASSだがWindowsライブ動作は未実現。
- CloudPC Linux：journal/handoff99件＋製品SQLite/receipt344件＝**443件PASS**。既存の4b-2d-1では実FastAPI/PostgreSQL16 E2E（7合成records/3profiles、重複0）PASS済み。4b-2d-2aでは本物のServer E2Eは再実行していない。
- 引き続き`ProductBridgeWorker --journal-once`は明示opt-in／閉じたwriter用のみ。`--journal-foreground`も常時配線も**禁止**。完全frameを読めてもfsync済み確定の証明にはならない。

## CodexのAgent必読順

1. [Serverの夜間引継ぎ正本](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_codex_overnight.md) を全文。
2. このファイルと本repo `AGENTS.md`。
3. `docs/00_index.md`、`docs/07_history.md`、`docs/03_rrbridge_design.md`、`docs/04_romraider_internals.md`、`docs/09_open_issues.md`、`docs/10_interfaces.md`。
4. `docs/product_v1_implementation_progress.md`、`docs/08_decisions.md`（最新はD-038）、`docs/phase_2_1_d2b_2c_4b_2d_2a_live_readonly_probe.md`、[Server設計・製品v1通信IF](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md)。
5. Codexが既に着手していた場合は **`docs/handoff/codex_overnight_worklog.md` の最新項目、open PR、直近commitとCIステップ**（これが最優先の再開点）。

## 最初に進める作業

- **Phase 4b-2d-2b** はいきなり製品Workerに接続しない。Windowsで「旧writer排他とlive readerの両立」ができない事実から、**lock専用ファイルへの分離と旧writerとの共存可否を先に合成PoC**で検証する。
- 続いてwriter fsync確定を証明するdurable watermarkのversion、単調sequence、UUID/checksum、atomic更新の異常系をPoCで検証する。
- LinuxはCloudPC、WindowsはGitHub Actionsの**実ステップ**を確認。既存`scripts/test-ingress-journal-prototype.sh`、`scripts/test-ingress-sqlite-receipts.sh`、`scripts/test-product-v1-spool.sh`の回帰を守る。
- Windows旧方式で読めない状態は必ずBLOCKEDと表示。読み取りを無理に通すため旧ロックを外す、未fsync完全frameをSQLite cursor/ACKへ昇格するなどの安全ゲート緩和は禁止。
- **ユーザーは就寝中**。軽微な設計・新規PoC・試験・commit/PR/CI成功後のmergeは委任済み。ECU/X270/ROM/Caddy/MCP/SSH/本番DB/不可逆な変更、重大な互換性破壊は行わず、翌日の「要承認事項」に残す。

## 上限停止に備えるための最重要運用

**開始直後にこのrepoへ `docs/handoff/codex_overnight_worklog.md` （本引継ぎで初期作成済み）へCodexの最初の実作業ログを追記し、最初の小コミットからリモートへpushすること。**

その後もコード・試験・失敗内容・原因・変更履歴・再開コマンドを、作業途中でこまめに追記・commit・pushする。書くべき項目と様式はServer側正本§F参照。

- 実装の都度 `docs/07_history.md`、`docs/08_decisions.md`、`docs/product_v1_implementation_progress.md` を現在地に合わせて更新し、Serverの`docs/implementation_progress.md`を別docs-only PRで同期する。
- 実行していない試験にPASSを書かない。Actionsのworkflow全体がsuccessでも、private Server読取secret不足による**実Server E2EステップSKIPPED**をPASSと記載しない。
- 夜間終了時／途中停止時は `docs/handoff/codex_overnight_report.md` へ翌朝向け要約（Phase・commit・PR・CI・blocker・次コマンド）を残す。
- 最初の作業よりも「最後に確認できた状態を必ずGitで追えること」を優先する。**差分破棄、force push、原本/未ACK削除禁止**。
