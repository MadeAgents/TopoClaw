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
import { toDataURL } from 'qrcode'
import { initApi, getBindingImei } from '../services/api'
import { CustomerServiceLogModal } from './CustomerServiceLogModal'
import {
  type CustomerServiceDomainMode,
  DEFAULT_CUSTOMER_SERVICE_URL,
  getChatAssistantUrl,
  getCustomerServiceDomainMode,
  getCustomerServiceExternalUrl,
  getCustomerServiceUrl,
  getServerUrl,
  getSkillCommunityUrl,
  setCustomerServiceDomainMode,
  setCustomerServiceExternalUrl,
  setCustomerServiceUrl,
  setImei as saveImei,
  setServerUrl,
} from '../services/storage'
import { getDefaultBuiltinUrls, startBuiltinCustomerService, syncTopomobileWsUrlFromCustomerServiceUrl } from '../services/builtinAssistantConfig'
import type { FormEvent } from 'react'
import topoclawBrandImage from '../../TopoClaw3.png'
import './BindingPage.css'

function generateBindingToken(): string {
  const chars = '0123456789abcdefghijklmnopqrstuvwxyz'
  let s = ''
  const arr = new Uint8Array(12)
  crypto.getRandomValues(arr)
  for (let i = 0; i < 12; i++) s += chars[arr[i] % chars.length]
  return s
}

interface BindingPageProps {
  serverUrl: string
  onBound: () => void
}

