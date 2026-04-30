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

"""多端：端云互发、PC 执行指令、统一消息、session 同步"""
import asyncio
import logging
import uuid
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from core.deps import connection_manager
from core.time_utils import get_now_isoformat
from schemas.models import (
    ActiveSessionSetRequest,
    CrossDeviceExecuteRequest,
    CrossDeviceMobileToolInvokeRequest,
    CrossDeviceSendRequest,
    SessionSyncRequest,
)
from services.mobile_tool_bridge import (
    new_request_id,
    pop_pending_mobile_tool_result,
    register_pending_mobile_tool_result,
)
from services.active_session_store import get_active as active_session_get
from services.active_session_store import set_active as active_session_set
from services.session_store import get_sessions as session_store_get
from services.session_store import sync_sessions as session_store_sync
from services.unified_message_store import (
    append_assistant_msg,
    append_cross_device_msg,
    append_group_msg,
    get_assistant_messages,
    get_friend_messages,
    get_group_messages,
    get_messages,
    parse_created_at_ms,
)
from storage.cross_device import get_cross_device_messages, save_cross_device_messages

logger = logging.getLogger(__name__)
router = APIRouter(tags=["cross-device"])


@router.post("/api/cross-device/send")
async def send_cross_device_message(request: CrossDeviceSendRequest):
    """发送端云互发消息（PC 或手机 -> 另一侧）"""
    try:
        imei = request.imei.strip()
        from_device = "pc"
        msg_id = str(uuid.uuid4())
        created_at = get_now_isoformat()
        msg = {
            "id": msg_id,
            "from_device": from_device,
            "content": request.content,
            "message_type": request.message_type or "text",
            "created_at": created_at,
        }
        if request.file_base64:
            msg["file_base64"] = request.file_base64
        if request.file_name:
            msg["file_name"] = request.file_name
        store = get_cross_device_messages()
        if imei not in store:
            store[imei] = []
        store[imei].append(msg)
        save_cross_device_messages()
        try:
            append_cross_device_msg(imei, dict(msg))
        except Exception as e:
            logger.warning(f"端云消息双写到 unified_messages 失败（cross_device 已保存）: {e}")
        push_msg = {
            "type": "cross_device_message",
            "message_id": msg_id,
            "from_device": "pc",
            "sender": "我的电脑",
            "content": request.content,
            "message_type": request.message_type or "text",
            "timestamp": created_at,
        }
        if request.file_base64:
            push_msg["file_base64"] = request.file_base64
        if request.file_name:
            push_msg["file_name"] = request.file_name
        if connection_manager.is_user_online(imei):
            await connection_manager.send_to_user(imei, push_msg)
            logger.info(f"端云消息已实时推送到手机: {imei[:8]}...")
        return JSONResponse({"success": True, "message_id": msg_id})
    except Exception as e:
        logger.error(f"发送端云消息失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/cross-device/execute")
async def send_execute_command(request: CrossDeviceExecuteRequest):
    """PC 端发起执行指令，转发到手机端执行，手机执行后将结果回传 PC"""
    try:
        imei = request.imei.strip()
        msg_id = str(uuid.uuid4())
        conv_id = (request.conversation_id or "").strip()
        if conv_id.startswith("group_"):
            gid = conv_id.replace("group_", "", 1)
            append_group_msg(
                gid, imei, request.query, type="user", sender="我", id=msg_id, uuid=request.uuid
            )
        else:
            append_assistant_msg(
                imei,
                sender="我",
                content=request.query,
                type="user",
                id=msg_id,
                uuid=request.uuid,
            )
        push_msg = {
            "type": "pc_execute_command",
            "message_id": msg_id,
            "query": request.query,
            "uuid": request.uuid,
            "timestamp": get_now_isoformat(),
        }
        if request.steps:
            push_msg["steps"] = request.steps
        if request.assistant_base_url:
            push_msg["assistant_base_url"] = request.assistant_base_url
        if request.conversation_id:
            push_msg["conversation_id"] = request.conversation_id
        if request.chat_summary:
            push_msg["chat_summary"] = request.chat_summary
        if connection_manager.is_user_online(imei):
            await connection_manager.send_to_user(imei, push_msg)
            logger.info(f"PC 执行指令已推送到手机: {imei[:8]}..., query={request.query[:50]}...")
            return JSONResponse({"success": True, "message_id": msg_id})
        return JSONResponse(
            {
                "success": False,
                "message": "手机端不在线，请确保手机已打开应用并保持连接",
                "message_id": msg_id,
            },
            status_code=503,
        )
    except Exception as e:
        logger.error(f"PC 执行指令转发失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/cross-device/mobile-tool-invoke")
