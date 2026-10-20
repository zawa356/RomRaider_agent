#!/usr/bin/env bash
# Phase VII-1 single Offline Candidate Entry acceptance. Packages the product classes into a JAR (plus, on Windows, the
# test-only native reader JAR) and starts real supervisor/child JVMs from a pinned RLOP1 profile. Synthetic fixtures only.
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
deps="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ $windows == 1 ]]; then deps="${deps}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase7
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out/main" "$out/test" "$out/jars"
 # Dependency closure of the entry only (the javaagent classes need RomRaider.jar and are not part of this offline tool).
 javac --release 8 -encoding UTF-8 -cp "$deps" -sourcepath src/main/java -d "$out/main" src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java
 (cd "$out/main" && jar cf ../jars/romlogger-offline.jar .)
 tests=(src/test/java/net/neristal/rrbridge/prototype/ingress/Phase7EntryTest.java)
 if [[ $windows == 1 ]]; then tests+=(src/test/java/net/neristal/rrbridge/prototype/ingress/WindowsWatermarkV2Test.java); else tests+=(src/test/java/net/neristal/rrbridge/prototype/ingress/PosixWatermarkV2Test.java); fi
 javac --release 8 -encoding UTF-8 -cp "$out/main${sep}${deps}" -sourcepath "src/test/java${sep}src/main/java" -d "$out/test" "${tests[@]}"
 # A class must come from exactly one JAR (RLAP1 refuses shadowing): drop recompiled copies of packaged product classes.
 (cd "$out/main" && find . -name '*.class') | while read -r c; do rm -f "$out/test/$c"; done
 (cd "$out/test" && jar cf ../jars/romlogger-phase7-test.jar .)
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
abs(){ if [[ $windows == 1 ]]; then cygpath -w "$(pwd)/$1"; else echo "$(pwd)/$1"; fi; }
jars=("$(abs "$out/jars/romlogger-offline.jar")" "$(abs lib/sqlite-jdbc.jar)" "$(abs lib/slf4j-api.jar)" "$(abs lib/slf4j-nop.jar)")
if [[ $windows == 1 ]]; then jars+=("$(abs build/identity-deps/jna-5.14.0.jar)"); fi
jars+=("$(abs "$out/jars/romlogger-phase7-test.jar")")
java -cp "$out/test${sep}$out/main${sep}${deps}" net.neristal.rrbridge.prototype.ingress.Phase7EntryTest "${jars[@]}"
echo 'Phase VII entry tests completed; launch/production/ACK remain disabled'
