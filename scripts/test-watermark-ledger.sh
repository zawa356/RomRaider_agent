#!/usr/bin/env bash
# Phase 3c-1 RLC2 highwater ledger + Phase 3d-1 RLM1 rotation PoC: REAL SQLite, synthetic TEMP data only.
# No product spool (v1_*), no Server ACK, no Worker wiring.
# Requires build-only jars from scripts/test-product-v1-spool.sh (and, on Windows,
# JNA from scripts/test-windows-native-identity.sh). --run-only reuses compiled classes.
set -euo pipefail
cd "$(dirname "$0")/.."
for j in lib/sqlite-jdbc.jar lib/slf4j-api.jar lib/slf4j-nop.jar; do
  [[ -s "$j" ]] || { echo "missing $j: run scripts/test-product-v1-spool.sh first" >&2; exit 2; }
done
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
jna=build/identity-deps/jna-5.14.0.jar
pkg=net/neristal/rrbridge/prototype/ingress
entries=("src/test/java/$pkg/PosixWatermarkLedgerTest.java" "src/test/java/$pkg/PosixRotationTest.java")
if [[ "$windows" == "1" ]]; then
  [[ -s "$jna" ]] || { echo "missing $jna: run scripts/test-windows-native-identity.sh first" >&2; exit 2; }
  printf '%s  %s\n' 34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6 "$jna" | sha256sum -c -
  cp="${cp}${sep}${jna}"
  entries+=("src/test/java/$pkg/WindowsWatermarkLedgerTest.java" "src/test/java/$pkg/WindowsRotationTest.java")
fi
if [[ "${1:-}" != "--run-only" ]]; then
  mkdir -p build/rlc2-ledger
  javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" \
    -d build/rlc2-ledger "${entries[@]}"
fi
java -cp "build/rlc2-ledger${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PosixWatermarkLedgerTest
# Phase 3d-1 seal / RLM1 rotation manifest + session ingest.
java -cp "build/rlc2-ledger${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PosixRotationTest
if [[ "$windows" == "1" ]]; then
  java -cp "build/rlc2-ledger${sep}${cp}" net.neristal.rrbridge.prototype.ingress.WindowsWatermarkLedgerTest
  java -cp "build/rlc2-ledger${sep}${cp}" net.neristal.rrbridge.prototype.ingress.WindowsRotationTest
fi
