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
 * 技能本地存储
 * - 我的技能：用户本地创建的技能
 * - 本地收藏：从技能社区下载的技能
 */
import { isSkillSyncedFromService, type Skill } from './api'
import { toCanonicalSkillName } from './skillNames'

const KEY_MY_SKILLS = 'my_skills'
const KEY_COLLECTED_SKILLS = 'collected_skills'

function loadJson<T>(key: string, defaultVal: T): T {
  try {
    const s = localStorage.getItem(key)
    if (!s) return defaultVal
    return JSON.parse(s) as T
  } catch {
    return defaultVal
  }
}

function saveJson(key: string, val: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(val))
  } catch (e) {
    // 避免 localStorage 配额异常中断渲染流程（例如技能体积过大时）。
    console.warn(`[skillStorage] 保存失败: ${key}`, e)
  }
}

/** 将历史 ClawHub 命名迁移为 PublicHub（id / source / URL 字段） */
function migrateSkillFromStorage(skill: Skill): Skill {
  const raw = skill as Skill & { clawhubUrl?: string; source?: string }
  let next: Skill = { ...skill }
  if ((raw.source as string | undefined) === 'clawhub') next = { ...next, source: 'publichub' }
  if (next.id?.startsWith('clawhub_')) {
    next = { ...next, id: `publichub_${next.id.slice('clawhub_'.length)}` }
  }
  if (raw.clawhubUrl && !next.publicHubUrl) {
    next = { ...next, publicHubUrl: raw.clawhubUrl }
  }
  return next
}

function migrateSkillList(list: Skill[]): Skill[] {
  return list.map(migrateSkillFromStorage)
}

function isSkillRecord(entry: unknown): entry is Skill {
  if (!entry || typeof entry !== 'object') return false
  const candidate = entry as Partial<Skill>
  return typeof candidate.id === 'string' && candidate.id.trim().length > 0
}

function sanitizeSkillList(list: Skill[]): Skill[] {
  return list.filter(isSkillRecord)
}

/** 加载我的技能 */
export function loadMySkills(): Skill[] {
  const list = loadJson<Skill[]>(KEY_MY_SKILLS, [])
  return sanitizeSkillList(migrateSkillList(Array.isArray(list) ? list : []))
}

/** 保存我的技能 */
export function saveMySkills(skills: Skill[]): void {
  saveJson(KEY_MY_SKILLS, skills)
}

/** 添加技能到我的技能 */
export function addMySkill(skill: Skill): boolean {
  const list = loadMySkills()
  if (list.some((s) => s.id === skill.id)) return false
  list.push({ ...skill, createdAt: skill.createdAt ?? Date.now() })
  saveMySkills(list)
  return true
}

/** 从我的技能删除 */
export function removeMySkill(skillId: string): boolean {
  const list = loadMySkills().filter((s) => s.id !== skillId)
  if (list.length === loadMySkills().length) return false
  saveMySkills(list)
  return true
}

/** 加载本地收藏（从技能社区下载的技能） */
export function loadCollectedSkills(): Skill[] {
  const list = loadJson<Skill[]>(KEY_COLLECTED_SKILLS, [])
  return sanitizeSkillList(migrateSkillList(Array.isArray(list) ? list : []))
}

/** 保存本地收藏 */
export function saveCollectedSkills(skills: Skill[]): void {
  saveJson(KEY_COLLECTED_SKILLS, skills)
}

/** 收藏技能到本地 */
export function addCollectedSkill(skill: Skill): boolean {
  const list = loadCollectedSkills()
  const normalized = skill.title.trim().toLowerCase()
  if (list.some((s) => s.title.trim().toLowerCase() === normalized)) return false
  list.push({
    ...skill,
    id: skill.id || `collected_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`,
    createdAt: skill.createdAt ?? Date.now(),
  })
  saveCollectedSkills(list)
  return true
}

/** 取消收藏 */
export function removeCollectedSkill(skillId: string): boolean {
  const list = loadCollectedSkills().filter((s) => s.id !== skillId)
  if (list.length === loadCollectedSkills().length) return false
  saveCollectedSkills(list)
  return true
}

/** 是否已收藏（按 title 匹配） */
export function isCollected(title: string): boolean {
  const norm = title.trim().toLowerCase()
  return loadCollectedSkills().some((s) => s.title.trim().toLowerCase() === norm)
}

/** 从收藏中删除（按 title 匹配） */
export function removeCollectedByTitle(title: string): boolean {
  const list = loadCollectedSkills()
  const norm = title.trim().toLowerCase()
  const filtered = list.filter((s) => s.title.trim().toLowerCase() !== norm)
  if (filtered.length === list.length) return false
  saveCollectedSkills(filtered)
  return true
}

/** 我的技能 = 我创建的 + 收藏的（合并展示） */
export function loadAllMySkills(): Skill[] {
  const my = loadMySkills()
  const collected = loadCollectedSkills()
  const seen = new Set<string>()
  const result: Skill[] = []
  for (const s of [...my, ...collected]) {
    const key = s.title.trim().toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    result.push(s)
  }
  return result.sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0))
}

/** 所有已开启定时的技能（含 my 和 collected，不按标题去重，确保带 schedule 的不被覆盖） */
export function loadScheduledSkills(): Skill[] {
  const my = loadMySkills()
  const collected = loadCollectedSkills()
  return [...my, ...collected].filter((s) => isSkillRecord(s) && s.scheduleConfig?.isEnabled === true)
}

/** 删除技能（从我的或收藏中移除） */
export function removeSkill(skill: Skill): boolean {
  const fromMy = removeMySkill(skill.id)
  const fromCollected = removeCollectedByTitle(skill.title)
  return fromMy || fromCollected
}

