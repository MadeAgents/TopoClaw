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

import type { Conversation } from '../types/conversation'

/** 与侧栏 Tab 文案对齐：整词匹配时按类型筛选 */
export type GlobalSearchCategory = 'friend' | 'group' | 'assistant'

/**
 * 顶部搜索框：输入「好友」「单聊」「群组」「群聊」「助手」「小助手」时进入分类模式（仅匹配该类型）。
 * 否则按名称、最近消息预览子串匹配。
 */
export function getGlobalSearchCategory(searchRaw: string): GlobalSearchCategory | null {
  const q = searchRaw.trim().toLowerCase()
  if (q === '好友' || q === '单聊') return 'friend'
  if (q === '群组' || q === '群聊') return 'group'
  if (q === '助手' || q === '小助手') return 'assistant'
  return null
}

export function conversationMatchesGlobalSearch(
  c: Pick<Conversation, 'name' | 'type'>,
  searchRaw: string,
  lastMessagePreview?: string
): boolean {
  const q = searchRaw.trim().toLowerCase()
  if (!q) return true
  const cat = getGlobalSearchCategory(searchRaw)
  if (cat === 'friend') return c.type === 'friend'
  if (cat === 'group') return c.type === 'group'
  if (cat === 'assistant') return c.type === 'assistant' || c.type === 'cross_device'
  const preview = (lastMessagePreview ?? '').toLowerCase()
  return c.name.toLowerCase().includes(q) || preview.includes(q)
}
