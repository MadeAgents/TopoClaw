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

/**
 * 本地存储 - IMEI 与服务器配置
 */
import { COLORCLAW_SERVICE_BASE_URL } from '../config/colorClawService'

const KEY_IMEI = 'imei'
const KEY_DEVICE_ID = 'device_id'
const KEY_SERVER_URL = 'server_url'
const KEY_CUSTOMER_SERVICE_URL = 'customer_service_url'
const KEY_CUSTOMER_SERVICE_DOMAIN_MODE = 'customer_service_domain_mode'
const KEY_CUSTOMER_SERVICE_EXTERNAL_URL = 'customer_service_external_url'
const KEY_CHAT_ASSISTANT_URL = 'chat_assistant_url'
const KEY_SKILL_COMMUNITY_URL = 'skill_community_url'
const KEY_BOUND = 'bound'
const KEY_AUTO_EXECUTE_CODE = 'auto_execute_code'
const KEY_SCHEDULE_JOB_BASE_URL = 'schedule_job_base_url'
const KEY_PUBLIC_HUB_MIRROR = 'public_hub_mirror'
const IMEI_MIN_LEN = 6
const IMEI_MAX_LEN = 64
const IMEI_ALLOWED_RE = /^[A-Za-z0-9_-]+$/

function ensureTrailingSlash(url: string): string {
  return url.endsWith('/') ? url : `${url}/`
}

function joinBasePath(base: string, path: string): string {
  return ensureTrailingSlash(base) + path.replace(/^\/+/, '')
}

const ENV_BASE_URL = (import.meta.env.VITE_MOBILE_AGENT_BASE_URL as string | undefined)?.trim() || ''
const SAFE_BASE_URL = ensureTrailingSlash(ENV_BASE_URL || 'https://example.invalid/')

const ENV_CUSTOMER_URL = (import.meta.env.VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL as string | undefined)?.trim() || ''
const ENV_CHAT_ASSISTANT_URL = (import.meta.env.VITE_MOBILE_AGENT_CHAT_ASSISTANT_URL as string | undefined)?.trim() || ''
const ENV_SKILL_COMMUNITY_URL = (import.meta.env.VITE_MOBILE_AGENT_SKILL_COMMUNITY_URL as string | undefined)?.trim() || ''

export const DEFAULT_SERVER_URL = SAFE_BASE_URL
export const DEFAULT_CUSTOMER_SERVICE_URL = ensureTrailingSlash(
  ENV_CUSTOMER_URL || joinBasePath(SAFE_BASE_URL, 'v4/')
)
export const DEFAULT_CHAT_ASSISTANT_URL = ensureTrailingSlash(
  ENV_CHAT_ASSISTANT_URL || joinBasePath(SAFE_BASE_URL, 'v10/')
)
export const DEFAULT_SKILL_COMMUNITY_URL = ensureTrailingSlash(
  ENV_SKILL_COMMUNITY_URL || joinBasePath(SAFE_BASE_URL, 'v9/')
)

export type CustomerServiceDomainMode = 'external' | 'internal'

/**
 * 兼容 TopoMobile 的标识格式：
 * - ANDROID_ID（常见 16 位十六进制）
 * - UUID（36 位，含连字符）
 * 同时允许历史数据在过渡期使用字母/数字/_/-，并限制长度。
 */
export function normalizeImei(input: string | null | undefined): string | null {
  const trimmed = (input ?? '').trim()
  if (!trimmed) return null
  if (trimmed.length < IMEI_MIN_LEN || trimmed.length > IMEI_MAX_LEN) return null
  if (!IMEI_ALLOWED_RE.test(trimmed)) return null
  return trimmed
}

export function isValidImei(input: string | null | undefined): boolean {
  return normalizeImei(input) != null
}

export function getImei(): string | null {
  return localStorage.getItem(KEY_IMEI)
}

export function getValidatedImei(): string | null {
  return normalizeImei(getImei())
}

export function setImei(imei: string): void {
  localStorage.setItem(KEY_IMEI, imei.trim())
  localStorage.setItem(KEY_BOUND, 'true')
}

/** PC 端设备 ID，用于长连接 WebSocket 的 device_id，首次生成后持久化 */
export function getDeviceId(): string {
  let id = localStorage.getItem(KEY_DEVICE_ID)
  if (!id) {
    id = 'pc_' + crypto.randomUUID()
    localStorage.setItem(KEY_DEVICE_ID, id)
  }
  return id
}

export function getServerUrl(): string {
  return localStorage.getItem(KEY_SERVER_URL) || DEFAULT_SERVER_URL
}

export function setServerUrl(url: string): void {
  localStorage.setItem(KEY_SERVER_URL, url.endsWith('/') ? url : `${url}/`)
}

/** 人工客服/跨设备服务地址 */
export function getCustomerServiceUrl(): string {
  return localStorage.getItem(KEY_CUSTOMER_SERVICE_URL) || DEFAULT_CUSTOMER_SERVICE_URL
}

