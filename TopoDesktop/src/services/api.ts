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
 * API 服务 - 与 apk5 云侧接口对齐
 * 聊天小助手已迁移为 WebSocket，不再使用 HTTP POST /chat、/chat/stream
 */
import axios, { AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import {
  DEFAULT_CHAT_ASSISTANT_URL,
  DEFAULT_SERVER_URL,
  DEFAULT_SKILL_COMMUNITY_URL,
  getChatAssistantUrl,
  getPublicHubMirror, 
  type PublicHubMirrorId,
  getCustomerServiceUrl,
  getServerUrl,
  getSkillCommunityUrl,
  normalizeImei,
} from './storage'
import { sendChatViaWebSocket } from './chatWebSocket'
import { perfLogEnd, perfTimeAsync } from '../utils/perfLog'

/** 聊天小助手默认服务地址 */
export const CHAT_ASSISTANT_BASE_URL = DEFAULT_CHAT_ASSISTANT_URL

export { COLORCLAW_SERVICE_BASE_URL } from '../config/colorClawService'

/** 获取聊天小助手服务地址（用户可自定义，否则用默认） */
export function getChatAssistantBaseUrl(): string {
  return getChatAssistantUrl()
}

/** 获取人工客服/跨设备服务地址（用户可自定义，否则用默认） */
export function getCustomerServiceBaseUrl(): string {
  return getCustomerServiceUrl()
}

/** 获取技能社区服务地址（用户可自定义，否则用默认） */
export function getSkillCommunityBaseUrl(): string {
  return getSkillCommunityUrl()
}

export interface ChatResponse {
  message?: string
  action?: string
  action_type?: string
  reason?: string
  thought?: string
  app_name?: string
  text?: string
  params?: string
  x?: number
  y?: number
  click?: number[]
  swipe?: number[]
  type?: unknown[]
  long_press?: number[]
  drag?: number[]
}

export interface ChatRequestParams {
  uuid: string
  query: string
  images: string[]
  imei: string
  /** 临时强调的技能名称列表（本轮生效） */
  focusSkills?: string[]
  agentId?: string
  userResponse?: string
  chatSummary?: string
  packageName?: string
  className?: string
  outputLanguage?: string
}

export interface Friend {
  imei: string
  nickname?: string
  avatar?: string
  status: string
  addedAt: number
}

/** 群组内小助手的配置（供陌生成员通过 displayId 建立与助手后端的联系） */
export interface GroupAssistantConfig {
  baseUrl?: string
  name?: string
  creator_imei?: string
  creator_nickname?: string
  capabilities?: string[]
  intro?: string
  avatar?: string
  multiSession?: boolean
  displayId?: string
  /** 群主为该助手在当前群设置的角色提示词 */
  rolePrompt?: string
  /** 单助手禁言：开启后该助手在群内不再回复 */
  assistantMuted?: boolean
}

export interface GroupInfo {
  group_id: string
  name: string
  creator_imei: string
  members: string[]
  /** 服务端默认群标记 */
  is_default_group?: boolean
  /** 默认群管理助手 */
  group_manager_assistant_id?: string
  /** 编排模式：开启后群消息由编排流程接管（预留） */
  workflow_mode?: boolean
  /** 自由发言：开启后群内新消息会广播给所有助手 */
  free_discovery?: boolean
  /** 助手禁言：开启后助手不再发送群消息 */
  assistant_muted?: boolean
  /** 成员拉入关系（member_imei -> operator_imei） */
  member_added_by?: Record<string, string>
  created_at: string
  assistant_enabled: boolean
  /** 群组内小助手 ID 列表（后端可能返回） */
  assistants?: string[]
  /** 助手拉入关系（assistant_id -> operator_imei） */
  assistant_added_by?: Record<string, string>
  /** 自定义小助手配置（assistant_id -> config），供群成员与陌生助手建立联系 */
  assistant_configs?: Record<string, GroupAssistantConfig>
}

export interface GroupWorkflowCloudPayload {
  schemaVersion?: number
  meta?: Record<string, unknown>
  graph?: {
    nodes?: unknown[]
    edges?: unknown[]
  }
  ui?: Record<string, unknown>
  extras?: Record<string, unknown>
}

export interface GroupWorkflowFetchResponse {
  success: boolean
  workflow: GroupWorkflowCloudPayload | null
  version: number
  updatedAt?: string | null
  updatedBy?: string | null
}

export interface GroupWorkflowSaveResponse {
  success: boolean
  message?: string
  conflict?: boolean
  version?: number
  currentVersion?: number
  updatedAt?: string
  updatedBy?: string
  workflow?: GroupWorkflowCloudPayload
}

export interface FriendListResponse {
  success: boolean
  friends?: Friend[]
  message?: string
}

export interface GroupListResponse {
  success: boolean
  groups?: GroupInfo[]
  message?: string
}

export interface OnlineUsersResponse {
  success: boolean
  count?: number
  users?: string[]
}

export interface CustomerServiceUserStatus {
  success: boolean
  imei: string
  is_online: boolean
  offline_message_count?: number
}

export interface UserSettings {
  all_agents_reply?: boolean
  /** 数字分身全局默认开关 */
  digital_clone_enabled?: boolean
  /** 好友级别覆盖（friend imei -> enabled） */
  digital_clone_friend_overrides?: Record<string, boolean>
}

export interface ConversationSummarySyncEntry {
  id: string
  schema?: 'v1'
  userImei?: string
  scopeType: 'friend' | 'group'
  scopeId: string
  scopeName?: string
  createdAt: number
  messageStartTs?: number
  messageEndTs?: number
  messageCount?: number
  roundCount?: number
  summary: string
}

let apiClient: AxiosInstance | null = null
let customerApiClient: AxiosInstance | null = null

function normalizeImeiOrNull(imei: string): string | null {
  const normalized = normalizeImei(imei)
  if (!normalized) {
    console.warn('[API] 非法 imei，已拦截请求')
    return null
  }
  return normalized
}

function addPerfInterceptors(client: AxiosInstance, label: string) {
  client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    ;(config as InternalAxiosRequestConfig & { _perfStart?: number })._perfStart = Date.now()
    return config
  })
  client.interceptors.response.use(
    (res) => {
      const start = (res.config as InternalAxiosRequestConfig & { _perfStart?: number })._perfStart
      if (start) perfLogEnd(`HTTP ${label}`, start, { url: res.config.url, method: res.config.method })
      return res
    },
    (err) => {
      const start = err.config?._perfStart as number | undefined
      if (start) perfLogEnd(`HTTP ${label} 失败`, start, { url: err.config?.url, error: err.message })
      return Promise.reject(err)
    }
  )
}

