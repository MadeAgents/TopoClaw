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

"""Weixin (iLink ClawBot) channel — HTTP long-poll getUpdates + sendmessage.

Protocol reference: ``docs/weixin-api.md``. Behaviour aligned with
``@tencent-weixin/openclaw-weixin`` (api.ts, monitor.ts, messaging/inbound.ts, send.ts).

Obtain ``bot_token`` and per-bot ``base_url`` via QR login
(``GET ilink/bot/get_bot_qrcode``, ``GET ilink/bot/get_qrcode_status``), then set
``channels.weixin.botToken`` and ``channels.weixin.baseUrl`` in config.
"""

from __future__ import annotations

import asyncio
import base64
import io
import json
import re
import secrets
import sys
import time
import uuid
from contextlib import redirect_stdout
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
from loguru import logger

from topoclaw.bus.events import OutboundMessage
from topoclaw.bus.queue import MessageBus
from topoclaw.channels.base import BaseChannel
from topoclaw.config.schema import Config, WeixinConfig

# --- Protocol constants (openclaw-weixin api/types.ts) ---
MESSAGE_TYPE_USER = 1
MESSAGE_TYPE_BOT = 2
MESSAGE_ITEM_TEXT = 1
MESSAGE_ITEM_IMAGE = 2
MESSAGE_ITEM_VOICE = 3
MESSAGE_ITEM_FILE = 4
MESSAGE_ITEM_VIDEO = 5
MESSAGE_STATE_FINISH = 2

SESSION_EXPIRED_ERRCODE = -14
DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000
DEFAULT_API_TIMEOUT_MS = 15_000
MAX_CONSECUTIVE_FAILURES = 3
BACKOFF_DELAY_MS = 30_000
RETRY_DELAY_MS = 2_000
SESSION_PAUSE_MS = 60 * 60 * 1000
TEXT_CHUNK_LIMIT = 4000

# QR interactive login (auth/login-qr.ts)
MAX_QR_REFRESH_COUNT = 3
QR_STATUS_LONG_POLL_MS = 35_000
DEFAULT_QR_LOGIN_TIMEOUT_S = 480.0


def _random_wechat_uin() -> str:
    """X-WECHAT-UIN: random uint32 as decimal string, base64-encoded (see weixin-api.md)."""
    u32 = int.from_bytes(secrets.token_bytes(4), "big") & 0xFFFFFFFF
    return base64.b64encode(str(u32).encode()).decode("ascii")


def _ensure_trailing_slash(url: str) -> str:
    return url if url.endswith("/") else f"{url}/"


def markdown_to_plain_text(text: str) -> str:
    """Strip markdown for Weixin plain-text delivery (messaging/send.ts)."""
    if not text:
        return ""
    result = text
    result = re.sub(r"```[^\n]*\n?([\s\S]*?)```", lambda m: m.group(1).strip(), result)
    result = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", result)
    result = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", result)
    result = re.sub(r"^\|[\s:|-]+\|$", "", result, flags=re.MULTILINE)
    result = re.sub(
        r"^\|(.+)\|$",
        lambda m: "  ".join(c.strip() for c in m.group(1).split("|")),
        result,
        flags=re.MULTILINE,
    )
    result = re.sub(r"\*\*(.+?)\*\*", r"\1", result)
    result = re.sub(r"__(.+?)__", r"\1", result)
    result = re.sub(r"`([^`]+)`", r"\1", result)
    result = re.sub(r"^#{1,6}\s+", "", result, flags=re.MULTILINE)
    return result.strip()


def _is_media_item(item: dict[str, Any]) -> bool:
    t = item.get("type")
    return t in (
        MESSAGE_ITEM_IMAGE,
        MESSAGE_ITEM_VIDEO,
        MESSAGE_ITEM_FILE,
        MESSAGE_ITEM_VOICE,
    )


