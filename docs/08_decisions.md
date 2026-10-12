# 08. 決定記録（ADR）

形式: 背景 / 選択肢 / 決定 / 理由 / 結果・影響 / 状態。新しい決定は末尾に追記し、覆すときは新しい番号で「D-xxx を置き換える」と書く。

---

## D-001 作業環境を ChatGPT Work から Claude Cowork へ移す（2026-10-12）

- 背景: ChatGPT のWorkモードの調子が悪い [ユーザー発言]。
- 決定: このプロジェクトは Claude Cowork（Claudeのプロジェクト「【RomLogger】AI連携」）で進める。引継ぎ資料はClaude側でも更新し、ChatGPTのLibraryにはユーザーが反映する。
- 影響: ClaudeからX270にリモート接続して実機作業ができるようになった。
- 状態: 有効。

## D-002 RomRaider の外部制御は `-javaagent`（RRBridge）方式とする（2026-10-12）

- 背景: 引継ぎ資料では「XML＋再読込／Java側に制御API追加／UI操作」の3案が未選択だった。
- 選択肢:
  1. 設定・プロファイルXMLを外部で書き換えて再読込 → 実行中のRomRaiderが終了時に上書きし競合する。再読込を外部から発火できない。
  2. プラグイン機構 → インスタンスは取れるが、クラスパスへの追加か Program Files への書き込みが必要。作業ディレクトリ変更は相対パス依存で不可。
  3. RomRaiderのソース改造・再ビルド → 本体更新への追従が難しい。
  4. UI操作（画面クリック、Java Access Bridge） → 遅く壊れやすい。無人運用に向かない。
  5. **`-javaagent`** → 作業ディレクトリもクラスパスも変えずに同じJVMへ入れる。`EcuLogger.getEcuLoggerWithoutCreation()` でインスタンスを取得できる。
- 決定: 5。公開メソッドを優先し、必要最小限のprivateフィールドだけリフレクションで扱う。
- 結果: 実機で status／logging／filelog／profile の操作を確認。RomRaiderのバージョン依存（`04_romraider_internals.md` §1）が残る。
- 状態: 有効。

## D-003 RRBridge の配置は `Documents\OpenPortWork\RRBridge`、起動は専用バッチ（2026-10-12）

- 背景: Program Files は一般ユーザーで書き込めない。RomRaiderの作業ディレクトリはインストール先である必要がある。
- 決定: jar等はユーザーフォルダに置き、`start-romraider-with-bridge.bat` が `cd /d <インストール先>` してから `-javaagent:"<RRBridge>\rrbridge.jar" -Drrbridge.dir="<RRBridge>"` 付きで公式と同じ引数で起動する。
- 影響: **通常のショートカットから起動するとRRBridgeは読み込まれない**（実際に一度起きた）。運用ではショートカットを差し替えるか、Supervisorから起動する。
- 状態: 有効。

## D-004 コマンドの受け口はファイル（inbox/outbox）とHTTP（127.0.0.1:18765）の2経路（2026-10-12）

- 理由: ファイル経由は、外部（クラウド側のAI、別プロセス）からマウント経由でも試験でき、プロセスが落ちても指示が残る。HTTPは後段のSupervisor／MCPから扱いやすい。処理は `Agent.execute()` に一本化。
- 制約: HTTPは認証なし（localhost限定）。同じPC上の任意のプロセスから操作できる。
- 状態: 有効。

## D-005 プロファイルは `RRBridge\profiles\` のファイル名だけを受け付ける（2026-10-12）

- 理由: 任意パスを受け付けると、LLMやネットワーク経由の指示で意図しないファイルを読ませられる。計測計画の「承認済みの範囲」をファイルの配置で表現できる。
- 実装: `[A-Za-z0-9_.-]+\.xml` かつ実在。範囲外は拒否を実機確認。
- 状態: 有効。

## D-006 計測区間の切替は `switch_profile`（記録停止→読込→記録再開）で行い、1区間1CSVとする（2026-10-12）

- 背景: CSV記録中に `loadUserProfile` すると、項目の登録・解除のたびにヘッダー行が追記され、1つのCSVに10行のヘッダーが並んだ（実機）。
- 決定: 記録中なら停止→読込→1.5秒待機→再開。区間＝CSVファイル＝segment として扱える。
- 影響: 切替のたびに短い欠測が出る（ECU通信中の長さは未測定）。転送ストリーム側は止めないので、ストリームには中間状態のsegmentが記録される。
- 状態: 有効。

## D-007 CSVを経由せず、ロガー内部の配信から直接データを取り出す（2026-10-12）

- 背景: ユーザー要求「ログを取りながら転送したい」。CSVはバッファリングされ、全項目が揃った行しか書かれず、プロファイル切替でヘッダーが崩れる。
- 決定: `QueryManagerImpl.updateHandlers` に自前の `DataUpdateHandler` を追加する。1応答＝1サンプル、換算済みの値、`ResponseImpl` の生成時刻を使う。
- 影響: CSV記録のオン・オフと無関係に、ロギング中は常にストリームが流れる。実ECUでの到着は未確認。
- 状態: 有効（実車確認待ち）。

