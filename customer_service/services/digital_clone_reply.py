# -*- coding: utf-8 -*-
# Copyright 2025 OPPO
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""数字分身：好友单聊自动回复（TopoClaw）"""

from __future__ import annotations

import asyncio
import logging
import uuid

from core.deps import connection_manager
from services.custom_assistant_store import (
    DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID,
    get_default_topoclaw_assistant_id,
)
from services.friend_dispatch import dispatch_friend_message
from services.group_service import DEFAULT_ASSISTANT_ID
from services.owner_feedback_service import (
    build_owner_feedback_prompt,
    dispatch_owner_feedback_message,
    format_owner_feedback_content,
    split_reply_and_owner_feedback,
)
from services.user_settings_store import get_user_settings

logger = logging.getLogger(__name__)

DIGITAL_CLONE_SENDER_NAME = "数字分身"


def _to_bool(v: object, default: bool = False) -> bool:
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return bool(v)
    if isinstance(v, str):
        t = v.strip().lower()
        if t in {"1", "true", "yes", "on"}:
            return True
        if t in {"0", "false", "no", "off"}:
            return False
    return default


def is_digital_clone_enabled_for_friend(owner_imei: str, friend_imei: str) -> bool:
    settings = get_user_settings(owner_imei)
    raw_overrides = settings.get("digital_clone_friend_overrides")
    if isinstance(raw_overrides, dict):
        override = raw_overrides.get(friend_imei)
        if override is not None:
            return _to_bool(override, default=False)
    return _to_bool(settings.get("digital_clone_enabled"), default=False)


async def maybe_auto_reply_friend_message(
    app,
    *,
    sender_imei: str,
    target_imei: str,
    content: str,
    msg_type: str,
    image_base64: str | None,
    clone_query_context: str | None = None,
) -> None:
    """
    数字分身自动回复（目标用户侧）：
    - 开关取 target_imei（接收者）配置，支持好友级覆盖
    - 使用目标用户默认 TopoClaw 自动回复给 sender_imei
    """
    if not is_digital_clone_enabled_for_friend(target_imei, sender_imei):
        return
    hub = getattr(app.state, "topomobile_relay_hub", None)
    if hub is None:
        logger.warning("数字分身自动回复跳过：topomobile_relay_hub 不可用")
        return

    route_imei_id = connection_manager.resolve_user_adapter_route(target_imei) or target_imei
    default_topoclaw_id = get_default_topoclaw_assistant_id(target_imei)
    agent_id = default_topoclaw_id or DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID or DEFAULT_ASSISTANT_ID
    request_id = f"friend-clone-{uuid.uuid4().hex}"
    thread_id = f"friend_{target_imei}:{sender_imei}:{agent_id}"
    current_query = (content or "").strip() or ("[图片]" if image_base64 else "")
    base_context = (
        clone_query_context.strip()
        if isinstance(clone_query_context, str) and clone_query_context.strip()
        else ""
    )
    query = (
        f"{base_context}\n\n【好友本轮消息】\n{current_query}".strip()
        if base_context
        else current_query
    )
    feedback_prompt = build_owner_feedback_prompt(
        scope_type="friend",
        scope_name=f"与 {sender_imei[:8]}... 的私聊",
    )
    if feedback_prompt:
        query = f"{feedback_prompt}\n\n{query}".strip()
    if not query:
        return
    payload: dict = {
        "type": "chat",
        "request_id": request_id,
        "agent_id": agent_id,
        "thread_id": thread_id,
        "message": query,
        "message_type": msg_type or "text",
        "images": [image_base64] if image_base64 else [],
    }
    stream_id = None
    chunks: list[str] = []
    done_response = ""
    try:
        stream_id = await hub.send_stream_request(route_imei_id, payload)
        while True:
            event = await hub.recv_stream_event(stream_id, timeout=180.0)
            ev_type = str(event.get("type") or "")
            if ev_type == "delta":
                chunks.append(str(event.get("content") or ""))
                continue
            if ev_type == "done":
                done_response = str(event.get("response") or "")
                break
            if ev_type in {"error", "stopped"}:
                logger.warning(
                    "数字分身自动回复失败: target=%s..., sender=%s..., err=%s",
                    target_imei[:8],
                    sender_imei[:8],
                    str(event.get("error") or event.get("content") or "unknown"),
                )
                return
    except Exception as exc:
        logger.warning(
            "数字分身自动回复异常: target=%s..., sender=%s..., err=%s",
            target_imei[:8],
            sender_imei[:8],
            exc,
        )
        return
    finally:
        if stream_id is not None:
            await hub.close_stream_request(stream_id)

    reply = ("".join(chunks) + done_response).strip()
    if not reply:
        return
    reply, owner_feedback_text = split_reply_and_owner_feedback(reply)
    owner_feedback_content = (
        format_owner_feedback_content(
            source_type="friend",
            source_name=sender_imei,
            feedback_text=owner_feedback_text,
            source_user_imei=sender_imei,
        )
        if owner_feedback_text
        else ""
    )
    if not reply and not owner_feedback_content:
        return

    if reply:
        await dispatch_friend_message(
            target_imei,
            sender_imei,
            reply,
            message_type="text",
            image_base64=None,
            # 服务端代发分身回复，按 PC 路径镜像，确保发送方手机也能收到同步。
            sender_device="pc",
            sender_label=DIGITAL_CLONE_SENDER_NAME,
            extra_fields={
                "assistant_id": agent_id,
                "is_clone_reply": True,
                "sender_label": DIGITAL_CLONE_SENDER_NAME,
                "clone_origin": "digital_clone",
                # 分身归属：target_imei（即被代答的一方）
                "clone_owner_imei": target_imei,
            },
        )
    if owner_feedback_content:
        async def _push_owner_feedback() -> None:
            try:
                await dispatch_owner_feedback_message(
                    owner_imei=target_imei,
                    content=owner_feedback_content,
                    sender="TopoClaw",
                )
            except Exception as exc:
                logger.warning(
                    "数字分身私聊主动反馈投递失败: owner=%s..., sender=%s..., err=%s",
                    target_imei[:8],
                    sender_imei[:8],
                    exc,
                )

        asyncio.create_task(_push_owner_feedback())

