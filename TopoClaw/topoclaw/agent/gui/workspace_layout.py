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

"""Workspace subpaths for GUI runtime files (under ``<workspace>/gui_temp/``)."""

from __future__ import annotations

from pathlib import Path

GUI_TEMP_ROOT = "gui_temp"


def gui_temp_root(workspace: Path | str) -> Path:
    """``<workspace>/gui_temp`` — parent for GUI temp dirs."""
    return Path(workspace) / GUI_TEMP_ROOT


def gui_temp_screenshots(workspace: Path | str) -> Path:
    """HTTP / bridge screenshot staging (NamedTemporaryFile, etc.)."""
    return gui_temp_root(workspace) / "temp_screenshots"


def gui_temp_rendered(workspace: Path | str) -> Path:
    """Short-lived action-annotated screenshots before base64 encode."""
    return gui_temp_root(workspace) / "temp_rendered"


def gui_prompts_dir(workspace: Path | str) -> Path:
    """Optional Jinja overrides (e.g. ``mobile_prompt.j2``); searched before package templates."""
    return gui_temp_root(workspace) / "gui_prompts"
