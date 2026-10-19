# Phase VI 実装報告・再開点

入口は [Bootstrap](../ops/development_environment_bootstrap.md) → 本報告 → [検証証跡](phase6_overnight_evidence.md) → [独立レビュー](phase6_overnight_review.md)。時系列・失敗・再開点は[追記worklog](phase6_overnight_worklog.md)。本報告のブランチは未マージ。mainのPhase IV報告をPhase VI成果と取り違えないこと。

## 開始点・コード固定・PR

- Agent開始main `4f80c5e9831ec39368f4e771d829337598eeaa76`、開始#80 `7da38372044792cde5271932a6134b7c6f156ccb`。Server開始main `d8c7a2f3b96ba278d269074dc40cca7741b4155b`、開始#62 `056958dd1973106173e0e9b4c8664324c001ad8a`。既存mainとPhase V worktreeは保護した。
- **最終runtime/test固定head `53f18a27663c0f7f9c8f2398fd3390190f5f5749`**。追加レビューでtransition入口のdrive proof順序を補強した。`../phase6-entry-acceptance`でこの最終差分を重点検証する。**広域基準は別head `d3941031af4d22c7317055c6d9439cf10e75e364`**、`../phase6-final-acceptance`でWindows全体batch／実x86を実行し、Linux全体・負荷は完了した。広域基準と最終入口差分の結果を読み替えず、完了結果は証跡末尾へ追記する。53f18a2以後は文書だけ。
- Server runtime/schema/wire/migrationは変更していない。Server文書同期は#62の上にstackする。

