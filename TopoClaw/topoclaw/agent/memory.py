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

"""Memory system for persistent agent memory."""

from __future__ import annotations

import asyncio
import json
import weakref
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

from loguru import logger

from topoclaw.session.compress_marker import SESSION_COMPRESS_MARKER
from topoclaw.utils.helpers import ensure_dir, estimate_message_tokens, estimate_prompt_tokens_chain

if TYPE_CHECKING:
    from topoclaw.providers.base import LLMProvider
    from topoclaw.session.manager import Session, SessionManager


_SAVE_MEMORY_TOOL = [
    {
        "type": "function",
        "function": {
            "name": "save_memory",
            "description": "Save the memory consolidation result to persistent storage.",
            "parameters": {
                "type": "object",
                "properties": {
                    "history_entry": {
                        "type": "string",
                        "description": "A paragraph summarizing key events/decisions/topics. "
                        "Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search.",
                    },
                    "memory_update": {
                        "type": "string",
                        "description": "Full updated long-term memory as markdown. Include all existing "
                        "facts plus new ones. Return unchanged if nothing new.",
                    },
                },
                "required": ["history_entry", "memory_update"],
            },
        },
    }
]

# Used only by ``compress_session``: summarize the chat tail for JSONL replacement (no file I/O).
_SESSION_TAIL_SUMMARY_TOOL = [
    {
        "type": "function",
        "function": {
            "name": "emit_session_summary",
            "description": "Return a summary of the conversation segment for the session log only.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {
                        "type": "string",
                        "description": "Concise summary: key facts, decisions, tool outcomes, and open threads.",
                    },
                },
                "required": ["summary"],
            },
        },
    }
]


_MEMORY_UPDATE_TOOL = [
    {
        "type": "function",
        "function": {
            "name": "memory_update",
            "description": (
                "Update event memory and/or long-term memory. "
                "When no update is needed, set both event_memory_update and long_term_memory_update to false "
                "and omit new_event_memory and new_long_term_memory entirely (do not pass empty strings)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "event_memory_update": {
                        "type": "boolean",
                        "description": "Whether to update event memory.",
                    },
                    "new_event_memory": {
                        "type": "string",
                        "description": (
                            "Full new event memory content. Required only when event_memory_update is true; "
                            "omit this parameter when event_memory_update is false."
                        ),
                    },
                    "long_term_memory_update": {
                        "type": "boolean",
                        "description": "Whether to update long-term memory.",
                    },
                    "new_long_term_memory": {
                        "type": "string",
                        "description": (
                            "Full new long-term memory content. Required only when long_term_memory_update is true; "
                            "omit this parameter when long_term_memory_update is false."
                        ),
                    },
                },
                "required": ["event_memory_update", "long_term_memory_update"],
            },
        },
    },
]

# Instructions for the LLM that calls ``memory_update`` (EVENT_MEMORY.md + MEMORY.md).
_MEMORY_UPDATE_SYSTEM_PROMPT = """You are the memory curator for this assistant. You receive current event memory, long-term memory, and the latest conversation turn(s). Call the ``memory_update`` tool exactly once with your decision.

## Event memory (EVENT_MEMORY.md)

- **Scope only:** the user's **current conversation focus**, **active topics**, **stage-level decisions**, and **important recent actions explicitly visible in the dialogue**. Treat it as a concise focus board for the ongoing conversation, not as a real-time execution log.
- **Do not store** transient runtime details such as tool-by-tool progress, background task status, per-step subagent updates, temporary browser state, or rapidly changing operational telemetry. Those belong in session/task events, not EVENT_MEMORY.md.
- **No user profiling** and **no inferred preferences**—everything must be **directly extractable** from the conversation.
- **Higher update frequency** than long-term memory; **keep concise** (short bullets, merge duplicates, trim stale detail quickly).

**Update event memory when any of these holds:**
1. The user raises a **new** question or focus topic.
2. The user's **conversation-stage objective changes materially**.
3. The **conversation topic shifts** clearly.
4. The user states an **important fact or decision** that should stay visible across the next few turns.
5. A background task changes the conversation at a high level, such as "waiting for user input", "completed with result X", or "failed for reason Y". Record only the stage-level outcome if it changes what the assistant should focus on next; do not record detailed task progress.

## Long-term memory (MEMORY.md)

- Holds a **user profile** refined across conversations. You may infer stable traits only when **grounded** in what the user said or did (or clearly confirmed). Do not invent profile details without evidence.
- **Lower update frequency** than event memory: update when durable traits/preferences/projects change, not on every turn.
- **Keep it concise:** tight sections, bullets, drop stale or redundant lines.

**If there is little or no user information yet:** do not fabricate a detailed profile. Prefer ``long_term_memory_update: false`` until there is substantive signal. When profile gaps matter, the assistant should **ask the user** for feedback; reflect answers in MEMORY.md after they are given.

**Update long-term memory when any of these holds:**
1. Event memory changed in ways that imply stable traits—check whether MEMORY.md should **sync**.
2. The user **explicitly** states new stable information about themselves.
3. The dialogue reveals **new preferences or habits** with clear behavioral or verbal evidence.
4. **Obvious change** in the user (goals, context, preferences) relative to what MEMORY.md already contains.

## Tool usage

- If **no** file needs changes: set ``event_memory_update`` and ``long_term_memory_update`` to **false** and **omit** ``new_event_memory`` and ``new_long_term_memory`` (no empty strings).
- If updating: pass the **full** replacement markdown for each file you set to true (still **concise**)."""


