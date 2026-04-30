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

"""小助手广场"""
import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from schemas.models import PlazaAssistantAddRequest, PlazaAssistantSubmitRequest, PlazaAssistantUpdateRequest
from services.custom_assistant_store import (
    build_custom_assistant_id,
    get_custom_assistants,
    save_custom_assistants,
)
from services.plaza_assistant_store import (
    add_to_plaza,
    get_plaza_list,
    get_by_id as get_plaza_by_id,
    update_in_plaza,
    remove_from_plaza,
    toggle_plaza_like,
)
from api.plaza_public import enrich_plaza_item_for_client

logger = logging.getLogger(__name__)
router = APIRouter(tags=["plaza-assistants"])


@router.get("/api/plaza-assistants")
async def get_plaza_assistants_api(
    page: int = 1,
    limit: int = 50,
    imei: Optional[str] = None,
    sort: str = "latest",
):
    """分页获取广场小助手列表。可选 imei：与创建者一致时条目带 is_creator=true；sort=latest|hot（最热按点赞数）。"""
    try:
        sk = (sort or "latest").strip().lower()
        if sk not in ("latest", "hot"):
            sk = "latest"
        items, has_more = get_plaza_list(page=page, limit=limit, sort=sk)
        viewer = (imei or "").strip() or None
        public_items = [enrich_plaza_item_for_client(x, viewer_imei=viewer) for x in items]
        return JSONResponse({"success": True, "assistants": public_items, "has_more": has_more})
    except Exception as e:
        logger.error(f"获取广场小助手列表失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/plaza-assistants")
async def submit_plaza_assistant_api(request: PlazaAssistantSubmitRequest):
    """用户将小助手上架到广场"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        raw = request.assistant.model_dump(by_alias=True)
        item = add_to_plaza(imei, raw)
        return JSONResponse(
            {"success": True, "assistant": enrich_plaza_item_for_client(item, viewer_imei=imei)}
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"广场上架失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/plaza-assistants/{plaza_id}/add")
async def add_plaza_assistant_to_user_api(plaza_id: str, request: PlazaAssistantAddRequest):
    """将广场小助手添加到用户的自定义列表"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        item = get_plaza_by_id(plaza_id)
        if not item:
            raise HTTPException(status_code=404, detail="广场小助手不存在")
        creator_imei = str(item.get("creator_imei") or "").strip()
        source_assistant_id = str(item.get("assistant_id") or "").strip()
        stable_id = source_assistant_id or build_custom_assistant_id(creator_imei or imei)
        assistant_data = {
            "id": stable_id,
            "name": item.get("name", "小助手"),
            "intro": item.get("intro") or None,
            "baseUrl": item.get("baseUrl", ""),
            "capabilities": item.get("capabilities") or [],
            "avatar": item.get("avatar"),
            "multiSessionEnabled": item.get("multiSessionEnabled"),
        }
        if not assistant_data["baseUrl"]:
            raise HTTPException(status_code=400, detail="baseUrl 无效")
        existing = get_custom_assistants(imei)
        # 兼容重复添加：同 id（或同 baseUrl）只保留一条，不覆盖现有 id。
        exists = any(
            str(x.get("id") or "").strip() == assistant_data["id"]
            or str(x.get("baseUrl") or "").strip() == assistant_data["baseUrl"]
            for x in existing
        )
        if not exists:
            existing.append(assistant_data)
        save_custom_assistants(imei, existing)
        logger.info(f"广场添加: plaza_id={plaza_id[:8]}..., imei={imei[:8]}..., name={assistant_data['name']}")
        return JSONResponse({"success": True, "assistant": assistant_data})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"添加广场小助手到用户列表失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.patch("/api/plaza-assistants/{plaza_id}")
async def update_plaza_assistant_api(plaza_id: str, request: PlazaAssistantUpdateRequest):
    """创建者更新广场中的小助手资料（名称、头像、介绍、域名）"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        updates = {}
        if request.name is not None:
            updates["name"] = request.name
        if request.intro is not None:
            updates["intro"] = request.intro
        if request.baseUrl is not None:
            updates["baseUrl"] = request.baseUrl
        if request.avatar is not None:
            updates["avatar"] = request.avatar
        if not updates:
            raise HTTPException(status_code=400, detail="至少需要更新一个字段")
        item = update_in_plaza(plaza_id, imei, updates)
        if item is None:
            raise HTTPException(status_code=404, detail="广场小助手不存在或您不是创建者")
        return JSONResponse(
            {"success": True, "assistant": enrich_plaza_item_for_client(item, viewer_imei=imei)}
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新广场小助手失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/plaza-assistants/{plaza_id}/like")
async def toggle_plaza_like_api(plaza_id: str, request: PlazaAssistantAddRequest):
    """切换对广场小助手的点赞（再点取消）。"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        result = toggle_plaza_like(plaza_id, imei)
        if result is None:
            raise HTTPException(status_code=404, detail="广场小助手不存在")
        return JSONResponse(
            {
                "success": True,
                "likes_count": result["likes_count"],
                "liked_by_me": result["liked_by_me"],
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"广场点赞失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/plaza-assistants/{plaza_id}/remove")
async def remove_plaza_assistant_api(plaza_id: str, request: PlazaAssistantAddRequest):
    """创建者将已上架的小助手从广场下架"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        removed = remove_from_plaza(plaza_id, imei)
        if not removed:
            raise HTTPException(status_code=404, detail="广场小助手不存在或您不是创建者")
        return JSONResponse({"success": True})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"广场下架失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
