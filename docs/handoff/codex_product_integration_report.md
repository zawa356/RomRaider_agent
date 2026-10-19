# Phase I 製品統合基盤・実Server結合 最終報告

2026-10-18。I-1〜I-7の実装を7件のPRで通常マージした。複数物理Sourceを所有する論理Session、原子的な永続取込、snapshot変更検出、構造検証、診断契約、実Server HTTP結合、障害・負荷試験が実行可能になった。**現在の経路は明示opt-inの合成・静止状態専用であり、既存Workerの自動置換・自動送信・正式ACKは有効化していない。**

最終コードmain: `aa55dcd007b48728a320448fb827049254fcb6fd`。検証した実装head `be086297f758133d13632e1a54acd274459b0a39` とtree差分なし。Server進捗正本main: `9274394ec5b093ecd71f44c7eaec146130bb1d32`（[Server PR58](https://github.com/zawa356/RomRaiderServer/pull/58)）。本報告の後続PRは文書のみ。逐次記録は [worklog](codex_product_integration_worklog.md)。

開始基準はAgent `e916f57eef0f450eea35cad78c0e9d105a1f9fe0` / Server `d66ab42648181ef64b5264d71b9694ee5cc63816`。両方clean main/origin一致、open PR0、個人noreply作者を確認した。コード編集前のcheckpoint `9da2e08` をcommit/pushし、小Phaseごとに保存した。

## 完了した実装

| Phase | 実装 | 結果・境界 |
|---|---|---|
| I-1 | `Rlc2IntegrationSchema`、[storage contract v2](../interfaces/rlc2_integration_v2.md)、D-057 | RLI2 application ID / migration revision1の専用DB。旧v1のclaim/TSV/journal cursor/過去runをread-only検査。旧DBを変更・自動移行しない |
| I-2 | `Rlc2IntegrationStore`、共有backendのtransaction guard | 既存変換・取込コードを再利用。Sessionのcanonical directory/run bindingと明示承認したSource群を、claim/cursor/原文receipt/profile/recordと同一transactionで保存 |
| I-3 | `Rlc2InputSnapshot`、競合RED/GREEN | 変換前からcommit直前までの入力変更を検出し出力をrollback。Manifest/Source追加・seal・Ledger・legacy DB・未確定tailの変化を検査。外部quiescenceは必須のまま |
| I-4 | `Rlc2SchemaVerifier`、Supervisor contract v2 | 列/型/NOT NULL/PK/UNIQUE/FK/CHECK/索引/trigger/view/version/余分なobject、quick_check/FKを検証。旧合法v0 DDL互換。診断成功と整合性・durability・ACK・手動介入を分離 |
| I-5 | `test-rlc2-real-server-http.py` | 実Uvicorn/FastAPI、実Server migrations、専用PostgreSQL schema、実HTTPと保存内容を検証。モデル検証や直接INSERTをHTTP成功の代用にしていない |
| I-6 | `Rlc2IntegrationFaultTest`、追加schema負試験 | 実子JVMの4段階halt/restart、別process Worker競合、原本/legacy bytes保全。SQL token正規化の追加REDを修正 |
| I-7 | CLI、Windows一括、候補ストア負荷、実PG停止wrapper、補助CI | 明示的な実行口を実装。実x86/Linuxの10k・11session、実PostgreSQL processの停止・再起動まで確認。既存Worker/送信は自動接続しない |

新しいclassは既存ingress namespaceにある製品候補コードで、`rrbridge.jar`へJNAを追加していない。Windows native CLIはtest-only wrapper。既存 `src/main/.../product/v1` のWorker/sender/製品schemaコードは変更なし。Serverのruntime/API/migrationsも変更なしで、Server側PRは契約・受入・進捗の文書同期のみ。

## Session・Source・IDの契約

- device/vehicle/payload sessionの論理claimが、1つのmanifest-session UUIDとcanonical directoryを所有する。
- 各物理Source UUIDは、元path・native identity・独立したframe sequence/offset/prefix hash・原文receiptを所有する。新しいSourceは、検証済みの延長Manifestに存在し、かつ呼出側がUUIDを明示承認した場合のみ追加する。
- 既存v1 converterのrun/record IDを再利用する。Source UUIDが増えてもrunや既存record IDは変えない。物理frame sequenceと論理runのobservation sequenceは別物。
- 専用DBは `.rlc2-integration.db`、RLI2、user_version=1。共有する6つの `preview_*` 表に `integration_bindings` / `integration_admissions` を追加した計8表。既存preview-v0や製品v1 DBをrenameして流用しない。
- 削除された過去receipt/record/admission、同IDの異内容、巻戻り、別path/Sessionへの再割当は自動再構築せず停止する。ACKは常にfalse。

SQLite初期化・version更新は1 transaction。既存稼働v1 DBへ自動適用されるmigrationは追加していない。PostgreSQL候補schemaは試験用mirrorで、SQLiteのINTEGERをBIGINT、BLOBをBYTEAへ対応させ、revision行と2^40 generation probeを検査する。これは常用PostgreSQL取込backendの実装ではない。

## PR・時刻・main証拠

時刻は全て2026-10-18 JST。開始は各checkpoint commit、終了は通常merge時刻。GitHub Actionsの試験成功を根拠にした表ではない。

| Phase / PR | 開始→merge | main merge SHA |
|---|---|---|
| I-1 [52](https://github.com/zawa356/RomRaider_agent/pull/52) | 21:16:37→22:06:12 | 37de3ea2a0763f9e241799f8c606612c5ee35e48 |
| I-2 [53](https://github.com/zawa356/RomRaider_agent/pull/53) | 21:22:21→22:06:22 | a336a3c89ad6ce8d1ed73f13aa9af6364dceb878 |
| I-3 [54](https://github.com/zawa356/RomRaider_agent/pull/54) | 21:25:37→22:06:33 | 04a4c9963404a33e85a86732c8109efd8ceb9197 |
| I-4 [55](https://github.com/zawa356/RomRaider_agent/pull/55) | 21:29:33→22:06:44 | 90d79c6c7c032a22b7d9f04df7d62230c5c7d41c |
| I-5 [56](https://github.com/zawa356/RomRaider_agent/pull/56) | 21:35:43→22:06:55 | 7b74edf0170eb35a896b5788adb67cd64a9bf646 |
| I-6 [57](https://github.com/zawa356/RomRaider_agent/pull/57) | 21:40:54→22:07:05 | 0a25315476c9cb6d13bb5b0b3b3cb894aac66f93 |
| I-7 [58](https://github.com/zawa356/RomRaider_agent/pull/58) | 21:45:21→22:07:16 | aa55dcd007b48728a320448fb827049254fcb6fd |

PRは前Phaseへstackして保存し、最終stackの全体検証後、依存順にmainへretargetして通常mergeした。force push・履歴改変・既存branch削除なし。

## Actionsと検証方針

[初期Actions実行](https://github.com/zawa356/RomRaider_agent/actions/runs/39126824495)は「recent account payments / spending limit」の注記によりjob開始前に停止し、stepsは空だった。ユーザーは無料枠超過の可能性を示し、ローカル試験を今回の基準とするよう指示した。**その指示に基づくローカル/VPS検証とレビューでmergeした。今PhaseのActionsはNOT RUNであり、CI PASSとは報告しない。** Billing設定の変更や課金操作は行っていない。

Windows/Linux/実x86/SQLite/PGのworkflow定義を維持・拡張した。補助の実HTTP workflowはprivate Server checkout credentialがある場合だけ実行し、ない場合は引き続きNOT RUNを表示する。YAML構文をPyYAML6.0.3（検証venvのみ）、PowerShell scriptをAST parserで確認した。quota/credential復旧後は実mainで適用stepを再実行・確認する。

## 実行結果

全体Windowsログ: `build/acceleration-20261018-214753.log`、exit0。VPSは新しい専用 `/tmp/romlogger-integration-20261018` でproduct/spool、journal、実SQLite、ledger、rotation、diagnostics、preview、candidate、large loadを実行しexit0。合成src/scripts/固定JARだけを転送し、実車archiveや認証情報は送っていない。

| 最終suite | Windows17 x64 | 実Zulu8 x86 | VPS Linux17 x64 |
|---|---:|---:|---:|
| versioned schema | 13 | 13 | 13 |
| admitted engine / 実CLI | 28 | 28 | 28 |
| snapshot mutation | 15 | 15 | 15 |
| 厳密schema / old-v0互換 | 35 | 35 | 35 |
| candidate子JVM故障 | 21 | 21 | 20 |
| 診断 / 変換 / 移行前検査 | 37 / 21 / 19 | 37 / 21 / 19 | 36 / 20 / 18 |
| preview / 既存故障suite | 43 / 44 | 43 / 44 | 42 / 43 |
| default load（1000・4session） | 32 | 32 | 31 |
| large（10000＋10小session） | 別途large NOT RUN | 60 | 59 |

Windows一括ではactual architecture検査の+1を含むsuiteがある。数字を重複加算した総check数は示さない。既存product/原本/receipt/UUID/copy/CRC/marker/rotation/quota/8MiB/16segment試験も含まれる。旧Windows RLC1 full56等の未実施分は下記へ分離した。

大きい負荷ではpreviewとversioned candidate双方を検証し、10100 records / 10111 receipts / 22 physical admissions / 11 logical bindingsを保持。再実行・全highwater・ID・原本bytes・ACK制約を確認した。candidateのsnapshot＋project＋commitは単発観測でLinux1656ms / 実x8610448ms。正規化benchmarkや性能保証ではない。

実HTTPは最新コードで**35項目PASS**。1つは既存の専用loopback test clusterに作った invocation-owned schema、もう1つは新規owned PostgreSQL16.15 clusterで同じ35項目を実施した。70種類の異なる試験という意味ではない。

1. 実Server migrationsをupgrade二度＋check、候補PGの途中DDL失敗rollback/replay/64bit幅を確認。
2. 定義未登録422、実profile登録、3 Sourceをまたぐ同一runの実batch保存。
3. 実commit済みHTTP応答をproxyで切断し、同一batch再送で4件全てduplicate。
4. 同record ID異内容409、スキーマ不正422、定義外parameter422。
5. owned schema内の実PostgreSQL transaction失敗でACKなし。
6. shared test serviceを止めない接続不能endpoint503と、別owned PG daemonを実際にimmediate stopしたときの503/noACK。
7. owned daemon再起動後に確定済み4レコード・1run・sequence0/1/2・profile/value参照・Server canonical JSONを直接SQL確認。
8. HTTP test ACKをAgentへ反映せず、candidate DBの全bytesが不変であることを確認。

Server既存回帰も専用DBでupgrade二度/check＋**57 passed, 1 warning**。旧product senderのmock HTTP試験と、今回の実Server HTTP試験は別の結果として記録した。

検証venv: Python3.12.10 / FastAPI0.143.3 / Uvicorn0.54.0 / psycopg3.3.6 / SQLAlchemy2.1.5 / Alembic1.20.0。Javaは17.0.20.1 x64と1.8.0_504 x86。Java bytecodeはrelease8。既存Server requirementsの範囲指定は変更していない。

## 発見した不具合と失敗履歴

- RED `d08860d`: projection後のSource/Ledger更新を許した古いcommit。入力evidenceをcommit直前まで検証しrollbackへ。
- RED `0a27d0d`: ACK必須列の改名をowned検査が受理。全schema object/制約/version/integrity検査へ。
- RED `15f04f3`: `TEXT NOT NULL` と `TEXTNOTNULL` の正規化衝突。空白token境界とliteralを保持し、負試験35とold-v0互換を再確認。I-4の初期検査だけで完成とはせず、I-6修正を含む全stackで最終検証した。
- HTTP harness: 既存record内容改変の409を参照不正422と誤期待したため、新record IDで参照検証を分離。timestampの元文字列表現ではなくServer canonical JSONで厳密比較。Server runtimeの修正は不要だった。
- owned PG harness: Windowsの継承pipe待ちで起動待機が戻らないケースを修正。該当owned PID/data pathだけを確認・停止し、DEVNULL/hidden processとcleanup fallbackへ。次の実停止時には未設定の接続timeoutで待機したため、試験DSNにconnect_timeout=2を設定して再実行。最終35 PASSと全owned cluster停止を確認。失敗を成功として数えていない。

## 未実装・NOT RUN・安全ゲート

- **稼働Writerとのlive取込と旧Worker併用はBLOCKED。** `externallyQuiescent=true` / `--quiescent` は呼出側の宣言であり、自動停止や排他leaseではない。producer、Manifest/Ledger更新、legacy importerをsnapshot開始からcommitまで停止する。最後の再読直後に非協調writerが書く場合までatomicとは主張しない。
- 既存稼働v1 DBへの正式migration、既存Worker自動置換、自動送信、正式ACK、未ACK原本削除は未実装・無効。旧単一Source経路/ACK状態はそのまま保持する。read-only legacy inventoryは将来の旧Worker動作をlockしない。
- Windows directory durability UNKNOWN。物理電源断、OS再起動、実ENOSPC、実ECU/X270/OpenPortはNOT RUN。JVM haltやPG immediate stopを物理電源断の証明にしない。
- root `test.sh` は `lib/RomRaider.jar` がないためNOT RUN。旧Windows RLC1 full56は既存durability gateによりNOT RUN/BLOCKED。ローカルの権限が必要なsymlink fixtureはNOT RUN。今PhaseのActionsも実行されていないため、そのcoverageを足さない。
- RLC1→RLC2は既存の移行前検査/dry-runのみで、自動変換・旧identity読み替えなし。
- 初回claimのTOFU、digestは認証ではないこと、POSIX identityがpath-sampledである限界を維持。全DB/原本を整合的に書き換える管理者への認証防御ではない。

終了時の環境確認: 既存Docker14サービス全てUp、専用container残存なし。VPS専用領域に実行中test processなし。shared test PGのpreview/HTTP schema残存0、3つのinvocation-owned PG cluster全て停止、既存55432 clusterはready。合成原本・失敗証拠・ignored buildログ/dataは保持し、自動修復や再帰削除をしていない。認証情報をGit/PR/chatへ出していない。

## 次の実装と再開

次は製品への正式採用の前提を決める。旧Workerと新engineのlane所有権を同一のversioned transaction/協調leaseへ移し、未ACK/ACK済みrowを変えず、未知Source・途中migration・rollbackを検査する設計が必要。稼働DBへ自動適用する変更は別途影響確認・承認の対象。Writer/Manifest/Ledgerの協調snapshot規約も必要で、現在のfingerprintをlive atomicityへ読み替えない。正式ACKはdirectory durabilityと別の受入条件が満たされるまで無効のまま。

PowerShell7で再開（ユーザーの新しい変更があれば先に保護）:

```powershell
Set-Location C:\Dev\RomLogger\RomRaider_agent
git status --short
git switch main
git pull --ff-only
gh pr list --state open
git log -5 --oneline
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

実HTTPを含める場合、保護されたprocess環境へ専用loopback test DSNを設定し、同scriptに `-Postgres -RealServerHttp -Python C:\Dev\tools\venv-server\Scripts\python.exe -ServerPath C:\Dev\RomLogger\RomRaiderServer` を付ける。DSNを履歴へ直書きしない。owned PG process-stopの別実行は [自動化手順](../acceleration_test_automation.md)、CLI引数と承認UUIDの扱いは [契約](../interfaces/rlc2_integration_v2.md) を参照する。

```powershell
Set-Location C:\Dev\RomLogger\RomRaiderServer
git status --short
git switch main
git pull --ff-only
gh pr list --state open
```

quota復旧時はAgentの `ingress-journal-prototype-ci.yml` / `product-v1-spool-ci.yml` を実mainで再実行し、job成功だけでなく対象stepsの実行を確認する。実HTTP CIはprivate checkout credentialが別途必要。次branchは最新mainと本worklogの基準SHAをcheckpointしてから着手する。

Next fresh chat: [development bootstrap](../ops/development_environment_bootstrap.md) and [Phase II worklog](codex_phase2_worklog.md).
