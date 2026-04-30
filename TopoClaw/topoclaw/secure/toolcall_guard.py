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

"""
Validate model tool calls for file write/edit risk and exec-based bypass.

Exec: shell mutations must target paths under allowed roots; Python one-liners (or
``python``/``python3``/``py``) that perform filesystem writes, deletes, or moves are
rejected outright so agents use ``read_file`` / ``write_file`` / ``edit_file`` / ``list_dir``
instead of bypassing the file tools.

Default writable roots (expanduser + resolve):
  ~/.topoclaw/workspace
  ~/.topoclaw/agents

Users may append additional directories via ``ToolcallGuard(extra_allowed_dirs=...)``
or by loading paths from a JSON file (see ``ToolcallGuard.from_paths_file``).

Per-path permissions (``read_only`` vs ``edit``) apply under allowed roots: the most
specific matching path (file or directory prefix) wins. Use
:meth:`ToolcallGuard.set_path_permission` from application code, or persist rules in the
sidecar JSON next to ``config.json`` (see :func:`topoclaw.config.loader.load_toolcall_guard_path_permissions`),
or ``path_permissions`` inside a bundle JSON loaded via :meth:`ToolcallGuard.from_paths_file`.

When service mode enables the guard, :meth:`ToolcallGuard.attach_runtime_state_file` (wired from
:func:`topoclaw.agent.agent_loop_factory.toolcall_guard_from_config`) loads and saves user-granted
roots and permission overrides to ``{workspace}/toolcall_guard_runtime.json`` (one file per agent
workspace).
"""

from __future__ import annotations

import json
import re
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Literal, Mapping, Sequence

from topoclaw.utils.path_guard import ensure_within, resolve_path

# Persisted next to each agent workspace when :meth:`ToolcallGuard.attach_runtime_state_file` is used.
TOOLCALL_GUARD_AGENT_RUNTIME_FILENAME = "toolcall_guard_runtime.json"

# Public permission labels for :meth:`ToolcallGuard.set_path_permission`.
PathPermission = Literal["read_only", "edit"]

# Tools that mutate files on disk (must target an allowed path).
_MUTATING_FILE_TOOLS: frozenset[str] = frozenset({"write_file", "edit_file"})

# Heuristic: shell snippets that likely perform writes, edits, or destructive fs ops
# (Unix/macOS/Windows; see also _EXEC_AUX_MUTATION and _normalize_exec_for_mutation_scan).
_EXEC_MUTATION_HINT = re.compile(
    r"(?:^|[;&|]|\n)\s*(?:"
    r">>|>"  # redirection
    r"|\b(?:cp|mv|install|dd|rsync|scp|sftp|touch|mkdir|mkfifo|mknod|mktemp|ln\s+-s|chmod|chown|chgrp|"
    r"rm|rd|rmdir|unlink|del|erase|shred|truncate|tee|sponge|patch|ed\b|ex\b|"
    r"sed\s+-i|perl\s+-pi|ruby\s+-pi|awk\s+.*>>|"
    r"powershell(?:\.exe)?\s+(?:[^;\n]*\b(?:Set-Content|Out-File|Add-Content|New-Item|Clear-Content|"
    r"Remove-Item)\b)|"
    r"cmd(?:\.exe)?\s+(?:/c\s*)?(?:copy|move|del|erase|mkdir|mklink|ren|rename|echo\s+[^>]*>)"
    r")\b)",
    re.IGNORECASE | re.MULTILINE,
)

# Multi-token patterns (any OS) checked after privilege-wrapper stripping.
_EXEC_AUX_MUTATION: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bfind\b[^\n;&|]*?\s-delete\b", re.IGNORECASE),
    re.compile(r"\bfind\b[^\n;&|]*?\s-exec\b\s+\S*rm\b", re.IGNORECASE),
    re.compile(r"\bgit\s+apply\b", re.IGNORECASE),
    re.compile(r"\bgit\s+am\b", re.IGNORECASE),
    re.compile(r"\bemacs\b[^\n]*\s--batch\b", re.IGNORECASE),
    re.compile(r"\bvim\b[^\n]*\s-c\s*", re.IGNORECASE),
    re.compile(r"\bvi\b[^\n]*\s\+", re.IGNORECASE),
    re.compile(r"\bnano\b\s+", re.IGNORECASE),
    re.compile(r"\btee\s+[^\s;&|]", re.IGNORECASE),
)

