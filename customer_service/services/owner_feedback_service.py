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

"""数字分身主动回传：prompt 约束、回传块解析与投递。"""

from __future__ import annotations

import logging
import re
from typing import Optional

from core.deps import connection_manager
from core.time_utils import get_now_isoformat
from services.active_session_store import get_active as active_session_get
from services.custom_assistant_store import (
    get_custom_assistants,
)
from services.unified_message_store import append_assistant_or_conv_msg
from storage.profiles import profiles_storage

logger = logging.getLogger(__name__)

OWNER_FEEDBACK_START = "[[OWNER_FEEDBACK]]"
OWNER_FEEDBACK_END = "[[/OWNER_FEEDBACK]]"
OWNER_FEEDBACK_CONVERSATION_ID = "custom_topoclaw"
_OWNER_FEEDBACK_PATTERN = re.compile(
    rf"{re.escape(OWNER_FEEDBACK_START)}\s*(.*?)\s*{re.escape(OWNER_FEEDBACK_END)}",
    re.DOTALL,
)


def build_owner_feedback_prompt(*, scope_type: str, scope_name: str) -> str:
    """构建「必要时主动回传给主人」的提示词片段。"""
    scope_label = "群聊" if str(scope_type).strip().lower() == "group" else "私聊"
    source_label = str(scope_name or "").strip() or "当前会话"
    return (
        "【数字分身主动反馈规则】\n"
        "你是数字分身。请先完成当前会话中的正常回复，不要等待任何反馈动作。\n"
        "当出现以下任一情况时，你需要主动向主人回传：\n"
        "1) 形成明确结论或建议；\n"
        "2) 风险/进度发生关键变化；\n"
        "3) 需要主人决策或确认。\n"
        "若需回传，请在正常回复末尾附加如下块（不要在其他位置输出这两个标记）：\n"
        f"{OWNER_FEEDBACK_START}\n"
        f"source_type: {scope_label}\n"
        f"source_name: {source_label}\n"
        "summary: <1-3句总结>\n"
        "key_points:\n"
        "- <要点1>\n"
        "- <要点2>\n"
        "next_action: <建议主人下一步，可为空>\n"
        f"{OWNER_FEEDBACK_END}\n"
        "如果无需回传，则不要输出该块。"
    )


def split_reply_and_owner_feedback(reply_text: str) -> tuple[str, str | None]:
    """
    从助手回复中拆分：
    - 对外可见回复（去掉反馈块）
    - 给主人的反馈文本（若存在）
    """
    raw = str(reply_text or "")
    if not raw:
        return "", None
    matches = list(_OWNER_FEEDBACK_PATTERN.finditer(raw))
    if not matches:
        return raw.strip(), None
    feedback = matches[-1].group(1).strip()
    visible = _OWNER_FEEDBACK_PATTERN.sub("", raw).strip()
    return visible, (feedback or None)


def format_owner_feedback_content(
    *,
    source_type: str,
    source_name: str,
    feedback_text: str,
    source_user_imei: str | None = None,
    source_user_name: str | None = None,
) -> str:
    scope_label = "群聊" if str(source_type).strip().lower() == "group" else "私聊"
    raw_source = str(source_name or "").strip() or "未知来源"
    identity = _format_user_identity(source_user_imei, source_user_name)
    if scope_label == "私聊":
        source = identity or raw_source
    else:
        source = f"{raw_source} / {identity}".strip(" /") if identity else raw_source
    body = _rewrite_feedback_source_name_line(
        feedback_text,
        source_type=scope_label,
        source_with_identity=source,
        user_identity=identity,
    )
    return f"【分身主动反馈】\n来源：{scope_label} / {source}\n\n{body}".strip()


def _resolve_user_name(imei: str) -> str:
    profile = profiles_storage.get(imei, {}) if imei else {}
    if not isinstance(profile, dict):
        return ""
    for key in ("name", "nickname", "nickName"):
        value = str(profile.get(key) or "").strip()
        if value:
            return value
    return ""


