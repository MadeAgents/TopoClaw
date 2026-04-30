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

import { useState, useCallback, useRef, useEffect } from 'react'
import QRCode from 'qrcode'
import {
  buildAssistantUrl,
  buildCustomAssistantId,
  addCustomAssistant,
  getCustomAssistants,
  CAP_CHAT,
  CAP_EXECUTION_MOBILE,
  CAP_EXECUTION_PC,
  CAP_GROUP_MANAGER,
} from '../services/customAssistants'
import { fetchInstalledSkillsFromService, syncCustomAssistantsToCloud } from '../services/api'
import {
  getBuiltinAssistantConfig,
  saveBuiltinAssistantConfig,
  getDefaultBuiltinUrl,
  getBuiltinModelProfiles,
  readLocalConfigTxt,
  saveLocalConfigTxt,
  saveBuiltinModelProfiles,
  type BuiltinModelProfileRow,
} from '../services/builtinAssistantConfig'
import { createAgentViaWebSocket } from '../services/chatWebSocket'
import { loadAllMySkills } from '../services/skillStorage'
import { getSkillDisplayName, toCanonicalSkillName } from '../services/skillNames'
import { getImei } from '../services/storage'
import { BuiltinAssistantEnvProfilesFields } from './BuiltinAssistantEnvProfilesFields'
import './NewAssistantModal.css'

interface NewAssistantModalProps {
  onClose: () => void
  onSaved?: () => void
}

