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

"""Tool for GUI agents to request main agent to forward questions."""

import asyncio
import uuid
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool


class RequestForwardTool(Tool):
    """GUI Agent请求主Agent转发问题的工具"""

    def __init__(
        self,
        agent_bus,
        agent_type: str,
        task_id: str,
    ):
        """Initialize RequestForwardTool.

        Args:
            agent_bus: Agent communication bus
            agent_type: Type of agent (e.g., "mobile_gui", "desktop_gui")
            task_id: Current task ID
        """
        self.agent_bus = agent_bus
        self.agent_type = agent_type
        self.task_id = task_id
        self._pending_requests: dict[str, asyncio.Future] = {}

    @property
    def name(self) -> str:
        return "request_forward"

    @property
    def description(self) -> str:
        return (
            "请求主Agent代为询问用户。用于需要主Agent协调的场景。"
            "主Agent会将问题转发给用户，并将用户回复返回。"
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
            "required": ["question"],
        }

    async def execute(
        self,
        question: str,
        context: str | None = None,
        options: list[str] | None = None,
        timeout: int = 60,
        **kwargs: Any,
    ) -> str:
        """请求主Agent转发问题，返回用户回复"""
        from topoclaw.agent.interaction.bus import AgentMessage, AgentMessageType

        request_id = str(uuid.uuid4())[:8]
        future = asyncio.Future()
        self._pending_requests[request_id] = future

        # 发送转发请求给主Agent
        msg = AgentMessage(
            type=AgentMessageType.FORWARD_REQUEST,
            source_agent=self.agent_type,
            target_agent="main",
            task_id=self.task_id,
            content=question,
            metadata={
                "request_id": request_id,
                "context": context,
                "options": options or [],
                "timeout": timeout,
            },
        )

        try:
            await self.agent_bus.publish_agent_message(msg)
            logger.info(
                "GUI Agent [{}] requested forward: {}", self.agent_type, question[:50]
            )

            # 启动监听回复
            asyncio.create_task(self._listen_for_reply(request_id, timeout))

            # 等待主Agent返回用户回复
            try:
                reply = await asyncio.wait_for(future, timeout=timeout + 5)
                logger.info(
                    "GUI Agent [{}] received forwarded reply: {}",
                    self.agent_type,
                    reply[:50],
                )
                return reply
            except asyncio.TimeoutError:
                error_msg = f"主Agent未在{timeout}秒内返回用户回复"
                logger.warning("GUI Agent [{}] forward request timeout", self.agent_type)
                return f"Error: {error_msg}"
        except Exception as e:
            logger.error("Error requesting forward: {}", e)
            return f"Error: 请求转发失败 - {str(e)}"
        finally:
            self._pending_requests.pop(request_id, None)

    async def _listen_for_reply(self, request_id: str, timeout: int) -> None:
        """监听主Agent返回的用户回复"""
        from topoclaw.agent.interaction.bus import AgentMessageType

        while True:
            try:
                msg = await asyncio.wait_for(
                    self.agent_bus.consume_agent_message(), timeout=timeout
                )

                if (
                    msg.type == AgentMessageType.USER_REPLY
                    and msg.metadata.get("request_id") == request_id
                ):
                    future = self._pending_requests.get(request_id)
                    if future and not future.done():
                        future.set_result(msg.content)
                    break
            except asyncio.TimeoutError:
                future = self._pending_requests.get(request_id)
                if future and not future.done():
                    future.set_exception(asyncio.TimeoutError())
                break
            except Exception as e:
                logger.error("Error listening for reply: {}", e)
                break

    def handle_reply(self, request_id: str, reply: str) -> bool:
        """处理主Agent返回的回复（由消息处理器调用）"""
        future = self._pending_requests.get(request_id)
        if future and not future.done():
            future.set_result(reply)
            return True
        return False
