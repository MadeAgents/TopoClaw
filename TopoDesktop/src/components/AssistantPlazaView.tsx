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

import { useState, useEffect, useCallback } from 'react'
import { getImei } from '../services/storage'
import {
  syncCustomAssistantsToCloud,
  getPlazaAssistants,
  addPlazaAssistantToMine,
  removePlazaAssistant,
  submitToPlaza,
  togglePlazaLike,
  getFriends,
  getGroups,
  sendFriendMessage,
  sendGroupMessageViaWebSocket,
  type PlazaAssistantItem,
  type Friend,
  type GroupInfo,
} from '../services/api'
import {
  getCustomAssistants,
  getVisibleCustomAssistants,
  addCustomAssistant,
  removeCustomAssistant,
  hasChat,
  isAssistantUserCreated,
  ensureDefaultBuiltinAssistantsAndMaybeSync,
  isProtectedBuiltinAssistantId,
  type CustomAssistant,
} from '../services/customAssistants'
import { clearMessagesForConversation, markConversationHasMessages } from '../services/messageStorage'
import type { Conversation } from '../types/conversation'
import { toAvatarSrcLikeContacts } from '../utils/avatar'
import { AddAssistantModal } from './AddAssistantModal'
import { NewAssistantModal } from './NewAssistantModal'
import { EditAssistantModal } from './EditAssistantModal'
import { PlazaAssistantIntroModal } from './PlazaAssistantIntroModal'
import { buildAssistantShareCardContent } from '../services/assistantShareCard'
import './AssistantPlazaView.css'

interface AssistantPlazaViewProps {
  /** 顶部导航栏搜索词，用于过滤「我的助手」列表 */
  search?: string
  onRefresh?: () => void
  /** 点击「发消息」时打开聊天（切换到消息 tab 并选中该助手） */
  onOpenChat?: (c: Conversation) => void
}

