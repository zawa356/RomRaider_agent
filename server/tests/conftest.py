import os
import sys
import time

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from rrserver.db import Database  # noqa: E402


def _urls():
    urls = ["sqlite"]
    if os.environ.get("RR_TEST_PG_URL"):
        urls.append("postgres")
    return urls


@pytest.fixture(params=_urls())
def db(request, tmp_path):
    if request.param == "sqlite":
        d = Database(f"sqlite:///{tmp_path / 't.db'}")
    else:
        d = Database(os.environ["RR_TEST_PG_URL"])
        with d.tx() as cur:
            for t in ("measurements", "samples", "parameters", "events", "sessions"):
                cur.execute(f"DELETE FROM {t}")
    yield d


def sample_body(session="S1", segment=1, start_seq=1, n=10, t0=None, step_ms=125):
    t0 = t0 or int(time.time() * 1000) - n * step_ms
    lines = [
        f"M\t{session}\t{segment}\tP8\tEngine Speed\trpm",
        f"M\t{session}\t{segment}\tP13\tThrottle Opening Angle\t%",
        f"E\t{session}\t{segment}\t{t0}\tlogging_start",
    ]
    for i in range(n):
        lines.append(f"S\t{session}\t{segment}\t{start_seq + i}\t{t0 + i * step_ms}\tP13={5 + i},P8={800 + 100 * i}.0")
    return "\n".join(lines) + "\n"
