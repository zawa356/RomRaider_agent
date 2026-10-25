#!/usr/bin/env bash
# Phase X-C1: F-IX-1 reproduction with the REAL pinned dependency JARs (RLDI1 unchanged) and the RLDI2 synthetic-only
# dependency-policy candidate (fixed pins, minimal exceptions, adversarial and race cases). Read-only inventory of owned
# temp installs; nothing is launched. --run-only re-runs compiled classes (actual Java 8 x86); --expect-red runs only the
# reproduction against code without RLDI2 (expects RED_COUNT>0).
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ -f build/identity-deps/jna-5.14.0.jar ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase-xc-rldi2; red=false; runonly=false
for arg in "$@"; do case $arg in --expect-red) red=true;; --run-only) runonly=true;; esac; done
T=src/test/java/net/neristal/rrbridge/prototype/ingress
tests=("$T/PhaseXcF1ReproTest.java"); [[ $red == true ]] || tests+=("$T/PhaseXcRldi2Test.java")
if [[ $runonly == false ]]; then
 rm -rf "$out"; mkdir -p "$out/closure" "$out/test" "$out/jars"
 # Same approved-entry closure as scripts/test-phase8-inventory.sh (RLWR1 JNA binding on Windows).
 binding=(); if [[ $windows == 1 ]]; then binding=(src/main/java/net/neristal/rrbridge/prototype/ingress/WindowsProductReaderJna.java); fi
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath src/main/java -d "$out/closure" src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java "${binding[@]}"
 (cd "$out/closure" && jar cf ../jars/romlogger-offline.jar .)
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out/test" "${tests[@]}"
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p' | tr -d '\r')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -Dphasexc.expectRed=$red -cp "$out/test${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PhaseXcF1ReproTest "$out/jars/romlogger-offline.jar"
if [[ $red == false ]]; then
 # 32-bit JVMs default to a small heap; bomb/large fixtures need test-side buffers (the product streams).
 java -Xmx512m -cp "$out/test${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PhaseXcRldi2Test "$out/jars/romlogger-offline.jar"
fi
echo "Phase X-C RLDI2 suite completed; launch/production/ACK remain disabled"
