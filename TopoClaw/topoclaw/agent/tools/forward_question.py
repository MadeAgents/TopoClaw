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

"""Tool for main agent to forward questions from sub-agents to users."""

import asyncio
import uuid
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool
from topoclaw.bus.events import OutboundMessage


class ForwardQuestionTool(Tool):
    """主Agent转发问题的工具"""

    def __init__(
        self,
        send_callback,
        agent_bus,
        channel: str,
        chat_id: str,
    ):
        """Initialize ForwardQuestionTool.

        Args:
            send_callback: Callback to send messages to user
            agent_bus: Agent communication bus
            channel: Message channel
            chat_id: Chat ID
        """
        self._send_callback = send_callback
        self.agent_bus = agent_bus
        self.channel = channel
        self.chat_id = chat_id
        self._pending_forwards: dict[str, asyncio.Future] = {}

    @property
    def name(self) -> str:
        return "forward_question"

    @property
    def description(self) -> str:
        return (
            "转发SubAgent的问题给用户，并将用户回复返回给SubAgent。"
            "这是主Agent专用的工具，用于统一管理用户交互。"
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "question": {
                    "type": "string",
                    "description": "要转发给用户的问题",
                },
                "source_agent": {
                    "type": "string",
                    "description": "发起问题的Agent类型",
                },
                "task_id": {
                    "type": "string",
                    "description": "任务ID",
                },
                "context": {
                    "type": "string",
                    "description": "问题的上下文说明（可选）",
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
            "required": ["question", "source_agent", "task_id"],
        }

    async def execute(
        self,
        question: str,
        source_agent: str,
        task_id: str,
        context: str | None = None,
        options: list[str] | None = None,
        timeout: int = 60,
        request_id: str | None = None,
        **kwargs: Any,
    ) -> str:
        """转发问题给用户，返回用户回复"""
        from topoclaw.agent.interaction.bus import AgentMessage, AgentMessageType

        forward_id = str(uuid.uuid4())[:8]
        future = asyncio.Future()
        self._pending_forwards[forward_id] = future

        # 构建转发消息（可以添加主Agent的解释）
        if context:
            message = f"{context}\n\n{source_agent}询问：{question}"
        else:
            message = f"{source_agent}询问：{question}"

        if options:
            message += f"\n选项: {', '.join(options)}"

        # 发送问题给用户
        msg = OutboundMessage(
            channel=self.channel,
            chat_id=self.chat_id,
            content=message,
            metadata={
                "_interactive": True,
                "_type": "forwarded_question",
                "_forward_id": forward_id,
                "_source_agent": source_agent,
                "_task_id": task_id,
                "_request_id": request_id,
                "_options": options or [],
            },
        )

        try:
            await self._send_callback(msg)
            logger.info(
                "Main agent forwarded question from [{}]: {}",
                source_agent,
                question[:50],
            )

            # 启动监听用户回复
            asyncio.create_task(
                self._listen_for_reply(forward_id, source_agent, task_id, request_id, timeout)
            )

            # 等待用户回复
            try:
                reply = await asyncio.wait_for(future, timeout=timeout)
                logger.info(
                    "Main agent received reply for [{}]: {}", source_agent, reply[:50]
                )

                # 将回复发送回GUI Agent
                await self.agent_bus.publish_agent_message(
                    AgentMessage(
                        type=AgentMessageType.USER_REPLY,
                        source_agent="main",
                        target_agent=source_agent,
                        task_id=task_id,
                        content=reply,
                        metadata={
                            "request_id": request_id,
                        },
                    )
                )

                return f"已转发问题并收到回复：{reply}"
            except asyncio.TimeoutError:
                error_msg = f"用户未在{timeout}秒内回复"
                logger.warning("Main agent forward timeout")
                return f"Error: {error_msg}"
        except Exception as e:
            logger.error("Error forwarding question: {}", e)
            return f"Error: 转发问题失败 - {str(e)}"
        finally:
            self._pending_forwards.pop(forward_id, None)

    async def _listen_for_reply(
        self,
        forward_id: str,
        source_agent: str,
        task_id: str,
        request_id: str | None,
        timeout: int,
    ) -> None:
        """监听用户回复（通过消息总线）"""
        # 注意：实际实现中需要通过消息路由器将用户回复路由到这里
        # 这里提供一个占位实现
        pass

    def handle_user_reply(self, forward_id: str, reply: str) -> bool:
        """处理用户回复（由消息路由器调用）"""
        future = self._pending_forwards.get(forward_id)
        if future and not future.done():
            future.set_result(reply)
            return True
        return False

    def set_context(self, channel: str, chat_id: str) -> None:
        """更新上下文"""
        self.channel = channel
        self.chat_id = chat_id