# open(..., "w") / open(..., 'wb') etc. in one-liners
_PY_OPEN_WRITE = re.compile(
    r"""open\s*\([^)]*['\"][wa][ba]*['\"]""",
    re.IGNORECASE,
)

# ``python`` / ``python3`` / ``py`` as a shell-invoked interpreter (not substrings like "typography")
_SHELL_INVOKES_PYTHON = re.compile(
    r"(?:^|[;&|`\n])(?:python(?:3(?:\.\d+)?|2)?|py)(?:\.exe)?(?=\s)",
    re.IGNORECASE | re.MULTILINE,
)

# Python stdlib / pathlib patterns that mutate the filesystem (exec bypass → use file tools)
_PY_FS_API = re.compile(
    r"(?:"
    r"\bos\.(?:remove|unlink|rename|rmdir|makedirs|mkdir|replace|chmod|chown|utime|removedirs)\s*\("
    r"|\bshutil\.(?:rmtree|move|copy|copy2|copytree|copyfile|copyfileobj)\s*\("
    r"|\b(?:pathlib\.)?Path\s*\([^)]*\)\s*\.\s*(?:write_text|write_bytes|unlink|rename|rmdir|"
    r"mkdir|touch|replace|chmod|hardlink_to|symlink_to)\s*\("
    r"|\.\s*(?:write_text|write_bytes|unlink|rename|rmdir|mkdir|touch|replace)\s*\("
    r")",
    re.IGNORECASE,
)

_WIN_ABS = re.compile(r'(?<!["\w])([A-Za-z]:\\(?:[^\\<>|:*?"\n\r]+|\\[^\n\r])+)')
# Windows paths inside quotes (CMD: del "D:\path\file.txt") — unquoted pattern skips after leading ".
_WIN_ABS_IN_DQUOTES = re.compile(r'"([A-Za-z]:\\[^"]*)"')
_WIN_ABS_IN_SQUOTES = re.compile(r"'([A-Za-z]:\\[^']*)'")
# POSIX absolute paths (avoid single-letter false positives)
_POSIX_ABS = re.compile(r"(?:^|[\s`'\"=])(/[^\s`'\"|;&<>\n\r]*)")
# Quoted POSIX absolutes: rm "/tmp/a", jq ... > "/tmp/out"
_POSIX_ABS_IN_DQUOTES = re.compile(r'"(/[^"]*)"')
_POSIX_ABS_IN_SQUOTES = re.compile(r"'(/[^']*)'")

# Redirection targets: > path, >> path (skip >& n); allow quoted paths for spaces
_REDIR_TARGET = re.compile(
    r"(?:^|[\s])(?:>?>)\s*(?!&)"
    r'(?:"([^"]*)"|\'([^\']*)\'|([^\s;&|]+))',
    re.MULTILINE,
)


def default_allowed_roots() -> list[Path]:
    """Return the built-in writable roots: ~/.topoclaw/workspace and ~/.topoclaw/agents."""
    home = Path.home()
    return [
        (home / ".topoclaw" / "workspace").resolve(),
        (home / ".topoclaw" / "agents").resolve(),
    ]


def build_agent_toolcall_guard(
    workspace: Path | str,
    *,
    extra_allowed_dirs: Sequence[Path | str] | None = None,
    path_permissions: Mapping[str, str] | None = None,
) -> ToolcallGuard:
    """
    Guard tuned for :class:`topoclaw.agent.loop.AgentLoop`: relative ``write_file`` / ``edit_file``
    paths resolve against ``workspace``, and that directory (plus any extras) is included in
    allowed write roots alongside :func:`default_allowed_roots`.
    """
    ws = Path(workspace).expanduser().resolve()
    ordered: list[Path] = []
    seen: set[Path] = set()
    for p in (ws, *(extra_allowed_dirs or ())):
        if not str(p).strip():
            continue
        rp = Path(p).expanduser().resolve()
        if rp in seen:
            continue
        seen.add(rp)
        ordered.append(rp)
    return ToolcallGuard(
        workspace=ws,
        extra_allowed_dirs=ordered,
        path_permissions=path_permissions,
    )


def _normalize_path_permission_label(raw: str) -> PathPermission:
    v = (raw or "").strip().lower().replace("-", "_")
    if v in ("read_only", "readonly"):
        return "read_only"
    if v == "edit":
        return "edit"
    raise ValueError(f"invalid path permission {raw!r}; use 'read_only' or 'edit'")


