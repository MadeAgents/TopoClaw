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

import type { Skill } from './api'

export const SKILL_SHARE_CARD_PREFIX = 'skill_share_card:'

export interface SkillShareCardPayload {
  id?: string
  title: string
  originalPurpose?: string
  steps?: string[]
  executionPlatform?: Skill['executionPlatform']
  source?: Skill['source']
  author?: string
  tags?: string[]
  packageBase64?: string
  packageFileName?: string
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

export function buildSkillShareCardContent(payload: SkillShareCardPayload): string {
  const cleaned: SkillShareCardPayload = {
    ...payload,
    title: (payload.title || '').trim(),
    originalPurpose: (payload.originalPurpose || '').trim() || undefined,
    steps: (payload.steps || []).map((x) => String(x || '').trim()).filter(Boolean),
    tags: (payload.tags || []).map((x) => String(x || '').trim()).filter(Boolean),
    author: (payload.author || '').trim() || undefined,
    packageBase64: (payload.packageBase64 || '').trim() || undefined,
    packageFileName: (payload.packageFileName || '').trim() || undefined,
  }
  return `${SKILL_SHARE_CARD_PREFIX}${encodeUtf8Base64(JSON.stringify(cleaned))}`
}

export function parseSkillShareCardContent(content: string): SkillShareCardPayload | null {
  const raw = String(content || '').trim()
  if (!raw.startsWith(SKILL_SHARE_CARD_PREFIX)) return null
  try {
    const encoded = raw.slice(SKILL_SHARE_CARD_PREFIX.length).trim()
    if (!encoded) return null
    const parsed = JSON.parse(decodeUtf8Base64(encoded)) as SkillShareCardPayload
    const title = String(parsed.title || '').trim()
    if (!title) return null
    return {
      ...parsed,
      title,
      originalPurpose: (parsed.originalPurpose || '').trim() || undefined,
      steps: Array.isArray(parsed.steps) ? parsed.steps.map((x) => String(x || '').trim()).filter(Boolean) : [],
      tags: Array.isArray(parsed.tags) ? parsed.tags.map((x) => String(x || '').trim()).filter(Boolean) : [],
      author: (parsed.author || '').trim() || undefined,
      packageBase64: (parsed.packageBase64 || '').trim() || undefined,
      packageFileName: (parsed.packageFileName || '').trim() || undefined,
    }
  } catch {
    return null
  }
}

export function toSkillSharePreview(content: string): string | null {
  const card = parseSkillShareCardContent(content)
  if (!card) return null
  return `[技能分享] ${card.title}`
}
