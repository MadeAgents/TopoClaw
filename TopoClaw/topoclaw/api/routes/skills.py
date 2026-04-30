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

"""Skills routes."""

from __future__ import annotations

from fastapi import APIRouter, Body, HTTPException, Request

from topoclaw.service.skills_service import SkillsService

router = APIRouter(prefix="/skills", tags=["skills"])


def _skills_service(request: Request) -> SkillsService:
    return request.app.state.skills_service


@router.get("")
async def list_skills(request: Request):
    return await _skills_service(request).list_skills()


@router.get("/{name}")
async def get_skill(name: str, request: Request):
    result = await _skills_service(request).get_skill(name)
    if "error" in result:
        raise HTTPException(status_code=404, detail=result["error"])
    return result

@router.post("/update")
async def update_skills(request: Request):
    # 去clawhub上，找本地已有的skills，是否存在更新，若更新则修改；
    # ps：仅针对自己下载的skills
    result = await _skills_service(request).update_skills()
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@router.post("/download")
async def download_skill(
    request: Request,
    source_url: str = Body(..., embed=True),
    skill_name: str | None = Body(None, embed=True),
    overwrite: bool = Body(False, embed=True),
):
    result = await _skills_service(request).download_skill_from_clawhub(
        source_url=source_url,
        skill_name=skill_name,
        overwrite=overwrite,
    )
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@router.post("/remove")
async def remove_skill(request: Request, skill_name: str = Body(..., embed=True)):
    result = await _skills_service(request).remove_skill(skill_name)
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@router.get("/{name}/package")
async def export_skill_package(name: str, request: Request):
    result = await _skills_service(request).export_skill_package(name)
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@router.post("/import-package")
async def import_skill_package(
    request: Request,
    package_base64: str = Body(..., embed=True),
    prefer_name: str | None = Body(None, embed=True),
    overwrite: bool = Body(False, embed=True),
):
    result = await _skills_service(request).import_skill_package(
        package_base64=package_base64,
        prefer_name=prefer_name,
        overwrite=overwrite,
    )
    if "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


@router.get("/deeplink-catalog/status")
async def deeplink_catalog_status(request: Request):
    return await _skills_service(request).deeplink_catalog_status()


@router.post("/deeplink-catalog/sync")
async def sync_deeplink_catalog(request: Request, payload: dict = Body(...)):
    result = await _skills_service(request).sync_deeplink_catalog(payload)
    if not result.get("ok"):
        raise HTTPException(status_code=400, detail=result.get("error") or "sync failed")
    return result
