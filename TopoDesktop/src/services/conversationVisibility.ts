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

const STORAGE_KEY = 'pc-conversation-hidden'

function readIds(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter((x): x is string => typeof x === 'string' && x.length > 0)
  } catch {
    return []
  }
}

function writeIds(ids: string[]): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(ids))
}

export function getHiddenConversationIds(): string[] {
  return readIds()
}

export function hideConversationFromChatList(id: string): string[] {
  const next = [id, ...readIds().filter((x) => x !== id)]
  writeIds(next)
  return next
}

export function restoreConversationToChatList(id: string): string[] {
  const next = readIds().filter((x) => x !== id)
  writeIds(next)
  return next
}

export function isConversationHiddenFromChatList(id: string): boolean {
  return readIds().includes(id)
}
