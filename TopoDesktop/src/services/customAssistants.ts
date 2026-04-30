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
 * 自定义小助手存储
 * 协议：assistant://add?type={type}&url={base_url}&name={name}[&multiSession=1]
 * - type: execution|chat（旧）或 execution_mobile,execution_pc,chat（新，逗号分隔）
 * - multiSession: 1 或 true 表示支持多 session，解析后写入 multiSessionEnabled
 */
import { ASSISTANT_AVATAR, GROUP_MANAGER_AVATAR } from '../constants/assistants'

const KEY_CUSTOM_ASSISTANTS = 'custom_assistants'
const PREFIX_ID = 'custom_'
const RANDOM_ID_LEN = 8
const TOPOCLAW_RELAY_BASE_URL = 'topoclaw://relay'

export const TYPE_EXECUTION = 'execution'
export const TYPE_CHAT = 'chat'
export const CAP_EXECUTION_MOBILE = 'execution_mobile'
export const CAP_EXECUTION_PC = 'execution_pc'
export const CAP_CHAT = 'chat'
/** 群组管理者：群内未 @ 任何小助手时，消息统一由此助手回复 */
export const CAP_GROUP_MANAGER = 'group_manager'

/** 内置在 exe 内的小助手默认地址（TopoClaw / nanobot） */
export const DEFAULT_BUILTIN_ASSISTANT_URL = 'http://localhost:18790/'
/** 内置 GroupManager（SimpleChat，非 nanobot） */
export const DEFAULT_BUILTIN_GROUP_MANAGER_URL = 'http://localhost:18791/'

/** 每个用户默认的内置小助手（可在我创建的助手中编辑）；固定 id 便于端云同步与去重 */
export const DEFAULT_TOPOCLAW_ASSISTANT_ID = 'custom_topoclaw'
const DEFAULT_TOPOCLAW_NAME = 'TopoClaw'
const DEFAULT_TOPOCLAW_INTRO = '您的数字分身，您的全能小助手'

export const DEFAULT_GROUP_MANAGER_ASSISTANT_ID = 'custom_groupmanager'

export function isDefaultBuiltinUrl(url: string): boolean {
  const norm = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''
  const u = norm(url)
  if (u === norm(DEFAULT_BUILTIN_ASSISTANT_URL) || u === norm(DEFAULT_BUILTIN_GROUP_MANAGER_URL)) {
    return true
  }
  try {
    const parsed = new URL(url)
    const port = Number(parsed.port || (parsed.protocol === 'https:' ? 443 : 80))
    // 内置默认域名在桌面端可能是 localhost，也可能是局域网 IP（同端口）。
    return port === 18790 || port === 18791
  } catch {
    return false
  }
}

export function builtinSlotForAssistantId(id: string): 'topoclaw' | 'groupmanager' | null {
  if (id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID) return 'groupmanager'
  if (id === DEFAULT_TOPOCLAW_ASSISTANT_ID) return 'topoclaw'
  return null
}

/** 来源：向导「创建」为 created；链接或广场「添加」为 added */
export type AssistantOrigin = 'created' | 'added'

export interface CustomAssistant {
  id: string
  name: string
  intro?: string
  baseUrl: string
  capabilities?: string[]
  avatar?: string
  /** 创建者展示文案（如 短 imei · 昵称） */
  creator_imei?: string
  /** 创建者头像（base64/data URL） */
  creator_avatar?: string
  /** 运行时 agent 的系统 prompt（单端口多助手） */
  systemPrompt?: string
  /** 运行时 agent 的技能白名单 */
  skillsInclude?: string[]
  /** 运行时 agent 的技能黑名单 */
  skillsExclude?: string[]
  /** 是否支持多 session，新建/添加时可设置，默认 false */
  multiSessionEnabled?: boolean
  /** 时间戳+随机码形成的唯一展示ID，用于小助手主页展示 */
  displayId?: string
  /** 用户在本机创建 vs 通过链接/广场添加，用于「我的助手」内筛选 */
  assistantOrigin?: AssistantOrigin
}

export function resolveCustomAssistantAvatarForDisplay(
  assistant: Pick<CustomAssistant, 'id' | 'avatar'>
): string | undefined {
  const raw = String(assistant.avatar || '').trim()
  if (raw) return raw
  if (assistant.id === DEFAULT_TOPOCLAW_ASSISTANT_ID) return ASSISTANT_AVATAR
  if (assistant.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID) return GROUP_MANAGER_AVATAR
  return undefined
}

