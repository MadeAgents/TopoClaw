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

"""Session management for conversation history."""

import asyncio
import json
import shutil
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.config.paths import get_legacy_sessions_dir
from topoclaw.session.compress_marker import merge_followup_user_into_compressed_tail
from topoclaw.utils.helpers import ensure_dir, safe_filename


@dataclass
class Session:
    """
    A conversation session.

    Stores messages in JSONL format for easy reading and persistence.

    Important: Messages are append-only for LLM cache efficiency by default.
    Automatic token consolidation only advances ``last_consolidated`` (messages stay in the file).
    The ``compress_session`` tool replaces the unconsolidated tail in ``messages`` with one
    summary message (LLM summarizes the tail only; it does not write MEMORY.md/HISTORY.md).
    """

    key: str  # channel:chat_id
    messages: list[dict[str, Any]] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.now)
    updated_at: datetime = field(default_factory=datetime.now)
    metadata: dict[str, Any] = field(default_factory=dict)
    last_consolidated: int = 0  # Number of messages already consolidated to files
    
    # Enhanced: Session state management (per agent type)
    state: dict[str, Any] = field(default_factory=dict)
    
    # Enhanced: Session relationships
    parent_session_key: str | None = None
    child_sessions: list[str] = field(default_factory=list)

    def add_message(self, role: str, content: str, **kwargs: Any) -> None:
        """Add a message to the session."""
        msg = {
            "role": role,
            "content": content,
            "timestamp": datetime.now().isoformat(),
            **kwargs
        }
        if merge_followup_user_into_compressed_tail(self.messages, msg):
            self.updated_at = datetime.now()
            return
        self.messages.append(msg)
        self.updated_at = datetime.now()

    def get_history(self, max_messages: int = 500) -> list[dict[str, Any]]:
        """Return unconsolidated messages for LLM input, aligned to a user turn."""
        unconsolidated = self.messages[self.last_consolidated:]
        sliced = unconsolidated[-max_messages:]

        # Drop leading non-user messages to avoid orphaned tool_result blocks
        for i, m in enumerate(sliced):
            if m.get("role") == "user":
                sliced = sliced[i:]
                break

        out: list[dict[str, Any]] = []
        for m in sliced:
            entry: dict[str, Any] = {"role": m["role"], "content": m.get("content", "")}
            for k in ("tool_calls", "tool_call_id", "name"):
                if k in m:
                    entry[k] = m[k]
            out.append(entry)
        return out

    def clear(self) -> None:
        """Clear all messages and reset session to initial state."""
        self.messages = []
        self.last_consolidated = 0
        self.updated_at = datetime.now()
    
    def get_state(self, agent_type: str) -> dict:
        """Get state for a specific agent type."""
        return self.state.setdefault(agent_type, {})
    
    def update_state(self, agent_type: str, **kwargs: Any) -> None:
        """Update state for a specific agent type."""
        self.state.setdefault(agent_type, {}).update(kwargs)
        self.updated_at = datetime.now()


