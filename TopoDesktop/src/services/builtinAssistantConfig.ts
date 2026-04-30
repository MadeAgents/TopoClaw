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

import { getCustomAssistants } from './customAssistants'

/** 内置小助手环境配置（域名、模型、API Key） */
const ENV_GUI_API_BASE = (import.meta.env.VITE_BUILTIN_GUI_API_BASE as string | undefined)?.trim()
const DEFAULT_GUI_API_BASE = ENV_GUI_API_BASE || 'https://example.invalid/v1'

export type BuiltinAssistantSlot = 'topoclaw' | 'groupmanager'

/** 与 config.json 中 topo_desktop 条目一致 */
export interface BuiltinModelProfileRow {
  model: string
  apiBase: string
  apiKey: string
}

export type BuiltinModelProfilesResult =
  | {
      ok: true
      nonGuiProfiles: BuiltinModelProfileRow[]
      guiProfiles: BuiltinModelProfileRow[]
      activeNonGuiModel: string
      activeImageModel: string
      activeGuiModel: string
      activeGroupManagerModel: string
    }
  | { ok: false; error: string }

const BUILTIN_MODEL_PROFILES_CACHE_TTL_MS = 5 * 60 * 1000
let builtinModelProfilesCache: { value: BuiltinModelProfilesResult; at: number } | null = null
let builtinModelProfilesPending: Promise<BuiltinModelProfilesResult> | null = null

export interface BuiltinAssistantConfig {
  model: string
  apiBase: string
  apiKey: string
  /** agents.gui（provider: custom2） */
  guiModel: string
  guiApiBase: string
  guiApiKey: string
  /** channels.qq */
  qqEnabled: boolean
  qqAppId: string
  qqAppSecret: string
  /** comma-separated openid list, "*" means allow all */
  qqAllowFrom: string
  /** channels.weixin */
  weixinEnabled: boolean
  weixinBotToken: string
  weixinBaseUrl: string
  /** comma-separated weixin user id list, "*" means allow all */
  weixinAllowFrom: string
}

const STORAGE_KEY = 'builtin_assistant_config'

const DEFAULT: BuiltinAssistantConfig = {
  model: 'gpt-4o-mini',
  apiBase: 'https://api.openai.com/v1',
  apiKey: '',
  guiModel: 'Qwen3-VL-32B-Instruct-rl',
  guiApiBase: DEFAULT_GUI_API_BASE,
  guiApiKey: '',
  qqEnabled: false,
  qqAppId: '',
  qqAppSecret: '',
  qqAllowFrom: '*',
  weixinEnabled: false,
  weixinBotToken: '',
  weixinBaseUrl: 'https://ilinkai.weixin.qq.com',
  weixinAllowFrom: '*',
}

const BUILTIN_TOPOCLAW_PORT = 18790
const BUILTIN_GROUP_MANAGER_PORT = 18791

/** 获取指定内置服务的默认 baseUrl（TopoClaw=topoclaw，GroupManager=SimpleChat） */
export async function getDefaultBuiltinUrl(slot: BuiltinAssistantSlot = 'topoclaw'): Promise<string> {
  const api = (window as unknown as { builtinAssistant?: { getDefaultUrl: (s?: BuiltinAssistantSlot) => Promise<string> } }).builtinAssistant
  if (api?.getDefaultUrl) {
    try {
      return await api.getDefaultUrl(slot)
    } catch {
      // fallback
    }
  }
  const port = slot === 'groupmanager' ? BUILTIN_GROUP_MANAGER_PORT : BUILTIN_TOPOCLAW_PORT
  return `http://localhost:${port}/`
}

export async function getDefaultBuiltinUrls(): Promise<{ topoclaw: string; groupmanager: string }> {
  const api = (window as unknown as { builtinAssistant?: { getDefaultUrls: () => Promise<{ topoclaw: string; groupmanager: string }> } }).builtinAssistant
  if (api?.getDefaultUrls) {
    try {
      return await api.getDefaultUrls()
    } catch {
      // fallback
    }
  }
  const topoclaw = await getDefaultBuiltinUrl('topoclaw')
  const groupmanager = await getDefaultBuiltinUrl('groupmanager')
  return { topoclaw, groupmanager }
}

