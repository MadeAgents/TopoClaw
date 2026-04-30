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

"""WebSocket ``create_agent`` — runtime workspace + ``skills_filter.json`` + ``AgentLoop`` registration."""

from __future__ import annotations

import hashlib
import shutil
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.agent.session_keys import DEFAULT_AGENT_ID, normalize_agent_id
from topoclaw.agent.skills import write_skills_filter
from topoclaw.bus.queue import MessageBus
from topoclaw.config.loader import save_config
from topoclaw.config.paths import get_runtime_subdir
from topoclaw.config.schema import Config, NamedAgentEntry
from topoclaw.cron.service import CronService
from topoclaw.providers.base import LLMProvider
from topoclaw.agent.agent_loop_factory import build_service_agent_loop
from topoclaw.agent.agent_registry import AgentRegistry, get_agent_registry
from topoclaw.session.manager import SessionManager
from topoclaw.utils.helpers import ensure_dir, safe_filename, sync_workspace_templates

MAX_AGENT_ID_LEN = 80


def _coerce_str_list(msg: dict[str, Any], *keys: str) -> list[str]:
    for k in keys:
        v = msg.get(k)
        if v is None:
            continue
        if isinstance(v, list):
            return [str(x).strip() for x in v if str(x).strip()]
        if isinstance(v, str) and v.strip():
            return [s.strip() for s in v.split(",") if s.strip()]
    return []


def _extract_prompt(msg: dict[str, Any]) -> str:
    for k in ("default_prompt", "system_prompt", "prompt"):
        v = msg.get(k)
        if v is None:
            continue
        s = str(v).strip()
        if s:
            return s
    return ""


def _upsert_named_agent_in_config(
    *,
    config: Config,
    agent_id: str,
    workspace: Path,
    skills_include: list[str],
    skills_exclude: list[str],
) -> None:
    entry = NamedAgentEntry(
        id=agent_id,
        workspace=str(workspace),
        skills_include=skills_include,
        skills_exclude=skills_exclude,
    )
    updated = False
    for i, item in enumerate(config.agents.named_agents):
        if (item.id or "").strip() == agent_id:
            config.agents.named_agents[i] = entry
            updated = True
            break
    if not updated:
        config.agents.named_agents.append(entry)
    save_config(config)


def _remove_named_agent_from_config(*, config: Config, agent_id: str) -> bool:
    before = len(config.agents.named_agents)
    config.agents.named_agents = [x for x in config.agents.named_agents if (x.id or "").strip() != agent_id]
    removed = len(config.agents.named_agents) != before
    save_config(config)
    return removed


def _remove_runtime_workspace(workspace: Path) -> bool:
    """Delete runtime-created workspace only under <data>/agents/*."""
    try:
        root = get_runtime_subdir("agents").resolve()
        target = workspace.resolve()
        if not target.is_relative_to(root):
            return False
        shutil.rmtree(target)
        return True
    except Exception:
        return False


