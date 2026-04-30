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

"""Skills service for listing and reading skill metadata."""

from __future__ import annotations

import asyncio
import base64
import binascii
import io
import re
import shlex
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

from topoclaw.agent.skills import SkillsLoader
from topoclaw.config.paths import get_data_dir, get_shared_skills_dir
from topoclaw.service.deeplink_catalog_service import (
    DeeplinkCatalogService,
    get_default_deeplink_catalog_service,
)


class SkillsService:
    """Service facade for workspace/default skills visibility."""

    def __init__(self, workspace: Path) -> None:
        self.workspace = workspace
        self.shared_skills_dir = get_shared_skills_dir()
        self.data_dir = get_data_dir()
        self.deeplink_catalog_service: DeeplinkCatalogService = get_default_deeplink_catalog_service()

    def _loader(self) -> SkillsLoader:
        return SkillsLoader(self.workspace)

    async def list_skills(self) -> dict[str, Any]:
        loader = self._loader()
        always_set = set(loader.get_always_skills())
        skills = loader.list_skills(filter_unavailable=False)
        return {
            "count": len(skills),
            "skills": [
                {
                    "name": item["name"],
                    "always": item["name"] in always_set,
                    "description": loader._get_skill_description(item["name"]),
                    "path": item["path"],
                    "source": item["source"],
                }
                for item in skills
            ],
        }

    async def get_skill(self, name: str) -> dict[str, Any]:
        loader = self._loader()
        content = loader.load_skill(name)
        if content:
            meta = loader.get_skill_metadata(name) or {}
            return {
                "name": name,
                "always": name in set(loader.get_always_skills()),
                "description": loader._get_skill_description(name),
                "path": next((s["path"] for s in loader.list_skills(filter_unavailable=False) if s["name"] == name), ""),
                "metadata": meta,
                "content": content,
            }
        return {"error": f"skill not found: {name}"}

    async def download_skill_from_clawhub(
        self,
        *,
        source_url: str,
        skill_name: str | None = None,
        overwrite: bool = False,
    ) -> dict[str, Any]:
        """
        Download a skill package from ClawHub-like URL and install into shared skills.

        Supports:
        - Direct SKILL.md URL
        - ZIP package URL containing SKILL.md
        """
        parsed = urllib.parse.urlparse(source_url)
        if parsed.scheme not in {"http", "https"}:
            return {"error": "source_url must be an http(s) URL"}

        with tempfile.TemporaryDirectory(prefix="skill_download_") as temp_dir_raw:
            temp_dir = Path(temp_dir_raw)
            payload_path = temp_dir / "payload.bin"
            with urllib.request.urlopen(source_url, timeout=30) as resp:
                payload_path.write_bytes(resp.read())

            extracted_root = temp_dir / "extracted"
            extracted_root.mkdir(parents=True, exist_ok=True)

            is_zip = zipfile.is_zipfile(payload_path)
            if is_zip:
                with zipfile.ZipFile(payload_path, "r") as zf:
                    zf.extractall(extracted_root)
            else:
                # Treat content as direct SKILL.md markdown file.
                direct_skill = extracted_root / "SKILL.md"
                direct_skill.write_bytes(payload_path.read_bytes())

            skill_md = self._find_skill_md(extracted_root)
            if not skill_md:
                return {"error": "downloaded package does not contain SKILL.md"}

            install_name = self._normalize_skill_name(skill_name or skill_md.parent.name)
            if not install_name:
                return {"error": "invalid skill name"}

            target_dir = self.shared_skills_dir / install_name

            if target_dir.exists():
                if not overwrite:
                    return {"error": f"skill already exists: {install_name}", "name": install_name}
                shutil.rmtree(target_dir)

            source_dir = skill_md.parent
            shutil.copytree(source_dir, target_dir)

            audit_status = "passed"
            audit_message = None
            try:
                await self.audit_downloaded_skill(target_dir)
            except NotImplementedError:
                audit_status = "pending"
                audit_message = "Security audit hook not implemented yet."

            return {
                "status": "ok",
                "name": install_name,
                "path": str(target_dir / "SKILL.md"),
                "audit_status": audit_status,
                "audit_message": audit_message,
            }

    @staticmethod
    def _is_within(path: Path, root: Path) -> bool:
        try:
            path.relative_to(root)
            return True
        except ValueError:
            return False

    @staticmethod
    def _safe_extract_zip(zip_file: zipfile.ZipFile, destination: Path) -> None:
        for member in zip_file.infolist():
            if member.is_dir():
                continue
            member_path = destination / member.filename
            resolved = member_path.resolve()
            if not SkillsService._is_within(resolved, destination):
                raise ValueError(f"zip entry escapes destination: {member.filename}")
            resolved.parent.mkdir(parents=True, exist_ok=True)
            with zip_file.open(member, "r") as src, resolved.open("wb") as dst:
                shutil.copyfileobj(src, dst)

    @staticmethod
    def _iter_packable_files(root: Path) -> list[Path]:
        excluded_dirs = {".git", ".svn", ".hg", "__pycache__", "node_modules", ".DS_Store"}
        files: list[Path] = []
        for file_path in root.rglob("*"):
            rel_parts = file_path.relative_to(root).parts
            if any(part in excluded_dirs for part in rel_parts):
                continue
            if file_path.is_symlink():
                raise ValueError(f"symlink not allowed in packaged skill: {file_path}")
            if file_path.is_file():
                files.append(file_path)
        return files

    async def export_skill_package(self, skill_name: str) -> dict[str, Any]:
        if not skill_name or not skill_name.strip():
            return {"error": "skill_name is required"}

        loader = self._loader()
        skills = loader.list_skills(filter_unavailable=False)
        target_skill = next((s for s in skills if s["name"] == skill_name), None)
        if not target_skill:
            return {"error": f"skill not found: {skill_name}"}

        skill_path_raw = target_skill.get("path")
        if not skill_path_raw:
            return {"error": f"invalid skill path: {skill_name}"}
        skill_path = Path(skill_path_raw).resolve()
        skill_dir = skill_path if skill_path.is_dir() else skill_path.parent
        skill_md = skill_dir / "SKILL.md"
        if not skill_md.exists():
            return {"error": f"SKILL.md not found for skill: {skill_name}"}

        try:
            files = self._iter_packable_files(skill_dir)
            buf = io.BytesIO()
            folder_name = self._normalize_skill_name(skill_name) or skill_dir.name
            with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
                for file_path in files:
                    arcname = (Path(folder_name) / file_path.relative_to(skill_dir)).as_posix()
                    zf.write(file_path, arcname)
            package_b64 = base64.b64encode(buf.getvalue()).decode("ascii")
            return {
                "status": "ok",
                "name": skill_name,
                "source": target_skill.get("source"),
                "path": str(skill_dir),
                "package_base64": package_b64,
                "package_file_name": f"{folder_name}.zip",
            }
        except Exception as exc:
            return {"error": f"failed to export skill package: {exc}"}

    async def import_skill_package(
        self,
        *,
        package_base64: str,
        prefer_name: str | None = None,
        overwrite: bool = False,
    ) -> dict[str, Any]:
        raw_b64 = (package_base64 or "").strip()
        if not raw_b64:
            return {"error": "package_base64 is required"}

        try:
            package_bytes = base64.b64decode(raw_b64, validate=True)
        except (ValueError, binascii.Error):
            return {"error": "package_base64 is not valid base64"}

        with tempfile.TemporaryDirectory(prefix="skill_import_") as temp_dir_raw:
            temp_dir = Path(temp_dir_raw)
            extract_root = temp_dir / "extracted"
            extract_root.mkdir(parents=True, exist_ok=True)
            try:
                with zipfile.ZipFile(io.BytesIO(package_bytes), "r") as zf:
                    self._safe_extract_zip(zf, extract_root)
            except zipfile.BadZipFile:
                return {"error": "invalid skill package zip"}
            except ValueError as exc:
                return {"error": str(exc)}

            skill_md = self._find_skill_md(extract_root)
            if not skill_md:
                return {"error": "skill package does not contain SKILL.md"}

            source_dir = skill_md.parent
            install_name = self._normalize_skill_name(prefer_name or source_dir.name)
            if not install_name:
                return {"error": "invalid skill name"}

            target_dir = self.shared_skills_dir / install_name
            if target_dir.exists():
                if not overwrite:
                    return {"error": f"skill already exists: {install_name}", "name": install_name}
                shutil.rmtree(target_dir)

            shutil.copytree(source_dir, target_dir)

            audit_status = "passed"
            audit_message = None
            try:
                await self.audit_downloaded_skill(target_dir)
            except NotImplementedError:
                audit_status = "pending"
                audit_message = "Security audit hook not implemented yet."

            return {
                "status": "ok",
                "name": install_name,
                "path": str(target_dir / "SKILL.md"),
                "audit_status": audit_status,
                "audit_message": audit_message,
            }

    @staticmethod
    def _find_skill_md(root: Path) -> Path | None:
        candidates = list(root.rglob("SKILL.md"))
        if not candidates:
            return None
        # Prefer shallower path for deterministic behavior.
        candidates.sort(key=lambda p: (len(p.parts), str(p)))
        return candidates[0]

    @staticmethod
    def _normalize_skill_name(raw: str) -> str:
        value = (raw or "").strip().lower()
        value = re.sub(r"[^a-z0-9._-]+", "-", value)
        value = value.strip("._-")
        return value

    async def update_skills(self) -> dict[str, Any]:
        """
        Refresh all shared skills by checking for updates from ClawHub.

        Uses clawhub CLI to update all installed skills in the shared topoclaw data dir.

        Returns:
            Result dict with status or error.
        """
        if not shutil.which("npx"):
            return {"error": "npx not found. Please install Node.js to use clawhub CLI."}

        workspace_path = str(self.data_dir.resolve())
        if sys.platform == "win32":
            safe_path = '"' + workspace_path.replace('"', '""') + '"'
        else:
            safe_path = shlex.quote(workspace_path)
        command = f"npx --yes clawhub@latest update --all --workdir {safe_path}"

        try:
            process = await asyncio.create_subprocess_shell(
                command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=workspace_path,
            )

            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=120,
            )

            if process.returncode != 0:
                error_msg = stderr.decode("utf-8", errors="replace").strip() if stderr else "unknown error"
                return {"error": f"clawhub update failed: {error_msg}"}

            output = stdout.decode("utf-8", errors="replace").strip()
            return {
                "status": "ok",
                "message": "skills refreshed successfully",
                "output": output,
            }

        except asyncio.TimeoutError:
            return {"error": "clawhub update timed out after 120 seconds"}
        except FileNotFoundError:
            return {"error": "npx command not found"}
        except Exception as e:
            return {"error": f"failed to refresh skills: {e}"}

    async def remove_skill(self, skill_name: str) -> dict[str, Any]:
        """
        Remove a skill from shared custom skills.

        Only shared custom skills can be removed. Built-in skills are protected.

        Args:
            skill_name: Name of the skill to remove.

        Returns:
            Result dict with status or error.
        """
        if not skill_name or not skill_name.strip():
            return {"error": "skill_name is required"}

        loader = self._loader()
        skills = loader.list_skills(filter_unavailable=False)
        target_skill = next((s for s in skills if s["name"] == skill_name), None)

        if not target_skill:
            return {"error": f"skill not found: {skill_name}"}

        if target_skill["source"] != "shared":
            return {"error": f"cannot remove {target_skill['source']} skill: {skill_name}"}

        skill_dir = self.shared_skills_dir / skill_name
        if not skill_dir.exists() or not skill_dir.is_dir():
            return {"error": f"skill directory not found: {skill_name}"}

        shutil.rmtree(skill_dir)

        return {
            "status": "ok",
            "name": skill_name,
            "removed_path": str(skill_dir),
        }

    async def audit_downloaded_skill(self, skill_dir: Path) -> None:
        """
        Security audit hook for downloaded skills.

        TODO: integrate a real security scanner/checklist here.
        """
        _ = skill_dir
        raise NotImplementedError("Security audit is not implemented yet.")

    async def handle_ws_message(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        """Handle skills-related websocket messages."""
        msg_type = str(msg.get("type") or "").strip()
        if msg_type == "skills_list":
            result = await self.list_skills()
            return {"type": "skills_listed", "ok": True, **result}

        if msg_type == "skill_get":
            name = str(msg.get("name") or msg.get("skill_name") or "").strip()
            if not name:
                return {"type": "skill_detail", "ok": False, "error": "name is required"}
            result = await self.get_skill(name)
            if "error" in result:
                return {"type": "skill_detail", "ok": False, "error": result["error"]}
            return {"type": "skill_detail", "ok": True, **result}

        if msg_type == "skills_update":
            result = await self.update_skills()
            if "error" in result:
                return {"type": "skills_updated", "ok": False, "error": result["error"]}
            return {"type": "skills_updated", "ok": True, **result}

        if msg_type == "skill_download":
            source_url = str(msg.get("source_url") or "").strip()
            skill_name_raw = msg.get("skill_name")
            skill_name = str(skill_name_raw).strip() if isinstance(skill_name_raw, str) else None
            overwrite = bool(msg.get("overwrite", False))
            if not source_url:
                return {"type": "skill_downloaded", "ok": False, "error": "source_url is required"}
            result = await self.download_skill_from_clawhub(
                source_url=source_url,
                skill_name=skill_name,
                overwrite=overwrite,
            )
            if "error" in result:
                return {"type": "skill_downloaded", "ok": False, "error": result["error"]}
            return {"type": "skill_downloaded", "ok": True, **result}

        if msg_type == "skill_remove":
            skill_name = str(msg.get("skill_name") or msg.get("name") or "").strip()
            if not skill_name:
                return {"type": "skill_removed", "ok": False, "error": "skill_name is required"}
            result = await self.remove_skill(skill_name)
            if "error" in result:
                return {"type": "skill_removed", "ok": False, "error": result["error"]}
            return {"type": "skill_removed", "ok": True, **result}

        if msg_type == "skill_export_package":
            skill_name = str(msg.get("skill_name") or msg.get("name") or "").strip()
            if not skill_name:
                return {"type": "skill_exported_package", "ok": False, "error": "skill_name is required"}
            result = await self.export_skill_package(skill_name)
            if "error" in result:
                return {"type": "skill_exported_package", "ok": False, "error": result["error"]}
            return {"type": "skill_exported_package", "ok": True, **result}

        if msg_type == "skill_import_package":
            package_base64 = str(msg.get("package_base64") or "").strip()
            prefer_name_raw = msg.get("prefer_name")
            prefer_name = str(prefer_name_raw).strip() if isinstance(prefer_name_raw, str) else None
            overwrite = bool(msg.get("overwrite", False))
            result = await self.import_skill_package(
                package_base64=package_base64,
                prefer_name=prefer_name,
                overwrite=overwrite,
            )
            if "error" in result:
                return {"type": "skill_imported_package", "ok": False, "error": result["error"]}
            return {"type": "skill_imported_package", "ok": True, **result}

        if msg_type == "deeplink_catalog_sync":
            payload = msg.get("payload")
            if not isinstance(payload, dict):
                return {"type": "deeplink_catalog_synced", "ok": False, "error": "payload is required"}
            result = self.deeplink_catalog_service.sync_catalog(payload)
            return {"type": "deeplink_catalog_synced", **result}

        if msg_type == "deeplink_catalog_status":
            result = self.deeplink_catalog_service.status()
            return {"type": "deeplink_catalog_status", **result}

        return None

    async def sync_deeplink_catalog(self, payload: dict[str, Any]) -> dict[str, Any]:
        return self.deeplink_catalog_service.sync_catalog(payload)

    async def deeplink_catalog_status(self) -> dict[str, Any]:
        return self.deeplink_catalog_service.status()
