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
from __future__ import annotations

"""HTTP chat routes."""
"""HTTP chat routes.
历史遗留产物，本地topoclaw进程目前统一走websocket
"""


import asyncio
import json
from typing import Any

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse

from topoclaw.models.api import ChatRequest, ChatResponse
from topoclaw.models.constant import TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
from topoclaw.service.chat_service import ChatService

router = APIRouter(prefix="", tags=["chat"])


def _chat_service(request: Request) -> ChatService:
    return request.app.state.chat_service


@router.get("/health")
async def health() -> dict[str, Any]:
    return {"status": "healthy"}


@router.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, request: Request) -> ChatResponse:
    service = _chat_service(request)
    response, need_execution, reason, chat_summary = await service.run_chat_turn(req, inbound_channel="http")
    return ChatResponse(
        response=response,
        thread_id=req.thread_id,
        need_execution=need_execution,
        reason=reason,
        chat_summary=chat_summary,
    )


@router.post("/chat/stream")
async def chat_stream(req: ChatRequest, request: Request):
    service = _chat_service(request)
    queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()

    async def _progress(
        content: str,
        *,
        tool_hint: bool = False,
        tool_guard: bool = False,
        reasoning: bool = False,
    ) -> str | None:
        if tool_guard:
            return TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
        if tool_hint:
            await queue.put({"tool_call": content.split("(", 1)[0].strip()})
            return None
        if reasoning:
            await queue.put({"assistant_reasoning": content})
            return None
        await queue.put({"delta": content})
        return None

    async def _run() -> None:
        try:
            response, need_execution, reason, chat_summary, skill_generated = await service.run_chat_turn(
                req,
                inbound_channel="http",
                progress_cb=_progress,
                include_skill=True,
            )
            await queue.put({"delta": response})
            if skill_generated:
                await queue.put({"skill_generated": skill_generated})
            await queue.put({"need_execution": need_execution})
            if reason:
                await queue.put({"reason": reason})
            if chat_summary:
                await queue.put({"chat_summary": chat_summary})
        except Exception as exc:
            await queue.put({"error": str(exc)})
        finally:
            await queue.put(None)

    async def _gen():
        runner = asyncio.create_task(_run())
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                yield f"data: {json.dumps(item, ensure_ascii=False)}\n\n"
        finally:
            if not runner.done():
                runner.cancel()
                await asyncio.gather(runner, return_exceptions=True)

    return StreamingResponse(_gen(), media_type="text/event-stream")
