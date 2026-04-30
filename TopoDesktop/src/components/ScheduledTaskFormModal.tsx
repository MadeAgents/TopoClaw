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

import { useState, useEffect } from 'react'
import { createPortal } from 'react-dom'
import type { CreateScheduledJobPayload, ScheduledJob } from '../types/scheduledJob'
import {
  validateSchedulePayload,
  defaultJobNameFromMessage,
} from '../types/scheduledJob'
import './AddScheduledTaskModal.css'

export interface FormPrefill {
  message?: string
  name?: string
}

type ScheduleKind = 'interval' | 'cron' | 'at'

function detectKind(job: ScheduledJob): ScheduleKind {
  if (job.at?.trim()) return 'at'
  if (job.cron_expr?.trim()) return 'cron'
  return 'interval'
}

function atToDatetimeLocal(at: string): string {
  const s = at.trim()
  if (s.length >= 16) return s.slice(0, 16)
  return s
}

function buildPayload(
  message: string,
  name: string,
  kind: ScheduleKind,
  everySeconds: number,
  cronExpr: string,
  tz: string,
  atLocal: string,
  deliver: boolean,
  channel: string,
  to: string,
  deleteAfterRun: boolean
): CreateScheduledJobPayload {
  const base: CreateScheduledJobPayload = {
    message: message.trim(),
    name: name.trim() || defaultJobNameFromMessage(message),
    deliver: deliver || undefined,
    channel: channel.trim() || undefined,
    to: to.trim() || undefined,
  }
  if (deleteAfterRun) base.delete_after_run = true
  if (kind === 'at') {
    base.at = atLocal.trim()
  } else if (kind === 'cron') {
    base.cron_expr = cronExpr.trim()
    if (tz.trim()) base.tz = tz.trim()
  } else {
    base.every_seconds = Math.max(1, Math.floor(everySeconds))
  }
  return base
}

export interface ScheduledTaskFormModalProps {
  mode: 'create' | 'edit'
  initialJob?: ScheduledJob
  prefill?: FormPrefill
  /** 仅展示用，写入 job-local-labels */
  initialWorkspaceLabel?: string
  onClose: () => void
  onCreate: (payload: CreateScheduledJobPayload) => Promise<ScheduledJob>
  onReplace?: (oldId: string, payload: CreateScheduledJobPayload) => Promise<ScheduledJob>
  /** 创建/替换成功后，写入本地工作区标签 */
  onWorkspaceLabel?: (jobId: string, label: string) => void
  /** 编辑态删除 */
  onDelete?: () => Promise<void>
}