function randomDigits(n = RANDOM_ID_LEN): string {
  let out = ''
  for (let i = 0; i < n; i += 1) out += String(Math.floor(Math.random() * 10))
  return out
}

function safeImeiToken(imei?: string): string {
  const token = String(imei ?? '').trim().toLowerCase().replace(/[^a-z0-9]/g, '')
  return token || 'imei'
}

export function buildCustomAssistantId(imei?: string, nowMs?: number): string {
  const ts = Number.isFinite(nowMs) ? Math.trunc(nowMs as number) : Date.now()
  return `${safeImeiToken(imei)}_${ts}_${randomDigits(RANDOM_ID_LEN)}`
}

/** 生成时间戳+随机码形成的唯一展示ID */
function generateDisplayId(): string {
  return `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

function parseCapabilities(typeParam: string): string[] {
  const raw = typeParam.split(',').map((s) => s.trim()).filter(Boolean)
  if (raw.length === 0) return [CAP_CHAT]
  return raw.map((cap) => {
    if (cap === TYPE_EXECUTION) return CAP_EXECUTION_MOBILE
    if (cap === TYPE_CHAT) return CAP_CHAT
    return cap
  })
}

export function buildAssistantUrl(
  name: string,
  baseUrl: string,
  capabilities: string[],
  options?: { multiSessionEnabled?: boolean; assistantId?: string }
): string {
  const typeParam = capabilities.length === 0 ? CAP_CHAT
    : capabilities.length === 1 ? capabilities[0]
    : capabilities.join(',')
  const urlEncoded = encodeURIComponent(baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`)
  const nameEncoded = encodeURIComponent(name)
  let url = `assistant://add?type=${typeParam}&url=${urlEncoded}&name=${nameEncoded}`
  if (options?.assistantId?.trim()) {
    url += `&id=${encodeURIComponent(options.assistantId.trim())}`
  }
  if (options?.multiSessionEnabled) {
    url += '&multiSession=1'
  }
  return url
}

export function parseAssistantUrl(uriString: string, creatorImei?: string): CustomAssistant | null {
  try {
    const uri = new URL(uriString.trim())
    if (uri.protocol !== 'assistant:' || uri.host !== 'add') return null
    const typeParam = uri.searchParams.get('type')
    const urlEncoded = uri.searchParams.get('url')
    if (!typeParam || !urlEncoded) return null
    const baseUrl = decodeURIComponent(urlEncoded)
    const normalizedUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
    const nameEnc = uri.searchParams.get('name')
    const name = nameEnc ? decodeURIComponent(nameEnc) : '小助手'
    const capabilities = parseCapabilities(typeParam)
    const multiSessionParam = uri.searchParams.get('multiSession')
    const multiSessionEnabled = multiSessionParam === '1' || multiSessionParam?.toLowerCase() === 'true'
    const idFromLink = (uri.searchParams.get('id') ?? '').trim()
    const id = idFromLink || buildCustomAssistantId(creatorImei)
    return {
      id,
      name,
      baseUrl: normalizedUrl,
      capabilities,
      multiSessionEnabled: multiSessionEnabled || undefined,
      displayId: generateDisplayId(),
      assistantOrigin: 'added',
    }
  } catch {
    return null
  }
}

function inferAssistantOrigin(raw: Record<string, unknown>, id: string): AssistantOrigin | undefined {
  const o = raw.assistantOrigin
  if (o === 'created' || o === 'added') return o
  const snake = raw.assistant_origin
  if (snake === 'created' || snake === 'added') return snake
  if (id.startsWith('plaza_')) return 'added'
  return undefined
}

