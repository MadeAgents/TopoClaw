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

"""Image rendering utilities for GUI agents."""

import base64
import math
from pathlib import Path
from typing import TYPE_CHECKING, Any

from loguru import logger

if TYPE_CHECKING:
    from PIL import ImageDraw

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    Image = None
    ImageDraw = None  # type: ignore[assignment]
    ImageFont = None
    logger.warning("PIL (Pillow) not available. Image rendering will be disabled.")


def encode_image(image_path: str) -> str:
    """Encode image to base64.
    
    Args:
        image_path: Path to image file
    
    Returns:
        Base64 encoded string
    """
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def render_action_on_image(
    image_path: str,
    action: str,
    output_path: str | None = None,
) -> str:
    """Render action annotation on screenshot image.
    
    Args:
        image_path: Path to screenshot image
        action: Action string (e.g., "click[300,620]", "swipe[540,1500,540,500]")
        output_path: Optional output path. If None, creates a temp file.
    
    Returns:
        Path to rendered image
    """
    if Image is None:
        logger.warning("PIL not available, returning original image")
        return image_path
    
    if not Path(image_path).exists():
        logger.error("Image file does not exist for rendering: {}", image_path)
        return image_path
    
    try:
        # Load image
        img = Image.open(image_path).convert("RGBA")
        draw = ImageDraw.Draw(img)
        
        # Parse action
        action_lower = action.lower().strip()
        
        if action_lower.startswith("click["):
            # Extract coordinates: click[x,y]
            coords_str = action_lower.split("[", 1)[1].rstrip("]")
            coords_str = coords_str.replace(" ", ",")
            x, y = map(int, [c for c in coords_str.split(",") if c])
            _draw_click_arrow(draw, x, y, img.width, img.height)
        
        elif action_lower.startswith("swipe["):
            # Extract coordinates: swipe[x1,y1,x2,y2]
            coords_str = action_lower.split("[", 1)[1].rstrip("]")
            # handle case where coordinates might be space-separated instead of comma-separated
            coords_str = coords_str.replace(" ", ",")
            x1, y1, x2, y2 = map(int, [c for c in coords_str.split(",") if c])
            _draw_swipe_arrow(draw, x1, y1, x2, y2, img.width, img.height)
        
        elif action_lower.startswith("type["):
            # Extract coordinates: type[x,y,text]
            coords_str = action_lower.split("[", 1)[1].rstrip("]")
            parts = coords_str.split(",", 2)
            if len(parts) >= 2:
                try:
                    x, y = int(parts[0].strip()), int(parts[1].strip())
                    _draw_click_arrow(draw, x, y, img.width, img.height, color=(0, 255, 0))  # Green for type
                except ValueError:
                    pass
        
        elif action_lower.startswith("long_press["):
            # Extract coordinates: long_press[x,y]
            coords_str = action_lower.split("[", 1)[1].rstrip("]")
            coords_str = coords_str.replace(" ", ",")
            x, y = map(int, [c for c in coords_str.split(",") if c])
            _draw_long_press_indicator(draw, x, y, img.width, img.height)
        
        # Save rendered image
        if output_path is None:
            output_path = str(Path(image_path).parent / f"rendered_{Path(image_path).stem}.png")

        # Convert back to RGB for PNG compatibility
        img_rgb = Image.new("RGB", img.size, (255, 255, 255))
        img_rgb.paste(img, mask=img.split()[3] if img.mode == "RGBA" else None)
        img_rgb.save(output_path, "PNG")
        
        return output_path
    
    except Exception as e:
        logger.error("Failed to render action on image: {}", e)
        return image_path


