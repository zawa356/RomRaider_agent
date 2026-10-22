#!/usr/bin/env bash
# Phase VII-4 adversarial separate-process checks against the single entry (synthetic fixtures only).
set -euo pipefail
exec bash "$(dirname "$0")/phase7-packaged.sh" Phase7AdversarialTest "$@"
