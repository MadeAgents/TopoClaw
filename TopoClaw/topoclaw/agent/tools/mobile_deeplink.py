"""Tool for executing deeplinks on mobile via relay."""

from __future__ import annotations

import json
from typing import Any

from topoclaw.agent.tools.base import Tool
from topoclaw.service.gui_mobile_service import dispatch_mobile_tool_request


class ExecuteMobileDeeplinkTool(Tool):
    """Invoke phone-side deeplink execution and return result."""

    def __init__(self) -> None:
        self._thread_id = ""

    def set_context(self, channel: str, chat_id: str, metadata: dict[str, Any] | None = None) -> None:
        _ = channel, metadata
        self._thread_id = str(chat_id or "").strip()

    @property
    def name(self) -> str:
        return "execute_mobile_deeplink"

    @property
    def description(self) -> str:
        return (
            "Execute deeplink on user's phone through TopoMobile relay. "
            "Requires a valid deeplink string and current chat thread binding."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "deeplink": {
                    "type": "string",
                    "description": "Target deeplink to execute, e.g. alipays://..., intent:#Intent;...;end",
                    "minLength": 1,
                },
                "timeout_s": {
                    "type": "integer",
                    "description": "Wait timeout seconds for mobile execution result.",
                    "minimum": 3,
                    "maximum": 60,
                },
            },
            "required": ["deeplink"],
        }

    async def execute(self, deeplink: str, timeout_s: int = 20, **kwargs: Any) -> str:
        link = str(deeplink or "").strip()
        if not link:
            return json.dumps({"ok": False, "error": "deeplink is required"}, ensure_ascii=False)
        if not self._thread_id:
            return json.dumps({"ok": False, "error": "missing thread context"}, ensure_ascii=False)

        result = await dispatch_mobile_tool_request(
            thread_id=self._thread_id,
            tool="device.open_deeplink",
            args={"deeplink": link},
            timeout_s=timeout_s,
            protocol="mobile_tool/v1",
        )
        if not bool(result.get("success")):
            return json.dumps(
                {
                    "ok": False,
                    "request_id": str(result.get("request_id") or ""),
                    "deeplink": link,
                    "error": str(result.get("error") or "mobile deeplink execution failed"),
                },
                ensure_ascii=False,
            )
        return json.dumps(
            {
                "ok": True,
                "request_id": str(result.get("request_id") or ""),
                "deeplink": link,
                "result": str(result.get("content") or ""),
            },
            ensure_ascii=False,
        )

