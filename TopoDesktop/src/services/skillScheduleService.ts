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
 * 技能定时调度服务
 * - 计算下次触发时间
 * - 轮询检查到点触发（弹窗提醒，用户点击执行后才下发）
 */
import type { Skill, SkillScheduleConfig } from './api'
import { loadScheduledSkills, updateSkillSchedule } from './skillStorage'

const POLL_INTERVAL_MS = 15_000 // 每 15 秒检查一次
const KEY_TRIGGERED = 'schedule_triggered_occurrences'
let pollTimer: ReturnType<typeof setTimeout> | null = null
let onSkillDueCallback: ((skills: Skill[]) => void) | null = null

function isValidScheduledSkill(skill: unknown): skill is Skill {
  if (!skill || typeof skill !== 'object') return false
  const candidate = skill as Partial<Skill>
  return typeof candidate.id === 'string' && candidate.id.trim().length > 0
}

function loadTriggeredOccurrences(): Set<string> {
  try {
    const s = localStorage.getItem(KEY_TRIGGERED)
    if (!s) return new Set()
    const arr = JSON.parse(s) as string[]
    return new Set(Array.isArray(arr) ? arr : [])
  } catch {
    return new Set()
  }
}

function saveTriggeredOccurrence(key: string): void {
  const set = loadTriggeredOccurrences()
  set.add(key)
  try {
    localStorage.setItem(KEY_TRIGGERED, JSON.stringify([...set]))
  } catch (_) {}
}

/** 已触发过的 occurrence（内存 + 持久化），避免每次打开都弹 */
let triggeredOccurrences = loadTriggeredOccurrences()

/**
 * 计算下次触发时间（与 apk5 SkillScheduleManager 算法一致）
 */
export function calculateNextTriggerTime(config: SkillScheduleConfig): number | null {
  const now = Date.now()

  const toCalendar = (ms: number) => {
    const d = new Date(ms)
    return {
      year: d.getFullYear(),
      month: d.getMonth(),
      date: d.getDate(),
      day: d.getDay(), // 0=周日
      hour: d.getHours(),
      minute: d.getMinutes(),
    }
  }

  const target = toCalendar(config.targetTime)
  const current = toCalendar(now)

  switch (config.scheduleType) {
    case 'ONCE': {
      const t = new Date(config.targetTime)
      t.setSeconds(0, 0)
      const targetMs = t.getTime()
      return targetMs > now ? targetMs : null
    }
    case 'DAILY': {
      let next = new Date(now)
      next.setHours(target.hour, target.minute, 0, 0)
      if (next.getTime() <= now) {
        next.setDate(next.getDate() + 1)
      }
      return next.getTime()
    }
    case 'WEEKLY': {
      const repeatDays = config.repeatDays ?? []
      if (repeatDays.length === 0) return null
      for (let i = 0; i <= 7; i++) {
        const next = new Date(now)
        next.setDate(next.getDate() + i)
        const nextDay = next.getDay()
        if (!repeatDays.includes(nextDay)) continue
        next.setHours(target.hour, target.minute, 0, 0)
        if (next.getTime() > now) return next.getTime()
      }
      return null
    }
    case 'MONTHLY': {
      let next = new Date(now)
      next.setDate(target.date)
      next.setHours(target.hour, target.minute, 0, 0)
      if (next.getTime() <= now) {
        next.setMonth(next.getMonth() + 1)
      }
      return next.getTime()
    }
    default:
      return null
  }
}

/**
 * 判断本次触发的计划时间是否已到
 * 条件：计划时间 <= now
 */
function getOccurrenceKey(skillId: string, config: SkillScheduleConfig): string | null {
  const now = Date.now()
  const toCalendar = (ms: number) => {
    const d = new Date(ms)
    return { year: d.getFullYear(), month: d.getMonth(), date: d.getDate(), day: d.getDay(), hour: d.getHours(), minute: d.getMinutes() }
  }
  const target = toCalendar(config.targetTime)

  switch (config.scheduleType) {
    case 'ONCE': {
      const t = new Date(config.targetTime)
      t.setSeconds(0, 0)
      const targetMs = t.getTime()
      return targetMs <= now ? `${skillId}:ONCE` : null
    }
    case 'DAILY': {
      const scheduled = new Date(now)
      scheduled.setHours(target.hour, target.minute, 0, 0)
      const scheduledMs = scheduled.getTime()
      if (scheduledMs > now) return null
      const y = scheduled.getFullYear()
      const m = String(scheduled.getMonth() + 1).padStart(2, '0')
      const d = String(scheduled.getDate()).padStart(2, '0')
      return `${skillId}:${y}${m}${d}`
    }
    case 'WEEKLY': {
      const repeatDays = config.repeatDays ?? []
      if (repeatDays.length === 0) return null
      const today = new Date(now)
      const todayDay = today.getDay()
      if (!repeatDays.includes(todayDay)) return null
      today.setHours(target.hour, target.minute, 0, 0)
      const scheduledMs = today.getTime()
      if (scheduledMs > now) return null
      const y = today.getFullYear()
      const m = String(today.getMonth() + 1).padStart(2, '0')
      const d = String(today.getDate()).padStart(2, '0')
      return `${skillId}:${y}${m}${d}`
    }
    case 'MONTHLY': {
      const scheduled = new Date(now)
      scheduled.setDate(target.date)
      scheduled.setHours(target.hour, target.minute, 0, 0)
      const scheduledMs = scheduled.getTime()
      if (scheduledMs > now) return null
      const y = scheduled.getFullYear()
      const m = String(scheduled.getMonth() + 1).padStart(2, '0')
      return `${skillId}:${y}${m}`
    }
    default:
      return null
  }
}

/**
 * 检查并处理到点的定时技能
 */
function checkAndTrigger(): void {
  const skills = loadScheduledSkills()
  const dueSkills: Skill[] = []

  for (const skill of skills) {
    if (!isValidScheduledSkill(skill)) continue
    const config = skill.scheduleConfig
    if (!config?.isEnabled) continue

    const key = getOccurrenceKey(skill.id, config)
    if (key == null) continue
    if (triggeredOccurrences.has(key)) continue

    triggeredOccurrences.add(key)
    saveTriggeredOccurrence(key)
    dueSkills.push(skill)
    if (config.scheduleType === 'ONCE') {
      updateSkillSchedule(skill.id, { ...config, isEnabled: false })
    }
  }

  if (dueSkills.length > 0) {
    console.log('[Schedule] 检测到到期技能:', dueSkills.map((s) => s.title || s.id))
  }
  if (onSkillDueCallback && dueSkills.length > 0) {
    console.log('[Schedule] 调用回调，弹窗应显示')
    onSkillDueCallback(dueSkills)
  }
}

/**
 * 启动定时调度
 * @param onSkillDue 到点时回调，传入技能数组；若提供则弹窗由用户确认执行，否则不触发
 */
export function startScheduleService(onSkillDue?: (skills: Skill[]) => void): void {
  if (pollTimer) {
    console.log('[Schedule] 服务已运行，跳过重复启动')
    return
  }
  onSkillDueCallback = onSkillDue ?? null
  console.log('[Schedule] 定时服务已启动', onSkillDue ? '含回调' : '无回调')
  const safeCheckAndTrigger = () => {
    try {
      checkAndTrigger()
    } catch (error) {
      console.warn('[Schedule] 调度检查异常，已忽略本次轮询:', error)
    }
  }
  safeCheckAndTrigger() // 立即检查一次
  pollTimer = setInterval(safeCheckAndTrigger, POLL_INTERVAL_MS)
}

/**
 * 停止定时调度
 */
export function stopScheduleService(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}
