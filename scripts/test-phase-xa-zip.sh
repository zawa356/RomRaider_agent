#!/usr/bin/env bash
# Phase X-A-2: malformed ZIP/JAR inputs against the RLDI1 in-memory parser. --run-only; --expect-red on pre-fix code.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
out=build/phase-xa-zip; red=false; runonly=false
for arg in "$@"; do case $arg in --expect-red) red=true;; --run-only) runonly=true;; esac; done
if [[ $runonly == false ]]; then rm -rf "$out"; mkdir -p "$out"; javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" src/test/java/net/neristal/rrbridge/prototype/ingress/PhaseXaZipAuditTest.java; fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then actual=$(java -XshowSettings:properties -version 2>&1 | sed -n "s/^ *sun.arch.data.model = //p"); [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }; fi
java -Dphasexa.expectRed=$red -cp "$out${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PhaseXaZipAuditTest
