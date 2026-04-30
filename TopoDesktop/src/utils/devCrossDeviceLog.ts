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
 * 跨设备 customer_service 日志：默认始终输出到控制台，便于正式包排查。
 * 过滤：仅忽略高频 ping/pong。不需要 localStorage。
 * 若需静音：`localStorage.setItem('coloros_quiet_cross_device','1')` 后刷新。
 */

const MAX_PREVIEW = 56
const POLL_ROW_CAP = 8

const QUIET_KEY = 'coloros_quiet_cross_device'

/** 关闭所有 `[CrossDevice]` / 可选静音其它 IO 日志时复用（当前仅 CrossDevice 使用） */
export function isVerboseIoMuted(): boolean {
  try {
    if (typeof localStorage === 'undefined') return false
    const v = (localStorage.getItem(QUIET_KEY) ?? '').trim().toLowerCase()
    return v === '1' || v === 'true' || v === 'yes' || v === 'on'
  } catch {
    return false
  }
}

function isQuiet(): boolean {
  return isVerboseIoMuted()
}

export function previewForDev(text: unknown): string {
  if (text == null) return '(empty)'
  const s = String(text).replace(/\s+/g, ' ').trim()
  if (!s) return '(empty)'
  return s.length <= MAX_PREVIEW ? s : `${s.slice(0, MAX_PREVIEW)}...`
}

function shortId(id: string): string {
  if (!id) return '-'
  return id.length > 14 ? `${id.slice(0, 14)}...` : id
}

/** WebSocket 下行（customer-service） */
export function logCrossDeviceWsInbound(data: Record<string, unknown>): void {
  if (isQuiet()) return
  const typ = String(data.type ?? '').toLowerCase()
  if (typ === 'ping' || typ === 'pong') return
  const preview = previewForDev(
    typeof data.content === 'string' && data.content.length > 0
      ? data.content
      : typeof data.query === 'string'
        ? data.query
        : ''
  )
  console.log('[CrossDevice] ws ←', data.type ?? '?', {
    conv: data.conversation_id ?? '-',
    group: data.groupId ?? '-',
    sender: data.sender ?? '-',
    preview,
  })
}

/** WebSocket 上行（customer-service） */
export function logCrossDeviceWsOutbound(msg: Record<string, unknown>): void {
  if (isQuiet()) return
  const typ = String(msg.type ?? '').toLowerCase()
  if (typ === 'ping' || typ === 'pong') return
  const preview = previewForDev(
    typeof msg.content === 'string' && msg.content.length > 0
      ? msg.content
      : typeof msg.query === 'string'
        ? msg.query
        : ''
  )
  console.log('[CrossDevice] ws →', msg.type ?? '?', {
    conv: msg.conversation_id ?? '-',
    group: msg.groupId ?? msg.group_id ?? '-',
    preview,
  })
}

/** GET /api/messages 等：仅在有增量时打印，避免空轮询刷屏 */
export function logCrossDevicePollInbound(
  conversationId: string,
  messages: Array<{ id?: string; sender?: string | null; content?: string | null }>
): void {
  if (isQuiet() || messages.length === 0) return
  const n = messages.length
  console.log('[CrossDevice] poll ←', conversationId, 'n=', n)
  const slice = messages.slice(0, POLL_ROW_CAP)
  for (const m of slice) {
    console.log('[CrossDevice] poll   ·', {
      id: shortId(m.id ?? ''),
      sender: m.sender ?? '-',
      preview: previewForDev(m.content),
    })
  }
  if (n > POLL_ROW_CAP) {
    console.log('[CrossDevice] poll   · ...', n - POLL_ROW_CAP, 'more')
  }
}
