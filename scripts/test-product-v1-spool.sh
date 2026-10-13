#!/usr/bin/env bash
# Standalone product-v1 spool tests: no RomRaider.jar, no ECU and no tokens.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib build/product-v1-test
JDBC_VERSION=3.46.1.3
SLF4J_VERSION=2.0.16
if [[ ! -s lib/sqlite-jdbc.jar ]]; then
  curl --fail --silent --show-error --location \
    -o lib/sqlite-jdbc.jar \
    "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/${JDBC_VERSION}/sqlite-jdbc-${JDBC_VERSION}.jar"
fi
for name in slf4j-api slf4j-nop; do
  if [[ ! -s "lib/${name}.jar" ]]; then
    curl --fail --silent --show-error --location \
      -o "lib/${name}.jar" \
      "https://repo.maven.apache.org/maven2/org/slf4j/${name}/${SLF4J_VERSION}/${name}-${SLF4J_VERSION}.jar"
  fi
done
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
javac --release 8 -encoding UTF-8 -d build/product-v1-test \
  src/main/java/net/neristal/rrbridge/product/v1/*.java \
  src/test/java/net/neristal/rrbridge/product/v1/*.java
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.DurableSpoolTest
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.ProductSenderTest
