# Copyright 2025 OPPO

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""WebSocket connection registry – pure infrastructure layer.

Manages connection lifecycle (register/remove/send), metadata slots,
and protocol-level pending futures (code/gui/computer_use RPC).

Does NOT parse message types, maintain business subscriptions,
or understand device identity. Upper layers write arbitrary metadata
via set_metadata/get_metadata.
"""

from __future__ import annotations

import asyncio
import itertools
import json
from typing import Any, Awaitable, Callable

from fastapi import WebSocket
from loguru import logger

OnDisconnect = Callable[[str, dict[str, Any]], Awaitable[None]]

_conn_counter = itertools.count(1)


def _new_conn_id() -> str:
    return f"conn_{next(_conn_counter)}"


class WSConnectionRegistry:
    """Connection lifecycle + transparent send + metadata slots."""

    def __init__(self) -> None:
        self._connections: dict[str, WebSocket] = {}
        self._metadata: dict[str, dict[str, Any]] = {}
        self._ws_to_conn: dict[int, str] = {}
        self._pending_code_futures: dict[str, asyncio.Future[str]] = {}
        self._pending_gui_futures: dict[str, asyncio.Future[str]] = {}
        self._pending_mobile_tool_futures: dict[str, asyncio.Future[str]] = {}
        self._disconnect_callbacks: list[OnDisconnect] = []
        self._lock = asyncio.Lock()

    # ── lifecycle ──────────────────────────────────────────────

    async def register(self, websocket: WebSocket) -> str:
        """Register a raw WebSocket, return a temporary conn_id."""
        conn_id = _new_conn_id()
        async with self._lock:
            self._connections[conn_id] = websocket
            self._metadata[conn_id] = {}
            self._ws_to_conn[id(websocket)] = conn_id
        return conn_id

    async def remove(self, conn_id: str) -> dict[str, Any]:
        """Remove connection and return its metadata snapshot."""
        async with self._lock:
            ws = self._connections.pop(conn_id, None)
            metadata = dict(self._metadata.pop(conn_id, {}))
            if ws is not None:
                self._ws_to_conn.pop(id(ws), None)
        for cb in self._disconnect_callbacks:
            try:
                await cb(conn_id, metadata)
            except Exception:
                logger.opt(exception=True).warning("[ws_registry] disconnect callback error")
        return metadata

    def on_disconnect(self, callback: OnDisconnect) -> None:
        """Register a callback invoked when any connection is removed."""
        self._disconnect_callbacks.append(callback)

    # ── send ───────────────────────────────────────────────────

    async def send(self, conn_id: str, payload: dict[str, Any]) -> bool:
        async with self._lock:
            ws = self._connections.get(conn_id)
        if ws is None:
            return False
        try:
            await ws.send_json(payload)
            return True
        except Exception:
            return False

    async def send_to_ws(self, websocket: WebSocket, payload: dict[str, Any]) -> bool:
        """Fallback for pre-registration sends (e.g. JSON parse errors)."""
        try:
            await websocket.send_json(payload)
            return True
        except Exception:
            return False

    # ── metadata slots ─────────────────────────────────────────

    async def set_metadata(self, conn_id: str, key: str, value: Any) -> None:
        async with self._lock:
            meta = self._metadata.get(conn_id)
            if meta is not None:
                meta[key] = value

    async def get_metadata(self, conn_id: str) -> dict[str, Any] | None:
        async with self._lock:
            meta = self._metadata.get(conn_id)
            return dict(meta) if meta is not None else None

    async def conn_id_for_ws(self, websocket: WebSocket) -> str | None:
        async with self._lock:
            return self._ws_to_conn.get(id(websocket))

    async def total_connections(self) -> int:
        async with self._lock:
            return len(self._connections)

    async def snapshot_connections(self) -> list[dict[str, Any]]:
        """Return connection + metadata snapshot for diagnostics/routing fallback."""
        async with self._lock:
            return [
                {
                    "conn_id": conn_id,
                    "metadata": dict(self._metadata.get(conn_id, {})),
                }
                for conn_id in self._connections.keys()
            ]

    # ── pending futures (protocol-level RPC) ───────────────────

    async def add_pending_code(self, request_id: str, future: asyncio.Future[str]) -> None:
        async with self._lock:
            self._pending_code_futures[request_id] = future

    async def pop_pending_code(self, request_id: str) -> asyncio.Future[str] | None:
        async with self._lock:
            return self._pending_code_futures.pop(request_id, None)

    @staticmethod
    def _format_code_execute_result(msg: dict[str, Any]) -> str:
        if bool(msg.get("success")):
            stdout = str(msg.get("stdout") or msg.get("content") or "")
            stderr = str(msg.get("stderr") or "")
            if stderr:
                return f"stdout:\n{stdout}\n\nstderr:\n{stderr}"
            return stdout
        error = str(msg.get("error") or "执行失败")
        return f"[执行失败: {error}]"

    async def resolve_code_execute_result(self, msg: dict[str, Any]) -> None:
        request_id = str(msg.get("request_id") or "")
        if not request_id:
            return
        fut = await self.pop_pending_code(request_id)
        if fut and not fut.done():
            fut.set_result(self._format_code_execute_result(msg))

    async def add_pending_gui(self, request_id: str, future: asyncio.Future[str]) -> None:
        async with self._lock:
            self._pending_gui_futures[request_id] = future

    async def pop_pending_gui(self, request_id: str) -> asyncio.Future[str] | None:
        async with self._lock:
            return self._pending_gui_futures.pop(request_id, None)

    async def resolve_gui_execute_result(self, msg: dict[str, Any]) -> None:
        logger.info(f"resolving gui result: {msg}")
        request_id = msg.get("request_id")
        if not request_id:
            return
        fut = await self.pop_pending_gui(request_id)
        if fut and not fut.done():
            # GUI result comes from device executing the step
            if msg.get("success", True):
                # Check for "content" or "result" (since result might be dict)
                content = msg.get("content")
                if content is None or content == "":
                    content = msg.get("result", "")
                fut.set_result(str(content) if not isinstance(content, dict) else json.dumps(content, ensure_ascii=False))
            else:
                content = msg.get("error", "")
                fut.set_result(str(content))

    async def add_pending_mobile_tool(self, request_id: str, future: asyncio.Future[str]) -> None:
        async with self._lock:
            self._pending_mobile_tool_futures[request_id] = future

    async def pop_pending_mobile_tool(self, request_id: str) -> asyncio.Future[str] | None:
        async with self._lock:
            return self._pending_mobile_tool_futures.pop(request_id, None)

    @staticmethod
    def _format_mobile_tool_result(msg: dict[str, Any]) -> str:
        payload = msg.get("payload") if isinstance(msg.get("payload"), dict) else {}
        if bool(payload.get("ok", False)):
            data = payload.get("data")
            if isinstance(data, dict):
                return json.dumps(data, ensure_ascii=False)
            return str(data or "")
        error = payload.get("error")
        if isinstance(error, dict):
            code = str(error.get("code") or "INTERNAL_ERROR")
            message = str(error.get("message") or "mobile tool failed")
            return f"[{code}] {message}"
        return str(error or "mobile tool failed")

    async def resolve_mobile_tool_result(self, msg: dict[str, Any]) -> None:
        request_id = str(msg.get("request_id") or "")
        if not request_id:
            return
        fut = await self.pop_pending_mobile_tool(request_id)
        if fut and not fut.done():
            fut.set_result(self._format_mobile_tool_result(msg))
