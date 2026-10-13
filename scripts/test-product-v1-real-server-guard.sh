#!/usr/bin/env bash
# Negative preflight only: NO Server checkout, NO database, NO network.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
verify_reject() {
  local title="$1" expected="$2"
  shift 2
  if "$@" >"$tmp" 2>&1; then
    echo "FAIL: $title unexpectedly passed safety guard" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" "$tmp"; then
    echo "FAIL: $title was rejected for an unexpected reason" >&2
    exit 1
  fi
  echo "PASS: $title"
}
script="scripts/test-product-v1-real-server.sh"
verify_reject "missing explicit disposable opt-in" \
  "ROMLOGGER_E2E_DISPOSABLE" \
  env -u ROMLOGGER_E2E_DISPOSABLE \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL=postgresql://test@127.0.0.1:25432/disposable_e2e \
    bash "$script"
verify_reject "reject non-local PostgreSQL host" \
  "Refusing E2E run" \
  env ROMLOGGER_E2E_DISPOSABLE=1 \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL=postgresql://test@untrusted.example:5432/disposable_e2e \
    bash "$script"
verify_reject "reject production-looking database name" \
  "Refusing E2E run" \
  env ROMLOGGER_E2E_DISPOSABLE=1 \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL=postgresql://test@127.0.0.1:25432/production \
    bash "$script"
verify_reject "reject non-PostgreSQL URL" \
  "Refusing E2E run" \
  env ROMLOGGER_E2E_DISPOSABLE=1 \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL=sqlite:///disposable_e2e \
    bash "$script"
verify_reject "reject libpq query host override" \
  "Refusing E2E run" \
  env ROMLOGGER_E2E_DISPOSABLE=1 \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL='postgresql://test@127.0.0.1:25432/romlogger_ci_e2e?host=untrusted.example' \
    bash "$script"
verify_reject "reject unapproved database prefix" \
  "Refusing E2E run" \
  env ROMLOGGER_E2E_DISPOSABLE=1 \
    ROMLOGGER_SERVER_PATH=/absent-server-fixture \
    DATABASE_URL=postgresql://test@127.0.0.1:25432/private_e2e \
    bash "$script"
echo "Disposable real-Server E2E preflight guard: 6 checks PASS (no DB access)"
