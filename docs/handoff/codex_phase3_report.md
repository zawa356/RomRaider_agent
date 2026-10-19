# Phase III 配置・切替診断・隔離バックアップ 最終報告

次回の入口は [Bootstrap](../ops/development_environment_bootstrap.md) → 本報告 → [Agent進捗](../product_v1_implementation_progress.md)。履歴は [追記worklog](codex_phase3_worklog.md)、実コマンド・ログSHA256・検証headは [証跡](phase3_validation_evidence.md)。

## 結果と検証対象

最終引継ぎ文書は [Agent PR74](https://github.com/zawa356/RomRaider_agent/pull/74)。この文書自身を取り込む最終merge SHAはPRのmerge記録と最新mainで確認する（文書内へ自己参照SHAを推測して埋めない）。

最終コード検証head `ed8efd6e6195418feffe77224ef61f2116a6db7b`。前段の独立full matrixは `2bef02e102664a44908a3662c388f1c959beda49`。開始main Agent `1dfdcd8f564e2c46cb2651ab76aa4e1705701b83` / Server `3091d6196d3ebb69c568de09b8b4941fe35cad5e`。コード編集前checkpoint `6206d5e` をpushし、指示書・GitHub最新main・PR・CI・実環境を独立確認した。開始時両repo clean/open PR0。

Agent PR [70](https://github.com/zawa356/RomRaider_agent/pull/70)、[71](https://github.com/zawa356/RomRaider_agent/pull/71)、[72](https://github.com/zawa356/RomRaider_agent/pull/72)、[73](https://github.com/zawa356/RomRaider_agent/pull/73) 取込済み。最終実装main `c206282119c424c3dd15c66b5cddfb5713d69fac`（PR73通常merge、2026-10-19T14:56:03+09:00）。PR70 merge `6e0c779501a4a892e61874b93516a48dd416b1cc`、PR72 merge `f8b428e2333e0d88ac554ace5df7599a35b965d0`。PR71はPR72がその全commitを取り込んだためGitHubがMERGEDへ更新し、独立merge commitは作成されていない。mainと検証headのsrc/scripts/.github差分0。引継ぎの最終文書PRは別のdocs-only commitとする。

| Phase | 判定 | 今回の成果 / 残る境界 |
|---|---|---|
| III-0 | 完了 | Windows開発VM、認証/最新Git/CI、pin済SSHと既存サービスを確認、先行checkpoint |
| III-1 | 固定配置候補の受入 / 製品配置は部分完了 | RLDB1 paired root/旧DB anchor、canonical path/identity/ACL/owner/alias検査、読取専用CLI、bound RLI2入口。単一logical Session/manifestの固定配置に限定。manifest置換・次Session・復元再bindingの運用権限は未成立 |
| III-2 | 診断・合成受入完了 | 既存lockだけの観測、現build classset比較、v1 inventory/ACK/cursor/receipt/latch、実子Worker保持中拒否と終了後明示再試行。未知binary/実際のprocess provenanceは外部確認が必要 |
| III-3 | 部分完了 | RLBA1役割/hash証跡・inactive copied-target検証CLI、欠落/改変/WAL/稼働lock拒否、RMI1 schema-reference漏れ修正、実copy途中/部分backup拒否とsidecar alias防止。復元権限/再binding・物理複数file永続化は未成立 |
| III-4 | 部分完了 | preflight→owned RMI1 copy→versioned inventory→read-only verification→backup rehearsal。rowid/ID/profile/ACK/receipt/claim/cursor/実retry/review状態と原本bytes保全。RMI1 copy6境界の子JVM halt52項目、DB busy/source変更、full rehearsal/backup32項目。RMI1→RLI2正式record変換・本番cutover/rollbackは未実装/無効 |
| III-5 | 実環境受入・引継ぎ完了 / CI NOT RUN | 独立固定checkout/Windows両Java/Linux/実HTTP/PG/既存回帰と引継ぎ。CI NOT RUN。物理電源断/実ENOSPC/実車はNOT RUN |

## 実装契約

- [RLDB1](../interfaces/deployment_binding_v1.md): root、正確な旧DB、既存RLI2出力、session/manifest/ledgerとdevice/vehicle/payload/manifest sessionを対応付ける。別root/DB alias、symlink/junction、未知version/owner、危険ACL/owner、入力内出力、sidecarを拒否。POSIX unix:nlink / Windows bounded read-only fsutil照会でregular fileのhardlink数1を要求。不明/失敗は拒否する。
- 改訂 `Rlc2IntegrationStore.ingestBoundDeployment` は出力open前とtransaction stage/precommitでbindingを確認し、既存legacy→lane→publication→SQLiteとsnapshot/admission/ACK拒否を保持。既存Workerの既定動作・wire v1・本番DB schemaは変更しない。
- [切替診断](../ops/cutover_readiness_v1.md): READY_FOR_SYNTHETIC_TESTは本番許可ではない。既存lock不在/未知旧binaryはREVIEW、稼働lockはBLOCKED。短い共有観測は同時起動の非blocking Workerを拒否させ得るため、停止確認後に診断する。実稼働binary/classpathの真正性は判定しない。
- [RLBA1/RMI1 rehearsal](../interfaces/isolated_backup_acceptance_v1.md): 原本を更新せず、閉じた各DB・所有metadata・manifest/ledger/originalの役割・length/SHA256を比較。RMI1は全旧表/rowid/sqlite_sequenceとinventory/guardを検査。コピーは新しい隔離directoryだけ、activation_allowed=false。失敗入力・部分copyは保持し修復しない。

## 今回見つけて修正した欠陥

1. 別rootで同一論理Sessionを同時取得できるRLLO1の限界をREDで再現。既存low-level APIは研究用のまま残し、製品候補の改訂入口へpaired bindingを追加した。
2. RMI1 schema-referenceが反復処理で10 directory残るRED。finallyで自分のfresh directoryの正確なDB/sidecarだけを片付け、GREENでは追加0、既存referencesを保持。
3. 新しい合成helperをproduct packageへ置くと旧standalone wildcard buildが失敗。prototype packageへ移して回帰を修正した。
4. hardlink別名の旧DBを別rootへ登録できるRED。さらに、RMI1のtargetが原本hardlinkでowner markerが一致すると、原本を変更した後にdigest拒否する重大なREDを**新規合成DBのみ**で確認。canonical parent/単一hardlink/同一file拒否をguard・writable open前に移し、両Windows JavaとLinuxで原本不変GREEN。失敗fixtureはignored indexとともに保持した。

5. 所有確認済み候補のWALが別DBへのhardlinkの場合、SQLite recoveryが別の合成DBを変更するRED。ownerとWAL/SHM/journalすべてのcanonical path/type/link数をwritable recovery前に検証し、原本と別DBの不変を確認した。

追加 [copy故障・状態CLI契約](../interfaces/migration_copy_fault_acceptance_v1.md) はNO_CANDIDATE、owner-only、partial、完全plain copy、journal残存、closed RMI1 verifiedを区別する。SQL COMMIT後のWAL残存を「完了」と断定しない。診断は入力/target/owner/lockを作成・復旧せず、明示quiescence宣言と既存READ lock観測を必要とする。

## 独立実行結果

| 対象 / exact head | 結果 |
|---|---|
| 独立Windows full batch / ed8efd6 | exit0。JDK17 x64と実Zulu8 x86、Java8 bytecode、既存回帰。binding18/engine6/readiness11/backup32/stages52を両JavaでPASS |
| Linux17 / ed8efd6 | exit0。product/PhaseIII/PhaseII。binding17/engine6/readiness11/backup32/stages52/migration21/CLI2・alias負試験PASS |
| bound大規模 / ed8efd6 | Windows64・実32・Linux64それぞれ10,003 records、precommit rollback/retry/replay PASS（各exit0） |
| Linux preview大規模 / ed8efd6 | 10,000 observations＋10小Session、59 checks PASS。旧low-level経路の複数Sessionでありbound Session遷移ではない |
| 実HTTP＋invocation-owned PG16 / ed8efd6 | 35 checks PASS/exit0、Agent bytes/ACK不変、所有cluster終了 |
| Server main3091d61 runtime | 専用PG migration upgrade二度/check成功、pytest57 passed/1 warning、exit0。今回Server変更は文書だけ |
| GitHub Actions / ed8efd6 | job117885507423 steps0、account payments/spending limit。CI NOT RUN |


大規模のbound entryは10,003 recordsのprecommit失敗rollback→明示retry→exact replay/ACK無効をWindows64/実32/Linux64で確認。Linuxの旧候補loadは10,000 observations＋10小Session（合計11sessions/10,100records/10,111receipts）と複数JVM競合・停止復旧を確認。単発metricsはbenchmark/保証値ではない。

実FastAPI/Uvicorn＋invocation-owned PostgreSQL16.15でHTTP35項目（応答喪失/再送/409/422/DB停止503/no ACK/再起動・保存確認）PASS。試験ACKでAgent bytes/ACKは不変。既存55432 clusterを停止していない。Server canonical main上の隔離DBでmigration upgrade二度/check成功、pytest57 passed/1 warning。Server runtime/schema/wire変更なし。

## 未成立・NOT RUN

- **BLOCKED:** 別host/複製root/同一path・identityを復元したclone/ネットワークFSを横断するglobal fencing。整合的にmetadataを作り替える管理者への認証保証。external-single-activeは運用者の宣言であり自動証明ではない。
- RLDB1 v1は単一の固定logical Session/manifestに限定する。manifest file identityの変わる正当な置換/rotationも拒否するため、継続運用の再binding/Session切替契約は未成立。既存low-level multi-session loadのPASSをbound deploymentの複数Session受入と混同しない。
- 未完了: RLDB1復元/rebind authority、実process/配布物の完全provenance、RMI1→RLI2 record/ACK対応契約、backupの物理複数file永続化/整合的悪意改変。RMI1の6境界実halt・full rehearsal割込み・実partial backupは今回追加して受入したが、それを物理電源断証明へ昇格させない。
- **Windows directory durability = UNKNOWN**。Runtime.halt/PG process crashは物理電源断ではない。実ENOSPC/実車/X270/OpenPort/ECU/常駐Logger/公開deploy/本番migration/正式ACK/自動Worker切替・停止・再起動はNOT RUN/無効。
- root test.shはlib/RomRaider.jar不在でNOT RUN。旧Windows RLC1 full56は既存gateでBLOCKED、旧symlink privilege fixturesはSKIP/NOT RUN（新junction試験のPASSで代替しない）。batchのoptional PostgreSQL preview引数は未使用だが、別の専用PG/実HTTP35試験は実行済み。
- CIはaccount payment/spending limit annotationとsteps=[]。**CI NOT RUN（runner未開始）**。課金変更・再dispatchなし。YAML parser依存はローカルになくNOT RUN、変更行は手動レビュー。PowerShell AST・diff whitespace・既知資格情報値混入・生成物tracked0を確認。

## 後片付け・再開

Server [PR60](https://github.com/zawa356/RomRaiderServer/pull/60) は2026-10-19T14:58:23+09:00通常merge済み。Server main `bb27cd10ad60cda1a7f4434c3f803df6c4db2a39`、runtime/schema/wire変更なし。2026-10-19T14:58:27+09:00、全Windows試験終了後のlocal Java残存0、既存PG55432 readyを確認した。

最終確認2026-10-19T14:54〜14:55+09:00: Docker既存14containerが同じIDで全Up、API/MCP/proxy稼働、VPS試験Java0、既存PG55432 ready。自分のowned PostgreSQLは終了。失敗fixture/ignored logs/固定checkoutを保持。他人の一時file・既存サービス/volume・lockを削除していない。最終Windows大規模試験終了後のlocal Java残存0も上記時刻に確認済み。VPS Caddy systemd unitの稼働は確認できず、Docker proxyの稼働と区別する。

```powershell
Set-Location C:\Dev\RomLogger\RomRaider_agent
git status --short
git fetch origin
gh pr list --state open
git log -5 --oneline
# cleanな場合だけ mainへ移り、origin/mainへfast-forwardする。reset/cleanは禁止。
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

Serverもstatus/fetch/main/PRを確認する。固定検証checkout `C:\Dev\RomLogger\phase3-release-ed8efd6`（前段 `phase3-release-2bef02e` も保持） はdetached headとignoredログを保全しており、既存サービスではない。再試験は同headで実施できる。VPSの隔離sourceは `/tmp/romlogger-phase3-ed8efd6`、pin済既存helperで到達し、新しい試験だけを行う。秘密値/DSNをargvや文書へ出さない。

次の最優先は、**停止・単一稼働を外部で確定した復元/rebind契約とRMI1→RLI2に必要な原本provenance/receipt対応契約**。旧v1 DBのwire JSON/claim/cursorだけでは、RLI2が要求する元RLC2 physical UUID/manifest/committed frame bytes/receiptを生成できない。捏造せず、実原本との照合契約か明示した別storage versionを設計してからrecord mapping/rollbackを実装する。本報告のPASSを本番切替承認へ流用しない。
