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

"""Runtime path helpers derived from the active config context."""

from __future__ import annotations

from pathlib import Path

from topoclaw.config.loader import get_config_path
from topoclaw.utils.helpers import ensure_dir
from topoclaw.utils.path_guard import ensure_within, resolve_path

_DEFAULT_APP_DIR = ".topoclaw"
_LEGACY_APP_DIR = ".topoclaw"


def _app_home_dir() -> Path:
    default_dir = resolve_path(Path.home() / _DEFAULT_APP_DIR)
    legacy_dir = resolve_path(Path.home() / _LEGACY_APP_DIR)
    if default_dir.exists():
        return default_dir
    if legacy_dir.exists():
        return legacy_dir
    return default_dir


def get_data_dir() -> Path:
    """Return the instance-level runtime data directory."""
    return ensure_dir(get_config_path().parent)


def get_runtime_subdir(name: str) -> Path:
    """Return a named runtime subdirectory under the instance data dir."""
    return ensure_dir(get_data_dir() / name)


def get_media_dir(channel: str | None = None) -> Path:
    """Return the media directory, optionally namespaced per channel."""
    base = get_runtime_subdir("media")
    return ensure_dir(base / channel) if channel else base


def get_cron_dir() -> Path:
    """Return the cron storage directory."""
    return get_runtime_subdir("cron")


def get_orchestration_dir() -> Path:
    """Return the orchestration storage directory."""
    return get_runtime_subdir("orchestration")


def get_logs_dir() -> Path:
    """Return the logs directory."""
    return get_runtime_subdir("logs")


def get_workspace_path(workspace: str | None = None) -> Path:
    """Resolve and ensure the agent workspace path."""
    path = resolve_path(Path(workspace).expanduser() if workspace else _app_home_dir() / "workspace")
    if not workspace:
        path = ensure_within(path, _app_home_dir())
    return ensure_dir(path)


def get_cli_history_path() -> Path:
    """Return the shared CLI history file path."""
    return _app_home_dir() / "history" / "cli_history"


def get_bridge_install_dir() -> Path:
    """Return the shared WhatsApp bridge installation directory."""
    return _app_home_dir() / "bridge"


def get_shared_skills_dir() -> Path:
    """Return the shared custom skills directory."""
    return ensure_dir(get_data_dir() / "skills")


def get_legacy_sessions_dir() -> Path:
    """Return the legacy global session directory used for migration fallback."""
    return _app_home_dir() / "sessions"
