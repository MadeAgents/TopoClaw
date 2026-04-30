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

"""Base class for GUI agents."""

import asyncio
import json
import re
from abc import ABC, abstractmethod
from pathlib import Path
from typing import TYPE_CHECKING, Any

from loguru import logger

from topoclaw.agent.memory import MemoryStore
from topoclaw.agent.tools.registry import ToolRegistry
from topoclaw.providers.base import LLMProvider
from topoclaw.session.compress_marker import merge_followup_user_into_compressed_tail
from topoclaw.session.manager import SessionManager

if TYPE_CHECKING:
    from topoclaw.agent.gui.registry import GUIModelProfile


class GUIAgentBase(ABC):
    """Base class for GUI automation agents (mobile and desktop)."""

    def __init__(
        self,
        provider: LLMProvider,
        workspace: Path,
        model: str | None = None,
        temperature: float = 0.3,
        max_tokens: int = 4096,
        reasoning_effort: str | None = None,
        provider_kwargs: dict[str, Any] | None = None,
        memory_store: MemoryStore | None = None,
        session_manager: SessionManager | None = None,
        max_iterations: int = 20,
        gui_model_profile: "GUIModelProfile | None" = None,
    ):
        """Initialize GUI Agent.

        Args:
            provider: LLM provider instance
            workspace: Workspace path
            model: Model name (defaults to provider's default)
            temperature: LLM temperature
            max_tokens: Maximum tokens per response
            reasoning_effort: Reasoning effort level
            provider_kwargs: Extra provider kwargs
            memory_store: Memory store instance (shared with main agent)
            session_manager: Session manager instance (shared with main agent)
            max_iterations: Maximum agent loop iterations
        """
        self.provider = provider
        self.workspace = workspace
        self.model = model or provider.get_default_model()
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.reasoning_effort = reasoning_effort
        self.provider_kwargs = provider_kwargs or {}
        self.max_iterations = max_iterations
        self._gui_model_profile_override = gui_model_profile

        # Shared components
        self.memory = memory_store or MemoryStore(workspace)
        self.sessions = session_manager or SessionManager(workspace)

        # Agent-specific components (built by subclasses)
        self.tools: ToolRegistry | None = None
        self._system_prompt: str | None = None

    def _resolve_gui_profile(self) -> "GUIModelProfile":
        from topoclaw.agent.gui.registry import GUIModelProfile, resolve_gui_model_profile

        variant = "mobile" if "mobile" in self.__class__.__name__.lower() else "computer"
        return resolve_gui_model_profile(
            self.model,
            variant=variant,  # type: ignore[arg-type]
            workspace=self.workspace,
            explicit=self._gui_model_profile_override,
        )

    @abstractmethod
    def _build_tools(self) -> ToolRegistry:
        """Build the tool registry for this GUI agent. Must be implemented by subclasses."""
        raise NotImplementedError

    @abstractmethod
    def _build_system_prompt(self, task: str) -> str:
        """Build the system prompt for this GUI agent. Must be implemented by subclasses."""
        raise NotImplementedError

    def _get_system_prompt(self, task: str) -> str:
        """Get system prompt, building it if necessary."""
        # Rebuild prompt for each task (may contain task-specific variables)
        return self._build_system_prompt(task)

    async def execute_step(
        self,
        session_id: str,
        screenshot_path: str,
        query: str,
        execution_history: list[str] | None = None,
        app_name: str | None = None,
        user_response: str | None = None,
    ) -> dict[str, Any]:
        """Execute a single step of GUI automation task.

        Args:
            session_id: Session identifier
            screenshot_path: Path to screenshot image file
            query: User query/task description
            execution_history: List of previous execution results
            app_name: Current app name
            user_response: User response to call_user action

        Returns:
            Dictionary with response_text, action, thought, reasoning, etc.
        """
        logger.info("GUI Agent executing step for session: {}", session_id)

        # Build tools if not already built
        if self.tools is None:
            self.tools = self._build_tools()

        # Get or create session
        key = f"gui:{session_id}"
        session = self.sessions.get_or_create(key)

        # Update current task if query provided
        if query:
            if not hasattr(self, "current_task"):
                self.current_task = query
            else:
                self.current_task = query

        # Update app name if provided
        if app_name:
            if hasattr(self, "current_app"):
                self.current_app = app_name

        # Build system prompt
        system_prompt = self._get_system_prompt(query or self.current_task)
        memory_context = self.memory.get_memory_context()

        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
        ]

        # Add memory context if available
        if memory_context:
            messages.append({
                "role": "system",
                "content": f"## Long-term Memory\n{memory_context}",
            })

        # Add session history
        history = session.get_history(max_messages=50)
        messages.extend(history)

        # Build user message with screenshot and context
        # Get previous state from agent instance
        previous_screenshot = getattr(self, "previous_screenshot_path", None)
        previous_action = getattr(self, "previous_action", None)
        history_trajectory = getattr(self, "history_trajectory", [])
        task_progress_summary = getattr(self, "task_progress_summary", "")
        
        user_content = self._build_user_message(
            task=query or self.current_task,
            execution_history=execution_history or [],
            screenshot_path=screenshot_path,
            previous_screenshot_path=previous_screenshot,
            previous_action=previous_action,
            history_trajectory=history_trajectory,
            task_progress_summary=task_progress_summary,
        )
        messages.append({"role": "user", "content": user_content})

        # Call LLM
        response = await self.provider.chat(
            messages=messages,
            tools=None,  # No tools, using action format instead
            model=self.model,
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            reasoning_effort=self.reasoning_effort,
            **self.provider_kwargs,
        )

        response_text = response.content or ""
        logger.debug("LLM response: {}", response_text[:200])

        # Determine agent type (mobile or computer)
        agent_type = "mobile" if "mobile" in self.__class__.__name__.lower() else "computer"
        
        # Parse thought, reasoning, and action from response
        from topoclaw.agent.gui.utils.base_parser import BaseParser
        parsed = BaseParser.parse(response_text, agent_type=agent_type)
        
        summary = parsed.summary
        thought = parsed.thought
        action_intent = parsed.action_intent
        action_str = parsed.raw_action

        # Parse action (this also updates state if implemented in subclass)
        action_result = await self._parse_action(response_text, 1)

        # Update state for next iteration
        # Save current screenshot as previous for next step
        if screenshot_path and Path(screenshot_path).exists():
            self.previous_screenshot_path = screenshot_path
        self.previous_action = action_str
        self.previous_thought = thought
        
        # Update history trajectory
        if not hasattr(self, "history_trajectory"):
            self.history_trajectory = []
        self.history_trajectory.append({
            "thought": thought,
            "action": action_str,
        })
        # Note: history_trajectory is not truncated to preserve full history

        # Update task progress summary from thought
        # Extract "当前完成了什么" part from thought (format: "用户要干什么；当前完成了什么；接下来应该做什么")
        if thought and "；" in thought:
            parts = thought.split("；")
            if len(parts) >= 2:
                self.task_progress_summary = parts[1].strip()
        elif thought:
            # Fallback: use full thought if format doesn't match
            self.task_progress_summary = thought

        # Add assistant response to session
        messages.append({
            "role": "assistant",
            "content": response_text,
        })
        messages.append({
            "role": "user",
            "content": f"Action result: {action_result}",
        })

        # Save session
        self._save_session(session, messages, len(history))

        return {
            "response_text": response_text,
            "action": action_result,
            "summary": summary,
            "thought": thought,
            "action_intent": action_intent,
            "status": "complete" if "complete" in response_text.lower() else "continue",
        }

    async def execute_task(
        self,
        task: str,
        session_key: str | None = None,
        on_progress: Any | None = None,
    ) -> str:
        """Execute a GUI automation task.

        Args:
            task: Task description
            session_key: Session key for this task
            on_progress: Optional progress callback

        Returns:
            Final result string
        """
        logger.info("GUI Agent executing task: {}", task)

        # Build tools if not already built
        if self.tools is None:
            self.tools = self._build_tools()

        # Get or create session
        key = session_key or f"{self.__class__.__name__.lower()}:{id(task)}"
        session = self.sessions.get_or_create(key)

        # Build initial messages with system prompt
        system_prompt = self._get_system_prompt(task)
        memory_context = self.memory.get_memory_context()

        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
        ]

        # Add memory context if available
        if memory_context:
            messages.append({
                "role": "system",
                "content": f"## Long-term Memory\n{memory_context}",
            })

        # Add session history
        history = session.get_history(max_messages=50)
        messages.extend(history)

        # Run agent loop with automatic screenshots
        final_result: str | None = None
        iteration = 0
        execution_history: list[str] = []

        while iteration < self.max_iterations:
            iteration += 1

            # Screenshot should be provided externally via execute_step method
            # For execute_task, screenshot_path is None (this method is deprecated)
            screenshot_path = None
            logger.warning(
                "execute_task() method does not support automatic screenshots. "
                "Please use execute_step() method with screenshot_path parameter instead."
            )

            # Build user message with screenshot and context
            previous_screenshot = getattr(self, "previous_screenshot_path", None)
            previous_action = getattr(self, "previous_action", None)
            history_trajectory = getattr(self, "history_trajectory", [])
            task_progress_summary = getattr(self, "task_progress_summary", "")
            
            user_content = self._build_user_message(
                task=task,
                execution_history=execution_history,
                screenshot_path=screenshot_path,
                previous_screenshot_path=previous_screenshot,
                previous_action=previous_action,
                history_trajectory=history_trajectory,
                task_progress_summary=task_progress_summary,
            )
            messages.append({"role": "user", "content": user_content})

            # Call LLM (no tools, just text response with action format)
            response = await self.provider.chat(
                messages=messages,
                tools=None,  # No tools, using action format instead
                model=self.model,
                temperature=self.temperature,
                max_tokens=self.max_tokens,
                reasoning_effort=self.reasoning_effort,
            )

            response_text = response.content or ""
            logger.debug("LLM response: {}", response_text[:200])

            # Parse action from response
            action_result = await self._parse_action(response_text, iteration)
            
            # Extract thought and action for state tracking
            thought_match = re.search(r"thought:\s*([^#]+)", response_text)
            thought = thought_match.group(1).strip() if thought_match else ""
            
            action_match = re.search(r"###action:\s*([a-z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
            if not action_match:
                action_match = re.search(r"action:\s*([a-z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
            action_str = action_match.group(1).strip() if action_match else ""
            
            # Update state for next iteration
            if screenshot_path and Path(screenshot_path).exists():
                self.previous_screenshot_path = screenshot_path
            self.previous_action = action_str
            self.previous_thought = thought
            
            # Update history trajectory
            if not hasattr(self, "history_trajectory"):
                self.history_trajectory = []
            self.history_trajectory.append({
                "thought": thought,
                "action": action_str,
            })
            # Note: history_trajectory is not truncated to preserve full history
            
            # Update task progress summary from thought
            if thought and "；" in thought:
                parts = thought.split("；")
                if len(parts) >= 2:
                    self.task_progress_summary = parts[1].strip()
            elif thought:
                self.task_progress_summary = thought
            
            # Add assistant response and action result to messages
            messages.append({
                "role": "assistant",
                "content": response_text,
            })
            messages.append({
                "role": "user",
                "content": f"Action result: {action_result}",
            })

            # Update execution history
            execution_history.append(action_result)

            # Check if task is complete
            if self._is_task_complete(response_text, action_result):
                final_result = self._extract_final_result(response_text, action_result)
                break

        if final_result is None:
            final_result = "Task completed but no final response was generated."

        # Save session
        self._save_session(session, messages, len(history))

        logger.info("GUI Agent task completed")
        return final_result

    @abstractmethod
    def _build_user_message(
        self,
        task: str,
        execution_history: list[str],
        screenshot_path: str | None,
    ) -> str | list[dict[str, Any]]:
        """Build user message with screenshot. Must be implemented by subclasses."""
        raise NotImplementedError

    @abstractmethod
    async def _parse_action(self, response_text: str, iteration: int) -> str:
        """Parse action from response text.
        
        Note: In frontend-backend architecture, actual execution happens on frontend.
        This method only parses and returns the action string.
        Must be implemented by subclasses.
        """
        raise NotImplementedError

    @abstractmethod
    def _is_task_complete(self, response_text: str, action_result: str) -> bool:
        """Check if task is complete. Must be implemented by subclasses."""
        raise NotImplementedError

    @abstractmethod
    def _extract_final_result(self, response_text: str, action_result: str) -> str:
        """Extract final result from response. Must be implemented by subclasses."""
        raise NotImplementedError

    def _save_session(self, session: Any, messages: list[dict], skip: int) -> None:
        """Save messages to session."""
        from datetime import datetime

        for m in messages[skip:]:
            entry = dict(m)
            entry.setdefault("timestamp", datetime.now().isoformat())
            if merge_followup_user_into_compressed_tail(session.messages, entry):
                continue
            session.messages.append(entry)
        session.updated_at = datetime.now()
        self.sessions.save(session)

    @staticmethod
    def _strip_think(text: str | None) -> str | None:
        """Remove thinking blocks from content."""
        if not text:
            return None
        import re
        return re.sub(r"<think>[\s\S]*?</think>", "", text).strip() or None
