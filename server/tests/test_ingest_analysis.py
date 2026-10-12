import pytest

from rrserver import analysis
from rrserver.ingest import ingest
from tests.conftest import sample_body


def test_ingest_and_dedupe(db):
    body = sample_body(t0=1_700_000_000_000)
    r = ingest(db, body)
    assert (r.samples, r.measurements, r.events, r.duplicates) == (10, 20, 1, 0)
    r2 = ingest(db, body)
    assert (r2.samples, r2.measurements, r2.events, r2.duplicates) == (0, 0, 0, 10)
    assert db.one("SELECT COUNT(*) FROM samples")[0] == 10


def test_ingest_rejects_bad_lines(db):
    r = ingest(db, "X\tfoo\nS\tS1\t1\tnotanumber\t1\tP8=1\nS\tS1\t1\t1\t1000\tP8=1.5\n")
    assert r.samples == 1 and r.rejected_lines == 2


def test_status_and_stats(db):
    ingest(db, sample_body(n=10, step_ms=125))
    st = analysis.vehicle_status(db)
    assert st["available"] and st["segment"] == 1
    names = {x["name"]: x["value"] for x in st["latest"]}
    assert names == {"Engine Speed": 1700.0, "Throttle Opening Angle": 14.0}
    assert st["recentEvents"][0]["text"] == "logging_start"

    stats = analysis.segment_stats(db)
    es = next(p for p in stats["parameters"] if p["name"] == "Engine Speed")
    assert es["count"] == 10 and es["min"] == 800 and es["max"] == 1700 and es["mean"] == 1250
    assert abs(es["stddev"] - 287.2281) < 1e-3
    assert stats["rateHz"] == 8.0

    last = analysis.segment_stats(db, last_seconds=0.5)
    assert last["samples"] == 5


def test_segments_sessions_recent(db):
    t0 = 1_700_000_000_000
    ingest(db, sample_body(segment=1, start_seq=1, n=5, t0=t0))
    ingest(db, sample_body(segment=2, start_seq=6, n=8, t0=t0 + 5 * 125))
    segs = analysis.list_segments(db)["segments"]
    assert [s["segment"] for s in segs] == [1, 2] and segs[1]["samples"] == 8
    assert analysis.list_sessions(db)[0]["segments"] == 2
    rv = analysis.recent_values(db, "engine", seconds=0.9, max_points=4)
    assert rv["available"] and rv["name"] == "Engine Speed" and len(rv["points"]) <= 4 and rv["totalPoints"] == 8
    assert analysis.recent_values(db, "nothing")["available"] is False


def test_event_marker(db):
    ingest(db, sample_body())
    m = analysis.add_event_marker(db, "  3速 4000rpm で 息つき  ")
    assert m["text"] == "3速 4000rpm で 息つき" and m["segment"] == 1
    ev = analysis.list_events(db)
    assert ev[0]["source"] == "driver"
    with pytest.raises(ValueError):
        analysis.add_event_marker(db, "   ")


def test_empty_db(db):
    assert analysis.vehicle_status(db)["available"] is False
    assert analysis.segment_stats(db)["available"] is False
    assert analysis.list_events(db) == []


@pytest.mark.parametrize("sql", [
    "DELETE FROM samples",
    "SELECT 1; DELETE FROM samples",
    "PRAGMA table_info(samples)",
    "UPDATE samples SET seq=1",
    "WITH x AS (SELECT 1) INSERT INTO events VALUES('a',1,1,'b',1,'c')",
    "select * from samples where 1=1; drop table samples",
])
def test_query_guard_rejects(db, sql):
    with pytest.raises(ValueError):
        analysis.query_telemetry(db, sql)


def test_query_select_and_truncate(db):
    ingest(db, sample_body(n=10))
    r = analysis.query_telemetry(db, "SELECT seq, t_ms FROM samples ORDER BY seq", max_rows=3)
    assert r["columns"] == ["seq", "t_ms"] and r["rowCount"] == 3 and r["truncated"] is True
    r = analysis.query_telemetry(db, "SELECT name FROM parameters WHERE name = 'delete me'")
    assert r["rowCount"] == 0  # 文字列リテラル内の禁止語は許可


def test_query_timeout(db):
    sql = ("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < 100000000) SELECT COUNT(*) FROM c")
    db_kind = db.kind
    with pytest.raises(TimeoutError):
        cols, rows, t = db.readonly_select(sql, timeout_ms=200)
    assert db_kind in ("sqlite", "postgres")


def test_acquisition_health(db):
    t0 = 1_700_000_000_000
    ingest(db, sample_body(n=20, t0=t0, step_ms=125))
    h = analysis.acquisition_health(db, now_ms=t0 + 19 * 125 + 100)
    assert h["rateHz"] == 8.0 and h["params"] == 2 and h["ok"] is True
    # health / link_error イベント
    ingest(db, f"E\tS1\t1\t{t0 + 2000}\thealth rate_hz=7.95 samples=80 params=2 interval_p95_ms=126 link_errors=0 link_connects=1\n"
               f"E\tS1\t1\t{t0 + 2100}\tlink_error count=1 Error occurred / readMsg error: timeout expired waiting for 51 more bytes\n")
    h = analysis.acquisition_health(db, now_ms=t0 + 2500)
    assert h["lastHealth"]["rate_hz"] == 7.95 and h["lastHealth"]["params"] == 2
    assert h["ok"] is False and any("通信エラー" in w for w in h["warnings"])


def test_acquisition_health_slow(db):
    t0 = 1_700_000_000_000
    ingest(db, sample_body(n=6, t0=t0, step_ms=900))
    h = analysis.acquisition_health(db, now_ms=t0 + 5 * 900)
    assert h["rateHz"] < 2.0 and h["ok"] is False and "下限" in h["warnings"][0]
