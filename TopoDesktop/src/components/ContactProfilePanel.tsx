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

import { useState, useEffect, useCallback, useRef } from 'react'
import { toDataURL } from 'qrcode'
import { GroupAvatar } from './GroupAvatar'
import { getGroupAvatarSourcesFromMembers, getFriendsGroupAvatarSources } from '../utils/groupAvatar'
import { getImei, getChatAssistantUrl, setChatAssistantUrl } from '../services/storage'
import {
  getCustomAssistantById,
  isCustomAssistantId,
  hasMultiSession,
  hasChat,
  hasGroupManager,
  getGroupAssistantDisplayName,
  inferCreatorImeiFromAssistantId,
  setAssistantGroupManager,
  builtinSlotForAssistantId,
  isProtectedBuiltinAssistantId,
  DEFAULT_TOPOCLAW_ASSISTANT_ID,
  DEFAULT_GROUP_MANAGER_ASSISTANT_ID,
  resolveCustomAssistantAvatarForDisplay,
  type CustomAssistant,
} from '../services/customAssistants'
import {
  addGroupMember,
  removeGroupMember,
  addGroupAssistant,
  removeGroupAssistant,
  updateGroupAssistantConfig,
  updateGroupConfig,
  getGroup,
  getProfile,
  quitGroup,
  dissolveGroup,
  type Friend,
  type GroupInfo,
  type UserProfile,
} from '../services/api'
import type { Conversation } from '../types/conversation'
import {
  CONVERSATION_ID_GROUP,
  CONVERSATION_ID_CHAT_ASSISTANT,
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_IM_QQ,
  CONVERSATION_ID_IM_WEIXIN,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
} from '../types/conversation'
import { ASSISTANT_AVATAR, GROUP_MANAGER_AVATAR } from '../constants/assistants'
import { toAvatarSrc, toAvatarSrcLikeContacts } from '../utils/avatar'
import { ChatImageLightbox, type ImageLightboxPayload } from './ChatInlineImage'
import {
  exportBuiltinAssistantLogToFile,
  getBuiltinAssistantConfig,
  getBuiltinAssistantLogBuffer,
  getDefaultBuiltinUrls,
  getWeixinLoginQr,
  pollWeixinLoginStatus,
  restartBuiltinAssistant,
  saveBuiltinAssistantConfig,
  type BuiltinAssistantSlot,
} from '../services/builtinAssistantConfig'
import {
  getConversationSummaryFileName,
  listConversationSummaries,
  type ConversationSummaryEntry,
} from '../services/conversationSummary'
import { OPEN_BUILTIN_LOG_EVENT } from './ChatLayout'
import { EditAssistantModal } from './EditAssistantModal'
import './ContactProfilePanel.css'

type ProfileTarget =
  | {
    type: 'assistant'
    id: string
    name: string
    avatar?: string
    baseUrl?: string
    creator_imei?: string
    displayId?: string
    /** 群内助手主页：只使用群配置中的创建者信息，不回退到本地助手 */
    disableLocalCreatorFallback?: boolean
  }
  | { type: 'friend'; data: Friend }
  | { type: 'group'; data: GroupInfo; isFriendsGroup?: boolean }

function toGroupConversationId(groupId: string): string {
  let raw = String(groupId || '').trim()
  while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
  return raw ? `group_${raw}` : 'group_'
}

type WorkspaceProfileKind = 'soul' | 'memory'

const PROFILE_LABELS: Record<WorkspaceProfileKind, string> = {
  soul: '助手人格',
  memory: '助手眼中的你',
}

const PROFILE_SNIPPETS: Record<WorkspaceProfileKind, string[]> = {
  soul: ['## 核心原则\n- ', '## 沟通风格\n- ', '## 禁止事项\n- '],
  memory: ['## 用户偏好\n- ', '## 长期目标\n- ', '## 近期变化\n- '],
}

/** 用于添加成员选择的小助手项 */
export interface AssistantForGroup {
  id: string
  name: string
  avatar?: string
  displayId?: string
}

export interface WorkflowWorkspaceMemberItem {
  id: string
  name: string
  type: 'assistant' | 'user'
}

export interface WorkflowWorkspacePayload {
  groupId: string
  groupName: string
  members: WorkflowWorkspaceMemberItem[]
}

interface ContactProfilePanelProps {
  target: ProfileTarget | null
  friends: Friend[]
  /** 可添加到群组的小助手列表（默认 + 自定义） */
  assistantsForGroup?: AssistantForGroup[]
  userAvatar?: string
  userName: string
  onClose: () => void
  onSendMessage: (c: Conversation) => void
  /** 群组数据更新后回调（添加/删除成员后） */
  onGroupUpdated?: (group: GroupInfo) => void
  /** 群组被移除（退群/解散）后回调 */
  onGroupRemoved?: (groupId: string) => void
  /** 打开全局工作流编排页 */
  onOpenWorkflowWorkspace?: (payload: WorkflowWorkspacePayload) => void
}

export function ContactProfilePanel({
  target,
  friends,
  assistantsForGroup = [],
  userAvatar,
  userName,
  onClose,
  onSendMessage,
  onGroupUpdated,
  onGroupRemoved,
  onOpenWorkflowWorkspace,
}: ContactProfilePanelProps) {
  if (!target) return null

  const ASSISTANT_ID_MAP: Record<string, string> = {
    [CONVERSATION_ID_ASSISTANT]: 'assistant',
    [CONVERSATION_ID_SKILL_LEARNING]: 'skill_learning',
    [CONVERSATION_ID_CHAT_ASSISTANT]: 'chat_assistant',
    [CONVERSATION_ID_CUSTOMER_SERVICE]: 'customer_service',
  }
  const toConversation = (): Conversation => {
    switch (target.type) {
      case 'assistant': {
        const a = getCustomAssistantById(target.id)
        const baseUrl = a?.baseUrl ?? target.baseUrl
        const multiSessionEnabled = a ? hasMultiSession(a) ? true : undefined : undefined
        return {
          id: target.id,
          name: target.name,
          avatar: target.avatar,
          lastMessageTime: Date.now(),
          type: 'assistant',
          ...(baseUrl && { baseUrl }),
          ...(multiSessionEnabled !== undefined && { multiSessionEnabled }),
        }
      }
      case 'friend': {
        const f = target.data
        return {
          id: `friend_${f.imei}`,
          name: f.nickname ?? f.imei.slice(0, 8) + '...',
          avatar: f.avatar,
          lastMessageTime: f.addedAt,
          type: 'friend',
        }
      }
      case 'group':
        if (target.isFriendsGroup) {
          return {
            id: CONVERSATION_ID_GROUP,
            name: '好友群',
            lastMessageTime: Date.now(),
            type: 'group',
          }
        }
        const groupAssistants = target.data.assistants ?? (target.data.assistant_enabled ? ['assistant'] : [])
        return {
          id: toGroupConversationId(target.data.group_id),
          name: target.data.name,
          lastMessageTime: Date.now(),
          type: 'group',
          members: target.data.members ?? [],
          groupWorkflowModeEnabled: !!target.data.workflow_mode,
          groupFreeDiscoveryEnabled: !!target.data.free_discovery,
          groupAssistantMutedEnabled: !!target.data.assistant_muted,
          assistants: groupAssistants.map((aid) => {
            const a = assistantsForGroup.find((x) => x.id === aid || ASSISTANT_ID_MAP[x.id] === aid)
            return { id: aid, name: a?.name ?? aid }
          }),
        }
      default:
        return { id: '', name: '', lastMessageTime: 0, type: 'friend' }
    }
  }

  const handleSendMessage = () => {
    onSendMessage(toConversation())
    onClose()
  }

  const isChatAssistant = target.type === 'assistant' && target.id === CONVERSATION_ID_CHAT_ASSISTANT

  return (
    <ContactProfilePanelContent
      target={target}
      friends={friends}
      assistantsForGroup={assistantsForGroup}
      userAvatar={userAvatar}
      userName={userName}
      onClose={onClose}
      onSendMessage={handleSendMessage}
      toConversation={toConversation}
      isChatAssistant={isChatAssistant}
      onGroupUpdated={onGroupUpdated}
      onGroupRemoved={onGroupRemoved}
      onOpenWorkflowWorkspace={onOpenWorkflowWorkspace}
    />
  )
}

const ASSISTANT_ID_MAP: Record<string, string> = {
  [CONVERSATION_ID_ASSISTANT]: 'assistant',
  [CONVERSATION_ID_SKILL_LEARNING]: 'skill_learning',
  [CONVERSATION_ID_CHAT_ASSISTANT]: 'chat_assistant',
  [CONVERSATION_ID_CUSTOMER_SERVICE]: 'customer_service',
}

