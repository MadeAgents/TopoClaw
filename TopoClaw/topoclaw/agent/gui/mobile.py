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

"""Mobile GUI Agent for touchscreen automation."""

import hashlib
import time
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.agent.gui.base import GUIAgentBase
from topoclaw.agent.gui.config.mobile_config import MobileGUIConfig
from topoclaw.agent.gui.prompt_loader import PromptLoader
from topoclaw.agent.gui.state_models import GUIAgentState
from topoclaw.agent.gui.utils.image_renderer import encode_image, render_action_on_image
from topoclaw.agent.gui.utils.step_logger import StepLogger
from topoclaw.agent.gui.workspace_layout import gui_temp_rendered
from topoclaw.agent.gui.utils.coordinate_mapper import CoordinateMapper
from topoclaw.agent.gui.registry import (
    GUIModelProfile,
    effective_native_model,
    needs_relative_coordinate_mapping,
)
from topoclaw.agent.gui.utils.base_parser import BaseParser
from topoclaw.agent.memory import MemoryStore
from topoclaw.agent.state_manager import AgentStateManager, register_state_model
from topoclaw.agent.tools.registry import ToolRegistry
from topoclaw.providers.base import LLMProvider
from topoclaw.session.manager import Session, SessionManager

try:
    from PIL import Image
except ImportError:
    Image = None

