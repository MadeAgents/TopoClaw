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

"""Device manager for WebSocket connections."""

from typing import Dict, Any
from fastapi import WebSocket
from loguru import logger


class DeviceManager:
    """Manages WebSocket connections for devices."""

    def __init__(self):
        self.connections: Dict[str, WebSocket] = {}

    async def connect(self, device_id: str, websocket: WebSocket):
        """Accept connection and store it."""
        await websocket.accept()
        self.connections[device_id] = websocket
        logger.info(f"Device connected: {device_id}")

    def disconnect(self, device_id: str):
        """Remove connection."""
        if device_id in self.connections:
            del self.connections[device_id]
            logger.info(f"Device disconnected: {device_id}")

    async def send_message(self, device_id: str, message: Dict[str, Any]) -> bool:
        """Send JSON message to a device."""
        if device_id in self.connections:
            try:
                await self.connections[device_id].send_json(message)
                return True
            except Exception as e:
                logger.error(f"Failed to send message to device {device_id}: {e}")
                self.disconnect(device_id)
                return False
        return False

    def is_connected(self, device_id: str) -> bool:
        """Check if device is connected."""
        return device_id in self.connections
