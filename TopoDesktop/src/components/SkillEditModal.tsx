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

import { useEffect, useRef, useState } from 'react'
import type { Skill } from '../services/api'
import {
  getBuiltinAssistantConfig,
  getBuiltinModelProfiles,
  saveBuiltinAssistantConfig,
  saveBuiltinModelProfiles,
  type BuiltinModelProfileRow,
} from '../services/builtinAssistantConfig'
import './SkillEditModal.css'

const BUILTIN_GENERATE_IMAGE_SKILL_ID = 'builtin_generate_image_openai_compatible'

type GenerateImageConfig = {
  model: string
  baseUrl: string
  apiKey: string
  prompt: string
  size: string
  outputPath: string
}

type GenerateImageProfileOption = {
  id: string
  label: string
  source: 'chat' | 'legacy'
  row: BuiltinModelProfileRow
}

const DEFAULT_GENERATE_IMAGE_CONFIG: GenerateImageConfig = {
  model: 'gpt-image-1',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  prompt: '一只戴宇航员头盔的橘猫，电影级光影，超高细节',
  size: '1024x1024',
  outputPath: 'generated_image.png',
}

function parseQuotedValue(line: string, key: string): string | null {
  const m = line.match(new RegExp(`^\\s*${key}\\s*=\\s*['"](.+)['"]\\s*$`))
  return m?.[1] ?? null
}

function parseGenerateImageConfig(steps: string[]): GenerateImageConfig {
  const conf: GenerateImageConfig = { ...DEFAULT_GENERATE_IMAGE_CONFIG }
  for (const line of steps) {
    const model = parseQuotedValue(line, 'MODEL')
    if (model != null) conf.model = model
    const baseUrl = parseQuotedValue(line, 'BASE_URL')
    if (baseUrl != null) conf.baseUrl = baseUrl
    const apiKey = parseQuotedValue(line, 'API_KEY')
    if (apiKey != null) conf.apiKey = apiKey === '请替换成你的Key' ? '' : apiKey
    const prompt = parseQuotedValue(line, 'PROMPT')
    if (prompt != null) conf.prompt = prompt
    const size = parseQuotedValue(line, 'SIZE')
    if (size != null) conf.size = size
    const outputPath = parseQuotedValue(line, 'OUTPUT_PATH')
    if (outputPath != null) conf.outputPath = outputPath
  }
  return conf
}

