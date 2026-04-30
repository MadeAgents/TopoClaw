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

"""Screenshot tool for GUI agents."""

import base64
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool


class ScreenshotTool(Tool):
    """Tool to take screenshots for GUI agents."""

    def __init__(self, workspace: Path, agent_type: str = "mobile"):
        """Initialize screenshot tool.

        Args:
            workspace: Workspace directory to save screenshots
            agent_type: Type of agent ("mobile" or "computer")
        """
        self.workspace = workspace
        self.agent_type = agent_type
        self.screenshot_dir = workspace / "screenshots"
        self.screenshot_dir.mkdir(parents=True, exist_ok=True)

    @property
    def name(self) -> str:
        return "screenshot"

    @property
    def description(self) -> str:
        return (
            "Take a screenshot of the current screen. "
            "For mobile agent, captures the mobile device screen. "
            "For computer agent, captures the desktop screen. "
            "Returns the path to the saved screenshot image."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {},
            "required": [],
        }

    async def execute(self, **kwargs: Any) -> str:
        """Take a screenshot and return the file path."""
        import time
        from datetime import datetime

        # Generate filename with timestamp
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{self.agent_type}_screenshot_{timestamp}.png"
        filepath = self.screenshot_dir / filename

        try:
            if self.agent_type == "mobile":
                # Take mobile screenshot (placeholder - needs actual implementation)
                # This would typically use ADB, Appium, or similar
                screenshot_path = await self._take_mobile_screenshot(filepath)
            else:
                # Take desktop screenshot
                screenshot_path = await self._take_desktop_screenshot(filepath)

            logger.info("Screenshot saved: {}", screenshot_path)
            return str(screenshot_path)
        except Exception as e:
            logger.error("Failed to take screenshot: {}", e)
            return f"Error: Failed to take screenshot - {str(e)}"

    async def _take_mobile_screenshot(self, filepath: Path) -> Path:
        """Take mobile device screenshot."""
        # Placeholder - implement using ADB, Appium, or similar
        # For now, return a placeholder path
        raise NotImplementedError(
            "Mobile screenshot not implemented. "
            "Please implement using ADB, Appium, or your mobile automation framework."
        )

    async def _take_desktop_screenshot(self, filepath: Path) -> Path:
        """Take desktop screenshot."""
        try:
            from PIL import ImageGrab

            # Take screenshot
            screenshot = ImageGrab.grab()
            screenshot.save(filepath, "PNG")
            return filepath
        except ImportError:
            # Fallback to other methods
            try:
                import mss
                with mss.mss() as sct:
                    # Capture entire screen
                    sct.shot(output=str(filepath))
                return filepath
            except ImportError:
                raise ImportError(
                    "Screenshot requires PIL (Pillow) or mss. "
                    "Install with: pip install pillow or pip install mss"
                )
