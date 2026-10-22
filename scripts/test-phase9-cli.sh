#!/usr/bin/env bash
# Phase IX-1 (R9-01): clean build of the separated offline tool JAR, then real child-JVM starts of all four CLIs from
# that JAR only, plus RLDI1 proof that the tool JAR stays out of the approved entry install. Synthetic, read-only.
# Build stage and run stage are separate: the run stage never compiles, and the child classpath is tool JAR + deps.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
deps="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
out=build/phase9-cli
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out/closure" "$out/test" "$out/jars"
 bash scripts/build-offline-tools.sh "$out/tools"
 # Same entry closure as phase7-packaged.sh / test-phase8-inventory.sh (on Windows incl. the RLWR1 JNA binding).
 binding=(); ecp="$deps"
 case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) binding=(src/main/java/net/neristal/rrbridge/prototype/ingress/WindowsProductReaderJna.java); ecp="${deps};build/identity-deps/jna-5.14.0.jar";; esac
 javac --release 8 -encoding UTF-8 -cp "$ecp" -sourcepath src/main/java -d "$out/closure" src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java "${binding[@]}"
 (cd "$out/closure" && jar cf ../jars/romlogger-offline.jar .)
 javac --release 8 -encoding UTF-8 -cp "$deps" -sourcepath "src/main/java${sep}src/test/java" -d "$out/test" src/test/java/net/neristal/rrbridge/product/v1/Phase9CliProcessTest.java
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -XshowSettings:properties -version 2>&1 | grep -E 'java.version =|sun.arch.data.model' || true
java -cp "$out/test${sep}${deps}" net.neristal.rrbridge.product.v1.Phase9CliProcessTest "$(pwd)/$out/tools/romlogger-offline-tools.jar" "$(pwd)/$out/jars/romlogger-offline.jar" "$deps"
