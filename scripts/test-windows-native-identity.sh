#!/usr/bin/env bash
# Test-only native identity dependency. Never adds JNA to product lib/dist.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/identity-deps build/ingress-journal-prototype
jar=build/identity-deps/jna-5.14.0.jar
if [[ ! -f "$jar" ]]; then
  curl -fsSL https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar -o "$jar"
fi
printf '%s  %s\n' 34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6 "$jar" | sha256sum -c -
javac --release 8 -encoding UTF-8 -cp "$jar;build/ingress-journal-prototype" \
  -d build/ingress-journal-prototype \
  src/test/java/net/neristal/rrbridge/prototype/ingress/WindowsNativeIdentityTest.java
# Java needs Windows classpath separator even inside Git Bash.
java -cp "$jar;build/ingress-journal-prototype" \
  net.neristal.rrbridge.prototype.ingress.WindowsNativeIdentityTest
