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

"""小助手广场存储 - 用户分享的助手列表（全局，非按 imei 分）"""

import json
import logging
import uuid
from datetime import datetime, timezone, timedelta
from typing import Dict, List, Any, Optional

from core.output_paths import PLAZA_ASSISTANTS_FILE

logger = logging.getLogger(__name__)
TZ_UTC_PLUS_8 = timezone(timedelta(hours=8))
_store: List[Dict[str, Any]] = []


def _creator_imei_key(stored: str) -> str:
    """存储中的 creator_imei 一般为原始 imei；兼容历史「前缀 · 昵称」格式，取前半段比对。"""
    s = (stored or "").strip()
    if " · " in s:
        return s.split(" · ", 1)[0].strip()
    return s


def _ensure_liked_imeis(item: Dict[str, Any]) -> None:
    """兼容旧数据：保证每条有 liked_imeis 列表。"""
    if "liked_imeis" not in item or not isinstance(item.get("liked_imeis"), list):
        item["liked_imeis"] = []


def _load():
    global _store
    try:
        if PLAZA_ASSISTANTS_FILE.is_file():
            with open(PLAZA_ASSISTANTS_FILE, 'r', encoding='utf-8') as f:
                _store = json.load(f)
            if not isinstance(_store, list):
                _store = []
            for _it in _store:
                if isinstance(_it, dict):
                    _ensure_liked_imeis(_it)
            logger.info(f"加载广场小助手列表成功，数量: {len(_store)}")
        else:
            _store = []
    except Exception as e:
        logger.error(f"加载广场小助手列表失败: {e}")
        _store = []


def _save():
    try:
        with open(PLAZA_ASSISTANTS_FILE, 'w', encoding='utf-8') as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存广场小助手列表失败: {e}")


def add_to_plaza(imei: str, assistant: Dict[str, Any]) -> Dict[str, Any]:
    """将小助手上架到广场，返回带 id 和 created_at 的条目"""
    _load()
    source_assistant_id = str(assistant.get("id") or "").strip()
    item = {
        "id": str(uuid.uuid4()),
        "creator_imei": imei.strip(),
        # 保留原助手 id，供其他用户添加时复用，保证跨用户稳定不变。
        "assistant_id": source_assistant_id or None,
        "name": assistant.get("name", "小助手"),
        "intro": assistant.get("intro") or "",
        "baseUrl": (assistant.get("baseUrl") or "").strip().rstrip("/") + "/",
        "capabilities": assistant.get("capabilities") or [],
        "avatar": assistant.get("avatar"),
        "multiSessionEnabled": assistant.get("multiSessionEnabled") or assistant.get("multi_session_enabled"),
        "created_at": datetime.now(TZ_UTC_PLUS_8).isoformat(),
        "liked_imeis": [],
    }
    if not item["baseUrl"]:
        raise ValueError("baseUrl 必填")
    _store.insert(0, item)
    _save()
    logger.info(f"广场上架: id={item['id'][:8]}..., creator={imei[:8]}..., name={item['name']}")
    return item


def get_plaza_list(page: int = 1, limit: int = 50, sort: str = "latest") -> tuple:
    """
    分页获取广场列表。
    sort: latest — 上架时间倒序（与列表存储顺序一致）；hot — 点赞数倒序，同点赞数按时间倒序。
    返回 (items, has_more)
    """
    _load()
    work: List[Dict[str, Any]] = []
    for it in _store:
        if isinstance(it, dict):
            _ensure_liked_imeis(it)
            work.append(it)
    sk = (sort or "latest").strip().lower()
    if sk == "hot":
        # 点赞数倒序；同点赞数按上架时间倒序。不能对 created_at 字符串做一元负号（会 TypeError）。
        work.sort(key=lambda x: x.get("created_at") or "", reverse=True)
        work.sort(key=lambda x: len(x.get("liked_imeis") or []), reverse=True)
    start = (page - 1) * limit
    end = start + limit
    items = work[start:end]
    has_more = len(work) > end
    return items, has_more


def get_by_id(plaza_id: str) -> Optional[Dict[str, Any]]:
    """根据 id 获取广场中的小助手"""
    _load()
    for item in _store:
        if item.get("id") == plaza_id:
            return item
    return None


def update_in_plaza(
    plaza_id: str,
    creator_imei: str,
    updates: Dict[str, Any],
) -> Optional[Dict[str, Any]]:
    """创建者更新广场中的小助手资料（名称、头像、介绍、域名）"""
    _load()
    for item in _store:
        if item.get("id") == plaza_id:
            if _creator_imei_key(item.get("creator_imei") or "") != creator_imei.strip():
                return None  # 仅创建者可编辑
            if "name" in updates and updates["name"] is not None:
                item["name"] = str(updates["name"]).strip() or "小助手"
            if "intro" in updates:
                item["intro"] = str(updates["intro"]).strip() if updates["intro"] else ""
            if "baseUrl" in updates:
                raw = (updates["baseUrl"] or "").strip()
                if not raw:
                    return None  # baseUrl 不能为空
                item["baseUrl"] = raw.rstrip("/") + "/"
            if "avatar" in updates:
                item["avatar"] = updates["avatar"]
            _save()
            logger.info(f"广场小助手已更新: plaza_id={plaza_id[:8]}..., creator={creator_imei[:8]}...")
            return item
    return None


def toggle_plaza_like(plaza_id: str, imei: str) -> Optional[Dict[str, Any]]:
    """
    切换点赞：已赞则取消，未赞则点赞。
    成功返回 {"likes_count": int, "liked_by_me": bool}；条目不存在返回 None。
    """
    _load()
    viewer = (imei or "").strip()
    if not viewer:
        return None
    for item in _store:
        if item.get("id") != plaza_id:
            continue
        _ensure_liked_imeis(item)
        likes: List[str] = list(item.get("liked_imeis") or [])
        if viewer in likes:
            likes = [x for x in likes if x != viewer]
            liked_by_me = False
        else:
            likes.append(viewer)
            liked_by_me = True
        item["liked_imeis"] = likes
        _save()
        return {"likes_count": len(likes), "liked_by_me": liked_by_me}
    return None


def remove_from_plaza(plaza_id: str, requester_imei: str) -> Optional[Dict[str, Any]]:
    """创建者将广场条目下架；成功返回被删除的条目，否则 None（不存在或无权限）。"""
    _load()
    rid = (requester_imei or "").strip()
    if not rid:
        return None
    for i, item in enumerate(_store):
        if item.get("id") != plaza_id:
            continue
        if _creator_imei_key(item.get("creator_imei") or "") != rid:
            return None
        removed = _store.pop(i)
        _save()
        logger.info(f"广场下架: plaza_id={plaza_id[:8]}..., creator={rid[:8]}..., name={removed.get('name')}")
        return removed
    return None


_load()
