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

"""Heartbeat service - periodic agent wake-up to check for tasks."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, Coroutine

from loguru import logger

if TYPE_CHECKING:
    from topoclaw.providers.base import LLMProvider

_HEARTBEAT_TOOL = [
    {
        "type": "function",
        "function": {
            "name": "heartbeat",
            "description": "Report heartbeat decision after reviewing tasks.",
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["skip", "run"],
                        "description": "skip = nothing to do, run = has active tasks",
                    },
                    "tasks": {
                        "type": "string",
                        "description": "Natural-language summary of active tasks (required for run)",
                    },
                },
                "required": ["action"],
            },
        },
    }
]

_PROACTIVE_TOOL = [
    {
        "type": "function",
        "function": {
            "name": "proactive",
            "description": (
                "Report timing decision after reading MEMORY.md and EVENT_MEMORY.md: "
                "stay silent, ask a needed question, or brief casual chat."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["silent", "question", "chat"],
                        "description": (
                            "silent: do not interrupt; "
                            "question: good time for one clarifying/blocking question; "
                            "chat: good time for light, non-blocking rapport or check-in"
                        ),
                    },
                    "content": {
                        "type": "string",
                        "description": (
                            "Draft message to send when action is question or chat; "
                            "empty when silent."
                        ),
                    },
                },
                "required": ["action"],
            },
        },
    }
]

class HeartbeatService:
    """
    Periodic heartbeat service that wakes the agent to check for tasks.

    Phase 1 (decision): reads HEARTBEAT.md and asks the LLM — via a virtual
    tool call — whether there are active tasks.  This avoids free-text parsing
    and the unreliable HEARTBEAT_OK token.

    Phase 2 (execution): only triggered when Phase 1 returns ``run``.  The
    ``on_execute`` callback runs the task through the full agent loop and
    returns the result to deliver.
    """

    def __init__(
        self,
        workspace: Path,
        provider: LLMProvider,
        model: str,
        on_execute: Callable[[str], Coroutine[Any, Any, str]] | None = None,
        on_notify: Callable[[str], Coroutine[Any, Any, None]] | None = None,
        interval_s: int = 30 * 60,
        enabled: bool = True,
    ):
        self.workspace = workspace
        self.provider = provider
        self.model = model
        self.on_execute = on_execute
        self.on_notify = on_notify
        self.interval_s = interval_s
        self.enabled = enabled
        self._running = False
        self._task: asyncio.Task | None = None

    @property
    def heartbeat_file(self) -> Path:
        return self.workspace / "HEARTBEAT.md"

    @property
    def long_term_memory_file(self) -> Path:
        return self.workspace / "memory" / "MEMORY.md"

    @property
    def event_memory_file(self) -> Path:
        return self.workspace / "memory" / "EVENT_MEMORY.md"


    def _read_heartbeat_file(self) -> str | None:
        if self.heartbeat_file.exists():
            try:
                return self.heartbeat_file.read_text(encoding="utf-8")
            except Exception:
                return None
        return None

    async def _decide(self, content: str) -> tuple[str, str]:
        """Phase 1: ask LLM to decide skip/run via virtual tool call.

        Returns (action, tasks) where action is 'skip' or 'run'.
        """
        response = await self.provider.chat_with_retry(
            messages=[
                {"role": "system", "content": "You are a heartbeat agent. Call the heartbeat tool to report your decision."},
                {"role": "user", "content": (
                    "Review the following HEARTBEAT.md and decide whether there are active tasks.\n\n"
                    f"{content}"
                )},
            ],
            tools=_HEARTBEAT_TOOL,
            model=self.model,
        )

        if not response.has_tool_calls:
            return "skip", ""

        args = response.tool_calls[0].arguments
        return args.get("action", "skip"), args.get("tasks", "")

    def _read_memory_snapshot(self) -> tuple[str, str]:
        """Return (MEMORY.md text, EVENT_MEMORY.md text); empty string if missing/unreadable."""
        long_term = ""
        event = ""
        if self.long_term_memory_file.exists():
            try:
                long_term = self.long_term_memory_file.read_text(encoding="utf-8")
            except Exception:
                pass
        if self.event_memory_file.exists():
            try:
                event = self.event_memory_file.read_text(encoding="utf-8")
            except Exception:
                pass
        return long_term.strip(), event.strip()

    async def _proactive(self) -> tuple[str, str]:
        """Decide from memory whether to stay silent, ask a question, or light chat."""
        long_term_memory, event_memory = self._read_memory_snapshot()

        if not long_term_memory and not event_memory:
            return "silent", ""

        system = (
            "你是一名可爱但高情商犯贱的智能助手。你的任务是观察对话节奏与历史记录，避开工作高峰。优先选择放松时段发起互动，注意共情，聚焦对方擅长领域，避免问易查信息。    "
            "语言风格: 以撒娇语气与示弱姿态为核心，通过‘温柔时机切入+适度夸赞+模糊求助’引导对话，"
            "用‘嗯～’、‘好不好嘛’等自然叠词软化表达，避免直接要求，将需求包裹在共情与崇拜中（如‘你好厉害，教教我呀～’），"
            "同时保持边界感，确保互动轻盈不越界，让回应既体贴又留有余地。\n\n"
            "Read MEMORY.md (long-term) and EVENT_MEMORY.md (recent). "
            "Call the proactive tool once.\n\n"
        )
        user = (
            "MEMORY.md:\n"
            f"{long_term_memory or '(empty)'}\n\n"
            "EVENT_MEMORY.md:\n"
            f"{event_memory or '(empty)'}\n\n"
            "上面是之前的记忆，现在请做出你的决定吧~ 记得用中文回答"
        )
        response = await self.provider.chat_with_retry(
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            tools=_PROACTIVE_TOOL,
            model=self.model,
        )
        # import pdb; pdb.set_trace()
        if not response.has_tool_calls:
            return "silent", ""
        args = response.tool_calls[0].arguments
        action = args.get("action", "silent")
        content = (args.get("content") or "").strip()
        if action not in ("question", "chat"):
            return "silent", ""
        if action in ("question", "chat") and not content:
            return "silent", ""
        return action, content


    async def start(self) -> None:
        """Start the heartbeat service."""
        if not self.enabled:
            logger.info("Heartbeat disabled")
            return
        if self._running:
            logger.warning("Heartbeat already running")
            return

        self._running = True
        self._task = asyncio.create_task(self._run_loop())
        logger.info("Heartbeat started (every {}s)", self.interval_s)

    def stop(self) -> None:
        """Stop the heartbeat service."""
        self._running = False
        if self._task:
            self._task.cancel()
            self._task = None

    async def _run_loop(self) -> None:
        """Main heartbeat loop."""
        while self._running:
            try:
                await asyncio.sleep(self.interval_s)
                if self._running:
                    await self._tick()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Heartbeat error: {}", e)

    async def _tick(self) -> None:
        """Execute a single heartbeat tick."""
        content = self._read_heartbeat_file()
        if not content:
            logger.debug("Heartbeat: HEARTBEAT.md missing or empty")
            return

        logger.info("Heartbeat: checking for tasks...")

        try:
            # 先主动提问，再执行
            # action, content = await self._proactive()

            # if action != "silent":
            #     logger.info("Heartbeat: proactive action: {}", action)
            #     if self.on_notify:
            #         await self.on_notify(content)

            action, tasks = await self._decide(content)

            if action != "run":
                logger.info("Heartbeat: OK (nothing to report)")
                return

            logger.info("Heartbeat: tasks found, executing...")
            if self.on_execute:
                response = await self.on_execute(tasks)
                if response and self.on_notify:
                    logger.info("Heartbeat: completed, delivering response")
                    await self.on_notify(response)
        except Exception:
            logger.exception("Heartbeat execution failed")

    async def trigger_now(self) -> str | None:
        """Manually trigger a heartbeat."""
        content = self._read_heartbeat_file()
        if not content:
            return None
        action, tasks = await self._decide(content)
        if action != "run" or not self.on_execute:
            return None
        return await self.on_execute(tasks)
