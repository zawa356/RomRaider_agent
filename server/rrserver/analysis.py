"""MCPツールの中身（DBからの集計）。MCPに依存しない純粋な関数にして単体で試験できるようにする。

返り値は音声モードで読み上げやすいよう、項目名・単位付きの dict にする（IDだけを返さない）。
"""
from __future__ import annotations

import math
import time
from typing import Any

from .db import Database


def _now_ms() -> int:
    return int(time.time() * 1000)


def latest_session(db: Database) -> str | None:
    r = db.one("SELECT session FROM samples ORDER BY id DESC LIMIT 1")
    if r:
        return r[0]
    r = db.one("SELECT session FROM sessions ORDER BY last_received_ms DESC LIMIT 1")
    return r[0] if r else None


def _resolve(db: Database, session: str | None, segment: int | None) -> tuple[str | None, int | None]:
    session = session or latest_session(db)
    if session is None:
        return None, None
    if segment is None:
        r = db.one("SELECT segment FROM samples WHERE session=? ORDER BY id DESC LIMIT 1", (session,))
        segment = r[0] if r else None
    return session, segment


def _params(db: Database, session: str, segment: int) -> dict[str, tuple[str, str]]:
    rows = db.query("SELECT param_id, name, units FROM parameters WHERE session=? AND segment=?", (session, segment))
    return {pid: (name, units) for pid, name, units in rows}


def vehicle_status(db: Database, now_ms: int | None = None) -> dict[str, Any]:
    now = now_ms or _now_ms()
    session = latest_session(db)
    if session is None:
        return {"available": False, "message": "まだデータがありません"}
    last = db.one("SELECT id, segment, seq, t_ms, received_ms FROM samples WHERE session=? ORDER BY id DESC LIMIT 1", (session,))
    out: dict[str, Any] = {"available": True, "session": session}
    if last:
        sid, segment, seq, t_ms, recv = last
        names = _params(db, session, segment)
        vals = db.query("SELECT param_id, value FROM measurements WHERE sample_id=?", (sid,))
        out.update({
            "segment": segment, "lastSeq": seq,
            "lastSampleAgeSec": round((now - t_ms) / 1000, 1),
            "transferLagMs": recv - t_ms,
            "receiving": now - recv < 10_000,
            "latest": [{"name": names.get(p, (p, ""))[0], "units": names.get(p, (p, ""))[1], "value": v, "id": p} for p, v in sorted(vals)],
        })
    ev = db.query("SELECT t_ms, text, source FROM events WHERE session=? ORDER BY t_ms DESC LIMIT 5", (session,))
    out["recentEvents"] = [{"agoSec": round((now - t) / 1000), "text": txt, "source": src} for t, txt, src in ev]
    return out


def list_sessions(db: Database, limit: int = 10) -> list[dict[str, Any]]:
    rows = db.query(
        "SELECT s.session, COUNT(x.id), MIN(x.t_ms), MAX(x.t_ms), COUNT(DISTINCT x.segment) "
        "FROM sessions s LEFT JOIN samples x ON x.session=s.session GROUP BY s.session ORDER BY s.session DESC LIMIT ?",
        (max(1, min(100, limit)),))
    return [{"session": s, "samples": n, "durationSec": round(((b or 0) - (a or 0)) / 1000, 1), "segments": segs} for s, n, a, b, segs in rows]


def list_segments(db: Database, session: str | None = None) -> dict[str, Any]:
    session = session or latest_session(db)
    if session is None:
        return {"session": None, "segments": []}
    rows = db.query("SELECT segment, COUNT(*), MIN(t_ms), MAX(t_ms) FROM samples WHERE session=? GROUP BY segment ORDER BY segment", (session,))
    segs = []
    for seg, n, a, b in rows:
        names = [v[0] for v in _params(db, session, seg).values()]
        dur = (b - a) / 1000 if n > 1 else 0
        segs.append({"segment": seg, "samples": n, "durationSec": round(dur, 1),
                     "rateHz": round((n - 1) / dur, 2) if dur > 0 else None, "parameters": sorted(names)})
    return {"session": session, "segments": segs}