/** 更新技能的定时配置（按 id 查找并更新） */
export function updateSkillSchedule(
  skillId: string,
  scheduleConfig: Skill['scheduleConfig']
): boolean {
  const myList = loadMySkills()
  const myIdx = myList.findIndex((s) => s.id === skillId)
  if (myIdx >= 0) {
    myList[myIdx] = { ...myList[myIdx], scheduleConfig: scheduleConfig ?? undefined }
    saveMySkills(myList)
    return true
  }
  const collectedList = loadCollectedSkills()
  const colIdx = collectedList.findIndex((s) => s.id === skillId)
  if (colIdx >= 0) {
    collectedList[colIdx] = { ...collectedList[colIdx], scheduleConfig: scheduleConfig ?? undefined }
    saveCollectedSkills(collectedList)
    return true
  }
  return false
}

/** 更新技能（完整替换，按 id 查找） */
export function updateSkill(skill: Skill): boolean {
  const myList = loadMySkills()
  const myIdx = myList.findIndex((s) => s.id === skill.id)
  if (myIdx >= 0) {
    myList[myIdx] = skill
    saveMySkills(myList)
    return true
  }
  const collectedList = loadCollectedSkills()
  const colIdx = collectedList.findIndex((s) => s.id === skill.id)
  if (colIdx >= 0) {
    collectedList[colIdx] = skill
    saveCollectedSkills(collectedList)
    return true
  }
  return false
}

function normalizeSourceFromService(raw: string): Skill['source'] {
  if (raw === 'clawhub') return 'publichub'
  return raw as Skill['source']
}

/** 用服务端技能列表同步 collected_skills 中的服务端技能（TopHub 社区条目不受影响） */
export function syncPublicHubSkillsWithServer(
  serverSkills: { name: string; description: string; source: string; path?: string }[]
): void {
  const collected = loadCollectedSkills()
  const existingSkillsMap = new Map(collected.map((s) => [s.id, s]))

  const nonServerSkills = collected.filter((skill) => !isSkillSyncedFromService(skill))

  const serverSkillsConverted = serverSkills.map((serverSkill) => {
    const skillId = `publichub_${serverSkill.name}`
    const existing =
      existingSkillsMap.get(skillId) ?? existingSkillsMap.get(`clawhub_${serverSkill.name}`)

    return {
      id: skillId,
      title: serverSkill.name,
      steps: [] as string[],
      createdAt: existing?.createdAt || Date.now(),
      source: normalizeSourceFromService(serverSkill.source),
      originalPurpose: serverSkill.description,
      scheduleConfig: existing?.scheduleConfig,
    }
  })

  saveCollectedSkills([...nonServerSkills, ...serverSkillsConverted])
}

function normalizeSkillKey(skill: Skill): string {
  const canonical = toCanonicalSkillName(skill.title).trim().toLowerCase()
  if (canonical) return canonical
  const title = (skill.title || '').trim().toLowerCase()
  if (title) return title
  return (skill.id || '').trim().toLowerCase()
}

function skillRichnessScore(skill: Skill): number {
  const stepLen = (skill.steps ?? []).join('\n').trim().length
  const purposeLen = (skill.originalPurpose ?? '').trim().length
  const hasSchedule = skill.scheduleConfig?.isEnabled ? 1 : 0
  return stepLen + purposeLen + hasSchedule * 500
}

function pickPreferredSkill(a: Skill, b: Skill): Skill {
  // 优先保留带定时配置的、内容更完整的、时间更新的技能。
  const aScheduled = !!a.scheduleConfig?.isEnabled
  const bScheduled = !!b.scheduleConfig?.isEnabled
  if (aScheduled !== bScheduled) return aScheduled ? a : b

  const aScore = skillRichnessScore(a)
  const bScore = skillRichnessScore(b)
  if (aScore !== bScore) return aScore > bScore ? a : b

  const aTime = Number(a.createdAt || 0)
  const bTime = Number(b.createdAt || 0)
  return aTime >= bTime ? a : b
}

function dedupeByCanonicalKey(list: Skill[]): Skill[] {
  const picked = new Map<string, Skill>()
  const ordered: string[] = []

  list.forEach((skill) => {
    const key = normalizeSkillKey(skill)
    if (!key) return
    const prev = picked.get(key)
    if (!prev) {
      picked.set(key, skill)
      ordered.push(key)
      return
    }
    picked.set(key, pickPreferredSkill(prev, skill))
  })

  return ordered.map((key) => picked.get(key)).filter(Boolean) as Skill[]
}

export function compactSkillStorage(): {
  myBefore: number
  myAfter: number
  collectedBefore: number
  collectedAfter: number
  removedTotal: number
} {
  const myBeforeList = loadMySkills()
  const collectedBeforeList = loadCollectedSkills()

  const myDeduped = dedupeByCanonicalKey(myBeforeList)
  const myKeys = new Set(myDeduped.map((s) => normalizeSkillKey(s)).filter(Boolean))

  const collectedDeduped = dedupeByCanonicalKey(collectedBeforeList).filter(
    (s) => !myKeys.has(normalizeSkillKey(s))
  )

  saveMySkills(myDeduped)
  saveCollectedSkills(collectedDeduped)

  const myBefore = myBeforeList.length
  const myAfter = myDeduped.length
  const collectedBefore = collectedBeforeList.length
  const collectedAfter = collectedDeduped.length
  const removedTotal = myBefore + collectedBefore - myAfter - collectedAfter

  return { myBefore, myAfter, collectedBefore, collectedAfter, removedTotal }
}
