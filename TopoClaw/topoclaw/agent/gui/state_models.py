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

"""Domain models for GUI agent state management."""

from dataclasses import dataclass, field
from typing import Any


@dataclass
class GUIAgentState:
    """Domain model for GUI Agent state.
    
    This model represents the state of a GUI automation session,
    including previous actions, history, and progress tracking.
    """
    
    # Current task and app context
    current_task: str = ""
    current_app: str = "未知应用"
    
    # Previous step information
    previous_screenshot_path: str | None = None
    previous_action: str | None = None
    previous_thought: str | None = None
    previous_action_intent: str | None = None
    
    # History and progress tracking
    history_trajectory: list[dict[str, str]] = field(default_factory=list)
    task_progress_summary: str = ""
    execution_history: list[str] = field(default_factory=list)
    
    # Advanced planning and memory
    todo_list: list[str] = field(default_factory=list)  # Current plan/todo list
    notebook: str = ""  # Notebook for recording key information

    # Opaque state for native GUI backends (e.g. OpenAI previous_response_id)
    backend_state: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary for serialization."""
        return {
            "current_task": self.current_task,
            "current_app": self.current_app,
            "previous_screenshot_path": self.previous_screenshot_path,
            "previous_action": self.previous_action,
            "previous_thought": self.previous_thought,
            "previous_action_intent": self.previous_action_intent,
            "history_trajectory": self.history_trajectory,
            "task_progress_summary": self.task_progress_summary,
            "execution_history": self.execution_history,
            "todo_list": self.todo_list,
            "notebook": self.notebook,
            "backend_state": self.backend_state,
        }
    
    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "GUIAgentState":
        """Create from dictionary (deserialization)."""
        return cls(
            current_task=data.get("current_task", ""),
            current_app=data.get("current_app", "未知应用"),
            previous_screenshot_path=data.get("previous_screenshot_path"),
            previous_action=data.get("previous_action"),
            previous_thought=data.get("previous_thought"),
            previous_action_intent=data.get("previous_action_intent"),
            history_trajectory=data.get("history_trajectory", []),
            task_progress_summary=data.get("task_progress_summary", ""),
            execution_history=data.get("execution_history", []),
            todo_list=data.get("todo_list", []),
            notebook=data.get("notebook", ""),
            backend_state=data.get("backend_state") or {},
        )
    
    def update(self, **kwargs: Any) -> None:
        """Update state fields."""
        for key, value in kwargs.items():
            if hasattr(self, key):
                setattr(self, key, value)
    
    def compress(self) -> None:
        """Compress state to keep only recent history."""
        # Limit history_trajectory to prevent memory issues in long-running sessions
        # Keep last 100 items (enough for context, but prevents unbounded growth)
        if len(self.history_trajectory) > 100:
            self.history_trajectory = self.history_trajectory[-100:]
        
        # Keep only last 20 execution history items
        if len(self.execution_history) > 20:
            self.execution_history = self.execution_history[-20:]
