#!/usr/bin/env bash
# Phase X-B: RLDI1 verifies every JAR entry (CRC-32, exact DEFLATE end, sizes, expansion bounds). --run-only; --expect-red on pre-fix code.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
out=build/phase-xb-zip; red=false; runonly=false
for arg in "$@"; do case $arg in --expect-red) red=true;; --run-only) runonly=true;; esac; done
if [[ $runonly == false ]]; then rm -rf "$out"; mkdir -p "$out"; javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" src/test/java/net/neristal/rrbridge/prototype/ingress/PhaseXbZipAllEntriesTest.java; fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then actual=$(java -XshowSettings:properties -version 2>&1 | sed -n "s/^ *sun.arch.data.model = //p"); [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }; fi
# 32-bit JVMs default to a small heap; the bomb fixtures need ~200 MiB of test-side buffers (the product streams).
java -Xmx512m -Dphasexb.expectRed=$red -cp "$out${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PhaseXbZipAllEntriesTest
