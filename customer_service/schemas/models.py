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

from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class RegisterRequest(BaseModel):
    imei: str
    device_info: Optional[Dict] = None
    user_profile: Optional[Dict] = None


class SendMessageRequest(BaseModel):
    imei: str
    content: str
    message_type: str = "text"


class AddFriendRequest(BaseModel):
    friendImei: str
    imei: str  # 当前用户IMEI（临时方案）


class AcceptFriendRequest(BaseModel):
    friendImei: str
    imei: str  # 当前用户IMEI（临时方案）


class SendFriendMessageRequest(BaseModel):
    targetImei: str
    content: str
    message_type: str = "text"
    imei: str  # 当前用户IMEI（临时方案）
    imageBase64: Optional[str] = None  # 好友图片消息（与 WebSocket 字段一致）
    senderLabel: Optional[str] = None
    isCloneReply: Optional[bool] = None
    cloneOwnerImei: Optional[str] = None
    cloneOrigin: Optional[str] = None


class RemoveFriendRequest(BaseModel):
    friendImei: str
    imei: str  # 当前用户IMEI（临时方案）


class CreateGroupRequest(BaseModel):
    name: str
    memberImeis: List[str]
    imei: str  # 创建者IMEI
    assistantEnabled: bool = True  # 是否添加小助手，可通过@小助手下达指令


class AddGroupMemberRequest(BaseModel):
    groupId: str
    memberImei: str
    imei: str  # 操作者IMEI


class RemoveGroupMemberRequest(BaseModel):
    groupId: str
    memberImei: str
    imei: str  # 操作者IMEI


class QuitGroupRequest(BaseModel):
    groupId: str
    imei: str  # 退出者IMEI


class DissolveGroupRequest(BaseModel):
    groupId: str
    imei: str  # 群主IMEI


class SetGroupAssistantRequest(BaseModel):
    groupId: str
    imei: str  # 操作者IMEI（仅群主可操作）
    enabled: bool  # True=添加小助手，False=移除小助手


class AddGroupAssistantRequest(BaseModel):
    groupId: str
    imei: str
    assistantId: str  # assistant/skill_learning/chat_assistant/自定义ID
    creatorImei: Optional[str] = None  # 可选：助手归属用户（用于好友数字分身入群）
    baseUrl: Optional[str] = None
    name: Optional[str] = None
    capabilities: Optional[List[str]] = None
    intro: Optional[str] = None
    avatar: Optional[str] = None
    multiSession: Optional[bool] = None
    displayId: Optional[str] = None
    rolePrompt: Optional[str] = None


class RemoveGroupAssistantRequest(BaseModel):
    groupId: str
    imei: str
    assistantId: str


class UpdateGroupAssistantConfigRequest(BaseModel):
    groupId: str
    imei: str
    assistantId: str
    capabilities: Optional[List[str]] = None
    baseUrl: Optional[str] = None
    name: Optional[str] = None
    intro: Optional[str] = None
    avatar: Optional[str] = None
    multiSession: Optional[bool] = None
    rolePrompt: Optional[str] = None
    assistantMuted: Optional[bool] = None


class UpdateGroupConfigRequest(BaseModel):
    groupId: str
    imei: str
    workflowMode: Optional[bool] = None
    freeDiscovery: Optional[bool] = None
    assistantMuted: Optional[bool] = None


class SendAssistantGroupMessageRequest(BaseModel):
    groupId: str
    content: str
    imei: str  # 发送者IMEI（用于验证权限）
    sender: str = "自动执行小助手"
    assistantId: Optional[str] = None


class SendGroupMessageRequest(BaseModel):
    groupId: str
    content: str
    imei: str  # 发送者IMEI（用于权限验证）
    message_type: str = "text"
    imageBase64: Optional[str] = None
    sender: Optional[str] = None
    senderLabel: Optional[str] = None
    isCloneReply: Optional[bool] = None
    cloneOwnerImei: Optional[str] = None
    cloneOrigin: Optional[str] = None
    skipServerAssistantDispatch: Optional[bool] = None