export function AssistantPlazaView({ search = '', onRefresh, onOpenChat }: AssistantPlazaViewProps) {
  type ShareTargetType = 'friend' | 'group'
  const [activeTab, setActiveTab] = useState<'plaza' | 'my'>('my')
  const [list, setList] = useState<CustomAssistant[]>([])
  const [plazaList, setPlazaList] = useState<PlazaAssistantItem[]>([])
  const [plazaLoading, setPlazaLoading] = useState(false)
  /** 广场列表添加/下架进行中，对应条目 id */
  const [plazaActionLoadingId, setPlazaActionLoadingId] = useState<string | null>(null)
  const [showAddModal, setShowAddModal] = useState(false)
  const [showNewAssistantModal, setShowNewAssistantModal] = useState(false)
  const [editingAssistant, setEditingAssistant] = useState<CustomAssistant | null>(null)
  const [plazaIntroItem, setPlazaIntroItem] = useState<PlazaAssistantItem | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)
  const [plazaSort, setPlazaSort] = useState<'latest' | 'hot'>('latest')
  const [plazaLikeBusy, setPlazaLikeBusy] = useState(false)
  /** 为 true 时「我的助手」仅显示用户通过向导创建的助手 */
  const [filterCreatedOnly, setFilterCreatedOnly] = useState(false)
  const [sharingAssistant, setSharingAssistant] = useState<CustomAssistant | null>(null)
  const [shareTargetType, setShareTargetType] = useState<ShareTargetType | null>(null)
  const [shareFriends, setShareFriends] = useState<Friend[]>([])
  const [shareGroups, setShareGroups] = useState<GroupInfo[]>([])
  const [shareLoadingTargets, setShareLoadingTargets] = useState(false)
  const [selectedShareIds, setSelectedShareIds] = useState<string[]>([])
  const [shareSending, setShareSending] = useState(false)

  const imei = getImei()

  const refreshList = useCallback(() => {
    setList(getVisibleCustomAssistants())
  }, [])

  useEffect(() => {
    refreshList()
  }, [refreshList, refreshKey])

  /** 仅打开助手广场、未经过会话列表拉云时，仍保证默认 TopoClaw 存在 */
  useEffect(() => {
    void ensureDefaultBuiltinAssistantsAndMaybeSync(imei || undefined).then((added) => {
      if (added) setRefreshKey((k) => k + 1)
    })
  }, [imei])

  useEffect(() => {
    if (activeTab !== 'plaza') return
    setPlazaLoading(true)
    getPlazaAssistants({ page: 1, limit: 50, imei: imei || undefined, sort: plazaSort })
      .then(({ assistants }) => setPlazaList(assistants))
      .catch(() => setPlazaList([]))
      .finally(() => setPlazaLoading(false))
  }, [activeTab, imei, plazaSort])

  const handleTogglePlazaLike = async (item: PlazaAssistantItem) => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    setPlazaLikeBusy(true)
    try {
      const r = await togglePlazaLike(imei, item.id)
      if (!r.success) {
        window.alert('点赞失败，请重试')
        return
      }
      setPlazaList((prev) =>
        prev.map((x) =>
          x.id === item.id ? { ...x, likes_count: r.likes_count, liked_by_me: r.liked_by_me } : x
        )
      )
      setPlazaIntroItem((prev) =>
        prev && prev.id === item.id
          ? { ...prev, likes_count: r.likes_count, liked_by_me: r.liked_by_me }
          : prev
      )
    } catch {
      window.alert('点赞失败，请检查网络')
    } finally {
      setPlazaLikeBusy(false)
    }
  }

  const handleAddFromPlaza = async (item: PlazaAssistantItem): Promise<boolean> => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return false
    }
    setPlazaActionLoadingId(item.id)
    try {
      const { success, assistant } = await addPlazaAssistantToMine(imei, item.id)
      if (success && assistant) {
        addCustomAssistant({
          id: assistant.id,
          name: assistant.name,
          intro: assistant.intro,
          baseUrl: assistant.baseUrl,
          capabilities: assistant.capabilities,
          avatar: assistant.avatar,
          creator_imei: assistant.creator_imei || item.creator_imei,
          creator_avatar: assistant.creator_avatar || item.creator_avatar,
          multiSessionEnabled: assistant.multiSessionEnabled,
          assistantOrigin: 'added',
        })
        setRefreshKey((k) => k + 1)
        onRefresh?.()
        window.alert('已添加到「我的助手」')
        return true
      }
      window.alert('添加失败，请重试')
      return false
    } catch {
      window.alert('添加失败，请检查网络')
      return false
    } finally {
      setPlazaActionLoadingId(null)
    }
  }

  const handleRemoveFromPlaza = async (item: PlazaAssistantItem): Promise<boolean> => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return false
    }
    setPlazaActionLoadingId(item.id)
    try {
      const { success } = await removePlazaAssistant(imei, item.id)
      if (success) {
        setPlazaList((prev) => prev.filter((x) => x.id !== item.id))
        window.alert('已从助手广场下架')
        return true
      }
      window.alert('下架失败，请重试')
      return false
    } catch {
      window.alert('下架失败，请检查网络')
      return false
    } finally {
      setPlazaActionLoadingId(null)
    }
  }

  const handlePublishToPlaza = async (a: CustomAssistant) => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    try {
      const { success } = await submitToPlaza(imei, {
        id: a.id,
        name: a.name,
        intro: a.intro,
        baseUrl: a.baseUrl,
        capabilities: a.capabilities,
        avatar: a.avatar,
        multiSessionEnabled: a.multiSessionEnabled,
      })
      if (success) {
        window.alert('已发布到助手广场')
      } else {
        window.alert('发布失败，请重试')
      }
    } catch {
      window.alert('发布失败，请检查网络')
    }
  }

  const resetShareState = () => {
    setSharingAssistant(null)
    setShareTargetType(null)
    setSelectedShareIds([])
    setShareSending(false)
  }

  const handleOpenShareSelector = async (assistant: CustomAssistant, targetType: ShareTargetType) => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    setSharingAssistant(assistant)
    setShareTargetType(targetType)
    setSelectedShareIds([])
    setShareLoadingTargets(true)
    try {
      if (targetType === 'friend') {
        const list = await getFriends(imei).catch(() => [])
        setShareFriends(list.filter((f) => f.status === 'accepted'))
      } else {
        const list = await getGroups(imei).catch(() => [])
        setShareGroups(list)
      }
    } finally {
      setShareLoadingTargets(false)
    }
  }

  const handleSendAssistantShare = async () => {
    if (!imei || !sharingAssistant || !shareTargetType) return
    if (selectedShareIds.length === 0) {
      window.alert(shareTargetType === 'friend' ? '请至少选择一位好友' : '请至少选择一个群组')
      return
    }
    let likesCount = 0
    try {
      const localMatch = plazaList.find(
        (p) =>
          (p.baseUrl || '').trim().replace(/\/+$/, '/') === (sharingAssistant.baseUrl || '').trim().replace(/\/+$/, '/') &&
          ((p.creator_imei || '').trim() === (imei || '').trim() || (p.name || '').trim() === (sharingAssistant.name || '').trim())
      )
      if (localMatch) {
        likesCount = Number(localMatch.likes_count || 0)
      } else {
        const { assistants } = await getPlazaAssistants({ page: 1, limit: 100, imei, sort: 'hot' })
        const remoteMatch = assistants.find(
          (p) =>
            (p.baseUrl || '').trim().replace(/\/+$/, '/') === (sharingAssistant.baseUrl || '').trim().replace(/\/+$/, '/') &&
            ((p.creator_imei || '').trim() === (imei || '').trim() || (p.name || '').trim() === (sharingAssistant.name || '').trim())
        )
        likesCount = Number(remoteMatch?.likes_count || 0)
      }
    } catch {
      likesCount = 0
    }

    const content = buildAssistantShareCardContent({
      id: sharingAssistant.id,
      name: sharingAssistant.name,
      intro: sharingAssistant.intro,
      avatar: sharingAssistant.avatar,
      likesCount,
      baseUrl: sharingAssistant.baseUrl,
      capabilities: sharingAssistant.capabilities ?? [],
      multiSessionEnabled: sharingAssistant.multiSessionEnabled,
      displayId: sharingAssistant.displayId,
      creatorImei: sharingAssistant.creator_imei,
      creatorAvatar: sharingAssistant.creator_avatar,
    })
    setShareSending(true)
    try {
      if (shareTargetType === 'friend') {
        const tasks = selectedShareIds.map((targetImei) =>
          sendFriendMessage(imei, targetImei, content, { messageType: 'assistant_card' })
        )
        const result = await Promise.allSettled(tasks)
        const successCount = result.filter((x) => x.status === 'fulfilled' && x.value.success).length
        const failCount = selectedShareIds.length - successCount
        window.alert(failCount > 0 ? `已分享给 ${successCount} 位好友，${failCount} 位发送失败` : `已分享给 ${successCount} 位好友`)
      } else {
        const tasks = selectedShareIds.map((gid) =>
          sendGroupMessageViaWebSocket(imei, gid, content, { messageType: 'assistant_card' })
        )
        const result = await Promise.allSettled(tasks)
        const successCount = result.filter((x) => x.status === 'fulfilled' && x.value.success).length
        const failCount = selectedShareIds.length - successCount
        window.alert(failCount > 0 ? `已分享到 ${successCount} 个群组，${failCount} 个发送失败` : `已分享到 ${successCount} 个群组`)
      }
      resetShareState()
    } catch {
      window.alert('分享失败，请检查网络后重试')
    } finally {
      setShareSending(false)
    }
  }

  const handleDelete = async (a: CustomAssistant) => {
    if (isProtectedBuiltinAssistantId(a.id)) {
      window.alert('该助手为默认内置助手（TopoClaw / GroupManager），无法删除。您仍可通过「编辑」修改名称与配置。')
      return
    }
    if (!window.confirm(`确定要删除小助手「${a.name}」吗？删除后将从您的列表中移除，聊天记录也会被清除。`)) return
    removeCustomAssistant(a.id)
    clearMessagesForConversation(a.id)
    const updated = getCustomAssistants()
    if (imei) {
      const syncOk = await syncCustomAssistantsToCloud(imei, updated)
      if (!syncOk) {
        console.warn('删除小助手：云端同步失败')
        window.alert('本地已删除，但云端同步失败，其他设备可能仍显示该小助手。请检查网络后重试。')
      }
    } else {
      window.alert('未绑定设备，仅删除本地。请先绑定手机以同步到云端。')
    }
    setRefreshKey((k) => k + 1)
    onRefresh?.()
  }

  const handleAdded = () => {
    setRefreshKey((k) => k + 1)
    onRefresh?.()
  }

  const handleNewAssistantSaved = () => {
    setRefreshKey((k) => k + 1)
    onRefresh?.()
  }

  const searchQuery = search.trim()
  const listAfterOrigin = filterCreatedOnly ? list.filter(isAssistantUserCreated) : list
  const filteredList = searchQuery
    ? listAfterOrigin.filter(
        (a) =>
          a.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          (a.intro ?? '').toLowerCase().includes(searchQuery.toLowerCase())
      )
    : listAfterOrigin

  return (
    <div className="assistant-plaza-view">
      <div className="assistant-plaza-nav">
        <button
          type="button"
          className={`assistant-plaza-nav-btn ${activeTab === 'my' ? 'active' : ''}`}
          onClick={() => setActiveTab('my')}
        >
          我的助手
        </button>
        <button
          type="button"
          className={`assistant-plaza-nav-btn ${activeTab === 'plaza' ? 'active' : ''}`}
          onClick={() => setActiveTab('plaza')}
        >
          助手广场
        </button>
        <div className={`assistant-plaza-nav-indicator ${activeTab === 'plaza' ? 'right' : 'left'}`} />
      </div>

      <div className={`assistant-plaza-toolbar ${activeTab === 'plaza' ? 'assistant-plaza-toolbar-plaza' : ''}`}>
        {activeTab === 'my' && (
          <>
            <button
              type="button"
              className="assistant-plaza-add-btn"
              onClick={() => setShowAddModal(true)}
            >
              + 添加小助手
            </button>
            <button
              type="button"
              className="assistant-plaza-add-btn"
              onClick={() => setShowNewAssistantModal(true)}
            >
              + 创建小助手
            </button>
            <button
              type="button"
              className={`assistant-plaza-add-btn${filterCreatedOnly ? ' assistant-plaza-add-btn-active' : ''}`}
              onClick={() => setFilterCreatedOnly((v) => !v)}
              aria-pressed={filterCreatedOnly}
            >
              我创建的助手
            </button>
          </>
        )}
        {activeTab === 'plaza' && (
          <div className="assistant-plaza-sort-bar">
            <button
              type="button"
              className={`assistant-plaza-sort-btn ${plazaSort === 'latest' ? 'active' : ''}`}
              onClick={() => setPlazaSort('latest')}
            >
              最新
            </button>
            <button
              type="button"
              className={`assistant-plaza-sort-btn ${plazaSort === 'hot' ? 'active' : ''}`}
              onClick={() => setPlazaSort('hot')}
            >
              最热
            </button>
          </div>
        )}
      </div>

      {activeTab === 'my' && filterCreatedOnly && (
        <div className="assistant-plaza-filter-banner" role="status">
          <span className="assistant-plaza-filter-banner-text">
            已开启「我创建的助手」筛选：仅显示创建向导添加的助手；通过「添加小助手」链接或助手广场添加的不在此列表。
          </span>
          <button
            type="button"
            className="assistant-plaza-filter-banner-btn"
            onClick={() => setFilterCreatedOnly(false)}
          >
            显示全部助手
          </button>
        </div>
      )}

      <div className="assistant-plaza-content">
        {activeTab === 'plaza' && (
          <div className="assistant-plaza-list">
            {plazaLoading ? (
              <div className="assistant-plaza-empty">
                <p className="assistant-plaza-empty-desc">加载中...</p>
              </div>
            ) : plazaList.length === 0 ? (
              <div className="assistant-plaza-empty">
                <p className="assistant-plaza-empty-title">暂无分享</p>
                <p className="assistant-plaza-empty-desc">广场中暂无他人分享的小助手，快去「我的助手」发布你的小助手吧</p>
              </div>
            ) : (
              plazaList.map((item) => {
                const plazaAv = toAvatarSrcLikeContacts(item.avatar)
                return (
                <div
                  key={item.id}
                  className="assistant-plaza-card assistant-plaza-card-plaza-clickable"
                  onClick={() => setPlazaIntroItem(item)}
                >
                  <div
                    className="assistant-plaza-card-avatar"
                    data-copy-image-src={plazaAv || undefined}
                    style={{
                      backgroundImage: plazaAv ? `url(${plazaAv})` : undefined,
                    }}
                  >
                    {!plazaAv && (item.name?.slice(0, 1) ?? '助')}
                  </div>
                  <div className="assistant-plaza-card-body">
                    <div className="assistant-plaza-card-name">{item.name}</div>
                    {item.intro && <div className="assistant-plaza-card-intro">{item.intro}</div>}
                  </div>
                  <div
                    className="assistant-plaza-card-actions"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <button
                      type="button"
                      className={`assistant-plaza-card-like ${item.liked_by_me ? 'assistant-plaza-card-like-active' : ''}`}
                      onClick={() => { void handleTogglePlazaLike(item) }}
                      disabled={plazaLikeBusy || !!plazaActionLoadingId}
                      title={item.liked_by_me ? '取消点赞' : '点赞'}
                    >
                      {item.liked_by_me ? '已点赞' : '点赞'} {item.likes_count ?? 0}
                    </button>
                    {item.is_creator ? (
                      <button
                        type="button"
                        className="assistant-plaza-card-delete"
                        onClick={() => {
                          if (!window.confirm('确定从助手广场下架该助手吗？其他用户将不再在广场中看到。')) return
                          void handleRemoveFromPlaza(item)
                        }}
                        disabled={!!plazaActionLoadingId}
                        title="从助手广场删除"
                      >
                        {plazaActionLoadingId === item.id ? '下架中...' : '删除'}
                      </button>
                    ) : (
                      <button
                        type="button"
                        className="assistant-plaza-card-add"
                        onClick={() => { void handleAddFromPlaza(item) }}
                        disabled={!!plazaActionLoadingId}
                        title="添加到我的助手"
                      >
                        {plazaActionLoadingId === item.id ? '添加中...' : '添加'}
                      </button>
                    )}
                  </div>
                </div>
              )
            })
            )}
          </div>
        )}

        {activeTab === 'my' && (
          <div className="assistant-plaza-list">
            {filteredList.length === 0 ? (
              <div className="assistant-plaza-empty">
                {searchQuery ? (
                  <p className="assistant-plaza-empty-desc">未找到匹配的小助手</p>
                ) : filterCreatedOnly && list.length > 0 ? (
                  <>
                    <p className="assistant-plaza-empty-desc">暂无自己创建的小助手</p>
                    <p className="assistant-plaza-empty-hint">再次点击「我创建的助手」可查看全部我的助手</p>
                  </>
                ) : (
                  <>
                    <p className="assistant-plaza-empty-desc">暂无小助手</p>
                    <p className="assistant-plaza-empty-hint">点击「添加小助手」粘贴链接添加，或「创建小助手」新建</p>
                    <div className="assistant-plaza-empty-actions">
                      <button
                        type="button"
                        className="assistant-plaza-add-inline-btn"
                        onClick={() => setShowAddModal(true)}
                      >
                        添加小助手
                      </button>
                      <button
                        type="button"
                        className="assistant-plaza-add-inline-btn"
                        onClick={() => setShowNewAssistantModal(true)}
                      >
                        创建小助手
                      </button>
                    </div>
                  </>
                )}
              </div>
            ) : (
              filteredList.map((a) => {
                const myAv = toAvatarSrcLikeContacts(a.avatar)
                return (
                <div key={a.id} className="assistant-plaza-card">
                  <div
                    className="assistant-plaza-card-avatar"
                    data-copy-image-src={myAv || undefined}
                    style={{
                      backgroundImage: myAv ? `url(${myAv})` : undefined,
                    }}
                  >
                    {!myAv && (a.name?.slice(0, 1) ?? '助')}
                  </div>
                  <div className="assistant-plaza-card-body">
                    <div className="assistant-plaza-card-name">{a.name}</div>
                    {a.intro && <div className="assistant-plaza-card-intro">{a.intro}</div>}
                    {a.baseUrl && (
                      <div className="assistant-plaza-card-baseurl" title={a.baseUrl}>
                        服务地址：{a.baseUrl}
                      </div>
                    )}
                  </div>
                  <div className="assistant-plaza-card-actions">
                    {hasChat(a) && onOpenChat && (
                      <button
                        type="button"
                        className="assistant-plaza-card-chat"
                        onClick={() => {
                          const conv: Conversation = {
                            id: a.id,
                            name: a.name,
                            avatar: toAvatarSrcLikeContacts(a.avatar) ?? a.avatar,
                            type: 'assistant',
                            lastMessageTime: Date.now(),
                            baseUrl: a.baseUrl,
                            multiSessionEnabled: a.multiSessionEnabled,
                          }
                          markConversationHasMessages(a.id)
                          onOpenChat(conv)
                        }}
                        title="发消息"
                      >
                        发消息
                      </button>
                    )}
                    <button
                      type="button"
                      className="assistant-plaza-card-edit"
                      onClick={() => setEditingAssistant(a)}
                      title="编辑小助手"
                    >
                      编辑
                    </button>
                    <button
                      type="button"
                      className="assistant-plaza-card-publish"
                      onClick={() => setSharingAssistant(a)}
                      title="分享助手"
                    >
                      分享
                    </button>
                    {!isProtectedBuiltinAssistantId(a.id) && (
                      <button
                        type="button"
                        className="assistant-plaza-card-delete"
                        onClick={() => handleDelete(a)}
                        title="删除小助手"
                      >
                        删除
                      </button>
                    )}
                  </div>
                </div>
              )
            })
            )}
          </div>
        )}
      </div>

      {showAddModal && (
        <AddAssistantModal
          onClose={() => setShowAddModal(false)}
          onAdded={handleAdded}
        />
      )}
      {showNewAssistantModal && (
        <NewAssistantModal
          onClose={() => setShowNewAssistantModal(false)}
          onSaved={handleNewAssistantSaved}
        />
      )}
      {editingAssistant && (
        <EditAssistantModal
          assistant={editingAssistant}
          onClose={() => setEditingAssistant(null)}
          onSaved={() => {
            setRefreshKey((k) => k + 1)
            onRefresh?.()
            setEditingAssistant(null)
          }}
        />
      )}
      {sharingAssistant && !shareTargetType && (
        <div className="assistant-share-overlay" onClick={resetShareState}>
          <div className="assistant-share-modal" onClick={(e) => e.stopPropagation()}>
            <div className="assistant-share-title">分享「{sharingAssistant.name}」</div>
            <button
              type="button"
              className="assistant-share-option"
              onClick={() => void handleOpenShareSelector(sharingAssistant, 'friend')}
            >
              分享给好友
            </button>
            <button
              type="button"
              className="assistant-share-option"
              onClick={() => void handleOpenShareSelector(sharingAssistant, 'group')}
            >
              分享到群组
            </button>
            <button
              type="button"
              className="assistant-share-option"
              onClick={() => {
                void handlePublishToPlaza(sharingAssistant)
                resetShareState()
              }}
            >
              分享到广场
            </button>
            <button type="button" className="assistant-share-cancel" onClick={resetShareState}>
              取消
            </button>
          </div>
        </div>
      )}
      {sharingAssistant && shareTargetType && (
        <div className="assistant-share-overlay" onClick={resetShareState}>
          <div className="assistant-share-modal assistant-share-modal-targets" onClick={(e) => e.stopPropagation()}>
            <div className="assistant-share-title">
              {shareTargetType === 'friend' ? '分享给好友' : '分享到群组'} · {sharingAssistant.name}
            </div>
            <div className="assistant-share-target-list">
              {shareLoadingTargets ? (
                <div className="assistant-share-empty">加载中...</div>
              ) : shareTargetType === 'friend' ? (
                shareFriends.length === 0 ? (
                  <div className="assistant-share-empty">暂无可分享的好友</div>
                ) : (
                  shareFriends.map((f) => (
                    <label key={f.imei} className="assistant-share-target-item">
                      <input
                        type="checkbox"
                        checked={selectedShareIds.includes(f.imei)}
                        onChange={(e) => {
                          const checked = e.target.checked
                          setSelectedShareIds((prev) =>
                            checked ? [...prev, f.imei] : prev.filter((x) => x !== f.imei)
                          )
                        }}
                      />
                      <span>{f.nickname || f.imei}</span>
                    </label>
                  ))
                )
              ) : shareGroups.length === 0 ? (
                <div className="assistant-share-empty">暂无可分享的群组</div>
              ) : (
                shareGroups.map((g) => (
                  <label key={g.group_id} className="assistant-share-target-item">
                    <input
                      type="checkbox"
                      checked={selectedShareIds.includes(g.group_id)}
                      onChange={(e) => {
                        const checked = e.target.checked
                        setSelectedShareIds((prev) =>
                          checked ? [...prev, g.group_id] : prev.filter((x) => x !== g.group_id)
                        )
                      }}
                    />
                    <span>{g.name || g.group_id}</span>
                  </label>
                ))
              )}
            </div>
            <div className="assistant-share-actions">
              <button type="button" className="assistant-share-cancel" onClick={resetShareState} disabled={shareSending}>
                取消
              </button>
              <button type="button" className="assistant-share-send" onClick={() => void handleSendAssistantShare()} disabled={shareSending}>
                {shareSending ? '发送中...' : '发送'}
              </button>
            </div>
          </div>
        </div>
      )}
      {plazaIntroItem && (
        <PlazaAssistantIntroModal
          item={plazaIntroItem}
          onClose={() => setPlazaIntroItem(null)}
          likeBusy={plazaLikeBusy}
          onToggleLike={() => void handleTogglePlazaLike(plazaIntroItem)}
          primaryDisabled={!!plazaActionLoadingId}
          primaryDanger={plazaIntroItem.is_creator === true}
          primaryLabel={
            plazaIntroItem.is_creator === true
              ? plazaActionLoadingId === plazaIntroItem.id
                ? '下架中...'
                : '从助手广场删除'
              : plazaActionLoadingId === plazaIntroItem.id
                ? '添加中...'
                : '添加到我的助手'
          }
          onPrimaryAction={() => {
            void (async () => {
              if (plazaIntroItem.is_creator === true) {
                if (!window.confirm('确定从助手广场下架该助手吗？其他用户将不再在广场中看到。')) return
                const ok = await handleRemoveFromPlaza(plazaIntroItem)
                if (ok) setPlazaIntroItem(null)
              } else {
                const ok = await handleAddFromPlaza(plazaIntroItem)
                if (ok) setPlazaIntroItem(null)
              }
            })()
          }}
        />
      )}
    </div>
  )
}
