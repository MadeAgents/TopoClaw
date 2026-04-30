"""Task control tools for supervisor-managed interactive tasks."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from topoclaw.agent.tools.base import Tool

if TYPE_CHECKING:
    from topoclaw.agent.task_supervisor import TaskSupervisor


class _TaskControlTool(Tool):
    def __init__(self, supervisor: "TaskSupervisor") -> None:
        self._supervisor = supervisor
        self._session_key = "cli:direct"

    def set_context(self, channel: str, chat_id: str, session_key: str | None = None) -> None:
        self._session_key = session_key or f"{channel}:{chat_id}"

    def _is_active_for_session(self, task_id: str, allowed_statuses: set[str] | None = None) -> bool:
        task = self._supervisor.get_task(task_id)
        if not task or task.session_key != self._session_key:
            return False
        if allowed_statuses is None:
            return True
        return task.status.value in allowed_statuses

    def _resolve_task_id(
        self,
        task_id: str | None,
        *,
        allowed_statuses: set[str] | None = None,
    ) -> str | None:
        explicit = (task_id or "").strip()
        if explicit:
            if self._is_active_for_session(explicit, allowed_statuses):
                return explicit
            return None

        matches = [
            item for item in self._supervisor.get_task_summaries(self._session_key)
            if allowed_statuses is None or str(item.get("status") or "") in allowed_statuses
        ]
        if len(matches) == 1:
            return str(matches[0].get("task_id") or "").strip() or None
        return None

    def _describe_resolution_error(
        self,
        task_id: str | None,
        *,
        action: str,
        allowed_statuses: set[str] | None = None,
    ) -> str:
        explicit = (task_id or "").strip()
        if explicit:
            task = self._supervisor.get_task(explicit)
            if not task:
                return (
                    f"Error: Task {explicit} is not active in the current session. "
                    f"It may have already completed, failed, been cancelled, or been closed."
                )
            if task.session_key != self._session_key:
                return f"Error: Task {explicit} does not belong to the current session."
            if allowed_statuses and task.status.value not in allowed_statuses:
                statuses = ", ".join(sorted(allowed_statuses))
                return f"Error: Task {explicit} is in status '{task.status.value}', not one of: {statuses}."
            return f"Error: Could not resolve which task to {action}."

        matches = [
            item for item in self._supervisor.get_task_summaries(self._session_key)
            if allowed_statuses is None or str(item.get("status") or "") in allowed_statuses
        ]
        if not matches:
            if allowed_statuses:
                statuses = ", ".join(sorted(allowed_statuses))
                return f"Error: No task in the current session is in a controllable status ({statuses})."
            return f"Error: No active task found in the current session to {action}."
        return f"Error: Multiple candidate tasks exist; specify task_id explicitly to {action} one."


class ResumeTaskTool(_TaskControlTool):
    name = "resume_task"
    description = (
        "Resume a paused background task managed by the task supervisor. "
        "Use this when the user is clearly replying to a paused browser or interactive task."
    )
    parameters = {
        "type": "object",
        "properties": {
            "task_id": {
                "type": "string",
                "description": "Task ID to resume. Optional only when exactly one paused task exists in the current session.",
            },
            "user_reply": {
                "type": "string",
                "description": "The user's reply or confirmation to send back to the paused task.",
                "minLength": 1,
            },
        },
        "required": ["user_reply"],
    }

    async def execute(self, task_id: str | None = None, user_reply: str = "", **kwargs: Any) -> str:
        allowed = {"paused_for_human"}
        resolved = self._resolve_task_id(task_id, allowed_statuses=allowed)
        if not resolved:
            return self._describe_resolution_error(task_id, action="resume", allowed_statuses=allowed)
        ok = await self._supervisor.resume_task(resolved, user_reply)
        if not ok:
            return f"Error: Failed to resume task {resolved}."
        return f"Resumed background task {resolved}."


class CancelTaskTool(_TaskControlTool):
    name = "cancel_task"
    description = (
        "Cancel a background task managed by the task supervisor. "
        "Use this when the user explicitly asks to stop or abandon a paused/running task."
    )
    parameters = {
        "type": "object",
        "properties": {
            "task_id": {
                "type": "string",
                "description": "Task ID to cancel. Optional only when exactly one paused task exists in the current session.",
            },
        },
        "required": [],
    }

    async def execute(self, task_id: str | None = None, **kwargs: Any) -> str:
        allowed = {"pending", "running", "paused_for_human"}
        resolved = self._resolve_task_id(task_id, allowed_statuses=allowed)
        if not resolved:
            return self._describe_resolution_error(task_id, action="cancel", allowed_statuses=allowed)
        ok = await self._supervisor.cancel_task(resolved)
        if not ok:
            return f"Error: Failed to cancel task {resolved}."
        return f"Cancelled background task {resolved}."
