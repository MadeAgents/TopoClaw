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

"""Tool for GUI agents to directly ask users questions."""

import asyncio
import uuid
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool
from topoclaw.bus.events import OutboundMessage


class AskUserTool(Tool):
    """GUI Agent直接询问用户的工具"""

    def __init__(
        self,
        send_callback,
        agent_type: str,
        channel: str,
        chat_id: str,
        session_key: str,
    ):
        """Initialize AskUserTool.

        Args:
            send_callback: Callback to send messages to user
            agent_type: Type of agent (e.g., "mobile_gui", "desktop_gui")
            channel: Message channel
            chat_id: Chat ID
            session_key: Session key for tracking
        """
        self._send_callback = send_callback
        self.agent_type = agent_type
        self.channel = channel
        self.chat_id = chat_id
        self.session_key = session_key
        self._pending_questions: dict[str, asyncio.Future] = {}

    @property
    def name(self) -> str:
        return "ask_user"

    @property
    def description(self) -> str:
        return (
            "直接询问用户问题，等待用户回复。用于需要用户输入的场景。"
            "这是GUI Agent专用的工具，可以直接与用户交互。"
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "要询问用户的问题",
                },
                "options": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "可选答案列表（可选）",
                },
                "timeout": {
                    "type": "integer",
                    "description": "超时时间（秒），默认60",
                    "default": 60,
                },
            },
            "required": ["question"],
        }

    async def execute(
        self,
        question: str,
        options: list[str] | None = None,
        timeout: int = 60,
        **kwargs: Any,
    ) -> str:
        """直接询问用户，返回用户回复"""
        question_id = str(uuid.uuid4())[:8]
        future = asyncio.Future()
        self._pending_questions[question_id] = future

        # 构建问题消息
        question_text = question
        if options:
            question_text += f"\n选项: {', '.join(options)}"

        # 发送问题给用户（标记来源）
        msg = OutboundMessage(
            channel=self.channel,
            chat_id=self.chat_id,
            content=f"[{self.agent_type}] {question_text}",
            metadata={
                "_interactive": True,
                "_type": "question",
                "_question_id": question_id,
                "_source_agent": self.agent_type,
                "_session_key": self.session_key,
                "_options": options or [],
            },
        )

        try:
            await self._send_callback(msg)
            logger.info("GUI Agent [{}] asked user: {}", self.agent_type, question[:50])

            # 等待用户回复（通过reply_handler处理）
            # 注意：实际实现中需要通过AgentBus或回调机制接收回复
            try:
                reply = await asyncio.wait_for(future, timeout=timeout)
                logger.info("GUI Agent [{}] received reply: {}", self.agent_type, reply[:50])
                return reply
            except asyncio.TimeoutError:
                error_msg = f"用户未在{timeout}秒内回复"
                logger.warning("GUI Agent [{}] question timeout", self.agent_type)
                return f"Error: {error_msg}"
        except Exception as e:
            logger.error("Error asking user: {}", e)
            return f"Error: 发送问题失败 - {str(e)}"
        finally:
            self._pending_questions.pop(question_id, None)

    def handle_user_reply(self, question_id: str, reply: str) -> bool:
        """处理用户回复（由消息路由器调用）"""
        future = self._pending_questions.get(question_id)
        if future and not future.done():
            future.set_result(reply)
            return True
        return False

    def set_context(self, channel: str, chat_id: str, session_key: str) -> None:
        """更新上下文"""
        self.channel = channel
        self.chat_id = chat_id
        self.session_key = session_key
