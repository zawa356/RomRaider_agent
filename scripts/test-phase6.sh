#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for test in writer drive preflight transition staging-parent; do
 bash "scripts/test-phase6-${test}.sh" "$@"
done
echo 'Phase VI synthetic tests completed; production/ACK/launch remain disabled'