/** 跨设备服务地址来源模式：外部域名 / 应用内域名 */
export function getCustomerServiceDomainMode(): CustomerServiceDomainMode {
  const raw = localStorage.getItem(KEY_CUSTOMER_SERVICE_DOMAIN_MODE)
  return raw === 'internal' ? 'internal' : 'external'
}

export function setCustomerServiceDomainMode(mode: CustomerServiceDomainMode): void {
  localStorage.setItem(KEY_CUSTOMER_SERVICE_DOMAIN_MODE, mode)
}

/** 外部域名模式下独立保存的地址草稿，避免被内部域名覆盖 */
export function getCustomerServiceExternalUrl(): string {
  return localStorage.getItem(KEY_CUSTOMER_SERVICE_EXTERNAL_URL) || DEFAULT_CUSTOMER_SERVICE_URL
}

export function setCustomerServiceExternalUrl(url: string): void {
  const trimmed = url.trim()
  if (!trimmed) {
    localStorage.removeItem(KEY_CUSTOMER_SERVICE_EXTERNAL_URL)
    return
  }
  localStorage.setItem(KEY_CUSTOMER_SERVICE_EXTERNAL_URL, trimmed.endsWith('/') ? trimmed : `${trimmed}/`)
}

export function setCustomerServiceUrl(url: string): void {
  const trimmed = url.trim()
  if (!trimmed) {
    localStorage.removeItem(KEY_CUSTOMER_SERVICE_URL)
    return
  }
  localStorage.setItem(KEY_CUSTOMER_SERVICE_URL, trimmed.endsWith('/') ? trimmed : `${trimmed}/`)
}

/** 聊天小助手服务地址 */
export function getChatAssistantUrl(): string {
  return localStorage.getItem(KEY_CHAT_ASSISTANT_URL) || DEFAULT_CHAT_ASSISTANT_URL
}

export function setChatAssistantUrl(url: string): void {
  const trimmed = url.trim()
  if (!trimmed) {
    localStorage.removeItem(KEY_CHAT_ASSISTANT_URL)
    return
  }
  localStorage.setItem(KEY_CHAT_ASSISTANT_URL, trimmed.endsWith('/') ? trimmed : `${trimmed}/`)
}

/** 定时任务（/cron/jobs）根地址；未设置时与本地 Skills 服务同源（见 colorClawService） */
export function getScheduleJobBaseUrl(): string {
  const u = localStorage.getItem(KEY_SCHEDULE_JOB_BASE_URL)
  if (u && u.trim()) {
    return u.endsWith('/') ? u : `${u}/`
  }
  const b = COLORCLAW_SERVICE_BASE_URL.trim()
  return b.endsWith('/') ? b : `${b}/`
}

export function setScheduleJobBaseUrl(url: string): void {
  const trimmed = url.trim()
  if (!trimmed) {
    localStorage.removeItem(KEY_SCHEDULE_JOB_BASE_URL)
    return
  }
  localStorage.setItem(KEY_SCHEDULE_JOB_BASE_URL, trimmed.endsWith('/') ? trimmed : `${trimmed}/`)
}
/** 技能社区服务地址 */
export function getSkillCommunityUrl(): string {
  return localStorage.getItem(KEY_SKILL_COMMUNITY_URL) || DEFAULT_SKILL_COMMUNITY_URL
}

export function setSkillCommunityUrl(url: string): void {
  const trimmed = url.trim()
  if (!trimmed) {
    localStorage.removeItem(KEY_SKILL_COMMUNITY_URL)
    return
  }
  localStorage.setItem(KEY_SKILL_COMMUNITY_URL, trimmed.endsWith('/') ? trimmed : `${trimmed}/`)
}

export function isBound(): boolean {
  return getValidatedImei() != null
}

export function logout(): void {
  localStorage.removeItem(KEY_IMEI)
  localStorage.removeItem(KEY_BOUND)
}

/** 是否自动执行模型生成的代码（默认关闭） */
export function getAutoExecuteCode(): boolean {
  try {
    return localStorage.getItem(KEY_AUTO_EXECUTE_CODE) === 'true'
  } catch {
    return false
  }
}

export function setAutoExecuteCode(v: boolean): void {
  localStorage.setItem(KEY_AUTO_EXECUTE_CODE, String(v))
}

/** 技能社区 PublicHub 目录镜像（与本地 Skills 服务无关） */
export type PublicHubMirrorId = 'clawhub' | 'skillhub'

export function getPublicHubMirror(): PublicHubMirrorId {
  const v = localStorage.getItem(KEY_PUBLIC_HUB_MIRROR)
  return v === 'clawhub' ? 'clawhub' : 'skillhub'
}

export function setPublicHubMirror(id: PublicHubMirrorId): void {
  localStorage.setItem(KEY_PUBLIC_HUB_MIRROR, id)
}
