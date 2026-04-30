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
import { loadScheduledSkills } from '../services/skillStorage'
import { calculateNextTriggerTime } from '../services/skillScheduleService'
import type { Skill, ScheduleType } from '../services/api'
import './SkillScheduleListModal.css'

interface SkillScheduleListModalProps {
  onClose: () => void
}

const SCHEDULE_TYPE_LABELS: Record<ScheduleType, string> = {
  ONCE: '单次',
  DAILY: '每天',
  WEEKLY: '每周',
  MONTHLY: '每月',
}

const WEEK_NAMES = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function formatTime(ms: number): string {
  const d = new Date(ms)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function formatDateTime(ms: number): string {
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${formatTime(ms)}`
}

export function SkillScheduleListModal({ onClose }: SkillScheduleListModalProps) {
  const [scheduledSkills, setScheduledSkills] = useState<
    { skill: Skill; nextTime: number | null }[]
  >([])

  useEffect(() => {
    const skills = loadScheduledSkills()
    const items = skills.map((skill) => {
      const config = skill.scheduleConfig!
      const nextTime = calculateNextTriggerTime(config)
      return { skill, nextTime }
    })
    setScheduledSkills(items)
  }, [])

  return (
    <div className="skill-schedule-list-overlay" onClick={onClose}>
      <div
        className="skill-schedule-list-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="skill-schedule-list-header">
          <h3 className="skill-schedule-list-title">⏰ 定时任务列表</h3>
          <button
            className="skill-schedule-list-close"
            onClick={onClose}
            type="button"
          >
            ×
          </button>
        </div>

        <div className="skill-schedule-list-body">
          {scheduledSkills.length === 0 ? (
            <div className="skill-schedule-list-empty">暂无定时任务</div>
          ) : (
            <div className="skill-schedule-list-items">
              {scheduledSkills.map(({ skill, nextTime }) => {
                const config = skill.scheduleConfig!
                const typeLabel = SCHEDULE_TYPE_LABELS[config.scheduleType]
                const timeStr = formatTime(config.targetTime)
                let extraInfo = ''
                if (config.scheduleType === 'ONCE') {
                  extraInfo = ` | 日期: ${formatDateTime(config.targetTime).slice(0, 10)}`
                } else if (config.scheduleType === 'WEEKLY' && config.repeatDays?.length) {
                  extraInfo = ` | 星期: ${config.repeatDays.map((d) => WEEK_NAMES[d]).join('、')}`
                }
                return (
                  <div key={skill.id} className="skill-schedule-list-item">
                    <div className="skill-schedule-list-item-title">
                      {skill.title}
                    </div>
                    <div className="skill-schedule-list-item-meta">
                      类型: {typeLabel} | 时间: {timeStr}
                      {extraInfo}
                    </div>
                    <div
                      className={`skill-schedule-list-item-next ${
                        nextTime && nextTime > Date.now()
                          ? 'skill-schedule-list-item-next-valid'
                          : 'skill-schedule-list-item-next-invalid'
                      }`}
                    >
                      {nextTime && nextTime > Date.now()
                        ? `下次执行: ${formatDateTime(nextTime)}`
                        : '已过期或无效'}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="skill-schedule-list-footer">
          <button
            type="button"
            className="skill-schedule-list-btn"
            onClick={onClose}
          >
            关闭
          </button>
        </div>
      </div>
    </div>
  )
}
