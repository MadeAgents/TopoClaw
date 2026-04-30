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

import { SCHEDULED_TASK_TEMPLATES } from './scheduledTaskTemplateData'
import type { FormPrefill } from './ScheduledTaskFormModal'
import './ScheduledTaskTemplatesModal.css'

interface ScheduledTaskTemplatesModalProps {
  onClose: () => void
  onPick: (prefill: FormPrefill) => void
}

export function ScheduledTaskTemplatesModal({ onClose, onPick }: ScheduledTaskTemplatesModalProps) {
  return (
    <div className="st-templates-overlay" onClick={onClose}>
      <div className="st-templates-modal" onClick={(e) => e.stopPropagation()}>
        <div className="st-templates-header">
          <span className="st-templates-title">从模板入手</span>
          <button type="button" className="st-templates-close" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="st-templates-grid">
          {SCHEDULED_TASK_TEMPLATES.map((t, i) => (
            <button
              key={i}
              type="button"
              className="st-templates-card"
              onClick={() => {
                onPick({ name: t.title, message: t.description })
                onClose()
              }}
            >
              <div className="st-templates-icon">{t.icon}</div>
              <div className="st-templates-body">
                <div className="st-templates-card-title">{t.title}</div>
                <div className="st-templates-card-desc">{t.description}</div>
              </div>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