export function ScheduledTaskFormModal({
  mode,
  initialJob,
  prefill,
  initialWorkspaceLabel = '',
  onClose,
  onCreate,
  onReplace,
  onWorkspaceLabel,
  onDelete,
}: ScheduledTaskFormModalProps) {
  const [message, setMessage] = useState('')
  const [name, setName] = useState('')
  const [workspace, setWorkspace] = useState(initialWorkspaceLabel)
  const [scheduleKind, setScheduleKind] = useState<ScheduleKind>('interval')
  const [everySeconds, setEverySeconds] = useState(300)
  const [cronExpr, setCronExpr] = useState('0 9 * * *')
  const [tz, setTz] = useState('')
  const [atLocal, setAtLocal] = useState('')
  const [deliver, setDeliver] = useState(false)
  const [channel, setChannel] = useState('')
  const [to, setTo] = useState('')
  const [deleteAfterRun, setDeleteAfterRun] = useState(false)
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (initialJob) {
      setMessage(initialJob.message ?? '')
      setName(initialJob.name ?? '')
      setWorkspace(initialWorkspaceLabel)
      const k = detectKind(initialJob)
      setScheduleKind(k)
      if (initialJob.every_seconds != null) setEverySeconds(initialJob.every_seconds)
      if (initialJob.cron_expr) setCronExpr(initialJob.cron_expr)
      if (initialJob.tz) setTz(initialJob.tz)
      if (initialJob.at) setAtLocal(atToDatetimeLocal(initialJob.at))
      setDeliver(!!initialJob.deliver)
      setChannel(initialJob.channel ?? '')
      setTo(initialJob.to ?? '')
      setDeleteAfterRun(!!initialJob.delete_after_run)
      if (initialJob.deliver || initialJob.channel || initialJob.to || initialJob.delete_after_run) {
        setShowAdvanced(true)
      }
      return
    }
    if (prefill) {
      if (prefill.message) setMessage(prefill.message)
      if (prefill.name) setName(prefill.name)
    }
  }, [initialJob, prefill, initialWorkspaceLabel])

  const title = mode === 'create' ? '添加定时任务' : '编辑定时任务'

  const handleSubmit = async () => {
    const payload = buildPayload(
      message,
      name,
      scheduleKind,
      everySeconds,
      cronExpr,
      tz,
      atLocal,
      deliver,
      channel,
      to,
      deleteAfterRun
    )
    const v = validateSchedulePayload(payload)
    if (!v.ok) {
      setError(v.error)
      return
    }
    setError('')
    setSaving(true)
    try {
      let job: ScheduledJob
      if (mode === 'edit' && initialJob?.id && onReplace) {
        if (!window.confirm('将删除原任务并以当前内容新建，确定吗？')) {
          setSaving(false)
          return
        }
        job = await onReplace(initialJob.id, payload)
      } else {
        job = await onCreate(payload)
      }
      const ws = workspace.trim()
      if (ws && onWorkspaceLabel) onWorkspaceLabel(job.id, ws)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return createPortal(
    <div className="add-scheduled-task-overlay" onClick={onClose}>
      <div className="add-scheduled-task-modal" onClick={(e) => e.stopPropagation()}>
        <div className="add-scheduled-task-header">
          <span className="add-scheduled-task-title">{title}</span>
          <button type="button" className="add-scheduled-task-close" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>

        <div className="add-scheduled-task-body">
          <div className="add-scheduled-task-field">
            <label htmlFor="stf-message">提示词（message）</label>
            <textarea
              id="stf-message"
              className="add-scheduled-task-prompt"
              style={{ minHeight: 100, padding: 'var(--space-8)', border: '1px solid var(--color-border)', borderRadius: 'var(--radius-md)', width: '100%', boxSizing: 'border-box' }}
              placeholder="触发时交给 Agent 的文案"
              value={message}
              onChange={(e) => {
                setMessage(e.target.value)
                setError('')
              }}
              rows={4}
            />
          </div>

          <div className="add-scheduled-task-field">
            <label htmlFor="stf-name">名称（可选）</label>
            <input
              id="stf-name"
              type="text"
              className="add-scheduled-task-input"
              placeholder="默认取提示词前 30 字"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="add-scheduled-task-field">
            <label htmlFor="stf-ws">工作空间（本地标签）</label>
            <input
              id="stf-ws"
              type="text"
              className="add-scheduled-task-input"
              placeholder="仅本机展示，不参与服务端字段"
              value={workspace}
              onChange={(e) => setWorkspace(e.target.value)}
            />
          </div>

          <div className="add-scheduled-task-field add-scheduled-task-field-frequency">
            <span className="add-scheduled-task-label-block">调度方式（三选一）</span>
            <div className="add-scheduled-task-freq-tabs" role="tablist">
              <button
                type="button"
                role="tab"
                className={`add-scheduled-task-freq-tab ${scheduleKind === 'interval' ? 'active' : ''}`}
                onClick={() => setScheduleKind('interval')}
              >
                按间隔（秒）
              </button>
              <button
                type="button"
                role="tab"
                className={`add-scheduled-task-freq-tab ${scheduleKind === 'cron' ? 'active' : ''}`}
                onClick={() => setScheduleKind('cron')}
              >
                Cron
              </button>
              <button
                type="button"
                role="tab"
                className={`add-scheduled-task-freq-tab ${scheduleKind === 'at' ? 'active' : ''}`}
                onClick={() => setScheduleKind('at')}
              >
                指定时间
              </button>
            </div>

            {scheduleKind === 'interval' && (
              <div className="add-scheduled-task-interval-row" style={{ marginTop: 8 }}>
                <span>每</span>
                <input
                  type="number"
                  min={1}
                  className="add-scheduled-task-interval-num"
                  value={everySeconds}
                  onChange={(e) => setEverySeconds(Number(e.target.value))}
                />
                <span className="add-scheduled-task-interval-suffix">秒执行一次</span>
              </div>
            )}

            {scheduleKind === 'cron' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
                <input
                  type="text"
                  className="add-scheduled-task-input"
                  placeholder="Cron 表达式，如 0 9 * * *"
                  value={cronExpr}
                  onChange={(e) => setCronExpr(e.target.value)}
                />
                <input
                  type="text"
                  className="add-scheduled-task-input"
                  placeholder="时区（可选），如 Asia/Shanghai"
                  value={tz}
                  onChange={(e) => setTz(e.target.value)}
                />
              </div>
            )}

            {scheduleKind === 'at' && (
              <div className="add-scheduled-task-time-wrap" style={{ marginTop: 8 }}>
                <input
                  type="datetime-local"
                  className="add-scheduled-task-time"
                  style={{ maxWidth: '100%' }}
                  value={atLocal}
                  onChange={(e) => setAtLocal(e.target.value)}
                />
              </div>
            )}
          </div>

          <div className="add-scheduled-task-field">
            <button
              type="button"
              className="add-scheduled-task-cancel"
              style={{ padding: 0, border: 'none', background: 'none', textDecoration: 'underline' }}
              onClick={() => setShowAdvanced((v) => !v)}
            >
              {showAdvanced ? '收起高级选项' : '高级：投递与 delete_after_run'}
            </button>
            {showAdvanced && (
              <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 'var(--font-size-sm)' }}>
                  <input type="checkbox" checked={deliver} onChange={(e) => setDeliver(e.target.checked)} />
                  deliver
                </label>
                <input
                  type="text"
                  className="add-scheduled-task-input"
                  placeholder="channel"
                  value={channel}
                  onChange={(e) => setChannel(e.target.value)}
                />
                <input
                  type="text"
                  className="add-scheduled-task-input"
                  placeholder="to"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                />
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 'var(--font-size-sm)' }}>
                  <input
                    type="checkbox"
                    checked={deleteAfterRun}
                    onChange={(e) => setDeleteAfterRun(e.target.checked)}
                  />
                  delete_after_run = true（不勾选则不传，由服务端默认）
                </label>
              </div>
            )}
          </div>

          {error && <p className="add-scheduled-task-error">{error}</p>}
        </div>

        <div className="add-scheduled-task-actions">
          {mode === 'edit' && onDelete && (
            <button
              type="button"
              className="add-scheduled-task-cancel"
              style={{ marginBottom: 8, color: 'var(--color-danger)', borderColor: '#fecaca' }}
              disabled={saving}
              onClick={async () => {
                if (!window.confirm('确定删除该定时任务？')) return
                setSaving(true)
                try {
                  await onDelete()
                  onClose()
                } catch (e) {
                  setError(e instanceof Error ? e.message : '删除失败')
                } finally {
                  setSaving(false)
                }
              }}
            >
              删除任务
            </button>
          )}
          <button type="button" className="add-scheduled-task-add" onClick={handleSubmit} disabled={saving}>
            {mode === 'create' ? '添加' : '保存'}
          </button>
          <button type="button" className="add-scheduled-task-cancel" onClick={onClose} disabled={saving}>
            取消
          </button>
        </div>
      </div>
    </div>,
    document.body
  )
}
