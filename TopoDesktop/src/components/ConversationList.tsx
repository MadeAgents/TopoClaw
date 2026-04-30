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

import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { getFriends, getGroups, getProfile, getCustomAssistantsFromCloud } from '../services/api'
import { perfLog, perfLogEnd } from '../utils/perfLog'
import type { Friend, GroupInfo, UserProfile } from '../services/api'
import { getImei, getValidatedImei } from '../services/storage'
import { hasStoredMessages } from '../services/messageStorage'
import {
  getVisibleCustomAssistants,
  builtinSlotForAssistantId,
  mergeCloudAssistantsAndEnsureDefaultTopo,
  ensureDefaultBuiltinAssistantsAndMaybeSync,
  resolveCustomAssistantAvatarForDisplay,
} from '../services/customAssistants'
import type { Conversation } from '../types/conversation'
import {
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
  CONVERSATION_ID_GROUP,
  CONVERSATION_ID_ME,
} from '../types/conversation'
import { GroupAvatar } from './GroupAvatar'
import { getFriendsGroupAvatarSources, getGroupAvatarSourcesFromMembers } from '../utils/groupAvatar'
import { toAvatarSrc, toAvatarSrcLikeContacts } from '../utils/avatar'
import { ASSISTANT_AVATAR, SKILL_LEARNING_AVATAR, CUSTOMER_SERVICE_AVATAR, ME_AVATAR } from '../constants/assistants'
import {
  getPinnedConversationIds,
  pinConversation,
  unpinConversation,
} from '../services/conversationPins'
import {
  hideConversationFromChatList,
  isConversationHiddenFromChatList,
} from '../services/conversationVisibility'
import { useSearchIndexUpdater } from '../contexts/SearchIndexContext'
import { OnlineStatusManager } from '../services/onlineStatusManager'
import './ConversationList.css'

type TabType = 'all' | 'friend' | 'group' | 'assistant'

interface ConversationListProps {
  selectedId: string | null
  onSelect: (c: Conversation) => void
  onDelete?: (c: Conversation) => void
  lastMessages?: Record<string, string>
  lastMessageTimes?: Record<string, number>
  unreadCounts?: Record<string, number>
  conversationsWithMessages?: Set<string>
  refreshKey?: number
}

function formatLastMessageTime(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)
  if (d >= today) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  if (d >= yesterday) return '昨天'
  if (d.getFullYear() === now.getFullYear()) {
    return `${d.getMonth() + 1}月${d.getDate()}日`
  }
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

const DEFAULT_ASSISTANT_NAMES: Record<string, string> = {
  assistant: '自动执行小助手',
  skill_learning: '技能学习小助手',
  chat_assistant: '聊天小助手',
  customer_service: '人工客服',
}
const HIDDEN_BUILTIN_ASSISTANT_IDS = new Set([
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
])

const ASSISTANT_CONVERSATIONS: Conversation[] = [
  { id: CONVERSATION_ID_ME, name: '我的手机', lastMessageTime: Date.now(), type: 'cross_device', isPinned: true, avatar: ME_AVATAR },
  { id: CONVERSATION_ID_ASSISTANT, name: '自动执行小助手', lastMessageTime: Date.now(), type: 'assistant', isPinned: true, avatar: ASSISTANT_AVATAR },
  { id: CONVERSATION_ID_SKILL_LEARNING, name: '技能学习小助手', lastMessageTime: Date.now(), type: 'assistant', avatar: SKILL_LEARNING_AVATAR },
  { id: CONVERSATION_ID_CUSTOMER_SERVICE, name: '人工客服', lastMessageTime: Date.now(), type: 'assistant', avatar: CUSTOMER_SERVICE_AVATAR },
]
const VISIBLE_ASSISTANT_CONVERSATIONS = ASSISTANT_CONVERSATIONS.filter((c) => !HIDDEN_BUILTIN_ASSISTANT_IDS.has(c.id))
const DEFAULT_GROUP_AUTOPIN_STORAGE_KEY = 'pc-default-group-autopin-done'

function readDefaultGroupAutopinDoneIds(): Set<string> {
  try {
    const raw = localStorage.getItem(DEFAULT_GROUP_AUTOPIN_STORAGE_KEY)
    if (!raw) return new Set()
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return new Set()
    return new Set(parsed.filter((x): x is string => typeof x === 'string' && x.length > 0))
  } catch {
    return new Set()
  }
}

