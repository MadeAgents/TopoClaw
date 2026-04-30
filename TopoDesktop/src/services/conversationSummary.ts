import { ensureBuiltinAssistantStarted, getDefaultBuiltinUrl } from './builtinAssistantConfig'
import { loadMessages, loadMessagesForGroup, type StoredMessage } from './messageStorage'
import { sendChatViaWebSocket } from './chatWebSocket'
import { getImei } from './storage'
import { syncConversationSummaries } from './api'

type ScopeType = 'friend' | 'group'

interface SummaryState {
  epochMs: number
  scopes: Record<string, { cursorTs: number }>
}

type SummaryMessageLike = StoredMessage & {
  messageSource?: 'user' | 'friend' | 'my_clone' | 'friend_clone' | 'assistant'
  cloneOwnerImei?: string
}

export interface ConversationSummaryEntry {
  id: string
  schema: 'v1'
  userImei: string
  scopeType: ScopeType
  scopeId: string
  scopeName: string
  createdAt: number
  messageStartTs: number
  messageEndTs: number
  messageCount: number
  roundCount: number
  summary: string
}

const STATE_KEY = 'topodesktop_conversation_summary_state_v1'
const CLOUD_SYNC_TS_KEY = 'topodesktop_conversation_summary_cloud_sync_ts_v1'
const PENDING_UPLOAD_KEY = 'topodesktop_conversation_summary_pending_upload_v1'
const FILE_NAME = 'CONVERSATION_SUMMARIES.md'
const ENTRY_PREFIX = '<!-- TOPO_SUMMARY_ENTRY:'
const ENTRY_SUFFIX = ' -->'
const MESSAGE_BATCH_COUNT = 10
const ROUND_COUNT = 5
const runningScopes = new Set<string>()
let writeQueue: Promise<void> = Promise.resolve()
let cloudSyncInFlight: Promise<void> | null = null

function debugLog(message: string, detail?: unknown): void {
  try {
    console.info(`[ConversationSummary] ${message}`, detail ?? '')
  } catch {
    // ignore
  }
}

type ElectronSummaryBridge = {
  readConversationSummaryFile?: () => Promise<{ ok: boolean; content?: string; path?: string; error?: string }>
  writeConversationSummaryFile?: (content: string) => Promise<{ ok: boolean; path?: string; error?: string }>
}

function getBridge(): ElectronSummaryBridge | undefined {
  return (window as unknown as { electronAPI?: ElectronSummaryBridge }).electronAPI
}

function nowTs(): number {
  return Date.now()
}

function toScopeTypeAndId(conversationId: string): { scopeType: ScopeType; scopeId: string } | null {
  if (conversationId.startsWith('friend_')) {
    const scopeId = conversationId.replace(/^friend_/, '').trim()
    return scopeId ? { scopeType: 'friend', scopeId } : null
  }
  if (conversationId === 'group') return null
  if (conversationId.startsWith('group_')) {
    let raw = conversationId.trim()
    while (raw.startsWith('group_')) raw = raw.slice('group_'.length)
    const scopeId = raw.trim()
    return scopeId ? { scopeType: 'group', scopeId } : null
  }
  return null
}

function scopeKey(scopeType: ScopeType, scopeId: string): string {
  return `${scopeType}:${scopeId}`
}

function loadState(): SummaryState {
  try {
    const raw = localStorage.getItem(STATE_KEY)
    if (!raw) {
      const initial: SummaryState = { epochMs: nowTs(), scopes: {} }
      localStorage.setItem(STATE_KEY, JSON.stringify(initial))
      return initial
    }
    const parsed = JSON.parse(raw) as Partial<SummaryState>
    const epochMs = typeof parsed.epochMs === 'number' && Number.isFinite(parsed.epochMs) ? parsed.epochMs : nowTs()
    const scopes = parsed.scopes && typeof parsed.scopes === 'object' ? parsed.scopes : {}
    return { epochMs, scopes }
  } catch {
    return { epochMs: nowTs(), scopes: {} }
  }
}

function saveState(next: SummaryState): void {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(next))
  } catch {
    // ignore
  }
}

function getScopeMessages(conversationId: string): StoredMessage[] {
  if (conversationId.startsWith('group_')) return loadMessagesForGroup(conversationId)
  return loadMessages(conversationId)
}

function normalizeMessages(messages: StoredMessage[]): StoredMessage[] {
  return messages
    .filter((m) => m && typeof m.timestamp === 'number' && m.timestamp > 0)
    .filter((m) => (m.content || '').trim().length > 0)
    .filter((m) => m.type !== 'system')
    .sort((a, b) => a.timestamp - b.timestamp)
}

