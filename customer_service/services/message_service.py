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
消息服务
处理消息发送、离线消息存储和推送
"""
import logging
from datetime import datetime
from typing import Dict, List, Optional
from websocket.connection_manager import ConnectionManager

logger = logging.getLogger(__name__)


class MessageService:
    """消息服务类"""
    
    def __init__(self, connection_manager: ConnectionManager):
        self.connection_manager = connection_manager
        # 离线消息存储 {imei: [messages]}
        self.offline_messages: Dict[str, List[Dict]] = {}
    
    async def send_message_to_user(self, imei: str, message: Dict):
        """发送消息给用户（在线直接推送，离线存入队列）；同时推送至手机和 PC（若已连接）"""
        is_mobile_online = self.connection_manager.is_user_online(imei)
        is_pc_online = self.connection_manager.is_pc_online(imei)
        logger.info(f"send_message_to_user: imei={imei[:8]}..., 手机在线={is_mobile_online}, PC在线={is_pc_online}")
        
        if is_mobile_online:
            result = await self.connection_manager.send_to_user(imei, message)
            if result:
                logger.info(f"实时推送消息成功给手机: {imei[:8]}...")
            else:
                logger.warning(f"实时推送消息失败（手机），用户可能已断开: {imei[:8]}...")
                if not is_pc_online:
                    await self.save_offline_message(imei, message)
                    logger.info(f"已保存为离线消息: {imei[:8]}...")
        if is_pc_online:
            pc_result = await self.connection_manager.send_to_pc(imei, message)
            if pc_result:
                logger.info(f"实时推送消息成功给PC: {imei[:8]}...")
        if not is_mobile_online and not is_pc_online:
            await self.save_offline_message(imei, message)
            logger.info(f"用户离线，保存离线消息: {imei[:8]}..., 离线消息数: {self.get_offline_message_count(imei)}")
    
    async def save_offline_message(self, imei: str, message: Dict):
        """保存离线消息"""
        if imei not in self.offline_messages:
            self.offline_messages[imei] = []
        
        self.offline_messages[imei].append({
            **message,
            "saved_at": datetime.now().isoformat()
        })
    
    async def get_offline_messages(self, imei: str) -> List[Dict]:
        """获取并清除用户的离线消息"""
        messages = self.offline_messages.get(imei, [])
        # 清除已获取的消息
        if imei in self.offline_messages:
            del self.offline_messages[imei]
        return messages
    
    def get_offline_message_count(self, imei: str) -> int:
        """获取用户的离线消息数量"""
        return len(self.offline_messages.get(imei, []))
