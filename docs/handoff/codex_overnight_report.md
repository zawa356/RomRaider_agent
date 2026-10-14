# RomLogger 夜間開発報告 — 2026-10-14

## 結果

- **4b-2d-2b-1:** lock分離独立PoCを[Agent PR #27](https://github.com/zawa356/RomRaider_agent/pull/27)でmainへ統合。新旧writerの全別JVM起動順の排他と、Windows/Linuxの新writer保有中read-only読取を確認。純粋な別lockのみでは二重起動できる反例、POSIX別handle closeの初回FAILと修正を履歴保存。
- **4b-2d-2b-2:** [Agent PR #28](https://github.com/zawa356/RomRaider_agent/pull/28)にfsync後のversioned watermark独立PoCを保存。Linuxは確定prefix読取・改変/置換/部分更新拒否・別JVM haltをPASS。WindowsはfileKey未提供のため**watermark BLOCKED**、安全拒否/原本保全のみをPASS。
- **4b-2d-2b-3:** 両OSwatermark成立ゲート未達のため**BLOCKED/未実装**。SQLite結合/rotation自動化には進めず、原本セット・16本/8MiB・session単一source規約・代替案を[設計記録](../phase_4b_2d_2b_lock_watermark.md)へ残した。
- 既存writer/Logger/Worker/正式IF/製品DB/ACKの配線は変更していない。実車/X270/ECU・物理電源断・実ディスク満杯・今回の実PostgreSQL E2EはNOT RUN。以前のE2E成功とは区別する。

## 実際の検証

| 環境 | 実行結果 | 制限 |
| --- | --- | --- |
| WSL Linux OpenJDK17.0.20.1 --release8 | journal197 PASS、製品SQLite/receipt344 PASS（計541）、legacy test.sh44 PASS/0 FAIL | legacy jar欠落の初回compile失敗後、公式1.1.0-NOV26-2025 jarをignored libへ導入。実車試験ではない |
| VPS1 codex専用clone / OpenJDK17.0.20.1 | journal197 PASS、SQLite/receipt344は0bef713時点でPASS（以後同対象コード非変更）。最新main同期後の最終実行を下へ追記 | Git資格情報を転送せずlocal gh＋Git bundle同期 |
| Actions Linux | journal197＋SQLite/receipt344 PASS | directory force SYNCED観測は物理電源断保証ではない |
| Actions Windows Server 2025 / Temurin17 | journal143＋SQLite/receipt344 PASS | 143=旧34+41+probe23+lock39+storage/拒否6。watermark full56ケースは**NOT RUN/BLOCKED**。directory force **UNCONFIRMED** |

PR28 code85ce5e3/docs ad1a570の[journal CI38410669443](https://github.com/zawa356/RomRaider_agent/actions/runs/38410669443)と[spool CI38410669276](https://github.com/zawa356/RomRaider_agent/actions/runs/38410669276)は両OSの実ステップsuccess。最新文書追加後のPR/main CI・VPS最終結果は追記節を参照。
初回Windows FAIL: [38409471808](https://github.com/zawa356/RomRaider_agent/actions/runs/38409471808)、[38409589169](https://github.com/zawa356/RomRaider_agent/actions/runs/38409589169)。
Server正本同期: [PR #39](https://github.com/zawa356/RomRaiderServer/pull/39) merged/main50ff71c、[PR #40](https://github.com/zawa356/RomRaiderServer/pull/40)でwatermark blockerを記録。Serverコード/DB変更なし。

## 未完了・判断待ち

1. Windows原本識別: 公開fileKey()がnull。hardlink anchor/isSameFile、native file index、UUID内包原本の比較・移行検証が必要。内部JVM metadata設定で解決する推測は公式実装を確認して撤回した。
2. directory永続化・物理電源断、Zulu8 32bit、実ディスク満杯、同一JVM別handle close、network FS・inode再利用は未検証。
3. readerのprevious Snapshotはメモリだけ。永続highwater/receipt同一SQLite transaction・run/source claimとrotationの整合は未実装。
4. `--journal-foreground`、Supervisor通知/レビュー解除、容量管理、自動配備/retentionは導入しない。既存Windows writerのmandatory lockによるライブread BLOCKEDも継続。

## Gitだけで再開する手順

```bash
git fetch origin
git status --porcelain=v1 --branch
gh pr list --state open
gh run list --branch main --limit 6
cat docs/handoff/codex_overnight_worklog.md
cat docs/phase_4b_2d_2b_lock_watermark.md
```

新作業は最新mainから新しいPhase専用branchを作る。Windows識別方式の独立設計/合成PoCが次候補。既存Worker/原本/ACKへは接続しない。
VPS専用Agent clone: `/home/codex/git/romlogger-codex-4b2d2b-agent`。
VPS専用Server clone: `/home/codex/git/romlogger-codex-4b2d2b-server`（最終同期時に作成/確認）。
local `/tmp`のSSH/sync helperに依存しなくても、local認証済みghとGit bundleで再同期可能。認証情報はチャット/Git/転送bundleに保存しない。

詳細なcommit/試験/失敗/次操作は[追記worklog](codex_overnight_worklog.md)、[Agent台帳](../product_v1_implementation_progress.md)、[ADR](../08_decisions.md)、Serverのimplementation_progress.mdが正本。
