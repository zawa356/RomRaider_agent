# 06. 実機検証の手順と結果（2026-10-12）

実施者: Claude（Cowork）。X270にリモート接続し、ファイル操作（Linux VM経由で `Documents\OpenPortWork` などをマウント）と画面操作（RomRaider Loggerとエクスプローラーのクリックのみ許可）で実施。
X270上でWindowsのコマンドを直接実行する手段はなかったため、起動はエクスプローラーでバッチをダブルクリック、操作は `inbox/*.cmd` ファイル経由で行った。

## 0. 検証時の条件

- OpenPort: USB接続済み、ECU無応答（`Error sending init: readMsg error: timeout expired waiting for 2 more message(s)` を約3秒周期で繰り返す状態）。
- したがって、**ECUからの実データは一度も流れていない**。ロギングを開始しても「接続試行中」の状態。
- 受信サーバーとRomRaiderは同じX270上（127.0.0.1）。

## 1. v0.1.0: 外部制御の基本（12:03〜12:04）

起動: RomRaiderを File > Exit で終了 → `start-romraider-with-bridge.bat` をダブルクリック。

`rrbridge.log`:
```
2026-10-12T12:03:10.731+09:00 agent loaded v0.1.0 dir=C:\Users\zawa\Documents\OpenPortWork\RRBridge
2026-10-12T12:03:22.131+09:00 EcuLogger instance found
2026-10-12T12:03:23.626+09:00 http listening on 127.0.0.1:18765
```
（起動からインスタンス取得まで約11秒。GUI待ち3秒を含む）

コマンドの投入方法（Linux VM側のシェル関数）:
```bash
run(){ n=$(date +%H%M%S)_$1; shift; printf '%s\r\n' "$@" > inbox/$n.tmp && mv inbox/$n.tmp inbox/$n.cmd
       for i in $(seq 1 40); do [ -f outbox/$n.json ] && break; sleep 0.25; done; cat outbox/$n.json; }
run a_status cmd=status
run b_load   cmd=load_profile profile=basic.xml
```

| 試験 | 結果 |
| --- | --- |
| `status` | `ready:true, logging:false, fileLogging:false` |
| `load_profile basic.xml` | ok。タイトル `- Profile: C:\User...\profiles\basic.xml`。画面のDataタブに6項目（Coolant, Engine Speed, Intake Air, Manifold, Throttle, Vehicle Speed）が表示されたことをスクリーンショットで確認 |
| `load_profile ../../.RomRaider/settings.xml` | 拒否 `profile must be a file name like basic.xml` |
| `reset_ecu`（未定義） | 拒否 `unknown command. allowed: …` |
| `logging_start` | ok（応答時点では `logging:false`。非同期） |
| `filelog_start`（3秒後） | ok。`logging:true, fileLogging:true`、`romraiderlog_20261012_120350.csv` 作成 |
| CSV記録中に `load_profile fuel.xml` | ok。ただしCSVに問題（下記） |
| `filelog_stop` → `logging_stop` | ok。CSV 1,848バイト |

**発見: CSV記録中のプロファイル切替でヘッダーが多重化**。`romraiderlog_20261012_120350.csv` の中身はヘッダー行だけが10行並んだ（ECU無応答のためデータ行なし）。basicの6項目から、A/F Correction #1 → A/F Learning #1 → A/F Sensor #1 が1つずつ追加され、Coolant → Intake Air → Manifold → Vehicle Speed が1つずつ削除され、Mass Airflow → MAF Sensor Voltage が追加される過程が、1行ずつ記録されていた。

## 2. v0.2.0: `switch_profile`（12:05〜12:06）

RomRaider終了 → jar差し替え → 再起動。
```
run a cmd=switch_profile profile=basic.xml    # ok（記録していないので読込のみ）
run b cmd=logging_start
run c cmd=filelog_start                       # romraiderlog_20261012_120539.csv
run d cmd=switch_profile profile=fuel.xml     # 停止→読込→再開 → romraiderlog_20261012_120545.csv
run e cmd=switch_profile profile=basic.xml    # → romraiderlog_20261012_120550.csv
run f cmd=filelog_stop
run g cmd=logging_stop
```
結果: 3ファイルとも**ヘッダー1行**。
- 120539: `Time (msec),Coolant Temperature (C),Engine Speed (rpm),Intake Air Temperature (C),Manifold Relative Pressure (kPa),Throttle Opening Angle (%),Vehicle Speed (km/h)`
- 120545: `Time (msec),Engine Speed (rpm),Throttle Opening Angle (%),A/F Correction #1 (%),A/F Learning #1 (%),A/F Sensor #1 (Lambda),Mass Airflow (g/s),Mass Airflow Sensor Voltage (V)`
- 120550: `Time (msec),Engine Speed (rpm),Throttle Opening Angle (%),Coolant Temperature (C),Intake Air Temperature (C),Manifold Relative Pressure (kPa),Vehicle Speed (km/h)` ← **同じbasic.xmlでも120539と列順が違う**（直前のプロファイルから残った項目が先に来る）。

