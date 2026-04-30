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


from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from loguru import logger

from topoclaw.agent.session_keys import DEFAULT_AGENT_ID, normalize_agent_id

if TYPE_CHECKING:
    from topoclaw.agent.loop import AgentLoop


@dataclass
class AgentRegistry:
    """Maps ``agent_id`` → ``AgentLoop`` and workspace root.

    Routing is strict/auditable:
    - Only pre-registered agents (default, config named_agents, or runtime create_agent) are used.
    - Unknown/empty ``agent_id`` always routes to the default agent.
    """

    default_id: str
    loops: dict[str, AgentLoop]
    workspaces: dict[str, Path]
    _registry_lock: asyncio.Lock = field(default_factory=asyncio.Lock, repr=False, compare=False)

    @classmethod
    def single(cls, agent: AgentLoop, workspace: Path, agent_id: str = DEFAULT_AGENT_ID) -> AgentRegistry:
        """Single primary agent (backward compatible)."""
        return cls(
            default_id=agent_id,
            loops={agent_id: agent},
            workspaces={agent_id: workspace},
        )

    async def materialize(self, agent_id: str | None) -> tuple[AgentLoop, Path, str]:
        """Return ``(AgentLoop, workspace, resolved_agent_id)``; unknown ids fallback to default."""
        aid = normalize_agent_id(agent_id)
        loop = self.loops.get(aid)
        if loop is not None:
            return loop, self.workspaces[aid], aid
        d = self.default_id
        return self.loops[d], self.workspaces[d], d

    def resolve_loop(self, agent_id: str | None) -> AgentLoop:
        """Sync resolver — only returns already-registered loops; unknown ids → default."""
        aid = normalize_agent_id(agent_id)
        loop = self.loops.get(aid)
        if loop is not None:
            return loop
        return self.loops[self.default_id]

    def resolve_workspace(self, agent_id: str | None) -> Path:
        """Sync resolver — only returns already-registered workspaces; unknown ids → default."""
        aid = normalize_agent_id(agent_id)
        path = self.workspaces.get(aid)
        if path is not None:
            return path
        return self.workspaces[self.default_id]

    async def register_created_agent(
        self,
        agent_id: str,
        loop: AgentLoop,
        workspace: Path,
    ) -> tuple[bool, str]:
        """
        Register a new agent at runtime (e.g. WebSocket ``create_agent``).

        *agent_id* must be non-empty and not canonical ``default``.
        """
        raw = (agent_id or "").strip()
        if not raw:
            return False, "agent_id is required"
        aid = normalize_agent_id(raw)
        if aid == DEFAULT_AGENT_ID:
            return False, "agent_id cannot be default or empty"

        async with self._registry_lock:
            if aid in self.loops:
                return False, f"agent already exists: {aid}"
            self.loops[aid] = loop
            self.workspaces[aid] = workspace
        logger.info("Runtime-registered agent id={} workspace={}", aid, workspace)
        return True, ""

    async def unregister_created_agent(self, agent_id: str) -> tuple["AgentLoop" | None, Path | None]:
        """Remove a non-default agent from registry and return ``(loop, workspace)``."""
        aid = normalize_agent_id(agent_id)
        if aid == self.default_id:
            return None, None
        async with self._registry_lock:
            loop = self.loops.pop(aid, None)
            workspace = self.workspaces.pop(aid, None)
        return loop, workspace


# Module-level singleton for global access
_default_agent_registry: AgentRegistry | None = None


def get_agent_registry() -> AgentRegistry:
    """Get the default shared AgentRegistry instance."""
    global _default_agent_registry
    if _default_agent_registry is None:
        raise RuntimeError("AgentRegistry not initialized. Call set_agent_registry() first.")
    return _default_agent_registry


def set_agent_registry(registry: AgentRegistry) -> None:
    """Set the default shared AgentRegistry instance."""
    global _default_agent_registry
    _default_agent_registry = registry
