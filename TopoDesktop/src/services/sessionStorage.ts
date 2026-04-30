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

/**
 * 多 session 聊天会话存储
 * 用于支持多 session 的自定义小助手，session 仅影响 WebSocket（thread_id）
 * 当提供 baseUrl 时使用 baseUrl 作 key，解决 assistant id 因云端同步而变化导致 session 丢失
 */
import { getCustomAssistants } from './customAssistants'
import { evictOldestMessageBucketsForQuota } from './messageStorage'

const PREFIX = 'chat_sessions_'
const ACTIVE_PREFIX = 'chat_active_session_'

export interface ChatSession {
  id: string
  title: string
  createdAt: number
}

const MAX_SESSION_COUNT = 300
const MAX_SESSION_TITLE_LEN = 120

function normalizeSessionsForStorage(sessions: ChatSession[], maxCount = MAX_SESSION_COUNT): ChatSession[] {
  const byId = new Map<string, ChatSession>()
  for (const raw of sessions || []) {
    const id = String(raw?.id || '').trim()
    if (!id) continue
    const createdAtRaw = Number(raw?.createdAt)
    const createdAt = Number.isFinite(createdAtRaw) && createdAtRaw > 0 ? createdAtRaw : Date.now()
    const titleRaw = String(raw?.title || '').trim()
    const title = (titleRaw || '新对话').slice(0, MAX_SESSION_TITLE_LEN)
    const next: ChatSession = { id, title, createdAt }
    const prev = byId.get(id)
    if (!prev || next.createdAt > prev.createdAt) {
      byId.set(id, next)
    }
  }
  const sorted = Array.from(byId.values()).sort((a, b) => b.createdAt - a.createdAt)
  return sorted.slice(0, Math.max(1, maxCount))
}

function normalizeBaseUrl(url: string): string {
  return (url || '').trim().replace(/\/+$/, '') || ''
}

function getKey(conversationId: string, baseUrl?: string): string {
  if (baseUrl && conversationId) {
    const norm = normalizeBaseUrl(baseUrl)
    if (norm) return `${PREFIX}by_url_${norm}_${conversationId}`
  }
  return PREFIX + conversationId
}

function getActiveKey(conversationId: string, baseUrl?: string): string {
  if (baseUrl && conversationId) {
    const norm = normalizeBaseUrl(baseUrl)
    if (norm) return `${ACTIVE_PREFIX}by_url_${norm}_${conversationId}`
  }
  return `${ACTIVE_PREFIX}${conversationId}`
}

export function loadSessions(conversationId: string, baseUrl?: string, legacyIds?: string[]): ChatSession[] {
  try {
    const key = getKey(conversationId, baseUrl)
    let raw = localStorage.getItem(key)
    console.log('[Session] loadSessions', { conversationId, baseUrl, key, found: !!raw, rawLen: raw?.length })
    if (!raw && baseUrl) {
      // 迁移：旧 key 为 by_url_${norm}（无 conversation_id），尝试迁移到新 key
      const norm = normalizeBaseUrl(baseUrl)
      if (norm) {
        const oldKey = `${PREFIX}by_url_${norm}`
        const oldRaw = localStorage.getItem(oldKey)
        if (oldRaw) {
          try {
            const arr = JSON.parse(oldRaw)
            const list = Array.isArray(arr) ? arr : []
            if (list.length > 0) {
              console.log('[Session] loadSessions migrated from old by_url key', { oldKey, count: list.length })
              saveSessions(conversationId, list, baseUrl)
              return list
            }
          } catch {
            // ignore
          }
        }
      }
      // 迁移：旧数据可能存在于 conversation_id key 下；assistant id 云端同步后可能变化，尝试当前 id 及同一 baseUrl 的其他 id
      const resolved =
        legacyIds?.length
          ? legacyIds
          : getCustomAssistants()
              .filter((a) => normalizeBaseUrl(a.baseUrl) === normalizeBaseUrl(baseUrl))
              .map((a) => a.id)
      const idsToTry = [...new Set([conversationId, ...resolved])]
      console.log('[Session] loadSessions migration', { idsToTry, assistantsCount: getCustomAssistants().length })
      for (const id of idsToTry) {
        const legacyKey = getKey(id, undefined)
        raw = localStorage.getItem(legacyKey)
        if (raw) {
          try {
            const arr = JSON.parse(raw)
            const list = Array.isArray(arr) ? arr : []
            if (list.length > 0) {
              console.log('[Session] loadSessions migrated from', { legacyKey, count: list.length })
              saveSessions(conversationId, list, baseUrl)
              return list
            }
          } catch {
            // ignore
          }
        }
      }
      console.log('[Session] loadSessions migration found nothing')
      return []
    }
    if (!raw) return []
    const arr = JSON.parse(raw)
    let list = Array.isArray(arr) ? arr : []
    // 防护：by_url 有数据时仍检查 legacy key，避免曾写入 legacy 的 session 被遗忘
    if (baseUrl) {
      const resolved =
        legacyIds?.length
          ? legacyIds
          : getCustomAssistants()
              .filter((a) => normalizeBaseUrl(a.baseUrl) === normalizeBaseUrl(baseUrl))
              .map((a) => a.id)
      const idsToTry = [...new Set([conversationId, ...resolved])]
      const originalCount = list.length
      for (const id of idsToTry) {
        const legacyKey = getKey(id, undefined)
        if (legacyKey === key) continue
        const legRaw = localStorage.getItem(legacyKey)
        if (!legRaw) continue
        try {
          const legArr = JSON.parse(legRaw)
          const legacyList = Array.isArray(legArr) ? legArr : []
          if (legacyList.length > 0) {
            const byId = new Map<string, ChatSession>()
            for (const s of [...list, ...legacyList]) {
              const cur = byId.get(s.id)
              if (!cur || s.createdAt > cur.createdAt || (s.createdAt === cur.createdAt && s.title && !cur.title)) {
                byId.set(s.id, s)
              }
            }
            list = Array.from(byId.values()).sort((a, b) => b.createdAt - a.createdAt)
          }
        } catch {
          // ignore
        }
      }
      if (list.length > originalCount) {
        console.log('[Session] loadSessions merged from legacy', { mergedCount: list.length, originalCount })
        saveSessions(conversationId, list, baseUrl)
      }
    }
    console.log('[Session] loadSessions result', { key, count: list.length })
    return list
  } catch (e) {
    console.warn('[Session] loadSessions error', e)
    return []
  }
}

