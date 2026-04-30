const TOPOCLAW_SAFE_MODE_ENABLED_KEY = 'topodesktop_topoclaw_safe_mode_enabled_v1'
const TOPOCLAW_SEALED_SESSIONS_KEY = 'topodesktop_topoclaw_safe_mode_sealed_sessions_v1'

export const TOPOCLAW_SAFE_MODE_CHANGED_EVENT = 'topodesktop-topoclaw-safe-mode-changed'

function canUseStorage(): boolean {
  return typeof window !== 'undefined' && !!window.localStorage
}

function emitSafeModeChanged(): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(TOPOCLAW_SAFE_MODE_CHANGED_EVENT))
}

function loadSealedSessionSet(): Set<string> {
  if (!canUseStorage()) return new Set()
  try {
    const raw = window.localStorage.getItem(TOPOCLAW_SEALED_SESSIONS_KEY)
    if (!raw) return new Set()
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr)) return new Set()
    const normalized = arr
      .map((x) => String(x || '').trim())
      .filter((x) => !!x)
    return new Set(normalized)
  } catch {
    return new Set()
  }
}

function saveSealedSessionSet(sessionIds: Set<string>): void {
  if (!canUseStorage()) return
  window.localStorage.setItem(TOPOCLAW_SEALED_SESSIONS_KEY, JSON.stringify(Array.from(sessionIds)))
}

export function isTopoClawSafeModeEnabled(): boolean {
  if (!canUseStorage()) return false
  return window.localStorage.getItem(TOPOCLAW_SAFE_MODE_ENABLED_KEY) === '1'
}

export function setTopoClawSafeModeEnabled(enabled: boolean): void {
  if (!canUseStorage()) return
  const next = enabled ? '1' : '0'
  if (window.localStorage.getItem(TOPOCLAW_SAFE_MODE_ENABLED_KEY) === next) return
  window.localStorage.setItem(TOPOCLAW_SAFE_MODE_ENABLED_KEY, next)
  emitSafeModeChanged()
}

export function isTopoClawSessionSealed(sessionId: string): boolean {
  const id = String(sessionId || '').trim()
  if (!id) return false
  return loadSealedSessionSet().has(id)
}

export function sealTopoClawSession(sessionId: string): void {
  const id = String(sessionId || '').trim()
  if (!id) return
  const set = loadSealedSessionSet()
  if (set.has(id)) return
  set.add(id)
  saveSealedSessionSet(set)
}

export function unsealTopoClawSession(sessionId: string): void {
  const id = String(sessionId || '').trim()
  if (!id) return
  const set = loadSealedSessionSet()
  if (!set.delete(id)) return
  saveSealedSessionSet(set)
}

export function clearTopoClawSealedSessions(): void {
  if (!canUseStorage()) return
  window.localStorage.removeItem(TOPOCLAW_SEALED_SESSIONS_KEY)
}