async def invoke_mobile_tool(request: CrossDeviceMobileToolInvokeRequest):
    """HTTP 触发 mobile_tool 调用，支持等待 mobile_tool_result。"""
    try:
        imei = request.imei.strip()
        if not imei:
            return JSONResponse({"success": False, "message": "imei 不能为空"}, status_code=400)

        tool = str(request.tool or "").strip()
        if not tool:
            return JSONResponse({"success": False, "message": "tool 不能为空"}, status_code=400)

        if not connection_manager.is_user_online(imei):
            return JSONResponse(
                {
                    "success": False,
                    "message": "手机端不在线，无法执行端侧工具",
                    "imei": imei,
                },
                status_code=503,
            )

        request_id = (request.request_id or "").strip() or new_request_id("mobile_tool")
        protocol = str(request.protocol or "mobile_tool/v1").strip() or "mobile_tool/v1"
        conversation_id = str(request.conversation_id or "assistant").strip() or "assistant"
        timeout_ms = max(1000, min(int(request.timeout_ms or 15000), 60000))
        args = request.args if isinstance(request.args, dict) else {}

        push_msg = {
            "type": "mobile_tool_invoke",
            "request_id": request_id,
            "protocol": protocol,
            "conversation_id": conversation_id,
            "payload": {
                "tool": tool,
                "args": args,
            },
            "timestamp": get_now_isoformat(),
        }

        pending_future = None
        if request.wait_result:
            pending_future = register_pending_mobile_tool_result(imei, request_id)

        sent = await connection_manager.send_to_user(imei, push_msg)
        if not sent:
            if request.wait_result:
                pop_pending_mobile_tool_result(imei, request_id)
            return JSONResponse(
                {
                    "success": False,
                    "message": "消息下发失败，手机连接可能已断开",
                    "imei": imei,
                    "request_id": request_id,
                },
                status_code=503,
            )

        if not request.wait_result:
            return JSONResponse(
                {
                    "success": True,
                    "accepted": True,
                    "imei": imei,
                    "request_id": request_id,
                    "protocol": protocol,
                    "tool": tool,
                    "conversation_id": conversation_id,
                }
            )

        assert pending_future is not None
        try:
            result_message = await asyncio.wait_for(pending_future, timeout=timeout_ms / 1000.0)
        except asyncio.TimeoutError:
            pop_pending_mobile_tool_result(imei, request_id)
            return JSONResponse(
                {
                    "success": False,
                    "timeout": True,
                    "message": f"等待 mobile_tool_result 超时（{timeout_ms}ms）",
                    "imei": imei,
                    "request_id": request_id,
                    "protocol": protocol,
                    "tool": tool,
                },
                status_code=504,
            )

        payload = result_message.get("payload") if isinstance(result_message, dict) else {}
        payload = payload if isinstance(payload, dict) else {}
        ok = bool(payload.get("ok") is True)
        return JSONResponse(
            {
                "success": ok,
                "imei": imei,
                "request_id": request_id,
                "protocol": protocol,
                "tool": tool,
                "result": result_message,
            }
        )
    except Exception as e:
        logger.error(f"mobile_tool HTTP 调用失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/cross-device/messages")
async def get_cross_device_messages_api(
    imei: str,
    before_id: Optional[str] = None,
    limit: int = 20,
    since_timestamp: Optional[int] = None,
):
    """分页获取端云互发历史消息。since_timestamp: 毫秒时间戳，仅返回该时间之后的消息（增量同步）"""
    try:
        store = get_cross_device_messages()
        msgs = store.get(imei, [])
        msgs = sorted(msgs, key=lambda m: m.get("created_at", ""), reverse=True)
        if since_timestamp is not None and since_timestamp > 0:
            msgs = [m for m in msgs if parse_created_at_ms(m) > since_timestamp]
        if before_id:
            idx = next((i for i, m in enumerate(msgs) if m.get("id") == before_id), len(msgs))
            msgs = msgs[idx + 1 :]
        result = msgs[:limit]
        return JSONResponse(
            {"success": True, "messages": result, "has_more": len(msgs) > limit}
        )
    except Exception as e:
        logger.error(f"获取端云消息失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/messages")