function buildGenerateImageSteps(conf: GenerateImageConfig): string[] {
  const pyStr = (value: string): string => value.replace(/\\/g, '\\\\').replace(/'/g, "\\'")
  return [
    'import os',
    'import base64',
    'import time',
    'import requests',
    '',
    '# ===== 必填配置（按你的模型服务商修改） =====',
    `MODEL = '${pyStr(conf.model)}'`,
    `BASE_URL = '${pyStr(conf.baseUrl)}'`,
    `API_KEY = '${pyStr(conf.apiKey || '请替换成你的Key')}'`,
    '# ========================================',
    '',
    '# 生成参数',
    `PROMPT = '${pyStr(conf.prompt)}'`,
    `SIZE = '${pyStr(conf.size)}'`,
    `OUTPUT_PATH = '${pyStr(conf.outputPath)}'`,
    '',
    'base_url = BASE_URL.rstrip("/")',
    'api_key = "".join((API_KEY or "").split())',
    'model = (MODEL or "").strip()',
    '',
    'if not api_key or api_key == "请替换成你的Key":',
    '    raise RuntimeError("请先填写 API_KEY")',
    'if not model:',
    '    raise RuntimeError("请先填写 MODEL")',
    'if not base_url:',
    '    raise RuntimeError("请先填写 BASE_URL")',
    '',
    "url = f'{base_url}/images/generations'",
    'headers = {',
    "    'Authorization': f'Bearer {api_key}',",
    "    'Content-Type': 'application/json',",
    '}',
    'payload = {',
    "    'model': model,",
    "    'prompt': PROMPT,",
    "    'size': SIZE,",
    '}',
    '',
    'last_error = None',
    'for attempt in range(3):',
    '    try:',
    '        resp = requests.post(url, headers=headers, json=payload, timeout=120)',
    '        if resp.status_code >= 400:',
    '            msg = resp.text',
    '            retryable = resp.status_code in (404, 429, 500, 502, 503, 504)',
    '            if retryable and attempt < 2:',
    '                time.sleep(1.2 * (attempt + 1))',
    '                continue',
    "            raise RuntimeError(f'图片生成失败(HTTP {resp.status_code}): {msg}')",
    '        data = resp.json()',
    '        break',
    '    except Exception as e:',
    '        last_error = e',
    '        if attempt >= 2:',
    '            raise',
    '        time.sleep(1.2 * (attempt + 1))',
    "if 'data' not in locals():",
    "    raise RuntimeError(f'图片生成失败: {last_error}')",
    '',
    "items = data.get('data') or []",
    'if not items:',
    "    raise RuntimeError(f'接口返回为空: {data}')",
    '',
    "b64 = items[0].get('b64_json')",
    "image_url = items[0].get('url')",
    '',
    'if b64:',
    "    with open(OUTPUT_PATH, 'wb') as f:",
    '        f.write(base64.b64decode(b64))',
    'elif image_url:',
    '    img_resp = requests.get(image_url, timeout=120)',
    '    img_resp.raise_for_status()',
    "    with open(OUTPUT_PATH, 'wb') as f:",
    '        f.write(img_resp.content)',
    'else:',
    "    raise RuntimeError(f'未返回图片数据: {items[0]}')",
    '',
    "print(f'图片已生成: {os.path.abspath(OUTPUT_PATH)}')",
  ]
}

interface SkillEditModalProps {
  skill: Skill
  /** 是否为新建模式（新建技能 vs 编辑技能） */
  createMode?: boolean
  onClose: () => void
  onSave: (updated: Skill) => void
  onSchedule: (skill: Skill) => void
  /** 取消定时时调用，仅更新 schedule，不关闭弹窗 */
  onScheduleChange?: (config: Skill['scheduleConfig']) => void
}

export function SkillEditModal({
  skill,
  createMode = false,
  onClose,
  onSave,
  onSchedule,
  onScheduleChange,
}: SkillEditModalProps) {
  const isGenerateImageSkill = skill.id === BUILTIN_GENERATE_IMAGE_SKILL_ID
  const [title, setTitle] = useState(skill.title)
  const [originalPurpose, setOriginalPurpose] = useState(skill.originalPurpose ?? '')
  const [steps, setSteps] = useState<string[]>(
    (skill.steps?.length ?? 0) > 0 ? [...skill.steps] : ['']
  )
  const [generateImageConfig, setGenerateImageConfig] = useState<GenerateImageConfig>(
    parseGenerateImageConfig(skill.steps ?? [])
  )
  const [profileOptions, setProfileOptions] = useState<GenerateImageProfileOption[]>([])
  const [selectedProfileId, setSelectedProfileId] = useState('')
  const [profilesLoading, setProfilesLoading] = useState(false)
  const [profilesError, setProfilesError] = useState('')
  const initProfileAppliedRef = useRef(false)
  const [showApiKey, setShowApiKey] = useState(false)
  const [executionPlatform, setExecutionPlatform] = useState<'mobile' | 'pc'>(
    skill.executionPlatform ?? 'mobile'
  )

  const addStep = () => setSteps((s) => [...s, ''])
  const removeStep = (idx: number) =>
    setSteps((s) => s.filter((_, i) => i !== idx))
  const updateStep = (idx: number, val: string) =>
    setSteps((s) => {
      const next = [...s]
      next[idx] = val
      return next
    })

  const handleSave = () => {
    const persistGenerateImageProfile = async (conf: GenerateImageConfig): Promise<{ ok: boolean; error?: string }> => {
      const model = conf.model.trim()
      const apiBase = conf.baseUrl.trim()
      const apiKey = conf.apiKey.trim()
      if (!model || !apiBase) {
        return { ok: false, error: '模型名和服务 URL 不能为空' }
      }

      const profileRes = await getBuiltinModelProfiles()
      if (profileRes.ok) {
        const nextNonGui = [...profileRes.nonGuiProfiles]
        const idx = nextNonGui.findIndex((row) => row.model === model)
        const updated: BuiltinModelProfileRow = { model, apiBase, apiKey }
        if (idx >= 0) {
          nextNonGui[idx] = updated
        } else {
          nextNonGui.push(updated)
        }
        const saveRes = await saveBuiltinModelProfiles({
          nonGuiProfiles: nextNonGui,
          activeImageModel: model,
        })
        return saveRes.ok ? { ok: true } : { ok: false, error: saveRes.error || '保存模型配置失败' }
      }

      const legacyRes = await saveBuiltinAssistantConfig({
        model,
        apiBase,
        apiKey,
      })
      return legacyRes.ok ? { ok: true } : { ok: false, error: legacyRes.error || '保存配置失败' }
    }

    const handleSaveAsync = async () => {
      const trimmedTitle = title.trim()
      if (!trimmedTitle) {
        window.alert('请输入技能名称')
        return
      }
      if (isGenerateImageSkill) {
        if (!generateImageConfig.model.trim()) {
          window.alert('请输入模型名')
          return
        }
        if (!generateImageConfig.baseUrl.trim()) {
          window.alert('请输入服务 URL')
          return
        }
        const persistRes = await persistGenerateImageProfile(generateImageConfig)
        if (!persistRes.ok) {
          window.alert(`保存全局配置失败：${persistRes.error || '未知错误'}`)
          return
        }
      }
      const validSteps = isGenerateImageSkill
        ? buildGenerateImageSteps({
            ...generateImageConfig,
            model: generateImageConfig.model.trim(),
            baseUrl: generateImageConfig.baseUrl.trim(),
            apiKey: generateImageConfig.apiKey.trim(),
            prompt: generateImageConfig.prompt.trim() || DEFAULT_GENERATE_IMAGE_CONFIG.prompt,
            size: generateImageConfig.size.trim() || DEFAULT_GENERATE_IMAGE_CONFIG.size,
            outputPath: generateImageConfig.outputPath.trim() || DEFAULT_GENERATE_IMAGE_CONFIG.outputPath,
          })
        : steps.map((x) => x.trim()).filter(Boolean)
      const updated: Skill = {
        ...skill,
        title: trimmedTitle,
        originalPurpose: originalPurpose.trim() || undefined,
        steps: validSteps,
        executionPlatform: isGenerateImageSkill ? 'pc' : executionPlatform,
      }
      onSave(updated)
      onClose()
    }

    void handleSaveAsync()
  }

  const hasSchedule = skill.scheduleConfig?.isEnabled

  useEffect(() => {
    if (!isGenerateImageSkill) return
    let cancelled = false
    ;(async () => {
      setProfilesLoading(true)
      setProfilesError('')
      const opts: GenerateImageProfileOption[] = []
      const profileRes = await getBuiltinModelProfiles()
      if (profileRes.ok) {
        profileRes.nonGuiProfiles.forEach((row, idx) => {
          if (!row.model.trim()) return
          opts.push({
            id: `chat:${idx}:${row.model}`,
            label: `[chat] ${row.model}`,
            source: 'chat',
            row,
          })
        })
      } else {
        const cfg = await getBuiltinAssistantConfig()
        opts.push({
          id: `legacy:chat:${cfg.model}`,
          label: `[chat] ${cfg.model}`,
          source: 'legacy',
          row: { model: cfg.model, apiBase: cfg.apiBase, apiKey: cfg.apiKey },
        })
      }
      if (cancelled) return
      setProfileOptions(opts)
      setProfilesLoading(false)
      if (profileRes.ok) {
        const currentId = opts.find(
          (o) =>
            o.row.model === generateImageConfig.model &&
            o.row.apiBase === generateImageConfig.baseUrl &&
            o.row.apiKey === generateImageConfig.apiKey
        )?.id
        if (currentId) {
          setSelectedProfileId(currentId)
          return
        }
        if (!initProfileAppliedRef.current) {
          const preferredImageModel = profileRes.activeImageModel || profileRes.activeNonGuiModel
          const activeProfile = opts.find(
            (o) => o.source === 'chat' && o.row.model === preferredImageModel
          )
          if (activeProfile) {
            setSelectedProfileId(activeProfile.id)
            if (
              generateImageConfig.model === DEFAULT_GENERATE_IMAGE_CONFIG.model &&
              generateImageConfig.baseUrl === DEFAULT_GENERATE_IMAGE_CONFIG.baseUrl &&
              !generateImageConfig.apiKey
            ) {
              setGenerateImageConfig((s) => ({
                ...s,
                model: activeProfile.row.model,
                baseUrl: activeProfile.row.apiBase,
                apiKey: activeProfile.row.apiKey,
              }))
            }
          }
          initProfileAppliedRef.current = true
        }
      } else {
        setProfilesError('未读取到多套模型配置，已回退到当前配置。')
      }
    })().catch((e) => {
      if (cancelled) return
      setProfilesLoading(false)
      setProfilesError(`读取配置失败：${e instanceof Error ? e.message : String(e)}`)
    })
    return () => {
      cancelled = true
    }
  }, [isGenerateImageSkill])

  const handleProfileSelect = (id: string) => {
    setSelectedProfileId(id)
    if (!id) return
    const option = profileOptions.find((o) => o.id === id)
    if (!option) return
    setGenerateImageConfig((s) => ({
      ...s,
      model: option.row.model,
      baseUrl: option.row.apiBase,
      apiKey: option.row.apiKey,
    }))
  }

  return (
    <div className="skill-edit-overlay" onClick={onClose}>
      <div className="skill-edit-modal" onClick={(e) => e.stopPropagation()}>
        <div className="skill-edit-header">
          <h3 className="skill-edit-title">{createMode ? '新建技能' : '编辑技能'}</h3>
          <button className="skill-edit-close" onClick={onClose} type="button">
            ×
          </button>
        </div>

        <div className="skill-edit-body">
          <div className="skill-edit-field">
            <label>技能名称</label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="skill-edit-input"
              placeholder="输入技能名称"
            />
          </div>

          <div className="skill-edit-field">
            <label>用途说明（可选）</label>
            <textarea
              value={originalPurpose}
              onChange={(e) => setOriginalPurpose(e.target.value)}
              className="skill-edit-textarea"
              placeholder="描述技能用途"
              rows={2}
            />
          </div>

          <div className="skill-edit-field">
            <label>步骤</label>
            {isGenerateImageSkill ? (
              <div className="skill-edit-image-form">
                <div className="skill-edit-image-grid">
                  <div className="skill-edit-image-item">
                    <label>从配置选择（model/url/key）</label>
                    <select
                      className="skill-edit-input"
                      value={selectedProfileId}
                      onChange={(e) => handleProfileSelect(e.target.value)}
                      disabled={profilesLoading || profileOptions.length === 0}
                    >
                      <option value="">
                        {profilesLoading ? '读取配置中...' : '— 请选择已有配置，或手动填写 —'}
                      </option>
                      {profileOptions.map((opt) => (
                        <option key={opt.id} value={opt.id}>
                          {opt.label}
                        </option>
                      ))}
                    </select>
                    {!!profilesError && (
                      <span className="skill-edit-image-status skill-edit-image-status-warning">
                        {profilesError}
                      </span>
                    )}
                  </div>
                  <div className="skill-edit-image-item">
                    <label>模型名（MODEL）</label>
                    <input
                      type="text"
                      className="skill-edit-input"
                      value={generateImageConfig.model}
                      onChange={(e) => setGenerateImageConfig((s) => ({ ...s, model: e.target.value }))}
                      placeholder="如：gpt-image-1"
                    />
                  </div>
                  <div className="skill-edit-image-item">
                    <label>服务 URL（BASE_URL）</label>
                    <input
                      type="url"
                      className="skill-edit-input"
                      value={generateImageConfig.baseUrl}
                      onChange={(e) => setGenerateImageConfig((s) => ({ ...s, baseUrl: e.target.value }))}
                      placeholder="如：https://api.openai.com/v1"
                    />
                  </div>
                  <div className="skill-edit-image-item">
                    <label>密钥（API_KEY）</label>
                    <div className="skill-edit-api-row">
                      <input
                        type={showApiKey ? 'text' : 'password'}
                        className="skill-edit-input"
                        value={generateImageConfig.apiKey}
                        onChange={(e) => setGenerateImageConfig((s) => ({ ...s, apiKey: e.target.value }))}
                        placeholder="输入你的 API Key"
                        autoComplete="off"
                      />
                      <button
                        type="button"
                        className="skill-edit-btn skill-edit-btn-secondary skill-edit-api-toggle"
                        onClick={() => setShowApiKey((v) => !v)}
                      >
                        {showApiKey ? '隐藏' : '显示'}
                      </button>
                    </div>
                  </div>
                  <div className="skill-edit-image-item">
                    <label>提示词（PROMPT）</label>
                    <textarea
                      className="skill-edit-textarea"
                      rows={3}
                      value={generateImageConfig.prompt}
                      onChange={(e) => setGenerateImageConfig((s) => ({ ...s, prompt: e.target.value }))}
                      placeholder="输入你希望生成的图片描述"
                    />
                  </div>
                  <div className="skill-edit-image-two-cols">
                    <div className="skill-edit-image-item">
                      <label>尺寸（SIZE）</label>
                      <select
                        className="skill-edit-input"
                        value={generateImageConfig.size}
                        onChange={(e) => setGenerateImageConfig((s) => ({ ...s, size: e.target.value }))}
                      >
                        <option value="1024x1024">1024x1024</option>
                        <option value="1536x1024">1536x1024</option>
                        <option value="1024x1536">1024x1536</option>
                      </select>
                    </div>
                    <div className="skill-edit-image-item">
                      <label>输出文件名（OUTPUT_PATH）</label>
                      <input
                        type="text"
                        className="skill-edit-input"
                        value={generateImageConfig.outputPath}
                        onChange={(e) => setGenerateImageConfig((s) => ({ ...s, outputPath: e.target.value }))}
                        placeholder="如：generated_image.png"
                      />
                    </div>
                  </div>
                </div>
                <p className="skill-edit-image-hint">
                  保存后会自动生成对应 Python 脚本。执行成功后，图片会保存在 PC 本地路径。
                </p>
              </div>
            ) : (
              <div className="skill-edit-steps">
                {steps.map((step, i) => (
                  <div key={i} className="skill-edit-step-row">
                    <input
                      type="text"
                      value={step}
                      onChange={(e) => updateStep(i, e.target.value)}
                      className="skill-edit-input skill-edit-step-input"
                      placeholder={`步骤 ${i + 1}`}
                    />
                    <button
                      type="button"
                      className="skill-edit-step-remove"
                      onClick={() => removeStep(i)}
                      title="删除"
                    >
                      ×
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="skill-edit-step-add"
                  onClick={addStep}
                >
                  + 添加步骤
                </button>
              </div>
            )}
          </div>

          <div className="skill-edit-field">
            <label>执行位置</label>
            {isGenerateImageSkill ? (
              <div className="skill-edit-fixed-platform">PC（固定）</div>
            ) : (
              <div className="skill-edit-platform">
                <label className="skill-edit-platform-item">
                  <input
                    type="radio"
                    name="platform"
                    checked={executionPlatform === 'mobile'}
                    onChange={() => setExecutionPlatform('mobile')}
                  />
                  <span>手机</span>
                </label>
                <label className="skill-edit-platform-item">
                  <input
                    type="radio"
                    name="platform"
                    checked={executionPlatform === 'pc'}
                    onChange={() => setExecutionPlatform('pc')}
                  />
                  <span>PC</span>
                </label>
              </div>
            )}
          </div>

          <div className="skill-edit-field">
            <label>定时</label>
            <div className="skill-edit-schedule-row">
              {hasSchedule ? (
                <>
                  <span className="skill-edit-schedule-status">已开启</span>
                  <button
                    type="button"
                    className="skill-edit-btn skill-edit-btn-schedule"
                    onClick={() => onSchedule({ ...skill, scheduleConfig: skill.scheduleConfig })}
                  >
                    修改定时
                  </button>
                  <button
                    type="button"
                    className="skill-edit-btn skill-edit-btn-cancel-schedule"
                    onClick={() => onScheduleChange?.(undefined)}
                  >
                    取消定时
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  className="skill-edit-btn skill-edit-btn-schedule"
                  onClick={() => onSchedule(skill)}
                >
                  ⏰ 添加定时
                </button>
              )}
            </div>
          </div>
        </div>

        <div className="skill-edit-actions">
          <button
            type="button"
            className="skill-edit-btn skill-edit-btn-secondary"
            onClick={onClose}
          >
            取消
          </button>
          <button
            type="button"
            className="skill-edit-btn skill-edit-btn-primary"
            onClick={handleSave}
          >
            保存
          </button>
        </div>
      </div>
    </div>
  )
}
