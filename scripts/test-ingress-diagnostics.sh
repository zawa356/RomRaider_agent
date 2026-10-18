#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
pkg=net/neristal/rrbridge/prototype/ingress
entries=("src/test/java/$pkg/IngressDiagnosticsTest.java")
if [[ "$windows" == 1 ]]; then
  jna=build/identity-deps/jna-5.14.0.jar
  printf '%s  %s\n' 34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6 "$jna" | sha256sum -c -
  cp="${cp}${sep}${jna}"
  entries+=("src/test/java/$pkg/WindowsIngressDiagnosticsCli.java")
fi
if [[ "${1:-}" != "--run-only" ]]; then
  mkdir -p build/ingress-diagnostics
  javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d build/ingress-diagnostics "${entries[@]}"
fi
java -cp "build/ingress-diagnostics${sep}${cp}" net.neristal.rrbridge.prototype.ingress.IngressDiagnosticsTest
