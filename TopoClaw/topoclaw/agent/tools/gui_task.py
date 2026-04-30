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

"""Tool for dispatching GUI requests to the linked mobile device via websocket."""

from __future__ import annotations

import json
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool
from topoclaw.service.gui_mobile_service import dispatch_gui_execute_request as dispatch_mobile_gui_execute_request


class GUITaskTool(Tool):
    """Submit a GUI task to the linked phone only; wait for execution result."""

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
        return "gui_task"

    @property
    def description(self) -> str:
        return (
            "Run a task on the user's phone (GUI automation over websocket). "
            "Do not use for general questions or desktop—only when the user needs something done on the phone. "
            "At most one call per attempt; if it fails, use other tools or a different plan."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "What to do on the phone (concrete steps).",
                    "minLength": 1,
                },
                "thread_id": {
                    "type": "string",
                    "description": "Optional thread ID. Defaults to current chat_id context.",
                    "minLength": 1,
                },
                "timeout_s": {
                    "type": "integer",
                    "description": "Wait timeout in seconds (default 120).",
                    "minimum": 1,
                    "maximum": 600,
                },
                "chat_summary": {
                    "type": "string",
                    "description": "Optional summary to help mobile execution.",
                },
                "target_imei": {
                    "type": "string",
                    "description": "Optional explicit target IMEI. Use this when multiple devices are online.",
                    "minLength": 1,
                },
            },
            "required": ["task"],
        }

    async def execute(
        self,
        task: str,
        thread_id: str | None = None,
        timeout_s: int = 1200,
        chat_summary: str | None = None,
        target_imei: str | None = None,
        **kwargs: Any,
    ) -> str:
        task = (task or "").strip()
        if not task:
            return "Error: task cannot be empty."

        resolved_thread_id = (thread_id or self._chat_id or "").strip()
        if not resolved_thread_id:
            return "Error: missing thread_id and no tool context chat_id."
        bounded_timeout = max(1, min(int(timeout_s), 600))
        resolved_target_imei = str(target_imei or "").strip() or self._imei

        data = await dispatch_mobile_gui_execute_request(
            thread_id=resolved_thread_id,
            query=task,
            chat_summary=chat_summary,
            timeout_s=bounded_timeout,
            target_imei=resolved_target_imei or None,
        )

        # 构建返回结果
        success = bool(data.get("success"))
        error = str(data.get("error") or "")
        result_text = str(data.get("content") or "")
        combined_text = f"{error}\n{result_text}".lower()
        error_type = ""

        if not success:
            if "超时" in combined_text or "timeout" in combined_text:
                error_type = "timeout"
            elif (
                "步数超限" in combined_text
                or "最大步数" in combined_text
                or "max_steps" in combined_text
            ):
                error_type = "max_steps_exceeded"
            elif "任务已停止" in combined_text or "stopped" in combined_text:
                error_type = "stopped"
            elif (
                "无手机端在线" in combined_text
                or "没有在线连接" in combined_text
                or "not online" in combined_text
                or "offline" in combined_text
            ):
                error_type = "device_offline"
            else:
                error_type = "dispatch_failed"

        # 如果失败，添加更友好的错误提示
        if not success and error:
            if error_type == "device_offline" or "gui service unavailable" in error.lower():
                error = (
                    f"{error}\n\n"
                    "执行建议:\n"
                    "- 当前无可用在线设备连接。\n"
                    "- 短时间内不要再调用 gui_task。\n"
                    "- 请尝试其他工具或方案。"
                )
            if error_type in {"timeout", "max_steps_exceeded"}:
                error = (
                    f"{error}\n\n"
                    f"执行建议:\n"
                    f"- 当前失败类型: {error_type}\n"
                    f"- 不要继续重复调用 gui_task（单次问题最多 1 次）\n"
                    f"- 请尝试其他工具或方案（例如先用 read_file/web_search 获取信息后再决策）"
                )
        elif not success and not error:
            error = (
                "GUI task failed without detailed error.\n\n"
                "执行建议:\n"
                "- 不要继续重复调用 gui_task（单次问题最多 1 次）\n"
                "- 请尝试其他工具或方案。"
            )

        out = json.dumps(
            {
                "success": success,
                "request_id": data.get("request_id", ""),
                "thread_id": resolved_thread_id,
                "device_type": "mobile",
                "result": result_text,
                "error": error,
                "error_type": error_type,
                "source": "unified_ws",
            },
            ensure_ascii=False,
        )
        # Log to terminal so gui_task execution result is visible in server logs
        if success:
            preview = (result_text or "")[:200] + ("..." if len(result_text or "") > 200 else "")
            logger.info(
                "[gui_task] success=true device_type=mobile thread_id={} result_preview={}",
                resolved_thread_id,
                preview or "(empty)",
            )
        else:
            logger.info(
                "[gui_task] success=false device_type=mobile error_type={} error={}",
                error_type or "(none)",
                (error or "")[:300],
            )
        return out
