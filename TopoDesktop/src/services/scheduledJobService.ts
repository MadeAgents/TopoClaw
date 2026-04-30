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

import type { CreateScheduledJobPayload, ScheduledJob, ScheduledJobStatus } from '../types/scheduledJob'
import {
  listScheduledJobs,
  createScheduledJob,
  deleteScheduledJob,
} from './scheduledJobApi'
import { clearJobLocalLabel } from './scheduledJobCache'
import { loadCachedJobs, saveCachedJobs } from './scheduledJobCache'
import { getNextRunTimestamp } from './scheduledJobNextRun'

export interface PartitionedJobs {
  scheduled: ScheduledJob[]
  running: ScheduledJob[]
  completed: ScheduledJob[]
}

/** 同一 id 只保留首次出现，避免列表渲染 duplicate key */
function dedupeJobsById(jobs: ScheduledJob[]): ScheduledJob[] {
  const seen = new Set<string>()
  const out: ScheduledJob[] = []
  for (const j of jobs) {
    if (!j.id || seen.has(j.id)) continue
    seen.add(j.id)
    out.push(j)
  }
  return out
}

/** 无 status 时默认 scheduled（TODO: 联调后删除 fallback） */
export function resolveJobStatus(job: ScheduledJob): ScheduledJobStatus {
  return job.status ?? 'scheduled'
}

export function partitionByStatus(jobs: ScheduledJob[]): PartitionedJobs {
  const scheduled: ScheduledJob[] = []
  const running: ScheduledJob[] = []
  const completed: ScheduledJob[] = []
  for (const j of jobs) {
    const s = resolveJobStatus(j)
    if (s === 'running') running.push(j)
    else if (s === 'completed') completed.push(j)
    else scheduled.push(j)
  }
  return { scheduled, running, completed }
}

export async function refreshJobs(): Promise<ScheduledJob[]> {
  const list = dedupeJobsById(await listScheduledJobs())
  const fetchedAt = Date.now()
  saveCachedJobs(list, fetchedAt)
  return list
}

export async function createJob(
  payload: CreateScheduledJobPayload
): Promise<{ job: ScheduledJob; list: ScheduledJob[] }> {
  const job = await createScheduledJob(payload)
  const list = await refreshJobs()
  return { job, list }
}

export async function deleteJob(id: string): Promise<ScheduledJob[]> {
  await deleteScheduledJob(id)
  return refreshJobs()
}

/** 无 update 接口时：删后重建 */
export async function replaceJob(
  oldId: string,
  payload: CreateScheduledJobPayload
): Promise<{ job: ScheduledJob; list: ScheduledJob[] }> {
  await deleteScheduledJob(oldId)
  clearJobLocalLabel(oldId)
  const job = await createScheduledJob(payload)
  const list = await refreshJobs()
  return { job, list }
}

const verifyPending = new Set<string>()
const lastVerifyAt = new Map<string, number>()
const VERIFY_DEBOUNCE_MS = 5000

/**
 * 已到本地 nextRun 的 scheduled 任务：拉一次 list 与服务端对齐（运行中态）。
 * 返回刷新后的列表；无需刷新时返回 null。
 */
export async function tickDueJobVerification(
  jobs: ScheduledJob[],
  fetchedAt: number,
  now: number
): Promise<ScheduledJob[] | null> {
  const candidates = jobs.filter((j) => {
    if (resolveJobStatus(j) !== 'scheduled') return false
    const next = getNextRunTimestamp(j, now, fetchedAt)
    if (next == null || next > now) return false
    if (verifyPending.has(j.id)) return false
    const last = lastVerifyAt.get(j.id) ?? 0
    if (now - last < VERIFY_DEBOUNCE_MS) return false
    return true
  })
  if (candidates.length === 0) return null

  for (const j of candidates) {
    verifyPending.add(j.id)
    lastVerifyAt.set(j.id, now)
  }
  try {
    const fresh = await refreshJobs()
    return fresh
  } finally {
    for (const j of candidates) verifyPending.delete(j.id)
  }
}

export function jobToCreatePayload(job: ScheduledJob): CreateScheduledJobPayload {
  return {
    message: job.message,
    name: job.name,
    every_seconds: job.every_seconds,
    cron_expr: job.cron_expr,
    tz: job.tz,
    at: job.at,
    deliver: job.deliver,
    channel: job.channel,
    to: job.to,
    delete_after_run: job.delete_after_run,
  }
}

export function getInitialJobsFromCache(): { jobs: ScheduledJob[]; fetchedAt: number } {
  const c = loadCachedJobs()
  if (c) return { jobs: dedupeJobsById(c.jobs), fetchedAt: c.fetchedAt }
  return { jobs: [], fetchedAt: Date.now() }
}
