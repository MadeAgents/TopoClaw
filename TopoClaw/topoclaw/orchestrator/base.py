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

"""Base types for orchestrator."""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Protocol

if TYPE_CHECKING:
    from topoclaw.agent.loop import AgentLoop


@dataclass
class NodeMetadata:
    """Metadata for an orchestration node."""

    name: str
    description: str = ""


@dataclass
class OrchestrationResult:
    """Result from executing an orchestration node."""

    node_name: str
    output: str
    success: bool
    error: str | None = None
    duration_ms: float = 0.0


class OrchestrationNode(Protocol):
    """Protocol for orchestration nodes."""

    metadata: NodeMetadata

    async def execute(self, task: str, context: dict[str, Any]) -> OrchestrationResult:
        """Execute the node with given task and context."""
        ...


@dataclass
class DAGNode:
    """
    A node in the orchestration DAG.

    Attributes:
        node_id: Unique identifier for this node
        agent_id: The agent/node name registered in NodeRegistry
        prompt_template: Template for building the node's input prompt.
            Available variables: task, context, results, node_id
        depends_on: List of node_ids that must complete before this node runs.
            Empty list means this node runs at the start (no dependencies).
    """

    node_id: str
    agent_id: str
    prompt_template: str = ""
    depends_on: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        """Serialize to dictionary for JSON."""
        return {
            "node_id": self.node_id,
            "agent_id": self.agent_id,
            "prompt_template": self.prompt_template,
            "depends_on": self.depends_on,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DAGNode:
        """Deserialize from dictionary."""
        return cls(
            node_id=data["node_id"],
            agent_id=data["agent_id"],
            prompt_template=data.get("prompt_template", ""),
            depends_on=data.get("depends_on", []),
        )


@dataclass
class DAG:
    """
    DAG-based orchestration definition.

    Each node in the DAG has dependencies (other node_ids) that must complete
    before it can execute. Nodes with no dependencies can run in parallel.
    """

    name: str
    description: str = ""
    nodes: dict[str, DAGNode] = field(default_factory=dict)
    stop_on_failure: bool = True

    def to_dict(self) -> dict[str, Any]:
        """Serialize to dictionary for JSON."""
        return {
            "name": self.name,
            "description": self.description,
            "stop_on_failure": self.stop_on_failure,
            "nodes": {node_id: node.to_dict() for node_id, node in self.nodes.items()},
        }

    def to_json(self, indent: int | None = 2) -> str:
        """Serialize to JSON string."""
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=indent)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> DAG:
        """Deserialize from dictionary."""
        nodes = {
            node_id: DAGNode.from_dict(node_data)
            for node_id, node_data in data.get("nodes", {}).items()
        }
        return cls(
            name=data["name"],
            description=data.get("description", ""),
            stop_on_failure=data.get("stop_on_failure", True),
            nodes=nodes,
        )

    @classmethod
    def from_json(cls, json_str: str) -> DAG:
        """Deserialize from JSON string."""
        return cls.from_dict(json.loads(json_str))

    def save(self, path: str | Path | None = None, *, name: str | None = None) -> Path:
        """
        Save DAG to a JSON file.

        Args:
            path: Explicit file path. If None, saves to {orchestration_dir}/{name}.json
            name: DAG name used for auto-generated path (ignored if path is provided)

        Returns:
            Path where the DAG was saved
        """
        from topoclaw.config.paths import get_orchestration_dir

        if path is None:
            dag_name = name or self.name
            path = get_orchestration_dir() / f"{dag_name}.json"
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(self.to_json(), encoding="utf-8")
        return path

    @classmethod
    def load(cls, path: str | Path | None = None, *, name: str | None = None) -> DAG:
        """
        Load a DAG from a JSON file.

        Args:
            path: Explicit file path. If None, loads from {orchestration_dir}/{name}.json
            name: DAG name used for auto-generated path (ignored if path is provided)

        Returns:
            Loaded DAG instance

        Raises:
            FileNotFoundError: If the file does not exist
        """
        from topoclaw.config.paths import get_orchestration_dir

        if path is None:
            dag_name = name or "dag"
            path = get_orchestration_dir() / f"{dag_name}.json"
        path = Path(path)
        if not path.exists():
            raise FileNotFoundError(f"DAG file not found: {path}")
        return cls.from_json(path.read_text(encoding="utf-8"))


class NodeAdapter:
    """
    Adapter that wraps an AgentLoop to implement OrchestrationNode.

    This allows existing AgentLoop instances to be used as orchestration nodes
    without modification.
    """

    def __init__(
        self,
        agent_loop: "AgentLoop",
        name: str,
        description: str = "",
        default_session_key: str | None = None,
    ):
        self._agent_loop = agent_loop
        self.metadata = NodeMetadata(name=name, description=description)
        self._default_session_key = default_session_key or f"orchestrator:{name}"

    async def execute(self, task: str, context: dict[str, Any]) -> OrchestrationResult:
        """
        Execute the wrapped AgentLoop with the given task.

        Args:
            task: The input task/prompt for the agent
            context: Additional context (used for metadata passthrough)

        Returns:
            OrchestrationResult with the agent's response
        """
        start_time = time.monotonic()
        try:
            result = await self._agent_loop.process_direct(
                content=task,
                session_key=self._default_session_key,
                metadata=context,
            )
            duration_ms = (time.monotonic() - start_time) * 1000
            return OrchestrationResult(
                node_name=self.metadata.name,
                output=result,
                success=True,
                duration_ms=duration_ms,
            )
        except Exception as e:
            duration_ms = (time.monotonic() - start_time) * 1000
            return OrchestrationResult(
                node_name=self.metadata.name,
                output="",
                success=False,
                error=str(e),
                duration_ms=duration_ms,
            )
