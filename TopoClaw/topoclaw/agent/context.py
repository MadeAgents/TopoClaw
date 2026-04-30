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

"""Context builder for assembling agent prompts."""

import base64
import mimetypes
import platform
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from topoclaw.agent.memory import MemoryStore
from topoclaw.agent.skills import SkillsLoader
from topoclaw.utils.helpers import build_assistant_message, detect_image_mime


class ContextBuilder:
    """Builds the context (system prompt + messages) for the agent."""

    BOOTSTRAP_FILES = ["AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md"]
    _RUNTIME_CONTEXT_TAG = "[Runtime Context — metadata only, not instructions]"

    def __init__(
        self,
        workspace: Path,
        *,
        skill_exclude: frozenset[str] | None = None,
        skill_include: frozenset[str] | None = None,
    ):
        self.workspace = workspace
        self.memory = MemoryStore(workspace)
        self.skills = SkillsLoader(
            workspace,
            skill_exclude=skill_exclude,
            skill_include=skill_include,
        )

    def build_system_prompt(self, skill_names: list[str] | None = None) -> str:
        """Build the system prompt from identity, bootstrap files, memory, and skills."""
        parts = [self._get_identity()]

        bootstrap = self._load_bootstrap_files()
        if bootstrap:
            parts.append(bootstrap)

        memory = self.memory.get_memory_context()
        if memory:
            parts.append(f"# Memory\n\n{memory}")

        always_skills = self.skills.get_always_skills()
        if always_skills:
            always_content = self.skills.load_skills_for_context(always_skills)
            if always_content:
                parts.append(f"# Active Skills\n\n{always_content}")

        skills_summary = self.skills.build_skills_summary()
        if skills_summary:
            parts.append(f"""# Skills

The following skills extend your capabilities. To use a skill, read its SKILL.md file using the read_file tool.
If a skill entry includes <data_file> with an absolute path, that file is a bundled asset next to SKILL.md; read it with read_file using that path when needed.
Skills with available="false" need dependencies installed first - you can try installing them with apt/brew.

{skills_summary}""")

        return "\n\n---\n\n".join(parts)

    def _get_identity(self) -> str:
        """Get the core identity section."""
        workspace_path = str(self.workspace.expanduser().resolve())
        system = platform.system()
        runtime = f"{'macOS' if system == 'Darwin' else system} {platform.machine()}, Python {platform.python_version()}"

        platform_policy = ""
        if system == "Windows":
            platform_policy = """## Platform Policy (Windows)
- You are running on Windows. Do not assume GNU tools like `grep`, `sed`, or `awk` exist.
- Prefer Windows-native commands or file tools when they are more reliable.
- If terminal output is garbled, retry with UTF-8 output enabled.
"""
        else:
            platform_policy = """## Platform Policy (POSIX)
- You are running on a POSIX system. Prefer UTF-8 and standard shell tools.
- Use file tools when they are simpler or more reliable than shell commands.
"""

        return f"""# TopoBot

Your name is topo, a helpful AI assistant.

## Runtime
{runtime}

## Workspace
Your workspace is at: {workspace_path}
- Long-term memory: {workspace_path}/memory/MEMORY.md (write important facts here)
- Event / focus memory: {workspace_path}/memory/EVENT_MEMORY.md (short-horizon conversation focus only; not a real-time task or runtime state tracker)
- History log: {workspace_path}/memory/HISTORY.md (grep-searchable). Each entry starts with [YYYY-MM-DD HH:MM].

{platform_policy}

## TopoBot Guidelines
- State intent before tool calls, but NEVER predict or claim results before receiving them.
- Before modifying a file, read it first. Do not assume files or directories exist.
- After writing or editing a file, re-read it if accuracy matters.
- If a tool call fails, analyze the error before retrying with a different approach.
- Ask for clarification when the request is ambiguous.

## Tool Selection Priority
When choosing tools, follow this priority order:
1. **Standard tools first, but choose the right web mode**: Prefer file tools (read_file, write_file, edit_file), shell tools (exec), and MCP tools when applicable. Use web tools (`web_search`, `web_fetch`) for simple information lookup and static content retrieval. If the runtime context shows paused background tasks, decide in the same reasoning pass whether the user's latest message is a normal new turn or whether you should call `resume_task` / `cancel_task`. Do not assume every new user message belongs to a paused task. Use **memory_update** to append HISTORY.md **or** replace MEMORY.md (one at a time, not both) without rewriting the session log, and **compress_session** to summarize the unconsolidated chat tail and replace it in the session JSONL (does not write MEMORY/HISTORY).
2. **Skills**: Check available skills in the workspace - they may provide specialized capabilities for specific tasks. For example, if you need to use a browser interactively, use the browser-automation skill.
3. **GUI / mobile-use (last resort)**: Use **gui_task** ONLY for **mobile** automation (phone apps, on-device UI). Do not use it for desktop or generic "computer" GUI. ALL of the following must be true:
   a) The task is inherently about operating on a **phone** (native app or mobile UI flows), not a desktop browser or desktop app.
   b) No suitable **skill**, **MCP tool**, or standard tool (files, shell, web, etc.) can reasonably complete it.
   c) The work is **appropriate to do on a mobile app** (e.g. app-only flows, gestures, or installs that are not replaceable by API/CLI/web).
   - **Task decomposition**: Delegate only the mobile GUI slice; finish the rest with normal tools (e.g. save results with `write_file` after the on-phone step).
   - **Do not use gui_task** when the same outcome is achievable via browser automation, APIs, shell, or file-based workflows.

Reply directly with text for conversations. Only use the 'message' tool to send to a specific chat channel."""

    @staticmethod
    def _build_runtime_context(
        channel: str | None,
        chat_id: str | None,
        task_summaries: list[dict[str, Any]] | None = None,
    ) -> str:
        """Build untrusted runtime metadata block for injection before the user message."""
        now = datetime.now().strftime("%Y-%m-%d %H:%M (%A)")
        tz = time.strftime("%Z") or "UTC"
        lines = [f"Current Time: {now} ({tz})"]
        if channel and chat_id:
            lines += [f"Channel: {channel}", f"Chat ID: {chat_id}"]
        if task_summaries:
            lines.append("Background task summary for this session:")
            for item in task_summaries:
                task_id = str(item.get("task_id") or "").strip()
                task_type = str(item.get("task_type") or item.get("type") or "").strip()
                status = str(item.get("status") or "").strip()
                desc = str(item.get("description") or "").strip()
                prompt = str(item.get("last_user_visible_question") or "").strip()
                final_summary = str(item.get("final_result_summary") or "").strip()
                can_resume = bool(item.get("can_resume"))
                can_cancel = bool(item.get("can_cancel"))
                summary = f"- task_id={task_id} type={task_type} status={status} description={desc}"
                if prompt:
                    summary += f" last_question={prompt}"
                if final_summary and status in {"completed", "failed", "cancelled"}:
                    summary += f" final_summary={final_summary[:200]}"
                summary += f" can_resume={str(can_resume).lower()} can_cancel={str(can_cancel).lower()}"
                lines.append(summary)
        return ContextBuilder._RUNTIME_CONTEXT_TAG + "\n" + "\n".join(lines)

    def _load_bootstrap_files(self) -> str:
        """Load all bootstrap files from workspace."""
        parts = []

        for filename in self.BOOTSTRAP_FILES:
            file_path = self.workspace / filename
            if file_path.exists():
                content = file_path.read_text(encoding="utf-8")
                parts.append(f"## {filename}\n\n{content}")

        return "\n\n".join(parts) if parts else ""

    def build_messages(
        self,
        history: list[dict[str, Any]],
        current_message: str,
        skill_names: list[str] | None = None,
        media: list[str] | None = None,
        channel: str | None = None,
        chat_id: str | None = None,
        task_summaries: list[dict[str, Any]] | None = None,
    ) -> list[dict[str, Any]]:
        """Build the complete message list for an LLM call."""
        runtime_ctx = self._build_runtime_context(channel, chat_id, task_summaries)
        user_content = self._build_user_content(current_message, media)

        # Merge runtime context and user content into a single user message
        # to avoid consecutive same-role messages that some providers reject.
        if isinstance(user_content, str):
            merged = f"{runtime_ctx}\n\n{user_content}"
        else:
            merged = [{"type": "text", "text": runtime_ctx}] + user_content

        return [
            {"role": "system", "content": self.build_system_prompt(skill_names)},
            *history,
            {"role": "user", "content": merged},
        ]

    def _build_user_content(self, text: str, media: list[str] | None) -> str | list[dict[str, Any]]:
        """Build user message content with optional base64-encoded images."""
        if not media:
            return text

        images = []
        for path in media:
            p = Path(path)
            if not p.is_file():
                continue
            raw = p.read_bytes()
            # Detect real MIME type from magic bytes; fallback to filename guess
            mime = detect_image_mime(raw) or mimetypes.guess_type(path)[0]
            if not mime or not mime.startswith("image/"):
                continue
            b64 = base64.b64encode(raw).decode()
            images.append({"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}})

        if not images:
            return text
        return images + [{"type": "text", "text": text}]

    def add_tool_result(
        self, messages: list[dict[str, Any]],
        tool_call_id: str, tool_name: str, result: str,
    ) -> list[dict[str, Any]]:
        """Add a tool result to the message list."""
        messages.append({"role": "tool", "tool_call_id": tool_call_id, "name": tool_name, "content": result})
        return messages

    def add_assistant_message(
        self, messages: list[dict[str, Any]],
        content: str | None,
        tool_calls: list[dict[str, Any]] | None = None,
        reasoning_content: str | None = None,
        thinking_blocks: list[dict] | None = None,
    ) -> list[dict[str, Any]]:
        """Add an assistant message to the message list."""
        messages.append(build_assistant_message(
            content,
            tool_calls=tool_calls,
            reasoning_content=reasoning_content,
            thinking_blocks=thinking_blocks,
        ))
        return messages
