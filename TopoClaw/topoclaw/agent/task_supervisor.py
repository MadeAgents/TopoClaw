"""Supervisor for interactive tasks and subagent lifecycles."""

from __future__ import annotations

import asyncio
import uuid
from pathlib import Path
from typing import Any, Callable, TYPE_CHECKING

from loguru import logger

from topoclaw.agent.interactive_task import InteractiveSubagent, InteractiveTask, TaskStatus
from topoclaw.agent.task_events import TaskEvent, TaskEventType

if TYPE_CHECKING:
    from topoclaw.bus.queue import MessageBus
    from topoclaw.config.schema import Config


class TaskSupervisor:
    """Lifecycle controller for interactive tasks.

    Owns:
    - task creation and storage
    - executor registry
    - task execution/cancellation
    - high-level task events emitted back to AgentLoop
    """

    def __init__(
        self,
        bus: "MessageBus",
        config: "Config",
        workspace: Path | None = None,
    ) -> None:
        self.bus = bus
        self.config = config
        self.workspace = workspace if workspace is not None else config.workspace_path

        self._tasks: dict[str, InteractiveTask] = {}
        self._session_tasks: dict[str, set[str]] = {}
        self._recent_terminal_tasks: dict[str, list[dict[str, Any]]] = {}
        self._running_tasks: dict[str, asyncio.Task[None]] = {}
        self._executors: dict[str, InteractiveSubagent] = {}
        self._event_callback: Callable[[TaskEvent], None] | None = None
        self._main_loop: asyncio.AbstractEventLoop | None = None

        self._init_default_executors()

    def _init_default_executors(self) -> None:
        if self.config.tools.browser_use.enabled:
            from topoclaw.agent.executors.browser_use_executor import BrowserUseExecutor

            executor = BrowserUseExecutor(self.config, self.workspace)
            self.register_executor(executor.executor_type, executor)
            logger.info("Registered executor: browser-use")

    def register_executor(self, task_type: str, executor: InteractiveSubagent) -> None:
        self._executors[task_type] = executor
        logger.info("Registered executor for type: {}", task_type)

    def register_event_callback(self, callback: Callable[[TaskEvent], None]) -> None:
        self._event_callback = callback
        try:
            self._main_loop = asyncio.get_running_loop()
        except RuntimeError:
            self._main_loop = None

    def spawn_task(
        self,
        task_type: str,
        description: str,
        session_key: str,
        channel: str,
        chat_id: str,
        original_query: str | None = None,
        metadata: dict[str, Any] | None = None,
        config: dict[str, Any] | None = None,
    ) -> str:
        if self._main_loop is None:
            try:
                self._main_loop = asyncio.get_running_loop()
            except RuntimeError:
                pass

        if task_type not in self._executors:
            raise ValueError(f"Unknown task type: {task_type}. Available: {list(self._executors.keys())}")

        task_id = str(uuid.uuid4())[:8]
        task = InteractiveTask(
            task_id=task_id,
            task_type=task_type,
            description=description,
            session_key=session_key,
            channel=channel,
            chat_id=chat_id,
            original_query=original_query,
        )
        task._runtime = {
            "config": config or {},
            "metadata": dict(metadata or {}),
        }
        task._status_callback = self._notify_status_change_threadsafe

        self._tasks[task_id] = task
        self._session_tasks.setdefault(session_key, set()).add(task_id)

        bg_task = asyncio.create_task(
            self._run_task(task_id),
            name=f"interactive-task-{task_id}",
        )
        self._running_tasks[task_id] = bg_task

        def _cleanup(_: asyncio.Task) -> None:
            self._running_tasks.pop(task_id, None)

        bg_task.add_done_callback(_cleanup)
        logger.info("Spawned interactive task [{}]: type={} session={}", task_id, task_type, session_key)
        return task_id

    async def _run_task(self, task_id: str) -> None:
        task = self._tasks.get(task_id)
        if not task:
            logger.error("Task not found: {}", task_id)
            return

        if not task._status_callback:
            task._status_callback = self._notify_status_change_threadsafe

        executor = self._executors.get(task.task_type)
        if not executor:
            task.status = TaskStatus.FAILED
            task.error = f"No executor found for type: {task.task_type}"
            task.trigger_status_change()
            return

        try:
            await executor.run(task)
        except asyncio.CancelledError:
            task.status = TaskStatus.CANCELLED
            task.error = "Task was cancelled"
            task.trigger_status_change()
            raise
        except Exception as exc:
            task.status = TaskStatus.FAILED
            task.error = str(exc)
            logger.exception("Task {} failed", task_id)
            task.trigger_status_change()

    def _notify_status_change_threadsafe(self, task: InteractiveTask) -> None:
        event = self._build_event_for_task(task)
        if event is None or not self._event_callback:
            return
        self._record_prompt_view(task, event)

        try:
            current_loop = asyncio.get_running_loop()
            if self._main_loop is None:
                self._main_loop = current_loop
            if current_loop is self._main_loop:
                self._event_callback(event)
            elif self._main_loop and not self._main_loop.is_closed():
                self._main_loop.call_soon_threadsafe(lambda: self._schedule_event_callback(event))
        except RuntimeError:
            if self._main_loop and not self._main_loop.is_closed():
                self._main_loop.call_soon_threadsafe(lambda: self._schedule_event_callback(event))
            else:
                self._event_callback(event)
        except Exception:
            logger.exception("Task event callback failed for task {}", task.task_id)

    def _schedule_event_callback(self, event: TaskEvent) -> None:
        if not self._event_callback:
            return
        try:
            self._event_callback(event)
        except Exception:
            logger.exception("Task event callback failed for task {}", event.task_id)

    def _build_event_for_task(self, task: InteractiveTask) -> TaskEvent | None:
        payload: dict[str, Any] = {
            "task_type": task.task_type,
            "status": task.status.value,
            "description": task.description,
        }

        if task.human_assistance:
            payload["human_assistance"] = {
                "type": task.human_assistance.assistance_type,
                "question": task.human_assistance.question,
                "context": task.human_assistance.context,
                "replied": task.human_assistance.reply is not None,
            }
        if task.result:
            payload["result"] = task.result
        if task.error:
            payload["error"] = task.error

        event_type = TaskEventType.TASK_STATUS_CHANGED
        if task.status == TaskStatus.RUNNING and task.started_at is not None:
            event_type = TaskEventType.TASK_STARTED
        elif task.status == TaskStatus.PAUSED_FOR_HUMAN:
            event_type = TaskEventType.HUMAN_ASSISTANCE_REQUESTED
        elif task.status == TaskStatus.COMPLETED:
            event_type = TaskEventType.TASK_COMPLETED
        elif task.status == TaskStatus.FAILED:
            event_type = TaskEventType.TASK_FAILED
        elif task.status == TaskStatus.CANCELLED:
            event_type = TaskEventType.TASK_CANCELLED

        return TaskEvent(
            event_id=str(uuid.uuid4()),
            task_id=task.task_id,
            session_key=task.session_key,
            event_type=event_type,
            payload=payload,
        )

    @staticmethod
    def _build_task_summary(task: InteractiveTask) -> dict[str, Any]:
        status = task.status.value
        question = task.human_assistance.question if task.human_assistance else None
        final_summary = task.result or task.error or ""
        return {
            "task_id": task.task_id,
            "task_type": task.task_type,
            "status": status,
            "description": task.description,
            "last_user_visible_question": question,
            "final_result_summary": final_summary,
            "can_resume": status == TaskStatus.PAUSED_FOR_HUMAN.value,
            "can_cancel": status in {
                TaskStatus.PENDING.value,
                TaskStatus.RUNNING.value,
                TaskStatus.PAUSED_FOR_HUMAN.value,
            },
            "created_at": task.created_at.isoformat() if task.created_at else None,
            "updated_at": (
                task.completed_at.isoformat()
                if task.completed_at
                else task.paused_at.isoformat()
                if task.paused_at
                else task.started_at.isoformat()
                if task.started_at
                else task.created_at.isoformat()
            ),
        }

    def _record_prompt_view(self, task: InteractiveTask, event: TaskEvent) -> None:
        session_key = task.session_key
        task_id = task.task_id
        if event.event_type in (TaskEventType.TASK_COMPLETED, TaskEventType.TASK_FAILED, TaskEventType.TASK_CANCELLED):
            recent = [
                item for item in self._recent_terminal_tasks.get(session_key, [])
                if str(item.get("task_id") or "") != task_id
            ]
            summary = self._build_task_summary(task)
            summary["last_event_type"] = event.event_type.value
            recent.insert(0, summary)
            self._recent_terminal_tasks[session_key] = recent[:3]
        else:
            recent = self._recent_terminal_tasks.get(session_key, [])
            if recent:
                self._recent_terminal_tasks[session_key] = [
                    item for item in recent if str(item.get("task_id") or "") != task_id
                ]

    def get_task(self, task_id: str) -> InteractiveTask | None:
        return self._tasks.get(task_id)

    async def resume_task(self, task_id: str, user_reply: str) -> bool:
        task = self._tasks.get(task_id)
        if not task:
            logger.warning("Cannot resume: task {} not found", task_id)
            return False
        if task.status != TaskStatus.PAUSED_FOR_HUMAN:
            logger.warning("Cannot resume: task {} is not paused (status: {})", task_id, task.status)
            return False

        executor = self._executors.get(task.task_type)
        if not executor:
            logger.error("Cannot resume: no executor for type {}", task.task_type)
            return False

        try:
            await executor.resume(task, user_reply)
            return True
        except Exception:
            logger.exception("Failed to resume task {}", task_id)
            return False

    async def cancel_task(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if not task:
            return False
        if task.status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED):
            return False

        bg_task = self._running_tasks.get(task_id)
        if bg_task and not bg_task.done():
            bg_task.cancel()

        executor = self._executors.get(task.task_type)
        if executor:
            try:
                await executor.cancel(task)
            except Exception:
                logger.exception("Error cancelling task {}", task_id)

        task.status = TaskStatus.CANCELLED
        self._notify_status_change_threadsafe(task)
        return True

    async def close_task(self, task_id: str) -> bool:
        task = self._tasks.get(task_id)
        if not task:
            return False
        task_ids = self._session_tasks.get(task.session_key)
        if task_ids:
            task_ids.discard(task_id)
            if not task_ids:
                self._session_tasks.pop(task.session_key, None)
        self._running_tasks.pop(task_id, None)
        return self._tasks.pop(task_id, None) is not None

    async def cancel_by_session(self, session_key: str) -> int:
        cancelled = 0
        for task_id in list(self._session_tasks.get(session_key, set())):
            if await self.cancel_task(task_id):
                cancelled += 1
        return cancelled

    def list_session_tasks(self, session_key: str) -> list[dict[str, Any]]:
        task_ids = self._session_tasks.get(session_key, set())
        return [
            {
                "task_id": self._tasks[task_id].task_id,
                "type": self._tasks[task_id].task_type,
                "status": self._tasks[task_id].status.value,
                "description": self._tasks[task_id].description,
            }
            for task_id in task_ids
            if task_id in self._tasks
        ]

    def list_paused_tasks(self, session_key: str) -> list[InteractiveTask]:
        return [
            self._tasks[task_id]
            for task_id in self._session_tasks.get(session_key, set())
            if task_id in self._tasks and self._tasks[task_id].status == TaskStatus.PAUSED_FOR_HUMAN
        ]

    def has_paused_tasks(self, session_key: str) -> bool:
        return bool(self.list_paused_tasks(session_key))

    def list_actionable_tasks(self, session_key: str) -> list[dict[str, Any]]:
        return [
            item for item in self.get_task_summaries(session_key)
            if bool(item.get("can_resume")) or bool(item.get("can_cancel"))
        ]

    def get_task_summaries(self, session_key: str) -> list[dict[str, Any]]:
        active: list[dict[str, Any]] = []
        for task_id in self._session_tasks.get(session_key, set()):
            task = self._tasks.get(task_id)
            if not task:
                continue
            active.append(self._build_task_summary(task))
        recent = [dict(item) for item in self._recent_terminal_tasks.get(session_key, [])[:2]]
        return active + recent
