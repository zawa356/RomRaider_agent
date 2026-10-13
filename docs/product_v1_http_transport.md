# 製品版 Agent–Server v1 HTTP送信実装（Phase 2.1-B）

この文書は**製品版の実装状況**を説明する。通信契約の正本は
[Server–Agent wire v1](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/server_agent_wire_v1.md)、
OpenAPI正本は[こちら](https://github.com/zawa356/RomRaiderServer/blob/main/docs/interfaces/agent_api_v1.openapi.json)。

## 責務と適用範囲

```text
[後続工程: RRBridge Logger callback]
          ↓  ※Phase 2.1-Cまでは未接続
[DurableSpool / SQLite: 原本ID, run, profile, 未ACK保全]
          ↓
[ProductSender.pumpOnce(): 1回のHTTP交換]
          ↓ profile PUT（定義を先行登録）
          ↓ telemetry POST（1 run / 最大128件）
[Server v1 HTTP API: commit完了後に全件ACK]
          ↓
[SQLiteへバッチ全体の受領記録 → ACK済JSON本文を解放]
```

現在、`ProductSender`は**独立コンポーネント**であり、`Agent.java`や`Stream.java`から自動起動されない。送信のスケジューリング・バックオフ・旧PoC移行も実装していない。したがって、X270や実ECUから既に製品Serverへ自動転送されているとは解釈しないこと。

## 実装済みのAPI通信

| 操作 | HTTP | 送信条件と正常応答 |
| --- | --- | --- |
| ProfileRevision登録 | `PUT /api/agent/v1/profile-revisions/{uuid}` | 保存済みのprofile原文JSONを送信。`200/201` + `registered/already_registered` + 要求と同じIDの場合だけProfile ACKをSQLiteに記録 |
| Telemetryバッチ送信 | `POST /api/agent/v1/telemetry/batches` | Profile ACK済み観測のみ、同一run、最大128件。Server `200` + `status=committed`、送信時の`batch_id`、件数・inserted/duplicateの合計一致でのみ全件ACK |
| 通信断・503・429 | 両方 | `RETRY_LATER`、**元のJSON・UUIDは保持** |
| 401/403・409/422・413・不正な2xx本文 | 両方 | `NEEDS_REVIEW`、**元のJSON・UUIDは保持**。認証・契約違反の解決なしに無限再送しない |
| 3xx転送先変更 | 両方 | リダイレクト追従禁止、保全して`NEEDS_REVIEW` |

`ProductSender.pumpOnce()`は1回の呼出しで最大1回のHTTPリクエストだけを実行し、`PROFILE_ACKED`、`BATCH_ACKED`、`RETRY_LATER`、`NEEDS_REVIEW`、`IDLE`の結果を返す。タイマー・暗黙のループは持たない。認証トークンはメモリにのみ渡し、平文をログ・リポジトリ・エラー本文に出さない。

### セキュリティ

- 外部接続は**HTTPS必須**。開発検証用に`localhost`／`127.0.0.1`／`::1`のloopbackだけHTTPを許容する
- Java標準の証明書検証を使用。TLS検証を無効にするオプションはない
- 送信先のリダイレクトは追従しない。URLの埋込認証、パス付きベースURL、改行を含むtokenを禁止
- リクエスト本文は512 KiB、レスポンス本文は32 KiB以内に制限

## CIで確認すること

[GitHub Actions](../.github/workflows/product-v1-spool-ci.yml)で、`scripts/test-product-v1-spool.sh`からWindows／Linux双方のJava 8互換コンパイルと、**実SQLite＋ローカルHTTPモック**を利用して次を試す。

- Profileを観測データより先に送信し、レスポンスのID・登録状態を確認
- 送信失敗、資格情報エラー、契約違反、過大リクエスト、リダイレクトで未ACK原本を保持
- 不正な`committed`、他バッチID、異なる件数、重複したJSONフィールドをACKとして受理しない
- 「Serverがcommitしたが応答が届かない」を模擬し、SQLite再起動後に同じrecord IDを再送、duplicate-onlyの成功ACKで確定
- HTTPS以外の外部URLを拒否する（実ネットワーク上のTLS通信・証明書設定は未検証）

## 次フェーズに残す事項

1. **Phase 2.1-C**：既存Java Agentの合成イベントを製品v1の完全なwire JSONとしてSQLiteへ書き、非同期送信へつなぐ。Logger callbackにHTTPやSQLite待ちを追加しない
2. **Phase 2.1-C/2.1-D**：本物のProduct Server（FastAPI/PostgreSQL）とAgent（Java8）を同一試験環境で起動したend-to-end転送を確認。現在の模擬受信サーバーのみで実Server E2E合格とはしない
3. バックオフ、Retry-After、413の分割・409/422の原因レコード隔離、直近／過去データ公平送信、容量警戒、SQLite破損・disk-full試験、常駐／電源断復旧
4. `appendRecord`はすでにシリアライズしたJSONを保存するため、**原本JSONのServer schema整合は次フェーズで生成段階から保証**する。デモ用の不完全なJSONを本番送信しない
5. Windows X270上のZulu 8（32bit）／RomRaider同一JVM／実ECU通信と、安全な自動プロファイル切替は未検証

認可・ECU書込みなどの安全ルールは従来どおり維持する。