## D-008 転送は「ローカルspool（正本）→ HTTP受信API → DB」、DBへの直接書き込みはしない（2026-10-12）

- 選択肢: (a) RomRaider/RRBridgeからJDBCでMariaDB/MySQLへ直接、(b) 受信APIを挟む。
- 決定: (b)。
- 理由: DBをインターネットに公開しない。通信断時の再送、重複排除（`session+seq`）、検証、認証（Bearerトークン）を受信側で一括管理できる。車載PCにDB資格情報を置かない。送り先は `sink.properties` の書き換えだけで切り替えられる。
- 結果: 受信停止中のデータを復旧後に全件再送できることを確認。
- 状態: 有効。

## D-009 転送形式はTSV（M/S/E行）、縦持ちで保存（2026-10-12）

- 理由: エージェントに外部ライブラリを入れない（JSONライブラリなし）。行単位でspoolに追記・途中から再送しやすい。プロファイルごとに列が変わるので縦持ち（`measurements(sample_id, param_id, value)`）。
- 状態: 有効（本番受信APIでも同形式を受ける想定。変更する場合は互換を考える）。

## D-010 検証用受信サーバーは X270 内の Java＋SQLite（2026-10-12）

- 理由: X270上にPythonがあるか不明、同梱のJava 8は確実に動く。sqlite-jdbcはWindows x86ネイティブを同梱しJava 8対応。
- 制約: RomRaiderのクラスに依存させない（`rrreceiver.jar` を分離。D-010の実装中にClassNotFoundを踏んだ）。
- 状態: 試作。本番はVPS上の別実装に置き換える。

## D-011 サーバーはVPS 1台を推奨（提案のみ、未決定）（2026-10-12）

- 提案: Ubuntu Server 24.04 LTS、Caddy（443のみ、自動TLS）、PostgreSQL（`statement_timeout` 付き読み取り専用ロール）、Python（受信API＋MCP同居）、Docker Compose。自宅PC＋Cloudflare Tunnelも同じ構成で可能。
- 前提調査: MCPはAI各社のクラウドから接続するため公開必須、VPNだけでは届かない。
- 状態: **ユーザー未決定**。

## D-012 音声UIの第一候補は Claude モバイル（調査段階）（2026-10-12）

- 背景: 引継ぎ資料では Android ChatGPT Live＋自作Remote MCP を想定。
- 調査結果: ChatGPTの自作MCPはWeb専用（モバイル非対応）でBusiness以上（Proは読み取りのみ）、音声モードで失敗する報告あり。Claudeはカスタムコネクタを個人プランで追加でき、音声モードは接続済みツールに対応（カスタムコネクタは未明記）。
- 次: ダミーMCPで Claude Android 音声から呼べるか実機検証。
- 状態: 調査段階。

## D-013 GitHub `zawa356/RomRaider_agent`（private）で管理、jarはコミットしない（2026-10-12）

- 決定: ソース、バッチ、プロファイル、ドキュメントを管理。`RomRaider.jar`・`sqlite-jdbc.jar` はライセンス・サイズの都合で `lib/` に手動配置（`.gitignore`）。実行時生成物と `sink.properties` も除外。
- 注意: RomRaiderはGPL v2。公開・配布時はライセンスを確認。
- 状態: 有効。

## D-014 読み取り系と操作系を分け、読み取り系はロックなし・GET可にする（2026-10-12、v0.4.0）

- 背景: v0.3.0 は `execute` 全体が `synchronized`、HTTPは単一スレッドで、`switch_profile` の待機（1.5秒以上）中は `status` も返らなかった。ドキュメントでは `stream_status` をGETで書いていたが実装はPOSTのみだった。
- 決定: `READ_ONLY = {status, stream_status, list_profiles}` はロックなし・GET可。操作系は `CONTROL_LOCK` で直列化。HTTPは4スレッド。
- 状態: 有効。実機確認済み。

## D-015 `logging_stop` はCSV記録中なら先に記録を止める（2026-10-12、v0.4.0）

- 理由: ロギングだけを止めると「Start file log」ボタンが押されたまま残り、表示と実態がずれる恐れがある。後段から見て「止めたら全部止まる」方が扱いやすい。
- 状態: 有効。実機で `logging:false, fileLogging:false` を確認。

## D-016 RomRaider非依存の自己試験を持つ（2026-10-12、v0.4.0）

- 決定: JUnit等を入れず、`SelfTest.java`（main）と `test.sh` で、GUIなしで動く範囲（転送・受信・重複排除・認証・掃除・検証）を確認する。RomRaider連携部分は実機の手動試験（`06_verification.md`）で補う。
- 理由: 依存を増やさない方針（D-009）と、RomRaider.jarをリポジトリに含めない方針（D-013）のため、GitHub ActionsでのCIは現状できない（RomRaider.jarを取得する手段が必要）。
- 状態: 有効。

## D-017 HTTPの認証は任意（既定オフ）（2026-10-12、v0.4.0）

- 理由: localhost限定だが、同じPCの他プロセスから操作できる。Supervisor導入時にトークンを設定する想定。既定オフにしたのは、現行の手動試験・ファイル経由の操作を変えないため。ファイル経由（inbox）は認証対象外（フォルダのアクセス権で守る）。
- 状態: 有効。
