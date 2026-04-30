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

import { useState, useEffect, useRef } from 'react'
import html2canvas from 'html2canvas'
import { toDataURL } from 'qrcode'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  type CustomerServiceDomainMode,
  DEFAULT_CUSTOMER_SERVICE_URL,
  DEFAULT_SKILL_COMMUNITY_URL,
  getAutoExecuteCode,
  getCustomerServiceDomainMode,
  getCustomerServiceExternalUrl,
  getCustomerServiceUrl,
  getImei,
  getServerUrl,
  getSkillCommunityUrl,
  setAutoExecuteCode,
  setCustomerServiceDomainMode,
  setCustomerServiceExternalUrl,
  setCustomerServiceUrl,
  setSkillCommunityUrl,
} from '../services/storage'
import { adaptAssistantIdsForUser, getCustomAssistantsFromCloud, getUserSettings, initApi, updateUserSettings } from '../services/api'
import { clearAllChatMessages } from '../services/messageStorage'
import { probeDesktopUpdate, type DesktopUpdatePayload } from '../services/desktopVersionCheck'
import {
  getCustomAssistants,
  isDefaultBuiltinUrl,
  isProtectedBuiltinAssistantId,
  mergeCloudAssistantsAndEnsureDefaultTopo,
} from '../services/customAssistants'
import { upsertAgentViaWebSocket } from '../services/chatWebSocket'
import {
  type BuiltinTokenUsageStatsResult,
  type BuiltinModelProfileRow,
  getBuiltinAssistantConfig,
  getBuiltinServicesEnabled,
  getBuiltinAssistantLogBuffer,
  getBuiltinTokenUsageStats,
  getDefaultBuiltinUrls,
  getBuiltinModelProfiles,
  readLocalConfigTxt,
  getWeixinLoginQr,
  pollWeixinLoginStatus,
  saveLocalConfigTxt,
  saveBuiltinModelProfiles,
  setBuiltinServicesEnabled,
  saveBuiltinAssistantConfig,
  restartBuiltinAssistant,
  startBuiltinCustomerService,
  syncTopomobileWsUrlFromCustomerServiceUrl,
} from '../services/builtinAssistantConfig'
import { NewAssistantModal } from './NewAssistantModal'
import { DeveloperView } from './DeveloperView'
import { DesktopUpdateDialog } from './DesktopUpdateDialog'
import './SettingsView.css'

interface SettingsViewProps {
  onLogout?: () => void
  onClearChatHistory?: () => void
  onNewAssistantSaved?: () => void
}

