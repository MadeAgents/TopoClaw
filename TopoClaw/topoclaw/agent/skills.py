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

"""Skills loader for agent capabilities and workspace ``skills_filter.json``."""

from __future__ import annotations

import json
import os
import re
import shutil
from pathlib import Path
from typing import Any

from topoclaw.config.paths import get_shared_skills_dir

# Default builtin skills directory (relative to this file)
BUILTIN_SKILLS_DIR = Path(__file__).parent.parent / "skills"

# Filenames (same directory as SKILL.md) to expose in build_skills_summary as <data_file> for read_file.
_SKILL_ASSET_FILENAMES: tuple[str, ...] = ("stock_code.csv",)


class SkillsLoader:
    """
    Loader for agent skills.

    Skills are markdown files (SKILL.md) that teach the agent how to use
    specific tools or perform certain tasks.
    """

    def __init__(
        self,
        workspace: Path,
        builtin_skills_dir: Path | None = None,
        *,
        skill_exclude: frozenset[str] | None = None,
        skill_include: frozenset[str] | None = None,
    ):
        self.workspace = workspace
        self.shared_skills = get_shared_skills_dir()
        # Legacy fallback: old per-workspace custom skills.
        self.workspace_skills_legacy = workspace / "skills"
        self.builtin_skills = builtin_skills_dir or BUILTIN_SKILLS_DIR
        self._skill_exclude = skill_exclude or frozenset()
        # When non-empty, only these skill names appear in listings / always skills.
        self._skill_include = skill_include

    def _skill_visible(self, name: str) -> bool:
        if self._skill_include is not None and len(self._skill_include) > 0:
            return name in self._skill_include
        return name not in self._skill_exclude

    def list_skills(self, filter_unavailable: bool = True) -> list[dict[str, str]]:
        """
        List all available skills.

        Args:
            filter_unavailable: If True, filter out skills with unmet requirements.

        Returns:
            List of skill info dicts with 'name', 'path', 'source'.
        """
        skills = []

        # Shared custom skills (highest priority)
        if self.shared_skills.exists():
            for skill_dir in self.shared_skills.iterdir():
                if skill_dir.is_dir():
                    skill_file = skill_dir / "SKILL.md"
                    if skill_file.exists():
                        skills.append({"name": skill_dir.name, "path": str(skill_file), "source": "shared"})

        # Legacy per-workspace skills (fallback for backward compatibility)
        if self.workspace_skills_legacy.exists():
            for skill_dir in self.workspace_skills_legacy.iterdir():
                if skill_dir.is_dir():
                    skill_file = skill_dir / "SKILL.md"
                    if skill_file.exists() and not any(s["name"] == skill_dir.name for s in skills):
                        skills.append({"name": skill_dir.name, "path": str(skill_file), "source": "workspace_legacy"})

        # Built-in skills
        if self.builtin_skills and self.builtin_skills.exists():
            for skill_dir in self.builtin_skills.iterdir():
                if skill_dir.is_dir():
                    skill_file = skill_dir / "SKILL.md"
                    if skill_file.exists() and not any(s["name"] == skill_dir.name for s in skills):
                        skills.append({"name": skill_dir.name, "path": str(skill_file), "source": "builtin"})

        skills = [s for s in skills if self._skill_visible(s["name"])]
        # Filter by requirements
        if filter_unavailable:
            return [s for s in skills if self._check_requirements(self._get_skill_meta(s["name"]))]
        return skills

    def load_skill(self, name: str) -> str | None:
        """
        Load a skill by name.

        Args:
            name: Skill name (directory name).

        Returns:
            Skill content or None if not found.
        """
        # Check shared custom skills first
        shared_skill = self.shared_skills / name / "SKILL.md"
        if shared_skill.exists():
            return shared_skill.read_text(encoding="utf-8")

        # Legacy workspace fallback
        workspace_skill_legacy = self.workspace_skills_legacy / name / "SKILL.md"
        if workspace_skill_legacy.exists():
            return workspace_skill_legacy.read_text(encoding="utf-8")

        # Check built-in
        if self.builtin_skills:
            builtin_skill = self.builtin_skills / name / "SKILL.md"
            if builtin_skill.exists():
                return builtin_skill.read_text(encoding="utf-8")

        return None

    def load_skills_for_context(self, skill_names: list[str]) -> str:
        """
        Load specific skills for inclusion in agent context.

        Args:
            skill_names: List of skill names to load.

        Returns:
            Formatted skills content.
        """
        parts = []
        for name in skill_names:
            content = self.load_skill(name)
            if content:
                content = self._strip_frontmatter(content)
                parts.append(f"### Skill: {name}\n\n{content}")

        return "\n\n---\n\n".join(parts) if parts else ""

    def build_skills_summary(self) -> str:
        """
        Build a summary of all skills (name, description, path, availability).

        This is used for progressive loading - the agent can read the full
        skill content using read_file when needed.

        Returns:
            XML-formatted skills summary.
        """
        all_skills = self.list_skills(filter_unavailable=False)
        if not all_skills:
            return ""

        def escape_xml(s: str) -> str:
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        lines = ["<skills>"]
        for s in all_skills:
            name = escape_xml(s["name"])
            path = s["path"]
            desc = escape_xml(self._get_skill_description(s["name"]))
            skill_meta = self._get_skill_meta(s["name"])
            available = self._check_requirements(skill_meta)

            lines.append(f"  <skill available=\"{str(available).lower()}\">")
            lines.append(f"    <name>{name}</name>")
            lines.append(f"    <description>{desc}</description>")
            lines.append(f"    <location>{path}</location>")

            skill_dir = Path(path).parent
            for asset_name in _SKILL_ASSET_FILENAMES:
                asset_path = (skill_dir / asset_name).resolve()
                if asset_path.is_file():
                    lines.append(
                        f'    <data_file name="{escape_xml(asset_name)}">{escape_xml(str(asset_path))}</data_file>'
                    )

            # Show missing requirements for unavailable skills
            if not available:
                missing = self._get_missing_requirements(skill_meta)
                if missing:
                    lines.append(f"    <requires>{escape_xml(missing)}</requires>")

            lines.append("  </skill>")
        lines.append("</skills>")

        return "\n".join(lines)

    def _get_missing_requirements(self, skill_meta: dict) -> str:
        """Get a description of missing requirements."""
        missing = []
        requires = skill_meta.get("requires", {})
        for b in requires.get("bins", []):
            if not shutil.which(b):
                missing.append(f"CLI: {b}")
        for env in requires.get("env", []):
            if not os.environ.get(env):
                missing.append(f"ENV: {env}")
        return ", ".join(missing)

    def _get_skill_description(self, name: str) -> str:
        """Get the description of a skill from its frontmatter."""
        meta = self.get_skill_metadata(name)
        if meta and meta.get("description"):
            return meta["description"]
        return name  # Fallback to skill name

    def _strip_frontmatter(self, content: str) -> str:
        """Remove YAML frontmatter from markdown content."""
        if content.startswith("---"):
            match = re.match(r"^---\n.*?\n---\n", content, re.DOTALL)
            if match:
                return content[match.end():].strip()
        return content

    def _parse_topoclaw_metadata(self, raw: str) -> dict:
        """Parse skill metadata JSON from frontmatter (supports topoclaw and openclaw keys)."""
        try:
            data = json.loads(raw)
            return data.get("topoclaw", data.get("openclaw", {})) if isinstance(data, dict) else {}
        except (json.JSONDecodeError, TypeError):
            return {}

    def _check_requirements(self, skill_meta: dict) -> bool:
        """Check if skill requirements are met (bins, env vars)."""
        requires = skill_meta.get("requires", {})
        for b in requires.get("bins", []):
            if not shutil.which(b):
                return False
        for env in requires.get("env", []):
            if not os.environ.get(env):
                return False
        return True

    def _get_skill_meta(self, name: str) -> dict:
        """Get topoclaw metadata for a skill (cached in frontmatter)."""
        meta = self.get_skill_metadata(name) or {}
        return self._parse_topoclaw_metadata(meta.get("metadata", ""))

    def get_always_skills(self) -> list[str]:
        """Get skills marked as always=true that meet requirements."""
        result = []
        for s in self.list_skills(filter_unavailable=True):
            meta = self.get_skill_metadata(s["name"]) or {}
            skill_meta = self._parse_topoclaw_metadata(meta.get("metadata", ""))
            if skill_meta.get("always") or meta.get("always"):
                result.append(s["name"])
        return result

    def get_skill_metadata(self, name: str) -> dict | None:
        """
        Get metadata from a skill's frontmatter.

        Args:
            name: Skill name.

        Returns:
            Metadata dict or None.
        """
        content = self.load_skill(name)
        if not content:
            return None

        if content.startswith("---"):
            match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
            if match:
                # Simple YAML parsing
                metadata = {}
                for line in match.group(1).split("\n"):
                    if ":" in line:
                        key, value = line.split(":", 1)
                        metadata[key.strip()] = value.strip().strip('"\'')
                return metadata

        return None


