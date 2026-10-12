# 05. X270 実機の環境情報

調査日: 2026-10-12（Claude Cowork から X270 にリモート接続し、ファイル読み取り・画面操作で確認）。特記なき限り **[実機確認]**。

## 1. ハードウェア・OS

| 項目 | 値 |
| --- | --- |
| PC | Lenovo ThinkPad X270 |
| 端末名 | `laptop-glmhjuc8` |
| OS | Windows 10（`os.name=Windows 10`、`os.version=10.0`。引継ぎ資料には「Windows 10 Home 22H2」とあるがエディション・ビルドは未確認） |
| ユーザー | `zawa`（ホーム `C:\Users\zawa`） |
| ロケール | `user.country=JP`、`sun.jnu.encoding=MS932` |
| 他に入っているもの | ChatGPT デスクトップ、OpenAI Codex、Claude デスクトップ、PowerShell 7.6.5、Firefox、7-Zip（ダウンロードフォルダのインストーラーから推定 [推測]） |

## 2. RomRaider

| 項目 | 値 |
| --- | --- |
| インストール先 | `C:\Program Files (x86)\RomRaider`（一般ユーザーは書き込み不可） |
| バージョン | 1.1.0 NOV26 2025 Build 1227 |
| Java | 同梱 Azul Zulu 8.96.0.205-CA-win32（1.8.0_504、32bit、Client VM） |
| ユーザー設定 | `C:\Users\zawa\.RomRaider\`（`settings.xml`、`profile_backup.xml`、`rr_system.log`） |
| Logger定義 | `C:/Program Files (x86)/RomRaider/definitions/logger/v370/logger_METRIC_EN_v370.xml`（2,288,834バイト、2021-11-17） |
| ECU定義 | `C:\Program Files (x86)\RomRaider\definitions\ecu\metric\ecu_defs.xml` |
| 読込結果 | `loaded protocol SSM: 245 parameters, 172 switches from def version 370.` |
| 起動時のプラグイン | AEM X-Wifi、AEM UEGO、APSX D1/D2、ECOTRONS ALM、14Point7、Innovate（LogWorks未インストールの警告）、MRF、Phidget InterfaceKit、PLX、Tech Edge、TXS Tuner、TXS UTEC、Zeitronix ZT-2 |

### 2.1 `settings.xml` のロガー関連（2026-10-12時点）

```xml
<logger locale="system">
    <autoConnectOnStartup value="false"/>
    <serial port="" refresh="false"/>
    <protocol name="SSM" transport="ISO9141" module="ECU" fastpoll="false" library=""/>
    <maximized value="false"/>
    <size x="600" y="1000" divider="400"/>
    <location x="0" y="0"/>
    <tabs selected="0" showlist="true"/>
    <definition path="C:/Program Files (x86)/RomRaider/definitions/logger/v370/logger_METRIC_EN_v370.xml"/>
    <profile path=""/>
    <filelogging path="C:/Users/zawa/Documents/OpenPortWork/Logs" switchid="S20" active="true" absolutetimestamp="false"/>
    <debug level="info"/>
    <plugins>
        <plugin id="com.romraider.logger.external.phidget.interfacekit.plugin.IntfKitDataSource" port="HID USB"/>
    </plugins>
    <gauge index="0"/>
    <dyno car="Missing cars_def.xml" gear="" threshold="98.0" units="%"/>
