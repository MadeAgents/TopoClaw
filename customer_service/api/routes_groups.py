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

"""群组管理与小助手群消息"""
import asyncio
import logging

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

from core.deps import message_service
from core.time_utils import get_now_isoformat
from schemas.models import (
    AddGroupAssistantRequest,
    AddGroupMemberRequest,
    CreateGroupRequest,
    DissolveGroupRequest,
    QuitGroupRequest,
    RemoveGroupAssistantRequest,
    RemoveGroupMemberRequest,
    SendAssistantGroupMessageRequest,
    SendGroupMessageRequest,
    SaveGroupWorkflowRequest,
    SetGroupAssistantRequest,
    UpdateGroupConfigRequest,
    UpdateGroupAssistantConfigRequest,
)
from services.group_service import (
    ASSISTANT_BOT_ID,
    add_group_assistant,
    add_group_member,
    create_group,
    dissolve_group,
    get_group,
    get_user_groups,
    quit_group,
    remove_group_assistant,
    remove_group_member,
    set_assistant_enabled,
    update_group_assistant_config,
    update_group_assistant_muted,
    update_group_free_discovery,
    update_group_workflow_mode,
)
from services.group_workflow_service import get_group_workflow, save_group_workflow
from services.unified_message_store import append_group_msg

logger = logging.getLogger(__name__)
router = APIRouter(tags=["groups"])
NO_REPLY_MARKER = "###不回复###"


@router.post("/api/groups/create")
async def create_group_api(request: CreateGroupRequest):
    """创建群组"""
    try:
        group_id = create_group(
            request.imei,
            request.name,
            request.memberImeis,
            assistant_enabled=request.assistantEnabled,
        )
        group = get_group(group_id)
        return JSONResponse(
            {
                "success": True,
                "message": "创建群组成功",
                "groupId": group_id,
                "group": group,
            }
        )
    except Exception as e:
        logger.error(f"创建群组失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/add-member")
async def add_group_member_api(request: AddGroupMemberRequest):
    """添加群组成员（群成员可操作）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        success = add_group_member(request.groupId, request.imei, request.memberImei)
        if success:
            try:
                group_info = get_group(request.groupId)
                if group_info:
                    group_change_notification = {
                        "type": "group_changed",
                        "groupId": request.groupId,
                        "groupName": group_info.get("name", ""),
                        "action": "member_added",
                        "timestamp": get_now_isoformat(),
                        "message": f"您已被添加到群组「{group_info.get('name', '')}」",
                    }
                    await message_service.send_message_to_user(
                        request.memberImei, group_change_notification
                    )
                    logger.info(
                        f"已发送群组变更通知给用户: {request.memberImei[:8]}..., 群组: {request.groupId}"
                    )
            except Exception as notify_error:
                logger.warning(f"发送群组变更通知失败: {notify_error}")

            return JSONResponse({"success": True, "message": "添加成员成功"})
        return JSONResponse({"success": False, "message": "仅群成员可拉人，且不能重复添加"})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"添加群组成员失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/remove-member")
async def remove_group_member_api(request: RemoveGroupMemberRequest):
    """移除群组成员（群主可移除任意成员；非群主仅可移除自己拉入的成员）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        success = remove_group_member(request.groupId, request.imei, request.memberImei)
        if success:
            try:
                group_info = get_group(request.groupId)
                if group_info:
                    group_change_notification = {
                        "type": "group_changed",
                        "groupId": request.groupId,
                        "groupName": group_info.get("name", ""),
                        "action": "member_removed",
                        "timestamp": get_now_isoformat(),
                        "message": f"您已被移出群组「{group_info.get('name', '')}」",
                    }
                    await message_service.send_message_to_user(
                        request.memberImei, group_change_notification
                    )
                    logger.info(
                        f"已发送群组变更通知给用户: {request.memberImei[:8]}..., 群组: {request.groupId}"
                    )
            except Exception as notify_error:
                logger.warning(f"发送群组变更通知失败: {notify_error}")

            return JSONResponse({"success": True, "message": "移除成员成功"})
        return JSONResponse({"success": False, "message": "无权限移除该成员，或成员不存在（群主不可被移除）"})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"移除群组成员失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/set-assistant")
