#!/usr/bin/env bash
# Isolated phase-4b2a SQLite contract test; no ECU, real Server or production DB.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/test-product-v1-spool.sh
mkdir -p build/ingress-sqlite-receipts
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";";; esac
cp="lib/sqlite-jdbc.jar${sep}lib/slf4j-api.jar${sep}lib/slf4j-nop.jar"
javac --release 8 -encoding UTF-8 -cp "$cp" -d build/ingress-sqlite-receipts \
 src/main/java/net/neristal/rrbridge/prototype/ingress/IngressJournalPrototype.java \
 src/main/java/net/neristal/rrbridge/prototype/ingress/JournalIdentityPrototype.java \
 src/main/java/net/neristal/rrbridge/prototype/ingress/JournalSqliteReceiptPrototype.java \
 src/test/java/net/neristal/rrbridge/prototype/ingress/JournalSqliteReceiptPrototypeTest.java
java -cp "build/ingress-sqlite-receipts${sep}${cp}" \
 net.neristal.rrbridge.prototype.ingress.JournalSqliteReceiptPrototypeTest
