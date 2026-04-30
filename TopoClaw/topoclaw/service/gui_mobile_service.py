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

"""Mobile GUI service and unified WS GUI dispatch."""

from __future__ import annotations

import asyncio
import base64
import json
import re
import tempfile
import time
import uuid
from pathlib import Path
from typing import Any, Awaitable, Callable

from fastapi import HTTPException, Request, UploadFile
from loguru import logger

from topoclaw.agent.gui.workspace_layout import gui_temp_screenshots
from topoclaw.bus.events import OutboundMessage
from topoclaw.connection.device_registry import DeviceRegistry
from topoclaw.connection.ws_registry import WSConnectionRegistry
from topoclaw.models.gui_mobile import (
    MobileActionResponse,
    MobileNextActionRequest,
    MobileNextActionResponse,
)

_DEFAULT_MOBILE_GUI_SERVICE: "MobileGUIService | None" = None
ActionResponse = MobileActionResponse


def set_default_gui_service(service: "MobileGUIService") -> None:
    global _DEFAULT_MOBILE_GUI_SERVICE
    _DEFAULT_MOBILE_GUI_SERVICE = service


async def dispatch_gui_execute_request(
    thread_id: str,
    query: str,
    chat_summary: str | None = None,
    timeout_s: int = 120,
    max_steps: int = 100,
    target_imei: str | None = None,
) -> dict[str, Any]:
    if not _DEFAULT_MOBILE_GUI_SERVICE:
        return {"success": False, "error": "gui service unavailable"}
    return await _DEFAULT_MOBILE_GUI_SERVICE.dispatch_gui_execute_request(
        thread_id=thread_id,
        query=query,
        chat_summary=chat_summary,
        timeout_s=timeout_s,
        max_steps=max_steps,
        target_imei=target_imei,
    )


async def dispatch_mobile_tool_request(
    *,
    thread_id: str,
    tool: str,
    args: dict[str, Any] | None = None,
    timeout_s: int = 20,
    protocol: str = "mobile_tool/v1",
) -> dict[str, Any]:
    if not _DEFAULT_MOBILE_GUI_SERVICE:
        return {"success": False, "error": "mobile tool service unavailable"}
    return await _DEFAULT_MOBILE_GUI_SERVICE.dispatch_mobile_tool_request(
        thread_id=thread_id,
        tool=tool,
        args=args or {},
        timeout_s=timeout_s,
        protocol=protocol,
    )


def _save_base64_image(base64_data: str, temp_dir: Path) -> Path:
    if base64_data.startswith("data:"):
        base64_data = base64_data.split(",", 1)[1]
    try:
        image_data = base64.b64decode(base64_data)
        temp_file = tempfile.NamedTemporaryFile(dir=temp_dir, suffix=".png", delete=False)
        temp_file.write(image_data)
        temp_file.close()
        return Path(temp_file.name)
    except Exception as exc:
        logger.error("Failed to save base64 image: {}", exc)
        raise HTTPException(status_code=400, detail=f"Invalid base64 image: {exc}") from exc


def _is_base64_data(data: str) -> bool:
    if data.startswith("data:"):
        return True
    if len(data) <= 100:
        return False
    base64_chars = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=\n\r\t ")
    cleaned = "".join(c for c in data if c not in "\n\r\t ")
    return len(cleaned) > 100 and all(c in base64_chars for c in cleaned)


async def _process_uploaded_images(images: list[UploadFile] | None, workspace: Path) -> Path | None:
    if not images:
        return None
    image = images[0]
    image_data = await image.read()
    temp_dir = gui_temp_screenshots(workspace)
    temp_dir.mkdir(parents=True, exist_ok=True)
    temp_file = tempfile.NamedTemporaryFile(dir=temp_dir, suffix=".png", delete=False)
    temp_file.write(image_data)
    temp_file.close()
    return Path(temp_file.name)


async def _process_base64_images_from_form(request: Request, workspace: Path) -> Path | None:
    try:
        form_data = await request.form()
        image_keys: list[str] = []
        for key in form_data.keys():
            if key.startswith("images[") and key.endswith("]"):
                value = form_data.get(key)
                if value and (isinstance(value, str) or str(value).strip()):
                    image_keys.append(key)
            elif key in {"images", "screenshot"}:
                value = form_data.get(key)
                if isinstance(value, str) and value.strip():
                    image_keys.append(key)
        image_keys.sort()
        for key in image_keys:
            base64_data = form_data.get(key)
            if not base64_data:
                continue
            if not isinstance(base64_data, str):
                base64_data = str(base64_data)
            base64_data = base64_data.strip()
            if _is_base64_data(base64_data):
                temp_dir = gui_temp_screenshots(workspace)
                temp_dir.mkdir(parents=True, exist_ok=True)
                return _save_base64_image(base64_data, temp_dir)
        return None
    except Exception as exc:
        logger.error("Failed to parse base64 image from form data: {}", exc, exc_info=True)
        return None


