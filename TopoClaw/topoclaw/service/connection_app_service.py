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

"""Application service for websocket connection and routing concerns.

Owns register/ping/stats/thread-subscription lifecycle logic for WS messages.
ChatService stays focused on chat-turn processing.
"""

from __future__ import annotations

from typing import Any, TYPE_CHECKING

from loguru import logger

if TYPE_CHECKING:
    from topoclaw.connection.device_registry import DeviceRegistry
    from topoclaw.connection.thread_binding_registry import ThreadBindingRegistry
    from topoclaw.connection.ws_registry import WSConnectionRegistry


class ConnectionAppService:
    """Connection orchestration around registries."""

    def __init__(
        self,
        *,
        registry: WSConnectionRegistry,
        device_registry: DeviceRegistry,
        thread_binding_registry: ThreadBindingRegistry,
    ) -> None:
        self._registry = registry
        self._device_registry = device_registry
        self._thread_binding_registry = thread_binding_registry
        self._cron_running_announced: set[str] = set()

    @staticmethod
    def _cron_task_id(thread_id: str, job_id: str | None = None) -> str:
        normalized_job_id = (job_id or "").strip()
        if normalized_job_id:
            return f"cron_{normalized_job_id}"
        normalized_thread = (thread_id or "").strip().replace(":", "_")
        return f"cron_{normalized_thread or 'unknown'}"

    @staticmethod
    def _cron_task_desc(job_name: str | None, job_query: str | None) -> str:
        query = (job_query or "").strip()
        if query:
            return query
        name = (job_name or "").strip()
        if name:
            return name
        return "定时任务"

    @staticmethod
    def _format_cron_running_event(task_id: str, description: str) -> str:
        return (
            "[Task Event] Background task is running\n"
            f"Task ID: {task_id}\n"
            "Type: scheduled_task\n"
            f"Description: {description}"
        )

    @staticmethod
    def _format_cron_completed_event(task_id: str, description: str, result: str) -> str:
        body = (
            "[Task Event] Background task completed\n"
            f"Task ID: {task_id}\n"
            "Type: scheduled_task\n"
            f"Description: {description}"
        )
        result_text = (result or "").strip()
        if result_text:
            body += f"\nResult: {result_text}"
        return body

    async def get_realtime_stats(self) -> dict[str, Any]:
        device_snap = await self._device_registry.snapshot()
        total_connections = await self._registry.total_connections()
        binding_snap = await self._thread_binding_registry.snapshot()
        return {
            "total_connections": total_connections,
            "unique_device_count": device_snap.get("unique_device_count", 0),
            "by_device_type": device_snap.get("by_device_type", {}),
            "bound_thread_count": binding_snap.get("bound_thread_count", 0),
            "bound_connections_by_thread": binding_snap.get("bound_connections_by_thread", {}),
        }

    async def list_online_devices(self, *, device_type: str | None = None) -> list[dict[str, Any]]:
        return await self._device_registry.list_devices(device_type=device_type)

    async def is_device_online(self, device_id: str) -> bool:
        normalized = (device_id or "").strip()
        if not normalized:
            return False
        info = await self._device_registry.get_device_info(normalized)
        return bool(info and info.get("online"))

    async def handle_ws_message(self, conn_id: str, msg: dict[str, Any]) -> bool:
        """Handle non-chat/non-gui websocket messages."""
        msg_type = str(msg.get("type") or "")
        if msg_type == "ping":
            return await self._handle_ping(conn_id, msg)
        if msg_type == "register":
            return await self._handle_register(conn_id, msg)
        if msg_type in {"stats", "ws_stats"}:
            return await self._handle_stats(conn_id)
        if msg_type == "subscribe_thread":
            return await self._handle_subscribe_thread(conn_id, msg)
        if msg_type == "unsubscribe_thread":
            return await self._handle_unsubscribe_thread(conn_id, msg)
        if msg_type == "code_execute_result":
            await self._registry.resolve_code_execute_result(msg)
            return False
        return not await self._send(conn_id, {"type": "error", "error": f"未知类型: {msg_type}"})

    async def try_mark_device_online_from_chat(self, conn_id: str, msg: dict[str, Any]) -> None:
        """Best-effort mark known device online from a chat payload.

        Some clients may send chat messages before a formal register message.
        If chat carries device_id/device_type and device exists in registry
        history, refresh its online status and current conn mapping.
        """
        payload = msg.get("payload")
        payload_obj = payload if isinstance(payload, dict) else {}
        conn_meta = await self._registry.get_metadata(conn_id)
        conn_meta_obj = conn_meta if isinstance(conn_meta, dict) else {}

        device_id = str(
            msg.get("device_id")
            or payload_obj.get("device_id")
            or conn_meta_obj.get("device_id")
            or ""
        ).strip()
        if not device_id:
            return

        hinted_type = str(
            msg.get("device_type")
            or payload_obj.get("device_type")
            or conn_meta_obj.get("device_type")
            or "unknown"
        ).strip().lower() or "unknown"
        existing = await self._device_registry.get_device_info(device_id)
        if existing and (existing.get("device_type") or "").strip():
            hinted_type = str(existing.get("device_type") or hinted_type).strip().lower() or hinted_type

        supports_code_execute = (
            bool(existing.get("supports_code_execute"))
            if existing is not None
            else hinted_type == "pc"
        )
        supports_gui_execute = (
            bool(existing.get("supports_gui_execute"))
            if existing is not None
            else hinted_type == "mobile"
        )
        await self._device_registry.register_device(
            device_id=device_id,
            conn_id=conn_id,
            device_type=hinted_type,
            supports_code_execute=supports_code_execute,
            supports_gui_execute=supports_gui_execute,
        )

        # Chat payload already carries thread_id in normal websocket turns.
        # Auto-bind here so server-side push (cron/assistant_push) can route
        # by thread even when client-side subscribe_thread is missing/delayed.
        thread_id = str(
            msg.get("thread_id")
            or msg.get("session_id")
            or payload_obj.get("thread_id")
            or payload_obj.get("session_id")
            or ""
        ).strip()
        if thread_id:
            await self._thread_binding_registry.bind(thread_id, device_id)
            await self._device_registry.bind_thread(device_id, thread_id)
            await self._registry.set_metadata(conn_id, "last_chat_thread_id", thread_id)
        await self._registry.set_metadata(conn_id, "device_id", device_id)
        await self._registry.set_metadata(conn_id, "device_type", hinted_type)
        user_imei = str(
            msg.get("imei")
            or payload_obj.get("imei")
            or conn_meta_obj.get("imei")
            or ""
        ).strip()
        if user_imei:
            await self._registry.set_metadata(conn_id, "imei", user_imei)

        thread_id = str(
            msg.get("thread_id")
            or msg.get("session_id")
            or payload_obj.get("thread_id")
            or payload_obj.get("session_id")
            or ""
        ).strip()
        if thread_id:
            await self._thread_binding_registry.bind(thread_id, device_id)
            await self._device_registry.bind_thread(device_id, thread_id)
            await self._registry.set_metadata(conn_id, "thread_id", thread_id)
            await self._registry.set_metadata(conn_id, "last_chat_thread_id", thread_id)
            await self._registry.set_metadata(conn_id, "multi_thread", False)
        else:
            await self._registry.set_metadata(conn_id, "multi_thread", True)

        logger.info(
            "[ws] chat marked device online device_id={} device_type={} conn_id={} known_device={} thread_id={}",
            device_id,
            hinted_type,
            conn_id,
            existing is not None,
            thread_id or "(none)",
        )

    async def release_connection(self, conn_id: str) -> None:
        """Cleanup device + thread binding state for disconnected connection."""
        device_id = await self._device_registry.mark_offline_by_conn(conn_id)
        if device_id:
            await self._thread_binding_registry.unbind_all_for_device(device_id)
            await self._device_registry.clear_threads(device_id)
        stats = await self.get_realtime_stats()
        logger.info(
            "[ws] disconnected total={} unique_devices={} by_type={}",
            stats["total_connections"],
            stats["unique_device_count"],
            stats["by_device_type"],
        )

    async def pick_device_for_thread(
        self,
        thread_id: str,
        *,
        preferred_type: str | None = None,
    ) -> dict[str, Any] | None:
        normalized_thread_id = (thread_id or "").strip()
        if not normalized_thread_id:
            return None
        device_ids = await self.get_devices_for_thread(normalized_thread_id)
        for did in device_ids:
            info = await self._device_registry.get_device_info(did)
            if not info:
                continue
            if preferred_type and (info.get("device_type") or "").lower() != preferred_type.lower():
                continue
            return info
        return None

    async def push_event(
        self,
        thread_id: str,
        payload: dict[str, Any],
        *,
        allow_imei_fallback: bool = False,
    ) -> int:
        normalized_thread_id = (thread_id or "").strip()
        if not normalized_thread_id:
            return 0
        event = {"thread_id": normalized_thread_id, **payload}
        return await self.push_to_thread(
            normalized_thread_id,
            event,
            allow_imei_fallback=allow_imei_fallback,
        )

    async def push_cron_progress(
        self,
        thread_id: str,
        content: str,
        *,
        tool_hint: bool = False,
        job_id: str | None = None,
        job_name: str | None = None,
        job_query: str | None = None,
    ) -> int:
        cron_task_id = self._cron_task_id(thread_id, job_id)
        cron_desc = self._cron_task_desc(job_name, job_query)
        running_key = f"{(thread_id or '').strip()}::{cron_task_id}"
        sent_total = 0
        if running_key not in self._cron_running_announced:
            sent_total += await self.push_event(
                thread_id,
                {
                    "type": "assistant_push",
                    "content": self._format_cron_running_event(cron_task_id, cron_desc),
                    "source": "cron",
                },
                allow_imei_fallback=True,
            )
            self._cron_running_announced.add(running_key)

        normalized_content = content or ""
        if not normalized_content.strip():
            return sent_total
        if tool_hint:
            tool_name = normalized_content.split("(", 1)[0].strip() or "tool"
            sent_total += await self.push_event(
                thread_id,
                {"type": "tool_call", "name": tool_name},
                allow_imei_fallback=True,
            )
            return sent_total
        sent_total += await self.push_event(
            thread_id,
            {"type": "delta", "content": normalized_content},
            allow_imei_fallback=True,
        )
        return sent_total

    async def push_cron_done(
        self,
        thread_id: str,
        response: str,
        *,
        job_id: str | None = None,
        job_name: str | None = None,
        job_query: str | None = None,
    ) -> int:
        normalized_response = response or ""
        cron_task_id = self._cron_task_id(thread_id, job_id)
        cron_desc = self._cron_task_desc(job_name, job_query)
        running_key = f"{(thread_id or '').strip()}::{cron_task_id}"
        self._cron_running_announced.discard(running_key)
        sent_total = 0
        sent_total += await self.push_event(
            thread_id,
            {
                "type": "assistant_push",
                "content": self._format_cron_completed_event(cron_task_id, cron_desc, normalized_response),
                "source": "cron",
            },
            allow_imei_fallback=True,
        )
        if normalized_response:
            sent_total += await self.push_event(
                thread_id,
                {"type": "assistant_push", "content": normalized_response},
                allow_imei_fallback=True,
            )
            sent_total += await self.push_event(
                thread_id,
                {"type": "delta", "content": normalized_response},
                allow_imei_fallback=True,
            )
        sent_total += await self.push_event(
            thread_id,
            {
                "type": "done",
                "response": normalized_response,
                "need_execution": None,
                "chat_summary": None,
                "source": "cron",
            },
            allow_imei_fallback=True,
        )
        logger.info("[cron] push_cron_done thread_id={} sent={} response_len={}", thread_id, sent_total, len(normalized_response))
        return sent_total

    async def get_devices_for_thread(self, thread_id: str) -> list[str]:
        return await self._thread_binding_registry.get_devices_for_thread(thread_id)

    async def unbind_thread_for_connection(self, conn_id: str, thread_id: str) -> bool:
        """Remove one thread binding for the device behind ``conn_id``."""
        normalized_conn_id = (conn_id or "").strip()
        normalized_thread_id = (thread_id or "").strip()
        if not normalized_conn_id or not normalized_thread_id:
            return False

        device_id = await self._device_registry.get_device_id_by_conn(normalized_conn_id)
        if not device_id:
            return False

        await self._thread_binding_registry.unbind(normalized_thread_id, device_id)
        await self._device_registry.unbind_thread(device_id, normalized_thread_id)

        meta = await self._registry.get_metadata(normalized_conn_id)
        if isinstance(meta, dict):
            meta_thread_id = str(meta.get("thread_id") or "").strip()
            if meta_thread_id == normalized_thread_id:
                await self._registry.set_metadata(normalized_conn_id, "thread_id", None)
        return True

    async def push_to_thread(
        self,
        thread_id: str,
        payload: dict[str, Any],
        *,
        device_type: str | None = None,
        exclude_device_id: str | None = None,
        exclude_conn_id: str | None = None,
        allow_imei_fallback: bool = False,
    ) -> int:
        device_ids = await self._thread_binding_registry.get_devices_for_thread(thread_id)
        sent = 0
        for target_device_id in device_ids:
            if exclude_device_id and target_device_id == exclude_device_id:
                continue
            info = await self._device_registry.get_device_info(target_device_id)
            if not info:
                continue
            target_conn_id = str(info.get("conn_id") or "")
            if not target_conn_id:
                continue
            if exclude_conn_id and target_conn_id == exclude_conn_id:
                continue
            if device_type and str(info.get("device_type") or "").lower() != device_type.lower():
                continue
            if await self._registry.send(target_conn_id, payload):
                sent += 1
            else:
                await self._device_registry.mark_offline(target_device_id, expected_conn_id=target_conn_id)
                await self._thread_binding_registry.unbind_all_for_device(target_device_id)
                await self._device_registry.clear_threads(target_device_id)

        # Fallback path:
        # If thread->device binding is missing/stale, try active websocket
        # connections whose metadata.thread_id matches this thread.
        if sent == 0:
            snapshots = await self._registry.snapshot_connections()
            normalized_thread_id = (thread_id or "").strip()
            for snap in snapshots:
                conn_id = str(snap.get("conn_id") or "").strip()
                if not conn_id:
                    continue
                meta = snap.get("metadata")
                if not isinstance(meta, dict):
                    continue
                meta_thread = str(meta.get("thread_id") or "").strip()
                if meta_thread != normalized_thread_id:
                    continue
                if exclude_conn_id and conn_id == exclude_conn_id:
                    continue
                if await self._registry.send(conn_id, payload):
                    sent += 1
        if sent == 0 and allow_imei_fallback:
            sent += await self._fallback_push_to_online_imei_conn(thread_id, payload, exclude_conn_id=exclude_conn_id)
        return sent

    @staticmethod
    def _infer_imei_from_thread_id(thread_id: str) -> str:
        tid = (thread_id or "").strip()
        if not tid:
            return ""
        prefix = tid.split("_", 1)[0].strip()
        return prefix if len(prefix) >= 8 else ""

    async def _fallback_push_to_online_imei_conn(
        self,
        thread_id: str,
        payload: dict[str, Any],
        *,
        exclude_conn_id: str | None = None,
    ) -> int:
        imei = self._infer_imei_from_thread_id(thread_id)
        snapshots = await self._registry.snapshot_connections()
        # session 线程（如 UUID）可能不带 imei 前缀；尝试从连接 metadata 反查 imei。
        if not imei:
            normalized_thread = (thread_id or "").strip()
            for snap in snapshots:
                meta = snap.get("metadata")
                if not isinstance(meta, dict):
                    continue
                meta_last_thread = str(meta.get("last_chat_thread_id") or "").strip()
                meta_thread = str(meta.get("thread_id") or "").strip()
                if normalized_thread and normalized_thread in {meta_last_thread, meta_thread}:
                    guessed = str(meta.get("imei") or "").strip()
                    if guessed:
                        imei = guessed
                        break
        if not imei:
            return 0
        preferred: tuple[str, str] | None = None
        candidate: tuple[str, str] | None = None
        for snap in snapshots:
            conn_id = str(snap.get("conn_id") or "").strip()
            if not conn_id:
                continue
            if exclude_conn_id and conn_id == exclude_conn_id:
                continue
            meta = snap.get("metadata")
            if not isinstance(meta, dict):
                continue
            meta_imei = str(meta.get("imei") or "").strip()
            if meta_imei != imei:
                continue
            meta_thread = str(meta.get("last_chat_thread_id") or meta.get("thread_id") or "").strip()
            if not candidate:
                candidate = (conn_id, meta_thread)
            if str(meta.get("device_type") or "").strip().lower() == "pc":
                preferred = (conn_id, meta_thread)
                break
        target = preferred or candidate
        if not target:
            return 0
        target_conn_id, target_thread_id = target
        payload_to_send = dict(payload)
        if target_thread_id and target_thread_id != str(payload_to_send.get("thread_id") or "").strip():
            payload_to_send.setdefault("original_thread_id", str(payload_to_send.get("thread_id") or "").strip())
            payload_to_send["thread_id"] = target_thread_id
        ok = await self._registry.send(target_conn_id, payload_to_send)
        if ok:
            logger.info(
                "[cron] imei fallback push imei={} original_thread_id={} target_thread_id={} conn_id={}",
                imei,
                thread_id,
                str(payload_to_send.get("thread_id") or ""),
                target_conn_id,
            )
            return 1
        return 0

    async def _handle_ping(self, conn_id: str, msg: dict[str, Any]) -> bool:
        ping_payload = dict(msg or {})
        pong_payload: dict[str, Any] = {"type": "pong"}
        request_id = ping_payload.get("request_id")
        if request_id is not None:
            pong_payload["request_id"] = request_id
        logger.debug("[ws] ping received conn_id={} payload={}", conn_id, ping_payload)
        ok = await self._send(conn_id, pong_payload)
        logger.debug("[ws] pong sent conn_id={} payload={} ok={}", conn_id, pong_payload, ok)
        return not ok

    async def _handle_register(self, conn_id: str, msg: dict[str, Any]) -> bool:
        device_id = str(msg.get("device_id") or "").strip()
        if not device_id:
            return not await self._send(conn_id, {"type": "error", "error": "register 需提供 device_id"})
        thread_id = str(msg.get("thread_id") or "").strip()
        imei = str(msg.get("imei") or "").strip()
        device_type = str(msg.get("device_type") or "unknown").strip().lower() or "unknown"
        supports_code_execute = msg.get("supports_code_execute")
        if supports_code_execute is None:
            supports_code_execute = device_type == "pc"
        supports_gui_execute = msg.get("supports_gui_execute")
        if supports_gui_execute is None:
            supports_gui_execute = device_type == "mobile"
        multi_thread = not thread_id

        await self._device_registry.register_device(
            device_id=device_id,
            conn_id=conn_id,
            device_type=device_type,
            supports_code_execute=bool(supports_code_execute),
            supports_gui_execute=bool(supports_gui_execute),
        )
        await self._registry.set_metadata(conn_id, "device_id", device_id)
        await self._registry.set_metadata(conn_id, "device_type", device_type)
        await self._registry.set_metadata(conn_id, "multi_thread", multi_thread)
        await self._registry.set_metadata(conn_id, "thread_id", thread_id if not multi_thread else None)
        if thread_id:
            await self._registry.set_metadata(conn_id, "last_chat_thread_id", thread_id)
        if imei:
            await self._registry.set_metadata(conn_id, "imei", imei)

        if thread_id:
            await self._thread_binding_registry.bind(thread_id, device_id)
            await self._device_registry.bind_thread(device_id, thread_id)

        if not await self._send(conn_id, {"type": "registered", "thread_id": thread_id}):
            return True
        stats = await self.get_realtime_stats()
        logger.info(
            "[ws] register device_id={} device_type={} thread_id={} total={} unique_devices={}",
            device_id,
            device_type,
            thread_id or "(multi-thread)",
            stats["total_connections"],
            stats["unique_device_count"],
        )
        return False

    async def _handle_stats(self, conn_id: str) -> bool:
        stats = await self.get_realtime_stats()
        return not await self._send(conn_id, {"type": "ws_stats", **stats})

    async def _handle_subscribe_thread(self, conn_id: str, msg: dict[str, Any]) -> bool:
        thread_id = str(msg.get("thread_id") or "").strip()
        if not thread_id:
            return False
        meta = await self._registry.get_metadata(conn_id)
        if not meta or not meta.get("multi_thread"):
            return False
        device_id = await self._device_registry.get_device_id_by_conn(conn_id)
        if not device_id:
            return False
        ok = await self._thread_binding_registry.bind(thread_id, device_id)
        if ok:
            await self._device_registry.bind_thread(device_id, thread_id)
            await self._registry.set_metadata(conn_id, "last_chat_thread_id", thread_id)
        logger.info("[ws] subscribe_thread thread_id={} ok={}", thread_id, ok)
        return False

    async def _handle_unsubscribe_thread(self, conn_id: str, msg: dict[str, Any]) -> bool:
        thread_id = str(msg.get("thread_id") or "").strip()
        if not thread_id:
            return False
        device_id = await self._device_registry.get_device_id_by_conn(conn_id)
        if not device_id:
            return False
        ok = await self._thread_binding_registry.unbind(thread_id, device_id)
        if ok:
            await self._device_registry.unbind_thread(device_id, thread_id)
        logger.info("[ws] unsubscribe_thread thread_id={} ok={}", thread_id, ok)
        return False

    async def _send(self, conn_id: str, payload: dict[str, Any]) -> bool:
        return await self._registry.send(conn_id, payload)
