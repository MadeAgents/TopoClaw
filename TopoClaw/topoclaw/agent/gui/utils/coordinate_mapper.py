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

"""Coordinate mapping utilities."""

import re
from typing import Any

from loguru import logger

class CoordinateMapper:
    """Utility class for mapping relative coordinates to absolute screen coordinates."""

    @staticmethod
    def to_absolute(
        action_str: str,
        width: int | None,
        height: int | None,
        coord_range: int = 1000
    ) -> str:
        """Map relative coordinates (0-coord_range) to absolute screen coordinates.
        
        Args:
            action_str: Raw action string (e.g., "click[500,300]")
            width: Screen width in absolute pixels
            height: Screen height in absolute pixels
            coord_range: The maximum value of the relative coordinate space (default: 1000)
            
        Returns:
            Mapped action string with absolute coordinates
        """
        if not action_str or not width or not height:
            return action_str
            
        action_lower = action_str.lower().strip()
        
        # Helper to map a single coordinate
        def _map_x(x: int) -> int:
            if x <= coord_range:
                mapped = int((x / coord_range) * width)
                # Ensure it's strictly within the image width bounds
                return max(0, min(width - 1, mapped))
            # If the model already gave an absolute pixel value greater than range
            return max(0, min(width - 1, x))
            
        def _map_y(y: int) -> int:
            if y <= coord_range:
                mapped = int((y / coord_range) * height)
                # Ensure it's strictly within the image height bounds
                return max(0, min(height - 1, mapped))
            # If the model already gave an absolute pixel value greater than range
            return max(0, min(height - 1, y))
            
        # Extract coordinates helper
        def _extract_coords(s: str, num: int) -> list[int] | None:
            match = re.search(r"\[([^\]]+)\]", s)
            if not match:
                return None
            try:
                coords = [int(c.strip()) for c in match.group(1).split(",")[:num]]
                return coords if len(coords) == num else None
            except (ValueError, AttributeError):
                return None
        
        # click[x,y]
        if action_lower.startswith("click["):
            coords = _extract_coords(action_str, 2)
            if coords:
                return f"click[{_map_x(coords[0])},{_map_y(coords[1])}]"
                
        # swipe[x1,y1,x2,y2]
        elif action_lower.startswith("swipe["):
            coords = _extract_coords(action_str, 4)
            if coords:
                return f"swipe[{_map_x(coords[0])},{_map_y(coords[1])},{_map_x(coords[2])},{_map_y(coords[3])}]"
                
        # drag[x1,y1,x2,y2]
        elif action_lower.startswith("drag["):
            coords = _extract_coords(action_str, 4)
            if coords:
                return f"drag[{_map_x(coords[0])},{_map_y(coords[1])},{_map_x(coords[2])},{_map_y(coords[3])}]"
                
        # type[x,y,text]
        elif action_lower.startswith("type["):
            match = re.match(r"type\[(\d+),(\d+),(.+)\]", action_str, re.IGNORECASE)
            if match:
                return f"type[{_map_x(int(match.group(1)))},{_map_y(int(match.group(2)))},{match.group(3)}]"
                
        # long_press[x,y]
        elif action_lower.startswith("long_press["):
            coords = _extract_coords(action_str, 2)
            if coords:
                return f"long_press[{_map_x(coords[0])},{_map_y(coords[1])}]"
                
        # double_click[x,y]
        elif action_lower.startswith("double_click["):
            coords = _extract_coords(action_str, 2)
            if coords:
                return f"double_click[{_map_x(coords[0])},{_map_y(coords[1])}]"
                
        # right_click[x,y]
        elif action_lower.startswith("right_click["):
            coords = _extract_coords(action_str, 2)
            if coords:
                return f"right_click[{_map_x(coords[0])},{_map_y(coords[1])}]"
                
        # move[x,y]
        elif action_lower.startswith("move["):
            coords = _extract_coords(action_str, 2)
            if coords:
                return f"move[{_map_x(coords[0])},{_map_y(coords[1])}]"

        # scroll[x,y,delta] — 仅映射 x,y，delta 保持像素量
        elif action_lower.startswith("scroll["):
            m = re.match(r"scroll\[(-?\d+),(-?\d+),(-?\d+)\]", action_str, re.IGNORECASE)
            if m:
                x, y, delta = int(m.group(1)), int(m.group(2)), int(m.group(3))
                return f"scroll[{_map_x(x)},{_map_y(y)},{delta}]"

        # hotkey[x,y,keys] — keys 内含 JSON，不能用简单括号匹配
        elif action_lower.startswith("hotkey["):
            m = re.match(r"hotkey\[(\d+),(\d+),", action_str, re.IGNORECASE)
            if m:
                rest = action_str[m.end() - 1 :]  # 保留从 keys 前逗号起的后缀，如 ,["ctrl","c"]]
                return f"hotkey[{_map_x(int(m.group(1)))},{_map_y(int(m.group(2)))}{rest}"

        return action_str
