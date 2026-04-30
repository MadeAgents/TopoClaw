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

"""Agent loop: the core processing engine."""

from __future__ import annotations

import asyncio
import json
import re
from contextlib import AsyncExitStack
from copy import deepcopy
from pathlib import Path
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from loguru import logger

from topoclaw.models.constant import (
    TOOL_GUARD_CONFIRM_TYPE_DENY,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_EDIT,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_READ_ONLY,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_EDIT,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_READ_ONLY,
    TOOL_GUARD_CONFIRM_TYPE_INVALID,
    TOOL_GUARD_CONFIRM_TYPE_TEMPORARY_ALLOW,
    TOOL_GUARD_CONFIRM_TYPE_TIMEOUT,
)

from topoclaw.agent.context import ContextBuilder
from topoclaw.agent.memory import MemoryConsolidator
from topoclaw.agent.subagent import SubagentManager
from topoclaw.agent.task_events import TaskEvent, TaskEventType
from topoclaw.agent.tools.cron import CronTool
from topoclaw.agent.tools.filesystem import EditFileTool, ListDirTool, ReadFileTool, WriteFileTool
from topoclaw.agent.tools.gui_task import GUITaskTool
from topoclaw.agent.tools.image_generation import ImageGenerationTool
from topoclaw.agent.tools.message import MessageTool
from topoclaw.agent.tools.read_medias import ReadImageTool, ReadPdfTool
from topoclaw.agent.tools.registry import ToolRegistry
from topoclaw.agent.tools.memory_update import MemoryUpdateTool
from topoclaw.agent.tools.session_memory import SessionMemoryCompressTool
from topoclaw.agent.tools.shell import ExecTool
from topoclaw.agent.tools.mobile_get_location import MobileGetLocationTool
from topoclaw.agent.tools.mobile_location_skill import MobileLocationSkillTool
from topoclaw.agent.tools.mobile_deeplink import ExecuteMobileDeeplinkTool
from topoclaw.agent.tools.deeplink_lookup import SearchDeeplinkCatalogTool
from topoclaw.agent.tools.spawn import SpawnTool
from topoclaw.agent.tools.spawn_browser_interactive import SpawnBrowserInteractiveTaskTool
from topoclaw.agent.tools.task_control import CancelTaskTool, ResumeTaskTool
from topoclaw.agent.tools.academic_search import (
    OpenAlexPaperSearchTool,
    SemanticScholarPaperSearchTool,
)
from topoclaw.agent.tools.arxiv_search import ArxivSearchTool
from topoclaw.agent.tools.deepxiv import (
    DeepxivArxivReadTool,
    DeepxivArxivSearchTool,
    DeepxivArxivTrendingTool,
)
from topoclaw.agent.tools.web import WebFetchTool, WebSearchTool
from topoclaw.agent.tools.serper import SerperSearchTool
from topoclaw.bus.events import InboundMessage, OutboundMessage
from topoclaw.bus.queue import MessageBus
from topoclaw.providers.base import LLMProvider
from topoclaw.secure.toolcall_guard import ToolcallGuard, parse_tool_arguments
from topoclaw.session.compress_marker import merge_followup_user_into_compressed_tail
from topoclaw.session.manager import Session, SessionManager

if TYPE_CHECKING:
    from topoclaw.config.schema import ChannelsConfig, Config, ExecToolConfig
    from topoclaw.cron.service import CronService


