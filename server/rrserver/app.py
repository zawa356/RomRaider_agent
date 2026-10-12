"""ASGIアプリ: 受信API（/ingest）、死活（/health）、Remote MCP（/mcp, Streamable HTTP）。

環境変数:
  DATABASE_URL     postgresql://… または sqlite:///…（既定 sqlite:///telemetry.db）
  INGEST_TOKEN     /ingest の Bearer トークン（RRBridge の sink.properties の token と同じ値）。空なら認証なし（ローカル試験用）
  MCP_TOKEN        /mcp の Bearer トークン（Claude のカスタムコネクタに設定する値）。空なら認証なし（ローカル試験用）
  ALLOWED_HOSTS    DNSリバインディング対策で許可するHostヘッダー（カンマ区切り、例 rr.example.com）。空なら検査しない
起動: uvicorn rrserver.app:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import hmac
import json
import os
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from starlette.requests import Request
from starlette.responses import JSONResponse

from . import __version__, analysis
from .db import Database
from .ingest import ingest

INSTRUCTIONS = """スバル レガシィ BP5（EJ20X）を RomRaider Logger で計測したデータを照会するツール群です。
データは車載PCの RRBridge からリアルタイムに送られてきます。
- まず get_vehicle_status で最新の状態（受信中か、最新値、直近のイベント）を確認してください。
- 区間（segment）は取得項目の組み合わせが変わるたびに増えます。list_segments で一覧、get_segment_stats で統計を見られます。
- 値の解釈（ノック補正、空燃比、ブーストなど）は、サンプリング周期や項目の組み合わせに依存します。根拠が足りない場合はそう伝えてください。
- このツールから車両やECUを操作することはできません。add_event_marker は運転者のメモを記録するだけです。
- 運転中の利用者には短く要点を答えてください。"""

_db: Database | None = None


def db() -> Database:
    global _db
    if _db is None:
        _db = Database()
    return _db


def _check(token_env: str, request_headers: Any) -> bool:
    token = os.environ.get(token_env, "")
    if not token:
        return True
    got = request_headers.get("authorization", "")
    return hmac.compare_digest(got, f"Bearer {token}")


RO = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=False)

mcp = MCPServer(name="rrbridge-telemetry", version=__version__, instructions=INSTRUCTIONS)


@mcp.tool(title="車両・受信の状態", annotations=RO)
def get_vehicle_status() -> dict:
    """最新セッションの状態: データを受信中か、最新サンプルの全項目の値（名称・単位付き）、直近のイベント5件。"""
    return analysis.vehicle_status(db())


@mcp.tool(title="セッション一覧", annotations=RO)
def list_sessions(limit: int = 10) -> dict:
    """計測セッション（RomRaider起動単位）の一覧。新しい順。サンプル数・長さ・区間数。"""
    return {"sessions": analysis.list_sessions(db(), limit)}


@mcp.tool(title="区間一覧", annotations=RO)
def list_segments(session: str | None = None) -> dict:
    """セッション内の区間（取得項目の組み合わせごと）の一覧。サンプル数、長さ、サンプリング周波数、項目名。session 省略時は最新。"""
    return analysis.list_segments(db(), session)


@mcp.tool(title="区間の統計", annotations=RO)
def get_segment_stats(session: str | None = None, segment: int | None = None, last_seconds: float | None = None) -> dict:
    """区間の項目ごとの件数・最小・最大・平均・標準偏差。省略時は最新セッションの最新区間。last_seconds を指定すると直近N秒だけ。"""
    return analysis.segment_stats(db(), session, segment, last_seconds)


@mcp.tool(title="直近の推移", annotations=RO)
def get_recent_values(parameter: str, seconds: float = 30, max_points: int = 120) -> dict:
    """1項目の直近の時系列（間引き済み）。parameter はID（例 P8）か名称の一部（例 Engine Speed、A/F）。tSec は最新からの相対秒。"""
    return analysis.recent_values(db(), parameter, seconds, max_points)


@mcp.tool(title="イベント一覧", annotations=RO)
def list_events(session: str | None = None, limit: int = 20) -> dict:
    """ロギング開始停止、CSV記録、プロファイル切替、運転者メモなどのイベント。新しい順。agoSec は何秒前か。"""
    return {"events": analysis.list_events(db(), session, limit)}


@mcp.tool(title="読み取り専用SQL", annotations=RO)
def query_telemetry(sql: str, max_rows: int = 200) -> dict:
    """SELECT/WITH の1文だけを実行（読み取り専用、3秒で打ち切り、最大500行）。
    表: sessions(session, first_received_ms, last_received_ms) / parameters(session, segment, param_id, name, units) /
    samples(id, session, segment, seq, t_ms, received_ms) / measurements(sample_id, param_id, value) /
    events(session, segment, t_ms, text, received_ms, source)。値は measurements を samples と parameters に結合して得る。"""
    return analysis.query_telemetry(db(), sql, max_rows)


@mcp.tool(title="メモを記録",
          annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=False, open_world_hint=False))
def add_event_marker(text: str) -> dict:
    """運転者が気付いたこと（例: 3速4000rpmで息つき）を現在時刻のイベントとして記録する。車両の操作はしない。"""
    return analysis.add_event_marker(db(), text)


@mcp.custom_route("/health", methods=["GET"])
async def health(request: Request) -> JSONResponse:
    return JSONResponse({"ok": True, "service": "rrbridge-server", "version": __version__, "db": db().kind})


@mcp.custom_route("/ingest", methods=["POST"])
async def ingest_route(request: Request) -> JSONResponse:
    if not _check("INGEST_TOKEN", request.headers):
        return JSONResponse({"ok": False, "error": "unauthorized"}, status_code=401)
    body = (await request.body()).decode("utf-8", errors="replace")
    try:
        return JSONResponse(ingest(db(), body).as_dict())
    except Exception as e:  # noqa: BLE001
        return JSONResponse({"ok": False, "error": str(e)}, status_code=500)


def _security() -> TransportSecuritySettings:
    hosts = [h.strip() for h in os.environ.get("ALLOWED_HOSTS", "").split(",") if h.strip()]
    if not hosts:
        return TransportSecuritySettings(enable_dns_rebinding_protection=False)
    return TransportSecuritySettings(enable_dns_rebinding_protection=True, allowed_hosts=hosts,
                                     allowed_origins=[f"https://{h}" for h in hosts])


class McpAuth:
    """/mcp だけに Bearer トークンを要求するASGIミドルウェア（/ingest と /health は各ハンドラーで判断）"""

    def __init__(self, inner):
        self.inner = inner

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http" and scope["path"].startswith("/mcp"):
            headers = {k.decode().lower(): v.decode() for k, v in scope.get("headers", [])}
            if not _check("MCP_TOKEN", headers):
                body = json.dumps({"error": "unauthorized"}).encode()
                await send({"type": "http.response.start", "status": 401,
                            "headers": [(b"content-type", b"application/json"), (b"www-authenticate", b"Bearer")]})
                await send({"type": "http.response.body", "body": body})
                return
        await self.inner(scope, receive, send)


app = McpAuth(mcp.streamable_http_app(stateless_http=True, json_response=True, transport_security=_security()))
