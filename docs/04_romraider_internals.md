# 04. RomRaider 内部の調査結果

対象: **RomRaider 1.1.0 NOV26 2025 Build 1227**。X270のインストール先 `C:\Program Files (x86)\RomRaider\src\main\java\` に同梱されたソースを2026-10-12に読んだ結果。行番号はこの同梱ソースのもの。
ChatGPT時代（2026-10-10以前）には公開GitHub `RomRaider/RomRaider` の `1.1.0-NOV26-2025` タグも調査している（引継ぎ資料 §5）。

**RomRaiderを更新したら、§1の表の項目がまだ存在するか必ず確認すること。** RRBridgeはこれらに依存している。

## 1. RRBridgeが依存している箇所

| 対象 | 種別 | 場所（行） | 使い方 |
| --- | --- | --- | --- |
| `EcuLogger.getEcuLoggerWithoutCreation()` | public static | `logger/ecu/EcuLogger.java` 304 | ロガー本体の取得（`private static EcuLogger instance` 302） |
| `EcuLogger.startLogging()` / `stopLogging()` / `isLogging()` | public | 1842 / 1846 / 1838 | `controller.start()` / `stop()` / `isStarted()` を呼ぶだけ |
| `EcuLogger.restartLogging()` | public | 1712 | stop→start（RRBridgeでは未使用） |
| `EcuLogger.loadUserProfile(String path)` | public | 684 | プロファイル読込。成功時タイトルに `Profile: <短縮名>` |
| `EcuLogger.getCurrentProfile()` | public | 907 | 未使用 |
| `EcuLogger.getSettings()` | public | 1983 | `Settings.getLoggerOutputDirPath()`（`Settings.java` 596）でCSV出力先 |
| `EcuLogger.getTitle()` | JFrame継承 | — | 状態表示 |
| `EcuLogger.controller` | **private** `LoggerController` | 243 | リフレクション |
| `EcuLogger.logToFileButton` | **private** `JToggleButton` | 287（生成 1460、Listener 1471〜1483） | リフレクションで取得し `doClick()` |
| `EcuLogger.fileUpdateHandler` | private `FileUpdateHandlerImpl` | 266 | 直接は触らない（ボタン経由） |
| `LoggerControllerImpl.queryManager` | **private final** `QueryManager` | `comms/controller/LoggerControllerImpl.java` 38（生成 43） | リフレクション（読むだけ） |
| `QueryManagerImpl.updateHandlers` | **private** `DataUpdateHandler[]` | `comms/manager/QueryManagerImpl.java` 88 | リフレクションで配列を差し替え |
| `QueryManagerImpl.dataUpdater` | private `AsyncDataUpdateHandler` | 87（生成 280） | 稼働中なら `handlers` も差し替え |
| `AsyncDataUpdateHandler.handlers` | package-private `DataUpdateHandler[]` | `comms/manager/AsyncDataUpdateHandler.java` | リフレクション |
| `DataUpdateHandler` | interface | `logger/ecu/ui/handler/DataUpdateHandler.java` | `registerData` / `handleDataUpdate(Response)` / `deregisterData` / `cleanUp` / `reset` |
| `Response` | interface | `comms/query/Response.java` | `getData()`（`Set<LoggerData>`）、`getDataValue(LoggerData)`、`getTimestamp()` |
| `ResponseImpl` | class | `comms/query/ResponseImpl.java` 30〜33 | `timestamp = System.currentTimeMillis()`（応答オブジェクト生成時） |
| `LoggerData` | interface | `definition/LoggerData.java` | `getId()`、`getName()`、`getSelectedConvertor()` |
| `EcuDataConvertor.getUnits()` | interface | `definition/EcuDataConvertor.java` 31 | 単位 |

## 2. データ配信の流れ [コード調査]

1. `EcuLogger` コンストラクタ付近（436〜448行）で各ハンドラーを生成し、`new LoggerControllerImpl(ecuInitCallback, this, liveDataUpdateHandler, graphUpdateHandler, dashboardUpdateHandler, mafUpdateHandler, injectorUpdateHandler, dynoUpdateHandler, fileUpdateHandler, TableUpdateHandler.getInstance(), DataflowSimulationHandler.getInstance())` に可変長引数で渡す（9個）。
2. `LoggerControllerImpl` が `new QueryManagerImpl(…, dataUpdateHandlers)` を生成し、`QueryManagerImpl.updateHandlers` に配列として保持。
3. ロギング開始時（`QueryManagerImpl` 276〜281行）に、稼働中の `dataUpdater` を止めて `new AsyncDataUpdateHandler(updateHandlers)` を作り `start()`。**このため `updateHandlers` を差し替えれば次回以降のロギングに反映される。**
4. 問い合わせループ `runLogger()` → `sendEcuQueries()`（370行）→ `handleQueryResponse()`（394行付近）→ `buildResponse()` → `dataUpdater.addResponse(response)`（399行）。
5. `AsyncDataUpdateHandler.run()` は `Vector<Response>` を16msごと（コメント「60 FPS」）に取り出し、全ハンドラーの `handleDataUpdate(r)` を順に呼ぶ。
6. `DataUpdateHandlerManagerImpl`（`addHandler` を持つ）はMAF・Injector・Dynoなどのタブ用で、上記の主経路とは別物。ここにハンドラーを足してもデータは来ない。

## 3. CSV出力の挙動 [コード調査＋実機確認]

- `FileUpdateHandlerImpl.handleDataUpdate()`（89行）: `fileLogger.isStarted()` のときだけ、各 `LoggerData` の値を `getSelectedConvertor().format(value)` で文字列化して現在行に入れ、**行が全項目そろった（`isFull()`）ときだけ**書き出す。
- `FileUpdateHandlerImpl.start()`／`stop()`（130／139行）がCSVファイルの開閉。`registerData`／`deregisterData`（78／102行）で項目が増減するとヘッダーを書き直す → **記録中にプロファイルを替えると、項目の追加・削除のたびにヘッダー行が追記される**（実機で10行のヘッダーが並んだCSVを確認。`06_verification.md`）。
- `FileLoggerImpl`: ファイル名 `romraiderlog_yyyyMMdd_HHmmss.csv`（任意プレフィックスあり）、バッファ付き書き込み、時刻は相対ミリ秒（設定 `absolutetimestamp="false"`）または絶対時刻。
- CSVの列順はプロファイルXMLの記載順ではない（登録順。実機で確認）。
- ロギング中でもECU無応答ならCSVはヘッダーのみ（0行）で作られる（実機確認）。
- CSV記録はボタン以外に**スイッチ連動**がある: 設定 `<filelogging … switchid="S20" active="true">` のとき、ECUのスイッチ（S20＝リアデフォッガー）の状態で `fileUpdateHandler.start()/stop()` が呼ばれる（`EcuLogger.initFileLoggingController` 700〜720行、`QueryManagerImpl` 371〜375／394行）。X270ではこれが有効。RRBridgeのCSV制御と競合するかは未検証。

## 4. プロファイル（ユーザープロファイルXML） [コード調査]

- 読込: `UserProfileLoaderImpl`（旧 `profile.dtd` 互換処理あり）、`UserProfileHandler` が `profile`、`parameter`、`switch`、`external` 要素をパース。属性 `protocol`、`id`、`units`、`livedata`、`graph`、`dash`。選択は値 `selected`。
- 保存: `UserProfileImpl`（124〜154行）。エンコーディング **ISO-8859-1**。`<profile protocol="SSM">` の下に `<parameters>`、`<switches>`、`<externals>`。選択されているものに ` livedata="selected"`、` graph="selected"`、` dash="selected"` が付く。
- RomRaiderは終了時に `~/.RomRaider/profile_backup.xml` へ現在のプロファイルを保存し、起動時に読み込む（`loadUserProfile` に空パスを渡すと `HOME + BACKUP_PROFILE`）。
- RRBridge用のテストプロファイル（`profiles/basic.xml`、`fuel.xml`）は `livedata="selected"` だけを付けた最小形式で、読込成功を実機確認済み。

## 5. プラグイン機構 [コード調査]（不採用だが記録）

- `ExternalDataSourceLoaderImpl.loadExternalDataSources()`（41行〜）: 作業ディレクトリの `./plugins` 内の `*.plugin`（Properties。`datasource.class=<FQCN>`）を読み、`getClass().getClassLoader().loadClass()` でクラスを読む（＝アプリのクラスパス上にある必要がある）。
- `ExternalDataSource` インターフェースに `Action getMenuAction(EcuLogger logger)` があり、`EcuLoggerMenuBar` 175行でメニュー構築時に呼ばれる → ロガー本体のインスタンスを受け取れる。`GenericDataSourceManager` がnullのとき汎用アクションを返す。
- 既存プラグイン15個（aem、aem.xwifi、aem2、apsx、ecotrons、fourteenpoint7、innovate、lm2_mts、mrf、phidget_ik、plx、te、txs_tuner、txs_utec、zt2）。クラス本体は `RomRaider.jar` 内。
- `RomRaider.jar` のマニフェスト: `Main-Class: com.romraider.ECUExec`、`Class-Path: lib/common/Graph3d.jar lib/common/args4j-2.0.1.jar lib/common/com4j.jar lib/common/j3dcore.jar lib/common/j3dutils.jar lib/common/jSerialComm-2.9.1.jar lib/common/jama-1.0.2.jar lib/common/jamlab.jar lib/common/jcommon-1.0.12.jar lib/common/jep.jar lib/common/jfreechart-1.0.9.jar lib/common/jna.jar lib/common/log4j-1.2.14.jar lib/common/phidget21.jar lib/common/platform.jar lib/common/vecmath.jar`。ビルド者 Dale Schultz、`Created-By: 1.8.0_322-b06 (Temurin)`。

## 6. 作業ディレクトリへの相対パス依存 [コード調査]

起動バッチ（`run-logger.bat`）は `cd /d "%~dp0"` でインストール先に移動してから起動する。以下が相対パスのため、**作業ディレクトリはインストール先でなければならない**:

| パス | 参照元 |
| --- | --- |
| `./customize/j2534Libraries.properties` | `io/j2534/api/J2534LibraryLocator.java` 307（J2534 DLLの探索） |
| `./customize/ncslearning.properties`、`ssmlearning.properties` | 学習値表示 |
| `customize/warningSound.wav` | ゲージ警告音 |
| `./customize/nameSequences.properties` | `swing/DefinitionManager.java` 354 |
| `./plugins` | プラグイン読込 |
| `lib/log4j.properties` | `util/LogManager.java` 31 |
| `i18n` | `util/ResourceUtil.java` 34 |
| `definitions` | `Settings.java` 179（既定値） |
| `lib/windows/32`（`-Djava.library.path`） | ネイティブDLL |

## 7. その他の観察

- 同梱JRE: Azul Zulu 8.96.0.205-CA-win32（`JAVA_VERSION="1.8.0_504"`、`OS_ARCH="i586"`）。`runtime/bin` に `javaw.exe`、`java.exe`、`instrument.dll`（javaagent用）、`jdwp.dll`、`dt_socket.dll`、`dt_shmem.dll`、Java Access Bridge（`JavaAccessBridge*.dll`、`jabswitch.exe`）。**`javac` と `attach.dll` はない**（JRE）。
- 公式の起動コマンド（`run-logger.bat`）: `javaw.exe -Djava.library.path=lib/windows/32 -Dawt.useSystemAAFontSettings=lcd -Dswing.aatext=true -Xms64M -Xmx512M -jar RomRaider.jar -logger`。
- 起動ログ `~/.RomRaider/rr_system.log` の冒頭に System Properties が全部出る。`sun.java.command` で起動方法（RRBridge付きかどうか）を判別できる。日本語ロケールでは日時が文字化けする（`2026�N10��12��`）。
- 接続時のログ: `Trying new J2534/ISO9141 connection: Tactrix Inc. - OpenPort 2.0 J2534 ISO/CAN/VPW/PWM` → `J2534 Version => firmware: 1.17.4877, dll: 1.01.4341 Jul 10 2014 14:16:35, api: 04.04` → `connection initialised` → `ECU ID = 2F04785006` → 一旦close → 再接続 → `TX Manager Started.` → `Starting AsyncDataUpdateHandler`。
- 失敗パターン:
  - OpenPortがUSB未接続: `PassThruOpen error [8:ERR_DEVICE_NOT_CONNECTED], no devices available` の後、`Trying serial connection...` → `Parameter portName must not be null or empty`（シリアルポート未設定）を約1秒周期で繰り返す。
  - OpenPort接続済み・ECU無応答（イグニッションOFF等）: `Error sending init: readMsg error: timeout expired waiting for 2 more message(s)`（または1 more）を約3秒周期で繰り返す。
  - 通信中のタイムアウト: `J2534Exception: readMsg error: timeout expired waiting for 51 more bytes`（`SSMLoggerConnection.sendAddressReads`）→ close → 再接続（約3秒周期）。
- `EcuLoggerMenuBar` のToolsメニューには ECUリセット（F7）、DTC読出（F8）、Global Adjustment（F5）、学習値（F6）がある。**RRBridgeからは呼ばない。**
