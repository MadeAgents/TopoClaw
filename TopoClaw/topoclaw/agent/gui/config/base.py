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

"""Base configuration for GUI agents."""

from abc import ABC, abstractmethod
from pathlib import Path


class GUIAgentConfig(ABC):
    """Base configuration interface for GUI agents."""
    
    @property
    @abstractmethod
    def agent_type(self) -> str:
        """Agent type identifier (e.g., 'mobile').
        
        This is used by ModelAdapter to select the appropriate action patterns.
        """
        pass
    
    @property
    @abstractmethod
    def default_temperature(self) -> float:
        """Default temperature for this agent type."""
        pass
    
    @property
    @abstractmethod
    def default_max_iterations(self) -> int:
        """Default max iterations for this agent type."""
        pass

    @property
    def include_previous_screenshot_in_user_message(self) -> bool:
        """If True, each user turn may include previous frame (action-rendered) plus current.

        If False (default), only the current screenshot is attached to the user message;
        prior-step context remains in the system prompt (state / trajectory).
        """
        return False

    @abstractmethod
    def create_prompt_loader(self, workspace: Path) -> "PromptLoader":
        """Create prompt loader for this agent type.
        
        Returns:
            PromptLoader instance
        """
        pass
    
    def is_complete_action(self, action_str: str) -> bool:
        """Check if action indicates task completion.
        
        Args:
            action_str: Action string (e.g., "complete", "COMPLETE", "answer[...]")
            
        Returns:
            True if action indicates completion
        """
        action_lower = action_str.lower()
        return action_lower.startswith("complete") or action_lower.startswith("answer")
    
    def get_adapter_agent_type(self) -> str:
        """Get agent type string for model adapter (default: same as agent_type).
        
        Note: ModelAdapter uses this to select action patterns via get_action_patterns().
        """
        return self.agent_type
