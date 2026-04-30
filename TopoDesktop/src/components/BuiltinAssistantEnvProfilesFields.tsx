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

import type { Dispatch, SetStateAction } from 'react'
import type { BuiltinModelProfileRow } from '../services/builtinAssistantConfig'

function emptyProfile(): BuiltinModelProfileRow {
  return { model: '', apiBase: '', apiKey: '' }
}

type BuiltinAssistantEnvProfilesFieldsProps = {
  nonGuiProfiles: BuiltinModelProfileRow[]
  guiProfiles: BuiltinModelProfileRow[]
  setNonGuiProfiles: Dispatch<SetStateAction<BuiltinModelProfileRow[]>>
  setGuiProfiles: Dispatch<SetStateAction<BuiltinModelProfileRow[]>>
  /** false：仅展示 chat（如 GroupManager 编辑） */
  showGuiSection?: boolean
  /** 「获取本地配置」成功后的预设列表，用于每行下拉快速套用 */
  localNonGuiPresets?: BuiltinModelProfileRow[]
  localGuiPresets?: BuiltinModelProfileRow[]
}

function rowMatchesPreset(row: BuiltinModelProfileRow, p: BuiltinModelProfileRow): boolean {
  return row.model === p.model && row.apiBase === p.apiBase && row.apiKey === p.apiKey
}

