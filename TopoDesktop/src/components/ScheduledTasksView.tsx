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

import { useState, useMemo, useCallback, useEffect } from 'react'
import type { CreateScheduledJobPayload, ScheduledJob } from '../types/scheduledJob'
import { loadJobLocalLabels, setJobLocalLabel, clearJobLocalLabel } from '../services/scheduledJobCache'
import { useScheduledJobs, type ScheduledTab } from '../hooks/useScheduledJobs'
import { refreshJobs as fetchScheduledJobsDirect } from '../services/scheduledJobService'
import { isScheduledJobsMockMode } from '../services/scheduledJobApi'
import { ScheduledJobRow } from './ScheduledJobRow'
import { ScheduledTaskFormModal, type FormPrefill } from './ScheduledTaskFormModal'
import { ScheduledTaskTemplatesModal } from './ScheduledTaskTemplatesModal'
import './ScheduledTasksView.css'

export const OPEN_SCHEDULED_JOB_EDITOR_EVENT = 'open-scheduled-job-editor'

function filterJobs(jobs: ScheduledJob[], q: string): ScheduledJob[] {
  const s = q.trim().toLowerCase()
  if (!s) return jobs
  const labels = loadJobLocalLabels()
  return jobs.filter((j) => {
    const lab = labels[j.id]?.label ?? ''
    return (
      (j.name ?? '').toLowerCase().includes(s) ||
      j.message.toLowerCase().includes(s) ||
      (j.cron_expr ?? '').toLowerCase().includes(s) ||
      (j.channel ?? '').toLowerCase().includes(s) ||
      (j.to ?? '').toLowerCase().includes(s) ||
      lab.toLowerCase().includes(s)
    )
  })
}

const TAB_LABELS: Record<ScheduledTab, string> = {
  scheduled: '已安排',
  running: '运行中',
  completed: '已完成',
}

/** 暂不展示「运行中」「已完成」分区，仅保留已安排 */
const VISIBLE_SCHEDULE_TABS: ScheduledTab[] = ['scheduled']

interface ScheduledTasksViewProps {
  search?: string
}

