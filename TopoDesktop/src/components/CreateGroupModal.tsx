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

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  createGroup,
  addGroupAssistant,
  getFriends,
  getGroup,
  type GroupInfo,
} from '../services/api'
import { getImei } from '../services/storage'
import {
  getVisibleCustomAssistants,
  getCustomAssistantById,
  DEFAULT_GROUP_MANAGER_ASSISTANT_ID,
} from '../services/customAssistants'
import type { Conversation } from '../types/conversation'
import {
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CHAT_ASSISTANT,
} from '../types/conversation'
import './CreateGroupModal.css'

interface CreateGroupModalProps {
  onClose: () => void
  /** 创建成功并同步小助手后，传入可打开的群会话（与移动端建群后进入群聊一致） */
  onCreated?: (conversation: Conversation) => void
}

const BUILTIN_ASSISTANT_NAMES: Record<string, string> = {
  [CONVERSATION_ID_ASSISTANT]: '自动执行小助手',
  [CONVERSATION_ID_SKILL_LEARNING]: '技能学习小助手',
  [CONVERSATION_ID_CHAT_ASSISTANT]: '聊天小助手',
  [DEFAULT_GROUP_MANAGER_ASSISTANT_ID]: 'GroupManager',
}
const HIDDEN_BUILTIN_ASSISTANT_IDS = new Set([
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
])

function toGroupConversationId(groupId: string): string {
  let raw = String(groupId || '').trim()
  while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
  return raw ? `group_${raw}` : 'group_'
}

function groupInfoToConversation(g: GroupInfo, customNameById: Map<string, string>): Conversation {
  const groupAssistants = g.assistants ?? (g.assistant_enabled ? [DEFAULT_GROUP_MANAGER_ASSISTANT_ID] : [])
  return {
    id: toGroupConversationId(g.group_id),
    name: g.name,
    lastMessageTime: Date.now(),
    type: 'group',
    members: g.members ?? [],
    assistants: groupAssistants.map((aid) => ({
      id: aid,
      name: customNameById.get(aid) ?? BUILTIN_ASSISTANT_NAMES[aid] ?? aid,
    })),
    ...(g.assistant_configs != null && { assistantConfigs: g.assistant_configs as Conversation['assistantConfigs'] }),
  }
}

