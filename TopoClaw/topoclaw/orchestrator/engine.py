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

"""Orchestration engine for running DAG-based multi-agent workflows."""

from __future__ import annotations

import asyncio
from collections import defaultdict
from typing import Any

from loguru import logger

from topoclaw.orchestrator.base import DAG, DAGNode, OrchestrationResult
from topoclaw.orchestrator.registry import NodeRegistry


class OrchestrationEngine:
    """
    Executes DAG-based Orchestration definitions.

    Nodes with no dependencies run in parallel at the start.
    A node runs when all its dependencies have completed successfully.
    Results from dependency nodes are accumulated and passed to dependent nodes.
    """

    def __init__(self, registry: NodeRegistry):
        """
        Initialize the orchestration engine.

        Args:
            registry: NodeRegistry containing available agent nodes
        """
        self._registry = registry

    async def run(
        self,
        orchestration: DAG,
        task: str,
        context: dict[str, Any] | None = None,
    ) -> list[OrchestrationResult]:
        """
        Execute a DAG orchestration.

        Args:
            orchestration: The DAG definition to run
            task: The initial task/goal
            context: Optional context passed to all nodes

        Returns:
            List of OrchestrationResult, one per node execution (in execution order)
        """
        ctx = context or {}
        results: dict[str, OrchestrationResult] = {}
        completed: set[str] = set()
        pending: set[str] = set(orchestration.nodes.keys())

        logger.info(
            "Starting DAG orchestration '{}' with {} nodes",
            orchestration.name,
            len(orchestration.nodes),
        )

        # Build dependency graph for quick lookup
        dependents: dict[str, list[str]] = defaultdict(list)
        for node_id, node in orchestration.nodes.items():
            for dep in node.depends_on:
                dependents[dep].append(node_id)

        async def run_node(node_id: str, node: DAGNode) -> OrchestrationResult:
            """Run a single node and return its result."""
            logger.info("Executing node: {} (agent: {})", node_id, node.agent_id)
            logger.debug("NodeRegistry contents at execution time: {}", list(self._registry.list_nodes()))

            agent = self._registry.get(node.agent_id)
            if agent is None:
                logger.error("Agent '{}' not found in NodeRegistry for node '{}'. Available nodes: {}", node.agent_id, node_id, list(self._registry.list_nodes()))
                return OrchestrationResult(
                    node_name=node_id,
                    output="",
                    success=False,
                    error=f"Agent '{node.agent_id}' not found in registry",
                )

            # Build accumulated context from dependencies
            dep_results = {}
            for dep_id in node.depends_on:
                if dep_id in results:
                    dep_results[dep_id] = results[dep_id]

            accumulated = task
            for dep_id, dep_result in dep_results.items():
                accumulated += f"\n\n--- [{dep_id}] Output ---\n{dep_result.output}"

            # Build prompt
            if node.prompt_template:
                try:
                    # results dict for template access
                    prompt = node.prompt_template.format(
                        task=task,
                        context=ctx,
                        results=dep_results,
                        node_id=node_id,
                    )
                except (KeyError, ValueError):
                    prompt = accumulated
            else:
                prompt = accumulated

            logger.debug("Node {} executing with prompt (first 200 chars): {}", node_id, prompt[:200])
            try:
                result = await agent.execute(prompt, ctx)
                logger.info("Node {} executed successfully, output length: {}", node_id, len(result.output))
                return result
            except Exception as e:
                logger.exception("Node {} execution failed with exception: {}", node_id, e)
                return OrchestrationResult(
                    node_name=node_id,
                    output="",
                    success=False,
                    error=f"Execution failed: {e}",
                )

        # Track in-degree for topological scheduling
        in_degree: dict[str, int] = {
            node_id: len(node.depends_on) for node_id, node in orchestration.nodes.items()
        }

        # Queue of nodes ready to execute (no dependencies or all deps done)
        ready: list[str] = [
            node_id for node_id, degree in in_degree.items() if degree == 0
        ]

        while ready:
            if len(ready) == 1:
                # Single node - run synchronously
                node_id = ready.pop()
                node = orchestration.nodes[node_id]
                result = await run_node(node_id, node)
                results[node_id] = result
                completed.add(node_id)

                if not result.success and orchestration.stop_on_failure:
                    logger.error("Node {} failed (error: {}), stopping DAG execution", node_id, result.error)
                    break

                # Update dependents' in-degree
                for dep in dependents[node_id]:
                    in_degree[dep] -= 1
                    if in_degree[dep] == 0 and dep not in completed:
                        ready.append(dep)
            else:
                # Multiple nodes ready - run in parallel
                logger.info("Executing {} nodes in parallel: {}", len(ready), ready)
                tasks = [
                    run_node(node_id, orchestration.nodes[node_id])
                    for node_id in ready
                ]
                node_results = await asyncio.gather(*tasks, return_exceptions=True)

                just_completed = []
                for node_id, result in zip(ready, node_results):
                    if isinstance(result, Exception):
                        result = OrchestrationResult(
                            node_name=node_id,
                            output="",
                            success=False,
                            error=str(result),
                        )
                    results[node_id] = result
                    completed.add(node_id)
                    just_completed.append(node_id)

                    if not result.success and orchestration.stop_on_failure:
                        logger.error("Node {} failed (error: {}), stopping DAG execution", node_id, result.error)

                # Clear ready and update in-degree for dependents
                ready.clear()
                for node_id in just_completed:
                    for dep in dependents[node_id]:
                        in_degree[dep] -= 1
                        if in_degree[dep] == 0 and dep not in completed:
                            ready.append(dep)

        logger.info(
            "DAG orchestration '{}' finished. {} nodes executed, {} remaining",
            orchestration.name,
            len(results),
            len(orchestration.nodes) - len(completed),
        )

        # Return results in original definition order for determinism
        return [results.get(node_id, OrchestrationResult(
            node_name=node_id,
            output="",
            success=False,
            error="Node was not executed",
        )) for node_id in orchestration.nodes]

    async def run_with_result(
        self,
        orchestration: DAG,
        task: str,
        context: dict[str, Any] | None = None,
    ) -> tuple[list[OrchestrationResult], str]:
        """
        Execute a DAG orchestration and return results plus final output.

        The final output is taken from the node(s) with no dependents.

        Args:
            orchestration: The DAG definition to run
            task: The initial task/goal
            context: Optional context passed to all nodes

        Returns:
            Tuple of (all results, final output string from terminal nodes)
        """
        results = await self.run(orchestration, task, context)
        # Final output comes from nodes that have no dependents
        terminal_nodes = {
            node_id for node_id in orchestration.nodes
            if all(
                node_id not in orchestration.nodes[dep].depends_on
                for dep in orchestration.nodes
            )
        }
        final_outputs = [
            results[i].output
            for i, node_id in enumerate(orchestration.nodes)
            if node_id in terminal_nodes and results[i].success
        ]
        final_output = "\n\n".join(final_outputs) if final_outputs else ""
        return results, final_output
