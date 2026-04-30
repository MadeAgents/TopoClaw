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

"""Browser-use executor using threading.Event for blocking human assistance."""

from __future__ import annotations

import asyncio
import json
import threading
from concurrent.futures import Future as ConcurrentFuture
from datetime import datetime
from pathlib import Path
from typing import Any, TYPE_CHECKING
from urllib.parse import urlparse

from loguru import logger

from topoclaw.agent.interactive_task import (
    HumanAssistanceState,
    InteractiveSubagent,
    InteractiveTask,
    TaskStatus,
)

# Registry key; must match TaskSupervisor registration for this executor.
BROWSER_USE_TASK_TYPE = "browser-use"


def normalize_browser_start_url(raw: str | None) -> str | None:
    """Return a usable http(s) URL, or None if *raw* is empty/invalid.

    Accepts URLs without a scheme (``https://`` is prepended). Used by
    :class:`SpawnBrowserInteractiveTaskTool` and :class:`BrowserUseExecutor`.
    """
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    if not s.startswith(("http://", "https://")):
        s = "https://" + s
    parsed = urlparse(s)
    if not parsed.netloc:
        return None
    return s


if TYPE_CHECKING:
    from topoclaw.config.schema import Config


SPEED_OPTIMIZATION_PROMPT = """
Speed optimization instructions:
- Be extremely concise and direct in your responses
- Get to the goal as quickly as possible
- Use multi-action sequences whenever possible to reduce steps
"""


# region agent log
def _debug_log(run_id: str, hypothesis_id: str, location: str, message: str, data: dict[str, Any]) -> None:
    try:
        import json
        from datetime import datetime

        with open(r"e:\codes\TopoClaw\debug-3f42c2.log", "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "sessionId": "3f42c2",
                "runId": run_id,
                "hypothesisId": hypothesis_id,
                "location": location,
                "message": message,
                "data": data,
                "timestamp": int(datetime.now().timestamp() * 1000),
            }, ensure_ascii=False) + "\n")
    except Exception:
        pass
# endregion


def _summarize_browser_use_message(message: Any) -> dict[str, Any]:
    role = getattr(message, "role", None)
    content = getattr(message, "content", None)
    summary: dict[str, Any] = {"role": role}

    if isinstance(content, str):
        summary["content"] = content
        return summary

    if isinstance(content, list):
        parts: list[dict[str, Any]] = []
        for part in content:
            part_type = getattr(part, "type", None)
            if part_type == "text":
                parts.append({"type": "text", "text": getattr(part, "text", "")})
                continue
            if part_type == "image_url":
                image_url = getattr(part, "image_url", None)
                url = getattr(image_url, "url", "") if image_url else ""
                parts.append({
                    "type": "image_url",
                    "detail": getattr(image_url, "detail", None) if image_url else None,
                    "media_type": getattr(image_url, "media_type", None) if image_url else None,
                    "url": "[base64 image omitted]" if isinstance(url, str) and url.startswith("data:") else url,
                })
                continue
            try:
                parts.append(json.loads(part.model_dump_json()))
            except Exception:
                parts.append({"type": part_type or type(part).__name__, "repr": repr(part)})
        summary["content"] = parts
        return summary

    summary["content"] = content
    return summary


def _load_browser_use_interactive_override_prompt() -> str:
    prompt_path = Path(__file__).resolve().parent.parent / "prompts" / "browser_use_interactive_system_prompt.md"
    return prompt_path.read_text(encoding="utf-8")


