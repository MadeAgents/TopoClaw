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

import './DesktopUpdateDialog.css'

export type DesktopUpdateDialogProps = {
  open: boolean
  forceUpdate: boolean
  currentVersion: string
  latestVersion: string
  message: string
  updateUrl: string
  onDismiss: () => void
}

export function DesktopUpdateDialog({
  open,
  forceUpdate,
  currentVersion,
  latestVersion,
  message,
  updateUrl,
  onDismiss,
}: DesktopUpdateDialogProps) {
  if (!open) return null

  const handleUpdate = () => {
    const url = updateUrl?.trim()
    if (url) {
      void window.electronAPI?.openExternal?.(url)
    }
    if (!forceUpdate) onDismiss()
  }

  const lines = message.split(/\r?\n/).map((l) => l.trim()).filter((l) => l.length > 0)
  const introLine = lines[0]
  const bulletLines = lines.slice(1)

  return (
    <div className="desktop-update-overlay" role="dialog" aria-modal="true" aria-labelledby="desktop-update-title">
      <div className="desktop-update-card">
        <h2 id="desktop-update-title" className="desktop-update-title">
          检测到新版本
        </h2>
        <p className="desktop-update-versions">
          当前版本：<strong>{currentVersion}</strong>
          <span className="desktop-update-sep">·</span>
          最新版本：<strong>{latestVersion}</strong>
        </p>
        <div className="desktop-update-message">
          {lines.length === 0 ? (
            <p>建议更新到最新版本以获得更好的体验。</p>
          ) : (
            <>
              <p className="desktop-update-intro">{introLine}</p>
              {bulletLines.length > 0 ? (
                <ul className="desktop-update-bullets">
                  {bulletLines.map((line, i) => (
                    <li key={i}>{line}</li>
                  ))}
                </ul>
              ) : null}
            </>
          )}
        </div>
        <div className="desktop-update-actions">
          {!forceUpdate && (
            <button type="button" className="desktop-update-btn desktop-update-btn-secondary" onClick={onDismiss}>
              暂不更新
            </button>
          )}
          <button type="button" className="desktop-update-btn desktop-update-btn-primary" onClick={handleUpdate}>
            立即更新
          </button>
        </div>
      </div>
    </div>
  )
}
