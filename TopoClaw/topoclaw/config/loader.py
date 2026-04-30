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

"""Configuration loading utilities."""

import json
from pathlib import Path
from typing import Any

from topoclaw.config.schema import Config
from topoclaw.utils.path_guard import resolve_path

# Sidecar next to config.json: map path string -> "read_only" | "edit" for ToolcallGuard.
TOOLCALL_GUARD_PATH_PERMISSIONS_FILENAME = "toolcall_guard_path_permissions.json"


# Global variable to store current config path (for multi-instance support)
_current_config_path: Path | None = None
_DEFAULT_APP_DIR = ".topoclaw"
_LEGACY_APP_DIR = ".topoclaw"


def set_config_path(path: Path) -> None:
    """Set the current config path (used to derive data directory)."""
    global _current_config_path
    _current_config_path = normalize_config_path(path)


def normalize_config_path(path: Path | str) -> Path:
    """Normalize config file path and reject obviously unsafe values."""
    normalized = resolve_path(path)
    if normalized.suffix.lower() != ".json":
        raise ValueError(f"Config path must be a .json file: {normalized}")
    return normalized


def get_config_path() -> Path:
    """Get the configuration file path."""
    if _current_config_path:
        return normalize_config_path(_current_config_path)
    default_path = Path.home() / _DEFAULT_APP_DIR / "config.json"
    legacy_path = Path.home() / _LEGACY_APP_DIR / "config.json"
    if default_path.exists():
        return normalize_config_path(default_path)
    if legacy_path.exists():
        return normalize_config_path(legacy_path)
    return normalize_config_path(default_path)


def get_toolcall_guard_path_permissions_path(config_path: Path | None = None) -> Path:
    """JSON sidecar path: same directory as the active ``config.json``."""
    return normalize_config_path(config_path or get_config_path()).parent / TOOLCALL_GUARD_PATH_PERMISSIONS_FILENAME


def load_toolcall_guard_path_permissions(config_path: Path | None = None) -> dict[str, str]:
    """
    Load per-path ToolcallGuard rules from the sidecar file.

    File format: a JSON object mapping path strings to ``read_only`` or ``edit``, or
    ``{\"path_permissions\": { ... }}`` (same as :meth:`topoclaw.secure.toolcall_guard.ToolcallGuard.from_paths_file`).
    Missing file returns ``{}``.
    """
    p = get_toolcall_guard_path_permissions_path(config_path)
    if not p.is_file():
        return {}
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return {}
    return _coerce_toolcall_guard_path_permissions_payload(raw)


def save_toolcall_guard_path_permissions(
    permissions: dict[str, str],
    *,
    config_path: Path | None = None,
) -> Path:
    """Write path permissions sidecar; returns the file path."""
    p = get_toolcall_guard_path_permissions_path(config_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(permissions, f, indent=2, ensure_ascii=False)
    return p


def _coerce_toolcall_guard_path_permissions_payload(raw: Any) -> dict[str, str]:
    if isinstance(raw, dict):
        if "path_permissions" in raw and isinstance(raw["path_permissions"], dict):
            raw = raw["path_permissions"]
        out: dict[str, str] = {}
        for k, v in raw.items():
            if isinstance(k, str) and isinstance(v, str):
                out[k] = v
        return out
    return {}


def _migrate_inline_toolcall_guard_permissions_to_sidecar(data: dict, config_path: Path) -> None:
    """Move ``tools.toolcall_guard_path_permissions`` from config JSON into sidecar once."""
    tools = data.get("tools")
    if not isinstance(tools, dict):
        return
    inline = None
    for key in (
        "toolcall_guard_path_permissions",
        "toolcallGuardPathPermissions",
        "toolCallGuardPathPermissions",
    ):
        if key not in tools:
            continue
        candidate = tools.pop(key)
        if isinstance(candidate, dict) and candidate:
            inline = candidate
            break
    if inline is None:
        return
    sidecar = get_toolcall_guard_path_permissions_path(config_path)
    if not sidecar.exists():
        coerced = _coerce_toolcall_guard_path_permissions_payload(inline)
        if coerced:
            save_toolcall_guard_path_permissions(coerced, config_path=config_path)
            print(
                f"Migrated tools.toolcall_guard_path_permissions to {sidecar} "
                "(remove the old key from config.json if still present)."
            )


def load_config(config_path: Path | None = None) -> Config:
    """
    Load configuration from file or create default.

    Args:
        config_path: Optional path to config file. Uses default if not provided.

    Returns:
        Loaded configuration object.
    """
    try:
        path = normalize_config_path(config_path or get_config_path())
    except ValueError as e:
        print(f"Warning: Invalid config path: {e}")
        print("Using default configuration.")
        return Config()

    if path.exists():
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            data = _migrate_config(data)
            _migrate_inline_toolcall_guard_permissions_to_sidecar(data, path)
            return Config.model_validate(data)
        except (json.JSONDecodeError, ValueError) as e:
            print(f"Warning: Failed to load config from {path}: {e}")
            print("Using default configuration.")

    return Config()


def merge_config_to_schema_defaults(config: Config) -> Config:
    """Round-trip through the schema so newly added fields (e.g. ``tools.*``) get current defaults.

    Used by ``topoclaw onboard`` refresh to merge legacy JSON with the latest template without
    discarding user values.
    """
    return Config.model_validate(config.model_dump(mode="json"))


def save_config(config: Config, config_path: Path | None = None) -> None:
    """
    Save configuration to file.

    Args:
        config: Configuration to save.
        config_path: Optional path to save to. Uses default if not provided.
    """
    path = normalize_config_path(config_path or get_config_path())
    path.parent.mkdir(parents=True, exist_ok=True)

    data = config.model_dump(by_alias=True)

    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)


def _migrate_config(data: dict) -> dict:
    """Migrate old config formats to current."""
    # Move tools.exec.restrictToWorkspace → tools.restrictToWorkspace
    tools = data.get("tools", {})
    exec_cfg = tools.get("exec", {})
    if "restrictToWorkspace" in exec_cfg and "restrictToWorkspace" not in tools:
        tools["restrictToWorkspace"] = exec_cfg.pop("restrictToWorkspace")
    return data
