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

import { useState, useCallback, useEffect, useRef, useMemo } from 'react'
import { LeftNav, type NavTab } from './LeftNav'
import { ConversationList } from './ConversationList'
import { ChatDetail } from './ChatDetail'
import { ContactsView } from './ContactsView'
import { SettingsView } from './SettingsView'
import { SkillsView } from './SkillsView'
import { AssistantPlazaView } from './AssistantPlazaView'
import { ScheduledTasksView } from './ScheduledTasksView'
import { QuickNotesView } from './QuickNotesView'
import { WindowControls } from './WindowControls'
import { AddAssistantModal } from './AddAssistantModal'
import { AddFriendModal } from './AddFriendModal'
import { CreateGroupModal } from './CreateGroupModal'
import { AddMenuDropdown, type AddMenuAction } from './AddMenuDropdown'
import { BuiltinAssistantLogModal } from './BuiltinAssistantLogModal'
import type { Conversation } from '../types/conversation'
import {
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
} from '../types/conversation'
import { getConversationsWithMessages, getLastMessagePreview, getMaxStoredMessageTimestamp, markConversationHasMessages } from '../services/messageStorage'
import { applyInboxSyncPayload } from '../services/inboxSync'
import { getSessions, syncInbox, syncSessions } from '../services/api'
import { getImei } from '../services/storage'
import {
  connectAll,
  disconnectAll,
  getBuiltinModelProfilesViaPool,
  setOnRemoteExecuteRequest,
  subscribeAnyAssistantPush,
  unsubscribeAnyAssistantPush,
} from '../services/chatWebSocketPool'
import { addQuickNote } from '../services/quickNotesStorage'
import type { ScheduledJob } from '../types/scheduledJob'
import { refreshJobs as refreshScheduledJobs, deleteJob as deleteScheduledJob } from '../services/scheduledJobService'
import { getNextRunTimestamp } from '../services/scheduledJobNextRun'
import { DEFAULT_TOPOCLAW_ASSISTANT_ID, getCustomAssistantByBaseUrl, getCustomAssistantById, hasMultiSession } from '../services/customAssistants'
import {
  ensureBuiltinAssistantStarted,
  getDefaultBuiltinUrls,
  saveBuiltinAssistantConfig,
  type BuiltinAssistantSlot,
  warmBuiltinModelProfilesCache,
} from '../services/builtinAssistantConfig'
import { isConversationHiddenFromChatList, restoreConversationToChatList } from '../services/conversationVisibility'
import { loadSessions, saveSessions, type ChatSession } from '../services/sessionStorage'
import { SearchIndexProvider, useSearchIndexForGlobalSearch } from '../contexts/SearchIndexContext'
import { GlobalSearchDropdown } from './GlobalSearchDropdown'
import './ChatLayout.css'

/** 全局事件：打开内置小助手日志弹窗（由 ChatLayout 监听，确保顶层渲染） */
export const OPEN_BUILTIN_LOG_EVENT = 'open-builtin-log'

export type OpenBuiltinLogEventDetail = { slot?: BuiltinAssistantSlot }

interface ChatLayoutProps {
  onLogout?: () => void
  onStartupProgress?: (event: StartupProgressEvent) => void
}

interface ScheduledTaskPreflightWarning {
  job: ScheduledJob
  runAtMs: number
  occurrenceKey: string
}

export interface StartupProgressEvent {
  phase: 'running' | 'success' | 'error'
  message: string
}

type TaskCenterStatus = 'pending' | 'running' | 'paused_for_human' | 'completed' | 'failed' | 'cancelled' | 'unknown'

interface TaskCenterItem {
  taskId: string
  threadId: string
  baseUrlKey: string
  status: TaskCenterStatus
  taskType: string
  description: string
  detail: string
  updatedAt: number
}

interface RuntimeRunningPayload {
  conversationId: string
  conversationName: string
  baseUrl: string
  query: string
  running: boolean
}

const HIDDEN_BUILTIN_ASSISTANT_IDS = new Set([
  CONVERSATION_ID_ASSISTANT,
  CONVERSATION_ID_SKILL_LEARNING,
  CONVERSATION_ID_CUSTOMER_SERVICE,
])

function parseTaskStatus(content: string): TaskCenterStatus {
  const raw = String(content || '')
  const lower = content.toLowerCase()
  if (lower.includes('waiting for user input')) return 'paused_for_human'
  if (lower.includes('completed')) return 'completed'
  if (lower.includes('failed')) return 'failed'
  if (lower.includes('cancelled')) return 'cancelled'
  if (lower.includes('running')) return 'running'
  if (raw.includes('请求协助') || raw.includes('等待用户输入')) return 'paused_for_human'
  if (raw.includes('后台任务已完成') || raw.includes('任务已完成')) return 'completed'
  if (raw.includes('后台任务失败') || raw.includes('任务失败')) return 'failed'
  if (raw.includes('后台任务已取消') || raw.includes('任务已取消')) return 'cancelled'
  if (raw.includes('后台任务已开始') || raw.includes('后台任务运行中') || raw.includes('正在执行')) return 'running'
  if (lower.includes('status changed')) return 'unknown'
  return 'unknown'
}

function extractTaskLine(content: string, key: string): string {
  const re = new RegExp(`${key}:\\s*(.+)`, 'i')
  const m = content.match(re)
  return (m?.[1] || '').trim()
}