# --- Per-workspace skill filter (``skills_filter.json`` in workspace root) ---

SKILLS_FILTER_FILENAME = "skills_filter.json"


def _normalize_name_list(raw: Any) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        return []
    out: list[str] = []
    for x in raw:
        if isinstance(x, str) and x.strip():
            out.append(x.strip())
    return out


def write_skills_filter(
    workspace: Path,
    *,
    include: list[str] | None = None,
    exclude: list[str] | None = None,
) -> Path:
    """Write ``skills_filter.json`` (overwrites). Returns path written."""
    doc = {
        "include": _normalize_name_list(include),
        "exclude": _normalize_name_list(exclude),
    }
    path = workspace / SKILLS_FILTER_FILENAME
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def read_skills_filter_file(path: Path) -> tuple[list[str] | None, list[str]]:
    """
    Parse filter file. Returns ``(include_or_none, exclude)``.
    Empty ``include`` array means no whitelist (same as omitting include).
    """
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None, []
    if not isinstance(data, dict):
        return None, []
    inc = _normalize_name_list(data.get("include"))
    exc = _normalize_name_list(data.get("exclude"))
    return (inc if inc else None, exc)


def resolve_skill_filters_for_loop(
    workspace: Path,
    *,
    fallback_include: list[str] | None = None,
    fallback_exclude: list[str] | None = None,
) -> tuple[list[str] | None, list[str] | None]:
    """
    Skill lists for ``AgentLoop`` / ``ContextBuilder``.

    If ``skills_filter.json`` exists in *workspace*, it is the source of truth.
    Otherwise use *fallback_* from config (named agents).
    """
    path = workspace / SKILLS_FILTER_FILENAME
    if path.is_file():
        inc, exc = read_skills_filter_file(path)
        return inc, (exc if exc else None)
    fi = fallback_include if fallback_include else None
    fe = fallback_exclude if fallback_exclude else None
    return fi, fe
