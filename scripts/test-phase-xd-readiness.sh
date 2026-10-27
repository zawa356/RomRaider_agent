#!/usr/bin/env bash
# Phase X-D1: RLDR1 required-dependency readiness candidate (read-only, synthetic-only, default-off Java API). Owned temp
# installs built from the real entry closure JAR and the real pinned dependency JARs; nothing is launched by the readiness
# API. The closure probe starts short child JVMs (owned in-memory SQLite only) to measure which dependencies the SQLite
# path really needs. --run-only re-runs compiled classes (actual Java 8 x86); --expect-red runs the readiness test against
# code without RLDR1 (expects RED_COUNT>0).
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
if [[ -f build/identity-deps/jna-5.14.0.jar ]]; then cp="${cp}${sep}build/identity-deps/jna-5.14.0.jar"; fi
out=build/phase-xd-readiness; red=false; runonly=false
for arg in "$@"; do case $arg in --expect-red) red=true;; --run-only) runonly=true;; esac; done
T=src/test/java/net/neristal/rrbridge/prototype/ingress
if [[ $runonly == false ]]; then
 rm -rf "$out"; mkdir -p "$out/closure" "$out/test" "$out/probe" "$out/jars"
 # Same approved-entry closure as scripts/test-phase-xc-rldi2.sh (RLWR1 JNA binding on Windows).
 binding=(); if [[ $windows == 1 ]]; then binding=(src/main/java/net/neristal/rrbridge/prototype/ingress/WindowsProductReaderJna.java); fi
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath src/main/java -d "$out/closure" src/main/java/net/neristal/rrbridge/prototype/ingress/OfflineCandidateEntry.java "${binding[@]}"
 (cd "$out/closure" && jar cf ../jars/romlogger-offline.jar .)
 javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out/test" "$T/PhaseXdReadinessTest.java" "$T/PhaseXdClosureProbe.java" "$T/PhaseXdWindowsAclProbe.java"
 # The probe child must see no test or product classes besides itself: compiled alone, JDK classes only.
 javac --release 8 -encoding UTF-8 -d "$out/probe" "$T/PhaseXdClosureProbe.java"
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p' | tr -d '\r')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
abs(){ if [[ $windows == 1 ]]; then cygpath -w "$(pwd)/$1"; else echo "$(pwd)/$1"; fi; }
if [[ $red == false ]]; then
 # Which JARs does the SQLite path need? Measured with real child JVMs (exact class path, no JVM options).
 jnaarg="-"; if [[ -f build/identity-deps/jna-5.14.0.jar ]]; then jnaarg="$(abs build/identity-deps/jna-5.14.0.jar)"; fi
 java -cp "$out/test" net.neristal.rrbridge.prototype.ingress.PhaseXdClosureProbe "$(abs "$out/probe")" "$(abs lib/sqlite-jdbc.jar)" "$(abs lib/slf4j-api.jar)" "$(abs lib/slf4j-nop.jar)" "$jnaarg"
fi
if [[ $red == false ]]; then
 # X-D2 read-only Windows ACL PoC (test code; reads owner/DACL of a new owned fixture and its real parents, sets no ACL).
 java -cp "$out/test" net.neristal.rrbridge.prototype.ingress.PhaseXdWindowsAclProbe
fi
java -Xmx512m -Dphasexd.expectRed=$red -cp "$out/test${sep}${cp}" net.neristal.rrbridge.prototype.ingress.PhaseXdReadinessTest "$(abs "$out/jars/romlogger-offline.jar")"
echo "Phase X-D RLDR1 readiness suite completed; launch/production/ACK remain disabled"
