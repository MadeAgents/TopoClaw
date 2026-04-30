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
收件箱聚合增量同步（方案 B）：一次请求拉取当前用户各好友单聊、群聊、自动执行小助手在 since 之后的新消息。
"""
import logging
import time
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from services.group_service import get_user_groups
from services.unified_message_store import (
    get_assistant_messages,
    get_friend_messages,
    get_group_messages,
    parse_created_at_ms,
)
from storage.friends import friends_storage

logger = logging.getLogger(__name__)
router = APIRouter(tags=["inbox"])


def _chronological(msgs: List[Dict]) -> List[Dict]:
    return sorted(msgs, key=lambda m: parse_created_at_ms(m))


@router.get("/api/inbox/sync")
async def inbox_sync(
    imei: str,
    since_timestamp: int = 0,
    limit_per_conversation: int = 80,
):
    """
    聚合增量同步。since_timestamp>0 时仅返回各会话中 created_at 晚于该毫秒时间戳的消息；
    since_timestamp==0 时返回各会话最近 limit_per_conversation 条（按时间倒序截取后的时间正序列表）。
    """
    try:
        imei_str = (imei or "").strip()
        if not imei_str:
            raise HTTPException(status_code=400, detail="缺少 imei")

        cap = max(10, min(int(limit_per_conversation or 80), 200))
        since_ms: Optional[int] = int(since_timestamp) if int(since_timestamp) > 0 else None

        conversations: Dict[str, List[Dict[str, Any]]] = {}

        for friend_imei in friends_storage.get(imei_str, []):
            fid = (friend_imei or "").strip()
            if not fid:
                continue
            try:
                msgs, _ = get_friend_messages(imei_str, fid, None, cap, since_ms)
            except Exception as e:
                logger.warning(f"inbox_sync friend {fid[:8]}...: {e}")
                continue
            if msgs:
                cid = f"friend_{fid}"
                conversations[cid] = _chronological(msgs)

        for group in get_user_groups(imei_str):
            gid_raw = (group.get("group_id") or "").strip()
            if not gid_raw:
                continue
            gkey = gid_raw.replace("group_", "", 1) if gid_raw.startswith("group_") else gid_raw
            try:
                msgs, _ = get_group_messages(gkey, None, cap, since_ms)
            except Exception as e:
                logger.warning(f"inbox_sync group {gkey[:16]}...: {e}")
                continue
            if msgs:
                cid = gid_raw if gid_raw.startswith("group_") else f"group_{gid_raw}"
                conversations[cid] = _chronological(msgs)

        try:
            msgs, _ = get_assistant_messages(imei_str, None, cap, since_ms)
            if msgs:
                conversations["assistant"] = _chronological(msgs)
        except Exception as e:
            logger.warning(f"inbox_sync assistant: {e}")

        total = sum(len(v) for v in conversations.values())
        logger.info(
            f"inbox_sync imei={imei_str[:8]}... since={since_timestamp} cap={cap} convs={len(conversations)} msgs={total}"
        )

        return JSONResponse(
            {
                "success": True,
                "conversations": conversations,
                "server_time_ms": int(time.time() * 1000),
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"inbox_sync 失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