class SessionManager:
    """
    Manages conversation sessions.

    Sessions are stored as JSONL files in the sessions directory.
    """

    def __init__(self, workspace: Path, max_cache_size: int = 1000):
        self.workspace = ensure_dir(workspace)
        self.sessions_dir = ensure_dir(self.workspace / "sessions")
        self.legacy_sessions_dir = get_legacy_sessions_dir()
        try:
            from topoclaw.agent.session_keys import migrate_legacy_websocket_sessions

            migrate_legacy_websocket_sessions(self.sessions_dir)
        except Exception:
            logger.exception("Websocket session key migration failed (sessions dir={})", self.sessions_dir)
        self._cache: dict[str, Session] = {}
        self._max_cache_size = max_cache_size
        self._access_order: list[str] = []  # LRU order
        self._session_locks: dict[str, asyncio.Lock] = {}  # Per-session locks

    def _get_session_path(self, key: str) -> Path:
        """Get the file path for a session."""
        safe_key = safe_filename(key.replace(":", "_"))
        return self.sessions_dir / f"{safe_key}.jsonl"

    def _get_legacy_session_path(self, key: str) -> Path:
        """Legacy global session path (~/.topoclaw/sessions/)."""
        safe_key = safe_filename(key.replace(":", "_"))
        return self.legacy_sessions_dir / f"{safe_key}.jsonl"

    async def get_or_create_locked(
        self,
        key: str,
        parent_session_key: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> Session:
        """
        Get or create a session with lock protection (thread-safe).

        Args:
            key: Session key
            parent_session_key: Optional parent session key for relationship
            metadata: Optional metadata to update

        Returns:
            The session
        """
        lock = self._session_locks.setdefault(key, asyncio.Lock())
        async with lock:
            return self.get_or_create(key, parent_session_key, metadata)
    
    def get_or_create(
        self,
        key: str,
        parent_session_key: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> Session:
        """
        Get an existing session or create a new one.

        Args:
            key: Session key (usually channel:chat_id).
            parent_session_key: Optional parent session key for relationship
            metadata: Optional metadata to update

        Returns:
            The session.
        """
        # LRU cache management
        if key in self._cache:
            self._access_order.remove(key)
            self._access_order.append(key)
            session = self._cache[key]
        else:
            session = self._load(key)
            if session is None:
                session = Session(key=key)
            
            # Set relationship
            if parent_session_key:
                session.parent_session_key = parent_session_key
                parent = self.get_or_create(parent_session_key)
                if key not in parent.child_sessions:
                    parent.child_sessions.append(key)
            
            # Update metadata
            if metadata:
                session.metadata.update(metadata)
            
            # Add to cache (LRU)
            self._add_to_cache(key, session)
        
        return session
    
    def _add_to_cache(self, key: str, session: Session) -> None:
        """Add session to cache with LRU management."""
        # If cache is full, remove least recently used
        if len(self._cache) >= self._max_cache_size:
            oldest_key = self._access_order.pop(0)
            oldest_session = self._cache.pop(oldest_key)
            self.save(oldest_session)  # Save to disk before removing
        
        self._cache[key] = session
        self._access_order.append(key)

    def _load(self, key: str) -> Session | None:
        """Load a session from disk."""
        path = self._get_session_path(key)
        if not path.exists():
            legacy_path = self._get_legacy_session_path(key)
            if legacy_path.exists():
                try:
                    shutil.move(str(legacy_path), str(path))
                    logger.info("Migrated session {} from legacy path", key)
                except Exception:
                    logger.exception("Failed to migrate session {}", key)

        if not path.exists():
            return None

        try:
            messages = []
            metadata = {}
            created_at = None
            last_consolidated = 0
            state = {}
            parent_session_key = None
            child_sessions = []

            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue

                    data = json.loads(line)

                    if data.get("_type") == "metadata":
                        metadata = data.get("metadata", {})
                        created_at = datetime.fromisoformat(data["created_at"]) if data.get("created_at") else None
                        last_consolidated = data.get("last_consolidated", 0)
                        state = data.get("state", {})
                        parent_session_key = data.get("parent_session_key")
                        child_sessions = data.get("child_sessions", [])
                    else:
                        messages.append(data)

            session = Session(
                key=key,
                messages=messages,
                created_at=created_at or datetime.now(),
                metadata=metadata,
                last_consolidated=last_consolidated,
                state=state,
                parent_session_key=parent_session_key,
                child_sessions=child_sessions,
            )
            return session
        except Exception as e:
            logger.warning("Failed to load session {}: {}", key, e)
            return None

    def save(self, session: Session) -> None:
        """Save a session to disk."""
        path = self._get_session_path(session.key)

        with open(path, "w", encoding="utf-8") as f:
            metadata_line = {
                "_type": "metadata",
                "key": session.key,
                "created_at": session.created_at.isoformat(),
                "updated_at": session.updated_at.isoformat(),
                "metadata": session.metadata,
                "last_consolidated": session.last_consolidated,
                "state": session.state,  # Save state
                "parent_session_key": session.parent_session_key,
                "child_sessions": session.child_sessions,
            }
            f.write(json.dumps(metadata_line, ensure_ascii=False) + "\n")
            for msg in session.messages:
                f.write(json.dumps(msg, ensure_ascii=False) + "\n")

        self._cache[session.key] = session

    def invalidate(self, key: str) -> None:
        """Remove a session from the in-memory cache."""
        self._cache.pop(key, None)

    async def delete_session(self, key: str) -> dict[str, Any]:
        """
        Delete persisted session (jsonl) and remove it from the LRU cache.

        Uses the same per-key lock as ``get_or_create_locked`` to avoid races
        with concurrent loads/saves.
        """
        lock = self._session_locks.setdefault(key, asyncio.Lock())
        async with lock:
            return self._delete_session_unlocked(key)

    def _delete_session_unlocked(self, key: str) -> dict[str, Any]:
        """Must hold ``self._session_locks[key]`` when calling from async code."""
        path = self._get_session_path(key)
        legacy_path = self._get_legacy_session_path(key)
        file_existed = path.exists() or legacy_path.exists()
        unlink_error: str | None = None
        for p in (path, legacy_path):
            if not p.exists():
                continue
            try:
                p.unlink()
            except OSError as e:
                unlink_error = str(e)
                logger.warning("Failed to delete session file {}: {}", p, e)

        if key in self._cache:
            self._cache.pop(key, None)
        if key in self._access_order:
            self._access_order.remove(key)

        # Drop dangling child pointers in cached parents
        for sess in list(self._cache.values()):
            if key in sess.child_sessions:
                sess.child_sessions = [c for c in sess.child_sessions if c != key]

        return {
            "session_file_existed": file_existed,
            "session_file_removed": file_existed and unlink_error is None,
            "error": unlink_error,
        }

    async def delete_sessions_for_keys(self, keys: list[str]) -> dict[str, Any]:
        """
        Delete each session key (separate jsonl paths). Merges outcomes for logging / API.

        Typical use: try canonical key then legacy alias so one of them hits the on-disk file.
        """
        merged: dict[str, Any] = {
            "session_file_existed": False,
            "session_file_removed": False,
            "error": None,
            "per_key": [],
        }
        existed_any = False
        removed_all_that_existed = True
        for key in keys:
            info = await self.delete_session(key)
            merged["per_key"].append({"key": key, **info})
            if info.get("session_file_existed"):
                existed_any = True
                if not info.get("session_file_removed"):
                    removed_all_that_existed = False
            if info.get("error") and merged["error"] is None:
                merged["error"] = info["error"]
        merged["session_file_existed"] = existed_any
        merged["session_file_removed"] = bool(existed_any and removed_all_that_existed)
        return merged

    def list_sessions(self) -> list[dict[str, Any]]:
        """
        List all sessions.

        Returns:
            List of session info dicts.
        """
        sessions = []

        for path in self.sessions_dir.glob("*.jsonl"):
            try:
                # Read just the metadata line
                with open(path, encoding="utf-8") as f:
                    first_line = f.readline().strip()
                    if first_line:
                        data = json.loads(first_line)
                        if data.get("_type") == "metadata":
                            key = data.get("key") or path.stem.replace("_", ":", 1)
                            sessions.append({
                                "key": key,
                                "created_at": data.get("created_at"),
                                "updated_at": data.get("updated_at"),
                                "path": str(path)
                            })
            except Exception:
                continue

        return sorted(sessions, key=lambda x: x.get("updated_at", ""), reverse=True)
    
    def get_child_sessions(self, parent_key: str) -> list[Session]:
        """Get all child sessions for a parent session."""
        parent = self.get_or_create(parent_key)
        return [self.get_or_create(key) for key in parent.child_sessions]
    
    def get_session_state(self, key: str, agent_type: str) -> dict:
        """Get state for a specific agent type in a session."""
        session = self.get_or_create(key)
        return session.get_state(agent_type)