async def get_unified_messages(
    imei: str,
    conversation_id: str,
    before_id: Optional[str] = None,
    limit: int = 20,
    since_timestamp: Optional[int] = None,
):
    """统一消息接口：分页获取任意会话的历史消息。"""
    try:
        imei = imei.strip()
        cid = conversation_id.strip()
        since_ms = since_timestamp if (since_timestamp is not None and since_timestamp > 0) else None
        if cid == "assistant":
            msgs, has_more = get_assistant_messages(imei, before_id, limit, since_ms)
        elif cid == "_me":
            store = get_cross_device_messages()
            msgs = store.get(imei, [])
            msgs = sorted(msgs, key=lambda m: m.get("created_at", ""), reverse=True)
            if since_ms is not None:
                msgs = [m for m in msgs if parse_created_at_ms(m) > since_ms]
            if before_id:
                idx = next((i for i, m in enumerate(msgs) if m.get("id") == before_id), len(msgs))
                msgs = msgs[idx + 1 :]
            has_more = len(msgs) > limit
            msgs = msgs[:limit]
        elif cid.startswith("friend_"):
            other_imei = cid.replace("friend_", "")
            if other_imei:
                msgs, has_more = get_friend_messages(imei, other_imei, before_id, limit, since_ms)
            else:
                msgs, has_more = [], False
        elif cid.startswith("group_"):
            gid = cid.replace("group_", "")
            msgs, has_more = get_group_messages(gid, before_id, limit, since_ms)
        else:
            cid = f"{imei}_{cid}" if not cid.startswith(imei) else cid
            cid = cid.replace("__", "_")
            msgs, has_more = get_messages(cid, before_id, limit, since_ms)
        return JSONResponse({"success": True, "messages": msgs, "has_more": has_more})
    except Exception as e:
        logger.error(f"获取统一消息失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/sessions")
async def get_sessions_api(imei: str, conversation_id: str, base_url: Optional[str] = None):
    """获取多 session 自定义小助手的 session 列表。"""
    try:
        imei = imei.strip()
        cid = conversation_id.strip()
        if not cid:
            return JSONResponse({"success": True, "sessions": []})
        sessions = session_store_get(imei, cid, base_url or "")
        return JSONResponse({"success": True, "sessions": sessions})
    except Exception as e:
        logger.error(f"获取 session 列表失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/sessions/sync")
async def sync_sessions_api(request: SessionSyncRequest):
    """同步 session 列表：客户端上传本地 sessions，服务端合并后返回"""
    try:
        imei = request.imei.strip()
        cid = request.conversation_id.strip()
        base_url = request.base_url or ""
        logger.info(
            f"[Session] sync 收到: imei={imei[:8]}..., conv={cid[:20]}..., base_url={'有' if base_url else '无'}, 上传数量={len(request.sessions)}"
        )
        if not cid:
            return JSONResponse({"success": True, "sessions": []})
        merged = session_store_sync(imei, cid, request.sessions, base_url=base_url)
        logger.info(f"[Session] sync 返回: 合并后数量={len(merged)}")
        return JSONResponse({"success": True, "sessions": merged})
    except Exception as e:
        logger.error(f"同步 session 失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/sessions/active")
async def get_active_session_api(imei: str, conversation_id: str, base_url: Optional[str] = None):
    """获取多 session 自定义小助手当前活跃 session（跨端跟切）。"""
    try:
        imei = imei.strip()
        cid = conversation_id.strip()
        if not cid:
            return JSONResponse(
                {"success": True, "active_session_id": None, "updated_at": 0}
            )
        sid, ut = active_session_get(imei, cid, base_url or "")
        return JSONResponse(
            {
                "success": True,
                "active_session_id": sid,
                "updated_at": ut,
            }
        )
    except Exception as e:
        logger.error(f"获取 active session 失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/sessions/active")
async def set_active_session_api(request: ActiveSessionSetRequest):
    """设置活跃 session，并向该用户手机+PC WebSocket 广播 custom_assistant_active_session。"""
    try:
        imei = request.imei.strip()
        cid = request.conversation_id.strip()
        base_url = request.base_url or ""
        if not cid or not request.active_session_id.strip():
            return JSONResponse(
                {"success": False, "message": "conversation_id 或 active_session_id 为空"},
                status_code=400,
            )
        sid, ut = active_session_set(imei, cid, request.active_session_id.strip(), base_url=base_url)
        payload = {
            "type": "custom_assistant_active_session",
            "conversation_id": cid,
            "base_url": base_url,
            "active_session_id": sid,
            "updated_at": ut,
        }
        await connection_manager.notify_imei_all_devices(imei, payload)
        return JSONResponse(
            {
                "success": True,
                "active_session_id": sid,
                "updated_at": ut,
            }
        )
    except ValueError as e:
        return JSONResponse({"success": False, "message": str(e)}, status_code=400)
    except Exception as e:
        logger.error(f"设置 active session 失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
