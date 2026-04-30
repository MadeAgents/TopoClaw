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

"""群组 @ 自动执行小助手时的服务端回复逻辑（保留供调用；当前群消息由客户端直连 Agent）"""
import logging
import os
import uuid

import httpx

from core.deps import message_service
from core.time_utils import get_now_isoformat
from services.group_service import ASSISTANT_BOT_ID, get_group

logger = logging.getLogger(__name__)


async def handle_assistant_reply(group_id: str, user_message: str, sender_imei: str):
    """处理自动执行小助手的群组回复"""
    try:
        reply_content = f"收到您的消息：{user_message[:50]}... 我正在处理中，请稍候。"

        assistant_reply = {
            "type": "group_message",
            "groupId": group_id,
            "senderImei": ASSISTANT_BOT_ID,
            "content": reply_content,
            "timestamp": get_now_isoformat(),
            "sender": "自动执行小助手",
            "is_assistant_reply": True,
        }

        group = get_group(group_id)
        if group:
            members = group["members"]
            for member_imei in members:
                if member_imei != ASSISTANT_BOT_ID:
                    await message_service.send_message_to_user(member_imei, assistant_reply)

        logger.info(f"小助手已发送确认消息: {group_id}")

        agent_url = os.getenv("COLOROS_AGENT_URL", "http://localhost:5021")
        logger.info(f"准备调用TopoMobileAgent服务: {agent_url}")

        chat_uuid = str(uuid.uuid4())

        placeholder_image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        request_data = {
            "uuid": chat_uuid,
            "query": user_message,
            "images[0]": placeholder_image,
            "imei": sender_imei,
        }

        try:
            async with httpx.AsyncClient(timeout=60.0) as client:
                response = await client.post(
                    f"{agent_url}/upload",
                    data=request_data,
                    headers={"Content-Type": "application/x-www-form-urlencoded"},
                )

                if response.status_code == 200:
                    result = response.json()
                    logger.info(f"TopoMobileAgent服务处理成功: {group_id}, uuid={chat_uuid}")
                    logger.debug(f"服务返回结果: {result}")

                    reply_text = None

                    if result.get("message"):
                        reply_text = result.get("message")
                    elif result.get("reason"):
                        reply_text = result.get("reason")
                    elif result.get("thought"):
                        reply_text = result.get("thought")

                    if not reply_text:
                        action_type = result.get("action_type", "")
                        if action_type and action_type.lower() == "complete":
                            reply_text = "任务已完成"
                        elif action_type:
                            reply_text = f"已处理您的请求（动作类型: {action_type}）"
                        else:
                            reply_text = "已收到您的消息，正在处理中..."

                    if reply_text:
                        result_reply = {
                            "type": "group_message",
                            "groupId": group_id,
                            "senderImei": ASSISTANT_BOT_ID,
                            "content": reply_text,
                            "timestamp": get_now_isoformat(),
                            "sender": "自动执行小助手",
                            "is_assistant_reply": True,
                        }

                        if group:
                            members = group["members"]
                            for member_imei in members:
                                if member_imei != ASSISTANT_BOT_ID:
                                    await message_service.send_message_to_user(member_imei, result_reply)

                        logger.info(f"小助手已发送处理结果: {group_id}, 内容: {reply_text[:50]}...")
                    else:
                        logger.warning(f"TopoMobileAgent服务返回了结果但没有可用的回复文本: {result}")
                else:
                    logger.warning(
                        f"TopoMobileAgent服务返回错误状态码: {response.status_code}, 响应: {response.text}"
                    )

        except httpx.TimeoutException:
            logger.error(f"调用TopoMobileAgent服务超时: {agent_url}")
        except httpx.RequestError as e:
            logger.error(f"调用TopoMobileAgent服务失败: {e}")
        except Exception as e:
            logger.error(f"调用TopoMobileAgent服务时发生异常: {e}", exc_info=True)

        logger.info(f"小助手处理完成: {group_id}")
    except Exception as e:
        logger.error(f"处理小助手回复失败: {e}", exc_info=True)
