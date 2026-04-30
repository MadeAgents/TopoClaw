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
 * 长连接 WebSocket 聊天 Hook
 * 进入聊天页建立连接并 register，离开时断开
 */
import { useEffect, useRef, useState, useCallback } from 'react'
import { getChatWebSocketUrl } from '../services/chatWebSocket'
import { runComputerUseLoop } from '../services/computerUseLoop'
import { getDeviceId, getImei } from '../services/storage'
import { showInstallOverlay, hideInstallOverlay } from '../utils/installOverlay'
import { showToolGuardConfirmModal, TOOL_GUARD_DENIED_BUBBLE } from '../utils/toolGuardConfirm'
import type { Skill } from '../services/api'

type ConnectionStatus = 'disconnected' | 'connecting' | 'connected'

interface UseChatWebSocketOptions {
  baseUrl: string
  threadId: string
  enabled: boolean
  onAssistantPush?: (content: string) => void
}

interface SendChatResult {
  fullText: string
  needExecutionFired: boolean
}

export function useChatWebSocket({
  baseUrl,
  threadId,
  enabled,
  onAssistantPush,
}: UseChatWebSocketOptions) {
  const HEARTBEAT_INTERVAL_MS = 30_000
  const PONG_TIMEOUT_MS = 10_000

  const [status, setStatus] = useState<ConnectionStatus>('disconnected')
  const wsRef = useRef<WebSocket | null>(null)
  const onAssistantPushRef = useRef(onAssistantPush)
  onAssistantPushRef.current = onAssistantPush
  const heartbeatIdRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const pongTimeoutIdRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingResolveRef = useRef<{
    resolve: (r: SendChatResult) => void
    reject: (e: Error) => void
    fullText: string
    needExecutionFired: boolean
  } | null>(null)

  const clearHeartbeat = useCallback(() => {
    if (heartbeatIdRef.current) {
      clearInterval(heartbeatIdRef.current)
      heartbeatIdRef.current = null
    }
    if (pongTimeoutIdRef.current) {
      clearTimeout(pongTimeoutIdRef.current)
      pongTimeoutIdRef.current = null
    }
  }, [])

  const disconnect = useCallback(() => {
    clearHeartbeat()
    const ws = wsRef.current
    if (ws) {
      try {
        ws.close()
      } catch {}
      wsRef.current = null
    }
    setStatus('disconnected')
    const pending = pendingResolveRef.current
    if (pending) {
      pending.reject(new Error('连接已断开'))
      pendingResolveRef.current = null
    }
  }, [])

  const sendChat = useCallback(
    async (
      message: string,
      images: string[],
      callbacks: {
        onDelta: (d: string) => void
        onReasoning?: (reasoning: string) => void
        onToolCall?: (name: string) => void
        onSkillGenerated?: (skill: Skill) => void
        onNeedExecution?: (s: string) => void
      }
    ): Promise<SendChatResult> => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        throw new Error('WebSocket 未连接')
      }
      return new Promise((resolve, reject) => {
        let allowAllForCurrentTask = false
        const fullText = ''
        const needExecutionFired = false
        pendingResolveRef.current = {
          resolve,
          reject,
          fullText,
          needExecutionFired,
        }

        const handleMsg = async (ev: MessageEvent) => {
          const data = JSON.parse(ev.data as string) as {
            type?: string
            thread_id?: string
            confirmation_id?: string
            content?: string
            name?: string
            skill?: Skill
            need_execution?: boolean
            chat_summary?: string
            error?: string
            response?: string
          }
          const pending = pendingResolveRef.current
          if (!pending) return
          if (data.error) {
            pending.reject(new Error(data.error))
            pendingResolveRef.current = null
            ws.removeEventListener('message', handleMsg)
            return
          }
          switch (data.type) {
            case 'delta':
              if (data.content) {
                pending.fullText += data.content
                callbacks.onDelta(data.content)
              }
              break
            case 'assistant_reasoning':
              if (data.content && callbacks.onReasoning) callbacks.onReasoning(data.content)
              break
            case 'tool_call':
              if (data.name && callbacks.onToolCall) callbacks.onToolCall(data.name)
              break
            case 'tool_guard_prompt': {
              if (!data.confirmation_id) break
              let allowOnce = allowAllForCurrentTask
              if (!allowAllForCurrentTask) {
                const decision = await showToolGuardConfirmModal(data)
                if (decision === 'allow_for_task') {
                  allowAllForCurrentTask = true
                }
                allowOnce = decision !== 'deny'
              }
              ws.send(
                JSON.stringify({
                  type: 'user_confirmed',
                  confirmation_id: data.confirmation_id,
                  content: allowOnce ? 'temporary_allow' : 'deny',
                })
              )
              if (!allowOnce) {
                ws.send(
                  JSON.stringify({
                    type: 'stop',
                    thread_id: data.thread_id || threadId,
                  })
                )
                pending.fullText = TOOL_GUARD_DENIED_BUBBLE
                callbacks.onDelta(TOOL_GUARD_DENIED_BUBBLE)
                pending.resolve({
                  fullText: pending.fullText,
                  needExecutionFired: pending.needExecutionFired,
                })
                pendingResolveRef.current = null
                ws.removeEventListener('message', handleMsg)
              }
              break
            }
            case 'skill_generated':
              if (data.skill && callbacks.onSkillGenerated) callbacks.onSkillGenerated(data.skill)
              break
            case 'need_execution':
              if (data.need_execution && callbacks.onNeedExecution) {
                pending.needExecutionFired = true
                callbacks.onNeedExecution(data.chat_summary ?? '')
              }
              break
            case 'done':
              if (data.need_execution && callbacks.onNeedExecution) {
                pending.needExecutionFired = true
                callbacks.onNeedExecution(data.chat_summary ?? '')
              }
              if (data.response) pending.fullText = data.response
              pending.resolve({
                fullText: pending.fullText,
                needExecutionFired: pending.needExecutionFired,
              })
              pendingResolveRef.current = null
              ws.removeEventListener('message', handleMsg)
              break
          }
        }
        ws.addEventListener('message', handleMsg)
        ws.send(
          JSON.stringify({
            type: 'chat',
            thread_id: threadId,
            message: message || (images?.length ? '[图片]' : ''),
            images: images?.filter((b) => b && b.length > 100) ?? undefined,
          })
        )
      })
    },
    [threadId]
  )

  useEffect(() => {
    if (!enabled || !baseUrl || !threadId) {
      disconnect()
      return
    }
    const wsUrl = getChatWebSocketUrl(baseUrl)
    setStatus('connecting')
    const ws = new WebSocket(wsUrl)
    wsRef.current = ws

    ws.onopen = () => {
      setStatus('connected') // 连接已建立即可发送，不等待 registered
      const deviceId = getDeviceId()
      const imei = getImei()
      ws.send(
        JSON.stringify({
          type: 'register',
          thread_id: threadId,
          device_id: deviceId,
          device_type: 'pc',
          supports_code_execute: true,
          supports_computer_use: true,
          imei: imei ?? undefined,
        })
      )
      // 心跳保活：每 30 秒发 ping，10 秒内未收到 pong 则断开
      clearHeartbeat()
      const sendPing = () => {
        const w = wsRef.current
        if (!w || w.readyState !== WebSocket.OPEN) return
        if (pongTimeoutIdRef.current) {
          clearTimeout(pongTimeoutIdRef.current)
          pongTimeoutIdRef.current = null
        }
        w.send(JSON.stringify({ type: 'ping' }))
        pongTimeoutIdRef.current = setTimeout(() => {
          pongTimeoutIdRef.current = null
          disconnect()
        }, PONG_TIMEOUT_MS)
      }
      heartbeatIdRef.current = setInterval(sendPing, HEARTBEAT_INTERVAL_MS)
    }

    ws.onmessage = async (ev) => {
      try {
        const data = JSON.parse(ev.data as string) as {
          type?: string
          content?: string
          request_id?: string
          code?: string
          query?: string
          chat_summary?: string
        }
        if (data.type === 'registered') {
          return
        }
        if (data.type === 'pong') {
          if (pongTimeoutIdRef.current) {
            clearTimeout(pongTimeoutIdRef.current)
            pongTimeoutIdRef.current = null
          }
          return
        }
        if (data.type === 'assistant_push' && data.content) {
          onAssistantPushRef.current?.(data.content)
          return
        }
        if (data.type === 'computer_use_execute_request' && data.request_id && data.query != null) {
          const result: { type: string; request_id: string; success: boolean; content?: string; error?: string } = {
            type: 'computer_use_execute_result',
            request_id: data.request_id,
            success: false,
          }
          try {
            const r = await runComputerUseLoop(
              baseUrl,
              data.request_id,
              data.query,
              data.chat_summary
            )
            result.success = r.success
            if (r.content != null) result.content = r.content
            if (r.error != null) result.error = r.error
          } catch (e) {
            result.error = String(e)
          }
          if (wsRef.current?.readyState === WebSocket.OPEN) {
            wsRef.current.send(JSON.stringify(result))
          }
          return
        }
        if (data.type === 'code_execute_request' && data.request_id && data.code != null) {
          const codeExec = typeof window !== 'undefined' ? (window as { codeExec?: { run: (code: string) => Promise<{ success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string }>; installPackage?: (name: string) => Promise<{ success: boolean; error?: string; stderr?: string }> } }).codeExec : undefined
          const result: { type: string; request_id: string; success: boolean; stdout?: string; stderr?: string; error?: string } = {
            type: 'code_execute_result',
            request_id: data.request_id,
            success: false,
          }
          if (codeExec?.run) {
            try {
              let r = await codeExec.run(data.code)
              if (!r.success && r.missingPackage && codeExec.installPackage && typeof window !== 'undefined') {
                const ok = window.confirm(`代码执行需要安装 ${r.missingPackage} 包，是否允许安装？（需要网络）`)
                if (ok) {
                  try {
                    showInstallOverlay(r.missingPackage)
                    const inst = await codeExec.installPackage(r.missingPackage)
                    if (inst.success) r = await codeExec.run(data.code)
                    else r = { success: false, error: `安装失败: ${inst.error || inst.stderr || '未知错误'}` }
                  } finally {
                    hideInstallOverlay()
                  }
                }
              }
              result.success = r.success ?? false
              if (r.stdout != null) result.stdout = r.stdout
              if (r.stderr != null) result.stderr = r.stderr
              if (r.error != null) result.error = r.error
            } catch (e) {
              result.error = String(e)
            }
          } else {
            result.error = '暂不支持（需在 Electron 环境中运行）'
          }
          if (wsRef.current?.readyState === WebSocket.OPEN) {
            wsRef.current.send(JSON.stringify(result))
          }
        }
      } catch {}
    }

    ws.onerror = () => {
      setStatus('disconnected')
    }

    ws.onclose = () => {
      wsRef.current = null
      setStatus('disconnected')
    }

    return () => {
      disconnect()
    }
  }, [enabled, baseUrl, threadId, disconnect])

  return { status, sendChat, disconnect }
}
