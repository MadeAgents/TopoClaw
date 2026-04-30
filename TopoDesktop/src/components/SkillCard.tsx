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

import { getSkillPlatformUiLabel, getSkillSourceCategoryLabel, isSkillSyncedFromService, type Skill } from '../services/api'
import { getSkillDisplayName } from '../services/skillNames'
import './SkillCard.css'

interface SkillCardProps {
  skill: Skill
  variant: 'my' | 'community'
  onDelete?: (skill: Skill) => void
  onCollect?: (skill: Skill) => void
  onExecute?: (skill: Skill) => void
  onShare?: (skill: Skill) => void
  onCardClick?: (skill: Skill) => void
  isCollected?: boolean
}

export function SkillCard({ skill, variant, onDelete, onCollect, onExecute, onShare, onCardClick, isCollected }: SkillCardProps) {
  const stepsPreview = skill.steps?.slice(0, 3) ?? []
  const hasMore = (skill.steps?.length ?? 0) > 3
  const displayTitle = getSkillDisplayName(skill.title)

  const isServerSkill = isSkillSyncedFromService(skill)
  const sourceCategoryLabel = getSkillSourceCategoryLabel(skill.source)
  const platformUiLabel = getSkillPlatformUiLabel(skill)
  const sourceBadgeText = skill.source === 'publichub' ? 'PublicHub' : skill.source === 'plaza' ? '技能广场' : 'TopHub'

  return (
    <div
      className={`skill-card ${onCardClick ? 'skill-card-clickable' : ''}`}
      onClick={onCardClick ? () => onCardClick(skill) : undefined}
      onKeyDown={onCardClick ? (e) => e.key === 'Enter' && onCardClick(skill) : undefined}
      role={onCardClick ? 'button' : undefined}
      tabIndex={onCardClick ? 0 : undefined}
    >
      <div className="skill-card-header">
        <span className="skill-card-title">
          {displayTitle}
          <span className={`skill-card-source ${sourceBadgeText === 'TopHub' ? 'skill-card-source-tophub' : 'skill-card-source-publichub'}`}>{sourceBadgeText}</span>
        </span>
        {skill.isHot && <span className="skill-card-hot">热门</span>}
        {skill.isCertified && <span className="skill-card-certified">✓ 认证</span>}
        {skill.scheduleConfig?.isEnabled && (
          <span className="skill-card-schedule">定时</span>
        )}
      </div>
      {skill.author && (
        <div className="skill-card-author">作者：{skill.author}</div>
      )}
      {skill.originalPurpose && (
        <div className="skill-card-purpose">用途：{skill.originalPurpose}</div>
      )}
      {(skill.downloads !== undefined || skill.stars !== undefined) && (
        <div className="skill-card-stats">
          {skill.downloads !== undefined && (
            <span className="skill-card-stat-item">⬇ {skill.downloads}</span>
          )}
          {skill.stars !== undefined && (
            <span className="skill-card-stat-item">★ {skill.stars}</span>
          )}
        </div>
      )}
      {(skill.tags && skill.tags.length > 0) || platformUiLabel !== null || sourceCategoryLabel ? (
        <div className="skill-card-tags">
          {platformUiLabel !== null && (
            <span className="skill-card-tag skill-card-tag-platform">{platformUiLabel}</span>
          )}
          {sourceCategoryLabel && (
            <span className="skill-card-tag">{sourceCategoryLabel}</span>
          )}
          {skill.tags && skill.tags.slice(0, 3).map((tag, i) => (
            <span key={i} className="skill-card-tag">{tag}</span>
          ))}
          {skill.tags && skill.tags.length > 3 && <span className="skill-card-tag-more">+{skill.tags.length - 3}</span>}
        </div>
      ) : null}
      {!isServerSkill && stepsPreview.length > 0 && (
        <div className="skill-card-steps">
          <span className="skill-card-steps-label">步骤：</span>
          {stepsPreview.join('、')}
          {hasMore && '…'}
        </div>
      )}
      <div className="skill-card-actions" onClick={(e) => e.stopPropagation()}>
        {variant === 'my' && onExecute && !isServerSkill && (
          <button
            type="button"
            className="skill-card-btn skill-card-btn-execute"
            onClick={() => onExecute(skill)}
          >
            ▶ 执行
          </button>
        )}
        {variant === 'my' && onDelete && (
          <button
            type="button"
            className="skill-card-btn skill-card-btn-danger"
            onClick={() => onDelete(skill)}
          >
            删除
          </button>
        )}
        {variant === 'my' && onShare && (
          <button
            type="button"
            className="skill-card-btn skill-card-btn-outline"
            onClick={() => onShare(skill)}
          >
            分享
          </button>
        )}
        {variant === 'community' && onCollect && (
          <button
            type="button"
            className={`skill-card-btn ${isCollected ? 'skill-card-btn-collected' : 'skill-card-btn-primary'}`}
            onClick={() => onCollect(skill)}
            disabled={isCollected}
          >
            {isCollected ? '已收藏' : '收藏'}
          </button>
        )}
      </div>
    </div>
  )
}
