# -*- coding: utf-8 -*-
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

"""TopoMobile relay websocket endpoint + adapter client helper.

This module is intentionally standalone so it can be introduced without
modifying existing app wiring.

Capabilities:
1. Accept TopoMobile adapter websocket connections (server side).
2. Send request messages to a connected adapter and await replies (client side
   from relay logic perspective).

Typical integration (in app bootstrap):
    from api.websocket_topomobile import register_topomobile_websocket
    register_topomobile_websocket(app)
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import uuid
from typing import Any

from fastapi import APIRouter, FastAPI, WebSocket, WebSocketDisconnect

from core.deps import connection_manager

logger = logging.getLogger(__name__)

router = APIRouter(tags=["topomobile-relay"])


class TopoMobileRelayHub:
    """Manage adapter connections and request/response correlation."""

    OWNER_LEASE_SECONDS = max(
        5.0,
        float(os.getenv("TOPOMOBILE_OWNER_LEASE_SECONDS", "20") or 20.0),
    )
    OWNER_HARD_STALE_SECONDS = max(
        OWNER_LEASE_SECONDS,
        float(os.getenv("TOPOMOBILE_OWNER_HARD_STALE_SECONDS", "120") or 120.0),
    )

    def __init__(self) -> None:
        self._connections: dict[str, WebSocket] = {}
        self._connection_meta: dict[str, dict[str, Any]] = {}
        self._pending: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._streams: dict[str, asyncio.Queue[dict[str, Any]]] = {}
        self._event_queues: dict[str, asyncio.Queue[dict[str, Any]]] = {}
        self._lock = asyncio.Lock()

    @staticmethod
    def _remote_addr(websocket: WebSocket) -> str:
        client = getattr(websocket, "client", None)
        host = getattr(client, "host", None)
        port = getattr(client, "port", None)
        if host is None or port is None:
            return "(unknown)"
        return f"{host}:{port}"

    async def connect(self, imei_id: str, websocket: WebSocket) -> bool:
        await websocket.accept()
        conn_id = f"tmws-{uuid.uuid4().hex[:10]}"
        remote = self._remote_addr(websocket)
        replaced = False
        async with self._lock:
            old = self._connections.get(imei_id)
            old_meta = dict(self._connection_meta.get(imei_id) or {})
            now = asyncio.get_running_loop().time()
            owner_instance = str(old_meta.get("instance_id") or "").strip()
            owner_last_seen = float(
                old_meta.get("last_seen_at")
                or old_meta.get("adapter_ready_at")
                or old_meta.get("connected_at")
                or 0.0
            )
            owner_age_s = (now - owner_last_seen) if owner_last_seen > 0 else float("inf")
            owner_lease_valid = bool(old and owner_instance and owner_age_s <= self.OWNER_LEASE_SECONDS)
            # Only protect active owner within lease window.
            # Once lease expires, allow newcomer to take over and replace the old connection.
            if owner_lease_valid:
                logger.warning(
                    "TopoMobile node connection denied by owner guard: imei_id=%s owner_conn_id=%s owner_remote=%s owner_instance_id=%s owner_age_s=%.1f lease_ttl_s=%.1f newcomer_conn_id=%s newcomer_remote=%s",
                    imei_id,
                    old_meta.get("conn_id", "(unknown)"),
                    old_meta.get("remote", "(unknown)"),
                    owner_instance,
                    owner_age_s,
                    self.OWNER_LEASE_SECONDS,
                    conn_id,
                    remote,
                )
                try:
                    await websocket.close(code=1008, reason="owner_locked")
                except Exception:
                    pass
                return False
            if old and owner_instance:
                logger.warning(
                    "TopoMobile owner lease expired, replacing connection: imei_id=%s owner_conn_id=%s owner_remote=%s owner_instance_id=%s owner_age_s=%.1f lease_ttl_s=%.1f newcomer_conn_id=%s newcomer_remote=%s",
                    imei_id,
                    old_meta.get("conn_id", "(unknown)"),
                    old_meta.get("remote", "(unknown)"),
                    owner_instance,
                    owner_age_s,
                    self.OWNER_LEASE_SECONDS,
                    conn_id,
                    remote,
                )
            self._connections[imei_id] = websocket
            self._connection_meta[imei_id] = {
                "conn_id": conn_id,
                "remote": remote,
                "connected_at": now,
                "last_seen_at": now,
            }
            self._event_queues.setdefault(imei_id, asyncio.Queue())
            replaced = bool(old and old is not websocket)
        if old and old is not websocket:
            logger.warning(
                "TopoMobile node connection replaced: imei_id=%s old_conn_id=%s old_remote=%s new_conn_id=%s new_remote=%s",
                imei_id,
                old_meta.get("conn_id", "(unknown)"),
                old_meta.get("remote", "(unknown)"),
                conn_id,
                remote,
            )
            try:
                await old.close(code=1012, reason="replaced_by_new_connection")
            except Exception:
                pass
        connection_manager.register_adapter_connection(imei_id, websocket)
        logger.info(
            "TopoMobile node connected: imei_id=%s conn_id=%s remote=%s replaced=%s",
            imei_id,
            conn_id,
            remote,
            replaced,
        )
        return True

    async def disconnect(
        self,
        imei_id: str,
        websocket: WebSocket | None = None,
        *,
        close_code: int | None = None,
        close_reason: str | None = None,
        trigger: str = "unknown",
    ) -> None:
        removed = False
        removed_meta: dict[str, Any] = {}
        async with self._lock:
            current = self._connections.get(imei_id)
            if websocket is None or current is websocket:
                self._connections.pop(imei_id, None)
                removed_meta = self._connection_meta.pop(imei_id, {})
                removed = True
        connection_manager.unregister_adapter_connection(imei_id, websocket)
        if removed:
            logger.info(
                "TopoMobile node disconnected: imei_id=%s conn_id=%s remote=%s close_code=%s close_reason=%s trigger=%s",
                imei_id,
                removed_meta.get("conn_id", "(unknown)"),
                removed_meta.get("remote", "(unknown)"),
                close_code,
                (close_reason or "").strip(),
                trigger,
            )
            return
        logger.debug(
            "TopoMobile node disconnect ignored (stale connection): imei_id=%s close_code=%s close_reason=%s trigger=%s",
            imei_id,
            close_code,
            (close_reason or "").strip(),
            trigger,
        )

    async def mark_adapter_ready(self, imei_id: str, websocket: WebSocket, message: dict[str, Any]) -> None:
        instance_id = str(message.get("instance_id") or "").strip()
        pid = str(message.get("pid") or "").strip()
        adapter = str(message.get("adapter") or "").strip()
        node_id = str(message.get("node_id") or "").strip()
        capabilities = message.get("capabilities")
        async with self._lock:
            current = self._connections.get(imei_id)
            if current is not websocket:
                return
            meta = self._connection_meta.setdefault(imei_id, {})
            if instance_id:
                meta["instance_id"] = instance_id
            if pid:
                meta["pid"] = pid
            if adapter:
                meta["adapter"] = adapter
            if node_id:
                meta["node_id"] = node_id
            if isinstance(capabilities, list):
                meta["capabilities"] = capabilities
            meta["adapter_ready_at"] = asyncio.get_running_loop().time()
            meta["last_seen_at"] = meta["adapter_ready_at"]
            conn_id = str(meta.get("conn_id") or "(unknown)")
            remote = str(meta.get("remote") or "(unknown)")
        logger.info(
            "TopoMobile adapter_ready: imei_id=%s conn_id=%s remote=%s adapter=%s node_id=%s instance_id=%s pid=%s",
            imei_id,
            conn_id,
            remote,
            adapter or "(unknown)",
            node_id or "(unknown)",
            instance_id or "(unknown)",
            pid or "(unknown)",
        )

    async def touch_connection(self, imei_id: str, websocket: WebSocket | None = None) -> None:
        now = asyncio.get_running_loop().time()
        async with self._lock:
            current = self._connections.get(imei_id)
            if current is None:
                return
            if websocket is not None and current is not websocket:
                return
            meta = self._connection_meta.setdefault(imei_id, {})
            meta["last_seen_at"] = now

    async def _resolve_target_imei_id(self, imei_id: str) -> str:
        """解析要连接的 TopoMobile 节点 id。

        仅当调用方未指定节点（空字符串）时才回退到 000。若显式请求 001 等但该节点未上线，
        不得静默改连 000，否则多实例下会把 B 的流量错误打到 A 的适配器。
        """
        normalized = (imei_id or "").strip()
        async with self._lock:
            if normalized in self._connections:
                return normalized
            if not normalized and "000" in self._connections:
                return "000"
        return normalized

    async def send_request(
        self,
        imei_id: str,
        payload: dict[str, Any],
        *,
        timeout: float = 30.0,
    ) -> dict[str, Any]:
        """Send request to adapter node and wait for matching reply."""
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")

        target_imei_id = await self._resolve_target_imei_id(imei_id)
        async with self._lock:
            ws = self._connections.get(target_imei_id)
        if not ws:
            raise RuntimeError(
                f"topomobile adapter offline: node_id={target_imei_id!r} "
                f"(请确认本地 topoclaw 已连接 /ws/topomobile/{target_imei_id})"
            )

        request_id = str(payload.get("request_id") or "").strip() or f"tm-{uuid.uuid4().hex}"
        packet = dict(payload)
        packet["request_id"] = request_id

        loop = asyncio.get_running_loop()
        fut: asyncio.Future[dict[str, Any]] = loop.create_future()
        self._pending[request_id] = fut

        try:
            await ws.send_json(packet)
        except Exception as exc:
            self._pending.pop(request_id, None)
            raise RuntimeError(f"failed to send request: {exc}") from exc

        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        finally:
            self._pending.pop(request_id, None)

    async def send_stream_request(self, imei_id: str, payload: dict[str, Any]) -> str:
        """Send request to adapter and receive all frames via stream queue."""
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        request_id = str(payload.get("request_id") or "").strip() or f"tm-{uuid.uuid4().hex}"
        packet = dict(payload)
        packet["request_id"] = request_id

        target_imei_id = await self._resolve_target_imei_id(imei_id)
        async with self._lock:
            ws = self._connections.get(target_imei_id)
            if not ws:
                raise RuntimeError(
                    f"topomobile adapter offline: node_id={target_imei_id!r} "
                    f"(请确认本地 topoclaw 已连接 /ws/topomobile/{target_imei_id})"
                )
            self._streams[request_id] = asyncio.Queue()
        try:
            await ws.send_json(packet)
        except Exception as exc:
            async with self._lock:
                self._streams.pop(request_id, None)
            raise RuntimeError(f"failed to send stream request: {exc}") from exc
        return request_id

    async def recv_stream_event(self, request_id: str, *, timeout: float | None = None) -> dict[str, Any]:
        """Receive one event for the given stream request_id."""
        async with self._lock:
            queue = self._streams.get(request_id)
        if not queue:
            raise RuntimeError(f"stream not found: {request_id}")
        if timeout is None:
            return await queue.get()
        return await asyncio.wait_for(queue.get(), timeout=timeout)

    async def close_stream_request(self, request_id: str) -> None:
        async with self._lock:
            self._streams.pop(request_id, None)

    async def push_incoming(self, imei_id: str, message: dict[str, Any]) -> None:
        """Route adapter inbound message either to pending future or event queue."""
        request_id = str(message.get("request_id") or "").strip()
        if request_id:
            async with self._lock:
                stream_q = self._streams.get(request_id)
            if stream_q is not None:
                await stream_q.put(message)
                return
            fut = self._pending.get(request_id)
            if fut and not fut.done():
                fut.set_result(message)
                return

        # Proactive frames (cron / push): forward to the user's mobile TopoClaw bridge WS by IMEI
        user_imei = str(message.get("imei") or "").strip()
        if user_imei:
            msg_type = str(message.get("type") or "").strip()
            request_id = str(message.get("request_id") or "").strip()
            if msg_type in {"mobile_tool_invoke", "mobile_tool_cancel"} and request_id:
                # 记录 request 级回程路由，避免 fallback 场景下结果回传到错误 TopoMobile 节点。
                route_imei_id = connection_manager.resolve_user_adapter_route(user_imei) or imei_id
                connection_manager.remember_mobile_tool_request_route(
                    request_id=request_id,
                    imei=user_imei,
                    route_imei_id=route_imei_id,
                )
            if msg_type == "gui_execute_request" and request_id:
                route_imei_id = connection_manager.resolve_user_adapter_route(user_imei) or imei_id
                connection_manager.remember_gui_request_route(
                    gui_request_id=request_id,
                    imei=user_imei,
                    route_imei_id=route_imei_id,
                    thread_id=str(message.get("thread_id") or "").strip(),
                )
            ws = connection_manager.mobile_agent_connections.get(user_imei)
            if ws is not None:
                try:
                    await ws.send_json(message)
                    return
                except Exception as exc:
                    logger.warning(
                        "TopoMobile relay: failed to push to mobile imei=%s: %s",
                        user_imei[:16],
                        exc,
                    )
                # If mobile-agent ws send fails, continue to fallback path below.
            else:
                logger.info(
                    "TopoMobile relay: mobile-agent ws not found imei=%s type=%s request_id=%s",
                    user_imei[:16],
                    str(message.get("type") or ""),
                    request_id or "(none)",
                )

            # Fallback delivery:
            # Some app versions only keep websocket_customer mobile channel online.
            # For selected proactive message types we can safely fallback to that channel.
            source = str(message.get("source") or "").strip().lower()
            # cron 执行结果常以 assistant_push/delta/done 形式回推；当 mobile_agent ws 不在线时也要镜像给 websocket_customer。
            cron_fallback_types = {"assistant_push", "delta", "done"}
            general_fallback_types = {"gui_execute_request", "mobile_tool_invoke", "mobile_tool_cancel"}
            should_fallback = (
                msg_type in general_fallback_types
                or (source == "cron" and msg_type in cron_fallback_types)
            )
            if should_fallback and connection_manager.is_user_online(user_imei):
                try:
                    fallback_message = dict(message)
                    if msg_type == "gui_execute_request":
                        # 标记为 relay fallback 帧，便于后续 step/result 固定回到同一路由。
                        fallback_message.setdefault("source", "relay_fallback")
                        fallback_message.setdefault("relay_imei_id", imei_id)
                    ok = await connection_manager.send_to_user(user_imei, fallback_message)
                    if ok:
                        logger.info(
                            "TopoMobile relay: fallback delivered via websocket_customer imei=%s type=%s source=%s request_id=%s",
                            user_imei[:16],
                            msg_type,
                            source or "(none)",
                            request_id or "(none)",
                        )
                        return
                    logger.warning(
                        "TopoMobile relay: fallback send_to_user failed imei=%s type=%s source=%s request_id=%s",
                        user_imei[:16],
                        msg_type,
                        source or "(none)",
                        request_id or "(none)",
                    )
                except Exception as exc:
                    logger.warning(
                        "TopoMobile relay: fallback send_to_user exception imei=%s type=%s source=%s request_id=%s err=%s",
                        user_imei[:16],
                        msg_type,
                        source or "(none)",
                        request_id or "(none)",
                        exc,
                    )

        async with self._lock:
            queue = self._event_queues.setdefault(imei_id, asyncio.Queue())
        await queue.put(message)

    async def next_event(self, imei_id: str, *, timeout: float | None = None) -> dict[str, Any]:
        """Consume unsolicited events for a connected node."""
        async with self._lock:
            queue = self._event_queues.setdefault(imei_id, asyncio.Queue())
        if timeout is None:
            return await queue.get()
        return await asyncio.wait_for(queue.get(), timeout=timeout)

    async def is_connected(self, imei_id: str) -> bool:
        async with self._lock:
            return imei_id in self._connections


relay_hub = TopoMobileRelayHub()


@router.websocket("/ws/topomobile/{imei_id}")
async def topomobile_ws_endpoint(websocket: WebSocket, imei_id: str):
    """Adapter connection endpoint.

    Adapter should connect here and then exchange JSON frames.
    """
    connected = await relay_hub.connect(imei_id, websocket)
    if not connected:
        # This connection was intentionally denied (e.g. owner lease protection).
        # Do not enter recv loop after close, otherwise Starlette raises runtime error.
        return
    close_code: int | None = None
    close_reason = ""
    trigger = "loop_exit"
    try:
        while True:
            try:
                raw = await websocket.receive_text()
            except WebSocketDisconnect as exc:
                close_code = getattr(exc, "code", None)
                close_reason = str(getattr(exc, "reason", "") or "")
                trigger = "websocket_disconnect"
                break

            try:
                message: Any = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "error": "invalid JSON"})
                continue
            if not isinstance(message, dict):
                await websocket.send_json({"type": "error", "error": "message must be object"})
                continue
            await relay_hub.touch_connection(imei_id, websocket)

            msg_type = str(message.get("type") or "")
            if msg_type == "ping":
                await websocket.send_json({"type": "pong", "request_id": message.get("request_id")})
                continue
            if msg_type == "adapter_ready":
                await relay_hub.mark_adapter_ready(imei_id, websocket, message)
                await websocket.send_json(
                    {
                        "type": "ready_ack",
                        "imei_id": imei_id,
                        "request_id": message.get("request_id"),
                    }
                )
                continue

            await relay_hub.push_incoming(imei_id, message)
    except Exception as exc:
        close_reason = str(exc)
        trigger = "handler_exception"
        logger.exception("TopoMobile websocket handler error: imei_id=%s err=%s", imei_id, exc)
        raise
    finally:
        await relay_hub.disconnect(
            imei_id,
            websocket,
            close_code=close_code,
            close_reason=close_reason,
            trigger=trigger,
        )


class TopoMobileAdapterClient:
    """Helper used by service logic to call connected adapters."""

    def __init__(self, hub: TopoMobileRelayHub | None = None) -> None:
        self.hub = hub or relay_hub

    async def call(self, imei_id: str, payload: dict[str, Any], *, timeout: float = 30.0) -> dict[str, Any]:
        return await self.hub.send_request(imei_id, payload, timeout=timeout)

    async def chat(
        self,
        imei_id: str,
        *,
        thread_id: str,
        message: str,
        agent_id: str | None = None,
        images: list[str] | None = None,
        timeout: float = 120.0,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "type": "chat",
            "thread_id": thread_id,
            "message": message,
            "images": images or [],
        }
        if agent_id:
            payload["agent_id"] = agent_id
        return await self.call(imei_id, payload, timeout=timeout)


def register_topomobile_websocket(app: FastAPI) -> None:
    """Register TopoMobile websocket router and expose hub/client via app.state."""
    app.include_router(router)
    app.state.topomobile_relay_hub = relay_hub
    app.state.topomobile_adapter_client = TopoMobileAdapterClient(relay_hub)
