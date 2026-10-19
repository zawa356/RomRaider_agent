#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/phase6-drive-classes
if [[ "${1:-}" != "--run-only" ]]; then
 sep=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
 mkdir -p "$out"
 javac --release 8 -encoding UTF-8 -sourcepath "src/main/java${sep}src/test/java" -d "$out" src/test/java/net/neristal/rrbridge/product/v1/Phase6DriveTest.java src/test/java/net/neristal/rrbridge/product/v1/Phase6DriveBindingRedTest.java
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -cp "$out" net.neristal.rrbridge.product.v1.Phase6DriveTest
java -cp "$out" net.neristal.rrbridge.product.v1.Phase6DriveBindingRedTest
