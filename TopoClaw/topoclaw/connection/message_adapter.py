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

"""Inbound/Outbound message adapters for chat service."""

from __future__ import annotations

from typing import Any

from topoclaw.agent.session_keys import websocket_session_key
from topoclaw.models.api import ChatRequest
from topoclaw.bus.events import InboundMessage, OutboundMessage


class MessageAdapter:
    """Adapter to normalize API DTOs into bus message types."""

    def build_inbound(
        self,
        req: ChatRequest,
        *,
        inbound_channel: str,
        metadata: dict[str, Any] | None = None,
        session_key_override: str | None = None,
    ) -> InboundMessage:
        meta = dict(metadata or {})
        if session_key_override is not None:
            sk = session_key_override
        elif inbound_channel == "websocket":
            aid = getattr(req, "agent_id", None)
            if aid is None:
                aid = meta.get("agent_id")
            sk = websocket_session_key(aid, req.thread_id)
        else:
            sk = f"{inbound_channel}:{req.thread_id}"
        return InboundMessage(
            channel=inbound_channel,
            sender_id="api_user",
            chat_id=req.thread_id,
            content=req.message,
            media=req.images or [],
            metadata=meta,
            session_key_override=sk,
        )

    def extract_outbound_content(self, msg: OutboundMessage | None) -> str:
        if not msg:
            return ""
        return msg.content or ""
