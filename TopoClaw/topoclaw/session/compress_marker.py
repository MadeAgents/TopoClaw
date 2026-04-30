"""Marker for rows produced by ``compress_session`` (merge follow-up user turns into one bubble)."""

from __future__ import annotations

from datetime import datetime
from typing import Any

SESSION_COMPRESS_MARKER = "[Session compressed — summary of prior messages below]\n\n"


def user_message_is_compressed_summary(msg: dict[str, Any]) -> bool:
    if msg.get("role") != "user":
        return False
    c = msg.get("content")
    return isinstance(c, str) and c.startswith(SESSION_COMPRESS_MARKER)


def merge_followup_user_into_compressed_tail(
    messages: list[dict[str, Any]],
    entry: dict[str, Any],
) -> bool:
    """
    If ``entry`` is a plain-text user turn and the last message is a compress_session summary row,
    append the new text under the summary (same JSONL row) and return True. Caller must not
    append ``entry`` when True is returned.
    """
    if not messages or entry.get("role") != "user":
        return False
    last = messages[-1]
    if not user_message_is_compressed_summary(last):
        return False
    content = entry.get("content")
    if not isinstance(content, str):
        return False
    sep = "\n\n---\n\n"
    last["content"] = (last.get("content") or "") + sep + content
    last["timestamp"] = entry.get("timestamp", datetime.now().isoformat())
    return True