function extractTaskId(content: string): string {
  const fromTaskEvent = extractTaskLine(content, 'Task ID')
  if (fromTaskEvent) return fromTaskEvent
  const m1 = content.match(/(?:任务ID|任务Id|task id)\s*[:：]\s*`?([A-Za-z0-9_-]{4,})`?/i)
  if (m1?.[1]) return m1[1].trim()
  const m2 = content.match(/任务\s*`?([A-Za-z0-9_-]{4,})`?\s*请求协助/)
  if (m2?.[1]) return m2[1].trim()
  return ''
}

function parseTaskEventPush(payload: { threadId: string; content: string; baseUrlKey: string }): TaskCenterItem | null {
  const content = String(payload.content || '').trim()
  const looksLikeTaskEvent = (
    content.includes('[Task Event]')
    || content.includes('[系统通知] 后台任务')
    || /(?:任务ID|任务Id|task id)\s*[:：]\s*/i.test(content)
    || /任务\s*`?[A-Za-z0-9_-]{4,}`?\s*请求协助/.test(content)
  )
  if (!content || !looksLikeTaskEvent) return null
  const taskId = extractTaskId(content)
  if (!taskId) return null
  const taskType = extractTaskLine(content, 'Type')
  const description =
    extractTaskLine(content, 'Description')
    || extractTaskLine(content, '任务描述')
    || extractTaskLine(content, '原始请求')
  const detail =
    extractTaskLine(content, 'Question')
    || extractTaskLine(content, 'Result')
    || extractTaskLine(content, 'Error')
    || extractTaskLine(content, 'Status')
    || extractTaskLine(content, '执行结果')
    || extractTaskLine(content, '错误')
    || extractTaskLine(content, '任务请求')
  return {
    taskId,
    threadId: payload.threadId,
    baseUrlKey: payload.baseUrlKey,
    status: parseTaskStatus(content),
    taskType,
    description,
    detail,
    updatedAt: Date.now(),
  }
}

function taskStatusText(status: TaskCenterStatus): string {
  switch (status) {
    case 'running':
      return '运行中'
    case 'paused_for_human':
      return '待协助'
    case 'completed':
      return '已完成'
    case 'failed':
      return '失败'
    case 'cancelled':
      return '已取消'
    case 'pending':
      return '排队中'
    default:
      return '状态变更'
  }
}

function taskStatusClass(status: TaskCenterStatus): string {
  switch (status) {
    case 'running':
      return 'task-status-running'
    case 'paused_for_human':
      return 'task-status-paused'
    case 'completed':
      return 'task-status-completed'
    case 'failed':
      return 'task-status-failed'
    case 'cancelled':
      return 'task-status-cancelled'
    default:
      return 'task-status-unknown'
  }
}