export function initApi(baseUrl: string = DEFAULT_SERVER_URL, customerServiceBaseUrl: string = getCustomerServiceBaseUrl()) {
  const url = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const customerUrl = customerServiceBaseUrl.endsWith('/') ? customerServiceBaseUrl : `${customerServiceBaseUrl}/`
  apiClient = axios.create({
    baseURL: url,
    timeout: 120000,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
  customerApiClient = axios.create({
    baseURL: customerUrl,
    timeout: 30000,
    headers: { 'Content-Type': 'application/json' },
  })
  addPerfInterceptors(apiClient, 'api')
  addPerfInterceptors(customerApiClient, 'customer')
  return apiClient
}

export function getApiClient(): AxiosInstance {
  if (!apiClient) initApi(getServerUrl(), getCustomerServiceBaseUrl())
  return apiClient!
}

export function getCustomerApiClient(): AxiosInstance {
  if (!customerApiClient) initApi(getServerUrl(), getCustomerServiceBaseUrl())
  return customerApiClient!
}

/** 云侧 GET /api/version/check-desktop 响应（TopoDesktop） */
export interface DesktopVersionCheckResponse {
  success: boolean
  current_version?: string | null
  latest_version: string
  min_supported_version: string
  update_url: string
  has_update: boolean
  force_update: boolean
  update_message: string
  last_updated?: string
  platform?: string | null
}

function guessDesktopPlatform(): string {
  if (typeof navigator === 'undefined') return 'win32'
  const ua = navigator.userAgent || ''
  const p = navigator.platform || ''
  if (/Mac|iPhone|iPod|iPad/i.test(p) || /Mac OS/i.test(ua)) return 'darwin'
  return 'win32'
}

/** 请求 PC 端版本检查（需已 initApi） */
export async function fetchDesktopVersionCheck(
  currentVersion: string
): Promise<DesktopVersionCheckResponse | null> {
  try {
    const client = getCustomerApiClient()
    const res = await client.get<DesktopVersionCheckResponse>('api/version/check-desktop', {
      params: { current_version: currentVersion, platform: guessDesktopPlatform() },
    })
    if (res.data?.success) return res.data
  } catch (e) {
    console.warn('[TopoDesktop] 版本检查失败', e)
  }
  return null
}

export async function sendChatMessage(
  params: ChatRequestParams,
  baseUrl?: string
): Promise<ChatResponse> {
  if (baseUrl) {
    return sendChatAssistantMessage(params, baseUrl)
  }
  const client = getApiClient()
  const formData = new URLSearchParams()
  formData.append('uuid', params.uuid)
  formData.append('query', params.query)
  formData.append('images[0]', params.images[0] ?? '')
  if (params.images[1]) formData.append('images[1]', params.images[1])
  if (params.imei) {
    const normalizedImei = normalizeImeiOrNull(params.imei)
    if (normalizedImei) formData.append('imei', normalizedImei)
  }
  if (params.userResponse) formData.append('user_response', params.userResponse)
  if (params.chatSummary) formData.append('chat_summary', params.chatSummary)
  if (params.packageName) formData.append('package_name', params.packageName)
  if (params.className) formData.append('class_name', params.className)
  if (params.outputLanguage) formData.append('output_language', params.outputLanguage)

  const res = await client.post<ChatResponse>('upload', formData, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
  return res.data
}

/** 聊天小助手非流式：通过 WebSocket 获取完整回复（已替代 HTTP POST /chat） */
export async function sendChatAssistantMessage(
  params: ChatRequestParams,
  baseUrl: string
): Promise<ChatResponse> {
  const base = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const images = params.images?.filter((b64) => b64 && b64.length > 100) ?? []
  const { fullText } = await sendChatViaWebSocket(
    base,
    {
      thread_id: params.uuid,
      message: params.query || (images.length ? '[图片]' : ''),
      images: images.length ? images : undefined,
      focus_skills: params.focusSkills?.map((x) => x.trim()).filter(Boolean) ?? undefined,
      agent_id: params.agentId?.trim() || undefined,
    },
    { onDelta: () => {} },
    AbortSignal.timeout(900000)
  )
  return {
    params: fullText,
    text: fullText,
    message: fullText,
  }
}

/** 聊天小助手流式：通过 WebSocket 逐 token 返回，支持 tool_call、skill_generated、need_execution 事件 */
export async function sendChatAssistantMessageStream(
  params: ChatRequestParams,
  baseUrl: string,
  onDelta: (delta: string) => void,
  onReasoning?: (reasoning: string) => void,
  onMedia?: (media: { fileBase64: string; fileName?: string; content?: string; messageType?: 'image' | 'file' }) => void,
  onToolCall?: (toolName: string) => void,
  onSkillGenerated?: (skill: Skill) => void,
  onNeedExecution?: (chatSummary: string) => void,
  agentId?: string
): Promise<{ fullText: string; needExecutionFired: boolean }> {
  const base = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const images = params.images?.filter((b64) => b64 && b64.length > 100) ?? []
  return sendChatViaWebSocket(
    base,
    {
      thread_id: params.uuid,
      message: params.query || (images.length ? '[图片]' : ''),
      images: images.length ? images : undefined,
      focus_skills: params.focusSkills?.map((x) => x.trim()).filter(Boolean) ?? undefined,
      agent_id: agentId?.trim() || params.agentId?.trim() || undefined,
    },
    {
      onDelta,
      onReasoning,
      onMedia,
      onToolCall,
      onSkillGenerated,
      onNeedExecution,
    },
    AbortSignal.timeout(900000)
  )
}

/** 自定义小助手项（端云同步） */
export interface CustomAssistantApiItem {
  id: string
  name: string
  intro?: string
  baseUrl: string
  capabilities?: string[]
  avatar?: string
  displayId?: string
  creator_imei?: string
  creator_avatar?: string
  systemPrompt?: string
  skillsInclude?: string[]
  skillsExclude?: string[]
  /** 是否支持多 session */
  multiSessionEnabled?: boolean
  /** 与本地 CustomAssistant.assistantOrigin 一致，端云同步可选字段 */
  assistantOrigin?: 'created' | 'added'
}

/** 获取用户的自定义小助手列表 */
export async function getCustomAssistantsFromCloud(
  imei: string,
  /** 可选：防止缓存，删除/刷新后传入 Date.now() 确保拉取最新数据 */
  cacheBust?: number,
  options?: { timeoutMs?: number; throwOnError?: boolean }
): Promise<CustomAssistantApiItem[]> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return []
    const client = getCustomerApiClient()
    const params: Record<string, string | number> = { imei: normalizedImei }
    if (cacheBust != null) params._t = cacheBust
    const res = await client.get<{ success: boolean; assistants?: CustomAssistantApiItem[] }>(
      'api/custom-assistants',
      { params, timeout: options?.timeoutMs }
    )
    const raw = res.data?.success && res.data.assistants ? res.data.assistants : []
    return raw.map((item) => {
      const a = item as unknown as Record<string, unknown>
      const normalizedDisplayId =
        typeof a.displayId === 'string' && a.displayId.trim()
          ? a.displayId.trim()
          : typeof a.display_id === 'string' && a.display_id.trim()
            ? a.display_id.trim()
            : undefined
      const normalizedCreatorImei =
        typeof a.creator_imei === 'string' && a.creator_imei.trim()
          ? a.creator_imei.trim()
          : typeof a.creatorImei === 'string' && a.creatorImei.trim()
            ? a.creatorImei.trim()
            : undefined
      const normalizedCreatorAvatar =
        typeof a.creator_avatar === 'string'
          ? a.creator_avatar
          : typeof a.creatorAvatar === 'string'
            ? a.creatorAvatar
            : undefined
      return {
        ...item,
        displayId: normalizedDisplayId,
        creator_imei: normalizedCreatorImei,
        creator_avatar: normalizedCreatorAvatar,
        systemPrompt: typeof a.systemPrompt === 'string'
          ? a.systemPrompt
          : typeof a.system_prompt === 'string'
            ? a.system_prompt
            : undefined,
        skillsInclude: Array.isArray(a.skillsInclude)
          ? (a.skillsInclude as unknown[]).map((x) => String(x).trim()).filter(Boolean)
          : Array.isArray(a.skills_include)
            ? (a.skills_include as unknown[]).map((x) => String(x).trim()).filter(Boolean)
            : undefined,
        skillsExclude: Array.isArray(a.skillsExclude)
          ? (a.skillsExclude as unknown[]).map((x) => String(x).trim()).filter(Boolean)
          : Array.isArray(a.skills_exclude)
            ? (a.skills_exclude as unknown[]).map((x) => String(x).trim()).filter(Boolean)
            : undefined,
        multiSessionEnabled: a.multiSessionEnabled ?? a.multi_session_enabled ?? undefined,
        assistantOrigin:
          a.assistantOrigin === 'created' || a.assistantOrigin === 'added'
            ? a.assistantOrigin
            : a.assistant_origin === 'created' || a.assistant_origin === 'added'
              ? a.assistant_origin
              : undefined,
      } as CustomAssistantApiItem
    })
  } catch (e) {
    if (options?.throwOnError) {
      throw e
    }
    return []
  }
}

/** 同步用户的自定义小助手列表到云端 */
export async function syncCustomAssistantsToCloud(
  imei: string,
  assistants: CustomAssistantApiItem[]
): Promise<boolean> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return false
    const client = getCustomerApiClient()
    const res = await client.post<{ success: boolean }>('api/custom-assistants', {
      imei: normalizedImei,
      assistants,
      client_type: 'pc',
    })
    return res.data?.success ?? false
  } catch {
    return false
  }
}

/** 一键适配「新助手id」体系（displayId 优先） */
export async function adaptAssistantIdsForUser(
  imei: string
): Promise<{
  success: boolean
  message?: string
  assistant_stats?: { assistants_total: number; assistants_updated: number }
  group_stats?: { groups_total: number; groups_updated: number; configs_updated: number }
}> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
    const client = getCustomerApiClient()
    const res = await client.post<{
      success: boolean
      message?: string
      assistant_stats?: { assistants_total: number; assistants_updated: number }
      group_stats?: { groups_total: number; groups_updated: number; configs_updated: number }
    }>('api/custom-assistants/adapt-id', { imei: normalizedImei })
    return res.data ?? { success: false }
  } catch {
    return { success: false, message: '请求失败' }
  }
}

// ------------ 小助手广场 API ------------

export interface PlazaAssistantItem {
  id: string
  /** 展示用创建者文案（如 短 imei · 昵称），由服务端根据用户资料生成 */
  creator_imei?: string
  /** 创建者头像（base64 或 data URL，与资料接口一致） */
  creator_avatar?: string
  /** 当前请求的 imei 是否为该条目的创建者（需列表请求携带 imei） */
  is_creator?: boolean
  name: string
  intro?: string
  baseUrl: string
  capabilities?: string[]
  avatar?: string
  multiSessionEnabled?: boolean
  created_at?: string
  likes_count?: number
  liked_by_me?: boolean
}

