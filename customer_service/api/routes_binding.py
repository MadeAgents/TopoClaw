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

"""设备扫码绑定"""
import logging
from datetime import datetime

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from core.time_utils import TZ_UTC_PLUS_8
from schemas.models import BindingSubmitRequest
from storage.binding import BINDING_TTL_SECONDS, clean_expired_bindings, get_binding_store

logger = logging.getLogger(__name__)
router = APIRouter(tags=["binding"])


@router.post("/api/binding/{token}")
async def submit_binding(token: str, request: BindingSubmitRequest):
    """手机扫码后上报 IMEI，完成绑定"""
    try:
        store = get_binding_store()
        clean_expired_bindings()
        expires_at = datetime.now(TZ_UTC_PLUS_8).timestamp() + BINDING_TTL_SECONDS
        store[token] = {"imei": request.imei.strip(), "expires_at": expires_at}
        logger.info(f"绑定令牌 {token[:8]}... 已接收 IMEI: {request.imei[:8]}...")
        return JSONResponse({"success": True, "message": "绑定成功"})
    except Exception as e:
        logger.error(f"绑定提交失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/binding/{token}")
async def get_binding(token: str):
    """PC 轮询获取绑定的 IMEI"""
    try:
        store = get_binding_store()
        clean_expired_bindings()
        entry = store.get(token)
        if not entry:
            raise HTTPException(status_code=404, detail="未找到绑定或已过期")
        expires_at = entry.get("expires_at", 0)
        if expires_at < datetime.now(TZ_UTC_PLUS_8).timestamp():
            del store[token]
            raise HTTPException(status_code=404, detail="绑定已过期")
        return JSONResponse({"imei": entry["imei"]})
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取绑定失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))
