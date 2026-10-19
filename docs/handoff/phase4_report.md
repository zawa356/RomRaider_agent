# Phase IV 最終報告（独立レビュー・versioned rebind・provenance・旧v1分類）

次に読むもの: [Bootstrap](../ops/development_environment_bootstrap.md) → 本報告 → [Agent進捗](../product_v1_implementation_progress.md)。詳細は、時系列が[追記worklog](phase4_worklog.md)、実コマンド・log SHA256・検証headが[検証証跡](phase4_validation_evidence.md)、レビュー指摘が[独立レビュー](phase4_independent_review.md)にある。

## 実行者・環境・開始点

- 実行者: Claude Code（Claude Opus 5.5、Claude desktop app）。Phase I〜IIIの実装担当（Codex）とは別のエージェントとして、前任の成果を独立にレビューした。
- 実行環境: Windows 11 Pro開発VM（10.0.26200、ja-JP、利用者権限）、JDK17.0.20.1 x64、実Zulu 1.8.0_504 x86（data model 32）、Python 3.12。VPSはLinux 6.8／ext4／JDK17.0.20.1で、pin済みのhost keyを持つ既存helperを使い、新規の隔離ディレクトリだけで作業した。
- 開始時のmain: Agent `2b620c744e900b1ea2a5f614e0047b256c72a77d`、Server `bb27cd10ad60cda1a7f4434c3f803df6c4db2a39`。両repoともclean、open PRは0件。コード編集前のcheckpointは `0b14ddf`（branch `phase4/checkpoint-review`）。
- GitHub Actions: runnerが起動せず、jobはsteps=0。**CI NOT RUN**として扱った。課金設定の変更や再dispatchはしていない。

## PRと状態