def _ensure_text(value: Any) -> str:
    """Normalize tool-call payload values to text for file storage."""
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)


def _normalize_save_memory_args(args: Any) -> dict[str, Any] | None:
    """Normalize provider tool-call arguments to the expected dict shape."""
    if isinstance(args, str):
        args = json.loads(args)
    if isinstance(args, list):
        return args[0] if args and isinstance(args[0], dict) else None
    return args if isinstance(args, dict) else None


class MemoryStore:
    """Workspace memory: MEMORY.md (facts), EVENT_MEMORY.md (structured focus), HISTORY.md (log)."""

    def __init__(self, workspace: Path):
        self.memory_dir = ensure_dir(workspace / "memory")
        self.memory_file = self.memory_dir / "MEMORY.md"
        self.history_file = self.memory_dir / "HISTORY.md"
        self.event_memory_file = self.memory_dir / "EVENT_MEMORY.md"

    def read_long_term(self) -> str:
        if self.memory_file.exists():
            return self.memory_file.read_text(encoding="utf-8")
        return ""

    def read_event_memory(self) -> str:
        if self.event_memory_file.exists():
            return self.event_memory_file.read_text(encoding="utf-8")
        return ""
    
    def write_event_memory(self, content: str) -> None:
        self.event_memory_file.write_text(content, encoding="utf-8")

    def write_long_term(self, content: str) -> None:
        self.memory_file.write_text(content, encoding="utf-8")

    def append_history(self, entry: str) -> None:
        with open(self.history_file, "a", encoding="utf-8") as f:
            f.write(entry.rstrip() + "\n\n")

    def get_memory_context(self) -> str:
        long_term = self.read_long_term()
        event = self.read_event_memory()
        parts: list[str] = []
        parts.append(
            "Use EVENT_MEMORY.md only as concise short-horizon focus memory for the conversation. "
            "Do not treat it as a real-time task tracker or runtime state source; task/session events are more authoritative for live execution state. "
            "Update memory only when the conversation focus or durable user information has actually changed. "
            "If nothing new, do not update. Always ask user for confirmation when the memory should be updated."
        )
        if event.strip():
            parts.append(f"## Event Memory\n{event.strip()}")
        if long_term.strip():
            parts.append(f"## Long-term Memory\n{long_term.strip()}")
        return "\n\n".join(parts) if parts else ""

    @staticmethod
    def _format_messages(messages: list[dict]) -> str:
        lines = []
        for message in messages:
            if not message.get("content"):
                continue
            tools = f" [tools: {', '.join(message['tools_used'])}]" if message.get("tools_used") else ""
            lines.append(
                f"[{message.get('timestamp', '?')[:16]}] {message['role'].upper()}{tools}: {message['content']}"
            )
        return "\n".join(lines)

    def apply_save_memory_payload(
        self,
        *,
        history_entry: Any | None,
        memory_update: Any | None,
    ) -> str:
        """
        Persist the same fields as the internal ``save_memory`` tool: append ``history_entry``
        to HISTORY.md and replace MEMORY.md when ``memory_update`` differs from current content.

        Returns text suitable for embedding into the compressed session message (prefers
        ``history_entry``, else a truncated ``memory_update``).
        """
        current_memory = self.read_long_term()
        session_summary: str | None = None
        if history_entry is not None:
            et = _ensure_text(history_entry)
            if et.strip():
                self.append_history(et)
                session_summary = et
        if memory_update is not None:
            update = _ensure_text(memory_update)
            if update != current_memory:
                self.write_long_term(update)
            if not (session_summary and session_summary.strip()):
                session_summary = update[:8000] if update else None

        if not session_summary or not str(session_summary).strip():
            session_summary = (
                "[Consolidation produced no history_entry text; see MEMORY.md / HISTORY.md.]"
            )
        return session_summary

    async def consolidate(
        self,
        messages: list[dict],
        provider: LLMProvider,
        model: str,
    ) -> tuple[bool, str | None]:
        """
        Consolidate the provided message chunk into MEMORY.md + HISTORY.md.

        Returns ``(success, session_summary_text)`` for token-based archival (writes MEMORY/HISTORY).
        The second value is the ``history_entry`` text when present, otherwise a short fallback
        from ``memory_update``.
        """
        if not messages:
            return True, None

        current_memory = self.read_long_term()
        prompt = f"""Process this conversation and call the save_memory tool with your consolidation.

## Current Long-term Memory
{current_memory or "(empty)"}

## Conversation to Process
{self._format_messages(messages)}"""

        try:
            response = await provider.chat_with_retry(
                messages=[
                    {"role": "system", "content": "You are a memory consolidation agent. Call the save_memory tool with your consolidation of the conversation."},
                    {"role": "user", "content": prompt},
                ],
                tools=_SAVE_MEMORY_TOOL,
                model=model,
            )

            if not response.has_tool_calls:
                logger.warning("Memory consolidation: LLM did not call save_memory, skipping")
                return False, None

            args = _normalize_save_memory_args(response.tool_calls[0].arguments)
            if args is None:
                logger.warning("Memory consolidation: unexpected save_memory arguments")
                return False, None

            session_summary = self.apply_save_memory_payload(
                history_entry=args.get("history_entry"),
                memory_update=args.get("memory_update"),
            )

            logger.info("Memory consolidation done for {} messages", len(messages))
            return True, session_summary
        except Exception:
            logger.exception("Memory consolidation failed")
            return False, None

    async def summarize_tail_for_session_compress(
        self,
        messages: list[dict],
        provider: LLMProvider,
        model: str,
    ) -> tuple[bool, str | None]:
        """
        LLM summarizes a message segment for ``compress_session`` only. Does **not** read or
        write MEMORY.md / HISTORY.md.
        """
        if not messages:
            return True, None

        segment = self._format_messages(messages)
        prompt = f"""Summarize the following conversation segment so it can replace those messages in the session log.

Rules:
- Preserve important facts, decisions, errors, and unresolved items.
- Do not mention MEMORY.md, HISTORY.md, or any file paths unless they appeared in the chat.
- Call emit_session_summary exactly once with the summary text.

## Segment
{segment}"""

        try:
            response = await provider.chat_with_retry(
                messages=[
                    {
                        "role": "system",
                        "content": (
                            "You compress session chat logs. Summarize the segment; "
                            "call emit_session_summary once. Do not write workspace files."
                        ),
                    },
                    {"role": "user", "content": prompt},
                ],
                tools=_SESSION_TAIL_SUMMARY_TOOL,
                model=model,
            )

            if not response.has_tool_calls:
                logger.warning("Session tail summary: LLM did not call emit_session_summary")
                return False, None

            args = _normalize_save_memory_args(response.tool_calls[0].arguments)
            if args is None:
                logger.warning("Session tail summary: unexpected tool arguments")
                return False, None

            raw = args.get("summary")
            text = _ensure_text(raw).strip() if raw is not None else ""
            if not text:
                logger.warning("Session tail summary: empty summary text")
                return False, None

            logger.info("Session tail summary done for {} messages", len(messages))
            return True, text
        except Exception:
            logger.exception("Session tail summary failed")
            return False, None