export function BuiltinAssistantEnvProfilesFields({
  nonGuiProfiles,
  guiProfiles,
  setNonGuiProfiles,
  setGuiProfiles,
  showGuiSection = true,
  localNonGuiPresets = [],
  localGuiPresets = [],
}: BuiltinAssistantEnvProfilesFieldsProps) {
  const patchNonGui = (index: number, patch: Partial<BuiltinModelProfileRow>) => {
    setNonGuiProfiles((rows) => rows.map((r, j) => (j === index ? { ...r, ...patch } : r)))
  }
  const patchGui = (index: number, patch: Partial<BuiltinModelProfileRow>) => {
    setGuiProfiles((rows) => rows.map((r, j) => (j === index ? { ...r, ...patch } : r)))
  }

  return (
    <>
      <p className="new-assistant-hint" style={{ marginTop: 12, marginBottom: 8 }}>
        chat（agents.defaults / custom）：可配置多套，模型名须唯一；在聊天输入栏下方可按模型名切换并仅重启当前内置助手。
      </p>
      {nonGuiProfiles.map((row, i) => (
        <div
          key={`ng-${i}`}
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
            marginBottom: 12,
            paddingBottom: 12,
            borderBottom: '1px solid var(--color-border, #e5e7eb)',
          }}
        >
          {localNonGuiPresets.length > 0 && (
            <div>
              <label style={{ fontSize: 12, color: '#666' }}>从本地预设选择</label>
              <select
                className="new-assistant-input"
                style={{ width: '100%', padding: '8px 10px', cursor: 'pointer' }}
                value={localNonGuiPresets.find((p) => rowMatchesPreset(row, p))?.model ?? ''}
                onChange={(e) => {
                  const v = e.target.value
                  if (!v) return
                  const pr = localNonGuiPresets.find((p) => p.model === v)
                  if (pr) patchNonGui(i, { model: pr.model, apiBase: pr.apiBase, apiKey: pr.apiKey })
                }}
              >
                <option value="">— 手动填写 —</option>
                {localNonGuiPresets.map((p) => (
                  <option key={p.model} value={p.model}>
                    {p.model}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div>
            <label style={{ fontSize: 12, color: '#666' }}>模型名</label>
            <input
              type="text"
              placeholder="如：gpt-4o-mini"
              value={row.model}
              onChange={(e) => patchNonGui(i, { model: e.target.value })}
              className="new-assistant-input"
            />
          </div>
          <div>
            <label style={{ fontSize: 12, color: '#666' }}>API Base</label>
            <input
              type="text"
              placeholder="https://api.openai.com/v1"
              value={row.apiBase}
              onChange={(e) => patchNonGui(i, { apiBase: e.target.value })}
              className="new-assistant-input"
            />
          </div>
          <div>
            <label style={{ fontSize: 12, color: '#666' }}>API Key</label>
            <input
              type="password"
              placeholder="sk-..."
              value={row.apiKey}
              onChange={(e) => patchNonGui(i, { apiKey: e.target.value })}
              className="new-assistant-input"
            />
          </div>
          {nonGuiProfiles.length > 1 && (
            <button
              type="button"
              className="new-assistant-cancel"
              style={{ alignSelf: 'flex-start' }}
              onClick={() => setNonGuiProfiles((rows) => rows.filter((_, j) => j !== i))}
            >
              删除此条
            </button>
          )}
        </div>
      ))}
      <button
        type="button"
        className="new-assistant-cancel"
        style={{ marginBottom: 16 }}
        onClick={() => setNonGuiProfiles((r) => [...r, emptyProfile()])}
      >
        添加 chat 配置
      </button>

      {showGuiSection && (
        <>
          <p className="new-assistant-hint" style={{ marginTop: 4, marginBottom: 8 }}>
            GUI（agents.gui / custom2）：多套配置，模型名须唯一。
          </p>
          {guiProfiles.map((row, i) => (
            <div
              key={`g-${i}`}
              style={{
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
                marginBottom: 12,
                paddingBottom: 12,
                borderBottom: '1px solid var(--color-border, #e5e7eb)',
              }}
            >
              {localGuiPresets.length > 0 && (
                <div>
                  <label style={{ fontSize: 12, color: '#666' }}>从本地预设选择</label>
                  <select
                    className="new-assistant-input"
                    style={{ width: '100%', padding: '8px 10px', cursor: 'pointer' }}
                    value={localGuiPresets.find((p) => rowMatchesPreset(row, p))?.model ?? ''}
                    onChange={(e) => {
                      const v = e.target.value
                      if (!v) return
                      const pr = localGuiPresets.find((p) => p.model === v)
                      if (pr) patchGui(i, { model: pr.model, apiBase: pr.apiBase, apiKey: pr.apiKey })
                    }}
                  >
                    <option value="">— 手动填写 —</option>
                    {localGuiPresets.map((p) => (
                      <option key={p.model} value={p.model}>
                        {p.model}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              <div>
                <label style={{ fontSize: 12, color: '#666' }}>模型名</label>
                <input
                  type="text"
                  placeholder="如：Qwen3-VL-32B-Instruct-rl"
                  value={row.model}
                  onChange={(e) => patchGui(i, { model: e.target.value })}
                  className="new-assistant-input"
                />
              </div>
              <div>
                <label style={{ fontSize: 12, color: '#666' }}>API Base</label>
                <input
                  type="text"
                  placeholder={import.meta.env.VITE_BUILTIN_GUI_API_BASE || 'https://your-api-base/v1'}
                  value={row.apiBase}
                  onChange={(e) => patchGui(i, { apiBase: e.target.value })}
                  className="new-assistant-input"
                />
              </div>
              <div>
                <label style={{ fontSize: 12, color: '#666' }}>API Key</label>
                <input
                  type="password"
                  placeholder="sk-..."
                  value={row.apiKey}
                  onChange={(e) => patchGui(i, { apiKey: e.target.value })}
                  className="new-assistant-input"
                />
              </div>
              {guiProfiles.length > 1 && (
                <button
                  type="button"
                  className="new-assistant-cancel"
                  style={{ alignSelf: 'flex-start' }}
                  onClick={() => setGuiProfiles((rows) => rows.filter((_, j) => j !== i))}
                >
                  删除此条
                </button>
              )}
            </div>
          ))}
          <button
            type="button"
            className="new-assistant-cancel"
            onClick={() => setGuiProfiles((r) => [...r, emptyProfile()])}
          >
            添加 GUI 配置
          </button>
        </>
      )}
    </>
  )
}
