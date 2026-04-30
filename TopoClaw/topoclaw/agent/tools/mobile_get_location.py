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

"""Tool for fetching current mobile geo location via mobile_tool/v1 relay."""

from __future__ import annotations

import json
from typing import Any

from topoclaw.agent.tools.base import Tool
from topoclaw.service.gui_mobile_service import dispatch_mobile_tool_request


class MobileGetLocationTool(Tool):
    """Call phone-side `device.get_location` capability over relay."""

    def __init__(self) -> None:
        self._channel = "cli"
        self._chat_id = "direct"
        self._imei = ""

    def set_context(self, channel: str, chat_id: str, metadata: dict[str, Any] | None = None) -> None:
        self._channel = channel
        self._chat_id = chat_id
        self._imei = str((metadata or {}).get("imei") or "").strip()

    @property
    def name(self) -> str:
        return "mobile_get_location"

    @property
    def description(self) -> str:
        return (
            "Get the current geo location from user's phone via mobile relay. "
            "Use when user asks current location/coordinates or nearby context."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "accuracy": {
                    "type": "string",
                    "enum": ["coarse", "fine"],
                    "description": "Location precision. fine may need precise permission.",
                },
                "with_address": {
                    "type": "boolean",
                    "description": "Whether to ask device to reverse geocode address.",
                },
                "timeout_s": {
                    "type": "integer",
                    "minimum": 3,
                    "maximum": 120,
                    "description": "Timeout seconds waiting mobile result.",
                },
            },
            "required": [],
        }

    async def execute(
        self,
        accuracy: str = "coarse",
        with_address: bool = False,
        timeout_s: int = 20,
        **kwargs: Any,
    ) -> str:
        resolved_accuracy = str(accuracy or "coarse").strip().lower()
        if resolved_accuracy not in {"coarse", "fine"}:
            resolved_accuracy = "coarse"
        try:
            timeout_value = int(timeout_s)
        except (TypeError, ValueError):
            timeout_value = 20
        bounded_timeout = max(3, min(timeout_value, 120))

        result = await dispatch_mobile_tool_request(
            thread_id=self._chat_id,
            tool="device.get_location",
            args={
                "accuracy": resolved_accuracy,
                "with_address": bool(with_address),
                "timeout_ms": bounded_timeout * 1000,
            },
            timeout_s=bounded_timeout,
            protocol="mobile_tool/v1",
        )
        if not bool(result.get("success")):
            return f"Error: {result.get('error') or 'mobile get location failed'}"

        content = str(result.get("content") or "").strip()
        if not content:
            return "Error: mobile_get_location returned empty content"
        # Keep JSON shape if possible for follow-up tool reasoning.
        try:
            parsed = json.loads(content)
            return json.dumps(parsed, ensure_ascii=False)
        except Exception:
            return content