class MemoryConsolidator:
    """Owns consolidation policy, locking, and session offset updates."""

    _MAX_CONSOLIDATION_ROUNDS = 5

    def __init__(
        self,
        workspace: Path,
        provider: LLMProvider,
        model: str,
        sessions: SessionManager,
        context_window_tokens: int,
        build_messages: Callable[..., list[dict[str, Any]]],
        get_tool_definitions: Callable[[], list[dict[str, Any]]],
    ):
        self.store = MemoryStore(workspace)
        self.provider = provider
        self.model = model
        self.sessions = sessions
        self.context_window_tokens = context_window_tokens
        self._build_messages = build_messages
        self._get_tool_definitions = get_tool_definitions
        self._locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()

    def get_lock(self, session_key: str) -> asyncio.Lock:
        """Return the shared consolidation lock for one session."""
        return self._locks.setdefault(session_key, asyncio.Lock())

    async def consolidate_messages(
        self, messages: list[dict[str, object]]
    ) -> tuple[bool, str | None]:
        """Archive a message chunk into MEMORY/HISTORY; returns success and optional session summary text."""
        return await self.store.consolidate(messages, self.provider, self.model)

    async def summarize_tail_for_session_compress(
        self, messages: list[dict[str, object]]
    ) -> tuple[bool, str | None]:
        """Summarize a tail for ``compress_session`` without touching MEMORY/HISTORY files."""
        return await self.store.summarize_tail_for_session_compress(
            list(messages), self.provider, self.model
        )

    def pick_consolidation_boundary(
        self,
        session: Session,
        tokens_to_remove: int,
    ) -> tuple[int, int] | None:
        """Pick a user-turn boundary that removes enough old prompt tokens."""
        start = session.last_consolidated
        if start >= len(session.messages) or tokens_to_remove <= 0:
            return None

        removed_tokens = 0
        last_boundary: tuple[int, int] | None = None
        for idx in range(start, len(session.messages)):
            message = session.messages[idx]
            if idx > start and message.get("role") == "user":
                last_boundary = (idx, removed_tokens)
                if removed_tokens >= tokens_to_remove:
                    return last_boundary
            removed_tokens += estimate_message_tokens(message)

        return last_boundary

    def estimate_session_prompt_tokens(self, session: Session) -> tuple[int, str]:
        """Estimate current prompt size for the normal session history view."""
        history = session.get_history(max_messages=0)
        channel, chat_id = (session.key.split(":", 1) if ":" in session.key else (None, None))
        probe_messages = self._build_messages(
            history=history,
            current_message="[token-probe]",
            channel=channel,
            chat_id=chat_id,
        )
        return estimate_prompt_tokens_chain(
            self.provider,
            self.model,
            probe_messages,
            self._get_tool_definitions(),
        )

    async def archive_unconsolidated(self, session: Session) -> bool:
        """Archive the full unconsolidated tail for /new-style session rollover."""
        lock = self.get_lock(session.key)
        async with lock:
            snapshot = session.messages[session.last_consolidated:]
            if not snapshot:
                return True
            ok, _ = await self.consolidate_messages(snapshot)
            return ok

    async def _compress_one_round(
        self,
        session: Session,
        tokens_to_remove: int,
        *,
        round_num: int | None,
        estimated: int,
        source: str,
    ) -> tuple[bool, str]:
        """
        Archive one chunk ending at a user-turn boundary. Returns (advanced, reason).

        reason is one of: ok, no_boundary, empty_chunk, consolidate_failed.
        """
        boundary = self.pick_consolidation_boundary(session, max(1, tokens_to_remove))
        if boundary is None:
            return False, "no_boundary"

        end_idx = boundary[0]
        chunk = session.messages[session.last_consolidated:end_idx]
        if not chunk:
            return False, "empty_chunk"

        label = round_num if round_num is not None else "proactive"
        logger.info(
            "Memory compress round {} for {}: ~{} tokens via {}, chunk={} msgs",
            label,
            session.key,
            estimated,
            source,
            len(chunk),
        )
        ok, _ = await self.consolidate_messages(chunk)
        if not ok:
            return False, "consolidate_failed"
        session.last_consolidated = end_idx
        self.sessions.save(session)
        return True, "ok"


    async def memory_update(self, session: Session, model: str | None = None) -> str:
        """
        Update the event memory and long-term memory.
        """
        return await self.memory_update_with_turn(
            session=session,
            model=model,
            current_turn=session.messages[-2:],
        )

    async def memory_update_with_turn(
        self,
        session: Session,
        model: str | None = None,
        current_turn: list[dict[str, Any]] | None = None,
    ) -> str:
        """Update memory using an optional explicit turn snapshot."""
        turn_messages = current_turn if current_turn is not None else session.messages[-2:]
        if not turn_messages:
            return "Memory update skipped (empty turn)."
        current_turn = turn_messages
        current_turn_memory = self.store._format_messages(current_turn)

        event_memory = self.store.read_event_memory()
        long_term_memory = self.store.read_long_term()
        
        prompt = f"""
        ## Current Event Memory
        {event_memory}
        ## Current Long-term Memory
        {long_term_memory}
        ## Current Turn Memory
        {current_turn_memory}

        please call the memory_update tool exactly once with your decision.
        """
        response = await self.provider.chat_with_retry(
            messages=[{"role": "system", "content": _MEMORY_UPDATE_SYSTEM_PROMPT}, {"role": "user", "content": prompt}],
            model=model or self.model,
            tools=_MEMORY_UPDATE_TOOL,
        )

        if not response.has_tool_calls:
            return "Memory update failed (LLM did not call memory_update)."
        
        args = _normalize_save_memory_args(response.tool_calls[0].arguments)
        if args is None:
            return "Memory update failed (unexpected tool arguments)."
        
        event_memory_update = args.get("event_memory_update")
        new_event_memory = args.get("new_event_memory", None)
        long_term_memory_update = args.get("long_term_memory_update")
        new_long_term_memory = args.get("new_long_term_memory", None)

        if event_memory_update and new_event_memory:
            self.store.write_event_memory(new_event_memory)

        if long_term_memory_update and new_long_term_memory:
            self.store.write_long_term(new_long_term_memory)

        return "Memory updated successfully."
    

    async def proactive_compress_session(self, session: Session) -> str:
        """
        ``compress_session`` tool: LLM-summarize the full unconsolidated tail (no MEMORY/HISTORY
        writes), then replace that tail in ``session.messages`` with one summary user message and
        persist to JSONL. Use ``memory_update`` to persist to MEMORY.md or HISTORY.md.
        主动压缩会话，不等token超出，由llm主动调用
        """
        if not session.messages:
            return "Nothing to compress (empty session)."

        lock = self.get_lock(session.key)
        async with lock:
            start = session.last_consolidated
            tail = session.messages[start:]
            if not tail:
                return "Nothing to compress (no unconsolidated messages)."

            initial_est, _ = self.estimate_session_prompt_tokens(session)
            ok, summary_text = await self.summarize_tail_for_session_compress(tail)
            if not ok:
                return (
                    "Session summary failed (LLM did not return a summary or an error occurred)."
                )

            body = (summary_text or "").strip()
            if not body:
                body = "[Session compressed; prior messages were replaced by this placeholder.]"

            replacement = {
                "role": "user",
                "content": SESSION_COMPRESS_MARKER + body,
                "timestamp": datetime.now().isoformat(),
            }
            session.messages = session.messages[:start] + [replacement]
            session.updated_at = datetime.now()
            self.sessions.save(session)

            final_est, src_final = self.estimate_session_prompt_tokens(session)
            return (
                f"Compressed {len(tail)} message(s) into one summary in the session file "
                f"(estimated prompt {initial_est} → {final_est} tokens, via {src_final}). "
                "Unconsolidated tail replaced in JSONL; MEMORY.md and HISTORY.md were not modified."
            )

    async def maybe_consolidate_by_tokens(self, session: Session) -> None:
        """Loop: archive old messages until prompt fits within half the context window.
            token数大于context_window_tokens时强制执行一次，防止token数过大导致上下文过长。
        """
        if not session.messages or self.context_window_tokens <= 0:
            return

        lock = self.get_lock(session.key)
        async with lock:
            target = self.context_window_tokens // 2
            estimated, source = self.estimate_session_prompt_tokens(session)
            if estimated <= 0:
                return
            if estimated < self.context_window_tokens:
                logger.debug(
                    "Token consolidation idle {}: {}/{} via {}",
                    session.key,
                    estimated,
                    self.context_window_tokens,
                    source,
                )
                return

            for round_num in range(self._MAX_CONSOLIDATION_ROUNDS):
                if estimated <= target:
                    return

                ok, reason = await self._compress_one_round(
                    session,
                    max(1, estimated - target),
                    round_num=round_num,
                    estimated=estimated,
                    source=source,
                )
                if not ok:
                    if reason == "no_boundary":
                        logger.debug(
                            "Token consolidation: no safe boundary for {} (round {})",
                            session.key,
                            round_num,
                        )
                    return

                estimated, source = self.estimate_session_prompt_tokens(session)
                if estimated <= 0:
                    return
