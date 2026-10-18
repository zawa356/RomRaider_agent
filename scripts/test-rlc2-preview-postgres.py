"""Synthetic preview SQLite -> private PostgreSQL schema; no Server endpoint or ACK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
from urllib.parse import urlparse
from uuid import UUID, uuid4


def destination():
    if os.environ.get("ROMLOGGER_RLC2_PREVIEW_ENABLE") != "1":
        raise ValueError("explicit synthetic preview opt-in required")
    value = os.environ.get("DATABASE_URL", "")
    u = urlparse(value)
    if (u.scheme not in {"postgres", "postgresql"}
            or u.hostname not in {"127.0.0.1", "localhost"}
            or u.query or u.fragment or u.params
            or re.fullmatch(r"/romlogger_(?:test|[a-z0-9_]*_e2e)", u.path) is None):
        raise ValueError("loopback disposable test database without overrides required")
    return value


def read_fixture(path_file):
    path = Path(Path(path_file).read_text(encoding="utf-8").strip()).resolve(strict=True)
    if not path.name.endswith(".rlc2-preview.db"):
        raise ValueError("dedicated preview fixture required")
    if any(Path(str(path) + suffix).exists() for suffix in ("-wal", "-shm", "-journal")):
        raise ValueError("closed checkpointed fixture required")
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        db.row_factory = sqlite3.Row
        assert db.execute("PRAGMA application_id").fetchone()[0] == 0x524C5031
        fixture = {table: [dict(row) for row in db.execute("SELECT * FROM " + table)]
                   for table in ("preview_sessions", "preview_sources", "preview_receipts", "preview_profiles", "preview_records")}
    assert tuple(len(fixture[t]) for t in fixture) == (1, 2, 4, 1, 3)
    assert all(row["ack_eligible"] == 0 for row in fixture["preview_records"])
    assert fixture["preview_sessions"][0]["payload_session"].startswith("synthetic-")
    for receipt in fixture["preview_receipts"]:
        assert hashlib.sha256(receipt["body_tsv"].encode()).hexdigest() == receipt["body_sha256"]
        raw = f'RomLogger-rlc2-ledger-prototype|{receipt["source_uuid"]}|{receipt["seq"]}'.encode()
        assert str(UUID(bytes=hashlib.md5(raw).digest(), version=3)) == receipt["receipt_id"]
    for record in fixture["preview_records"]:
        body = json.loads(record["body_json"])
        assert body["record_id"] == record["record_id"]
        if body["kind"] == "observation":
            assert body["source_kind"] == "synthetic"
            assert all(item["parameter_id"].startswith("TEST_") for item in body["values"])
    return fixture


def validate_server_contract(fixture):
    root = os.environ.get("ROMLOGGER_SERVER_PATH")
    if not root:
        if os.environ.get("ROMLOGGER_REQUIRE_SERVER_CONTRACT") == "1":
            raise ValueError("required Server checkout missing")
        print("Server model contract: NOT RUN (no Server checkout configured)")
        return
    if not Path(root, "product_v1", "api.py").is_file():
        raise ValueError("invalid Server checkout")
    sys.path.insert(0, str(Path(root).resolve()))
    from product_v1.api import Batch, ProfileRevision
    session = fixture["preview_sessions"][0]
    for profile in fixture["preview_profiles"]:
        ProfileRevision.model_validate_json(profile["body_json"])
    runs = {r["run_id"] for r in fixture["preview_records"]}
    assert len(runs) == 1
    Batch.model_validate({"protocol_version": 1, "batch_id": str(uuid4()),
                          "device_id": session["device"], "vehicle_id": session["vehicle"],
                          "run_id": runs.pop(),
                          "records": [json.loads(r["body_json"]) for r in fixture["preview_records"]]})
    print("Actual Server ProfileRevision/Batch model contract: PASS (local validation only; no endpoint/ACK)")


def verify_postgres(url, fixture):
    import psycopg
    from psycopg import sql
    from psycopg.types.json import Jsonb
    schema = "rlc2_preview_" + uuid4().hex
    name = sql.Identifier(schema)
    created = False
    try:
        with psycopg.connect(url, connect_timeout=5) as db:
            db.execute(sql.SQL("CREATE SCHEMA {}").format(name))
            db.execute(sql.SQL("SET LOCAL search_path TO {}").format(name))
            db.execute("CREATE TABLE preview_sources(source_uuid uuid PRIMARY KEY, seq bigint NOT NULL, prefix_sha256 text NOT NULL)")
            db.execute("CREATE TABLE preview_receipts(source_uuid uuid REFERENCES preview_sources, seq bigint NOT NULL, receipt_id uuid UNIQUE NOT NULL, body_sha256 text NOT NULL, body_tsv text NOT NULL, PRIMARY KEY(source_uuid,seq))")
            db.execute("CREATE TABLE preview_profiles(profile_id uuid PRIMARY KEY, body jsonb NOT NULL)")
            db.execute("CREATE TABLE preview_records(record_id uuid PRIMARY KEY, run_id uuid NOT NULL, body jsonb NOT NULL, ack_eligible boolean NOT NULL DEFAULT false CHECK(ack_eligible=false))")
            db.execute("CREATE TABLE failure_probe(id integer PRIMARY KEY)")
        created = True
        # Separate committed connections exercise a real restart/replay boundary.
        for attempt in range(2):
            with psycopg.connect(url, connect_timeout=5) as db:
                db.execute(sql.SQL("SET LOCAL search_path TO {}").format(name))
                for r in fixture["preview_sources"]:
                    db.execute("INSERT INTO preview_sources VALUES(%s,%s,%s) ON CONFLICT DO NOTHING", (r["source_uuid"], r["seq"], r["prefix_sha256"]))
                    assert db.execute("SELECT seq,prefix_sha256 FROM preview_sources WHERE source_uuid=%s", (r["source_uuid"],)).fetchone() == (r["seq"], r["prefix_sha256"])
                for r in fixture["preview_receipts"]:
                    db.execute("INSERT INTO preview_receipts VALUES(%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING", (r["source_uuid"], r["seq"], r["receipt_id"], r["body_sha256"], r["body_tsv"]))
                    assert db.execute("SELECT body_sha256,body_tsv FROM preview_receipts WHERE source_uuid=%s AND seq=%s", (r["source_uuid"], r["seq"])).fetchone() == (r["body_sha256"], r["body_tsv"])
                for r in fixture["preview_profiles"]:
                    db.execute("INSERT INTO preview_profiles VALUES(%s,%s) ON CONFLICT DO NOTHING", (r["profile_id"], Jsonb(json.loads(r["body_json"]))))
                    assert db.execute("SELECT body FROM preview_profiles WHERE profile_id=%s", (r["profile_id"],)).fetchone()[0] == json.loads(r["body_json"])
                for r in fixture["preview_records"]:
                    body = json.loads(r["body_json"])
                    db.execute("INSERT INTO preview_records VALUES(%s,%s,%s,false) ON CONFLICT DO NOTHING", (r["record_id"], r["run_id"], Jsonb(body)))
                    assert db.execute("SELECT body,ack_eligible FROM preview_records WHERE record_id=%s", (r["record_id"],)).fetchone() == (body, False)
                assert db.execute("SELECT count(*),count(DISTINCT record_id),bool_or(ack_eligible) FROM preview_records").fetchone() == (3, 3, False)
                assert db.execute("SELECT count(*) FROM preview_profiles").fetchone()[0] == 1
                assert db.execute("SELECT count(*) FROM preview_receipts").fetchone()[0] == 4
                assert db.execute("SELECT count(*) FROM preview_sources s WHERE s.seq<>(SELECT count(*) FROM preview_receipts r WHERE r.source_uuid=s.source_uuid)").fetchone()[0] == 0
                try:
                    with db.transaction():
                        db.execute("UPDATE preview_records SET ack_eligible=true")
                except psycopg.errors.CheckViolation:
                    pass
                else:
                    raise AssertionError("ACK constraint failed")
                try:
                    with db.transaction():
                        db.execute("INSERT INTO failure_probe VALUES(1)")
                        raise RuntimeError("synthetic DB failure")
                except RuntimeError:
                    pass
                assert db.execute("SELECT count(*) FROM failure_probe").fetchone()[0] == 0
            print(f"PostgreSQL preview pass {attempt + 1}: 2 sources / 4 receipts / 1 profile / 3 records; ACK forbidden; rollback verified")
    finally:
        if created:
            # Only the random schema successfully created by this invocation is removed.
            with psycopg.connect(url, connect_timeout=5) as db:
                db.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(name))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sqlite-path-file", default="build/rlc2-preview-fixture.path")
    args = parser.parse_args()
    url = destination()  # Before opening any database.
    fixture = read_fixture(args.sqlite_path_file)
    validate_server_contract(fixture)
    verify_postgres(url, fixture)
    print("Isolated RLC2 PostgreSQL preview: PASS; no Server ACK or production table access")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        # Never echo a DSN, original body, authentication value or database exception.
        raise SystemExit("RLC2 preview integration failed; details withheld from console") from None
