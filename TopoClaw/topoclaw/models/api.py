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

"""Data models for service HTTP/WS API routes."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    """Request body for /chat and /chat/stream."""

    thread_id: str
    message: str
    images: list[str] = Field(default_factory=list, max_length=3)
    agent_id: str | None = None


class ChatResponse(BaseModel):
    """Response body for /chat."""

    response: str
    thread_id: str
    need_execution: bool | None = None
    reason: str | None = None
    chat_summary: str | None = None


class TaskStatusResponse(BaseModel):
    """Task status response model."""

    message_id: str
    status: str  # pending, processing, completed, error
    content: str | None = None
    error: str | None = None
    progress: list[str] | None = None


class BindRequest(BaseModel):
    """Bind a thread to external delivery target."""

    thread_id: str
    channel: str
    chat_id: str


class WsChatRequest(BaseModel):
    """Request envelope for websocket chat protocol."""

    request_id: str | None = None
    type: str = "chat"  # chat | chat_stream
    transport: str = "https"  # https | ws (default https)
    payload: ChatRequest


class SummarizeChatRequest(BaseModel):
    """Best-effort request model for /api/summarize-chat compatibility."""

    message: str | None = None
    query: str | None = None
    chat_summary: str | None = None
    summary: str | None = None
    conversation: list[dict[str, Any]] | None = None
    messages: list[dict[str, Any]] | None = None
