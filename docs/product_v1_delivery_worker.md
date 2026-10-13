# Phase 2.1-D2b-1：製品v1の安全な送信ループ・バックオフ（明示起動）

## 今回追加した構成

従来RRBridge/PoCのLogger callback、`Stream.java`、旧TSV送信、操作コマンド、K-Line安全ガードは**変更しない**。

```text
RomRaider Logger (既存PoC) → 旧TSV queue / writer（既存・欠損リスク残る）
                                       ↓
    ProductBridgeWorker --foreground （別Javaプロセスで明示起動のみ）
            ↓ ProductLegacyScanner（最大64ソースを検査）
    IncrementalTsvBridge（1ソース最大256完結行）
            ↓ SQLite v1_records / v1_import_cursors
    ProductDeliveryCoordinator（同一SQLite v1_delivery_state）
            ↓ 期限内のみ1回のHTTP
    ProductSender → HTTPS → Server v1（完全commit ACK）
            ↓
    SQLite ACK ledger（元の未ACK原本は削除しない）
```

- `ProductDeliveryCoordinator`：通信エラーの再試行回数、次回試行時刻、`NEEDS_REVIEW`停止状態をSQLiteに保存。再起動・外部HTTP障害でも待機を維持し、未ACK原本を破棄しない
- `ProductBridgeWorker`：`--once`または`--foreground`で**手動／外部管理プロセスから明示起動**する。RomRaider JVM内のLoggerイベントをブロックせず、単体Javaプロセスでスプール監視
- `ProductLegacyScanner`：既読TSVの消失・ファイル改変、symlink、監視対象64ファイル超を検出した場合には**例外停止**。生データを飛ばして次のファイルへ進まない
- `*.worker.lock`をOSプロセス間排他に使い、同一DBの製品ワーカーを二重起動しない。旧PoCのwriter/CSV senderの排他までは保証しない
- Workerが自動的にECU操作やProfile切替を行う機能はない。別プロセス用Windowsサービスの自動起動設定もこの段階では実装しない
- 既存の明示用`ProductBridgeOnce`も**共通の永続再試行・要調査停止ゲート経由**へ変更し、通常CLIから停止状態を迂回して再送できないようにした

## 再試行と停止の意味

| 状態 | ローカル動作 | データ保護 |
| --- | --- | --- |
| `PROFILE_ACKED` / `BATCH_ACKED` | 待機を解除。次の呼出しで別バッチへ進める | 実Server確定ACK済みのレコードのみSQLite受領済みにする |
| `IDLE` | 次の確認まで2秒待機 | 元データを変更しない |
| `RETRY_LATER` | 2秒、4秒、8秒…最大120秒の指数バックオフ。回数／期限をSQLiteに保存 | 未ACK行、ID、原文JSONを保持 |
| `NEEDS_REVIEW` | 401/403/409/413/422等で永続停止。再起動後も送信しない | 原本を保持。資格情報や契約違反の修復を人が判断 |
| その他のSQLite／予期しない例外 | 呼出元へ例外を返し、ワーカーは停止する | 正常ACKに偽装せず、エラーを黙殺しない |

現段階では`Retry-After`・再試行へのjitterは未対応。413時の分割／409・422時の行単位隔離も未対応で、自動再試行しない。再試行が必要な要調査停止を解除する`resetAfterOperatorReview()` APIはあるが、**車載で自由に解除できるUIや遠隔AI操作ツールには公開しない**。

`ProductSender.pumpOnce()`およびワーカーの送信処理は、1回のループで最大1 HTTP要求。ネットワーク障害で延期されていても、**新規TSVのSQLiteへの取り込みは継続**する。DB保存に失敗した場合は例外停止し、元TSVを勝手に削除しない。

## 検証用コマンド

Java8互換ソースをローカルでビルドした後、別プロセスで明示実行する。

```bash
# 実際には秘密情報の平文コマンド引数やGitリポジトリへの保存は禁止
export ROMLOGGER_DEVICE_ID="<device-uuid>"
export ROMLOGGER_VEHICLE_ID="<vehicle-uuid>"
export ROMLOGGER_DEVICE_TOKEN="<secret-token>"

java -cp "build/product-v1-test:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
  net.neristal.rrbridge.product.v1.ProductBridgeWorker \
  --once ./spool ./product-outbox.sqlite https://example.invalid

# こちらは自動起動設定ではなく、明示的に実行した時のみループ
# ProductBridgeWorker --foreground ./spool ./product-outbox.sqlite https://...
```

Windowsのクラスパス区切りは`;`。上記は開発用コマンド例で、X270のJava8 32-bit・ファイル共有競合・サービス常駐は未確認。

## 既存PoCに残っている重要な欠損リスク（コード確認済み）

1. `Stream.offer()`は上限付き`queue.offer()`。満杯の場合`dropped`を加算するが、当該ログ行はTSVにも残らない。既存`statusJson()`の`linesDropped`で累積件数は確認可能。ただし今の製品ワーカーはこの値を取得・強制停止しない。
2. `Stream.writerLoop()`は`queue.take()`と`drainTo()`で**書き込み成功前に行をキューから取り出す**。その後のTSV書き込み／closeに失敗した場合、バッファを再キューしないため、ロガーの観測値が失われ得る。エラーは`Agent.log`に出るが製品Serverに欠番eventとして送る処理は未実装。
3. `cleanupSpool()`は旧Receiverの`.sent`完了・保存日数で元TSVを削除する。製品v1のSQLite/Server ACKとは**独立**しており、製品ワーカーが取り込む前に削除される可能性がある。新規ワーカーの「取り込み済みソース消失検出」は、**一度も見ていないソースの削除までは防げない**。
4. `senderLoop()`は旧TSVを別サービスへ送信するため、製品v1の完全commit ACKとは別扱い。旧経路を安全に廃止／維持する移行ルールが必要。

現状のD2b-1はこれらを**検出・解消するための土台**であり、ECU→DBの無欠損を保証しない。次のD2b-2では上流のログ収集・ローカル永続化境界と旧TSV削除条件を優先する。

## CI判定と残課題

- Windows/Linux GitHub ActionsでJDK17の`--release 8`、実SQLite永続状態、合成HTTPモック、増分TSV、別JVM`--once`実行をテスト
- 再起動後のbackoff維持、認証失敗等の恒久停止、正しいACKによる解除、未ACK原文維持、TSV消失時の停止、シングルコマンド実行を検証する
- 実Server E2EのServer側pinをこのD2b-1コードに更新していない。Server側で合格済みのD2a試験は旧バージョンに限定される。新Workerの実Server E2Eも未検証
- Windows実車PC／電源断／disk-full／sqlite破損／長時間走行／64ファイル超の実負荷／Windowsサービス自動起動／外部TLS経路は未検証

[実装台帳](product_v1_implementation_progress.md)にPR/commit/CIの証跡を記録する。