</logger>
```

- `autoConnectOnStartup=false`: 起動しただけでは接続しない → RRBridgeの `logging_start` が必要。ただし2026-10-12 11:49の観察では、起動中のRomRaiderが1秒周期で接続を試みていた（前回セッションでロギングを開始したままだった可能性 [推測]）。
- `fastpoll="false"`: SSMの高速ポーリング無効。周期の改善余地として未検証。
- `filelogging … switchid="S20" active="true"`: デフォッガー連動のCSV記録が有効（`04_romraider_internals.md` §3）。
- `absolutetimestamp="false"`: CSVの時刻は相対ミリ秒。
- ROMイメージ置き場: `C:\Users\zawa\Documents\OpenPortWork\ROM`。

## 3. 通信機器・ECU

| 項目 | 値 |
| --- | --- |
| インターフェース | Tactrix OpenPort 2.0（互換機）「OpenPort 2.0 J2534 ISO/CAN/VPW/PWM」 |
| J2534 DLL | `C:\WINDOWS\SysWOW64\op20pt32.dll`、dll 1.01.4341（Jul 10 2014）、API 04.04 |
| ファームウェア | 1.17.4877 |
| プロトコル | SSM / ISO9141（K-Line） |
| ECU ID | `2F04785006` |

2026-10-12の状態:
- 11:49ごろ: OpenPort未接続（`ERR_DEVICE_NOT_CONNECTED`）。
- 12:03以降: OpenPortはUSB接続、ECU無応答（`timeout expired waiting for 2 more message(s)`）。車両側がイグニッションOFFまたは未接続と思われる [推測]。

## 4. フォルダ構成

```
C:\Users\zawa\Documents\OpenPortWork\
├ Exports\ ROM\ Reports\          （2026-09-19作成、中身は未確認）
├ versions.php                    （12,935バイト、未確認）
├ Logs\                           RomRaiderのCSV出力先
└ RRBridge\                       このリポジトリの配置先（2026-10-12作成）
   ├ rrbridge.jar  rrreceiver.jar  sqlite-jdbc.jar
   ├ start-romraider-with-bridge.bat  start-receiver.bat  sink.properties  README.txt
   ├ profiles\basic.xml  fuel.xml
   ├ src\…（当時のソース）
   ├ inbox\  outbox\               コマンド入出力
   ├ spool\rr_<session>.tsv(.sent) 転送の正本と送信済み位置
   ├ data\telemetry.db(-wal,-shm)  受信サーバーのDB、receiver.log
   └ status.json  rrbridge.log
