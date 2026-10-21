#!/usr/bin/env bash
# Phase IX-4 (R9-04): RLDI1 same-size/same-mtime and physical-replacement races during inventory. Owned temp installs.
#   bash scripts/test-phase9-inventory-race.sh [--run-only] [--expect-red]   (--expect-red: run against pre-fix code)
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
out=build/phase9-inventory-race; red=false
for arg in "$@"; do [[ $arg == --expect-red ]] && red=true; done
runonly=false; for arg in "$@"; do [[ $arg == --run-only ]] && runonly=true; done
if [[ $runonly == false ]]; then
 rm -rf "$out"; mkdir -p "$out"
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" src/test/java/net/neristal/rrbridge/prototype/ingress/Phase9InventoryRaceTest.java
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -Dphase9.expectRed=$red -cp "$out${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Phase9InventoryRaceTest
