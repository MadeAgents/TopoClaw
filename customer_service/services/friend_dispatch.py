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

"""好友单聊：统一投递逻辑（WebSocket / HTTP 共用）"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Literal, Optional

from core.deps import connection_manager, message_service
from core.time_utils import get_now_isoformat
from services.unified_message_store import append_friend_msg
from storage.friends import friends_storage

logger = logging.getLogger(__name__)

SenderDevice = Literal["mobile", "pc"]


@dataclass
class FriendDispatchResult:
    ok: bool
    message_id: Optional[str] = None
    error_content: Optional[str] = None
    target_was_offline: bool = False


def _safe_extra_fields(extra_fields: Optional[dict], *, for_sync_push: bool) -> dict:
    if not extra_fields:
        return {}
    blocked = {"type", "timestamp", "content"}
    if for_sync_push:
        blocked |= {"conversation_id", "sender_imei", "message_id", "is_from_me", "message_type", "imageBase64"}
    out: dict = {}
    for k, v in extra_fields.items():
        key = str(k or "").strip()
        if not key or key in blocked:
            continue
        out[key] = v
    return out


def _build_friend_inbox_message(
    sender_imei: str,
    content: str,
    msg_type: str,
    image_base64: Optional[str],
    sender_label: Optional[str] = None,
    extra_fields: Optional[dict] = None,
) -> dict:
    friend_message = {
        "type": "friend_message",
        "senderImei": sender_imei,
        "content": content,
        "message_type": msg_type,
        "timestamp": get_now_isoformat(),
        "sender": (sender_label or "好友"),
    }
    if image_base64 is not None and image_base64 != "":
        friend_message["imageBase64"] = image_base64
    safe_extra = _safe_extra_fields(extra_fields, for_sync_push=False)
    if safe_extra:
        friend_message.update(safe_extra)
    return friend_message


def _build_friend_sync_sender(
    imei: str,
    target_imei: str,
    content: str,
    msg_id: str,
    msg_type: str,
    image_base64: Optional[str],
    sender_label: Optional[str] = None,
    extra_fields: Optional[dict] = None,
) -> dict:
    push = {
        "type": "friend_sync_message",
        "conversation_id": f"friend_{target_imei}",
        "sender_imei": imei,
        "content": content,
        "message_id": msg_id,
        "message_type": msg_type,
        "timestamp": get_now_isoformat(),
        "is_from_me": True,
    }
    if image_base64:
        push["imageBase64"] = image_base64
    if sender_label:
        push["sender"] = sender_label
    safe_extra = _safe_extra_fields(extra_fields, for_sync_push=True)
    if safe_extra:
        push.update(safe_extra)
    return push


def _build_friend_sync_receiver(
    sender_imei: str,
    target_imei: str,
    content: str,
    msg_id: str,
    msg_type: str,
    image_base64: Optional[str],
    sender_label: Optional[str] = None,
    extra_fields: Optional[dict] = None,
) -> dict:
    push = {
        "type": "friend_sync_message",
        "conversation_id": f"friend_{sender_imei}",
        "sender_imei": sender_imei,
        "content": content,
        "message_id": msg_id,
        "message_type": msg_type,
        "timestamp": get_now_isoformat(),
        "is_from_me": False,
    }
    if image_base64:
        push["imageBase64"] = image_base64
    if sender_label:
        push["sender"] = sender_label
    safe_extra = _safe_extra_fields(extra_fields, for_sync_push=True)
    if safe_extra:
        push.update(safe_extra)
    return push


async def _push_sender_mirror(imei: str, push_sender: dict, sender_device: SenderDevice) -> None:
    """发送方镜像：PC 连接始终推；若消息源自 PC（HTTP 或 PC WS），再推手机，避免手机发消息时重复回显。"""
    if connection_manager.is_pc_online(imei):
        await connection_manager.send_to_pc(imei, push_sender)
        logger.info(f"好友消息已推送到发送方 PC: {imei[:8]}...")
    if sender_device == "pc" and connection_manager.is_user_online(imei):
        await connection_manager.send_to_user(imei, push_sender)
        logger.info(f"好友消息已推送到发送方手机: {imei[:8]}...")


async def dispatch_friend_message(
    imei: str,
    target_imei: str,
    content: str,
    message_type: str = "text",
    image_base64: Optional[str] = None,
    *,
    sender_device: SenderDevice = "mobile",
    client_message_id: Optional[str] = None,
    sender_label: Optional[str] = None,
    extra_fields: Optional[dict] = None,
) -> FriendDispatchResult:
    """
    处理好友消息发送与多端同步。
    sender_device: 消息从手机 WS 发出为 mobile；从 PC（WS 或 HTTP）发出为 pc。
    """
    if not target_imei:
        return FriendDispatchResult(ok=False, error_content="好友消息缺少目标IMEI")

    user_friends = friends_storage.get(imei, [])
    if target_imei not in user_friends:
        logger.warning(f"不是好友关系: {imei[:8]}... -> {target_imei[:8]}...")
        return FriendDispatchResult(ok=False, error_content="消息发送失败，对方不是您的好友")

    msg_type = message_type or "text"
    append_kw: dict = {"message_type": msg_type}
    if image_base64:
        append_kw["imageBase64"] = image_base64
    if client_message_id:
        append_kw["id"] = client_message_id

    friend_inbox = _build_friend_inbox_message(
        imei,
        content,
        msg_type,
        image_base64,
        sender_label=sender_label,
        extra_fields=extra_fields,
    )

    if not connection_manager.is_user_online(target_imei):
        logger.warning(f"目标好友不在线: {target_imei[:8]}...")
        msg_id = append_friend_msg(imei, target_imei, imei, content, **append_kw, **(extra_fields or {}))
        await message_service.save_offline_message(target_imei, friend_inbox)
        push_sender = _build_friend_sync_sender(
            imei,
            target_imei,
            content,
            msg_id,
            msg_type,
            image_base64,
            sender_label=sender_label,
            extra_fields=extra_fields,
        )
        await _push_sender_mirror(imei, push_sender, sender_device)
        if connection_manager.is_pc_online(target_imei):
            push_receiver = _build_friend_sync_receiver(
                imei,
                target_imei,
                content,
                msg_id,
                msg_type,
                image_base64,
                sender_label=sender_label,
                extra_fields=extra_fields,
            )
            await connection_manager.send_to_pc(target_imei, push_receiver)
            logger.info(f"好友消息已推送到接收方 PC（手机不在线）: {target_imei[:8]}...")
        return FriendDispatchResult(ok=True, message_id=msg_id, target_was_offline=True)

    msg_id = append_friend_msg(imei, target_imei, imei, content, **append_kw, **(extra_fields or {}))
    result = await connection_manager.send_to_user(target_imei, friend_inbox)

    if result:
        logger.info(f"好友消息转发成功: {imei[:8]}... -> {target_imei[:8]}...")
        if connection_manager.is_pc_online(target_imei):
            push_receiver = _build_friend_sync_receiver(
                imei,
                target_imei,
                content,
                msg_id,
                msg_type,
                image_base64,
                sender_label=sender_label,
                extra_fields=extra_fields,
            )
            await connection_manager.send_to_pc(target_imei, push_receiver)
            logger.info(f"好友消息已推送到接收方 PC: {target_imei[:8]}...")
    else:
        logger.warning(f"好友消息转发失败，保存为离线消息: {target_imei[:8]}...")
        await message_service.save_offline_message(target_imei, friend_inbox)

    push_sender = _build_friend_sync_sender(
        imei,
        target_imei,
        content,
        msg_id,
        msg_type,
        image_base64,
        sender_label=sender_label,
        extra_fields=extra_fields,
    )
    await _push_sender_mirror(imei, push_sender, sender_device)

    return FriendDispatchResult(ok=True, message_id=msg_id, target_was_offline=False)
