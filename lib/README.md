# lib/

ビルド時に参照するjarを置く場所です（Gitには含めません）。

| ファイル | 入手元 | 用途 |
| --- | --- | --- |
| `RomRaider.jar` | RomRaiderのインストール先（例: `C:\Program Files (x86)\RomRaider\RomRaider.jar`） | エージェントのコンパイル時のみ参照（実行時はRomRaider本体のものが使われる） |
| `sqlite-jdbc.jar` | Maven Central `org.xerial:sqlite-jdbc:3.46.1.3`（Java 8対応、Windows x86ネイティブ同梱） | 検証用受信サーバー、および製品v1 SQLite spool |
| `slf4j-api.jar` / `slf4j-nop.jar` | Maven Central `org.slf4j:2.0.16` | **製品spoolの単独CI試験用**。実環境RomRaider同一JVMへの同梱・ログ経路はPhase 2.1-B以降に確認 |

`scripts/fetch-deps.sh` で sqlite-jdbc を取得できます。RomRaider.jar は手動でコピーしてください。
