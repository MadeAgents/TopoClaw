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

"""技能广场存储 - 用户分享的技能列表（全局，非按 imei 分）"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List

from core.output_paths import PLAZA_SKILLS_FILE

logger = logging.getLogger(__name__)
TZ_UTC_PLUS_8 = timezone(timedelta(hours=8))
_store: List[Dict[str, Any]] = []


def _load() -> None:
    global _store
    try:
        if PLAZA_SKILLS_FILE.is_file():
            with open(PLAZA_SKILLS_FILE, "r", encoding="utf-8") as f:
                _store = json.load(f)
            if not isinstance(_store, list):
                _store = []
        else:
            _store = []
    except Exception as e:
        logger.error("加载技能广场列表失败: %s", e)
        _store = []


def _save() -> None:
    try:
        with open(PLAZA_SKILLS_FILE, "w", encoding="utf-8") as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error("保存技能广场列表失败: %s", e)


def add_to_plaza(imei: str, skill: Dict[str, Any]) -> Dict[str, Any]:
    _load()
    title = str(skill.get("title") or "").strip()
    package_base64 = str(skill.get("package_base64") or "").strip()
    if not title:
        raise ValueError("title 必填")
    if not package_base64:
        raise ValueError("package_base64 必填")
    item = {
        "id": str(uuid.uuid4()),
        "creator_imei": imei.strip(),
        "skill_id": str(skill.get("id") or "").strip() or None,
        "title": title,
        "originalPurpose": str(skill.get("originalPurpose") or "").strip() or "",
        "steps": [str(x or "").strip() for x in (skill.get("steps") or []) if str(x or "").strip()],
        "executionPlatform": skill.get("executionPlatform"),
        "author": str(skill.get("author") or "").strip() or None,
        "tags": [str(x or "").strip() for x in (skill.get("tags") or []) if str(x or "").strip()],
        "package_base64": package_base64,
        "package_file_name": str(skill.get("package_file_name") or "").strip() or "skill_package.zip",
        "created_at": datetime.now(TZ_UTC_PLUS_8).isoformat(),
    }
    _store.insert(0, item)
    _save()
    return item


def list_plaza_skills(page: int = 1, limit: int = 50, query: str = "") -> tuple[list[Dict[str, Any]], bool]:
    _load()
    q = (query or "").strip().lower()
    items = [x for x in _store if isinstance(x, dict)]
    if q:
        items = [
            x
            for x in items
            if q in str(x.get("title") or "").lower()
            or q in str(x.get("originalPurpose") or "").lower()
            or any(q in str(t or "").lower() for t in (x.get("tags") or []))
        ]
    start = (max(page, 1) - 1) * max(limit, 1)
    end = start + max(limit, 1)
    return items[start:end], len(items) > end


_load()
