#!/usr/bin/env bash
# Product v1 Agent -> REAL FastAPI -> REAL PostgreSQL (disposable CI database).
# Requires RomRaiderServer checked out separately and a working DATABASE_URL.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ROMLOGGER_SERVER_PATH:?Set path to checked-out RomRaiderServer}"
: "${DATABASE_URL:?Disposable PostgreSQL DATABASE_URL required}"
SERVER_ROOT="$(cd "$ROMLOGGER_SERVER_PATH" && pwd)"
if [[ ! -f "$SERVER_ROOT/product_v1/api.py" ]]; then
  echo "Server source not found" >&2
  exit 2
fi
export ROMLOGGER_E2E_ORIGIN="http://127.0.0.1:18083"
# Never echo token; do not use set -x. Temporary credentials are mode 600.
umask 077
creds="$(mktemp)"
server_log="$(mktemp)"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -f "$creds" "$server_log"
}
trap cleanup EXIT
(cd "$SERVER_ROOT" && python -m product_v1.migrate upgrade)
(cd "$SERVER_ROOT" && python -m product_v1.migrate check)
(cd "$SERVER_ROOT" && python -m product_v1.admin --display-name "CI synthetic Agent E2E") > "$creds"
export ROMLOGGER_E2E_DEVICE_ID="$(python -c 'import json,sys;print(json.load(open(sys.argv[1]))["device_id"])' "$creds")"
export ROMLOGGER_E2E_VEHICLE_ID="$(python -c 'import json,sys;print(json.load(open(sys.argv[1]))["vehicle_id"])' "$creds")"
export ROMLOGGER_E2E_TOKEN="$(python -c 'import json,sys;print(json.load(open(sys.argv[1]))["device_token"])' "$creds")"
(cd "$SERVER_ROOT" && exec python -m uvicorn product_v1.api:app --host 127.0.0.1 --port 18083) > "$server_log" 2>&1 &
server_pid=$!
ready=0
for i in $(seq 1 50); do
  if curl --fail --silent "$ROMLOGGER_E2E_ORIGIN/health" >/dev/null; then
    ready=1;break
  fi
  sleep 0.5
done
if [[ "$ready" != "1" ]]; then
  echo "FastAPI did not become healthy (log withheld to avoid secret disclosure)" >&2
  exit 1
fi
java -cp "build/product-v1-test:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
  net.neristal.rrbridge.product.v1.ProductServerE2E
# The second Java delivery replay MUST NOT have created more than two records.
(cd "$SERVER_ROOT" && python -c '
import os
import psycopg
from psycopg.rows import dict_row
with psycopg.connect(os.environ["DATABASE_URL"], row_factory=dict_row) as db:
    r=db.execute("""SELECT COUNT(*) AS n, MIN(source_kind) AS source
                    FROM v1_records WHERE device_id=%s""",
                 (os.environ["ROMLOGGER_E2E_DEVICE_ID"],)).fetchone()
    p=db.execute("""SELECT COUNT(*) AS n FROM v1_profile_revisions
                    WHERE device_id=%s""",(os.environ["ROMLOGGER_E2E_DEVICE_ID"],)).fetchone()
    assert r["n"]==2 and r["source"]=="synthetic", r
    assert p["n"]==1, p
print("REAL PostgreSQL verification: PASS (2 records, 1 profile, synthetic provenance)")
')
