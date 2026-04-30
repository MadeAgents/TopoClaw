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

import logging
import time
from typing import Any, Dict, List

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from services.conversation_summary_store import append_entries, list_entries

logger = logging.getLogger(__name__)
router = APIRouter(tags=["conversation-summaries"])


class ConversationSummarySyncRequest(BaseModel):
    imei: str = Field(..., description="用户 IMEI")
    entries: List[Dict[str, Any]] = Field(default_factory=list, description="本地新增摘要（append-only）")
    since_ts: int = Field(default=0, description="拉取云端增量起点 createdAt(ms)")
    limit: int = Field(default=2000, description="最多拉取条数")


@router.post("/api/conversation-summaries/sync")
async def conversation_summaries_sync(request: ConversationSummarySyncRequest):
    """
    双向增量同步：
    1) 先幂等写入客户端上传 entries
    2) 再返回 since_ts 之后的云端摘要（按 createdAt 升序）
    """
    try:
        imei = (request.imei or "").strip()
        if not imei:
            raise HTTPException(status_code=400, detail="imei 必填")
        accepted, total = append_entries(imei, request.entries or [])
        cloud_entries = list_entries(imei, request.since_ts, request.limit)
        logger.info(
            "conversation_summaries_sync imei=%s... uploaded=%s accepted=%s returned=%s since=%s",
            imei[:8],
            total,
            accepted,
            len(cloud_entries),
            request.since_ts,
        )
        return JSONResponse(
            {
                "success": True,
                "accepted": accepted,
                "uploaded": total,
                "entries": cloud_entries,
                "server_time_ms": int(time.time() * 1000),
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error("conversation_summaries_sync 失败: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