function summaryRoleLabel(m: SummaryMessageLike): string {
  const role = String(m.sender || '').trim() || (m.type === 'assistant' ? '助手' : '用户')
  if (m.messageSource === 'my_clone') {
    return m.cloneOwnerImei ? `${role}(我的数字分身:${m.cloneOwnerImei})` : `${role}(我的数字分身)`
  }
  if (m.messageSource === 'friend_clone') {
    return m.cloneOwnerImei ? `${role}(好友数字分身:${m.cloneOwnerImei})` : `${role}(好友数字分身)`
  }
  return role
}

function buildSummaryPrompt(scopeType: ScopeType, scopeName: string, batch: StoredMessage[]): string {
  const lines = batch.map((raw) => {
    const m = raw as SummaryMessageLike
    const role = summaryRoleLabel(m)
    const content = String(m.content || '').trim()
    return `- ${role}: ${content}`
  })
  return [
    '请基于以下最近 5 轮对话生成摘要。',
    `会话类型：${scopeType === 'friend' ? '好友' : '群组'}`,
    `会话名称：${scopeName || '-'}`,
    '输出要求：',
    '1) 使用中文。',
    '2) 控制在 6 条以内的短要点。',
    '3) 聚焦事实、用户偏好、待办和风险提醒。',
    '4) 不要输出 JSON，不要输出多余寒暄。',
    '5) 若消息标注了“数字分身”，请在摘要中明确是“我的分身”还是“好友分身”，避免混淆真人发言。',
    '',
    '对话片段：',
    ...lines,
  ].join('\n')
}

function formatTs(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function ensureFileHeader(existing: string): string {
  if (existing.trim()) return existing
  return [
    '# 会话记录摘要（自动生成）',
    '',
    '> 说明：本文件为 append-only 存档，后写入的同会话摘要为最新可用版本。',
    '> 作用域：仅好友与群组会话；不同会话互不影响。',
    '',
    '<!-- TOPO_SUMMARY_SCHEMA:v1 -->',
    '',
  ].join('\n')
}

function buildEntryBlock(entry: ConversationSummaryEntry): string {
  const header = `${ENTRY_PREFIX}${JSON.stringify(entry)}${ENTRY_SUFFIX}`
  const title = `### ${formatTs(entry.createdAt)} | ${entry.scopeType === 'friend' ? '好友' : '群组'} | ${entry.scopeName}`
  return [
    '',
    header,
    title,
    '',
    entry.summary.trim(),
    '',
  ].join('\n')
}

async function readSummaryFile(): Promise<{ content: string; path?: string }> {
  const api = getBridge()
  if (!api?.readConversationSummaryFile) return { content: '' }
  const res = await api.readConversationSummaryFile()
  if (!res?.ok) throw new Error(res?.error || '读取会话摘要文件失败')
  return { content: String(res.content || ''), path: res.path }
}

async function writeSummaryFile(content: string): Promise<void> {
  const api = getBridge()
  if (!api?.writeConversationSummaryFile) throw new Error('当前环境不支持写入会话摘要文件')
  const res = await api.writeConversationSummaryFile(content)
  if (!res?.ok) throw new Error(res?.error || '写入会话摘要文件失败')
}

async function summarizeByTopoClaw(scopeType: ScopeType, scopeName: string, batch: StoredMessage[]): Promise<string> {
  const started = await ensureBuiltinAssistantStarted()
  if (!started.ok) throw new Error(started.error || 'TopoClaw 未启动')
  const baseUrl = await getDefaultBuiltinUrl('topoclaw')
  const summaryThread = `summary_${scopeType}_${Date.now()}`
  const prompt = buildSummaryPrompt(scopeType, scopeName, batch)
  const result = await sendChatViaWebSocket(
    baseUrl,
    { thread_id: summaryThread, message: prompt },
    { onDelta: () => {} },
    AbortSignal.timeout(60_000)
  )
  const text = String(result.fullText || '').trim()
  if (!text) throw new Error('TopoClaw 返回空摘要')
  return text
}

function makeEntry(scopeType: ScopeType, scopeId: string, scopeName: string, batch: StoredMessage[], summary: string): ConversationSummaryEntry {
  const userImei = (getImei() || '').trim() || 'unknown'
  const createdAt = nowTs()
  const startTs = batch[0]?.timestamp ?? createdAt
  const endTs = batch[batch.length - 1]?.timestamp ?? createdAt
  const id = `sum_${scopeType}_${scopeId}_${endTs}_${createdAt}`
  return {
    id,
    schema: 'v1',
    userImei,
    scopeType,
    scopeId,
    scopeName: scopeName || scopeId,
    createdAt,
    messageStartTs: startTs,
    messageEndTs: endTs,
    messageCount: batch.length,
    roundCount: ROUND_COUNT,
    summary: summary.trim(),
  }
}

function parseEntriesFromContent(content: string): ConversationSummaryEntry[] {
  const out: ConversationSummaryEntry[] = []
  const pattern = /<!-- TOPO_SUMMARY_ENTRY:(.*?) -->/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(content)) != null) {
    const json = String(match[1] || '').trim()
    if (!json) continue
    try {
      const parsed = JSON.parse(json) as ConversationSummaryEntry
      if (!parsed?.scopeType || !parsed.scopeId || !parsed.id) continue
      if (typeof parsed.createdAt !== 'number' || parsed.createdAt <= 0) continue
      out.push(parsed)
    } catch {
      // ignore invalid rows
    }
  }
  return out
}

