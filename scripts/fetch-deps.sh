#!/usr/bin/env bash
# sqlite-jdbc を lib/ に取得する
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
V=3.46.1.3
tmp=lib/sqlite-jdbc.jar.partial
trap 'rm -f "$tmp"' EXIT
curl -fsSL -o "$tmp" "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/${V}/sqlite-jdbc-${V}.jar"
expected="$(while read -r checksum path; do
  if [[ "${path%$'\r'}" == "lib/sqlite-jdbc.jar" ]]; then printf '%s' "$checksum"; break; fi
done < lib/test-dependencies.sha256)"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo 'Missing pinned SQLite checksum' >&2; exit 1; }
printf '%s  %s\n' "$expected" "$tmp" | sha256sum -c -
mv -f "$tmp" lib/sqlite-jdbc.jar
echo "lib/sqlite-jdbc.jar (${V})"
