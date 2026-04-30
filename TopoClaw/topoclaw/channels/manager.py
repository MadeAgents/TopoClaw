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

"""Channel manager for coordinating chat channels."""

from __future__ import annotations

import asyncio
from typing import Any

from loguru import logger

from topoclaw.bus.events import OutboundMessage
from topoclaw.bus.queue import MessageBus
from topoclaw.channels.base import BaseChannel
from topoclaw.config.schema import Config


class ChannelManager:
    """
    Manages chat channels and coordinates message routing.

    Responsibilities:
    - Initialize enabled channels (Telegram, WhatsApp, etc.)
    - Start/stop channels
    - Route outbound messages
    """

    def __init__(self, config: Config, bus: MessageBus, websocket_manager=None, http_response_manager=None, connection_app_service=None):
        self.config = config
        self.bus = bus
        self.channels: dict[str, BaseChannel] = {}
        self._dispatch_task: asyncio.Task | None = None
        self.websocket_manager = websocket_manager
        self.http_response_manager = http_response_manager
        self.connection_app_service = connection_app_service

        self._init_channels()

    def register_topomobile_channel(
        self,
        *,
        runtime: Any,
        skills_service: Any,
        topoclaw_config: Config,
    ) -> None:
        """Register TopoMobile relay channel (needs ServiceRuntime + SkillsService; call after API app is built)."""
        if not self.config.channels.topomobile.enabled:
            return
        if "topomobile" in self.channels:
            return
        from topoclaw.channels.topomobile import TopoMobileChannel

        ch = TopoMobileChannel(
            self.config.channels.topomobile,
            self.bus,
            runtime=runtime,
            topoclaw_config=topoclaw_config,
            skills_service=skills_service,
        )
        self.channels["topomobile"] = ch
        if getattr(ch.config, "allow_from", None) == []:
            raise SystemExit(
                'Error: "topomobile" has empty allowFrom (denies all). '
                'Set ["*"] to allow everyone, or add specific IMEI/user IDs.'
            )

    def _init_channels(self) -> None:
        """Initialize channels based on config."""

        # Telegram channel
        if self.config.channels.telegram.enabled:
            try:
                from topoclaw.channels.telegram import TelegramChannel
                self.channels["telegram"] = TelegramChannel(
                    self.config.channels.telegram,
                    self.bus,
                    groq_api_key=self.config.providers.groq.api_key,
                )
                logger.info("Telegram channel enabled")
            except ImportError as e:
                logger.warning("Telegram channel not available: {}", e)

        # WhatsApp channel
        if self.config.channels.whatsapp.enabled:
            try:
                from topoclaw.channels.whatsapp import WhatsAppChannel
                self.channels["whatsapp"] = WhatsAppChannel(
                    self.config.channels.whatsapp, self.bus
                )
                logger.info("WhatsApp channel enabled")
            except ImportError as e:
                logger.warning("WhatsApp channel not available: {}", e)

        # Discord channel
        if self.config.channels.discord.enabled:
            try:
                from topoclaw.channels.discord import DiscordChannel
                self.channels["discord"] = DiscordChannel(
                    self.config.channels.discord, self.bus
                )
                logger.info("Discord channel enabled")
            except ImportError as e:
                logger.warning("Discord channel not available: {}", e)

        # Feishu channel
        if self.config.channels.feishu.enabled:
            try:
                from topoclaw.channels.feishu import FeishuChannel
                self.channels["feishu"] = FeishuChannel(
                    self.config.channels.feishu, self.bus,
                    groq_api_key=self.config.providers.groq.api_key,
                )
                logger.info("Feishu channel enabled")
            except ImportError as e:
                logger.warning("Feishu channel not available: {}", e)

        # Mochat channel
        if self.config.channels.mochat.enabled:
            try:
                from topoclaw.channels.mochat import MochatChannel

                self.channels["mochat"] = MochatChannel(
                    self.config.channels.mochat, self.bus
                )
                logger.info("Mochat channel enabled")
            except ImportError as e:
                logger.warning("Mochat channel not available: {}", e)

        # DingTalk channel
        if self.config.channels.dingtalk.enabled:
            try:
                from topoclaw.channels.dingtalk import DingTalkChannel
                self.channels["dingtalk"] = DingTalkChannel(
                    self.config.channels.dingtalk, self.bus
                )
                logger.info("DingTalk channel enabled")
            except ImportError as e:
                logger.warning("DingTalk channel not available: {}", e)

        # Email channel
        if self.config.channels.email.enabled:
            try:
                from topoclaw.channels.email import EmailChannel
                self.channels["email"] = EmailChannel(
                    self.config.channels.email, self.bus
                )
                logger.info("Email channel enabled")
            except ImportError as e:
                logger.warning("Email channel not available: {}", e)

        # Slack channel
        if self.config.channels.slack.enabled:
            try:
                from topoclaw.channels.slack import SlackChannel
                self.channels["slack"] = SlackChannel(
                    self.config.channels.slack, self.bus
                )
                logger.info("Slack channel enabled")
            except ImportError as e:
                logger.warning("Slack channel not available: {}", e)

        # QQ channel
        if self.config.channels.qq.enabled:
            try:
                from topoclaw.channels.qq import QQChannel
                self.channels["qq"] = QQChannel(
                    self.config.channels.qq,
                    self.bus,
                )
                logger.info("QQ channel enabled")
            except ImportError as e:
                logger.warning("QQ channel not available: {}", e)

        # Matrix channel
        if self.config.channels.matrix.enabled:
            try:
                from topoclaw.channels.matrix import MatrixChannel
                self.channels["matrix"] = MatrixChannel(
                    self.config.channels.matrix,
                    self.bus,
                )
                logger.info("Matrix channel enabled")
            except ImportError as e:
                logger.warning("Matrix channel not available: {}", e)

        # Weixin iLink (ClawBot) channel
        if self.config.channels.weixin.enabled:
            try:
                from topoclaw.channels.weixin import WeixinChannel

                self.channels["weixin"] = WeixinChannel(self.config.channels.weixin, self.bus)
                logger.info("Weixin channel enabled")
            except ImportError as e:
                logger.warning("Weixin channel not available: {}", e)

        self._validate_allow_from()

    def _validate_allow_from(self) -> None:
        for name, ch in self.channels.items():
            if getattr(ch.config, "allow_from", None) == []:
                raise SystemExit(
                    f'Error: "{name}" has empty allowFrom (denies all). '
                    f'Set ["*"] to allow everyone, or add specific user IDs.'
                )

    async def _start_channel(self, name: str, channel: BaseChannel) -> None:
        """Start a channel and log any exceptions."""
        try:
            await channel.start()
        except Exception as e:
            logger.error("Failed to start channel {}: {}", name, e)

    async def start_all(self) -> None:
        """Start all channels and the outbound dispatcher."""
        if not self.channels:
            logger.warning("No channels enabled")
            return

        # Start outbound dispatcher
        self._dispatch_task = asyncio.create_task(self._dispatch_outbound())

        # Start channels
        tasks = []
        for name, channel in self.channels.items():
            logger.info("Starting {} channel...", name)
            tasks.append(asyncio.create_task(self._start_channel(name, channel)))

        # Wait for all to complete (they should run forever)
        await asyncio.gather(*tasks, return_exceptions=True)

    async def stop_all(self) -> None:
        """Stop all channels and the dispatcher."""
        logger.info("Stopping all channels...")

        # Stop dispatcher
        if self._dispatch_task:
            self._dispatch_task.cancel()
            try:
                await self._dispatch_task
            except asyncio.CancelledError:
                pass

        # Stop all channels
        for name, channel in self.channels.items():
            try:
                await channel.stop()
                logger.info("Stopped {} channel", name)
            except Exception as e:
                logger.error("Error stopping {}: {}", name, e)

    async def _dispatch_outbound(self) -> None:
        """Dispatch outbound messages to the appropriate channel."""
        logger.info("Outbound dispatcher started")

        while True:
            try:
                msg = await asyncio.wait_for(
                    self.bus.consume_outbound(),
                    timeout=1.0
                )

                # 处理 WebSocket 消息
                if msg.channel == "websocket":
                    await self._handle_websocket_message(msg)
                    continue

                # 处理 HTTP 消息
                if msg.channel == "http":
                    await self._handle_http_message(msg)
                    continue

                # 处理进度消息（传统渠道）
                if msg.metadata.get("_progress"):
                    if msg.metadata.get("_tool_hint") and not self.config.channels.send_tool_hints:
                        continue
                    if not msg.metadata.get("_tool_hint") and not self.config.channels.send_progress:
                        continue

                # 处理传统渠道消息
                channel = self.channels.get(msg.channel)
                if channel:
                    try:
                        await channel.send(msg)
                    except Exception as e:
                        logger.error("Error sending to {}: {}", msg.channel, e)
                else:
                    logger.warning("Unknown channel: {}", msg.channel)

            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break

    async def _handle_websocket_message(self, msg: OutboundMessage) -> None:
        """通过 WebSocket 发送消息"""
        if self.connection_app_service:
            thread_id = str(msg.metadata.get("_thread_id") or msg.chat_id or "").strip()
            def _infer_imei_from_thread_id(raw_thread_id: str) -> str:
                tid = (raw_thread_id or "").strip()
                if not tid:
                    return ""
                prefix = tid.split("_", 1)[0].strip()
                return prefix if len(prefix) >= 8 else ""

            async def _mirror_to_topomobile(*, progress: bool, tool_hint: bool = False) -> None:
                imei = str(msg.metadata.get("imei") or "").strip() or _infer_imei_from_thread_id(thread_id)
                fallback_meta: dict[str, Any] = {
                    "request_id": str(msg.metadata.get("request_id") or f"cron-mirror-{thread_id}" ),
                    "source": str(msg.metadata.get("source") or "cron"),
                }
                if imei:
                    fallback_meta["imei"] = imei
                if progress:
                    fallback_meta["_progress"] = True
                    fallback_meta["_tool_hint"] = bool(tool_hint)
                await self.bus.publish_outbound(
                    OutboundMessage(
                        channel="topomobile",
                        chat_id=thread_id,
                        content=msg.content,
                        metadata=fallback_meta,
                    )
                )

            if msg.metadata.get("_interactive"):
                payload = {
                    "type": "assistant_push",
                    "thread_id": thread_id,
                    "agent_id": str(msg.metadata.get("_agent_id") or "default"),
                    "content": msg.content,
                }
                logger.info(
                    "[ws] interactive outbound received thread_id={} agent_id={} content_len={}",
                    thread_id or "(missing)",
                    str(msg.metadata.get("_agent_id") or "default"),
                    len(str(msg.content or "")),
                )
                sent = await self.connection_app_service.push_event(thread_id, payload)
                logger.info("[ws] interactive push by thread thread_id={} sent={}", thread_id, sent)
                return
            
            if msg.metadata.get("_progress"):
                sent = await self.connection_app_service.push_cron_progress(
                    thread_id, msg.content, tool_hint=msg.metadata.get("_tool_hint", False)
                )
                if sent == 0:
                    await _mirror_to_topomobile(progress=True, tool_hint=msg.metadata.get("_tool_hint", False))
            else:
                sent = await self.connection_app_service.push_cron_done(thread_id, msg.content)
                if sent == 0:
                    await _mirror_to_topomobile(progress=False)
            return

        if not self.websocket_manager:
            logger.warning("WebSocketManager not available, cannot send WebSocket message")
            return

        connection_id = msg.chat_id  # chat_id 就是 connection_id
        
        # 构建消息
        if msg.metadata.get("_progress"):
            message = {
                "type": "progress",
                "content": msg.content,
                "tool_hint": msg.metadata.get("_tool_hint", False),
                "session_id": msg.metadata.get("session_id"),
            }
        else:
            message = {
                "type": "response",
                "content": msg.content,
                "session_id": msg.metadata.get("session_id"),
                "metadata": msg.metadata or {},
            }

        await self.websocket_manager.send_message(connection_id, message)

    async def _handle_http_message(self, msg: OutboundMessage) -> None:
        """处理 HTTP 消息（通过 Future 机制）"""
        if not self.http_response_manager:
            logger.warning("HTTPResponseManager not available, cannot send HTTP response")
            return

        request_id = msg.chat_id  # chat_id 就是 message_id/request_id
        self.http_response_manager.set_response(request_id, msg)

    def get_channel(self, name: str) -> BaseChannel | None:
        """Get a channel by name."""
        return self.channels.get(name)

    def get_status(self) -> dict[str, Any]:
        """Get status of all channels."""
        return {
            name: {
                "enabled": True,
                "running": channel.is_running
            }
            for name, channel in self.channels.items()
        }

    @property
    def enabled_channels(self) -> list[str]:
        """Get list of enabled channel names."""
        return list(self.channels.keys())
