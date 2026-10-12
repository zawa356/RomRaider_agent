#!/usr/bin/env bash
# rrbridge.jar（エージェント）と rrreceiver.jar（検証用受信サーバー）をビルドし dist/ に配置する。
# 必要: JDK 9以上（--release 8 でJava 8向けにコンパイル）、lib/RomRaider.jar、lib/sqlite-jdbc.jar
set -euo pipefail
cd "$(dirname "$0")"
for f in lib/RomRaider.jar lib/sqlite-jdbc.jar; do [ -f "$f" ] || { echo "missing $f (lib/README.md 参照)"; exit 1; }; done
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
rm -rf build dist && mkdir -p build/classes build/receiver dist
javac -Xlint:-options --release 8 -encoding UTF-8 -cp "lib/RomRaider.jar${SEP}lib/sqlite-jdbc.jar" -d build/classes src/main/java/net/neristal/rrbridge/*.java
jar cfm dist/rrbridge.jar src/main/resources-manifest.txt -C build/classes .
# 受信サーバーはRomRaiderに依存しないクラスだけで単独jarにする
mkdir -p build/receiver/net/neristal/rrbridge
cp build/classes/net/neristal/rrbridge/Receiver*.class build/classes/net/neristal/rrbridge/Util.class build/receiver/net/neristal/rrbridge/
jar cf dist/rrreceiver.jar -C build/receiver .
# 配置用一式
cp lib/sqlite-jdbc.jar dist/
cp -r profiles dist/
cp scripts/start-romraider-with-bridge.bat scripts/start-receiver.bat dist/
cp scripts/sink.properties.example dist/sink.properties
cp scripts/rrbridge.properties.example dist/rrbridge.properties.example
echo "dist/ に出力しました。dist/ の中身を X270 の Documents\\OpenPortWork\\RRBridge にコピーして使います。"
