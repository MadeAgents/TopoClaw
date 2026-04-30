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

"""技能广场"""

import logging
from typing import Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from schemas.models import PlazaSkillSubmitRequest
from services.plaza_skill_store import add_to_plaza, list_plaza_skills
from storage.profiles import profiles_storage

logger = logging.getLogger(__name__)
router = APIRouter(tags=["plaza-skills"])


def _enrich_item(item: dict, viewer_imei: Optional[str] = None) -> dict:
    out = dict(item)
    raw = str(item.get("creator_imei") or "").strip()
    profile = profiles_storage.get(raw, {}) if raw else {}
    nick = str(profile.get("name") or "").strip()
    avatar = profile.get("avatar")
    prefix = raw[:16] if len(raw) > 16 else raw
    out["creator_imei"] = f"{prefix} · {nick}" if nick and prefix else (nick or prefix or raw)
    if avatar:
        out["creator_avatar"] = avatar
    else:
        out.pop("creator_avatar", None)
    out["is_creator"] = bool(viewer_imei and raw and raw == viewer_imei.strip())
    return out


@router.get("/api/plaza-skills")
async def get_plaza_skills_api(
    page: int = 1,
    limit: int = 50,
    imei: Optional[str] = None,
    query: Optional[str] = None,
):
    try:
        items, has_more = list_plaza_skills(page=page, limit=limit, query=(query or ""))
        viewer = (imei or "").strip() or None
        result = [_enrich_item(x, viewer_imei=viewer) for x in items]
        return JSONResponse({"success": True, "skills": result, "has_more": has_more})
    except Exception as e:
        logger.error("获取技能广场列表失败: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/plaza-skills")
async def submit_plaza_skill_api(request: PlazaSkillSubmitRequest):
    try:
        imei = request.imei.strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        raw = request.skill.model_dump(by_alias=True)
        item = add_to_plaza(imei, raw)
        return JSONResponse({"success": True, "skill": _enrich_item(item, viewer_imei=imei)})
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        logger.error("技能广场上架失败: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
