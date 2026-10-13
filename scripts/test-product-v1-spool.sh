#!/usr/bin/env bash
# Standalone product-v1 spool tests: no RomRaider.jar, no ECU and no tokens.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib build/product-v1-test
JDBC_VERSION=3.46.1.3
SLF4J_VERSION=2.0.16
# Maven Central occasionally returns transient 403 on Windows GitHub runners.
# Use two official Maven Central endpoints, bounded retries and validate
# the full jar BEFORE atomically publishing it to the local classpath.
download_jar() {
  local target="$1" artifact="$2" tmp="${1}.partial"
  if [[ -s "$target" ]] && jar tf "$target" >/dev/null 2>&1; then return 0; fi
  rm -f "$target" "$tmp"
  local base
  for base in https://repo.maven.apache.org/maven2 https://repo1.maven.org/maven2; do
    if curl --fail --silent --show-error --location \
        --retry 3 --retry-all-errors --retry-delay 2 --connect-timeout 10 \
        --max-time 90 --output "$tmp" "$base/$artifact"; then
      if jar tf "$tmp" >/dev/null 2>&1; then
        mv -f "$tmp" "$target"
        return 0
      fi
      echo "Invalid downloaded JAR from Maven Central, retrying mirror" >&2
    fi
    rm -f "$tmp"
  done
  echo "Unable to obtain valid build-only JAR: $target" >&2
  return 1
}
download_jar lib/sqlite-jdbc.jar \
  "org/xerial/sqlite-jdbc/${JDBC_VERSION}/sqlite-jdbc-${JDBC_VERSION}.jar"
for name in slf4j-api slf4j-nop; do
  download_jar "lib/${name}.jar" \
    "org/slf4j/${name}/${SLF4J_VERSION}/${name}-${SLF4J_VERSION}.jar"
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
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.LegacyTsvBridgeTest
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.IncrementalTsvBridgeTest
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.ProductDeliveryCoordinatorTest
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.ProductLegacyScannerTest
java -cp "build/product-v1-test${sep}${cp}" \
  net.neristal.rrbridge.product.v1.ProductBridgeWorkerTest
