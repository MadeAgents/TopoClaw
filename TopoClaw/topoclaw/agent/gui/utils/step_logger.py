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

"""Step logger for GUI agents to record model inputs and outputs."""

import hashlib
import json
import re
import time
from datetime import datetime
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.agent.gui.utils.image_renderer import render_action_on_image

try:
    from PIL import Image
except ImportError:
    Image = None


class StepLogger:
    """Logger for recording each step's model inputs and outputs.
    
    Directory structure:
    workspace/logs/{query_hash}/
        duration.json       # Text-only timeline of all steps (no image payloads)
        step_001/
            input.json          # Model input messages (with image paths)
            output.json         # Model output response
            screenshots/        # All screenshots for this step
                current.png
                previous.png
            metadata.json       # Step metadata (timestamp, query, etc.)
        step_002/
            ...
    """

    def __init__(self, workspace: Path, enabled: bool = True):
        """Initialize step logger.
        
        Args:
            workspace: Workspace directory
            enabled: Whether logging is enabled
        """
        self.workspace = workspace
        self.logs_dir = workspace / "logs"
        self.enabled = enabled
        # Per query_hash: monotonic clock anchors for duration.json
        self._run_start_mono: dict[str, float] = {}
        self._last_step_mono: dict[str, float] = {}

        if self.enabled:
            self.logs_dir.mkdir(parents=True, exist_ok=True)
    
    def _get_query_hash(self, query: str) -> str:
        """Generate a hash for the query to use as directory name.
        
        Args:
            query: User query string
            
        Returns:
            Hash string (first 16 characters of SHA256)
        """
        if not query:
            query = "empty_query"
        return hashlib.sha256(query.encode()).hexdigest()[:16]
    
    def _get_step_dir(self, query: str, step_num: int) -> Path:
        """Get directory path for a specific step.
        
        Args:
            query: User query string
            step_num: Step number (1-indexed)
            
        Returns:
            Path to step directory
        """
        query_hash = self._get_query_hash(query)
        query_dir = self.logs_dir / query_hash
        step_dir = query_dir / f"step_{step_num:03d}"
        return step_dir

    @staticmethod
    def _strip_images_from_text(text: str, max_len: int) -> str:
        """Remove embedded image data URLs; trim length for duration.json (no binary)."""
        if not text:
            return ""
        cleaned = re.sub(
            r"data:image/[^;]+;base64,[A-Za-z0-9+/=\s]+",
            "[image/base64 omitted]",
            text,
            flags=re.IGNORECASE | re.DOTALL,
        )
        if len(cleaned) > max_len:
            return cleaned[:max_len] + "\n...[truncated]"
        return cleaned

    def _write_duration_timeline(
        self,
        query: str,
        step_num: int,
        response: dict[str, Any],
        *,
        raw_action: str | None,
        mapped_action: str | None,
        metadata: dict[str, Any] | None,
        thought: str | None = None,
        action_intent: str | None = None,
        execution_result: str | None = None,
    ) -> None:
        """Append one step to workspace/logs/{query_hash}/duration.json (text-only)."""
        qh = self._get_query_hash(query)
        qdir = self.logs_dir / qh
        qdir.mkdir(parents=True, exist_ok=True)
        path = qdir / "duration.json"
        now_wall = datetime.now().isoformat()
        now_mono = time.monotonic()

        if step_num <= 0:
            return

        if step_num == 1:
            self._run_start_mono[qh] = now_mono
            self._last_step_mono[qh] = now_mono
            elapsed_since_start_ms = 0
            elapsed_since_prev_ms = 0
            doc: dict[str, Any] = {
                "version": 1,
                "query_hash": qh,
                "query_preview": (query or "")[:500],
                "run_started_at": now_wall,
                "steps": [],
            }
        else:
            run_start = self._run_start_mono.get(qh, now_mono)
            last_step = self._last_step_mono.get(qh, now_mono)
            elapsed_since_start_ms = int((now_mono - run_start) * 1000)
            elapsed_since_prev_ms = int((now_mono - last_step) * 1000)
            self._last_step_mono[qh] = now_mono
            if path.exists():
                try:
                    doc = json.loads(path.read_text(encoding="utf-8"))
                except Exception:
                    doc = {
                        "version": 1,
                        "query_hash": qh,
                        "query_preview": (query or "")[:500],
                        "run_started_at": now_wall,
                        "steps": [],
                    }
            else:
                self._run_start_mono.setdefault(qh, now_mono)
                doc = {
                    "version": 1,
                    "query_hash": qh,
                    "query_preview": (query or "")[:500],
                    "run_started_at": now_wall,
                    "steps": [],
                }

        meta_safe: dict[str, Any] = {}
        if metadata:
            for k in ("session_key", "app_name", "model", "temperature", "max_tokens"):
                if k in metadata and metadata[k] is not None:
                    meta_safe[k] = metadata[k]

        entry: dict[str, Any] = {
            "step": step_num,
            "step_folder": f"step_{step_num:03d}",
            "wallclock_at": now_wall,
            "elapsed_ms_since_run_start": elapsed_since_start_ms,
            "elapsed_ms_since_prev_step": elapsed_since_prev_ms,
            "thought": (thought or "")[:4000],
            "action_intent": (action_intent or "")[:4000],
            "raw_action": (raw_action or "")[:8000],
            "mapped_action": (mapped_action or "")[:8000],
            "execution_result": (execution_result or "")[:8000],
            "assistant_text": self._strip_images_from_text(str(response.get("content") or ""), 24_000),
            "model": str(response.get("model") or ""),
            "finish_reason": str(response.get("finish_reason") or ""),
            "usage": response.get("usage") or {},
            "metadata": meta_safe,
        }
        steps = doc.get("steps")
        if not isinstance(steps, list):
            steps = []
            doc["steps"] = steps
        steps.append(entry)
        doc["updated_at"] = now_wall
        doc["total_steps"] = len(steps)
        doc["total_elapsed_ms_since_run_start"] = elapsed_since_start_ms

        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(doc, f, ensure_ascii=False, indent=2)
            logger.debug("Updated duration timeline: {}", path)
        except Exception as e:
            logger.warning("Failed to write duration.json: {}", e)

    def _save_image(self, image_path: str | Path, step_dir: Path, name: str) -> str | None:
        """Save an image file to the step's screenshots directory.
        
        Args:
            image_path: Path to source image file
            step_dir: Step directory
            name: Name for the saved image (e.g., "current", "previous")
            
        Returns:
            Relative path to saved image (from step_dir) or None if failed
        """
        if not image_path:
            return None
        
        source_path = Path(image_path)
        if not source_path.exists():
            logger.warning("Image file does not exist: {}", image_path)
            return None
        
        screenshots_dir = step_dir / "screenshots"
        screenshots_dir.mkdir(parents=True, exist_ok=True)
        
        try:
            dest_path = screenshots_dir / f"{name}.png"
            if Image is not None:
                img = Image.open(source_path)
                img.save(dest_path, "PNG")
            else:
                # Fallback: keep the file but normalize the name to png
                dest_path.write_bytes(source_path.read_bytes())
            return f"screenshots/{name}.png"
        except Exception as e:
            logger.error("Failed to save image {}: {}", image_path, e)
            return None
    
    def _extract_images_from_messages(
        self,
        messages: list[dict[str, Any]],
        step_dir: Path,
        skip_paths: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        """Extract images from messages and save them, replacing base64 with file paths.
        
        Args:
            messages: List of message dicts (may contain base64 images)
            step_dir: Step directory for saving images
            
        Returns:
            List of messages with images replaced by file paths
        """
        logged_messages = []
        image_counter = 0
        skip_paths_normalized = {str(Path(p).resolve()) for p in (skip_paths or set())}
        
        for msg in messages:
            logged_msg = {"role": msg.get("role"), "content": None}
            content = msg.get("content")
            
            if isinstance(content, str):
                # Simple text content
                logged_msg["content"] = content
            elif isinstance(content, list):
                # Multimodal content (may contain images)
                logged_content = []
                
                # First pass: count images to determine naming strategy
                image_count = sum(1 for item in content if isinstance(item, dict) and item.get("type") == "image_url")
                
                # Second pass: process items and name images based on position
                image_index = 0  # Track which image we're processing
                for item in content:
                    if isinstance(item, dict):
                        item_type = item.get("type")
                        if item_type == "text":
                            logged_content.append({
                                "type": "text",
                                "text": item.get("text", ""),
                            })
                        elif item_type == "image_url":
                            # Extract image URL
                            image_url = item.get("image_url", {})
                            url = image_url.get("url", "")
                            
                            # Determine image name based on position
                            # In GUI agent message: [previous_image?, current_image, text]
                            # If 2 images: first is previous_rendered, second is current
                            # If 1 image: it's current
                            image_name = None
                            if image_count == 2:
                                # Two images: first is previous, second is current
                                if image_index == 0:
                                    image_name = "previous_rendered"
                                else:
                                    image_name = "current"
                            elif image_count == 1:
                                # One image: it's current
                                image_name = "current"
                            else:
                                # Fallback to numbered name
                                image_name = f"image_{image_counter:02d}"
                                image_counter += 1
                            
                            image_index += 1
                            
                            # Check if it's a base64 data URL
                            if url.startswith("data:image"):
                                # Extract base64 data and save as file
                                try:
                                    import base64
                                    # Parse data URL: data:image/jpeg;base64,<data>
                                    header, data = url.split(",", 1)
                                    # Extract format from header
                                    format_part = header.split(";")[0].split("/")[-1]
                                    # Decode and save
                                    image_data = base64.b64decode(data)
                                    screenshots_dir = step_dir / "screenshots"
                                    screenshots_dir.mkdir(parents=True, exist_ok=True)
                                    final_image_name = f"{image_name}.png"
                                    image_path = screenshots_dir / final_image_name
                                    if Image is not None:
                                        from io import BytesIO
                                        img = Image.open(BytesIO(image_data))
                                        img.save(image_path, "PNG")
                                    else:
                                        image_path.write_bytes(image_data)
                                    
                                    # Replace with relative path
                                    logged_content.append({
                                        "type": "image_url",
                                    "image_url": {"url": f"screenshots/{final_image_name}"},
                                    })
                                except Exception as e:
                                    logger.error("Failed to extract base64 image: {}", e)
                                    logged_content.append(item)  # Keep original
                            elif url.startswith("file://") or (not url.startswith("http") and Path(url).exists()):
                                # It's a file path, save it
                                file_path = url.replace("file://", "")
                                try:
                                    resolved_file_path = str(Path(file_path).resolve())
                                except Exception:
                                    resolved_file_path = file_path

                                if resolved_file_path in skip_paths_normalized:
                                    logged_content.append({
                                        "type": "image_url",
                                        "image_url": {"url": f"screenshots/{Path(file_path).name}"},
                                    })
                                    continue

                                saved_path = self._save_image(file_path, step_dir, image_name)
                                if saved_path:
                                    logged_content.append({
                                        "type": "image_url",
                                        "image_url": {"url": saved_path},
                                    })
                                else:
                                    logged_content.append(item)  # Keep original
                            else:
                                # Keep as-is (might be HTTP URL or other format)
                                logged_content.append(item)
                        else:
                            # Keep other types as-is
                            logged_content.append(item)
                    else:
                        logged_content.append(item)
                
                logged_msg["content"] = logged_content
            else:
                # Keep as-is
                logged_msg["content"] = content
            
            logged_messages.append(logged_msg)
        
        return logged_messages
    
    def log_step(
        self,
        query: str,
        step_num: int,
        messages: list[dict[str, Any]],
        response: dict[str, Any],
        screenshot_path: str | None = None,
        previous_screenshot_path: str | None = None,
        raw_action: str | None = None,
        mapped_action: str | None = None,
        previous_mapped_action: str | None = None,
        metadata: dict[str, Any] | None = None,
        thought: str | None = None,
        action_intent: str | None = None,
        execution_result: str | None = None,
    ) -> Path | None:
        """Log a step's input and output.
        
        Args:
            query: User query string
            step_num: Step number (1-indexed)
            messages: Input messages to the model
            response: Model response (dict with content, etc.)
            screenshot_path: Path to current screenshot (will be saved)
            previous_screenshot_path: Path to previous screenshot (will be saved)
            metadata: Additional metadata to save
            thought: Parsed thought (stored in duration.json only, no images)
            action_intent: Parsed action intent for timeline
            execution_result: Client/executor feedback text for this step
            
        Returns:
            Path to step directory, or None if logging is disabled or failed
        """
        if not self.enabled:
            return None
        
        try:
            step_dir = self._get_step_dir(query, step_num)
            step_dir.mkdir(parents=True, exist_ok=True)
            
            # Save screenshots if provided
            screenshot_info = {}
            if screenshot_path:
                saved_path = self._save_image(screenshot_path, step_dir, "current")
                if saved_path:
                    screenshot_info["current"] = saved_path
            
            if previous_screenshot_path:
                saved_path = self._save_image(previous_screenshot_path, step_dir, "previous")
                if saved_path:
                    screenshot_info["previous"] = saved_path

            rendered_info = {}
            if screenshot_path and mapped_action:
                rendered_current = step_dir / "screenshots" / "current_rendered.png"
                rendered_current.parent.mkdir(parents=True, exist_ok=True)
                rendered_current_path = render_action_on_image(screenshot_path, mapped_action, str(rendered_current))
                if rendered_current_path:
                    rendered_info["current"] = f"screenshots/{Path(rendered_current_path).name}"

            if previous_screenshot_path and previous_mapped_action:
                rendered_previous = step_dir / "screenshots" / "previous_rendered.png"
                rendered_previous.parent.mkdir(parents=True, exist_ok=True)
                rendered_previous_path = render_action_on_image(previous_screenshot_path, previous_mapped_action, str(rendered_previous))
                if rendered_previous_path:
                    rendered_info["previous"] = f"screenshots/{Path(rendered_previous_path).name}"
            
            # Extract images from messages and save them
            logged_messages = self._extract_images_from_messages(
                messages,
                step_dir,
                skip_paths={p for p in [screenshot_path, previous_screenshot_path] if p},
            )
            
            # Save input (messages with image paths instead of base64)
            input_data = {
                "messages": logged_messages,
                "screenshots": screenshot_info,
                "rendered_screenshots": rendered_info,
            }
            input_file = step_dir / "input.json"
            with open(input_file, "w", encoding="utf-8") as f:
                json.dump(input_data, f, ensure_ascii=False, indent=2)
            
            # Save output (model response)
            output_data = {
                "content": response.get("content", ""),
                "model": response.get("model", ""),
                "usage": response.get("usage", {}),
                "finish_reason": response.get("finish_reason", ""),
                "raw_action": raw_action or "",
                "mapped_action": mapped_action or "",
            }
            output_file = step_dir / "output.json"
            with open(output_file, "w", encoding="utf-8") as f:
                json.dump(output_data, f, ensure_ascii=False, indent=2)
            
            # Save metadata
            metadata_data = {
                "query": query,
                "step_num": step_num,
                "timestamp": datetime.now().isoformat(),
                **(metadata or {}),
            }
            metadata_file = step_dir / "metadata.json"
            with open(metadata_file, "w", encoding="utf-8") as f:
                json.dump(metadata_data, f, ensure_ascii=False, indent=2)

            self._write_duration_timeline(
                query,
                step_num,
                response,
                raw_action=raw_action,
                mapped_action=mapped_action,
                metadata=metadata,
                thought=thought,
                action_intent=action_intent,
                execution_result=execution_result,
            )

            logger.debug("Logged step {} to {}", step_num, step_dir)
            return step_dir
            
        except Exception as e:
            logger.error("Failed to log step: {}", e)
            return None
