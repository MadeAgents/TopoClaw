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

"""客户端版本检查（移动端 + PC 桌面端）"""
import logging
import os
from typing import Any, Dict, Optional, Tuple

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from storage.version_file import load_version_info

logger = logging.getLogger(__name__)
router = APIRouter(tags=["version"])

DEFAULT_UPDATE_URL = (
    os.getenv("CUSTOMER_SERVICE_UPDATE_URL", "").strip()
)


def _evaluate_version_flags(
    current_version: Optional[str],
    latest_version: str,
    min_supported_version: str,
    update_info: Dict[str, Any],
    *,
    force_only_when_below_min: bool = False,
) -> Tuple[bool, bool]:
    """
    has_update：当前版本低于 latest。
    force_update：默认与历史移动端逻辑一致（有新版本或低于 min 均可为 True）；
    若 force_only_when_below_min=True（桌面端），仅当当前低于 min_supported 时为 True，便于展示「暂不更新」。
    """
    has_update = False
    need_update = False
    if current_version:
        try:
            current_parts = [int(x) for x in current_version.split(".")]
            latest_parts = [int(x) for x in latest_version.split(".")]

            max_len = max(len(current_parts), len(latest_parts))
            current_parts.extend([0] * (max_len - len(current_parts)))
            latest_parts.extend([0] * (max_len - len(latest_parts)))

            for i in range(max_len):
                if latest_parts[i] > current_parts[i]:
                    has_update = True
                    if not force_only_when_below_min:
                        need_update = True
                    break
                if latest_parts[i] < current_parts[i]:
                    break

            min_parts = [int(x) for x in min_supported_version.split(".")]
            min_parts.extend([0] * (max_len - len(min_parts)))
            for i in range(max_len):
                if min_parts[i] > current_parts[i]:
                    need_update = True
                    break
                if min_parts[i] < current_parts[i]:
                    break

        except Exception as e:
            logger.warning(
                "版本号比较失败: %s, current_version=%s, latest_version=%s",
                e,
                current_version,
                latest_version,
            )
            has_update = latest_version != current_version
            need_update = False
    else:
        has_update = bool(update_info.get("has_update", False))
        need_update = bool(update_info.get("force_update", False))

    return has_update, need_update


def _desktop_slice(version_info: Dict[str, Any]) -> Dict[str, Any]:
    """桌面端配置：缺省时与移动端共用 update_url（TopoMobile 同款下载页）。"""
    mobile_url = str(version_info.get("update_url") or DEFAULT_UPDATE_URL).strip() or DEFAULT_UPDATE_URL
    d = version_info.get("desktop")
    if not isinstance(d, dict):
        d = {}
    raw_desktop_url = d.get("update_url")
    desktop_url = (
        str(raw_desktop_url).strip()
        if isinstance(raw_desktop_url, str) and str(raw_desktop_url).strip()
        else mobile_url
    )
    ann = d.get("update_info")
    if not isinstance(ann, dict):
        ann = version_info.get("update_info") or {}
    if not isinstance(ann, dict):
        ann = {}
    last = d.get("last_updated")
    if last is None or last == "":
        last = version_info.get("last_updated", "")
    return {
        "latest_version": str(d.get("latest_version", "0.0.0")),
        "min_supported_version": str(d.get("min_supported_version", "0.0.0")),
        "update_url": desktop_url,
        "update_info": ann,
        "last_updated": str(last or ""),
    }


@router.get("/api/version/check")
async def check_version(current_version: Optional[str] = None):
    """
    检查版本更新（TopoMobile 等移动端）

    Args:
        current_version: 客户端当前版本号（可选）
    """
    try:
        version_info = load_version_info()
        latest_version = version_info.get("latest_version", "1.0")
        min_supported_version = version_info.get("min_supported_version", "1.0")
        update_url = str(version_info.get("update_url") or DEFAULT_UPDATE_URL)
        update_info = version_info.get("update_info", {})
        if not isinstance(update_info, dict):
            update_info = {}

        has_update, need_update = _evaluate_version_flags(
            current_version,
            str(latest_version),
            str(min_supported_version),
            update_info,
        )

        return JSONResponse(
            {
                "success": True,
                "current_version": current_version,
                "latest_version": latest_version,
                "min_supported_version": min_supported_version,
                "update_url": update_url,
                "has_update": has_update,
                "force_update": need_update,
                "update_message": update_info.get("update_message", "开源版本"),
                "last_updated": version_info.get("last_updated", ""),
            }
        )
    except Exception as e:
        logger.error("检查版本失败: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e)) from e


@router.get("/api/version/check-desktop")
async def check_desktop_version(
    current_version: Optional[str] = None,
    platform: Optional[str] = None,
):
    """
    检查 TopoDesktop（PC）版本；下载地址默认与移动端 version_info.update_url 一致。
    """
    try:
        version_info = load_version_info()
        cfg = _desktop_slice(version_info)
        latest_version = cfg["latest_version"]
        min_supported_version = cfg["min_supported_version"]
        update_url = cfg["update_url"]
        update_info = cfg["update_info"]

        has_update, need_update = _evaluate_version_flags(
            current_version,
            latest_version,
            min_supported_version,
            update_info,
            force_only_when_below_min=True,
        )

        return JSONResponse(
            {
                "success": True,
                "current_version": current_version,
                "latest_version": latest_version,
                "min_supported_version": min_supported_version,
                "update_url": update_url,
                "has_update": has_update,
                "force_update": need_update,
                "update_message": update_info.get("update_message", "开源版本"),
                "last_updated": cfg["last_updated"],
                "platform": platform,
            }
        )
    except Exception as e:
        logger.error("检查桌面端版本失败: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e)) from e
