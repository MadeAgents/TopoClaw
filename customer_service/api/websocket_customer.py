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

"""WebSocket：多端实时通道（与原版 app.py 行为一致）"""
import asyncio
import json
import logging
import re
import uuid
from datetime import datetime
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from core.deps import connection_manager, conversation_logger, message_service
from core.time_utils import TZ_UTC_PLUS_8, get_now_isoformat
from services.custom_assistant_store import (
    DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID,
    get_default_topoclaw_assistant_id,
)
from services.group_service import (
    ASSISTANT_BOT_ID,
    DEFAULT_ASSISTANT_ID,
    get_group,
    is_assistant_mentioned,
)
from services.friend_dispatch import dispatch_friend_message
from services.digital_clone_reply import maybe_auto_reply_friend_message
from services.owner_feedback_service import (
    build_owner_feedback_prompt,
    dispatch_owner_feedback_message,
    format_owner_feedback_content,
    split_reply_and_owner_feedback,
)
from services.unified_message_store import (
    append_assistant_msg,
    append_assistant_or_conv_msg,
    append_cross_device_msg,
    append_custom_assistant_single_msg,
    append_group_msg,
    get_group_messages,
)
from services.user_settings_store import get_user_setting
from services.mobile_tool_bridge import resolve_pending_mobile_tool_result
from storage.cross_device import get_cross_device_messages, save_cross_device_messages

logger = logging.getLogger(__name__)
_clone_context_waiters: dict[str, asyncio.Future] = {}
_GROUP_CONTEXT_RECENT_MESSAGE_LIMIT = 10
_GROUP_PROMPT_MAX_LINE_CHARS = 240
_NO_REPLY_MARKER = "###不回复###"
_BUILTIN_ASSISTANT_INTROS = {
    "assistant": "支持手机端自动化任务，如打开应用、操作界面等。",
    "skill_learning": "负责记录和学习技能。",
    "chat_assistant": "支持通用对话聊天。",
    "customer_service": "提供人工客服支持。",
}


async def _dispatch_owner_feedback_non_blocking(
    *,
    owner_imei: str,
    content: str,
) -> None:
    try:
        await dispatch_owner_feedback_message(
            owner_imei=owner_imei,
            content=content,
            sender="TopoClaw",
        )
    except Exception as exc:
        logger.warning("投递分身主动反馈失败: owner=%s..., err=%s", owner_imei[:8], exc)


async def _request_clone_query_context(
    *,
    owner_imei: str,
    friend_imei: str,
    content: str,
    msg_type: str,
    timeout_seconds: float = 4.0,
) -> str | None:
    """向桌面端请求数字分身上下文（前端生成），超时则返回 None。"""
    if not connection_manager.is_pc_online(owner_imei):
        return None
    request_id = f"clone-ctx-{uuid.uuid4().hex}"
    loop = asyncio.get_running_loop()
    waiter: asyncio.Future = loop.create_future()
    _clone_context_waiters[request_id] = waiter
    push_msg = {
        "type": "clone_context_request",
        "request_id": request_id,
        "friend_imei": friend_imei,
        "content": content,
        "message_type": msg_type or "text",
        "timestamp": get_now_isoformat(),
    }
    try:
        pushed = await connection_manager.send_to_pc(owner_imei, push_msg)
        if not pushed:
            return None
        result = await asyncio.wait_for(waiter, timeout=timeout_seconds)
        if isinstance(result, str) and result.strip():
            return result.strip()
        return None
    except Exception:
        return None
    finally:
        _clone_context_waiters.pop(request_id, None)


_BASE64_LIKE_KEYS = {
    "imagebase64",
    "filebase64",
    "file_base64",
    "image_base64",
    "base64",
    "screenshot",
}


def _shorten_text(value: str, max_len: int = 240) -> str:
    if len(value) <= max_len:
        return value
    return f"{value[:120]}...<trimmed len={len(value)}>...{value[-60:]}"


def _looks_like_base64(value: str) -> bool:
    if len(value) < 256:
        return False
    compact = value.replace("\n", "").replace("\r", "")
    return bool(re.fullmatch(r"[A-Za-z0-9+/=]+", compact))