export function saveSessions(conversationId: string, sessions: ChatSession[], baseUrl?: string): void {
  const key = getKey(conversationId, baseUrl)
  const normalizedSessions = normalizeSessionsForStorage(sessions)
  const payload = JSON.stringify(normalizedSessions)
  try {
    localStorage.setItem(key, payload)
    console.log('[Session] saveSessions', { conversationId, baseUrl, key, count: normalizedSessions.length })
  } catch (e) {
    const msg = String((e as { message?: string })?.message || e || '')
    const isQuota = msg.toLowerCase().includes('quotaexceeded')
    if (!isQuota) {
      console.warn('[Session] saveSessions error', e)
      return
    }
    // 先清理旧消息桶，再按更小会话快照分级重试，避免 session 保存完全失效
    const { removedKeys } = evictOldestMessageBucketsForQuota(20)
    console.warn('[Session] saveSessions quota exceeded, start degraded retries', {
      conversationId,
      key,
      removed: removedKeys.length,
    })

    const retryCaps = [200, 120, 80, 40, 20, 10, 3, 1]
    for (const cap of retryCaps) {
      try {
        const degraded = normalizeSessionsForStorage(normalizedSessions, cap)
        localStorage.setItem(key, JSON.stringify(degraded))
        console.log('[Session] saveSessions retry success', {
          conversationId,
          baseUrl,
          key,
          cap,
          count: degraded.length,
          removed: removedKeys.length,
        })
        return
      } catch (retryErr) {
        evictOldestMessageBucketsForQuota(8)
        if (cap === retryCaps[retryCaps.length - 1]) {
          console.warn('[Session] saveSessions retry failed after degradation', retryErr)
        }
      }
    }
    // 即使 session 列表写不进去，也尽量保住“当前活跃 session”这条最小路由信息
    if (normalizedSessions.length > 0) {
      setActiveSessionLocal(conversationId, normalizedSessions[0].id, baseUrl)
    }
  }
}

export function addSession(conversationId: string, session: ChatSession, baseUrl?: string): void {
  const list = loadSessions(conversationId, baseUrl)
  if (list.some((s) => s.id === session.id)) {
    console.log('[Session] addSession skip dup', { conversationId, sessionId: session.id })
    return
  }
  list.unshift(session)
  saveSessions(conversationId, list, baseUrl)
  console.log('[Session] addSession', { conversationId, baseUrl, sessionId: session.id })
}

export function updateSessionTitle(
  conversationId: string,
  sessionId: string,
  title: string,
  baseUrl?: string
): void {
  const list = loadSessions(conversationId, baseUrl)
  const idx = list.findIndex((s) => s.id === sessionId)
  if (idx >= 0) {
    list[idx] = { ...list[idx], title }
    saveSessions(conversationId, list, baseUrl)
  }
}

export function removeSession(conversationId: string, sessionId: string, baseUrl?: string): void {
  const list = loadSessions(conversationId, baseUrl).filter((s) => s.id !== sessionId)
  saveSessions(conversationId, list, baseUrl)
}

/** 记录当前活跃 session（用于未携带 session_id 的入站消息路由兜底） */
export function setActiveSessionLocal(conversationId: string, sessionId: string | null, baseUrl?: string): void {
  try {
    const key = getActiveKey(conversationId, baseUrl)
    const sid = (sessionId || '').trim()
    if (!sid) {
      localStorage.removeItem(key)
      return
    }
    localStorage.setItem(key, sid)
  } catch {
    // ignore
  }
}

/** 获取本地记忆的活跃 session（用于未携带 session_id 的入站消息路由兜底） */
export function getActiveSessionLocal(conversationId: string, baseUrl?: string): string | null {
  try {
    const byUrlKey = getActiveKey(conversationId, baseUrl)
    const byUrl = (localStorage.getItem(byUrlKey) || '').trim()
    if (byUrl) return byUrl
    const legacy = (localStorage.getItem(getActiveKey(conversationId)) || '').trim()
    return legacy || null
  } catch {
    return null
  }
}
