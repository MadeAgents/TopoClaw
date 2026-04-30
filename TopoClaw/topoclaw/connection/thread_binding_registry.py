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

"""Thread-device binding registry.

Stores many-to-many relations between thread_id and device_id.
No connection lifecycle or transport logic is handled here.
"""

from __future__ import annotations

import asyncio
from typing import Any


class ThreadBindingRegistry:
    """Infrastructure registry for thread <-> device bindings."""

    def __init__(self) -> None:
        self._thread_to_devices: dict[str, set[str]] = {}
        self._device_to_threads: dict[str, set[str]] = {}
        self._lock = asyncio.Lock()

    async def bind(self, thread_id: str, device_id: str) -> bool:
        normalized_thread = (thread_id or "").strip()
        normalized_device = (device_id or "").strip()
        if not normalized_thread or not normalized_device:
            return False
        async with self._lock:
            self._thread_to_devices.setdefault(normalized_thread, set()).add(normalized_device)
            self._device_to_threads.setdefault(normalized_device, set()).add(normalized_thread)
        return True

    async def unbind(self, thread_id: str, device_id: str) -> bool:
        normalized_thread = (thread_id or "").strip()
        normalized_device = (device_id or "").strip()
        if not normalized_thread or not normalized_device:
            return False
        async with self._lock:
            devices = self._thread_to_devices.get(normalized_thread)
            if devices:
                devices.discard(normalized_device)
                if not devices:
                    self._thread_to_devices.pop(normalized_thread, None)
            threads = self._device_to_threads.get(normalized_device)
            if threads:
                threads.discard(normalized_thread)
                if not threads:
                    self._device_to_threads.pop(normalized_device, None)
        return True

    async def unbind_all_for_device(self, device_id: str) -> None:
        normalized_device = (device_id or "").strip()
        if not normalized_device:
            return
        async with self._lock:
            threads = self._device_to_threads.pop(normalized_device, set())
            for thread_id in threads:
                devices = self._thread_to_devices.get(thread_id)
                if not devices:
                    continue
                devices.discard(normalized_device)
                if not devices:
                    self._thread_to_devices.pop(thread_id, None)

    async def get_devices_for_thread(self, thread_id: str) -> list[str]:
        normalized_thread = (thread_id or "").strip()
        if not normalized_thread:
            return []
        async with self._lock:
            return list(self._thread_to_devices.get(normalized_thread, set()))

    async def snapshot(self) -> dict[str, Any]:
        async with self._lock:
            bound_thread_count = len(self._thread_to_devices)
            bound_connections_by_thread = {tid: len(devices) for tid, devices in self._thread_to_devices.items()}
        return {
            "bound_thread_count": bound_thread_count,
            "bound_connections_by_thread": bound_connections_by_thread,
        }
