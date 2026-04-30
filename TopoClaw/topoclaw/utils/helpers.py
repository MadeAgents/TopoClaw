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

"""Utility functions for topoclaw."""

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

import tiktoken


def detect_image_mime(data: bytes) -> str | None:
    """Detect image MIME type from magic bytes, ignoring file extension."""
    if not data:
        return None
    # UTF-8 BOM prepended to binary PNG/JPEG (some exporters or clipboard saves)
    if data[:3] == b"\xef\xbb\xbf":
        data = data[3:]
    if len(data) >= 8 and data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    # Loose PNG: standard 4-byte signature (bytes 4–7 vary in a few broken but decodable files)
    if len(data) >= 4 and data[:4] == b"\x89PNG":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if len(data) >= 6 and data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if len(data) >= 2 and data[:2] == b"BM":
        return "image/bmp"
    return None


def ensure_dir(path: Path) -> Path:
    """Ensure directory exists, return it."""
    path.mkdir(parents=True, exist_ok=True)
    return path


def timestamp() -> str:
    """Current ISO timestamp."""
    return datetime.now().isoformat()


_UNSAFE_CHARS = re.compile(r'[<>:"/\\|?*]')

def safe_filename(name: str) -> str:
    """Replace unsafe path characters with underscores."""
    return _UNSAFE_CHARS.sub("_", name).strip()


def split_message(content: str, max_len: int = 2000) -> list[str]:
    """
    Split content into chunks within max_len, preferring line breaks.

    Args:
        content: The text content to split.
        max_len: Maximum length per chunk (default 2000 for Discord compatibility).

    Returns:
        List of message chunks, each within max_len.
    """
    if not content:
        return []
    if len(content) <= max_len:
        return [content]
    chunks: list[str] = []
    while content:
        if len(content) <= max_len:
            chunks.append(content)
            break
        cut = content[:max_len]
        # Try to break at newline first, then space, then hard break
        pos = cut.rfind('\n')
        if pos <= 0:
            pos = cut.rfind(' ')
        if pos <= 0:
            pos = max_len
        chunks.append(content[:pos])
        content = content[pos:].lstrip()
    return chunks


def build_assistant_message(
    content: str | None,
    tool_calls: list[dict[str, Any]] | None = None,
    reasoning_content: str | None = None,
    thinking_blocks: list[dict] | None = None,
) -> dict[str, Any]:
    """Build a provider-safe assistant message with optional reasoning fields."""
    msg: dict[str, Any] = {"role": "assistant", "content": content}
    if tool_calls:
        msg["tool_calls"] = tool_calls
    if reasoning_content is not None:
        msg["reasoning_content"] = reasoning_content
    if thinking_blocks:
        msg["thinking_blocks"] = thinking_blocks
    return msg


def estimate_prompt_tokens(
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]] | None = None,
) -> int:
    """Estimate prompt tokens with tiktoken."""
    try:
        enc = tiktoken.get_encoding("cl100k_base")
        parts: list[str] = []
        for msg in messages:
            content = msg.get("content")
            if isinstance(content, str):
                parts.append(content)
            elif isinstance(content, list):
                for part in content:
                    if isinstance(part, dict) and part.get("type") == "text":
                        txt = part.get("text", "")
                        if txt:
                            parts.append(txt)
        if tools:
            parts.append(json.dumps(tools, ensure_ascii=False))
        return len(enc.encode("\n".join(parts)))
    except Exception:
        return 0