export function ScheduledTasksView({ search = '' }: ScheduledTasksViewProps) {
  const {
    fetchedAt,
    now,
    activeTab,
    setActiveTab,
    loading,
    error,
    refresh,
    partitioned,
    create,
    replace,
    remove,
  } = useScheduledJobs()

  useEffect(() => {
    if (!VISIBLE_SCHEDULE_TABS.includes(activeTab)) {
      setActiveTab('scheduled')
    }
  }, [activeTab, setActiveTab])

  const [labelTick, setLabelTick] = useState(0)
  const localLabels = useMemo(() => {
    void labelTick
    return loadJobLocalLabels()
  }, [labelTick, partitioned])

  const [templatesOpen, setTemplatesOpen] = useState(false)
  const [formOpen, setFormOpen] = useState(false)
  const [formMode, setFormMode] = useState<'create' | 'edit'>('create')
  const [editingJob, setEditingJob] = useState<ScheduledJob | null>(null)
  const [formPrefill, setFormPrefill] = useState<FormPrefill | undefined>()

  const currentList = useMemo(() => {
    const raw =
      activeTab === 'scheduled'
        ? partitioned.scheduled
        : activeTab === 'running'
          ? partitioned.running
          : partitioned.completed
    return filterJobs(raw, search)
  }, [activeTab, partitioned, search])
  const allJobs = useMemo(
    () => [...partitioned.scheduled, ...partitioned.running, ...partitioned.completed],
    [partitioned]
  )

  const openCreate = useCallback((prefill?: FormPrefill) => {
    setFormMode('create')
    setEditingJob(null)
    setFormPrefill(prefill)
    setFormOpen(true)
  }, [])

  const openEdit = useCallback((job: ScheduledJob) => {
    setFormMode('edit')
    setEditingJob(job)
    setFormPrefill(undefined)
    setFormOpen(true)
  }, [])

  const handleWorkspaceLabel = useCallback((jobId: string, label: string) => {
    setJobLocalLabel(jobId, label)
    setLabelTick((t) => t + 1)
  }, [])

  const openEditById = useCallback(async (jobId: string) => {
    const id = (jobId || '').trim()
    if (!id) return
    const local = allJobs.find((j) => j.id === id)
    if (local) {
      openEdit(local)
      return
    }
    try {
      const latest = await fetchScheduledJobsDirect()
      const matched = latest.find((j) => j.id === id)
      if (matched) {
        openEdit(matched)
        return
      }
      console.warn('[ScheduledTasksView] job not found by id', id)
    } catch (e) {
      console.warn('[ScheduledTasksView] openEditById failed', e)
    }
  }, [allJobs, openEdit])

  useEffect(() => {
    const handler = (evt: Event) => {
      const e = evt as CustomEvent<{ jobId?: string }>
      const jobId = e.detail?.jobId
      if (!jobId) return
      void openEditById(jobId)
    }
    window.addEventListener(OPEN_SCHEDULED_JOB_EDITOR_EVENT, handler as EventListener)
    return () => window.removeEventListener(OPEN_SCHEDULED_JOB_EDITOR_EVENT, handler as EventListener)
  }, [openEditById])

  return (
    <div className="scheduled-tasks-view">
      <div className="scheduled-tasks-header">
        <div className="scheduled-tasks-header-text">
          <h1 className="scheduled-tasks-title">定时任务</h1>
          <p className="scheduled-tasks-desc">管理定时任务并查看近期定时任务的运行记录。</p>
          {isScheduledJobsMockMode() && (
            <p className="scheduled-tasks-mock-hint">
              当前为开发环境 Mock。联调真实接口：设置 VITE_USE_REAL_SCHEDULE_API=true；请求与「我的技能」相同，经 colorClawService（主进程访问 127.0.0.1:18790）发送 GET/POST /cron/jobs 与 DELETE /cron/jobs/任务id。
            </p>
          )}
        </div>
        <div className="scheduled-tasks-header-actions">
          <button type="button" className="scheduled-tasks-secondary-btn" onClick={() => setTemplatesOpen(true)}>
            查看模板
          </button>
          <button type="button" className="scheduled-tasks-secondary-btn" onClick={() => refresh()} disabled={loading}>
            刷新
          </button>
          <button type="button" className="scheduled-tasks-add-btn" onClick={() => openCreate()}>
            + 添加
          </button>
        </div>
      </div>

      {error && <div className="scheduled-tasks-banner-error">{error}</div>}

      {VISIBLE_SCHEDULE_TABS.length > 1 && (
        <div className="scheduled-tasks-tabs" role="tablist">
          {VISIBLE_SCHEDULE_TABS.map((tab) => (
            <button
              key={tab}
              type="button"
              role="tab"
              aria-selected={activeTab === tab}
              className={`scheduled-tasks-tab ${activeTab === tab ? 'active' : ''}`}
              onClick={() => setActiveTab(tab)}
            >
              {TAB_LABELS[tab]}
              <span className="scheduled-tasks-tab-count">
                {tab === 'scheduled'
                  ? partitioned.scheduled.length
                  : tab === 'running'
                    ? partitioned.running.length
                    : partitioned.completed.length}
              </span>
            </button>
          ))}
        </div>
      )}

      <div className="scheduled-tasks-content">
        <div className="scheduled-tasks-job-list">
          {loading && currentList.length === 0 ? (
            <div className="scheduled-tasks-empty">加载中…</div>
          ) : currentList.length === 0 ? (
            <div className="scheduled-tasks-empty">暂无任务</div>
          ) : (
            currentList.map((job) => (
              <ScheduledJobRow
                key={job.id}
                job={job}
                variant={activeTab}
                now={now}
                fetchedAt={fetchedAt}
                localWorkspaceLabel={localLabels[job.id]?.label}
                onOpen={() => openEdit(job)}
              />
            ))
          )}
        </div>
      </div>

      {templatesOpen && (
        <ScheduledTaskTemplatesModal
          onClose={() => setTemplatesOpen(false)}
          onPick={(prefill) => {
            openCreate(prefill)
          }}
        />
      )}

      {formOpen && (
        <ScheduledTaskFormModal
          mode={formMode}
          initialJob={editingJob ?? undefined}
          prefill={formPrefill}
          initialWorkspaceLabel={editingJob ? localLabels[editingJob.id]?.label ?? '' : ''}
          onClose={() => {
            setFormOpen(false)
            setEditingJob(null)
            setFormPrefill(undefined)
          }}
          onCreate={async (payload: CreateScheduledJobPayload) => create(payload)}
          onReplace={formMode === 'edit' ? async (oldId, payload) => replace(oldId, payload) : undefined}
          onWorkspaceLabel={handleWorkspaceLabel}
          onDelete={
            formMode === 'edit' && editingJob
              ? async () => {
                  await remove(editingJob.id)
                  clearJobLocalLabel(editingJob.id)
                  setLabelTick((t) => t + 1)
                }
              : undefined
          }
        />
      )}
    </div>
  )
}
