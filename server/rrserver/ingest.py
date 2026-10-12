"""RRBridge の転送形式（TSV）を取り込む。Java版 Receiver.ingest() と同じ規則。

  M session segment param_id name units
  S session segment seq epoch_ms id=value,id=value,...
  E session segment epoch_ms text

(session, seq) で重複排除するので、同じ本文を何度受けても結果は変わらない。
"""
from __future__ import annotations

import time
from dataclasses import dataclass

from .db import Database


@dataclass
class IngestResult:
    samples: int = 0
    measurements: int = 0
    events: int = 0
    duplicates: int = 0
    rejected_lines: int = 0
    max_lag_ms: int = 0

    def as_dict(self) -> dict:
        return {"ok": True, "samples": self.samples, "measurements": self.measurements, "events": self.events,
                "duplicates": self.duplicates, "rejectedLines": self.rejected_lines, "maxLagMs": self.max_lag_ms}


def ingest(db: Database, body: str, now_ms: int | None = None) -> IngestResult:
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    res = IngestResult()
    sessions: set[str] = set()
    with db.tx() as cur:
        for line in body.split("\n"):
            if not line.strip():
                continue
            f = line.rstrip("\r").split("\t")
            try:
                kind = f[0]
                if kind == "M" and len(f) >= 6:
                    cur.execute("INSERT INTO parameters VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                                (f[1], int(f[2]), f[3], f[4], f[5]))
                elif kind == "S" and len(f) >= 6:
                    t = int(f[4])
                    cur.execute("INSERT INTO samples(session,segment,seq,t_ms,received_ms) VALUES(?,?,?,?,?) "
                                "ON CONFLICT DO NOTHING", (f[1], int(f[2]), int(f[3]), t, now))
                    if cur.rowcount == 0:
                        res.duplicates += 1
                        sessions.add(f[1])
                        continue
                    sid = cur.execute("SELECT id FROM samples WHERE session=? AND seq=?", (f[1], int(f[3]))).fetchone()[0]
                    for kv in f[5].split(","):
                        k, sep, v = kv.partition("=")
                        if not sep or not k:
                            continue
                        cur.execute("INSERT INTO measurements VALUES(?,?,?) ON CONFLICT DO NOTHING", (sid, k, float(v)))
                        res.measurements += 1
                    res.samples += 1
                    res.max_lag_ms = max(res.max_lag_ms, now - t)
                elif kind == "E" and len(f) >= 5:
                    cur.execute("INSERT INTO events(session,segment,t_ms,text,received_ms,source) VALUES(?,?,?,?,?,?) "
                                "ON CONFLICT DO NOTHING", (f[1], int(f[2]), int(f[3]), f[4], now, "rrbridge"))
                    res.events += max(0, cur.rowcount)
                else:
                    res.rejected_lines += 1
                    continue
                sessions.add(f[1])
            except (ValueError, IndexError):
                res.rejected_lines += 1
        for s in sessions:
            cur.execute("INSERT INTO sessions VALUES(?,?,?) ON CONFLICT(session) DO UPDATE SET last_received_ms=excluded.last_received_ms",
                        (s, now, now))
    return res
