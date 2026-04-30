# -*- coding: utf-8 -*-
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

"""
WebSocket连接管理
管理所有活跃的WebSocket连接
"""
import logging
import time
from typing import Any, Dict, List, Optional
from fastapi import WebSocket

logger = logging.getLogger(__name__)

# 云端默认：用户 IMEI -> TopoMobile adapter node_id（与本地 topoclaw channels.topomobile.node_id 一致）。
# 未在 WS 上 bind 时，resolve_user_adapter_route 仍会回退到此表。
# 与 WS register/bind 或 mock_clients 约定一致；显式 bind 会覆盖此表。
_DEFAULT_ADAPTER_ROUTE_BY_IMEI: Dict[str, str] = {
    "9a8f314a3f0df184": "001",
    "user-a-imei": "000",
    "user-b-imei": "001",
    "user-c-imei": "000",
    "user-d-imei": "000",
}


class ConnectionManager:
    """WebSocket连接管理器"""

    MOBILE_PRESENCE_TTL_SECONDS = 90.0
    
    def __init__(self):
        # 活跃连接 {imei: websocket} - 手机端
        self.active_connections: Dict[str, WebSocket] = {}
        # 手机端最近活跃时间（心跳/任意消息）{imei: monotonic_seconds}
        self.mobile_last_seen: Dict[str, float] = {}
        # PC 端连接 {imei: [websocket, ...]} - 端云互发用（同 IMEI 支持多 PC 并发在线）
        self.pc_connections: Dict[str, List[WebSocket]] = {}
        # 手机 Agent 通道连接 {imei: websocket}
        self.mobile_agent_connections: Dict[str, WebSocket] = {}
        # TopoMobile adapter 连接 {imei_id: websocket}
        self.adapter_connections: Dict[str, WebSocket] = {}
        # 用户 -> adapter 路由映射 {imei: imei_id}
        self.user_adapter_route: Dict[str, str] = {}
        # GUI 请求路由缓存：gui_request_id -> {imei, route_imei_id, thread_id, updated_at}
        self.gui_request_route: Dict[str, Dict[str, Any]] = {}
        # mobile_tool 请求路由缓存：request_id -> {imei, route_imei_id, updated_at}
        self.mobile_tool_request_route: Dict[str, Dict[str, Any]] = {}
        # 已在客服通道登记的用户：群内内置 assistant 走其 TopoClaw 默认代理（chat 不传 agent_id）
        self.users_with_default_topoclaw_slot: set[str] = set()
    
    async def connect(self, websocket: WebSocket, imei: str, device: str = "mobile"):
        """建立连接，device: mobile 或 pc"""
        await websocket.accept()
        if device == "pc":
            peers = self.pc_connections.setdefault(imei, [])
            if websocket not in peers:
                peers.append(websocket)
            total_pc_conn = sum(len(v) for v in self.pc_connections.values())
            logger.info(
                f"PC 上线: {imei[:8]}..., IMEI下连接数: {len(peers)}, PC总连接数: {total_pc_conn}, PC在线IMEI数: {len(self.pc_connections)}"
            )
        else:
            self.active_connections[imei] = websocket
            self.mobile_last_seen[imei] = time.monotonic()
            logger.info(f"用户上线: {imei[:8]}..., 当前在线用户数: {len(self.active_connections)}")
    
    async def disconnect(self, imei: str, device: str = "mobile", websocket: Optional[WebSocket] = None):
        """断开连接，仅清理对应 device 类型的连接（mobile 或 pc）"""
        if device == "pc":
            peers = self.pc_connections.get(imei, [])
            if not peers:
                return
            if websocket is None:
                # 兼容历史调用：未知具体 socket 时，清理该 IMEI 下全部 PC 连接。
                del self.pc_connections[imei]
                total_pc_conn = sum(len(v) for v in self.pc_connections.values())
                logger.info(
                    f"PC 下线(清空IMEI全部连接): {imei[:8]}..., PC总连接数: {total_pc_conn}, PC在线IMEI数: {len(self.pc_connections)}"
                )
                return
            next_peers = [ws for ws in peers if ws is not websocket]
            if next_peers:
                self.pc_connections[imei] = next_peers
            else:
                self.pc_connections.pop(imei, None)
            total_pc_conn = sum(len(v) for v in self.pc_connections.values())
            logger.info(
                f"PC 下线: {imei[:8]}..., 剩余IMEI连接数: {len(next_peers)}, PC总连接数: {total_pc_conn}, PC在线IMEI数: {len(self.pc_connections)}"
            )
        else:
            if imei in self.active_connections:
                del self.active_connections[imei]
            if imei in self.mobile_last_seen:
                del self.mobile_last_seen[imei]
                logger.info(f"用户下线: {imei[:8]}...")

    def touch_user_presence(self, imei: str) -> None:
        """更新手机端最近活跃时间（用于更稳健的在线判定）。"""
        if imei in self.active_connections:
            self.mobile_last_seen[imei] = time.monotonic()

    def is_user_ws_connected(self, imei: str) -> bool:
        """仅检查是否存在 mobile WS 连接（不判断新鲜度）。"""
        return imei in self.active_connections

    def register_mobile_agent_connection(self, imei: str, websocket: WebSocket) -> None:
        """登记手机到中转服务的 agent WS 连接（连接已在外部 accept）。"""
        self.mobile_agent_connections[imei] = websocket
        logger.info(f"手机 Agent 通道上线: {imei[:8]}..., count={len(self.mobile_agent_connections)}")

    def unregister_mobile_agent_connection(self, imei: str, websocket: WebSocket | None = None) -> None:
        cur = self.mobile_agent_connections.get(imei)
        if cur is None:
            return
        if websocket is not None and cur is not websocket:
            return
        del self.mobile_agent_connections[imei]
        logger.info(f"手机 Agent 通道下线: {imei[:8]}..., count={len(self.mobile_agent_connections)}")

    def register_adapter_connection(self, imei_id: str, websocket: WebSocket) -> None:
        """登记 TopoMobile adapter 连接（连接已在外部 accept）。"""
        self.adapter_connections[imei_id] = websocket
        logger.info(f"TopoMobile adapter 上线: imei_id={imei_id}, count={len(self.adapter_connections)}")

    def unregister_adapter_connection(self, imei_id: str, websocket: WebSocket | None = None) -> None:
        cur = self.adapter_connections.get(imei_id)
        if cur is None:
            return
        if websocket is not None and cur is not websocket:
            return
        del self.adapter_connections[imei_id]
        logger.info(f"TopoMobile adapter 下线: imei_id={imei_id}, count={len(self.adapter_connections)}")

    def bind_user_adapter_route(self, imei: str, imei_id: str) -> None:
        """绑定用户 IMEI 到 adapter imei_id 的路由映射。"""
        if imei and imei_id:
            self.user_adapter_route[imei] = imei_id
            try:
                from services.custom_assistant_store import ensure_default_topoclaw_assistant

                ensure_default_topoclaw_assistant(imei, display_id=imei_id)
            except Exception:
                logger.exception("同步默认 OpenClaw 小助手 displayId 失败: imei=%s", imei[:12])

    def resolve_user_adapter_route(self, imei: str) -> Optional[str]:
        """获取用户当前绑定的 adapter imei_id（显式 bind 优先，否则默认表）。"""
        key = (imei or "").strip()
        if not key:
            return None
        explicit = self.user_adapter_route.get(key)
        if explicit:
            return explicit
        return _DEFAULT_ADAPTER_ROUTE_BY_IMEI.get(key)

    def remember_gui_request_route(
        self,
        *,
        gui_request_id: str,
        imei: str,
        route_imei_id: str | None,
        thread_id: str | None = None,
    ) -> None:
        request_id = str(gui_request_id or "").strip()
        if not request_id:
            return
        self.gui_request_route[request_id] = {
            "imei": str(imei or "").strip(),
            "route_imei_id": str(route_imei_id or "").strip(),
            "thread_id": str(thread_id or "").strip(),
            "updated_at": time.time(),
        }

    def resolve_gui_request_route(self, gui_request_id: str) -> Dict[str, Any] | None:
        request_id = str(gui_request_id or "").strip()
        if not request_id:
            return None
        item = self.gui_request_route.get(request_id)
        return dict(item) if isinstance(item, dict) else None

    def forget_gui_request_route(self, gui_request_id: str) -> None:
        request_id = str(gui_request_id or "").strip()
        if not request_id:
            return
        self.gui_request_route.pop(request_id, None)

    def remember_mobile_tool_request_route(
        self,
        *,
        request_id: str,
        imei: str,
        route_imei_id: str | None,
    ) -> None:
        rid = str(request_id or "").strip()
        if not rid:
            return
        self.mobile_tool_request_route[rid] = {
            "imei": str(imei or "").strip(),
            "route_imei_id": str(route_imei_id or "").strip(),
            "updated_at": time.time(),
        }

    def resolve_mobile_tool_request_route(self, request_id: str) -> Dict[str, Any] | None:
        rid = str(request_id or "").strip()
        if not rid:
            return None
        item = self.mobile_tool_request_route.get(rid)
        return dict(item) if isinstance(item, dict) else None

    def forget_mobile_tool_request_route(self, request_id: str) -> None:
        rid = str(request_id or "").strip()
        if not rid:
            return
        self.mobile_tool_request_route.pop(rid, None)

    def register_default_topoclaw_user_slot(self, imei: str) -> None:
        """用户连接/登录后：在 custom_assistants.json 写入默认 OpenClaw 助手条目（与群内 assistant 无关）。"""
        key = (imei or "").strip()
        if not key:
            return
        self.users_with_default_topoclaw_slot.add(key)
        try:
            from services.custom_assistant_store import ensure_default_topoclaw_assistant

            ensure_default_topoclaw_assistant(key)
        except Exception:
            logger.exception("写入默认 OpenClaw 小助手失败: imei=%s", key[:12])
        logger.info("已登记用户默认 OpenClaw 小助手槽位: imei=%s...", key[:12])
    
    def is_user_online(self, imei: str) -> bool:
        """检查手机用户是否在线（WS 连接 + 最近活跃时间未过期）。"""
        if imei not in self.active_connections:
            return False
        last_seen = self.mobile_last_seen.get(imei)
        if last_seen is None:
            # 兼容老连接：无时间戳时先按在线处理，避免灰度期误判
            return True
        return (time.monotonic() - last_seen) <= self.MOBILE_PRESENCE_TTL_SECONDS
    
    async def send_to_user(self, imei: str, message: Dict):
        """向指定用户发送消息"""
        msg_type = message.get("type", "unknown")

        if imei not in self.active_connections:
            logger.debug(f"用户不在线: {imei[:8]}..., msg_type={msg_type}")
            return False

        websocket = self.active_connections[imei]
        logger.debug(f"send_to_user: imei={imei[:8]}..., msg_type={msg_type}")

        try:
            await websocket.send_json(message)
            self.mobile_last_seen[imei] = time.monotonic()
            return True
        except (RuntimeError, ConnectionResetError) as e:
            logger.warning(f"发送消息失败: {imei[:8]}... - {type(e).__name__}: {e}")
            self.active_connections.pop(imei, None)
            self.mobile_last_seen.pop(imei, None)
            return False
        except Exception as e:
            logger.error(f"发送消息失败（{type(e).__name__}）: {imei[:8]}... - {e}", exc_info=True)
            self.active_connections.pop(imei, None)
            self.mobile_last_seen.pop(imei, None)
            return False
    
    async def broadcast(self, message: Dict):
        """广播消息给所有在线用户"""
        disconnected = []
        for imei, websocket in self.active_connections.items():
            try:
                await websocket.send_json(message)
            except Exception as e:
                logger.error(f"广播消息失败: {imei[:8]}... - {e}")
                disconnected.append(imei)
        
        # 清理断开的连接
        for imei in disconnected:
            if imei in self.active_connections:
                del self.active_connections[imei]
            if imei in self.mobile_last_seen:
                del self.mobile_last_seen[imei]
    
    def get_online_users(self) -> List[str]:
        """获取所有在线用户的IMEI列表"""
        return list(self.active_connections.keys())
    
    def is_pc_online(self, imei: str) -> bool:
        """检查 PC 端是否在线"""
        peers = self.pc_connections.get(imei) or []
        return len(peers) > 0
    
    async def send_to_pc(self, imei: str, message: Dict) -> bool:
        """向 PC 端发送消息（端云互发）：同 IMEI 所有 PC 连接广播。"""
        peers = list(self.pc_connections.get(imei) or [])
        if not peers:
            return False
        ok_count = 0
        next_peers: List[WebSocket] = []
        for ws in peers:
            try:
                await ws.send_json(message)
                ok_count += 1
                next_peers.append(ws)
            except Exception as e:
                logger.warning(f"发送消息到 PC 失败: {imei[:8]}... - {e}")
        if next_peers:
            self.pc_connections[imei] = next_peers
        else:
            self.pc_connections.pop(imei, None)
        if len(next_peers) != len(peers):
            logger.warning(
                f"PC 连接已清理: imei={imei[:8]}..., before={len(peers)}, after={len(next_peers)}"
            )
        return ok_count > 0

    async def notify_imei_all_devices(self, imei: str, message: Dict) -> None:
        """向该 IMEI 的手机端与 PC 端同时推送（若在线），用于跨端同步如 active session"""
        if imei in self.active_connections:
            try:
                await self.active_connections[imei].send_json(message)
                self.mobile_last_seen[imei] = time.monotonic()
            except Exception as e:
                logger.warning(f"notify mobile 失败: {imei[:8]}... - {e}")
                if imei in self.active_connections:
                    del self.active_connections[imei]
                if imei in self.mobile_last_seen:
                    del self.mobile_last_seen[imei]
        if imei in self.pc_connections:
            peers = list(self.pc_connections.get(imei) or [])
            next_peers: List[WebSocket] = []
            for ws in peers:
                try:
                    await ws.send_json(message)
                    next_peers.append(ws)
                except Exception as e:
                    logger.warning(f"notify PC 失败: {imei[:8]}... - {e}")
            if next_peers:
                self.pc_connections[imei] = next_peers
            else:
                self.pc_connections.pop(imei, None)
