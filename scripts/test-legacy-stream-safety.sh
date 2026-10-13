#!/usr/bin/env bash
# Test production legacy Stream.java without RomRaider.jar or an ECU.
# Compile against test-only Java stubs in an isolated output directory.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/legacy-safety
javac --release 8 -encoding UTF-8 -d build/legacy-safety \
  src/test/poc-stubs/com/romraider/logger/ecu/EcuLogger.java \
  src/test/poc-stubs/com/romraider/logger/ecu/comms/query/Response.java \
  src/test/poc-stubs/com/romraider/logger/ecu/definition/LoggerData.java \
  src/test/poc-stubs/com/romraider/logger/ecu/ui/handler/DataUpdateHandler.java \
  src/test/poc-stubs/net/neristal/rrbridge/Agent.java \
  src/test/poc-stubs/net/neristal/rrbridge/Util.java \
  src/test/poc-stubs/net/neristal/rrbridge/LinkMonitor.java \
  src/main/java/net/neristal/rrbridge/Stream.java \
  src/test/poc-safety/net/neristal/rrbridge/LegacyStreamSafetyTest.java
java -cp build/legacy-safety net.neristal.rrbridge.LegacyStreamSafetyTest