def _sanitize_for_log(value, key: str | None = None):
    if isinstance(value, dict):
        return {k: _sanitize_for_log(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [_sanitize_for_log(v, key) for v in value]
    if isinstance(value, str):
        lower_key = (key or "").lower()
        if lower_key in _BASE64_LIKE_KEYS or _looks_like_base64(value):
            head = value[:24]
            tail = value[-12:] if len(value) > 12 else value
            return f"[omitted base64 len={len(value)}, head={head}..., tail=...{tail}]"
        return _shorten_text(value)
    return value


def _canonicalize_conversation_id(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    if "__" in text:
        text = text.split("__", 1)[0].strip()
    if text.endswith("_local"):
        text = text[:-6].strip()
    if text.startswith("group_group_"):
        text = f"group_{text[len('group_group_'):]}"
    if text.startswith("friend_friend_"):
        text = f"friend_{text[len('friend_friend_'):]}"
    if text.startswith("group_") and ":" in text:
        text = text.split(":", 1)[0].strip()
    if text.startswith("friend_") and ":" in text:
        text = text.split(":", 1)[0].strip()
    return text


def _extract_conversation_id_from_text(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    if "__" in text:
        # Multi-session route: "<conversation_id>__<session_id>".
        text = text.split("__", 1)[0].strip()
    if (
        text.startswith("friend_")
        or text.startswith("group_")
        or text.startswith("custom_")
        or text == "assistant"
    ):
        return _canonicalize_conversation_id(text)
    if text.endswith("_local"):
        maybe = text[:-6].strip()
        if (
            maybe.startswith("friend_")
            or maybe.startswith("group_")
            or maybe.startswith("custom_")
            or maybe == "assistant"
        ):
            return _canonicalize_conversation_id(maybe)
    if "_" in text:
        # Strip actor prefix, e.g. "<imei>_friend_xxx".
        _, tail = text.split("_", 1)
        tail = tail.strip()
        if (
            tail.startswith("friend_")
            or tail.startswith("group_")
            or tail.startswith("custom_")
            or tail == "assistant"
        ):
            return _canonicalize_conversation_id(tail)
    m = re.search(
        r"(friend_[A-Za-z0-9:\-]+|group_[A-Za-z0-9:_\-]+|custom_[A-Za-z0-9:_\-]+)",
        text,
    )
    if m:
        return _canonicalize_conversation_id(m.group(1))
    return ""


def _normalize_conversation_id(raw_conversation_id: Any, *, fallback_thread_id: Any = "") -> str:
    conv = _extract_conversation_id_from_text(str(raw_conversation_id or "").strip())
    if conv:
        return conv
    fallback = _extract_conversation_id_from_text(str(fallback_thread_id or "").strip())
    if fallback:
        return fallback
    raw = str(raw_conversation_id or "").strip()
    return raw or "assistant"


def _assistant_base_name(group: dict | None, assistant_id: str) -> str:
    if assistant_id == DEFAULT_ASSISTANT_ID:
        return "自动执行小助手"
    cfg = ((group or {}).get("assistant_configs") or {}).get(assistant_id) or {}
    if _is_topoclaw_group_assistant(assistant_id, cfg):
        creator_label = _resolve_group_assistant_creator_label(cfg)
        if creator_label:
            return f"TopoClaw（{creator_label}）"
        return "TopoClaw"
    return str(cfg.get("name") or assistant_id)


def _build_assistant_token_map(group: dict | None, assistants: list[str]) -> dict[str, str]:
    """构建 @ 解析 token->assistant_id 映射；重名 token 会标记冲突并移除。"""
    token_map: dict[str, str] = {}
    ambiguous: set[str] = set()
    base_names = {aid: _assistant_base_name(group, aid) for aid in assistants}
    name_count: dict[str, int] = {}
    for n in base_names.values():
        name_count[n] = (name_count.get(n, 0) or 0) + 1

    def _put(token: str, aid: str) -> None:
        t = _normalize_mention_token(token)
        if not t:
            return
        if t in token_map and token_map[t] != aid:
            ambiguous.add(t)
            return
        token_map[t] = aid

    def _creator_imei_and_nick(cfg: dict) -> tuple[str, str]:
        raw_imei = str(cfg.get("creator_imei") or "").strip()
        raw_nick = str(cfg.get("creator_nickname") or "").strip()
        imei = raw_imei
        if "·" in imei:
            imei = imei.split("·", 1)[0].strip()
        if "(" in imei:
            imei = imei.split("(", 1)[0].strip()
        nick = raw_nick
        if not nick and raw_imei:
            m = re.search(r"\(([^()]+)\)\s*$", raw_imei)
            if m and m.group(1).strip():
                nick = m.group(1).strip()
        return imei, nick

    for aid in assistants:
        base = base_names.get(aid, aid)
        _put(aid, aid)
        _put(base, aid)
        cfg = ((group or {}).get("assistant_configs") or {}).get(aid) or {}
        creator_imei, creator_nick = _creator_imei_and_nick(cfg)
        if creator_nick:
            _put(f"{base}({creator_nick})", aid)
        if creator_imei:
            _put(f"{base}({creator_imei})", aid)
        if (name_count.get(base) or 0) > 1:
            _put(f"{base}({aid})", aid)

    for t in ambiguous:
        token_map.pop(t, None)
    return token_map


def _normalize_mention_token(token: str) -> str:
    text = str(token or "").strip().lower()
    if not text:
        return ""
    # 统一中英文括号与空白，避免 @TopoClaw(小B) / @TopoClaw（小B） 解析不一致
    text = text.replace("（", "(").replace("）", ")")
    text = text.replace("\u3000", " ")
    text = re.sub(r"\s+", "", text)
    return text.strip(",，:：。；;!！?？")


def _normalize_group_assistant_creator_imei(raw_creator: str) -> str:
    creator = str(raw_creator or "").strip()
    if "·" in creator:
        creator = creator.split("·", 1)[0].strip()
    if "(" in creator:
        creator = creator.split("(", 1)[0].strip()
    return creator


def _resolve_group_assistant_creator_label(cfg: dict) -> str:
    """解析群助手归属用户展示名：优先 creator_nickname，其次资料昵称，最后短 imei。"""
    creator_nickname = str(cfg.get("creator_nickname") or "").strip()
    if creator_nickname:
        return creator_nickname
    creator_imei = _normalize_group_assistant_creator_imei(cfg.get("creator_imei") or "")
    if not creator_imei:
        return ""
    try:
        from storage.profiles import profiles_storage

        profile = profiles_storage.get(creator_imei, {}) or {}
        profile_name = str(profile.get("name") or "").strip()
        if profile_name:
            return profile_name
    except Exception:
        pass
    return f"{creator_imei[:8]}..."


def _build_group_member_token_map(group: dict) -> dict[str, str]:
    """构建群成员 @token -> member_imei 映射（重名 token 自动去歧义）。"""
    from storage.profiles import profiles_storage

    members = [str(x).strip() for x in (group.get("members") or []) if str(x).strip()]
    token_map: dict[str, str] = {}
    ambiguous: set[str] = set()

    def _put(token: str, member_imei: str) -> None:
        t = _normalize_mention_token(token)
        if not t:
            return
        if t in token_map and token_map[t] != member_imei:
            ambiguous.add(t)
            return
        token_map[t] = member_imei

    for member_imei in members:
        if member_imei == ASSISTANT_BOT_ID:
            continue
        _put(member_imei, member_imei)
        nickname = str((profiles_storage.get(member_imei, {}) or {}).get("name") or "").strip()
        if nickname:
            _put(nickname, member_imei)
            _put(f"{nickname}({member_imei})", member_imei)

    for t in ambiguous:
        token_map.pop(t, None)
    return token_map


def _extract_mentioned_member_imei(content: str, group: dict) -> str | None:
    token_map = _build_group_member_token_map(group)
    if not token_map:
        return None
    for token in re.findall(r"@([^\s@]+)", content or ""):
        hit = token_map.get(_normalize_mention_token(token))
        if hit:
            return hit
    return None


def _is_topoclaw_group_assistant(assistant_id: str, cfg: dict) -> bool:
    base_url = str(cfg.get("baseUrl") or "").strip().lower().rstrip("/")
    aid = str(assistant_id or "").strip().lower()
    return (
        base_url == "topoclaw://relay"
        or aid == DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID
        or aid.startswith(f"{DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID}__")
        or "topoclaw" in aid
    )


def _is_group_manager_group_assistant(assistant_id: str, cfg: dict) -> bool:
    aid = str(assistant_id or "").strip().lower()
    base_url = str(cfg.get("baseUrl") or "").strip().lower()
    capabilities = {
        str(x).strip().lower()
        for x in (cfg.get("capabilities") or [])
        if str(x).strip()
    }
    return (
        aid == "custom_groupmanager"
        or ":18791" in base_url
        or "group_manager" in capabilities
    )


def _is_group_assistant_muted(group: dict | None, assistant_id: str) -> bool:
    cfg = ((group or {}).get("assistant_configs") or {}).get(assistant_id) or {}
    return bool(cfg.get("assistantMuted", False))


def _find_member_topoclaw_assistant_in_group(group: dict, member_imei: str) -> str | None:
    """按群成员 imei 反查其在群内的 TopoClaw 分身 assistant_id。"""
    target = str(member_imei or "").strip()
    if not target:
        return None
    assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
    if not assistants:
        return None
    cfg_map = group.get("assistant_configs") or {}

    default_topoclaw_id = get_default_topoclaw_assistant_id(target)
    if default_topoclaw_id and default_topoclaw_id in assistants:
        cfg = cfg_map.get(default_topoclaw_id) or {}
        creator = _normalize_group_assistant_creator_imei(cfg.get("creator_imei") or "")
        if creator == target or not creator:
            return default_topoclaw_id

    for aid in assistants:
        cfg = cfg_map.get(aid) or {}
        creator = _normalize_group_assistant_creator_imei(cfg.get("creator_imei") or "")
        if creator != target:
            continue
        if _is_topoclaw_group_assistant(aid, cfg):
            return aid
    return None


def _extract_mentioned_assistant(content: str, assistants: list[str], group: dict | None = None) -> str | None:
    """解析要 @ 的群内助手 id；内置 assistant 按旧版行为带 agent_id 转发 TopoClaw。
    
    无论是否匹配，始终返回一个有效的 assistant_id（用于 _dispatch_group_assistant_reply 等需要指定助手的场景）。
    """
    text = content or ""
    normalized = [str(x).strip() for x in assistants if str(x).strip()]
    if not normalized:
        return None

    token_map = _build_assistant_token_map(group, normalized)
    explicit = re.search(r"@小助手[\s:：,，]*@?([^\s@]+)", text)
    if explicit:
        candidate = _normalize_mention_token(explicit.group(1))
        hit = token_map.get(candidate)
        if hit:
            return hit

    for token in re.findall(r"@([^\s@]+)", text):
        hit = token_map.get(_normalize_mention_token(token))
        if hit:
            return hit

    if DEFAULT_ASSISTANT_ID in normalized:
        return DEFAULT_ASSISTANT_ID
    return normalized[0]


def _extract_explicitly_mentioned_assistant(
    content: str,
    assistants: list[str],
    group: dict | None = None,
) -> str | None:
    """只提取明确被 @ 的助手 ID。
    
    - 如果消息中明确 @ 了某个在 assistants 列表中的助手，返回该助手 ID
    - 如果没有 @ 任何助手，或 @ 的助手不在列表中，返回 None
    
    用于决定是广播给所有助手（None）还是只发给特定助手。
    """
    text = content or ""
    normalized = [str(x).strip() for x in assistants if str(x).strip()]
    if not normalized:
        return None

    # 匹配 "@小助手 @xxx" 或 "@小助手 xxx" 格式
    token_map = _build_assistant_token_map(group, normalized)
    explicit = re.search(r"@小助手[\s:：,，]*@?([^\s@]+)", text)
    if explicit:
        candidate = _normalize_mention_token(explicit.group(1))
        hit = token_map.get(candidate)
        if hit:
            return hit

    # 匹配直接 @ 助手 ID 的格式
    for token in re.findall(r"@([^\s@]+)", text):
        hit = token_map.get(_normalize_mention_token(token))
        if hit:
            return hit

    # 没有明确 @ 任何助手
    return None


def _clean_group_prompt(content: str, assistant_id: str) -> str:
    text = (content or "").strip()
    text = re.sub(r"@小助手", "", text)
    if assistant_id:
        text = re.sub(rf"@{re.escape(assistant_id)}", "", text)
    return re.sub(r"\s+", " ", text).strip()


def _assistant_display_name(group: dict, assistant_id: str) -> str:
    if assistant_id == DEFAULT_ASSISTANT_ID:
        base_name = "自动执行小助手"
    else:
        cfg = (group.get("assistant_configs") or {}).get(assistant_id) or {}
        if _is_topoclaw_group_assistant(assistant_id, cfg):
            creator_label = _resolve_group_assistant_creator_label(cfg)
            return f"TopoClaw（{creator_label}）" if creator_label else "TopoClaw"
        base_name = str(cfg.get("name") or assistant_id)
    # 群内同名助手冲突时，展示为 名称(id)，方便区分并支持精确 @。
    assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
    if not assistants:
        return base_name
    same_name_ids = []
    for aid in assistants:
        if aid == DEFAULT_ASSISTANT_ID:
            n = "自动执行小助手"
        else:
            c = (group.get("assistant_configs") or {}).get(aid) or {}
            n = str(c.get("name") or aid)
        if n == base_name:
            same_name_ids.append(aid)
    if len(same_name_ids) > 1:
        return f"{base_name}({assistant_id})"
    return base_name


def _inject_group_role_prompt_for_assistant(
    group: dict,
    assistant_id: str,
    query: str,
) -> str:
    """将群内为助手配置的 rolePrompt 注入 query，并显式区分 prompt 与本轮请求。"""
    cfg = (group.get("assistant_configs") or {}).get(assistant_id) or {}
    aid = str(assistant_id or "").strip().lower()
    base_url = str(cfg.get("baseUrl") or "").strip().lower()
    capabilities = {str(x).strip().lower() for x in (cfg.get("capabilities") or []) if str(x).strip()}
    is_group_manager_builtin = (
        aid == "custom_groupmanager"
        or ":18791" in base_url
        or "group_manager" in capabilities
    )
    is_topoclaw_builtin = _is_topoclaw_group_assistant(assistant_id, cfg)
    creator = (
        str(cfg.get("creator_nickname") or "").strip()
        or str(cfg.get("creator_imei") or "").strip()
        or "当前用户"
    )
    topoclaw_identity_line = (
        f"你是{creator}的数字分身（TopoClaw），请始终以该身份回答。"
        if is_topoclaw_builtin
        else ""
    )
    group_name = str(group.get("name") or "").strip()
    group_label = f"「{group_name}」" if group_name else "当前群聊"
    group_manager_identity_line = (
        f"你是{group_label}的群组管理助手（GroupManager），职责是组织讨论、协调发言、在需要时@具体助手或成员，不要把自己当作任何人的 TopoClaw 数字分身。"
        if is_group_manager_builtin
        else ""
    )
    identity_line = "\n".join(
        [x for x in (topoclaw_identity_line, group_manager_identity_line) if x]
    ).strip()
    feedback_prompt = (
        build_owner_feedback_prompt(
            scope_type="group",
            scope_name=str(group.get("name") or group.get("id") or "当前群聊"),
        )
        if is_topoclaw_builtin
        else ""
    )
    role_prompt = str(cfg.get("rolePrompt") or "").strip()
    user_query = (query or "").strip()
    if not role_prompt:
        parts = [x for x in (identity_line, feedback_prompt, user_query) if x]
        return "\n\n".join(parts).strip()
    if identity_line and identity_line not in role_prompt:
        role_prompt = f"{identity_line}\n{role_prompt}".strip()
    if feedback_prompt and feedback_prompt not in role_prompt:
        role_prompt = f"{role_prompt}\n\n{feedback_prompt}".strip()
    return (
        "【用户设置角色提示词 ROLE_PROMPT】\n"
        f"{role_prompt}\n\n"
        "【用户本轮请求 QUERY】\n"
        f"{user_query}\n\n"
        "请严格区分：ROLE_PROMPT 是长期角色约束；QUERY 是本轮待处理请求。"
    )


def _resolve_group_assistant_route_user(
    group: dict,
    assistant_id: str,
    sender_imei: str,
) -> str:
    """群助手路由：优先按助手创建者转发，缺失时回退发送者。"""
    cfg = (group.get("assistant_configs") or {}).get(assistant_id) or {}
    raw_creator = str(cfg.get("creator_imei") or "").strip()
    creator = _normalize_group_assistant_creator_imei(raw_creator)
    members = {str(x).strip() for x in (group.get("members") or []) if str(x).strip()}
    if creator and creator in members:
        return creator
    return sender_imei


def _trim_text_for_prompt(value: str, max_chars: int = _GROUP_PROMPT_MAX_LINE_CHARS) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    text = re.sub(r"\s+", " ", text)
    if len(text) <= max_chars:
        return text
    return f"{text[: max_chars - 1]}…"


def _contains_no_reply_marker(value: str) -> bool:
    return _NO_REPLY_MARKER in str(value or "")


def _strip_no_reply_marker(value: str) -> str:
    text = str(value or "").replace(_NO_REPLY_MARKER, "")
    return text.strip()


def _resolve_group_member_name(member_imei: str, profiles_storage: dict) -> str:
    profile = profiles_storage.get(member_imei, {}) or {}
    nickname = str(profile.get("name") or "").strip()
    return nickname or f"{member_imei[:8]}..."


def _build_group_recent_messages_context(
    group_id: str,
    group: dict,
    profiles_storage: dict,
    limit: int = _GROUP_CONTEXT_RECENT_MESSAGE_LIMIT,
) -> list[str]:
    if not group_id:
        return []
    try:
        recent_msgs, _ = get_group_messages(group_id, None, limit, None)
    except Exception as e:
        logger.warning(f"获取群聊最近消息失败: group={group_id}, err={e}")
        return []
    if not recent_msgs:
        return []

    assistants = {str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()}
    lines = ["【群聊最近消息（按时间从旧到新）】"]
    for msg in reversed(recent_msgs):
        sender_imei = str(msg.get("sender_imei") or "").strip()
        sender = str(msg.get("sender") or "").strip()
        if not sender:
            if sender_imei in assistants:
                sender = _assistant_display_name(group, sender_imei)
            elif sender_imei and sender_imei != ASSISTANT_BOT_ID:
                sender = _resolve_group_member_name(sender_imei, profiles_storage)
            else:
                sender = "系统"

        content = _trim_text_for_prompt(msg.get("content") or "")
        if not content:
            msg_type = str(msg.get("message_type") or "").strip().lower()
            if msg.get("imageBase64") or msg_type in {"image", "img", "file"}:
                content = "[图片/文件消息]"
            else:
                content = "[空消息]"
        lines.append(f"- {sender}: {content}")
    return lines


def _build_context_text_for_agent(
    sender_imei: str,
    group: dict | None = None,
    group_id: str | None = None,
) -> str:
    """构建发送给 agent 的上下文文本，包含用户创建的所有 agent 和群聊信息。
    
    Args:
        sender_imei: 发送者 IMEI
        group: 群组信息（群聊时传入，私聊时为 None）
    
    Returns:
        上下文文本，可直接拼接到 message 前面
    """
    from services.custom_assistant_store import get_custom_assistants
    from storage.profiles import profiles_storage
    
    context_parts = []
    
    # 1. 添加用户创建的 agent 列表
    try:
        user_agents = get_custom_assistants(sender_imei)
        if user_agents:
            agent_descs = []
            for a in user_agents:
                aid = str(a.get("id", ""))
                name = str(a.get("name", ""))
                intro = str(a.get("intro", ""))
                if aid:
                    desc = f"@{aid}"
                    if name and name != aid:
                        desc += f"({name})"
                    if intro:
                        desc += f" - {intro}"
                    agent_descs.append(desc)
            
            if agent_descs:
                context_parts.append(f"【用户创建的助手】")
                context_parts.append("用户创建了以下助手，你可以根据需要建议用户 @ 对应的助手：")
                for desc in agent_descs:
                    context_parts.append(f"  - {desc}")
    except Exception as e:
        logger.warning(f"获取用户 agent 列表失败: {e}")
    
    # 2. 如果是群聊，添加群聊上下文
    if group:
        context_parts.append(f"\n【当前群聊信息】")
        group_name = group.get("name", "未命名群组")
        context_parts.append(f"群名称: {group_name}")
        
        # 群成员
        members = [str(x).strip() for x in (group.get("members") or []) if str(x).strip()]
        if members:
            context_parts.append("群成员（可通过 @昵称 或 @IMEI 指定 ta 回答）:")
            for member_imei in members:
                if member_imei == ASSISTANT_BOT_ID:
                    continue
                nickname = _resolve_group_member_name(member_imei, profiles_storage)
                context_parts.append(f"  - {nickname}（IMEI: {member_imei}）")
        
        # 群内助手
        assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
        assistant_configs = group.get("assistant_configs", {})
        if assistants:
            context_parts.append("群内助手（可通过 @助手名 或 @助手ID 指定其回答）:")
            for aid in assistants:
                cfg = assistant_configs.get(aid, {})
                name = _assistant_display_name(group, aid)
                intro = _trim_text_for_prompt(cfg.get("intro") or "")
                if not intro:
                    base_aid = aid.split("__", 1)[0]
                    intro = _BUILTIN_ASSISTANT_INTROS.get(base_aid, "")
                line = f"  - {name}（ID: {aid}）"
                if intro:
                    line += f"：{intro}"
                context_parts.append(line)

            context_parts.append(
                "协作提醒：当问题更适合其他对象处理时，请明确建议用户使用 @某助手 或 @某成员 来指定回答对象。"
            )

        gid = str(group_id or group.get("id") or "").strip()
        recent_lines = _build_group_recent_messages_context(gid, group, profiles_storage)
        if recent_lines:
            context_parts.append("")
            context_parts.extend(recent_lines)
        context_parts.append(
            f"回复策略：若你判断当前消息与你无关、无需你发言，请仅输出“{_NO_REPLY_MARKER}”，不要输出其他内容。"
        )
    
    # 解析发送者昵称，标注当前消息来源
    sender_name = _resolve_group_member_name(sender_imei, profiles_storage)
    
    if context_parts:
        return "\n".join(context_parts) + f"\n\n【来自群成员「{sender_name}」（IMEI: {sender_imei}）的消息】\n"
    return f"【来自群成员「{sender_name}」（IMEI: {sender_imei}）的消息】\n"



async def _handle_single_assistant_broadcast(
    app: FastAPI,
    hub,
    route_imei_id: str,
    group: dict,
    group_id: str,
    sender_imei: str,
    content: str,
    msg_type: str,
    image_base64: str | None,
    assistant_id: str,
    forward_reply_to_group: bool = True,
) -> None:
    """向单个助手发送群聊广播消息并处理回复。"""

    # 兼容旧签名：当前 route 由助手归属动态决定，而非固定 sender_imei。
    del route_imei_id
    request_id = f"group-broadcast-{uuid.uuid4().hex}"
    thread_id = f"group_{group_id}:{assistant_id}"
    route_user = _resolve_group_assistant_route_user(group, assistant_id, sender_imei)
    route_imei_id = connection_manager.resolve_user_adapter_route(route_user) or route_user

    # 构建上下文文本（包含用户 agent 列表和群聊信息），拼接到消息前面
    context_text = _build_context_text_for_agent(sender_imei, group, group_id)
    query_with_role = _inject_group_role_prompt_for_assistant(group, assistant_id, content)
    full_message = context_text + query_with_role  # 保持原始 @ 信息 + 注入角色提示词

    # 构造群聊广播消息payload
    payload: dict = {
        "type": "chat",  # 使用 chat 类型以便接收回复
        "request_id": request_id,
        "agent_id": assistant_id,
        "thread_id": thread_id,
        "message": full_message,
        "message_type": msg_type,
        "images": [],
        # 关键：显式携带助手归属用户 imei，避免 TopoClaw 侧从 group_* thread_id 误解析为 "group"
        "imei": route_user,
    }

    if image_base64:
        payload["images"] = [image_base64]

    stream_id = None
    chunks: list[str] = []
    done_response = ""
    error_text = ""

    try:
        stream_id = await hub.send_stream_request(route_imei_id, payload)
        while True:
            event = await hub.recv_stream_event(stream_id, timeout=180.0)
            ev_type = str(event.get("type") or "")
            if ev_type == "delta":
                chunks.append(str(event.get("content") or ""))
                continue
            if ev_type == "done":
                done_response = str(event.get("response") or "")
                break
            if ev_type in {"error", "stopped"}:
                error_text = str(event.get("error") or event.get("content") or "assistant 调用失败")
                break
    except asyncio.TimeoutError:
        error_text = "assistant 响应超时"
    except Exception as exc:
        error_text = str(exc)
    finally:
        if stream_id is not None:
            await hub.close_stream_request(stream_id)

    reply = (("".join(chunks) + done_response).strip() if not error_text else "").strip()
    if not reply:
        return  # 没有回复内容，不发送群消息
    reply, owner_feedback_text = split_reply_and_owner_feedback(reply)
    assistants_set = {str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()}
    is_human_turn = sender_imei not in assistants_set
    should_emit_owner_feedback = (
        forward_reply_to_group
        and is_human_turn
        and _is_topoclaw_group_assistant(assistant_id, (group.get("assistant_configs") or {}).get(assistant_id) or {})
    )
    owner_feedback_content = ""
    if should_emit_owner_feedback and owner_feedback_text:
        owner_feedback_content = format_owner_feedback_content(
            source_type="group",
            source_name=str(group.get("name") or group_id),
            feedback_text=owner_feedback_text,
            source_user_imei=sender_imei,
        )
    if _contains_no_reply_marker(reply):
        logger.debug("助手 %s 返回不回复标记，跳过群消息: group=%s", assistant_id, group_id)
        if owner_feedback_content:
            asyncio.create_task(
                _dispatch_owner_feedback_non_blocking(
                    owner_imei=route_user,
                    content=owner_feedback_content,
                )
            )
        return
    reply = _strip_no_reply_marker(reply)
    if not reply:
        if owner_feedback_content:
            asyncio.create_task(
                _dispatch_owner_feedback_non_blocking(
                    owner_imei=route_user,
                    content=owner_feedback_content,
                )
            )
        return
    if not forward_reply_to_group:
        return
    if bool(group.get("assistant_muted", False)):
        return

    assistant_name = _assistant_display_name(group, assistant_id)
    assistant_message = {
        "type": "group_message",
        "groupId": group_id,
        "senderImei": assistant_id,
        "content": reply,
        "timestamp": get_now_isoformat(),
        "sender": assistant_name,
        "is_assistant_reply": True,
        "assistant_id": assistant_id,
    }

    msg_id = append_group_msg(
        group_id,
        assistant_id,
        reply,
        type="assistant",
        sender=assistant_name,
        assistant_id=assistant_id,
    )
    assistant_message["message_id"] = msg_id

    for member_imei in group.get("members", []):
        if member_imei != ASSISTANT_BOT_ID:
            await message_service.send_message_to_user(member_imei, assistant_message)

    logger.debug(f"助手 {assistant_id} 回复已发送到群 {group_id}")
    if owner_feedback_content:
        asyncio.create_task(
            _dispatch_owner_feedback_non_blocking(
                owner_imei=route_user,
                content=owner_feedback_content,
            )
        )
    if bool(group.get("free_discovery", False)) and not bool(group.get("assistant_muted", False)):
        asyncio.create_task(
            _notify_group_assistants(
                app,
                group=group,
                group_id=group_id,
                sender_imei=assistant_id,
                content=reply,
                msg_type="text",
                image_base64=None,
                target_assistant=None,
                sync_only=False,
                exclude_assistants={assistant_id},
            )
        )


async def _notify_group_assistants(
    app: FastAPI,
    *,
    group: dict,
    group_id: str,
    sender_imei: str,
    content: str,
    msg_type: str = "text",
    image_base64: str | None = None,
    target_assistant: str | None = None,
    sync_only: bool = False,
    exclude_assistants: set[str] | None = None,
) -> None:
    """向群组中的assistant发送消息并处理回复。
    
    如果指定了 target_assistant，只发给该助手；否则根据用户设置决定广播策略：
    - all_agents_reply=true: 广播给所有助手
    - all_agents_reply=false(默认): 只广播给默认助手(topoclaw)，其他助手只有被@才回复
    """
    assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
    if not assistants:
        return
    if bool(group.get("assistant_muted", False)):
        logger.debug("群组助手禁言开启，跳过助手分发: group=%s", group_id)
        return

    group_free_discovery = bool(group.get("free_discovery", False))
    excludes = {str(x).strip() for x in (exclude_assistants or set()) if str(x).strip()}
    if group_free_discovery:
        target_assistants = [aid for aid in assistants if aid not in excludes]
    # 如果指定了目标助手，只发给该助手
    elif target_assistant:
        if target_assistant not in assistants:
            logger.warning(f"目标助手 {target_assistant} 不在群组助手列表中，跳过发送")
            return
        if target_assistant in excludes:
            return
        target_assistants = [target_assistant]
    else:
        # 未指定目标助手时，根据用户设置决定广播策略
        all_agents_reply = get_user_setting(sender_imei, "all_agents_reply", False)
        
        if all_agents_reply:
            # 开启：广播给所有助手
            target_assistants = assistants
        else:
            # 默认关闭：只广播给“默认管理助手”。
            # 优先 GroupManager；没有 GroupManager 时再回退到 TopoClaw/assistant。
            default_assistants = []
            cfg_map = group.get("assistant_configs") or {}
            group_manager_id = None
            for aid in assistants:
                cfg = cfg_map.get(aid) or {}
                if _is_group_manager_group_assistant(aid, cfg):
                    group_manager_id = aid
                    break
            if group_manager_id:
                default_assistants.append(group_manager_id)
            else:
                topoclaw_id = get_default_topoclaw_assistant_id(sender_imei)
                if topoclaw_id and topoclaw_id in assistants:
                    default_assistants.append(topoclaw_id)
                elif DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID in assistants:
                    default_assistants.append(DEFAULT_TOPOCLAW_CUSTOM_ASSISTANT_ID)
                elif DEFAULT_ASSISTANT_ID in assistants:
                    default_assistants.append(DEFAULT_ASSISTANT_ID)
                elif assistants:
                    # 如果没有默认助手，使用第一个助手
                    default_assistants.append(assistants[0])
            
            target_assistants = default_assistants

    if target_assistants:
        target_assistants = [aid for aid in target_assistants if not _is_group_assistant_muted(group, aid)]

    hub = getattr(app.state, "topomobile_relay_hub", None)
    if hub is None:
        logger.warning("topomobile_relay_hub 不可用，无法向assistant发送群聊消息")
        return

    route_user = _resolve_group_assistant_route_user(group, target_assistant, sender_imei)
    route_imei_id = connection_manager.resolve_user_adapter_route(route_user) or route_user

    if not target_assistants:
        return

    # 为每个助手并行发送消息并处理回复
    tasks = []
    for assistant_id in target_assistants:
        cfg = (group.get("assistant_configs") or {}).get(assistant_id) or {}
        task = asyncio.create_task(
            _handle_single_assistant_broadcast(
                app, hub, route_imei_id, group, group_id, sender_imei,
                content, msg_type, image_base64, assistant_id, forward_reply_to_group=(not sync_only)
            )
        )
        tasks.append(task)

    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)


async def _dispatch_group_assistant_reply(
    app: FastAPI,
    *,
    group: dict,
    group_id: str,
    sender_imei: str,
    content: str,
) -> None:
    assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
    if not assistants:
        return

    target_assistant = _extract_mentioned_assistant(content, assistants, group)
    if not target_assistant:
        return
    if _is_group_assistant_muted(group, target_assistant):
        return

    # 保留原始消息内容（不去除 @ 信息）
    # prompt = _clean_group_prompt(content, target_assistant)
    # if not prompt:
    #     return

    hub = getattr(app.state, "topomobile_relay_hub", None)
    if hub is None:
        logger.warning("topomobile_relay_hub 不可用，无法处理群组 assistant 回复")
        return

    route_user = _resolve_group_assistant_route_user(group, target_assistant, sender_imei)
    route_imei_id = connection_manager.resolve_user_adapter_route(route_user) or route_user
    request_id = f"group-chat-{uuid.uuid4().hex}"
    thread_id = f"group_{group_id}:{target_assistant}"
    
    # 构建上下文文本（包含用户 agent 列表和群聊信息），拼接到消息前面
    context_text = _build_context_text_for_agent(sender_imei, group, group_id)
    query_with_role = _inject_group_role_prompt_for_assistant(group, target_assistant, content)
    full_message = context_text + query_with_role  # 发送完整原始消息，保留 @ 信息
    
    payload: dict = {
        "type": "chat",
        "request_id": request_id,
        "agent_id": target_assistant,
        "thread_id": thread_id,
        "message": full_message,
        "images": [],
        # 显式带 imei，确保移动端工具调用回路由到助手所有者手机
        "imei": route_user,
    }

    stream_id = None
    chunks: list[str] = []
    done_response = ""
    error_text = ""
    try:
        stream_id = await hub.send_stream_request(route_imei_id, payload)
        while True:
            event = await hub.recv_stream_event(stream_id, timeout=180.0)
            ev_type = str(event.get("type") or "")
            if ev_type == "delta":
                chunks.append(str(event.get("content") or ""))
                continue
            if ev_type == "done":
                done_response = str(event.get("response") or "")
                break
            if ev_type in {"error", "stopped"}:
                error_text = str(event.get("error") or event.get("content") or "assistant 调用失败")
                break
    except asyncio.TimeoutError:
        error_text = "assistant 响应超时"
    except Exception as exc:
        error_text = str(exc)
    finally:
        if stream_id is not None:
            await hub.close_stream_request(stream_id)

    reply = (("".join(chunks) + done_response).strip() if not error_text else "").strip()
    if not reply:
        label = _assistant_display_name(group, target_assistant)
        reply = f"[{label}] {error_text or '已收到消息，但未返回文本结果'}"
    if _contains_no_reply_marker(reply):
        logger.info("助手 %s 返回不回复标记，跳过群消息发送: group=%s", target_assistant, group_id)
        return
    reply = _strip_no_reply_marker(reply)
    if not reply:
        return

    assistant_name = _assistant_display_name(group, target_assistant)
    reply_sender_imei = target_assistant
    assistant_message = {
        "type": "group_message",
        "groupId": group_id,
        "senderImei": reply_sender_imei,
        "content": reply,
        "timestamp": get_now_isoformat(),
        "sender": assistant_name,
        "is_assistant_reply": True,
        "assistant_id": target_assistant,
    }

    msg_id = append_group_msg(
        group_id,
        reply_sender_imei,
        reply,
        type="assistant",
        sender=assistant_name,
        assistant_id=target_assistant,
    )
    assistant_message["message_id"] = msg_id

    for member_imei in group.get("members", []):
        if member_imei != ASSISTANT_BOT_ID:
            await message_service.send_message_to_user(member_imei, assistant_message)


def register_websocket(app: FastAPI) -> None:
    @app.websocket("/ws/customer-service/{imei}")
    async def websocket_endpoint(websocket: WebSocket, imei: str):
        """WebSocket连接端点，URL 可选 ?device=pc 表示 PC 端"""
        qs = websocket.scope.get("query_string", b"").decode()
        device = "pc" if "device=pc" in qs.lower() else "mobile"
        logger.info(f"WebSocket连接: imei={imei[:8]}..., device={device}")
        try:
            await connection_manager.connect(websocket, imei, device)
            connection_manager.register_default_topoclaw_user_slot(imei)
        except Exception as e:
            logger.error(f"WebSocket连接失败: {e}")
            raise

        try:
            offline_messages = await message_service.get_offline_messages(imei)
            if offline_messages:
                await websocket.send_json(
                    {
                        "type": "offline_messages",
                        "messages": offline_messages,
                        "count": len(offline_messages),
                    }
                )
                logger.info(f"已推送 {len(offline_messages)} 条离线消息给用户: {imei[:8]}...")

                for msg in offline_messages:
                    msg_type = msg.get("type", "")
                    if msg_type == "friend_message" or msg_type == "friend_request":
                        continue
                    conversation_logger.log_service_message(
                        imei=imei,
                        content=msg.get("content", ""),
                        sender=msg.get("sender", "人工客服"),
                        timestamp=msg.get("timestamp") or msg.get("saved_at"),
                        extra_data={"is_offline_push": True},
                    )

            logger.debug(f"开始监听用户消息: {imei[:8]}...")
            while True:
                try:
                    data = await websocket.receive_text()
                    if device != "pc":
                        connection_manager.touch_user_presence(imei)

                    try:
                        message_data = json.loads(data)
                        message_type = message_data.get("type")
                        logger.debug(
                            f"收到消息: {imei[:8]}..., type={message_type}, len={len(data)}"
                        )

                        if message_type == "user_message":
                            content = message_data.get("content", "")
                            user_timestamp_raw = message_data.get("timestamp")

                            if user_timestamp_raw is None:
                                user_timestamp_ms = int(
                                    datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000
                                )
                            elif isinstance(user_timestamp_raw, (int, float)):
                                user_timestamp_ms = int(user_timestamp_raw)
                            elif isinstance(user_timestamp_raw, str):
                                try:
                                    if "T" in user_timestamp_raw:
                                        clean_timestamp = user_timestamp_raw.replace("Z", "").strip()

                                        if "+" in clean_timestamp or (
                                            clean_timestamp.count("-") > 2
                                            and ":" in clean_timestamp.split("T")[1]
                                        ):
                                            dt = datetime.fromisoformat(clean_timestamp)
                                        else:
                                            dt = datetime.fromisoformat(clean_timestamp)
                                            dt = dt.replace(tzinfo=TZ_UTC_PLUS_8)

                                        user_timestamp_ms = int(dt.timestamp() * 1000)
                                    else:
                                        user_timestamp_ms = int(float(user_timestamp_raw))
                                except (ValueError, AttributeError):
                                    user_timestamp_ms = int(
                                        datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000
                                    )
                            else:
                                user_timestamp_ms = int(
                                    datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000
                                )

                            logger.debug(f"user_message: {imei[:8]}... - {content[:50]}")

                            conversation_logger.log_user_message(
                                imei=imei,
                                content=content,
                                timestamp=user_timestamp_ms,
                                extra_data={"message_id": message_data.get("message_id")},
                            )

                            reply_timestamp_ms = int(
                                datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000
                            )
                            reply_message = {
                                "type": "service_message",
                                "content": "已收到您的消息，后台人员将尽快回复您。",
                                "timestamp": reply_timestamp_ms,
                                "sender": "人工客服",
                            }
                            await websocket.send_json(reply_message)

                            conversation_logger.log_service_message(
                                imei=imei,
                                content=reply_message["content"],
                                sender=reply_message["sender"],
                                timestamp=reply_timestamp_ms,
                            )

                            logger.debug(f"已回复用户消息: {imei[:8]}...")

                        elif message_type == "clone_context_response":
                            request_id = str(message_data.get("request_id") or "").strip()
                            clone_ctx = message_data.get("clone_query_context")
                            waiter = _clone_context_waiters.get(request_id)
                            if waiter is not None and not waiter.done():
                                waiter.set_result(clone_ctx if isinstance(clone_ctx, str) else "")
                            continue

                        elif message_type == "friend_message":
                            target_imei_raw = message_data.get("targetImei", "")
                            content = message_data.get("content", "")
                            msg_type = message_data.get("message_type", "text")
                            image_base64 = message_data.get("imageBase64")
                            clone_query_context = message_data.get("clone_query_context")
                            is_clone_reply = bool(message_data.get("is_clone_reply"))
                            client_msg_id = message_data.get("message_id")
                            target_imei = (
                                target_imei_raw.strip() if isinstance(target_imei_raw, str) else ""
                            )

                            logger.debug(
                                f"friend_message: {imei[:8]}... -> {(target_imei[:8] + '...') if target_imei else '(空)'}"
                            )

                            if not target_imei:
                                logger.warning(f"好友消息缺少targetImei: {imei[:8]}...")
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "好友消息缺少目标IMEI",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            sender_device = "pc" if device == "pc" else "mobile"
                            cmid = (
                                client_msg_id.strip()
                                if isinstance(client_msg_id, str) and client_msg_id.strip()
                                else None
                            )
                            result = await dispatch_friend_message(
                                imei,
                                target_imei,
                                content or "",
                                msg_type,
                                image_base64,
                                sender_device=sender_device,
                                client_message_id=cmid,
                            )
                            if not result.ok:
                                err_payload = {
                                    "content": result.error_content or "发送失败",
                                    "timestamp": get_now_isoformat(),
                                }
                                if cmid:
                                    err_payload["type"] = "friend_message_error"
                                    err_payload["message_id"] = cmid
                                else:
                                    err_payload["type"] = "error"
                                await websocket.send_json(err_payload)
                                continue
                            if not is_clone_reply:
                                async def _auto_reply_with_desktop_context() -> None:
                                    fallback_context = (
                                        clone_query_context.strip()
                                        if isinstance(clone_query_context, str) and clone_query_context.strip()
                                        else None
                                    )
                                    # 优先以“被代答用户（target_imei）”视角构造上下文，避免沿用发送方携带的旧上下文导致身份反转。
                                    context_for_clone = await _request_clone_query_context(
                                        owner_imei=target_imei,
                                        friend_imei=imei,
                                        content=content or "",
                                        msg_type=msg_type,
                                    )
                                    if not context_for_clone:
                                        context_for_clone = fallback_context
                                    await maybe_auto_reply_friend_message(
                                        app,
                                        sender_imei=imei,
                                        target_imei=target_imei,
                                        content=content or "",
                                        msg_type=msg_type,
                                        image_base64=image_base64,
                                        clone_query_context=context_for_clone,
                                    )

                                asyncio.create_task(_auto_reply_with_desktop_context())
                            await websocket.send_json(
                                {
                                    "type": "friend_message_ack",
                                    "success": True,
                                    "message_id": result.message_id,
                                    "target_online": not result.target_was_offline,
                                    "timestamp": get_now_isoformat(),
                                }
                            )

                        elif message_type == "group_message":
                            group_id = message_data.get("groupId", "")
                            content = message_data.get("content", "")
                            msg_type = message_data.get("message_type", "text")
                            image_base64 = message_data.get("imageBase64")
                            sender_label = str(message_data.get("sender_label") or message_data.get("sender") or "群成员").strip() or "群成员"
                            is_clone_reply = bool(message_data.get("is_clone_reply"))
                            clone_owner_imei = str(message_data.get("clone_owner_imei") or "").strip()
                            clone_origin = str(message_data.get("clone_origin") or "").strip()
                            if is_clone_reply and not clone_owner_imei:
                                clone_owner_imei = imei
                            skip_server_assistant_dispatch = bool(
                                message_data.get("skip_server_assistant_dispatch")
                            )
                            raw_message_id = message_data.get("message_id")
                            msg_id = (
                                raw_message_id.strip()
                                if isinstance(raw_message_id, str) and raw_message_id.strip()
                                else str(uuid.uuid4())
                            )

                            if not group_id:
                                logger.warning(f"群组消息缺少groupId: {imei[:8]}...")
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "群组消息缺少群组ID",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            group = get_group(group_id)
                            if not group:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "群组不存在",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            if imei not in group["members"]:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "您不是该群组成员",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            # 提取被@的助手（用于决定广播还是定向发送）
                            assistants = [str(x).strip() for x in (group.get("assistants") or []) if str(x).strip()]
                            # 明确被 @ 的助手（用于定向发送），None 表示广播给所有助手
                            explicitly_mentioned = _extract_explicitly_mentioned_assistant(content, assistants, group) if assistants else None
                            # 如果没有 @ 助手，则尝试解析被 @ 的群成员，并路由到该成员的 TopoClaw 分身
                            mentioned_member_imei = None
                            member_clone_assistant = None
                            if not explicitly_mentioned:
                                mentioned_member_imei = _extract_mentioned_member_imei(content, group)
                                if mentioned_member_imei:
                                    member_clone_assistant = _find_member_topoclaw_assistant_in_group(group, mentioned_member_imei)
                                    if member_clone_assistant:
                                        explicitly_mentioned = member_clone_assistant
                            # 用于触发回复的助手（如果没有明确 @，则使用默认助手）
                            reply_target_assistant = _extract_mentioned_assistant(content, assistants, group) if assistants else None
                            # 判断是否@了助手：传统关键词 或 明确 @ 了群组中的某个助手
                            assistant_mentioned = is_assistant_mentioned(content) or (explicitly_mentioned is not None)

                            sender_origin = str(message_data.get("sender_origin") or "").strip() or device
                            group_message = {
                                "type": "group_message",
                                "message_id": msg_id,
                                "groupId": group_id,
                                "senderImei": imei,
                                "content": content,
                                "message_type": msg_type,
                                "timestamp": get_now_isoformat(),
                                "sender": sender_label,
                                "assistant_mentioned": assistant_mentioned,
                                "sender_origin": sender_origin,
                            }
                            if image_base64:
                                group_message["imageBase64"] = image_base64
                            if is_clone_reply:
                                group_message["is_clone_reply"] = True
                            if clone_owner_imei:
                                group_message["clone_owner_imei"] = clone_owner_imei
                            if clone_origin:
                                group_message["clone_origin"] = clone_origin
                            if sender_label:
                                group_message["sender_label"] = sender_label

                            logger.debug(
                                f"group_message: group={group_id}, sender={imei[:8]}..., type={msg_type}"
                            )
                            append_kwargs: dict = {
                                "message_type": msg_type,
                                "sender": sender_label,
                            }
                            if image_base64:
                                append_kwargs["imageBase64"] = image_base64
                            if is_clone_reply:
                                append_kwargs["is_clone_reply"] = True
                            if clone_owner_imei:
                                append_kwargs["clone_owner_imei"] = clone_owner_imei
                            if clone_origin:
                                append_kwargs["clone_origin"] = clone_origin
                            if sender_label:
                                append_kwargs["sender_label"] = sender_label
                            append_group_msg(
                                group_id,
                                imei,
                                content,
                                id=msg_id,
                                **append_kwargs,
                            )

                            members = group["members"]

                            for member_imei in members:
                                if member_imei != ASSISTANT_BOT_ID:
                                    await message_service.send_message_to_user(
                                        member_imei, group_message
                                    )

                            force_server_dispatch = bool(group.get("free_discovery", False)) and not bool(group.get("assistant_muted", False))
                            if skip_server_assistant_dispatch and not force_server_dispatch:
                                logger.debug(
                                    "group_message 跳过服务端助手分发: group=%s, sender=%s...",
                                    group_id,
                                    imei[:8],
                                )
                            else:
                                if skip_server_assistant_dispatch and force_server_dispatch:
                                    logger.debug(
                                        "group_message 自由发言开启，继续服务端分发: group=%s, sender=%s...",
                                        group_id,
                                        imei[:8],
                                    )
                                # 统一仅走 _notify_group_assistants 一条链路，避免 @ 助手时
                                # 与 _dispatch_group_assistant_reply 双触发，导致同一助手重复回复。
                                asyncio.create_task(
                                    _notify_group_assistants(
                                        app,
                                        group=group,
                                        group_id=group_id,
                                        sender_imei=imei,
                                        content=content,
                                        msg_type=msg_type,
                                        image_base64=image_base64,
                                        target_assistant=explicitly_mentioned,  # None 表示按配置广播/默认助手
                                    )
                                )

                            if assistant_mentioned:
                                logger.debug(
                                    f"@助手({reply_target_assistant or '默认'}): {group_id}"
                                )

                            logger.debug(
                                f"群组消息已广播: {group_id}, 成员数: {len(members)}"
                            )

                        elif message_type == "remote_assistant_command":
                            group_id = message_data.get("groupId", "")
                            target_imei = message_data.get("targetImei", "")
                            command = message_data.get("command", "")
                            sender_imei = message_data.get("senderImei", imei)

                            if not group_id or not target_imei or not command:
                                logger.warning(
                                    f"远程指令缺少必要参数: groupId={group_id}, targetImei={target_imei}, command={command[:50]}"
                                )
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "远程指令缺少必要参数",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            group = get_group(group_id)
                            if not group:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "群组不存在",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            if sender_imei not in group["members"]:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "您不是该群组成员",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            if target_imei not in group["members"]:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "目标用户不是该群组成员",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            remote_command_message = {
                                "type": "remote_assistant_command",
                                "groupId": group_id,
                                "targetImei": target_imei,
                                "command": command,
                                "senderImei": sender_imei,
                                "timestamp": get_now_isoformat(),
                            }

                            if not connection_manager.is_user_online(target_imei):
                                logger.warning(f"目标用户不在线: {target_imei[:8]}...")
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "目标用户不在线",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue

                            result = await connection_manager.send_to_user(
                                target_imei, remote_command_message
                            )
                            if result:
                                logger.info(
                                    f"远程指令转发成功: {sender_imei[:8]}... -> {target_imei[:8]}..., 指令: {command[:50]}"
                                )
                                await websocket.send_json(
                                    {
                                        "type": "success",
                                        "content": "远程指令已发送",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                            else:
                                logger.warning(f"远程指令转发失败: {target_imei[:8]}...")
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "远程指令发送失败",
                                        "timestamp": get_now_isoformat(),
                                    }
                                )

                        elif message_type == "execute_result":
                            content = message_data.get("content", "")
                            exec_uuid = message_data.get("uuid", "")
                            msg_id = message_data.get("message_id", "")
                            conv_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id") or message_data.get("uuid"),
                            )
                            if conv_id.startswith("group_"):
                                gid = conv_id.replace("group_", "", 1)
                                append_group_msg(
                                    gid,
                                    "assistant",
                                    content,
                                    type="assistant",
                                    sender="自动执行小助手",
                                    id=msg_id,
                                    uuid=exec_uuid,
                                )
                            elif conv_id and conv_id != "assistant":
                                append_custom_assistant_single_msg(
                                    imei,
                                    assistant_id=conv_id,
                                    sender="小助手",
                                    content=content,
                                    type="assistant",
                                    id=msg_id,
                                    uuid=exec_uuid,
                                )
                            else:
                                append_assistant_msg(
                                    imei,
                                    sender="小助手",
                                    content=content,
                                    type="assistant",
                                    id=msg_id,
                                    uuid=exec_uuid,
                                )
                            push_msg = {
                                "type": "execute_result",
                                "content": content,
                                "uuid": exec_uuid,
                                "message_id": msg_id,
                                "sender": "小助手",
                                "timestamp": get_now_isoformat(),
                            }
                            if conv_id:
                                push_msg["conversation_id"] = conv_id
                            if connection_manager.is_pc_online(imei):
                                await connection_manager.send_to_pc(imei, push_msg)
                                logger.debug(f"执行结果已推送到 PC: {imei[:8]}...")
                            else:
                                logger.debug(f"PC 不在线，执行结果无法推送: {imei[:8]}...")

                        elif message_type == "cross_device_message":
                            content = message_data.get("content", "")
                            msg_type = message_data.get("message_type", "text")
                            file_base64 = message_data.get("fileBase64") or message_data.get(
                                "file_base64"
                            )
                            file_name = message_data.get("fileName") or message_data.get("file_name")
                            msg_id = str(uuid.uuid4())
                            created_at = get_now_isoformat()
                            msg = {
                                "id": msg_id,
                                "from_device": "mobile",
                                "content": content,
                                "message_type": msg_type,
                                "created_at": created_at,
                            }
                            if file_base64:
                                msg["file_base64"] = file_base64
                            if file_name:
                                msg["file_name"] = file_name
                            cd = get_cross_device_messages()
                            if imei not in cd:
                                cd[imei] = []
                            cd[imei].append(msg)
                            save_cross_device_messages()
                            try:
                                append_cross_device_msg(imei, dict(msg))
                            except Exception as e:
                                logger.warning(
                                    f"端云消息双写到 unified_messages 失败（cross_device 已保存）: {e}"
                                )
                            push_msg = {
                                "type": "cross_device_message",
                                "message_id": msg_id,
                                "from_device": "mobile",
                                "sender": "我的手机",
                                "content": content,
                                "message_type": msg_type,
                                "timestamp": created_at,
                            }
                            if file_base64:
                                push_msg["file_base64"] = file_base64
                            if file_name:
                                push_msg["file_name"] = file_name
                            if connection_manager.is_pc_online(imei):
                                await connection_manager.send_to_pc(imei, push_msg)
                            logger.debug(f"端云消息已保存: {imei[:8]}...")

                        elif message_type == "assistant_user_message":
                            content = message_data.get("content", "")
                            msg_id = message_data.get("message_id", str(uuid.uuid4()))
                            conv_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id"),
                            )
                            file_base64 = (
                                message_data.get("file_base64")
                                or message_data.get("fileBase64")
                                or message_data.get("imageBase64")
                                or ""
                            )
                            file_name = message_data.get("file_name") or message_data.get("fileName") or "图片.png"
                            msg_type = (
                                str(message_data.get("message_type") or "").strip().lower()
                                or ("image" if file_base64 else "text")
                            )
                            append_assistant_or_conv_msg(
                                imei,
                                conv_id,
                                sender="我",
                                content=content,
                                msg_type="user",
                                id=msg_id,
                                **({"file_base64": file_base64} if file_base64 else {}),
                                **({"file_name": file_name} if file_base64 else {}),
                                **({"message_type": msg_type} if file_base64 else {}),
                            )
                            push_msg = {
                                "type": "assistant_user_message",
                                "message_id": msg_id,
                                "sender": "我",
                                "content": content,
                                "conversation_id": conv_id,
                                "timestamp": get_now_isoformat(),
                            }
                            if file_base64:
                                push_msg["file_base64"] = file_base64
                                push_msg["file_name"] = file_name
                                push_msg["message_type"] = msg_type
                            if device == "pc":
                                if connection_manager.is_user_online(imei):
                                    await connection_manager.send_to_user(imei, push_msg)
                                else:
                                    logger.debug(f"手机不在线，用户消息已入库: imei={imei[:8]}...")
                            else:
                                if connection_manager.is_pc_online(imei):
                                    await connection_manager.send_to_pc(imei, push_msg)

                        elif message_type == "assistant_thinking_sync":
                            thinking_content = str(message_data.get("thinking_content") or "").strip()
                            if not thinking_content:
                                continue
                            sender = str(message_data.get("sender") or "小助手")
                            msg_id = message_data.get("message_id", str(uuid.uuid4()))
                            conv_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id"),
                            )
                            push_msg = {
                                "type": "assistant_thinking_sync",
                                "message_id": msg_id,
                                "sender": sender,
                                "thinking_content": thinking_content,
                                "conversation_id": conv_id,
                                "timestamp": get_now_isoformat(),
                            }
                            logger.info(
                                "assistant_thinking_sync 收到: imei=%s..., msg_id=%s, conv=%s, len=%s, preview=%s",
                                imei[:8],
                                str(msg_id)[:16],
                                conv_id or "(none)",
                                len(thinking_content),
                                _shorten_text(thinking_content, 120),
                            )
                            mobile_online = connection_manager.is_user_online(imei)
                            pc_online = connection_manager.is_pc_online(imei)
                            send_ok = False
                            if device == "pc":
                                if mobile_online:
                                    send_ok = bool(await connection_manager.send_to_user(imei, push_msg))
                            else:
                                if pc_online:
                                    send_ok = bool(await connection_manager.send_to_pc(imei, push_msg))
                            logger.info(
                                "assistant_thinking_sync 转发: imei=%s..., from=%s, to_mobile_online=%s, to_pc_online=%s, sent=%s, msg_id=%s",
                                imei[:8],
                                device,
                                mobile_online,
                                pc_online,
                                send_ok,
                                str(msg_id)[:16],
                            )

                        elif message_type == "assistant_sync_message":
                            raw_content = str(message_data.get("content", "") or "")
                            sender = message_data.get("sender", "系统")
                            msg_id = message_data.get("message_id", str(uuid.uuid4()))
                            conv_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id"),
                            )
                            content, leaked_owner_feedback = split_reply_and_owner_feedback(raw_content)
                            if leaked_owner_feedback:
                                logger.warning(
                                    "assistant_sync_message 检测到 OWNER_FEEDBACK 泄漏并已剥离: imei=%s..., conv=%s, sender=%s",
                                    imei[:8],
                                    conv_id,
                                    sender,
                                )
                            file_base64 = (
                                message_data.get("file_base64")
                                or message_data.get("fileBase64")
                                or message_data.get("imageBase64")
                                or ""
                            )
                            file_name = message_data.get("file_name") or message_data.get("fileName") or "图片.png"
                            media_msg_type = (
                                str(message_data.get("message_type") or "").strip().lower()
                                or ("image" if file_base64 else "text")
                            )
                            if not content and leaked_owner_feedback and not file_base64:
                                logger.info(
                                    "assistant_sync_message 仅包含 OWNER_FEEDBACK 块，跳过展示消息: imei=%s..., conv=%s, sender=%s",
                                    imei[:8],
                                    conv_id,
                                    sender,
                                )
                                continue
                            # 自定义小助手名为 sender 时也应记为 assistant，仅明确「系统」走 system
                            msg_type_inner = "system" if sender == "系统" else "assistant"
                            append_assistant_or_conv_msg(
                                imei,
                                conv_id,
                                sender=sender,
                                content=content,
                                msg_type=msg_type_inner,
                                id=msg_id,
                                **({"file_base64": file_base64} if file_base64 else {}),
                                **({"file_name": file_name} if file_base64 else {}),
                                **({"message_type": media_msg_type} if file_base64 else {}),
                            )
                            push_msg = {
                                "type": "assistant_sync_message",
                                "message_id": msg_id,
                                "sender": sender,
                                "content": content,
                                "conversation_id": conv_id,
                                "timestamp": get_now_isoformat(),
                            }
                            if file_base64:
                                push_msg["file_base64"] = file_base64
                                push_msg["file_name"] = file_name
                                push_msg["message_type"] = media_msg_type
                            if device == "pc":
                                if connection_manager.is_user_online(imei):
                                    await connection_manager.send_to_user(imei, push_msg)
                                else:
                                    logger.debug(f"手机不在线，sync 消息已入库: imei={imei[:8]}...")
                            else:
                                if connection_manager.is_pc_online(imei):
                                    await connection_manager.send_to_pc(imei, push_msg)

                        elif message_type == "mobile_execute_pc_command":
                            query = message_data.get("query", "")
                            assistant_base_url = message_data.get("assistant_base_url", "")
                            thread_id = str(message_data.get("thread_id") or "").strip()
                            session_id = str(message_data.get("session_id") or "").strip()
                            conversation_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=thread_id,
                            )
                            msg_id = message_data.get("message_id", str(uuid.uuid4()))
                            chat_summary = message_data.get("chat_summary", "") or ""
                            file_base64 = (
                                message_data.get("file_base64")
                                or message_data.get("fileBase64")
                                or message_data.get("imageBase64")
                                or ""
                            )
                            file_name = message_data.get("file_name") or message_data.get("fileName") or "图片.png"
                            msg_type = (
                                str(message_data.get("message_type") or "").strip().lower()
                                or ("image" if file_base64 else "text")
                            )
                            if not query or not assistant_base_url:
                                logger.warning(
                                    f"mobile_execute_pc_command 缺少必要参数: query={bool(query)}, base_url={bool(assistant_base_url)}"
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "mobile_execute_pc_error",
                                            "content": "参数错误",
                                            "message_id": msg_id,
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                                continue
                            push_msg = {
                                "type": "mobile_execute_pc_command",
                                "query": query,
                                "assistant_base_url": assistant_base_url,
                                "conversation_id": conversation_id,
                                "message_id": msg_id,
                                "chat_summary": chat_summary or query[:80],
                                "timestamp": get_now_isoformat(),
                            }
                            if thread_id:
                                push_msg["thread_id"] = thread_id
                            if session_id:
                                push_msg["session_id"] = session_id
                            if file_base64:
                                push_msg["file_base64"] = file_base64
                                push_msg["file_name"] = file_name
                                push_msg["message_type"] = msg_type
                            if connection_manager.is_pc_online(imei):
                                await connection_manager.send_to_pc(imei, push_msg)
                            else:
                                logger.debug(f"PC 不在线，无法桥接手机执行指令: {imei[:8]}...")
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "mobile_execute_pc_error",
                                            "content": "电脑端不在线，请确保电脑已打开应用并保持连接",
                                            "message_id": msg_id,
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )

                        elif message_type == "mobile_execute_pc_thinking":
                            msg_id = message_data.get("message_id", "")
                            thinking_content = str(message_data.get("thinking_content") or "").strip()
                            if not thinking_content:
                                continue
                            thread_id = str(message_data.get("thread_id") or "").strip()
                            session_id = str(message_data.get("session_id") or "").strip()
                            conversation_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=thread_id,
                            )
                            push_msg = {
                                "type": "mobile_execute_pc_thinking",
                                "message_id": msg_id,
                                "thinking_content": thinking_content,
                                "conversation_id": conversation_id,
                                "timestamp": get_now_isoformat(),
                            }
                            if thread_id:
                                push_msg["thread_id"] = thread_id
                            if session_id:
                                push_msg["session_id"] = session_id
                            logger.info(
                                "mobile_execute_pc_thinking 收到: imei=%s..., msg_id=%s, conv=%s, len=%s, preview=%s",
                                imei[:8],
                                str(msg_id)[:16],
                                conversation_id or thread_id or "(none)",
                                len(thinking_content),
                                _shorten_text(thinking_content, 120),
                            )
                            mobile_online = connection_manager.is_user_online(imei)
                            send_ok = False
                            if mobile_online:
                                send_ok = bool(await connection_manager.send_to_user(imei, push_msg))
                            logger.info(
                                "mobile_execute_pc_thinking 转发手机: imei=%s..., online=%s, sent=%s, msg_id=%s",
                                imei[:8],
                                mobile_online,
                                send_ok,
                                str(msg_id)[:16],
                            )

                        elif message_type == "mobile_execute_pc_result":
                            msg_id = message_data.get("message_id", "")
                            success = message_data.get("success", False)
                            content = message_data.get("content", "") or ""
                            error = message_data.get("error", "")
                            thread_id = str(message_data.get("thread_id") or "").strip()
                            session_id = str(message_data.get("session_id") or "").strip()
                            conversation_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=thread_id,
                            )
                            file_base64 = (
                                message_data.get("file_base64")
                                or message_data.get("fileBase64")
                                or message_data.get("imageBase64")
                                or ""
                            )
                            file_name = message_data.get("file_name") or message_data.get("fileName") or "图片.png"
                            msg_type = (
                                str(message_data.get("message_type") or "").strip().lower()
                                or ("image" if file_base64 else "text")
                            )
                            push_msg = {
                                "type": "mobile_execute_pc_result",
                                "message_id": msg_id,
                                "success": success,
                                "content": content,
                                "error": error,
                                "conversation_id": conversation_id,
                                "timestamp": get_now_isoformat(),
                            }
                            if thread_id:
                                push_msg["thread_id"] = thread_id
                            if session_id:
                                push_msg["session_id"] = session_id
                            if file_base64:
                                push_msg["file_base64"] = file_base64
                                push_msg["file_name"] = file_name
                                push_msg["message_type"] = msg_type
                            if connection_manager.is_user_online(imei):
                                await connection_manager.send_to_user(imei, push_msg)

                        elif message_type == "assistant_stop_task":
                            conversation_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id"),
                            )
                            push_msg = {
                                "type": "assistant_stop_task",
                                "conversation_id": conversation_id,
                                "timestamp": get_now_isoformat(),
                            }
                            if device == "mobile":
                                if connection_manager.is_pc_online(imei):
                                    await connection_manager.send_to_pc(imei, push_msg)
                            else:
                                if connection_manager.is_user_online(imei):
                                    await connection_manager.send_to_user(imei, push_msg)

                        elif message_type == "gui_execute_result":
                            request_id = str(message_data.get("request_id") or "").strip()
                            success = bool(message_data.get("success", False))
                            content = str(message_data.get("content") or "")
                            error = str(message_data.get("error") or "")
                            thread_id = str(message_data.get("thread_id") or "").strip()
                            if not request_id:
                                logger.warning(
                                    f"gui_execute_result 缺少 request_id: imei={imei[:8]}..."
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "error",
                                            "content": "gui_execute_result 缺少 request_id",
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                                continue

                            hub = getattr(app.state, "topomobile_relay_hub", None)
                            if hub is None:
                                logger.warning(
                                    f"topomobile_relay_hub 不可用，无法转发 gui_execute_result: imei={imei[:8]}..., request_id={request_id}"
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_execute_result_ack",
                                            "ok": False,
                                            "request_id": request_id,
                                            "error": "relay unavailable",
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                                continue

                            route_info = connection_manager.resolve_gui_request_route(request_id) or {}
                            route_imei_id = str(route_info.get("route_imei_id") or "").strip()
                            if not route_imei_id:
                                route_imei_id = connection_manager.resolve_user_adapter_route(imei) or imei
                            outbound = {
                                "type": "gui_execute_result",
                                "request_id": request_id,
                                "success": success,
                                "content": content,
                                "error": error,
                                "imei": imei,
                            }
                            if thread_id:
                                outbound["thread_id"] = thread_id
                            try:
                                relay_resp = await hub.send_request(route_imei_id, outbound, timeout=30.0)
                                connection_manager.forget_gui_request_route(request_id)
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_execute_result_ack",
                                            "ok": True,
                                            "request_id": request_id,
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                            except Exception as exc:
                                logger.warning(
                                    f"gui_execute_result 转发失败: imei={imei[:8]}..., request_id={request_id}, err={exc}"
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_execute_result_ack",
                                            "ok": False,
                                            "request_id": request_id,
                                            "error": str(exc),
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )

                        elif message_type == "gui_step_request":
                            step_request_id = str(message_data.get("step_request_id") or message_data.get("request_id") or "").strip()
                            gui_request_id = str(message_data.get("gui_request_id") or "").strip()
                            query = str(message_data.get("query") or "").strip()
                            screenshot = str(message_data.get("screenshot") or "").strip()
                            package_name = str(message_data.get("package_name") or "").strip()
                            user_response = str(message_data.get("user_response") or "").strip()
                            thread_id = str(message_data.get("thread_id") or "").strip()
                            if not step_request_id:
                                step_request_id = f"gui-step-{uuid.uuid4().hex}"
                            if not gui_request_id:
                                logger.warning(
                                    f"gui_step_request 缺少 gui_request_id: imei={imei[:8]}..., step_request_id={step_request_id}"
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_step_response",
                                            "ok": False,
                                            "success": False,
                                            "step_request_id": step_request_id,
                                            "error": "missing gui_request_id",
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                                continue
                            if not query and not screenshot:
                                logger.warning(
                                    f"gui_step_request 缺少 query/screenshot: imei={imei[:8]}..., gui_request_id={gui_request_id}"
                                )
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_step_response",
                                            "ok": False,
                                            "success": False,
                                            "step_request_id": step_request_id,
                                            "gui_request_id": gui_request_id,
                                            "error": "missing query and screenshot",
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )
                                continue

                            push_msg = {
                                "type": "gui_step_request",
                                "step_request_id": step_request_id,
                                "gui_request_id": gui_request_id,
                                "query": query,
                                "screenshot": screenshot,
                                "imei": imei,
                                "timestamp": get_now_isoformat(),
                            }
                            if thread_id:
                                push_msg["thread_id"] = thread_id
                            if package_name:
                                push_msg["package_name"] = package_name
                            if user_response:
                                push_msg["user_response"] = user_response

                            # Relay-first routing: keep step on the same TopoClaw route as gui_execute_request.
                            # Fallback to PC bridge only if relay unavailable.
                            route_info = connection_manager.resolve_gui_request_route(gui_request_id) or {}
                            mapped_thread_id = str(route_info.get("thread_id") or "").strip()
                            if mapped_thread_id and "thread_id" not in push_msg:
                                push_msg["thread_id"] = mapped_thread_id
                            route_imei_id = str(route_info.get("route_imei_id") or "").strip()
                            if not route_imei_id:
                                route_imei_id = connection_manager.resolve_user_adapter_route(imei) or imei
                            hub = getattr(app.state, "topomobile_relay_hub", None)
                            if hub is not None:
                                outbound = dict(push_msg)
                                try:
                                    relay_resp = await hub.send_request(route_imei_id, outbound, timeout=45.0)
                                    if device == "mobile":
                                        await websocket.send_json(relay_resp)
                                    continue
                                except Exception as exc:
                                    logger.warning(
                                        f"gui_step_request 转发到 TopoClaw 失败，回退PC链路: imei={imei[:8]}..., gui_request_id={gui_request_id}, step_request_id={step_request_id}, err={exc}"
                                    )

                            if connection_manager.is_pc_online(imei):
                                await connection_manager.send_to_pc(imei, push_msg)
                            else:
                                logger.debug(f"PC 不在线，无法转发 gui_step_request: imei={imei[:8]}...")
                                if device == "mobile":
                                    await websocket.send_json(
                                        {
                                            "type": "gui_step_response",
                                            "ok": False,
                                            "success": False,
                                            "step_request_id": step_request_id,
                                            "gui_request_id": gui_request_id,
                                            "error": "电脑端不在线，请确保电脑已打开应用并保持连接",
                                            "timestamp": get_now_isoformat(),
                                        }
                                    )

                        elif message_type == "gui_step_response":
                            step_request_id = str(message_data.get("step_request_id") or "").strip()
                            gui_request_id = str(message_data.get("gui_request_id") or "").strip()
                            push_msg = {
                                "type": "gui_step_response",
                                "ok": message_data.get("ok", False),
                                "success": message_data.get("success", False),
                                "step_request_id": step_request_id,
                                "gui_request_id": gui_request_id,
                                "chat_response": message_data.get("chat_response"),
                                "error": str(message_data.get("error") or ""),
                                "timestamp": get_now_isoformat(),
                            }
                            if connection_manager.is_user_online(imei):
                                await connection_manager.send_to_user(imei, push_msg)

                        elif message_type in {
                            "mobile_tool_invoke",
                            "mobile_tool_cancel",
                            "mobile_tool_manifest",
                            "mobile_tool_ack",
                            "mobile_tool_event",
                            "mobile_tool_result",
                        }:
                            request_id = str(message_data.get("request_id") or "").strip()
                            protocol = str(message_data.get("protocol") or "mobile_tool/v1").strip() or "mobile_tool/v1"
                            conversation_id = _normalize_conversation_id(
                                message_data.get("conversation_id"),
                                fallback_thread_id=message_data.get("thread_id"),
                            )
                            push_msg = {
                                "type": message_type,
                                "request_id": request_id,
                                "protocol": protocol,
                                "conversation_id": conversation_id,
                                "payload": message_data.get("payload") or {},
                                "timestamp": get_now_isoformat(),
                            }
                            if (
                                message_type == "mobile_tool_result"
                                and request_id
                                and device == "mobile"
                            ):
                                resolve_pending_mobile_tool_result(
                                    imei=imei,
                                    request_id=request_id,
                                    payload=push_msg,
                                )
                            if message_type in {"mobile_tool_invoke", "mobile_tool_cancel"}:
                                if connection_manager.is_user_online(imei):
                                    await connection_manager.send_to_user(imei, push_msg)
                                else:
                                    logger.debug(
                                        f"手机不在线，无法转发{message_type}: imei={imei[:8]}..."
                                    )
                                    if device == "pc":
                                        await websocket.send_json(
                                            {
                                                "type": "error",
                                                "content": "手机不在线，无法执行端侧工具",
                                                "request_id": request_id,
                                                "timestamp": get_now_isoformat(),
                                            }
                                        )
                            else:
                                hub = getattr(app.state, "topomobile_relay_hub", None)
                                relayed = False
                                if device == "mobile" and hub is not None:
                                    route_imei_id = connection_manager.resolve_user_adapter_route(imei) or imei
                                    if request_id:
                                        route_info = connection_manager.resolve_mobile_tool_request_route(request_id)
                                        if isinstance(route_info, dict):
                                            routed_imei = str(route_info.get("imei") or "").strip()
                                            routed_node = str(route_info.get("route_imei_id") or "").strip()
                                            if routed_imei == imei and routed_node:
                                                route_imei_id = routed_node
                                    outbound = {
                                        "type": message_type,
                                        "request_id": request_id,
                                        "protocol": protocol,
                                        "conversation_id": conversation_id,
                                        "payload": message_data.get("payload") or {},
                                        "imei": imei,
                                    }
                                    thread_id = str(message_data.get("thread_id") or "").strip()
                                    if thread_id:
                                        outbound["thread_id"] = thread_id
                                    try:
                                        relay_resp = await hub.send_request(route_imei_id, outbound, timeout=30.0)
                                        relayed = True
                                    except Exception as exc:
                                        logger.warning(
                                            f"{message_type} 转发到 TopoClaw 失败: imei={imei[:8]}..., request_id={request_id or '(none)'}, err={exc}"
                                        )
                                    finally:
                                        if message_type == "mobile_tool_result" and request_id:
                                            connection_manager.forget_mobile_tool_request_route(request_id)
                                if not relayed:
                                    if connection_manager.is_pc_online(imei):
                                        await connection_manager.send_to_pc(imei, push_msg)
                                    if device == "mobile" and message_type == "mobile_tool_result" and request_id:
                                        connection_manager.forget_mobile_tool_request_route(request_id)

                        elif message_type == "ping":
                            logger.debug(f"收到心跳消息: {imei[:8]}...")
                            if device != "pc":
                                connection_manager.touch_user_presence(imei)
                            pong_message = {"type": "pong", "timestamp": get_now_isoformat()}
                            await websocket.send_json(pong_message)
                            logger.debug(f"已回复心跳: {imei[:8]}...")
                        elif message_type in {
                            "set_llm_provider",
                            "set_gui_provider",
                            "get_builtin_model_profiles",
                            "cron_list_jobs",
                            "cron_create_job",
                            "cron_delete_job",
                        }:
                            # APK -> customer_service -> topomobile relay -> TopoClaw
                            # 复用 relay 协议：请求从 APK 转发到 TopoClaw，再将结果回传给 APK
                            if device != "mobile":
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": f"{message_type} only supports mobile origin",
                                        "request_id": message_data.get("request_id"),
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue
                            hub = getattr(app.state, "topomobile_relay_hub", None)
                            if hub is None:
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": "topomobile relay unavailable",
                                        "request_id": message_data.get("request_id"),
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                                continue
                            route_imei_id = connection_manager.resolve_user_adapter_route(imei) or imei
                            outbound = {
                                "type": message_type,
                                "request_id": message_data.get("request_id"),
                                "imei": imei,
                            }
                            if message_type in {"set_llm_provider", "set_gui_provider"}:
                                outbound["model"] = message_data.get("model")
                            elif message_type == "cron_list_jobs":
                                outbound["include_disabled"] = bool(message_data.get("include_disabled", False))
                            elif message_type == "cron_create_job":
                                for key in (
                                    "message",
                                    "name",
                                    "every_seconds",
                                    "cron_expr",
                                    "at",
                                    "tz",
                                    "delete_after_run",
                                    "deliver",
                                    "channel",
                                    "to",
                                    "agent_id",
                                ):
                                    if key in message_data:
                                        outbound[key] = message_data.get(key)
                            elif message_type == "cron_delete_job":
                                outbound["job_id"] = message_data.get("job_id")
                            try:
                                relay_resp = await hub.send_request(route_imei_id, outbound, timeout=30.0)
                                await websocket.send_json(relay_resp)
                            except Exception as exc:
                                logger.warning(
                                    f"{message_type} 转发失败: imei={imei[:8]}..., err={exc}"
                                )
                                await websocket.send_json(
                                    {
                                        "type": "error",
                                        "content": str(exc),
                                        "request_id": message_data.get("request_id"),
                                        "timestamp": get_now_isoformat(),
                                    }
                                )
                        else:
                            logger.warning(f"未知消息类型: {message_type}")

                    except json.JSONDecodeError as e:
                        logger.error(f"JSON解析失败: {e}, 原始数据: {data[:200]}")
                        try:
                            await websocket.send_json(
                                {
                                    "type": "error",
                                    "content": "消息格式错误，请检查JSON格式",
                                    "timestamp": get_now_isoformat(),
                                }
                            )
                        except Exception:
                            pass

                except WebSocketDisconnect:
                    logger.info(f"WebSocket连接断开（内层）: {imei[:8]}...")
                    raise
                except RuntimeError as e:
                    if "not connected" in str(e).lower():
                        logger.info(f"WebSocket已断开（RuntimeError）: {imei[:8]}...")
                        break
                    logger.error(f"处理WebSocket消息时发生运行时错误: {e}", exc_info=True)
                    continue
                except Exception as e:
                    logger.error(f"处理WebSocket消息时发生错误: {e}", exc_info=True)
                    continue

        except WebSocketDisconnect:
            logger.info(f"WebSocket连接断开: {imei[:8]}...")
        except Exception as e:
            logger.error(f"WebSocket错误: {e}", exc_info=True)
        finally:
            await connection_manager.disconnect(imei, device, websocket)