async def execute_create_agent(
    *,
    msg: dict[str, Any],
    config: Config,
    registry: AgentRegistry,
    bus: MessageBus,
    cron_service: CronService,
    provider: LLMProvider,
) -> dict[str, Any]:
    """
    Build workspace (runtime ``agents/``), write ``skills_filter.json``, optional ``SOUL.md`` prefix,
    construct ``AgentLoop``, register on *registry*.
    """
    base: dict[str, Any] = {"type": "agent_created"}

    raw_id = str(msg.get("agent_id") or "").strip()
    if not raw_id or len(raw_id) > MAX_AGENT_ID_LEN:
        return {**base, "ok": False, "error": "invalid or missing agent_id"}
    if any(c in raw_id for c in ("/", "\\", "\x00")):
        return {**base, "ok": False, "error": "invalid agent_id characters"}

    aid = normalize_agent_id(raw_id)
    if aid == DEFAULT_AGENT_ID:
        return {**base, "ok": False, "error": "agent_id cannot be default"}

    inc = _coerce_str_list(msg, "skills_include", "skill_include")
    exc = _coerce_str_list(msg, "skills_exclude", "skill_exclude")
    prompt_str = _extract_prompt(msg)

    digest = hashlib.sha256(aid.encode("utf-8")).hexdigest()[:16]
    safe = (safe_filename(aid) or "agent")[:60]
    leaf = f"{safe}-{digest}"
    wpath = ensure_dir(get_runtime_subdir("agents") / leaf)
    sync_workspace_templates(wpath, silent=True)

    write_skills_filter(wpath, include=inc or None, exclude=exc or None)

    soul_md = wpath / "SOUL.md"
    if prompt_str:
        try:
            existing = soul_md.read_text(encoding="utf-8") if soul_md.exists() else ""
            soul_md.write_text(
                f"# Agent identity\n\n{prompt_str}\n\n---\n\n{existing}",
                encoding="utf-8",
            )
        except OSError as e:
            logger.warning("create_agent: could not update SOUL.md: {}", e)

    loop = build_service_agent_loop(
        config=config,
        bus=bus,
        provider=provider,
        workspace=wpath,
        cron_service=cron_service,
        session_manager=SessionManager(wpath),
    )

    ok, err = await registry.register_created_agent(aid, loop, wpath)
    if not ok:
        return {**base, "ok": False, "error": err or "register failed"}

    try:
        _upsert_named_agent_in_config(
            config=config,
            agent_id=aid,
            workspace=wpath,
            skills_include=inc,
            skills_exclude=exc,
        )
    except Exception as e:
        logger.exception("create_agent: failed to persist agent in config: {}", e)
        await registry.unregister_created_agent(aid)
        return {**base, "ok": False, "error": f"failed to persist config: {e}"}

    return {
        **base,
        "ok": True,
        "agent_id": aid,
        "workspace": str(wpath.resolve()),
        "skills_include": inc,
        "skills_exclude": exc,
    }


async def execute_delete_agent(
    *,
    msg: dict[str, Any],
    config: Config,
    registry: AgentRegistry,
) -> dict[str, Any]:
    """Delete an existing non-default agent: registry + config + runtime workspace."""
    base: dict[str, Any] = {"type": "agent_deleted"}

    raw_id = str(msg.get("agent_id") or "").strip()
    if not raw_id or len(raw_id) > MAX_AGENT_ID_LEN:
        return {**base, "ok": False, "error": "invalid or missing agent_id"}
    if any(c in raw_id for c in ("/", "\\", "\x00")):
        return {**base, "ok": False, "error": "invalid agent_id characters"}

    aid = normalize_agent_id(raw_id)
    if aid == DEFAULT_AGENT_ID:
        return {**base, "ok": False, "error": "agent_id cannot be default"}

    loop, workspace = await registry.unregister_created_agent(aid)
    if loop is None or workspace is None:
        return {**base, "ok": False, "error": "agent not found"}

    try:
        _remove_named_agent_from_config(config=config, agent_id=aid)
    except Exception as e:
        logger.exception("delete_agent: failed to persist config: {}", e)
        ok, _err = await registry.register_created_agent(aid, loop, workspace)
        if not ok:
            logger.error("delete_agent: failed to rollback registry for {}", aid)
        return {**base, "ok": False, "error": f"failed to persist config: {e}"}

    warning: str | None = None
    try:
        await loop.close_mcp()
        loop.stop()
    except Exception as e:
        logger.warning("delete_agent: failed stopping agent loop {}: {}", aid, e)
        warning = f"agent loop cleanup failed: {e}"

    workspace_removed = _remove_runtime_workspace(workspace)
    payload: dict[str, Any] = {
        **base,
        "ok": True,
        "agent_id": aid,
        "workspace": str(workspace),
        "workspace_removed": workspace_removed,
    }
    if warning:
        payload["warning"] = warning
    return payload
