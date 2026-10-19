# Phase V 実装報告 — 旧v1出自照合と隔離Stagingの強化

入口: [Bootstrap](../ops/development_environment_bootstrap.md) → 本報告 → [検証証跡](phase5_validation_evidence.md)。[追記式worklog](phase5_worklog.md)にRED/GREEN、失敗、commit、再開点を残した。[次回のP1/P2設計](phase5_followups.md)も参照する。

本変更はdefault-off・synthetic-onlyの候補実装。本番切替、正式ACK、自動送信、実車への接続は有効化していない。

## 開始点と保存先

- Agent開始main: `4f80c5e9831ec39368f4e771d829337598eeaa76`。Server開始main: `d8c7a2f3b96ba278d269074dc40cca7741b4155b`。開始時に両checkoutはclean、open PRは0件。Agent #79とServer #61は既にMERGEDだったため、古いbaseの変更・再マージは不要だった。
- 実装branch: `phase5/provenance-hardening`、[Agent PR #80](https://github.com/zawa356/RomRaider_agent/pull/80)。実装の最終headは `b4b909cc3111c11e58f35b7600e47bea1d6aadfa`。以後の引継ぎcommitは文書のみで、コード・テスト・CI定義はこのheadと同一。
- 指定Windows開発VM、JDK17 x64、実Zulu8 x86、pin済み接続先のLinuxを使用。既存main checkoutを保護し、新しいworktreeと合成fixtureで作業した。

## 実装済み

| 優先度・範囲 | 実装内容 |
|---|---|
| P0 Cursor | UUIDの存在確認を廃止し、claim／Cursor／原本のdevice・vehicle・logical session、実物Source UUID・正確な絶対pathを照合。整数sequence・範囲、RLC1 first hash／chain／metadata snapshotも実payloadから再計算する。欠落・重複・orphan Cursorやclaim衝突はSession全体を止める。RLC1 chainをRLC2 highwater／receiptへ変換しない。 |
| P0 保存済みv1 Record | DB内のhashを信用するだけでは、本文や時刻を変更してもVERIFIEDになった。実原本の再Projectionと、保持されているJSONおよび全canonical属性を比較する。未ACKで本文NULLはUNKNOWN、ACK済み本文NULLの正当な履歴は引き続き検証可能。既存ID・hash規則・ACK状態は変更しない。 |
| P0 Vault | 保存する11列すべて、全Session分類、metaを型・NULL込みで独立照合。application_id／user_version、全schema object、quick/integrity/FK checkを確認し、未知trigger／viewも拒否する。 |
| P0 RLI2 | 分類時に固定した実原本Projectionから、8表すべての正確な集合・内容を独立生成して照合。Record／Profile／Receipt／Source・cursor／Session・Manifest／claim／binding／admissionの余剰・欠落・変更を拒否する。INSERT OR IGNORE後も既存binding／admissionの値を照合する。 |
| P0 原本の固定 | v1・原本・ledgerのsnapshotをStaging／検証の前後で確認する。WindowsではfileKeyがNULLのため、同一バイト列への原本置換をhashだけでは検出できなかった。実RLC2 readerで物理識別子と捕捉済みmarkerも再検証する。分類前の原本成長は許可し、分類からStaging完了までの変更は拒否する。 |
| P1 衝突 | 同じ物理UUIDが別runで再使用された場合、後続だけでなく先行runもCONFLICTにする。入力順の両方を検証。run IDは従来どおりdevice＋session由来とし、vehicle衝突はclaim衝突として拒否する。 |
| P1 Staging | 新規の非公開work directoryを原子的にclaimし、CREATE_NEWの内部DBだけをSQLiteで書く。完成後forceし、上書きしないhardlink作成で公開する。既存の最終出力は書込みopenしない。競合ファイルと途中成果を保持し、再実行で採用・削除・上書きしない。4地点の子JVM halt、3地点の公開競合、旧Worker lockを検証した。 |
| 引継ぎ | report／evidence／worklog、Bootstrap、進捗、判断記録、interfaceと次回設計を更新。Phase IV #79／Server #61の実際のマージ状態も反映する。 |

`stagingState`の成功値は `STAGED_LOCAL_HASHES_MATCH_INACTIVE` へ変更した。これは「完成した局所ファイルと局所markerのhashが一致」という意味であり、現在の原本の真正性・利用許可・完全復元を意味しない。原本が成長した後の古い候補を自動拡張せず、新たなsnapshotには別名の候補と再レビューを必要とする。

RLLV1は全v1 Record行とhistorical ACK列の証跡であり、完全な旧DBバックアップではない。Profile／ACK receipt／Cursor／delivery／review等の完全な保全責務は、変更していないv1原本とRMI1にある。RLLV1の正式storage versionへの採用は未承認の提案に留める。

## 検証済みと実行中を区別する

最初に32件のCursor／出力改竄の誤受理をREDとして再現。その後、Windowsの同一バイト列原本置換1件、保存hashを残した本文・属性改竄4件も追加でRED→GREENにした。

最終headの重点試験は、主試験56件＋物理置換1件＋保持本文4件＝61ケース。Windows JDK17 x64／実Zulu8 x86（JVM32）／Linux JDK17で成功。LinuxのPhase III／IV・preview／integration／schema／snapshot／子JVM回帰、1万件Stagingと1万件＋10 Sessionの負荷・冪等性、実x86の1万件Staging、実FastAPI＋専用PGのHTTP35件も成功している。Serverのmigration2回／checkと57試験も成功（warning1件）。

**最終headのWindows全体バッチは実行中。完了した実結果を下記証跡で確定するまではPASSに数えない。** head・環境・command・exit・件数・log SHA256は [検証証跡](phase5_validation_evidence.md)。異なるheadの過去PASSや、編集途中／中止したbatchを最終headのPASSとしていない。

## 未完了・次回の範囲

| 範囲 | 未完了内容 |
|---|---|
| P1追加異常系 | journal生成前の非協調SQLite reserved lock、別process Writerの更新／rollback中のRLPC1、権限を要するWindows symlink／junctionでの出力親差替え。immutable readと外部quiescence申告だけで任意Writer不在を証明できるとはしていない。 |
| P2 F3 | ドライブ文字に割り当てたSMBのlocale非依存drive type検出は未実装。合成probe／実ドライブ試験の設計を引き継ぐ。 |
| P2 実行物 | 新製品入口での旧binary排除、実際の旧binary専用試験は未実装。既存入口の棚卸しとdefault-off launcher案を引き継ぐ。 |
| P2 Transition | 同一Session内SOURCE_UUID_REUSED、abandonとlive lock、世代交差の追加専用fixtureは未実装。既存Phase III／IV回帰の成功とは区別する。 |

## BLOCKED・NOT RUN・継続する安全ゲート

| 項目 | 状態 |
|---|---|
| PRのマージ | CI NOT RUN。Actionsのfailureはjob steps=0でrunner未開始。今回の明示指示に従い、CI未実行のPRはマージしない。課金変更・再dispatchなし。 |
| 正式利用・本番移行 | 正式ACK／自動送信／本番migration／実車接続は無効。候補の実装・合成検証を安全な本番利用と混同しない。 |
| 実行環境の証明 | Windows process provenanceとdirectory durabilityはUNKNOWN。global fencing、悪意ある同一user／管理者による整合的な差替えは未証明。 |
| 物理障害・実車 | 物理電源断／実ENOSPC／ECU／OpenPort／X270はNOT RUN。Runtime.haltは物理電源断の証明ではない。 |
| root自己試験 | `test.sh`に必要なRomRaider.jarがないためNOT RUN。合成の製品・spool・ingress回帰とは区別する。 |
| 部分成果の扱い | `.rlpc1-work`と途中DBを保全。自動回復・採用・削除なし。2DB＋markerは単一atomic transactionではない。 |

## 再開手順

```powershell
Set-Location C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\RomRaider_agent
git status --short --branch
git fetch origin
gh pr view 80 --json state,headRefOid,isDraft
git log -6 --oneline
# 先に証跡のheadと結果を読む。変更・失敗・未解決懸念がある場合に再試験する。
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

固定検証checkoutは `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\phase5-acceptance-final`。実行中のBashファイルを編集しない。ignored logと途中合成fixtureを保全する。次の実装は [具体的なP1/P2設計](phase5_followups.md)から再開できる。これらの手順は既存の実DB／Workerや送信経路への操作を許可しない。
