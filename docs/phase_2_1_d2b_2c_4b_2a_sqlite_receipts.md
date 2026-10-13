# Phase 2.1-D2b-2c-4b-2a：ジャーナル固有IDと実SQLite受領台帳

**範囲：独立PoC。製品v1変換・送信経路、旧TSV writer、ECU/Logger callbackには未接続。**

## 責任境界とデータフロー

```text
[既存の本番相当経路：今回変更なし]
Logger callback → memory queue → TSV writer → IncrementalTsvBridge → v1_records(SQLite) → Server

[独立PoC：明示テスト時のみ]
journal(前段RLJ1ファイル) + journal.identity(RLI1 UUID/CRC)
      → CRC/seq/UTF-8と受領済みprefixの整合性検査
      → proto_ingress_rows(SQLite, immutable receipt) + proto_ingress_cursor
        └ 両者を単一SQLiteトランザクションで確定（WAL / synchronous=FULL）
      × 未実装：原本M/S/E→製品v1プロフィール/観測レコード変換とServer ACK
```

## 実装仕様

- `JournalIdentityPrototype.open(File)` は**既存の空ファイル**からID作成を始める。別サイドカー `.identity` にランダムな正規化UUIDとCRC32をCREATE_NEW・FileChannel.force(true)で一度だけ記録する。再起動時は既存IDのみを使う。
- 非空ジャーナルに対応するサイドカーがない場合、不完全・破損・シンボリックリンクの場合は**自動採番／修復せず拒否する**。UUIDの別コピー等による重複には運用上の危険がある。
- `JournalSqliteReceiptPrototype` は**本番のv1_recordsではない**新しいPoC受領表`proto_ingress_rows`と`proto_ingress_cursor`のみ使用する。ジャーナルの全フレームを検査後、取り込んだ行の不変UUID・原文SHA-256を照合する。source UUIDとsequenceから決定的record UUIDを生成し、既存HandoffのID生成規則と一致させる。
- 新規レコードと`committed_seq`を同一SQLiteトランザクションで保存。保存直前に失敗すれば全体rollback、SQLite commit成功後に呼出側が停止した場合は再起動後に重複挿入しない。原本ジャーナル・サイドカー・受領表を勝手に削除しない。
- journalが途中で短縮、prefixが置換、原本の整合性が失われた場合はfail closed。取込済みprefixの全照合とJournal.replay()を行うため、4aの8MiB上限を超える長期運用・性能目標には適していない。
- 永続IDは**journal本体ではなく同じフォルダの別ファイル**。サイドカー名・ファイル配置・バックアップ/ローテーションの原子的な管理は未確定。ディレクトリエントリのfsync／Windows x86／物理電源断は未検証で、JVMクラッシュ耐性と同一視しない。
- 同期fsync/SQLite I/OをECU callbackへ繋がない。バッファ受理時点のvolatile欠損、容量逼迫時の安全停止、Product v1でのプロファイル解析とACK連動は未解決。

## CloudPC検証

専用VPSの`jr2twt`ユーザーに作成した独立cloneで実施。実車、既存Caddy/MCP、Serverプロセス、実ディスクのdisk-fullには触れない。

- `bash scripts/test-ingress-journal-prototype.sh`: 前Phaseのジャーナル/Handoff 34+41=**75件PASS**。
- `bash scripts/test-ingress-sqlite-receipts.sh`: 製品v1既存回帰試験を実SQLiteで実行したうえで、新しい受領台帳**22件PASS**。
- 新規22件には、異なるjournalごとのUUID、ID再起動維持、2件のみ取込とcursor、再取込時の重複防止、checkpoint前の故障rollback、別JVMのSQLite commit直後Runtime.halt(77)／再起動確認、サイドカー欠落／CRC破損、journal prefix改変の拒否を含む。
- 初回の試験はサイドカーCRC文字を固定の`0`へ変更するfixtureが既に`0`なら破損しないため失敗した。元値と異なる値に必ず変更するよう修正し、全試験PASS。失敗履歴を保持する。
- [PR CI #38381013007](https://github.com/zawa356/RomRaider_agent/actions/runs/38381013007)および[main CI #38381208274](https://github.com/zawa356/RomRaider_agent/actions/runs/38381208274)でUbuntu/Windows両OSの既存ジャーナル・製品v1実SQLite回帰＋受領台帳試験がすべて成功。テストで新設したテーブルは製品APIの保証を意味しない。

## 後続のPhase 4b-2b（未実装）

1. Sidecarの独立した寿命と原本journalの同一性・更新禁止の保証（OS差とdir fsyncを含む）。
2. 既存`IncrementalTsvBridge`のプロフィール/セッション/観測ID契約と衝突させず、journal原文→製品`v1_records`／`v1_profiles`への変換を設計・試験。
3. 入力原本／receipt／製品record／Server ACKそれぞれの「保存成功」の意味、二重投入／中断点／カーソルをE2Eで検証。
4. 32bit Windows、容量逼迫、継続I/O停止、リアルタイム負荷、停車時の安全検証まで製品Agentには接続しない。

**判定：永続識別子＋実SQLite受領行／cursorの原子的境界は合成試験でPASS。製品の観測レコード変換・無欠損保証・実車投入はNOT RUN。**
