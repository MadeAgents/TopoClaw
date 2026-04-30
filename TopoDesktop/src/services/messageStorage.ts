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

import { mergeUserMessageKeepImage, type ChatMessageWithMedia } from '../utils/chatImage'

const PREFIX = 'chat_messages_'

export interface StoredMessage {
  id: string
  sender: string
  content: string
  type: string
  timestamp: number
  /** 语义来源：user | friend | my_clone | friend_clone | assistant */
  messageSource?: 'user' | 'friend' | 'my_clone' | 'friend_clone' | 'assistant'
  /** 分身归属 IMEI（若为数字分身消息） */
  cloneOwnerImei?: string
  messageType?: 'text' | 'file'
  fileBase64?: string
  fileName?: string
  fileList?: Array<{ fileBase64: string; fileName?: string }>
}

function getMessageStorageKey(conversationId: string, sessionId?: string): string {
  return sessionId ? `${PREFIX}${conversationId}_${sessionId}` : PREFIX + conversationId
}

export function loadMessages(conversationId: string, sessionId?: string): StoredMessage[] {
  try {
    const key = getMessageStorageKey(conversationId, sessionId)
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

export function saveMessages(
  conversationId: string,
  messages: StoredMessage[],
  sessionId?: string
): void {
  const key = getMessageStorageKey(conversationId, sessionId)
  const persist = (nextMessages: StoredMessage[]): boolean => {
    try {
      localStorage.setItem(key, JSON.stringify(nextMessages))
      if (nextMessages.length > 0) {
        const lastTs = nextMessages[nextMessages.length - 1]?.timestamp
        if (typeof lastTs === 'number') updateMaxStoredMessageTimestamp(lastTs)
      }
      return true
    } catch {
      return false
    }
  }
  try {
    if (persist(messages)) return
    const { removedKeys } = evictOldestMessageBucketsForQuota(12)
    if (persist(messages)) {
      console.warn('[MessageStorage] saveMessages recovered after eviction', {
        key,
        removed: removedKeys.length,
      })
      return
    }
    // 仍超限时退化：仅保留最近消息，优先保证“能看到最新消息”
    const caps = [200, 120, 80, 40, 20, 10, 3, 1]
    for (const cap of caps) {
      const degraded = messages.slice(-cap)
      if (persist(degraded)) {
        console.warn('[MessageStorage] saveMessages degraded due to quota', {
          key,
          originalCount: messages.length,
          kept: degraded.length,
          removed: removedKeys.length,
        })
        return
      }
      evictOldestMessageBucketsForQuota(6)
    }
    console.warn('[MessageStorage] saveMessages failed after quota retries', {
      key,
      count: messages.length,
    })
  } catch (e) {
    console.warn('[MessageStorage] saveMessages unexpected error', e)
  }
}

/** 群组 canonical id（统一为 group_xxx 格式），用于避免 append 与 load 的 key 不一致导致消息丢失 */
function toGroupCanonicalId(id: string): string {
  const raw = id.replace(/^group_/, '')
  return raw ? `group_${raw}` : id
}

/** 群组消息加载：尝试 group_xxx 与 xxx 两种 key，合并后返回，避免 key 格式不一致导致消息被「吃掉」 */
export function loadMessagesForGroup(conversationId: string): StoredMessage[] {
  const canonical = toGroupCanonicalId(conversationId)
  const raw = conversationId.replace(/^group_/, '')
  const keys = [canonical, raw].filter((k) => k && k !== conversationId)
  const all = new Map<string, StoredMessage>()
  const fromMain = loadMessages(conversationId)
  fromMain.forEach((m) => all.set(m.id, m))
  for (const k of keys) {
    loadMessages(k).forEach((m) => all.set(m.id, m))
  }
  return [...all.values()].sort((a, b) => a.timestamp - b.timestamp)
}

/** 追加单条消息到会话存储（用于 WebSocket 收到消息时暂未查看该会话的场景） */
export function appendMessageToStorage(
  conversationId: string,
  message: StoredMessage,
  sessionId?: string
): void {
  try {
    const existing = loadMessages(conversationId, sessionId)
    const byId = new Map(existing.map((m) => [m.id, m]))
    byId.set(message.id, { ...message, type: message.type || 'assistant' })
    const merged = [...byId.values()].sort((a, b) => a.timestamp - b.timestamp)
    saveMessages(conversationId, merged, sessionId)
    // 群组：仅当会话 id 明确以 group_ 开头时，才写入 canonical key。
    // 不能把“看起来像十六进制”的普通会话 id 误判为群 id，否则会串会话。
    if (!sessionId && conversationId.startsWith('group_')) {
      const canonical = toGroupCanonicalId(conversationId)
      if (canonical !== conversationId) {
        const alt = loadMessages(canonical)
        const altById = new Map(alt.map((m) => [m.id, m]))
        altById.set(message.id, { ...message, type: message.type || 'assistant' })
        saveMessages(canonical, [...altById.values()].sort((a, b) => a.timestamp - b.timestamp))
      }
    }
  } catch {
    // ignore
  }
}

/** 检查本地是否存有该会话的消息（群聊、技能学习、人工客服等使用 localStorage 的会话） */
export function hasStoredMessages(conversationId: string, sessionId?: string): boolean {
  if (sessionId != null) {
    return loadMessages(conversationId, sessionId).length > 0
  }
  if (loadMessages(conversationId).length > 0) return true
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i)
    if (k?.startsWith(PREFIX + conversationId + '_')) return true
  }
  return false
}

