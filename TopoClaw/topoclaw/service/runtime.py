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

"""Runtime objects for unified service mode."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from topoclaw.agent.loop import AgentLoop
from topoclaw.agent.agent_registry import AgentRegistry
from topoclaw.bus.events import OutboundMessage
from topoclaw.bus.queue import MessageBus
from topoclaw.channels.manager import ChannelManager
from topoclaw.cron.service import CronService
from topoclaw.heartbeat.service import HeartbeatService
from topoclaw.session.manager import SessionManager


@dataclass
class BindingTarget:
    """Delivery target bound to an API thread."""

    channel: str
    chat_id: str
    updated_at: str


class ThreadBindingStore:
    """In-memory binding store for thread delivery targets."""

    def __init__(self) -> None:
        self._targets: dict[str, BindingTarget] = {}
        self._lock = asyncio.Lock()

    async def set(self, thread_id: str, channel: str, chat_id: str) -> None:
        async with self._lock:
            self._targets[thread_id] = BindingTarget(
                channel=channel,
                chat_id=chat_id,
                updated_at=datetime.now().isoformat(),
            )

    async def get(self, thread_id: str) -> BindingTarget | None:
        async with self._lock:
            return self._targets.get(thread_id)

    async def delete(self, thread_id: str) -> bool:
        async with self._lock:
            return self._targets.pop(thread_id, None) is not None


class EventHub:
    """Simple async pub/sub hub for API thread events."""

    def __init__(self) -> None:
        self._subs: dict[str, set[asyncio.Queue[dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()

    async def publish(self, thread_id: str, event: dict[str, Any]) -> None:
        async with self._lock:
            targets = list(self._subs.get(thread_id, set()))
        for q in targets:
            await q.put(event)

    async def subscribe(self, thread_id: str) -> asyncio.Queue[dict[str, Any]]:
        q: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        async with self._lock:
            self._subs.setdefault(thread_id, set()).add(q)
        return q

    async def unsubscribe(self, thread_id: str, queue: asyncio.Queue[dict[str, Any]]) -> None:
        async with self._lock:
            if thread_id in self._subs:
                self._subs[thread_id].discard(queue)
                if not self._subs[thread_id]:
                    del self._subs[thread_id]


@dataclass
class ServiceRuntime:
    """Container for service-mode runtime objects."""

    bus: MessageBus
    agent: AgentLoop
    channels: ChannelManager
    cron: CronService
    heartbeat: HeartbeatService
    sessions: SessionManager
    bindings: ThreadBindingStore
    events: EventHub
    agent_registry: AgentRegistry | None = None
    node_registry: Any = None
    orchestration_registry: Any = None
    token_usage_service: Any = None

    async def deliver(self, thread_id: str, content: str) -> None:
        """Deliver message to bound external channel or API event stream."""
        target = await self.bindings.get(thread_id)
        if target and target.channel != "api":
            await self.bus.publish_outbound(OutboundMessage(
                channel=target.channel,
                chat_id=target.chat_id,
                content=content,
            ))
            return

        await self.events.publish(thread_id, {
            "delta": content,
            "source": "push",
            "thread_id": thread_id,
        })
