#!/usr/bin/env bash
# RomRaiderのGUIなしで動く範囲の自己試験。必要: JDK 9+、lib/RomRaider.jar、lib/sqlite-jdbc.jar
set -euo pipefail
cd "$(dirname "$0")"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
CP="lib/RomRaider.jar${SEP}lib/sqlite-jdbc.jar"
rm -rf build/test && mkdir -p build/test
javac -Xlint:-options --release 8 -encoding UTF-8 -cp "$CP" -d build/test src/main/java/net/neristal/rrbridge/*.java src/test/java/net/neristal/rrbridge/*.java
java -cp "build/test${SEP}${CP}" net.neristal.rrbridge.SelfTest 2>&1 | grep -v -E '^SLF4J|JAVA_TOOL_OPTIONS|^Picked up'
exit "${PIPESTATUS[0]}"
