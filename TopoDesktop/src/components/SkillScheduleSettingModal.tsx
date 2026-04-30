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

import { useState } from 'react'
import type { Skill, SkillScheduleConfig, ScheduleType } from '../services/api'
import './SkillScheduleSettingModal.css'

interface SkillScheduleSettingModalProps {
  skill: Skill
  onClose: () => void
  onSave: (config: SkillScheduleConfig | null) => void
}

const SCHEDULE_TYPES: { value: ScheduleType; label: string }[] = [
  { value: 'ONCE', label: '单次' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
]

const WEEK_DAYS = ['日', '一', '二', '三', '四', '五', '六']

function toTimeString(ms: number): string {
  const d = new Date(ms)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function toDateString(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function SkillScheduleSettingModal({
  skill,
  onClose,
  onSave,
}: SkillScheduleSettingModalProps) {
  const cfg = skill.scheduleConfig
  const now = Date.now()
  const defaultTime = cfg?.targetTime ?? now

  const [scheduleType, setScheduleType] = useState<ScheduleType>(
    cfg?.scheduleType ?? 'ONCE'
  )
  const [timeValue, setTimeValue] = useState(toTimeString(defaultTime))
  const [dateValue, setDateValue] = useState(toDateString(defaultTime))
  const [repeatDays, setRepeatDays] = useState<number[]>(
    cfg?.repeatDays ?? [new Date().getDay()]
  )

  const toggleRepeatDay = (day: number) => {
    setRepeatDays((prev) =>
      prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day].sort()
    )
  }

  const handleSave = () => {
    if (scheduleType === 'WEEKLY' && repeatDays.length === 0) {
      window.alert('请至少选择一天')
      return
    }
    const [h, m] = timeValue.split(':').map(Number)
    const [y, mo, d] = dateValue.split('-').map(Number)
    const baseDate = new Date(y, (mo ?? 1) - 1, d ?? 1, h ?? 0, m ?? 0, 0, 0)
    const targetTime = baseDate.getTime()

    const config: SkillScheduleConfig = {
      isEnabled: true,
      scheduleType,
      targetTime,
      repeatDays: scheduleType === 'WEEKLY' ? repeatDays : undefined,
    }
    onSave(config)
    onClose()
  }

  const handleDisable = () => {
    onSave(null)
    onClose()
  }

  return (
    <div className="skill-schedule-overlay" onClick={onClose}>
      <div
        className="skill-schedule-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="skill-schedule-header">
          <h3 className="skill-schedule-title">⏰ 定时设置</h3>
          <button className="skill-schedule-close" onClick={onClose} type="button">
            ×
          </button>
        </div>

        <div className="skill-schedule-body">
          <div className="skill-schedule-field">
            <label>重复类型</label>
            <select
              value={scheduleType}
              onChange={(e) => setScheduleType(e.target.value as ScheduleType)}
              className="skill-schedule-select"
            >
              {SCHEDULE_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </div>

          <div className="skill-schedule-field">
            <label>执行时间</label>
            <input
              type="time"
              value={timeValue}
              onChange={(e) => setTimeValue(e.target.value)}
              className="skill-schedule-input"
            />
          </div>

          {scheduleType === 'ONCE' && (
            <div className="skill-schedule-field">
              <label>执行日期</label>
              <input
                type="date"
                value={dateValue}
                onChange={(e) => setDateValue(e.target.value)}
                className="skill-schedule-input"
              />
            </div>
          )}

          {scheduleType === 'WEEKLY' && (
            <div className="skill-schedule-field">
              <label>重复星期</label>
              <div className="skill-schedule-week">
                {WEEK_DAYS.map((label, idx) => (
                  <label key={idx} className="skill-schedule-week-item">
                    <input
                      type="checkbox"
                      checked={repeatDays.includes(idx)}
                      onChange={() => toggleRepeatDay(idx)}
                    />
                    <span>{label}</span>
                  </label>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="skill-schedule-actions">
          <button
            type="button"
            className="skill-schedule-btn skill-schedule-btn-secondary"
            onClick={handleDisable}
          >
            取消定时
          </button>
          <button
            type="button"
            className="skill-schedule-btn skill-schedule-btn-primary"
            onClick={handleSave}
          >
            保存
          </button>
        </div>
      </div>
    </div>
  )
}