function dedupeById(entries: ConversationSummaryEntry[]): ConversationSummaryEntry[] {
  const m = new Map<string, ConversationSummaryEntry>()
  for (const e of entries) {
    const id = String(e.id || '').trim()
    if (!id) continue
    m.set(id, e)
  }
  return [...m.values()]
}

function loadPendingEntries(): ConversationSummaryEntry[] {
  try {
    const raw = localStorage.getItem(PENDING_UPLOAD_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw) as ConversationSummaryEntry[]
    return Array.isArray(arr) ? dedupeById(arr) : []
  } catch {
    return []
  }
}

function savePendingEntries(entries: ConversationSummaryEntry[]): void {
  try {
    localStorage.setItem(PENDING_UPLOAD_KEY, JSON.stringify(dedupeById(entries)))
  } catch {
    // ignore
  }
}

function queuePendingEntry(entry: ConversationSummaryEntry): void {
  const current = loadPendingEntries()
  current.push(entry)
  savePendingEntries(current)
  debugLog('queue pending entry', {
    entryId: entry.id,
    scopeType: entry.scopeType,
    scopeId: entry.scopeId,
    pendingCount: current.length,
  })
}

function loadCloudSyncTs(): number {
  try {
    const raw = localStorage.getItem(CLOUD_SYNC_TS_KEY)
    const ts = raw ? Number(raw) : 0
    return Number.isFinite(ts) && ts > 0 ? ts : 0
  } catch {
    return 0
  }
}

function saveCloudSyncTs(ts: number): void {
  if (!Number.isFinite(ts) || ts <= 0) return
  try {
    localStorage.setItem(CLOUD_SYNC_TS_KEY, String(Math.floor(ts)))
  } catch {
    // ignore
  }
}

function normalizeCloudEntry(raw: ConversationSummaryEntry, currentImei: string): ConversationSummaryEntry | null {
  const id = String(raw?.id || '').trim()
  const scopeType = raw?.scopeType
  const scopeId = String(raw?.scopeId || '').trim()
  const createdAt = Number(raw?.createdAt || 0)
  const summary = String(raw?.summary || '').trim()
  if (!id || !scopeId || (scopeType !== 'friend' && scopeType !== 'group') || !Number.isFinite(createdAt) || createdAt <= 0) return null
  return {
    id,
    schema: 'v1',
    userImei: String(raw?.userImei || '').trim() || currentImei,
    scopeType,
    scopeId,
    scopeName: String(raw?.scopeName || '').trim() || scopeId,
    createdAt,
    messageStartTs: Number(raw?.messageStartTs || 0),
    messageEndTs: Number(raw?.messageEndTs || 0),
    messageCount: Number(raw?.messageCount || 0),
    roundCount: Number(raw?.roundCount || ROUND_COUNT),
    summary,
  }
}

async function appendEntriesLocal(entries: ConversationSummaryEntry[]): Promise<void> {
  const cleanEntries = dedupeById(entries)
  if (cleanEntries.length === 0) return
  writeQueue = writeQueue.then(async () => {
    const { content } = await readSummaryFile()
    const existingContent = ensureFileHeader(content)
    const existing = parseEntriesFromContent(existingContent)
    const existingIds = new Set(existing.map((x) => x.id))
    let appendText = ''
    for (const entry of cleanEntries.sort((a, b) => a.createdAt - b.createdAt)) {
      if (existingIds.has(entry.id)) continue
      appendText += buildEntryBlock(entry)
      existingIds.add(entry.id)
    }
    if (!appendText) return
    const next = existingContent + appendText
    await writeSummaryFile(next)
  })
  return writeQueue
}