/** 分页获取广场小助手列表 */
export async function getPlazaAssistants(
  params?: { page?: number; limit?: number; imei?: string; sort?: 'latest' | 'hot' },
  signal?: AbortSignal
): Promise<{ assistants: PlazaAssistantItem[]; has_more: boolean }> {
  const client = getCustomerApiClient()
  const normalizedImei = params?.imei ? normalizeImeiOrNull(params.imei) : null
  const res = await client.get<{ success: boolean; assistants?: PlazaAssistantItem[]; has_more?: boolean }>(
    'api/plaza-assistants',
    {
      params: {
        page: params?.page ?? 1,
        limit: params?.limit ?? 50,
        ...(normalizedImei ? { imei: normalizedImei } : {}),
        ...(params?.sort ? { sort: params.sort } : {}),
      },
      signal,
    }
  )
  return {
    assistants: res.data?.assistants ?? [],
    has_more: res.data?.has_more ?? false,
  }
}

/** 切换点赞（再点取消） */
export async function togglePlazaLike(
  imei: string,
  plazaId: string
): Promise<{ success: boolean; likes_count: number; liked_by_me: boolean }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, likes_count: 0, liked_by_me: false }
  const client = getCustomerApiClient()
  const res = await client.post<{
    success: boolean
    likes_count?: number
    liked_by_me?: boolean
  }>(`api/plaza-assistants/${encodeURIComponent(plazaId)}/like`, { imei: normalizedImei })
  const d = res.data
  return {
    success: d?.success ?? false,
    likes_count: d?.likes_count ?? 0,
    liked_by_me: d?.liked_by_me ?? false,
  }
}

/** 将小助手上架到广场 */
export async function submitToPlaza(
  imei: string,
  assistant: CustomAssistantApiItem
): Promise<{ success: boolean; assistant?: PlazaAssistantItem }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; assistant?: PlazaAssistantItem }>(
    'api/plaza-assistants',
    { imei: normalizedImei, assistant }
  )
  return res.data ?? { success: false }
}

/** 将广场小助手添加到当前用户的自定义列表 */
export async function addPlazaAssistantToMine(
  imei: string,
  plazaId: string
): Promise<{ success: boolean; assistant?: CustomAssistantApiItem }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; assistant?: CustomAssistantApiItem }>(
    `api/plaza-assistants/${encodeURIComponent(plazaId)}/add`,
    { imei: normalizedImei }
  )
  return res.data ?? { success: false }
}

/** 创建者将广场中的小助手下架 */
export async function removePlazaAssistant(imei: string, plazaId: string): Promise<{ success: boolean }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean }>(
    `api/plaza-assistants/${encodeURIComponent(plazaId)}/remove`,
    { imei: normalizedImei }
  )
  return res.data ?? { success: false }
}

/** 创建者更新广场中的小助手资料（名称、头像、介绍、域名） */
export async function updatePlazaAssistant(
  imei: string,
  plazaId: string,
  updates: { name?: string; intro?: string; baseUrl?: string; avatar?: string }
): Promise<{ success: boolean; assistant?: PlazaAssistantItem }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const res = await client.patch<{ success: boolean; assistant?: PlazaAssistantItem }>(
    `api/plaza-assistants/${encodeURIComponent(plazaId)}`,
    { imei: normalizedImei, ...updates }
  )
  return res.data ?? { success: false }
}

// ------------ 技能广场 API ------------

export interface PlazaSkillItem {
  id: string
  creator_imei?: string
  creator_avatar?: string
  is_creator?: boolean
  title: string
  originalPurpose?: string
  steps?: string[]
  executionPlatform?: Skill['executionPlatform']
  author?: string
  tags?: string[]
  created_at?: string
  package_base64?: string
  package_file_name?: string
}

export async function getPlazaSkills(
  params?: { page?: number; limit?: number; imei?: string; query?: string },
  signal?: AbortSignal
): Promise<{ skills: PlazaSkillItem[]; has_more: boolean }> {
  const client = getCustomerApiClient()
  const normalizedImei = params?.imei ? normalizeImeiOrNull(params.imei) : null
  const res = await client.get<{ success: boolean; skills?: PlazaSkillItem[]; has_more?: boolean }>(
    'api/plaza-skills',
    {
      params: {
        page: params?.page ?? 1,
        limit: params?.limit ?? 50,
        ...(normalizedImei ? { imei: normalizedImei } : {}),
        ...(params?.query ? { query: params.query } : {}),
      },
      signal,
    }
  )
  return {
    skills: res.data?.skills ?? [],
    has_more: res.data?.has_more ?? false,
  }
}

export async function submitSkillToPlaza(
  imei: string,
  skill: Skill,
  packagePayload: { packageBase64: string; packageFileName?: string }
): Promise<{ success: boolean; skill?: PlazaSkillItem }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; skill?: PlazaSkillItem }>(
    'api/plaza-skills',
    {
      imei: normalizedImei,
      skill: {
        id: skill.id,
        title: skill.title,
        originalPurpose: skill.originalPurpose,
        steps: skill.steps,
        executionPlatform: skill.executionPlatform,
        author: skill.author,
        tags: skill.tags,
        package_base64: packagePayload.packageBase64,
        package_file_name: packagePayload.packageFileName,
      },
    }
  )
  return res.data ?? { success: false }
}

/** 追加自定义小助手聊天消息到 customer_service，实现手机/PC 跨设备同步，支持图片 base64，多 session 时传入 session_id */
export async function appendCustomAssistantChat(
  imei: string,
  assistantId: string,
  userContent: string,
  assistantContent: string,
  assistantName: string = '小助手',
  options?: { file_base64?: string; file_name?: string; session_id?: string }
): Promise<{ success: boolean; message_id?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const body: Record<string, string | undefined> = {
    imei: normalizedImei,
    assistant_id: assistantId,
    user_content: userContent,
    assistant_content: assistantContent,
    assistant_name: assistantName,
  }
  if (options?.file_base64) body.file_base64 = options.file_base64
  if (options?.file_name) body.file_name = options.file_name
  if (options?.session_id) body.session_id = options.session_id
  const res = await client.post<{ success: boolean; message_id?: string }>(
    'api/custom-assistant-chat/append',
    body
  )
  return res.data ?? { success: false }
}

const CHAT_HISTORY_FETCH_TIMEOUT_MS = 12_000

/** 聊天小助手历史：GET /chat/history，用于跨设备加载（带超时，避免本机助手未就绪时长时间挂起） */
export async function getChatAssistantHistory(
  baseUrl: string,
  threadId: string,
  limit = 100,
  signal?: AbortSignal
): Promise<{ messages: Array<{ role: string; content: string; order: number }> }> {
  return perfTimeAsync('fetch chat/history', async () => {
    const base = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
    const url = `${base}chat/history?thread_id=${encodeURIComponent(threadId)}&limit=${limit}`
    const timeoutController = new AbortController()
    const timeoutId = window.setTimeout(() => timeoutController.abort(), CHAT_HISTORY_FETCH_TIMEOUT_MS)
    const onParentAbort = () => timeoutController.abort()
    if (signal) {
      if (signal.aborted) timeoutController.abort()
      else signal.addEventListener('abort', onParentAbort, { once: true })
    }
    try {
      const res = await fetch(url, { signal: timeoutController.signal })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = (await res.json()) as { messages?: Array<{ role: string; content: string; order: number }>; thread_id?: string }
      return { messages: data.messages ?? [] }
    } finally {
      window.clearTimeout(timeoutId)
      if (signal) signal.removeEventListener('abort', onParentAbort)
    }
  }, { threadId })
}

export async function getFriends(imei: string, options?: { timeoutMs?: number }): Promise<Friend[]> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return []
  const client = getCustomerApiClient()
  const res = await client.get<FriendListResponse>('api/friends/list', {
    params: { imei: normalizedImei },
    timeout: options?.timeoutMs,
  })
  return res.data?.success && res.data.friends ? res.data.friends : []
}

/** 添加好友（发送好友请求） */
export async function addFriend(
  imei: string,
  targetImei: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string }>(
    'api/friends/add',
    {
      imei: normalizedImei,
      // customer_service 当前 schema 要求 friendImei；保留 targetImei 兼容旧服务实现。
      friendImei: targetImei,
      targetImei,
    }
  )
  return res.data ?? { success: false }
}

export async function getGroups(imei: string, options?: { timeoutMs?: number }): Promise<GroupInfo[]> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return []
  const client = getCustomerApiClient()
  const res = await client.get<GroupListResponse>('api/groups/list', {
    params: { imei: normalizedImei },
    timeout: options?.timeoutMs,
  })
  return res.data?.success && res.data.groups ? res.data.groups : []
}

/** 获取所有在线用户 IMEI 列表（customer_service 视角） */
export async function getOnlineUsers(
  options?: { timeoutMs?: number }
): Promise<string[]> {
  try {
    const client = getCustomerApiClient()
    const res = await client.get<OnlineUsersResponse>('api/customer-service/online-users', {
      timeout: options?.timeoutMs,
    })
    if (res.data?.success !== true) return []
    return Array.isArray(res.data.users) ? res.data.users : []
  } catch {
    return []
  }
}