```

ダウンロードフォルダ（`C:\Users\zawa\Downloads`）にある過去の検討資料（ChatGPT／Claude時代のもの。中身は未読）:
`OpenPort_X270_AI引継資料_詳細版`（2026-09-21）、`OpenPort_X270_作業指示書_Work版`（09-19）、`OpenPort_X270_Cowork作業指示書_第1次`（09-19）、`OpenPort_X270_Claude_Review_Response`（09-13）、`OpenPort_X270_Online_Operation_Review`（09-13）、`OpenPort_X270_Online_Operation_Proposal`（09-13）、`RomLogger_AI連携_会話引継ぎと設計検討_2026-10-10.md`（10-12）。

## 5. 既存の実車ログ（2026-09-21）

`Documents\OpenPortWork\Logs` にあるCSV。時刻は相対ミリ秒。周期は連続する行の時刻差の中央値。

| ファイル | 項目数 | サンプル数 | 長さ | 周期中央値 | p95 | 最大 | 周波数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| romraiderlog_20260921_132003.csv | 12 | 4,912 | 22.6分 | 276ms | 277 | 402 | 3.62Hz |
| romraiderlog_20260921_135522.csv | 14 | 2,053 | 10.3分 | 301ms | 302 | 353 | 3.32Hz |
| romraiderlog_20260921_143310.csv | 8 | 9,498 | 19.9分 | 125ms | 126 | 388 | 8.00Hz |
| romraiderlog_20260921_150206.csv | 9 | 2,475 | 6.6分 | 159ms | 160 | 165 | 6.29Hz |
| romraiderlog_20260921_154328.csv | 10 | 6,638 | 18.5分 | 167ms | 168 | 307 | 5.99Hz |
| romraiderlog_20260921_161938.csv | 18 | 1,689 | 9.9分 | 351ms | 352 | 383 | 2.85Hz |
| romraiderlog_20260921_164010.csv | 19 | 2,350 | 15.1分 | 385ms | 385 | 392 | 2.60Hz |
| romraiderlog_20260921_171529.csv | 13 | 8,532 | 33.3分 | 234ms | 235 | 275 | 4.27Hz |
| romraiderlog_20260921_180710.csv | 9 | 0 | — | — | — | — | ヘッダーのみ |
| romraiderlog_20260921_180839.csv | 9 | 0 | — | — | — | — | ヘッダーのみ |
| romraiderlog_20260921_191115.csv | 9 | 0 | — | — | — | — | ヘッダーのみ（ブースト系） |

各ファイルの項目:
- 132003: IAM, Feedback Knock Correction, Fine Learning Knock Correction, Engine Speed, Engine Load, Knock Correction Advance, Fine Learning Table Offset*, Throttle Opening Angle, Manifold Relative Pressure, A/F Correction #1, A/F Learning #1, A/F Sensor #1
- 135522: 上記＋A/F Learning Airflow Range, Mass Airflow
- 143310: Engine Speed, Mass Airflow, Mass Airflow Sensor Voltage, A/F Sensor #1, A/F Correction #1, A/F Learning #1, A/F Learning Airflow Range, Throttle Opening Angle
- 150206: 上記＋Closed Loop Fueling Target
- 154328: 上記＋CL/OL Fueling*
- 161938: 上記＋Engine Load, IAM, Feedback Knock Correction, Fine Learning Knock Correction, Primary Wastegate Duty Cycle, Manifold Relative Pressure, Injector Duty Cycle, Ignition Total Timing
- 164010: 上記＋Primary Open Loop Map Enrichment
- 171529: Engine Speed, Engine Load (4-Byte)*, Throttle Opening Angle, Mass Airflow, A/F Sensor #1 (Lambda), A/F Correction #1, Fuel Pump Duty, Fuel Injector #1 Pulse Width, Injector Duty Cycle, CL/OL Fueling*, Primary Open Loop Map Enrichment (4-byte)*, A/F Learning #1, Manifold Relative Pressure
- 191115: Engine Speed, Throttle Opening Angle, Manifold Relative Pressure, Target Boost (4-byte)*, Boost Error*, Primary Wastegate Duty Maximum* (4-byte)*, Turbo Dynamics Proportional (4-byte)*, Turbo Dynamics Integral (4-byte)*, Primary Wastegate Duty Cycle

171529 の先頭行（アイドル）: `0,747,0.26,3.14,3.63,1.00,-0.78,33.33,2.05,1.28,8,14.70,1.56,-61`（747rpm、MRP −61kPa、λ1.00、閉ループ=8）。

### 5.1 読み取れること

- **周期は項目数（＝応答バイト数）にほぼ比例**。8項目で8Hz、13〜14項目で3.3〜4.3Hz、18〜19項目で2.6〜2.9Hz。`*` 付きの4バイト拡張パラメーターは1項目あたりの負荷が大きいと思われる [推測]。
- ユーザーは9/21に、ノック系 → 燃調系 → 燃調＋点火 → 燃料系 → ブースト系 と調査目的に応じてプロファイルを手動で切り替えていた。これは自動化したい「適応的計測」の手作業版にあたる。

### 5.2 `rr_system.log` から（2,598,745バイト、25,000行超、2026-09-19〜）

- 起動記録: 2026-09-19 23:43、09-20 21:50、09-21 13:08、10-12 11:50、11:59、12:03（RRBridge v0.1）、12:05（v0.2）、12:14（v0.3）。
- 09-21 13:11: `Loading profile: C:\Users\zawa/.RomRaider/profile_backup.xml`。
- 13:51ごろ: `readMsg error: timeout expired waiting for 51 more bytes` → 切断 → 再接続（`ECU ID = 2F04785006`）を約3.2秒周期で繰り返す区間がある（ログ924〜1430行付近）。その後14:30、15:41〜15:42、16:15〜16:16にも同様の繰り返し。項目数が多い構成で応答が途切れた可能性があるが、原因は未確定（項目構成、配線、電源などの切り分けが必要）。
- 17:11:21: 接続成功・`TX Manager Started` 以降、19:06:41に `timeout expired waiting for 28 more bytes` で切れるまで約1時間55分連続でポーリングしていた（CSV記録はその一部）。
- `ERR_DEVICE_NOT_CONNECTED` は累計3,424回（OpenPort未接続のまま起動していた時間が長い）。
