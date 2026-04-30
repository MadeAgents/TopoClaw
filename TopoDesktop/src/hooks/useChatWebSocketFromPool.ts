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
 * 从连接池获取指定 baseUrl + thread 的 WebSocket 能力
 * 方案三：按 baseUrl 建连，threadId 作为业务参数；支持 subscribe_thread 订阅多 session
 */
import { useEffect, useState, useCallback, useRef } from 'react'
import {
  ensureConnection,
  getConnection,
  getStatus,
  subscribePush,
  unsubscribePush,
  subscribeStatus,
  unsubscribeStatus,
  type ConnectionStatus,
  type SendChatResult,
} from '../services/chatWebSocketPool'
import type { Skill } from '../services/api'

export function useChatWebSocketFromPool(
  baseUrl: string,
  threadId: string,
  threadIdsToSubscribe: string[],
  enabled: boolean,
  onAssistantPush?: (threadId: string, content: string) => void
) {
  const [status, setStatus] = useState<ConnectionStatus>(() =>
    enabled && baseUrl ? getStatus(baseUrl) : 'disconnected'
  )

  const statusCb = useCallback((key: string, s: ConnectionStatus) => {
    if (key === baseUrl || (baseUrl && key === baseUrl.replace(/\/+$/, ''))) setStatus(s)
  }, [baseUrl])

  const pushCb = useCallback(
    (content: string) => {
      onAssistantPush?.(threadId, content)
    },
    [onAssistantPush, threadId]
  )

  const subscribedRef = useRef<Set<string>>(new Set())
  const pushCallbacksRef = useRef<Map<string, (c: string) => void>>(new Map())

  useEffect(() => {
    if (!enabled || !baseUrl || !threadId) return
    ensureConnection(baseUrl)
    setStatus(getStatus(baseUrl))
    subscribeStatus(statusCb)
    subscribePush(threadId, pushCb)

    for (const tid of threadIdsToSubscribe) {
      if (tid && tid !== threadId) {
        let cb = pushCallbacksRef.current.get(tid)
        if (!cb) {
          cb = (content: string) => onAssistantPush?.(tid, content)
          pushCallbacksRef.current.set(tid, cb)
        }
        subscribePush(tid, cb)
      }
    }

    const conn = getConnection(baseUrl)
    if (conn) {
      for (const tid of threadIdsToSubscribe) {
        if (tid && !subscribedRef.current.has(tid)) {
          conn.subscribeThread(tid)
          subscribedRef.current.add(tid)
        }
      }
    }

    return () => {
      unsubscribeStatus(statusCb)
      unsubscribePush(threadId, pushCb)
      for (const tid of threadIdsToSubscribe) {
        if (tid !== threadId) {
          const cb = pushCallbacksRef.current.get(tid)
          if (cb) unsubscribePush(tid, cb)
        }
      }
      const c = getConnection(baseUrl)
      if (c) {
        for (const tid of subscribedRef.current) {
          c.unsubscribeThread(tid)
        }
        subscribedRef.current.clear()
      }
    }
  }, [enabled, baseUrl, threadId, threadIdsToSubscribe.join(','), statusCb, pushCb])

  useEffect(() => {
    if (!enabled || !baseUrl || threadIdsToSubscribe.length === 0) return
    const conn = getConnection(baseUrl)
    if (!conn) return
    const current = new Set(threadIdsToSubscribe)
    for (const tid of subscribedRef.current) {
      if (!current.has(tid)) {
        conn.unsubscribeThread(tid)
        subscribedRef.current.delete(tid)
      }
    }
    for (const tid of threadIdsToSubscribe) {
      if (tid && !subscribedRef.current.has(tid)) {
        conn.subscribeThread(tid)
        subscribedRef.current.add(tid)
      }
    }
  }, [enabled, baseUrl, threadIdsToSubscribe.join(',')])

  const sendChat = useCallback(
    async (
      message: string,
      images: string[],
      focusSkills: string[] | undefined,
      callbacks: {
        onDelta: (d: string) => void
        onReasoning?: (reasoning: string) => void
        onMedia?: (media: { fileBase64: string; fileName?: string; content?: string; messageType?: 'image' | 'file' }) => void
        onToolCall?: (name: string) => void
        onSkillGenerated?: (skill: Skill) => void
        onNeedExecution?: (s: string) => void
      },
      agentId?: string
    ): Promise<SendChatResult> => {
      const conn = getConnection(baseUrl)
      if (!conn) throw new Error('WebSocket 未连接')
      return conn.sendChat(threadId, message, images, focusSkills, callbacks, agentId)
    },
    [baseUrl, threadId]
  )

  const sendStop = useCallback(() => {
    const conn = getConnection(baseUrl)
    if (conn?.sendStop && threadId) {
      conn.sendStop(threadId)
    }
  }, [baseUrl, threadId])

  return {
    status,
    sendChat,
    sendStop,
  }
}