/** 获取手机 websocket 在线状态（customer_service 视角） */
export async function getMobileUserStatus(
  imei: string,
  options?: { timeoutMs?: number }
): Promise<{ success: boolean; isOnline: boolean; offlineMessageCount: number }> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return { success: false, isOnline: false, offlineMessageCount: 0 }
    const client = getCustomerApiClient()
    const res = await client.get<CustomerServiceUserStatus>(
      `api/customer-service/user-status/${encodeURIComponent(normalizedImei)}`,
      { timeout: options?.timeoutMs }
    )
    const data = res.data
    return {
      success: data?.success === true,
      isOnline: data?.is_online === true,
      offlineMessageCount: Number(data?.offline_message_count ?? 0),
    }
  } catch {
    return { success: false, isOnline: false, offlineMessageCount: 0 }
  }
}

/** 获取用户设置（带默认值） */
export async function getUserSettings(
  imei: string
): Promise<{ success: boolean; settings: UserSettings }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, settings: {} as UserSettings }
  const client = getCustomerApiClient()
  const res = await client.get<{ success: boolean; settings?: UserSettings }>('api/user-settings', {
    params: { imei: normalizedImei },
  })
  return {
    success: res.data?.success ?? false,
    settings: (res.data?.settings ?? {}) as UserSettings,
  }
}

/** 批量更新用户设置（仅后端允许键会生效） */
export async function updateUserSettings(
  imei: string,
  settings: Partial<UserSettings>
): Promise<{ success: boolean; settings: UserSettings }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, settings: {} as UserSettings }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; settings?: UserSettings }>('api/user-settings', {
    imei: normalizedImei,
    settings,
  })
  return {
    success: res.data?.success ?? false,
    settings: (res.data?.settings ?? {}) as UserSettings,
  }
}

/** 会话摘要（好友/群组）双向增量同步 */
export async function syncConversationSummaries(
  imei: string,
  payload?: { entries?: ConversationSummarySyncEntry[]; sinceTs?: number; limit?: number }
): Promise<{
  success: boolean
  accepted: number
  uploaded: number
  entries: ConversationSummarySyncEntry[]
  server_time_ms?: number
}> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return { success: false, accepted: 0, uploaded: 0, entries: [] }
    const client = getCustomerApiClient()
    const uploadCount = payload?.entries?.length ?? 0
    const sinceTs = payload?.sinceTs ?? 0
    if (typeof window !== 'undefined') {
      console.info('[ConversationSummary][API] sync request', {
        imei: normalizedImei ? `${normalizedImei.slice(0, 8)}...` : '(empty)',
        uploadCount,
        sinceTs,
        limit: payload?.limit ?? 2000,
      })
    }
    const res = await client.post<{
      success?: boolean
      accepted?: number
      uploaded?: number
      entries?: ConversationSummarySyncEntry[]
      server_time_ms?: number
    }>('api/conversation-summaries/sync', {
      imei: normalizedImei,
      entries: payload?.entries ?? [],
      since_ts: payload?.sinceTs ?? 0,
      limit: payload?.limit ?? 2000,
    })
    if (typeof window !== 'undefined') {
      console.info('[ConversationSummary][API] sync response', {
        success: res.data?.success ?? false,
        accepted: Number(res.data?.accepted ?? 0),
        uploaded: Number(res.data?.uploaded ?? 0),
        returned: (res.data?.entries ?? []).length,
        serverTime: res.data?.server_time_ms,
      })
    }
    return {
      success: res.data?.success ?? false,
      accepted: Number(res.data?.accepted ?? 0),
      uploaded: Number(res.data?.uploaded ?? 0),
      entries: res.data?.entries ?? [],
      server_time_ms: res.data?.server_time_ms,
    }
  } catch (e) {
    if (typeof window !== 'undefined') {
      console.warn('[ConversationSummary][API] sync failed', e)
    }
    return { success: false, accepted: 0, uploaded: 0, entries: [] }
  }
}

/** 创建群组（与移动端 / 服务端 CreateGroupRequest 一致：memberImeis + assistantEnabled） */
export async function createGroup(
  imei: string,
  name: string,
  memberImeis: string[],
  assistantEnabled: boolean = true
): Promise<{ success: boolean; message?: string; group?: GroupInfo; groupId?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{
    success: boolean
    message?: string
    group?: GroupInfo
    groupId?: string
  }>('api/groups/create', {
    imei: normalizedImei,
    name,
    memberImeis,
    assistantEnabled,
  })
  return res.data ?? { success: false }
}

/** 获取单个群组详情 */
export async function getGroup(groupId: string): Promise<GroupInfo | null> {
  try {
    const client = getCustomerApiClient()
    const res = await client.get<{ success: boolean; group?: GroupInfo }>(`api/groups/${groupId}`)
    return res.data?.success && res.data.group ? res.data.group : null
  } catch {
    return null
  }
}

/** 添加群组成员（群成员可操作） */
export async function addGroupMember(
  imei: string,
  groupId: string,
  memberImei: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string }>('api/groups/add-member', {
    imei: normalizedImei,
    groupId,
    memberImei,
  })
  return res.data ?? { success: false }
}

/** 移除群组成员（群主可移除任意成员；非群主仅可移除自己拉入成员） */
export async function removeGroupMember(
  imei: string,
  groupId: string,
  memberImei: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string }>('api/groups/remove-member', {
    imei: normalizedImei,
    groupId,
    memberImei,
  })
  return res.data ?? { success: false }
}

/** 添加群组小助手时的可选配置（自定义小助手需传，供群成员与陌生助手建立联系） */
export interface AddGroupAssistantConfig {
  baseUrl?: string
  name?: string
  capabilities?: string[]
  intro?: string
  avatar?: string
  multiSession?: boolean
  displayId?: string
  rolePrompt?: string
}

/** 添加群组小助手（群成员可操作）。自定义小助手传入 assistantConfig 供群成员同步。 */
export async function addGroupAssistant(
  imei: string,
  groupId: string,
  assistantId: string,
  assistantConfig?: AddGroupAssistantConfig
): Promise<{ success: boolean; message?: string; group?: GroupInfo }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = { imei: normalizedImei, groupId, assistantId }
  if (assistantConfig) {
    if (assistantConfig.baseUrl != null) body.baseUrl = assistantConfig.baseUrl
    if (assistantConfig.name != null) body.name = assistantConfig.name
    if (assistantConfig.capabilities != null) body.capabilities = assistantConfig.capabilities
    if (assistantConfig.intro != null) body.intro = assistantConfig.intro
    if (assistantConfig.avatar != null) body.avatar = assistantConfig.avatar
    if (assistantConfig.multiSession != null) body.multiSession = assistantConfig.multiSession
    if (assistantConfig.displayId != null) body.displayId = assistantConfig.displayId
    if (assistantConfig.rolePrompt != null) body.rolePrompt = assistantConfig.rolePrompt
  }
  const res = await client.post<{ success: boolean; message?: string; group?: GroupInfo }>(
    'api/groups/add-assistant',
    body
  )
  return res.data ?? { success: false }
}

/** 移除群组小助手（群主可移除任意助手；非群主仅可移除自己拉入助手） */
export async function removeGroupAssistant(
  imei: string,
  groupId: string,
  assistantId: string
): Promise<{ success: boolean; message?: string; group?: GroupInfo }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string; group?: GroupInfo }>(
    'api/groups/remove-assistant',
    { imei: normalizedImei, groupId, assistantId }
  )
  return res.data ?? { success: false }
}

/** 成员退出群组（群主不可退出，需解散） */
export async function quitGroup(
  imei: string,
  groupId: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string }>('api/groups/quit', {
    imei: normalizedImei,
    groupId,
  })
  return res.data ?? { success: false }
}

/** 群主解散群组 */
export async function dissolveGroup(
  imei: string,
  groupId: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const res = await client.post<{ success: boolean; message?: string }>('api/groups/dissolve', {
    imei: normalizedImei,
    groupId,
  })
  return res.data ?? { success: false }
}

/** 更新群组中指定小助手配置（仅群主可操作） */
export async function updateGroupAssistantConfig(
  imei: string,
  groupId: string,
  assistantId: string,
  config: {
    capabilities?: string[]
    baseUrl?: string
    name?: string
    intro?: string
    avatar?: string
    multiSession?: boolean
    rolePrompt?: string
    assistantMuted?: boolean
  }
): Promise<{ success: boolean; message?: string; group?: GroupInfo }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) {
    return { success: false, message: '设备标识无效，请重新绑定后重试' }
  }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = { imei: normalizedImei, groupId, assistantId }
  if (config.capabilities != null) body.capabilities = config.capabilities
  if (config.baseUrl != null) body.baseUrl = config.baseUrl
  if (config.name != null) body.name = config.name
  if (config.intro != null) body.intro = config.intro
  if (config.avatar != null) body.avatar = config.avatar
  if (config.multiSession != null) body.multiSession = config.multiSession
  if (config.rolePrompt != null) body.rolePrompt = config.rolePrompt
  if (config.assistantMuted != null) body.assistantMuted = config.assistantMuted
  const res = await client.post<{ success: boolean; message?: string; group?: GroupInfo }>(
    'api/groups/update-assistant-config',
    body
  )
  return res.data ?? { success: false }
}

