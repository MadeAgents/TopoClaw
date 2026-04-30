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

import { CRON_JOBS_PATH, cronJobDeletePath } from '../config/scheduleApi'
import { getColorClawService } from './api'
import type { CreateScheduledJobPayload, ScheduledJob, ScheduledJobStatus } from '../types/scheduledJob'
import { validateSchedulePayload } from '../types/scheduledJob'

/**
 * Mock 开关：
 * - VITE_MOCK_SCHEDULED_JOBS=true：强制 Mock（不发起任何 HTTP/IPC）。
 * - 开发模式（Vite DEV）且未显式 VITE_USE_REAL_SCHEDULE_API=true：若渲染进程**没有** colorClawService（纯浏览器开页面），用 Mock；
 *   **Electron 桌面端**已注入 get/post/delete 时默认走真实接口，与「我的技能」同链路，无需再设环境变量。
 * - 生产构建：始终走真实接口（除非 VITE_MOCK_SCHEDULED_JOBS）。
 */
function hasColorClawScheduleBridge(): boolean {
  if (typeof window === 'undefined') return false
  const b = getColorClawService()
  return !!(b?.get && b?.post && b?.delete)
}

const useMock =
  typeof import.meta !== 'undefined' &&
  (import.meta.env.VITE_MOCK_SCHEDULED_JOBS === 'true' ||
    (import.meta.env.DEV &&
      import.meta.env.VITE_USE_REAL_SCHEDULE_API !== 'true' &&
      !hasColorClawScheduleBridge()))

let mockStore: ScheduledJob[] = []
/** 新建任务用 mock-${n}，须大于种子里的 mock-1 / mock-2，避免与 seed 的 id 冲突导致 React key 重复 */
let mockId = 100

