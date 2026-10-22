# Phase X-B 統合報告 — Phase V〜X-A の main 統合準備（Claude Code, 2026-10-22 JST）

併読：[検証証跡](phase_xb_integration_evidence.md) · [追記式作業ログ](phase_xb_integration_worklog.md) ·
[RLDI1](../interfaces/distribution_inventory_v1.md)（X-B 追記） · [Phase X-A報告](phase_xa_ci_stability_report.md)。

**このフェーズでは main へのマージ・Ready化・既存PRの変更・rebase/force push・タグ/リリース/デプロイを一切行っていない。**

## 1. 結果の要約

| 項目 | 結果 |
|---|---|
| Agent統合Draft PR | [#105](https://github.com/zawa356/RomRaider_agent/pull/105)：base=`main`（`4f80c5e`）、head=`integration/phase-v-xa-to-main`。コード・試験の最終 head は `7055e2e`。以降のコミットは文書のみ（証跡ファイル参照） |
| 既存PRの網羅 | #80〜#104 の25本すべての head が統合ブランチの祖先（25/25、`git merge-base --is-ancestor` で確認） |
| 履歴 | squash・rebase・cherry-pick なし。`main..7055e2e` は83コミット＝#104 の72＋回収4＋マージ2＋X-B 3＋文書1＋診断試験修正1。元のSHAはすべて保持 |
| スタックの非線形 | #92 は `d93a975` 起点、#93 は `2ccae84` 起点だったため、docs-only の4コミットが #104 に含まれていなかった：#91 の `e4dc146`・`01cd886`・`71f72c5`、#92 の `ae5ff53`。owner判断Aにより非fast-forwardマージ2回（`4cb627d`、`7010e37`）で回収。競合は AGENTS.md の1行だけ。新しい側のエントリはすべて残し、Phase VII 行は #91 の正確な版を採用。何も削除していない |
| 製品コード | `e8e2c54` との差分のうち製品コードは `DistributionInventory.java`（ZIP修正）のみ。その他は docs、テスト2（X-B 新規、X-A ドライブ診断の WOW64 TIMEOUT 処理）、スクリプト1、CIドライバ1行 |
| ZIP全エントリ検証 | RED（`2c8a5a3`）：非クラスエントリの不正12件がすべて MATCHED → 修正（`dae1848`）で全件停止。実依存 sqlite-jdbc・slf4j・JNA と JDK 製 JAR は互換 |
| CI | `fb9fd56`：13/13 success。`15e4ebb`：phase8 windows の診断 step で **1件失敗**。試験専用の WOW64 PowerShell 参照呼び出しが 30 s 上限を超えたもので、製品・X-B とは無関係。記録を残したうえで `7055e2e` で試験側のみ修正した。**最終 `7055e2e`：4ワークフロー・13ジョブすべて attempt 1 で success**、失敗 step なし。merge ref の tree は head の tree と一致（`118cca1d…`） |
| Server | Draft [RomRaiderServer#66](https://github.com/zawa356/RomRaiderServer/pull/66)：#62〜#65 の7コミットを SHA のまま内包し、状況説明の docs 1コミットを追加。変更は `docs/implementation_progress.md` のみ。PoC・Product v1 contract・固定旧Agent E2E がすべて success |

## 2. Phase X-B の ZIP/JAR 強化（owner 判断：全エントリ方式）

- **変更前：** CRC検査と展開検査は manifest と通常の `.class` だけが対象だった。リソース、`META-INF/services`、ネイティブ DLL/.so、multi-release クラス、ディレクトリエントリは、ヘッダーの整合性しか見ていなかった。
- **変更後：** すべてのエントリを、固定 64 KiB バッファでストリーミング検証する（全内容をメモリに保持しない）。
  - STORED は「圧縮サイズ＝宣言サイズ」であること。
  - DEFLATE は宣言サイズちょうどでストリームが終わり、余剰入力がないこと。展開は「宣言サイズ＋1バイト」で打ち切る。
  - CRC-32 が一致すること。ディレクトリエントリは空であること。
  - 展開量の上限は、エントリ単位 16 MiB（`JAR_ENTRY_EXPANSION_BOUND`）、JAR 合計 128 MiB（`JAR_TOTAL_EXPANSION_BOUND`）。宣言値を展開前に検査する。
  - 実依存の最大値は、エントリ単位 1.3 MB、JAR 合計 25.7 MB（sqlite-jdbc）で、上限には約5倍の余裕がある。
- **負試験：** 次の12件を `JAR_UNREADABLE` またはバウンド拒否で停止することを確認。
  - CRC 不一致のリソース、CRC 不一致の service ファイル
  - DEFLATE 破損の DLL、内容と CRC が合わない STORED DLL
  - 宣言より長い／終端なし／末尾ゴミ付き／宣言より短い DEFLATE
  - データ付きのディレクトリエントリ
  - 上限超過の単一エントリ、合計上限超過
  - 宣言を偽るボム（10 MiB を 1 KiB と宣言。ヒープ増加は約 3 MiB）
- **互換性：** 実 JAR 4本と JDK 製 JAR は、全エントリが検証を通る。
- **範囲：** RLDI2 の許可リストや本番許可には踏み込んでいない。F-IX-1（第三者 main による MISMATCH）は変わらない。

## 3. 安全性ゲート（不変）

- synthetic_only、launch/production/ACK 無効、`formal_ack=DISABLED`、`--production-live` は常に拒否、`reader_adoption=BLOCKED`、WRITER_UNPROVEN、Windows ACL は UNKNOWN、global old-binary exclusion は NOT_PROVEN。
- ECU・X270・RomRaider 本体・本番 VPS/DB/MCP には一切触れていない。
- 実 Server E2E は Server `d8c7a2f` を固定し、使い捨ての PostgreSQL16 で実行している。

## 4. マージ判断表（owner の承認待ち）

| 順 | 対象 | 方式 | マージ前に確認すること | マージ後に確認すること |
|---:|---|---|---|---|
| 1 | Agent [#105](https://github.com/zawa356/RomRaider_agent/pull/105) | **merge commit のみ**（squash/rebase 不可）。Draft→Ready は owner が実施 | 最終 head の4ワークフロー13ジョブが success であること（証跡ファイルの最終節）。merge ref の tree＝head の tree。main が `4f80c5e` のまま（変わっていたら再検証） | main の tree＝#105 head の tree、#80〜#104 の25 head が main の祖先、push トリガの Actions の結果 |
| 2 | 旧 #80〜#104 | 操作しない | — | GitHub が自動で merged にした PR は再操作しない。open のまま残った PR があれば一覧化し、cross-reference コメント案を owner に提示。ブランチは削除しない |
| 3 | Server [#66](https://github.com/zawa356/RomRaiderServer/pull/66) | merge commit | Agent のマージ後に、2026-10-22 節を実際の統合結果へ更新し、CI を再実行 | Server main の CI。旧 #62〜#65 の扱いは2と同様 |
| 4 | Phase X-C | — | RLDI2/F-IX-1、Windows ACL、FILE_SHARE_DELETE、JNA 配布ライセンスの owner 判断 | — |

**リスク・注意：**
- 両リポジトリとも public で、main に保護ルールがない。そのため誤ったボタン操作（特に squash）を防ぐ仕組みがない。マージは owner 本人が merge commit を選んで実行すること。
- 中間 head の古い CI 失敗（`DRIVE_TYPE_UNPROVEN`、RLDI1 の temp 書込み、OUT_DIR）は後続コミットで修正済み。だから個別 PR の逐次マージは推奨しない。

## 5. 残課題（owner 判断・未実施）

- owner 判断：RLDI2/F-IX-1、JNA ライセンス、FILE_SHARE_DELETE、Windows ACL の合格条件。
- 未実施：Linux／Windows 上の owned HTTP35・10k 負荷の再実行（CI は REAL Server E2E のみ）、Phase V/VI のローカル回帰、8.3 短縮パスの fixture、NTFS 以外のボリューム。
- 提案：Maven 429 や pip IncompleteRead への対策として、依存ハッシュを検証しつつキャッシュを使う CI 改善（マージ前スコープ外）。
- 開発 VM の `%TEMP%\romlogger-*`（所有者不明）は削除していない。
