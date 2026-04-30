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

"""Service for executing orchestrations."""

from __future__ import annotations

from typing import Any

from loguru import logger

from topoclaw.agent.agent_registry import AgentRegistry
from topoclaw.config.schema import Config
from topoclaw.orchestrator import (
    NodeRegistry,
    OrchestrationRegistry,
    get_orchestration_registry,
)
from topoclaw.orchestrator.base import DAG, NodeAdapter
from topoclaw.orchestrator.engine import OrchestrationEngine


class OrchestrationService:
    """
    Service for managing and executing DAG orchestrations.

    Coordinates between the NodeRegistry (agent nodes) and OrchestrationRegistry (DAG definitions),
    then executes orchestrations using the OrchestrationEngine.
    """

    def __init__(
        self,
        node_registry: NodeRegistry,
        orchestration_registry: OrchestrationRegistry,
        agent_registry: AgentRegistry,
        config: Config | None = None,
    ):
        self._node_registry = node_registry
        self._orchestration_registry = orchestration_registry
        self._agent_registry = agent_registry
        self._config = config
        self._engine = OrchestrationEngine(node_registry)

    async def execute(
        self,
        orchestration_name: str,
        task: str,
        context: dict[str, Any] | None = None,
    ) -> tuple[bool, str, list[Any]]:
        """
        Execute a named orchestration with a task.

        Args:
            orchestration_name: Name of the orchestration to execute
            task: The input task/goal
            context: Optional context passed to all nodes

        Returns:
            Tuple of (success, final_output, list_of_results)
        """
        dag = self._orchestration_registry.get(orchestration_name)
        if dag is None:
            logger.error("Orchestration '{}' not found", orchestration_name)
            return False, f"Orchestration '{orchestration_name}' not found", []

        logger.info("Executing orchestration '{}' with task: {}", orchestration_name, task)

        try:
            results = await self._engine.run(dag, task, context)
            success_count = sum(1 for r in results if r.success)
            final_outputs = [r.output for r in results if r.success]
            final_output = "\n\n".join(final_outputs) if final_outputs else ""

            logger.info(
                "Orchestration '{}' completed: {}/{} nodes succeeded",
                orchestration_name,
                success_count,
                len(results),
            )

            return True, final_output, results

        except Exception as e:
            logger.exception("Orchestration '{}' failed: {}", orchestration_name, e)
            return False, str(e), []

    async def execute_dag(
        self,
        dag: DAG,
        task: str,
        context: dict[str, Any] | None = None,
    ) -> tuple[bool, str, list[Any]]:
        """
        Execute a DAG directly without registering it.

        Args:
            dag: The DAG to execute
            task: The input task/goal
            context: Optional context passed to all nodes

        Returns:
            Tuple of (success, final_output, list_of_results)
        """
        logger.info("Executing DAG '{}' with task: {}", dag.name, task)

        try:
            results = await self._engine.run(dag, task, context)
            success_count = sum(1 for r in results if r.success)
            final_outputs = [r.output for r in results if r.success]
            final_output = "\n\n".join(final_outputs) if final_outputs else ""

            return True, final_output, results

        except Exception as e:
            logger.exception("DAG '{}' failed: {}", dag.name, e)
            return False, str(e), []

    def register_agent_as_node(
        self,
        agent_id: str,
        node_name: str,
        description: str = "",
        default_session_key: str | None = None,
    ) -> bool:
        """
        Register an agent from the AgentRegistry as an orchestration node.

        Args:
            agent_id: The agent_id in AgentRegistry
            node_name: The name to register the node under
            description: Description for the node
            default_session_key: Optional session key override

        Returns:
            True if registered successfully, False if agent not found
        """
        loop = self._agent_registry.resolve_loop(agent_id)
        if loop is None:
            logger.warning("Cannot register agent '{}' as node: not found in AgentRegistry", agent_id)
            return False

        adapter = NodeAdapter(
            agent_loop=loop,
            name=node_name,
            description=description or f"Agent node: {agent_id}",
            default_session_key=default_session_key or f"orchestrator:{node_name}",
        )
        self._node_registry.register(node_name, adapter)
        logger.info("Registered agent '{}' as orchestration node '{}'", agent_id, node_name)
        return True

    def list_orchestrations(self) -> list[str]:
        """List all registered orchestration names."""
        return self._orchestration_registry.list_orchestrations()

    def list_nodes(self) -> list[str]:
        """List all registered node names."""
        return self._node_registry.list_nodes()

    def get_orchestration(self, name: str) -> DAG | None:
        """Get a registered orchestration by name."""
        return self._orchestration_registry.get(name)

    async def delete_orchestration(self, name: str) -> tuple[bool, str]:
        """
        Delete an orchestration.

        Args:
            name: Orchestration name to delete

        Returns:
            Tuple of (success, error_message)
        """
        dag = self._orchestration_registry.get(name)
        if dag is None:
            return False, f"Orchestration '{name}' not found"

        self._orchestration_registry.unregister(name)
        logger.info("Deleted orchestration '{}'", name)
        return True, ""

    async def handle_ws_message(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        """
        Handle a WebSocket message of type 'task' or 'list_orchestrations'.

        task message format:
            {
                "type": "task",
                "orchestration_id": "stock_analysis",  # name of the DAG
                "task": "分析贵州茅台的投资价值",
                "context": {...}  # optional
            }

        list_orchestrations message format:
            {
                "type": "list_orchestrations"
            }

        Returns:
            Response dict, or None if message type not handled.
        """
        msg_type = str(msg.get("type") or "")

        if msg_type == "list_orchestrations":
            orchs = self._orchestration_registry.list_orchestrations()
            result = []
            for name in orchs:
                dag = self._orchestration_registry.get(name)
                if dag:
                    node_ids = list(dag.nodes.keys())
                    result.append({
                        "name": dag.name,
                        "description": dag.description,
                        "nodes": node_ids,
                    })
            return {
                "type": "list_orchestrations_result",
                "orchestrations": result,
            }

        if msg_type == "delete_orchestration":
            name = str(msg.get("orchestration_id") or msg.get("name") or "").strip()
            if not name:
                return {
                    "type": "delete_orchestration_result",
                    "ok": False,
                    "error": "orchestration_id is required",
                }
            ok, err = await self.delete_orchestration(name)
            if ok:
                return {
                    "type": "delete_orchestration_result",
                    "ok": True,
                    "orchestration_id": name,
                }
            return {
                "type": "delete_orchestration_result",
                "ok": False,
                "error": err,
            }

        if msg_type == "task":
            orchestration_id = str(msg.get("orchestration_id") or "").strip()
            if not orchestration_id:
                return {
                    "type": "task_result",
                    "ok": False,
                    "error": "orchestration_id is required",
                }

            task = str(msg.get("task") or "").strip()
            if not task:
                return {
                    "type": "task_result",
                    "ok": False,
                    "error": "task is required",
                }

            context = msg.get("context")

            dag = self._orchestration_registry.get(orchestration_id)
            if dag is None:
                logger.error("Orchestration '{}' not found", orchestration_id)
                return {
                    "type": "task_result",
                    "ok": False,
                    "error": f"Orchestration '{orchestration_id}' not found",
                }

            logger.info("Executing orchestration '{}' with task: {}. DAG nodes: {}", orchestration_id, task, list(dag.nodes.keys()))
            logger.info("Available node registry nodes: {}", self._node_registry.list_nodes())

            try:
                results = await self._engine.run(dag, task, context)
                success_count = sum(1 for r in results if r.success)
                final_outputs = [r.output for r in results if r.success]
                final_output = "\n\n".join(final_outputs) if final_outputs else ""

                logger.info(
                    "Orchestration '{}' completed: {}/{} nodes succeeded",
                    orchestration_id,
                    success_count,
                    len(results),
                )

                return {
                    "type": "task_result",
                    "ok": True,
                    "orchestration_id": orchestration_id,
                    "final_output": final_output,
                    "results": [
                        {
                            "node_name": r.node_name,
                            "output": r.output,
                            "success": r.success,
                            "error": r.error,
                            "duration_ms": r.duration_ms,
                        }
                        for r in results
                    ],
                }

            except Exception as e:
                logger.exception("Orchestration '{}' failed: {}", orchestration_id, e)
                return {
                    "type": "task_result",
                    "ok": False,
                    "error": str(e),
                }

        return None