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

/**
 * 定时任务 Job — 与服务端 list/create 字段对齐
 */

export type ScheduledJobStatus = 'scheduled' | 'running' | 'completed'

/** 创建/更新请求体（不含 id） */
export interface CreateScheduledJobPayload {
  message: string
  name?: string
  /** 三选一：与 cron_expr、at 互斥 */
  every_seconds?: number
  cron_expr?: string
  tz?: string
  at?: string
  deliver?: boolean
  channel?: string
  to?: string
  delete_after_run?: boolean
}

/** 列表项 = 创建字段 + id + 状态与时间戳（服务端字段名允许 snake_case，API 层可做映射） */
export interface ScheduledJob extends CreateScheduledJobPayload {
  id: string
  status?: ScheduledJobStatus
  /** ISO 或 number，按服务端实际 */
  started_at?: string
  completed_at?: string
}

export type ValidateResult = { ok: true } | { ok: false; error: string }

/** 校验调度三选一：every_seconds、cron_expr、at 恰好其一 */
export function validateSchedulePayload(p: CreateScheduledJobPayload): ValidateResult {
  const hasEvery = p.every_seconds != null && p.every_seconds > 0
  const hasCron = typeof p.cron_expr === 'string' && p.cron_expr.trim().length > 0
  const hasAt = typeof p.at === 'string' && p.at.trim().length > 0
  const n = [hasEvery, hasCron, hasAt].filter(Boolean).length
  if (n === 0) {
    return { ok: false, error: '请选择一种调度方式：按间隔、cron 或指定时间' }
  }
  if (n > 1) {
    return { ok: false, error: '按间隔、cron、指定时间只能填写一种' }
  }
  if (hasCron && p.tz != null && String(p.tz).trim() === '') {
    return { ok: false, error: '若填写时区，不能为空字符串' }
  }
  if (!p.message || !p.message.trim()) {
    return { ok: false, error: '请填写触发时交给 Agent 的文案（message）' }
  }
  return { ok: true }
}

export function defaultJobNameFromMessage(message: string): string {
  const t = message.trim()
  return t.length <= 30 ? t : t.slice(0, 30)
}