class _PromptLoggingChatModel:
    """Transparent wrapper that dumps browser-use prompts to a txt file."""

    def __init__(self, wrapped: Any, output_path: Path, task_id: str):
        self._wrapped = wrapped
        self._output_path = output_path
        self._task_id = task_id
        self._counter = 0
        self._lock = threading.Lock()

    @property
    def provider(self) -> str:
        return self._wrapped.provider

    @property
    def name(self) -> str:
        return self._wrapped.name

    @property
    def model(self) -> str:
        return self._wrapped.model

    @property
    def model_name(self) -> str:
        return getattr(self._wrapped, "model_name", self._wrapped.model)

    @property
    def _verified_api_keys(self) -> bool:
        return getattr(self._wrapped, "_verified_api_keys", False)

    @_verified_api_keys.setter
    def _verified_api_keys(self, value: bool) -> None:
        setattr(self._wrapped, "_verified_api_keys", value)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._wrapped, name)

    def _dump_prompt(
        self,
        call_index: int,
        messages: list[Any],
        output_format: type[Any] | None,
        kwargs: dict[str, Any],
    ) -> None:
        try:
            payload: dict[str, Any] = {
                "task_id": self._task_id,
                "call_index": call_index,
                "timestamp": datetime.now().isoformat(),
                "model": self.model,
                "provider": self.provider,
                "session_id": kwargs.get("session_id"),
                "kwargs": {k: v for k, v in kwargs.items() if k != "session_id"},
                "messages": [_summarize_browser_use_message(m) for m in messages],
            }

            if output_format is not None:
                payload["output_format"] = getattr(output_format, "__name__", str(output_format))
                try:
                    payload["output_schema"] = output_format.model_json_schema()
                except Exception as e:
                    payload["output_schema_error"] = str(e)

            with self._lock:
                self._output_path.parent.mkdir(parents=True, exist_ok=True)
                with self._output_path.open("a", encoding="utf-8") as f:
                    f.write(f"===== browser-use prompt #{call_index} =====\n")
                    f.write(json.dumps(payload, ensure_ascii=False, indent=2))
                    f.write("\n\n")
            _debug_log(
                "run1",
                "P2",
                "browser_use_executor.py:_PromptLoggingChatModel._dump_prompt",
                "Full prompt dump written",
                {
                    "task_id": self._task_id,
                    "call_index": call_index,
                    "path": str(self._output_path),
                    "payload": payload,
                },
            )
        except Exception:
            _debug_log(
                "run1",
                "P3",
                "browser_use_executor.py:_PromptLoggingChatModel._dump_prompt",
                "Prompt dump failed",
                {
                    "task_id": self._task_id,
                    "call_index": call_index,
                    "path": str(self._output_path),
                },
            )
            logger.exception("Failed to dump browser-use prompt for task {}", self._task_id)

    async def ainvoke(
        self,
        messages: list[Any],
        output_format: type[Any] | None = None,
        **kwargs: Any,
    ) -> Any:
        self._counter += 1
        self._dump_prompt(self._counter, messages, output_format, kwargs)
        return await self._wrapped.ainvoke(messages, output_format=output_format, **kwargs)


