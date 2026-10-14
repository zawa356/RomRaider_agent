# Codex 2026-10-14 日中自律開発 — Agent入口

**正式な全体指示書：** [Server側全文 `2026-10-14_codex_day_autonomy.md`](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-14_codex_day_autonomy.md)。**このファイルだけで開始せず、Server全文を読むこと。**

ユーザーは仕事中で、作業中に質問・承認・遠隔操作をできません。**安全・承認済み範囲の設計・実装・合成試験・修正・PR/CI/merge・次小Phaseへの移行はCodex自身で判断し、確認待ちで停止しない。** ただし禁止された実車・ECU・X270・Caddy/MCP/SSH/既存DB/本番・原本破棄・互換性破壊は行わず、要承認事項として保留。

## 開始時点の基準（古くなるため要再取得）

- Agent main：`0177587dfc4b7bff8852231ca19bbcd8283eeb0a`、Server main：`a2eb1c22c6f3a39c731abd30a725c69b10c2d3f6`。指示書のPR merge後、必ずSHAを更新。
- 両repo open PR：開始前0件。[前回の最終報告](codex_overnight_report.md)と[前回worklog](codex_overnight_worklog.md)を確認。
- 2b-1 [PR #27](https://github.com/zawa356/RomRaider_agent/pull/27)／2b-2 [PR #28](https://github.com/zawa356/RomRaider_agent/pull/28) はmerged。Linux 197 journal＋344 SQLite＝541合成PASS。Windowsは143 journal＋344 SQLite＝487 PASSだが、**RLC1 watermark本体56件はWindowsで未実行/BLOCKED**。
- 2b-3 製品SQLite/writer/reader/rotationの結合は未着手。Windows `fileKey()` がnullであるため、原本同一性が成立していない。writerと別handleを同JVMで閉じるとPOSIXロックが消える反例あり。これらを無視して本番配線しない。

## 必読

1. [Server Codex日中指示書全文](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-14_codex_day_autonomy.md)。
2. `AGENTS.md`、`docs/00_index.md`、`docs/product_v1_implementation_progress.md`、`docs/08_decisions.md`、`docs/07_history.md`、`docs/09_open_issues.md`。
3. `docs/phase_4b_2d_2b_lock_watermark.md`、`docs/handoff/codex_overnight_report.md` と `docs/handoff/codex_overnight_worklog.md`。
4. 今回の[追記式日中worklog](codex_daytime_worklog.md)、[日中報告](codex_daytime_report.md)と、両repoの最新open PR・CI。

## 最初の作業

**最初のコード編集より先に** `docs/handoff/codex_daytime_worklog.md`に実作業開始エントリを追加し、専用branchからcommit/pushする。

- 第一候補：**Phase 4b-2d-2b-3a Windows原本同一性の比較PoC**（hardlink anchor+isSameFile、Windows volume serial/file index、header内UUID）。拒否条件・コピー/rename/元原本置換・writer競合・旧互換・Windows実runnerの試験を揃える。
- 有効な方式を実証できたときのみ 3b watermark→3c SQLite原子的highwater→3d rotation へ小Phaseを分けて前進。**一度に本番Writer/Logger/Serverへ配線しない**。
- 技術的にBLOCKEDなら、反例と手順をGitへ記録してから、同じ製品目標に資する別の安全なテスト・非本番実装へ切り替える。**AI判断で積極的に進める**。
- 試験は`scripts/test-ingress-journal-prototype.sh`、`scripts/test-ingress-sqlite-receipts.sh`、`scripts/test-product-v1-spool.sh`、可能なら`test.sh`。Windows GitHub Actionsの各**実ステップ**を確認する。Java8互換コンパイル≠Zulu8 32bit実行試験。
- Agentのhistory/ADR/progressとServerの`docs/implementation_progress.md`を随時更新。**何度も小commit/push**し、WIP・FAIL・BLOCKEDも記録。PRはCI成功後に通常mergeしてmain CIを確認。最後に日中reportへ成果・未解決・次コマンドをpushする。
