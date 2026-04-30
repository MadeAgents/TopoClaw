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

"""
会话摘要存储（云侧）
- append-only：新摘要永不覆盖旧摘要
- 按 user imei 隔离
- 幂等去重：同 id 只写一次
"""

import json
import logging
from typing import Dict, List, Any, Tuple

from core.output_paths import CONVERSATION_SUMMARIES_FILE

logger = logging.getLogger(__name__)

_store: Dict[str, List[Dict[str, Any]]] = {}


def _load() -> None:
    global _store
    try:
        if CONVERSATION_SUMMARIES_FILE.is_file():
            with open(CONVERSATION_SUMMARIES_FILE, "r", encoding="utf-8") as f:
                parsed = json.load(f)
            if isinstance(parsed, dict):
                _store = {
                    str(k): v for k, v in parsed.items() if isinstance(v, list)
                }
            else:
                _store = {}
            logger.info("加载会话摘要存储成功，用户数: %s", len(_store))
        else:
            _store = {}
    except Exception as e:
        logger.error("加载会话摘要存储失败: %s", e)
        _store = {}


def _save() -> None:
    try:
        with open(CONVERSATION_SUMMARIES_FILE, "w", encoding="utf-8") as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error("保存会话摘要存储失败: %s", e)


def _safe_int(v: Any, default: int = 0) -> int:
    try:
        i = int(v)
        return i
    except Exception:
        return default


def _normalize_entry(entry: Dict[str, Any], user_imei: str) -> Dict[str, Any]:
    scope_type = str(entry.get("scopeType") or "").strip()
    scope_id = str(entry.get("scopeId") or "").strip()
    created_at = _safe_int(entry.get("createdAt"), 0)
    message_start_ts = _safe_int(entry.get("messageStartTs"), 0)
    message_end_ts = _safe_int(entry.get("messageEndTs"), 0)
    message_count = _safe_int(entry.get("messageCount"), 0)
    round_count = _safe_int(entry.get("roundCount"), 0)
    summary = str(entry.get("summary") or "").strip()
    if not scope_type or not scope_id or created_at <= 0:
        raise ValueError("invalid conversation summary entry")
    entry_id = str(entry.get("id") or "").strip()
    if not entry_id:
        entry_id = f"sum_{scope_type}_{scope_id}_{created_at}_{message_end_ts}"
    return {
        "id": entry_id,
        "schema": "v1",
        "userImei": user_imei,
        "scopeType": scope_type,
        "scopeId": scope_id,
        "scopeName": str(entry.get("scopeName") or scope_id),
        "createdAt": created_at,
        "messageStartTs": message_start_ts,
        "messageEndTs": message_end_ts,
        "messageCount": message_count,
        "roundCount": round_count,
        "summary": summary,
    }


def append_entries(user_imei: str, entries: List[Dict[str, Any]]) -> Tuple[int, int]:
    """
    追加多条摘要（幂等），返回 (accepted, total)
    """
    _load()
    imei = (user_imei or "").strip()
    if not imei:
        return 0, 0
    if imei not in _store:
        _store[imei] = []
    current = _store[imei]
    existing_ids = {str(x.get("id") or "").strip() for x in current}
    accepted = 0
    total = 0
    for raw in entries or []:
        if not isinstance(raw, dict):
            continue
        total += 1
        try:
            normalized = _normalize_entry(raw, imei)
        except Exception:
            continue
        nid = normalized["id"]
        if nid in existing_ids:
            continue
        current.append(normalized)
        existing_ids.add(nid)
        accepted += 1
    if accepted > 0:
        current.sort(key=lambda x: _safe_int(x.get("createdAt"), 0))
        _store[imei] = current
        _save()
    return accepted, total


def list_entries(user_imei: str, since_ts: int = 0, limit: int = 2000) -> List[Dict[str, Any]]:
    _load()
    imei = (user_imei or "").strip()
    if not imei:
        return []
    rows = list(_store.get(imei, []))
    since = _safe_int(since_ts, 0)
    if since > 0:
        rows = [x for x in rows if _safe_int(x.get("createdAt"), 0) > since]
    rows.sort(key=lambda x: _safe_int(x.get("createdAt"), 0))
    cap = max(1, min(_safe_int(limit, 2000), 10000))
    return rows[-cap:]


_load()
