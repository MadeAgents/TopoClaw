"""Tool: LLM-summarize unconsolidated session tail and replace it in JSONL (no MEMORY/HISTORY writes)."""

from typing import Any

from topoclaw.agent.memory import MemoryConsolidator
from topoclaw.agent.tools.base import Tool


class SessionMemoryCompressTool(Tool):
    """
    One LLM call summarizes the unconsolidated tail, then that tail is replaced in the session
    file by a single user message. Does **not** write memory/MEMORY.md or memory/HISTORY.md — use
    ``memory_update`` for that.
    """

    def __init__(self, consolidator: MemoryConsolidator):
        self._consolidator = consolidator
        self._channel = ""
        self._chat_id = ""
        self._session_key = ""

    def set_context(self, channel: str, chat_id: str, *, session_key: str | None = None) -> None:
        self._channel = channel
        self._chat_id = chat_id
        self._session_key = session_key or (f"{channel}:{chat_id}" if channel and chat_id else "")

    @property
    def name(self) -> str:
        return "compress_session"

    @property
    def description(self) -> str:
        return (
            "Compress the **unconsolidated** session tail: one LLM call **summarizes** those "
            "messages, then they are **replaced** in the session JSONL by a single summary user "
            "message (smaller prompt). Does **not** modify MEMORY.md or HISTORY.md — use "
            "``memory_update`` to persist long-term memory or history. No parameters."
        )

    def cast_params(self, params: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(params, dict):
            return params
        return super().cast_params({})

    @property
    def parameters(self) -> dict[str, Any]:
        return {"type": "object", "properties": {}}

    async def execute(self, **kwargs: Any) -> str:
        if not self._session_key:
            return "Error: no session context (channel/chat_id)."
        session = self._consolidator.sessions.get_or_create(self._session_key)
        return await self._consolidator.proactive_compress_session(session)
