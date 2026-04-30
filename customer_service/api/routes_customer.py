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

"""人工客服：注册、发消息、健康检查、在线状态、全量聊天记录"""
import logging
from datetime import datetime

from fastapi import APIRouter, HTTPException
from fastapi.responses import JSONResponse

from core.deps import connection_manager, conversation_logger, message_service
from core.time_utils import TZ_UTC_PLUS_8, get_now_isoformat, get_now_timestamp_ms
from schemas.models import RegisterRequest, SendMessageRequest

logger = logging.getLogger(__name__)
router = APIRouter(tags=["customer-service"])


@router.post("/api/customer-service/register")
async def register_user(request: RegisterRequest):
    """用户上线注册"""
    try:
        logger.info(f"用户注册: imei={request.imei[:8]}...")
        return JSONResponse(
            {"success": True, "message": "注册成功", "imei": request.imei}
        )
    except Exception as e:
        logger.error(f"注册失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/customer-service/send-message")
async def send_message_to_user(request: SendMessageRequest):
    """人工客服向用户发送消息（支持离线推送）"""
    try:
        imei = request.imei
        is_online = connection_manager.is_user_online(imei)
        logger.info(f"准备发送消息给用户: {imei[:8]}..., 在线状态: {is_online}")

        message = {
            "type": "service_message",
            "content": request.content,
            "message_type": request.message_type,
            "timestamp": get_now_isoformat(),
            "sender": "人工客服",
        }

        logger.info(f"消息内容: {message}")

        await message_service.send_message_to_user(imei, message)

        conversation_logger.log_service_message(
            imei=imei,
            content=message["content"],
            sender=message["sender"],
            timestamp=message["timestamp"],
            extra_data={"message_type": message.get("message_type", "text")},
        )

        final_status = connection_manager.is_user_online(imei)
        logger.info(f"消息发送完成，用户最终在线状态: {final_status}")

        return JSONResponse(
            {
                "success": True,
                "message": "消息已发送",
                "is_online": final_status,
                "imei": imei[:8] + "...",
            }
        )
    except Exception as e:
        logger.error(f"发送消息失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/customer-service/health")
async def health_check():
    """健康检查"""
    return JSONResponse({"status": "ok", "service": "customer_service"})


@router.get("/api/customer-service/online-users")
async def get_online_users():
    """获取所有在线用户的IMEI列表"""
    try:
        online_users = connection_manager.get_online_users()
        return JSONResponse(
            {"success": True, "count": len(online_users), "users": online_users}
        )
    except Exception as e:
        logger.error(f"获取在线用户列表失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/customer-service/user-status/{imei}")
async def get_user_status(imei: str):
    """获取指定用户的在线状态"""
    try:
        is_online = connection_manager.is_user_online(imei)
        offline_count = message_service.get_offline_message_count(imei)
        return JSONResponse(
            {
                "success": True,
                "imei": imei,
                "is_online": is_online,
                "offline_message_count": offline_count,
            }
        )
    except Exception as e:
        logger.error(f"获取用户状态失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/customer-service/pc-status/{imei}")
async def get_pc_status(imei: str):
    """获取指定用户 PC 端在线状态"""
    try:
        is_pc_online = connection_manager.is_pc_online(imei)
        return JSONResponse(
            {
                "success": True,
                "imei": imei,
                "is_pc_online": is_pc_online,
            }
        )
    except Exception as e:
        logger.error(f"获取PC在线状态失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/api/customer-service/test-connection/{imei}")
async def test_connection(imei: str):
    """测试WebSocket连接并发送测试消息"""
    try:
        is_online = connection_manager.is_user_online(imei)
        logger.info(f"测试连接: imei={imei[:8]}..., 在线状态: {is_online}")

        if not is_online:
            return JSONResponse(
                {
                    "success": False,
                    "message": "用户不在线",
                    "imei": imei[:8] + "...",
                    "is_online": False,
                }
            )

        test_message = {
            "type": "service_message",
            "content": "这是一条连接测试消息",
            "message_type": "text",
            "timestamp": get_now_isoformat(),
            "sender": "系统测试",
        }

        logger.info(f"准备发送测试消息给: {imei[:8]}...")
        result = await connection_manager.send_to_user(imei, test_message)

        return JSONResponse(
            {
                "success": result,
                "message": "测试消息已发送" if result else "测试消息发送失败",
                "imei": imei[:8] + "...",
                "is_online": is_online,
                "test_message_sent": result,
            }
        )
    except Exception as e:
        logger.error(f"测试连接失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/api/customer-service/all-chats")
