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

import { useEffect, useRef } from 'react'
import './AddMenuDropdown.css'

export type AddMenuAction = 'addFriend' | 'createGroup' | 'addAssistant'

interface AddMenuDropdownProps {
  open: boolean
  onClose: () => void
  onSelect: (action: AddMenuAction) => void
  anchorRef: React.RefObject<HTMLButtonElement | null>
}

const MENU_ITEMS: { key: AddMenuAction; label: string }[] = [
  { key: 'addFriend', label: '添加好友' },
  { key: 'createGroup', label: '创建群组' },
  { key: 'addAssistant', label: '添加小助手' },
]

export function AddMenuDropdown({ open, onClose, onSelect, anchorRef }: AddMenuDropdownProps) {
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node
      if (
        menuRef.current?.contains(target) ||
        anchorRef.current?.contains(target)
      ) {
        return
      }
      onClose()
    }
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [open, onClose, anchorRef])

  if (!open) return null

  return (
    <div
      ref={menuRef}
      className="add-menu-dropdown"
      style={{
        top: anchorRef.current
          ? anchorRef.current.getBoundingClientRect().bottom + 4
          : 0,
        left: anchorRef.current
          ? anchorRef.current.getBoundingClientRect().left
          : 0,
      }}
    >
      {MENU_ITEMS.map(({ key, label }) => (
        <button
          key={key}
          type="button"
          className="add-menu-item"
          onClick={() => {
            onSelect(key)
            onClose()
          }}
        >
          {label}
        </button>
      ))}
    </div>
  )
}
