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
 * 聊天 WebSocket 客户端
 * 将 baseUrl (https://...) 转为 wsUrl (wss://...ws)
 */
import type { Skill } from './api'
import { previewForDev } from '@/utils/devCrossDeviceLog'
import { showToolGuardConfirmModal, TOOL_GUARD_DENIED_BUBBLE } from '@/utils/toolGuardConfirm'
import { getImei } from './storage'

/** 校验 baseUrl 是否为有效的 http/https 地址，用于避免构造无效的 WebSocket URL */
export function isValidBaseUrl(url: string): boolean {
  const s = (url ?? '').trim()
  return s.startsWith('https://') || s.startsWith('http://')
}

/** 将 HTTP baseUrl 转为 WebSocket URL */
export function getChatWebSocketUrl(baseUrl: string): string {
  const base = (baseUrl ?? '').trim().replace(/\/+$/, '')
  if (!isValidBaseUrl(baseUrl)) {
    throw new Error(
      `baseUrl 必须是有效的 http:// 或 https:// 地址，当前值: ${JSON.stringify(baseUrl)}`
    )
  }
  if (base.startsWith('https://')) {
    return base.replace(/^https:\/\//, 'wss://') + '/ws'
  }
  return base.replace(/^http:\/\//, 'ws://') + '/ws'
}

export interface ChatWebSocketCallbacks {
  onDelta: (delta: string) => void
  onReasoning?: (reasoning: string) => void
  onMedia?: (media: { fileBase64: string; fileName?: string; content?: string; messageType?: 'image' | 'file' }) => void
  onToolCall?: (toolName: string) => void
  onSkillGenerated?: (skill: Skill) => void
  onNeedExecution?: (chatSummary: string) => void
}

export interface ChatWebSocketResult {
  fullText: string
  needExecutionFired: boolean
}

export interface CreateAgentParams {
  agent_id: string
  system_prompt?: string
  skills_include?: string[]
  skills_exclude?: string[]
}

export interface CreateAgentResult {
  ok: boolean
  error?: string
}

export interface DeleteAgentResult {
  ok: boolean
  error?: string
}

/**
 * 通过 WebSocket 发送聊天消息，流式接收回复
 */
export async function sendChatViaWebSocket(
  baseUrl: string,
  params: { thread_id: string; message: string; images?: string[]; focus_skills?: string[]; agent_id?: string },
  callbacks: ChatWebSocketCallbacks,
  signal?: AbortSignal
): Promise<ChatWebSocketResult> {
  const wsUrl = getChatWebSocketUrl(baseUrl)
  console.log('[ChatWS] 连接', wsUrl, {
    thread_id: params.thread_id,
    msgPreview: previewForDev(params.message || (params.images?.length ? '[图片]' : '')),
    images: params.images?.length ?? 0,
  })
  const ws = new WebSocket(wsUrl)
  const { onDelta, onReasoning, onMedia, onToolCall, onSkillGenerated, onNeedExecution } = callbacks

  let fullText = ''
  let needExecutionFired = false
  let allowAllForCurrentTask = false
  let resolve: (r: ChatWebSocketResult) => void
  let reject: (e: Error) => void
  const promise = new Promise<ChatWebSocketResult>((res, rej) => {
    resolve = res
    reject = rej
  })

  const cleanup = () => {
    try {
      ws.close()
    } catch {
      /* ignore */
    }
  }

  if (signal) {
    signal.addEventListener(
      'abort',
      () => {
        try {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'stop', thread_id: params.thread_id }))
          }
        } catch { /* ignore */ }
        cleanup()
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true }
    )
  }

  ws.onopen = () => {
    const currentImei = (getImei() || '').trim() || undefined
    const payload = {
      type: 'chat',
      thread_id: params.thread_id,
      message: params.message || (params.images?.length ? '[图片]' : ''),
      images: params.images?.filter((b) => b && b.length > 100) ?? undefined,
      focus_skills: params.focus_skills?.map((s) => s.trim()).filter(Boolean) ?? undefined,
      agent_id: params.agent_id?.trim() || undefined,
      imei: currentImei,
    }
    console.log('[ChatWS] → send chat', { thread_id: payload.thread_id, msgPreview: previewForDev(payload.message) })
    ws.send(JSON.stringify(payload))
  }

  ws.onmessage = async (ev) => {
    try {
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
        file_base64?: string
        file_name?: string
        message_type?: string
      }
      if (data.error) {
        console.error('[ChatWS] ← error 帧', previewForDev(data.error))
        reject(new Error(data.error))
        cleanup()
        return
      }
      switch (data.type) {
        case 'delta':
          if (data.content) {
            // delta 频率很高，默认不逐条打印，避免拖慢桌面端渲染
            fullText += data.content
            onDelta(data.content)
          }
          break
        case 'assistant_reasoning':
          if (data.content && onReasoning) onReasoning(data.content)
          break
        case 'tool_call':
          console.log('[ChatWS] ← tool_call', data.name ?? '')
          if (data.name && onToolCall) onToolCall(data.name)
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
                thread_id: data.thread_id || params.thread_id,
              })
            )
            fullText = TOOL_GUARD_DENIED_BUBBLE
            onDelta(TOOL_GUARD_DENIED_BUBBLE)
            resolve({ fullText, needExecutionFired })
            cleanup()
          }
          break
        }
        case 'skill_generated':
          console.log('[ChatWS] ← skill_generated', data.skill?.title ?? '')
          if (data.skill && onSkillGenerated) onSkillGenerated(data.skill)
          break
        case 'need_execution':
          if (data.need_execution === true && onNeedExecution) {
            needExecutionFired = true
            onNeedExecution(data.chat_summary ?? '')
          }
          break
        case 'done':
          console.log('[ChatWS] ← done', { need_execution: data.need_execution, preview: previewForDev(data.response ?? fullText) })
          if (data.need_execution === true && onNeedExecution) {
            needExecutionFired = true
            onNeedExecution(data.chat_summary ?? '')
          }
          if (data.response) fullText = data.response
          resolve({ fullText, needExecutionFired })
          cleanup()
          break
        case 'assistant_media': {
          const b64 = typeof data.file_base64 === 'string' ? data.file_base64.trim() : ''
          if (b64 && onMedia) {
            onMedia({
              fileBase64: b64,
              fileName: data.file_name || '图片.png',
              content: data.content || '',
              messageType: data.message_type === 'file' ? 'file' : 'image',
            })
          }
          break
        }
        case 'pong':
          break
        default:
          if (data.type) console.log('[ChatWS] ←', data.type, previewForDev(JSON.stringify(data).slice(0, 200)))
          break
      }
    } catch (e) {
      if (!(e instanceof SyntaxError)) {
        reject(e instanceof Error ? e : new Error(String(e)))
        cleanup()
      }
    }
  }

  ws.onerror = () => {
    console.error('[ChatWS] onerror', wsUrl)
    reject(new Error('WebSocket 连接错误'))
    cleanup()
  }

  ws.onclose = (ev) => {
    console.warn('[ChatWS] onclose', { code: ev.code, reason: ev.reason || '', wasClean: ev.wasClean, wsUrl })
    if (!ev.wasClean && fullText === '') {
      reject(new Error(`连接关闭: ${ev.code} ${ev.reason || '未知'}`))
    }
  }

  return promise
}