const BUILTIN_SERVICES_TOGGLE_KEY = 'builtin_services_enabled'

export async function getBuiltinServicesEnabled(): Promise<boolean> {
  const api = (window as unknown as {
    builtinAssistant?: {
      getGlobalEnabled: () => Promise<{ ok: boolean; enabled: boolean; error?: string }>
    }
  }).builtinAssistant
  if (api?.getGlobalEnabled) {
    try {
      const res = await api.getGlobalEnabled()
      if (res.ok) return res.enabled !== false
    } catch {
      // fallback
    }
  }
  try {
    const raw = localStorage.getItem(BUILTIN_SERVICES_TOGGLE_KEY)
    if (raw == null) return true
    return raw !== '0'
  } catch {
    return true
  }
}

export async function setBuiltinServicesEnabled(enabled: boolean): Promise<{ ok: boolean; enabled: boolean; error?: string }> {
  const api = (window as unknown as {
    builtinAssistant?: {
      setGlobalEnabled: (enabled: boolean) => Promise<{ ok: boolean; enabled: boolean; error?: string }>
    }
  }).builtinAssistant
  if (api?.setGlobalEnabled) {
    try {
      const res = await api.setGlobalEnabled(enabled)
      if (res.ok) {
        try {
          localStorage.setItem(BUILTIN_SERVICES_TOGGLE_KEY, res.enabled ? '1' : '0')
        } catch {
          // ignore
        }
      }
      return res
    } catch (e) {
      return { ok: false, enabled, error: String(e) }
    }
  }
  try {
    localStorage.setItem(BUILTIN_SERVICES_TOGGLE_KEY, enabled ? '1' : '0')
  } catch {
    return { ok: false, enabled, error: '本地保存失败' }
  }
  return { ok: true, enabled }
}

const normUrl = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''

/** 是否有使用任一内置默认地址的小助手 */
export async function hasDefaultDomainAssistant(): Promise<boolean> {
  const urls = await getDefaultBuiltinUrls()
  const list = getCustomAssistants()
  const candidates = new Set([
    normUrl(urls.topoclaw),
    normUrl(urls.groupmanager),
    normUrl(`http://localhost:${BUILTIN_TOPOCLAW_PORT}/`),
    normUrl(`http://localhost:${BUILTIN_GROUP_MANAGER_PORT}/`),
  ])
  return list.some((a) => candidates.has(normUrl(a.baseUrl)))
}