export function BindingPage({ serverUrl, onBound }: BindingPageProps) {
  const [mode, setMode] = useState<'choose' | 'input' | 'scan'>('choose')
  const [imeiInput, setImeiInput] = useState('')
  const [error, setError] = useState('')
  const [bindingToken, setBindingToken] = useState('')
  const [qrDataUrl, setQrDataUrl] = useState('')
  const [customerServiceMode, setCustomerServiceMode] = useState<CustomerServiceDomainMode>(getCustomerServiceDomainMode())
  const [customerServiceDraft, setCustomerServiceDraft] = useState<string>(getCustomerServiceExternalUrl())
  const [customerServiceSaving, setCustomerServiceSaving] = useState(false)
  const [customerServiceStarting, setCustomerServiceStarting] = useState(false)
  const [customerServiceHint, setCustomerServiceHint] = useState('')
  const [activeCustomerServiceUrl, setActiveCustomerServiceUrl] = useState<string>(getCustomerServiceUrl())
  const [customerServiceHealth, setCustomerServiceHealth] = useState<{
    status: 'idle' | 'checking' | 'ok' | 'fail'
    text: string
  }>({ status: 'idle', text: '' })
  const [internalCustomerServiceUrl, setInternalCustomerServiceUrl] = useState('http://localhost:8002/')
  const [serviceSettingsExpanded, setServiceSettingsExpanded] = useState(false)
  const [showServiceLogModal, setShowServiceLogModal] = useState(false)
  const canvasRef = useRef<HTMLCanvasElement>(null)

  const getCustomerServiceUrlForQr = (): string => {
    const mode = getCustomerServiceDomainMode()
    if (mode === 'external') return getCustomerServiceExternalUrl()
    return getCustomerServiceUrl()
  }

  const regenerateBindingQr = () => {
    const token = generateBindingToken()
    setBindingToken(token)
    const syncParams = new URLSearchParams({
      server_url: getServerUrl(),
      customer_service_url: getCustomerServiceUrlForQr(),
      chat_assistant_url: getChatAssistantUrl(),
      skill_community_url: getSkillCommunityUrl(),
    })
    const qrContent = `cma-bind:${token}?${syncParams.toString()}`
    void toDataURL(qrContent, { width: 260, margin: 2 })
      .then(setQrDataUrl)
      .catch((err: Error) => setError('生成二维码失败: ' + (err?.message || err)))
  }

  const buildInternalCustomerServiceUrl = (host: string): string => {
    const h = (host || '').trim() || 'localhost'
    return `http://${h}:8002/`
  }

  const resolveInternalCustomerServiceUrl = async (): Promise<string> => {
    try {
      const urls = await getDefaultBuiltinUrls()
      const host = new URL(urls.topoclaw).hostname || 'localhost'
      return buildInternalCustomerServiceUrl(host)
    } catch {
      return buildInternalCustomerServiceUrl('localhost')
    }
  }

  const probeCustomerServiceAvailable = async (rawUrl: string, timeoutMs = 1800): Promise<boolean> => {
    const trimmed = (rawUrl || '').trim()
    if (!trimmed) return false
    const normalized = trimmed.endsWith('/') ? trimmed : `${trimmed}/`
    const fallbackBase = normalized.replace(/\/v\d+\/?$/i, '/')
    const candidates = [
      `${normalized}api/customer-service/health`,
      `${fallbackBase}api/customer-service/health`,
      `${normalized}health`,
      `${fallbackBase}api/health`,
    ]
    const uniqCandidates = [...new Set(candidates)]
    for (const endpoint of uniqCandidates) {
      const controller = new AbortController()
      const timer = window.setTimeout(() => controller.abort(), timeoutMs)
      try {
        const res = await fetch(endpoint, { method: 'GET', signal: controller.signal })
        if (res.ok) return true
      } catch {
        // ignore and try next candidate
      } finally {
        window.clearTimeout(timer)
      }
    }
    return false
  }

  const refreshCustomerServiceHealth = async (url: string) => {
    setCustomerServiceHealth({ status: 'checking', text: '服务检测中…' })
    const ok = await probeCustomerServiceAvailable(url)
    if (ok) {
      setCustomerServiceHealth({ status: 'ok', text: '服务已启动√' })
      return true
    }
    setCustomerServiceHealth({ status: 'fail', text: '服务不可用 ×' })
    return false
  }

  useEffect(() => {
    let cancelled = false
    void resolveInternalCustomerServiceUrl().then((url) => {
      if (!cancelled) setInternalCustomerServiceUrl(url)
    })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    void (async () => {
      const mode = getCustomerServiceDomainMode()
      const currentUrl = getCustomerServiceUrl()
      if (mode === 'internal') {
        const started = await ensureInternalCustomerServiceStarted(false)
        if (!started) {
          if (!cancelled) {
            setCustomerServiceHealth({ status: 'fail', text: '服务不可用 ×' })
          }
          return
        }
        const internalUrl = await resolveInternalCustomerServiceUrl()
        if (cancelled) return
        setInternalCustomerServiceUrl(internalUrl)
        setActiveCustomerServiceUrl(internalUrl)
        setCustomerServiceUrl(internalUrl)
        initApi(serverUrl, internalUrl)
        await refreshCustomerServiceHealth(internalUrl)
        return
      }
      if (cancelled) return
      setActiveCustomerServiceUrl(currentUrl)
      await refreshCustomerServiceHealth(currentUrl)
    })()
    return () => {
      cancelled = true
    }
  }, [serverUrl])

  const ensureInternalCustomerServiceStarted = async (restart = false): Promise<boolean> => {
    setCustomerServiceStarting(true)
    try {
      const startRes = await startBuiltinCustomerService({ restart })
      if (!startRes.ok) {
        setCustomerServiceHint(`启动内置服务失败：${startRes.error || '未知错误'}`)
        return false
      }
      const nextUrl = await resolveInternalCustomerServiceUrl()
      setInternalCustomerServiceUrl(nextUrl)
      if (restart) {
        setCustomerServiceHint('已重启本地内置服务')
      } else {
        setCustomerServiceHint(startRes.alreadyRunning ? '本地内置服务已就绪' : '已启动本地内置服务')
      }
      return true
    } finally {
      setCustomerServiceStarting(false)
    }
  }

  const saveCustomerService = async () => {
    setCustomerServiceSaving(true)
    setCustomerServiceHint('')
    try {
      let nextUrl = customerServiceDraft.trim() || DEFAULT_CUSTOMER_SERVICE_URL
      if (customerServiceMode === 'internal') {
        const ok = await ensureInternalCustomerServiceStarted(true)
        if (!ok) return
        nextUrl = await resolveInternalCustomerServiceUrl()
      }
      setCustomerServiceDomainMode(customerServiceMode)
      setCustomerServiceUrl(nextUrl)
      setActiveCustomerServiceUrl(nextUrl)
      if (customerServiceMode === 'external') {
        setCustomerServiceExternalUrl(nextUrl)
        setCustomerServiceDraft(nextUrl)
      }
      initApi(serverUrl, nextUrl)
      const syncRes = await syncTopomobileWsUrlFromCustomerServiceUrl(nextUrl)
      if (!syncRes.ok) {
        setCustomerServiceHint(`已保存，但通道同步失败：${syncRes.error || '未知错误'}`)
      } else {
        setCustomerServiceHint('已保存服务地址')
      }
      if (mode === 'scan') {
        // 扫码页已生成的二维码不会自动变更；保存后立即重生成，确保手机拿到最新配置。
        regenerateBindingQr()
      }
      await refreshCustomerServiceHealth(nextUrl)
    } finally {
      setCustomerServiceSaving(false)
    }
  }

  const handleShowServiceTerminal = () => {
    setShowServiceLogModal(true)
  }

  const renderServiceSettings = () => (
    <div className="binding-service-box">
      <div className="binding-service-current-label">当前连接的中转服务</div>
      <div className="binding-service-current-value">{activeCustomerServiceUrl || '-'}</div>
      <div
        className={[
          'binding-service-status',
          customerServiceHealth.status === 'ok'
            ? 'ok'
            : customerServiceHealth.status === 'fail'
              ? 'fail'
              : '',
        ].join(' ').trim()}
      >
        {customerServiceHealth.text || '服务状态待检测'}
      </div>
      <button
        type="button"
        className="binding-service-toggle"
        onClick={() => setServiceSettingsExpanded((v) => !v)}
      >
        服务设置 {serviceSettingsExpanded ? '▲' : '▼'}
      </button>
      {serviceSettingsExpanded && (
        <>
          <div className="binding-service-mode-row">
            <button
              type="button"
              className={`binding-service-mode-btn ${customerServiceMode === 'external' ? 'active' : ''}`}
              onClick={() => {
                setCustomerServiceMode('external')
                setCustomerServiceDraft(getCustomerServiceExternalUrl())
              }}
            >
              外部服务
            </button>
            <button
              type="button"
              className={`binding-service-mode-btn ${customerServiceMode === 'internal' ? 'active' : ''}`}
              onClick={() => {
                setCustomerServiceMode('internal')
              }}
            >
              本地内置服务
            </button>
          </div>
          <div className="binding-service-label">中转服务域名</div>
          {customerServiceMode === 'external' ? (
            <input
              type="text"
              value={customerServiceDraft}
              onChange={(e) => setCustomerServiceDraft(e.target.value)}
              placeholder={DEFAULT_CUSTOMER_SERVICE_URL}
              className="binding-service-input"
            />
          ) : (
            <div className="binding-service-inline-row">
              <div className="binding-service-value">{internalCustomerServiceUrl}</div>
              <button
                type="button"
                className="btn-secondary binding-service-terminal-btn"
                onClick={handleShowServiceTerminal}
                title="显示本地内置服务日志"
                disabled={customerServiceStarting}
              >
                {customerServiceStarting ? '启动中…' : '显示服务终端'}
              </button>
            </div>
          )}
          <div className="binding-service-actions">
            <button
              type="button"
              className="btn-primary"
              onClick={() => void saveCustomerService()}
              disabled={customerServiceSaving}
            >
              {customerServiceSaving ? '保存中…' : (customerServiceMode === 'internal' ? '保存并重启' : '保存')}
            </button>
          </div>
        </>
      )}
      {serviceSettingsExpanded && customerServiceHint && <div className="binding-service-hint">{customerServiceHint}</div>}
      {!serviceSettingsExpanded && customerServiceHint && (
        <div className="binding-service-hint">{customerServiceHint}</div>
      )}
      {showServiceLogModal && (
        <CustomerServiceLogModal onClose={() => setShowServiceLogModal(false)} />
      )}
    </div>
  )

  const handleBindImei = (imei: string) => {
    const trimmed = imei.trim()
    if (!trimmed) {
      setError('请输入 IMEI')
      return
    }
    setError('')
    saveImei(trimmed)
    setServerUrl(serverUrl)
    initApi(serverUrl)
    onBound()
  }

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    handleBindImei(imeiInput)
  }

  // 扫码绑定：PC 生成 token 二维码 cma-bind:{token}，手机扫后上报 IMEI，PC 轮询获取
  useEffect(() => {
    if (mode === 'scan') {
      // 扫码绑定时携带 PC 当前服务配置，手机端绑定成功后可自动对齐，无需手动再配一遍。
      regenerateBindingQr()
      initApi(serverUrl)
    }
  }, [mode, serverUrl])

  // 轮询 binding 接口直到获取 IMEI
  useEffect(() => {
    if (mode !== 'scan' || !bindingToken) return
    const POLL_INTERVAL = 1500
    const TIMEOUT = 5 * 60 * 1000
    const start = Date.now()
    let cancelled = false

    const poll = async () => {
      if (cancelled || Date.now() - start > TIMEOUT) return
      const imei = await getBindingImei(bindingToken)
      if (cancelled) return
      if (imei) {
        saveImei(imei)
        setServerUrl(serverUrl)
        initApi(serverUrl)
        onBound()
        return
      }
      setTimeout(poll, POLL_INTERVAL)
    }
    poll()
    return () => { cancelled = true }
  }, [mode, bindingToken, serverUrl, onBound])

  let cardContent: JSX.Element
  if (mode === 'choose') {
    cardContent = (
      <>
        <h1>绑定手机</h1>
        <p className="binding-desc">
          请使用与手机端相同的 IMEI 绑定，以打通聊天记录。
          <br />
          <strong>扫码绑定</strong>：PC 生成二维码，手机端通过「扫一扫」扫描绑定。
          <br />
          <strong>输入 IMEI</strong>：在手机端「我的」→「我的二维码」查看 IMEI 后手动输入。
        </p>
        <div className="binding-actions">
          <button className="btn-primary" onClick={() => setMode('input')}>
            输入 IMEI
          </button>
          <button className="btn-secondary" onClick={() => setMode('scan')}>
            扫码绑定
          </button>
        </div>
      </>
    )
  } else if (mode === 'scan') {
    cardContent = (
      <>
        <h1>扫码绑定</h1>
        <p className="binding-desc">
          请打开手机端「扫一扫」，扫描下方二维码完成绑定
        </p>
        <div className="qr-display">
          {qrDataUrl ? (
            <img src={qrDataUrl} alt="绑定二维码" className="qr-image" />
          ) : (
            <canvas ref={canvasRef} className="qr-placeholder" />
          )}
        </div>
        <p className="imei-hint">请使用手机端「扫一扫」扫描二维码，绑定将自动完成</p>
        {error && <p className="error">{error}</p>}
        <div className="form-actions">
          <button className="btn-secondary" onClick={() => setMode('choose')}>
            返回
          </button>
        </div>
      </>
    )
  } else {
    cardContent = (
      <>
        <h1>输入 IMEI</h1>
        <p className="binding-desc">请输入手机端显示的 IMEI（在「我的」→「我的二维码」中查看）</p>
        <form onSubmit={handleSubmit}>
          <input
            type="text"
            value={imeiInput}
            onChange={(e) => setImeiInput(e.target.value)}
            placeholder="例如: 480b0b29b2c3ff90"
            className="imei-input"
            autoFocus
          />
          {error && <p className="error">{error}</p>}
          <div className="form-actions">
            <button type="submit" className="btn-primary">绑定</button>
            <button type="button" className="btn-secondary" onClick={() => setMode('choose')}>
              返回
            </button>
          </div>
        </form>
      </>
    )
  }

  return (
    <div className="binding-page">
      <section className="binding-pane binding-pane-left">
        <div className="binding-brand-block">
          <h2 className="binding-brand-title">
            <span className="binding-brand-title-line">TopoClaw</span>
            <span className="binding-brand-title-line">你的全场景 AI 数字助手</span>
          </h2>
          <img className="binding-brand-image" src={topoclawBrandImage} alt="TopoClaw 展示图" />
        </div>
      </section>
      <section className="binding-pane binding-pane-right">
        <div className="binding-card">
          {cardContent}
          {renderServiceSettings()}
        </div>
      </section>
    </div>
  )
}
