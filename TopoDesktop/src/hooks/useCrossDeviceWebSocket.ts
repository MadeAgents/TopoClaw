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

import { useEffect, useRef, useCallback } from 'react'
import { getCustomerServiceUrl } from '../services/storage'
import { logCrossDeviceWsInbound, logCrossDeviceWsOutbound } from '@/utils/devCrossDeviceLog'
import { perfLog, perfLogEnd } from '../utils/perfLog'
import { sendChatViaWebSocket } from '../services/chatWebSocket'
import { getDefaultBuiltinUrl } from '../services/builtinAssistantConfig'

export type CrossDeviceMessageHandler = (msg: {
  type: string
  protocol?: string
  request_id?: string
  message_id?: string
  from_device?: string
  sender?: string
  content?: string
  message_type?: string
  file_base64?: string
  file_name?: string
  imageBase64?: string
  timestamp?: string
  uuid?: string
  /** mobile_execute_pc_command 字段 */
  query?: string
  assistant_base_url?: string
  conversation_id?: string
  session_id?: string
  thread_id?: string
  chat_summary?: string
  /** mobile_execute_pc_result 字段 */
  success?: boolean
  error?: string
  /** mobile_execute_pc_thinking 字段 */
  thinking_content?: string
  payload?: Record<string, unknown>
}) => void

