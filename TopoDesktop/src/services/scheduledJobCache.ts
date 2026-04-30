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

import type { ScheduledJob } from '../types/scheduledJob'

const CACHE_KEY = 'scheduled-jobs-cache-v1'
const LABELS_KEY = 'job-local-labels-v1'

export interface ScheduledJobsCachePayload {
  jobs: ScheduledJob[]
  fetchedAt: number
}

export function loadCachedJobs(): ScheduledJobsCachePayload | null {
  try {
    const s = localStorage.getItem(CACHE_KEY)
    if (!s) return null
    const o = JSON.parse(s) as ScheduledJobsCachePayload
    if (!o || !Array.isArray(o.jobs)) return null
    return { jobs: o.jobs, fetchedAt: typeof o.fetchedAt === 'number' ? o.fetchedAt : 0 }
  } catch {
    return null
  }
}

export function saveCachedJobs(jobs: ScheduledJob[], fetchedAt: number): void {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify({ jobs, fetchedAt }))
  } catch {}
}

export function clearJobsCache(): void {
  try {
    localStorage.removeItem(CACHE_KEY)
  } catch {}
}

export function loadJobLocalLabels(): Record<string, { label: string }> {
  try {
    const s = localStorage.getItem(LABELS_KEY)
    if (!s) return {}
    const o = JSON.parse(s) as Record<string, { label: string }>
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}

export function setJobLocalLabel(jobId: string, label: string): void {
  try {
    const m = loadJobLocalLabels()
    m[jobId] = { label }
    localStorage.setItem(LABELS_KEY, JSON.stringify(m))
  } catch {}
}

export function clearJobLocalLabel(jobId: string): void {
  try {
    const m = loadJobLocalLabels()
    delete m[jobId]
    localStorage.setItem(LABELS_KEY, JSON.stringify(m))
  } catch {}
}