function migrateAssistant(a: Record<string, unknown>): CustomAssistant {
  const caps = a.capabilities as string[] | undefined
  const type = a.type as string | undefined
  const capabilities = Array.isArray(caps) && caps.length > 0
    ? caps
    : type === TYPE_EXECUTION ? [CAP_EXECUTION_MOBILE] : [CAP_CHAT]
  const displayId = typeof a.displayId === 'string' && a.displayId.trim()
    ? a.displayId.trim()
    : typeof a.display_id === 'string' && a.display_id.trim()
      ? a.display_id.trim()
      : generateDisplayId()
  const id = String(a.id ?? '')
  const assistantOrigin = inferAssistantOrigin(a, id)
  const creatorImei = typeof a.creator_imei === 'string'
    ? a.creator_imei.trim()
    : typeof a.creatorImei === 'string'
      ? a.creatorImei.trim()
      : ''
  const creatorAvatar = typeof a.creator_avatar === 'string'
    ? a.creator_avatar
    : typeof a.creatorAvatar === 'string'
      ? a.creatorAvatar
      : undefined
  return {
    id,
    name: String(a.name ?? '小助手'),
    intro: a.intro != null ? String(a.intro) : undefined,
    baseUrl: String(a.baseUrl ?? ''),
    capabilities,
    avatar: a.avatar != null ? String(a.avatar) : undefined,
    creator_imei: creatorImei || undefined,
    creator_avatar: creatorAvatar,
    systemPrompt: a.systemPrompt != null ? String(a.systemPrompt) : undefined,
    skillsInclude: Array.isArray(a.skillsInclude)
      ? a.skillsInclude.map((x) => String(x).trim()).filter(Boolean)
      : undefined,
    skillsExclude: Array.isArray(a.skillsExclude)
      ? a.skillsExclude.map((x) => String(x).trim()).filter(Boolean)
      : undefined,
    multiSessionEnabled: a.multiSessionEnabled === true ? true : undefined,
    displayId,
    assistantOrigin,
  }
}

/** 「我创建的助手」筛选：显式 added 或广场 id 为否；其余（含未标记的旧数据）视为创建 */
export function isAssistantUserCreated(a: CustomAssistant): boolean {
  if (a.assistantOrigin === 'added') return false
  if (a.assistantOrigin === 'created') return true
  return !a.id.startsWith('plaza_')
}

export function getCustomAssistants(): CustomAssistant[] {
  try {
    const raw = localStorage.getItem(KEY_CUSTOM_ASSISTANTS)
    if (!raw) return []
    const arr = JSON.parse(raw) as unknown[]
    if (!Array.isArray(arr)) return []
    const migrated = arr
      .map((a) => migrateAssistant(a as Record<string, unknown>))
      .filter((a) => a.baseUrl)
    const needSave = arr.some((a) => !(a as Record<string, unknown>).displayId)
    if (needSave && migrated.length > 0) {
      localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(migrated))
    }
    return migrated
  } catch {
    return []
  }
}

/**
 * 若列表中尚无默认 TopoClaw，则追加一条指向当前内置服务地址的助手（与新建向导勾选默认域名一致的能力组合）。
 * @returns 是否写入了本地列表（新增）
 */
export function ensureDefaultTopoClawAssistant(baseUrl: string): boolean {
  const list = getCustomAssistants()
  if (list.some((a) => a.id === DEFAULT_TOPOCLAW_ASSISTANT_ID)) return false
  const normalized = baseUrl.trim()
  const normalizedFull = normalized.endsWith('/') ? normalized : `${normalized}/`
  addCustomAssistant({
    id: DEFAULT_TOPOCLAW_ASSISTANT_ID,
    name: DEFAULT_TOPOCLAW_NAME,
    intro: DEFAULT_TOPOCLAW_INTRO,
    baseUrl: normalizedFull,
    capabilities: [CAP_EXECUTION_MOBILE, CAP_EXECUTION_PC, CAP_CHAT],
    multiSessionEnabled: true,
    assistantOrigin: 'created',
  })
  return true
}

export function ensureDefaultGroupManagerAssistant(baseUrl: string): boolean {
  void baseUrl
  // GroupManager has been retired from the built-in desktop runtime.
  return false
}

