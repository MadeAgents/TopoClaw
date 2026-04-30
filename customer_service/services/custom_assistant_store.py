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

"""自定义小助手列表存储 - 端云同步"""

import json
import logging
import random
import re
from datetime import datetime
from typing import Dict, List, Any

from core.output_paths import CUSTOM_ASSISTANTS_FILE

logger = logging.getLogger(__name__)
_store: Dict[str, List[Dict[str, Any]]] = {}

# 登录/连接时自动写入 custom_assistants.json 的「默认 TopoClaw」条目（单聊等可见；对话可不传 agent_id）
# 兼容旧逻辑：仍保留 legacy id 常量 topoclaw，避免历史数据与旧客户端异常。
DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID = "topoclaw"
_RANDOM_LEN = 8
_ID_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
_TOP_CLAW_BASE_URL = "topoclaw://relay"

# 内置常见中文助手名的拼音映射；其他名称会走 ASCII slug 回退。
_BUILTIN_NAME_PINYIN = {
    "自动执行小助手": "zidongzhixingxiaozhushou",
    "技能学习小助手": "jinengxuexixiaozhushou",
    "聊天小助手": "liaotianxiaozhushou",
    "人工客服": "rengongkefu",
}


def _safe_token(raw: str, default: str = "assistant") -> str:
    text = str(raw or "").strip().lower()
    token = re.sub(r"[^a-z0-9]+", "", text)
    return token or default


def _name_token(name: str) -> str:
    clean = str(name or "").strip()
    if clean in _BUILTIN_NAME_PINYIN:
        return _BUILTIN_NAME_PINYIN[clean]
    token = re.sub(r"[^a-z0-9]+", "", clean.lower())
    return token or "assistant"


def _random_digits(n: int = _RANDOM_LEN) -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(max(1, n)))


def _generate_display_id(imei: str, existing: set[str]) -> str:
    """生成展示唯一标识 displayId（不改历史 id）。"""
    imei_token = _safe_token(imei, "imei")
    for _ in range(1000):
        did = f"{int(datetime.now().timestamp() * 1000)}_{imei_token}_{_random_digits(_RANDOM_LEN)}"
        if did not in existing:
            return did
    return f"{int(datetime.now().timestamp() * 1000)}_{imei_token}_{_random_digits(12)}"


def _migrate_display_ids() -> bool:
    """一次性迁移：为历史自定义助手补齐 displayId。"""
    changed = False
    for imei, assistants in list(_store.items()):
        if not isinstance(assistants, list):
            continue
        existing_display_ids: set[str] = set()
        for a in assistants:
            if isinstance(a, dict):
                did = str(a.get("displayId") or "").strip()
                if did:
                    existing_display_ids.add(did)
        for a in assistants:
            if not isinstance(a, dict):
                continue
            if not a.get("baseUrl"):
                continue
            did = str(a.get("displayId") or "").strip()
            if did:
                continue
            new_did = _generate_display_id(imei, existing_display_ids)
            a["displayId"] = new_did
            existing_display_ids.add(new_did)
            changed = True
    return changed


def _build_builtin_id(imei: str, name: str, existing_ids: set[str]) -> str:
    imei_token = _safe_token(imei, "imei")
    name_token = _name_token(name)
    for _ in range(1000):
        aid = f"{imei_token}_{name_token}_{_random_digits(_RANDOM_LEN)}"
        if aid not in existing_ids:
            return aid
    # 极端冲突回退：追加时间戳后缀保证可用
    return f"{imei_token}_{name_token}_{int(datetime.now().timestamp() * 1000)}"


def build_custom_assistant_id(creator_imei: str, created_at_ms: int | None = None) -> str:
    imei_token = _safe_token(creator_imei, "imei")
    ts = int(created_at_ms) if created_at_ms else int(datetime.now().timestamp() * 1000)
    return f"{imei_token}_{ts}_{_random_digits(_RANDOM_LEN)}"


def _build_custom_id_unique(creator_imei: str, existing_ids: set[str], created_at_ms: int | None = None) -> str:
    for _ in range(1000):
        aid = build_custom_assistant_id(creator_imei, created_at_ms)
        if aid not in existing_ids:
            return aid
    return f"{_safe_token(creator_imei, 'imei')}_{int(datetime.now().timestamp() * 1000)}_{_random_digits(10)}"


def _normalize_assistant_id(imei: str, item: Dict[str, Any], existing_ids: set[str]) -> str:
    current = str(item.get("id") or "").strip()
    # 兼容旧格式：合法 id 一律保留，确保“已创建 ID 不变”。
    if current and _ID_RE.match(current):
        existing_ids.add(current)
        return current
    aid = _build_custom_id_unique(imei, existing_ids)
    existing_ids.add(aid)
    return aid


def get_default_topoclaw_assistant_id(imei: str) -> str | None:
    """返回该用户默认 topoclaw 助手 ID（优先新格式，兼容 legacy）。"""
    _load()
    imei = (imei or "").strip()
    if not imei:
        return None
    for a in _store.get(imei, []):
        if (str(a.get("baseUrl") or "").strip().lower().rstrip("/") + "/") == (_TOP_CLAW_BASE_URL.rstrip("/") + "/"):
            aid = str(a.get("id") or "").strip()
            if aid:
                return aid
    return None


def is_topoclaw_assistant_id(imei: str, assistant_id: str) -> bool:
    aid = str(assistant_id or "").strip()
    if not aid:
        return False
    if aid == DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID:
        return True
    found = get_default_topoclaw_assistant_id(imei)
    return bool(found and found == aid)


