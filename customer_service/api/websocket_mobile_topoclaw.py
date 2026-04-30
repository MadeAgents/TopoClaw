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

"""WebSocket bridge: mobile -> customer_service -> topomobile adapter -> local TopoClaw."""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from typing import Any
from urllib.parse import parse_qs

from fastapi import APIRouter, FastAPI, WebSocket, WebSocketDisconnect

from api.websocket_topomobile import TopoMobileAdapterClient, TopoMobileRelayHub, relay_hub
from core.deps import connection_manager
from services.custom_assistant_store import get_custom_assistants, save_custom_assistants

logger = logging.getLogger(__name__)
router = APIRouter(tags=["mobile-topoclaw-bridge"])

_SUPPORTED_TYPES: set[str] = {
    "create_agent",
    "delete_agent",
    "chat",
    "stop",
    "delete_session",
    "skills_list",
    "skill_get",
    "skills_update",
    "skill_download",
    "skill_remove",
    "cron_list_jobs",
    "cron_create_job",
    "cron_delete_job",
    "gui_execute_result",
    "get_builtin_model_profiles",
    "set_llm_provider",
    "set_gui_provider",
}

_CHAT_TERMINAL_TYPES: set[str] = {"done", "stopped", "error"}

# 消息与 query 均未指定节点、且无法从 IMEI 映射表解析时，回退到此节点（与本地 topoclaw node_id 一致）。
_FALLBACK_TOPOMOBILE_NODE_ID = "000"


def _message_timeout(msg: dict[str, Any], default_seconds: float, max_seconds: float) -> float:
    raw = msg.get("timeout_seconds")
    try:
        if raw is None:
            return default_seconds
        val = float(raw)
        if val <= 0:
            return default_seconds
        return min(val, max_seconds)
    except Exception:
        return default_seconds


def _extract_imei_id(imei: str, msg: dict[str, Any], bound_imei_id: str) -> str:
    """解析 TopoMobile 节点 id：显式 msg/query > IMEI 路由表（bind + 默认表）> 全局回退 000。"""
    imei_id = str(msg.get("imei_id") or msg.get("node_id") or "").strip()
    if imei_id:
        return imei_id
    if bound_imei_id:
        return bound_imei_id
    mapped = connection_manager.resolve_user_adapter_route(imei)
    if mapped:
        return mapped
    return _FALLBACK_TOPOMOBILE_NODE_ID


def _upsert_agent_to_custom_assistants(
    *,
    imei: str,
    imei_id: str,
    agent_id: str,
) -> None:
    """Persist TopoClaw-created agent in existing custom-assistants storage."""
    imei = (imei or "").strip()
    agent_id = (agent_id or "").strip()
    if not imei or not agent_id:
        return
    assistants = list(get_custom_assistants(imei))
    existing = None
    for item in assistants:
        if str(item.get("id") or "").strip() == agent_id:
            existing = item
            break
    entry = dict(existing or {})
    entry.setdefault("id", agent_id)
    entry.setdefault("name", agent_id)
    entry.setdefault("intro", "TopoClaw agent")
    entry["baseUrl"] = "topoclaw://relay"
    entry.setdefault("capabilities", ["chat", "skills", "cron"])
    entry.setdefault("avatar", "")
    entry.setdefault("multiSessionEnabled", True)
    if imei_id:
        # Keep route hint without introducing new type branches.
        entry["displayId"] = imei_id
    if existing is None:
        assistants.append(entry)
    else:
        idx = assistants.index(existing)
        assistants[idx] = entry
    save_custom_assistants(imei, assistants)


def _remove_agent_from_custom_assistants(*, imei: str, agent_id: str) -> None:
    imei = (imei or "").strip()
    agent_id = (agent_id or "").strip()
    if not imei or not agent_id:
        return
    assistants = list(get_custom_assistants(imei))
    new_list = [x for x in assistants if str(x.get("id") or "").strip() != agent_id]
    if len(new_list) != len(assistants):
        save_custom_assistants(imei, new_list)


