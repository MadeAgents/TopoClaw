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
多 session 聊天会话存储 - 实现 PC 与手机端 session 列表跨设备同步
与 unified_message_store 类似，持久化到 JSON 文件
"""
import json
import logging
from typing import Dict, List, Optional

from core.output_paths import SESSIONS_STORAGE_FILE

logger = logging.getLogger(__name__)
_store: Dict[str, List[Dict]] = {}  # key: {imei}_{conversation_id} -> [{id, title, createdAt}]


def _normalize_base_url(url: str) -> str:
    """规范化 baseUrl，确保 PC 与手机使用相同 key（两端 assistant id 可能不同）"""
    u = (url or "").strip().rstrip("/")
    return u if u else ""


def _key(imei: str, conversation_id: str, base_url: Optional[str] = None) -> str:
    imei = (imei or "").strip()
    cid = (conversation_id or "").strip()
    # 自定义小助手：baseUrl + conversation_id 作 key，保证同 baseUrl 的不同 assistant 各自独立
    # （PC/手机 assistant id 云端同步后一致，跨端仍可互通）
    if base_url:
        norm = _normalize_base_url(base_url)
        if norm and cid:
            return f"{imei}_by_url_{norm}_{cid}"
    return f"{imei}_{cid}"


def _load_store() -> None:
    global _store
    try:
        if SESSIONS_STORAGE_FILE.is_file():
            with open(SESSIONS_STORAGE_FILE, 'r', encoding='utf-8') as f:
                _store = json.load(f)
            logger.info(f"加载 session 存储成功，条目数: {len(_store)}")
        else:
            _store = {}
    except Exception as e:
        logger.error(f"加载 session 存储失败: {e}")
        _store = {}


def _save_store() -> None:
    try:
        with open(SESSIONS_STORAGE_FILE, 'w', encoding='utf-8') as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存 session 存储失败: {e}")


def _normalize_session(s: Dict) -> Dict:
    """标准化 session 格式"""
    return {
        "id": str(s.get("id", "")),
        "title": str(s.get("title", "新对话")),
        "createdAt": int(s.get("createdAt", 0)) or int(s.get("created_at", 0)),
    }


def sync_sessions(imei: str, conversation_id: str, client_sessions: List[Dict], base_url: str = "") -> List[Dict]:
    """
    同步 session 列表：客户端传入的 sessions 为权威来源，支持删除同步。
    若传入 base_url（自定义小助手），则用 baseUrl 作为存储 key，保证 PC/手机 跨端一致。
    规则：仅保留客户端传入的 session；若服务端有同 id 的更好数据（如更新的 title），则合并。
    这样一端删除 session 后 sync，其他端拉取时会得到删除后的列表。
    返回合并后的列表，按 createdAt 降序。
    """
    _load_store()
    key = _key(imei, conversation_id, base_url)
    server_list = _store.get(key, [])

    server_by_id = {str(s.get("id", "")): _normalize_session(s) for s in server_list if s.get("id")}
    merged: Dict[str, Dict] = {}
    for s in client_sessions:
        ns = _normalize_session(s)
        sid = ns["id"]
        if not sid:
            continue
        cur = server_by_id.get(sid)
        if not cur:
            merged[sid] = ns
        else:
            # 取 createdAt 较大者；若相同，取 title 非空者
            if ns["createdAt"] > cur["createdAt"]:
                merged[sid] = ns
            elif cur["createdAt"] > ns["createdAt"]:
                merged[sid] = cur
            else:
                merged[sid] = ns if ns["title"] else cur

    result = sorted(merged.values(), key=lambda x: -x["createdAt"])
    _store[key] = result
    _save_store()
    logger.info(
        f"[Session] sync 完成: key={key}, base_url={'有' if base_url else '无'}, "
        f"服务端原有={len(server_list)}, 客户端上传={len(client_sessions)}, 合并后={len(result)}"
    )
    return result


def get_sessions(imei: str, conversation_id: str, base_url: str = "") -> List[Dict]:
    """获取指定会话的 session 列表。若新 key 无数据，尝试从旧 key（仅 base_url）迁移"""
    _load_store()
    key = _key(imei, conversation_id, base_url)
    result = _store.get(key, [])
    # 迁移：旧 key 为 by_url 无 conversation_id，若有数据则迁移到新 key
    if not result and base_url and conversation_id:
        norm = _normalize_base_url(base_url)
        if norm:
            old_key = f"{imei.strip()}_by_url_{norm}"
            if old_key != key:
                legacy = _store.get(old_key, [])
                if legacy:
                    _store[key] = legacy
                    _save_store()
                    logger.info(f"[Session] 迁移: {old_key} -> {key}, 数量={len(legacy)}")
                    return legacy
    return result
