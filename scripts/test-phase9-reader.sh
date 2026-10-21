#!/usr/bin/env bash
# Phase IX-2 RLWR1 Windows product reader candidate (synthetic only, not adopted).
# Fake-native logic test on every OS; real NTFS + RLC2 scenarios on Windows (x64 JDK17; --run-only under actual Zulu8 x86).
set -euo pipefail
cd "$(dirname "$0")/.."
sep=":"; windows=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; windows=1;; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
jna=build/identity-deps/jna-5.14.0.jar
out=build/phase9-reader
pkg=src/test/java/net/neristal/rrbridge/prototype/ingress
if [[ $windows == 1 ]]; then
 printf '%s  %s\n' 34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6 "$jna" | sha256sum -c -
 cp="${cp}${sep}${jna}"
fi
if [[ "${1:-}" != "--run-only" ]]; then
 rm -rf "$out"; mkdir -p "$out/fake" "$out/native"
 # Fake test: product reader logic only, compiled WITHOUT JNA on the classpath (the JNA binding is not reachable from it).
 javac --release 8 -encoding UTF-8 -cp "lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar" -implicit:class -sourcepath "src/main/java${sep}src/test/java" -d "$out/fake" \
   -Xlint:-options $pkg/WindowsProductReaderFakeTest.java
 [[ -f "$out/fake/net/neristal/rrbridge/prototype/ingress/WindowsProductReaderFakeTest.class" ]] || { echo "FAIL fake test did not compile"; exit 1; }
 if [[ $windows == 1 ]]; then
  javac --release 8 -encoding UTF-8 -cp "$cp" -sourcepath "src/main/java${sep}src/test/java" -d "$out/native" $pkg/WindowsProductReaderNativeTest.java src/main/java/net/neristal/rrbridge/prototype/ingress/WindowsProductReaderJna.java
 fi
fi
if [[ -n "${ROMLOGGER_EXPECT_DATA_MODEL:-}" ]]; then
 actual=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p')
 [[ "$actual" == "$ROMLOGGER_EXPECT_DATA_MODEL" ]] || { echo "data model $actual != $ROMLOGGER_EXPECT_DATA_MODEL"; exit 1; }
fi
java -cp "$out/fake${sep}lib/sqlite-jdbc.jar" net.neristal.rrbridge.prototype.ingress.WindowsProductReaderFakeTest
if [[ $windows == 1 ]]; then
 java -cp "$out/native${sep}${cp}" net.neristal.rrbridge.prototype.ingress.WindowsProductReaderNativeTest
else
 echo "NOT_RUN RLWR1 native NTFS test (Windows only); POSIX refuses READER_PLATFORM_UNSUPPORTED (fake test)"
fi
echo "Phase IX-2 RLWR1 completed; adoption BLOCKED; production/ACK disabled"