async def set_group_assistant_api(request: SetGroupAssistantRequest):
    """设置群组小助手（添加/移除自动执行小助手）。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        success = set_assistant_enabled(request.groupId, request.imei, request.enabled)
        if success:
            updated_group = get_group(request.groupId)
            return JSONResponse(
                {
                    "success": True,
                    "message": "添加小助手成功" if request.enabled else "移除小助手成功",
                    "group": updated_group,
                }
            )
        return JSONResponse(
            {"success": False, "message": "无权限操作该小助手，或该小助手状态未变化"}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"设置群组小助手失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/add-assistant")
async def add_group_assistant_api(request: AddGroupAssistantRequest):
    """添加群组小助手（群成员可操作）。可添加多个小助手。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        assistant_config = None
        if request.baseUrl or request.name or request.rolePrompt:
            assistant_config = {
                "baseUrl": request.baseUrl or "",
                "name": request.name or "小助手",
                "capabilities": request.capabilities or ["chat"],
                "intro": request.intro or "",
                "avatar": request.avatar or "",
                "multiSession": request.multiSession if request.multiSession is not None else True,
                "displayId": request.displayId or "",
                "rolePrompt": request.rolePrompt or "",
                # 默认归属操作者；自动拉好友数字分身时可显式指定 creatorImei
                "creator_imei": request.creatorImei or request.imei,
            }
        success = add_group_assistant(
            request.groupId, request.imei, request.assistantId, assistant_config=assistant_config
        )
        if success:
            updated_group = get_group(request.groupId)
            return JSONResponse(
                {"success": True, "message": "添加小助手成功", "group": updated_group}
            )
        return JSONResponse(
            {"success": False, "message": "仅群成员可添加小助手，或该小助手已在群组中"}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"添加群组小助手失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/remove-assistant")
