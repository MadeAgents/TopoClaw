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

import { getClawHubSkillPageUrl, isSkillSyncedFromService, type Skill } from '../services/api'
import { getSkillDisplayName } from '../services/skillNames'
import './SkillDetailModal.css'

interface SkillDetailModalProps {
  skill: Skill
  onClose: () => void
  onCollect?: (skill: Skill) => void
  onDelete?: (skill: Skill) => void
  onSchedule?: (skill: Skill) => void
  onEdit?: (skill: Skill) => void
  onExecute?: (skill: Skill) => void
  isCollected?: boolean
}

export function SkillDetailModal({
  skill,
  onClose,
  onCollect,
  onDelete,
  onSchedule,
  onEdit,
  onExecute,
  isCollected,
}: SkillDetailModalProps) {
  const steps = skill.steps ?? []
  const displayTitle = getSkillDisplayName(skill.title)

  const isServerSkill = isSkillSyncedFromService(skill)
  const sourceBadgeText = skill.source === 'publichub' ? 'PublicHub' : skill.source === 'plaza' ? '技能广场' : 'TopHub'

  return (
    <div className="skill-detail-overlay" onClick={onClose}>
      <div className="skill-detail-modal" onClick={(e) => e.stopPropagation()}>
        <div className="skill-detail-header">
          <h3 className="skill-detail-title">{displayTitle}</h3>
          <span className={`skill-detail-source ${sourceBadgeText === 'TopHub' ? 'skill-detail-source-tophub' : 'skill-detail-source-publichub'}`}>{sourceBadgeText}</span>
          {skill.isHot && <span className="skill-detail-hot">热门</span>}
          {skill.isCertified && <span className="skill-detail-certified">✓ 认证</span>}
          <button className="skill-detail-close" onClick={onClose} type="button">
            ×
          </button>
        </div>

        <div className="skill-detail-body">
          {skill.originalPurpose && (
            <div className="skill-detail-section">
              <label>用途</label>
              <p>{skill.originalPurpose}</p>
            </div>
          )}

          {skill.source === 'publichub' && (
            <div className="skill-detail-section">
              <label>PublicHub 信息</label>
              <div className="skill-detail-publichub-info">
                {skill.author && (
                  <div className="skill-detail-publichub-item">
                    <span className="skill-detail-publichub-label">作者：</span>
                    <span className="skill-detail-publichub-value">{skill.author}</span>
                  </div>
                )}
                {(skill.downloads !== undefined || skill.stars !== undefined) && (
                  <div className="skill-detail-publichub-item">
                    <span className="skill-detail-publichub-label">统计：</span>
                    {skill.downloads !== undefined && (
                      <span className="skill-detail-publichub-stat">⬇ {skill.downloads}</span>
                    )}
                    {skill.stars !== undefined && (
                      <span className="skill-detail-publichub-stat">★ {skill.stars}</span>
                    )}
                  </div>
                )}
                {skill.tags && skill.tags.length > 0 && (
                  <div className="skill-detail-publichub-item">
                    <span className="skill-detail-publichub-label">标签：</span>
                    <div className="skill-detail-publichub-tags">
                      {skill.tags.map((tag, i) => (
                        <span key={i} className="skill-detail-publichub-tag">{tag}</span>
                      ))}
                    </div>
                  </div>
                )}
                {skill.isCertified && (
                  <div className="skill-detail-publichub-item">
                    <span className="skill-detail-publichub-certified">✓ 已通过安全认证</span>
                  </div>
                )}
                {getClawHubSkillPageUrl(skill) && (
                  <div className="skill-detail-publichub-item">
                    <button
                      type="button"
                      className="skill-detail-publichub-link"
                      onClick={() => {
                        const url = getClawHubSkillPageUrl(skill)!
                        if (window.electronAPI?.openExternal) {
                          void window.electronAPI.openExternal(url)
                        } else {
                          window.open(url, '_blank')
                        }
                      }}
                    >
                      在 ClawHub 查看 →
                    </button>
                  </div>
                )}
              </div>
            </div>
          )}

          {!isServerSkill && steps.length > 0 && (
            <div className="skill-detail-section">
              <label>步骤</label>
              <ol className="skill-detail-steps">
                {steps.map((step, i) => (
                  <li key={i}>{step}</li>
                ))}
              </ol>
            </div>
          )}
        </div>

        {(onCollect || onDelete || onSchedule || onEdit || onExecute) && (
          <div className="skill-detail-actions">
            {onExecute && !isServerSkill && (
              <button
                type="button"
                className="skill-detail-btn skill-detail-btn-execute"
                onClick={() => onExecute(skill)}
              >
                ▶ 执行
              </button>
            )}
            {onEdit && !isServerSkill && (
              <button
                type="button"
                className="skill-detail-btn skill-detail-btn-edit"
                onClick={() => {
                  onEdit(skill)
                  onClose()
                }}
              >
                编辑
              </button>
            )}
            {onSchedule && (
              <button
                type="button"
                className="skill-detail-btn skill-detail-btn-schedule"
                onClick={() => onSchedule(skill)}
              >
                ⏰ {skill.scheduleConfig?.isEnabled ? '修改定时' : '定时'}
              </button>
            )}
            {onCollect && (
              <button
                type="button"
                className={`skill-detail-btn ${isCollected ? 'skill-detail-btn-collected' : 'skill-detail-btn-primary'}`}
                onClick={() => onCollect(skill)}
                disabled={isCollected}
              >
                {isCollected ? '已收藏' : '收藏'}
              </button>
            )}
            {onDelete && (
              <button
                type="button"
                className="skill-detail-btn skill-detail-btn-danger"
                onClick={() => {
                  if (window.confirm(`确定要删除技能「${skill.title}」吗？`)) {
                    onDelete(skill)
                    onClose()
                  }
                }}
              >
                删除
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