function writeDefaultGroupAutopinDoneIds(ids: Set<string>): void {
  localStorage.setItem(DEFAULT_GROUP_AUTOPIN_STORAGE_KEY, JSON.stringify([...ids]))
}

function canDeleteConversation(c: Conversation): boolean {
  return c.id !== CONVERSATION_ID_ME
}

function buildLocalCustomAssistantConversations(): Conversation[] {
  return getVisibleCustomAssistants().map((a) => ({
    id: a.id,
    name: a.name,
    avatar: toAvatarSrcLikeContacts(resolveCustomAssistantAvatarForDisplay(a)),
    lastMessageTime: Date.now(),
    type: 'assistant' as const,
    baseUrl: a.baseUrl,
    displayId: a.displayId,
    multiSessionEnabled: a.multiSessionEnabled,
  }))
}

function normalizeGroupRawId(groupId: string): string {
  let raw = String(groupId || '').trim()
  while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
  return raw
}

function toGroupConversationId(groupId: string): string {
  const raw = normalizeGroupRawId(groupId)
  return raw ? `group_${raw}` : 'group_'
}

function hasChatContent(c: Conversation, lastMessages: Record<string, string>, conversationsWithMessages: Set<string>): boolean {
  if (c.id === CONVERSATION_ID_ASSISTANT) return true // 自动执行小助手始终显示
  if (builtinSlotForAssistantId(c.id) === 'topoclaw') return true // TopoClaw 始终显示
  if (c.type === 'group' && c.isDefaultGroup) return true // 默认群始终显示
  if (c.type === 'assistant') {
    // 其他小助手（人工客服、技能学习、聊天小助手、自定义）：无聊天记录则不显示
    return !!(lastMessages[c.id] || conversationsWithMessages.has(c.id) || hasStoredMessages(c.id))
  }
  if (lastMessages[c.id]) return true
  if (conversationsWithMessages.has(c.id)) return true
  if (c.type === 'group') return hasStoredMessages(c.id)
  return false
}

