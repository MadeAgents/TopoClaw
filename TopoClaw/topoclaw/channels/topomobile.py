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

"""TopoMobile channel: cloud relay WebSocket adapter (BaseChannel).

Inbound: JSON from customer_service relay (same protocol as before).
Outbound: progress and final replies go through MessageBus → ChannelManager → send(),
so cron and other producers can use channel=topomobile like other IM channels.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket
import uuid
from typing import TYPE_CHECKING, Any
from urllib.parse import urlparse, urlunparse
from types import SimpleNamespace

from loguru import logger

from topoclaw.agent.session_keys import (
    normalize_agent_id,
    websocket_session_key,
    websocket_session_keys_to_delete,
)
from topoclaw.bus.events import OutboundMessage
from topoclaw.channels.base import BaseChannel
from topoclaw.service.gui_mobile_service import _DEFAULT_MOBILE_GUI_SERVICE
from topoclaw.service.agent_manage_service import execute_create_agent, execute_delete_agent
from topoclaw.service.skills_service import SkillsService
from topoclaw.providers.service import ProviderService
from topoclaw.config.loader import get_config_path

if TYPE_CHECKING:
    from topoclaw.config.schema import Config, TopoMobileConfig
    from topoclaw.service.runtime import ServiceRuntime


class TopoMobileAgentAdapter:
    """Encapsulate agent-related operations for TopoMobile WS channel."""

    def __init__(self, *, runtime: Any, topoclaw_config: Any) -> None:
        self.runtime = runtime
        self.topoclaw_config = topoclaw_config

    @staticmethod
    def _request_id_from_msg(msg: dict[str, Any]) -> str | None:
        rid = msg.get("request_id")
        if rid is None:
            return None
        s = str(rid).strip()
        return s or None

    async def create_agent(self, msg: dict[str, Any]) -> dict[str, Any]:
        if not self.runtime.agent_registry:
            return {"type": "agent_created", "ok": False, "error": "agent registry unavailable"}
        return await execute_create_agent(
            msg=msg,
            config=self.topoclaw_config,
            registry=self.runtime.agent_registry,
            bus=self.runtime.bus,
            cron_service=self.runtime.cron,
            provider=self.runtime.agent.provider,
        )

    async def delete_agent(self, msg: dict[str, Any]) -> dict[str, Any]:
        if not self.runtime.agent_registry:
            return {"type": "agent_deleted", "ok": False, "error": "agent registry unavailable"}
        return await execute_delete_agent(
            msg=msg,
            config=self.topoclaw_config,
            registry=self.runtime.agent_registry,
        )

    async def run_chat(
        self,
        *,
        msg: dict[str, Any],
        channel_name: str = "topomobile",
    ) -> dict[str, Any]:
        thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
        raw_agent = msg.get("agent_id")
        agent_kw = str(raw_agent).strip() if raw_agent is not None else None
        rid = self._request_id_from_msg(msg)

        if not self.runtime.agent_registry:
            raise RuntimeError("agent registry unavailable")
        agent_loop, _, canonical_agent = await self.runtime.agent_registry.materialize(agent_kw)
        metadata: dict[str, Any] = {
            "thread_id": thread_id,
            "agent_id": canonical_agent,
            "source": "topomobile",
            "request_id": rid,
        }
        response_text = await agent_loop.process_direct(
            content=str(msg.get("message") or "").strip(),
            session_key=websocket_session_key(agent_kw, thread_id),
            channel=channel_name,
            chat_id=thread_id,
            media=[str(x) for x in (msg.get("images") or []) if str(x).strip()],
            metadata=metadata,
            on_progress=None,
        )
        # process_direct does not publish the final OutboundMessage; progress already used the bus.
        await self.runtime.bus.publish_outbound(
            OutboundMessage(
                channel=channel_name,
                chat_id=thread_id,
                content=response_text or "",
                metadata={**metadata, "final_done": True},
            )
        )
        return {
            "type": "done",
            "thread_id": thread_id,
            "agent_id": canonical_agent,
            "response": response_text or "",
            "need_execution": False,
            "chat_summary": None,
        }

    async def stop(
        self,
        *,
        msg: dict[str, Any],
        turn_tasks: dict[str, asyncio.Task[None]],
    ) -> dict[str, Any]:
        thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
        raw_agent = msg.get("agent_id")
        agent_kw = str(raw_agent).strip() if raw_agent is not None else None
        sk = websocket_session_key(agent_kw, thread_id)
        rid = self._request_id_from_msg(msg)

        turn_cancelled = False
        if task := turn_tasks.get(sk):
            if not task.done():
                task.cancel()
                turn_cancelled = True

        sub_cancelled = 0
        try:
            if self.runtime.agent_registry:
                loop, _, canonical_agent = await self.runtime.agent_registry.materialize(agent_kw)
            else:
                loop = self.runtime.agent
                canonical_agent = normalize_agent_id(agent_kw)
            sub_cancelled = await loop.subagents.cancel_by_session(sk)
        except Exception:
            sub_cancelled = 0

        payload: dict[str, Any] = {
            "type": "stopped",
            "thread_id": thread_id,
            "agent_id": canonical_agent,
            "reason": "用户主动停止",
            "chat_turn_cancelled": turn_cancelled,
            "subtasks_cancelled": sub_cancelled,
            "gui_tasks_stopped": {"mobile": 0},
            "request_id": rid,
        }
        return payload

    async def delete_session(
        self,
        *,
        msg: dict[str, Any],
        turn_tasks: dict[str, asyncio.Task[None]],
    ) -> dict[str, Any]:
        thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
        raw_agent = msg.get("agent_id")
        agent_kw = str(raw_agent).strip() if raw_agent is not None else None
        keys = websocket_session_keys_to_delete(agent_kw, thread_id)
        primary_key = keys[0]
        rid = self._request_id_from_msg(msg)

        turn_cancelled = False
        if task := turn_tasks.get(primary_key):
            if not task.done():
                task.cancel()
                turn_cancelled = True

        if self.runtime.agent_registry:
            loop, _, canonical_agent = await self.runtime.agent_registry.materialize(agent_kw)
        else:
            loop = self.runtime.agent
            canonical_agent = normalize_agent_id(agent_kw)

        sub_cancelled = 0
        for sk in keys:
            try:
                sub_cancelled += await loop.subagents.cancel_by_session(sk)
            except Exception:
                continue

        deletion = await loop.sessions.delete_sessions_for_keys(keys)
        payload: dict[str, Any] = {
            "type": "session_deleted",
            "thread_id": thread_id,
            "thread_id_resolved": thread_id,
            "agent_id": canonical_agent,
            "session_key": primary_key,
            "session_keys_tried": keys,
            "chat_turn_cancelled": turn_cancelled,
            "subtasks_cancelled": sub_cancelled,
            "session_file_existed": deletion.get("session_file_existed", False),
            "session_file_removed": deletion.get("session_file_removed", False),
            "delete_per_key": deletion.get("per_key", []),
            "gui_tasks_stopped": {"mobile": 0},
            "request_id": rid,
        }
        if deletion.get("error"):
            payload["error"] = deletion.get("error")
        return payload


class TopoMobileChannel(BaseChannel):
    """Bridge customer_service relay WebSocket ↔ MessageBus (IM-style channel)."""

    name = "topomobile"

    def __init__(
        self,
        config: TopoMobileConfig,
        bus: Any,
        *,
        runtime: ServiceRuntime,
        topoclaw_config: Config,
        skills_service: SkillsService,
    ) -> None:
        super().__init__(config, bus)
        self.runtime = runtime
        self.topoclaw_config = topoclaw_config
        self.skills_service = skills_service
        self.provider_service = ProviderService(runtime=runtime, config=topoclaw_config)
        self.agent_adapter = TopoMobileAgentAdapter(
            runtime=runtime,
            topoclaw_config=topoclaw_config,
        )

        self._main_task: asyncio.Task[None] | None = None
        self._send_lock = asyncio.Lock()
        self._turn_tasks: dict[str, asyncio.Task[None]] = {}
        self._node_id = (self.config.node_id or "").strip() or socket.gethostname() or "000"
        self._instance_id = f"{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self._adapter_ws: Any = None
        self._ws_ready = asyncio.Event()
        # thread_id (chat_id) -> user IMEI from last inbound relay frame; used when enriching outbound to customer_service
        self._thread_to_imei: dict[str, str] = {}

    def _remember_imei(self, thread_id: str, msg: dict[str, Any]) -> None:
        tid = (thread_id or "").strip()
        if not tid:
            return
        imei = str(msg.get("imei") or "").strip()
        if imei:
            self._thread_to_imei[tid] = imei

    def _imei_for_thread(self, thread_id: str) -> str:
        return (self._thread_to_imei.get((thread_id or "").strip()) or "").strip()

    def _merge_imei_into_payload(self, thread_id: str, msg: dict[str, Any] | None, payload: dict[str, Any]) -> None:
        """Attach imei for relay routing (customer_service → mobile) from inbound msg or thread map."""
        if msg:
            self._remember_imei(thread_id, msg)
        imei = self._imei_for_thread(thread_id)
        if imei:
            payload["imei"] = imei

    def _resolve_ws_url(self) -> str:
        raw = (self.config.ws_url or "").strip()
        if not raw:
            return f"ws://localhost:8000/ws/topomobile/{self._node_id}"
        parsed = urlparse(raw)
        path = (parsed.path or "").strip()
        if path in {"", "/"}:
            final_path = f"/ws/topomobile/{self._node_id}"
            return urlunparse(parsed._replace(path=final_path))

        normalized = path.rstrip("/")
        topomobile_prefix = "/ws/topomobile"
        if normalized.endswith("/ws"):
            final_path = f"{normalized}/topomobile/{self._node_id}"
            return urlunparse(parsed._replace(path=final_path))
        if normalized.endswith(topomobile_prefix):
            final_path = f"{normalized}/{self._node_id}"
            return urlunparse(parsed._replace(path=final_path))
        if f"{topomobile_prefix}/" in normalized:
            head = normalized.split(f"{topomobile_prefix}/", 1)[0]
            final_path = f"{head}{topomobile_prefix}/{self._node_id}"
            return urlunparse(parsed._replace(path=final_path))

        # 兼容传入 customer_service 基地址（如 /v4 或 /v4/），统一补齐到 /ws/topomobile/{node_id}
        final_path = f"{normalized}/ws/topomobile/{self._node_id}"
        return urlunparse(parsed._replace(path=final_path))

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._main_task = asyncio.create_task(self._run_loop())
        logger.info("TopoMobile channel started, target={}", self.config.ws_url)

    async def stop(self) -> None:
        self._running = False
        self._adapter_ws = None
        self._ws_ready.clear()
        if self._main_task:
            self._main_task.cancel()
            try:
                await self._main_task
            except asyncio.CancelledError:
                pass
            self._main_task = None
        await self._cancel_all_turns()
        logger.info("TopoMobile channel stopped")

    async def send(self, msg: OutboundMessage) -> None:
        """Send outbound agent/cron traffic to the relay as JSON (delta / done / error)."""
        if not self._adapter_ws:
            md = msg.metadata or {}
            is_gui = md.get("passthrough_type") == "gui_execute_request"
            wait_s = 15.0 if is_gui else 5.0
            logger.info(
                "TopoMobile: no relay WS, waiting up to {}s for reconnect (chat_id={}, gui={})",
                wait_s, msg.chat_id, is_gui,
            )
            try:
                await asyncio.wait_for(self._ws_ready.wait(), timeout=wait_s)
            except asyncio.TimeoutError:
                logger.warning(
                    "TopoMobile: relay WS unavailable after {}s; dropping outbound to {}",
                    wait_s, msg.chat_id,
                )
                return
            if not self._adapter_ws:
                logger.warning(
                    "TopoMobile: relay WS lost again after ready signal; dropping outbound to {}",
                    msg.chat_id,
                )
                return
        md = msg.metadata or {}
        request_id = md.get("request_id")
        thread_id = msg.chat_id
        agent_id = md.get("agent_id", normalize_agent_id(None))
        imei = (md.get("imei") or "").strip() or self._imei_for_thread(thread_id)

        def _maybe_imei(p: dict[str, Any]) -> None:
            if imei:
                p["imei"] = imei

        passthrough_type = str(md.get("passthrough_type") or "").strip()
        if passthrough_type == "gui_execute_request":
            payload = {
                "type": "gui_execute_request",
                "thread_id": str(md.get("thread_id") or thread_id or "").strip(),
                "request_id": md.get("request_id"),
                "query": msg.content,
            }
            if md.get("chat_summary"):
                payload["chat_summary"] = md.get("chat_summary")
            _maybe_imei(payload)
            if not payload.get("request_id"):
                payload.pop("request_id", None)
            await self._send_json(self._adapter_ws, payload)
            return

        if passthrough_type in {"mobile_tool_invoke", "mobile_tool_cancel"}:
            payload = {
                "type": passthrough_type,
                "thread_id": str(md.get("thread_id") or thread_id or "").strip(),
                "request_id": md.get("request_id"),
                "conversation_id": str(md.get("conversation_id") or thread_id or "").strip(),
                "protocol": str(md.get("protocol") or "mobile_tool/v1"),
            }
            if passthrough_type == "mobile_tool_invoke":
                payload["payload"] = {
                    "tool": str(md.get("tool") or "").strip(),
                    "args": md.get("tool_args") if isinstance(md.get("tool_args"), dict) else {},
                }
            _maybe_imei(payload)
            if not payload.get("request_id"):
                payload.pop("request_id", None)
            await self._send_json(self._adapter_ws, payload)
            return

        if md.get("_progress"):
            payload = {
                "type": "delta",
                "thread_id": thread_id,
                "agent_id": agent_id,
                "content": msg.content,
            }
            _maybe_imei(payload)
            if request_id:
                payload["request_id"] = request_id
            if md.get("_tool_hint"):
                payload["tool_hint"] = True
        elif md.get("terminal_type") == "stopped":
            payload = {
                "type": "stopped",
                "thread_id": thread_id,
                "agent_id": agent_id,
                "reason": md.get("reason") or "用户主动停止",
                "chat_turn_cancelled": md.get("chat_turn_cancelled", True),
            }
            _maybe_imei(payload)
            if request_id:
                payload["request_id"] = request_id
        elif md.get("terminal_type") == "error":
            payload = {
                "type": "error",
                "thread_id": thread_id,
                "agent_id": agent_id,
                "error": msg.content,
            }
            _maybe_imei(payload)
            if request_id:
                payload["request_id"] = request_id
        else:
            payload = {
                "type": "done",
                "thread_id": thread_id,
                "agent_id": agent_id,
                "response": msg.content,
                "need_execution": False,
                "chat_summary": None,
            }
            _maybe_imei(payload)
            if request_id:
                payload["request_id"] = request_id
            if md.get("source") == "cron":
                payload["source"] = "cron"

        await self._send_json(self._adapter_ws, payload)

    async def _run_loop(self) -> None:
        import websockets

        while self._running:
            _loop_start = asyncio.get_event_loop().time()
            try:
                target_ws_url = self._resolve_ws_url()
                kwargs: dict[str, Any] = {
                    "open_timeout": self.config.open_timeout_seconds,
                    "ping_interval": self.config.ping_interval_seconds,
                    "ping_timeout": self.config.ping_timeout_seconds,
                }
                if self.config.extra_headers:
                    kwargs["additional_headers"] = self.config.extra_headers
                async with websockets.connect(target_ws_url, **kwargs) as ws:
                    self._adapter_ws = ws
                    self._ws_ready.set()
                    await self._send_json(
                        ws,
                        {
                            "type": "adapter_ready",
                            "adapter": self.name,
                            "node_id": self._node_id,
                            "instance_id": self._instance_id,
                            "pid": os.getpid(),
                            "capabilities": [
                                "chat",
                                "stop",
                                "delete_session",
                                "create_agent",
                                "delete_agent",
                                "skills",
                                "cron",
                            ],
                        },
                    )
                    if self.config.auth_token.strip():
                        await self._send_json(
                            ws,
                            {
                                "type": "auth",
                                "token": self.config.auth_token.strip(),
                                "node_id": self._node_id,
                            },
                        )
                    logger.info("TopoMobile channel connected: node_id={} url={}", self._node_id, target_ws_url)
                    await self._read_loop(ws)
                    close_code = getattr(ws, "close_code", None)
                    close_reason = getattr(ws, "close_reason", None)
                    logger.info(
                        "TopoMobile: connection closed (code={}, reason={})",
                        close_code, close_reason,
                    )
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.warning("TopoMobile channel connection error: {}", exc)
            finally:
                self._adapter_ws = None
                self._ws_ready.clear()

            if self._running:
                elapsed = asyncio.get_event_loop().time() - _loop_start
                backoff = max(0.0, self.config.reconnect_delay_seconds - elapsed)
                if backoff > 0:
                    logger.debug("TopoMobile: backoff {:.1f}s before reconnect", backoff)
                    await asyncio.sleep(backoff)

    async def _read_loop(self, ws: Any) -> None:
        while self._running:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=self.config.recv_timeout_seconds)
            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                raise
            except Exception:
                return

            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await self._send_json(ws, {"type": "error", "error": "invalid JSON"})
                continue
            if not isinstance(msg, dict):
                await self._send_json(ws, {"type": "error", "error": "message must be an object"})
                continue
            await self._dispatch(ws, msg)

    @staticmethod
    def _thread_id(msg: dict[str, Any]) -> str:
        return str(msg.get("thread_id") or msg.get("session_id") or "").strip()

    @staticmethod
    def _request_id(msg: dict[str, Any]) -> str | None:
        rid = msg.get("request_id")
        if rid is None:
            return None
        s = str(rid).strip()
        return s or None

    @classmethod
    def _with_request_id(cls, msg: dict[str, Any], payload: dict[str, Any]) -> dict[str, Any]:
        rid = cls._request_id(msg)
        if rid:
            payload["request_id"] = rid
        return payload

    @staticmethod
    def _request_shim_for_mobile_upload(mobile_agent: Any, screenshot_b64: str | None = None) -> Any:
        screenshot = (screenshot_b64 or "").strip()
        form_payload: dict[str, Any] = {}
        if screenshot:
            form_payload["screenshot"] = screenshot

        class _RequestShim:
            def __init__(self, agent_obj: Any, form_data: dict[str, Any]) -> None:
                self.app = SimpleNamespace(state=SimpleNamespace(mobile_agent=agent_obj))
                self._form_data = form_data

            async def form(self) -> dict[str, Any]:
                return self._form_data

        return _RequestShim(mobile_agent, form_payload)

    @staticmethod
    def _extract_models_from_profiles(raw_profiles: Any) -> list[str]:
        models: list[str] = []
        if not isinstance(raw_profiles, list):
            return models
        for item in raw_profiles:
            if not isinstance(item, dict):
                continue
            model = str(item.get("model") or "").strip()
            if model and model not in models:
                models.append(model)
        return models

    def _get_builtin_model_profiles_payload(self) -> dict[str, Any]:
        chat_models: list[str] = []
        gui_models: list[str] = []
        active_chat = ""
        active_gui = ""
        try:
            cfg_path = get_config_path()
            raw = json.loads(cfg_path.read_text(encoding="utf-8")) if cfg_path.is_file() else {}
            topo_desktop = raw.get("topo_desktop") if isinstance(raw, dict) else None
            if isinstance(topo_desktop, dict):
                chat_models = self._extract_models_from_profiles(
                    topo_desktop.get("nonGuiProfiles") or topo_desktop.get("non_gui_profiles")
                )
                gui_models = self._extract_models_from_profiles(
                    topo_desktop.get("guiProfiles") or topo_desktop.get("gui_profiles")
                )
                active_chat = str(topo_desktop.get("activeNonGuiModel") or "").strip()
                active_gui = str(topo_desktop.get("activeGuiModel") or "").strip()
        except Exception:
            chat_models = []
            gui_models = []

        default_chat = str(getattr(self.topoclaw_config.agents.defaults, "model", "") or "").strip()
        default_gui = ""
        if getattr(self.topoclaw_config.agents, "gui", None) is not None:
            default_gui = str(getattr(self.topoclaw_config.agents.gui, "model", "") or "").strip()

        if not chat_models and default_chat:
            chat_models = [default_chat]
        if not gui_models:
            gui_models = [default_gui] if default_gui else ([chat_models[0]] if chat_models else [])

        # 以运行时真实生效模型为准（优先于 config/topo_desktop）
        runtime_chat = str(getattr(self.runtime.agent, "model", "") or "").strip()
        if runtime_chat:
            active_chat = runtime_chat
        runtime_gui = str(
            getattr(getattr(_DEFAULT_MOBILE_GUI_SERVICE, "mobile_agent", None), "model", "") or ""
        ).strip()
        if runtime_gui:
            active_gui = runtime_gui

        if not active_chat:
            active_chat = chat_models[0] if chat_models else ""
        if not active_gui:
            active_gui = gui_models[0] if gui_models else ""

        return {
            "type": "builtin_model_profiles_result",
            "ok": True,
            "non_gui_profiles": chat_models,
            "gui_profiles": gui_models,
            "active_non_gui_model": active_chat,
            "active_gui_model": active_gui,
        }

    async def _dispatch(self, ws: Any, msg: dict[str, Any]) -> None:
        msg_type = str(msg.get("type") or "").strip()

        if msg_type == "ping":
            await self._send_json(ws, self._with_request_id(msg, {"type": "pong"}))
            return

        if msg_type in {"skills_list", "skill_get", "skills_update", "skill_download", "skill_remove"}:
            payload = await self.skills_service.handle_ws_message(msg)
            if payload is None:
                await self._send_json(
                    ws,
                    self._with_request_id(msg, {"type": "error", "error": "unsupported skills request"}),
                )
                return
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type in {"cron_list_jobs", "cron_create_job", "cron_delete_job"}:
            payload = await self.runtime.cron.handle_ws_message(msg)
            if payload is None:
                await self._send_json(
                    ws,
                    self._with_request_id(msg, {"type": "error", "error": "unsupported cron request"}),
                )
                return
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type == "create_agent":
            payload = await self.agent_adapter.create_agent(msg)
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type == "delete_agent":
            payload = await self.agent_adapter.delete_agent(msg)
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type == "chat":
            await self._handle_chat(ws, msg)
            return

        if msg_type == "stop":
            await self._handle_stop(ws, msg)
            return

        if msg_type == "delete_session":
            await self._handle_delete_session(ws, msg)
            return

        if msg_type == "gui_execute_result":
            request_id = str(msg.get("request_id") or "").strip()
            if _DEFAULT_MOBILE_GUI_SERVICE is not None and request_id:
                await _DEFAULT_MOBILE_GUI_SERVICE.handle_ws_message(msg)
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {
                            "type": "gui_execute_result_ack",
                            "ok": True,
                            "request_id": request_id,
                        },
                    ),
                )
            else:
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {
                            "type": "error",
                            "error": "gui_execute_result ignored: missing request_id or service unavailable",
                        },
                    ),
                )
            return

        if msg_type in {"mobile_tool_result", "mobile_tool_event", "mobile_tool_ack"}:
            request_id = str(msg.get("request_id") or "").strip()
            if msg_type == "mobile_tool_result" and request_id:
                if _DEFAULT_MOBILE_GUI_SERVICE is not None:
                    await _DEFAULT_MOBILE_GUI_SERVICE.connection_registry.resolve_mobile_tool_result(msg)
                else:
                    logger.warning(
                        "mobile_tool_result ignored: mobile gui service unavailable request_id={}",
                        request_id,
                    )
            await self._send_json(
                ws,
                self._with_request_id(
                    msg,
                    {
                        "type": f"{msg_type}_ack",
                        "ok": True,
                        "request_id": request_id,
                    },
                ),
            )
            return

        if msg_type == "gui_step_request":
            if _DEFAULT_MOBILE_GUI_SERVICE is None:
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {"type": "gui_step_response", "success": False, "error": "mobile gui service unavailable"},
                    ),
                )
                return
            mobile_agent = getattr(_DEFAULT_MOBILE_GUI_SERVICE, "mobile_agent", None)
            if mobile_agent is None:
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {"type": "gui_step_response", "success": False, "error": "mobile agent unavailable"},
                    ),
                )
                return

            gui_request_id = str(msg.get("gui_request_id") or msg.get("request_id") or "").strip()
            step_request_id = str(msg.get("step_request_id") or msg.get("request_id") or "").strip()
            query = str(msg.get("query") or "").strip()
            screenshot = str(msg.get("screenshot") or msg.get("image") or "").strip()
            user_response = str(msg.get("user_response") or "").strip() or None
            package_name = str(msg.get("package_name") or "").strip() or None
            if not gui_request_id:
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {"type": "gui_step_response", "success": False, "error": "missing gui_request_id"},
                    ),
                )
                return
            if not query and not screenshot:
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {"type": "gui_step_response", "success": False, "error": "missing query and screenshot"},
                    ),
                )
                return

            try:
                request_obj = self._request_shim_for_mobile_upload(mobile_agent, screenshot_b64=screenshot)
                result = await _DEFAULT_MOBILE_GUI_SERVICE.handle_mobile_upload(
                    request=request_obj,
                    uuid_value=gui_request_id,
                    task_id=gui_request_id,
                    query=query,
                    feedback=None,
                    feedback_text=None,
                    user_response=user_response,
                    package_name=package_name,
                    images=None,
                )
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {
                            "type": "gui_step_response",
                            "success": True,
                            "step_request_id": step_request_id,
                            "gui_request_id": gui_request_id,
                            "chat_response": result,
                        },
                    ),
                )
            except Exception as exc:
                logger.warning(
                    "TopoMobile gui_step_request failed request_id={} gui_request_id={} err={}",
                    self._request_id(msg) or "(none)",
                    gui_request_id,
                    exc,
                )
                await self._send_json(
                    ws,
                    self._with_request_id(
                        msg,
                        {
                            "type": "gui_step_response",
                            "success": False,
                            "step_request_id": step_request_id,
                            "gui_request_id": gui_request_id,
                            "error": str(exc),
                        },
                    ),
                )
            return

        if msg_type == "get_builtin_model_profiles":
            payload = self._get_builtin_model_profiles_payload()
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type == "set_llm_provider":
            try:
                payload = await self.provider_service.handle_set_llm_provider(msg)
            except Exception as exc:
                payload = {
                    "type": "set_llm_provider_result",
                    "ok": False,
                    "applied": False,
                    "reason": str(exc),
                    "patch_keys": [],
                    "updated_agent_ids": [],
                    "errors": [{"agent_id": "*", "error": str(exc)}],
                }
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        if msg_type == "set_gui_provider":
            mobile_agent = getattr(_DEFAULT_MOBILE_GUI_SERVICE, "mobile_agent", None)
            try:
                payload = await self.provider_service.handle_set_gui_provider(
                    msg,
                    mobile_agent=mobile_agent,
                )
            except Exception as exc:
                payload = {
                    "type": "set_gui_provider_result",
                    "ok": False,
                    "applied": False,
                    "reason": str(exc),
                    "patch_keys": [],
                    "updated_targets": [],
                    "errors": [{"target": "*", "error": str(exc)}],
                }
            await self._send_json(ws, self._with_request_id(msg, payload))
            return

        await self._send_json(ws, self._with_request_id(msg, {"type": "error", "error": f"unknown type: {msg_type}"}))

    async def _handle_chat(self, ws: Any, msg: dict[str, Any]) -> None:
        thread_id = self._thread_id(msg)
        if not thread_id:
            await self._send_json(ws, self._with_request_id(msg, {"type": "error", "error": "missing thread_id"}))
            return
        content = str(msg.get("message") or "").strip()
        if not content:
            await self._send_json(ws, self._with_request_id(msg, {"type": "error", "error": "missing message"}))
            return

        self._remember_imei(thread_id, msg)

        raw_agent = msg.get("agent_id")
        agent_kw = str(raw_agent).strip() if raw_agent is not None else None
        canonical_agent = normalize_agent_id(agent_kw)
        session_key = websocket_session_key(agent_kw, thread_id)

        sender = str(msg.get("imei") or msg.get("sender_id") or thread_id)
        if not self.is_allowed(sender):
            logger.warning("TopoMobile: access denied for sender {}", sender)
            await self._send_json(
                ws,
                self._with_request_id(
                    msg,
                    {
                        "type": "error",
                        "error": "access denied for this sender (allowFrom)",
                    },
                ),
            )
            return

        if old := self._turn_tasks.get(session_key):
            if not old.done():
                old.cancel()

        async def _run_turn() -> None:
            try:
                await self.agent_adapter.run_chat(msg=msg, channel_name=self.name)
            except asyncio.CancelledError:
                await self.bus.publish_outbound(
                    OutboundMessage(
                        channel=self.name,
                        chat_id=thread_id,
                        content="",
                        metadata={
                            "terminal_type": "stopped",
                            "request_id": self._request_id(msg),
                            "agent_id": canonical_agent,
                            "reason": "用户主动停止",
                            "chat_turn_cancelled": True,
                        },
                    )
                )
            except Exception as exc:
                await self.bus.publish_outbound(
                    OutboundMessage(
                        channel=self.name,
                        chat_id=thread_id,
                        content=str(exc),
                        metadata={
                            "terminal_type": "error",
                            "request_id": self._request_id(msg),
                            "agent_id": canonical_agent,
                        },
                    )
                )
            finally:
                self._turn_tasks.pop(session_key, None)

        task = asyncio.create_task(_run_turn())
        self._turn_tasks[session_key] = task

    async def _handle_stop(self, ws: Any, msg: dict[str, Any]) -> None:
        thread_id = self._thread_id(msg)
        if not thread_id:
            await self._send_json(ws, self._with_request_id(msg, {"type": "error", "error": "missing thread_id"}))
            return
        payload = await self.agent_adapter.stop(msg=msg, turn_tasks=self._turn_tasks)
        self._merge_imei_into_payload(thread_id, msg, payload)
        await self._send_json(ws, self._with_request_id(msg, payload))

    async def _handle_delete_session(self, ws: Any, msg: dict[str, Any]) -> None:
        thread_id = self._thread_id(msg)
        if not thread_id:
            await self._send_json(ws, self._with_request_id(msg, {"type": "error", "error": "missing thread_id"}))
            return
        payload = await self.agent_adapter.delete_session(msg=msg, turn_tasks=self._turn_tasks)
        self._merge_imei_into_payload(thread_id, msg, payload)
        await self._send_json(ws, self._with_request_id(msg, payload))

    async def _cancel_all_turns(self) -> None:
        if not self._turn_tasks:
            return
        tasks = list(self._turn_tasks.values())
        self._turn_tasks.clear()
        for task in tasks:
            if not task.done():
                task.cancel()
        for task in tasks:
            try:
                await task
            except Exception:
                continue

    async def _send_json(self, ws: Any, payload: dict[str, Any]) -> None:
        async with self._send_lock:
            await ws.send(json.dumps(payload, ensure_ascii=False))


# Back-compat: old name for the channel class
TopoMobileWSAdapter = TopoMobileChannel
