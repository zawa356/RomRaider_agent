# RomLogger Codex日中開発報告 — 2026-10-14


## 今回の結果

4つの小Phaseを自律実装・試験・通常mergeし、Server正本へ同期した。製品用ライブジャーナルの完成宣言ではない。

| Phase | 結果 | Agent / Server同期 |
| --- | --- | --- |
| 4b-2d-2b-3a 原本識別3案比較 | A hardlink一式コピー/C UUIDコピーの反例を再現。B native NTFS volume/file indexを次候補へ | [Agent31](https://github.com/zawa356/RomRaider_agent/pull/31) / [Server42](https://github.com/zawa356/RomRaiderServer/pull/42)、MERGED |
| 3a-compat（安全代替） | 同じnative READ handleへIDとbytesを結合。actual Zulu8 1.8.0_504・32bitの実行に成功 | [Agent32](https://github.com/zawa356/RomRaider_agent/pull/32) / [Server43](https://github.com/zawa356/RomRaiderServer/pull/43)、MERGED |
| 3a-writer-binding（安全代替） | 同じnative handleでID/owned prefix/WriteFile/FlushFileBuffers、native guardと新旧writer排他を検証 | [Agent33](https://github.com/zawa356/RomRaider_agent/pull/33) / [Server44](https://github.com/zawa356/RomRaiderServer/pull/44)、MERGED |
| 3a-writer-integrity（レビュー修正） | flush後のCRC-valid payload改変をREDで再現し、exact written-frame比較へ修正。原本保全/latch | [Agent36](https://github.com/zawa356/RomRaider_agent/pull/36) / [Server46](https://github.com/zawa356/RomRaiderServer/pull/46)、MERGED |

すべて`src/test`内の独立fixtureとCI/試験scriptが対象。既存RLJ1/RLI1/RLC1、製品writer、Logger、Worker、SQLite/ACK、正式Server IFのコードは変更していない。JNA5.14.0はSHA256固定のtest-only build dependencyで、製品lib/distや本番Windowsへ導入していない。

## 実際に確認した試験

| 環境 | 最終結果 | 区分・制限 |
| --- | --- | --- |
| CloudPC MCP / Linux OpenJDK17.0.20.1 | VPS integrity worktree tested `07e2aaa5679f11c6d6fdaae64486f2e82f53bc67`でjournal222 + realSQLite/receipt344 = **566 PASS** | 合成のみ。native Windows writer runtimeはNOT RUN |
| Actions Windows Server2025 / Temurin17 x64 | journal168 + SQLite344 + nativeID18 + boundREAD20 + nativewriter46 = **596 unique checks PASS** | Windows旧RLC1 full watermark56はBLOCKED/NOT RUN、directory sync UNCONFIRMED |
| Actions Windows Server2025 / **Zulu8 1.8.0_504 x86** | nativeID18 + boundREAD21 + writer47 + anchor比較25 + split39 + watermark安全拒否6 = **156 PASS** | 実data-model32をassert。x86 SQLite/product suite NOT RUN。X270/Windows10/RomRaider同JVMではない |
| WSL OpenJDK17 --release8 legacy test.sh | **44 PASS / 0 FAIL** | 既存ignored compile-only RomRaider.jarを専用worktreeへコピー。GUI/ECUを動かしていない |

追加実行したspool322とx86 job内のx64 baselineは既存subsetの再試験なので合計へ二重加算しない。

- 3a最新PR CI [38496282039](https://github.com/zawa356/RomRaider_agent/actions/runs/38496282039)、main [38496452266](https://github.com/zawa356/RomRaider_agent/actions/runs/38496452266)：両OS適用steps成功。
- 3a-compat最新PR CI [38497003668](https://github.com/zawa356/RomRaider_agent/actions/runs/38497003668)、main [38497117325](https://github.com/zawa356/RomRaider_agent/actions/runs/38497117325)：x64/x86/Linux実steps成功。
- nativewriter最新PR CI [38497855408](https://github.com/zawa356/RomRaider_agent/actions/runs/38497855408)、main [38498047914](https://github.com/zawa356/RomRaider_agent/actions/runs/38498047914) **attempt2**：全3jobs/適用steps成功。Linux Windows-only native stepは意図したSKIPPED。
- nativewriter main attempt1はWindows SQLite依存JAR取得で両Maven endpointからHTTP404が8回発生しFAIL。SQLite/その後のnative stepはNOT RUN/SKIPPED。同一artifactはlocalHTTP200、再実行で取得と実試験が成功。失敗を消さずworklogへ保存。試験skipやversion変更で隠していない。
- Server同期PR42/43/44と各main CIのunit/compose/SQL round-trip実stepsを確認。最新Server main [38498052348](https://github.com/zawa356/RomRaiderServer/actions/runs/38498052348) success。failure-log captureだけ意図したSKIPPED。
- 今回のAgent→実FastAPI→PostgreSQL E2EはNOT RUN。Server compose CIのDB round-tripをAgent E2Eと呼ばない。既存Agent E2E secret不足のSKIPPEDも成功へ読み替えない。

## 設計上の到達点と未成立

A/Cを物理原本識別の単独方式として否決。Bのnative ID/read/write/flushのhandle bindingはNTFS/x64/x86のTEST candidateで成立した。旧writer→native/native→旧writer、split↔native、native同士の別JVM排他と、保持中のbounded native READを確認した。Linux同JVMの別anchor handle closeによるguard喪失の反例も保存した。

[D-041〜044](../08_decisions.md)と[3a比較](../phase_4b_2d_2b_3a_identity_comparison.md)、[compat](../phase_4b_2d_2b_3a_compatibility.md)、[writer binding](../phase_4b_2d_2b_3a_writer_binding.md)を参照。

残る技術ゲート：

1. **Windows directory同期UNCONFIRMED**。FlushFileBuffers成功やJVM haltはdirectory entryの物理電源断耐性を証明しない。
2. 新identity tag/versionを持つcross-platform marker候補は未実装。旧RLC1 fileKeyHashをnative IDへ読み替えない。旧Windows full56は引き続きBLOCKED。
3. native fixtureのexpected ID/prefixはメモリ。永続expected identity/再利用防止、高water/CAS/原文receipt同一SQLite transaction、source lane/claim競合は未実装。
4. 3c SQLite合成結合、3d seal/rotationと製品常時配線は安全ゲート未達でBLOCKED。原本/未ACK自動削除・retentionなし。
5. OS reboot/物理電源断/実ENOSPC/ReFS/network FS/volume clone/実車/X270はNOT RUN。

## 要判断事項

- native依存とwriter方式の製品採用、旧writer移行、新marker format互換・既存原本セット移設。今回の合成試験の成功だけで本番へ適用しない。
- Windowsのdirectory durabilityを満たす運用/技術方式。UNKNOWNをPASSへ昇格させない。
- 実X270/ECU/電源断などの実機試験と、本番配備・既存サービス操作。勤務中の無応答を承認にしていない。

## 環境と保全

CloudPC MCP接続成功。既存`jr2twt`権限/gh認証で専用cloneを利用し、local personal noreply作者でcommit/pushした。VPS clone：

- `/home/jr2twt/git/romlogger-day-3a-agent-20261014`
- `/home/jr2twt/git/romlogger-day-3a-compat-agent-20261014`
- `/home/jr2twt/git/romlogger-day-3a-writer-agent-20261014`（merged mainの最終VPS実試験）

Caddy/SSH active、mcp-ssh/ssh-mcp-chatgpt runningをread-onlyで確認。既存設定/認証/サービス/本番DB変更コマンドは実行していない。自身のJava子プロセスは終了し、jr2twtのprocess観測でJava残存なし。新しい常駐サービス/DBを起動していない。合成TEMPの部分/改変/未確定原本はレビュー証拠として保持し、既存ログ原本を削除していない。MCP API機能のE2E healthはNOT RUN。

## Gitのみで再開

最新コードcheckpointはAgent `126c174a93a6b8a1cf3aa14e90bd6c4902b94055`、Server `8804caac79bf887d69dc26087139a95bac500516`。この報告のdocs-only mergeでmain SHAは変わるので、上記を最新HEADと固定しない。正確なpublished HEAD/PR状態は次で再取得する。コードPhaseのopen PRは0件、最新文書は`docs/daytime-integrity-final`で保存。全履歴は追記worklogに残す。

```bash
git fetch origin
git status --porcelain=v1 --branch
gh pr list --state open
gh api repos/zawa356/RomRaider_agent/branches/main --jq .commit.sha
gh api repos/zawa356/RomRaiderServer/branches/main --jq .commit.sha
cat docs/handoff/codex_daytime_worklog.md
cat docs/phase_4b_2d_2b_3a_writer_binding.md
gh run view 38500236200 -R zawa356/RomRaider_agent --json headSha,conclusion,jobs
```

次の安全な開発は**別branchのPhase3b：新version/identity-tagのcross-platform watermark候補の設計と独立負試験**。まずWindows directory UNKNOWNを明示する契約、native/POSIX ID encoding、copy/古いmarker/退行/発行各段階の中断を設計する。3bのcross-OS厳格ゲートが成立するまで3c/3d/製品Workerへ進まない。新しい質問や本番適用を要せずtest-only設計・試験は続けられるが、本報告は今回完了した4小Phaseの実績を区切る。

## 11:03 JST時点の公開履歴（後続integrity修正前）

最終報告Agent [PR34](https://github.com/zawa356/RomRaider_agent/pull/34)、Server [PR45](https://github.com/zawa356/RomRaiderServer/pull/45)はMERGED。公開main Agent96a6afb／Servera65e219で、[Agent main38498916558](https://github.com/zawa356/RomRaider_agent/actions/runs/38498916558)のWindows/Linux/actualZulu8 x86全適用stepsと全jobs success、[Server main38498917172](https://github.com/zawa356/RomRaiderServer/actions/runs/38498917172)の適用steps successを確認した。最後のメタデータ追記は`docs/daytime-published-checkpoint`のみで、実行コード差分なし。最終HEADは上記再取得コマンドで確認する。**次は新しいPhase3bの設計/checkpointであり、完了したreport PRを再作成しない。**

## 最新integrity修正の最終確認 — 2026-10-14 11:23 JST

追加レビューでflush後のpayload+CRC同時改変がprivate TEST writerのmemory Proofへ紛れ込む不足を発見。RED213fd22/[CI38499434376](https://github.com/zawa356/RomRaider_agent/actions/runs/38499434376)でWindows17/x64のAssertionErrorを実再現（x86 REDはbaseline失敗でNOT RUN）。c72ad0dでWriteFileへ渡したframe exact bytesと読み戻しを比較し、Proof拒否・CRC-valid原本保持・terminal latchへ修正。GREENのx64 writer46/x86 writer47を実ログで確認した。

最新PR36 CI [38499916996](https://github.com/zawa356/RomRaider_agent/actions/runs/38499916996)、修正main [38500236200](https://github.com/zawa356/RomRaider_agent/actions/runs/38500236200)は全3jobs/適用実steps success。raw main jobs115645193558/115645193831でwriter46/47、actualZulu8 data-model32、比較25/split39/旧watermark安全拒否6を確認。Server46/main [38500240744](https://github.com/zawa356/RomRaiderServer/actions/runs/38500240744)適用steps success。最新合計はWindows17/x64596、x86subset156、Linux566。以前の594/154/writer44/45は追加負試験前の歴史的範囲。

追加VPS worktree `/home/jr2twt/git/romlogger-day-native-integrity-agent-20261014`のjournal/SQLite566 PASS。Native runtimeはWindows-onlyであり、Linuxでnative writer成功とは報告しない。コードPhaseのopen PRは0。この最終metadataのcommit/mergeでmain SHAは変わるが、実行コード差分はない。published HEADと文書PR状態は上記Gitコマンドで再取得し、最後のworklogを優先する。**次はPhase3bの新version marker設計・独立負試験。RLC1再解釈やdirectory UNKNOWNの成功扱い、未成立のSQLite/ACKへの配線は行わない。**
