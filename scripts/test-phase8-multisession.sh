#!/usr/bin/env bash
# Phase VIII-2: >=10 small Sessions through the single entry (RLMS1), restart 0 duplicates, cross-session refusals.
# ROMLOGGER_PHASE8_SESSIONS (default 10, minimum 10). Synthetic only; actual supervisor + child JVMs.
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/phase7-packaged.sh Phase8MultiSessionTest "$@"