def segment_stats(db: Database, session: str | None = None, segment: int | None = None,
                  last_seconds: float | None = None, now_ms: int | None = None) -> dict[str, Any]:
    """区間（または直近N秒）の項目ごとの統計。標準偏差はPythonで計算（SQLiteとPostgreSQLの差を吸収）。"""
    session, segment = _resolve(db, session, segment)
    if session is None or segment is None:
        return {"available": False, "message": "対象のデータがありません"}
    cond, params = "x.session=? AND x.segment=?", [session, segment]
    if last_seconds:
        last_t = db.one("SELECT MAX(t_ms) FROM samples WHERE session=? AND segment=?", (session, segment))[0]
        cond += " AND x.t_ms >= ?"
        params.append(int(last_t - last_seconds * 1000))
    rows = db.query(
        f"SELECT m.param_id, COUNT(*), MIN(m.value), MAX(m.value), AVG(m.value), SUM(m.value*m.value) "
        f"FROM samples x JOIN measurements m ON m.sample_id=x.id WHERE {cond} GROUP BY m.param_id", params)
    span = db.one(f"SELECT COUNT(*), MIN(x.t_ms), MAX(x.t_ms) FROM samples x WHERE {cond}", params)
    names = _params(db, session, segment)
    stats = []
    for pid, n, mn, mx, avg, sumsq in sorted(rows):
        var = max(0.0, (sumsq / n) - avg * avg) if n else 0.0
        name, units = names.get(pid, (pid, ""))
        stats.append({"name": name, "units": units, "id": pid, "count": n, "min": mn, "max": mx,
                      "mean": round(avg, 4), "stddev": round(math.sqrt(var), 4)})
    n, a, b = span
    dur = (b - a) / 1000 if n and n > 1 else 0
    return {"available": True, "session": session, "segment": segment, "samples": n, "durationSec": round(dur, 1),
            "rateHz": round((n - 1) / dur, 2) if dur > 0 else None, "lastSeconds": last_seconds, "parameters": stats}


def recent_values(db: Database, parameter: str, seconds: float = 30, max_points: int = 120,
                  session: str | None = None) -> dict[str, Any]:
    """直近の時系列（間引き）。parameter はIDか名称の部分一致。"""
    session = session or latest_session(db)
    if session is None:
        return {"available": False, "message": "まだデータがありません"}
    cands = db.query("SELECT DISTINCT param_id, name, units FROM parameters WHERE session=?", (session,))
    p = parameter.lower()
    match = [c for c in cands if c[0].lower() == p] or [c for c in cands if p in (c[1] or "").lower()]
    if not match:
        return {"available": False, "message": f"項目が見つかりません: {parameter}", "candidates": sorted({c[1] for c in cands})}
    pid, name, units = match[0]
    last_t = db.one("SELECT MAX(t_ms) FROM samples WHERE session=?", (session,))[0]
    rows = db.query("SELECT x.t_ms, m.value FROM samples x JOIN measurements m ON m.sample_id=x.id "
                    "WHERE x.session=? AND m.param_id=? AND x.t_ms >= ? ORDER BY x.t_ms",
                    (session, pid, int(last_t - seconds * 1000)))
    step = max(1, math.ceil(len(rows) / max(1, max_points)))
    pts = [{"tSec": round((t - last_t) / 1000, 2), "value": v} for t, v in rows[::step]]
    return {"available": True, "name": name, "units": units, "id": pid, "points": pts, "totalPoints": len(rows), "step": step}


def list_events(db: Database, session: str | None = None, limit: int = 20, now_ms: int | None = None) -> list[dict[str, Any]]:
    now = now_ms or _now_ms()
    session = session or latest_session(db)
    if session is None:
        return []
    rows = db.query("SELECT t_ms, segment, text, source FROM events WHERE session=? ORDER BY t_ms DESC LIMIT ?",
                    (session, max(1, min(200, limit))))
    return [{"agoSec": round((now - t) / 1000), "segment": seg, "text": txt, "source": src} for t, seg, txt, src in rows]


def add_event_marker(db: Database, text: str, now_ms: int | None = None) -> dict[str, Any]:
    """運転者のメモを現在のセッション・区間に記録する（時刻はサーバー受信時刻）"""
    text = " ".join(text.split())[:500]
    if not text:
        raise ValueError("メモが空です")
    now = now_ms or _now_ms()
    session, segment = _resolve(db, None, None)
    if session is None:
        session, segment = "no-session", 0
    with db.tx() as cur:
        cur.execute("INSERT INTO events(session,segment,t_ms,text,received_ms,source) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    (session, segment or 0, now, text, now, "driver"))
    return {"ok": True, "session": session, "segment": segment, "text": text}


def query_telemetry(db: Database, sql: str, max_rows: int = 200) -> dict[str, Any]:
    cols, rows, truncated = db.readonly_select(sql, max_rows=max(1, min(500, max_rows)))
    return {"columns": cols, "rows": [list(r) for r in rows], "rowCount": len(rows), "truncated": truncated}
