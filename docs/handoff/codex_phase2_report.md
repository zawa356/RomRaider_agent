# Phase II 共通Lane所有権・協調排他 最終報告

次回新チャットが最初に読むURL3件:

1. [環境Bootstrap](https://github.com/zawa356/RomRaider_agent/blob/main/docs/ops/development_environment_bootstrap.md)
2. [このPhase II最終報告](https://github.com/zawa356/RomRaider_agent/blob/main/docs/handoff/codex_phase2_report.md)
3. [最新Agent製品進捗](https://github.com/zawa356/RomRaider_agent/blob/main/docs/product_v1_implementation_progress.md)

開始: 2026-10-19 09:33 JST (+09:00)。終了・merge SHAは末尾に追記。開始mainはAgent `2a741efd24825fdb453a33934b0f0169ae125337`、Server `9274394ec5b093ecd71f44c7eaec146130bb1d32`。両方clean、open PR0から開始し、コード変更前checkpoint `4eafb4a` をpushした。

共通Lane保持、旧Worker互換ロック、製品取込へのopt-in配線、合成Writer/Reader公開境界、旧v1 DBの隔離コピー移行候補を実装した。既存Worker自動置換・常駐化、実車取込、本番DB移行、自動送信・新経路正式ACKは有効化していない。[worklog](codex_phase2_worklog.md)と[Lane契約v1](../interfaces/lane_ownership_v1.md)が逐次証拠・規約。

## 実装とPR

| Phase | PR | 実装 |
|---|---|---|
| II-0 | [60](https://github.com/zawa356/RomRaider_agent/pull/60) | 固定Bootstrap、AGENTS/前報告からの入口、開始checkpoint |
| II-1/2 | [61](https://github.com/zawa356/RomRaider_agent/pull/61) | RLLO1契約・ADR、旧Worker競合RED、保持型OSロック・owner UUID/generation、既存.worker.lockゲート |
| II-3 | [62](https://github.com/zawa356/RomRaider_agent/pull/62) | DurableSpool/TSV/Journal/Scannerのscope、Worker明示設定、RLC2共通Lane API、書込/commit直前検証 |
| II-4 | [63](https://github.com/zawa356/RomRaider_agent/pull/63) | Manifest単位の公開境界、合成Writer公開/seal/rotation/LedgerとReader snapshot→commitの協調 |
| II-5 | [64](https://github.com/zawa356/RomRaider_agent/pull/64) | strict v1 inventory/dry-run、原本不変のコピー移行、ACK/rowid/cursor/profile保全、未知schema/claim矛盾拒否 |
| II-6 | [65](https://github.com/zawa356/RomRaider_agent/pull/65) | 共通Lane/公開境界での実子JVM halt、隔離migration halt・所有確認済み出力だけのSQLite復旧、改変拒否 |
| II-7 | [66](https://github.com/zawa356/RomRaider_agent/pull/66) | 新協調経路の負荷、Windows実x86/Linux入口、CI filters、v1 writable open前のforeign DB拒否 |
| II-8 | [67](https://github.com/zawa356/RomRaider_agent/pull/67) | 読取専用状態CLI、固定JSON/exit、手動復旧/backup制約runbook |
| 補強 | [68](https://github.com/zawa356/RomRaider_agent/pull/68) | 実Worker subprocessと別JVM/別旧DB/別出力DBの新Engine競合、最終owner確認順序 |

## 所有権と保証範囲

- 論理(device, vehicle, payload session)に1つの協調Lane。複数物理Sourceは同じLaneを共有し、既存の明示承認・record/run IDを保持する。
- 全参加者が同じ既存private rootを使い、exclusive OSロックを取得してcommit完了まで保持する。世代/ownerを検証するが、分散DB fencingや期限付きleaseではない。時間経過・PID再利用では交代せず、異常終了後も実際のkernel lock取得を必要とする。
- Windows JavaのfileKeyがnullのため、OSロック・handle上の完全metadata・観測creation timeを検査。POSIXはfileKeyも検査する。管理者による整合的なpath/DB改変への認証保証ではない。directory durabilityはUNKNOWN。
- 旧Worker互換ゲートは指定した正確な旧DBの.worker.lockをsnapshot前からcommit後まで保持し、同じ旧DBの別Sessionも直列化する。別旧DB/別Sessionの共通Lane自体は独立。未知の旧importer・他DB・別root・非協調Writer全体との安全な共存は主張しない。ロックファイルは削除しない。
- 順序は旧Worker→取込Lane→公開境界→SQLite。Writerは公開境界の内側でsource/marker/seal/rotation/manifest/Ledgerまで整合させ、取込Lane/出力DBは取得しない。別DBを単一原子transactionと扱わない。実Loggerへの配線はない。
- `ROMLOGGER_SYNTHETIC_LANE_ROOT` / opt-in constructor/APIだけで候補を有効化。未設定の既存動作を自動移行しない。古いbinaryが新環境変数を理解すると仮定しない。
- 移行候補はRMI1 `.lane-migration.db`へのinventory追加・保全検証。RLI2への本番record変換やin-place更新ではない。v1全表/sqlite_sequenceのrow/rowid/内容/ACKと元ファイルhashを保全する。未知/不一致/不完全copyを自動修復しない。自動downgradeはなく、原本を手動rollback元として保持する。
- SQLite hot recoveryを許すのは、RMI1 source digest sidecarと既知headerで所有確認した隔離出力だけ。sidecarとDBは別durability event。途中copy/marker破損は手動調査し、原本WAL/SHM/journalは消さず拒否する。

## RED/GREENとレビュー

`d32c346` の競合試験を修正前Storeで実行し、旧Workerロック保持中にRLC2がcommitするAssertionErrorを再現した。最初はfocused classpathに反射用Windows fixtureがなく、そのClassNotFoundはREDと数えない。修正前classを別buildへ再compileした `build/phase2-conflict-red.log` が実RED証拠。修正後は拒否/出力0件、解放後の正常3件を確認。

実x86でprocess終了直後にもkernel lockがBUSYの例を確認。試験だけ実取得まで有界に再試行し、製品へ期限takeoverは追加していない。migrationで通常read-only SQLite openがWAL/SHMを作ることを検出し、停止確認済み入力のimmutable URIへ修正。元sidecarを削除してPASSさせていない。

全差分をレビューし、owner検証をsnapshot再hashの後、commit直前へ移した。コピー移行再実行はmetadataだけでなく全schema/保全trigger/Source inventoryも比較。生成物tracked0、接続情報実値のdiff一致0、PowerShell AST/YAML parse PASS。force push・履歴改変・秘密表示/commitなし。

## 検証

Windows11 Pro x64の実在開発repo、JDK17.0.20.1 x64、実Zulu8 1.8.0_504 x86、VPS Linux JDK17.0.20.1、固定hash SQLite JDBC3.46.1.3を使用。VM管理URL/VM ID/RDP先は未確認。X270は別端末でアクセスしていない。

| focused suite | Windows x64 | 実Zulu8 x86 | VPS Linux17 |
|---|---:|---:|---:|
| 所有権/診断/実CLI | 26 PASS | 26 PASS | 26 PASS |
| 製品cross-DB/実Worker起動 | 7項目＋subprocess PASS | 同 PASS | PASS |
| 隔離migration/実halt | 19 PASS | 19 PASS | PASS |
| 旧Worker/RLC2・製品/RLC2競合 | 拒否/正常retry PASS | 同 PASS | PASS |
| 公開境界/3Source extension | 6 PASS | 6 PASS | PASS |
| 共通Lane子JVM halt/retry | 21 PASS | 21 PASS | PASS |
| 新Engine別JVM・別DB競合 | 7 PASS | 7 PASS | PASS |
| 10000＋10小Session | large別実行NOT RUN | 60 PASS | 59 PASS |

負荷は10100records/10111receipts/22admissions/11bindings、再実行・原本bytes・ID・highwater・ACK無効を検査。協調candidate snapshot/project/commit単発観測は実x86 5364ms、Linux別走行1929ms。benchmark/電源断/性能保証ではない。

実FastAPI/Uvicorn＋専用PostgreSQL16.15 clusterでHTTP35 PASS。3Source同一run、409/422、commit済み応答喪失と重複再送、DB transaction失敗no ACK、所有PG processだけのimmediate stop→503/no ACK→restart→保存4件/参照整合を確認。試験ACKでAgent bytes/ACKを変えていない。既存PGサービスは停止していない。Serverは専用DBでmigration upgrade二度/check、pytest **57 passed, 1 warning**。

主なコマンドとignored証拠:

- `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1` — 初回 `build/acceleration-20261019-094037.log` exit0、最終 `build/acceleration-20261019-095142.log` もexit0。
- `bash scripts/test-phase2-lanes.sh` / 実x86 `--run-only` — `build/phase2-release-x64.log`, `build/phase2-release-x86.log`。
- `Rlc2PreviewLoadTest`, `ROMLOGGER_LOAD_OBSERVATIONS=10000`, `ROMLOGGER_LOAD_SESSIONS=10`, architecture32 — `build/phase2-large-x86.log`。
- `scripts/test-rlc2-owned-postgres-http.py` with explicit owned-cluster enable/bin/ServerPath — `build/phase2-owned-http.log`。DSN/credentialをargv/logへ書かない。
- `C:\Dev\tools\run-server-tests.ps1` — `C:\Dev\RomLogger\logs\server-pytest-20261019-094046.log`。
- Linux `/tmp/romlogger-phase2-20261019-final` / `...-release`。committed Git archiveを使用。初回Windows text転送はCRLFでshell失敗しPASSに数えない。

## CI・未実施・残課題

確認Actions39182473844は全job steps=[]/failure。annotationはrecent account payments/spending limitでjob未開始と明示。**CI NOT RUN（runner未開始）**。再dispatch/billing変更なし。指示書で許可されたローカル/VPS検証とレビューをmerge根拠とし、補助workflowを維持。

- X270/ECU/OpenPort、物理電源断、実ENOSPC、実Logger live、本番migration、新経路正式ACKはNOT RUN/無効。Windows directory durability UNKNOWN。
- `test.sh`はRomRaider.jar不足でNOT RUN。旧Windows RLC1 full56は既存gateでBLOCKED、ローカル権限必須symlink fixtureはNOT RUN。新試験PASSで置き換えない。
- 別rootの復元コピー/別ホスト/ネットワークFSのglobal fencing、非協調Writer/旧importer、旧DB→RLI2正式record migrationは未成立。次工程はversioned deployment/root binding、旧worker停止手順、正式変換/rollback受入と別承認。
- snapshot/隔離migration候補の成功でlive/ACKを解禁しない。Source自動採用・未ACK削除・自動復旧なし。

## 次回の最小再開

```powershell
Set-Location C:\Dev\RomLogger\RomRaider_agent
git status --short
git fetch origin
gh pr list --state open
git log -5 --oneline
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

Serverもstatus/fetch/main/PRを照合し、Bootstrap・本報告・worklog・最新進捗から再開。手動復旧は[runbook](../ops/lane_manual_recovery_v1.md)。独立した未成立ゲートを保持する。

## 最終ローカル検証確定

最終source head `82e1d54da767a545027ecbf82f6fd361d29a2a77`。Windows full batch `build/acceleration-20261019-095142.log` exit0。同headのfocused x64/x86/Linuxで所有権26・migration19・公開6・子JVM21・別Engine競合7と製品/実Worker拒否/正常retryがPASS。Linux final large59 PASS、同走行candidate1511msは単発観測。既存schema13/engine28/snapshot15/strict35/JVM20(Linux)/21(Windows)もPASS。全体/個別の重複合算はしない。

[検証結果抜粋とログSHA256](phase2_validation_evidence.md)をGitにも保存した。既存Docker14全てUp、VPS試験JVM残存なし、既存PG55432ready、所有PGのPID file0。原本/ACK/既存サービスを保全した。

## 通常merge証拠

| PR | merge (JST +09:00) | merge SHA |
|---|---|---|
| 60 | 2026-10-19T09:34:50+09:00 | `aeb20c29427a9f5575547f90a67493642fbe6842` |
| 61 | 2026-10-19T10:02:21+09:00 | `6f7a1702791c24d69056e9d0087063feed2cfe24` |
| 62 | 2026-10-19T10:02:31+09:00 | `f0f5adf41cef5bfd92a0eae7bb4dcea5e7e39578` |
| 63 | 2026-10-19T10:02:44+09:00 | `2bd3db2d5bc9ffc886fbbb5608e79df1cfa7e323` |
| 64 | 2026-10-19T10:02:57+09:00 | `73903ea29ba8307e9c33fa26ee90eb615e8e40b5` |
| 65 | 2026-10-19T10:03:06+09:00 | `fc04fbbd620573b3b9a5b284934b064932fd57f2` |
| 66 | 2026-10-19T10:03:18+09:00 | `0c69ebbd2f763b077c2b9cb090d89c157a9b67e2` |
| 67 | 2026-10-19T10:03:32+09:00 | `eb1b51c6c217e8082070cc640ddd52ec30f0d7a5` |
| 68 | 2026-10-19T10:03:46+09:00 | `ca9cfe06d774261be05dd5253b8c90f6cf994f3d` |

最終実装main `ca9cfe06d774261be05dd5253b8c90f6cf994f3d` と検証head82e1d54のsrc/scripts/.github差分0をfetch後に確認。本文書の後続publicationは文書のみ。

## 変更ファイル

```text
.github/workflows/ingress-journal-prototype-ci.yml
AGENTS.md
docs/00_index.md
docs/07_history.md
docs/08_decisions.md
docs/10_interfaces.md
docs/acceleration_test_automation.md
docs/handoff/codex_phase2_report.md
docs/handoff/codex_phase2_worklog.md
docs/handoff/codex_product_integration_report.md
docs/handoff/phase2_validation_evidence.md
docs/interfaces/lane_ownership_v1.md
docs/ops/development_environment_bootstrap.md
docs/ops/lane_manual_recovery_v1.md
docs/product_v1_implementation_progress.md
scripts/run-acceleration-tests.ps1
scripts/test-phase2-lanes.sh
src/main/java/net/neristal/rrbridge/product/v1/DurableSpool.java
src/main/java/net/neristal/rrbridge/product/v1/IncrementalTsvBridge.java
src/main/java/net/neristal/rrbridge/product/v1/LaneOwnership.java
src/main/java/net/neristal/rrbridge/product/v1/LaneOwnershipStatus.java
src/main/java/net/neristal/rrbridge/product/v1/LegacyWorkerGuard.java
src/main/java/net/neristal/rrbridge/product/v1/ProductBridgeWorker.java
src/main/java/net/neristal/rrbridge/product/v1/V1LaneMigration.java
src/main/java/net/neristal/rrbridge/prototype/ingress/Rlc2IntegrationStore.java
src/main/java/net/neristal/rrbridge/prototype/ingress/Rlc2PublicationBoundary.java
src/test/java/net/neristal/rrbridge/product/v1/LaneOwnershipTest.java
src/test/java/net/neristal/rrbridge/product/v1/ProductLaneTest.java
src/test/java/net/neristal/rrbridge/product/v1/V1LaneMigrationTest.java
src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2LaneCompetitionTest.java
src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2LaneConflictTest.java
src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2LaneFaultTest.java
src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2PreviewLoadTest.java
src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2PublicationTest.java
```

## 終了checkpoint

実装・検証とServer同期の終了: 2026-10-19T10:06:26+09:00。Agent最終実装main `ca9cfe06d774261be05dd5253b8c90f6cf994f3d`、Server終了main `3091d6196d3ebb69c568de09b8b4941fe35cad5e`（[Server PR59](https://github.com/zawa356/RomRaiderServer/pull/59)、2026-10-19 10:05:39 JSTに通常merge、文書のみ）。両main fetch済み、実装PR残存0。本文書のpublicationは後続の文書PRであり、上記は実装・検証対象のSHA。実行時間枠の残量は推測していない。
