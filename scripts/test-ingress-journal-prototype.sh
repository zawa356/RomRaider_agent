#!/usr/bin/env bash
# D2b-2c-4a prototype ONLY. No RomRaider.jar, ECU, Server or actual disk-fill.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/ingress-journal-prototype
javac --release 8 -encoding UTF-8 -d build/ingress-journal-prototype \
  src/main/java/net/neristal/rrbridge/prototype/ingress/IngressJournalPrototype.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/IngressHandoffPrototype.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/JournalIdentityPrototype.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/LiveJournalReadProbe.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/SplitLockJournalPrototype.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/DurableWatermarkPrototype.java \
  src/main/java/net/neristal/rrbridge/prototype/ingress/WatermarkV2Prototype.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/IngressJournalPrototypeTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/IngressHandoffPrototypeTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/LiveJournalReadProbeTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/SplitLockJournalPrototypeTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/JournalIdentityComparisonTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/DurableWatermarkPrototypeTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/WatermarkV2Scenarios.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/WatermarkV2ContractTest.java \
  src/test/java/net/neristal/rrbridge/prototype/ingress/PosixWatermarkV2Test.java
java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.IngressJournalPrototypeTest

java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.IngressHandoffPrototypeTest


java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.LiveJournalReadProbeTest

java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.SplitLockJournalPrototypeTest

java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.DurableWatermarkPrototypeTest

java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.JournalIdentityComparisonTest

# Phase 3b RLC2: codec contract (all OS) and POSIX dev/inode scenarios (NOT RUN without unix view).
java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.WatermarkV2ContractTest

java -cp build/ingress-journal-prototype \
  net.neristal.rrbridge.prototype.ingress.PosixWatermarkV2Test
