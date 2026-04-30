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

"""Chat service – chat turn processing.

Responsibilities:
- Process chat turns and stream progress/done events.
"""

from __future__ import annotations

import asyncio
import base64
import json
import mimetypes
import re
import tempfile
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from fastapi import HTTPException
from loguru import logger

from topoclaw.config.schema import Config
from topoclaw.agent.session_keys import (
    DEFAULT_AGENT_ID,
    normalize_agent_id,
    normalize_client_thread_id,
    websocket_session_key,
    websocket_session_keys_to_delete,
)
from topoclaw.agent.tools.message import MessageTool
from topoclaw.bus.events import OutboundMessage
from topoclaw.models.api import ChatRequest
from topoclaw.models.constant import (
    TOOL_GUARD_CLIENT_CONFIRM_VALUES,
    TOOL_GUARD_CONFIRM_TYPE_INVALID,
    TOOL_GUARD_CONFIRM_TYPE_TIMEOUT,
    TOOL_GUARD_TIMEOUT_SEC,
    normalize_tool_guard_choice,
)
from topoclaw.connection.message_adapter import MessageAdapter
from topoclaw.connection.ws_registry import WSConnectionRegistry
from topoclaw.service.gui_mobile_service import dispatch_mobile_tool_request
from topoclaw.service.runtime import ServiceRuntime


# ── helpers (unchanged from original) ─────────────────────────


def _extract_fields(raw: str) -> tuple[str, bool | None, str | None, str | None, dict[str, Any] | None]:
    text = raw or ""
    try:
        obj = json.loads(text)
    except Exception:
        return text, None, None, None, None

    if not isinstance(obj, dict):
        return text, None, None, None, None

    response = obj.get("response")
    if not isinstance(response, str):
        response = text
    need_execution = obj.get("need_execution")
    reason = obj.get("reason")
    chat_summary = obj.get("chat_summary")
    skill_generated = obj.get("skill_generated")
    return (
        response,
        need_execution if isinstance(need_execution, bool) else None,
        reason if isinstance(reason, str) else None,
        chat_summary if isinstance(chat_summary, str) else None,
        skill_generated if isinstance(skill_generated, dict) else None,
    )


def _save_base64_images(workspace: Path, images: list[str]) -> list[Path]:
    if not images:
        return []
    if len(images) > 3:
        raise HTTPException(status_code=400, detail="images supports up to 3 items")

    output: list[Path] = []
    temp_dir = workspace / "temp_api_images"
    temp_dir.mkdir(parents=True, exist_ok=True)

    for item in images:
        data = item.split(",", 1)[1] if item.startswith("data:") else item
        try:
            decoded = base64.b64decode(data)
        except Exception as exc:
            raise HTTPException(status_code=400, detail=f"invalid base64 image: {exc}") from exc
        tmp = tempfile.NamedTemporaryFile(dir=temp_dir, suffix=".png", delete=False)
        tmp.write(decoded)
        tmp.close()
        output.append(Path(tmp.name))
    return output


def _cleanup(paths: list[Path]) -> None:
    for p in paths:
        try:
            if p.exists():
                p.unlink()
        except Exception:
            pass


def _build_media_payload(media_path: str) -> tuple[str, str, str] | None:
    p = Path(str(media_path or "").strip())
    if not p or not p.exists() or not p.is_file():
        return None
    try:
        raw = p.read_bytes()
    except Exception:
        return None
    if not raw:
        return None
    b64 = base64.b64encode(raw).decode("ascii")
    file_name = p.name or "文件"
    mime, _ = mimetypes.guess_type(file_name)
    msg_type = "image" if isinstance(mime, str) and mime.startswith("image/") else "file"
    return b64, file_name, msg_type


_MARKDOWN_IMAGE_RE = re.compile(r"!\[[^\]]*]\(([^)]+)\)")
_ASSISTANT_MEDIA_MARKER_PREFIX = "[[__TC_MEDIA_"


