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
import { parseAssistantUrl, addCustomAssistant, getCustomAssistants } from '../services/customAssistants'
import { syncCustomAssistantsToCloud } from '../services/api'
import { getImei } from '../services/storage'
import './AddAssistantModal.css'

interface AddAssistantModalProps {
  onClose: () => void
  onAdded: () => void
}

export function AddAssistantModal({ onClose, onAdded }: AddAssistantModalProps) {
  const [link, setLink] = useState('')
  const [error, setError] = useState('')

  const handleAdd = () => {
    const trimmed = link.trim()
    if (!trimmed) {
      setError('请输入小助手链接')
      return
    }
    const assistant = parseAssistantUrl(trimmed, getImei() || undefined)
    if (!assistant) {
      setError('链接格式不正确，请检查')
      return
    }
    addCustomAssistant(assistant)
    const imei = getImei()
    if (imei) {
      syncCustomAssistantsToCloud(imei, getCustomAssistants()).catch(() => {})
    }
    onAdded()
    onClose()
  }

  return (
    <div className="add-assistant-overlay" onClick={onClose}>
      <div className="add-assistant-modal" onClick={(e) => e.stopPropagation()}>
        <div className="add-assistant-header">
          <span className="add-assistant-title">添加小助手</span>
          <button className="add-assistant-close" onClick={onClose} aria-label="关闭">×</button>
        </div>
        <p className="add-assistant-hint">粘贴小助手链接，如 assistant://add?type=chat&url=https://...&name=... 可选 &amp;multiSession=1 支持多会话</p>
        <textarea
          className="add-assistant-input"
          placeholder="assistant://add?type=chat&url=https%3A%2F%2F..."
          value={link}
          onChange={(e) => { setLink(e.target.value); setError('') }}
          rows={3}
        />
        {error && <p className="add-assistant-error">{error}</p>}
        <div className="add-assistant-actions">
          <button className="add-assistant-cancel" onClick={onClose}>取消</button>
          <button className="add-assistant-add" onClick={handleAdd}>添加</button>
        </div>
      </div>
    </div>
  )
}