/** 确保内置服务已启动（TopoClaw + GroupManager） */
export async function ensureBuiltinAssistantStarted(): Promise<{ ok: boolean; alreadyRunning?: boolean; error?: string }> {
  const api = (window as unknown as { builtinAssistant?: { ensureStarted: () => Promise<{ ok: boolean; alreadyRunning?: boolean; error?: string }> } }).builtinAssistant
  if (api?.ensureStarted) {
    try {
      return await api.ensureStarted()
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }
  return { ok: false, error: '非 Electron 环境' }
}

export async function restartBuiltinAssistant(slot?: BuiltinAssistantSlot): Promise<{ ok: boolean; error?: string }> {
  const api = (window as unknown as { builtinAssistant?: { restart: (s?: BuiltinAssistantSlot) => Promise<{ ok: boolean; error?: string }> } }).builtinAssistant
  if (api?.restart) {
    try {
      return await api.restart(slot)
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }
  return { ok: false, error: '非 Electron 环境' }
}

export async function setActiveCustomerServiceUrlForBuiltinAssistant(
  customerServiceUrl: string
): Promise<{ ok: boolean; customerServiceUrl?: string; restarted?: boolean; error?: string }> {
  const api = (window as unknown as {
    builtinAssistant?: {
      setActiveCustomerServiceUrl?: (
        customerServiceUrl: string
      ) => Promise<{ ok: boolean; customerServiceUrl?: string; restarted?: boolean; error?: string }>
    }
  }).builtinAssistant
  if (!api?.setActiveCustomerServiceUrl) {
    return { ok: false, error: '非 Electron 环境' }
  }
  try {
    return await api.setActiveCustomerServiceUrl(customerServiceUrl)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function syncTopomobileWsUrlFromCustomerServiceUrl(
  customerServiceUrl: string
): Promise<{ ok: boolean; wsUrl?: string; restarted?: boolean; error?: string }> {
  const api = (window as unknown as {
    builtinAssistant?: {
      syncTopomobileWsUrlFromCustomerServiceUrl?: (
        customerServiceUrl: string
      ) => Promise<{ ok: boolean; wsUrl?: string; restarted?: boolean; error?: string }>
    }
  }).builtinAssistant
  if (!api?.syncTopomobileWsUrlFromCustomerServiceUrl) {
    return { ok: false, error: '非 Electron 环境' }
  }
  try {
    return await api.syncTopomobileWsUrlFromCustomerServiceUrl(customerServiceUrl)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function startBuiltinCustomerService(
  options?: { restart?: boolean }
): Promise<{ ok: boolean; alreadyRunning?: boolean; restarted?: boolean; error?: string }> {
  const api = (window as unknown as {
    builtinAssistant?: {
      startCustomerService?: (
        params?: { restart?: boolean }
      ) => Promise<{ ok: boolean; alreadyRunning?: boolean; restarted?: boolean; error?: string }>
    }
  }).builtinAssistant
  if (!api?.startCustomerService) {
    return { ok: false, error: '非 Electron 环境' }
  }
  try {
    return await api.startCustomerService({ restart: options?.restart === true })
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function isBuiltinCustomerServiceLogPipeActive(): Promise<boolean> {
  const api = (window as unknown as {
    builtinAssistant?: {
      customerServiceLogPipeActive?: () => Promise<boolean>
    }
  }).builtinAssistant
  try {
    return (await api?.customerServiceLogPipeActive?.()) ?? false
  } catch {
    return false
  }
}

export async function getBuiltinCustomerServiceLogBuffer(): Promise<string> {
  const api = (window as unknown as {
    builtinAssistant?: {
      customerServiceGetLogBuffer?: () => Promise<string>
    }
  }).builtinAssistant
  try {
    return (await api?.customerServiceGetLogBuffer?.()) ?? ''
  } catch {
    return ''
  }
}

export async function getBuiltinAssistantLogBuffer(slot: BuiltinAssistantSlot = 'topoclaw'): Promise<string> {
  const api = (window as unknown as { builtinAssistant?: { getLogBuffer: (s?: BuiltinAssistantSlot) => Promise<string> } }).builtinAssistant
  try {
    return (await api?.getLogBuffer?.(slot)) ?? ''
  } catch {
    return ''
  }
}

export type ExportBuiltinLogResult = { ok: boolean; error?: string; canceled?: boolean; path?: string }

export async function exportBuiltinAssistantLogToFile(text: string): Promise<ExportBuiltinLogResult> {
  const api = (window as unknown as { builtinAssistant?: { exportLog: (t: string) => Promise<ExportBuiltinLogResult> } })
    .builtinAssistant
  if (!api?.exportLog) return { ok: false, error: '非 Electron 环境' }
  try {
    return await api.exportLog(text)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function hasBuiltinAssistantLogStream(slot: BuiltinAssistantSlot = 'topoclaw'): Promise<boolean> {
  const api = (window as unknown as { builtinAssistant?: { hasLogStream: (s?: BuiltinAssistantSlot) => Promise<boolean> } }).builtinAssistant
  try {
    return (await api?.hasLogStream?.(slot)) ?? false
  } catch {
    return false
  }
}

export async function isBuiltinAssistantLogPipeActive(slot: BuiltinAssistantSlot = 'topoclaw'): Promise<boolean> {
  const api = (window as unknown as { builtinAssistant?: { logPipeActive: (s?: BuiltinAssistantSlot) => Promise<boolean> } }).builtinAssistant
  try {
    return (await api?.logPipeActive?.(slot)) ?? false
  } catch {
    return false
  }
}

export async function getBuiltinAssistantDefaults(): Promise<BuiltinAssistantConfig> {
  const api = (window as unknown as { builtinAssistant?: { getDefaults: () => Promise<BuiltinAssistantConfig> } }).builtinAssistant
  if (api?.getDefaults) {
    try {
      return await api.getDefaults()
    } catch {
      // fallback
    }
  }
  return { ...DEFAULT }
}

export async function getBuiltinAssistantConfig(): Promise<BuiltinAssistantConfig> {
  const defaults = await getBuiltinAssistantDefaults()
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved) as Partial<BuiltinAssistantConfig>
      return { ...defaults, ...parsed }
    }
  } catch {
    // ignore
  }
  return defaults
}

export async function saveBuiltinAssistantConfig(
  config: Partial<BuiltinAssistantConfig>,
  options?: { skipIpc?: boolean }
): Promise<{ ok: boolean; error?: string }> {
  const current = await getBuiltinAssistantConfig()
  const merged = { ...current, ...config }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(merged))
  } catch {
    return { ok: false, error: '本地保存失败' }
  }
  if (options?.skipIpc) return { ok: true }
  const api = (window as unknown as { builtinAssistant?: { saveConfig: (c: Partial<BuiltinAssistantConfig>) => Promise<{ ok: boolean; error?: string }> } }).builtinAssistant
  if (api?.saveConfig) {
    try {
      return await api.saveConfig(merged)
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }
  return { ok: true }
}

export async function getBuiltinModelProfiles(
  options?: { forceRefresh?: boolean }
): Promise<BuiltinModelProfilesResult> {
  const forceRefresh = options?.forceRefresh === true
  const now = Date.now()
  if (!forceRefresh && builtinModelProfilesCache) {
    if (now - builtinModelProfilesCache.at <= BUILTIN_MODEL_PROFILES_CACHE_TTL_MS) {
      return builtinModelProfilesCache.value
    }
  }
  if (!forceRefresh && builtinModelProfilesPending) {
    return builtinModelProfilesPending
  }
  const api = (window as unknown as { builtinAssistant?: { getModelProfiles: () => Promise<BuiltinModelProfilesResult> } }).builtinAssistant
  if (!api?.getModelProfiles) return { ok: false, error: '非 Electron 环境' }
  builtinModelProfilesPending = (async () => {
    try {
      const res = await api.getModelProfiles()
      builtinModelProfilesCache = { value: res, at: Date.now() }
      return res
    } catch (e) {
      return { ok: false, error: String(e) }
    } finally {
      builtinModelProfilesPending = null
    }
  })()
  return builtinModelProfilesPending
}

export async function warmBuiltinModelProfilesCache(): Promise<void> {
  await getBuiltinModelProfiles().catch(() => {})
}

export interface TokenUsageSummaryRow {
  input_tokens: number
  output_tokens: number
  total_tokens: number
  events: number
  estimated_events: number
}

export interface TokenUsageByModelRow extends TokenUsageSummaryRow {
  model: string
}

export interface TokenUsageByDayRow extends TokenUsageSummaryRow {
  date: string
}

export type BuiltinTokenUsageStatsResult =
  | {
      ok: true
      days: number
      total: TokenUsageSummaryRow
      by_model: TokenUsageByModelRow[]
      by_day: TokenUsageByDayRow[]
      generated_at?: string
    }
  | {
      ok: false
      error: string
      days: number
      total: TokenUsageSummaryRow
      by_model: TokenUsageByModelRow[]
      by_day: TokenUsageByDayRow[]
    }

const EMPTY_TOKEN_USAGE_TOTAL: TokenUsageSummaryRow = {
  input_tokens: 0,
  output_tokens: 0,
  total_tokens: 0,
  events: 0,
  estimated_events: 0,
}

export async function getBuiltinTokenUsageStats(
  options?: { days?: number; baseUrl?: string; signal?: AbortSignal }
): Promise<BuiltinTokenUsageStatsResult> {
  const daysRaw = Number(options?.days ?? 30)
  const days = Number.isFinite(daysRaw) && daysRaw > 0 ? Math.min(3650, Math.floor(daysRaw)) : 30
  let baseUrl = (options?.baseUrl || '').trim()
  if (!baseUrl) {
    try {
      baseUrl = await getDefaultBuiltinUrl('topoclaw')
    } catch {
      baseUrl = ''
    }
  }
  if (!baseUrl) {
    return {
      ok: false,
      error: '内置 TopoClaw 地址不可用',
      days,
      total: { ...EMPTY_TOKEN_USAGE_TOTAL },
      by_model: [],
      by_day: [],
    }
  }
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  try {
    const res = await fetch(`${normalizedBase}metrics/token-usage?days=${encodeURIComponent(String(days))}`, {
      signal: options?.signal,
    })
    if (!res.ok) {
      return {
        ok: false,
        error: `HTTP ${res.status}`,
        days,
        total: { ...EMPTY_TOKEN_USAGE_TOTAL },
        by_model: [],
        by_day: [],
      }
    }
    const data = (await res.json()) as Partial<BuiltinTokenUsageStatsResult> & Record<string, unknown>
    const totalObj = (data.total as Record<string, unknown> | undefined) || {}
    const byModel = Array.isArray(data.by_model) ? data.by_model as TokenUsageByModelRow[] : []
    const byDay = Array.isArray(data.by_day) ? data.by_day as TokenUsageByDayRow[] : []
    if (data.ok === true) {
      return {
        ok: true,
        days: Number(data.days ?? days) || days,
        total: {
          input_tokens: Number(totalObj.input_tokens ?? 0) || 0,
          output_tokens: Number(totalObj.output_tokens ?? 0) || 0,
          total_tokens: Number(totalObj.total_tokens ?? 0) || 0,
          events: Number(totalObj.events ?? 0) || 0,
          estimated_events: Number(totalObj.estimated_events ?? 0) || 0,
        },
        by_model: byModel,
        by_day: byDay,
        generated_at: typeof data.generated_at === 'string' ? data.generated_at : undefined,
      }
    }
    return {
      ok: false,
      error: typeof data.error === 'string' ? data.error : '统计接口返回异常',
      days: Number(data.days ?? days) || days,
      total: {
        input_tokens: Number(totalObj.input_tokens ?? 0) || 0,
        output_tokens: Number(totalObj.output_tokens ?? 0) || 0,
        total_tokens: Number(totalObj.total_tokens ?? 0) || 0,
        events: Number(totalObj.events ?? 0) || 0,
        estimated_events: Number(totalObj.estimated_events ?? 0) || 0,
      },
      by_model: byModel,
      by_day: byDay,
    }
  } catch (e) {
    return {
      ok: false,
      error: String(e),
      days,
      total: { ...EMPTY_TOKEN_USAGE_TOTAL },
      by_model: [],
      by_day: [],
    }
  }
}

export async function saveBuiltinModelProfiles(payload: {
  nonGuiProfiles?: BuiltinModelProfileRow[]
  guiProfiles?: BuiltinModelProfileRow[]
  activeNonGuiModel?: string
  activeImageModel?: string
  activeGuiModel?: string
  activeGroupManagerModel?: string
}): Promise<{ ok: boolean; error?: string }> {
  const api = (window as unknown as { builtinAssistant?: { saveModelProfiles: (p: unknown) => Promise<{ ok: boolean; error?: string }> } }).builtinAssistant
  if (!api?.saveModelProfiles) return { ok: false, error: '非 Electron 环境' }
  try {
    const res = await api.saveModelProfiles(payload)
    if (res.ok) {
      builtinModelProfilesCache = null
    }
    return res
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function applyBuiltinModelSelection(params: {
  slot: BuiltinAssistantSlot
  nonGuiModel: string
  guiModel?: string
}): Promise<{ ok: boolean; error?: string }> {
  const api = (window as unknown as { builtinAssistant?: { applyModelSelection: (p: typeof params) => Promise<{ ok: boolean; error?: string }> } }).builtinAssistant
  if (!api?.applyModelSelection) return { ok: false, error: '非 Electron 环境' }
  try {
    return await api.applyModelSelection(params)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export type ReadLocalConfigTxtResult =
  | { ok: true; nonGuiProfiles: BuiltinModelProfileRow[]; guiProfiles: BuiltinModelProfileRow[] }
  | { ok: false; error: string }

export async function readLocalConfigTxt(): Promise<ReadLocalConfigTxtResult> {
  const api = (window as unknown as { builtinAssistant?: { readLocalConfigTxt: () => Promise<ReadLocalConfigTxtResult> } }).builtinAssistant
  if (!api?.readLocalConfigTxt) return { ok: false, error: '当前环境不支持读取本地配置' }
  try {
    return await api.readLocalConfigTxt()
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export async function saveLocalConfigTxt(payload: {
  nonGuiProfiles: BuiltinModelProfileRow[]
  guiProfiles: BuiltinModelProfileRow[]
}): Promise<{ ok: boolean; path?: string; error?: string }> {
  const api = (window as unknown as {
    builtinAssistant?: {
      saveLocalConfigTxt: (p: {
        nonGuiProfiles?: BuiltinModelProfileRow[]
        guiProfiles?: BuiltinModelProfileRow[]
      }) => Promise<{ ok: boolean; path?: string; error?: string }>
    }
  }).builtinAssistant
  if (!api?.saveLocalConfigTxt) return { ok: false, error: '当前环境不支持保存本地配置' }
  try {
    return await api.saveLocalConfigTxt(payload)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export type WeixinLoginQrResult =
  | { ok: true; qrcodeTicket: string; payload: string; baseUrl: string }
  | { ok: false; error: string }

export async function getWeixinLoginQr(input?: { baseUrl?: string; botType?: string; skRouteTag?: string }): Promise<WeixinLoginQrResult> {
  const api = (window as unknown as {
    builtinAssistant?: {
      weixinGetQr: (i?: { baseUrl?: string; botType?: string; skRouteTag?: string }) => Promise<WeixinLoginQrResult>
    }
  }).builtinAssistant
  if (!api?.weixinGetQr) return { ok: false, error: '当前环境不支持微信扫码登录' }
  try {
    return await api.weixinGetQr(input)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export type WeixinLoginStatusResult =
  | {
      ok: true
      status: string
      botToken?: string
      baseUrl?: string
      accountId?: string
      userId?: string
    }
  | { ok: false; error: string }

export async function pollWeixinLoginStatus(input: {
  baseUrl?: string
  qrcodeTicket?: string
  skRouteTag?: string
}): Promise<WeixinLoginStatusResult> {
  const api = (window as unknown as {
    builtinAssistant?: {
      weixinPollQrStatus: (i: { baseUrl?: string; qrcodeTicket?: string; skRouteTag?: string }) => Promise<WeixinLoginStatusResult>
    }
  }).builtinAssistant
  if (!api?.weixinPollQrStatus) return { ok: false, error: '当前环境不支持微信扫码状态查询' }
  try {
    return await api.weixinPollQrStatus(input)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

export type ImLocalHistoryResult =
  | {
      ok: true
      channel: 'qq' | 'weixin'
      messages: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number }>
    }
  | { ok: false; error: string }

export async function getImLocalHistory(input: { channel: 'qq' | 'weixin'; limit?: number }): Promise<ImLocalHistoryResult> {
  const api = (window as unknown as {
    builtinAssistant?: {
      getImLocalHistory: (i: { channel: 'qq' | 'weixin'; limit?: number }) => Promise<ImLocalHistoryResult>
    }
  }).builtinAssistant
  if (!api?.getImLocalHistory) return { ok: false, error: '当前环境不支持读取 IM 本地历史' }
  try {
    return await api.getImLocalHistory(input)
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}