async def remove_group_assistant_api(request: RemoveGroupAssistantRequest):
    """移除群组小助手（群主可移除任意助手；非群主仅可移除自己拉入的助手）。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        success = remove_group_assistant(request.groupId, request.imei, request.assistantId)
        if success:
            updated_group = get_group(request.groupId)
            return JSONResponse(
                {"success": True, "message": "移除小助手成功", "group": updated_group}
            )
        return JSONResponse(
            {"success": False, "message": "无权限移除该小助手，或该小助手不在群组中"}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"移除群组小助手失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/update-assistant-config")
async def update_group_assistant_config_api(request: UpdateGroupAssistantConfigRequest):
    """更新群组小助手配置（群主可操作；非群主仅可禁言自己的助手）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        config = {
            k: v
            for k, v in {
                "capabilities": request.capabilities,
                "baseUrl": request.baseUrl,
                "name": request.name,
                "intro": request.intro,
                "avatar": request.avatar,
                "multiSession": request.multiSession,
                "rolePrompt": request.rolePrompt,
                "assistantMuted": request.assistantMuted,
            }.items()
            if v is not None
        }
        success = update_group_assistant_config(
            request.groupId, request.imei, request.assistantId, assistant_config=config
        )
        if success:
            updated_group = get_group(request.groupId)
            return JSONResponse(
                {"success": True, "message": "更新配置成功", "group": updated_group}
            )
        return JSONResponse(
            {
                "success": False,
                "message": "无权限更新配置，或该小助手不在群组中",
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新群组小助手配置失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/update-config")
async def update_group_config_api(request: UpdateGroupConfigRequest):
    """更新群组配置（仅群主可操作）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        if (
            request.workflowMode is None
            and request.freeDiscovery is None
            and request.assistantMuted is None
        ):
            return JSONResponse({"success": False, "message": "未提供可更新的配置项"})
        success = True
        if request.workflowMode is not None:
            success = success and update_group_workflow_mode(
                request.groupId, request.imei, bool(request.workflowMode)
            )
        if request.freeDiscovery is not None:
            success = success and update_group_free_discovery(
                request.groupId, request.imei, bool(request.freeDiscovery)
            )
        if request.assistantMuted is not None:
            success = success and update_group_assistant_muted(
                request.groupId, request.imei, bool(request.assistantMuted)
            )
        if success:
            updated_group = get_group(request.groupId)
            return JSONResponse(
                {
                    "success": True,
                    "message": "更新配置成功",
                    "group": updated_group,
                }
            )
        return JSONResponse(
            {
                "success": False,
                "message": "只有群主可以更新群组配置",
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新群组配置失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/groups/list")
async def get_user_groups_api(imei: str):
    """获取用户所在的所有群组"""
    try:
        groups = get_user_groups(imei)
        return JSONResponse({"success": True, "groups": groups})
    except Exception as e:
        logger.error(f"获取群组列表失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/quit")
async def quit_group_api(request: QuitGroupRequest):
    """成员退出群组（群主不可退出，需解散群组）。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        success = quit_group(request.groupId, request.imei)
        if success:
            try:
                group_info = get_group(request.groupId)
                if group_info:
                    for member_imei in group_info.get("members", []):
                        if member_imei == request.imei:
                            continue
                        group_change_notification = {
                            "type": "group_changed",
                            "groupId": request.groupId,
                            "groupName": group_info.get("name", ""),
                            "action": "member_left",
                            "timestamp": get_now_isoformat(),
                            "message": f"成员 {request.imei[:8]}... 已退出群组「{group_info.get('name', '')}」",
                        }
                        await message_service.send_message_to_user(member_imei, group_change_notification)
            except Exception as notify_error:
                logger.warning(f"发送群组退出通知失败: {notify_error}")
            return JSONResponse({"success": True, "message": "已退出群组"})
        return JSONResponse({"success": False, "message": "退出失败：您可能不是群成员，或您是群主（请解散群组）"})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"退出群组失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/dissolve")
async def dissolve_group_api(request: DissolveGroupRequest):
    """群主解散群组。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        if group.get("creator_imei") != request.imei:
            raise HTTPException(status_code=403, detail="只有群主可以解散群组")
        members = [m for m in group.get("members", []) if m != request.imei]
        group_name = group.get("name", "")
        success = dissolve_group(request.groupId, request.imei)
        if success:
            try:
                for member_imei in members:
                    group_change_notification = {
                        "type": "group_changed",
                        "groupId": request.groupId,
                        "groupName": group_name,
                        "action": "group_dissolved",
                        "timestamp": get_now_isoformat(),
                        "message": f"群组「{group_name}」已被群主解散",
                    }
                    await message_service.send_message_to_user(member_imei, group_change_notification)
            except Exception as notify_error:
                logger.warning(f"发送群组解散通知失败: {notify_error}")
            return JSONResponse({"success": True, "message": "群组已解散"})
        return JSONResponse({"success": False, "message": "解散失败"})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"解散群组失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/send-assistant-message")
async def send_assistant_group_message(request: SendAssistantGroupMessageRequest, raw_request: Request):
    """以小助手身份发送群组消息（用于客户端直接调用TopoMobileAgent后返回结果）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")

        if request.imei not in group["members"]:
            raise HTTPException(status_code=403, detail="您不是该群组成员")

        is_system_message = request.sender == "系统"
        if bool(group.get("assistant_muted", False)) and not is_system_message:
            return JSONResponse({"success": False, "message": "群组已开启助手禁言"})

        normalized_content = str(request.content or "").strip()
        if NO_REPLY_MARKER in normalized_content:
            logger.info(
                "助手消息包含不回复标记，跳过入群广播: group=%s, sender=%s",
                request.groupId,
                request.sender,
            )
            return JSONResponse({"success": True, "message": "检测到不回复标记，已跳过发送"})

        assistant_id = str(request.assistantId or "").strip()
        assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
        valid_assistant_id = assistant_id if assistant_id in assistants else ""
        assistant_message = {
            "type": "group_message",
            "groupId": request.groupId,
            "senderImei": (valid_assistant_id or ASSISTANT_BOT_ID) if not is_system_message else "system",
            "content": normalized_content,
            "timestamp": get_now_isoformat(),
            "sender": request.sender,
            "is_assistant_reply": not is_system_message,
            **({"assistant_id": valid_assistant_id} if valid_assistant_id else {}),
        }

        members = group["members"]
        for member_imei in members:
            if member_imei != ASSISTANT_BOT_ID:
                await message_service.send_message_to_user(member_imei, assistant_message)

        message_type = "系统消息" if is_system_message else "小助手消息"
        logger.info(
            f"{message_type}已发送到群组: {request.groupId}, 发送者: {request.sender}, 内容: {normalized_content[:50]}..."
        )

        # 自由发言开启时：群内新增助手消息也继续分发给其他助手（可多轮）。
        if bool(group.get("free_discovery", False)) and not bool(group.get("assistant_muted", False)) and not is_system_message:
            try:
                from api.websocket_customer import _notify_group_assistants

                asyncio.create_task(
                    _notify_group_assistants(
                        raw_request.app,
                        group=group,
                        group_id=request.groupId,
                        sender_imei=request.imei,
                        content=normalized_content,
                        msg_type="text",
                        image_base64=None,
                        target_assistant=None,
                        sync_only=False,
                        exclude_assistants={valid_assistant_id} if valid_assistant_id else set(),
                    )
                )
            except Exception as sync_error:
                logger.warning(f"自由发言分发助手消息失败: {sync_error}")

        return JSONResponse({"success": True, "message": "小助手消息已发送"})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"发送小助手群组消息失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/send-message")