## 3. v0.3.0: リアルタイム転送（12:14〜12:20）

### 3.1 取り付け
```
2026-10-12T12:14:42.318+09:00 agent loaded v0.3.0 dir=C:\Users\zawa\Documents\OpenPortWork\RRBridge
2026-10-12T12:14:52.977+09:00 EcuLogger instance found
2026-10-12T12:14:52.999+09:00 stream installed (handlers=10, session=20261012_121452, sink=http://127.0.0.1:18766/ingest)
2026-10-12T12:14:54.256+09:00 http listening on 127.0.0.1:18765
```

### 3.2 受信停止中の保持と、復旧後の再送
1. 受信サーバー未起動のまま `selftest_stream count=80 hz=8`。
2. 12秒後 `stream_status`: `samples:80, linesProduced:85 (M3+E2+S80), linesSent:0, sendErrors:3, lastSendResult:"java.net.ConnectException: Connection refused: connect"`。spool `rr_20261012_121452.tsv` 6,498バイト。
3. `start-receiver.bat` 起動 → **1回目は失敗**（`ClassNotFoundException: com.romraider.logger.ecu.ui.handler.DataUpdateHandler`。受信サーバーが `Stream.read()` を呼んでいたため。`07_history.md` 参照）。`Util` に切り出し、`rrreceiver.jar` として別jar化して再起動。
4. 再起動後: `lastSendResult: 200 {"ok":true,"samples":80,"measurements":240,"events":2,"duplicates":0,"maxLagMs":236922} (1090ms, 6498B)`。**停止中の80サンプルが全件保存された**（遅延236.9秒は停止していた時間）。

### 3.3 連続転送の遅延
`selftest_stream count=240 hz=8` を流しながら5秒ごとに状態を確認:
```
samples linesSent queue lastSendResult
117     124       0     200 {"samples":4,…,"maxLagMs":495} (17ms, 316B)
157     164       0     200 {"samples":4,…,"maxLagMs":431} (16ms, 316B)
196     203       0     200 {"samples":4,…,"maxLagMs":408} (18ms, 311B)
236     243       0     200 {"samples":4,…,"maxLagMs":463} (10ms, 316B)
277     286       0     200 {"samples":4,…,"maxLagMs":461} (15ms, 316B)
317     322       0     200 {"samples":4,…,"maxLagMs":482} (14ms, 311B)
```
DB（スナップショットをコピーしてsqlite3で集計）:
- segment 1（停止中に発生した80件）: 平均遅延231,840ms、最小226,776、最大236,922。
- **segment 2（連続転送240件）: 平均273ms、最小5ms、最大533ms。**
- `measurements` 960行（320サンプル×3項目）、`parameters` 6行、`events` 4行。
- 最新値の例: seq 320 `Selftest Engine Speed 871.0 / Throttle 6.13 / MRP -57.0`。
- 遅延の内訳: 送信スレッドの待機（最大500ms）が支配的。POST 1回は10〜20ms。

### 3.4 重複排除（コンテナ内試験、Java 21）
受信停止中30件 → 受信開始で再送 → さらに20件 → `.sent` を0に戻して全件再送:
`200 {"ok":true,"samples":0,"measurements":0,"events":4,"duplicates":50,"maxLagMs":0}` → 50件すべて重複として無視された。

### 3.5 受信サーバー単体（コンテナ内、RomRaider.jarなし）
```
printf 'M\tX\t1\tP8\tEngine Speed\trpm\nS\tX\t1\t1\t1791775000000\tP8=750.0\n' | curl -X POST --data-binary @- http://127.0.0.1:18799/ingest
→ {"ok":true,"samples":1,"measurements":1,"events":0,"duplicates":0,…}
```

## 4. v0.4.0（12:58〜13:00）

配置手順: RomRaiderを File > Exit、受信サーバーのコンソールを閉じる → `rrbridge.jar`、`rrreceiver.jar`、バッチ、`rrbridge.properties.example` を上書き → `start-receiver.bat` → `start-romraider-with-bridge.bat`。`rrbridge.properties` は置いていない（既定値で動作）。

```
2026-10-12T12:58:43.462+09:00 agent loaded v0.4.0 dir=C:\Users\zawa\Documents\OpenPortWork\RRBridge httpAuth=off settleMs=1500
2026-10-12T12:58:53.499+09:00 EcuLogger instance found
2026-10-12T12:58:53.520+09:00 stream installed (handlers=10, session=20261012_125843, sink=http://127.0.0.1:18766/ingest)
2026-10-12T12:58:54.440+09:00 http listening on 127.0.0.1:18765
```