async def get_all_chats():
    """获取所有用户与人工客服的聊天记录（管理员模式）"""
    try:
        from pathlib import Path

        conversations_dir = conversation_logger.base_dir

        logger.info(f"查找聊天记录目录: {conversations_dir}")
        logger.info(f"目录是否存在: {conversations_dir.exists()}")

        online_users = connection_manager.get_online_users()
        logger.info(f"当前在线用户数: {len(online_users)}")

        jsonl_files = list(conversations_dir.glob("*.jsonl")) if conversations_dir.exists() else []
        logger.info(f"找到 {len(jsonl_files)} 个JSONL文件")

        processed_imeis = set()
        all_chats = []

        for file_path in jsonl_files:
            imei = file_path.stem
            logger.info(f"处理文件: {file_path.name}, IMEI: {imei}")

            messages = conversation_logger.get_conversation_history(imei)
            logger.info(f"用户 {imei} 的聊天记录数量: {len(messages)}")

            formatted_messages = []
            for msg in messages:
                timestamp_value = msg.get("timestamp")
                if timestamp_value is None:
                    timestamp_ms = get_now_timestamp_ms()
                elif isinstance(timestamp_value, (int, float)):
                    timestamp_ms = int(timestamp_value)
                elif isinstance(timestamp_value, str):
                    try:
                        if "T" in timestamp_value:
                            clean_timestamp = timestamp_value.replace("Z", "").strip()

                            if "+" in clean_timestamp or (
                                clean_timestamp.count("-") > 2
                                and ":" in clean_timestamp.split("T")[1]
                            ):
                                dt = datetime.fromisoformat(clean_timestamp)
                            else:
                                dt = datetime.fromisoformat(clean_timestamp)
                                dt = dt.replace(tzinfo=TZ_UTC_PLUS_8)

                            timestamp_ms = int(dt.timestamp() * 1000)
                        else:
                            timestamp_ms = int(float(timestamp_value))
                    except (ValueError, AttributeError) as e:
                        logger.warning(
                            f"解析timestamp失败: {timestamp_value}, 使用当前时间, error={e}"
                        )
                        timestamp_ms = int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)
                else:
                    timestamp_ms = int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)

                formatted_messages.append(
                    {
                        "sender": msg.get("sender", "未知"),
                        "content": msg.get("content", ""),
                        "timestamp": timestamp_ms,
                        "message_type": msg.get("type", "text"),
                    }
                )

            all_chats.append({"imei": imei, "messages": formatted_messages})
            processed_imeis.add(imei)

        for imei in online_users:
            if imei not in processed_imeis:
                logger.info(f"添加在线用户（无jsonl文件）: {imei}")
                all_chats.append({"imei": imei, "messages": []})
                processed_imeis.add(imei)

        if all_chats:

            def get_sort_key(chat):
                if chat["messages"]:
                    return chat["messages"][-1]["timestamp"]
                try:
                    fp = conversations_dir / f"{chat['imei']}.jsonl"
                    if fp.exists():
                        return int(fp.stat().st_mtime * 1000)
                except Exception:
                    pass
                return get_now_timestamp_ms()

            all_chats.sort(key=get_sort_key, reverse=True)

        logger.info(
            f"获取所有聊天记录成功，共 {len(all_chats)} 个用户（包括 {len(online_users)} 个在线用户）"
        )
        if all_chats:
            for chat in all_chats:
                message_count = len(chat["messages"])
                is_on = chat["imei"] in online_users
                status = "在线" if is_on else "离线"
                logger.info(f"  - 用户 {chat['imei']}: {message_count} 条消息, 状态: {status}")
        else:
            logger.info("  没有找到任何聊天记录")

        return JSONResponse(
            {
                "success": True,
                "message": f"成功获取 {len(all_chats)} 个用户的聊天记录",
                "chats": all_chats,
            }
        )
    except Exception as e:
        logger.error(f"获取所有聊天记录失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
