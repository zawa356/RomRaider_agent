#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; entries=()
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; entries+=(src/test/java/net/neristal/rrbridge/prototype/ingress/WindowsWatermarkV2Test.java);; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ "$sep" == ";" ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
if [[ "${1:-}" != "--run-only" ]]; then
 mkdir -p build/phase2
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d build/phase2 "${entries[@]}" src/test/java/net/neristal/rrbridge/product/v1/LaneOwnershipTest.java src/test/java/net/neristal/rrbridge/product/v1/ProductLaneTest.java src/test/java/net/neristal/rrbridge/product/v1/V1LaneMigrationTest.java src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2LaneConflictTest.java src/test/java/net/neristal/rrbridge/prototype/ingress/Rlc2PublicationTest.java
fi
for test in product.v1.LaneOwnershipTest product.v1.ProductLaneTest product.v1.V1LaneMigrationTest prototype.ingress.Rlc2LaneConflictTest prototype.ingress.Rlc2PublicationTest; do
 java -cp "build/phase2${sep}${cp}" "net.neristal.rrbridge.$test"
done
