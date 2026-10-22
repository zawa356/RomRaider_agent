#!/usr/bin/env bash
# Phase IX-1 (R9-01): explicit, reproducible build of the four read-only offline tool CLIs that were split out of the
# OfflineCandidateEntry closure in Phase VIII-1b. Output is a separate tool JAR that must never be installed next to the
# approved entry JAR (RLDI1 would report UNAPPROVED_MAIN_METHOD). No test sources, no JNA, no Main-Class.
#   bash scripts/build-offline-tools.sh [OUT_DIR]     (default build/offline-tools; OUT_DIR is wiped first)
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
out="${1:-build/offline-tools}"
# OUT_DIR is wiped, so it must resolve strictly inside <repo>/build: relative, no empty/./.. component, at least one
# component below build, and no symlink/junction at any level (build itself included). A string prefix check is not enough.
refuse(){ echo "OUT_DIR refused ($1): must be build/<dir>[/...] without links, '.', '..' or absolute form"; exit 2; }
case "$out" in /*|*\\*|*:*) refuse absolute;; esac
IFS=/ read -r -a parts <<< "$out"
[[ ${#parts[@]} -ge 2 && "${parts[0]}" == build ]] || refuse not-below-build
for part in "${parts[@]}"; do [[ -n "$part" && "$part" != . && "$part" != .. ]] || refuse component; done
root=$(pwd -P); probe="$root"
for part in "${parts[@]}"; do probe="$probe/$part"; [[ -L "$probe" ]] && refuse link; done
if [[ -d "$root/build" ]]; then [[ "$(cd "$root/build" && pwd -P)" == "$root/build" ]] || refuse build-alias; fi
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