const KEY_CONV_HAS_MSG = 'conversations_with_messages'
const KEY_BASELINE_TIMESTAMP = 'chat_baseline_timestamp'
const KEY_MAX_STORED_MESSAGE_TIMESTAMP = 'chat_messages_max_ts'

function updateMaxStoredMessageTimestamp(nextTs: number): void {
  if (!Number.isFinite(nextTs) || nextTs <= 0) return
  try {
    const raw = localStorage.getItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
    const prev = raw ? parseInt(raw, 10) : 0
    if (!Number.isFinite(prev) || nextTs > prev) {
      localStorage.setItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP, String(nextTs))
    }
  } catch {
    // ignore
  }
}

/** 获取基准时间戳（首次启动或最近一次清空的时间），用于增量同步 */
export function getBaselineTimestamp(): number {
  try {
    const v = localStorage.getItem(KEY_BASELINE_TIMESTAMP)
    if (v) return parseInt(v, 10)
    const now = Date.now()
    localStorage.setItem(KEY_BASELINE_TIMESTAMP, String(now))
    return now
  } catch {
    return Date.now()
  }
}

/** 重置基准时间戳（清空聊天记录时调用） */
export function resetBaselineTimestamp(): void {
  try {
    localStorage.setItem(KEY_BASELINE_TIMESTAMP, String(Date.now()))
  } catch {
    // ignore
  }
}

/** 获取会话最后一条消息的预览和时间戳（用于会话列表预加载） */
export function getLastMessagePreview(conversationId: string): { content: string; timestamp: number } | null {
  try {
    let last: StoredMessage | null = null

    if (conversationId.startsWith('group_')) {
      const msgs = loadMessagesForGroup(conversationId)
      last = msgs.length > 0 ? msgs[msgs.length - 1]! : null
    } else {
      const direct = loadMessages(conversationId)
      if (direct.length > 0) {
        last = direct[direct.length - 1]!
      } else {
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i)
          if (k?.startsWith(PREFIX + conversationId + '_')) {
            const raw = localStorage.getItem(k)
            if (!raw) continue
            try {
              const arr = JSON.parse(raw)
              const list = Array.isArray(arr) ? arr : []
              const lastInSession = list.length > 0 ? (list[list.length - 1] as StoredMessage) : null
              if (lastInSession && (!last || lastInSession.timestamp > last.timestamp)) {
                last = lastInSession
              }
            } catch {
              // ignore
            }
          }
        }
      }
    }

    if (!last) return null
    return {
      content: last.content?.slice(0, 50) ?? '',
      timestamp: last.timestamp,
    }
  } catch {
    return null
  }
}

/** 获取已确认有消息的会话 ID 列表（好友、端云互发、小助手等从 API 拉取的会话） */
export function getConversationsWithMessages(): string[] {
  try {
    const raw = localStorage.getItem(KEY_CONV_HAS_MSG)
    if (!raw) return []
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 标记该会话有消息（在打开会话并加载到消息时调用） */
export function markConversationHasMessages(conversationId: string): void {
  try {
    const set = new Set(getConversationsWithMessages())
    set.add(conversationId)
    localStorage.setItem(KEY_CONV_HAS_MSG, JSON.stringify([...set]))
  } catch {
    // ignore
  }
}

/** 清除指定 session 的本地消息（多 session 小助手删除会话时调用） */
export function removeMessagesForSession(conversationId: string, sessionId: string): void {
  try {
    localStorage.removeItem(`${PREFIX}${conversationId}_${sessionId}`)
    localStorage.removeItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
  } catch {
    // ignore
  }
}

/** 清除指定会话的本地聊天记录（含多 session 的 session 消息） */
export function clearMessagesForConversation(conversationId: string): void {
  try {
    localStorage.removeItem(PREFIX + conversationId)
    const toRemove: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(PREFIX + conversationId + '_')) toRemove.push(k)
    }
    toRemove.forEach((k) => localStorage.removeItem(k))
    const set = new Set(getConversationsWithMessages())
    set.delete(conversationId)
    localStorage.setItem(KEY_CONV_HAS_MSG, JSON.stringify([...set]))
    localStorage.removeItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
  } catch {
    // ignore
  }
}

const SESSIONS_PREFIX = 'chat_sessions_'