class MobileGUIAgent(GUIAgentBase):
    """Mobile GUI Agent for touchscreen device automation."""

    def __init__(
        self,
        provider: LLMProvider,
        workspace: Path,
        model: str | None = None,
        temperature: float | None = None,
        max_tokens: int = 4096,
        reasoning_effort: str | None = None,
        provider_kwargs: dict[str, Any] | None = None,
        memory_store: MemoryStore | None = None,
        session_manager: SessionManager | None = None,
        max_iterations: int | None = None,
        models_needing_mapping: list[str] | None = None,
        enable_step_logging: bool = True,
        gui_model_profile: GUIModelProfile | None = None,
    ):
        """Initialize Mobile GUI Agent.
        
        Args:
            enable_step_logging: Whether to enable step logging (default: True)
        """
        # Load configuration
        self.config = MobileGUIConfig()
        
        # Use config defaults if not provided
        temperature = temperature or self.config.default_temperature
        max_iterations = max_iterations or self.config.default_max_iterations
        
        super().__init__(
            provider=provider,
            workspace=workspace,
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
            reasoning_effort=reasoning_effort,
            provider_kwargs=provider_kwargs,
            memory_store=memory_store,
            session_manager=session_manager,
            max_iterations=max_iterations,
            gui_model_profile=gui_model_profile,
        )
        
        # Initialize components from config
        self.prompt_loader = self.config.create_prompt_loader(workspace)
        self.current_app = "未知应用"
        self.user_qa_section = ""
        self.current_task = ""  # Initialize current_task
        
        # State manager for session-based state isolation
        self.state_manager = AgentStateManager(self.sessions)
        
        # Register GUI state model
        register_state_model("gui", GUIAgentState)
        
        # Coordinate mapper setup
        self.models_needing_mapping = models_needing_mapping or ["seed", "qwen3"]
        model_lower = (self.model or "").lower()
        self._needs_mapping = any(m.lower() in model_lower for m in self.models_needing_mapping)
        self._effective_coordinate_relative = self._needs_mapping
        self._last_screen_width = None
        self._last_screen_height = None
        
        # Step logger for recording model inputs and outputs
        self.step_logger = StepLogger(workspace, enabled=enable_step_logging)
        self._step_counter: dict[str, int] = {}  # Track step number per query

    def _resolve_session_key(
        self,
        session_id: str,
        query: str | None = None,
        parent_session_key: str | None = None,
    ) -> str:
        """Resolve session key with task_id validation.
        
        Args:
            session_id: Session ID from frontend or task_id
            query: Query text (used to generate task_id if needed)
            parent_session_key: Parent session key (for main agent calls)
        
        Returns:
            Resolved session key in format: gui:{identifier}
        """
        if parent_session_key:
            # Main agent call: inherit parent session
            # session_id should be task_id
            if ":" in session_id:
                # Already in format: device:task
                return f"gui:{parent_session_key}:{session_id}"
            else:
                # Just task_id
                return f"gui:{parent_session_key}:{session_id}"
        else:
            # Frontend direct call: must include device_id and task_id
            if ":" in session_id:
                # Format: device_id:task_id
                return f"gui:{session_id}"
            else:
                # Only device_id, generate task_id
                if query:
                    task_id = hashlib.md5(query.encode()).hexdigest()[:8]
                    return f"gui:{session_id}:{task_id}"
                else:
                    # No query, use timestamp
                    task_id = str(int(time.time()))
                    return f"gui:{session_id}:{task_id}"
    
    def _extract_execution_history(self, session: Session) -> list[str]:
        """Extract execution history from session messages.
        
        Args:
            session: Session object
        
        Returns:
            List of execution history strings (last 20 items)
        """
        history = []
        for msg in session.messages:
            content = str(msg.get("content", ""))
            if msg.get("role") == "tool" and "Action result" in content:
                history.append(content)
            elif msg.get("role") == "user" and "execution_history" in msg.get("metadata", {}):
                history.extend(msg.get("metadata", {}).get("execution_history", []))
        return history[-20:]  # Keep last 20 items

    def _build_tools(self) -> ToolRegistry:
        """Build mobile-specific tools (no screenshot tool, it's automatic)."""
        tools = ToolRegistry()
        # Tools are not used in the new format, actions are parsed from text
        logger.debug("Mobile GUI Agent initialized (action-based, no tools)")
        return tools

    def _build_system_prompt(
        self, 
        task: str, 
        state: GUIAgentState | None = None
    ) -> str:
        """Build system prompt using Jinja2 template.
        
        Args:
            task: User task/query
            state: GUI agent state model (optional)
        """
        long_term_memory = self.memory.get_memory_context() or ""
        # Extract long-term memory text (remove markdown headers)
        if "## Long-term Memory" in long_term_memory:
            long_term_memory = long_term_memory.split("## Long-term Memory")[-1].strip()

        # Extract previous state from state model
        previous_plan = None
        previous_summary = None
        previous_thought = None
        previous_action_intent = None
        previous_action = None
        previous_note = None
        todo_list = None
        notebook = None
        
        if state:
            # Get previous state (from last step)
            previous_plan = state.todo_list if state.todo_list else None
            previous_summary = state.task_progress_summary if state.task_progress_summary else None
            previous_thought = state.previous_thought if state.previous_thought else None
            previous_action_intent = state.previous_action_intent if state.previous_action_intent else None
            previous_action = state.previous_action if state.previous_action else None
            # Note: previous_note is the last note appended, we can get it from notebook if needed
            # For now, we'll use the full notebook as previous_note context
            previous_note = state.notebook if state.notebook else None
            
            # Get current state
            todo_list = state.todo_list if state.todo_list else None
            notebook = state.notebook if state.notebook else None
            history_trajectory = state.history_trajectory if state.history_trajectory else None
        else:
            history_trajectory = None

        # Use state values instead of instance variables for thread safety
        current_app = state.current_app if state and state.current_app else "未知应用"
        
        return self.prompt_loader.render_mobile_prompt(
            query_text=task,
            app_name=current_app,
            user_qa_section=self.user_qa_section,  # This is typically static, but could be moved to state if needed
            long_term_memory=long_term_memory,
            previous_plan=previous_plan,
            previous_summary=previous_summary,
            previous_thought=previous_thought,
            previous_action_intent=previous_action_intent,
            previous_action=previous_action,
            previous_note=previous_note,
            todo_list=todo_list,
            notebook=notebook,
            history_trajectory=history_trajectory,
        )

    def _build_user_message(
        self,
        task: str,
        execution_history: list[str],
        screenshot_path: str | None,
        previous_screenshot_path: str | None = None,
        previous_action: str | None = None,
        previous_action_intent: str | None = None,
        history_trajectory: list[dict[str, str]] | None = None,
        task_progress_summary: str | None = None,
        todo_list: list[str] | None = None,
        notebook: str | None = None,
    ) -> list[dict[str, Any]]:
        """Build user message with screenshots.
        
        Note: Context information (previous state, history, plan, notebook) is provided
        in System Prompt to avoid duplication. User Message focuses on current step data.
        
        Args:
            task: User query/task
            execution_history: List of execution results
            screenshot_path: Path to current screenshot
            previous_screenshot_path: Previous screenshot path (will be rendered with action)
            previous_action: Previous action string (only used for rendering on screenshot)
            previous_action_intent: Not used (already in System Prompt)
            history_trajectory: Not used (already in System Prompt)
            task_progress_summary: Not used (already in System Prompt as previous_summary)
            todo_list: Not used (already in System Prompt)
            notebook: Not used (already in System Prompt)
        """
        content = []
        text_parts = []

        # Note: User task is already in System Prompt (query_text), so we don't duplicate it here.
        # User Message focuses on current step visual data: screenshots.

        # 1. Add previous screenshot with action rendering (if enabled and available)
        rendered_path = None
        if (
            self.config.include_previous_screenshot_in_user_message
            and previous_screenshot_path
            and previous_action
            and Path(previous_screenshot_path).exists()
        ):
            try:
                # Render action on previous screenshot
                temp_dir = gui_temp_rendered(self.workspace)
                temp_dir.mkdir(parents=True, exist_ok=True)
                rendered_path = str(temp_dir / f"prev_{Path(previous_screenshot_path).name}")
                rendered_path = render_action_on_image(previous_screenshot_path, previous_action, rendered_path)
                
                base64_image = encode_image(rendered_path)
                content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"},
                })
                text_parts.append("上一步截图（已标注动作）：")
            except Exception as e:
                logger.error("Failed to render previous screenshot: {}", e)
                rendered_path = None
        
        # Clean up rendered image after encoding (to save disk space)
        if rendered_path and Path(rendered_path).exists():
            try:
                Path(rendered_path).unlink()
            except Exception as e:
                logger.debug("Failed to cleanup rendered image: {}", e)

        # 3. Add current screenshot
        screen_width = None
        screen_height = None
        if screenshot_path and Path(screenshot_path).exists():
            try:
                base64_image = encode_image(screenshot_path)
                content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{base64_image}"},
                })
                text_parts.append("当前截图：")
                
                # Get screen dimensions from screenshot (for coordinate mapping)
                # This avoids re-reading the screenshot in adapter
                try:
                    from PIL import Image
                    img = Image.open(screenshot_path)
                    screen_width, screen_height = img.width, img.height
                except Exception as e:
                    logger.debug("Failed to get screen dimensions from screenshot: {}", e)
            except Exception as e:
                logger.error("Failed to encode screenshot: {}", e)
        
        # Store screen dimensions for coordinate mapping (used by adapter)
        if screen_width and screen_height:
            self._last_screen_width = screen_width
            self._last_screen_height = screen_height

        # Note: Previous action/intent, history trajectory, todo_list, notebook, and user task
        # are already included in System Prompt, so we don't duplicate them here.
        # User Message focuses on current step: screenshots only.

        # Add text content (only screenshot labels, if any)
        if text_parts:
            content.append({
                "type": "text",
                "text": "\n".join(text_parts),
            })
        elif not content:
            # Fallback: if no screenshots and no text, provide minimal text
            # (though this should rarely happen in normal flow)
            content.append({"type": "text", "text": "请分析当前截图。"})

        return content

    async def _parse_action(
        self, 
        response_text: str, 
        iteration: int,
        session_key: str | None = None,
        state: GUIAgentState | None = None,
    ) -> str:
        """Parse action from LLM response text.
        
        Note: In frontend-backend architecture, actual execution happens on frontend.
        This method only parses and returns the action string.
        """
        parsed = BaseParser.parse(
            response_text,
            agent_type=self.config.get_adapter_agent_type()
        )
        
        raw_action = parsed.raw_action
        
        # Apply coordinate mapping if needed
        mapped_action = raw_action
        if getattr(self, "_effective_coordinate_relative", self._needs_mapping) and raw_action:
            mapped_action = CoordinateMapper.to_absolute(
                raw_action,
                self._last_screen_width,
                self._last_screen_height,
                coord_range=1000
            )
            parsed.raw_action = raw_action
            parsed.action = mapped_action
        else:
            parsed.action = raw_action
            
        thought = parsed.thought
        action_intent = parsed.action_intent
        action_str = parsed.action
        summary = parsed.summary
        
        if not action_str:
            logger.warning("No action found in response: {}", response_text[:200])
            return "No action found in response"
        
        action_str_lower = action_str.lower()
        logger.info("Parsed summary: {} | thought: {} | intent: {} | action: {}", summary, thought, action_intent, action_str)

        # Update state if state model provided
        if state:
            # Update history trajectory
            state.history_trajectory.append({
                "thought": thought,
                "action_intent": action_intent,
                "action": action_str,
            })
            
            # Update task progress summary from parsed summary
            if summary:
                state.task_progress_summary = summary
            
            # Handle state updates for specific actions
            if action_str_lower.startswith("open["):
                # Extract app name and update current_app
                try:
                    app_name = action_str.split("[", 1)[1].rstrip("]")
                    state.current_app = app_name
                    # Note: Don't update self.current_app to avoid concurrency issues
                    logger.info("Updated current_app to: {}", app_name)
                except Exception as e:
                    logger.warning("Failed to parse app name from action: {}", e)

        # Return original action string for frontend execution
        # API will parse and format it appropriately
        return action_str

    def _is_task_complete(self, response_text: str, action_result: str) -> bool:
        """Check if task is complete using config."""
        # Also check response_text for backward compatibility
        if "complete" in response_text.lower() or "任务已完成" in response_text:
            return True
        if action_result == "Task completed":
            return True
        return self.config.is_complete_action(action_result)

    def _extract_final_result(self, response_text: str, action_result: str) -> str:
        """Extract final result from response.
        
        Note: This method is only used by execute_task(), which is not used
        in the frontend-backend architecture. In the current architecture,
        frontend handles task completion and result extraction.
        """
        # Minimal implementation for abstract method compatibility
        # In frontend-backend architecture, this is not used
        return action_result

    async def execute_step(
        self,
        session_id: str,
        screenshot_path: str,
        query: str,
        execution_history: list[str] | None = None,
        app_name: str | None = None,
        user_response: str | None = None,
        parent_session_key: str | None = None,
    ) -> dict[str, Any]:
        """Execute a single step of GUI automation task with enhanced context.
        
        Overrides base class to include previous screenshot, action, and progress tracking.
        """
        logger.info("Mobile GUI Agent executing step for session: {}", session_id)

        # Build tools if not already built
        if self.tools is None:
            self.tools = self._build_tools()

        # Resolve session key (with task_id validation)
        session_key = self._resolve_session_key(session_id, query, parent_session_key)
        
        # Get or create session (with lock protection)
        session = await self.sessions.get_or_create_locked(session_key)
        
        # Get state as domain model (thread-safe)
        state: GUIAgentState = await self.state_manager.get_state_model(session_key, "gui")

        # Update current task if query provided
        # Note: Only update state, not instance variables (to avoid concurrency issues)
        if query:
            state.current_task = query

        # Update app name if provided
        if app_name:
            state.current_app = app_name

        # Get state values from domain model
        previous_screenshot_path = state.previous_screenshot_path
        previous_action = state.previous_action
        previous_action_intent = getattr(state, "previous_action_intent", None)
        history_trajectory = state.history_trajectory
        task_progress_summary = state.task_progress_summary
        
        # Extract execution history from session if not provided
        if execution_history is None:
            execution_history = self._extract_execution_history(session)
        else:
            # Merge with state execution_history
            execution_history = (state.execution_history + execution_history)[-20:]

        # Build system prompt with state context
        system_prompt = self._build_system_prompt(
            query or self.current_task or (state.current_task if state else ""),
            state=state
        )
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

        # Note: Do NOT add session history here for GUI agent
        # History information is already included in System Prompt (history_trajectory, previous state, etc.)
        # Each step should be independent, not accumulating messages
        # history = session.get_history(max_messages=50)
        # messages.extend(history)

        # Build user message with screenshots
        # Note: Context information (previous state, history, plan, notebook) is in System Prompt
        # User Message only contains current step data: screenshots and task description
        user_content = self._build_user_message(
            task=query or (state.current_task if state else ""),
            execution_history=execution_history,
            screenshot_path=screenshot_path,
            previous_screenshot_path=previous_screenshot_path,
            previous_action=previous_action,  # Only used for rendering action on previous screenshot
            # Removed parameters (already in System Prompt):
            # previous_action_intent, history_trajectory, task_progress_summary, todo_list, notebook
        )
        messages.append({"role": "user", "content": user_content})

        prof = self._resolve_gui_profile()
        self._effective_coordinate_relative = needs_relative_coordinate_mapping(prof)

        used_native = False
        native_result = None

        if not used_native:
            response = await self.provider.chat(
                messages=messages,
                tools=None,
                model=self.model,
                temperature=self.temperature,
                max_tokens=self.max_tokens,
                reasoning_effort=self.reasoning_effort,
            )
            response_text = response.content or ""
        else:
            response_text = native_result.response_text if native_result else ""
            response = None

        logger.debug("LLM response: {}", (response_text or "")[:200])
        
        # Log this step (input and output)
        current_query = query or (state.current_task if state else "") or "unknown_query"
        # Get or increment step counter for this query
        if current_query not in self._step_counter:
            self._step_counter[current_query] = 0
        self._step_counter[current_query] += 1
        step_num = self._step_counter[current_query]
        
        # Prepare response dict for logging
        response_dict = {
            "content": response_text,
            "model": (
                getattr(response, "model", self.model)
                if response is not None
                else effective_native_model(prof, self.model)
            ),
            "usage": getattr(response, "usage", {}) if response is not None else (native_result.usage if native_result else {}),
            "finish_reason": getattr(response, "finish_reason", "") if response is not None else "native_gui",
        }
        
        # Set screen dimensions for coordinate mapping if needed
        # This avoids re-reading the screenshot in mapper if already set
        if hasattr(self, '_last_screen_width') and hasattr(self, '_last_screen_height'):
            pass # Already set in _build_user_message

        # Parse thought, reasoning, and action from response
        parsed = BaseParser.parse(
            response_text,
            agent_type=self.config.get_adapter_agent_type()
        )
        thought = parsed.thought
        action_intent = parsed.action_intent
        summary = parsed.summary

        # Apply coordinate mapping to the main action logic
        raw_action = parsed.raw_action
        action_str = raw_action
        if used_native and native_result:
            action_str = native_result.action_str
            raw_action = native_result.action_str
        elif self._effective_coordinate_relative and raw_action:
            action_str = CoordinateMapper.to_absolute(
                raw_action,
                self._last_screen_width,
                self._last_screen_height,
                coord_range=1000
            )
        
        # Update plan and notebook if present
        if state:
            try:
                if parsed.plan_update:
                    state.todo_list = parsed.plan_update
                    logger.info("Updated plan: {}", state.todo_list)
                
                if parsed.note_append:
                    if state.notebook:
                        state.notebook += "\n" + parsed.note_append
                    else:
                        state.notebook = parsed.note_append
                    logger.info("Appended note: {}", parsed.note_append)
            except Exception as e:
                logger.error("Failed to update plan/notebook: {}", e)
                # Continue execution even if plan/notebook update fails

        # Parse action (updates history_trajectory via _parse_action when using chat path)
        if used_native and native_result:
            action_result = native_result.action_str
            action_str = action_result
            raw_action = native_result.action_str
            if state:
                state.history_trajectory.append(
                    {
                        "thought": thought,
                        "action_intent": action_intent,
                        "action": action_str,
                    }
                )
                if summary:
                    state.task_progress_summary = summary
        else:
            action_result = await self._parse_action(
                response_text,
                1,
                session_key=session_key,
                state=state,
            )
            action_str = action_result
            raw_action = parsed.raw_action if parsed.raw_action else action_str

        # Log the step and get saved screenshot paths
        step_dir = self.step_logger.log_step(
            query=current_query,
            step_num=step_num,
            messages=messages,
            response=response_dict,
            screenshot_path=screenshot_path,
            previous_screenshot_path=previous_screenshot_path,
            raw_action=raw_action,
            mapped_action=action_str,
            previous_mapped_action=previous_action,
            metadata={
                "session_key": session_key,
                "app_name": app_name or (state.current_app if state else ""),
                "model": self.model,
                "temperature": self.temperature,
                "max_tokens": self.max_tokens,
            },
            thought=thought,
            action_intent=action_intent,
            execution_result=str(action_result) if action_result is not None else None,
        )
        
        # Get saved screenshot paths from step_logger for state persistence
        # Use saved paths instead of temp paths
        saved_current_path = None
        saved_previous_path = None
        if step_dir and step_dir.exists():
            screenshots_dir = step_dir / "screenshots"
            if screenshots_dir.exists():
                current_file = screenshots_dir / "current.png"
                if current_file.exists():
                    saved_current_path = str(current_file)
                previous_file = screenshots_dir / "previous.png"
                if previous_file.exists():
                    saved_previous_path = str(previous_file)

        # Update state (thread-safe)
        # Note: history_trajectory and task_progress_summary are already updated in _parse_action
        try:
            # Use saved screenshot paths from step_logger for persistence
            # This ensures previous_screenshot_path points to a persistent file, not a temp file
            if saved_current_path:
                state.previous_screenshot_path = saved_current_path
            elif screenshot_path:
                state.previous_screenshot_path = screenshot_path
            else:
                state.previous_screenshot_path = previous_screenshot_path
            
            state.previous_action = action_str
            state.previous_thought = thought
            state.previous_action_intent = action_intent
            if action_result:
                state.execution_history = (state.execution_history + [action_result])[-20:]
            
            # Save state
            await self.state_manager.update_state_model(session_key, "gui", state)
        except Exception as e:
            logger.error("Failed to update state: {}", e)
            # Continue execution even if state update fails, but log the error

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
        # Note: We don't use session history anymore, so skip=2 (skip system prompts)
        # Only save the current step's user message, assistant response, and action result
        self._save_session(session, messages, 2)  # Skip 2 system messages

        if not summary:
            summary = ""

        return {
            "response_text": response_text,
            "action": action_result,
            "summary": summary,
            "thought": thought,
            "action_intent": action_intent,
            "status": "complete" if "COMPLETE" in response_text.upper() else "continue",
        }