| 順序 | PR / branch | base / code head | 状態 |
|---|---|---|---|
| 基準 | [#80](https://github.com/zawa356/RomRaider_agent/pull/80) | main /7da3837 | OPEN・未マージ |
| VI-1 | [#81](https://github.com/zawa356/RomRaider_agent/pull/81) `phase6/writer-quiescence` | phase5/provenance-hardening /c52d7ba | draft・未マージ |
| VI-2 | [#82](https://github.com/zawa356/RomRaider_agent/pull/82) `phase6/local-fixed-drive` | phase6/writer-quiescence /105f66d | draft・未マージ |
| VI-3/5 | [#83](https://github.com/zawa356/RomRaider_agent/pull/83) `phase6/offline-preflight` | phase6/local-fixed-drive /556f4ff | draft・未マージ |
| VI-4 | [#84](https://github.com/zawa356/RomRaider_agent/pull/84) `phase6/transition-abandon-locks` | phase6/offline-preflight /97e28b0 | draft・未マージ |
| VI-1追加 | [#85](https://github.com/zawa356/RomRaider_agent/pull/85) `phase6/staging-parent-identity` | phase6/transition-abandon-locks /d394103 | draft・未マージ |
| VI-2入口追加 | [#86](https://github.com/zawa356/RomRaider_agent/pull/86) `phase6/transition-drive-preflight` | phase6/staging-parent-identity /53f18a2 | draft・未マージ |

## 実装と判定

| Phase | 判定と具体的成果 |
|---|---|
| VI-0 | **レビュー完了**。対象クラスを直接読み、後発journal見落とし・abandon中のLane排他欠落・出力親差替えで競合DBへ書く問題を合成REDで再現。外部の別reviewer agentを使ったとはしていない。 |
| VI-1 | **主要な実Writer RED/GREEN完了、最終広域検証中**。旧DBを原本候補数と独立にsnapshot化。別JVMのBEGIN IMMEDIATE/EXCLUSIVE（sidecarなし）・journal/WAL・commit/rollback/halt、分類後journal作成、出力親差替え、実Windows junctionを検証。親/workのnative物理IDをSQLite書込み・公開・marker作成前に再確認。途中成果と競合bytesを保全。非協調Writer不在は証明できず、専用read-only gateは常にWRITER_UNPROVEN/BLOCKED。 |
| VI-2 | **合成ポリシーと実local drive実装完了、最終回帰中**。固定PowerShellからGetDriveTypeW＋QueryDosDeviceWを読み取り専用照会し、各配置driveをFSアクセス前にFixed/localへ限定。Remote/Unknown/subst/不整合/timeout/不明出力は拒否。rootはstdinのdata、出力と時間は制限。実SMB・subst/volume mount操作はNOT RUN。 |
| VI-3 | **Partial**。default-off `OfflineCandidatePreflight`が既知のoffline entry、期待するJAR順序/hash・Java/bitness・deployment世代・manifest bytes・Source admission・既存lockの保持観測を照合。実子JVMのWorker/Lane/Publication競合は拒否。Writer/process proofが未成立のため、正常launcherや旧binaryの普遍的排除を実装済みとはしない。`launch_authorized=false`固定。 |
| VI-4 | **追加候補を実装、最終検証中**。prepareに新旧Lane/Publication lock名を記録し、abandonもそれらをread-onlyで保持。4競合・4halt境界・同一Sessionで別runの物理Source UUID再利用・retryで次世代だけを認める試験を追加。古い未完了prepareでlock集合が不明ならABANDON_LOCK_SET_UNKNOWN、手動レビュー。全ての世代交差・clone/別hostの並行シナリオを証明したとはしない。 |
| VI-5 | **Partial**。read-only preflightにproduct_mode、Worker/Lane、世代/manifest、Source/artifact/process、cursor/Vault/RLI2、writer/durability/formal_ackを独立フィールド化。未検査cursor/Vault/RLI2はUNKNOWNのまま。完成した全製品readiness判定ではない。 |

## 再現した欠陥と安全上の限界

1. **vault-onlyの後発journal**：原本候補0件では旧DBの継続snapshotがなく、未commit journal生成後も完了markerを作成できた。旧DBを常に独立監視して停止させる。
2. **abandonのlock不足**：子JVMが旧Laneを保持していてもabandon markerを作れた。新旧Lane/Publicationを保持観測し、競合中はmarkerを作らない。REDが二重有効化そのものを示したとはしていない。
3. **出力親の差替え**：同名の別private parent/workへ差し替えると、最終エラーの前に競合側の空RLI2を初期化・公開した。native親/work identityを保持して、その書込み前に拒否する。
4. **transitionのdrive判定順序**：planはchainを先に読み、commitはlockファイルを先に作っていた。合成Remote＋欠落bindingのfixtureで順序漏れとlock作成をRED再現し、drive proofをI/O前へ移した。実SMBを再現／アクセスしたとはしていない。

sidecarなしのSQLite lockとimmutable読取りの組合せは、内容一致とWriter不在が別であることを実測した限界。外部quiescence宣言で解除しない。Windows process provenance／directory durabilityはUNKNOWN。物理電源断・real ENOSPC・global fencing・悪意ある管理者のcheck-to-use raceは未証明。RLLV1はRecord証跡であり、全旧v1表の完全バックアップではない。

## 保全と再開

新しい試験はすべてinvocation-ownedの合成v1/RLC2/ledger/RLI2/lock/子JVM。既存の実旧DB・原本・ACK・Worker・実車/X270/OpenPort/ECUは操作していない。ProductBridgeWorker/Once、DurableSpool、ProductSenderのソース差分は空。新preflightはread-onlyで、合成fixture全treeの前後hash不変を検証する。Server57件は指定された隔離test DBだけで実行し、既存serviceを再起動していない。Linuxは新規private test directoryと自分の子processだけ、Docker/service操作なし。

```powershell
Set-Location C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-4\RomRaider_agent
git status --short --branch
git fetch origin
gh pr view 86 --json state,baseRefName,headRefOid
# 最初に本報告と証跡末尾の完了/失敗を読む。不要な既済試験の再実行はしない。
# 新しい実装はphase6/transition-drive-preflightを基準に別worktreeへ。mainから切り直さない。
```

次は未成立のWriter/process proofの協調契約・運用上の旧binary排除を設計し、明示offのoffline launcherへ接続する。Vault/cursor/RLI2のreadiness集約、さらに細かいwriter進行時点・同時abandon/retry・別root/hostの実競合は未完了として引き継ぐ。自動修復、正式ACK、自動Sender、実運用切替を有効化しない。CIが未実行の間、今回の全PRと既存#80/#62をマージしない。
