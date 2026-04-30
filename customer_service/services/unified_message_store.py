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
统一消息存储 - 支持所有会话类型的消息持久化与拉取
实现 PC 与手机端聊天记录完全同步
"""
import json
import logging
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from core.output_paths import UNIFIED_MESSAGES_FILE

logger = logging.getLogger(__name__)

TZ_UTC_PLUS_8 = timezone(timedelta(hours=8))

# conversation_id 格式:
# - 端云互发: {imei}  (沿用 cross_device 的 key)
# - 自动执行小助手: {imei}_assistant
# - 技能学习: {imei}_skill_learning
# - 人工客服: {imei}_customer_service
# - 好友单聊: friend_{imei_a}_{imei_b}  (imei 按字典序排列保证一致性)
# - 群聊: group_{group_id}

UNIFIED_STORAGE_FILE: Path = UNIFIED_MESSAGES_FILE
_store: Dict[str, List[Dict]] = {}


def _conv_id_friend(imei_a: str, imei_b: str) -> str:
    """好友会话 id，双方一致"""
    a, b = sorted([imei_a.strip(), imei_b.strip()])
    return f"friend_{a}_{b}"


def _conv_id_assistant(imei: str) -> str:
    return f"{imei.strip()}_assistant"


def _conv_id_cross_device(imei: str) -> str:
    return imei.strip()


def _conv_id_group(group_id: str) -> str:
    return f"group_{group_id}"


def _now_iso() -> str:
    return datetime.now(TZ_UTC_PLUS_8).isoformat()


def load_store():
    global _store
    try:
        if UNIFIED_STORAGE_FILE.is_file():
            with open(UNIFIED_STORAGE_FILE, 'r', encoding='utf-8') as f:
                _store = json.load(f)
            logger.info(f"加载统一消息存储成功，会话数: {len(_store)}")
        else:
            _store = {}
    except Exception as e:
        logger.error(f"加载统一消息存储失败: {e}")
        _store = {}


def save_store():
    try:
        with open(UNIFIED_STORAGE_FILE, 'w', encoding='utf-8') as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存统一消息存储失败: {e}")


def append_message(conversation_id: str, msg: Dict) -> str:
    """追加消息，返回 message_id"""
    msg_id = msg.get("id") or str(uuid.uuid4())
    if "id" not in msg:
        msg["id"] = msg_id
    if "created_at" not in msg:
        msg["created_at"] = _now_iso()
    if conversation_id not in _store:
        _store[conversation_id] = []
    _store[conversation_id].append(msg)
    save_store()
    return msg_id


def _parse_created_at_ms(m: Dict) -> int:
    """将 created_at 解析为毫秒时间戳"""
    val = m.get("created_at", "")
    if not val:
        return 0
    try:
        val = val.replace("Z", "+00:00")
        dt = datetime.fromisoformat(val)
        return int(dt.timestamp() * 1000)
    except Exception:
        return 0


def parse_created_at_ms(msg: Dict) -> int:
    """导出的解析方法，供 app 过滤 cross_device 消息使用"""
    return _parse_created_at_ms(msg)


def get_messages(
    conversation_id: str,
    before_id: Optional[str] = None,
    limit: int = 20,
    since_timestamp_ms: Optional[int] = None
) -> Tuple[List[Dict], bool]:
    """分页获取消息，按 created_at 降序。since_timestamp_ms: 仅返回该时间戳之后的消息（增量同步）"""
    msgs = _store.get(conversation_id, [])
    msgs = sorted(msgs, key=lambda m: m.get("created_at", ""), reverse=True)
    if since_timestamp_ms is not None and since_timestamp_ms > 0:
        msgs = [m for m in msgs if _parse_created_at_ms(m) > since_timestamp_ms]
    if before_id:
        idx = next((i for i, m in enumerate(msgs) if m.get("id") == before_id), len(msgs))
        msgs = msgs[idx + 1:]
    result = msgs[:limit]
    has_more = len(msgs) > limit
    return result, has_more


# 便捷方法
def append_assistant_msg(imei: str, sender: str, content: str, **kwargs) -> str:
    """小助手会话追加消息"""
    cid = _conv_id_assistant(imei)
    msg = {"sender": sender, "content": content, **kwargs}
    return append_message(cid, msg)


def append_cross_device_msg(imei: str, msg: Dict) -> str:
    """端云互发追加（兼容现有结构）"""
    cid = _conv_id_cross_device(imei)
    return append_message(cid, msg)


def append_friend_msg(imei_a: str, imei_b: str, sender_imei: str, content: str, **kwargs) -> str:
    """好友消息"""
    cid = _conv_id_friend(imei_a, imei_b)
    msg = {"sender_imei": sender_imei, "content": content, **kwargs}
    return append_message(cid, msg)


def append_group_msg(group_id: str, sender_imei: str, content: str, **kwargs) -> str:
    """群消息"""
    cid = _conv_id_group(group_id)
    msg = {"sender_imei": sender_imei, "content": content, **kwargs}
    return append_message(cid, msg)


def get_assistant_messages(imei: str, before_id: Optional[str] = None, limit: int = 20, since_timestamp_ms: Optional[int] = None):
    return get_messages(_conv_id_assistant(imei), before_id, limit, since_timestamp_ms)


def get_cross_device_messages_from_store(imei: str, before_id: Optional[str] = None, limit: int = 20, since_timestamp_ms: Optional[int] = None):
    return get_messages(_conv_id_cross_device(imei), before_id, limit, since_timestamp_ms)


def get_friend_messages(imei_a: str, imei_b: str, before_id: Optional[str] = None, limit: int = 20, since_timestamp_ms: Optional[int] = None):
    return get_messages(_conv_id_friend(imei_a, imei_b), before_id, limit, since_timestamp_ms)


def get_group_messages(group_id: str, before_id: Optional[str] = None, limit: int = 20, since_timestamp_ms: Optional[int] = None):
    return get_messages(_conv_id_group(group_id), before_id, limit, since_timestamp_ms)


def _conv_id_custom_assistant(imei: str, assistant_id: str, session_id: Optional[str] = None) -> str:
    """自定义小助手会话 id：{imei}_{assistant_id} 或 {imei}_{assistant_id}_{session_id}（多 session）"""
    base = f"{imei.strip()}_{assistant_id.strip()}"
    if session_id and session_id.strip():
        return f"{base}_{session_id.strip()}"
    return base


def append_custom_assistant_single_msg(imei: str, assistant_id: str, sender: str, content: str, session_id: Optional[str] = None, **kwargs) -> str:
    """向自定义小助手会话追加单条消息（如 execute_result）。多 session 时传入 session_id"""
    cid = _conv_id_custom_assistant(imei, assistant_id, session_id)
    msg = {"sender": sender, "content": content, **kwargs}
    return append_message(cid, msg)


def append_custom_assistant_msg(
    imei: str,
    assistant_id: str,
    user_content: str,
    assistant_content: str,
    assistant_name: str = "小助手",
    file_base64: Optional[str] = None,
    file_name: Optional[str] = None,
    session_id: Optional[str] = None,
) -> str:
    """追加自定义小助手聊天消息（用户+助手一对），用于跨设备同步，支持图片 base64。多 session 时传入 session_id"""
    cid = _conv_id_custom_assistant(imei, assistant_id, session_id)
    user_msg: Dict = {"sender": "我", "content": user_content, "type": "user"}
    if file_base64:
        user_msg["file_base64"] = file_base64
        user_msg["message_type"] = "file"
    if file_name:
        user_msg["file_name"] = file_name
    msg_id_user = append_message(cid, user_msg)
    append_message(cid, {"sender": assistant_name, "content": assistant_content, "type": "assistant"})
    return msg_id_user


def append_assistant_or_conv_msg(imei: str, conversation_id: str, sender: str, content: str, msg_type: str = "assistant", **kwargs) -> str:
    """按 conversation_id 追加消息。conversation_id 为 assistant 时用 append_assistant_msg，否则用统一存储。
    多 session 格式: assistantId__sessionId，存储时 __ 转为 _ 以匹配 _conv_id_custom_assistant"""
    if conversation_id in ("assistant", ""):
        return append_assistant_msg(imei, sender=sender, content=content, type=msg_type, **kwargs)
    conv = conversation_id.strip()
    conv = conv.replace("__", "_")  # 多 session: custom_xxx__sess123 -> custom_xxx_sess123
    cid = f"{imei.strip()}_{conv}" if not conv.startswith(imei) else conv
    msg = {"sender": sender, "content": content, "type": msg_type, **kwargs}
    return append_message(cid, msg)


def get_custom_assistant_messages(
    imei: str,
    assistant_id: str,
    before_id: Optional[str] = None,
    limit: int = 100,
    since_timestamp_ms: Optional[int] = None,
    session_id: Optional[str] = None,
):
    """获取自定义小助手聊天历史。多 session 时传入 session_id"""
    cid = _conv_id_custom_assistant(imei, assistant_id, session_id)
    return get_messages(cid, before_id, limit, since_timestamp_ms)


load_store()
