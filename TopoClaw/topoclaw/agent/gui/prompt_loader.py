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

"""Prompt loader using Jinja2 templates for GUI agents."""

import os
from datetime import datetime
from pathlib import Path
from typing import Any

from jinja2 import Environment, FileSystemLoader, Template
from loguru import logger

from topoclaw.agent.gui.workspace_layout import gui_prompts_dir


class PromptLoader:
    """Load and render Jinja2 templates for GUI agent prompts."""

    def __init__(self, workspace: Path):
        """Initialize prompt loader.

        Args:
            workspace: Workspace directory where templates are stored
        """
        self.workspace = workspace
        self.template_dir = gui_prompts_dir(workspace)
        self.template_dir.mkdir(parents=True, exist_ok=True)

        # Try to load from workspace first, fallback to package templates
        template_paths = []
        
        # Workspace templates (priority)
        if self.template_dir.exists():
            template_paths.append(str(self.template_dir))
        
        # Package templates (fallback) - use relative path
        pkg_path = Path(__file__).parent / "prompts"
        if pkg_path.exists():
            template_paths.append(str(pkg_path))

        if not template_paths:
            raise ValueError("No template directories found")

        self.env = Environment(
            loader=FileSystemLoader(template_paths),
            trim_blocks=True,
            lstrip_blocks=True,
        )

    def load_template(self, template_name: str) -> Template:
        """Load a template by name."""
        try:
            return self.env.get_template(template_name)
        except Exception as e:
            logger.error("Failed to load template {}: {}", template_name, e)
            raise

    def render_mobile_prompt(
        self,
        query_text: str,
        app_name: str = "未知应用",
        user_qa_section: str = "",
        long_term_memory: str = "",
        current_date: str | None = None,
        # Previous state information
        previous_plan: list[str] | None = None,
        previous_summary: str | None = None,
        previous_thought: str | None = None,
        previous_action_intent: str | None = None,
        previous_action: str | None = None,
        previous_note: str | None = None,
        # Current state information
        todo_list: list[str] | None = None,
        notebook: str | None = None,
        history_trajectory: list[dict[str, str]] | None = None,
    ) -> str:
        """Render mobile agent prompt template.

        Args:
            query_text: User's query/instruction
            app_name: Current app name
            user_qa_section: User Q&A section content
            long_term_memory: Long-term memory content
            current_date: Current date string (defaults to now)
            previous_plan: Previous step's plan/todo list
            previous_summary: Previous step's summary
            previous_thought: Previous step's thought
            previous_action_intent: Previous step's action intent
            previous_action: Previous step's action
            previous_note: Previous step's note
            todo_list: Current plan/todo list
            notebook: Current notebook content
            history_trajectory: Recent execution history (list of {thought, action_intent, action} dicts)

        Returns:
            Rendered prompt string
        """
        if current_date is None:
            current_date = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        template = self.load_template("mobile_prompt.j2")
        return template.render(
            query_text=query_text,
            app_name=app_name,
            user_qa_section=user_qa_section,
            long_term_memory=long_term_memory,
            current_date=current_date,
            previous_plan=previous_plan or [],
            previous_summary=previous_summary or "",
            previous_thought=previous_thought or "",
            previous_action_intent=previous_action_intent or "",
            previous_action=previous_action or "",
            previous_note=previous_note or "",
            todo_list=todo_list or [],
            notebook=notebook or "",
            history_trajectory=history_trajectory or [],
        )