function estimateBucketLastTimestamp(raw: string): number {
  try {
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr) || arr.length === 0) return 0
    const last = arr[arr.length - 1] as StoredMessage
    if (typeof last?.timestamp === 'number') return last.timestamp
    let max = 0
    for (const it of arr) {
      const ts = typeof (it as StoredMessage)?.timestamp === 'number' ? (it as StoredMessage).timestamp : 0
      if (ts > max) max = ts
    }
    return max
  } catch {
    return 0
  }
}

/**
 * 为会话写入失败（配额超限）做兜底：只淘汰最旧的 chat_messages_* 键。
 * 不会触碰 chat_sessions_*、配置类 key。
 */
export function evictOldestMessageBucketsForQuota(maxBuckets = 3): { removedKeys: string[] } {
  const metas: Array<{ key: string; lastTs: number; size: number }> = []
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (!key?.startsWith(PREFIX)) continue
      const raw = localStorage.getItem(key) || ''
      metas.push({
        key,
        lastTs: estimateBucketLastTimestamp(raw),
        size: raw.length,
      })
    }
  } catch {
    return { removedKeys: [] }
  }
  metas.sort((a, b) => {
    if (a.lastTs !== b.lastTs) return a.lastTs - b.lastTs // 最旧优先
    return b.size - a.size // 同时刻优先删更大块
  })
  const removed: string[] = []
  const limit = Math.max(1, maxBuckets)
  for (const m of metas.slice(0, limit)) {
    try {
      localStorage.removeItem(m.key)
      removed.push(m.key)
    } catch {
      // ignore single key failure
    }
  }
  if (removed.length > 0) {
    try {
      localStorage.removeItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
    } catch {
      // ignore
    }
  }
  return { removedKeys: removed }
}

/** 清空 PC 端所有本地聊天记录（仅 localStorage，不涉及服务端，含多 session 的 sessions） */
export function clearAllChatMessages(): void {
  try {
    const keys: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith(PREFIX) || k?.startsWith(SESSIONS_PREFIX)) keys.push(k)
    }
    keys.forEach((k) => localStorage.removeItem(k))
    localStorage.removeItem(KEY_CONV_HAS_MSG)
    localStorage.removeItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
    resetBaselineTimestamp()
  } catch {
    // ignore
  }
}

/** 扫描本地 chat_messages_* 的最大消息时间戳，用于收件箱增量同步的 since（与服务端 created_at 毫秒对齐） */
export function getMaxStoredMessageTimestamp(): number {
  try {
    const cached = localStorage.getItem(KEY_MAX_STORED_MESSAGE_TIMESTAMP)
    if (cached) {
      const ts = parseInt(cached, 10)
      if (Number.isFinite(ts) && ts > 0) return ts
    }
  } catch {
    // ignore
  }

  let max = 0
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (!k?.startsWith(PREFIX)) continue
      const raw = localStorage.getItem(k)
      if (!raw) continue
      try {
        const arr = JSON.parse(raw) as unknown
        if (!Array.isArray(arr)) continue
        for (const m of arr) {
          const t = typeof (m as StoredMessage).timestamp === 'number' ? (m as StoredMessage).timestamp : 0
          if (t > max) max = t
        }
      } catch {
        // ignore
      }
    }
  } catch {
    // ignore
  }
  if (max > 0) {
    updateMaxStoredMessageTimestamp(max)
  }
  // 当本地已清空时，避免返回 0 触发服务端“全量最近消息”回灌侧栏预览。
  // 兜底使用基准时间戳（清空时会重置为当前时间）。
  return max > 0 ? max : getBaselineTimestamp()
}

function mergeInboxPair(incoming: StoredMessage, existing: StoredMessage | undefined, conversationId: string): StoredMessage {
  if (!existing) return incoming
  const isFriendOrGroup =
    conversationId.startsWith('friend_') || conversationId.startsWith('group_')
  if (isFriendOrGroup && incoming.type === 'user' && existing.type === 'user') {
    return mergeUserMessageKeepImage(
      incoming as ChatMessageWithMedia,
      existing as ChatMessageWithMedia
    ) as StoredMessage
  }
  return incoming
}

/** 将收件箱同步的一批消息合并写入 localStorage（群聊写 canonical 与裸 id 双 key） */
export function mergeInboxMessagesIntoStorage(conversationId: string, incoming: StoredMessage[]): StoredMessage[] {
  if (incoming.length === 0) return []
  const isGroup = conversationId.startsWith('group_')
  const existing = isGroup ? loadMessagesForGroup(conversationId) : loadMessages(conversationId)
  const byId = new Map(existing.map((m) => [m.id, m]))
  for (const m of incoming) {
    const ex = byId.get(m.id)
    byId.set(m.id, mergeInboxPair(m, ex, conversationId))
  }
  const merged = [...byId.values()].sort((a, b) => a.timestamp - b.timestamp)
  if (isGroup) {
    const canonical = toGroupCanonicalId(conversationId)
    saveMessages(canonical, merged)
    const raw = canonical.replace(/^group_/, '')
    if (raw) saveMessages(raw, merged)
  } else {
    saveMessages(conversationId, merged)
  }
  return merged
}