async function syncConversationSummariesWithCloudInternal(): Promise<void> {
  const imei = (getImei() || '').trim()
  if (!imei) return
  const pending = loadPendingEntries()
  const sinceTs = loadCloudSyncTs()
  debugLog('start cloud sync', {
    imei: `${imei.slice(0, 8)}...`,
    pendingCount: pending.length,
    sinceTs,
  })
  const res = await syncConversationSummaries(imei, {
    entries: pending,
    sinceTs,
    limit: 5000,
  })
  if (!res.success) {
    debugLog('cloud sync not successful', { pendingCount: pending.length, sinceTs })
    return
  }
  const remoteEntries = (res.entries || [])
    .map((x) => normalizeCloudEntry(x as ConversationSummaryEntry, imei))
    .filter((x): x is ConversationSummaryEntry => !!x)
  debugLog('cloud sync success', {
    accepted: res.accepted,
    uploaded: res.uploaded,
    returned: remoteEntries.length,
  })
  if (remoteEntries.length > 0) {
    await appendEntriesLocal(remoteEntries)
  }
  savePendingEntries([])
  let maxTs = sinceTs
  for (const e of pending) {
    if (e.createdAt > maxTs) maxTs = e.createdAt
  }
  for (const e of remoteEntries) {
    if (e.createdAt > maxTs) maxTs = e.createdAt
  }
  saveCloudSyncTs(maxTs)
  debugLog('finish cloud sync', { nextSinceTs: maxTs, pendingCleared: true })
}

export async function syncConversationSummariesWithCloud(): Promise<void> {
  if (cloudSyncInFlight) return cloudSyncInFlight
  cloudSyncInFlight = syncConversationSummariesWithCloudInternal().finally(() => {
    cloudSyncInFlight = null
  })
  return cloudSyncInFlight
}

export async function maybeGenerateConversationSummary(params: {
  conversationId: string
  conversationName?: string
}): Promise<void> {
  const scope = toScopeTypeAndId(params.conversationId)
  if (!scope) return
  const key = scopeKey(scope.scopeType, scope.scopeId)
  if (runningScopes.has(key)) return
  runningScopes.add(key)
  try {
    const state = loadState()
    const cursorTs = state.scopes[key]?.cursorTs ?? state.epochMs
    const all = normalizeMessages(getScopeMessages(params.conversationId))
    const candidates = all.filter((m) => m.timestamp > cursorTs && m.timestamp >= state.epochMs)
    if (candidates.length < MESSAGE_BATCH_COUNT) {
      debugLog('skip summary: not enough messages', {
        conversationId: params.conversationId,
        scopeType: scope.scopeType,
        scopeId: scope.scopeId,
        cursorTs,
        candidateCount: candidates.length,
        required: MESSAGE_BATCH_COUNT,
      })
      return
    }
    const batch = candidates.slice(0, MESSAGE_BATCH_COUNT)
    const scopeName = (params.conversationName || '').trim() || scope.scopeId
    debugLog('generate summary', {
      conversationId: params.conversationId,
      scopeType: scope.scopeType,
      scopeId: scope.scopeId,
      scopeName,
      batchCount: batch.length,
      firstTs: batch[0]?.timestamp,
      lastTs: batch[batch.length - 1]?.timestamp,
    })
    const summary = await summarizeByTopoClaw(scope.scopeType, scopeName, batch)
    const entry = makeEntry(scope.scopeType, scope.scopeId, scopeName, batch, summary)
    await appendEntriesLocal([entry])
    queuePendingEntry(entry)
    void syncConversationSummariesWithCloud()
    const endTs = batch[batch.length - 1]?.timestamp ?? cursorTs
    state.scopes[key] = { cursorTs: endTs }
    saveState(state)
    debugLog('summary generated', {
      entryId: entry.id,
      scopeType: entry.scopeType,
      scopeId: entry.scopeId,
      endTs,
    })
  } catch (e) {
    console.warn('[ConversationSummary] 生成失败', e, {
      conversationId: params.conversationId,
      conversationName: params.conversationName,
    })
  } finally {
    runningScopes.delete(key)
  }
}

export async function listConversationSummaries(params: {
  scopeType: ScopeType
  scopeId: string
}): Promise<{ entries: ConversationSummaryEntry[]; latest: ConversationSummaryEntry | null }> {
  try {
    void syncConversationSummariesWithCloud()
    const { content } = await readSummaryFile()
    const all = parseEntriesFromContent(content)
    const entries = all
      .filter((x) => x.scopeType === params.scopeType && x.scopeId === params.scopeId)
      .sort((a, b) => b.createdAt - a.createdAt)
    return { entries, latest: entries[0] ?? null }
  } catch {
    return { entries: [], latest: null }
  }
}

export function getConversationSummaryFileName(): string {
  return FILE_NAME
}
