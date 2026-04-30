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
对话记录服务
将客服助手与每个IMEI的对话记录保存到文件，支持增量追加
使用JSON Lines格式（每行一个JSON对象），便于增量追加和流式处理
"""
import json
import logging
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Dict, Optional

from core.output_paths import CUSTOMER_SERVICE_DIR

# 东八区时区（UTC+8）
TZ_UTC_PLUS_8 = timezone(timedelta(hours=8))

logger = logging.getLogger(__name__)


class ConversationLogger:
    """对话记录器，支持增量追加JSON Lines格式"""
    
    def __init__(self, base_dir: Optional[str] = None):
        """
        初始化对话记录器
        
        Args:
            base_dir: 对话记录存储的基础目录，默认为 customer_service2/outputs/customer_service
        """
        if base_dir is None:
            base_dir = str(CUSTOMER_SERVICE_DIR)

        self.base_dir = Path(base_dir)
        # 确保目录存在
        self.base_dir.mkdir(parents=True, exist_ok=True)
        logger.info(f"对话记录目录: {self.base_dir}")
    
    def _get_file_path(self, imei: str) -> Path:
        """
        获取指定IMEI的对话记录文件路径
        
        Args:
            imei: 设备IMEI
            
        Returns:
            文件路径
        """
        # 清理IMEI中的特殊字符，确保文件名安全
        safe_imei = self._sanitize_filename(imei)
        return self.base_dir / f"{safe_imei}.jsonl"
    
    def _sanitize_filename(self, filename: str) -> str:
        """
        清理文件名中的特殊字符，确保文件名安全
        
        Args:
            filename: 原始文件名
            
        Returns:
            清理后的文件名
        """
        # Windows不允许的字符: < > : " / \ | ? *
        # 替换为下划线
        invalid_chars = '<>:"/\\|?*'
        safe_name = filename
        for char in invalid_chars:
            safe_name = safe_name.replace(char, '_')
        return safe_name
    
    def log_message(self, imei: str, message_type: str, content: str, 
                    sender: str, timestamp: Optional[str] = None, 
                    extra_data: Optional[Dict] = None):
        """
        记录一条对话消息（增量追加）
        
        Args:
            imei: 设备IMEI
            message_type: 消息类型（如 "user_message", "service_message"）
            content: 消息内容
            sender: 发送者（如 "用户", "人工客服"）
            timestamp: 时间戳（ISO格式字符串或数字毫秒），如果为None则使用当前时间
            extra_data: 额外的数据字段（可选）
        """
        try:
            file_path = self._get_file_path(imei)
            
            # 统一时间戳格式：转换为数字（毫秒）
            timestamp_ms = None
            
            # 优先使用extra_data中的original_timestamp（数字）
            if extra_data and "original_timestamp" in extra_data:
                original_ts = extra_data["original_timestamp"]
                if isinstance(original_ts, (int, float)):
                    timestamp_ms = int(original_ts)
            
            # 如果没有original_timestamp，处理timestamp参数
            if timestamp_ms is None:
                if timestamp is None:
                    # 使用当前时间（东八区）
                    timestamp_ms = int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)
                elif isinstance(timestamp, (int, float)):
                    # 已经是数字，直接使用
                    timestamp_ms = int(timestamp)
                elif isinstance(timestamp, str):
                    # ISO格式字符串，转换为毫秒时间戳
                    try:
                        if 'T' in timestamp:
                            # ISO格式：2026-01-14T12:09:47.216886
                            clean_timestamp = timestamp.replace('Z', '').strip()
                            
                            # 检查是否包含时区偏移
                            if '+' in clean_timestamp or (clean_timestamp.count('-') > 2 and ':' in clean_timestamp.split('T')[1]):
                                # 包含时区信息，直接解析
                                dt = datetime.fromisoformat(clean_timestamp)
                            else:
                                # 不包含时区信息，按东八区（UTC+8）解析
                                dt = datetime.fromisoformat(clean_timestamp)
                                # 将naive datetime转换为东八区时区
                                dt = dt.replace(tzinfo=TZ_UTC_PLUS_8)
                            
                            timestamp_ms = int(dt.timestamp() * 1000)
                        else:
                            # 可能是纯数字字符串
                            timestamp_ms = int(float(timestamp))
                    except (ValueError, AttributeError) as e:
                        logger.warning(f"解析timestamp失败: {timestamp}, 使用当前时间, error={e}")
                        timestamp_ms = int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)
                else:
                    # 其他类型，使用当前时间（东八区）
                    timestamp_ms = int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)
            
            # 构建消息记录，统一使用数字时间戳（毫秒）
            record = {
                "timestamp": timestamp_ms,  # 统一保存为数字（毫秒）
                "type": message_type,
                "sender": sender,
                "content": content
            }
            
            # 添加额外数据（排除original_timestamp，避免重复）
            if extra_data:
                filtered_extra = {k: v for k, v in extra_data.items() if k != "original_timestamp"}
                if filtered_extra:
                    record.update(filtered_extra)
            
            # 追加到文件（JSON Lines格式：每行一个JSON对象）
            with open(file_path, 'a', encoding='utf-8') as f:
                json.dump(record, f, ensure_ascii=False)
                f.write('\n')  # 每行一个JSON对象
            
            logger.debug(f"已记录对话消息: imei={imei[:8]}..., type={message_type}, sender={sender}, timestamp={timestamp_ms}")
            
        except Exception as e:
            logger.error(f"记录对话消息失败: imei={imei[:8]}..., error={e}", exc_info=True)
    
    def log_user_message(self, imei: str, content: str, 
                        timestamp: Optional[str] = None,
                        extra_data: Optional[Dict] = None):
        """
        记录用户消息
        
        Args:
            imei: 设备IMEI
            content: 消息内容
            timestamp: 时间戳（ISO格式），如果为None则使用当前时间
            extra_data: 额外的数据字段（可选）
        """
        self.log_message(
            imei=imei,
            message_type="user_message",
            content=content,
            sender="用户",
            timestamp=timestamp,
            extra_data=extra_data
        )
    
    def log_service_message(self, imei: str, content: str,
                           sender: str = "人工客服",
                           timestamp: Optional[str] = None,
                           extra_data: Optional[Dict] = None):
        """
        记录客服消息
        
        Args:
            imei: 设备IMEI
            content: 消息内容
            sender: 发送者，默认为"人工客服"
            timestamp: 时间戳（ISO格式），如果为None则使用当前时间
            extra_data: 额外的数据字段（可选）
        """
        self.log_message(
            imei=imei,
            message_type="service_message",
            content=content,
            sender=sender,
            timestamp=timestamp,
            extra_data=extra_data
        )
    
    def get_conversation_history(self, imei: str, limit: Optional[int] = None) -> list:
        """
        获取指定IMEI的对话历史记录
        
        Args:
            imei: 设备IMEI
            limit: 返回的记录数量限制，None表示返回所有记录
            
        Returns:
            对话记录列表（按时间顺序）
        """
        try:
            file_path = self._get_file_path(imei)
            
            if not file_path.exists():
                return []
            
            records = []
            with open(file_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            record = json.loads(line)
                            records.append(record)
                        except json.JSONDecodeError as e:
                            logger.warning(f"解析对话记录失败: {e}, line={line[:100]}")
                            continue
            
            # 如果指定了limit，返回最新的limit条记录
            if limit is not None and limit > 0:
                records = records[-limit:]
            
            return records
            
        except Exception as e:
            logger.error(f"获取对话历史失败: imei={imei[:8]}..., error={e}", exc_info=True)
            return []
    
    def get_conversation_count(self, imei: str) -> int:
        """
        获取指定IMEI的对话记录数量
        
        Args:
            imei: 设备IMEI
            
        Returns:
            对话记录数量
        """
        try:
            file_path = self._get_file_path(imei)
            
            if not file_path.exists():
                return 0
            
            count = 0
            with open(file_path, 'r', encoding='utf-8') as f:
                for line in f:
                    if line.strip():
                        count += 1
            
            return count
            
        except Exception as e:
            logger.error(f"获取对话记录数量失败: imei={imei[:8]}..., error={e}", exc_info=True)
            return 0