function ContactProfilePanelContent({
  target,
  friends,
  assistantsForGroup = [],
  userAvatar,
  userName,
  onClose,
  onSendMessage,
  toConversation,
  isChatAssistant,
  onGroupUpdated,
  onGroupRemoved,
  onOpenWorkflowWorkspace,
}: {
  target: NonNullable<ContactProfilePanelProps['target']>
  friends: Friend[]
  assistantsForGroup?: AssistantForGroup[]
  userAvatar?: string
  userName: string
  onClose: () => void
  onSendMessage: (c: Conversation) => void
  toConversation: () => Conversation
  isChatAssistant: boolean
  onGroupUpdated?: (group: GroupInfo) => void
  onGroupRemoved?: (groupId: string) => void
  onOpenWorkflowWorkspace?: (payload: WorkflowWorkspacePayload) => void
}) {
  const WEIXIN_DEFAULT_BASE_URL = 'https://ilinkai.weixin.qq.com'
  const [serverUrlEdit, setServerUrlEdit] = useState(getChatAssistantUrl())
  const [serverSaved, setServerSaved] = useState(false)
  /** 群组：本地数据（添加/删除后更新），非群组时为空 */
  const [groupData, setGroupData] = useState<GroupInfo | null>(
    target.type === 'group' && !target.isFriendsGroup ? target.data : null
  )
  /** 群主管理模式：是否显示删除按钮及群组管理者设置 */
  const [groupManageMode, setGroupManageMode] = useState(false)
  /** 群成员管理页面开关：仅在点击顶部按钮后显示成员区块 */
  const [showGroupMemberManagement, setShowGroupMemberManagement] = useState(false)
  const [workflowModeSaving, setWorkflowModeSaving] = useState(false)
  const [freeDiscoverySaving, setFreeDiscoverySaving] = useState(false)
  const [assistantMutedSaving, setAssistantMutedSaving] = useState(false)
  const [assistantItemMutedSaving, setAssistantItemMutedSaving] = useState<Record<string, boolean>>({})
  /** 群组管理者切换后刷新（用于重新读取 customAssistants） */
  const [groupManagerRefresh, setGroupManagerRefresh] = useState(0)
  /** 添加成员弹窗 */
  const [showAddMemberModal, setShowAddMemberModal] = useState(false)
  const [addMemberLoading, setAddMemberLoading] = useState(false)
  const [addMemberError, setAddMemberError] = useState<string | null>(null)
  /** 群助手角色提示词弹窗 */
  const [rolePromptModalTarget, setRolePromptModalTarget] = useState<{ assistantId: string; assistantName: string } | null>(null)
  const [rolePromptInput, setRolePromptInput] = useState('')
  const [rolePromptSaving, setRolePromptSaving] = useState(false)
  const [rolePromptError, setRolePromptError] = useState<string | null>(null)
  const [showLogButton, setShowLogButton] = useState(false)
  const [showRestartButton, setShowRestartButton] = useState(false)
  const [builtinProfileSlot, setBuiltinProfileSlot] = useState<BuiltinAssistantSlot>('topoclaw')
  const [restartLoading, setRestartLoading] = useState(false)
  const [exportLogLoading, setExportLogLoading] = useState(false)
  const [showProfileEditor, setShowProfileEditor] = useState(false)
  const [profileEditorKind, setProfileEditorKind] = useState<WorkspaceProfileKind>('soul')
  const [profileEditorPath, setProfileEditorPath] = useState('')
  const [profileEditorContent, setProfileEditorContent] = useState('')
  const [profileEditorLoading, setProfileEditorLoading] = useState(false)
  const [profileEditorSaving, setProfileEditorSaving] = useState(false)
  const [profileEditorRestoring, setProfileEditorRestoring] = useState(false)
  const [profileEditorEditing, setProfileEditorEditing] = useState(false)
  const [profileEditorHint, setProfileEditorHint] = useState<string | null>(null)
  const [profileEditorError, setProfileEditorError] = useState<string | null>(null)
  const [editingAssistant, setEditingAssistant] = useState<CustomAssistant | null>(null)
  const [imageLightbox, setImageLightbox] = useState<ImageLightboxPayload | null>(null)
  const [friendProfile, setFriendProfile] = useState<UserProfile | null>(null)
  const [friendProfileLoading, setFriendProfileLoading] = useState(false)
  const [memberProfileTarget, setMemberProfileTarget] = useState<ProfileTarget | null>(null)
  const [showConversationSummaryModal, setShowConversationSummaryModal] = useState(false)
  const [conversationSummaryLoading, setConversationSummaryLoading] = useState(false)
  const [conversationSummaryError, setConversationSummaryError] = useState<string | null>(null)
  const [conversationSummaryEntries, setConversationSummaryEntries] = useState<ConversationSummaryEntry[]>([])
  const [showQqModal, setShowQqModal] = useState(false)
  const [qqAppId, setQqAppId] = useState('')
  const [qqAppSecret, setQqAppSecret] = useState('')
  const [qqAllowFrom, setQqAllowFrom] = useState('*')
  const [qqSaving, setQqSaving] = useState(false)
  const [qqTesting, setQqTesting] = useState(false)
  const [qqError, setQqError] = useState('')
  const [qqSuccess, setQqSuccess] = useState('')
  const [showWeixinModal, setShowWeixinModal] = useState(false)
  const [weixinBotToken, setWeixinBotToken] = useState('')
  const [weixinBaseUrl, setWeixinBaseUrl] = useState(WEIXIN_DEFAULT_BASE_URL)
  const [weixinAllowFrom, setWeixinAllowFrom] = useState('*')
  const [weixinSaving, setWeixinSaving] = useState(false)
  const [weixinTesting, setWeixinTesting] = useState(false)
  const [weixinError, setWeixinError] = useState('')
  const [weixinSuccess, setWeixinSuccess] = useState('')
  const [weixinQrDataUrl, setWeixinQrDataUrl] = useState('')
  const [weixinQrHint, setWeixinQrHint] = useState('')
  const [weixinQrLoading, setWeixinQrLoading] = useState(false)
  const profileTextareaRef = useRef<HTMLTextAreaElement | null>(null)
  const weixinPollLoopIdRef = useRef(0)
  const weixinModalOpenRef = useRef(false)
  const imei = getImei()

  const g = groupData ?? (target.type === 'group' && !target.isFriendsGroup ? target.data : null)
  const isOwner = !!g && !!imei && g.creator_imei === imei
  const isGroupMember = !!g && !!imei && (g.members ?? []).includes(imei)
  const canRemoveGroupMember = useCallback((memberImei: string): boolean => {
    if (!g || !imei) return false
    if (memberImei === g.creator_imei) return false
    if (g.creator_imei === imei) return true
    const addedBy = g.member_added_by?.[memberImei]
    return !!addedBy && addedBy === imei
  }, [g, imei])
  const canRemoveGroupAssistant = useCallback((assistantId: string): boolean => {
    if (!g || !imei) return false
    if (g.creator_imei === imei) return true
    const addedBy = g.assistant_added_by?.[assistantId]
    return !!addedBy && addedBy === imei
  }, [g, imei])
  const groupAssistants = g?.assistants ?? (g?.assistant_enabled ? ['assistant'] : [])
  const groupMemberImeisForAssistants = [
    ...(g?.members ?? []),
    g?.creator_imei ?? '',
  ].map((s) => String(s || '').trim()).filter(Boolean)
  const groupCreatorImei = (g?.creator_imei || '').trim()
  const groupAssistantConfigsForDisplay = Object.fromEntries(
    groupAssistants.map((id) => {
      const cfg = g?.assistant_configs?.[id] ?? {}
      const creatorImeiFromCfg = String(cfg.creator_imei || '').trim()
      const creatorImeiFromId = inferCreatorImeiFromAssistantId(id, groupMemberImeisForAssistants)
      const isBuiltinOrFixedId =
        id === CONVERSATION_ID_ASSISTANT
        || id === CONVERSATION_ID_SKILL_LEARNING
        || id === CONVERSATION_ID_CHAT_ASSISTANT
        || id === CONVERSATION_ID_CUSTOMER_SERVICE
        || id === DEFAULT_TOPOCLAW_ASSISTANT_ID
        || id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID
      const creatorImei = creatorImeiFromCfg || creatorImeiFromId || (isBuiltinOrFixedId ? groupCreatorImei : '')
      const creatorNicknameFromCfg = String(cfg.creator_nickname || '').trim()
      const creatorNickname = creatorNicknameFromCfg || (
        creatorImei
          ? (creatorImei === imei
            ? (userName || '').trim()
            : (friends.find((x) => x.imei === creatorImei)?.nickname || '').trim())
          : ''
      )
      return [
        id,
        {
          ...cfg,
          creator_imei: creatorImei || undefined,
          creator_nickname: creatorNickname || undefined,
        },
      ]
    })
  ) as Record<string, { baseUrl?: string; name?: string; creator_imei?: string; creator_nickname?: string; capabilities?: string[]; displayId?: string; assistantMuted?: boolean }>
  useEffect(() => {
    if (target.type === 'group' && !target.isFriendsGroup) {
      setGroupData(target.data)
    } else {
      setGroupData(null)
    }
  }, [
    target.type,
    target.type === 'group' ? target.isFriendsGroup : undefined,
    target.type === 'group' ? target.data?.group_id : undefined,
    target.type === 'group' ? target.data?.members?.length : undefined,
    target.type === 'group' ? target.data?.assistant_enabled : undefined,
  ])

  useEffect(() => {
    setGroupManageMode(false)
    setShowGroupMemberManagement(false)
    setAddMemberError(null)
  }, [target.type, target.type === 'group' ? target.data?.group_id : undefined])

  useEffect(() => {
    weixinModalOpenRef.current = showWeixinModal
    if (!showWeixinModal) {
      weixinPollLoopIdRef.current += 1
    }
  }, [showWeixinModal])

  useEffect(() => {
    if (isChatAssistant) setServerUrlEdit(getChatAssistantUrl())
  }, [isChatAssistant])

  useEffect(() => {
    let cancelled = false
    if (target.type !== 'friend') {
      setFriendProfile(null)
      setFriendProfileLoading(false)
      return
    }
    const friendImei = target.data.imei
    if (!friendImei) {
      setFriendProfile(null)
      setFriendProfileLoading(false)
      return
    }
    setFriendProfileLoading(true)
    getProfile(friendImei, { timeoutMs: 12000 })
      .then((profile) => {
        if (cancelled) return
        setFriendProfile(profile)
      })
      .catch(() => {
        if (!cancelled) setFriendProfile(null)
      })
      .finally(() => {
        if (!cancelled) setFriendProfileLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [target])

  /** TopoClaw / GroupManager 内置地址或固定 id：显示对应实例日志与重启 */
  useEffect(() => {
    if (target.type !== 'assistant') {
      setShowLogButton(false)
      setShowRestartButton(false)
      return
    }
    const { id: assistantId, baseUrl: assistantBaseUrl } = target
    let cancelled = false
    ;(async () => {
      const urls = await getDefaultBuiltinUrls()
      if (cancelled) return
      const norm = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''
      const a = getCustomAssistantById(assistantId)
      const baseUrl = a?.baseUrl ?? assistantBaseUrl ?? ''
      const n = norm(baseUrl)
      const urlMatchesTopo =
        !!n && (n === norm(urls.topoclaw) || n === norm('http://localhost:18790/'))
      const urlMatchesGm =
        !!n && (n === norm(urls.groupmanager) || n === norm('http://localhost:18791/'))
      let slot: BuiltinAssistantSlot | null = builtinSlotForAssistantId(assistantId)
      if (!slot && assistantId === CONVERSATION_ID_ASSISTANT) slot = 'topoclaw'
      if (!slot && urlMatchesTopo) slot = 'topoclaw'
      if (!slot && urlMatchesGm) slot = 'groupmanager'
      const isDefaultDomain = slot !== null
      if (slot) setBuiltinProfileSlot(slot)
      setShowLogButton(isDefaultDomain)
      setShowRestartButton(isDefaultDomain)
    })()
    return () => {
      cancelled = true
    }
  }, [target])

  const refreshGroup = useCallback(async () => {
    if (!g?.group_id) return
    const updated = await getGroup(g.group_id)
    if (updated) {
      setGroupData(updated)
      onGroupUpdated?.(updated)
    }
  }, [g?.group_id, onGroupUpdated])

  const applyGroupTopRightMode = useCallback(
    async (mode: 'none' | 'workflow' | 'free' | 'muted') => {
      if (!imei || !g || !isOwner) return
      const prevGroup = g
      const nextWorkflowMode = mode === 'workflow'
      const nextFreeDiscovery = mode === 'free'
      const nextAssistantMuted = mode === 'muted'
      setGroupData({
        ...g,
        workflow_mode: nextWorkflowMode,
        free_discovery: nextFreeDiscovery,
        assistant_muted: nextAssistantMuted,
      })
      setWorkflowModeSaving(true)
      setFreeDiscoverySaving(true)
      setAssistantMutedSaving(true)
      setAddMemberError(null)
      try {
        const res = await updateGroupConfig(imei, g.group_id, {
          workflowMode: nextWorkflowMode,
          freeDiscovery: nextFreeDiscovery,
          assistantMuted: nextAssistantMuted,
        })
        if (!res.success) {
          setGroupData(prevGroup)
          setAddMemberError(res.message ?? '更新群组模式失败')
          return
        }
        if (res.group) {
          setGroupData(res.group)
          onGroupUpdated?.(res.group)
        } else {
          await refreshGroup()
        }
      } catch {
        setGroupData(prevGroup)
        setAddMemberError('更新群组模式失败')
      } finally {
        setWorkflowModeSaving(false)
        setFreeDiscoverySaving(false)
        setAssistantMutedSaving(false)
      }
    },
    [imei, g, isOwner, onGroupUpdated, refreshGroup]
  )

  const groupTopRightMode: 'none' | 'workflow' | 'free' | 'muted' = (
    !!g?.workflow_mode ? 'workflow' : !!g?.free_discovery ? 'free' : !!g?.assistant_muted ? 'muted' : 'none'
  )
  const groupTopRightModeSaving = workflowModeSaving || freeDiscoverySaving || assistantMutedSaving

  const handleRemoveMember = useCallback(
    async (memberImei: string) => {
      if (!imei || !g) return
      const ok = window.confirm('确定移出该成员？')
      if (!ok) return
      const res = await removeGroupMember(imei, g.group_id, memberImei)
      if (res.success) await refreshGroup()
      else setAddMemberError(res.message ?? '移除失败')
    },
    [imei, g, refreshGroup]
  )

  const handleAddMember = useCallback(
    async (item: { type: 'friend'; imei: string } | { type: 'assistant'; id: string }) => {
      if (!imei || !g) return
      setAddMemberLoading(true)
      setAddMemberError(null)
      try {
        if (item.type === 'friend') {
          const res = await addGroupMember(imei, g.group_id, item.imei)
          if (res.success) {
            await refreshGroup()
            setShowAddMemberModal(false)
          } else setAddMemberError(res.message ?? '添加失败')
        } else {
          const assistantId = ASSISTANT_ID_MAP[item.id] ?? item.id
          const custom = getCustomAssistantById(item.id) ?? getCustomAssistantById(assistantId)
          const assistantConfig = custom
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
          const res = await addGroupAssistant(imei, g.group_id, assistantId, assistantConfig)
          if (res.success) {
            if (res.group) {
              setGroupData(res.group)
              onGroupUpdated?.(res.group)
            } else {
              await refreshGroup()
            }
            setShowAddMemberModal(false)
          } else setAddMemberError(res.message ?? '添加失败')
        }
      } finally {
        setAddMemberLoading(false)
      }
    },
    [imei, g, refreshGroup, onGroupUpdated]
  )

  const handleLeaveOrDissolveGroup = useCallback(async () => {
    if (!g || !imei) return
    if (isOwner) {
      const ok = window.confirm(`确定解散群组「${g.name || g.group_id}」吗？解散后所有成员将失去该群。`)
      if (!ok) return
      const res = await dissolveGroup(imei, g.group_id)
      if (res.success) {
        window.alert('群组已解散')
        onGroupRemoved?.(g.group_id)
        onClose()
      } else {
        window.alert(res.message || '解散失败')
      }
      return
    }
    const ok = window.confirm(`确定退出群组「${g.name || g.group_id}」吗？`)
    if (!ok) return
    const res = await quitGroup(imei, g.group_id)
    if (res.success) {
      window.alert('已退出群组')
      onGroupRemoved?.(g.group_id)
      onClose()
    } else {
      window.alert(res.message || '退出失败')
    }
  }, [g, imei, isOwner, onClose, onGroupRemoved])

  const handleRemoveAssistant = useCallback(
    async (assistantId: string) => {
      if (!imei || !g) return
      const ok = window.confirm('确定移出该小助手？')
      if (!ok) return
      const id = ASSISTANT_ID_MAP[assistantId] ?? assistantId
      const res = await removeGroupAssistant(imei, g.group_id, id)
      if (res.success) {
        if (res.group) {
          setGroupData(res.group)
          onGroupUpdated?.(res.group)
        } else await refreshGroup()
      } else setAddMemberError(res.message ?? '移除失败')
    },
    [imei, g, refreshGroup, onGroupUpdated]
  )

  const handleToggleAssistantItemMuted = useCallback(
    async (assistantId: string, enabled: boolean) => {
      if (!imei || !g) return
      const cfg = g.assistant_configs?.[assistantId] ?? {}
      const creator = String(cfg.creator_imei || '').trim()
      const addedBy = String(g.assistant_added_by?.[assistantId] || '').trim()
      const canManageOwnAssistant = isGroupMember && (creator === imei || addedBy === imei)
      if (!isOwner && !canManageOwnAssistant) return
      const prevEnabled = !!cfg.assistantMuted
      setAddMemberError(null)
      setAssistantItemMutedSaving((prev) => ({ ...prev, [assistantId]: true }))
      setGroupData({
        ...g,
        assistant_configs: {
          ...(g.assistant_configs ?? {}),
          [assistantId]: {
            ...cfg,
            assistantMuted: enabled,
          },
        },
      })
      try {
        const res = await updateGroupAssistantConfig(imei, g.group_id, assistantId, { assistantMuted: enabled })
        if (!res.success) {
          setGroupData({
            ...g,
            assistant_configs: {
              ...(g.assistant_configs ?? {}),
              [assistantId]: {
                ...cfg,
                assistantMuted: prevEnabled,
              },
            },
          })
          setAddMemberError(res.message ?? '更新单助手禁言失败')
          return
        }
        if (res.group) {
          setGroupData(res.group)
          onGroupUpdated?.(res.group)
        } else {
          await refreshGroup()
        }
      } catch {
        setGroupData({
          ...g,
          assistant_configs: {
            ...(g.assistant_configs ?? {}),
            [assistantId]: {
              ...cfg,
              assistantMuted: prevEnabled,
            },
          },
        })
        setAddMemberError('更新单助手禁言失败')
      } finally {
        setAssistantItemMutedSaving((prev) => {
          const next = { ...prev }
          delete next[assistantId]
          return next
        })
      }
    },
    [imei, g, isOwner, isGroupMember, onGroupUpdated, refreshGroup]
  )

  const openRolePromptModal = useCallback((assistantId: string, assistantName: string) => {
    const existingPrompt = (g?.assistant_configs?.[assistantId]?.rolePrompt ?? '').trim()
    setRolePromptModalTarget({ assistantId, assistantName })
    setRolePromptInput(existingPrompt)
    setRolePromptError(null)
  }, [g?.assistant_configs])

  const handleSaveRolePrompt = useCallback(async () => {
    if (!imei || !g || !rolePromptModalTarget) return
    setRolePromptSaving(true)
    setRolePromptError(null)
    try {
      const res = await updateGroupAssistantConfig(
        imei,
        g.group_id,
        rolePromptModalTarget.assistantId,
        { rolePrompt: rolePromptInput.trim() }
      )
      if (!res.success) {
        setRolePromptError(res.message ?? '保存失败')
        return
      }
      if (res.group) {
        setGroupData(res.group)
        onGroupUpdated?.(res.group)
      } else {
        await refreshGroup()
      }
      setRolePromptModalTarget(null)
      setRolePromptInput('')
    } catch (err) {
      const msg =
        (err as { response?: { data?: { detail?: string; message?: string } } })?.response?.data?.detail
        ?? (err as { response?: { data?: { detail?: string; message?: string } } })?.response?.data?.message
        ?? '保存失败'
      setRolePromptError(msg)
    } finally {
      setRolePromptSaving(false)
    }
  }, [imei, g, rolePromptModalTarget, rolePromptInput, onGroupUpdated, refreshGroup])

  const handleSaveServerUrl = () => {
    const trimmed = serverUrlEdit.trim()
    if (trimmed) {
      setChatAssistantUrl(trimmed)
      setServerSaved(true)
      setTimeout(() => setServerSaved(false), 2000)
    }
  }

  const handleSendMessage = () => {
    onSendMessage(toConversation())
    onClose()
  }

  const handleExitGroupMemberManagement = useCallback(() => {
    setShowGroupMemberManagement(false)
    setGroupManageMode(false)
    setAddMemberError(null)
  }, [])
  const isImConfigAssistant =
    target.type === 'assistant' &&
    (target.id === CONVERSATION_ID_IM_QQ || target.id === CONVERSATION_ID_IM_WEIXIN)
  const imConfigLabel =
    target.type === 'assistant' && target.id === CONVERSATION_ID_IM_QQ ? 'QQ' : '微信'
  const isTopoClawHomepage =
    target.type === 'assistant' && builtinProfileSlot === 'topoclaw' && (showLogButton || showRestartButton)
  const assistantTarget = target.type === 'assistant' ? target : null
  const assistantInfo = assistantTarget ? getCustomAssistantById(assistantTarget.id) : undefined
  const isBuiltinHomepage = target.type === 'assistant' && (showLogButton || showRestartButton)
  const canEditBuiltinAssistant = isBuiltinHomepage && builtinProfileSlot !== 'groupmanager'
  const builtinCreatorImei = imei?.trim() || 'IMEI'
  const builtinCreatorName = userName.trim()
  const builtinCreator = builtinCreatorName ? `${builtinCreatorImei}(${builtinCreatorName})` : builtinCreatorImei
  const localCreatorFallbackDisabled =
    target.type === 'assistant' && target.disableLocalCreatorFallback === true
  const assistantCreatorLine =
    target.type === 'assistant' && !isImConfigAssistant
      ? (
          target.creator_imei?.trim()
          || (localCreatorFallbackDisabled ? '' : assistantInfo?.creator_imei?.trim())
          || (localCreatorFallbackDisabled ? '' : (isBuiltinHomepage ? builtinCreator : ''))
          || (localCreatorFallbackDisabled ? '' : (assistantInfo?.assistantOrigin === 'created' ? (imei?.trim() || '当前设备账号') : ''))
          || '未知'
        )
      : ''
  const profileAvatarSrc =
    target.type === 'assistant'
      ? toAvatarSrc(target.avatar)
      : target.type === 'friend'
        ? toAvatarSrc(target.data.avatar)
        : undefined
  const canPreviewProfileAvatar = target.type !== 'group' && !!profileAvatarSrc
  const profileAvatarFileName =
    target.type === 'assistant'
      ? `${target.name || 'assistant'}.png`
      : target.type === 'friend'
        ? `${target.data.nickname || target.data.imei}.png`
        : 'avatar.png'
  const openProfileAvatarLightbox = () => {
    if (!profileAvatarSrc || !canPreviewProfileAvatar) return
    setImageLightbox({ dataUrl: profileAvatarSrc, fileName: profileAvatarFileName })
  }
  const conversationSummaryScope = (() => {
    if (target.type === 'friend') {
      const scopeId = String(target.data.imei || '').trim()
      if (!scopeId) return null
      return { scopeType: 'friend' as const, scopeId }
    }
    if (target.type === 'group' && !target.isFriendsGroup) {
      const scopeId = String(target.data.group_id || '').trim()
      if (!scopeId) return null
      return { scopeType: 'group' as const, scopeId }
    }
    return null
  })()
  const canShowConversationSummaryButton = !!conversationSummaryScope

  const openConversationSummaryModal = () => {
    if (!conversationSummaryScope) return
    setShowConversationSummaryModal(true)
    setConversationSummaryLoading(true)
    setConversationSummaryError(null)
    setConversationSummaryEntries([])
    void listConversationSummaries({
      scopeType: conversationSummaryScope.scopeType,
      scopeId: conversationSummaryScope.scopeId,
    })
      .then((res) => {
        setConversationSummaryEntries(res.entries)
      })
      .catch((e) => {
        setConversationSummaryError(`读取失败：${String(e)}`)
      })
      .finally(() => setConversationSummaryLoading(false))
  }

  const openWorkspaceProfileEditor = (kind: WorkspaceProfileKind) => {
    setShowProfileEditor(true)
    setProfileEditorKind(kind)
    setProfileEditorEditing(false)
    setProfileEditorPath('')
    setProfileEditorContent('')
    setProfileEditorHint(null)
    setProfileEditorError(null)
    setProfileEditorLoading(true)
    const reader = window.electronAPI?.readWorkspaceProfileFile
    if (!reader) {
      setProfileEditorLoading(false)
      setProfileEditorError('当前环境不支持读取工作区文件')
      return
    }
    void reader(kind)
      .then((res) => {
        if (!res?.ok) {
          setProfileEditorError(res?.error || '读取失败')
          return
        }
        setProfileEditorPath(res.path || '')
        setProfileEditorContent(res.content || '')
      })
      .catch((e) => {
        setProfileEditorError(`读取失败：${String(e)}`)
      })
      .finally(() => setProfileEditorLoading(false))
  }

  const saveWorkspaceProfileEditor = async (): Promise<boolean> => {
    setProfileEditorSaving(true)
    setProfileEditorHint(null)
    setProfileEditorError(null)
    const writer = window.electronAPI?.writeWorkspaceProfileFile
    if (!writer) {
      setProfileEditorSaving(false)
      setProfileEditorError('当前环境不支持写入工作区文件')
      return false
    }
    try {
      const res = await writer(profileEditorKind, profileEditorContent)
      if (!res?.ok) {
        setProfileEditorError(res?.error || '保存失败')
        return false
      }
      setProfileEditorPath(res.path || profileEditorPath)
      setProfileEditorHint(`${PROFILE_LABELS[profileEditorKind]}已保存`)
      return true
    } catch (e) {
      setProfileEditorError(`保存失败：${String(e)}`)
      return false
    } finally {
      setProfileEditorSaving(false)
    }
  }

  const restoreWorkspaceProfileDefault = () => {
    const ok = window.confirm(`确定将${PROFILE_LABELS[profileEditorKind]}恢复为默认模板吗？`)
    if (!ok) return
    setProfileEditorRestoring(true)
    setProfileEditorHint(null)
    setProfileEditorError(null)
    const reader = window.electronAPI?.readWorkspaceProfileDefaultFile
    if (!reader) {
      setProfileEditorRestoring(false)
      setProfileEditorError('当前环境不支持读取默认模板')
      return
    }
    void reader(profileEditorKind)
      .then((res) => {
        if (!res?.ok) {
          setProfileEditorError(res?.error || '恢复默认失败')
          return
        }
        setProfileEditorContent(res.content || '')
        setProfileEditorHint(`${PROFILE_LABELS[profileEditorKind]}已恢复默认模板，请点击保存生效`)
      })
      .catch((e) => {
        setProfileEditorError(`恢复默认失败：${String(e)}`)
      })
      .finally(() => setProfileEditorRestoring(false))
  }

  const handleProfileEditorToggleEdit = () => {
    if (!profileEditorEditing) {
      setProfileEditorEditing(true)
      setProfileEditorHint(null)
      setProfileEditorError(null)
      return
    }
    void saveWorkspaceProfileEditor().then((ok) => {
      if (ok) setProfileEditorEditing(false)
    })
  }

  const insertProfileSnippet = (snippet: string) => {
    const textarea = profileTextareaRef.current
    if (!textarea) {
      setProfileEditorContent((prev) => `${prev}${prev.endsWith('\n') || prev.length === 0 ? '' : '\n'}${snippet}`)
      return
    }
    const start = textarea.selectionStart ?? profileEditorContent.length
    const end = textarea.selectionEnd ?? profileEditorContent.length
    const next = `${profileEditorContent.slice(0, start)}${snippet}${profileEditorContent.slice(end)}`
    setProfileEditorContent(next)
    window.requestAnimationFrame(() => {
      textarea.focus()
      const caret = start + snippet.length
      textarea.setSelectionRange(caret, caret)
    })
  }

  const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms))

  const openQqConfigModal = async () => {
    setQqError('')
    setQqSuccess('')
    setShowQqModal(true)
    try {
      const cfg = await getBuiltinAssistantConfig()
      setQqAppId(cfg.qqAppId || '')
      setQqAppSecret(cfg.qqAppSecret || '')
      setQqAllowFrom((cfg.qqAllowFrom || '*').trim() || '*')
    } catch {
      setQqAppId('')
      setQqAppSecret('')
      setQqAllowFrom('*')
    }
  }

  const handleSaveQqConfig = async () => {
    const appId = qqAppId.trim()
    const appSecret = qqAppSecret.trim()
    const allowFrom = qqAllowFrom.trim() || '*'
    if (!appId) {
      setQqError('请填写 QQ AppID')
      return
    }
    if (!appSecret) {
      setQqError('请填写 QQ AppSecret')
      return
    }

    setQqSaving(true)
    setQqError('')
    setQqSuccess('')
    try {
      const saveRes = await saveBuiltinAssistantConfig({
        qqEnabled: true,
        qqAppId: appId,
        qqAppSecret: appSecret,
        qqAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setQqError(saveRes.error || '保存配置失败')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setQqError(`配置已保存，但重启内置服务失败：${restartRes.error || '未知错误'}`)
        return
      }
      setQqSuccess('QQ 通道已启用，内置服务重启成功。现在可在 QQ 私聊或群里 @机器人 使用。')
    } finally {
      setQqSaving(false)
    }
  }

  const handleTestQqConfig = async () => {
    const appId = qqAppId.trim()
    const appSecret = qqAppSecret.trim()
    const allowFrom = qqAllowFrom.trim() || '*'
    if (!appId) {
      setQqError('请填写 QQ AppID')
      return
    }
    if (!appSecret) {
      setQqError('请填写 QQ AppSecret')
      return
    }

    setQqTesting(true)
    setQqError('')
    setQqSuccess('正在测试连接，请稍候…')
    try {
      const before = await getBuiltinAssistantLogBuffer('topoclaw')
      const saveRes = await saveBuiltinAssistantConfig({
        qqEnabled: true,
        qqAppId: appId,
        qqAppSecret: appSecret,
        qqAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setQqError(saveRes.error || '保存测试配置失败')
        setQqSuccess('')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setQqError(`测试失败：内置服务重启失败（${restartRes.error || '未知错误'}）`)
        setQqSuccess('')
        return
      }

      const beforeLen = before.length
      let delta = ''
      for (let i = 0; i < 8; i += 1) {
        await sleep(1000)
        const after = await getBuiltinAssistantLogBuffer('topoclaw')
        delta = after.slice(Math.min(beforeLen, after.length))
        const text = delta.toLowerCase()
        if (text.includes('qq bot ready') || text.includes('qq channel enabled')) {
          setQqSuccess('测试成功：QQ 通道连接正常。你现在可以在 QQ 里直接发消息进行验证。')
          setQqError('')
          return
        }
        if (
          text.includes('qq sdk not installed') ||
          text.includes('qq app_id and secret not configured') ||
          text.includes('qq bot error') ||
          text.includes('failed to start channel qq')
        ) {
          setQqError('测试失败：请检查 AppID / AppSecret 是否正确，并确认网络可访问 QQ 平台。')
          setQqSuccess('')
          return
        }
      }

      setQqError('测试超时：未在日志中检测到明确成功信号，请打开日志进一步排查。')
      setQqSuccess('')
    } finally {
      setQqTesting(false)
    }
  }

  const startWeixinQrLogin = async (baseUrlRaw?: string) => {
    const baseUrl = (baseUrlRaw || weixinBaseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
    setWeixinQrLoading(true)
    setWeixinQrHint('正在获取微信登录二维码…')
    setWeixinError('')
    const loopId = Date.now()
    weixinPollLoopIdRef.current = loopId
    try {
      const qrRes = await getWeixinLoginQr({ baseUrl })
      if (!qrRes.ok) {
        setWeixinError(qrRes.error || '获取二维码失败')
        setWeixinQrHint('')
        return
      }
      const qrData = await toDataURL(qrRes.payload, { width: 260, margin: 1 })
      setWeixinQrDataUrl(qrData)
      setWeixinQrHint('请使用微信扫一扫，扫描二维码完成绑定')
      void pollWeixinQrLoop(loopId, qrRes.baseUrl || baseUrl, qrRes.qrcodeTicket)
    } catch (e) {
      setWeixinError(`获取二维码失败：${String(e)}`)
      setWeixinQrHint('')
    } finally {
      setWeixinQrLoading(false)
    }
  }

  const pollWeixinQrLoop = async (loopId: number, baseUrl: string, qrcodeTicket: string) => {
    while (weixinModalOpenRef.current && weixinPollLoopIdRef.current === loopId) {
      const statusRes = await pollWeixinLoginStatus({ baseUrl, qrcodeTicket })
      if (!statusRes.ok) {
        setWeixinQrHint(statusRes.error || '查询扫码状态失败')
        await sleep(1500)
        continue
      }
      const status = (statusRes.status || '').toLowerCase()
      if (status === 'scaned') {
        setWeixinQrHint('已扫码，请在微信中确认授权…')
      } else if (status === 'expired') {
        setWeixinQrHint('二维码已过期，正在刷新…')
        if (weixinPollLoopIdRef.current === loopId) {
          void startWeixinQrLogin(baseUrl)
        }
        return
      } else if (status === 'confirmed') {
        const botToken = (statusRes.botToken || '').trim()
        const finalBaseUrl = (statusRes.baseUrl || baseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
        if (!botToken) {
          setWeixinError('扫码已确认，但未获取到 botToken，请重试')
          return
        }
        setWeixinQrHint('扫码成功，正在保存配置并重启服务…')
        const saveRes = await saveBuiltinAssistantConfig({
          weixinEnabled: true,
          weixinBotToken: botToken,
          weixinBaseUrl: finalBaseUrl,
          weixinAllowFrom: weixinAllowFrom.trim() || '*',
        })
        if (!saveRes.ok) {
          setWeixinError(saveRes.error || '保存微信配置失败')
          return
        }
        setWeixinBotToken(botToken)
        setWeixinBaseUrl(finalBaseUrl)
        const restartRes = await restartBuiltinAssistant('topoclaw')
        if (!restartRes.ok) {
          setWeixinError(`扫码成功，但重启内置服务失败：${restartRes.error || '未知错误'}`)
          return
        }
        setWeixinSuccess('扫码绑定成功，微信通道已启用。')
        setWeixinError('')
        setWeixinQrHint('已绑定成功')
        return
      }
      await sleep(1200)
    }
  }

  const openWeixinConfigModal = async () => {
    setWeixinError('')
    setWeixinSuccess('')
    setWeixinQrHint('')
    setWeixinQrDataUrl('')
    setShowWeixinModal(true)
    try {
      const cfg = await getBuiltinAssistantConfig()
      setWeixinBotToken(cfg.weixinBotToken || '')
      const nextBaseUrl = (cfg.weixinBaseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
      setWeixinBaseUrl(nextBaseUrl)
      setWeixinAllowFrom((cfg.weixinAllowFrom || '*').trim() || '*')
      void startWeixinQrLogin(nextBaseUrl)
    } catch {
      setWeixinBotToken('')
      setWeixinBaseUrl(WEIXIN_DEFAULT_BASE_URL)
      setWeixinAllowFrom('*')
      void startWeixinQrLogin(WEIXIN_DEFAULT_BASE_URL)
    }
  }

  const handleSaveWeixinConfig = async () => {
    const botToken = weixinBotToken.trim()
    const baseUrl = weixinBaseUrl.trim() || WEIXIN_DEFAULT_BASE_URL
    const allowFrom = weixinAllowFrom.trim() || '*'

    setWeixinSaving(true)
    setWeixinError('')
    setWeixinSuccess('')
    try {
      const saveRes = await saveBuiltinAssistantConfig({
        weixinEnabled: true,
        weixinBotToken: botToken,
        weixinBaseUrl: baseUrl,
        weixinAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setWeixinError(saveRes.error || '保存配置失败')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setWeixinError(`配置已保存，但重启内置服务失败：${restartRes.error || '未知错误'}`)
        return
      }
      if (!botToken) {
        setWeixinSuccess('微信配置已保存。因未填写 botToken，服务重启后会进入扫码绑定流程，请在日志中查看二维码并用微信确认。')
        return
      }
      setWeixinSuccess('微信通道已启用，内置服务重启成功。你现在可以在微信侧发消息验证。')
    } finally {
      setWeixinSaving(false)
    }
  }

  const handleTestWeixinConfig = async () => {
    const botToken = weixinBotToken.trim()
    const baseUrl = weixinBaseUrl.trim() || WEIXIN_DEFAULT_BASE_URL
    const allowFrom = weixinAllowFrom.trim() || '*'

    setWeixinTesting(true)
    setWeixinError('')
    setWeixinSuccess('正在测试连接，请稍候…')
    try {
      const before = await getBuiltinAssistantLogBuffer('topoclaw')
      const saveRes = await saveBuiltinAssistantConfig({
        weixinEnabled: true,
        weixinBotToken: botToken,
        weixinBaseUrl: baseUrl,
        weixinAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setWeixinError(saveRes.error || '保存测试配置失败')
        setWeixinSuccess('')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setWeixinError(`测试失败：内置服务重启失败（${restartRes.error || '未知错误'}）`)
        setWeixinSuccess('')
        return
      }

      const beforeLen = before.length
      let delta = ''
      for (let i = 0; i < 10; i += 1) {
        await sleep(1000)
        const after = await getBuiltinAssistantLogBuffer('topoclaw')
        delta = after.slice(Math.min(beforeLen, after.length))
        const text = delta.toLowerCase()
        if (text.includes('weixin channel enabled') || text.includes('weixin monitor started')) {
          setWeixinSuccess('测试成功：微信通道已启动。你现在可以在微信侧发消息进行验证。')
          setWeixinError('')
          return
        }
        if (
          text.includes('请使用微信扫描') ||
          text.includes('weixin (ilink): 正在获取登录二维码') ||
          text.includes('已扫码，请在微信上确认登录')
        ) {
          setWeixinSuccess('测试中：已进入微信扫码绑定流程，请在日志二维码完成确认后再重试发送消息。')
          setWeixinError('')
          return
        }
        if (
          text.includes('微信扫码登录失败') ||
          text.includes('微信登录超时') ||
          text.includes('failed to start channel weixin') ||
          text.includes('weixin: base_url missing')
        ) {
          setWeixinError('测试失败：请检查 Base URL、网络连通性，或重新扫码绑定获取 botToken。')
          setWeixinSuccess('')
          return
        }
      }

      setWeixinError('测试超时：未检测到明确成功信号，请打开日志进一步排查。')
      setWeixinSuccess('')
    } finally {
      setWeixinTesting(false)
    }
  }

  return (
    <div className="contact-profile-panel">
      {g && isOwner && !showGroupMemberManagement && (
        <div className="contact-profile-top-right-group-toggles">
          <label
            className="contact-profile-group-discovery-toggle contact-profile-group-discovery-toggle-top"
            title="开启后，群聊进入编排模式（与自由发言/助手禁言互斥）"
          >
            <span>编排模式</span>
            <input
              type="checkbox"
              checked={groupTopRightMode === 'workflow'}
              disabled={groupTopRightModeSaving}
              onChange={(e) => {
                void applyGroupTopRightMode(e.target.checked ? 'workflow' : 'none')
              }}
            />
          </label>
          <label
            className="contact-profile-group-discovery-toggle contact-profile-group-discovery-toggle-top"
            title="开启后，群内新消息会同步给所有助手进行自由发言"
          >
            <span>自由发言</span>
            <input
              type="checkbox"
              checked={groupTopRightMode === 'free'}
              disabled={groupTopRightModeSaving}
              onChange={(e) => {
                void applyGroupTopRightMode(e.target.checked ? 'free' : 'none')
              }}
            />
          </label>
          <label
            className="contact-profile-group-discovery-toggle contact-profile-group-discovery-toggle-top"
            title="开启后，助手将不再参与群聊回复"
          >
            <span>助手禁言</span>
            <input
              type="checkbox"
              checked={groupTopRightMode === 'muted'}
              disabled={groupTopRightModeSaving}
              onChange={(e) => {
                void applyGroupTopRightMode(e.target.checked ? 'muted' : 'none')
              }}
            />
          </label>
        </div>
      )}
      <button
        type="button"
        className="contact-profile-close"
        onClick={showGroupMemberManagement ? handleExitGroupMemberManagement : onClose}
        aria-label={showGroupMemberManagement ? '返回主页' : '关闭'}
        title={showGroupMemberManagement ? '返回主页' : '关闭'}
      >
        ✕
      </button>
      <div className="contact-profile-content">
        <div className="contact-profile-center">
          <div className="contact-profile-header">
          <div
            className={`contact-profile-avatar-wrap ${target.type === 'group' ? 'contact-profile-avatar-wrap-group' : ''} ${canPreviewProfileAvatar ? 'contact-profile-avatar-previewable' : ''}`}
            onClick={openProfileAvatarLightbox}
            role={canPreviewProfileAvatar ? 'button' : undefined}
            tabIndex={canPreviewProfileAvatar ? 0 : undefined}
            onKeyDown={(e) => {
              if ((e.key === 'Enter' || e.key === ' ') && profileAvatarSrc) {
                e.preventDefault()
                openProfileAvatarLightbox()
              }
            }}
            title={canPreviewProfileAvatar ? '点击查看大图' : undefined}
          >
            {target.type === 'assistant' && (
              profileAvatarSrc ? (
                <img src={profileAvatarSrc} alt="" />
              ) : (
                <span className="contact-profile-avatar-letter">{target.name.slice(0, 1)}</span>
              )
            )}
            {target.type === 'friend' && (
              profileAvatarSrc ? (
                <img
                  src={profileAvatarSrc}
                  alt=""
                />
              ) : (
                <span className="contact-profile-avatar-letter">
                  {(target.data.nickname ?? target.data.imei).slice(0, 1)}
                </span>
              )
            )}
            {target.type === 'group' && (
              target.isFriendsGroup ? (
                <GroupAvatar {...getFriendsGroupAvatarSources(userAvatar, userName)} size={80} />
              ) : (
                <GroupAvatar
                  {...getGroupAvatarSourcesFromMembers(
                    (g ?? target.data)?.members ?? [],
                    friends,
                    getImei() ?? '',
                    userAvatar,
                    {
                      assistants: (g ?? target.data)?.assistants ?? ((g ?? target.data)?.assistant_enabled ? ['assistant'] : []),
                      assistantConfigs: (g ?? target.data)?.assistant_configs,
                      assistantNames: Object.fromEntries(
                        Object.entries((g ?? target.data)?.assistant_configs ?? {}).map(([id, cfg]) => [id, cfg.name || id])
                      ),
                    }
                  )}
                  size={80}
                />
              )
            )}
          </div>
          <h3 className="contact-profile-name">
            {target.type === 'assistant' && target.name}
            {target.type === 'friend' && (target.data.nickname ?? target.data.imei.slice(0, 8) + '...')}
            {target.type === 'group' && (target.isFriendsGroup ? '好友群' : target.data.name)}
          </h3>
          {target.type === 'assistant' && (() => {
            const a = getCustomAssistantById(target.id)
            const shouldShowAssistantMeta =
              !!a || !!target.baseUrl?.trim() || !!target.displayId?.trim() || isCustomAssistantId(target.id)
            if (!shouldShowAssistantMeta) return null
            const baseUrl = a?.baseUrl ?? target.baseUrl
            const multiSession = a ? hasMultiSession(a) : false
            const displayId =
              target.displayId?.trim()
              || (target.disableLocalCreatorFallback ? '' : (a?.displayId || ''))
            return (baseUrl || multiSession || displayId) ? (
              <>
                {displayId && (
                  <p className="contact-profile-sub" title="小助手唯一标识">
                    ID：{displayId}
                  </p>
                )}
                {a && (
                  <p className="contact-profile-sub">
                    {multiSession ? '支持多会话' : '不支持多会话'}
                  </p>
                )}
                {baseUrl && (
                  <p className="contact-profile-sub contact-profile-baseurl" title={baseUrl}>
                    服务地址：{baseUrl}
                  </p>
                )}
              </>
            ) : null
          })()}
          {target.type === 'assistant' && !isImConfigAssistant && (
            <p className="contact-profile-sub">创作者：{assistantCreatorLine}</p>
          )}
          {target.type === 'friend' && (
            <p className="contact-profile-sub">IMEI: {target.data.imei}</p>
          )}
          {target.type === 'group' && !target.isFriendsGroup && (
            <p className="contact-profile-sub">
              {((g ?? target.data)?.members?.length ?? 0) +
                ((g ?? target.data)?.assistants?.length ?? ((g ?? target.data)?.assistant_enabled ? 1 : 0))}{' '}
              位成员 · 创建于 {(g ?? target.data)?.created_at?.slice(0, 10) || '-'}
            </p>
          )}
          </div>
          <div className="contact-profile-footer">
            {showRestartButton && (
              <button
                type="button"
                className="contact-profile-btn-restart"
                onClick={async () => {
                  setRestartLoading(true)
                  try {
                    const res = await restartBuiltinAssistant(builtinProfileSlot)
                    if (res.ok) {
                      window.alert('内置小助手已重启')
                    } else {
                      window.alert('重启失败：' + (res.error ?? '未知错误'))
                    }
                  } finally {
                    setRestartLoading(false)
                  }
                }}
                disabled={restartLoading}
                title="重启内置小助手服务"
              >
                {restartLoading ? '重启中...' : '重启'}
              </button>
            )}
            {showLogButton && (
              <>
                <button
                  type="button"
                  className="contact-profile-btn-log"
                  onClick={() =>
                    window.dispatchEvent(
                      new CustomEvent(OPEN_BUILTIN_LOG_EVENT, { detail: { slot: builtinProfileSlot } })
                    )
                  }
                  title="查看内置小助手实时日志"
                >
                  日志
                </button>
                <button
                  type="button"
                  className="contact-profile-btn-export-log"
                  disabled={exportLogLoading}
                  onClick={async () => {
                    setExportLogLoading(true)
                    try {
                      const text = await getBuiltinAssistantLogBuffer(builtinProfileSlot)
                      if (!text.trim()) {
                        window.alert('暂无日志可导出')
                        return
                      }
                      const res = await exportBuiltinAssistantLogToFile(text)
                      if (res.canceled) return
                      if (res.ok) {
                        window.alert('已导出到：\n' + (res.path ?? ''))
                      } else {
                        window.alert('导出失败：' + (res.error ?? '未知错误'))
                      }
                    } finally {
                      setExportLogLoading(false)
                    }
                  }}
                  title="将当前缓冲的日志保存为 .txt 文件"
                >
                  {exportLogLoading ? '导出中...' : '导出日志到本地'}
                </button>
              </>
            )}
            {canEditBuiltinAssistant && (
              <button
                type="button"
                className="contact-profile-btn-log"
                onClick={() => {
                  if (!assistantTarget) return
                  const mappedId = ASSISTANT_ID_MAP[assistantTarget.id] ?? assistantTarget.id
                  const assistant =
                    getCustomAssistantById(assistantTarget.id) ?? getCustomAssistantById(mappedId)
                  if (!assistant || !isProtectedBuiltinAssistantId(assistant.id)) {
                    window.alert('未找到可编辑的内置小助手配置')
                    return
                  }
                  setEditingAssistant(assistant)
                }}
                title="编辑内置小助手"
              >
                编辑
              </button>
            )}
            {isTopoClawHomepage && (
              <>
                <button
                  type="button"
                  className="contact-profile-btn-log"
                  onClick={() => openWorkspaceProfileEditor('soul')}
                  title="编辑 SOUL.md"
                >
                  助手人格
                </button>
                <button
                  type="button"
                  className="contact-profile-btn-log"
                  onClick={() => openWorkspaceProfileEditor('memory')}
                  title="编辑 MEMORY.md"
                >
                  助手眼中的你
                </button>
              </>
            )}
            {canShowConversationSummaryButton && (
              <button
                type="button"
                className="contact-profile-btn-log"
                onClick={openConversationSummaryModal}
                title="查看该会话的摘要记录"
              >
                会话记录
              </button>
            )}
            {g && (
              <button
                type="button"
                className="contact-profile-btn-log"
                onClick={() => {
                  setShowGroupMemberManagement(true)
                  setAddMemberError(null)
                }}
                title="查看群成员与管理功能"
              >
                群成员管理
              </button>
            )}
            <button type="button" className="contact-profile-btn-send" onClick={handleSendMessage}>
              发消息
            </button>
          </div>
        </div>
        <div className="contact-profile-body">
          {isChatAssistant && (
            <div className="contact-profile-server-settings">
              <h4 className="contact-profile-server-title">服务器设置</h4>
              <p className="contact-profile-server-desc">修改聊天小助手的服务地址</p>
              <input
                type="url"
                className="contact-profile-server-input"
                placeholder="https://your-server.com/v10/"
                value={serverUrlEdit}
                onChange={(e) => setServerUrlEdit(e.target.value)}
              />
              <button
                type="button"
                className="contact-profile-server-save"
                onClick={handleSaveServerUrl}
              >
                {serverSaved ? '已保存' : '保存'}
              </button>
            </div>
          )}
          {isImConfigAssistant && (
            <div className="contact-profile-server-settings">
              <h4 className="contact-profile-server-title">{imConfigLabel} 配置</h4>
              <p className="contact-profile-server-desc">
                在会话页可进行连接开关与状态查看。
              </p>
              <button
                type="button"
                className="contact-profile-server-save"
                onClick={() => {
                  if (target.id === CONVERSATION_ID_IM_QQ) {
                    void openQqConfigModal()
                    return
                  }
                  void openWeixinConfigModal()
                }}
              >
                前往{imConfigLabel}配置
              </button>
            </div>
          )}
          {target.type === 'friend' && (
            <div className="contact-profile-friend-details">
              <h4 className="contact-profile-group-members-title">详细资料</h4>
              <div className="contact-profile-friend-details-grid">
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">个性签名</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.signature || '-')}
                  </span>
                </div>
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">性别</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.gender || '-')}
                  </span>
                </div>
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">地址</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.address || '-')}
                  </span>
                </div>
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">电话</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.phone || '-')}
                  </span>
                </div>
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">生日</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.birthday || '-')}
                  </span>
                </div>
                <div className="contact-profile-friend-detail-item">
                  <span className="contact-profile-friend-detail-label">偏好</span>
                  <span className="contact-profile-friend-detail-value">
                    {friendProfileLoading ? '加载中...' : (friendProfile?.preferences || '-')}
                  </span>
                </div>
              </div>
            </div>
          )}
          {g && showGroupMemberManagement && (
            <div className="contact-profile-group-members contact-profile-group-members-overlay">
              <div className="contact-profile-group-members-header">
                <h4 className="contact-profile-group-members-title">群成员管理</h4>
                <div className="contact-profile-group-header-actions">
                  {isGroupMember && (
                    <>
                      <button
                        type="button"
                        className={`contact-profile-group-manage-btn ${groupManageMode ? 'active' : ''}`}
                        onClick={() => setGroupManageMode((m) => !m)}
                      >
                        {groupManageMode ? '完成' : '管理'}
                      </button>
                      <button
                        type="button"
                        className="contact-profile-group-add-btn"
                        onClick={() => {
                          setShowAddMemberModal(true)
                          setAddMemberError(null)
                        }}
                        title="添加好友/小助手"
                      >
                        +
                      </button>
                      <button
                        type="button"
                        className="contact-profile-group-manage-btn"
                        onClick={() => { void handleLeaveOrDissolveGroup() }}
                        title={isOwner ? '解散群组' : '退出群组'}
                      >
                        {isOwner ? '解散' : '退出'}
                      </button>
                    </>
                  )}
                </div>
              </div>
              <div className="contact-profile-group-members-list">
                    {groupAssistants.map((aid) => {
                      const convId = Object.keys(ASSISTANT_ID_MAP).find((k) => ASSISTANT_ID_MAP[k] === aid) ?? aid
                      const a = assistantsForGroup.find((x) => x.id === convId) ?? assistantsForGroup.find((x) => x.id === aid)
                      const baseName = a?.name ?? (aid === 'assistant' ? '自动执行小助手' : aid)
                      const name = getGroupAssistantDisplayName(
                        aid,
                        baseName,
                        groupAssistants.map((id) => {
                          const mappedId = Object.keys(ASSISTANT_ID_MAP).find((k) => ASSISTANT_ID_MAP[k] === id) ?? id
                          const mapped = assistantsForGroup.find((x) => x.id === mappedId) ?? assistantsForGroup.find((x) => x.id === id)
                          return { id, name: mapped?.name ?? (id === 'assistant' ? '自动执行小助手' : id) }
                        }),
                        groupAssistantConfigsForDisplay
                      )
                      const custom = getCustomAssistantById(convId) ?? getCustomAssistantById(aid)
                      const configInGroup = g.assistant_configs?.[aid]
                      const configForDisplay = groupAssistantConfigsForDisplay[aid]
                      const avatar = a?.avatar
                        ?? (custom ? resolveCustomAssistantAvatarForDisplay(custom) : undefined)
                        ?? (aid === 'assistant' ? ASSISTANT_AVATAR : undefined)
                        ?? (aid === DEFAULT_GROUP_MANAGER_ASSISTANT_ID ? GROUP_MANAGER_AVATAR : undefined)
                      const creatorImeiInGroup = String(configForDisplay?.creator_imei || '').trim()
                      const creatorNickInGroup = String(configForDisplay?.creator_nickname || '').trim()
                      const creatorDisplayInGroup =
                        creatorImeiInGroup && creatorNickInGroup
                          ? `${creatorImeiInGroup}(${creatorNickInGroup})`
                          : (creatorImeiInGroup || creatorNickInGroup || '')
                      const assistantTarget: ProfileTarget = {
                        type: 'assistant',
                        id: convId,
                        name,
                        avatar,
                        baseUrl: configInGroup?.baseUrl ?? custom?.baseUrl,
                        creator_imei: creatorDisplayInGroup || undefined,
                        displayId: String(configForDisplay?.displayId || '').trim() || undefined,
                        disableLocalCreatorFallback: true,
                      }
                      const showGroupManagerToggle = isOwner && groupManageMode && custom && hasChat(custom)
                      const assistantCreatorImei = String(configForDisplay?.creator_imei || '').trim()
                      const assistantAddedBy = String(g.assistant_added_by?.[aid] || '').trim()
                      const canManageOwnAssistantMute = isGroupMember && (assistantCreatorImei === imei || assistantAddedBy === imei)
                      const canManageAssistantMute = isOwner || canManageOwnAssistantMute
                      const canSetRolePrompt = isOwner && groupManageMode && (
                        aid === 'assistant'
                        || convId === 'assistant'
                        || (!!custom && hasChat(custom))
                        || ((configInGroup?.capabilities ?? []).includes('chat'))
                      )
                      const isGroupManager = !!custom && hasGroupManager(custom)
                      return (
                        <div
                          key={`a-${aid}-${groupManagerRefresh}`}
                          className="contact-profile-group-member-item contact-profile-group-member-item-clickable"
                          role="button"
                          tabIndex={0}
                          title="查看主页"
                          onClick={() => setMemberProfileTarget(assistantTarget)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault()
                              setMemberProfileTarget(assistantTarget)
                            }
                          }}
                        >
                          <div className="contact-profile-group-member-avatar">
                            {avatar ? (
                              <img src={toAvatarSrcLikeContacts(avatar)} alt="" />
                            ) : (
                              <span className="contact-profile-avatar-letter">{name.slice(0, 1)}</span>
                            )}
                          </div>
                          <span
                            className="contact-profile-group-member-type-icon contact-profile-group-member-type-icon-assistant"
                            title="助手"
                            aria-label="助手"
                          >
                            AI
                          </span>
                          <span className="contact-profile-group-member-name">{name}</span>
                          {isGroupManager && (
                            <span className="contact-profile-group-manager-badge" title="群内未 @ 时由此助手统一回复">
                              群组管理者
                            </span>
                          )}
                          {canManageAssistantMute && (
                            <label
                              className="contact-profile-group-discovery-toggle"
                              title="开启后该助手在群内不再回复"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <span>禁言</span>
                              <input
                                type="checkbox"
                                checked={!!configForDisplay?.assistantMuted}
                                disabled={!!assistantItemMutedSaving[aid]}
                                onChange={(e) => {
                                  e.stopPropagation()
                                  void handleToggleAssistantItemMuted(aid, e.target.checked)
                                }}
                                onClick={(e) => e.stopPropagation()}
                              />
                            </label>
                          )}
                          {showGroupManagerToggle && (
                            <label
                              className="contact-profile-group-manager-toggle"
                              title="群内未 @ 任何小助手时，消息由此助手统一回复"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <input
                                type="checkbox"
                                checked={!!custom && hasGroupManager(custom)}
                                onChange={(e) => {
                                  e.stopPropagation()
                                  if (custom) {
                                    setAssistantGroupManager(custom.id, e.target.checked)
                                    setGroupManagerRefresh((k) => k + 1)
                                  }
                                }}
                                onClick={(e) => e.stopPropagation()}
                              />
                              <span>群组管理者</span>
                            </label>
                          )}
                          {canSetRolePrompt && (
                            <button
                              type="button"
                              className="contact-profile-group-role-btn"
                              onClick={(e) => {
                                e.stopPropagation()
                                openRolePromptModal(aid, name)
                              }}
                              title="为该助手设置群内角色提示词"
                            >
                              设置角色
                            </button>
                          )}
                          {groupManageMode && canRemoveGroupAssistant(aid) && (
                            <button
                              type="button"
                              className="contact-profile-group-member-remove"
                              onClick={(e) => {
                                e.stopPropagation()
                                void handleRemoveAssistant(aid)
                              }}
                              title="移出小助手"
                            >
                              ×
                            </button>
                          )}
                        </div>
                      )
                    })}
                    {(g.members ?? []).map((memberImei) => {
                      const f = friends.find((x) => x.imei === memberImei)
                      const displayName = f?.nickname ?? (memberImei === imei ? userName || '我' : memberImei.slice(0, 8) + '...')
                      const avatar = memberImei === imei ? userAvatar : f?.avatar
                      const friendTarget: ProfileTarget = {
                        type: 'friend',
                        data: f ?? {
                          imei: memberImei,
                          nickname: memberImei === imei ? userName || '我' : displayName,
                          avatar,
                          status: 'accepted',
                          addedAt: Date.now(),
                        },
                      }
                      return (
                        <div
                          key={memberImei}
                          className="contact-profile-group-member-item contact-profile-group-member-item-clickable"
                          role="button"
                          tabIndex={0}
                          title="查看主页"
                          onClick={() => setMemberProfileTarget(friendTarget)}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault()
                              setMemberProfileTarget(friendTarget)
                            }
                          }}
                        >
                          <div className="contact-profile-group-member-avatar">
                            {avatar ? (
                              <img src={avatar.startsWith('data:') ? avatar : `data:image/png;base64,${avatar}`} alt="" />
                            ) : (
                              <span className="contact-profile-avatar-letter">{displayName.slice(0, 1)}</span>
                            )}
                          </div>
                          <span
                            className="contact-profile-group-member-type-icon contact-profile-group-member-type-icon-user"
                            title="用户"
                            aria-label="用户"
                          >
                            U
                          </span>
                          <span className="contact-profile-group-member-name">{displayName}</span>
                          {groupManageMode && canRemoveGroupMember(memberImei) && (
                            <button
                              type="button"
                              className="contact-profile-group-member-remove"
                              onClick={(e) => {
                                e.stopPropagation()
                                void handleRemoveMember(memberImei)
                              }}
                              title="移出群组"
                            >
                              ×
                            </button>
                          )}
                        </div>
                      )
                    })}
              </div>
              {addMemberError && (
                <p className="contact-profile-group-error">{addMemberError}</p>
              )}
            </div>
          )}
        </div>
      </div>
      {memberProfileTarget && (
        <div className="contact-profile-modal-overlay" onClick={() => setMemberProfileTarget(null)}>
          <div className="contact-profile-member-homepage-modal" onClick={(e) => e.stopPropagation()}>
            <ContactProfilePanel
              target={memberProfileTarget}
              friends={friends}
              assistantsForGroup={assistantsForGroup}
              userAvatar={userAvatar}
              userName={userName}
              onClose={() => setMemberProfileTarget(null)}
              onSendMessage={onSendMessage}
              onGroupUpdated={onGroupUpdated}
              onGroupRemoved={onGroupRemoved}
              onOpenWorkflowWorkspace={onOpenWorkflowWorkspace}
            />
          </div>
        </div>
      )}
      {showProfileEditor && (
        <div className="contact-profile-modal-overlay" onClick={() => !profileEditorSaving && setShowProfileEditor(false)}>
          <div className="contact-profile-modal contact-profile-file-editor-modal" onClick={(e) => e.stopPropagation()}>
            <div className="contact-profile-modal-header">
              <h4>{PROFILE_LABELS[profileEditorKind]}</h4>
              <div className="contact-profile-modal-header-actions">
                <button
                  type="button"
                  className={`contact-profile-modal-edit-btn ${profileEditorEditing ? 'is-editing' : ''}`}
                  onClick={handleProfileEditorToggleEdit}
                  disabled={profileEditorLoading || profileEditorSaving || profileEditorRestoring}
                  title={profileEditorEditing ? '完成并保存' : '编辑'}
                  aria-label={profileEditorEditing ? '完成' : '编辑'}
                >
                  {profileEditorEditing ? '完成' : '✎'}
                </button>
                <button
                  type="button"
                  className="contact-profile-modal-close"
                  onClick={() => setShowProfileEditor(false)}
                  disabled={profileEditorSaving}
                  aria-label="关闭"
                >
                  ✕
                </button>
              </div>
            </div>
            <div className="contact-profile-modal-body contact-profile-file-editor-body">
              {profileEditorLoading ? (
                <p className="contact-profile-sub">读取中...</p>
              ) : (
                <>
                  <div className="contact-profile-file-editor-card">
                    <div className="contact-profile-file-editor-toolbar">
                      <p className="contact-profile-file-editor-card-title">快捷插入</p>
                      <button
                        type="button"
                        className="contact-profile-file-editor-reset"
                        onClick={restoreWorkspaceProfileDefault}
                        disabled={!profileEditorEditing || profileEditorLoading || profileEditorSaving || profileEditorRestoring}
                      >
                        {profileEditorRestoring ? '恢复中...' : '恢复默认'}
                      </button>
                    </div>
                    <div className="contact-profile-file-editor-snippets">
                      {PROFILE_SNIPPETS[profileEditorKind].map((snippet) => (
                        <button
                          key={snippet}
                          type="button"
                          className="contact-profile-file-editor-chip"
                          onClick={() => insertProfileSnippet(snippet)}
                          disabled={!profileEditorEditing || profileEditorSaving || profileEditorRestoring}
                        >
                          {snippet.split('\n')[0]}
                        </button>
                      ))}
                    </div>
                  </div>
                  <textarea
                    ref={profileTextareaRef}
                    className="contact-profile-file-editor-textarea"
                    value={profileEditorContent}
                    onChange={(e) => setProfileEditorContent(e.target.value)}
                    readOnly={!profileEditorEditing}
                    placeholder="在这里编辑内容"
                  />
                </>
              )}
              {profileEditorError && <p className="contact-profile-group-error">{profileEditorError}</p>}
              {profileEditorHint && <p className="contact-profile-sub">{profileEditorHint}</p>}
            </div>
          </div>
        </div>
      )}
      {showConversationSummaryModal && (
        <div
          className="contact-profile-modal-overlay"
          onClick={() => {
            if (conversationSummaryLoading) return
            setShowConversationSummaryModal(false)
          }}
        >
          <div className="contact-profile-modal contact-profile-conversation-summary-modal" onClick={(e) => e.stopPropagation()}>
            <div className="contact-profile-modal-header">
              <h4>会话记录</h4>
              <button
                type="button"
                className="contact-profile-modal-close"
                onClick={() => setShowConversationSummaryModal(false)}
                aria-label="关闭"
              >
                ✕
              </button>
            </div>
            <div className="contact-profile-modal-body">
              <p className="contact-profile-sub">
                最新摘要默认用于记忆，历史摘要仅作存档（文件：{getConversationSummaryFileName()}）。
              </p>
              {conversationSummaryLoading ? (
                <p className="contact-profile-sub">读取中...</p>
              ) : conversationSummaryEntries.length === 0 ? (
                <p className="contact-profile-sub">暂无摘要记录（从功能启用后开始累计）。</p>
              ) : (
                <div className="contact-profile-conversation-summary-list">
                  {conversationSummaryEntries.map((entry, idx) => (
                    <div key={entry.id} className="contact-profile-conversation-summary-item">
                      <div className="contact-profile-conversation-summary-head">
                        <span>{new Date(entry.createdAt).toLocaleString()}</span>
                        {idx === 0 && (
                          <span className="contact-profile-group-manager-badge" title="当前会话最新摘要">
                            最新
                          </span>
                        )}
                      </div>
                      <p className="contact-profile-conversation-summary-text">{entry.summary}</p>
                    </div>
                  ))}
                </div>
              )}
              {conversationSummaryError && <p className="contact-profile-group-error">{conversationSummaryError}</p>}
            </div>
          </div>
        </div>
      )}
      {rolePromptModalTarget && (
        <div
          className="contact-profile-modal-overlay"
          onClick={() => {
            if (rolePromptSaving) return
            setRolePromptModalTarget(null)
            setRolePromptError(null)
          }}
        >
          <div className="contact-profile-modal" onClick={(e) => e.stopPropagation()}>
            <div className="contact-profile-modal-header">
              <h4>设置角色：{rolePromptModalTarget.assistantName}</h4>
              <button
                type="button"
                className="contact-profile-modal-close"
                onClick={() => {
                  if (rolePromptSaving) return
                  setRolePromptModalTarget(null)
                  setRolePromptError(null)
                }}
                aria-label="关闭"
              >
                ✕
              </button>
            </div>
            <div className="contact-profile-modal-body">
              <p className="contact-profile-sub contact-profile-roleprompt-tip">
                该提示词会在每次给此助手发消息时作为「用户设置角色提示词」注入。请写明长期角色、边界与语气，不要写本轮具体问题。
              </p>
              <textarea
                className="contact-profile-roleprompt-input"
                placeholder="示例：你是群内财务助手，回答要简洁，优先给出结论与风险提示。"
                value={rolePromptInput}
                onChange={(e) => setRolePromptInput(e.target.value)}
                rows={8}
                disabled={rolePromptSaving}
              />
              {rolePromptError && <p className="contact-profile-group-error">{rolePromptError}</p>}
              <div className="contact-profile-roleprompt-actions">
                <button
                  type="button"
                  className="contact-profile-group-manage-btn"
                  onClick={() => {
                    if (rolePromptSaving) return
                    setRolePromptModalTarget(null)
                    setRolePromptError(null)
                  }}
                  disabled={rolePromptSaving}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="contact-profile-btn-send contact-profile-roleprompt-save-btn"
                  onClick={() => { void handleSaveRolePrompt() }}
                  disabled={rolePromptSaving}
                >
                  {rolePromptSaving ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {showAddMemberModal && g && (
        <AddGroupMemberModal
          friends={friends}
          assistantsForGroup={assistantsForGroup}
          currentMembers={g.members ?? []}
          currentAssistants={groupAssistants}
          currentAssistantConfigs={g.assistant_configs}
          assistantIdMap={ASSISTANT_ID_MAP}
          onAdd={handleAddMember}
          onClose={() => {
            setShowAddMemberModal(false)
            setAddMemberError(null)
          }}
          loading={addMemberLoading}
          error={addMemberError}
        />
      )}
      {editingAssistant && (
        <EditAssistantModal
          assistant={editingAssistant}
          onClose={() => setEditingAssistant(null)}
          onSaved={() => setEditingAssistant(null)}
        />
      )}
      {showQqModal && (
        <div className="contact-profile-modal-overlay" onClick={() => !qqSaving && !qqTesting && setShowQqModal(false)}>
          <div className="contact-profile-modal contact-profile-conversation-summary-modal" onClick={(e) => e.stopPropagation()}>
            <div className="contact-profile-modal-header">
              <h4>注册 QQ 通道</h4>
              <button
                type="button"
                className="contact-profile-modal-close"
                onClick={() => setShowQqModal(false)}
                disabled={qqSaving || qqTesting}
                aria-label="关闭"
              >
                ✕
              </button>
            </div>
            <div className="contact-profile-modal-body">
              <p className="contact-profile-sub">
                输入你在 QQ 机器人平台申请的 AppID 和 AppSecret。保存后会自动重启内置 TopoClaw 服务。
              </p>
              <div className="settings-group">
                <label>QQ AppID</label>
                <input
                  type="text"
                  className="settings-input"
                  value={qqAppId}
                  onChange={(e) => setQqAppId(e.target.value)}
                  placeholder="如：1903678999"
                />
              </div>
              <div className="settings-group">
                <label>QQ AppSecret</label>
                <input
                  type="password"
                  className="settings-input"
                  value={qqAppSecret}
                  onChange={(e) => setQqAppSecret(e.target.value)}
                  placeholder="请输入 AppSecret"
                />
              </div>
              <div className="settings-group">
                <label>Allow From（可选，逗号分隔，默认 *）</label>
                <input
                  type="text"
                  className="settings-input"
                  value={qqAllowFrom}
                  onChange={(e) => setQqAllowFrom(e.target.value)}
                  placeholder="* 或 user_openid_1,user_openid_2"
                />
              </div>
              {qqError && <div className="settings-error">{qqError}</div>}
              {qqSuccess && <div className="settings-success">{qqSuccess}</div>}
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setShowQqModal(false)}
                  disabled={qqSaving || qqTesting}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void handleTestQqConfig()}
                  disabled={qqSaving || qqTesting}
                >
                  {qqTesting ? '测试中…' : '测试连接'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={handleSaveQqConfig}
                  disabled={qqSaving || qqTesting}
                >
                  {qqSaving ? '保存中…' : '保存并启用'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {showWeixinModal && (
        <div
          className="contact-profile-modal-overlay"
          onClick={() => !weixinSaving && !weixinTesting && setShowWeixinModal(false)}
        >
          <div className="contact-profile-modal contact-profile-conversation-summary-modal" onClick={(e) => e.stopPropagation()}>
            <div className="contact-profile-modal-header">
              <h4>注册微信通道</h4>
              <button
                type="button"
                className="contact-profile-modal-close"
                onClick={() => setShowWeixinModal(false)}
                disabled={weixinSaving || weixinTesting}
                aria-label="关闭"
              >
                ✕
              </button>
            </div>
            <div className="contact-profile-modal-body">
              <p className="contact-profile-sub">
                可直接填写 botToken，或留空后保存并重启以触发扫码绑定。保存后会自动重启内置 TopoClaw 服务。
              </p>
              <div className="settings-group">
                <label>微信 BotToken（可选）</label>
                <input
                  type="password"
                  className="settings-input"
                  value={weixinBotToken}
                  onChange={(e) => setWeixinBotToken(e.target.value)}
                  placeholder="留空则重启后走扫码绑定"
                />
              </div>
              <div className="settings-group">
                <label>微信 Base URL</label>
                <input
                  type="text"
                  className="settings-input"
                  value={weixinBaseUrl}
                  onChange={(e) => setWeixinBaseUrl(e.target.value)}
                  placeholder={WEIXIN_DEFAULT_BASE_URL}
                />
              </div>
              <div className="settings-group">
                <label>微信登录二维码</label>
                <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                  {weixinQrDataUrl ? (
                    <img src={weixinQrDataUrl} alt="微信登录二维码" style={{ width: 180, height: 180, borderRadius: 8, border: '1px solid #eee' }} />
                  ) : (
                    <div
                      style={{
                        width: 180,
                        height: 180,
                        borderRadius: 8,
                        border: '1px dashed #ccc',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        color: '#999',
                        fontSize: 12,
                      }}
                    >
                      {weixinQrLoading ? '加载中…' : '暂无二维码'}
                    </div>
                  )}
                  <button
                    type="button"
                    className="settings-btn settings-btn-secondary"
                    onClick={() => void startWeixinQrLogin()}
                    disabled={weixinQrLoading || weixinSaving || weixinTesting}
                  >
                    {weixinQrLoading ? '获取中…' : '刷新二维码'}
                  </button>
                </div>
                {weixinQrHint && <div className="contact-profile-sub">{weixinQrHint}</div>}
              </div>
              <div className="settings-group">
                <label>Allow From（可选，逗号分隔，默认 *）</label>
                <input
                  type="text"
                  className="settings-input"
                  value={weixinAllowFrom}
                  onChange={(e) => setWeixinAllowFrom(e.target.value)}
                  placeholder="* 或 user_id_1,user_id_2"
                />
              </div>
              {weixinError && <div className="settings-error">{weixinError}</div>}
              {weixinSuccess && <div className="settings-success">{weixinSuccess}</div>}
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setShowWeixinModal(false)}
                  disabled={weixinSaving || weixinTesting}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void handleTestWeixinConfig()}
                  disabled={weixinSaving || weixinTesting}
                >
                  {weixinTesting ? '测试中…' : '测试连接'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={handleSaveWeixinConfig}
                  disabled={weixinSaving || weixinTesting}
                >
                  {weixinSaving ? '保存中…' : '保存并启用'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      <ChatImageLightbox payload={imageLightbox} onClose={() => setImageLightbox(null)} />
    </div>
  )
}

function AddGroupMemberModal({
  friends,
  assistantsForGroup,
  currentMembers,
  currentAssistants,
  currentAssistantConfigs,
  assistantIdMap,
  onAdd,
  onClose,
  loading,
  error,
}: {
  friends: Friend[]
  assistantsForGroup: AssistantForGroup[]
  currentMembers: string[]
  currentAssistants: string[]
  currentAssistantConfigs?: Record<string, { displayId?: string }>
  assistantIdMap: Record<string, string>
  onAdd: (item: { type: 'friend'; imei: string } | { type: 'assistant'; id: string }) => void
  onClose: () => void
  loading: boolean
  error: string | null
}) {
  const friendsToAdd = friends.filter((f) => f.status === 'accepted' && !currentMembers.includes(f.imei))
  const existingDisplayIds = new Set(
    currentAssistants
      .map((aid) => String(currentAssistantConfigs?.[aid]?.displayId || '').trim())
      .filter(Boolean)
  )
  const assistantsToAdd = assistantsForGroup.filter((a) => {
    const backendId = assistantIdMap[a.id] ?? a.id
    const candidateDisplayId = String(a.displayId || '').trim()
    if (candidateDisplayId) {
      // Prefer displayId for de-duplication so same assistantId from different creators can coexist.
      return !existingDisplayIds.has(candidateDisplayId)
    }
    return !currentAssistants.includes(backendId)
  })

  return (
    <div className="contact-profile-modal-overlay" onClick={onClose}>
      <div className="contact-profile-modal" onClick={(e) => e.stopPropagation()}>
        <div className="contact-profile-modal-header">
          <h4>添加成员</h4>
          <button type="button" className="contact-profile-modal-close" onClick={onClose} aria-label="关闭">
            ✕
          </button>
        </div>
        <div className="contact-profile-modal-body">
          {error && <p className="contact-profile-group-error">{error}</p>}
          {friendsToAdd.length === 0 && assistantsToAdd.length === 0 ? (
            <p className="contact-profile-modal-empty">暂无可添加的好友或小助手</p>
          ) : (
            <>
              {assistantsToAdd.length > 0 && (
                <div className="contact-profile-modal-section">
                  <p className="contact-profile-modal-section-title">小助手</p>
                  {assistantsToAdd.map((a) => (
                    <button
                      key={a.id}
                      type="button"
                      className="contact-profile-modal-item"
                      onClick={() => onAdd({ type: 'assistant', id: a.id })}
                      disabled={loading}
                    >
                      <div className="contact-profile-group-member-avatar">
                        {a.avatar ? (
                          <img src={toAvatarSrcLikeContacts(a.avatar)} alt="" />
                        ) : (
                          <span className="contact-profile-avatar-letter">{a.name.slice(0, 1)}</span>
                        )}
                      </div>
                      <span>{a.name}</span>
                    </button>
                  ))}
                </div>
              )}
              {friendsToAdd.length > 0 && (
                <div className="contact-profile-modal-section">
                  <p className="contact-profile-modal-section-title">好友</p>
                  {friendsToAdd.map((f) => (
                    <button
                      key={f.imei}
                      type="button"
                      className="contact-profile-modal-item"
                      onClick={() => onAdd({ type: 'friend', imei: f.imei })}
                      disabled={loading}
                    >
                      <div className="contact-profile-group-member-avatar">
                        {f.avatar ? (
                          <img src={f.avatar.startsWith('data:') ? f.avatar : `data:image/png;base64,${f.avatar}`} alt="" />
                        ) : (
                          <span className="contact-profile-avatar-letter">{(f.nickname ?? f.imei).slice(0, 1)}</span>
                        )}
                      </div>
                      <span>{f.nickname ?? f.imei.slice(0, 8) + '...'}</span>
                    </button>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
