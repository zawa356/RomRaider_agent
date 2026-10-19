#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; entries=(); case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; entries+=(src/test/java/net/neristal/rrbridge/prototype/ingress/WindowsWatermarkV2Test.java);; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ "$sep" == ";" ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase5-classes
if [[ "${1:-}" != "--run-only" ]]; then
 mkdir -p "$out"
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" "${entries[@]}" src/test/java/net/neristal/rrbridge/prototype/ingress/Phase5ProvenanceTest.java
fi
java -cp "${out}${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Phase5ProvenanceTest