def _extract_action_info(response_text: str) -> dict[str, Any]:
    thought_match = re.search(r"thought:\s*([^#]+)", response_text, re.IGNORECASE)
    reasoning_match = re.search(r"reasoning:\s*([^#]+)", response_text, re.IGNORECASE)
    action_match = re.search(r"###action:\s*([a-z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
    if not action_match:
        action_match = re.search(r"action:\s*([a-z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
    if not action_match:
        action_match = re.search(r"###action:\s*([A-Z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
    if not action_match:
        action_match = re.search(r"action:\s*([A-Z_]+(?:\[[^\]]+\])?)", response_text, re.IGNORECASE)
    return {
        "thought": thought_match.group(1).strip() if thought_match else "",
        "reasoning": reasoning_match.group(1).strip() if reasoning_match else "",
        "action": action_match.group(1).strip() if action_match else "wait",
    }


def _extract_action_result(result: dict[str, Any]) -> tuple[str, str, str]:
    # logger.info("Raw result from agent: {}", result)
    if result.get("action"):
        return (
            result["action"],
            result.get("thought", ""),
            result.get("action_intent", "") or result.get("reasoning", "")
        )
    action_info = _extract_action_info(result.get("response_text", ""))
    return (
        action_info["action"],
        action_info["thought"],
        action_info.get("reasoning", action_info.get("action_intent", "")),
    )


def _parse_mobile_action(action_str: str) -> tuple[str, list[Any] | str]:
    action_str = action_str.strip()
    if "[" in action_str:
        action_name, args_str = action_str.split("[", 1)
        args_str = args_str.rstrip("]").strip()
        action_name = action_name.strip().lower()
    else:
        action_name = action_str.strip().lower()
        args_str = ""
    if action_name == "click":
        return ("click", list(map(int, args_str.replace(" ", ",").split(",")))) if args_str else ("click", [])
    if action_name == "swipe":
        return ("swipe", list(map(int, args_str.replace(" ", ",").split(",")))) if args_str else ("swipe", [])
    if action_name == "type":
        if not args_str:
            return "type", ""
        parts = args_str.split(",", 2)
        if len(parts) == 3:
            raw_text = parts[2].strip()
            try:
                decoded = json.loads(raw_text)
                text = decoded if isinstance(decoded, str) else str(decoded)
            except json.JSONDecodeError:
                text = raw_text
            return "type", [int(parts[0].strip()), int(parts[1].strip()), text]
        return "type", args_str
    if action_name in {"open", "call_user"}:
        if "[" in action_str:
            return ("open", action_str.split("[", 1)[1].rstrip("]")) if action_name == "open" else (
                "call_user",
                action_str.split("[", 1)[1].rstrip("]"),
            )
        return ("open", args_str) if action_name == "open" else ("call_user", args_str)
    if action_name in {"long_press", "double_click"}:
        return (action_name, list(map(int, args_str.replace(" ", ",").split(",")))) if args_str else (action_name, [])
    if action_name in {"complete", "answer"}:
        return "complete", args_str if args_str else []
    if action_name in {"back", "home", "screenshot", "wait"}:
        return action_name, []
    if action_name == "long_screenshot":
        params = args_str.split(",") if args_str else ["down", "3"]
        direction = params[0].strip().lower() if params else "down"
        steps = int(params[1].strip()) if len(params) > 1 else 3
        return "long_screenshot", [direction, steps]
    return "wait", []


def _build_mobile_action_response(action_name: str, action_args: list[Any] | str, thought: str, reasoning: str) -> dict[str, Any]:
    response = {"action_type": action_name, "reason": reasoning, "thought": thought}
    if action_name == "click":
        response["click"] = action_args
    elif action_name == "swipe":
        response["swipe"] = action_args
    elif action_name == "open":
        response["app_name"] = action_args
    elif action_name == "type":
        if isinstance(action_args, list) and len(action_args) == 3:
            response["text"] = action_args[2]
            response["click"] = action_args[:2]
        else:
            response["text"] = action_args
    elif action_name in {"long_press", "double_click"}:
        response["coordinates"] = action_args
    elif action_name == "call_user":
        response["text"] = action_args
    elif action_name == "complete" and isinstance(action_args, str) and action_args.strip():
        response["answer"] = action_args
    return response


async def _mark_task_complete_if_needed(
    request: Request,
    task_id: str | None,
    action_name: str,
    action_args: list[Any] | str,
    thought: str,
    reasoning: str,
) -> None:
    # Task completion is now handled via WebSocket, no need for manager-based completion
    pass


class MobileGUIService:
    """Service layer for mobile GUI routes and GUI websocket execution."""

    def __init__(
        self,
        connection_registry: WSConnectionRegistry,
        device_registry: DeviceRegistry | None = None,
        release_connection: Callable[[str], Awaitable[None]] | None = None,
        outbound_publish: Callable[[OutboundMessage], Awaitable[None]] | None = None,
        mobile_agent: Any | None = None,
        direct_channel_send: Callable[[OutboundMessage], Awaitable[None]] | None = None,
    ) -> None:
        self.connection_registry = connection_registry
        self.device_registry = device_registry
        self._release_connection = release_connection
        self._outbound_publish = outbound_publish
        self.mobile_agent = mobile_agent
        self._direct_channel_send = direct_channel_send
        self._task_state_by_id: dict[str, dict[str, Any]] = {}
        self._session_alias_to_task_id: dict[str, str] = {}
        self._task_state_lock = asyncio.Lock()

    @staticmethod
    def _is_valid_relay_imei(candidate: str | None) -> bool:
        value = str(candidate or "").strip()
        if not value:
            return False
        lower = value.lower()
        # Avoid routing pseudo thread prefixes such as group_*/friend_* as imei.
        if lower in {"group", "friend", "assistant", "custom", "topoclaw", "chat"}:
            return False
        if not all(ch.isalnum() or ch in {"-", ":"} for ch in value):
            return False
        # IMEI/device ids used by this project are typically mixed hex/alnum and include digits.
        if not any(ch.isdigit() for ch in value):
            return False
        return True

    @classmethod
    def _derive_imei_from_thread_id(cls, thread_id: str | None) -> str:
        tid = str(thread_id or "").strip()
        if "_" not in tid:
            return ""
        prefix = tid.split("_", 1)[0].strip()
        if not prefix:
            return ""
        if cls._is_valid_relay_imei(prefix):
            return prefix
        return ""

    @staticmethod
    def _thread_variants(thread_id: str | None) -> list[str]:
        tid = str(thread_id or "").strip()
        if not tid:
            return []
        variants: list[str] = [tid]
        if "__" in tid:
            tail = tid.rsplit("__", 1)[-1].strip()
            if tail and tail not in variants:
                variants.append(tail)
        return variants

    @classmethod
    def _thread_matches(cls, lhs: str | None, rhs: str | None) -> bool:
        left_variants = cls._thread_variants(lhs)
        right_variants = cls._thread_variants(rhs)
        if not left_variants or not right_variants:
            return False
        for left in left_variants:
            for right in right_variants:
                if left == right:
                    return True
        return False

    async def _resolve_imei_for_relay(
        self,
        thread_id: str | None,
        *,
        target_imei: str | None = None,
        require_explicit_target_on_multi_online: bool = False,
    ) -> tuple[str, str]:
        normalized_target_imei = str(target_imei or "").strip()
        if normalized_target_imei and not self._is_valid_relay_imei(normalized_target_imei):
            return "", "invalid target imei"
        # Explicit target should be forwarded to relay directly.
        # Mobile online/offline truth comes from relay service, not local ws snapshot.
        if normalized_target_imei:
            return normalized_target_imei, ""

        online_mobile_imeis: set[str] = set()
        mobile_devices: list[dict[str, Any]] = []
        if self.device_registry is not None:
            try:
                mobile_devices = await self.device_registry.list_devices(device_type="mobile")
                for device in mobile_devices:
                    device_id = str(device.get("device_id") or "").strip()
                    if self._is_valid_relay_imei(device_id):
                        online_mobile_imeis.add(device_id)
            except Exception:
                logger.opt(exception=True).warning(
                    "[MobileGUI][dispatch.resolve_imei] device_registry list failed thread_id={}",
                    str(thread_id or "").strip() or "(none)",
                )

        snapshots: list[dict[str, Any]] = []
        try:
            snapshots = await self.connection_registry.snapshot_connections()
            for snap in snapshots:
                meta = snap.get("metadata")
                if not isinstance(meta, dict):
                    continue
                device_type = str(meta.get("device_type") or "").strip().lower()
                if device_type and device_type != "mobile":
                    continue
                candidate = str(meta.get("imei") or meta.get("device_id") or "").strip()
                if self._is_valid_relay_imei(candidate):
                    online_mobile_imeis.add(candidate)
        except Exception:
            logger.opt(exception=True).warning(
                "[MobileGUI][dispatch.resolve_imei] ws metadata snapshot failed thread_id={}",
                str(thread_id or "").strip() or "(none)",
            )

        if require_explicit_target_on_multi_online and len(online_mobile_imeis) > 1:
            return "", "multiple online devices require explicit target imei"

        imei_from_tid = self._derive_imei_from_thread_id(thread_id)
        if imei_from_tid:
            return imei_from_tid, ""

        normalized_thread_id = str(thread_id or "").strip()
        if not normalized_thread_id:
            if len(online_mobile_imeis) == 1:
                return next(iter(online_mobile_imeis)), ""
            return "", "missing thread id for relay"

        if mobile_devices:
            try:
                for device in mobile_devices:
                    device_id = str(device.get("device_id") or "").strip()
                    if not device_id:
                        continue
                    bound_threads = device.get("thread_ids")
                    if not isinstance(bound_threads, list):
                        continue
                    if any(self._thread_matches(normalized_thread_id, bound) for bound in bound_threads):
                        logger.info(
                            "[MobileGUI][dispatch.resolve_imei] resolved from device_registry thread_id={} imei={}",
                            normalized_thread_id,
                            device_id,
                        )
                        return device_id, ""
            except Exception:
                logger.opt(exception=True).warning(
                    "[MobileGUI][dispatch.resolve_imei] device_registry lookup failed thread_id={}",
                    normalized_thread_id,
                )

        try:
            for snap in snapshots:
                meta = snap.get("metadata")
                if not isinstance(meta, dict):
                    continue
                meta_thread = str(meta.get("thread_id") or meta.get("last_chat_thread_id") or "").strip()
                meta_last_thread = str(meta.get("last_chat_thread_id") or "").strip()
                if (
                    not self._thread_matches(normalized_thread_id, meta_thread)
                    and not self._thread_matches(normalized_thread_id, meta_last_thread)
                ):
                    continue
                meta_imei = str(meta.get("imei") or meta.get("device_id") or "").strip()
                if not self._is_valid_relay_imei(meta_imei):
                    continue
                if online_mobile_imeis and meta_imei not in online_mobile_imeis:
                    continue
                logger.info(
                    "[MobileGUI][dispatch.resolve_imei] resolved from ws metadata thread_id={} imei={} conn_id={}",
                    normalized_thread_id,
                    meta_imei,
                    str(snap.get("conn_id") or "").strip() or "(none)",
                )
                return meta_imei, ""
            if len(online_mobile_imeis) == 1:
                only_imei = next(iter(online_mobile_imeis))
                logger.info(
                    "[MobileGUI][dispatch.resolve_imei] resolved by single-candidate fallback thread_id={} imei={}",
                    normalized_thread_id,
                    only_imei,
                )
                return only_imei, ""
        except Exception:
            logger.opt(exception=True).warning(
                "[MobileGUI][dispatch.resolve_imei] ws metadata lookup failed thread_id={}",
                normalized_thread_id,
            )

        return "", "missing imei for relay"

    async def _cleanup_failed_conn(self, conn_id: str) -> None:
        normalized = str(conn_id or "").strip()
        if not normalized:
            return
        try:
            if self._release_connection is not None:
                await self._release_connection(normalized)
        except Exception:
            logger.opt(exception=True).warning("[GUI] release stale conn failed conn_id={}", normalized)
        try:
            await self.connection_registry.remove(normalized)
        except Exception:
            logger.opt(exception=True).warning("[GUI] remove stale conn failed conn_id={}", normalized)

    @staticmethod
    def _conn_sort_key(conn_id: str) -> tuple[int, str]:
        value = str(conn_id or "")
        if value.startswith("conn_"):
            suffix = value[5:]
            if suffix.isdigit():
                return (int(suffix), value)
        return (-1, value)

    @staticmethod
    def _now_ms() -> int:
        return int(time.time() * 1000)

    async def _set_task_state(self, task_id: str, state: dict[str, Any]) -> None:
        async with self._task_state_lock:
            self._task_state_by_id[task_id] = state

    async def _get_task_state(self, task_id: str) -> dict[str, Any] | None:
        async with self._task_state_lock:
            state = self._task_state_by_id.get(task_id)
            return dict(state) if state else None

    async def _update_task_state(self, task_id: str, **updates: Any) -> None:
        async with self._task_state_lock:
            state = self._task_state_by_id.get(task_id)
            if not state:
                return
            state.update(updates)

    async def _remove_task_state(self, task_id: str) -> None:
        async with self._task_state_lock:
            self._task_state_by_id.pop(task_id, None)
            aliases = [sid for sid, tid in self._session_alias_to_task_id.items() if tid == task_id]
            for sid in aliases:
                self._session_alias_to_task_id.pop(sid, None)

    async def _resolve_tracking_session_id(self, session_id: str | None) -> str | None:
        """Resolve upload session_id to tracked request_id.

        Upload callbacks may use a client-generated uuid different from ws request_id.
        We opportunistically bind that uuid to the current active task so stop/timeout
        logic can be applied consistently.
        """
        if not session_id:
            return None

        sid = str(session_id).strip()
        if not sid:
            return None

        async with self._task_state_lock:
            if sid in self._task_state_by_id:
                return sid

            mapped = self._session_alias_to_task_id.get(sid)
            if mapped and mapped in self._task_state_by_id:
                return mapped

            # Heuristic fallback: if exactly one active task exists, bind this upload uuid.
            active_task_ids = list(self._task_state_by_id.keys())
            if len(active_task_ids) == 1:
                target = active_task_ids[0]
                self._session_alias_to_task_id[sid] = target
                logger.info(
                    "[MobileGUI][alias.bind] upload_session_id={} -> request_id={}",
                    sid,
                    target,
                )
                return target

        return sid

    async def stop_active_tasks(self, *, thread_id: str | None = None, reason: str = "任务已停止") -> int:
        """Stop active GUI tasks and resolve waiting futures immediately."""
        async with self._task_state_lock:
            targets = [
                task_id
                for task_id, state in self._task_state_by_id.items()
                if not thread_id or str(state.get("thread_id") or "").strip() == str(thread_id).strip()
            ]

        stopped = 0
        now_ms = self._now_ms()
        for task_id in targets:
            await self._update_task_state(
                task_id,
                stopped=True,
                stop_reason=reason,
                stopped_at_ms=now_ms,
            )
            await self.connection_registry.resolve_gui_execute_result(
                {
                    "request_id": task_id,
                    "success": False,
                    "content": reason,
                    "error": reason,
                }
            )
            stopped += 1

        logger.info(
            "[MobileGUI][stop] thread_id={} reason={} stopped_tasks={}",
            thread_id or "(all)",
            reason,
            stopped,
        )
        return stopped

    async def _finalize_mobile_task_result(self, task_id: str, content: str, *, success: bool) -> None:
        """Resolve pending gui_task future (if any) and remove task state."""
        logger.info(
            "[MobileGUI][finalize] request_id={} success={} content_preview={}",
            task_id,
            success,
            str(content or "")[:120],
        )
        await self.connection_registry.resolve_gui_execute_result(
            {
                "request_id": task_id,
                "success": success,
                "content": content,
                "error": "" if success else content,
            }
        )
        await self._remove_task_state(task_id)

    async def _log_task_progress(
        self,
        *,
        source: str,
        task_id: str | None,
        uuid_value: str | None,
        session_id: str | None,
    ) -> dict[str, Any] | None:
        if not session_id:
            logger.info(
                "[MobileGUI][{}] task_id={} uuid={} session_id=(none) has_state=false",
                source,
                task_id or "(none)",
                uuid_value or "(none)",
            )
            return None

        tracking_session_id = await self._resolve_tracking_session_id(session_id)
        state = await self._get_task_state(tracking_session_id) if tracking_session_id else None
        if not state:
            logger.warning(
                "[MobileGUI][{}] task_id={} uuid={} session_id={} has_state=false",
                source,
                task_id or "(none)",
                uuid_value or "(none)",
                session_id,
            )
            return None

        now_ms = self._now_ms()
        started_at_ms = int(state.get("started_at_ms") or 0)
        deadline_at_ms = int(state.get("deadline_at_ms") or 0)
        step_count = int(state.get("step_count") or 0)
        max_steps_val = int(state.get("max_steps") or 0)
        elapsed_ms = max(0, now_ms - started_at_ms) if started_at_ms else 0
        remaining_ms = max(0, deadline_at_ms - now_ms) if deadline_at_ms else 0
        timeout_s = int(state.get("timeout_s") or 0)
        logger.info(
            "[MobileGUI][{}] task_id={} uuid={} session_id={} tracking_session_id={} has_state=true step_count={} max_steps={} timeout_s={} "
            "elapsed_ms={} remaining_ms={}",
            source,
            task_id or "(none)",
            uuid_value or "(none)",
            session_id,
            tracking_session_id,
            step_count,
            max_steps_val,
            timeout_s,
            elapsed_ms,
            remaining_ms,
        )
        return state

    async def handle_ws_message(self, msg: dict[str, Any]) -> bool:
        """Handle GUI-specific websocket messages."""
        msg_type = str(msg.get("type") or "")
        if msg_type == "gui_execute_result":
            request_id = str(msg.get("request_id") or "")
            if request_id:
                await self._remove_task_state(request_id)
                logger.info("[MobileGUI][ws_result] request_id={} cleaned_task_state=true", request_id)
            await self.connection_registry.resolve_gui_execute_result(msg)
            return True
        return False

    def get_mobile_agent_for_task(self, request: Request, task_id: str | None = None):
        # Unified to WebSocket mode: always use global mobile_agent
        agent = getattr(request.app.state, "mobile_agent", None) or self.mobile_agent
        if not agent:
            raise HTTPException(status_code=500, detail="Mobile agent not initialized.")
        return agent

    async def handle_mobile_upload(
        self,
        request: Request,
        uuid_value: str | None,
        task_id: str | None,
        query: str,
        feedback: int | None,
        feedback_text: str | None,
        user_response: str | None,
        package_name: str | None,
        images: list[UploadFile] | None,
    ) -> dict[str, Any]:
        session_id = task_id or uuid_value
        if not session_id and feedback is None:
            raise HTTPException(status_code=400, detail="Either task_id or uuid is required")
        agent = self.get_mobile_agent_for_task(request, task_id)
        tracking_session_id = await self._resolve_tracking_session_id(session_id)
        state = await self._log_task_progress(
            source="upload.enter",
            task_id=task_id,
            uuid_value=uuid_value,
            session_id=session_id,
        )
        # Enforce stop / wall-clock deadline / max steps before running the agent (same idea as computer GUI).
        if tracking_session_id and state:
            if bool(state.get("stopped")):
                stop_reason = str(state.get("stop_reason") or "任务已停止")
                logger.info(
                    "[MobileGUI][upload.stopped] session_id={} tracking_session_id={} reason={}",
                    session_id,
                    tracking_session_id,
                    stop_reason,
                )
                await self._finalize_mobile_task_result(tracking_session_id, stop_reason, success=False)
                return {
                    "action_type": "complete",
                    "reason": stop_reason,
                    "thought": stop_reason,
                    "answer": stop_reason,
                }
            now_ms = self._now_ms()
            deadline_at_ms = int(state.get("deadline_at_ms") or 0)
            if deadline_at_ms and now_ms > deadline_at_ms:
                timeout_text = "执行超时：达到任务总时限，已自动结束。"
                logger.info(
                    "[MobileGUI][upload.deadline] session_id={} tracking_session_id={}",
                    session_id,
                    tracking_session_id,
                )
                await self._finalize_mobile_task_result(tracking_session_id, timeout_text, success=False)
                return {
                    "action_type": "complete",
                    "reason": timeout_text,
                    "thought": timeout_text,
                    "answer": timeout_text,
                }

            next_step = int(state.get("step_count") or 0) + 1
            max_steps = int(state.get("max_steps") or 0)
            if max_steps and next_step > max_steps:
                max_step_text = f"执行步数超限：已达到最大步数 {max_steps}，任务自动结束。"
                logger.info(
                    "[MobileGUI][upload.max_steps] session_id={} tracking_session_id={} max_steps={}",
                    session_id,
                    tracking_session_id,
                    max_steps,
                )
                await self._finalize_mobile_task_result(tracking_session_id, max_step_text, success=False)
                return {
                    "action_type": "complete",
                    "reason": max_step_text,
                    "thought": max_step_text,
                    "answer": max_step_text,
                }
            await self._update_task_state(tracking_session_id, step_count=next_step, last_step_at_ms=now_ms)
            await self._log_task_progress(
                source="upload.after_step_increment",
                task_id=task_id,
                uuid_value=uuid_value,
                session_id=session_id,
            )
        if feedback is not None:
            logger.info("Feedback request received: session_id={}, feedback={}, feedback_text={}", session_id, feedback, feedback_text)
            return {"status": "success", "message": "Feedback received"}

        screenshot_path = await _process_uploaded_images(images, agent.workspace)
        if not screenshot_path:
            screenshot_path = await _process_base64_images_from_form(request, agent.workspace)
        if not screenshot_path and not query:
            raise HTTPException(status_code=400, detail="至少需要提供query或图片")
        if not screenshot_path:
            return {"action_type": "wait", "reason": "等待屏幕截图", "thought": "等待屏幕截图"}

        if state is None:
            ended = "当前无进行中的 GUI 任务或任务已结束（可能已超时），请勿继续上传。"
            logger.info("[MobileGUI][upload.rejected] session_id={} reason=no_task_state", session_id)
            return {
                "action_type": "complete",
                "reason": ended,
                "thought": ended,
                "answer": ended,
            }

        try:
            result = await agent.execute_step(
                session_id=session_id,
                screenshot_path=str(screenshot_path),
                query=query,
                execution_history=[],
                app_name=package_name,
                user_response=user_response,
            )
            action_str, thought, reasoning = _extract_action_result(result)
            action_name, action_args = _parse_mobile_action(action_str)
            if action_name == "answer":
                action_name = "complete"
            response = _build_mobile_action_response(action_name, action_args, thought, reasoning)
            await _mark_task_complete_if_needed(request, task_id, action_name, action_args, thought, reasoning)

            # When upload returns complete, resolve the pending gui_task future so the tool gets the final answer.
            if action_name == "complete":
                effective_tracking_id = await self._resolve_tracking_session_id(session_id)
                if effective_tracking_id:
                    final_text = (
                        response.get("answer")
                        if isinstance(response.get("answer"), str) and str(response.get("answer", "")).strip()
                        else "任务已完成"
                    )
                    await self.connection_registry.resolve_gui_execute_result(
                        {
                            "request_id": effective_tracking_id,
                            "success": True,
                            "content": str(final_text).strip() or "任务已完成",
                        }
                    )
                    await self._remove_task_state(effective_tracking_id)
                    logger.info(
                        "[MobileGUI][upload.complete] request_id={} final_answer_preview={}",
                        effective_tracking_id,
                        str(final_text)[:120],
                    )
            return response
        except Exception as exc:
            logger.error("Error executing mobile step: {}", exc, exc_info=True)
            raise HTTPException(status_code=500, detail=f"Error processing request: {exc}") from exc
        finally:
            if screenshot_path and screenshot_path.exists():
                try:
                    screenshot_path.unlink()
                except Exception:
                    pass

    async def handle_mobile_next_action(
        self,
        request: Request,
        payload: MobileNextActionRequest,
    ) -> MobileNextActionResponse:
        agent = self.get_mobile_agent_for_task(request, payload.task_id)
        temp_dir = gui_temp_screenshots(agent.workspace)
        temp_dir.mkdir(parents=True, exist_ok=True)
        if payload.image_url.startswith(("http://", "https://")):
            raise HTTPException(status_code=400, detail="HTTP/HTTPS URL images not yet supported")
        screenshot_path = _save_base64_image(payload.image_url, temp_dir)
        try:
            result = await agent.execute_step(
                session_id=payload.duid,
                screenshot_path=str(screenshot_path),
                query=payload.query,
                execution_history=[],
                app_name=payload.package_name,
                user_response=payload.user_response,
            )
            action_str, thought, reasoning = _extract_action_result(result)
            action_name, action_args = _parse_mobile_action(action_str)
            if action_name == "answer":
                action_name = "complete"
            return MobileNextActionResponse(
                status=True,
                message=[MobileActionResponse(action=action_name, arguments=action_args, reason=reasoning, thought=thought)],
                task_id=payload.task_id,
            )
        finally:
            if screenshot_path.exists():
                try:
                    screenshot_path.unlink()
                except Exception:
                    pass

    async def dispatch_gui_execute_request(
        self,
        thread_id: str,
        query: str,
        chat_summary: str | None = None,
        timeout_s: int = 120,
        max_steps: int = 100,
        target_imei: str | None = None,
    ) -> dict[str, Any]:
        query = (query or "").strip()
        if not query:
            return {"success": False, "error": "missing query"}

        # Relay-only dispatch: mobile online/offline is owned by customer_service.
        # TopoClaw only forwards request and waits for gui_execute_result.
        normalized_thread_id = str(thread_id or "").strip()
        logger.info(
            "[GUI] dispatch_gui_execute_request: thread_id='{}', query='{}' relay_only=true",
            thread_id,
            query[:50],
        )

        request_id = f"gui_{uuid.uuid4().hex[:10]}"
        future: asyncio.Future[str] = asyncio.get_running_loop().create_future()
        await self.connection_registry.add_pending_gui(request_id, future)
        try:
            timeout_value = int(timeout_s)
        except (TypeError, ValueError):
            timeout_value = 120
        bounded_timeout = max(1, min(timeout_value, 600))
        try:
            max_steps_value = int(max_steps)
        except (TypeError, ValueError):
            max_steps_value = 100
        bounded_max_steps = max(1, min(max_steps_value, 200))
        await self._set_task_state(
            request_id,
            {
                "request_id": request_id,
                "thread_id": str(thread_id or ""),
                "started_at_ms": self._now_ms(),
                "deadline_at_ms": self._now_ms() + bounded_timeout * 1000,
                "timeout_s": bounded_timeout,
                "max_steps": bounded_max_steps,
                "step_count": 0,
            },
        )
        logger.info(
            "[MobileGUI][dispatch] request_id={} thread_id={} timeout_s={} max_steps={} query={}",
            request_id,
            thread_id or "(none)",
            bounded_timeout,
            bounded_max_steps,
            query[:120],
        )

        payload: dict[str, Any] = {
            "type": "gui_execute_request",
            "request_id": request_id,
            "query": query,
        }
        if chat_summary:
            payload["chat_summary"] = chat_summary

        relay_ok = False
        relay_reason = ""
        send_fn = self._direct_channel_send or self._outbound_publish
        if send_fn is not None:
            imei, resolve_reason = await self._resolve_imei_for_relay(
                normalized_thread_id,
                target_imei=target_imei,
                require_explicit_target_on_multi_online=True,
            )
            if imei:
                relay_payload_md: dict[str, Any] = {
                    "passthrough_type": "gui_execute_request",
                    "request_id": request_id,
                    "thread_id": normalized_thread_id,
                    "imei": imei,
                }
                if chat_summary:
                    relay_payload_md["chat_summary"] = chat_summary
                try:
                    await send_fn(
                        OutboundMessage(
                            channel="topomobile",
                            chat_id=normalized_thread_id or request_id,
                            content=query,
                            metadata=relay_payload_md,
                        )
                    )
                    relay_ok = True
                    logger.info(
                        "[MobileGUI][dispatch.relay] request_id={} thread_id={} imei={} direct={}",
                        request_id,
                        normalized_thread_id or "(none)",
                        imei,
                        self._direct_channel_send is not None,
                    )
                except Exception as exc:
                    relay_reason = f"relay send failed: {exc}"
            else:
                relay_reason = resolve_reason or "missing imei for relay"
        else:
            relay_reason = "outbound publisher unavailable"

        if not relay_ok:
            await self.connection_registry.pop_pending_gui(request_id)
            await self._remove_task_state(request_id)
            return {
                "success": False,
                "request_id": request_id,
                "error": f"GUI 请求下发失败：relay 未送达，原因: {relay_reason or 'unknown'}",
            }

        try:
            content = await asyncio.wait_for(future, timeout=float(bounded_timeout))
            await self._remove_task_state(request_id)
            return {"success": True, "request_id": request_id, "content": content}
        except asyncio.TimeoutError:
            await self._remove_task_state(request_id)
            return {
                "success": False,
                "request_id": request_id,
                "error": "执行超时：手机端未在限定时间内返回结果",
            }
        finally:
            await self.connection_registry.pop_pending_gui(request_id)

    async def dispatch_mobile_tool_request(
        self,
        *,
        thread_id: str,
        tool: str,
        args: dict[str, Any] | None = None,
        timeout_s: int = 20,
        protocol: str = "mobile_tool/v1",
    ) -> dict[str, Any]:
        normalized_thread_id = str(thread_id or "").strip()
        normalized_tool = str(tool or "").strip()
        if not normalized_thread_id:
            return {"success": False, "error": "missing thread_id"}
        if not normalized_tool:
            return {"success": False, "error": "missing tool"}

        request_id = f"mt_{uuid.uuid4().hex[:12]}"
        future: asyncio.Future[str] = asyncio.get_running_loop().create_future()
        await self.connection_registry.add_pending_mobile_tool(request_id, future)
        send_fn = self._direct_channel_send or self._outbound_publish
        imei, _ = await self._resolve_imei_for_relay(normalized_thread_id)
        if send_fn is None:
            await self.connection_registry.pop_pending_mobile_tool(request_id)
            return {
                "success": False,
                "request_id": request_id,
                "error": "mobile tool dispatch unavailable: outbound publisher missing",
            }
        if not imei:
            await self.connection_registry.pop_pending_mobile_tool(request_id)
            return {
                "success": False,
                "request_id": request_id,
                "error": "mobile tool dispatch failed: missing imei for relay",
            }

        try:
            timeout_value = int(timeout_s)
        except (TypeError, ValueError):
            timeout_value = 20
        bounded_timeout = max(3, min(timeout_value, 120))

        metadata: dict[str, Any] = {
            "passthrough_type": "mobile_tool_invoke",
            "request_id": request_id,
            "thread_id": normalized_thread_id,
            "conversation_id": normalized_thread_id,
            "imei": imei,
            "protocol": str(protocol or "mobile_tool/v1"),
            "tool": normalized_tool,
            "tool_args": args if isinstance(args, dict) else {},
        }
        try:
            await send_fn(
                OutboundMessage(
                    channel="topomobile",
                    chat_id=normalized_thread_id,
                    content=f"invoke:{normalized_tool}",
                    metadata=metadata,
                )
            )
        except Exception as exc:
            await self.connection_registry.pop_pending_mobile_tool(request_id)
            return {
                "success": False,
                "request_id": request_id,
                "error": f"mobile tool relay send failed: {exc}",
            }

        try:
            result_text = await asyncio.wait_for(future, timeout=float(bounded_timeout))
            return {"success": True, "request_id": request_id, "content": result_text}
        except asyncio.TimeoutError:
            return {
                "success": False,
                "request_id": request_id,
                "error": "mobile tool timeout: no result from phone in time",
            }
        finally:
            await self.connection_registry.pop_pending_mobile_tool(request_id)


__all__ = [
    "ActionResponse",
    "MobileGUIService",
    "MobileNextActionRequest",
    "MobileNextActionResponse",
    "dispatch_gui_execute_request",
    "dispatch_mobile_tool_request",
    "set_default_gui_service",
]