function normalizeAssistantBaseUrl(url: string): string {
  const trimmed = (url || '').trim()
  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`
}

function normalizeRelayBaseUrl(url: string): string {
  return (url || '').trim().toLowerCase().replace(/\/+$/, '')
}

export function isHiddenRelayAssistant(a: Pick<CustomAssistant, 'baseUrl'>): boolean {
  return normalizeRelayBaseUrl(a.baseUrl) === TOPOCLAW_RELAY_BASE_URL
}

function isHiddenByDefault(a: Pick<CustomAssistant, 'id' | 'baseUrl'>): boolean {
  // GroupManager 作为群内系统角色存在，默认不在助手列表中显式展示。
  if (a.id === DEFAULT_GROUP_MANAGER_ASSISTANT_ID) return true
  return isHiddenRelayAssistant(a)
}

/** 面向界面的可见小助手列表（隐藏内部 relay 代理助手与默认 GroupManager） */
export function getVisibleCustomAssistants(): CustomAssistant[] {
  return getCustomAssistants().filter((a) => !isHiddenByDefault(a))
}

/**
 * 启动时对齐默认内置助手地址：
 * - 缺失则新增默认助手
 * - 已存在则仅同步 baseUrl 到当前默认地址（保留用户编辑的名称/头像/简介等）
 */
function alignBuiltinDefaultAssistantUrls(urls: { topoclaw: string; groupmanager: string }): boolean {
  void urls.groupmanager
  const list = getCustomAssistants()
  let changed = false
  const next = [...list]

  const ensureById = (
    id: string,
    baseUrl: string,
    createPayload: Omit<CustomAssistant, 'baseUrl'> & { baseUrl: string }
  ) => {
    const idx = next.findIndex((a) => a.id === id)
    const normalized = normalizeAssistantBaseUrl(baseUrl)
    if (idx < 0) {
      next.push({ ...createPayload, baseUrl: normalized })
      changed = true
      return
    }
    const current = next[idx]
    if (normalizeAssistantBaseUrl(current.baseUrl) !== normalized) {
      next[idx] = { ...current, baseUrl: normalized }
      changed = true
    }
  }

  ensureById(DEFAULT_TOPOCLAW_ASSISTANT_ID, urls.topoclaw, {
    id: DEFAULT_TOPOCLAW_ASSISTANT_ID,
    name: DEFAULT_TOPOCLAW_NAME,
    intro: DEFAULT_TOPOCLAW_INTRO,
    baseUrl: urls.topoclaw,
    capabilities: [CAP_EXECUTION_MOBILE, CAP_EXECUTION_PC, CAP_CHAT],
    multiSessionEnabled: true,
    assistantOrigin: 'created',
  })

  const filtered = next.filter((a) => a.id !== DEFAULT_GROUP_MANAGER_ASSISTANT_ID)
  if (filtered.length !== next.length) {
    next.splice(0, next.length, ...filtered)
    changed = true
  }

  if (changed) {
    localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(next))
  }
  return changed
}

/** 保证 TopoClaw + 内置 GroupManager（SimpleChat）存在；只要已绑定设备就同步云端 */
export async function ensureDefaultBuiltinAssistantsAndMaybeSync(imei: string | undefined): Promise<boolean> {
  const { getDefaultBuiltinUrls } = await import('./builtinAssistantConfig')
  const urls = await getDefaultBuiltinUrls()
  const changed = alignBuiltinDefaultAssistantUrls(urls)
  if (imei) {
    const { syncCustomAssistantsToCloud } = await import('./api')
    await syncCustomAssistantsToCloud(imei, getCustomAssistants())
  }
  return changed
}

export async function ensureDefaultTopoClawAndMaybeSync(imei: string | undefined): Promise<boolean> {
  return ensureDefaultBuiltinAssistantsAndMaybeSync(imei)
}

/**
 * 云端列表合并后：保证默认 TopoClaw 与 GroupManager；若有新增且已绑定设备则回写云端。
 */
export async function mergeCloudAssistantsAndEnsureDefaultTopo(
  cloudItems: Parameters<typeof setCustomAssistantsFromCloud>[0],
  imei: string | undefined
): Promise<void> {
  setCustomAssistantsFromCloud(cloudItems)
  await ensureDefaultBuiltinAssistantsAndMaybeSync(imei)
}

export function addCustomAssistant(a: CustomAssistant): void {
  const list = getCustomAssistants()
  if (list.some((x) => x.id === a.id)) return
  const toAdd = !a.displayId ? { ...a, displayId: generateDisplayId() } : a
  list.push(toAdd)
  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(list))
}

/** 更新小助手资料（名称、头像、介绍、域名） */
export function updateCustomAssistant(
  id: string,
  updates: Partial<Pick<CustomAssistant, 'name' | 'intro' | 'baseUrl' | 'avatar' | 'systemPrompt' | 'skillsInclude' | 'skillsExclude'>>
): boolean {
  const list = getCustomAssistants()
  const idx = list.findIndex((a) => a.id === id)
  if (idx < 0) return false
  const current = list[idx]
  const next = { ...current }
  if (updates.name !== undefined) next.name = updates.name.trim()
  if (updates.intro !== undefined) next.intro = updates.intro?.trim() || undefined
  if (updates.baseUrl !== undefined) {
    const u = updates.baseUrl.trim()
    next.baseUrl = u.endsWith('/') ? u : `${u}/`
  }
  if (updates.avatar !== undefined) next.avatar = updates.avatar || undefined
  if (updates.systemPrompt !== undefined) {
    const p = (updates.systemPrompt || '').trim()
    next.systemPrompt = p || undefined
  }
  if (updates.skillsInclude !== undefined) {
    const arr = (updates.skillsInclude ?? []).map((x) => String(x).trim()).filter(Boolean)
    next.skillsInclude = arr.length > 0 ? arr : undefined
  }
  if (updates.skillsExclude !== undefined) {
    const arr = (updates.skillsExclude ?? []).map((x) => String(x).trim()).filter(Boolean)
    next.skillsExclude = arr.length > 0 ? arr : undefined
  }
  list[idx] = next
  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(list))
  return true
}

export function isDefaultTopoClawAssistantId(id: string): boolean {
  return id === DEFAULT_TOPOCLAW_ASSISTANT_ID
}

export function isProtectedBuiltinAssistantId(id: string): boolean {
  return id === DEFAULT_TOPOCLAW_ASSISTANT_ID
}

export function removeCustomAssistant(id: string): void {
  if (isProtectedBuiltinAssistantId(id)) return
  const list = getCustomAssistants().filter((a) => a.id !== id)
  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(list))
}

/** 将已有小助手设为/取消群组管理者（需具备聊天能力） */
export function setAssistantGroupManager(id: string, enabled: boolean): boolean {
  const list = getCustomAssistants()
  const idx = list.findIndex((a) => a.id === id)
  if (idx < 0) return false
  const a = list[idx]
  const caps = [...(a.capabilities ?? [])]
  const hasGroup = caps.includes(CAP_GROUP_MANAGER)
  const hasChatCap = caps.includes(CAP_CHAT)
  if (enabled) {
    if (!hasChatCap) caps.push(CAP_CHAT)
    if (!hasGroup) caps.push(CAP_GROUP_MANAGER)
  } else {
    if (hasGroup) caps.splice(caps.indexOf(CAP_GROUP_MANAGER), 1)
  }
  list[idx] = { ...a, capabilities: caps }
  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(list))
  return true
}

/** 从云端拉取后替换本地列表（端云同步） */
export function setCustomAssistants(list: CustomAssistant[]): void {
  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(list.filter((a) => a.baseUrl)))
}

/** 从云端拉取后合并到本地：云端数据为准，但保留本地独有的助手以及本地 multiSessionEnabled、群组管理者（后端可能尚未支持） */
export function setCustomAssistantsFromCloud(cloudList: (CustomAssistant & { multi_session_enabled?: boolean })[]): void {
  const local = getCustomAssistants()
  const norm = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''
  const matchedLocalIds = new Set<string>()
  const matchedLocalBaseUrls = new Set<string>()
  const merged: CustomAssistant[] = cloudList
    .filter((a) => a.baseUrl)
    .map((cloud) => {
      const localById = local.find((l) => l.id === cloud.id)
      const localItem = localById ?? local.find((l) => norm(l.baseUrl) === norm(cloud.baseUrl))
      if (localItem) {
        matchedLocalIds.add(localItem.id)
        if (localItem.baseUrl) matchedLocalBaseUrls.add(norm(localItem.baseUrl))
      }
      const fromCloud = cloud.multiSessionEnabled ?? (cloud as { multi_session_enabled?: boolean }).multi_session_enabled
      const cloudCaps = cloud.capabilities ?? []
      const localHasGroupManager = localItem && (localItem.capabilities ?? []).includes(CAP_GROUP_MANAGER)
      const cloudHasGroupManager = cloudCaps.includes(CAP_GROUP_MANAGER)
      const caps = cloudHasGroupManager ? cloudCaps : localHasGroupManager ? [...cloudCaps, CAP_GROUP_MANAGER] : cloudCaps
      const displayId = typeof cloud.displayId === 'string' && cloud.displayId.trim()
        ? cloud.displayId.trim()
        : localItem?.displayId || generateDisplayId()
      const cloudOrigin = (cloud as CustomAssistant).assistantOrigin
      const mergedOrigin =
        cloudOrigin === 'created' || cloudOrigin === 'added'
          ? cloudOrigin
          : localItem?.assistantOrigin
      const creatorImei =
        typeof (cloud as { creator_imei?: unknown }).creator_imei === 'string'
          ? (cloud as { creator_imei?: string }).creator_imei?.trim()
          : undefined
      const creatorAvatar =
        typeof (cloud as { creator_avatar?: unknown }).creator_avatar === 'string'
          ? (cloud as { creator_avatar?: string }).creator_avatar
          : undefined
      return {
        ...cloud,
        capabilities: caps,
        systemPrompt:
          (cloud as CustomAssistant).systemPrompt
          ?? (cloud as CustomAssistant & { system_prompt?: string }).system_prompt
          ?? localItem?.systemPrompt
          ?? undefined,
        skillsInclude:
          (cloud as CustomAssistant).skillsInclude
          ?? (cloud as CustomAssistant & { skills_include?: string[] }).skills_include
          ?? localItem?.skillsInclude
          ?? undefined,
        skillsExclude:
          (cloud as CustomAssistant).skillsExclude
          ?? (cloud as CustomAssistant & { skills_exclude?: string[] }).skills_exclude
          ?? localItem?.skillsExclude
          ?? undefined,
        multiSessionEnabled: fromCloud ?? localItem?.multiSessionEnabled ?? undefined,
        creator_imei: creatorImei || localItem?.creator_imei,
        creator_avatar: creatorAvatar ?? localItem?.creator_avatar,
        displayId,
        ...(mergedOrigin ? { assistantOrigin: mergedOrigin } : {}),
      }
    })

  const localOnly: CustomAssistant[] = local
    .filter((l) => l.baseUrl && !matchedLocalIds.has(l.id) && !matchedLocalBaseUrls.has(norm(l.baseUrl)))
    .map((l) => ({ ...l, capabilities: l.capabilities ?? [] }))
  merged.push(...localOnly)

  localStorage.setItem(KEY_CUSTOM_ASSISTANTS, JSON.stringify(merged))
}

export function getCustomAssistantById(id: string): CustomAssistant | undefined {
  return getCustomAssistants().find((a) => a.id === id)
}

/** 按 baseUrl 查找（assistant id 因云端同步变化时，可通过 baseUrl 仍找到对应助手） */
export function getCustomAssistantByBaseUrl(baseUrl: string): CustomAssistant | undefined {
  if (!baseUrl?.trim()) return undefined
  const norm = (s: string) => (s || '').trim().replace(/\/+$/, '') || ''
  const target = norm(baseUrl)
  return getCustomAssistants().find((a) => norm(a.baseUrl) === target)
}

export function isCustomAssistantId(id: string | undefined): boolean {
  const normalized = String(id || '').trim()
  if (!normalized) return false
  if (normalized.startsWith(PREFIX_ID) || normalized.startsWith('plaza_')) return true
  // 兼容新 ID 体系：creatorImei_timestamp_random（无 custom_ 前缀）
  return getCustomAssistants().some((a) => a.id === normalized)
}

/** 群组内小助手配置（来自 assistant_configs），供陌生成员建立与助手后端的联系 */
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
  rolePrompt?: string
}

/**
 * 解析群组小助手配置：优先从本地 customAssistants 查找，若无则用群组的 assistant_configs。
 * 返回具备 baseUrl 的配置对象，供群聊中与陌生小助手互动。
 */
export function resolveGroupAssistantConfig(
  assistantId: string,
  assistantName: string,
  groupAssistantConfigs?: Record<string, GroupAssistantConfig> | null
): { id: string; name: string; baseUrl: string; capabilities?: string[]; intro?: string; avatar?: string; multiSessionEnabled?: boolean; displayId?: string; rolePrompt?: string } | null {
  const cfg = groupAssistantConfigs?.[assistantId]
  const local = getCustomAssistantById(assistantId)
  const builtinSlot = builtinSlotForAssistantId(assistantId)
  if (cfg?.baseUrl) {
    // In groups, assistant id/name may collide with local assistants (e.g. built-in fixed ids).
    // Always prefer group config as the authoritative routing target.
    // Exception: fixed built-in ids should always follow local builtin baseUrl to avoid
    // stale/mis-synced group configs (e.g. GroupManager id pointing to TopoClaw port).
    const resolvedBaseUrl = builtinSlot && local?.baseUrl ? local.baseUrl : cfg.baseUrl
    return {
      id: assistantId,
      name: cfg.name ?? assistantName,
      baseUrl: resolvedBaseUrl,
      capabilities: cfg.capabilities ?? local?.capabilities,
      intro: cfg.intro ?? local?.intro,
      avatar: cfg.avatar ?? local?.avatar,
      multiSessionEnabled: cfg.multiSession ?? local?.multiSessionEnabled,
      // In group context, displayId should be group-scoped and consistent across members.
      // Do not fallback to local displayId when group config is present.
      displayId: cfg.displayId,
      rolePrompt: cfg.rolePrompt,
    }
  }
  if (local?.baseUrl) {
    return {
      ...local,
      baseUrl: local.baseUrl,
      rolePrompt: cfg?.rolePrompt,
    }
  }
  // Important: do NOT fallback by assistant name.
  // In groups, same-name assistants may belong to different owners.
  // Routing must bind to assistantId/group config to avoid cross-user misrouting.
  return null
}

function parseCreatorNickname(
  creatorNickname?: string,
  creatorImeiLine?: string
): string {
  const nick = (creatorNickname || '').trim()
  if (nick) return nick
  const raw = (creatorImeiLine || '').trim()
  if (!raw) return ''
  if (raw.includes('·')) {
    const maybe = (raw.split('·').pop() || '').trim()
    if (maybe && maybe !== raw) return maybe
  }
  const bracketMatch = raw.match(/\(([^()]+)\)\s*$/)
  if (bracketMatch?.[1]?.trim()) return bracketMatch[1].trim()
  return ''
}

function parseCreatorLabel(
  creatorNickname?: string,
  creatorImeiLine?: string
): string {
  const nick = parseCreatorNickname(creatorNickname, creatorImeiLine)
  if (nick) return nick
  const raw = String(creatorImeiLine || '').trim()
  if (!raw) return ''
  let imei = raw
  if (imei.includes('·')) imei = (imei.split('·', 1)[0] || '').trim()
  if (imei.includes('(')) imei = (imei.split('(', 1)[0] || '').trim()
  imei = imei.replace(/\s+/g, '')
  if (!imei) return ''
  return imei.length > 8 ? `${imei.slice(0, 8)}...` : imei
}

function isTopoClawGroupAssistant(
  assistantId: string,
  fallbackName: string,
  cfg?: GroupAssistantConfig | null
): boolean {
  const id = String(assistantId || '').trim().toLowerCase()
  const name = String(cfg?.name || fallbackName || '').trim().toLowerCase()
  const baseUrl = String(cfg?.baseUrl || '').trim().toLowerCase().replace(/\/+$/, '')
  if (baseUrl === TOPOCLAW_RELAY_BASE_URL) return true
  if (!id && !name) return false
  return id.includes('topoclaw') || name.includes('topoclaw')
}

/**
 * 群内助手显示名：默认用助手名；仅当群内存在同名助手时，追加「(创建者昵称)」以区分。
 */
export function getGroupAssistantDisplayName(
  assistantId: string,
  fallbackName: string,
  assistants: Array<{ id: string; name: string }>,
  groupAssistantConfigs?: Record<string, GroupAssistantConfig> | null
): string {
  const cfg = groupAssistantConfigs?.[assistantId]
  const baseName = (cfg?.name || fallbackName || assistantId).trim() || assistantId
  if (isTopoClawGroupAssistant(assistantId, fallbackName, cfg)) {
    const creatorLabel = parseCreatorLabel(cfg?.creator_nickname, cfg?.creator_imei)
    return creatorLabel ? `TopoClaw（${creatorLabel}）` : 'TopoClaw'
  }
  const sameNameCount = assistants.reduce((count, a) => {
    const n = (groupAssistantConfigs?.[a.id]?.name || a.name || a.id).trim() || a.id
    return n === baseName ? count + 1 : count
  }, 0)
  if (sameNameCount <= 1) return baseName
  const creatorNick = parseCreatorNickname(cfg?.creator_nickname, cfg?.creator_imei)
  return creatorNick ? `${baseName}(${creatorNick})` : `${baseName}(${assistantId})`
}

/** 由 assistantId 反推出创建者 imei（格式通常为 safeImeiToken_timestamp_random） */
export function inferCreatorImeiFromAssistantId(
  assistantId: string,
  candidateImeis: string[]
): string {
  const id = String(assistantId || '').trim().toLowerCase()
  if (!id) return ''
  const token = id.split('_', 1)[0]?.trim() || ''
  if (!token) return ''
  const hit = candidateImeis.find((imei) => safeImeiToken(imei) === token)
  return hit ? String(hit).trim() : ''
}

export function hasChat(a: CustomAssistant): boolean {
  const caps = a.capabilities ?? []
  return caps.includes(CAP_CHAT)
}

export function hasExecutionPc(a: CustomAssistant): boolean {
  const caps = a.capabilities ?? []
  return caps.includes(CAP_EXECUTION_PC)
}

export function hasExecutionMobile(a: CustomAssistant): boolean {
  const caps = a.capabilities ?? []
  return caps.includes(CAP_EXECUTION_MOBILE)
}

/** 是否支持多 session（多会话） */
export function hasMultiSession(a: CustomAssistant): boolean {
  return a.multiSessionEnabled === true
}

/** 是否为群组管理者（群内未 @ 时统一由此助手回复，需同时具备聊天能力） */
export function hasGroupManager(a: CustomAssistant): boolean {
  const caps = a.capabilities ?? []
  return caps.includes(CAP_GROUP_MANAGER) && caps.includes(CAP_CHAT)
}

/** 内置小助手 id -> 简介（无自定义 intro 时使用） */
const BUILTIN_ASSISTANT_INTROS: Record<string, string> = {
  assistant: '支持手机端自动化任务，如打开应用、操作界面等',
  skill_learning: '负责记录和学习技能',
  chat_assistant: '支持对话聊天',
  customer_service: '人工客服支持',
}

/**
 * 为群组管理者构建群内小助手上下文
 * 使用各小助手的简介（intro），便于管理者根据用户问题推荐合适的小助手
 */
export function buildGroupAssistantContext(assistants: Array<{ id: string; name: string }>): string {
  if (!assistants?.length) return ''
  const list = assistants.map((a) => {
    const custom = getCustomAssistantById(a.id)
    const intro = custom?.intro?.trim() ?? BUILTIN_ASSISTANT_INTROS[a.id] ?? ''
    return intro ? `- ${a.name}：${intro}` : `- ${a.name}`
  })
  return `【群组小助手列表】当前群组内有以下小助手，请根据用户问题推荐合适的小助手：\n${list.join('\n')}\n\n`
}

/** @小助手名 后可接受的分隔符：空格、中文逗号、英文逗号、冒号等，用于提取执行指令 */
const MENTION_SEP = /^[\s,，：:、]+/

/**
 * 解析群组管理者回复中的 @小助手名 指令 格式，用于自动触发对应小助手执行
 * 返回首个匹配的 { assistant, command }，若无则 null
 * 自动包含内置「自动执行小助手」（id=assistant），与用户直接 @ 的行为一致
 */
export function parseGroupManagerMention(
  reply: string,
  assistants: Array<{ id: string; name: string }>
): { assistant: { id: string; name: string }; command: string } | null {
  if (!reply?.trim()) return null
  const text = reply.trim()
  // 确保包含内置自动执行小助手（用户可直接 @，群组管理者也可能推荐）
  const extended = [...assistants]
  if (!extended.some((x) => x.id === 'assistant')) {
    extended.push({ id: 'assistant', name: '自动执行小助手' })
  }
  // 按名称长度降序，避免「自动执行」匹配到「自动执行小助手」前
  const sorted = [...extended].sort((a, b) => b.name.length - a.name.length)
  for (const a of sorted) {
    const atName = `@${a.name}`
    if (!text.includes(atName)) continue
    const idx = text.indexOf(atName)
    const after = text.slice(idx + atName.length).replace(MENTION_SEP, '')
    const command = (after.split(/\r?\n/)[0] ?? after).trim()
    if (command) return { assistant: a, command }
  }
  return null
}
