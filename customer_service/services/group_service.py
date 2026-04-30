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
群组服务
管理群组创建、成员管理和消息路由
"""
import json
import logging
import re
import uuid
from datetime import datetime
from typing import Dict, List, Optional

from core.output_paths import GROUPS_STORAGE_FILE, USER_GROUPS_FILE
from services.custom_assistant_store import get_custom_assistants

logger = logging.getLogger(__name__)

# 群组存储 {group_id: group_info}
groups_storage: Dict[str, Dict] = {}

# 用户-群组关系 {imei: [group_ids]}
user_groups: Dict[str, List[str]] = {}

# 自动执行小助手标识（与 APK 中 CONVERSATION_ID_ASSISTANT 对应）
DEFAULT_ASSISTANT_ID = "assistant"
# 内置默认自定义 TopoClaw（移动端/桌面端固定内置会话）
DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID = "custom_topoclaw"
# 内置 GroupManager 小助手（桌面端默认内置）
DEFAULT_GROUP_MANAGER_ASSISTANT_ID = "custom_groupmanager"
DEFAULT_GROUP_NAME = "助手群"
# 兼容 app.py 的导入（消息发送时 senderImei 等使用）
ASSISTANT_BOT_ID = "assistant_bot"


def _normalize_group_assistants(group: Dict) -> None:
    """确保群组有 assistants 字段，兼容旧数据 assistant_enabled"""
    if "assistants" not in group:
        if group.get("assistant_enabled", False):
            group["assistants"] = [DEFAULT_ASSISTANT_ID]
        else:
            group["assistants"] = []
        if "assistant_enabled" in group:
            del group["assistant_enabled"]
    if not isinstance(group.get("assistants"), list):
        group["assistants"] = list(group["assistants"]) if group.get("assistants") else []


def _ensure_assistants_list(group: Dict) -> None:
    """确保群组有 assistants 字段，兼容旧的 assistant_enabled"""
    if "assistants" not in group:
        if group.get("assistant_enabled", False):
            group["assistants"] = ["assistant"]
        else:
            group["assistants"] = []
        group["assistant_enabled"] = len(group["assistants"]) > 0


def _ensure_group_feature_flags(group: Dict) -> None:
    """确保群组具备功能开关字段，兼容旧数据。"""
    group["workflow_mode"] = bool(group.get("workflow_mode", False))
    group["free_discovery"] = bool(group.get("free_discovery", False))
    group["assistant_muted"] = bool(group.get("assistant_muted", False))
    group["is_default_group"] = bool(group.get("is_default_group", False))
    if not str(group.get("group_manager_assistant_id") or "").strip():
        group["group_manager_assistant_id"] = DEFAULT_GROUP_MANAGER_ASSISTANT_ID


def load_groups_storage():
    """从文件加载群组数据"""
    global groups_storage, user_groups
    try:
        if GROUPS_STORAGE_FILE.is_file():
            with open(GROUPS_STORAGE_FILE, "r", encoding="utf-8") as f:
                groups_storage = json.load(f)
        # groups_storage 是唯一真相；user_groups 仅作为可丢失派生缓存。
        user_groups = {}
        migrated = False
        for g in groups_storage.values():
            _ensure_assistants_list(g)
            _ensure_group_feature_flags(g)
            _ensure_group_permission_meta(g)
            migrated = _migrate_group_assistant_identity_fields(g) or migrated
        rebuilt = _rebuild_user_groups_from_groups_storage()
        if migrated:
            _save_groups_storage_only()
            logger.info("已完成 groups assistant_configs 身份字段存量迁移")
        if rebuilt:
            _save_user_groups_only()
            logger.info("已重建 user_groups 映射")
        logger.info(f"加载群组数据成功: {len(groups_storage)} 个群组")
    except Exception as e:
        logger.error(f"加载群组数据失败: {e}")
        groups_storage = {}
        user_groups = {}


def save_groups_storage():
    """保存群组数据到文件（groups_storage 为唯一真相）"""
    _save_groups_storage_only()
    # 同步刷新派生缓存；失败不影响主数据正确性。
    if _rebuild_user_groups_from_groups_storage():
        _save_user_groups_only()


def _save_groups_storage_only():
    try:
        with open(GROUPS_STORAGE_FILE, "w", encoding="utf-8") as f:
            json.dump(groups_storage, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存群组数据失败: {e}")


def _save_user_groups_only():
    try:
        with open(USER_GROUPS_FILE, "w", encoding="utf-8") as f:
            json.dump(user_groups, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存用户群组映射失败: {e}")


def _rebuild_user_groups_from_groups_storage() -> bool:
    """
    从 groups_storage 重建 user_groups（缺失/不一致兜底），避免 user_groups 丢失导致群列表为空。
    """
    global user_groups
    rebuilt: Dict[str, List[str]] = {}
    for gid, group in groups_storage.items():
        members = [str(x).strip() for x in (group.get("members") or []) if str(x).strip()]
        creator = str(group.get("creator_imei") or "").strip()
        if creator and creator not in members:
            members.append(creator)
        for imei in members:
            rebuilt.setdefault(imei, [])
            if gid not in rebuilt[imei]:
                rebuilt[imei].append(gid)
    if rebuilt != (user_groups or {}):
        user_groups = rebuilt
        return True
    return False


def _ensure_assistants_field(group: Dict) -> None:
    """确保群组有 assistants 列表，兼容旧的 assistant_enabled 格式"""
    if "assistants" not in group:
        if group.get("assistant_enabled", False):
            group["assistants"] = ["assistant"]
        else:
            group["assistants"] = []
        group["assistant_enabled"] = len(group["assistants"]) > 0
    _ensure_group_feature_flags(group)


def _ensure_group_permission_meta(group: Dict) -> None:
    """确保群组具备成员/助手的添加者元数据，兼容旧数据。"""
    creator = str(group.get("creator_imei") or "").strip()
    members = [str(x).strip() for x in group.get("members", []) if str(x).strip()]
    assistants = [str(x).strip() for x in group.get("assistants", []) if str(x).strip()]

    member_added_by = group.get("member_added_by")
    if not isinstance(member_added_by, dict):
        member_added_by = {}
        group["member_added_by"] = member_added_by
    for m in members:
        # 历史数据默认视为群主拉入（含群主自己）
        member_added_by.setdefault(m, creator or m)
    # 清理已不在群中的旧记录
    for k in list(member_added_by.keys()):
        if k not in members:
            member_added_by.pop(k, None)

    assistant_added_by = group.get("assistant_added_by")
    if not isinstance(assistant_added_by, dict):
        assistant_added_by = {}
        group["assistant_added_by"] = assistant_added_by
    for aid in assistants:
        # 历史数据默认视为群主拉入
        assistant_added_by.setdefault(aid, creator or "")
    for k in list(assistant_added_by.keys()):
        if k not in assistants:
            assistant_added_by.pop(k, None)


def _migrate_group_assistant_identity_fields(group: Dict) -> bool:
    """
    一次性迁移：为群助手配置补齐 creator_display_id。
    规则：
    - 优先复用 cfg.displayId
    - 否则若已有 creator_display_id 保持不变
    """
    changed = False
    cfgs = group.get("assistant_configs")
    if not isinstance(cfgs, dict):
        return False
    for aid, cfg in list(cfgs.items()):
        if not isinstance(cfg, dict):
            continue
        creator_imei = str(cfg.get("creator_imei") or "").strip()
        canonical_display_id = _resolve_creator_assistant_display_id(
            creator_imei, aid, cfg.get("baseUrl")
        )
        if canonical_display_id:
            if str(cfg.get("displayId") or "").strip() != canonical_display_id:
                cfg["displayId"] = canonical_display_id
                changed = True
            if str(cfg.get("creator_display_id") or "").strip() != canonical_display_id:
                cfg["creator_display_id"] = canonical_display_id
                changed = True
            cfgs[aid] = cfg
            continue
        creator_display_id = str(cfg.get("creator_display_id") or "").strip()
        if creator_display_id:
            continue
        display_id = str(cfg.get("displayId") or "").strip()
        if display_id:
            cfg["creator_display_id"] = display_id
            cfgs[aid] = cfg
            changed = True
    return changed


def _norm_url(url: str) -> str:
    return str(url or "").strip().rstrip("/").lower()


def _resolve_creator_assistant_display_id(
    creator_imei: str,
    assistant_id: str,
    base_url: str | None = None,
) -> str:
    """
    以创建侧云端 custom_assistants 为唯一真源，解析 canonical displayId。
    优先 id 命中，其次 baseUrl 命中。
    """
    imei = str(creator_imei or "").strip()
    if not imei:
        return ""
    try:
        assistants = get_custom_assistants(imei)
    except Exception:
        return ""
    aid = str(assistant_id or "").strip()
    for item in assistants:
        if not isinstance(item, dict):
            continue
        if str(item.get("id") or "").strip() == aid:
            return str(item.get("displayId") or "").strip()
    target_url = _norm_url(base_url or "")
    if target_url:
        for item in assistants:
            if not isinstance(item, dict):
                continue
            if _norm_url(item.get("baseUrl") or "") == target_url:
                return str(item.get("displayId") or "").strip()
    return ""


def _safe_token(raw: str) -> str:
    token = re.sub(r"[^a-z0-9]", "", str(raw or "").strip().lower())
    return token or "imei"


def _build_group_assistant_instance_id(base_assistant_id: str, owner_key: str, existing_ids: List[str]) -> str:
    """
    构建群内助手实例 id，允许同一个 assistant_id 在同一群内按创建者并存。
    例：custom_topoclaw -> custom_topoclaw__f41d0b2c
    """
    base = str(base_assistant_id or "").strip()
    if not base:
        return base_assistant_id
    token = _safe_token(owner_key)
    candidate = f"{base}__{token}"
    if candidate not in existing_ids:
        return candidate
    i = 2
    while f"{candidate}_{i}" in existing_ids:
        i += 1
    return f"{candidate}_{i}"


def create_group(
    creator_imei: str,
    name: str,
    member_imeis: List[str],
    assistant_enabled: bool = True,
    assistant_ids: Optional[List[str]] = None,
) -> str:
    """创建群组

    Args:
        creator_imei: 创建者IMEI
        name: 群组名称
        member_imeis: 成员IMEI列表
        assistant_enabled: 是否添加小助手（向后兼容，默认True）
        assistant_ids: 小助手ID列表（assistant/skill_learning/chat_assistant/自定义ID），优先使用
    """
    group_id = f"group_{uuid.uuid4().hex[:12]}"

    if assistant_ids is not None:
        initial_assistants = list(dict.fromkeys(assistant_ids))
    elif assistant_enabled:
        initial_assistants = [DEFAULT_GROUP_MANAGER_ASSISTANT_ID]
    else:
        initial_assistants = []
    # 新建群默认自动加入 GroupManager，并作为默认群组管理助手能力来源。
    if DEFAULT_GROUP_MANAGER_ASSISTANT_ID not in initial_assistants:
        initial_assistants.append(DEFAULT_GROUP_MANAGER_ASSISTANT_ID)

    all_members = list(set([creator_imei] + member_imeis))
    if creator_imei not in all_members:
        all_members.append(creator_imei)
    assistant_configs: Dict[str, Dict] = {}
    if DEFAULT_GROUP_MANAGER_ASSISTANT_ID in initial_assistants:
        assistant_configs[DEFAULT_GROUP_MANAGER_ASSISTANT_ID] = {
            "name": "GroupManager",
            "capabilities": ["chat", "group_manager"],
            "creator_imei": creator_imei,
            "multiSession": True,
        }

    group_info = {
        "group_id": group_id,
        "name": name,
        "creator_imei": creator_imei,
        "members": all_members,
        "created_at": datetime.now().isoformat(),
        "assistant_enabled": len(initial_assistants) > 0,
        "assistants": initial_assistants,
        "assistant_configs": assistant_configs,
        "workflow_mode": False,
        "free_discovery": True,
        "assistant_muted": False,
        "member_added_by": {m: creator_imei for m in all_members},
        "assistant_added_by": {aid: creator_imei for aid in initial_assistants},
    }

    groups_storage[group_id] = group_info

    save_groups_storage()
    logger.info(f"创建群组成功: {group_id}, 创建者: {creator_imei[:8]}..., 成员数: {len(all_members)}")
    return group_id


def _ensure_default_group_shape(group: Dict, owner_imei: str) -> bool:
    """修正默认群关键结构（幂等）"""
    changed = False
    owner = str(owner_imei or "").strip()
    if not owner:
        return False

    _ensure_assistants_field(group)
    _ensure_group_feature_flags(group)
    _ensure_group_permission_meta(group)
    if str(group.get("name") or "").strip() != DEFAULT_GROUP_NAME:
        group["name"] = DEFAULT_GROUP_NAME
        changed = True

    members = [str(x).strip() for x in (group.get("members") or []) if str(x).strip()]
    normalized_members = [owner]
    if members != normalized_members:
        group["members"] = normalized_members
        changed = True

    assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
    normalized_assistants = [
        DEFAULT_GROUP_MANAGER_ASSISTANT_ID,
        DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID,
    ]
    if assistants != normalized_assistants:
        group["assistants"] = normalized_assistants
        changed = True

    if not bool(group.get("assistant_enabled", False)):
        group["assistant_enabled"] = True
        changed = True

    if not bool(group.get("is_default_group", False)):
        group["is_default_group"] = True
        changed = True

    if str(group.get("group_manager_assistant_id") or "").strip() != DEFAULT_GROUP_MANAGER_ASSISTANT_ID:
        group["group_manager_assistant_id"] = DEFAULT_GROUP_MANAGER_ASSISTANT_ID
        changed = True

    cfg_map = group.setdefault("assistant_configs", {})
    # 默认群仅保留 GroupManager/TopoClaw 配置，避免历史 assistant 残留干扰。
    if isinstance(cfg_map, dict):
        allowed_ids = {DEFAULT_GROUP_MANAGER_ASSISTANT_ID, DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID}
        removed_cfg = [aid for aid in list(cfg_map.keys()) if aid not in allowed_ids]
        if removed_cfg:
            for aid in removed_cfg:
                cfg_map.pop(aid, None)
            changed = True
    gm_cfg = cfg_map.get(DEFAULT_GROUP_MANAGER_ASSISTANT_ID) or {}
    gm_caps = [str(x).strip() for x in (gm_cfg.get("capabilities") or []) if str(x).strip()]
    if "chat" not in gm_caps:
        gm_caps.append("chat")
    if "group_manager" not in gm_caps:
        gm_caps.append("group_manager")
    normalized_gm_cfg = {
        **gm_cfg,
        "name": str(gm_cfg.get("name") or "GroupManager"),
        "capabilities": gm_caps,
        "creator_imei": str(gm_cfg.get("creator_imei") or owner),
        "multiSession": bool(gm_cfg.get("multiSession", True)),
    }
    if normalized_gm_cfg != gm_cfg:
        cfg_map[DEFAULT_GROUP_MANAGER_ASSISTANT_ID] = normalized_gm_cfg
        changed = True

    member_added_by = group.setdefault("member_added_by", {})
    if member_added_by != {owner: owner}:
        group["member_added_by"] = {owner: owner}
        changed = True

    assistant_added_by = group.setdefault("assistant_added_by", {})
    normalized_assistant_added_by = {
        DEFAULT_GROUP_MANAGER_ASSISTANT_ID: owner,
        DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID: owner,
    }
    if assistant_added_by != normalized_assistant_added_by:
        group["assistant_added_by"] = normalized_assistant_added_by
        changed = True

    return changed


def ensure_default_group_for_user(imei: str) -> Optional[Dict]:
    """确保用户存在默认群组（幂等，覆盖新老用户）"""
    owner = str(imei or "").strip()
    if not owner:
        return None

    target_group: Optional[Dict] = None
    for group in groups_storage.values():
        creator = str(group.get("creator_imei") or "").strip()
        if creator != owner:
            continue
        if bool(group.get("is_default_group", False)):
            target_group = group
            break

    if target_group is None:
        group_id = create_group(
            creator_imei=owner,
            name=DEFAULT_GROUP_NAME,
            member_imeis=[],
            assistant_enabled=True,
            assistant_ids=[DEFAULT_GROUP_MANAGER_ASSISTANT_ID, DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID],
        )
        target_group = groups_storage.get(group_id)
        if target_group is None:
            return None
        target_group["is_default_group"] = True
        target_group["group_manager_assistant_id"] = DEFAULT_GROUP_MANAGER_ASSISTANT_ID
        _ensure_default_group_shape(target_group, owner)
        save_groups_storage()
        logger.info("已为用户创建默认群组: imei=%s..., group_id=%s", owner[:8], group_id)
        return target_group

    if _ensure_default_group_shape(target_group, owner):
        save_groups_storage()
        logger.info("已修正用户默认群组结构: imei=%s..., group_id=%s", owner[:8], target_group.get("group_id"))
    return target_group


def add_group_member(group_id: str, operator_imei: str, member_imei: str) -> bool:
    """添加群组成员（群成员可拉人）"""
    if group_id not in groups_storage:
        return False

    group = groups_storage[group_id]
    _ensure_assistants_field(group)
    _ensure_group_permission_meta(group)
    operator = str(operator_imei or "").strip()
    member = str(member_imei or "").strip()
    if not operator or not member:
        return False
    if operator not in group.get("members", []):
        logger.warning(f"非群成员无权拉人: {operator[:8]}..., 群组: {group_id}")
        return False
    if member not in group["members"]:
        group["members"].append(member)
        group.setdefault("member_added_by", {})[member] = operator

        save_groups_storage()
        logger.info(f"添加群组成员成功: {group_id}, 成员: {member[:8]}..., 操作者: {operator[:8]}...")
        return True
    return False


def remove_group_member(group_id: str, operator_imei: str, member_imei: str) -> bool:
    """移除群组成员（群主可移除任意成员；非群主仅可移除自己拉入的成员）"""
    if group_id not in groups_storage:
        return False

    group = groups_storage[group_id]
    _ensure_assistants_field(group)
    _ensure_group_permission_meta(group)
    operator = str(operator_imei or "").strip()
    member = str(member_imei or "").strip()
    if not operator or not member:
        return False
    if operator not in group.get("members", []):
        logger.warning(f"非群成员无权移除成员: {operator[:8]}..., 群组: {group_id}")
        return False
    if member == group.get("creator_imei"):
        logger.warning(f"禁止移除群主: {group_id}, operator={operator[:8]}...")
        return False
    if member in group["members"]:
        added_by = (group.get("member_added_by") or {}).get(member, "")
        is_owner = operator == group.get("creator_imei")
        if not is_owner and added_by != operator:
            logger.warning(
                f"非群主仅可移除自己拉入成员: {group_id}, operator={operator[:8]}..., member={member[:8]}..., added_by={str(added_by)[:8]}..."
            )
            return False
        group["members"].remove(member)
        if "member_added_by" in group:
            group["member_added_by"].pop(member, None)

        save_groups_storage()
        logger.info(f"移除群组成员成功: {group_id}, 成员: {member[:8]}..., 操作者: {operator[:8]}...")
        return True
    return False


def get_group(group_id: str) -> Optional[Dict]:
    """获取群组信息"""
    group = groups_storage.get(group_id)
    if group is not None:
        _ensure_assistants_field(group)
    return group


def get_user_groups(imei: str) -> List[Dict]:
    """获取用户所在的所有群组"""
    key = str(imei or "").strip()
    if not key:
        return []
    ensure_default_group_for_user(key)
    # groups_storage 为唯一真相：每次从主数据现算，避免缓存为空/脏数据导致群列表异常。
    groups = []
    for gid, group in groups_storage.items():
        _ensure_assistants_field(group)
        members = [str(x).strip() for x in (group.get("members") or []) if str(x).strip()]
        creator = str(group.get("creator_imei") or "").strip()
        if key in members or key == creator:
            groups.append(group)
    return groups


def update_group_free_discovery(group_id: str, imei: str, enabled: bool) -> bool:
    """更新群组自由发现开关（仅群主可操作）。"""
    group = groups_storage.get(group_id)
    if not group:
        return False
    _ensure_assistants_field(group)
    operator = str(imei or "").strip()
    if operator != str(group.get("creator_imei") or "").strip():
        logger.warning(f"非群主无权更新自由发现开关: {operator[:8]}..., 群组: {group_id}")
        return False
    target = bool(enabled)
    if bool(group.get("free_discovery", False)) == target:
        return True
    group["free_discovery"] = target
    save_groups_storage()
    logger.info(f"更新群组自由发现开关: group={group_id}, enabled={target}")
    return True


def update_group_assistant_muted(group_id: str, imei: str, enabled: bool) -> bool:
    """更新群组助手禁言开关（仅群主可操作）。"""
    group = groups_storage.get(group_id)
    if not group:
        return False
    _ensure_assistants_field(group)
    operator = str(imei or "").strip()
    if operator != str(group.get("creator_imei") or "").strip():
        logger.warning(f"非群主无权更新助手禁言开关: {operator[:8]}..., 群组: {group_id}")
        return False
    target = bool(enabled)
    if bool(group.get("assistant_muted", False)) == target:
        return True
    group["assistant_muted"] = target
    save_groups_storage()
    logger.info(f"更新群组助手禁言开关: group={group_id}, enabled={target}")
    return True


def update_group_workflow_mode(group_id: str, imei: str, enabled: bool) -> bool:
    """更新群组编排模式开关（仅群主可操作）。"""
    group = groups_storage.get(group_id)
    if not group:
        return False
    _ensure_assistants_field(group)
    operator = str(imei or "").strip()
    if operator != str(group.get("creator_imei") or "").strip():
        logger.warning(f"非群主无权更新群组编排模式开关: {operator[:8]}..., 群组: {group_id}")
        return False
    target = bool(enabled)
    if bool(group.get("workflow_mode", False)) == target:
        return True
    group["workflow_mode"] = target
    save_groups_storage()
    logger.info(f"更新群组编排模式开关: group={group_id}, enabled={target}")
    return True


def get_group_members(group_id: str) -> List[str]:
    """获取群组成员列表（不包括小助手）"""
    group = groups_storage.get(group_id)
    if not group:
        return []
    return group.get("members", [])


def set_assistant_enabled(group_id: str, imei: str, enabled: bool) -> bool:
    """设置群组是否启用自动执行小助手。enabled=true 添加，enabled=false 移除。"""
    if group_id not in groups_storage:
        return False
    if enabled:
        return add_group_assistant(group_id, imei, DEFAULT_ASSISTANT_ID, assistant_config=None)
    return remove_group_assistant(group_id, imei, DEFAULT_ASSISTANT_ID)


def add_group_assistant(
    group_id: str,
    imei: str,
    assistant_id: str,
    assistant_config: Optional[Dict] = None,
) -> bool:
    """添加群组小助手（群成员可添加）
    若 assistant_config 非空（自定义小助手），则存储配置供群成员同步使用
    """
    if group_id not in groups_storage:
        return False
    group = groups_storage[group_id]
    _ensure_assistants_field(group)
    _ensure_group_permission_meta(group)
    operator = str(imei or "").strip()
    if operator not in group.get("members", []):
        logger.warning(f"非群成员无权添加小助手: {operator[:8]}..., 群组: {group_id}")
        return False
    creator_imei = (
        (assistant_config or {}).get("creator_imei")
        or operator
    )
    display_id_from_client = str((assistant_config or {}).get("displayId") or "").strip()
    canonical_display_id = _resolve_creator_assistant_display_id(
        str(creator_imei),
        assistant_id,
        (assistant_config or {}).get("baseUrl") if isinstance(assistant_config, dict) else None,
    )
    display_id = canonical_display_id or display_id_from_client
    final_assistant_id = assistant_id
    assistants = group["assistants"]
    cfg_map = group.get("assistant_configs") or {}
    # 创建侧 displayId 是稳定唯一标识：同一个 displayId 视为同一助手，禁止重复添加。
    if display_id:
        for aid in assistants:
            cfg = cfg_map.get(aid) or {}
            if str(cfg.get("displayId") or "").strip() == display_id:
                logger.info(
                    f"群助手已存在(同 displayId)，忽略重复添加: {group_id}, assistant={aid}, displayId={display_id}"
                )
                return False
    if assistant_id in assistants:
        existing_cfg = cfg_map.get(assistant_id) or {}
        existing_creator = (
            str(existing_cfg.get("creator_imei") or "").strip()
            or str((group.get("assistant_added_by") or {}).get(assistant_id) or "").strip()
            or str(group.get("creator_imei") or "").strip()
        )
        # 同创建者重复添加：保持幂等，不再新增实例。
        if existing_creator == str(creator_imei).strip():
            if assistant_config:
                logger.info(
                    f"群助手已存在(同创建者)，忽略重复添加: {group_id}, assistant={assistant_id}, creator={existing_creator[:8]}..."
                )
            return False
        # 不同创建者同 assistant_id：派生实例 id，允许并存。
        # 优先使用创建侧 displayId 作为 owner_key，确保跨端稳定一致。
        owner_key = display_id or str(creator_imei)
        final_assistant_id = _build_group_assistant_instance_id(assistant_id, owner_key, assistants)

    config_updated = False
    if assistant_config and final_assistant_id not in ("assistant", "skill_learning", "chat_assistant", "customer_service"):
        if "assistant_configs" not in group:
            group["assistant_configs"] = {}
        group["assistant_configs"][final_assistant_id] = {
            "baseUrl": assistant_config.get("baseUrl") or "",
            "name": assistant_config.get("name") or "小助手",
            "capabilities": assistant_config.get("capabilities") or ["chat"],
            "intro": assistant_config.get("intro") or "",
            "avatar": assistant_config.get("avatar") or "",
            "multiSession": assistant_config.get("multiSession", True),
            "displayId": display_id,
            "rolePrompt": assistant_config.get("rolePrompt") or "",
            "creator_imei": str(assistant_config.get("creator_imei") or operator).strip(),
            "creator_display_id": display_id,
        }
        config_updated = True
    if final_assistant_id not in assistants:
        assistants.append(final_assistant_id)
        group.setdefault("assistant_added_by", {})[final_assistant_id] = operator
        group["assistant_enabled"] = True
        save_groups_storage()
        logger.info(
            f"添加群组小助手成功: {group_id}, assistant={assistant_id}, instance={final_assistant_id}, operator={operator[:8]}..."
        )
        return True
    if config_updated:
        save_groups_storage()
    return False


def update_group_assistant_config(
    group_id: str,
    imei: str,
    assistant_id: str,
    assistant_config: Dict,
) -> bool:
    """更新群组小助手的配置（群主可操作；非群主仅可禁言自己的助手）。"""
    if group_id not in groups_storage:
        return False
    group = groups_storage[group_id]
    _ensure_assistants_field(group)
    _ensure_group_permission_meta(group)
    operator = str(imei or "").strip()
    if not operator:
        return False
    assistants = group["assistants"]
    if assistant_id not in assistants:
        return False
    requested_keys = {k for k, v in (assistant_config or {}).items() if v is not None}
    is_owner = group.get("creator_imei") == operator
    if not is_owner:
        if operator not in group.get("members", []):
            logger.warning(f"非群成员无权更新小助手配置: {operator[:8]}..., 群组: {group_id}")
            return False
        # 非群主仅允许操作 assistantMuted，且只能修改自己的助手。
        if not requested_keys or any(k != "assistantMuted" for k in requested_keys):
            logger.warning(f"非群主尝试更新受限字段: {operator[:8]}..., 群组: {group_id}, keys={sorted(requested_keys)}")
            return False
        cfg_map = group.get("assistant_configs") or {}
        cfg = cfg_map.get(assistant_id) or {}
        creator = str(cfg.get("creator_imei") or "").strip()
        added_by = str((group.get("assistant_added_by") or {}).get(assistant_id) or "").strip()
        if creator != operator and added_by != operator:
            logger.warning(
                f"非群主仅可禁言自己的助手: {operator[:8]}..., 群组={group_id}, assistant={assistant_id}, creator={creator[:8]}..., added_by={added_by[:8]}..."
            )
            return False
    is_builtin = assistant_id in ("assistant", "skill_learning", "chat_assistant", "customer_service")
    if is_builtin:
        # 内置助手仅允许更新群内角色提示词与单助手禁言，避免覆盖其固定能力与基础配置
        assistant_config = {
            k: v
            for k, v in (assistant_config or {}).items()
            if k in {"rolePrompt", "assistantMuted"} and v is not None
        }
        if not assistant_config:
            return False
    if "assistant_configs" not in group:
        group["assistant_configs"] = {}
    cfg = group["assistant_configs"].get(assistant_id, {})
    merged = dict(cfg)
    for k, v in assistant_config.items():
        if v is not None:
            if k == "assistantMuted":
                merged[k] = bool(v)
                continue
            merged[k] = v
    if not is_builtin and "capabilities" not in merged:
        merged["capabilities"] = ["chat"]
    group["assistant_configs"][assistant_id] = merged
    save_groups_storage()
    logger.info(f"更新群组小助手配置: {group_id}, assistant={assistant_id}")
    return True


def remove_group_assistant(group_id: str, imei: str, assistant_id: str) -> bool:
    """移除群组小助手（群主可移除任意助手；非群主仅可移除自己拉入的助手）"""
    if group_id not in groups_storage:
        return False
    group = groups_storage[group_id]
    _ensure_assistants_field(group)
    _ensure_group_permission_meta(group)
    operator = str(imei or "").strip()
    if operator not in group.get("members", []):
        logger.warning(f"非群成员无权移除小助手: {operator[:8]}..., 群组: {group_id}")
        return False
    assistants = group["assistants"]
    if assistant_id in assistants:
        is_owner = operator == group.get("creator_imei")
        added_by = (group.get("assistant_added_by") or {}).get(assistant_id, "")
        if not is_owner and added_by != operator:
            logger.warning(
                f"非群主仅可移除自己拉入助手: {group_id}, operator={operator[:8]}..., assistant={assistant_id}, added_by={str(added_by)[:8]}..."
            )
            return False
        assistants.remove(assistant_id)
        if "assistant_added_by" in group:
            group["assistant_added_by"].pop(assistant_id, None)
        group["assistant_enabled"] = len(assistants) > 0
        if "assistant_configs" in group and assistant_id in group["assistant_configs"]:
            del group["assistant_configs"][assistant_id]
        save_groups_storage()
        logger.info(f"移除群组小助手成功: {group_id}, assistant={assistant_id}, operator={operator[:8]}...")
        return True
    return False


def quit_group(group_id: str, imei: str) -> bool:
    """普通成员退出群组；群主不可退出（需解散群组）。"""
    group = groups_storage.get(group_id)
    if not group:
        return False
    operator = str(imei or "").strip()
    if not operator:
        return False
    if operator == group.get("creator_imei"):
        return False
    if operator not in group.get("members", []):
        return False
    _ensure_group_permission_meta(group)
    group["members"].remove(operator)
    if "member_added_by" in group:
        group["member_added_by"].pop(operator, None)
    save_groups_storage()
    logger.info(f"成员退出群组: {group_id}, imei={operator[:8]}...")
    return True


def dissolve_group(group_id: str, imei: str) -> bool:
    """群主解散群组。"""
    group = groups_storage.get(group_id)
    if not group:
        return False
    operator = str(imei or "").strip()
    if operator != group.get("creator_imei"):
        return False
    groups_storage.pop(group_id, None)
    save_groups_storage()
    logger.info(f"群组已解散: {group_id}, creator={operator[:8]}...")
    return True


def adapt_user_group_assistant_ids(imei: str) -> Dict[str, int]:
    """
    为指定用户适配其所在群组中的助手身份字段：
    - 对齐 assistant_configs.displayId 到创建侧 canonical displayId
    - 补齐 creator_display_id
    - 若可确定归属且缺失 creator_imei，则补为当前 imei
    """
    load_groups_storage()
    imei = str(imei or "").strip()
    if not imei:
        return {"groups_total": 0, "groups_updated": 0, "configs_updated": 0}
    mine = get_custom_assistants(imei)
    id_to_display: Dict[str, str] = {}
    base_to_display: Dict[str, str] = {}
    display_set: set[str] = set()
    for a in mine:
        if not isinstance(a, dict):
            continue
        aid = str(a.get("id") or "").strip()
        did = str(a.get("displayId") or "").strip()
        burl = _norm_url(a.get("baseUrl") or "")
        if aid and did:
            id_to_display[aid] = did
        if burl and did:
            base_to_display[burl] = did
        if did:
            display_set.add(did)
    groups_total = 0
    groups_updated = 0
    configs_updated = 0
    for group in groups_storage.values():
        members = [str(x).strip() for x in group.get("members", []) if str(x).strip()]
        if imei not in members:
            continue
        groups_total += 1
        cfgs = group.get("assistant_configs")
        if not isinstance(cfgs, dict):
            continue
        touched_group = False
        for aid, cfg in list(cfgs.items()):
            if not isinstance(cfg, dict):
                continue
            aid_str = str(aid or "").strip()
            aid_base = aid_str.split("__", 1)[0]
            cfg_creator = str(cfg.get("creator_imei") or "").strip()
            cfg_display = str(cfg.get("displayId") or "").strip()
            cfg_creator_display = str(cfg.get("creator_display_id") or "").strip()
            cfg_url = _norm_url(cfg.get("baseUrl") or "")
            canonical = (
                id_to_display.get(aid_str)
                or id_to_display.get(aid_base)
                or base_to_display.get(cfg_url, "")
                or (cfg_display if cfg_display in display_set else "")
            )
            matched_to_me = bool(canonical)
            if canonical and cfg_display != canonical:
                cfg["displayId"] = canonical
                configs_updated += 1
                touched_group = True
            if canonical and cfg_creator_display != canonical:
                cfg["creator_display_id"] = canonical
                configs_updated += 1
                touched_group = True
            if matched_to_me and not cfg_creator:
                cfg["creator_imei"] = imei
                configs_updated += 1
                touched_group = True
            cfgs[aid] = cfg
        if touched_group:
            groups_updated += 1
    if groups_updated > 0:
        save_groups_storage()
        logger.info(
            "已适配群助手ID: imei=%s..., groups_updated=%s, configs_updated=%s",
            imei[:8],
            groups_updated,
            configs_updated,
        )
    return {
        "groups_total": groups_total,
        "groups_updated": groups_updated,
        "configs_updated": configs_updated,
    }


def is_assistant_mentioned(content: str) -> bool:
    """检测消息是否@了自动执行小助手（触发群内 TopoClaw 回复）"""
    mentions = ["@自动执行小助手", "@小助手", "@assistant", "@自动执行助手"]
    content_lower = (content or "").lower()
    return any(mention.lower() in content_lower for mention in mentions)


# 启动时加载数据
load_groups_storage()
