#!/usr/bin/env bash
# Phase IV focused tests. Fresh synthetic fixtures only; no Worker/service/ECU/production DB access.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; entries=()
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; entries+=(src/test/java/net/neristal/rrbridge/prototype/ingress/WindowsWatermarkV2Test.java);; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
# Windows physical source identity in tests uses the pinned test-only JNA dependency (as test-phase3-deployment.sh).
if [[ "$sep" == ";" ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase4-classes
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out"
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out" \
  "${entries[@]}" src/main/java/net/neristal/rrbridge/product/v1/*.java \
  src/test/java/net/neristal/rrbridge/product/v1/Phase4ReviewRedTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/DeploymentTransitionTest.java \
  src/test/java/net/neristal/rrbridge/product/v1/ArtifactProvenanceTest.java \
  src/test/java/net/neristal/rrbridge/product/v1/ProvenanceChild.java
fi
run(){ java -cp "${out}${sep}${cp}" "$@"; }
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
run net.neristal.rrbridge.product.v1.Phase4ReviewRedTest
run net.neristal.rrbridge.prototype.ingress.DeploymentTransitionTest
run net.neristal.rrbridge.product.v1.ArtifactProvenanceTest
echo "Phase IV focused tests completed"