@router.websocket("/ws")
async def mobile_topoclaw_bridge_endpoint(websocket: WebSocket):
    """
    Mobile-facing websocket bridge for TopoClaw APIs.

    Usage:
    - Optional bind frame: {"type":"bind_topoclaw_node","node_id":"<node>"}
    - Then send TopoClaw request frames (create_agent/chat/skills_*/cron_* ...)
    - For chat, relay streams delta/done frames back with same request_id.
    """
    await websocket.accept()
    app = websocket.scope["app"]
    hub: TopoMobileRelayHub = getattr(app.state, "topomobile_relay_hub", relay_hub)
    adapter_client: TopoMobileAdapterClient = getattr(
        app.state,
        "topomobile_adapter_client",
        TopoMobileAdapterClient(hub),
    )

    query_imei_id = ""
    query_imei = ""
    try:
        qs = websocket.scope.get("query_string", b"").decode("utf-8", errors="ignore")
        parsed_qs = parse_qs(qs)
        query_imei_id = (parsed_qs.get("imei_id") or parsed_qs.get("node_id") or [""])[0].strip()
        query_imei = (parsed_qs.get("imei") or [""])[0].strip()
    except Exception:
        query_imei_id = ""
        query_imei = ""
    bound_imei_id = query_imei_id
    current_imei = query_imei

    logger.info(
        "mobile-topoclaw bridge connected: imei=%s, imei_id=%s",
        current_imei or "(unset)",
        bound_imei_id or "(unset)",
    )

    async def _safe_send_json(payload: dict[str, Any]) -> bool:
        """Best-effort send; return False when client WS is already closed."""
        try:
            await websocket.send_json(payload)
            return True
        except WebSocketDisconnect:
            return False
        except RuntimeError:
            # e.g. "Cannot call send once close message has been sent."
            return False
        except Exception:
            return False

    if current_imei:
        connection_manager.register_mobile_agent_connection(current_imei, websocket)
        if bound_imei_id:
            connection_manager.bind_user_adapter_route(current_imei, bound_imei_id)

    try:
        while True:
            try:
                raw = await websocket.receive_text()
            except WebSocketDisconnect:
                break

            try:
                msg: Any = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_json({"type": "error", "error": "invalid JSON"})
                continue
            if not isinstance(msg, dict):
                await websocket.send_json({"type": "error", "error": "message must be object"})
                continue

            msg_type = str(msg.get("type") or "").strip()
            request_id = str(msg.get("request_id") or "").strip() or f"mws-{uuid.uuid4().hex}"

            if msg_type == "ping":
                if not await _safe_send_json({"type": "pong", "request_id": request_id}):
                    break
                continue

            if msg_type == "register":
                thread_id = str(msg.get("thread_id") or "").strip()
                device_id = str(msg.get("device_id") or "").strip()
                device_type = str(msg.get("device_type") or "").strip()
                imei_from_msg = str(msg.get("imei") or "").strip()
                imei_id_from_msg = str(msg.get("imei_id") or msg.get("node_id") or "").strip()
                if imei_from_msg:
                    current_imei = imei_from_msg
                if imei_id_from_msg:
                    bound_imei_id = imei_id_from_msg
                if current_imei:
                    connection_manager.register_mobile_agent_connection(current_imei, websocket)
                if current_imei and bound_imei_id:
                    connection_manager.bind_user_adapter_route(current_imei, bound_imei_id)
                logger.info(
                    "mobile-topoclaw register: imei=%s imei_id=%s thread_id=%s device_type=%s device_id=%s",
                    current_imei or "(unset)",
                    bound_imei_id or "(unset)",
                    thread_id or "(unset)",
                    device_type or "(unset)",
                    device_id or "(unset)",
                )
                if not await _safe_send_json({"type": "registered", "thread_id": thread_id}):
                    break
                continue

            if msg_type == "bind_topoclaw_node":
                imei_id = str(msg.get("imei_id") or msg.get("node_id") or "").strip()
                if not imei_id:
                    if not await _safe_send_json(
                        {"type": "error", "error": "imei_id is required", "request_id": request_id}
                    ):
                        break
                    continue
                bound_imei_id = imei_id
                if current_imei:
                    connection_manager.bind_user_adapter_route(current_imei, bound_imei_id)
                if not await _safe_send_json(
                    {
                        "type": "node_bound",
                        "ok": True,
                        "imei": current_imei,
                        "imei_id": bound_imei_id,
                        "request_id": request_id,
                    }
                ):
                    break
                continue

            if msg_type not in _SUPPORTED_TYPES:
                if not await _safe_send_json(
                    {
                        "type": "error",
                        "error": f"unsupported type: {msg_type}",
                        "request_id": request_id,
                    }
                ):
                    break
                continue

            imei_id = _extract_imei_id(current_imei, msg, bound_imei_id)
            outbound = dict(msg)
            outbound["request_id"] = request_id
            outbound.pop("imei_id", None)
            outbound.pop("node_id", None)
            if current_imei and "imei" not in outbound:
                outbound["imei"] = current_imei

            # 为 chat 消息添加上下文（仅当私聊默认 agent 时）
            # 默认 agent: topoclaw 或 assistant，其他 agent 只转发消息本身
            if msg_type == "chat" and current_imei:
                try:
                    from services.custom_assistant_store import (
                        DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID,
                        is_topoclaw_assistant_id,
                    )
                    from services.group_service import DEFAULT_ASSISTANT_ID
                    
                    agent_id = str(outbound.get("agent_id") or "")
                    is_default_agent = (
                        agent_id == DEFAULT_ASSISTANT_ID
                        or agent_id == DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID
                        or is_topoclaw_assistant_id(current_imei, agent_id)
                    )
                    
                    if is_default_agent:
                        from services.custom_assistant_store import get_custom_assistants
                        from storage.profiles import profiles_storage

                        context_parts = []

                        # 解析主人昵称
                        owner_profile = profiles_storage.get(current_imei, {}) or {}
                        owner_name = str(owner_profile.get("name") or "").strip() or current_imei[:8] + "..."
                        context_parts.append(
                            f"【会话身份】这是你的主人「{owner_name}」（IMEI: {current_imei}）在私聊会话中发来的消息。"
                            ' 当主人问"我是谁"或类似身份问题时，请回答主人的身份信息，而不是你自己的。'
                        )

                        user_agents = get_custom_assistants(current_imei)
                        if user_agents:
                            context_parts.append("\n【用户创建的助手】")
                            context_parts.append("用户创建了以下助手，你可以根据需要建议用户 @ 对应的助手：")
                            
                            for a in user_agents:
                                aid = str(a.get("id", ""))
                                name = str(a.get("name", ""))
                                intro = str(a.get("intro", ""))
                                if aid:
                                    desc = f"  - @{aid}"
                                    if name and name != aid:
                                        desc += f"({name})"
                                    if intro:
                                        desc += f" - {intro}"
                                    context_parts.append(desc)

                        context_parts.append(f"\n【来自主人「{owner_name}」的消息】\n")
                        context_text = "\n".join(context_parts)

                        original_message = outbound.get("message", "")
                        outbound["message"] = context_text + original_message
                except Exception as e:
                    logger.warning(f"构建 chat 上下文失败: {e}")

            try:
                if msg_type == "chat":
                    # import pdb; pdb.set_trace()
                    timeout = _message_timeout(msg, default_seconds=180.0, max_seconds=1800.0)
                    stream_id = await hub.send_stream_request(imei_id, outbound)
                    try:
                        while True:
                            event = await hub.recv_stream_event(stream_id, timeout=timeout)
                            if not await _safe_send_json(event):
                                raise WebSocketDisconnect(code=1006)
                            ev_type = str(event.get("type") or "")
                            if ev_type in _CHAT_TERMINAL_TYPES:
                                break
                    finally:
                        await hub.close_stream_request(stream_id)
                else:
                    timeout = _message_timeout(msg, default_seconds=30.0, max_seconds=600.0)
                    response = await adapter_client.call(imei_id, outbound, timeout=timeout)
                    r_type = str(response.get("type") or "")
                    if r_type == "agent_created" and bool(response.get("ok")):
                        _upsert_agent_to_custom_assistants(
                            imei=current_imei,
                            imei_id=imei_id,
                            agent_id=str(response.get("agent_id") or str(msg.get("agent_id") or "")),
                        )
                    elif r_type == "agent_deleted" and bool(response.get("ok")):
                        _remove_agent_from_custom_assistants(
                            imei=current_imei,
                            agent_id=str(response.get("agent_id") or str(msg.get("agent_id") or "")),
                        )
                    if not await _safe_send_json(response):
                        break
            except asyncio.TimeoutError:
                if not await _safe_send_json(
                    {
                        "type": "error",
                        "error": "relay timeout",
                        "imei_id": imei_id,
                        "request_id": request_id,
                    }
                ):
                    break
            except Exception as exc:
                logger.warning(
                    "mobile-topoclaw relay failed: imei=%s imei_id=%s type=%s err=%s",
                    current_imei,
                    imei_id,
                    msg_type,
                    exc,
                )
                if not await _safe_send_json(
                    {
                        "type": "error",
                        "error": str(exc),
                        "imei_id": imei_id,
                        "request_id": request_id,
                    }
                ):
                    break
    finally:
        if current_imei:
            connection_manager.unregister_mobile_agent_connection(current_imei, websocket)
        logger.info("mobile-topoclaw bridge disconnected: imei=%s", current_imei or "(unset)")


def register_mobile_topoclaw_websocket(app: FastAPI) -> None:
    """Register mobile-facing websocket bridge route."""
    app.include_router(router)
