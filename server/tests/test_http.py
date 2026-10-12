"""uvicorn を実際に起動し、/health、/ingest、/mcp（JSON-RPC over Streamable HTTP）を試す"""
import json
import os
import socket
import threading
import time

import httpx
import pytest
import uvicorn

from tests.conftest import sample_body


@pytest.fixture(scope="module")
def server(tmp_path_factory):
    db_path = tmp_path_factory.mktemp("http") / "t.db"
    os.environ.update({"DATABASE_URL": f"sqlite:///{db_path}", "INGEST_TOKEN": "ing", "MCP_TOKEN": "mcp", "ALLOWED_HOSTS": ""})
    import rrserver.app as appmod
    appmod._db = None
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    cfg = uvicorn.Config(appmod.app, host="127.0.0.1", port=port, log_level="warning")
    srv = uvicorn.Server(cfg)
    th = threading.Thread(target=srv.run, daemon=True); th.start()
    for _ in range(100):
        try:
            httpx.get(f"http://127.0.0.1:{port}/health"); break
        except httpx.HTTPError:
            time.sleep(0.05)
    yield f"http://127.0.0.1:{port}"
    srv.should_exit = True; th.join(5)


MCP_HEADERS = {"Authorization": "Bearer mcp", "Accept": "application/json, text/event-stream", "Content-Type": "application/json"}


def rpc(base, method, params=None, id_=1, headers=MCP_HEADERS):
    r = httpx.post(f"{base}/mcp", headers=headers, json={"jsonrpc": "2.0", "id": id_, "method": method, "params": params or {}})
    return r


def test_health(server):
    r = httpx.get(f"{server}/health")
    assert r.status_code == 200 and r.json()["ok"]


def test_ingest_auth(server):
    assert httpx.post(f"{server}/ingest", content=sample_body()).status_code == 401
    r = httpx.post(f"{server}/ingest", content=sample_body(), headers={"Authorization": "Bearer ing"})
    assert r.status_code == 200 and r.json()["samples"] == 10


def test_mcp_auth(server):
    assert rpc(server, "tools/list", headers={**MCP_HEADERS, "Authorization": "Bearer wrong"}).status_code == 401


def test_mcp_tools(server):
    init = rpc(server, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "0"}})
    assert init.status_code == 200, init.text
    assert init.json()["result"]["serverInfo"]["name"] == "rrbridge-telemetry"
    tools = rpc(server, "tools/list", id_=2).json()["result"]["tools"]
    names = {t["name"] for t in tools}
    assert names == {"get_vehicle_status", "get_acquisition_health", "list_sessions", "list_segments", "get_segment_stats", "get_recent_values",
                     "list_events", "query_telemetry", "add_event_marker"}
    ro = {t["name"]: t.get("annotations", {}).get("readOnlyHint") for t in tools}
    assert ro["get_vehicle_status"] is True and ro["add_event_marker"] is False
    httpx.post(f"{server}/ingest", content=sample_body(session="S9"), headers={"Authorization": "Bearer ing"})
    res = rpc(server, "tools/call", {"name": "get_vehicle_status", "arguments": {}}, id_=3).json()["result"]
    payload = res.get("structuredContent") or json.loads(res["content"][0]["text"])
    payload = payload.get("result", payload)
    assert payload["available"] and payload["session"] == "S9"
    bad = rpc(server, "tools/call", {"name": "query_telemetry", "arguments": {"sql": "DELETE FROM samples"}}, id_=4).json()
    assert bad.get("result", {}).get("isError") or "error" in bad
