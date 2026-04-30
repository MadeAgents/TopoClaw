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

"""Binding service for API thread targets and SSE stream."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import HTTPException, Request
from fastapi.responses import StreamingResponse

from topoclaw.models.api import BindRequest
from topoclaw.service.runtime import ServiceRuntime


class BindingService:
    """Service layer for thread binding and active push stream."""

    def __init__(self, runtime: ServiceRuntime) -> None:
        self.runtime = runtime

    async def bind(self, req: BindRequest) -> dict[str, Any]:
        await self.runtime.bindings.set(req.thread_id, req.channel, req.chat_id)
        return {"status": "ok", "thread_id": req.thread_id}

    async def get_binding(self, thread_id: str) -> dict[str, Any]:
        target = await self.runtime.bindings.get(thread_id)
        if not target:
            raise HTTPException(status_code=404, detail="binding not found")
        return {
            "thread_id": thread_id,
            "channel": target.channel,
            "chat_id": target.chat_id,
            "updated_at": target.updated_at,
        }

    async def delete_binding(self, thread_id: str) -> dict[str, Any]:
        deleted = await self.runtime.bindings.delete(thread_id)
        return {"status": "ok", "deleted": deleted}

    async def events_stream(self, request: Request, thread_id: str) -> StreamingResponse:
        queue = await self.runtime.events.subscribe(thread_id)

        async def _gen():
            try:
                while True:
                    if await request.is_disconnected():
                        break
                    try:
                        item = await asyncio.wait_for(queue.get(), timeout=15)
                    except asyncio.TimeoutError:
                        yield f"data: {json.dumps({'delta': '', 'thread_id': thread_id}, ensure_ascii=False)}\n\n"
                        continue
                    yield f"data: {json.dumps(item, ensure_ascii=False)}\n\n"
            finally:
                await self.runtime.events.unsubscribe(thread_id, queue)

        return StreamingResponse(_gen(), media_type="text/event-stream")