@dataclass(frozen=True)
class ValidationResult:
    """Outcome of validating one tool call or exec snippet."""

    allowed: bool
    reason: str | None = None
    tool_name: str | None = None
    detail: str | None = None


def parse_tool_arguments(raw: Any) -> dict[str, Any]:
    """Normalize tool ``arguments`` from API payloads (dict or JSON string)."""
    if raw is None:
        return {}
    if isinstance(raw, Mapping):
        return dict(raw)
    if isinstance(raw, str):
        raw = raw.strip()
        if not raw:
            return {}
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return {}
        return dict(parsed) if isinstance(parsed, Mapping) else {}
    return {}


def _is_under(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _path_under_any_root(path: Path, roots: Sequence[Path]) -> bool:
    rp = path.resolve()
    for root in roots:
        try:
            if _is_under(rp, root.resolve()):
                return True
        except OSError:
            continue
    return False


def _extract_path_tokens(command: str) -> list[str]:
    """Collect plausible path tokens from a shell command (Windows + POSIX)."""
    out: list[str] = []
    for m in _WIN_ABS.finditer(command):
        out.append(m.group(1))
    for m in _WIN_ABS_IN_DQUOTES.finditer(command):
        out.append(m.group(1))
    for m in _WIN_ABS_IN_SQUOTES.finditer(command):
        out.append(m.group(1))
    for m in _POSIX_ABS.finditer(command):
        p = m.group(1).rstrip("'\"")
        if len(p) > 1:
            out.append(p)
    for m in _POSIX_ABS_IN_DQUOTES.finditer(command):
        p = m.group(1).rstrip("'\"")
        if len(p) > 1:
            out.append(p)
    for m in _POSIX_ABS_IN_SQUOTES.finditer(command):
        p = m.group(1).rstrip("'\"")
        if len(p) > 1:
            out.append(p)
    return out


def _redirection_targets(command: str) -> list[str]:
    out: list[str] = []
    for dq, sq, bare in _REDIR_TARGET.findall(command):
        for g in (dq, sq, bare):
            if g and str(g).strip():
                out.append(str(g).rstrip("`'\"\n\r"))
    return out


def _normalize_exec_for_mutation_scan(command: str) -> str:
    """Strip leading ``sudo`` / ``doas`` per line so keyword heuristics still match."""
    lines: list[str] = []
    for raw_line in command.splitlines():
        t = raw_line.strip()
        for _ in range(4):
            m = re.match(r"^(?:sudo|doas)\s+", t, re.IGNORECASE)
            if not m:
                break
            t = t[m.end() :].lstrip()
        lines.append(t)
    return "\n".join(lines) if lines else command


def _shell_invokes_python(command: str) -> bool:
    """True if the command line starts a Python interpreter (python, python3, py, or /path/to/python)."""
    s = (command or "").strip()
    if not s:
        return False
    if re.match(r"^(?:python(?:3(?:\.\d+)?|2)?|py)(?:\.exe)?\s", s, re.IGNORECASE):
        return True
    # /usr/bin/python3, venv/bin/python, etc.
    if re.match(r"^(?:/[\w.-]+)*/python(?:3(?:\.\d+)?|2)?(?:\.exe)?\s", s, re.IGNORECASE):
        return True
    if bool(_SHELL_INVOKES_PYTHON.search(s)):
        return True
    if re.search(
        r"(?:^|[;&|`\n])(?:/[\w.-]+)*/python(?:3(?:\.\d+)?|2)?(?:\.exe)?(?=\s)",
        s,
        re.IGNORECASE,
    ):
        return True
    return False


def _python_snippet_mutates_filesystem(command: str) -> bool:
    """Heuristic: Python source embedded in the shell command mutates the filesystem."""
    if _PY_OPEN_WRITE.search(command):
        return True
    if _PY_FS_API.search(command):
        return True
    lower = command.lower()
    if "pathlib" in lower and ".write_text(" in lower:
        return True
    if "pathlib" in lower and ".write_bytes(" in lower:
        return True
    if "os." in lower and (
        "remove(" in lower or "unlink(" in lower or "rmdir(" in lower or "rename(" in lower
    ):
        return True
    if "import " in lower and "open(" in lower and any(
        x in lower for x in ("'w'", '"w"', "'a'", '"a"', "'wb'", '"wb"', "'ab'", '"ab"')
    ):
        return True
    return False


def _exec_python_filesystem_bypass(command: str) -> bool:
    """Python in exec used for file write/delete/move/etc. — must use filesystem tools instead."""
    return _shell_invokes_python(command) and _python_snippet_mutates_filesystem(command)


def _mutation_target_paths_from_command(command: str) -> list[str]:
    """Path-like args not always covered by generic token scan (PowerShell params, git apply, etc.)."""
    out: list[str] = []
    for m in re.finditer(
        r"-(?:LiteralPath|Path|FilePath|Destination)\s+(\"([^\"]*)\"|'([^']*)'|([^\s;&|]+))",
        command,
        re.IGNORECASE,
    ):
        for g in (m.group(2), m.group(3), m.group(4)):
            if g and str(g).strip():
                out.append(str(g).strip())
    m = re.search(
        r"\bgit\s+apply\b(?:\s+--[=\w-]+)*(?:\s+[^\s-][^\s]*)*\s+(\S+)",
        command,
        re.IGNORECASE,
    )
    if m:
        tok = m.group(1).strip().strip("\"'")
        if tok and not tok.startswith("-") and not tok.startswith("--"):
            out.append(tok)
    return out


class ToolcallGuard:
    """
    Policy: file writes (tool or exec) must target paths under allowed roots.

    Relative paths in tool calls are resolved against ``workspace`` when set,
    mirroring :mod:`topoclaw.agent.tools.filesystem` behavior.
    """

    def __init__(
        self,
        extra_allowed_dirs: Sequence[Path | str] | None = None,
        *,
        workspace: Path | str | None = None,
        roots: Sequence[Path | str] | None = None,
        path_permissions: Mapping[str, str] | None = None,
    ) -> None:
        self._extra = [
            resolve_path(p) for p in (extra_allowed_dirs or ()) if str(p).strip()
        ]
        self._workspace = (
            resolve_path(workspace) if workspace is not None else None
        )
        self._roots_override: list[Path] | None = None
        if roots is not None:
            self._roots_override = [resolve_path(p) for p in roots if str(p).strip()]
        self._path_permissions: dict[Path, PathPermission] = {}
        if path_permissions:
            for pstr, perm in path_permissions.items():
                try:
                    self.set_path_permission(pstr, perm, _skip_runtime_persist=True)
                except ValueError:
                    continue

        self._runtime_persist_path: Path | None = None
        self._bootstrap_extra: frozenset[Path] | None = None
        self._bootstrap_perms: dict[Path, PathPermission] | None = None
        self._persist_depth: int = 0

    def attach_runtime_state_file(self, path: Path | str) -> None:
        """
        Enable load/save of user-granted roots and path permissions under the agent workspace.

        Baseline is the guard state *before* loading the file; only deltas are written so global
        config sidecars are not duplicated into this file.
        """
        p = resolve_path(path)
        if self._workspace is not None:
            p = ensure_within(p, self._workspace)
        self._runtime_persist_path = p
        self._bootstrap_extra = frozenset(self._extra)
        self._bootstrap_perms = dict(self._path_permissions)
        self._load_runtime_state_if_present()

    def persist_runtime_state_to_disk(self) -> None:
        """Write runtime overlay (extra roots + permission overrides) to :attr:`_runtime_persist_path`."""
        path = self._runtime_persist_path
        if path is None:
            return
        baseline_extra = self._bootstrap_extra or frozenset()
        baseline_perms = self._bootstrap_perms or {}
        extra_runtime = [str(p) for p in self._extra if p not in baseline_extra]
        perm_runtime: dict[str, str] = {}
        for k, v in self._path_permissions.items():
            if k not in baseline_perms or baseline_perms.get(k) != v:
                perm_runtime[str(k)] = v
        payload: dict[str, Any] = {
            "version": 1,
            "extra_allowed_dirs": extra_runtime,
            "path_permissions": perm_runtime,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def _load_runtime_state_if_present(self) -> None:
        path = self._runtime_persist_path
        if path is None or not path.is_file():
            return
        try:
            raw = path.read_text(encoding="utf-8")
            data = json.loads(raw)
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(data, dict):
            return
        with self._suppress_runtime_persist():
            perm_obj = data.get("path_permissions")
            if isinstance(perm_obj, dict):
                for k, v in perm_obj.items():
                    if not isinstance(k, str) or not isinstance(v, str):
                        continue
                    try:
                        self.set_path_permission(k, v, _skip_runtime_persist=True)
                    except ValueError:
                        continue
            extra_list = data.get("extra_allowed_dirs")
            if isinstance(extra_list, list):
                for d in extra_list:
                    if isinstance(d, str) and d.strip():
                        self.add_extra_allowed_root(d, _skip_runtime_persist=True)

    @contextmanager
    def _suppress_runtime_persist(self):
        self._persist_depth += 1
        try:
            yield
        finally:
            self._persist_depth -= 1

    def _maybe_persist_runtime_state(self) -> None:
        if self._runtime_persist_path is None or self._persist_depth > 0:
            return
        try:
            self.persist_runtime_state_to_disk()
        except OSError:
            pass

    @classmethod
    def from_paths_file(
        cls,
        path: Path | str,
        *,
        workspace: Path | str | None = None,
        roots: Sequence[Path | str] | None = None,
    ) -> ToolcallGuard:
        """
        Load extra allowed directories from a JSON file.

        Supported shapes::

            { "extra_allowed_dirs": [ "/path/one", "C:\\\\path\\\\two" ] }

        or a bare JSON array of strings.

        Optional ``path_permissions`` object maps path strings to ``read_only`` or ``edit``::

            {
              "extra_allowed_dirs": ["/tmp/ws"],
              "path_permissions": { "/tmp/ws/secrets": "read_only" }
            }
        """
        p = Path(path).expanduser()
        raw = p.read_text(encoding="utf-8")
        data = json.loads(raw)
        extra: list[str] = []
        if isinstance(data, list):
            extra = [str(x) for x in data]
        elif isinstance(data, dict):
            raw_list = data.get("extra_allowed_dirs") or data.get("allowed_dirs_extra")
            if isinstance(raw_list, list):
                extra = [str(x) for x in raw_list]
        perm_map: dict[str, str] = {}
        if isinstance(data, dict):
            raw_perm = data.get("path_permissions")
            if isinstance(raw_perm, dict):
                perm_map = {str(k): str(v) for k, v in raw_perm.items()}
        return cls(
            extra_allowed_dirs=extra,
            workspace=workspace,
            roots=roots,
            path_permissions=perm_map or None,
        )

    def allowed_roots(self) -> list[Path]:
        if self._roots_override is not None:
            return list(self._roots_override)
        return [*default_allowed_roots(), *self._extra]

    def resolve_tool_path(self, path: str | Path) -> Path:
        """Resolve a path as filesystem tools do (workspace for relative paths)."""
        p = Path(path).expanduser()
        if not p.is_absolute():
            base = self._workspace
            if base is not None:
                p = base / p
        return p.resolve()

    def path_allowed_for_write(self, path: str | Path) -> bool:
        return _path_under_any_root(self.resolve_tool_path(path), self.allowed_roots())

    def add_extra_allowed_root(
        self, path: Path | str, *, _skip_runtime_persist: bool = False
    ) -> None:
        """
        Append a resolved path as an extra writable root if it is not already covered.

        Pass a **file** path to allow writes to that file only (siblings stay outside the root).
        Pass a **directory** to allow writes anywhere under it (subject to ``path_permissions``).
        """
        rp = Path(path).expanduser().resolve()
        if rp in self._extra:
            return
        if _path_under_any_root(rp, self.allowed_roots()):
            return
        self._extra.append(rp)
        if not _skip_runtime_persist:
            self._maybe_persist_runtime_state()

    def set_path_permission(
        self, path: Path | str, permission: str, *, _skip_runtime_persist: bool = False
    ) -> None:
        """
        Register ``read_only`` or ``edit`` for a file or directory.

        Directories apply to all paths under them unless a more specific descendant rule
        exists (longest-prefix / nearest ancestor wins).
        """
        norm = _normalize_path_permission_label(permission)
        key = Path(path).expanduser().resolve()
        if self._path_permissions.get(key) == norm:
            return
        self._path_permissions[key] = norm
        if not _skip_runtime_persist:
            self._maybe_persist_runtime_state()

    def remove_path_permission(self, path: Path | str) -> bool:
        """Remove an explicit rule for *path*; returns whether a key was removed."""
        key = Path(path).expanduser().resolve()
        removed = self._path_permissions.pop(key, None) is not None
        if removed:
            self._maybe_persist_runtime_state()
        return removed

    def clear_path_permissions(self) -> None:
        """Drop all per-path rules (only roots-based policy remains)."""
        if not self._path_permissions:
            return
        self._path_permissions.clear()
        self._maybe_persist_runtime_state()

    def path_permissions_snapshot(self) -> dict[str, str]:
        """Resolved paths -> permission (for debugging / persistence)."""
        return {str(p): perm for p, perm in self._path_permissions.items()}

    def effective_path_permission_for_resolved(self, resolved: Path) -> PathPermission | None:
        """Most specific rule on *resolved* or an ancestor; ``None`` if unset (writes allowed in-root)."""
        cur = resolved
        while True:
            perm = self._path_permissions.get(cur)
            if perm is not None:
                return perm
            parent = cur.parent
            if parent == cur:
                break
            cur = parent
        return None

    def effective_path_permission(self, path: str | Path) -> PathPermission | None:
        """Like :meth:`effective_path_permission_for_resolved` but uses tool path resolution."""
        return self.effective_path_permission_for_resolved(self.resolve_tool_path(path))

    def _is_resolved_path_read_only(self, resolved: Path) -> bool:
        return self.effective_path_permission_for_resolved(resolved) == "read_only"

    def validate_file_tool(
        self, name: str, arguments: Mapping[str, Any] | str | None
    ) -> ValidationResult:
        """Check write/edit tools; other path tools are allowed."""
        args = parse_tool_arguments(arguments)
        if name in _MUTATING_FILE_TOOLS:
            raw_path = args.get("path")
            if not raw_path or not isinstance(raw_path, str):
                return ValidationResult(
                    False,
                    "missing_or_invalid_path",
                    name,
                    "write_file/edit_file requires a string 'path'",
                )
            if not self.path_allowed_for_write(raw_path):
                return ValidationResult(
                    False,
                    "path_outside_allowed_roots",
                    name,
                    f"path {raw_path!r} is not under allowed roots",
                )
            rp = self.resolve_tool_path(raw_path)
            if self._is_resolved_path_read_only(rp):
                return ValidationResult(
                    False,
                    "path_read_only",
                    name,
                    f"path {raw_path!r} is read_only (set edit via set_path_permission to allow writes)",
                )
        return ValidationResult(True, None, name, None)

    def validate_exec_command(
        self,
        command: str,
        cwd: str | Path | None = None,
    ) -> ValidationResult:
        """
        Static check on the exec command string.

        If the command appears to perform filesystem mutations, every absolute
        path (and redirection target) must fall under allowed roots. Relative
        paths are resolved against ``cwd`` or the process cwd.
        """
        cmd = (command or "").strip()
        if not cmd:
            return ValidationResult(True, None, "exec", None)

        if _exec_python_filesystem_bypass(cmd):
            return ValidationResult(
                False,
                "exec_python_filesystem_use_file_tools",
                "exec",
                "Filesystem changes via Python in exec are blocked. Use the file tools: read_file, "
                "write_file, edit_file, list_dir (and read_pdf when appropriate) instead of shell "
                "Python for reads, writes, deletes, or moves.",
            )

        roots = self.allowed_roots()
        base_cwd = Path(cwd).expanduser().resolve() if cwd else Path.cwd()

        if not self._exec_looks_mutating(cmd):
            return ValidationResult(True, None, "exec", None)

        candidates: list[str] = []
        candidates.extend(_extract_path_tokens(cmd))
        candidates.extend(_redirection_targets(cmd))
        candidates.extend(_mutation_target_paths_from_command(cmd))

        checked: list[str] = []
        for raw in candidates:
            token = raw.strip().strip("'\"")
            if not token or token.startswith("-") or re.fullmatch(r"\d+", token):
                continue
            try:
                p = Path(token).expanduser()
                if not p.is_absolute():
                    p = (base_cwd / p).resolve()
                else:
                    p = p.resolve()
            except OSError:
                continue
            checked.append(str(p))
            if not _path_under_any_root(p, roots):
                return ValidationResult(
                    False,
                    "exec_targets_path_outside_allowed_roots",
                    "exec",
                    f"mutation-like command references path outside allowed roots: {p}",
                )
            if self._is_resolved_path_read_only(p):
                return ValidationResult(
                    False,
                    "exec_targets_read_only_path",
                    "exec",
                    f"mutation-like command targets read_only path: {p}",
                )

        return ValidationResult(True, None, "exec", None)

    @staticmethod
    def _exec_looks_mutating(command: str) -> bool:
        cmd = _normalize_exec_for_mutation_scan(command)
        # Shell redirection to a file (covers `echo x > f` where keyword hints do not match).
        if _REDIR_TARGET.search(cmd):
            return True
        if _EXEC_MUTATION_HINT.search(cmd):
            return True
        if any(p.search(cmd) for p in _EXEC_AUX_MUTATION):
            return True
        if _PY_OPEN_WRITE.search(cmd):
            return True
        lower = cmd.lower()
        if "import " in lower and "open(" in lower and any(
            x in lower for x in ("'w'", '"w"', "'a'", '"a"', "'wb'", '"wb"')
        ):
            return True
        if "pathlib" in lower and ".write_text(" in lower:
            return True
        if "os." in lower and (
            "remove(" in lower or "unlink(" in lower or "rmdir(" in lower or "rename(" in lower
        ):
            return True
        return False

    def validate_exec_output(
        self,
        text: str,
        *,
        cwd: str | Path | None = None,
    ) -> ValidationResult:
        """
        Optional post-run scan of exec stdout/stderr.

        Looks for mutation hints plus absolute paths outside allowed roots.
        Intended to catch obfuscated commands; expect false positives/negatives.
        """
        if not (text or "").strip():
            return ValidationResult(True, None, "exec_output", None)

        roots = self.allowed_roots()
        base_cwd = Path(cwd).expanduser().resolve() if cwd else Path.cwd()

        snippet = text if len(text) <= 200_000 else text[:200_000]
        if not self._exec_looks_mutating(snippet):
            return ValidationResult(True, None, "exec_output", None)

        for raw in (
            _extract_path_tokens(snippet)
            + _redirection_targets(snippet)
            + _mutation_target_paths_from_command(snippet)
        ):
            token = raw.strip().strip("'\"")
            if not token:
                continue
            try:
                p = Path(token).expanduser()
                if not p.is_absolute():
                    p = (base_cwd / p).resolve()
                else:
                    p = p.resolve()
            except OSError:
                continue
            if not _path_under_any_root(p, roots):
                return ValidationResult(
                    False,
                    "exec_output_suggests_write_outside_roots",
                    "exec_output",
                    f"output references path outside allowed roots: {p}",
                )
            if self._is_resolved_path_read_only(p):
                return ValidationResult(
                    False,
                    "exec_output_suggests_write_to_read_only",
                    "exec_output",
                    f"output references read_only path: {p}",
                )
        return ValidationResult(True, None, "exec_output", None)

    def validate_tool_call(self, name: str, arguments: Any) -> ValidationResult:
        """Validate a single tool call by name."""
        if name == "exec":
            args = parse_tool_arguments(arguments)
            cmd = args.get("command")
            if not isinstance(cmd, str):
                return ValidationResult(
                    False,
                    "invalid_exec_command",
                    "exec",
                    "exec requires string 'command'",
                )
            wd = args.get("working_dir")
            cwd: str | Path | None = wd if isinstance(wd, str) else None
            return self.validate_exec_command(cmd, cwd=cwd)
        if name in _MUTATING_FILE_TOOLS:
            return self.validate_file_tool(name, arguments)
        return ValidationResult(True, None, name, None)

    def validate_tool_calls(
        self, tool_calls: Iterable[Mapping[str, Any]]
    ) -> list[ValidationResult]:
        return [self._validate_one_message_tool(tc) for tc in tool_calls]

    def _validate_one_message_tool(self, tc: Mapping[str, Any]) -> ValidationResult:
        # OpenAI-style: { "type": "function", "function": { "name", "arguments" } }
        fn = tc.get("function")
        if isinstance(fn, Mapping):
            name = str(fn.get("name") or "")
            return self.validate_tool_call(name, fn.get("arguments"))
        name = str(tc.get("name") or "")
        return self.validate_tool_call(name, tc.get("arguments"))


def validate_assistant_tool_calls(
    tool_calls: Sequence[Mapping[str, Any]] | None,
    guard: ToolcallGuard | None = None,
) -> list[ValidationResult]:
    """Convenience: validate a list of tool_calls from an assistant message."""
    g = guard or ToolcallGuard()
    if not tool_calls:
        return []
    return g.validate_tool_calls(tool_calls)
