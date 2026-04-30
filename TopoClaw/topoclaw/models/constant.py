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

"""Shared protocol constants (e.g. WebSocket tool-guard confirmations)."""

from __future__ import annotations

from typing import Any

# WebSocket tool-guard: client echoes one of :data:`TOOL_GUARD_CLIENT_CONFIRM_VALUES`;
# :func:`normalize_tool_guard_choice` validates; unknown values map to ``deny``.
# ``invalid`` is reserved for server-side supersede of a stale waiter only.
TOOL_GUARD_TIMEOUT_SEC = 5.0
TOOL_GUARD_CONFIRM_TYPE_TEMPORARY_ALLOW = "temporary_allow"
TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_READ_ONLY = "grant_file_read_only"
TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_EDIT = "grant_file_edit"
TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_READ_ONLY = "grant_directory_read_only"
TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_EDIT = "grant_directory_edit"
TOOL_GUARD_CONFIRM_TYPE_DENY = "deny"
TOOL_GUARD_CONFIRM_TYPE_TIMEOUT = "timeout"
TOOL_GUARD_CONFIRM_TYPE_INVALID = "invalid"

TOOL_GUARD_CLIENT_CONFIRM_VALUES: tuple[str, ...] = (
    TOOL_GUARD_CONFIRM_TYPE_TEMPORARY_ALLOW,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_READ_ONLY,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_FILE_EDIT,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_READ_ONLY,
    TOOL_GUARD_CONFIRM_TYPE_GRANT_DIRECTORY_EDIT,
    TOOL_GUARD_CONFIRM_TYPE_DENY,
)
TOOL_GUARD_CLIENT_CONFIRM_SET = frozenset(TOOL_GUARD_CLIENT_CONFIRM_VALUES)


def normalize_tool_guard_choice(msg: dict[str, Any]) -> str:
    """
    Validate a client ``user_confirmed`` payload.

    The client must send one of :data:`TOOL_GUARD_CLIENT_CONFIRM_VALUES` in ``content`` or ``choice``.
    Anything else is treated as **deny** (refuse execution).
    """
    raw = str(msg.get("content") or msg.get("choice") or "").strip()
    if raw in TOOL_GUARD_CLIENT_CONFIRM_SET:
        return raw
    return TOOL_GUARD_CONFIRM_TYPE_DENY