/**
 * 通过 WebSocket 创建服务端 runtime agent（单端口多助手）
 */
export async function createAgentViaWebSocket(
  baseUrl: string,
  params: CreateAgentParams,
  signal?: AbortSignal
): Promise<CreateAgentResult> {
  const wsUrl = getChatWebSocketUrl(baseUrl)
  const ws = new WebSocket(wsUrl)
  let settled = false

  let resolve: (r: CreateAgentResult) => void
  let reject: (e: Error) => void
  const promise = new Promise<CreateAgentResult>((res, rej) => {
    resolve = res
    reject = rej
  })

  const cleanup = () => {
    try {
      ws.close()
    } catch {
      /* ignore */
    }
  }

  if (signal) {
    signal.addEventListener(
      'abort',
      () => {
        if (settled) return
        settled = true
        cleanup()
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true }
    )
  }

  ws.onopen = () => {
    const payload = {
      type: 'create_agent',
      agent_id: params.agent_id.trim(),
      system_prompt: params.system_prompt?.trim() || undefined,
      skills_include: params.skills_include?.filter((s) => s.trim()) ?? [],
      skills_exclude: params.skills_exclude?.filter((s) => s.trim()) ?? [],
    }
    ws.send(JSON.stringify(payload))
  }

  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data as string) as { type?: string; ok?: boolean; error?: string }
      if (data.type !== 'agent_created') return
      settled = true
      if (data.ok) {
        resolve({ ok: true })
      } else {
        resolve({ ok: false, error: data.error || '创建助手失败' })
      }
      cleanup()
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
      cleanup()
    }
  }

  ws.onerror = () => {
    if (settled) return
    settled = true
    reject(new Error('WebSocket 连接错误'))
    cleanup()
  }

  ws.onclose = (ev) => {
    if (!settled && !ev.wasClean) {
      settled = true
      reject(new Error(`连接关闭: ${ev.code} ${ev.reason || '未知'}`))
    }
  }

  return promise
}

export async function deleteAgentViaWebSocket(
  baseUrl: string,
  agentId: string,
  signal?: AbortSignal
): Promise<DeleteAgentResult> {
  const wsUrl = getChatWebSocketUrl(baseUrl)
  const ws = new WebSocket(wsUrl)
  let settled = false

  let resolve: (r: DeleteAgentResult) => void
  let reject: (e: Error) => void
  const promise = new Promise<DeleteAgentResult>((res, rej) => {
    resolve = res
    reject = rej
  })

  const cleanup = () => {
    try {
      ws.close()
    } catch {
      /* ignore */
    }
  }

  if (signal) {
    signal.addEventListener(
      'abort',
      () => {
        if (settled) return
        settled = true
        cleanup()
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true }
    )
  }

  ws.onopen = () => {
    ws.send(
      JSON.stringify({
        type: 'delete_agent',
        agent_id: agentId.trim(),
      })
    )
  }

  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data as string) as { type?: string; ok?: boolean; error?: string }
      if (data.type !== 'agent_deleted') return
      settled = true
      if (data.ok) resolve({ ok: true })
      else resolve({ ok: false, error: data.error || '删除助手失败' })
      cleanup()
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
      cleanup()
    }
  }

  ws.onerror = () => {
    if (settled) return
    settled = true
    reject(new Error('WebSocket 连接错误'))
    cleanup()
  }

  ws.onclose = (ev) => {
    if (!settled && !ev.wasClean) {
      settled = true
      reject(new Error(`连接关闭: ${ev.code} ${ev.reason || '未知'}`))
    }
  }

  return promise
}

export async function upsertAgentViaWebSocket(
  baseUrl: string,
  params: CreateAgentParams,
  signal?: AbortSignal
): Promise<CreateAgentResult> {
  const created = await createAgentViaWebSocket(baseUrl, params, signal)
  if (created.ok) return created
  const msg = (created.error || '').toLowerCase()
  if (!msg.includes('already exists')) return created
  const deleted = await deleteAgentViaWebSocket(baseUrl, params.agent_id, signal)
  if (!deleted.ok) return { ok: false, error: deleted.error || created.error }
  return createAgentViaWebSocket(baseUrl, params, signal)
}