def _load():
    global _store
    try:
        if CUSTOM_ASSISTANTS_FILE.is_file():
            with open(CUSTOM_ASSISTANTS_FILE, 'r', encoding='utf-8') as f:
                _store = json.load(f)
            migrated = _migrate_display_ids()
            if migrated:
                _save()
                logger.info("已完成 custom_assistants displayId 存量迁移")
            logger.info(f"加载自定义小助手列表成功，用户数: {len(_store)}")
        else:
            _store = {}
    except Exception as e:
        logger.error(f"加载自定义小助手列表失败: {e}")
        _store = {}


def _save():
    try:
        with open(CUSTOM_ASSISTANTS_FILE, 'w', encoding='utf-8') as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存自定义小助手列表失败: {e}")


def get_custom_assistants(imei: str) -> List[Dict[str, Any]]:
    """获取用户的自定义小助手列表"""
    _load()  # 每次从文件重载，确保多进程/多实例下数据一致
    result = _store.get(imei.strip(), [])
    logger.info(f"返回小助手列表: imei={imei[:8]}..., 数量={len(result)}")
    return result


def save_custom_assistants(imei: str, assistants: List[Dict[str, Any]]) -> None:
    """保存用户的自定义小助手列表"""
    _load()  # 先重载，合并其他进程可能已写入的变更
    imei = imei.strip()
    existing_ids: set[str] = set()
    existing_display_ids: set[str] = set()
    for a in _store.get(imei, []):
        if isinstance(a, dict):
            did = str(a.get("displayId") or "").strip()
            if did:
                existing_display_ids.add(did)
    normalized: List[Dict[str, Any]] = []
    for raw in assistants:
        if not raw or not raw.get("baseUrl"):
            continue
        item = dict(raw)
        item["id"] = _normalize_assistant_id(imei, item, existing_ids)
        did = str(item.get("displayId") or "").strip()
        if not did:
            did = _generate_display_id(imei, existing_display_ids)
            item["displayId"] = did
        existing_display_ids.add(did)
        normalized.append(item)
    _store[imei] = normalized
    _save()
    logger.info(f"保存自定义小助手列表: imei={imei[:8]}..., 数量={len(_store[imei])}")


def adapt_user_assistant_ids(imei: str) -> Dict[str, int]:
    """
    为指定用户执行「新助手ID」适配：
    - 补齐 displayId（若缺失）
    - 补齐 creator_imei（若缺失）
    """
    _load()
    imei = (imei or "").strip()
    if not imei:
        return {"assistants_total": 0, "assistants_updated": 0}
    assistants = list(_store.get(imei, []))
    if not assistants:
        return {"assistants_total": 0, "assistants_updated": 0}
    existing_display_ids: set[str] = set()
    for a in assistants:
        if isinstance(a, dict):
            did = str(a.get("displayId") or "").strip()
            if did:
                existing_display_ids.add(did)
    updated = 0
    normalized: List[Dict[str, Any]] = []
    existing_ids: set[str] = set()
    for raw in assistants:
        if not raw or not raw.get("baseUrl"):
            continue
        item = dict(raw)
        item["id"] = _normalize_assistant_id(imei, item, existing_ids)
        did = str(item.get("displayId") or "").strip()
        if not did:
            did = _generate_display_id(imei, existing_display_ids)
            item["displayId"] = did
            existing_display_ids.add(did)
            updated += 1
        creator = str(item.get("creator_imei") or "").strip()
        if not creator:
            item["creator_imei"] = imei
            updated += 1
        normalized.append(item)
    _store[imei] = normalized
    if updated > 0:
        _save()
        logger.info("已适配用户助手ID: imei=%s..., 更新=%s", imei[:8], updated)
    return {"assistants_total": len(normalized), "assistants_updated": updated}


def ensure_default_topoclaw_assistant(imei: str, display_id: str | None = None) -> None:
    """确保该用户在 custom_assistants.json 中有一条默认 topoclaw 助手（与群内 assistant 分离）。

    - 客服 WS 连接、资料保存时调用（display_id 可为空）。
    - Agent WS 绑定 node 后传入 display_id（imei_id）便于端上展示/路由。
    """
    imei = (imei or "").strip()
    if not imei:
        return
    _load()
    assistants = list(_store.get(imei, []))
    existing_ids = {str(a.get("id") or "").strip() for a in assistants if str(a.get("id") or "").strip()}
    did = get_default_topoclaw_assistant_id(imei) or _build_builtin_id(imei, "topoclaw", existing_ids)
    base_entry: Dict[str, Any] = {
        "id": did,
        "name": "topoclaw",
        "intro": "登录/连接时自动登记；走本机 TopoClaw 默认代理（可不传 agent_id）",
        "baseUrl": _TOP_CLAW_BASE_URL,
        "capabilities": ["chat", "skills", "cron"],
        "avatar": "",
        "multiSessionEnabled": True,
        "displayId": (display_id or "").strip(),
    }
    idx = next((i for i, a in enumerate(assistants)
                if (str(a.get("baseUrl") or "").strip().lower().rstrip("/") + "/") == (_TOP_CLAW_BASE_URL.rstrip("/") + "/")),
               None)
    if idx is None:
        assistants.append(dict(base_entry))
        logger.info("已为用户追加默认 topoclaw 小助手记录: imei=%s...", imei[:12])
    else:
        merged = dict(assistants[idx])
        merged.update({k: v for k, v in base_entry.items() if k != "displayId"})
        if display_id is not None and str(display_id).strip():
            merged["displayId"] = str(display_id).strip()
        elif "displayId" not in merged or merged.get("displayId") is None:
            merged["displayId"] = base_entry["displayId"]
        assistants[idx] = merged
        logger.info("已更新用户默认 topoclaw 小助手记录: imei=%s...", imei[:12])
    save_custom_assistants(imei, assistants)


# 保持向后兼容的别名
ensure_default_openclaw_assistant = ensure_default_topoclaw_assistant


_load()
