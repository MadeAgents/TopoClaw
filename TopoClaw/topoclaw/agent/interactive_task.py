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

"""Data models for interactive subagent tasks."""

from __future__ import annotations

import asyncio
import threading
from abc import ABC, abstractmethod
from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class TaskStatus(str, Enum):
    """Status of an interactive task."""

    PENDING = "pending"  # Task created but not yet started
    RUNNING = "running"  # Task is actively executing
    PAUSED_FOR_HUMAN = "paused_for_human"  # Task paused waiting for user input
    COMPLETED = "completed"  # Task finished successfully
    FAILED = "failed"  # Task encountered an error
    CANCELLED = "cancelled"  # Task was cancelled by user
    TIMEOUT = "timeout"  # Task exceeded time limit


class HumanAssistanceState(BaseModel):
    """State when task needs human assistance."""

    assistance_type: str = ""  # e.g., "login", "captcha", "confirmation"
    question: str = ""  # What the user needs to do
    context: dict[str, Any] = Field(default_factory=dict)  # Additional context (URL, page title, etc.)
    timeout_seconds: int = 600  # How long to wait for user response
    reply: str | None = None  # User's reply (when provided)
    replied_at: datetime | None = None  # When the reply was received


class InteractiveTask(BaseModel):
    """Represents an interactive subagent task."""

    task_id: str
    task_type: str  # e.g., "browser-use", or other executor types
    description: str  # Human-readable description
    session_key: str  # Which session owns this task
    channel: str  # Origin channel
    chat_id: str  # Origin chat ID
    original_query: str | None = None  # User's original request that triggered this task
    status: TaskStatus = TaskStatus.PENDING
    created_at: datetime = Field(default_factory=datetime.now)
    started_at: datetime | None = None
    paused_at: datetime | None = None
    completed_at: datetime | None = None
    human_assistance: HumanAssistanceState | None = None
    assistance_history: list[HumanAssistanceState] = Field(default_factory=list)
    result: str | None = None  # Final result when completed
    error: str | None = None  # Error message when failed

    # Runtime state (not persisted)
    _runtime: Any | None = None  # Executor-specific runtime object
    _pause_event: threading.Event | None = None  # For blocking wait in action
    _user_reply: str | None = None  # Store user reply for action to return
    _status_callback: Any | None = None  # Callback to notify status changes

    class Config:
        arbitrary_types_allowed = True

    def trigger_status_change(self) -> None:
        """Trigger status change notification via callback."""
        if self._status_callback:
            try:
                if asyncio.iscoroutinefunction(self._status_callback):
                    asyncio.create_task(self._status_callback(self))
                else:
                    self._status_callback(self)
            except Exception:
                pass


class InteractiveSubagent(ABC):
    """Base class for interactive subagents (background tasks with pause/resume).

    Subclasses implement a concrete modality (e.g. browser automation): **start** via
    :meth:`run`, **human-in-the-loop** via :meth:`pause` / :meth:`resume`, and **interrupt**
    via :meth:`cancel`. The supervisor dispatches by :attr:`executor_type`.
    """

    @property
    @abstractmethod
    def executor_type(self) -> str:
        """Registry key for :class:`topoclaw.agent.task_supervisor.TaskSupervisor`."""
        ...

    @abstractmethod
    async def run(self, task: InteractiveTask) -> None:
        """Execute the task. Should update task.status and handle completion.

        This method should:
        1. Set task.status to RUNNING
        2. Execute the task logic
        3. Call pause() when human assistance is needed
        4. Set task.status to COMPLETED, FAILED, or CANCELLED on finish
        """
        ...

    @abstractmethod
    async def pause(self, task: InteractiveTask, reason: str, context: dict[str, Any] | None = None) -> None:
        """Pause the task and wait for user response.

        Should:
        1. Set task.status to PAUSED_FOR_HUMAN
        2. Create/update human_assistance state
        3. Wait for resume() to be called
        """
        ...

    @abstractmethod
    async def resume(self, task: InteractiveTask, user_reply: str) -> None:
        """Resume a paused task with user response.

        Should:
        1. Update human_assistance.reply
        2. Wake up the paused task
        3. Return control to run()
        """
        ...

    @abstractmethod
    async def cancel(self, task: InteractiveTask) -> None:
        """Cancel a running or paused task."""
        ...

    def get_status_summary(self, task: InteractiveTask) -> dict[str, Any]:
        """Get a status summary for display purposes."""
        summary = {
            "task_id": task.task_id,
            "type": task.task_type,
            "status": task.status.value,
            "description": task.description,
        }
        if task.human_assistance:
            summary["assistance"] = {
                "type": task.human_assistance.assistance_type,
                "question": task.human_assistance.question,
            }
        return summary


# Backwards-compatible alias
InteractiveExecutor = InteractiveSubagent