export function useCrossDeviceWebSocket(imei: string | null, onMessage: CrossDeviceMessageHandler) {
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage
  const mobileExecAbortRef = useRef<AbortController | null>(null)

  const connect = useCallback(() => {
    if (!imei) return
    const url = getWsBaseUrl().replace(/^http/, 'ws')
    const wsUrl = `${url}ws/customer-service/${imei}?device=pc`
    try {
      const connectStart = Date.now()
      perfLog('CrossDevice WebSocket 开始建连', { wsUrl })
      const ws = new WebSocket(wsUrl)
      ws.onopen = () => {
        perfLogEnd('CrossDevice WebSocket 建连成功', connectStart, { wsUrl })
        console.log('[CrossDevice] WebSocket 已连接 (PC)')
      }
      ws.onmessage = (e) => {
        try {
          const data = JSON.parse(e.data) as Parameters<CrossDeviceMessageHandler>[0]
          logCrossDeviceWsInbound(data as Record<string, unknown>)
          if (data.type === 'mobile_execute_pc_command') {
            const isGroupMgrCmd = typeof data.thread_id === 'string' && data.thread_id.includes('_group_')
            if (!isGroupMgrCmd) {
              onMessageRef.current(data)
            }
            const baseUrl = data.assistant_base_url ?? ''
            const requestId = data.message_id ?? `mobile_pc_${Date.now()}`
            const query = data.query ?? ''
            const chatSummary = data.chat_summary ?? ''
            const rawImage = data.file_base64 || data.imageBase64 || ''
            const imageBase64 = typeof rawImage === 'string' ? rawImage.trim() : ''
            if (baseUrl && query) {
              mobileExecAbortRef.current?.abort()
              const abortCtrl = new AbortController()
              mobileExecAbortRef.current = abortCtrl
              const convId = (data.conversation_id || '').trim()
              const threadCtx = resolveThreadContext({
                imei,
                conversationId: convId,
                sessionId: data.session_id,
                threadId: data.thread_id,
              })
              ;(async () => {
                const isGroupManagerRequest = typeof data.thread_id === 'string' && data.thread_id.includes('_group_')
                const sendResult = (payload: {
                  success: boolean
                  content?: string
                  error?: string
                  file_base64?: string
                  file_name?: string
                  message_type?: string
                }) => {
                  mobileExecAbortRef.current = null
                  const result = {
                    type: 'mobile_execute_pc_result',
                    message_id: data.message_id,
                    conversation_id: data.conversation_id,
                    session_id: threadCtx.sessionId,
                    thread_id: threadCtx.threadId,
                    success: payload.success,
                    content: payload.content ?? '',
                    error: payload.error ?? '',
                    ...(payload.file_base64 ? { file_base64: payload.file_base64 } : {}),
                    ...(payload.file_name ? { file_name: payload.file_name } : {}),
                    ...(payload.message_type ? { message_type: payload.message_type } : {}),
                    timestamp: new Date().toISOString(),
                  }
                  const wsCurrent = wsRef.current
                  if (wsCurrent?.readyState === WebSocket.OPEN) {
                    logCrossDeviceWsOutbound(result as Record<string, unknown>)
                    wsCurrent.send(JSON.stringify(result))
                  }
                  if (!isGroupManagerRequest) {
                    onMessageRef.current(result)
                  }
                }

                try {
                  const threadId = threadCtx.threadId
                  const agentId = convId.startsWith('custom_') ? convId : undefined
                  let needExecSummary = ''
                  let thinkingContent = ''
                  let assistantMediaBase64 = ''
                  let assistantMediaFileName = ''
                  let assistantMediaType: 'image' | 'file' = 'image'
                  let lastThinkingForwardAt = 0
                  const chatResult = await sendChatViaWebSocket(
                    baseUrl,
                    {
                      thread_id: threadId,
                      message: query,
                      images: imageBase64 ? [imageBase64] : [],
                      agent_id: agentId,
                    },
                    {
                      onDelta: (delta) => {
                        if (abortCtrl.signal.aborted) return
                        thinkingContent += delta
                        if (!isGroupManagerRequest) {
                          const thinkingPayload = {
                            type: 'mobile_execute_pc_thinking',
                            message_id: data.message_id,
                            conversation_id: data.conversation_id,
                            session_id: threadCtx.sessionId,
                            thread_id: threadId,
                            thinking_content: thinkingContent,
                            timestamp: new Date().toISOString(),
                          }
                          onMessageRef.current(thinkingPayload)
                          const now = Date.now()
                          if (now - lastThinkingForwardAt >= 250) {
                            const wsCurrent = wsRef.current
                            if (wsCurrent?.readyState === WebSocket.OPEN) {
                              logCrossDeviceWsOutbound(thinkingPayload as Record<string, unknown>)
                              wsCurrent.send(JSON.stringify(thinkingPayload))
                              lastThinkingForwardAt = now
                            }
                          }
                        }
                      },
                      onNeedExecution: (s) => {
                        needExecSummary = s || ''
                      },
                      onMedia: (media) => {
                        const b64 = typeof media.fileBase64 === 'string' ? media.fileBase64.trim() : ''
                        if (!b64) return
                        assistantMediaBase64 = b64
                        assistantMediaFileName = media.fileName || '图片.png'
                        assistantMediaType = media.messageType === 'file' ? 'file' : 'image'
                      },
                    },
                    abortCtrl.signal
                  )

                  if (abortCtrl.signal.aborted) {
                    sendResult({ success: false, error: '任务已被用户停止' })
                    return
                  }

                  if (!chatResult.needExecutionFired) {
                    sendResult({
                      success: true,
                      content: chatResult.fullText || (assistantMediaBase64 ? '[图片]' : '任务已完成'),
                      ...(assistantMediaBase64
                        ? {
                            file_base64: assistantMediaBase64,
                            file_name: assistantMediaFileName,
                            message_type: assistantMediaType,
                          }
                        : {}),
                    })
                    return
                  }

                  const { runComputerUseLoop } = await import('../services/computerUseLoop')
                  const r = await runComputerUseLoop(
                    baseUrl,
                    requestId,
                    query,
                    needExecSummary || chatSummary,
                    abortCtrl.signal
                  )
                  sendResult({ success: r.success, content: r.content, error: r.error })
                } catch (err) {
                  if (abortCtrl.signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) {
                    sendResult({ success: false, error: '任务已被用户停止' })
                  } else {
                    sendResult({ success: false, error: String(err) })
                  }
                }
              })()
            }
            return
          }
          if (data.type === 'assistant_stop_task') {
            if (mobileExecAbortRef.current) {
              console.log('[CrossDevice] 收到 assistant_stop_task，中止当前执行')
              mobileExecAbortRef.current.abort()
              mobileExecAbortRef.current = null
            }
            onMessageRef.current(data)
            return
          }
          if (data.type === 'gui_step_request') {
            const stepRequestId = (data as Record<string, unknown>).step_request_id as string ?? ''
            const guiRequestId = (data as Record<string, unknown>).gui_request_id as string ?? ''
            const screenshot = (data as Record<string, unknown>).screenshot as string ?? ''
            const stepQuery = (data as Record<string, unknown>).query as string ?? ''
            const packageName = (data as Record<string, unknown>).package_name as string ?? ''
            const userResponse = (data as Record<string, unknown>).user_response as string ?? ''
            const taskId = guiRequestId || `gui_${Date.now()}`
            console.log('[CrossDevice] 收到 gui_step_request, step_request_id=', stepRequestId)
            ;(async () => {
              const sendStepResponse = (payload: {
                ok: boolean
                success: boolean
                chat_response?: Record<string, unknown> | null
                error?: string
              }) => {
                const result = {
                  type: 'gui_step_response',
                  step_request_id: stepRequestId,
                  gui_request_id: guiRequestId,
                  ok: payload.ok,
                  success: payload.success,
                  chat_response: payload.chat_response ?? null,
                  error: payload.error ?? '',
                  timestamp: new Date().toISOString(),
                }
                const wsCurrent = wsRef.current
                if (wsCurrent?.readyState === WebSocket.OPEN) {
                  logCrossDeviceWsOutbound(result as Record<string, unknown>)
                  wsCurrent.send(JSON.stringify(result))
                }
              }
              try {
                const baseUrl = (await getDefaultBuiltinUrl('topoclaw')).replace(/\/+$/, '')
                const uploadUrl = `${baseUrl}/upload`
                const formData = new FormData()
                formData.append('task_id', taskId)
                formData.append('query', stepQuery)
                if (screenshot) formData.append('screenshot', screenshot)
                if (packageName) formData.append('package_name', packageName)
                if (userResponse) formData.append('user_response', userResponse)
                const resp = await fetch(uploadUrl, {
                  method: 'POST',
                  body: formData,
                  signal: AbortSignal.timeout(120_000),
                })
                if (!resp.ok) {
                  sendStepResponse({ ok: false, success: false, error: `TopoClaw /upload 返回 ${resp.status}` })
                  return
                }
                const chatResponse = await resp.json()
                sendStepResponse({ ok: true, success: true, chat_response: chatResponse })
              } catch (err) {
                console.error('[CrossDevice] gui_step_request 处理失败:', err)
                sendStepResponse({ ok: false, success: false, error: String(err) })
              }
            })()
            return
          }
          if (
            data.type === 'friend_message_ack' ||
            data.type === 'friend_message_error' ||
            data.type === 'cross_device_message' ||
            data.type === 'execute_result' ||
            data.type === 'assistant_user_message' ||
            data.type === 'assistant_sync_message' ||
            data.type === 'assistant_thinking_sync' ||
            data.type === 'friend_sync_message' ||
            data.type === 'group_message' ||
            data.type === 'mobile_execute_pc_result' ||
            data.type === 'mobile_tool_ack' ||
            data.type === 'mobile_tool_event' ||
            data.type === 'mobile_tool_result' ||
            data.type === 'mobile_tool_manifest' ||
            data.type === 'clone_context_request' ||
            data.type === 'custom_assistant_active_session'
          ) {
            onMessageRef.current(data)
          }
        } catch {
          // ignore
        }
      }
      ws.onclose = () => {
        wsRef.current = null
        reconnectRef.current = setTimeout(connect, 5000)
      }
      ws.onerror = () => {
        perfLogEnd('CrossDevice WebSocket 建连失败', connectStart, { wsUrl })
      }
      wsRef.current = ws
    } catch {
      reconnectRef.current = setTimeout(connect, 5000)
    }
  }, [imei])

  useEffect(() => {
    if (!imei) return
    connect()
    return () => {
      if (reconnectRef.current) clearTimeout(reconnectRef.current)
      if (wsRef.current) {
        wsRef.current.close()
        wsRef.current = null
      }
    }
  }, [imei, connect])

  const send = useCallback((msg: object): boolean => {
    const ws = wsRef.current
    if (ws?.readyState === WebSocket.OPEN) {
      logCrossDeviceWsOutbound(msg as Record<string, unknown>)
      ws.send(JSON.stringify(msg))
      return true
    }
    console.warn('[CrossDevice] ws → 未发送(socket 未连接)', (msg as { type?: string }).type)
    return false
  }, [])

  const cancelMobileExecution = useCallback(() => {
    if (mobileExecAbortRef.current) {
      console.log('[CrossDevice] cancelMobileExecution: 中止当前执行')
      mobileExecAbortRef.current.abort()
      mobileExecAbortRef.current = null
    }
  }, [])

  return { connect, send, cancelMobileExecution }
}

