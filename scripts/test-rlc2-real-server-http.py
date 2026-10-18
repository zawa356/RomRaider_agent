"""Real loopback FastAPI/Uvicorn + owned PostgreSQL schema. Test ACK never updates Agent."""
import copy
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlencode
from uuid import uuid4

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("preview_guard", HERE / "test-rlc2-preview-postgres.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
checks = 0


def check(ok, message):
    global checks
    if not ok:
        print("FAIL " + message, flush=True)
        raise AssertionError(message)
    checks += 1
    print("PASS " + message, flush=True)


def fixture():
    path = Path(Path("build/rlc2-integration-fixture.path").read_text().strip()).resolve(strict=True)
    if not path.name.endswith(".rlc2-integration.db"):
        raise ValueError("dedicated integration fixture required")
    if any(Path(str(path) + s).exists() for s in ("-wal", "-shm", "-journal")):
        raise ValueError("closed fixture required")
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        db.row_factory = sqlite3.Row
        check(db.execute("PRAGMA application_id").fetchone()[0] == 0x524C4932 and db.execute("PRAGMA user_version").fetchone()[0] == 1, "versioned admitted fixture")
        tables = {name: [dict(r) for r in db.execute("SELECT * FROM " + name)] for name in
                  ("preview_sessions", "preview_sources", "preview_receipts", "preview_profiles", "preview_records", "integration_admissions", "integration_bindings")}
        ddl = [r[0] for r in db.execute("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid")]
    check(tuple(len(tables[t]) for t in tables) == (1, 3, 5, 1, 4, 3, 1), "three admitted physical sources, five receipts, four records")
    check(all(r["ack_eligible"] == 0 for r in tables["preview_records"]), "all Agent rows remain ACK-ineligible")
    for r in tables["preview_receipts"]:
        check(hashlib.sha256(r["body_tsv"].encode()).hexdigest() == r["body_sha256"], "original receipt hash verified")
    for r in tables["preview_records"]:
        body = json.loads(r["body_json"])
        check(body["record_id"] == r["record_id"] and body["source_kind"] in {"synthetic", "agent"}, "immutable synthetic v1 record")
    return path, tables, ddl


def request(port, method, path, body, token):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=8)
    try:
        conn.request(method, path, json.dumps(body, allow_nan=False), {"Content-Type": "application/json", "Authorization": "Bearer " + token})
        response = conn.getresponse()
        return response.status, json.loads(response.read())
    finally:
        conn.close()


def pg_candidate_migration(url, ddl):
    import psycopg
    statements = []
    for text in ddl:
        text = re.sub(r"\bBLOB\b", "BYTEA", text, flags=re.I)
        text = re.sub(r"CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ", text, count=1, flags=re.I)
        text = re.sub(r"CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ", text, count=1, flags=re.I)
        statements.append(text)
    try:
        with psycopg.connect(url) as db:
            for text in statements[:4]:
                db.execute(text)
            raise RuntimeError("synthetic migration interruption")
    except RuntimeError:
        pass
    with psycopg.connect(url) as db:
        check(db.execute("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name LIKE 'preview_%'").fetchone()[0] == 0, "PostgreSQL candidate interrupted DDL fully rolls back")
    for attempt in range(2):
        with psycopg.connect(url) as db:
            for text in statements:
                db.execute(text)
            db.execute("CREATE TABLE IF NOT EXISTS integration_schema_version(singleton int PRIMARY KEY CHECK(singleton=1),revision int NOT NULL CHECK(revision=1))")
            db.execute("INSERT INTO integration_schema_version VALUES(1,1) ON CONFLICT DO NOTHING")
            check(db.execute("SELECT revision FROM integration_schema_version").fetchone()[0] == 1, "PostgreSQL candidate explicit migration/replay revision1")