| Phase | PR | head | 状態 |
|---|---|---|---|
| IV-0 独立レビューの修正 | [#75](https://github.com/zawa356/RomRaider_agent/pull/75) → main | `418c23b` | **MERGED** `2e6825c44cdcd1a0d62b6f9c6bd8345216b9e89d` |
| IV-1 RLDT1/RLDB2 | [#76](https://github.com/zawa356/RomRaider_agent/pull/76) → main | `ea9256f` | **MERGED** `d95950c819629a2ebc64199e6cd465d984e505ea` |
| IV-2 RLAP1 provenance | [#77](https://github.com/zawa356/RomRaider_agent/pull/77) → main | `315041e` | **MERGED** `a76b35c4cd51f174248e9adf9b435c8d8ffc8a4e` |
| IV-3/4 RLPC1・batch組込み | [#78](https://github.com/zawa356/RomRaider_agent/pull/78) → main | `04564c5` | **MERGED** `dca2f4da28f8690f59860bb980439c79332bb73a` |
| IV-5 引継ぎ文書 | [#79](https://github.com/zawa356/RomRaider_agent/pull/79) → main | — | 本文書を含むPR。merge SHAは自己参照になるため記載しない（GitHubのPRとmainで確認する） |
| Server進捗の同期 | [RomRaiderServer#61](https://github.com/zawa356/RomRaiderServer/pull/61)（文書のみ） | — | ユーザーの承認後にmerge（SHAはPRで確認） |

2026-10-19、ユーザーの明示的な承認を受けて #75→#76→#77→#78 の順に通常mergeした。stackedのbaseは、それぞれ直前のmerge後にmainへ付け替えている。各PRには、merge前の確認記録コメントを残した。どのPRも、本番切替・本番DB migration・Workerの停止や置換・正式ACKを含まない。合成でopt-inした候補コードと文書だけで構成している。

## Phaseごとの判定

| Phase | 判定 | 内容 |
|---|---|---|
| IV-0 | **完了** | 再現した欠陥: High 1件（F1: Windowsの識別子が作成日時だけだったため、同じパスで旧DBやledgerを差し替え・復元しても通過した）、Medium 1件（F2: 既知artifactの判定がManifestの`Class-Path`やagent属性を無視していた）、Low 2件（F6・F7）を、RED→修正→GREENで直した。既知の制約としてMedium 1件（F3: ドライブ文字に割り当てたネットワークドライブ）を、未修正のまま文書化した |
| IV-1 | **候補コード完了（合成のみ）** | RLDT1のtransition（prepare→anchor→root→committed、abandon、決定的なplan digest、lockを保持したままのcommit）、RLDB2のmanifest内容floor、読み取り専用のinspect/plan CLI、`ingestBoundActive`。transitionの証跡があるとRLDB1は `BINDING_SUPERSEDED` を返す |
| IV-2 | **候補コード完了（読み取り専用）** | RLAP1（順序付きJARのhash・class集合・runtime）。processの証拠は段階分けで扱う: self reportは主張にすぎない、検証側が起動した子JVMは照合できる、Linux同一ユーザーでは`/proc`のcmdlineと実際に開いているJARのbytesを照合できる、Windowsは常にUNKNOWN |
| IV-3 | **分類エンジンと隔離stagingが完了。完全な変換はBLOCKED** | RLPC1で4分類（VERIFIED_ORIGINAL／LEGACY_WITHOUT_RLC2_PROVENANCE／CONFLICT／UNKNOWN_OR_INCOMPLETE）。RLLV1 vaultと、VERIFIEDな原本だけから作るRLI2候補を隔離stagingで作成する。原本のない旧v1 recordはRLI2へ移さない（移す方法がないため、BLOCKEDのまま） |
| IV-4 | **完了（下表）** | Windows x64と実x86、Linux、Server回帰、1万レコード規模の回帰、修正前コードでのRED確認 |
| IV-5 | 本PR | 報告・証跡・進捗・Bootstrapの更新とServer側の同期 |

## 独立レビューの主な指摘（詳細は[review](phase4_independent_review.md)）

- **F1 High（修正済み）**: Windows版Javaでは `fileKey()` が `null` を返すため、RLDB1のidentityが作成日時だけになっていた。作成日時は利用者権限で書き換えられ、NTFS tunnelingで引き継がれる。差替え・復元・作り直しの3パターンをWindowsでREDとして再現した。volume serialとNTFS file IDで束縛するよう修正した（`fsutil file queryfileid`、読み取り専用・timeout付き）。旧形式で登録されたbindingは `BINDING_IDENTITY_REENROLL_REQUIRED` で拒否する。Linuxはinodeとbirth timeで検出できることを実測で確認した。
- **F2 Medium（修正済み）**: Manifestの`Class-Path`／`Launcher-Agent-Class` を持つJARを既知と判定していた。属性を許可リスト方式にし、class以外のresourceと重複entryも拒否する。WindowsとLinuxの両方で修正前REDを確認した。
- **F6 Low（修正済み）**: RMI1のsidecar判定がlinkをたどっていた。最初のテストは後段の検査で拒否されてしまい、修正の有無を区別できていなかった。テストを厳格にしたうえで、Linuxで修正前RED→修正後GREENを確認した。
- **F3（既知の制約）**: SMBのネットワークドライブをドライブ文字に割り当てると、FS種別の判定を通過する可能性がある。再現はNOT RUN。

## 検証結果（詳細は[証跡](phase4_validation_evidence.md)）

| 対象 | 結果 |
|---|---|
| Windows JDK17 x64／Zulu8 x86（IV-0、`418c23b`） | review 9/0（両Java）。Phase III回帰exit0（両Java）。acceleration batchはexit0、PASS行2157 |
| Windows全体batch（`0e61251`、Phase IVを組込み） | **exit0**、PASS行2413。x64・x86の両方で review 9/0、transition 64、provenance 21、legacy 34。Phase III回帰も同じ件数で通過 |
| Linux JDK17（`0e61251`） | phase4 exit0: review 9/0、transition 64、provenance 22（`/proc`の照合と、起動後のJAR差替え検出を含む）、legacy 34。phase3 exit0: binding17・engine6・readiness11・backup32・stages52 |
| Linuxの修正前コード（`2b620c7`） | F2・F2b・F6がRED。F1はLinuxでは発生しない（Windows固有の欠陥であることを確認） |
| Linux bound 1万レコード（`0e61251`） | 10,003 records、6 checks、exit0 |
| Server main `bb27cd1`（専用テストDB） | migration upgrade×2とcheckがexit0、pytest 57 passed（warning 1件） |
| 実HTTP＋invocation-owned PG（35項目） | exit0、35 PASS。停止したのは自分で起動したclusterだけで、既存の55432は稼働したまま |
| Windows bound 1万レコード | x64・x86ともexit0（10,003 records） |

## 変更していないことの確認

X270、OpenPort、ECU、実車、実Loggerには一切アクセスしていない。既存の旧v1 DB、ACK状態、receipt、cursor、highwater、record ID、原本ファイル、既存Worker、サービス、VPSとDockerの既存container・DB・volumeは変更していない。新しいテストはすべて、新規に作った合成fixtureと隔離ディレクトリだけを使った。既存の55432 PostgreSQLは停止していない。Server側の変更は文書だけ。資格情報やDSNは、出力にもcommitにも含めていない。

## 未成立・BLOCKED・NOT RUN

- **BLOCKED**: global fencing（別host、複製root、同一pathとidentityを持つclone、ネットワークFS）。`--external-single-active` は運用者の宣言であり、機械的な証明ではない。
- **BLOCKED**: 原本のない旧v1 record（TSV lane、RLC1 journalだけのもの）をRLI2へ移すこと。vaultでの保持にとどめる。
- 未実装: commit後のrollback（以前の世代へ戻すこと）、Phase IV以前のbinaryの機械的な排除（運用で排除する必要がある）、Windowsでのprocess provenance、コード署名と配布経路の真正性、F3の機械的な検出。
- NOT RUN: 旧binaryを実際に実行する試験、RLPC1 staging中の子JVM halt、別rootからの復元staging、同一Session内の `SOURCE_UUID_REUSED` 専用fixture、Windowsでのsymlink試験（権限なし）。
- **Windows directory durability = UNKNOWN**。物理電源断・実ENOSPC・実車はNOT RUN。Runtime.haltは物理電源断の証明ではない。
- CI NOT RUN（runner未起動）。

## 再開手順

```powershell
Set-Location C:\Dev\RomLogger\RomRaider_agent
git status --short
git fetch origin
gh pr list --state open        # #75〜#78 と IV-5 がstackedでopen
git log --oneline -8 origin/phase4/iv5-handoff
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1   # Phase IVの試験を含む
bash scripts/test-phase4.sh                                 # Phase IVだけを実行する場合
```

次の作業候補:

1. mainで `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1` を再実行し、merge後のmainを確認する（merge後のmainでの再試験は本セッションでは未実施）。
2. RLDT1の「旧binary排除」を、Worker起動時に動くRLAP1のself report／`/proc`照合と組み合わせる。
3. F3対策として、drive typeの照会を読み取り専用で追加する。
4. RLPC1 stagingの子JVM halt試験と、同一Session内の `SOURCE_UUID_REUSED` 専用fixtureを追加する。
5. 原本のない旧v1 recordを、どの別storageの扱いとするか（RLLV1 vaultを正式なversionとして採用するか）をユーザーと決める。
