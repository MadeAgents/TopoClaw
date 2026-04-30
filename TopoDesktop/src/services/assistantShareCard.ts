// Copyright 2025 OPPO
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

export const ASSISTANT_SHARE_CARD_PREFIX = 'assistant_share_card:'

export interface AssistantShareCardPayload {
  id?: string
  name: string
  intro?: string
  avatar?: string
  likesCount?: number
  baseUrl: string
  capabilities: string[]
  multiSessionEnabled?: boolean
  displayId?: string
  creatorImei?: string
  creatorAvatar?: string
}

function encodeUtf8Base64(input: string): string {
  const bytes = new TextEncoder().encode(input)
  let binary = ''
  bytes.forEach((b) => {
    binary += String.fromCharCode(b)
  })
  return btoa(binary)
}

function decodeUtf8Base64(input: string): string {
  const binary = atob(input)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return new TextDecoder().decode(bytes)
}

export function buildAssistantShareCardContent(payload: AssistantShareCardPayload): string {
  const cleaned: AssistantShareCardPayload = {
    ...payload,
    name: (payload.name || '小助手').trim() || '小助手',
    baseUrl: (payload.baseUrl || '').trim(),
    likesCount: Number.isFinite(payload.likesCount) ? Math.max(0, Math.floor(Number(payload.likesCount))) : undefined,
    capabilities: (payload.capabilities || []).map((x) => String(x).trim()).filter(Boolean),
  }
  return `${ASSISTANT_SHARE_CARD_PREFIX}${encodeUtf8Base64(JSON.stringify(cleaned))}`
}

export function parseAssistantShareCardContent(content: string): AssistantShareCardPayload | null {
  const raw = String(content || '').trim()
  if (!raw.startsWith(ASSISTANT_SHARE_CARD_PREFIX)) return null
  try {
    const encoded = raw.slice(ASSISTANT_SHARE_CARD_PREFIX.length).trim()
    if (!encoded) return null
    const parsed = JSON.parse(decodeUtf8Base64(encoded)) as AssistantShareCardPayload
    const name = String(parsed?.name || '').trim()
    const baseUrl = String(parsed?.baseUrl || '').trim()
    if (!name || !baseUrl) return null
    return {
      ...parsed,
      name,
      baseUrl,
      likesCount: Number.isFinite(parsed.likesCount) ? Math.max(0, Math.floor(Number(parsed.likesCount))) : undefined,
      capabilities: Array.isArray(parsed.capabilities)
        ? parsed.capabilities.map((x) => String(x).trim()).filter(Boolean)
        : [],
    }
  } catch {
    return null
  }
}

export function toAssistantSharePreview(content: string): string | null {
  const card = parseAssistantShareCardContent(content)
  if (!card) return null
  return `[助手分享] ${card.name}`
}
