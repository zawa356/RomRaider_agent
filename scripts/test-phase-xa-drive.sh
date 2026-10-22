#!/usr/bin/env bash
# Phase X-A: WindowsDriveGate probe failure classes + real probe latency (codes/ms only). --run-only for actual x86.
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/phase-xa-drive
if [[ "${1:-}" != "--run-only" ]]; then rm -rf "$out"; mkdir -p "$out"; javac --release 8 -encoding UTF-8 -sourcepath src/main/java -d "$out" src/test/java/net/neristal/rrbridge/product/v1/PhaseXaDriveProbeTest.java; fi
java -cp "$out" net.neristal.rrbridge.product.v1.PhaseXaDriveProbeTest
