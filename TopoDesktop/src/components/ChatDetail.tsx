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

import React, { useState, useRef, useEffect, useLayoutEffect, useCallback, useMemo, Children, isValidElement } from 'react'
import html2canvas from 'html2canvas'
import { v4 as uuidv4, v5 as uuidv5 } from 'uuid'
import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import { SearchAddon } from '@xterm/addon-search'
import 'xterm/css/xterm.css'
import { sendChatMessage, sendChatAssistantMessage, sendChatAssistantMessageStream, sendCrossDeviceMessage, sendExecuteCommand, sendExecuteForAssistant, getProfile, getFriends, getGroups, getGroup, addGroupMember, addGroupAssistant, getMobileUserStatus, getCrossDeviceMessages, getUnifiedMessages, getChatAssistantBaseUrl, getChatAssistantHistory, appendCustomAssistantChat, sendAssistantGroupMessage, getSessions, syncSessions, getActiveSession, setActiveSession, fetchInstalledSkillsFromService, getUserSettings, updateUserSettings, syncCustomAssistantsToCloud, updateGroupAssistantConfig, getGroupWorkflow, saveGroupWorkflow, importSkillPackageToService, type Skill, type Friend, type GroupInfo } from '../services/api'
import { runComputerUseLoop } from '../services/computerUseLoop'
import { getImei, getAutoExecuteCode, getDeviceId, getValidatedImei } from '../services/storage'
import { OnlineStatusManager } from '../services/onlineStatusManager'
import {
  DEFAULT_TOPOCLAW_ASSISTANT_ID,
  DEFAULT_GROUP_MANAGER_ASSISTANT_ID,
  getCustomAssistantById,
  getCustomAssistantByBaseUrl,
  getCustomAssistants,
  getVisibleCustomAssistants,
  isCustomAssistantId,
  hasChat,
  hasExecutionPc,
  hasExecutionMobile,
  hasMultiSession,
  hasGroupManager,
  buildGroupAssistantContext,
  parseGroupManagerMention,
  resolveGroupAssistantConfig,
  getGroupAssistantDisplayName,
  inferCreatorImeiFromAssistantId,
  builtinSlotForAssistantId,
  isDefaultBuiltinUrl,
  addCustomAssistant,
  buildAssistantUrl,
  parseAssistantUrl,
  resolveCustomAssistantAvatarForDisplay,
} from '../services/customAssistants'
import { getBuiltinAssistantConfig, getBuiltinModelProfiles, getDefaultBuiltinUrl, getImLocalHistory, restartBuiltinAssistant, saveBuiltinAssistantConfig, saveBuiltinModelProfiles, type BuiltinModelProfileRow } from '../services/builtinAssistantConfig'
import { addCollectedSkill, loadMySkills, loadCollectedSkills, syncPublicHubSkillsWithServer } from '../services/skillStorage'
import type { Conversation } from '../types/conversation'
import { CONVERSATION_ID_ME, CONVERSATION_ID_ASSISTANT, CONVERSATION_ID_CHAT_ASSISTANT, CONVERSATION_ID_GROUP, CONVERSATION_ID_IM_QQ, CONVERSATION_ID_IM_WEIXIN } from '../types/conversation'
import { loadMessages, loadMessagesForGroup, saveMessages, appendMessageToStorage, getBaselineTimestamp, hasStoredMessages, removeMessagesForSession } from '../services/messageStorage'
import { loadSessions, addSession, saveSessions, updateSessionTitle, removeSession, getActiveSessionLocal, setActiveSessionLocal, type ChatSession } from '../services/sessionStorage'
import { saveBuiltinSessionModelSelection } from '../services/builtinSessionModelStorage'
import { useCrossDeviceWebSocket } from '../hooks/useCrossDeviceWebSocket'
import { perfLog, perfLogEnd } from '../utils/perfLog'
import { mergeUserMessageKeepImage, toChatImageSrc } from '../utils/chatImage'
import { ChatInlineImage, ChatImageLightbox, type ImageLightboxPayload } from './ChatInlineImage'
import { useChatWebSocketFromPool } from '../hooks/useChatWebSocketFromPool'
import { ensureConnection, getConnection, getBuiltinModelProfilesViaPool, setGuiProviderViaPool, setLlmProviderViaPool } from '../services/chatWebSocketPool'
import {
  cloudHistoryThrottleKey,
  markCloudHistoryFetched,
  invalidateCloudHistoryThrottle,
} from '../services/chatCloudHistoryThrottle'
import { ME_AVATAR, ASSISTANT_AVATAR, CHAT_ASSISTANT_AVATAR, SKILL_LEARNING_AVATAR, CUSTOMER_SERVICE_AVATAR, GROUP_MANAGER_AVATAR } from '../constants/assistants'
import { toAvatarSrc, toAvatarSrcLikeContacts } from '../utils/avatar'
import { showInstallOverlay, hideInstallOverlay } from '../utils/installOverlay'
import { GroupAvatar } from './GroupAvatar'
import { ContactProfilePanel, type WorkflowWorkspacePayload } from './ContactProfilePanel'
import { getFriendsGroupAvatarSources, getGroupAvatarSourcesFromMembers } from '../utils/groupAvatar'
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import JSZip from 'jszip'
import { copyImageFromSrc } from '../utils/imageClipboard'
import { OPEN_SCHEDULED_JOB_EDITOR_EVENT } from './ScheduledTasksView'
import { getSkillDisplayName, toCanonicalSkillName } from '../services/skillNames'
import { addQuickNote } from '../services/quickNotesStorage'
import { addMySkill, loadAllMySkills } from '../services/skillStorage'
import { setToolGuardAutoAllowRoots } from '../utils/toolGuardConfirm'
import {
  TOPOCLAW_SAFE_MODE_CHANGED_EVENT,
  isTopoClawSafeModeEnabled,
  isTopoClawSessionSealed,
  sealTopoClawSession,
} from '../services/topoclawSafeMode'
import { parseAssistantShareCardContent, toAssistantSharePreview, type AssistantShareCardPayload } from '../services/assistantShareCard'
import { parseSkillShareCardContent, toSkillSharePreview, type SkillShareCardPayload } from '../services/skillShareCard'
import { parseSkillPackageBundleBase64 } from '../services/skillPackage'
import { listConversationSummaries, maybeGenerateConversationSummary } from '../services/conversationSummary'
import './ChatDetail.css'

const NEED_EXECUTION_GUIDE = '请点击右上角「执行」按钮（或切换到执行模式），发送当前手机屏幕截图，我将根据画面继续操作。'
const IDE_RECENT_FILES_STORAGE_KEY = 'topoclaw.ide.recent-files'
const GROUP_PROMPT_RECENT_LIMIT = 10
const GROUP_PROMPT_MAX_LINE_CHARS = 220
const OWNER_FEEDBACK_BLOCK_RE = /\[\[OWNER_FEEDBACK\]\][\s\S]*?\[\[\/OWNER_FEEDBACK\]\]/gi
const LOCAL_FILE_EXTENSION_SET = new Set([
  'txt', 'md', 'markdown', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'csv',
  'json', 'yaml', 'yml', 'xml', 'log', 'ini', 'cfg', 'conf',
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico',
  'py', 'ts', 'tsx', 'js', 'jsx', 'java', 'go', 'rs', 'cpp', 'c', 'h', 'hpp', 'cs',
  'sh', 'bat', 'ps1', 'sql', 'r', 'toml', 'lock',
  'zip', 'rar', '7z', 'tar', 'gz',
])
const LOCAL_IMAGE_EXTENSION_SET = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico'])
const MARKDOWN_WORKSPACE_IMAGE_CACHE = new Map<string, string>()
const ASSISTANT_MEDIA_MARKER_PREFIX = '[[__TC_MEDIA_'
const ASSISTANT_MEDIA_MARKER_RE = /\[\[__TC_MEDIA_(\d+)__\]\]/g

function stripNeedExecutionGuide(text: string): string {
  return text
    .replace(/\[NEED_EXECUTION\]\s*/g, '')
    .replace(NEED_EXECUTION_GUIDE, '')
    .replace(OWNER_FEEDBACK_BLOCK_RE, '')
    .replace(/\n{2,}/g, '\n\n')
    .trim()
}

/** 去重时规范化内容：strip 执行引导 + trim + 合并空白，确保本地显示与 broadcast 能正确匹配 */
function normalizeContentForDedupe(s: string): string {
  return stripNeedExecutionGuide(s)
    .replace(/\s+/g, ' ')
    .trim()
}

function trimForCloneContext(text: string, limit = 180): string {
  const oneLine = String(text || '')
    .replace(/\r/g, '\n')
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean)
    .join(' ')
    .trim()
  if (!oneLine) return ''
  if (oneLine.length <= limit) return oneLine
  return `${oneLine.slice(0, limit)}...`
}

function trimGroupPromptLine(text: string, limit = GROUP_PROMPT_MAX_LINE_CHARS): string {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim()
  if (!normalized) return ''
  if (normalized.length <= limit) return normalized
  return `${normalized.slice(0, Math.max(0, limit - 1))}…`
}

function sanitizeFileName(input: string): string {
  return (input || 'chat')
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, '_')
    .slice(0, 64) || 'chat'
}

function buildTopoclawWorkspaceHint(pathname: string, noun: '图片' | '文件' = '图片'): string {
  const p = pathname.trim()
  if (!p) return ''
  const guide = [
    `【系统提示】${noun}已写入本地 workspace。`,
    `[WORKSPACE_FILE_PATH] ${p}`,
    '请直接使用上面的绝对路径读取该文件，并基于文件内容继续后续工作，不要再次向用户询问文件路径。',
  ].join('\n')
  return guide
}

function buildTopoclawWorkspaceBatchHint(dirPath: string, total: number, success: number, failed: number): string {
  const p = dirPath.trim()
  if (!p) return ''
  return [
    '【系统提示】文件夹内容已批量写入本地 workspace。',
    `[WORKSPACE_DIR_PATH] ${p}`,
    `批量导入统计：总计 ${total}，成功 ${success}，失败 ${failed}。`,
    '请优先遍历该目录下全部文件并基于文件内容继续工作，不要再次向用户询问文件路径。',
  ].join('\n')
}

function buildDataUrl(base64: string, mime: string, fallbackMime = 'application/octet-stream'): string {
  const safeMime = typeof mime === 'string' && mime.includes('/') ? mime : fallbackMime
  return `data:${safeMime};base64,${base64}`
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error || new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

type DragEntry = {
  isFile: boolean
  isDirectory: boolean
  name: string
  file?: (success: (file: File) => void, error?: (err: DOMException) => void) => void
  createReader?: () => {
    readEntries: (success: (entries: DragEntry[]) => void, error?: (err: DOMException) => void) => void
  }
}

type WorkflowMemberItem = WorkflowWorkspacePayload['members'][number]

type WorkflowCanvasNode = {
  id: string
  sourceMemberId: string
  name: string
  type: WorkflowMemberItem['type'] | 'decision'
  nodeTaskIntro?: string
  userRoleIntro?: string
  decisionAssigneeId?: string
  decisionResponsibility?: string
  decisionCondition?: string
  decisionCodeExample?: string
  x: number
  y: number
}

type WorkflowCanvasEdge = {
  id: string
  fromNodeId: string
  toNodeId: string
}

type WorkflowEditTarget =
  | { type: 'node'; id: string }
  | { type: 'edge'; id: string }
  | null

type WorkflowViewportState = {
  scale: number
  offsetX: number
  offsetY: number
}

type WorkflowDecisionAssigneeOption = {
  id: string
  label: string
  kind: 'code' | 'assistant' | 'user'
}

type WorkflowPersistMeta = {
  workflowId: string
  name: string
  groupId: string
  groupName: string
  createdAt: number
  updatedAt: number
  appVersion: string
}

type WorkflowPersistPayloadV1 = {
  schemaVersion: 1
  meta: WorkflowPersistMeta
  graph: {
    nodes: WorkflowCanvasNode[]
    edges: WorkflowCanvasEdge[]
  }
  ui?: {
    viewport?: WorkflowViewportState
    selectedNodeId?: string
    selectedEdgeId?: string
  }
  extras?: Record<string, unknown>
}

type WorkflowRunNodeStatus = 'idle' | 'running' | 'success' | 'failed'

type WorkflowRunLogItem = {
  id: string
  ts: number
  level: 'info' | 'success' | 'error'
  text: string
}

const WORKFLOW_NODE_WIDTH = 88
const WORKFLOW_NODE_HEIGHT = 44
const WORKFLOW_GRID_SIZE = 8
const WORKFLOW_STAGE_WIDTH = 3200
const WORKFLOW_STAGE_HEIGHT = 2200
const WORKFLOW_MIN_SCALE = 0.5
const WORKFLOW_MAX_SCALE = 2.4
const WORKFLOW_DEFAULT_SCALE = 1
const WORKFLOW_EDGE_ARROW_BACKOFF = 8
const WORKFLOW_FILE_SUFFIX = '.topowf.json'
const WORKFLOW_DRAFT_STORAGE_KEY_PREFIX = 'topoclaw_workflow_draft_'
const WORKFLOW_APP_VERSION = 'TopoDesktop'

function snapWorkflowPosition(value: number, max: number): number {
  const clamped = Math.min(max, Math.max(0, value))
  const snapped = Math.round(clamped / WORKFLOW_GRID_SIZE) * WORKFLOW_GRID_SIZE
  return Math.min(max, Math.max(0, snapped))
}

function clampWorkflowScale(value: number): number {
  return Math.min(WORKFLOW_MAX_SCALE, Math.max(WORKFLOW_MIN_SCALE, value))
}

function buildWorkflowEdgePath(
  from: { x: number; y: number },
  to: { x: number; y: number }
): string {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const curvature = Math.max(28, Math.min(120, Math.abs(dx) * 0.45 + Math.abs(dy) * 0.2))
  const backflowCurvature = Math.max(36, Math.min(140, Math.abs(dx) * 0.35 + Math.abs(dy) * 0.45))
  const c1x = dx >= 0 ? from.x + curvature : from.x + backflowCurvature
  const c2x = dx >= 0 ? to.x - curvature : to.x - backflowCurvature
  return `M ${from.x} ${from.y} C ${c1x} ${from.y}, ${c2x} ${to.y}, ${to.x} ${to.y}`
}

function pullBackWorkflowPoint(
  from: { x: number; y: number },
  to: { x: number; y: number },
  backoff: number
): { x: number; y: number } {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const len = Math.hypot(dx, dy)
  if (len < 1e-6) return to
  const safeBackoff = Math.min(backoff, Math.max(0, len - 1))
  return {
    x: to.x - (dx / len) * safeBackoff,
    y: to.y - (dy / len) * safeBackoff,
  }
}

function sanitizeWorkflowNodes(input: unknown): WorkflowCanvasNode[] {
  if (!Array.isArray(input)) return []
  const out: WorkflowCanvasNode[] = []
  const seen = new Set<string>()
  for (const item of input) {
    if (!item || typeof item !== 'object') continue
    const src = item as Record<string, unknown>
    const id = String(src.id || '').trim()
    if (!id || seen.has(id)) continue
    const name = String(src.name || '').trim()
    const sourceMemberId = String(src.sourceMemberId || '').trim()
    const typeRaw = String(src.type || '').trim()
    const type: WorkflowCanvasNode['type'] =
      typeRaw === 'assistant' || typeRaw === 'user' || typeRaw === 'decision'
        ? typeRaw
        : 'assistant'
    const x = Number(src.x)
    const y = Number(src.y)
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue
    const node: WorkflowCanvasNode = {
      id,
      sourceMemberId,
      name: name || id,
      type,
      x,
      y,
    }
    if (typeof src.userRoleIntro === 'string') node.userRoleIntro = src.userRoleIntro
    if (typeof src.nodeTaskIntro === 'string') node.nodeTaskIntro = src.nodeTaskIntro
    if (typeof src.decisionAssigneeId === 'string') node.decisionAssigneeId = src.decisionAssigneeId
    if (typeof src.decisionResponsibility === 'string') node.decisionResponsibility = src.decisionResponsibility
    if (typeof src.decisionCondition === 'string') node.decisionCondition = src.decisionCondition
    if (typeof src.decisionCodeExample === 'string') node.decisionCodeExample = src.decisionCodeExample
    out.push(node)
    seen.add(id)
  }
  return out
}

function sanitizeWorkflowEdges(input: unknown, nodeIds: Set<string>): WorkflowCanvasEdge[] {
  if (!Array.isArray(input)) return []
  const out: WorkflowCanvasEdge[] = []
  const seenId = new Set<string>()
  const seenPair = new Set<string>()
  for (const item of input) {
    if (!item || typeof item !== 'object') continue
    const src = item as Record<string, unknown>
    const id = String(src.id || '').trim()
    const fromNodeId = String(src.fromNodeId || '').trim()
    const toNodeId = String(src.toNodeId || '').trim()
    if (!id || seenId.has(id)) continue
    if (!fromNodeId || !toNodeId || fromNodeId === toNodeId) continue
    if (!nodeIds.has(fromNodeId) || !nodeIds.has(toNodeId)) continue
    const pairKey = `${fromNodeId}->${toNodeId}`
    if (seenPair.has(pairKey)) continue
    out.push({ id, fromNodeId, toNodeId })
    seenId.add(id)
    seenPair.add(pairKey)
  }
  return out
}

function sanitizeWorkflowViewport(input: unknown): WorkflowViewportState {
  if (!input || typeof input !== 'object') {
    return { scale: WORKFLOW_DEFAULT_SCALE, offsetX: 0, offsetY: 0 }
  }
  const src = input as Record<string, unknown>
  const scaleRaw = Number(src.scale)
  const offsetXRaw = Number(src.offsetX)
  const offsetYRaw = Number(src.offsetY)
  return {
    scale: Number.isFinite(scaleRaw) ? clampWorkflowScale(scaleRaw) : WORKFLOW_DEFAULT_SCALE,
    offsetX: Number.isFinite(offsetXRaw) ? offsetXRaw : 0,
    offsetY: Number.isFinite(offsetYRaw) ? offsetYRaw : 0,
  }
}

function sanitizeWorkflowPayloadV1(raw: unknown): WorkflowPersistPayloadV1 | null {
  if (!raw || typeof raw !== 'object') return null
  const src = raw as Record<string, unknown>
  const graph = src.graph && typeof src.graph === 'object' ? (src.graph as Record<string, unknown>) : null
  const rawNodes = graph?.nodes
  const rawEdges = graph?.edges
  const nodes = sanitizeWorkflowNodes(rawNodes)
  const nodeIdSet = new Set(nodes.map((node) => node.id))
  const edges = sanitizeWorkflowEdges(rawEdges, nodeIdSet)
  const metaSrc = src.meta && typeof src.meta === 'object' ? (src.meta as Record<string, unknown>) : {}
  const now = Date.now()
  const workflowId = String(metaSrc.workflowId || '').trim() || `wf_${now}`
  const meta: WorkflowPersistMeta = {
    workflowId,
    name: String(metaSrc.name || '').trim() || '未命名编排',
    groupId: String(metaSrc.groupId || '').trim(),
    groupName: String(metaSrc.groupName || '').trim(),
    createdAt: Number.isFinite(Number(metaSrc.createdAt)) ? Number(metaSrc.createdAt) : now,
    updatedAt: Number.isFinite(Number(metaSrc.updatedAt)) ? Number(metaSrc.updatedAt) : now,
    appVersion: String(metaSrc.appVersion || '').trim() || WORKFLOW_APP_VERSION,
  }
  const uiSrc = src.ui && typeof src.ui === 'object' ? (src.ui as Record<string, unknown>) : {}
  const selectedNodeId = String(uiSrc.selectedNodeId || '').trim()
  const selectedEdgeId = String(uiSrc.selectedEdgeId || '').trim()
  const ui = {
    viewport: sanitizeWorkflowViewport(uiSrc.viewport),
    ...(selectedNodeId ? { selectedNodeId } : {}),
    ...(selectedEdgeId ? { selectedEdgeId } : {}),
  }
  return {
    schemaVersion: 1,
    meta,
    graph: { nodes, edges },
    ui,
    extras: src.extras && typeof src.extras === 'object' ? (src.extras as Record<string, unknown>) : {},
  }
}

function migrateWorkflowPayload(raw: unknown): WorkflowPersistPayloadV1 | null {
  if (!raw || typeof raw !== 'object') return null
  const src = raw as Record<string, unknown>
  const schemaVersion = Number(src.schemaVersion || 1)
  if (schemaVersion === 1 && src.graph) {
    return sanitizeWorkflowPayloadV1(src)
  }
  // Legacy fallback: accept flat { nodes, edges } payloads from early prototypes.
  if (Array.isArray(src.nodes) && Array.isArray(src.edges)) {
    return sanitizeWorkflowPayloadV1({
      schemaVersion: 1,
      meta: {
        workflowId: `wf_${Date.now()}`,
        name: String(src.name || '未命名编排'),
        groupId: String(src.groupId || ''),
        groupName: String(src.groupName || ''),
        createdAt: Date.now(),
        updatedAt: Date.now(),
        appVersion: WORKFLOW_APP_VERSION,
      },
      graph: {
        nodes: src.nodes,
        edges: src.edges,
      },
      ui: {
        viewport: src.viewport,
      },
      extras: {},
    })
  }
  return null
}

function getDragEntry(item: DataTransferItem): DragEntry | null {
  const it = item as unknown as {
    webkitGetAsEntry?: () => DragEntry | null
    getAsEntry?: () => DragEntry | null
  }
  if (typeof it.webkitGetAsEntry === 'function') return it.webkitGetAsEntry()
  if (typeof it.getAsEntry === 'function') return it.getAsEntry()
  return null
}

function getDroppedTopLevelDirectoryNames(dataTransfer: DataTransfer): string[] {
  const names: string[] = []
  const items = Array.from(dataTransfer.items || [])
  for (const item of items) {
    if (item.kind !== 'file') continue
    const entry = getDragEntry(item)
    if (!entry || !entry.isDirectory) continue
    const name = String(entry.name || '').trim()
    if (!name) continue
    if (names.some((v) => v.toLowerCase() === name.toLowerCase())) continue
    names.push(name)
  }
  return names
}

function readAllDragDirectoryEntries(reader: { readEntries: (success: (entries: DragEntry[]) => void, error?: (err: DOMException) => void) => void }): Promise<DragEntry[]> {
  return new Promise((resolve, reject) => {
    const out: DragEntry[] = []
    const loop = () => {
      reader.readEntries(
        (entries) => {
          if (!entries || entries.length === 0) {
            resolve(out)
            return
          }
          out.push(...entries)
          loop()
        },
        (err) => reject(err)
      )
    }
    loop()
  })
}

async function collectFilesFromDragEntry(entry: DragEntry): Promise<File[]> {
  if (entry.isFile && typeof entry.file === 'function') {
    const file = await new Promise<File | null>((resolve) => {
      entry.file!(
        (f) => resolve(f),
        () => resolve(null)
      )
    })
    return file ? [file] : []
  }
  if (entry.isDirectory && typeof entry.createReader === 'function') {
    const reader = entry.createReader()
    const children = await readAllDragDirectoryEntries(reader)
    const nested = await Promise.all(children.map((child) => collectFilesFromDragEntry(child)))
    return nested.flat()
  }
  return []
}

async function collectDroppedFiles(dataTransfer: DataTransfer): Promise<File[]> {
  const out: File[] = []
  const items = Array.from(dataTransfer.items || [])
  for (const item of items) {
    if (item.kind !== 'file') continue
    const entry = getDragEntry(item)
    if (entry) {
      const files = await collectFilesFromDragEntry(entry)
      if (files.length > 0) {
        out.push(...files)
        continue
      }
    }
    const f = item.getAsFile()
    if (f) out.push(f)
  }
  if (out.length === 0) {
    out.push(...Array.from(dataTransfer.files || []))
  }
  return out
}

function triggerBlobDownload(fileName: string, blob: Blob): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function csvEscapeCell(value: string): string {
  const raw = String(value ?? '')
  if (/[",\r\n]/.test(raw)) {
    return `"${raw.replace(/"/g, '""')}"`
  }
  return raw
}

function tableElementToCsv(table: HTMLTableElement): string {
  const rows = Array.from(table.querySelectorAll('tr'))
  return rows
    .map((row) => {
      const cells = Array.from(row.querySelectorAll('th,td'))
      return cells.map((cell) => csvEscapeCell((cell.textContent || '').trim())).join(',')
    })
    .join('\r\n')
}

function buildCsvDefaultFileName(): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const d = new Date()
  return `table-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.csv`
}

function normalizeWebUrl(text: string): string | null {
  const s = text.trim().replace(/^["']|["']$/g, '')
  if (!s) return null
  if (/\s/.test(s)) return null
  if (/^https?:\/\/[^\s]+$/i.test(s)) return s
  if (/^www\.[^\s]+$/i.test(s)) return `https://${s}`
  return null
}

function looksLikeLocalFileToken(text: string): boolean {
  const s = text.trim().replace(/^["']|["']$/g, '')
  if (!s) return false
  if (normalizeWebUrl(s)) return false
  // Windows modern path handling and workspace-generated names can exceed MAX_PATH.
  // Keep a generous cap only to avoid obviously malformed huge tokens.
  if (s.length > 2048) return false
  if (/\s/.test(s)) return false
  if (/^\.[a-zA-Z0-9]{1,16}$/.test(s)) return false
  const extMatch = s.match(/\.([a-zA-Z0-9]{1,16})$/)
  const ext = (extMatch?.[1] || '').toLowerCase()
  const hasKnownExt = !!ext && LOCAL_FILE_EXTENSION_SET.has(ext)
  const hasPathHint = /[\\/]/.test(s) || /^[a-zA-Z]:/.test(s) || /^\.\.?\//.test(s) || /^\.\.\\/.test(s)
  if (!hasKnownExt && !hasPathHint) return false
  if (hasKnownExt) return true
  // no extension but clearly a local path (e.g. absolute/relative directory)
  return hasPathHint
}

function buildAssistantMediaMarker(index: number): string {
  return `${ASSISTANT_MEDIA_MARKER_PREFIX}${Math.max(0, Math.floor(index))}__]]`
}

function extractAssistantMediaMarkerIndices(content: string): number[] {
  const src = String(content || '')
  if (!src) return []
  const out: number[] = []
  ASSISTANT_MEDIA_MARKER_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = ASSISTANT_MEDIA_MARKER_RE.exec(src)) !== null) {
    const idx = Number(m[1])
    if (Number.isFinite(idx) && idx >= 0) out.push(idx)
  }
  ASSISTANT_MEDIA_MARKER_RE.lastIndex = 0
  return out
}

function contentHasAssistantMediaMarker(content: string, index: number): boolean {
  if (!content) return false
  return String(content).includes(buildAssistantMediaMarker(index))
}

function appendAssistantMediaMarkerToContent(content: string, index: number): string {
  const marker = buildAssistantMediaMarker(index)
  const raw = String(content || '')
  if (contentHasAssistantMediaMarker(raw, index)) return raw
  const base = raw.trim() === '[图片]' ? '' : raw
  if (!base) return marker
  const normalizedBase = base.endsWith('\n') ? base : `${base}\n`
  return `${normalizedBase}${marker}`
}

function normalizeLocalPathToken(input: string): string {
  let raw = String(input || '').trim()
  if (!raw) return ''
  // Some markdown/urlTransform pipelines may percent-encode slashes.
  raw = raw.replace(/%5C/ig, '\\').replace(/%2F/ig, '/')
  try {
    raw = decodeURIComponent(raw)
  } catch {
    // Keep best-effort decoded token
  }
  // Markdown link destination may swallow the slash after drive letter (e.g. D:Users\...)
  if (/^[a-zA-Z]:(?![\\/])/.test(raw)) return `${raw.slice(0, 2)}\\${raw.slice(2)}`
  return raw
}

function inferImageMimeFromPath(inputPath: string): string {
  const normalized = String(inputPath || '').trim().toLowerCase()
  if (normalized.endsWith('.jpg') || normalized.endsWith('.jpeg')) return 'image/jpeg'
  if (normalized.endsWith('.gif')) return 'image/gif'
  if (normalized.endsWith('.webp')) return 'image/webp'
  if (normalized.endsWith('.bmp')) return 'image/bmp'
  if (normalized.endsWith('.svg')) return 'image/svg+xml'
  if (normalized.endsWith('.ico')) return 'image/x-icon'
  return 'image/png'
}

function isLikelyWorkspaceRelativeImageToken(raw: string): boolean {
  const src = normalizeLocalPathToken(String(raw || '').trim().replace(/^["']|["']$/g, ''))
  if (!src) return false
  if (/^(data:|blob:|https?:\/\/)/i.test(src)) return false
  if (/^[a-zA-Z][a-zA-Z\d+\-.]*:/.test(src) && !/^[a-zA-Z]:[\\/]/.test(src)) return false
  if (!looksLikeLocalFileToken(src)) return false
  const extMatch = src.match(/\.([a-zA-Z0-9]{1,16})$/)
  const ext = (extMatch?.[1] || '').toLowerCase()
  return !!ext && LOCAL_IMAGE_EXTENSION_SET.has(ext)
}

function MarkdownWorkspaceImage(props: React.ComponentPropsWithoutRef<'img'> & { node?: unknown }) {
  const { node: _node, src, alt, ...rest } = props
  const normalizedSrc = normalizeLocalPathToken(String(src || '').trim().replace(/^["']|["']$/g, ''))
  const shouldResolveFromWorkspace = isLikelyWorkspaceRelativeImageToken(normalizedSrc)
  const [resolvedDataUrl, setResolvedDataUrl] = useState<string>('')

  useEffect(() => {
    let cancelled = false
    if (!shouldResolveFromWorkspace) {
      setResolvedDataUrl('')
      return () => { cancelled = true }
    }
    const cached = MARKDOWN_WORKSPACE_IMAGE_CACHE.get(normalizedSrc)
    if (cached) {
      setResolvedDataUrl(cached)
      return () => { cancelled = true }
    }
    setResolvedDataUrl('')
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.readBinaryFile) return () => { cancelled = true }
    const readBinaryFile = api.readBinaryFile

    void (async () => {
      try {
        let absPath = ''
        if (/^[a-zA-Z]:[\\/]/.test(normalizedSrc) || normalizedSrc.startsWith('/') || normalizedSrc.startsWith('\\')) {
          absPath = normalizedSrc
        } else if (api.resolveGeneratedFile) {
          const resolved = await api.resolveGeneratedFile(normalizedSrc)
          if (resolved?.success && resolved.path) absPath = String(resolved.path).trim()
        }
        if (!absPath) return
        const binary = await readBinaryFile(absPath)
        if (!binary?.ok || !binary.base64) return
        const mime = inferImageMimeFromPath(binary.path || absPath)
        const dataUrl = buildDataUrl(binary.base64, mime, 'image/png')
        MARKDOWN_WORKSPACE_IMAGE_CACHE.set(normalizedSrc, dataUrl)
        if (!cancelled) setResolvedDataUrl(dataUrl)
      } catch (err) {
        console.warn('[MessageMarkdown] workspace image resolve failed:', err)
      }
    })()

    return () => { cancelled = true }
  }, [normalizedSrc, shouldResolveFromWorkspace])

  if (shouldResolveFromWorkspace && !resolvedDataUrl) {
    return <span className="message-inline-image-loading">{alt || '图片加载中…'}</span>
  }
  return <img {...rest} src={shouldResolveFromWorkspace ? resolvedDataUrl : normalizedSrc} alt={alt || ''} loading="lazy" />
}

type TableMenuPayload = {
  x: number
  y: number
  csvText: string
  defaultFileName: string
  enterEdit: () => void
}

function MarkdownTable({
  node: _node,
  children,
  className,
  onRequestMenu,
  ...rest
}: React.ComponentPropsWithoutRef<'table'> & {
  node?: unknown
  onRequestMenu?: (payload: TableMenuPayload) => void
}) {
  const tableRef = useRef<HTMLTableElement | null>(null)
  const [editable, setEditable] = useState(false)

  const resolvedClassName = `${className || ''}${editable ? ' is-editable' : ''}`.trim()

  return (
    <div
      className={`message-markdown-table-wrap${editable ? ' is-editable' : ''}`}
      onDoubleClick={(e) => {
        e.stopPropagation()
        setEditable(true)
        requestAnimationFrame(() => tableRef.current?.focus())
      }}
      onContextMenu={(e) => {
        const table = tableRef.current
        if (!table || !onRequestMenu) return
        e.preventDefault()
        e.stopPropagation()
        onRequestMenu({
          x: e.clientX,
          y: e.clientY,
          csvText: tableElementToCsv(table),
          defaultFileName: buildCsvDefaultFileName(),
          enterEdit: () => {
            setEditable(true)
            requestAnimationFrame(() => tableRef.current?.focus())
          },
        })
      }}
    >
      <table
        {...rest}
        ref={tableRef}
        className={resolvedClassName}
        contentEditable={editable}
        suppressContentEditableWarning
        tabIndex={editable ? 0 : -1}
        onBlur={() => setEditable(false)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            setEditable(false)
          }
        }}
      >
        {children}
      </table>
    </div>
  )
}

/** 群组管理小助手执行完成后的跟进：最多 100 轮 */
const MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS = 100

interface PendingGroupManagerFollowUp {
  groupConvId: string
  userQuery: string
  executedAssistant: { id: string; name: string }
  executedCommand: string
  groupManagerBaseUrl: string
  groupManagerName: string
  groupManagerId: string
  assistants: Array<{ id: string; name: string }>
  assistantConfigs?: Record<string, { baseUrl?: string; name?: string; displayId?: string; capabilities?: string[]; rolePrompt?: string }>
  members?: Array<{ imei: string; displayName: string }>
  senderName?: string
  round: number
}

/** 群组管理小助手优先使用连接池长连接，否则回退到短连接 */
async function sendGroupManagerChatViaPoolOrStream(
  params: {
    threadId: string
    message: string
    images: string[]
    imei?: string
    baseUrl: string
    isGroupManager: boolean
  },
  callbacks: {
    onDelta: (d: string) => void
    onReasoning?: (reasoning: string) => void
    onMedia?: (media: { fileBase64: string; fileName?: string; content?: string; messageType?: 'image' | 'file' }) => void
    onToolCall?: (name: string) => void
    onSkillGenerated?: (skill: Skill) => void
    onNeedExecution?: (chatSummary: string) => void
  }
): Promise<{ fullText: string; needExecutionFired: boolean }> {
  const { threadId, message, images, imei, baseUrl, isGroupManager } = params
  if (isGroupManager && baseUrl) {
    ensureConnection(baseUrl)
    const conn = getConnection(baseUrl)
    if (conn) {
      return conn.sendChat(threadId, message, images, undefined, callbacks)
    }
  }
  return sendChatAssistantMessageStream(
    { uuid: threadId, query: message, images, imei: imei ?? '' },
    baseUrl,
    callbacks.onDelta,
    callbacks.onReasoning,
    callbacks.onMedia,
    callbacks.onToolCall,
    callbacks.onSkillGenerated,
    callbacks.onNeedExecution
  )
}

/** 构建执行结果反馈消息，供群组管理小助手判断任务完成情况 */
function buildExecutionFeedbackMessage(
  userQuery: string,
  executedAssistant: string,
  executedCommand: string,
  resultContent: string,
  round: number,
  assistants: Array<{ id: string; name: string }> = [],
  options?: { members?: Array<{ imei: string; displayName: string }>; senderName?: string }
): string {
  const groupContext = buildGroupAssistantContext(assistants)
  const senderPrefix = options?.senderName ? `【发件人：${options.senderName}】` : ''
  return `【执行结果反馈】\n\n${groupContext}${senderPrefix}用户原始请求：${userQuery}\n执行小助手：${executedAssistant}\n执行指令：${executedCommand}\n执行结果：${resultContent}\n当前轮次：${round}/${MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS}`
}

/**
 * 以云侧为准：仅使用 remote，不合并 local-only，避免删除后刷新又出现。
 * 同 id 时取 createdAt 较大者或 title 非空者（用于 sync 返回与 local 的合并）。
 */
function mergeSessionsByRemote(local: ChatSession[], remote: ChatSession[]): ChatSession[] {
  const localById = new Map(local.map((s) => [s.id, s]))
  const result: ChatSession[] = []
  for (const s of remote) {
    const localVer = localById.get(s.id)
    const best = !localVer
      ? s
      : s.createdAt > localVer.createdAt || (s.createdAt === localVer.createdAt && s.title && !localVer.title)
        ? s
        : localVer
    result.push(best)
  }
  return result.sort((a, b) => b.createdAt - a.createdAt)
}

function getCodeTextFromPreChildren(children: React.ReactNode): string {
  const child = Children.toArray(children)[0]
  if (!child || !isValidElement(child)) return ''
  const c = child as React.ReactElement<{ children?: React.ReactNode }>
  const inner = c.props?.children
  if (typeof inner === 'string') return inner
  if (Array.isArray(inner)) return inner.map((x) => (typeof x === 'string' ? x : '')).join('')
  return ''
}

let mermaidApiPromise: Promise<(typeof import('mermaid'))['default']> | null = null
let mermaidInitialized = false
const MERMAID_SVG_CACHE_LIMIT = 120
const mermaidSvgCache = new Map<string, string>()

function getCachedMermaidSvg(code: string): string {
  const key = String(code || '')
  if (!key) return ''
  const hit = mermaidSvgCache.get(key)
  if (!hit) return ''
  // refresh insertion order for simple LRU behavior
  mermaidSvgCache.delete(key)
  mermaidSvgCache.set(key, hit)
  return hit
}

function setCachedMermaidSvg(code: string, svg: string): void {
  const key = String(code || '')
  const value = String(svg || '')
  if (!key || !value) return
  if (mermaidSvgCache.has(key)) mermaidSvgCache.delete(key)
  mermaidSvgCache.set(key, value)
  if (mermaidSvgCache.size <= MERMAID_SVG_CACHE_LIMIT) return
  const oldestKey = mermaidSvgCache.keys().next().value as string | undefined
  if (oldestKey) mermaidSvgCache.delete(oldestKey)
}

async function getMermaidApi(): Promise<(typeof import('mermaid'))['default']> {
  if (!mermaidApiPromise) {
    mermaidApiPromise = import('mermaid').then((mod) => mod.default)
  }
  const mermaidApi = await mermaidApiPromise
  if (!mermaidInitialized) {
    mermaidApi.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'default',
      suppressErrorRendering: true,
    })
    mermaidInitialized = true
  }
  return mermaidApi
}

async function renderMermaidSvg(code: string): Promise<string> {
  const mermaidApi = await getMermaidApi()
  const renderId = `topoclaw-mermaid-${Date.now()}-${Math.random().toString(36).slice(2)}`
  const output = await mermaidApi.render(renderId, code)
  if (typeof output === 'string') return output
  return output.svg
}

async function mermaidSvgToPngBlob(svgMarkup: string): Promise<Blob> {
  try {
    const svgBlob = new Blob([svgMarkup], { type: 'image/svg+xml;charset=utf-8' })
    const svgUrl = URL.createObjectURL(svgBlob)
    try {
      const img = new Image()
      await new Promise<void>((resolve, reject) => {
        img.onload = () => resolve()
        img.onerror = () => reject(new Error('加载 SVG 失败'))
        img.src = svgUrl
      })
      const width = Math.max(1, Math.ceil(img.naturalWidth || 1))
      const height = Math.max(1, Math.ceil(img.naturalHeight || 1))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const ctx = canvas.getContext('2d')
      if (!ctx) throw new Error('无法创建 Canvas 上下文')
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(img, 0, 0, width, height)
      const pngBlob = await new Promise<Blob>((resolve, reject) => {
        canvas.toBlob((blob) => {
          if (blob) resolve(blob)
          else reject(new Error('导出 PNG 失败'))
        }, 'image/png')
      })
      return pngBlob
    } finally {
      URL.revokeObjectURL(svgUrl)
    }
  } catch {
    // Fallback: rasterize rendered SVG DOM via html2canvas (works better on some Electron/WebView envs).
    const host = document.createElement('div')
    host.style.position = 'fixed'
    host.style.left = '-10000px'
    host.style.top = '0'
    host.style.background = '#ffffff'
    host.style.padding = '0'
    host.style.margin = '0'
    host.style.zIndex = '-1'
    host.innerHTML = svgMarkup
    document.body.appendChild(host)
    try {
      const target = (host.firstElementChild as HTMLElement | null) ?? host
      const canvas = await html2canvas(target, {
        scale: 2,
        backgroundColor: '#ffffff',
        useCORS: true,
        logging: false,
        windowWidth: Math.max(1, target.scrollWidth || target.clientWidth || 1),
        windowHeight: Math.max(1, target.scrollHeight || target.clientHeight || 1),
      })
      const blob = await new Promise<Blob>((resolve, reject) => {
        canvas.toBlob((out) => {
          if (out) resolve(out)
          else reject(new Error('导出 PNG 失败'))
        }, 'image/png')
      })
      return blob
    } finally {
      host.remove()
    }
  }
}

interface PythonCodeBlockWrapperProps extends React.ComponentPropsWithoutRef<'pre'> {
  messageId?: string
  isLastAssistantItem?: boolean
  autoExecute?: boolean
  executedIdsRef?: React.MutableRefObject<Set<string>>
}

interface EditablePreBlockProps extends React.ComponentPropsWithoutRef<'pre'> {
  onSaveToQuickNote?: (text: string) => void
}

interface MermaidPreBlockProps extends React.ComponentPropsWithoutRef<'pre'> {
  messageId?: string
  onSaveToQuickNote?: (text: string) => void
}

function EditablePreBlock({
  children,
  onSaveToQuickNote,
  ...preProps
}: EditablePreBlockProps) {
  const [editable, setEditable] = useState(false)
  const [content, setContent] = useState<string>(() => getCodeTextFromPreChildren(children))
  const preRef = useRef<HTMLPreElement | null>(null)

  useEffect(() => {
    if (editable) return
    setContent(getCodeTextFromPreChildren(children))
  }, [children, editable])

  const handleCopy = useCallback(async () => {
    const text = (preRef.current?.innerText || content || '').trimEnd()
    if (!text) return
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      /* ignore */
    }
  }, [content])

  const handleSave = useCallback(() => {
    const text = (preRef.current?.innerText || content || '').trim()
    if (!text) return
    onSaveToQuickNote?.(text)
  }, [content, onSaveToQuickNote])

  const handleEditToggle = useCallback(() => {
    if (editable) {
      const text = preRef.current?.innerText ?? content
      setContent(text)
      setEditable(false)
      preRef.current?.blur()
      return
    }
    setEditable(true)
    requestAnimationFrame(() => preRef.current?.focus())
  }, [content, editable])

  return (
    <div
      className={`message-pre-block${editable ? ' is-editing' : ''}`}
      onDoubleClick={(e) => {
        e.stopPropagation()
        setEditable(true)
        requestAnimationFrame(() => preRef.current?.focus())
      }}
    >
      <div className="message-pre-toolbar">
        <button
          type="button"
          className="message-pre-toolbar-btn"
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            handleEditToggle()
          }}
        >
          {editable ? '保存' : '编辑'}
        </button>
        <button
          type="button"
          className="message-pre-toolbar-btn"
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            void handleCopy()
          }}
        >
          复制
        </button>
        <button
          type="button"
          className="message-pre-toolbar-btn"
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            handleSave()
          }}
        >
          保存到随手记
        </button>
      </div>
      <pre
        {...preProps}
        ref={preRef}
        contentEditable={editable}
        suppressContentEditableWarning
        spellCheck={false}
        tabIndex={0}
        onInput={(e) => setContent((e.currentTarget as HTMLPreElement).innerText)}
        onBlur={() => setEditable(false)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault()
            setEditable(false)
            preRef.current?.blur()
          }
        }}
      >
        {content}
      </pre>
    </div>
  )
}

function MermaidPreBlock({
  children,
  messageId,
  onSaveToQuickNote,
  ...preProps
}: MermaidPreBlockProps) {
  const mermaidCode = useMemo(() => getCodeTextFromPreChildren(children).trim(), [children])
  const [svg, setSvg] = useState<string>(() => getCachedMermaidSvg(mermaidCode))
  const [error, setError] = useState('')
  const [rendering, setRendering] = useState<boolean>(() => !!mermaidCode && !getCachedMermaidSvg(mermaidCode))
  const [exporting, setExporting] = useState(false)
  const [copyingImage, setCopyingImage] = useState(false)
  const [notice, setNotice] = useState('')
  const noticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const renderTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mermaidCanvasRef = useRef<HTMLDivElement | null>(null)
  const latestSvgRef = useRef(svg)
  const lastRenderedCodeRef = useRef(mermaidCode && svg ? mermaidCode : '')

  const showNotice = useCallback((text: string) => {
    setNotice(text)
    if (noticeTimerRef.current) {
      clearTimeout(noticeTimerRef.current)
      noticeTimerRef.current = null
    }
    noticeTimerRef.current = setTimeout(() => {
      setNotice('')
      noticeTimerRef.current = null
    }, 2200)
  }, [])

  useEffect(() => {
    latestSvgRef.current = svg
  }, [svg])

  useEffect(() => {
    if (renderTimerRef.current) {
      clearTimeout(renderTimerRef.current)
      renderTimerRef.current = null
    }
    if (!mermaidCode) {
      setSvg('')
      setError('')
      setRendering(false)
      lastRenderedCodeRef.current = ''
      return
    }
    const cachedSvg = getCachedMermaidSvg(mermaidCode)
    if (cachedSvg) {
      setSvg(cachedSvg)
      setError('')
      setRendering(false)
      lastRenderedCodeRef.current = mermaidCode
      return
    }
    if (mermaidCode === lastRenderedCodeRef.current) return
    let cancelled = false
    setRendering(true)
    renderTimerRef.current = setTimeout(() => {
      void (async () => {
        try {
          const renderedSvg = await renderMermaidSvg(mermaidCode)
          if (cancelled) return
          setCachedMermaidSvg(mermaidCode, renderedSvg)
          setSvg(renderedSvg)
          setError('')
          lastRenderedCodeRef.current = mermaidCode
        } catch (err) {
          if (cancelled) return
          const msg = err instanceof Error ? err.message : String(err)
          if (!latestSvgRef.current) {
            setError(msg || 'Mermaid 渲染失败')
          }
        } finally {
          if (!cancelled) setRendering(false)
        }
      })()
    }, 320)
    return () => {
      cancelled = true
      if (renderTimerRef.current) {
        clearTimeout(renderTimerRef.current)
        renderTimerRef.current = null
      }
    }
  }, [mermaidCode])

  useEffect(() => () => {
    if (noticeTimerRef.current) {
      clearTimeout(noticeTimerRef.current)
      noticeTimerRef.current = null
    }
    if (renderTimerRef.current) {
      clearTimeout(renderTimerRef.current)
      renderTimerRef.current = null
    }
  }, [])

  const handleCopyCode = useCallback(async () => {
    if (!mermaidCode.trim()) {
      showNotice('当前无可复制源码')
      return
    }
    try {
      await navigator.clipboard.writeText(mermaidCode)
      showNotice('已复制 Mermaid 源码')
    } catch {
      showNotice('复制失败')
    }
  }, [mermaidCode, showNotice])

  const handleExportPng = useCallback(async () => {
    if (!svg.trim()) {
      showNotice('图表尚未渲染完成')
      return
    }
    setExporting(true)
    try {
      const target = mermaidCanvasRef.current
      if (!target) throw new Error('图表节点不可用')
      const canvas = await html2canvas(target, {
        scale: 2,
        backgroundColor: '#ffffff',
        useCORS: true,
        logging: false,
        windowWidth: Math.max(1, target.scrollWidth || target.clientWidth || 1),
        windowHeight: Math.max(1, target.scrollHeight || target.clientHeight || 1),
      })
      const dataUrl = canvas.toDataURL('image/png')
      const stamp = new Date().toISOString().replace(/[:.]/g, '-')
      const name = sanitizeFileName(messageId ? `mermaid_${messageId}_${stamp}` : `mermaid_${stamp}`)
      const api = window.electronAPI?.saveImageAs
      if (api) {
        const r = await api(dataUrl, `${name}.png`)
        if (!r.ok && !r.canceled) throw new Error(r.error || '保存失败')
      } else {
        const res = await fetch(dataUrl)
        const pngBlob = await res.blob()
        triggerBlobDownload(`${name}.png`, pngBlob)
      }
      showNotice('已保存到本地')
    } catch {
      showNotice('保存失败')
    } finally {
      setExporting(false)
    }
  }, [messageId, showNotice, svg])

  const handleCopyImageToClipboard = useCallback(async () => {
    if (!svg.trim()) {
      showNotice('图表尚未渲染完成')
      return
    }
    setCopyingImage(true)
    try {
      const target = mermaidCanvasRef.current
      if (!target) throw new Error('图表节点不可用')
      const canvas = await html2canvas(target, {
        scale: 2,
        backgroundColor: '#ffffff',
        useCORS: true,
        logging: false,
        windowWidth: Math.max(1, target.scrollWidth || target.clientWidth || 1),
        windowHeight: Math.max(1, target.scrollHeight || target.clientHeight || 1),
      })
      const dataUrl = canvas.toDataURL('image/png')
      const copied = await copyImageFromSrc(dataUrl)
      if (!copied.ok) throw new Error(copied.error || '复制失败')
      showNotice('图片已写入剪贴板')
    } catch {
      showNotice('导出到剪贴板失败')
    } finally {
      setCopyingImage(false)
    }
  }, [showNotice, svg])

  return (
    <div className="message-mermaid-block">
      <div className="message-mermaid-meta">
        <span className="message-mermaid-label">Mermaid 图表</span>
        {rendering && <span className="message-mermaid-status">渲染中...</span>}
        {!rendering && error && <span className="message-mermaid-status is-error">渲染失败，显示源码</span>}
      </div>
      <div className="message-mermaid-actions">
        <button
          type="button"
          className="message-pre-toolbar-btn"
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            void handleCopyCode()
          }}
        >
          复制图源码
        </button>
        <button
          type="button"
          className="message-pre-toolbar-btn"
          disabled={rendering || exporting || !!error || !svg}
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            void handleExportPng()
          }}
        >
          {exporting ? '保存中...' : '保存到本地'}
        </button>
        <button
          type="button"
          className="message-pre-toolbar-btn"
          disabled={rendering || exporting || copyingImage || !!error || !svg}
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            void handleCopyImageToClipboard()
          }}
        >
          {copyingImage ? '导出中...' : '导出为图片'}
        </button>
        {notice && <span className="message-mermaid-status">{notice}</span>}
      </div>
      {!error && svg && (
        <div className="message-mermaid-canvas-wrap">
          <div ref={mermaidCanvasRef} className="message-mermaid-canvas" dangerouslySetInnerHTML={{ __html: svg }} />
        </div>
      )}
      {(error || !svg) && (
        <EditablePreBlock {...preProps} onSaveToQuickNote={onSaveToQuickNote}>
          {children}
        </EditablePreBlock>
      )}
    </div>
  )
}

/** Python 代码块：带执行按钮，点击执行并显示结果；可配置自动执行 */
function PythonCodeBlockWrapper({
  children,
  messageId,
  isLastAssistantItem,
  autoExecute,
  executedIdsRef,
  ...preProps
}: PythonCodeBlockWrapperProps) {
  const [status, setStatus] = useState<'idle' | 'running' | 'done'>('idle')
  const [result, setResult] = useState<{ success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string } | null>(null)
  const codeText = getCodeTextFromPreChildren(children)
  const handleExecute = useCallback(async () => {
    const api =
      typeof window !== 'undefined'
        ? (window as unknown as {
            codeExec?: {
              run?: (code: string) => Promise<{ success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string }>
              installPackage?: (pkg: string) => Promise<{ success: boolean; stderr?: string; error?: string }>
            }
          }).codeExec
        : undefined
    if (!api?.run) {
      setResult({ success: false, error: '暂不支持（需在 Electron 环境中运行）' })
      setStatus('done')
      return
    }
    setStatus('running')
    setResult(null)
    try {
      let res = await api.run(codeText)
      if (!res.success && res.missingPackage && api.installPackage) {
        const ok = window.confirm(`代码执行需要安装 ${res.missingPackage} 包，是否允许安装？（需要网络）`)
        if (ok) {
          try {
            showInstallOverlay(res.missingPackage)
            const installRes = await api.installPackage(res.missingPackage)
            if (installRes.success) {
              res = await api.run(codeText)
            } else {
              res = { success: false, error: `安装失败: ${installRes.error || installRes.stderr || '未知错误'}` }
            }
          } finally {
            hideInstallOverlay()
          }
        }
      }
      setResult(res)
    } catch (e) {
      setResult({ success: false, error: String(e) })
    } finally {
      setStatus('done')
    }
  }, [codeText])
  const hasAutoRunRef = useRef(false)
  useLayoutEffect(() => {
    if (
      !autoExecute ||
      !isLastAssistantItem ||
      !messageId ||
      !executedIdsRef ||
      executedIdsRef.current.has(messageId) ||
      !codeText.trim() ||
      hasAutoRunRef.current
    ) {
      return
    }
    hasAutoRunRef.current = true
    executedIdsRef.current.add(messageId)
    handleExecute()
  }, [autoExecute, isLastAssistantItem, messageId, executedIdsRef, codeText, handleExecute])
  return (
    <div className="message-code-block">
      <pre {...preProps}>{children}</pre>
      <div className="message-code-actions">
        <button
          type="button"
          className="message-code-execute-btn"
          disabled={status === 'running'}
          onClick={handleExecute}
        >
          {status === 'running' ? '执行中…' : '执行'}
        </button>
        {result && (
          <div className="message-code-output">
            {result.error && <div className="message-code-output-error">{result.error}</div>}
            {result.stderr && <div className="message-code-output-stderr">{result.stderr}</div>}
            {result.stdout && <pre className="message-code-output-stdout">{result.stdout}</pre>}
          </div>
        )}
      </div>
    </div>
  )
}

/** 创建 Markdown 组件：为 ```python 块添加执行按钮，支持自动执行 */
function createMarkdownComponents(
  messageId: string,
  isLastAssistantItem: boolean,
  autoExecute: boolean,
  executedIdsRef: React.MutableRefObject<Set<string>>,
  onRequestTableMenu?: (payload: TableMenuPayload) => void,
  onRevealLocalFileToken?: (fileToken: string) => void,
  onOpenScheduledJob?: (jobId: string) => void,
  onOpenExternalUrl?: (url: string) => void,
  onOpenFriendProfile?: (nickname: string) => void,
  onSaveTextBlockToQuickNote?: (text: string) => void
) {
  return {
    pre(props: React.ComponentPropsWithoutRef<'pre'> & { node?: unknown }) {
      const child = Children.toArray(props.children)?.[0]
      const className = (child && isValidElement(child) && (child.props as { className?: string })?.className) || ''
      const isMermaid = String(className).includes('language-mermaid')
      const isPython = String(className).includes('language-python')
      if (isMermaid) {
        return (
          <MermaidPreBlock {...props} messageId={messageId} onSaveToQuickNote={onSaveTextBlockToQuickNote}>
            {props.children}
          </MermaidPreBlock>
        )
      }
      if (isPython) {
        return (
          <PythonCodeBlockWrapper
            {...props}
            messageId={messageId}
            isLastAssistantItem={isLastAssistantItem}
            autoExecute={autoExecute}
            executedIdsRef={executedIdsRef}
          />
        )
      }
      return (
        <EditablePreBlock {...props} onSaveToQuickNote={onSaveTextBlockToQuickNote}>
          {props.children}
        </EditablePreBlock>
      )
    },
    table(props: React.ComponentPropsWithoutRef<'table'> & { node?: unknown }) {
      return <MarkdownTable {...props} onRequestMenu={onRequestTableMenu} />
    },
    code(props: React.ComponentPropsWithoutRef<'code'> & { node?: unknown }) {
      const className = String(props.className || '')
      const text = String(Children.toArray(props.children).join('') || '')
      const isInlineCandidate = !className.includes('language-') && !text.includes('\n')
      if (isInlineCandidate) {
        const maybeUrl = normalizeWebUrl(text)
        if (maybeUrl && onOpenExternalUrl) {
          return (
            <a
              href={maybeUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => {
                e.preventDefault()
                e.stopPropagation()
                onOpenExternalUrl(maybeUrl)
              }}
            >
              {text}
            </a>
          )
        }
      }
      if (isInlineCandidate && onRevealLocalFileToken && looksLikeLocalFileToken(text)) {
        return (
          <button
            type="button"
            className="message-inline-file-link"
            title={`定位文件：${text}`}
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              onRevealLocalFileToken(text)
            }}
          >
            {text}
          </button>
        )
      }
      return <code {...props}>{props.children}</code>
    },
    a(props: React.ComponentPropsWithoutRef<'a'> & { node?: unknown }) {
      const href = String(props.href || '')
      const prefix = 'topoclaw-scheduled-job://'
      if (href.startsWith('topoclaw-scheduled-job:')) {
        const jobId = decodeURIComponent(href.startsWith(prefix) ? href.slice(prefix.length) : href.replace(/^topoclaw-scheduled-job:\/*/, ''))
        return (
          <button
            type="button"
            className="message-inline-file-link"
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              onOpenScheduledJob?.(jobId)
            }}
          >
            {props.children}
          </button>
        )
      }
      if (href.startsWith(LOCAL_FILE_LINK_PREFIX) && onRevealLocalFileToken) {
        const token = decodeURIComponent(href.slice(LOCAL_FILE_LINK_PREFIX.length))
        return (
          <button
            type="button"
            className="message-inline-file-link"
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              onRevealLocalFileToken(token)
            }}
          >
            {props.children}
          </button>
        )
      }
      if (href.startsWith(FRIEND_NICK_LINK_PREFIX) && onOpenFriendProfile) {
        const nickname = decodeURIComponent(href.slice(FRIEND_NICK_LINK_PREFIX.length))
        return (
          <button
            type="button"
            className="message-inline-friend-link"
            title={`查看好友名片：${nickname}`}
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              onOpenFriendProfile(nickname)
            }}
          >
            {props.children}
          </button>
        )
      }
      const maybeUrl = normalizeWebUrl(href)
      if (maybeUrl && onOpenExternalUrl) {
        return (
          <a
            {...props}
            href={maybeUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => {
              e.preventDefault()
              e.stopPropagation()
              onOpenExternalUrl(maybeUrl)
            }}
          >
            {props.children}
          </a>
        )
      }
      return <a {...props}>{props.children}</a>
    },
    img(props: React.ComponentPropsWithoutRef<'img'> & { node?: unknown }) {
      return <MarkdownWorkspaceImage {...props} />
    },
  }
}

function injectScheduledJobLinks(content: string): string {
  if (!content) return content
  return content.replace(
    /(任务ID|任务Id|Task ID|task id)\s*[:：]\s*`?([A-Za-z0-9_-]{4,})`?/g,
    (_m, label: string, taskId: string) =>
      `${label}: [\`${taskId}\`](topoclaw-scheduled-job://${encodeURIComponent(taskId)})`
  )
}

const LOCAL_FILE_LINK_PREFIX = 'topoclaw-local-file://'
const FRIEND_NICK_LINK_PREFIX = 'topoclaw-friend://'
const LOCAL_FILE_TOKEN_IN_TEXT_RE = /(^|[\s(（\[【<"'“‘])([^\s`"'<>()[\]{}，。；：、！？,!?]+\.[a-zA-Z0-9]{1,16})([，。；：、！？,!?]?)/g
const COMMON_DOMAIN_EXTENSIONS = new Set(['com', 'cn', 'net', 'org', 'io', 'ai', 'dev', 'app', 'in', 'top', 'co', 'cc'])

function isLikelyDomainLikeToken(token: string): boolean {
  const normalized = String(token || '').trim().toLowerCase()
  if (!normalized || /[\\/]/.test(normalized)) return false
  const idx = normalized.lastIndexOf('.')
  if (idx <= 0 || idx >= normalized.length - 1) return false
  const ext = normalized.slice(idx + 1)
  if (!COMMON_DOMAIN_EXTENSIONS.has(ext)) return false
  return /^[a-z0-9.-]+$/.test(normalized)
}

function injectLocalFileLinks(content: string): string {
  if (!content) return content
  const lines = content.split('\n')
  let inFence = false
  const out = lines.map((line) => {
    const trimmed = line.trim()
    if (trimmed.startsWith('```')) {
      inFence = !inFence
      return line
    }
    if (inFence || !line) return line
    const segments = line.split(/(`[^`]*`)/g)
    return segments.map((seg) => {
      if (!seg || (seg.startsWith('`') && seg.endsWith('`'))) return seg
      return seg.replace(LOCAL_FILE_TOKEN_IN_TEXT_RE, (matched, lead: string, token: string, suffix: string) => {
        const normalized = String(token || '').trim().replace(/^["'`]+|["'`]+$/g, '')
        if (!normalized || isLikelyDomainLikeToken(normalized) || !looksLikeLocalFileToken(normalized)) return matched
        return `${lead}[${normalized}](${LOCAL_FILE_LINK_PREFIX}${encodeURIComponent(normalized)})${suffix || ''}`
      })
    }).join('')
  })
  return out.join('\n')
}

function injectFriendNicknameLinks(content: string, friendNicknames: string[]): string {
  if (!content) return content
  const normalized = Array.from(
    new Set(
      (friendNicknames || [])
        .map((name) => String(name || '').trim())
        .filter(Boolean)
    )
  ).sort((a, b) => b.length - a.length)
  if (normalized.length === 0) return content
  const namePattern = new RegExp(`(${normalized.map(escapeRegExp).join('|')})`, 'g')
  const markdownLinkPartRe = /(\[[^\]]*]\([^)]+\))/g
  const markdownLinkExactRe = /^\[[^\]]*]\([^)]+\)$/
  const lines = content.split('\n')
  let inFence = false
  const out = lines.map((line) => {
    const trimmed = line.trim()
    if (trimmed.startsWith('```')) {
      inFence = !inFence
      return line
    }
    if (inFence || !line) return line
    const codeSegments = line.split(/(`[^`]*`)/g)
    return codeSegments.map((segment) => {
      if (!segment || (segment.startsWith('`') && segment.endsWith('`'))) return segment
      const markdownSegments = segment.split(markdownLinkPartRe)
      return markdownSegments.map((part) => {
        if (!part) return part
        if (markdownLinkExactRe.test(part)) return part
        return part.replace(namePattern, (matchedName: string) =>
          `[${matchedName}](${FRIEND_NICK_LINK_PREFIX}${encodeURIComponent(matchedName)})`
        )
      }).join('')
    }).join('')
  })
  return out.join('\n')
}

/** react-markdown 默认只允许 http(s)/mailto 等，会清空自定义协议导致 href="" 并触发整页跳转 */
function chatMarkdownUrlTransform(value: string): string {
  const v = normalizeLocalPathToken(String(value || ''))
  if (v.startsWith('topoclaw-scheduled-job:')) return v
  if (v.startsWith(LOCAL_FILE_LINK_PREFIX)) return v
  if (v.startsWith(FRIEND_NICK_LINK_PREFIX)) return v
  // Keep local workspace tokens / absolute local paths intact for custom img/file renderers.
  if (looksLikeLocalFileToken(v)) return v
  return defaultUrlTransform(v)
}

/** 包裹 ReactMarkdown，稳定 components 引用，避免流式渲染时 remount 导致自动执行被取消 */
function MessageMarkdown({
  messageId,
  content,
  friendNicknames,
  isLastItem,
  autoExecuteCode,
  executedIdsRef,
  onRequestTableMenu,
  onRevealLocalFileToken,
  onOpenScheduledJob,
  onOpenExternalUrl,
  onOpenFriendProfile,
  onSaveTextBlockToQuickNote,
}: {
  messageId: string
  content: string
  friendNicknames: string[]
  isLastItem: boolean
  autoExecuteCode: boolean
  executedIdsRef: React.MutableRefObject<Set<string>>
  onRequestTableMenu?: (payload: TableMenuPayload) => void
  onRevealLocalFileToken?: (fileToken: string) => void
  onOpenScheduledJob?: (jobId: string) => void
  onOpenExternalUrl?: (url: string) => void
  onOpenFriendProfile?: (nickname: string) => void
  onSaveTextBlockToQuickNote?: (text: string) => void
}) {
  const contentWithInjectedLinks = useMemo(
    () => injectLocalFileLinks(injectScheduledJobLinks(injectFriendNicknameLinks(content, friendNicknames))),
    [content, friendNicknames]
  )
  const components = useMemo(
    () => createMarkdownComponents(
      messageId,
      isLastItem,
      autoExecuteCode,
      executedIdsRef,
      onRequestTableMenu,
      onRevealLocalFileToken,
      onOpenScheduledJob,
      onOpenExternalUrl,
      onOpenFriendProfile,
      onSaveTextBlockToQuickNote
    ),
    [messageId, isLastItem, autoExecuteCode, onRequestTableMenu, onRevealLocalFileToken, onOpenScheduledJob, onOpenExternalUrl, onOpenFriendProfile, onSaveTextBlockToQuickNote] // executedIdsRef 是稳定引用，无需放入 deps
  )
  return (
    <ReactMarkdown components={components} remarkPlugins={[remarkGfm]} urlTransform={chatMarkdownUrlTransform}>
      {contentWithInjectedLinks}
    </ReactMarkdown>
  )
}

interface Message {
  id: string
  sender: string
  /** 群聊等场景的发送者唯一标识；用于避免同名用户串头像/主页 */
  senderImei?: string
  content: string
  type: 'user' | 'assistant' | 'system'
  timestamp: number
  messageType?: 'text' | 'file'
  fileBase64?: string
  fileName?: string
  fileList?: Array<{ fileBase64: string; fileName?: string }>
  /** 聊天小助手工具调用记录，用于「正在思考」下拉栏，如 ["调用query_skill_community工具"] */
  thinkingContents?: string[]
  /** 生成的技能（等待用户选择是否加入） */
  generatedSkill?: Skill
  /** 用户已处理：加入或取消 */
  generatedSkillResolved?: 'added' | 'cancelled'
  /** 语义来源：普通用户/好友、我的数字分身、好友数字分身、助手 */
  messageSource?: 'user' | 'friend' | 'my_clone' | 'friend_clone' | 'assistant'
  /** 数字分身归属人 IMEI（若为分身消息） */
  cloneOwnerImei?: string
}

type DraftImage = { base64: string; name: string; mime: string }

type MessageQuoteContext = {
  messageId: string
  sender: string
  timestamp: number
  content: string
}

function formatQuoteTimestamp(timestamp: number): string {
  const ts = Number.isFinite(timestamp) && timestamp > 0 ? timestamp : Date.now()
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

function normalizeQuotedContent(text: string): string {
  const compact = text.replace(/\s+/g, ' ').trim()
  if (!compact) return '[空内容]'
  if (compact.length <= 320) return compact
  return `${compact.slice(0, 320)}...`
}

function buildQuotePromptSuffix(quoted: MessageQuoteContext | null): string {
  if (!quoted) return ''
  const sender = (quoted.sender || '').trim() || '未知发送人'
  const sentAt = formatQuoteTimestamp(quoted.timestamp)
  const content = normalizeQuotedContent(quoted.content || '')
  return `用户引入了信息：${content}（发送人：${sender}，发送时间：${sentAt}）`
}

function toMessageExportText(m: Message): string {
  const base = m.type === 'assistant' ? stripNeedExecutionGuide(m.content || '') : (m.content || '')
  if (m.messageType === 'file') {
    const fileLabel = `[文件] ${m.fileName || '未命名文件'}`
    return base?.trim() && base !== '[图片]' ? `${base}\n${fileLabel}` : fileLabel
  }
  return base
}

function extractMessageImageAttachment(m: Message): { base64: string; name: string; mime: string } | null {
  const toMime = (name: string): string => {
    const low = name.toLowerCase()
    if (low.endsWith('.jpg') || low.endsWith('.jpeg')) return 'image/jpeg'
    if (low.endsWith('.gif')) return 'image/gif'
    if (low.endsWith('.webp')) return 'image/webp'
    if (low.endsWith('.bmp')) return 'image/bmp'
    if (low.endsWith('.svg')) return 'image/svg+xml'
    return 'image/png'
  }
  const looksImage = (name?: string) => !!name && (/\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(name) || name === '图片.png')
  if (Array.isArray(m.fileList)) {
    const target = m.fileList.find((f) => f?.fileBase64 && looksImage(f.fileName))
    if (target) {
      const fileName = target.fileName || '图片.png'
      return { base64: target.fileBase64, name: fileName, mime: toMime(fileName) }
    }
  }
  if (m.fileBase64 && looksImage(m.fileName)) {
    const fileName = m.fileName || '图片.png'
    return { base64: m.fileBase64, name: fileName, mime: toMime(fileName) }
  }
  return null
}

function buildQuickNoteChatSourceLabel(conv: Conversation | null, isGroupConv: boolean): string {
  if (!conv) return '未知会话'
  const name = (conv.name || '').trim() || '未命名'
  if (isGroupConv || conv.type === 'group') {
    return `在「${name}」群组的群聊`
  }
  if (conv.type === 'assistant') {
    return `与助手「${name}」的私聊`
  }
  return `与「${name}」的私聊`
}

function normalizeGroupRawId(value: string | undefined | null): string {
  let raw = String(value || '').trim()
  while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
  return raw
}

function toCanonicalGroupConversationId(value: string | undefined | null): string {
  const raw = normalizeGroupRawId(value)
  return raw ? `group_${raw}` : ''
}

function toServerGroupId(value: string | undefined | null): string {
  const raw = normalizeGroupRawId(value)
  return raw ? `group_${raw}` : ''
}

/** 连续相同的小助手消息去重：sender 相同且内容规范化后一致则只保留第一条 */
function dedupeConsecutiveAssistantMessages(list: Message[]): Message[] {
  const result: Message[] = []
  for (const m of list) {
    if (m.type !== 'assistant') {
      result.push(m)
      continue
    }
    const last = result[result.length - 1]
    if (
      last?.type === 'assistant' &&
      last.sender === m.sender &&
      normalizeContentForDedupe(last.content || '') === normalizeContentForDedupe(m.content || '')
    )
      continue
    result.push(m)
  }
  return result
}

interface ChatDetailProps {
  conversation: Conversation | null
  conversationListCollapsed?: boolean
  onToggleConversationList?: () => void
  onUpdateLastMessage?: (
    conversationId: string,
    message: string,
    timestamp?: number,
    options?: { isFromMe?: boolean; isViewing?: boolean }
  ) => void
  onConversationViewed?: (conversationId: string) => void
  onSelectConversation?: (conversation: Conversation) => void
  /** 手机发起 PC 执行任务后，自动跳转到该 session */
  sessionIdToNavigate?: string | null
  onSessionIdNavigated?: () => void
  onAssistantRunningChange?: (payload: {
    conversationId: string
    conversationName: string
    baseUrl: string
    query: string
    running: boolean
  }) => void
}

type MobileStatusCheckStep = 'idle' | 'checking' | 'success' | 'failed'
const MOBILE_STATUS_POLL_INTERVAL_MS = 60000

type MobileProbeWaiter = {
  resolve: (result: { ok: boolean; error?: string }) => void
  timer: ReturnType<typeof setTimeout>
}

type ChatProfileTarget = React.ComponentProps<typeof ContactProfilePanel>['target']
type IdeTerminalBridge = {
  openWindow?: () => Promise<{ ok: boolean; error?: string }>
  create?: (payload?: { cwd?: string }) => Promise<{ ok: boolean; error?: string }>
  write?: (data: string) => void
  resize?: (cols: number, rows: number) => void
  onData?: (callback: (data: string) => void) => (() => void) | void
}
type IdeWorkspaceBridge = {
  openPath?: (filePath: string) => Promise<{ success: boolean; error?: string }>
  openExternal?: (url: string) => Promise<{ success: boolean; error?: string }>
  showItemInFolder?: (filePath: string) => Promise<{ success: boolean; error?: string }>
  copyFileToClipboard?: (filePath: string) => Promise<{ success: boolean; error?: string }>
  resolveGeneratedFile?: (fileToken: string) => Promise<{ success: boolean; path?: string; error?: string }>
  revealGeneratedFile?: (fileToken: string) => Promise<{ success: boolean; path?: string; error?: string }>
  saveChatFileToWorkspace?: (dataUrl: string, originalFileName?: string, batchDir?: string) => Promise<{
    ok: boolean
    path?: string
    error?: string
  }>
  listWorkspaceFiles?: (opts?: {
    maxFiles?: number
    maxBytes?: number
  }) => Promise<{
    ok: boolean
    workspaceDir?: string
    files?: Array<{ relativePath: string; content: string }>
    error?: string
  }>
  pickFolderFiles?: (opts?: {
    maxFiles?: number
    maxBytes?: number
  }) => Promise<{
    ok: boolean
    canceled?: boolean
    folderPath?: string
    files?: Array<{ relativePath: string; content: string }>
    error?: string
  }>
  listFolderFiles?: (opts?: {
    folderPath?: string
    maxFiles?: number
    maxBytes?: number
  }) => Promise<{
    ok: boolean
    folderPath?: string
    files?: Array<{ relativePath: string; content: string }>
    error?: string
  }>
  saveTextAs?: (text: string, defaultFileName?: string) => Promise<{
    ok: boolean
    canceled?: boolean
    error?: string
    path?: string
  }>
  writeTextFile?: (filePath: string, text: string) => Promise<{
    ok: boolean
    path?: string
    error?: string
  }>
  readTextFile?: (filePath: string) => Promise<{
    ok: boolean
    path?: string
    content?: string
    error?: string
  }>
  readBinaryFile?: (filePath: string) => Promise<{
    ok: boolean
    path?: string
    base64?: string
    error?: string
  }>
}
type IdeTreeNode = {
  name: string
  path: string
  type: 'folder' | 'file'
  children?: IdeTreeNode[]
}

type FileLinkActionTarget =
  | { kind: 'chat_file'; fileBase64: string; fileName: string }
  | { kind: 'local_file'; filePath: string; fileName: string }

function normalizeIdePath(input: string): string {
  return String(input || '').replace(/\\/g, '/').trim()
}

function ideFileExt(inputPath: string): string {
  const name = normalizeIdePath(inputPath).split('/').pop() || ''
  const idx = name.lastIndexOf('.')
  if (idx <= 0 || idx >= name.length - 1) return ''
  return name.slice(idx + 1).toLowerCase()
}

function ideFileTypeBadge(inputPath: string): { kind: string; label: string } {
  const normalized = normalizeIdePath(inputPath)
  const fileName = (normalized.split('/').pop() || '').toLowerCase()
  const ext = ideFileExt(normalized)
  if (fileName === '.env' || fileName.startsWith('.env.')) return { kind: 'env', label: 'ENV' }
  if (['ts', 'tsx'].includes(ext)) return { kind: 'ts', label: 'TS' }
  if (['js', 'jsx', 'mjs', 'cjs'].includes(ext)) return { kind: 'js', label: 'JS' }
  if (ext === 'json') return { kind: 'json', label: 'JSON' }
  if (ext === 'md') return { kind: 'md', label: 'MD' }
  if (['html', 'htm'].includes(ext)) return { kind: 'html', label: 'HTML' }
  if (['css', 'scss', 'less'].includes(ext)) return { kind: 'style', label: 'CSS' }
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico'].includes(ext)) return { kind: 'image', label: 'IMG' }
  if (['yml', 'yaml', 'toml', 'ini', 'cfg', 'conf'].includes(ext)) return { kind: 'config', label: 'CFG' }
  if (ext === 'lock' || fileName.includes('lock')) return { kind: 'lock', label: 'LOCK' }
  if (['txt', 'log'].includes(ext)) return { kind: 'text', label: 'TXT' }
  if (ext) {
    const shortExt = ext.slice(0, 8)
    return { kind: 'ext', label: `.${shortExt}` }
  }
  return { kind: 'file', label: 'FILE' }
}

function isIdeReadonlyPreviewExt(ext: string): boolean {
  return ['ppt', 'pptx', 'doc', 'docx', 'xls', 'xlsx', 'csv'].includes(String(ext || '').toLowerCase())
}

function buildIdeRunCommand(ext: string, absPath: string): string {
  const normalizedExt = String(ext || '').toLowerCase()
  const escaped = absPath.replace(/"/g, '\\"')
  if (normalizedExt === 'py') return `python "${escaped}"`
  if (['js', 'mjs', 'cjs'].includes(normalizedExt)) return `node "${escaped}"`
  if (normalizedExt === 'ts') return `npx ts-node "${escaped}"`
  if (normalizedExt === 'ps1') return `powershell -NoProfile -ExecutionPolicy Bypass -File "${escaped}"`
  if (normalizedExt === 'bat' || normalizedExt === 'cmd') return `cmd /c ""${escaped}""`
  if (normalizedExt === 'sh') return `bash "${escaped}"`
  return ''
}

function ideTerminalOutputLooksIdle(chunk: string): boolean {
  const tail = String(chunk || '').replace(/\r/g, '').slice(-240)
  if (!tail.trim()) return false
  if (/PS [^\n>]*>\s*$/.test(tail)) return true
  if (/[A-Za-z]:\\[^>\n]*>\s*$/.test(tail)) return true
  if (/[$#]\s*$/.test(tail)) return true
  return false
}

function shouldIgnoreIdeChangeStats(path: string): boolean {
  const normalized = normalizeIdePath(path).toLowerCase()
  if (!normalized) return false
  return (
    normalized === 'workspace/memory' ||
    normalized.startsWith('workspace/memory/') ||
    normalized === 'workspace/sessions' ||
    normalized.startsWith('workspace/sessions/')
  )
}

function normalizeThinkingDetailLines(contents: string[]): string[] {
  return (contents || []).map((line) => String(line || '').trim()).filter(Boolean)
}

function isTaskEventMessageText(content: string): boolean {
  return String(content || '').includes('[Task Event]')
}

function decodeXmlEntities(input: string): string {
  return String(input || '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_m, code) => String.fromCharCode(Number(code)))
}

async function extractPptxSlideTextsFromBase64(base64: string): Promise<Array<{ title: string; text: string }>> {
  const raw = String(base64 || '').trim()
  if (!raw) return []
  const zip = await JSZip.loadAsync(raw, { base64: true })
  const slideEntries = Object.keys(zip.files)
    .map((name) => {
      const m = name.match(/^ppt\/slides\/slide(\d+)\.xml$/i)
      return m ? { name, index: Number(m[1]) } : null
    })
    .filter((v): v is { name: string; index: number } => !!v)
    .sort((a, b) => a.index - b.index)
  const slides: Array<{ title: string; text: string }> = []
  for (const entry of slideEntries) {
    const xml = await zip.file(entry.name)?.async('string')
    if (!xml) continue
    const texts = Array.from(xml.matchAll(/<a:t>([\s\S]*?)<\/a:t>/g))
      .map((m) => decodeXmlEntities(m[1] || '').trim())
      .filter(Boolean)
    slides.push({
      title: `第 ${entry.index} 页`,
      text: texts.join('\n'),
    })
  }
  return slides
}

function ideAbsPathToFileUrl(absPath: string): string {
  const raw = String(absPath || '').trim()
  if (!raw) return ''
  const normalized = raw.replace(/\\/g, '/')
  if (/^[a-zA-Z]:\//.test(normalized)) return `file:///${encodeURI(normalized)}`
  if (normalized.startsWith('/')) return `file://${encodeURI(normalized)}`
  return ''
}

function ideDirnameFromAnyPath(absPath: string): string {
  const v = String(absPath || '').trim()
  if (!v) return ''
  const idx = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'))
  if (idx <= 0) return ''
  return v.slice(0, idx)
}

function buildIdeTree(paths: string[], folders: string[] = []): IdeTreeNode[] {
  const root: IdeTreeNode = { name: '__root__', path: '', type: 'folder', children: [] }
  for (const rawFolder of folders) {
    const folderPath = normalizeIdePath(rawFolder).replace(/\/+$/g, '')
    if (!folderPath) continue
    const parts = folderPath.split('/').filter(Boolean)
    let cursor = root
    parts.forEach((part, index) => {
      if (!cursor.children) cursor.children = []
      const nodePath = parts.slice(0, index + 1).join('/')
      let child = cursor.children.find((n) => n.name === part && n.type === 'folder')
      if (!child) {
        child = { name: part, path: nodePath, type: 'folder', children: [] }
        cursor.children.push(child)
      }
      cursor = child
    })
  }
  for (const raw of paths) {
    const p = normalizeIdePath(raw)
    if (!p) continue
    const parts = p.split('/').filter(Boolean)
    let cursor = root
    parts.forEach((part, index) => {
      const isLeaf = index === parts.length - 1
      if (!cursor.children) cursor.children = []
      const nodePath = parts.slice(0, index + 1).join('/')
      let child = cursor.children.find((n) => n.name === part && n.type === (isLeaf ? 'file' : 'folder'))
      if (!child) {
        child = isLeaf
          ? { name: part, path: nodePath, type: 'file' }
          : { name: part, path: nodePath, type: 'folder', children: [] }
        cursor.children.push(child)
      }
      if (child.type === 'folder') cursor = child
    })
  }
  const sortTree = (nodes: IdeTreeNode[]): IdeTreeNode[] =>
    nodes
      .map((node) => (node.type === 'folder' ? { ...node, children: sortTree(node.children || []) } : node))
      .sort((a, b) => {
        if (a.type !== b.type) return a.type === 'folder' ? -1 : 1
        return a.name.localeCompare(b.name, 'zh-CN')
      })
  return sortTree(root.children || [])
}

function collectIdeFolderPaths(nodes: IdeTreeNode[]): string[] {
  const out: string[] = []
  const walk = (list: IdeTreeNode[]) => {
    list.forEach((node) => {
      if (node.type !== 'folder') return
      out.push(node.path)
      if (node.children?.length) walk(node.children)
    })
  }
  walk(nodes)
  return out
}

function collectIdeAncestorFoldersForFile(filePath: string): string[] {
  const normalized = normalizeIdePath(filePath)
  if (!normalized) return []
  const parts = normalized.split('/').filter(Boolean)
  if (parts.length <= 1) return []
  const out: string[] = []
  for (let i = 1; i < parts.length; i++) {
    out.push(parts.slice(0, i).join('/'))
  }
  return out
}

function escapeRegExp(input: string): string {
  return input.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function hasExplicitMentionToken(source: string, mentionToken: string): boolean {
  const src = String(source || '')
  const token = String(mentionToken || '').trim()
  if (!src || !token) return false
  // Require a clear boundary after mention token to avoid substring hits like "@TopoClaw(张三)" matching "@TopoClaw".
  const re = new RegExp(`${escapeRegExp(token)}(?=$|[\\s,，:：;；、。!！?？\\n\\t])`, 'i')
  return re.test(src)
}

function basenameFromAnyPath(input: string): string {
  const raw = String(input || '').trim()
  if (!raw) return ''
  const normalized = raw.replace(/\\/g, '/')
  const parts = normalized.split('/').filter(Boolean)
  return parts[parts.length - 1] || raw
}

function commonTopLevelFolder(paths: string[]): string {
  const topLevels = paths
    .map((p) => normalizeIdePath(p).split('/').filter(Boolean)[0] || '')
    .filter(Boolean)
  if (topLevels.length === 0) return ''
  const first = topLevels[0]
  if (topLevels.every((v) => v.toLowerCase() === first.toLowerCase())) return first
  return ''
}

function buildQueryTokens(input: string): string[] {
  const text = String(input || '').trim().toLowerCase()
  if (!text) return []
  const out = new Set<string>()
  out.add(text)
  const asciiWords = text.match(/[a-z0-9_./-]{2,}/g) || []
  asciiWords.forEach((w) => out.add(w))
  const zhWords = text.match(/[\u4e00-\u9fa5]{2,}/g) || []
  zhWords.forEach((w) => out.add(w))
  return Array.from(out).slice(0, 24)
}

type IdeDiffLine = { kind: 'context' | 'add' | 'remove'; text: string }
type IdeDiffHunk = { lines: IdeDiffLine[] }

function buildLineDiff(oldText: string, newText: string): { added: number; removed: number; hunks: IdeDiffHunk[] } {
  const oldLines = String(oldText || '').split(/\r?\n/)
  const newLines = String(newText || '').split(/\r?\n/)
  const n = oldLines.length
  const m = newLines.length

  // Protect UI from huge O(n*m) diff costs.
  if (n > 800 || m > 800 || n * m > 420000) {
    const lines: IdeDiffLine[] = []
    for (const line of oldLines.slice(0, 180)) lines.push({ kind: 'remove', text: line })
    for (const line of newLines.slice(0, 180)) lines.push({ kind: 'add', text: line })
    return {
      added: newLines.length,
      removed: oldLines.length,
      hunks: [{ lines }],
    }
  }

  const dp: number[][] = Array.from({ length: n + 1 }, () => Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      if (oldLines[i] === newLines[j]) dp[i][j] = dp[i + 1][j + 1] + 1
      else dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const ops: IdeDiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (oldLines[i] === newLines[j]) {
      ops.push({ kind: 'context', text: oldLines[i] })
      i += 1
      j += 1
      continue
    }
    if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push({ kind: 'remove', text: oldLines[i] })
      i += 1
    } else {
      ops.push({ kind: 'add', text: newLines[j] })
      j += 1
    }
  }
  while (i < n) ops.push({ kind: 'remove', text: oldLines[i++] })
  while (j < m) ops.push({ kind: 'add', text: newLines[j++] })

  const added = ops.reduce((acc, line) => acc + (line.kind === 'add' ? 1 : 0), 0)
  const removed = ops.reduce((acc, line) => acc + (line.kind === 'remove' ? 1 : 0), 0)
  if (added === 0 && removed === 0) return { added, removed, hunks: [] }

  const changedIdx: number[] = []
  for (let k = 0; k < ops.length; k++) {
    if (ops[k].kind !== 'context') changedIdx.push(k)
  }
  const hunks: IdeDiffHunk[] = []
  const context = 3
  let p = 0
  while (p < changedIdx.length) {
    let start = Math.max(0, changedIdx[p] - context)
    let end = Math.min(ops.length - 1, changedIdx[p] + context)
    while (p + 1 < changedIdx.length && changedIdx[p + 1] <= end + context) {
      p += 1
      end = Math.min(ops.length - 1, changedIdx[p] + context)
    }
    hunks.push({ lines: ops.slice(start, end + 1) })
    p += 1
  }

  return { added, removed, hunks }
}

function countKeywordHits(sourceLower: string, token: string, maxHits = 4): number {
  if (!token) return 0
  let from = 0
  let hits = 0
  while (from < sourceLower.length) {
    const idx = sourceLower.indexOf(token, from)
    if (idx < 0) break
    hits += 1
    if (hits >= maxHits) break
    from = idx + Math.max(token.length, 1)
  }
  return hits
}

function pickSnippetByTokens(content: string, tokens: string[]): string {
  const lines = content.split(/\r?\n/)
  if (lines.length === 0) return ''
  const lowered = lines.map((line) => line.toLowerCase())
  let hitLine = -1
  for (let i = 0; i < lowered.length; i++) {
    if (tokens.some((t) => t && lowered[i].includes(t))) {
      hitLine = i
      break
    }
  }
  const center = hitLine >= 0 ? hitLine : 0
  const start = Math.max(0, center - 3)
  const end = Math.min(lines.length, start + 9)
  return lines.slice(start, end).join('\n').trim()
}

const EMOJI_LIST = ['😀', '😊', '👍', '❤️', '😂', '🎉', '🙏', '😍', '🔥', '✨', '😅', '🥳', '💪', '👏', '😎', '🤔', '😢', '🥺', '🙌', '💯', '🌟', '💀', '🤣', '😭', '😘', '🤗', '😜', '🤪', '😴', '🤩']
const MAX_FOCUS_SKILLS = 8
const MAX_CHAT_ATTACH_IMAGES = 3

function formatApiError(err: unknown): string {
  const res = (err as { response?: { status?: number; data?: { message?: string } } })?.response
  const status = res?.status
  const detail = res?.data?.message
  const errName = (err as { name?: string })?.name || ''
  const errCode = (err as { code?: string })?.code || ''
  if (status === 503) return detail ?? '服务暂不可用(503)，手机端可能离线，请确保手机已打开应用并保持连接'
  if (status === 502) return '网关错误(502)，请稍后重试'
  if (status === 504) return '请求超时(504)，请检查网络'
  if (status === 500) return '服务器内部错误(500)，请稍后重试'
  const msg = err instanceof Error ? err.message : '请求失败'
  const msgLower = String(msg || '').toLowerCase()
  if (
    errName === 'AbortError'
    || errCode === 'ERR_CANCELED'
    || msgLower === 'aborted'
    || msgLower.includes('abort')
    || msgLower.includes('canceled')
    || msgLower.includes('cancelled')
  ) {
    return '执行超时，请稍后再试~'
  }
  return msg.includes('status code') ? `请求失败(${status ?? '未知'})` : `请求失败: ${msg}`
}

function injectGroupAssistantRolePrompt(
  query: string,
  rolePrompt?: string | null
): string {
  const prompt = (rolePrompt || '').trim()
  const userQuery = (query || '').trim()
  if (!prompt) return userQuery
  return [
    '【用户设置角色提示词 ROLE_PROMPT】',
    prompt,
    '',
    '【用户本轮请求 QUERY】',
    userQuery,
    '',
    '请严格区分：ROLE_PROMPT 是长期角色约束；QUERY 是本轮待处理请求。',
  ].join('\n')
}

const VISUAL_EXPLAIN_HINT = '仅在有助于理解流程/结构时，可选提供 Mermaid 图；默认优先其他方式回答。'

function injectVisualExplainHint(query: string): string {
  const base = (query || '').trim()
  if (!base) return base
  if (base.includes(VISUAL_EXPLAIN_HINT)) return base
  return `${base}\n\n${VISUAL_EXPLAIN_HINT}`
}

function isBuiltinTopoclawTarget(assistantId?: string | null, _assistantBaseUrl?: string | null): boolean {
  const id = String(assistantId || '').trim()
  if (!id) return false
  if (id === DEFAULT_TOPOCLAW_ASSISTANT_ID || id.startsWith(`${DEFAULT_TOPOCLAW_ASSISTANT_ID}__`)) return true
  const slot = builtinSlotForAssistantId(id)
  if (slot === 'topoclaw') return true
  // Custom assistants may point to the same backend endpoint as TopoClaw (e.g. :18790),
  // but only the reserved default assistant id should carry digital-clone identity.
  return false
}

function injectTopoclawIdentityLine(
  query: string,
  options: {
    assistantId?: string | null
    assistantBaseUrl?: string | null
    creatorNickname?: string | null
    creatorImei?: string | null
  }
): string {
  const userQuery = String(query || '').trim()
  if (!userQuery) return userQuery
  if (!isBuiltinTopoclawTarget(options.assistantId, options.assistantBaseUrl)) return userQuery
  const creator =
    String(options.creatorNickname || '').trim()
    || String(options.creatorImei || '').trim()
    || '当前用户'
  const identityLine = `你是${creator}的数字分身（TopoClaw），请始终以该身份回答。`
  if (userQuery.includes(identityLine)) return userQuery
  return `${identityLine}\n\n${userQuery}`
}

function injectGroupManagerIdentityLine(
  query: string,
  options?: {
    groupName?: string | null
  }
): string {
  const userQuery = String(query || '').trim()
  if (!userQuery) return userQuery
  const groupName = String(options?.groupName || '').trim()
  const groupLabel = groupName ? `「${groupName}」` : '当前群聊'
  const identityLine = [
    `你是${groupLabel}的群组管理助手（GroupManager）。`,
    '你的职责是组织讨论、协调发言、在需要时@具体助手或成员，不要把自己当作任何人的 TopoClaw 数字分身。',
  ].join('')
  if (userQuery.includes(identityLine)) return userQuery
  return `${identityLine}\n\n${userQuery}`
}

function getGroupAssistantRolePrompt(
  assistantId: string,
  assistantConfigs?: Record<string, { rolePrompt?: string }> | null
): string {
  return (assistantConfigs?.[assistantId]?.rolePrompt || '').trim()
}

function MessageAvatar({ src, fallback }: { src?: string; fallback: string }) {
  const imgSrc = src ? toAvatarSrc(src) : undefined
  if (imgSrc) return <img src={imgSrc} alt="" className="message-avatar-img" />
  return <span className="message-avatar-fallback">{fallback.slice(0, 1)}</span>
}

export function ChatDetail({
  conversation,
  conversationListCollapsed = false,
  onToggleConversationList,
  onUpdateLastMessage,
  onConversationViewed,
  onSelectConversation,
  sessionIdToNavigate,
  onSessionIdNavigated,
  onAssistantRunningChange,
}: ChatDetailProps) {
  const [messagesRaw, setMessagesRaw] = useState<Message[]>([])
  const setMessages = useCallback((updater: React.SetStateAction<Message[]>) => {
    setMessagesRaw((prev) => {
      const next = typeof updater === 'function' ? updater(prev) : updater
      return dedupeConsecutiveAssistantMessages(next)
    })
  }, [])
  const messages = messagesRaw
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  /** APK 发起的任务正在电脑端执行中 */
  const [isMobileTaskRunning, setIsMobileTaskRunning] = useState(false)
  const runningReportRef = useRef<{
    conversationId: string
    conversationName: string
    baseUrl: string
    query: string
    running: boolean
  } | null>(null)
  /** 拉取云侧/助手历史（与发送、流式 loading 分离，避免长时间锁输入） */
  const [historyLoading, setHistoryLoading] = useState(false)
  useEffect(() => {
    if (!onAssistantRunningChange) return
    const latestUserQuery = [...messages]
      .reverse()
      .find((m) => m.type === 'user' && String(m.content || '').trim())?.content || ''
    const nextQuery = String(latestUserQuery || '').replace(/\s+/g, ' ').trim()
    const nextConversationId = String(conversation?.id || '').trim()
    const nextConversationName = String(conversation?.name || '').trim()
    const nextBaseUrl = String(conversation?.baseUrl || '').trim()
    const nextRunning = !!nextConversationId && (loading || isMobileTaskRunning)
    const prev = runningReportRef.current

    if (!nextConversationId) {
      runningReportRef.current = null
      return
    }

    if (
      !prev
      || prev.conversationId !== nextConversationId
      || prev.running !== nextRunning
      || (nextRunning && prev.query !== nextQuery)
    ) {
      onAssistantRunningChange({
        conversationId: nextConversationId,
        conversationName: nextConversationName,
        baseUrl: nextBaseUrl,
        query: nextQuery,
        running: nextRunning,
      })
    }

    runningReportRef.current = {
      conversationId: nextConversationId,
      conversationName: nextConversationName,
      baseUrl: nextBaseUrl,
      query: nextQuery,
      running: nextRunning,
    }
  }, [
    messages,
    conversation?.id,
    conversation?.name,
    conversation?.baseUrl,
    loading,
    isMobileTaskRunning,
    onAssistantRunningChange,
  ])

  useEffect(() => {
    return () => {
      const prev = runningReportRef.current
      if (!prev?.running || !onAssistantRunningChange) return
      onAssistantRunningChange({
        conversationId: prev.conversationId,
        conversationName: prev.conversationName,
        baseUrl: prev.baseUrl,
        query: prev.query,
        running: false,
      })
    }
  }, [onAssistantRunningChange])

  /** 停止任务后输入框上方的短时提示 */
  const [stopTaskNotice, setStopTaskNotice] = useState(false)
  const stopTaskNoticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 长图复制成功后输入框上方的短时提示 */
  const [clipboardSavedNotice, setClipboardSavedNotice] = useState(false)
  const clipboardSavedNoticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 记入随手记成功后的短时提示 */
  const [quickNoteSavedNotice, setQuickNoteSavedNotice] = useState(false)
  const quickNoteSavedNoticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 通用短时提示（toast） */
  const [inlineToastNotice, setInlineToastNotice] = useState('')
  const inlineToastNoticeTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 自定义/聊天小助手：选中的图片（最多 3 张），用于图片问答 */
  const [selectedImages, setSelectedImages] = useState<DraftImage[]>([])
  /** TopoClaw 聊天：选中的任意文件（不含 data: 前缀），落地到 workspace 后按路径处理 */
  const [selectedFileBase64, setSelectedFileBase64] = useState<string | null>(null)
  const [selectedFileName, setSelectedFileName] = useState('file.bin')
  const [selectedFileMime, setSelectedFileMime] = useState('application/octet-stream')
  /** 消息引用：右键气泡后可将上下文附加到下一条 query */
  const [quotedMessageContext, setQuotedMessageContext] = useState<MessageQuoteContext | null>(null)
  /** TopoClaw 批量导入（文件夹/多文件）进度 */
  const [bulkImportProgress, setBulkImportProgress] = useState<{
    active: boolean
    total: number
    processed: number
    success: number
    failed: number
    currentName?: string
    error?: string
  } | null>(null)
  const [bulkWorkspaceBatch, setBulkWorkspaceBatch] = useState<{
    dirPath: string
    total: number
    success: number
    failed: number
  } | null>(null)
  const [bulkFolderForCodeMode, setBulkFolderForCodeMode] = useState<{
    dirPath: string
    folderName: string
  } | null>(null)
  const bulkImportCancelRef = useRef(false)
  const [isAttachmentDragOver, setIsAttachmentDragOver] = useState(false)
  const attachmentDragDepthRef = useRef(0)
  /** 内置 TopoClaw / GroupManager：多套模型下拉（数据来自 config.json topo_desktop） */
  const [builtinProfileLists, setBuiltinProfileLists] = useState<{
    nonGuiProfiles: BuiltinModelProfileRow[]
    guiProfiles: BuiltinModelProfileRow[]
  } | null>(null)
  const [builtinSelNonGui, setBuiltinSelNonGui] = useState('')
  const [builtinSelGui, setBuiltinSelGui] = useState('')
  const [builtinSelGm, setBuiltinSelGm] = useState('')
  const [builtinProfilesLoading, setBuiltinProfilesLoading] = useState(false)
  const [builtinApplyingModel, setBuiltinApplyingModel] = useState(false)
  const [builtinModelSwitchErr, setBuiltinModelSwitchErr] = useState('')
  const [modelAlignRetryModalOpen, setModelAlignRetryModalOpen] = useState(false)
  const [modelAlignRetryHint, setModelAlignRetryHint] = useState('模型切换中，请稍等几秒再试')
  const modelAlignRetryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [mobileStatusModalOpen, setMobileStatusModalOpen] = useState(false)
  const [mobileStatusChecking, setMobileStatusChecking] = useState(false)
  const [mobileOnlineStep, setMobileOnlineStep] = useState<MobileStatusCheckStep>('idle')
  const [mobileGuiTaskStep, setMobileGuiTaskStep] = useState<MobileStatusCheckStep>('idle')
  const [mobileStatusCheckHint, setMobileStatusCheckHint] = useState('')
  const mobileStatusCheckingRef = useRef(false)
  const mobileStatusBootCheckedRef = useRef(false)
  const [imageLightbox, setImageLightbox] = useState<ImageLightboxPayload | null>(null)
  const [userAvatar, setUserAvatar] = useState<string | undefined>()
  const [userName, setUserName] = useState('')
  const messagesContainerRef = useRef<HTMLDivElement>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const messagesTopRef = useRef<HTMLDivElement>(null)
  const shouldAutoScrollRef = useRef(true)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const [showEmoji, setShowEmoji] = useState(false)
  const [showSkillPicker, setShowSkillPicker] = useState(false)
  const [skillKeyword, setSkillKeyword] = useState('')
  const [selectedFocusSkills, setSelectedFocusSkills] = useState<string[]>([])
  const [focusSkillSyncing, setFocusSkillSyncing] = useState(false)
  const [focusSkillRefreshKey, setFocusSkillRefreshKey] = useState(0)
  const [expandedSystemIds, setExpandedSystemIds] = useState<Set<string>>(new Set())
  const [sessionSidebarOpen, setSessionSidebarOpen] = useState(false)
  const [profileSidebarTarget, setProfileSidebarTarget] = useState<ChatProfileTarget>(null)
  const [workflowWorkspace, setWorkflowWorkspace] = useState<WorkflowWorkspacePayload | null>(null)
  const [workflowInsertMenuOpen, setWorkflowInsertMenuOpen] = useState(false)
  const workflowInsertMenuRef = useRef<HTMLDivElement>(null)
  const [workflowStartMenuOpen, setWorkflowStartMenuOpen] = useState(false)
  const workflowStartMenuRef = useRef<HTMLDivElement>(null)
  const [workflowHeaderMenuOpen, setWorkflowHeaderMenuOpen] = useState(false)
  const workflowHeaderMenuRef = useRef<HTMLDivElement>(null)
  const [workflowContextMenu, setWorkflowContextMenu] = useState<{
    x: number
    y: number
    target: 'node' | 'edge'
    id: string
  } | null>(null)
  const workflowLoadInputRef = useRef<HTMLInputElement | null>(null)
  const workflowCanvasRef = useRef<HTMLElement>(null)
  const [workflowCloudVersion, setWorkflowCloudVersion] = useState(0)
  const [workflowRunInProgress, setWorkflowRunInProgress] = useState(false)
  const [workflowRunNodeStatusMap, setWorkflowRunNodeStatusMap] = useState<Record<string, WorkflowRunNodeStatus>>({})
  const [workflowRunLogs, setWorkflowRunLogs] = useState<WorkflowRunLogItem[]>([])
  const [workflowRunLogOpen, setWorkflowRunLogOpen] = useState(false)
  const workflowRunTokenRef = useRef(0)
  const [workflowCanvasNodes, setWorkflowCanvasNodes] = useState<WorkflowCanvasNode[]>([])
  const [workflowCanvasEdges, setWorkflowCanvasEdges] = useState<WorkflowCanvasEdge[]>([])
  const [workflowConnecting, setWorkflowConnecting] = useState<{ fromNodeId: string; toX: number; toY: number } | null>(null)
  const [workflowSelectedTarget, setWorkflowSelectedTarget] = useState<WorkflowEditTarget>(null)
  const [workflowEditTarget, setWorkflowEditTarget] = useState<WorkflowEditTarget>(null)
  const [workflowUpstreamDraftNodeId, setWorkflowUpstreamDraftNodeId] = useState('')
  const [workflowDownstreamDraftNodeId, setWorkflowDownstreamDraftNodeId] = useState('')
  const [assistantRolePromptDraft, setAssistantRolePromptDraft] = useState('')
  const [assistantRolePromptSaving, setAssistantRolePromptSaving] = useState(false)
  const [assistantRolePromptSaveHint, setAssistantRolePromptSaveHint] = useState('')
  const [workflowAddMemberOpen, setWorkflowAddMemberOpen] = useState(false)
  const [workflowAddMemberType, setWorkflowAddMemberType] = useState<'friend' | 'assistant'>('friend')
  const [workflowAddMemberTargetId, setWorkflowAddMemberTargetId] = useState('')
  const [workflowAddMemberSaving, setWorkflowAddMemberSaving] = useState(false)
  const [workflowAddMemberHint, setWorkflowAddMemberHint] = useState('')
  const [workflowViewport, setWorkflowViewport] = useState<WorkflowViewportState>({
    scale: WORKFLOW_DEFAULT_SCALE,
    offsetX: 0,
    offsetY: 0,
  })
  const workflowNodeDragRef = useRef<{ nodeId: string; offsetX: number; offsetY: number } | null>(null)
  const workflowPanRef = useRef<{ startX: number; startY: number; baseOffsetX: number; baseOffsetY: number } | null>(null)
  const [ideModeEnabled, setIdeModeEnabled] = useState(false)
  const [ideFileMenuOpen, setIdeFileMenuOpen] = useState(false)
  const [ideTerminalMenuOpen, setIdeTerminalMenuOpen] = useState(false)
  const [ideRecentPanelOpen, setIdeRecentPanelOpen] = useState(false)
  const [ideOpenedEntries, setIdeOpenedEntries] = useState<string[]>([])
  const [ideManualFolders, setIdeManualFolders] = useState<string[]>([])
  const [ideOpenTabs, setIdeOpenTabs] = useState<string[]>([])
  const [ideRecentEntries, setIdeRecentEntries] = useState<string[]>([])
  const [ideExplorerTree, setIdeExplorerTree] = useState<IdeTreeNode[]>([])
  const [ideExplorerSearchOpen, setIdeExplorerSearchOpen] = useState(false)
  const [ideExplorerSearchKeyword, setIdeExplorerSearchKeyword] = useState('')
  const [ideExplorerContextMenu, setIdeExplorerContextMenu] = useState<{
    x: number
    y: number
    path: string
    nodeType: 'file' | 'folder'
  } | null>(null)
  const [ideExpandedFolders, setIdeExpandedFolders] = useState<Set<string>>(new Set())
  const [ideExplorerWidth, setIdeExplorerWidth] = useState(230)
  const [ideRightPaneWidth, setIdeRightPaneWidth] = useState(364)
  const [ideActiveFile, setIdeActiveFile] = useState<string | null>(null)
  const [ideFileContents, setIdeFileContents] = useState<Record<string, string>>({})
  const [ideSavedPaths, setIdeSavedPaths] = useState<Record<string, string>>({})
  const [ideSavedContents, setIdeSavedContents] = useState<Record<string, string>>({})
  const [ideUnsavedDrafts, setIdeUnsavedDrafts] = useState<Record<string, true>>({})
  const [ideHumanEditedPaths, setIdeHumanEditedPaths] = useState<Record<string, true>>({})
  const [ideAssistantAddedPaths, setIdeAssistantAddedPaths] = useState<Record<string, true>>({})
  const [idePreferredCwd, setIdePreferredCwd] = useState<string | null>(null)
  const [ideQaContextRootRelPath, setIdeQaContextRootRelPath] = useState<string | null>(null)
  const [ideQaContextRootAbsPath, setIdeQaContextRootAbsPath] = useState<string | null>(null)
  const [ideQaLastRetrievalHitCount, setIdeQaLastRetrievalHitCount] = useState(0)
  const [ideDiffReviewPath, setIdeDiffReviewPath] = useState<string | null>(null)
  const [ideTerminalVisible, setIdeTerminalVisible] = useState(false)
  const [ideTerminalCollapsed, setIdeTerminalCollapsed] = useState(true)
  const [ideTerminalHeight, setIdeTerminalHeight] = useState(172)
  const [ideTerminals, setIdeTerminals] = useState<Array<{ id: string; name: string }>>([{ id: 'py-1', name: '终端1' }])
  const [ideActiveTerminalId, setIdeActiveTerminalId] = useState('py-1')
  const [ideTerminalError, setIdeTerminalError] = useState('')
  const [ideTerminalFindOpen, setIdeTerminalFindOpen] = useState(false)
  const [ideTerminalFindKeyword, setIdeTerminalFindKeyword] = useState('')
  const [ideTerminalFindCurrent, setIdeTerminalFindCurrent] = useState(0)
  const [ideTerminalFindTotal, setIdeTerminalFindTotal] = useState(0)
  const [ideTerminalRunning, setIdeTerminalRunning] = useState(false)
  const ideFileMenuRef = useRef<HTMLDivElement>(null)
  const ideTerminalMenuRef = useRef<HTMLDivElement>(null)
  const ideEditorRef = useRef<HTMLTextAreaElement>(null)
  const [ideFindOpen, setIdeFindOpen] = useState(false)
  const [ideFindKeyword, setIdeFindKeyword] = useState('')
  const [ideFindCurrentMatch, setIdeFindCurrentMatch] = useState(0)
  const [idePptxPreviewLoading, setIdePptxPreviewLoading] = useState(false)
  const [idePptxPreviewSlides, setIdePptxPreviewSlides] = useState<Array<{ title: string; text: string }>>([])
  const [idePptxPreviewError, setIdePptxPreviewError] = useState('')
  const idePptxPreviewCacheRef = useRef<Record<string, Array<{ title: string; text: string }>>>({})
  const idePptxPreviewErrorCacheRef = useRef<Record<string, string>>({})
  const ideFindInputRef = useRef<HTMLInputElement>(null)
  const ideTerminalHostsRef = useRef<Record<string, HTMLDivElement | null>>({})
  const ideTerminalFindInputRef = useRef<HTMLInputElement>(null)
  const ideTerminalMapRef = useRef<Record<string, { term: Terminal; fitAddon: FitAddon; searchAddon: SearchAddon; unsub: (() => void) | null }>>({})
  const ideTerminalConnectedRef = useRef(false)
  const ideTerminalBusyRef = useRef(false)
  const ideTerminalCounterRef = useRef(1)
  const ideDefaultWorkspaceLoadedRef = useRef(false)
  const ideSyncIgnoredPathsRef = useRef<Set<string>>(new Set())
  const [fileLinkActionTarget, setFileLinkActionTarget] = useState<FileLinkActionTarget | null>(null)
  const [profileFriends, setProfileFriends] = useState<Friend[]>([])
  const [onlineUsers, setOnlineUsers] = useState<Set<string>>(new Set())
  const [groupInfoCache, setGroupInfoCache] = useState<Record<string, GroupInfo>>({})
  const [shareCardPreview, setShareCardPreview] = useState<AssistantShareCardPayload | null>(null)
  const [shareSkillPreview, setShareSkillPreview] = useState<SkillShareCardPayload | null>(null)
  const [addingShareAssistant, setAddingShareAssistant] = useState(false)
  const [addingShareSkill, setAddingShareSkill] = useState(false)
  /** 多 session：当前选中的 session id（仅自定义小助手且 multiSessionEnabled 时使用） */
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null)
  const currentSessionIdRef = useRef<string | null>(null)
  currentSessionIdRef.current = currentSessionId
  const [topoSafeModeEnabled, setTopoSafeModeEnabled] = useState<boolean>(isTopoClawSafeModeEnabled())
  /** 多 session：会话列表，按 createdAt 倒序 */
  const [sessions, setSessions] = useState<ChatSession[]>([])
  const sessionUuidRef = useRef<string>(uuidv4())
  const executedIdsRef = useRef<Set<string>>(new Set())
  const autoExecuteCode = getAutoExecuteCode()
  const openScheduledJobEditor = useCallback((jobId: string) => {
    const id = (jobId || '').trim()
    if (!id) return
    window.dispatchEvent(new CustomEvent<{ jobId: string }>(OPEN_SCHEDULED_JOB_EDITOR_EVENT, { detail: { jobId: id } }))
  }, [])
  const imei = getImei()
  /** 未登录无 imei 时仍用稳定 device_id 参与 thread_id，便于 WebSocket 池建连与收发 */
  const chatActorId = imei ?? getDeviceId()
  const isCrossDevice = conversation?.type === 'cross_device' && conversation?.id === CONVERSATION_ID_ME
  const isAssistant = conversation?.type === 'assistant' && conversation?.id === CONVERSATION_ID_ASSISTANT
  const isChatAssistant = conversation?.type === 'assistant' && conversation?.id === CONVERSATION_ID_CHAT_ASSISTANT
  const isImQq = conversation?.type === 'assistant' && conversation?.id === CONVERSATION_ID_IM_QQ
  const isImWeixin = conversation?.type === 'assistant' && conversation?.id === CONVERSATION_ID_IM_WEIXIN
  const isImConversation = isImQq || isImWeixin
  const customAssistant =
    conversation?.id && isCustomAssistantId(conversation.id)
      ? (getCustomAssistantById(conversation.id) ?? (conversation.baseUrl ? getCustomAssistantByBaseUrl(conversation.baseUrl) : undefined))
      : null
  const isCustomChatAssistant = customAssistant ? hasChat(customAssistant) : false
  const builtinChatSlot =
    conversation?.id && isCustomAssistantId(conversation.id) ? builtinSlotForAssistantId(conversation.id) : null
  const isTopoClawConversation = isChatAssistant || builtinChatSlot === 'topoclaw'
  const isTopoClawSafeModeTarget = conversation?.id === DEFAULT_TOPOCLAW_ASSISTANT_ID || builtinChatSlot === 'topoclaw'
  const isCurrentTopoSessionSealed = isTopoClawSafeModeTarget && !!currentSessionId && isTopoClawSessionSealed(currentSessionId)
  const shouldBlockTopoCloudTraffic = isTopoClawSafeModeTarget && (topoSafeModeEnabled || isCurrentTopoSessionSealed)
  const canUseFocusSkills = isTopoClawConversation
  const isMobileReadyForGuiTask = mobileOnlineStep === 'success' && mobileGuiTaskStep === 'success'
  const isMultiSessionCustom = (customAssistant ? hasMultiSession(customAssistant) : false) || (conversation?.multiSessionEnabled === true)
  const isFriend = conversation?.type === 'friend' && conversation?.id?.startsWith('friend_')
  const currentFriendImei = isFriend && conversation?.id ? conversation.id.replace(/^friend_/, '') : ''
  const isCurrentFriendOnline = !!(isFriend && currentFriendImei && onlineUsers.has(currentFriendImei))
  const isGroup = conversation?.type === 'group'
  /** 支持从云端/助手 HTTP 拉历史的会话类型（打开时仅本地；点击标题手动同步） */
  const needCloudHistorySync =
    isCrossDevice ||
    isAssistant ||
    isChatAssistant ||
    isCustomChatAssistant ||
    (isFriend && !!conversation?.id) ||
    (isGroup && !!conversation?.id)
  const triggerConversationSummary = useCallback((targetConversationId?: string, targetConversationName?: string) => {
    const id = String(targetConversationId || '').trim()
    if (!id) return
    if (!(id.startsWith('friend_') || id.startsWith('group_'))) return
    void maybeGenerateConversationSummary({
      conversationId: id,
      conversationName: String(targetConversationName || '').trim() || undefined,
    })
  }, [])
  /** 好友私聊、我的手机：不展示「正在思考」占位与工具思考折叠栏（群聊与小助手不受影响） */
  const suppressThinkingChrome = isFriend || isCrossDevice || isImConversation
  /** 小助手会话头像：使用已配置头像；内置 TopoClaw / GroupManager 无头像时使用专用图。 */
  const assistantAvatar = customAssistant
    ? (toAvatarSrcLikeContacts(resolveCustomAssistantAvatarForDisplay(customAssistant)) ?? toAvatarSrc(conversation?.avatar))
    : toAvatarSrc(conversation?.avatar)
  /** 群聊中根据小助手名称获取头像（无配置时不使用自动执行小助手图作为通用兜底） */
  const getGroupAssistantAvatar = useCallback((senderName: string) => {
    const defaultMap: Record<string, string> = {
      '自动执行小助手': ASSISTANT_AVATAR,
      GroupManager: GROUP_MANAGER_AVATAR,
      '聊天小助手': CHAT_ASSISTANT_AVATAR,
      '技能学习小助手': SKILL_LEARNING_AVATAR,
      '人工客服': CUSTOMER_SERVICE_AVATAR,
    }
    if (defaultMap[senderName]) return defaultMap[senderName]
    const asst = conversation?.assistants?.find((a) => a.name === senderName)
    const custom = asst
      ? resolveGroupAssistantConfig(asst.id, senderName, conversation?.assistantConfigs)
      : getCustomAssistants().find((c) => c.name === senderName)
    if (custom) return toAvatarSrcLikeContacts(resolveCustomAssistantAvatarForDisplay(custom)) ?? undefined
    if (asst?.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID) return GROUP_MANAGER_AVATAR
    return undefined
  }, [conversation?.assistants, conversation?.assistantConfigs])
  const currentConvIdRef = useRef(conversation?.id)
  currentConvIdRef.current = conversation?.id

  useEffect(() => {
    const handler = () => setTopoSafeModeEnabled(isTopoClawSafeModeEnabled())
    window.addEventListener(TOPOCLAW_SAFE_MODE_CHANGED_EVENT, handler)
    return () => window.removeEventListener(TOPOCLAW_SAFE_MODE_CHANGED_EVENT, handler)
  }, [])

  useEffect(() => {
    const validatedImei = getValidatedImei()
    if (!validatedImei) {
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
    if (!isTopoClawSafeModeTarget || !topoSafeModeEnabled || !currentSessionId) return
    sealTopoClawSession(currentSessionId)
  }, [isTopoClawSafeModeTarget, topoSafeModeEnabled, currentSessionId])
  /** 跨端活跃 session：用 updated_at 去重，避免 WS 回显重复跟切 */
  const activeSessionRemoteTsRef = useRef(0)
  const applyRemoteActiveSessionRef = useRef<
    (p: { conversation_id?: string; base_url?: string; active_session_id?: string; updated_at?: number }) => void
  >(() => {})
  useEffect(() => {
    activeSessionRemoteTsRef.current = 0
  }, [conversation?.id])
  /** 当前 messages 所属的会话 ID，避免切换会话时把上一会话的消息错误保存到新会话 */
  const messagesOwnerRef = useRef<string | null>(null)
  /** 正在切换会话（加载中），保存逻辑应跳过，避免使用尚未刷新的 state */
  const isTransitioningRef = useRef(false)
  /** 历史拉取代数：仅最新一次请求结束时可清理 historyLoading */
  const historyLoadSeqRef = useRef(0)
  /** 用户点击标题触发的云端历史请求，切换会话时中止 */
  const cloudHistoryManualAbortRef = useRef<AbortController | null>(null)
  const [imConnected, setImConnected] = useState(false)
  const [imSwitching, setImSwitching] = useState(false)
  const [imConnectionHint, setImConnectionHint] = useState('')
  const [digitalCloneGlobalEnabled, setDigitalCloneGlobalEnabled] = useState(false)
  const [digitalCloneCurrentFriendEnabled, setDigitalCloneCurrentFriendEnabled] = useState(false)
  const [digitalCloneCurrentFriendOverride, setDigitalCloneCurrentFriendOverride] = useState(false)
  const [digitalCloneSaving, setDigitalCloneSaving] = useState(false)
  /** 会话维度执行态缓存：用于切换会话后恢复该会话的停止按钮状态 */
  const executionStateByRouteRef = useRef<Record<string, { loading: boolean; mobileRunning: boolean }>>({})
  const previousExecutionRouteKeyRef = useRef('')
  const executionRouteKey = useMemo(() => {
    const convId = String(conversation?.id || '').trim()
    if (!convId) return ''
    const sid = isMultiSessionCustom ? String(currentSessionId || '').trim() : ''
    return sid ? `${convId}::${sid}` : convId
  }, [conversation?.id, isMultiSessionCustom, currentSessionId])
  const currentExecutionRouteKeyRef = useRef('')
  currentExecutionRouteKeyRef.current = executionRouteKey

  /** 切换会话/子会话时：保存旧会话执行态，并恢复新会话执行态 */
  useEffect(() => {
    const prevKey = previousExecutionRouteKeyRef.current
    const nextKey = executionRouteKey
    if (prevKey && prevKey !== nextKey) {
      executionStateByRouteRef.current[prevKey] = {
        loading,
        mobileRunning: isMobileTaskRunning,
      }
    }
    if (!nextKey) {
      if (loading) setLoading(false)
      if (isMobileTaskRunning) setIsMobileTaskRunning(false)
      previousExecutionRouteKeyRef.current = ''
      return
    }
    const remembered = executionStateByRouteRef.current[nextKey] || { loading: false, mobileRunning: false }
    if (loading !== remembered.loading) setLoading(remembered.loading)
    if (isMobileTaskRunning !== remembered.mobileRunning) setIsMobileTaskRunning(remembered.mobileRunning)
    previousExecutionRouteKeyRef.current = nextKey
  }, [executionRouteKey])

  /** 当前会话执行态变化时实时落盘，确保切走后可恢复 */
  useEffect(() => {
    if (!executionRouteKey) return
    executionStateByRouteRef.current[executionRouteKey] = {
      loading,
      mobileRunning: isMobileTaskRunning,
    }
  }, [executionRouteKey, loading, isMobileTaskRunning])

  useEffect(() => {
    let cancelled = false
    const loadDigitalCloneSetting = async () => {
      if (!imei || !isFriend || !currentFriendImei) {
        setDigitalCloneCurrentFriendOverride(false)
        setDigitalCloneCurrentFriendEnabled(false)
        return
      }
      try {
        const res = await getUserSettings(imei)
        if (!res.success || cancelled) return
        const settings = res.settings || {}
        const globalEnabled = settings.digital_clone_enabled === true
        const overrides = settings.digital_clone_friend_overrides || {}
        const hasOverride = Object.prototype.hasOwnProperty.call(overrides, currentFriendImei)
        const currentEnabled = hasOverride ? overrides[currentFriendImei] === true : globalEnabled
        setDigitalCloneGlobalEnabled(globalEnabled)
        setDigitalCloneCurrentFriendOverride(hasOverride)
        setDigitalCloneCurrentFriendEnabled(currentEnabled)
      } catch {
        // ignore settings fetch failure in chat detail
      }
    }
    void loadDigitalCloneSetting()
    return () => {
      cancelled = true
    }
  }, [imei, isFriend, currentFriendImei, conversation?.id])
  /** 群聊 @ 提及：好友列表（用于解析群成员昵称） */
  const [groupFriends, setGroupFriends] = useState<Friend[]>([])
  /** 群聊 @ 提及：弹出层显示、@ 起始位置、查询词、选中下标 */
  const [mentionPopupOpen, setMentionPopupOpen] = useState(false)
  const mentionStartPosRef = useRef(-1)
  const [mentionQuery, setMentionQuery] = useState('')
  const [mentionSelectedIndex, setMentionSelectedIndex] = useState(0)
  const mentionPopupRef = useRef<HTMLDivElement>(null)
  /** 群组管理小助手触发的执行：message_id -> 待跟进上下文，execute_result 到达后触发群组管理者二次判断 */
  const pendingGroupManagerFollowUpRef = useRef<Map<string, PendingGroupManagerFollowUp>>(new Map())
  /** 好友消息经 customer_service WebSocket 发送时，等待 friend_message_ack / friend_message_error */
  const friendWsWaitersRef = useRef<
    Map<
      string,
      {
        resolve: (v: { message_id: string; target_online: boolean }) => void
        reject: (e: Error) => void
      }
    >
  >(new Map())
  const screenshotAskSendRef = useRef<
    (text: string, image?: { base64: string; name?: string; mime?: string }) => void
  >(() => {})
  /** 截图浮层「提问 TopoClaw」：先切到内置 TopoClaw（custom_topoclaw）会话，再发送 */
  const pendingDesktopScreenshotSendRef = useRef<{
    text: string
    image?: { base64: string; name?: string; mime?: string }
  } | null>(null)
  /** 点击停止后用于屏蔽旧流式回调（按会话/子会话隔离，避免切换会话误伤其他任务） */
  const chatStreamGenerationByScopeRef = useRef<Map<string, number>>(new Map())
  const buildExecutionScopeKey = useCallback((conversationId?: string | null, sessionId?: string | null) => {
    const convId = String(conversationId || '').trim()
    if (!convId) return '__global__'
    const sid = String(sessionId || '').trim()
    return sid ? `${convId}__${sid}` : convId
  }, [])
  const bumpChatStreamGeneration = useCallback((conversationId?: string | null, sessionId?: string | null) => {
    const key = buildExecutionScopeKey(conversationId, sessionId)
    const prev = chatStreamGenerationByScopeRef.current.get(key) || 0
    const next = prev + 1
    chatStreamGenerationByScopeRef.current.set(key, next)
    return next
  }, [buildExecutionScopeKey])
  const getChatStreamGeneration = useCallback((conversationId?: string | null, sessionId?: string | null) => {
    const key = buildExecutionScopeKey(conversationId, sessionId)
    return chatStreamGenerationByScopeRef.current.get(key) || 0
  }, [buildExecutionScopeKey])
  /** 记录所有本地电脑执行循环，按会话 scope 管理中止 */
  const activePcExecutionAbortControllersByScopeRef = useRef<Map<string, Set<AbortController>>>(new Map())
  const abortPcExecutionsForScope = useCallback((conversationId?: string | null, sessionId?: string | null) => {
    const key = buildExecutionScopeKey(conversationId, sessionId)
    const scoped = activePcExecutionAbortControllersByScopeRef.current.get(key)
    if (!scoped) return
    scoped.forEach((ctrl) => ctrl.abort())
    activePcExecutionAbortControllersByScopeRef.current.delete(key)
  }, [buildExecutionScopeKey])
  const abortAllPcExecutions = useCallback(() => {
    activePcExecutionAbortControllersByScopeRef.current.forEach((set) => {
      set.forEach((ctrl) => ctrl.abort())
    })
    activePcExecutionAbortControllersByScopeRef.current.clear()
  }, [])
  const runComputerUseLoopWithAbort = useCallback(
    async (
      baseUrl: string,
      requestId: string,
      query: string,
      chatSummary?: string,
      scope?: { conversationId?: string | null; sessionId?: string | null }
    ) => {
      const scopeKey = buildExecutionScopeKey(scope?.conversationId, scope?.sessionId)
      const abortCtrl = new AbortController()
      const scopes = activePcExecutionAbortControllersByScopeRef.current
      const scopedSet = scopes.get(scopeKey) ?? new Set<AbortController>()
      scopedSet.add(abortCtrl)
      scopes.set(scopeKey, scopedSet)
      try {
        return await runComputerUseLoop(baseUrl, requestId, query, chatSummary, abortCtrl.signal)
      } finally {
        const latestSet = activePcExecutionAbortControllersByScopeRef.current.get(scopeKey)
        if (latestSet) {
          latestSet.delete(abortCtrl)
          if (latestSet.size === 0) activePcExecutionAbortControllersByScopeRef.current.delete(scopeKey)
        }
      }
    },
    [buildExecutionScopeKey]
  )

  useEffect(() => {
    return () => {
      if (stopTaskNoticeTimerRef.current) {
        clearTimeout(stopTaskNoticeTimerRef.current)
        stopTaskNoticeTimerRef.current = null
      }
      if (clipboardSavedNoticeTimerRef.current) {
        clearTimeout(clipboardSavedNoticeTimerRef.current)
        clipboardSavedNoticeTimerRef.current = null
      }
      if (quickNoteSavedNoticeTimerRef.current) {
        clearTimeout(quickNoteSavedNoticeTimerRef.current)
        quickNoteSavedNoticeTimerRef.current = null
      }
      if (inlineToastNoticeTimerRef.current) {
        clearTimeout(inlineToastNoticeTimerRef.current)
        inlineToastNoticeTimerRef.current = null
      }
      abortAllPcExecutions()
    }
  }, [abortAllPcExecutions])

  useEffect(() => {
    setStopTaskNotice(false)
    if (stopTaskNoticeTimerRef.current) {
      clearTimeout(stopTaskNoticeTimerRef.current)
      stopTaskNoticeTimerRef.current = null
    }
    setClipboardSavedNotice(false)
    if (clipboardSavedNoticeTimerRef.current) {
      clearTimeout(clipboardSavedNoticeTimerRef.current)
      clipboardSavedNoticeTimerRef.current = null
    }
    setQuickNoteSavedNotice(false)
    if (quickNoteSavedNoticeTimerRef.current) {
      clearTimeout(quickNoteSavedNoticeTimerRef.current)
      quickNoteSavedNoticeTimerRef.current = null
    }
    setInlineToastNotice('')
    if (inlineToastNoticeTimerRef.current) {
      clearTimeout(inlineToastNoticeTimerRef.current)
      inlineToastNoticeTimerRef.current = null
    }
    bulkImportCancelRef.current = true
    setBulkImportProgress(null)
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
    setQuotedMessageContext(null)
    setProfileSidebarTarget(null)
    setWorkflowWorkspace(null)
  }, [conversation?.id])

  const showInlineToastNotice = useCallback((text: string) => {
    const content = String(text || '').trim()
    if (!content) return
    setInlineToastNotice(content)
    if (inlineToastNoticeTimerRef.current) {
      clearTimeout(inlineToastNoticeTimerRef.current)
      inlineToastNoticeTimerRef.current = null
    }
    inlineToastNoticeTimerRef.current = setTimeout(() => {
      setInlineToastNotice('')
      inlineToastNoticeTimerRef.current = null
    }, 3200)
  }, [])

  useEffect(() => {
    const subscribe = window.electronAPI?.onDesktopScreenshotPrefill
    if (!subscribe) return
    return subscribe(
      ({
        text,
        autoSend,
        imageBase64,
        imageMime,
        imageName,
        forceTopoClaw,
        skipComposerImage,
        saveToQuickNote,
      }) => {
        if (saveToQuickNote === true) return
        const value = String(text || '').trim()
        const hasImage = typeof imageBase64 === 'string' && imageBase64.length > 50
        if (!value && !hasImage) return
        const imagePayload = hasImage
          ? {
              base64: imageBase64 as string,
              mime: imageMime || 'image/png',
              name: imageName || `screenshot-${Date.now()}.png`,
            }
          : null
        const skipComposer = skipComposerImage === true
        const forceTc = forceTopoClaw === true

        if (imagePayload && !skipComposer) {
          setSelectedImages([
            {
              base64: imagePayload.base64,
              mime: imagePayload.mime,
              name: imagePayload.name,
            },
          ])
        }

        if (value && !(autoSend && skipComposer)) {
          setInput((prev) => (prev.trim() ? `${prev}\n${value}` : value))
        }
        if (!(autoSend && skipComposer)) {
          requestAnimationFrame(() => inputRef.current?.focus())
        }

        if (!autoSend) return

        if (forceTc && !isTopoClawConversation) {
          /** TopoClaw 为内置自定义助手 custom_topoclaw，不是 chat_assistant（聊天小助手） */
          const topoAsst = getCustomAssistantById(DEFAULT_TOPOCLAW_ASSISTANT_ID)
          const name = (topoAsst?.name || 'TopoClaw').trim() || 'TopoClaw'
          onSelectConversation?.({
            id: DEFAULT_TOPOCLAW_ASSISTANT_ID,
            name,
            lastMessageTime: Date.now(),
            type: 'assistant',
            avatar: topoAsst ? resolveCustomAssistantAvatarForDisplay(topoAsst) : undefined,
            baseUrl: topoAsst?.baseUrl,
            multiSessionEnabled: topoAsst?.multiSessionEnabled ?? true,
          })
          pendingDesktopScreenshotSendRef.current = { text: value, image: imagePayload ?? undefined }
          return
        }

        screenshotAskSendRef.current(value, imagePayload ?? undefined)
      }
    )
  }, [onSelectConversation, isTopoClawConversation])

  useEffect(() => {
    if (isGroup && imei) {
      getFriends(imei).catch(() => []).then(setGroupFriends)
    } else {
      setGroupFriends([])
    }
  }, [isGroup, imei])

  useEffect(() => {
    if (!imei) {
      setProfileFriends([])
      return
    }
    let cancelled = false
    getFriends(imei)
      .then((list) => {
        if (!cancelled) setProfileFriends(list)
      })
      .catch(() => {
        if (!cancelled) setProfileFriends([])
      })
    return () => {
      cancelled = true
    }
  }, [imei])

  /** 群聊成员消息：按昵称在好友列表中解析头像，无则首字占位 */
  const getGroupMemberDisplayName = useCallback(
    (senderImei: string, fallbackName?: string) => {
      const sid = (senderImei || '').trim()
      if (!sid) return (fallbackName || '').trim() || '群成员'
      const myImei = (imei ?? '').trim()
      if (myImei && sid === myImei) return userName || '我'
      const matched = groupFriends.find((x) => x.imei === sid)
      if (matched?.nickname?.trim()) return matched.nickname.trim()
      return sid.slice(0, 8) + '...'
    },
    [groupFriends, imei, userName]
  )

  const getGroupMemberAvatar = useCallback(
    (senderName: string, senderImei?: string) => {
      const sid = (senderImei || '').trim()
      if (sid) {
        const byImei = groupFriends.find((x) => x.imei === sid)
        if (byImei?.avatar) return toAvatarSrc(byImei.avatar)
      }
      const name = (senderName || '').trim()
      if (!name || name === '群成员') return undefined
      const f = groupFriends.find((x) => (x.nickname || '').trim() === name)
      if (f?.avatar) return toAvatarSrc(f.avatar)
      const memberImeis = conversation?.members?.length ? conversation.members : []
      for (const mImei of memberImeis) {
        const gf = groupFriends.find((x) => x.imei === mImei)
        if (gf && (gf.nickname || '').trim() === name && gf.avatar) return toAvatarSrc(gf.avatar)
      }
      return undefined
    },
    [groupFriends, conversation?.members]
  )

  /** 群聊 @ 提及候选：群内所有小助手 + 群成员（排除自己），按 query 过滤。好友群无 members 时用 groupFriends */
  const groupAssistantConfigsForDisplay = useMemo(() => {
    const assistants = conversation?.assistants ?? []
    const baseConfigs = conversation?.assistantConfigs ?? {}
    if (!assistants.length) return baseConfigs
    const groupCreatorImei = (conversation as { creator_imei?: string } | null)?.creator_imei?.trim()
      || (groupInfoCache[normalizeGroupRawId(conversation?.id) || '']?.creator_imei || '').trim()
    const memberImeis = [
      ...(conversation?.members ?? []),
      groupCreatorImei,
    ].map((s) => String(s || '').trim()).filter(Boolean)
    const out: Record<string, { baseUrl?: string; name?: string; displayId?: string; capabilities?: string[]; rolePrompt?: string; creator_imei?: string; creator_nickname?: string }> = {}
    for (const a of assistants) {
      const cfg = baseConfigs[a.id] ?? {}
      const creatorImeiFromCfg = (cfg as { creator_imei?: string }).creator_imei?.trim() || ''
      const creatorImeiFromId = inferCreatorImeiFromAssistantId(a.id, memberImeis)
      const isBuiltinOrFixedId =
        a.id === CONVERSATION_ID_ASSISTANT
        || a.id === 'skill_learning'
        || a.id === CONVERSATION_ID_CHAT_ASSISTANT
        || a.id === 'customer_service'
        || a.id === DEFAULT_TOPOCLAW_ASSISTANT_ID
        || a.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID
      const creatorImei = creatorImeiFromCfg || creatorImeiFromId || (isBuiltinOrFixedId ? groupCreatorImei : '')
      const creatorNickname =
        (cfg as { creator_nickname?: string }).creator_nickname?.trim()
        || (
          creatorImei
            ? (creatorImei === imei
              ? (userName || '').trim()
              : (groupFriends.find((f) => f.imei === creatorImei)?.nickname || '').trim())
            : ''
        )
      out[a.id] = { ...cfg, creator_imei: creatorImei || undefined, creator_nickname: creatorNickname || undefined }
    }
    return out
  }, [conversation?.assistants, conversation?.assistantConfigs, conversation?.id, imei, userName, groupFriends, groupInfoCache])

  const groupAssistantDisplayNameMap = useMemo(() => {
    const assistants = conversation?.assistants ?? []
    const m: Record<string, string> = {}
    for (const a of assistants) {
      m[a.id] = getGroupAssistantDisplayName(a.id, a.name, assistants, groupAssistantConfigsForDisplay)
    }
    return m
  }, [conversation?.assistants, groupAssistantConfigsForDisplay])

  const normalizeGroupCreatorImei = useCallback((raw: string | undefined | null): string => {
    const text = String(raw || '').trim()
    if (!text) return ''
    const noNick = (text.split('·', 1)[0] || text).trim()
    const noBracket = (noNick.split('(', 1)[0] || noNick).trim()
    return noBracket
  }, [])

  const resolveGroupAssistantOwnerImei = useCallback(
    (assistantId: string, fallbackCreatorImei?: string | null): string => {
      const fromDisplayCfg = groupAssistantConfigsForDisplay?.[assistantId]?.creator_imei
      const fromGroupCfg = conversation?.assistantConfigs?.[assistantId]?.creator_imei
      const normalized =
        normalizeGroupCreatorImei(fromDisplayCfg)
        || normalizeGroupCreatorImei(fromGroupCfg)
        || normalizeGroupCreatorImei(fallbackCreatorImei || '')
      return normalized || String(imei || '').trim()
    },
    [groupAssistantConfigsForDisplay, conversation?.assistantConfigs, normalizeGroupCreatorImei, imei]
  )

  const mentionCandidates = useMemo(() => {
    if (!isGroup) return []
    const members = conversation?.members?.length
      ? conversation.members
      : (conversation?.id === CONVERSATION_ID_GROUP ? groupFriends.map((f) => f.imei) : [])
    const myImei = (imei ?? '').trim()
    const q = mentionQuery.toLowerCase().trim()
    const candidates: { id: string; displayName: string; isAssistant: boolean }[] = []
    /** 群内小助手（含自动执行、功能测试、搜索等） */
    for (const a of conversation?.assistants ?? []) {
      const displayName = groupAssistantDisplayNameMap[a.id] ?? a.name
      const match = !q || displayName.toLowerCase().includes(q) || a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q)
      if (match) candidates.push({ id: a.id, displayName, isAssistant: true })
    }
    /** 群成员（好友） */
    for (const memberImei of members) {
      if (memberImei === myImei) continue
      const f = groupFriends.find((x) => x.imei === memberImei)
      const displayName = f?.nickname ?? (memberImei.slice(0, 8) + '...')
      const match = !q || displayName.toLowerCase().includes(q) || memberImei.toLowerCase().includes(q)
      if (match) candidates.push({ id: memberImei, displayName, isAssistant: false })
    }
    return candidates
  }, [isGroup, conversation?.members, conversation?.assistants, imei, groupFriends, mentionQuery, groupAssistantDisplayNameMap])

  /** 群成员列表（含显示名），供群组管理小助手知晓群内有哪些用户 */
  const groupMembersWithNames = useMemo((): Array<{ imei: string; displayName: string }> => {
    if (!isGroup) return []
    const raw =
      conversation?.members?.length
        ? conversation.members
        : conversation?.id === CONVERSATION_ID_GROUP
          ? [...new Set([imei, ...groupFriends.map((f) => f.imei)])]
          : []
    const members = raw.filter((m): m is string => !!m)
    const myImei = (imei ?? '').trim()
    return members.map((m) => ({
      imei: m,
      displayName: m === myImei ? (userName || '我') : (groupFriends.find((f) => f.imei === m)?.nickname ?? m.slice(0, 8) + '...'),
    }))
  }, [isGroup, conversation?.members, conversation?.id, imei, userName, groupFriends])

  const workflowWorkspaceMembers = useMemo<WorkflowWorkspacePayload['members']>(() => {
    if (!isGroup) return []
    const assistantMembers = (conversation?.assistants ?? []).map((assistant) => ({
      id: `assistant-${assistant.id}`,
      name: groupAssistantDisplayNameMap[assistant.id] ?? assistant.name ?? assistant.id,
      type: 'assistant' as const,
    }))
    const userMembers = groupMembersWithNames.map((member) => ({
      id: `member-${member.imei}`,
      name: member.displayName,
      type: 'user' as const,
    }))
    return [...assistantMembers, ...userMembers]
  }, [isGroup, conversation?.assistants, groupAssistantDisplayNameMap, groupMembersWithNames])

  const resetWorkflowViewportToCenter = useCallback((scale = WORKFLOW_DEFAULT_SCALE) => {
    const canvasRect = workflowCanvasRef.current?.getBoundingClientRect()
    if (!canvasRect) return
    setWorkflowViewport({
      scale,
      offsetX: canvasRect.width / 2 - (WORKFLOW_STAGE_WIDTH * scale) / 2,
      offsetY: canvasRect.height / 2 - (WORKFLOW_STAGE_HEIGHT * scale) / 2,
    })
  }, [])

  const workflowClientToWorld = useCallback((clientX: number, clientY: number): { x: number; y: number } | null => {
    const canvasRect = workflowCanvasRef.current?.getBoundingClientRect()
    if (!canvasRect) return null
    return {
      x: (clientX - canvasRect.left - workflowViewport.offsetX) / workflowViewport.scale,
      y: (clientY - canvasRect.top - workflowViewport.offsetY) / workflowViewport.scale,
    }
  }, [workflowViewport.offsetX, workflowViewport.offsetY, workflowViewport.scale])

  const openWorkflowWorkspace = useCallback((payload: WorkflowWorkspacePayload) => {
    setWorkflowWorkspace(payload)
    setProfileSidebarTarget(null)
    setSessionSidebarOpen(false)
    if (ideModeEnabled) setIdeModeEnabled(false)
    if (!conversationListCollapsed) onToggleConversationList?.()
    window.requestAnimationFrame(() => {
      resetWorkflowViewportToCenter()
    })
  }, [ideModeEnabled, conversationListCollapsed, onToggleConversationList, resetWorkflowViewportToCenter])

  const handleWorkflowComposeFromHeader = useCallback(() => {
    const activeConversation = conversation
    if (!activeConversation || activeConversation.type !== 'group') return
    const groupId = activeConversation.id === CONVERSATION_ID_GROUP
      ? CONVERSATION_ID_GROUP
      : toServerGroupId(activeConversation.id)
    openWorkflowWorkspace({
      groupId,
      groupName: activeConversation.name || '群聊',
      members: workflowWorkspaceMembers,
    })
    setWorkflowHeaderMenuOpen(false)
  }, [conversation, workflowWorkspaceMembers, openWorkflowWorkspace])

  const handleCloseWorkflowWorkspace = useCallback(() => {
    setWorkflowWorkspace(null)
    setWorkflowInsertMenuOpen(false)
    setWorkflowStartMenuOpen(false)
    setWorkflowCanvasEdges([])
    setWorkflowConnecting(null)
    setWorkflowSelectedTarget(null)
    setWorkflowEditTarget(null)
    setWorkflowContextMenu(null)
    setWorkflowAddMemberOpen(false)
    setWorkflowAddMemberTargetId('')
    setWorkflowAddMemberHint('')
    if (conversationListCollapsed) onToggleConversationList?.()
  }, [conversationListCollapsed, onToggleConversationList])

  useEffect(() => {
    if (!workflowInsertMenuOpen) return
    const onPointerDown = (event: MouseEvent) => {
      const menu = workflowInsertMenuRef.current
      if (!menu) return
      if (menu.contains(event.target as Node)) return
      setWorkflowInsertMenuOpen(false)
    }
    window.addEventListener('mousedown', onPointerDown)
    return () => {
      window.removeEventListener('mousedown', onPointerDown)
    }
  }, [workflowInsertMenuOpen])

  useEffect(() => {
    if (!workflowStartMenuOpen) return
    const onPointerDown = (event: MouseEvent) => {
      const menu = workflowStartMenuRef.current
      if (!menu) return
      if (menu.contains(event.target as Node)) return
      setWorkflowStartMenuOpen(false)
    }
    window.addEventListener('mousedown', onPointerDown)
    return () => {
      window.removeEventListener('mousedown', onPointerDown)
    }
  }, [workflowStartMenuOpen])

  useEffect(() => {
    if (!workflowHeaderMenuOpen) return
    const onPointerDown = (event: MouseEvent) => {
      const menu = workflowHeaderMenuRef.current
      if (!menu) return
      if (menu.contains(event.target as Node)) return
      setWorkflowHeaderMenuOpen(false)
    }
    window.addEventListener('mousedown', onPointerDown)
    return () => {
      window.removeEventListener('mousedown', onPointerDown)
    }
  }, [workflowHeaderMenuOpen])

  const workflowStorageRawGroupId = useMemo(
    () => normalizeGroupRawId(workflowWorkspace?.groupId || conversation?.id),
    [workflowWorkspace?.groupId, conversation?.id]
  )
  const workflowDraftStorageKey = useMemo(
    () => (workflowStorageRawGroupId ? `${WORKFLOW_DRAFT_STORAGE_KEY_PREFIX}${workflowStorageRawGroupId}` : ''),
    [workflowStorageRawGroupId]
  )

  const buildWorkflowPersistPayload = useCallback(
    (existingMeta?: Partial<WorkflowPersistMeta>): WorkflowPersistPayloadV1 => {
      const now = Date.now()
      const groupId = toServerGroupId(workflowWorkspace?.groupId || conversation?.id || '')
      const groupName = String(workflowWorkspace?.groupName || conversation?.name || '未命名群组').trim() || '未命名群组'
      const workflowName = String(existingMeta?.name || `${groupName}编排`).trim() || `${groupName}编排`
      const selectedNodeId = workflowEditTarget?.type === 'node' ? workflowEditTarget.id : ''
      const selectedEdgeId = workflowEditTarget?.type === 'edge' ? workflowEditTarget.id : ''
      return {
        schemaVersion: 1,
        meta: {
          workflowId: String(existingMeta?.workflowId || `wf_${now}`).trim() || `wf_${now}`,
          name: workflowName,
          groupId,
          groupName,
          createdAt: Number.isFinite(Number(existingMeta?.createdAt)) ? Number(existingMeta?.createdAt) : now,
          updatedAt: now,
          appVersion: WORKFLOW_APP_VERSION,
        },
        graph: {
          nodes: workflowCanvasNodes,
          edges: workflowCanvasEdges,
        },
        ui: {
          viewport: workflowViewport,
          ...(selectedNodeId ? { selectedNodeId } : {}),
          ...(selectedEdgeId ? { selectedEdgeId } : {}),
        },
        extras: {},
      }
    },
    [
      workflowWorkspace?.groupId,
      workflowWorkspace?.groupName,
      conversation?.id,
      conversation?.name,
      workflowEditTarget,
      workflowCanvasNodes,
      workflowCanvasEdges,
      workflowViewport,
    ]
  )

  const applyWorkflowPersistPayload = useCallback((payload: WorkflowPersistPayloadV1) => {
    setWorkflowCanvasNodes(payload.graph.nodes)
    setWorkflowCanvasEdges(payload.graph.edges)
    setWorkflowConnecting(null)
    setWorkflowSelectedTarget(null)
    setWorkflowAddMemberOpen(false)
    setWorkflowAddMemberTargetId('')
    setWorkflowAddMemberHint('')
    setWorkflowViewport(payload.ui?.viewport ? sanitizeWorkflowViewport(payload.ui.viewport) : { scale: WORKFLOW_DEFAULT_SCALE, offsetX: 0, offsetY: 0 })
    const selectedNodeId = String(payload.ui?.selectedNodeId || '').trim()
    const selectedEdgeId = String(payload.ui?.selectedEdgeId || '').trim()
    if (selectedNodeId && payload.graph.nodes.some((node) => node.id === selectedNodeId)) {
      setWorkflowSelectedTarget({ type: 'node', id: selectedNodeId })
      setWorkflowEditTarget({ type: 'node', id: selectedNodeId })
      return
    }
    if (selectedEdgeId && payload.graph.edges.some((edge) => edge.id === selectedEdgeId)) {
      setWorkflowSelectedTarget({ type: 'edge', id: selectedEdgeId })
      setWorkflowEditTarget({ type: 'edge', id: selectedEdgeId })
      return
    }
    setWorkflowSelectedTarget(null)
    setWorkflowEditTarget(null)
  }, [])

  const parseWorkflowPersistText = useCallback((text: string): WorkflowPersistPayloadV1 | null => {
    if (!text.trim()) return null
    try {
      const parsed = JSON.parse(text) as unknown
      return migrateWorkflowPayload(parsed)
    } catch {
      return null
    }
  }, [])

  const parseWorkflowPersistObject = useCallback((raw: unknown): WorkflowPersistPayloadV1 | null => {
    return migrateWorkflowPayload(raw)
  }, [])

  const resolveWorkflowPayloadForConversation = useCallback(async (activeConversation: Conversation): Promise<WorkflowPersistPayloadV1 | null> => {
    if (activeConversation.type !== 'group') return null
    const rawGroupId = normalizeGroupRawId(activeConversation.id)
    const draftStorageKey = rawGroupId ? `${WORKFLOW_DRAFT_STORAGE_KEY_PREFIX}${rawGroupId}` : ''
    const localRaw = draftStorageKey ? window.localStorage.getItem(draftStorageKey) || '' : ''
    const localPayload = parseWorkflowPersistText(localRaw)
    const myImei = (imei || '').trim()
    const serverGroupId = toServerGroupId(activeConversation.id || '')
    if (!myImei || !serverGroupId) return localPayload
    try {
      const cloudRes = await getGroupWorkflow(myImei, serverGroupId)
      const cloudPayload = cloudRes.success && cloudRes.workflow
        ? parseWorkflowPersistObject(cloudRes.workflow)
        : null
      if (cloudPayload && localPayload) {
        const cloudUpdatedAt = Number(cloudPayload.meta?.updatedAt || 0)
        const localUpdatedAt = Number(localPayload.meta?.updatedAt || 0)
        if (localUpdatedAt > cloudUpdatedAt) return localPayload
        setWorkflowCloudVersion(Number(cloudRes.version || 0))
        return cloudPayload
      }
      if (cloudPayload) {
        setWorkflowCloudVersion(Number(cloudRes.version || 0))
        return cloudPayload
      }
      return localPayload
    } catch {
      return localPayload
    }
  }, [parseWorkflowPersistText, parseWorkflowPersistObject, imei])

  useEffect(() => {
    let cancelled = false
    setWorkflowCanvasNodes([])
    setWorkflowCanvasEdges([])
    setWorkflowConnecting(null)
    setWorkflowSelectedTarget(null)
    setWorkflowEditTarget(null)
    setWorkflowAddMemberOpen(false)
    setWorkflowAddMemberTargetId('')
    setWorkflowAddMemberHint('')
    setWorkflowCloudVersion(0)
    setWorkflowRunInProgress(false)
    setWorkflowRunNodeStatusMap({})
    setWorkflowRunLogs([])

    const applyEmptyWorkspace = () => {
      window.requestAnimationFrame(() => {
        resetWorkflowViewportToCenter()
      })
    }

    const syncFromCloud = async () => {
      const localRaw = workflowDraftStorageKey ? window.localStorage.getItem(workflowDraftStorageKey) || '' : ''
      const localPayload = parseWorkflowPersistText(localRaw)
      const myImei = (imei || '').trim()
      const serverGroupId = toServerGroupId(workflowWorkspace?.groupId || conversation?.id || '')
      if (!myImei || !serverGroupId) {
        if (localPayload) {
          applyWorkflowPersistPayload(localPayload)
          return
        }
        applyEmptyWorkspace()
        return
      }
      const cloudRes = await getGroupWorkflow(myImei, serverGroupId)
      if (cancelled) return
      const cloudPayload = cloudRes.success && cloudRes.workflow
        ? parseWorkflowPersistObject(cloudRes.workflow)
        : null
      if (cloudPayload && localPayload) {
        const cloudUpdatedAt = Number(cloudPayload.meta?.updatedAt || 0)
        const localUpdatedAt = Number(localPayload.meta?.updatedAt || 0)
        if (localUpdatedAt > cloudUpdatedAt) {
          applyWorkflowPersistPayload(localPayload)
          setWorkflowCloudVersion(Number(cloudRes.version || 0))
          return
        }
        applyWorkflowPersistPayload(cloudPayload)
        setWorkflowCloudVersion(Number(cloudRes.version || 0))
        return
      }
      if (cloudPayload) {
        applyWorkflowPersistPayload(cloudPayload)
        setWorkflowCloudVersion(Number(cloudRes.version || 0))
        return
      }
      if (localPayload) {
        applyWorkflowPersistPayload(localPayload)
        return
      }
      applyEmptyWorkspace()
    }

    void syncFromCloud()
    return () => {
      cancelled = true
    }
  }, [
    workflowWorkspace?.groupId,
    workflowDraftStorageKey,
    parseWorkflowPersistText,
    parseWorkflowPersistObject,
    applyWorkflowPersistPayload,
    resetWorkflowViewportToCenter,
    imei,
    conversation?.id,
  ])

  useEffect(() => {
    if (!workflowWorkspace || !workflowDraftStorageKey) return
    try {
      const prevRaw = window.localStorage.getItem(workflowDraftStorageKey) || ''
      const prevPayload = parseWorkflowPersistText(prevRaw)
      const nextPayload = buildWorkflowPersistPayload(prevPayload?.meta)
      window.localStorage.setItem(workflowDraftStorageKey, JSON.stringify(nextPayload))
    } catch {
      // ignore draft save failures
    }
  }, [
    workflowWorkspace,
    workflowDraftStorageKey,
    workflowCanvasNodes,
    workflowCanvasEdges,
    workflowViewport,
    workflowEditTarget,
    buildWorkflowPersistPayload,
    parseWorkflowPersistText,
  ])

  const workflowNodePositionMap = useMemo(() => {
    const map: Record<string, { x: number; y: number }> = {}
    for (const node of workflowCanvasNodes) {
      map[node.id] = { x: node.x, y: node.y }
    }
    return map
  }, [workflowCanvasNodes])

  const selectedWorkflowNode = useMemo(() => {
    if (!workflowEditTarget || workflowEditTarget.type !== 'node') return null
    return workflowCanvasNodes.find((node) => node.id === workflowEditTarget.id) ?? null
  }, [workflowEditTarget, workflowCanvasNodes])

  const selectedWorkflowNodeId = selectedWorkflowNode?.id ?? ''

  const selectedWorkflowUpstreamNodes = useMemo(() => {
    if (!selectedWorkflowNodeId) return []
    return workflowCanvasEdges
      .filter((edge) => edge.toNodeId === selectedWorkflowNodeId)
      .map((edge) => workflowCanvasNodes.find((node) => node.id === edge.fromNodeId))
      .filter((node): node is WorkflowCanvasNode => !!node)
  }, [selectedWorkflowNodeId, workflowCanvasEdges, workflowCanvasNodes])

  const selectedWorkflowDownstreamNodes = useMemo(() => {
    if (!selectedWorkflowNodeId) return []
    return workflowCanvasEdges
      .filter((edge) => edge.fromNodeId === selectedWorkflowNodeId)
      .map((edge) => workflowCanvasNodes.find((node) => node.id === edge.toNodeId))
      .filter((node): node is WorkflowCanvasNode => !!node)
  }, [selectedWorkflowNodeId, workflowCanvasEdges, workflowCanvasNodes])

  const workflowSelectableNeighborNodes = useMemo(() => {
    if (!selectedWorkflowNodeId) return []
    return workflowCanvasNodes.filter((node) => node.id !== selectedWorkflowNodeId)
  }, [selectedWorkflowNodeId, workflowCanvasNodes])

  const selectedAssistantNodeDetails = useMemo(() => {
    if (!selectedWorkflowNode || selectedWorkflowNode.type !== 'assistant') return null
    const sourceMemberId = selectedWorkflowNode.sourceMemberId || ''
    const assistantId = sourceMemberId.startsWith('assistant-')
      ? sourceMemberId.slice('assistant-'.length)
      : ''
    const assistantInConversation = assistantId
      ? (conversation?.assistants ?? []).find((item) => item.id === assistantId)
      : null
    const resolvedAssistantConfig = assistantId
      ? resolveGroupAssistantConfig(
          assistantId,
          assistantInConversation?.name ?? selectedWorkflowNode.name,
          conversation?.assistantConfigs
        )
      : undefined
    const assistantConfigFromGroup = assistantId ? conversation?.assistantConfigs?.[assistantId] : undefined
    const creatorImei = String(
      assistantConfigFromGroup?.creator_imei
      || (resolvedAssistantConfig as { creator_imei?: string } | undefined)?.creator_imei
      || ''
    ).trim()
    const creatorNickname = String(
      assistantConfigFromGroup?.creator_nickname
      || (resolvedAssistantConfig as { creator_nickname?: string } | undefined)?.creator_nickname
      || ''
    ).trim()
    const creatorDisplay = creatorNickname && creatorImei
      ? `${creatorNickname}(${creatorImei})`
      : (creatorNickname || creatorImei || '')
    const assistantBaseName = groupAssistantDisplayNameMap[assistantId]
      || assistantInConversation?.name
      || assistantId
      || selectedWorkflowNode.name
    return {
      assistantId,
      assistantDisplayName: creatorDisplay ? `${assistantBaseName}（${creatorDisplay}）` : assistantBaseName,
      assistantServiceUrl: String(
        assistantConfigFromGroup?.baseUrl
        || resolvedAssistantConfig?.baseUrl
        || ''
      ).trim(),
      rolePrompt: String(assistantConfigFromGroup?.rolePrompt || '').trim(),
      nodeTaskIntro: selectedWorkflowNode.nodeTaskIntro || '',
    }
  }, [selectedWorkflowNode, conversation?.assistants, conversation?.assistantConfigs, groupAssistantDisplayNameMap])

  const selectedUserNodeDetails = useMemo(() => {
    if (!selectedWorkflowNode || selectedWorkflowNode.type !== 'user') return null
    const sourceMemberId = selectedWorkflowNode.sourceMemberId || ''
    const userImei = sourceMemberId.startsWith('member-')
      ? sourceMemberId.slice('member-'.length)
      : ''
    const matchedFriend = groupFriends.find((item) => item.imei === userImei)
      || profileFriends.find((item) => item.imei === userImei)
    const userNickname = matchedFriend?.nickname || selectedWorkflowNode.name || '-'
    return {
      userNickname,
      userImei: userImei || '-',
      userRoleIntro: selectedWorkflowNode.userRoleIntro || '',
      nodeTaskIntro: selectedWorkflowNode.nodeTaskIntro || '',
    }
  }, [selectedWorkflowNode, groupFriends, profileFriends])

  const workflowDecisionAssigneeOptions = useMemo<WorkflowDecisionAssigneeOption[]>(() => {
    const memberOptions = workflowWorkspaceMembers.map((member) => ({
      id: member.id,
      label: member.name,
      kind: member.type === 'assistant' ? 'assistant' as const : 'user' as const,
    }))
    return [
      { id: 'code', label: '代码', kind: 'code' },
      ...memberOptions,
    ]
  }, [workflowWorkspaceMembers])

  const selectedDecisionNodeDetails = useMemo(() => {
    if (!selectedWorkflowNode || selectedWorkflowNode.type !== 'decision') return null
    const resolvedAssigneeId = String(selectedWorkflowNode.decisionAssigneeId || '').trim() || 'code'
    const assignee = workflowDecisionAssigneeOptions.find((item) => item.id === resolvedAssigneeId)
      ?? workflowDecisionAssigneeOptions[0]
      ?? { id: 'code', label: '代码', kind: 'code' as const }
    const assigneeRawId = assignee.id.startsWith('assistant-')
      ? assignee.id.slice('assistant-'.length)
      : assignee.id.startsWith('member-')
        ? assignee.id.slice('member-'.length)
        : assignee.id
    const isCode = assignee.kind === 'code'
    return {
      assigneeId: assignee.id,
      assigneeRawId,
      isCode,
      nodeTaskIntro: selectedWorkflowNode.nodeTaskIntro || '',
      nodeResponsibility: selectedWorkflowNode.decisionResponsibility || '',
      codeExample: selectedWorkflowNode.decisionCodeExample || '',
    }
  }, [selectedWorkflowNode, workflowDecisionAssigneeOptions])

  const workflowFriendsForAdd = useMemo(
    () => profileFriends.filter((friend) => friend.status === 'accepted'),
    [profileFriends]
  )

  const workflowAssistantsForAdd = useMemo(() => {
    const builtin = [
      { id: CONVERSATION_ID_ASSISTANT, name: '自动执行小助手' },
      { id: 'skill_learning', name: '技能学习小助手' },
      { id: 'customer_service', name: '人工客服' },
    ]
    const custom = getVisibleCustomAssistants().map((assistant) => ({ id: assistant.id, name: assistant.name }))
    return [...builtin, ...custom].filter((assistant) => assistant.id !== CONVERSATION_ID_IM_QQ && assistant.id !== CONVERSATION_ID_IM_WEIXIN)
  }, [])

  const workflowGroupRawId = normalizeGroupRawId(workflowWorkspace?.groupId || conversation?.id)
  const workflowGroupFromCache = workflowGroupRawId ? groupInfoCache[workflowGroupRawId] : undefined
  const workflowGroupOwnerImei = String(
    workflowGroupFromCache?.creator_imei
    || ((conversation as { creator_imei?: string } | null)?.creator_imei || '')
  ).trim()
  const workflowCanAddMembers = !!workflowWorkspace && !!imei && !!workflowGroupOwnerImei && workflowGroupOwnerImei === imei

  const buildWorkflowMembersFromGroupInfo = useCallback((group: GroupInfo): WorkflowWorkspacePayload['members'] => {
    const assistantIds = group.assistants ?? (group.assistant_enabled ? ['assistant'] : [])
    const assistantMembers = assistantIds.map((assistantId) => {
      const nameFromGroup = String(group.assistant_configs?.[assistantId]?.name || '').trim()
      const nameFromConversation = (conversation?.assistants ?? []).find((item) => item.id === assistantId)?.name
      const nameFromProfile = workflowAssistantsForAdd.find((item) => item.id === assistantId)?.name
      return {
        id: `assistant-${assistantId}`,
        name: nameFromGroup || nameFromConversation || nameFromProfile || assistantId,
        type: 'assistant' as const,
      }
    })
    const selfImei = (imei || '').trim()
    const userMembers = (group.members ?? []).map((memberImei) => {
      const f = workflowFriendsForAdd.find((item) => item.imei === memberImei)
      const displayName = f?.nickname ?? (memberImei === selfImei ? (userName || '我') : `${memberImei.slice(0, 8)}...`)
      return {
        id: `member-${memberImei}`,
        name: displayName,
        type: 'user' as const,
      }
    })
    return [...assistantMembers, ...userMembers]
  }, [conversation?.assistants, workflowAssistantsForAdd, workflowFriendsForAdd, imei, userName])

  const workflowExistingMemberIds = useMemo(() => new Set((workflowWorkspace?.members ?? []).map((item) => item.id)), [workflowWorkspace?.members])

  const workflowAddableFriends = useMemo(
    () => workflowFriendsForAdd.filter((friend) => !workflowExistingMemberIds.has(`member-${friend.imei}`)),
    [workflowFriendsForAdd, workflowExistingMemberIds]
  )
  const workflowAddableAssistants = useMemo(
    () => workflowAssistantsForAdd.filter((assistant) => !workflowExistingMemberIds.has(`assistant-${assistant.id}`)),
    [workflowAssistantsForAdd, workflowExistingMemberIds]
  )

  useEffect(() => {
    setWorkflowUpstreamDraftNodeId('')
    setWorkflowDownstreamDraftNodeId('')
    setAssistantRolePromptSaveHint('')
  }, [selectedWorkflowNodeId])

  useEffect(() => {
    if (!selectedAssistantNodeDetails) {
      setAssistantRolePromptDraft('')
      return
    }
    setAssistantRolePromptDraft(selectedAssistantNodeDetails.rolePrompt || '')
  }, [selectedAssistantNodeDetails?.assistantId, selectedAssistantNodeDetails?.rolePrompt])

  const getWorkflowNodeAnchor = useCallback((
    nodeId: string,
    side: 'left' | 'right'
  ): { x: number; y: number } | null => {
    const node = workflowNodePositionMap[nodeId]
    if (!node) return null
    return {
      x: node.x + (side === 'right' ? WORKFLOW_NODE_WIDTH : 0),
      y: node.y + WORKFLOW_NODE_HEIGHT / 2,
    }
  }, [workflowNodePositionMap])

  const handleWorkflowMemberDragStart = useCallback((event: React.DragEvent<HTMLDivElement>, member: WorkflowMemberItem) => {
    event.dataTransfer.setData('application/topoclaw-workflow-member', JSON.stringify(member))
    event.dataTransfer.effectAllowed = 'copy'
  }, [])

  const handleWorkflowMemberClick = useCallback((member: WorkflowMemberItem) => {
    if (member.type === 'assistant') {
      const rawAssistantId = member.id.startsWith('assistant-')
        ? member.id.slice('assistant-'.length)
        : member.id
      const assistantId = rawAssistantId === 'assistant' ? CONVERSATION_ID_ASSISTANT : rawAssistantId
      const fromGroupConfig = conversation?.assistantConfigs?.[rawAssistantId]
      const resolvedConfig = resolveGroupAssistantConfig(rawAssistantId, member.name, conversation?.assistantConfigs)
      const custom = getCustomAssistantById(assistantId) ?? getCustomAssistantById(rawAssistantId)
      const avatar = custom?.avatar
        || (assistantId === CONVERSATION_ID_ASSISTANT
          ? ASSISTANT_AVATAR
          : assistantId === 'skill_learning'
            ? SKILL_LEARNING_AVATAR
            : assistantId === 'customer_service'
              ? CUSTOMER_SERVICE_AVATAR
              : undefined)
      setSessionSidebarOpen(false)
      setProfileSidebarTarget({
        type: 'assistant',
        id: assistantId,
        name: member.name,
        avatar,
        baseUrl: String(fromGroupConfig?.baseUrl || resolvedConfig?.baseUrl || custom?.baseUrl || '').trim() || undefined,
        creator_imei: String(
          fromGroupConfig?.creator_imei
          || (resolvedConfig as { creator_imei?: string } | undefined)?.creator_imei
          || ''
        ).trim() || undefined,
        displayId: String(fromGroupConfig?.displayId || resolvedConfig?.displayId || '').trim() || undefined,
        disableLocalCreatorFallback: true,
      })
      return
    }
    const userImei = member.id.startsWith('member-') ? member.id.slice('member-'.length) : member.id
    const matched = profileFriends.find((friend) => friend.imei === userImei)
    setSessionSidebarOpen(false)
    setProfileSidebarTarget({
      type: 'friend',
      data: matched ?? {
        imei: userImei,
        nickname: member.name,
        status: 'accepted',
        addedAt: Date.now(),
      },
    })
  }, [conversation?.assistantConfigs, profileFriends])

  const handleWorkflowNodeDragMove = useCallback((event: MouseEvent) => {
    const dragging = workflowNodeDragRef.current
    if (!dragging) return
    const point = workflowClientToWorld(event.clientX, event.clientY)
    if (!point) return
    const maxX = Math.max(0, WORKFLOW_STAGE_WIDTH - WORKFLOW_NODE_WIDTH)
    const maxY = Math.max(0, WORKFLOW_STAGE_HEIGHT - WORKFLOW_NODE_HEIGHT)
    const x = snapWorkflowPosition(point.x - dragging.offsetX, maxX)
    const y = snapWorkflowPosition(point.y - dragging.offsetY, maxY)
    setWorkflowCanvasNodes((prev) => prev.map((node) => (node.id === dragging.nodeId ? { ...node, x, y } : node)))
  }, [workflowClientToWorld])

  const handleWorkflowNodeDragEnd = useCallback(() => {
    workflowNodeDragRef.current = null
    window.removeEventListener('mousemove', handleWorkflowNodeDragMove)
    window.removeEventListener('mouseup', handleWorkflowNodeDragEnd)
  }, [handleWorkflowNodeDragMove])

  const handleWorkflowNodeMouseDown = useCallback((
    event: React.MouseEvent<HTMLDivElement>,
    nodeId: string
  ) => {
    if (event.button !== 0) return
    if (workflowConnecting) return
    const point = workflowClientToWorld(event.clientX, event.clientY)
    const node = workflowNodePositionMap[nodeId]
    if (!point || !node) return
    workflowNodeDragRef.current = {
      nodeId,
      offsetX: point.x - node.x,
      offsetY: point.y - node.y,
    }
    window.addEventListener('mousemove', handleWorkflowNodeDragMove)
    window.addEventListener('mouseup', handleWorkflowNodeDragEnd)
    setWorkflowSelectedTarget({ type: 'node', id: nodeId })
    setWorkflowEditTarget(null)
    setWorkflowContextMenu(null)
    event.preventDefault()
  }, [handleWorkflowNodeDragMove, handleWorkflowNodeDragEnd, workflowConnecting, workflowClientToWorld, workflowNodePositionMap])

  const handleWorkflowNodeContextMenu = useCallback((event: React.MouseEvent<HTMLDivElement>, nodeId: string) => {
    event.preventDefault()
    event.stopPropagation()
    setWorkflowSelectedTarget({ type: 'node', id: nodeId })
    setWorkflowEditTarget({ type: 'node', id: nodeId })
    setWorkflowContextMenu({ x: event.clientX, y: event.clientY, target: 'node', id: nodeId })
  }, [])

  const handleWorkflowEdgeContextMenu = useCallback((event: React.MouseEvent<SVGPathElement>, edgeId: string) => {
    event.preventDefault()
    event.stopPropagation()
    setWorkflowSelectedTarget({ type: 'edge', id: edgeId })
    setWorkflowEditTarget({ type: 'edge', id: edgeId })
    setWorkflowContextMenu({ x: event.clientX, y: event.clientY, target: 'edge', id: edgeId })
  }, [])

  const handleWorkflowEdgeStart = useCallback((event: React.MouseEvent<HTMLButtonElement>, fromNodeId: string) => {
    event.preventDefault()
    event.stopPropagation()
    const fromAnchor = getWorkflowNodeAnchor(fromNodeId, 'right')
    if (!fromAnchor) return
    setWorkflowEditTarget(null)
    setWorkflowConnecting({ fromNodeId, toX: fromAnchor.x, toY: fromAnchor.y })
  }, [getWorkflowNodeAnchor])

  const handleWorkflowNodeMouseUp = useCallback((nodeId: string) => {
    setWorkflowConnecting((prev) => {
      if (!prev) return prev
      if (prev.fromNodeId === nodeId) return null
      setWorkflowCanvasEdges((edges) => {
        if (edges.some((edge) => edge.fromNodeId === prev.fromNodeId && edge.toNodeId === nodeId)) {
          return edges
        }
        return [
          ...edges,
          {
            id: `workflow-edge-${prev.fromNodeId}-${nodeId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
            fromNodeId: prev.fromNodeId,
            toNodeId: nodeId,
          },
        ]
      })
      return null
    })
  }, [])

  const deleteWorkflowTarget = useCallback((target: Exclude<WorkflowEditTarget, null>) => {
    if (target.type === 'node') {
      const nodeId = target.id
      setWorkflowCanvasNodes((prev) => prev.filter((node) => node.id !== nodeId))
      setWorkflowCanvasEdges((prev) => prev.filter((edge) => edge.fromNodeId !== nodeId && edge.toNodeId !== nodeId))
      setWorkflowSelectedTarget((prev) => (prev && prev.type === 'node' && prev.id === nodeId ? null : prev))
      setWorkflowEditTarget(null)
      return
    }
    const edgeId = target.id
    setWorkflowCanvasEdges((prev) => prev.filter((edge) => edge.id !== edgeId))
    setWorkflowSelectedTarget((prev) => (prev && prev.type === 'edge' && prev.id === edgeId ? null : prev))
    setWorkflowEditTarget(null)
  }, [])

  const handleWorkflowDeleteEditTarget = useCallback(() => {
    const target = workflowEditTarget || workflowSelectedTarget
    if (!target) return
    deleteWorkflowTarget(target)
  }, [workflowEditTarget, workflowSelectedTarget, deleteWorkflowTarget])

  const handleWorkflowContextMenuEdit = useCallback(() => {
    if (!workflowContextMenu) return
    setWorkflowEditTarget({ type: workflowContextMenu.target, id: workflowContextMenu.id })
    setWorkflowContextMenu(null)
  }, [workflowContextMenu])

  const handleWorkflowContextMenuDelete = useCallback(() => {
    if (!workflowContextMenu) return
    deleteWorkflowTarget({ type: workflowContextMenu.target, id: workflowContextMenu.id })
    setWorkflowContextMenu(null)
  }, [workflowContextMenu, deleteWorkflowTarget])

  const handleWorkflowNodeNameChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, name: value }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const handleWorkflowNodeTaskIntroChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, nodeTaskIntro: value }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const handleWorkflowUserRoleIntroChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, userRoleIntro: value }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const handleWorkflowDecisionAssigneeChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, decisionAssigneeId: value || 'code' }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const handleWorkflowDecisionResponsibilityChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, decisionResponsibility: value }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const handleWorkflowDecisionCodeExampleChange = useCallback((value: string) => {
    if (!selectedWorkflowNodeId) return
    setWorkflowCanvasNodes((prev) => prev.map((node) => (
      node.id === selectedWorkflowNodeId
        ? { ...node, decisionCodeExample: value }
        : node
    )))
  }, [selectedWorkflowNodeId])

  const addWorkflowEdge = useCallback((fromNodeId: string, toNodeId: string) => {
    if (!fromNodeId || !toNodeId || fromNodeId === toNodeId) return
    setWorkflowCanvasEdges((prev) => {
      if (prev.some((edge) => edge.fromNodeId === fromNodeId && edge.toNodeId === toNodeId)) return prev
      return [
        ...prev,
        {
          id: `workflow-edge-${fromNodeId}-${toNodeId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          fromNodeId,
          toNodeId,
        },
      ]
    })
  }, [])

  const removeWorkflowEdge = useCallback((fromNodeId: string, toNodeId: string) => {
    setWorkflowCanvasEdges((prev) => prev.filter((edge) => !(edge.fromNodeId === fromNodeId && edge.toNodeId === toNodeId)))
  }, [])

  const handleAddWorkflowUpstream = useCallback(() => {
    if (!selectedWorkflowNodeId || !workflowUpstreamDraftNodeId) return
    addWorkflowEdge(workflowUpstreamDraftNodeId, selectedWorkflowNodeId)
    setWorkflowUpstreamDraftNodeId('')
  }, [selectedWorkflowNodeId, workflowUpstreamDraftNodeId, addWorkflowEdge])

  const handleAddWorkflowDownstream = useCallback(() => {
    if (!selectedWorkflowNodeId || !workflowDownstreamDraftNodeId) return
    addWorkflowEdge(selectedWorkflowNodeId, workflowDownstreamDraftNodeId)
    setWorkflowDownstreamDraftNodeId('')
  }, [selectedWorkflowNodeId, workflowDownstreamDraftNodeId, addWorkflowEdge])

  const handleInsertWorkflowDecisionNode = useCallback(() => {
    const canvasRect = workflowCanvasRef.current?.getBoundingClientRect()
    let worldCenterX = WORKFLOW_STAGE_WIDTH / 2
    let worldCenterY = WORKFLOW_STAGE_HEIGHT / 2
    if (canvasRect) {
      worldCenterX = (canvasRect.width / 2 - workflowViewport.offsetX) / workflowViewport.scale
      worldCenterY = (canvasRect.height / 2 - workflowViewport.offsetY) / workflowViewport.scale
    }
    const maxX = Math.max(0, WORKFLOW_STAGE_WIDTH - WORKFLOW_NODE_WIDTH)
    const maxY = Math.max(0, WORKFLOW_STAGE_HEIGHT - WORKFLOW_NODE_HEIGHT)
    const x = snapWorkflowPosition(worldCenterX - WORKFLOW_NODE_WIDTH / 2, maxX)
    const y = snapWorkflowPosition(worldCenterY - WORKFLOW_NODE_HEIGHT / 2, maxY)
    setWorkflowCanvasNodes((prev) => {
      const decisionCount = prev.filter((node) => node.type === 'decision').length + 1
      return [
        ...prev,
        {
          id: `workflow-node-decision-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          sourceMemberId: 'decision-rule',
          name: `判断实例${decisionCount}`,
          type: 'decision',
          nodeTaskIntro: '',
          decisionAssigneeId: 'code',
          decisionResponsibility: '',
          decisionCodeExample: 'response = input_data.get("response", "")\nif response == "xx":\n  return 1\nreturn 2',
          x,
          y,
        },
      ]
    })
    setWorkflowInsertMenuOpen(false)
  }, [workflowViewport.offsetX, workflowViewport.offsetY, workflowViewport.scale])

  const handleWorkflowNewWorkspace = useCallback(() => {
    const hasContent = workflowCanvasNodes.length > 0 || workflowCanvasEdges.length > 0
    if (hasContent) {
      const ok = window.confirm('确认新建编排区？当前编排内容将被清空。')
      if (!ok) return
    }
    setWorkflowCanvasNodes([])
    setWorkflowCanvasEdges([])
    setWorkflowConnecting(null)
    setWorkflowSelectedTarget(null)
    setWorkflowEditTarget(null)
    setWorkflowUpstreamDraftNodeId('')
    setWorkflowDownstreamDraftNodeId('')
    setWorkflowCloudVersion(0)
    setWorkflowStartMenuOpen(false)
    window.requestAnimationFrame(() => {
      resetWorkflowViewportToCenter()
    })
    showInlineToastNotice('已新建空白编排区')
  }, [workflowCanvasNodes.length, workflowCanvasEdges.length, resetWorkflowViewportToCenter, showInlineToastNotice])

  const handleWorkflowSaveWorkspace = useCallback(async () => {
    if (!workflowWorkspace) return
    try {
      const existingRaw = workflowDraftStorageKey ? window.localStorage.getItem(workflowDraftStorageKey) || '' : ''
      const existingPayload = parseWorkflowPersistText(existingRaw)
      const payload = buildWorkflowPersistPayload(existingPayload?.meta)
      const myImei = (imei || '').trim()
      const serverGroupId = toServerGroupId(workflowWorkspace.groupId || conversation?.id || '')
      if (myImei && serverGroupId) {
        let cloudRes = await saveGroupWorkflow(
          myImei,
          serverGroupId,
          payload,
          workflowCloudVersion
        )
        if (cloudRes.conflict) {
          const force = window.confirm('云端编排已被其他成员更新，是否覆盖为当前内容？')
          if (!force) {
            showInlineToastNotice('已取消云端保存，当前内容仍保留在本地草稿')
            setWorkflowStartMenuOpen(false)
            return
          }
          cloudRes = await saveGroupWorkflow(
            myImei,
            serverGroupId,
            payload,
            cloudRes.currentVersion ?? null
          )
        }
        if (cloudRes.success) {
          setWorkflowCloudVersion(Number(cloudRes.version ?? 0))
        } else {
          showInlineToastNotice(cloudRes.message || '云端保存失败')
          setWorkflowStartMenuOpen(false)
          return
        }
      }
      const text = `${JSON.stringify(payload, null, 2)}\n`
      const groupSlug = (normalizeGroupRawId(payload.meta.groupId || payload.meta.groupName) || 'group').replace(/[^a-zA-Z0-9_-]+/g, '_')
      const defaultName = `${groupSlug}-${payload.meta.workflowId}${WORKFLOW_FILE_SUFFIX}`
      const api = window.electronAPI
      if (api?.saveTextAs) {
        const saveRes = await api.saveTextAs(text, defaultName)
        if (saveRes?.ok) {
          showInlineToastNotice('编排已保存')
        }
        setWorkflowStartMenuOpen(false)
        return
      }
      triggerBlobDownload(defaultName, new Blob([text], { type: 'application/json;charset=utf-8' }))
      showInlineToastNotice('编排已导出')
      setWorkflowStartMenuOpen(false)
    } catch {
      showInlineToastNotice('保存失败，请稍后重试')
    }
  }, [
    workflowWorkspace,
    workflowDraftStorageKey,
    parseWorkflowPersistText,
    buildWorkflowPersistPayload,
    showInlineToastNotice,
    imei,
    conversation?.id,
    workflowCloudVersion,
  ])

  const handleWorkflowPickLoadFile = useCallback(() => {
    setWorkflowStartMenuOpen(false)
    workflowLoadInputRef.current?.click()
  }, [])

  const handleWorkflowLoadFileChange = useCallback(async (event: React.ChangeEvent<HTMLInputElement>) => {
    const input = event.currentTarget
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    try {
      const text = await file.text()
      const payload = parseWorkflowPersistText(text)
      if (!payload) {
        showInlineToastNotice('载入失败：文件格式无效')
        return
      }
      applyWorkflowPersistPayload(payload)
      setWorkflowCloudVersion(0)
      showInlineToastNotice(`已载入编排：${payload.graph.nodes.length} 节点 / ${payload.graph.edges.length} 连线`)
    } catch {
      showInlineToastNotice('载入失败：无法读取文件')
    }
  }, [parseWorkflowPersistText, applyWorkflowPersistPayload, showInlineToastNotice])

  const appendWorkflowRunLog = useCallback((level: WorkflowRunLogItem['level'], text: string) => {
    const item: WorkflowRunLogItem = {
      id: `wf-run-log-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      ts: Date.now(),
      level,
      text,
    }
    setWorkflowRunLogs((prev) => [...prev, item])
  }, [])

  const handleWorkflowSimulateRun = useCallback(async () => {
    const nodes = [...workflowCanvasNodes]
    const edges = [...workflowCanvasEdges]
    if (nodes.length === 0) {
      showInlineToastNotice('当前没有可执行节点')
      return
    }

    const nodeMap = new Map(nodes.map((node) => [node.id, node]))
    const indegree: Record<string, number> = {}
    const downstream: Record<string, string[]> = {}
    const upstream: Record<string, string[]> = {}
    for (const node of nodes) {
      indegree[node.id] = 0
      downstream[node.id] = []
      upstream[node.id] = []
    }
    for (const edge of edges) {
      if (!nodeMap.has(edge.fromNodeId) || !nodeMap.has(edge.toNodeId)) continue
      indegree[edge.toNodeId] += 1
      downstream[edge.fromNodeId].push(edge.toNodeId)
      upstream[edge.toNodeId].push(edge.fromNodeId)
    }

    // 先做一次环检测：必须是 DAG 才能模拟执行。
    {
      const tmpIn = { ...indegree }
      const queue = nodes.filter((node) => tmpIn[node.id] === 0).map((node) => node.id)
      let visited = 0
      while (queue.length > 0) {
        const cur = queue.shift()!
        visited += 1
        for (const nxt of downstream[cur]) {
          tmpIn[nxt] -= 1
          if (tmpIn[nxt] === 0) queue.push(nxt)
        }
      }
      if (visited !== nodes.length) {
        showInlineToastNotice('模拟运行失败：检测到环路，请先调整连线')
        return
      }
    }

    const runToken = Date.now()
    workflowRunTokenRef.current = runToken
    setWorkflowRunInProgress(true)
    setWorkflowRunLogOpen(true)
    setWorkflowRunLogs([])
    setWorkflowRunNodeStatusMap(
      nodes.reduce<Record<string, WorkflowRunNodeStatus>>((acc, node) => {
        acc[node.id] = 'idle'
        return acc
      }, {})
    )
    appendWorkflowRunLog('info', `开始模拟执行：${nodes.length} 个节点，${edges.length} 条连线`)

    const wait = (ms: number) => new Promise<void>((resolve) => {
      window.setTimeout(() => resolve(), ms)
    })

    let ready = nodes.filter((node) => indegree[node.id] === 0).map((node) => node.id)
    let failed = false
    const finished = new Set<string>()
    const outputByNodeId: Record<string, string> = {}
    const buildSimulatedInput = (nodeId: string): string => {
      const deps = upstream[nodeId] || []
      const depOutputs = deps
        .map((depId) => outputByNodeId[depId])
        .filter((text): text is string => !!text)
      if (depOutputs.length === 0) {
        return '模拟执行，请回复执行成功'
      }
      return `上游输出：${depOutputs.join('；')}\n模拟执行，请回复执行成功`
    }
    while (ready.length > 0) {
      const levelNodeIds = [...ready]
      ready = []
      if (workflowRunTokenRef.current !== runToken) return
      setWorkflowRunNodeStatusMap((prev) => {
        const next = { ...prev }
        for (const nodeId of levelNodeIds) next[nodeId] = 'running'
        return next
      })
      for (const nodeId of levelNodeIds) {
        const n = nodeMap.get(nodeId)
        appendWorkflowRunLog('info', `节点「${n?.name || nodeId}」开始执行`)
      }

      const levelResults = await Promise.all(levelNodeIds.map(async (nodeId) => {
        const node = nodeMap.get(nodeId)
        const inputText = buildSimulatedInput(nodeId)
        appendWorkflowRunLog('info', `节点「${node?.name || nodeId}」输入：${inputText.replace(/\n/g, ' ')}`)
        await wait(450 + Math.floor(Math.random() * 550))
        if (!node) return { nodeId, ok: false, reason: '节点不存在', output: '' }
        if (node.type !== 'assistant') {
          return { nodeId, ok: false, reason: '当前模拟执行仅支持助手实例节点', output: '' }
        }
        return { nodeId, ok: true, reason: '', output: '执行成功' }
      }))

      if (workflowRunTokenRef.current !== runToken) return

      for (const result of levelResults) {
        const node = nodeMap.get(result.nodeId)
        finished.add(result.nodeId)
        if (result.ok) {
          outputByNodeId[result.nodeId] = result.output
          setWorkflowRunNodeStatusMap((prev) => ({ ...prev, [result.nodeId]: 'success' }))
          appendWorkflowRunLog('success', `节点「${node?.name || result.nodeId}」输出：${result.output}`)
          appendWorkflowRunLog('success', `节点「${node?.name || result.nodeId}」执行完成`)
          for (const nxt of downstream[result.nodeId]) {
            indegree[nxt] -= 1
            if (indegree[nxt] === 0) ready.push(nxt)
          }
        } else {
          failed = true
          setWorkflowRunNodeStatusMap((prev) => ({ ...prev, [result.nodeId]: 'failed' }))
          appendWorkflowRunLog('error', `节点「${node?.name || result.nodeId}」执行失败：${result.reason}`)
        }
      }
      if (failed) break
    }

    if (workflowRunTokenRef.current !== runToken) return

    if (failed) {
      const skipped = nodes.filter((node) => !finished.has(node.id)).map((node) => node.name)
      if (skipped.length > 0) {
        appendWorkflowRunLog('info', `未执行节点：${skipped.join('、')}`)
      }
      appendWorkflowRunLog('error', '模拟执行结束：存在失败节点')
      showInlineToastNotice('模拟执行失败，请查看执行日志')
    } else {
      appendWorkflowRunLog('success', '模拟执行完成：全部节点成功')
      showInlineToastNotice('模拟执行完成')
    }
    setWorkflowRunInProgress(false)
  }, [workflowCanvasNodes, workflowCanvasEdges, appendWorkflowRunLog, showInlineToastNotice])

  const handleWorkflowExecuteRun = useCallback(async (override?: { nodes?: WorkflowCanvasNode[]; edges?: WorkflowCanvasEdge[] }) => {
    if (workflowRunInProgress) return
    const nodes = [...(override?.nodes ?? workflowCanvasNodes)]
    const edges = [...(override?.edges ?? workflowCanvasEdges)]
    if (nodes.length === 0) {
      showInlineToastNotice('当前没有可执行节点')
      return
    }
    const normalizedTaskInput = String(input || '').trim() || '请基于当前流程开始执行任务'

    const nodeMap = new Map(nodes.map((node) => [node.id, node]))
    const indegree: Record<string, number> = {}
    const downstream: Record<string, string[]> = {}
    const upstream: Record<string, string[]> = {}
    for (const node of nodes) {
      indegree[node.id] = 0
      downstream[node.id] = []
      upstream[node.id] = []
    }
    for (const edge of edges) {
      if (!nodeMap.has(edge.fromNodeId) || !nodeMap.has(edge.toNodeId)) continue
      indegree[edge.toNodeId] += 1
      downstream[edge.fromNodeId].push(edge.toNodeId)
      upstream[edge.toNodeId].push(edge.fromNodeId)
    }

    const runToken = Date.now()
    workflowRunTokenRef.current = runToken
    setWorkflowRunInProgress(true)
    setWorkflowRunLogOpen(true)
    setWorkflowRunLogs([])
    setWorkflowRunNodeStatusMap(
      nodes.reduce<Record<string, WorkflowRunNodeStatus>>((acc, node) => {
        acc[node.id] = 'idle'
        return acc
      }, {})
    )
    appendWorkflowRunLog('info', `开始真实执行：${nodes.length} 个节点，${edges.length} 条连线`)
    appendWorkflowRunLog('info', 'execution_mode: live')
    appendWorkflowRunLog('info', `执行任务：${normalizedTaskInput}`)

    {
      const tmpIn = { ...indegree }
      const queue = nodes.filter((node) => tmpIn[node.id] === 0).map((node) => node.id)
      let visited = 0
      while (queue.length > 0) {
        const cur = queue.shift()!
        visited += 1
        for (const nxt of downstream[cur]) {
          tmpIn[nxt] -= 1
          if (tmpIn[nxt] === 0) queue.push(nxt)
        }
      }
      if (visited !== nodes.length) {
        appendWorkflowRunLog('error', '真实执行失败：检测到环路，请先调整连线')
        setWorkflowRunInProgress(false)
        showInlineToastNotice('执行失败：检测到环路，请先调整连线')
        return
      }
    }

    let ready = nodes.filter((node) => indegree[node.id] === 0).map((node) => node.id)
    let failed = false
    const finished = new Set<string>()
    const outputByNodeId: Record<string, string> = {}

    const buildNodeInput = (nodeId: string): string => {
      const deps = upstream[nodeId] || []
      const depOutputs = deps
        .map((depId) => outputByNodeId[depId])
        .filter((text): text is string => !!text)
      if (depOutputs.length === 0) return normalizedTaskInput
      return `全局任务：${normalizedTaskInput}\n上游输出：${depOutputs.join('；')}`
    }

    while (ready.length > 0) {
      const levelNodeIds = [...ready]
      ready = []
      if (workflowRunTokenRef.current !== runToken) return
      setWorkflowRunNodeStatusMap((prev) => {
        const next = { ...prev }
        for (const nodeId of levelNodeIds) next[nodeId] = 'running'
        return next
      })

      for (const nodeId of levelNodeIds) {
        const n = nodeMap.get(nodeId)
        appendWorkflowRunLog('info', `节点「${n?.name || nodeId}」开始执行`)
      }

      const levelResults = await Promise.all(levelNodeIds.map(async (nodeId) => {
        const node = nodeMap.get(nodeId)
        if (!node) return { nodeId, ok: false, reason: '节点不存在', output: '' }
        if (node.type !== 'assistant') {
          return { nodeId, ok: false, reason: '真实执行当前仅支持助手实例节点', output: '' }
        }
        const sourceMemberId = String(node.sourceMemberId || '').trim()
        const assistantId = sourceMemberId.startsWith('assistant-') ? sourceMemberId.slice('assistant-'.length) : ''
        if (!assistantId) return { nodeId, ok: false, reason: '节点未绑定助手', output: '' }
        const assistantInConversation = (conversation?.assistants ?? []).find((item) => item.id === assistantId)
        const resolvedConfig = resolveGroupAssistantConfig(
          assistantId,
          assistantInConversation?.name ?? node.name,
          conversation?.assistantConfigs
        )
        const baseUrl = String(
          conversation?.assistantConfigs?.[assistantId]?.baseUrl
          || resolvedConfig?.baseUrl
          || ''
        ).trim()
        if (!baseUrl) {
          return { nodeId, ok: false, reason: `未找到助手服务地址（assistantId=${assistantId}）`, output: '' }
        }
        const rolePrompt = String(conversation?.assistantConfigs?.[assistantId]?.rolePrompt || '').trim()
        const nodeTaskIntro = String(node.nodeTaskIntro || '').trim()
        const plainInput = buildNodeInput(nodeId)
        const inputSections: string[] = []
        if (nodeTaskIntro) inputSections.push(`【当前节点任务】\n${nodeTaskIntro}`)
        if (rolePrompt) inputSections.push(`【节点角色】\n${rolePrompt}`)
        inputSections.push(`【执行输入】\n${plainInput}`)
        const fullInput = inputSections.join('\n\n')
        appendWorkflowRunLog('info', `节点「${node.name}」输入：${fullInput.replace(/\n/g, ' ')}`)
        try {
          const res = await sendChatAssistantMessage(
            {
              uuid: `workflow_live_${Date.now()}_${nodeId}`,
              query: fullInput,
              images: [],
              imei: (imei || 'workflow').trim() || 'workflow',
              agentId: assistantId,
            },
            baseUrl
          )
          const output = String(res.text || res.message || res.params || '').trim()
          if (!output) return { nodeId, ok: false, reason: '助手返回为空', output: '' }
          return { nodeId, ok: true, reason: '', output }
        } catch (error) {
          return { nodeId, ok: false, reason: formatApiError(error), output: '' }
        }
      }))

      if (workflowRunTokenRef.current !== runToken) return

      for (const result of levelResults) {
        const node = nodeMap.get(result.nodeId)
        finished.add(result.nodeId)
        if (result.ok) {
          outputByNodeId[result.nodeId] = result.output
          setWorkflowRunNodeStatusMap((prev) => ({ ...prev, [result.nodeId]: 'success' }))
          appendWorkflowRunLog('success', `节点「${node?.name || result.nodeId}」输出：${result.output}`)
          appendWorkflowRunLog('success', `节点「${node?.name || result.nodeId}」执行完成`)
          const sourceMemberId = String(node?.sourceMemberId || '').trim()
          const assistantId = sourceMemberId.startsWith('assistant-') ? sourceMemberId.slice('assistant-'.length) : ''
          const candidateConversationId = String(conversation?.id || workflowWorkspace?.groupId || '').trim()
          const groupId = toServerGroupId(candidateConversationId)
          const rawGroupId = normalizeGroupRawId(candidateConversationId)
          const canonicalGroupConvId = toCanonicalGroupConversationId(candidateConversationId)
          const senderName = assistantId
            ? (
              groupAssistantDisplayNameMap[assistantId]
              || (conversation?.assistants ?? []).find((item) => item.id === assistantId)?.name
              || node?.name
              || assistantId
            )
            : (node?.name || '助手')
          if (!groupId) {
            appendWorkflowRunLog(
              'error',
              `节点「${node?.name || result.nodeId}」回传群聊失败：群组 ID 无效（conversation.id=${candidateConversationId || '-'}）`
            )
          } else {
            const groupBubbleMsg: Message = {
              id: uuidv4(),
              sender: senderName,
              senderImei: assistantId || undefined,
              content: result.output,
              type: 'assistant',
              timestamp: Date.now(),
            }
            const persistIds = Array.from(
              new Set([
                conversation?.id ?? '',
                canonicalGroupConvId,
                `group_${rawGroupId}`,
                rawGroupId,
                workflowWorkspace?.groupId ?? '',
              ].filter((id) => !!id))
            )
            persistIds.forEach((cid) => appendMessageToStorage(cid, groupBubbleMsg))
            const isViewingCurrentGroup = normalizeGroupRawId(currentConvIdRef.current) === rawGroupId
            if (isViewingCurrentGroup) {
              setMessages((prev) => (prev.some((m) => m.id === groupBubbleMsg.id) ? prev : [...prev, groupBubbleMsg]))
            }
            const summaryTargetId = String(conversation?.id || canonicalGroupConvId || '').trim()
            if (summaryTargetId) {
              onUpdateLastMessage?.(
                summaryTargetId,
                toSkillSharePreview(result.output) || toAssistantSharePreview(result.output) || result.output.slice(0, 50),
                groupBubbleMsg.timestamp,
                { isFromMe: false, isViewing: isViewingCurrentGroup }
              )
              triggerConversationSummary(summaryTargetId, conversation?.name)
            }
            if (imei) {
              try {
                await sendAssistantGroupMessage(imei, groupId, result.output, senderName, assistantId)
              } catch (error) {
                appendWorkflowRunLog(
                  'error',
                  `节点「${node?.name || result.nodeId}」回传群聊失败：${formatApiError(error)}（groupId=${groupId}）`
                )
              }
            } else {
              appendWorkflowRunLog(
                'error',
                `节点「${node?.name || result.nodeId}」回传群聊失败：当前设备 IMEI 无效（groupId=${groupId}）`
              )
            }
          }
          for (const nxt of downstream[result.nodeId]) {
            indegree[nxt] -= 1
            if (indegree[nxt] === 0) ready.push(nxt)
          }
        } else {
          failed = true
          setWorkflowRunNodeStatusMap((prev) => ({ ...prev, [result.nodeId]: 'failed' }))
          appendWorkflowRunLog('error', `节点「${node?.name || result.nodeId}」执行失败：${result.reason}`)
        }
      }

      if (failed) break
    }

    if (workflowRunTokenRef.current !== runToken) return
    if (failed) {
      const skipped = nodes.filter((node) => !finished.has(node.id)).map((node) => node.name)
      if (skipped.length > 0) appendWorkflowRunLog('info', `未执行节点：${skipped.join('、')}`)
      appendWorkflowRunLog('error', '真实执行结束：存在失败节点')
      showInlineToastNotice('真实执行失败，请查看执行日志')
    } else {
      appendWorkflowRunLog('success', '真实执行完成：全部节点成功')
      showInlineToastNotice('真实执行完成')
    }
    setWorkflowRunInProgress(false)
  }, [
    workflowCanvasNodes,
    workflowCanvasEdges,
    workflowRunInProgress,
    input,
    conversation?.assistants,
    conversation?.assistantConfigs,
    conversation?.id,
    conversation?.name,
    conversation?.type,
    workflowWorkspace?.groupId,
    groupAssistantDisplayNameMap,
    onUpdateLastMessage,
    imei,
    appendWorkflowRunLog,
    showInlineToastNotice,
  ])

  const handleWorkflowExecuteFromHeader = useCallback(async () => {
    setWorkflowHeaderMenuOpen(false)
    const activeConversation = conversation
    if (!activeConversation || activeConversation.type !== 'group') {
      window.alert('当前会话不是群聊，无法执行编排。')
      return
    }
    if (workflowRunInProgress) {
      window.alert('当前已有编排正在执行，请稍后再试。')
      return
    }
    const payload = await resolveWorkflowPayloadForConversation(activeConversation)
    if (!payload || payload.graph.nodes.length === 0) {
      window.alert('当前群组没有可执行的编排，请先点击“打开编排页”进行配置。')
      return
    }
    await handleWorkflowExecuteRun({ nodes: payload.graph.nodes, edges: payload.graph.edges })
  }, [conversation, workflowRunInProgress, resolveWorkflowPayloadForConversation, handleWorkflowExecuteRun])

  const workflowDecisionEdgeCodeMap = useMemo(() => {
    const nodeTypeMap = new Map<string, WorkflowCanvasNode['type']>()
    for (const node of workflowCanvasNodes) {
      nodeTypeMap.set(node.id, node.type)
    }
    const grouped = new Map<string, string[]>()
    for (const edge of workflowCanvasEdges) {
      if (nodeTypeMap.get(edge.fromNodeId) !== 'decision') continue
      const arr = grouped.get(edge.fromNodeId) ?? []
      arr.push(edge.id)
      grouped.set(edge.fromNodeId, arr)
    }
    const encoded: Record<string, string> = {}
    for (const edgeIds of grouped.values()) {
      edgeIds.forEach((edgeId, idx) => {
        encoded[edgeId] = String(idx + 1)
      })
    }
    return encoded
  }, [workflowCanvasEdges, workflowCanvasNodes])

  const handleSaveAssistantRolePrompt = useCallback(async () => {
    if (!selectedAssistantNodeDetails?.assistantId) return
    if (conversation?.type !== 'group') return
    const myImei = (imei || '').trim()
    if (!myImei) {
      setAssistantRolePromptSaveHint('保存失败：当前设备 IMEI 无效')
      return
    }
    const serverGroupId = toServerGroupId(conversation.id)
    if (!serverGroupId) {
      setAssistantRolePromptSaveHint('保存失败：群组 ID 无效')
      return
    }
    setAssistantRolePromptSaving(true)
    setAssistantRolePromptSaveHint('')
    try {
      const res = await updateGroupAssistantConfig(
        myImei,
        serverGroupId,
        selectedAssistantNodeDetails.assistantId,
        { rolePrompt: assistantRolePromptDraft.trim() }
      )
      if (!res.success) {
        setAssistantRolePromptSaveHint(`保存失败：${res.message || '未知错误'}`)
        return
      }
      const previousConfigs = conversation.assistantConfigs ?? {}
      const nextConfigs = {
        ...previousConfigs,
        [selectedAssistantNodeDetails.assistantId]: {
          ...(previousConfigs[selectedAssistantNodeDetails.assistantId] ?? {}),
          rolePrompt: assistantRolePromptDraft.trim(),
        },
      }
      onSelectConversation?.({
        ...conversation,
        assistantConfigs: nextConfigs,
      })
      setAssistantRolePromptSaveHint('已同步到群组角色设置')
    } catch {
      setAssistantRolePromptSaveHint('保存失败：网络异常')
    } finally {
      setAssistantRolePromptSaving(false)
    }
  }, [
    selectedAssistantNodeDetails?.assistantId,
    conversation,
    imei,
    assistantRolePromptDraft,
    onSelectConversation,
  ])

  useEffect(() => {
    if (!workflowAddMemberOpen) return
    if (workflowAddMemberType === 'friend') {
      if (workflowAddMemberTargetId && workflowAddableFriends.some((friend) => friend.imei === workflowAddMemberTargetId)) return
      setWorkflowAddMemberTargetId(workflowAddableFriends[0]?.imei ?? '')
      return
    }
    if (workflowAddMemberTargetId && workflowAddableAssistants.some((assistant) => assistant.id === workflowAddMemberTargetId)) return
    setWorkflowAddMemberTargetId(workflowAddableAssistants[0]?.id ?? '')
  }, [
    workflowAddMemberOpen,
    workflowAddMemberType,
    workflowAddMemberTargetId,
    workflowAddableFriends,
    workflowAddableAssistants,
  ])

  const applyWorkflowGroupUpdate = useCallback((group: GroupInfo) => {
    const rawGroupId = normalizeGroupRawId(group.group_id)
    setGroupInfoCache((prev) => ({ ...prev, [rawGroupId]: group }))
    if (conversation?.type === 'group' && normalizeGroupRawId(conversation.id) === rawGroupId) {
      const groupAssistants = group.assistants ?? (group.assistant_enabled ? ['assistant'] : [])
      const assistantConfigs = group.assistant_configs ?? {}
      const updatedConversation: Conversation = {
        ...conversation,
        id: toCanonicalGroupConversationId(group.group_id) || conversation.id,
        name: group.name || conversation.name,
        members: group.members ?? [],
        groupWorkflowModeEnabled: !!group.workflow_mode,
        groupFreeDiscoveryEnabled: !!group.free_discovery,
        groupAssistantMutedEnabled: !!group.assistant_muted,
        assistantConfigs: Object.keys(assistantConfigs).length > 0 ? assistantConfigs : undefined,
        assistants: groupAssistants.map((aid) => {
          const existing = conversation.assistants?.find((a) => a.id === aid)
          const fromProfile = workflowAssistantsForAdd.find((a) => a.id === aid)
          return {
            id: aid,
            name: assistantConfigs[aid]?.name ?? existing?.name ?? fromProfile?.name ?? aid,
          }
        }),
      }
      onSelectConversation?.(updatedConversation)
    }
    setWorkflowWorkspace((prev) => {
      if (!prev) return prev
      if (normalizeGroupRawId(prev.groupId) !== rawGroupId) return prev
      return {
        ...prev,
        groupId: rawGroupId,
        groupName: group.name || prev.groupName,
        members: buildWorkflowMembersFromGroupInfo(group),
      }
    })
  }, [conversation, workflowAssistantsForAdd, onSelectConversation, buildWorkflowMembersFromGroupInfo])

  const handleWorkflowAddGroupMember = useCallback(async () => {
    if (!workflowCanAddMembers || !workflowWorkspace) return
    const myImei = (imei || '').trim()
    if (!myImei) {
      setWorkflowAddMemberHint('添加失败：当前设备 IMEI 无效')
      return
    }
    const serverGroupId = toServerGroupId(workflowWorkspace.groupId || conversation?.id || '')
    if (!serverGroupId) {
      setWorkflowAddMemberHint('添加失败：群组 ID 无效')
      return
    }
    if (!workflowAddMemberTargetId) {
      setWorkflowAddMemberHint('请先选择要添加的成员')
      return
    }
    setWorkflowAddMemberSaving(true)
    setWorkflowAddMemberHint('')
    try {
      if (workflowAddMemberType === 'friend') {
        const res = await addGroupMember(myImei, serverGroupId, workflowAddMemberTargetId)
        if (!res.success) {
          setWorkflowAddMemberHint(`添加失败：${res.message || '未知错误'}`)
          return
        }
      } else {
        const assistantId = workflowAddMemberTargetId
        const custom = getCustomAssistantById(assistantId)
        const config = custom
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
        const res = await addGroupAssistant(myImei, serverGroupId, assistantId, config)
        if (!res.success) {
          setWorkflowAddMemberHint(`添加失败：${res.message || '未知错误'}`)
          return
        }
        if (res.group) {
          applyWorkflowGroupUpdate(res.group)
          setWorkflowAddMemberHint('添加成功')
          setWorkflowAddMemberTargetId('')
          return
        }
      }
      const refreshed = await getGroup(serverGroupId)
      if (!refreshed) {
        setWorkflowAddMemberHint('添加成功，但刷新群成员失败')
        setWorkflowAddMemberTargetId('')
        return
      }
      applyWorkflowGroupUpdate(refreshed)
      setWorkflowAddMemberHint('添加成功')
      setWorkflowAddMemberTargetId('')
    } catch {
      setWorkflowAddMemberHint('添加失败：网络异常')
    } finally {
      setWorkflowAddMemberSaving(false)
    }
  }, [
    workflowCanAddMembers,
    workflowWorkspace,
    imei,
    conversation?.id,
    workflowAddMemberTargetId,
    workflowAddMemberType,
    applyWorkflowGroupUpdate,
  ])

  useEffect(() => {
    return () => {
      window.removeEventListener('mousemove', handleWorkflowNodeDragMove)
      window.removeEventListener('mouseup', handleWorkflowNodeDragEnd)
    }
  }, [handleWorkflowNodeDragMove, handleWorkflowNodeDragEnd])

  useEffect(() => {
    if (!workflowConnecting) return
    const handleMouseMove = (event: MouseEvent) => {
      const point = workflowClientToWorld(event.clientX, event.clientY)
      if (!point) return
      const toX = Math.min(WORKFLOW_STAGE_WIDTH, Math.max(0, point.x))
      const toY = Math.min(WORKFLOW_STAGE_HEIGHT, Math.max(0, point.y))
      setWorkflowConnecting((prev) => (prev ? { ...prev, toX, toY } : prev))
    }
    const handleMouseUp = () => {
      setWorkflowConnecting(null)
    }
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('mouseup', handleMouseUp)
    return () => {
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseup', handleMouseUp)
    }
  }, [workflowConnecting, workflowClientToWorld])

  useEffect(() => {
    if (!workflowEditTarget) return
    if (workflowEditTarget.type === 'node') {
      if (!workflowCanvasNodes.some((node) => node.id === workflowEditTarget.id)) {
        setWorkflowEditTarget(null)
      }
      return
    }
    if (!workflowCanvasEdges.some((edge) => edge.id === workflowEditTarget.id)) {
      setWorkflowEditTarget(null)
    }
  }, [workflowEditTarget, workflowCanvasNodes, workflowCanvasEdges])

  useEffect(() => {
    if (!workflowWorkspace || !workflowEditTarget) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Backspace') return
      const target = event.target as HTMLElement | null
      if (target) {
        const tag = (target.tagName || '').toLowerCase()
        const editable = target.isContentEditable
        if (editable || tag === 'input' || tag === 'textarea' || tag === 'select') return
      }
      event.preventDefault()
      deleteWorkflowTarget(workflowEditTarget)
      setWorkflowContextMenu(null)
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
    }
  }, [workflowWorkspace, workflowEditTarget, deleteWorkflowTarget])

  useEffect(() => {
    if (!workflowContextMenu) return
    const close = () => setWorkflowContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('contextmenu', close)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('contextmenu', close)
    }
  }, [workflowContextMenu])

  const handleWorkflowPanMove = useCallback((event: MouseEvent) => {
    const pan = workflowPanRef.current
    if (!pan) return
    setWorkflowViewport((prev) => ({
      ...prev,
      offsetX: pan.baseOffsetX + (event.clientX - pan.startX),
      offsetY: pan.baseOffsetY + (event.clientY - pan.startY),
    }))
  }, [])

  const handleWorkflowPanEnd = useCallback(() => {
    workflowPanRef.current = null
    window.removeEventListener('mousemove', handleWorkflowPanMove)
    window.removeEventListener('mouseup', handleWorkflowPanEnd)
  }, [handleWorkflowPanMove])

  const handleWorkflowCanvasMouseDown = useCallback((event: React.MouseEvent<HTMLElement>) => {
    if (event.button !== 0) return
    setWorkflowContextMenu(null)
    const target = event.target as HTMLElement
    if (
      target.closest('.chat-workflow-workspace-node')
      || target.closest('.chat-workflow-workspace-node-port')
      || target.closest('.chat-workflow-workspace-edit-panel')
      || target.closest('.chat-workflow-workspace-edit-delete')
      || target.closest('.chat-workflow-workspace-connection-hit')
    ) {
      return
    }
    setWorkflowSelectedTarget(null)
    setWorkflowEditTarget(null)
    if (workflowConnecting) return
    workflowPanRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      baseOffsetX: workflowViewport.offsetX,
      baseOffsetY: workflowViewport.offsetY,
    }
    window.addEventListener('mousemove', handleWorkflowPanMove)
    window.addEventListener('mouseup', handleWorkflowPanEnd)
    event.preventDefault()
  }, [workflowConnecting, workflowViewport.offsetX, workflowViewport.offsetY, handleWorkflowPanMove, handleWorkflowPanEnd])

  const handleWorkflowCanvasWheel = useCallback((event: React.WheelEvent<HTMLElement>) => {
    if (!event.ctrlKey) return
    event.preventDefault()
    const canvasRect = workflowCanvasRef.current?.getBoundingClientRect()
    if (!canvasRect) return
    const pointerX = event.clientX - canvasRect.left
    const pointerY = event.clientY - canvasRect.top
    setWorkflowViewport((prev) => {
      const nextScale = clampWorkflowScale(prev.scale * (event.deltaY < 0 ? 1.08 : 0.92))
      const worldX = (pointerX - prev.offsetX) / prev.scale
      const worldY = (pointerY - prev.offsetY) / prev.scale
      return {
        scale: nextScale,
        offsetX: pointerX - worldX * nextScale,
        offsetY: pointerY - worldY * nextScale,
      }
    })
  }, [])

  useEffect(() => {
    return () => {
      window.removeEventListener('mousemove', handleWorkflowPanMove)
      window.removeEventListener('mouseup', handleWorkflowPanEnd)
    }
  }, [handleWorkflowPanMove, handleWorkflowPanEnd])

  const handleWorkflowCanvasDrop = useCallback((event: React.DragEvent<HTMLElement>) => {
    event.preventDefault()
    const raw = event.dataTransfer.getData('application/topoclaw-workflow-member')
    if (!raw) return
    const member = JSON.parse(raw) as WorkflowMemberItem
    if (!member || !member.id || !member.name || (member.type !== 'assistant' && member.type !== 'user')) return
    const point = workflowClientToWorld(event.clientX, event.clientY)
    if (!point) return
    const maxX = Math.max(0, WORKFLOW_STAGE_WIDTH - WORKFLOW_NODE_WIDTH)
    const maxY = Math.max(0, WORKFLOW_STAGE_HEIGHT - WORKFLOW_NODE_HEIGHT)
    const x = snapWorkflowPosition(point.x - WORKFLOW_NODE_WIDTH / 2, maxX)
    const y = snapWorkflowPosition(point.y - WORKFLOW_NODE_HEIGHT / 2, maxY)
    setWorkflowCanvasNodes((prev) => [
      ...prev,
      {
        id: `workflow-node-${member.id}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        sourceMemberId: member.id,
        name: member.name,
        type: member.type,
        nodeTaskIntro: '',
        userRoleIntro: member.type === 'user' ? '' : undefined,
        x,
        y,
      },
    ])
  }, [workflowClientToWorld])

  const hideMentionPopup = useCallback(() => {
    setMentionPopupOpen(false)
    mentionStartPosRef.current = -1
    setMentionQuery('')
    setMentionSelectedIndex(0)
  }, [])

  const selectMentionCandidate = useCallback((candidate: { id: string; displayName: string; isAssistant: boolean }) => {
    const inputEl = inputRef.current
    if (!inputEl) return
    const start = mentionStartPosRef.current
    if (start < 0) return
    const insertText = `@${candidate.displayName}`
    const before = input.slice(0, start)
    let after = input.slice(start)
    const spaceIdx = after.search(/\s/)
    if (spaceIdx >= 0) after = after.slice(spaceIdx)
    else after = ''
    const newVal = before + insertText + ' ' + after
    setInput(newVal)
    hideMentionPopup()
    requestAnimationFrame(() => {
      const pos = start + insertText.length + 1
      inputEl.setSelectionRange(pos, pos)
      inputEl.focus()
    })
  }, [input, hideMentionPopup])

  const handleGroupInputChange = useCallback((val: string) => {
    setInput(val)
    const cursor = inputRef.current?.selectionStart ?? val.length
    const beforeCursor = val.slice(0, cursor)
    const lastAt = beforeCursor.lastIndexOf('@')
    if (lastAt >= 0) {
      const afterAt = beforeCursor.slice(lastAt + 1)
      if (!/\s/.test(afterAt)) {
        mentionStartPosRef.current = lastAt
        setMentionQuery(afterAt)
        setMentionPopupOpen(true)
        setMentionSelectedIndex(0)
        return
      }
    }
    setMentionPopupOpen(false)
    mentionStartPosRef.current = -1
  }, [])

  const handleInputKeyDownForMention = useCallback((e: React.KeyboardEvent) => {
    if (!mentionPopupOpen || mentionCandidates.length === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setMentionSelectedIndex((i) => (i + 1) % mentionCandidates.length)
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      setMentionSelectedIndex((i) => (i - 1 + mentionCandidates.length) % mentionCandidates.length)
      return
    }
    if (e.key === 'Enter' && mentionPopupOpen) {
      e.preventDefault()
      selectMentionCandidate(mentionCandidates[mentionSelectedIndex])
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      hideMentionPopup()
    }
  }, [mentionPopupOpen, mentionCandidates, mentionSelectedIndex, selectMentionCandidate, hideMentionPopup])

  const chatBaseUrl =
    isChatAssistant ? getChatAssistantBaseUrl()
    : isCustomChatAssistant && customAssistant ? customAssistant.baseUrl
    : conversation?.baseUrl && isCustomAssistantId(conversation?.id) ? conversation.baseUrl
    : undefined
  const builtinRuntimeAgentId = useMemo(() => {
    if (!conversation?.id) return undefined
    if (isCustomChatAssistant && customAssistant && isDefaultBuiltinUrl(customAssistant.baseUrl)) {
      return conversation.id
    }
    return undefined
  }, [conversation?.id, isCustomChatAssistant, customAssistant])
  const chatThreadId =
    isMultiSessionCustom && currentSessionId
      ? currentSessionId
      : conversation?.id && chatActorId
        ? (conversation.id === CONVERSATION_ID_CHAT_ASSISTANT
          ? uuidv5(`${chatActorId}_chat_assistant`, '6ba7b810-9dad-11d1-80b4-00c04fd430c8')
          : `${chatActorId}_${conversation.id}`)
        : ''
  const useChatWs = (isChatAssistant || isCustomChatAssistant) && !!chatBaseUrl && !!chatThreadId

  const builtinModelSessionKey = useMemo(() => {
    if (!builtinChatSlot || !conversation?.id) return null
    if (isMultiSessionCustom && currentSessionId) {
      return `${conversation.id}__${currentSessionId}`
    }
    return conversation.id
  }, [builtinChatSlot, conversation?.id, isMultiSessionCustom, currentSessionId])

  const threadIdsToSubscribe =
    isMultiSessionCustom && sessions.length > 0
      ? sessions.map((s) => s.id)
      : chatThreadId
        ? [chatThreadId]
        : []

  const chatWs = useChatWebSocketFromPool(
    chatBaseUrl ?? '',
    chatThreadId,
    threadIdsToSubscribe,
    useChatWs,
    useCallback(
      (pushThreadId: string, content: string) => {
        if (currentConvIdRef.current !== conversation?.id) return
        if (isMultiSessionCustom && pushThreadId !== currentSessionId) {
          if (!conversation?.id) return
          const m: Message = {
            id: uuidv4(),
            sender: customAssistant?.name ?? '小助手',
            content: stripNeedExecutionGuide(content).trim() || content,
            type: 'assistant',
            timestamp: Date.now(),
          }
          appendMessageToStorage(conversation.id, m, pushThreadId)
          return
        }
        const ts = Date.now()
        const norm = (s: string) => stripNeedExecutionGuide(s).trim()
        const normalized = norm(content)
        setMessages((prev) => {
          const isDup = prev.some(
            (x) =>
              x.type === 'assistant' &&
              norm(x.content || '') === normalized &&
              Math.abs((x.timestamp || 0) - ts) < 15000
          )
          if (isDup) return prev
          return [
            ...prev,
            {
              id: uuidv4(),
              sender: customAssistant?.name ?? '小助手',
              content: normalized || content,
              type: 'assistant' as const,
              timestamp: ts,
            },
          ]
        })
      },
      [conversation?.id, isMultiSessionCustom, currentSessionId, customAssistant?.name]
    )
  )

  useEffect(() => {
    if (!imei) return
    getProfile(imei).then((p) => {
      setUserAvatar(p?.avatar)
      setUserName(p?.name ?? '')
    })
  }, [imei])

  const refreshImLocalHistory = useCallback(async () => {
    if (!conversation?.id || !isImConversation) return
    const channel = isImQq ? 'qq' : 'weixin'
    const res = await getImLocalHistory({ channel, limit: 500 })
    if (!res.ok) {
      setImConnectionHint(res.error || '读取本地 IM 历史失败')
      return
    }
    const imMessages: Message[] = res.messages.map((m) => ({
      id: m.id,
      sender: m.role === 'assistant' ? 'TopoClaw' : '用户自己',
      content: m.content || '',
      type: (m.role === 'assistant' ? 'assistant' : 'user') as Message['type'],
      timestamp: m.timestamp || Date.now(),
    }))
    if (!imMessages.length) return
    const existing = loadMessages(conversation.id) as Message[]
    const existingIds = new Set(existing.map((m) => m.id))
    const newlyAdded = imMessages.filter((m) => !existingIds.has(m.id))
    setMessages((prev) => {
      const byId = new Map<string, Message>()
      prev.forEach((m) => byId.set(m.id, m))
      imMessages.forEach((m) => byId.set(m.id, m))
      const merged = [...byId.values()].sort((a, b) => a.timestamp - b.timestamp)
      if (currentConvIdRef.current === conversation.id) {
        saveMessages(conversation.id, merged)
      }
      return merged
    })
    if (newlyAdded.length === 0) return
    const last = newlyAdded[newlyAdded.length - 1]
    if (last) {
      onUpdateLastMessage?.(conversation.id, (last.content || '').slice(0, 50), last.timestamp, {
        isFromMe: last.type === 'user',
        isViewing: true,
      })
    }
  }, [conversation?.id, isImConversation, isImQq, onUpdateLastMessage, setMessages])

  useEffect(() => {
    if (!isImConversation || !conversation?.id) return
    let cancelled = false
    ;(async () => {
      const cfg = await getBuiltinAssistantConfig()
      if (cancelled) return
      setImConnected(isImQq ? !!cfg.qqEnabled : !!cfg.weixinEnabled)
    })()
    return () => {
      cancelled = true
    }
  }, [conversation?.id, isImConversation, isImQq])

  useEffect(() => {
    if (!isImConversation || !conversation?.id) return
    void refreshImLocalHistory()
    const FOREGROUND_POLL_MS = 5000
    const BACKGROUND_POLL_MS = 15000
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | null = null

    const scheduleNext = () => {
      if (stopped) return
      const isVisible = typeof document === 'undefined' || document.visibilityState === 'visible'
      const nextMs = isVisible ? FOREGROUND_POLL_MS : BACKGROUND_POLL_MS
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        void refreshImLocalHistory().finally(() => {
          scheduleNext()
        })
      }, nextMs)
    }

    const onVisibilityChange = () => {
      if (stopped) return
      if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
        void refreshImLocalHistory()
      }
      scheduleNext()
    }

    document.addEventListener('visibilitychange', onVisibilityChange)
    scheduleNext()
    return () => {
      stopped = true
      if (timer) clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [conversation?.id, isImConversation, refreshImLocalHistory])

  const handleToggleImConnection = useCallback(async () => {
    if (!isImConversation || imSwitching) return
    const nextConnected = !imConnected
    setImSwitching(true)
    setImConnectionHint('')
    try {
      if (isImQq) {
        await saveBuiltinAssistantConfig({ qqEnabled: nextConnected })
      } else {
        await saveBuiltinAssistantConfig({ weixinEnabled: nextConnected })
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setImConnectionHint(`切换失败：${restartRes.error || '内置服务重启失败'}`)
        return
      }
      setImConnected(nextConnected)
      setImConnectionHint(nextConnected ? '已连接' : '已断开')
    } catch (e) {
      setImConnectionHint(`切换失败：${String(e)}`)
    } finally {
      setImSwitching(false)
    }
  }, [imConnected, imSwitching, isImConversation, isImQq])

  const handleToggleDigitalCloneForCurrentFriend = useCallback(async () => {
    if (!imei || !isFriend || !currentFriendImei || digitalCloneSaving) return
    const next = !digitalCloneCurrentFriendEnabled
    setDigitalCloneSaving(true)
    try {
      const current = await getUserSettings(imei)
      const settings = current.settings || {}
      const overrides = { ...(settings.digital_clone_friend_overrides || {}) }
      overrides[currentFriendImei] = next
      const updated = await updateUserSettings(imei, {
        digital_clone_friend_overrides: overrides,
      })
      if (!updated.success) return
      const globalEnabled = updated.settings.digital_clone_enabled === true
      const hasOverride = Object.prototype.hasOwnProperty.call(
        updated.settings.digital_clone_friend_overrides || {},
        currentFriendImei
      )
      const effectiveEnabled = hasOverride
        ? (updated.settings.digital_clone_friend_overrides?.[currentFriendImei] === true)
        : globalEnabled
      setDigitalCloneGlobalEnabled(globalEnabled)
      setDigitalCloneCurrentFriendOverride(hasOverride)
      setDigitalCloneCurrentFriendEnabled(effectiveEnabled)
    } catch {
      // ignore toggle failure
    } finally {
      setDigitalCloneSaving(false)
    }
  }, [
    imei,
    isFriend,
    currentFriendImei,
    digitalCloneSaving,
    digitalCloneCurrentFriendEnabled,
  ])

  const wsSendRef = useRef<(msg: object) => boolean>(() => false)
  const mobileProbeWaitersRef = useRef<Map<string, MobileProbeWaiter>>(new Map())
  const buildCloneQueryContext = useCallback(async (params: {
    friendImei: string
    friendName?: string
    conversationId?: string
  }): Promise<string | undefined> => {
    const friendImei = String(params.friendImei || '').trim()
    if (!friendImei) return undefined
    const friendFromList = profileFriends.find((f) => f.imei === friendImei)
    const ownerLabel = (userName || '').trim() || (imei ? `${imei.slice(0, 8)}...` : '当前用户')
    const friendLabel = String(params.friendName || '').trim()
      || (friendFromList?.nickname || '').trim()
      || `${friendImei.slice(0, 8)}...`
    let latestSummary = ''
    try {
      const summaryRes = await listConversationSummaries({ scopeType: 'friend', scopeId: friendImei })
      latestSummary = trimForCloneContext(summaryRes.latest?.summary || '', 700)
    } catch {
      latestSummary = ''
    }
    const conversationId = String(params.conversationId || `friend_${friendImei}`).trim()
    const historyLines = (loadMessages(conversationId) as Message[])
      .slice(-30)
      .filter((m) => trimForCloneContext(m.content || '').length > 0)
      .slice(-10)
      .map((m) => {
        const role =
          m.messageSource === 'my_clone'
            ? '我的数字分身'
            : m.messageSource === 'friend_clone'
              ? '好友数字分身'
              : m.sender === '我'
                ? '我'
                : '好友'
        return `- ${role}: ${trimForCloneContext(m.content || '')}`
      })
    const parts = [
      '【数字分身会话设定】',
      `你是${ownerLabel}的数字分身（TopoClaw）。`,
      `你当前正在和${friendLabel}聊天，对方是${ownerLabel}的好友。`,
      `请始终以${ownerLabel}的身份与口吻，直接回复${friendLabel}，不要把对方当成${ownerLabel}本人。`,
    ]
    if (latestSummary) {
      parts.push('', '【我与该好友的会话摘要（可选）】', latestSummary)
    }
    if (historyLines.length > 0) {
      parts.push('', '【最近10条会话上下文（可选）】', ...historyLines)
    }
    const payload = parts.join('\n').trim()
    return payload || undefined
  }, [profileFriends, userName, imei])

  const { send: wsSend, cancelMobileExecution } = useCrossDeviceWebSocket(imei, useCallback((msg) => {
    const resolveSessionContext = (conversationId: string): {
      isMultiSession: boolean
      baseUrl?: string
    } => {
      const targetConvId = String(conversationId || '').trim()
      if (!targetConvId) return { isMultiSession: false }
      const isCurrentConversation = targetConvId === conversation?.id
      const targetAssistant = isCurrentConversation ? customAssistant : getCustomAssistantById(targetConvId)
      const targetIsMultiSession = isCurrentConversation
        ? isMultiSessionCustom
        : !!(targetAssistant && hasMultiSession(targetAssistant))
      if (!targetIsMultiSession) return { isMultiSession: false }
      const targetBaseUrl = isCurrentConversation
        ? (customAssistant?.baseUrl ?? conversation?.baseUrl)
        : targetAssistant?.baseUrl
      return { isMultiSession: true, baseUrl: targetBaseUrl }
    }
    const ensureIncomingSessionExists = (
      conversationId: string,
      sessionId?: string,
      createdAt?: number
    ) => {
      const sid = String(sessionId || '').trim()
      if (!sid) return
      const targetConvId = String(conversationId || '').trim()
      if (!targetConvId) return
      const { isMultiSession, baseUrl } = resolveSessionContext(targetConvId)
      if (!isMultiSession) return
      const exists = loadSessions(targetConvId, baseUrl).some((s) => s.id === sid)
      if (exists) return
      const next: ChatSession = { id: sid, title: '新对话', createdAt: createdAt ?? Date.now() }
      addSession(targetConvId, next, baseUrl)
      setActiveSessionLocal(targetConvId, sid, baseUrl)
      if (currentConvIdRef.current === targetConvId) {
        setSessions((prev) => {
          if (prev.some((s) => s.id === sid)) return prev
          return [next, ...prev].sort((a, b) => b.createdAt - a.createdAt)
        })
      }
    }
    const resolveDefaultSessionId = (conversationId: string, sessionIdFromMsg?: string): string | undefined => {
      const sidFromMsg = String(sessionIdFromMsg || '').trim()
      if (sidFromMsg) return sidFromMsg
      const targetConvId = String(conversationId || '').trim()
      if (!targetConvId) return undefined
      const isCurrentConversation = targetConvId === conversation?.id
      const { isMultiSession, baseUrl: targetBaseUrl } = resolveSessionContext(targetConvId)
      if (!isMultiSession) return undefined
      if (isCurrentConversation && currentSessionId) return currentSessionId
      const remembered = getActiveSessionLocal(targetConvId, targetBaseUrl)
      const rememberedId = (remembered || '').trim()
      if (rememberedId) return rememberedId
      const knownSessions = loadSessions(targetConvId, targetBaseUrl)
      return knownSessions.length > 0 ? knownSessions[0].id : undefined
    }
    const normalizeIncomingConversationId = (rawConversationId: string | undefined): string => {
      const raw = String(rawConversationId || '').trim()
      if (!raw) return ''
      const normalizeConversationCore = (value: string): string => {
        let text = String(value || '').trim()
        if (!text) return ''
        if (text.includes('__')) {
          const [cid, sid] = text.split('__')
          const normalizedCid = normalizeConversationCore(cid || '')
          return normalizedCid && sid ? `${normalizedCid}__${sid}` : normalizedCid
        }
        if (text.endsWith('_local')) text = text.slice(0, -6).trim()
        if (text.startsWith('group_group_')) {
          text = `group_${text.slice('group_group_'.length)}`
        }
        if (text.startsWith('friend_friend_')) {
          text = `friend_${text.slice('friend_friend_'.length)}`
        }
        if (text.startsWith('group_') && text.includes(':')) {
          text = text.split(':', 1)[0].trim()
        }
        if (text.startsWith('friend_') && text.includes(':')) {
          text = text.split(':', 1)[0].trim()
        }
        return text
      }
      const normalizedRaw = normalizeConversationCore(raw)
      if (normalizedRaw.startsWith('friend_') || normalizedRaw.startsWith('group_') || normalizedRaw === CONVERSATION_ID_ASSISTANT) return normalizedRaw
      if (isCustomAssistantId(normalizedRaw)) return normalizedRaw
      const currentImei = String(imei || '').trim()
      const tryStripActorPrefix = (value: string): string => {
        const text = String(value || '').trim()
        if (!text) return ''
        const normalizedLocal = normalizeConversationCore(text)
        if (normalizedLocal.startsWith('friend_') || normalizedLocal.startsWith('group_') || normalizedLocal === CONVERSATION_ID_ASSISTANT) {
          return normalizedLocal
        }
        if (isCustomAssistantId(normalizedLocal)) return normalizedLocal
        const idx = normalizedLocal.indexOf('_')
        if (idx <= 0) return ''
        const tail = normalizedLocal.slice(idx + 1).trim()
        if (!tail) return ''
        if (tail.startsWith('friend_') || tail.startsWith('group_') || tail === CONVERSATION_ID_ASSISTANT) return tail
        if (isCustomAssistantId(tail)) return tail
        return ''
      }
      if (currentImei && normalizedRaw.startsWith(`${currentImei}_`)) {
        const stripped = tryStripActorPrefix(normalizedRaw)
        if (stripped) return stripped
      }
      {
        const stripped = tryStripActorPrefix(normalizedRaw)
        if (stripped) return stripped
      }
      const lower = normalizedRaw.toLowerCase()
      // 兼容云端/历史 TopoClaw 动态 ID（如 8786e85a..._topoclaw_xxx）到本地固定会话 custom_topoclaw。
      if (
        lower === 'topoclaw' ||
        lower.includes('_topoclaw_') ||
        lower.startsWith('topoclaw_')
      ) {
        const localTopoByBaseUrl = getCustomAssistantByBaseUrl('topoclaw://relay')
        const localTopoByFixedId = getCustomAssistantById(DEFAULT_TOPOCLAW_ASSISTANT_ID)
        return localTopoByBaseUrl?.id || localTopoByFixedId?.id || DEFAULT_TOPOCLAW_ASSISTANT_ID
      }
      return normalizedRaw
    }
    const parseConversationRoute = (rawConversationId: string | undefined, sessionIdFromMsg?: string) => {
      const raw = normalizeIncomingConversationId(rawConversationId)
      if (!raw) {
        const fallbackConvId = CONVERSATION_ID_ASSISTANT
        return { conversationId: fallbackConvId, sessionId: resolveDefaultSessionId(fallbackConvId, sessionIdFromMsg) }
      }
      if (raw.includes('__')) {
        const [cid, sid] = raw.split('__')
        const normalizedCid = normalizeIncomingConversationId(cid || CONVERSATION_ID_ASSISTANT)
        return {
          conversationId: normalizedCid,
          sessionId: resolveDefaultSessionId(normalizedCid, sid || sessionIdFromMsg || undefined),
        }
      }
      return { conversationId: raw, sessionId: resolveDefaultSessionId(raw, sessionIdFromMsg) }
    }
    const isViewingRoute = (conversationId: string, sessionId?: string) =>
      currentConvIdRef.current === conversationId &&
      (!sessionId || currentSessionId === sessionId)

    if (msg.type === 'clone_context_request') {
      const req = msg as { request_id?: string; friend_imei?: string }
      const requestId = String(req.request_id || '').trim()
      const friendImei = String(req.friend_imei || '').trim()
      if (!requestId || !friendImei) return
      const conversationId = `friend_${friendImei}`
      void buildCloneQueryContext({
        friendImei,
        conversationId,
      }).then((ctx) => {
        wsSendRef.current({
          type: 'clone_context_response',
          request_id: requestId,
          friend_imei: friendImei,
          clone_query_context: ctx || '',
        })
      }).catch(() => {
        wsSendRef.current({
          type: 'clone_context_response',
          request_id: requestId,
          friend_imei: friendImei,
          clone_query_context: '',
        })
      })
      return
    }
    if (msg.type === 'mobile_tool_ack' || msg.type === 'mobile_tool_result') {
      const requestId = String((msg as { request_id?: string }).request_id || '').trim()
      if (requestId) {
        const waiter = mobileProbeWaitersRef.current.get(requestId)
        if (waiter) {
          const payload = ((msg as { payload?: unknown }).payload && typeof (msg as { payload?: unknown }).payload === 'object'
            ? (msg as { payload: Record<string, unknown> }).payload
            : {}) as Record<string, unknown>
          if (msg.type === 'mobile_tool_ack') {
            const accepted = payload.accepted === true
            mobileProbeWaitersRef.current.delete(requestId)
            clearTimeout(waiter.timer)
            waiter.resolve({ ok: accepted, error: accepted ? undefined : '手机拒绝了 gui_task 探针' })
            return
          }
          const tool = String(payload.tool || '')
          if (tool === 'device.gui_task_probe') {
            const ok = payload.ok === true
            const errObj = (payload.error && typeof payload.error === 'object') ? payload.error as Record<string, unknown> : null
            const errorMsg = errObj ? String(errObj.message || '') : ''
            mobileProbeWaitersRef.current.delete(requestId)
            clearTimeout(waiter.timer)
            waiter.resolve({ ok, error: ok ? undefined : (errorMsg || '手机未通过 gui_task 探针') })
            return
          }
        }
      }
    }

    if (msg.type === 'friend_message_ack') {
      const mid = (msg as { message_id?: string }).message_id
      if (mid) {
        const w = friendWsWaitersRef.current.get(mid)
        if (w) {
          friendWsWaitersRef.current.delete(mid)
          w.resolve({
            message_id: mid,
            target_online: (msg as { target_online?: boolean }).target_online === true,
          })
        }
      }
      return
    }
    if (msg.type === 'friend_message_error') {
      const mid = (msg as { message_id?: string }).message_id
      const errText = msg.content || '发送失败'
      if (mid) {
        const w = friendWsWaitersRef.current.get(mid)
        if (w) {
          friendWsWaitersRef.current.delete(mid)
          w.reject(new Error(errText))
        }
      }
      return
    }
    if (msg.type === 'custom_assistant_active_session') {
      applyRemoteActiveSessionRef.current?.(
        msg as { conversation_id?: string; base_url?: string; active_session_id?: string; updated_at?: number }
      )
      return
    }
    if (msg.type === 'execute_result') {
      const content = msg.content || ''
      const ts = msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now()
      const route = parseConversationRoute((msg as { conversation_id?: string }).conversation_id)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const msgId = (msg as { message_id?: string }).message_id
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      ensureIncomingSessionExists(targetConvId, targetSessionId, ts)
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(content) || toAssistantSharePreview(content) || content.slice(0, 50), ts, { isFromMe: false, isViewing })
      const stableId = msgId || `exec_${ts}_${content.slice(0, 32)}`
      const m: Message = {
        id: stableId,
        sender: msg.sender || '小助手',
        content,
        type: 'assistant',
        timestamp: ts,
      }
      if (!isViewing) {
        appendMessageToStorage(targetConvId, m, targetSessionId ?? undefined)
      }
      if (isViewing) {
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (targetSessionId && currentSessionId !== targetSessionId) return prev
          const isDup = prev.some((x) => x.id === stableId || (x.type === 'assistant' && x.content === content && Math.abs(x.timestamp - ts) < 5000))
          if (isDup) return prev
          return [...prev, m]
        })
      }
      setLoading(false)
      /** 群组管理小助手跟进：若该执行由群组管理者触发，则调用群组管理者判断任务完成情况并总结/继续调度 */
      if (msgId && targetConvId?.startsWith('group_')) {
        const pending = pendingGroupManagerFollowUpRef.current.get(msgId)
        if (pending) {
          pendingGroupManagerFollowUpRef.current.delete(msgId)
          const feedbackMsg = buildExecutionFeedbackMessage(
            pending.userQuery,
            pending.executedAssistant.name,
            pending.executedCommand,
            content,
            pending.round,
            pending.assistants,
            { members: pending.members, senderName: pending.senderName }
          )
          const groupId = toServerGroupId(targetConvId)
          const gmMsgId = uuidv4()
          const gmMsg: Message = { id: gmMsgId, sender: pending.groupManagerName, content: '', type: 'assistant', timestamp: Date.now() }
          if (currentConvIdRef.current === targetConvId) {
            setMessages((prev) => [...prev, gmMsg])
          }
          /** 群组管理小助手使用统一 threadId，不包含 imei，确保同一群组所有用户共享同一上下文 */
          const threadId = `group_${targetConvId}_${pending.groupManagerId}`
          const pendingGroupManagerRolePrompt = pending.assistantConfigs?.[pending.groupManagerId]?.rolePrompt
          const gmFeedbackQuery = injectGroupManagerIdentityLine(feedbackMsg, { groupName: conversation?.name })
          sendGroupManagerChatViaPoolOrStream(
            {
              threadId,
              message: injectGroupAssistantRolePrompt(gmFeedbackQuery, pendingGroupManagerRolePrompt),
              images: [],
              imei: imei ?? undefined,
              baseUrl: pending.groupManagerBaseUrl,
              isGroupManager: true,
            },
            {
              onDelta: (delta) => {
                if (currentConvIdRef.current === targetConvId) {
                  setMessages((prev) => prev.map((ma) => (ma.id === gmMsgId ? { ...ma, content: ma.content + delta } : ma)))
                }
              },
            }
          )
            .then(({ fullText }) => {
              const stripped = stripNeedExecutionGuide(fullText) || fullText
              appendMessageToStorage(targetConvId, { ...gmMsg, content: stripped })
              if (imei && groupId) {
                sendAssistantGroupMessage(imei, groupId, stripped, pending.groupManagerName, pending.groupManagerId).catch(() => {})
              }
              const mention = parseGroupManagerMention(stripped, pending.assistants)
              if (mention && mention.assistant.id !== pending.groupManagerId && pending.round < MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS) {
                const target = mention.assistant
                const cmd = mention.command
                const targetCustom = resolveGroupAssistantConfig(target.id, target.name, pending.assistantConfigs)
                if (target.id === 'assistant') {
                  const assistantRolePrompt = getGroupAssistantRolePrompt('assistant', pending.assistantConfigs)
                  const wrappedExecuteQuery = injectGroupAssistantRolePrompt(cmd, assistantRolePrompt)
                  sendExecuteCommand(imei!, wrappedExecuteQuery, sessionUuidRef.current, undefined, targetConvId).then((res) => {
                    if (res.message_id) {
                      pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                        ...pending,
                        executedAssistant: target,
                        executedCommand: cmd,
                        round: pending.round + 1,
                      })
                    }
                  }).catch(() => {})
                } else if (targetCustom && (hasExecutionMobile(targetCustom) || hasExecutionPc(targetCustom))) {
                  const execUuid = `group_${targetConvId}_${target.id}_${Date.now()}`
                  const chatSummary = cmd.slice(0, 80) || undefined
                  const pendingCfg = (pending.assistantConfigs?.[target.id] ?? {}) as { creator_nickname?: string; creator_imei?: string }
                  const pendingExecQuery = injectTopoclawIdentityLine(cmd, {
                    assistantId: target.id,
                    assistantBaseUrl: targetCustom.baseUrl,
                    creatorNickname: pendingCfg.creator_nickname,
                    creatorImei: pendingCfg.creator_imei,
                  })
                  const execTargetImei = resolveGroupAssistantOwnerImei(target.id, pendingCfg.creator_imei)
                  sendExecuteForAssistant(execTargetImei, pendingExecQuery, execUuid, targetCustom.baseUrl, targetConvId, chatSummary).then((res) => {
                    if (res.message_id) {
                      pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                        ...pending,
                        executedAssistant: target,
                        executedCommand: cmd,
                        round: pending.round + 1,
                      })
                    }
                  }).catch(() => {})
                }
              }
            })
            .catch(() => {})
        }
      }
      return
    }
    if (msg.type === 'mobile_execute_pc_command') {
      const content = (msg as { query?: string }).query ?? ''
      const ts = Date.now()
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || ''
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const msgId = (msg as { message_id?: string }).message_id
      const assistantName = (targetConvId ? getCustomAssistantById(targetConvId) : null)?.name ?? '小助手'
      if (!targetConvId || !content) return
      ensureIncomingSessionExists(targetConvId, targetSessionId, ts)
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(content) || toAssistantSharePreview(content) || content.slice(0, 50), ts, { isFromMe: true, isViewing })
      const userMsgId = msgId || uuidv4()
      const assistantMsgId = `${userMsgId}_pc_exec`
      if (!isViewing) {
        appendMessageToStorage(
          targetConvId,
          { id: assistantMsgId, sender: assistantName, content: '正在思考…', type: 'assistant', timestamp: ts },
          targetSessionId ?? undefined
        )
      }
      if (isViewing) {
        setIsMobileTaskRunning(true)
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (targetSessionId && currentSessionId !== targetSessionId) return prev
          if (prev.some((x) => x.id === assistantMsgId)) return prev
          return [
            ...prev,
            { id: assistantMsgId, sender: assistantName, content: '正在思考…', type: 'assistant', timestamp: ts },
          ]
        })
      }
      return
    }
    if (msg.type === 'mobile_execute_pc_result') {
      const success = (msg as { success?: boolean }).success ?? false
      const content = (msg as { content?: string }).content || (msg as { error?: string }).error || (success ? '任务已完成' : '执行失败')
      const ts = msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now()
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || ''
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const msgId = (msg as { message_id?: string }).message_id
      const syncFileBase64 = typeof msg.file_base64 === 'string' && msg.file_base64.trim() ? msg.file_base64.trim() : undefined
      const syncFileName = (msg as { file_name?: string }).file_name || '图片.png'
      const previewText = syncFileBase64 ? (content && content !== '[图片]' ? content : '[图片]') : content
      if (!targetConvId) return
      ensureIncomingSessionExists(targetConvId, targetSessionId, ts)
      const assistantMsgId = msgId ? `${msgId}_pc_exec` : null
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(previewText) || toAssistantSharePreview(previewText) || previewText.slice(0, 50), ts, { isFromMe: false, isViewing })
      if (!isViewing) {
        const assistantName = (targetConvId ? getCustomAssistantById(targetConvId) : null)?.name ?? '小助手'
        const scopedSessionId = targetSessionId ?? undefined
        const finalMsg: Message = {
          id: assistantMsgId || uuidv4(),
          sender: assistantName,
          content: syncFileBase64 ? (content || '[图片]') : content,
          type: 'assistant',
          timestamp: ts,
          ...(syncFileBase64 ? { messageType: 'file' as const, fileBase64: syncFileBase64, fileName: syncFileName } : {}),
        }
        if (assistantMsgId) {
          const list = loadMessages(targetConvId, scopedSessionId) as Message[]
          let replaced = false
          const merged = list.map((m) => {
            if (m.id !== assistantMsgId) return m
            replaced = true
            return { ...m, ...finalMsg, id: assistantMsgId }
          })
          if (replaced) {
            saveMessages(targetConvId, merged, scopedSessionId)
          } else {
            appendMessageToStorage(targetConvId, finalMsg, scopedSessionId)
          }
        } else {
          appendMessageToStorage(targetConvId, finalMsg, scopedSessionId)
        }
      }
      if (isViewing) {
        const assistantName = (targetConvId ? getCustomAssistantById(targetConvId) : null)?.name ?? '小助手'
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (targetSessionId && currentSessionId !== targetSessionId) return prev
          const nextContent = syncFileBase64 ? (content || '[图片]') : content
          if (assistantMsgId) {
            let found = false
            const mapped = prev.map((m) => {
              if (m.id !== assistantMsgId) return m
              found = true
              return {
                ...m,
                content: nextContent,
                ...(syncFileBase64 ? { messageType: 'file' as const, fileBase64: syncFileBase64, fileName: syncFileName } : {}),
              }
            })
            if (found) return mapped
          }
          // 占位消息不存在时，直接追加正式回复，避免“日志有回复但界面不显示”
          return [
            ...prev,
            {
              id: assistantMsgId || uuidv4(),
              sender: assistantName,
              content: nextContent,
              type: 'assistant' as const,
              timestamp: ts,
              ...(syncFileBase64 ? { messageType: 'file' as const, fileBase64: syncFileBase64, fileName: syncFileName } : {}),
            },
          ]
        })
      }
      setLoading(false)
      setIsMobileTaskRunning(false)
      return
    }
    if (msg.type === 'mobile_execute_pc_thinking') {
      const thinking = (msg as { thinking_content?: string }).thinking_content || ''
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || ''
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const msgId = (msg as { message_id?: string }).message_id
      if (!thinking || !targetConvId || !msgId) return
      const assistantMsgId = `${msgId}_pc_exec`
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      if (!isViewing) return
      setMessages((prev) => {
        if (currentConvIdRef.current !== targetConvId) return prev
        if (targetSessionId && currentSessionId !== targetSessionId) return prev
        return prev.map((m) => (m.id === assistantMsgId ? { ...m, thinkingContents: [thinking] } : m))
      })
      return
    }
    if (msg.type === 'assistant_thinking_sync') {
      const thinking = (msg as { thinking_content?: string }).thinking_content || ''
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || ''
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const msgId = (msg as { message_id?: string }).message_id
      if (!thinking || !targetConvId || !msgId) return
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      if (!isViewing) return
      setMessages((prev) => {
        if (currentConvIdRef.current !== targetConvId) return prev
        if (targetSessionId && currentSessionId !== targetSessionId) return prev
        return prev.map((m) => (m.id === msgId ? { ...m, thinkingContents: [thinking] } : m))
      })
      return
    }
    if (msg.type === 'assistant_stop_task') {
      const targetConvId = (msg as { conversation_id?: string }).conversation_id || ''
      const isViewing = currentConvIdRef.current === targetConvId
      if (isViewing || !targetConvId) {
        setLoading(false)
        setIsMobileTaskRunning(false)
        setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '对端已停止任务', type: 'system' as const, timestamp: Date.now() }])
      }
      return
    }
    if (msg.type === 'assistant_user_message') {
      const content = (msg as { content?: string }).content ?? ''
      const ts = msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now()
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || CONVERSATION_ID_ASSISTANT
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const syncFileBase64 = typeof msg.file_base64 === 'string' && msg.file_base64.trim() ? msg.file_base64.trim() : undefined
      const syncFileName = (msg as { file_name?: string }).file_name || '图片.png'
      const previewText = syncFileBase64 ? (content && content !== '[图片]' ? content : '[图片]') : content
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      ensureIncomingSessionExists(targetConvId, targetSessionId, ts)
      onUpdateLastMessage?.(
        targetConvId,
        toSkillSharePreview(previewText) || toAssistantSharePreview(previewText) || previewText.slice(0, 50),
        ts,
        { isFromMe: true, isViewing },
      )
      const msgId = (msg as { message_id?: string }).message_id
      const userMsg: Message = {
        id: msgId || uuidv4(),
        sender: (msg as { sender?: string }).sender ?? '我',
        content: syncFileBase64 ? (content || '[图片]') : content,
        type: 'user',
        timestamp: ts,
        ...(syncFileBase64 ? { messageType: 'file' as const, fileBase64: syncFileBase64, fileName: syncFileName } : {}),
      }
      // 无论当前是否正在查看该会话，都先持久化到目标会话桶，避免切换会话后消息“看起来丢失”
      appendMessageToStorage(targetConvId, userMsg, targetSessionId ?? undefined)
      if (!isViewing) {
        return
      }
      setMessages((prev) => {
        if (currentConvIdRef.current !== targetConvId) return prev
        if (targetSessionId && currentSessionId !== targetSessionId) return prev
        if (prev.some((x) => x.id === userMsg.id)) return prev
        return [...prev, userMsg]
      })
      return
    }
    if (msg.type === 'assistant_sync_message') {
      const s = (msg as { sender?: string }).sender ?? '系统'
      const isOwnerFeedback = (msg as { is_owner_feedback?: boolean }).is_owner_feedback === true
      const senderDisplay = isOwnerFeedback && s !== '系统' && !s.includes('反馈') ? `${s}（反馈）` : s
      const content = (msg as { content?: string }).content ?? ''
      const ts = msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now()
      const rawConvId = (msg as { conversation_id?: string }).conversation_id || CONVERSATION_ID_ASSISTANT
      const sessionIdFromMsg = (msg as { session_id?: string }).session_id
      const route = parseConversationRoute(rawConvId, sessionIdFromMsg)
      const targetConvId = route.conversationId
      const targetSessionId = route.sessionId
      const isViewing = isViewingRoute(targetConvId, targetSessionId)
      const currentSessionIdNow = currentSessionIdRef.current
      const isSameConversationOpen = currentConvIdRef.current === targetConvId
      const shouldMirrorOwnerFeedbackInView =
        isOwnerFeedback &&
        isSameConversationOpen &&
        !!targetSessionId &&
        !!currentSessionIdNow &&
        currentSessionIdNow !== targetSessionId
      const syncFileBase64 = typeof msg.file_base64 === 'string' && msg.file_base64.trim() ? msg.file_base64.trim() : undefined
      const syncFileName = (msg as { file_name?: string }).file_name || '图片.png'
      const previewText = syncFileBase64 ? (content && content !== '[图片]' ? content : '[图片]') : content
      ensureIncomingSessionExists(targetConvId, targetSessionId, ts)
      onUpdateLastMessage?.(
        targetConvId,
        toSkillSharePreview(previewText) || toAssistantSharePreview(previewText) || previewText.slice(0, 50),
        ts,
        { isFromMe: senderDisplay === '我' || senderDisplay === '用户自己', isViewing },
      )
      const syncMsg: Message = {
        id: (msg as { message_id?: string }).message_id ?? uuidv4(),
        sender: senderDisplay,
        content: syncFileBase64 ? (content || '[图片]') : content,
        type: senderDisplay === '系统' ? 'system' : 'assistant',
        timestamp: ts,
        ...(isOwnerFeedback ? { messageSource: 'assistant' as Message['messageSource'] } : {}),
        ...(syncFileBase64 ? { messageType: 'file' as const, fileBase64: syncFileBase64, fileName: syncFileName } : {}),
      }
      // 无论当前是否正在查看该会话，都先持久化到目标会话桶，避免切换会话后消息“看起来丢失”
      appendMessageToStorage(targetConvId, syncMsg, targetSessionId ?? undefined)
      if (!isViewing) {
        if (shouldMirrorOwnerFeedbackInView) {
          // owner_feedback 已按后端 session 精确落库；这里额外镜像到当前可见面板，避免“已收到但看不到”。
          setMessages((prev) => {
            if (currentConvIdRef.current !== targetConvId) return prev
            if (prev.some((x) => x.id === syncMsg.id)) return prev
            return [...prev, syncMsg]
          })
        }
        if (syncMsg.type === 'assistant') {
          setLoading(false)
          setIsMobileTaskRunning(false)
        }
        return
      }
      if (syncMsg.type === 'assistant') {
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (targetSessionId && currentSessionId !== targetSessionId) return prev
          const recent = prev.slice(-5)
          const isDup = recent.some((x) => x.type === 'assistant' && x.content === content && Math.abs(x.timestamp - ts) < 5000)
          if (isDup) return prev
          return [...prev, syncMsg]
        })
        setLoading(false)
        setIsMobileTaskRunning(false)
      } else {
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (targetSessionId && currentSessionId !== targetSessionId) return prev
          return [...prev, syncMsg]
        })
      }
      return
    }
    if (msg.type === 'cross_device_message') {
      const mt = (msg.message_type || 'text') as string
      if (mt === 'gui_task_probe_ack') {
        const raw = String(msg.content || '').trim()
        let requestId = ''
        let ok = true
        if (raw) {
          try {
            const parsed = JSON.parse(raw) as { request_id?: string; ok?: boolean; error?: string }
            requestId = String(parsed.request_id || '').trim()
            ok = parsed.ok !== false
          } catch {
            requestId = raw
            ok = true
          }
        }
        if (requestId) {
          const waiter = mobileProbeWaitersRef.current.get(requestId)
          if (waiter) {
            mobileProbeWaitersRef.current.delete(requestId)
            clearTimeout(waiter.timer)
            waiter.resolve({ ok, error: ok ? undefined : '手机未通过 gui_task 探针' })
          }
        }
        // 探针回包只用于状态检测，不展示到“我的电脑”聊天流
        return
      }
      const rawB64 = msg.file_base64 || msg.imageBase64
      const b64 = typeof rawB64 === 'string' && rawB64.trim().length > 0 ? rawB64.trim() : undefined
      const isMedia = mt === 'image' || mt === 'file' || !!b64
      const text = (msg.content ?? '').trim()
      const content = !isMedia ? (msg.content ?? '') : mt === 'file' ? text || `[文件] ${msg.file_name || '文件'}` : text || '[图片]'
      const ts = msg.timestamp ? new Date(msg.timestamp).getTime() : Date.now()
      const targetConvId = CONVERSATION_ID_ME
      const isViewing = currentConvIdRef.current === targetConvId
      const m: Message = {
        id: msg.message_id || uuidv4(),
        sender: msg.sender || '我的手机',
        content,
        type: 'assistant',
        timestamp: ts,
        ...(b64
          ? { messageType: 'file' as const, fileBase64: b64, fileName: msg.file_name || '图片.png' }
          : {}),
      }
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(content) || toAssistantSharePreview(content) || content.slice(0, 50), ts, { isFromMe: false, isViewing })
      if (!isViewing) {
        appendMessageToStorage(targetConvId, m)
        return
      }
      setMessages((prev) => {
        if (currentConvIdRef.current !== targetConvId) return prev
        return [...prev, m]
      })
      return
    }
    const gm = msg as {
      type?: string
      message_id?: string
      groupId?: string
      group_id?: string
      conversation_id?: string
      conversationId?: string
      senderImei?: string
      sender_imei?: string
      sender?: string
      sender_label?: string
      content?: string
      message_type?: string
      timestamp?: string
      imageBase64?: string
      image_base64?: string
      is_assistant_reply?: boolean
      assistant_id?: string
      is_clone_reply?: boolean
      clone_owner_imei?: string
      clone_origin?: string
      sender_origin?: string
    }
    let incomingGroupIdRaw =
      (typeof gm.groupId === 'string' && gm.groupId.trim())
      || (typeof gm.group_id === 'string' && gm.group_id.trim())
      || (
        (() => {
          const cidRaw =
            (typeof gm.conversation_id === 'string' && gm.conversation_id.trim())
            || (typeof gm.conversationId === 'string' && gm.conversationId.trim())
            || ''
          const cid = normalizeIncomingConversationId(cidRaw)
          if (!cid) return ''
          if (!cid.startsWith('group_')) return ''
          return cid
        })()
      )
      || ''
    if (!incomingGroupIdRaw && gm.type === 'group_message' && conversation?.type === 'group') {
      // 某些链路可能只携带 conversation 上下文而未显式给 groupId，兜底到当前会话。
      incomingGroupIdRaw = conversation.id || currentConvIdRef.current || ''
    }
    if (gm.type === 'group_message' && incomingGroupIdRaw) {
      const incomingGroupId = String(incomingGroupIdRaw).trim()
      const rawGroupId = normalizeGroupRawId(incomingGroupId)
      const canonicalGroupConvId = toCanonicalGroupConversationId(incomingGroupId) || normalizeIncomingConversationId(incomingGroupId)
      const idMatches = (id: string | undefined) => {
        if (!id) return false
        const raw = normalizeGroupRawId(id)
        return !!raw && raw === rawGroupId
      }
      const targetConvId =
        [currentConvIdRef.current ?? undefined, conversation?.id, messagesOwnerRef.current ?? undefined].find((id) => idMatches(id))
        ?? canonicalGroupConvId
      const isViewing =
        idMatches(currentConvIdRef.current ?? undefined) ||
        idMatches(conversation?.id) ||
        idMatches(messagesOwnerRef.current ?? undefined)
      const content = gm.message_type === 'file' ? '[图片]' : (gm.content ?? '')
      const ts = gm.timestamp ? new Date(gm.timestamp).getTime() : Date.now()
      const senderImei =
        (typeof gm.senderImei === 'string' && gm.senderImei.trim())
        || (typeof gm.sender_imei === 'string' && gm.sender_imei.trim())
        || ''
      const myImei = imei ? String(imei).trim() : ''
      const assistantIdFromPayload = typeof gm.assistant_id === 'string' ? gm.assistant_id.trim() : ''
      const assistantIdFromSender =
        (conversation?.assistants ?? []).some((as) => as.id === senderImei) ? senderImei : ''
      const assistantIdentityId = assistantIdFromPayload || assistantIdFromSender
      // 自动执行小助手回复永远不当作「自己发的」处理，参考 Android 的 isAssistantReply 逻辑
      const isAssistantReply = !!assistantIdentityId || gm.sender === '自动执行小助手' || gm.is_assistant_reply === true
      const senderLabel = (typeof gm.sender_label === 'string' && gm.sender_label.trim())
        ? gm.sender_label.trim()
        : ((typeof gm.sender === 'string' && gm.sender.trim()) ? gm.sender.trim() : '')
      const cloneOriginRaw = gm.clone_origin
      const isCloneReply =
        gm.is_clone_reply === true
        || (typeof cloneOriginRaw === 'string' && cloneOriginRaw.trim().toLowerCase() === 'digital_clone')
      const cloneOwnerRaw = gm.clone_owner_imei
      const cloneOwnerImei = typeof cloneOwnerRaw === 'string' && cloneOwnerRaw.trim()
        ? cloneOwnerRaw.trim()
        : senderImei
      const isMyClone = isCloneReply && !!cloneOwnerImei && !!myImei && cloneOwnerImei === myImei
      const senderOrigin = typeof gm.sender_origin === 'string' ? gm.sender_origin.trim() : ''
      const isFromMyOtherDevice = !isAssistantReply && !isCloneReply && !!myImei && !!senderImei && senderImei === myImei && senderOrigin === 'mobile'
      const isFromMe = (isAssistantReply || isCloneReply) ? false : (!!myImei && !!senderImei && senderImei === myImei && !isFromMyOtherDevice)
      const senderDisplayName =
        assistantIdentityId
          ? (groupAssistantDisplayNameMap[assistantIdentityId] ?? gm.sender ?? assistantIdentityId)
          : isMyClone
            ? '我（数字分身）'
            : isCloneReply
              ? (senderLabel && !senderLabel.includes('数字分身') ? `${senderLabel}（数字分身）` : (senderLabel || '数字分身'))
          : !isAssistantReply && (!gm.sender || gm.sender === '群成员')
          ? getGroupMemberDisplayName(senderImei, gm.sender)
          : (gm.sender ?? '群成员')
      if (process.env.NODE_ENV === 'development') {
        console.log('[group_message]', {
          targetConvId,
          currentConvId: currentConvIdRef.current,
          conversationId: conversation?.id,
          isViewing,
          isFromMe,
          isAssistantReply,
          sender: gm.sender,
          senderImei: senderImei || '(empty)',
          myImei: myImei ? `${myImei.slice(0, 8)}...` : '(empty)',
          senderDisplayName,
          willDisplay: !isFromMe,
        })
      }
      const uiTreatAsFromMe = isFromMe || isMyClone || isFromMyOtherDevice
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(content) || toAssistantSharePreview(content) || content.slice(0, 50), ts, { isFromMe: uiTreatAsFromMe, isViewing })
      if (!isFromMe) {
        const isFromAssistant =
          isAssistantReply ||
          gm.sender === '自动执行小助手' ||
          !!assistantIdentityId ||
          senderImei.startsWith('custom_') ||
          senderImei.startsWith('assistant_') ||
          (targetConvId === currentConvIdRef.current && conversation?.assistants?.some((as) => as.name === gm.sender)) ||
          (targetConvId === conversation?.id && conversation?.assistants?.some((as) => as.name === gm.sender))
        const m: Message = {
          id: gm.message_id || uuidv4(),
          sender: isFromMyOtherDevice ? '我' : senderDisplayName,
          senderImei: senderImei || undefined,
          content,
          type: isFromAssistant ? 'assistant' : 'user',
          timestamp: ts,
          ...(isCloneReply ? { messageSource: (isMyClone ? 'my_clone' : 'friend_clone') as Message['messageSource'] } : {}),
          ...(isCloneReply ? { cloneOwnerImei: cloneOwnerImei || undefined } : {}),
          ...((gm.imageBase64 || gm.image_base64)
            ? { messageType: 'file' as const, fileBase64: (gm.imageBase64 || gm.image_base64) as string, fileName: '图片.png' }
            : {}),
        }
        // 群会话 id 在不同端可能出现 group_xxx / group_group_xxx / xxx 的混用，这里统一多 key 落库兜底，
        // 避免“消息到了但当前会话读不到对应 localStorage bucket”。
        const persistIds = Array.from(
          new Set([
            targetConvId,
            canonicalGroupConvId,
            toCanonicalGroupConversationId(rawGroupId),
            currentConvIdRef.current ?? '',
            conversation?.id ?? '',
            messagesOwnerRef.current ?? '',
            incomingGroupId,
            rawGroupId,
          ].filter((id) => !!id && (idMatches(id) || id === canonicalGroupConvId)))
        )
        persistIds.forEach((cid) => appendMessageToStorage(cid, m))
        triggerConversationSummary(targetConvId, conversation?.name)
        // 始终尝试 setMessages；任一 ref 匹配即视为当前会话，避免时序/格式差异导致消息需切换才显示
        setMessages((prev) => {
          const ownerMatch = idMatches(messagesOwnerRef.current ?? undefined)
          const currentMatch = idMatches(currentConvIdRef.current ?? undefined)
          const conversationMatch = idMatches(conversation?.id)
          if (!ownerMatch && !currentMatch && !conversationMatch) return prev
          if (prev.some((x) => x.id === m.id)) return prev
          const normNew = normalizeContentForDedupe(content)
          const senderName = gm.sender ?? ''
          const withinWindow = (x: Message) => Math.abs((x.timestamp || 0) - ts) < 15000
          const isExactDup = prev.some(
            (x) =>
              x.type === 'assistant' &&
              x.sender === senderName &&
              withinWindow(x) &&
              normalizeContentForDedupe(x.content || '') === normNew
          )
          if (isExactDup) return prev
          const last = prev[prev.length - 1]
          const lastNorm = last?.content ? normalizeContentForDedupe(last.content) : ''
          const isStreamingPlaceholder =
            last?.type === 'assistant' &&
            last.sender === senderName &&
            withinWindow(last) &&
            (lastNorm === '' || (normNew.length >= 10 && (normNew.startsWith(lastNorm) || lastNorm.startsWith(normNew))))
          if (isStreamingPlaceholder) return prev
          return [...prev, m]
        })
      }
      return
    }
    const fsm = msg as {
      type?: string
      conversation_id?: string
      is_from_me?: boolean
      sender_imei?: string
      sender?: string
      sender_label?: string
      content?: string
      message_id?: string
      message_type?: string
      timestamp?: string
      imageBase64?: string
    }
    if (fsm.type === 'friend_sync_message' && fsm.conversation_id) {
      const img = typeof fsm.imageBase64 === 'string' ? fsm.imageBase64.trim() : ''
      const isImageMsg = !!img || fsm.message_type === 'image'
      const textPart = (fsm.content ?? '').trim()
      const content = isImageMsg
        ? textPart || '[图片]'
        : fsm.message_type === 'file'
          ? '[文件]'
          : (fsm.content ?? '')
      const ts = fsm.timestamp ? new Date(fsm.timestamp).getTime() : Date.now()
      const targetConvId = fsm.conversation_id
      const isViewing = currentConvIdRef.current === targetConvId
      const senderImei = typeof fsm.sender_imei === 'string' ? fsm.sender_imei.trim() : ''
      const myImei = imei ? String(imei).trim() : ''
      const isFromMe = fsm.is_from_me === false ? false : (fsm.is_from_me === true ? true : (!!myImei && !!senderImei && senderImei === myImei))
      const senderLabel = (typeof fsm.sender_label === 'string' && fsm.sender_label.trim())
        ? fsm.sender_label.trim()
        : ((typeof fsm.sender === 'string' && fsm.sender.trim()) ? fsm.sender.trim() : '')
      const friendDisplayName =
        senderLabel || (isViewing && conversation?.id === targetConvId ? (conversation?.name ?? '好友') : '好友')
      const m: Message = {
        id: fsm.message_id ?? uuidv4(),
        sender: isFromMe ? '我' : friendDisplayName,
        senderImei: senderImei || undefined,
        content,
        type: 'user',
        timestamp: ts,
        messageSource: isFromMe ? 'user' : 'friend',
        ...(img ? { messageType: 'file' as const, fileBase64: img, fileName: '图片.png' } : {}),
      }
      const cloneOriginRaw = (fsm as { clone_origin?: string }).clone_origin
      const isCloneReply =
        (fsm as { is_clone_reply?: boolean }).is_clone_reply === true
        || (typeof cloneOriginRaw === 'string' && cloneOriginRaw.trim().toLowerCase() === 'digital_clone')
      if (isCloneReply) {
        const cloneOwnerRaw = (fsm as { clone_owner_imei?: string }).clone_owner_imei
        const cloneOwnerImei = typeof cloneOwnerRaw === 'string' && cloneOwnerRaw.trim()
          ? cloneOwnerRaw.trim()
          : senderImei
        const isMyClone = !!cloneOwnerImei && !!myImei && cloneOwnerImei === myImei
        m.messageSource = isMyClone ? 'my_clone' : 'friend_clone'
        m.cloneOwnerImei = cloneOwnerImei || undefined
        if (isMyClone) {
          m.sender = '我（数字分身）'
        } else if (m.sender && !m.sender.includes('数字分身')) {
          m.sender = `${m.sender}（数字分身）`
        }
      }
      const uiTreatAsFromMe = isFromMe || m.messageSource === 'my_clone'
      onUpdateLastMessage?.(targetConvId, toSkillSharePreview(content) || toAssistantSharePreview(content) || content.slice(0, 50), ts, { isFromMe: uiTreatAsFromMe, isViewing })
      appendMessageToStorage(targetConvId, m)
      triggerConversationSummary(targetConvId, conversation?.name)
      if (isViewing) {
        setMessages((prev) => {
          if (currentConvIdRef.current !== targetConvId) return prev
          if (fsm.message_id && prev.some((x) => x.id === fsm.message_id)) return prev
          return [...prev, m]
        })
      }
    }
  }, [conversation?.id, conversation?.name, conversation?.assistants, conversation?.baseUrl, imei, onUpdateLastMessage, currentSessionId, getGroupMemberDisplayName, triggerConversationSummary, customAssistant, isMultiSessionCustom, buildCloneQueryContext]))

  wsSendRef.current = wsSend

  useEffect(() => {
    wsSendRef.current = wsSend
  }, [wsSend])

  useEffect(() => {
    return () => {
      if (modelAlignRetryTimerRef.current) {
        clearTimeout(modelAlignRetryTimerRef.current)
        modelAlignRetryTimerRef.current = null
      }
      mobileProbeWaitersRef.current.forEach((waiter) => {
        clearTimeout(waiter.timer)
        waiter.resolve({ ok: false, error: '状态检测已取消' })
      })
      mobileProbeWaitersRef.current.clear()
    }
  }, [])

  useEffect(() => {
    if (conversation?.id) onConversationViewed?.(conversation.id)
  }, [conversation?.id, onConversationViewed])

  const showModelAlignRetryModal = (hint?: string) => {
    const text = String(hint || '').trim() || '模型切换中，请稍等几秒再试'
    setModelAlignRetryHint(text)
    setModelAlignRetryModalOpen(true)
    if (modelAlignRetryTimerRef.current) {
      clearTimeout(modelAlignRetryTimerRef.current)
      modelAlignRetryTimerRef.current = null
    }
    modelAlignRetryTimerRef.current = setTimeout(() => {
      setModelAlignRetryModalOpen(false)
      modelAlignRetryTimerRef.current = null
    }, 2800)
  }

  useEffect(() => {
    if (!builtinChatSlot) {
      setBuiltinModelSwitchErr('')
      setBuiltinProfilesLoading(false)
      return
    }
    let cancelled = false
    setBuiltinProfilesLoading(true)
    getBuiltinModelProfiles().then(async (r) => {
      if (cancelled) return
      if (!r.ok) {
        setBuiltinModelSwitchErr(r.error || '读取模型配置失败')
        return
      }
      let startupSyncWarn = ''
      let runtimeActiveNonGui = r.activeNonGuiModel
      let runtimeActiveGui = r.activeGuiModel
      if (builtinChatSlot === 'topoclaw' && chatBaseUrl) {
        try {
          const runtimeRes = await getBuiltinModelProfilesViaPool(chatBaseUrl, {
            agentId: builtinRuntimeAgentId,
          })
          if (runtimeRes.ok) {
            runtimeActiveNonGui = runtimeRes.active_non_gui_model || runtimeActiveNonGui
            runtimeActiveGui = runtimeRes.active_gui_model || runtimeActiveGui
          }
        } catch {
          startupSyncWarn = '运行时模型读取失败，当前展示可能仅为本地配置值'
        }
      }
      setBuiltinProfileLists({
        nonGuiProfiles: r.nonGuiProfiles,
        guiProfiles: r.guiProfiles,
      })
      if (builtinChatSlot === 'topoclaw') {
        const nonGuiModel = r.nonGuiProfiles.some((p) => p.model === runtimeActiveNonGui)
          ? runtimeActiveNonGui
          : r.activeNonGuiModel
        const guiModel = r.guiProfiles.some((p) => p.model === runtimeActiveGui)
          ? runtimeActiveGui
          : r.activeGuiModel
        setBuiltinSelNonGui(nonGuiModel)
        setBuiltinSelGui(guiModel)
        // 保持“前端展示即实际生效”：
        // 若会话记忆模型与当前 active 不一致，立即写回 config（由主进程触发对应实例重启）。
        if (nonGuiModel !== r.activeNonGuiModel || guiModel !== r.activeGuiModel) {
          saveBuiltinModelProfiles({
            activeNonGuiModel: nonGuiModel,
            activeGuiModel: guiModel,
          }).then((res) => {
            if (!res.ok && !cancelled) {
              setBuiltinModelSwitchErr(res.error || '同步前端模型选择失败')
            }
          }).catch((e) => {
            if (!cancelled) setBuiltinModelSwitchErr(String(e))
          })
        }
        if (chatBaseUrl && nonGuiModel !== runtimeActiveNonGui) {
          const ng = r.nonGuiProfiles.find((p) => p.model === nonGuiModel)
          if (ng) {
            try {
              const hot = await setLlmProviderViaPool(chatBaseUrl, {
                model: ng.model,
                api_base: ng.apiBase,
                api_key: ng.apiKey,
              })
              if (!hot.ok) {
                const detail = hot.errors.map((e) => `${e.agent_id}: ${e.error}`).join('; ')
                startupSyncWarn = hot.reason || detail || '启动时 Chat 模型热切换失败'
              }
            } catch (e) {
              startupSyncWarn = `启动时 Chat 模型热切换失败：${String(e)}`
            }
          }
        }
        if (chatBaseUrl && guiModel !== runtimeActiveGui) {
          const gui = r.guiProfiles.find((p) => p.model === guiModel)
          if (gui) {
            try {
              const hot = await setGuiProviderViaPool(chatBaseUrl, {
                model: gui.model,
                api_base: gui.apiBase,
                api_key: gui.apiKey,
              })
              if (!hot.ok) {
                const detail = hot.errors.map((e) => `${e.target}: ${e.error}`).join('; ')
                startupSyncWarn = hot.reason || detail || '启动时 GUI 模型热切换失败'
              }
            } catch (e) {
              startupSyncWarn = `启动时 GUI 模型热切换失败：${String(e)}`
            }
          }
        }
      } else {
        const gmModel = r.activeGroupManagerModel
        setBuiltinSelGm(gmModel)
        if (gmModel !== r.activeGroupManagerModel) {
          saveBuiltinModelProfiles({
            activeGroupManagerModel: gmModel,
          }).then((res) => {
            if (!res.ok && !cancelled) {
              setBuiltinModelSwitchErr(res.error || '同步 GroupManager 模型选择失败')
            }
          }).catch((e) => {
            if (!cancelled) setBuiltinModelSwitchErr(String(e))
          })
        }
        if (chatBaseUrl && gmModel !== r.activeGroupManagerModel) {
          const gm = r.nonGuiProfiles.find((p) => p.model === gmModel)
          if (gm) {
            setLlmProviderViaPool(chatBaseUrl, {
              model: gm.model,
              api_base: gm.apiBase,
              api_key: gm.apiKey,
            }).catch(() => {})
          }
        }
      }
      setBuiltinModelSwitchErr(startupSyncWarn)
    }).catch((e) => {
      if (!cancelled) setBuiltinModelSwitchErr(String(e))
    }).finally(() => {
      if (!cancelled) setBuiltinProfilesLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [builtinChatSlot, conversation?.id, builtinModelSessionKey, chatBaseUrl, builtinRuntimeAgentId])

  const handleBuiltinTopoNonGuiChange = useCallback(
    async (value: string) => {
      setBuiltinApplyingModel(true)
      setBuiltinModelSwitchErr('')
      try {
        const ng = builtinProfileLists?.nonGuiProfiles.find((p) => p.model === value)
        if (!ng) {
          setBuiltinModelSwitchErr(`未找到 chat 模型：${value}`)
          return
        }
        const saveRes = await saveBuiltinModelProfiles({
          activeNonGuiModel: value,
          activeGuiModel: builtinSelGui,
        })
        if (!saveRes.ok) {
          setBuiltinModelSwitchErr(saveRes.error || '保存模型选择失败')
          return
        }
        if (!chatBaseUrl) {
          setBuiltinModelSwitchErr('当前会话缺少可用服务地址')
          return
        }
        const hot = await setLlmProviderViaPool(chatBaseUrl, {
          model: ng.model,
          api_base: ng.apiBase,
          api_key: ng.apiKey,
        })
        if (!hot.ok) {
          const detail = hot.errors.map((e) => `${e.agent_id}: ${e.error}`).join('; ')
          setBuiltinModelSwitchErr(hot.reason || detail || '热切换失败')
          return
        }
        setBuiltinSelNonGui(value)
        if (builtinModelSessionKey) {
          saveBuiltinSessionModelSelection(builtinModelSessionKey, {
            nonGuiModel: value,
            guiModel: builtinSelGui,
          })
        }
      } catch (e) {
        setBuiltinModelSwitchErr(String(e))
      } finally {
        setBuiltinApplyingModel(false)
      }
    },
    [builtinProfileLists, builtinSelGui, chatBaseUrl, builtinModelSessionKey]
  )

  const handleBuiltinTopoGuiChange = useCallback(
    async (value: string) => {
      setBuiltinApplyingModel(true)
      setBuiltinModelSwitchErr('')
      try {
        const gui = builtinProfileLists?.guiProfiles.find((p) => p.model === value)
        if (!gui) {
          setBuiltinModelSwitchErr(`未找到 GUI 模型：${value}`)
          return
        }
        const saveRes = await saveBuiltinModelProfiles({
          activeNonGuiModel: builtinSelNonGui,
          activeGuiModel: value,
        })
        if (!saveRes.ok) {
          setBuiltinModelSwitchErr(saveRes.error || '保存模型选择失败')
          return
        }
        if (!chatBaseUrl) {
          setBuiltinModelSwitchErr('当前会话缺少可用服务地址')
          return
        }
        const hot = await setGuiProviderViaPool(chatBaseUrl, {
          model: gui.model,
          api_base: gui.apiBase,
          api_key: gui.apiKey,
        })
        if (!hot.ok) {
          const detail = hot.errors.map((e) => `${e.target}: ${e.error}`).join('; ')
          setBuiltinModelSwitchErr(hot.reason || detail || 'GUI 热切换失败')
          return
        }
        setBuiltinSelGui(value)
        if (builtinModelSessionKey) {
          saveBuiltinSessionModelSelection(builtinModelSessionKey, {
            nonGuiModel: builtinSelNonGui,
            guiModel: value,
          })
        }
      } catch (e) {
        setBuiltinModelSwitchErr(String(e))
      } finally {
        setBuiltinApplyingModel(false)
      }
    },
    [builtinProfileLists, builtinSelNonGui, chatBaseUrl, builtinModelSessionKey]
  )

  const handleBuiltinGmModelChange = useCallback(async (value: string) => {
    setBuiltinApplyingModel(true)
    setBuiltinModelSwitchErr('')
    try {
      const ng = builtinProfileLists?.nonGuiProfiles.find((p) => p.model === value)
      if (!ng) {
        setBuiltinModelSwitchErr(`未找到模型：${value}`)
        return
      }
      const saveRes = await saveBuiltinModelProfiles({
        activeGroupManagerModel: value,
      })
      if (!saveRes.ok) {
        setBuiltinModelSwitchErr(saveRes.error || '保存模型选择失败')
        return
      }
      if (!chatBaseUrl) {
        setBuiltinModelSwitchErr('当前会话缺少可用服务地址')
        return
      }
      const hot = await setLlmProviderViaPool(chatBaseUrl, {
        model: ng.model,
        api_base: ng.apiBase,
        api_key: ng.apiKey,
      })
      if (!hot.ok) {
        const detail = hot.errors.map((e) => `${e.agent_id}: ${e.error}`).join('; ')
        setBuiltinModelSwitchErr(hot.reason || detail || '热切换失败')
        return
      }
      setBuiltinSelGm(value)
      if (builtinModelSessionKey) {
        saveBuiltinSessionModelSelection(builtinModelSessionKey, {
          groupManagerModel: value,
        })
      }
    } catch (e) {
      setBuiltinModelSwitchErr(String(e))
    } finally {
      setBuiltinApplyingModel(false)
    }
  }, [builtinProfileLists, chatBaseUrl, builtinModelSessionKey])

  const ensureBuiltinRuntimeModelsAligned = useCallback(async (): Promise<boolean> => {
    if (builtinChatSlot !== 'topoclaw') return true
    if (!chatBaseUrl) return true
    if (!builtinProfileLists) return true
    const expectedNonGui = (builtinSelNonGui || '').trim()
    const expectedGui = (builtinSelGui || '').trim()
    if (!expectedNonGui || !expectedGui) return true
    try {
      const runtimeRes = await getBuiltinModelProfilesViaPool(chatBaseUrl, {
        agentId: builtinRuntimeAgentId,
      })
      if (!runtimeRes.ok) {
        setBuiltinModelSwitchErr('运行时模型读取失败，请稍后重试')
        return false
      }
      if (runtimeRes.active_non_gui_model !== expectedNonGui) {
        const nonGuiProfile = builtinProfileLists.nonGuiProfiles.find((p) => p.model === expectedNonGui)
        if (!nonGuiProfile) {
          setBuiltinModelSwitchErr(`未找到 chat 模型配置：${expectedNonGui}`)
          return false
        }
        const hot = await setLlmProviderViaPool(chatBaseUrl, {
          model: nonGuiProfile.model,
          api_base: nonGuiProfile.apiBase,
          api_key: nonGuiProfile.apiKey,
        })
        if (!hot.ok) {
          const detail = hot.errors.map((e) => `${e.agent_id}: ${e.error}`).join('; ')
          setBuiltinModelSwitchErr(hot.reason || detail || 'Chat 模型对齐失败')
          return false
        }
      }
      if (runtimeRes.active_gui_model !== expectedGui) {
        const guiProfile = builtinProfileLists.guiProfiles.find((p) => p.model === expectedGui)
        if (!guiProfile) {
          setBuiltinModelSwitchErr(`未找到 GUI 模型配置：${expectedGui}`)
          return false
        }
        const hot = await setGuiProviderViaPool(chatBaseUrl, {
          model: guiProfile.model,
          api_base: guiProfile.apiBase,
          api_key: guiProfile.apiKey,
        })
        if (!hot.ok) {
          const detail = hot.errors.map((e) => `${e.target}: ${e.error}`).join('; ')
          setBuiltinModelSwitchErr(hot.reason || detail || 'GUI 模型对齐失败')
          return false
        }
      }
      setBuiltinModelSwitchErr('')
      return true
    } catch (e) {
      setBuiltinModelSwitchErr(`运行时模型对齐失败：${String(e)}`)
      return false
    }
  }, [
    builtinChatSlot,
    chatBaseUrl,
    builtinProfileLists,
    builtinSelNonGui,
    builtinSelGui,
    builtinRuntimeAgentId,
  ])

  const waitGuiTaskProbeAck = useCallback((requestId: string, timeoutMs = 3500): Promise<{ ok: boolean; error?: string }> => {
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        mobileProbeWaitersRef.current.delete(requestId)
        resolve({ ok: false, error: '等待手机响应超时' })
      }, timeoutMs)
      mobileProbeWaitersRef.current.set(requestId, { resolve, timer })
    })
  }, [])

  const handleRunMobileStatusCheck = useCallback(async (options?: { source?: 'manual' | 'auto' }) => {
    if (mobileStatusCheckingRef.current) return
    const source = options?.source ?? 'manual'
    if (source === 'manual') setMobileStatusModalOpen(true)
    mobileStatusCheckingRef.current = true
    setMobileStatusChecking(true)
    setMobileOnlineStep('checking')
    setMobileGuiTaskStep('idle')
    setMobileStatusCheckHint('')
    try {
      if (!imei) {
        setMobileOnlineStep('failed')
        setMobileStatusCheckHint('未获取到当前设备 imei，无法检测')
        return
      }
      const status = await getMobileUserStatus(imei, { timeoutMs: 5000 })
      if (!status.success) {
        setMobileOnlineStep('failed')
        setMobileStatusCheckHint('手机在线状态查询失败，请稍后重试')
        return
      }
      if (!status.isOnline) {
        setMobileOnlineStep('failed')
        setMobileStatusCheckHint('手机当前不在线')
        return
      }
      setMobileOnlineStep('success')
      setMobileGuiTaskStep('checking')
      const requestId = `gui_task_probe_${uuidv4()}`
      const sent = wsSendRef.current({
        type: 'mobile_tool_invoke',
        protocol: 'mobile_tool/v1',
        request_id: requestId,
        conversation_id: conversation?.id || CONVERSATION_ID_ASSISTANT,
        payload: {
          tool: 'device.gui_task_probe',
          args: {},
        },
      })
      if (!sent) {
        setMobileGuiTaskStep('failed')
        setMobileStatusCheckHint('电脑端跨设备连接未建立，请稍后再试')
        return
      }
      const probeResult = await waitGuiTaskProbeAck(requestId)
      if (!probeResult.ok) {
        setMobileGuiTaskStep('failed')
        setMobileStatusCheckHint(probeResult.error || '手机未通过 gui_task 接收检测')
        return
      }
      setMobileGuiTaskStep('success')
      setMobileStatusCheckHint('检测通过：手机在线，且可接收 gui_task 任务')
    } catch (e) {
      setMobileStatusCheckHint(`检测失败：${String(e)}`)
      setMobileOnlineStep((prev) => (prev === 'checking' ? 'failed' : prev))
      setMobileGuiTaskStep((prev) => (prev === 'checking' ? 'failed' : prev))
    } finally {
      mobileStatusCheckingRef.current = false
      setMobileStatusChecking(false)
    }
  }, [imei, conversation?.id, waitGuiTaskProbeAck])

  useEffect(() => {
    if (mobileStatusBootCheckedRef.current) return
    if (!conversation?.id) return
    mobileStatusBootCheckedRef.current = true
    void handleRunMobileStatusCheck({ source: 'auto' })
  }, [conversation?.id, handleRunMobileStatusCheck])

  useEffect(() => {
    if (builtinChatSlot !== 'topoclaw') return
    const runAutoCheck = () => { void handleRunMobileStatusCheck({ source: 'auto' }) }
    runAutoCheck()
    const timer = window.setInterval(runAutoCheck, MOBILE_STATUS_POLL_INTERVAL_MS)
    return () => {
      window.clearInterval(timer)
    }
  }, [builtinChatSlot, conversation?.id, handleRunMobileStatusCheck])

  /** 多 session 自定义小助手用 baseUrl 作本地存储 key，避免 assistant id 云端同步后变化导致 session 丢失 */
  const sessionBaseUrl = isMultiSessionCustom ? (customAssistant?.baseUrl ?? conversation?.baseUrl) : undefined
  useEffect(() => {
    if (!conversation?.id || !isMultiSessionCustom) return
    setActiveSessionLocal(conversation.id, currentSessionId, sessionBaseUrl)
  }, [conversation?.id, currentSessionId, isMultiSessionCustom, sessionBaseUrl])
  const loadLocalMessagesForConversation = useCallback(
    (conversationId: string, sessionId?: string | null): Message[] => {
      if (isGroup) return loadMessagesForGroup(conversationId) as Message[]
      if (isMultiSessionCustom) {
        const sid = (sessionId || '').trim()
        if (!sid) return loadMessages(conversationId) as Message[]
        const scoped = loadMessages(conversationId, sid) as Message[]
        if (scoped.length > 0) return scoped
        // 兼容历史：旧版本或未携带 session_id 的消息可能落在默认桶，导致打开详情页后“看起来丢失”
        return loadMessages(conversationId) as Message[]
      }
      return loadMessages(conversationId) as Message[]
    },
    [isGroup, isMultiSessionCustom]
  )
  const patchStoredMessageById = useCallback(
    (
      conversationId: string,
      messageId: string,
      patcher: (message: Message) => Message,
      sessionId?: string
    ) => {
      const scopedSessionId = (sessionId || '').trim() || undefined
      const list = loadMessages(conversationId, scopedSessionId) as Message[]
      if (!Array.isArray(list) || list.length === 0) return
      let changed = false
      const next = list.map((m) => {
        if (m.id !== messageId) return m
        changed = true
        return patcher(m)
      })
      if (changed) {
        saveMessages(conversationId, next, scopedSessionId)
      }
    },
    []
  )

  // [Session] 调试：追踪 session 相关关键变量；多 session 时缺失 sessionBaseUrl 会导致与手机端 key 不一致，无法跨端同步
  useEffect(() => {
    if (conversation?.id && isMultiSessionCustom) {
      if (!sessionBaseUrl) {
        console.warn('[Session] ChatDetail: 多 session 但 sessionBaseUrl 为空，云端 key 将与手机端不一致，跨端同步可能失败', {
          conversationId: conversation.id,
          conversationBaseUrl: conversation.baseUrl ?? '(undefined)',
        })
      }
      console.log('[Session] ChatDetail', {
        conversationId: conversation.id,
        sessionBaseUrl: sessionBaseUrl ?? '(undefined)',
        customAssistantId: customAssistant?.id ?? '(null)',
        conversationBaseUrl: conversation.baseUrl ?? '(undefined)',
        isMultiSessionCustom,
      })
    }
  }, [conversation?.id, conversation?.baseUrl, sessionBaseUrl, customAssistant?.id, isMultiSessionCustom])

  useEffect(() => {
    applyRemoteActiveSessionRef.current = (p) => {
      if (!isMultiSessionCustom || !conversation?.id) return
      if (p.conversation_id !== conversation.id) return
      const norm = (u: string) => (u || '').trim().replace(/\/+$/, '')
      if (sessionBaseUrl && norm(p.base_url || '') !== norm(sessionBaseUrl)) return
      const ut =
        typeof p.updated_at === 'number' && !Number.isNaN(p.updated_at)
          ? p.updated_at
          : Number(p.updated_at) || 0
      if (ut <= activeSessionRemoteTsRef.current) return
      const sid = (p.active_session_id || '').trim()
      if (!sid) return
      activeSessionRemoteTsRef.current = ut
      addSession(conversation.id, { id: sid, title: '新对话', createdAt: Date.now() }, sessionBaseUrl)
      setSessions((prev) => {
        if (prev.some((s) => s.id === sid)) return prev
        return [{ id: sid, title: '新对话', createdAt: Date.now() }, ...prev].sort((a, b) => b.createdAt - a.createdAt)
      })
      setCurrentSessionId(sid)
    }
  }, [isMultiSessionCustom, conversation?.id, sessionBaseUrl])

  const postActiveSessionToServer = useCallback(
    async (sessionId: string | null) => {
      if (!sessionId || !imei || !conversation?.id || !sessionBaseUrl || shouldBlockTopoCloudTraffic) return
      try {
        const r = await setActiveSession(imei, conversation.id, sessionId, { baseUrl: sessionBaseUrl })
        if (r.success && r.updated_at != null)
          activeSessionRemoteTsRef.current = Math.max(activeSessionRemoteTsRef.current, r.updated_at)
      } catch {
        /* ignore */
      }
    },
    [imei, conversation?.id, sessionBaseUrl, shouldBlockTopoCloudTraffic]
  )

  const handleNewSession = useCallback(async () => {
    if (!conversation?.id || !isMultiSessionCustom) return
    const sessionId = uuidv4()
    const session: ChatSession = { id: sessionId, title: '新对话', createdAt: Date.now() }
    addSession(conversation.id, session, sessionBaseUrl)
    setSessions((prev) => [session, ...prev])
    setCurrentSessionId(sessionId)
    setMessages([])
    if (imei && sessionBaseUrl && !shouldBlockTopoCloudTraffic) {
      try {
        const list = loadSessions(conversation.id, sessionBaseUrl)
        const { success, sessions: synced } = await syncSessions(imei, conversation.id, list, {
          baseUrl: sessionBaseUrl,
        })
        if (success) {
          const merged = mergeSessionsByRemote(list, synced)
          if (merged.length > 0) {
            saveSessions(conversation.id, merged, sessionBaseUrl)
            setSessions(merged)
          }
        }
      } catch {
        // 同步失败不影响本地
      }
    } else if (imei && !sessionBaseUrl) {
      console.warn('[Session] handleNewSession: 跳过云端同步，sessionBaseUrl 为空会导致与手机端 key 不一致')
    }
    await postActiveSessionToServer(sessionId)
  }, [conversation?.id, isMultiSessionCustom, imei, sessionBaseUrl, postActiveSessionToServer, shouldBlockTopoCloudTraffic])

  const topoSafeModePrevRef = useRef(topoSafeModeEnabled)
  useEffect(() => {
    const prev = topoSafeModePrevRef.current
    topoSafeModePrevRef.current = topoSafeModeEnabled
    if (!prev || topoSafeModeEnabled) return
    if (!isTopoClawSafeModeTarget || !isMultiSessionCustom) return
    if (!currentSessionId || !isTopoClawSessionSealed(currentSessionId)) return
    void handleNewSession()
  }, [topoSafeModeEnabled, isTopoClawSafeModeTarget, isMultiSessionCustom, currentSessionId, handleNewSession])

  const handleSelectSession = useCallback(
    (sessionId: string) => {
      setCurrentSessionId(sessionId)
      void postActiveSessionToServer(sessionId)
    },
    [postActiveSessionToServer]
  )

  /** 右键菜单：{ x, y, sessionId } */
  const [sessionContextMenu, setSessionContextMenu] = useState<{ x: number; y: number; sessionId: string } | null>(null)
  const handleSessionContextMenu = useCallback((e: React.MouseEvent, sessionId: string) => {
    e.preventDefault()
    e.stopPropagation()
    setSessionContextMenu({ x: e.clientX, y: e.clientY, sessionId })
  }, [])
  const handleDeleteSession = useCallback(
    async (sessionId: string) => {
      if (!conversation?.id || !isMultiSessionCustom) return
      const remaining = sessions.filter((s) => s.id !== sessionId)
      if (remaining.length === 0) return // 至少保留一个会话
      removeSession(conversation.id, sessionId, sessionBaseUrl)
      removeMessagesForSession(conversation.id, sessionId)
      setSessions(remaining)
      if (currentSessionId === sessionId) {
        const nextId = remaining.length > 0 ? remaining[0].id : null
        setCurrentSessionId(nextId)
        setMessages([])
        if (nextId) void postActiveSessionToServer(nextId)
      }
      setSessionContextMenu(null)
      // 同步删除后的列表到云端，使其他端也能删除
      if (imei && sessionBaseUrl && remaining.length > 0 && !shouldBlockTopoCloudTraffic) {
        try {
          const list = loadSessions(conversation.id, sessionBaseUrl)
          const { success, sessions: synced } = await syncSessions(imei, conversation.id, list, {
            baseUrl: sessionBaseUrl,
          })
          if (success && synced.length > 0) {
            saveSessions(conversation.id, synced, sessionBaseUrl)
            setSessions(synced)
          }
        } catch {
          // 同步失败不影响本地删除
        }
      }
    },
    [conversation?.id, isMultiSessionCustom, currentSessionId, sessions, sessionBaseUrl, imei, postActiveSessionToServer, shouldBlockTopoCloudTraffic]
  )
  useEffect(() => {
    if (!sessionContextMenu) return
    const close = () => setSessionContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('contextmenu', close)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('contextmenu', close)
    }
  }, [sessionContextMenu])

  /** 对话输入框右键：剪切 / 复制 / 粘贴 / 全选 */
  const [chatInputContextMenu, setChatInputContextMenu] = useState<{ x: number; y: number } | null>(null)
  /** 消息气泡右键：复制 / 引用 / 全选 / 多选 / 转发 */
  const [messageContextMenu, setMessageContextMenu] = useState<{
    x: number
    y: number
    messageId: string
    tableCsvText?: string
    tableCsvFileName?: string
    tableEnterEdit?: () => void
  } | null>(null)
  /** 消息多选模式 */
  const [messageMultiSelectMode, setMessageMultiSelectMode] = useState(false)
  const [selectedMessageIds, setSelectedMessageIds] = useState<Set<string>>(new Set())

  const exportableMessages = useMemo(
    () => messages.filter((m) => m.type === 'user' || m.type === 'assistant'),
    [messages]
  )
  const selectedMessages = useMemo(() => {
    if (selectedMessageIds.size === 0) return []
    return exportableMessages.filter((m) => selectedMessageIds.has(m.id))
  }, [exportableMessages, selectedMessageIds])

  const closeMessageMultiSelect = useCallback(() => {
    setMessageMultiSelectMode(false)
    setSelectedMessageIds(new Set())
  }, [])

  useEffect(() => {
    setMessageContextMenu(null)
    closeMessageMultiSelect()
  }, [conversation?.id, closeMessageMultiSelect])

  const captureMessagesAsImage = useCallback(async (targetMessages: Message[]) => {
    if (!conversation || targetMessages.length === 0) return
    const ids = targetMessages.map((m) => m.id)
    const chatRoot = document.querySelector('.chat-detail .chat-messages') as HTMLDivElement | null
    if (!chatRoot) return
    const rootWidth = Math.min(Math.max(chatRoot.clientWidth - 24, 360), 920)

    const panel = document.createElement('div')
    panel.className = 'chat-detail chat-forward-image-panel'
    panel.style.width = `${rootWidth}px`

    const title = document.createElement('div')
    title.className = 'chat-forward-image-title'
    title.textContent = conversation.name || '聊天记录'
    panel.appendChild(title)

    const content = document.createElement('div')
    content.className = 'chat-messages chat-forward-image-content'

    for (const id of ids) {
      const src = chatRoot.querySelector(`[data-export-message-id="${id}"]`) as HTMLElement | null
      if (!src) continue
      const clone = src.cloneNode(true) as HTMLElement
      clone.querySelectorAll('.message-multi-check').forEach((n) => n.remove())
      clone.querySelectorAll('.message-content-selectable').forEach((n) => n.classList.remove('is-selected'))
      content.appendChild(clone)
    }

    if (!content.childElementCount) return
    panel.appendChild(content)
    document.body.appendChild(panel)

    try {
      const canvas = await html2canvas(panel, {
        backgroundColor: '#f5f7fb',
        scale: Math.max(2, window.devicePixelRatio || 1),
        useCORS: true,
      })
      const baseName = sanitizeFileName(conversation.name || 'chat')
      const ts = new Date().toISOString().replace(/[:.]/g, '-')
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob((b) => resolve(b), 'image/png'))
      if (!blob) return
      const dataUrl = canvas.toDataURL('image/png')
      return { dataUrl, blob, baseName, ts }
    } finally {
      panel.remove()
    }
  }, [conversation])

  const exportMessagesAsImage = useCallback(async (targetMessages: Message[]) => {
    const rendered = await captureMessagesAsImage(targetMessages)
    if (!rendered) return
    const { dataUrl, blob, baseName, ts } = rendered
    const copied = await copyImageFromSrc(dataUrl)
    if (copied.ok) {
      setClipboardSavedNotice(true)
      if (clipboardSavedNoticeTimerRef.current) {
        clearTimeout(clipboardSavedNoticeTimerRef.current)
        clipboardSavedNoticeTimerRef.current = null
      }
      clipboardSavedNoticeTimerRef.current = setTimeout(() => {
        setClipboardSavedNotice(false)
        clipboardSavedNoticeTimerRef.current = null
      }, 2000)
    } else {
      triggerBlobDownload(`${baseName}_${ts}.chat.png`, blob)
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: `复制长图失败，已自动下载：${copied.error || '未知错误'}`, type: 'system', timestamp: Date.now() },
      ])
    }
  }, [captureMessagesAsImage, setMessages])

  const handleMessageContextMenu = useCallback((e: React.MouseEvent, messageId: string) => {
    const target = e.target as Element | null
    if (target?.closest('.chat-image-local-context') || target?.closest('.chat-image-context-menu')) {
      return
    }
    e.preventDefault()
    e.stopPropagation()
    if (messageMultiSelectMode) return
    setMessageContextMenu({ x: e.clientX, y: e.clientY, messageId, tableCsvText: undefined, tableCsvFileName: undefined })
  }, [messageMultiSelectMode])

  const handleMessageMenuTriggerClick = useCallback((e: React.MouseEvent<HTMLButtonElement>, messageId: string) => {
    e.preventDefault()
    e.stopPropagation()
    if (messageMultiSelectMode) return
    const rect = e.currentTarget.getBoundingClientRect()
    setMessageContextMenu({
      x: Math.round(rect.left),
      y: Math.round(rect.bottom + 6),
      messageId,
      tableCsvText: undefined,
      tableCsvFileName: undefined,
    })
  }, [messageMultiSelectMode])

  const handleMessageTableMenuRequest = useCallback(
    (payload: TableMenuPayload, messageId: string) => {
      if (messageMultiSelectMode) return
      setMessageContextMenu({
        x: payload.x,
        y: payload.y,
        messageId,
        tableCsvText: payload.csvText,
        tableCsvFileName: payload.defaultFileName,
        tableEnterEdit: payload.enterEdit,
      })
    },
    [messageMultiSelectMode]
  )

  const handleMessageCopy = useCallback(async () => {
    if (!messageContextMenu) return
    const target = messages.find((m) => m.id === messageContextMenu.messageId)
    setMessageContextMenu(null)
    if (!target) return
    try {
      await navigator.clipboard.writeText(toMessageExportText(target))
    } catch {
      /* ignore */
    }
  }, [messageContextMenu, messages])

  const handleMessageSelectAll = useCallback(() => {
    setMessageContextMenu(null)
    if (exportableMessages.length === 0) return
    setMessageMultiSelectMode(true)
    setSelectedMessageIds(new Set(exportableMessages.map((m) => m.id)))
  }, [exportableMessages])

  const handleMessageMultiSelect = useCallback(() => {
    if (!messageContextMenu) return
    setMessageContextMenu(null)
    const id = messageContextMenu.messageId
    setMessageMultiSelectMode(true)
    setSelectedMessageIds((prev) => {
      const next = new Set(prev)
      next.add(id)
      return next
    })
  }, [messageContextMenu])

  const handleMessageForwardSingle = useCallback(() => {
    if (!messageContextMenu) return
    const target = messages.find((m) => m.id === messageContextMenu.messageId)
    setMessageContextMenu(null)
    if (!target) return
    void exportMessagesAsImage([target])
  }, [messageContextMenu, messages, exportMessagesAsImage])

  const showQuickNoteSavedNotice = useCallback(() => {
    setQuickNoteSavedNotice(true)
    if (quickNoteSavedNoticeTimerRef.current) {
      clearTimeout(quickNoteSavedNoticeTimerRef.current)
      quickNoteSavedNoticeTimerRef.current = null
    }
    quickNoteSavedNoticeTimerRef.current = setTimeout(() => {
      setQuickNoteSavedNotice(false)
      quickNoteSavedNoticeTimerRef.current = null
    }, 2000)
  }, [])

  const saveMessageToQuickNote = useCallback(
    (target: Message, options?: { silent?: boolean }) => {
      const textBody = toMessageExportText(target).trim()
      const image = extractMessageImageAttachment(target)
      if (!textBody && !image) {
        if (!options?.silent) {
          setMessages((prev) => [
            ...prev,
            {
              id: uuidv4(),
              sender: '系统',
              content: '该消息没有可保存的文字或图片',
              type: 'system',
              timestamp: Date.now(),
            },
          ])
        }
        return false
      }
      const sourceChatLabel = buildQuickNoteChatSourceLabel(conversation, isGroup)
      const sourceSender = (target.sender || '').trim() || (target.type === 'user' ? '用户' : '助手')
      const sourceMessageAt = Number(target.timestamp) > 0 ? target.timestamp : Date.now()
      addQuickNote({
        text: textBody || (image ? '[图片]' : ''),
        imageBase64: image?.base64,
        imageMime: image?.mime,
        imageName: image?.name,
        sourceChatLabel,
        sourceMessageAt,
        sourceSender,
      })
      if (!options?.silent) {
        showQuickNoteSavedNotice()
      }
      return true
    },
    [conversation, isGroup, setMessages, showQuickNoteSavedNotice]
  )

  const saveTextBlockToQuickNote = useCallback((text: string, sourceSender?: string, sourceMessageAt?: number) => {
    const pureText = (text || '').trim()
    if (!pureText) return
    addQuickNote({
      text: pureText,
      sourceChatLabel: buildQuickNoteChatSourceLabel(conversation, isGroup),
      sourceSender: (sourceSender || '').trim() || '助手',
      sourceMessageAt: Number(sourceMessageAt) > 0 ? Number(sourceMessageAt) : Date.now(),
    })
    showQuickNoteSavedNotice()
  }, [conversation, isGroup, showQuickNoteSavedNotice])

  const handleAddAssistantFromShareCard = useCallback(async (payload: AssistantShareCardPayload) => {
    if (addingShareAssistant) return
    const name = (payload.name || '').trim()
    const baseUrl = (payload.baseUrl || '').trim()
    if (!name || !baseUrl) {
      window.alert('助手信息不完整，无法添加')
      return
    }
    const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
    const exists = getCustomAssistants().some((a) => {
      if (payload.displayId && a.displayId && a.displayId === payload.displayId) return true
      return (a.baseUrl || '').trim().replace(/\/+$/, '/') === normalizedBaseUrl
    })
    if (exists) {
      window.alert('该助手已在你的助手库中')
      return
    }
    setAddingShareAssistant(true)
    try {
      const link = buildAssistantUrl(name, normalizedBaseUrl, payload.capabilities || [ 'chat' ], {
        multiSessionEnabled: payload.multiSessionEnabled,
      })
      const parsed = parseAssistantUrl(link, imei || undefined)
      if (!parsed) {
        window.alert('助手分享卡片无效，请重试')
        return
      }
      parsed.intro = payload.intro
      parsed.avatar = payload.avatar
      parsed.displayId = payload.displayId || parsed.displayId
      parsed.creator_imei = payload.creatorImei || parsed.creator_imei
      parsed.creator_avatar = payload.creatorAvatar || parsed.creator_avatar
      addCustomAssistant(parsed)
      if (imei) {
        const updated = getCustomAssistants()
        const ok = await syncCustomAssistantsToCloud(imei, updated)
        if (!ok) {
          window.alert('已添加到本地助手库，但云端同步失败，请稍后重试')
          return
        }
      }
      setShareCardPreview(null)
      window.alert('已添加到我的助手')
    } catch {
      window.alert('添加失败，请稍后重试')
    } finally {
      setAddingShareAssistant(false)
    }
  }, [addingShareAssistant, imei])

  const handleAddSkillFromShareCard = useCallback(async (payload: SkillShareCardPayload) => {
    if (addingShareSkill) return
    const title = (payload.title || '').trim()
    if (!title) {
      window.alert('技能信息不完整，无法添加')
      return
    }
    const exists = loadAllMySkills().some((s) => (s.title || '').trim().toLowerCase() === title.toLowerCase())
    if (exists) {
      window.alert('该技能已在你的技能库中')
      return
    }
    setAddingShareSkill(true)
    try {
      let imported = null as Awaited<ReturnType<typeof parseSkillPackageBundleBase64>> | null
      if (payload.packageBase64) {
        imported = await parseSkillPackageBundleBase64(payload.packageBase64)
        if (!imported) {
          window.alert('技能包解析失败，请让对方重新分享')
          return
        }
      }
      let serviceInstallMessage = ''
      if (imported?.servicePackageBase64) {
        const installRes = await importSkillPackageToService(imported.servicePackageBase64, {
          preferName: imported.serviceSkillName || imported.skill.title,
          overwrite: false,
        })
        if (!installRes.success) {
          serviceInstallMessage = `（本地安装失败：${installRes.error || '未知错误'}）`
        }
      }
      addMySkill({
        ...(imported?.skill || {}),
        id: imported?.skill.id || payload.id || `shared_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
        title: imported?.skill.title || title,
        originalPurpose: imported?.skill.originalPurpose ?? payload.originalPurpose,
        steps: imported?.skill.steps || ((payload.steps && payload.steps.length > 0) ? payload.steps : ['']),
        executionPlatform: imported?.skill.executionPlatform ?? payload.executionPlatform,
        source: imported?.skill.source ?? payload.source ?? 'shared',
        author: imported?.skill.author ?? payload.author,
        tags: imported?.skill.tags ?? payload.tags,
        packageBase64: payload.packageBase64,
        packageFileName: payload.packageFileName,
        createdAt: Date.now(),
      })
      setShareSkillPreview(null)
      window.alert((payload.packageBase64 ? '已从技能包添加到我的技能' : '已添加到我的技能') + serviceInstallMessage)
    } catch {
      window.alert('添加技能失败，请稍后重试')
    } finally {
      setAddingShareSkill(false)
    }
  }, [addingShareSkill])

  const handleMessageAddQuickNote = useCallback(() => {
    if (!messageContextMenu) return
    const target = messages.find((m) => m.id === messageContextMenu.messageId)
    setMessageContextMenu(null)
    if (!target) return
    void saveMessageToQuickNote(target)
  }, [messageContextMenu, messages, saveMessageToQuickNote])

  const handleMessageQuote = useCallback(() => {
    if (!messageContextMenu) return
    const target = messages.find((m) => m.id === messageContextMenu.messageId)
    setMessageContextMenu(null)
    if (!target) return
    const fallbackSender = target.type === 'user' ? '用户' : (target.type === 'assistant' ? '助手' : '系统')
    setQuotedMessageContext({
      messageId: target.id,
      sender: (target.sender || '').trim() || fallbackSender,
      timestamp: Number(target.timestamp) > 0 ? target.timestamp : Date.now(),
      content: toMessageExportText(target).trim(),
    })
  }, [messageContextMenu, messages])

  const handleMessageSaveCsv = useCallback(async () => {
    if (!messageContextMenu?.tableCsvText) return
    const csvText = messageContextMenu.tableCsvText
    const defaultName = messageContextMenu.tableCsvFileName || buildCsvDefaultFileName()
    setMessageContextMenu(null)
    if (window.electronAPI?.saveCsvAs) {
      await window.electronAPI.saveCsvAs(csvText, defaultName)
      return
    }
    triggerBlobDownload(defaultName, new Blob(['\uFEFF', csvText], { type: 'text/csv;charset=utf-8;' }))
  }, [messageContextMenu])

  const handleMessageEditTable = useCallback(() => {
    if (!messageContextMenu?.tableEnterEdit) return
    const enterEdit = messageContextMenu.tableEnterEdit
    setMessageContextMenu(null)
    enterEdit()
  }, [messageContextMenu])

  const saveChatFileToWorkspacePath = useCallback(async (fileBase64: string, fileName?: string): Promise<string | null> => {
    const api = (window.electronAPI || undefined) as IdeWorkspaceBridge | undefined
    if (!api?.saveChatFileToWorkspace) return null
    const saveRes = await api.saveChatFileToWorkspace(
      buildDataUrl(fileBase64, 'application/octet-stream'),
      fileName || 'file.bin'
    )
    if (!saveRes?.ok || !saveRes.path) {
      if (saveRes?.error) {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: `保存文件失败：${saveRes.error}`, type: 'system', timestamp: Date.now() },
        ])
      }
      return null
    }
    return String(saveRes.path)
  }, [])

  const loadFolderIntoCodeMode = useCallback(async (folderPath: string, focusFileAbsPath?: string): Promise<boolean> => {
    const api = (window.electronAPI || undefined) as IdeWorkspaceBridge | undefined
    if (!api?.listFolderFiles) return false
    const folderRaw = String(folderPath || '').trim()
    if (!folderRaw) return false
    const res = await api.listFolderFiles({ folderPath: folderRaw, maxFiles: 1500, maxBytes: 256 * 1024 })
    if (!res?.ok || !Array.isArray(res.files) || res.files.length === 0) return false
    const folderName = basenameFromAnyPath(folderRaw) || 'workspace'
    const names = res.files
      .map((f) => normalizeIdePath(`${folderName}/${String(f.relativePath || '')}`))
      .filter(Boolean)
    if (names.length === 0) return false
    const contentMap: Record<string, string> = {}
    const savedPathMap: Record<string, string> = {}
    const folderNorm = folderRaw.replace(/[\\\/]+$/g, '')
    for (const item of res.files) {
      const rel = normalizeIdePath(String(item.relativePath || ''))
      const normalized = normalizeIdePath(`${folderName}/${rel}`)
      if (!normalized) continue
      contentMap[normalized] = typeof item.content === 'string' ? item.content : ''
      if (rel) savedPathMap[normalized] = `${folderNorm}\\${rel.replace(/\//g, '\\')}`
    }
    const tree = buildIdeTree(names, [])
    const focusAbs = String(focusFileAbsPath || '').trim().replace(/\//g, '\\')
    const folderWithSlash = `${folderNorm.replace(/\//g, '\\')}\\`
    const relFocus = focusAbs && focusAbs.toLowerCase().startsWith(folderWithSlash.toLowerCase())
      ? focusAbs.slice(folderWithSlash.length).replace(/\\/g, '/')
      : ''
    const focusPath = normalizeIdePath(relFocus ? `${folderName}/${relFocus}` : '')
    const activePath = focusPath && names.some((p) => p.toLowerCase() === focusPath.toLowerCase())
      ? (names.find((p) => p.toLowerCase() === focusPath.toLowerCase()) || names[0])
      : names[0]
    setIdeOpenedEntries(names)
    setIdeManualFolders([])
    setIdeOpenTabs(activePath ? [activePath] : [])
    setIdeExplorerTree(tree)
    setIdeExpandedFolders(new Set(collectIdeAncestorFoldersForFile(activePath || '')))
    setIdeActiveFile(activePath || null)
    setIdeFileContents(contentMap)
    setIdeSavedPaths(savedPathMap)
    setIdeSavedContents(contentMap)
    setIdeUnsavedDrafts({})
    setIdeHumanEditedPaths({})
    setIdeAssistantAddedPaths({})
    setIdeQaContextRootRelPath(folderName || null)
    setIdePreferredCwd(folderNorm.replace(/\//g, '\\'))
    setIdeQaContextRootAbsPath(folderNorm.replace(/\//g, '\\'))
    setIdeModeEnabled(true)
    setIdeFileMenuOpen(false)
    setIdeRecentPanelOpen(false)
    setIdeTerminalVisible(false)
    setIdeTerminalCollapsed(true)
    if (!conversationListCollapsed) onToggleConversationList?.()
    setIdeRecentEntries((prev) => {
      const merged = [...names, ...prev]
      const uniq: string[] = []
      const seen = new Set<string>()
      for (const entry of merged) {
        const normalized = normalizeIdePath(entry)
        if (!normalized) continue
        const key = normalized.toLowerCase()
        if (seen.has(key)) continue
        seen.add(key)
        uniq.push(normalized)
        if (uniq.length >= 30) break
      }
      return uniq
    })
    return true
  }, [conversationListCollapsed, onToggleConversationList])

  const handleOpenFileFolder = useCallback(async (fileBase64: string, fileName?: string) => {
    const path = await saveChatFileToWorkspacePath(fileBase64, fileName)
    if (!path) return false
    const api = (window.electronAPI || undefined) as IdeWorkspaceBridge | undefined
    const revealRes = await api?.showItemInFolder?.(path)
    if (!revealRes?.success && revealRes?.error) {
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: `打开文件夹失败：${revealRes.error}`, type: 'system', timestamp: Date.now() },
      ])
      return false
    }
    return true
  }, [saveChatFileToWorkspacePath])

  const handleFileLinkQuickView = useCallback(async (target: FileLinkActionTarget) => {
    if (target.kind === 'chat_file') {
      const path = await saveChatFileToWorkspacePath(target.fileBase64, target.fileName)
      if (!path) return
      const folder = ideDirnameFromAnyPath(path)
      if (!folder) return
      const ok = await loadFolderIntoCodeMode(folder, path)
      if (!ok) {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: '快捷查看失败：未能加载该文件所在目录。', type: 'system', timestamp: Date.now() },
        ])
      }
      return
    }
    const path = String(target.filePath || '').trim()
    if (!path) return
    const folder = ideDirnameFromAnyPath(path)
    if (!folder) return
    const ok = await loadFolderIntoCodeMode(folder, path)
    if (!ok) {
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: '快捷查看失败：未能加载该文件所在目录。', type: 'system', timestamp: Date.now() },
      ])
    }
  }, [loadFolderIntoCodeMode, saveChatFileToWorkspacePath])

  const handleFileLinkOpenFolder = useCallback(async (target: FileLinkActionTarget) => {
    const api = (window.electronAPI || undefined) as IdeWorkspaceBridge | undefined
    if (target.kind === 'chat_file') {
      await handleOpenFileFolder(target.fileBase64, target.fileName)
      return
    }
    const path = String(target.filePath || '').trim()
    if (!path) return
    const revealRes = await api?.showItemInFolder?.(path)
    if (!revealRes?.success && revealRes?.error) {
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: `打开文件夹失败：${revealRes.error}`, type: 'system', timestamp: Date.now() },
      ])
    }
  }, [handleOpenFileFolder])

  const handleRevealLocalFileToken = useCallback(async (fileToken: string) => {
    const token = String(fileToken || '').trim()
    if (!token) return
    const api = (window.electronAPI || undefined) as IdeWorkspaceBridge | undefined
    if (!ideModeEnabled && api?.resolveGeneratedFile) {
      const resolved = await api.resolveGeneratedFile(token)
      if (resolved?.success && resolved.path) {
        setFileLinkActionTarget({
          kind: 'local_file',
          filePath: String(resolved.path),
          fileName: basenameFromAnyPath(String(resolved.path)) || '文件',
        })
        return
      }
      if (resolved?.error) {
        showInlineToastNotice(`定位文件失败：${resolved.error}`)
      }
      return
    }
    const res = await api?.revealGeneratedFile?.(token)
    if (!res?.success && res?.error) {
      showInlineToastNotice(`定位文件失败：${res.error}`)
    }
  }, [ideModeEnabled, showInlineToastNotice])

  const handleOpenExternalUrl = useCallback(async (url: string) => {
    const target = normalizeWebUrl(String(url || ''))
    if (!target) return
    const opened = await window.electronAPI?.openExternal?.(target)
    if (!opened?.success) {
      window.open(target, '_blank', 'noopener,noreferrer')
    }
  }, [])

  const handleForwardSelectedMessages = useCallback(() => {
    if (selectedMessages.length === 0) return
    void exportMessagesAsImage(selectedMessages)
    closeMessageMultiSelect()
  }, [selectedMessages, exportMessagesAsImage, closeMessageMultiSelect])

  const handleSaveSelectedMessagesToQuickNote = useCallback(async () => {
    if (!conversation || selectedMessages.length === 0) return
    const rendered = await captureMessagesAsImage(selectedMessages)
    if (!rendered) return
    const b64 = rendered.dataUrl.split(',', 2)[1] || ''
    if (!b64) return
    const summaryText = selectedMessages
      .map((m) => `${m.type === 'assistant' ? '助手' : '我'}：${toMessageExportText(m)}`)
      .join('\n')
      .slice(0, 1800)
    const firstTs = selectedMessages[0]?.timestamp
    const sourceMessageAt = Number(firstTs) > 0 ? firstTs : Date.now()
    addQuickNote({
      text: `聊天长图（${conversation.name || '会话'}）\n${summaryText}`,
      imageBase64: b64,
      imageMime: 'image/png',
      imageName: `${sanitizeFileName(conversation.name || 'chat')}_${new Date().toISOString().replace(/[:.]/g, '-')}.chat.png`,
      sourceChatLabel: buildQuickNoteChatSourceLabel(conversation, isGroup),
      sourceMessageAt,
      sourceSender: `共 ${selectedMessages.length} 条消息`,
    })
    showQuickNoteSavedNotice()
    closeMessageMultiSelect()
  }, [conversation, isGroup, selectedMessages, captureMessagesAsImage, closeMessageMultiSelect, showQuickNoteSavedNotice])

  const toggleSelectedMessage = useCallback((messageId: string) => {
    setSelectedMessageIds((prev) => {
      const next = new Set(prev)
      if (next.has(messageId)) next.delete(messageId)
      else next.add(messageId)
      return next
    })
  }, [])

  useEffect(() => {
    if (!chatInputContextMenu) return
    const close = () => setChatInputContextMenu(null)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    window.addEventListener('click', close)
    window.addEventListener('contextmenu', close)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('contextmenu', close)
      window.removeEventListener('keydown', onKey)
    }
  }, [chatInputContextMenu])

  useEffect(() => {
    if (!messageContextMenu) return
    const close = () => setMessageContextMenu(null)
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    window.addEventListener('click', close)
    window.addEventListener('contextmenu', close)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('contextmenu', close)
      window.removeEventListener('keydown', onKey)
    }
  }, [messageContextMenu])

  /** 多 session：本地优先渲染，云端后台校准（SWR） */
  useEffect(() => {
    if (!conversation?.id || !isMultiSessionCustom) {
      console.log('[Session] load effect skip', { conversationId: conversation?.id, isMultiSessionCustom })
      setCurrentSessionId(null)
      setSessions([])
      return
    }
    let cancelled = false
    const convId = conversation.id
    const run = async () => {
      const list = loadSessions(convId, sessionBaseUrl)
      console.log('[Session] loadSessions result', {
        conversationId: convId,
        sessionBaseUrl: sessionBaseUrl ?? '(undefined)',
        localCount: list.length,
      })
      if (!cancelled) {
        setSessions(list)
        setCurrentSessionId((prev) => {
          if (prev && list.some((s) => s.id === prev)) return prev
          return list.length > 0 ? list[0].id : null
        })
      }
      let merged = list
      if (imei && sessionBaseUrl && !shouldBlockTopoCloudTraffic) {
        try {
          // 先拉取服务端列表（支持删除同步：其他端删除的 session 会从服务端消失）
          const { success: getOk, sessions: remote } = await getSessions(imei, convId, {
            baseUrl: sessionBaseUrl,
          })
          if (!cancelled && getOk) {
            // 以云侧为准：直接使用 remote，不合并 local-only
            merged = remote.length > 0 ? remote : list
            saveSessions(convId, merged, sessionBaseUrl)
          }
          // 再同步本地（确保新建/更新/删除能推送到服务端）
          const { success: syncOk, sessions: synced } = await syncSessions(imei, convId, merged, {
            baseUrl: sessionBaseUrl,
          })
          if (!cancelled && syncOk && synced.length > 0) {
            const final = mergeSessionsByRemote(merged, synced)
            saveSessions(convId, final, sessionBaseUrl)
            merged = final
          }
        } catch {
          // 同步失败则使用本地
        }
      }
      if (cancelled) return

      let finalSessions: ChatSession[] = []
      let pickId = ''
      const rememberedLocalSessionId = (getActiveSessionLocal(convId, sessionBaseUrl) || '').trim()
      const rememberedHasMessages = rememberedLocalSessionId
        ? hasStoredMessages(convId, rememberedLocalSessionId)
        : false
      if (merged.length > 0) {
        finalSessions = merged
        pickId = merged[0].id
        if (rememberedLocalSessionId && finalSessions.some((s) => s.id === rememberedLocalSessionId)) {
          pickId = rememberedLocalSessionId
        }
      } else {
        const legacySessionId = imei
          ? uuidv5(`${imei}_${convId}`, '6ba7b810-9dad-11d1-80b4-00c04fd430c8')
          : uuidv4()
        const migrated: ChatSession = { id: legacySessionId, title: '新对话', createdAt: Date.now() }
        addSession(convId, migrated, sessionBaseUrl)
        finalSessions = [migrated]
        pickId = legacySessionId
        if (!cancelled) {
          setSessions(finalSessions)
          setCurrentSessionId(legacySessionId)
        }
        if (imei && sessionBaseUrl && !shouldBlockTopoCloudTraffic) {
          try {
            const { success, sessions: synced } = await syncSessions(imei, convId, [migrated], {
              baseUrl: sessionBaseUrl,
            })
            if (!cancelled && success && synced.length > 0) {
              saveSessions(convId, synced, sessionBaseUrl)
              finalSessions = synced
              pickId = synced[0].id
            }
          } catch {
            // ignore
          }
        }
      }
      if (rememberedLocalSessionId && rememberedHasMessages && !finalSessions.some((s) => s.id === rememberedLocalSessionId)) {
        const localSession: ChatSession = { id: rememberedLocalSessionId, title: '新对话', createdAt: Date.now() }
        addSession(convId, localSession, sessionBaseUrl)
        finalSessions = [localSession, ...finalSessions].sort((a, b) => b.createdAt - a.createdAt)
        pickId = rememberedLocalSessionId
      }

      if (!cancelled && imei && sessionBaseUrl && !shouldBlockTopoCloudTraffic) {
        try {
          const act = await getActiveSession(imei, convId, { baseUrl: sessionBaseUrl })
          if (act.success && act.active_session_id) {
            const aid = act.active_session_id
            let listSess = finalSessions
            if (!listSess.some((s) => s.id === aid)) {
              const ph: ChatSession = { id: aid, title: '新对话', createdAt: Date.now() }
              addSession(convId, ph, sessionBaseUrl)
              listSess = [ph, ...listSess].sort((a, b) => b.createdAt - a.createdAt)
              saveSessions(convId, listSess, sessionBaseUrl)
              finalSessions = listSess
            }
            // 进入会话时优先保留本地“最近活跃且有消息”的 session，避免被云端旧 active 覆盖后出现详情空白
            const rememberedStillExists =
              !!rememberedLocalSessionId && finalSessions.some((s) => s.id === rememberedLocalSessionId)
            if (!(rememberedStillExists && rememberedHasMessages)) {
              pickId = aid
            }
            activeSessionRemoteTsRef.current = Math.max(activeSessionRemoteTsRef.current, act.updated_at || 0)
          }
        } catch {
          // ignore
        }
      }

      if (!cancelled) {
        setSessions(finalSessions)
        setCurrentSessionId((prev) => {
          if (pickId) return pickId
          if (prev && finalSessions.some((s) => s.id === prev)) return prev
          return finalSessions.length > 0 ? finalSessions[0].id : null
        })
      }
    }
    run()
    return () => { cancelled = true }
  }, [conversation?.id, isMultiSessionCustom, imei, sessionBaseUrl, shouldBlockTopoCloudTraffic])

  /** 手机发起 PC 执行任务后，自动跳转到该 session */
  useEffect(() => {
    if (!sessionIdToNavigate || !conversation?.id || !isMultiSessionCustom) return
    const sid = sessionIdToNavigate
    const exists = sessions.some((s) => s.id === sid)
    if (!exists) {
      addSession(conversation.id, { id: sid, title: '新对话', createdAt: Date.now() }, sessionBaseUrl)
      setSessions((prev) => [...prev, { id: sid, title: '新对话', createdAt: Date.now() }])
    }
    setCurrentSessionId(sid)
    onSessionIdNavigated?.()
  }, [sessionIdToNavigate, conversation?.id, isMultiSessionCustom, sessions, sessionBaseUrl, onSessionIdNavigated])

  /** 用户点击标题栏会话名：从云端/助手 HTTP 拉取并合并历史（打开会话时不自动请求） */
  const refreshHistoryFromCloud = useCallback(() => {
    if (!conversation?.id || !needCloudHistorySync) return
    if (isMultiSessionCustom && !currentSessionId) return
    if (shouldBlockTopoCloudTraffic) return

    cloudHistoryManualAbortRef.current?.abort()
    const abort = new AbortController()
    cloudHistoryManualAbortRef.current = abort

    const loadForId = conversation.id
    const rawLocal = loadLocalMessagesForConversation(
      loadForId,
      isMultiSessionCustom ? currentSessionId ?? undefined : undefined
    ) as Message[]
    const baseline = getBaselineTimestamp()
    const localMsgs = rawLocal.filter((m) => m.timestamp > baseline)
    const sinceTs = localMsgs.length > 0 ? Math.max(...localMsgs.map((m) => m.timestamp)) : baseline

    const throttleKey = cloudHistoryThrottleKey(
      conversation.id,
      isMultiSessionCustom ? currentSessionId : null
    )

    const historySeq = ++historyLoadSeqRef.current
    setHistoryLoading(true)
    isTransitioningRef.current = true

    void (async () => {
      const signal = abort.signal
      if (signal.aborted) return
      const histStart = Date.now()
      perfLog('ChatDetail 手动同步云端历史', { conversationId: loadForId })
      try {
        let apiMsgs: Message[] = []
        if (isChatAssistant) {
          const baseUrl = getChatAssistantBaseUrl()
          if (baseUrl) {
            const { messages: hist } = await getChatAssistantHistory(baseUrl, sessionUuidRef.current, 100, signal)
            apiMsgs = hist.map((m, idx) => ({
              id: `chat_assistant_${m.order}_${idx}`,
              sender: m.role === 'user' ? '我' : '聊天小助手',
              content: m.content || '',
              type: (m.role === 'user' ? 'user' : 'assistant') as Message['type'],
              timestamp: baseline + m.order * 1000,
            }))
          }
        } else if (isCrossDevice) {
          const { messages } = await getCrossDeviceMessages(imei!, undefined, 100, signal, sinceTs)
          apiMsgs = messages.map((m) => {
            const rawB64 = m.file_base64 || m.imageBase64
            const b64 = typeof rawB64 === 'string' && rawB64.trim().length > 0 ? rawB64.trim() : undefined
            const mt = m.message_type || 'text'
            const isImg = mt === 'image' || mt === 'file' || !!b64
            const text = (m.content || '').trim()
            const content =
              !isImg ? (m.content || '') : mt === 'file' ? text || `[文件] ${m.file_name || '文件'}` : text || '[图片]'
            return {
              id: m.id,
              sender: m.from_device === 'pc' ? '我' : '我的手机',
              content,
              type: (m.from_device === 'pc' ? 'user' : 'assistant') as Message['type'],
              timestamp: new Date(m.created_at).getTime(),
              ...(b64
                ? { messageType: 'file' as const, fileBase64: b64, fileName: m.file_name || '图片.png' }
                : {}),
            }
          })
        } else if (isAssistant) {
          const { messages } = await getUnifiedMessages(imei!, 'assistant', undefined, 100, signal, sinceTs)
          apiMsgs = messages.map((m) => ({
            id: m.id,
            sender: m.sender || '小助手',
            content: m.content || '',
            type: (m.type === 'user' ? 'user' : m.type === 'system' ? 'system' : 'assistant') as Message['type'],
            timestamp: m.created_at ? new Date(m.created_at).getTime() : Date.now(),
          }))
        } else if (isMultiSessionCustom && currentSessionId && chatBaseUrl && conversation.id) {
          const { messages: hist } = await getChatAssistantHistory(chatBaseUrl, currentSessionId, 100, signal)
          apiMsgs = hist.map((m, idx) => ({
            id: `multi_${m.order}_${idx}`,
            sender: m.role === 'user' ? '我' : (customAssistant?.name ?? '小助手'),
            content: m.content || '',
            type: (m.role === 'user' ? 'user' : 'assistant') as Message['type'],
            timestamp: baseline + m.order * 1000,
          }))
          const firstUser = hist.find((m) => m.role === 'user')
          if (firstUser?.content) {
            const list = loadSessions(conversation.id, sessionBaseUrl)
            const curSession = list.find((s) => s.id === currentSessionId)
            if (curSession?.title === '新对话') {
              const title = firstUser.content.slice(0, 30).replace(/\s+/g, ' ').trim()
              const newTitle = title.length < firstUser.content.length ? `${title}...` : title
              updateSessionTitle(conversation.id, currentSessionId!, newTitle, sessionBaseUrl)
              setSessions((prev) =>
                prev.map((s) => (s.id === currentSessionId ? { ...s, title: newTitle } : s))
              )
              if (imei && sessionBaseUrl && !shouldBlockTopoCloudTraffic) {
                const list2 = loadSessions(conversation.id, sessionBaseUrl)
                syncSessions(imei, conversation.id, list2, { baseUrl: sessionBaseUrl })
                  .then(({ success, sessions: synced }) => {
                    if (success && synced.length > 0) saveSessions(conversation.id, synced, sessionBaseUrl)
                  })
                  .catch(() => {})
              }
            }
          }
        } else if (isCustomChatAssistant && conversation.id && !isMultiSessionCustom) {
          const { messages } = await getUnifiedMessages(imei!, conversation.id, undefined, 100, signal, sinceTs)
          apiMsgs = messages.map((m) => ({
            id: m.id,
            sender: m.sender || (customAssistant?.name ?? '小助手'),
            content: m.content || '',
            type: (m.type === 'user' ? 'user' : m.type === 'system' ? 'system' : 'assistant') as Message['type'],
            timestamp: m.created_at ? new Date(m.created_at).getTime() : Date.now(),
            ...(m.file_base64
              ? { messageType: 'file' as const, fileBase64: m.file_base64, fileName: m.file_name || '图片.png' }
              : {}),
          }))
        } else if (isFriend && conversation.id) {
          const { messages } = await getUnifiedMessages(imei!, conversation.id, undefined, 100, signal, sinceTs)
          const myImei = imei ? String(imei).trim() : ''
          apiMsgs = messages.map((m) => {
            const senderImei = typeof m.sender_imei === 'string' ? m.sender_imei.trim() : ''
            const isMe = !!myImei && !!senderImei && senderImei === myImei
            const rawB64 = m.file_base64 || m.imageBase64
            const b64 = typeof rawB64 === 'string' && rawB64.trim().length > 0 ? rawB64.trim() : undefined
            const isImg = m.message_type === 'image' || m.message_type === 'file' || !!b64
            const text = (m.content || '').trim()
            const senderLabel = typeof m.sender_label === 'string' && m.sender_label.trim()
              ? m.sender_label.trim()
              : (typeof m.sender === 'string' && m.sender.trim()
                  ? m.sender.trim()
                  : (conversation?.name ?? '好友'))
            const cloneOriginRaw = (m as { clone_origin?: string }).clone_origin
            const isCloneReply =
              (m as { is_clone_reply?: boolean }).is_clone_reply === true
              || (typeof cloneOriginRaw === 'string' && cloneOriginRaw.trim().toLowerCase() === 'digital_clone')
            const cloneOwnerRaw = (m as { clone_owner_imei?: string }).clone_owner_imei
            const cloneOwnerImei = typeof cloneOwnerRaw === 'string' && cloneOwnerRaw.trim()
              ? cloneOwnerRaw.trim()
              : senderImei
            const isMyClone = isCloneReply && !!cloneOwnerImei && !!myImei && cloneOwnerImei === myImei
            const senderDisplay = isMe
              ? '我'
              : isMyClone
                ? '我（数字分身）'
                : (isCloneReply && senderLabel && !senderLabel.includes('数字分身')
                    ? `${senderLabel}（数字分身）`
                    : senderLabel)
            return {
              id: m.id,
              sender: senderDisplay,
              content: isImg ? text || '[图片]' : (m.content || ''),
              type: 'user' as const,
              timestamp: m.created_at ? new Date(m.created_at).getTime() : Date.now(),
              messageSource: isCloneReply ? (isMyClone ? 'my_clone' : 'friend_clone') : (isMe ? 'user' : 'friend'),
              ...(isCloneReply ? { cloneOwnerImei: cloneOwnerImei || undefined } : {}),
              ...(b64 ? { messageType: 'file' as const, fileBase64: b64, fileName: m.file_name || '图片.png' } : {}),
            }
          })
        } else if (isGroup && conversation.id) {
          const { messages } = await getUnifiedMessages(imei!, conversation.id, undefined, 100, signal, sinceTs)
          const myImei = imei ? String(imei).trim() : ''
          apiMsgs = messages.map((m) => {
            const senderImei = typeof m.sender_imei === 'string' ? m.sender_imei.trim() : ''
            const isMe = !!myImei && !!senderImei && senderImei === myImei
            const sender = (m as { sender?: string }).sender
            const senderLabelRaw = (m as { sender_label?: string }).sender_label
            const senderLabel = typeof senderLabelRaw === 'string' && senderLabelRaw.trim()
              ? senderLabelRaw.trim()
              : (typeof sender === 'string' ? sender : '')
            const rawGb64 = m.file_base64 || m.imageBase64
            const gb64 = typeof rawGb64 === 'string' && rawGb64.trim().length > 0 ? rawGb64.trim() : undefined
            const isAssistantMsg =
              (typeof m.type === 'string' && m.type === 'assistant') ||
              sender === '自动执行小助手'
            const cloneOriginRaw = (m as { clone_origin?: string }).clone_origin
            const isCloneReply =
              (m as { is_clone_reply?: boolean }).is_clone_reply === true
              || (typeof cloneOriginRaw === 'string' && cloneOriginRaw.trim().toLowerCase() === 'digital_clone')
            const cloneOwnerRaw = (m as { clone_owner_imei?: string }).clone_owner_imei
            const cloneOwnerImei = typeof cloneOwnerRaw === 'string' && cloneOwnerRaw.trim()
              ? cloneOwnerRaw.trim()
              : senderImei
            const isMyClone = isCloneReply && !!cloneOwnerImei && !!myImei && cloneOwnerImei === myImei
            const senderDisplay =
              isMyClone
                ? '我（数字分身）'
                : isMe
                  ? '我'
                  : isCloneReply
                    ? (senderLabel && !senderLabel.includes('数字分身') ? `${senderLabel}（数字分身）` : (senderLabel || '数字分身'))
                : isAssistantMsg
                  ? (sender ?? '自动执行小助手')
                  : getGroupMemberDisplayName(senderImei, sender ?? '群成员')
            return {
              id: m.id,
              sender: senderDisplay,
              senderImei: senderImei || undefined,
              content: m.content || '',
              type: (isAssistantMsg ? 'assistant' : 'user') as Message['type'],
              timestamp: m.created_at ? new Date(m.created_at).getTime() : Date.now(),
              ...(isCloneReply ? { messageSource: (isMyClone ? 'my_clone' : 'friend_clone') as Message['messageSource'] } : {}),
              ...(isCloneReply ? { cloneOwnerImei: cloneOwnerImei || undefined } : {}),
              ...(gb64
                ? { messageType: 'file' as const, fileBase64: gb64, fileName: m.file_name || '图片.png' }
                : {}),
            }
          })
        }
        if (currentConvIdRef.current !== loadForId) return
        const latestLocal = loadLocalMessagesForConversation(
          loadForId,
          isMultiSessionCustom ? currentSessionId ?? undefined : undefined
        ).filter((m) => m.timestamp > baseline)
        let merged: Message[]
        if ((isChatAssistant || isCustomChatAssistant) && apiMsgs.length > 0) {
          merged = apiMsgs
        } else {
          const byId = new Map<string, Message>()
          latestLocal.forEach((m) => byId.set(m.id, m))
          apiMsgs.forEach((m) => {
            const contentDup = [...byId.values()].some(
              (ex) => ex.type === 'assistant' && ex.content === m.content && Math.abs((ex.timestamp || 0) - (m.timestamp || 0)) < 10000
            )
            if (!contentDup) {
              const existing = byId.get(m.id)
              let next: Message = m
              if ((isFriend || isGroup || isCrossDevice) && m.type === 'user' && existing?.type === 'user') {
                next = mergeUserMessageKeepImage(m, existing)
              } else if (isCrossDevice && m.type === 'assistant' && existing?.type === 'assistant') {
                next = mergeUserMessageKeepImage(m, existing)
              }
              byId.set(m.id, next)
            }
          })
          merged = [...byId.values()].sort((a, b) => a.timestamp - b.timestamp)
        }
        if (isGroup || isFriend || isCrossDevice) {
          setMessages((prev) => {
            if (currentConvIdRef.current !== loadForId) return prev
            const byId = new Map<string, Message>()
            merged.forEach((m) => byId.set(m.id, m))
            prev.forEach((m) => {
              if (!byId.has(m.id)) {
                byId.set(m.id, m)
                return
              }
              const cur = byId.get(m.id)!
              if (m.type === 'user' && cur.type === 'user') {
                byId.set(m.id, mergeUserMessageKeepImage(cur, m))
              } else if (isCrossDevice && m.type === 'assistant' && cur.type === 'assistant') {
                byId.set(m.id, mergeUserMessageKeepImage(cur, m))
              }
            })
            return [...byId.values()].sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))
          })
        } else {
          setMessages(merged)
        }
        markCloudHistoryFetched(throttleKey)
        perfLogEnd('ChatDetail 手动同步云端历史', histStart, { conversationId: loadForId, count: merged.length })
      } catch (e) {
        if ((e as { name?: string; code?: string })?.name === 'AbortError' || (e as { code?: string })?.code === 'ERR_CANCELED') {
          if (currentConvIdRef.current === loadForId) {
            const fresh = loadLocalMessagesForConversation(
              loadForId,
              isMultiSessionCustom ? currentSessionId ?? undefined : undefined
            )
            const filtered = fresh.filter((m) => m.timestamp > baseline)
            setMessages(filtered.length > 0 ? filtered : localMsgs)
          }
          return
        }
        if (currentConvIdRef.current === loadForId) setMessages(localMsgs)
        perfLogEnd('ChatDetail 手动同步云端历史失败', histStart, { conversationId: loadForId, error: String(e) })
      } finally {
        if (historyLoadSeqRef.current === historySeq) {
          setHistoryLoading(false)
          isTransitioningRef.current = false
        }
        if (cloudHistoryManualAbortRef.current === abort) {
          cloudHistoryManualAbortRef.current = null
        }
      }
    })()
  }, [
    needCloudHistorySync,
    conversation,
    isMultiSessionCustom,
    currentSessionId,
    isGroup,
    isChatAssistant,
    isCrossDevice,
    isAssistant,
    chatBaseUrl,
    customAssistant,
    isCustomChatAssistant,
    isFriend,
    imei,
    sessionBaseUrl,
    setMessages,
  ])

  useEffect(() => {
    if (!conversation) {
      historyLoadSeqRef.current += 1
      setHistoryLoading(false)
      cloudHistoryManualAbortRef.current?.abort()
      cloudHistoryManualAbortRef.current = null
      messagesOwnerRef.current = null
      isTransitioningRef.current = false
      setMessages([])
      setCurrentSessionId(null)
      setSessions([])
      return
    }
    if (isMultiSessionCustom && !currentSessionId) {
      historyLoadSeqRef.current += 1
      setHistoryLoading(false)
      cloudHistoryManualAbortRef.current?.abort()
      cloudHistoryManualAbortRef.current = null
      messagesOwnerRef.current = null
      isTransitioningRef.current = false
      // 不再在 session 未就绪时清空，优先展示本地默认桶消息，避免切换瞬间白屏
      const baseline = getBaselineTimestamp()
      const fallbackLocal = (loadMessages(conversation.id) as Message[]).filter((m) => m.timestamp > baseline)
      setMessages(fallbackLocal)
      return
    }
    isTransitioningRef.current = true
    if (imei) {
      sessionUuidRef.current = uuidv5(`${imei}_${conversation.id}`, '6ba7b810-9dad-11d1-80b4-00c04fd430c8')
    } else {
      sessionUuidRef.current = uuidv4()
    }
    const rawLocal = loadLocalMessagesForConversation(
      conversation.id,
      isMultiSessionCustom ? currentSessionId ?? undefined : undefined
    ) as Message[]
    const baseline = getBaselineTimestamp()
    const localMsgs = rawLocal.filter((m) => m.timestamp > baseline)

    if (!needCloudHistorySync) {
      historyLoadSeqRef.current += 1
      setHistoryLoading(false)
      messagesOwnerRef.current = conversation.id
      setMessages(localMsgs)
      setLoading(false)
      queueMicrotask(() => { isTransitioningRef.current = false })
      return
    }

    messagesOwnerRef.current = conversation.id
    setMessages(localMsgs)
    historyLoadSeqRef.current += 1
    setHistoryLoading(false)
    queueMicrotask(() => { isTransitioningRef.current = false })

    return () => {
      cloudHistoryManualAbortRef.current?.abort()
      cloudHistoryManualAbortRef.current = null
    }
  }, [conversation?.id, needCloudHistorySync, isGroup, isMultiSessionCustom, currentSessionId, imei, sessionBaseUrl, shouldBlockTopoCloudTraffic, loadLocalMessagesForConversation])

  useEffect(() => {
    if (conversation && messages.length > 0 && !loading && !isTransitioningRef.current) {
      if (messagesOwnerRef.current !== conversation.id) return
      saveMessages(conversation.id, messages, isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined)
      const last = messages[messages.length - 1]
      if (last.sender !== '系统') {
        const preview = last.messageType === 'file' ? `[文件] ${last.fileName || '文件'}` : (toSkillSharePreview(last.content) || toAssistantSharePreview(last.content) || last.content.slice(0, 50))
        // 聊天小助手/多 session 自定义小助手的历史消息来自 getChatAssistantHistory，仅有 order 无真实时间戳，
        // 使用 baseline+order 生成的假时间戳会导致会话列表显示错误日期，此处不传 timestamp 避免覆盖
        const isSyntheticTimestamp =
          (isChatAssistant || isMultiSessionCustom) &&
          (typeof last.id === 'string' && (last.id.startsWith('chat_assistant_') || last.id.startsWith('multi_')))
        const ts = isSyntheticTimestamp ? undefined : last.timestamp
        onUpdateLastMessage?.(conversation.id, preview, ts, {
          isFromMe: last.sender === '我' || last.sender === '用户自己',
          isViewing: true,
        })
      }
    }
  }, [conversation?.id, isCrossDevice, loading, messages, onUpdateLastMessage, isMultiSessionCustom, currentSessionId, isChatAssistant])

  const isNearBottom = (el: HTMLDivElement): boolean => {
    const remain = el.scrollHeight - el.scrollTop - el.clientHeight
    return remain <= 120
  }

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'auto' })
  }

  useLayoutEffect(() => {
    shouldAutoScrollRef.current = true
  }, [conversation?.id])

  useLayoutEffect(() => {
    if (loading || shouldAutoScrollRef.current) {
      scrollToBottom()
    }
  }, [messages, loading])

  /** 停止当前 WebSocket 聊天任务，发送 openclaw_guan 协议的 stop 消息，同时通知对端 */
  /** 停止当前任务：停止 chat WS 流、中止本地 mobile execution、通知对端 */
  const handleStop = useCallback(() => {
    const stopConversationId = conversation?.id
    const stopSessionId = isMultiSessionCustom ? currentSessionIdRef.current : undefined
    bumpChatStreamGeneration(stopConversationId, stopSessionId)
    if (chatWs?.sendStop) {
      chatWs.sendStop()
    }
    cancelMobileExecution()
    abortPcExecutionsForScope(stopConversationId, stopSessionId)
    setLoading(false)
    setIsMobileTaskRunning(false)
    if (conversation?.id) {
      wsSend({ type: 'assistant_stop_task', conversation_id: conversation.id })
    }
    if (stopTaskNoticeTimerRef.current) {
      clearTimeout(stopTaskNoticeTimerRef.current)
      stopTaskNoticeTimerRef.current = null
    }
    setStopTaskNotice(true)
    stopTaskNoticeTimerRef.current = setTimeout(() => {
      setStopTaskNotice(false)
      stopTaskNoticeTimerRef.current = null
    }, 2000)
  }, [chatWs, wsSend, cancelMobileExecution, conversation?.id, isMultiSessionCustom, bumpChatStreamGeneration, abortPcExecutionsForScope])

  const handleSend = async (
    overrideText?: string,
    overrideImage?: { base64: string; name?: string; mime?: string }
  ) => {
    const text = (overrideText ?? input).trim()
    const quotePromptSuffix = buildQuotePromptSuffix(quotedMessageContext)
    const queryTextWithQuote = quotePromptSuffix ? (text ? `${text}\n\n${quotePromptSuffix}` : quotePromptSuffix) : text
    const focusSkillsSnapshot = canUseFocusSkills
      ? selectedFocusSkills.map((x) => toCanonicalSkillName(x)).filter(Boolean)
      : []
    const imageListSnapshot: DraftImage[] = overrideImage
      ? [{ base64: overrideImage.base64, name: overrideImage.name || '图片.png', mime: overrideImage.mime || 'image/png' }]
      : selectedImages
    const primaryImageSnapshot = imageListSnapshot[0]
    const imageBase64Snapshot = primaryImageSnapshot?.base64 || null
    const imageNameSnapshot = primaryImageSnapshot?.name || '图片.png'
    const imageMimeSnapshot = primaryImageSnapshot?.mime || 'image/png'
    const fileBase64Snapshot = selectedFileBase64
    const fileNameSnapshot = selectedFileName || 'file.bin'
    const fileMimeSnapshot = selectedFileMime || 'application/octet-stream'
    const isTopoClawChat = isTopoClawConversation
    const bulkBatchSnapshot = isTopoClawChat ? bulkWorkspaceBatch : null
    const canSendText = !!text
    const canSendImage =
      !!(isCustomChatAssistant || isChatAssistant || isFriend || isCrossDevice) && imageListSnapshot.length > 0
    const canSendFile = !!isTopoClawChat && !!fileBase64Snapshot
    const canSendBatch = !!isTopoClawChat && !!bulkBatchSnapshot?.dirPath
    if ((!canSendText && !canSendImage && !canSendFile && !canSendBatch) || loading || !conversation) return
    if (!imei) return
    const sendRouteKey = executionRouteKey
    const sendConversationId = String(conversation.id || '').trim()
    const sendConversationName = String(conversation.name || '').trim()
    const sendBaseUrl = String(conversation.baseUrl || '').trim()
    const sendQueryText = String(text || '').replace(/\s+/g, ' ').trim()
    const setSendLoading = (next: boolean) => {
      if (sendRouteKey) {
        const prev = executionStateByRouteRef.current[sendRouteKey] || { loading: false, mobileRunning: false }
        const nextState = { ...prev, loading: next }
        executionStateByRouteRef.current[sendRouteKey] = nextState
        const prevRunning = !!(prev.loading || prev.mobileRunning)
        const nextRunning = !!(nextState.loading || nextState.mobileRunning)
        const isBackgroundRoute = currentExecutionRouteKeyRef.current !== sendRouteKey
        if (
          isBackgroundRoute &&
          prevRunning !== nextRunning &&
          onAssistantRunningChange &&
          sendConversationId
        ) {
          onAssistantRunningChange({
            conversationId: sendConversationId,
            conversationName: sendConversationName,
            baseUrl: sendBaseUrl,
            query: sendQueryText,
            running: nextRunning,
          })
        }
      }
      if (currentExecutionRouteKeyRef.current === sendRouteKey) {
        setLoading(next)
      }
    }

    const streamConversationId = conversation.id
    const streamSessionId = isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
    const streamGeneration = bumpChatStreamGeneration(streamConversationId, streamSessionId)
    const isStreamStale = () => getChatStreamGeneration(streamConversationId, streamSessionId) !== streamGeneration

    invalidateCloudHistoryThrottle(conversation.id, isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined)

    if (isCrossDevice) {
      const displayText = text || (imageListSnapshot.length > 1 ? `[图片${imageListSnapshot.length}张]` : imageBase64Snapshot ? '[图片]' : '')
      const imageB64 = imageBase64Snapshot
      if (!displayText.trim() && !imageB64) return
      setInput('')
      setSelectedImages([])
      setSelectedFileBase64(null)
      setSelectedFileName('file.bin')
      setSelectedFileMime('application/octet-stream')
      setSelectedFocusSkills([])
      setQuotedMessageContext(null)
      const pendingId = uuidv4()
      const userMsg: Message = {
        id: pendingId,
        sender: '我',
        content: displayText,
        type: 'user',
        timestamp: Date.now(),
        ...(imageB64 ? { messageType: 'file' as const, fileBase64: imageB64, fileName: imageNameSnapshot } : {}),
      }
      setMessages((prev) => [...prev, userMsg])
      setSendLoading(true)
      try {
        const res = await sendCrossDeviceMessage(imei, displayText, {
          ...(imageB64 ? { imageBase64: imageB64 } : {}),
        })
        if (!res.success) throw new Error('发送失败')
        const finalId = res.message_id ?? pendingId
        if (res.message_id && res.message_id !== pendingId) {
          setMessages((prev) => prev.map((m) => (m.id === pendingId ? { ...m, id: finalId } : m)))
        }
        appendMessageToStorage(CONVERSATION_ID_ME, { ...userMsg, id: finalId })
        onUpdateLastMessage?.(CONVERSATION_ID_ME, displayText.slice(0, 50), Date.now(), {
          isFromMe: true,
          isViewing: true,
        })
      } catch (err) {
        const errMsg = formatApiError(err)
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: errMsg, type: 'system', timestamp: Date.now() },
        ])
      } finally {
        setSendLoading(false)
      }
      return
    }

    if (isImConversation && conversation.id) {
      const display = text
      if (!display) {
        setSendLoading(false)
        return
      }
      const threadId = `${chatActorId}_${conversation.id}_local`
      const userMsg: Message = {
        id: uuidv4(),
        sender: '用户自己',
        content: display,
        type: 'user',
        timestamp: Date.now(),
      }
      const assistantMsgId = uuidv4()
      setMessages((prev) => [
        ...prev,
        userMsg,
        {
          id: assistantMsgId,
          sender: 'TopoClaw',
          content: '',
          type: 'assistant',
          timestamp: Date.now(),
        },
      ])
      appendMessageToStorage(conversation.id, userMsg)
      setQuotedMessageContext(null)
      onUpdateLastMessage?.(conversation.id, display.slice(0, 50), userMsg.timestamp, {
        isFromMe: true,
        isViewing: true,
      })
      try {
        let baseUrl = getChatAssistantBaseUrl()
        try {
          baseUrl = await getDefaultBuiltinUrl('topoclaw')
        } catch {
          // fallback to chat assistant url
        }
        const imQuery = injectVisualExplainHint(injectTopoclawIdentityLine(queryTextWithQuote, {
          assistantId: DEFAULT_TOPOCLAW_ASSISTANT_ID,
          assistantBaseUrl: baseUrl,
          creatorNickname: userName,
          creatorImei: imei,
        }))
        const { fullText } = await sendChatAssistantMessageStream(
          { uuid: threadId, query: imQuery, images: [], imei },
          baseUrl,
          (delta) => {
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsgId ? { ...m, content: `${m.content}${delta}` } : m
              )
            )
          }
        )
        const finalText = stripNeedExecutionGuide(fullText || '').trim() || fullText || '已处理'
        const finalAssistantMsg: Message = {
          id: assistantMsgId,
          sender: 'TopoClaw',
          content: finalText,
          type: 'assistant',
          timestamp: Date.now(),
        }
        setMessages((prev) => prev.map((m) => (m.id === assistantMsgId ? finalAssistantMsg : m)))
        appendMessageToStorage(conversation.id, finalAssistantMsg)
        onUpdateLastMessage?.(conversation.id, finalText.slice(0, 50), Date.now(), {
          isFromMe: false,
          isViewing: currentConvIdRef.current === conversation.id,
        })
      } catch (err) {
        const errMsg = formatApiError(err)
        setMessages((prev) => prev.map((m) => (m.id === assistantMsgId ? { ...m, content: errMsg, type: 'system' } : m)))
      } finally {
        setSendLoading(false)
      }
      return
    }

    const displayText = text || (imageListSnapshot.length > 1 ? `[图片${imageListSnapshot.length}张]` : imageBase64Snapshot ? '[图片]' : fileBase64Snapshot ? `[文件] ${fileNameSnapshot}` : '')
    const imageBase64ToSync = imageBase64Snapshot
    const fileBase64ToSync = isTopoClawChat ? fileBase64Snapshot : null
    const attachmentForMessage = fileBase64ToSync
      ? { messageType: 'file' as const, fileBase64: fileBase64ToSync, fileName: fileNameSnapshot }
      : (imageListSnapshot.length > 0
        ? {
            messageType: 'file' as const,
            fileBase64: imageListSnapshot[0].base64,
            fileName: imageListSnapshot[0].name,
            ...(imageListSnapshot.length > 1
              ? {
                  fileList: imageListSnapshot.map((img) => ({
                    fileBase64: img.base64,
                    fileName: img.name,
                  })),
                }
              : {}),
          }
        : null)
    let topoclawWorkspaceImagePath = ''
    let topoclawWorkspaceFilePath = ''
    if (isTopoClawChat && imageBase64ToSync && window.electronAPI?.saveChatImageToWorkspace) {
      const saved = await window.electronAPI.saveChatImageToWorkspace(
        buildDataUrl(imageBase64ToSync, imageMimeSnapshot, 'image/png'),
        imageNameSnapshot
      )
      if (saved.ok && saved.path) {
        topoclawWorkspaceImagePath = saved.path
      } else if (saved.error) {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: `图片落盘失败：${saved.error}`, type: 'system', timestamp: Date.now() },
        ])
      }
    }
    if (isTopoClawChat && fileBase64ToSync && window.electronAPI?.saveChatFileToWorkspace) {
      const saved = await window.electronAPI.saveChatFileToWorkspace(
        buildDataUrl(fileBase64ToSync, fileMimeSnapshot),
        fileNameSnapshot
      )
      if (saved.ok && saved.path) {
        topoclawWorkspaceFilePath = saved.path
      } else if (saved.error) {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: `文件落盘失败：${saved.error}`, type: 'system', timestamp: Date.now() },
        ])
      }
    }
    const topoclawSingleHint = topoclawWorkspaceFilePath
      ? buildTopoclawWorkspaceHint(topoclawWorkspaceFilePath, '文件')
      : (topoclawWorkspaceImagePath ? buildTopoclawWorkspaceHint(topoclawWorkspaceImagePath, '图片') : '')
    const topoclawBatchHint =
      bulkBatchSnapshot?.dirPath
        ? buildTopoclawWorkspaceBatchHint(
            bulkBatchSnapshot.dirPath,
            bulkBatchSnapshot.total,
            bulkBatchSnapshot.success,
            bulkBatchSnapshot.failed
          )
        : ''
    const codeFolderRetrievalHint = isTopoClawChat ? buildIdeQaRetrievalHint(queryTextWithQuote || text) : ''
    const topoclawHint = [topoclawSingleHint, topoclawBatchHint, codeFolderRetrievalHint].filter(Boolean).join('\n\n')
    const topoclawQueryRaw = topoclawHint ? (queryTextWithQuote ? `${queryTextWithQuote}\n\n${topoclawHint}` : topoclawHint) : queryTextWithQuote
    const topoclawQueryText = injectTopoclawIdentityLine(topoclawQueryRaw, {
      assistantId: conversation?.id,
      assistantBaseUrl: customAssistant?.baseUrl ?? conversation?.baseUrl,
      creatorNickname: userName,
      creatorImei: imei,
    })
    const topoclawAnswerQueryText = injectVisualExplainHint(topoclawQueryText)
    setInput('')
    setSelectedImages([])
    setSelectedFileBase64(null)
    setSelectedFileName('file.bin')
    setSelectedFileMime('application/octet-stream')
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
    setSelectedFocusSkills([])
    setShowSkillPicker(false)
    setSkillKeyword('')
    setQuotedMessageContext(null)
    setSendLoading(true)
    try {
      if (isFriend && conversation.id) {
        const sendConvId = conversation.id
        const targetImei = conversation.id.replace(/^friend_/, '')
        if (!targetImei) {
          setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '会话格式错误', type: 'system', timestamp: Date.now() }])
          setSendLoading(false)
          return
        }
        const cloneQueryContext = await buildCloneQueryContext({
          friendImei: targetImei,
          friendName: conversation.name,
          conversationId: sendConvId,
        })
        const pendingId = uuidv4()
        const ackPromise = new Promise<{ message_id: string; target_online: boolean }>((resolve, reject) => {
          const t = window.setTimeout(() => {
            friendWsWaitersRef.current.delete(pendingId)
            reject(new Error('发送超时，请确认客服服务已启动且本机 WebSocket 已连接'))
          }, 30000)
          friendWsWaitersRef.current.set(pendingId, {
            resolve: (v) => {
              window.clearTimeout(t)
              friendWsWaitersRef.current.delete(pendingId)
              resolve(v)
            },
            reject: (e) => {
              window.clearTimeout(t)
              friendWsWaitersRef.current.delete(pendingId)
              reject(e)
            },
          })
        })
        const sent = wsSend({
          type: 'friend_message',
          targetImei,
          content: text,
          message_type: imageBase64ToSync ? 'image' : 'text',
          message_id: pendingId,
          ...(cloneQueryContext ? { clone_query_context: cloneQueryContext } : {}),
          ...(imageBase64ToSync ? { imageBase64: imageBase64ToSync } : {}),
        })
        if (!sent) {
          const w = friendWsWaitersRef.current.get(pendingId)
          friendWsWaitersRef.current.delete(pendingId)
          w?.reject(new Error('WebSocket 未就绪，请稍后重试'))
        }
        const res = await ackPromise
        const userMsg: Message = {
          id: res.message_id || pendingId,
          sender: '我',
          content: displayText,
          type: 'user',
          timestamp: Date.now(),
          messageSource: 'user',
          ...(attachmentForMessage ?? {}),
        }
        setMessages((prev) => {
          // ACK 回来前可能已收到 friend_sync_message，或用户已切会话；这里必须做去重与会话守卫
          if (currentConvIdRef.current !== sendConvId) return prev
          if (prev.some((m) => m.id === userMsg.id)) return prev
          return [...prev, userMsg]
        })
        appendMessageToStorage(sendConvId, userMsg)
        onUpdateLastMessage?.(sendConvId, displayText.slice(0, 50), Date.now(), { isFromMe: true, isViewing: currentConvIdRef.current === sendConvId })
        triggerConversationSummary(sendConvId, conversation?.name)
      } else if (isGroup && conversation.id) {
        const groupId = toServerGroupId(conversation.id)
        if (conversation.id === CONVERSATION_ID_GROUP) {
          setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '默认群组「好友群」暂不可用', type: 'system', timestamp: Date.now() }])
          setSendLoading(false)
          return
        }
        if (!wsSend) {
          setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: 'WebSocket 未连接，请检查网络', type: 'system', timestamp: Date.now() }])
          setSendLoading(false)
          return
        }
        const userMsg: Message = {
          id: uuidv4(),
          sender: '我',
          content: text,
          type: 'user',
          timestamp: Date.now(),
          messageSource: 'user',
          ...(attachmentForMessage ?? {}),
        }
        setMessages((prev) => [...prev, userMsg])
        onUpdateLastMessage?.(conversation.id, text.slice(0, 50), Date.now(), { isFromMe: true, isViewing: true })
        triggerConversationSummary(conversation.id, conversation.name)
        const freeDiscoveryEnabled = conversation.groupFreeDiscoveryEnabled === true
        const assistantMutedEnabled = conversation.groupAssistantMutedEnabled === true
        const workflowModeEnabled = conversation.groupWorkflowModeEnabled === true
        wsSend({
          type: 'group_message',
          groupId,
          content: text,
          message_type: imageBase64ToSync ? 'image' : 'text',
          // 自由发言开启后改由服务端统一分发给所有助手，避免本地链路与服务端重复触发。
          skip_server_assistant_dispatch: freeDiscoveryEnabled && !assistantMutedEnabled && !workflowModeEnabled ? false : true,
          timestamp: Date.now(),
          ...(imageBase64ToSync ? { imageBase64: imageBase64ToSync } : {}),
        })
        const groupPromptMessages =
          messagesOwnerRef.current === conversation.id
            ? messages
            : (loadLocalMessagesForConversation(conversation.id) as Message[])
        const buildGroupInvocationContext = (query: string): string => {
          const groupName = String(conversation.name || '未命名群组').trim()
          const senderDisplay = String(userName || '我').trim() || '我'
          const sections: string[] = []
          sections.push('【当前群聊信息】')
          sections.push(`群名称: ${groupName}`)

          if (groupMembersWithNames.length > 0) {
            sections.push('群成员（可通过 @昵称 或 @IMEI 指定 ta 回答）:')
            for (const m of groupMembersWithNames) {
              const display = String(m.displayName || m.imei || '').trim() || m.imei
              sections.push(`  - ${display}（IMEI: ${m.imei}）`)
            }
          }

          const assistants = conversation.assistants ?? []
          if (assistants.length > 0) {
            sections.push('群内助手（可通过 @助手名 或 @助手ID 指定其回答）:')
            for (const a of assistants) {
              const displayName = groupAssistantDisplayNameMap[a.id] ?? a.name ?? a.id
              const resolvedConfig = resolveGroupAssistantConfig(a.id, a.name, conversation.assistantConfigs)
              const intro = trimGroupPromptLine(
                String(resolvedConfig?.intro || getCustomAssistantById(a.id)?.intro || '')
              )
              sections.push(intro ? `  - ${displayName}（ID: ${a.id}）：${intro}` : `  - ${displayName}（ID: ${a.id}）`)
            }
            sections.push('协作提醒：当问题更适合其他对象处理时，请建议用户使用 @某助手 或 @某成员 来指定回答对象。')
          }

          const recent = [...groupPromptMessages, userMsg]
            .filter((m) => String(m.content || '').trim())
            .slice(-GROUP_PROMPT_RECENT_LIMIT)
          if (recent.length > 0) {
            sections.push('【群聊最近消息（按时间从旧到新）】')
            for (const m of recent) {
              const sender = String(m.sender || '').trim() || '群成员'
              const contentLine = trimGroupPromptLine(String(m.content || ''))
              sections.push(`- ${sender}: ${contentLine || '[空消息]'}`)
            }
          }

          const q = String(query || '').trim()
          const senderTag = `【发件人：${senderDisplay}】`
          sections.push('【用户消息】')
          sections.push(q ? `${senderTag}${q}` : `${senderTag}[图片/文件消息]`)
          return `${sections.join('\n')}\n`
        }
        if (!freeDiscoveryEnabled && !assistantMutedEnabled && !workflowModeEnabled) {
        /** 检测 @ 并触发小助手：自动执行小助手 + 群内其他执行类小助手 */
        const assistantBaseNameCount = new Map<string, number>()
        for (const a of conversation.assistants ?? []) {
          const key = String(a.name || '').trim().toLowerCase()
          if (!key) continue
          assistantBaseNameCount.set(key, (assistantBaseNameCount.get(key) ?? 0) + 1)
        }
        const buildAssistantMentionTokens = (a: { id: string; name: string }): string[] => {
          const displayName = groupAssistantDisplayNameMap[a.id] ?? a.name
          const out = [`@${displayName}`]
          const baseName = String(a.name || '').trim()
          const sameBaseNameCount = assistantBaseNameCount.get(baseName.toLowerCase()) ?? 0
          // When duplicated names exist, only displayName is considered to avoid one @ hitting all homonyms.
          const allowRawBaseAlias = displayName === baseName || sameBaseNameCount <= 1
          if (allowRawBaseAlias && displayName !== baseName) out.push(`@${baseName}`)
          return out
        }
        const toStrip = ['@自动执行小助手', '@小助手', '@assistant', '@自动执行助手']
        for (const a of conversation.assistants ?? []) {
          toStrip.push(...buildAssistantMentionTokens(a))
        }
        let stripped = text
        for (const m of toStrip) {
          stripped = stripped.replace(new RegExp(`${escapeRegExp(m)}(?=$|[\\s,，:：;；、。!！?？\\n\\t])\\s*`, 'gi'), '')
        }
        stripped = stripped.replace(/\s+/g, ' ').trim()
        const autoExecMentions = ['@自动执行小助手', '@小助手', '@assistant', '@自动执行助手']
        const isAutoExecMentioned = autoExecMentions.some((m) => hasExplicitMentionToken(text, m))
        if (isAutoExecMentioned && stripped) {
          const assistantRolePrompt = getGroupAssistantRolePrompt('assistant', conversation.assistantConfigs)
          const wrappedExecuteQuery = injectGroupAssistantRolePrompt(
            buildGroupInvocationContext(stripped),
            assistantRolePrompt
          )
          const res = await sendExecuteCommand(imei, wrappedExecuteQuery, sessionUuidRef.current, undefined, conversation.id)
          if (!res.success) {
            setMessages((prev) => [
              ...prev,
              {
                id: uuidv4(),
                sender: '自动执行小助手',
                content: '手机端不在线，无法执行。请确保手机已打开应用并保持连接',
                type: 'assistant',
                timestamp: Date.now(),
              },
            ])
          }
        }
        for (const a of conversation.assistants ?? []) {
          if (a.id === 'assistant') continue
          const assistantMentionTokens = buildAssistantMentionTokens(a)
          if (!assistantMentionTokens.some((m) => hasExplicitMentionToken(text, m))) continue
          const custom = resolveGroupAssistantConfig(a.id, a.name, conversation.assistantConfigs)
          if (!custom?.baseUrl) {
            console.warn('[群聊@小助手] 未找到小助手配置（含群组配置）:', { assistantId: a.id, assistantName: a.name })
            continue
          }

          if (hasChat(custom)) {
            /** 群内 @ 聊天类小助手：群组管理者用统一 threadId（不含 imei）共享上下文，其他助手按用户隔离 */
            const threadId = hasGroupManager(custom)
              ? `group_${conversation.id}_${a.id}`
              : (imei ? `${imei}_group_${conversation.id}_${a.id}` : `group_${conversation.id}_${a.id}_${Date.now()}`)
            const assistantMsgId = uuidv4()
            const assistantMsg: Message = {
              id: assistantMsgId,
              sender: groupAssistantDisplayNameMap[a.id] ?? a.name,
              content: '',
              type: 'assistant',
              timestamp: Date.now(),
            }
            setMessages((prev) => [...prev, assistantMsg])
            const groupConvId = conversation.id
            const targetBaseQuery = injectTopoclawIdentityLine(buildGroupInvocationContext(stripped), {
              assistantId: a.id,
              assistantBaseUrl: custom.baseUrl,
              creatorNickname: conversation.assistantConfigs?.[a.id]?.creator_nickname,
              creatorImei: conversation.assistantConfigs?.[a.id]?.creator_imei,
            })
            const targetQuery = hasGroupManager(custom)
              ? injectGroupManagerIdentityLine(targetBaseQuery, { groupName: conversation.name })
              : targetBaseQuery
            const wrappedQuery = injectGroupAssistantRolePrompt(targetQuery, custom.rolePrompt)
            const creatorImei = resolveGroupAssistantOwnerImei(a.id)
            const isMyTopoclawCloneInGroup =
              !!imei &&
              creatorImei === imei &&
              isBuiltinTopoclawTarget(a.id, custom.baseUrl)
            sendGroupManagerChatViaPoolOrStream(
              {
                threadId,
                message: wrappedQuery,
                images: [],
                imei,
                baseUrl: custom.baseUrl,
                isGroupManager: hasGroupManager(custom),
              },
              {
                onDelta: (delta) => {
                  setMessages((prev) => {
                    if (currentConvIdRef.current !== groupConvId) return prev
                    return prev.map((m) => (m.id === assistantMsgId ? { ...m, content: m.content + delta } : m))
                  })
                },
                // 群聊中默认不透传 need_execution，避免重复触发。
                // 例外：当前用户自己的 TopoClaw 分身（默认 custom_topoclaw）在群内被 @ 时，
                // 允许像私聊一样发起跨设备执行到“自己绑定手机”。
                onNeedExecution: isMyTopoclawCloneInGroup && imei && conversation.id
                  ? (chatSummary: string) => {
                      const execUuid = `group_${conversation.id}_${a.id}_${Date.now()}`
                      sendExecuteForAssistant(creatorImei, targetQuery, execUuid, custom.baseUrl, conversation.id, chatSummary)
                        .then((res) => {
                          if (res.success) {
                            setMessages((prev) => [
                              ...prev,
                              {
                                id: uuidv4(),
                                sender: '系统',
                                content: '已向手机推送执行指令，请在手机端确认',
                                type: 'system',
                                timestamp: Date.now(),
                              },
                            ])
                          }
                        })
                        .catch(() => {})
                    }
                  : undefined,
              }
            )
              .then(({ fullText }) => {
                setMessages((prev) =>
                  prev.map((m) => (m.id === assistantMsgId ? { ...m, content: stripNeedExecutionGuide(m.content) || fullText } : m))
                )
                if (imei && fullText) {
                  appendCustomAssistantChat(imei, a.id, stripped, fullText, a.name).catch(() => {})
                  sendAssistantGroupMessage(imei, groupId, fullText, groupAssistantDisplayNameMap[a.id] ?? a.name, a.id).catch(() => {})
                }
              })
              .catch((err) => {
                const errMsg = formatApiError(err)
                setMessages((prev) =>
                  prev.map((m) => (m.id === assistantMsgId ? { ...m, content: errMsg } : m))
                )
              })
            continue
          }

          if (!hasExecutionMobile(custom) && !hasExecutionPc(custom)) continue
          const execUuid = `group_${conversation.id}_${a.id}_${Date.now()}`
          const chatSummary = stripped.slice(0, 80) || undefined
          const execQuery = injectTopoclawIdentityLine(buildGroupInvocationContext(stripped), {
            assistantId: a.id,
            assistantBaseUrl: custom.baseUrl,
            creatorNickname: conversation.assistantConfigs?.[a.id]?.creator_nickname,
            creatorImei: conversation.assistantConfigs?.[a.id]?.creator_imei,
          })
          const execTargetImei = resolveGroupAssistantOwnerImei(a.id)
          sendExecuteForAssistant(execTargetImei, execQuery, execUuid, custom.baseUrl, conversation.id, chatSummary).then((res) => {
            if (!res.success) {
              setMessages((prev) => [
                ...prev,
                {
                  id: uuidv4(),
                  sender: groupAssistantDisplayNameMap[a.id] ?? a.name,
                  content: '手机端不在线，无法执行。请确保手机已打开应用并保持连接',
                  type: 'assistant',
                  timestamp: Date.now(),
                },
              ])
            }
          }).catch(() => {})
        }
        /** 群组管理者：未 @ 任何小助手时，由群组管理者统一回复（仅当群内有群组管理者时生效） */
        const hasMentionedAnyAssistant =
          isAutoExecMentioned ||
          (conversation.assistants ?? []).some((a) => {
            if (a.id === 'assistant') return false
            const assistantMentionTokens = buildAssistantMentionTokens(a)
            return assistantMentionTokens.some((m) => hasExplicitMentionToken(text, m))
          })
        const groupManagerAssistant = (conversation.assistants ?? []).find((a) => {
          const custom = resolveGroupAssistantConfig(a.id, a.name, conversation.assistantConfigs)
          return custom && hasGroupManager(custom) && hasChat(custom)
        })
        if (!hasMentionedAnyAssistant && (stripped || imageBase64ToSync) && (conversation.assistants ?? []).length > 0 && !groupManagerAssistant && imei) {
          setMessages((prev) => [
            ...prev,
            { id: uuidv4(), sender: '系统', content: '提示：群内暂无「群组管理者」。若希望直接发消息时有小助手回复，请通过通讯录→群组主页→管理，为某个聊天小助手设置「群组管理者」。', type: 'system', timestamp: Date.now() },
          ])
        }
        if (
          !hasMentionedAnyAssistant &&
          groupManagerAssistant &&
          (stripped || imageBase64ToSync) &&
          imei
        ) {
          const a = groupManagerAssistant
          const custom = resolveGroupAssistantConfig(a.id, a.name, conversation.assistantConfigs)
          if (custom?.baseUrl) {
            /** 群组管理小助手使用统一 threadId，同一群组所有用户共享同一上下文 */
            const threadId = `group_${conversation.id}_${a.id}`
            const assistantMsgId = uuidv4()
            const assistantMsg: Message = {
              id: assistantMsgId,
              sender: groupAssistantDisplayNameMap[a.id] ?? a.name,
              content: '',
              type: 'assistant',
              timestamp: Date.now(),
            }
            setMessages((prev) => [...prev, assistantMsg])
            const groupConvId = conversation.id
            const userQuery = stripped || (imageBase64ToSync ? '[图片]' : '')
            const gmBaseQuery = injectTopoclawIdentityLine(buildGroupInvocationContext(userQuery), {
              assistantId: a.id,
              assistantBaseUrl: custom.baseUrl,
              creatorNickname: conversation.assistantConfigs?.[a.id]?.creator_nickname,
              creatorImei: conversation.assistantConfigs?.[a.id]?.creator_imei,
            })
            const gmQuery = injectGroupManagerIdentityLine(gmBaseQuery, { groupName: conversation.name })
            const wrappedQuery = injectGroupAssistantRolePrompt(gmQuery, custom.rolePrompt)
            sendGroupManagerChatViaPoolOrStream(
              {
                threadId,
                message: wrappedQuery,
                images: imageBase64ToSync ? [imageBase64ToSync] : [],
                imei,
                baseUrl: custom.baseUrl,
                isGroupManager: true,
              },
              {
                onDelta: (delta) => {
                  setMessages((prev) => {
                    if (currentConvIdRef.current !== groupConvId) return prev
                    return prev.map((m) => (m.id === assistantMsgId ? { ...m, content: m.content + delta } : m))
                  })
                },
                onNeedExecution: imei && groupConvId
                  ? (chatSummary: string) => {
                      const execUuid = `group_${groupConvId}_${a.id}_${Date.now()}`
                      const gmExecBaseQuery = injectTopoclawIdentityLine(stripped || '', {
                        assistantId: a.id,
                        assistantBaseUrl: custom.baseUrl,
                        creatorNickname: conversation.assistantConfigs?.[a.id]?.creator_nickname,
                        creatorImei: conversation.assistantConfigs?.[a.id]?.creator_imei,
                      })
                      const gmExecQuery = injectGroupManagerIdentityLine(gmExecBaseQuery, { groupName: conversation.name })
                      const execTargetImei = resolveGroupAssistantOwnerImei(a.id)
                      sendExecuteForAssistant(execTargetImei, gmExecQuery, execUuid, custom.baseUrl, groupConvId, chatSummary).catch(
                        () => {}
                      )
                    }
                  : undefined,
              }
            )
              .then(({ fullText }) => {
                setMessages((prev) =>
                  prev.map((m) => (m.id === assistantMsgId ? { ...m, content: stripNeedExecutionGuide(m.content) || fullText } : m))
                )
                if (imei && fullText) {
                  appendCustomAssistantChat(imei, a.id, stripped || '', fullText, a.name).catch(() => {})
                  sendAssistantGroupMessage(imei, groupId, fullText, groupAssistantDisplayNameMap[a.id] ?? a.name, a.id).catch(() => {})
                  /** 解析群组管理者回复中的 @小助手 指令，自动触发对应小助手执行（含内置自动执行小助手） */
                  const mention = parseGroupManagerMention(fullText, conversation.assistants ?? [])
                  if (mention && mention.assistant.id !== a.id) {
                    const target = mention.assistant
                    const cmd = mention.command
                    const targetCustom = resolveGroupAssistantConfig(target.id, target.name, conversation.assistantConfigs)
                    if (!targetCustom?.baseUrl && target.id !== 'assistant') return
                    const resolvedTarget = targetCustom
                    if (target.id === 'assistant') {
                      const assistantRolePrompt = getGroupAssistantRolePrompt('assistant', conversation.assistantConfigs)
                      const wrappedExecuteQuery = injectGroupAssistantRolePrompt(cmd, assistantRolePrompt)
                      sendExecuteCommand(imei, wrappedExecuteQuery, sessionUuidRef.current, undefined, conversation.id).then((res) => {
                        if (!res.success) {
                          setMessages((prev) => [
                            ...prev,
                            {
                              id: uuidv4(),
                              sender: groupAssistantDisplayNameMap[target.id] ?? target.name,
                              content: '手机端不在线，无法执行。请确保手机已打开应用并保持连接',
                              type: 'assistant',
                              timestamp: Date.now(),
                            },
                          ])
                        } else if (res.message_id) {
                          pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                            groupConvId: conversation.id,
                            userQuery,
                            executedAssistant: { id: target.id, name: groupAssistantDisplayNameMap[target.id] ?? target.name },
                            executedCommand: cmd,
                            groupManagerBaseUrl: custom.baseUrl,
                            groupManagerName: groupAssistantDisplayNameMap[a.id] ?? a.name,
                            groupManagerId: a.id,
                            assistants: conversation.assistants ?? [],
                            assistantConfigs: conversation.assistantConfigs,
                            members: groupMembersWithNames,
                            senderName: userName,
                            round: 1,
                          })
                        }
                      }).catch(() => {})
                      return
                    }
                    if (resolvedTarget && hasChat(resolvedTarget)) {
                      const targetMsgId = uuidv4()
                      const targetMsg: Message = { id: targetMsgId, sender: groupAssistantDisplayNameMap[target.id] ?? target.name, content: '', type: 'assistant', timestamp: Date.now() }
                      setMessages((prev) => [...prev, targetMsg])
                      const targetThreadId = `${imei}_group_${conversation.id}_${target.id}`
                      const delegatedBaseCmd = injectTopoclawIdentityLine(cmd, {
                        assistantId: target.id,
                        assistantBaseUrl: resolvedTarget.baseUrl,
                        creatorNickname: conversation.assistantConfigs?.[target.id]?.creator_nickname,
                        creatorImei: conversation.assistantConfigs?.[target.id]?.creator_imei,
                      })
                      const delegatedCmd = hasGroupManager(resolvedTarget)
                        ? injectGroupManagerIdentityLine(delegatedBaseCmd, { groupName: conversation.name })
                        : delegatedBaseCmd
                      const wrappedCmd = injectGroupAssistantRolePrompt(delegatedCmd, resolvedTarget.rolePrompt)
                      sendGroupManagerChatViaPoolOrStream(
                        {
                          threadId: targetThreadId,
                          message: wrappedCmd,
                          images: [],
                          imei,
                          baseUrl: resolvedTarget.baseUrl,
                          isGroupManager: hasGroupManager(resolvedTarget),
                        },
                        {
                          onDelta: (delta) => {
                            setMessages((prev) => {
                              if (currentConvIdRef.current !== groupConvId) return prev
                              return prev.map((m) => (m.id === targetMsgId ? { ...m, content: m.content + delta } : m))
                            })
                          },
                          onNeedExecution:
                            imei && groupConvId
                              ? (chatSummary: string) => {
                                  const execUuid = `group_${groupConvId}_${target.id}_${Date.now()}`
                                  const delegatedExecBase = injectTopoclawIdentityLine(cmd, {
                                    assistantId: target.id,
                                    assistantBaseUrl: resolvedTarget.baseUrl,
                                    creatorNickname: conversation.assistantConfigs?.[target.id]?.creator_nickname,
                                    creatorImei: conversation.assistantConfigs?.[target.id]?.creator_imei,
                                  })
                                  const delegatedExec = hasGroupManager(resolvedTarget)
                                    ? injectGroupManagerIdentityLine(delegatedExecBase, { groupName: conversation.name })
                                    : delegatedExecBase
                                  const execTargetImei = resolveGroupAssistantOwnerImei(target.id)
                                  sendExecuteForAssistant(execTargetImei, delegatedExec, execUuid, resolvedTarget.baseUrl, groupConvId, chatSummary).catch(() => {})
                                }
                              : undefined,
                        }
                      )
                        .then(({ fullText: ft }) => {
                          setMessages((prev) => prev.map((m) => (m.id === targetMsgId ? { ...m, content: stripNeedExecutionGuide(m.content) || ft } : m)))
                          if (imei && ft) {
                            appendCustomAssistantChat(imei, target.id, cmd, ft, target.name).catch(() => {})
                            sendAssistantGroupMessage(imei, groupId, ft, groupAssistantDisplayNameMap[target.id] ?? target.name, target.id).catch(() => {})
                          }
                          /** 无论什么小助手，都由群组管理小助手收尾；群组管理用统一 threadId 共享上下文 */
                          const gmThreadId = `group_${groupConvId}_${a.id}`
                          const doFeedbackAndHandleReply = (
                            uq: string,
                            execAsst: { id: string; name: string },
                            execCmd: string,
                            result: string,
                            rnd: number
                          ): Promise<void> => {
                            const roundGmMsgId = uuidv4()
                            const roundGmMsg: Message = { id: roundGmMsgId, sender: groupAssistantDisplayNameMap[a.id] ?? a.name, content: '', type: 'assistant', timestamp: Date.now() }
                            if (currentConvIdRef.current === groupConvId) {
                              setMessages((prev) => [...prev, roundGmMsg])
                            }
                            const gmRoundFeedbackQuery = injectGroupManagerIdentityLine(
                              buildExecutionFeedbackMessage(uq, execAsst.name, execCmd, result, rnd, conversation.assistants ?? [], {
                                members: groupMembersWithNames,
                                senderName: userName,
                              }),
                              { groupName: conversation.name }
                            )
                            return sendGroupManagerChatViaPoolOrStream(
                              {
                                threadId: gmThreadId,
                                message: injectGroupAssistantRolePrompt(gmRoundFeedbackQuery, custom.rolePrompt),
                                images: [],
                                imei,
                                baseUrl: custom.baseUrl,
                                isGroupManager: true,
                              },
                              {
                                onDelta: (delta) => {
                                  if (currentConvIdRef.current === groupConvId) {
                                    setMessages((prev) => prev.map((ma) => (ma.id === roundGmMsgId ? { ...ma, content: ma.content + delta } : ma)))
                                  }
                                },
                              }
                            ).then(({ fullText: gmFullText }) => {
                              const stripped = stripNeedExecutionGuide(gmFullText) || gmFullText
                              appendMessageToStorage(groupConvId, { ...roundGmMsg, content: stripped })
                              if (imei && groupId) {
                                sendAssistantGroupMessage(imei, groupId, stripped, groupAssistantDisplayNameMap[a.id] ?? a.name, a.id).catch(() => {})
                              }
                              const nextMention = parseGroupManagerMention(stripped, conversation.assistants ?? [])
                              if (!nextMention || nextMention.assistant.id === a.id || rnd >= MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS) return
                              const nextTarget = nextMention.assistant
                              const nextCmd = nextMention.command
                              const nextCustom = resolveGroupAssistantConfig(nextTarget.id, nextTarget.name, conversation.assistantConfigs)
                              if (nextTarget.id === 'assistant') {
                                const assistantRolePrompt = getGroupAssistantRolePrompt('assistant', conversation.assistantConfigs)
                                const wrappedExecuteQuery = injectGroupAssistantRolePrompt(nextCmd, assistantRolePrompt)
                                sendExecuteCommand(imei!, wrappedExecuteQuery, sessionUuidRef.current, undefined, groupConvId).then((res) => {
                                  if (res.message_id) {
                                    pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                                      groupConvId,
                                      userQuery: uq,
                                      executedAssistant: nextTarget,
                                      executedCommand: nextCmd,
                                      groupManagerBaseUrl: custom.baseUrl,
                                      groupManagerName: groupAssistantDisplayNameMap[a.id] ?? a.name,
                                      groupManagerId: a.id,
                                      assistants: conversation.assistants ?? [],
                                      assistantConfigs: conversation.assistantConfigs,
                                      members: groupMembersWithNames,
                                      senderName: userName,
                                      round: rnd + 1,
                                    })
                                  }
                                }).catch(() => {})
                                return
                              }
                              if (nextCustom && hasChat(nextCustom)) {
                                const nextMsgId = uuidv4()
                                const nextMsg: Message = { id: nextMsgId, sender: groupAssistantDisplayNameMap[nextTarget.id] ?? nextTarget.name, content: '', type: 'assistant', timestamp: Date.now() }
                                if (currentConvIdRef.current === groupConvId) {
                                  setMessages((prev) => [...prev, nextMsg])
                                }
                                const nextThreadId = `${imei}_group_${groupConvId}_${nextTarget.id}`
                                const nextCmdWithIdentity = hasGroupManager(nextCustom)
                                  ? injectGroupManagerIdentityLine(nextCmd, { groupName: conversation.name })
                                  : nextCmd
                                const wrappedNextCmd = injectGroupAssistantRolePrompt(nextCmdWithIdentity, nextCustom.rolePrompt)
                                return sendGroupManagerChatViaPoolOrStream(
                                  { threadId: nextThreadId, message: wrappedNextCmd, images: [], imei, baseUrl: nextCustom.baseUrl, isGroupManager: hasGroupManager(nextCustom) },
                                  {
                                    onDelta: (d) => {
                                      if (currentConvIdRef.current === groupConvId) {
                                        setMessages((prev) => prev.map((m) => (m.id === nextMsgId ? { ...m, content: m.content + d } : m)))
                                      }
                                    },
                                    onNeedExecution:
                                      imei && groupConvId
                                        ? (cs: string) => {
                                            const execUuid = `group_${groupConvId}_${nextTarget.id}_${Date.now()}`
                                            const nextExecBaseQuery = injectTopoclawIdentityLine(nextCmd, {
                                              assistantId: nextTarget.id,
                                              assistantBaseUrl: nextCustom.baseUrl,
                                              creatorNickname: conversation.assistantConfigs?.[nextTarget.id]?.creator_nickname,
                                              creatorImei: conversation.assistantConfigs?.[nextTarget.id]?.creator_imei,
                                            })
                                            const nextExecQuery = hasGroupManager(nextCustom)
                                              ? injectGroupManagerIdentityLine(nextExecBaseQuery, { groupName: conversation.name })
                                              : nextExecBaseQuery
                                            const execTargetImei = resolveGroupAssistantOwnerImei(nextTarget.id)
                                            sendExecuteForAssistant(execTargetImei, nextExecQuery, execUuid, nextCustom.baseUrl, groupConvId, cs).catch(() => {})
                                          }
                                        : undefined,
                                  }
                                ).then(({ fullText: nextFt }) => {
                                  if (currentConvIdRef.current === groupConvId) {
                                    setMessages((prev) => prev.map((m) => (m.id === nextMsgId ? { ...m, content: stripNeedExecutionGuide(m.content) || nextFt } : m)))
                                  }
                                  if (imei && nextFt) {
                                    appendCustomAssistantChat(imei, nextTarget.id, nextCmd, nextFt, nextTarget.name).catch(() => {})
                                    sendAssistantGroupMessage(imei, groupId, nextFt, groupAssistantDisplayNameMap[nextTarget.id] ?? nextTarget.name, nextTarget.id).catch(() => {})
                                  }
                                  return doFeedbackAndHandleReply(uq, nextTarget, nextCmd, nextFt, rnd + 1)
                                })
                              }
                              if (nextCustom && (hasExecutionMobile(nextCustom) || hasExecutionPc(nextCustom))) {
                                const execUuid = `group_${groupConvId}_${nextTarget.id}_${Date.now()}`
                                const chatSummary = nextCmd.slice(0, 80) || undefined
                                const nextExecBaseQuery = injectTopoclawIdentityLine(nextCmd, {
                                  assistantId: nextTarget.id,
                                  assistantBaseUrl: nextCustom.baseUrl,
                                  creatorNickname: conversation.assistantConfigs?.[nextTarget.id]?.creator_nickname,
                                  creatorImei: conversation.assistantConfigs?.[nextTarget.id]?.creator_imei,
                                })
                                const nextExecQuery = hasGroupManager(nextCustom)
                                  ? injectGroupManagerIdentityLine(nextExecBaseQuery, { groupName: conversation.name })
                                  : nextExecBaseQuery
                                const execTargetImei = resolveGroupAssistantOwnerImei(nextTarget.id)
                                sendExecuteForAssistant(execTargetImei, nextExecQuery, execUuid, nextCustom.baseUrl, groupConvId, chatSummary).then((res) => {
                                  if (res.message_id) {
                                    pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                                      groupConvId,
                                      userQuery: uq,
                                      executedAssistant: nextTarget,
                                      executedCommand: nextCmd,
                                      groupManagerBaseUrl: custom.baseUrl,
                                      groupManagerName: groupAssistantDisplayNameMap[a.id] ?? a.name,
                                      groupManagerId: a.id,
                                      assistants: conversation.assistants ?? [],
                                      assistantConfigs: conversation.assistantConfigs,
                                      members: groupMembersWithNames,
                                      senderName: userName,
                                      round: rnd + 1,
                                    })
                                  }
                                }).catch(() => {})
                              }
                            })
                          }
                          doFeedbackAndHandleReply(userQuery, target, cmd, ft, 1).catch(() => {})
                        })
                        .catch((err) => {
                          const errMsg = formatApiError(err)
                          setMessages((prev) => prev.map((m) => (m.id === targetMsgId ? { ...m, content: errMsg } : m)))
                        })
                      return
                    }
                    if (resolvedTarget && (hasExecutionMobile(resolvedTarget) || hasExecutionPc(resolvedTarget))) {
                      const execUuid = `group_${conversation.id}_${target.id}_${Date.now()}`
                      const chatSummary = cmd.slice(0, 80) || undefined
                      const resolvedExecQuery = injectTopoclawIdentityLine(cmd, {
                        assistantId: target.id,
                        assistantBaseUrl: resolvedTarget.baseUrl,
                        creatorNickname: conversation.assistantConfigs?.[target.id]?.creator_nickname,
                        creatorImei: conversation.assistantConfigs?.[target.id]?.creator_imei,
                      })
                      const execTargetImei = resolveGroupAssistantOwnerImei(target.id)
                      sendExecuteForAssistant(execTargetImei, resolvedExecQuery, execUuid, resolvedTarget.baseUrl, conversation.id, chatSummary).then((res) => {
                        if (!res.success) {
                          setMessages((prev) => [
                            ...prev,
                            {
                              id: uuidv4(),
                              sender: groupAssistantDisplayNameMap[target.id] ?? target.name,
                              content: '手机端不在线，无法执行。请确保手机已打开应用并保持连接',
                              type: 'assistant',
                              timestamp: Date.now(),
                            },
                          ])
                        } else if (res.message_id) {
                          pendingGroupManagerFollowUpRef.current.set(res.message_id, {
                            groupConvId: conversation.id,
                            userQuery,
                            executedAssistant: { id: target.id, name: groupAssistantDisplayNameMap[target.id] ?? target.name },
                            executedCommand: cmd,
                            groupManagerBaseUrl: custom.baseUrl,
                            groupManagerName: groupAssistantDisplayNameMap[a.id] ?? a.name,
                            groupManagerId: a.id,
                            assistants: conversation.assistants ?? [],
                            assistantConfigs: conversation.assistantConfigs,
                            members: groupMembersWithNames,
                            senderName: userName,
                            round: 1,
                          })
                        }
                      }).catch(() => {})
                    }
                  }
                }
              })
              .catch((err) => {
                const errMsg = formatApiError(err)
                setMessages((prev) =>
                  prev.map((m) => (m.id === assistantMsgId ? { ...m, content: errMsg } : m))
                )
              })
          }
        } else if (
          !hasMentionedAnyAssistant &&
          (stripped || imageBase64ToSync) &&
          (conversation.assistants ?? []).some((a) => a.id !== 'assistant')
        ) {
          setMessages((prev) => [
            ...prev,
            {
              id: uuidv4(),
              sender: '系统',
              content: '提示：群内暂无「群组管理者」。若希望直接发消息时有小助手回复，请通过通讯录→群组主页→管理，为某个聊天小助手设置「群组管理者」。',
              type: 'system',
              timestamp: Date.now(),
            },
          ])
        }
        }
        if (assistantMutedEnabled && (text || imageBase64ToSync)) {
          setMessages((prev) => [
            ...prev,
            {
              id: uuidv4(),
              sender: '系统',
              content: '当前群组已开启「助手禁言」，助手不会参与回复。',
              type: 'system',
              timestamp: Date.now(),
            },
          ])
        }
        setSendLoading(false)
      } else if (isAssistant) {
        const res = await sendExecuteCommand(imei, text, sessionUuidRef.current)
        if (!res.success) {
          throw new Error('手机端不在线，请确保手机已打开应用并保持连接')
        }
        const userMsg: Message = {
          id: res.message_id ?? uuidv4(),
          sender: '我',
          content: text,
          type: 'user',
          timestamp: Date.now(),
        }
        setMessages((prev) => [...prev, userMsg])
      } else if (conversation.type === 'assistant') {
        if (customAssistant && !hasChat(customAssistant) && !hasExecutionPc(customAssistant)) {
          // 仅手机端执行：复刻「聊天+执行」下发方式，100% 默认触发
          const userMsg: Message = {
            id: uuidv4(),
            sender: '我',
            content: displayText,
            type: 'user',
            timestamp: Date.now(),
            ...(attachmentForMessage ?? {}),
          }
          setMessages((prev) => [...prev, userMsg])
          if (!imei) {
            setMessages((prev) => [
              ...prev,
              { id: uuidv4(), sender: '系统', content: '请先绑定设备后再发送', type: 'system' as const, timestamp: Date.now() },
            ])
            setSendLoading(false)
            return
          }
          const execUuid = `assistant_${conversation.id}_${Date.now()}`
          const chatSummary = (displayText || '').slice(0, 80) || undefined
          const singleExecQuery = injectTopoclawIdentityLine(queryTextWithQuote, {
            assistantId: conversation.id,
            assistantBaseUrl: customAssistant.baseUrl,
            creatorNickname: userName,
            creatorImei: imei,
          })
          sendExecuteForAssistant(imei, singleExecQuery, execUuid, customAssistant.baseUrl, conversation.id, chatSummary).then(
            (res) => {
              if (res.success) {
                setMessages((prev) => [
                  ...prev,
                  { id: uuidv4(), sender: '系统', content: '已向手机推送执行指令，请在手机端确认', type: 'system' as const, timestamp: Date.now() },
                ])
              } else {
                setMessages((prev) => [
                  ...prev,
                  {
                    id: uuidv4(),
                    sender: customAssistant?.name ?? '小助手',
                    content: '手机端不在线，请确保手机已打开应用并保持连接',
                    type: 'assistant' as const,
                    timestamp: Date.now(),
                  },
                ])
              }
            }
          ).catch(() => {
            setMessages((prev) => [
              ...prev,
              { id: uuidv4(), sender: '系统', content: '推送失败，请重试', type: 'system' as const, timestamp: Date.now() },
            ])
          })
          setSendLoading(false)
          return
        }
        if (customAssistant && !hasChat(customAssistant) && hasExecutionPc(customAssistant)) {
          // 仅电脑端执行：本地直接调用 runComputerUseLoop，复刻「仅手机端执行」的 100% 触发方式
          const userMsg: Message = {
            id: uuidv4(),
            sender: '我',
            content: displayText,
            type: 'user',
            timestamp: Date.now(),
            ...(attachmentForMessage ?? {}),
          }
          setMessages((prev) => [...prev, userMsg])
          const execUuid = `assistant_${conversation.id}_${Date.now()}`
          const chatSummary = (displayText || '').slice(0, 80) || undefined
          const assistantName = customAssistant?.name ?? '小助手'
          const assistantMsg: Message = {
            id: uuidv4(),
            sender: assistantName,
            content: '正在执行…',
            type: 'assistant',
            timestamp: Date.now(),
          }
          setMessages((prev) => [...prev, assistantMsg])
          runComputerUseLoopWithAbort(
            customAssistant.baseUrl,
            execUuid,
            queryTextWithQuote,
            chatSummary,
            { conversationId: conversation.id, sessionId: isMultiSessionCustom ? currentSessionId : undefined }
          )
            .then((r) => {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id
                    ? { ...m, content: r.success ? (r.content || '任务已完成') : `执行失败：${r.error || '未知错误'}` }
                    : m
                )
              )
            })
            .catch((e) => {
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsg.id ? { ...m, content: `执行异常：${String(e)}` } : m
                )
              )
            })
            .finally(() => setSendLoading(false))
          return
        }
        const userMsgId = uuidv4()
        const userMsg: Message = {
          id: userMsgId,
          sender: '我',
          content: displayText,
          type: 'user',
          timestamp: Date.now(),
          ...(attachmentForMessage ?? {}),
        }
        setMessages((prev) => [...prev, userMsg])
        appendMessageToStorage(
          conversation.id,
          userMsg,
          isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
        )
        const isTopoCloudBlockedForSend =
          isTopoClawSafeModeTarget && (topoSafeModeEnabled || (currentSessionId ? isTopoClawSessionSealed(currentSessionId) : false))
        if (isTopoCloudBlockedForSend && currentSessionId) {
          sealTopoClawSession(currentSessionId)
        }
        let userMsgWsSyncOk = false
        if (imei && (isChatAssistant || isCustomChatAssistant) && wsSend && !isTopoCloudBlockedForSend) {
          const convId =
            isMultiSessionCustom && currentSessionId
              ? `${conversation.id}__${currentSessionId}`
              : conversation?.id ?? 'assistant'
          userMsgWsSyncOk = wsSend({
            type: 'assistant_user_message',
            content: displayText,
            message_id: userMsgId,
            sender: '我',
            conversation_id: convId,
            timestamp: new Date().toISOString(),
            ...(imageBase64ToSync ? { file_base64: imageBase64ToSync, file_name: '图片.png', message_type: 'image' } : {}),
          })
        }
        if (isCustomChatAssistant && isMultiSessionCustom && currentSessionId && messages.length === 0) {
          const curSession = sessions.find((s) => s.id === currentSessionId)
          if (curSession?.title === '新对话') {
            const title = (displayText ? displayText.slice(0, 50).replace(/\s+/g, ' ').trim() : '[图片]') || '[图片]'
            updateSessionTitle(conversation.id, currentSessionId, title, sessionBaseUrl)
            setSessions((prev) =>
              prev.map((s) => (s.id === currentSessionId ? { ...s, title } : s))
            )
            // 同步更新后的 session 列表到云端，使手机端能拿到正确标题
            if (imei && sessionBaseUrl && !isTopoCloudBlockedForSend) {
              const list = loadSessions(conversation.id, sessionBaseUrl)
              syncSessions(imei, conversation.id, list, { baseUrl: sessionBaseUrl }).then(({ success, sessions: synced }) => {
                if (success && synced.length > 0) saveSessions(conversation.id, synced, sessionBaseUrl)
              }).catch(() => {})
            }
          }
        }
        const baseUrl =
          conversation.id === CONVERSATION_ID_CHAT_ASSISTANT ? getChatAssistantBaseUrl()
          : (customAssistant && hasChat(customAssistant)) ? customAssistant.baseUrl
          : undefined
        const imagesToSend = imageListSnapshot.map((x) => x.base64).filter((x) => x && x.length > 10)
        const chatAgentId = builtinRuntimeAgentId
        if ((conversation.id === CONVERSATION_ID_CHAT_ASSISTANT || isCustomChatAssistant) && baseUrl) {
          const chatAssistantConvId = conversation.id
          const assistantName = customAssistant?.name ?? '小助手'
          const buildThinkingSyncConversationId = () =>
            isMultiSessionCustom && currentSessionId
              ? `${conversation.id}__${currentSessionId}`
              : conversation.id
          const emitAssistantThinkingSync = (thinkingContent: string, assistantMessageId: string, senderName: string = assistantName) => {
            const normalizedThinking = String(thinkingContent || '').trim()
            if (!normalizedThinking || !imei || isTopoCloudBlockedForSend) return
            wsSend({
              type: 'assistant_thinking_sync',
              thinking_content: normalizedThinking,
              message_id: assistantMessageId,
              sender: senderName,
              conversation_id: buildThinkingSyncConversationId(),
              timestamp: new Date().toISOString(),
            })
          }
          if (isCustomChatAssistant) {
            if (isMultiSessionCustom && !currentSessionId) {
              setSendLoading(false)
              return
            }
            const assistantId = uuidv4()
            const assistantMsg: Message = {
              id: assistantId,
              sender: assistantName,
              content: '',
              type: 'assistant',
              timestamp: Date.now(),
            }
            setMessages((prev) => [...prev, assistantMsg])
            appendMessageToStorage(
              conversation.id,
              assistantMsg,
              isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
            )
            const threadId = chatThreadId || (imei ? `${imei}_${conversation.id}` : sessionUuidRef.current)
            let assistantMediaBase64 = ''
            let assistantMediaFileName = ''
            let assistantMediaFallbackContent = ''
            const assistantMediaItems: Array<{
              fileBase64: string
              fileName: string
              messageType: 'image' | 'file'
            }> = []
            const targetSessionId = isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
            const callbacks = {
              onDelta: (delta: string) => {
                if (isStreamStale()) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, content: (m.content || '') + delta }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId ? { ...m, content: m.content + delta } : m
                  )
                })
              },
              onReasoning: (reasoning: string) => {
                if (isStreamStale()) return
                const thinkingLine = String(reasoning || '').trim()
                if (!thinkingLine) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId
                      ? { ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }
                      : m
                  )
                })
                emitAssistantThinkingSync(thinkingLine, assistantId)
              },
              onMedia: (media: { fileBase64: string; fileName?: string; content?: string; messageType?: 'image' | 'file' }) => {
                if (isStreamStale()) return
                assistantMediaBase64 = media.fileBase64
                assistantMediaFileName = media.fileName || (media.messageType === 'file' ? '文件' : '图片.png')
                const mediaType = media.messageType === 'file' ? 'file' : 'image'
                const fallbackContent = (media.content || '').trim()
                if (fallbackContent) assistantMediaFallbackContent = fallbackContent
                const mediaIdx = assistantMediaItems.findIndex((item) =>
                  item.fileBase64 === media.fileBase64 ||
                  (item.fileName === assistantMediaFileName && item.messageType === mediaType)
                )
                const nextMediaItem = {
                  fileBase64: media.fileBase64,
                  fileName: assistantMediaFileName,
                  messageType: mediaType as 'image' | 'file',
                }
                if (mediaIdx >= 0) assistantMediaItems[mediaIdx] = nextMediaItem
                else assistantMediaItems.push(nextMediaItem)
                const newFile = { fileBase64: media.fileBase64, fileName: assistantMediaFileName }
                const accumulateMedia = (m: Message): Message => {
                  const textSeed = (m.content && m.content.trim()) ? m.content : (media.content || '')
                  const existing: Array<{ fileBase64: string; fileName?: string }> =
                    m.fileList && m.fileList.length > 0
                      ? m.fileList
                      : m.fileBase64
                        ? [{ fileBase64: m.fileBase64, fileName: m.fileName }]
                        : []
                  const duplicateIdx = existing.findIndex((f) =>
                    f.fileName && newFile.fileName && f.fileName === newFile.fileName
                  )
                  const nextList = duplicateIdx >= 0 ? existing : [...existing, newFile]
                  const mediaIndex = duplicateIdx >= 0 ? duplicateIdx : (nextList.length - 1)
                  return {
                    ...m,
                    content: appendAssistantMediaMarkerToContent(textSeed, mediaIndex),
                    messageType: 'file',
                    fileBase64: nextList[0].fileBase64,
                    fileName: nextList[0].fileName,
                    fileList: nextList.length > 1 ? nextList : undefined,
                  }
                }
                patchStoredMessageById(chatAssistantConvId, assistantId, accumulateMedia, targetSessionId)
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) => m.id === assistantId ? accumulateMedia(m) : m)
                })
              },
              onToolCall: (toolName: string) => {
                if (isStreamStale()) return
                const thinkingLine = `调用${toolName}工具`
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId
                      ? { ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }
                      : m
                  )
                })
                emitAssistantThinkingSync(thinkingLine, assistantId)
              },
              onSkillGenerated: (skill: Skill) => {
                if (isStreamStale()) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, generatedSkill: skill }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) => (m.id === assistantId ? { ...m, generatedSkill: skill } : m))
                })
              },
              onNeedExecution: imei && conversation.id && !isTopoCloudBlockedForSend
                ? (chatSummary: string) => {
                    if (isStreamStale()) return
                    const execUuid = `assistant_${conversation.id}_${Date.now()}`
                    if (customAssistant && hasExecutionPc(customAssistant)) {
                      const pcAssistantName = customAssistant?.name ?? '小助手'
                      const pcExecMsgId = uuidv4()
                      setMessages((prev) => [
                        ...prev,
                        { id: pcExecMsgId, sender: pcAssistantName, content: '正在执行…', type: 'assistant' as const, timestamp: Date.now() },
                      ])
                      runComputerUseLoopWithAbort(
                        baseUrl,
                        execUuid,
                        topoclawQueryText,
                        chatSummary,
                        { conversationId: conversation.id, sessionId: isMultiSessionCustom ? currentSessionId : undefined }
                      )
                        .then((r) => {
                          setMessages((prev) =>
                            prev.map((m) =>
                              m.id === pcExecMsgId
                                ? { ...m, content: r.success ? (r.content || '任务已完成') : `执行失败：${r.error || '未知错误'}` }
                                : m
                            )
                          )
                        })
                        .catch((e) => {
                          setMessages((prev) =>
                            prev.map((m) =>
                              m.id === pcExecMsgId ? { ...m, content: `执行异常：${String(e)}` } : m
                            )
                          )
                        })
                    }
                    if (!customAssistant || !hasExecutionPc(customAssistant) || hasExecutionMobile(customAssistant)) {
                      sendExecuteForAssistant(imei, topoclawQueryText, execUuid, baseUrl, conversation.id, chatSummary).then(
                        (res) => {
                          if (res.success) {
                            setMessages((prev) => [
                              ...prev,
                              { id: uuidv4(), sender: '系统', content: '已向手机推送执行指令，请在手机端确认', type: 'system' as const, timestamp: Date.now() },
                            ])
                          }
                        }
                      ).catch(() => {})
                    }
                  }
                : undefined,
            }
            const streamFallback = () =>
              sendChatAssistantMessageStream(
                {
                  uuid: threadId,
                  query: topoclawAnswerQueryText,
                  images: imagesToSend,
                  imei: imei ?? '',
                  focusSkills: focusSkillsSnapshot,
                },
                baseUrl,
                callbacks.onDelta,
                callbacks.onReasoning,
                callbacks.onMedia,
                callbacks.onToolCall,
                callbacks.onSkillGenerated,
                callbacks.onNeedExecution,
                chatAgentId
              )
            try {
              const shouldStrictAlignModel =
                builtinChatSlot === 'topoclaw' &&
                ((isCustomChatAssistant && !!customAssistant && isDefaultBuiltinUrl(customAssistant.baseUrl)) ||
                  conversation.id === CONVERSATION_ID_CHAT_ASSISTANT)
              if (shouldStrictAlignModel) {
                const aligned = await ensureBuiltinRuntimeModelsAligned()
                if (!aligned) {
                  showModelAlignRetryModal(builtinModelSwitchErr || '模型切换中，请稍等几秒再试')
                  setMessages((prev) => [
                    ...prev,
                    {
                      id: uuidv4(),
                      sender: '系统',
                      content: '模型尚未对齐到当前选择，已取消本次发送。请稍后重试。',
                      type: 'system' as const,
                      timestamp: Date.now(),
                    },
                  ])
                  setSendLoading(false)
                  return
                }
              }
              let replyText = ''
              let needExecutionFired = false
              if (useChatWs && chatWs.sendChat) {
                try {
                  const res = await chatWs.sendChat(topoclawAnswerQueryText, imagesToSend, focusSkillsSnapshot, callbacks, chatAgentId)
                  replyText = res.fullText
                  needExecutionFired = res.needExecutionFired
                } catch (_) {
                  const res = await streamFallback()
                  replyText = res.fullText
                  needExecutionFired = res.needExecutionFired
                }
              } else {
                const res = await streamFallback()
                replyText = res.fullText
                needExecutionFired = res.needExecutionFired
              }
              if (isStreamStale()) return
              const normalizedReply = stripNeedExecutionGuide(replyText) || replyText
              const assistantSyncContent = normalizedReply || assistantMediaFallbackContent || (assistantMediaItems.length > 0 ? '[图片]' : '')
              const assistantSyncFileList = assistantMediaItems.map((item) => ({
                file_base64: item.fileBase64,
                file_name: item.fileName,
                message_type: item.messageType,
              }))
              const assistantSyncPrimary = assistantSyncFileList[0]
              if (imei && conversation.id && (assistantSyncContent || assistantSyncPrimary) && !isTopoCloudBlockedForSend) {
                const sessionIdOpt = isMultiSessionCustom && currentSessionId ? currentSessionId : undefined
                const convId =
                  isMultiSessionCustom && currentSessionId
                    ? `${conversation.id}__${currentSessionId}`
                    : conversation.id
                const wsSyncOk = wsSend({
                  type: 'assistant_sync_message',
                  content: assistantSyncContent,
                  message_id: uuidv4(),
                  sender: assistantName,
                  conversation_id: convId,
                  timestamp: new Date().toISOString(),
                  ...(assistantSyncPrimary ? {
                    file_base64: assistantSyncPrimary.file_base64,
                    file_name: assistantSyncPrimary.file_name,
                    message_type: assistantSyncPrimary.message_type,
                  } : {}),
                  ...(assistantSyncFileList.length > 0 ? { file_list: assistantSyncFileList } : {}),
                })
                if (!wsSyncOk || !userMsgWsSyncOk || imageBase64ToSync) {
                  appendCustomAssistantChat(imei, conversation.id, displayText, replyText, assistantName, {
                    ...(imageBase64ToSync ? { file_base64: imageBase64ToSync, file_name: '图片.png' } : {}),
                    session_id: sessionIdOpt,
                  }).catch(() => {})
                }
              }
              const previewText = (assistantMediaBase64 ? (normalizedReply || '[图片]') : normalizedReply).trim() || '[图片]'
              const isViewingNow =
                currentConvIdRef.current === chatAssistantConvId &&
                (!targetSessionId || currentSessionIdRef.current === targetSessionId)
              onUpdateLastMessage?.(
                chatAssistantConvId,
                toSkillSharePreview(previewText) || toAssistantSharePreview(previewText) || previewText.slice(0, 50),
                Date.now(),
                { isFromMe: false, isViewing: isViewingNow }
              )
              patchStoredMessageById(
                chatAssistantConvId,
                assistantId,
                (m) => ({ ...m, content: normalizedReply || stripNeedExecutionGuide(m.content) }),
                targetSessionId
              )
              setMessages((prev) => prev.map((m) => m.id === assistantId ? { ...m, content: normalizedReply || stripNeedExecutionGuide(m.content) } : m))
              if (!needExecutionFired && imei && conversation.id && customAssistant && (hasExecutionMobile(customAssistant) || hasExecutionPc(customAssistant)) && !isTopoCloudBlockedForSend) {
                const needExecByText = replyText.includes('[NEED_EXECUTION]') ||
                  (replyText.includes('执行') && (replyText.includes('截图') || replyText.includes('屏幕'))) ||
                  (replyText.includes('请发送') && replyText.includes('截图'))
                if (needExecByText) {
                  const execUuid = `assistant_${conversation.id}_${Date.now()}`
                  const chatSummary = text.slice(0, 80) || replyText.replace(/\[NEED_EXECUTION\].*/s, '').trim().slice(0, 80)
                  if (hasExecutionPc(customAssistant)) {
                    const pcName = customAssistant?.name ?? '小助手'
                    const pcMsgId = uuidv4()
                    setMessages((prev) => [
                      ...prev,
                      { id: pcMsgId, sender: pcName, content: '正在执行…', type: 'assistant' as const, timestamp: Date.now() },
                    ])
                    runComputerUseLoopWithAbort(
                      baseUrl,
                      execUuid,
                      text,
                      chatSummary,
                      { conversationId: conversation.id, sessionId: isMultiSessionCustom ? currentSessionId : undefined }
                    )
                      .then((r) => {
                        setMessages((prev) =>
                          prev.map((m) =>
                            m.id === pcMsgId
                              ? { ...m, content: r.success ? (r.content || '任务已完成') : `执行失败：${r.error || '未知错误'}` }
                              : m
                          )
                        )
                      })
                      .catch((e) => {
                        setMessages((prev) =>
                          prev.map((m) =>
                            m.id === pcMsgId ? { ...m, content: `执行异常：${String(e)}` } : m
                          )
                        )
                      })
                  }
                  if (hasExecutionMobile(customAssistant)) {
                    sendExecuteForAssistant(imei, text, execUuid, baseUrl, conversation.id, chatSummary).then((res) => {
                      if (res.success) {
                        setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '已向手机推送执行指令，请在手机端确认', type: 'system' as const, timestamp: Date.now() }])
                      }
                    }).catch(() => {})
                  }
                }
              }
            } catch (err) {
              const errMsg = formatApiError(err)
              patchStoredMessageById(
                chatAssistantConvId,
                assistantId,
                (m) => ({ ...m, content: errMsg }),
                targetSessionId
              )
              setMessages((prev) => {
                if (currentConvIdRef.current !== chatAssistantConvId) return prev
                return prev.map((m) => (m.id === assistantId ? { ...m, content: errMsg } : m))
              })
            } finally {
              setSendLoading(false)
            }
          } else {
          const assistantId = uuidv4()
          const assistantMsg: Message = {
            id: assistantId,
            sender: '小助手',
            content: '',
            type: 'assistant',
            timestamp: Date.now(),
          }
          setMessages((prev) => [...prev, assistantMsg])
          appendMessageToStorage(
            conversation.id,
            assistantMsg,
            isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
          )
          const targetSessionId = isMultiSessionCustom ? (currentSessionId ?? undefined) : undefined
          let assistantHasMedia = false
          try {
            const { fullText: replyText, needExecutionFired } = await sendChatAssistantMessageStream(
              {
                uuid: sessionUuidRef.current,
                query: topoclawAnswerQueryText,
                images: imagesToSend,
                imei,
                focusSkills: focusSkillsSnapshot,
              },
              baseUrl,
              (delta) => {
                if (isStreamStale()) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, content: (m.content || '') + delta }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId ? { ...m, content: m.content + delta } : m
                  )
                })
              },
              (reasoning) => {
                if (isStreamStale()) return
                const thinkingLine = String(reasoning || '').trim()
                if (!thinkingLine) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId
                      ? { ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }
                      : m
                  )
                })
              },
              (media) => {
                if (isStreamStale()) return
                assistantHasMedia = true
                const incomingFileName = media.fileName || (media.messageType === 'file' ? '文件' : '图片.png')
                const newFile = { fileBase64: media.fileBase64, fileName: incomingFileName }
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => {
                    const textSeed = (m.content && m.content.trim()) ? m.content : (media.content || '')
                    const existing: Array<{ fileBase64: string; fileName?: string }> =
                      m.fileList && m.fileList.length > 0
                        ? m.fileList
                        : m.fileBase64
                          ? [{ fileBase64: m.fileBase64, fileName: m.fileName }]
                          : []
                    const duplicateIdx = existing.findIndex((f) =>
                      f.fileName && newFile.fileName && f.fileName === newFile.fileName
                    )
                    const nextList = duplicateIdx >= 0 ? existing : [...existing, newFile]
                    const mediaIndex = duplicateIdx >= 0 ? duplicateIdx : (nextList.length - 1)
                    return {
                      ...m,
                      content: appendAssistantMediaMarkerToContent(textSeed, mediaIndex),
                      messageType: 'file',
                      fileBase64: nextList[0].fileBase64,
                      fileName: nextList[0].fileName,
                      fileList: nextList.length > 1 ? nextList : undefined,
                    }
                  },
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) => {
                    if (m.id !== assistantId) return m
                    const textSeed = (m.content && m.content.trim()) ? m.content : (media.content || '')
                    const existing: Array<{ fileBase64: string; fileName?: string }> =
                      m.fileList && m.fileList.length > 0
                        ? m.fileList
                        : m.fileBase64
                          ? [{ fileBase64: m.fileBase64, fileName: m.fileName }]
                          : []
                    const duplicateIdx = existing.findIndex((f) =>
                      f.fileName && newFile.fileName && f.fileName === newFile.fileName
                    )
                    const nextList = duplicateIdx >= 0 ? existing : [...existing, newFile]
                    const mediaIndex = duplicateIdx >= 0 ? duplicateIdx : (nextList.length - 1)
                    return {
                      ...m,
                      content: appendAssistantMediaMarkerToContent(textSeed, mediaIndex),
                      messageType: 'file',
                      fileBase64: nextList[0].fileBase64,
                      fileName: nextList[0].fileName,
                      fileList: nextList.length > 1 ? nextList : undefined,
                    }
                  })
                })
              },
              (toolName) => {
                if (isStreamStale()) return
                const thinkingLine = `调用${toolName}工具`
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId
                      ? { ...m, thinkingContents: [...(m.thinkingContents ?? []), thinkingLine] }
                      : m
                  )
                })
                emitAssistantThinkingSync(thinkingLine, assistantId, '小助手')
              },
              (skill) => {
                if (isStreamStale()) return
                patchStoredMessageById(
                  chatAssistantConvId,
                  assistantId,
                  (m) => ({ ...m, generatedSkill: skill }),
                  targetSessionId
                )
                setMessages((prev) => {
                  if (currentConvIdRef.current !== chatAssistantConvId) return prev
                  return prev.map((m) =>
                    m.id === assistantId ? { ...m, generatedSkill: skill } : m
                  )
                })
              },
              imei && conversation?.id && !isTopoCloudBlockedForSend &&
                (conversation.id === CONVERSATION_ID_CHAT_ASSISTANT || (customAssistant && (hasExecutionMobile(customAssistant) || hasExecutionPc(customAssistant))))
                ? (chatSummary) => {
                    if (isStreamStale()) return
                    const execUuid = `assistant_${conversation.id}_${Date.now()}`
                    if (customAssistant && hasExecutionPc(customAssistant)) {
                      const pcName2 = customAssistant?.name ?? '小助手'
                      const pcMsgId2 = uuidv4()
                      setMessages((prev) => [
                        ...prev,
                        { id: pcMsgId2, sender: pcName2, content: '正在执行…', type: 'assistant' as const, timestamp: Date.now() },
                      ])
                      runComputerUseLoopWithAbort(
                        baseUrl,
                        execUuid,
                        topoclawQueryText,
                        chatSummary,
                        { conversationId: conversation.id, sessionId: isMultiSessionCustom ? currentSessionId : undefined }
                      )
                        .then((r) => {
                          setMessages((prev) =>
                            prev.map((m) =>
                              m.id === pcMsgId2
                                ? { ...m, content: r.success ? (r.content || '任务已完成') : `执行失败：${r.error || '未知错误'}` }
                                : m
                            )
                          )
                        })
                        .catch((e) => {
                          setMessages((prev) =>
                            prev.map((m) =>
                              m.id === pcMsgId2 ? { ...m, content: `执行异常：${String(e)}` } : m
                            )
                          )
                        })
                    }
                    if (!customAssistant || !hasExecutionPc(customAssistant) || hasExecutionMobile(customAssistant) || conversation.id === CONVERSATION_ID_CHAT_ASSISTANT) {
                      sendExecuteForAssistant(imei, topoclawQueryText, execUuid, baseUrl, conversation.id, chatSummary).then(
                        (res) => {
                          if (res.success) {
                            setMessages((prev) => [
                              ...prev,
                              { id: uuidv4(), sender: '系统', content: '已向手机推送执行指令，请在手机端确认', type: 'system' as const, timestamp: Date.now() },
                            ])
                          }
                        }
                      ).catch(() => {})
                    }
                  }
                : undefined
            )
            if (isStreamStale()) return
            const normalizedReply = stripNeedExecutionGuide(replyText) || replyText
            const previewText = (assistantHasMedia ? (normalizedReply || '[图片]') : normalizedReply).trim() || '[图片]'
            const isViewingNow =
              currentConvIdRef.current === chatAssistantConvId &&
              (!targetSessionId || currentSessionIdRef.current === targetSessionId)
            onUpdateLastMessage?.(
              chatAssistantConvId,
              toSkillSharePreview(previewText) || toAssistantSharePreview(previewText) || previewText.slice(0, 50),
              Date.now(),
              { isFromMe: false, isViewing: isViewingNow }
            )
            patchStoredMessageById(
              chatAssistantConvId,
              assistantId,
              (m) => ({ ...m, content: normalizedReply || stripNeedExecutionGuide(m.content) }),
              targetSessionId
            )
            setMessages((prev) => prev.map((m) => m.id === assistantId ? { ...m, content: normalizedReply || stripNeedExecutionGuide(m.content) } : m))
            if (!needExecutionFired && imei && conversation?.id && baseUrl && !isTopoCloudBlockedForSend &&
                (conversation.id === CONVERSATION_ID_CHAT_ASSISTANT || (customAssistant && (hasExecutionMobile(customAssistant) || hasExecutionPc(customAssistant))))) {
              const needExecByText = replyText.includes('[NEED_EXECUTION]') ||
                (replyText.includes('执行') && (replyText.includes('截图') || replyText.includes('屏幕'))) ||
                (replyText.includes('请发送') && replyText.includes('截图'))
              if (needExecByText) {
                const execUuid = `assistant_${conversation.id}_${Date.now()}`
                const chatSummary = text.slice(0, 80) || replyText.replace(/\[NEED_EXECUTION\].*/s, '').trim().slice(0, 80)
                if (customAssistant && hasExecutionPc(customAssistant)) {
                  const pcName3 = customAssistant?.name ?? '小助手'
                  const pcMsgId3 = uuidv4()
                  setMessages((prev) => [
                    ...prev,
                    { id: pcMsgId3, sender: pcName3, content: '正在执行…', type: 'assistant' as const, timestamp: Date.now() },
                  ])
                  runComputerUseLoopWithAbort(
                    baseUrl,
                    execUuid,
                    topoclawQueryText,
                    chatSummary,
                    { conversationId: conversation.id, sessionId: isMultiSessionCustom ? currentSessionId : undefined }
                  )
                    .then((r) => {
                      setMessages((prev) =>
                        prev.map((m) =>
                          m.id === pcMsgId3
                            ? { ...m, content: r.success ? (r.content || '任务已完成') : `执行失败：${r.error || '未知错误'}` }
                            : m
                        )
                      )
                    })
                    .catch((e) => {
                      setMessages((prev) =>
                        prev.map((m) =>
                          m.id === pcMsgId3 ? { ...m, content: `执行异常：${String(e)}` } : m
                        )
                      )
                    })
                }
                if (!customAssistant || !hasExecutionPc(customAssistant) || hasExecutionMobile(customAssistant) || conversation.id === CONVERSATION_ID_CHAT_ASSISTANT) {
                  sendExecuteForAssistant(imei, topoclawQueryText, execUuid, baseUrl, conversation.id, chatSummary).then((res) => {
                    if (res.success) {
                      setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '已向手机推送执行指令，请在手机端确认', type: 'system' as const, timestamp: Date.now() }])
                    }
                  }).catch(() => {})
                }
              }
            }
          } catch (err) {
            const errMsg = formatApiError(err)
            patchStoredMessageById(
              chatAssistantConvId,
              assistantId,
              (m) => ({ ...m, content: errMsg }),
              targetSessionId
            )
            setMessages((prev) => {
              if (currentConvIdRef.current !== chatAssistantConvId) return prev
              return prev.map((m) => (m.id === assistantId ? { ...m, content: errMsg } : m))
            })
          } finally {
            setSendLoading(false)
          }
          }
        } else {
          const res = await sendChatMessage(
            {
              uuid: sessionUuidRef.current,
              query: injectVisualExplainHint(queryTextWithQuote),
              images: [''],
              imei,
              focusSkills: focusSkillsSnapshot,
            },
            baseUrl
          )
          const replyText =
            res.params ?? res.text ?? res.message ?? res.reason ?? res.thought
              ?? (res.action_type === 'complete' ? '任务已完成' : '已收到您的消息')
          const assistantMsg: Message = {
            id: uuidv4(),
            sender: '小助手',
            content: replyText,
            type: 'assistant',
            timestamp: Date.now(),
          }
          setMessages((prev) => [...prev, assistantMsg])
          setSendLoading(false)
        }
      } else {
        setMessages((prev) => [...prev, { id: uuidv4(), sender: '系统', content: '该会话类型暂不支持发送消息', type: 'system', timestamp: Date.now() }])
      }
    } catch (err) {
      const errMsg = formatApiError(err)
      const phoneOffline = errMsg.includes('手机端不在线')
      const assistantOfflineBubble =
        phoneOffline && conversation?.type === 'assistant'
          ? {
              id: uuidv4(),
              sender: (conversation.name || customAssistant?.name || '小助手').trim(),
              content: errMsg,
              type: 'assistant' as const,
              timestamp: Date.now(),
            }
          : null
      setMessages((prev) => [
        ...prev,
        assistantOfflineBubble ?? { id: uuidv4(), sender: '系统', content: errMsg, type: 'system', timestamp: Date.now() },
      ])
      setSendLoading(false)
    } finally {
      if (!isAssistant) setSendLoading(false)
    }
  }

  useEffect(() => {
    screenshotAskSendRef.current = (
      text: string,
      image?: { base64: string; name?: string; mime?: string }
    ) => {
      void handleSend(text, image)
    }
  }, [handleSend])

  /**
   * 截图浮层「提问 TopoClaw」：切到 custom_topoclaw 后须等多 session 的 currentSessionId 就绪，
   * 否则 handleSend 会在 isMultiSessionCustom && !currentSessionId 处直接 return，消息显示但请求未发出。
   */
  useEffect(() => {
    const p = pendingDesktopScreenshotSendRef.current
    if (!p || !conversation) return
    if (!isTopoClawConversation) return
    if (isMultiSessionCustom && !currentSessionId) return
    pendingDesktopScreenshotSendRef.current = null
    void handleSend(p.text, p.image)
  }, [conversation?.id, isTopoClawConversation, isMultiSessionCustom, currentSessionId, handleSend])

  const applySelectedImageFile = useCallback((file: File, base64: string) => {
    setSelectedImages((prev) => {
      const normalized: DraftImage = {
        base64,
        name: file.name || '图片.png',
        mime: file.type || 'image/png',
      }
      const deduped = prev.filter((x) => x.base64 !== normalized.base64)
      return [...deduped, normalized].slice(0, MAX_CHAT_ATTACH_IMAGES)
    })
    setSelectedFileBase64(null)
    setSelectedFileName('file.bin')
    setSelectedFileMime('application/octet-stream')
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
  }, [])

  const applySelectedGenericFile = useCallback((file: File, base64: string) => {
    setSelectedFileBase64(base64)
    setSelectedFileName(file.name || 'file.bin')
    setSelectedFileMime(file.type || 'application/octet-stream')
    setSelectedImages([])
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
  }, [])

  const handleDismissBulkImportBanner = useCallback(() => {
    bulkImportCancelRef.current = true
    setBulkImportProgress(null)
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
  }, [])

  const importDroppedFilesToWorkspace = useCallback(async (files: File[], droppedFolderName?: string) => {
    if (!isTopoClawConversation) {
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: '当前会话仅支持单文件拖拽，请切换到 TopoClaw 会话后再批量导入。', type: 'system', timestamp: Date.now() },
      ])
      return
    }
    if (!window.electronAPI?.saveChatFileToWorkspace) {
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: '当前环境不支持批量导入，请在桌面端使用。', type: 'system', timestamp: Date.now() },
      ])
      return
    }
    const total = files.length
    const batchTag = `drop_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
    bulkImportCancelRef.current = false
    setBulkImportProgress({
      active: true,
      total,
      processed: 0,
      success: 0,
      failed: 0,
      currentName: files[0]?.name || '',
    })
    setBulkWorkspaceBatch(null)
    setBulkFolderForCodeMode(null)
    let processed = 0
    let success = 0
    let failed = 0
    let firstPath = ''
    try {
      for (const f of files) {
        if (bulkImportCancelRef.current) break
        setBulkImportProgress({
          active: true,
          total,
          processed,
          success,
          failed,
          currentName: f.name,
        })
        try {
          const dataUrl = await readFileAsDataUrl(f)
          if (bulkImportCancelRef.current) break
          const saved = await window.electronAPI.saveChatFileToWorkspace(dataUrl, f.name, batchTag)
          if (bulkImportCancelRef.current) break
          if (saved.ok && saved.path) {
            success += 1
            if (!firstPath) firstPath = saved.path
          } else {
            failed += 1
          }
        } catch {
          failed += 1
        }
        if (bulkImportCancelRef.current) break
        processed += 1
        setBulkImportProgress({
          active: true,
          total,
          processed,
          success,
          failed,
          currentName: f.name,
        })
      }
      if (bulkImportCancelRef.current) {
        setMessages((prev) => [
          ...prev,
          {
            id: uuidv4(),
            sender: '系统',
            content: `已取消批量导入（已处理 ${processed}/${total}）。`,
            type: 'system',
            timestamp: Date.now(),
          },
        ])
        setBulkImportProgress(null)
        setBulkWorkspaceBatch(null)
        setBulkFolderForCodeMode(null)
        return
      }
      const batchDirPath = firstPath ? firstPath.replace(/[\\/][^\\/]+$/, '') : ''
      if (batchDirPath) {
        setBulkWorkspaceBatch({
          dirPath: batchDirPath,
          total,
          success,
          failed,
        })
        if (droppedFolderName) {
          setBulkFolderForCodeMode({
            dirPath: batchDirPath,
            folderName: droppedFolderName,
          })
        }
      }
      setMessages((prev) => [
        ...prev,
        {
          id: uuidv4(),
          sender: '系统',
          content: `批量导入完成：总计 ${total}，成功 ${success}，失败 ${failed}${batchDirPath ? `。目录：${batchDirPath}` : ''}`,
          type: 'system',
          timestamp: Date.now(),
        },
      ])
      setBulkImportProgress({
        active: false,
        total,
        processed,
        success,
        failed,
      })
    } catch (e) {
      setBulkImportProgress({
        active: false,
        total,
        processed,
        success,
        failed,
        error: String(e),
      })
      setMessages((prev) => [
        ...prev,
        { id: uuidv4(), sender: '系统', content: `批量导入失败：${String(e)}`, type: 'system', timestamp: Date.now() },
      ])
    }
  }, [isTopoClawConversation, setMessages])

  const handleAttachmentDrop = useCallback((payload: { files: FileList | File[]; droppedFolderName?: string }) => {
    const list = Array.from(payload.files || [])
    const droppedFolderName = String(payload.droppedFolderName || '').trim()
    if (list.length === 0) return
    const canImage = isCustomChatAssistant || isChatAssistant || isFriend || isCrossDevice
    const canFile = isCrossDevice || isTopoClawConversation
    const preferred = (canImage && list.find((f) => f.type.startsWith('image/'))) || list[0]
    if (!preferred) return
    void (async () => {
      try {
        if ((list.length > 1 || !!droppedFolderName) && canFile) {
          await importDroppedFilesToWorkspace(list, droppedFolderName || undefined)
          return
        }
        const dataUrl = await readFileAsDataUrl(preferred)
        const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl
        if (!base64 || base64.length < 10) return
        if (preferred.type.startsWith('image/') && canImage) {
          applySelectedImageFile(preferred, base64)
          return
        }
        if (canFile) {
          applySelectedGenericFile(preferred, base64)
          return
        }
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: '当前会话不支持该附件类型，请切换到支持附件的助手。', type: 'system', timestamp: Date.now() },
        ])
      } catch {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: '读取附件失败，请重试。', type: 'system', timestamp: Date.now() },
        ])
      }
    })()
  }, [
    isCustomChatAssistant,
    isChatAssistant,
    isFriend,
    isCrossDevice,
    isTopoClawConversation,
    applySelectedImageFile,
    applySelectedGenericFile,
    importDroppedFilesToWorkspace,
  ])

  /** 自定义/聊天小助手/好友单聊：选择图片 */
  const handleImageSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files || [])
      .filter((f) => f.type.startsWith('image/'))
      .slice(0, MAX_CHAT_ATTACH_IMAGES)
    if (files.length === 0) return
    files.forEach((file) => {
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result)
        const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl
        if (base64 && base64.length > 100) applySelectedImageFile(file, base64)
      }
      reader.readAsDataURL(file)
    })
    e.target.value = ''
  }

  const imagePasteAllowed = isCustomChatAssistant || isChatAssistant || isFriend || isCrossDevice

  const handleChatInputPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    if (!imagePasteAllowed) return
    const cd = e.clipboardData
    if (!cd) return

    const readFileAsBase64 = (file: File) => {
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result)
        const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl
        if (base64 && base64.length > 100) applySelectedImageFile(file, base64)
      }
      reader.readAsDataURL(file)
    }

    if (cd.files?.length) {
      for (let i = 0; i < cd.files.length; i++) {
        const f = cd.files[i]
        if (f?.type.startsWith('image/')) {
          e.preventDefault()
          readFileAsBase64(f)
          return
        }
      }
    }

    for (let i = 0; i < (cd.items?.length ?? 0); i++) {
      const item = cd.items[i]
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const file = item.getAsFile()
        if (file) {
          e.preventDefault()
          readFileAsBase64(file)
          return
        }
      }
    }

    const plain = cd.getData('text/plain')
    if (typeof plain === 'string' && plain.trim().length > 0) return

    const syncB64 = window.electronAPI?.readClipboardImageBase64Sync?.()
    if (typeof syncB64 === 'string' && syncB64.length > 50) {
      e.preventDefault()
      applySelectedImageFile(new File([], 'clipboard.png', { type: 'image/png' }), syncB64)
    }
  }

  const handleChatInputAreaContextMenu = useCallback((e: React.MouseEvent<HTMLTextAreaElement>) => {
    e.preventDefault()
    e.stopPropagation()
    setChatInputContextMenu({ x: e.clientX, y: e.clientY })
  }, [])

  const insertTextAtChatInputCaret = (ta: HTMLTextAreaElement, text: string) => {
    const start = ta.selectionStart ?? 0
    const end = ta.selectionEnd ?? 0
    const cur = ta.value
    const next = cur.slice(0, start) + text + cur.slice(end)
    if (isGroup) handleGroupInputChange(next)
    else setInput(next)
    const pos = start + text.length
    requestAnimationFrame(() => {
      ta.focus()
      ta.setSelectionRange(pos, pos)
    })
  }

  const pasteChatInputFromClipboardFallback = async () => {
    const ta = inputRef.current
    if (!ta) return

    const tryImageFile = (file: File) => {
      if (!imagePasteAllowed || !file.type.startsWith('image/')) return false
      const reader = new FileReader()
      reader.onload = () => {
        const dataUrl = String(reader.result)
        const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl
        if (base64 && base64.length > 100) applySelectedImageFile(file, base64)
      }
      reader.readAsDataURL(file)
      return true
    }

    try {
      if (navigator.clipboard?.read && imagePasteAllowed) {
        const clipItems = await navigator.clipboard.read()
        for (const ci of clipItems) {
          for (const type of ci.types) {
            if (type.startsWith('image/')) {
              const blob = await ci.getType(type)
              if (tryImageFile(new File([blob], 'paste.png', { type: blob.type || type }))) return
            }
          }
        }
        for (const ci of clipItems) {
          if (ci.types.includes('text/plain')) {
            const blob = await ci.getType('text/plain')
            insertTextAtChatInputCaret(ta, await blob.text())
            return
          }
        }
      }
    } catch {
      /* 继续尝试 readText / Electron 位图 */
    }

    let textPlain = ''
    try {
      textPlain = await navigator.clipboard.readText()
    } catch {
      /* ignore */
    }
    if (textPlain) {
      insertTextAtChatInputCaret(ta, textPlain)
      return
    }

    if (imagePasteAllowed) {
      const syncB64 = window.electronAPI?.readClipboardImageBase64Sync?.()
      if (typeof syncB64 === 'string' && syncB64.length > 50) {
        applySelectedImageFile(new File([], 'clipboard.png', { type: 'image/png' }), syncB64)
      }
    }
  }

  const handleChatInputMenuCut = async () => {
    setChatInputContextMenu(null)
    const ta = inputRef.current
    if (!ta || ta.disabled) return
    const start = ta.selectionStart ?? 0
    const end = ta.selectionEnd ?? 0
    if (start === end) return
    const slice = ta.value.slice(start, end)
    try {
      await navigator.clipboard.writeText(slice)
    } catch {
      return
    }
    const next = ta.value.slice(0, start) + ta.value.slice(end)
    if (isGroup) handleGroupInputChange(next)
    else setInput(next)
    requestAnimationFrame(() => {
      ta.focus()
      ta.setSelectionRange(start, start)
    })
  }

  const handleChatInputMenuCopy = async () => {
    setChatInputContextMenu(null)
    const ta = inputRef.current
    if (!ta || ta.disabled) return
    const start = ta.selectionStart ?? 0
    const end = ta.selectionEnd ?? 0
    if (start === end) return
    try {
      await navigator.clipboard.writeText(ta.value.slice(start, end))
    } catch {
      try {
        ta.focus()
        ta.setSelectionRange(start, end)
        document.execCommand('copy')
      } catch {
        /* ignore */
      }
    }
  }

  const handleChatInputMenuPaste = () => {
    setChatInputContextMenu(null)
    const ta = inputRef.current
    if (!ta || ta.disabled) return
    ta.focus()
    try {
      if (document.execCommand('paste')) return
    } catch {
      /* fall through */
    }
    void pasteChatInputFromClipboardFallback()
  }

  const handleChatInputMenuSelectAll = () => {
    setChatInputContextMenu(null)
    const ta = inputRef.current
    if (!ta || ta.disabled) return
    ta.focus()
    ta.select()
  }

  const handleCrossDeviceFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !imei || !conversation || loading || !isCrossDevice) return
    const reader = new FileReader()
    reader.onload = async () => {
      const base64 = String(reader.result).split(',')[1]
      if (!base64) return
      const userMsg: Message = {
        id: uuidv4(),
        sender: '我',
        content: `[文件] ${file.name}`,
        type: 'user',
        timestamp: Date.now(),
        messageType: 'file',
        fileBase64: base64,
        fileName: file.name,
      }
      setMessages((prev) => [...prev, userMsg])
      setLoading(true)
      try {
        await sendCrossDeviceMessage(imei, '[文件]', {
          message_type: 'file',
          file_base64: base64,
          file_name: file.name,
        })
      } catch (err) {
        const errMsg = formatApiError(err)
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: errMsg, type: 'system', timestamp: Date.now() },
        ])
      } finally {
        setLoading(false)
      }
    }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  const handleTopoClawFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !isTopoClawConversation) return
    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = String(reader.result)
      const base64 = dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl
      if (!base64 || base64.length < 10) return
      applySelectedGenericFile(file, base64)
    }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  const handleInputDragEnter = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    if (!e.dataTransfer?.types?.includes('Files')) return
    e.preventDefault()
    e.stopPropagation()
    attachmentDragDepthRef.current += 1
    setIsAttachmentDragOver(true)
  }, [])

  const handleInputDragOver = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    if (!e.dataTransfer?.types?.includes('Files')) return
    e.preventDefault()
    e.stopPropagation()
    e.dataTransfer.dropEffect = 'copy'
    setIsAttachmentDragOver(true)
  }, [])

  const handleInputDragLeave = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    if (!isAttachmentDragOver) return
    e.preventDefault()
    e.stopPropagation()
    attachmentDragDepthRef.current = Math.max(0, attachmentDragDepthRef.current - 1)
    if (attachmentDragDepthRef.current === 0) setIsAttachmentDragOver(false)
  }, [isAttachmentDragOver])

  const handleInputDrop = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    e.stopPropagation()
    attachmentDragDepthRef.current = 0
    setIsAttachmentDragOver(false)
    const dt = e.dataTransfer
    if (!dt) return
    const topLevelFolders = getDroppedTopLevelDirectoryNames(dt)
    const droppedFolderName = topLevelFolders.length === 1 ? topLevelFolders[0] : ''
    void (async () => {
      const droppedFiles = await collectDroppedFiles(dt)
      if (!droppedFiles || droppedFiles.length === 0) {
        setMessages((prev) => [
          ...prev,
          { id: uuidv4(), sender: '系统', content: '未识别到可读取的文件或文件夹内容。', type: 'system', timestamp: Date.now() },
        ])
        return
      }
      handleAttachmentDrop({ files: droppedFiles, droppedFolderName: droppedFolderName || undefined })
    })()
  }, [handleAttachmentDrop])

  const handleSkillAction = useCallback((messageId: string, skill: Skill, action: 'add' | 'cancel') => {
    if (action === 'add') {
      addCollectedSkill(skill)
    }
    setMessages((prev) =>
      prev.map((m) =>
        m.id === messageId ? { ...m, generatedSkillResolved: action === 'add' ? 'added' : 'cancelled' } : m
      )
    )
  }, [])

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    // 聊天记录仅存本地，不拉取云端历史，无加载更多
    shouldAutoScrollRef.current = isNearBottom(e.currentTarget)
  }

  const handleEmojiInsert = useCallback((emoji: string) => {
    const inputEl = inputRef.current
    if (inputEl) {
      const start = inputEl.selectionStart ?? input.length
      const end = inputEl.selectionEnd ?? input.length
      const before = input.slice(0, start)
      const after = input.slice(end)
      const newVal = before + emoji + after
      setInput(newVal)
      requestAnimationFrame(() => {
        const pos = start + emoji.length
        inputEl.setSelectionRange(pos, pos)
        inputEl.focus()
      })
    } else {
      setInput((v) => v + emoji)
    }
  }, [input])

  const toggleFocusSkill = useCallback((skillName: string) => {
    setSelectedFocusSkills((prev) => {
      const exists = prev.includes(skillName)
      if (exists) return prev.filter((n) => n !== skillName)
      if (prev.length >= MAX_FOCUS_SKILLS) return prev
      return [...prev, skillName]
    })
  }, [])

  useEffect(() => {
    if (!showEmoji) return
    const onOutside = () => setShowEmoji(false)
    document.addEventListener('click', onOutside)
    return () => document.removeEventListener('click', onOutside)
  }, [showEmoji])

  useEffect(() => {
    if (!showSkillPicker) return
    const onOutside = () => setShowSkillPicker(false)
    document.addEventListener('click', onOutside)
    return () => document.removeEventListener('click', onOutside)
  }, [showSkillPicker])

  const syncFocusSkillsFromService = useCallback(async () => {
    setFocusSkillSyncing(true)
    try {
      const serverSkills = await fetchInstalledSkillsFromService()
      syncPublicHubSkillsWithServer(serverSkills)
      setFocusSkillRefreshKey((k) => k + 1)
    } catch (e) {
      console.error('同步技能列表失败:', e)
    } finally {
      setFocusSkillSyncing(false)
    }
  }, [])

  const mySkillOptions = useMemo(() => {
    const all = [...loadMySkills(), ...loadCollectedSkills()]
    const seen = new Set<string>()
    const seenCanonical = new Set<string>()
    const options: Array<{ canonical: string; label: string }> = []
    for (const skill of all) {
      const idKey = String(skill.id || '').trim()
      if (!idKey || seen.has(idKey)) continue
      seen.add(idKey)
      const canonical = toCanonicalSkillName(skill.title)
      if (!canonical || seenCanonical.has(canonical)) continue
      seenCanonical.add(canonical)
      options.push({ canonical, label: getSkillDisplayName(canonical) })
    }
    return options
  }, [conversation?.id, focusSkillRefreshKey])

  const filteredSkillOptions = useMemo(() => {
    const key = skillKeyword.trim().toLowerCase()
    if (!key) return mySkillOptions
    return mySkillOptions.filter(
      (item) =>
        item.label.toLowerCase().includes(key) ||
        item.canonical.toLowerCase().includes(key)
    )
  }, [mySkillOptions, skillKeyword])

  useEffect(() => {
    if (!canUseFocusSkills) {
      setSelectedFocusSkills([])
      setShowSkillPicker(false)
      setSkillKeyword('')
      return
    }
    const availableCanonical = new Set(mySkillOptions.map((item) => item.canonical))
    setSelectedFocusSkills((prev) =>
      prev.filter((name) => availableCanonical.has(name))
    )
  }, [canUseFocusSkills, mySkillOptions, conversation?.id])

  useEffect(() => {
    if (!showSkillPicker || !canUseFocusSkills) return
    void syncFocusSkillsFromService()
  }, [showSkillPicker, canUseFocusSkills, syncFocusSkillsFromService])

  // 输入框根据内容自动调整高度，支持自动换行
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.style.height = 'auto'
    const maxH = 150
    el.style.height = `${Math.min(el.scrollHeight, maxH)}px`
  }, [input])

  useEffect(() => {
    if (isTopoClawConversation) return
    setIdeModeEnabled(false)
    setIdeFileMenuOpen(false)
    setIdeRecentPanelOpen(false)
    setIdeOpenedEntries([])
    setIdeManualFolders([])
    setIdeOpenTabs([])
    setIdeExplorerTree([])
    setIdeExplorerSearchOpen(false)
    setIdeExplorerSearchKeyword('')
    setIdeExplorerContextMenu(null)
    setIdeExpandedFolders(new Set())
    setIdeExplorerWidth(230)
    setIdeRightPaneWidth(364)
    setIdeActiveFile(null)
    setIdeFileContents({})
    setIdeSavedPaths({})
    setIdeSavedContents({})
    setIdeUnsavedDrafts({})
    setIdeHumanEditedPaths({})
    setIdeAssistantAddedPaths({})
    setIdePreferredCwd(null)
    setIdeQaContextRootRelPath(null)
    setIdeQaContextRootAbsPath(null)
    setIdeQaLastRetrievalHitCount(0)
    setIdeDiffReviewPath(null)
    setIdeFindOpen(false)
    setIdeFindKeyword('')
    setIdeFindCurrentMatch(0)
    setIdeTerminalVisible(false)
    setIdeTerminalCollapsed(true)
    setIdeTerminalHeight(172)
    setIdeTerminalFindOpen(false)
    setIdeTerminalFindKeyword('')
    setIdeTerminalFindCurrent(0)
    setIdeTerminalFindTotal(0)
    setIdeTerminalRunning(false)
    setIdeTerminals([{ id: 'py-1', name: '终端1' }])
    setIdeActiveTerminalId('py-1')
    ideTerminalCounterRef.current = 1
    setIdeTerminalError('')
    setIdeTerminalMenuOpen(false)
    ideTerminalConnectedRef.current = false
    ideTerminalBusyRef.current = false
  }, [isTopoClawConversation, conversation?.id])

  useEffect(() => {
    if (!ideFileMenuOpen && !ideTerminalMenuOpen) return
    const onDocPointerDown = (event: MouseEvent) => {
      const target = event.target as Node | null
      if (!target) return
      if (ideFileMenuRef.current?.contains(target)) return
      if (ideTerminalMenuRef.current?.contains(target)) return
      if (ideFileMenuOpen) setIdeFileMenuOpen(false)
      if (ideTerminalMenuOpen) setIdeTerminalMenuOpen(false)
    }
    document.addEventListener('mousedown', onDocPointerDown)
    return () => document.removeEventListener('mousedown', onDocPointerDown)
  }, [ideFileMenuOpen, ideTerminalMenuOpen])

  useEffect(() => {
    if (!ideExplorerContextMenu) return
    const close = () => setIdeExplorerContextMenu(null)
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    document.addEventListener('click', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('click', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [ideExplorerContextMenu])

  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      const raw = window.localStorage.getItem(IDE_RECENT_FILES_STORAGE_KEY)
      if (!raw) return
      const parsed = JSON.parse(raw)
      if (!Array.isArray(parsed)) return
      const cleaned = parsed
        .map((v) => String(v || '').trim())
        .filter(Boolean)
        .slice(0, 30)
      setIdeRecentEntries(cleaned)
    } catch {
      // ignore broken local cache
    }
  }, [])

  useEffect(() => {
    if (typeof window === 'undefined') return
    try {
      window.localStorage.setItem(IDE_RECENT_FILES_STORAGE_KEY, JSON.stringify(ideRecentEntries.slice(0, 30)))
    } catch {
      // ignore write failures
    }
  }, [ideRecentEntries])

  const pushIdeRecentEntries = useCallback((entries: string[]) => {
    const normalized = entries.map((v) => String(v || '').trim()).filter(Boolean)
    if (normalized.length === 0) return
    setIdeRecentEntries((prev) => {
      const merged = [...normalized, ...prev]
      const uniq: string[] = []
      const seen = new Set<string>()
      for (const name of merged) {
        const key = name.toLowerCase()
        if (seen.has(key)) continue
        seen.add(key)
        uniq.push(name)
        if (uniq.length >= 30) break
      }
      return uniq
    })
  }, [])

  const readIdeFileText = useCallback(async (file: File): Promise<string> => {
    try {
      const text = await file.text()
      if (text.includes('\u0000')) return '[二进制文件，暂不支持文本预览]'
      return text
    } catch (err) {
      return `[读取文件失败] ${err instanceof Error ? err.message : String(err)}`
    }
  }, [])

  const isIdeVirtualFileContent = useCallback((text: string): boolean => {
    const value = String(text || '')
    return value.startsWith('[二进制文件') || value.startsWith('[文件过大') || value.startsWith('[读取文件失败]')
  }, [])

  const resolveFolderCwdFromFiles = useCallback((files: File[]): string | null => {
    if (files.length === 0) return null
    const first = files[0] as File & { path?: string }
    const abs = String(first.path || '').replace(/\\/g, '/')
    const rel = normalizeIdePath(first.webkitRelativePath || first.name)
    if (!abs || !rel) return null
    if (!abs.endsWith(rel)) return null
    const parent = abs.slice(0, abs.length - rel.length).replace(/[\\/]+$/, '')
    const root = rel.split('/')[0] || ''
    if (!parent || !root) return null
    return `${parent}/${root}`.replace(/\//g, '\\')
  }, [])

  const loadDefaultWorkspaceIntoIde = useCallback(async (): Promise<boolean> => {
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.listWorkspaceFiles) return false
    const res = await api.listWorkspaceFiles({ maxFiles: 1200, maxBytes: 256 * 1024 })
    if (!res?.ok || !Array.isArray(res.files) || res.files.length === 0) return false
    const names = res.files
      .map((f) => normalizeIdePath(f.relativePath))
      .filter(Boolean)
    if (names.length === 0) return false
    const tree = buildIdeTree(names, [])
    const contentMap: Record<string, string> = {}
    const savedPathMap: Record<string, string> = {}
    const workspaceDir = String(res.workspaceDir || '').trim().replace(/\//g, '\\')
    for (const file of res.files) {
      const p = normalizeIdePath(file.relativePath)
      if (!p) continue
      contentMap[p] = typeof file.content === 'string' ? file.content : ''
      if (workspaceDir) {
        const rel = p.replace(/^workspace\//i, '')
        savedPathMap[p] = `${workspaceDir.replace(/[\\\/]+$/g, '')}\\${rel.replace(/\//g, '\\')}`
      }
    }
    setIdeOpenedEntries(names)
    setIdeManualFolders([])
    setIdeOpenTabs(names[0] ? [names[0]] : [])
    setIdeExplorerTree(tree)
    setIdeExpandedFolders(new Set())
    setIdeActiveFile(names[0] || null)
    setIdeFileContents(contentMap)
    setIdeSavedPaths(savedPathMap)
    setIdeSavedContents(contentMap)
    setIdeUnsavedDrafts({})
    setIdeHumanEditedPaths({})
    setIdeAssistantAddedPaths({})
    setIdeQaContextRootRelPath('workspace')
    if (typeof res.workspaceDir === 'string' && res.workspaceDir.trim()) {
      setIdePreferredCwd(res.workspaceDir.trim())
      setIdeQaContextRootAbsPath(res.workspaceDir.trim())
    } else {
      setIdeQaContextRootAbsPath(null)
    }
    pushIdeRecentEntries(names.slice(0, 80))
    return true
  }, [pushIdeRecentEntries])

  const handleEnterCodeModeFromBulkFolder = useCallback(async () => {
    if (!bulkFolderForCodeMode?.dirPath) return
    const rawDir = String(bulkFolderForCodeMode.dirPath).trim()
    if (!rawDir) return
    const normalizedDir = rawDir.replace(/\\/g, '/')
    const marker = '/workspace/'
    const markerIdx = normalizedDir.toLowerCase().lastIndexOf(marker)
    if (markerIdx < 0) {
      window.alert('未能定位导入目录，请先重试拖拽导入。')
      return
    }
    const relTail = normalizedDir.slice(markerIdx + marker.length).replace(/^\/+/, '')
    const prefix = normalizeIdePath(`workspace/${relTail}`)
    if (!prefix) {
      window.alert('未能定位导入目录，请先重试拖拽导入。')
      return
    }
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.listWorkspaceFiles) return
    const res = await api.listWorkspaceFiles({ maxFiles: 1200, maxBytes: 256 * 1024 })
    if (!res?.ok || !Array.isArray(res.files) || res.files.length === 0) {
      window.alert('导入目录读取失败，请稍后重试。')
      return
    }
    const names: string[] = []
    const contentMap: Record<string, string> = {}
    const savedPathMap: Record<string, string> = {}
    const prefixLower = prefix.toLowerCase()
    const rawDirNorm = rawDir.replace(/\//g, '\\').replace(/[\\\/]+$/g, '')
    for (const file of res.files) {
      const p = normalizeIdePath(file.relativePath)
      if (!p) continue
      const lower = p.toLowerCase()
      if (!(lower === prefixLower || lower.startsWith(`${prefixLower}/`))) continue
      names.push(p)
      contentMap[p] = typeof file.content === 'string' ? file.content : ''
      const relInPicked = p === prefix ? '' : p.slice(prefix.length).replace(/^\/+/, '')
      if (relInPicked) savedPathMap[p] = `${rawDirNorm}\\${relInPicked.replace(/\//g, '\\')}`
    }
    if (names.length === 0) {
      window.alert('该目录暂无可打开的文本文件。')
      return
    }
    const tree = buildIdeTree(names, [])
    setIdeOpenedEntries(names)
    setIdeManualFolders([])
    setIdeOpenTabs(names[0] ? [names[0]] : [])
    setIdeExplorerTree(tree)
    setIdeExpandedFolders(new Set())
    setIdeActiveFile(names[0] || null)
    setIdeFileContents(contentMap)
    setIdeSavedPaths(savedPathMap)
    setIdeSavedContents(contentMap)
    setIdeUnsavedDrafts({})
    setIdeHumanEditedPaths({})
    setIdeAssistantAddedPaths({})
    setIdePreferredCwd(rawDir.replace(/\//g, '\\'))
    setIdeQaContextRootRelPath(prefix)
    setIdeQaContextRootAbsPath(rawDir.replace(/\//g, '\\'))
    setIdeModeEnabled(true)
    setIdeFileMenuOpen(false)
    setIdeRecentPanelOpen(false)
    setIdeTerminalVisible(false)
    setIdeTerminalCollapsed(true)
    if (!conversationListCollapsed) onToggleConversationList?.()
    pushIdeRecentEntries(names.slice(0, 80))
    ideDefaultWorkspaceLoadedRef.current = true
  }, [bulkFolderForCodeMode, conversationListCollapsed, onToggleConversationList, pushIdeRecentEntries])

  useEffect(() => {
    if (!ideModeEnabled || !isTopoClawConversation) return
    if (ideDefaultWorkspaceLoadedRef.current) return
    if (ideOpenedEntries.length > 0) {
      ideDefaultWorkspaceLoadedRef.current = true
      return
    }
    void loadDefaultWorkspaceIntoIde()
      .then((ok) => {
        if (ok) ideDefaultWorkspaceLoadedRef.current = true
      })
      .catch(() => {
        // ignore workspace preload failure
      })
  }, [ideModeEnabled, isTopoClawConversation, ideOpenedEntries.length, loadDefaultWorkspaceIntoIde])

  useEffect(() => {
    if (!ideModeEnabled || !isTopoClawConversation) {
      setToolGuardAutoAllowRoots([])
      return
    }
    const root = String(ideQaContextRootAbsPath || idePreferredCwd || '').trim()
    setToolGuardAutoAllowRoots(root ? [root] : [])
    return () => {
      setToolGuardAutoAllowRoots([])
    }
  }, [ideModeEnabled, isTopoClawConversation, ideQaContextRootAbsPath, idePreferredCwd])

  useEffect(() => {
    if (!ideModeEnabled || !isTopoClawConversation || !ideTerminalVisible) return
    const terminalApi = (typeof window !== 'undefined'
      ? (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI
      : undefined)
    if (!terminalApi?.create || !terminalApi?.write || !terminalApi?.resize || !terminalApi?.onData) {
      setIdeTerminalError('当前环境不支持内置终端，请在设置中使用“打开 Python 终端”。')
      return
    }
    setIdeTerminalError('')

    for (const tab of ideTerminals) {
      const host = ideTerminalHostsRef.current[tab.id]
      if (!host || ideTerminalMapRef.current[tab.id]) continue
      const term = new Terminal({
        cursorBlink: true,
        fontSize: 12,
        fontFamily: 'Consolas, "Courier New", monospace',
        theme: { background: '#10131a' },
      })
      const fitAddon = new FitAddon()
      const searchAddon = new SearchAddon()
      term.loadAddon(fitAddon)
      term.loadAddon(searchAddon)
      term.open(host)
      const rec = { term, fitAddon, searchAddon, unsub: null as (() => void) | null }
      ideTerminalMapRef.current[tab.id] = rec
      terminalApi.create({ cwd: idePreferredCwd || undefined }).then((res) => {
        if (!res?.ok) {
          setIdeTerminalError(res?.error || '创建终端失败')
          ideTerminalConnectedRef.current = false
          return
        }
        ideTerminalConnectedRef.current = true
        const unsub = terminalApi.onData?.((data: string) => {
          term.write(data)
          if (ideTerminalOutputLooksIdle(data)) {
            ideTerminalBusyRef.current = false
            setIdeTerminalRunning(false)
          }
        })
        rec.unsub = typeof unsub === 'function' ? unsub : null
        term.writeln(`\x1b[32m${tab.name} 已连接\x1b[0m`)
        term.writeln('输入 exit 可退出当前会话。\r\n')
      }).catch((err: unknown) => {
        setIdeTerminalError(err instanceof Error ? err.message : '创建终端失败')
        ideTerminalConnectedRef.current = false
      })
      term.onData((data) => {
        if (data.includes('\r')) {
          ideTerminalBusyRef.current = true
          setIdeTerminalRunning(true)
        }
        terminalApi.write?.(data)
      })
    }

    const activeIds = new Set(ideTerminals.map((t) => t.id))
    Object.keys(ideTerminalMapRef.current).forEach((id) => {
      if (activeIds.has(id)) return
      const rec = ideTerminalMapRef.current[id]
      rec.unsub?.()
      rec.term.dispose()
      delete ideTerminalMapRef.current[id]
      delete ideTerminalHostsRef.current[id]
    })
  }, [ideModeEnabled, isTopoClawConversation, ideTerminalVisible, ideTerminals, conversation?.id])

  useEffect(() => {
    const cleanupAll = () => {
      Object.values(ideTerminalMapRef.current).forEach((rec) => {
        rec.unsub?.()
        rec.term.dispose()
      })
      ideTerminalMapRef.current = {}
      ideTerminalHostsRef.current = {}
      ideTerminalConnectedRef.current = false
      ideTerminalBusyRef.current = false
      setIdeTerminalRunning(false)
    }
    if (!ideModeEnabled || !isTopoClawConversation) {
      cleanupAll()
    }
    return cleanupAll
  }, [ideModeEnabled, isTopoClawConversation, conversation?.id])

  useEffect(() => {
    if (!ideModeEnabled || ideTerminalCollapsed) return
    const timer = window.setTimeout(() => {
      const rec = ideTerminalMapRef.current[ideActiveTerminalId]
      const terminalApi = (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI
      if (!rec) return
      try {
        rec.fitAddon.fit()
        terminalApi?.resize?.(rec.term.cols, rec.term.rows)
      } catch {
        // ignore resize race
      }
    }, 0)
    return () => window.clearTimeout(timer)
  }, [ideModeEnabled, ideTerminalCollapsed, ideExplorerWidth, ideRightPaneWidth, ideTerminalHeight, ideActiveTerminalId])

  useEffect(() => {
    if (!ideModeEnabled || ideTerminalCollapsed) return
    const onResize = () => {
      const rec = ideTerminalMapRef.current[ideActiveTerminalId]
      const terminalApi = (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI
      if (!rec) return
      try {
        rec.fitAddon.fit()
        terminalApi?.resize?.(rec.term.cols, rec.term.rows)
      } catch {
        // ignore
      }
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [ideModeEnabled, ideTerminalCollapsed, ideActiveTerminalId])

  const handleRefreshActiveTerminal = useCallback(() => {
    const targetId = ideActiveTerminalId
    if (!targetId) return
    const rec = ideTerminalMapRef.current[targetId]
    if (rec) {
      rec.unsub?.()
      rec.term.dispose()
      delete ideTerminalMapRef.current[targetId]
      if (ideTerminalHostsRef.current[targetId]) {
        ideTerminalHostsRef.current[targetId]!.innerHTML = ''
      }
    }
    setIdeTerminalError('')
    setIdeTerminals((prev) => [...prev])
  }, [ideActiveTerminalId])

  const calcTerminalMatchTotal = useCallback((terminalId: string, keyword: string): number => {
    const rec = ideTerminalMapRef.current[terminalId]
    const needle = keyword.trim()
    if (!rec || !needle) return 0
    const sourceLines: string[] = []
    const buf = rec.term.buffer.active
    for (let i = 0; i < buf.length; i++) {
      const line = buf.getLine(i)?.translateToString(true) || ''
      sourceLines.push(line)
    }
    const source = sourceLines.join('\n')
    if (!source) return 0
    const regex = new RegExp(escapeRegExp(needle), 'gi')
    let total = 0
    while (regex.exec(source)) total += 1
    return total
  }, [])

  const runTerminalFind = useCallback((direction: 1 | -1 = 1) => {
    const rec = ideTerminalMapRef.current[ideActiveTerminalId]
    const keyword = ideTerminalFindKeyword.trim()
    if (!rec || !keyword) {
      setIdeTerminalFindCurrent(0)
      setIdeTerminalFindTotal(0)
      return false
    }
    const total = calcTerminalMatchTotal(ideActiveTerminalId, keyword)
    setIdeTerminalFindTotal(total)
    if (total === 0) {
      setIdeTerminalFindCurrent(0)
      return false
    }
    const options = { caseSensitive: false, incremental: true }
    const ok = direction === 1
      ? rec.searchAddon.findNext(keyword, options)
      : rec.searchAddon.findPrevious(keyword, options)
    if (!ok) return false
    setIdeTerminalFindCurrent((prev) => {
      if (direction === 1) return prev >= total ? 1 : Math.max(1, prev + 1)
      if (prev <= 1) return total
      return prev - 1
    })
    return true
  }, [ideActiveTerminalId, ideTerminalFindKeyword, calcTerminalMatchTotal])

  const openTerminalFindPanel = useCallback(() => {
    if (!ideTerminalVisible) setIdeTerminalVisible(true)
    if (ideTerminalCollapsed) setIdeTerminalCollapsed(false)
    setIdeTerminalFindOpen(true)
    setIdeTerminalFindCurrent(0)
    const total = calcTerminalMatchTotal(ideActiveTerminalId, ideTerminalFindKeyword)
    setIdeTerminalFindTotal(total)
    setTimeout(() => {
      ideTerminalFindInputRef.current?.focus()
      ideTerminalFindInputRef.current?.select()
    }, 0)
  }, [ideTerminalVisible, ideTerminalCollapsed, calcTerminalMatchTotal, ideActiveTerminalId, ideTerminalFindKeyword])

  const handleIdeOpenFile = useCallback(() => {
    const inputEl = document.createElement('input')
    inputEl.type = 'file'
    inputEl.multiple = true
    inputEl.onchange = () => {
      const picked = Array.from(inputEl.files || [])
      if (picked.length > 0) {
        void (async () => {
          const names = picked.map((f) => normalizeIdePath(f.name)).filter(Boolean)
          const firstAbs = String((picked[0] as File & { path?: string }).path || '').trim()
          const contentPairs = await Promise.all(
            picked.map(async (f) => [normalizeIdePath(f.name), await readIdeFileText(f)] as const)
          )
          const savedPathMap: Record<string, string> = {}
          picked.forEach((f) => {
            const p = normalizeIdePath(f.name)
            const abs = String((f as File & { path?: string }).path || '').trim()
            if (p && abs) savedPathMap[p] = abs.replace(/\//g, '\\')
          })
          setIdeOpenedEntries(names)
          setIdeManualFolders([])
          setIdeOpenTabs(names)
          setIdeExplorerTree([])
          setIdeExpandedFolders(new Set())
          setIdeActiveFile(names[0] || null)
          setIdeFileContents(Object.fromEntries(contentPairs))
          setIdeSavedPaths(savedPathMap)
          setIdeSavedContents(Object.fromEntries(contentPairs))
          setIdeUnsavedDrafts({})
          setIdeHumanEditedPaths({})
          setIdeAssistantAddedPaths({})
          setIdeQaContextRootRelPath(null)
          setIdeQaContextRootAbsPath(null)
          if (firstAbs) {
            const normalized = firstAbs.replace(/\//g, '\\')
            const idx = Math.max(normalized.lastIndexOf('\\'), normalized.lastIndexOf('/'))
            if (idx > 0) setIdePreferredCwd(normalized.slice(0, idx))
          }
          pushIdeRecentEntries(names)
        })()
      }
      inputEl.remove()
    }
    inputEl.click()
  }, [pushIdeRecentEntries, readIdeFileText])

  const handleIdeOpenFolder = useCallback(() => {
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)

    // Prefer Electron native directory picker; it is more reliable than webkitdirectory.
    const pickFolderFiles = api?.pickFolderFiles
    if (pickFolderFiles) {
      void (async () => {
        try {
          const picked = await pickFolderFiles({ maxFiles: 1500, maxBytes: 256 * 1024 })
          if (!picked?.ok) {
            if (!picked?.canceled) {
              window.alert(picked?.error || '打开文件夹失败，请稍后重试。')
            }
            return
          }
          const folderPath = String(picked.folderPath || '').trim()
          const folderName = basenameFromAnyPath(folderPath) || 'workspace'
          const files = Array.isArray(picked.files) ? picked.files : []
          const names = files
            .map((f) => normalizeIdePath(`${folderName}/${String(f.relativePath || '')}`))
            .filter(Boolean)
          if (names.length === 0) {
            window.alert('该目录暂无可打开的文本文件。')
            return
          }
          const contentMap: Record<string, string> = {}
          const savedPathMap: Record<string, string> = {}
          const base = folderPath.replace(/[\\\/]+$/g, '')
          for (const item of files) {
            const rel = normalizeIdePath(String(item.relativePath || ''))
            const normalized = normalizeIdePath(`${folderName}/${rel}`)
            if (!normalized) continue
            contentMap[normalized] = typeof item.content === 'string' ? item.content : ''
            if (base && rel) savedPathMap[normalized] = `${base}\\${rel.replace(/\//g, '\\')}`
          }
          const tree = buildIdeTree(names, [])
          setIdeOpenedEntries(names)
          setIdeManualFolders([])
          setIdeOpenTabs(names[0] ? [names[0]] : [])
          setIdeExplorerTree(tree)
          setIdeExpandedFolders(new Set())
          setIdeActiveFile(names[0] || null)
          setIdeFileContents(contentMap)
          setIdeSavedPaths(savedPathMap)
          setIdeSavedContents(contentMap)
          setIdeUnsavedDrafts({})
          setIdeHumanEditedPaths({})
          setIdeAssistantAddedPaths({})
          setIdeQaContextRootRelPath(folderName || null)
          if (folderPath) setIdePreferredCwd(folderPath)
          setIdeQaContextRootAbsPath(folderPath || null)
          pushIdeRecentEntries(names)
          return
        } catch {
          // Fall through to webkitdirectory fallback below.
        }
      })()
      return
    }

    const inputEl = document.createElement('input') as HTMLInputElement & { webkitdirectory?: boolean }
    inputEl.type = 'file'
    inputEl.multiple = true
    inputEl.webkitdirectory = true
    inputEl.setAttribute('webkitdirectory', '')
    inputEl.onchange = () => {
      const files = Array.from(inputEl.files || [])
      if (files.length === 0) {
        inputEl.remove()
        window.alert('未选择目录或当前环境不支持目录选择。')
        return
      }
      void (async () => {
        const names = files
          .map((f) => normalizeIdePath(f.webkitRelativePath || f.name))
          .filter(Boolean)
        const folderCwd = resolveFolderCwdFromFiles(files)
        const contentPairs = await Promise.all(
          files.map(async (f) => {
            const p = normalizeIdePath(f.webkitRelativePath || f.name)
            return [p, await readIdeFileText(f)] as const
          })
        )
        const savedPathMap: Record<string, string> = {}
        files.forEach((f) => {
          const p = normalizeIdePath(f.webkitRelativePath || f.name)
          const abs = String((f as File & { path?: string }).path || '').trim()
          if (p && abs) savedPathMap[p] = abs.replace(/\//g, '\\')
        })
        const tree = buildIdeTree(names, [])
        setIdeOpenedEntries(names)
        setIdeManualFolders([])
        setIdeOpenTabs(names[0] ? [names[0]] : [])
        setIdeExplorerTree(tree)
        setIdeExpandedFolders(new Set())
        setIdeActiveFile(names[0] || null)
        setIdeFileContents(Object.fromEntries(contentPairs))
        setIdeSavedPaths(savedPathMap)
        setIdeSavedContents(Object.fromEntries(contentPairs))
        setIdeUnsavedDrafts({})
        setIdeHumanEditedPaths({})
        setIdeAssistantAddedPaths({})
        const folderRel = commonTopLevelFolder(names)
        setIdeQaContextRootRelPath(folderRel || null)
        if (folderCwd) setIdePreferredCwd(folderCwd)
        setIdeQaContextRootAbsPath(folderCwd || null)
        pushIdeRecentEntries(names)
      })()
      inputEl.remove()
    }
    inputEl.click()
  }, [pushIdeRecentEntries, readIdeFileText, resolveFolderCwdFromFiles])

  const handleOpenRecentInIde = useCallback((entry: string) => {
    const name = String(entry || '').trim()
    if (!name) return
    setIdeOpenedEntries((prev) => {
      const uniq: string[] = [name]
      const seen = new Set<string>([name.toLowerCase()])
      for (const item of prev) {
        const normalized = String(item || '').trim()
        if (!normalized) continue
        const key = normalized.toLowerCase()
        if (seen.has(key)) continue
        seen.add(key)
        uniq.push(normalized)
      }
      return uniq
    })
    setIdeOpenTabs((prev) => (prev.some((p) => p.toLowerCase() === name.toLowerCase()) ? prev : [...prev, name]))
    setIdeActiveFile(name)
    setIdeFileContents((prev) => {
      if (prev[name]) return prev
      return { ...prev, [name]: '[该最近文件仅记录名称，请重新打开文件后查看内容]' }
    })
    setIdeSavedContents((prev) => {
      if (Object.prototype.hasOwnProperty.call(prev, name)) return prev
      return { ...prev, [name]: '[该最近文件仅记录名称，请重新打开文件后查看内容]' }
    })
    pushIdeRecentEntries([name])
  }, [pushIdeRecentEntries])

  const handleExplorerResizeStart = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault()
    const startX = event.clientX
    const startWidth = ideExplorerWidth
    const onMove = (e: MouseEvent) => {
      const next = Math.min(520, Math.max(180, startWidth + (e.clientX - startX)))
      setIdeExplorerWidth(next)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [ideExplorerWidth])

  const handleRightPaneResizeStart = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault()
    const startX = event.clientX
    const startWidth = ideRightPaneWidth
    const onMove = (e: MouseEvent) => {
      const next = Math.min(560, Math.max(220, startWidth - (e.clientX - startX)))
      setIdeRightPaneWidth(next)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [ideRightPaneWidth])

  const handleTerminalResizeStart = useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    if (ideTerminalCollapsed) return
    event.preventDefault()
    const startY = event.clientY
    const startHeight = ideTerminalHeight
    const onMove = (e: MouseEvent) => {
      const delta = startY - e.clientY
      const next = Math.min(520, Math.max(108, startHeight + delta))
      setIdeTerminalHeight(next)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
    document.body.style.cursor = 'row-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [ideTerminalCollapsed, ideTerminalHeight])

  const writeClipboardText = useCallback(async (text: string) => {
    const v = String(text || '')
    if (!v) return
    try {
      await navigator.clipboard?.writeText(v)
    } catch {
      // ignore clipboard failure
    }
  }, [])

  const resolveIdeAbsolutePath = useCallback((inputPath: string): string => {
    const relPath = normalizeIdePath(inputPath)
    if (!relPath) return ''
    if (/^[a-zA-Z]:\//.test(relPath) || relPath.startsWith('/')) {
      return relPath.replace(/\//g, '\\')
    }
    const savedAbs = String(ideSavedPaths[relPath] || '').trim()
    if (savedAbs) return savedAbs.replace(/\//g, '\\')
    const baseRaw = String(idePreferredCwd || '').trim()
    if (!baseRaw) return relPath.replace(/\//g, '\\')
    const baseNorm = baseRaw.replace(/\\/g, '/').replace(/\/+$/g, '')
    const baseName = baseNorm.split('/').filter(Boolean).pop()?.toLowerCase() || ''
    const parts = relPath.split('/').filter(Boolean)
    const relParts = (baseName && parts.length > 0 && parts[0].toLowerCase() === baseName)
      ? parts.slice(1)
      : parts
    const joined = relParts.length > 0 ? `${baseNorm}/${relParts.join('/')}` : baseNorm
    return joined.replace(/\//g, '\\')
  }, [idePreferredCwd, ideSavedPaths])

  const resolveIdeRelativePath = useCallback((inputPath: string): string => {
    const relPath = normalizeIdePath(inputPath)
    if (!relPath) return ''
    const baseRaw = String(idePreferredCwd || '').trim()
    if (!baseRaw) return relPath
    const baseName = baseRaw.replace(/\\/g, '/').replace(/\/+$/g, '').split('/').filter(Boolean).pop()?.toLowerCase() || ''
    const parts = relPath.split('/').filter(Boolean)
    if (baseName && parts.length > 0 && parts[0].toLowerCase() === baseName) {
      return parts.slice(1).join('/') || relPath
    }
    return relPath
  }, [idePreferredCwd])

  const handleCopyIdeEntry = useCallback(async (path: string, nodeType: 'file' | 'folder') => {
    const targetPath = normalizeIdePath(path)
    if (!targetPath) return
    if (nodeType !== 'file') {
      await writeClipboardText(targetPath.split('/').pop() || targetPath)
      return
    }
    const absPath = resolveIdeAbsolutePath(targetPath)
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (absPath && api?.copyFileToClipboard) {
      const copied = await api.copyFileToClipboard(absPath)
      if (copied?.success) return
      window.alert(copied?.error || '复制文件失败。')
      return
    }
    await writeClipboardText(absPath || targetPath)
    window.alert('当前环境暂不支持复制文件本体，已复制文件路径。')
  }, [resolveIdeAbsolutePath, writeClipboardText])

  const buildIdeQaRetrievalHint = useCallback((question: string): string => {
    if (!isTopoClawConversation || !ideModeEnabled) return ''
    const rootRel = normalizeIdePath(ideQaContextRootRelPath || '')
    if (!rootRel) return ''
    const tokens = buildQueryTokens(question)
    const queryLower = String(question || '').toLowerCase()
    const isOverviewQuestion = /(介绍|仓库|项目|架构|技术栈|目录|overview|repo|repository|readme)/i.test(queryLower)
    const prefixLower = rootRel.toLowerCase()
    const keyFiles = ['readme.md', 'package.json', 'pyproject.toml', 'requirements.txt', 'cargo.toml', 'go.mod']
    const scored: Array<{ path: string; score: number; snippet: string }> = []

    for (const rawPath of ideOpenedEntries) {
      const path = normalizeIdePath(rawPath)
      if (!path) continue
      const lowerPath = path.toLowerCase()
      if (!(lowerPath === prefixLower || lowerPath.startsWith(`${prefixLower}/`))) continue
      const content = ideFileContents[path] ?? ''
      if (!content || content.startsWith('[二进制文件')) continue
      let score = 0
      for (const token of tokens) {
        if (!token) continue
        if (lowerPath.includes(token)) score += 9
        score += countKeywordHits(content.toLowerCase(), token, 3) * 3
      }
      const baseName = basenameFromAnyPath(path).toLowerCase()
      if (isOverviewQuestion && keyFiles.includes(baseName)) score += 12
      if (isOverviewQuestion && /src\/|app\/|lib\/|topoclaw\/|customer_service\//i.test(lowerPath)) score += 3
      if (score <= 0 && !isOverviewQuestion) continue
      const snippet = pickSnippetByTokens(content, tokens)
      scored.push({ path, score, snippet })
    }

    scored.sort((a, b) => (b.score - a.score) || a.path.localeCompare(b.path, 'zh-CN'))
    const top = scored.slice(0, 8)
    const activePath = normalizeIdePath(ideActiveFile || '')
    const activeContent = activePath ? String(ideFileContents[activePath] ?? '') : ''
    const hasActiveFileContext = !!activePath && !!activeContent && !isIdeVirtualFileContent(activeContent)
    const activeAlreadyInTop = hasActiveFileContext
      ? top.some((item) => item.path.toLowerCase() === activePath.toLowerCase())
      : false
    const extraActiveHit = hasActiveFileContext && !activeAlreadyInTop ? 1 : 0
    setIdeQaLastRetrievalHitCount(top.length + extraActiveHit)
    if (top.length === 0 && !hasActiveFileContext) return ''

    const lines: string[] = []
    lines.push('[CODE_CONTEXT]')
    lines.push(`root_rel_path: ${rootRel}`)
    if (ideQaContextRootAbsPath) lines.push(`root_abs_path: ${ideQaContextRootAbsPath}`)
    if (hasActiveFileContext) lines.push(`active_file: ${activePath}`)
    lines.push(`matched_files: ${top.length}`)
    lines.push('请优先基于以下文件片段回答；若证据不足请明确说明。')
    let budget = 7200 - lines.join('\n').length
    if (hasActiveFileContext) {
      const activeBlock = [
        '[ACTIVE_FILE]',
        `[FILE] ${activePath}`,
        '[SNIPPET]',
        pickSnippetByTokens(activeContent, tokens) || '(empty)',
        '[/SNIPPET]',
        '[/ACTIVE_FILE]',
      ].join('\n')
      if (activeBlock.length <= budget) {
        lines.push(activeBlock)
        budget -= activeBlock.length + 1
      }
    }
    for (const item of top) {
      if (hasActiveFileContext && item.path.toLowerCase() === activePath.toLowerCase()) continue
      if (budget < 220) break
      const block = [
        `[FILE] ${item.path}`,
        '[SNIPPET]',
        item.snippet || '(empty)',
        '[/SNIPPET]',
      ].join('\n')
      if (block.length > budget) break
      lines.push(block)
      budget -= block.length + 1
    }
    lines.push('[/CODE_CONTEXT]')
    return lines.join('\n')
  }, [
    isTopoClawConversation,
    ideModeEnabled,
    ideQaContextRootRelPath,
    ideQaContextRootAbsPath,
    ideActiveFile,
    ideOpenedEntries,
    ideFileContents,
    isIdeVirtualFileContent,
  ])

  const syncIdeOpenedFilesFromDisk = useCallback(async () => {
    if (!ideModeEnabled || !isTopoClawConversation) return
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.readTextFile) return
    const candidates = ideOpenedEntries.slice(0, 160)
    const updates: Record<string, string> = {}
    for (const relPath of candidates) {
      const path = normalizeIdePath(relPath)
      if (!path) continue
      if (ideSyncIgnoredPathsRef.current.has(path.toLowerCase())) continue
      const current = ideFileContents[path]
      if (typeof current !== 'string') continue
      const baseline = ideSavedContents[path] ?? ''
      // Do not sync placeholders for binary/oversized/read-failed files.
      if (isIdeVirtualFileContent(current) || isIdeVirtualFileContent(baseline)) continue
      // Avoid overwriting unsaved local edits in editor.
      if (current !== baseline) continue
      const absPath = resolveIdeAbsolutePath(path)
      if (!absPath) continue
      try {
        const res = await api.readTextFile(absPath)
        if (!res?.ok || typeof res.content !== 'string') continue
        if (res.content === current) continue
        updates[path] = res.content
      } catch {
        // ignore file read failures per file
      }
    }
    if (Object.keys(updates).length === 0) return
    setIdeFileContents((prev) => ({ ...prev, ...updates }))
  }, [
    ideModeEnabled,
    isTopoClawConversation,
    ideOpenedEntries,
    ideFileContents,
    ideSavedContents,
    isIdeVirtualFileContent,
    resolveIdeAbsolutePath,
  ])

  useEffect(() => {
    if (!ideModeEnabled || !isTopoClawConversation) return
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api) return
    const rootAbs = String(ideQaContextRootAbsPath || idePreferredCwd || '').trim()
    let cancelled = false
    let polling = false

    const syncExplorerTreeFromDisk = async () => {
      if (cancelled || polling) return
      polling = true
      try {
        let names: string[] = []
        const contentMap: Record<string, string> = {}
        const savedPathMap: Record<string, string> = {}

        if (rootAbs && api.listFolderFiles) {
          const res = await api.listFolderFiles({ folderPath: rootAbs, maxFiles: 1500, maxBytes: 256 * 1024 })
          if (res?.ok && Array.isArray(res.files)) {
            const folderName = basenameFromAnyPath(rootAbs) || (ideQaContextRootRelPath || 'workspace')
            const base = rootAbs.replace(/[\\\/]+$/g, '')
            for (const item of res.files) {
              const rel = normalizeIdePath(String(item.relativePath || ''))
              const normalized = normalizeIdePath(`${folderName}/${rel}`)
              if (!normalized) continue
              names.push(normalized)
              contentMap[normalized] = typeof item.content === 'string' ? item.content : ''
              if (base && rel) savedPathMap[normalized] = `${base}\\${rel.replace(/\//g, '\\')}`
            }
          }
        } else if (api.listWorkspaceFiles) {
          const res = await api.listWorkspaceFiles({ maxFiles: 1200, maxBytes: 256 * 1024 })
          if (res?.ok && Array.isArray(res.files)) {
            const workspaceDir = String(res.workspaceDir || '').trim().replace(/\//g, '\\')
            for (const file of res.files) {
              const p = normalizeIdePath(String(file.relativePath || ''))
              if (!p) continue
              names.push(p)
              contentMap[p] = typeof file.content === 'string' ? file.content : ''
              if (workspaceDir) {
                const rel = p.replace(/^workspace\//i, '')
                savedPathMap[p] = `${workspaceDir.replace(/[\\\/]+$/g, '')}\\${rel.replace(/\//g, '\\')}`
              }
            }
          }
        }

        names = Array.from(new Set(names.map((v) => normalizeIdePath(v)).filter(Boolean)))
        if (names.length === 0) return
        const nextSet = new Set(names.map((p) => p.toLowerCase()))
        const currentSet = new Set(ideOpenedEntries.map((p) => normalizeIdePath(p).toLowerCase()).filter(Boolean))
        // Only mark assistant-added files after we already have an IDE baseline.
        // This avoids counting the very first Code-mode workspace scan as "本次修改".
        const canTrackAssistantAdded = currentSet.size > 0
        const addedFromDisk = canTrackAssistantAdded
          ? names
            .map((p) => normalizeIdePath(p).toLowerCase())
            .filter((key) => !!key && !currentSet.has(key))
          : []
        let changed = names.length !== ideOpenedEntries.length
        if (!changed) {
          for (const key of nextSet) {
            if (!currentSet.has(key)) {
              changed = true
              break
            }
          }
        }
        if (!changed) return

        const tree = buildIdeTree(names, ideManualFolders)
        setIdeExplorerTree(tree)
        setIdeOpenedEntries(names)
        setIdeAssistantAddedPaths((prev) => {
          const next = { ...prev }
          for (const key of Object.keys(next)) {
            if (!nextSet.has(key)) delete next[key]
          }
          for (const key of addedFromDisk) {
            if (key) next[key] = true
          }
          return next
        })
        setIdeOpenTabs((prev) => prev.filter((p) => nextSet.has(normalizeIdePath(p).toLowerCase())))
        setIdeActiveFile((prev) => {
          const cur = normalizeIdePath(prev || '')
          if (cur && nextSet.has(cur.toLowerCase())) return prev
          return names[0] || null
        })
        setIdeFileContents((prev) => {
          const next: Record<string, string> = {}
          for (const filePath of names) {
            if (ideUnsavedDrafts[filePath] && Object.prototype.hasOwnProperty.call(prev, filePath)) {
              next[filePath] = prev[filePath]
              continue
            }
            next[filePath] = Object.prototype.hasOwnProperty.call(contentMap, filePath)
              ? contentMap[filePath]
              : (prev[filePath] ?? '')
          }
          return next
        })
        setIdeSavedContents((prev) => {
          const next: Record<string, string> = {}
          for (const filePath of names) {
            next[filePath] = Object.prototype.hasOwnProperty.call(contentMap, filePath)
              ? contentMap[filePath]
              : (prev[filePath] ?? '')
          }
          return next
        })
        setIdeSavedPaths((prev) => ({ ...prev, ...savedPathMap }))
      } catch {
        // ignore periodic sync errors
      } finally {
        polling = false
      }
    }

    void syncExplorerTreeFromDisk()
    const timer = window.setInterval(() => { void syncExplorerTreeFromDisk() }, 2500)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [
    ideModeEnabled,
    isTopoClawConversation,
    ideQaContextRootAbsPath,
    ideQaContextRootRelPath,
    idePreferredCwd,
    ideOpenedEntries,
    ideManualFolders,
    ideUnsavedDrafts,
  ])

  useEffect(() => {
    if (!ideModeEnabled || !isTopoClawConversation) return
    if (loading) return
    const timer = window.setTimeout(() => {
      void syncIdeOpenedFilesFromDisk()
    }, 120)
    return () => window.clearTimeout(timer)
  }, [ideModeEnabled, isTopoClawConversation, loading, messages.length, ideOpenedEntries.length, syncIdeOpenedFilesFromDisk])

  useEffect(() => {
    ideSyncIgnoredPathsRef.current.clear()
  }, [ideQaContextRootAbsPath])

  const handleIdeRenameFile = useCallback((oldPath: string) => {
    const source = normalizeIdePath(oldPath)
    if (!source) return
    const oldName = source.split('/').pop() || source
    const nextName = String(window.prompt('重命名文件', oldName) || '').trim()
    if (!nextName || nextName === oldName) return
    const prefix = source.includes('/') ? `${source.slice(0, source.lastIndexOf('/'))}/` : ''
    const nextPath = normalizeIdePath(`${prefix}${nextName}`)
    const nextOpened = ideOpenedEntries.map((p) => (p === source ? nextPath : p))
    const nextTree = buildIdeTree(nextOpened, ideManualFolders)
    setIdeOpenedEntries(nextOpened)
    setIdeOpenTabs((prev) => prev.map((p) => (p === source ? nextPath : p)))
    setIdeExplorerTree(nextTree)
    setIdeExpandedFolders(new Set(collectIdeFolderPaths(nextTree)))
    setIdeFileContents((prev) => {
      const next: Record<string, string> = { ...prev }
      if (Object.prototype.hasOwnProperty.call(next, source)) {
        next[nextPath] = next[source]
        delete next[source]
      }
      return next
    })
    setIdeSavedPaths((prev) => {
      const next = { ...prev }
      if (Object.prototype.hasOwnProperty.call(next, source)) {
        next[nextPath] = next[source]
        delete next[source]
      }
      return next
    })
    setIdeSavedContents((prev) => {
      const next = { ...prev }
      if (Object.prototype.hasOwnProperty.call(next, source)) {
        next[nextPath] = next[source]
        delete next[source]
      }
      return next
    })
    setIdeUnsavedDrafts((prev) => {
      if (!Object.prototype.hasOwnProperty.call(prev, source)) return prev
      const next = { ...prev }
      next[nextPath] = true
      delete next[source]
      return next
    })
    setIdeRecentEntries((prev) => prev.map((p) => (p === source ? nextPath : p)))
    setIdeActiveFile((prev) => (prev === source ? nextPath : prev))
    pushIdeRecentEntries([nextPath])
  }, [ideOpenedEntries, ideManualFolders, pushIdeRecentEntries])

  const handleIdeDeleteFile = useCallback((targetPath: string) => {
    const path = normalizeIdePath(targetPath)
    if (!path) return
    if (window.confirm(`确认删除文件「${path}」吗？`)) {
      const nextOpened = ideOpenedEntries.filter((p) => p !== path)
      const nextTabs = ideOpenTabs.filter((p) => p !== path)
      const nextTree = buildIdeTree(nextOpened, ideManualFolders)
      setIdeOpenedEntries(nextOpened)
      setIdeOpenTabs(nextTabs)
      setIdeExplorerTree(nextTree)
      setIdeExpandedFolders(new Set(collectIdeFolderPaths(nextTree)))
      setIdeFileContents((prev) => {
        const next = { ...prev }
        delete next[path]
        return next
      })
      setIdeSavedPaths((prev) => {
        const next = { ...prev }
        delete next[path]
        return next
      })
      setIdeSavedContents((prev) => {
        const next = { ...prev }
        delete next[path]
        return next
      })
      setIdeUnsavedDrafts((prev) => {
        const next = { ...prev }
        delete next[path]
        return next
      })
      setIdeRecentEntries((prev) => prev.filter((p) => p !== path))
      setIdeActiveFile((prev) => {
        if (prev !== path) return prev
        return nextTabs[0] ?? nextOpened[0] ?? null
      })
    }
  }, [ideOpenedEntries, ideOpenTabs, ideManualFolders])

  const handleIdeCreateNewFile = useCallback(() => {
    const occupied = new Set(ideOpenedEntries.map((p) => normalizeIdePath(p).toLowerCase()))
    let n = 1
    let finalPath = `未命名-${n}.txt`
    while (occupied.has(finalPath.toLowerCase())) {
      n += 1
      finalPath = `未命名-${n}.txt`
    }
    const nextOpened = [finalPath, ...ideOpenedEntries.filter((p) => normalizeIdePath(p).toLowerCase() !== finalPath.toLowerCase())]
    const nextTree = buildIdeTree(nextOpened, ideManualFolders)
    setIdeOpenedEntries(nextOpened)
    setIdeOpenTabs((prev) => [finalPath, ...prev.filter((p) => p.toLowerCase() !== finalPath.toLowerCase())])
    setIdeExplorerTree(nextTree)
    setIdeExpandedFolders(new Set(collectIdeFolderPaths(nextTree)))
    setIdeFileContents((prev) => ({ ...prev, [finalPath]: '' }))
    setIdeSavedContents((prev) => ({ ...prev, [finalPath]: '' }))
    setIdeUnsavedDrafts((prev) => ({ ...prev, [finalPath]: true }))
    setIdeActiveFile(finalPath)
    pushIdeRecentEntries([finalPath])
  }, [ideOpenedEntries, ideManualFolders, pushIdeRecentEntries])

  const saveIdeFile = useCallback(async (targetPath: string, saveAs = false): Promise<string | null> => {
    const active = normalizeIdePath(targetPath)
    if (!active) return null
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.saveTextAs) return null
    const text = ideFileContents[active] ?? ''
    const defaultName = basenameFromAnyPath(active) || 'untitled.txt'
    const existing = ideSavedPaths[active]
    const isDraft = !!ideUnsavedDrafts[active]
    if (!saveAs && existing && api.writeTextFile && !isDraft) {
      const writeRes = await api.writeTextFile(existing, text)
      if (writeRes?.ok) {
        setIdeSavedContents((prev) => ({ ...prev, [active]: text }))
        return active
      }
    }
    const saveRes = await api.saveTextAs(text, defaultName)
    if (!saveRes?.ok || !saveRes.path) return null
    const nextAbsPath = String(saveRes.path)
    const nextDisplayName = normalizeIdePath(basenameFromAnyPath(nextAbsPath) || defaultName)

    if (nextDisplayName && nextDisplayName.toLowerCase() !== active.toLowerCase()) {
      setIdeOpenedEntries((prev) => {
        const replaced = prev.map((p) => (p.toLowerCase() === active.toLowerCase() ? nextDisplayName : p))
        const uniq: string[] = []
        const seen = new Set<string>()
        for (const item of replaced) {
          const k = item.toLowerCase()
          if (seen.has(k)) continue
          seen.add(k)
          uniq.push(item)
        }
        const nextTree = buildIdeTree(uniq, ideManualFolders)
        setIdeExplorerTree(nextTree)
        setIdeExpandedFolders(new Set(collectIdeFolderPaths(nextTree)))
        return uniq
      })
      setIdeOpenTabs((prev) => prev.map((p) => (p.toLowerCase() === active.toLowerCase() ? nextDisplayName : p)))
      setIdeRecentEntries((prev) => prev.map((p) => (p.toLowerCase() === active.toLowerCase() ? nextDisplayName : p)))
      setIdeFileContents((prev) => {
        const next = { ...prev }
        next[nextDisplayName] = prev[active] ?? ''
        delete next[active]
        return next
      })
      setIdeSavedContents((prev) => {
        const next = { ...prev }
        next[nextDisplayName] = text
        delete next[active]
        return next
      })
      setIdeSavedPaths((prev) => {
        const next = { ...prev }
        next[nextDisplayName] = nextAbsPath
        delete next[active]
        return next
      })
      setIdeUnsavedDrafts((prev) => {
        const next = { ...prev }
        delete next[active]
        return next
      })
      setIdeActiveFile(nextDisplayName)
      pushIdeRecentEntries([nextDisplayName])
      return nextDisplayName
    }
    setIdeSavedPaths((prev) => ({ ...prev, [active]: nextAbsPath }))
    setIdeSavedContents((prev) => ({ ...prev, [active]: text }))
    setIdeUnsavedDrafts((prev) => {
      const next = { ...prev }
      delete next[active]
      return next
    })
    return active
  }, [ideFileContents, ideSavedPaths, ideUnsavedDrafts, ideManualFolders, pushIdeRecentEntries])

  const handleIdeSaveCurrentFile = useCallback(async (saveAs = false): Promise<string | null> => {
    const active = ideActiveFile
    if (!active) return null
    return saveIdeFile(active, saveAs)
  }, [ideActiveFile, saveIdeFile])

  const handleOpenActiveFileExternally = useCallback(async () => {
    const active = normalizeIdePath(ideActiveFile || '')
    if (!active) return
    const absPath = resolveIdeAbsolutePath(active)
    if (!absPath) return
    const fileUrl = ideAbsPathToFileUrl(absPath)
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api) return
    if (api.openPath) {
      const res = await api.openPath(absPath)
      if (!res?.success) window.alert(res?.error || '无法用系统应用打开该文件。')
      return
    }
    if (api.openExternal && fileUrl) {
      const res = await api.openExternal(fileUrl)
      if (!res?.success) window.alert(res?.error || '无法用系统应用打开该文件。')
      return
    }
    if (api.showItemInFolder) {
      await api.showItemInFolder(absPath)
      return
    }
    window.alert('当前环境暂不支持外部打开文件。')
  }, [ideActiveFile, resolveIdeAbsolutePath])

  const handleJumpToIdeEntryFolder = useCallback(async (path: string, nodeType: 'file' | 'folder') => {
    const targetPath = normalizeIdePath(path)
    if (!targetPath) return
    const absPath = resolveIdeAbsolutePath(targetPath)
    if (!absPath) {
      window.alert('无法定位目标路径。')
      return
    }
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api) {
      window.alert('当前环境暂不支持打开文件夹。')
      return
    }

    if (nodeType === 'folder') {
      if (api.openPath) {
        const res = await api.openPath(absPath)
        if (!res?.success) window.alert(res?.error || '打开文件夹失败。')
        return
      }
      if (api.showItemInFolder) {
        const res = await api.showItemInFolder(absPath)
        if (!res?.success) window.alert(res?.error || '打开文件夹失败。')
        return
      }
      window.alert('当前环境暂不支持打开文件夹。')
      return
    }

    if (api.showItemInFolder) {
      const res = await api.showItemInFolder(absPath)
      if (!res?.success) window.alert(res?.error || '打开文件夹失败。')
      return
    }
    if (api.openPath) {
      const folderPath = ideDirnameFromAnyPath(absPath) || absPath
      const res = await api.openPath(folderPath)
      if (!res?.success) window.alert(res?.error || '打开文件夹失败。')
      return
    }
    window.alert('当前环境暂不支持打开文件夹。')
  }, [resolveIdeAbsolutePath])

  const ideFindMatches = useMemo(() => {
    const keyword = ideFindKeyword.trim().toLowerCase()
    if (!keyword) return [] as number[]
    const text = (ideActiveFile ? (ideFileContents[ideActiveFile] ?? '') : '')
    if (!text) return [] as number[]
    const source = text.toLowerCase()
    const out: number[] = []
    let from = 0
    while (from <= source.length) {
      const idx = source.indexOf(keyword, from)
      if (idx < 0) break
      out.push(idx)
      from = idx + Math.max(keyword.length, 1)
      if (out.length >= 2000) break
    }
    return out
  }, [ideFindKeyword, ideActiveFile, ideFileContents])

  const scrollIdeEditorToIndex = useCallback((index: number) => {
    const el = ideEditorRef.current
    if (!el) return
    const style = window.getComputedStyle(el)
    const lineHeight = Number.parseFloat(style.lineHeight || '') || 18
    const line = el.value.slice(0, Math.max(index, 0)).split('\n').length - 1
    const target = Math.max(0, line * lineHeight - el.clientHeight * 0.35)
    el.scrollTop = target
  }, [])

  const runIdeFind = useCallback((direction: 1 | -1 = 1) => {
    const keyword = ideFindKeyword.trim()
    if (!keyword) return false
    const el = ideEditorRef.current
    const matches = ideFindMatches
    if (!el || matches.length === 0) {
      setIdeFindCurrentMatch(0)
      return false
    }
    const selStart = Math.max(el.selectionStart, 0)
    const selEnd = Math.max(el.selectionEnd, selStart)
    let targetIdx = -1
    if (direction === 1) {
      targetIdx = matches.find((m) => m >= selEnd) ?? matches[0]
    } else {
      for (let i = matches.length - 1; i >= 0; i--) {
        if (matches[i] < selStart) {
          targetIdx = matches[i]
          break
        }
      }
      if (targetIdx < 0) targetIdx = matches[matches.length - 1]
    }
    const ordinal = matches.indexOf(targetIdx) + 1
    setIdeFindCurrentMatch(ordinal)
    el.focus()
    el.setSelectionRange(targetIdx, targetIdx + keyword.length)
    scrollIdeEditorToIndex(targetIdx)
    return true
  }, [ideFindKeyword, ideFindMatches, scrollIdeEditorToIndex])

  const openIdeFindPanel = useCallback(() => {
    const editor = ideEditorRef.current
    if (!editor) return
    const selected = editor.value.slice(editor.selectionStart, editor.selectionEnd).trim()
    if (selected && selected.length < 120 && !selected.includes('\n')) {
      setIdeFindKeyword(selected)
    }
    setIdeFindOpen(true)
    setIdeFindCurrentMatch(0)
    setTimeout(() => {
      ideFindInputRef.current?.focus()
      ideFindInputRef.current?.select()
    }, 0)
  }, [])

  const handleCodeEditorKeyDown = useCallback((event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const ctrlOrMeta = event.ctrlKey || event.metaKey
    if (!ctrlOrMeta) return
    const key = event.key.toLowerCase()
    if (['c', 'v', 'x', 'z', 'a'].includes(key)) {
      event.stopPropagation()
      return
    }
    if (key === 'f') {
      event.preventDefault()
      event.stopPropagation()
      openIdeFindPanel()
    }
  }, [openIdeFindPanel])

  useEffect(() => {
    if (!ideModeEnabled) return
    const onKeyDown = (event: KeyboardEvent) => {
      const ctrlOrMeta = event.ctrlKey || event.metaKey
      if (!ctrlOrMeta) return
      if (event.key.toLowerCase() !== 'f') return
      event.preventDefault()
      event.stopPropagation()
      const activeEl = document.activeElement
      const activeTerminalHost = ideTerminalHostsRef.current[ideActiveTerminalId]
      const isInTerminal = !!(activeEl && activeTerminalHost && activeTerminalHost.contains(activeEl))
      if (isInTerminal) {
        openTerminalFindPanel()
        return
      }
      if (!ideActiveFile) return
      openIdeFindPanel()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => window.removeEventListener('keydown', onKeyDown, true)
  }, [ideModeEnabled, ideActiveFile, ideActiveTerminalId, openIdeFindPanel, openTerminalFindPanel])

  useEffect(() => {
    if (!ideFindOpen) return
    if (ideFindMatches.length === 0) {
      setIdeFindCurrentMatch(0)
      return
    }
    const el = ideEditorRef.current
    if (!el) {
      setIdeFindCurrentMatch(1)
      return
    }
    const selStart = Math.max(el.selectionStart, 0)
    const keywordLen = Math.max(1, ideFindKeyword.trim().length)
    let idx = ideFindMatches.findIndex((m) => selStart >= m && selStart <= m + keywordLen)
    if (idx < 0) {
      idx = ideFindMatches.findIndex((m) => m >= selStart)
      if (idx < 0) idx = 0
    }
    setIdeFindCurrentMatch(idx + 1)
  }, [ideFindOpen, ideFindMatches, ideFindKeyword, ideActiveFile])

  useEffect(() => {
    if (!ideTerminalFindOpen) return
    const total = calcTerminalMatchTotal(ideActiveTerminalId, ideTerminalFindKeyword)
    setIdeTerminalFindTotal(total)
    if (total === 0) {
      setIdeTerminalFindCurrent(0)
      return
    }
    setIdeTerminalFindCurrent((prev) => Math.min(Math.max(prev, 1), total))
  }, [ideTerminalFindOpen, ideActiveTerminalId, ideTerminalFindKeyword, calcTerminalMatchTotal])

  const handleCreateNewTerminal = useCallback(() => {
    const n = ideTerminalCounterRef.current + 1
    ideTerminalCounterRef.current = n
    const id = `py-${n}`
    const tab = { id, name: `终端${n}` }
    setIdeTerminals((prev) => [...prev, tab])
    setIdeActiveTerminalId(id)
    setIdeTerminalVisible(true)
    setIdeTerminalCollapsed(false)
  }, [])

  const waitForIdeTerminalReady = useCallback(async (timeoutMs = 2200): Promise<boolean> => {
    const startedAt = Date.now()
    while (Date.now() - startedAt < timeoutMs) {
      if (ideTerminalConnectedRef.current) return true
      await new Promise((resolve) => window.setTimeout(resolve, 60))
    }
    return ideTerminalConnectedRef.current
  }, [])

  const handleHideTerminalPanel = useCallback(() => {
    setIdeTerminalVisible(false)
    setIdeTerminalCollapsed(true)
    setIdeTerminalFindOpen(false)
  }, [])

  const handleRunActiveFileInTerminal = useCallback(async () => {
    const activePath = normalizeIdePath(ideActiveFile || '')
    if (!activePath) {
      window.alert('请先打开要运行的文件。')
      return
    }
    if (ideTerminalBusyRef.current || ideTerminalRunning) {
      window.alert('终端运行中，请稍后再试')
      return
    }
    const ext = ideFileExt(activePath)
    const commandExt = String(ext || '').toLowerCase()
    const commandCapable = ['py', 'js', 'mjs', 'cjs', 'ts', 'ps1', 'bat', 'cmd', 'sh']
    if (!commandCapable.includes(commandExt)) {
      window.alert(`当前文件类型（.${commandExt || 'unknown'}）暂不支持一键运行。`)
      return
    }

    const savedPath = await saveIdeFile(activePath, false)
    const targetPath = normalizeIdePath(savedPath || activePath)
    if (!targetPath) {
      window.alert('运行前请先保存当前文件。')
      return
    }
    const absPath = resolveIdeAbsolutePath(targetPath)
    if (!absPath) {
      window.alert('无法解析当前文件绝对路径，暂时不能运行。')
      return
    }

    setIdeTerminalMenuOpen(false)
    setIdeTerminalVisible(true)
    setIdeTerminalCollapsed(false)
    if (!ideActiveTerminalId && ideTerminals[0]?.id) {
      setIdeActiveTerminalId(ideTerminals[0].id)
    }
    const ready = await waitForIdeTerminalReady()
    if (!ready) {
      window.alert('终端尚未就绪，请稍后再试。')
      return
    }
    if (ideTerminalBusyRef.current || ideTerminalRunning) {
      window.alert('终端运行中，请稍后再试')
      return
    }

    const runCmd = buildIdeRunCommand(commandExt, absPath)
    if (!runCmd) {
      window.alert(`当前文件类型（.${commandExt || 'unknown'}）暂不支持一键运行。`)
      return
    }

    const terminalApi = (typeof window !== 'undefined'
      ? (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI
      : undefined)
    if (!terminalApi?.write) {
      window.alert('当前环境不支持内置终端运行。')
      return
    }
    const rec = ideTerminalMapRef.current[ideActiveTerminalId || ideTerminals[0]?.id || '']
    rec?.term.writeln(`\x1b[36m运行：${runCmd}\x1b[0m`)
    ideTerminalBusyRef.current = true
    setIdeTerminalRunning(true)
    terminalApi.write(`${runCmd}\r`)
  }, [ideActiveFile, ideActiveTerminalId, ideTerminals, ideTerminalRunning, resolveIdeAbsolutePath, saveIdeFile, waitForIdeTerminalReady])

  const handleCloseTerminal = useCallback((terminalId: string) => {
    const rec = ideTerminalMapRef.current[terminalId]
    if (rec) {
      rec.unsub?.()
      rec.term.dispose()
      delete ideTerminalMapRef.current[terminalId]
      delete ideTerminalHostsRef.current[terminalId]
    }
    setIdeTerminals((prev) => {
      const next = prev.filter((t) => t.id !== terminalId)
      setIdeActiveTerminalId((current) => (current === terminalId ? (next[0]?.id || '') : current))
      return next
    })
  }, [])

  const ideExplorerSearchResults = useMemo(() => {
    const key = ideExplorerSearchKeyword.trim().toLowerCase()
    if (!key) return [] as Array<{ path: string; line: number; preview: string }>
    const out: Array<{ path: string; line: number; preview: string }> = []
    for (const filePath of ideOpenedEntries) {
      const normalizedPath = normalizeIdePath(filePath)
      if (!normalizedPath) continue
      const pathHit = normalizedPath.toLowerCase().includes(key)
      let line = 0
      let preview = ''
      const content = ideFileContents[normalizedPath] || ''
      if (content) {
        const lines = content.split(/\r?\n/)
        for (let i = 0; i < lines.length; i++) {
          if (lines[i].toLowerCase().includes(key)) {
            line = i + 1
            preview = lines[i].trim()
            break
          }
        }
      }
      if (!pathHit && line === 0) continue
      out.push({
        path: normalizedPath,
        line,
        preview: preview || normalizedPath,
      })
      if (out.length >= 120) break
    }
    return out
  }, [ideExplorerSearchKeyword, ideOpenedEntries, ideFileContents])

  const handleOpenExplorerSearchResult = useCallback((result: { path: string; line: number }) => {
    const path = normalizeIdePath(result.path)
    if (!path) return
    setIdeActiveFile(path)
    setIdeOpenedEntries((prev) => (prev.some((p) => p.toLowerCase() === path.toLowerCase()) ? prev : [path, ...prev]))
    setIdeOpenTabs((prev) => (prev.some((p) => p.toLowerCase() === path.toLowerCase()) ? prev : [...prev, path]))
    setIdeSavedContents((prev) => {
      if (Object.prototype.hasOwnProperty.call(prev, path)) return prev
      return { ...prev, [path]: ideFileContents[path] ?? '' }
    })
    pushIdeRecentEntries([path])
    if (result.line > 0) {
      setTimeout(() => {
        const el = ideEditorRef.current
        if (!el) return
        const lines = el.value.split(/\r?\n/)
        const lineIndex = Math.max(0, Math.min(result.line - 1, lines.length - 1))
        const offset = lines.slice(0, lineIndex).reduce((acc, v) => acc + v.length + 1, 0)
        el.focus()
        el.setSelectionRange(offset, offset + (lines[lineIndex]?.length || 0))
      }, 0)
    }
  }, [pushIdeRecentEntries, ideFileContents])

  const closeIdeTabDirectly = useCallback((targetPath: string) => {
    const path = normalizeIdePath(targetPath)
    if (!path) return
    setIdeOpenTabs((prev) => {
      const index = prev.findIndex((p) => p.toLowerCase() === path.toLowerCase())
      if (index < 0) return prev
      const next = prev.filter((_, i) => i !== index)
      setIdeActiveFile((current) => {
        if (!current || current.toLowerCase() !== path.toLowerCase()) return current
        return next[index] ?? next[index - 1] ?? null
      })
      return next
    })
  }, [])

  const handleIdeCloseTab = useCallback(async (targetPath: string) => {
    const path = normalizeIdePath(targetPath)
    if (!path) return
    const currentText = ideFileContents[path] ?? ''
    const savedText = ideSavedContents[path]
    const isDirty = !!ideUnsavedDrafts[path] || (typeof savedText === 'string' && savedText !== currentText)
    let closingPath = path
    if (isDirty) {
      const shouldSave = window.confirm(`文件「${path}」尚未保存。\n\n点击“确定”：保存并关闭\n点击“取消”：进入放弃更改确认`)
      if (shouldSave) {
        const savedPath = await saveIdeFile(path, false)
        if (!savedPath) return
        closingPath = savedPath
      } else {
        const shouldDiscard = window.confirm(`确认放弃「${path}」未保存的更改并关闭吗？`)
        if (!shouldDiscard) return
      }
    }
    closeIdeTabDirectly(closingPath)
  }, [ideFileContents, ideSavedContents, ideUnsavedDrafts, saveIdeFile, closeIdeTabDirectly])

  useEffect(() => {
    const active = normalizeIdePath(ideActiveFile || '')
    if (!active) return
    setIdeOpenTabs((prev) => (prev.some((p) => p.toLowerCase() === active.toLowerCase()) ? prev : [...prev, active]))
  }, [ideActiveFile])

  const displayItems = useMemo(() => {
    type Item =
      | { type: 'user' | 'assistant'; id: string; message: Message }
      | { type: 'system_group'; id: string; contents: string[] }
      | { type: 'system_plain'; id: string; contents: string[] }
      | { type: 'assistant_with_thinking'; id: string; thinkingContents: string[]; answerMessage: Message }
    const items: Item[] = []
    let systemBuffer: Message[] = []
    const flushSystem = (nextMsg?: Message) => {
      if (systemBuffer.length === 0) return
      const contents = systemBuffer.map((s) => s.content)
      const groupId = systemBuffer[0].id
      if (nextMsg?.type === 'assistant') {
        if (suppressThinkingChrome) {
          systemBuffer = []
          items.push({ type: 'assistant', id: nextMsg.id, message: nextMsg })
          return true
        }
        items.push({ type: 'assistant_with_thinking', id: nextMsg.id, thinkingContents: contents, answerMessage: nextMsg })
        systemBuffer = []
        return true
      }
      if (suppressThinkingChrome) {
        items.push({ type: 'system_plain', id: groupId, contents })
      } else {
        items.push({ type: 'system_group', id: groupId, contents })
      }
      systemBuffer = []
      return false
    }
    for (let i = 0; i < messages.length; i++) {
      const m = messages[i]
      if (isTaskEventMessageText(m.content)) continue
      if (m.type === 'system') {
        systemBuffer.push(m)
      } else {
        const merged = flushSystem(m)
        if (!merged) {
          if (m.type === 'assistant' && m.thinkingContents?.length && !suppressThinkingChrome) {
            items.push({ type: 'assistant_with_thinking', id: m.id, thinkingContents: m.thinkingContents, answerMessage: m })
          } else {
            items.push({ type: m.type as 'user' | 'assistant', id: m.id, message: m })
          }
        }
      }
    }
    flushSystem()
    return items
  }, [messages, suppressThinkingChrome])

  const conversationImageGallery = useMemo(() => {
    const images: Array<{ key: string; dataUrl: string; fileName: string }> = []
    const keyToIndex = new Map<string, number>()
    const isImageFileName = (fileName?: string) =>
      !!(fileName?.match(/\.(png|jpe?g|gif|webp)$/i) || fileName === '图片.png')

    const collectFromMessage = (message: Message) => {
      const files =
        message.fileList && message.fileList.length > 0
          ? message.fileList
          : (message.fileBase64 ? [{ fileBase64: message.fileBase64, fileName: message.fileName }] : [])
      if (!files.length) return
      if (!files.every((f) => isImageFileName(f.fileName))) return
      files.forEach((f, idx) => {
        const dataUrl = toChatImageSrc(f.fileBase64, f.fileName)
        if (!dataUrl) return
        const key = `${message.id}_${idx}`
        keyToIndex.set(key, images.length)
        images.push({ key, dataUrl, fileName: f.fileName || '图片.png' })
      })
    }

    displayItems.forEach((item) => {
      if (item.type === 'user' || item.type === 'assistant') {
        collectFromMessage(item.message)
        return
      }
      if (item.type === 'assistant_with_thinking') {
        collectFromMessage(item.answerMessage)
      }
    })

    return { images, keyToIndex }
  }, [displayItems])

  const openConversationImageLightbox = useCallback((imageKey: string, fallbackPayload: ImageLightboxPayload) => {
    const currentIndex = conversationImageGallery.keyToIndex.get(imageKey)
    if (typeof currentIndex !== 'number') {
      setImageLightbox(fallbackPayload)
      return
    }
    const current = conversationImageGallery.images[currentIndex]
    setImageLightbox({
      dataUrl: current?.dataUrl || fallbackPayload.dataUrl,
      fileName: current?.fileName || fallbackPayload.fileName,
      gallery: conversationImageGallery.images.map((item) => ({ dataUrl: item.dataUrl, fileName: item.fileName })),
      initialIndex: currentIndex,
    })
  }, [conversationImageGallery])

  const [hoveredMessageId, setHoveredMessageId] = useState<string | null>(null)

  const ideChangedFiles = useMemo(() => {
    const keys = new Set<string>([
      ...Object.keys(ideFileContents || {}),
      ...Object.keys(ideSavedContents || {}),
    ])
    const out: Array<{ path: string; added: number; removed: number; hunks: IdeDiffHunk[] }> = []
    keys.forEach((rawPath) => {
      const path = normalizeIdePath(rawPath)
      if (!path) return
      if (shouldIgnoreIdeChangeStats(path)) return
      const pathKey = path.toLowerCase()
      if (ideHumanEditedPaths[pathKey]) return
      const current = ideFileContents[path]
      const saved = ideSavedContents[path]
      if (typeof current !== 'string') return
      const baseline = typeof saved === 'string' ? saved : ''
      const isAssistantAdded = !!ideAssistantAddedPaths[pathKey]
      const isUnsavedNewFile = !!ideUnsavedDrafts[path] && !String(ideSavedPaths[path] || '').trim()
      if (current === baseline) {
        if (!isUnsavedNewFile && !isAssistantAdded) return
        out.push({
          path,
          added: Math.max(1, String(current || '').split(/\r?\n/).length),
          removed: 0,
          hunks: [{ lines: [{ kind: 'add', text: current || '[新建空文件]' }] }],
        })
        return
      }
      const diff = buildLineDiff(baseline, current)
      if (diff.added === 0 && diff.removed === 0) return
      out.push({ path, added: diff.added, removed: diff.removed, hunks: diff.hunks })
    })
    out.sort((a, b) => a.path.localeCompare(b.path, 'zh-CN'))
    return out
  }, [ideFileContents, ideSavedContents, ideUnsavedDrafts, ideSavedPaths, ideHumanEditedPaths, ideAssistantAddedPaths])
  const ideChangedTotalAdded = useMemo(() => ideChangedFiles.reduce((acc, item) => acc + item.added, 0), [ideChangedFiles])
  const ideChangedTotalRemoved = useMemo(() => ideChangedFiles.reduce((acc, item) => acc + item.removed, 0), [ideChangedFiles])
  const ideDiffReviewIndex = useMemo(() => {
    if (!ideDiffReviewPath) return -1
    return ideChangedFiles.findIndex((item) => item.path.toLowerCase() === ideDiffReviewPath.toLowerCase())
  }, [ideDiffReviewPath, ideChangedFiles])
  const ideDiffReviewCurrent = ideDiffReviewIndex >= 0 ? ideChangedFiles[ideDiffReviewIndex] : null

  const handleAcceptDiffForPath = useCallback((targetPath: string) => {
    const path = normalizeIdePath(targetPath)
    if (!path) return
    const pathKey = path.toLowerCase()
    ideSyncIgnoredPathsRef.current.add(path.toLowerCase())
    setIdeSavedContents((prev) => ({ ...prev, [path]: ideFileContents[path] ?? '' }))
    setIdeUnsavedDrafts((prev) => {
      const next = { ...prev }
      delete next[path]
      return next
    })
    setIdeAssistantAddedPaths((prev) => {
      const next = { ...prev }
      delete next[pathKey]
      return next
    })
  }, [ideFileContents])

  const handleRejectDiffForPath = useCallback((targetPath: string) => {
    const path = normalizeIdePath(targetPath)
    if (!path) return
    const pathKey = path.toLowerCase()
    const baseline = ideSavedContents[path]
    ideSyncIgnoredPathsRef.current.add(path.toLowerCase())
    setIdeFileContents((prev) => {
      const next = { ...prev }
      next[path] = typeof baseline === 'string' ? baseline : ''
      return next
    })
    setIdeUnsavedDrafts((prev) => {
      const next = { ...prev }
      delete next[path]
      return next
    })
    setIdeAssistantAddedPaths((prev) => {
      const next = { ...prev }
      delete next[pathKey]
      return next
    })
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    const targetAbsPath = resolveIdeAbsolutePath(path)
    if (api?.writeTextFile && targetAbsPath) {
      void api.writeTextFile(targetAbsPath, typeof baseline === 'string' ? baseline : '')
    }
  }, [ideSavedContents, resolveIdeAbsolutePath])

  useEffect(() => {
    if (!ideDiffReviewPath) return
    const exists = ideChangedFiles.some((item) => item.path.toLowerCase() === ideDiffReviewPath.toLowerCase())
    if (exists) return
    setIdeDiffReviewPath(ideChangedFiles[0]?.path ?? null)
  }, [ideDiffReviewPath, ideChangedFiles])

  useEffect(() => {
    const activePath = normalizeIdePath(ideActiveFile || '')
    const ext = activePath ? ideFileExt(activePath).toLowerCase() : ''
    if (ext !== 'pptx') {
      setIdePptxPreviewLoading(false)
      setIdePptxPreviewSlides([])
      setIdePptxPreviewError('')
      return
    }
    const absPath = String(resolveIdeAbsolutePath(activePath) || '').trim()
    if (!absPath) {
      setIdePptxPreviewSlides([])
      setIdePptxPreviewError('无法定位当前 PPTX 文件路径。')
      return
    }
    const cacheHit = idePptxPreviewCacheRef.current[absPath]
    if (cacheHit) {
      setIdePptxPreviewSlides(cacheHit)
      setIdePptxPreviewError('')
      setIdePptxPreviewLoading(false)
      return
    }
    const cachedErr = idePptxPreviewErrorCacheRef.current[absPath]
    if (cachedErr) {
      setIdePptxPreviewSlides([])
      setIdePptxPreviewError(cachedErr)
      setIdePptxPreviewLoading(false)
      return
    }
    const api = (typeof window !== 'undefined'
      ? (window as Window & { electronAPI?: IdeWorkspaceBridge }).electronAPI
      : undefined)
    if (!api?.readBinaryFile) {
      setIdePptxPreviewSlides([])
      setIdePptxPreviewError('当前环境不支持 PPTX 内置预览，请使用“外部编辑”。')
      return
    }
    let cancelled = false
    setIdePptxPreviewLoading(true)
    setIdePptxPreviewError('')
    setIdePptxPreviewSlides([])
    void api.readBinaryFile(absPath).then(async (res) => {
      if (cancelled) return
      if (!res?.ok || !res.base64) {
        const err = res?.error || '读取 PPTX 失败，请使用“外部编辑”。'
        idePptxPreviewErrorCacheRef.current[absPath] = err
        setIdePptxPreviewError(err)
        return
      }
      try {
        const slides = await extractPptxSlideTextsFromBase64(res.base64)
        if (cancelled) return
        if (slides.length === 0) {
          const err = '该 PPTX 未解析到可展示文字，建议使用“外部编辑”。'
          idePptxPreviewErrorCacheRef.current[absPath] = err
          setIdePptxPreviewError(err)
          return
        }
        idePptxPreviewCacheRef.current[absPath] = slides
        setIdePptxPreviewSlides(slides)
      } catch (err) {
        if (cancelled) return
        const msg = err instanceof Error ? err.message : String(err)
        const text = `PPTX 预览解析失败：${msg}`
        idePptxPreviewErrorCacheRef.current[absPath] = text
        setIdePptxPreviewError(text)
      } finally {
        if (!cancelled) setIdePptxPreviewLoading(false)
      }
    }).catch((err) => {
      if (cancelled) return
      const msg = err instanceof Error ? err.message : String(err)
      const text = `读取 PPTX 失败：${msg}`
      idePptxPreviewErrorCacheRef.current[absPath] = text
      setIdePptxPreviewError(text)
      setIdePptxPreviewLoading(false)
    })
    return () => { cancelled = true }
  }, [ideActiveFile, resolveIdeAbsolutePath])

  if (!conversation) {
    return (
      <div className="chat-detail chat-detail-empty">
        <button
          type="button"
          className={`chat-conversation-list-toggle chat-conversation-list-toggle-empty ${conversationListCollapsed ? 'collapsed' : ''}`}
          onClick={onToggleConversationList}
          title={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
          aria-label={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
          aria-expanded={!conversationListCollapsed}
        >
          <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M12 4L7 10L12 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <p>请从左侧选择一个对话</p>
      </div>
    )
  }

  const defaultAssistantsForProfile = [
    { id: CONVERSATION_ID_ASSISTANT, name: '自动执行小助手', avatar: ASSISTANT_AVATAR },
    { id: 'skill_learning', name: '技能学习小助手', avatar: SKILL_LEARNING_AVATAR },
    { id: 'customer_service', name: '人工客服', avatar: CUSTOMER_SERVICE_AVATAR },
  ]
  const assistantsForProfile = [
    ...defaultAssistantsForProfile,
    ...getVisibleCustomAssistants().map((a) => ({ id: a.id, name: a.name, avatar: a.avatar, displayId: a.displayId })),
  ].filter((a) => a.id !== CONVERSATION_ID_IM_QQ && a.id !== CONVERSATION_ID_IM_WEIXIN)
  const friendsForProfile = profileFriends.filter((f) => f.status === 'accepted')
  const friendNicknamesForMessageLinks = friendsForProfile
    .map((friend) => String(friend.nickname || '').trim())
    .filter(Boolean)

  const openProfileSidebar = (target: ChatProfileTarget) => {
    if (!target) return
    setSessionSidebarOpen(false)
    setProfileSidebarTarget(target)
  }

  const handleOpenFriendProfileByNickname = (nickname: string) => {
    const name = String(nickname || '').trim()
    if (!name) return
    const matched = friendsForProfile.find((friend) => String(friend.nickname || '').trim() === name)
    if (!matched) {
      showInlineToastNotice(`未找到昵称为「${name}」的好友`)
      return
    }
    openProfileSidebar({ type: 'friend', data: matched })
  }

  const buildFriendTarget = (targetImei: string, fallbackName: string, fallbackAvatar?: string): ChatProfileTarget => {
    const matched = friendsForProfile.find((f) => f.imei === targetImei)
    if (matched) return { type: 'friend', data: matched }
    return {
      type: 'friend',
      data: {
        imei: targetImei,
        nickname: fallbackName,
        avatar: fallbackAvatar,
        status: 'accepted',
        addedAt: Date.now(),
      },
    }
  }

  const resolveGroupTarget = async (): Promise<ChatProfileTarget> => {
    if (conversation.id === CONVERSATION_ID_GROUP) {
      return {
        type: 'group',
        data: {
          group_id: CONVERSATION_ID_GROUP,
          name: '好友群',
          creator_imei: '',
          members: [],
          created_at: '',
          assistant_enabled: false,
        },
        isFriendsGroup: true,
      }
    }
    const groupId = normalizeGroupRawId(conversation.id)
    const cached = groupInfoCache[groupId]
    if (cached) return { type: 'group', data: cached }
    if (imei) {
      try {
        const groups = await getGroups(imei)
        const matched = groups.find((g) => normalizeGroupRawId(g.group_id) === groupId)
        if (matched) {
          setGroupInfoCache((prev) => ({ ...prev, [groupId]: matched }))
          return { type: 'group', data: matched }
        }
      } catch {
        // ignore and fallback to current conversation payload
      }
    }
    return {
      type: 'group',
      data: {
        group_id: groupId,
        name: conversation.name,
        creator_imei: '',
        members: conversation.members ?? [],
        created_at: '',
        assistant_enabled: (conversation.assistants?.length ?? 0) > 0,
        assistants: conversation.assistants?.map((a) => a.id),
        assistant_configs: conversation.assistantConfigs,
      },
    }
  }

  const handleHeaderAvatarClick = async () => {
    if (conversation.type === 'assistant') {
      openProfileSidebar({
        type: 'assistant',
        id: conversation.id,
        name: conversation.name,
        avatar: assistantAvatar,
        baseUrl: conversation.baseUrl,
        displayId: conversation.displayId,
      })
      return
    }
    if (conversation.type === 'friend') {
      const friendImei = conversation.id.replace(/^friend_/, '')
      openProfileSidebar(buildFriendTarget(friendImei, conversation.name, conversation.avatar))
      return
    }
    if (conversation.type === 'group') {
      const target = await resolveGroupTarget()
      openProfileSidebar(target)
      return
    }
    if (conversation.type === 'cross_device' && imei) {
      openProfileSidebar(buildFriendTarget(imei, userName || '我', userAvatar))
    }
  }

  const handleMessageAvatarClick = async (m: Message) => {
    if (m.sender === '我' || m.messageSource === 'my_clone' || (isImConversation && m.sender === '用户自己')) {
      if (!imei) return
      openProfileSidebar(buildFriendTarget(imei, userName || '我', userAvatar))
      return
    }
    if (conversation.type === 'group') {
      if (m.type === 'assistant') {
        const senderName = (m.sender || '').trim()
        const byConv = conversation.assistants?.find((a) => {
          if (a.name === senderName) return true
          const displayName = groupAssistantDisplayNameMap[a.id]
          if (displayName && displayName === senderName) return true
          // 兼容后端重名标记：Name(assistant_id)
          if (senderName.endsWith(`(${a.id})`)) return true
          return false
        })
        const resolvedId = byConv?.id ?? m.sender
        const resolvedConfig = resolveGroupAssistantConfig(resolvedId, m.sender, conversation.assistantConfigs)
        const groupCfg = conversation.assistantConfigs?.[resolvedId]
        const creatorImeiInGroup = String(groupCfg?.creator_imei || '').trim()
        const creatorNicknameInGroup = String(groupCfg?.creator_nickname || '').trim()
        const creatorLine =
          creatorImeiInGroup && creatorNicknameInGroup
            ? `${creatorImeiInGroup}(${creatorNicknameInGroup})`
            : (creatorImeiInGroup || creatorNicknameInGroup || '')
        openProfileSidebar({
          type: 'assistant',
          id: resolvedId,
          name: m.sender || '小助手',
          avatar: getGroupAssistantAvatar(m.sender),
          baseUrl: resolvedConfig?.baseUrl,
          creator_imei: creatorLine || undefined,
          displayId: resolvedConfig?.displayId,
          disableLocalCreatorFallback: true,
        })
        return
      }
      const senderImei = (m.senderImei || '').trim()
      const matchedFriend =
        (senderImei ? friendsForProfile.find((f) => f.imei === senderImei) : undefined) ||
        friendsForProfile.find((f) => f.imei === m.sender) ||
        friendsForProfile.find((f) => (f.nickname || '').trim() === (m.sender || '').trim())
      if (matchedFriend) {
        openProfileSidebar({ type: 'friend', data: matchedFriend })
        return
      }
      openProfileSidebar({
        type: 'friend',
        data: {
          imei: senderImei || m.sender || '',
          nickname: m.sender || '群成员',
          status: 'accepted',
          addedAt: Date.now(),
        },
      })
      return
    }
    if (conversation.type === 'assistant' || m.type === 'assistant') {
      openProfileSidebar({
        type: 'assistant',
        id: conversation.id,
        name: conversation.name,
        avatar: assistantAvatar,
        baseUrl: conversation.baseUrl,
        displayId: conversation.displayId,
      })
      return
    }
    if (conversation.type === 'friend') {
      const friendImei = conversation.id.replace(/^friend_/, '')
      openProfileSidebar(buildFriendTarget(friendImei, conversation.name, conversation.avatar))
      return
    }
    if (conversation.type === 'cross_device' && imei) {
      openProfileSidebar(buildFriendTarget(imei, userName || '我', userAvatar))
    }
  }

  const headerGroupAvatar = conversation.type === 'group'
    ? (
      conversation.id === CONVERSATION_ID_GROUP
        ? getFriendsGroupAvatarSources(userAvatar, userName)
        : getGroupAvatarSourcesFromMembers(conversation.members ?? [], friendsForProfile, imei ?? '', userAvatar, {
          assistants: conversation.assistants?.map((a) => a.id) ?? [],
          assistantConfigs: conversation.assistantConfigs,
          assistantNames: Object.fromEntries((conversation.assistants ?? []).map((a) => [a.id, a.name])),
        })
    )
    : null

  /** 仅支持多 session 的自定义小助手显示侧边栏按钮（新建时勾选「是否支持多session」或链接带 multiSession=1） */
  const showSessionSidebarButton = customAssistant && hasMultiSession(customAssistant)
  const showIdeModeButton = isTopoClawConversation

  const rightSidebarOpen = !ideModeEnabled && (sessionSidebarOpen || !!profileSidebarTarget)
  const ideActiveContent = ideActiveFile ? (ideFileContents[ideActiveFile] ?? '') : ''
  const ideActiveFileExt = ideActiveFile ? ideFileExt(ideActiveFile) : ''
  const ideActiveAbsPath = ideActiveFile ? resolveIdeAbsolutePath(ideActiveFile) : ''
  const ideActiveFileUrl = ideAbsPathToFileUrl(ideActiveAbsPath)
  const ideActiveMode: 'text' | 'image' | 'readonly-preview' = (() => {
    const ext = ideActiveFileExt
    if (!ext) return 'text'
    if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico'].includes(ext)) return 'image'
    if (isIdeReadonlyPreviewExt(ext)) return 'readonly-preview'
    return 'text'
  })()
  const ideVisibleTabs = !ideActiveFile
    ? ideOpenTabs
    : (ideOpenTabs.some((p) => p.toLowerCase() === ideActiveFile.toLowerCase()) ? ideOpenTabs : [...ideOpenTabs, ideActiveFile])
  const hasIdeExplorer = ideExplorerTree.length > 0
  const renderIdeTreeNodes = (nodes: IdeTreeNode[], depth = 0): React.ReactNode => (
    nodes.map((node) => {
      if (node.type === 'folder') {
        const expanded = ideExpandedFolders.has(node.path)
        return (
          <div key={node.path} className="chat-ide-tree-node">
            <button
              type="button"
              className="chat-ide-tree-folder"
              style={{ paddingLeft: `${depth * 7 + 8}px` }}
              onContextMenu={(event) => {
                event.preventDefault()
                event.stopPropagation()
                setIdeExplorerContextMenu({
                  x: event.clientX,
                  y: event.clientY,
                  path: node.path,
                  nodeType: 'folder',
                })
              }}
              onClick={() => {
                setIdeExpandedFolders((prev) => {
                  const next = new Set(prev)
                  if (next.has(node.path)) next.delete(node.path)
                  else next.add(node.path)
                  return next
                })
              }}
            >
              <span className={`chat-ide-tree-caret ${expanded ? 'expanded' : ''}`}>▸</span>
              <span className="chat-ide-tree-label">{node.name}</span>
            </button>
            {expanded && node.children && node.children.length > 0 ? renderIdeTreeNodes(node.children, depth + 1) : null}
          </div>
        )
      }
      const selected = ideActiveFile === node.path
      const badge = ideFileTypeBadge(node.path)
      return (
        <button
          key={node.path}
          type="button"
          className={`chat-ide-tree-file ${selected ? 'active' : ''}`}
          style={{ paddingLeft: `${depth * 7 + 28}px` }}
          title={node.path}
          onContextMenu={(event) => {
            event.preventDefault()
            event.stopPropagation()
            setIdeExplorerContextMenu({
              x: event.clientX,
              y: event.clientY,
              path: node.path,
              nodeType: 'file',
            })
          }}
          onClick={() => {
            setIdeActiveFile(node.path)
            setIdeOpenedEntries((prev) => {
              if (prev.some((v) => v.toLowerCase() === node.path.toLowerCase())) return prev
              return [node.path, ...prev]
            })
            setIdeOpenTabs((prev) => {
              if (prev.some((v) => v.toLowerCase() === node.path.toLowerCase())) return prev
              return [...prev, node.path]
            })
            setIdeSavedContents((prev) => {
              if (Object.prototype.hasOwnProperty.call(prev, node.path)) return prev
              return { ...prev, [node.path]: ideFileContents[node.path] ?? '' }
            })
            pushIdeRecentEntries([node.path])
          }}
        >
          <span className={`chat-ide-tree-file-type type-${badge.kind}`} aria-hidden>{badge.label}</span>
          <span className="chat-ide-tree-file-name">{node.name}</span>
        </button>
      )
    })
  )

  return (
    <div className={`chat-detail ${rightSidebarOpen ? 'chat-detail-with-sidebar' : ''} ${ideModeEnabled ? 'chat-detail-ide-mode' : ''}`}>
      <div
        className="chat-detail-left"
        style={ideModeEnabled ? { width: `${ideRightPaneWidth}px`, flex: `0 0 ${ideRightPaneWidth}px` } : undefined}
      >
      <div className="chat-header">
        {!ideModeEnabled && (
          <button
            type="button"
            className={`chat-conversation-list-toggle ${conversationListCollapsed ? 'collapsed' : ''}`}
            onClick={onToggleConversationList}
            title={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
            aria-label={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
            aria-expanded={!conversationListCollapsed}
          >
            <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path d="M12 4L7 10L12 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        )}
        <button
          type="button"
          className="chat-header-avatar-btn"
          onClick={() => void handleHeaderAvatarClick()}
          title="查看主页"
        >
          <span className={`chat-header-avatar ${conversation.type === 'group' ? 'chat-header-avatar-group' : ''}`}>
            {conversation.type === 'group' && headerGroupAvatar ? (
              <GroupAvatar avatars={headerGroupAvatar.avatars} placeholders={headerGroupAvatar.placeholders} size={32} />
            ) : (
              <MessageAvatar
                src={conversation.type === 'assistant' ? assistantAvatar : (conversation.type === 'cross_device' ? userAvatar : conversation.avatar)}
                fallback={conversation.name || '会话'}
              />
            )}
          </span>
        </button>
        <h2 className="chat-header-title">
          <span className="chat-header-title-main">
            {needCloudHistorySync ? (
              <button
                type="button"
                className="chat-header-title-sync"
                onClick={() => void refreshHistoryFromCloud()}
                title="点击从云端同步历史消息"
              >
                {conversation.name}
              </button>
            ) : (
              conversation.name
            )}
          </span>
          {isCurrentFriendOnline && (
            <span className="chat-header-title-lamp" title="在线" aria-label="在线">
              💡
            </span>
          )}
        </h2>
        <span className="conversation-type">
          {conversation.type === 'assistant' ? '小助手' : conversation.type === 'group' ? '群聊' : conversation.type === 'cross_device' ? '端云互发' : '单聊'}
        </span>
        {builtinChatSlot === 'topoclaw' && (
          <button
            type="button"
            className={`chat-header-status-check-btn ${isMobileReadyForGuiTask ? 'is-online' : 'is-offline'}`}
            onClick={() => { void handleRunMobileStatusCheck() }}
            disabled={mobileStatusChecking}
            title={
              mobileStatusChecking
                ? '状态检测中...'
                : (isMobileReadyForGuiTask
                  ? '手机在线，且可接收 gui_task 任务'
                  : '手机未就绪（点击检测手机在线与 gui_task 接收能力）')
            }
            aria-label="状态检测"
          >
            <span className="chat-header-status-lamp" aria-hidden="true">💡</span>
          </button>
        )}
        {isFriend && (
          <button
            type="button"
            className={`chat-header-clone-toggle ${digitalCloneCurrentFriendEnabled ? 'on' : 'off'}`}
            onClick={() => void handleToggleDigitalCloneForCurrentFriend()}
            disabled={digitalCloneSaving}
            title={`仅作用于当前好友；全局默认：${digitalCloneGlobalEnabled ? '开' : '关'}`}
          >
            <span className="chat-header-clone-left">
              <span className="chat-header-clone-icon" aria-hidden>◇</span>
              <span className="chat-header-clone-text">数字分身</span>
            </span>
            <span className={`chat-header-clone-switch ${digitalCloneCurrentFriendEnabled ? 'on' : 'off'}`}>
              {digitalCloneSaving
                ? '...'
                : (digitalCloneCurrentFriendEnabled ? '开' : '关')}
            </span>
            {!digitalCloneCurrentFriendOverride && !digitalCloneSaving && (
              <span className="chat-header-clone-follow">跟随全局</span>
            )}
          </button>
        )}
        {isImConversation && (
          <button
            type="button"
            className={`chat-header-im-toggle ${imConnected ? 'connected' : 'disconnected'}`}
            onClick={() => void handleToggleImConnection()}
            disabled={imSwitching}
            title={imConnected ? '点击断开连接' : '点击连接'}
          >
            {imSwitching ? '切换中…' : imConnected ? '已连接（点击断开）' : '已断开（点击连接）'}
          </button>
        )}
        {isImConversation && imConnectionHint && (
          <span className="chat-header-im-hint">{imConnectionHint}</span>
        )}
        {showIdeModeButton && (
          <button
            type="button"
            className={`chat-header-ide-btn ${ideModeEnabled ? 'active' : ''}`}
            onClick={() => {
              const nextEnabled = !ideModeEnabled
              setProfileSidebarTarget(null)
              setSessionSidebarOpen(false)
              setIdeFileMenuOpen(false)
              setIdeTerminalMenuOpen(false)
              if (!nextEnabled) setIdeRecentPanelOpen(false)
              if (nextEnabled) setIdeTerminalVisible(false)
              if (nextEnabled) setIdeTerminalCollapsed(true)
              if (nextEnabled && !conversationListCollapsed) onToggleConversationList?.()
              if (!nextEnabled && conversationListCollapsed) onToggleConversationList?.()
              setIdeModeEnabled(nextEnabled)
            }}
            title={ideModeEnabled ? '退出 IDE 模式' : '打开 IDE 模式'}
          >
            <span className="chat-header-ide-icon" aria-hidden>
              <svg viewBox="0 0 24 24" fill="none">
                <path d="M8 8L4 12L8 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                <path d="M16 8L20 12L16 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                <path d="M13.5 5L10.5 19" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </span>
            <span className="chat-header-ide-label">Code</span>
          </button>
        )}
        {conversation.type === 'group' && (
          <div className="chat-header-workflow-menu" ref={workflowHeaderMenuRef}>
            <button
              type="button"
              className="chat-header-workflow-btn"
              onClick={() => setWorkflowHeaderMenuOpen((prev) => !prev)}
              title="任务编排"
              aria-haspopup="menu"
              aria-expanded={workflowHeaderMenuOpen}
            >
              <span className="chat-header-workflow-icon" aria-hidden>
                <svg viewBox="0 0 24 24" fill="none">
                  <path d="M8 12H15.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                  <path d="M15.5 12L18 8" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                  <path d="M15.5 12L18 16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                  <circle cx="6.5" cy="12" r="2.2" stroke="currentColor" strokeWidth="1.6" />
                  <circle cx="19.5" cy="8" r="1.9" stroke="currentColor" strokeWidth="1.6" />
                  <circle cx="19.5" cy="16" r="1.9" stroke="currentColor" strokeWidth="1.6" />
                </svg>
              </span>
              编排
            </button>
            {workflowHeaderMenuOpen && (
              <div className="chat-header-workflow-dropdown" role="menu" aria-label="编排菜单">
                <button
                  type="button"
                  className="chat-header-workflow-option"
                  role="menuitem"
                  onClick={() => void handleWorkflowExecuteFromHeader()}
                >
                  执行
                </button>
                <button
                  type="button"
                  className="chat-header-workflow-option"
                  role="menuitem"
                  onClick={handleWorkflowComposeFromHeader}
                >
                  打开编排页
                </button>
              </div>
            )}
          </div>
        )}
        {showSessionSidebarButton && (
          <button
            type="button"
            className={`chat-header-sidebar-btn ${sessionSidebarOpen ? 'active' : ''}`}
            disabled={ideModeEnabled}
            onClick={() => {
              setProfileSidebarTarget(null)
              setSessionSidebarOpen((v) => !v)
            }}
            title={ideModeEnabled ? 'IDE 模式下不可用' : (sessionSidebarOpen ? '关闭侧边栏' : '打开会话侧边栏')}
          >
            <span className="chat-header-sidebar-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                <rect x="1" y="5" width="22" height="14" rx="3" />
                <line x1="6.5" y1="5.5" x2="6.5" y2="18.5" />
              </svg>
            </span>
          </button>
        )}
      </div>
      <div className="chat-detail-body">
        <div className="chat-detail-main">
        <div ref={messagesContainerRef} className="chat-messages" onScroll={handleScroll}>
        {displayItems.map((item) => {
          if (item.type === 'assistant_with_thinking') {
            const thinkingId = `thinking-${item.id}`
            const isLastItem = item === displayItems[displayItems.length - 1]
            const isStreaming = loading && isLastItem
            const isThinkingCompleted = !isStreaming
            const isExpanded = expandedSystemIds.has(thinkingId) || isStreaming
            const ans = item.answerMessage
            const thinkingDetailContents = normalizeThinkingDetailLines(item.thinkingContents)
            const answerContent = ans.content || ''
            const normalizedAnswerContent = stripNeedExecutionGuide(answerContent)
            const answerHasInlineMediaMarkers = extractAssistantMediaMarkerIndices(normalizedAnswerContent).length > 0
            const ansFiles =
              ans.fileList && ans.fileList.length > 0
                ? ans.fileList
                : (ans.fileBase64 ? [{ fileBase64: ans.fileBase64, fileName: ans.fileName }] : [])
            const ansAllImages =
              ansFiles.length > 0 &&
              ansFiles.every((f) => f.fileName?.match(/\.(png|jpe?g|gif|webp)$/i) || f.fileName === '图片.png')
            const isSelected = selectedMessageIds.has(ans.id)
            const showSkillActions = !!(ans.generatedSkill && !ans.generatedSkillResolved && !(loading && isLastItem))
            const showAnswerShell = (ans.messageType === 'file' && ansFiles.length > 0) || !!answerContent || showSkillActions
            const displayName = ans.sender || conversation?.name || '小助手'
            const thinkingAvatar = isGroup ? getGroupAssistantAvatar(displayName) : assistantAvatar
            return (
              <div key={item.id} className="message message-assistant message-assistant-with-thinking" data-export-message-id={ans.id}>
                <button
                  type="button"
                  className="message-avatar message-avatar-button"
                  onClick={() => void handleMessageAvatarClick(ans)}
                  title="查看主页"
                >
                  <MessageAvatar src={thinkingAvatar} fallback={displayName.slice(0, 1)} />
                </button>
                <div className="message-body">
                  {messageMultiSelectMode && (
                    <button
                      type="button"
                      className={`message-multi-check ${isSelected ? 'selected' : ''}`}
                      onClick={() => toggleSelectedMessage(ans.id)}
                      aria-label={isSelected ? '取消选中' : '选中消息'}
                    >
                      {isSelected ? '✓' : ''}
                    </button>
                  )}
                  <div className="message-sender-row">
                    <span className="message-sender">{displayName}</span>
                    <span className={`message-time-inline ${hoveredMessageId === ans.id ? 'visible' : ''}`}>
                      {formatQuoteTimestamp(ans.timestamp)}
                    </span>
                  </div>
                  <button
                    type="button"
                    className="message-system-toggle"
                    onClick={() => setExpandedSystemIds((prev) => {
                      const next = new Set(prev)
                      if (next.has(thinkingId)) next.delete(thinkingId)
                      else next.add(thinkingId)
                      return next
                    })}
                  >
                    {isThinkingCompleted ? (
                      <svg className="message-system-icon message-system-icon-success" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="9" />
                        <path d="m8.5 12 2.3 2.3L15.5 9.7" />
                      </svg>
                    ) : (
                      <svg className="message-system-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="3" />
                        <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
                      </svg>
                    )}
                    <span className="message-system-label">{isThinkingCompleted ? '思考完成' : '正在思考'}</span>
                    <svg className={`message-system-chevron ${isExpanded ? 'expanded' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M6 9l6 6 6-6" />
                    </svg>
                  </button>
                  {isExpanded && (
                    <div className="message-system-detail">
                      {thinkingDetailContents.map((c, i) => (
                        <div key={i} className="message-system-detail-item">{c}</div>
                      ))}
                    </div>
                  )}
                  {showAnswerShell && (
                    <div
                      className="message-content-shell"
                      onMouseEnter={() => setHoveredMessageId(ans.id)}
                      onMouseLeave={() => setHoveredMessageId((prev) => (prev === ans.id ? null : prev))}
                    >
                      <div
                        className={`message-content message-content-markdown message-content-selectable${isSelected ? ' is-selected' : ''}`}
                        onContextMenu={(e) => handleMessageContextMenu(e, ans.id)}
                        onClick={(e) => {
                          if (!messageMultiSelectMode) return
                          e.preventDefault()
                          e.stopPropagation()
                          toggleSelectedMessage(ans.id)
                        }}
                      >
                        {ans.messageType === 'file' && ansFiles.length > 0 ? (
                          <>
                            {answerHasInlineMediaMarkers ? (
                              (() => {
                                const nodes: React.ReactNode[] = []
                                const renderedMediaIndexes = new Set<number>()
                                const re = /\[\[__TC_MEDIA_(\d+)__\]\]/g
                                let last = 0
                                let textChunkId = 0
                                let mMatch: RegExpExecArray | null
                                while ((mMatch = re.exec(normalizedAnswerContent)) !== null) {
                                  const textPart = normalizedAnswerContent.slice(last, mMatch.index)
                                  if (textPart.trim() && textPart.trim() !== '[图片]') {
                                    nodes.push(
                                      <MessageMarkdown
                                        key={`${ans.id}_inline_text_${textChunkId++}`}
                                        messageId={ans.id}
                                        content={textPart}
                                        friendNicknames={friendNicknamesForMessageLinks}
                                        isLastItem={isLastItem}
                                        autoExecuteCode={autoExecuteCode}
                                        executedIdsRef={executedIdsRef}
                                        onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, ans.id)}
                                        onRevealLocalFileToken={handleRevealLocalFileToken}
                                        onOpenScheduledJob={openScheduledJobEditor}
                                        onOpenExternalUrl={handleOpenExternalUrl}
                                        onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                        onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, ans.sender, ans.timestamp)}
                                      />
                                    )
                                  }
                                  const mediaIdx = Number(mMatch[1])
                                  if (Number.isFinite(mediaIdx) && mediaIdx >= 0 && mediaIdx < ansFiles.length) {
                                    renderedMediaIndexes.add(mediaIdx)
                                    const f = ansFiles[mediaIdx]
                                    if (ansAllImages) {
                                      nodes.push(
                                        <ChatInlineImage
                                          key={`${ans.id}_${mediaIdx}`}
                                          dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                          fileName={f.fileName || '图片.png'}
                                          className="message-file-img"
                                          onOpenLightbox={(payload) => openConversationImageLightbox(`${ans.id}_${mediaIdx}`, payload)}
                                          onAddToQuickNote={() => void saveMessageToQuickNote(ans)}
                                        />
                                      )
                                    } else {
                                      nodes.push(
                                        <a
                                          key={`${ans.id}_${mediaIdx}`}
                                          href={toChatImageSrc(f.fileBase64, f.fileName) || `data:application/octet-stream;base64,${f.fileBase64}`}
                                          download={f.fileName || 'file'}
                                          className="message-file-link"
                                          onClick={(e) => {
                                            if (window.electronAPI?.saveChatFileToWorkspace && window.electronAPI?.showItemInFolder) {
                                              e.preventDefault()
                                              e.stopPropagation()
                                              if (!ideModeEnabled) {
                                                setFileLinkActionTarget({
                                                  kind: 'chat_file',
                                                  fileBase64: f.fileBase64,
                                                  fileName: f.fileName || '文件',
                                                })
                                                return
                                              }
                                              void handleOpenFileFolder(f.fileBase64, f.fileName)
                                            }
                                          }}
                                        >
                                          {f.fileName || '文件'}
                                        </a>
                                      )
                                    }
                                  }
                                  last = mMatch.index + mMatch[0].length
                                }
                                const tailText = normalizedAnswerContent.slice(last)
                                if (tailText.trim() && tailText.trim() !== '[图片]') {
                                  nodes.push(
                                    <MessageMarkdown
                                      key={`${ans.id}_inline_text_${textChunkId++}`}
                                      messageId={ans.id}
                                      content={tailText}
                                      friendNicknames={friendNicknamesForMessageLinks}
                                      isLastItem={isLastItem}
                                      autoExecuteCode={autoExecuteCode}
                                      executedIdsRef={executedIdsRef}
                                      onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, ans.id)}
                                      onRevealLocalFileToken={handleRevealLocalFileToken}
                                      onOpenScheduledJob={openScheduledJobEditor}
                                      onOpenExternalUrl={handleOpenExternalUrl}
                                      onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                      onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, ans.sender, ans.timestamp)}
                                    />
                                  )
                                }
                                ansFiles.forEach((f, idx) => {
                                  if (renderedMediaIndexes.has(idx)) return
                                  if (ansAllImages) {
                                    nodes.push(
                                      <ChatInlineImage
                                        key={`${ans.id}_${idx}`}
                                        dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                        fileName={f.fileName || '图片.png'}
                                        className="message-file-img"
                                        onOpenLightbox={(payload) => openConversationImageLightbox(`${ans.id}_${idx}`, payload)}
                                        onAddToQuickNote={() => void saveMessageToQuickNote(ans)}
                                      />
                                    )
                                  }
                                })
                                return nodes
                              })()
                            ) : (
                              <>
                                {normalizedAnswerContent && normalizedAnswerContent !== '[图片]' && (
                                  <MessageMarkdown
                                    messageId={ans.id}
                                    content={normalizedAnswerContent}
                                    friendNicknames={friendNicknamesForMessageLinks}
                                    isLastItem={isLastItem}
                                    autoExecuteCode={autoExecuteCode}
                                    executedIdsRef={executedIdsRef}
                                    onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, ans.id)}
                                    onRevealLocalFileToken={handleRevealLocalFileToken}
                                    onOpenScheduledJob={openScheduledJobEditor}
                                    onOpenExternalUrl={handleOpenExternalUrl}
                                    onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                    onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, ans.sender, ans.timestamp)}
                                  />
                                )}
                                {ansAllImages ? (
                                  ansFiles.map((f, idx) => (
                                    <ChatInlineImage
                                      key={`${ans.id}_${idx}`}
                                      dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                      fileName={f.fileName || '图片.png'}
                                      className="message-file-img"
                                      onOpenLightbox={(payload) => openConversationImageLightbox(`${ans.id}_${idx}`, payload)}
                                      onAddToQuickNote={() => void saveMessageToQuickNote(ans)}
                                    />
                                  ))
                                ) : (
                                  <a
                                    href={toChatImageSrc(ansFiles[0].fileBase64, ansFiles[0].fileName) || `data:application/octet-stream;base64,${ansFiles[0].fileBase64}`}
                                    download={ansFiles[0].fileName || 'file'}
                                    className="message-file-link"
                                    onClick={(e) => {
                                      if (window.electronAPI?.saveChatFileToWorkspace && window.electronAPI?.showItemInFolder) {
                                        e.preventDefault()
                                        e.stopPropagation()
                                        if (!ideModeEnabled) {
                                          setFileLinkActionTarget({
                                            kind: 'chat_file',
                                            fileBase64: ansFiles[0].fileBase64,
                                            fileName: ansFiles[0].fileName || '文件',
                                          })
                                          return
                                        }
                                        void handleOpenFileFolder(ansFiles[0].fileBase64, ansFiles[0].fileName)
                                      }
                                    }}
                                  >
                                    {ansFiles[0].fileName || '文件'}
                                  </a>
                                )}
                              </>
                            )}
                          </>
                        ) : (
                          answerContent ? (
                            <MessageMarkdown
                              messageId={ans.id}
                              content={answerContent}
                              friendNicknames={friendNicknamesForMessageLinks}
                              isLastItem={isLastItem}
                              autoExecuteCode={autoExecuteCode}
                              executedIdsRef={executedIdsRef}
                              onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, ans.id)}
                              onRevealLocalFileToken={handleRevealLocalFileToken}
                              onOpenScheduledJob={openScheduledJobEditor}
                              onOpenExternalUrl={handleOpenExternalUrl}
                              onOpenFriendProfile={handleOpenFriendProfileByNickname}
                              onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, ans.sender, ans.timestamp)}
                            />
                          ) : null
                        )}
                        {showSkillActions && (
                          <div className="message-skill-actions">
                            <button type="button" className="message-skill-btn message-skill-btn-cancel" onClick={() => handleSkillAction(ans.id, ans.generatedSkill!, 'cancel')}>
                              取消
                            </button>
                            <button type="button" className="message-skill-btn message-skill-btn-add" onClick={() => handleSkillAction(ans.id, ans.generatedSkill!, 'add')}>
                              加入我的技能
                            </button>
                          </div>
                        )}
                      </div>
                      {!messageMultiSelectMode && (
                        <button
                          type="button"
                          className={`message-menu-trigger ${hoveredMessageId === ans.id ? 'visible' : ''}`}
                          aria-label="更多操作"
                          title="更多操作"
                          onClick={(e) => handleMessageMenuTriggerClick(e, ans.id)}
                        >
                          ...
                        </button>
                      )}
                    </div>
                  )}
                </div>
              </div>
            )
          }
          if (item.type === 'system_plain' && item.contents) {
            return (
              <div key={item.id} className="message message-assistant message-system message-system-plain">
                <button
                  type="button"
                  className="message-avatar message-avatar-button"
                  onClick={() => void handleHeaderAvatarClick()}
                  title="查看主页"
                >
                  <MessageAvatar src={assistantAvatar} fallback="助" />
                </button>
                <div className="message-body">
                  <span className="message-sender">小助手</span>
                  <div className="message-system-detail">
                    {item.contents.map((c, i) => (
                      <div key={i} className="message-system-detail-item">{c}</div>
                    ))}
                  </div>
                </div>
              </div>
            )
          }
          if (item.type === 'system_group' && item.contents) {
            const isExpanded = expandedSystemIds.has(item.id)
            return (
              <div key={item.id} className="message message-assistant message-system">
                <button
                  type="button"
                  className="message-avatar message-avatar-button"
                  onClick={() => void handleHeaderAvatarClick()}
                  title="查看主页"
                >
                  <MessageAvatar src={assistantAvatar} fallback="助" />
                </button>
                <div className="message-body">
                  <span className="message-sender">小助手</span>
                  <button
                    type="button"
                    className="message-system-toggle"
                    onClick={() => setExpandedSystemIds((prev) => {
                      const next = new Set(prev)
                      if (next.has(item.id)) next.delete(item.id)
                      else next.add(item.id)
                      return next
                    })}
                  >
                    <svg className="message-system-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <circle cx="12" cy="12" r="3" />
                      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
                    </svg>
                    <span className="message-system-label">正在思考</span>
                    <svg className={`message-system-chevron ${isExpanded ? 'expanded' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M6 9l6 6 6-6" />
                    </svg>
                  </button>
                  {isExpanded && (
                    <div className="message-system-detail">
                      {item.contents.map((c, i) => (
                        <div key={i} className="message-system-detail-item">{c}</div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )
          }
          if (item.type !== 'user' && item.type !== 'assistant') return null
          const m = item.message
          const mFiles =
            m.fileList && m.fileList.length > 0
              ? m.fileList
              : (m.fileBase64 ? [{ fileBase64: m.fileBase64, fileName: m.fileName }] : [])
          const mAllImages =
            mFiles.length > 0 &&
            mFiles.every((f) => f.fileName?.match(/\.(png|jpe?g|gif|webp)$/i) || f.fileName === '图片.png')
          const isMyCloneMessage = m.messageSource === 'my_clone'
          const isFromMe = m.sender === '我' || isMyCloneMessage || (isImConversation && m.sender === '用户自己')
          const isFromPhone = m.sender === '我的手机'
          const sharedAssistant = parseAssistantShareCardContent(m.content || '')
          const sharedSkill = parseSkillShareCardContent(m.content || '')
          const isSelected = selectedMessageIds.has(m.id)
          const isLastItem = item === displayItems[displayItems.length - 1]
          const showThinkingAbove = !suppressThinkingChrome && !isFromMe && loading && isLastItem && !m.content
          const normalizedMessageContent = isFromMe ? (m.content || '') : stripNeedExecutionGuide(m.content || '')
          const messageHasInlineMediaMarkers = extractAssistantMediaMarkerIndices(normalizedMessageContent).length > 0
          const avatarSrc = isFromMe
            ? userAvatar
            : isFromPhone
              ? ME_AVATAR
              : isGroup
                ? m.type === 'assistant'
                  ? getGroupAssistantAvatar(m.sender)
                  : getGroupMemberAvatar(m.sender, m.senderImei)
                : conversation?.type === 'assistant'
                  ? assistantAvatar
                  : toAvatarSrc(conversation?.avatar)
          const avatarFallback = isFromMe
            ? userName || '我'
            : isFromPhone
              ? '手'
              : isGroup
                ? m.sender?.slice(0, 1) || (m.type === 'assistant' ? '助' : '友')
                : conversation?.name?.slice(0, 1) || '友'
          const senderDisplayName = isMyCloneMessage ? '我（数字分身）' : m.sender
          return (
            <div key={m.id} className={`message ${isFromMe ? 'message-user' : 'message-assistant'}${isFromPhone ? ' message-from-phone' : ''}`} data-export-message-id={m.id}>
              {!isFromMe && (
                <button
                  type="button"
                  className="message-avatar message-avatar-button"
                  onClick={() => void handleMessageAvatarClick(m)}
                  title="查看主页"
                >
                  <MessageAvatar src={avatarSrc} fallback={avatarFallback} />
                </button>
              )}
              {isFromMe && (
                <button
                  type="button"
                  className="message-avatar message-avatar-button"
                  onClick={() => void handleMessageAvatarClick(m)}
                  title="查看主页"
                >
                  <MessageAvatar src={userAvatar} fallback={userName || '我'} />
                </button>
              )}
              <div className="message-body">
                {messageMultiSelectMode && (
                  <button
                    type="button"
                    className={`message-multi-check ${isSelected ? 'selected' : ''}`}
                    onClick={() => toggleSelectedMessage(m.id)}
                    aria-label={isSelected ? '取消选中' : '选中消息'}
                  >
                    {isSelected ? '✓' : ''}
                  </button>
                )}
                <div className="message-sender-row">
                  <span className="message-sender">{senderDisplayName}</span>
                  <span className={`message-time-inline ${hoveredMessageId === m.id ? 'visible' : ''}`}>
                    {formatQuoteTimestamp(m.timestamp)}
                  </span>
                </div>
                {showThinkingAbove && (
                  <div className="message-thinking-above">正在思考...</div>
                )}
                <div
                  className="message-content-shell"
                  onMouseEnter={() => setHoveredMessageId(m.id)}
                  onMouseLeave={() => setHoveredMessageId((prev) => (prev === m.id ? null : prev))}
                >
                  <div
                    className={`message-content message-content-markdown message-content-selectable${isSelected ? ' is-selected' : ''}`}
                    onContextMenu={(e) => handleMessageContextMenu(e, m.id)}
                    onClick={(e) => {
                      if (!messageMultiSelectMode) return
                      e.preventDefault()
                      e.stopPropagation()
                      toggleSelectedMessage(m.id)
                    }}
                  >
                    {sharedAssistant ? (
                      <div className="chat-assistant-share-card">
                        <div className="chat-assistant-share-card-head">助手分享</div>
                        <div className="chat-assistant-share-card-title">{sharedAssistant.name}</div>
                        {sharedAssistant.intro && (
                          <div className="chat-assistant-share-card-intro">{sharedAssistant.intro}</div>
                        )}
                        <div className="chat-assistant-share-card-actions">
                          <button
                            type="button"
                            className="chat-assistant-share-card-btn"
                            onClick={() => setShareCardPreview(sharedAssistant)}
                          >
                            查看主页
                          </button>
                          <button
                            type="button"
                            className="chat-assistant-share-card-btn chat-assistant-share-card-btn-primary"
                            disabled={addingShareAssistant}
                            onClick={() => { void handleAddAssistantFromShareCard(sharedAssistant) }}
                          >
                            {addingShareAssistant ? '添加中...' : '添加到我的助手'}
                          </button>
                        </div>
                      </div>
                    ) : sharedSkill ? (
                      <div className="chat-assistant-share-card">
                        <div className="chat-assistant-share-card-head">技能分享</div>
                        <div className="chat-assistant-share-card-title">{sharedSkill.title}</div>
                        {sharedSkill.originalPurpose && (
                          <div className="chat-assistant-share-card-intro">{sharedSkill.originalPurpose}</div>
                        )}
                        <div className="chat-assistant-share-card-actions">
                          <button
                            type="button"
                            className="chat-assistant-share-card-btn"
                            onClick={() => setShareSkillPreview(sharedSkill)}
                          >
                            {sharedSkill.packageBase64 ? '查看技能包' : '查看卡片'}
                          </button>
                          <button
                            type="button"
                            className="chat-assistant-share-card-btn chat-assistant-share-card-btn-primary"
                            disabled={addingShareSkill}
                            onClick={() => { void handleAddSkillFromShareCard(sharedSkill) }}
                          >
                            {addingShareSkill ? '添加中...' : '添加到我的技能'}
                          </button>
                        </div>
                      </div>
                    ) : m.messageType === 'file' && mFiles.length === 0 ? (
                      <div className="message-text-block">{m.content?.trim() || '[图片数据未加载，请重新进入会话或稍后重试]'}</div>
                    ) : m.messageType === 'file' && mFiles.length > 0 ? (
                      <>
                        {messageHasInlineMediaMarkers ? (
                          (() => {
                            const nodes: React.ReactNode[] = []
                            const renderedMediaIndexes = new Set<number>()
                            const re = /\[\[__TC_MEDIA_(\d+)__\]\]/g
                            let last = 0
                            let textChunkId = 0
                            let mMatch: RegExpExecArray | null
                            while ((mMatch = re.exec(normalizedMessageContent)) !== null) {
                              const textPart = normalizedMessageContent.slice(last, mMatch.index)
                              if (textPart.trim() && textPart.trim() !== '[图片]') {
                                nodes.push(
                                  <MessageMarkdown
                                    key={`${m.id}_inline_text_${textChunkId++}`}
                                    messageId={m.id}
                                    content={textPart}
                                    friendNicknames={friendNicknamesForMessageLinks}
                                    isLastItem={isLastItem}
                                    autoExecuteCode={isFromMe ? false : autoExecuteCode}
                                    executedIdsRef={executedIdsRef}
                                    onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, m.id)}
                                    onRevealLocalFileToken={handleRevealLocalFileToken}
                                    onOpenScheduledJob={openScheduledJobEditor}
                                    onOpenExternalUrl={handleOpenExternalUrl}
                                    onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                    onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, m.sender, m.timestamp)}
                                  />
                                )
                              }
                              const mediaIdx = Number(mMatch[1])
                              if (Number.isFinite(mediaIdx) && mediaIdx >= 0 && mediaIdx < mFiles.length) {
                                renderedMediaIndexes.add(mediaIdx)
                                const f = mFiles[mediaIdx]
                                if (mAllImages) {
                                  nodes.push(
                                    <ChatInlineImage
                                      key={`${m.id}_${mediaIdx}`}
                                      dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                      fileName={f.fileName || '图片.png'}
                                      className="message-file-img"
                                      onOpenLightbox={(payload) => openConversationImageLightbox(`${m.id}_${mediaIdx}`, payload)}
                                      onAddToQuickNote={() => void saveMessageToQuickNote(m)}
                                    />
                                  )
                                } else {
                                  nodes.push(
                                    <a
                                      key={`${m.id}_${mediaIdx}`}
                                      href={toChatImageSrc(f.fileBase64, f.fileName) || `data:application/octet-stream;base64,${f.fileBase64}`}
                                      download={f.fileName || 'file'}
                                      className="message-file-link"
                                      onClick={(e) => {
                                        if (window.electronAPI?.saveChatFileToWorkspace && window.electronAPI?.showItemInFolder) {
                                          e.preventDefault()
                                          e.stopPropagation()
                                          if (!ideModeEnabled) {
                                            setFileLinkActionTarget({
                                              kind: 'chat_file',
                                              fileBase64: f.fileBase64,
                                              fileName: f.fileName || '文件',
                                            })
                                            return
                                          }
                                          void handleOpenFileFolder(f.fileBase64, f.fileName)
                                        }
                                      }}
                                    >
                                      {f.fileName || '文件'}
                                    </a>
                                  )
                                }
                              }
                              last = mMatch.index + mMatch[0].length
                            }
                            const tailText = normalizedMessageContent.slice(last)
                            if (tailText.trim() && tailText.trim() !== '[图片]') {
                              nodes.push(
                                <MessageMarkdown
                                  key={`${m.id}_inline_text_${textChunkId++}`}
                                  messageId={m.id}
                                  content={tailText}
                                  friendNicknames={friendNicknamesForMessageLinks}
                                  isLastItem={isLastItem}
                                  autoExecuteCode={isFromMe ? false : autoExecuteCode}
                                  executedIdsRef={executedIdsRef}
                                  onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, m.id)}
                                  onRevealLocalFileToken={handleRevealLocalFileToken}
                                  onOpenScheduledJob={openScheduledJobEditor}
                                  onOpenExternalUrl={handleOpenExternalUrl}
                                  onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                  onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, m.sender, m.timestamp)}
                                />
                              )
                            }
                            mFiles.forEach((f, idx) => {
                              if (renderedMediaIndexes.has(idx)) return
                              if (mAllImages) {
                                nodes.push(
                                  <ChatInlineImage
                                    key={`${m.id}_${idx}`}
                                    dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                    fileName={f.fileName || '图片.png'}
                                    className="message-file-img"
                                    onOpenLightbox={(payload) => openConversationImageLightbox(`${m.id}_${idx}`, payload)}
                                    onAddToQuickNote={() => void saveMessageToQuickNote(m)}
                                  />
                                )
                              }
                            })
                            return nodes
                          })()
                        ) : (
                          <>
                            {normalizedMessageContent && normalizedMessageContent !== '[图片]' && (
                              <MessageMarkdown
                                messageId={m.id}
                                content={normalizedMessageContent}
                                friendNicknames={friendNicknamesForMessageLinks}
                                isLastItem={isLastItem}
                                autoExecuteCode={isFromMe ? false : autoExecuteCode}
                                executedIdsRef={executedIdsRef}
                                onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, m.id)}
                                onRevealLocalFileToken={handleRevealLocalFileToken}
                                onOpenScheduledJob={openScheduledJobEditor}
                                onOpenExternalUrl={handleOpenExternalUrl}
                                onOpenFriendProfile={handleOpenFriendProfileByNickname}
                                onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, m.sender, m.timestamp)}
                              />
                            )}
                            {mAllImages ? (
                              mFiles.map((f, idx) => (
                                <ChatInlineImage
                                  key={`${m.id}_${idx}`}
                                  dataUrl={toChatImageSrc(f.fileBase64, f.fileName)}
                                  fileName={f.fileName || '图片.png'}
                                  className="message-file-img"
                                  onOpenLightbox={(payload) => openConversationImageLightbox(`${m.id}_${idx}`, payload)}
                                  onAddToQuickNote={() => void saveMessageToQuickNote(m)}
                                />
                              ))
                            ) : (
                              <a
                                href={toChatImageSrc(mFiles[0].fileBase64, mFiles[0].fileName) || `data:application/octet-stream;base64,${mFiles[0].fileBase64}`}
                                download={mFiles[0].fileName || 'file'}
                                className="message-file-link"
                                onClick={(e) => {
                                  if (window.electronAPI?.saveChatFileToWorkspace && window.electronAPI?.showItemInFolder) {
                                    e.preventDefault()
                                    e.stopPropagation()
                                    if (!ideModeEnabled) {
                                      setFileLinkActionTarget({
                                        kind: 'chat_file',
                                        fileBase64: mFiles[0].fileBase64,
                                        fileName: mFiles[0].fileName || '文件',
                                      })
                                      return
                                    }
                                    void handleOpenFileFolder(mFiles[0].fileBase64, mFiles[0].fileName)
                                  }
                                }}
                              >
                                {mFiles[0].fileName || '文件'}
                              </a>
                            )}
                          </>
                        )}
                      </>
                    ) : (
                      <MessageMarkdown
                        messageId={m.id}
                        content={isFromMe ? (m.content || '') : stripNeedExecutionGuide(m.content || '')}
                        friendNicknames={friendNicknamesForMessageLinks}
                        isLastItem={isLastItem}
                        autoExecuteCode={isFromMe ? false : autoExecuteCode}
                        executedIdsRef={executedIdsRef}
                        onRequestTableMenu={(payload) => handleMessageTableMenuRequest(payload, m.id)}
                        onRevealLocalFileToken={handleRevealLocalFileToken}
                        onOpenScheduledJob={openScheduledJobEditor}
                        onOpenExternalUrl={handleOpenExternalUrl}
                        onOpenFriendProfile={handleOpenFriendProfileByNickname}
                        onSaveTextBlockToQuickNote={(text) => saveTextBlockToQuickNote(text, m.sender, m.timestamp)}
                      />
                    )}
                    {!isFromMe && m.generatedSkill && !m.generatedSkillResolved && !(loading && isLastItem) && (
                      <div className="message-skill-actions">
                        <button type="button" className="message-skill-btn message-skill-btn-cancel" onClick={() => handleSkillAction(m.id, m.generatedSkill!, 'cancel')}>
                          取消
                        </button>
                        <button type="button" className="message-skill-btn message-skill-btn-add" onClick={() => handleSkillAction(m.id, m.generatedSkill!, 'add')}>
                          加入我的技能
                        </button>
                      </div>
                    )}
                  </div>
                  {!messageMultiSelectMode && (
                    <button
                      type="button"
                      className={`message-menu-trigger ${hoveredMessageId === m.id ? 'visible' : ''}`}
                      aria-label="更多操作"
                      title="更多操作"
                      onClick={(e) => handleMessageMenuTriggerClick(e, m.id)}
                    >
                      ...
                    </button>
                  )}
                </div>
              </div>
            </div>
          )
        })}
        {!suppressThinkingChrome && loading && (() => {
          if (displayItems.length === 0) return true
          const last = displayItems[displayItems.length - 1]
          const lastIsAssistant = last.type === 'assistant' || last.type === 'assistant_with_thinking'
          return !lastIsAssistant
        })() && (
            <div className="message message-assistant">
              <button
                type="button"
                className="message-avatar message-avatar-button"
                onClick={() => void handleHeaderAvatarClick()}
                title="查看主页"
              >
                <MessageAvatar src={isGroup && displayItems.length > 0 ? getGroupAssistantAvatar((displayItems[displayItems.length - 1] as { message?: { sender?: string } })?.message?.sender ?? '小助手') : assistantAvatar} fallback="助" />
              </button>
              <div className="message-body">
                <span className="message-sender">小助手</span>
                <div className="message-content typing">正在思考...</div>
              </div>
            </div>
        )}
        <div ref={messagesEndRef} />
        <div ref={messagesTopRef} style={{ position: 'absolute', top: 0 }} />
      </div>
      <div
        className={`chat-input-area ${isAttachmentDragOver ? 'is-drag-over' : ''}`}
        onDragEnter={handleInputDragEnter}
        onDragOver={handleInputDragOver}
        onDragLeave={handleInputDragLeave}
        onDrop={handleInputDrop}
      >
        {messageMultiSelectMode && (
          <div className="chat-message-multi-bar">
            <span className="chat-message-multi-count">已选 {selectedMessageIds.size} 条</span>
            <button
              type="button"
              className="chat-message-multi-btn"
              disabled={selectedMessageIds.size === 0}
              onClick={handleForwardSelectedMessages}
            >
              生成聊天长图并转发
            </button>
            <button
              type="button"
              className="chat-message-multi-btn"
              disabled={selectedMessageIds.size === 0}
              onClick={() => {
                void handleSaveSelectedMessagesToQuickNote()
              }}
            >
              生成聊天长图并记入随手记
            </button>
            <button
              type="button"
              className="chat-message-multi-btn chat-message-multi-btn-ghost"
              onClick={closeMessageMultiSelect}
            >
              取消多选
            </button>
          </div>
        )}
        {stopTaskNotice && (
          <div className="chat-stop-task-toast" role="status">
            任务已停止
          </div>
        )}
        {clipboardSavedNotice && (
          <div className="chat-stop-task-toast" role="status">
            已保存至剪切板
          </div>
        )}
        {quickNoteSavedNotice && (
          <div className="chat-stop-task-toast" role="status">
            已记入随手记
          </div>
        )}
        {inlineToastNotice && (
          <div className="chat-stop-task-toast" role="status">
            {inlineToastNotice}
          </div>
        )}
        {historyLoading && (
          <div className="chat-history-sync-hint" role="status">
            正在同步消息…
          </div>
        )}
        {(bulkImportProgress || bulkWorkspaceBatch) && (
          <div className="chat-bulk-import-banner" role="status">
            <button
              type="button"
              className="chat-bulk-import-close"
              title="关闭并清除批量导入上下文"
              onClick={handleDismissBulkImportBanner}
            >
              ×
            </button>
            {bulkImportProgress && (
              <div className="chat-bulk-import-progress">
                <div className="chat-bulk-import-progress-text">
                  {bulkImportProgress.active ? '批量导入中' : '批量导入完成'}：{bulkImportProgress.processed}/{bulkImportProgress.total}
                  （成功 {bulkImportProgress.success}，失败 {bulkImportProgress.failed}）
                  {bulkImportProgress.currentName ? `，当前：${bulkImportProgress.currentName}` : ''}
                </div>
                <div className="chat-bulk-import-progress-track">
                  <div
                    className="chat-bulk-import-progress-fill"
                    style={{
                      width:
                        bulkImportProgress.total > 0
                          ? `${Math.min(100, Math.round((bulkImportProgress.processed / bulkImportProgress.total) * 100))}%`
                          : '0%',
                    }}
                  />
                </div>
              </div>
            )}
            {bulkWorkspaceBatch && (
              <div className="chat-bulk-import-ready">
                已导入批量文件：{bulkWorkspaceBatch.success}/{bulkWorkspaceBatch.total}（失败 {bulkWorkspaceBatch.failed}），发送时将按目录一并提供给 TopoClaw。
                {bulkFolderForCodeMode && (
                  <button
                    type="button"
                    className="chat-bulk-import-enter-code"
                    onClick={() => {
                      void handleEnterCodeModeFromBulkFolder()
                    }}
                    title={`进入 Code 模式并打开文件夹：${bulkFolderForCodeMode.folderName}`}
                  >
                    进入Code模式
                  </button>
                )}
              </div>
            )}
          </div>
        )}
        {isTopoClawConversation && ideModeEnabled && ideQaContextRootRelPath && (
          <div className="chat-code-context-hint" role="status">
            当前问答上下文：{ideQaContextRootRelPath}
            {ideQaLastRetrievalHitCount > 0 ? `（最近命中 ${ideQaLastRetrievalHitCount} 个文件）` : ''}
          </div>
        )}
        {isTopoClawConversation && ideModeEnabled && ideChangedFiles.length > 0 && (
          <div className="chat-code-diff-summary" role="status">
            <div className="chat-code-diff-summary-head">
              <span>本次修改 {ideChangedFiles.length} 个文件</span>
              <span className="chat-code-diff-plus">+{ideChangedTotalAdded}</span>
              <span className="chat-code-diff-minus">-{ideChangedTotalRemoved}</span>
              <button
                type="button"
                className="chat-code-diff-action"
                onClick={() => {
                  ideChangedFiles.forEach((item) => handleAcceptDiffForPath(item.path))
                }}
              >
                全部接收修改
              </button>
              <button
                type="button"
                className="chat-code-diff-action"
                onClick={() => {
                  ideChangedFiles.forEach((item) => handleRejectDiffForPath(item.path))
                }}
              >
                全部拒绝修改
              </button>
            </div>
            <div className="chat-code-diff-files">
              {ideChangedFiles.map((item) => (
                <button
                  key={item.path}
                  type="button"
                  className="chat-code-diff-file"
                  onClick={() => setIdeDiffReviewPath(item.path)}
                  title={`查看修改：${item.path}`}
                >
                  <span className="chat-code-diff-file-name">{item.path}</span>
                  <span className="chat-code-diff-plus">+{item.added}</span>
                  <span className="chat-code-diff-minus">-{item.removed}</span>
                </button>
              ))}
            </div>
          </div>
        )}
        <div className={`chat-input-card ${isAttachmentDragOver ? 'is-drag-over' : ''}`}>
          {selectedImages.length > 0 && (
            <div className="chat-image-preview-row">
              {selectedImages.map((img, idx) => (
                <div key={`${img.name}_${idx}`} className="chat-image-preview">
                  <ChatInlineImage
                    dataUrl={toChatImageSrc(img.base64, img.name || '图片.png')}
                    fileName={img.name || '图片.png'}
                    className="chat-image-preview-img"
                    onOpenLightbox={setImageLightbox}
                  />
                  <button
                    type="button"
                    className="chat-image-preview-remove"
                    onClick={() => {
                      setSelectedImages((prev) => prev.filter((_, i) => i !== idx))
                    }}
                    title="移除图片"
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          )}
          {selectedFileBase64 && (
            <div className="chat-image-preview-row">
              <div className="chat-image-preview">
                <a
                  href={`data:${selectedFileMime || 'application/octet-stream'};base64,${selectedFileBase64}`}
                  download={selectedFileName || 'file.bin'}
                  className="message-file-link"
                  title={selectedFileName || '文件'}
                >
                  {selectedFileName || '文件'}
                </a>
                <button
                  type="button"
                  className="chat-image-preview-remove"
                  onClick={() => {
                    setSelectedFileBase64(null)
                    setSelectedFileName('file.bin')
                    setSelectedFileMime('application/octet-stream')
                  }}
                  title="移除文件"
                >
                  ×
                </button>
              </div>
            </div>
          )}
          {quotedMessageContext && (
            <div className="chat-quote-preview-row">
              <div className="chat-quote-preview" title={quotedMessageContext.content}>
                <div className="chat-quote-preview-title">
                  引用：{quotedMessageContext.sender} · {formatQuoteTimestamp(quotedMessageContext.timestamp)}
                </div>
                <div className="chat-quote-preview-content">{normalizeQuotedContent(quotedMessageContext.content)}</div>
                <button
                  type="button"
                  className="chat-quote-preview-remove"
                  onClick={() => setQuotedMessageContext(null)}
                  title="移除引用"
                >
                  ×
                </button>
              </div>
            </div>
          )}
          {selectedFocusSkills.length > 0 && (
            <div className="chat-focus-skill-row">
              {selectedFocusSkills.map((skillName) => (
                <span key={skillName} className="chat-focus-skill-chip" title={skillName}>
                  <span className="chat-focus-skill-name">{getSkillDisplayName(skillName)}</span>
                  <button
                    type="button"
                    className="chat-focus-skill-remove"
                    onClick={() => setSelectedFocusSkills((prev) => prev.filter((name) => name !== skillName))}
                    title="移除技能"
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
          <div className={`chat-input-wrap ${isAttachmentDragOver ? 'is-drag-over' : ''}`}>
            {/* 等待模型/网络回复时仍允许编辑；防重复发送由 handleSend 入口与发送按钮 disabled 负责 */}
            <textarea
              ref={inputRef}
              rows={1}
              value={input}
              onChange={(e) => (isGroup ? handleGroupInputChange(e.target.value) : setInput(e.target.value))}
              onContextMenu={handleChatInputAreaContextMenu}
              onPaste={handleChatInputPaste}
              onKeyDown={(e) => {
                if (isGroup && mentionPopupOpen) handleInputKeyDownForMention(e)
                if (!e.defaultPrevented && e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
              onBlur={() => setTimeout(hideMentionPopup, 150)}
              placeholder="输入消息，Enter 发送，Shift+Enter 换行"
              className="chat-input"
            />
            {isGroup && mentionPopupOpen && mentionCandidates.length > 0 && (
              <div ref={mentionPopupRef} className="chat-mention-popup">
                {mentionCandidates.map((c, i) => (
                  <button
                    key={c.id}
                    type="button"
                    className={`chat-mention-item ${i === mentionSelectedIndex ? 'selected' : ''}`}
                    onMouseDown={(e) => {
                      e.preventDefault()
                      selectMentionCandidate(c)
                    }}
                  >
                    <span className="chat-mention-name">{c.displayName}</span>
                    {c.isAssistant && <span className="chat-mention-tag">小助手</span>}
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="chat-input-actions">
            <div className="chat-input-actions-left">
              {isCrossDevice && (
                <label className="chat-action-chip" title="附件">
                  <input type="file" className="chat-file-input" onChange={handleCrossDeviceFileSelect} />
                  📎
                </label>
              )}
              {(isCustomChatAssistant || isChatAssistant || isFriend || isCrossDevice) && (
                <label
                  className="chat-action-chip"
                  title={isFriend || isCrossDevice ? '发送图片' : '上传图片进行图片问答'}
                >
                  <input
                    type="file"
                    className="chat-file-input"
                    accept="image/*"
                    multiple
                    onChange={handleImageSelect}
                  />
                  🖼️
                </label>
              )}
              {isTopoClawConversation && (
                <label className="chat-action-chip" title="发送任意文件（将写入 workspace）">
                  <input
                    type="file"
                    className="chat-file-input"
                    onChange={handleTopoClawFileSelect}
                  />
                  📄
                </label>
              )}
              <div className="chat-emoji-wrap" onClick={(e) => e.stopPropagation()}>
                <button
                  type="button"
                  className="chat-action-chip chat-emoji-btn"
                  onClick={() => {
                    setShowEmoji((v) => !v)
                    setShowSkillPicker(false)
                  }}
                  title="表情"
                >
                  😊
                </button>
                {showEmoji && (
                  <div className="chat-emoji-picker">
                    {EMOJI_LIST.map((e) => (
                      <button
                        key={e}
                        type="button"
                        className="chat-emoji-item"
                        onClick={() => handleEmojiInsert(e)}
                      >
                        {e}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              {canUseFocusSkills && (
                <div className="chat-focus-skill-wrap" onClick={(e) => e.stopPropagation()}>
                  <button
                    type="button"
                    className="chat-action-chip chat-focus-skill-btn"
                    onClick={() => {
                      setShowSkillPicker((v) => !v)
                      setShowEmoji(false)
                    }}
                    title="选择技能"
                  >
                    技能
                  </button>
                  {showSkillPicker && (
                    <div className="chat-focus-skill-picker">
                      <div className="chat-focus-skill-header">
                        <input
                          type="text"
                          className="chat-focus-skill-search"
                          placeholder="搜索我的技能"
                          value={skillKeyword}
                          onChange={(e) => setSkillKeyword(e.target.value)}
                        />
                        <span className="chat-focus-skill-count">
                          {selectedFocusSkills.length}/{MAX_FOCUS_SKILLS}
                        </span>
                      </div>
                      <div className="chat-focus-skill-list">
                        {focusSkillSyncing ? (
                          <p className="chat-focus-skill-empty">同步中...</p>
                        ) : filteredSkillOptions.length === 0 ? (
                          <p className="chat-focus-skill-empty">
                            {mySkillOptions.length === 0
                              ? '暂无技能，请先到「我的技能」添加或刷新。'
                              : '没有匹配到技能。'}
                          </p>
                        ) : (
                          filteredSkillOptions.map((skill) => {
                            const checked = selectedFocusSkills.includes(skill.canonical)
                            const disabled = !checked && selectedFocusSkills.length >= MAX_FOCUS_SKILLS
                            return (
                              <label key={skill.canonical} className={`chat-focus-skill-item ${checked ? 'checked' : ''}`}>
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  disabled={disabled}
                                  onChange={() => toggleFocusSkill(skill.canonical)}
                                />
                                <span title={skill.canonical}>{skill.label}</span>
                              </label>
                            )
                          })
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}
              {builtinChatSlot && (builtinProfileLists || builtinProfilesLoading) && (
                <div className="chat-builtin-model-toolbar">
                  {builtinProfileLists ? (
                    builtinChatSlot === 'topoclaw' ? (
                      <>
                        <label className="chat-builtin-model-toolbar-label" htmlFor="chat-builtin-chat">
                          chat
                        </label>
                        <select
                          id="chat-builtin-chat"
                          className="chat-builtin-model-select"
                          value={builtinSelNonGui}
                          disabled={builtinApplyingModel}
                          onChange={(e) => handleBuiltinTopoNonGuiChange(e.target.value)}
                        >
                          {builtinProfileLists.nonGuiProfiles.map((p) => (
                            <option key={p.model} value={p.model}>
                              {p.model}
                            </option>
                          ))}
                        </select>
                        <label className="chat-builtin-model-toolbar-label" htmlFor="chat-builtin-gui">
                          GUI
                        </label>
                        <select
                          id="chat-builtin-gui"
                          className="chat-builtin-model-select"
                          value={builtinSelGui}
                          disabled={builtinApplyingModel}
                          onChange={(e) => handleBuiltinTopoGuiChange(e.target.value)}
                        >
                          {builtinProfileLists.guiProfiles.map((p) => (
                            <option key={p.model} value={p.model}>
                              {p.model}
                            </option>
                          ))}
                        </select>
                      </>
                    ) : (
                      <>
                        <label className="chat-builtin-model-toolbar-label" htmlFor="chat-builtin-gm">
                          模型
                        </label>
                        <select
                          id="chat-builtin-gm"
                          className="chat-builtin-model-select"
                          value={builtinSelGm}
                          disabled={builtinApplyingModel}
                          onChange={(e) => handleBuiltinGmModelChange(e.target.value)}
                        >
                          {builtinProfileLists.nonGuiProfiles.map((p) => (
                            <option key={p.model} value={p.model}>
                              {p.model}
                            </option>
                          ))}
                        </select>
                      </>
                    )
                  ) : (
                    <>
                      <span className="chat-builtin-model-toolbar-loading" role="status" aria-live="polite">
                        <span className="chat-builtin-model-toolbar-spinner" aria-hidden />
                        模型加载中…
                      </span>
                    </>
                  )}
                  {builtinProfilesLoading && (
                    <span className="chat-builtin-model-toolbar-hint">刷新中…</span>
                  )}
                  {builtinApplyingModel && (
                    <span className="chat-builtin-model-toolbar-hint">切换中…</span>
                  )}
                  {builtinModelSwitchErr ? (
                    <span className="chat-builtin-model-toolbar-err" role="alert" title={builtinModelSwitchErr}>
                      {builtinModelSwitchErr}
                    </span>
                  ) : null}
                </div>
              )}
            </div>
            {(loading && useChatWs && chatWs && !isGroup) || isMobileTaskRunning ? (
              <button
                type="button"
                className="send-btn stop-btn"
                onClick={handleStop}
                title="停止"
              >
                <svg className="send-btn-stop-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden>
                  {/* 外圈 rgb(37,99,235)，白方块 15×15 再大五号 → 20×20 */}
                  <rect x="0.5" y="0.5" width="23" height="23" rx="3.5" fill="rgb(37, 99, 235)" />
                  <rect x="2" y="2" width="20" height="20" rx="2.5" fill="#ffffff" />
                </svg>
              </button>
            ) : (
              <button
                type="button"
                className="send-btn"
                onClick={() => {
                  void handleSend()
                }}
                disabled={
                  loading ||
                  (isCrossDevice
                    ? !input.trim() && selectedImages.length === 0
                    : (isCustomChatAssistant || isChatAssistant || isFriend)
                      ? !input.trim() && selectedImages.length === 0 && !(isTopoClawConversation && !!selectedFileBase64)
                      : !input.trim())
                }
                title="发送"
              >
                ▲
              </button>
            )}
          </div>
        </div>
      </div>
        </div>
      </div>
      {ideModeEnabled && (
        <div
          className="chat-ide-right-resize-handle"
          onMouseDown={handleRightPaneResizeStart}
          role="separator"
          aria-orientation="vertical"
          aria-label="调整会话详情宽度"
        />
      )}
      </div>
      {ideModeEnabled && showIdeModeButton && (
        <section className="chat-ide-workspace" aria-label="IDE 工作区">
          <div className="chat-ide-topbar">
            <div className="chat-ide-menu-row">
              <button
                type="button"
                className={`chat-conversation-list-toggle chat-ide-conversation-toggle ${conversationListCollapsed ? 'collapsed' : ''}`}
                onClick={onToggleConversationList}
                title={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
                aria-label={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
                aria-expanded={!conversationListCollapsed}
              >
                <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M12 4L7 10L12 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
              <div className="chat-ide-menu" ref={ideFileMenuRef}>
                <button
                  type="button"
                  className={`chat-ide-menu-trigger ${ideFileMenuOpen ? 'active' : ''}`}
                  onClick={() => {
                    setIdeFileMenuOpen((v) => !v)
                    setIdeTerminalMenuOpen(false)
                  }}
                >
                  文件
                </button>
                {ideFileMenuOpen && (
                  <div className="chat-ide-menu-dropdown" role="menu">
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        handleIdeCreateNewFile()
                      }}
                    >
                      新建文件
                    </button>
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        handleIdeOpenFile()
                      }}
                    >
                      打开文件
                    </button>
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        handleIdeOpenFolder()
                      }}
                    >
                      打开文件夹
                    </button>
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        setIdeRecentPanelOpen(true)
                      }}
                    >
                      最近打开文件
                    </button>
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        void handleIdeSaveCurrentFile(false)
                      }}
                    >
                      保存
                    </button>
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeFileMenuOpen(false)
                        void handleIdeSaveCurrentFile(true)
                      }}
                    >
                      另存为
                    </button>
                  </div>
                )}
              </div>
              <button type="button" className="chat-ide-menu-placeholder" title="即将支持">编辑</button>
              <button type="button" className="chat-ide-menu-placeholder" title="即将支持">视图</button>
              <button
                type="button"
                className="chat-ide-menu-trigger"
                onClick={() => { void handleRunActiveFileInTerminal() }}
                title={ideTerminalRunning ? '终端运行中，请稍后再试' : '运行当前打开文件'}
                disabled={ideTerminalRunning}
              >
                运行
              </button>
              <div className="chat-ide-menu" ref={ideTerminalMenuRef}>
                <button
                  type="button"
                  className={`chat-ide-menu-trigger ${ideTerminalMenuOpen ? 'active' : ''}`}
                  onClick={() => {
                    setIdeTerminalMenuOpen((v) => !v)
                    setIdeFileMenuOpen(false)
                  }}
                >
                  终端
                </button>
                {ideTerminalMenuOpen && (
                  <div className="chat-ide-menu-dropdown" role="menu">
                    <button
                      type="button"
                      className="chat-ide-menu-item"
                      onClick={() => {
                        setIdeTerminalMenuOpen(false)
                        setIdeTerminalVisible(true)
                        setIdeTerminalCollapsed(false)
                      }}
                    >
                      打开终端
                    </button>
                  </div>
                )}
              </div>
            </div>
            <span className="chat-ide-topbar-hint">TopoClaw IDE</span>
          </div>
          <div className="chat-ide-workbench">
            {hasIdeExplorer && (
              <aside className="chat-ide-explorer" style={{ width: `${ideExplorerWidth}px`, flex: `0 0 ${ideExplorerWidth}px` }} aria-label="文件管理器">
                <div className="chat-ide-explorer-title">
                  <span>文件管理器</span>
                  <button
                    type="button"
                    className={`chat-ide-explorer-search-toggle ${ideExplorerSearchOpen ? 'active' : ''}`}
                    title={ideExplorerSearchOpen ? '关闭搜索' : '搜索文件内容'}
                    onClick={() => {
                      setIdeExplorerSearchOpen((v) => !v)
                      if (ideExplorerSearchOpen) setIdeExplorerSearchKeyword('')
                    }}
                  >
                    <svg viewBox="0 0 20 20" fill="none" aria-hidden>
                      <circle cx="9" cy="9" r="5.5" stroke="currentColor" strokeWidth="1.6" />
                      <path d="M13.5 13.5L17 17" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                    </svg>
                  </button>
                </div>
                {ideExplorerSearchOpen && (
                  <div className="chat-ide-explorer-search-panel">
                    <input
                      className="chat-ide-explorer-search-input"
                      value={ideExplorerSearchKeyword}
                      onChange={(event) => setIdeExplorerSearchKeyword(event.target.value)}
                      placeholder="搜索文件名或内容"
                    />
                    {ideExplorerSearchKeyword.trim() ? (
                      <div className="chat-ide-explorer-search-results">
                        {ideExplorerSearchResults.length === 0 ? (
                          <div className="chat-ide-explorer-search-empty">未找到匹配项</div>
                        ) : (
                          ideExplorerSearchResults.map((item, idx) => (
                            <button
                              key={`${item.path}-${idx}`}
                              type="button"
                              className="chat-ide-explorer-search-item"
                              onClick={() => handleOpenExplorerSearchResult(item)}
                              title={item.path}
                            >
                              <div className="chat-ide-explorer-search-path">{item.path}</div>
                              <div className="chat-ide-explorer-search-preview">
                                {item.line > 0 ? `L${item.line}: ` : ''}
                                {item.preview}
                              </div>
                            </button>
                          ))
                        )}
                      </div>
                    ) : null}
                  </div>
                )}
                <div className="chat-ide-tree">
                  {(!ideExplorerSearchOpen || !ideExplorerSearchKeyword.trim()) ? renderIdeTreeNodes(ideExplorerTree) : null}
                </div>
              </aside>
            )}
            {hasIdeExplorer && (
              <div
                className="chat-ide-explorer-resize-handle"
                onMouseDown={handleExplorerResizeStart}
                role="separator"
                aria-orientation="vertical"
                aria-label="调整文件管理器宽度"
              />
            )}
            <div className="chat-ide-main-column">
              <div className="chat-ide-editor">
                <div className="chat-ide-editor-content">
                  {ideActiveFile ? (
                    <>
                      <div className="chat-ide-editor-tabs" role="tablist" aria-label="已打开文件">
                        {ideVisibleTabs.map((tabPath) => {
                          const active = ideActiveFile?.toLowerCase() === tabPath.toLowerCase()
                          const tabName = tabPath.split('/').pop() || tabPath
                          return (
                            <div
                              key={tabPath}
                              className={`chat-ide-editor-tab ${active ? 'active' : ''}`}
                              role="tab"
                              aria-selected={active}
                            >
                              <button
                                type="button"
                                className="chat-ide-editor-tab-main"
                                onClick={() => setIdeActiveFile(tabPath)}
                                onContextMenu={(event) => {
                                  event.preventDefault()
                                  event.stopPropagation()
                                  setIdeExplorerContextMenu({
                                    x: event.clientX,
                                    y: event.clientY,
                                    path: tabPath,
                                    nodeType: 'file',
                                  })
                                }}
                                title={tabPath}
                              >
                                {tabName}
                              </button>
                              <button
                                type="button"
                                className="chat-ide-editor-tab-close"
                                onClick={(event) => {
                                  event.preventDefault()
                                  event.stopPropagation()
                                  handleIdeCloseTab(tabPath)
                                }}
                                title="关闭文件标签"
                                aria-label={`关闭 ${tabName}`}
                              >
                                ×
                              </button>
                            </div>
                          )
                        })}
                      </div>
                      {ideFindOpen && (
                        <div className="chat-ide-findbar">
                          <input
                            ref={ideFindInputRef}
                            className="chat-ide-find-input"
                            value={ideFindKeyword}
                            onChange={(event) => setIdeFindKeyword(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') {
                                event.preventDefault()
                                const ok = runIdeFind(event.shiftKey ? -1 : 1)
                                if (!ok && ideFindKeyword.trim()) window.alert(`未找到：${ideFindKeyword}`)
                              } else if (event.key === 'Escape') {
                                event.preventDefault()
                                setIdeFindOpen(false)
                              }
                            }}
                            placeholder="查找"
                          />
                          <button
                            type="button"
                            className="chat-ide-find-btn"
                            onClick={() => {
                              const ok = runIdeFind(-1)
                              if (!ok && ideFindKeyword.trim()) window.alert(`未找到：${ideFindKeyword}`)
                            }}
                            title="上一个"
                          >
                            ↑
                          </button>
                          <button
                            type="button"
                            className="chat-ide-find-btn"
                            onClick={() => {
                              const ok = runIdeFind(1)
                              if (!ok && ideFindKeyword.trim()) window.alert(`未找到：${ideFindKeyword}`)
                            }}
                            title="下一个"
                          >
                            ↓
                          </button>
                          <button
                            type="button"
                            className="chat-ide-find-close"
                            onClick={() => setIdeFindOpen(false)}
                            title="关闭查找"
                            aria-label="关闭查找"
                          >
                            ×
                          </button>
                          <span className="chat-ide-find-count">
                            {ideFindMatches.length === 0 ? '0/0' : `${ideFindCurrentMatch}/${ideFindMatches.length}`}
                          </span>
                        </div>
                      )}
                      {ideActiveMode === 'image' ? (
                        <div className="chat-ide-special-view">
                          <div className="chat-ide-special-toolbar">
                            <span className="chat-ide-special-label">图片预览模式</span>
                            <button
                              type="button"
                              className="chat-ide-special-action"
                              onClick={() => { void handleOpenActiveFileExternally() }}
                              title="在系统应用中打开"
                            >
                              外部打开
                            </button>
                          </div>
                          {ideActiveFileUrl ? (
                            <div className="chat-ide-image-preview-wrap">
                              <img className="chat-ide-image-preview" src={ideActiveFileUrl} alt={basenameFromAnyPath(ideActiveFile || '') || 'image'} />
                            </div>
                          ) : (
                            <div className="chat-ide-empty">该图片暂不可预览，请尝试外部打开</div>
                          )}
                        </div>
                      ) : ideActiveMode === 'readonly-preview' ? (
                        <div className="chat-ide-special-view">
                          <div className="chat-ide-special-toolbar">
                            <span className="chat-ide-special-label">只读预览模式（Word / Excel / PPT / CSV）</span>
                            <button
                              type="button"
                              className="chat-ide-special-action"
                              onClick={() => { void handleOpenActiveFileExternally() }}
                              title="在系统应用中打开并编辑"
                            >
                              外部编辑
                            </button>
                          </div>
                          {ideActiveFileExt.toLowerCase() === 'pptx' ? (
                            idePptxPreviewLoading ? (
                              <div className="chat-ide-office-tip">正在解析 PPTX 内容…</div>
                            ) : idePptxPreviewError ? (
                              <div className="chat-ide-office-tip">{idePptxPreviewError}</div>
                            ) : (
                              <div className="chat-ide-pptx-preview">
                                {idePptxPreviewSlides.map((slide, idx) => (
                                  <section key={`${slide.title}-${idx}`} className="chat-ide-pptx-slide">
                                    <div className="chat-ide-pptx-slide-title">{slide.title}</div>
                                    <pre className="chat-ide-pptx-slide-text">{slide.text || '(本页无可解析文字)'}</pre>
                                  </section>
                                ))}
                              </div>
                            )
                          ) : isIdeVirtualFileContent(ideActiveContent) ? (
                            <div className="chat-ide-office-tip">
                              当前文件不支持内置编辑，建议点击“外部编辑”使用系统应用打开并编辑。
                            </div>
                          ) : (
                            <textarea
                              ref={ideEditorRef}
                              className="chat-ide-code-editor"
                              value={ideActiveContent}
                              onKeyDown={handleCodeEditorKeyDown}
                              spellCheck={false}
                              readOnly
                            />
                          )}
                        </div>
                      ) : (
                        <textarea
                          ref={ideEditorRef}
                          className="chat-ide-code-editor"
                          value={ideActiveContent}
                          onChange={(event) => {
                            const current = ideActiveFile
                            if (!current) return
                            const value = event.target.value
                            setIdeFileContents((prev) => ({ ...prev, [current]: value }))
                            const key = normalizeIdePath(current).toLowerCase()
                            const baseline = ideSavedContents[current] ?? ''
                            setIdeHumanEditedPaths((prev) => {
                              const next = { ...prev }
                              if (value === baseline) delete next[key]
                              else next[key] = true
                              return next
                            })
                          }}
                          onKeyDown={handleCodeEditorKeyDown}
                          spellCheck={false}
                        />
                      )}
                    </>
                  ) : (
                    <div className="chat-ide-empty">打开文件</div>
                  )}
                  {ideRecentPanelOpen && (
                    <div className="chat-ide-recent-panel">
                      <div className="chat-ide-recent-header">
                        <div className="chat-ide-recent-title">最近打开文件</div>
                        <button
                          type="button"
                          className="chat-ide-recent-close"
                          onClick={() => setIdeRecentPanelOpen(false)}
                          title="关闭最近打开文件"
                          aria-label="关闭最近打开文件"
                        >
                          ×
                        </button>
                      </div>
                      {ideRecentEntries.length === 0 ? (
                        <div className="chat-ide-recent-empty">暂无最近文件</div>
                      ) : (
                        <div className="chat-ide-recent-list">
                          {ideRecentEntries.slice(0, 10).map((name, idx) => (
                            <button
                              key={`${name}-${idx}`}
                              type="button"
                              className="chat-ide-recent-item"
                              title={name}
                              onClick={() => handleOpenRecentInIde(name)}
                            >
                              {name}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
              <div
                className={`chat-ide-terminal-wrap ${ideTerminalCollapsed ? 'collapsed' : ''} ${ideTerminalVisible ? '' : 'hidden'}`}
                style={!ideTerminalCollapsed ? { height: `${ideTerminalHeight}px` } : undefined}
              >
                <div
                  className="chat-ide-terminal-resize-handle"
                  onMouseDown={handleTerminalResizeStart}
                  role="separator"
                  aria-orientation="horizontal"
                  aria-label="调整终端高度"
                />
                <div className="chat-ide-terminal-header">
                  <div className="chat-ide-terminal-tabs">
                    {ideTerminals.map((tab) => (
                      <div key={tab.id} className={`chat-ide-terminal-tab ${ideActiveTerminalId === tab.id ? 'active' : ''}`}>
                        <button
                          type="button"
                          className="chat-ide-terminal-tab-main"
                          onClick={() => setIdeActiveTerminalId(tab.id)}
                        >
                          {tab.name}
                        </button>
                        <button
                          type="button"
                          className="chat-ide-terminal-tab-close"
                          onClick={(event) => {
                            event.stopPropagation()
                            handleCloseTerminal(tab.id)
                          }}
                          title="关闭终端"
                          aria-label="关闭终端"
                        >
                          ×
                        </button>
                      </div>
                    ))}
                  </div>
                  <div className="chat-ide-terminal-actions">
                    <button
                      type="button"
                      className="chat-ide-terminal-new"
                      onClick={openTerminalFindPanel}
                      title="在终端中查找 (Ctrl+F)"
                    >
                      查找
                    </button>
                    <button
                      type="button"
                      className="chat-ide-terminal-new"
                      onClick={handleCreateNewTerminal}
                      title="打开新终端"
                    >
                      打开新终端
                    </button>
                    <button
                      type="button"
                      className="chat-ide-terminal-new"
                      onClick={handleRefreshActiveTerminal}
                      title="刷新当前终端"
                    >
                      刷新终端
                    </button>
                    {typeof window !== 'undefined' && (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI?.openWindow && (
                      <button
                        type="button"
                        className="chat-ide-terminal-popout"
                        onClick={() => {
                          void (window as Window & { terminalAPI?: IdeTerminalBridge }).terminalAPI?.openWindow?.()
                        }}
                        title="在独立窗口中打开终端"
                      >
                        弹出
                      </button>
                    )}
                    <button
                      type="button"
                      className="chat-ide-terminal-collapse"
                      onClick={() => setIdeTerminalCollapsed((v) => !v)}
                      title="收起终端"
                      aria-label="收起终端"
                    >
                      <svg viewBox="0 0 20 20" fill="none" aria-hidden>
                        <path d="M6 8L10 12L14 8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </button>
                    <button
                      type="button"
                      className="chat-ide-terminal-hide"
                      onClick={handleHideTerminalPanel}
                      title="关闭终端"
                      aria-label="关闭终端"
                    >
                      ×
                    </button>
                  </div>
                </div>
                {ideTerminalFindOpen && (
                  <div className="chat-ide-terminal-findbar">
                    <input
                      ref={ideTerminalFindInputRef}
                      className="chat-ide-terminal-find-input"
                      value={ideTerminalFindKeyword}
                      onChange={(event) => {
                        setIdeTerminalFindKeyword(event.target.value)
                        setIdeTerminalFindCurrent(0)
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          event.preventDefault()
                          const ok = runTerminalFind(event.shiftKey ? -1 : 1)
                          if (!ok && ideTerminalFindKeyword.trim()) window.alert(`未找到：${ideTerminalFindKeyword}`)
                        } else if (event.key === 'Escape') {
                          event.preventDefault()
                          setIdeTerminalFindOpen(false)
                        }
                      }}
                      placeholder="在终端中查找"
                    />
                    <button
                      type="button"
                      className="chat-ide-find-btn"
                      onClick={() => {
                        const ok = runTerminalFind(-1)
                        if (!ok && ideTerminalFindKeyword.trim()) window.alert(`未找到：${ideTerminalFindKeyword}`)
                      }}
                      title="上一个"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      className="chat-ide-find-btn"
                      onClick={() => {
                        const ok = runTerminalFind(1)
                        if (!ok && ideTerminalFindKeyword.trim()) window.alert(`未找到：${ideTerminalFindKeyword}`)
                      }}
                      title="下一个"
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      className="chat-ide-find-close"
                      onClick={() => setIdeTerminalFindOpen(false)}
                      title="关闭终端查找"
                      aria-label="关闭终端查找"
                    >
                      ×
                    </button>
                    <span className="chat-ide-find-count">
                      {ideTerminalFindTotal === 0 ? '0/0' : `${ideTerminalFindCurrent}/${ideTerminalFindTotal}`}
                    </span>
                  </div>
                )}
                <div className="chat-ide-terminal-body">
                  {ideTerminalError ? (
                    <div className="chat-ide-terminal-error">{ideTerminalError}</div>
                  ) : (
                    <div className="chat-ide-terminal-stack">
                      {ideTerminals.map((tab) => (
                        <div
                          key={tab.id}
                          className={`chat-ide-terminal ${ideActiveTerminalId === tab.id ? 'active' : 'hidden'}`}
                          ref={(el) => {
                            ideTerminalHostsRef.current[tab.id] = el
                          }}
                        />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        </section>
      )}
      {fileLinkActionTarget && !ideModeEnabled && (
        <div className="chat-file-link-action-mask" onClick={() => setFileLinkActionTarget(null)}>
          <div className="chat-file-link-action-modal" role="dialog" aria-label="文件打开方式" onClick={(e) => e.stopPropagation()}>
            <div className="chat-file-link-action-title">选择打开方式</div>
            <div className="chat-file-link-action-name">{fileLinkActionTarget.fileName || '文件'}</div>
            <div className="chat-file-link-action-buttons">
              <button
                type="button"
                className="chat-file-link-action-btn primary"
                onClick={() => {
                  const target = fileLinkActionTarget
                  setFileLinkActionTarget(null)
                  if (!target) return
                  void handleFileLinkQuickView(target)
                }}
              >
                快捷查看
              </button>
              <button
                type="button"
                className="chat-file-link-action-btn"
                onClick={() => {
                  const target = fileLinkActionTarget
                  setFileLinkActionTarget(null)
                  if (!target) return
                  void handleFileLinkOpenFolder(target)
                }}
              >
                到文件夹查看
              </button>
              <button
                type="button"
                className="chat-file-link-action-btn"
                onClick={() => setFileLinkActionTarget(null)}
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}
      {ideDiffReviewCurrent && (
        <div className="chat-code-diff-modal-mask" onClick={() => setIdeDiffReviewPath(null)}>
          <div className="chat-code-diff-modal" role="dialog" aria-label="修改审阅" onClick={(e) => e.stopPropagation()}>
            <div className="chat-code-diff-modal-head">
              <div className="chat-code-diff-modal-title">{ideDiffReviewCurrent.path}</div>
              <button
                type="button"
                className="chat-code-diff-modal-close"
                onClick={() => setIdeDiffReviewPath(null)}
                aria-label="关闭审阅"
              >
                ×
              </button>
            </div>
            <div className="chat-code-diff-modal-toolbar">
              <span>{ideDiffReviewIndex + 1}/{ideChangedFiles.length} 个修改</span>
              <button
                type="button"
                className="chat-code-diff-action"
                disabled={ideChangedFiles.length <= 1}
                onClick={() => {
                  if (ideChangedFiles.length <= 1) return
                  const nextIndex = (ideDiffReviewIndex - 1 + ideChangedFiles.length) % ideChangedFiles.length
                  setIdeDiffReviewPath(ideChangedFiles[nextIndex].path)
                }}
              >
                上一个
              </button>
              <button
                type="button"
                className="chat-code-diff-action"
                disabled={ideChangedFiles.length <= 1}
                onClick={() => {
                  if (ideChangedFiles.length <= 1) return
                  const nextIndex = (ideDiffReviewIndex + 1) % ideChangedFiles.length
                  setIdeDiffReviewPath(ideChangedFiles[nextIndex].path)
                }}
              >
                下一个
              </button>
              <button
                type="button"
                className="chat-code-diff-action"
                onClick={() => {
                  handleRejectDiffForPath(ideDiffReviewCurrent.path)
                }}
              >
                拒绝修改
              </button>
              <button
                type="button"
                className="chat-code-diff-action"
                onClick={() => {
                  handleAcceptDiffForPath(ideDiffReviewCurrent.path)
                }}
              >
                接收修改
              </button>
            </div>
            <div className="chat-code-diff-modal-body">
              {ideDiffReviewCurrent.hunks.length === 0 ? (
                <div className="chat-code-diff-empty">无可展示差异</div>
              ) : (
                ideDiffReviewCurrent.hunks.map((hunk, hunkIdx) => (
                  <div key={`${ideDiffReviewCurrent.path}-${hunkIdx}`} className="chat-code-diff-hunk">
                    {hunk.lines.map((line, idx) => (
                      <div
                        key={`${hunkIdx}-${idx}`}
                        className={`chat-code-diff-line chat-code-diff-line-${line.kind}`}
                      >
                        <span className="chat-code-diff-line-prefix">
                          {line.kind === 'add' ? '+' : line.kind === 'remove' ? '-' : ' '}
                        </span>
                        <span className="chat-code-diff-line-text">{line.text || ' '}</span>
                      </div>
                    ))}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}
      {ideExplorerContextMenu && (
        <div
          className="chat-ide-file-context-menu"
          style={{
            left: Math.min(ideExplorerContextMenu.x, (typeof window !== 'undefined' ? window.innerWidth : 800) - 188),
            top: Math.min(ideExplorerContextMenu.y, (typeof window !== 'undefined' ? window.innerHeight : 600) - 220),
          }}
          role="menu"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="chat-session-context-menu-item"
            onClick={() => {
              const targetPath = ideExplorerContextMenu.path
              const targetType = ideExplorerContextMenu.nodeType
              setIdeExplorerContextMenu(null)
              void handleCopyIdeEntry(targetPath, targetType)
            }}
          >
            复制
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            onClick={() => {
              const absolute = resolveIdeAbsolutePath(ideExplorerContextMenu.path)
              void writeClipboardText(absolute || ideExplorerContextMenu.path)
              setIdeExplorerContextMenu(null)
            }}
          >
            复制路径
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            onClick={() => {
              const relative = resolveIdeRelativePath(ideExplorerContextMenu.path)
              void writeClipboardText(relative)
              setIdeExplorerContextMenu(null)
            }}
          >
            复制相对路径
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            onClick={() => {
              const targetPath = ideExplorerContextMenu.path
              const targetType = ideExplorerContextMenu.nodeType
              setIdeExplorerContextMenu(null)
              void handleJumpToIdeEntryFolder(targetPath, targetType)
            }}
          >
            跳转到文件夹查看
          </button>
          {ideExplorerContextMenu.nodeType === 'file' && (
            <>
              <button
                type="button"
                className="chat-session-context-menu-item"
                onClick={() => {
                  handleIdeRenameFile(ideExplorerContextMenu.path)
                  setIdeExplorerContextMenu(null)
                }}
              >
                重命名
              </button>
              <button
                type="button"
                className="chat-session-context-menu-item chat-session-context-menu-item-danger"
                onClick={() => {
                  handleIdeDeleteFile(ideExplorerContextMenu.path)
                  setIdeExplorerContextMenu(null)
                }}
              >
                删除
              </button>
            </>
          )}
        </div>
      )}
      {!ideModeEnabled && sessionSidebarOpen && (
        <aside className="chat-session-sidebar" aria-label="会话侧边栏">
          <button
            type="button"
            className="chat-session-sidebar-new-btn"
            title="开启新对话"
            onClick={handleNewSession}
          >
            <svg className="chat-session-sidebar-new-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <circle cx="12" cy="12" r="9" />
              <line x1="12" y1="8" x2="12" y2="16" />
              <line x1="8" y1="12" x2="16" y2="12" />
            </svg>
            <span>开启新对话</span>
          </button>
          <div className="chat-session-sidebar-list">
            {sessions.map((s) => (
              <button
                key={s.id}
                type="button"
                className={`chat-session-sidebar-item ${s.id === currentSessionId ? 'selected' : ''}`}
                onClick={() => handleSelectSession(s.id)}
                onContextMenu={(e) => handleSessionContextMenu(e, s.id)}
                title={s.title}
              >
                {s.title}
              </button>
            ))}
          </div>
        </aside>
      )}
      {!ideModeEnabled && profileSidebarTarget && (
        <aside className="chat-session-sidebar chat-profile-sidebar" aria-label="主页侧边栏">
          <ContactProfilePanel
            target={profileSidebarTarget}
            friends={friendsForProfile}
            assistantsForGroup={assistantsForProfile}
            userAvatar={userAvatar}
            userName={userName || '我'}
            onClose={() => setProfileSidebarTarget(null)}
            onSendMessage={(targetConversation) => {
              setProfileSidebarTarget(null)
              onSelectConversation?.(targetConversation)
            }}
            onGroupUpdated={(group) => {
              setGroupInfoCache((prev) => ({ ...prev, [group.group_id]: group }))
              if (
                conversation.type === 'group' &&
                normalizeGroupRawId(conversation.id) === normalizeGroupRawId(group.group_id)
              ) {
                const groupAssistants = group.assistants ?? (group.assistant_enabled ? ['assistant'] : [])
                const assistantConfigs = group.assistant_configs ?? {}
                const updatedConversation: Conversation = {
                  ...conversation,
                  id: toCanonicalGroupConversationId(group.group_id) || conversation.id,
                  name: group.name || conversation.name,
                  members: group.members ?? [],
                  groupWorkflowModeEnabled: !!group.workflow_mode,
                  groupFreeDiscoveryEnabled: !!group.free_discovery,
                  groupAssistantMutedEnabled: !!group.assistant_muted,
                  assistantConfigs: Object.keys(assistantConfigs).length > 0 ? assistantConfigs : undefined,
                  assistants: groupAssistants.map((aid) => {
                    const existing = conversation.assistants?.find((a) => a.id === aid)
                    const fromProfile = assistantsForProfile.find((a) => a.id === aid)
                    return {
                      id: aid,
                      name: assistantConfigs[aid]?.name ?? existing?.name ?? fromProfile?.name ?? aid,
                    }
                  }),
                }
                onSelectConversation?.(updatedConversation)
                setProfileSidebarTarget((prev) => {
                  if (!prev || prev.type !== 'group') return prev
                  return { ...prev, data: group }
                })
              }
            }}
            onGroupRemoved={(groupId) => {
              setGroupInfoCache((prev) => {
                const next = { ...prev }
                delete next[groupId]
                return next
              })
              setProfileSidebarTarget((prev) => {
                if (!prev || prev.type !== 'group') return prev
                const currentId = prev.data?.group_id
                return currentId === groupId ? null : prev
              })
            }}
            onOpenWorkflowWorkspace={openWorkflowWorkspace}
          />
        </aside>
      )}
      {!!workflowWorkspace && (
        <div className="chat-workflow-workspace-overlay" role="dialog" aria-modal="true" aria-label="编排工作区">
          <div className="chat-workflow-workspace-topbar">
            <button
              type="button"
              className={`chat-workflow-workspace-list-toggle ${conversationListCollapsed ? 'collapsed' : ''}`}
              onClick={() => onToggleConversationList?.()}
              title={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
              aria-label={conversationListCollapsed ? '展开会话列表' : '收起会话列表'}
              aria-expanded={!conversationListCollapsed}
            >
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
                <path d="M12 4L7 10L12 16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </button>
            <div className="chat-workflow-workspace-menu">
              <div className="chat-workflow-workspace-insert-menu" ref={workflowStartMenuRef}>
                <button
                  type="button"
                  className={`chat-workflow-workspace-menu-btn chat-workflow-workspace-insert-trigger ${workflowStartMenuOpen ? 'open' : ''}`}
                  onClick={() => {
                    setWorkflowStartMenuOpen((prev) => !prev)
                    setWorkflowInsertMenuOpen(false)
                  }}
                  aria-haspopup="menu"
                  aria-expanded={workflowStartMenuOpen}
                  title="文件"
                >
                  文件
                </button>
                {workflowStartMenuOpen && (
                  <div className="chat-workflow-workspace-insert-dropdown" role="menu" aria-label="文件菜单">
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={handleWorkflowNewWorkspace}
                    >
                      新建编排区
                    </button>
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={() => void handleWorkflowSaveWorkspace()}
                    >
                      保存编排区
                    </button>
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={handleWorkflowPickLoadFile}
                    >
                      载入编排区
                    </button>
                  </div>
                )}
              </div>
              <button type="button" className="chat-workflow-workspace-menu-btn">编排模板</button>
              <div className="chat-workflow-workspace-insert-menu" ref={workflowInsertMenuRef}>
                <button
                  type="button"
                  className={`chat-workflow-workspace-menu-btn chat-workflow-workspace-insert-trigger ${workflowInsertMenuOpen ? 'open' : ''}`}
                  onClick={() => {
                    setWorkflowInsertMenuOpen((prev) => !prev)
                    setWorkflowStartMenuOpen(false)
                  }}
                  aria-haspopup="menu"
                  aria-expanded={workflowInsertMenuOpen}
                  title="插入节点"
                >
                  插入节点
                </button>
                {workflowInsertMenuOpen && (
                  <div className="chat-workflow-workspace-insert-dropdown" role="menu" aria-label="插入节点选项">
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={() => setWorkflowInsertMenuOpen(false)}
                    >
                      插入助手实例
                    </button>
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={() => setWorkflowInsertMenuOpen(false)}
                    >
                      插入用户实例
                    </button>
                    <button
                      type="button"
                      className="chat-workflow-workspace-insert-option"
                      role="menuitem"
                      onClick={handleInsertWorkflowDecisionNode}
                    >
                      插入判断实例
                    </button>
                  </div>
                )}
              </div>
              <button
                type="button"
                className="chat-workflow-workspace-menu-btn"
                onClick={() => void handleWorkflowSimulateRun()}
                disabled={workflowRunInProgress}
              >
                {workflowRunInProgress ? '模拟运行中...' : '模拟运行'}
              </button>
              <button
                type="button"
                className="chat-workflow-workspace-menu-btn"
                onClick={() => void handleWorkflowExecuteRun()}
                disabled={workflowRunInProgress}
              >
                {workflowRunInProgress ? '执行中...' : '执行'}
              </button>
              <button
                type="button"
                className={`chat-workflow-workspace-menu-btn ${workflowRunLogOpen ? 'active' : ''}`}
                onClick={() => setWorkflowRunLogOpen((prev) => !prev)}
              >
                执行日志
              </button>
            </div>
            <h3 className="chat-workflow-workspace-title">编排工作区</h3>
            <button
              type="button"
              className="chat-workflow-workspace-close"
              onClick={handleCloseWorkflowWorkspace}
            >
              返回
            </button>
          </div>
          <input
            ref={workflowLoadInputRef}
            type="file"
            accept={`.json,${WORKFLOW_FILE_SUFFIX},application/json`}
            style={{ display: 'none' }}
            onChange={(event) => void handleWorkflowLoadFileChange(event)}
          />
          <div className="chat-workflow-workspace-main">
            <section
              className="chat-workflow-workspace-canvas"
              aria-label="编排区"
              ref={workflowCanvasRef}
              onMouseDown={handleWorkflowCanvasMouseDown}
              onWheel={handleWorkflowCanvasWheel}
              onDragOver={(event) => {
                event.preventDefault()
                event.dataTransfer.dropEffect = 'copy'
              }}
              onDrop={handleWorkflowCanvasDrop}
            >
              <div className={`chat-workflow-workspace-edit-panel ${workflowEditTarget ? 'open' : ''}`}>
                <div className="chat-workflow-workspace-edit-actions-top">
                  <button
                    type="button"
                    className="chat-workflow-workspace-edit-delete"
                    onClick={handleWorkflowDeleteEditTarget}
                    disabled={!workflowEditTarget}
                  >
                    删除
                  </button>
                </div>
                <div className="chat-workflow-workspace-edit-body">
                  {selectedDecisionNodeDetails ? (
                    <>
                      <label className="chat-workflow-workspace-edit-label" htmlFor="workflow-edit-node-name">
                        节点名
                      </label>
                      <input
                        id="workflow-edit-node-name"
                        className="chat-workflow-workspace-edit-node-name-input"
                        value={selectedWorkflowNode?.name ?? ''}
                        onChange={(event) => handleWorkflowNodeNameChange(event.target.value)}
                      />

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点 ID</span>
                        <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                          {selectedWorkflowNode?.id || '-'}
                        </span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点介绍</span>
                        <textarea
                          className="chat-workflow-workspace-edit-role-textarea"
                          value={selectedDecisionNodeDetails.nodeTaskIntro}
                          onChange={(event) => handleWorkflowNodeTaskIntroChange(event.target.value)}
                          placeholder="请输入当前节点要完成的任务说明"
                        />
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">担任者</span>
                        <select
                          className="chat-workflow-workspace-edit-select chat-workflow-workspace-edit-select-primary"
                          value={selectedDecisionNodeDetails.assigneeId}
                          onChange={(event) => handleWorkflowDecisionAssigneeChange(event.target.value)}
                        >
                          {workflowDecisionAssigneeOptions.map((option) => (
                            <option key={`decision-assignee-${option.id}`} value={option.id}>
                              {option.label}
                            </option>
                          ))}
                        </select>
                      </div>

                      {!selectedDecisionNodeDetails.isCode && (
                        <>
                          <div className="chat-workflow-workspace-edit-field">
                            <span className="chat-workflow-workspace-edit-label">担任者 ID</span>
                            <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                              {selectedDecisionNodeDetails.assigneeRawId || '-'}
                            </span>
                          </div>

                          <div className="chat-workflow-workspace-edit-field">
                            <span className="chat-workflow-workspace-edit-label">节点职责</span>
                            <textarea
                              className="chat-workflow-workspace-edit-role-textarea"
                              value={selectedDecisionNodeDetails.nodeResponsibility}
                              onChange={(event) => handleWorkflowDecisionResponsibilityChange(event.target.value)}
                              placeholder="请输入节点职责"
                            />
                          </div>
                        </>
                      )}

                      {selectedDecisionNodeDetails.isCode && (
                        <div className="chat-workflow-workspace-edit-field">
                          <span className="chat-workflow-workspace-edit-label">示例代码</span>
                          <textarea
                            className="chat-workflow-workspace-edit-role-textarea chat-workflow-workspace-edit-value-mono"
                            value={selectedDecisionNodeDetails.codeExample}
                            onChange={(event) => handleWorkflowDecisionCodeExampleChange(event.target.value)}
                            placeholder={'请填写代码示例，例如：\nresponse = input_data.get("response", "")\nif response == "xx":\n  return 1\nreturn 2'}
                            spellCheck={false}
                          />
                        </div>
                      )}

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">上游节点节点名列</span>
                        {selectedWorkflowUpstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowUpstreamNodes.map((node) => (
                              <div key={`up-d-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span>{node.name}</span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(node.id, selectedWorkflowNodeId)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowUpstreamDraftNodeId}
                            onChange={(event) => setWorkflowUpstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择上游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`up-decision-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowUpstream}
                            disabled={!workflowUpstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">下游节点节点名列</span>
                        {selectedWorkflowDownstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowDownstreamNodes.map((node) => (
                              <div key={`down-d-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span className="chat-workflow-workspace-edit-list-node">
                                  {(() => {
                                    const edgeId = workflowCanvasEdges.find(
                                      (edge) => edge.fromNodeId === selectedWorkflowNodeId && edge.toNodeId === node.id
                                    )?.id
                                    const code = edgeId ? workflowDecisionEdgeCodeMap[edgeId] : ''
                                    if (!code) return node.name
                                    return (
                                      <>
                                        <span className="chat-workflow-workspace-edit-branch-code">{code}</span>
                                        <span>{node.name}</span>
                                      </>
                                    )
                                  })()}
                                </span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(selectedWorkflowNodeId, node.id)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowDownstreamDraftNodeId}
                            onChange={(event) => setWorkflowDownstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择下游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`down-decision-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowDownstream}
                            disabled={!workflowDownstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>
                    </>
                  ) : selectedAssistantNodeDetails ? (
                    <>
                      <label className="chat-workflow-workspace-edit-label" htmlFor="workflow-edit-node-name">
                        节点名
                      </label>
                      <input
                        id="workflow-edit-node-name"
                        className="chat-workflow-workspace-edit-node-name-input"
                        value={selectedWorkflowNode?.name ?? ''}
                        onChange={(event) => handleWorkflowNodeNameChange(event.target.value)}
                      />

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点 ID</span>
                        <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                          {selectedWorkflowNode?.id || '-'}
                        </span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点介绍</span>
                        <textarea
                          className="chat-workflow-workspace-edit-role-textarea"
                          value={selectedAssistantNodeDetails.nodeTaskIntro}
                          onChange={(event) => handleWorkflowNodeTaskIntroChange(event.target.value)}
                          placeholder="请输入当前节点要完成的任务说明"
                        />
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">助手名</span>
                        <span className="chat-workflow-workspace-edit-value">{selectedAssistantNodeDetails.assistantDisplayName || '-'}</span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">助手 ID</span>
                        <span className="chat-workflow-workspace-edit-value">{selectedAssistantNodeDetails.assistantId || '-'}</span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">助手服务地址</span>
                        <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                          {selectedAssistantNodeDetails.assistantServiceUrl || '-'}
                        </span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">助手角色介绍</span>
                        <textarea
                          className="chat-workflow-workspace-edit-role-textarea"
                          value={assistantRolePromptDraft}
                          onChange={(event) => setAssistantRolePromptDraft(event.target.value)}
                          placeholder="请输入助手角色介绍"
                        />
                        <button
                          type="button"
                          className="chat-workflow-workspace-edit-inline-btn"
                          onClick={() => void handleSaveAssistantRolePrompt()}
                          disabled={assistantRolePromptSaving}
                        >
                          {assistantRolePromptSaving ? '保存中...' : '保存角色介绍'}
                        </button>
                        {assistantRolePromptSaveHint && (
                          <span className="chat-workflow-workspace-edit-hint">{assistantRolePromptSaveHint}</span>
                        )}
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">上游节点节点名列</span>
                        {selectedWorkflowUpstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowUpstreamNodes.map((node) => (
                              <div key={`up-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span>{node.name}</span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(node.id, selectedWorkflowNodeId)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowUpstreamDraftNodeId}
                            onChange={(event) => setWorkflowUpstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择上游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`up-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowUpstream}
                            disabled={!workflowUpstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">下游节点节点名列</span>
                        {selectedWorkflowDownstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowDownstreamNodes.map((node) => (
                              <div key={`down-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span>{node.name}</span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(selectedWorkflowNodeId, node.id)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowDownstreamDraftNodeId}
                            onChange={(event) => setWorkflowDownstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择下游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`down-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowDownstream}
                            disabled={!workflowDownstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>
                    </>
                  ) : selectedUserNodeDetails ? (
                    <>
                      <label className="chat-workflow-workspace-edit-label" htmlFor="workflow-edit-node-name">
                        节点名
                      </label>
                      <input
                        id="workflow-edit-node-name"
                        className="chat-workflow-workspace-edit-node-name-input"
                        value={selectedWorkflowNode?.name ?? ''}
                        onChange={(event) => handleWorkflowNodeNameChange(event.target.value)}
                      />

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点 ID</span>
                        <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                          {selectedWorkflowNode?.id || '-'}
                        </span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">节点介绍</span>
                        <textarea
                          className="chat-workflow-workspace-edit-role-textarea"
                          value={selectedUserNodeDetails.nodeTaskIntro}
                          onChange={(event) => handleWorkflowNodeTaskIntroChange(event.target.value)}
                          placeholder="请输入当前节点要完成的任务说明"
                        />
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">用户名</span>
                        <span className="chat-workflow-workspace-edit-value">{selectedUserNodeDetails.userNickname || '-'}</span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">用户 IMEI</span>
                        <span className="chat-workflow-workspace-edit-value chat-workflow-workspace-edit-value-mono">
                          {selectedUserNodeDetails.userImei || '-'}
                        </span>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">用户角色介绍</span>
                        <textarea
                          className="chat-workflow-workspace-edit-role-textarea"
                          value={selectedUserNodeDetails.userRoleIntro}
                          onChange={(event) => handleWorkflowUserRoleIntroChange(event.target.value)}
                          placeholder="请输入用户角色介绍"
                        />
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">上游节点节点名列</span>
                        {selectedWorkflowUpstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowUpstreamNodes.map((node) => (
                              <div key={`up-u-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span>{node.name}</span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(node.id, selectedWorkflowNodeId)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowUpstreamDraftNodeId}
                            onChange={(event) => setWorkflowUpstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择上游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`up-user-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowUpstream}
                            disabled={!workflowUpstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>

                      <div className="chat-workflow-workspace-edit-field">
                        <span className="chat-workflow-workspace-edit-label">下游节点节点名列</span>
                        {selectedWorkflowDownstreamNodes.length > 0 ? (
                          <div className="chat-workflow-workspace-edit-list">
                            {selectedWorkflowDownstreamNodes.map((node) => (
                              <div key={`down-u-${node.id}`} className="chat-workflow-workspace-edit-list-item">
                                <span>{node.name}</span>
                                <button
                                  type="button"
                                  className="chat-workflow-workspace-edit-list-remove"
                                  onClick={() => removeWorkflowEdge(selectedWorkflowNodeId, node.id)}
                                >
                                  删除
                                </button>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <span className="chat-workflow-workspace-edit-value">-</span>
                        )}
                        <div className="chat-workflow-workspace-edit-edge-op">
                          <select
                            className="chat-workflow-workspace-edit-select"
                            value={workflowDownstreamDraftNodeId}
                            onChange={(event) => setWorkflowDownstreamDraftNodeId(event.target.value)}
                          >
                            <option value="">选择下游节点</option>
                            {workflowSelectableNeighborNodes.map((node) => (
                              <option key={`down-user-opt-${node.id}`} value={node.id}>{node.name}</option>
                            ))}
                          </select>
                          <button
                            type="button"
                            className="chat-workflow-workspace-edit-inline-btn"
                            onClick={handleAddWorkflowDownstream}
                            disabled={!workflowDownstreamDraftNodeId}
                          >
                            增加
                          </button>
                        </div>
                      </div>
                    </>
                  ) : (
                    <div className="chat-workflow-workspace-edit-placeholder">
                      {workflowEditTarget ? '当前对象无可编辑详情' : '双击节点或连线进入编辑模式'}
                    </div>
                  )}
                </div>
                <div className="chat-workflow-workspace-edit-actions-bottom">
                  <button
                    type="button"
                    className="chat-workflow-workspace-edit-collapse"
                    onClick={() => setWorkflowEditTarget(null)}
                  >
                    收起
                  </button>
                </div>
              </div>
              <div
                className="chat-workflow-workspace-stage"
                style={{
                  width: `${WORKFLOW_STAGE_WIDTH}px`,
                  height: `${WORKFLOW_STAGE_HEIGHT}px`,
                  transform: `translate(${workflowViewport.offsetX}px, ${workflowViewport.offsetY}px) scale(${workflowViewport.scale})`,
                }}
              >
                <svg className="chat-workflow-workspace-connections" aria-hidden="true">
                  <defs>
                    <marker
                      id="chat-workflow-workspace-arrowhead"
                      markerWidth="8"
                      markerHeight="8"
                      refX="7"
                      refY="4"
                      orient="auto"
                      markerUnits="strokeWidth"
                    >
                      <path d="M0,0 L8,4 L0,8 Z" fill="currentColor" />
                    </marker>
                  </defs>
                  {workflowCanvasEdges.map((edge) => {
                    const from = getWorkflowNodeAnchor(edge.fromNodeId, 'right')
                    const to = getWorkflowNodeAnchor(edge.toNodeId, 'left')
                    if (!from || !to) return null
                    const toBackoff = pullBackWorkflowPoint(from, to, WORKFLOW_EDGE_ARROW_BACKOFF)
                    const path = buildWorkflowEdgePath(from, toBackoff)
                    const edgeCode = workflowDecisionEdgeCodeMap[edge.id]
                    const codePosX = (from.x + toBackoff.x) / 2
                    const codePosY = (from.y + toBackoff.y) / 2 - 6
                    const edgeSelected = workflowSelectedTarget?.type === 'edge' && workflowSelectedTarget.id === edge.id
                    return (
                      <g key={edge.id}>
                        <path
                          d={path}
                          className={`chat-workflow-workspace-connection-line ${edgeSelected ? 'selected' : ''}`}
                          markerEnd="url(#chat-workflow-workspace-arrowhead)"
                        />
                        {edgeCode && (
                          <text
                            x={codePosX}
                            y={codePosY}
                            className="chat-workflow-workspace-connection-code"
                            textAnchor="middle"
                          >
                            {edgeCode}
                          </text>
                        )}
                        <path
                          d={path}
                          className="chat-workflow-workspace-connection-hit"
                          onClick={() => {
                            setWorkflowSelectedTarget({ type: 'edge', id: edge.id })
                            setWorkflowEditTarget(null)
                          }}
                          onDoubleClick={() => {
                            setWorkflowSelectedTarget({ type: 'edge', id: edge.id })
                            setWorkflowEditTarget({ type: 'edge', id: edge.id })
                          }}
                          onContextMenu={(event) => handleWorkflowEdgeContextMenu(event, edge.id)}
                        />
                      </g>
                    )
                  })}
                  {workflowConnecting && (() => {
                    const from = getWorkflowNodeAnchor(workflowConnecting.fromNodeId, 'right')
                    if (!from) return null
                    const previewPath = buildWorkflowEdgePath(from, { x: workflowConnecting.toX, y: workflowConnecting.toY })
                    return (
                      <path
                        d={previewPath}
                        className="chat-workflow-workspace-connection-preview"
                        markerEnd="url(#chat-workflow-workspace-arrowhead)"
                      />
                    )
                  })()}
                </svg>
                {workflowCanvasNodes.map((node) => (
                  <div
                    key={node.id}
                    className={`chat-workflow-workspace-node chat-workflow-workspace-node-${node.type} ${
                      workflowSelectedTarget?.type === 'node' && workflowSelectedTarget.id === node.id ? 'is-selected' : ''
                    }`}
                    style={{ left: `${node.x}px`, top: `${node.y}px` }}
                    title={node.name}
                    onMouseDown={(event) => handleWorkflowNodeMouseDown(event, node.id)}
                    onMouseUp={() => handleWorkflowNodeMouseUp(node.id)}
                    onDoubleClick={() => {
                      setWorkflowSelectedTarget({ type: 'node', id: node.id })
                      setWorkflowEditTarget({ type: 'node', id: node.id })
                    }}
                    onContextMenu={(event) => handleWorkflowNodeContextMenu(event, node.id)}
                  >
                    {workflowRunNodeStatusMap[node.id] === 'running' && (
                      <span className="chat-workflow-workspace-node-status chat-workflow-workspace-node-status-running" aria-label="执行中" />
                    )}
                    {workflowRunNodeStatusMap[node.id] === 'success' && (
                      <span className="chat-workflow-workspace-node-status chat-workflow-workspace-node-status-success" aria-label="执行完成">
                        ✓
                      </span>
                    )}
                    {workflowRunNodeStatusMap[node.id] === 'failed' && (
                      <span className="chat-workflow-workspace-node-status chat-workflow-workspace-node-status-failed" aria-label="执行失败">
                        !
                      </span>
                    )}
                    <span className="chat-workflow-workspace-node-port chat-workflow-workspace-node-port-in" aria-hidden />
                    <span className="chat-workflow-workspace-node-name">{node.name}</span>
                    <button
                      type="button"
                      className="chat-workflow-workspace-node-port chat-workflow-workspace-node-port-out"
                      aria-label={`从${node.name}开始连线`}
                      title="拖拽连线到下游节点"
                      onMouseDown={(event) => handleWorkflowEdgeStart(event, node.id)}
                    />
                  </div>
                ))}
              </div>
              {workflowCanvasNodes.length === 0 && (
                <div className="chat-workflow-workspace-canvas-empty">编排区</div>
              )}
              {workflowRunLogOpen && (
                <div className="chat-workflow-runlog-panel" aria-label="执行日志">
                  <div className="chat-workflow-runlog-header">
                    <span>执行日志</span>
                    <button
                      type="button"
                      className="chat-workflow-runlog-clear"
                      onClick={() => setWorkflowRunLogs([])}
                      disabled={workflowRunInProgress || workflowRunLogs.length === 0}
                    >
                      清空
                    </button>
                  </div>
                  <div className="chat-workflow-runlog-list">
                    {workflowRunLogs.length === 0 ? (
                      <div className="chat-workflow-runlog-empty">暂无日志</div>
                    ) : (
                      workflowRunLogs.map((item) => (
                        <div key={item.id} className={`chat-workflow-runlog-item chat-workflow-runlog-item-${item.level}`}>
                          <span className="chat-workflow-runlog-time">
                            {new Date(item.ts).toLocaleTimeString()}
                          </span>
                          <span className="chat-workflow-runlog-text">{item.text}</span>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}
            </section>
            <aside className="chat-workflow-workspace-members" aria-label="群成员列表">
              <div className="chat-workflow-workspace-members-header">
                <h4 className="chat-workflow-workspace-members-title">群成员列表</h4>
                {workflowCanAddMembers && (
                  <button
                    type="button"
                    className={`chat-workflow-workspace-members-add-toggle ${workflowAddMemberOpen ? 'open' : ''}`}
                    onClick={() => {
                      setWorkflowAddMemberOpen((prev) => !prev)
                      setWorkflowAddMemberHint('')
                    }}
                  >
                    添加成员
                  </button>
                )}
              </div>
              {workflowCanAddMembers && workflowAddMemberOpen && (
                <div className="chat-workflow-workspace-members-add-panel">
                  <div className="chat-workflow-workspace-members-add-type-row">
                    <label>
                      <input
                        type="radio"
                        checked={workflowAddMemberType === 'friend'}
                        onChange={() => setWorkflowAddMemberType('friend')}
                      />
                      <span>好友</span>
                    </label>
                    <label>
                      <input
                        type="radio"
                        checked={workflowAddMemberType === 'assistant'}
                        onChange={() => setWorkflowAddMemberType('assistant')}
                      />
                      <span>助手</span>
                    </label>
                  </div>
                  <div className="chat-workflow-workspace-members-add-input-row">
                    <select
                      className="chat-workflow-workspace-members-add-select"
                      value={workflowAddMemberTargetId}
                      onChange={(event) => setWorkflowAddMemberTargetId(event.target.value)}
                    >
                      <option value="">请选择</option>
                      {workflowAddMemberType === 'friend'
                        ? workflowAddableFriends.map((friend) => (
                            <option key={`wf-friend-${friend.imei}`} value={friend.imei}>
                              {friend.nickname || friend.imei}
                            </option>
                          ))
                        : workflowAddableAssistants.map((assistant) => (
                            <option key={`wf-assistant-${assistant.id}`} value={assistant.id}>
                              {assistant.name}
                            </option>
                          ))}
                    </select>
                    <button
                      type="button"
                      className="chat-workflow-workspace-members-add-btn"
                      onClick={() => void handleWorkflowAddGroupMember()}
                      disabled={
                        workflowAddMemberSaving
                        || !workflowAddMemberTargetId
                        || (workflowAddMemberType === 'friend' && workflowAddableFriends.length === 0)
                        || (workflowAddMemberType === 'assistant' && workflowAddableAssistants.length === 0)
                      }
                    >
                      {workflowAddMemberSaving ? '添加中...' : '添加'}
                    </button>
                  </div>
                  {workflowAddMemberHint && (
                    <div className="chat-workflow-workspace-members-add-hint">{workflowAddMemberHint}</div>
                  )}
                </div>
              )}
              <div className="chat-workflow-workspace-members-list">
                {workflowWorkspace.members.map((item) => (
                  <div
                    key={item.id}
                    className="chat-workflow-workspace-member-item"
                    draggable
                    onDragStart={(event) => handleWorkflowMemberDragStart(event, item)}
                    onClick={() => handleWorkflowMemberClick(item)}
                  >
                    {/** 头像文本使用昵称首字，背景色继续区分助手/用户 */}
                    {(() => {
                      const initial = Array.from((item.name || '').trim())[0] || '?'
                      return (
                    <span
                      className={`chat-workflow-workspace-member-type-icon ${
                        item.type === 'assistant'
                          ? 'chat-workflow-workspace-member-type-icon-assistant'
                          : 'chat-workflow-workspace-member-type-icon-user'
                      }`}
                      title={item.type === 'assistant' ? '助手' : '用户'}
                      aria-label={item.type === 'assistant' ? '助手' : '用户'}
                    >
                      {initial}
                    </span>
                      )
                    })()}
                    <span className="chat-workflow-workspace-member-name">{item.name}</span>
                  </div>
                ))}
              </div>
            </aside>
          </div>
        </div>
      )}
      {chatInputContextMenu && (
        <div
          className="chat-input-context-menu"
          style={{
            left: Math.min(
              chatInputContextMenu.x,
              (typeof window !== 'undefined' ? window.innerWidth : 800) - 176
            ),
            top: Math.min(
              chatInputContextMenu.y,
              (typeof window !== 'undefined' ? window.innerHeight : 600) - 228
            ),
          }}
          role="menu"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={() => void handleChatInputMenuCut()}
          >
            剪切
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={() => void handleChatInputMenuCopy()}
          >
            复制
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleChatInputMenuPaste}
          >
            粘贴
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleChatInputMenuSelectAll}
          >
            全选
          </button>
        </div>
      )}
      {messageContextMenu && (
        <div
          className="chat-message-context-menu"
          style={{
            left: Math.min(
              messageContextMenu.x,
              (typeof window !== 'undefined' ? window.innerWidth : 800) - 220
            ),
            top: Math.min(
              messageContextMenu.y,
              (typeof window !== 'undefined' ? window.innerHeight : 600) - (messageContextMenu.tableCsvText ? 346 : 306)
            ),
          }}
          role="menu"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={() => void handleMessageCopy()}
          >
            复制
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleMessageQuote}
          >
            引用
          </button>
          {messageContextMenu.tableCsvText && (
            <button
              type="button"
              className="chat-session-context-menu-item"
              role="menuitem"
              onMouseDown={(e) => {
                e.preventDefault()
                e.stopPropagation()
              }}
              onClick={handleMessageEditTable}
            >
              编辑表格
            </button>
          )}
          {messageContextMenu.tableCsvText && (
            <button
              type="button"
              className="chat-session-context-menu-item"
              role="menuitem"
              onMouseDown={(e) => {
                e.preventDefault()
                e.stopPropagation()
              }}
              onClick={() => void handleMessageSaveCsv()}
            >
              保存到本地（CSV）
            </button>
          )}
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleMessageSelectAll}
          >
            全选
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleMessageMultiSelect}
          >
            多选
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleMessageForwardSingle}
          >
            转发
          </button>
          <button
            type="button"
            className="chat-session-context-menu-item"
            role="menuitem"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleMessageAddQuickNote}
          >
            记入随手记
          </button>
        </div>
      )}
      {sessionContextMenu && (
        <div
          className="chat-session-context-menu"
          style={{ left: sessionContextMenu.x, top: sessionContextMenu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="chat-session-context-menu-item chat-session-context-menu-item-danger"
            onClick={() => handleDeleteSession(sessionContextMenu.sessionId)}
          >
            删除
          </button>
        </div>
      )}
      {workflowContextMenu && (
        <div
          className="chat-session-context-menu"
          style={{ left: workflowContextMenu.x, top: workflowContextMenu.y }}
          onClick={(e) => e.stopPropagation()}
        >
          {workflowContextMenu.target === 'node' && (
            <button
              type="button"
              className="chat-session-context-menu-item"
              onMouseDown={(e) => {
                e.preventDefault()
                e.stopPropagation()
              }}
              onClick={handleWorkflowContextMenuEdit}
            >
              编辑
            </button>
          )}
          <button
            type="button"
            className="chat-session-context-menu-item chat-session-context-menu-item-danger"
            onMouseDown={(e) => {
              e.preventDefault()
              e.stopPropagation()
            }}
            onClick={handleWorkflowContextMenuDelete}
          >
            删除
          </button>
        </div>
      )}
      {shareCardPreview && (
        <div className="chat-share-card-modal-mask" onClick={() => setShareCardPreview(null)}>
          <div className="chat-share-card-modal" role="dialog" aria-label="助手主页" onClick={(e) => e.stopPropagation()}>
            <div className="chat-share-card-modal-title">助手主页</div>
            <div className="chat-share-card-modal-name">{shareCardPreview.name}</div>
            <div className="chat-share-card-modal-likes">点赞数：{Number(shareCardPreview.likesCount || 0)}</div>
            <div className="chat-share-card-modal-url" title={shareCardPreview.baseUrl}>{shareCardPreview.baseUrl}</div>
            <div className="chat-share-card-modal-intro">{shareCardPreview.intro || '暂无简介'}</div>
            <div className="chat-share-card-modal-actions">
              <button type="button" className="chat-share-card-modal-btn" onClick={() => setShareCardPreview(null)}>
                关闭
              </button>
              <button
                type="button"
                className="chat-share-card-modal-btn chat-share-card-modal-btn-primary"
                disabled={addingShareAssistant}
                onClick={() => { void handleAddAssistantFromShareCard(shareCardPreview) }}
              >
                {addingShareAssistant ? '添加中...' : '添加到我的助手'}
              </button>
            </div>
          </div>
        </div>
      )}
      {shareSkillPreview && (
        <div className="chat-share-card-modal-mask" onClick={() => setShareSkillPreview(null)}>
          <div className="chat-share-card-modal" role="dialog" aria-label="技能卡片" onClick={(e) => e.stopPropagation()}>
            <div className="chat-share-card-modal-title">{shareSkillPreview.packageBase64 ? '技能包' : '技能卡片'}</div>
            <div className="chat-share-card-modal-name">{shareSkillPreview.title}</div>
            <div className="chat-share-card-modal-intro">{shareSkillPreview.originalPurpose || '暂无简介'}</div>
            {shareSkillPreview.packageFileName && (
              <div className="chat-share-card-modal-skill-steps">文件：{shareSkillPreview.packageFileName}</div>
            )}
            {!!shareSkillPreview.steps?.length && (
              <div className="chat-share-card-modal-skill-steps">
                步骤：{shareSkillPreview.steps.slice(0, 3).join('；')}{shareSkillPreview.steps.length > 3 ? '…' : ''}
              </div>
            )}
            <div className="chat-share-card-modal-actions">
              <button type="button" className="chat-share-card-modal-btn" onClick={() => setShareSkillPreview(null)}>
                关闭
              </button>
              <button
                type="button"
                className="chat-share-card-modal-btn chat-share-card-modal-btn-primary"
                disabled={addingShareSkill}
                onClick={() => { void handleAddSkillFromShareCard(shareSkillPreview) }}
              >
                {addingShareSkill ? '添加中...' : '添加到我的技能'}
              </button>
            </div>
          </div>
        </div>
      )}
      {mobileStatusModalOpen && (
        <div className="chat-status-check-modal-mask" onClick={() => setMobileStatusModalOpen(false)}>
          <div className="chat-status-check-modal" role="dialog" aria-label="状态检测" onClick={(e) => e.stopPropagation()}>
            <div className="chat-status-check-modal-title">状态检测</div>
            <div className="chat-status-check-modal-row">
              <span>1. 手机是否在线</span>
              <span className={`chat-status-check-badge is-${mobileOnlineStep}`}>
                {mobileOnlineStep === 'idle' ? '未开始' : mobileOnlineStep === 'checking' ? '检测中' : mobileOnlineStep === 'success' ? '通过' : '失败'}
              </span>
            </div>
            <div className="chat-status-check-modal-row">
              <span>2. 手机是否可接收 gui_task</span>
              <span className={`chat-status-check-badge is-${mobileGuiTaskStep}`}>
                {mobileGuiTaskStep === 'idle' ? '未开始' : mobileGuiTaskStep === 'checking' ? '检测中' : mobileGuiTaskStep === 'success' ? '通过' : '失败'}
              </span>
            </div>
            <div className="chat-status-check-modal-hint">
              {mobileStatusCheckHint || (mobileStatusChecking ? '正在检测，请稍候...' : '可点击“重新检测”再次执行')}
            </div>
            <div className="chat-status-check-modal-actions">
              <button type="button" className="chat-share-card-modal-btn" onClick={() => setMobileStatusModalOpen(false)}>
                关闭
              </button>
              <button
                type="button"
                className="chat-share-card-modal-btn chat-share-card-modal-btn-primary"
                onClick={() => { void handleRunMobileStatusCheck() }}
                disabled={mobileStatusChecking}
              >
                {mobileStatusChecking ? '检测中...' : '重新检测'}
              </button>
            </div>
          </div>
        </div>
      )}
      {modelAlignRetryModalOpen && (
        <div className="chat-status-check-modal-mask" onClick={() => setModelAlignRetryModalOpen(false)}>
          <div className="chat-status-check-modal" role="dialog" aria-label="模型切换提示" onClick={(e) => e.stopPropagation()}>
            <div className="chat-status-check-modal-title">模型切换中</div>
            <div className="chat-status-check-modal-hint">
              {modelAlignRetryHint}
            </div>
            <div className="chat-status-check-modal-actions">
              <button
                type="button"
                className="chat-share-card-modal-btn chat-share-card-modal-btn-primary"
                onClick={() => setModelAlignRetryModalOpen(false)}
              >
                我知道了
              </button>
            </div>
          </div>
        </div>
      )}
      <ChatImageLightbox payload={imageLightbox} onClose={() => setImageLightbox(null)} />
    </div>
  )
}