export function ConversationList({ selectedId, onSelect, onDelete, lastMessages = {}, lastMessageTimes = {}, unreadCounts = {}, conversationsWithMessages = new Set(), refreshKey = 0 }: ConversationListProps) {
  const [tab, setTab] = useState<TabType>('all')
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [friends, setFriends] = useState<Friend[]>([])
  const [onlineUsers, setOnlineUsers] = useState<Set<string>>(new Set())
  const [userAvatar, setUserAvatar] = useState<string | undefined>()
  const [userName, setUserName] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [pinnedIds, setPinnedIds] = useState<string[]>(() => getPinnedConversationIds())
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; conversation: Conversation } | null>(null)
  const contextMenuRef = useRef<HTMLDivElement>(null)
  const setSearchIndex = useSearchIndexUpdater()
  const remoteSnapshotRef = useRef<{ friends: Friend[]; groups: GroupInfo[]; profile: UserProfile | null }>({
    friends: [],
    groups: [],
    profile: null,
  })

  const refreshPinned = useCallback(() => {
    setPinnedIds(getPinnedConversationIds())
  }, [])

  useEffect(() => {
    if (!contextMenu) return
    const onPointerDown = (e: MouseEvent | TouchEvent) => {
      const el = contextMenuRef.current
      const t = e.target as Node
      if (el?.contains(t)) return
      setContextMenu(null)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setContextMenu(null)
    }
    document.addEventListener('mousedown', onPointerDown, true)
    document.addEventListener('touchstart', onPointerDown, true)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onPointerDown, true)
      document.removeEventListener('touchstart', onPointerDown, true)
      document.removeEventListener('keydown', onKey)
    }
  }, [contextMenu])

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      const loadStart = Date.now()
      perfLog('ConversationList 开始加载')
      const imei = getValidatedImei()
      // 首屏先渲染本地会话壳子，避免被云端接口阻塞。
      const localAssistants = buildLocalCustomAssistantConversations()
      if (!cancelled) {
        // 仅在首次无数据时渲染壳子；后续 refresh 保留现有列表，避免长度来回抖动。
        setConversations((prev) => (prev.length > 0 ? prev : [...VISIBLE_ASSISTANT_CONVERSATIONS, ...localAssistants]))
        setLoading(false)
      }

      if (!imei) {
        await ensureDefaultBuiltinAssistantsAndMaybeSync(undefined)
        if (!cancelled) {
          setConversations([...VISIBLE_ASSISTANT_CONVERSATIONS, ...buildLocalCustomAssistantConversations()])
        }
        perfLogEnd('ConversationList 加载', loadStart, { skip: 'no imei' })
        return
      }
      try {
        const FAST_TIMEOUT_MS = 3500
        let degraded = false
        // 首屏关键路径短超时：失败时不覆盖本地，避免列表长度来回抖动。
        try {
          const cloudAssistants = await getCustomAssistantsFromCloud(
            imei,
            refreshKey > 0 ? Date.now() : undefined,
            { timeoutMs: FAST_TIMEOUT_MS, throwOnError: true }
          )
          const cloudItems = cloudAssistants.map((a) => ({
            id: a.id,
            name: a.name,
            intro: a.intro,
            baseUrl: a.baseUrl,
            capabilities: a.capabilities,
            avatar: a.avatar,
            multiSessionEnabled: a.multiSessionEnabled,
            displayId: a.displayId,
            assistantOrigin: a.assistantOrigin,
            creator_imei: a.creator_imei,
          }))
          // 仅在请求成功时才用云端覆盖本地
          await mergeCloudAssistantsAndEnsureDefaultTopo(cloudItems, imei)
        } catch {
          // keep local assistants unchanged
          degraded = true
        }
        const [f, g, profile] = await Promise.all([
          getFriends(imei, { timeoutMs: FAST_TIMEOUT_MS }).catch(() => {
            degraded = true
            return remoteSnapshotRef.current.friends
          }),
          getGroups(imei, { timeoutMs: FAST_TIMEOUT_MS }).catch(() => {
            degraded = true
            return remoteSnapshotRef.current.groups
          }),
          getProfile(imei, { timeoutMs: FAST_TIMEOUT_MS }).catch(() => {
            degraded = true
            return remoteSnapshotRef.current.profile
          }),
        ])
        remoteSnapshotRef.current = { friends: f, groups: g, profile }
        if (cancelled) return
        setFriends(f)
        setUserAvatar(profile?.avatar)
        setUserName(profile?.name ?? '')
        const customAssistants = buildLocalCustomAssistantConversations()
        const list: Conversation[] = [
          ...VISIBLE_ASSISTANT_CONVERSATIONS,
          ...customAssistants,
          ...g.map((gr) => {
            const groupAssistants = gr.assistants ?? (gr.assistant_enabled ? ['assistant'] : [])
            const configs = gr.assistant_configs ?? {}
            const assistantNameCount: Record<string, number> = {}
            for (const aid of groupAssistants) {
              const baseName = configs[aid]?.name ?? DEFAULT_ASSISTANT_NAMES[aid] ?? customAssistants.find((c) => c.id === aid)?.name ?? aid
              assistantNameCount[baseName] = (assistantNameCount[baseName] ?? 0) + 1
            }
            return {
              id: toGroupConversationId(gr.group_id),
              name: gr.name,
              lastMessageTime: Date.now(),
              type: 'group' as const,
              isDefaultGroup: gr.is_default_group === true,
              members: gr.members,
              groupWorkflowModeEnabled: !!gr.workflow_mode,
              groupFreeDiscoveryEnabled: !!gr.free_discovery,
              groupAssistantMutedEnabled: !!gr.assistant_muted,
              assistantConfigs: Object.keys(configs).length > 0 ? configs : undefined,
              assistants: groupAssistants.map((aid) => ({
                id: aid,
                name: (() => {
                  const baseName = configs[aid]?.name ?? DEFAULT_ASSISTANT_NAMES[aid] ?? customAssistants.find((c) => c.id === aid)?.name ?? aid
                  return (assistantNameCount[baseName] ?? 0) > 1 ? `${baseName}(${aid})` : baseName
                })(),
              })),
            }
          }),
          ...f.filter((fr) => fr.status === 'accepted').map((fr) => ({
            id: `friend_${fr.imei}`,
            name: fr.nickname ?? fr.imei.slice(0, 8) + '...',
            avatar: fr.avatar,
            lastMessageTime: fr.addedAt,
            type: 'friend' as const,
          })),
        ]
        if (cancelled) return
        setConversations(list)
        perfLogEnd('ConversationList 加载', loadStart, { count: list.length })

        if (!degraded) return
        // 后台补偿重试：使用常规超时再拉一轮，补齐慢网/抖动下首屏阶段未拿到的数据。
        void (async () => {
          try {
            const RETRY_TIMEOUT_MS = 15000
            const cloudAssistantsRetry = await getCustomAssistantsFromCloud(
              imei,
              refreshKey > 0 ? Date.now() : undefined,
              { timeoutMs: RETRY_TIMEOUT_MS, throwOnError: true }
            )
            const cloudItemsRetry = cloudAssistantsRetry.map((a) => ({
              id: a.id,
              name: a.name,
              intro: a.intro,
              baseUrl: a.baseUrl,
              capabilities: a.capabilities,
              avatar: a.avatar,
              multiSessionEnabled: a.multiSessionEnabled,
              displayId: a.displayId,
              assistantOrigin: a.assistantOrigin,
              creator_imei: a.creator_imei,
            }))
            await mergeCloudAssistantsAndEnsureDefaultTopo(cloudItemsRetry, imei)
            const [fRetry, gRetry, profileRetry] = await Promise.all([
              getFriends(imei, { timeoutMs: RETRY_TIMEOUT_MS }).catch(() => f),
              getGroups(imei, { timeoutMs: RETRY_TIMEOUT_MS }).catch(() => g),
              getProfile(imei, { timeoutMs: RETRY_TIMEOUT_MS }).catch(() => profile),
            ])
            remoteSnapshotRef.current = { friends: fRetry, groups: gRetry, profile: profileRetry }
            if (cancelled) return
            setFriends(fRetry)
            setUserAvatar(profileRetry?.avatar)
            setUserName(profileRetry?.name ?? '')
            const customAssistantsRetry = buildLocalCustomAssistantConversations()
            const retryList: Conversation[] = [
              ...VISIBLE_ASSISTANT_CONVERSATIONS,
              ...customAssistantsRetry,
              ...gRetry.map((gr) => {
                const groupAssistants = gr.assistants ?? (gr.assistant_enabled ? ['assistant'] : [])
                const configs = gr.assistant_configs ?? {}
                const assistantNameCount: Record<string, number> = {}
                for (const aid of groupAssistants) {
                  const baseName = configs[aid]?.name ?? DEFAULT_ASSISTANT_NAMES[aid] ?? customAssistantsRetry.find((c) => c.id === aid)?.name ?? aid
                  assistantNameCount[baseName] = (assistantNameCount[baseName] ?? 0) + 1
                }
                return {
                  id: toGroupConversationId(gr.group_id),
                  name: gr.name,
                  lastMessageTime: Date.now(),
                  type: 'group' as const,
                  isDefaultGroup: gr.is_default_group === true,
                  members: gr.members,
                  groupWorkflowModeEnabled: !!gr.workflow_mode,
                  groupFreeDiscoveryEnabled: !!gr.free_discovery,
                  groupAssistantMutedEnabled: !!gr.assistant_muted,
                  assistantConfigs: Object.keys(configs).length > 0 ? configs : undefined,
                  assistants: groupAssistants.map((aid) => ({
                    id: aid,
                    name: (() => {
                      const baseName = configs[aid]?.name ?? DEFAULT_ASSISTANT_NAMES[aid] ?? customAssistantsRetry.find((c) => c.id === aid)?.name ?? aid
                      return (assistantNameCount[baseName] ?? 0) > 1 ? `${baseName}(${aid})` : baseName
                    })(),
                  })),
                }
              }),
              ...fRetry.filter((fr) => fr.status === 'accepted').map((fr) => ({
                id: `friend_${fr.imei}`,
                name: fr.nickname ?? fr.imei.slice(0, 8) + '...',
                avatar: fr.avatar,
                lastMessageTime: fr.addedAt,
                type: 'friend' as const,
              })),
            ]
            if (cancelled) return
            setConversations(retryList)
          } catch {
            // ignore background retry errors
          }
        })()
      } catch (e) {
        if (!cancelled) {
          // 保留已有列表，只有在完全无数据时才回退到本地壳子。
          setConversations((prev) =>
            prev.length > 0 ? prev : [...VISIBLE_ASSISTANT_CONVERSATIONS, ...buildLocalCustomAssistantConversations()]
          )
        }
        perfLogEnd('ConversationList 加载失败', loadStart, { error: String(e) })
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [refreshKey])

  useEffect(() => {
    setSearchIndex(conversations)
  }, [conversations, setSearchIndex])

  useEffect(() => {
    const imei = getValidatedImei()
    if (!imei) {
      setOnlineUsers(new Set())
      return
    }
    const unsubscribe = OnlineStatusManager.addListener((nextOnlineUsers) => {
      setOnlineUsers(nextOnlineUsers)
    })
    OnlineStatusManager.startChecking()
    return () => {
      unsubscribe()
      OnlineStatusManager.stopChecking()
    }
  }, [])

  useEffect(() => {
    const defaultGroupIds = conversations
      .filter((c) => c.type === 'group' && c.isDefaultGroup)
      .map((c) => c.id)
    if (defaultGroupIds.length === 0) return
    const doneIds = readDefaultGroupAutopinDoneIds()
    let changed = false
    defaultGroupIds.forEach((id) => {
      // 仅首次发现该默认群时自动置顶一次，后续尊重用户手动取消置顶。
      if (!doneIds.has(id)) {
        doneIds.add(id)
        if (!pinnedIds.includes(id)) {
          pinConversation(id)
          changed = true
        }
      }
    })
    if (changed) {
      writeDefaultGroupAutopinDoneIds(doneIds)
      refreshPinned()
      return
    }
    // 即使本次无需 pin，也要持久化 newly seen default group，避免下次重复尝试。
    const alreadySaved = readDefaultGroupAutopinDoneIds()
    let doneSetChanged = false
    defaultGroupIds.forEach((id) => {
      if (!alreadySaved.has(id)) {
        alreadySaved.add(id)
        doneSetChanged = true
      }
    })
    if (doneSetChanged) {
      writeDefaultGroupAutopinDoneIds(alreadySaved)
    }
  }, [conversations, pinnedIds, refreshPinned])

  const pinnedSet = useMemo(() => new Set(pinnedIds), [pinnedIds])
  const pinnedIndexMap = useMemo(() => {
    const m = new Map<string, number>()
    pinnedIds.forEach((id, idx) => m.set(id, idx))
    return m
  }, [pinnedIds])
  const sortedList = useMemo(() => {
    const filtered = conversations.filter((c) => {
      if (HIDDEN_BUILTIN_ASSISTANT_IDS.has(c.id)) return false
      if (isConversationHiddenFromChatList(c.id)) return false
      if (!hasChatContent(c, lastMessages, conversationsWithMessages) && c.id !== selectedId) return false
      if (tab === 'all') return true
      if (tab === 'assistant') return c.type === 'assistant' || c.type === 'cross_device'
      if (tab === 'group') return c.type === 'group'
      if (tab === 'friend') return c.type === 'friend'
      return true
    })
    return [...filtered].sort((a, b) => {
      const ap = pinnedSet.has(a.id)
      const bp = pinnedSet.has(b.id)
      if (ap !== bp) return ap ? -1 : 1
      if (ap && bp) return (pinnedIndexMap.get(a.id) ?? 0) - (pinnedIndexMap.get(b.id) ?? 0)
      const ta = lastMessageTimes[a.id] ?? (a.type === 'friend' ? a.lastMessageTime : 0) ?? 0
      const tb = lastMessageTimes[b.id] ?? (b.type === 'friend' ? b.lastMessageTime : 0) ?? 0
      return tb - ta
    })
  }, [conversations, lastMessages, conversationsWithMessages, selectedId, tab, pinnedSet, pinnedIndexMap, lastMessageTimes])
  const currentImei = getImei() ?? ''
  const groupAvatarByConversationId = useMemo(() => {
    const m = new Map<string, ReturnType<typeof getFriendsGroupAvatarSources> | ReturnType<typeof getGroupAvatarSourcesFromMembers>>()
    for (const c of sortedList) {
      const isFriendsGroup = c.type === 'group' && c.id === CONVERSATION_ID_GROUP
      const isGroupWithMembers = c.type === 'group' && !!c.members && c.members.length > 0
      if (isFriendsGroup) {
        m.set(c.id, getFriendsGroupAvatarSources(userAvatar, userName))
      } else if (isGroupWithMembers) {
        m.set(
          c.id,
          getGroupAvatarSourcesFromMembers(c.members!, friends, currentImei, userAvatar, {
            assistants: c.assistants?.map((a) => a.id) ?? [],
            assistantConfigs: c.assistantConfigs,
            assistantNames: Object.fromEntries((c.assistants ?? []).map((a) => [a.id, a.name])),
          })
        )
      }
    }
    return m
  }, [sortedList, userAvatar, userName, friends, currentImei])

  return (
    <div className="conversation-list">
      <div className="list-tabs-wrap">
      <div className="list-tabs">
        <button className={tab === 'all' ? 'active' : ''} onClick={() => setTab('all')}>全部</button>
        <button className={tab === 'friend' ? 'active' : ''} onClick={() => setTab('friend')}>单聊</button>
        <button className={tab === 'group' ? 'active' : ''} onClick={() => setTab('group')}>群聊</button>
        <button className={tab === 'assistant' ? 'active' : ''} onClick={() => setTab('assistant')}>助手</button>
      </div>
      </div>
      <div className="list-items">
        {loading ? (
          <div className="list-loading">加载中...</div>
        ) : (
          sortedList.map((c) => {
            const groupAvatarSrc = groupAvatarByConversationId.get(c.id) ?? null

            const userPinned = pinnedSet.has(c.id)
            return (
            <div
              key={c.id}
              className={`list-item ${selectedId === c.id ? 'selected' : ''} ${c.id === CONVERSATION_ID_ME ? 'list-item-me' : ''} ${userPinned ? 'list-item-pinned' : ''}`}
              onClick={() => onSelect(c)}
              onContextMenu={(e) => {
                e.preventDefault()
                e.stopPropagation()
                setContextMenu({ x: e.clientX, y: e.clientY, conversation: c })
              }}
            >
              <div className="item-avatar">
                {groupAvatarSrc ? (
                  <GroupAvatar {...groupAvatarSrc} size={44} className="item-avatar-group" />
                ) : c.avatar ? (
                  <img src={toAvatarSrc(c.avatar) ?? c.avatar} alt="" />
                ) : (
                  <span>{c.name.slice(0, 1)}</span>
                )}
              </div>
              <div className="item-content">
                <div className="item-top">
                  <div className="item-title-row">
                    <span className="item-name-text">{c.name}</span>
                    {c.type === 'friend' && onlineUsers.has(c.id.replace(/^friend_/, '')) && (
                      <span className="item-online-lamp" title="在线" aria-label="在线">
                        💡
                      </span>
                    )}
                    {userPinned && (
                      <span className="item-pin-badge" title="已置顶">
                        置顶
                      </span>
                    )}
                  </div>
                  <span className="item-time">{lastMessageTimes[c.id] != null ? formatLastMessageTime(lastMessageTimes[c.id]!) : (c.type === 'friend' ? formatLastMessageTime(c.lastMessageTime) : '')}</span>
                </div>
                <div className="item-preview">{lastMessages[c.id] ?? c.lastMessage ?? ''}</div>
              </div>
              {(unreadCounts[c.id] ?? 0) > 0 && (
                <span className="item-badge">{unreadCounts[c.id]! > 99 ? '99+' : unreadCounts[c.id]}</span>
              )}
            </div>
          )})
        )}
      </div>
      {contextMenu && (
        <div
          ref={contextMenuRef}
          className="conversation-list-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          role="menu"
        >
          {contextMenu.conversation.id === CONVERSATION_ID_ME ? (
            <button
              type="button"
              className="conversation-list-context-menu-item"
              role="menuitem"
              onClick={() => setContextMenu(null)}
            >
              我的手机固定置顶
            </button>
          ) : pinnedIds.includes(contextMenu.conversation.id) ? (
            <>
              <button
                type="button"
                className="conversation-list-context-menu-item"
                role="menuitem"
                onClick={() => {
                  unpinConversation(contextMenu.conversation.id)
                  refreshPinned()
                  setContextMenu(null)
                }}
              >
                取消置顶
              </button>
              {canDeleteConversation(contextMenu.conversation) && (
                <button
                  type="button"
                  className="conversation-list-context-menu-item"
                  role="menuitem"
                  onClick={() => {
                    hideConversationFromChatList(contextMenu.conversation.id)
                    unpinConversation(contextMenu.conversation.id)
                    refreshPinned()
                    onDelete?.(contextMenu.conversation)
                    setContextMenu(null)
                  }}
                >
                  删除
                </button>
              )}
            </>
          ) : (
            <>
              <button
                type="button"
                className="conversation-list-context-menu-item"
                role="menuitem"
                onClick={() => {
                  pinConversation(contextMenu.conversation.id)
                  refreshPinned()
                  setContextMenu(null)
                }}
              >
                置顶
              </button>
              {canDeleteConversation(contextMenu.conversation) && (
                <button
                  type="button"
                  className="conversation-list-context-menu-item"
                  role="menuitem"
                  onClick={() => {
                    hideConversationFromChatList(contextMenu.conversation.id)
                    unpinConversation(contextMenu.conversation.id)
                    refreshPinned()
                    onDelete?.(contextMenu.conversation)
                    setContextMenu(null)
                  }}
                >
                  删除
                </button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}
