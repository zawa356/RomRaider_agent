#!/usr/bin/env bash
# Phase VII-3 synthetic-owned E2E through the single entry. Optional, explicitly opted-in owned PostgreSQL + actual
# FastAPI HTTP step reuses scripts/test-rlc2-owned-postgres-http.py on the E2E candidate (test ACK only, Agent bytes unchanged).
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/phase7-packaged.sh Phase7E2ETest "$@"
if [[ "${ROMLOGGER_OWNED_PG_HTTP_ENABLE:-}" == "1" ]]; then
 [[ -n "${ROMLOGGER_HTTP_PYTHON:-}" && -n "${ROMLOGGER_OWNED_PG_BIN:-}" && -n "${ROMLOGGER_SERVER_PATH:-}" ]] || { echo "owned HTTP step needs ROMLOGGER_HTTP_PYTHON, ROMLOGGER_OWNED_PG_BIN, ROMLOGGER_SERVER_PATH"; exit 2; }
 before=$(sha256sum "$(cat build/rlc2-integration-fixture.path | tr '\' '/')" | cut -d' ' -f1)
 "$ROMLOGGER_HTTP_PYTHON" scripts/test-rlc2-owned-postgres-http.py
 after=$(sha256sum "$(cat build/rlc2-integration-fixture.path | tr '\' '/')" | cut -d' ' -f1)
 [[ "$before" == "$after" ]] || { echo "E2E candidate bytes changed by HTTP step"; exit 1; }
 echo "PASS owned HTTP step left the E2E candidate bytes unchanged ($after)"
else
 echo "NOT_RUN owned PostgreSQL/HTTP step (set ROMLOGGER_OWNED_PG_HTTP_ENABLE=1 with ROMLOGGER_HTTP_PYTHON, ROMLOGGER_OWNED_PG_BIN, ROMLOGGER_SERVER_PATH)"
fi
