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

import { useState, useRef, useEffect } from 'react'
import {
  updateCustomAssistant,
  getCustomAssistants,
  DEFAULT_BUILTIN_ASSISTANT_URL,
  DEFAULT_BUILTIN_GROUP_MANAGER_URL,
  DEFAULT_GROUP_MANAGER_ASSISTANT_ID,
  isDefaultBuiltinUrl,
  isProtectedBuiltinAssistantId,
  type CustomAssistant,
} from '../services/customAssistants'
import { fetchInstalledSkillsFromService, syncCustomAssistantsToCloud } from '../services/api'
import { loadAllMySkills } from '../services/skillStorage'
import { getSkillDisplayName, toCanonicalSkillName } from '../services/skillNames'
import { upsertAgentViaWebSocket } from '../services/chatWebSocket'
import {
  getBuiltinAssistantConfig,
  saveBuiltinAssistantConfig,
  getDefaultBuiltinUrl,
  getBuiltinModelProfiles,
  saveBuiltinModelProfiles,
  readLocalConfigTxt,
  saveLocalConfigTxt,
  type BuiltinModelProfileRow,
} from '../services/builtinAssistantConfig'
import { BuiltinAssistantEnvProfilesFields } from './BuiltinAssistantEnvProfilesFields'
import { getImei } from '../services/storage'
import { toAvatarSrcLikeContacts } from '../utils/avatar'
import './NewAssistantModal.css'

interface EditAssistantModalProps {
  assistant: CustomAssistant
  onClose: () => void
  onSaved?: () => void
}

const normUrl = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''
const sameStringArray = (a: string[], b: string[]) =>
  a.length === b.length && a.every((v, i) => v === b[i])