function formatDateTime(ms: number): string {
  const d = new Date(ms)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}:${ss}`
}

function normalizeConversationId(conversationId: string): string {
  const raw = String(conversationId || '').trim()
  if (!raw) return ''
  if (!raw.startsWith('group_')) return raw
  let groupRaw = raw
  while (groupRaw.startsWith('group_')) groupRaw = groupRaw.slice('group_'.length)
  return groupRaw ? `group_${groupRaw}` : raw
}

function normalizeWarningTitle(job: ScheduledJob): string {
  const n = (job.name ?? '').trim()
  if (n) return n
  const msg = (job.message ?? '').trim()
  return msg.length > 24 ? `${msg.slice(0, 24)}...` : msg || '未命名任务'
}

async function waitBuiltinHealthReady(url: string, timeoutMs = 5000): Promise<boolean> {
  const base = (url || '').trim().replace(/\/+$/, '')
  if (!base) return false
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 700)
    try {
      const res = await fetch(`${base}/health`, { method: 'GET', signal: controller.signal })
      if (res.ok) return true
    } catch {
      // ignore and retry
    } finally {
      clearTimeout(timer)
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  return false
}

function ChatLayoutInner({ onLogout, onStartupProgress }: ChatLayoutProps) {
  const searchIndexConversations = useSearchIndexForGlobalSearch()
  const [activeTab, setActiveTab] = useState<NavTab>('messages')
  const [selected, setSelected] = useState<Conversation | null>(null)
  const [lastMessages, setLastMessages] = useState<Record<string, string>>({})
  const [lastMessageTimes, setLastMessageTimes] = useState<Record<string, number>>({})
  const [unreadCounts, setUnreadCounts] = useState<Record<string, number>>({})
  const [search, setSearch] = useState('')
  /** 点击下拉外区域后暂隐藏下拉，输入框聚焦或搜索内容变化时重新显示 */
  const [searchDismissed, setSearchDismissed] = useState(false)
  const [conversationsWithMessages, setConversationsWithMessages] = useState<Set<string>>(
    () => new Set(getConversationsWithMessages())
  )
  const [addMenuOpen, setAddMenuOpen] = useState(false)
  const [showAddFriendModal, setShowAddFriendModal] = useState(false)
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false)
  const [showAddAssistantModal, setShowAddAssistantModal] = useState(false)
  const [showTaskCenter, setShowTaskCenter] = useState(false)
  const [taskCenterItems, setTaskCenterItems] = useState<Record<string, TaskCenterItem>>({})
  const [conversationListRefreshKey, setConversationListRefreshKey] = useState(0)
  const groupConversationAutoRefreshAtRef = useRef(0)
  const [conversationListCollapsed, setConversationListCollapsed] = useState(false)
  const [quickNotesRefreshKey, setQuickNotesRefreshKey] = useState(0)
  const addButtonRef = useRef<HTMLButtonElement>(null)
  const taskCenterButtonRef = useRef<HTMLButtonElement>(null)
  const taskCenterPanelRef = useRef<HTMLDivElement>(null)
  const searchAreaRef = useRef<HTMLDivElement>(null)
  /** 手机发起 PC 执行任务后，自动跳转到该 session */
  const [sessionIdToNavigate, setSessionIdToNavigate] = useState<string | null>(null)
  /** Toast 提示（内置小助手启动结果） */
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null)
  /** 内置小助手日志弹窗（顶层渲染，通过 open-builtin-log 事件打开） */
  const [showLogModal, setShowLogModal] = useState(false)
  const [logModalSlot, setLogModalSlot] = useState<BuiltinAssistantSlot>('topoclaw')
  const [preflightWarning, setPreflightWarning] = useState<ScheduledTaskPreflightWarning | null>(null)
  const [stoppingWarningTask, setStoppingWarningTask] = useState(false)
  const warningJobsRef = useRef<ScheduledJob[]>([])
  const warningFetchedAtRef = useRef(Date.now())
  const warnedOccurrencesRef = useRef<Set<string>>(new Set())
  const dismissedOccurrencesRef = useRef<Set<string>>(new Set())
  const warningRefreshingRef = useRef(false)
  const topoClawPrewarmDoneRef = useRef(false)

  const handleUpdateLastMessage = useCallback((
    conversationId: string,
    message: string,
    timestamp?: number,
    options?: { isFromMe?: boolean; isViewing?: boolean; skipUnread?: boolean }
  ) => {
    const normalizedConversationId = normalizeConversationId(conversationId)
    if (!normalizedConversationId) return
    if (!HIDDEN_BUILTIN_ASSISTANT_IDS.has(normalizedConversationId) && isConversationHiddenFromChatList(normalizedConversationId)) {
      restoreConversationToChatList(normalizedConversationId)
    }
    markConversationHasMessages(normalizedConversationId)
    setConversationsWithMessages((prev) => new Set(prev).add(normalizedConversationId))
    setLastMessages((prev) => ({ ...prev, [normalizedConversationId]: message }))
    if (timestamp != null) {
      setLastMessageTimes((prev) => ({ ...prev, [normalizedConversationId]: timestamp }))
    }
    if (options?.skipUnread) return
    // 窗口最小化/失焦时，即使选中了该会话也视为未读，以便触发任务栏提示
    const windowFocused = typeof document !== 'undefined' && document.hasFocus()
    const shouldCountUnread = options?.isFromMe !== true && (options?.isViewing !== true || !windowFocused)
    if (shouldCountUnread) {
      setUnreadCounts((prev) => ({ ...prev, [normalizedConversationId]: (prev[normalizedConversationId] ?? 0) + 1 }))
    }

    // Group sessions in list come from remote getGroups().
    // If a new group receives its first message, force a throttled refresh
    // so the group appears in chat list without requiring Contacts refresh.
    if (normalizedConversationId.startsWith('group_')) {
      const now = Date.now()
      if (now - groupConversationAutoRefreshAtRef.current > 1200) {
        groupConversationAutoRefreshAtRef.current = now
        setConversationListRefreshKey((k) => k + 1)
      }
    }
  }, [])

  const handleSelectConversation = useCallback((c: Conversation) => {
    const normalizedId = normalizeConversationId(c.id)
    const normalizedConversation = normalizedId && normalizedId !== c.id ? { ...c, id: normalizedId } : c
    setSelected(normalizedConversation)
    setActiveTab('messages')
    setUnreadCounts((prev) => ({ ...prev, [normalizedConversation.id]: 0 }))
  }, [])

  const handleContactsSelect = useCallback((c: Conversation) => {
    const normalizedId = normalizeConversationId(c.id)
    const normalizedConversation = normalizedId && normalizedId !== c.id ? { ...c, id: normalizedId } : c
    setSelected(normalizedConversation)
    setActiveTab('messages')
    setUnreadCounts((prev) => ({ ...prev, [normalizedConversation.id]: 0 }))
  }, [])

  const handleConversationViewed = useCallback((id: string) => {
    const normalizedId = normalizeConversationId(id)
    if (!normalizedId) return
    setUnreadCounts((prev) => (prev[normalizedId] ? { ...prev, [normalizedId]: 0 } : prev))
  }, [])

  // 窗口重新回到前台时，自动清空当前已打开会话的未读红点。
  useEffect(() => {
    const clearSelectedUnreadIfForeground = () => {
      const selectedId = normalizeConversationId(String(selected?.id || ''))
      if (!selectedId) return
      if (typeof document !== 'undefined' && !document.hasFocus()) return
      setUnreadCounts((prev) => (prev[selectedId] ? { ...prev, [selectedId]: 0 } : prev))
    }
    const onVisibilityChange = () => {
      if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
      clearSelectedUnreadIfForeground()
    }
    window.addEventListener('focus', clearSelectedUnreadIfForeground)
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      window.removeEventListener('focus', clearSelectedUnreadIfForeground)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [selected?.id])

  const handleClearChatHistory = useCallback(() => {
    setLastMessages({})
    setLastMessageTimes({})
    setUnreadCounts({})
    setConversationsWithMessages(new Set())
    setSelected(null)
  }, [])

  const taskCenterList = useMemo(
    () => Object.values(taskCenterItems).sort((a, b) => b.updatedAt - a.updatedAt),
    [taskCenterItems]
  )
  const runningTaskCount = useMemo(
    () => taskCenterList.filter((t) => t.status === 'running').length,
    [taskCenterList]
  )

  const handleAssistantRunningChange = useCallback((payload: RuntimeRunningPayload) => {
    const conversationId = String(payload.conversationId || '').trim()
    if (!conversationId) return
    const queryText = String(payload.query || '').replace(/\s+/g, ' ').trim()
    const conversationName = String(payload.conversationName || '').trim()
    const runtimeTaskId = `runtime:${conversationId}`
    setTaskCenterItems((prev) => {
      if (!payload.running) {
        if (!prev[runtimeTaskId]) return prev
        const next = { ...prev }
        delete next[runtimeTaskId]
        return next
      }
      return {
        ...prev,
        [runtimeTaskId]: {
          taskId: runtimeTaskId,
          threadId: conversationId,
          baseUrlKey: String(payload.baseUrl || '').trim() || 'runtime',
          status: 'running',
          taskType: 'chat_turn',
          description: queryText || conversationName || '当前会话任务',
          detail: queryText ? `会话：${conversationName || conversationId}` : '会话正在执行中',
          updatedAt: Date.now(),
        },
      }
    })
  }, [])

  useEffect(() => {
    const onPush = (payload: { threadId: string; content: string; baseUrlKey: string }) => {
      const parsed = parseTaskEventPush(payload)
      if (!parsed) return
      setTaskCenterItems((prev) => {
        const next: Record<string, TaskCenterItem> = { ...prev, [parsed.taskId]: parsed }
        const keys = Object.keys(next)
        if (keys.length <= 60) return next
        const sorted = keys
          .map((k) => next[k])
          .sort((a, b) => b.updatedAt - a.updatedAt)
          .slice(0, 60)
        const trimmed: Record<string, TaskCenterItem> = {}
        for (const item of sorted) trimmed[item.taskId] = item
        return trimmed
      })
    }
    subscribeAnyAssistantPush(onPush)
    return () => unsubscribeAnyAssistantPush(onPush)
  }, [])

  useEffect(() => {
    if (!showTaskCenter) return
    const onDocMouseDown = (e: MouseEvent) => {
      const target = e.target as Node
      if (taskCenterPanelRef.current?.contains(target)) return
      if (taskCenterButtonRef.current?.contains(target)) return
      setShowTaskCenter(false)
    }
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowTaskCenter(false)
    }
    document.addEventListener('mousedown', onDocMouseDown)
    document.addEventListener('keydown', onEscape)
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown)
      document.removeEventListener('keydown', onEscape)
    }
  }, [showTaskCenter])

  /**
   * 先尝试启动内置 nanobot，再建 WebSocket 池。
   * 原先与 connectAll 并行时，常在服务尚未监听 18790 前就建连，8s 超时后连接被移除，表现成「打开应用不默认连上」。
   */
  useEffect(() => {
    let cancelled = false
    const report = (phase: StartupProgressEvent['phase'], message: string) => {
      onStartupProgress?.({ phase, message })
    }
    ;(async () => {
      report('running', '正在初始化消息连接...')
      // 首屏先可交互，连接池先建一次（云端/非内置地址可立即受益）。
      connectAll()
      report('running', '已建立初始连接，正在应用默认配置...')
      // 默认冷启动为断开状态（每次重启应用后需手动连接 IM 通道）
      await saveBuiltinAssistantConfig({ qqEnabled: false, weixinEnabled: false })
      report('running', '正在启动内置服务...')
      const res = await ensureBuiltinAssistantStarted()
      if (cancelled) return
      if (res.ok) {
        setToast({ message: res.alreadyRunning ? '内置小助手已在运行' : '内置小助手已启动', type: 'success' })
        report('running', res.alreadyRunning ? '内置服务已在运行，正在检查健康状态...' : '内置服务已启动，正在检查健康状态...')
        // 固定等待改为健康探测：ready 即继续，避免冷启动额外硬等待。
        if (!res.alreadyRunning) {
          try {
            const urls = await getDefaultBuiltinUrls()
            const checks = await Promise.allSettled([
              waitBuiltinHealthReady(urls.topoclaw, 5000),
            ])
            const topoclawReady = checks[0].status === 'fulfilled' && checks[0].value === true
            report(
              'running',
              `健康检查结果：TopoClaw ${topoclawReady ? '就绪' : '未就绪'}`
            )
          } catch {
            // ignore
          }
        }
      } else {
        setToast({ message: '内置小助手启动失败：' + (res.error ?? '未知错误'), type: 'error' })
        report('error', '内置服务启动失败：' + (res.error ?? '未知错误'))
      }
      setTimeout(() => {
        if (!cancelled) setToast(null)
      }, 3000)
      if (cancelled) return
      // 内置服务就绪后再触发一次连接池，补齐 localhost 冷启动建连。
      report('running', '正在完成最终连接...')
      connectAll()
      if (res.ok) {
        report('success', '启动完成，正在进入聊天界面...')
      }
    })()
    return () => {
      cancelled = true
      disconnectAll()
    }
  }, [onStartupProgress])

  /**
   * 隐藏预热 TopoClaw：仅后台预拉多 session 元数据，不切换当前会话视图。
   */
  useEffect(() => {
    if (topoClawPrewarmDoneRef.current) return
    const topo = getCustomAssistantById(DEFAULT_TOPOCLAW_ASSISTANT_ID)
    if (!topo || !hasMultiSession(topo)) return
    topoClawPrewarmDoneRef.current = true
    let cancelled = false
    void (async () => {
      const imei = (getImei() || '').trim()
      const baseUrl = (topo.baseUrl || '').trim()
      if (!imei || !baseUrl) return
      // 先预热本地/主进程模型配置缓存，减少首次进入 TopoClaw 时“模型加载中”停留时间。
      await warmBuiltinModelProfilesCache()
      // 再预热运行时 active model，提前打通首个 get_builtin_model_profiles 请求。
      await getBuiltinModelProfilesViaPool(baseUrl).catch(() => {})
      const localSessions = loadSessions(topo.id, baseUrl)
      let merged: ChatSession[] = localSessions
      try {
        const remoteRes = await getSessions(imei, topo.id, { baseUrl })
        if (cancelled) return
        if (remoteRes.success && remoteRes.sessions.length > 0) {
          merged = remoteRes.sessions
          saveSessions(topo.id, merged, baseUrl)
        }
      } catch {
        // ignore prewarm fetch failure
      }
      try {
        const syncRes = await syncSessions(imei, topo.id, merged, { baseUrl })
        if (cancelled) return
        if (syncRes.success && syncRes.sessions.length > 0) {
          saveSessions(topo.id, syncRes.sessions, baseUrl)
        }
      } catch {
        // ignore prewarm sync failure
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  // 应用启动时从本地存储预加载会话列表的最近消息预览和时间戳
  useEffect(() => {
    let cancelled = false
    let idleId: number | null = null
    let timer: ReturnType<typeof setTimeout> | null = null
    const run = () => {
      if (cancelled) return
      const ids = getConversationsWithMessages()
      const nextMessages: Record<string, string> = {}
      const nextTimes: Record<string, number> = {}
      for (const id of ids) {
        const preview = getLastMessagePreview(id)
        if (preview) {
          nextMessages[id] = preview.content
          nextTimes[id] = preview.timestamp
        }
      }
      if (Object.keys(nextMessages).length > 0 && !cancelled) {
        setLastMessages((prev) => ({ ...prev, ...nextMessages }))
        setLastMessageTimes((prev) => ({ ...prev, ...nextTimes }))
      }
    }
    if (typeof window !== 'undefined' && 'requestIdleCallback' in window) {
      idleId = window.requestIdleCallback(run, { timeout: 1200 })
    } else {
      timer = setTimeout(run, 120)
    }
    return () => {
      cancelled = true
      if (idleId != null && typeof window !== 'undefined' && 'cancelIdleCallback' in window) {
        window.cancelIdleCallback(idleId)
      }
      if (timer) clearTimeout(timer)
    }
  }, [])

  /** 方案 B：HTTP 增量收件箱（与跨端 WebSocket 互补；WS 断线或漏推时用此补齐侧栏与本地） */
  const pullInboxIncrement = useCallback(async () => {
    const imei = getImei()?.trim()
    if (!imei) return
    try {
      const since = getMaxStoredMessageTimestamp()
      const { success, conversations } = await syncInbox(imei, since, { limitPerConversation: 80 })
      if (!success) return
      applyInboxSyncPayload(imei, conversations, (id, preview, ts, isFromMe) => {
        handleUpdateLastMessage(id, preview, ts, { isFromMe, skipUnread: true })
      })
      if (Object.keys(conversations ?? {}).length > 0) {
        setConversationListRefreshKey((k) => k + 1)
      }
    } catch (e) {
      console.warn('[Inbox] sync failed', e)
    }
  }, [handleUpdateLastMessage])

  useEffect(() => {
    const timer = setTimeout(() => {
      void pullInboxIncrement()
    }, 600)
    return () => clearTimeout(timer)
  }, [pullInboxIncrement])

  /** 窗口回到前台或页签可见时再拉一次增量，避免仅靠启动时一次 sync、长连断后必须手动「刷新」才更新 */
  useEffect(() => {
    let debounce: ReturnType<typeof setTimeout> | null = null
    const schedule = () => {
      if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
      if (debounce) clearTimeout(debounce)
      debounce = setTimeout(() => {
        debounce = null
        void pullInboxIncrement()
      }, 400)
    }
    window.addEventListener('focus', schedule)
    document.addEventListener('visibilitychange', schedule)
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void pullInboxIncrement()
    }, 90_000)
    return () => {
      window.removeEventListener('focus', schedule)
      document.removeEventListener('visibilitychange', schedule)
      window.clearInterval(interval)
      if (debounce) clearTimeout(debounce)
    }
  }, [pullInboxIncrement])

  /** 未读数变化时通知主进程：任务栏闪烁 + 红色角标，窗口获得焦点时清除 */
  useEffect(() => {
    const total = Object.values(unreadCounts).reduce((a, b) => a + b, 0)
    window.electronAPI?.notifyNewMessage?.(total)
  }, [unreadCounts])

  /** 监听全局事件，打开日志弹窗（顶层渲染避免被父级遮挡或卸载） */
  useEffect(() => {
    const handler = (e: Event) => {
      const d = (e as CustomEvent<OpenBuiltinLogEventDetail>).detail
      setLogModalSlot(d?.slot ?? 'topoclaw')
      setShowLogModal(true)
    }
    window.addEventListener(OPEN_BUILTIN_LOG_EVENT, handler)
    return () => window.removeEventListener(OPEN_BUILTIN_LOG_EVENT, handler)
  }, [])

  useEffect(() => {
    if (!search.trim()) setSearchDismissed(false)
  }, [search])

  const refreshPreflightJobs = useCallback(async () => {
    if (warningRefreshingRef.current) return
    warningRefreshingRef.current = true
    try {
      const list = await refreshScheduledJobs()
      warningJobsRef.current = list
      warningFetchedAtRef.current = Date.now()
    } catch (e) {
      console.warn('[ScheduledPreflight] refresh failed', e)
    } finally {
      warningRefreshingRef.current = false
    }
  }, [])

  useEffect(() => {
    void refreshPreflightJobs()
    const timer = window.setInterval(() => {
      void refreshPreflightJobs()
    }, 15_000)
    return () => window.clearInterval(timer)
  }, [refreshPreflightJobs])

  useEffect(() => {
    const timer = window.setInterval(() => {
      const now = Date.now()

      // Periodically trim stale occurrence markers.
      if (warnedOccurrencesRef.current.size > 300 || dismissedOccurrencesRef.current.size > 300) {
        const keepWarned = new Set<string>()
        const keepDismissed = new Set<string>()
        for (const k of warnedOccurrencesRef.current) {
          const at = Number(k.split('@')[1] ?? 0)
          if (Number.isFinite(at) && at > now - 300_000) keepWarned.add(k)
        }
        for (const k of dismissedOccurrencesRef.current) {
          const at = Number(k.split('@')[1] ?? 0)
          if (Number.isFinite(at) && at > now - 300_000) keepDismissed.add(k)
        }
        warnedOccurrencesRef.current = keepWarned
        dismissedOccurrencesRef.current = keepDismissed
      }

      if (preflightWarning && now >= preflightWarning.runAtMs) {
        setPreflightWarning(null)
      }
      if (preflightWarning) return

      let candidate: ScheduledTaskPreflightWarning | null = null
      for (const job of warningJobsRef.current) {
        const nextRun = getNextRunTimestamp(job, now, warningFetchedAtRef.current)
        if (nextRun == null) continue
        const remain = nextRun - now
        if (remain <= 0 || remain > 10_000) continue
        const occurrenceKey = `${job.id}@${nextRun}`
        if (warnedOccurrencesRef.current.has(occurrenceKey)) continue
        if (dismissedOccurrencesRef.current.has(occurrenceKey)) continue
        if (candidate == null || nextRun < candidate.runAtMs) {
          candidate = { job, runAtMs: nextRun, occurrenceKey }
        }
      }
      if (!candidate) return
      warnedOccurrencesRef.current.add(candidate.occurrenceKey)
      setPreflightWarning(candidate)
    }, 1000)
    return () => window.clearInterval(timer)
  }, [preflightWarning])

  const closePreflightWarning = useCallback(() => {
    if (preflightWarning) {
      dismissedOccurrencesRef.current.add(preflightWarning.occurrenceKey)
    }
    setPreflightWarning(null)
  }, [preflightWarning])

  const stopPreflightTask = useCallback(async () => {
    if (!preflightWarning || stoppingWarningTask) return
    setStoppingWarningTask(true)
    try {
      const list = await deleteScheduledJob(preflightWarning.job.id)
      warningJobsRef.current = list
      warningFetchedAtRef.current = Date.now()
      setPreflightWarning(null)
      setToast({ message: `已停止任务：${normalizeWarningTitle(preflightWarning.job)}`, type: 'success' })
    } catch (e) {
      setToast({ message: e instanceof Error ? e.message : '停止任务失败', type: 'error' })
    } finally {
      setStoppingWarningTask(false)
      setTimeout(() => setToast(null), 2500)
    }
  }, [preflightWarning, stoppingWarningTask])

  useEffect(() => {
    const onDocDown = (e: MouseEvent) => {
      const el = searchAreaRef.current
      if (!el?.contains(e.target as Node)) setSearchDismissed(true)
    }
    document.addEventListener('mousedown', onDocDown)
    return () => document.removeEventListener('mousedown', onDocDown)
  }, [])

  useEffect(() => {
    setOnRemoteExecuteRequest((threadId, baseUrl) => {
      const assistant = getCustomAssistantByBaseUrl(baseUrl)
      if (assistant && hasMultiSession(assistant)) {
        const conv: Conversation = {
          id: assistant.id,
          name: assistant.name,
          avatar: assistant.avatar,
          type: 'assistant',
          baseUrl: assistant.baseUrl,
          multiSessionEnabled: true,
          lastMessageTime: Date.now(),
        }
        setSelected(conv)
        setActiveTab('messages')
        setSessionIdToNavigate(threadId)
      }
    })
    return () => setOnRemoteExecuteRequest(null)
  }, [])

  useEffect(() => {
    const subscribe = window.electronAPI?.onDesktopScreenshotPrefill
    if (!subscribe) return
    return subscribe((payload) => {
      if (payload?.saveToQuickNote !== true) return
      const hasText = !!String(payload.text || '').trim()
      const hasImage = typeof payload.imageBase64 === 'string' && payload.imageBase64.length > 50
      if (!hasText && !hasImage) return
      addQuickNote({
        text: payload.text || '',
        imageBase64: payload.imageBase64,
        imageMime: payload.imageMime,
        imageName: payload.imageName,
      })
      setQuickNotesRefreshKey((k) => k + 1)
      setToast({ message: '已保存到随手记', type: 'success' })
      setTimeout(() => setToast(null), 2200)
    })
  }, [])

  return (
    <div className="chat-layout">
      <LeftNav
        activeTab={activeTab}
        onTabChange={setActiveTab}
        onLogout={onLogout}
      />
      <div className="chat-layout-right">
        <header className="chat-title-bar">
          <div className="chat-title-bar-content">
            {activeTab === 'messages' || activeTab === 'contacts' || activeTab === 'skills' || activeTab === 'assistantPlaza' ? (
              <div className="chat-title-bar-search-row">
                <div className="chat-title-bar-search-wrap" ref={searchAreaRef}>
                  <svg className="chat-title-bar-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="11" cy="11" r="8" />
                    <path d="m21 21-4.35-4.35" />
                  </svg>
                  <input
                    type="text"
                    className="chat-title-bar-search"
                    placeholder="搜索好友、群组、助手或对话"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    onFocus={() => setSearchDismissed(false)}
                  />
                  {search.trim() && !searchDismissed ? (
                    <GlobalSearchDropdown
                      search={search}
                      lastMessages={lastMessages}
                      lastMessageTimes={lastMessageTimes}
                      conversations={searchIndexConversations}
                      onSelect={(c) => {
                        markConversationHasMessages(c.id)
                        setConversationsWithMessages((prev) => new Set(prev).add(c.id))
                        handleSelectConversation(c)
                        setSearch('')
                        setSearchDismissed(false)
                      }}
                    />
                  ) : null}
                </div>
                <button
                  ref={addButtonRef}
                  type="button"
                  className="chat-title-bar-add-assistant"
                  onClick={() => setAddMenuOpen((o) => !o)}
                  title="添加"
                >
                  +
                </button>
                <button
                  ref={taskCenterButtonRef}
                  type="button"
                  className="chat-title-bar-task-center-btn"
                  title="任务中心"
                  onClick={() => setShowTaskCenter((v) => !v)}
                >
                  <span className="chat-title-bar-task-center-icon">⌛</span>
                  {runningTaskCount > 0 ? (
                    <span className="chat-title-bar-task-center-badge">{runningTaskCount}</span>
                  ) : null}
                </button>
                <AddMenuDropdown
                  open={addMenuOpen}
                  onClose={() => setAddMenuOpen(false)}
                  onSelect={(action: AddMenuAction) => {
                    if (action === 'addFriend') setShowAddFriendModal(true)
                    else if (action === 'createGroup') setShowCreateGroupModal(true)
                    else if (action === 'addAssistant') setShowAddAssistantModal(true)
                  }}
                  anchorRef={addButtonRef}
                />
                {showTaskCenter ? (
                  <div ref={taskCenterPanelRef} className="chat-task-center-panel">
                    <div className="chat-task-center-header">
                      <span>任务中心</span>
                      <span className="chat-task-center-header-sub">{runningTaskCount} 个运行中</span>
                    </div>
                    {taskCenterList.length === 0 ? (
                      <div className="chat-task-center-empty">暂无后台任务事件</div>
                    ) : (
                      <div className="chat-task-center-list">
                        {taskCenterList.map((task) => (
                          <div key={task.taskId} className="chat-task-center-item">
                            <div className="chat-task-center-item-head">
                              <span className={`chat-task-center-status ${taskStatusClass(task.status)}`}>
                                {taskStatusText(task.status)}
                              </span>
                              <span className="chat-task-center-time">
                                {new Date(task.updatedAt).toLocaleTimeString()}
                              </span>
                            </div>
                            <div className="chat-task-center-title">
                              <span title={task.description || '(未命名任务)'}>
                                {task.description || '(未命名任务)'}
                              </span>
                            </div>
                            <div className="chat-task-center-meta">
                              ID: {task.taskId}
                              {task.taskType ? ` · ${task.taskType}` : ''}
                            </div>
                            {task.detail ? (
                              <div className="chat-task-center-detail">{task.detail}</div>
                            ) : null}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ) : null}
              </div>
            ) : activeTab === 'scheduledTasks' || activeTab === 'quickNotes' ? (
              <div className="chat-title-bar-search-row">
                <div className="chat-title-bar-search-wrap" ref={searchAreaRef}>
                  <svg className="chat-title-bar-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <circle cx="11" cy="11" r="8" />
                    <path d="m21 21-4.35-4.35" />
                  </svg>
                  <input
                    type="text"
                    className="chat-title-bar-search"
                    placeholder="搜索好友、群组、助手或对话"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    onFocus={() => setSearchDismissed(false)}
                  />
                  {search.trim() && !searchDismissed ? (
                    <GlobalSearchDropdown
                      search={search}
                      lastMessages={lastMessages}
                      lastMessageTimes={lastMessageTimes}
                      conversations={searchIndexConversations}
                      onSelect={(c) => {
                        markConversationHasMessages(c.id)
                        setConversationsWithMessages((prev) => new Set(prev).add(c.id))
                        handleSelectConversation(c)
                        setSearch('')
                        setSearchDismissed(false)
                      }}
                    />
                  ) : null}
                </div>
              </div>
            ) : (
              <span className="chat-title-bar-text">设置</span>
            )}
          </div>
          <WindowControls />
        </header>
        <div className="chat-tab-content">
          <div className={`chat-tab-panel ${activeTab === 'messages' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-main-area">
              <aside className={`chat-sidebar ${conversationListCollapsed ? 'chat-sidebar-collapsed' : ''}`}>
                <ConversationList
                  selectedId={selected?.id ?? null}
                  onSelect={handleSelectConversation}
                  onDelete={(c) => {
                    if (selected?.id === c.id) {
                      setSelected(null)
                    }
                    setUnreadCounts((prev) => ({ ...prev, [c.id]: 0 }))
                  }}
                  lastMessages={lastMessages}
                  lastMessageTimes={lastMessageTimes}
                  unreadCounts={unreadCounts}
                  conversationsWithMessages={conversationsWithMessages}
                  refreshKey={conversationListRefreshKey}
                />
              </aside>
              <main className={`chat-main ${conversationListCollapsed ? 'chat-main-expanded' : ''}`}>
                <ChatDetail
                  conversation={selected}
                  conversationListCollapsed={conversationListCollapsed}
                  onToggleConversationList={() => setConversationListCollapsed((prev) => !prev)}
                  onUpdateLastMessage={handleUpdateLastMessage}
                  onConversationViewed={handleConversationViewed}
                  onSelectConversation={handleSelectConversation}
                  sessionIdToNavigate={sessionIdToNavigate}
                  onSessionIdNavigated={() => setSessionIdToNavigate(null)}
                  onAssistantRunningChange={handleAssistantRunningChange}
                />
              </main>
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'contacts' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-contacts-full">
              <ContactsView
                onSelectConversation={handleContactsSelect}
                onTitleClick={() => setConversationListRefreshKey((k) => k + 1)}
                refreshKey={conversationListRefreshKey}
              />
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'skills' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-settings-full">
              <SkillsView />
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'assistantPlaza' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-settings-full">
              <AssistantPlazaView
                onRefresh={() => setConversationListRefreshKey((k) => k + 1)}
                onOpenChat={(c) => {
                  markConversationHasMessages(c.id)
                  setConversationsWithMessages((prev) => new Set(prev).add(c.id))
                  setSelected(c)
                  setActiveTab('messages')
                }}
              />
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'scheduledTasks' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-settings-full">
              <ScheduledTasksView />
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'quickNotes' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-settings-full">
              <QuickNotesView refreshKey={quickNotesRefreshKey} />
            </div>
          </div>
          <div className={`chat-tab-panel ${activeTab === 'settings' ? 'chat-tab-panel-active' : 'chat-tab-panel-hidden'}`}>
            <div className="chat-settings-full">
              <SettingsView
                onLogout={onLogout}
                onClearChatHistory={handleClearChatHistory}
                onNewAssistantSaved={() => setConversationListRefreshKey((k) => k + 1)}
              />
            </div>
          </div>
        </div>
      </div>
      {showAddFriendModal && (
        <AddFriendModal
          onClose={() => setShowAddFriendModal(false)}
          onAdded={() => setConversationListRefreshKey((k) => k + 1)}
        />
      )}
      {showCreateGroupModal && (
        <CreateGroupModal
          onClose={() => setShowCreateGroupModal(false)}
          onCreated={(c) => {
            setConversationListRefreshKey((k) => k + 1)
            markConversationHasMessages(c.id)
            setConversationsWithMessages((prev) => new Set(prev).add(c.id))
            setSelected(c)
            setActiveTab('messages')
          }}
        />
      )}
      {showAddAssistantModal && (
        <AddAssistantModal
          onClose={() => setShowAddAssistantModal(false)}
          onAdded={() => {
            setConversationListRefreshKey((k) => k + 1)
            connectAll()
          }}
        />
      )}
      {toast && (
        <div className={`chat-layout-toast chat-layout-toast-${toast.type}`}>
          {toast.message}
        </div>
      )}
      {showLogModal && (
        <BuiltinAssistantLogModal slot={logModalSlot} onClose={() => setShowLogModal(false)} />
      )}
      {preflightWarning && (
        <div className="scheduled-preflight-overlay" role="dialog" aria-modal="true">
          <div className="scheduled-preflight-card">
            <div className="scheduled-preflight-title">任务预警</div>
            <div className="scheduled-preflight-content">
              任务「{normalizeWarningTitle(preflightWarning.job)}」将在 10 秒后开始执行。
              <br />
              预计开始时间：{formatDateTime(preflightWarning.runAtMs)}
            </div>
            <div className="scheduled-preflight-actions">
              <button
                type="button"
                className="scheduled-preflight-btn scheduled-preflight-btn-close"
                onClick={closePreflightWarning}
                disabled={stoppingWarningTask}
              >
                关闭弹窗
              </button>
              <button
                type="button"
                className="scheduled-preflight-btn scheduled-preflight-btn-stop"
                onClick={stopPreflightTask}
                disabled={stoppingWarningTask}
              >
                {stoppingWarningTask ? '停止中...' : '停止任务'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export function ChatLayout(props: ChatLayoutProps) {
  return (
    <SearchIndexProvider>
      <ChatLayoutInner {...props} />
    </SearchIndexProvider>
  )
}
