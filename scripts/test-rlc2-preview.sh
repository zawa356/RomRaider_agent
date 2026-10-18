#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
pkg=net/neristal/rrbridge/prototype/ingress
entries=("src/main/java/$pkg/Rlc2PreviewCli.java" "src/test/java/$pkg/Rlc2PreviewStoreTest.java" "src/test/java/$pkg/Rlc2PreviewFaultTest.java" "src/test/java/$pkg/Rlc2PreviewLoadTest.java")
if [[ "$windows" == 1 ]]; then
  jna=build/identity-deps/jna-5.14.0.jar
  printf '%s  %s\n' 34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6 "$jna" | sha256sum -c -
  cp="${cp}${sep}${jna}"
  entries+=("src/test/java/$pkg/WindowsRlc2PreviewCli.java")
fi
entries+=("src/test/java/$pkg/Rlc2IntegrationSchemaTest.java")
entries+=("src/test/java/$pkg/Rlc2IntegrationStoreTest.java")
entries+=("src/test/java/$pkg/Rlc2IntegrationSnapshotTest.java")
entries+=("src/test/java/$pkg/Rlc2StoreSchemaTest.java")
entries+=("src/test/java/$pkg/Rlc2IntegrationFaultTest.java")
entries+=("src/main/java/$pkg/Rlc2IntegrationCli.java")
if [[ "$windows" == 1 ]]; then entries+=("src/test/java/$pkg/WindowsRlc2IntegrationCli.java"); fi
if [[ "${1:-}" != "--run-only" ]]; then
  mkdir -p build/rlc2-preview
  javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d build/rlc2-preview "${entries[@]}"
fi
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2PreviewStoreTest build/rlc2-preview-fixture.path
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2PreviewFaultTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2PreviewLoadTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationSchemaTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationStoreTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationSnapshotTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2StoreSchemaTest
java -cp "build/rlc2-preview${sep}${cp}" net.neristal.rrbridge.prototype.ingress.Rlc2IntegrationFaultTest