def body_from_item_list(item_list: list[dict[str, Any]] | None) -> str:
    """Text body from item_list (messaging/inbound.ts bodyFromItemList)."""
    if not item_list:
        return ""
    for item in item_list:
        if item.get("type") == MESSAGE_ITEM_TEXT:
            ti = item.get("text_item") or {}
            tx = ti.get("text")
            if tx is None:
                continue
            text = str(tx)
            ref = item.get("ref_msg")
            if not ref:
                return text
            rmi = (ref.get("message_item") or {}) if isinstance(ref, dict) else {}
            if rmi and _is_media_item(rmi):
                return text
            parts: list[str] = []
            if isinstance(ref, dict) and ref.get("title"):
                parts.append(str(ref["title"]))
            if rmi:
                rb = body_from_item_list([rmi])
                if rb:
                    parts.append(rb)
            if not parts:
                return text
            return f'[引用: {" | ".join(parts)}]\n{text}'
        if item.get("type") == MESSAGE_ITEM_VOICE:
            vi = item.get("voice_item") or {}
            if vi.get("text"):
                return str(vi["text"])
    return ""


def default_get_updates_buf_path(account_id: str) -> Path:
    return Path.home() / ".topoclaw" / "weixin" / f"{account_id}_get_updates.buf"


async def fetch_bot_qrcode(
    api_base_url: str,
    bot_type: str = "3",
    *,
    sk_route_tag: str | None = None,
) -> dict[str, Any]:
    """GET ilink/bot/get_bot_qrcode — returns JSON with qrcode, qrcode_img_content."""
    base = _ensure_trailing_slash(api_base_url)
    url = f"{base}ilink/bot/get_bot_qrcode?bot_type={quote(bot_type, safe='')}"
    headers: dict[str, str] = {}
    if sk_route_tag:
        headers["SKRouteTag"] = sk_route_tag
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.get(url, headers=headers)
        r.raise_for_status()
        return r.json()


async def poll_qrcode_status(
    api_base_url: str,
    qrcode: str,
    *,
    sk_route_tag: str | None = None,
    long_poll_timeout_ms: int = 35_000,
) -> dict[str, Any]:
    """GET ilink/bot/get_qrcode_status — long-poll; may return status wait/scaned/confirmed/expired."""
    base = _ensure_trailing_slash(api_base_url)
    url = f"{base}ilink/bot/get_qrcode_status?qrcode={quote(qrcode, safe='')}"
    headers = {"iLink-App-ClientVersion": "1"}
    if sk_route_tag:
        headers["SKRouteTag"] = sk_route_tag
    async with httpx.AsyncClient(timeout=long_poll_timeout_ms / 1000.0 + 5.0) as client:
        try:
            r = await client.get(url, headers=headers, timeout=long_poll_timeout_ms / 1000.0)
            r.raise_for_status()
            return r.json()
        except httpx.TimeoutException:
            return {"status": "wait"}


def _qr_payload_for_terminal(qr_response: dict[str, Any]) -> str:
    """String to embed in a scannable terminal QR (openclaw passes qrcode_img_content to qrcode-terminal)."""
    img = qr_response.get("qrcode_img_content")
    if isinstance(img, str) and img.strip():
        return img.strip()
    raw = qr_response.get("qrcode")
    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    return ""


def print_weixin_qr_terminal(payload: str, *, console: Any = None) -> None:
    """Render a QR code as ASCII to the console (requires ``qrcode`` package)."""
    try:
        import qrcode  # type: ignore[import-untyped]
        from qrcode.constants import ERROR_CORRECT_M
    except ImportError:
        msg = "[Install qrcode: uv sync]"
        if console:
            console.print(f"[yellow]{msg}[/yellow]\n{payload}")
        else:
            print(msg, payload, sep="\n", file=sys.stderr)
        return

    qr = qrcode.QRCode(version=None, error_correction=ERROR_CORRECT_M, box_size=1, border=1)
    qr.add_data(payload)
    qr.make(fit=True)
    buf = io.StringIO()
    with redirect_stdout(buf):
        qr.print_ascii(invert=True)
    text = buf.getvalue()
    if console:
        console.print(text)
    else:
        print(text)


