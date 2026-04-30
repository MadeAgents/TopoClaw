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

"""好友关系与好友消息"""
import asyncio
import logging
import time

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

from core.deps import message_service
from core.time_utils import get_now_isoformat
from schemas.models import (
    AcceptFriendRequest,
    AddFriendRequest,
    RemoveFriendRequest,
    SendFriendMessageRequest,
)
from services.friend_dispatch import dispatch_friend_message
from services.digital_clone_reply import maybe_auto_reply_friend_message
from storage.friends import friends_storage, save_friends_storage
from storage.profiles import profiles_storage

logger = logging.getLogger(__name__)
router = APIRouter(tags=["friends"])


@router.post("/api/friends/add")
async def add_friend(request: AddFriendRequest):
    """添加好友（简化版：直接添加，无需确认）"""
    try:
        imei = request.imei
        if not imei:
            raise HTTPException(status_code=400, detail="缺少用户IMEI")

        friend_imei = request.friendImei
        if imei == friend_imei:
            return JSONResponse({"success": False, "message": "不能添加自己为好友"})

        if imei in friends_storage and friend_imei in friends_storage[imei]:
            logger.info(f"已经是好友关系: {imei[:8]}... <-> {friend_imei[:8]}...")
            return JSONResponse({"success": True, "message": "已经是好友"})

        if imei not in friends_storage:
            friends_storage[imei] = []
        if friend_imei not in friends_storage[imei]:
            friends_storage[imei].append(friend_imei)
            logger.info(f"添加好友关系: {imei[:8]}... -> {friend_imei[:8]}...")
        else:
            logger.info(f"好友关系已存在: {imei[:8]}... -> {friend_imei[:8]}...")

        if friend_imei not in friends_storage:
            friends_storage[friend_imei] = []
        if imei not in friends_storage[friend_imei]:
            friends_storage[friend_imei].append(imei)
            logger.info(f"添加反向好友关系: {friend_imei[:8]}... -> {imei[:8]}...")
        else:
            logger.info(f"反向好友关系已存在: {friend_imei[:8]}... -> {imei[:8]}...")

        save_friends_storage()

        logger.info(f"添加好友成功: {imei[:8]}... <-> {friend_imei[:8]}...")
        logger.info(
            f"当前好友存储状态: {imei[:8]}... 的好友列表: {[f[:8] + '...' for f in friends_storage.get(imei, [])]}"
        )
        logger.info(
            f"当前好友存储状态: {friend_imei[:8]}... 的好友列表: {[f[:8] + '...' for f in friends_storage.get(friend_imei, [])]}"
        )

        friend_request_message = {
            "type": "friend_request",
            "senderImei": imei,
            "content": f"用户 {imei[:8]}... 添加您为好友",
            "timestamp": get_now_isoformat(),
            "sender": "系统",
        }

        await message_service.send_message_to_user(friend_imei, friend_request_message)

        return JSONResponse({"success": True, "message": "添加好友成功"})
    except Exception as e:
        logger.error(f"添加好友失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/friends/accept")
async def accept_friend(request: AcceptFriendRequest):
    """接受好友请求（简化版：与add相同）"""
    return await add_friend(AddFriendRequest(friendImei=request.friendImei, imei=request.imei))


@router.get("/api/friends/list")
async def get_friends(imei: str):
    """获取好友列表"""
    try:
        if not imei:
            raise HTTPException(status_code=400, detail="缺少用户IMEI")

        friend_imeis = friends_storage.get(imei, [])
        logger.info(f"获取好友列表: {imei[:8]}... 的好友列表: {[f[:8] + '...' for f in friend_imeis]}")
        logger.info(f"当前所有好友存储: {list(friends_storage.keys())}")
        now_ms = int(time.time() * 1000)
        friends = []
        for fi in friend_imeis:
            profile = profiles_storage.get(fi, {})
            nickname = profile.get("name") if profile else None
            avatar = profile.get("avatar") if profile else None
            friends.append(
                {
                    "imei": fi,
                    "nickname": nickname,
                    "avatar": avatar,
                    "status": "accepted",
                    "addedAt": now_ms,
                }
            )

        return JSONResponse({"success": True, "friends": friends})
    except Exception as e:
        logger.error(f"获取好友列表失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/friends/send-message")
async def send_friend_message(request: SendFriendMessageRequest, http_request: Request):
    """发送好友消息（兼容旧版 HTTP；与 WebSocket friend_message 共用 dispatch_friend_message）"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="缺少用户IMEI")

        target_imei = request.targetImei.strip()
        if not target_imei:
            raise HTTPException(status_code=400, detail="缺少目标好友IMEI")

        content = request.content or ""
        msg_type = request.message_type or "text"
        image_b64 = (request.imageBase64 or "").strip() or None
        if image_b64 and msg_type == "text":
            msg_type = "image"
        sender_label = (request.senderLabel or "").strip() or None
        clone_owner_imei = (request.cloneOwnerImei or "").strip() or None
        clone_origin = (request.cloneOrigin or "").strip() or None
        extra_fields: dict = {}
        if request.isCloneReply is True:
            extra_fields["is_clone_reply"] = True
        if clone_owner_imei:
            extra_fields["clone_owner_imei"] = clone_owner_imei
        if clone_origin:
            extra_fields["clone_origin"] = clone_origin
        if sender_label:
            extra_fields["sender_label"] = sender_label

        result = await dispatch_friend_message(
            imei,
            target_imei,
            content,
            msg_type,
            image_b64,
            sender_device="pc",
            client_message_id=None,
            sender_label=sender_label,
            extra_fields=(extra_fields or None),
        )
        if not result.ok:
            return JSONResponse(
                {"success": False, "message": result.error_content or "发送失败"},
                status_code=400,
            )
        asyncio.create_task(
            maybe_auto_reply_friend_message(
                http_request.app,
                sender_imei=imei,
                target_imei=target_imei,
                content=content,
                msg_type=msg_type,
                image_base64=image_b64,
            )
        )

        return JSONResponse(
            {
                "success": True,
                "message": "消息已发送",
                "message_id": result.message_id,
                "target_online": not result.target_was_offline,
            }
        )
    except Exception as e:
        logger.error(f"发送好友消息失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/friends/remove")
async def remove_friend(request: RemoveFriendRequest):
    """删除好友"""
    try:
        imei = request.imei
        friend_imei = request.friendImei

        if not imei:
            raise HTTPException(status_code=400, detail="缺少用户IMEI")
        if not friend_imei:
            raise HTTPException(status_code=400, detail="缺少好友IMEI")

        if imei in friends_storage:
            if friend_imei in friends_storage[imei]:
                friends_storage[imei].remove(friend_imei)
                logger.info(f"删除好友成功: {imei[:8]}... 删除了 {friend_imei[:8]}...")
            else:
                logger.warning(f"好友不存在: {imei[:8]}... 的好友列表中不存在 {friend_imei[:8]}...")
        else:
            logger.warning(f"用户不存在: {imei[:8]}... 不在好友存储中")

        if friend_imei in friends_storage:
            if imei in friends_storage[friend_imei]:
                friends_storage[friend_imei].remove(imei)
                logger.info(f"双向删除好友成功: {friend_imei[:8]}... 的好友列表中删除了 {imei[:8]}...")

        save_friends_storage()

        return JSONResponse({"success": True, "message": "删除好友成功"})
    except Exception as e:
        logger.error(f"删除好友失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))