function seedMockIfEmpty(): void {
  if (mockStore.length > 0) return
  const soon = Date.now() + 120_000
  mockStore = [
    {
      id: 'mock-1',
      name: '示例：两分钟后',
      message: '示例任务：两分钟后触发',
      at: new Date(soon).toISOString().slice(0, 19),
      status: 'scheduled',
    },
    {
      id: 'mock-2',
      name: '示例：每 5 分钟',
      message: '间隔任务示例',
      every_seconds: 300,
      status: 'scheduled',
    },
    {
      id: 'mock-running',
      name: '运行中示例',
      message: '模拟正在执行的任务',
      every_seconds: 3600,
      status: 'running',
    },
    {
      id: 'mock-completed',
      name: '已完成示例',
      message: '模拟已结束任务',
      at: '2020-01-01T10:00:00',
      status: 'completed',
      completed_at: '2020-01-01T10:05:00',
    },
  ]
}

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function msToLocalDatetime(ms: unknown): string | undefined {
  if (ms == null || ms === '') return undefined
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return undefined
  const d = new Date(n)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

function asRecord(v: unknown): Record<string, unknown> | null {
  return v != null && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
}

/** 服务端 Job（/cron/jobs）→ UI 用 ScheduledJob */
function mapServerStatusToUi(
  scheduleKind: string,
  lastStatus: string,
  nextRunAtMs: number | undefined,
  lastRunAtMs: number | undefined,
  enabled: boolean
): ScheduledJobStatus {
  if (!enabled) return 'completed'
  const st = (lastStatus || '').toLowerCase()
  if (st === 'running' || st === 'in_progress' || st === 'executing') return 'running'

  const now = Date.now()
  const next =
    nextRunAtMs != null && Number.isFinite(Number(nextRunAtMs)) ? Number(nextRunAtMs) : undefined
  const last =
    lastRunAtMs != null && Number.isFinite(Number(lastRunAtMs)) ? Number(lastRunAtMs) : undefined

  if (scheduleKind === 'at' && last != null && last > 0 && (next === undefined || next <= now)) {
    return 'completed'
  }

  if (st === 'failed' || st === 'error' || st === 'failure') return 'completed'

  if (next != null && next > now) return 'scheduled'

  if (st === 'success' || st === 'ok' || st === 'completed') {
    if (scheduleKind === 'every' || scheduleKind === 'cron') return 'scheduled'
    return 'completed'
  }

  return 'scheduled'
}

function normalizeCronJobV2(raw: Record<string, unknown>): ScheduledJob {
  const payload = asRecord(raw.payload) ?? {}
  const schedule = asRecord(raw.schedule) ?? {}
  const state = asRecord(raw.state) ?? {}

  const kind = String(schedule.kind ?? '').toLowerCase()

  let every_seconds: number | undefined
  let cron_expr: string | undefined
  let tz: string | undefined
  let at: string | undefined

  if (kind === 'every') {
    if (schedule.every_ms != null) {
      every_seconds = Math.max(1, Math.round(Number(schedule.every_ms) / 1000))
    } else if (schedule.every_seconds != null) {
      every_seconds = Math.max(1, Math.floor(Number(schedule.every_seconds)))
    }
  } else if (kind === 'cron') {
    if (schedule.expr != null) cron_expr = String(schedule.expr)
    if (schedule.tz != null) tz = String(schedule.tz)
  } else if (kind === 'at') {
    at = msToLocalDatetime(schedule.at_ms)
    if (!at && schedule.at != null) at = String(schedule.at).trim() || undefined
  }

  const message = String(payload.message ?? raw.message ?? '').trim() || String(raw.name ?? '')
  const name = raw.name != null ? String(raw.name) : undefined
  const deliver = payload.deliver === true || payload.deliver === 'true'
  const channel = payload.channel != null ? String(payload.channel) : undefined
  const to = payload.to != null ? String(payload.to) : undefined
  const delete_after_run =
    raw.delete_after_run === true ||
    raw.delete_after_run === 'true' ||
    raw.deleteAfterRun === true
  const enabled = raw.enabled !== false && raw.enabled !== 'false'

  const nextRunAtMs =
    state.next_run_at_ms != null ? Number(state.next_run_at_ms) : undefined
  const lastRunAtMs = state.last_run_at_ms != null ? Number(state.last_run_at_ms) : undefined
  const lastStatus = state.last_status != null ? String(state.last_status) : ''

  const status = mapServerStatusToUi(kind, lastStatus, nextRunAtMs, lastRunAtMs, enabled)

  let started_at: string | undefined
  let completed_at: string | undefined
  if (lastRunAtMs != null && Number.isFinite(lastRunAtMs) && lastRunAtMs > 0) {
    const iso = msToLocalDatetime(lastRunAtMs)
    if (status === 'running') started_at = iso
    if (status === 'completed') completed_at = iso
  }

  return {
    id: String(raw.id ?? ''),
    message,
    name,
    every_seconds,
    cron_expr,
    tz,
    at,
    deliver: deliver || undefined,
    channel,
    to,
    delete_after_run: delete_after_run || undefined,
    status,
    started_at,
    completed_at,
  }
}

/** 将服务端或旧版扁平 JSON 转为 ScheduledJob */
function normalizeJob(raw: Record<string, unknown>): ScheduledJob {
  if (raw.payload != null && typeof raw.payload === 'object') {
    return normalizeCronJobV2(raw)
  }

  const g = (k: string, alt?: string) => {
    const v = raw[k] ?? (alt ? raw[alt] : undefined)
    return v
  }
  return {
    id: String(g('id') ?? ''),
    message: String(g('message') ?? ''),
    name: g('name') != null ? String(g('name')) : undefined,
    every_seconds:
      g('every_seconds') != null
        ? Number(g('every_seconds'))
        : g('everySeconds') != null
          ? Number(g('everySeconds'))
          : undefined,
    cron_expr:
      g('cron_expr') != null ? String(g('cron_expr')) : g('cronExpr') != null ? String(g('cronExpr')) : undefined,
    tz: g('tz') != null ? String(g('tz')) : undefined,
    at: g('at') != null ? String(g('at')) : undefined,
    deliver: g('deliver') === true || g('deliver') === 'true',
    channel: g('channel') != null ? String(g('channel')) : undefined,
    to: g('to') != null ? String(g('to')) : undefined,
    delete_after_run:
      g('delete_after_run') === true ||
      g('delete_after_run') === 'true' ||
      g('deleteAfterRun') === true,
    status: (g('status') as ScheduledJob['status']) ?? undefined,
    started_at: g('started_at') != null ? String(g('started_at')) : g('startedAt') != null ? String(g('startedAt')) : undefined,
    completed_at:
      g('completed_at') != null ? String(g('completed_at')) : g('completedAt') != null ? String(g('completedAt')) : undefined,
  }
}

export class ScheduledJobApiError extends Error {
  status?: number
  constructor(message: string, status?: number) {
    super(message)
    this.name = 'ScheduledJobApiError'
    this.status = status
  }
}

const LOCAL_SERVICE_RETRY_DELAYS_MS = [250, 500, 1000, 1500]

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function isLocalServiceNotReadyError(errorMessage: string | undefined): boolean {
  const msg = String(errorMessage ?? '').toLowerCase()
  if (!msg) return false
  return (
    msg.includes('econnrefused') ||
    msg.includes('connect econnrefused') ||
    (msg.includes('connect') && msg.includes('127.0.0.1')) ||
    msg.includes('socket hang up') ||
    msg.includes('fetch failed')
  )
}

type BridgeResponse<TData = unknown> = { success: boolean; data?: TData; error?: string }

async function requestWithLocalServiceRetry<TData>(
  request: () => Promise<BridgeResponse<TData>>
): Promise<BridgeResponse<TData>> {
  let lastFailure: BridgeResponse<TData> | null = null
  let lastThrown: unknown = null

  for (let attempt = 0; attempt <= LOCAL_SERVICE_RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      const result = await request()
      if (result.success) return result
      lastFailure = result
      if (!isLocalServiceNotReadyError(result.error) || attempt >= LOCAL_SERVICE_RETRY_DELAYS_MS.length) {
        return result
      }
    } catch (e) {
      lastThrown = e
      const message = e instanceof Error ? e.message : String(e)
      if (!isLocalServiceNotReadyError(message) || attempt >= LOCAL_SERVICE_RETRY_DELAYS_MS.length) {
        throw e
      }
    }
    await sleep(LOCAL_SERVICE_RETRY_DELAYS_MS[attempt])
  }

  if (lastFailure) return lastFailure
  if (lastThrown) throw lastThrown
  return { success: false, error: '本地服务暂未就绪' }
}