export function CreateGroupModal({ onClose, onCreated }: CreateGroupModalProps) {
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [friendsLoading, setFriendsLoading] = useState(true)
  const [friends, setFriends] = useState<Awaited<ReturnType<typeof getFriends>>>([])
  const [selectedFriends, setSelectedFriends] = useState<Set<string>>(new Set())
  const [selectedAssistants, setSelectedAssistants] = useState<Set<string>>(
    () => new Set([DEFAULT_GROUP_MANAGER_ASSISTANT_ID])
  )

  const customAssistants = useMemo(() => getVisibleCustomAssistants(), [])

  const assistantRows = useMemo(() => {
    const rows: { id: string; label: string }[] = [
      { id: DEFAULT_GROUP_MANAGER_ASSISTANT_ID, label: BUILTIN_ASSISTANT_NAMES[DEFAULT_GROUP_MANAGER_ASSISTANT_ID] },
      { id: CONVERSATION_ID_ASSISTANT, label: BUILTIN_ASSISTANT_NAMES[CONVERSATION_ID_ASSISTANT] },
      { id: CONVERSATION_ID_SKILL_LEARNING, label: BUILTIN_ASSISTANT_NAMES[CONVERSATION_ID_SKILL_LEARNING] },
      { id: CONVERSATION_ID_CHAT_ASSISTANT, label: BUILTIN_ASSISTANT_NAMES[CONVERSATION_ID_CHAT_ASSISTANT] },
    ].filter((row) => !HIDDEN_BUILTIN_ASSISTANT_IDS.has(row.id))
    const exists = new Set(rows.map((r) => r.id))
    customAssistants.forEach((a) => {
      if (!exists.has(a.id)) rows.push({ id: a.id, label: a.name })
    })
    return rows
  }, [customAssistants])

  const loadFriends = useCallback(async () => {
    const imei = getImei()
    if (!imei) {
      setFriends([])
      setFriendsLoading(false)
      return
    }
    setFriendsLoading(true)
    try {
      const list = await getFriends(imei).catch(() => [])
      setFriends(list.filter((f) => f.status === 'accepted'))
    } finally {
      setFriendsLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadFriends()
  }, [loadFriends])

  const toggleFriend = (imei: string) => {
    setSelectedFriends((prev) => {
      const next = new Set(prev)
      if (next.has(imei)) next.delete(imei)
      else next.add(imei)
      return next
    })
    setError('')
  }

  const toggleAssistant = (id: string) => {
    if (id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID) return
    setSelectedAssistants((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
    setError('')
  }

  const selectedSummary = useMemo(() => {
    const n = selectedFriends.size
    const a = selectedAssistants.size
    if (a === 0) return `已选 ${n} 位好友`
    return `已选 ${n} 位好友，${a} 个小助手`
  }, [selectedFriends.size, selectedAssistants.size])

  const handleCreate = async () => {
    const trimmed = name.trim()
    if (!trimmed) {
      setError('请输入群组名称')
      return
    }
    if (selectedFriends.size === 0 && selectedAssistants.size === 0) {
      setError('请至少选择一位好友或一个小助手')
      return
    }
    const imei = getImei()
    if (!imei) {
      setError('请先绑定设备')
      return
    }
    setError('')
    setLoading(true)
    try {
      const memberImeis = [...selectedFriends]
      const finalSelectedAssistants = new Set(selectedAssistants)
      finalSelectedAssistants.add(DEFAULT_GROUP_MANAGER_ASSISTANT_ID)
      // 首次建群仅需“群内存在助手”即可；此处传 true 以满足后端首建条件，再补充其它助手。
      const assistantEnabled = finalSelectedAssistants.size > 0
      const res = await createGroup(imei, trimmed, memberImeis, assistantEnabled)
      if (!res.success || !res.group?.group_id) {
        setError(res.message ?? '创建失败')
        return
      }

      let group: GroupInfo | undefined = res.group
      const gid = group.group_id

      const extraIds = [...finalSelectedAssistants].filter((id) => id !== DEFAULT_GROUP_MANAGER_ASSISTANT_ID)
      for (const aid of extraIds) {
        const custom = getCustomAssistantById(aid)
        const r = await addGroupAssistant(
          imei,
          gid,
          aid,
          custom
            ? {
                baseUrl: custom.baseUrl,
                name: custom.name,
                capabilities: custom.capabilities,
                intro: custom.intro,
                avatar: custom.avatar,
                multiSession: custom.multiSessionEnabled,
                displayId: custom.displayId,
              }
            : undefined
        )
        if (r.success && r.group) {
          group = r.group
        }
      }

      if (!group) {
        const fetched = await getGroup(gid)
        if (fetched) group = fetched
      }

      if (!group) {
        setError('创建成功但无法加载群组信息')
        onCreated?.({
          id: `group_${gid}`,
          name: trimmed,
          lastMessageTime: Date.now(),
          type: 'group',
          members: [imei, ...memberImeis],
        })
        onClose()
        return
      }

      const customNameById = new Map(customAssistants.map((a) => [a.id, a.name]))
      onCreated?.(groupInfoToConversation(group, customNameById))
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="create-group-overlay" onClick={onClose}>
      <div className="create-group-modal" onClick={(e) => e.stopPropagation()}>
        <div className="create-group-header">
          <span className="create-group-title">创建群组</span>
          <button type="button" className="create-group-close" onClick={onClose} aria-label="关闭">×</button>
        </div>
        <p className="create-group-hint">
          输入群组名称，选择已接受的好友与群内小助手（GroupManager 固定启用且作为群组管理助手；自动执行小助手默认不勾选）
        </p>
        <input
          className="create-group-input"
          placeholder="请输入群组名称"
          value={name}
          onChange={(e) => { setName(e.target.value); setError('') }}
        />
        <p className="create-group-summary" aria-live="polite">{selectedSummary}</p>

        <div className="create-group-list-wrap">
          <div className="create-group-scroll create-group-scroll-unified">
            <div className="create-group-section-label">小助手</div>
            {assistantRows.map((row) => (
              <label key={row.id} className="create-group-check-row">
                {(() => {
                  const isForcedGroupManager = row.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID
                  return (
                <input
                  type="checkbox"
                  checked={isForcedGroupManager ? true : selectedAssistants.has(row.id)}
                  onChange={() => toggleAssistant(row.id)}
                  disabled={isForcedGroupManager}
                />
                  )
                })()}
                <span className="create-group-check-label">{row.label}</span>
              </label>
            ))}

            <div className="create-group-section-label create-group-section-label-spaced">好友</div>
            {friendsLoading ? (
              <div className="create-group-empty">加载好友中…</div>
            ) : friends.length === 0 ? (
              <div className="create-group-empty">暂无已接受的好友，请先添加好友</div>
            ) : (
              friends.map((f) => {
                const label = f.nickname?.trim() || `${f.imei.slice(0, 8)}…`
                return (
                  <label key={f.imei} className="create-group-check-row">
                    <input
                      type="checkbox"
                      checked={selectedFriends.has(f.imei)}
                      onChange={() => toggleFriend(f.imei)}
                    />
                    <span className="create-group-check-label">{label}</span>
                  </label>
                )
              })
            )}
          </div>
        </div>

        {error && <p className="create-group-error">{error}</p>}
        <div className="create-group-actions">
          <button type="button" className="create-group-cancel" onClick={onClose}>取消</button>
          <button
            type="button"
            className="create-group-create"
            onClick={() => void handleCreate()}
            disabled={loading}
          >
            {loading ? '创建中...' : '创建'}
          </button>
        </div>
      </div>
    </div>
  )
}