export function NewAssistantModal({ onClose, onSaved }: NewAssistantModalProps) {
  const [name, setName] = useState('')
  const [intro, setIntro] = useState('')
  const [useDefaultDomain, setUseDefaultDomain] = useState(true)
  const [baseUrl, setBaseUrl] = useState('')
  const [nonGuiProfiles, setNonGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [guiProfiles, setGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [activeNonGuiModel, setActiveNonGuiModel] = useState('')
  const [activeGuiModel, setActiveGuiModel] = useState('')
  const [activeGroupManagerModel, setActiveGroupManagerModel] = useState('')
  const [avatarDataUrl, setAvatarDataUrl] = useState<string | null>(null)
  const [capMobile, setCapMobile] = useState(true)
  const [capPc, setCapPc] = useState(true)
  const [capChat, setCapChat] = useState(true)
  const [capGroupManager, setCapGroupManager] = useState(false)
  const [multiSessionEnabled, setMultiSessionEnabled] = useState(true)
  const [modelConfigExpanded, setModelConfigExpanded] = useState(false)
  const [systemPrompt, setSystemPrompt] = useState('')
  const [skillPool, setSkillPool] = useState<Array<{ id: string; name: string; label: string }>>([])
  const [skillsIncludeSelected, setSkillsIncludeSelected] = useState<string[]>([])
  const [error, setError] = useState('')
  const [link, setLink] = useState<string | null>(null)
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null)
  const [defaultUrl, setDefaultUrl] = useState<string>('http://localhost:18790/')

  const avatarRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    getDefaultBuiltinUrl().then(setDefaultUrl)
  }, [])

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

  useEffect(() => {
    if (!useDefaultDomain) return
    let cancelled = false
    ;(async () => {
      const [localRes, runtimeRes] = await Promise.all([readLocalConfigTxt(), getBuiltinModelProfiles()])
      if (cancelled) return
      if (localRes.ok) {
        const localNg = localRes.nonGuiProfiles.map((x) => ({ ...x }))
        const localG = localRes.guiProfiles.map((x) => ({ ...x }))
        setNonGuiProfiles(localNg)
        setGuiProfiles(localG)
        const runtimeNg = runtimeRes.ok ? runtimeRes.activeNonGuiModel : ''
        const runtimeG = runtimeRes.ok ? runtimeRes.activeGuiModel : ''
        const runtimeGm = runtimeRes.ok ? runtimeRes.activeGroupManagerModel : ''
        setActiveNonGuiModel(localNg.some((p) => p.model === runtimeNg) ? runtimeNg : (localNg[0]?.model ?? ''))
        setActiveGuiModel(localG.some((p) => p.model === runtimeG) ? runtimeG : (localG[0]?.model ?? ''))
        setActiveGroupManagerModel(localNg.some((p) => p.model === runtimeGm) ? runtimeGm : (localNg[0]?.model ?? ''))
        return
      }
      if (runtimeRes.ok) {
        setNonGuiProfiles(runtimeRes.nonGuiProfiles.map((x) => ({ ...x })))
        setGuiProfiles(runtimeRes.guiProfiles.map((x) => ({ ...x })))
        setActiveNonGuiModel(runtimeRes.activeNonGuiModel)
        setActiveGuiModel(runtimeRes.activeGuiModel)
        setActiveGroupManagerModel(runtimeRes.activeGroupManagerModel)
        return
      }
      const c = await getBuiltinAssistantConfig()
      if (cancelled) return
      setNonGuiProfiles([{ model: c.model, apiBase: c.apiBase, apiKey: c.apiKey }])
      setGuiProfiles([{ model: c.guiModel, apiBase: c.guiApiBase, apiKey: c.guiApiKey }])
      setActiveNonGuiModel(c.model)
      setActiveGuiModel(c.guiModel)
      setActiveGroupManagerModel(c.model)
    })()
    return () => {
      cancelled = true
    }
  }, [useDefaultDomain])

  const handleAvatarChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !file.type.startsWith('image/')) return
    const reader = new FileReader()
    reader.onload = () => setAvatarDataUrl(reader.result as string)
    reader.readAsDataURL(file)
  }

  const handleSave = async () => {
    const trimmedName = name.trim()
    const effectiveUrl = useDefaultDomain ? defaultUrl : baseUrl.trim()
    const normalizedUrl = effectiveUrl.endsWith('/') ? effectiveUrl : `${effectiveUrl}/`

    if (!trimmedName) {
      setError('请输入小助手名称')
      return
    }
    if (!useDefaultDomain && !baseUrl.trim()) {
      setError('请输入后台服务域名')
      return
    }
    if (!capMobile && !capPc && !capChat && !capGroupManager) {
      setError('请至少选择一项功能')
      return
    }
    if (capGroupManager && !capChat) {
      setError('群组管理者需同时勾选「其他（聊天）」')
      return
    }

    if (useDefaultDomain) {
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
        return
      }
      if (new Set(ngModels).size !== ngModels.length) {
        setError('chat 配置中模型名不能重复')
        return
      }
      if (new Set(gModels).size !== gModels.length) {
        setError('GUI 配置中模型名不能重复')
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
        return
      }
    }

    const capabilities: string[] = []
    if (capMobile) capabilities.push(CAP_EXECUTION_MOBILE)
    if (capPc) capabilities.push(CAP_EXECUTION_PC)
    if (capChat) capabilities.push(CAP_CHAT)
    if (capGroupManager) capabilities.push(CAP_GROUP_MANAGER)

    const id = buildCustomAssistantId(getImei() || undefined)
    const promptText = systemPrompt.trim()
    const skillsInclude = skillsIncludeSelected.map((s) => toCanonicalSkillName(s)).filter(Boolean)

    if (useDefaultDomain) {
      if (skillsInclude.length === 0) {
        setError('内置自定义小助手至少选择一个技能白名单')
        return
      }
      const createRes = await createAgentViaWebSocket(
        normalizedUrl,
        {
          agent_id: id,
          system_prompt: promptText || undefined,
          skills_include: skillsInclude.length ? skillsInclude : undefined,
        },
        AbortSignal.timeout(120000)
      )
      if (!createRes.ok) {
        setError(createRes.error || '创建内置助手失败')
        return
      }
    }

    const avatarBase64 = avatarDataUrl?.includes('base64,')
      ? avatarDataUrl.split('base64,')[1]
      : undefined
    const assistant = {
      id,
      name: trimmedName,
      intro: intro.trim() || undefined,
      baseUrl: normalizedUrl,
      capabilities,
      avatar: avatarBase64,
      systemPrompt: promptText || undefined,
      skillsInclude: skillsInclude.length ? skillsInclude : undefined,
      multiSessionEnabled: multiSessionEnabled ? true : undefined,
      assistantOrigin: 'created' as const,
    }
    addCustomAssistant(assistant)

    const imei = getImei()
    if (imei) {
      const list = getCustomAssistants()
      const synced = await syncCustomAssistantsToCloud(imei, list)
      if (!synced) {
        setError('小助手已创建，但同步到云端失败，请稍后在助手管理中重试同步')
      }
    }

    onSaved?.()

    const url = buildAssistantUrl(trimmedName, normalizedUrl, capabilities, {
      assistantId: id,
      multiSessionEnabled: multiSessionEnabled || undefined,
    })
    setLink(url)
    setError('')

    QRCode.toDataURL(url, { width: 200, margin: 1 }).then(setQrDataUrl).catch(() => setQrDataUrl(null))
  }

  const handleCopyLink = useCallback(() => {
    if (!link) return
    navigator.clipboard.writeText(link)
  }, [link])

  const toggleSkill = (name: string) => {
    setSkillsIncludeSelected((prev) => (prev.includes(name) ? prev.filter((x) => x !== name) : [...prev, name]))
  }

  return (
    <div className="new-assistant-overlay" onClick={onClose}>
      <div className="new-assistant-modal" onClick={(e) => e.stopPropagation()}>
        <div className="new-assistant-header">
          <span className="new-assistant-title">新建小助手</span>
          <button className="new-assistant-close" onClick={onClose} aria-label="关闭">×</button>
        </div>
        <div className="new-assistant-body">
          <p className="new-assistant-hint">填写小助手信息，生成链接和二维码供用户添加</p>
          <div className="new-assistant-field">
            <label>小助手名称</label>
            <input
              type="text"
              placeholder="如：天气助手"
              value={name}
              onChange={(e) => { setName(e.target.value); setError('') }}
              className="new-assistant-input"
            />
          </div>
          <div className="new-assistant-field">
            <label>小助手头像</label>
            <div className="new-assistant-avatar-row">
              <div
                className={`new-assistant-avatar ${avatarDataUrl ? 'has-image' : ''}`}
                onClick={() => avatarRef.current?.click()}
              >
                {avatarDataUrl ? (
                  <img src={avatarDataUrl} alt="头像" />
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
                  checked={useDefaultDomain}
                  onChange={() => { setUseDefaultDomain(true); setError('') }}
                />
                <span>使用默认域名</span>
              </label>
              <label className="new-assistant-check">
                <input
                  type="radio"
                  name="urlMode"
                  checked={!useDefaultDomain}
                  onChange={() => { setUseDefaultDomain(false); setError('') }}
                />
                <span>使用自定义域名</span>
              </label>
            </div>
            {useDefaultDomain ? (
              <>
                <p className="new-assistant-hint" style={{ marginTop: 0 }}>
                  默认域名：{defaultUrl.replace(/\/$/, '')}（本机 IP，手机可连；内置在 exe 内的服务）
                </p>
                <button
                  type="button"
                  className="new-assistant-collapse-btn"
                  onClick={() => setModelConfigExpanded((v) => !v)}
                >
                  {modelConfigExpanded ? '收起模型配置' : '展开模型配置'}
                </button>
                {modelConfigExpanded && (
                  <BuiltinAssistantEnvProfilesFields
                    nonGuiProfiles={nonGuiProfiles}
                    guiProfiles={guiProfiles}
                    setNonGuiProfiles={setNonGuiProfiles}
                    setGuiProfiles={setGuiProfiles}
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
          <div className="new-assistant-field">
            <label>小助手功能</label>
            <div className="new-assistant-checkboxes">
              <label className="new-assistant-check">
                <input type="checkbox" checked={capMobile} onChange={(e) => setCapMobile(e.target.checked)} />
                <span>手机端执行</span>
              </label>
              <label className="new-assistant-check">
                <input type="checkbox" checked={capPc} onChange={(e) => setCapPc(e.target.checked)} />
                <span>电脑端执行</span>
              </label>
              <label className="new-assistant-check">
                <input type="checkbox" checked={capChat} onChange={(e) => setCapChat(e.target.checked)} />
                <span>其他（聊天）</span>
              </label>
              <label className="new-assistant-check">
                <input
                  type="checkbox"
                  checked={capGroupManager}
                  onChange={(e) => {
                    const v = e.target.checked
                    setCapGroupManager(v)
                    if (v) setCapChat(true)
                  }}
                />
                <span>群组管理者</span>
              </label>
            </div>
            <p className="new-assistant-hint" style={{ marginTop: 4, marginBottom: 0 }}>
              群组管理者：群内未 @ 任何小助手时，消息统一由此助手回复（需同时勾选「其他（聊天）」）
            </p>
          </div>
          {useDefaultDomain && (
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
                      <label key={`inc-${s.id}`} className="new-assistant-skill-check">
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
          <div className="new-assistant-field">
            <label className="new-assistant-check">
              <input type="checkbox" checked={multiSessionEnabled} onChange={(e) => setMultiSessionEnabled(e.target.checked)} />
              <span>是否支持多 session</span>
            </label>
            <p className="new-assistant-hint" style={{ marginTop: 4, marginBottom: 0 }}>开启后，该小助手可创建多个独立会话</p>
          </div>
          {error && <p className="new-assistant-error">{error}</p>}
          {link && (
            <div className="new-assistant-result">
              <div className="new-assistant-result-header">
                {avatarDataUrl && (
                  <img src={avatarDataUrl} alt="头像" className="new-assistant-result-avatar" />
                )}
                <span className="new-assistant-result-name">{name}</span>
              </div>
              <p className="new-assistant-result-hint">将下方链接或二维码分享给用户，用户可通过「添加小助手」扫码或粘贴添加</p>
              <div className="new-assistant-link-wrap">
                <code className="new-assistant-link">{link}</code>
              </div>
              <div className="new-assistant-result-actions">
                <button type="button" className="new-assistant-copy" onClick={handleCopyLink}>
                  复制链接
                </button>
                {qrDataUrl && (
                  <div className="new-assistant-qr-wrap">
                    <img src={qrDataUrl} alt="二维码" className="new-assistant-qr" />
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
        <div className="new-assistant-actions">
          <button className="new-assistant-cancel" onClick={onClose}>取消</button>
          <button className="new-assistant-save" onClick={handleSave}>保存</button>
        </div>
      </div>
    </div>
  )
}
