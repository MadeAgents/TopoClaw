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

"""Tool for creating agent team orchestrations (DAG definitions)."""

import hashlib
from typing import Any

from loguru import logger
import json

from topoclaw.agent.agent_registry import AgentRegistry
from topoclaw.agent.agent_loop_factory import build_service_agent_loop
from topoclaw.agent.session_keys import DEFAULT_AGENT_ID, normalize_agent_id
from topoclaw.agent.tools.base import Tool
from topoclaw.bus.queue import MessageBus
from topoclaw.config.schema import Config
from topoclaw.cron.service import CronService
from topoclaw.orchestrator import OrchestrationRegistry, get_orchestration_registry, get_registry
from topoclaw.orchestrator.base import DAG, DAGNode, NodeAdapter
from topoclaw.providers.base import LLMProvider
from topoclaw.session.manager import SessionManager
from topoclaw.utils.helpers import ensure_dir, safe_filename, sync_workspace_templates
from topoclaw.config.loader import save_config
from topoclaw.config.paths import get_runtime_subdir
from topoclaw.config.schema import NamedAgentEntry
from topoclaw.agent.skills import write_skills_filter


def _check_dag_cycle(node_ids: list[str], depends_on: dict[str, list[str]]) -> str | None:
    """
    Check if the dependency graph contains a cycle using Kahn's algorithm.

    Returns None if no cycle, otherwise returns error message describing the cycle.
    """
    in_degree = {nid: 0 for nid in node_ids}
    for nid, deps in depends_on.items():
        for dep in deps:
            if dep in in_degree:
                in_degree[nid] += 1

    queue = [nid for nid, deg in in_degree.items() if deg == 0]
    visited = 0

    while queue:
        node = queue.pop(0)
        visited += 1
        for nid, deps in depends_on.items():
            if node in deps:
                in_degree[nid] -= 1
                if in_degree[nid] == 0:
                    queue.append(nid)

    if visited != len(node_ids):
        cyclic_nodes = [nid for nid, deg in in_degree.items() if deg > 0]
        return f"Cycle detected in dependency graph: {cyclic_nodes}"

    return None


