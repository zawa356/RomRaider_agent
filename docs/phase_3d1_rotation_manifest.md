# Phase 3d-1 — journal seal / RLM1 rotation manifest 独立PoC

対象: `src/main/.../prototype/ingress/RotationManifestPrototype.java`、test `RotationScenarios` /
`PosixRotationTest` / `WindowsRotationTest`（`scripts/test-watermark-ledger.sh`で実行）。
製品writer・Logger・Worker・spool・ACKへは**配線しない**。原本の削除・切詰め・修復は一切しない。

## 構成

1 session = 1 directory。中身は次のとおり。

- segment ファイル `segment-0001.rlj` … `segment-0016.rlj`。各segmentはそれぞれのRLI1 UUID・RLC2 markerを持つ。
- `rotation.manifest`（RLM1）

RLM1 のレイアウト（big-endian、SHA-256 digest付き。digestは**認証ではない**）:

- header: magic `RLM1`、version 1、session UUID、generation、sealed数（≤16）、active有無
- sealed entry（102 bytes）: index、原本UUID、identity tag/長さ/値、最終sequence、最終offset、最終prefix SHA-256
- active entry（54 bytes）: index、原本UUID、identity

## 規約

- `begin`: 空のdirectoryでのみ開始する。segment-0001をCREATE_NEWで作り、RLI1を付け、manifest gen1（active=1）を書く。
- `seal`: activeのRLC2 markerが**segment全長**を確定しており（handle長 == marker offset）、identityとUUIDが一致することを要求する。
  空segment（seq0）はsealしない。manifestはtmp CREATE_NEW → force → 原子的置換で更新する。
- `startNext`: activeがないこと、sealed数が16未満であることを要求する。新segmentをCREATE_NEWで作り、新UUIDを付けて登録する。
  上限到達時は**明示的に停止**し、削除やretentionは行わない。
- `verify`: 次の場合はすべてfail closedにする。
  - sealed segmentのRLC2・identity・長さがmanifestと一致しない（seal後の追記を含む）
  - active segmentのidentity・UUIDが一致しない
  - manifestにないsegment（orphan）がある
  - 順序の入替え、UUID・identityの重複、部分manifest、tmpの残存
  - previous manifestに対する世代の巻戻り、sealed entryの書換え、active segmentの差替え
- `ingestSession`: verifyの後、3c-1 ledgerへmanifest順にingestする。各sealed segmentは、ledgerのhighwaterが最終seq・offsetに達していなければ次へ進まない。

## 試験（合成TEMP、Windows x64 43 / x86 44 / Linux 43）

- 3 segmentのrotation（gen5）。各segmentのUUIDとidentityが異なることを確認する。
- session ingestで5件を1回だけ記録する（再実行は0件）。
- 未publishのtailがあるとseal拒否、active中のstartNextも拒否する。seal後の追記を検出する。
- manifestの巻戻り、digest再計算済みのsealed entry改変（history・segment markerの両方で）、順序入替え、部分manifestを拒否する。
- orphan segment、active segmentのUUID sidecarコピー、sealed segmentの欠落を拒否する。
- 16 segment到達後の17本目を拒否する。16本すべてが残り、何も削除しない。
- 空segmentは、未publish・publish済み（seq0）のどちらもsealしない。空でないdirectoryでのbeginは拒否する。
- seal・startNextの各段階（SEGMENT_CREATED / TEMP_SYNCED / REPLACED）で、同一JVMのIOException注入と別JVMの`Runtime.halt`を行う。
  - SEGMENT_CREATED: 新segmentはorphanになり（自動採用しない）、retryはCREATE_NEWで止まる。
  - TEMP_SYNCED: tmpを保持し、verifyは拒否する。
  - REPLACED: 新manifestが可視になる（呼出側から見ると結果は曖昧）。

## 残るゲート

- Windows directory durabilityはUNKNOWNのまま（manifestにも同じ分類を適用）。OS再起動・物理電源断・実ENOSPCはNOT RUN。
- orphan・tmp残存・CREATE_NEW競合はreview待ちで止まる。運用上の解消手順（Supervisorが判断して通知する）は未設計。
- 製品の`v1_source_claims`・正式sessionやrun IDとの対応、旧TSVからの移行、session境界のServer契約は未設計。
- digestは認証ではない。manifestとsegmentを一式で書き換えられる管理者には耐えない。
