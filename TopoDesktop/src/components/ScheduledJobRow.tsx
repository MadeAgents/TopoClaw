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
import {
  formatScheduleSummary,
  formatJobTargetTag,
  formatCountdown,
  getNextRunTimestamp,
} from '../services/scheduledJobNextRun'
import './ScheduledJobRow.css'

export type JobRowVariant = 'scheduled' | 'running' | 'completed'

export interface ScheduledJobRowProps {
  job: ScheduledJob
  variant: JobRowVariant
  now: number
  fetchedAt: number
  localWorkspaceLabel?: string
  onOpen: () => void
}

export function ScheduledJobRow({
  job,
  variant,
  now,
  fetchedAt,
  localWorkspaceLabel,
  onOpen,
}: ScheduledJobRowProps) {
  const title = job.name?.trim() || job.message.slice(0, 40) + (job.message.length > 40 ? '…' : '')
  const tag = formatJobTargetTag(job, localWorkspaceLabel)
  const scheduleLine = formatScheduleSummary(job)
  const next = variant === 'scheduled' ? getNextRunTimestamp(job, now, fetchedAt) : null
  const countdown =
    next != null && next > now ? formatCountdown(next - now) : variant === 'scheduled' ? '—' : null

  return (
    <button
      type="button"
      className={`scheduled-job-row scheduled-job-row-${variant}`}
      onClick={onOpen}
    >
      <div className="scheduled-job-row-main">
        <div className="scheduled-job-row-title">{title}</div>
        <div className="scheduled-job-row-meta">
          <span className="scheduled-job-row-tag">{tag}</span>
          <span className="scheduled-job-row-schedule">{scheduleLine}</span>
        </div>
        {job.completed_at && variant === 'completed' && (
          <div className="scheduled-job-row-done">完成于 {job.completed_at}</div>
        )}
      </div>
      <div className="scheduled-job-row-tail">
        {variant === 'scheduled' && countdown != null && (
          <span className="scheduled-job-row-countdown">{countdown}</span>
        )}
        {variant === 'running' && (
          <span className="scheduled-job-row-spinner" aria-label="运行中" />
        )}
      </div>
    </button>
  )
}
