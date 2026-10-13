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
| 2.1-A | Java 8対応SQLite durable spool、原本・定義とACK台帳 | **実装／CI結果確認中** | [Issue #7](https://github.com/zawa356/RomRaiderServer/issues/7) |
| 2.1-B | Server v1プロファイル登録・バッチJSON送信・ACK検証・再送 | 未着手 | Issue #7 |
| 2.1-C | 既存RRBridgeから製品spoolへ取り込み（合成PoC first） | 未着手 | Issue #7 |
| 2.1-D | Windows再起動／通信断／ディスク不足／実機での耐久検証 | 未着手 | Issue #7 |
| 3 | ECU→RomRaider→Agent→Serverの停車状態での実車検証 | 未着手 | [Issue #8](https://github.com/zawa356/RomRaiderServer/issues/8) |

## 変更履歴

### 2026-10-13 — Phase 2.1-A（検証中）

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
- PRとCI実行URLは合格・統合後に追記