class AgentLoop:
    """
    The agent loop is the core processing engine.

    It:
    1. Receives messages from the bus
    2. Builds context with history, memory, skills
    3. Calls the LLM
    4. Executes tool calls
    5. Sends responses back
    """

    _TOOL_RESULT_MAX_CHARS = 500

    def __init__(
        self,
        bus: MessageBus,
        provider: LLMProvider,
        workspace: Path,
        model: str | None = None,
        max_iterations: int = 40,
        temperature: float = 0.1,
        max_tokens: int = 4096,
        reasoning_effort: str | None = None,
        provider_kwargs: dict[str, Any] | None = None,
        context_window_tokens: int = 65_536,
        brave_api_key: str | None = None,
        web_proxy: str | None = None,
        exec_config: ExecToolConfig | None = None,
        cron_service: CronService | None = None,
        restrict_to_workspace: bool = False,
        session_manager: SessionManager | None = None,
        mcp_servers: dict | None = None,
        channels_config: ChannelsConfig | None = None,
        skill_exclude: list[str] | None = None,
        skill_include: list[str] | None = None,
        app_config: Config | None = None,
        toolcall_guard: ToolcallGuard | None = None,
    ):
        from topoclaw.config.schema import ExecToolConfig
        self.bus = bus
        self.channels_config = channels_config
        self.provider = provider
        self.workspace = workspace
        self.model = model or provider.get_default_model()
        self.max_iterations = max_iterations
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.reasoning_effort = reasoning_effort
        self.provider_kwargs = provider_kwargs or {}
        self.context_window_tokens = context_window_tokens
        self.brave_api_key = brave_api_key
        self.web_proxy = web_proxy
        self.exec_config = exec_config or ExecToolConfig()
        self.cron_service = cron_service
        self.restrict_to_workspace = restrict_to_workspace
        self._app_config = app_config
        self.toolcall_guard = toolcall_guard

        self.context = ContextBuilder(
            workspace,
            skill_exclude=frozenset(skill_exclude) if skill_exclude else frozenset(),
            skill_include=frozenset(skill_include) if skill_include else None,
        )
        self.sessions = session_manager or SessionManager(workspace)
        self.tools = ToolRegistry()
        self.subagents = SubagentManager(
            provider=provider,
            workspace=workspace,
            bus=bus,
            model=self.model,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            reasoning_effort=reasoning_effort,
            brave_api_key=brave_api_key,
            web_proxy=web_proxy,
            exec_config=self.exec_config,
            restrict_to_workspace=restrict_to_workspace,
            toolcall_guard=toolcall_guard,
        )

        # Initialize interactive subagent manager if enabled
        self.task_supervisor = None
        self._spawn_browser_interactive_tool = None
        if self._app_config and self._app_config.tools.interactive.enabled:
            from topoclaw.agent.task_supervisor import TaskSupervisor

            self.task_supervisor = TaskSupervisor(
                bus=bus,
                config=self._app_config,
                workspace=workspace,
            )
            self.task_supervisor.register_event_callback(self._on_interactive_task_event)

            self._spawn_browser_interactive_tool = SpawnBrowserInteractiveTaskTool(self.task_supervisor)


        self._running = False
        self._mcp_servers = mcp_servers or {}
        self._mcp_stack: AsyncExitStack | None = None
        self._mcp_connected = False
        self._mcp_connecting = False
        self._active_tasks: dict[str, list[asyncio.Task]] = {}  # session_key -> tasks
        self._memory_update_tail: asyncio.Task | None = None
        self._processing_lock = asyncio.Lock()
        self.memory_consolidator = MemoryConsolidator(
            workspace=workspace,
            provider=provider,
            model=self.model,
            sessions=self.sessions,
            context_window_tokens=context_window_tokens,
            build_messages=self.context.build_messages,
            get_tool_definitions=self.tools.get_definitions,
        )
        self._register_default_tools()

    def _register_default_tools(self) -> None:
        """Register the default set of tools."""
        allowed_dir = self.workspace if self.restrict_to_workspace else None
        for cls in (ReadFileTool, ReadPdfTool, WriteFileTool, EditFileTool, ListDirTool):
            self.tools.register(cls(workspace=self.workspace, allowed_dir=allowed_dir))
        self.tools.register(
            ReadImageTool(
                provider=self.provider,
                # Keep read_image aligned with the current non-GUI chat model/provider.
                model=self.model,
                workspace=self.workspace,
                allowed_dir=allowed_dir,
                temperature=self.temperature,
                max_tokens=min(self.max_tokens, 4096),
            )
        )
        self.tools.register(ExecTool(
            working_dir=str(self.workspace),
            timeout=self.exec_config.timeout,
            restrict_to_workspace=self.restrict_to_workspace,
            path_append=self.exec_config.path_append,
        ))
        
        self.tools.register(ReadPdfTool(workspace=self.workspace, allowed_dir=allowed_dir))

        self.tools.register(WebSearchTool())
        self.tools.register(SerperSearchTool())
        self.tools.register(WebFetchTool(proxy=self.web_proxy))
        self.tools.register(
            ImageGenerationTool(
                workspace=self.workspace,
                app_config=self._app_config,
                token_usage_service=getattr(self.provider, "usage_service", None),
            )
        )
        # self.tools.register(SemanticScholarPaperSearchTool(proxy=self.web_proxy))
        # self.tools.register(OpenAlexPaperSearchTool(proxy=self.web_proxy))
        self.tools.register(ArxivSearchTool(proxy=self.web_proxy))
        self.tools.register(DeepxivArxivSearchTool())
        self.tools.register(DeepxivArxivTrendingTool())
        self.tools.register(DeepxivArxivReadTool())
        logger.info(
            "Registered tools semantic_scholar_search, openalex_search, arxiv_search, "
            "deepxiv_arxiv_search, deepxiv_arxiv_trending, deepxiv_arxiv_read"
        )
        # 用于调用GUI任务
        self.tools.register(GUITaskTool())
        self.tools.register(MobileGetLocationTool())
        self.tools.register(MobileLocationSkillTool(app_config=self._app_config))
        self.tools.register(ExecuteMobileDeeplinkTool())
        if self._app_config and self._app_config.tools.deeplink_lookup.enabled:
            self.tools.register(SearchDeeplinkCatalogTool())
            logger.info("Registered tool search_deeplink_catalog")
        
        self.tools.register(MessageTool(send_callback=self.bus.publish_outbound))
        self.tools.register(SpawnTool(manager=self.subagents))
        if self.cron_service:
            self.tools.register(CronTool(self.cron_service))

        # Memory工具，但是不太喜欢调用，不如每次用后台memory agent感知
        # self.tools.register(SessionMemoryCompressTool(self.memory_consolidator))
        # self.tools.register(MemoryUpdateTool(self.memory_consolidator))

        # Note: browser_automation tool is temporarily disabled in favor of spawn_browser_interactive_task
        # if self._app_config and self._app_config.tools.browser_use.enabled:
        #     from topoclaw.agent.tools.browser_automation import BrowserAutomationTool
        #
        #     self.tools.register(BrowserAutomationTool(self._app_config, workspace=self.workspace))
        #     logger.info("Registered tool browser_automation (tools.browserUse.enabled=true)")

        # Register interactive subagent tools if enabled
        if self._spawn_browser_interactive_tool:
            self.tools.register(self._spawn_browser_interactive_tool)
            self.tools.register(ResumeTaskTool(self.task_supervisor))
            self.tools.register(CancelTaskTool(self.task_supervisor))

        from topoclaw.agent.tools.stock_tools import StockAkshareTool, StockTechnicalAnalysisTool

        self.tools.register(StockAkshareTool())
        self.tools.register(StockTechnicalAnalysisTool())
        logger.info("Registered tools akshare_stock, akshare_stock_technical")


    async def _connect_mcp(self) -> None:
        """Connect to configured MCP servers (one-time, lazy)."""
        if self._mcp_connected or self._mcp_connecting or not self._mcp_servers:
            return
        self._mcp_connecting = True
        from topoclaw.agent.tools.mcp import connect_mcp_servers
        try:
            self._mcp_stack = AsyncExitStack()
            await self._mcp_stack.__aenter__()
            await connect_mcp_servers(self._mcp_servers, self.tools, self._mcp_stack)
            self._mcp_connected = True
        except Exception as e:
            logger.error("Failed to connect MCP servers (will retry next message): {}", e)
            if self._mcp_stack:
                try:
                    await self._mcp_stack.aclose()
                except Exception:
                    pass
                self._mcp_stack = None
        finally:
            self._mcp_connecting = False

    def _set_tool_context(
        self,
        channel: str,
        chat_id: str,
        message_id: str | None = None,
        *,
        session_key: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        """Update context for all tools that need routing info.

        ``session_key`` must match :meth:`SessionManager.get_or_create` (including
        ``session_key_override`` from the inbound message); defaults to
        ``channel:chat_id``.
        """
        resolved_key = session_key or f"{channel}:{chat_id}"
        for name in ("message", "spawn", "cron", "gui_task", "mobile_get_location", "mobile_location_skill", "execute_mobile_deeplink", "compress_session",
                     "spawn_browser_interactive_task", "resume_task", "cancel_task"):
            if tool := self.tools.get(name):
                if not hasattr(tool, "set_context"):
                    continue
                if name == "message":
                    tool.set_context(channel, chat_id, message_id)
                elif name == "compress_session":
                    tool.set_context(channel, chat_id, session_key=resolved_key)
                elif name == "spawn_browser_interactive_task":
                    tool.set_context(channel, chat_id, resolved_key, metadata)
                elif name in ("mobile_get_location", "mobile_location_skill"):
                    tool.set_context(channel, chat_id, metadata)
                elif name == "gui_task":
                    tool.set_context(channel, chat_id, metadata)
                elif name in ("resume_task", "cancel_task"):
                    tool.set_context(channel, chat_id, session_key=resolved_key)
                else:
                    tool.set_context(channel, chat_id)

    def _on_interactive_task_event(self, event: TaskEvent) -> None:
        """Handle supervisor task events from interactive tasks."""
        if not self.task_supervisor:
            return

        config = self._app_config.tools.interactive if self._app_config else None
        if not config:
            return

        task = self.task_supervisor.get_task(event.task_id)
        event_content = self._format_task_event_message(event, task)
        self._record_task_event(event, task)
        if task is not None:
            thread_id = str(getattr(task, "chat_id", "") or "").strip()
            channel = str(getattr(task, "channel", "") or "").strip()
            if thread_id and channel:
                asyncio.create_task(
                    self.bus.publish_outbound(
                        OutboundMessage(
                            channel=channel,
                            chat_id=thread_id,
                            content=event_content,
                            metadata={
                                "_interactive": True,
                                "_task_id": event.task_id,
                                "_task_event_type": event.event_type.value,
                            },
                        )
                    )
                )

        if event.event_type == TaskEventType.TASK_COMPLETED and task is not None:
            asyncio.create_task(self._handle_task_completion(task))
            return

        if event.event_type == TaskEventType.TASK_FAILED and task is not None:
            asyncio.create_task(self._handle_task_failure(task))
            return

        if event.event_type == TaskEventType.HUMAN_ASSISTANCE_REQUESTED and task is not None and config.notify_on_pause:
            assistance = event.payload.get("human_assistance") or {}
            question = str(assistance.get("question") or "").strip()
            content = self._format_human_assistance_assistant_message(task, question)

            runtime_meta: dict[str, Any] = {}
            if isinstance(task._runtime, dict):
                runtime_meta = dict(task._runtime.get("metadata") or {})
            thread_id = str(task.chat_id or "").strip()
            agent_id = str(runtime_meta.get("agent_id") or "default").strip() or "default"

            session = self.sessions.get_or_create(task.session_key)
            session.add_message(
                "assistant",
                content,
                metadata={
                    "is_task_assistance_prompt": True,
                    "task_id": task.task_id,
                    "task_event_type": event.event_type.value,
                },
            )
            self.sessions.save(session)
            logger.info(
                "[interactive] queue assistance push task_id={} channel={} thread_id={} agent_id={}",
                task.task_id,
                task.channel,
                thread_id or "(missing)",
                agent_id,
            )

            asyncio.create_task(
                self.bus.publish_outbound(
                    OutboundMessage(
                        channel=task.channel,
                        chat_id=thread_id or task.chat_id,
                        content=content,
                        metadata={
                            "_interactive": True,
                            "_task_id": task.task_id,
                            "_status": task.status.value,
                            "_agent_id": agent_id,
                        },
                    )
                )
            )

    @staticmethod
    def _format_human_assistance_assistant_message(task: Any, question: str) -> str:
        original_query = str(getattr(task, "original_query", "") or "").strip()
        description = str(getattr(task, "description", "") or "").strip()
        lines = [f"任务 `{task.task_id}` 请求协助：\n"]
        if original_query:
            lines.append(f"**任务描述** : {original_query}\n")
        # if description and description != original_query:
        #     lines.append(f"Task goal: {description}")
        if question:
            lines.append("**任务请求** :")
            lines.append(question)
        else:
            lines.append("Please check the current browser page and tell me when I can continue.")
        return "\n".join(lines)

    @staticmethod
    def _format_task_event_message(event: TaskEvent, task: Any | None) -> str:
        payload = dict(event.payload or {})
        assistance = dict(payload.get("human_assistance") or {})
        task_id = event.task_id
        task_type = str(payload.get("task_type") or getattr(task, "task_type", "") or "task").strip()
        description = str(payload.get("description") or getattr(task, "description", "") or "").strip()
        question = str(assistance.get("question") or "").strip()
        result = str(payload.get("result") or payload.get("error") or "").strip()

        if event.event_type == TaskEventType.TASK_STARTED:
            return (
                "[Task Event] Background task is running\n"
                f"Task ID: {task_id}\n"
                f"Type: {task_type}\n"
                f"Description: {description}"
            )
        if event.event_type == TaskEventType.HUMAN_ASSISTANCE_REQUESTED:
            body = (
                "[Task Event] Background task is waiting for user input\n"
                f"Task ID: {task_id}\n"
                f"Type: {task_type}\n"
                f"Description: {description}"
            )
            if question:
                body += f"\nQuestion: {question}"
            return body
        if event.event_type == TaskEventType.TASK_COMPLETED:
            body = (
                "[Task Event] Background task completed\n"
                f"Task ID: {task_id}\n"
                f"Type: {task_type}\n"
                f"Description: {description}"
            )
            if result:
                body += f"\nResult: {result}"
            return body
        if event.event_type == TaskEventType.TASK_FAILED:
            body = (
                "[Task Event] Background task failed\n"
                f"Task ID: {task_id}\n"
                f"Type: {task_type}\n"
                f"Description: {description}"
            )
            if result:
                body += f"\nError: {result}"
            return body
        if event.event_type == TaskEventType.TASK_CANCELLED:
            return (
                "[Task Event] Background task cancelled\n"
                f"Task ID: {task_id}\n"
                f"Type: {task_type}\n"
                f"Description: {description}"
            )
        return (
            "[Task Event] Background task status changed\n"
            f"Task ID: {task_id}\n"
            f"Type: {task_type}\n"
            f"Status: {str(payload.get('status') or getattr(task, 'status', '') or '')}\n"
            f"Description: {description}"
        )

    def _record_task_event(self, event: TaskEvent, task: Any | None) -> None:
        session = self.sessions.get_or_create(event.session_key)
        task_id = event.task_id
        session.add_message(
            "system",
            self._format_task_event_message(event, task),
            metadata={
                "is_task_event": True,
                "task_id": task_id,
                "task_event_type": event.event_type.value,
            },
        )
        self.sessions.save(session)
        logger.info(
            "Recorded task event {} for session {} task={}",
            event.event_type.value,
            event.session_key,
            task_id,
        )

    async def _handle_task_completion(self, task) -> None:
        """Handle completed task: inject result into session and trigger Agent."""
        from topoclaw.bus.events import InboundMessage

        # Build result message
        original_query = task.original_query or task.description
        result_content = (
            f"[系统通知] 后台任务已完成\n"
            f"任务ID: {task.task_id}\n"
            f"原始请求: {original_query}\n"
            f"任务描述: {task.description}\n\n"
            f"执行结果:\n{task.result or '任务已完成'}"
        )

        logger.info(
            "Task {} completed, triggering Agent processing for session {}",
            task.task_id, task.session_key
        )

        # Create inbound message to trigger Agent processing
        # _process_message will handle session saving via _save_turn
        inbound = InboundMessage(
            channel="system",
            sender_id="interactive_task",
            chat_id=f"{task.channel}:{task.chat_id}",
            content=result_content,
            metadata={
                "task_id": task.task_id,
                "task_type": task.task_type,
                "original_query": original_query,
                "is_task_result": True,
            }
        )

        # Trigger Agent processing
        try:
            outbound = await self._process_message(inbound, session_key=task.session_key)
            if outbound:
                await self.bus.publish_outbound(outbound)
        except Exception as e:
            logger.exception("Failed to process task completion for {}", task.task_id)
        finally:
            if self.task_supervisor:
                await self.task_supervisor.close_task(task.task_id)

    async def _handle_task_failure(self, task) -> None:
        """Handle failed task: inject error into session and trigger Agent."""
        from topoclaw.bus.events import InboundMessage

        # Build error message
        original_query = task.original_query or task.description
        error_content = (
            f"[系统通知] 后台任务失败\n"
            f"任务ID: {task.task_id}\n"
            f"原始请求: {original_query}\n"
            f"错误: {task.error or '未知错误'}"
        )

        logger.info(
            "Task {} failed, triggering Agent processing for session {}",
            task.task_id, task.session_key
        )

        # Create inbound message to trigger Agent processing
        # _process_message will handle session saving via _save_turn
        inbound = InboundMessage(
            channel="system",
            sender_id="interactive_task",
            chat_id=f"{task.channel}:{task.chat_id}",
            content=error_content,
            metadata={
                "task_id": task.task_id,
                "task_type": task.task_type,
                "original_query": original_query,
                "is_task_result": True,
                "is_failure": True,
            }
        )

        # Trigger Agent processing
        try:
            outbound = await self._process_message(inbound, session_key=task.session_key)
            if outbound:
                await self.bus.publish_outbound(outbound)
        except Exception:
            logger.exception("Failed to process task failure for {}", task.task_id)
        finally:
            if self.task_supervisor:
                await self.task_supervisor.close_task(task.task_id)

    @staticmethod
    def _strip_think(text: str | None) -> str | None:
        """Remove <think>…</think> blocks that some models embed in content."""
        if not text:
            return None
        return re.sub(r"<think>[\s\S]*?</think>", "", text).strip() or None

    @staticmethod
    def _tool_hint(tool_calls: list) -> str:
        """Format tool calls as concise hint, e.g. 'web_search("query")'."""
        def _fmt(tc):
            args = (tc.arguments[0] if isinstance(tc.arguments, list) else tc.arguments) or {}
            val = next(iter(args.values()), None) if isinstance(args, dict) else None
            if not isinstance(val, str):
                return tc.name
            return f'{tc.name}("{val[:40]}…")' if len(val) > 40 else f'{tc.name}("{val}")'
        return ", ".join(_fmt(tc) for tc in tool_calls)

    @staticmethod
    def _extract_generated_image_paths(result: str) -> list[str]:
        """Extract local file paths from generate_image textual output."""
        paths: list[str] = []
        for raw in (result or "").splitlines():
            line = raw.strip()
            if not line.startswith("- "):
                continue
            candidate = line[2:].strip().strip("`").strip("\"'")
            if not candidate:
                continue
            p = Path(candidate)
            if p.exists() and p.is_file():
                paths.append(str(p))
        return paths

    async def _execute_tool_with_optional_guard(
        self,
        tool_call: Any,
        on_progress: Callable[..., Awaitable[Any]] | None,
    ) -> str:
        """Run one tool; when :attr:`toolcall_guard` blocks, consult ``on_progress(..., tool_guard=True)``."""
        if self.toolcall_guard is None:
            return await self.tools.execute(tool_call.name, tool_call.arguments)

        vr = self.toolcall_guard.validate_tool_call(tool_call.name, tool_call.arguments)
        if vr.allowed:
            return await self.tools.execute(tool_call.name, tool_call.arguments)

        detail = vr.detail or vr.reason or "blocked"
        logger.warning(
            "ToolcallGuard blocked {}: {} — {}",
            tool_call.name,
            vr.reason,
            detail,
        )
        choice: Any = None
        if on_progress:
            payload = json.dumps(
                {
                    "tool_name": tool_call.name,
                    "arguments": tool_call.arguments,
                    "reason": vr.reason,
                    "detail": detail,
                },
                ensure_ascii=False,
            )
            choice = await on_progress(payload, tool_hint=False, tool_guard=True)

        if choice is None:
            choice = TOOL_GUARD_CONFIRM_TYPE_DENY
        if choice in (
            TOOL_GUARD_CONFIRM_TYPE_DENY,
            TOOL_GUARD_CONFIRM_TYPE_TIMEOUT,
            TOOL_GUARD_CONFIRM_TYPE_INVALID,
        ):
            return f"Error: tool call blocked by policy ({vr.reason}): {detail}"

        guard = self.toolcall_guard
        if choice == TOOL_GUARD_CONFIRM_TYPE_TEMPORARY_ALLOW:
            return await self.tools.execute(tool_call.name, tool_call.arguments)

        if tool_call.name in ("write_file", "edit_file"):
            args = parse_tool_arguments(tool_call.arguments)
            raw_path = args.get("path")
            if isinstance(raw_path, str):
                rp = guard.resolve_tool_path(raw_path)
                if choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_READ_ONLY:
                    guard.set_path_permission(rp, "read_only")
                elif choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_EDIT:
                    guard.set_path_permission(rp, "edit")
                elif choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_READ_ONLY:
                    guard.set_path_permission(rp.parent, "read_only")
                elif choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_EDIT:
                    guard.set_path_permission(rp.parent, "edit")
                vr2 = guard.validate_tool_call(tool_call.name, tool_call.arguments)
                if (
                    not vr2.allowed
                    and vr2.reason == "path_outside_allowed_roots"
                    and choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_EDIT
                ):
                    # ``set_path_permission`` does not widen roots; only in-root read_only was fixed.
                    guard.add_extra_allowed_root(rp)
                    vr2 = guard.validate_tool_call(tool_call.name, tool_call.arguments)
                if (
                    not vr2.allowed
                    and vr2.reason == "path_outside_allowed_roots"
                    and choice == TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_EDIT
                ):
                    guard.add_extra_allowed_root(rp.parent)
                    vr2 = guard.validate_tool_call(tool_call.name, tool_call.arguments)
                if vr2.allowed:
                    return await self.tools.execute(tool_call.name, tool_call.arguments)
                d2 = vr2.detail or vr2.reason or "still blocked"
                return (
                    f"Error: after user confirmation, tool call still blocked ({vr2.reason}): {d2}"
                )

        return (
            f"Error: tool call blocked by policy ({vr.reason}): {detail}; "
            f"confirmation {choice!r} does not apply to this tool."
        )

    async def _run_agent_loop(
        self,
        initial_messages: list[dict],
        on_progress: Callable[..., Awaitable[Any]] | None = None,
        *,
        model: str | None = None,
        reasoning_effort: str | None = None,
        provider_kwargs: dict[str, Any] | None = None,
    ) -> tuple[str | None, list[str], list[dict]]:
        """Run the agent iteration loop."""
        messages = initial_messages
        iteration = 0
        final_content = None
        tools_used: list[str] = []
        effective_model = model or self.model
        effective_reasoning_effort = reasoning_effort if reasoning_effort is not None else self.reasoning_effort
        effective_provider_kwargs = dict(provider_kwargs or self.provider_kwargs)

        while iteration < self.max_iterations:
            iteration += 1

            tool_defs = self.tools.get_definitions()

            response = await self.provider.chat_with_retry(
                messages=messages,
                tools=tool_defs,
                model=effective_model,
                temperature=self.temperature,
                max_tokens=self.max_tokens,
                reasoning_effort=effective_reasoning_effort,
                **effective_provider_kwargs,
            )

            if response.has_tool_calls:
                if on_progress:
                    thought = response.reasoning_content
                    if thought:
                        await on_progress(thought, reasoning=True)
                    await on_progress(self._tool_hint(response.tool_calls), tool_hint=True)

                tool_call_dicts = [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {
                            "name": tc.name,
                            "arguments": json.dumps(tc.arguments, ensure_ascii=False)
                        }
                    }
                    for tc in response.tool_calls
                ]
                messages = self.context.add_assistant_message(
                    messages, response.content, tool_call_dicts,
                    reasoning_content=response.reasoning_content,
                    thinking_blocks=response.thinking_blocks,
                )

                for tool_call in response.tool_calls:
                    tools_used.append(tool_call.name)
                    args_str = json.dumps(tool_call.arguments, ensure_ascii=False)
                    logger.info("Tool call: {}({})", tool_call.name, args_str)
                    result = await self._execute_tool_with_optional_guard(
                        tool_call, on_progress
                    )
                    if (
                        tool_call.name == "generate_image"
                        and isinstance(result, str)
                        and not result.startswith("Error")
                    ):
                        media_paths = self._extract_generated_image_paths(result)
                        if media_paths:
                            message_tool = self.tools.get("message")
                            if isinstance(message_tool, MessageTool):
                                sent = await message_tool.execute(
                                    content="已为你生成图片，见附件。",
                                    media=media_paths,
                                )
                                if isinstance(sent, str) and sent.startswith("Error"):
                                    logger.warning("Auto-send generated images failed: {}", sent[:300])
                                else:
                                    logger.info(
                                        "Auto-sent generated images via message tool: {} file(s)",
                                        len(media_paths),
                                    )
                    if isinstance(result, str) and result.startswith("Error"):
                        logger.warning("Tool {} returned error: {}", tool_call.name, result[:1000])

                    # Handle orchestrate_agents result for on_progress callback
                    if tool_call.name == "orchestrate_agents" and on_progress:
                        try:
                            data = json.loads(result)
                            if isinstance(data, dict) and "payload" in data and "text" in data:
                                await on_progress(json.dumps(data["payload"], ensure_ascii=False))
                                result = data["text"]  # Use text for model
                        except (json.JSONDecodeError, TypeError):
                            pass

                    messages = self.context.add_tool_result(
                        messages, tool_call.id, tool_call.name, result
                    )
            else:
                clean = self._strip_think(response.content)
                # Don't persist error responses to session history — they can
                # poison the context and cause permanent 400 loops (#1303).
                if response.finish_reason == "error":
                    logger.error("LLM returned error: {}", (clean or "")[:200])
                    final_content = clean or "Sorry, I encountered an error calling the AI model."
                    break
                messages = self.context.add_assistant_message(
                    messages, clean, reasoning_content=response.reasoning_content,
                    thinking_blocks=response.thinking_blocks,
                )
                final_content = clean
                break

        if final_content is None and iteration >= self.max_iterations:
            logger.warning("Max iterations ({}) reached", self.max_iterations)
            final_content = (
                f"I reached the maximum number of tool call iterations ({self.max_iterations}) "
                "without completing the task. You can try breaking the task into smaller steps."
            )

        # 增加记忆的更新

        return final_content, tools_used, messages

    async def run(self) -> None:
        """Run the agent loop, dispatching messages as tasks to stay responsive to /stop."""
        self._running = True
        await self._connect_mcp()
        logger.info("Agent loop started")

        while self._running:
            try:
                msg = await asyncio.wait_for(self.bus.consume_inbound(), timeout=1.0)
            except asyncio.TimeoutError:
                continue

            if msg.content.strip().lower() == "/stop":
                await self._handle_stop(msg)
            else:
                task = asyncio.create_task(self._dispatch(msg))
                self._track_session_task(msg.session_key, task)

    def _track_session_task(self, session_key: str, task: asyncio.Task) -> None:
        """Track task lifecycle under a session key for /stop cancellation."""
        self._active_tasks.setdefault(session_key, []).append(task)

        def _cleanup(t: asyncio.Task, *, key: str = session_key) -> None:
            bucket = self._active_tasks.get(key, [])
            if t in bucket:
                bucket.remove(t)
            if not bucket:
                self._active_tasks.pop(key, None)
            if t.cancelled():
                return
            try:
                exc = t.exception()
            except Exception:
                logger.exception("Task status check failed for session {}", key)
                return
            if exc is not None:
                logger.warning("Background task failed for session {}: {}", key, exc)

        task.add_done_callback(_cleanup)

    def _schedule_memory_update(
        self,
        *,
        session_key: str,
        session: Session,
        model: str | None,
        current_turn: list[dict[str, Any]],
    ) -> None:
        """Run memory_update in background while keeping global write order."""
        prev_tail = self._memory_update_tail

        async def _run_memory_update() -> None:
            if prev_tail and not prev_tail.done():
                try:
                    await prev_tail
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.warning("Previous memory_update task failed; continuing queue")
            await self.memory_consolidator.memory_update_with_turn(
                session,
                model=model,
                current_turn=current_turn,
            )

        task = asyncio.create_task(_run_memory_update())
        self._memory_update_tail = task
        self._track_session_task(session_key, task)

    async def _handle_stop(self, msg: InboundMessage) -> None:
        """Cancel all active tasks and subagents for the session."""
        tasks = self._active_tasks.pop(msg.session_key, [])
        cancelled = sum(1 for t in tasks if not t.done() and t.cancel())
        for t in tasks:
            try:
                await t
            except (asyncio.CancelledError, Exception):
                pass
        sub_cancelled = await self.subagents.cancel_by_session(msg.session_key)

        # Cancel interactive tasks as well
        interactive_cancelled = 0
        if self.task_supervisor:
            interactive_cancelled = await self.task_supervisor.cancel_by_session(msg.session_key)

        total = cancelled + sub_cancelled + interactive_cancelled
        content = f"⏹ Stopped {total} task(s)." if total else "No active task to stop."
        await self.bus.publish_outbound(OutboundMessage(
            channel=msg.channel, chat_id=msg.chat_id, content=content,
        ))

    async def _dispatch(self, msg: InboundMessage) -> None:
        """Process a message under the global lock."""
        async with self._processing_lock:
            try:
                response = await self._process_message(msg)
                if response is not None:
                    await self.bus.publish_outbound(response)
                elif msg.channel == "cli":
                    await self.bus.publish_outbound(OutboundMessage(
                        channel=msg.channel, chat_id=msg.chat_id,
                        content="", metadata=msg.metadata or {},
                    ))
            except asyncio.CancelledError:
                logger.info("Task cancelled for session {}", msg.session_key)
                raise
            except Exception:
                logger.exception("Error processing message for session {}", msg.session_key)
                await self.bus.publish_outbound(OutboundMessage(
                    channel=msg.channel, chat_id=msg.chat_id,
                    content="Sorry, I encountered an error.",
                ))

    async def close_mcp(self) -> None:
        """Close MCP connections."""
        if self._mcp_stack:
            try:
                await self._mcp_stack.aclose()
            except (RuntimeError, BaseExceptionGroup):
                pass  # MCP SDK cancel scope cleanup is noisy but harmless
            self._mcp_stack = None

    def stop(self) -> None:
        """Stop the agent loop."""
        self._running = False
        for tasks in list(self._active_tasks.values()):
            for task in tasks:
                if not task.done():
                    task.cancel()
        logger.info("Agent loop stopping")

    async def _process_message(
        self,
        msg: InboundMessage,
        session_key: str | None = None,
        on_progress: Callable[..., Awaitable[Any]] | None = None,
    ) -> OutboundMessage | None:
        """Process a single inbound message and return the response."""
        # System messages: parse origin from chat_id ("channel:chat_id")
        if msg.channel == "system":
            channel, chat_id = (msg.chat_id.split(":", 1) if ":" in msg.chat_id
                                else ("cli", msg.chat_id))
            logger.info("Processing system message from {}", msg.sender_id)
            key = session_key or f"{channel}:{chat_id}"
            session = self.sessions.get_or_create(key)
            await self.memory_consolidator.maybe_consolidate_by_tokens(session)
            self._set_tool_context(
                channel,
                chat_id,
                msg.metadata.get("message_id"),
                session_key=key,
                metadata=msg.metadata,
            )
            history = session.get_history(max_messages=0)
            task_summaries = self.task_supervisor.get_task_summaries(key) if self.task_supervisor else None
            messages = self.context.build_messages(
                history=history,
                current_message=msg.content, channel=channel, chat_id=chat_id,
                task_summaries=task_summaries,
            )
            
            final_content, _, all_msgs = await self._run_agent_loop(
                messages,
                model=self.model,
                reasoning_effort=self.reasoning_effort,
                provider_kwargs=dict(self.provider_kwargs),
            )
            self._save_turn(session, all_msgs, 1 + len(history))
            self.sessions.save(session)
            await self.memory_consolidator.maybe_consolidate_by_tokens(session)
            return OutboundMessage(channel=channel, chat_id=chat_id,
                                  content=final_content or "Background task completed.")

        preview = msg.content[:80] + "..." if len(msg.content) > 80 else msg.content
        logger.info("Processing message from {}:{}: {}", msg.channel, msg.sender_id, preview)

        key = session_key or msg.session_key
        session = self.sessions.get_or_create(key)
        # Freeze LLM routing for this turn to avoid mid-turn hot-switch drift.
        turn_model = self.model
        turn_reasoning_effort = self.reasoning_effort
        turn_provider_kwargs = dict(self.provider_kwargs)

        # Slash commands
        cmd = msg.content.strip().lower()
        if cmd == "/new":
            try:
                if not await self.memory_consolidator.archive_unconsolidated(session):
                    return OutboundMessage(
                        channel=msg.channel,
                        chat_id=msg.chat_id,
                        content="Memory archival failed, session not cleared. Please try again.",
                    )
            except Exception:
                logger.exception("/new archival failed for {}", session.key)
                return OutboundMessage(
                    channel=msg.channel,
                    chat_id=msg.chat_id,
                    content="Memory archival failed, session not cleared. Please try again.",
                )

            session.clear()
            self.sessions.save(session)
            self.sessions.invalidate(session.key)
            return OutboundMessage(channel=msg.channel, chat_id=msg.chat_id,
                                  content="New session started.")
        if cmd == "/help":
            return OutboundMessage(channel=msg.channel, chat_id=msg.chat_id,
                                  content="🐈 topoclaw commands:\n/new — Start a new conversation\n/stop — Stop the current task\n/help — Show available commands")

        await self.memory_consolidator.maybe_consolidate_by_tokens(session)

        self._set_tool_context(
            msg.channel,
            msg.chat_id,
            msg.metadata.get("message_id"),
            session_key=key,
            metadata=msg.metadata,
        )
        if message_tool := self.tools.get("message"):
            if isinstance(message_tool, MessageTool):
                message_tool.start_turn()

        history = session.get_history(max_messages=0)
        task_summaries = self.task_supervisor.get_task_summaries(key) if self.task_supervisor else None
        initial_messages = self.context.build_messages(
            history=history,
            current_message=msg.content,
            media=msg.media if msg.media else None,
            channel=msg.channel, chat_id=msg.chat_id,
            task_summaries=task_summaries,
        )

        async def _bus_progress(
            content: str,
            *,
            tool_hint: bool = False,
            tool_guard: bool = False,
            reasoning: bool = False,
        ) -> str | None:
            if tool_guard:
                return TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
            meta = dict(msg.metadata or {})
            meta["_progress"] = True
            meta["_tool_hint"] = tool_hint
            if reasoning:
                meta["_reasoning"] = True
            await self.bus.publish_outbound(OutboundMessage(
                channel=msg.channel, chat_id=msg.chat_id, content=content, metadata=meta,
            ))
            return None

        final_content, _, all_msgs = await self._run_agent_loop(
            initial_messages,
            on_progress=on_progress or _bus_progress,
            model=turn_model,
            reasoning_effort=turn_reasoning_effort,
            provider_kwargs=turn_provider_kwargs,
        )

        if final_content is None:
            final_content = "I've completed processing but have no response to give."

        self._save_turn(session, all_msgs, 1 + len(history))
        self.sessions.save(session)
        # 后台更新长期记忆和事件记忆（不阻塞本轮回复）
        current_turn = deepcopy(session.messages[-2:]) if len(session.messages) >= 2 else deepcopy(session.messages)
        self._schedule_memory_update(
            session_key=key,
            session=session,
            model=turn_model,
            current_turn=current_turn,
        )
        # 检测是否超出token窗口，进行压缩
        await self.memory_consolidator.maybe_consolidate_by_tokens(session)

        if (mt := self.tools.get("message")) and isinstance(mt, MessageTool) and mt._sent_in_turn:
            return None

        preview = final_content[:120] + "..." if len(final_content) > 120 else final_content
        logger.info("Response to {}:{}: {}", msg.channel, msg.sender_id, preview)
        return OutboundMessage(
            channel=msg.channel, chat_id=msg.chat_id, content=final_content,
            metadata=msg.metadata or {},
        )

    def _save_turn(self, session: Session, messages: list[dict], skip: int) -> None:
        """Save new-turn messages into session, truncating large tool results."""
        from datetime import datetime
        for m in messages[skip:]:
            entry = dict(m)
            role, content = entry.get("role"), entry.get("content")
            if role == "assistant" and not content and not entry.get("tool_calls"):
                continue  # skip empty assistant messages — they poison session context
            if role == "tool" and isinstance(content, str) and len(content) > self._TOOL_RESULT_MAX_CHARS:
                entry["content"] = content[:self._TOOL_RESULT_MAX_CHARS] + "\n... (truncated)"
            elif role == "user":
                if isinstance(content, str) and content.startswith(ContextBuilder._RUNTIME_CONTEXT_TAG):
                    # Strip the runtime-context prefix, keep only the user text.
                    parts = content.split("\n\n", 1)
                    if len(parts) > 1 and parts[1].strip():
                        entry["content"] = parts[1]
                    else:
                        continue
                if isinstance(content, list):
                    filtered = []
                    for c in content:
                        if c.get("type") == "text" and isinstance(c.get("text"), str) and c["text"].startswith(ContextBuilder._RUNTIME_CONTEXT_TAG):
                            continue  # Strip runtime context from multimodal messages
                        if (c.get("type") == "image_url"
                                and c.get("image_url", {}).get("url", "").startswith("data:image/")):
                            filtered.append({"type": "text", "text": "[image]"})
                        else:
                            filtered.append(c)
                    if not filtered:
                        continue
                    entry["content"] = filtered
            entry.setdefault("timestamp", datetime.now().isoformat())
            if merge_followup_user_into_compressed_tail(session.messages, entry):
                continue
            session.messages.append(entry)
        session.updated_at = datetime.now()

    async def process_direct(
        self,
        content: str,
        session_key: str = "cli:direct",
        channel: str = "cli",
        chat_id: str = "direct",
        media: list[str] | None = None,
        metadata: dict[str, Any] | None = None,
        on_progress: Callable[..., Awaitable[Any]] | None = None,
    ) -> str:
        """Process a message directly (for CLI or cron usage)."""
        await self._connect_mcp()
        msg = InboundMessage(
            channel=channel,
            sender_id="user",
            chat_id=chat_id,
            content=content,
            media=media or [],
            metadata=metadata or {},
        )
        response = await self._process_message(msg, session_key=session_key, on_progress=on_progress)
        return response.content if response else ""
