# Phase IV 独立レビュー（別エージェント）

- レビュー担当: Claude Code（Claude Opus 5.5）。Phase I〜IIIの実装担当（Codex）とは別のエージェント。
- 対象: Agent main `2b620c744e900b1ea2a5f614e0047b256c72a77d`（PR #74）。Server main `bb27cd10ad60cda1a7f4434c3f803df6c4db2a39`。
- 方法: 実コードを読み、仮説ごとに**新しい合成fixtureだけ**で再現テストを書いて実行した（`src/test/java/net/neristal/rrbridge/product/v1/Phase4ReviewRedTest.java`）。前任の報告書やPASS記録は事実として扱わず、REDを実際に観測できたものだけを「確認済み」とした。
- 読んだコード: `DeploymentBinding`, `LaneOwnership`, `LegacyWorkerGuard`, `CutoverReadiness`, `V1LaneMigration`, `Rlc2IntegrationStore`, `Rlc2InputSnapshot`, `IsolatedBackupAcceptance`, `SyntheticMigrationRehearsal`, `IngressDiagnostics.readOnly/safePath`, `Rlc2IntegrationSchema.requireUnclaimed` と、Phase IIIの関連テスト・`scripts/test-phase3-deployment.sh`・RLDB1/切替診断の文書。`DurableSpool`（830行）はpragma・ロック・WALの箇所だけを読み、全文はレビューしていない。

## 結果一覧

| ID | 重大度 | 状態 | 概要 |
|---|---|---|---|
| F1 | **High** | RED→修正→GREEN（Windows） | WindowsでのRLDB1 file identityが作成日時だけで、同じパスに置かれた差替え・復元済みの旧DBやledgerを検出できない |
| F2 | Medium | RED→修正→GREEN | `knownArtifact`がJAR Manifestの`Class-Path`／`Launcher-Agent-Class`などやclass以外のresourceを無視するため、照合していないコードを読み込めるJARが「既知build」と判定される |
| F3 | Medium（既知の制約） | 未修正・文書化済み | ドライブ文字に割り当てたネットワークドライブは、UNC判定もFS種別判定もすり抜ける可能性がある（静的な指摘。再現はNOT RUN） |
| F4 | Low | 確認済み・変更なし | Linuxのidentityは、JDKが作成日時（birth time）を返すことが前提になっている |
| F5 | Low | 変更なし | lane・旧Workerのlock pathの安全確認はsymlinkだけを拒否し、junctionは拒否しない。ただし相互排他は保たれる |
| F6 | Low | 修正（Linuxで要確認） | RMI1のpreflightで、壊れたsymlinkのsidecarを「閉じたDB」と誤って判定する |
| F7 | Low | 修正 | fsutilの出力を待機後に読み、UTF-8でdecodeしていた（pipeが詰まるとtimeoutで拒否になり、localeに依存する） |

## F1 — High: WindowsのRLDB1 identityが作成日時だけ

**根拠となるコード:** `DeploymentBinding.identity()` は `fileKey()+"|"+creationTime()` を返す。Windows版Javaでは `BasicFileAttributes.fileKey()` が常に `null` になる（2026-10-19、JDK17.0.20.1で実測。`p4scratch/K.java` の出力 `key=null`）。そのためWindowsで束縛されるidentityは `null|<作成日時>` だけになる。作成日時は利用者権限で書き換えられ（`Files.setAttribute(...,"basic:creationTime")`）、NTFSのname tunnelingは同じ名前で削除→再作成されたファイルに元の作成日時を自動で引き継ぐ。

**再現（RED、Windows JDK17 x64、`build/phase4-review-red.log`）:**
- F1: 束縛済みの旧DBを、作成日時を保ったまま別内容のファイルへREPLACE_EXISTINGで差し替える → `verify` が受理した。
- F1b: 同じ操作をledgerに対して行う → 受理した（巻き戻したdurable ledgerを検出できない）。
- F1c: 旧DBを削除して同じ名前で作り直す（作成日時は操作しない）→ tunnelingによって受理した。

