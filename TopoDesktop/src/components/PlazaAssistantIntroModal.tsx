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

import type { PlazaAssistantItem } from '../services/api'
import { toAvatarSrcLikeContacts } from '../utils/avatar'
import './PlazaAssistantIntroModal.css'

interface PlazaAssistantIntroModalProps {
  item: PlazaAssistantItem
  onClose: () => void
  /** 主操作：添加或从广场删除 */
  onPrimaryAction: () => void
  primaryLabel: string
  primaryDisabled?: boolean
  /** 为 true 时使用危险按钮样式（下架） */
  primaryDanger?: boolean
  /** 切换点赞 */
  onToggleLike?: () => void
  likeBusy?: boolean
}

export function PlazaAssistantIntroModal({
  item,
  onClose,
  onPrimaryAction,
  primaryLabel,
  primaryDisabled,
  primaryDanger,
  onToggleLike,
  likeBusy = false,
}: PlazaAssistantIntroModalProps) {
  const assistantSrc = toAvatarSrcLikeContacts(item.avatar)
  const creatorSrc = toAvatarSrcLikeContacts(item.creator_avatar)
  const creatorLine = item.creator_imei?.trim() || '—'
  const creatorLetter =
    creatorLine === '—'
      ? '创'
      : creatorLine.includes(' · ')
        ? (creatorLine.split(' · ').pop() ?? creatorLine).trim().slice(0, 1) || '创'
        : creatorLine.slice(0, 1) || '创'

  return (
    <div className="plaza-intro-overlay" onClick={onClose}>
      <div className="plaza-intro-modal" onClick={(e) => e.stopPropagation()}>
        <div className="plaza-intro-body">
          <div
            className="plaza-intro-assistant-avatar"
            data-copy-image-src={assistantSrc || undefined}
            style={{
              backgroundImage: assistantSrc ? `url(${assistantSrc})` : undefined,
            }}
          >
            {!assistantSrc && (item.name?.slice(0, 1) ?? '助')}
          </div>
          <div className="plaza-intro-name">{item.name}</div>
          <div className="plaza-intro-section-label">简介</div>
          <div className="plaza-intro-intro">{item.intro?.trim() ? item.intro : '暂无简介'}</div>
          <div className="plaza-intro-section-label">助手域名</div>
          <div className="plaza-intro-url" title={item.baseUrl}>
            {item.baseUrl || '—'}
          </div>
          <div className="plaza-intro-section-label">创建者</div>
          <div className="plaza-intro-creator">
            <div
              className="plaza-intro-creator-avatar"
              data-copy-image-src={creatorSrc || undefined}
              style={{
                backgroundImage: creatorSrc ? `url(${creatorSrc})` : undefined,
              }}
            >
              {!creatorSrc && creatorLetter}
            </div>
            <div className="plaza-intro-creator-text">{creatorLine}</div>
          </div>
        </div>
        <div className="plaza-intro-actions">
          <span className="plaza-intro-actions-spacer" aria-hidden />
          <button type="button" className="plaza-intro-btn-secondary" onClick={onClose}>
            关闭
          </button>
          {onToggleLike && (
            <>
              <button
                type="button"
                className={`plaza-intro-like-btn ${item.liked_by_me ? 'liked' : ''}`}
                onClick={onToggleLike}
                disabled={likeBusy}
                aria-label="点赞"
                title="点赞"
              >
                ♥
              </button>
              <span className="plaza-intro-like-count">{item.likes_count ?? 0}</span>
            </>
          )}
          <button
            type="button"
            className={primaryDanger ? 'plaza-intro-btn-danger' : 'plaza-intro-btn-primary'}
            onClick={onPrimaryAction}
            disabled={primaryDisabled}
          >
            {primaryLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
