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

import type { Skill } from '../services/api'
import { sendExecuteCommand } from '../services/api'
import { getImei } from '../services/storage'
import './SkillDueModal.css'

interface SkillDueModalProps {
  skill: Skill
  onClose: () => void
}

export function SkillDueModal({ skill, onClose }: SkillDueModalProps) {
  const handleExecute = async () => {
    const platform = skill.executionPlatform ?? 'mobile'
    if (platform === 'pc') {
      window.alert('暂不支持')
      return
    }
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定手机')
      return
    }
    try {
      const uuid = `schedule_${skill.id}_${Date.now()}`
      const res = await sendExecuteCommand(imei, skill.title, uuid, skill.steps)
      if (!res.success) {
        window.alert('手机端不在线，请确保手机已打开应用并保持连接')
        return
      }
      window.alert('已向手机发送执行指令')
      onClose()
    } catch (e) {
      window.alert('执行失败：' + (e instanceof Error ? e.message : '网络错误'))
    }
  }

  return (
    <div className="skill-due-overlay" onClick={onClose}>
      <div className="skill-due-modal" onClick={(e) => e.stopPropagation()}>
        <div className="skill-due-header">
          <h3 className="skill-due-title">⏰ 定时提醒</h3>
          <button className="skill-due-close" onClick={onClose} type="button">
            ×
          </button>
        </div>
        <div className="skill-due-body">
          <p className="skill-due-desc">「{skill.title}」到执行时间了</p>
          <p className="skill-due-hint">
            {(skill.executionPlatform ?? 'mobile') === 'pc'
              ? 'PC 端技能暂不支持执行'
              : '点击执行后将指令发送到手机'}
          </p>
        </div>
        <div className="skill-due-actions">
          <button
            type="button"
            className="skill-due-btn skill-due-btn-cancel"
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            className="skill-due-btn skill-due-btn-execute"
            onClick={handleExecute}
            disabled={(skill.executionPlatform ?? 'mobile') === 'pc'}
          >
            ▶ 执行
          </button>
        </div>
      </div>
    </div>
  )
}