class SaveGroupWorkflowRequest(BaseModel):
    groupId: str
    imei: str
    workflow: Dict
    expectedVersion: Optional[int] = None


class BindingSubmitRequest(BaseModel):
    imei: str


class AdaptAssistantIdsRequest(BaseModel):
    imei: str


class CrossDeviceSendRequest(BaseModel):
    imei: str
    content: str
    message_type: str = "text"
    file_base64: Optional[str] = None
    file_name: Optional[str] = None


class CrossDeviceExecuteRequest(BaseModel):
    """PC 端发起执行指令，由手机端执行。支持自定义小助手上下文（assistant_base_url/conversation_id/chat_summary）"""

    imei: str
    query: str
    uuid: str
    steps: Optional[list[str]] = None
    assistant_base_url: Optional[str] = None
    conversation_id: Optional[str] = None
    chat_summary: Optional[str] = None


class CrossDeviceMobileToolInvokeRequest(BaseModel):
    """PC/agent 触发 mobile_tool/v1 调用（HTTP 桥接）。"""

    imei: str
    tool: str
    args: Dict = Field(default_factory=dict)
    conversation_id: Optional[str] = "assistant"
    protocol: Optional[str] = "mobile_tool/v1"
    request_id: Optional[str] = None
    wait_result: bool = True
    timeout_ms: int = 15000


class CustomAssistantChatAppendRequest(BaseModel):
    """自定义小助手聊天消息同步（跨设备），支持图片 base64，多 session 时传入 session_id"""

    imei: str
    assistant_id: str
    user_content: str
    assistant_content: str
    assistant_name: str = "小助手"
    file_base64: Optional[str] = None
    file_name: Optional[str] = None
    session_id: Optional[str] = None


class CustomAssistantItem(BaseModel):
    """单个自定义小助手"""

    id: str
    name: str
    intro: Optional[str] = None
    baseUrl: str
    capabilities: Optional[List[str]] = None
    avatar: Optional[str] = None
    multi_session_enabled: Optional[bool] = Field(None, alias="multiSessionEnabled")
    display_id: Optional[str] = Field(None, alias="displayId")
    assistant_origin: Optional[str] = Field(None, alias="assistantOrigin")
    creator_imei: Optional[str] = None


class CustomAssistantsSyncRequest(BaseModel):
    """自定义小助手列表同步"""

    imei: str
    assistants: List[CustomAssistantItem]
    client_type: Optional[str] = None


class SessionSyncRequest(BaseModel):
    """多 session 跨设备同步请求。base_url 可选，传入时用 baseUrl 作存储 key（解决 PC/手机 assistant id 不一致）"""

    imei: str
    conversation_id: str
    sessions: List[Dict] = Field(default_factory=list)
    base_url: Optional[str] = None


class ActiveSessionSetRequest(BaseModel):
    """设置当前活跃 session（跨端跟切）"""

    imei: str
    conversation_id: str
    active_session_id: str
    base_url: Optional[str] = None


class PlazaAssistantSubmitRequest(BaseModel):
    """用户将小助手上架到广场"""

    imei: str
    assistant: CustomAssistantItem


class PlazaAssistantAddRequest(BaseModel):
    """将广场小助手添加到用户列表"""

    imei: str


class PlazaAssistantUpdateRequest(BaseModel):
    """创建者更新广场中的小助手资料"""

    imei: str
    name: Optional[str] = None
    intro: Optional[str] = None
    baseUrl: Optional[str] = None
    avatar: Optional[str] = None


class PlazaSkillItem(BaseModel):
    """技能广场单条技能"""

    id: Optional[str] = None
    title: str
    original_purpose: Optional[str] = Field(None, alias="originalPurpose")
    steps: Optional[List[str]] = None
    execution_platform: Optional[str] = Field(None, alias="executionPlatform")
    author: Optional[str] = None
    tags: Optional[List[str]] = None
    package_base64: str
    package_file_name: Optional[str] = None


class PlazaSkillSubmitRequest(BaseModel):
    """用户将技能上架到广场"""

    imei: str
    skill: PlazaSkillItem
