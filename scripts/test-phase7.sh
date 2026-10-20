#!/usr/bin/env bash
# Phase VII synthetic acceptance: entry, readiness, E2E, adversarial. Pass --run-only after a JDK17 compile to rerun under Java 8.
# scripts/test-phase7-review-red.sh is a historical reproduction (expected exit1 against the unchanged old CLI) and is not included.
set -euo pipefail
cd "$(dirname "$0")/.."
for test in entry readiness e2e adversarial; do
 bash "scripts/test-phase7-${test}.sh" "$@"
done
echo 'Phase VII synthetic tests completed; launch/production/ACK remain disabled; writer absence WRITER_UNPROVEN'
