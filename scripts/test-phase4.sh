#!/usr/bin/env bash
# Phase IV focused tests. Fresh synthetic fixtures only; no Worker/service/ECU/production DB access.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
out=build/phase4-classes
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out"
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" \
  src/main/java/net/neristal/rrbridge/product/v1/*.java \
  src/test/java/net/neristal/rrbridge/product/v1/Phase4ReviewRedTest.java
fi
run(){ java -cp "${out}${sep}${cp}" "$@"; }
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
run net.neristal.rrbridge.product.v1.Phase4ReviewRedTest
echo "Phase IV focused tests completed"