export function EditAssistantModal({ assistant, onClose, onSaved }: EditAssistantModalProps) {
  const [name, setName] = useState(assistant.name)
  const [intro, setIntro] = useState(assistant.intro ?? '')
  const [defaultUrl, setDefaultUrl] = useState<string>('http://localhost:18790/')
  const [useDefaultUrl, setUseDefaultUrl] = useState(true)
  const [baseUrl, setBaseUrl] = useState('')
  const [nonGuiProfiles, setNonGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [guiProfiles, setGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [activeNonGuiModel, setActiveNonGuiModel] = useState('')
  const [activeGuiModel, setActiveGuiModel] = useState('')
  const [activeGroupManagerModel, setActiveGroupManagerModel] = useState('')
  const [systemPrompt, setSystemPrompt] = useState(assistant.systemPrompt ?? '')
  const [skillPool, setSkillPool] = useState<Array<{ id: string; name: string; label: string }>>([])
  const [skillsIncludeSelected, setSkillsIncludeSelected] = useState<string[]>(
    (assistant.skillsInclude ?? []).map((x) => toCanonicalSkillName(x)).filter(Boolean)
  )
  const [avatarDataUrl, setAvatarDataUrl] = useState<string | null>(
    assistant.avatar ? `data:image/png;base64,${assistant.avatar}` : null
  )
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [localNonGuiPresets, setLocalNonGuiPresets] = useState<BuiltinModelProfileRow[]>([])
  const [localGuiPresets, setLocalGuiPresets] = useState<BuiltinModelProfileRow[]>([])
  const [loadingLocalConfig, setLoadingLocalConfig] = useState(false)

  const avatarRef = useRef<HTMLInputElement>(null)
  const isGroupManager = assistant.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID
  const isProtectedBuiltin = isProtectedBuiltinAssistantId(assistant.id)
  const canEditRuntimeAgent = useDefaultUrl && !isProtectedBuiltin && isDefaultBuiltinUrl(defaultUrl)

  useEffect(() => {
    const slot = assistant.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID ? 'groupmanager' : 'topoclaw'
    getDefaultBuiltinUrl(slot).then((url) => {
      setDefaultUrl(url)
      const localhostAlt = slot === 'groupmanager' ? DEFAULT_BUILTIN_GROUP_MANAGER_URL : DEFAULT_BUILTIN_ASSISTANT_URL
      const isDefault =
        normUrl(assistant.baseUrl) === normUrl(url) || normUrl(assistant.baseUrl) === normUrl(localhostAlt)
      setUseDefaultUrl(isDefault)
      setBaseUrl(isDefault ? '' : assistant.baseUrl.replace(/\/$/, ''))
    })
  }, [assistant.id, assistant.baseUrl])

  useEffect(() => {
    if (!useDefaultUrl) return
    let cancelled = false
    ;(async () => {
      const [localRes, runtimeRes] = await Promise.all([readLocalConfigTxt(), getBuiltinModelProfiles()])
      if (cancelled) return
      if (localRes.ok) {
        const localNg = localRes.nonGuiProfiles.map((x) => ({ ...x }))
        const localG = localRes.guiProfiles.map((x) => ({ ...x }))
        setNonGuiProfiles(localNg)
        setGuiProfiles(localG)
        setLocalNonGuiPresets(localNg)
        setLocalGuiPresets(localG)
        const runtimeNg = runtimeRes.ok ? runtimeRes.activeNonGuiModel : ''
        const runtimeG = runtimeRes.ok ? runtimeRes.activeGuiModel : ''
        const runtimeGm = runtimeRes.ok ? runtimeRes.activeGroupManagerModel : ''
        setActiveNonGuiModel(localNg.some((p) => p.model === runtimeNg) ? runtimeNg : (localNg[0]?.model ?? ''))
        setActiveGuiModel(localG.some((p) => p.model === runtimeG) ? runtimeG : (localG[0]?.model ?? ''))
        setActiveGroupManagerModel(localNg.some((p) => p.model === runtimeGm) ? runtimeGm : (localNg[0]?.model ?? ''))
        return
      }
      if (runtimeRes.ok) {
        const runtimeNg = runtimeRes.nonGuiProfiles.map((x) => ({ ...x }))
        const runtimeG = runtimeRes.guiProfiles.map((x) => ({ ...x }))
        setNonGuiProfiles(runtimeNg)
        setGuiProfiles(runtimeG)
        setLocalNonGuiPresets(runtimeNg)
        setLocalGuiPresets(runtimeG)
        setActiveNonGuiModel(runtimeRes.activeNonGuiModel)
        setActiveGuiModel(runtimeRes.activeGuiModel)
        setActiveGroupManagerModel(runtimeRes.activeGroupManagerModel)
        return
      }
      const c = await getBuiltinAssistantConfig()
      if (cancelled) return
      const fallbackNg = [{ model: c.model, apiBase: c.apiBase, apiKey: c.apiKey }]
      const fallbackG = [{ model: c.guiModel, apiBase: c.guiApiBase, apiKey: c.guiApiKey }]
      setNonGuiProfiles(fallbackNg)
      setGuiProfiles(fallbackG)
      setLocalNonGuiPresets(fallbackNg)
      setLocalGuiPresets(fallbackG)
      setActiveNonGuiModel(c.model)
      setActiveGuiModel(c.guiModel)
      setActiveGroupManagerModel(c.model)
    })()
    return () => {
      cancelled = true
    }
  }, [useDefaultUrl])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const installed = await fetchInstalledSkillsFromService()
      if (cancelled) return
      const local = loadAllMySkills()
      const localPool = local
        .map((s) => {
          const canonical = toCanonicalSkillName(s.title)
          return { id: s.id, name: canonical, label: getSkillDisplayName(canonical) }
        })
        .filter((s) => s.name)
      const installedPool = installed
        .map((s) => {
          const canonical = toCanonicalSkillName(s.name)
          return { id: `installed_${canonical}`, name: canonical, label: getSkillDisplayName(canonical) }
        })
        .filter((s) => s.name)
      const merged: Array<{ id: string; name: string; label: string }> = []
      const seen = new Set<string>()
      for (const item of [...localPool, ...installedPool]) {
        const key = item.name.trim().toLowerCase()
        if (!key || seen.has(key)) continue
        seen.add(key)
        merged.push(item)
      }
      merged.sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN'))
      setSkillPool(merged)
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const handleLoadLocalConfig = async () => {
    setError('')
    setLoadingLocalConfig(true)
    const r = await readLocalConfigTxt()
    setLoadingLocalConfig(false)
    if (!r.ok) {
      setError(r.error)
      return
    }
    if (isGroupManager) {
      if (r.nonGuiProfiles.length === 0) {
        setError('本地配置中无 non_gui 条目，GroupManager 仅使用 chat 模型列表')
        return
      }
      setNonGuiProfiles(r.nonGuiProfiles.map((x) => ({ ...x })))
      setLocalNonGuiPresets(r.nonGuiProfiles.map((x) => ({ ...x })))
      setLocalGuiPresets([])
      setActiveGroupManagerModel((prev) =>
        r.nonGuiProfiles.some((p) => p.model === prev) ? prev : r.nonGuiProfiles[0]!.model
      )
      return
    }
    if (r.nonGuiProfiles.length > 0) {
      setNonGuiProfiles(r.nonGuiProfiles.map((x) => ({ ...x })))
      setActiveNonGuiModel((prev) =>
        r.nonGuiProfiles.some((p) => p.model === prev) ? prev : r.nonGuiProfiles[0]!.model
      )
    }
    if (r.guiProfiles.length > 0) {
      setGuiProfiles(r.guiProfiles.map((x) => ({ ...x })))
      setActiveGuiModel((prev) =>
        r.guiProfiles.some((p) => p.model === prev) ? prev : r.guiProfiles[0]!.model
      )
    }
    setLocalNonGuiPresets(r.nonGuiProfiles.map((x) => ({ ...x })))
    setLocalGuiPresets(r.guiProfiles.map((x) => ({ ...x })))
  }

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !file.type.startsWith('image/')) return
    const reader = new FileReader()
    reader.onload = () => setAvatarDataUrl(reader.result as string)
    reader.readAsDataURL(file)
  }

  const handleSave = async () => {
    const trimmedName = name.trim()
    const effectiveUrl = useDefaultUrl ? defaultUrl : baseUrl.trim()
    const normalizedUrl = effectiveUrl.endsWith('/') ? effectiveUrl : `${effectiveUrl}/`

    if (!trimmedName) {
      setError('请输入小助手名称')
      return
    }
    if (!useDefaultUrl && !baseUrl.trim()) {
      setError('请输入后台服务域名')
      return
    }

    const avatarBase64 = avatarDataUrl?.includes('base64,')
      ? avatarDataUrl.split('base64,')[1]
      : (assistant.avatar ?? undefined)
    const normalizedIntro = intro.trim() || undefined
    const normalizedSystemPrompt = systemPrompt.trim() || undefined
    const normalizedSkillsInclude = skillsIncludeSelected
      .map((x) => toCanonicalSkillName(x))
      .filter(Boolean)
    const currentSkillsInclude = (assistant.skillsInclude ?? [])
      .map((x) => toCanonicalSkillName(x))
      .filter(Boolean)
    const hasAssistantProfileChanges =
      trimmedName !== assistant.name ||
      (normalizedIntro ?? '') !== (assistant.intro?.trim() ?? '') ||
      normUrl(normalizedUrl) !== normUrl(assistant.baseUrl) ||
      (avatarBase64 ?? '') !== (assistant.avatar ?? '') ||
      (normalizedSystemPrompt ?? '') !== (assistant.systemPrompt?.trim() ?? '') ||
      !sameStringArray(normalizedSkillsInclude, currentSkillsInclude)

    setSaving(true)
    setError('')

    try {
      if (useDefaultUrl) {
        const ngTrim = nonGuiProfiles.map((p) => ({
          model: p.model.trim(),
          apiBase: p.apiBase.trim(),
          apiKey: p.apiKey,
        }))
        const gTrim = guiProfiles.map((p) => ({
          model: p.model.trim(),
          apiBase: p.apiBase.trim(),
          apiKey: p.apiKey,
        }))
        const ngModels = ngTrim.map((p) => p.model).filter(Boolean)
        const gModels = gTrim.map((p) => p.model).filter(Boolean)
        if (ngModels.length === 0 || gModels.length === 0) {
          setError('chat 与 GUI 至少各保留一条有效配置（模型名必填）')
          setSaving(false)
          return
        }
        if (new Set(ngModels).size !== ngModels.length) {
          setError('chat 配置中模型名不能重复')
          setSaving(false)
          return
        }
        if (new Set(gModels).size !== gModels.length) {
          setError('GUI 配置中模型名不能重复')
          setSaving(false)
          return
        }
        let aNg = activeNonGuiModel.trim()
        let aG = activeGuiModel.trim()
        let aGm = activeGroupManagerModel.trim()
        if (!ngTrim.some((p) => p.model === aNg)) aNg = ngTrim[0]!.model
        if (!gTrim.some((p) => p.model === aG)) aG = gTrim[0]!.model
        if (!ngTrim.some((p) => p.model === aGm)) aGm = ngTrim[0]!.model
        const localRes = await saveLocalConfigTxt({ nonGuiProfiles: ngTrim, guiProfiles: gTrim })
        if (!localRes.ok) {
          setError(localRes.error || '保存本地配置失败')
          setSaving(false)
          return
        }
        const res = await saveBuiltinModelProfiles({
          nonGuiProfiles: ngTrim,
          guiProfiles: gTrim,
          activeNonGuiModel: aNg,
          activeGuiModel: aG,
          activeGroupManagerModel: aGm,
        })
        if (!res.ok) {
          setError(res.error || '保存内置多套配置失败')
          setSaving(false)
          return
        }
        const ng = ngTrim.find((p) => p.model === aNg) ?? ngTrim[0]!
        const g = gTrim.find((p) => p.model === aG) ?? gTrim[0]!
        const ls = await saveBuiltinAssistantConfig(
          {
            model: ng.model,
            apiBase: ng.apiBase,
            apiKey: ng.apiKey,
            guiModel: g.model,
            guiApiBase: g.apiBase,
            guiApiKey: g.apiKey,
          },
          { skipIpc: true }
        )
        if (!ls.ok) {
          setError(ls.error || '本地缓存失败')
          setSaving(false)
          return
        }
      }

      if (canEditRuntimeAgent) {
        if (skillsIncludeSelected.length === 0) {
          setError('内置自定义小助手至少选择一个技能白名单')
          setSaving(false)
          return
        }
        const upsertRes = await upsertAgentViaWebSocket(
          normalizedUrl,
          {
            agent_id: assistant.id,
            system_prompt: systemPrompt.trim() || undefined,
            skills_include: skillsIncludeSelected.length ? skillsIncludeSelected : undefined,
          },
          AbortSignal.timeout(120000)
        )
        if (!upsertRes.ok) {
          setError(upsertRes.error || '更新助手 prompt/技能 失败')
          setSaving(false)
          return
        }
      }

      if (hasAssistantProfileChanges) {
        updateCustomAssistant(assistant.id, {
          name: trimmedName,
          intro: normalizedIntro,
          baseUrl: normalizedUrl,
          avatar: avatarBase64,
          systemPrompt: normalizedSystemPrompt,
          skillsInclude: normalizedSkillsInclude,
          skillsExclude: undefined,
        })
      }

      const imei = getImei()
      if (hasAssistantProfileChanges && imei) {
        const list = getCustomAssistants()
        const syncOk = await syncCustomAssistantsToCloud(imei, list)
        if (!syncOk) {
          setError('本地保存成功，但助手资料云侧同步失败，请检查网络后重试')
          return
        }
      }

      onSaved?.()
      onClose()
    } catch (e) {
      setError('保存失败，请重试')
    } finally {
      setSaving(false)
    }
  }

  const displayAvatar = avatarDataUrl ?? (assistant.avatar ? toAvatarSrcLikeContacts(assistant.avatar) : null)
  const toggleSkill = (name: string) => {
    setSkillsIncludeSelected((prev) => (prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name]))
  }

  return (
    <div className="new-assistant-overlay" onClick={onClose}>
      <div className="new-assistant-modal" onClick={(e) => e.stopPropagation()}>
        <div className="new-assistant-header">
          <span className="new-assistant-title">编辑小助手</span>
          <button className="new-assistant-close" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="new-assistant-body">
          <p className="new-assistant-hint">修改小助手的名称、头像、介绍和域名</p>
          <div className="new-assistant-field">
            <label>小助手名称</label>
            <input
              type="text"
              placeholder="如：天气助手"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
                setError('')
              }}
              className="new-assistant-input"
            />
          </div>
          <div className="new-assistant-field">
            <label>小助手头像</label>
            <div className="new-assistant-avatar-row">
              <div
                className={`new-assistant-avatar ${displayAvatar ? 'has-image' : ''}`}
                onClick={() => avatarRef.current?.click()}
              >
                {displayAvatar ? (
                  <img src={typeof displayAvatar === 'string' ? displayAvatar : undefined} alt="头像" />
                ) : (
                  <span>点击上传</span>
                )}
              </div>
              <input
                ref={avatarRef}
                type="file"
                accept="image/*"
                onChange={handleAvatarChange}
                className="new-assistant-avatar-input"
              />
            </div>
          </div>
          <div className="new-assistant-field">
            <label>小助手介绍（选填）</label>
            <textarea
              placeholder="简要描述小助手功能"
              value={intro}
              onChange={(e) => setIntro(e.target.value)}
              className="new-assistant-input new-assistant-textarea"
              rows={2}
            />
          </div>
          <div className="new-assistant-field">
            <label>服务地址</label>
            <div className="new-assistant-checkboxes" style={{ marginBottom: 8 }}>
              <label className="new-assistant-check">
                <input
                  type="radio"
                  name="urlMode"
                  checked={useDefaultUrl}
                  onChange={() => { setUseDefaultUrl(true); setError('') }}
                />
                <span>使用默认域名</span>
              </label>
              <label className="new-assistant-check">
                <input
                  type="radio"
                  name="urlMode"
                  checked={!useDefaultUrl}
                  onChange={() => { setUseDefaultUrl(false); setError('') }}
                />
                <span>使用自定义域名</span>
              </label>
            </div>
            {useDefaultUrl ? (
              <>
                <p className="new-assistant-hint" style={{ marginTop: 0 }}>
                  默认域名：{defaultUrl.replace(/\/$/, '')}（本机 IP，手机可连；内置在 exe 内的服务）
                </p>
                <p className="new-assistant-hint" style={{ marginTop: 0 }}>
                  默认域名下的模型配置为全局共享：在任意自有助手中修改后，会同步到全局并影响所有默认域名助手。
                </p>
                {useDefaultUrl && (
                  <button
                    type="button"
                    className="new-assistant-local-config-btn"
                    onClick={() => void handleLoadLocalConfig()}
                    disabled={loadingLocalConfig || saving}
                  >
                    {loadingLocalConfig ? '读取中…' : '获取本地配置'}
                  </button>
                )}
                {useDefaultUrl && (
                  <BuiltinAssistantEnvProfilesFields
                    nonGuiProfiles={nonGuiProfiles}
                    guiProfiles={guiProfiles}
                    setNonGuiProfiles={setNonGuiProfiles}
                    setGuiProfiles={setGuiProfiles}
                    showGuiSection={!isGroupManager}
                    localNonGuiPresets={localNonGuiPresets}
                    localGuiPresets={localGuiPresets}
                  />
                )}
              </>
            ) : (
              <input
                type="url"
                placeholder="https://agent.example.com/"
                value={baseUrl}
                onChange={(e) => { setBaseUrl(e.target.value); setError('') }}
                className="new-assistant-input"
              />
            )}
          </div>
          {canEditRuntimeAgent && (
            <>
              <div className="new-assistant-field">
                <label>系统 Prompt（选填）</label>
                <textarea
                  placeholder="例如：你是一个专注于数据分析的助手..."
                  value={systemPrompt}
                  onChange={(e) => setSystemPrompt(e.target.value)}
                  className="new-assistant-input new-assistant-textarea"
                  rows={4}
                />
              </div>
              <div className="new-assistant-field">
                <label>技能白名单（仅允许所选技能）</label>
                <div className="new-assistant-skill-pool">
                  {skillPool.length === 0 ? (
                    <p className="new-assistant-hint" style={{ margin: 0 }}>暂无可选技能，请先在「我的技能」中准备技能。</p>
                  ) : (
                    skillPool.map((s) => (
                      <label key={`inc-edit-${s.id}`} className="new-assistant-skill-check">
                        <input
                          type="checkbox"
                          checked={skillsIncludeSelected.includes(s.name)}
                          onChange={() => toggleSkill(s.name)}
                        />
                        <span>{s.label}</span>
                      </label>
                    ))
                  )}
                </div>
              </div>
            </>
          )}
          {error && <p className="new-assistant-error">{error}</p>}
        </div>
        <div className="new-assistant-actions">
          <button className="new-assistant-cancel" onClick={onClose} disabled={saving}>
            取消
          </button>
          <button className="new-assistant-save" onClick={handleSave} disabled={saving}>
            {saving ? '保存中...' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}