def _normalize_markdown_local_path(raw: str) -> str:
    token = str(raw or "").strip().strip("<>").strip()
    if not token:
        return ""
    if (token.startswith('"') and token.endswith('"')) or (token.startswith("'") and token.endswith("'")):
        token = token[1:-1].strip()
    token = token.replace("%5C", "\\").replace("%5c", "\\").replace("%2F", "/").replace("%2f", "/")
    try:
        token = unquote(token)
    except Exception:
        pass
    # Markdown destination may swallow slash after drive letter: D:Users\foo
    if re.match(r"^[a-zA-Z]:(?![\\/])", token):
        token = f"{token[:2]}\\{token[2:]}"
    return token.strip()


def _resolve_local_media_path(workspace: Path, raw_target: str) -> Path | None:
    token = _normalize_markdown_local_path(raw_target)
    if not token:
        return None
    low = token.lower()
    if low.startswith(("http://", "https://", "data:", "blob:")):
        return None
    # Has custom scheme but is not a local drive path
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:", token) and not re.match(r"^[a-zA-Z]:[\\/]", token):
        return None
    p = Path(token)
    if not p.is_absolute():
        p = (workspace / p).resolve()
    if not p.exists() or not p.is_file():
        return None
    return p


def _materialize_markdown_local_images(
    workspace: Path,
    content: str,
) -> tuple[str, list[tuple[str, str, str]]]:
    text = str(content or "")
    if not text:
        return text, []

    media_payloads: list[tuple[str, str, str]] = []
    media_index_by_path: dict[str, int] = {}

    def _replace(match: re.Match[str]) -> str:
        raw_target = match.group(1) or ""
        resolved = _resolve_local_media_path(workspace, raw_target)
        if resolved is None:
            return match.group(0)
        key = str(resolved)
        if key in media_index_by_path:
            idx = media_index_by_path[key]
            return f"{_ASSISTANT_MEDIA_MARKER_PREFIX}{idx}__]]"
        payload = _build_media_payload(key)
        if not payload:
            return match.group(0)
        idx = len(media_payloads)
        media_payloads.append(payload)
        media_index_by_path[key] = idx
        return f"{_ASSISTANT_MEDIA_MARKER_PREFIX}{idx}__]]"

    rewritten = _MARKDOWN_IMAGE_RE.sub(_replace, text)
    return rewritten, media_payloads


_FOCUS_SKILL_TOP_AGENT_IDS = {
    DEFAULT_AGENT_ID,
    "topoclaw",
    "custom_topoclaw",
}
_DEEPLINK_CANDIDATE_RE = re.compile(r"(intent:[^\s\"'<>]+|[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s\"'<>]+)")


def _normalize_focus_skills(raw: Any) -> list[str]:
    if not isinstance(raw, list):
        return []
    out: list[str] = []
    seen: set[str] = set()
    for item in raw:
        name = str(item or "").strip()
        if not name:
            continue
        key = name.lower()
        if key in seen:
            continue
        seen.add(key)
        out.append(name)
        if len(out) >= 8:
            break
    return out


def _inject_focus_skills_hint(message: str, focus_skills: list[str]) -> str:
    base = str(message or "").strip()
    if not focus_skills:
        return base
    hint = f"需要重点关注的工具：{'、'.join(focus_skills)}"
    if not base:
        return hint
    return f"{base}\n\n{hint}\n请优先使用这些工具；若不适用，再选择其他工具。"


def _inject_user_imei_hint(message: str, user_imei: str) -> str:
    base = str(message or "").strip()
    imei = str(user_imei or "").strip()
    if not imei:
        return base
    hint = (
        f"当前应用登录用户IMEI：{imei}\n"
    )
    if imei in base:
        return base
    if not base:
        return hint
    return f"{base}\n\n{hint}"