/** 与「我的技能」一致：经 preload → 主进程 HTTP 访问 127.0.0.1:18790（非渲染进程直连） */
function requireColorClawBridge(): NonNullable<ReturnType<typeof getColorClawService>> {
  const b = getColorClawService()
  if (!b?.get || !b?.post || !b?.delete) {
    throw new ScheduledJobApiError(
      '无法访问本地服务：colorClawService 不可用（请使用桌面端并重新编译 Electron；纯浏览器预览请依赖开发 Mock 或暂不使用定时任务）'
    )
  }
  return b
}

export async function listScheduledJobs(): Promise<ScheduledJob[]> {
  if (useMock) {
    seedMockIfEmpty()
    return [...mockStore]
  }
  const bridge = requireColorClawBridge()
  const path = `/${CRON_JOBS_PATH}?include_disabled=false`
  const result = await requestWithLocalServiceRetry(() => bridge.get!(path))
  if (!result.success) {
    throw new ScheduledJobApiError(result.error || '列出任务失败')
  }
  const data = result.data as unknown
  if (Array.isArray(data)) {
    return data
      .map((row) => normalizeJob(row as Record<string, unknown>))
      .filter((j) => j.id)
  }
  if (data && typeof data === 'object' && Array.isArray((data as { jobs?: unknown }).jobs)) {
    return ((data as { jobs: unknown[] }).jobs)
      .map((row) => normalizeJob(row as Record<string, unknown>))
      .filter((j) => j.id)
  }
  return []
}

export async function createScheduledJob(body: CreateScheduledJobPayload): Promise<ScheduledJob> {
  const v = validateSchedulePayload(body)
  if (!v.ok) {
    throw new ScheduledJobApiError(v.error)
  }
  if (useMock) {
    seedMockIfEmpty()
    const id = `mock-${mockId++}`
    const job: ScheduledJob = {
      id,
      ...body,
      status: 'scheduled',
    }
    mockStore.push(job)
    return { ...job }
  }
  const bridge = requireColorClawBridge()
  const result = await requestWithLocalServiceRetry(() => bridge.post!(`/${CRON_JOBS_PATH}`, body))
  if (!result.success) {
    throw new ScheduledJobApiError(result.error || '创建任务失败')
  }
  const data = result.data
  if (data && typeof data === 'object') {
    return normalizeJob(data as Record<string, unknown>)
  }
  throw new ScheduledJobApiError('创建成功但未返回任务数据')
}

export async function deleteScheduledJob(id: string): Promise<void> {
  if (useMock) {
    mockStore = mockStore.filter((j) => j.id !== id)
    return
  }
  const bridge = requireColorClawBridge()
  const result = await requestWithLocalServiceRetry(() => bridge.delete!(`/${cronJobDeletePath(id)}`))
  if (!result.success) {
    throw new ScheduledJobApiError(result.error || '删除任务失败')
  }
}

export function isScheduledJobsMockMode(): boolean {
  return useMock
}
