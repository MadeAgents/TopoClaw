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

import { DEFAULT_TOPOCLAW_ASSISTANT_ID } from './customAssistants'
import { CONVERSATION_ID_ASSISTANT, CONVERSATION_ID_ME } from '../types/conversation'

const STORAGE_KEY = 'pc-conversation-pins'

/** 首次安装/从未写过置顶时：内置 TopoClaw 第一、自动执行小助手第二 */
function defaultPinnedConversationIds(): string[] {
  return [CONVERSATION_ID_ME, DEFAULT_TOPOCLAW_ASSISTANT_ID, CONVERSATION_ID_ASSISTANT]
}

function ensureRequiredPins(ids: string[]): string[] {
  if (ids.includes(CONVERSATION_ID_ME)) return ids
  return [CONVERSATION_ID_ME, ...ids]
}

function readIds(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    // 区分「未初始化」与「用户已清空置顶」：仅 missing key 时写入默认
    if (raw === null) {
      const defaults = defaultPinnedConversationIds()
      writeIds(defaults)
      return [...defaults]
    }
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is string => typeof x === 'string' && x.length > 0)
  } catch {
    return []
  }
}

function writeIds(ids: string[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(ids))
}

export function getPinnedConversationIds(): string[] {
  const normalized = ensureRequiredPins(readIds())
  writeIds(normalized)
  return normalized
}

/** 置顶：插到置顶区最前（同 id 会去重） */
export function pinConversation(id: string): string[] {
  const next = ensureRequiredPins([id, ...readIds().filter((x) => x !== id)])
  writeIds(next)
  return next
}

export function unpinConversation(id: string): string[] {
  if (id === CONVERSATION_ID_ME) return getPinnedConversationIds()
  const next = ensureRequiredPins(readIds().filter((x) => x !== id))
  writeIds(next)
  return next
}

export function isConversationPinned(id: string): boolean {
  return readIds().includes(id)
}