/** 更新群组通用配置（仅群主可操作） */
export async function updateGroupConfig(
  imei: string,
  groupId: string,
  config: {
    workflowMode?: boolean
    freeDiscovery?: boolean
    assistantMuted?: boolean
  }
): Promise<{ success: boolean; message?: string; group?: GroupInfo }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) {
    return { success: false, message: '设备标识无效，请重新绑定后重试' }
  }
  try {
    const client = getCustomerApiClient()
    const body: Record<string, unknown> = { imei: normalizedImei, groupId }
    if (config.workflowMode != null) body.workflowMode = config.workflowMode
    if (config.freeDiscovery != null) body.freeDiscovery = config.freeDiscovery
    if (config.assistantMuted != null) body.assistantMuted = config.assistantMuted
    const res = await client.post<{ success: boolean; message?: string; group?: GroupInfo }>(
      'api/groups/update-config',
      body
    )
    return res.data ?? { success: false }
  } catch (e) {
    const status = (e as { response?: { status?: number } })?.response?.status
    if (status === 404 || status === 405) {
      return { success: false, message: '当前服务端暂不支持群组配置开关，请先升级服务端版本。' }
    }
    return { success: false, message: '更新群组配置失败，请稍后重试。' }
  }
}

/** 以小助手身份发送群组消息（广播到所有群成员，用于助手回复同步到手机/其他设备） */
export async function sendAssistantGroupMessage(
  imei: string,
  groupId: string,
  content: string,
  sender: string = '小助手',
  assistantId?: string
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = { imei: normalizedImei, groupId, content, sender }
  if (assistantId && assistantId.trim()) body.assistantId = assistantId.trim()
  const res = await client.post<{ success: boolean; message?: string }>(
    'api/groups/send-assistant-message',
    body
  )
  return res.data ?? { success: false }
}

/** 获取群组云端编排发布版（群成员可读） */
export async function getGroupWorkflow(
  imei: string,
  groupId: string
): Promise<GroupWorkflowFetchResponse> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, workflow: null, version: 0 }
  try {
    const client = getCustomerApiClient()
    const res = await client.get<{
      success: boolean
      workflow?: GroupWorkflowCloudPayload | null
      version?: number
      updatedAt?: string | null
      updatedBy?: string | null
    }>(`api/groups/${groupId}/workflow`, {
      params: { imei: normalizedImei },
    })
    return {
      success: res.data?.success ?? false,
      workflow: (res.data?.workflow ?? null) as GroupWorkflowCloudPayload | null,
      version: Number(res.data?.version ?? 0),
      updatedAt: res.data?.updatedAt,
      updatedBy: res.data?.updatedBy,
    }
  } catch {
    return { success: false, workflow: null, version: 0 }
  }
}

/** 保存群组云端编排发布版（带 expectedVersion 乐观锁） */
export async function saveGroupWorkflow(
  imei: string,
  groupId: string,
  workflow: GroupWorkflowCloudPayload,
  expectedVersion?: number | null
): Promise<GroupWorkflowSaveResponse> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  try {
    const client = getCustomerApiClient()
    const body: Record<string, unknown> = {
      imei: normalizedImei,
      groupId,
      workflow,
    }
    if (expectedVersion != null) body.expectedVersion = expectedVersion
    const res = await client.post<GroupWorkflowSaveResponse>('api/groups/workflow/save', body)
    return res.data ?? { success: false }
  } catch (e) {
    const status = (e as { response?: { status?: number; data?: GroupWorkflowSaveResponse } })?.response?.status
    const data = (e as { response?: { data?: GroupWorkflowSaveResponse } })?.response?.data
    if (status === 409 && data) {
      return {
        success: false,
        conflict: true,
        message: data.message || '编排版本冲突，请先刷新',
        currentVersion: data.currentVersion,
        version: data.version,
        updatedAt: data.updatedAt,
        updatedBy: data.updatedBy,
      }
    }
    return { success: false, message: '保存群组编排失败，请稍后重试' }
  }
}

export interface UserProfile {
  imei?: string
  name?: string
  signature?: string
  avatar?: string
  gender?: string
  address?: string
  phone?: string
  birthday?: string
  preferences?: string
  updatedAt?: number
}

export interface UserProfileUpdatePayload {
  name?: string
  signature?: string
  avatar?: string
  gender?: string
  address?: string
  phone?: string
  birthday?: string
  preferences?: string
}

/** 获取用户资料（含头像） */
export async function getProfile(imei: string, options?: { timeoutMs?: number }): Promise<UserProfile | null> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return null
    const client = getCustomerApiClient()
    const res = await client.get<{ success: boolean; profile?: UserProfile }>(
      `api/profile/${encodeURIComponent(normalizedImei)}`,
      {
      timeout: options?.timeoutMs,
      }
    )
    return res.data?.success && res.data.profile ? res.data.profile : null
  } catch {
    return null
  }
}

/** 更新用户资料（与 customer_service /api/profile/{imei} Form 字段对齐） */
export async function updateProfile(
  imei: string,
  updates: UserProfileUpdatePayload
): Promise<UserProfile | null> {
  try {
    const normalizedImei = normalizeImeiOrNull(imei)
    if (!normalizedImei) return null
    const client = getCustomerApiClient()
    const formData = new URLSearchParams()
    if (updates.name != null) formData.append('name', updates.name)
    if (updates.signature != null) formData.append('signature', updates.signature)
    if (updates.avatar != null) formData.append('avatar', updates.avatar)
    if (updates.gender != null) formData.append('gender', updates.gender)
    if (updates.address != null) formData.append('address', updates.address)
    if (updates.phone != null) formData.append('phone', updates.phone)
    if (updates.birthday != null) formData.append('birthday', updates.birthday)
    if (updates.preferences != null) formData.append('preferences', updates.preferences)
    const res = await client.post<{ success: boolean; profile?: UserProfile }>(
      `api/profile/${encodeURIComponent(normalizedImei)}`,
      formData,
      { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } }
    )
    return res.data?.success && res.data.profile ? res.data.profile : null
  } catch {
    return null
  }
}

/** 轮询获取绑定 IMEI，手机扫码后服务端返回 */
export async function getBindingImei(token: string): Promise<string | null> {
  try {
    const client = getCustomerApiClient()
    const res = await client.get<{ imei: string }>(`api/binding/${token}`)
    return res.data?.imei ?? null
  } catch {
    return null
  }
}

/** 端云互发 - 发送消息 */
export interface CrossDeviceMessage {
  id: string
  from_device: 'pc' | 'mobile'
  content: string
  message_type: string
  file_base64?: string
  file_name?: string
  /** 部分客户端可能存 camelCase */
  imageBase64?: string
  created_at: string
}

export async function sendCrossDeviceMessage(
  imei: string,
  content: string,
  options?: {
    message_type?: string
    file_base64?: string
    file_name?: string
    /** 与好友发图一致：写入 file_base64，默认 message_type 为 image */
    imageBase64?: string
  }
): Promise<{ success: boolean; message_id?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const imageB64 = options?.imageBase64
  const fileB64 = options?.file_base64 ?? imageB64
  const messageType = options?.message_type ?? (imageB64 ? 'image' : 'text')
  const body: Record<string, unknown> = { imei: normalizedImei, content, message_type: messageType }
  if (fileB64) body.file_base64 = fileB64
  if (options?.file_name) body.file_name = options.file_name
  else if (imageB64) body.file_name = '图片.png'
  const res = await client.post<{ success: boolean; message_id?: string }>('api/cross-device/send', body)
  return res.data ?? { success: false }
}

/**
 * 发送好友消息（HTTP，与 customer_service `POST /api/friends/send-message` 对应）。
 * PC 客户端已改为经 WebSocket `friend_message` 发送；保留此函数供脚本或其它客户端兼容。
 */
export async function sendFriendMessage(
  imei: string,
  targetImei: string,
  content: string,
  options?: { messageType?: string; imageBase64?: string }
): Promise<{ success: boolean; message?: string; message_id?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const client = getCustomerApiClient()
  const messageType = options?.messageType ?? 'text'
  const body: Record<string, unknown> = { imei: normalizedImei, targetImei, content, message_type: messageType }
  if (options?.imageBase64) body.imageBase64 = options.imageBase64
  const res = await client.post<{ success: boolean; message?: string; message_id?: string }>(
    'api/friends/send-message',
    body
  )
  return res.data ?? { success: false }
}

