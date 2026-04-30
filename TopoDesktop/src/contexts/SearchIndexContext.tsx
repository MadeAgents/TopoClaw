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

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import type { Conversation } from '../types/conversation'

interface SearchIndexContextValue {
  conversations: Conversation[]
  setConversationsForSearch: (list: Conversation[]) => void
}

const SearchIndexContext = createContext<SearchIndexContextValue | null>(null)

export function SearchIndexProvider({ children }: { children: ReactNode }) {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const setConversationsForSearch = useCallback((list: Conversation[]) => {
    setConversations(list)
  }, [])
  const value = useMemo(
    () => ({ conversations, setConversationsForSearch }),
    [conversations, setConversationsForSearch]
  )
  return <SearchIndexContext.Provider value={value}>{children}</SearchIndexContext.Provider>
}

export function useSearchIndexForGlobalSearch(): Conversation[] {
  const ctx = useContext(SearchIndexContext)
  return ctx?.conversations ?? []
}

export function useSearchIndexUpdater(): (list: Conversation[]) => void {
  const ctx = useContext(SearchIndexContext)
  if (!ctx) {
    return () => {}
  }
  return ctx.setConversationsForSearch
}
