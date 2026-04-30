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

import { useMemo } from 'react'
import type { Conversation } from '../types/conversation'
import { conversationMatchesGlobalSearch } from '../utils/globalSearch'
import { getPinnedConversationIds } from '../services/conversationPins'
import { toAvatarSrc } from '../utils/avatar'
import './GlobalSearchDropdown.css'

const MAX_RESULTS = 50

function conversationTypeLabel(c: Conversation): string {
  if (c.type === 'friend') return '好友'
  if (c.type === 'group') return '群组'
  if (c.type === 'assistant' || c.type === 'cross_device') return '助手'
  return ''
}

interface GlobalSearchDropdownProps {
  search: string
  lastMessages: Record<string, string>
  lastMessageTimes: Record<string, number>
  conversations: Conversation[]
  onSelect: (c: Conversation) => void
}

export function GlobalSearchDropdown({
  search,
  lastMessages,
  lastMessageTimes,
  conversations,
  onSelect,
}: GlobalSearchDropdownProps) {
  const sortedMatches = useMemo(() => {
    const q = search.trim()
    if (!q) return []
    const matched = conversations.filter((c) =>
      conversationMatchesGlobalSearch(c, search, lastMessages[c.id])
    )
    const pinnedSet = new Set(getPinnedConversationIds())
    matched.sort((a, b) => {
      const ap = pinnedSet.has(a.id)
      const bp = pinnedSet.has(b.id)
      if (ap !== bp) return ap ? -1 : 1
      const ta = lastMessageTimes[a.id] ?? a.lastMessageTime ?? 0
      const tb = lastMessageTimes[b.id] ?? b.lastMessageTime ?? 0
      return tb - ta
    })
    return matched.slice(0, MAX_RESULTS)
  }, [conversations, search, lastMessages, lastMessageTimes])

  if (!search.trim()) return null

  return (
    <div className="global-search-dropdown" role="listbox" aria-label="搜索结果">
      {sortedMatches.length === 0 ? (
        <div className="global-search-dropdown-empty">无匹配结果</div>
      ) : (
        sortedMatches.map((c) => (
          <button
            key={c.id}
            type="button"
            role="option"
            className="global-search-dropdown-item"
            onMouseDown={(e) => {
              e.preventDefault()
              onSelect(c)
            }}
          >
            <div className="global-search-dropdown-avatar">
              {c.avatar ? (
                <img src={toAvatarSrc(c.avatar) ?? c.avatar} alt="" />
              ) : (
                <span>{c.name.slice(0, 1)}</span>
              )}
            </div>
            <div className="global-search-dropdown-body">
              <div className="global-search-dropdown-top">
                <span className="global-search-dropdown-name">{c.name}</span>
                <span className="global-search-dropdown-tag">{conversationTypeLabel(c)}</span>
              </div>
              {lastMessages[c.id] ? (
                <div className="global-search-dropdown-preview">{lastMessages[c.id]}</div>
              ) : null}
            </div>
          </button>
        ))
      )}
    </div>
  )
}