async def run_weixin_login_interactive(
    config: Config,
    *,
    login_timeout_s: float = DEFAULT_QR_LOGIN_TIMEOUT_S,
    console: Any | None = None,
) -> None:
    """
    Block until the user completes Weixin QR login, then set ``channels.weixin.bot_token``
    and optionally ``base_url`` / ``account_id`` on *config* (caller should ``save_config``).

    Mirrors ``openclaw-weixin`` ``waitForWeixinLogin`` / ``startWeixinLoginWithQr``.
    """
    from rich.console import Console

    con = console or Console()
    wx = config.channels.weixin
    api_base = (wx.base_url or "https://ilinkai.weixin.qq.com").strip().rstrip("/")
    bot_type = (wx.bot_type or "3").strip()
    sk = (wx.sk_route_tag or "").strip() or None

    con.print("[bold cyan]Weixin (iLink)[/bold cyan]: 正在获取登录二维码…")
    qr_data = await fetch_bot_qrcode(api_base, bot_type, sk_route_tag=sk)
    qrcode_ticket = str(qr_data.get("qrcode") or "").strip()
    display_payload = _qr_payload_for_terminal(qr_data)
    if not qrcode_ticket:
        raise RuntimeError("get_bot_qrcode: 响应缺少 qrcode")
    if not display_payload:
        display_payload = qrcode_ticket

    con.print("\n[bold]请使用微信扫描下方二维码完成 ClawBot 绑定：[/bold]\n")
    print_weixin_qr_terminal(display_payload, console=con)
    if display_payload.startswith("http"):
        con.print(f"\n[dim]若二维码显示异常，可在浏览器打开:[/dim]\n{display_payload}\n")

    deadline = time.time() + max(login_timeout_s, 1.0)
    scanned_printed = False
    qr_refresh_count = 1

    while time.time() < deadline:
        try:
            status = await poll_qrcode_status(
                api_base,
                qrcode_ticket,
                sk_route_tag=sk,
                long_poll_timeout_ms=QR_STATUS_LONG_POLL_MS,
            )
            st = str(status.get("status") or "")

            if st == "confirmed":
                token = status.get("bot_token")
                if not token or not str(token).strip():
                    raise RuntimeError("登录已确认但服务器未返回 bot_token")
                bot_id = status.get("ilink_bot_id")
                if not bot_id:
                    logger.warning("Weixin login: ilink_bot_id missing in response")
                baseurl = str(status.get("baseurl") or "").strip().rstrip("/")
                wx.bot_token = str(token).strip()
                if baseurl:
                    wx.base_url = baseurl
                if bot_id:
                    wx.account_id = str(bot_id).strip()
                uid = status.get("ilink_user_id")
                if uid:
                    con.print(
                        f"\n[green]登录成功。[/green] "
                        f"[dim]可将此 ID 写入 channels.weixin.allowFrom:[/dim] [bold]{uid}[/bold]\n"
                    )
                else:
                    con.print("\n[green]登录成功，bot_token 已写入内存中的配置。[/green]\n")
                return

            if st == "expired":
                qr_refresh_count += 1
                if qr_refresh_count > MAX_QR_REFRESH_COUNT:
                    raise RuntimeError("二维码多次过期，请重新运行 service 再试")
                con.print(
                    f"\n[yellow]二维码已过期，正在刷新 ({qr_refresh_count}/{MAX_QR_REFRESH_COUNT})…[/yellow]\n"
                )
                qr_data = await fetch_bot_qrcode(api_base, bot_type, sk_route_tag=sk)
                qrcode_ticket = str(qr_data.get("qrcode") or "").strip()
                display_payload = _qr_payload_for_terminal(qr_data) or qrcode_ticket
                print_weixin_qr_terminal(display_payload, console=con)
                scanned_printed = False

            elif st == "scaned" and not scanned_printed:
                con.print("\n[yellow]已扫码，请在微信上确认登录…[/yellow]\n")
                scanned_printed = True

        except httpx.HTTPStatusError as e:
            raise RuntimeError(f"微信登录 HTTP 错误: {e.response.status_code}") from e

        await asyncio.sleep(1.0)

    raise TimeoutError("微信登录超时，请重试")


