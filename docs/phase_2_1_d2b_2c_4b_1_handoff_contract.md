# Phase 2.1-D2b-2c-4b-1：独立Handoffとジャーナル再生契約

> **PoC専用／未配備。** `Stream.java`、X270のRomRaider Logger callback、旧TSV writer、製品v1`DurableSpool`への接続は一切行っていない。

## 位置付けと責任境界

```text
[既存の実装（不変）]
RomRaider callback -> bounded memory queue -> legacy TSV writer -> IncrementalTsvBridge -> v1 SQLite outbox -> Server

[今回の独立PoC（新設・未接続）]
test producer --[offer: volatile only]--> bounded ArrayBlockingQueue
                                                 |
                            isolated worker: drainOne() [explicit invocation]
                                                 |
                                      IngressJournalPrototype.append()
                                      (one record + fsync + CRC/seq)
                                                 |
                              replayTo(sourceUuid, sink, afterSequence, limit)
                                                 |
                              idempotent mock sink in synthetic tests
                              NOT the product v1 SQLite outbox
```

- `offer()` はディスク書込・通信・sleep・ジャーナルロック待ちを行わず、`ACCEPTED_VOLATILE`、`REJECTED_FULL`、`REJECTED_HALTED`、`REJECTED_INVALID`のいずれかを返す。**受理は永続化成功を意味しない**。`ArrayBlockingQueue`自身の短時間の内部ロック／GC影響は残るため、リアルタイム性能の保証ではない。
- `drainOne()` は専用workerからのみ呼び、成功したジャーナルfsyncの後に行を解放する。失敗した行は`inFlight`に保持し、状態を`halted`へ遷移。メモリの最大保留行数は`queue capacity+1 inFlight`。
- 復帰は`resumeAfterReview()`で明示する。全ジャーナル再検査成功後にのみ通常のI/O失敗から復帰する。quota超過は`quotaTerminal`でラッチし、同じファイルを切り詰めたり上書きしたりして再開しない。
- `replayTo()` は既存ジャーナルを削除せず、`sourceUuid+sequence`から**安定したレコードUUID**を生成する。`afterSequence`は、下流が**実際に永続commit済みと確認した場合だけ**増やせる。失敗時は先頭からの再再生と冪等な重複排除が前提。
- 新しい`IngressHandoffPrototype.IdempotentSink`は**インターフェース規約**に留まり、製品v1の`DurableSpool.appendRecord`やprofile・run変換・SQLite cursorへは接続していない。合成試験ではメモリ上のMapで重複排除を模擬しており、**製品SQLiteでのend-to-end重複回避を実証したわけではない**。
- `sourceUuid` はjournalファイルと同じ寿命で永続的に管理する外部IDが必要。ファイルを別名へコピーしたりIDを変更して再生した場合、同一原本の異なるレコードIDが生じ得る。現在のジャーナルフォーマット自体にsourceUuidは記録していないので、次Phaseで必ず仕様化する。
- メモリの`accepted`、`writerFailures`、`rejected*`とin-flight原本は再起動時には戻らない。`offer()`の直後・writer fsync前に落ちれば、その行は喪失する。**無欠損保証・耐電源断保証は未達**。

## 異常時の正しい意味

| 故障 | 今回の独立PoC |
| --- | --- |
| writerがI/O待ち | callback相当のofferは有限queueがある限り即時に返る。受理はvolatile |
| queue満杯 | `REJECTED_FULL`、reject数増加。実計測を自動停止・再設定しない |
| journalのIOException | in-flightを保持し、haltして後続offerを拒否。明示的なreview後にretry |
| journalのquota上限 | `quotaTerminal`。同一journalの自動resumeは拒否。元ファイルを消去しない |
| journal fsync成功・下流失敗 | journalは残り、安定IDで再送できる。真の重複排除はSQLite接続後の別試験が必要 |
| JVM異常終了前のvolatile queue | **喪失可能**。既存ジャーナルに書き終えた分のみ復旧可能 |
| 破損したjournal | 4aのCRC/seq/UTF-8/ヘッダ検証でfail closed。人手の保全・調査を待つ |

## 合成試験と安全境界

`bash scripts/test-ingress-journal-prototype.sh`を実行するとJava17の`javac --release 8`でJava8互換のソースをコンパイルし、4a journal試験34件と4b-1 handoff試験41件を実施する。専用CloudPCの隔離cloneで計75件PASS。

4b-1は`CountDownLatch`でwriterを停止したままproducerに投入し、キュー満杯拒否、故障後のin-flight保持、明示復帰、正確な再生ID、同一IDの繰返し再生、模擬sink側commit後の例外、journal容量上限での終端停止、再起動前volatile行が失われることを合成検証。OSの実disk-full、車両、X270、電源断、Zulu8 32bit、Server E2Eは未実施。

## 次の小Phase（D2b-2c-4b-2）

1. ジャーナル自体へ改変困難な`sourceUuid`を作成時に永続化し、既存ファイルの再開・ローテーション・別ファイルのID衝突を設計する。
2. **既存の`IncrementalTsvBridge`のmetadata/session/profileルールを維持した形で**journalから製品SQLiteへ原本を変換し、durable record commit後にのみ永続cursorを進める。レコードID衝突時はfail closed。TSV経路との二重投入・二重ACK・回復時の競合を試験する。
3. callbackでの`offer`拒否やquota終端をSupervisorへ**監視通知する仕様**を設計。走行中の自動操作・ECU通信変更は行わない。
4. Windows 32bit・JVMメモリ・I/Oレイテンシ・電源断後の安全復帰をX270で別途検証するまで製品版へ配線しない。

**このPhaseでの判定：Handoff/リプレイ契約のPoCは成立。下流SQLiteの実永続性を含む全経路は未実装・未検証。**
