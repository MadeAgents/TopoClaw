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

"""Device identity and online status registry.

Maps DeviceID -> (ConnID, DeviceType, Capabilities).
Infrastructure layer called by ChatService for cross-device
coordination and multi-device status queries.
"""

from __future__ import annotations

import asyncio
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.config.paths import get_data_dir
from topoclaw.utils.path_guard import ensure_within, resolve_path


class DeviceRegistry:
    """Device identity store – infrastructure layer below ChatService.

    State file: ``<data_dir>/device_registry.json`` (typically ``~/.topoclaw/device_registry.json``),
    not under workspace. Legacy path ``<workspace>/.topoclaw/device_registry.json`` is migrated once.
    """

    def __init__(self, workspace: Path | str | None = None) -> None:
        self._devices: dict[str, dict[str, Any]] = {}
        self._conn_to_device: dict[str, str] = {}
        self._state_file = resolve_path(get_data_dir() / "device_registry.json")
        self._maybe_migrate_legacy_workspace(Path(workspace) if workspace else None)
        self._lock = asyncio.Lock()
        self._load_state()

    def _maybe_migrate_legacy_workspace(self, workspace: Path | None) -> None:
        """Move registry from old ``workspace/.topoclaw/device_registry.json`` if present."""
        if not workspace:
            return
        workspace_root = resolve_path(workspace)
        legacy = ensure_within(workspace_root / ".topoclaw" / "device_registry.json", workspace_root)
        if not legacy.exists() or self._state_file.exists():
            return
        try:
            self._state_file.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(legacy), str(self._state_file))
            logger.info(
                "Migrated device registry from {} to {}",
                legacy,
                self._state_file,
            )
        except OSError as e:
            logger.warning("Could not migrate device registry from {}: {}", legacy, e)

    @staticmethod
    def _now_iso() -> str:
        return datetime.now(timezone.utc).isoformat()

    def _load_state(self) -> None:
        if not self._state_file.exists():
            return
        try:
            data = json.loads(self._state_file.read_text(encoding="utf-8"))
            raw_devices = data.get("devices")
            if not isinstance(raw_devices, dict):
                return
            for device_id, info in raw_devices.items():
                if not isinstance(info, dict):
                    continue
                normalized_device_id = str(device_id or "").strip()
                if not normalized_device_id:
                    continue
                normalized_conn_id = str(info.get("conn_id") or "").strip()
                online = bool(info.get("online")) and bool(normalized_conn_id)
                thread_ids = info.get("thread_ids")
                if not isinstance(thread_ids, list):
                    thread_ids = []
                normalized_threads = sorted(
                    {str(tid or "").strip() for tid in thread_ids if str(tid or "").strip()}
                )
                device_info: dict[str, Any] = {
                    "device_id": normalized_device_id,
                    "conn_id": normalized_conn_id if online else None,
                    "online": online,
                    "device_type": str(info.get("device_type") or "unknown").strip().lower() or "unknown",
                    "supports_code_execute": bool(info.get("supports_code_execute")),
                    "supports_gui_execute": bool(info.get("supports_gui_execute")),
                    "thread_ids": normalized_threads,
                    "registered_at": str(info.get("registered_at") or self._now_iso()),
                    "last_seen_at": str(info.get("last_seen_at") or self._now_iso()),
                    "last_offline_at": str(info.get("last_offline_at") or ""),
                }
                self._devices[normalized_device_id] = device_info
                if online and normalized_conn_id:
                    self._conn_to_device[normalized_conn_id] = normalized_device_id
        except Exception:
            # Corrupted state file should not block service startup.
            return

    def _persist_state_locked(self) -> None:
        self._state_file.parent.mkdir(parents=True, exist_ok=True)
        devices_payload = {did: dict(info) for did, info in self._devices.items()}
        payload = {
            "updated_at": self._now_iso(),
            "devices": devices_payload,
        }
        self._state_file.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    async def register_device(
        self,
        device_id: str,
        conn_id: str,
        device_type: str = "unknown",
        *,
        supports_code_execute: bool = False,
        supports_gui_execute: bool = False,
    ) -> None:
        normalized_device_id = (device_id or "").strip()
        normalized_conn_id = (conn_id or "").strip()
        normalized_device_type = (device_type or "unknown").strip().lower() or "unknown"
        if not normalized_device_id or not normalized_conn_id:
            return
        async with self._lock:
            old_info = self._devices.get(normalized_device_id)
            old_conn_id = str((old_info or {}).get("conn_id") or "")
            if old_conn_id:
                self._conn_to_device.pop(old_conn_id, None)

            previous_device_for_conn = self._conn_to_device.get(normalized_conn_id)
            if previous_device_for_conn and previous_device_for_conn != normalized_device_id:
                previous_info = self._devices.get(previous_device_for_conn)
                if previous_info is not None:
                    previous_info["online"] = False
                    previous_info["conn_id"] = None
                    previous_info["thread_ids"] = []
                    previous_info["last_offline_at"] = self._now_iso()

            registered_at = str((old_info or {}).get("registered_at") or self._now_iso())
            existing_threads = (old_info or {}).get("thread_ids")
            if not isinstance(existing_threads, list):
                existing_threads = []
            self._devices[normalized_device_id] = {
                "device_id": normalized_device_id,
                "conn_id": normalized_conn_id,
                "online": True,
                "device_type": normalized_device_type,
                "supports_code_execute": supports_code_execute,
                "supports_gui_execute": supports_gui_execute,
                "thread_ids": sorted(
                    {str(tid or "").strip() for tid in existing_threads if str(tid or "").strip()}
                ),
                "registered_at": registered_at,
                "last_seen_at": self._now_iso(),
                "last_offline_at": "",
            }
            self._conn_to_device[normalized_conn_id] = normalized_device_id
            self._persist_state_locked()

    async def mark_offline(self, device_id: str, *, expected_conn_id: str | None = None) -> None:
        """Mark device offline.

        If *expected_conn_id* is given, only mark offline when it matches
        (prevents marking a device offline after it already reconnected)."""
        normalized_device_id = (device_id or "").strip()
        normalized_expected_conn = (expected_conn_id or "").strip() or None
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            if info is None:
                return
            conn_id = str(info.get("conn_id") or "")
            if normalized_expected_conn and conn_id != normalized_expected_conn:
                return
            info["online"] = False
            info["conn_id"] = None
            info["thread_ids"] = []
            info["last_offline_at"] = self._now_iso()
            if conn_id:
                self._conn_to_device.pop(conn_id, None)
            self._persist_state_locked()

    async def mark_offline_by_conn(self, conn_id: str) -> str | None:
        normalized_conn_id = (conn_id or "").strip()
        if not normalized_conn_id:
            return None
        async with self._lock:
            device_id = self._conn_to_device.pop(normalized_conn_id, None)
            if not device_id:
                return None
            info = self._devices.get(device_id)
            if info and str(info.get("conn_id") or "") == normalized_conn_id:
                info["online"] = False
                info["conn_id"] = None
                info["thread_ids"] = []
                info["last_offline_at"] = self._now_iso()
                self._persist_state_locked()
            return device_id

    async def get_connection(self, device_id: str) -> str | None:
        normalized_device_id = (device_id or "").strip()
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            if not info or not bool(info.get("online")):
                return None
            return str(info.get("conn_id") or "") or None

    async def get_device_id_by_conn(self, conn_id: str) -> str | None:
        normalized_conn_id = (conn_id or "").strip()
        async with self._lock:
            if not normalized_conn_id:
                return None
            return self._conn_to_device.get(normalized_conn_id)

    async def get_device_info(self, device_id: str) -> dict[str, Any] | None:
        normalized_device_id = (device_id or "").strip()
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            return dict(info) if info else None

    async def list_devices(self, *, device_type: str | None = None) -> list[dict[str, Any]]:
        async with self._lock:
            devices = [dict(v) for v in self._devices.values() if bool(v.get("online"))]
        if device_type:
            normalized = device_type.strip().lower()
            return [d for d in devices if d.get("device_type") == normalized]
        return devices

    async def bind_thread(self, device_id: str, thread_id: str) -> bool:
        normalized_device_id = (device_id or "").strip()
        normalized_thread_id = (thread_id or "").strip()
        if not normalized_device_id or not normalized_thread_id:
            return False
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            if not info:
                return False
            thread_ids = info.get("thread_ids")
            if not isinstance(thread_ids, list):
                thread_ids = []
            merged = sorted({*{str(tid or "").strip() for tid in thread_ids}, normalized_thread_id} - {""})
            info["thread_ids"] = merged
            info["last_seen_at"] = self._now_iso()
            self._persist_state_locked()
        return True

    async def unbind_thread(self, device_id: str, thread_id: str) -> bool:
        normalized_device_id = (device_id or "").strip()
        normalized_thread_id = (thread_id or "").strip()
        if not normalized_device_id or not normalized_thread_id:
            return False
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            if not info:
                return False
            thread_ids = info.get("thread_ids")
            if not isinstance(thread_ids, list):
                thread_ids = []
            remaining = sorted(
                {str(tid or "").strip() for tid in thread_ids if str(tid or "").strip()}
                - {normalized_thread_id}
            )
            info["thread_ids"] = remaining
            self._persist_state_locked()
        return True

    async def clear_threads(self, device_id: str) -> None:
        normalized_device_id = (device_id or "").strip()
        if not normalized_device_id:
            return
        async with self._lock:
            info = self._devices.get(normalized_device_id)
            if not info:
                return
            info["thread_ids"] = []
            self._persist_state_locked()

    async def snapshot(self) -> dict[str, Any]:
        async with self._lock:
            online_devices = [v for v in self._devices.values() if bool(v.get("online"))]
            total = len(online_devices)
            by_type: dict[str, int] = {}
            for info in online_devices:
                dt = str(info.get("device_type") or "unknown")
                by_type[dt] = by_type.get(dt, 0) + 1
        return {
            "unique_device_count": total,
            "by_device_type": by_type,
        }
