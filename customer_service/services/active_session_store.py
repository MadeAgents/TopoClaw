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

"""
自定义小助手多 session：跨端「当前活跃 session」指针，与 session_store 使用相同 key 规则。
"""
import json
import logging
import time
from typing import Dict, Optional, Tuple

from core.output_paths import ACTIVE_SESSIONS_STORAGE_FILE

logger = logging.getLogger(__name__)
_store: Dict[str, Dict] = {}


def _normalize_base_url(url: str) -> str:
    u = (url or "").strip().rstrip("/")
    return u if u else ""


def _key(imei: str, conversation_id: str, base_url: Optional[str] = None) -> str:
    imei = (imei or "").strip()
    cid = (conversation_id or "").strip()
    if base_url:
        norm = _normalize_base_url(base_url)
        if norm and cid:
            return f"{imei}_by_url_{norm}_{cid}"
    return f"{imei}_{cid}"


def _load_store() -> None:
    global _store
    try:
        if ACTIVE_SESSIONS_STORAGE_FILE.is_file():
            with open(ACTIVE_SESSIONS_STORAGE_FILE, "r", encoding="utf-8") as f:
                _store = json.load(f)
            if not isinstance(_store, dict):
                _store = {}
        else:
            _store = {}
    except Exception as e:
        logger.error(f"加载 active session 存储失败: {e}")
        _store = {}


def _save_store() -> None:
    try:
        ACTIVE_SESSIONS_STORAGE_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(ACTIVE_SESSIONS_STORAGE_FILE, "w", encoding="utf-8") as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存 active session 存储失败: {e}")


def get_active(imei: str, conversation_id: str, base_url: str = "") -> Tuple[Optional[str], int]:
    """返回 (active_session_id 或 None, updated_at 毫秒)"""
    _load_store()
    key = _key(imei, conversation_id, base_url)
    row = _store.get(key)
    if not row or not isinstance(row, dict):
        return None, 0
    sid = row.get("active_session_id") or row.get("activeSessionId")
    if not sid:
        return None, 0
    ut = int(row.get("updated_at", 0) or row.get("updatedAt", 0) or 0)
    return str(sid), ut


def set_active(
    imei: str, conversation_id: str, active_session_id: str, base_url: str = ""
) -> Tuple[str, int]:
    """
    设置活跃 session。Last-Write-Wins：仅当新 updated_at >= 旧值时写入（同毫秒则覆盖）。
    返回 (active_session_id, updated_at)。
    """
    _load_store()
    key = _key(imei, conversation_id, base_url)
    now_ms = int(time.time() * 1000)
    sid = (active_session_id or "").strip()
    if not sid:
        raise ValueError("active_session_id 为空")

    prev = _store.get(key) or {}
    prev_ut = int(prev.get("updated_at", 0) or 0)
    # 允许相等或更大：客户端可带 updated_at 做严格 LWW（后续扩展）；当前始终用服务端 now
    new_ut = max(now_ms, prev_ut + 1) if now_ms <= prev_ut else now_ms

    _store[key] = {"active_session_id": sid, "updated_at": new_ut}
    _save_store()
    logger.info(f"[ActiveSession] set key={key}, session={sid[:12]}..., updated_at={new_ut}")
    return sid, new_ut
