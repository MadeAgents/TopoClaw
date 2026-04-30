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
 * 聊天 WebSocket 连接池
 * 方案三：按 baseUrl 建连，单连接支持多 thread（session），thread_id 作为业务参数传递
 */
import { getChatWebSocketUrl, isValidBaseUrl } from './chatWebSocket'
import { getDeviceId, getImei } from './storage'
import { getChatAssistantBaseUrl, CHAT_ASSISTANT_BASE_URL } from './api'
import { getCustomAssistants, hasChat } from './customAssistants'
import { previewForDev } from '@/utils/devCrossDeviceLog'
import { perfLog, perfLogEnd } from '../utils/perfLog'
import { showInstallOverlay, hideInstallOverlay } from '../utils/installOverlay'
import { showToolGuardConfirmModal, TOOL_GUARD_DENIED_BUBBLE } from '../utils/toolGuardConfirm'
import type { Skill } from './api'

const HEARTBEAT_INTERVAL_MS = 30_000
const PONG_TIMEOUT_MS = 10_000
/** 建连超时：不可达地址 8 秒后放弃，避免长时间卡顿 */
const CONNECT_TIMEOUT_MS = 8_000

function normalizeBaseUrl(url: string): string {
  return (url ?? '').trim().replace(/\/+$/, '') || ''
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected'

export interface SendChatResult {
  fullText: string
  needExecutionFired: boolean
}

export interface SetLlmProviderPayload {
  api_key?: string
  api_base?: string
  model?: string
}

export interface SetLlmProviderResult {
  ok: boolean
  applied: boolean
  reason?: string
  patch_keys: string[]
  updated_agent_ids: string[]
  errors: Array<{ agent_id: string; error: string }>
  config_saved: boolean
}

export interface ConnectionEntry {
  baseUrl: string
  status: ConnectionStatus
  sendChat: (
    threadId: string,
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
  ) => Promise<SendChatResult>
  /** 发送停止消息，openclaw_guan 协议：{"type":"stop","thread_id":"会话ID"} */
  sendStop: (threadId: string) => void
  subscribeThread: (threadId: string) => void
  unsubscribeThread: (threadId: string) => void
}

type StatusListener = (key: string, status: ConnectionStatus) => void
type AnyAssistantPushListener = (payload: {
  threadId: string
  content: string
  baseUrlKey: string
}) => void

interface SingleConnState {
  ws: WebSocket
  baseUrl: string
  heartbeatId: ReturnType<typeof setInterval> | null
  pongTimeoutId: ReturnType<typeof setTimeout> | null
}

const connections = new Map<string, SingleConnState>()
const statusListeners = new Set<StatusListener>()
const pushListeners = new Map<string, Set<(content: string) => void>>()
const anyAssistantPushListeners = new Set<AnyAssistantPushListener>()
// 记录每个 baseUrl 希望订阅的 thread；用于连接建立后补发 subscribe_thread。
const desiredThreadSubscriptions = new Map<string, Set<string>>()
/** 收到 computer_use_execute_request 时调用，供 PC 执行后自动跳转到发起任务的 session */
export let onRemoteExecuteRequest: ((threadId: string, baseUrl: string) => void) | null = null
export function setOnRemoteExecuteRequest(cb: ((threadId: string, baseUrl: string) => void) | null) {
  onRemoteExecuteRequest = cb
}

function notifyStatus(baseUrl: string, status: ConnectionStatus) {
  statusListeners.forEach((cb) => cb(baseUrl, status))
}

function notifyPush(threadId: string, content: string) {
  pushListeners.get(threadId)?.forEach((cb) => cb(content))
}

function notifyAnyAssistantPush(payload: { threadId: string; content: string; baseUrlKey: string }) {
  anyAssistantPushListeners.forEach((cb) => cb(payload))
}

function clearHeartbeat(state: SingleConnState) {
  if (state.heartbeatId) {
    clearInterval(state.heartbeatId)
    state.heartbeatId = null
  }
  if (state.pongTimeoutId) {
    clearTimeout(state.pongTimeoutId)
    state.pongTimeoutId = null
  }
}

function createSingleConnection(baseUrl: string): SingleConnState | null {
  const deviceId = getDeviceId()
  const wsUrl = getChatWebSocketUrl(baseUrl)
  const key = normalizeBaseUrl(baseUrl)

  const connectStart = Date.now()
  perfLog('WebSocket 开始建连', { baseUrl: key })

  const state: SingleConnState = {
    ws: new WebSocket(wsUrl),
    baseUrl,
    heartbeatId: null,
    pongTimeoutId: null,
  }

  let connectTimeoutId: ReturnType<typeof setTimeout> | null = setTimeout(() => {
    connectTimeoutId = null
    const s = connections.get(key)
    if (s && s.ws.readyState !== WebSocket.OPEN) {
      perfLog('WebSocket 建连超时', { baseUrl: key, durationMs: Date.now() - connectStart })
      closeConnection(key)
    }
  }, CONNECT_TIMEOUT_MS)

  const clearConnectTimeout = () => {
    if (connectTimeoutId) {
      clearTimeout(connectTimeoutId)
      connectTimeoutId = null
    }
  }

  state.ws.onopen = () => {
    clearConnectTimeout()
    perfLogEnd('WebSocket 建连成功', connectStart, { baseUrl: key })
    notifyStatus(key, 'connected')
    console.log('[ChatWS][pool] 已连接', wsUrl, '| base', key)
    const imei = getImei()
    const reg = {
      type: 'register',
      device_id: deviceId,
      device_type: 'pc',
      supports_code_execute: true,
      supports_computer_use: true,
      base_url: baseUrl,
      imei: imei ?? undefined,
    }
    console.log('[ChatWS][pool] → register', { base_url: baseUrl, imei: imei ? `${String(imei).slice(0, 8)}...` : '(无)' })
    state.ws.send(JSON.stringify(reg))
    const desired = desiredThreadSubscriptions.get(key)
    if (desired && desired.size > 0) {
      for (const tid of desired) {
        if (!tid) continue
        console.log('[ChatWS][pool] → subscribe_thread (replay)', tid)
        state.ws.send(JSON.stringify({ type: 'subscribe_thread', thread_id: tid }))
      }
    }
    clearHeartbeat(state)
    const sendPing = () => {
      if (state.ws.readyState !== WebSocket.OPEN) return
      if (state.pongTimeoutId) {
        clearTimeout(state.pongTimeoutId)
        state.pongTimeoutId = null
      }
      state.ws.send(JSON.stringify({ type: 'ping' }))
      state.pongTimeoutId = setTimeout(() => {
        state.pongTimeoutId = null
        closeConnection(key)
      }, PONG_TIMEOUT_MS)
    }
    state.heartbeatId = setInterval(sendPing, HEARTBEAT_INTERVAL_MS)
  }

  state.ws.onmessage = async (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data as string) as {
        type?: string
        thread_id?: string
        content?: string
        name?: string
        skill?: Skill
        need_execution?: boolean
        chat_summary?: string
        error?: string
        response?: string
        request_id?: string
        code?: string
        query?: string
      }
      if (data.type === 'registered') return
      if (data.type === 'pong') {
        if (state.pongTimeoutId) {
          clearTimeout(state.pongTimeoutId)
          state.pongTimeoutId = null
        }
        return
      }
      if (data.type === 'assistant_push' && data.content) {
        console.log('[ChatWS][pool] ← assistant_push', {
          thread_id: data.thread_id ?? key,
          preview: previewForDev(data.content),
        })
        notifyAnyAssistantPush({
          threadId: data.thread_id ?? '',
          content: data.content,
          baseUrlKey: key,
        })
        const tid = data.thread_id
        if (tid) {
          notifyPush(tid, data.content)
        } else {
          notifyPush(key, data.content)
        }
        return
      }
      if (data.type === 'computer_use_execute_request' && data.request_id && data.query != null) {
        const sessionId = data.thread_id
        if (sessionId && onRemoteExecuteRequest) {
          onRemoteExecuteRequest(sessionId, state.baseUrl)
        }
        const { runComputerUseLoop } = await import('./computerUseLoop')
        const result: { type: string; request_id: string; success: boolean; content?: string; error?: string } = {
          type: 'computer_use_execute_result',
          request_id: data.request_id,
          success: false,
        }
        try {
          const r = await runComputerUseLoop(
            state.baseUrl,
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
        if (state.ws.readyState === WebSocket.OPEN) {
          state.ws.send(JSON.stringify(result))
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
        if (state.ws.readyState === WebSocket.OPEN) {
          state.ws.send(JSON.stringify(result))
        }
      }
    } catch {}
  }

  state.ws.onerror = () => {
    clearConnectTimeout()
    console.error('[ChatWS][pool] onerror', wsUrl, '| base', key)
    perfLogEnd('WebSocket 建连失败(error)', connectStart, { baseUrl: key })
    notifyStatus(key, 'disconnected')
  }

  state.ws.onclose = () => {
    clearConnectTimeout()
    console.warn('[ChatWS][pool] onclose', wsUrl, '| base', key)
    const wasOpen = state.ws.readyState === WebSocket.OPEN
    if (!wasOpen) perfLogEnd('WebSocket 建连失败(close)', connectStart, { baseUrl: key })
    clearHeartbeat(state)
    connections.delete(key)
    notifyStatus(key, 'disconnected')
  }

  return state
}

function closeConnection(key: string) {
  const state = connections.get(key)
  if (!state) return
  clearHeartbeat(state)
  try {
    state.ws.close()
  } catch {}
  connections.delete(key)
  notifyStatus(key, 'disconnected')
}

function getUniqueBaseUrls(): string[] {
  const imei = getImei()
  const seen = new Set<string>()
  const result: string[] = []

  // 云侧默认聊天地址仅在已登录（有 imei）时预连；未登录仍可预连自定义小助手（如本机 18790）
  if (imei) {
    let defaultBaseUrl = getChatAssistantBaseUrl()
    if (!isValidBaseUrl(defaultBaseUrl)) {
      if (defaultBaseUrl) {
        console.warn(
          '[ChatWebSocket] 聊天小助手地址无效，临时使用默认地址。当前配置:',
          JSON.stringify(defaultBaseUrl),
          '请在设置中修改为有效的 http:// 或 https:// 地址'
        )
      }
      defaultBaseUrl = CHAT_ASSISTANT_BASE_URL
    }
    if (defaultBaseUrl) {
      const k = normalizeBaseUrl(defaultBaseUrl)
      if (!seen.has(k)) {
        seen.add(k)
        result.push(defaultBaseUrl)
      }
    }
  }

  const custom = getCustomAssistants().filter((a) => hasChat(a) && a.baseUrl)
  for (const a of custom) {
    if (isValidBaseUrl(a.baseUrl)) {
      const k = normalizeBaseUrl(a.baseUrl)
      if (!seen.has(k)) {
        seen.add(k)
        result.push(a.baseUrl)
      }
    } else {
      console.warn('[ChatWebSocket] 跳过无效自定义小助手地址:', a.name, a.baseUrl)
    }
  }

  return result
}

/** 启动时连接默认小助手及所有自定义小助手，预建连以减少进入会话时的离线感 */
export function connectAll() {
  const baseUrls = getUniqueBaseUrls()
  if (baseUrls.length === 0) return

  for (const baseUrl of baseUrls) {
    const key = normalizeBaseUrl(baseUrl)
    if (connections.has(key)) continue
    notifyStatus(key, 'connecting')
    try {
      const state = createSingleConnection(baseUrl)
      if (state) connections.set(key, state)
    } catch (e) {
      console.error('[ChatWebSocket] 连接失败，跳过:', baseUrl, e)
      notifyStatus(key, 'disconnected')
    }
  }
}

/** 按需连接指定 baseUrl（用户切换会话时调用） */
export function ensureConnection(baseUrl: string): void {
  if (!baseUrl?.trim()) return
  if (!isValidBaseUrl(baseUrl)) return

  const key = normalizeBaseUrl(baseUrl)
  if (connections.has(key)) return

  notifyStatus(key, 'connecting')
  try {
    const state = createSingleConnection(baseUrl)
    if (state) connections.set(key, state)
  } catch (e) {
    console.error('[ChatWebSocket] 连接失败，跳过:', baseUrl, e)
    notifyStatus(key, 'disconnected')
  }
}

async function waitForConnectionOpen(baseUrl: string, timeoutMs = CONNECT_TIMEOUT_MS): Promise<SingleConnState> {
  ensureConnection(baseUrl)
  const key = normalizeBaseUrl(baseUrl)
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const state = connections.get(key)
    if (state?.ws.readyState === WebSocket.OPEN) return state
    await new Promise((resolve) => setTimeout(resolve, 120))
  }
  throw new Error('WebSocket 未连接')
}

export async function setLlmProviderViaPool(
  baseUrl: string,
  payload: SetLlmProviderPayload,
  timeoutMs = 10_000
): Promise<SetLlmProviderResult> {
  const key = normalizeBaseUrl(baseUrl)
  const state = await waitForConnectionOpen(baseUrl)
  return new Promise<SetLlmProviderResult>((resolve, reject) => {
    const timeoutId = setTimeout(() => {
      state.ws.removeEventListener('message', handleMsg)
      reject(new Error('热切换超时：未收到 set_llm_provider_result'))
    }, timeoutMs)
    const handleMsg = (ev: MessageEvent) => {
      try {
        const data = JSON.parse(ev.data as string) as {
          type?: string
          ok?: boolean
          applied?: boolean
          reason?: string
          patch_keys?: string[]
          updated_agent_ids?: string[]
          errors?: Array<{ agent_id?: string; error?: string }>
          config_saved?: boolean
        }
        if (data.type !== 'set_llm_provider_result') return
        clearTimeout(timeoutId)
        state.ws.removeEventListener('message', handleMsg)
        resolve({
          ok: data.ok === true,
          applied: data.applied === true,
          reason: typeof data.reason === 'string' ? data.reason : undefined,
          patch_keys: Array.isArray(data.patch_keys) ? data.patch_keys.map((x) => String(x)) : [],
          updated_agent_ids: Array.isArray(data.updated_agent_ids)
            ? data.updated_agent_ids.map((x) => String(x))
            : [],
          errors: Array.isArray(data.errors)
            ? data.errors.map((x) => ({ agent_id: String(x?.agent_id || ''), error: String(x?.error || '') }))
            : [],
          config_saved: data.config_saved === true,
        })
      } catch {
        // ignore non-JSON / non-result messages
      }
    }
    state.ws.addEventListener('message', handleMsg)
    try {
      state.ws.send(
        JSON.stringify({
          type: 'set_llm_provider',
          ...payload,
        })
      )
      console.log('[ChatWS][pool] → set_llm_provider', { base: key, payloadKeys: Object.keys(payload) })
    } catch (e) {
      clearTimeout(timeoutId)
      state.ws.removeEventListener('message', handleMsg)
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

export interface SetGuiProviderResult {
  ok: boolean
  applied: boolean
  reason?: string
  patch_keys: string[]
  updated_targets: string[]
  errors: Array<{ target: string; error: string }>
  config_saved: boolean
}

export interface BuiltinModelProfilesRuntimeResult {
  ok: boolean
  non_gui_profiles: string[]
  gui_profiles: string[]
  active_non_gui_model: string
  active_gui_model: string
}

export async function getBuiltinModelProfilesViaPool(
  baseUrl: string,
  options?: { agentId?: string },
  timeoutMs = 10_000
): Promise<BuiltinModelProfilesRuntimeResult> {
  const state = await waitForConnectionOpen(baseUrl)
  return new Promise<BuiltinModelProfilesRuntimeResult>((resolve, reject) => {
    const requestId = `builtin-model-profiles-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const timeoutId = setTimeout(() => {
      state.ws.removeEventListener('message', handleMsg)
      reject(new Error('获取运行时模型超时：未收到 builtin_model_profiles_result'))
    }, timeoutMs)
    const handleMsg = (ev: MessageEvent) => {
      try {
        const data = JSON.parse(ev.data as string) as {
          type?: string
          request_id?: string
          ok?: boolean
          non_gui_profiles?: string[]
          gui_profiles?: string[]
          active_non_gui_model?: string
          active_gui_model?: string
        }
        if (data.type !== 'builtin_model_profiles_result') return
        if ((data.request_id || '') !== requestId) return
        clearTimeout(timeoutId)
        state.ws.removeEventListener('message', handleMsg)
        resolve({
          ok: data.ok === true,
          non_gui_profiles: Array.isArray(data.non_gui_profiles) ? data.non_gui_profiles.map((x) => String(x)) : [],
          gui_profiles: Array.isArray(data.gui_profiles) ? data.gui_profiles.map((x) => String(x)) : [],
          active_non_gui_model: String(data.active_non_gui_model || ''),
          active_gui_model: String(data.active_gui_model || ''),
        })
      } catch {
        // ignore
      }
    }
    state.ws.addEventListener('message', handleMsg)
    try {
      state.ws.send(
        JSON.stringify({
          type: 'get_builtin_model_profiles',
          request_id: requestId,
          agent_id: options?.agentId?.trim() || undefined,
        })
      )
    } catch (e) {
      clearTimeout(timeoutId)
      state.ws.removeEventListener('message', handleMsg)
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

export async function setGuiProviderViaPool(
  baseUrl: string,
  payload: SetLlmProviderPayload,
  timeoutMs = 10_000
): Promise<SetGuiProviderResult> {
  const key = normalizeBaseUrl(baseUrl)
  const state = await waitForConnectionOpen(baseUrl)
  return new Promise<SetGuiProviderResult>((resolve, reject) => {
    const timeoutId = setTimeout(() => {
      state.ws.removeEventListener('message', handleMsg)
      reject(new Error('GUI 热切换超时：未收到 set_gui_provider_result'))
    }, timeoutMs)
    const handleMsg = (ev: MessageEvent) => {
      try {
        const data = JSON.parse(ev.data as string) as {
          type?: string
          ok?: boolean
          applied?: boolean
          reason?: string
          patch_keys?: string[]
          updated_targets?: string[]
          errors?: Array<{ target?: string; error?: string }>
          config_saved?: boolean
        }
        if (data.type !== 'set_gui_provider_result') return
        clearTimeout(timeoutId)
        state.ws.removeEventListener('message', handleMsg)
        resolve({
          ok: data.ok === true,
          applied: data.applied === true,
          reason: typeof data.reason === 'string' ? data.reason : undefined,
          patch_keys: Array.isArray(data.patch_keys) ? data.patch_keys.map((x) => String(x)) : [],
          updated_targets: Array.isArray(data.updated_targets)
            ? data.updated_targets.map((x) => String(x))
            : [],
          errors: Array.isArray(data.errors)
            ? data.errors.map((x) => ({ target: String(x?.target || ''), error: String(x?.error || '') }))
            : [],
          config_saved: data.config_saved === true,
        })
      } catch {
        // ignore non-JSON / non-result messages
      }
    }
    state.ws.addEventListener('message', handleMsg)
    try {
      state.ws.send(
        JSON.stringify({
          type: 'set_gui_provider',
          ...payload,
        })
      )
      console.log('[ChatWS][pool] → set_gui_provider', { base: key, payloadKeys: Object.keys(payload) })
    } catch (e) {
      clearTimeout(timeoutId)
      state.ws.removeEventListener('message', handleMsg)
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

export function disconnectAll() {
  for (const key of [...connections.keys()]) {
    closeConnection(key)
  }
}

export function getConnection(baseUrl: string): ConnectionEntry | null {
  const key = normalizeBaseUrl(baseUrl)
  const state = connections.get(key)
  if (!state) return null

  const sendChat = async (
    threadId: string,
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
    if (state.ws.readyState !== WebSocket.OPEN) {
      throw new Error('WebSocket 未连接')
    }
    const sendStart = Date.now()
    return new Promise<SendChatResult>((resolve, reject) => {
      let allowAllForCurrentTask = false
      const pending = { fullText: '', needExecutionFired: false }
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
          file_base64?: string
          file_name?: string
          message_type?: string
        }
        if (data.error) {
          console.error('[ChatWS][pool] ← error', previewForDev(data.error), '| thread', threadId)
          state.ws.removeEventListener('message', handleMsg)
          reject(new Error(data.error))
          return
        }
        switch (data.type) {
          case 'delta':
            if (data.content) {
              // delta 频率很高，默认不逐条打印，避免拖慢桌面端渲染
              pending.fullText += data.content
              callbacks.onDelta(data.content)
            }
            break
          case 'assistant_reasoning':
            if (data.content && callbacks.onReasoning) callbacks.onReasoning(data.content)
            break
          case 'tool_call':
            console.log('[ChatWS][pool] ← tool_call', data.name ?? '', '| thread', threadId)
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
            state.ws.send(
              JSON.stringify({
                type: 'user_confirmed',
                confirmation_id: data.confirmation_id,
                content: allowOnce ? 'temporary_allow' : 'deny',
              })
            )
            if (!allowOnce) {
              state.ws.send(
                JSON.stringify({
                  type: 'stop',
                  thread_id: data.thread_id || threadId,
                })
              )
              pending.fullText = TOOL_GUARD_DENIED_BUBBLE
              callbacks.onDelta(TOOL_GUARD_DENIED_BUBBLE)
              state.ws.removeEventListener('message', handleMsg)
              resolve({
                fullText: pending.fullText,
                needExecutionFired: pending.needExecutionFired,
              })
            }
            break
          }
          case 'skill_generated':
            console.log('[ChatWS][pool] ← skill_generated', data.skill?.title ?? '', '| thread', threadId)
            if (data.skill && callbacks.onSkillGenerated)
              callbacks.onSkillGenerated(data.skill)
            break
          case 'need_execution':
            console.log('[ChatWS][pool] ← need_execution | thread', threadId)
            if (data.need_execution && callbacks.onNeedExecution) {
              pending.needExecutionFired = true
              callbacks.onNeedExecution(data.chat_summary ?? '')
            }
            break
          case 'done':
            console.log('[ChatWS][pool] ← done', {
              thread: threadId,
              preview: previewForDev(data.response ?? pending.fullText),
            })
            if (data.need_execution && callbacks.onNeedExecution) {
              pending.needExecutionFired = true
              callbacks.onNeedExecution(data.chat_summary ?? '')
            }
            if (data.response) pending.fullText = data.response
            state.ws.removeEventListener('message', handleMsg)
            resolve({
              fullText: pending.fullText,
              needExecutionFired: pending.needExecutionFired,
            })
            break
          case 'stopped':
            console.log('[ChatWS][pool] ← stopped | thread', threadId)
            state.ws.removeEventListener('message', handleMsg)
            resolve({
              fullText: pending.fullText,
              needExecutionFired: pending.needExecutionFired,
            })
            break
          case 'assistant_media': {
            const b64 = typeof data.file_base64 === 'string' ? data.file_base64.trim() : ''
            if (b64 && callbacks.onMedia) {
              callbacks.onMedia({
                fileBase64: b64,
                fileName: data.file_name || '图片.png',
                content: data.content || '',
                messageType: data.message_type === 'file' ? 'file' : 'image',
              })
            }
            break
          }
        }
      }
      state.ws.addEventListener('message', handleMsg)
      const currentImei = (getImei() || '').trim() || undefined
      const chatPayload = {
        type: 'chat',
        thread_id: threadId,
        message: message || (images?.length ? '[图片]' : ''),
        images: images?.filter((b) => b && b.length > 100) ?? undefined,
        focus_skills: focusSkills?.map((s) => s.trim()).filter(Boolean) ?? undefined,
        agent_id: agentId?.trim() || undefined,
        imei: currentImei,
      }
      console.log('[ChatWS][pool] → chat', {
        base: key,
        thread_id: threadId,
        msgPreview: previewForDev(chatPayload.message),
        images: chatPayload.images?.length ?? 0,
      })
      state.ws.send(JSON.stringify(chatPayload))
    }).then(
      (r) => {
        perfLogEnd('WebSocket sendChat 完成', sendStart, { threadId, baseUrl: key })
        return r
      },
      (e) => {
        perfLogEnd('WebSocket sendChat 失败', sendStart, { threadId, baseUrl: key, error: String(e) })
        throw e
      }
    )
  }

  const sendStop = (stopThreadId: string) => {
    if (state.ws.readyState === WebSocket.OPEN && stopThreadId) {
      console.log('[ChatWS][pool] → stop', stopThreadId)
      state.ws.send(JSON.stringify({ type: 'stop', thread_id: stopThreadId }))
    }
  }

  const subscribeThread = (threadId: string) => {
    const tid = (threadId || '').trim()
    if (!tid) return
    let desired = desiredThreadSubscriptions.get(key)
    if (!desired) {
      desired = new Set<string>()
      desiredThreadSubscriptions.set(key, desired)
    }
    desired.add(tid)
    if (state.ws.readyState !== WebSocket.OPEN) return
    console.log('[ChatWS][pool] → subscribe_thread', tid)
    state.ws.send(JSON.stringify({ type: 'subscribe_thread', thread_id: tid }))
  }

  const unsubscribeThread = (threadId: string) => {
    const tid = (threadId || '').trim()
    if (!tid) return
    const desired = desiredThreadSubscriptions.get(key)
    desired?.delete(tid)
    if (desired && desired.size === 0) {
      desiredThreadSubscriptions.delete(key)
    }
    if (state.ws.readyState !== WebSocket.OPEN) return
    console.log('[ChatWS][pool] → unsubscribe_thread', tid)
    state.ws.send(JSON.stringify({ type: 'unsubscribe_thread', thread_id: tid }))
  }

  const status = state.ws.readyState === WebSocket.OPEN ? 'connected' : 'connecting'
  return {
    baseUrl: state.baseUrl,
    status,
    sendChat,
    sendStop,
    subscribeThread,
    unsubscribeThread,
  }
}

export function subscribePush(
  threadId: string,
  callback: (content: string) => void
) {
  let set = pushListeners.get(threadId)
  if (!set) {
    set = new Set()
    pushListeners.set(threadId, set)
  }
  set.add(callback)
}

export function unsubscribePush(
  threadId: string,
  callback: (content: string) => void
) {
  pushListeners.get(threadId)?.delete(callback)
}

export function subscribeStatus(listener: StatusListener) {
  statusListeners.add(listener)
}

export function unsubscribeStatus(listener: StatusListener) {
  statusListeners.delete(listener)
}

export function subscribeAnyAssistantPush(listener: AnyAssistantPushListener) {
  anyAssistantPushListeners.add(listener)
}

export function unsubscribeAnyAssistantPush(listener: AnyAssistantPushListener) {
  anyAssistantPushListeners.delete(listener)
}

export function getStatus(baseUrl: string): ConnectionStatus {
  const key = normalizeBaseUrl(baseUrl)
  const state = connections.get(key)
  if (!state) return 'disconnected'
  if (state.ws.readyState === WebSocket.OPEN) return 'connected'
  if (state.ws.readyState === WebSocket.CONNECTING) return 'connecting'
  return 'disconnected'
}