/** 使用 customer_service WebSocket 发送群消息（用于会话页外主动投递） */
export async function sendGroupMessageViaWebSocket(
  imei: string,
  groupId: string,
  content: string,
  options?: { messageType?: string; imageBase64?: string; timeoutMs?: number }
): Promise<{ success: boolean; message?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, message: '设备标识无效，请重新绑定后重试' }
  const baseUrl = getCustomerServiceBaseUrl().replace(/\/+$/, '')
  const wsBase = baseUrl.startsWith('https://')
    ? baseUrl.replace(/^https:\/\//, 'wss://')
    : baseUrl.replace(/^http:\/\//, 'ws://')
  const wsUrl = `${wsBase}/ws/customer-service/${encodeURIComponent(normalizedImei)}?device=pc`
  const timeoutMs = options?.timeoutMs ?? 12000

  return new Promise((resolve) => {
    let settled = false
    let timer: ReturnType<typeof setTimeout> | null = null
    let ws: WebSocket | null = null

    const finish = (result: { success: boolean; message?: string }) => {
      if (settled) return
      settled = true
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
      try {
        ws?.close()
      } catch {
        // ignore
      }
      resolve(result)
    }

    try {
      ws = new WebSocket(wsUrl)
    } catch (e) {
      finish({ success: false, message: e instanceof Error ? e.message : 'WebSocket 创建失败' })
      return
    }

    timer = setTimeout(() => {
      finish({ success: false, message: '发送超时，请稍后重试' })
    }, timeoutMs)

    ws.onopen = () => {
      try {
        ws!.send(
          JSON.stringify({
            type: 'group_message',
            groupId,
            content,
            message_type: options?.messageType ?? (options?.imageBase64 ? 'image' : 'text'),
            ...(options?.imageBase64 ? { imageBase64: options.imageBase64 } : {}),
          })
        )
        finish({ success: true })
      } catch (e) {
        finish({ success: false, message: e instanceof Error ? e.message : '发送失败' })
      }
    }

    ws.onerror = () => {
      finish({ success: false, message: 'WebSocket 连接失败' })
    }
  })
}

/** PC 端发起执行指令（由手机端执行，结果回传 PC）。conversationId 可选，用于群聊 @小助手 时将结果关联到群 */
export async function sendExecuteCommand(
  imei: string,
  query: string,
  uuid: string,
  steps?: string[],
  conversationId?: string
): Promise<{ success: boolean; message_id?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = { imei: normalizedImei, query, uuid }
  if (steps && steps.length > 0) {
    body.steps = steps
  }
  if (conversationId) {
    body.conversation_id = conversationId
  }
  const res = await client.post<{ success: boolean; message_id?: string }>(
    'api/cross-device/execute',
    body
  )
  return res.data ?? { success: false }
}

/** PC 端发起自定义小助手执行（need_execution 后自动推送，手机端在对应小助手上下文执行） */
export async function sendExecuteForAssistant(
  imei: string,
  query: string,
  uuid: string,
  assistantBaseUrl: string,
  conversationId: string,
  chatSummary?: string
): Promise<{ success: boolean; message_id?: string }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const body = {
    imei: normalizedImei,
    query,
    uuid,
    assistant_base_url: assistantBaseUrl,
    conversation_id: conversationId,
    ...(chatSummary != null && chatSummary !== '' ? { chat_summary: chatSummary } : {}),
  }
  const res = await client.post<{ success: boolean; message_id?: string }>(
    'api/cross-device/execute',
    body
  )
  return res.data ?? { success: false }
}

export async function getCrossDeviceMessages(
  imei: string,
  beforeId?: string,
  limit = 20,
  signal?: AbortSignal,
  sinceTimestamp?: number
): Promise<{ messages: CrossDeviceMessage[]; has_more: boolean }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { messages: [], has_more: false }
  const client = getCustomerApiClient()
  const params: Record<string, string | number> = { imei: normalizedImei, limit }
  if (beforeId) params.before_id = beforeId
  if (sinceTimestamp != null && sinceTimestamp > 0) params.since_timestamp = sinceTimestamp
  const res = await client.get<{ success: boolean; messages: CrossDeviceMessage[]; has_more?: boolean }>(
    'api/cross-device/messages',
    { params, signal }
  )
  return {
    messages: res.data?.messages ?? [],
    has_more: res.data?.has_more ?? false,
  }
}

/** 统一消息接口：分页获取任意会话历史 */
export interface UnifiedMessage {
  id: string
  sender?: string
  sender_label?: string
  sender_imei?: string
  is_clone_reply?: boolean
  clone_origin?: string
  clone_owner_imei?: string
  content: string
  type?: string
  created_at?: string
  message_type?: string
  file_base64?: string
  file_name?: string
  /** 好友单聊等场景服务端可能存 camelCase */
  imageBase64?: string
}

/** 多 session 拉取：从服务端获取 session 列表（用于加载时先拉取，支持删除同步） */
export async function getSessions(
  imei: string,
  conversationId: string,
  options?: { signal?: AbortSignal; baseUrl?: string }
): Promise<{ success: boolean; sessions: Array<{ id: string; title: string; createdAt: number }> }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, sessions: [] }
  const client = getCustomerApiClient()
  const params: Record<string, string> = { imei: normalizedImei, conversation_id: conversationId }
  if (options?.baseUrl) params.base_url = options.baseUrl
  try {
    const res = await client.get<{ success: boolean; sessions?: Array<{ id: string; title: string; createdAt: number }> }>(
      'api/sessions',
      { params, signal: options?.signal }
    )
    const success = res.data?.success ?? false
    const sessions = res.data?.sessions ?? []
    return { success, sessions }
  } catch (e) {
    console.warn('[Session] getSessions 请求失败', e)
    throw e
  }
}

/** 多 session 同步：上传本地 sessions，服务端合并后返回。baseUrl 用于自定义小助手跨端一致（PC/手机 assistant id 可能不同） */
export async function syncSessions(
  imei: string,
  conversationId: string,
  sessions: Array<{ id: string; title: string; createdAt: number }>,
  options?: { signal?: AbortSignal; baseUrl?: string }
): Promise<{ success: boolean; sessions: Array<{ id: string; title: string; createdAt: number }> }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, sessions: [] }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = { imei: normalizedImei, conversation_id: conversationId, sessions }
  if (options?.baseUrl) body.base_url = options.baseUrl
  try {
    const res = await client.post<{ success: boolean; sessions?: Array<{ id: string; title: string; createdAt: number }> }>(
      'api/sessions/sync',
      body,
      { signal: options?.signal }
    )
    const success = res.data?.success ?? false
    const returned = res.data?.sessions ?? []
    console.log('[Session] syncSessions 响应', {
      imei: normalizedImei ? `${normalizedImei.slice(0, 8)}...` : '(空)',
      success,
      baseUrl: options?.baseUrl ? '有' : '无',
      uploaded: sessions.length,
      returned: returned.length,
    })
    return { success, sessions: returned }
  } catch (e) {
    console.warn('[Session] syncSessions 请求失败', e)
    throw e
  }
}

/** 获取跨端当前活跃 session（多 session 自定义小助手跟切） */
export async function getActiveSession(
  imei: string,
  conversationId: string,
  options?: { signal?: AbortSignal; baseUrl?: string }
): Promise<{ success: boolean; active_session_id: string | null; updated_at: number }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, active_session_id: null, updated_at: 0 }
  const client = getCustomerApiClient()
  const params: Record<string, string> = { imei: normalizedImei, conversation_id: conversationId }
  if (options?.baseUrl) params.base_url = options.baseUrl
  try {
    const res = await client.get<{
      success?: boolean
      active_session_id?: string | null
      updated_at?: number
    }>('api/sessions/active', { params, signal: options?.signal })
    return {
      success: res.data?.success ?? false,
      active_session_id: res.data?.active_session_id ?? null,
      updated_at: res.data?.updated_at ?? 0,
    }
  } catch (e) {
    console.warn('[Session] getActiveSession 请求失败', e)
    throw e
  }
}

/** 设置活跃 session 并触发对端 WebSocket 跟切 */
export async function setActiveSession(
  imei: string,
  conversationId: string,
  activeSessionId: string,
  options?: { signal?: AbortSignal; baseUrl?: string }
): Promise<{ success: boolean; active_session_id?: string; updated_at?: number }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false }
  const client = getCustomerApiClient()
  const body: Record<string, unknown> = {
    imei: normalizedImei,
    conversation_id: conversationId,
    active_session_id: activeSessionId,
  }
  if (options?.baseUrl) body.base_url = options.baseUrl
  try {
    const res = await client.post<{
      success?: boolean
      active_session_id?: string
      updated_at?: number
    }>('api/sessions/active', body, { signal: options?.signal })
    return {
      success: res.data?.success ?? false,
      active_session_id: res.data?.active_session_id,
      updated_at: res.data?.updated_at,
    }
  } catch (e) {
    console.warn('[Session] setActiveSession 请求失败', e)
    throw e
  }
}