class OrchestrateAgentsTool(Tool):
    """
    Create an orchestration definition for a team of agents.

    Input:
        orchestration_name: Unique name for this orchestration
        agents: List of agent definitions, each with:
            - name: Agent identifier (used in depends_on)
            - role: Role description / system prompt for the agent
            - depends_on: List of agent names this agent depends on (default: [])

    Output:
        JSON string with orchestration_id, agents, and nodes for client to extract
    """

    def __init__(
        self,
        agent_registry: AgentRegistry,
        config: Config | None = None,
        bus: MessageBus | None = None,
        provider: LLMProvider | None = None,
        cron_service: CronService | None = None,
    ):
        self._agent_registry = agent_registry
        self._orchestration_registry = get_orchestration_registry()
        self._config = config
        self._bus = bus
        self._provider = provider
        self._cron_service = cron_service

    @property
    def name(self) -> str:
        return "orchestrate_agents"

    @property
    def description(self) -> str:
        return (
            "Create an orchestration definition for a team of agents. "
            "Input: orchestration_name (unique id), agents (list of name/role/depends_on). "
            "Example: orchestrate_agents(orchestration_name='research', "
            "agents=[{'name': 'planner', 'role': '制定研究计划', 'depends_on': []}, "
            "{'name': 'searcher', 'role': '搜索相关信息', 'depends_on': ['planner']}])"
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "orchestration_name": {
                    "type": "string",
                    "description": "Unique name for this orchestration",
                },
                "agents": {
                    "type": "array",
                    "description": "List of agent definitions",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "Agent identifier (used in depends_on)"},
                            "role": {"type": "string", "description": "Role description / system prompt"},
                            "depends_on": {
                                "type": "array",
                                "description": "List of agent names this agent depends on",
                                "items": {"type": "string"},
                                "default": [],
                            },
                        },
                        "required": ["name", "role"],
                    },
                },
            },
            "required": ["orchestration_name", "agents"],
        }

    async def execute(
        self,
        orchestration_name: str,
        agents: list[dict[str, Any]],
        **kwargs: Any,
    ) -> str:
        """Execute the orchestrate agents tool."""

        if not orchestration_name:
            return "Error: orchestration_name is required"

        if not agents:
            return "Error: at least one agent is required"

        agent_names = set()
        agent_map: dict[str, dict[str, Any]] = {}
        depends_on: dict[str, list[str]] = {}

        for agent_def in agents:
            name = str(agent_def.get("name") or "").strip()
            if not name:
                return "Error: agent name is required"
            if name in agent_names:
                return f"Error: duplicate agent name '{name}'"

            role = str(agent_def.get("role") or "").strip()
            if not role:
                return f"Error: agent '{name}' requires a role"

            deps = list(agent_def.get("depends_on") or [])
            agent_names.add(name)
            agent_map[name] = agent_def
            depends_on[name] = deps

        for name, deps in depends_on.items():
            for dep in deps:
                if dep not in agent_names:
                    return f"Error: agent '{name}' depends on unknown agent '{dep}'"

        cycle_error = _check_dag_cycle(list(agent_names), depends_on)
        if cycle_error:
            return f"Error: {cycle_error}"

        # Check if agents exist and create them if they don't
        created_agents = []
        if self._config and self._bus and self._provider and self._cron_service:
            for name in agent_names:
                # Check if agent already exists in registry
                existing_loop = None
                for loop_id in self._agent_registry.loops:
                    if normalize_agent_id(loop_id) == normalize_agent_id(name):
                        existing_loop = self._agent_registry.loops[loop_id]
                        break

                if existing_loop is None:
                    # Agent doesn't exist, create it
                    role = str(agent_map[name].get("role") or "").strip()
                    try:
                        aid = normalize_agent_id(name)
                        digest = hashlib.sha256(aid.encode("utf-8")).hexdigest()[:16]
                        safe = (safe_filename(aid) or "agent")[:60]
                        leaf = f"{safe}-{digest}"
                        wpath = ensure_dir(get_runtime_subdir("agents") / leaf)
                        sync_workspace_templates(wpath, silent=True)

                        write_skills_filter(wpath, include=None, exclude=None)

                        soul_md = wpath / "SOUL.md"
                        if role:
                            try:
                                existing = soul_md.read_text(encoding="utf-8") if soul_md.exists() else ""
                                soul_md.write_text(
                                    f"# Agent identity\n\n{role}\n\n---\n\n{existing}",
                                    encoding="utf-8",
                                )
                            except OSError as e:
                                logger.warning("orchestrate_agents: could not update SOUL.md: {}", e)

                        loop = build_service_agent_loop(
                            config=self._config,
                            bus=self._bus,
                            provider=self._provider,
                            workspace=wpath,
                            cron_service=self._cron_service,
                            session_manager=SessionManager(wpath),
                        )

                        ok, err = await self._agent_registry.register_created_agent(aid, loop, wpath)
                        if not ok:
                            return f"Error: failed to create agent '{name}': {err or 'registration failed'}"

                        # Persist the new agent to config so it survives restarts
                        try:
                            entry = NamedAgentEntry(
                                id=aid,
                                workspace=str(wpath),
                                skills_include=[],
                                skills_exclude=[],
                            )
                            updated = False
                            for i, item in enumerate(self._config.agents.named_agents):
                                if (item.id or "").strip() == aid:
                                    self._config.agents.named_agents[i] = entry
                                    updated = True
                                    break
                            if not updated:
                                self._config.agents.named_agents.append(entry)
                            save_config(self._config)
                            logger.info("orchestrate_agents: persisted agent '{}' to config", name)
                        except Exception as e:
                            logger.warning("orchestrate_agents: could not persist agent to config: {}", e)

                        created_agents.append(name)

                        # Also register in NodeRegistry so orchestration engine can find it
                        try:
                            node_registry = get_registry()
                            adapter = NodeAdapter(agent_loop=loop, name=aid, description=f"Agent: {aid}")
                            node_registry.register(aid, adapter)
                            logger.info("orchestrate_agents: registered agent '{}' (key='{}') in NodeRegistry. Total nodes: {}", name, aid, len(node_registry.list_nodes()))
                        except Exception as e:
                            logger.warning("orchestrate_agents: failed to register agent in NodeRegistry: {}", e)

                        logger.info("orchestrate_agents: created agent '{}' for orchestration", name)
                    except Exception as e:
                        logger.exception("orchestrate_agents: failed to create agent '{}': {}", name, e)
                        return f"Error: failed to create agent '{name}': {e}"
        else:
            # Check if all agents exist without trying to create them
            for name in agent_names:
                existing_loop = None
                for loop_id in self._agent_registry.loops:
                    if normalize_agent_id(loop_id) == normalize_agent_id(name):
                        existing_loop = self._agent_registry.loops[loop_id]
                        break
                if existing_loop is None:
                    return f"Error: agent '{name}' does not exist in registry and cannot be created (missing dependencies)"

        nodes: dict[str, DAGNode] = {}
        for name, agent_def in agent_map.items():
            deps = depends_on[name]
            role = agent_def.get("role", "")

            prompt_parts = [f"你是: {role}"]
            if deps:
                prompt_parts.append("\n\n--- 参考之前的agent输出 ---")
                for dep in deps:
                    prompt_parts.append(f"{{results['{dep}'].output}}")

            nodes[name] = DAGNode(
                node_id=name,
                agent_id=name,
                prompt_template="\n".join(prompt_parts),
                depends_on=deps,
            )

        dag = DAG(
            name=orchestration_name,
            description=f"Agent team orchestration",
            stop_on_failure=True,
            nodes=nodes,
        )

        existing = self._orchestration_registry.get(orchestration_name)
        if existing:
            self._orchestration_registry.unregister(orchestration_name)
            logger.info("Replaced existing orchestration '{}'", orchestration_name)

        self._orchestration_registry.register(dag)

        try:
            dag.save()
            logger.info("Saved orchestration '{}' to file", orchestration_name)
        except Exception as e:
            logger.warning("Failed to save DAG to file: {}", e)

        agent_list = []
        for name in agent_names:
            agent_def = agent_map[name]
            agent_list.append({
                "name": name,
                "role": agent_def.get("role", ""),
                "depends_on": depends_on.get(name, []),
            })

        # Return structured info with two parts:
        # 1. text for model
        # 2. payload for on_progress (extracted by loop.py)
        dep_tree = self._build_dep_tree(list(agent_names), depends_on)
        created_info = f"\n- 新创建的 Agents: {created_agents}" if created_agents else ""
        text = (
            f"编排已创建:\n"
            f"- orchestration_id: {orchestration_name}\n"
            f"- 节点数量: {len(nodes)}\n"
            f"- 节点列表: {list(nodes.keys())}\n"
            f"- 依赖关系:\n{dep_tree}{created_info}"
        )
        payload = {
            "type": "orchestration_created",
            "orchestration_id": orchestration_name,
            "agents": agent_list,
            "nodes": list(nodes.keys()),
            "created_agents": created_agents,
        }

        # Return JSON with both parts - loop.py will extract payload and use text for model
        return json.dumps({
            "payload": payload,
            "text": text,
        }, ensure_ascii=False)

    def _build_dep_tree(self, node_ids: list[str], depends_on: dict[str, list[str]]) -> str:
        """Build a tree-like string showing dependencies."""
        lines = []
        for name in node_ids:
            deps = depends_on.get(name, [])
            if deps:
                lines.append(f"  - {name} depends on: {deps}")
            else:
                lines.append(f"  - {name} (无依赖)")
        return "\n".join(lines) if lines else "  (无依赖)"