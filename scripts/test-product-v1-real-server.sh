#!/usr/bin/env bash
# Product v1 Agent -> REAL FastAPI -> REAL PostgreSQL (disposable CI database).
# Requires RomRaiderServer checked out separately and a working DATABASE_URL.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ROMLOGGER_SERVER_PATH:?Set path to checked-out RomRaiderServer}"
: "${DATABASE_URL:?Disposable PostgreSQL DATABASE_URL required}"
# Refuse production or remote databases even when the caller supplies a URL.
# Both explicit opt-in and a disposable database name are required.
: "${ROMLOGGER_E2E_DISPOSABLE:?Set ROMLOGGER_E2E_DISPOSABLE=1 for a disposable local DB}"
if [[ "$ROMLOGGER_E2E_DISPOSABLE" != "1" ]]; then
  echo "Explicit disposable E2E consent value must be 1" >&2
  exit 2
fi
python3 - <<'PY'
import os
import re
from urllib.parse import urlparse
u=urlparse(os.environ["DATABASE_URL"])
dbname=u.path.lstrip("/")
# Reject libpq URL parameters (including ?host=remote), extra path segments,
# encoded database names, and any non-test name BEFORE opening the database.
if (u.scheme not in ("postgresql","postgres")
        or u.hostname not in ("localhost","127.0.0.1")
        or u.query or u.fragment or u.params
        or re.fullmatch(r"romlogger_[a-z0-9_]*_e2e", dbname) is None):
    raise SystemExit("Refusing E2E run: require local PostgreSQL and romlogger_*_e2e database without URL overrides")
PY
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
# Duplicate-only replay MUST NOT create more than three observations.
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
    assert r["n"]==3 and r["source"]=="synthetic", r
    assert p["n"]==1, p
print("REAL PostgreSQL verification: PASS (3 records, 1 profile, synthetic provenance)")
')

# Phase 2.1-D2b-2c-4b-2c: run the newly integrated journal ingress
# through the SAME real Agent/Server runtime, not a mock transport.
java -cp "build/product-v1-test:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
  net.neristal.rrbridge.product.v1.JournalServerE2E
(cd "$SERVER_ROOT" && python -c '
import os
import psycopg
with psycopg.connect(os.environ["DATABASE_URL"]) as db:
    device=os.environ["ROMLOGGER_E2E_DEVICE_ID"]
    n=db.execute("SELECT count(*) FROM v1_records WHERE device_id=%s", (device,)).fetchone()[0]
    profiles=db.execute("SELECT count(*) FROM v1_profile_revisions WHERE device_id=%s",(device,)).fetchone()[0]
    distinct=db.execute("SELECT count(DISTINCT record_id) FROM v1_records WHERE device_id=%s",(device,)).fetchone()[0]
    untrusted=db.execute("SELECT count(*) FROM v1_records WHERE device_id=%s AND source_kind<>%s",(device,"synthetic")).fetchone()[0]
    assert (n,profiles,distinct,untrusted)==(6,2,6,0),(n,profiles,distinct,untrusted)
print("REAL PostgreSQL verification: PASS (TSV 3 + journal 3, two profiles, no duplicates)")
')

# Phase D2b-2c-4b-2d-1: run the REAL separate-JVM offline Worker CLI.
# It must never auto-select journal when called with legacy --once.
java -cp "build/product-v1-test:lib/sqlite-jdbc.jar:lib/slf4j-api.jar:lib/slf4j-nop.jar" \
  net.neristal.rrbridge.product.v1.JournalWorkerServerE2E
(cd "$SERVER_ROOT" && python -c '
import os
import psycopg
with psycopg.connect(os.environ["DATABASE_URL"]) as db:
    device=os.environ["ROMLOGGER_E2E_DEVICE_ID"]
    n=db.execute("SELECT count(*) FROM v1_records WHERE device_id=%s",(device,)).fetchone()[0]
    p=db.execute("SELECT count(*) FROM v1_profile_revisions WHERE device_id=%s",(device,)).fetchone()[0]
    uuids=db.execute("SELECT count(DISTINCT record_id) FROM v1_records WHERE device_id=%s",(device,)).fetchone()[0]
    source=db.execute("SELECT count(*) FROM v1_records WHERE device_id=%s AND source_kind=%s",(device,"synthetic")).fetchone()[0]
    assert (n,p,uuids,source)==(7,3,7,7),(n,p,uuids,source)
print("REAL PostgreSQL Worker verification: PASS (TSV 3 + journal 3 + CLI Worker 1, three profiles, no duplicates)")
')
