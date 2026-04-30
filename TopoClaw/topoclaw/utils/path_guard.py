# Copyright 2025 OPPO
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Small path safety helpers used by file sinks."""

from __future__ import annotations

from pathlib import Path


def resolve_path(path: Path | str) -> Path:
    """Normalize a user-controlled path to an absolute canonical path."""
    raw = str(path)
    if "\x00" in raw:
        raise ValueError("path contains NUL byte")
    return Path(raw).expanduser().resolve()


def ensure_within(path: Path, root: Path) -> Path:
    """Require that ``path`` is inside ``root`` (or equal)."""
    rp = resolve_path(path)
    rr = resolve_path(root)
    try:
        rp.relative_to(rr)
    except ValueError as exc:
        raise ValueError(f"path escapes allowed root: {rp}") from exc
    return rp