def estimate_message_tokens(message: dict[str, Any]) -> int:
    """Estimate prompt tokens contributed by one persisted message."""
    content = message.get("content")
    parts: list[str] = []
    if isinstance(content, str):
        parts.append(content)
    elif isinstance(content, list):
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text = part.get("text", "")
                if text:
                    parts.append(text)
            else:
                parts.append(json.dumps(part, ensure_ascii=False))
    elif content is not None:
        parts.append(json.dumps(content, ensure_ascii=False))

    for key in ("name", "tool_call_id"):
        value = message.get(key)
        if isinstance(value, str) and value:
            parts.append(value)
    if message.get("tool_calls"):
        parts.append(json.dumps(message["tool_calls"], ensure_ascii=False))

    payload = "\n".join(parts)
    if not payload:
        return 1
    try:
        enc = tiktoken.get_encoding("cl100k_base")
        return max(1, len(enc.encode(payload)))
    except Exception:
        return max(1, len(payload) // 4)


def estimate_prompt_tokens_chain(
    provider: Any,
    model: str | None,
    messages: list[dict[str, Any]],
    tools: list[dict[str, Any]] | None = None,
) -> tuple[int, str]:
    """Estimate prompt tokens via provider counter first, then tiktoken fallback."""
    provider_counter = getattr(provider, "estimate_prompt_tokens", None)
    if callable(provider_counter):
        try:
            tokens, source = provider_counter(messages, tools, model)
            if isinstance(tokens, (int, float)) and tokens > 0:
                return int(tokens), str(source or "provider_counter")
        except Exception:
            pass

    estimated = estimate_prompt_tokens(messages, tools)
    if estimated > 0:
        return int(estimated), "tiktoken"
    return 0, "none"


def _skip_bundled_template_file(filename: str) -> bool:
    """Skip package scaffolding under ``topoclaw/templates`` (e.g. ``__init__.py``)."""
    if filename.startswith("."):
        return True
    if filename == "__init__.py":
        return True
    return Path(filename).stem == "__init__"


def _read_traversable_bytes(node: Any) -> bytes | None:
    """Read file bytes from an importlib ``Traversable`` (or pathlib path)."""
    rb = getattr(node, "read_bytes", None)
    if callable(rb):
        try:
            return rb()
        except Exception:
            pass
    rt = getattr(node, "read_text", None)
    if callable(rt):
        try:
            return rt(encoding="utf-8").encode("utf-8")
        except TypeError:
            try:
                return rt().encode("utf-8")
            except Exception:
                pass
        except Exception:
            pass
    return None


def sync_workspace_templates(workspace: Path, silent: bool = False) -> list[str]:
    """Copy bundled ``topoclaw/templates`` tree into *workspace* (missing files only).

    Recursively copies all files under the templates directory, preserving relative
    paths. Skips hidden dotfiles and any file whose basename stem is ``__init__``
    (e.g. ``__init__.py``). Always ensures ``memory/HISTORY.md`` (empty if new).
    """
    from importlib.resources import files as pkg_files

    try:
        tpl = pkg_files("topoclaw") / "templates"
    except Exception:
        return []
    if not tpl.is_dir():
        return []

    added: list[str] = []

    def _visit(node: Any, rel_parts: list[str]) -> None:
        try:
            children = list(node.iterdir())
        except (NotImplementedError, OSError, TypeError):
            return
        for child in children:
            name = child.name
            try:
                is_dir = child.is_dir()
            except Exception:
                is_dir = False
            if is_dir:
                _visit(child, rel_parts + [name])
                continue
            if _skip_bundled_template_file(name):
                continue
            dest = workspace.joinpath(*rel_parts, name)
            if dest.exists():
                continue
            data = _read_traversable_bytes(child)
            if data is None:
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            added.append(str(dest.relative_to(workspace)))

    _visit(tpl, [])

    hist = workspace / "memory" / "HISTORY.md"
    if not hist.exists():
        hist.parent.mkdir(parents=True, exist_ok=True)
        hist.write_text("", encoding="utf-8")
        added.append(str(hist.relative_to(workspace)))

    skills_filter = workspace / "skills_filter.json"
    if not skills_filter.exists():
        skills_filter.write_text(
            '{\n  "include": [],\n  "exclude": []\n}\n',
            encoding="utf-8",
        )
        added.append(str(skills_filter.relative_to(workspace)))
        
    try:
        gui_example = pkg_files("topoclaw") / "agent" / "gui" / "gui_models.example.json"
        _write(gui_example, workspace / "gui_models.json")
    except Exception:
        pass
    
    if added and not silent:
        from rich.console import Console
        for name in added:
            Console().print(f"  [dim]Created {name}[/dim]")
    return added