class ChatService:
    """Chat service focused on chat-turn processing."""

    def __init__(
        self,
        runtime: ServiceRuntime,
        workspace: Path,
        *,
        registry: WSConnectionRegistry | None = None,
        topoclaw_config: Config | None = None,
    ) -> None:
        self.runtime = runtime
        self.workspace = workspace
        self.message_adapter = MessageAdapter()

        self._registry = registry
        self._topoclaw_config = topoclaw_config
        self._chat_queues: dict[str, asyncio.Queue[dict[str, Any] | None]] = {}
        # conn_id -> (confirmation_id, future) for in-flight tool_guard_prompt
        self._tool_guard_waiters: dict[str, tuple[str, asyncio.Future[str]]] = {}

    @property
    def _auto_execute_deeplink_enabled(self) -> bool:
        cfg = self._topoclaw_config
        if not cfg:
            return False
        return bool(cfg.tools.deeplink_lookup.enabled and cfg.tools.deeplink_lookup.auto_execute_mobile)

    @staticmethod
    def _extract_first_deeplink(text: str) -> str | None:
        for match in _DEEPLINK_CANDIDATE_RE.finditer(str(text or "")):
            candidate = match.group(1).strip().rstrip(".,;)]}，。；）】")
            lower = candidate.lower()
            if lower.startswith(("http://", "https://", "file://")):
                continue
            return candidate
        return None

    async def _auto_execute_deeplink_if_needed(
        self,
        *,
        thread_id: str,
        response_text: str,
        agent_id: str,
    ) -> tuple[str, dict[str, Any] | None]:
        if not self._auto_execute_deeplink_enabled:
            return response_text, None
        if str(agent_id or "").strip().lower() not in _FOCUS_SKILL_TOP_AGENT_IDS:
            return response_text, None

        deeplink = self._extract_first_deeplink(response_text)
        if not deeplink:
            return response_text, None

        result = await dispatch_mobile_tool_request(
            thread_id=thread_id,
            tool="device.open_deeplink",
            args={"deeplink": deeplink},
            timeout_s=25,
            protocol="mobile_tool/v1",
        )
        if bool(result.get("success")):
            result_text = str(result.get("content") or "").strip() or "手机端已执行 deeplink"
            appended = f"{response_text}\n\n[自动执行结果] {result_text}"
            return appended, {
                "deeplink": deeplink,
                "ok": True,
                "result": result_text,
                "request_id": str(result.get("request_id") or ""),
            }

        err = str(result.get("error") or "mobile deeplink execution failed")
        appended = f"{response_text}\n\n[自动执行结果] 执行失败：{err}"
        return appended, {
            "deeplink": deeplink,
            "ok": False,
            "error": err,
            "request_id": str(result.get("request_id") or ""),
        }

    # ── connection lifecycle (called from route) ──────────────

    async def accept_connection(self, conn_id: str) -> None:
        """Prepare internal state for a newly accepted connection."""
        self._chat_queues[conn_id] = asyncio.Queue()

    def shutdown_chat_queue(self, conn_id: str) -> None:
        """Signal the chat worker to stop (safe to call from sync context)."""
        queue = self._chat_queues.get(conn_id)
        if queue:
            try:
                queue.put_nowait(None)
            except asyncio.QueueFull:
                pass

    async def next_chat_message(self, conn_id: str) -> dict[str, Any] | None:
        """Block until the next chat message (or None for shutdown)."""
        queue = self._chat_queues.get(conn_id)
        if not queue:
            return None
        return await queue.get()

    async def enqueue_chat_message(self, conn_id: str, msg: dict[str, Any]) -> None:
        """Queue a chat message from websocket receiver for worker processing."""
        queue = self._chat_queues.get(conn_id)
        if not queue:
            return
        await queue.put(msg)
        logger.info(
            "[ws] chat queued thread_id={} message_preview={}",
            str(msg.get("thread_id") or msg.get("session_id") or "").strip() or "(missing)",
            str(msg.get("message") or "")[:120],
        )

    def submit_tool_guard_confirmation(self, conn_id: str, msg: dict[str, Any]) -> bool:
        """
        Resolve a pending ``tool_guard_prompt`` from a client message
        ``{ "type": "user_confirmed", "confirmation_id": "...", "content": "<see TOOL_GUARD_CLIENT_CONFIRM_VALUES>" }``.
        """
        entry = self._tool_guard_waiters.get(conn_id)
        if not entry:
            return False
        confirmation_id, fut = entry
        if str(msg.get("confirmation_id") or "") != confirmation_id:
            return False
        if fut.done():
            return False
        choice = normalize_tool_guard_choice(msg)
        self._tool_guard_waiters.pop(conn_id, None)
        fut.set_result(choice)
        return True

    # ── chat turn processing (WS path) ───────────────────────

    async def handle_chat_turn(self, conn_id: str, msg: dict[str, Any]) -> dict[str, Any] | None:
        """Process a single chat turn received from a WS connection."""
        conn_meta: dict[str, Any] = {}
        if self._registry:
            conn_meta = (await self._registry.get_metadata(conn_id)) or {}
        thread_id = str(msg.get("thread_id") or "").strip()
        if not thread_id:
            thread_id = str(msg.get("session_id") or "").strip()
        if not thread_id:
            thread_id = str(conn_meta.get("thread_id") or "").strip()

        message = str(msg.get("message", "") or "")
        images = msg.get("images")
        user_imei = str(msg.get("imei") or conn_meta.get("imei") or "").strip()

        if not thread_id:
            await self._send(conn_id, {"type": "error", "error": "缺少 thread_id"})
            return None

        raw_agent = msg.get("agent_id")
        agent_kw: str | None = None
        if raw_agent is not None:
            agent_kw = str(raw_agent).strip() or None

        reg0 = getattr(self.runtime, "agent_registry", None)
        if reg0:
            _, _, canonical_agent = await reg0.materialize(agent_kw)
        else:
            canonical_agent = DEFAULT_AGENT_ID

        focus_skills = _normalize_focus_skills(msg.get("focus_skills"))
        if focus_skills and str(canonical_agent or "").strip().lower() in _FOCUS_SKILL_TOP_AGENT_IDS:
            message = _inject_focus_skills_hint(message, focus_skills)
        if user_imei and str(canonical_agent or "").strip().lower() in _FOCUS_SKILL_TOP_AGENT_IDS:
            message = _inject_user_imei_hint(message, user_imei)

        req = ChatRequest(
            thread_id=thread_id,
            message=message,
            images=images or [],
            agent_id=agent_kw,
        )
        delta_chunks: list[str] = []

        async def on_tool_message(tool_msg: OutboundMessage) -> None:
            text = str(tool_msg.content or "")
            media_items = [
                str(item).strip()
                for item in (tool_msg.media or [])
                if str(item).strip()
            ]
            if media_items:
                for i, media_path in enumerate(media_items):
                    payload = _build_media_payload(media_path)
                    if not payload:
                        continue
                    b64, file_name, message_type = payload
                    await self._send(
                        conn_id,
                        {
                            "type": "assistant_media",
                            "thread_id": thread_id,
                            "agent_id": canonical_agent,
                            "content": text if i == 0 else "",
                            "message_type": message_type,
                            "file_base64": b64,
                            "file_name": file_name,
                        },
                    )
                return
            if text:
                await self._send(
                    conn_id,
                    {
                        "type": "delta",
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "content": text,
                    },
                )

        async def progress(
            content: str,
            *,
            tool_hint: bool = False,
            tool_guard: bool = False,
            reasoning: bool = False,
        ) -> str | None:
            if tool_guard:
                confirmation_id = str(uuid.uuid4())
                fut: asyncio.Future[str] = asyncio.get_running_loop().create_future()
                old = self._tool_guard_waiters.get(conn_id)
                if old:
                    _oid, old_fut = old
                    if not old_fut.done():
                        old_fut.set_result(TOOL_GUARD_CONFIRM_TYPE_INVALID)
                self._tool_guard_waiters[conn_id] = (confirmation_id, fut)
                await self._send(
                    conn_id,
                    {
                        "type": "tool_guard_prompt",
                        "confirmation_id": confirmation_id,
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "content": content,
                        "timeout_sec": TOOL_GUARD_TIMEOUT_SEC,
                        "choices": list(TOOL_GUARD_CLIENT_CONFIRM_VALUES),
                    },
                )
                try:
                    return await asyncio.wait_for(fut, TOOL_GUARD_TIMEOUT_SEC)
                except asyncio.TimeoutError:
                    popped = self._tool_guard_waiters.pop(conn_id, None)
                    if popped and popped[0] == confirmation_id and not popped[1].done():
                        popped[1].set_result(TOOL_GUARD_CONFIRM_TYPE_TIMEOUT)
                    return TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
                finally:
                    self._tool_guard_waiters.pop(conn_id, None)

            if tool_hint:
                await self._send(
                    conn_id,
                    {
                        "type": "tool_call",
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "name": content.split("(", 1)[0].strip(),
                    },
                )
            elif reasoning:
                await self._send(
                    conn_id,
                    {
                        "type": "assistant_reasoning",
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "content": content,
                    },
                )
            else:
                delta_chunks.append(content)
                await self._send(
                    conn_id,
                    {
                        "type": "delta",
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "content": content,
                    },
                )
            return None

        try:
            response, need_execution, reason, chat_summary, skill_generated = await self.run_chat_turn(
                req,
                inbound_channel="websocket",
                progress_cb=progress,
                include_skill=True,
                agent_id=agent_kw,
                metadata={"thread_id": thread_id, "agent_id": canonical_agent, "imei": user_imei or None},
                tool_message_sink=on_tool_message,
            )
        except asyncio.CancelledError:
            logger.info("[ws] chat turn cancelled thread_id={}", thread_id)
            return None
        except Exception as exc:
            await self._send(conn_id, {"type": "error", "error": str(exc)})
            return None

        if skill_generated:
            await self._send(
                conn_id,
                {
                    "type": "skill_generated",
                    "thread_id": thread_id,
                    "agent_id": canonical_agent,
                    "skill": skill_generated,
                },
            )

        final_response = response or ""
        if not final_response and delta_chunks:
            final_response = "".join(delta_chunks)
        final_response, deeplink_exec_result = await self._auto_execute_deeplink_if_needed(
            thread_id=thread_id,
            response_text=final_response,
            agent_id=canonical_agent,
        )
        final_response, inline_media_payloads = _materialize_markdown_local_images(
            self.workspace,
            final_response,
        )

        done_payload: dict[str, Any] = {
            "type": "done",
            "thread_id": thread_id,
            "agent_id": canonical_agent,
            "response": final_response,
            "need_execution": need_execution,
            "chat_summary": chat_summary,
        }
        if deeplink_exec_result is not None:
            done_payload["deeplink_auto_execution"] = deeplink_exec_result

        if final_response and not delta_chunks:
            await self._send(
                conn_id,
                {
                    "type": "delta",
                    "thread_id": thread_id,
                    "agent_id": canonical_agent,
                    "content": final_response,
                },
            )
        if inline_media_payloads:
            for i, payload in enumerate(inline_media_payloads):
                b64, file_name, message_type = payload
                await self._send(
                    conn_id,
                    {
                        "type": "assistant_media",
                        "thread_id": thread_id,
                        "agent_id": canonical_agent,
                        "content": final_response if i == 0 and not delta_chunks else "",
                        "message_type": message_type,
                        "file_base64": b64,
                        "file_name": file_name,
                    },
                )

        if reason:
            done_payload["reason"] = reason
        await self._send(conn_id, done_payload)
        logger.info(
            "[ws] done sent thread_id={} response_len={} need_execution={}",
            thread_id,
            len(final_response),
            need_execution,
        )
        return {"thread_id": thread_id, "response": final_response, "agent_id": canonical_agent}

    async def delete_websocket_session(self, conn_id: str, msg: dict[str, Any]) -> dict[str, Any]:
        """
        Remove the websocket session for ``agent_id`` + ``thread_id`` on the
        correct agent workspace: cancel subagents bound to that session key,
        delete the jsonl, and drop cache entry.

        Does not stop GUI tasks; callers should invoke GUI services separately.
        """
        thread_id = str(msg.get("thread_id") or "").strip()
        if not thread_id:
            thread_id = str(msg.get("session_id") or "").strip()
        if not thread_id and self._registry:
            meta = await self._registry.get_metadata(conn_id)
            thread_id = str((meta or {}).get("thread_id") or "").strip()

        if not thread_id:
            await self._send(conn_id, {"type": "error", "error": "缺少 thread_id"})
            return {"ok": False, "error": "missing thread_id"}

        raw_agent = msg.get("agent_id")
        agent_kw: str | None = None
        if raw_agent is not None:
            agent_kw = str(raw_agent).strip() or None

        tid_norm = normalize_client_thread_id(thread_id)
        if tid_norm != thread_id:
            logger.info(
                "[ws] delete_session: normalized thread_id {} -> {}",
                thread_id,
                tid_norm,
            )

        sk = websocket_session_key(agent_kw, tid_norm)
        keys_to_delete = websocket_session_keys_to_delete(agent_kw, thread_id)

        reg = getattr(self.runtime, "agent_registry", None)
        if reg:
            agent_loop, _w, canonical = await reg.materialize(agent_kw)
        else:
            agent_loop = self.runtime.agent
            canonical = DEFAULT_AGENT_ID

        sub_cancelled = 0
        try:
            sub_cancelled = await agent_loop.subagents.cancel_by_session(sk)
        except Exception:
            logger.exception("[ws] delete_session: cancel_by_session failed key={}", sk)

        delete_info: dict[str, Any]
        try:
            delete_info = await agent_loop.sessions.delete_sessions_for_keys(keys_to_delete)
        except Exception as exc:
            logger.exception("[ws] delete_session: delete failed keys={}", keys_to_delete)
            await self._send(conn_id, {"type": "error", "error": str(exc)})
            return {"ok": False, "error": str(exc), "session_key": sk}

        return {
            "ok": True,
            "session_key": sk,
            "thread_id": thread_id,
            "thread_id_resolved": tid_norm,
            "session_keys_tried": keys_to_delete,
            "agent_id": canonical,
            "subtasks_cancelled": sub_cancelled,
            **{k: v for k, v in delete_info.items() if k != "per_key"},
            "delete_per_key": delete_info.get("per_key", []),
        }

    # ── core agent processing (used by both HTTP and WS) ──────

    async def run_chat_turn(
        self,
        req: ChatRequest,
        inbound_channel: str = "http",
        progress_cb: Any | None = None,
        include_skill: bool = False,
        *,
        session_key: str | None = None,
        metadata: dict[str, Any] | None = None,
        agent_id: str | None = None,
        tool_message_sink: Any | None = None,
    ) -> tuple[Any, ...]:
        eff_agent = agent_id
        if eff_agent is None or (isinstance(eff_agent, str) and not str(eff_agent).strip()):
            eff_agent = getattr(req, "agent_id", None)
        if isinstance(eff_agent, str):
            eff_agent = eff_agent.strip() or None

        reg = getattr(self.runtime, "agent_registry", None)
        if reg:
            agent_loop, agent_workspace, resolved_agent_id = await reg.materialize(eff_agent)
        else:
            agent_workspace = self.workspace
            agent_loop = self.runtime.agent
            resolved_agent_id = DEFAULT_AGENT_ID

        meta = dict(metadata or {})
        meta["agent_id"] = resolved_agent_id

        sk_override = session_key
        if sk_override is None and inbound_channel == "websocket":
            sk_override = websocket_session_key(eff_agent, req.thread_id)

        image_paths = _save_base64_images(agent_workspace, req.images)
        message_tool = agent_loop.tools.get("message")
        restore_send_callback = None
        if (
            inbound_channel == "websocket"
            and tool_message_sink is not None
            and isinstance(message_tool, MessageTool)
        ):
            restore_send_callback = message_tool._send_callback
            message_tool.set_send_callback(tool_message_sink)
        try:
            inbound = self.message_adapter.build_inbound(
                req,
                inbound_channel=inbound_channel,
                metadata=meta,
                session_key_override=sk_override,
            )
            inbound.media = [str(p) for p in image_paths]
            outbound = await agent_loop._process_message(  # noqa: SLF001
                inbound,
                session_key=inbound.session_key,
                on_progress=progress_cb,
            )
        finally:
            if isinstance(message_tool, MessageTool) and restore_send_callback is not None:
                message_tool.set_send_callback(restore_send_callback)
            _cleanup(image_paths)

        result = self.message_adapter.extract_outbound_content(outbound)
        response, need_execution, reason, chat_summary, skill_generated = _extract_fields(result)
        if include_skill:
            return response, need_execution, reason, chat_summary, skill_generated
        return response, need_execution, reason, chat_summary

    # ── internal helpers ──────────────────────────────────────

    async def _send(self, conn_id: str, payload: dict[str, Any]) -> bool:
        if not self._registry:
            return False
        return await self._registry.send(conn_id, payload)
