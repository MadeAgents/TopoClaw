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

import CronExpressionParser from 'cron-parser'
import type { ScheduledJob } from '../types/scheduledJob'

function parseAtMs(at: string): number | null {
  const t = Date.parse(at.includes('T') ? at : at.replace(' ', 'T'))
  return Number.isFinite(t) ? t : null
}

/**
 * 下一次触发时间（毫秒）。
 * @param fetchedAt list 刷新时间，用于 every_seconds 与列表对齐
 */
export function getNextRunTimestamp(
  job: ScheduledJob,
  now: number,
  fetchedAt: number
): number | null {
  if (job.at != null && job.at.trim()) {
    const t = parseAtMs(job.at.trim())
    if (t == null) return null
    return t > now ? t : null
  }
  if (job.every_seconds != null && job.every_seconds > 0) {
    const period = job.every_seconds * 1000
    const anchor = fetchedAt
    let next = anchor + Math.ceil((now - anchor) / period) * period
    while (next <= now) next += period
    return next
  }
  if (job.cron_expr != null && job.cron_expr.trim()) {
    try {
      const expr = CronExpressionParser.parse(job.cron_expr.trim(), {
        currentDate: new Date(now),
        tz: job.tz && job.tz.trim() ? job.tz.trim() : undefined,
      })
      return expr.next().getTime()
    } catch {
      return null
    }
  }
  return null
}

export function formatScheduleSummary(job: ScheduledJob): string {
  if (job.at?.trim()) return `一次性 ${job.at.trim()}`
  if (job.every_seconds != null && job.every_seconds > 0) return `每 ${job.every_seconds} 秒`
  if (job.cron_expr?.trim()) {
    return job.tz?.trim() ? `Cron: ${job.cron_expr.trim()} (${job.tz})` : `Cron: ${job.cron_expr.trim()}`
  }
  return '未配置调度'
}

export function formatJobTargetTag(
  job: ScheduledJob,
  localWorkspaceLabel?: string
): string {
  if (localWorkspaceLabel?.trim()) return localWorkspaceLabel.trim()
  if (job.deliver && (job.channel || job.to)) {
    const parts = [job.channel, job.to].filter(Boolean)
    return parts.length ? parts.join(' · ') : '投递'
  }
  if (job.channel || job.to) {
    return [job.channel, job.to].filter(Boolean).join(' · ') || '—'
  }
  return '—'
}

const MS_MIN = 60_000
const MS_HOUR = 60 * MS_MIN
const MS_DAY = 24 * MS_HOUR
/** 小于等于此时长显示「即将执行」 */
const MS_SOON = 2 * MS_MIN

/**
 * 倒计时文案：不精确到秒。
 * - ≤2 分钟：即将执行
 * - 1 小时内：N 分钟后
 * - ≥1 小时：还有 X 小时（可带分钟）；跨天则还有 X 天 X 小时（可带分钟）
 */
export function formatCountdown(ms: number): string {
  if (ms <= 0) return '即将执行'
  if (ms <= MS_SOON) return '即将执行'

  if (ms < MS_HOUR) {
    const minutes = Math.floor(ms / MS_MIN)
    return `${Math.max(1, minutes)}分钟后`
  }

  if (ms >= MS_DAY) {
    const d = Math.floor(ms / MS_DAY)
    const rem = ms % MS_DAY
    const h = Math.floor(rem / MS_HOUR)
    const m = Math.floor((rem % MS_HOUR) / MS_MIN)
    if (h === 0 && m === 0) return `还有${d}天`
    if (m === 0) return `还有${d}天${h}小时`
    return `还有${d}天${h}小时${m}分钟`
  }

  const h = Math.floor(ms / MS_HOUR)
  const m = Math.floor((ms % MS_HOUR) / MS_MIN)
  if (m === 0) return `还有${h}小时`
  return `还有${h}小时${m}分钟`
}
