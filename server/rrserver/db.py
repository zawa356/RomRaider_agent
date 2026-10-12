"""DBアクセス層。DATABASE_URL で PostgreSQL（本番想定）と SQLite（ローカル試験）を切り替える。

- postgresql://user:pass@host:5432/db  → psycopg 3
- sqlite:///path/to/telemetry.db       → 標準ライブラリ sqlite3

SQLは両方で動く書き方に揃え、プレースホルダだけ差し替える（? → %s）。
スキーマは X270 上の Java 版受信サーバー（Receiver.java）と同じ。
"""
from __future__ import annotations

import os
import re
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Iterator, Sequence

SCHEMA = [
    "CREATE TABLE IF NOT EXISTS sessions(session TEXT PRIMARY KEY, first_received_ms BIGINT, last_received_ms BIGINT)",
    "CREATE TABLE IF NOT EXISTS parameters(session TEXT, segment INTEGER, param_id TEXT, name TEXT, units TEXT,"
    " PRIMARY KEY(session, segment, param_id))",
    "CREATE TABLE IF NOT EXISTS samples(id {serial} PRIMARY KEY, session TEXT, segment INTEGER, seq BIGINT,"
    " t_ms BIGINT, received_ms BIGINT, UNIQUE(session, seq))",
    "CREATE TABLE IF NOT EXISTS measurements(sample_id BIGINT, param_id TEXT, value DOUBLE PRECISION,"
    " PRIMARY KEY(sample_id, param_id))",
    "CREATE TABLE IF NOT EXISTS events(session TEXT, segment INTEGER, t_ms BIGINT, text TEXT, received_ms BIGINT,"
    " source TEXT DEFAULT 'rrbridge', UNIQUE(session, t_ms, text))",
    "CREATE INDEX IF NOT EXISTS samples_t ON samples(session, t_ms)",
    "CREATE INDEX IF NOT EXISTS samples_seg ON samples(session, segment)",
]


class Database:
    def __init__(self, url: str | None = None):
        self.url = url or os.environ.get("DATABASE_URL", "sqlite:///telemetry.db")
        self.kind = "postgres" if self.url.startswith(("postgres://", "postgresql://")) else "sqlite"
        self._lock = threading.RLock()
        if self.kind == "sqlite":
            self.path = self.url[len("sqlite:///"):] if self.url.startswith("sqlite:///") else self.url
            self._conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
            self._conn.execute("PRAGMA journal_mode=WAL")
        else:
            import psycopg  # noqa: WPS433  (本番のみ必要)
            self._psycopg = psycopg
            self._conn = psycopg.connect(self.url, autocommit=True)
        self.init_schema()

    # ---- 基本操作 ----
    def _sql(self, sql: str) -> str:
        return sql.replace("?", "%s") if self.kind == "postgres" else sql

    def init_schema(self) -> None:
        serial = "BIGSERIAL" if self.kind == "postgres" else "INTEGER"
        with self.tx() as cur:
            for stmt in SCHEMA:
                cur.execute(stmt.format(serial=serial))
            if self.kind == "sqlite":
                cols = [r[1] for r in cur.execute("PRAGMA table_info(events)").fetchall()]
                if "source" not in cols:  # Java版受信サーバーが作ったDBとの互換
                    cur.execute("ALTER TABLE events ADD COLUMN source TEXT DEFAULT 'rrbridge'")

    @contextmanager
    def tx(self) -> Iterator[Any]:
        with self._lock:
            if self.kind == "sqlite":
                cur = self._conn.cursor()
                cur.execute("BEGIN")
                try:
                    yield _Cur(cur, self)
                    cur.execute("COMMIT")
                except BaseException:
                    cur.execute("ROLLBACK")
                    raise
            else:
                with self._conn.transaction():
                    with self._conn.cursor() as cur:
                        yield _Cur(cur, self)

    def query(self, sql: str, params: Sequence[Any] = ()) -> list[tuple]:
        with self.tx() as cur:
            cur.execute(sql, params)
            return cur.fetchall()

    def one(self, sql: str, params: Sequence[Any] = ()) -> tuple | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    # ---- 読み取り専用SQL（MCPの query_telemetry 用） ----
    _FORBIDDEN = re.compile(
        r"\b(insert|update|delete|drop|alter|create|replace|truncate|attach|detach|pragma|vacuum|grant|revoke|copy|"
        r"call|do|execute|prepare|listen|notify|set|reset|lock|reindex|analyze|cluster|comment|security|load)\b",
        re.IGNORECASE,
    )

    def readonly_select(self, sql: str, max_rows: int = 500, timeout_ms: int = 3000) -> tuple[list[str], list[tuple], bool]:
        """SELECT/WITH だけを実行し、(列名, 行, 切り詰めたか) を返す。

        多重防御: 文の検査（単一文、SELECT/WITH開始、禁止語）＋ 読み取り専用トランザクション/接続 ＋ タイムアウト ＋ 行数上限。
        """
        s = sql.strip().rstrip(";").strip()
        if ";" in s:
            raise ValueError("複数の文は実行できません (multiple statements are not allowed)")
        if not re.match(r"(?is)^(select|with)\b", s):
            raise ValueError("SELECT または WITH で始まる文だけ実行できます")
        if self._FORBIDDEN.search(re.sub(r"'(?:[^']|'')*'", "''", s)):
            raise ValueError("読み取り以外の操作を含む文は実行できません")
        if self.kind == "sqlite":
            conn = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True, check_same_thread=False)
            import time
            deadline = time.monotonic() + timeout_ms / 1000
            conn.set_progress_handler(lambda: 1 if time.monotonic() > deadline else 0, 10000)
            try:
                cur = conn.execute(s)
                cols = [d[0] for d in cur.description or []]
                rows = cur.fetchmany(max_rows + 1)
            except sqlite3.OperationalError as e:
                if "interrupted" in str(e):
                    raise TimeoutError(f"{timeout_ms}ms を超えたため中断しました") from e
                raise
            finally:
                conn.close()
        else:
            with self._lock:
                with self._conn.transaction():
                    with self._conn.cursor() as cur:
                        cur.execute("SET TRANSACTION READ ONLY")
                        cur.execute(f"SET LOCAL statement_timeout = {int(timeout_ms)}")
                        try:
                            cur.execute(s)
                        except self._psycopg.errors.QueryCanceled as e:
                            raise TimeoutError(f"{timeout_ms}ms を超えたため中断しました") from e
                        cols = [d.name for d in cur.description or []]
                        rows = cur.fetchmany(max_rows + 1)
        truncated = len(rows) > max_rows
        return cols, [tuple(r) for r in rows[:max_rows]], truncated


class _Cur:
    """プレースホルダの違いを吸収するカーソルの薄いラッパー"""

    def __init__(self, cur: Any, db: Database):
        self._cur, self._db = cur, db

    def execute(self, sql: str, params: Sequence[Any] = ()) -> "_Cur":
        self._cur.execute(self._db._sql(sql), tuple(params))
        return self

    def fetchall(self) -> list[tuple]:
        return [tuple(r) for r in self._cur.fetchall()]

    def fetchone(self) -> tuple | None:
        r = self._cur.fetchone()
        return tuple(r) if r is not None else None

    @property
    def rowcount(self) -> int:
        return self._cur.rowcount
