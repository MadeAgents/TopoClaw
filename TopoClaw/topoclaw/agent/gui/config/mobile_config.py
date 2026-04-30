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

"""Configuration for Mobile GUI Agent."""

from pathlib import Path

from topoclaw.agent.gui.config.base import GUIAgentConfig


class MobileGUIConfig(GUIAgentConfig):
    """Configuration for Mobile GUI Agent.

    Dual screenshot mode: override ``include_previous_screenshot_in_user_message``
    (see GUIAgentConfig) to return True if each turn should include previous + current images.
    """

    @property
    def agent_type(self) -> str:
        return "mobile"
    
    @property
    def default_temperature(self) -> float:
        return 0.3
    
    @property
    def default_max_iterations(self) -> int:
        return 20
    
    def create_prompt_loader(self, workspace: Path) -> "PromptLoader":
        """Create prompt loader for mobile agent."""
        from topoclaw.agent.gui.prompt_loader import PromptLoader
        return PromptLoader(workspace)
    
    def is_complete_action(self, action_str: str) -> bool:
        """Check if mobile action indicates completion."""
        action_lower = action_str.lower()
        return action_lower.startswith("complete") or action_lower.startswith("answer")