| # | コマンド | 結果 |
| --- | --- | --- |
| s1 | `status` | `agent:"RRBridge 0.4.0"`、`stream.installResult` に取り付け結果、`linesDropped:0`、`pendingBytes:0` |
| s2 | `list_profiles` | `basic.xml` → `["P8","P9","P13","P25","P2","P11"]`（6）、`fuel.xml` → `["P8","P12","P18","P58","P3","P4","P13"]`（7） |
| s3 | `switch_profile profile=../x.xml` | 拒否 `profile must be an existing file name in profiles dir (pattern …): ../x.xml` |
| s4 | `logging_start wait_ms=3000` | ok、応答時点で `logging:true`（v0.3.0では false だった） |
| s5 | `filelog_start` | ok、`romraiderlog_20261012_125911.csv` |
| s6 | 記録中に `switch_profile fuel.xml` | ok、`romraiderlog_20261012_125913.csv` に切替。`rrbridge.log`: `switch_profile fuel.xml took 1836ms (filelogRestarted=true)`（ECU無応答時の値） |
| s7 | `selftest_stream count=160 hz=8` → 22秒後 `stream_status` | `samples:160, linesProduced:170, linesDropped:0, linesSent:170, pendingBytes:0, sendErrors:0` |
| s9 | 記録中のまま `logging_stop wait_ms=3000` | ok、`logging:false, fileLogging:false`（CSV記録も止まった）、タイトル `Stopped logging to file` |

- 2つのCSVはどちらもヘッダー1行のみ（ECU無応答のためデータ行なし）。
- 自己試験 `./test.sh`（コンテナ、Java 21で `--release 8` コンパイル）: 30項目すべてPASS。

## 5. v0.5.0（13:53〜13:58）: 取得速度ガード

配置: RomRaider終了 → `rrbridge.jar` 上書き、`rrbridge.properties`（example と同内容）を新規配置 → 起動。
（最初のダブルクリックは、ファイルが1つ増えて行がずれたため `start-receiver.bat` を誤って起動した。2つ目の受信サーバーはポート競合で起動できなかったと思われる。13:56に正しく起動）

```
2026-10-12T13:56:39.836+09:00 agent loaded v0.5.0 … httpAuth=off settleMs=1500 maxParams=20 guard=on(min 2.0Hz, check 10000ms)
2026-10-12T13:56:50.717+09:00 link monitor attached 2026-10-12T13:56:50.708+09:00
```

| 試験 | 結果 |
| --- | --- |
| X270の `profiles/` に21項目の `test_21params.xml` を作成（試験用。残してある）→ `list_profiles` | `"count":21,"allowed":false`、basic/fuel は `allowed:true`、`maxParams:20` |
| `switch_profile profile=test_21params.xml` | 拒否 `too many parameters: test_21params.xml selects 21 (limit profile.maxParams=20). K-Line帯域を超えて…` |
| `switch_profile basic.xml` → `logging_start` → 15秒後の status | `link: {attach:"attached …", connects:6, ecuInits:0, errors:12, lastError:"Error sending init: Parameter portName must not be null or empty"}`、`stream: {realSamples:0, rateHz10s:0.0, intervalP95Ms10s:-1}`、`guard: {enabled:true, currentProfile:"basic.xml", lastAction:""}` |
| 転送ストリーム（spool） | `E … link_error count=3 Error sending init: readMsg error: timeout expired waiting for 2 more message(s)`、`E … health rate_hz=0.00 samples=0 params=0 interval_p95_ms=-1 link_errors=8 link_connects=5` などが受信サーバーへ送られた |
| 自動復帰 | ECUデータが無く切替前の速度が0のため作動条件を満たさず、**未確認** |

自己試験: Java 44項目PASS、server 36件PASS（SQLite＋PostgreSQL 16）。

## 6. 未検証（次に実車で行うこと）

`09_open_issues.md` の「実車試験計画」を参照。要点:
1. 実ECUの応答が `Stream.handleDataUpdate` に届き、値・単位・IDが正しいこと（CSVと突き合わせ）。
2. ECU通信中の `switch_profile` の所要時間と欠測時間（CSV停止〜再開の間のサンプル数）。
3. デフォッガー（S20）連動のCSV記録と、RRBridgeの `filelog_*` が競合しないか。
4. 8Hz級の連続運転での遅延・キュー滞留・spoolサイズ。
5. 通信断（タイムアウト・再接続）時のイベントとsegmentの振る舞い。