function buildThreadId(imei: string | null, conversationId: string): string {
  const normalizedImei = (imei || '').trim()
  const conv = (conversationId || '').trim()
  if (!normalizedImei) return conv || `mobile_pc_${Date.now()}`
  if (!conv) return `${normalizedImei}_assistant`
  if (conv.startsWith(`${normalizedImei}_`)) return conv
  return `${normalizedImei}_${conv}`
}

function extractSessionIdFromConversationId(conversationId: string): string | null {
  const conv = (conversationId || '').trim()
  if (!conv || !conv.includes('__')) return null
  const parts = conv.split('__', 2)
  const sid = (parts[1] || '').trim()
  return sid || null
}

function resolveThreadContext(params: {
  imei: string | null
  conversationId: string
  sessionId?: string
  threadId?: string
}): { threadId: string; sessionId?: string } {
  const tid = (params.threadId || '').trim()
  if (tid) {
    return { threadId: tid, sessionId: tid }
  }
  const sid = (params.sessionId || '').trim() || extractSessionIdFromConversationId(params.conversationId) || ''
  if (sid) {
    return { threadId: sid, sessionId: sid }
  }
  return { threadId: buildThreadId(params.imei, params.conversationId) }
}

function getWsBaseUrl(): string {
  const base = getCustomerServiceUrl()
  return base.endsWith('/') ? base : `${base}/`
}