def _format_user_identity(imei: Optional[str], name: Optional[str]) -> str:
    user_imei = str(imei or "").strip()
    if not user_imei:
        return ""
    display_name = str(name or "").strip() or _resolve_user_name(user_imei) or "用户"
    return f"{display_name}({user_imei})"


def _rewrite_feedback_source_name_line(
    feedback_text: str,
    *,
    source_type: str,
    source_with_identity: str,
    user_identity: str,
) -> str:
    text = str(feedback_text or "").strip()
    if not text:
        return text
    if source_type == "私聊":
        replacement = f"与 {user_identity} 的私聊" if user_identity else source_with_identity
    else:
        replacement = source_with_identity
    return re.sub(
        r"(?im)^(\s*source_name\s*:\s*)(.*)$",
        lambda m: f"{m.group(1)}{replacement}",
        text,
        count=1,
    )


def _resolve_conversation_base_url(owner_imei: str, conversation_id: str) -> str:
    imei = str(owner_imei or "").strip()
    cid = str(conversation_id or "").strip()
    if not imei or not cid:
        return ""
    try:
        rows = get_custom_assistants(imei)
    except Exception:
        return ""
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        rid = str(row.get("id") or "").strip()
        if rid != cid:
            continue
        return str(row.get("baseUrl") or "").strip()
    return ""


def _resolve_active_session_id(owner_imei: str, conversation_id: str) -> str | None:
    imei = str(owner_imei or "").strip()
    cid = str(conversation_id or "").strip()
    if not imei or not cid:
        return None
    # 优先按 base_url key 获取，兼容多 session 的 by_url 口径。
    base_url = _resolve_conversation_base_url(imei, cid)
    candidates = [base_url, ""]
    best_sid = ""
    best_ut = 0
    for bu in candidates:
        try:
            sid, ut = active_session_get(imei, cid, bu or "")
        except Exception:
            continue
        if sid and int(ut or 0) >= best_ut:
            best_sid = str(sid).strip()
            best_ut = int(ut or 0)
    return best_sid or None


async def dispatch_owner_feedback_message(
    *,
    owner_imei: str,
    content: str,
    sender: str = "TopoClaw",
    conversation_id: str | None = None,
) -> bool:
    """
    将反馈投递到主人与 TopoClaw 的会话。
    - 入库统一消息存储，保证历史可见
    - 实时推送到 PC/手机（在线时）
    """
    imei = str(owner_imei or "").strip()
    text = str(content or "").strip()
    if not imei or not text:
        return False

    # 产品约定：分身回传统一进入内置 TopoClaw 会话 custom_topoclaw。
    del conversation_id
    conv_id = OWNER_FEEDBACK_CONVERSATION_ID
    active_session_id = _resolve_active_session_id(imei, conv_id)

    storage_conv_id = conv_id
    if active_session_id:
        storage_conv_id = f"{conv_id}__{active_session_id}"
    msg_id = append_assistant_or_conv_msg(
        imei,
        storage_conv_id,
        sender=sender,
        content=text,
        msg_type="assistant",
        **({"session_id": active_session_id} if active_session_id else {}),
    )
    push_msg = {
        "type": "assistant_sync_message",
        "message_id": msg_id,
        "sender": sender,
        "content": text,
        "conversation_id": conv_id,
        "timestamp": get_now_isoformat(),
        "is_owner_feedback": True,
    }
    if active_session_id:
        push_msg["session_id"] = active_session_id
    pushed = False
    if connection_manager.is_pc_online(imei):
        pushed = await connection_manager.send_to_pc(imei, push_msg) or pushed
    if connection_manager.is_user_online(imei):
        pushed = await connection_manager.send_to_user(imei, push_msg) or pushed
    logger.info(
        "owner_feedback 投递完成: owner=%s..., conv=%s, session=%s, pushed=%s",
        imei[:8],
        conv_id,
        active_session_id or "(none)",
        pushed,
    )
    return True
