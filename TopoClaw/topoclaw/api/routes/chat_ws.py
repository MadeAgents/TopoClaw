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

"""Unified websocket route (/ws) with service-level message dispatch."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import APIRouter, FastAPI, WebSocket, WebSocketDisconnect

from topoclaw.connection.ws_registry import WSConnectionRegistry
from topoclaw.service.chat_service import ChatService
from topoclaw.service.connection_app_service import ConnectionAppService
from topoclaw.service.agent_manage_service import execute_create_agent, execute_delete_agent
from topoclaw.agent.session_keys import normalize_agent_id, websocket_session_key
from topoclaw.service.gui_mobile_service import MobileGUIService
from topoclaw.service.skills_service import SkillsService
from topoclaw.service.orchestration_service import OrchestrationService
from topoclaw.config.loader import get_config_path

router = APIRouter(prefix="", tags=["chat"])


def _chat_service(app: FastAPI) -> ChatService:
    return app.state.chat_service


def _connection_registry(app: FastAPI) -> WSConnectionRegistry:
    return app.state.connection_registry


def _connection_app_service(app: FastAPI) -> ConnectionAppService:
    return app.state.connection_app_service


def _mobile_gui_service(app: FastAPI) -> MobileGUIService:
    return app.state.mobile_gui_service


def _skills_service(app: FastAPI) -> SkillsService:
    return app.state.skills_service


def _orchestration_service(app: FastAPI) -> Any:
    return getattr(app.state, "orchestration_service", None)


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


def _builtin_model_profiles_payload(
    app: FastAPI,
    *,
    request_id: str = "",
    agent_id: str | None = None,
) -> dict[str, Any]:
    chat_models: list[str] = []
    gui_models: list[str] = []
    active_chat = ""
    active_gui = ""
    try:
        cfg_path = get_config_path()
        raw = json.loads(cfg_path.read_text(encoding="utf-8")) if cfg_path.is_file() else {}
        topo_desktop = raw.get("topo_desktop") if isinstance(raw, dict) else None
        if isinstance(topo_desktop, dict):
            chat_models = _extract_models_from_profiles(
                topo_desktop.get("nonGuiProfiles") or topo_desktop.get("non_gui_profiles")
            )
            gui_models = _extract_models_from_profiles(
                topo_desktop.get("guiProfiles") or topo_desktop.get("gui_profiles")
            )
            active_chat = str(topo_desktop.get("activeNonGuiModel") or "").strip()
            active_gui = str(topo_desktop.get("activeGuiModel") or "").strip()
    except Exception:
        chat_models = []
        gui_models = []

    runtime = getattr(app.state, "runtime", None)
    cfg = getattr(app.state, "topoclaw_config", None)
    default_chat = str(getattr(getattr(cfg, "agents", None).defaults, "model", "") or "").strip() if cfg else ""
    default_gui = ""
    if cfg and getattr(getattr(cfg, "agents", None), "gui", None) is not None:
        default_gui = str(getattr(cfg.agents.gui, "model", "") or "").strip()
    if not chat_models and default_chat:
        chat_models = [default_chat]
    if not gui_models:
        gui_models = [default_gui] if default_gui else ([chat_models[0]] if chat_models else [])

    runtime_loop = getattr(runtime, "agent", None)
    reg = getattr(runtime, "agent_registry", None) if runtime else None
    if reg is not None:
        try:
            runtime_loop = reg.resolve_loop(agent_id)
        except Exception:
            runtime_loop = getattr(runtime, "agent", None)
    runtime_chat = str(getattr(runtime_loop, "model", "") or "").strip()
    if runtime_chat:
        active_chat = runtime_chat
    runtime_gui = str(getattr(getattr(app.state, "mobile_agent", None), "model", "") or "").strip()
    if not runtime_gui:
        runtime_gui = str(getattr(getattr(app.state, "mobile_agent", None), "model", "") or "").strip()
    if runtime_gui:
        active_gui = runtime_gui

    if not active_chat:
        active_chat = chat_models[0] if chat_models else ""
    if not active_gui:
        active_gui = gui_models[0] if gui_models else ""

    return {
        "type": "builtin_model_profiles_result",
        "ok": True,
        "request_id": request_id,
        "non_gui_profiles": chat_models,
        "gui_profiles": gui_models,
        "active_non_gui_model": active_chat,
        "active_gui_model": active_gui,
    }


@router.websocket("/ws")
async def ws_unified(websocket: WebSocket):
    """Unified websocket endpoint for chat and GUI execution bridge."""
    await websocket.accept()

    app: FastAPI = websocket.scope["app"]
    registry = _connection_registry(app)
    chat_service = _chat_service(app)
    connection_service = _connection_app_service(app)
    mobile_gui_service = _mobile_gui_service(app)
    skills_service = _skills_service(app)
    runtime = getattr(app.state, "runtime", None)

    conn_id = await registry.register(websocket)
    await chat_service.accept_connection(conn_id)
    current_chat_task: asyncio.Task[dict[str, Any] | None] | None = None
    current_thread_id: str = ""
    current_turn_agent: str | None = None

    async def receiver() -> None:
        """Read raw frames, JSON-parse, and forward to ChatService."""
        nonlocal current_chat_task, current_thread_id, current_turn_agent
        try:
            while True:
                try:
                    raw = await websocket.receive_text()
                except WebSocketDisconnect:
                    break

                try:
                    msg: Any = json.loads(raw)
                except json.JSONDecodeError:
                    if not await registry.send(conn_id, {"type": "error", "error": "无效 JSON"}):
                        break
                    continue

                if not isinstance(msg, dict):
                    if not await registry.send(conn_id, {"type": "error", "error": "消息必须是对象"}):
                        break
                    continue

                msg_type = str(msg.get("type") or "")
                
                if msg_type == "user_confirmed":
                    if chat_service.submit_tool_guard_confirmation(conn_id, msg):
                        continue
                    if not await registry.send(
                        conn_id,
                        {
                            "type": "error",
                            "error": "无效的 tool_guard 确认（无待确认请求或 confirmation_id 不匹配）",
                        },
                    ):
                        break
                    continue
                if msg_type == "create_agent":
                    cfg = getattr(app.state, "topoclaw_config", None)
                    if (
                        not cfg
                        or not runtime
                        or not getattr(runtime, "agent_registry", None)
                        or not getattr(runtime, "bus", None)
                        or not getattr(runtime, "cron", None)
                    ):
                        if not await registry.send(
                            conn_id,
                            {
                                "type": "agent_created",
                                "ok": False,
                                "error": "create_agent unavailable (service not fully configured)",
                            },
                        ):
                            break
                        continue
                    reg = runtime.agent_registry
                    payload = await execute_create_agent(
                        msg=msg,
                        config=cfg,
                        registry=reg,
                        bus=runtime.bus,
                        cron_service=runtime.cron,
                        provider=runtime.agent.provider,
                    )
                    if not await registry.send(conn_id, payload):
                        break
                    continue
                if msg_type == "delete_agent":
                    cfg = getattr(app.state, "topoclaw_config", None)
                    if not cfg or not runtime or not getattr(runtime, "agent_registry", None):
                        if not await registry.send(
                            conn_id,
                            {
                                "type": "agent_deleted",
                                "ok": False,
                                "error": "delete_agent unavailable (service not fully configured)",
                            },
                        ):
                            break
                        continue
                    reg = runtime.agent_registry
                    payload = await execute_delete_agent(
                        msg=msg,
                        config=cfg,
                        registry=reg,
                    )
                    if not await registry.send(conn_id, payload):
                        break
                    continue
                if msg_type == "get_builtin_model_profiles":
                    request_id = str(msg.get("request_id") or "").strip()
                    raw_agent = msg.get("agent_id")
                    agent_kw = str(raw_agent).strip() if raw_agent is not None else ""
                    payload = _builtin_model_profiles_payload(
                        app,
                        request_id=request_id,
                        agent_id=(agent_kw or None),
                    )
                    if not await registry.send(conn_id, payload):
                        break
                    continue
                if msg_type == "set_llm_provider":
                    target = str(msg.get("target") or "").strip().lower()
                    provider_service = getattr(app.state, "provider_service", None)
                    if target == "gui":
                        if not provider_service:
                            if not await registry.send(
                                conn_id,
                                {
                                    "type": "set_gui_provider_result",
                                    "ok": False,
                                    "applied": False,
                                    "reason": "set_gui_provider unavailable (provider service missing)",
                                    "patch_keys": [],
                                    "updated_targets": [],
                                    "errors": [],
                                },
                            ):
                                break
                            continue
                        try:
                            payload = await provider_service.handle_set_gui_provider(
                                msg,
                                mobile_agent=getattr(app.state, "mobile_agent", None),
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
                        if not await registry.send(conn_id, payload):
                            break
                        continue
                    if not provider_service or not runtime or not getattr(runtime, "agent_registry", None):
                        if not await registry.send(
                            conn_id,
                            {
                                "type": "set_llm_provider_result",
                                "ok": False,
                                "applied": False,
                                "reason": "set_llm_provider unavailable (service not fully configured)",
                                "patch_keys": [],
                                "updated_agent_ids": [],
                                "errors": [],
                            },
                        ):
                            break
                        continue
                    try:
                        payload = await provider_service.handle_set_llm_provider(msg)
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
                    if not await registry.send(conn_id, payload):
                        break
                    continue
                if msg_type == "set_gui_provider":
                    provider_service = getattr(app.state, "provider_service", None)
                    if not provider_service:
                        if not await registry.send(
                            conn_id,
                            {
                                "type": "set_gui_provider_result",
                                "ok": False,
                                "applied": False,
                                "reason": "set_gui_provider unavailable (provider service missing)",
                                "patch_keys": [],
                                "updated_targets": [],
                                "errors": [],
                            },
                        ):
                            break
                        continue
                    try:
                        payload = await provider_service.handle_set_gui_provider(
                            msg,
                            mobile_agent=getattr(app.state, "mobile_agent", None),
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
                    if not await registry.send(conn_id, payload):
                        break
                    continue
                if msg_type == "chat":
                    await connection_service.try_mark_device_online_from_chat(conn_id, msg)
                    await chat_service.enqueue_chat_message(conn_id, msg)
                    continue
                if msg_type.startswith("skills_") or msg_type.startswith("skill_"):
                    payload = await skills_service.handle_ws_message(msg)
                    if payload is not None:
                        if not await registry.send(conn_id, payload):
                            break
                        continue
                if msg_type.startswith("cron_"):
                    if not runtime or not getattr(runtime, "cron", None):
                        if not await registry.send(
                            conn_id,
                            {"type": "error", "error": "cron service unavailable"},
                        ):
                            break
                        continue
                    payload = await runtime.cron.handle_ws_message(msg)
                    if payload is not None:
                        if not await registry.send(conn_id, payload):
                            break
                        continue
                if msg_type in ("task", "list_orchestrations", "delete_orchestration"):
                    orch_svc = getattr(app.state, "orchestration_service", None)
                    if not orch_svc:
                        if not await registry.send(
                            conn_id,
                            {"type": "error", "error": "orchestration service unavailable"},
                        ):
                            break
                        continue
                    result = await orch_svc.handle_ws_message(msg)
                    if result is not None:
                        if not await registry.send(conn_id, result):
                            break
                    continue
                if msg_type == "stop":
                    stop_reason = "用户主动停止"
                    stop_thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
                    if not stop_thread_id:
                        stop_thread_id = current_thread_id
                    if not stop_thread_id:
                        meta = await registry.get_metadata(conn_id)
                        stop_thread_id = str((meta or {}).get("thread_id") or "").strip()

                    raw_stop_agent = msg.get("agent_id")
                    stop_agent_kw = str(raw_stop_agent).strip() if raw_stop_agent is not None else None
                    stop_canonical = normalize_agent_id(stop_agent_kw)

                    turn_cancelled = False
                    if current_chat_task and not current_chat_task.done():
                        current_chat_task.cancel()
                        turn_cancelled = True

                    sub_cancelled = 0
                    if runtime and stop_thread_id:
                        try:
                            sk = websocket_session_key(stop_agent_kw, stop_thread_id)
                            reg = getattr(runtime, "agent_registry", None)
                            if reg:
                                agent_loop, _, _ = await reg.materialize(stop_agent_kw)
                            else:
                                agent_loop = runtime.agent
                            sub_cancelled = await agent_loop.subagents.cancel_by_session(sk)
                        except Exception:
                            sub_cancelled = 0

                    stopped_mobile = await mobile_gui_service.stop_active_tasks(
                        thread_id=stop_thread_id or None,
                        reason=stop_reason,
                    )
                    await registry.send(
                        conn_id,
                        {
                            "type": "stopped",
                            "thread_id": stop_thread_id,
                            "reason": stop_reason,
                            "chat_turn_cancelled": turn_cancelled,
                            "subtasks_cancelled": sub_cancelled,
                            "gui_tasks_stopped": {
                                "mobile": stopped_mobile,
                            },
                        },
                    )
                    continue
                if msg_type == "delete_session":
                    del_reason = "会话已删除"
                    del_thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
                    if not del_thread_id:
                        del_thread_id = current_thread_id
                    if not del_thread_id:
                        meta = await registry.get_metadata(conn_id)
                        del_thread_id = str((meta or {}).get("thread_id") or "").strip()

                    raw_del_agent = msg.get("agent_id")
                    del_agent_kw = str(raw_del_agent).strip() if raw_del_agent is not None else None
                    del_canonical = normalize_agent_id(del_agent_kw)

                    turn_cancelled = False
                    if (
                        current_chat_task
                        and not current_chat_task.done()
                        and del_thread_id
                        and current_thread_id == del_thread_id
                        and current_turn_agent is not None
                        and current_turn_agent == del_canonical
                    ):
                        current_chat_task.cancel()
                        turn_cancelled = True

                    result = await chat_service.delete_websocket_session(conn_id, msg)
                    if not result.get("ok"):
                        continue

                    resolved_tid = str(result.get("thread_id_resolved") or "").strip()
                    raw_tid = str(result.get("thread_id") or del_thread_id or "").strip()
                    for tid in {resolved_tid, raw_tid} - {""}:
                        await connection_service.unbind_thread_for_connection(conn_id, tid)

                    stop_gui_tid = str(result.get("thread_id_resolved") or del_thread_id or "").strip() or None
                    stopped_mobile = await mobile_gui_service.stop_active_tasks(
                        thread_id=stop_gui_tid,
                        reason=del_reason,
                    )
                    deleted_payload: dict[str, Any] = {
                        "type": "session_deleted",
                        "thread_id": del_thread_id,
                        "thread_id_resolved": result.get("thread_id_resolved"),
                        "agent_id": del_canonical,
                        "session_key": result.get("session_key"),
                        "session_keys_tried": result.get("session_keys_tried"),
                        "chat_turn_cancelled": turn_cancelled,
                        "subtasks_cancelled": result.get("subtasks_cancelled", 0),
                        "session_file_existed": result.get("session_file_existed"),
                        "session_file_removed": result.get("session_file_removed"),
                        "gui_tasks_stopped": {
                            "mobile": stopped_mobile,
                        },
                    }
                    if result.get("error"):
                        deleted_payload["error"] = result.get("error")
                    await registry.send(conn_id, deleted_payload)
                    continue
                if await mobile_gui_service.handle_ws_message(msg):
                    continue
                should_close = await connection_service.handle_ws_message(conn_id, msg)
                if should_close:
                    break
        finally:
            chat_service.shutdown_chat_queue(conn_id)

    async def worker() -> None:
        """Sequentially process queued chat messages."""
        nonlocal current_chat_task, current_thread_id, current_turn_agent
        while True:
            msg = await chat_service.next_chat_message(conn_id)
            if msg is None:
                return
            current_thread_id = str(msg.get("thread_id") or msg.get("session_id") or "").strip()
            raw_wa = msg.get("agent_id")
            wa_kw = str(raw_wa).strip() if raw_wa is not None else None
            current_turn_agent = normalize_agent_id(wa_kw)
            current_chat_task = asyncio.create_task(chat_service.handle_chat_turn(conn_id, msg))
            result: dict[str, Any] | None = None
            try:
                result = await current_chat_task
            except asyncio.CancelledError:
                pass
            finally:
                current_chat_task = None
                current_turn_agent = None
            if not result:
                continue
            await connection_service.push_to_thread(
                thread_id=str(result.get("thread_id") or ""),
                payload={
                    "type": "assistant_push",
                    "thread_id": str(result.get("thread_id") or ""),
                    "agent_id": str(result.get("agent_id") or "default"),
                    "content": str(result.get("response") or ""),
                },
                exclude_conn_id=conn_id,
            )
            current_thread_id = ""

    recv_task = asyncio.create_task(receiver())
    work_task = asyncio.create_task(worker())
    try:
        await asyncio.gather(recv_task, work_task)
    finally:
        recv_task.cancel()
        work_task.cancel()
        await connection_service.release_connection(conn_id)
        await registry.remove(conn_id)
        try:
            await websocket.close()
        except Exception:
            pass