export async function getUnifiedMessages(
  imei: string,
  conversationId: string,
  beforeId?: string,
  limit = 20,
  signal?: AbortSignal,
  sinceTimestamp?: number
): Promise<{ messages: UnifiedMessage[]; has_more: boolean }> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { messages: [], has_more: false }
  const client = getCustomerApiClient()
  const params: Record<string, string | number> = { imei: normalizedImei, conversation_id: conversationId, limit }
  if (beforeId) params.before_id = beforeId
  if (sinceTimestamp != null && sinceTimestamp > 0) params.since_timestamp = sinceTimestamp
  const res = await client.get<{ success: boolean; messages: UnifiedMessage[]; has_more?: boolean }>(
    'api/messages',
    { params, signal }
  )
  return {
    messages: res.data?.messages ?? [],
    has_more: res.data?.has_more ?? false,
  }
}

/** 启动时聚合增量同步：好友单聊、群聊、内置小助手（方案 B） */
export async function syncInbox(
  imei: string,
  sinceTimestamp: number,
  options?: { limitPerConversation?: number; signal?: AbortSignal }
): Promise<{
  success: boolean
  conversations: Record<string, UnifiedMessage[]>
  server_time_ms?: number
}> {
  const normalizedImei = normalizeImeiOrNull(imei)
  if (!normalizedImei) return { success: false, conversations: {} }
  const client = getCustomerApiClient()
  const params: Record<string, string | number> = {
    imei: normalizedImei,
    since_timestamp: sinceTimestamp,
    limit_per_conversation: options?.limitPerConversation ?? 80,
  }
  const res = await client.get<{
    success: boolean
    conversations?: Record<string, UnifiedMessage[]>
    server_time_ms?: number
  }>('api/inbox/sync', { params, signal: options?.signal })
  return {
    success: res.data?.success ?? false,
    conversations: res.data?.conversations ?? {},
    server_time_ms: res.data?.server_time_ms,
  }
}

// ------------ 技能社区 API ------------

export type ScheduleType = 'ONCE' | 'DAILY' | 'WEEKLY' | 'MONTHLY'

export interface SkillScheduleConfig {
  isEnabled: boolean
  scheduleType: ScheduleType
  targetTime: number
  repeatDays?: number[]  // 0=周日 1=周一 ... 6=周六
  nextTriggerTime?: number
}

export interface Skill {
  id: string
  title: string
  steps: string[]
  createdAt: number
  originalPurpose?: string
  isHot?: boolean
  hotSetAt?: number
  scheduleConfig?: SkillScheduleConfig
  /** 执行平台；有值则 UI 显示对应标签；无值且 source === 'tophub' 时 UI 视为手机（见 getSkillPlatformUiLabel） */
  executionPlatform?: 'mobile' | 'pc'
  /** 技能来源（tophub=TopHub 社区接口；plaza=技能广场） */
  source?: 'local' | 'publichub' | 'tophub' | 'plaza' | 'workspace' | 'builtin' | 'shared' | 'workspace_legacy'
  author?: string
  downloads?: number
  stars?: number
  isCertified?: boolean
  tags?: string[]
  /** PublicHub 技能详情页链接（抓取自 clawhub.ai 等目录站） */
  publicHubUrl?: string
  /** 分享包（zip）的 base64，主要用于技能广场与聊天分享落地安装 */
  packageBase64?: string
  /** 分享包文件名（如 xxx.zip） */
  packageFileName?: string
}

/** 从技能 id 解析 PublicHub slug（兼容历史 clawhub_ 前缀） */
export function publicHubSlugFromSkillId(id: string): string | null {
  if (id.startsWith('publichub_')) return id.slice('publichub_'.length)
  if (id.startsWith('clawhub_')) return id.slice('clawhub_'.length)
  return null
}

/** 技能上 PublicHub 详情页 URL（兼容历史 clawhubUrl） */
export function getPublicHubPageUrl(skill: Skill): string | undefined {
  if (skill.publicHubUrl) return skill.publicHubUrl
  const legacy = skill as Skill & { clawhubUrl?: string }
  return legacy.clawhubUrl
}

/** 与本地 Skills 服务同步的技能（相对 TopHub 技能社区；与 PublicHub 目录同源展示通路） */
export function isSkillSyncedFromService(skill: Pick<Skill, 'source'>): boolean {
  switch (skill.source) {
    case 'local':
    case 'publichub':
    case 'workspace':
    case 'builtin':
    case 'shared':
    case 'workspace_legacy':
      return true
    case 'plaza':
      return false
    default:
      return false
  }
}

/** 来源分类小标签（与标题旁 PublicHub 徽章不同，同「内置技能」一类） */
export function getSkillSourceCategoryLabel(source: Skill['source'] | undefined): string | null {
  switch (source) {
    case 'workspace':
      return '工作区技能'
    case 'builtin':
      return '内置技能'
    case 'local':
      return '本地'
    case 'publichub':
      return 'PublicHub'
    case 'tophub':
      return null
    case 'plaza':
      return '技能广场'
    case 'shared':
      return '全局技能'
    case 'workspace_legacy':
      return '旧工作区技能'
    default:
      return null
  }
}

/** 有 executionPlatform 则显示对应标签；无 platform 且来自 TopHub 则显示手机；否则不显示 */
export function getSkillPlatformUiLabel(skill: Skill): '手机' | 'PC' | null {
  if (skill.executionPlatform === 'pc') return 'PC'
  if (skill.executionPlatform === 'mobile') return '手机'
  if (skill.source === 'tophub') return '手机'
  return null
}

export interface SkillServiceResponse {
  skills: Skill[]
}

/** 从技能社区获取技能列表 */
export async function fetchSkillsFromCommunity(
  params?: { name?: string; desc?: string },
  signal?: AbortSignal
): Promise<Skill[]> {
  const baseUrl = getSkillCommunityBaseUrl() || DEFAULT_SKILL_COMMUNITY_URL
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  const url = `${normalizedBaseUrl}get_skills_for_mobile`
  const searchParams = new URLSearchParams()
  if (params?.name) searchParams.set('name', params.name)
  if (params?.desc) searchParams.set('desc', params.desc)
  const qs = searchParams.toString()
  const fullUrl = qs ? `${url}?${qs}` : url
  const res = await fetch(fullUrl, { signal })
  if (!res.ok) throw new Error(`技能社区请求失败: HTTP ${res.status}`)
  const data = (await res.json()) as SkillServiceResponse
  const list = data.skills ?? []
  return list.map((raw): Skill => {
    const s = raw as Skill
    return {
      ...s,
      source: 'tophub',
      executionPlatform: s.executionPlatform,
    }
  })
}

/** PublicHub 目录搜索（仅 q + limit，不经 URL 传排序/筛选参数） */
export interface PublicHubFetchOptions {
  query?: string
}

/** 各镜像 API 根与技能详情页站点根（路由与 Clawhub 一致：/api/v1/search、/api/v1/skills、/skills/{slug}） */
const PUBLIC_HUB_MIRRORS: Record<PublicHubMirrorId, { apiBase: string; skillPageOrigin: string }> = {
  clawhub: {
    apiBase: 'https://clawhub.ai/api/v1',
    skillPageOrigin: 'https://clawhub.ai',
  },
  skillhub: {
    apiBase: 'https://lightmake.site/api/v1',
    skillPageOrigin: 'https://lightmake.site',
  },
}

export const PUBLIC_HUB_MIRROR_OPTIONS: { id: PublicHubMirrorId; label: string }[] = [
  { id: 'skillhub', label: 'Skillhub' },
  { id: 'clawhub', label: 'Clawhub' },
]