def main():
    if os.environ.get("ROMLOGGER_RLC2_HTTP_ENABLE") != "1":
        raise ValueError("explicit HTTP test opt-in required")
    url = guard.destination()  # Strict loopback disposable DB, no caller URI overrides.
    root = Path(os.environ["ROMLOGGER_SERVER_PATH"]).resolve(strict=True)
    if not (root / "product_v1/api.py").is_file():
        raise ValueError("actual Server checkout required")
    path, data, ddl = fixture()
    fixture_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    import psycopg
    from psycopg import sql
    import uvicorn
    schema = "rlc2_http_" + uuid4().hex
    assert re.fullmatch(r"rlc2_http_[0-9a-f]{32}", schema)
    scoped = url + "?" + urlencode({"options": "-csearch_path=" + schema})
    prior_url = os.environ.get("DATABASE_URL")
    created = False
    server = thread = listener = None
    try:
        with psycopg.connect(url) as db:
            db.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        created = True
        child_env = dict(os.environ, DATABASE_URL=scoped)
        for action in ("upgrade", "upgrade", "check"):
            run = subprocess.run([sys.executable, "-m", "product_v1.migrate", action], cwd=root, env=child_env, capture_output=True, timeout=40)
            check(run.returncode == 0, "actual Server isolated migration " + action)
        pg_candidate_migration(scoped, ddl)
        session = data["preview_sessions"][0]
        device, vehicle = session["device"], session["vehicle"]
        token = "synthetic_http_" + uuid4().hex + uuid4().hex
        with psycopg.connect(scoped) as db:
            db.execute("INSERT INTO v1_vehicles(vehicle_id,display_name) VALUES(%s,'synthetic RLC2 HTTP')", (vehicle,))
            db.execute("INSERT INTO v1_devices(device_id,vehicle_id,token_sha256) VALUES(%s,%s,%s)", (device, vehicle, hashlib.sha256(token.encode()).hexdigest()))
        os.environ["DATABASE_URL"] = scoped
        sys.path.insert(0, str(root))
        from product_v1.api import app
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(32)
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="critical", access_log=False, lifespan="on"))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        deadline = time.monotonic() + 15
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.05)
        check(server.started, "actual FastAPI/Uvicorn owns loopback socket")
        profiles = [json.loads(p["body_json"]) for p in data["preview_profiles"]]
        records = [json.loads(r["body_json"]) for r in data["preview_records"]]
        runs = {r["run_id"] for r in data["preview_records"]}
        check(len(runs) == 1, "rotation retains one logical run")
        batch = {"protocol_version": 1, "batch_id": str(uuid4()), "device_id": device, "vehicle_id": vehicle, "run_id": runs.pop(), "records": records}
        status, body = request(port, "POST", "/api/agent/v1/telemetry/batches", batch, token)
        check(status == 422 and body.get("status") != "committed", "unknown profile HTTP422 leaves batch uncommitted")
        for profile in profiles:
            status, _ = request(port, "PUT", "/api/agent/v1/profile-revisions/" + profile["profile_revision_id"], profile, token)
            check(status == 201, "real HTTP profile definition registration")
        # Proxy forwards the entire request, consumes the committed response, then
        # closes the client connection without returning any HTTP ACK bytes.
        forwarded = threading.Event()
        class DropResponse(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                n = int(self.headers.get("Content-Length", "0"))
                if n > 512 * 1024:
                    self.send_error(413)
                    return
                payload = json.loads(self.rfile.read(n))
                status, result = request(port, "POST", "/api/agent/v1/telemetry/batches", payload, token)
                if status == 200 and result.get("status") == "committed":
                    forwarded.set()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
        proxy = ThreadingHTTPServer(("127.0.0.1", 0), DropResponse)
        proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        proxy_thread.start()
        try:
            lost = False
            try:
                request(proxy.server_address[1], "POST", "/drop", batch, token)
            except (http.client.RemoteDisconnected, ConnectionError):
                lost = True
            check(lost and forwarded.wait(5), "real committed HTTP response lost before client ACK")
        finally:
            proxy.shutdown()
            proxy.server_close()
            proxy_thread.join(5)
        status, ack = request(port, "POST", "/api/agent/v1/telemetry/batches", batch, token)
        check(status == 200 and ack["status"] == "committed" and ack["batch_id"] == batch["batch_id"] and ack["record_count"] == 4 and ack["duplicate_count"] == 4 and ack["inserted_count"] == 0, "identical batch after lost response returns four duplicates")
        changed = copy.deepcopy(batch)
        changed["batch_id"] = str(uuid4())
        sample = next(r for r in changed["records"] if r["kind"] == "observation")
        sample["values"][0]["value"] += 1
        check(request(port, "POST", "/api/agent/v1/telemetry/batches", changed, token)[0] == 409, "same record ID different content HTTP409")
        invalid = copy.deepcopy(batch)
        invalid["batch_id"] = str(uuid4())
        invalid["records"][0]["unexpected"] = "synthetic"
        check(request(port, "POST", "/api/agent/v1/telemetry/batches", invalid, token)[0] == 422, "malformed batch HTTP422")
        undefined = copy.deepcopy(batch)
        undefined["batch_id"] = str(uuid4())
        bad_reference = copy.deepcopy(next(r for r in undefined["records"] if r["kind"] == "observation"))
        bad_reference["record_id"] = str(uuid4())
        bad_reference["sequence"] = 11
        bad_reference["values"][0]["parameter_id"] = "TEST_UNDEFINED"
        undefined["records"] = [bad_reference]
        check(request(port, "POST", "/api/agent/v1/telemetry/batches", undefined, token)[0] == 422, "observation parameter outside definition HTTP422")
        # Real PostgreSQL transaction failure in the invocation-owned schema only.
        with psycopg.connect(scoped) as db:
            db.execute("CREATE FUNCTION synthetic_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic constraint' USING ERRCODE='23514'; END $$")
            db.execute("CREATE TRIGGER synthetic_reject BEFORE INSERT ON v1_records FOR EACH ROW EXECUTE FUNCTION synthetic_reject()")
        fresh = copy.deepcopy(batch)
        fresh["batch_id"] = str(uuid4())
        fresh["records"] = [copy.deepcopy(sample)]
        fresh["records"][0]["record_id"] = str(uuid4())
        fresh["records"][0]["sequence"] = 10
        try:
            status, error = request(port, "POST", "/api/agent/v1/telemetry/batches", fresh, token)
            check(status == 409 and error.get("status") != "committed", "actual PostgreSQL transaction failure produces no ACK")
        finally:
            with psycopg.connect(scoped) as db:
                db.execute("DROP TRIGGER synthetic_reject ON v1_records")
                db.execute("DROP FUNCTION synthetic_reject()")
        # Shared PG service is never stopped: make the test Server DB endpoint
        # genuinely unavailable on an owned bound-but-non-listening loopback port.
        with socket.socket() as unavailable:
            unavailable.bind(("127.0.0.1", 0))
            bad_port = unavailable.getsockname()[1]
            from urllib.parse import urlsplit, urlunsplit
            parts = urlsplit(scoped)
            auth = parts.netloc.rsplit("@", 1)[0]
            os.environ["DATABASE_URL"] = urlunsplit((parts.scheme, auth + "@127.0.0.1:" + str(bad_port), parts.path, parts.query + "&connect_timeout=2", ""))
            try:
                status, error = request(port, "POST", "/api/agent/v1/telemetry/batches", fresh, token)
                check(status == 503 and error.get("status") != "committed", "actual unavailable DB endpoint HTTP503 without ACK")
            finally:
                os.environ["DATABASE_URL"] = scoped
        with psycopg.connect(scoped) as db:
            rows = db.execute("SELECT record_id,run_id,sequence,payload FROM v1_records ORDER BY record_id").fetchall()
            check(len(rows) == 4 and len({str(r[1]) for r in rows}) == 1, "real PostgreSQL has four immutable records in one rotated run")
            expected = {r["record_id"]: r for r in records}
            check(all(r[3] == expected[str(r[0])] for r in rows), "exact HTTP-originated JSON persisted in PostgreSQL")
            check(sorted(r[2] for r in rows if r[2] is not None) == [0, 1, 2], "logical observation sequence spans all three physical sources")
            check(db.execute("SELECT count(*) FROM v1_profile_revisions").fetchone()[0] == 1 and db.execute("SELECT count(*) FROM v1_values").fetchone()[0] == 3, "persisted profile and observation references agree")
        check(hashlib.sha256(path.read_bytes()).hexdigest() == fixture_hash, "test HTTP ACK never changes Agent candidate bytes or ACK state")
        print(f"Real RLC2 Server HTTP E2E passed {checks} checks; test ACK only; no production access", flush=True)
    finally:
        if server is not None:
            server.should_exit = True
        if thread is not None:
            thread.join(10)
            if thread.is_alive():
                raise RuntimeError("owned HTTP test server did not stop")
        if listener is not None:
            listener.close()
        if prior_url is None:
            os.environ.pop("DATABASE_URL", None)
        else:
            os.environ["DATABASE_URL"] = prior_url
        if created:
            with psycopg.connect(url) as db:
                db.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Driver exceptions may contain connection details; never print them.
        print("RLC2 HTTP E2E FAILED: " + type(exc).__name__, file=sys.stderr)
        raise SystemExit(1)