**影響:** 運用者が古い旧v1 DBのバックアップを同じパスへ復元すると、binding検査と`ingestBoundDeployment`のprecommit再検査がどちらも通過する。新しいDBにしかないsource claimを、`requireUnclaimed`が古いDBを見て「未所有」と判定し得る。つまり二重所有の入口になる。Linux（JDK17・ext4）では、作り直しでinode番号が再利用されても作成日時が変わるため検出できた（VPSで実測。`dev=801,ino=19122` が再利用され、ctimeが変化した）。

**修正:** POSIXではこれまでどおり `fileKey|birth time` を使う。Windowsでは `ntfs:<volume:vsn>:<NTFS file ID>|<作成日時>` に変更した。file IDは `SystemRoot\System32\fsutil.exe file queryfileid` で取得する。この呼び出しは読み取り専用で、timeoutは5秒、出力は32KiB以内とし、待機中もpipeを読み続ける。日本語などlocaleに依存する文言は無視し、16桁または32桁の16進IDがちょうど1つ含まれることを要求する。旧形式（`null|`）で登録されたWindowsのbindingは `BINDING_IDENTITY_REENROLL_REQUIRED` で拒否する（記録を自動で書き換えることはしない）。

**GREEN:** F1/F1b/F1cはすべて `BINDING_LAYOUT_CHANGED` で拒否された。対照として、in-placeの書込みでは同じfile IDのまま検証を通る。F1d（旧形式のidentity）は再登録を要求する。Phase IIIの回帰（binding18/engine6/readiness11/backup32/stages52）はJDK17 x64でexit0。x86の結果は検証証跡を参照。

**残る範囲:** fsutilとOSは信頼する前提の環境であり、管理者がfile IDを偽装すること、あるいは全ファイルを整合的に作り直すcloneは、引き続き範囲外（global fencingはBLOCKED）。

## F2 — Medium: 既知artifactの判定がManifestとresourceを見ていない

**根拠となるコード:** `CutoverReadiness.knownArtifact()` は、`.class` 以外のentryを読み飛ばす。Manifestは一切読まない。

**再現（RED）:** 現在のbuildのclassだけを含むJARに `Class-Path: override.jar`（F2）や `Launcher-Agent-Class`（F2b）を加えても、`knownArtifact=true` になった。`Class-Path` のJARは、このJARに含まれないclassの供給元になる。

**修正:** Manifestのmain属性を許可リスト方式にした（Manifest-Version、Created-By、`Premain-Class: net.neristal.rrbridge.Agent`、`net.neristal.rrbridge.` 配下の Main-Class）。entryごとのsectionは拒否する。class・directory・`META-INF/MANIFEST.MF` 以外のentry（`META-INF/services`、署名ファイルなど）も拒否する。ZIP内で名前が重複するentryも拒否する。対照として、本番と同じ `Premain-Class` のManifestを持つJARと、Manifestを持たない現在のbuildのJARは、引き続き既知と判定される。

**残る範囲:** 実際に稼働しているprocessが、このJAR・このclasspathで起動されたことは証明しない（IV-2で扱う）。

## F3 — Medium（既知の制約）: ドライブ文字に割り当てたネットワークドライブ

`DeploymentBinding.safe()` は `\\` で始まるUNC pathだけを `NETWORK_PATH_UNSUPPORTED` で拒否する。SMBのネットワークドライブを `Z:` のように割り当てた場合、`FileStore.type()` はリモート側のFS名（例: `NTFS`）を返すため、許可リストを通過する可能性がある。Javaの標準APIはdrive type（`DRIVE_REMOTE`）を公開していない。RLDB1の文書は「FS種別の文字列は偽装されたリモートストレージへの証明にならない」と既に明記しているので、新しい欠陥ではなく既知の制約に分類した。再現にはSMB共有か管理者権限が必要なため、**NOT RUN**。次の候補は、`[System.IO.DriveInfo]::DriveType`（localeに依存しないenum値）を読み取り専用で照会すること。