def _draw_click_arrow(
    draw: Any,  # ImageDraw.ImageDraw when PIL is available
    x: int,
    y: int,
    img_width: int,
    img_height: int,
    color: tuple[int, int, int] = (255, 0, 0),  # Red
) -> None:
    """Draw a compact click marker: green box with red arrow."""
    # Clamp coordinates to image boundaries
    x = max(0, min(img_width - 1, x))
    y = max(0, min(img_height - 1, y))

    box_size = max(18, min(img_width, img_height) // 40)
    half = box_size // 2
    left = max(0, x - half)
    top = max(0, y - half)
    right = min(img_width - 1, x + half)
    bottom = min(img_height - 1, y + half)

    # Ensure right >= left and bottom >= top for PIL drawing
    right = max(left, right)
    bottom = max(top, bottom)

    # Green focus box
    draw.rectangle(
        [left, top, right, bottom],
        outline=(0, 220, 0),
        width=max(2, box_size // 8),
    )

    # Red arrow points to the box from upper-left
    arrow_start = (max(0, left - box_size), max(0, top - box_size))
    arrow_end = (left + 2, top + 2)
    draw.line([arrow_start, arrow_end], fill=(255, 0, 0), width=max(2, box_size // 8))

    # Arrow head
    head = max(6, box_size // 3)
    hx, hy = arrow_end
    draw.polygon(
        [
            (hx, hy),
            (hx - head, hy - head // 3),
            (hx - head // 2, hy + head // 2),
        ],
        fill=(255, 0, 0),
    )


def _draw_swipe_arrow(
    draw: Any,  # ImageDraw.ImageDraw when PIL is available
    x1: int,
    y1: int,
    x2: int,
    y2: int,
    img_width: int,
    img_height: int,
    color: tuple[int, int, int] = (0, 0, 255),  # Blue
) -> None:
    """Draw a swipe arrow from (x1,y1) to (x2,y2)."""
    # Clamp coordinates
    x1 = max(0, min(img_width - 1, x1))
    y1 = max(0, min(img_height - 1, y1))
    x2 = max(0, min(img_width - 1, x2))
    y2 = max(0, min(img_height - 1, y2))

    # Arrow size based on image size
    arrow_size = max(15, min(img_width, img_height) // 40)
    
    # Calculate direction
    dx = x2 - x1
    dy = y2 - y1
    length = math.sqrt(dx * dx + dy * dy)
    
    if length < arrow_size:
        # Too short, just draw a point
        _draw_click_arrow(draw, x2, y2, img_width, img_height, color)
        return
    
    # Normalize direction
    dx_norm = dx / length
    dy_norm = dy / length
    
    # Draw line
    line_width = max(3, arrow_size // 3)
    draw.line(
        [(x1, y1), (x2, y2)],
        fill=color,
        width=line_width,
    )
    
    # Draw start point (circle)
    start_radius = arrow_size // 3
    draw.ellipse(
        [x1 - start_radius, y1 - start_radius, x1 + start_radius, y1 + start_radius],
        fill=color,
        outline=(255, 255, 255),
        width=2,
    )
    
    # Draw arrowhead at end point
    # Arrowhead points perpendicular to direction
    perp_x = -dy_norm
    perp_y = dx_norm
    
    arrow_points = [
        (x2, y2),  # Tip
        (
            int(x2 - arrow_size * dx_norm + arrow_size * 0.5 * perp_x),
            int(y2 - arrow_size * dy_norm + arrow_size * 0.5 * perp_y),
        ),
        (
            int(x2 - arrow_size * 0.7 * dx_norm),
            int(y2 - arrow_size * 0.7 * dy_norm),
        ),
        (
            int(x2 - arrow_size * dx_norm - arrow_size * 0.5 * perp_x),
            int(y2 - arrow_size * dy_norm - arrow_size * 0.5 * perp_y),
        ),
    ]
    draw.polygon(arrow_points, fill=color, outline=(255, 255, 255), width=1)


def _draw_long_press_indicator(
    draw: Any,  # ImageDraw.ImageDraw when PIL is available
    x: int,
    y: int,
    img_width: int,
    img_height: int,
    color: tuple[int, int, int] = (255, 165, 0),  # Orange
) -> None:
    """Draw a long press indicator (concentric circles)."""
    # Clamp coordinates
    x = max(0, min(img_width - 1, x))
    y = max(0, min(img_height - 1, y))

    # Indicator size based on image size
    size = max(25, min(img_width, img_height) // 25)
    
    # Draw concentric circles
    for i in range(3):
        radius = size - i * (size // 3)
        width = 2 if i == 0 else 1
        draw.ellipse(
            [x - radius, y - radius, x + radius, y + radius],
            fill=None,
            outline=color,
            width=width,
        )
