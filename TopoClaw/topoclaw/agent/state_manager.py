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

"""Agent state management for session-based state isolation."""

import asyncio
from typing import Any, TypeVar

from topoclaw.session.manager import SessionManager

T = TypeVar("T")

# Registry for agent state models (populated by agent modules)
_AGENT_STATE_MODELS: dict[str, type] = {}


def get_state_model(agent_type: str) -> type | None:
    """Get state model class for agent type.
    
    Args:
        agent_type: Agent type (e.g., "gui", "sub")
    
    Returns:
        State model class or None if not found
    """
    return _AGENT_STATE_MODELS.get(agent_type)


def register_state_model(agent_type: str, model_class: type) -> None:
    """Register a state model for an agent type.
    
    Args:
        agent_type: Agent type identifier
        model_class: State model class (must have to_dict/from_dict methods)
    """
    _AGENT_STATE_MODELS[agent_type] = model_class


class AgentStateManager:
    """Manages agent state with session-based isolation."""

    def __init__(self, session_manager: SessionManager):
        """Initialize AgentStateManager.

        Args:
            session_manager: SessionManager instance
        """
        self.sessions = session_manager

    async def get_state(self, session_key: str, agent_type: str) -> dict:
        """Get state for a specific agent type in a session (thread-safe).

        Args:
            session_key: Session key
            agent_type: Agent type (e.g., "gui", "sub")

        Returns:
            State dictionary for the agent type
        """
        session = await self.sessions.get_or_create_locked(session_key)
        state_dict = session.get_state(agent_type)
        
        # Convert to domain model if available
        model_class = get_state_model(agent_type)
        if model_class and state_dict:
            return model_class.from_dict(state_dict).to_dict()
        
        return state_dict
    
    async def get_state_model(self, session_key: str, agent_type: str) -> Any:
        """Get state as domain model for a specific agent type (thread-safe).

        Args:
            session_key: Session key
            agent_type: Agent type (e.g., "gui", "sub")

        Returns:
            State model instance or dict if no model available
        """
        session = await self.sessions.get_or_create_locked(session_key)
        state_dict = session.get_state(agent_type)
        
        # Convert to domain model if available
        model_class = get_state_model(agent_type)
        if model_class:
            return model_class.from_dict(state_dict) if state_dict else model_class()
        
        return state_dict

    async def update_state(
        self,
        session_key: str,
        agent_type: str,
        **kwargs: Any,
    ) -> None:
        """Update state for a specific agent type in a session (thread-safe).

        Args:
            session_key: Session key
            agent_type: Agent type (e.g., "gui", "sub")
            **kwargs: State fields to update
        """
        session = await self.sessions.get_or_create_locked(session_key)
        state_dict = session.get_state(agent_type)
        
        # Use domain model if available
        model_class = get_state_model(agent_type)
        if model_class:
            # Load existing state or create new
            state_model = model_class.from_dict(state_dict) if state_dict else model_class()
            # Update fields
            state_model.update(**kwargs)
            # Compress if needed
            state_model.compress()
            # Convert back to dict
            state_dict = state_model.to_dict()
            session.state[agent_type] = state_dict
        else:
            # Fallback to dict update
            state_dict.update(kwargs)
            session.state[agent_type] = state_dict
        
        # Save to disk asynchronously
        await asyncio.to_thread(self.sessions.save, session)
    
    async def update_state_model(
        self,
        session_key: str,
        agent_type: str,
        state_model: Any,
    ) -> None:
        """Update state using domain model (thread-safe).

        Args:
            session_key: Session key
            agent_type: Agent type (e.g., "gui", "sub")
            state_model: State model instance
        """
        session = await self.sessions.get_or_create_locked(session_key)
        
        # Compress before saving
        if hasattr(state_model, "compress"):
            state_model.compress()
        
        # Convert to dict and save
        if hasattr(state_model, "to_dict"):
            session.state[agent_type] = state_model.to_dict()
        else:
            # Fallback: convert dataclass to dict
            import dataclasses
            session.state[agent_type] = dataclasses.asdict(state_model)
        
        # Save to disk asynchronously
        await asyncio.to_thread(self.sessions.save, session)

    def get_state_sync(self, session_key: str, agent_type: str) -> dict:
        """Get state synchronously (for non-async contexts).

        Args:
            session_key: Session key
            agent_type: Agent type

        Returns:
            State dictionary for the agent type
        """
        session = self.sessions.get_or_create(session_key)
        state_dict = session.get_state(agent_type)
        
        # Convert to domain model if available
        model_class = get_state_model(agent_type)
        if model_class and state_dict:
            return model_class.from_dict(state_dict).to_dict()
        
        return state_dict
    
    def get_state_model_sync(self, session_key: str, agent_type: str) -> Any:
        """Get state as domain model synchronously (for non-async contexts).

        Args:
            session_key: Session key
            agent_type: Agent type

        Returns:
            State model instance or dict if no model available
        """
        session = self.sessions.get_or_create(session_key)
        state_dict = session.get_state(agent_type)
        
        # Convert to domain model if available
        model_class = get_state_model(agent_type)
        if model_class:
            return model_class.from_dict(state_dict) if state_dict else model_class()
        
        return state_dict

    def update_state_sync(
        self,
        session_key: str,
        agent_type: str,
        **kwargs: Any,
    ) -> None:
        """Update state synchronously (for non-async contexts).

        Args:
            session_key: Session key
            agent_type: Agent type
            **kwargs: State fields to update
        """
        session = self.sessions.get_or_create(session_key)
        state_dict = session.get_state(agent_type)
        
        # Use domain model if available
        model_class = get_state_model(agent_type)
        if model_class:
            # Load existing state or create new
            state_model = model_class.from_dict(state_dict) if state_dict else model_class()
            # Update fields
            state_model.update(**kwargs)
            # Compress if needed
            state_model.compress()
            # Convert back to dict
            state_dict = state_model.to_dict()
            session.state[agent_type] = state_dict
        else:
            # Fallback to dict update
            state_dict.update(kwargs)
            session.state[agent_type] = state_dict
        
        self.sessions.save(session)

    def clear_state(self, session_key: str, agent_type: str) -> None:
        """Clear state for a specific agent type.

        Args:
            session_key: Session key
            agent_type: Agent type
        """
        session = self.sessions.get_or_create(session_key)
        if agent_type in session.state:
            del session.state[agent_type]
        self.sessions.save(session)