class BrowserUseExecutor(InteractiveSubagent):
    """Executor for browser-use with blocking human assistance.

    Uses threading.Event to block the action until user provides response.
    The user reply is returned as the action result to browser-use's LLM.
    Runs browser-use in a separate thread to avoid blocking the asyncio event loop.
    """

    def __init__(self, config: "Config", workspace: Path | None = None):
        self._config = config
        self._workspace = workspace if workspace is not None else config.workspace_path
        self._default_profile_dirname = "browser-use-profile"

    @staticmethod
    def _get_runtime_dict(task: InteractiveTask) -> dict[str, Any]:
        runtime = task._runtime if isinstance(task._runtime, dict) else {}
        if not isinstance(task._runtime, dict):
            task._runtime = runtime
        return runtime

    @property
    def executor_type(self) -> str:
        return BROWSER_USE_TASK_TYPE

    async def run(self, task: InteractiveTask) -> None:
        """Execute browser automation with blocking human assistance.

        Runs browser-use in a separate thread to avoid blocking the asyncio event loop
        when the action waits for human response.
        """
        from topoclaw.agent.browser_use_llm import resolve_gui_llm_for_browser_use
        from topoclaw.config.loader import load_config

        # 每次任务执行前重新读取本地配置，实现部分配置热生效
        try:
            self._config = load_config()
        except Exception as e:
            logger.warning("Failed to reload config before browser-use run, using cached: {}", e)

        task.status = TaskStatus.RUNNING
        task.started_at = task.started_at or __import__('datetime').datetime.now()

        bu = self._config.tools.browser_use
        task_config = task._runtime.get("config", {}) if task._runtime else {}

        # Create threading event for blocking wait
        task._pause_event = threading.Event()

        # Build browser config and LLM in async context
        browser_kw = self._build_browser_kwargs(bu)
        raw_llm = resolve_gui_llm_for_browser_use(self._config)
        prompt_log_path = self._build_prompt_log_path(task)
        llm = _PromptLoggingChatModel(raw_llm, prompt_log_path, task.task_id)
        runtime = self._get_runtime_dict(task)
        runtime["prompt_log_path"] = str(prompt_log_path)
        _debug_log(
            "run1",
            "P1",
            "browser_use_executor.py:run",
            "Prompt log path initialized",
            {"task_id": task.task_id, "path": str(prompt_log_path)},
        )
        logger.info("Browser-use prompt log for task {} => {}", task.task_id, prompt_log_path)

        result_future: ConcurrentFuture[Any] = ConcurrentFuture()
        worker = threading.Thread(
            target=self._run_browser_use_in_thread,
            args=(task, browser_kw, llm, bu, task_config, result_future),
            name=f"browser-use-{task.task_id}",
            daemon=True,
        )
        runtime["worker_thread"] = worker
        runtime["result_future"] = result_future
        worker.start()

        try:
            history = await asyncio.wrap_future(result_future)

            # Task completed
            if task.status != TaskStatus.FAILED:
                task.result = self._format_history(history)
                task.status = TaskStatus.COMPLETED
                task.completed_at = __import__('datetime').datetime.now()
                task.trigger_status_change()

        except asyncio.CancelledError:
            task.status = TaskStatus.CANCELLED
            task.error = "Task was cancelled"
            task.trigger_status_change()
            raise
        except Exception as e:
            task.status = TaskStatus.FAILED
            task.error = f"Browser automation failed: {e}"
            logger.exception("Browser-use task failed: {}", task.task_id)
            task.trigger_status_change()
        finally:
            runtime.pop("result_future", None)

    def _run_browser_use_in_thread(
        self,
        task: InteractiveTask,
        browser_kw: dict[str, Any],
        llm: Any,
        bu: Any,
        task_config: dict[str, Any],
        result_future: ConcurrentFuture[Any],
    ) -> Any:
        """Run browser-use agent in a separate thread."""
        # Create new event loop for this thread
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        runtime = self._get_runtime_dict(task)
        runtime["thread_loop"] = loop
        runtime["thread_name"] = threading.current_thread().name

        try:
            result = loop.run_until_complete(
                self._run_agent_async(task, browser_kw, llm, bu, task_config)
            )
            if not result_future.done():
                result_future.set_result(result)
            return result
        except BaseException as exc:
            if not result_future.done():
                result_future.set_exception(exc)
            raise
        finally:
            runtime["thread_loop"] = None
            loop.close()

    async def _run_agent_async(
        self,
        task: InteractiveTask,
        browser_kw: dict[str, Any],
        llm: Any,
        bu: Any,
        task_config: dict[str, Any],
    ) -> Any:
        """Async function to run browser-use agent within the thread."""
        from browser_use import Agent, Browser, Controller, ActionResult

        # Build browser
        browser = Browser(**browser_kw)

        # Create controller with blocking human assistance action
        controller = Controller()

        # Capture task reference for use in the action
        current_task = task

        @controller.action(
            "Call when you cannot safely continue alone: login/OTP/CAPTCHA/SMS, payment or checkout, "
            "account or consent dialogs, missing credentials, ambiguous targets, or any risky/irreversible "
            "step that needs a real human. Also when the site blocks automation (bot check, rate limit) "
            "and the user required this site. Do not guess, brute-force, or retry the same blocked flow "
            "endlessly.\n\n"
            "Parameter `reason` must be SHORT (about 1–3 sentences; prefer under 200 characters). Use the "
            "same language as the user's task (e.g. Chinese if they wrote Chinese). Say only what the user "
            "should do next—e.g. complete login, pass captcha/slider, close blocking overlay, choose account. "
            "Do NOT repeat the full automation goal and do NOT paste long page transcripts.\n\n"
            "Parameter `require_visible_browser` MUST be True if the human needs to interact with the webpage "
            "(e.g., solve captcha, login). Set to False ONLY if you just need to ask a clarifying question."
        )
        def request_human_assistance(reason: str, require_visible_browser: bool = False) -> Any:
            """Block until the user replies; ``reason`` is shown to the user—keep it brief."""
            if require_visible_browser and browser_kw.get("headless", False):
                current_task.status = TaskStatus.FAILED
                current_task.error = (
                    f"任务中止：遇到需要可视浏览器界面的人工操作（{reason}），但当前为无头模式 (headless=True)。"
                    "请提示用户配置有头模式后重试该任务。请请求人工干预，不要再尝试其他方法"
                )
                agent = current_task._runtime.get("agent")
                if agent:
                    try:
                        agent.stop()
                    except Exception:
                        pass
                current_task.trigger_status_change()
                return current_task.error

            # region agent log
            _debug_log(
                "run1",
                "H3",
                "browser_use_executor.py:request_human_assistance",
                "Human assistance action invoked",
                {"task_id": current_task.task_id, "reason": reason[:500]},
            )
            # endregion
            logger.info("Task {} requesting human assistance: {}", current_task.task_id, reason)

            # Update task status to paused (thread-safe due to GIL for simple assignment)
            current_task.status = TaskStatus.PAUSED_FOR_HUMAN
            current_task.paused_at = __import__('datetime').datetime.now()

            # Create human assistance state
            current_task.human_assistance = HumanAssistanceState(
                assistance_type="request_help",
                question=reason,
                context={"action": "request_human_assistance"},
            )
            current_task.assistance_history.append(current_task.human_assistance)

            # Clear any previous event signal
            current_task._pause_event.clear()
            current_task._user_reply = None

            # Notify status change (thread-safe via call_soon_threadsafe)
            current_task.trigger_status_change()

            logger.info("Task {} waiting for human response...", current_task.task_id)

            # Block until resume() is called or timeout
            timeout_seconds = self._config.tools.interactive.human_assistance_timeout
            if not current_task._pause_event.wait(timeout=timeout_seconds):
                # Timeout - continue with default message
                logger.warning("Task {} human assistance timeout", current_task.task_id)
                current_task.status = TaskStatus.RUNNING
                return ActionResult(
                    output="[Human assistance timeout - no response received, continuing with best effort]",
                    save_in_memory=True,
                )

            if current_task.status == TaskStatus.CANCELLED:
                logger.info("Task {} cancelled while waiting for human assistance", current_task.task_id)
                return ActionResult(
                    output="[Task cancelled while waiting for human assistance]",
                    save_in_memory=True,
                )

            # Got user response
            current_task.status = TaskStatus.RUNNING
            user_reply = current_task._user_reply or "[User acknowledged without specific input]"

            logger.info("Task {} received human response: {}", current_task.task_id, user_reply[:100])

            return ActionResult(
                output=f"[Human assistance received: {user_reply}]",
                save_in_memory=True,
            )

        # region agent log
        _debug_log(
            "run1",
            "H1",
            "browser_use_executor.py:_run_agent_async",
            "Controller action registration snapshot",
            {
                "task_id": task.task_id,
                "registered_actions": sorted(list(controller.registry.registry.actions.keys())),
                "has_request_human_assistance": "request_human_assistance" in controller.registry.registry.actions,
            },
        )
        # endregion

        # Build agent kwargs
        agent_kw: dict[str, Any] = {
            "task": task.description,
            "llm": llm,
            "browser": browser,
            "controller": controller,
            "override_system_message": _load_browser_use_interactive_override_prompt(),
            "use_judge": False,  # Disable judge to avoid false negatives
        }

        # Add flash_mode (default False per schema for interactive tasks)
        flash_mode = task_config.get("flash_mode", False)
        agent_kw["flash_mode"] = flash_mode
        if flash_mode:
            agent_kw["extend_system_message"] = SPEED_OPTIMIZATION_PROMPT

        start_url = normalize_browser_start_url(task_config.get("start_url"))
        if start_url:
            # browser-use runs these before the first LLM step (same format as library auto-URL)
            agent_kw["initial_actions"] = [{"navigate": {"url": start_url, "new_tab": False}}]
            logger.info("Browser-use task {} initial navigate to {}", task.task_id, start_url)

        # Register step logger if enabled
        if bu.log_steps:
            from topoclaw.agent.tools.browser_automation import (
                _make_browser_use_step_logger,
            )

            agent_kw["register_new_step_callback"] = _make_browser_use_step_logger(
                bu.log_steps_max_chars
            )

        # Create agent
        agent = Agent(**agent_kw)
        runtime = self._get_runtime_dict(task)
        runtime.update({"agent": agent, "browser": browser, "controller": controller})
        if task.status == TaskStatus.CANCELLED:
            agent.stop()
            await agent.close()
            raise asyncio.CancelledError("Task cancelled before browser-use agent.run() started")

        # region agent log
        try:
            prompt_description = agent.tools.registry.get_prompt_description(None)
        except Exception as e:
            prompt_description = f"[prompt unavailable: {e}]"
        try:
            action_schema = str(agent.ActionModel.model_json_schema())
        except Exception as e:
            action_schema = f"[schema unavailable: {e}]"
        _debug_log(
            "run1",
            "H2",
            "browser_use_executor.py:_run_agent_async",
            "Agent action exposure snapshot",
            {
                "task_id": task.task_id,
                "agent_registered_actions": sorted(list(agent.tools.registry.registry.actions.keys())),
                "prompt_has_request_human_assistance": "request_human_assistance" in prompt_description,
                "schema_has_request_human_assistance": "request_human_assistance" in action_schema,
            },
        )
        # endregion

        # Run with timeout
        max_steps = task_config.get("max_steps", bu.max_steps)

        # Run agent
        history = await agent.run(max_steps=max_steps)

        # region agent log
        _debug_log(
            "run1",
            "H4",
            "browser_use_executor.py:_run_agent_async",
            "Agent run finished",
            {
                "task_id": task.task_id,
                "status": task.status.value,
                "assistance_history_count": len(task.assistance_history),
                "has_human_assistance": task.human_assistance is not None,
            },
        )
        # endregion

        return history

    async def pause(self, task: InteractiveTask, reason: str, context: dict[str, Any] | None = None) -> None:
        """Manually pause the task (external trigger)."""
        if task.status == TaskStatus.PAUSED_FOR_HUMAN:
            return  # Already paused

        task.status = TaskStatus.PAUSED_FOR_HUMAN
        task.paused_at = __import__('datetime').datetime.now()

        task.human_assistance = HumanAssistanceState(
            assistance_type=context.get("type", "manual_pause") if context else "manual_pause",
            question=reason,
            context=context or {},
        )
        task.assistance_history.append(task.human_assistance)

        task.trigger_status_change()
        logger.info("Task {} manually paused: {}", task.task_id, reason)

    async def resume(self, task: InteractiveTask, user_reply: str) -> None:
        """Resume a paused task with user response.

        This stores the reply and signals the blocking action to continue.
        """
        if task.status != TaskStatus.PAUSED_FOR_HUMAN:
            raise ValueError(f"Cannot resume task in status {task.status}")

        # Store user reply
        task._user_reply = user_reply
        if task.human_assistance:
            task.human_assistance.reply = user_reply
            task.human_assistance.replied_at = __import__('datetime').datetime.now()

        # Signal the blocking action to continue
        if task._pause_event:
            task._pause_event.set()

        logger.info("Task {} resume signal sent with reply: {}", task.task_id, user_reply[:100])

    async def cancel(self, task: InteractiveTask) -> None:
        """Cancel a running or paused task."""
        task.status = TaskStatus.CANCELLED

        # Signal any blocking action to exit
        if task._pause_event:
            task._pause_event.set()

        # Use browser-use's native shutdown path on the worker thread's event loop
        # so internal event buses, watchdogs, and background tasks are stopped consistently.
        runtime = self._get_runtime_dict(task)
        agent = runtime.get("agent")
        browser = runtime.get("browser")
        thread_loop = runtime.get("thread_loop")

        if thread_loop and not thread_loop.is_closed():
            try:
                shutdown_future = asyncio.run_coroutine_threadsafe(
                    self._shutdown_runtime_on_worker_loop(task, agent, browser),
                    thread_loop,
                )
                await asyncio.wait_for(asyncio.wrap_future(shutdown_future), timeout=15)
            except asyncio.TimeoutError:
                logger.warning("Task {} timed out waiting for browser-use shutdown", task.task_id)
            except Exception:
                logger.exception("Task {} failed during worker-loop shutdown", task.task_id)
        else:
            await self._shutdown_runtime_fallback(task, agent, browser)

        worker = runtime.get("worker_thread")
        if isinstance(worker, threading.Thread) and worker.is_alive():
            worker.join(timeout=2)

        task.trigger_status_change()
        logger.info("Task {} cancelled", task.task_id)

    async def _shutdown_runtime_on_worker_loop(
        self,
        task: InteractiveTask,
        agent: Any,
        browser: Any,
    ) -> None:
        if agent:
            try:
                agent.stop()
            except Exception:
                logger.exception("Task {} failed to signal browser-use agent.stop()", task.task_id)

            try:
                await agent.close()
                _debug_log(
                    "run1",
                    "C1",
                    "browser_use_executor.py:_shutdown_runtime_on_worker_loop",
                    "browser-use agent.close completed on worker loop",
                    {"task_id": task.task_id},
                )
                return
            except Exception:
                logger.exception("Task {} failed during browser-use agent.close()", task.task_id)

        await self._shutdown_runtime_fallback(task, agent, browser)

    async def _shutdown_runtime_fallback(
        self,
        task: InteractiveTask,
        agent: Any,
        browser: Any,
    ) -> None:
        target = browser
        if not target and agent is not None:
            target = getattr(agent, "browser_session", None)
        if not target:
            return
        try:
            if hasattr(target, "kill"):
                await target.kill()
            elif hasattr(target, "stop"):
                await target.stop()
            elif hasattr(target, "close"):
                await target.close()
            _debug_log(
                "run1",
                "C2",
                "browser_use_executor.py:_shutdown_runtime_fallback",
                "browser-use fallback shutdown completed",
                {"task_id": task.task_id, "target": type(target).__name__},
            )
        except Exception:
            logger.exception("Task {} failed during browser cleanup fallback", task.task_id)

    def _build_browser_kwargs(self, bu) -> dict[str, Any]:
        """Build browser configuration kwargs."""
        browser_kw: dict[str, Any] = {}

        if bu.cdp_url and str(bu.cdp_url).strip():
            browser_kw["cdp_url"] = str(bu.cdp_url).strip()

        ud = (bu.user_data_dir or "").strip()
        if ud:
            browser_kw["user_data_dir"] = str(Path(ud).expanduser())
        elif not browser_kw.get("cdp_url"):
            default_ud = (self._workspace / self._default_profile_dirname).expanduser().resolve()
            default_ud.mkdir(parents=True, exist_ok=True)
            browser_kw["user_data_dir"] = str(default_ud)

        ex = (bu.chrome_executable_path or "").strip()
        if ex:
            browser_kw["executable_path"] = str(Path(ex).expanduser())

        browser_kw["headless"] = bool(bu.headless)

        if hasattr(bu, "minimum_wait_page_load_time"):
            browser_kw["minimum_wait_page_load_time"] = float(bu.minimum_wait_page_load_time)
        if hasattr(bu, "wait_between_actions"):
            browser_kw["wait_between_actions"] = float(bu.wait_between_actions)

        if bu.start_maximized and not browser_kw["headless"] and not browser_kw.get("cdp_url"):
            extra_args = list(browser_kw.get("args", []))
            if "--start-maximized" not in extra_args:
                extra_args.append("--start-maximized")
            browser_kw["args"] = extra_args

        return browser_kw

    def _build_prompt_log_path(self, task: InteractiveTask) -> Path:
        prompt_dir = (self._workspace / ".topoclaw" / "browser-use-prompts").resolve()
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        return prompt_dir / f"{timestamp}-{task.task_id}-prompt.txt"

    def _format_history(self, history: Any) -> str:
        """Format browser-use history for display."""
        lines: list[str] = []
        final_text = history.final_result() if hasattr(history, "final_result") else None
        if final_text:
            lines.append("--- extracted / final ---")
            lines.append(str(final_text))

        done = history.is_done() if hasattr(history, "is_done") else False
        ok = history.is_successful() if hasattr(history, "is_successful") else False
        lines.append(f"--- status: done={done} success={ok} ---")

        # Include judge verdict if available
        if hasattr(history, "is_judged") and history.is_judged():
            judgement = history.judgement()
            if judgement:
                verdict = judgement.get("verdict")
                reasoning = judgement.get("reasoning", "")[:500]
                failure_reason = judgement.get("failure_reason", "")
                lines.append(f"--- judge verdict: {'PASS' if verdict else 'FAIL'} ---")
                if failure_reason:
                    lines.append(f"judge failure reason: {failure_reason[:500]}")
                elif reasoning:
                    lines.append(f"judge reasoning: {reasoning}")

        if hasattr(history, "has_errors") and history.has_errors():
            err_list = [e for e in history.errors() if e]
            if err_list:
                lines.append("--- errors ---")
                lines.extend(str(e) for e in err_list[:20])

        body = "\n".join(lines) if lines else str(history)
        max_chars = 16000
        if len(body) > max_chars:
            return body[:max_chars] + "\n... (truncated)"
        return body
