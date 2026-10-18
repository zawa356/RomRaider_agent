# Phase 3c-1 — RLC2 永続highwater ledger 独立PoC（実SQLite）

対象: `src/main/.../prototype/ingress/WatermarkLedgerPrototype.java`、test `WatermarkLedgerScenarios` /
`PosixWatermarkLedgerTest` / `WindowsWatermarkLedgerTest`、`scripts/test-watermark-ledger.sh`。
製品spool（`v1_*`）、Server ACK、Worker、旧TSV経路は**変更も配線もしない**。

## 目的

Phase 3b（[RLC2](phase_3b_rlc2_watermark.md)）で残した2つの反例を、永続ledgerで閉じる。

1. previousを持たない新readerが古い（巻き戻された）markerを受理する。
2. コピーへidentityを向け直し、digestを再計算した偽造markerを受理する。

## 設計

独立SQLite DB（WAL、`synchronous=FULL`、`foreign_keys=ON`）に次の表を持つ。

| 表 | 内容 |
| --- | --- |
| `proto_rlc2_sources` | 原本UUID → claim時のcanonical path・identity tag・identity値（expected identity） |
| `proto_rlc2_highwater` | 原本UUID → generation・sequence・offset・prefix SHA-256 |
| `proto_rlc2_receipts` | 原文frame（seq・決定的record_id UNIQUE・frame offset・body SHA-256・body） |
| `proto_lane_claims` | path → `tsv` / `journal`（製品のlane排他の試作代替） |

`ingest()`は1つの`BEGIN IMMEDIATE`トランザクションで処理する。

1. pathに`tsv`のlane claimがあれば拒否する。
2. UUIDのclaimがあれば、別pathは拒否する（コピー・移動）。
3. 永続highwaterを`previouslyObserved`として`readCommitted`を呼ぶ。stale・巻戻り・既読prefixの改変はここで拒否される。
4. markerのidentityがexpected identityと一致することを要求する。
5. 初回だけ、source claimとjournal lane claimを作る。
6. 既存receiptを確定prefixと完全照合し、改竄を検出する。
7. 新frameのreceiptを挿入する。
8. receipt数・最大seqがmarkerのsequenceと一致することを確認する。
9. highwaterを更新する → commit。

途中で失敗すればrollbackし、claim・receipt・highwaterのいずれも残さない。

## 試験（合成TEMP、Windows x64 27 / x86 28、Linux CIで判定）

- 初回claimと2件記録、再ingestは0件（重複なし）、gen2は1件だけ追加。
- **反例1の解消:** 別プロセス（新しいledgerインスタンス）でgen1 markerへ巻き戻すと、永続highwaterで拒否され、ledgerは不変。
- **反例2の解消:** 偽造コピーはstateless readerでは依然受理される（再現）。ledgerでは次の2通りとも拒否する。
  - 別pathへ置いた場合：UUID claimが別pathにある。
  - claim済みpathへ差し替えた場合：expected identityが異なる。
  元の原本へ戻すと一致する。
- TSV lane claim下では拒否し、receiptを作らない。
- ledger内receiptの改竄は次回ingestで検出し、highwaterを進めない。
- markerを最初から作り直してgen1になった場合、永続gen2に対して拒否する。
- commit前のIOException注入ではclaim・receipt・highwaterをすべてrollbackし、retryで1回だけ記録する。
- 別JVMの`Runtime.halt`をcommit前・commit後に注入する。前ならledgerは空で再起動時に記録され、後ならcommit済みで再起動しても重複しない。
- 2つのJVMが同時にingestしても、片方だけが3件を記録し、もう片方は0件。frameごとにreceiptは正確に1件。

## 範囲外・残るゲート

- **ACKとの連動は行わない。** receiptは常にACK対象外。Windowsのdirectory durabilityはUNKNOWNのまま。
- 初回claimはTOFU（最初に観測したidentityを信頼する）。初回より前に置換されていた場合は検出できない。
- 管理者がledger DB自体を書き換える攻撃には耐えない（DBは認証されていない）。
- 製品の`v1_source_claims` / `v1_journal_import_cursors`への統合、session/source継続、rotation（3d）は未実装。
- OS再起動・物理電源断・実ENOSPCはNOT RUN。`Runtime.halt`は物理電源断ではない。

```bash
bash scripts/test-product-v1-spool.sh           # build-only jars（sqlite-jdbc / slf4j）
bash scripts/test-windows-native-identity.sh    # Windows: JNA（test-only）
bash scripts/test-watermark-ledger.sh           # JDK17でcompileして実行
ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-watermark-ledger.sh --run-only   # Zulu8 x86
```
