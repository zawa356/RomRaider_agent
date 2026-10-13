# Phase 2.1-D2b-2c-4a：独立した永続入口ジャーナルの検証

## スコープと重要な制限

2026-10-13現在、このJavaクラスは **独立した設計検証用PoC** であり、本製品のデータ取得経路・既存TSV writer・製品v1 SQLite outbox・Server・実車には**一切組み込まれていない**。

`IngressJournalPrototype.append()` は**レコードごとに同期fsyncを呼ぶ**。したがって、RomRaiderの `DataUpdateHandler.handleDataUpdate()` やECU通信に関連するスレッドから、直接・間接を問わず呼び出してはいけない。自動起動・設定の切替・本番デプロイも行わない。

現在の取得経路は以前のまま：`Logger callback → memory bounded queue (最大200000行、S行soft limit 195904) → TSV writer → 増分取込Worker → 製品SQLite outbox → Server`。試作ジャーナルを加える配線は未実装。

## 実装の概要

| 項目 | 試作仕様 |
| --- | --- |
| Java | Java 8互換（CIではJDK17 `--release 8`、Zulu 8 32bit実機試験なし） |
| 実装 | `src/main/java/net/neristal/rrbridge/prototype/ingress/IngressJournalPrototype.java` |
| 形式 | 1フレーム = magic 4B + length 4B + sequence 8B + CRC32 4B + UTF-8 TSV 1行 |
| ID・順序 | ジャーナルの追記位置`offset`と1から増加する`sequence`を再読込時にも維持。ファイル交換・切詰め・整理・削除は禁止 |
| 追記の確定 | payloadの書込後`FileDescriptor.sync()`が成功した場合のみ`append()`成功 |
| 排他 | 同一ファイルに対するOS file lockを取得。二重writerを拒否 |
| 容量 | 試作のみ最大8MiB、1行のUTF-8原文最大128KiB。容量超過は明示例外、既存データを削除しない |
| CRC | 偶発的なpayload改変をCRC32で検知。改ざん防止・暗号学的完全性は提供しない |
| 破損時 | ヘッダ断片・payload不足・CRC異常・sequence不一致・不正UTF-8・行区切り混入を検出し、**自動修復せずfail closed** |
| 再起動 | ファイルの先頭から順序と整合性を検査し、既にfsyncされたレコードを再生 |
| 再送契約 | 呼出側は安定した`sequence`/`offset`等から重複抑止する必要がある。ジャーナル自体は送信ACKや取込カーソルを持たず、何も自動削除しない |

### 容量制限時の動作

`QuotaExceededException`は**原本の保存を約束できなかった**ことを表し、新たな行を受け付けない。既に受け付けたレコードは保持する。容量不足のまま計測を続ければ取得元でデータが失われる可能性がある。将来の設計では、メモリqueue残量・予約枠・ディスク容量・監視フラグを監督する独立Supervisorの判断と、人間が承認した安全な収録停止動作が必要となる。自動でECUプロファイルを切り替えたり、運転中の車両を制御するものではない。

### 異常終了と破損の区別

- **fsync済みのappendの直後にJVMを`Runtime.halt(73)`で強制終了**した合成試験では、別JVMの再起動後に当該行を再読込できることを確認。
- レコードの途中まで書かれた状態は、起動時の走査で不正なtailとして検知。**自動で切り詰めず元ファイルを維持する**。明示したバックアップ・検証・人手の判断を経ない復旧は実装しない。
- **物理的な電源断、書込キャッシュ喪失、ストレージ故障、親ディレクトリの永続化、Windows x86/NTFS、途中fsync失敗、停電直後のOSの整合性**までは証明していない。
- 失敗後にファイルに「完全でCRCも正しいがfsync成功応答前だった」行が残る可能性はある。その場合は重複再生を許容する受信側の冪等性で対応する必要がある。無欠損・exactly-onceの意味にはならない。

## 評価・試験

- `bash scripts/test-ingress-journal-prototype.sh`：ECU・RomRaider本体・DB資格情報不要。
- 専用CloudPC `jr2twt`の隔離cloneでJava17によるJava8互換コンパイルと34項目の合成試験成功。
- GitHub Actionsの独立workflow `ingress-journal-prototype-ci.yml` でUbuntuとWindowsを試験。[run #38373518940](https://github.com/zawa356/RomRaider_agent/actions/runs/38373518940) の両OSジョブ／実テストステップPASS。
- 試験対象：日本語原文、追記順序／offset、再起動、同時writer拒否、quota、フレーム途中切断、CRC不一致、sequence不一致、不正UTF-8、symlink、異常終了した別JVMからの再読込。
- **実ホストのdisk-fullやOOMは再現していない**。Caddy/MCPのサービス・構成にも影響を与えない。

## 次段階に進むためのゲート

1. 既存の製品v1 SQLite outboxと新しい前段journalの責任分界を確定する。二重保存コストと、ジャーナルから元TSVへの変換／永続ACK・カーソル管理を比較する。
2. logger callbackをブロックしない専用writerへの受渡しで、容量逼迫時・異常停止時の保護範囲と捨てる条件を決める。**writerを停止する時間に上限がない場合、有限RAMとの両立で必ず欠損が発生し得る**ことを明記する。
3. ファイル末尾が破損した場合のバックアップ・運用者による明示解除・追記再開手順を設計する。誤って原本を捨てない。
4. 合成負荷・メモリ使用量・32bit JVM・Windowsのファイルロックとfd.sync・ディスク逼迫／クラッシュの境界を試験。OSの物理電源断については安全な専用テスト設備と事前承認が必要。
5. 製品v1の実Server E2E（現時点では最新Agent main版が未実行）を別の検証ゲートとし、今回のジャーナルPoCのCI成功と混同しない。

**今回の判定：独立ジャーナルの成立性のみPASS。製品版への接続・耐久保証・実車投入は保留。**
