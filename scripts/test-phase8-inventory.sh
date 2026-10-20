#!/usr/bin/env bash
# Phase VIII-1 RLDI1 distribution inventory: synthetic installs + the real packaged entry closure. Read-only, no launch.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ $windows == 1 ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase8-inventory
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out/closure" "$out/test" "$out/jars"
 # Same dependency closure as scripts/phase7-packaged.sh builds for the approved entry.
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath src/main/java -d "$out/closure" src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java
 (cd "$out/closure" && jar cf ../jars/romlogger-offline.jar .)
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out/test" src/test/java/net/neristal/rrbridge/prototype/ingress/Phase8InventoryTest.java
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -cp "$out/test${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Phase8InventoryTest "$out/jars/romlogger-offline.jar"
echo "Phase VIII inventory completed; launch/production/ACK remain disabled"
