"""Tool: append HISTORY.md **or** update MEMORY.md (same write semantics as internal ``save_memory``)."""

from typing import Any

from topoclaw.agent.memory import MemoryConsolidator
from topoclaw.agent.tools.base import Tool


def _nonempty_str(v: Any) -> bool:
    return v is not None and str(v).strip() != ""


class MemoryUpdateTool(Tool):
    """Persist one of: history log append, or long-term memory file replace — not both."""

    def __init__(self, consolidator: MemoryConsolidator):
        self._consolidator = consolidator

    @property
    def name(self) -> str:
        return "memory_update"

    @property
    def description(self) -> str:
        return (
            "Update workspace memory files **without** touching the session JSONL. "
            "Provide **exactly one** of: (1) ``history_entry`` — append one paragraph to "
            "memory/HISTORY.md; (2) ``memory_update`` — replace memory/MEMORY.md with the full "
            "new long-term memory text when it differs from the current file. "
            "Do **not** pass both. For LLM consolidation that also trims the chat log, use "
            "``compress_session``."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "history_entry": {
                    "type": "string",
                    "description": (
                        "Use **alone** (omit memory_update): append a paragraph to HISTORY.md. "
                        "Start with [YYYY-MM-DD HH:MM]. Include detail useful for grep search."
                    ),
                },
                "memory_update": {
                    "type": "string",
                    "description": (
                        "Use **alone** (omit history_entry): full updated MEMORY.md markdown "
                        "(all existing facts plus new ones). Skipped if identical to current file."
                    ),
                },
            },
        }

    def validate_params(self, params: dict[str, Any]) -> list[str]:
        errors = super().validate_params(params)
        if errors:
            return errors
        has_he = _nonempty_str(params.get("history_entry"))
        has_mu = _nonempty_str(params.get("memory_update"))
        if has_he and has_mu:
            return [
                "provide exactly one of history_entry or memory_update (not both)",
            ]
        if not has_he and not has_mu:
            return [
                "provide exactly one of history_entry or memory_update (non-empty string)",
            ]
        return []

    async def execute(
        self,
        history_entry: str | None = None,
        memory_update: str | None = None,
        **kwargs: Any,
    ) -> str:
        summary = self._consolidator.store.apply_save_memory_payload(
            history_entry=history_entry,
            memory_update=memory_update,
        )
        clip = summary[:400] + ("…" if len(summary) > 400 else "")
        if _nonempty_str(history_entry) and not _nonempty_str(memory_update):
            what = "Appended memory/HISTORY.md."
        elif _nonempty_str(memory_update) and not _nonempty_str(history_entry):
            what = "Updated memory/MEMORY.md when content changed."
        else:
            what = "Updated memory files."
        return f"{what} Session JSONL unchanged. Preview: {clip}"

