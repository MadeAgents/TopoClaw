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

"""自定义小助手列表与聊天记录同步"""
import logging
import uuid

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from schemas.models import AdaptAssistantIdsRequest, CustomAssistantChatAppendRequest, CustomAssistantsSyncRequest
from services.custom_assistant_store import (
    adapt_user_assistant_ids,
    get_custom_assistants,
    save_custom_assistants,
)
from services.group_service import adapt_user_group_assistant_ids
from services.unified_message_store import append_custom_assistant_msg

logger = logging.getLogger(__name__)
router = APIRouter(tags=["custom-assistants"])


@router.get("/api/custom-assistants")
async def get_custom_assistants_api(imei: str):
    """获取用户的自定义小助手列表（端云同步拉取）"""
    try:
        assistants = get_custom_assistants(imei)
        return JSONResponse({"success": True, "assistants": assistants})
    except Exception as e:
        logger.error(f"获取自定义小助手列表失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/custom-assistants")
async def sync_custom_assistants(request: CustomAssistantsSyncRequest):
    """同步用户的自定义小助手列表（端云同步保存）"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        client_type = str(request.client_type or "").strip().lower()
        if client_type != "pc":
            raise HTTPException(status_code=403, detail="仅 PC 客户端允许同步自定义小助手")
        assistants = [a.model_dump(by_alias=True) for a in request.assistants]
        save_custom_assistants(imei, assistants)
        return JSONResponse({"success": True})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"同步自定义小助手列表失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/custom-assistants/adapt-id")
async def adapt_custom_assistant_ids(request: AdaptAssistantIdsRequest):
    """一键适配指定用户的「新助手ID」体系（displayId 优先）。"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        assistant_stats = adapt_user_assistant_ids(imei)
        group_stats = adapt_user_group_assistant_ids(imei)
        return JSONResponse(
            {
                "success": True,
                "message": "适配完成",
                "assistant_stats": assistant_stats,
                "group_stats": group_stats,
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"适配新助手ID失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/custom-assistant-chat/append")
async def append_custom_assistant_chat(request: CustomAssistantChatAppendRequest):
    """追加自定义小助手聊天消息，用于手机/PC 跨设备同步"""
    try:
        imei = request.imei.strip()
        assistant_id = request.assistant_id.strip()
        if not imei or not assistant_id:
            raise HTTPException(status_code=400, detail="imei 和 assistant_id 必填")
        msg_id = append_custom_assistant_msg(
            imei=imei,
            assistant_id=assistant_id,
            user_content=request.user_content or "",
            assistant_content=request.assistant_content or "",
            assistant_name=request.assistant_name or "小助手",
            file_base64=request.file_base64,
            file_name=request.file_name,
            session_id=request.session_id,
        )
        sid = (request.session_id or "").strip()
        logger.info(
            "自定义小助手消息已同步: imei=%s..., assistant=%s..., session=%s",
            imei[:8],
            assistant_id[:16],
            (sid[:12] + "...") if len(sid) > 12 else (sid or "-"),
        )
        return JSONResponse({"success": True, "message_id": msg_id})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"追加自定义小助手消息失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