class WeixinChannel(BaseChannel):
    """Long-poll Weixin iLink bot channel."""

    name = "weixin"

    def __init__(self, config: WeixinConfig, bus: MessageBus):
        super().__init__(config, bus)
        self.config: WeixinConfig = config
        self._client: httpx.AsyncClient | None = None
        self._poll_task: asyncio.Task | None = None
        self._stop_evt = asyncio.Event()
        # chat_id (DM user or group) -> latest context_token from inbound
        self._context_tokens: dict[str, str] = {}
        self._pause_until_mono: float | None = None
        self._consecutive_failures = 0

    def _sync_buf_file(self) -> Path:
        raw = (self.config.get_updates_buf_path or "").strip()
        if raw:
            return Path(raw).expanduser()
        p = default_get_updates_buf_path(self.config.account_id)
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    def _load_get_updates_buf(self) -> str:
        path = self._sync_buf_file()
        try:
            if path.exists():
                return path.read_text(encoding="utf-8")
        except OSError as e:
            logger.warning("Weixin: could not read sync buf {}: {}", path, e)
        return ""

    def _save_get_updates_buf(self, buf: str) -> None:
        if not buf:
            return
        path = self._sync_buf_file()
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(buf, encoding="utf-8")
        except OSError as e:
            logger.warning("Weixin: could not write sync buf {}: {}", path, e)

    def _base_info(self) -> dict[str, Any]:
        return {"channel_version": self.config.channel_version or "1.0.2"}

    def _build_headers(self, body: bytes, *, with_token: bool) -> dict[str, str]:
        headers: dict[str, str] = {
            "Content-Type": "application/json",
            "AuthorizationType": "ilink_bot_token",
            "Content-Length": str(len(body)),
            "X-WECHAT-UIN": _random_wechat_uin(),
        }
        tok = (self.config.bot_token or "").strip()
        if with_token and tok:
            headers["Authorization"] = f"Bearer {tok}"
        tag = (self.config.sk_route_tag or "").strip()
        if tag:
            headers["SKRouteTag"] = tag
        return headers

    def _session_paused(self) -> bool:
        if self._pause_until_mono is None:
            return False
        if time.monotonic() >= self._pause_until_mono:
            self._pause_until_mono = None
            return False
        return True

    def _pause_session(self) -> None:
        self._pause_until_mono = time.monotonic() + SESSION_PAUSE_MS / 1000.0
        logger.error(
            "Weixin: session expired (errcode {}), pausing API calls for {} min",
            SESSION_EXPIRED_ERRCODE,
            SESSION_PAUSE_MS // 60_000,
        )

    async def _post_ilink(self, endpoint: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        if self._client is None:
            raise RuntimeError("Weixin HTTP client not started")
        if self._session_paused():
            raise RuntimeError("Weixin session paused after expiry; wait or re-login")
        base = _ensure_trailing_slash(self.config.base_url.strip())
        url = f"{base}{endpoint.lstrip('/')}"
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = self._build_headers(body, with_token=True)
        r = await self._client.post(url, content=body, headers=headers, timeout=timeout)
        text = r.text
        if not r.is_success:
            logger.error("Weixin POST {} -> {} {}", endpoint, r.status_code, text[:500])
            r.raise_for_status()
        try:
            return json.loads(text) if text else {}
        except json.JSONDecodeError:
            logger.error("Weixin POST {}: invalid JSON {}", endpoint, text[:300])
            return {}

    async def get_updates_once(self, get_updates_buf: str, timeout_ms: int) -> dict[str, Any]:
        """POST ilink/bot/getupdates. On client timeout returns empty msgs (monitor pattern)."""
        if self._client is None:
            return {"ret": 0, "msgs": [], "get_updates_buf": get_updates_buf}
        base = _ensure_trailing_slash(self.config.base_url.strip())
        url = f"{base}ilink/bot/getupdates"
        payload = {"get_updates_buf": get_updates_buf or "", "base_info": self._base_info()}
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = self._build_headers(body, with_token=True)
        timeout_sec = max(timeout_ms / 1000.0, 1.0) + 5.0
        try:
            r = await self._client.post(url, content=body, headers=headers, timeout=timeout_sec)
            text = r.text
            if not r.is_success:
                logger.error("Weixin getUpdates HTTP {} {}", r.status_code, text[:500])
                r.raise_for_status()
            return json.loads(text) if text else {}
        except httpx.TimeoutException:
            logger.debug("Weixin getUpdates client timeout after {}ms", timeout_ms)
            return {"ret": 0, "msgs": [], "get_updates_buf": get_updates_buf}
        except Exception as e:
            logger.error("Weixin getUpdates error: {}", e)
            raise

    async def send_text_message(
        self,
        to_user_id: str,
        text: str,
        context_token: str,
    ) -> None:
        """POST ilink/bot/sendmessage — one text item, BOT + FINISH."""
        if not context_token:
            raise ValueError("context_token is required for Weixin sendmessage")
        plain = markdown_to_plain_text(text)
        if not plain.strip():
            return
        chunks: list[str] = []
        if len(plain) <= TEXT_CHUNK_LIMIT:
            chunks = [plain]
        else:
            for i in range(0, len(plain), TEXT_CHUNK_LIMIT):
                chunks.append(plain[i : i + TEXT_CHUNK_LIMIT])
        for part in chunks:
            client_id = f"topoclaw-weixin-{uuid.uuid4().hex[:16]}"
            item_list: list[dict[str, Any]] = []
            if part:
                item_list.append({"type": MESSAGE_ITEM_TEXT, "text_item": {"text": part}})
            msg_body: dict[str, Any] = {
                "msg": {
                    "from_user_id": "",
                    "to_user_id": to_user_id,
                    "client_id": client_id,
                    "message_type": MESSAGE_TYPE_BOT,
                    "message_state": MESSAGE_STATE_FINISH,
                    "context_token": context_token,
                }
            }
            if item_list:
                msg_body["msg"]["item_list"] = item_list
            payload = {**msg_body, "base_info": self._base_info()}
            await self._post_ilink(
                "ilink/bot/sendmessage",
                payload,
                timeout=DEFAULT_API_TIMEOUT_MS / 1000.0,
            )

    def _resolve_chat_ids(self, msg: dict[str, Any]) -> tuple[str, str]:
        """(chat_id for session/routing, sender_id). Prefer group_id for group threads."""
        from_uid = str(msg.get("from_user_id") or "")
        gid = msg.get("group_id")
        if gid is not None and str(gid).strip():
            return str(gid).strip(), from_uid
        return from_uid, from_uid

    def _inbound_content_and_media_note(self, msg: dict[str, Any]) -> tuple[str, list[str]]:
        """Extract text; media-only inbound yields a short notice (CDN decrypt not implemented)."""
        items = msg.get("item_list") or []
        if not isinstance(items, list):
            items = []
        body = body_from_item_list(items)
        media: list[str] = []
        for it in items:
            if not isinstance(it, dict):
                continue
            t = it.get("type")
            if t == MESSAGE_ITEM_IMAGE:
                media.append("[weixin:image received — CDN decrypt not enabled in topoclaw]")
            elif t == MESSAGE_ITEM_VOICE and not body:
                media.append("[weixin:voice without ASR text]")
            elif t in (MESSAGE_ITEM_FILE, MESSAGE_ITEM_VIDEO):
                media.append("[weixin:file/video — download not enabled in topoclaw]")
        return body, media

    async def _handle_inbound_raw(self, raw: dict[str, Any]) -> None:
        if raw.get("message_type") != MESSAGE_TYPE_USER:
            return
        chat_id, sender_id = self._resolve_chat_ids(raw)
        ctx = raw.get("context_token")
        if ctx:
            self._context_tokens[chat_id] = str(ctx)
        content, media_notes = self._inbound_content_and_media_note(raw)
        extra = "\n".join(media_notes) if media_notes else ""
        if extra:
            content = f"{content}\n{extra}".strip() if content else extra
        if not content:
            logger.debug("Weixin: skip empty inbound from {}", sender_id)
            return
        metadata = {
            "context_token": self._context_tokens.get(chat_id, ""),
            "weixin_from_user_id": sender_id,
        }
        if raw.get("group_id"):
            metadata["weixin_group_id"] = raw.get("group_id")
        await self._handle_message(
            sender_id=sender_id,
            chat_id=chat_id,
            content=content,
            media=[],
            metadata=metadata,
            session_key=f"weixin:{chat_id}",
        )

    async def _poll_loop(self) -> None:
        get_buf = self._load_get_updates_buf()
        next_timeout = self.config.long_poll_timeout_ms or DEFAULT_LONG_POLL_TIMEOUT_MS
        logger.info(
            "Weixin monitor started base_url={} buf_len={}",
            self.config.base_url,
            len(get_buf),
        )
        while not self._stop_evt.is_set():
            if self._session_paused():
                await asyncio.sleep(1.0)
                continue
            try:
                resp = await self.get_updates_once(get_buf, next_timeout)
                if resp.get("longpolling_timeout_ms"):
                    v = int(resp["longpolling_timeout_ms"])
                    if v > 0:
                        next_timeout = v
                ret = resp.get("ret", 0)
                errcode = resp.get("errcode", 0)
                is_err = (ret not in (0, None)) or (errcode not in (0, None))
                if is_err:
                    if errcode == SESSION_EXPIRED_ERRCODE or ret == SESSION_EXPIRED_ERRCODE:
                        self._pause_session()
                        self._consecutive_failures = 0
                        continue
                    self._consecutive_failures += 1
                    logger.error(
                        "Weixin getUpdates err ret={} errcode={} errmsg={}",
                        ret,
                        errcode,
                        resp.get("errmsg"),
                    )
                    if self._consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                        self._consecutive_failures = 0
                        await asyncio.sleep(BACKOFF_DELAY_MS / 1000.0)
                    else:
                        await asyncio.sleep(RETRY_DELAY_MS / 1000.0)
                    continue
                self._consecutive_failures = 0
                new_buf = resp.get("get_updates_buf")
                if new_buf:
                    self._save_get_updates_buf(str(new_buf))
                    get_buf = str(new_buf)
                for m in resp.get("msgs") or []:
                    if isinstance(m, dict):
                        await self._handle_inbound_raw(m)
            except asyncio.CancelledError:
                break
            except Exception as e:
                if self._stop_evt.is_set():
                    break
                self._consecutive_failures += 1
                logger.exception("Weixin poll error: {}", e)
                if self._consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                    self._consecutive_failures = 0
                    await asyncio.sleep(BACKOFF_DELAY_MS / 1000.0)
                else:
                    await asyncio.sleep(RETRY_DELAY_MS / 1000.0)
        logger.info("Weixin monitor stopped")

    async def start(self) -> None:
        if not (self.config.bot_token or "").strip():
            logger.error("Weixin: bot_token missing — set channels.weixin.botToken after QR login")
            return
        if not (self.config.base_url or "").strip():
            logger.error("Weixin: base_url missing")
            return
        self._running = True
        self._stop_evt.clear()
        self._client = httpx.AsyncClient()
        self._poll_task = asyncio.create_task(self._poll_loop())

    async def stop(self) -> None:
        self._running = False
        self._stop_evt.set()
        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
            self._poll_task = None
        if self._client:
            await self._client.aclose()
            self._client = None
        logger.info("Weixin channel stopped")

    async def send(self, msg: OutboundMessage) -> None:
        if not self._client:
            logger.warning("Weixin send: client not running")
            return
        token = (msg.metadata or {}).get("context_token") or self._context_tokens.get(msg.chat_id)
        if not token:
            logger.error(
                "Weixin send: no context_token for chat_id={} — cannot attach reply to conversation",
                msg.chat_id,
            )
            return
        to_user = msg.reply_to or msg.chat_id
        try:
            await self.send_text_message(to_user, msg.content or "", token)
        except Exception as e:
            logger.error("Weixin send failed: {}", e)
