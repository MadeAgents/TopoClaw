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

"""Tool to spawn the browser-use interactive subagent (pause/resume for login, captcha, etc.)."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from topoclaw.agent.executors.browser_use_executor import (
    BROWSER_USE_TASK_TYPE,
    normalize_browser_start_url,
)
from topoclaw.agent.tools.base import Tool

if TYPE_CHECKING:
    from topoclaw.agent.task_supervisor import TaskSupervisor


class SpawnBrowserInteractiveTaskTool(Tool):
    """Starts the browser-use :class:`~topoclaw.agent.executors.browser_use_executor.BrowserUseExecutor` subagent."""

    def __init__(self, manager: "TaskSupervisor"):
        self._manager = manager
        self._origin_channel = "cli"
        self._origin_chat_id = "direct"
        self._session_key = "cli:direct"
        self._origin_metadata: dict[str, Any] = {}

    def set_context(
        self,
        channel: str,
        chat_id: str,
        session_key: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        self._origin_channel = channel
        self._origin_chat_id = chat_id
        self._session_key = session_key or f"{channel}:{chat_id}"
        self._origin_metadata = dict(metadata or {})

    @property
    def name(self) -> str:
        return "spawn_browser_interactive_task"

    @property
    def description(self) -> str:
        return (
            "Spawn a background **browser automation** task (browser-use) that can pause for human assistance "
            "and resume later. Runs independently without blocking the main conversation. "
            "When it needs user input (e.g., login, captcha), it pauses and notifies you. "
            "If the task fails or aborts because it requires visible human interaction (like a captcha or login) "
            "while in headless mode, you MUST inform the user to enable headed mode in their configuration "
            "and instruct them to retry the task.\n\n"
            "IMPORTANT: You must provide 'original_query' — the user's original request that triggered this task — "
            "so the system can associate the task result with the original intent when injecting it back.\n\n"
            "Optional 'start_url': if set, the browser opens this URL first (navigate) before continuing with "
            "'description'. Omit the scheme to default to https.\n\n"
            "Requires tools.interactive.enabled=true, tools.browser_use.enabled=true, and browser-use dependencies."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "Clear natural-language goal for the browser task.",
                    "minLength": 1,
                },
                "original_query": {
                    "type": "string",
                    "description": "The user's original request that triggered this task.",
                },
                "timeout_s": {
                    "type": "integer",
                    "description": "Max wall-clock seconds before timeout (optional, uses default if not set).",
                    "minimum": 30,
                    "maximum": 7200,
                },
                "max_steps": {
                    "type": "integer",
                    "description": "Maximum steps for the task (optional, uses default if not set).",
                    "minimum": 1,
                    "maximum": 500,
                },
                "start_url": {
                    "type": "string",
                    "description": (
                        "Optional. Page to open first (https://…). If omitted, the agent relies on "
                        "'description' / URLs inside it. Scheme defaults to https when missing."
                    ),
                },
                "flash_mode": {
                    "type": "boolean",
                    "description": "Fast mode that skips evaluation, next goal, and thinking, using only memory. Defaults to False.",
                },
            },
            "required": ["description", "original_query"],
        }

    async def execute(
        self,
        description: str,
        original_query: str,
        timeout_s: int | None = None,
        max_steps: int | None = None,
        start_url: str | None = None,
        flash_mode: bool | None = None,
        **kwargs: Any,
    ) -> str:
        config: dict[str, Any] = {}
        if timeout_s is not None:
            config["timeout_s"] = timeout_s
        if max_steps is not None:
            config["max_steps"] = max_steps
        if flash_mode is not None:
            config["flash_mode"] = flash_mode
        if start_url is not None and str(start_url).strip():
            normalized = normalize_browser_start_url(str(start_url))
            if not normalized:
                return (
                    "错误: start_url 无效。请提供可解析的链接（例如 https://example.com 或 example.com）。"
                )
            config["start_url"] = normalized

        try:
            task_id = self._manager.spawn_task(
                task_type=BROWSER_USE_TASK_TYPE,
                description=description,
                original_query=original_query,
                session_key=self._session_key,
                channel=self._origin_channel,
                chat_id=self._origin_chat_id,
                metadata=self._origin_metadata,
                config=config if config else None,
            )

            lines = [
                f"已启动后台浏览器交互任务（task_id: {task_id}）。",
                f"目标: {description[:80]}{'...' if len(description) > 80 else ''}",
            ]
            if config.get("start_url"):
                lines.append(f"起始页: {config['start_url']}")
            lines.append("")
            lines.append("任务会在后台运行，需要您协助时会暂停并通知您。")
            return "\n".join(lines)
        except ValueError as e:
            return f"错误: {e}"
        except Exception as e:
            return f"启动任务失败: {e}"
