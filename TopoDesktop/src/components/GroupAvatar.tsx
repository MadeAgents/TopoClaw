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

/**
 * 群组头像 - 2x2 网格拼接，与端侧 GroupAvatarHelper 布局一致
 * 左上(0) 右上(1) 左下(2) 右下(3)，padding 2px
 */
import './GroupAvatar.css'

export interface GroupAvatarProps {
  /** 4 个头像源：base64 或 data URL，空则显示占位符（首字母） */
  avatars: (string | undefined)[]
  /** 占位符显示的首字母，与 avatars 一一对应 */
  placeholders?: string[]
  size?: number
  className?: string
}

const DEFAULT_PLACEHOLDERS = ['', '', '', '']

function toImageSrc(avatar: string | undefined): string | null {
  if (!avatar) return null
  if (avatar.startsWith('data:') || avatar.startsWith('http') || avatar.startsWith('/') || avatar.startsWith('.')) return avatar
  return `data:image/png;base64,${avatar}`
}

export function GroupAvatar({ avatars, placeholders = DEFAULT_PLACEHOLDERS, size = 44, className = '' }: GroupAvatarProps) {
  const padding = Math.max(1, Math.floor(size / 22))
  const cellSize = Math.floor((size - padding) / 2)

  return (
    <div className={`group-avatar ${className}`} style={{ width: size, height: size }}>
      <div className="group-avatar-grid" style={{ gap: padding }}>
        {[0, 1, 2, 3].map((i) => {
          const src = toImageSrc(avatars[i])
          const letter = placeholders[i] ?? ''
          const isEmptyCell = !src && !letter
          return (
            <div
              key={i}
              className={`group-avatar-cell ${isEmptyCell ? 'group-avatar-cell-empty' : ''}`}
              style={{
                width: cellSize,
                height: cellSize,
                fontSize: Math.max(10, Math.floor(cellSize * 0.5)),
              }}
            >
              {src ? (
                <img src={src} alt="" />
              ) : (
                <span className="group-avatar-placeholder">{letter}</span>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
