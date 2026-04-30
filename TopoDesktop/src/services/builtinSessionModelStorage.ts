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

const STORAGE_KEY = 'builtin_session_model_selection_v1'

export interface BuiltinSessionModelSelection {
  nonGuiModel?: string
  guiModel?: string
  groupManagerModel?: string
  updatedAt: number
}

type SessionModelMap = Record<string, BuiltinSessionModelSelection>

function readStore(): SessionModelMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object') return {}
    return parsed as SessionModelMap
  } catch {
    return {}
  }
}

function writeStore(store: SessionModelMap): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(store))
  } catch {
    // ignore write failures (quota/private mode)
  }
}

export function loadBuiltinSessionModelSelection(sessionKey: string): BuiltinSessionModelSelection | null {
  const key = (sessionKey || '').trim()
  if (!key) return null
  const store = readStore()
  const row = store[key]
  if (!row || typeof row !== 'object') return null
  return {
    nonGuiModel: typeof row.nonGuiModel === 'string' ? row.nonGuiModel : undefined,
    guiModel: typeof row.guiModel === 'string' ? row.guiModel : undefined,
    groupManagerModel: typeof row.groupManagerModel === 'string' ? row.groupManagerModel : undefined,
    updatedAt: typeof row.updatedAt === 'number' ? row.updatedAt : 0,
  }
}

export function saveBuiltinSessionModelSelection(
  sessionKey: string,
  patch: Partial<Omit<BuiltinSessionModelSelection, 'updatedAt'>>
): void {
  const key = (sessionKey || '').trim()
  if (!key) return
  const store = readStore()
  const prev = store[key] ?? { updatedAt: 0 }
  store[key] = {
    nonGuiModel: typeof patch.nonGuiModel === 'string' ? patch.nonGuiModel : prev.nonGuiModel,
    guiModel: typeof patch.guiModel === 'string' ? patch.guiModel : prev.guiModel,
    groupManagerModel:
      typeof patch.groupManagerModel === 'string' ? patch.groupManagerModel : prev.groupManagerModel,
    updatedAt: Date.now(),
  }
  writeStore(store)
}
