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

import type { UnifiedMessage } from './api'
import { mergeInboxMessagesIntoStorage, type StoredMessage } from './messageStorage'

export function mapInboxUnifiedToStored(
  m: UnifiedMessage,
  conversationId: string,
  myImei: string
): StoredMessage {
  const ts = m.created_at ? new Date(m.created_at).getTime() : Date.now()
  if (conversationId === 'assistant') {
    return {
      id: m.id,
      sender: m.sender || '小助手',
      content: m.content || '',
      type: m.type === 'user' ? 'user' : m.type === 'system' ? 'system' : 'assistant',
      timestamp: ts,
      ...(m.file_base64
        ? { messageType: 'file' as const, fileBase64: m.file_base64, fileName: m.file_name || '图片.png' }
        : {}),
    }
  }
  if (conversationId.startsWith('friend_')) {
    const senderImei = typeof m.sender_imei === 'string' ? m.sender_imei.trim() : ''
    const me = myImei.trim()
    const isMe = !!me && !!senderImei && senderImei === me
    const rawB64 = m.file_base64 || m.imageBase64
    const b64 = typeof rawB64 === 'string' && rawB64.trim().length > 0 ? rawB64.trim() : undefined
    const isImg = m.message_type === 'image' || m.message_type === 'file' || !!b64
    const text = (m.content || '').trim()
    return {
      id: m.id,
      sender: isMe ? '我' : '好友',
      content: isImg ? text || '[图片]' : (m.content || ''),
      type: 'user',
      timestamp: ts,
      ...(b64 ? { messageType: 'file' as const, fileBase64: b64, fileName: m.file_name || '图片.png' } : {}),
    }
  }
  if (conversationId.startsWith('group_')) {
    const senderImei = typeof m.sender_imei === 'string' ? m.sender_imei.trim() : ''
    const me = myImei.trim()
    const isMe = !!me && !!senderImei && senderImei === me
    const sender = m.sender
    const rawGb64 = m.file_base64 || m.imageBase64
    const gb64 = typeof rawGb64 === 'string' && rawGb64.trim().length > 0 ? rawGb64.trim() : undefined
    const isAssistantMsg =
      (typeof m.type === 'string' && m.type === 'assistant') ||
      sender === '自动执行小助手'
    return {
      id: m.id,
      sender: isMe ? '我' : (sender ?? '群成员'),
      content: m.content || '',
      type: isAssistantMsg ? 'assistant' : 'user',
      timestamp: ts,
      ...(gb64
        ? { messageType: 'file' as const, fileBase64: gb64, fileName: m.file_name || '图片.png' }
        : {}),
    }
  }
  return {
    id: m.id,
    sender: m.sender || '用户',
    content: m.content || '',
    type: 'user',
    timestamp: ts,
  }
}

/** 将 /api/inbox/sync 返回的会话批量写入本地并回调更新侧栏预览（不增未读） */
export function applyInboxSyncPayload(
  myImei: string,
  conversations: Record<string, UnifiedMessage[]>,
  onConvUpdated: (id: string, preview: string, ts: number, isFromMe: boolean) => void
): void {
  for (const [cid, msgs] of Object.entries(conversations)) {
    if (!msgs?.length) continue
    const stored = msgs.map((m) => mapInboxUnifiedToStored(m, cid, myImei))
    mergeInboxMessagesIntoStorage(cid, stored)
    const last = stored[stored.length - 1]!
    const preview = (last.content || '').slice(0, 80)
    const isFromMe = last.sender === '我'
    onConvUpdated(cid, preview, last.timestamp, isFromMe)
  }
}
