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

"""Build ``AgentLoop`` instances for service mode (primary, named, dynamic)."""

from __future__ import annotations

from pathlib import Path

from topoclaw.agent.loop import AgentLoop
from topoclaw.agent.skills import resolve_skill_filters_for_loop
from topoclaw.bus.queue import MessageBus
from topoclaw.config.schema import Config
from topoclaw.cron.service import CronService
from topoclaw.providers.base import LLMProvider
from topoclaw.config.loader import load_toolcall_guard_path_permissions
from topoclaw.secure.toolcall_guard import (
    TOOLCALL_GUARD_AGENT_RUNTIME_FILENAME,
    ToolcallGuard,
    build_agent_toolcall_guard,
)
from topoclaw.session.manager import SessionManager


def toolcall_guard_from_config(config: Config, workspace: Path) -> ToolcallGuard | None:
    """Build ToolcallGuard for the agent workspace (see ``tools.use_toolcall_guard`` in config).

    ``workspace`` must match the loop's working directory (per-agent workspace for named agents).
    """
    if not config.tools.use_toolcall_guard:
        return None
    perms = load_toolcall_guard_path_permissions()
    guard = build_agent_toolcall_guard(
        workspace,
        extra_allowed_dirs=config.tools.toolcall_guard_extra_allowed_dirs,
        path_permissions=perms or None,
    )
    guard.attach_runtime_state_file(workspace / TOOLCALL_GUARD_AGENT_RUNTIME_FILENAME)
    return guard


def build_service_agent_loop(
    *,
    config: Config,
    bus: MessageBus,
    provider: LLMProvider,
    workspace: Path,
    cron_service: CronService,
    session_manager: SessionManager | None = None,
    skill_exclude: list[str] | None = None,
    skill_include: list[str] | None = None,
) -> AgentLoop:
    """Shared constructor for all service-mode text agents."""
    sm = session_manager if session_manager is not None else SessionManager(workspace)
    eff_include, eff_exclude = resolve_skill_filters_for_loop(
        workspace,
        fallback_include=skill_include,
        fallback_exclude=skill_exclude,
    )
    guard = toolcall_guard_from_config(config, workspace)
    return AgentLoop(
        bus=bus,
        provider=provider,
        workspace=workspace,
        model=config.agents.defaults.model,
        temperature=config.agents.defaults.temperature,
        max_tokens=config.agents.defaults.max_tokens,
        max_iterations=config.agents.defaults.max_tool_iterations,
        reasoning_effort=config.agents.defaults.reasoning_effort,
        provider_kwargs=config.agents.defaults.provider_kwargs,
        context_window_tokens=config.agents.defaults.context_window_tokens,
        brave_api_key=config.tools.web.search.api_key or None,
        web_proxy=config.tools.web.proxy or None,
        exec_config=config.tools.exec,
        cron_service=cron_service,
        restrict_to_workspace=config.tools.restrict_to_workspace,
        session_manager=sm,
        mcp_servers=config.tools.mcp_servers,
        channels_config=config.channels,
        skill_exclude=eff_exclude,
        skill_include=eff_include,
        app_config=config,
        toolcall_guard=guard,
    )