export function SettingsView({ onLogout, onClearChatHistory, onNewAssistantSaved }: SettingsViewProps) {
  const WEIXIN_DEFAULT_BASE_URL = 'https://ilinkai.weixin.qq.com'
  const [showNewAssistant, setShowNewAssistant] = useState(false)
  const [showDeveloper, setShowDeveloper] = useState(false)
  const [autoExecuteCode, setAutoExecuteCodeState] = useState(getAutoExecuteCode())
  const [appVersion, setAppVersion] = useState<string>('—')
  const [desktopUpdate, setDesktopUpdate] = useState<DesktopUpdatePayload | null>(null)
  const [versionCheckHint, setVersionCheckHint] = useState<string | null>(null)
  const [versionCheckBusy, setVersionCheckBusy] = useState(false)
  const [showQqModal, setShowQqModal] = useState(false)
  const [qqAppId, setQqAppId] = useState('')
  const [qqAppSecret, setQqAppSecret] = useState('')
  const [qqAllowFrom, setQqAllowFrom] = useState('*')
  const [qqSaving, setQqSaving] = useState(false)
  const [qqTesting, setQqTesting] = useState(false)
  const [qqError, setQqError] = useState('')
  const [qqSuccess, setQqSuccess] = useState('')
  const [showWeixinModal, setShowWeixinModal] = useState(false)
  const [weixinBotToken, setWeixinBotToken] = useState('')
  const [weixinBaseUrl, setWeixinBaseUrl] = useState('https://ilinkai.weixin.qq.com')
  const [weixinAllowFrom, setWeixinAllowFrom] = useState('*')
  const [weixinSaving, setWeixinSaving] = useState(false)
  const [weixinTesting, setWeixinTesting] = useState(false)
  const [weixinError, setWeixinError] = useState('')
  const [weixinSuccess, setWeixinSuccess] = useState('')
  const [weixinQrDataUrl, setWeixinQrDataUrl] = useState('')
  const [weixinQrHint, setWeixinQrHint] = useState('')
  const [weixinQrLoading, setWeixinQrLoading] = useState(false)
  const [builtinDefaultUrls, setBuiltinDefaultUrls] = useState<{ topoclaw: string; groupmanager: string } | null>(null)
  const [builtinDefaultIp, setBuiltinDefaultIp] = useState<string>('—')
  const [builtinUrlLoading, setBuiltinUrlLoading] = useState(false)
  const [builtinUrlHint, setBuiltinUrlHint] = useState<string | null>(null)
  const [customerServiceSyncHint, setCustomerServiceSyncHint] = useState<string | null>(null)
  const [customerServiceDomainMode, setCustomerServiceDomainModeState] = useState<CustomerServiceDomainMode>(getCustomerServiceDomainMode())
  const [customerServiceUrlDraft, setCustomerServiceUrlDraft] = useState<string>(getCustomerServiceExternalUrl())
  const [customerServiceSaving, setCustomerServiceSaving] = useState(false)
  const [customerServiceStarting, setCustomerServiceStarting] = useState(false)
  const [customerServiceDomainSwitched, setCustomerServiceDomainSwitched] = useState(false)
  const [showOpenSourceNotice, setShowOpenSourceNotice] = useState(false)
  const [openSourceNoticeText, setOpenSourceNoticeText] = useState('')
  const [openSourceNoticeLoading, setOpenSourceNoticeLoading] = useState(false)
  const [openSourceNoticeError, setOpenSourceNoticeError] = useState<string | null>(null)
  const [adaptAssistantIdsBusy, setAdaptAssistantIdsBusy] = useState(false)
  const [digitalCloneEnabled, setDigitalCloneEnabled] = useState(false)
  const [digitalCloneLoading, setDigitalCloneLoading] = useState(false)
  const [builtinServicesEnabled, setBuiltinServicesEnabledState] = useState(true)
  const [builtinServicesSaving, setBuiltinServicesSaving] = useState(false)
  const [builtinServicesHint, setBuiltinServicesHint] = useState('')
  const [globalModelExpanded, setGlobalModelExpanded] = useState(false)
  const [globalModelLoading, setGlobalModelLoading] = useState(false)
  const [globalModelSaving, setGlobalModelSaving] = useState(false)
  const [globalModelHint, setGlobalModelHint] = useState('')
  const [globalModelError, setGlobalModelError] = useState('')
  const [globalNonGuiProfiles, setGlobalNonGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [globalGuiProfiles, setGlobalGuiProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [globalActiveNonGuiModel, setGlobalActiveNonGuiModel] = useState('')
  const [globalActiveGuiModel, setGlobalActiveGuiModel] = useState('')
  const [tokenUsageStats, setTokenUsageStats] = useState<BuiltinTokenUsageStatsResult>({
    ok: false,
    error: '暂无数据',
    days: 30,
    total: { input_tokens: 0, output_tokens: 0, total_tokens: 0, events: 0, estimated_events: 0 },
    by_model: [],
    by_day: [],
  })
  const [tokenUsageDays, setTokenUsageDays] = useState(30)
  const [tokenUsageLoading, setTokenUsageLoading] = useState(false)
  const [tokenUsageHint, setTokenUsageHint] = useState('')
  const [tokenUsageExporting, setTokenUsageExporting] = useState<'model' | 'trend' | ''>('')
  const tokenModelChartRef = useRef<HTMLDivElement | null>(null)
  const tokenTrendChartRef = useRef<HTMLDivElement | null>(null)
  const imei = getImei()
  const skillCommunityUrl = getSkillCommunityUrl()
  const weixinPollLoopIdRef = useRef(0)
  const weixinModalOpenRef = useRef(false)

  const buildInternalCustomerServiceUrl = (host: string): string => {
    const h = (host || '').trim() || 'localhost'
    return `http://${h}:8002/`
  }

  const resolveInternalCustomerServiceUrl = async (): Promise<string> => {
    const ip = (builtinDefaultIp || '').trim()
    if (ip && ip !== '—') return buildInternalCustomerServiceUrl(ip)
    try {
      const urls = await getDefaultBuiltinUrls()
      const host = new URL(urls.topoclaw).hostname || 'localhost'
      return buildInternalCustomerServiceUrl(host)
    } catch {
      return buildInternalCustomerServiceUrl('localhost')
    }
  }

  useEffect(() => {
    void (async () => {
      try {
        const v = await window.electronAPI?.getAppVersion?.()
        if (typeof v === 'string' && v.trim()) setAppVersion(v.trim())
        else setAppVersion('0.0.0')
      } catch {
        setAppVersion('0.0.0')
      }
    })()
  }, [])

  const refreshBuiltinDefaultUrls = () => {
    setBuiltinUrlLoading(true)
    setBuiltinUrlHint(null)
    void getDefaultBuiltinUrls()
      .then((urls) => {
        setBuiltinDefaultUrls(urls)
        try {
          const host = new URL(urls.topoclaw).hostname || '—'
          setBuiltinDefaultIp(host)
        } catch {
          setBuiltinDefaultIp('—')
        }
      })
      .catch(() => {
        setBuiltinUrlHint('读取失败，请重试')
        setBuiltinDefaultIp('—')
      })
      .finally(() => setBuiltinUrlLoading(false))
  }

  const formatTokenNumber = (value: number): string => {
    if (!Number.isFinite(value)) return '0'
    return new Intl.NumberFormat('zh-CN').format(Math.max(0, Math.floor(value)))
  }

  const formatTokenTooltipValue = (value: unknown): string => {
    const n = typeof value === 'number' ? value : Number(value ?? 0)
    return formatTokenNumber(Number.isFinite(n) ? n : 0)
  }

  const loadTokenUsageStats = async (days: number) => {
    const baseUrl = builtinDefaultUrls?.topoclaw
    if (!baseUrl) {
      setTokenUsageHint('等待内置 TopoClaw 地址就绪后再加载统计。')
      return
    }
    setTokenUsageLoading(true)
    try {
      const result = await getBuiltinTokenUsageStats({ days, baseUrl })
      setTokenUsageStats(result)
      if (result.ok) {
        const estimateSuffix = result.total.estimated_events > 0 ? `，其中 ${result.total.estimated_events} 次为估算值` : ''
        setTokenUsageHint(`统计范围：最近 ${result.days} 天，累计 ${result.total.events} 次模型调用${estimateSuffix}。`)
      } else {
        setTokenUsageHint(`读取失败：${result.error}`)
      }
    } finally {
      setTokenUsageLoading(false)
    }
  }

  const exportTokenChartImage = async (
    target: HTMLDivElement | null,
    mode: 'model' | 'trend',
    defaultFileName: string
  ) => {
    if (!target) {
      window.alert('图表尚未就绪，请稍后重试。')
      return
    }
    setTokenUsageExporting(mode)
    try {
      const canvas = await html2canvas(target, {
        backgroundColor: '#ffffff',
        useCORS: true,
        scale: Math.max(2, window.devicePixelRatio || 1),
      })
      const dataUrl = canvas.toDataURL('image/png')
      const api = window.electronAPI
      if (api?.saveImageAs) {
        const saveRes = await api.saveImageAs(dataUrl, defaultFileName)
        if (!saveRes.ok && !saveRes.canceled) {
          window.alert(saveRes.error || '导出图片失败')
        }
        return
      }
      const a = document.createElement('a')
      a.href = dataUrl
      a.download = defaultFileName
      a.rel = 'noopener'
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (e) {
      window.alert(`导出图片失败：${String(e)}`)
    } finally {
      setTokenUsageExporting('')
    }
  }

  const tokenModelChartData = tokenUsageStats.by_model.slice(0, 8).map((row) => ({
    name: row.model.length > 22 ? `${row.model.slice(0, 22)}…` : row.model,
    input: row.input_tokens,
    output: row.output_tokens,
  }))

  const tokenDayChartData = [...tokenUsageStats.by_day]
    .reverse()
    .map((row) => ({
      date: row.date.slice(5),
      input: row.input_tokens,
      output: row.output_tokens,
    }))

  useEffect(() => {
    refreshBuiltinDefaultUrls()
  }, [])

  useEffect(() => {
    void loadTokenUsageStats(tokenUsageDays)
  }, [builtinDefaultUrls?.topoclaw, tokenUsageDays])

  useEffect(() => {
    let cancelled = false
    const loadDigitalCloneSetting = async () => {
      if (!imei) {
        setDigitalCloneEnabled(false)
        return
      }
      setDigitalCloneLoading(true)
      try {
        const res = await getUserSettings(imei)
        if (cancelled || !res.success) return
        setDigitalCloneEnabled(res.settings?.digital_clone_enabled === true)
      } finally {
        if (!cancelled) setDigitalCloneLoading(false)
      }
    }
    void loadDigitalCloneSetting()
    return () => {
      cancelled = true
    }
  }, [imei])

  useEffect(() => {
    let cancelled = false
    void getBuiltinServicesEnabled()
      .then((enabled) => {
        if (!cancelled) setBuiltinServicesEnabledState(enabled)
      })
      .catch(() => {
        if (!cancelled) setBuiltinServicesEnabledState(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    weixinModalOpenRef.current = showWeixinModal
    if (!showWeixinModal) {
      weixinPollLoopIdRef.current += 1
    }
  }, [showWeixinModal])

  useEffect(() => {
    if (!globalModelExpanded) return
    if (globalNonGuiProfiles.length > 0 && globalGuiProfiles.length > 0) return
    void loadGlobalModelProfiles()
  }, [globalModelExpanded, globalNonGuiProfiles.length, globalGuiProfiles.length])

  const reinitApiClients = () => {
    initApi(getServerUrl(), getCustomerServiceUrl())
  }

  const handleAutoExecuteChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const v = e.target.checked
    setAutoExecuteCodeState(v)
    setAutoExecuteCode(v)
  }

  const handleDigitalCloneChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const v = e.target.checked
    if (!imei) {
      setDigitalCloneEnabled(false)
      return
    }
    setDigitalCloneEnabled(v)
    setDigitalCloneLoading(true)
    try {
      const res = await updateUserSettings(imei, { digital_clone_enabled: v })
      if (res.success) {
        setDigitalCloneEnabled(res.settings?.digital_clone_enabled === true)
      }
    } finally {
      setDigitalCloneLoading(false)
    }
  }

  const handleBuiltinServicesToggle = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const nextEnabled = e.target.checked
    const prevEnabled = builtinServicesEnabled
    setBuiltinServicesEnabledState(nextEnabled)
    setBuiltinServicesSaving(true)
    setBuiltinServicesHint('')
    try {
      const res = await setBuiltinServicesEnabled(nextEnabled)
      if (!res.ok) {
        setBuiltinServicesEnabledState(prevEnabled)
        setBuiltinServicesHint(res.error || '保存失败，请重试')
        return
      }
      setBuiltinServicesEnabledState(res.enabled !== false)
      setBuiltinServicesHint(
        res.enabled
          ? '已开启：可启动 TopoClaw / GroupManager 与内置终端。'
          : '已关闭：所有内置服务和内置终端已停止并禁用。'
      )
    } finally {
      setBuiltinServicesSaving(false)
    }
  }

  const handleClearChatHistory = () => {
    if (!window.confirm('确定要清空所有聊天记录吗？此操作仅清除 PC 端本地数据，无法撤销。')) return
    clearAllChatMessages()
    onClearChatHistory?.()
  }

  const saveCustomerServiceUrl = async (nextUrl: string, mode: CustomerServiceDomainMode) => {
    setCustomerServiceSaving(true)
    setCustomerServiceSyncHint(null)
    setCustomerServiceUrl(nextUrl)
    if (mode === 'external') {
      setCustomerServiceExternalUrl(nextUrl)
      setCustomerServiceUrlDraft(nextUrl)
    }
    reinitApiClients()
    const syncRes = await syncTopomobileWsUrlFromCustomerServiceUrl(nextUrl)
    if (!syncRes.ok) {
      setCustomerServiceSyncHint(`已保存中转服务地址，但 TopoMobile 通道同步失败：${syncRes.error || '未知错误'}`)
    } else {
      setCustomerServiceSyncHint('已保存中转服务地址')
    }
    setCustomerServiceSaving(false)
  }

  const ensureCustomerServiceForInternalMode = async (restart = false): Promise<boolean> => {
    if (customerServiceDomainMode !== 'internal') return true
    setCustomerServiceStarting(true)
    try {
      const startRes = await startBuiltinCustomerService({ restart })
      if (!startRes.ok) {
        setCustomerServiceSyncHint(`启动内置 customer_service 失败：${startRes.error || '未知错误'}`)
        return false
      }
      return true
    } finally {
      setCustomerServiceStarting(false)
    }
  }

  const handleCustomerServiceSave = async () => {
    if (customerServiceDomainMode === 'internal') {
      const started = await ensureCustomerServiceForInternalMode(false)
      if (!started) return
    }
    const nextUrl = customerServiceDomainMode === 'internal'
      ? await resolveInternalCustomerServiceUrl()
      : (customerServiceUrlDraft.trim() || DEFAULT_CUSTOMER_SERVICE_URL)
    await saveCustomerServiceUrl(nextUrl, customerServiceDomainMode)
    if (customerServiceDomainSwitched) {
      onLogout?.()
    }
  }

  const handleCustomerServiceDomainModeChange = (mode: CustomerServiceDomainMode) => {
    if (mode === customerServiceDomainMode) return
    setCustomerServiceDomainSwitched(true)
    setCustomerServiceDomainModeState(mode)
    setCustomerServiceDomainMode(mode)
    setCustomerServiceSyncHint(null)
    if (mode === 'external') {
      setCustomerServiceUrlDraft(getCustomerServiceExternalUrl())
    }
  }

  const handleCustomerServiceInternalRestart = async () => {
    const ok = await ensureCustomerServiceForInternalMode(true)
    if (!ok) return
    setCustomerServiceSyncHint('内置 customer_service 已启动/重启')
  }

  const handleSkillCommunityChange = (e: React.FocusEvent<HTMLInputElement>) => {
    const v = e.target.value.trim()
    setSkillCommunityUrl(v || DEFAULT_SKILL_COMMUNITY_URL)
  }

  const patchGlobalNonGuiProfile = (idx: number, patch: Partial<BuiltinModelProfileRow>) => {
    setGlobalNonGuiProfiles((rows) => rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }

  const patchGlobalGuiProfile = (idx: number, patch: Partial<BuiltinModelProfileRow>) => {
    setGlobalGuiProfiles((rows) => rows.map((r, i) => (i === idx ? { ...r, ...patch } : r)))
  }

  const loadGlobalModelProfiles = async () => {
    setGlobalModelLoading(true)
    setGlobalModelError('')
    setGlobalModelHint('')
    try {
      const [localRes, runtimeRes] = await Promise.all([readLocalConfigTxt(), getBuiltinModelProfiles()])
      if (localRes.ok) {
        const localNg = localRes.nonGuiProfiles.map((x) => ({ ...x }))
        const localG = localRes.guiProfiles.map((x) => ({ ...x }))
        setGlobalNonGuiProfiles(localNg)
        setGlobalGuiProfiles(localG)
        const runtimeNg = runtimeRes.ok ? runtimeRes.activeNonGuiModel : ''
        const runtimeG = runtimeRes.ok ? runtimeRes.activeGuiModel : ''
        setGlobalActiveNonGuiModel(localNg.some((p) => p.model === runtimeNg) ? runtimeNg : localNg[0]?.model ?? '')
        setGlobalActiveGuiModel(localG.some((p) => p.model === runtimeG) ? runtimeG : localG[0]?.model ?? '')
        return
      }
      if (!runtimeRes.ok) {
        setGlobalModelError('读取本地配置失败，且读取全局模型配置失败')
        return
      }
      setGlobalNonGuiProfiles(runtimeRes.nonGuiProfiles.map((x) => ({ ...x })))
      setGlobalGuiProfiles(runtimeRes.guiProfiles.map((x) => ({ ...x })))
      setGlobalActiveNonGuiModel(runtimeRes.activeNonGuiModel)
      setGlobalActiveGuiModel(runtimeRes.activeGuiModel)
    } finally {
      setGlobalModelLoading(false)
    }
  }

  const handleSaveGlobalModelProfiles = async () => {
    setGlobalModelSaving(true)
    setGlobalModelError('')
    setGlobalModelHint('')
    try {
      const ngTrim = globalNonGuiProfiles.map((p) => ({
        model: p.model.trim(),
        apiBase: p.apiBase.trim(),
        apiKey: p.apiKey,
      }))
      const gTrim = globalGuiProfiles.map((p) => ({
        model: p.model.trim(),
        apiBase: p.apiBase.trim(),
        apiKey: p.apiKey,
      }))
      const ngModels = ngTrim.map((p) => p.model).filter(Boolean)
      const gModels = gTrim.map((p) => p.model).filter(Boolean)
      if (ngModels.length === 0 || gModels.length === 0) {
        setGlobalModelError('chat 与 GUI 至少各保留一条有效配置（模型名必填）')
        return
      }
      if (new Set(ngModels).size !== ngModels.length) {
        setGlobalModelError('chat 配置中模型名不能重复')
        return
      }
      if (new Set(gModels).size !== gModels.length) {
        setGlobalModelError('GUI 配置中模型名不能重复')
        return
      }
      let aNg = globalActiveNonGuiModel.trim()
      let aG = globalActiveGuiModel.trim()
      let aGm = aNg
      if (!ngTrim.some((p) => p.model === aNg)) aNg = ngTrim[0]!.model
      if (!gTrim.some((p) => p.model === aG)) aG = gTrim[0]!.model
      aGm = aNg

      const localRes = await saveLocalConfigTxt({ nonGuiProfiles: ngTrim, guiProfiles: gTrim })
      if (!localRes.ok) {
        setGlobalModelError(localRes.error || '保存本地配置失败')
        return
      }

      const saveRes = await saveBuiltinModelProfiles({
        nonGuiProfiles: ngTrim,
        guiProfiles: gTrim,
        activeNonGuiModel: aNg,
        activeGuiModel: aG,
        activeGroupManagerModel: aGm,
      })
      if (!saveRes.ok) {
        setGlobalModelError(saveRes.error || '保存全局模型配置失败')
        return
      }

      const ng = ngTrim.find((p) => p.model === aNg) ?? ngTrim[0]!
      const g = gTrim.find((p) => p.model === aG) ?? gTrim[0]!
      const cacheRes = await saveBuiltinAssistantConfig(
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
      if (!cacheRes.ok) {
        setGlobalModelError(cacheRes.error || '本地缓存全局模型配置失败')
        return
      }

      setGlobalActiveNonGuiModel(aNg)
      setGlobalActiveGuiModel(aG)

      const dependentAssistants = getCustomAssistants().filter(
        (a) => !isProtectedBuiltinAssistantId(a.id) && isDefaultBuiltinUrl(a.baseUrl)
      )
      let syncOk = 0
      let syncFail = 0
      if (dependentAssistants.length > 0) {
        const results = await Promise.allSettled(
          dependentAssistants.map((a) =>
            upsertAgentViaWebSocket(
              a.baseUrl,
              {
                agent_id: a.id,
                system_prompt: a.systemPrompt?.trim() || undefined,
                skills_include: (a.skillsInclude ?? []).map((s) => String(s).trim()).filter(Boolean),
              },
              AbortSignal.timeout(120000)
            )
          )
        )
        for (const r of results) {
          if (r.status === 'fulfilled' && r.value.ok) syncOk += 1
          else syncFail += 1
        }
      }

      setGlobalModelHint(
        syncFail > 0
          ? `全局模型已保存；依赖助手同步 ${syncOk} 个成功，${syncFail} 个失败（可稍后重试保存）。`
          : `全局模型已保存，并同步到 ${syncOk} 个依赖默认域名的自定义助手。`
      )
    } finally {
      setGlobalModelSaving(false)
    }
  }

  const handleLogout = () => onLogout?.()

  const handleAdaptAssistantIds = async () => {
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    setAdaptAssistantIdsBusy(true)
    try {
      const res = await adaptAssistantIdsForUser(imei)
      if (!res.success) {
        window.alert(`适配失败：${res.message || '未知错误'}`)
        return
      }
      // 适配完成后立刻从云侧回拉并覆盖本地，避免页面继续展示旧 id/displayId。
      try {
        const cloudAssistants = await getCustomAssistantsFromCloud(imei, Date.now(), { timeoutMs: 12000, throwOnError: true })
        await mergeCloudAssistantsAndEnsureDefaultTopo(cloudAssistants, imei)
        onNewAssistantSaved?.()
      } catch {
        // 云侧刷新失败不影响适配主流程提示
      }
      const a = res.assistant_stats
      const g = res.group_stats
      window.alert(
        `适配完成\n助手：${a?.assistants_updated ?? 0}/${a?.assistants_total ?? 0}\n群组：${g?.groups_updated ?? 0}/${g?.groups_total ?? 0}\n群配置更新：${g?.configs_updated ?? 0}`
      )
    } finally {
      setAdaptAssistantIdsBusy(false)
    }
  }

  const openQqConfigModal = async () => {
    setQqError('')
    setQqSuccess('')
    setShowQqModal(true)
    try {
      const cfg = await getBuiltinAssistantConfig()
      setQqAppId(cfg.qqAppId || '')
      setQqAppSecret(cfg.qqAppSecret || '')
      setQqAllowFrom((cfg.qqAllowFrom || '*').trim() || '*')
    } catch {
      setQqAppId('')
      setQqAppSecret('')
      setQqAllowFrom('*')
    }
  }

  const handleSaveQqConfig = async () => {
    const appId = qqAppId.trim()
    const appSecret = qqAppSecret.trim()
    const allowFrom = qqAllowFrom.trim() || '*'
    if (!appId) {
      setQqError('请填写 QQ AppID')
      return
    }
    if (!appSecret) {
      setQqError('请填写 QQ AppSecret')
      return
    }

    setQqSaving(true)
    setQqError('')
    setQqSuccess('')
    try {
      const saveRes = await saveBuiltinAssistantConfig({
        qqEnabled: true,
        qqAppId: appId,
        qqAppSecret: appSecret,
        qqAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setQqError(saveRes.error || '保存配置失败')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setQqError(`配置已保存，但重启内置服务失败：${restartRes.error || '未知错误'}`)
        return
      }
      setQqSuccess('QQ 通道已启用，内置服务重启成功。现在可在 QQ 私聊或群里 @机器人 使用。')
    } finally {
      setQqSaving(false)
    }
  }

  const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms))

  const handleTestQqConfig = async () => {
    const appId = qqAppId.trim()
    const appSecret = qqAppSecret.trim()
    const allowFrom = qqAllowFrom.trim() || '*'
    if (!appId) {
      setQqError('请填写 QQ AppID')
      return
    }
    if (!appSecret) {
      setQqError('请填写 QQ AppSecret')
      return
    }

    setQqTesting(true)
    setQqError('')
    setQqSuccess('正在测试连接，请稍候…')
    try {
      const before = await getBuiltinAssistantLogBuffer('topoclaw')
      const saveRes = await saveBuiltinAssistantConfig({
        qqEnabled: true,
        qqAppId: appId,
        qqAppSecret: appSecret,
        qqAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setQqError(saveRes.error || '保存测试配置失败')
        setQqSuccess('')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setQqError(`测试失败：内置服务重启失败（${restartRes.error || '未知错误'}）`)
        setQqSuccess('')
        return
      }

      const beforeLen = before.length
      let delta = ''
      for (let i = 0; i < 8; i += 1) {
        await sleep(1000)
        const after = await getBuiltinAssistantLogBuffer('topoclaw')
        delta = after.slice(Math.min(beforeLen, after.length))
        const text = delta.toLowerCase()
        if (text.includes('qq bot ready') || text.includes('qq channel enabled')) {
          setQqSuccess('测试成功：QQ 通道连接正常。你现在可以在 QQ 里直接发消息进行验证。')
          setQqError('')
          return
        }
        if (
          text.includes('qq sdk not installed') ||
          text.includes('qq app_id and secret not configured') ||
          text.includes('qq bot error') ||
          text.includes('failed to start channel qq')
        ) {
          setQqError('测试失败：请检查 AppID / AppSecret 是否正确，并确认网络可访问 QQ 平台。')
          setQqSuccess('')
          return
        }
      }

      setQqError('测试超时：未在日志中检测到明确成功信号，请打开日志进一步排查。')
      setQqSuccess('')
    } finally {
      setQqTesting(false)
    }
  }

  const openWeixinConfigModal = async () => {
    setWeixinError('')
    setWeixinSuccess('')
    setWeixinQrHint('')
    setWeixinQrDataUrl('')
    setShowWeixinModal(true)
    try {
      const cfg = await getBuiltinAssistantConfig()
      setWeixinBotToken(cfg.weixinBotToken || '')
      const nextBaseUrl = (cfg.weixinBaseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
      setWeixinBaseUrl(nextBaseUrl)
      setWeixinAllowFrom((cfg.weixinAllowFrom || '*').trim() || '*')
      void startWeixinQrLogin(nextBaseUrl)
    } catch {
      setWeixinBotToken('')
      setWeixinBaseUrl(WEIXIN_DEFAULT_BASE_URL)
      setWeixinAllowFrom('*')
      void startWeixinQrLogin(WEIXIN_DEFAULT_BASE_URL)
    }
  }

  const startWeixinQrLogin = async (baseUrlRaw?: string) => {
    const baseUrl = (baseUrlRaw || weixinBaseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
    setWeixinQrLoading(true)
    setWeixinQrHint('正在获取微信登录二维码…')
    setWeixinError('')
    const loopId = Date.now()
    weixinPollLoopIdRef.current = loopId
    try {
      const qrRes = await getWeixinLoginQr({ baseUrl })
      if (!qrRes.ok) {
        setWeixinError(qrRes.error || '获取二维码失败')
        setWeixinQrHint('')
        return
      }
      const qrData = await toDataURL(qrRes.payload, { width: 260, margin: 1 })
      setWeixinQrDataUrl(qrData)
      setWeixinQrHint('请使用微信扫一扫，扫描二维码完成绑定')
      void pollWeixinQrLoop(loopId, qrRes.baseUrl || baseUrl, qrRes.qrcodeTicket)
    } catch (e) {
      setWeixinError(`获取二维码失败：${String(e)}`)
      setWeixinQrHint('')
    } finally {
      setWeixinQrLoading(false)
    }
  }

  const pollWeixinQrLoop = async (loopId: number, baseUrl: string, qrcodeTicket: string) => {
    while (weixinModalOpenRef.current && weixinPollLoopIdRef.current === loopId) {
      const statusRes = await pollWeixinLoginStatus({ baseUrl, qrcodeTicket })
      if (!statusRes.ok) {
        setWeixinQrHint(statusRes.error || '查询扫码状态失败')
        await sleep(1500)
        continue
      }
      const status = (statusRes.status || '').toLowerCase()
      if (status === 'scaned') {
        setWeixinQrHint('已扫码，请在微信中确认授权…')
      } else if (status === 'expired') {
        setWeixinQrHint('二维码已过期，正在刷新…')
        if (weixinPollLoopIdRef.current === loopId) {
          void startWeixinQrLogin(baseUrl)
        }
        return
      } else if (status === 'confirmed') {
        const botToken = (statusRes.botToken || '').trim()
        const finalBaseUrl = (statusRes.baseUrl || baseUrl || WEIXIN_DEFAULT_BASE_URL).trim() || WEIXIN_DEFAULT_BASE_URL
        if (!botToken) {
          setWeixinError('扫码已确认，但未获取到 botToken，请重试')
          return
        }
        setWeixinQrHint('扫码成功，正在保存配置并重启服务…')
        const saveRes = await saveBuiltinAssistantConfig({
          weixinEnabled: true,
          weixinBotToken: botToken,
          weixinBaseUrl: finalBaseUrl,
          weixinAllowFrom: weixinAllowFrom.trim() || '*',
        })
        if (!saveRes.ok) {
          setWeixinError(saveRes.error || '保存微信配置失败')
          return
        }
        setWeixinBotToken(botToken)
        setWeixinBaseUrl(finalBaseUrl)
        const restartRes = await restartBuiltinAssistant('topoclaw')
        if (!restartRes.ok) {
          setWeixinError(`扫码成功，但重启内置服务失败：${restartRes.error || '未知错误'}`)
          return
        }
        setWeixinSuccess('扫码绑定成功，微信通道已启用。')
        setWeixinError('')
        setWeixinQrHint('已绑定成功')
        return
      }
      await sleep(1200)
    }
  }

  const handleSaveWeixinConfig = async () => {
    const botToken = weixinBotToken.trim()
    const baseUrl = weixinBaseUrl.trim() || WEIXIN_DEFAULT_BASE_URL
    const allowFrom = weixinAllowFrom.trim() || '*'

    setWeixinSaving(true)
    setWeixinError('')
    setWeixinSuccess('')
    try {
      const saveRes = await saveBuiltinAssistantConfig({
        weixinEnabled: true,
        weixinBotToken: botToken,
        weixinBaseUrl: baseUrl,
        weixinAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setWeixinError(saveRes.error || '保存配置失败')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setWeixinError(`配置已保存，但重启内置服务失败：${restartRes.error || '未知错误'}`)
        return
      }
      if (!botToken) {
        setWeixinSuccess('微信配置已保存。因未填写 botToken，服务重启后会进入扫码绑定流程，请在日志中查看二维码并用微信确认。')
        return
      }
      setWeixinSuccess('微信通道已启用，内置服务重启成功。你现在可以在微信侧发消息验证。')
    } finally {
      setWeixinSaving(false)
    }
  }

  const handleTestWeixinConfig = async () => {
    const botToken = weixinBotToken.trim()
    const baseUrl = weixinBaseUrl.trim() || WEIXIN_DEFAULT_BASE_URL
    const allowFrom = weixinAllowFrom.trim() || '*'

    setWeixinTesting(true)
    setWeixinError('')
    setWeixinSuccess('正在测试连接，请稍候…')
    try {
      const before = await getBuiltinAssistantLogBuffer('topoclaw')
      const saveRes = await saveBuiltinAssistantConfig({
        weixinEnabled: true,
        weixinBotToken: botToken,
        weixinBaseUrl: baseUrl,
        weixinAllowFrom: allowFrom,
      })
      if (!saveRes.ok) {
        setWeixinError(saveRes.error || '保存测试配置失败')
        setWeixinSuccess('')
        return
      }
      const restartRes = await restartBuiltinAssistant('topoclaw')
      if (!restartRes.ok) {
        setWeixinError(`测试失败：内置服务重启失败（${restartRes.error || '未知错误'}）`)
        setWeixinSuccess('')
        return
      }

      const beforeLen = before.length
      let delta = ''
      for (let i = 0; i < 10; i += 1) {
        await sleep(1000)
        const after = await getBuiltinAssistantLogBuffer('topoclaw')
        delta = after.slice(Math.min(beforeLen, after.length))
        const text = delta.toLowerCase()
        if (text.includes('weixin channel enabled') || text.includes('weixin monitor started')) {
          setWeixinSuccess('测试成功：微信通道已启动。你现在可以在微信侧发消息进行验证。')
          setWeixinError('')
          return
        }
        if (
          text.includes('请使用微信扫描') ||
          text.includes('weixin (ilink): 正在获取登录二维码') ||
          text.includes('已扫码，请在微信上确认登录')
        ) {
          setWeixinSuccess('测试中：已进入微信扫码绑定流程，请在日志二维码完成确认后再重试发送消息。')
          setWeixinError('')
          return
        }
        if (
          text.includes('微信扫码登录失败') ||
          text.includes('微信登录超时') ||
          text.includes('failed to start channel weixin') ||
          text.includes('weixin: base_url missing')
        ) {
          setWeixinError('测试失败：请检查 Base URL、网络连通性，或重新扫码绑定获取 botToken。')
          setWeixinSuccess('')
          return
        }
      }

      setWeixinError('测试超时：未检测到明确成功信号，请打开日志进一步排查。')
      setWeixinSuccess('')
    } finally {
      setWeixinTesting(false)
    }
  }

  const handleCheckDesktopUpdate = () => {
    setVersionCheckHint(null)
    setVersionCheckBusy(true)
    reinitApiClients()
    void probeDesktopUpdate()
      .then((p) => {
        if (p) setDesktopUpdate(p)
        else setVersionCheckHint('当前已是最新版本')
      })
      .catch(() => setVersionCheckHint('检查失败，请确认服务器地址与网络'))
      .finally(() => setVersionCheckBusy(false))
  }

  const openOpenSourceNotice = () => {
    setShowOpenSourceNotice(true)
    if (openSourceNoticeText || openSourceNoticeLoading) return
    setOpenSourceNoticeLoading(true)
    setOpenSourceNoticeError(null)
    void fetch('./THIRD_PARTY_LICENSES.md')
      .then(async (res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.text()
      })
      .then((text) => {
        setOpenSourceNoticeText(text)
      })
      .catch((e) => {
        setOpenSourceNoticeError(`读取开源声明失败：${String(e)}。请先执行 npm run licenses:generate 后重新打包。`)
      })
      .finally(() => setOpenSourceNoticeLoading(false))
  }

  return (
    <div className="settings-view">
      {desktopUpdate && (
        <DesktopUpdateDialog
          open
          forceUpdate={desktopUpdate.forceUpdate}
          currentVersion={desktopUpdate.currentVersion}
          latestVersion={desktopUpdate.latestVersion}
          message={desktopUpdate.updateMessage}
          updateUrl={desktopUpdate.updateUrl}
          onDismiss={() => setDesktopUpdate(null)}
        />
      )}
      {showNewAssistant && (
        <NewAssistantModal
          onClose={() => setShowNewAssistant(false)}
          onSaved={() => onNewAssistantSaved?.()}
        />
      )}
      {showDeveloper && (
        <div className="settings-developer-overlay">
          <div className="settings-developer-modal">
            <div className="settings-developer-header">
              <h3>开发者选项</h3>
              <button
                type="button"
                className="settings-developer-close"
                onClick={() => setShowDeveloper(false)}
              >
                ×
              </button>
            </div>
            <div className="settings-developer-body">
              <DeveloperView hideHeader />
            </div>
          </div>
        </div>
      )}
      {showQqModal && (
        <div className="settings-developer-overlay" onClick={() => !qqSaving && !qqTesting && setShowQqModal(false)}>
          <div className="settings-developer-modal settings-qq-modal" onClick={(e) => e.stopPropagation()}>
            <div className="settings-developer-header">
              <h3>注册 QQ 通道</h3>
              <button
                type="button"
                className="settings-developer-close"
                onClick={() => setShowQqModal(false)}
                disabled={qqSaving || qqTesting}
              >
                ×
              </button>
            </div>
            <div className="settings-developer-body settings-qq-body">
              <p className="settings-version-hint">
                输入你在 QQ 机器人平台申请的 AppID 和 AppSecret。保存后会自动重启内置 TopoClaw 服务。
              </p>
              <div className="settings-group">
                <label>QQ AppID</label>
                <input
                  type="text"
                  className="settings-input"
                  value={qqAppId}
                  onChange={(e) => setQqAppId(e.target.value)}
                  placeholder="如：1903678999"
                />
              </div>
              <div className="settings-group">
                <label>QQ AppSecret</label>
                <input
                  type="password"
                  className="settings-input"
                  value={qqAppSecret}
                  onChange={(e) => setQqAppSecret(e.target.value)}
                  placeholder="请输入 AppSecret"
                />
              </div>
              <div className="settings-group">
                <label>Allow From（可选，逗号分隔，默认 *）</label>
                <input
                  type="text"
                  className="settings-input"
                  value={qqAllowFrom}
                  onChange={(e) => setQqAllowFrom(e.target.value)}
                  placeholder="* 或 user_openid_1,user_openid_2"
                />
              </div>
              {qqError && <div className="settings-error">{qqError}</div>}
              {qqSuccess && <div className="settings-success">{qqSuccess}</div>}
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setShowQqModal(false)}
                  disabled={qqSaving || qqTesting}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void handleTestQqConfig()}
                  disabled={qqSaving || qqTesting}
                >
                  {qqTesting ? '测试中…' : '测试连接'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={handleSaveQqConfig}
                  disabled={qqSaving || qqTesting}
                >
                  {qqSaving ? '保存中…' : '保存并启用'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {showWeixinModal && (
        <div
          className="settings-developer-overlay"
          onClick={() => !weixinSaving && !weixinTesting && setShowWeixinModal(false)}
        >
          <div className="settings-developer-modal settings-qq-modal" onClick={(e) => e.stopPropagation()}>
            <div className="settings-developer-header">
              <h3>注册微信通道</h3>
              <button
                type="button"
                className="settings-developer-close"
                  onClick={() => setShowWeixinModal(false)}
                disabled={weixinSaving || weixinTesting}
              >
                ×
              </button>
            </div>
            <div className="settings-developer-body settings-qq-body">
              <p className="settings-version-hint">
                可直接填写 botToken，或留空后保存并重启以触发扫码绑定。保存后会自动重启内置 TopoClaw 服务。
              </p>
              <div className="settings-group">
                <label>微信 BotToken（可选）</label>
                <input
                  type="password"
                  className="settings-input"
                  value={weixinBotToken}
                  onChange={(e) => setWeixinBotToken(e.target.value)}
                  placeholder="留空则重启后走扫码绑定"
                />
              </div>
              <div className="settings-group">
                <label>微信 Base URL</label>
                <input
                  type="text"
                  className="settings-input"
                  value={weixinBaseUrl}
                  onChange={(e) => setWeixinBaseUrl(e.target.value)}
                  placeholder={WEIXIN_DEFAULT_BASE_URL}
                />
              </div>
              <div className="settings-group">
                <label>微信登录二维码</label>
                <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                  {weixinQrDataUrl ? (
                    <img src={weixinQrDataUrl} alt="微信登录二维码" style={{ width: 180, height: 180, borderRadius: 8, border: '1px solid #eee' }} />
                  ) : (
                    <div
                      style={{
                        width: 180,
                        height: 180,
                        borderRadius: 8,
                        border: '1px dashed #ccc',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        color: '#999',
                        fontSize: 12,
                      }}
                    >
                      {weixinQrLoading ? '加载中…' : '暂无二维码'}
                    </div>
                  )}
                  <button
                    type="button"
                    className="settings-btn settings-btn-secondary"
                    onClick={() => void startWeixinQrLogin()}
                    disabled={weixinQrLoading || weixinSaving || weixinTesting}
                  >
                    {weixinQrLoading ? '获取中…' : '刷新二维码'}
                  </button>
                </div>
                {weixinQrHint && <div className="settings-version-hint">{weixinQrHint}</div>}
              </div>
              <div className="settings-group">
                <label>Allow From（可选，逗号分隔，默认 *）</label>
                <input
                  type="text"
                  className="settings-input"
                  value={weixinAllowFrom}
                  onChange={(e) => setWeixinAllowFrom(e.target.value)}
                  placeholder="* 或 user_id_1,user_id_2"
                />
              </div>
              {weixinError && <div className="settings-error">{weixinError}</div>}
              {weixinSuccess && <div className="settings-success">{weixinSuccess}</div>}
              <div className="settings-actions">
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setShowWeixinModal(false)}
                  disabled={weixinSaving || weixinTesting}
                >
                  取消
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void handleTestWeixinConfig()}
                  disabled={weixinSaving || weixinTesting}
                >
                  {weixinTesting ? '测试中…' : '测试连接'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={handleSaveWeixinConfig}
                  disabled={weixinSaving || weixinTesting}
                >
                  {weixinSaving ? '保存中…' : '保存并启用'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
      {showOpenSourceNotice && (
        <div className="settings-developer-overlay" onClick={() => setShowOpenSourceNotice(false)}>
          <div className="settings-developer-modal settings-qq-modal" onClick={(e) => e.stopPropagation()}>
            <div className="settings-developer-header">
              <h3>第三方声明（含开源许可）</h3>
              <button type="button" className="settings-developer-close" onClick={() => setShowOpenSourceNotice(false)}>
                ×
              </button>
            </div>
            <div className="settings-developer-body settings-qq-body">
              {openSourceNoticeLoading && <div className="settings-version-hint">读取中…</div>}
              {openSourceNoticeError && <div className="settings-error">{openSourceNoticeError}</div>}
              {!openSourceNoticeLoading && !openSourceNoticeError && (
                <pre className="settings-notice-pre">{openSourceNoticeText}</pre>
              )}
            </div>
          </div>
        </div>
      )}
      <div className="settings-header">
        <h2>设置</h2>
      </div>
      <div className="settings-content">
        <div className="settings-group">
          <label>中转服务域名</label>
          <div className="settings-domain-mode-row">
            <button
              type="button"
              className={`settings-domain-mode-btn ${customerServiceDomainMode === 'external' ? 'active' : ''}`}
              onClick={() => { void handleCustomerServiceDomainModeChange('external') }}
            >
              外部服务
            </button>
            <button
              type="button"
              className={`settings-domain-mode-btn ${customerServiceDomainMode === 'internal' ? 'active' : ''}`}
              onClick={() => { void handleCustomerServiceDomainModeChange('internal') }}
            >
              本地内置服务
            </button>
          </div>
          {customerServiceDomainMode === 'external' ? (
            <>
              <input
                type="text"
                value={customerServiceUrlDraft}
                onChange={(e) => setCustomerServiceUrlDraft(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key !== 'Enter') return
                  e.preventDefault()
                  void handleCustomerServiceSave()
                }}
                className="settings-input"
                placeholder={DEFAULT_CUSTOMER_SERVICE_URL}
              />
              <div className="settings-value-row">
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={() => void handleCustomerServiceSave()}
                  disabled={customerServiceSaving || customerServiceStarting}
                >
                  {(customerServiceSaving || customerServiceStarting) ? '保存中…' : '保存'}
                </button>
                {customerServiceDomainSwitched && (
                  <span className="settings-inline-hint">点击保存切换域名并重新登录</span>
                )}
              </div>
            </>
          ) : (
            <>
              <div className="settings-value">
                {buildInternalCustomerServiceUrl((builtinDefaultIp || '').trim() || 'localhost')}
              </div>
              <div className="settings-value-row">
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={handleCustomerServiceInternalRestart}
                  disabled={customerServiceStarting}
                >
                  {customerServiceStarting ? '启动中…' : '启动/重启'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={() => void handleCustomerServiceSave()}
                  disabled={customerServiceSaving || customerServiceStarting}
                >
                  {(customerServiceSaving || customerServiceStarting) ? '保存中…' : '保存'}
                </button>
                {customerServiceDomainSwitched && (
                  <span className="settings-inline-hint">点击保存切换域名并重新登录</span>
                )}
              </div>
            </>
          )}
          {customerServiceSyncHint && <div className="settings-version-hint">{customerServiceSyncHint}</div>}
        </div>
        <div className="settings-group">
          <label>技能社区服务地址（v9）</label>
          <input
            type="text"
            defaultValue={skillCommunityUrl}
            onBlur={handleSkillCommunityChange}
            onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
            className="settings-input"
            placeholder={DEFAULT_SKILL_COMMUNITY_URL}
          />
        </div>
        <div className="settings-group">
          <label>全局模型配置（默认域名助手共享）</label>
          <div className="settings-value-row" style={{ marginBottom: globalModelExpanded ? 12 : 0 }}>
            <button
              type="button"
              className="settings-btn settings-btn-secondary"
              onClick={() => setGlobalModelExpanded((v) => !v)}
            >
              {globalModelExpanded ? '收起配置' : '展开配置'}
            </button>
            {globalModelExpanded && (
              <>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void loadGlobalModelProfiles()}
                  disabled={globalModelLoading || globalModelSaving}
                >
                  {globalModelLoading ? '读取中…' : '刷新'}
                </button>
                <button
                  type="button"
                  className="settings-btn settings-btn-primary"
                  onClick={() => void handleSaveGlobalModelProfiles()}
                  disabled={globalModelSaving || globalModelLoading}
                >
                  {globalModelSaving ? '保存中…' : '保存全局模型配置'}
                </button>
              </>
            )}
          </div>
          {globalModelExpanded && (
            <>
              <div className="settings-value-debug" style={{ marginBottom: 10 }}>
                规则：默认域名下所有自定义助手共用该配置；在此修改后会同步到依赖该全局配置的自定义助手。
              </div>
              <div className="settings-group">
                <label>chat 配置（non-gui）</label>
                {globalNonGuiProfiles.map((row, idx) => (
                  <div key={`settings-ng-${idx}`} style={{ border: '1px solid #eee', borderRadius: 8, padding: 10, marginBottom: 8 }}>
                    <input
                      type="text"
                      className="settings-input"
                      placeholder="模型名，如 gpt-4o-mini"
                      value={row.model}
                      onChange={(e) => patchGlobalNonGuiProfile(idx, { model: e.target.value })}
                    />
                    <input
                      type="text"
                      className="settings-input"
                      placeholder="API Base，如 https://api.openai.com/v1"
                      value={row.apiBase}
                      onChange={(e) => patchGlobalNonGuiProfile(idx, { apiBase: e.target.value })}
                    />
                    <input
                      type="password"
                      className="settings-input"
                      placeholder="API Key"
                      value={row.apiKey}
                      onChange={(e) => patchGlobalNonGuiProfile(idx, { apiKey: e.target.value })}
                    />
                    {globalNonGuiProfiles.length > 1 && (
                      <button
                        type="button"
                        className="settings-btn settings-btn-secondary"
                        onClick={() => setGlobalNonGuiProfiles((rows) => rows.filter((_, i) => i !== idx))}
                      >
                        删除此条
                      </button>
                    )}
                  </div>
                ))}
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setGlobalNonGuiProfiles((rows) => [...rows, { model: '', apiBase: '', apiKey: '' }])}
                >
                  添加 chat 配置
                </button>
              </div>
              <div className="settings-group">
                <label>GUI 配置</label>
                {globalGuiProfiles.map((row, idx) => (
                  <div key={`settings-g-${idx}`} style={{ border: '1px solid #eee', borderRadius: 8, padding: 10, marginBottom: 8 }}>
                    <input
                      type="text"
                      className="settings-input"
                      placeholder="模型名，如 Qwen3-VL-32B-Instruct-rl"
                      value={row.model}
                      onChange={(e) => patchGlobalGuiProfile(idx, { model: e.target.value })}
                    />
                    <input
                      type="text"
                      className="settings-input"
                      placeholder="API Base"
                      value={row.apiBase}
                      onChange={(e) => patchGlobalGuiProfile(idx, { apiBase: e.target.value })}
                    />
                    <input
                      type="password"
                      className="settings-input"
                      placeholder="API Key"
                      value={row.apiKey}
                      onChange={(e) => patchGlobalGuiProfile(idx, { apiKey: e.target.value })}
                    />
                    {globalGuiProfiles.length > 1 && (
                      <button
                        type="button"
                        className="settings-btn settings-btn-secondary"
                        onClick={() => setGlobalGuiProfiles((rows) => rows.filter((_, i) => i !== idx))}
                      >
                        删除此条
                      </button>
                    )}
                  </div>
                ))}
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => setGlobalGuiProfiles((rows) => [...rows, { model: '', apiBase: '', apiKey: '' }])}
                >
                  添加 GUI 配置
                </button>
              </div>
              <div className="settings-group">
                <label>当前生效模型</label>
                <div style={{ display: 'grid', gap: 8 }}>
                  <select
                    className="settings-input"
                    value={globalActiveNonGuiModel}
                    onChange={(e) => setGlobalActiveNonGuiModel(e.target.value)}
                  >
                    {globalNonGuiProfiles.map((p, idx) => (
                      <option key={`active-ng-${idx}-${p.model}`} value={p.model}>{p.model || '（空模型名）'}</option>
                    ))}
                  </select>
                  <select
                    className="settings-input"
                    value={globalActiveGuiModel}
                    onChange={(e) => setGlobalActiveGuiModel(e.target.value)}
                  >
                    {globalGuiProfiles.map((p, idx) => (
                      <option key={`active-g-${idx}-${p.model}`} value={p.model}>{p.model || '（空模型名）'}</option>
                    ))}
                  </select>
                </div>
              </div>
              {globalModelError && <div className="settings-error">{globalModelError}</div>}
              {globalModelHint && <div className="settings-version-hint">{globalModelHint}</div>}
            </>
          )}
        </div>
        <div className="settings-group">
          <label>Token 消耗统计（内置 TopoClaw）</label>
          <div className="settings-value-row" style={{ marginBottom: 10 }}>
            <select
              className="settings-input"
              style={{ maxWidth: 160 }}
              value={tokenUsageDays}
              onChange={(e) => setTokenUsageDays(Number(e.target.value) || 30)}
              disabled={tokenUsageLoading}
            >
              <option value={7}>最近 7 天</option>
              <option value={30}>最近 30 天</option>
              <option value={90}>最近 90 天</option>
            </select>
            <button
              type="button"
              className="settings-btn settings-btn-secondary"
              onClick={() => void loadTokenUsageStats(tokenUsageDays)}
              disabled={tokenUsageLoading}
            >
              {tokenUsageLoading ? '刷新中…' : '刷新统计'}
            </button>
          </div>
          <div className="settings-value settings-value-debug" style={{ marginBottom: 10 }}>
            <div>总输入 Token：{formatTokenNumber(tokenUsageStats.total.input_tokens)}</div>
            <div>总输出 Token：{formatTokenNumber(tokenUsageStats.total.output_tokens)}</div>
          </div>
          <div style={{ marginBottom: 10, overflowX: 'auto' }}>
            <div
              ref={tokenModelChartRef}
              style={{ background: '#fff', borderRadius: 8, padding: 12, border: '1px solid #f0f0f0' }}
            >
              <div className="settings-value-row" style={{ marginBottom: 8 }}>
                <strong>模型 Token 分布（最近 {tokenUsageDays} 天）</strong>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void exportTokenChartImage(tokenModelChartRef.current, 'model', `token-usage-by-model-${tokenUsageDays}d.png`)}
                  disabled={tokenUsageExporting === 'model'}
                >
                  {tokenUsageExporting === 'model' ? '导出中…' : '导出图片'}
                </button>
              </div>
              <div style={{ width: '100%', height: 260, marginBottom: 10 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={tokenModelChartData}
                    margin={{ top: 12, right: 12, left: 0, bottom: 10 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="name" interval={0} angle={-18} textAnchor="end" height={58} />
                    <YAxis />
                    <Tooltip formatter={(value) => formatTokenTooltipValue(value)} />
                    <Legend />
                    <Bar dataKey="input" name="输入 Token" fill="#8884d8" radius={[4, 4, 0, 0]} />
                    <Bar dataKey="output" name="输出 Token" fill="#82ca9d" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left', padding: '6px 4px' }}>模型</th>
                  <th style={{ textAlign: 'right', padding: '6px 4px' }}>输入</th>
                  <th style={{ textAlign: 'right', padding: '6px 4px' }}>输出</th>
                </tr>
              </thead>
              <tbody>
                {tokenUsageStats.by_model.slice(0, 8).map((row) => (
                  <tr key={row.model}>
                    <td style={{ padding: '4px' }}>{row.model}</td>
                    <td style={{ textAlign: 'right', padding: '4px' }}>{formatTokenNumber(row.input_tokens)}</td>
                    <td style={{ textAlign: 'right', padding: '4px' }}>{formatTokenNumber(row.output_tokens)}</td>
                  </tr>
                ))}
                {tokenUsageStats.by_model.length === 0 && (
                  <tr>
                    <td style={{ padding: '4px', color: '#999' }} colSpan={3}>暂无模型统计数据</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <div
              ref={tokenTrendChartRef}
              style={{ background: '#fff', borderRadius: 8, padding: 12, border: '1px solid #f0f0f0' }}
            >
              <div className="settings-value-row" style={{ marginBottom: 8 }}>
                <strong>每日 Token 趋势（最近 {tokenUsageDays} 天）</strong>
                <button
                  type="button"
                  className="settings-btn settings-btn-secondary"
                  onClick={() => void exportTokenChartImage(tokenTrendChartRef.current, 'trend', `token-usage-trend-${tokenUsageDays}d.png`)}
                  disabled={tokenUsageExporting === 'trend'}
                >
                  {tokenUsageExporting === 'trend' ? '导出中…' : '导出图片'}
                </button>
              </div>
              <div style={{ width: '100%', height: 260, marginBottom: 10 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart
                    data={tokenDayChartData}
                    margin={{ top: 12, right: 12, left: 0, bottom: 4 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis dataKey="date" />
                    <YAxis />
                    <Tooltip formatter={(value) => formatTokenTooltipValue(value)} />
                    <Legend />
                    <Line type="monotone" dataKey="input" name="输入 Token" stroke="#8884d8" strokeWidth={2} dot={false} />
                    <Line type="monotone" dataKey="output" name="输出 Token" stroke="#82ca9d" strokeWidth={2} dot={false} />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
              <thead>
                <tr>
                  <th style={{ textAlign: 'left', padding: '6px 4px' }}>日期</th>
                  <th style={{ textAlign: 'right', padding: '6px 4px' }}>输入</th>
                  <th style={{ textAlign: 'right', padding: '6px 4px' }}>输出</th>
                </tr>
              </thead>
              <tbody>
                {tokenUsageStats.by_day.map((row) => (
                  <tr key={row.date}>
                    <td style={{ padding: '4px' }}>{row.date}</td>
                    <td style={{ textAlign: 'right', padding: '4px' }}>{formatTokenNumber(row.input_tokens)}</td>
                    <td style={{ textAlign: 'right', padding: '4px' }}>{formatTokenNumber(row.output_tokens)}</td>
                  </tr>
                ))}
                {tokenUsageStats.by_day.length === 0 && (
                  <tr>
                    <td style={{ padding: '4px', color: '#999' }} colSpan={3}>暂无每日统计数据</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {tokenUsageHint ? <div className="settings-version-hint">{tokenUsageHint}</div> : null}
        </div>
        <div className="settings-group">
          <label>当前 IMEI</label>
          <div className="settings-value">{imei || '-'}</div>
        </div>
        <div className="settings-group">
          <label>默认内置服务地址（调试）</label>
          <div className="settings-value settings-value-debug">
            <div>默认 IP：{builtinDefaultIp}</div>
            <div>TopoClaw：{builtinDefaultUrls?.topoclaw || '—'}</div>
            <div>GroupManager：{builtinDefaultUrls?.groupmanager || '—'}</div>
          </div>
          <div className="settings-value-row">
            <button
              type="button"
              className="settings-btn settings-btn-secondary settings-check-update-btn"
              disabled={builtinUrlLoading}
              onClick={refreshBuiltinDefaultUrls}
            >
              {builtinUrlLoading ? '刷新中…' : '刷新默认地址'}
            </button>
          </div>
          {builtinUrlHint && <div className="settings-version-hint">{builtinUrlHint}</div>}
        </div>
        <div className="settings-group">
          <label>应用版本</label>
          <div className="settings-value settings-value-row">
            <span>{appVersion}</span>
            <button
              type="button"
              className="settings-btn settings-btn-secondary settings-check-update-btn"
              disabled={versionCheckBusy}
              onClick={handleCheckDesktopUpdate}
            >
              {versionCheckBusy ? '检查中…' : '检查更新'}
            </button>
          </div>
          {versionCheckHint && <div className="settings-version-hint">{versionCheckHint}</div>}
        </div>
        <div className="settings-group">
          <label>第三方声明（含开源许可）</label>
          <button
            type="button"
            className="settings-btn settings-btn-secondary settings-developer-entry"
            onClick={openOpenSourceNotice}
          >
            查看声明
          </button>
        </div>
        <div className="settings-group">
          <label>开发者选项</label>
          <button
            type="button"
            className="settings-btn settings-btn-secondary settings-developer-entry"
            onClick={() => setShowDeveloper(true)}
          >
            进入开发者工具
          </button>
        </div>
        <div className="settings-group">
          <label>QQ 通道接入</label>
          <button
            type="button"
            className="settings-btn settings-btn-secondary settings-developer-entry"
            onClick={() => void openQqConfigModal()}
          >
            配置 QQ 机器人
          </button>
        </div>
        <div className="settings-group">
          <label>微信通道接入</label>
          <button
            type="button"
            className="settings-btn settings-btn-secondary settings-developer-entry"
            onClick={() => void openWeixinConfigModal()}
          >
            配置微信通道
          </button>
        </div>
        <div className="settings-group settings-group-toggle">
          <label className="settings-toggle-row">
            <span>开启所有内置服务</span>
            <input
              type="checkbox"
              checked={builtinServicesEnabled}
              onChange={(e) => void handleBuiltinServicesToggle(e)}
              className="settings-toggle-input"
              disabled={builtinServicesSaving}
            />
            <span className="settings-toggle-slider" />
          </label>
          <div className="settings-toggle-desc">
            关闭后将停止 TopoClaw / GroupManager，并禁用内置终端执行
          </div>
          {builtinServicesHint ? <div className="settings-version-hint">{builtinServicesHint}</div> : null}
        </div>
        <div className="settings-group settings-group-toggle">
          <label className="settings-toggle-row">
            <span>数字分身</span>
            <input
              type="checkbox"
              checked={digitalCloneEnabled}
              onChange={(e) => void handleDigitalCloneChange(e)}
              className="settings-toggle-input"
              disabled={!imei || digitalCloneLoading}
            />
            <span className="settings-toggle-slider" />
          </label>
          <div className="settings-toggle-desc">
            作为默认开关；好友会话右上角可单独覆盖当前好友
          </div>
        </div>
        <div className="settings-group settings-group-toggle">
          <label className="settings-toggle-row">
            <span>是否自动执行代码</span>
            <input
              type="checkbox"
              checked={autoExecuteCode}
              onChange={handleAutoExecuteChange}
              className="settings-toggle-input"
            />
            <span className="settings-toggle-slider" />
          </label>
          <div className="settings-toggle-desc">开启后，模型生成的 Python 代码将自动执行</div>
        </div>
        <div className="settings-actions">
          <button className="settings-btn settings-btn-secondary" onClick={() => setShowNewAssistant(true)}>
            新建小助手
          </button>
          <button
            type="button"
            className="settings-btn settings-btn-secondary"
            onClick={() => void handleAdaptAssistantIds()}
            disabled={adaptAssistantIdsBusy}
            title="将当前账号在云侧与群组内的助手标识统一到新体系（displayId 优先）"
          >
            {adaptAssistantIdsBusy ? '适配中…' : '适配新助手id'}
          </button>
          {typeof window !== 'undefined' && window.terminalAPI && (
            <button
              type="button"
              className="settings-btn settings-btn-secondary"
              onClick={() => window.terminalAPI!.openWindow()}
              title={builtinServicesEnabled ? '在独立窗口中打开终端，可使用捆绑的 Python 环境' : '内置服务已关闭，终端不可用'}
              disabled={!builtinServicesEnabled}
            >
              打开 Python 终端
            </button>
          )}
        </div>
        <div className="settings-actions">
          <button className="settings-btn settings-btn-secondary" onClick={handleClearChatHistory}>
            清空聊天记录
          </button>
          <button className="settings-btn settings-btn-danger" onClick={handleLogout}>
            切换账号
          </button>
        </div>
      </div>
    </div>
  )
}
