// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

export interface Conversation {
  id: string
  name: string
  avatar?: string
  lastMessage?: string
  lastMessageTime: number
  unreadCount?: number
  isPinned?: boolean
  type: 'assistant' | 'group' | 'friend' | 'cross_device'
  /** 群组成员 IMEI 列表（仅 type=group 时） */
  members?: string[]
  /** 群组内小助手列表（仅 type=group 时），含 id 与显示名，用于 @ 候选 */
  assistants?: Array<{ id: string; name: string }>
  /** 群组内自定义小助手的配置（assistant_id -> config），供成员与陌生助手建立联系 */
  assistantConfigs?: Record<string, {
    baseUrl?: string
    name?: string
    displayId?: string
    capabilities?: string[]
    rolePrompt?: string
    creator_imei?: string
    creator_nickname?: string
  }>
  /** 群级自由发言开关：开启后由服务端将新消息广播给所有助手 */
  groupFreeDiscoveryEnabled?: boolean
  /** 群级助手禁言开关：开启后助手不再参与群聊回复 */
  groupAssistantMutedEnabled?: boolean
  /** 群级编排模式开关：开启后优先进入编排执行链路（预留） */
  groupWorkflowModeEnabled?: boolean
  /** 服务端标记：是否系统默认群 */
  isDefaultGroup?: boolean
  /** 自定义小助手的 baseUrl，用于 session 存储 key（assistant id 云端同步后可能变化，baseUrl 稳定） */
  baseUrl?: string
  /** 自定义小助手展示唯一标识（云端 canonical displayId） */
  displayId?: string
  /** 自定义小助手是否支持多 session */
  multiSessionEnabled?: boolean
}

export const CONVERSATION_ID_ASSISTANT = 'assistant'
export const CONVERSATION_ID_SKILL_LEARNING = 'skill_learning'
export const CONVERSATION_ID_CHAT_ASSISTANT = 'chat_assistant'
export const CONVERSATION_ID_CUSTOMER_SERVICE = 'customer_service'
export const CONVERSATION_ID_GROUP = 'group'
export const CONVERSATION_ID_ME = '_me'
export const CONVERSATION_ID_IM_QQ = 'im_qq'
export const CONVERSATION_ID_IM_WEIXIN = 'im_weixin'