async def send_group_message(request: SendGroupMessageRequest, raw_request: Request):
    """发送群消息（与 WebSocket group_message 同通道语义，支持数字分身标记）。"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        if request.imei not in group["members"]:
            raise HTTPException(status_code=403, detail="您不是该群组成员")

        normalized_content = str(request.content or "")
        msg_type = str(request.message_type or "text").strip() or "text"
        image_base64 = (request.imageBase64 or "").strip() or None
        if image_base64 and msg_type == "text":
            msg_type = "image"

        sender_label = (request.senderLabel or request.sender or "群成员").strip() or "群成员"
        clone_origin = (request.cloneOrigin or "").strip()
        is_clone_reply = bool(request.isCloneReply)
        clone_owner_imei = (request.cloneOwnerImei or "").strip()
        if is_clone_reply and not clone_owner_imei:
            clone_owner_imei = request.imei
        skip_server_assistant_dispatch = bool(request.skipServerAssistantDispatch)

        from api.websocket_customer import (
            _extract_explicitly_mentioned_assistant,
            _extract_mentioned_assistant,
            _extract_mentioned_member_imei,
            _find_member_topoclaw_assistant_in_group,
            _notify_group_assistants,
            is_assistant_mentioned,
        )

        assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
        explicitly_mentioned = _extract_explicitly_mentioned_assistant(normalized_content, assistants, group) if assistants else None
        mentioned_member_imei = None
        member_clone_assistant = None
        if not explicitly_mentioned:
            mentioned_member_imei = _extract_mentioned_member_imei(normalized_content, group)
            if mentioned_member_imei:
                member_clone_assistant = _find_member_topoclaw_assistant_in_group(group, mentioned_member_imei)
                if member_clone_assistant:
                    explicitly_mentioned = member_clone_assistant
        reply_target_assistant = _extract_mentioned_assistant(normalized_content, assistants, group) if assistants else None
        assistant_mentioned = is_assistant_mentioned(normalized_content) or (explicitly_mentioned is not None)

        group_message = {
            "type": "group_message",
            "groupId": request.groupId,
            "senderImei": request.imei,
            "content": normalized_content,
            "message_type": msg_type,
            "timestamp": get_now_isoformat(),
            "sender": sender_label,
            "assistant_mentioned": assistant_mentioned,
        }
        append_kwargs: dict = {"message_type": msg_type, "sender": sender_label}
        if image_base64:
            group_message["imageBase64"] = image_base64
            append_kwargs["imageBase64"] = image_base64
        if is_clone_reply:
            group_message["is_clone_reply"] = True
            append_kwargs["is_clone_reply"] = True
        if clone_owner_imei:
            group_message["clone_owner_imei"] = clone_owner_imei
            append_kwargs["clone_owner_imei"] = clone_owner_imei
        if clone_origin:
            group_message["clone_origin"] = clone_origin
            append_kwargs["clone_origin"] = clone_origin
        if sender_label:
            group_message["sender_label"] = sender_label
            append_kwargs["sender_label"] = sender_label

        msg_id = append_group_msg(
            request.groupId,
            request.imei,
            normalized_content,
            **append_kwargs,
        )
        group_message["message_id"] = msg_id

        for member_imei in group["members"]:
            if member_imei != ASSISTANT_BOT_ID:
                await message_service.send_message_to_user(member_imei, group_message)

        force_server_dispatch = bool(group.get("free_discovery", False)) and not bool(group.get("assistant_muted", False))
        if skip_server_assistant_dispatch and not force_server_dispatch:
            logger.info(
                "group http send 跳过服务端助手分发: group=%s, sender=%s..., explicit=%s",
                request.groupId,
                request.imei[:8],
                explicitly_mentioned,
            )
        else:
            if skip_server_assistant_dispatch and force_server_dispatch:
                logger.info(
                    "group http send 检测到自由发言已开启，忽略 skipServerAssistantDispatch 并继续服务端分发: group=%s, sender=%s...",
                    request.groupId,
                    request.imei[:8],
                )
            asyncio.create_task(
                _notify_group_assistants(
                    raw_request.app,
                    group=group,
                    group_id=request.groupId,
                    sender_imei=request.imei,
                    content=normalized_content,
                    msg_type=msg_type,
                    image_base64=image_base64,
                    target_assistant=explicitly_mentioned,
                )
            )
        if assistant_mentioned:
            logger.info(
                "group http send 检测到@助手(%s)，已交给统一助手广播链路: group=%s",
                reply_target_assistant or "默认",
                request.groupId,
            )

        return JSONResponse(
            {
                "success": True,
                "message": "群消息已发送",
                "message_id": msg_id,
                "assistant_mentioned": assistant_mentioned,
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"发送群消息失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/groups/{group_id}")
async def get_group_api(group_id: str):
    """获取群组详情"""
    try:
        group = get_group(group_id)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        return JSONResponse({"success": True, "group": group})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取群组详情失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/groups/{group_id}/workflow")
async def get_group_workflow_api(group_id: str, imei: str):
    """获取群组发布版编排（群成员可读）"""
    try:
        group = get_group(group_id)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        operator = str(imei or "").strip()
        if operator not in group.get("members", []):
            raise HTTPException(status_code=403, detail="您不是该群组成员")
        current = get_group_workflow(group_id)
        if not current:
            return JSONResponse(
                {"success": True, "workflow": None, "version": 0, "updatedAt": None, "updatedBy": None}
            )
        return JSONResponse(
            {
                "success": True,
                "workflow": current.get("workflow"),
                "version": int(current.get("version") or 0),
                "updatedAt": current.get("updated_at"),
                "updatedBy": current.get("updated_by"),
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error("获取群组编排失败: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/groups/workflow/save")
async def save_group_workflow_api(request: SaveGroupWorkflowRequest):
    """保存群组发布版编排（群成员可写，带乐观锁版本冲突检测）"""
    try:
        group = get_group(request.groupId)
        if not group:
            raise HTTPException(status_code=404, detail="群组不存在")
        operator = str(request.imei or "").strip()
        if operator not in group.get("members", []):
            raise HTTPException(status_code=403, detail="您不是该群组成员")

        result = save_group_workflow(
            group_id=request.groupId,
            imei=operator,
            workflow=request.workflow,
            expected_version=request.expectedVersion,
        )
        if not result.get("success") and result.get("conflict"):
            return JSONResponse(status_code=409, content=result)
        if not result.get("success"):
            return JSONResponse({"success": False, "message": result.get("message") or "保存失败"})

        notify = {
            "type": "group_workflow_updated",
            "groupId": request.groupId,
            "version": result.get("version"),
            "updatedBy": operator,
            "updatedAt": result.get("updatedAt"),
        }
        for member_imei in group.get("members", []):
            if member_imei != ASSISTANT_BOT_ID:
                await message_service.send_message_to_user(member_imei, notify)
        return JSONResponse(result)
    except HTTPException:
        raise
    except Exception as e:
        logger.error("保存群组编排失败: %s", e)
        raise HTTPException(status_code=500, detail=str(e))
