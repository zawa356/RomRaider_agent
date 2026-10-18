# RomLogger Codex 集中開発報告 — 2026-10-18

ユーザーの開始承認後、Phase A〜Gと追加の整合性修正Hを実装・試験・GitHubへ反映した。診断と合成データ専用の製品接続基盤は実行可能になった。新経路からの正式なServer ACK、製品Workerへの自動接続、既存v1表への移行は有効にしていない。

コードの最終基準は Agent `e1677c4e8341b1d60d61bd2be11dd57a5e459a43`（PR49）。本報告を追加するdocs PRはこのコード基準の後続であり、実装の再変更は含まない。逐次記録は [worklog](codex_acceleration_worklog.md)、Server進捗正本は [implementation_progress.md](https://github.com/zawa356/RomRaiderServer/blob/main/docs/implementation_progress.md)。利用枠の正確な消費量や連続実行時間は保証しない。昼の実装記録と夜の継続・最終確認をGitの順序と実測時刻で残している。

## 実装成果

| Phase | 実行可能な成果 | 主な判断・修正 |
|---|---|---|
| A/B | IngressDiagnostics API、固定コード、JSON/人間向けCLI | RLC2/ledger/manifestを短く独立レビュー。診断時のSQLite mode=roによるWAL/SHM生成をREDで検出し、閉じたsnapshot＋immutable read-onlyへ修正。原本・ledgerを書き換えない |
| C | Rlc2ProductAdapter、既存IncrementalTsvBridgeの純粋ProjectionSink | 既存TSV importerと完全一致するID/JSON/profile。明示opt-inとsynthetic-/TEST_制限、manifest順の変換、sourceとrunのseqを分離。デフォルト製品経路は従来sinkを使用 |
| D | Rlc2PreviewStore/CLI、隔離SQLite・PostgreSQL E2E | 専用拡張子/RLP1 application ID/6表の所有権確認。claim・source・receipt・profile・recordを同一transactionへ。CHECK ack_eligible=0、v1表なし |
| E | 別JVM停止・競合・DBエラーと再実行 | 出力DBのrollback journalを不当に拒否するREDを修正。正に識別した専用出力だけSQLite自身が回復し、その後schema確認。入力ledgerの不変条件は維持 |
| F | Windows一括試験、Windows/Linux/実x86/PG CI、依存hash固定 | JAR・CPython3.12 wheel・PG16 imageを固定。生成物追跡を拒否。Windows rename拒否時は厳密な原本保全とreader停止を検査し、進行保証を偽らないtest harnessへ |
| G | 手動next_action、RLC1/RLC2移行前検査、負荷・性能改善 | ledger遅延/未確定更新を破損と区別。混在markerを拒否。RLC1のfileKey hashをnative identityへ読み替えない。10k/11session実測、同一形式のhex変換を軽量化 |
| H | 再取り込み時の保存済みidentity集合検証 | イベントPKだけ変更すると件数チェックをすり抜けて元行を追加するREDを修正。旧source receipt範囲・連続prefix・正確なrecord ID集合を更新前に検査。欠落を自動再構築しない |

詳細: [診断](../acceleration_diagnostics.md)、[製品変換](../rlc2_product_projection.md)、[隔離E2E/復旧](../rlc2_preview_e2e.md)、[自動化](../acceleration_test_automation.md)、[移行前検査/負荷](../ingress_preflight_load.md)。ADR D-048〜D-056、interfaces C17〜C19と進捗・履歴を更新した。

## PR・main証拠

以下は通常merge。force push/history rewriteは行っていない。CI番号は同じ行のリポジトリに属する。

| Agent PR | 内容 | main merge SHA | main CI（journal / product） | Server進捗PR・main SHA・CI |
|---|---|---|---|---|
| [43](https://github.com/zawa356/RomRaider_agent/pull/43) | A/B | 77089a368d3c562bb5b5e8f01ae9e4e6ba90c606 | 39091295337 / 39091295292 | [50](https://github.com/zawa356/RomRaiderServer/pull/50) e056c0590cf61413b9e237cc7d9469a6a5578285 / 39091434142 |
| [44](https://github.com/zawa356/RomRaider_agent/pull/44) | C | b862651902ad25dc86a3233c00df77dddcaa3919 | 39091939531 / 39091939477 | [51](https://github.com/zawa356/RomRaiderServer/pull/51) 3d574d52ce716b5c2733ba3bc9f77414f1bf58bd / 39092090529 |
| [45](https://github.com/zawa356/RomRaider_agent/pull/45) | D | a4a5a0ba06d8cd908d7908cd5e08f5f40891518a | 39092940711 / 39092940692 | [52](https://github.com/zawa356/RomRaiderServer/pull/52) 94b05a67ca5154916e7f9d9eddeb3f607eaadbda / 39093054991 |
| [46](https://github.com/zawa356/RomRaider_agent/pull/46) | E | 9caa2486d7b4a9e256b68eb02835ed90cf09e0f7 | 39093698538 / 39093698530 | [53](https://github.com/zawa356/RomRaiderServer/pull/53) 2aaaeda93f39260c97534f52d463ace663b209fd / 39093791658 |
| [47](https://github.com/zawa356/RomRaider_agent/pull/47) | F | 202b9c95a77d7f07d28a6cb1d2a2c03927b13d98 | 39095250378 / 39095250377 | [54](https://github.com/zawa356/RomRaiderServer/pull/54) 60d33653da26ec5c796aae0fe44cf4094e9b9807 / 39095397003 |
| [48](https://github.com/zawa356/RomRaider_agent/pull/48) | G | 33875a2e86dabb91f8b3a93441192595a2a7d08f | 39097392309 / 39097392286 | [55](https://github.com/zawa356/RomRaiderServer/pull/55) af30941a4f0128a448389e3dd48d5003191bd2cd / 39097403278 |
| [49](https://github.com/zawa356/RomRaider_agent/pull/49) | H | e1677c4e8341b1d60d61bd2be11dd57a5e459a43 | 39124576935 / 39124576968 | [56](https://github.com/zawa356/RomRaiderServer/pull/56) a1bc6210159f6ee736f084b6f81d3da0d5f9f500 / 39124675916 |

上表のmain CIは全てsuccess。HのPR CIは [journal全4job](https://github.com/zawa356/RomRaider_agent/actions/runs/39097642181) / [product](https://github.com/zawa356/RomRaider_agent/actions/runs/39097642133) success、最終code mainも [journal全4job](https://github.com/zawa356/RomRaider_agent/actions/runs/39124576935) / [product](https://github.com/zawa356/RomRaider_agent/actions/runs/39124576968) success。job名だけでなく、実行stepとraw logのpreview42/43、fault36/37、実際の32-bit JVM、PG検証を確認した。LinuxでWindows専用stepがskipされるのは対象OSの違いである。

## 検証結果と限界

| 検証 | 結果 |
|---|---|
| 最終Hローカルpreview/fault/default load | Windows17 x64 42/36/23、実Zulu8 x86 43/37/24、VPS Linux17 x64 42/36/23 PASS。x86の+1はactual architecture検査 |
| 診断・migration preflight | diagnostics34/35/34、最終preflight18/19/18（x64/x86/Linux）PASS。共通reject helperの追加assertは各クラス固有counterと別 |
| 共通v1変換 | projection20/21/20 PASS。独立に呼んだ従来TSV importerと全ID/JSON/profile比較。Windows/Linuxの既存product suite PASS |
| 大きい負荷 | 10000主観測＋10小セッション＝11session、10100 records/10111 receipts/22source cursors/11claims。GでLinux44/x8645、HでLinux44 PASS。全highwater/重複防止/原本bytes検査 |
| 後方互換 | 旧Formatter実装が実x86で作った10001 receipt ledgerを新x64 inspectorで検証、原本全bytes不変 |
| 完全Windows一括 | build/acceleration-20261018-124526.log exit0。native102、旧回帰、実64/32、PG destination guard3、実PG/Server modelを含む。HのCIでも更新コードの完全batch実行 |
| PostgreSQL | ローカル16.15とCI隔離PG16。2回commit/reconnect、exact receipts/JSON、rollback、ACK制約。H後もローカル2回PASS |
| 実Server model | 実checkoutのProfileRevision/Batch検証PASS。HTTP endpoint/Server ACKは呼ばない |
| Server既存回帰 | ローカル専用DBでmigration upgrade二度/check＋57 passed |
| 依存固定 | clean venvのhash lock install成功、故意の不正hash拒否、valid wrong-JAR cache置換確認 |
| 実Server HTTP E2E | private Server read secret不足で既存gated step SKIPPED。workflow successをHTTP PASSと表現しない |
| ローカルsymlink | Windows権限不足で一部NOT RUN。CIは権限を用意し対象試験を必須化 |
| 旧RLC1 Windows 56 scenarios | 既存platform durability gateによりNOT RUN/BLOCKED。新RLC2の成功へ混ぜない |
| 物理電源断/実ENOSPC/実車 | NOT RUN。Runtime.halt、DB ABORT、quotaは代替の限定試験 |

性能は同一workloadの単発cold process観測であり、正規化benchmarkではない。Gの全工程表は専用文書に保存。hex軽量化前→後の再projection＋store replayはVPS 2086→939ms、実x86 11433→5010ms。commit等には逆方向のばらつきもある。H検証追加後のVPS同workload replayは1205ms。速度保証には使わない。

失敗履歴も削除していない。SQLite read-only副作用、Linux launcher未compile、専用出力rollback拒否、YAML colon構文、Windows一時rename拒否、event key置換をそれぞれRED/原因/修正/再実行としてworklogへ記録。WIP commitの生成pycは通常の後続commitで除去し追跡guardを追加、履歴は保持した。secretは含まない。

## 環境・原本保全

VPSは再インストール後の指定IPを確認し、ed25519 host fingerprintを記録、旧pinをbackupしてVPS分だけ更新した。鍵検証は有効。Ubuntu24.04.5の専用/tmp領域でJava17/SQLite/Python検証のみ実施。本番deployは行っていない。Windows JDK17 x64、実Zulu8 1.8.0_504 x86、Git Bash、Python3.12と専用loopback PGを使用した。

Dockerは既存imageでnetwork none/read-only/resource制限付きの専用--rm Java容器のみ利用。最終確認で既存14サービスは全てUp、専用容器の残存なし。VPS専用作業領域に実行中テストprocessなし。専用loopback PGに残存するrlc2_preview_試験schemaは0。合成fixture・失敗証拠は意図して保管し、原本やtmp markerを自動削除していない。認証情報はdotenvとして読み、ログ・Git・PRへ値を出していない。

## 次の製品統合と着手条件

1. **複数物理sourceの製品契約を先に確定**する。現行v1_source_claimsはpayload sessionごとに単一sourceであり、rotationの複数UUIDを無理に詰め込まない。論理manifest/session claimと物理source cursor/receiptを分けるversioned migration、旧TSV laneとの排他、rollback・再開条件を独立ADR/Server–Agent契約へ書く。専用preview DBはその仕様検討に使えるが、そのまま製品DBへrenameしない。
2. **合成専用integration段階**では既存ProjectionSinkとcheckpoint検証を再利用し、default offのproduct wiringを独立branchに実装できる。transaction境界はclaim・全source highwater・原文receipt・recordを一体にし、既知record IDとraw contentをimmutable比較。旧lane/複数worker/rotation途中/未知sessionは停止。Server送信はまだ無効で受入試験する。
3. **Writer/Worker統合の前**に、同時読み取り中に変わるsnapshotの扱い、lock順序、capacity、sealed境界、producer停止・手動再開の責務を確定。診断は外部quiescenceを要するimmutable snapshot方式であり、稼働中ledgerを安全なsnapshotだと見なさない。Supervisorへ固定code/next_actionを表示し自動repairしない。
4. **正式ACKの前**にWindows directory durability、対象FS/Java8の実保証、実電源断からの復旧、Server永続性と再送契約を別途満たす。現在UNKNOWNのままなのでenableしない。新経路を使った未ACK原本の自動削除も許可しない。
5. **実Server HTTP試験**はprivate checkout認証を安全に用意して別途実行し、stepの実行と実際の受信DB確認を証拠化する。既存本番DB・Docker serviceを流用しない。

Hの検証は保存データ間の整合性確認であって、全DB/checkpointを一貫して書き換えられる管理者への認証・改竄防止ではない。

## 再開コマンド

PowerShell 7、Windows開発VM。最初にclean/main/open PRを確認し、ユーザーの別作業があれば保護してから進める。

```powershell
Set-Location C:\Dev\RomLogger\RomRaider_agent
git status --short
git switch main
git pull --ff-only
gh pr list --state open
git log -4 --oneline
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

PostgreSQL/実Server modelも必要なら、専用loopback test DSNを保護されたprocess環境へ設定して同scriptの -Postgres -ServerPath C:\Dev\RomLogger\RomRaiderServer を使用する。DSNをコマンド履歴に直書きしない。手順は自動化文書を参照。

```powershell
Set-Location C:\Dev\RomLogger\RomRaiderServer
git status --short
git switch main
git pull --ff-only
gh pr list --state open
```

診断やCLIの実引数は各専用文書を参照し、原本・ledgerが静止したsynthetic fixtureのみ使用。次branchでworklogに基準SHAと目的をcheckpointしてから実装する。

### 旧自己試験の追加制約

リポジトリ直下の test.sh は必須入力 lib/RomRaider.jar がこの開発checkoutに存在しないため NOT RUN。今回実行した製品v1 spool・ingress・native・ledger・diagnostics・preview各suiteとは別であり、30項目の旧SelfTestを実行済みとは扱わない。RomRaider連携部分は実機未検証のまま。最終報告PR50後のこの注記は文書のみで、e1677c4の検証済みコードを変更しない。