/** 详情弹窗「在 ClawHub 查看」专用：固定 https://clawhub.ai/skills/{slug}，不受 PublicHub 镜像设置影响 */
export function getClawHubSkillPageUrl(skill: Skill): string | undefined {
  const fromId = publicHubSlugFromSkillId(skill.id)
  if (fromId) {
    return `${PUBLIC_HUB_MIRRORS.clawhub.skillPageOrigin}/skills/${fromId}`
  }
  const page = getPublicHubPageUrl(skill)
  if (page) {
    const match = page.match(/\/skills\/([^/?#]+)/)
    if (match?.[1]) {
      return `${PUBLIC_HUB_MIRRORS.clawhub.skillPageOrigin}/skills/${match[1]}`
    }
  }
  return undefined
}

function normalizePublicHubApiBase(base: string): string {
  return base.replace(/\/+$/, '')
}

/** 从 PublicHub 目录（经主进程代理；镜像由本地设置决定）获取技能列表 */
export async function fetchSkillsFromPublicHub(options?: PublicHubFetchOptions | string): Promise<Skill[]> {
  const opts: PublicHubFetchOptions =
    typeof options === 'string' ? { query: options } : options || {}

  const publicHubFetch = (window as unknown as { publicHubFetch?: { get: (u: string) => Promise<{ success: boolean; data?: unknown; error?: string }> } }).publicHubFetch
  if (!publicHubFetch) {
    console.warn('publicHubFetch 不可用，跳过 PublicHub 请求')
    return []
  }

  const mirrorId = getPublicHubMirror()
  const { apiBase, skillPageOrigin } = PUBLIC_HUB_MIRRORS[mirrorId]
  const apiRoot = normalizePublicHubApiBase(apiBase)

  try {
    const query = opts.query?.trim()
    let url: string

    if (query) {
      const searchParams = new URLSearchParams({ q: query, limit: '50' })
      url = `${apiRoot}/search?${searchParams.toString()}`
    } else {
      const searchParams = new URLSearchParams({ limit: '50' })
      url = `${apiRoot}/skills?${searchParams.toString()}`
    }

    const result = await publicHubFetch.get(url)
    if (!result.success || !result.data) {
      console.error('PublicHub 请求失败:', result.error)
      return []
    }

    const data = result.data as { results?: unknown[]; items?: unknown[] }
    const items = data.results || data.items || []

    return items.map((item: unknown): Skill => {
      const rec = item as Record<string, unknown>
      const slug = (rec.slug || rec.id || '') as string
      const displayName = (rec.displayName || rec.name || rec.title || '') as string
      const summary = (rec.summary || rec.description || '') as string
      const stats = (rec.stats || {}) as Record<string, unknown>
      const downloads = (stats.downloads ?? rec.downloads ?? 0) as number
      const stars = (stats.stars ?? rec.stars ?? 0) as number
      const tagsObj = rec.tags
      const tags =
        typeof tagsObj === 'object' && tagsObj !== null && !Array.isArray(tagsObj)
          ? Object.keys(tagsObj as Record<string, unknown>)
          : Array.isArray(tagsObj)
            ? (tagsObj as string[])
            : []
      const owner = (rec.owner || {}) as Record<string, unknown>
      const author = (typeof owner === 'object' && owner.handle ? owner.handle : rec.author || '') as string
      const createdAt = (rec.createdAt || rec.created_at || Date.now()) as number
      const isCertified = !!(rec.isCertified || rec.certified)

      return {
        id: `publichub_${slug}`,
        title: displayName,
        steps: [],
        createdAt: typeof createdAt === 'number' ? createdAt : Date.now(),
        originalPurpose: summary,
        source: 'publichub',
        author: author || undefined,
        downloads: typeof downloads === 'number' ? downloads : undefined,
        stars: typeof stars === 'number' ? stars : undefined,
        isCertified: isCertified || undefined,
        tags: tags.length > 0 ? tags : undefined,
        publicHubUrl: slug ? `${skillPageOrigin}/skills/${slug}` : undefined,
      }
    })
  } catch (e) {
    console.error('获取 PublicHub 技能失败:', e)
    return []
  }
}

export type ColorClawBridge = {
  get?: (path: string) => Promise<{ success: boolean; data?: Record<string, unknown>; error?: string }>
  post?: (path: string, body: unknown) => Promise<{ success: boolean; data?: Record<string, unknown>; error?: string }>
  delete?: (path: string) => Promise<{ success: boolean; data?: Record<string, unknown>; error?: string }>
}

export function getColorClawService(): ColorClawBridge | undefined {
  return (window as unknown as { colorClawService?: ColorClawBridge }).colorClawService
}

export interface ServiceSkillPackageExportResult {
  success: boolean
  skillName?: string
  source?: string
  packageBase64?: string
  packageFileName?: string
  error?: string
}

export async function exportInstalledSkillPackageFromService(skillName: string): Promise<ServiceSkillPackageExportResult> {
  const normalized = (skillName || '').trim()
  if (!normalized) return { success: false, error: '技能名不能为空' }
  const colorClawService = getColorClawService()
  if (!colorClawService?.get) {
    return { success: false, error: '当前环境不支持技能包导出' }
  }
  try {
    const result = await colorClawService.get(`/skills/${encodeURIComponent(normalized)}/package`)
    if (!result.success || !result.data) {
      return { success: false, error: result.error || '导出失败' }
    }
    const data = result.data as Record<string, unknown>
    return {
      success: true,
      skillName: typeof data.name === 'string' ? data.name : normalized,
      source: typeof data.source === 'string' ? data.source : undefined,
      packageBase64: typeof data.package_base64 === 'string' ? data.package_base64 : undefined,
      packageFileName: typeof data.package_file_name === 'string' ? data.package_file_name : undefined,
    }
  } catch (e) {
    return { success: false, error: `导出失败: ${e instanceof Error ? e.message : String(e)}` }
  }
}

export async function importSkillPackageToService(
  packageBase64: string,
  options?: { preferName?: string; overwrite?: boolean }
): Promise<{ success: boolean; skillName?: string; error?: string }> {
  const payload = (packageBase64 || '').trim()
  if (!payload) return { success: false, error: '技能包为空' }
  const colorClawService = getColorClawService()
  if (!colorClawService?.post) {
    return { success: false, error: '当前环境不支持技能包安装' }
  }
  try {
    const result = await colorClawService.post('/skills/import-package', {
      package_base64: payload,
      prefer_name: options?.preferName?.trim() || undefined,
      overwrite: options?.overwrite === true,
    })
    if (!result.success || !result.data) {
      return { success: false, error: result.error || '安装失败' }
    }
    const data = result.data as Record<string, unknown>
    return {
      success: true,
      skillName: typeof data.name === 'string' ? data.name : undefined,
    }
  } catch (e) {
    return { success: false, error: `安装失败: ${e instanceof Error ? e.message : String(e)}` }
  }
}

/** 安装 PublicHub 目录技能（通过 IPC → 主进程 → 本地 Skills 服务端，绕过浏览器 CORS） */
export async function installPublicHubSkill(
  skill: Skill
): Promise<{ success: boolean; error?: string; installedPath?: string }> {
  const colorClawService = getColorClawService()
  if (!colorClawService?.post) {
    return { success: false, error: '当前环境不支持 PublicHub 安装' }
  }

  let slug = publicHubSlugFromSkillId(skill.id) || ''
  if (!slug) {
    const pageUrl = getPublicHubPageUrl(skill)
    if (pageUrl) {
      const match = pageUrl.match(/\/skills\/([^/]+)/)
      if (match?.[1]) slug = match[1]
    }
  }

  if (!slug) {
    return { success: false, error: '无法解析 PublicHub 技能 slug' }
  }

  const sourceUrl = `https://wry-manatee-359.convex.site/api/v1/download?slug=${encodeURIComponent(slug)}`

  try {
    const result = await colorClawService.post('/skills/download', {
      source_url: sourceUrl,
      skill_name: slug,
      overwrite: false,
    })

    if (result.success && result.data?.status === 'ok') {
      return { success: true, installedPath: result.data.path as string | undefined }
    }

    return { success: false, error: result.error || '安装失败' }
  } catch (e) {
    return { success: false, error: `安装失败: ${e instanceof Error ? e.message : String(e)}` }
  }
}

/** 获取服务端已安装技能列表 */
export async function fetchInstalledSkillsFromService(): Promise<
  { name: string; description: string; source: string; path?: string }[]
> {
  const colorClawService = getColorClawService()
  if (!colorClawService?.get) {
    console.warn('colorClawService.get 不可用')
    return []
  }

  try {
    const result = await colorClawService.get('/skills')
    if (result.success && result.data?.skills) {
      return result.data.skills as { name: string; description: string; source: string; path?: string }[]
    }
    console.error('获取服务端技能列表失败:', result.error)
    return []
  } catch (e) {
    console.error('获取服务端技能列表异常:', e)
    return []
  }
}

/** 更新所有 PublicHub 目录已安装技能 */
export async function updatePublicHubSkills(): Promise<{ success: boolean; message?: string; error?: string }> {
  const colorClawService = getColorClawService()
  if (!colorClawService?.post) {
    return { success: false, error: '当前环境不支持更新' }
  }

  try {
    const result = await colorClawService.post('/skills/update', {})
    if (result.success && result.data?.status === 'ok') {
      return { success: true, message: result.data.message as string | undefined }
    }
    return { success: false, error: result.error || '更新失败' }
  } catch (e) {
    return { success: false, error: `更新失败: ${e instanceof Error ? e.message : String(e)}` }
  }
}

/** 从服务端删除技能 */
export async function removePublicHubSkillFromService(skillName: string): Promise<{ success: boolean; error?: string }> {
  const colorClawService = getColorClawService()
  if (!colorClawService?.post) {
    return { success: false, error: '当前环境不支持删除' }
  }

  try {
    const result = await colorClawService.post('/skills/remove', { skill_name: skillName })
    if (result.success && result.data?.status === 'ok') {
      return { success: true }
    }
    return { success: false, error: result.error || '删除失败' }
  } catch (e) {
    return { success: false, error: `删除失败: ${e instanceof Error ? e.message : String(e)}` }
  }
}
