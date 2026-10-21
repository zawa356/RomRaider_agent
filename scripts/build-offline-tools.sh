#!/usr/bin/env bash
# Phase IX-1 (R9-01): explicit, reproducible build of the four read-only offline tool CLIs that were split out of the
# OfflineCandidateEntry closure in Phase VIII-1b. Output is a separate tool JAR that must never be installed next to the
# approved entry JAR (RLDI1 would report UNAPPROVED_MAIN_METHOD). No test sources, no JNA, no Main-Class.
#   bash scripts/build-offline-tools.sh [OUT_DIR]     (default build/offline-tools; OUT_DIR is wiped first)
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
out="${1:-build/offline-tools}"
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
clis=(net/neristal/rrbridge/product/v1/ArtifactProvenanceCli net/neristal/rrbridge/product/v1/DeploymentBindingCli
      net/neristal/rrbridge/prototype/ingress/DeploymentTransitionCli net/neristal/rrbridge/prototype/ingress/IngressDiagnosticsCli)
rm -rf "$out"; mkdir -p "$out/classes"
srcs=(); for c in "${clis[@]}"; do srcs+=("src/main/java/$c.java"); done
# -sourcepath src/main/java only: the tool closure is built from product sources, never from test sources.
javac --release 8 -encoding UTF-8 -implicit:class -cp "$cp" -sourcepath src/main/java -d "$out/classes" "${srcs[@]}"
for c in "${clis[@]}"; do [[ -f "$out/classes/$c.class" ]] || { echo "TOOL_CLASS_MISSING $c"; exit 1; }; done
(cd "$out/classes" && jar cf ../romlogger-offline-tools.jar .)
echo "offline tools jar: $out/romlogger-offline-tools.jar sha256=$(sha256sum "$out/romlogger-offline-tools.jar" | cut -c1-64)"
