"""Event models for supervisor-managed interactive tasks."""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class TaskEventType(str, Enum):
    """High-level task lifecycle events emitted by the supervisor."""

    TASK_STARTED = "task_started"
    HUMAN_ASSISTANCE_REQUESTED = "human_assistance_requested"
    TASK_COMPLETED = "task_completed"
    TASK_FAILED = "task_failed"
    TASK_CANCELLED = "task_cancelled"
    TASK_STATUS_CHANGED = "task_status_changed"


class TaskEvent(BaseModel):
    """Structured event emitted by the task supervisor."""

    event_id: str
    task_id: str
    session_key: str
    event_type: TaskEventType
    created_at: datetime = Field(default_factory=datetime.now)
    payload: dict[str, Any] = Field(default_factory=dict)

