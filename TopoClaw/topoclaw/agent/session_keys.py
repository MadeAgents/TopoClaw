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

"""Canonical session keys for websocket chat (multi-agent)."""

from __future__ import annotations

import json
from pathlib import Path

from loguru import logger

from topoclaw.utils.helpers import safe_filename

DEFAULT_AGENT_ID = "default"


def normalize_agent_id(agent_id: str | None) -> str:
    """Empty / null agent_id maps to primary agent id ``default``."""
    if agent_id is None:
        return DEFAULT_AGENT_ID
    s = str(agent_id).strip()
    return s if s else DEFAULT_AGENT_ID


def websocket_session_key(agent_id: str | None, thread_id: str) -> str:
    """Build ``websocket:{agent_id}:{thread_id}`` with normalized agent id."""
    aid = normalize_agent_id(agent_id)
    tid = (thread_id or "").strip()
    return f"websocket:{aid}:{tid}"


# On disk, ``websocket:default:<thread_id>`` becomes filename stem ``websocket_default_<thread_id>``.
# Clients sometimes paste that stem as ``thread_id``; strip the mistaken prefix.
_WS_DEFAULT_FILE_PREFIX = "websocket_default_"


def normalize_client_thread_id(thread_id: str) -> str:
    """
    If ``thread_id`` looks like a jsonl stem (``websocket_default_...``) instead of the real
    conversation thread id, return the inner thread id (segment after ``default:``).
    """
    tid = (thread_id or "").strip()
    if tid.startswith(_WS_DEFAULT_FILE_PREFIX):
        return tid[len(_WS_DEFAULT_FILE_PREFIX) :]
    return tid


def websocket_session_keys_to_delete(agent_id: str | None, thread_id: str) -> list[str]:
    """
    Session keys that may exist on disk for this websocket agent + thread.

    Includes current ``websocket:{agent}:{tid}`` and, for the default agent only, legacy
    two-segment ``websocket:{tid}`` (pre–multi-agent migration).
    """
    tid = normalize_client_thread_id(thread_id)
    aid = normalize_agent_id(agent_id)
    primary = websocket_session_key(agent_id, tid)
    keys = [primary]
    if aid == DEFAULT_AGENT_ID:
        legacy = f"websocket:{tid}"
        if legacy != primary:
            keys.append(legacy)
    return keys


def _migrate_key(key: str | None) -> str | None:
    """Return new key if legacy ``websocket:{tid}`` should become ``websocket:default:{tid}``."""
    if not key or not isinstance(key, str):
        return None
    parts = key.split(":")
    if len(parts) == 2 and parts[0] == "websocket":
        return f"websocket:{DEFAULT_AGENT_ID}:{parts[1]}"
    return None


def migrate_legacy_websocket_sessions(sessions_dir: Path) -> int:
    """
    Migrate session files from ``websocket:{thread_id}`` to ``websocket:default:{thread_id}``.

    Updates metadata ``key``, ``parent_session_key``, and entries in ``child_sessions`` when they
    match the legacy two-segment websocket form. Rewrites / renames jsonl files as needed.

    Returns:
        Number of session files updated.
    """
    if not sessions_dir.is_dir():
        return 0

    migrated = 0
    for path in list(sessions_dir.glob("*.jsonl")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue
        lines = [ln for ln in text.splitlines() if ln.strip()]
        if not lines:
            continue
        try:
            meta = json.loads(lines[0])
        except json.JSONDecodeError:
            continue
        if meta.get("_type") != "metadata":
            continue

        dirty = False
        key = meta.get("key")
        nk = _migrate_key(key if isinstance(key, str) else None)
        if nk:
            meta["key"] = nk
            dirty = True

        pk = meta.get("parent_session_key")
        npk = _migrate_key(pk if isinstance(pk, str) else None)
        if npk:
            meta["parent_session_key"] = npk
            dirty = True

        children = meta.get("child_sessions")
        if isinstance(children, list):
            new_children: list = []
            child_dirty = False
            for c in children:
                if isinstance(c, str):
                    mc = _migrate_key(c) or c
                    if mc != c:
                        child_dirty = True
                    new_children.append(mc)
                else:
                    new_children.append(c)
            if child_dirty:
                meta["child_sessions"] = new_children
                dirty = True

        if not dirty:
            continue

        lines[0] = json.dumps(meta, ensure_ascii=False)
        out = "\n".join(lines) + "\n"
        new_safe = safe_filename(str(meta["key"]).replace(":", "_"))
        new_path = sessions_dir / f"{new_safe}.jsonl"

        try:
            new_path.write_text(out, encoding="utf-8")
        except OSError as e:
            logger.warning("Session migration write failed {}: {}", path, e)
            continue

        if new_path.resolve() != path.resolve():
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        migrated += 1

    if migrated:
        logger.info("Migrated {} websocket session file(s) to websocket:default:* layout", migrated)
    return migrated
