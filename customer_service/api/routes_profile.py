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

"""用户资料"""
import logging
import time
from typing import Optional

from fastapi import APIRouter, Form, HTTPException
from fastapi.responses import JSONResponse

from core.deps import connection_manager
from storage.profiles import profiles_storage, save_profiles_storage

logger = logging.getLogger(__name__)
router = APIRouter(tags=["profile"])


@router.get("/api/profile/{imei}")
async def get_profile(imei: str):
    """获取用户资料"""
    try:
        profile = profiles_storage.get(imei)
        if not profile:
            return JSONResponse(
                {"success": False, "message": "用户资料不存在", "profile": None}
            )

        return JSONResponse({"success": True, "profile": profile})
    except Exception as e:
        logger.error(f"获取用户资料失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/profile/{imei}")
async def update_profile(
    imei: str,
    name: Optional[str] = Form(None),
    signature: Optional[str] = Form(None),
    gender: Optional[str] = Form(None),
    address: Optional[str] = Form(None),
    phone: Optional[str] = Form(None),
    birthday: Optional[str] = Form(None),
    preferences: Optional[str] = Form(None),
    avatar: Optional[str] = Form(None),
):
    """创建或更新用户资料"""
    try:
        current_time = int(time.time() * 1000)

        profile = profiles_storage.get(imei, {})

        if name is not None:
            profile["name"] = name if name else None
        if signature is not None:
            profile["signature"] = signature if signature else None
        if gender is not None:
            profile["gender"] = gender if gender else None
        if address is not None:
            profile["address"] = address if address else None
        if phone is not None:
            profile["phone"] = phone if phone else None
        if birthday is not None:
            profile["birthday"] = birthday if birthday else None
        if preferences is not None:
            profile["preferences"] = preferences if preferences else None
        if avatar is not None:
            profile["avatar"] = avatar if avatar else None

        profile["imei"] = imei
        profile["updatedAt"] = current_time

        profiles_storage[imei] = profile
        save_profiles_storage()
        connection_manager.register_default_topoclaw_user_slot(imei)

        logger.info(f"更新用户资料成功: {imei[:8]}...")

        return JSONResponse({"success": True, "message": "保存成功", "profile": profile})
    except Exception as e:
        logger.error(f"更新用户资料失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/api/profile/{imei}")
async def delete_profile(imei: str):
    """删除用户资料"""
    try:
        if imei in profiles_storage:
            del profiles_storage[imei]
            save_profiles_storage()
            logger.info(f"删除用户资料成功: {imei[:8]}...")
            return JSONResponse({"success": True, "message": "删除成功"})
        return JSONResponse({"success": False, "message": "用户资料不存在"})
    except Exception as e:
        logger.error(f"删除用户资料失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))
