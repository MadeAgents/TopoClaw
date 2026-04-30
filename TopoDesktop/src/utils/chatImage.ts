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

/** 与 ChatDetail 中用户消息展示相关的最小字段（避免循环引用） */
export interface ChatMessageWithMedia {
  id: string
  content: string
  type?: string
  messageType?: 'text' | 'file'
  fileBase64?: string
  fileName?: string
  timestamp?: number
  sender?: string
}

/** 裸 base64 或已是 data URL，生成可供 <img src> 使用的地址 */
export function toChatImageSrc(fileBase64: string, fileName?: string): string {
  const raw = (fileBase64 || '').trim()
  if (!raw) return ''
  if (raw.startsWith('data:image/') || raw.startsWith('data:application/')) return raw
  const lower = (fileName || '').toLowerCase()
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return `data:image/jpeg;base64,${raw}`
  if (lower.endsWith('.webp')) return `data:image/webp;base64,${raw}`
  if (lower.endsWith('.gif')) return `data:image/gif;base64,${raw}`
  if (raw.startsWith('/9j')) return `data:image/jpeg;base64,${raw}`
  if (raw.startsWith('iVBOR')) return `data:image/png;base64,${raw}`
  if (raw.startsWith('UklGR')) return `data:image/webp;base64,${raw}`
  if (raw.startsWith('R0lGOD')) return `data:image/gif;base64,${raw}`
  return `data:image/png;base64,${raw}`
}

function mediaPayloadLen(m: ChatMessageWithMedia): number {
  const b = m.fileBase64
  if (!b || typeof b !== 'string') return 0
  return b.trim().length
}

/** 合并同 id 消息：优先保留带有效图片数据的一方（避免拉历史覆盖 WS 已收图） */
export function mergeUserMessageKeepImage<T extends ChatMessageWithMedia>(api: T, local: T | undefined): T {
  if (!local || local.id !== api.id) return api
  const apiLen = mediaPayloadLen(api)
  const localLen = mediaPayloadLen(local)
  if (localLen > 0 && apiLen === 0 && (local.messageType === 'file' || !!local.fileBase64)) {
    return {
      ...api,
      messageType: 'file',
      fileBase64: local.fileBase64,
      fileName: local.fileName || api.fileName || '图片.png',
      content: (api.content && api.content.trim()) ? api.content : (local.content || api.content),
    }
  }
  if (localLen > apiLen && localLen > 0) {
    return {
      ...api,
      messageType: 'file',
      fileBase64: local.fileBase64,
      fileName: local.fileName || api.fileName || '图片.png',
    }
  }
  return api
}
