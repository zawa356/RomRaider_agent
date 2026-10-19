# RomLogger 製品版 Windows Agent 実装台帳

**正本:** サーバー全体の[実装進捗](https://github.com/zawa356/RomRaiderServer/blob/main/docs/implementation_progress.md)と[製品設計](https://github.com/zawa356/RomRaiderServer/blob/main/docs/agent_detailed_design.md)。本台帳はこのリポジトリのAgent実装差分・CI・残課題を追跡する。

## 運用規約

- 小PhaseごとにPRを分け、コード、回帰試験、CI、履歴をそれぞれコミットして履歴を残す
- GitHub ActionsをLinuxとWindowsで実行し、失敗時は原因・修正コミットをこの履歴へ追記する
- CIのJava 8対応コンパイルは**実車WindowsのZulu 8 32-bitの実行確認ではない**
- 実ECU接続・走行中操作・未ACK破棄・本番展開を単体テストの成功から推定しない
- 現行PoCと製品v1の接続契約は異なる。**既存のTSV送信やRomRaider操作を勝手に置換しない**

## フェーズ一覧

| Phase | 対象 | 状態 | 記録 |
| --- | --- | --- | --- |
| 2.1-A | Java 8対応SQLite durable spool、原本・定義とACK台帳 | **CI合格・PR #3** | [PR #3](https://github.com/zawa356/RomRaider_agent/pull/3)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-B | Server v1プロファイル／バッチのHTTP送信・ACK厳密検証（契約モックCI） | **合成CI完了・PR #4** | [PR #4](https://github.com/zawa356/RomRaider_agent/pull/4)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-C1 | 既存RRBridge TSV→製品v1 SQLiteの非破壊取込・合成CI | **CI合格、PR #5** | [PR #5](https://github.com/zawa356/RomRaider_agent/pull/5)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-C2a | Agent固定版（JavaソースSHA照合）→実FastAPI/Postgresの合成E2E | **PASS（Server CI、Agent commit 6dd46835固定）** | [Server PR #22](https://github.com/zawa356/RomRaiderServer/pull/22)／[E2E CI](https://github.com/zawa356/RomRaiderServer/actions/runs/38346271102) |
| 2.1-C2b | Agent最新main自動同期E2E／常駐取り込み接続 | **未実装**（本Agent repoのPrivate checkout専用E2EはtokenなしでNOT RUN） | [E2E run](https://github.com/zawa356/RomRaider_agent/actions/runs/38339215160) |
| 2.1-D1 | TSV増分取り込み、SQLite永続offset＋安全再開・障害注入 | **CI合格・Agent PR #8** | [PR #8](https://github.com/zawa356/RomRaider_agent/pull/8) |
| 2.1-D2a | Agent増分取り込み版の実Server E2E再pin・再起動試験 | **実FastAPI/PostgreSQL E2E PASS・Server PR #24** | [Server PR #24](https://github.com/zawa356/RomRaiderServer/pull/24)／[E2E CI](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030034) |
| 2.1-D2b-1 | 明示起動の独立Worker、SQLite永続backoff／要調査ゲート、旧TSV監視 | **Windows/Linux各208件PASS・PR #11** | [PR #11](https://github.com/zawa356/RomRaider_agent/pull/11)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-D2b-2a | 旧PoC writer復旧・queue欠損イベント・TSV削除禁止 | **合成CI成功・PR #12** | [Agent PR #12](https://github.com/zawa356/RomRaider_agent/pull/12)／[Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-D2b-2b | fresh/backlog公平配送（原本ACKベースの3:1） | **合成CI合格・PR #13** | [PR #13](https://github.com/zawa356/RomRaider_agent/pull/13) |
| 2.1-D2b-2c-1 | S行が全キューを占有しないようM/E用に4096枠予約 | **合成CI合格・PR #15** | [PR #15](https://github.com/zawa356/RomRaider_agent/pull/15) |
| 2.1-D2b-2c-2 | 旧TSV writerの64KiB分割書込／障害時巻戻しテスト | **Linux/Windows合成CI合格・PR #16** | [Agent PR #16](https://github.com/zawa356/RomRaider_agent/pull/16)／[PR CI #38369809042](https://github.com/zawa356/RomRaider_agent/actions/runs/38369809042) |
| 2.1-D2b-2c-3 | writer未fsync行・障害状態の可視化／19.6万行飽和復旧試験 | **CloudPC合成44項目・Windows/Linux CI合格・PR #17** | [PR #17](https://github.com/zawa356/RomRaider_agent/pull/17)／[CI #38372123905](https://github.com/zawa356/RomRaider_agent/actions/runs/38372123905) |
| 2.1-D2b-2c-4a | 独立した有界fsync前段journal PoC・異常終了／破損／quota検証 | **PR #18／Windows・Linux CI合格、製品接続は未実装** | [PR #18](https://github.com/zawa356/RomRaider_agent/pull/18)／[PR CI #38373993678](https://github.com/zawa356/RomRaider_agent/actions/runs/38373993678)／[設計・試験記録](phase_2_1_d2b_2c_4a_ingress_journal_prototype.md) |
| 2.1-D2b-2c-4b-1 | 有界非ブロッキングhandoff・失敗保持・安定再生IDの独立PoC | **CloudPC合成75チェックPASS、CI確認中、製品未接続** | [設計・試験記録](phase_2_1_d2b_2c_4b_1_handoff_contract.md) |
| 2.1-D2b-2c-4b-2a | 永続source UUID sidecar＋実SQLiteの原本receipt/cursor原子的commit | **CloudPC実SQLite22項目＋旧PoC75項目PASS・PR/main CI両OS PASS、製品v1変換未接続** | [Agent PR #20](https://github.com/zawa356/RomRaider_agent/pull/20)／[main CI #38381208274](https://github.com/zawa356/RomRaider_agent/actions/runs/38381208274)／[設計・試験](phase_2_1_d2b_2c_4b_2a_sqlite_receipts.md) |
| 2.1-D2b-2c-4b-2b | journal M/S/E→既存製品v1変換＋SQLite cursor/レーン排他 | **CloudPC合成359チェック・PR Windows/Linux製品v1 CI PASS、実車/実Server未接続** | [Agent PR #22](https://github.com/zawa356/RomRaider_agent/pull/22)／[CI #38384353160](https://github.com/zawa356/RomRaider_agent/actions/runs/38384353160)／[設計・故障試験](phase_2_1_d2b_2c_4b_2b_journal_to_product.md) |
| 2.1-D2b-2c-4b-2c | 最新Agent journal/TSV→製品SQLite→FastAPI→実PostgreSQL16 E2E・安全DBガード | **CloudPC REAL E2E・6件/2プロフィール/重複ACK PASS、PR CI Windows/Linux＋DBガード6件PASS。GitHub実ServerステップはSKIPPED** | [PR #23](https://github.com/zawa356/RomRaider_agent/pull/23)／[CI #38387493290](https://github.com/zawa356/RomRaider_agent/actions/runs/38387493290)／[検証記録](phase_2_1_d2b_2c_4b_2c_real_postgres_e2e.md) |
| 2.1-D2b-2c-4b-2d-1 | オフライン限定の明示Worker選択、SQLite持続停止、状態確認CLI | **CloudPC419チェック＋実PostgreSQL E2E合成7件/3profiles PASS、PR Windows/Linux CI PASS。GitHub実ServerステップSKIPPED** | [PR #24](https://github.com/zawa356/RomRaider_agent/pull/24)／[CI #38391573311](https://github.com/zawa356/RomRaider_agent/actions/runs/38391573311)／[設計/試験](phase_2_1_d2b_2c_4b_2d_1_offline_worker.md) |
| 2.1-D2b-2c-4b-2d-2a | live writerと並行するlock-free/read-only RLJ1診断、existing-only identity | **CloudPC/Linux443項目PASS、Windowsライブ読取はFileLockでBLOCKED、PR両OS CI PASS（遮断検証）・製品cursor/ACK非接続** | [Agent PR #25](https://github.com/zawa356/RomRaider_agent/pull/25)／[journal CI #38401119395](https://github.com/zawa356/RomRaider_agent/actions/runs/38401119395)／[設計・検証](phase_2_1_d2b_2c_4b_2d_2a_live_readonly_probe.md) |
| 2.1-D2b-2c-4b-2d-2b-1 | 別writer lock＋8MiB外guardの独立PoC・新旧別JVM競合 | **PR #27 merged、Windows/Linux CI PASS、運用非接続** | [PR #27](https://github.com/zawa356/RomRaider_agent/pull/27) |
| 2.1-D2b-2c-4b-2d-2b-2 | fsync確定watermarkとprefix検証・別JVM故障注入 | **PR #28 merged、Linux PoC PASS、Windows BLOCKED（拒否/保全CI PASS）** | [PR #28](https://github.com/zawa356/RomRaider_agent/pull/28) |
| 4b-2d-2b-3a | Windows原本同一性3案比較 | **独立比較PASS、A/C反例で否決、B test candidate** | [PR31](https://github.com/zawa356/RomRaider_agent/pull/31) |
| 3a-compat（安全代替） | bound native READ/actual Zulu8 1.8.0_504 x86 | **独立fixture PASS、NOT_COMMITTED診断** | [PR32](https://github.com/zawa356/RomRaider_agent/pull/32) |
| 3a-writer-binding（安全代替） | same native ID/write/flush/guard | **x64 writer44/x86 writer45 PASS、marker/DB非接続** | [PR33](https://github.com/zawa356/RomRaider_agent/pull/33) |
| 3b（RLC2独立PoC） | 新version/identity-tag marker・確定手順・各段階故障注入 | **独立PoC（Windows x64/x86 PASS、Linux CIで判定）、Windows dir UNKNOWN、製品非接続** | [設計/試験](phase_3b_rlc2_watermark.md) |
| 3c-1（RLC2 ledger独立PoC） | 永続expected identity/highwater/receipt同一SQLite transaction | **独立PoC（Windows x64/x86 PASS、Linux CIで判定）、ACK非接続** | [設計/試験](phase_3c1_rlc2_ledger.md) |
| 3d-1（RLM1 rotation独立PoC） | seal・rotation manifest・16本quota・session順ingest | **独立PoC（Windows x64/x86・Linuxコンテナ PASS、CIで判定）、製品非接続** | [設計/試験](phase_3d1_rotation_manifest.md) |
| 2.1-D2b-2c-4b-2d-2b-3 | 新writer/reader/SQLite合成結合とrotation | **BLOCKED（永続highwater/dir durability未成立）、設計境界のみ** | [設計/ゲート](phase_4b_2d_2b_lock_watermark.md) |
| 2.1-D3 | X270 Zulu8 32bit・disk-full・物理電源断・実車耐久 | 未着手 | Issue #7 |
| 3 | ECU→RomRaider→Agent→Serverの停車状態での実車検証 | 未着手 | [Issue #8](https://github.com/zawa356/RomRaiderServer/issues/8) |

## Phase 2.1-D2b-2c-4b-2d-2a — lock-freeライブ原本診断（製品へ非接続）

- live writerが排他的ロックを保持する間もFileChannel.READによりRLJ1を検査できるread-only診断APIを追加。CRC・連番・UTF-8・8MiB/128KiB上限、途中tailや変更中ファイルを区別。原本には書込みしない。
- 旧JournalIdentityPrototype.openにはID生成があり得たため、readExisting()を分離しすべてのjournal readerから呼ぶように変更。sidecar欠落時の自動UUID再発行を防止。
- **CRC妥当な完全フレームでもfsync完了とは限らない**ため、既存ProductBridgeWorker、製品SQLiteカーソル、Server ACKに接続しない。次にdurable watermarkを検討する。
- CloudPC Java8実行：journal/handoff旧75＋新24＝99件、製品SQLite/receipt344件、合計**443件PASS**。160件fsync付き追記と最大320回の同時読取を追加5反復PASS。物理電源断／実車／Win32bitは未実施。[4b-2d-2a設計](phase_2_1_d2b_2c_4b_2d_2a_live_readonly_probe.md)。

## Phase 2.1-D2b-2c-4b-2d-1 — Journal Workerのオフライン・明示実行、安全停止、監視参照

- `ProductBridgeWorker --journal-once`を追加。明示フラグ`ROMLOGGER_JOURNAL_WORKER_ENABLE=1`が必須。旧`--once`／`--foreground`はTSVのみ。writer同時稼働に対してはjournal排他ロックがあるため`--journal-foreground`は実装せず拒否する。
- `ProductJournalScanner`で専用ディレクトリ／sidecar／UUID／元パス／8MiB・16本上限／writerロック・原本継続性を検証。任意の旧TSV混在やsidecar欠落時は原本を改変せずfail closed。
- `DurableSpool`に永続`v1_ingress_review_state`。不正入力で終了コード5、定型`INGRESS_REVIEW_REQUIRED`を出し、HTTP送信を実行せず、次回再起動後も旧TSV Workerを含め自動復旧しない。初回原因の固定コードのみ保存。解除APIなし。
- 読み取り専用`ProductIngressStatus`でSupervisor等が状態を確認可能（exit 0=no review latch、5=review、6=unknown、2=invalid argument）。**Supervisor本体との連携は未実装**。
- CloudPC：実SQLite＋receipt計**344チェックPASS**、journal/handoff**75チェックPASS**、計**419チェックPASS**。新規Scanner25、Worker別JVM23、Status別JVM12の60チェックを追加。
- CloudPC別置Postgres16の実FastAPI E2Eで既存TSV3＋従来journal3＋`--journal-once` Worker1＝**合成7 record・3 profile・重複0・profile/batch/重複ACK PASS**。実車、Windows32bit、電源断、同時writer、常駐Workerの並行journal監視は未実施。
- [4b-2d-1設計/故障試験](phase_2_1_d2b_2c_4b_2d_1_offline_worker.md)。

## Phase 2.1-D2b-2c-4b-2c — CloudPC専用PostgreSQL16の実Server E2E

- 最新AgentとServerの双方をCloudPCのjr2twt専用cloneに用意し、PostgreSQL16実行ファイルを**隔離展開**して127.0.0.1:25432の専用DBだけでE2E実施。FastAPIは127.0.0.1:18083で試験中のみ起動した。Caddy/MCP、既存DB・サービスは非変更。
- テスト用Java8ドライバ`JournalServerE2E`を新設。journal原本M2+S3から製品v1 SQLite、実HTTP profile/batch ACKへ到達し、別のSQLiteからの同一原本再送もServerで重複ACKしてレコード数が増えないことを確認。従来TSVの3件を合わせ、**実PostgreSQLで合成観測6件、profile2件、record UUID重複0**を実SQL照合した。
- DB誤接続回避：E2E用の明示フラグ、localhost/127.0.0.1、`romlogger_*_e2e`のみ許可。libpq URLクエリのhost上書きも拒否。DBへ接続しない6件の負試験PASS。既存回帰はSQLite系284件、journal/handoff75件＝合計359件PASS。
- GitHub Actionsの実Server E2Eはprivate Server repoの読取secretがない場合、**SKIPPED**のまま維持し、6件の安全ガードだけ常時検証。CloudPC実Server E2Eの成功とGitHub Actions上のE2E成功を混同しない。
- **未実装/未保証**：本機能を運用WorkerやLoggerに自動接続する設定・停止ゲート、8MiB上限／元queueの未fsync行、物理電源断／Win32bit・実ECU試験、Caddy/本番配備。詳細：[4b-2c実験記録](phase_2_1_d2b_2c_4b_2c_real_postgres_e2e.md)。

## Phase 2.1-D2b-2c-4b-2b — Journalから既存v1レコードへ明示的に変換（合成検証）

- 新規`IncrementalTsvBridge.importJournal()`を追加。既存`apply(M/S/E)`を共有するため同一原本から旧TSVと同じprofile/record UUIDを生成でき、製品`v1_profiles`・`v1_records`へ格納。自動起動・ECU callback・Server IFは変更なし。
- `v1_journal_import_cursors`はsource UUID/原本パス/セッション/連番/先頭SHAと累積SHAチェーン/metadata snapshotを記録。各行の製品レコードをSQLiteへcommitした後にcursorをCAS更新。障害時は同一ID再投入で冪等復旧。原本変更・移動・縮小をfail closed。
- `v1_source_claims`でdevice/vehicle/sessionあたりの旧TSV/Journal受入れを排他。既存増分・旧一括TSV両方にもガードを適用し、過去バージョンに残るcursorなしのrun recordにも安全側で対応する。
- CloudPCのJava8互換／実SQLite試験で製品v1＋receipt計**284件PASS**、journal/handoff旧回帰**75件PASS**、合計**359件PASS**。新しいJournalProductBridgeTestは**35件PASS**（TSVとの同一レコードID、重複・移設・SHA改変、旧run、ServerプロフィールACK gate、別JVM halt(79)直後の再起動）。
- [PR CI #38384353160](https://github.com/zawa356/RomRaider_agent/actions/runs/38384353160)では製品v1実SQLiteのWindows/Linux両ジョブPASS。一方、[real-server-e2e #38384353153](https://github.com/zawa356/RomRaider_agent/actions/runs/38384353153)は「gated状態の宣言」のみであり、Server接続ステップを**実行していない**。\n- テスト追加中、別JVMの新規原本を数える前の期待件数が残り初回FAIL。期待値を更新して全suite PASS。実車・ディスクフル・32bit・OS電源断・**最新Agentの実Server PostgreSQL E2EはNOT RUN**。
- 本Phaseは **製品v1内部の明示呼び出し機能** まで。Logger→journal→Workerの自動配線、本番配備、実Server ACKの結合検証を行っていない。詳細：[4b-2b設計／検証](phase_2_1_d2b_2c_4b_2b_journal_to_product.md)。

## Phase 2.1-D2b-2c-4b-2a — 原本ジャーナルIDと実SQLite receipt台帳の原子的境界

- 原本journalが**空のうちに**UUIDをsidecarへCREATE_NEW＋同期保存。非空journalにIDがなければfail closed。サイドカーCRC不一致も修復せず停止する。
- `JournalSqliteReceiptPrototype`はSQLiteの試作用`proto_ingress_rows`と`proto_ingress_cursor`だけを使用。原本SHA-256・決定的record UUID・source連番を確認し、レコード＋カーソルを同一SQLite transactionでcommit。製品用`v1_records`／ACK／TSV変換には未接続。
- CloudPC上のJava8互換・**実SQLite**で新規22チェックPASS、従来journal/handoff75チェックPASS、製品v1既存回帰PASS。失敗注入によるDB rollback、別JVMのcommit直後halt・次JVMでの再開、サイドカー欠落／変更、元journal改変を検証。
- [PR CI #38381013007](https://github.com/zawa356/RomRaider_agent/actions/runs/38381013007)と[Agent main CI #38381208274](https://github.com/zawa356/RomRaider_agent/actions/runs/38381208274)：Ubuntu/Windows両方のjournal＋製品v1実SQLite回帰＋receipt testを実行して成功。
- 初回は試験fixtureがCRC文字を固定`0`にしたため、同じ文字の場合だけ破損しない失敗があった。**異なる文字に確実に変更する修正後PASS**。詳細は[4b-2a設計](phase_2_1_d2b_2c_4b_2a_sqlite_receipts.md)。
- サイドカーのOS電源断・ディレクトリfsync、Windows32bit、journalとIDのペアの移動・ローテーション、原本→製品v1変換・実Server、キュー飽和時の無欠損保証は**NOT RUN/未実装**。本Phaseは独立PoCに限定。

## Phase 2.1-D2b-2c-4b-1 — 独立handoffと再生契約PoC

- Logger callback、旧TSV writer、製品v1 SQLite outboxに接続しない`IngressHandoffPrototype`を追加。受理側は`ArrayBlockingQueue.offer`のみで`ACCEPTED_VOLATILE`／満杯拒否／障害拒否を区別。同期fsyncは独立workerの`drainOne()`だけで行う（自動起動しない）。
- writer異常時は`inFlight`の原文を保持し、fail closedで後続受入れを停止。resumeは運用者の明示review＋journal再検証後のみ。quota超過はterminal latchし、既存原本を消さない。
- journalの`sequence`と外部不変`sourceUuid`から決定的UUIDを生成して再生。`afterSequence`を使った分割再生をサポート。ただし**実SQLiteへの送信、sourceUuidの本体への永続記録、cursorの永続化は未実装**。重複検証はテスト専用Mapのみ。
- CloudPC（Java17 `--release 8`）で4aの34件と4b-1の41件、合計75チェックPASS。worker停止中のproducer追加投入、queue満杯拒否、I/O失敗後の保持と復帰、重複ID、quota終端、再起動前の未fsync行を取り戻せないことを合成確認。
- **重要制約**：`ACCEPTED_VOLATILE`はfsync成功を表さず、JVM異常終了・電源断では失われる可能性がある。既存のLoggerや製品Serverに導入しておらず、D2b-2cの無欠損目標は未完了。次の4b-2で製品SQLiteとの厳密な責任分界・永続cursor・原本ID・安全な中断判断を設計する。
- 詳細：[4b-1設計・試験](phase_2_1_d2b_2c_4b_1_handoff_contract.md)。

## Phase 2.1-D2b-2c-4a — 独立した永続入口ジャーナルPoC

- 既存の`Stream.java`やlogger callback・実ECU・製品v1 SQLite outboxを一切変更せず、**独立したJava8互換`IngressJournalPrototype`** を追加。TSV原文をlength/sequence/CRC32付きframeとして保存し、各追記後のfsync、OS file lock、quota明示拒否、完全性検証、再起動後の読込を試験する。
- 復旧方針はfail closed。破損末尾は自動切詰め・再書込・削除せず、運用者の保全と判断を必須とする。1行最大128KiB、ジャーナル最大8MiBは**PoC検証上の上限**であり、実車用容量・性能評価ではない。
- CloudPC上の隔離cloneでJava17の`--release 8`を用い**34チェックPASS**、別Javaプロセスをfsync後`Runtime.halt(73)`で強制終了しても次起動時に既存行を再読込できた。GitHub Actions [CI #38373518940](https://github.com/zawa356/RomRaider_agent/actions/runs/38373518940)と[PR #18 CI #38373993678](https://github.com/zawa356/RomRaider_agent/actions/runs/38373993678)のWindows/Linux実試験ステップも両方PASS。
- **限定点**：JVM異常終了と物理電源断は同等ではない。OS/ディスクのキャッシュ・dir fsync・Windows x86・実デバイスの過負荷は未検証。finite RAM＋callback非ブロッキング＋無制限disk停止の条件では無欠損にならない。同期appendをECU callbackへ繋いではならない。
- 既存製品v1 SQLite outboxとの責任分界、別workerへの受渡し、データ量の保護・停止条件、重複排除と原本の明示管理は**後続Phaseの設計対象**。本Phaseは独立PoCの有効性のみ判定し、実車・本番へは配備しない。
- 詳細：[Phase 2.1-D2b-2c-4a 設計と検証記録](phase_2_1_d2b_2c_4a_ingress_journal_prototype.md)。

## Phase 2.1-D2b-2c-3 — 書込障害の観測と有界キューの耐久境界検証

- 旧PoC `Stream.java` の`status`/`stream_status`に`writerPendingLines`、`memoryBufferedLines`、`spoolWriteFailures`、`writerDegraded`、`lastWriterFsyncAtMs`、`lastWriterFailureAtMs`を**追加のみ**で公開。writer待機中の行も未fsync原本として表示できる。契約正本`docs/10_interfaces.md` C4.1を同期。
- 既存のwriter保護と非ブロッキングcallbackは維持。書込失敗は試行数・最終失敗時刻・劣化状態へ記録し、元データは`pending`に保持。正常fsyncで劣化状態を解除しても、過去の欠損を否定しない。
- 合成試験ではspoolディレクトリのパスを通常ファイルに置き換えることでwriterの書込だけを失敗させ、全体ディスク容量・実ホストのI/Oを枯渇させない。Queueを`SAMPLE_QUEUE_LIMIT=195904`件まで飽和させ、追加10サンプルのdropとM/E定義保持、復旧後の受理全195904行・損失マーカー1件・メタデータ3行を確認する。
- CloudPC（Ubuntu側、Java17 `--release 8`）でスタブ合成44チェックPASS。[PR #17 のCI #38372123905](https://github.com/zawa356/RomRaider_agent/actions/runs/38372123905)でもUbuntu/Windowsとも実テストステップPASS。初回のテストでは過去成功fsync時刻をnullと誤って期待し失敗、既存の成功時刻が残る仕様に修正。**試験実行は実ECUや本番VPSデータの操作を含まない。**
- **未解決**：writer停止時間が無制限なら有限メモリqueueでは無欠損保証不能。writerが正常化する前にJVM/PCが落ちれば未fsyncの`queue`と`pending`は失われる。失敗カウンターも同様にメモリ内のみで再起動後は失われる。本番運用監視・永続journalの導入には別のリソース／安全ゲート検討が必要。
- 製品v1のSQLite送信Worker・Server通信形式・安全ガードは変更しない。X270/Zulu8 32bit/実車・長期disk-full・本番配備は未実施。最新Agent→実PostgreSQL E2Eも未実施。

## Phase 2.1-D2b-2c-2 — 旧TSV writerのバッチ化・部分書込rollback検証

- `Stream.flushPending()`の既存5,000行単位の書き込みを、固定64KiBバッファを用いる`writeRowsBatched()`へ置換。行末LF、UTF-8、列順・原本値は変更しない。巨大単一行は二重コピーせず直接書く。
- `appendDurably()`にファイル尾のLF検査→元長さの記録→分割書込→fsyncをまとめ、IOExceptionに加えRuntimeExceptionでも可能な限り元長さへ戻す。失敗時は呼び出し元の`pending`と欠損イベントが残り、次回に同じ原本を再試行する。
- テストでは最初の書込が済んだ後に2回目でIOExceptionを合成発生させ、既存TSVの長さと内容が復元すること、再試行で全バイトが一度だけ追記されることを確認。途中で不正なnull行が出た場合も元に戻す。
- 2,400行の日本語UTF-8サンプル、64KiBを超える単独行、混在M/E行、既存queue overflow／writer失敗／元TSV保持の全試験を継続。標準JDKの`--release 8`でCloudPC Linux **31チェックPASS**、GitHub Actions [CI #38369405259](https://github.com/zawa356/RomRaider_agent/actions/runs/38369405259)でLinux/Windows両ジョブPASS。時間ベンチマークによる性能保証は行わない。
- **失敗と修正記録**：[初回CI #38369266614](https://github.com/zawa356/RomRaider_agent/actions/runs/38369266614)は追加したLF文字リテラルのエスケープ誤りでコンパイル失敗。commit `ebf1f4c`で修正し、合成試験追加後commit `9c90e02`に対するCIが通過した。失敗履歴を消さない。
- **残る重大制約**：writer停止中にメモリキューが上限に達するとS行を失う。通常の電源断・OSクラッシュ時に未fsyncのキュー／writer待機バッファを復旧する機能はない。rollback自体が失敗した場合はTSV尾の手動確認が必要。X270／Zulu8 32bit／disk-full／実ECU未検証。
- `handleDataUpdate()`の非ブロッキング性、安全ガード、旧PoC/製品v1の通信契約、元TSV自動削除禁止、本番配備状態を変更しない。**D2b-2c全体を完了扱いにしない**。

## Phase 2.1-D2b-2c-1 — bounded queueのサンプル優先制限（合成CI合格）

- 既存の200,000行上限は維持。S行のみ深さが`QUEUE_CAPACITY - 4096 = 195904`行以上の深さで新たなS行を非ブロッキングで拒否する。
- M/E行には4,096枠の予約余地を確保する。ただしM/E行の大量生成、競合、writer停止が続けば全体上限に到達し得る。**メタデータ完全保全・欠損ゼロの保証ではない**。
- 拒否したS行も既存`dropped`計数と`queue_overflow lost_lines`イベントへ反映する。原本の復元はできない。
- Logger callbackへディスクI/O・sleep・ブロッキング待機は追加しない。PoC/製品v1の通信契約、旧TSV原本の自動削除禁止は変更しない。
- スタブ合成試験で予約閾値・M/E格納・累積欠損マーカーを確認する。[PR #15](https://github.com/zawa356/RomRaider_agent/pull/15)／[CI run #38367644971](https://github.com/zawa356/RomRaider_agent/actions/runs/38367644971) のLinux/Windows両ジョブ成功。初期試験fixtureのキュー再充填忘れにより [run #38367585353](https://github.com/zawa356/RomRaider_agent/actions/runs/38367585353) は失敗、修正commit `cb220de` で修復。
- **未完了**：長期I/O停止・プロセス異常終了時の未fsyncメモリ、完全なdurable ingress、実車X270/32bit/disk-full。後続のD2b-2c-2以降で設計・故障注入する。

## 変更履歴

### 2026-10-13 — チャット上限に伴う開発引継ぎ

- [Agent固有の状況・再開手順](handoff/2026-10-13_chat_handoff.md)と[Server全体引継ぎ](https://github.com/zawa356/RomRaiderServer/blob/main/docs/handoff/2026-10-13_chat_handoff.md)を追加。
- E2Eの緑色ワークフローでもPrivate checkout・実結合のstepがskippedである場合は**NOT RUN**と区別。
- 本更新は文書のみ。実ECU・X270・車載安全判定・本番操作を実行したことを意味しない。

### 2026-10-13 — Phase 2.1-D2b-2b：滞留／新規データの公平配送（Windows/Linux合成CI合格）

- `DurableSpool.nextRecentBatch()`で最後に生成されたrunの**最も古い未ACKレコードから**同一runバッチを構築。runを混ぜず、個々のrun内の順序を保つ
- `ProductSender`で成功ACK台帳の件数を基準に**3バッチは旧滞留、4バッチ目は新run**を選択。プロセス再起動後も比率とデータIDが維持され、失敗・未ACKでは選択スロットが進まない
- ローカル未ACK削除なし。既存の厳格なACK条件、ProfileRevision待ち、通信断時backoff、要調査停止を維持
- `FairDeliveryTest`で実SQLiteの16レコード・2runを用い、古いrunの飢餓回避、最新runの遅延軽減、再起動、ACK無しでの安定性を合成検証
- **残課題**：旧PoCのメモリキュー飽和自体の完全防止は実装していない。飽和時の欠損マーカーは引き続き残り、既知の不完全性として実車／負荷試験で評価する
- [Agent PR #13](https://github.com/zawa356/RomRaider_agent/pull/13)／[Windows/Linux CI #38361284331](https://github.com/zawa356/RomRaider_agent/actions/runs/38361284331)：Windowsで`FairDeliveryTest` **19チェックPASS**、既存DurableSpoolTest42件・ProductSenderTest41件もPASS。Linuxジョブも成功

### 2026-10-13 — Phase 2.1-D2b-2a：PoC原本の安全側保全（合成CI成功）

- `Stream.writerLoop()`の書き込み失敗時にdequeue済み行を**破棄せず保持して再試行**。追記後fsyncし、不完全TSV末尾への追記を拒否
- `Stream.sendSome()`はwrite/fsync・rollbackと共通ロック下でファイルを読む。HTTP待機はロック外
- キュー満杯時に失われた元観測は復元できない。**次回writer成功時に`E queue_overflow lost_lines=N`として欠損数を明示**。電源断前の未fsyncキューは依然復旧不能
- 旧`cleanupSpool()`を無効化。旧Receiverの`.sent`では製品Server ACKにならないため、未読元TSVを**自動削除しない**。ディスク残量監視・手動バックアップ整理は必要
- 既存`Stream.java`本体をGUIなしRomRaider stubで検査するCIを追加。書込故障・再試行、破損末尾、overflow証跡、削除拒否をLinux/Windowsで実行
- [PR #12の最終CI #38355293148](https://github.com/zawa356/RomRaider_agent/actions/runs/38355293148)：Windows/Linuxそれぞれ**19項目PASS**。実車・Zulu8 32bit・disk-full・電源断は未試験
- **未完了**：queue飽和そのものの回避、再起動前メモリの完全保全、fresh/backlog公平配送、製品Worker最新の実Server E2E。後続D2b-2bで扱う

### 2026-10-13 — Phase 2.1-D2b-1：永続backoffと明示起動Worker／旧TSVソース欠損検知

- `DurableSpool.java`：ローカル`v1_delivery_state`にretry回数・次回送信時刻・要調査停止状態・最終送信結果を保存。Server側のACK/原本JSONの削除条件は変更しない
- `ProductDeliveryCoordinator.java`：RETRY_LATER時は2→4→8…最大120秒で遅延。再起動で待ち時間維持。認証・契約不備の`NEEDS_REVIEW`は再起動後も**送信禁止**。専用APIによる明示的なoperator resetまで止める
- `ProductBridgeWorker.java`：**`--once`／`--foreground`で明示起動した別Javaプロセスのみ**。TSVの増分取り込みと送信を分離し、SQLiteとOS lock fileで同一workerの多重起動を抑止。RomRaider Logger JVM本体・既存PoCの操作コードへ自動組込なし
- `ProductLegacyScanner.java`：旧TSVソース64個の上限・取り込み済みTSV消失／移動・symlinkを検知して**fail closed**。ファイルを削除しない
- `ProductDeliveryCoordinatorTest`：実SQLiteで再起動後のバックオフ・上限・正しいACKでの解除・要調査の永続停止／手動解除・予期しない例外伝播を47件試験
- `ProductLegacyScannerTest`：実SQLiteで途中行・再起動・元ファイル移動・64ファイル超・未ACK保全を14件試験
- `ProductBridgeOnce`（従来の明示CLI）も**共通の永続retry／reviewゲート**へ接続し、停止を迂回できないよう修正
- `ProductBridgeWorkerTest`：別のJavaプロセス（合成HTTPモック）で定義送信→次プロセスでバッチ送信→ACK済行の再送防止→既読ファイル消失時のプロセス停止を11件試験
- [Windows/Linux CI #38352330245](https://github.com/zawa356/RomRaider_agent/actions/runs/38352330245)：両OSで **SQLite42＋HTTP41＋旧TSV16＋増分37＋retry47＋scanner14＋別JVM11＝各208項目PASS**
- [送信Worker詳細／旧PoCの欠損リスク調査](product_v1_delivery_worker.md)。旧`Stream.offer()`のdrop、`writerLoop()`のdrain後write失敗時の行消失、`cleanupSpool()`の旧ACKだけによる元ファイル削除は**なお未解決**。D2b-2で優先対応
- **実Server E2EのD2b-1版pinは未更新**。現行Server PR #24はD2aのJava実ソースのみ。ECU・X270／Zulu8 32-bit・電源断・disk-full・常駐起動設定も未検証。自動デプロイは行わない

### 2026-10-13 — Phase 2.1-D2a：実Server E2Eの最新増分取り込み対応（テストコード）

- `ProductServerE2E.java`を従来の全件`LegacyTsvBridge`から、**`IncrementalTsvBridge`と永続offset**を使用する実Serverテストへ更新
- 合成TSVを初回1定義行だけ読み、**プロセス再起動→残り定義＋観測1件→未完結TSV行保留→追記→再起動して合計3件**をSQLiteへ保存する手順へ変更
- FastAPIへ定義PUT→3件のPOST→完全ACKを受け、**新しい別SQLite**で同一TSVを2/2/1行の増分チャンクで読み直し、同一原本IDを再送して重複のみのACKが成立するか検証
- 使い捨てPostgreSQLの直接照合を観測**3件**・ProfileRevision**1件**に変更。[Server PR #24](https://github.com/zawa356/RomRaiderServer/pull/24)でAgent main commit `668ad5230e7e3db4fc592311c857906f43802ad3` の7ソースファイルを原本Git blob SHA一致で固定し、[実E2E CI #38350030034](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030034)がPASS
- [Server製品版契約CI #38350030080](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030080)と[PoC回帰CI #38350030122](https://github.com/zawa356/RomRaiderServer/actions/runs/38350030122)も全ジョブPASS
- 既存RRBridgeのLogger callback、操作系、実車、自動スケジューラは対象外。Agent main変更のたびにServer CIが自動同期する構造ではなく、次の変更では再pin／差分確認が必要

### 2026-10-13 — Phase 2.1-D1：TSV増分取り込み／SQLite永続カーソル（Windows/Linux CI合格）

- `IncrementalTsvBridge.java`：旧TSVの読み取り専用処理を**保存済みoffsetから増分実行**。メタデータ定義のスナップショットをSQLiteへ保持して再起動後も全履歴再走査不要
- `DurableSpool.java`：`v1_import_cursors`を追加。**SQLiteへの原本保存が成功してから**offset・行番号・定義状態・source fileKey・先頭／境界SHAを更新。CASで古いカーソルによる後退を拒否
- `ProductBridgeOnce.java`：明示実行時に最大256の完結済みLF行のみ取り込み、最大1 HTTP交換。**従来PoCの`Stream.java`やLogger callbackを変更・自動起動しない**
- `IncrementalTsvBridgeTest.java`：再起動後の続き読み、部分行、故障レコードによるoffset停止、元ファイル改変／短縮の拒否、**原本DB commit直後／カーソル更新前の故障注入→重複なしの再処理**を実SQLiteで試験
- [PR #8 最終CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38348565296)：Windows／Linuxそれぞれ**SQLite 42＋HTTP 41＋旧TSV 16＋増分取り込み 37＝136項目PASS**
- [失敗CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38348269336)：故障注入テスト用TSVでタブ記号のエスケープを誤り形式不正。テストfixtureを修正した再実行でPASS。**失敗履歴は残す**
- [詳細と限界](product_v1_incremental_import.md)：既存キューあふれ／TSV writer欠損／ソース削除とローテーション、fileKey不明時の交換検知、実車負荷・電源断・disk-full・Windows X270/Zulu8、Server側pin E2E更新はいずれも未完了
- phase進行は安全側：未ACK原本を削除せず、予期しないソース変更は自動リセットしない

### 2026-10-13 — Phase 2.1-C2a：pin済みAgentと実Server/PostgreSQL合成E2Eの成功

- [Server PR #22](https://github.com/zawa356/RomRaiderServer/pull/22)に、本Agentのcommit `6dd46835a200d961d517f5bb54f11eb2d7bdd2af` のJavaソース5ファイルと実Server E2Eスクリプトを**検証用に原本blob SHA一致で固定**。製品版Agent本体は変更していない
- [E2E CI #38346271102](https://github.com/zawa356/RomRaiderServer/actions/runs/38346271102)で、Java8互換の実ソース→レガシー合成TSV→SQLite→実FastAPI→Disposable PostgreSQL16（観測2件＋定義1件）を成功。**別SQLiteからの同一record ID再送も重複登録なし**
- これで**pinしたAgentバージョンの実Server結合は検証できた**が、Agentの将来のmain自動追従ではない。旧RRBridgeの実車Logger callback、ECU、X270、Zulu8 32bit、TSV writer欠損／queue drop、常駐自動運用は未試験
- [pin版と同期更新手順](https://github.com/zawa356/RomRaiderServer/blob/main/tests/e2e/agent_v1_snapshot/README.md)。本Agent repoの`ROMLOGGER_SERVER_READ_TOKEN`を必要とする別Private checkoutワークフローは引き続き**NOT RUN**（別ルートの成功とは区別する）

### 2026-10-13 — Phase 2.1-C1追加修正：mainマージ後Windows CIの外部依存403

- **障害履歴（隠さない）**：[Agent main CI #38339774312](https://github.com/zawa356/RomRaider_agent/actions/runs/38339774312)のWindowsジョブで、`repo.maven.apache.org`からの依存JARダウンロードがHTTP403を返して終了。LinuxジョブはPASS、Javaコードのテスト自体はWindowsで開始できなかった
- **原因区分**：ビルド用依存ファイルの外部取得失敗。SQLite/HTTP/TSVの業務ロジック不一致ではないが、main CIの失敗として取り扱う
- **修正**：Maven Central公式2エンドポイントへの段階的再試行、部分ダウンロードを隔離、Java `jar tf`で破損確認してからJAR配置、OSごとのテスト依存jarキャッシュ
- **修正ブランチ再試験**：[PR #6](https://github.com/zawa356/RomRaider_agent/pull/6)／[Windows・Linux CI #38340182185](https://github.com/zawa356/RomRaider_agent/actions/runs/38340182185)ともに**99項目PASS**。mainへマージ後の再検証も行う。実Server E2EのNOT RUN問題はこのCI修正では解消しない

### 2026-10-13 — Phase 2.1-C1：RRBridge PoC TSV→製品SQLite取り込み（合成CI）

**追加**

- `LegacyTsvBridge.java`：既存RRBridge v0.5のM/S/E TSVを読み取り、ProfileRevision・Sample・Eventの正式v1 JSONを作りSQLiteに永続登録。TSV本体は読み取り専用。既存Logger callbackとPoC送信・安全制御は変更しない
- IDはdevice ID・旧session・segment・元sample sequence／行位置から**決定論的に生成**。再読み込み・再起動後も変わらず、二重登録は原本UUIDで抑制
- 元TSVのUTF-8/LF終端済み行だけ処理。書込み途中の行は保留し、不正データは黙って補完・読み飛ばししない
- TEST_パラメーターだけの観測は`synthetic`、それ以外は`ecu`。旧RRBridgeイベントは`source_kind=agent`で構造化登録
- `ProductBridgeOnce.java`で、明示的に指定したTSVファイルから一度だけSQLiteに取り込み、最大1 HTTP交換するCLIを追加。トークンは環境変数、**車載へ自動組込／自動起動しない**
- Windows/LinuxのSQLite実DBテストに`LegacyTsvBridgeTest`を追加。再読込・不完全行・イベント・profile定義の保護を確認
- [Product v1 Agent CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38339596749)：**Windows/Linuxの両方で SQLite 42 + HTTP 41 + TSV importer 16 = 各99件PASS**。合成データ／契約モックの試験。

**実Server接続試験の状態**

- `ProductServerE2E.java`と`scripts/test-product-v1-real-server.sh`で、独立したJava Agent→FastAPI→PostgreSQLの合成2件送信と重複再送を検証する試験を準備
- [PR #5](https://github.com/zawa356/RomRaider_agent/pull/5)に実装、テスト、通信契約と安全上の制約、任意実Server E2Eの試験手順を記録。\n- **GitHubの両リポジトリがPrivate**。Agentの標準`GITHUB_TOKEN`にはServerリポジトリの読取権限が無いため、専用の`ROMLOGGER_SERVER_READ_TOKEN`が設定されない場合に試験を**NOT RUN**と明示するCIゲートを設置
- [最初のE2E CI](https://github.com/zawa356/RomRaider_agent/actions/runs/38339215160)は**認証情報未設定のため未実行**。スキップしたジョブをE2E成功と呼ばない

**未解決**

- Serverとの実E2E、X270実機、Java8 32bit、常駐取込・バックオフ、レガシーキューあふれ、TSVローテーション／削除、disk-full、409/422隔離
- 元PoCのQueueOverflowやTSV writer失敗を検出・保全する仕組みは未実装。今回の変換層だけでECU→DBの完全無欠損を保証しない
- 毎回全TSVファイルを先頭から走査する**検証用実装**。大容量ログでの効率化・永続オフセットは後続工程
- Agent側に実際のK-Line、Supervisor、romraider profile操作を組み込んでいない

### 2026-10-13 — Phase 2.1-B（Windows/Linux合成CI合格）

**追加・修正**

- `ProductSender.java`：既存SQLiteのProfileRevisionを先にPUT登録し、定義ACK後に同一runの観測レコードをJSON配列化してPOSTする。1回の呼出しで1 HTTP交換に限定
- `AgentWire.java`：Java8のみでHTTPS通信（loopbackはHTTP可）、転送先リダイレクト禁止、Bearer認証、パスと本文の上限、受信JSONの型・フィールド重複を厳格に検証
- HTTP200だけでは決してACKとせず、`committed`／`batch_id`／`record_count`／`inserted_count+duplicate_count`一致を条件にSQLiteの原本受領台帳へ反映
- 401/403/409/413/422/不正レスポンスは`NEEDS_REVIEW`、429/503・一時ネットワーク不通は`RETRY_LATER`とし、**いずれも未ACK原本を保持**
- 不正な201 Profile応答が例外伝播し得た点を修正し、原本保全した診断待ち結果へ変更。さらに**201 registered／200 already_registered**のステータスと応答本文の一致を厳密に照合するよう修正
- `ProductSenderTest.java`：ローカルHTTP契約モックと実SQLiteで、ACK喪失後の再起動・duplicate-only確定、レスポンス偽装、不正なJSON、各種HTTPエラーの保全を検証
- [通信実装・未検証範囲](product_v1_http_transport.md)を追加

**非対象／未検証**

- **本物のServer v1 PostgreSQLとJava Agent間のE2E試験は未実施**（ローカルHTTP契約モックでの検証）
- 既存`Stream.java`のLogger callbackへの組込み、バックグラウンド送信、デバイスtokenファイル運用、実ECU、X270 Zulu 8 32bitは未対応
- 413分割、409/422の不良レコードの隔離、Retry-After／指数バックオフ、鮮度優先と過去回収の公平性はPhase 2.1-C以降
- PoCの旧HTTP/TSV、RomRaiderアプリ、手動操作・安全ゲートは未変更

**CI**: [Agent PR #4](https://github.com/zawa356/RomRaider_agent/pull/4)／[GitHub Actions](https://github.com/zawa356/RomRaider_agent/actions/runs/38337745932)：**Windows・LinuxそれぞれSQLite 42件＋HTTP契約モック41件、計83件成功**。実際のServer PostgreSQL・実ECU／Zulu8環境は未検証。

### 2026-10-13 — Phase 2.1-A（Linux/Windows合成CI合格）

**追加**

- `src/main/java/net/neristal/rrbridge/product/v1/DurableSpool.java`：既存PoCとは分離した製品版SQLiteスプール。JDBC／WAL／synchronous=FULLで永続トランザクション
- 不変record IDとrun+sequenceを保護し、内容の衝突を拒否する。ProfileRevisionの登録確認前は対応する観測値の送信候補を返さない
- 同一runの観測を最大128件のバッチとして取り出し、バッチIDと照合した**全件確定ACKのみ**記録。本当にACKされた後はJSON本体をNULL化し、ID／ハッシュ／ACK履歴は保持
- タイムアウト、HTTP 503、形式不正応答ではACK関数を呼ばない構成。欠番の自動補完、未ACKデータの削除・ダウンサンプリングはしない
- `src/test/java/net/neristal/rrbridge/product/v1/DurableSpoolTest.java`：実SQLiteで再起動復旧、定義ACK待ち、ACK不正拒否、ACK喪失後の冪等再送、行衝突とbatch境界、保持を試験
- GitHub Actions `Product v1 Windows Agent spool CI`：ubuntu-latest / windows-latest のマトリクスでJava 8互換バイトコード、実SQLiteテストを実行。RomRaider.jar不要

**変更しない範囲**

- `Stream.java`の既存PoC TSVキューと送信・測定処理、`Agent.java`の操作API／安全ガード
- RomRaider本体、プロファイルXML、本番MCP／Serverの操作要求配送

**残課題・制約**

- 入力JSONと車両／端末IDのv1 wireシリアライズはPhase 2.1-Bで実装。現時点のspoolクラス単体は**JSONテキストを保存するだけ**で完全な通信スキーマ検証はしない
- HTTP送受信、トークン管理、認証失敗・Server 409/422の隔離、未ACK backlogの優先制御は未実装
- 物理電源断・disk-full・SQLite破損時の実機試験、車載PCのZulu 8 32bitおよびRomRaider同一JVMへの組込は未検証
- SQLite初期DB生成／ACK済み本体回収に対する性能特性の測定は今後実施
- [PR #3](https://github.com/zawa356/RomRaider_agent/pull/3)／[GitHub Actions](https://github.com/zawa356/RomRaider_agent/actions/runs/38336131261)：**ubuntu-latest・windows-latestともに実SQLiteの42チェックが成功**。Java 8互換コンパイル確認。X270の32bit Zulu8や電源断の実機試験は引き続き未実施

## Phase 4b-2d-2b-1 — lock分離候補 / WORKING

- 新SplitLockJournalPrototypeと別JVMの旧→新/新→旧/新→新/旧→旧、旧/純粋別lock両方向の反例を追加。quota外guardでデータ範囲をreaderへ開放。既存writerと製品配線は変更しない。
- WSL Linux Java17 --release8: 39新規＋99既存=138 PASS。初回POSIX closeでのlock喪失FAILはD-039に記録し修正。VPS新規、Windows、SQLite回帰は確認中。

### 2026-10-13 22:47 JST — 4b-2d-2b-1 実検証結果 / PASS (PoCのみ)

- PR https://github.com/zawa356/RomRaider_agent/pull/27; code 36bd679 / docs a1a6fa9。
- WSL/VPS1 codex Linux OpenJDK17.0.20.1 --release8: journal138 + SQLite/receipt344 = 482チェックPASS。VPSにrgなし→grepへ切替、journal試験自体は最初から成功。
- GitHub PR最新a1a6fa9: journal run38407692420のWindows/Linux compile/crash/SQLite実ステップsuccess、spool run38407690527の両OS実ステップsuccess。旧writerライブ読取BLOCKEDは継続、新PoCの別JVMライブREAD成功。実Server E2Eは今回NOT RUN。
- 日時訂正: 前節の2026-10-14表記は記入誤り。実作業は2026-10-13 22:34〜22:49 JST (13:34〜13:49 UTC)。失敗内容・試験実績に変更なし。
- Next: 最新PR head再CI確認→main mergeとmain CI確認→4b-2d-2b-2 watermark独立PoC。製品導入承認待ち。

## Phase 4b-2d-2b-2 — 独立durable watermark / WORKING

- DurableWatermarkPrototype RLC1、SplitLockJournalPrototype.checkpoint、別JVM reader試験。既存原本/RLJ1/identity/製品Worker/SQLite/Server IFへ配線しない。
- WSL Linux JDK17 --release8: journal194チェックPASS (99+39+56)。dir force SYNCED観測。Windows/VPS、実物理電源断はNOT RUN。永続reader highwater未実装、tmp残存は自動修復せず停止。

### Phase 4b-2d-2b-2 現在のゲート — Windows watermark BLOCKED

- 初回journal Windows CI38409471808/38409589169 FAIL (fileKey null)。Linux full watermark56ケースPASS、Windowsはpublic fileKeyがないのでfullケースを実行できない。安全側に拒否し3件の停止/保全試験を追加。
- Phase 4b-2d-2b-3の合成SQLite結合はBLOCKED (両OS成立ゲート未達)。production foreground/rotation/DB/ACKに導入しない。代替設計とファイルセット保全条件を文書化する。

### 2026-10-14 09:46 JST — 中断復帰・最終区切り

- PR28最新CI38410669443/38410669276両OSsuccess。Linux journal197/SQLite344、Windows journal143/SQLite344。ただしWindows watermark56シナリオはBLOCKED/NOT RUN、storage primitive3+拒否/保全3のみ。directory force Windows UNCONFIRMED、Linux SYNCED観測 (物理電源断保証なし)。
- WSL legacy test.shは公式1.1.0-NOV26-2025 compile-only jarをignored lib/へ置いて44 PASS/0 FAIL。最初のjar欠落compile失敗も保存。実車/ECU/GUI/本番は未試験。
- 利用制限中断後、両repo cleanとPR28/40を確認して統合・VPS更新・報告書整備まで完了させる。新設計/2b-3結合へは進めない。

### 2026-10-14 09:51 JST — 最終検証済みの区切り

- Agent PR28 merged/main e3d76c22fba8fce7f41ad03f6f7c0b386a5794d9。main journal CI38492256469/spool CI38492256464の両OS全jobs/実ステップsuccess。
- VPS1最新テストf90f103でjournal197+SQLite/receipt344=541 PASS、実装main e3d76c2へ同期・clean。WSL legacy44 PASS。Windows journal143+SQLite344=487 PASS（watermark full56 BLOCKED/NOT RUN）。
- 実装はこのPoCまでで終了。2b-3 BLOCKED、次はWindows識別方式の独立設計/合成検証。設計/安全境界とreport/worklogを最終文書PRで同期。

## 2026-10-14 日中 — Phase 4b-2d-2b-3a / 比較中

- [比較設計](phase_4b_2d_2b_3a_identity_comparison.md)、D-041、[PR #31](https://github.com/zawa356/RomRaider_agent/pull/31)。A/C単独識別の反例を再現し、B native NTFS identityをテスト専用に比較。
- Linux journal222 PASS（旧197+比較25）。Windows実runner結果/SQLite最終結果はworklog追記待ち。3b/3c/3dは所有handle/永続ledger/directory durabilityゲート未達でBLOCKED。製品Worker/SQLite/ACKへ配線していない。

### 3a実試験 — 2026-10-14 10:31 JST

- Linux WSL/VPS journal222、VPS SQLite/receipt344（重複除外合計566）PASS。Windows Actions CI38495966977: journal168+SQLite344+native18=530 PASS。JNA bindingはtest-only x64/NTFS。Linux Windows-native stepは意図したSKIPPED。
- A/C反例を確定しBを次独立実験の候補へ。Windows watermark full56/dir永続化/x86は未成立、3b/3c/3dはBLOCKED。最新docs headのCI/main統合はworklog参照。

## Phase 4b-2d-2b-3a-compat — 安全な代替小Phase / WORKING

- [PR32](https://github.com/zawa356/RomRaider_agent/pull/32)、[設計](phase_4b_2d_2b_3a_compatibility.md)、D-042。同じnative READ handleにID/bytesを結び付けるtest-only診断と実Zulu8/x86試験を追加。Windows結果はworklog参照。
- 3a PR31/Server42 main CI38496452266/38496457020適用steps成功。3b/3c/3dは引き続きBLOCKED、製品DB/ACK/Workerへ配線なし。

### 3a-compat actual runtime result — 2026-10-14

- Windows actual Zulu8 1.8.0_504 x86 data-model32:109 checks (native18/boundREAD21/comparison25/split39/watermark safety6) PASS。x86 SQLite NOT RUN。X270/RomRaider同JVM/物理電源断はNOT RUN。
- Windows17 x64 journal168+SQLite344+native18+READ20=550 unique PASS。Linux journal222+SQLite344=566 PASS（VPS phase regressionはjournal222）。PR32 CI38496691143実steps success、最新docs-head/main結果はworklog参照。
- READはNOT_COMMITTED、legacy writer読取BLOCKED、full watermark56 NOT RUN/dirUNCONFIRMEDを維持。

## Phase 4b-2d-2b-3a-writer-binding — 安全代替 / WORKING

- [PR33](https://github.com/zawa356/RomRaider_agent/pull/33)、D-043、[設計](phase_4b_2d_2b_3a_writer_binding.md)。同一native write/flush/IDとquota外native guardをtest-only x64/x86で比較。marker/DB/製品配線なし。
- native ownership証拠が成立してもWindows dirUNCONFIRMED・persistent expected ID/highwater・新marker versionが未成立。3bの新version候補設計が次工程、3c/3dはBLOCKED。結果はworklogへ追記。

### 3a-writer-binding actual runtime results — 2026-10-14

- PR33 code96e4f15 CI38497582903全3jobs適用steps success、実ログ確認。Windows17/x64 writer44、Zulu8 1.8.0_504/x86 writer45 PASS。
- Windows17 combined594 unique checks、x86検証subset154 PASS。VPS Linux222+SQLite344=566 PASS。native writerはWindows専用、Linux nativewriter runtimeはNOT RUN。
- 原本ID/read/write/flushのnative test primitiveは成立。RLC1/dir同期/highwater/marker新版/rotation/本番移行は完成していない。最新docs-head/main/Server同期はworklog参照。

### 2026-10-14 日中最終verification

- 3小Phase Agent31/32/33とServer42/43/44全MERGED。コードmain Agentbd8e70b/Server5a9cdb3。Agent mainCI38498047914 attempt1はWindows依存JAR404でFAIL、attempt2実SQLite/native試験を含む全jobs success。Server main38498052348全適用steps success。
- 現行VPS main566合成checks、Windows17/x64594、actualZulu8/x86subset154、WSLlegacy44 PASS。Windows old RLC1 full56/dir sync/実車などNOT RUN/BLOCKEDを保持。[最新日中報告](handoff/codex_daytime_report.md)を正本の区切りにする。

## 3a-writer-integrity — 最終レビュー追加Phase / WORKING

- [PR36](https://github.com/zawa356/RomRaider_agent/pull/36)、D-044。flush後new payload/CRCが改変された場合にmemory Proofを拒否するtest-only修正。RED213fd22をWindows17/x64 CI38499434376で実再現、x86 RED NOT RUN。
- c72ad0dはexact written-frame比較を追加、GREEN/runtime結果待ち。過去のwriter44/45 PASSにはこの新負試験を含めない。RLC1/full56/dir/highwater/製品配線のゲートはそのまま。

### 3a-writer-integrity actual GREEN

- c72ad0d CI38499593483 all3jobs success。post-flush CRC-valid rewriteを拒否/保全/latchする新2項目PASS、x64 writer46、actualZulu8/x86writer47。combined x64596/x86subset156、Linux566。RED証跡と旧44/45範囲を保存。最新head/mainはworklog参照。

### 日中の最終integrity checkpoint

- Agent36/Server46 MERGED、コードmain126c174/8804caa、mainCI38500236200/38500240744全適用steps success。latest Windows17/x64596、actualZulu8/x86subset156、VPS/Linux566、legacy44。writerは46/47。
- 4小Phase（3a比較/compat/nativewriter/integrity）統合、D-041〜044。最新report/worklogは追加修正を含む。旧594/154は歴史的scope。次Phase3b設計へ、directory UNKNOWN/full old watermark56/ledger/highwater/rotation/本番配線は未成立。

## 2026-10-18 acceleration Phase A/B — isolated diagnostics (CI pending)

Read-only RLC2/ledger/RLM1 Supervisor diagnostics implemented; product Worker/ACK paths unchanged. Real SQLite preservation test caught and fixed WAL/SHM side effects. Windows x64 34 PASS; x86/Linux/CI pending. Closed checkpointed DB prerequisite; active DB fails closed. Details: acceleration_diagnostics.md / handoff/codex_acceleration_worklog.md.


## 2026-10-18 11:54:12 +09:00 — acceleration Phase C (verification in progress)

Executable Rlc2ProductAdapter projects validated source receipts through existing v1 converter in memory. Explicit synthetic gate; no Worker/outbox/HTTP/ACK integration. Windows x64 20 PASS, other environments pending. Current single-source product claim is incompatible with physical rotation; quarantine persistence planned in Phase D. Details: rlc2_product_projection.md.


## 2026-10-18 12:10:37 +09:00 — acceleration Phase D (CI pending)

Executable isolated source→RLC2→ledger→RLM1→shared v1 projection→SQLite preview→restart/replay path added, plus loopback test PostgreSQL verification. Dedicated application/schema ownership, atomic history/claims/cursors/receipts/records, immutable replay and CHECK ACK false. Actual local Server model JSON validation PASS without endpoint calls. Production Worker/claim schema/ACK routing unchanged. See rlc2_preview_e2e.md and acceleration worklog for per-environment counts and Linux harness RED/GREEN.


## 2026-10-18 12:22:40 +09:00 — acceleration Phase E (CI pending)

Isolated output restart bug reproduced with abrupt JVM stop, then fixed with header/app-ID/schema-gated SQLite rollback recovery. No input repair or ACK routing. Faults cover all transaction stages, competing CLI, DB lock/insert failure, no partial rows and preserved source evidence. Windows x64 fault36 PASS; other environments/CI pending. Operator dry-run/retry boundaries documented in rlc2_preview_e2e.md.


## 2026-10-18 12:37:03 +09:00 — acceleration Phase F (verification running)

Pinned Java/Python/PG CI artifacts and Windows one-command x64/x86/full regression with optional guarded PostgreSQL are implemented. Existing scripts stay cross-platform. CI checks generated artifacts and executes the actual Windows batch. See acceleration_test_automation.md / handoff/codex_acceleration_worklog.md for live proof and limits; no product sending gate changed.


## 2026-10-18 13:18:30 +09:00 — acceleration Phase G (final CI pending)

Manual next-action diagnostics, non-mutating source-only migration preflight, mixed-format/ledger-lag guards and real multi-session workload/replay added. RLC1 fileKey hash never promoted to native identity; ACK/repair disabled. Large10k/11-session tests pass on Linux17 and actual Zulu8 x86; identical-hex optimization retains old-ledger compatibility. Current product claim/cursor/directory-durability gates remain explicit. Details: ingress_preflight_load.md.


### 2026-10-18 — Preview checkpoint integrity follow-up
- Phase H independently verifies committed source receipt boundaries and the exact projected record ID set before replay. Unknown/missing committed rows are retained for review, including unchanged-count event key replacement. Normal append/rotation remain supported. See rlc2_preview_e2e.md and D-056; no production ACK enablement.

## 2026-10-18 — Codex acceleration final checkpoint
- Phase A–H code baseline e1677c4 (PR43–49); final main journal39124576935/product39124576968 success. Read-only Supervisor diagnostics, default-off synthetic v1 projection, isolated SQLite/PG preview, recovery tests, pinned automation, migration preflight/load and checkpoint identity validation are complete within their documented scope.
- Full report and next product integration design: [codex_acceleration_report.md](handoff/codex_acceleration_report.md). Product automatic send/formal ACK/schema migration remain gated; directory durability UNKNOWN and physical power loss/real ENOSPC NOT RUN.

## Phase I final candidate integration checkpoint — 2026-10-18
- I-1–I-7 Agent PR52–58 normally merged; code main aa55dcd007b48728a320448fb827049254fcb6fd, tested tree be08629. Executable RLI2 versioned schema, admitted multi-source atomic engine, quiescence/snapshot guards, strict schema/Supervisor axes, explicit CLI and actual Server HTTP/owned PG process-failure verification.
- Full Windows actual64/32 batch exit0; fresh VPS entire stack/10k and actualx86 10k PASS; actual HTTP35 including owned PG stop/restart and Server57 PASS. Actions could not schedule; user selected local/VPS verification, CI NOT RUN.
- Full evidence/gates/resume: [codex_product_integration_report.md](handoff/codex_product_integration_report.md). Existing Worker/schema/send/ACK not automatically replaced; live coexistence and production adoption remain blocked, directory durability UNKNOWN.

## 2026-10-19 — Phase II cooperative ownership candidate

Agent PR60–68: RLLO1 retained common logical Lane, exact legacy Worker compatibility lock, opt-in TSV/Journal/spool scopes and precommit checks, synthetic Writer publication/snapshot boundary, strict copy-only v1 inventory migration, real JVM halt/recovery, advisory status/manual recovery and actual Worker/separate-Engine process competition. Final implementation head82e1d54; normal merge SHAs in [final report](handoff/codex_phase2_report.md).

Windows x64/actual Zulu8x86/Linux focused26ownership/19migration/6publication/21fault/7Enginecompetition PASS; product and real Worker conflicts/retry PASS. Full Windows batch acceleration-20261019-095142 exit0, Linux broad suite and cooperative10k load59/actualx8660 PASS. Owned actual FastAPI/PG HTTP35 and Server pytest57 PASS. [Evidence/log digests](handoff/phase2_validation_evidence.md). Actions runner blocked by account payment/spending annotation, steps=[]: CI NOT RUN.

No automatic installation/replacement, production schema migration, live Logger/ECU wiring or new formal ACK. Common-root/cooperating-process guarantee only; old unknown importers/noncooperative Writers/duplicate restored roots remain outside it. Windows directory durability UNKNOWN; physical power loss/real ENOSPC/X270 NOT RUN. Migration is isolated inventory/preservation candidate, not RLI2 production record conversion.

## 2026-10-19 Phase III — 固定配置・切替前診断・隔離copy受入

Agent PR [70](https://github.com/zawa356/RomRaider_agent/pull/70)/[71](https://github.com/zawa356/RomRaider_agent/pull/71)/[72](https://github.com/zawa356/RomRaider_agent/pull/72)/[73](https://github.com/zawa356/RomRaider_agent/pull/73) merged。実装main `c206282119c424c3dd15c66b5cddfb5713d69fac`、独立最終検証head `ed8efd6e6195418feffe77224ef61f2116a6db7b` とsrc/scripts/.github差分0。最終文書は別PR。

- **受入済み:** RLDB1 paired root/旧DB anchor、canonical identity/ACL/owner/hardlink/alias検証とread-only JSON CLI、bound RLI2 explicit synthetic entry。readinessの旧Worker実lock保持拒否と所有子process終了後明示retry。RLBA1役割/hashによるinactive copied-target検査、RMI1全表/rowid/ACK/claim/cursor/receipt/retry/review状態保全、schema-reference自己所有一時fileのみcleanup。
- **故障境界:** output前/owner作成後/実copy途中/copy後/commit前/commit後の6子JVM halt、partial/owner-only保持、SQLite BUSY/source変更、full rehearsal/first-artifact backup割込み。ownerと全recovery sidecarのalias検査をwritable recovery前へ追加し、別合成DBが変更されたREDを修正。診断はsource/target/owner/lock作成・repairをしない。
- **実検証:** Windows full batch exit0、JDK17 x64と実Zulu8 x86でbinding18/readiness11/backup32/stages52、migration21/CLI2・負試験PASS。Linux17同等（binding17）PASS。bound10,003recordsを3runtime、Linux旧候補10,000observations＋10小Session load59、実HTTP/owned PG35、Server PG pytest57/1warning PASS。詳細は [Phase III報告](handoff/codex_phase3_report.md) と [ログhash/コマンド](handoff/phase3_validation_evidence.md)。CIはsteps0/account limitでNOT RUN。
- **部分完了/未成立:** 固定logical Session/manifestの配置だけを受入。manifest置換/次Session/rebind authorityと本番restore/cutover、実process/配布物完全provenance、別host/clone/networkFS global fence、RMI1→RLI2 record/physical receipt変換は未成立。旧wire JSON/claim/cursorだけからoriginal UUID/manifest/frame bytesを捏造しない。
- **NOT RUN/無効:** 正式ACK/新Sender常駐/既存Worker自動停止・切替/本番migration/公開deploy/実車・ECU。Windows directory durability UNKNOWN、物理電源断/実ENOSPC未試験。旧RLC1full56とroot test.sh/privileged symlink gateも継続。Docker14/既存PG稼働、owned試験終了、失敗fixture保全。

次回最優先は、停止・外部単一稼働が成立した環境の再binding権限契約と、元RLC2原本provenanceに基づくRMI1/RLI2対応契約。Bootstrap→Phase III報告→本台帳から再開する。
