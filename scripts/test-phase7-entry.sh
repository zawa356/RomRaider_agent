#!/usr/bin/env bash
# Phase VII-1 single Offline Candidate Entry acceptance (real supervisor/child JVMs from pinned profiles).
set -euo pipefail
exec bash "$(dirname "$0")/phase7-packaged.sh" Phase7EntryTest "$@"
