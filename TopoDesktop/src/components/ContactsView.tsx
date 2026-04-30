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

import { useState, useEffect } from 'react'
import { getFriends, getGroups, getProfile, getCustomAssistantsFromCloud } from '../services/api'
import { getImei, getValidatedImei } from '../services/storage'
import {
  getVisibleCustomAssistants,
  getCustomAssistantById,
  hasMultiSession,
  mergeCloudAssistantsAndEnsureDefaultTopo,
  ensureDefaultBuiltinAssistantsAndMaybeSync,
  resolveCustomAssistantAvatarForDisplay,
} from '../services/customAssistants'
import type { Friend, GroupInfo } from '../services/api'
import type { Conversation } from '../types/conversation'
import {
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
  CONVERSATION_ID_IM_QQ,
  CONVERSATION_ID_IM_WEIXIN,
} from '../types/conversation'
import { GroupAvatar } from './GroupAvatar'
import { ContactProfilePanel } from './ContactProfilePanel'
import { getGroupAvatarSourcesFromMembers } from '../utils/groupAvatar'
import { ASSISTANT_AVATAR, SKILL_LEARNING_AVATAR, CUSTOMER_SERVICE_AVATAR } from '../constants/assistants'
import { toAvatarSrcLikeContacts } from '../utils/avatar'
import { OnlineStatusManager } from '../services/onlineStatusManager'
import './ContactsView.css'

type ProfileTarget =
  | { type: 'assistant'; id: string; name: string; avatar?: string; baseUrl?: string; displayId?: string }
  | { type: 'friend'; data: Friend }
  | { type: 'group'; data: GroupInfo; isFriendsGroup?: boolean }

interface ContactsViewProps {
  onSelectConversation?: (c: Conversation) => void
  /** 刷新键，变化时重新加载（如添加小助手后） */
  refreshKey?: number
  /** 点击通讯录标题时触发，用于手动刷新 */
  onTitleClick?: () => void
}

const DEFAULT_ASSISTANTS = [
  { id: CONVERSATION_ID_ASSISTANT, name: '自动执行小助手', avatar: ASSISTANT_AVATAR },
  { id: CONVERSATION_ID_IM_QQ, name: 'QQ' },
  { id: CONVERSATION_ID_IM_WEIXIN, name: '微信' },
  { id: CONVERSATION_ID_SKILL_LEARNING, name: '技能学习小助手', avatar: SKILL_LEARNING_AVATAR },
  { id: CONVERSATION_ID_CUSTOMER_SERVICE, name: '人工客服', avatar: CUSTOMER_SERVICE_AVATAR },
]
const HIDDEN_BUILTIN_ASSISTANT_IDS = new Set([
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
])
const VISIBLE_DEFAULT_ASSISTANTS = DEFAULT_ASSISTANTS.filter((a) => !HIDDEN_BUILTIN_ASSISTANT_IDS.has(a.id))
const GROUP_BLOCKED_ASSISTANT_IDS = new Set([CONVERSATION_ID_IM_QQ, CONVERSATION_ID_IM_WEIXIN])
const DEFAULT_ASSISTANT_NAMES: Record<string, string> = {
  assistant: '自动执行小助手',
  skill_learning: '技能学习小助手',
  chat_assistant: '聊天小助手',
  customer_service: '人工客服',
}

function toGroupConversationId(groupId: string): string {
  let raw = String(groupId || '').trim()
  while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
  return raw ? `group_${raw}` : 'group_'
}

/** 未选中联系人时右侧主页区说明（文案独立撰写，避免与外部设计稿逐字雷同） */
const CONTACTS_HOME_EMPTY_COPY =
  '小助手、群组与好友都列在左侧；选中任意一项后，可在此查看资料并发起聊天，方便你快速找到目标。'

