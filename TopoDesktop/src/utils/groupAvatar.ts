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

import type { Friend } from '../services/api'
import {
  ASSISTANT_AVATAR,
  CHAT_ASSISTANT_AVATAR,
  SKILL_LEARNING_AVATAR,
  CUSTOMER_SERVICE_AVATAR,
} from '../constants/assistants'
import {
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_CHAT_ASSISTANT,
  CONVERSATION_ID_CUSTOMER_SERVICE,
  CONVERSATION_ID_SKILL_LEARNING,
} from '../types/conversation'

/** 好友群：左上=自己, 右上=自动执行, 左下=技能学习, 右下=人工客服（与端侧 createFriendsGroupAvatar 一致） */
export function getFriendsGroupAvatarSources(
  userAvatar: string | undefined,
  userName?: string
): {
  avatars: (string | undefined)[]
  placeholders: string[]
} {
  return {
    avatars: [
      userAvatar,
      ASSISTANT_AVATAR,
      SKILL_LEARNING_AVATAR,
      CUSTOMER_SERVICE_AVATAR,
    ],
    placeholders: [userName?.slice(0, 1) || '我', '', '', ''],
  }
}

interface GroupAvatarSourceOptions {
  /** 群内助手 ID 列表（按展示优先级） */
  assistants?: string[]
  /** 助手展示名（assistant_id -> name） */
  assistantNames?: Record<string, string>
  /** 助手头像/名称配置（assistant_id -> config） */
  assistantConfigs?: Record<string, { avatar?: string; name?: string }>
}

function getBuiltinAssistantAvatar(assistantId: string): string | undefined {
  switch (assistantId) {
    case CONVERSATION_ID_ASSISTANT:
      return ASSISTANT_AVATAR
    case CONVERSATION_ID_SKILL_LEARNING:
      return SKILL_LEARNING_AVATAR
    case CONVERSATION_ID_CUSTOMER_SERVICE:
      return CUSTOMER_SERVICE_AVATAR
    case CONVERSATION_ID_CHAT_ASSISTANT:
      return CHAT_ASSISTANT_AVATAR
    default:
      return undefined
  }
}

function getBuiltinAssistantName(assistantId: string): string | undefined {
  switch (assistantId) {
    case CONVERSATION_ID_ASSISTANT:
      return '自动执行小助手'
    case CONVERSATION_ID_SKILL_LEARNING:
      return '技能学习小助手'
    case CONVERSATION_ID_CUSTOMER_SERVICE:
      return '人工客服'
    case CONVERSATION_ID_CHAT_ASSISTANT:
      return '聊天小助手'
    default:
      return undefined
  }
}

/** 普通群组：按群内助手 + 群成员顺序取前 4 个，缺位留空 */
export function getGroupAvatarSourcesFromMembers(
  members: string[],
  friends: Friend[],
  userImei: string,
  userAvatar: string | undefined,
  options: GroupAvatarSourceOptions = {}
): {
  avatars: (string | undefined)[]
  placeholders: string[]
} {
  const assistants = options.assistants ?? []
  const assistantNames = options.assistantNames ?? {}
  const assistantConfigs = options.assistantConfigs ?? {}
  const avatars: (string | undefined)[] = []
  const placeholders: string[] = []

  for (const assistantId of assistants) {
    if (avatars.length >= 4) break
    const cfg = assistantConfigs[assistantId]
    const avatar = cfg?.avatar || getBuiltinAssistantAvatar(assistantId)
    const name = assistantNames[assistantId] || cfg?.name || getBuiltinAssistantName(assistantId) || assistantId
    avatars.push(avatar)
    placeholders.push((name || '').slice(0, 1))
  }

  for (const imei of members) {
    if (avatars.length >= 4) break
    if (imei === userImei) {
      avatars.push(userAvatar)
      placeholders.push('我')
    } else {
      const f = friends.find((x) => x.imei === imei)
      avatars.push(f?.avatar)
      placeholders.push((f?.nickname ?? imei).slice(0, 1) || '')
    }
  }
  while (avatars.length < 4) {
    avatars.push(undefined)
    placeholders.push('')
  }
  return { avatars, placeholders }
}
