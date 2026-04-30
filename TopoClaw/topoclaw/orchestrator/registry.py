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

"""Registry for orchestration nodes and orchestration management."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from loguru import logger

from topoclaw.orchestrator.base import OrchestrationNode, DAG

if TYPE_CHECKING:
    pass


class NodeRegistry:
    """
    Registry that maps node names to OrchestrationNode instances.

    This is the central place to register and look up agent nodes
    that can be used in orchestrations.
    """

    def __init__(self):
        self._nodes: dict[str, OrchestrationNode] = {}

    def register(self, name: str, node: OrchestrationNode) -> None:
        """
        Register an orchestration node.

        Args:
            name: Unique identifier for the node
            node: OrchestrationNode instance

        Raises:
            ValueError: If a node with the given name already exists
        """
        if name in self._nodes:
            raise ValueError(f"Node '{name}' is already registered")
        self._nodes[name] = node

    def get(self, name: str) -> OrchestrationNode | None:
        """
        Get a registered node by name.

        Args:
            name: Node identifier

        Returns:
            The node if found, None otherwise
        """
        return self._nodes.get(name)

    def list_nodes(self) -> list[str]:
        """
        List all registered node names.

        Returns:
            List of node names
        """
        return list(self._nodes.keys())

    def unregister(self, name: str) -> bool:
        """
        Unregister a node.

        Args:
            name: Node identifier

        Returns:
            True if node was removed, False if not found
        """
        if name in self._nodes:
            del self._nodes[name]
            return True
        return False

    def clear(self) -> None:
        """Remove all registered nodes."""
        self._nodes.clear()

    def __len__(self) -> int:
        return len(self._nodes)

    def __contains__(self, name: str) -> bool:
        return name in self._nodes


# Module-level singleton registry
_default_registry: NodeRegistry | None = None


def get_registry() -> NodeRegistry:
    """
    Get the default shared NodeRegistry instance.

    Returns:
        The singleton NodeRegistry
    """
    global _default_registry
    if _default_registry is None:
        _default_registry = NodeRegistry()
    return _default_registry


class OrchestrationRegistry:
    """
    Registry that manages all DAG orchestrations.

    Orchestrations are loaded from files and can be looked up by name.
    """

    def __init__(self):
        self._orchestrations: dict[str, DAG] = {}

    def load_from_file(self, path: Path) -> DAG:
        """
        Load a DAG from a JSON file and register it.

        Args:
            path: Path to the DAG JSON file

        Returns:
            The loaded DAG

        Raises:
            FileNotFoundError: If the file does not exist
        """
        dag = DAG.load(path)
        self._orchestrations[dag.name] = dag
        logger.info("Loaded orchestration '{}' from {}", dag.name, path)
        return dag

    def load_from_dir(self, dir_path: Path) -> list[DAG]:
        """
        Load all DAG JSON files from a directory (synchronous).

        Args:
            dir_path: Directory containing DAG JSON files

        Returns:
            List of loaded DAGs
        """
        loaded = []
        if not dir_path.is_dir():
            return loaded
        for json_file in dir_path.glob("*.json"):
            try:
                dag = DAG.load(json_file)
                self._orchestrations[dag.name] = dag
                loaded.append(dag)
            except Exception as e:
                logger.warning("Failed to load orchestration from {}: {}", json_file, e)
        return loaded

    def register(self, dag: DAG) -> None:
        """
        Register a DAG directly.

        Args:
            dag: The DAG to register
        """
        self._orchestrations[dag.name] = dag

    def get(self, name: str) -> DAG | None:
        """
        Get a registered orchestration by name.

        Args:
            name: Orchestration name

        Returns:
            The DAG if found, None otherwise
        """
        return self._orchestrations.get(name)

    def list_orchestrations(self) -> list[str]:
        """
        List all registered orchestration names.

        Returns:
            List of orchestration names
        """
        return list(self._orchestrations.keys())

    def unregister(self, name: str) -> bool:
        """
        Unregister an orchestration.

        Args:
            name: Orchestration name

        Returns:
            True if removed, False if not found
        """
        if name in self._orchestrations:
            del self._orchestrations[name]
            return True
        return False

    def clear(self) -> None:
        """Remove all registered orchestrations."""
        self._orchestrations.clear()

    def __len__(self) -> int:
        return len(self._orchestrations)

    def __contains__(self, name: str) -> bool:
        return name in self._orchestrations


# Module-level singleton
_default_orchestration_registry: OrchestrationRegistry | None = None


def get_orchestration_registry() -> OrchestrationRegistry:
    """
    Get the default shared OrchestrationRegistry instance.

    Returns:
        The singleton OrchestrationRegistry
    """
    global _default_orchestration_registry
    if _default_orchestration_registry is None:
        _default_orchestration_registry = OrchestrationRegistry()
    return _default_orchestration_registry