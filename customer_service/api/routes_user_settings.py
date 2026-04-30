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

"""用户设置 API - 存储用户级别的配置选项"""
import logging
from typing import Any, Dict

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from services.user_settings_store import (
    get_user_settings,
    update_user_settings,
    set_user_setting,
    DEFAULT_SETTINGS,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["user-settings"])


class UserSettingsResponse(BaseModel):
    success: bool
    settings: Dict[str, Any]


class UpdateUserSettingsRequest(BaseModel):
    imei: str = Field(..., description="用户 IMEI")
    settings: Dict[str, Any] = Field(..., description="要更新的设置项")


class UpdateUserSettingRequest(BaseModel):
    imei: str = Field(..., description="用户 IMEI")
    key: str = Field(..., description="设置项键名")
    value: Any = Field(..., description="设置项值")


@router.get("/api/user-settings")
async def get_user_settings_api(imei: str):
    """获取用户的所有设置（带默认值）"""
    try:
        if not imei or not imei.strip():
            raise HTTPException(status_code=400, detail="imei 必填")
        settings = get_user_settings(imei)
        return JSONResponse({"success": True, "settings": settings})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取用户设置失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/user-settings")
async def update_user_settings_api(request: UpdateUserSettingsRequest):
    """批量更新用户设置"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        if not request.settings:
            raise HTTPException(status_code=400, detail="settings 不能为空")
        
        # 只保留已知的设置项
        allowed_keys = set(DEFAULT_SETTINGS.keys())
        filtered_settings = {k: v for k, v in request.settings.items() if k in allowed_keys}
        
        update_user_settings(imei, filtered_settings)
        logger.info(f"更新用户设置: imei={imei[:8]}..., settings={filtered_settings}")
        return JSONResponse({"success": True, "settings": get_user_settings(imei)})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新用户设置失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/user-settings/{key}")
async def set_user_setting_api(key: str, request: UpdateUserSettingRequest):
    """设置单个用户设置项"""
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        if not key:
            raise HTTPException(status_code=400, detail="key 不能为空")
        
        # 检查是否是已知设置项
        if key not in DEFAULT_SETTINGS:
            raise HTTPException(status_code=400, detail=f"未知的设置项: {key}")
        
        set_user_setting(imei, key, request.value)
        logger.info(f"设置用户配置: imei={imei[:8]}..., key={key}, value={request.value}")
        return JSONResponse({"success": True, "settings": get_user_settings(imei)})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"设置用户配置失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/user-settings/{key}")
async def get_user_setting_api(imei: str, key: str):
    """获取单个用户设置项"""
    try:
        if not imei or not imei.strip():
            raise HTTPException(status_code=400, detail="imei 必填")
        if not key:
            raise HTTPException(status_code=400, detail="key 不能为空")
        
        settings = get_user_settings(imei)
        if key not in settings:
            raise HTTPException(status_code=404, detail=f"设置项不存在: {key}")
        
        return JSONResponse({"success": True, "key": key, "value": settings[key]})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取用户设置失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