## F4 — Low: Linuxのidentityはbirth timeが前提

VPS（Linux 6.8・ext4・JDK17.0.20.1）では `creationTime()` が実際の作成日時を返し、書込み後も変わらなかった。birth timeを返せないFS／JDKの組み合わせでは、Javaは最終更新日時を代わりに返す。その場合、正当なin-place書込みでもbindingが拒否される（安全側だが、継続運用が止まる）。IV-1のtransition契約では、検証環境のJDKとFSの組み合わせを証跡に残すこと。

## F5 — Low: lockのpath検査がjunctionを拒否しない

`LaneOwnership.safe`／`LegacyWorkerGuard.safe`／`IngressDiagnostics.safePath` は `isSymbolicLink` だけを拒否する。Windowsのjunctionは `isOther` 扱いのため通過する。ただし、同じ物理ファイルに対するlockは、JVM内ではFileKeyで管理されるlock table、JVM間ではOSのlockで衝突するため、別pathからのjunctionでも相互排他は崩れない。bound deploymentの入口では `DeploymentBinding.safe` がjunctionを拒否する。低レベルの研究用APIには変更を加えていない。

## F6 — Low: RMI1 preflightのsidecar判定がlinkをたどる

`V1LaneMigration.closed()` は `Files.exists(path)` をlinkをたどる形で呼んでいたため、リンク先の存在しない `-wal` symlinkを「存在しない」と判定していた。`NOFOLLOW_LINKS` に修正した。Windowsにはsymlinkを作成する権限がないため、このテストは **SKIP / NOT RUN**。Linuxでの実行結果は検証証跡に記録する。

## F7 — Low: fsutilの出力処理

hardlink検査では、process終了を5秒待った後でstdoutを読んでいた。出力がpipe bufferを超えるとtimeoutで拒否される（安全側）。さらにCP932の出力をUTF-8でdecodeしていた。共通の `fsutil()` helperに移し、待機中も出力を読み続け、byteを保ったままdecode（ISO-8859-1）するように変更した。行頭が `\` であるかの判定は、どちらの方式でも変わらない。

## 問題がないことを確認した点

- `CutoverReadiness` のexit0（`READY_FOR_SYNTHETIC_TEST`）が返すJSONでも、`production_authorized=false`・`ack_eligible=false`・`automatic_cutover=false` は固定されている。正式ACKや本番許可と誤認される経路は見つからなかった。
- `LaneOwnership` は `RandomAccessFile` と `FileLock` をfieldに保持し、`close()` 以外では解放しない。子JVMが異常終了するとOSのlockは解放され、generationが進む（設計どおり）。PIDやtimeoutによるtakeoverはない。
- `Rlc2IntegrationStore.ingestOwned` の順序は、snapshot取得 → projection → snapshot再検証 → lane key → admission → `requireUnclaimed` → transaction内のbeforeWrite（snapshot・各lock・binding）→ beforeCommit（unclaimed・snapshot・各lock）になっている。bound入口では、すべてのhook stageでRLDB1を再検証している。
- `Rlc2InputSnapshot` はsession内の全ファイル・ledger・旧DBの内容をSHA-256で比較するため、mtimeやファイル名の変化だけに依存していない。
- 旧DBの読み取りは `mode=ro&immutable=1` で行い、その前に `-wal/-shm/-journal` が存在しないことを要求している。

## 明示的な範囲外（不具合として扱わない）

別host・複製root・ネットワークFSにまたがるglobal fencing、`--external-single-active` が運用者の宣言にすぎないこと、実際に稼働しているprocessのprovenance、RMI1→RLI2の正式変換、Windows directory durability（UNKNOWN）、物理電源断・実ENOSPC。これらは前任者がBLOCKED／NOT RUNとして明示している。