function ContactsHomeEmptyIllustration() {
  return (
    <div className="contacts-home-empty-art" aria-hidden>
      <svg className="contacts-home-empty-svg" viewBox="0 0 120 120" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="60" cy="60" r="52" fill="var(--color-bg-tertiary, #f3f4f6)" />
        <rect x="36" y="34" width="48" height="58" rx="6" stroke="var(--color-border, #e5e7eb)" strokeWidth="2" fill="var(--color-bg, #fff)" />
        <path d="M44 42h32M44 52h24M44 62h28" stroke="var(--color-text-muted, #9ca3af)" strokeWidth="1.5" strokeLinecap="round" />
        <circle cx="72" cy="48" r="8" fill="rgb(199, 252, 252)" opacity="0.85" />
        <path d="M68 48l2.5 2.5L76 45" stroke="var(--color-primary, #6366f1)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="88" cy="72" r="10" stroke="var(--color-border, #d1d5db)" strokeWidth="1.5" fill="var(--color-bg, #fff)" />
        <circle cx="88" cy="69" r="3" fill="var(--color-text-muted, #9ca3af)" />
        <path d="M82 80c2-3 4-4 6-4s4 1 6 4" stroke="var(--color-text-muted, #9ca3af)" strokeWidth="1.2" strokeLinecap="round" />
      </svg>
    </div>
  )
}

export function ContactsView({ onSelectConversation, refreshKey = 0, onTitleClick }: ContactsViewProps) {
  const [friends, setFriends] = useState<Friend[]>([])
  const [onlineUsers, setOnlineUsers] = useState<Set<string>>(new Set())
  const [groups, setGroups] = useState<GroupInfo[]>([])
  const [customAssistants, setCustomAssistants] = useState<{ id: string; name: string; avatar?: string; baseUrl?: string; displayId?: string }[]>([])
  const [userAvatar, setUserAvatar] = useState<string | undefined>()
  const [userName, setUserName] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [expanded, setExpanded] = useState({ assistant: true, group: true, friend: true })
  const [profileTarget, setProfileTarget] = useState<ProfileTarget | null>(null)
  const mapVisibleCustomAssistants = () =>
    getVisibleCustomAssistants().map((a) => ({
      id: a.id,
      name: a.name,
      avatar: resolveCustomAssistantAvatarForDisplay(a),
      baseUrl: a.baseUrl,
      displayId: a.displayId,
    }))

  useEffect(() => {
    const load = async () => {
      const imei = getValidatedImei()
      if (!imei) {
        await ensureDefaultBuiltinAssistantsAndMaybeSync(undefined)
        setCustomAssistants(mapVisibleCustomAssistants())
        setLoading(false)
        return
      }
      try {
        // refreshKey > 0 时加防缓存参数，确保删除/添加后拉取最新数据
        const cloudAssistants = await getCustomAssistantsFromCloud(imei, refreshKey > 0 ? Date.now() : undefined)
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
          creator_avatar: a.creator_avatar,
        }))
        // 以云端为准；合并后保证默认内置 TopoClaw，必要时回写云端
        await mergeCloudAssistantsAndEnsureDefaultTopo(cloudItems, imei)
        setCustomAssistants(mapVisibleCustomAssistants())
        const [f, g, profile] = await Promise.all([
          getFriends(imei).catch(() => []),
          getGroups(imei).catch(() => []),
          getProfile(imei).catch(() => null),
        ])
        setFriends(f)
        setGroups(g)
        setUserAvatar(profile?.avatar)
        setUserName(profile?.name ?? '')
      } catch {
        setCustomAssistants(mapVisibleCustomAssistants())
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [refreshKey])

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

  const acceptedFriends = friends
    .filter((f) => f.status === 'accepted')
    .sort((a, b) => {
      const aOnline = onlineUsers.has(a.imei)
      const bOnline = onlineUsers.has(b.imei)
      if (aOnline && !bOnline) return -1
      if (!aOnline && bOnline) return 1
      return b.addedAt - a.addedAt
    })
  const acceptedOnlineCount = acceptedFriends.filter((f) => onlineUsers.has(f.imei)).length

  const handleAssistantClick = (id: string, name: string, avatar?: string, baseUrl?: string, displayId?: string) => {
    setProfileTarget({ type: 'assistant', id, name, avatar, baseUrl, displayId })
  }

  const handleGroupClick = (g: GroupInfo) => {
    setProfileTarget({ type: 'group', data: g })
  }

  const handleFriendClick = (f: Friend) => {
    setProfileTarget({ type: 'friend', data: f })
  }

  const handleProfileSendMessage = (c: Conversation) => {
    onSelectConversation?.(c)
    setProfileTarget(null)
  }
  const toConversationFromTarget = (target: ProfileTarget): Conversation => {
    switch (target.type) {
      case 'assistant': {
        const custom = getCustomAssistantById(target.id)
        const baseUrl = custom?.baseUrl ?? target.baseUrl
        const multiSessionEnabled = custom ? (hasMultiSession(custom) ? true : undefined) : undefined
        return {
          id: target.id,
          name: target.name,
          avatar: target.avatar,
          lastMessageTime: Date.now(),
          type: 'assistant',
          ...(baseUrl && { baseUrl }),
          ...(target.displayId && { displayId: target.displayId }),
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
      case 'group': {
        const groupAssistants = target.data.assistants ?? (target.data.assistant_enabled ? ['assistant'] : [])
        const customAssistantNameMap = new Map(customAssistants.map((a) => [a.id, a.name]))
        return {
          id: toGroupConversationId(target.data.group_id),
          name: target.data.name,
          lastMessageTime: Date.now(),
          type: 'group',
          members: target.data.members ?? [],
          assistants: groupAssistants.map((aid) => ({
            id: aid,
            name: customAssistantNameMap.get(aid) ?? DEFAULT_ASSISTANT_NAMES[aid] ?? aid,
          })),
        }
      }
      default:
        return { id: '', name: '', lastMessageTime: 0, type: 'friend' }
    }
  }

  const handleItemDoubleClick = (target: ProfileTarget) => {
    onSelectConversation?.(toConversationFromTarget(target))
    setProfileTarget(null)
  }

  return (
    <div className="contacts-view">
      <div className="contacts-main">
        <div className="contacts-header">
          <h2
            className={onTitleClick ? 'contacts-header-title-clickable' : ''}
            onClick={onTitleClick}
            title={onTitleClick ? '点击刷新' : undefined}
          >
            通讯录
          </h2>
        </div>
        <div className="contacts-content">
        {loading ? (
          <div className="contacts-loading">加载中...</div>
        ) : (
          <>
            <div className="contacts-section">
              <div
                className="contacts-section-header"
                onClick={() => setExpanded((e) => ({ ...e, assistant: !e.assistant }))}
              >
                <span>小助手</span>
                <span className="contacts-arrow">{expanded.assistant ? '▼' : '▶'}</span>
              </div>
              {expanded.assistant && (
                <div className="contacts-section-body">
                  {VISIBLE_DEFAULT_ASSISTANTS.map((a) => (
                    <div
                      key={a.id}
                      className="contacts-item"
                      onClick={() => handleAssistantClick(a.id, a.name, a.avatar)}
                      onDoubleClick={() => handleItemDoubleClick({ type: 'assistant', id: a.id, name: a.name, avatar: a.avatar })}
                    >
                      <div className="contacts-item-avatar">
                        {a.avatar ? (
                          <img src={a.avatar} alt="" />
                        ) : (
                          a.name.slice(0, 1)
                        )}
                      </div>
                      <span>{a.name}</span>
                    </div>
                  ))}
                  {customAssistants.map((a) => {
                    const avatarSrc = toAvatarSrcLikeContacts(a.avatar) ?? a.avatar
                    return (
                      <div
                        key={a.id}
                        className="contacts-item"
                        onClick={() => handleAssistantClick(a.id, a.name, avatarSrc, a.baseUrl, a.displayId)}
                        onDoubleClick={() => handleItemDoubleClick({ type: 'assistant', id: a.id, name: a.name, avatar: avatarSrc, baseUrl: a.baseUrl, displayId: a.displayId })}
                      >
                        <div className="contacts-item-avatar">
                          {avatarSrc ? (
                            <img src={avatarSrc} alt="" />
                          ) : (
                            a.name.slice(0, 1)
                          )}
                        </div>
                        <span>{a.name}</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>

            <div className="contacts-section">
              <div
                className="contacts-section-header"
                onClick={() => setExpanded((e) => ({ ...e, group: !e.group }))}
              >
                <span>群组 ({groups.length})</span>
                <span className="contacts-arrow">{expanded.group ? '▼' : '▶'}</span>
              </div>
              {expanded.group && (
                <div className="contacts-section-body">
                  {groups.map((g) => {
                    const { avatars, placeholders } = getGroupAvatarSourcesFromMembers(
                      g.members,
                      friends,
                      getImei() ?? '',
                      userAvatar,
                      {
                        assistants: g.assistants ?? (g.assistant_enabled ? ['assistant'] : []),
                        assistantConfigs: g.assistant_configs,
                        assistantNames: Object.fromEntries(
                          Object.entries(g.assistant_configs ?? {}).map(([id, cfg]) => [id, cfg.name || id])
                        ),
                      }
                    )
                    return (
                      <div
                        key={g.group_id}
                        className="contacts-item"
                        onClick={() => handleGroupClick(g)}
                        onDoubleClick={() => handleItemDoubleClick({ type: 'group', data: g })}
                      >
                        <div className="contacts-item-avatar contacts-item-avatar-group">
                          <GroupAvatar avatars={avatars} placeholders={placeholders} size={40} />
                        </div>
                        <span>{g.name}</span>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>

            <div className="contacts-section">
              <div
                className="contacts-section-header"
                onClick={() => setExpanded((e) => ({ ...e, friend: !e.friend }))}
              >
                <span>好友 ({acceptedOnlineCount}/{acceptedFriends.length})</span>
                <span className="contacts-arrow">{expanded.friend ? '▼' : '▶'}</span>
              </div>
              {expanded.friend && (
                <div className="contacts-section-body">
                  {acceptedFriends.map((f) => (
                      <div
                        key={f.imei}
                        className="contacts-item"
                        onClick={() => handleFriendClick(f)}
                        onDoubleClick={() => handleItemDoubleClick({ type: 'friend', data: f })}
                      >
                        <div className="contacts-item-avatar">
                          {f.avatar ? (
                            <img
                              src={f.avatar.startsWith('data:') ? f.avatar : `data:image/png;base64,${f.avatar}`}
                              alt=""
                            />
                          ) : (
                            (f.nickname ?? f.imei).slice(0, 1)
                          )}
                        </div>
                        <span className="contacts-item-name-row">
                          <span>{f.nickname ?? f.imei.slice(0, 8) + '...'}</span>
                          {onlineUsers.has(f.imei) && (
                            <span className="contacts-online-lamp" title="在线" aria-label="在线">
                              💡
                            </span>
                          )}
                        </span>
                      </div>
                    ))}
                </div>
              )}
            </div>
          </>
        )}
        </div>
      </div>
      <div className="contacts-detail-pane">
        {profileTarget ? (
          <ContactProfilePanel
            target={profileTarget}
            friends={friends}
            assistantsForGroup={[
              ...DEFAULT_ASSISTANTS
                .filter((a) => !HIDDEN_BUILTIN_ASSISTANT_IDS.has(a.id))
                .filter((a) => !GROUP_BLOCKED_ASSISTANT_IDS.has(a.id))
                .map((a) => ({ id: a.id, name: a.name, avatar: a.avatar, displayId: undefined })),
              ...customAssistants
                .filter((a) => !GROUP_BLOCKED_ASSISTANT_IDS.has(a.id))
                .map((a) => ({ id: a.id, name: a.name, avatar: a.avatar, displayId: a.displayId })),
            ]}
            userAvatar={userAvatar}
            userName={userName}
            onClose={() => setProfileTarget(null)}
            onSendMessage={handleProfileSendMessage}
            onGroupUpdated={(group) => {
              setGroups((prev) => prev.map((g) => (g.group_id === group.group_id ? group : g)))
              if (profileTarget?.type === 'group' && profileTarget.data.group_id === group.group_id) {
                setProfileTarget({ ...profileTarget, data: group })
              }
            }}
            onGroupRemoved={(groupId) => {
              setGroups((prev) => prev.filter((g) => g.group_id !== groupId))
              if (profileTarget?.type === 'group' && profileTarget.data.group_id === groupId) {
                setProfileTarget(null)
              }
            }}
          />
        ) : loading ? (
          <div className="contacts-home-empty contacts-home-empty--muted">
            <p className="contacts-home-empty-text">加载中…</p>
          </div>
        ) : (
          <div className="contacts-home-empty">
            <ContactsHomeEmptyIllustration />
            <p className="contacts-home-empty-text">{CONTACTS_HOME_EMPTY_COPY}</p>
          </div>
        )}
      </div>
    </div>
  )
}
