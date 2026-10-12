#!/usr/bin/env bash
# sqlite-jdbc を lib/ に取得する
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
V=3.46.1.3
curl -fsSL -o lib/sqlite-jdbc.jar "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/${V}/sqlite-jdbc-${V}.jar"
echo "lib/sqlite-jdbc.jar (${V})"
