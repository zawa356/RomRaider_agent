#!/usr/bin/env bash
# Phase VIII-3: RLIR1 readiness through the single entry (--readiness), schema rules, actual entry JVM. Synthetic only.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/phase7-packaged.sh Phase8ReadinessCliTest "$@"
